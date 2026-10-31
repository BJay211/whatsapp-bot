using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace Friday.Tools;

/// <summary>
/// A small, bounded index of the files and folders Friday is allowed to open.
/// It is warmed in the background at startup so "open dave.txt" is answered from
/// memory instead of walking the whole profile every time. The index is a cache,
/// never a source of truth: every hit is checked against the path guard before
/// anything is launched, and a miss falls back to a short, time-boxed walk of the
/// usual folders. It deliberately skips caches and source-control internals.
/// </summary>
public sealed record QuickEntry(string FullPath, string Name, bool IsDirectory)
{
    public int Depth => FullPath.Count(c => c == '\\');
}

public static class QuickIndex
{
    private const int MaxEntries = 50_000;
    private const int MaxDepth = 7;

    private static readonly object Gate = new();
    private static readonly HashSet<string> SkipNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "$recycle.bin", "system volume information", "appdata", "node_modules", ".git",
        ".vs", ".idea", "bin", "obj", "packages", ".gradle", ".nuget", "target",
        "build", "dist", "out", "venv", ".venv", "__pycache__", ".next", ".cache",
        ".npm", ".cargo", ".rustup", "windows", "program files", "program files (x86)",
        "programdata", "$windows.~bt", "$windows.~ws", "recovery", "perflogs",
        ".android", ".gradle", "debug", "release", "x64", "x86", "arm64",
    };

    private static QuickEntry[] _entries = Array.Empty<QuickEntry>();
    private static DateTime _built = DateTime.MinValue;
    private static Task? _warm;
    private static string _signature = "";

    public static bool Ready => _entries.Length > 0 && DateTime.UtcNow - _built < TimeSpan.FromMinutes(15);
    public static int Count => _entries.Length;

    /// <summary>Starts (or refreshes) the background index for these roots.</summary>
    public static void Warm(IEnumerable<string> roots)
    {
        var list = CleanRoots(roots);
        var signature = string.Join("|", list);
        lock (Gate)
        {
            if (_warm is { IsCompleted: false }) return;
            if (signature == _signature && DateTime.UtcNow - _built < TimeSpan.FromMinutes(10)) return;
            _signature = signature;
            _warm = Task.Run(() => Build(list));
        }
    }

    public static void Invalidate()
    {
        lock (Gate)
        {
            _entries = Array.Empty<QuickEntry>();
            _built = DateTime.MinValue;
            _signature = "";
        }
    }

    private static List<string> CleanRoots(IEnumerable<string> roots)
    {
        var clean = roots
            .Where(r => !string.IsNullOrWhiteSpace(r))
            .Select(r =>
            {
                try { return Path.GetFullPath(r).TrimEnd('\\'); }
                catch { return ""; }
            })
            .Where(Directory.Exists)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .OrderBy(r => r.Length)
            .ToList();

        // The profile folder usually also lists Desktop/Documents/Downloads. Keep
        // only the outermost root so those files are indexed once, not three times.
        var outer = new List<string>();
        foreach (var root in clean)
        {
            if (outer.Any(o => root.Equals(o, StringComparison.OrdinalIgnoreCase) ||
                               root.StartsWith(o + "\\", StringComparison.OrdinalIgnoreCase)))
                continue;
            outer.Add(root);
        }
        return outer;
    }

    private static void Build(List<string> roots)
    {
        var results = new List<QuickEntry>(4096);
        try
        {
            foreach (var root in roots)
            {
                if (results.Count >= MaxEntries) break;
                Walk(root, results);
            }
        }
        catch
        {
            // A cache build must never take the app down.
        }

        lock (Gate)
        {
            _entries = results.ToArray();
            _built = DateTime.UtcNow;
        }
    }

    private static void Walk(string root, List<QuickEntry> results)
    {
        var queue = new Queue<(string Path, int Depth)>();
        queue.Enqueue((root, 0));

        while (queue.Count > 0)
        {
            if (results.Count >= MaxEntries) return;
            var (dir, depth) = queue.Dequeue();

            IEnumerable<string> dirs;
            IEnumerable<string> files;
            try
            {
                dirs = Directory.EnumerateDirectories(dir);
                files = Directory.EnumerateFiles(dir);
            }
            catch
            {
                continue;
            }

            foreach (var file in files)
            {
                if (results.Count >= MaxEntries) return;
                try
                {
                    var name = Path.GetFileName(file);
                    if (name.Length > 0 && !name.StartsWith('.'))
                        results.Add(new QuickEntry(file, name, false));
                }
                catch { }
            }

            if (depth >= MaxDepth) continue;
            foreach (var child in dirs)
            {
                if (results.Count >= MaxEntries) return;
                try
                {
                    var info = new DirectoryInfo(child);
                    if ((info.Attributes & FileAttributes.ReparsePoint) != 0) continue;
                    if (SkipNames.Contains(info.Name)) continue;
                    results.Add(new QuickEntry(child, info.Name, true));
                    queue.Enqueue((child, depth + 1));
                }
                catch { }
            }
        }
    }

    /// <summary>
    /// Ranked matches for a spoken name. Exact names win, then prefix matches,
    /// then "all the words appear somewhere" (so "dave txt" finds dave.txt).
    /// </summary>
    public static IReadOnlyList<QuickEntry> Search(string query, bool directories, int max = 20)
    {
        var q = (query ?? "").Trim();
        if (q.Length == 0) return Array.Empty<QuickEntry>();

        var entries = _entries;
        if (entries.Length == 0) return Array.Empty<QuickEntry>();

        var lowered = q.ToLowerInvariant();
        var withoutExt = Path.GetFileNameWithoutExtension(lowered);
        var tokens = lowered.Split(new[] { ' ', '_', '-', '.' }, StringSplitOptions.RemoveEmptyEntries);
        var scored = new List<(QuickEntry Entry, int Score)>();

        foreach (var entry in entries)
        {
            if (entry.IsDirectory != directories) continue;
            var name = entry.Name.ToLowerInvariant();
            var stem = Path.GetFileNameWithoutExtension(name);

            int score;
            if (name == lowered) score = 1200;
            else if (stem == withoutExt && withoutExt.Length > 0) score = 1100;
            else if (name.StartsWith(lowered, StringComparison.OrdinalIgnoreCase)) score = 900 - Math.Min(200, name.Length);
            else if (stem.StartsWith(withoutExt, StringComparison.OrdinalIgnoreCase) && withoutExt.Length >= 2)
                score = 850 - Math.Min(200, stem.Length);
            else if (name.Contains(lowered, StringComparison.OrdinalIgnoreCase)) score = 700 - Math.Min(200, name.Length);
            else if (tokens.Length > 1 && tokens.All(t => name.Contains(t, StringComparison.OrdinalIgnoreCase)))
                score = 620 - Math.Min(180, name.Length);
            else continue;

            // Shallow paths are almost always the one the user means.
            score -= Math.Min(60, entry.Depth * 4);
            scored.Add((entry, score));
        }

        return scored
            .OrderByDescending(s => s.Score)
            .ThenBy(s => s.Entry.FullPath.Length)
            .Take(Math.Clamp(max, 1, 100))
            .Select(s => s.Entry)
            .ToArray();
    }

    public static string[] Suggestions(string query, int max = 6) =>
        Search(query, false, max).Select(e => e.Name)
            .Concat(Search(query, true, max).Select(e => e.Name + "\\"))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Take(max)
            .ToArray();

    // ------------------------------------------------------------ fast lookups

    private static readonly string[] CommonFolders =
    {
        "Desktop", "Documents", "Downloads", "Pictures", "Videos", "Music",
    };

    private static IEnumerable<string> ProbeFolders(IEnumerable<string> roots)
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var root in roots)
        {
            if (seen.Add(root)) yield return root;
        }
        foreach (var name in CommonFolders)
        {
            var folder = Path.Combine(home, name);
            if (seen.Add(folder)) yield return folder;
        }
        if (seen.Add(home)) yield return home;
    }

    /// <summary>
    /// Finds a file by name. Direct probes of the usual folders are instant; the
    /// index is next; a short bounded walk is the last resort for a cold start.
    /// </summary>
    public static string? FindFile(IEnumerable<string> roots, string query)
    {
        var name = (query ?? "").Trim().Trim('"');
        if (name.Length == 0) return null;

        var direct = DirectHit(roots, name, directories: false);
        if (direct is not null) return direct;

        var indexed = Search(name, directories: false, max: 1).FirstOrDefault();
        if (indexed is not null) return indexed.FullPath;

        return QuickWalk(roots, name, directories: false);
    }

    public static string? FindFolder(IEnumerable<string> roots, string query)
    {
        var name = (query ?? "").Trim().Trim('"');
        if (name.Length == 0) return null;

        var direct = DirectHit(roots, name, directories: true);
        if (direct is not null) return direct;

        var indexed = Search(name, directories: true, max: 1).FirstOrDefault();
        if (indexed is not null) return indexed.FullPath;

        return QuickWalk(roots, name, directories: true);
    }

    private static string? DirectHit(IEnumerable<string> roots, string name, bool directories)
    {
        // An absolute path or a path with a folder separator is not a name.
        if (Path.IsPathRooted(name) || name.Contains('\\') || name.Contains('/'))
        {
            return directories ? (Directory.Exists(name) ? name : null)
                               : (File.Exists(name) ? name : null);
        }

        foreach (var folder in ProbeFolders(roots))
        {
            try
            {
                var candidate = Path.Combine(folder, name);
                if (directories ? Directory.Exists(candidate) : File.Exists(candidate))
                    return candidate;
            }
            catch { }
        }
        return null;
    }

    /// <summary>A short, time-boxed walk used only when the index is still cold.</summary>
    private static string? QuickWalk(IEnumerable<string> roots, string name, bool directories)
    {
        var sw = Stopwatch.StartNew();
        var budget = TimeSpan.FromMilliseconds(650);
        var lowered = name.ToLowerInvariant();
        var stems = new[] { lowered, Path.GetFileNameWithoutExtension(lowered) };
        var rootsList = roots.ToList();

        foreach (var start in ProbeFolders(rootsList))
        {
            if (!Directory.Exists(start)) continue;
            var queue = new Queue<(string Path, int Depth)>();
            queue.Enqueue((start, 0));
            var visited = 0;

            while (queue.Count > 0 && sw.Elapsed < budget && visited < 5000)
            {
                var (dir, depth) = queue.Dequeue();
                visited++;

                try
                {
                    var candidates = directories ? Directory.EnumerateDirectories(dir) : Directory.EnumerateFiles(dir);
                    foreach (var item in candidates)
                    {
                        var itemName = Path.GetFileName(item).ToLowerInvariant();
                        if (stems.Any(s => s.Length > 0 && itemName == s)) return item;
                        if (stems.Any(s => s.Length > 1 && itemName.StartsWith(s, StringComparison.OrdinalIgnoreCase)))
                            return item;
                    }
                }
                catch { }

                if (depth >= 3) continue;
                try
                {
                    foreach (var child in Directory.EnumerateDirectories(dir))
                    {
                        var childName = Path.GetFileName(child);
                        if (SkipNames.Contains(childName)) continue;
                        var info = new DirectoryInfo(child);
                        if ((info.Attributes & FileAttributes.ReparsePoint) != 0) continue;
                        queue.Enqueue((child, depth + 1));
                    }
                }
                catch { }
            }

            if (sw.Elapsed >= budget) break;
        }
        return null;
    }
}
