using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using Microsoft.Win32;
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
        ["brave"] = "brave",
        ["opera"] = "opera",
        ["discord"] = "discord",
        ["steam"] = "steam",
        ["vlc"] = "vlc",
        ["obsidian"] = "obsidian",
        ["notion"] = "notion",
        ["slack"] = "slack",
        ["zoom"] = "zoom",
        ["teams"] = "teams",
        ["microsoft teams"] = "teams",
        ["outlook"] = "outlook",
        ["mail"] = "outlook",
        ["wordpad"] = "wordpad",
        ["control panel"] = "control",
        ["device manager"] = "devmgmt",
        ["services"] = "services",
        ["7 zip"] = "7z",
        ["7zip"] = "7z",
        ["git bash"] = "git-bash",
        ["store"] = "ms-windows-store:",
        ["microsoft store"] = "ms-windows-store:",
        ["xbox"] = "xbox",
    };

    /// <summary>
    /// Known install locations, tried before the Start Menu so a folder that
    /// happens to share an app's name can never be opened instead of the app.
    /// </summary>
    private static readonly Dictionary<string, string[]> KnownExecutables = new(StringComparer.OrdinalIgnoreCase)
    {
        ["chrome"] = new[]
        {
            @"%ProgramFiles%\Google\Chrome\Application\chrome.exe",
            @"%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe",
            @"%LocalAppData%\Google\Chrome\Application\chrome.exe",
        },
        ["msedge"] = new[]
        {
            @"%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe",
            @"%ProgramFiles%\Microsoft\Edge\Application\msedge.exe",
        },
        ["firefox"] = new[]
        {
            @"%ProgramFiles%\Mozilla Firefox\firefox.exe",
            @"%ProgramFiles(x86)%\Mozilla Firefox\firefox.exe",
        },
        ["brave"] = new[]
        {
            @"%ProgramFiles%\BraveSoftware\Brave-Browser\Application\brave.exe",
            @"%LocalAppData%\BraveSoftware\Brave-Browser\Application\brave.exe",
        },
        ["opera"] = new[]
        {
            @"%LocalAppData%\Programs\Opera\opera.exe",
            @"%ProgramFiles%\Opera\opera.exe",
        },
        ["code"] = new[]
        {
            @"%LocalAppData%\Programs\Microsoft VS Code\Code.exe",
            @"%ProgramFiles%\Microsoft VS Code\Code.exe",
        },
        ["devenv"] = new[]
        {
            @"%ProgramFiles%\Microsoft Visual Studio\2022\Community\Common7\IDE\devenv.exe",
            @"%ProgramFiles%\Microsoft Visual Studio\2022\Professional\Common7\IDE\devenv.exe",
            @"%ProgramFiles%\Microsoft Visual Studio\2022\Enterprise\Common7\IDE\devenv.exe",
            @"%ProgramFiles%\Microsoft Visual Studio\2022\BuildTools\Common7\IDE\devenv.exe",
        },
        ["notepad"] = new[] { @"%SystemRoot%\System32\notepad.exe", @"%SystemRoot%\notepad.exe" },
        ["calc"] = new[] { @"%SystemRoot%\System32\calc.exe" },
        ["mspaint"] = new[] { @"%SystemRoot%\System32\mspaint.exe" },
        ["explorer"] = new[] { @"%SystemRoot%\explorer.exe" },
        ["cmd"] = new[] { @"%SystemRoot%\System32\cmd.exe" },
        ["powershell"] = new[] { @"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" },
        ["pwsh"] = new[] { @"%ProgramFiles%\PowerShell\7\pwsh.exe" },
        ["wt"] = new[] { @"%LocalAppData%\Microsoft\WindowsApps\wt.exe" },
        ["taskmgr"] = new[] { @"%SystemRoot%\System32\Taskmgr.exe" },
        ["snippingtool"] = new[]
        {
            @"%SystemRoot%\System32\SnippingTool.exe",
            @"%LocalAppData%\Microsoft\WindowsApps\SnippingTool.exe",
        },
        ["spotify"] = new[]
        {
            @"%AppData%\Spotify\Spotify.exe",
            @"%LocalAppData%\Microsoft\WindowsApps\Spotify.exe",
        },
        ["vlc"] = new[]
        {
            @"%ProgramFiles%\VideoLAN\VLC\vlc.exe",
            @"%ProgramFiles(x86)%\VideoLAN\VLC\vlc.exe",
        },
        ["steam"] = new[] { @"%ProgramFiles(x86)%\Steam\steam.exe", @"%ProgramFiles%\Steam\steam.exe" },
        ["obsidian"] = new[] { @"%LocalAppData%\Obsidian\Obsidian.exe" },
        ["7z"] = new[]
        {
            @"%ProgramFiles%\7-Zip\7zFM.exe",
            @"%ProgramFiles(x86)%\7-Zip\7zFM.exe",
        },
        ["git-bash"] = new[] { @"%ProgramFiles%\Git\git-bash.exe", @"%ProgramFiles(x86)%\Git\git-bash.exe" },
        ["discord"] = new[] { @"%LocalAppData%\Discord\Update.exe" },
        ["zoom"] = new[] { @"%AppData%\Zoom\bin\Zoom.exe" },
        ["teams"] = new[] { @"%LocalAppData%\Microsoft\Teams\current\Teams.exe" },
        ["slack"] = new[] { @"%LocalAppData%\slack\slack.exe" },
        ["winword"] = new[] { @"%ProgramFiles%\Microsoft Office\root\Office16\WINWORD.EXE" },
        ["excel"] = new[] { @"%ProgramFiles%\Microsoft Office\root\Office16\EXCEL.EXE" },
        ["powerpnt"] = new[] { @"%ProgramFiles%\Microsoft Office\root\Office16\POWERPNT.EXE" },
    };

    private static readonly Dictionary<string, string> ImplicitExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ["text"] = "txt", ["txt"] = "txt",
        ["pdf"] = "pdf", ["word"] = "docx", ["doc"] = "docx", ["docx"] = "docx",
        ["excel"] = "xlsx", ["xls"] = "xlsx", ["xlsx"] = "xlsx", ["spreadsheet"] = "xlsx",
        ["powerpoint"] = "pptx", ["ppt"] = "pptx", ["pptx"] = "pptx", ["slides"] = "pptx",
        ["markdown"] = "md", ["md"] = "md", ["json"] = "json",
        ["jpg"] = "jpg", ["jpeg"] = "jpg", ["png"] = "png", ["image"] = "png",
        ["zip"] = "zip", ["csv"] = "csv", ["log"] = "log", ["xml"] = "xml",
        ["html"] = "html", ["css"] = "css", ["javascript"] = "js", ["js"] = "js",
        ["typescript"] = "ts", ["ts"] = "ts", ["python"] = "py", ["py"] = "py",
        ["mp3"] = "mp3", ["mp4"] = "mp4", ["wav"] = "wav", ["exe"] = "exe",
    };

    private static readonly Dictionary<string, (string Path, DateTime At)> LaunchCache =
        new(StringComparer.OrdinalIgnoreCase);
    private static readonly object LaunchGate = new();

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
            Name = "open_target",
            Description =
                "Open whatever the user named: an application, a file or a folder. " +
                "Bare names prefer an installed app (\"chrome\" opens Chrome, never a Chrome folder); " +
                "names with a file extension or a path prefer the file. Spoken extensions such as " +
                "\"dave dot text\" are understood.",
            Capability = Capability.Apps,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"target":{"type":"string","description":"What the user asked to open, as spoken."}},"required":["target"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(OpenTarget(ctx, Args.Str(args, "target"))),
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

    /// <summary>Launches or focuses an application by name.</summary>
    private static ToolResult OpenApp(ToolContext ctx, string name)
    {
        var sw = Stopwatch.StartNew();
        name = (name ?? "").Trim().Trim('"');
        if (name.Length == 0) return ToolResult.Fail("No application name was given.");

        var resolved = ResolveLaunch(name);
        if (resolved is null)
        {
            var suggestions = AppSuggestions(name);
            return ToolResult.Fail(suggestions.Length > 0
                ? $"I could not find \"{name}\". Did you mean: {string.Join(", ", suggestions)}?"
                : $"I could not find an installed application called \"{name}\". Try \"list apps\".");
        }

        return Launch(resolved, sw, name);
    }

    /// <summary>
    /// The single entry point behind "open ...". It decides whether the words are
    /// an app, a file or a folder, and always launches a resolved full path so a
    /// folder with the same name can never win. Bare names prefer apps; a spoken
    /// extension or a path prefers the file.
    /// </summary>
    private static ToolResult OpenTarget(ToolContext ctx, string raw)
    {
        var sw = Stopwatch.StartNew();
        var target = NormaliseSpokenTarget(raw);
        if (target.Length == 0) return ToolResult.Fail("What should I open?");

        // 1. Web links.
        if (Regex.IsMatch(target, @"^(https?://|www\.)", RegexOptions.IgnoreCase))
            return OpenUrl(target);

        var roots = ctx.Runtime.Paths.Roots;

        // 2. An explicit path (absolute, ~, or containing a separator). If it is
        //    absolute and missing, say so instead of guessing an app with that name.
        if (LooksLikePath(target))
        {
            if (ctx.Runtime.Paths.TryResolve(target, out var full, out var error, mustExist: true))
                return OpenResolved(ctx, full, sw);
            if (Path.IsPathRooted(target)) return ToolResult.Refused(error);
        }

        // 3. A name with an extension is a file: probe, then index, then a short walk.
        if (HasExtension(target))
        {
            var file = QuickIndex.FindFile(roots, target);
            if (file is not null && ctx.Runtime.Paths.TryResolve(file, out var fileFull, out _, mustExist: true))
                return OpenResolved(ctx, fileFull, sw);

            var near = QuickIndex.Suggestions(target);
            return ToolResult.Fail(near.Length > 0
                ? $"I could not find \"{target}\". Closest matches: {string.Join(", ", near)}."
                : $"I could not find a file called \"{target}\" in your authorised folders.");
        }

        // 4. Well-known folders ("desktop", "downloads", "my documents").
        var folderAlias = FolderAlias(target);
        if (folderAlias is not null && Directory.Exists(folderAlias))
        {
            if (ctx.Runtime.Paths.TryResolve(folderAlias, out var aliasFull, out _, mustExist: true))
                return OpenResolved(ctx, aliasFull, sw);
        }

        // 5. "open my project" means the last project Friday worked in.
        if (Regex.IsMatch(target, @"^(?:the\s+)?(?:my\s+)?(?:current\s+|last\s+)?(?:project|repo|repository)$",
                RegexOptions.IgnoreCase))
        {
            var last = ctx.Runtime.LastProjectPath;
            if (!string.IsNullOrWhiteSpace(last) && Directory.Exists(last) &&
                ctx.Runtime.Paths.TryResolve(last, out var projectFull, out _, mustExist: true))
                return OpenResolved(ctx, projectFull, sw);
        }

        // 6. Applications next, so "chrome" launches Chrome and not a folder called
        //    "chrome" that happens to sit on the Desktop.
        var resolved = ResolveLaunch(target);
        if (resolved is not null) return Launch(resolved, sw, target);

        // 7. A folder or file whose name does not look like one ("vendora").
        var projectHint = Regex.Replace(target, @"\s+(?:project|folder|directory)$", "", RegexOptions.IgnoreCase).Trim();
        if (projectHint.Length >= 3)
        {
            var folder = QuickIndex.FindFolder(roots, projectHint);
            if (folder is not null && ctx.Runtime.Paths.TryResolve(folder, out var folderFull, out _, mustExist: true))
            {
                RememberProject(ctx, folderFull);
                return OpenResolved(ctx, folderFull, sw);
            }

            var looseFile = QuickIndex.FindFile(roots, projectHint);
            if (looseFile is not null && ctx.Runtime.Paths.TryResolve(looseFile, out var looseFull, out _, mustExist: true))
                return OpenResolved(ctx, looseFull, sw);
        }

        var suggestions = AppSuggestions(target).Concat(QuickIndex.Suggestions(projectHint)).Take(6).ToArray();
        return ToolResult.Fail(suggestions.Length > 0
            ? $"I could not find \"{target}\". Did you mean: {string.Join(", ", suggestions)}?"
            : $"I could not find an app, file or folder called \"{target}\".");
    }

    private static ToolResult Launch(LaunchTarget resolved, Stopwatch sw, string spoken)
    {
        if (resolved.IsProtocol)
        {
            try
            {
                Process.Start(new ProcessStartInfo(resolved.Path) { UseShellExecute = true });
                return ToolResult.Good($"Opened {spoken} in {sw.ElapsedMilliseconds} ms.");
            }
            catch (Exception ex)
            {
                return ToolResult.Fail($"Windows could not open {spoken}: {ex.Message}");
            }
        }

        if (resolved.ProcessName is not null && TryActivate(resolved.ProcessName, out var title))
            return ToolResult.Good($"Brought {title} to the front in {sw.ElapsedMilliseconds} ms.");

        try
        {
            var info = new ProcessStartInfo(resolved.Path) { UseShellExecute = true };
            var directory = Path.GetDirectoryName(resolved.Path);
            if (!string.IsNullOrWhiteSpace(directory) && Directory.Exists(directory))
                info.WorkingDirectory = directory;

            Process.Start(info);
            return ToolResult.Good($"Opened {spoken} in {sw.ElapsedMilliseconds} ms.");
        }
        catch (Exception ex)
        {
            return ToolResult.Fail($"I resolved {resolved.Label} to {resolved.Path} but Windows would not start it: {ex.Message}");
        }
    }

    private static ToolResult OpenResolved(ToolContext ctx, string full, Stopwatch sw)
    {
        var isFolder = Directory.Exists(full);
        try
        {
            Process.Start(new ProcessStartInfo(full) { UseShellExecute = true });
            if (isFolder) RememberProject(ctx, full);
            var kind = isFolder ? "folder" : "file";
            return ToolResult.Good($"Opened the {kind} {full} in {sw.ElapsedMilliseconds} ms.",
                new JsonObject { ["path"] = full, ["kind"] = kind, ["ms"] = sw.ElapsedMilliseconds }.ToJsonString());
        }
        catch (Exception ex)
        {
            return ToolResult.Fail(
                $"I found {full} but Windows has no working association to open it: {ex.Message}");
        }
    }

    private static void RememberProject(ToolContext ctx, string folder)
    {
        try
        {
            if (Directory.Exists(Path.Combine(folder, ".git")) ||
                File.Exists(Path.Combine(folder, "package.json")) ||
                File.Exists(Path.Combine(folder, "build.gradle")) ||
                Directory.Exists(Path.Combine(folder, ".git")))
            {
                ctx.Runtime.LastProjectPath = folder;
            }
        }
        catch { }
    }

    /// <summary>
    /// Turns dictation into something a file system understands: "dave dot text"
    /// and "dave text" become dave.txt, and trailing "file/folder/app" noise is
    /// removed. Public so the self-test can exercise it without a microphone.
    /// </summary>
    public static string NormaliseSpokenTarget(string raw)
    {
        var text = (raw ?? "").Trim().Trim('"', '\'').Trim();
        if (text.Length == 0) return "";

        text = Regex.Replace(text,
            @"\s+(?:file|folder|directory|app|application|window|please|for me|" +
            @"on (?:my|the) (?:laptop|computer|pc))$",
            "", RegexOptions.IgnoreCase).Trim();

        // "dave dot text" -> "dave.txt"; also "dave dot t x t".
        text = Regex.Replace(text, @"\s+dot\s+(?<ext>[a-zA-Z][a-zA-Z ]{0,11})$", m =>
        {
            var spoken = m.Groups["ext"].Value.Replace(" ", "").ToLowerInvariant();
            return "." + (ImplicitExtensions.TryGetValue(spoken, out var ext) ? ext : spoken);
        }, RegexOptions.IgnoreCase);

        // The recogniser often drops "dot": "dave text" -> "dave.txt".
        if (!HasExtension(text) && !Aliases.ContainsKey(text))
        {
            var split = text.LastIndexOf(' ');
            if (split > 0)
            {
                var last = text[(split + 1)..].Trim().ToLowerInvariant();
                if (ImplicitExtensions.TryGetValue(last, out var ext) && last.Length > 2)
                    text = text[..split] + "." + ext;
            }
        }

        return text.Trim();
    }

    private static bool HasExtension(string target) =>
        Regex.IsMatch(target, @"\.[a-zA-Z][a-zA-Z0-9]{0,5}$", RegexOptions.IgnoreCase);

    private static bool LooksLikePath(string target) =>
        target.Contains('\\') || target.Contains('/') || target.StartsWith('~') ||
        Regex.IsMatch(target, @"^[a-zA-Z]:", RegexOptions.None);

    /// <summary>Well-known user folders, so "open downloads" is instant.</summary>
    public static string? FolderAlias(string name)
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var key = Regex.Replace((name ?? "").Trim().ToLowerInvariant(), @"\s+", " ");
        key = Regex.Replace(key, @"^(?:the|my)\s+", "");
        key = Regex.Replace(key, @"\s+(?:folder|directory)$", "");
        return key switch
        {
            "desktop" => Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
            "documents" or "document" or "docs" => Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
            "downloads" or "download" => Path.Combine(home, "Downloads"),
            "pictures" or "photos" => Environment.GetFolderPath(Environment.SpecialFolder.MyPictures),
            "music" => Environment.GetFolderPath(Environment.SpecialFolder.MyMusic),
            "videos" or "movies" => Environment.GetFolderPath(Environment.SpecialFolder.MyVideos),
            "home" or "profile" or "user folder" or "home folder" => home,
            _ => null,
        };
    }

    /// <summary>
    /// Resolves a spoken app name to a real executable (or a Start Menu shortcut).
    /// Order: alias, App Paths registry, known install folders, PATH, Start Menu.
    /// </summary>
    public static LaunchTarget? ResolveLaunch(string name)
    {
        name = (name ?? "").Trim().Trim('"');
        if (name.Length == 0) return null;

        lock (LaunchGate)
        {
            if (LaunchCache.TryGetValue(name, out var cached) &&
                DateTime.UtcNow - cached.At < TimeSpan.FromMinutes(5) &&
                (cached.Path.Contains(':') && !cached.Path.Contains(":\\") || File.Exists(cached.Path)))
            {
                return BuildTarget(cached.Path, name);
            }
        }

        var key = name;
        if (Aliases.TryGetValue(name, out var alias)) key = alias;

        // Protocols (Settings, Store, Camera) are not files.
        if (key.EndsWith(':')) return new LaunchTarget(key, name, null, true);

        // An explicit executable path.
        if (File.Exists(name) && Path.GetExtension(name).Equals(".exe", StringComparison.OrdinalIgnoreCase))
            return BuildTarget(Path.GetFullPath(name), name);

        var found = FindExecutable(key) ?? FindExecutable(name);
        if (found is null)
        {
            // Start Menu shortcuts are safe to launch: the shell follows the link.
            var apps = InstalledApps();
            var match = apps.FirstOrDefault(a => a.Name.Equals(name, StringComparison.OrdinalIgnoreCase));
            match = match.Path is null
                ? apps.FirstOrDefault(a => a.Name.Equals(key, StringComparison.OrdinalIgnoreCase))
                : match;
            if (match.Path is null)
            {
                var folded = name.Replace(" ", "").Replace("-", "");
                match = apps.FirstOrDefault(a =>
                    a.Name.Replace(" ", "").Replace("-", "").Equals(folded, StringComparison.OrdinalIgnoreCase));
            }
            if (match.Path is not null) found = match.Path;
        }

        if (found is null) return null;

        lock (LaunchGate)
        {
            LaunchCache[name] = (found, DateTime.UtcNow);
        }
        return BuildTarget(found, name);
    }

    private static LaunchTarget BuildTarget(string path, string label)
    {
        var process = Path.GetExtension(path).Equals(".exe", StringComparison.OrdinalIgnoreCase)
            ? Path.GetFileNameWithoutExtension(path)
            : null;
        return new LaunchTarget(path, label, process, false);
    }

    private static string? FindExecutable(string name)
    {
        if (string.IsNullOrWhiteSpace(name)) return null;

        var withExe = name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ? name : name + ".exe";

        // App Paths is where installers register Chrome, Office and friends.
        foreach (var hive in new[] { Registry.LocalMachine, Registry.CurrentUser })
        {
            try
            {
                using var appPaths = hive.OpenSubKey(
                    @"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\" + withExe);
                if (appPaths?.GetValue("") is string registered && File.Exists(registered))
                    return registered;
            }
            catch { }
        }

        // Known install folders, checked before any name-based search.
        if (KnownExecutables.TryGetValue(name, out var candidates))
        {
            foreach (var candidate in candidates)
            {
                var expanded = Environment.ExpandEnvironmentVariables(candidate);
                if (File.Exists(expanded)) return expanded;
            }
        }

        // PATH lookup, using the full path so the shell never resolves a folder.
        var path = Environment.GetEnvironmentVariable("PATH") ?? "";
        foreach (var folder in path.Split(';', StringSplitOptions.RemoveEmptyEntries))
        {
            try
            {
                var candidate = Path.Combine(folder.Trim(), withExe);
                if (File.Exists(candidate)) return candidate;
            }
            catch { }
        }

        // App name with spaces removed ("vs code" -> "vscode.exe").
        if (name.Contains(' '))
        {
            var compact = name.Replace(" ", "");
            if (!compact.Equals(name, StringComparison.OrdinalIgnoreCase))
            {
                var hit = FindExecutable(compact);
                if (hit is not null) return hit;
            }
        }

        return null;
    }

    private static bool TryActivate(string processName, out string title)
    {
        title = "";
        try
        {
            foreach (var process in Process.GetProcessesByName(processName))
            {
                try
                {
                    if (process.Id == Environment.ProcessId) continue;
                    var handle = process.MainWindowHandle;
                    if (handle == IntPtr.Zero)
                    {
                        process.Refresh();
                        handle = process.MainWindowHandle;
                    }
                    if (handle == IntPtr.Zero) continue;

                    title = string.IsNullOrWhiteSpace(process.MainWindowTitle)
                        ? processName
                        : process.MainWindowTitle;
                    Native.ShowWindow(handle, Native.SW_RESTORE);
                    Native.SetForegroundWindow(handle);
                    return true;
                }
                finally
                {
                    process.Dispose();
                }
            }
        }
        catch { }
        return false;
    }

    private static string[] AppSuggestions(string name)
    {
        var first = (name ?? "").Split(' ', StringSplitOptions.RemoveEmptyEntries).FirstOrDefault() ?? "";
        if (first.Length < 2) return Array.Empty<string>();
        return InstalledApps()
            .Where(a => a.Name.Contains(first, StringComparison.OrdinalIgnoreCase))
            .Take(6)
            .Select(a => a.Name)
            .ToArray();
    }

    public sealed record LaunchTarget(string Path, string Label, string? ProcessName, bool IsProtocol);

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
