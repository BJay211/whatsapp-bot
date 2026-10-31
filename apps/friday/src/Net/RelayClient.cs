using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Friday.Core;

namespace Friday.Net;

/// <summary>
/// Outbound half of the internet relay.
///
/// Friday never opens a port to the internet. It long-polls a relay service,
/// receives one already-authenticated phone request at a time, hands it to the
/// normal loopback <see cref="FridayServer"/> (so device tokens, request ids,
/// approvals and audit all still apply), and sends the response back. The relay
/// only routes bytes; it has no command logic and no device tokens.
///
/// The phone↔relay hop is HTTPS. The laptop↔relay hop is HTTPS with the relay
/// key in a header. The relay↔Friday hop never leaves the machine.
/// </summary>
public sealed class RelayClient : IDisposable
{
    private const int MaxBodyBytes = 2 * 1024 * 1024;

    private readonly FridayRuntime _runtime;
    private readonly HttpClient _relay;
    private readonly HttpClient _local;
    private readonly object _gate = new();

    private CancellationTokenSource? _cts;
    private Task? _loop;
    private string _status = "Relay is off.";
    private DateTimeOffset _lastContact = DateTimeOffset.MinValue;
    private long _forwarded;

    public RelayClient(FridayRuntime runtime)
    {
        _runtime = runtime;
        _relay = new HttpClient(new SocketsHttpHandler
        {
            ConnectTimeout = TimeSpan.FromSeconds(15),
            PooledConnectionLifetime = TimeSpan.FromMinutes(5),
        })
        {
            Timeout = Timeout.InfiniteTimeSpan,
        };

        // The local Friday service uses a self-signed certificate. This client
        // is only ever pointed at 127.0.0.1, so certificate validation for the
        // loopback hop is intentionally skipped; the phone's TLS pin still
        // protects the phone↔laptop identity end to end.
        var handler = new SocketsHttpHandler
        {
            ConnectTimeout = TimeSpan.FromSeconds(5),
            PooledConnectionLifetime = TimeSpan.FromMinutes(2),
        };
        handler.SslOptions.RemoteCertificateValidationCallback = (_, _, _, _) => true;
        _local = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(180) };
    }

    public bool Running => _loop is { IsCompleted: false };

    public string StatusText
    {
        get { lock (_gate) return _status; }
    }

    public long Forwarded
    {
        get { lock (_gate) return _forwarded; }
    }

    public bool Configured =>
        _runtime.Config.Relay.Enabled &&
        IsHttpUrl(_runtime.Config.Relay.Url) &&
        !string.IsNullOrWhiteSpace(_runtime.Config.Relay.LaptopId) &&
        !string.IsNullOrWhiteSpace(_runtime.Config.EffectiveRelayKey());

    public void Start()
    {
        if (!Configured)
        {
            SetStatus(_runtime.Config.Relay.Enabled
                ? "Relay is enabled but not configured yet."
                : "Relay is off.");
            return;
        }
        if (Running) return;

        _cts = new CancellationTokenSource();
        var token = _cts.Token;
        _loop = Task.Run(() => LoopAsync(token), token);
        SetStatus("Relay: connecting…");
        FLog.Info("relay", "Outbound relay started for laptop " + _runtime.Config.Relay.LaptopId);
    }

    public void Stop()
    {
        try { _cts?.Cancel(); } catch { }
        try { _loop?.Wait(TimeSpan.FromSeconds(3)); } catch { }
        _loop = null;
        _cts?.Dispose();
        _cts = null;
        SetStatus("Relay is off.");
    }

    private async Task LoopAsync(CancellationToken token)
    {
        var failures = 0;
        while (!token.IsCancellationRequested)
        {
            try
            {
                var envelope = await PollAsync(token).ConfigureAwait(false);
                if (envelope is not null)
                {
                    await HandleAsync(envelope, token).ConfigureAwait(false);
                }
                failures = 0;
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested)
            {
                return;
            }
            catch (Exception ex)
            {
                failures++;
                SetStatus("Relay: " + Short(ex.Message));
                var wait = TimeSpan.FromSeconds(Math.Min(30, 1 + failures * 2));
                try { await Task.Delay(wait, token).ConfigureAwait(false); }
                catch (OperationCanceledException) { return; }
            }
        }
    }

    private async Task<JsonObject?> PollAsync(CancellationToken token)
    {
        var baseUrl = _runtime.Config.Relay.Url.TrimEnd('/');
        using var request = new HttpRequestMessage(HttpMethod.Post, baseUrl + "/laptop/poll?wait=25");
        request.Headers.TryAddWithoutValidation("X-Friday-Relay-Id", _runtime.Config.Relay.LaptopId);
        request.Headers.TryAddWithoutValidation("X-Friday-Relay-Key", _runtime.Config.EffectiveRelayKey());
        request.Content = new StringContent("{}", Encoding.UTF8, "application/json");

        using var response = await _relay.SendAsync(
            request, HttpCompletionOption.ResponseHeadersRead, token).ConfigureAwait(false);

        if (response.StatusCode == System.Net.HttpStatusCode.NoContent ||
            response.StatusCode == System.Net.HttpStatusCode.GatewayTimeout)
        {
            MarkContact();
            return null;
        }

        var body = await response.Content.ReadAsStringAsync(token).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException(
                "relay HTTP " + (int)response.StatusCode + ": " + Clip(body, 200));
        }

        MarkContact();
        return JsonNode.Parse(body)?.AsObject();
    }

    private async Task HandleAsync(JsonObject envelope, CancellationToken token)
    {
        var id = envelope["id"]?.GetValue<string>() ?? "";
        var method = envelope["method"]?.GetValue<string>() ?? "GET";
        var path = envelope["path"]?.GetValue<string>() ?? "";
        if (id.Length == 0) return;

        int status;
        var responseHeaders = new Dictionary<string, string>();
        byte[] responseBody;

        try
        {
            if (!path.StartsWith("/v1/", StringComparison.Ordinal) ||
                path.Contains("..", StringComparison.Ordinal))
            {
                status = 400;
                responseBody = Encoding.UTF8.GetBytes("{\"error\":\"relay path not allowed\"}");
                responseHeaders["content-type"] = "application/json";
            }
            else
            {
                var scheme = _runtime.Config.Server.UseTls ? "https" : "http";
                var url = $"{scheme}://127.0.0.1:{_runtime.Config.Server.Port}{path}";
                using var local = new HttpRequestMessage(new HttpMethod(method), url);

                var bodyB64 = envelope["body_b64"]?.GetValue<string>() ?? "";
                if (bodyB64.Length > 0)
                {
                    var bytes = Convert.FromBase64String(bodyB64);
                    if (bytes.Length > MaxBodyBytes)
                        throw new InvalidOperationException("request body is too large");
                    local.Content = new ByteArrayContent(bytes);
                }

                if (envelope["headers"] is JsonObject headers)
                {
                    foreach (var pair in headers)
                    {
                        var name = pair.Key;
                        var value = pair.Value?.GetValue<string>() ?? "";
                        if (value.Length == 0) continue;
                        if (name.Equals("authorization", StringComparison.OrdinalIgnoreCase))
                            local.Headers.TryAddWithoutValidation("Authorization", value);
                        else if (name.Equals("content-type", StringComparison.OrdinalIgnoreCase))
                            local.Content?.Headers.TryAddWithoutValidation("Content-Type", value);
                        else if (name.Equals("accept", StringComparison.OrdinalIgnoreCase))
                            local.Headers.TryAddWithoutValidation("Accept", value);
                        else if (name.Equals("x-request-id", StringComparison.OrdinalIgnoreCase))
                            local.Headers.TryAddWithoutValidation("X-Request-Id", value);
                        else if (name.Equals("x-friday-device", StringComparison.OrdinalIgnoreCase))
                            local.Headers.TryAddWithoutValidation("X-Friday-Device", value);
                    }
                }

                using var reply = await _local.SendAsync(
                    local, HttpCompletionOption.ResponseHeadersRead, token).ConfigureAwait(false);
                status = (int)reply.StatusCode;
                responseBody = await reply.Content.ReadAsByteArrayAsync(token).ConfigureAwait(false);
                if (responseBody.Length > MaxBodyBytes)
                    throw new InvalidOperationException("response is too large");
                if (reply.Content.Headers.ContentType?.ToString() is { Length: > 0 } contentType)
                    responseHeaders["content-type"] = contentType;
            }
        }
        catch (Exception ex)
        {
            status = 502;
            responseHeaders["content-type"] = "application/json";
            responseBody = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new
            {
                error = "Friday could not run that request",
                detail = ex.Message,
            }));
        }

        await RespondAsync(id, status, responseHeaders, responseBody, token).ConfigureAwait(false);
        lock (_gate) _forwarded++;
        SetStatus("Relay connected · forwarded " + Forwarded + " request" + (Forwarded == 1 ? "" : "s"));
    }

    private async Task RespondAsync(
        string id, int status, Dictionary<string, string> headers, byte[] body, CancellationToken token)
    {
        var baseUrl = _runtime.Config.Relay.Url.TrimEnd('/');
        var payload = new JsonObject
        {
            ["id"] = id,
            ["status"] = status,
            ["headers"] = JsonSerializer.SerializeToNode(headers),
            ["body_b64"] = Convert.ToBase64String(body),
        };
        using var request = new HttpRequestMessage(HttpMethod.Post, baseUrl + "/laptop/respond")
        {
            Content = new StringContent(payload.ToJsonString(), Encoding.UTF8, "application/json"),
        };
        request.Headers.TryAddWithoutValidation("X-Friday-Relay-Id", _runtime.Config.Relay.LaptopId);
        request.Headers.TryAddWithoutValidation("X-Friday-Relay-Key", _runtime.Config.EffectiveRelayKey());

        using var response = await _relay.SendAsync(request, token).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            var text = await response.Content.ReadAsStringAsync(token).ConfigureAwait(false);
            throw new InvalidOperationException("relay respond HTTP " + (int)response.StatusCode + ": " + Clip(text, 160));
        }
    }

    private void MarkContact() => _lastContact = DateTimeOffset.UtcNow;

    private void SetStatus(string text)
    {
        var changed = false;
        lock (_gate)
        {
            if (_status != text)
            {
                _status = text;
                changed = true;
            }
        }
        if (changed) FLog.Info("relay", text);
    }

    private static bool IsHttpUrl(string url) =>
        Uri.TryCreate(url, UriKind.Absolute, out var uri) &&
        uri.Scheme is "https" or "http" &&
        !string.IsNullOrWhiteSpace(uri.Host);

    private static string Short(string text) => Clip(text.Replace('\r', ' ').Replace('\n', ' '), 120);

    private static string Clip(string text, int max) =>
        text.Length <= max ? text : text[..max] + "…";

    public void Dispose()
    {
        Stop();
        _relay.Dispose();
        _local.Dispose();
    }
}
