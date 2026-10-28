using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Xml.Linq;
using Friday.Core;

namespace Friday.Tools;

public static class FileTools
{
    public static IEnumerable<ToolDescriptor> Describe()
    {
        yield return new ToolDescriptor
        {
            Name = "search_files",
            Description = "Search for files by name inside the authorised folders.",
            Capability = Capability.Files,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"query":{"type":"string"},"root":{"type":"string"},"max":{"type":"integer"}},"required":["query"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(SearchFiles(ctx, Args.Str(args, "query"), Args.Str(args, "root"), Args.Int(args, "max", 40))),
        };
        yield return new ToolDescriptor
        {
            Name = "list_directory",
            Description = "List the contents of a folder inside an authorised root.",
            Capability = Capability.Files,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(ListDirectory(ctx, Args.Str(args, "path"))),
        };
        yield return new ToolDescriptor
        {
            Name = "read_file",
            Description = "Read a text, code, markdown or .docx file and return its contents.",
            Capability = Capability.Files,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"path":{"type":"string"},"max_chars":{"type":"integer"}},"required":["path"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(ReadFile(ctx, Args.Str(args, "path"), Args.Int(args, "max_chars", 12000))),
        };
        yield return new ToolDescriptor
        {
            Name = "write_file",
            Description = "Create or overwrite a text file inside an authorised folder. Overwriting asks for confirmation.",
            Capability = Capability.Files,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"},"overwrite":{"type":"boolean"}},"required":["path","content"],"additionalProperties":false}
            """),
            Handler = WriteFileAsync,
        };
        yield return new ToolDescriptor
        {
            Name = "create_folder",
            Description = "Create a folder inside an authorised root.",
            Capability = Capability.Files,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(CreateFolder(ctx, Args.Str(args, "path"))),
        };
        yield return new ToolDescriptor
        {
            Name = "copy_path",
            Description = "Copy a file or folder to another location inside the authorised folders.",
            Capability = Capability.Files,
            Risk = RiskLevel.Confirm,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"source":{"type":"string"},"destination":{"type":"string"}},"required":["source","destination"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(CopyOrMove(ctx, Args.Str(args, "source"), Args.Str(args, "destination"), move: false)),
        };
        yield return new ToolDescriptor
        {
            Name = "move_path",
            Description = "Move or rename a file or folder inside the authorised folders.",
            Capability = Capability.Files,
            Risk = RiskLevel.Confirm,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"source":{"type":"string"},"destination":{"type":"string"}},"required":["source","destination"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(CopyOrMove(ctx, Args.Str(args, "source"), Args.Str(args, "destination"), move: true)),
        };
        yield return new ToolDescriptor
        {
            Name = "delete_path",
            Description = "Delete a file or folder. Always requires explicit confirmation.",
            Capability = Capability.Files,
            Risk = RiskLevel.Destructive,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(Delete(ctx, Args.Str(args, "path"))),
        };
        yield return new ToolDescriptor
        {
            Name = "zip_path",
            Description = "Compress a file or folder into a .zip archive inside the authorised folders.",
            Capability = Capability.Files,
            Risk = RiskLevel.Confirm,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"path":{"type":"string"},"destination":{"type":"string"}},"required":["path"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(Zip(ctx, Args.Str(args, "path"), Args.Str(args, "destination"))),
        };
        yield return new ToolDescriptor
        {
            Name = "unzip",
            Description = "Extract a .zip archive into a folder inside the authorised roots.",
            Capability = Capability.Files,
            Risk = RiskLevel.Confirm,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"path":{"type":"string"},"destination":{"type":"string"}},"required":["path","destination"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(Unzip(ctx, Args.Str(args, "path"), Args.Str(args, "destination"))),
        };
        yield return new ToolDescriptor
        {
            Name = "find_large_files",
            Description = "Find the largest files inside an authorised folder.",
            Capability = Capability.Files,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"root":{"type":"string"},"count":{"type":"integer"}},"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(FindLarge(ctx, Args.Str(args, "root"), Args.Int(args, "count", 15))),
        };
        yield return new ToolDescriptor
        {
            Name = "find_duplicates",
            Description = "Find duplicate files by size and hash inside an authorised folder (read-only).",
            Capability = Capability.Files,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"root":{"type":"string"}},"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(FindDuplicates(ctx, Args.Str(args, "root"))),
        };
        yield return new ToolDescriptor
        {
            Name = "create_document",
            Description = "Write a document (markdown or plain text) into the authorised folders, creating a sensible file name.",
            Capability = Capability.Files,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"name":{"type":"string"},"content":{"type":"string"}},"required":["name","content"],"additionalProperties":false}
            """),
            Handler = (ctx, args) =>
            {
                var name = Args.Str(args, "name", "friday-note");
                foreach (var invalid in Path.GetInvalidFileNameChars()) name = name.Replace(invalid, '-');
                if (!Path.HasExtension(name)) name += ".md";
                var target = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), name);
                return Task.FromResult(WriteText(ctx, target, Args.Str(args, "content"), overwrite: false));
            },
        };
    }

    private static IEnumerable<string> Walk(string root, int limit = 20000)
    {
        var stack = new Stack<string>();
        stack.Push(root);
        var seen = 0;
        while (stack.Count > 0 && seen < limit)
        {
            var dir = stack.Pop();
            IEnumerable<string> files;
            IEnumerable<string> dirs;
            try
            {
                files = Directory.EnumerateFiles(dir);
                dirs = Directory.EnumerateDirectories(dir);
            }
            catch
            {
                continue;
            }

            foreach (var file in files)
            {
                seen++;
                if (seen > limit) yield break;
                yield return file;
            }

            foreach (var child in dirs)
            {
                var name = Path.GetFileName(child);
                if (name.Equals("node_modules", StringComparison.OrdinalIgnoreCase) ||
                    name.Equals(".git", StringComparison.OrdinalIgnoreCase) ||
                    name.Equals("build", StringComparison.OrdinalIgnoreCase) ||
                    name.Equals("bin", StringComparison.OrdinalIgnoreCase) ||
                    name.Equals("obj", StringComparison.OrdinalIgnoreCase)) continue;
                stack.Push(child);
            }
        }
    }

    private static ToolResult SearchFiles(ToolContext ctx, string query, string root, int max)
    {
        query = (query ?? "").Trim();
        if (query.Length == 0) return ToolResult.Fail("What should I search for?");

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

        var hits = new List<string>();
        foreach (var start in roots)
        {
            foreach (var file in Walk(start))
            {
                if (Path.GetFileName(file).Contains(query, StringComparison.OrdinalIgnoreCase))
                {
                    hits.Add(file);
                    if (hits.Count >= Math.Clamp(max, 1, 200)) break;
                }
            }
            if (hits.Count >= max) break;
        }

        if (hits.Count == 0) return ToolResult.Good($"No files matching \"{query}\".");
        return ToolResult.Good(string.Join(Environment.NewLine, hits),
            new JsonArray(hits.Select(h => (JsonNode)h!).ToArray()).ToJsonString());
    }

    private static ToolResult ListDirectory(ToolContext ctx, string path)
    {
        if (!ctx.Runtime.Paths.TryResolve(path, out var full, out var error, mustExist: true))
            return ToolResult.Refused(error);
        if (!Directory.Exists(full)) return ToolResult.Fail($"{full} is a file, not a folder.");

        var dirs = Directory.EnumerateDirectories(full)
            .Select(d => new DirectoryInfo(d))
            .OrderBy(d => d.Name).Take(60)
            .Select(d => $"[dir]  {d.Name}");
        var files = Directory.EnumerateFiles(full)
            .Select(f => new FileInfo(f))
            .OrderByDescending(f => f.LastWriteTime).Take(120)
            .Select(f => $"{f.Length / 1024,7} KB  {f.Name}  ({f.LastWriteTime:yyyy-MM-dd})");

        var text = string.Join(Environment.NewLine, dirs.Concat(files));
        return ToolResult.Good(string.IsNullOrEmpty(text) ? $"{full} is empty." : text);
    }

    private static ToolResult ReadFile(ToolContext ctx, string path, int maxChars)
    {
        if (!ctx.Runtime.Paths.TryResolve(path, out var full, out var error, mustExist: true))
            return ToolResult.Refused(error);
        if (!File.Exists(full)) return ToolResult.Fail($"{full} is a folder, not a file.");

        var info = new FileInfo(full);
        if (info.Length > 8 * 1024 * 1024)
            return ToolResult.Fail($"That file is {info.Length / 1024 / 1024} MB. Ask me for a specific part instead.");

        maxChars = Math.Clamp(maxChars, 500, 60000);
        var ext = Path.GetExtension(full).ToLowerInvariant();

        string text;
        if (ext == ".docx")
        {
            text = ReadDocx(full);
        }
        else if (ext is ".pdf")
        {
            return ToolResult.Fail(
                "PDF text extraction is not built in, so I will not guess at its contents. " +
                "Save it as .docx or .txt and I can read it.");
        }
        else if (ext is ".png" or ".jpg" or ".jpeg" or ".gif" or ".bmp" or ".mp4" or ".mov" or ".mp3" or ".wav" or ".zip" or ".exe" or ".dll")
        {
            return ToolResult.Fail($"I can open {ext} files, but I cannot read their contents as text.");
        }
        else
        {
            var bytes = File.ReadAllBytes(full);
            foreach (var encoding in new[] { Encoding.UTF8, Encoding.Unicode, Encoding.Default })
            {
                try
                {
                    text = encoding.GetString(bytes);
                    if (!text.Contains('\u0000')) break;
                }
                catch
                {
                    text = "";
                }
            }
            text = Encoding.UTF8.GetString(bytes);
        }

        var truncated = text.Length > maxChars;
        if (truncated) text = text[..maxChars] + "\n… (truncated)";
        return ToolResult.Good(text, new JsonObject
        {
            ["path"] = full,
            ["chars"] = text.Length,
            ["truncated"] = truncated,
        }.ToJsonString());
    }

    private static string ReadDocx(string path)
    {
        using var archive = ZipFile.OpenRead(path);
        var entry = archive.GetEntry("word/document.xml");
        if (entry is null) return "(This .docx has no readable document.xml.)";
        using var stream = entry.Open();
        var doc = XDocument.Load(stream);
        XNamespace w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        var paragraphs = doc.Descendants(w + "p")
            .Select(p => string.Concat(p.Descendants(w + "t").Select(t => t.Value)))
            .Where(s => s.Length > 0);
        return string.Join(Environment.NewLine, paragraphs);
    }

    private static async Task<ToolResult> WriteFileAsync(ToolContext ctx, JsonObject args)
    {
        var path = Args.Str(args, "path");
        var content = Args.Str(args, "content");
        var overwrite = Args.Bool(args, "overwrite");

        if (!ctx.Config.Policy.AllowFileWrites)
            return ToolResult.Refused("File writing is switched off in Settings > Access.");
        if (!ctx.Runtime.Paths.TryResolve(path, out var full, out var error))
            return ToolResult.Refused(error);
        if (Directory.Exists(full)) return ToolResult.Fail($"{full} is a folder.");
        if (File.Exists(full) && !overwrite)
            return ToolResult.Fail($"{full} already exists. Ask me to overwrite it if that is what you want.");

        if (File.Exists(full) && ctx.RequestApproval is not null)
        {
            var decision = await ctx.RequestApproval(new ApprovalRequest(
                Guid.NewGuid().ToString("n")[..10],
                "Overwrite an existing file?",
                $"{full}\n\nNew content is {content.Length} characters.",
                Capability.Files, "write_file", false, ctx.Source)).ConfigureAwait(false);
            if (!decision.Approved) return ToolResult.Refused("I did not overwrite the file.");
        }

        Directory.CreateDirectory(Path.GetDirectoryName(full) ?? ".");
        await File.WriteAllTextAsync(full, content, new UTF8Encoding(false), ctx.Token).ConfigureAwait(false);
        return ToolResult.Good($"Wrote {content.Length} characters to {full}.",
            new JsonObject { ["path"] = full, ["chars"] = content.Length }.ToJsonString());
    }

    private static ToolResult WriteText(ToolContext ctx, string path, string content, bool overwrite)
    {
        if (!ctx.Runtime.Paths.TryResolve(path, out var full, out var error))
            return ToolResult.Refused(error);
        if (File.Exists(full) && !overwrite) return ToolResult.Fail($"{full} already exists.");
        Directory.CreateDirectory(Path.GetDirectoryName(full) ?? ".");
        File.WriteAllText(full, content, new UTF8Encoding(false));
        return ToolResult.Good($"Created {full}.");
    }

    private static ToolResult CreateFolder(ToolContext ctx, string path)
    {
        if (!ctx.Config.Policy.AllowFileWrites) return ToolResult.Refused("File writing is switched off.");
        if (!ctx.Runtime.Paths.TryResolve(path, out var full, out var error)) return ToolResult.Refused(error);
        if (Directory.Exists(full)) return ToolResult.Good($"{full} already exists.");
        Directory.CreateDirectory(full);
        return ToolResult.Good($"Created folder {full}.");
    }

    private static ToolResult CopyOrMove(ToolContext ctx, string source, string destination, bool move)
    {
        if (!ctx.Config.Policy.AllowFileWrites) return ToolResult.Refused("File changes are switched off.");
        if (!ctx.Runtime.Paths.TryResolve(source, out var from, out var error, mustExist: true))
            return ToolResult.Refused(error);
        if (!ctx.Runtime.Paths.TryResolve(destination, out var to, out error))
            return ToolResult.Refused(error);

        // Guard against copying a folder into its own child.
        if (Directory.Exists(from) && to.StartsWith(from + "\\", StringComparison.OrdinalIgnoreCase))
            return ToolResult.Refused("That would copy a folder inside itself.");

        if (File.Exists(from))
        {
            Directory.CreateDirectory(Path.GetDirectoryName(to) ?? ".");
            if (move) File.Move(from, to, overwrite: false);
            else File.Copy(from, to, overwrite: false);
        }
        else if (Directory.Exists(from))
        {
            if (Directory.Exists(to)) return ToolResult.Fail($"{to} already exists.");
            CopyDirectory(from, to);
            if (move) Directory.Delete(from, recursive: true);
        }
        else
        {
            return ToolResult.Fail($"Nothing exists at {from}.");
        }

        return ToolResult.Good($"{(move ? "Moved" : "Copied")} {Path.GetFileName(from)} to {to}.");
    }

    private static void CopyDirectory(string from, string to)
    {
        Directory.CreateDirectory(to);
        foreach (var file in Directory.EnumerateFiles(from))
            File.Copy(file, Path.Combine(to, Path.GetFileName(file)), overwrite: false);
        foreach (var dir in Directory.EnumerateDirectories(from))
            CopyDirectory(dir, Path.Combine(to, Path.GetFileName(dir)));
    }

    private static ToolResult Delete(ToolContext ctx, string path)
    {
        if (!ctx.Config.Policy.AllowDelete)
            return ToolResult.Refused("Deleting is switched off in Settings > Access.");
        if (!ctx.Runtime.Paths.TryResolve(path, out var full, out var error, mustExist: true))
            return ToolResult.Refused(error);

        if (Directory.Exists(full))
        {
            var count = Directory.EnumerateFileSystemEntries(full, "*", SearchOption.AllDirectories).Count();
            if (count > 500)
                return ToolResult.Refused($"That folder holds {count} items. Delete it yourself after checking.");
            Directory.Delete(full, recursive: true);
        }
        else
        {
            File.Delete(full);
        }
        return ToolResult.Good($"Deleted {full}. This cannot be undone from here.");
    }

    private static ToolResult Zip(ToolContext ctx, string path, string destination)
    {
        if (!ctx.Runtime.Paths.TryResolve(path, out var source, out var error, mustExist: true))
            return ToolResult.Refused(error);
        if (!ctx.Runtime.Paths.TryResolve(destination, out var target, out error))
            return ToolResult.Refused(error);
        if (!target.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)) target += ".zip";
        if (File.Exists(target)) return ToolResult.Fail($"{target} already exists.");

        if (Directory.Exists(source)) ZipFile.CreateFromDirectory(source, target);
        else
        {
            using var archive = ZipFile.Open(target, ZipArchiveMode.Create);
            archive.CreateEntryFromFile(source, Path.GetFileName(source));
        }
        return ToolResult.Good($"Created {target}.");
    }

    private static ToolResult Unzip(ToolContext ctx, string path, string destination)
    {
        if (!ctx.Runtime.Paths.TryResolve(path, out var archivePath, out var error, mustExist: true))
            return ToolResult.Refused(error);
        if (!ctx.Runtime.Paths.TryResolve(destination, out var target, out error))
            return ToolResult.Refused(error);
        Directory.CreateDirectory(target);
        ZipFile.ExtractToDirectory(archivePath, target, overwriteFiles: false);
        return ToolResult.Good($"Extracted {Path.GetFileName(archivePath)} into {target}.");
    }

    private static ToolResult FindLarge(ToolContext ctx, string root, int count)
    {
        string start;
        if (string.IsNullOrWhiteSpace(root))
        {
            start = ctx.Runtime.Paths.Roots.FirstOrDefault() ?? "";
        }
        else if (!ctx.Runtime.Paths.TryResolve(root, out start, out var error, mustExist: true))
        {
            return ToolResult.Refused(error);
        }

        if (string.IsNullOrEmpty(start)) return ToolResult.Fail("No authorised folder to scan.");

        var files = Walk(start, 40000)
            .Select(f => new FileInfo(f))
            .Where(f => f.Exists)
            .OrderByDescending(f => f.Length)
            .Take(Math.Clamp(count, 1, 50))
            .Select(f => $"{f.Length / 1024 / 1024,6} MB  {f.FullName}")
            .ToArray();

        return ToolResult.Good(string.Join(Environment.NewLine, files));
    }

    private static ToolResult FindDuplicates(ToolContext ctx, string root)
    {
        string start;
        if (string.IsNullOrWhiteSpace(root))
            start = ctx.Runtime.Paths.Roots.FirstOrDefault() ?? "";
        else if (!ctx.Runtime.Paths.TryResolve(root, out start, out var error, mustExist: true))
            return ToolResult.Refused(error);

        if (string.IsNullOrEmpty(start)) return ToolResult.Fail("No authorised folder to scan.");

        var bySize = Walk(start, 30000)
            .Select(f => new FileInfo(f))
            .Where(f => f.Exists && f.Length > 64 * 1024)
            .GroupBy(f => f.Length)
            .Where(g => g.Count() > 1)
            .SelectMany(g => g)
            .Take(3000)
            .ToList();

        var byHash = new Dictionary<string, List<string>>(StringComparer.OrdinalIgnoreCase);
        using var sha = SHA256.Create();
        foreach (var file in bySize)
        {
            try
            {
                using var stream = File.OpenRead(file.FullName);
                var hash = Convert.ToHexString(sha.ComputeHash(stream));
                if (!byHash.TryGetValue(hash, out var list))
                {
                    list = new List<string>();
                    byHash[hash] = list;
                }
                list.Add(file.FullName);
            }
            catch
            {
                // Locked or unreadable files are skipped, not reported as duplicates.
            }
        }

        var groups = byHash.Values.Where(g => g.Count > 1).Take(15).ToArray();
        if (groups.Length == 0) return ToolResult.Good("I found no duplicate files over 64 KB.");

        var text = string.Join(Environment.NewLine + Environment.NewLine,
            groups.Select(g => string.Join(Environment.NewLine, g)));
        return ToolResult.Good(text);
    }
}
