using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Friday.Core;

namespace Friday.Tools;

public static class WebTools
{
    private static readonly HttpClient Http = new(new HttpClientHandler
    {
        AllowAutoRedirect = true,
        MaxAutomaticRedirections = 5,
        AutomaticDecompression = DecompressionMethods.All,
    })
    {
        Timeout = TimeSpan.FromSeconds(25),
    };

    static WebTools()
    {
        Http.DefaultRequestHeaders.UserAgent.ParseAdd(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Friday/1.0 (personal assistant)");
        Http.DefaultRequestHeaders.Accept.ParseAdd("text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5");
    }

    public static IEnumerable<ToolDescriptor> Describe()
    {
        yield return new ToolDescriptor
        {
            Name = "web_search",
            Description = "Search the web (DuckDuckGo HTML endpoint) and return result titles, URLs and snippets.",
            Capability = Capability.Web,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"query":{"type":"string"},"count":{"type":"integer"}},"required":["query"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => WebSearchAsync(Args.Str(args, "query"), Args.Int(args, "count", 6), ctx),
        };
        yield return new ToolDescriptor
        {
            Name = "fetch_page",
            Description = "Download a web page and return its readable text (HTML tags removed).",
            Capability = Capability.Web,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"url":{"type":"string"},"max_chars":{"type":"integer"}},"required":["url"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => FetchAsync(Args.Str(args, "url"), Args.Int(args, "max_chars", 12000), ctx),
        };
        yield return new ToolDescriptor
        {
            Name = "download_file",
            Description = "Download a file from a URL into an authorised folder. Asks for confirmation first.",
            Capability = Capability.Web,
            Risk = RiskLevel.Confirm,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"url":{"type":"string"},"path":{"type":"string"}},"required":["url","path"],"additionalProperties":false}
            """),
            Handler = DownloadAsync,
        };
    }

    private static async Task<ToolResult> WebSearchAsync(string query, int count, ToolContext ctx)
    {
        query = (query ?? "").Trim();
        if (query.Length == 0) return ToolResult.Fail("What should I search for?");

        var url = "https://html.duckduckgo.com/html/?q=" + Uri.EscapeDataString(query);
        try
        {
            using var response = await Http.GetAsync(url, ctx.Token).ConfigureAwait(false);
            var html = await response.Content.ReadAsStringAsync(ctx.Token).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
                return ToolResult.Fail($"The search endpoint returned HTTP {(int)response.StatusCode}.");

            var results = new List<(string Title, string Link, string Snippet)>();
            var matches = Regex.Matches(html,
                "<a[^>]+class=\"result__a\"[^>]+href=\"(?<link>[^\"]+)\"[^>]*>(?<title>.*?)</a>",
                RegexOptions.Singleline | RegexOptions.IgnoreCase);
            var snippetMatches = Regex.Matches(html,
                "<a[^>]+class=\"result__snippet\"[^>]*>(?<snip>.*?)</a>",
                RegexOptions.Singleline | RegexOptions.IgnoreCase);

            for (var i = 0; i < matches.Count && results.Count < Math.Clamp(count, 1, 12); i++)
            {
                var link = WebUtility.HtmlDecode(matches[i].Groups["link"].Value);
                var title = Clean(matches[i].Groups["title"].Value);
                var snippet = i < snippetMatches.Count ? Clean(snippetMatches[i].Groups["snip"].Value) : "";
                if (title.Length == 0) continue;
                results.Add((title, link, snippet));
            }

            if (results.Count == 0)
            {
                return ToolResult.Fail(
                    "The search page returned no parseable results. DuckDuckGo may be blocking " +
                    "automated queries from this network.");
            }

            var text = string.Join(Environment.NewLine + Environment.NewLine,
                results.Select(r => $"{r.Title}\n{r.Link}\n{r.Snippet}"));
            return ToolResult.Good(text, new JsonArray(results.Select(r => (JsonNode)new JsonObject
            {
                ["title"] = r.Title,
                ["url"] = r.Link,
                ["snippet"] = r.Snippet,
            }).ToArray()).ToJsonString());
        }
        catch (Exception ex)
        {
            return ToolResult.Fail("Web search failed: " + ex.Message);
        }
    }

    private static async Task<ToolResult> FetchAsync(string url, int maxChars, ToolContext ctx)
    {
        url = NormaliseUrl(url);
        if (url.Length == 0) return ToolResult.Fail("No URL was given.");
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https"))
            return ToolResult.Fail("Only http and https pages can be fetched.");

        try
        {
            using var response = await Http.GetAsync(uri, ctx.Token).ConfigureAwait(false);
            var body = await response.Content.ReadAsStringAsync(ctx.Token).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
                return ToolResult.Fail($"HTTP {(int)response.StatusCode} from {uri.Host}.");

            var text = Clean(body);
            maxChars = Math.Clamp(maxChars, 1000, 30000);
            var truncated = text.Length > maxChars;
            if (truncated) text = text[..maxChars] + "\n… (truncated)";
            return ToolResult.Good($"From {uri.Host}:\n\n{text}", new JsonObject
            {
                ["url"] = uri.ToString(),
                ["chars"] = text.Length,
                ["truncated"] = truncated,
            }.ToJsonString());
        }
        catch (Exception ex)
        {
            return ToolResult.Fail($"Could not fetch {uri.Host}: {ex.Message}");
        }
    }

    private static async Task<ToolResult> DownloadAsync(ToolContext ctx, JsonObject args)
    {
        var url = NormaliseUrl(Args.Str(args, "url"));
        if (!ctx.Config.Policy.AllowDownloads)
            return ToolResult.Refused("Downloads are switched off in Settings > Access.");
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https"))
            return ToolResult.Fail("Only http and https downloads are allowed.");

        if (!ctx.Runtime.Paths.TryResolve(Args.Str(args, "path"), out var target, out var error))
            return ToolResult.Refused(error);
        if (File.Exists(target)) return ToolResult.Fail($"{target} already exists.");

        Directory.CreateDirectory(Path.GetDirectoryName(target) ?? ".");
        try
        {
            using var response = await Http.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, ctx.Token)
                .ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
                return ToolResult.Fail($"HTTP {(int)response.StatusCode} from {uri.Host}.");

            var length = response.Content.Headers.ContentLength ?? 0;
            if (length > 200L * 1024 * 1024)
                return ToolResult.Refused("That file is larger than Friday's 200 MB download limit.");

            await using var input = await response.Content.ReadAsStreamAsync(ctx.Token).ConfigureAwait(false);
            await using var output = File.Create(target);
            await input.CopyToAsync(output, ctx.Token).ConfigureAwait(false);
            var info = new FileInfo(target);
            return ToolResult.Good($"Downloaded {info.Length / 1024} KB to {target}.",
                new JsonObject { ["path"] = target, ["bytes"] = info.Length }.ToJsonString());
        }
        catch (Exception ex)
        {
            try { if (File.Exists(target)) File.Delete(target); } catch { }
            return ToolResult.Fail("Download failed: " + ex.Message);
        }
    }

    private static string NormaliseUrl(string url)
    {
        url = (url ?? "").Trim();
        if (url.Length == 0) return "";
        if (!url.Contains("://")) url = "https://" + url;
        return url;
    }

    /// <summary>Strips scripts/styles/tags and collapses whitespace.</summary>
    public static string Clean(string html)
    {
        if (string.IsNullOrEmpty(html)) return "";
        var text = Regex.Replace(html, "<script.*?</script>", " ",
            RegexOptions.Singleline | RegexOptions.IgnoreCase);
        text = Regex.Replace(text, "<style.*?</style>", " ",
            RegexOptions.Singleline | RegexOptions.IgnoreCase);
        text = Regex.Replace(text, "<br\\s*/?>", "\n", RegexOptions.IgnoreCase);
        text = Regex.Replace(text, "</(p|div|li|h[1-6]|tr)>", "\n", RegexOptions.IgnoreCase);
        text = Regex.Replace(text, "<[^>]+>", " ");
        text = WebUtility.HtmlDecode(text);
        text = Regex.Replace(text, "[ \\t\\x0B\\f\\r]+", " ");
        text = Regex.Replace(text, "\\n\\s*\\n\\s*\\n+", "\n\n");
        return text.Trim();
    }
}
