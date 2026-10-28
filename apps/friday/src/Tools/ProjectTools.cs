using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Friday.Core;

namespace Friday.Tools;

public static class ProjectTools
{
    private static readonly string[] Markers =
    {
        ".git", "package.json", "build.gradle", "settings.gradle", "pyproject.toml",
        "requirements.txt", "Cargo.toml", "go.mod", "*.sln", "*.csproj", "pubspec.yaml",
    };

    public static IEnumerable<ToolDescriptor> Describe()
    {
        yield return new ToolDescriptor
        {
            Name = "list_projects",
            Description = "Find development projects inside the authorised folders.",
            Capability = Capability.Project,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""{"type":"object","properties":{"root":{"type":"string"}},"additionalProperties":false}"""),
            Handler = (ctx, args) => Task.FromResult(ListProjects(ctx, Args.Str(args, "root"))),
        };
        yield return new ToolDescriptor
        {
            Name = "inspect_project",
            Description = "Describe a project: its type, size, README summary, git branch and top-level layout.",
            Capability = Capability.Project,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(Inspect(ctx, Args.Str(args, "path"))),
        };
        yield return new ToolDescriptor
        {
            Name = "git_status",
            Description = "Show git status and the current branch for a project.",
            Capability = Capability.Project,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => GitAsync(ctx, Args.Str(args, "path"), "status --short --branch", "status"),
        };
        yield return new ToolDescriptor
        {
            Name = "git_diff",
            Description = "Show the uncommitted diff for a project (optionally staged).",
            Capability = Capability.Project,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"path":{"type":"string"},"staged":{"type":"boolean"}},"required":["path"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => GitAsync(ctx, Args.Str(args, "path"),
                Args.Bool(args, "staged") ? "diff --cached --stat" : "diff --stat", "diff"),
        };
        yield return new ToolDescriptor
        {
            Name = "run_tests",
            Description = "Detect the project's test command and run it (gradle, npm, dotnet or pytest).",
            Capability = Capability.Project,
            Risk = RiskLevel.Confirm,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"path":{"type":"string"},"timeout_seconds":{"type":"integer"}},"required":["path"],"additionalProperties":false}
            """),
            Handler = RunTestsAsync,
        };
        yield return new ToolDescriptor
        {
            Name = "search_code",
            Description = "Search text across source files in a project and return matching lines.",
            Capability = Capability.Project,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"query":{"type":"string"},"path":{"type":"string"},"max":{"type":"integer"}},"required":["query"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(SearchCode(ctx, Args.Str(args, "query"), Args.Str(args, "path"), Args.Int(args, "max", 40))),
        };
    }

    private static ToolResult ListProjects(ToolContext ctx, string root)
    {
        var roots = new List<string>();
        if (!string.IsNullOrWhiteSpace(root))
        {
            if (!ctx.Runtime.Paths.TryResolve(root, out var full, out var error, mustExist: true))
                return ToolResult.Refused(error);
            roots.Add(full);
        }
        else
        {
            roots.AddRange(ctx.Runtime.Paths.Roots);
        }

        var found = new List<string>();
        foreach (var start in roots)
        {
            var stack = new Stack<(string Path, int Depth)>();
            stack.Push((start, 0));
            var visited = 0;
            while (stack.Count > 0 && visited < 4000 && found.Count < 60)
            {
                var (dir, depth) = stack.Pop();
                visited++;
                try
                {
                    if (LooksLikeProject(dir))
                    {
                        found.Add(dir);
                        continue;
                    }

                    if (depth >= 3) continue;
                    foreach (var child in Directory.EnumerateDirectories(dir))
                    {
                        var name = Path.GetFileName(child);
                        if (name.StartsWith('.') || name.Equals("node_modules", StringComparison.OrdinalIgnoreCase) ||
                            name.Equals("AppData", StringComparison.OrdinalIgnoreCase) ||
                            name.Equals("Windows", StringComparison.OrdinalIgnoreCase))
                            continue;
                        stack.Push((child, depth + 1));
                    }
                }
                catch
                {
                    // Unreadable folders are skipped.
                }
            }
        }

        if (found.Count == 0) return ToolResult.Good("I found no projects in the authorised folders.");
        return ToolResult.Good(string.Join(Environment.NewLine, found.Distinct().OrderBy(f => f)));
    }

    private static bool LooksLikeProject(string dir)
    {
        if (Directory.Exists(Path.Combine(dir, ".git"))) return true;
        foreach (var marker in new[] { "package.json", "build.gradle", "pyproject.toml", "requirements.txt", "Cargo.toml", "go.mod", "pubspec.yaml" })
        {
            if (File.Exists(Path.Combine(dir, marker))) return true;
        }
        try
        {
            return Directory.EnumerateFiles(dir, "*.csproj").Any() ||
                   Directory.EnumerateFiles(dir, "*.sln").Any();
        }
        catch
        {
            return false;
        }
    }

    private static string DetectType(string dir)
    {
        if (File.Exists(Path.Combine(dir, "build.gradle"))) return "Android / Gradle (Kotlin or Java)";
        if (File.Exists(Path.Combine(dir, "package.json"))) return "Node / web project";
        if (File.Exists(Path.Combine(dir, "pyproject.toml")) || File.Exists(Path.Combine(dir, "requirements.txt"))) return "Python";
        if (File.Exists(Path.Combine(dir, "Cargo.toml"))) return "Rust";
        if (File.Exists(Path.Combine(dir, "go.mod"))) return "Go";
        if (File.Exists(Path.Combine(dir, "pubspec.yaml"))) return "Flutter / Dart";
        if (Directory.EnumerateFiles(dir, "*.sln").Any() || Directory.EnumerateFiles(dir, "*.csproj").Any()) return ".NET";
        return "Unknown";
    }

    private static ToolResult Inspect(ToolContext ctx, string path)
    {
        if (!ctx.Runtime.Paths.TryResolve(path, out var dir, out var error, mustExist: true))
            return ToolResult.Refused(error);
        if (!Directory.Exists(dir)) return ToolResult.Fail($"{dir} is a file, not a project folder.");

        var sb = new StringBuilder();
        sb.AppendLine("Project: " + dir);
        sb.AppendLine("Type: " + DetectType(dir));

        try
        {
            var files = Directory.EnumerateFiles(dir, "*", SearchOption.AllDirectories)
                .Take(20000).ToArray();
            sb.AppendLine($"Files: {files.Length} (counted up to 20,000)");
            var byExtension = files
                .GroupBy(f => Path.GetExtension(f).ToLowerInvariant())
                .Where(g => g.Key.Length > 1)
                .OrderByDescending(g => g.Count())
                .Take(8)
                .Select(g => $"{g.Key} {g.Count()}");
            sb.AppendLine("Main file types: " + string.Join(", ", byExtension));
        }
        catch (Exception ex)
        {
            sb.AppendLine("File scan: " + ex.Message);
        }

        var readme = Directory.EnumerateFiles(dir, "README*").FirstOrDefault();
        if (readme is not null)
        {
            try
            {
                var text = File.ReadAllText(readme);
                if (text.Length > 1200) text = text[..1200] + "…";
                sb.AppendLine();
                sb.AppendLine("README:");
                sb.AppendLine(text.Trim());
            }
            catch { }
        }

        if (Directory.Exists(Path.Combine(dir, ".git")))
        {
            var branch = RunGit(dir, "rev-parse --abbrev-ref HEAD", 8);
            if (branch.ok) sb.AppendLine().AppendLine("Git branch: " + branch.output.Trim());
            var status = RunGit(dir, "status --short", 8);
            if (status.ok)
            {
                var lines = status.output.Split('\n', StringSplitOptions.RemoveEmptyEntries);
                sb.AppendLine(lines.Length == 0 ? "Working tree: clean" : $"Working tree: {lines.Length} changed path(s)");
            }
        }

        return ToolResult.Good(sb.ToString().Trim());
    }

    private static async Task<ToolResult> GitAsync(ToolContext ctx, string path, string arguments, string label)
    {
        if (!ctx.Runtime.Paths.TryResolve(path, out var dir, out var error, mustExist: true))
            return ToolResult.Refused(error);
        if (!Directory.Exists(Path.Combine(dir, ".git")) &&
            !Directory.Exists(Path.GetDirectoryName(dir) is { } parent ? Path.Combine(parent, ".git") : ""))
        {
            // A subfolder of a repository is fine; git itself will confirm.
        }

        var (exit, output, err) = await RunCapturedAsync("git " + arguments, dir, 30, ctx.Token).ConfigureAwait(false);
        if (exit != 0)
            return ToolResult.Fail($"git {label} failed (exit {exit}).\n{err}\n{output}".Trim());
        return ToolResult.Good(output.Trim().Length == 0 ? $"git {label}: no output." : output.Trim());
    }

    private static async Task<ToolResult> RunTestsAsync(ToolContext ctx, JsonObject args)
    {
        if (!ctx.Runtime.Paths.TryResolve(Args.Str(args, "path"), out var dir, out var error, mustExist: true))
            return ToolResult.Refused(error);

        string command;
        string label;
        var timeout = Math.Clamp(Args.Int(args, "timeout_seconds", 600), 30, 3600);

        if (File.Exists(Path.Combine(dir, "gradlew.bat")))
        {
            command = ".\\gradlew.bat test";
            label = "Gradle tests";
        }
        else if (File.Exists(Path.Combine(dir, "package.json")))
        {
            command = "npm test -- --runInBand";
            label = "npm tests";
        }
        else if (Directory.EnumerateFiles(dir, "*.sln").Any() || Directory.EnumerateFiles(dir, "*.csproj").Any())
        {
            command = "dotnet test";
            label = "dotnet tests";
        }
        else if (File.Exists(Path.Combine(dir, "pyproject.toml")) || File.Exists(Path.Combine(dir, "pytest.ini")))
        {
            command = "python -m pytest -q";
            label = "pytest";
        }
        else
        {
            return ToolResult.Fail(
                "I could not detect a test command in that folder. " +
                "Supported: gradlew, package.json, .sln/.csproj, pyproject.toml/pytest.ini.");
        }

        ctx.Report("Running " + label + "…");
        var (exit, output, err) = await RunCapturedAsync(command, dir, timeout, ctx.Token).ConfigureAwait(false);
        var body = string.Join("\n", new[] { output.Trim(), err.Trim() }.Where(s => s.Length > 0));
        if (body.Length > 8000) body = body[..8000] + "\n… (truncated)";
        return new ToolResult(exit == 0,
            $"{label}: exit {exit}.\n\n{body}",
            new JsonObject { ["exit_code"] = exit, ["command"] = command }.ToJsonString());
    }

    private static ToolResult SearchCode(ToolContext ctx, string query, string path, int max)
    {
        query = (query ?? "").Trim();
        if (query.Length == 0) return ToolResult.Fail("What should I search for?");

        string start;
        if (!string.IsNullOrWhiteSpace(path))
        {
            if (!ctx.Runtime.Paths.TryResolve(path, out start, out var error, mustExist: true))
                return ToolResult.Refused(error);
        }
        else
        {
            start = ctx.Runtime.Paths.Roots.FirstOrDefault() ?? "";
        }
        if (string.IsNullOrEmpty(start)) return ToolResult.Fail("No authorised folder to search.");

        var results = new List<string>();
        Regex? regex = null;
        try { regex = new Regex(query, RegexOptions.IgnoreCase); }
        catch { }

        var extensions = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            ".cs", ".kt", ".java", ".py", ".js", ".jsx", ".ts", ".tsx", ".json", ".xml",
            ".gradle", ".md", ".txt", ".yml", ".yaml", ".html", ".css", ".ps1", ".sh",
        };

        var stack = new Stack<string>();
        stack.Push(start);
        var scanned = 0;
        while (stack.Count > 0 && results.Count < Math.Clamp(max, 1, 200) && scanned < 6000)
        {
            var dir = stack.Pop();
            try
            {
                foreach (var child in Directory.EnumerateDirectories(dir))
                {
                    var name = Path.GetFileName(child);
                    if (name.Equals(".git", StringComparison.OrdinalIgnoreCase) ||
                        name.Equals("node_modules", StringComparison.OrdinalIgnoreCase) ||
                        name.Equals("build", StringComparison.OrdinalIgnoreCase) ||
                        name.Equals("bin", StringComparison.OrdinalIgnoreCase) ||
                        name.Equals("obj", StringComparison.OrdinalIgnoreCase)) continue;
                    stack.Push(child);
                }

                foreach (var file in Directory.EnumerateFiles(dir))
                {
                    if (!extensions.Contains(Path.GetExtension(file))) continue;
                    scanned++;
                    if (scanned > 6000) break;
                    var info = new FileInfo(file);
                    if (info.Length > 2 * 1024 * 1024) continue;
                    var lines = File.ReadAllLines(file);
                    for (var i = 0; i < lines.Length; i++)
                    {
                        var hit = regex is not null
                            ? regex.IsMatch(lines[i])
                            : lines[i].Contains(query, StringComparison.OrdinalIgnoreCase);
                        if (!hit) continue;
                        var line = lines[i].Trim();
                        if (line.Length > 220) line = line[..220] + "…";
                        results.Add($"{file}:{i + 1}: {line}");
                        if (results.Count >= max) break;
                    }
                    if (results.Count >= max) break;
                }
            }
            catch
            {
                // Skip unreadable folders.
            }
        }

        return ToolResult.Good(results.Count == 0
            ? $"No matches for \"{query}\"."
            : string.Join(Environment.NewLine, results));
    }

    private static (bool ok, string output) RunGit(string dir, string args, int timeoutSeconds)
    {
        try
        {
            var psi = new ProcessStartInfo("git", args)
            {
                WorkingDirectory = dir,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            using var process = Process.Start(psi);
            if (process is null) return (false, "");
            var output = process.StandardOutput.ReadToEnd() + process.StandardError.ReadToEnd();
            process.WaitForExit(timeoutSeconds * 1000);
            return (process.ExitCode == 0, output);
        }
        catch
        {
            return (false, "");
        }
    }

    private static async Task<(int exit, string output, string error)> RunCapturedAsync(
        string command, string cwd, int timeoutSeconds, CancellationToken token)
    {
        var encoded = Convert.ToBase64String(Encoding.Unicode.GetBytes(
            "$ErrorActionPreference='Continue'; [Console]::OutputEncoding=[Text.Encoding]::UTF8; " + command));

        var psi = new ProcessStartInfo("powershell.exe",
            "-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " + encoded)
        {
            WorkingDirectory = cwd,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };

        using var process = new Process { StartInfo = psi };
        var output = new StringBuilder();
        var error = new StringBuilder();
        process.OutputDataReceived += (_, e) => { if (e.Data is not null) output.AppendLine(e.Data); };
        process.ErrorDataReceived += (_, e) => { if (e.Data is not null) error.AppendLine(e.Data); };
        process.Start();
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();

        using var cts = CancellationTokenSource.CreateLinkedTokenSource(token);
        cts.CancelAfter(TimeSpan.FromSeconds(timeoutSeconds));
        try
        {
            await process.WaitForExitAsync(cts.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            try { process.Kill(entireProcessTree: true); } catch { }
            return (-1, output.ToString(), token.IsCancellationRequested
                ? "Cancelled by the user."
                : $"Timed out after {timeoutSeconds}s.");
        }

        return (process.ExitCode, output.ToString(), error.ToString());
    }
}
