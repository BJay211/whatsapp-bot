using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using Friday.Core;
using Friday.Tools;
using Friday.Voice;

namespace Friday.Ai;

public sealed record LocalOutcome(bool Handled, bool Ok, string Text);

/// <summary>
/// The offline command layer. These are real actions handled by the same
/// validated tool registry, so basic laptop control keeps working with no API
/// key and no internet. Anything it does not recognise goes to the model.
/// </summary>
public static class LocalIntents
{
    public static async Task<LocalOutcome> TryHandleAsync(string text, ToolContext ctx, ToolRegistry registry)
    {
        var original = (text ?? "").Trim();
        if (original.Length == 0) return new LocalOutcome(false, false, "");

        var cleaned = StripWakeWord(original, ctx.Config.Voice.WakeWord);
        var lower = cleaned.ToLowerInvariant().Trim(' ', '.', '!', '?', ',');

        if (lower is "help" or "what can you do" or "what can i ask you" or "commands" or "show commands")
        {
            return new LocalOutcome(true, true, HelpText());
        }

        if (lower is "hi" or "hello" or "hey" or "hey friday" or "good morning" or "good afternoon" or "good evening")
        {
            return new LocalOutcome(true, true,
                "I'm here. I can open apps, run approved commands, look at files, take screenshots, " +
                "check the laptop, search the web, or just answer a question. What do you need?");
        }

        if (lower is "thanks" or "thank you" or "cheers") return new LocalOutcome(true, true, "Any time.");

        if (lower is "time" or "what time is it" or "the time")
            return new LocalOutcome(true, true, "It is " + DateTime.Now.ToString("h:mm tt") + ".");

        if (lower is "date" or "what is the date" or "what's the date" or "today's date" or "todays date")
            return new LocalOutcome(true, true, "Today is " + DateTime.Now.ToString("dddd d MMMM yyyy") + ".");

        if (lower is ("status" or "system status" or "laptop status" or "how is the laptop") ||
            lower.StartsWith("battery") || lower.Contains("battery level") || lower.Contains("power level"))
        {
            return await Run(registry, "system_status", new JsonObject(), ctx);
        }

        if (lower is "screenshot" or "take a screenshot" or "screen shot" or "capture the screen")
            return await Run(registry, "screenshot", new JsonObject { ["label"] = "voice" }, ctx);

        if (lower is "lock" or "lock the laptop" or "lock the screen" or "lock my laptop" or "lock computer")
            return await Run(registry, "lock_screen", new JsonObject(), ctx);

        if (lower.StartsWith("volume ") || lower.Contains("turn up the volume") || lower.Contains("turn down the volume") ||
            lower.Contains("increase the volume") || lower.Contains("decrease the volume") || lower is ("mute" or "unmute"))
        {
            var action = "up";
            var level = 50;
            if (lower.Contains("mute") || lower.Contains("unmute")) action = "mute";
            else if (lower.Contains("down") || lower.Contains("decrease") || lower.Contains("lower")) action = "down";
            else if (lower.Contains("up") || lower.Contains("increase") || lower.Contains("raise")) action = "up";

            var match = Regex.Match(lower, @"(?:to|at)\s*(\d{1,3})");
            if (match.Success && int.TryParse(match.Groups[1].Value, out var parsed))
            {
                action = "level";
                level = parsed;
            }

            return await Run(registry, "set_volume", new JsonObject { ["action"] = action, ["level"] = level }, ctx);
        }

        if (lower.Contains("brightness") || lower.Contains("dim the screen") || lower.Contains("screen brighter"))
        {
            var current = SystemTools.CurrentBrightness() ?? 60;
            var level = lower.Contains("max") || lower.Contains("full") ? 100
                : lower.Contains("dim") || lower.Contains("lower") || lower.Contains("down") ? 20
                : lower.Contains("up") || lower.Contains("increase") || lower.Contains("brighter") ? Math.Min(100, current + 20)
                : current;
            var match = Regex.Match(lower, @"(\d{1,3})\s*%?");
            if (match.Success && int.TryParse(match.Groups[1].Value, out var parsed)) level = Math.Clamp(parsed, 0, 100);
            return await Run(registry, "set_brightness", new JsonObject { ["level"] = level }, ctx);
        }

        if (lower is "pause" or "play" or "play music" or "pause the music" or "resume" or "stop the music" or
            "next track" or "next song" or "skip" or "previous track" or "previous song")
        {
            var action = lower.Contains("next") || lower.Contains("skip") ? "next"
                : lower.Contains("previous") || lower.Contains("back") ? "previous"
                : lower.Contains("stop") ? "stop" : "play_pause";
            return await Run(registry, "media_control", new JsonObject { ["action"] = action }, ctx);
        }

        if (lower is "sleep" or "sleep the laptop" or "put the laptop to sleep")
            return await Run(registry, "power_action", new JsonObject { ["action"] = "sleep" }, ctx);

        if (lower is "restart" or "restart the laptop" or "reboot" or "reboot the laptop")
            return await Run(registry, "power_action", new JsonObject { ["action"] = "restart", ["delay_seconds"] = 30 }, ctx);

        if (lower is "shut down" or "shutdown" or "shut down the laptop" or "turn off the laptop")
            return await Run(registry, "power_action", new JsonObject { ["action"] = "shutdown", ["delay_seconds"] = 30 }, ctx);

        if (lower is ("cancel shutdown" or "abort shutdown"))
            return await Run(registry, "power_action", new JsonObject { ["action"] = "cancel_shutdown" }, ctx);

        if (lower is ("what windows are open" or "list windows" or "show my windows"))
            return await Run(registry, "list_windows", new JsonObject(), ctx);

        if (lower is ("what is focused" or "active window" or "what am i looking at"))
            return await Run(registry, "active_window", new JsonObject(), ctx);

        if (lower is ("list tasks" or "what are you working on" or "tasks"))
        {
            var tasks = ctx.Runtime.Tasks.Recent(10);
            if (tasks.Count == 0) return new LocalOutcome(true, true, "Nothing is queued or running.");
            return new LocalOutcome(true, true, string.Join(Environment.NewLine,
                tasks.Select(t => $"{t.Status,-16} {t.Id}  {Trim(t.Instruction, 70)}")));
        }

        if (lower is ("stop" or "stop it" or "cancel" or "never mind" or "nevermind"))
        {
            ctx.Runtime.Tasks.CancelAll("Stopped at your request.");
            return new LocalOutcome(true, true, "Stopped. Nothing else is running.");
        }

        if (lower.StartsWith("run the tests") || lower is ("run tests" or "test the project" or "run the test suite"))
        {
            var project = ctx.Runtime.LastProjectPath ?? ctx.Config.Policy.FileRoots.FirstOrDefault() ?? ".";
            return await Run(registry, "run_tests", new JsonObject { ["path"] = project }, ctx);
        }

        if (lower is ("git status" or "check git" or "what changed"))
        {
            var project = ctx.Runtime.LastProjectPath ?? ctx.Config.Policy.FileRoots.FirstOrDefault() ?? ".";
            return await Run(registry, "git_status", new JsonObject { ["path"] = project }, ctx);
        }

        if (lower.StartsWith("list apps") || lower is ("what apps can you open" or "installed apps"))
            return await Run(registry, "list_apps", new JsonObject(), ctx);

        // ---- explicit command execution --------------------------------------
        // "run <x>" is ambiguous: it can mean "launch this app" or "execute this
        // command". It only becomes a command when it is backticked or starts
        // with a known developer verb, so "run chrome" still opens Chrome.
        if (cleaned.StartsWith('`') && cleaned.EndsWith('`') && cleaned.Length > 2)
        {
            var bare = cleaned.Trim('`').Trim();
            if (bare.Length > 0)
                return await Run(registry, "run_command", new JsonObject { ["command"] = bare }, ctx);
        }

        var runMatch = Regex.Match(cleaned, @"^(?:run|execute)\s+(?:the\s+)?(?:command\s+)?(?<c>.+)$",
            RegexOptions.IgnoreCase);
        if (runMatch.Success)
        {
            var command = runMatch.Groups["c"].Value.Trim();
            var backticked = command.Length > 1 && command.StartsWith('`') && command.EndsWith('`');
            if (backticked) command = command.Trim('`').Trim();
            if (command.Length > 0 && (backticked || LooksLikeCommand(command)))
                return await Run(registry, "run_command", new JsonObject { ["command"] = command }, ctx);
        }

        // ---- "open ..." family -------------------------------------------------
        // One resolver decides app vs file vs folder. It always launches a full
        // executable path, so "open chrome" cannot open a folder named chrome.
        var openMatch = Regex.Match(cleaned, @"^(?:please\s+)?(?:can you\s+)?(?:open|launch|start|run|switch to)\s+(?<target>.+)$",
            RegexOptions.IgnoreCase);
        if (openMatch.Success)
        {
            var target = openMatch.Groups["target"].Value.Trim().Trim('"');
            return await Run(registry, "open_target", new JsonObject { ["target"] = target }, ctx);
        }

        // ---- window control ---------------------------------------------------
        var windowMatch = Regex.Match(cleaned,
            @"^(?<action>close|minimi[sz]e|maximi[sz]e|focus|switch to|restore)\s+(?<target>.+)$",
            RegexOptions.IgnoreCase);
        if (windowMatch.Success)
        {
            var action = windowMatch.Groups["action"].Value.ToLowerInvariant() switch
            {
                "close" => "close",
                "minimize" or "minimise" => "minimize",
                "maximize" or "maximise" => "maximize",
                "restore" => "restore",
                _ => "focus",
            };
            var target = windowMatch.Groups["target"].Value.Trim();
            return await Run(registry, "window_action",
                new JsonObject { ["title"] = target, ["action"] = action }, ctx);
        }

        // ---- file search ------------------------------------------------------
        var searchFileMatch = Regex.Match(cleaned,
            @"^(?:find|search|look)\s+(?:for\s+)?(?:a\s+)?(?:file|files|folder)\s+(?:called\s+|named\s+)?(?<q>.+)$",
            RegexOptions.IgnoreCase);
        if (searchFileMatch.Success)
            return await Run(registry, "search_files",
                new JsonObject { ["query"] = AppTools.NormaliseSpokenTarget(searchFileMatch.Groups["q"].Value) }, ctx);

        var whereMatch = Regex.Match(cleaned, @"^(?:where is|find)\s+(?<q>.+)$", RegexOptions.IgnoreCase);
        if (whereMatch.Success)
            return await Run(registry, "search_files",
                new JsonObject { ["query"] = AppTools.NormaliseSpokenTarget(whereMatch.Groups["q"].Value) }, ctx);

        // ---- read a file ------------------------------------------------------
        var readMatch = Regex.Match(cleaned, @"^(?:read|open and read|show me)\s+(?:the\s+file\s+)?(?<p>.+)$",
            RegexOptions.IgnoreCase);
        if (readMatch.Success)
        {
            var path = AppTools.NormaliseSpokenTarget(readMatch.Groups["p"].Value);
            if (Regex.IsMatch(path, @"\.(txt|md|docx|json|log)$", RegexOptions.IgnoreCase))
                return await Run(registry, "read_file", new JsonObject { ["path"] = path }, ctx);
        }

        // ---- web search ------------------------------------------------------
        var webMatch = Regex.Match(cleaned,
            @"^(?:search(?: the web)?(?: for)?|google|look up|web search)\s+(?<q>.+)$", RegexOptions.IgnoreCase);
        if (webMatch.Success)
            return await Run(registry, "web_search", new JsonObject { ["query"] = webMatch.Groups["q"].Value.Trim() }, ctx);

        // ---- memory ----------------------------------------------------------
        var rememberMatch = Regex.Match(cleaned, @"^remember (?:that )?(?<m>.+)$", RegexOptions.IgnoreCase);
        if (rememberMatch.Success)
            return await Run(registry, "remember", new JsonObject { ["text"] = rememberMatch.Groups["m"].Value.Trim() }, ctx);

        var recallMatch = Regex.Match(cleaned, @"^(?:what do you remember about|recall|do you remember)\s+(?<m>.+)$",
            RegexOptions.IgnoreCase);
        if (recallMatch.Success)
            return await Run(registry, "recall", new JsonObject { ["query"] = recallMatch.Groups["m"].Value.Trim() }, ctx);

        return new LocalOutcome(false, false, "");
    }

