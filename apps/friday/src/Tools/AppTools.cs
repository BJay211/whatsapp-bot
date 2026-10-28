using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using Friday.Core;

namespace Friday.Tools;

public static class AppTools
{
    private static readonly Dictionary<string, string> Aliases = new(StringComparer.OrdinalIgnoreCase)
    {
        ["chrome"] = "chrome",
        ["google chrome"] = "chrome",
        ["browser"] = "chrome",
        ["edge"] = "msedge",
        ["microsoft edge"] = "msedge",
        ["firefox"] = "firefox",
        ["code"] = "code",
        ["vs code"] = "code",
        ["vscode"] = "code",
        ["visual studio code"] = "code",
        ["visual studio"] = "devenv",
        ["notepad"] = "notepad",
        ["calculator"] = "calc",
        ["calc"] = "calc",
        ["explorer"] = "explorer",
        ["file explorer"] = "explorer",
        ["files"] = "explorer",
        ["terminal"] = "wt",
        ["windows terminal"] = "wt",
        ["powershell"] = "powershell",
        ["cmd"] = "cmd",
        ["command prompt"] = "cmd",
        ["task manager"] = "taskmgr",
        ["settings"] = "ms-settings:",
        ["spotify"] = "spotify",
        ["whatsapp"] = "whatsapp",
        ["telegram"] = "telegram",
        ["word"] = "winword",
        ["excel"] = "excel",
        ["powerpoint"] = "powerpnt",
        ["paint"] = "mspaint",
        ["snipping tool"] = "snippingtool",
        ["camera"] = "microsoft.windows.camera:",
    };

    private static List<(string Name, string Path)>? _appCache;
    private static DateTime _cacheTime;

