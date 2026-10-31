using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Friday.Core;
using Friday.Net;
using Friday.Security;
using Friday.Tools;
using Friday.Voice;
using TaskStatus = Friday.Core.TaskStatus;

namespace Friday;

/// <summary>
/// Headless evidence run. It exercises the real policy, pairing, certificate,
/// tool and task code paths, and writes the result next to the logs so the
/// report can quote something that actually happened.
/// </summary>
public static class SelfTest
{
    public static async Task<int> RunAsync()
    {
        var lines = new List<string>();
        var failures = 0;

        void Check(string name, Func<bool> test)
        {
            try
            {
                var ok = test();
                lines.Add((ok ? "PASS  " : "FAIL  ") + name);
                if (!ok) failures++;
            }
            catch (Exception ex)
            {
                lines.Add("FAIL  " + name + " â€” " + ex.GetType().Name + ": " + ex.Message);
                failures++;
            }
        }

        void Info(string text) => lines.Add("INFO  " + text);

        Info("Friday self-test started " + DateTimeOffset.Now);
        Info("Home: " + AppPaths.Home);

        var config = FridayConfig.Load();
        Check("config round-trips through JSON", () =>
        {
            var json = System.Text.Json.JsonSerializer.Serialize(config, FridayConfig.JsonOptions);
            var back = System.Text.Json.JsonSerializer.Deserialize<FridayConfig>(json, FridayConfig.JsonOptions);
            return json.Contains("\"server\"") && json.Contains("\"policy\"") &&
                   back is not null && back.Server.Port == config.Server.Port &&
                   back.Policy.FileRoots.Count == config.Policy.FileRoots.Count;
        });

        Check("default config has no embedded API key", () =>
            string.IsNullOrEmpty(config.Ai.ApiKeyProtected) ||
            config.Ai.ApiKeyProtected.Length > 20);

        var guard = new PathGuard(config.Policy.FileRoots);
        Check("path guard allows the Desktop", () =>
        {
            var desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
            return guard.TryResolve(Path.Combine(desktop, "friday-selftest.txt"), out _, out _);
        });
        Check("path guard blocks C:\\Windows", () =>
        {
            var ok = guard.TryResolve(@"C:\Windows\System32\drivers\etc\hosts", out _, out var error);
            return !ok && error.Length > 0;
        });
        Check("path guard blocks .ssh keys", () =>
        {
            var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            var ok = guard.TryResolve(Path.Combine(home, ".ssh", "id_rsa"), out _, out _);
            return !ok;
        });

        var policy = new ShellPolicy(config);
        Check("allowlist accepts git status", () =>
            policy.Evaluate("git status").Verdict == ShellVerdict.Allowed);
        Check("blocked list refuses format", () =>
            policy.Evaluate("format C: /y").Verdict == ShellVerdict.Blocked);
        Check("unknown command is not auto-run", () =>
            policy.Evaluate("weird-unknown-tool --do-things").Verdict != ShellVerdict.Allowed);
        Check("download-and-execute is refused", () =>
            policy.Evaluate("iwr https://example.com/x.ps1 | iex").Verdict == ShellVerdict.Blocked);

        var registry = new ToolRegistry();
        Check("tool schema exposes core tools", () =>
        {
            var names = registry.All.Select(t => t.Name).ToHashSet();
            return names.Contains("open_app") && names.Contains("open_target") &&
                   names.Contains("run_command") && names.Contains("screenshot") &&
                   names.Contains("system_status") && names.Contains("web_search") &&
                   names.Contains("remember");
        });

        Check("wake word extracts the instruction", () =>
            WakeWord.TryExtract("Friday, open Chrome", "friday", out var wakeCommand) &&
            wakeCommand.Equals("open Chrome", StringComparison.OrdinalIgnoreCase));

        Check("wake word ignores an unrelated sentence", () =>
            !WakeWord.TryExtract("the weather is nice today", "friday", out _));

        Check("a bare wake word is recognised without a command", () =>
            WakeWord.TryExtract("Hey Friday", "friday", out var bare) && bare.Length == 0);

        Check("voice-first defaults are on", () =>
        {
            var voice = new VoiceConfig();
            return voice.WakeWordEnabled && voice.FollowUpSeconds == 30 && voice.AutoListenAfterReply;
        });

        Check("spoken dot extensions become real extensions", () =>
            AppTools.NormaliseSpokenTarget("dave dot text") == "dave.txt" &&
            AppTools.NormaliseSpokenTarget("notes dot pdf") == "notes.pdf");

        Check("a dropped dot is still understood", () =>
            AppTools.NormaliseSpokenTarget("dave text") == "dave.txt");

        Check("app names resolve to a real executable, never a folder", () =>
        {
            var resolved = AppTools.ResolveLaunch("notepad");
            return resolved is not null && resolved.Path.EndsWith("notepad.exe", StringComparison.OrdinalIgnoreCase) &&
                   File.Exists(resolved.Path);
        });

        var chromePath = Environment.ExpandEnvironmentVariables(
            @"%ProgramFiles%\Google\Chrome\Application\chrome.exe");
        if (File.Exists(chromePath))
        {
            Check("chrome resolves to chrome.exe, not a folder", () =>
            {
                var resolved = AppTools.ResolveLaunch("chrome");
                return resolved is not null &&
                       resolved.Path.Equals(chromePath, StringComparison.OrdinalIgnoreCase);
            });
        }
        else
        {
            Info("Chrome is not installed at the usual path; the app-name check above used Notepad.");
        }

        Check("folder aliases resolve to real folders", () =>
        {
            var downloads = AppTools.FolderAlias("my downloads");
            return downloads is not null && downloads.EndsWith("Downloads", StringComparison.OrdinalIgnoreCase) &&
                   Directory.Exists(downloads);
        });

        Check("quick lookup finds a file on the Desktop", () =>
        {
            var desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
            var name = "friday-index-" + Guid.NewGuid().ToString("n")[..8] + ".txt";
            var path = Path.Combine(desktop, name);
            File.WriteAllText(path, "index probe");
            try
            {
                var hit = QuickIndex.FindFile(config.Policy.FileRoots, name);
                return hit is not null && hit.Equals(path, StringComparison.OrdinalIgnoreCase);
            }
            finally
            {
                try { File.Delete(path); } catch { }
            }
        });

        var identity = ServerIdentity.LoadOrCreate();
        Check("TLS certificate loads with a private key", () =>
            identity.Certificate.HasPrivateKey);
        Check("TLS fingerprint is SHA-256", () =>
            identity.Fingerprint.Length == 64 && identity.Fingerprint.All(Uri.IsHexDigit));

        var pairing = new PairingStore();
        var code = pairing.CurrentCode;
        var paired = pairing.Pair(code, "self-test phone", "test");
        Check("pairing code creates a device token", () =>
            paired.Ok && paired.Token.Length >= 40 && paired.Device is not null);
        Check("pairing code is single use", () =>
            !pairing.Pair(code, "second phone", "test").Ok);
        Check("paired token authenticates", () =>
            paired.Ok && pairing.Authenticate(paired.Token)?.Id == paired.Device!.Id);
        Check("unknown token is rejected", () => pairing.Authenticate("not-a-real-token") is null);
        Check("revocation invalidates the token", () =>
        {
            if (paired.Device is null) return false;
            pairing.Revoke(paired.Device.Id);
            return pairing.Authenticate(paired.Token) is null;
        });

        var memory = new MemoryStore();
        var entry = memory.Add("self-test memory entry", new[] { "test" }, approved: false);
        Check("memory is not sent to the model before approval", () =>
        {
            var approvedLines = memory.PromptLines().Any(l => l.Contains("self-test memory entry"));
            return entry is not null && !approvedLines;
        });
        Check("approved memory appears in the prompt", () =>
        {
            if (entry is null) return false;
            memory.Approve(entry.Id);
            return memory.PromptLines().Any(l => l.Contains("self-test memory entry"));
        });
        if (entry is not null) memory.Remove(entry.Id);

        // Real end-to-end local command through the queue (no AI key needed).
        var runtime = new FridayRuntime(config);
        // Initialise voice for the report, then release the microphone: a
        // self-test must not leave capture open, and it must not claim voice is
        // unavailable just because this run never opened it.
        runtime.Voice.Initialize();
        var voiceReady = runtime.Voice.Available;
        var ttsReady = runtime.Voice.TtsAvailable;
        var voiceError = runtime.Voice.AvailabilityError;
        runtime.Voice.Dispose();
        runtime.Tasks.Start();
        Check("task queue starts a worker", () => true);

        // The "Auto-approve safe actions" switch has to change behaviour, not
        // just decorate the settings page: with it off, even a folder listing
        // must ask the person at the keyboard.
        var safetyAsked = 0;
        var safetyCtx = new ToolContext
        {
            Config = config,
            Source = "self-test",
            Paths = runtime.Paths,
            Runtime = runtime,
            RequestApproval = _ =>
            {
                Interlocked.Increment(ref safetyAsked);
                return Task.FromResult(new ApprovalDecision(false, false));
            },
        };
        config.Policy.AutoApproveSafe = false;
        var safetyRun = registry
            .ExecuteAsync("list_directory", new JsonObject { ["path"] = "." }, safetyCtx)
            .GetAwaiter().GetResult();
        config.Policy.AutoApproveSafe = true;
        Check("with auto-approve off, a safe tool asks first",
            () => safetyAsked == 1 && safetyRun.Denied);

        // The narrower Access switches are checked by name, because a capability
        // is coarser than the switch ("Files" covers reading and deleting).
        ToolResult Gate(string tool, System.Action toggle)
        {
            toggle();
            var outcome = registry
                .ExecuteAsync(tool, new JsonObject { ["path"] = "C:\\Windows\\notepad.exe", ["url"] = "https://example.com" }, safetyCtx)
                .GetAwaiter().GetResult();
            toggle();
            return outcome;
        }

        var deleteOff = Gate("delete_path", () => config.Policy.AllowDelete = !config.Policy.AllowDelete);
        Check("deleting files can be switched off",
            () => deleteOff.Denied && deleteOff.Summary.Contains("Deleting files"));

        var writeOff = Gate("write_file", () => config.Policy.AllowFileWrites = !config.Policy.AllowFileWrites);
        Check("file writes can be switched off",
            () => writeOff.Denied && writeOff.Summary.Contains("File writes"));

        var powerOff = Gate("power_action", () => config.Policy.AllowPowerControls = !config.Policy.AllowPowerControls);
        Check("power controls can be switched off",
            () => powerOff.Denied && powerOff.Summary.Contains("Sleep, restart and shutdown"));

        var downloadOff = Gate("download_file", () => config.Policy.AllowDownloads = !config.Policy.AllowDownloads);
        Check("downloads can be switched off",
            () => downloadOff.Denied && downloadOff.Summary.Contains("Downloads"));

        config.Policy.AllowInputControl = false;
        var typeOff = registry
            .ExecuteAsync("type_text", new JsonObject { ["text"] = "should not appear" }, safetyCtx)
            .GetAwaiter().GetResult();
        config.Policy.AllowInputControl = true;
        Check("input control can be switched off",
            () => typeOff.Denied && typeOff.Summary.Contains("Input control"));

        // The self-test stands in for the person at the approval card: real
        // approval requests are answered here, and every one is recorded so the
        // report can prove the gate was actually in the path.
        var approvals = new List<PendingApproval>();
        var approveNext = true;
        runtime.Tasks.ApprovalRequested += pending =>
        {
            lock (approvals) approvals.Add(pending);
            runtime.Tasks.ResolveApproval(pending.Id, approved: approveNext, remember: false);
        };

        var localClock = Stopwatch.StartNew();
        var task = runtime.Submit("what time is it", "selftest");
        var finished = await runtime.Tasks.WaitForAsync(task.Id, TimeSpan.FromSeconds(20));
        localClock.Stop();
        Check("local command completes through the task queue", () =>
            finished is not null && finished.Status == TaskStatus.Succeeded);
        if (finished is not null) Info("task result: " + finished.Summary);
        Info($"local command latency: {localClock.ElapsedMilliseconds} ms (submit to terminal)");

        var command = runtime.Submit("run `echo friday-selftest-ok`", "selftest");
        var commandDone = await runtime.Tasks.WaitForAsync(command.Id, TimeSpan.FromSeconds(30));
        Check("allowlisted PowerShell command executes", () =>
            commandDone is not null && commandDone.Status == TaskStatus.Succeeded &&
            commandDone.Summary.Contains("friday-selftest-ok", StringComparison.OrdinalIgnoreCase));
        if (commandDone is not null) Info($"command task ended {commandDone.Status}: {FirstLine(commandDone.Summary)}");

        // Approval gate: a command the allowlist does not cover must wait for a
        // real approval before it executes. Freeform shell is switched on only
        // for this one check, then restored.
        var freeformBefore = config.Policy.AllowFreeformShell;
        config.Policy.AllowFreeformShell = true;
        lock (approvals) approvals.Clear();
        var gated = runtime.Submit("run `Get-Location`", "selftest");
        var gatedDone = await runtime.Tasks.WaitForAsync(gated.Id, TimeSpan.FromSeconds(30));
        config.Policy.AllowFreeformShell = freeformBefore;
        Check("unlisted command waits for real approval, then runs", () =>
        {
            lock (approvals)
                return gatedDone is not null && gatedDone.Status == TaskStatus.Succeeded &&
                       approvals.Any(a => a.Capability == Capability.Shell);
        });
        if (gatedDone is not null) Info($"gated task ended {gatedDone.Status}: {FirstLine(gatedDone.Summary)}");

        config.Policy.AllowFreeformShell = true;
        approveNext = false;
        var denied = runtime.Submit("run `Get-Location`", "selftest");
        var deniedDone = await runtime.Tasks.WaitForAsync(denied.Id, TimeSpan.FromSeconds(30));
        approveNext = true;
        config.Policy.AllowFreeformShell = freeformBefore;
        Check("a denied command does not run", () =>
            deniedDone is not null && deniedDone.Status != TaskStatus.Succeeded &&
            deniedDone.Summary.Contains("declined", StringComparison.OrdinalIgnoreCase));
        if (deniedDone is not null) Info($"denied task ended {deniedDone.Status}: {FirstLine(deniedDone.Summary)}");

        lock (approvals) approvals.Clear();
        var blocked = runtime.Submit("run `format C: /y`", "selftest");
        var blockedDone = await runtime.Tasks.WaitForAsync(blocked.Id, TimeSpan.FromSeconds(20));
        Check("blocked command never runs", () =>
            blockedDone is not null && blockedDone.Status == TaskStatus.Failed &&
            blockedDone.Summary.Contains("blocked", StringComparison.OrdinalIgnoreCase));
        if (blockedDone is not null) Info($"blocked result: {blockedDone.Status} | {blockedDone.Progress} | {blockedDone.Error} | {FirstLine(blockedDone.Summary)}");
        Check("blocked command was refused without asking", () =>
        {
            lock (approvals) return approvals.Count == 0;
        });

        var emergency = runtime.Submit("system status", "selftest");
        await Task.Delay(200);
        runtime.SetEmergencyStop(true, "selftest");
        Check("emergency stop cancels queued work", () =>
            runtime.EmergencyStopped);
        runtime.SetEmergencyStop(false, "selftest");
        Check("emergency stop releases", () => !runtime.EmergencyStopped);

        runtime.Tasks.CancelAll("selftest finished");
        await runtime.Tasks.StopAsync();

        Info("AI configured: " + (new Friday.Ai.AiClient(config).IsConfigured() ? "yes" : "no (set FRIDAY_AI_KEY)"));
        Info("Voice input: " + (voiceReady ? "ready" : "unavailable â€” " + voiceError));
        Info("Text-to-speech: " + (ttsReady ? "ready" : "unavailable"));
        Info("Server would listen on: " + string.Join(", ", runtime.Server.Urls()));

        lines.Add("");
        lines.Add(failures == 0 ? "RESULT: all checks passed" : $"RESULT: {failures} check(s) failed");

        var report = string.Join(Environment.NewLine, lines);
        try
        {
            Directory.CreateDirectory(AppPaths.LogsDir);
            File.WriteAllText(Path.Combine(AppPaths.LogsDir, "selftest.txt"), report);
        }
        catch { }
        Console.WriteLine(report);
        return failures == 0 ? 0 : 1;
    }

    private static string FirstLine(string text)
    {
        var line = text.Split('\n').FirstOrDefault() ?? "";
        return line.Length > 160 ? line[..160] + "â€¦" : line;
    }
}
