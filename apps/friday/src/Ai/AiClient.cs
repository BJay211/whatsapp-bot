using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Friday.Core;
using Friday.Voice;

namespace Friday.Ai;

public sealed class AiToolCall
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Arguments { get; set; } = "{}";
}

public sealed class ChatMessage
{
    public string Role { get; set; } = "user";
    public string? Content { get; set; }
    public string? ToolCallId { get; set; }
    public List<AiToolCall>? ToolCalls { get; set; }

    public static ChatMessage System(string content) => new() { Role = "system", Content = content };
    public static ChatMessage User(string content) => new() { Role = "user", Content = content };
    public static ChatMessage Assistant(string content) => new() { Role = "assistant", Content = content };
    public static ChatMessage Tool(string callId, string content) => new()
    {
        Role = "tool",
        ToolCallId = callId,
        Content = content,
    };

    public JsonObject ToJson()
    {
        var json = new JsonObject { ["role"] = Role };
        if (Role == "assistant" && ToolCalls is { Count: > 0 })
        {
            json["content"] = Content is null ? null : JsonValue.Create(Content);
            var calls = new JsonArray();
            foreach (var call in ToolCalls)
            {
                calls.Add(new JsonObject
                {
                    ["id"] = call.Id,
                    ["type"] = "function",
                    ["function"] = new JsonObject
                    {
                        ["name"] = call.Name,
                        ["arguments"] = call.Arguments,
                    },
                });
            }
            json["tool_calls"] = calls;
        }
        else if (Role == "tool")
        {
            json["content"] = Content ?? "";
            json["tool_call_id"] = ToolCallId ?? "";
        }
        else
        {
            json["content"] = Content ?? "";
        }
        return json;
    }
}

public sealed record AiReply(
    bool Ok,
    string Text,
    IReadOnlyList<AiToolCall> ToolCalls,
    string Error,
    int StatusCode,
    string Model,
    int Tokens);

/// <summary>
/// Minimal OpenAI-compatible chat client with function calling. The key is read
/// from the environment or DPAPI-protected config; it is never logged and never
/// placed in a URL.
/// </summary>
public sealed class AiClient
{
    private readonly FridayConfig _config;
    private readonly HttpClient _http = new(new SocketsHttpHandler
    {
        // Compressed replies arrive faster on a slow link, and the connection is
        // kept warm between turns so only the first call pays for TLS.
        AutomaticDecompression = System.Net.DecompressionMethods.GZip | System.Net.DecompressionMethods.Deflate,
        PooledConnectionLifetime = TimeSpan.FromMinutes(10),
        ConnectTimeout = TimeSpan.FromSeconds(15),
    })
    {
        Timeout = TimeSpan.FromSeconds(120),
    };

    public AiClient(FridayConfig config) => _config = config;

    public bool IsConfigured()
    {
        var key = _config.EffectiveApiKey(out _);
        return !string.IsNullOrWhiteSpace(key) && !string.IsNullOrWhiteSpace(_config.EffectiveModel());
    }

    public string DescribeConfiguration()
    {
        var key = _config.EffectiveApiKey(out var source);
        return string.IsNullOrWhiteSpace(key)
            ? "No AI key is configured. Set FRIDAY_AI_KEY or add one in Settings."
            : $"Using {_config.EffectiveModel()} at {_config.EffectiveBaseUrl()} (key from {source}).";
    }