    public static IEnumerable<ToolDescriptor> Describe()
    {
        yield return new ToolDescriptor
        {
            Name = "open_app",
            Description = "Open an installed Windows application by name (for example Chrome, VS Code, Notepad, Spotify).",
            Capability = Capability.Apps,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"name":{"type":"string","description":"Application name as the user said it."}},"required":["name"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(OpenApp(ctx, Args.Str(args, "name"))),
        };
        yield return new ToolDescriptor
        {
            Name = "list_apps",
            Description = "List installed applications that Friday can open from the Start Menu.",
            Capability = Capability.Apps,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""{"type":"object","properties":{"filter":{"type":"string"}},"additionalProperties":false}"""),
            Handler = (ctx, args) =>
            {
                var filter = Args.Str(args, "filter");
                var apps = InstalledApps()
                    .Where(a => filter.Length == 0 || a.Name.Contains(filter, StringComparison.OrdinalIgnoreCase))
                    .Take(80)
                    .Select(a => a.Name)
                    .ToArray();
                return Task.FromResult(ToolResult.Good(
                    apps.Length == 0 ? "I found no matching applications." : string.Join(", ", apps),
                    new JsonObject { ["count"] = apps.Length, ["apps"] = new JsonArray(apps.Select(a => (JsonNode)a!).ToArray()) }.ToJsonString()));
            },
        };
        yield return new ToolDescriptor
        {
            Name = "open_url",
            Description = "Open a website (http/https) or mail link in the user's default browser.",
            Capability = Capability.Apps,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"url":{"type":"string","description":"Full URL including https://"}},"required":["url"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(OpenUrl(Args.Str(args, "url"))),
        };
        yield return new ToolDescriptor
        {
            Name = "open_path",
            Description = "Open a file or folder in its default application. Path must be inside an authorised folder.",
            Capability = Capability.Files,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(OpenPath(ctx, Args.Str(args, "path"))),
        };
        yield return new ToolDescriptor
        {
            Name = "list_windows",
            Description = "List the visible windows currently open on the desktop.",
            Capability = Capability.Windows,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""{"type":"object","properties":{},"additionalProperties":false}"""),
            Handler = (ctx, args) => Task.FromResult(ListWindows()),
        };
        yield return new ToolDescriptor
        {
            Name = "activate_window",
            Description = "Bring an open window to the front by (part of) its title.",
            Capability = Capability.Windows,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"title":{"type":"string"}},"required":["title"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(WindowAction(Args.Str(args, "title"), "focus")),
        };
        yield return new ToolDescriptor
        {
            Name = "window_action",
            Description = "Minimise, maximise, restore, focus or close a window by title. Closing can lose unsaved work, so it asks first.",
            Capability = Capability.Windows,
            Risk = RiskLevel.Confirm,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"title":{"type":"string"},"action":{"type":"string","enum":["focus","minimize","maximize","restore","close"]}},"required":["title","action"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(WindowAction(Args.Str(args, "title"), Args.Str(args, "action"))),
        };
        yield return new ToolDescriptor
        {
            Name = "active_window",
            Description = "Report the title and process of the window that currently has focus.",
            Capability = Capability.Windows,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""{"type":"object","properties":{},"additionalProperties":false}"""),
            Handler = (ctx, args) => Task.FromResult(ActiveWindow()),
        };
    }

    public static List<(string Name, string Path)> InstalledApps()
    {
        if (_appCache is not null && DateTime.UtcNow - _cacheTime < TimeSpan.FromMinutes(5)) return _appCache;

        var results = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var folders = new[]
        {
            Environment.GetFolderPath(Environment.SpecialFolder.Programs),
            Environment.GetFolderPath(Environment.SpecialFolder.CommonPrograms),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                @"Microsoft\Windows\Start Menu\Programs"),
            @"C:\ProgramData\Microsoft\Windows\Start Menu\Programs",
        };

        foreach (var folder in folders.Where(Directory.Exists).Distinct(StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                foreach (var file in Directory.EnumerateFiles(folder, "*.lnk", SearchOption.AllDirectories).Take(1200))
                {
                    var name = Path.GetFileNameWithoutExtension(file);
                    if (name.Contains("uninstall", StringComparison.OrdinalIgnoreCase)) continue;
                    if (name.Contains("readme", StringComparison.OrdinalIgnoreCase)) continue;
                    results.TryAdd(name, file);
                }
            }
            catch
            {
                // An unreadable Start Menu folder should not break the list.
            }
        }

        _appCache = results.Select(kv => (kv.Key, kv.Value)).OrderBy(a => a.Key).ToList();
        _cacheTime = DateTime.UtcNow;
        return _appCache;
    }

    private static ToolResult OpenApp(ToolContext ctx, string name)
    {
        name = (name ?? "").Trim();
        if (name.Length == 0) return ToolResult.Fail("No application name was given.");

        // 1. Known alias.
        if (Aliases.TryGetValue(name, out var alias))
        {
            try
            {
                Process.Start(new ProcessStartInfo(alias) { UseShellExecute = true });
                return ToolResult.Good($"Opened {name}.");
            }
            catch
            {
                // Fall through to the Start Menu search: the alias may be a
                // friendly name whose executable is not on PATH.
            }
        }

        // 2. Exact Start Menu match, then a contains match.
        var apps = InstalledApps();
        var match = apps.FirstOrDefault(a => a.Name.Equals(name, StringComparison.OrdinalIgnoreCase));
        if (match.Path is null)
        {
            match = apps.FirstOrDefault(a => a.Name.Contains(name, StringComparison.OrdinalIgnoreCase));
        }

        if (match.Path is not null)
        {
            Process.Start(new ProcessStartInfo(match.Path) { UseShellExecute = true });
            return ToolResult.Good($"Opened {match.Name}.");
        }

        // 3. Treat a bare word as an executable on PATH.
        if (!name.Contains(' ') && !name.Contains('\\'))
        {
            try
            {
                Process.Start(new ProcessStartInfo(name) { UseShellExecute = true });
                return ToolResult.Good($"Started {name}.");
            }
            catch
            {
                // Report below.
            }
        }

        var suggestions = apps
            .Where(a => a.Name.Contains(name.Split(' ')[0], StringComparison.OrdinalIgnoreCase))
            .Take(6)
            .Select(a => a.Name)
            .ToArray();

        return ToolResult.Fail(suggestions.Length > 0
            ? $"I could not find \"{name}\". Did you mean: {string.Join(", ", suggestions)}?"
            : $"I could not find an installed application called \"{name}\". Try \"list apps\".");
    }

    private static ToolResult OpenUrl(string url)
    {
        url = (url ?? "").Trim();
        if (url.Length == 0) return ToolResult.Fail("No URL was given.");
        if (!url.Contains("://")) url = "https://" + url;

        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri))
            return ToolResult.Fail($"\"{url}\" is not a valid URL.");

        if (uri.Scheme is not ("http" or "https" or "mailto"))
            return ToolResult.Refused($"Opening {uri.Scheme}: links is not allowed.");

        Process.Start(new ProcessStartInfo(uri.ToString()) { UseShellExecute = true });
        return ToolResult.Good($"Opened {uri.Host} in your browser.");
    }

    private static ToolResult OpenPath(ToolContext ctx, string path)
    {
        if (!ctx.Runtime.Paths.TryResolve(path, out var full, out var error, mustExist: true))
            return ToolResult.Refused(error);
        Process.Start(new ProcessStartInfo(full) { UseShellExecute = true });
        return ToolResult.Good($"Opened {full}.");
    }

    private static IEnumerable<(IntPtr Handle, string Title, string Process)> Windows()
    {
        var list = new List<(IntPtr, string, string)>();
        Native.EnumWindows((handle, _) =>
        {
            if (!Native.IsWindowVisible(handle)) return true;
            var length = Native.GetWindowTextLength(handle);
            if (length == 0) return true;
            var builder = new StringBuilder(length + 2);
            Native.GetWindowText(handle, builder, builder.Capacity);
            var title = builder.ToString();
            if (string.IsNullOrWhiteSpace(title)) return true;
            Native.GetWindowThreadProcessId(handle, out var pid);
            string process;
            try { process = Process.GetProcessById((int)pid).ProcessName; }
            catch { process = "?"; }
            list.Add((handle, title, process));
            return true;
        }, IntPtr.Zero);
        return list;
    }

    private static ToolResult ListWindows()
    {
        var windows = Windows()
            .GroupBy(w => w.Title)
            .Select(g => g.First())
            .Take(40)
            .ToArray();

        if (windows.Length == 0) return ToolResult.Good("No visible windows are open.");

        var text = string.Join(Environment.NewLine, windows.Select(w => $"{w.Process}: {w.Title}"));
        var json = new JsonArray(windows.Select(w => (JsonNode)new JsonObject
        {
            ["title"] = w.Title,
            ["process"] = w.Process,
        }).ToArray());
        return ToolResult.Good(text, json.ToJsonString());
    }

    private static ToolResult WindowAction(string title, string action)
    {
        title = (title ?? "").Trim();
        action = (action ?? "focus").Trim().ToLowerInvariant();
        if (title.Length == 0) return ToolResult.Fail("No window title was given.");

        var windows = Windows().ToArray();
        var match = windows.FirstOrDefault(w => w.Title.Equals(title, StringComparison.OrdinalIgnoreCase));
        if (match.Handle == IntPtr.Zero)
            match = windows.FirstOrDefault(w => w.Title.Contains(title, StringComparison.OrdinalIgnoreCase));

        if (match.Handle == IntPtr.Zero)
        {
            var nearby = windows.Where(w => w.Title.Contains(title.Split(' ')[0], StringComparison.OrdinalIgnoreCase))
                .Take(5).Select(w => w.Title).ToArray();
            return ToolResult.Fail(nearby.Length > 0
                ? $"I could not match \"{title}\". Open windows include: {string.Join("; ", nearby)}"
                : $"No open window matches \"{title}\".");
        }

        switch (action)
        {
            case "close":
                Native.PostMessage(match.Handle, Native.WM_CLOSE, IntPtr.Zero, IntPtr.Zero);
                return ToolResult.Good($"Asked \"{match.Title}\" to close.");
            case "minimize":
                Native.ShowWindow(match.Handle, Native.SW_MINIMIZE);
                return ToolResult.Good($"Minimised \"{match.Title}\".");
            case "maximize":
                Native.ShowWindow(match.Handle, Native.SW_MAXIMIZE);
                return ToolResult.Good($"Maximised \"{match.Title}\".");
            case "restore":
                Native.ShowWindow(match.Handle, Native.SW_RESTORE);
                return ToolResult.Good($"Restored \"{match.Title}\".");
            default:
                Native.ShowWindow(match.Handle, Native.SW_RESTORE);
                if (!Native.SetForegroundWindow(match.Handle))
                    return ToolResult.Fail(
                        $"Windows would not let me focus \"{match.Title}\". Click it once, then ask again.");
                return ToolResult.Good($"Focused \"{match.Title}\".");
        }
    }

    private static ToolResult ActiveWindow()
    {
        var handle = Native.GetForegroundWindow();
        var length = Native.GetWindowTextLength(handle);
        if (length == 0) return ToolResult.Good("No window has focus right now.");
        var builder = new StringBuilder(length + 2);
        Native.GetWindowText(handle, builder, builder.Capacity);
        Native.GetWindowThreadProcessId(handle, out var pid);
        string process;
        try { process = Process.GetProcessById((int)pid).ProcessName; }
        catch { process = "?"; }
        return ToolResult.Good($"Active window: {builder} ({process})");
    }
}