    private static readonly Regex CommandVerb = new(
        @"^(?:git|dotnet|npm|npx|node|python|py|pip|gradle|gradlew|adb|powershell|pwsh|cmd|echo|dir|where|winget|choco|curl|ping|ipconfig|tasklist|systeminfo|mvn|java|javac|rustc|cargo|go|make|cmake|msbuild|nuget|deno|yarn|pnpm|tsc|eslint|pytest|pipx|schtasks)\b|^(?:Get|Set|Test|Start|Stop|New|Remove|Write|Add|Select|Where|ForEach)-",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    /// <summary>
    /// True when the words after "run" look like a command rather than an app
    /// name, so "run chrome" still opens Chrome while "run git status" runs git.
    /// </summary>
    public static bool LooksLikeCommand(string text)
    {
        var trimmed = (text ?? "").Trim();
        if (trimmed.Length == 0) return false;
        if (trimmed.StartsWith('`')) return true;
        return CommandVerb.IsMatch(trimmed);
    }

    public static string StripWakeWord(string text, string? wakeWord = null)
    {
        var trimmed = (text ?? "").Trim();
        return WakeWord.TryExtract(trimmed, wakeWord ?? "friday", out var rest) ? rest : trimmed;
    }

    private static async Task<LocalOutcome> Run(ToolRegistry registry, string tool, JsonObject args, ToolContext ctx)
    {
        var result = await registry.ExecuteAsync(tool, args, ctx).ConfigureAwait(false);
        return new LocalOutcome(true, result.Ok, result.Summary);
    }

    public static string HelpText() => """
        I can run these on the laptop directly, even offline:

        • open Chrome / open VS Code / open Notepad
        • open dave.txt — or just say "open dave dot text"
        • open downloads / open my documents / open the Desktop
        • open my Vendora project
        • take a screenshot, lock the laptop
        • volume up, volume 40, mute, brightness 70
        • system status / battery
        • find file report.pdf, read notes.md
        • run git status, run the tests
        • search the web for <something>
        • remember that <fact>, what do you remember about <topic>
        • sleep, restart, shut down (these ask first)
        • stop — cancels whatever is running

        Say "Friday" first when the wake word is on. After a command I keep
        listening for 30 seconds without it; silence for 30 seconds switches
        me back to waiting for my name.

        Anything else goes to my AI brain when a key is configured.
        """;

    private static string Trim(string value, int max) =>
        value.Length <= max ? value : value[..max] + "…";
}
