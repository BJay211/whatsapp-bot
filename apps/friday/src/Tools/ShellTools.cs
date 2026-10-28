using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Friday.Core;
using Friday.Security;

namespace Friday.Tools;

public static class ShellTools
{
    private static readonly Dictionary<int, Process> Background = new();

    public static IEnumerable<ToolDescriptor> Describe()
    {
        yield return new ToolDescriptor
        {
            Name = "run_command",
            Description = "Run a PowerShell command that matches the user's allowlist (git, builds, tests, diagnostics). Returns exit code, stdout and stderr.",
            Capability = Capability.Shell,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"command":{"type":"string"},"cwd":{"type":"string"},"timeout_seconds":{"type":"integer"}},"required":["command"],"additionalProperties":false}
            """),
            Handler = RunCommandAsync,
        };
        yield return new ToolDescriptor
        {
            Name = "open_terminal",
            Description = "Open a visible PowerShell window in a project folder so the user can watch or continue.",
            Capability = Capability.Shell,
            Risk = RiskLevel.Confirm,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"cwd":{"type":"string"}},"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(OpenTerminal(ctx, Args.Str(args, "cwd"))),
        };
        yield return new ToolDescriptor
        {
            Name = "start_background",
            Description = "Start a long-running command (a dev server, a build) in the background. Returns a process id and a log file.",
            Capability = Capability.Shell,
            Risk = RiskLevel.Confirm,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"command":{"type":"string"},"cwd":{"type":"string"}},"required":["command"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(StartBackground(ctx, Args.Str(args, "command"), Args.Str(args, "cwd"))),
        };
        yield return new ToolDescriptor
        {
            Name = "stop_process",
            Description = "Stop a background process Friday started, by process id.",
            Capability = Capability.Shell,
            Risk = RiskLevel.Confirm,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"pid":{"type":"integer"}},"required":["pid"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(StopProcess(Args.Int(args, "pid"))),
        };
        yield return new ToolDescriptor
        {
            Name = "list_background",
            Description = "List background processes Friday started and whether they are still running.",
            Capability = Capability.Shell,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""{"type":"object","properties":{},"additionalProperties":false}"""),
            Handler = (ctx, args) =>
            {
                if (Background.Count == 0) return Task.FromResult(ToolResult.Good("No background processes."));
                var lines = Background.Select(kv =>
                {
                    string state;
                    try { state = kv.Value.HasExited ? "exited " + kv.Value.ExitCode : "running"; }
                    catch { state = "unknown"; }
                    return $"{kv.Key}  {state}  {kv.Value.StartTime:HH:mm:ss}";
                });
                return Task.FromResult(ToolResult.Good(string.Join(Environment.NewLine, lines)));
            },
        };
    }

    private static string ResolveWorkingDirectory(ToolContext ctx, string requested)
    {
        if (string.IsNullOrWhiteSpace(requested)) return ctx.Runtime.Paths.Roots.FirstOrDefault() ?? Environment.CurrentDirectory;
        if (ctx.Runtime.Paths.TryResolve(requested, out var full, out _, mustExist: true) && Directory.Exists(full)) return full;
        return Environment.CurrentDirectory;
    }

    private static async Task<ToolResult> RunCommandAsync(ToolContext ctx, JsonObject args)
    {
        var command = (Args.Str(args, "command") ?? "").Trim();
        if (command.Length == 0) return ToolResult.Fail("No command was given.");

        var policy = new ShellPolicy(ctx.Config);
        var decision = policy.Evaluate(command);
        if (decision.Verdict == ShellVerdict.Blocked)
        {
            FLog.Audit("run_command", ctx.Source, "blocked", command, false, ctx.DeviceId, ctx.RequestId);
            return ToolResult.Refused(decision.Reason);
        }

        if (decision.Verdict == ShellVerdict.NeedsApproval)
        {
            if (ctx.RequestApproval is null)
                return ToolResult.Refused("That command needs approval, but there is no way to ask you right now.");
            var approved = await ctx.RequestApproval(new ApprovalRequest(
                Guid.NewGuid().ToString("n")[..10],
                "Run this command?",
                command,
                Capability.Shell, "run_command", false, ctx.Source)).ConfigureAwait(false);
            if (!approved.Approved) return ToolResult.Refused("You declined the command.");
        }

        var cwd = ResolveWorkingDirectory(ctx, Args.Str(args, "cwd"));
        var timeout = Math.Clamp(
            Args.Int(args, "timeout_seconds", ctx.Config.Policy.CommandTimeoutSeconds),
            5, 3600);

        ctx.Report("Running: " + command);

        var startInfo = new ProcessStartInfo
        {
            FileName = "powershell.exe",
            Arguments = PowerShellArguments(Wrap(command)),
            WorkingDirectory = cwd,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };

        using var process = new Process { StartInfo = startInfo };
        var stdout = new StringBuilder();
        var stderr = new StringBuilder();
        var stopwatch = Stopwatch.StartNew();

        process.OutputDataReceived += (_, e) => { if (e.Data is not null) stdout.AppendLine(e.Data); };
        process.ErrorDataReceived += (_, e) => { if (e.Data is not null) stderr.AppendLine(e.Data); };

        if (!process.Start())
            return ToolResult.Fail("The command could not be started.");
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();

        var timedOut = false;
        try
        {
            using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ctx.Token);
            timeoutCts.CancelAfter(TimeSpan.FromSeconds(timeout));
            await process.WaitForExitAsync(timeoutCts.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            timedOut = !ctx.Token.IsCancellationRequested;
            try { process.Kill(entireProcessTree: true); } catch { }
            if (ctx.Token.IsCancellationRequested)
                return new ToolResult(false, "Command cancelled.", Cancelled: true);
        }

        try { process.WaitForExit(2000); } catch { }
        stopwatch.Stop();

        var outText = stdout.ToString().Trim();
        var errText = StripClixml(stderr.ToString()).Trim();
        var exit = process.HasExited ? process.ExitCode : -1;

        if (timedOut)
        {
            return ToolResult.Fail(
                $"The command exceeded {timeout}s and was stopped." +
                (outText.Length > 0 ? "\n\n" + Clamp(outText) : ""));
        }

        var ok = exit == 0;
        var summary = new StringBuilder();
        summary.Append(ok ? $"Exit 0 in {stopwatch.ElapsedMilliseconds} ms." : $"Exit {exit} in {stopwatch.ElapsedMilliseconds} ms.");
        if (outText.Length > 0) summary.Append("\n\n").Append(Clamp(outText));
        if (errText.Length > 0) summary.Append("\n\nstderr:\n").Append(Clamp(errText));

        return new ToolResult(ok, summary.ToString(), new JsonObject
        {
            ["command"] = command,
            ["cwd"] = cwd,
            ["exit_code"] = exit,
            ["stdout"] = Clamp(outText, 8000),
            ["stderr"] = Clamp(errText, 8000),
            ["duration_ms"] = stopwatch.ElapsedMilliseconds,
        }.ToJsonString());
    }

    private static string Wrap(string command) =>
        "$ProgressPreference='SilentlyContinue'; " +
        "$ErrorActionPreference='Continue'; [Console]::OutputEncoding=[Text.Encoding]::UTF8; " + command;

    /// <summary>
    /// -EncodedCommand avoids all the quoting traps of -Command: the script is
    /// passed as UTF-16LE base64, so quotes and newlines cannot escape the flag.
    /// </summary>
    internal static string PowerShellArguments(string script) =>
        "-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " +
        Convert.ToBase64String(Encoding.Unicode.GetBytes(script));

    private static string Clamp(string text, int max = 4000) =>
        text.Length <= max ? text : text[..max] + "\n… (truncated)";

    /// <summary>
    /// PowerShell serialises its progress stream as CLIXML when stderr is
    /// redirected. That blob is not an error and would otherwise be pasted into
    /// the conversation, so it is removed.
    /// </summary>
    private static string StripClixml(string text)
    {
        var marker = text.IndexOf("#< CLIXML", StringComparison.Ordinal);
        return marker < 0 ? text : text[..marker];
    }

    private static ToolResult OpenTerminal(ToolContext ctx, string cwd)
    {
        var dir = ResolveWorkingDirectory(ctx, cwd);
        Process.Start(new ProcessStartInfo
        {
            FileName = "powershell.exe",
            Arguments = "-NoExit " + PowerShellArguments(
                "Set-Location -LiteralPath '" + dir.Replace("'", "''") + "'"),
            WorkingDirectory = dir,
            UseShellExecute = true,
        });
        return ToolResult.Good($"Opened a terminal in {dir}.");
    }

    private static ToolResult StartBackground(ToolContext ctx, string command, string cwd)
    {
        command = (command ?? "").Trim();
        if (command.Length == 0) return ToolResult.Fail("No command was given.");

        var decision = new ShellPolicy(ctx.Config).Evaluate(command);
        if (decision.Verdict == ShellVerdict.Blocked) return ToolResult.Refused(decision.Reason);

        var dir = ResolveWorkingDirectory(ctx, cwd);
        var log = Path.Combine(AppPaths.LogsDir, $"bg_{DateTime.Now:yyyyMMdd_HHmmss}.log");

        var psi = new ProcessStartInfo
        {
            FileName = "powershell.exe",
            Arguments = PowerShellArguments(Wrap(command)),
            WorkingDirectory = dir,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };

        var process = new Process { StartInfo = psi, EnableRaisingEvents = true };
        var writer = new StreamWriter(log, append: false, Encoding.UTF8) { AutoFlush = true };
        process.OutputDataReceived += (_, e) => { if (e.Data is not null) lock (writer) writer.WriteLine(e.Data); };
        process.ErrorDataReceived += (_, e) => { if (e.Data is not null) lock (writer) writer.WriteLine("ERR " + e.Data); };
        process.Exited += (_, _) =>
        {
            lock (writer)
            {
                writer.WriteLine($"[friday] exited with code {SafeExit(process)}");
                writer.Dispose();
            }
        };

        if (!process.Start()) return ToolResult.Fail("The background process did not start.");
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        lock (Background) Background[process.Id] = process;

        FLog.Audit("start_background", ctx.Source, "started", command, true, ctx.DeviceId, ctx.RequestId);
        return ToolResult.Good($"Started in the background (pid {process.Id}). Log: {log}",
            new JsonObject { ["pid"] = process.Id, ["log"] = log }.ToJsonString());
    }

    private static int SafeExit(Process process)
    {
        try { return process.ExitCode; } catch { return -1; }
    }

    private static ToolResult StopProcess(int pid)
    {
        Process? process;
        lock (Background) Background.TryGetValue(pid, out process);
        if (process is null) return ToolResult.Fail($"I did not start a process with pid {pid}.");
        try
        {
            if (!process.HasExited) process.Kill(entireProcessTree: true);
            lock (Background) Background.Remove(pid);
            return ToolResult.Good($"Stopped background process {pid}.");
        }
        catch (Exception ex)
        {
            return ToolResult.Fail($"Could not stop pid {pid}: {ex.Message}");
        }
    }
}