    public async Task<AiReply> CompleteAsync(
        IReadOnlyList<ChatMessage> messages,
        JsonArray? tools,
        CancellationToken token)
    {
        var key = _config.EffectiveApiKey(out _);
        if (string.IsNullOrWhiteSpace(key))
            return new AiReply(false, "", Array.Empty<AiToolCall>(),
                "No AI API key is configured.", 0, "", 0);

        var model = _config.EffectiveModel();
        var url = _config.EffectiveBaseUrl() + "/chat/completions";

        var payload = new JsonObject
        {
            ["model"] = model,
            ["messages"] = new JsonArray(messages.Select(m => (JsonNode)m.ToJson()).ToArray()),
            ["temperature"] = _config.Ai.Temperature,
            ["max_tokens"] = _config.Ai.MaxTokens,
        };
        if (tools is { Count: > 0 })
        {
            payload["tools"] = tools.DeepClone();
            payload["tool_choice"] = "auto";
        }

        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, url)
            {
                Content = new StringContent(payload.ToJsonString(), Encoding.UTF8, "application/json"),
            };
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", key);
            request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));

            using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseContentRead, token)
                .ConfigureAwait(false);
            var body = await response.Content.ReadAsStringAsync(token).ConfigureAwait(false);

            if (!response.IsSuccessStatusCode)
            {
                var message = DescribeError((int)response.StatusCode, body, model);
                return new AiReply(false, "", Array.Empty<AiToolCall>(), message, (int)response.StatusCode, model, 0);
            }

            return Parse(body, model);
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            return new AiReply(false, "", Array.Empty<AiToolCall>(), ConnectionError(ex), 0, model, 0);
        }
    }

    /// <summary>
    /// Streams one chat completion. Complete sentences are handed to
    /// <paramref name="onSentence"/> while the provider is still writing, so
    /// Friday can start speaking before the answer is finished. Tool-call deltas
    /// are accumulated and returned exactly like the non-streaming call.
    /// </summary>
    public async Task<AiReply> CompleteStreamingAsync(
        IReadOnlyList<ChatMessage> messages,
        JsonArray? tools,
        Action<string> onSentence,
        CancellationToken token)
    {
        var key = _config.EffectiveApiKey(out _);
        if (string.IsNullOrWhiteSpace(key))
            return new AiReply(false, "", Array.Empty<AiToolCall>(),
                "No AI API key is configured.", 0, "", 0);

        var model = _config.EffectiveModel();
        var url = _config.EffectiveBaseUrl() + "/chat/completions";

        var payload = new JsonObject
        {
            ["model"] = model,
            ["messages"] = new JsonArray(messages.Select(m => (JsonNode)m.ToJson()).ToArray()),
            ["temperature"] = _config.Ai.Temperature,
            ["max_tokens"] = _config.Ai.MaxTokens,
            ["stream"] = true,
        };
        if (tools is { Count: > 0 })
        {
            payload["tools"] = tools.DeepClone();
            payload["tool_choice"] = "auto";
        }

        var full = new StringBuilder();
        var pending = new StringBuilder();
        var calls = new Dictionary<int, (string Id, string Name, StringBuilder Args)>();
        var served = model;
        var tokens = 0;

        void Push(string delta)
        {
            full.Append(delta);
            pending.Append(delta);
            var text = pending.ToString();
            var cut = LastSentenceBoundary(text);
            if (cut <= 0) return;
            var complete = text[..cut];
            pending.Remove(0, cut);
            foreach (var sentence in SpeechText.Chunks(SpeechText.Split(complete)))
                onSentence(sentence);
        }

        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, url)
            {
                Content = new StringContent(payload.ToJsonString(), Encoding.UTF8, "application/json"),
            };
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", key);
            request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("text/event-stream"));

            using var response = await _http.SendAsync(
                request, HttpCompletionOption.ResponseHeadersRead, token).ConfigureAwait(false);

            if (!response.IsSuccessStatusCode)
            {
                var body = await response.Content.ReadAsStringAsync(token).ConfigureAwait(false);
                return new AiReply(false, "", Array.Empty<AiToolCall>(),
                    DescribeError((int)response.StatusCode, body, model), (int)response.StatusCode, model, 0);
            }

            await using var stream = await response.Content.ReadAsStreamAsync(token).ConfigureAwait(false);
            using var reader = new StreamReader(stream);

            while (true)
            {
                token.ThrowIfCancellationRequested();
                var line = await reader.ReadLineAsync(token).ConfigureAwait(false);
                if (line is null) break;
                if (line.Length == 0) continue;
                if (!line.StartsWith("data:", StringComparison.OrdinalIgnoreCase)) continue;

                var data = line[5..].Trim();
                if (data == "[DONE]") break;

                JsonNode? node;
                try { node = JsonNode.Parse(data); }
                catch { continue; }
                var json = node?.AsObject();
                if (json is null) continue;

                served = json["model"]?.GetValue<string>() ?? served;
                if (json["usage"]?["total_tokens"]?.GetValue<int>() is { } used && used > 0)
                    tokens = used;

                var choices = json["choices"]?.AsArray();
                if (choices is null || choices.Count == 0) continue;
                var delta = choices[0]?["delta"]?.AsObject();
                if (delta is null) continue;

                if (delta["content"]?.GetValue<string>() is { Length: > 0 } content)
                    Push(content);

                if (delta["tool_calls"] is JsonArray toolDeltas)
                {
                    foreach (var item in toolDeltas)
                    {
                        if (item is not JsonObject tool) continue;
                        var index = tool["index"]?.GetValue<int>() ?? calls.Count;
                        if (!calls.TryGetValue(index, out var call))
                        {
                            call = ("", "", new StringBuilder());
                        }
                        var id = tool["id"]?.GetValue<string>();
                        var name = tool["function"]?["name"]?.GetValue<string>();
                        var args = tool["function"]?["arguments"]?.GetValue<string>();
                        calls[index] = (
                            string.IsNullOrWhiteSpace(id) ? call.Id : id,
                            string.IsNullOrWhiteSpace(name) ? call.Name : name,
                            call.Args.Append(args ?? ""));
                    }
                }
            }

            foreach (var sentence in SpeechText.Chunks(SpeechText.Split(pending.ToString())))
                onSentence(sentence);

            var text = full.ToString().Trim();
            var toolCalls = calls
                .OrderBy(pair => pair.Key)
                .Where(pair => pair.Value.Name.Length > 0)
                .Select(pair => new AiToolCall
                {
                    Id = pair.Value.Id.Length > 0
                        ? pair.Value.Id
                        : "call_" + Guid.NewGuid().ToString("n")[..8],
                    Name = pair.Value.Name,
                    Arguments = pair.Value.Args.Length > 0 ? pair.Value.Args.ToString() : "{}",
                })
                .ToList();

            if (text.Length == 0 && toolCalls.Count == 0)
            {
                // The endpoint may not implement SSE at all.
                return await CompleteAsync(messages, tools, token).ConfigureAwait(false);
            }

            return new AiReply(true, text, toolCalls, "", 200, served, tokens);
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            var partial = full.ToString().Trim();
            var partialCalls = calls
                .Where(pair => pair.Value.Name.Length > 0)
                .Select(pair => new AiToolCall
                {
                    Id = pair.Value.Id,
                    Name = pair.Value.Name,
                    Arguments = pair.Value.Args.ToString(),
                })
                .ToList();
            return new AiReply(
                false,
                partial,
                partialCalls,
                ConnectionError(ex),
                0,
                model,
                tokens);
        }
    }

    private static int LastSentenceBoundary(string text)
    {
        var cut = -1;
        for (var i = 0; i < text.Length; i++)
        {
            var ch = text[i];
            if (ch is '.' or '!' or '?' or '\n')
            {
                if (i + 1 >= text.Length || char.IsWhiteSpace(text[i + 1])) cut = i + 1;
            }
        }
        if (cut > 0 && cut < 12)
        {
            var next = text.IndexOf('\n', cut);
            if (next > cut) cut = next + 1;
        }
        return cut;
    }

    private AiReply Parse(string body, string model)
    {
        try
        {
            var json = JsonNode.Parse(body)?.AsObject();
            var choice = json?["choices"]?.AsArray().FirstOrDefault()?["message"]?.AsObject();
            if (choice is null)
                return new AiReply(false, "", Array.Empty<AiToolCall>(),
                    "The provider returned no message.", 200, model, 0);

            var content = choice["content"]?.GetValue<string>() ?? "";
            var calls = new List<AiToolCall>();
            if (choice["tool_calls"] is JsonArray array)
            {
                foreach (var item in array)
                {
                    var function = item?["function"];
                    if (function is null) continue;
                    calls.Add(new AiToolCall
                    {
                        Id = item?["id"]?.GetValue<string>() ?? "call_" + Guid.NewGuid().ToString("n")[..8],
                        Name = function["name"]?.GetValue<string>() ?? "",
                        Arguments = function["arguments"]?.GetValue<string>() ?? "{}",
                    });
                }
            }

            if (content.Length == 0 && calls.Count == 0)
                return new AiReply(false, "", calls, "The provider returned an empty answer.", 200, model, 0);

            var tokens = json?["usage"]?["total_tokens"]?.GetValue<int>() ?? 0;
            var served = json?["model"]?.GetValue<string>() ?? model;
            return new AiReply(true, content.Trim(), calls, "", 200, served, tokens);
        }
        catch (Exception ex)
        {
            return new AiReply(false, "", Array.Empty<AiToolCall>(),
                "The provider sent a response I could not read: " + ex.Message, 200, model, 0);
        }
    }

    private string DescribeError(int code, string body, string model)
    {
        string providerMessage;
        try
        {
            var json = JsonNode.Parse(body);
            providerMessage = json?["error"]?["message"]?.GetValue<string>()
                              ?? json?["message"]?.GetValue<string>()
                              ?? body[..Math.Min(body.Length, 300)];
        }
        catch
        {
            providerMessage = body.Length == 0 ? "(empty body)" : body[..Math.Min(body.Length, 300)];
        }

        return code switch
        {
            401 or 403 => $"The API key was rejected (HTTP {code}). Check the key and that it belongs to {_config.EffectiveBaseUrl()}.\n{providerMessage}",
            404 => $"HTTP 404. Either the base URL is wrong or \"{model}\" is not available on this account.\n{providerMessage}",
            429 => $"The provider rate-limited me (HTTP 429). Try again shortly.\n{providerMessage}",
            >= 500 => $"The provider had a server error (HTTP {code}).\n{providerMessage}",
            _ => $"HTTP {code} from {_config.EffectiveBaseUrl()}\n{providerMessage}",
        };
    }

    private static string ConnectionError(Exception ex)
    {
        var raw = ex.Message.ToLowerInvariant();
        return raw switch
        {
            var s when s.Contains("timed out") || s.Contains("timeout") =>
                "The AI provider did not answer in time.",
            var s when s.Contains("name or service") || s.Contains("resolve") =>
                "The AI address does not resolve. Check the base URL and your internet.",
            var s when s.Contains("ssl") || s.Contains("certificate") =>
                "TLS failed for the AI provider.",
            _ => "Could not reach the AI provider: " + ex.Message,
        };
    }
}
