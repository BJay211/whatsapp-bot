using System;
using System.IO;

namespace Friday.Core;

/// <summary>
/// Every writable file Friday owns lives under one folder, so uninstalling or
/// auditing is a single directory. Nothing is written into the repository and
/// no secret is ever stored next to the executable.
/// </summary>
public static class AppPaths
{
    public static string Home { get; private set; } = string.Empty;
    public static string ConfigFile => Path.Combine(Home, "config.json");
    public static string MemoryFile => Path.Combine(Home, "memory.json");
    public static string TasksFile => Path.Combine(Home, "tasks.json");
    public static string DevicesFile => Path.Combine(Home, "devices.json");
    public static string AuditFile => Path.Combine(Home, "audit.jsonl");
    public static string ConversationFile => Path.Combine(Home, "conversation.jsonl");
    public static string ServerPfx => Path.Combine(Home, "server.pfx");
    public static string ServerSecret => Path.Combine(Home, "server.secret");
    public static string ScreenshotsDir => Path.Combine(Home, "screenshots");
    public static string LogsDir => Path.Combine(Home, "logs");
    public static string RuntimeLog => Path.Combine(LogsDir, "friday.log");

    public static void Ensure()
    {
        var overridden = Environment.GetEnvironmentVariable("FRIDAY_HOME");
        Home = string.IsNullOrWhiteSpace(overridden)
            ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Friday")
            : Path.GetFullPath(overridden);

        Directory.CreateDirectory(Home);
        Directory.CreateDirectory(ScreenshotsDir);
        Directory.CreateDirectory(LogsDir);
    }

    /// <summary>Folders Friday is allowed to touch unless the user adds more.</summary>
    public static string[] DefaultFileRoots()
    {
        var roots = new List<string>();
        void Add(Environment.SpecialFolder folder)
        {
            var path = Environment.GetFolderPath(folder);
            if (!string.IsNullOrWhiteSpace(path) && Directory.Exists(path)) roots.Add(path);
        }

        Add(Environment.SpecialFolder.DesktopDirectory);
        Add(Environment.SpecialFolder.MyDocuments);
        // The profile folder is authorised as well, because that is where people
        // keep notes and small projects. It is broad, so PathGuard refuses the
        // credential stores inside it (.ssh, .aws, .azure, .gnupg, Windows
        // Credentials and known credential file names) regardless of this list.
        // Narrow it under Settings > Access if that is too much.
        Add(Environment.SpecialFolder.UserProfile);
        var downloads = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
        if (Directory.Exists(downloads)) roots.Add(downloads);

        var cwd = Environment.CurrentDirectory;
        if (Directory.Exists(cwd)) roots.Add(cwd);

        return roots.Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
    }
}
