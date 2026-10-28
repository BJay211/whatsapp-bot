using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Friday.Net;

internal sealed class HttpRequestLite
{
    public string Method { get; init; } = "GET";
    public string Path { get; init; } = "/";
    public string Version { get; init; } = "HTTP/1.1";
    public Dictionary<string, string> Query { get; init; } = new(StringComparer.OrdinalIgnoreCase);
    public Dictionary<string, string> Headers { get; init; } = new(StringComparer.OrdinalIgnoreCase);
    public string Body { get; init; } = "";

    /// <summary>True when the client is happy for the socket to stay open.</summary>
    public bool KeepAlive { get; init; }

    /// <summary>
    /// True when bytes for a following request arrived in the same read. They are
    /// dropped by this reader, so the connection must not be reused.
    /// </summary>
    public bool HasExtraBytes { get; init; }

    public string? Header(string name) => Headers.TryGetValue(name, out var value) ? value : null;

    public static async Task<HttpRequestLite?> ReadAsync(
        Stream stream, int maxBodyBytes, CancellationToken token)
    {
        var buffer = new byte[8192];
        using var head = new MemoryStream();
        var headerEnd = -1;

        while (headerEnd < 0)
        {
            var read = await stream.ReadAsync(buffer, token).ConfigureAwait(false);
            if (read <= 0) return null;
            head.Write(buffer, 0, read);
            if (head.Length > 64 * 1024) throw new InvalidDataException("Request headers too large.");
            headerEnd = IndexOf(head.GetBuffer(), (int)head.Length, "\r\n\r\n"u8);
        }

        var all = head.ToArray();
        var headerText = Encoding.ASCII.GetString(all, 0, headerEnd);
        var lines = headerText.Split("\r\n", StringSplitOptions.None);
        if (lines.Length == 0) return null;

        var requestLine = lines[0].Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (requestLine.Length < 2) return null;

        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var line in lines.Skip(1))
        {
            var colon = line.IndexOf(':');
            if (colon <= 0) continue;
            headers[line[..colon].Trim()] = line[(colon + 1)..].Trim();
        }

        if (headers.TryGetValue("Transfer-Encoding", out var transfer) &&
            transfer.Contains("chunked", StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidDataException("Chunked bodies are not accepted.");
        }

        var contentLength = 0;
        if (headers.TryGetValue("Content-Length", out var lengthText))
        {
            if (!int.TryParse(lengthText, out contentLength) || contentLength < 0)
                throw new InvalidDataException("Invalid Content-Length.");
        }
        if (contentLength > maxBodyBytes)
            throw new InvalidDataException($"Body is larger than {maxBodyBytes / 1024} KB.");

        var target = requestLine[1];
        var query = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var path = target;
        var question = target.IndexOf('?');
        if (question >= 0)
        {
            path = target[..question];
            foreach (var pair in target[(question + 1)..].Split('&', StringSplitOptions.RemoveEmptyEntries))
            {
                var equals = pair.IndexOf('=');
                if (equals < 0) query[Uri.UnescapeDataString(pair)] = "";
                else query[Uri.UnescapeDataString(pair[..equals])] = Uri.UnescapeDataString(pair[(equals + 1)..]);
            }
        }

        var alreadyRead = all.Length - (headerEnd + 4);
        var body = new byte[contentLength];
        if (alreadyRead > 0)
            Array.Copy(all, headerEnd + 4, body, 0, Math.Min(alreadyRead, contentLength));

        var offset = Math.Min(alreadyRead, contentLength);
        while (offset < contentLength)
        {
            var read = await stream.ReadAsync(body.AsMemory(offset, contentLength - offset), token)
                .ConfigureAwait(false);
            if (read <= 0) break;
            offset += read;
        }

        return new HttpRequestLite
        {
            Method = requestLine[0].ToUpperInvariant(),
            Path = path,
            Query = query,
            Headers = headers,
            Body = Encoding.UTF8.GetString(body, 0, offset),
            Version = requestLine.Length > 2 ? requestLine[2] : "HTTP/1.1",
            KeepAlive = WantsKeepAlive(requestLine, headers),
            HasExtraBytes = all.Length > headerEnd + 4 + contentLength,
        };
    }

    /// <summary>
    /// HTTP/1.1 defaults to keeping the connection; HTTP/1.0 does not. An explicit
    /// "Connection" header always wins. This is what lets the phone reuse one TLS
    /// session for a whole polling run instead of shaking hands every 900 ms.
    /// </summary>
    private static bool WantsKeepAlive(string[] requestLine, Dictionary<string, string> headers)
    {
        var version = requestLine.Length > 2 ? requestLine[2] : "HTTP/1.1";
        var explicitHeader = headers.TryGetValue("Connection", out var connection) ? connection : "";
        if (explicitHeader.Contains("close", StringComparison.OrdinalIgnoreCase)) return false;
        if (explicitHeader.Contains("keep-alive", StringComparison.OrdinalIgnoreCase)) return true;
        return !version.Equals("HTTP/1.0", StringComparison.OrdinalIgnoreCase);
    }

    private static int IndexOf(byte[] haystack, int length, ReadOnlySpan<byte> needle)
    {
        var span = haystack.AsSpan(0, length);
        var index = span.IndexOf(needle);
        return index;
    }
}
