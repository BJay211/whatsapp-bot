using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Friday.Core;
using Friday.Tools;

namespace Friday.Ai;

public sealed record AssistantReply(bool Ok, string Text, string? Data = null, bool UsedAi = false, bool Spoken = false);

/// <summary>
/// The reasoning loop. It separates what the model asks for from what is allowed
/// to happen: the model may only return tool calls, and every call is re-checked
/// by <see cref="ToolRegistry"/> before it touches the laptop.
/// </summary>
public sealed class Orchestrator
{
    private readonly FridayRuntime _runtime;
    private readonly AiClient _ai;
    private readonly List<ChatMessage> _history = new();
    private readonly object _gate = new();

    public Orchestrator(FridayRuntime runtime)
    {
        _runtime = runtime;
        _ai = new AiClient(runtime.Config);
    }

    public bool AiConfigured => _ai.IsConfigured();

    public async Task<AssistantReply> AskAsync(string utterance, ToolContext context)
    {
        utterance = (utterance ?? "").Trim();
        if (utterance.Length == 0) return new AssistantReply(false, "I didn't catch that.");

        var state = _runtime.State;
        state.Set(FridayState.Thinking, "Understanding…");

        // Local, offline-capable commands always win: they are faster and they
        // keep working when the provider is down. They still run through the
        // same permissioned tool layer.
        LocalOutcome local;
        try
        {
            local = await LocalIntents.TryHandleAsync(utterance, context, _runtime.Tools).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (context.Token.IsCancellationRequested)
        {
            return new AssistantReply(false, "Cancelled.");
        }

        if (local.Handled)
        {
            Remember(("user", utterance), ("assistant", local.Text));
            _runtime.Conversation.Add("you", utterance);
            _runtime.Conversation.Add("friday", local.Text);
            state.Set(local.Ok ? FridayState.Success : FridayState.Error, local.Ok ? "Done" : "Failed");
            if (local.Ok) state.Flash(FridayState.Success, "Done");
            return new AssistantReply(local.Ok, local.Text);
        }

        if (!_ai.IsConfigured())
        {
            var text = "I can do laptop commands without a key, but I need an AI key for open questions.\n\n" +
                       "Set FRIDAY_AI_KEY in the environment, or open Settings and save a Groq/OpenAI-compatible key. " +
                       "Then ask me again.\n\n" + LocalIntents.HelpText();
            _runtime.Conversation.Add("you", utterance);
            _runtime.Conversation.Add("friday", text);
            state.Set(FridayState.Idle, "AI not configured");
            return new AssistantReply(false, text);
        }

        var messages = BuildMessages(utterance);
        var tools = _runtime.Tools.SchemaFor(includeDestructive: true);
        var transcript = new StringBuilder();
        var usedAi = false;
        var spokenAny = false;

        for (var round = 0; round < Math.Clamp(_runtime.Config.Ai.MaxToolRounds, 1, 12); round++)
        {
            context.Token.ThrowIfCancellationRequested();
            state.Set(FridayState.Thinking, round == 0 ? "Thinking…" : "Working through it…");

            var speechStarted = false;
            AiReply reply;
            try
            {
                reply = await _ai.CompleteStreamingAsync(
                    messages,
                    tools,
                    sentence =>
                    {
                        if (!speechStarted)
                        {
                            speechStarted = _runtime.Voice.BeginStreamedSpeech();
                            if (!speechStarted) return;
                        }
                        _runtime.Voice.EnqueueSpeech(sentence);
                    },
                    context.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                if (speechStarted) _runtime.Voice.EndStreamedSpeech();
                return new AssistantReply(false, "Cancelled.");
            }
            finally
            {
                if (speechStarted)
                {
                    _runtime.Voice.EndStreamedSpeech();
                    spokenAny = true;
                }
            }

            if (!reply.Ok)
            {
                var failure = reply.Error + "\n\n" + LocalIntents.HelpText();
                _runtime.Conversation.Add("friday", failure);
                state.Set(FridayState.Error, "AI unavailable");
                state.Flash(FridayState.Error, "AI unavailable");
                return new AssistantReply(false, failure, null, true, spokenAny);
            }

            usedAi = true;
            usage(reply);

            if (reply.ToolCalls.Count == 0)
            {
                var final = reply.Text.Trim();
                if (transcript.Length > 0)
                {
                    // A model sometimes forgets the tool result by the next round;
                    // the transcript keeps the answer honest.
                    final = final.Length == 0 ? transcript.ToString().Trim() : final;
                }

                messages.Add(ChatMessage.Assistant(final));
                RememberAll(messages);
                _runtime.Conversation.Add("you", utterance);
                _runtime.Conversation.Add("friday", final);
                state.Set(FridayState.Success, "Done");
                state.Flash(FridayState.Success, "Done");
                return new AssistantReply(true, final, null, true, speechStarted || spokenAny);
            }

            messages.Add(new ChatMessage
            {
                Role = "assistant",
                Content = reply.Text,
                ToolCalls = reply.ToolCalls.ToList(),
            });

            foreach (var call in reply.ToolCalls)
            {
                context.Token.ThrowIfCancellationRequested();
                state.Set(FridayState.Executing, call.Name.Replace('_', ' ') + "…");

                JsonObject args;
                try
                {
                    args = JsonNode.Parse(string.IsNullOrWhiteSpace(call.Arguments) ? "{}" : call.Arguments)?.AsObject()
                           ?? new JsonObject();
                }
                catch
                {
                    var bad = $"I sent invalid arguments for {call.Name}, so nothing ran.";
                    messages.Add(ChatMessage.Tool(call.Id, bad));
                    transcript.AppendLine(bad);
                    continue;
                }

                ToolResult result;
                try
                {
                    result = await _runtime.Tools.ExecuteAsync(call.Name, args, context).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    return new AssistantReply(false, "Cancelled.");
                }

                var toolText = (result.Ok ? "OK: " : result.Denied ? "DENIED: " : "FAILED: ") + result.Summary;
                messages.Add(ChatMessage.Tool(call.Id, toolText));
                transcript.AppendLine(result.Summary);
                _runtime.Conversation.Add("action", $"{call.Name}: {result.Summary}");
                context.Report(result.Summary);
            }
        }

        var exhausted = transcript.Length > 0
            ? transcript.ToString().Trim()
            : "I stopped after several steps without finishing. Nothing else is running.";
        _runtime.Conversation.Add("friday", exhausted);
        state.Set(FridayState.Error, "Step limit reached");
        return new AssistantReply(false, exhausted, null, usedAi, spokenAny);
    }

    private void usage(AiReply reply)
    {
        if (reply.Tokens > 0)
            FLog.Info("ai", $"{reply.Model} used {reply.Tokens} tokens");
    }

    private List<ChatMessage> BuildMessages(string utterance)
    {
        var messages = new List<ChatMessage> { ChatMessage.System(SystemPrompt()) };
        lock (_gate)
        {
            messages.AddRange(_history.TakeLast(16));
        }
        messages.Add(ChatMessage.User(utterance));
        return messages;
    }

    private void RememberAll(List<ChatMessage> messages)
    {
        var relevant = messages
            .Where(m => m.Role is "user" or "assistant" && !string.IsNullOrWhiteSpace(m.Content))
            .TakeLast(12)
            .ToArray();
        lock (_gate)
        {
            foreach (var message in relevant)
            {
                if (_history.Count > 0 &&
                    _history[^1].Role == message.Role &&
                    _history[^1].Content == message.Content) continue;
                _history.Add(new ChatMessage { Role = message.Role, Content = message.Content });
            }
            while (_history.Count > 24) _history.RemoveAt(0);
        }
    }

    private void Remember(params (string Role, string Content)[] turns)
    {
        lock (_gate)
        {
            foreach (var (role, content) in turns)
            {
                if (string.IsNullOrWhiteSpace(content)) continue;
                if (_history.Count > 0 && _history[^1].Role == role && _history[^1].Content == content) continue;
                _history.Add(new ChatMessage { Role = role, Content = content });
            }
            while (_history.Count > 24) _history.RemoveAt(0);
        }
    }

    private string SystemPrompt()
    {
        var memory = _runtime.Memory.PromptLines();
        var roots = string.Join("; ", _runtime.Config.Policy.FileRoots.Take(6));
        var sb = new StringBuilder();
        sb.AppendLine("You are FRIDAY, the personal AI that runs natively on the user's Windows laptop.");
        sb.AppendLine("Voice: calm, warm, precise, quietly witty. Never theatrical, never fake. Address the user directly.");
        sb.AppendLine($"Today is {DateTime.Now:dddd d MMMM yyyy}, {DateTime.Now:HH:mm}. Laptop: {Environment.MachineName}. User: {Environment.UserName}.");
        if (!string.IsNullOrWhiteSpace(_runtime.Config.OwnerName))
            sb.AppendLine("The user prefers to be called " + _runtime.Config.OwnerName + ".");
        sb.AppendLine();
        sb.AppendLine("HARD RULES");
        sb.AppendLine("- You act only through the provided tools. Never claim you opened, ran, changed or found something unless a tool result says so.");
        sb.AppendLine("- If a tool fails or is denied, say exactly what failed in one sentence. Do not silently retry more than once.");
        sb.AppendLine("- Never invent file contents, command output, prices, dates or test results.");
        sb.AppendLine("- Do not ask the user to paste API keys or passwords into chat.");
        sb.AppendLine("- You cannot control the phone's hardware; the Android app handles phone actions itself.");
        sb.AppendLine("- Keep spoken replies to 1-4 short sentences unless the user asks for detail. Plain text, no markdown headings.");
        sb.AppendLine();
        sb.AppendLine("TOOLS");
        sb.AppendLine("- Prefer one tool call at a time, then check the result before the next.");
        sb.AppendLine("- Use open_app / open_url / open_path for launching things, run_command only for allowlisted developer commands, and memory tools only when the user asks you to remember something.");
        sb.AppendLine("- Destructive actions are gated by the user's approval; that is expected, so do not refuse a request just because approval will be needed.");
        sb.AppendLine("- File access is limited to: " + roots);
        sb.AppendLine();
        if (memory.Length > 0)
        {
            sb.AppendLine("APPROVED MEMORY");
            foreach (var line in memory) sb.AppendLine(line);
            sb.AppendLine();
        }
        sb.Append("If the user's request is ambiguous between the phone and the laptop, ask one short clarifying question before acting.");
        return sb.ToString();
    }
}
