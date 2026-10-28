using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace Friday.Security;

/// <summary>
/// Keeps file tools inside the directories the user authorised, and refuses
/// obvious credential stores and system folders even if someone adds a broad
/// root by mistake.
/// </summary>
public sealed class PathGuard
{
    private readonly List<string> _roots;
    private static readonly string[] AlwaysDeniedNames =
    {
        "id_rsa", "id_ed25519", "credentials", "credentials.json", ".env", ".netrc",
        "ntuser.dat", "sam", "system", "security", "login data", "cookies",
    };

    private static readonly string[] DeniedSegments =
    {
        @"\.ssh\", @"\.aws\", @"\.azure\", @"\.gnupg\", @"\appdata\roaming\microsoft\credentials",
        @"\appdata\local\microsoft\credentials", @"\windows\system32\config",
    };

    public PathGuard(IEnumerable<string> roots)
    {
        _roots = roots
            .Where(r => !string.IsNullOrWhiteSpace(r))
            .Select(Normalise)
            .Where(Directory.Exists)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    public IReadOnlyList<string> Roots => _roots;

    private static string Normalise(string path)
    {
        try { return Path.GetFullPath(path).TrimEnd('\\'); }
        catch { return path; }
    }

    public bool IsInside(string fullPath)
    {
        foreach (var root in _roots)
        {
            if (fullPath.Equals(root, StringComparison.OrdinalIgnoreCase)) return true;
            if (fullPath.StartsWith(root + "\\", StringComparison.OrdinalIgnoreCase)) return true;
        }
        return false;
    }

    /// <summary>Resolves a path and returns either a safe absolute path or a refusal.</summary>
    public bool TryResolve(string requested, out string fullPath, out string error, bool mustExist = false)
    {
        fullPath = "";
        error = "";
        if (string.IsNullOrWhiteSpace(requested))
        {
            error = "No path was given.";
            return false;
        }

        try
        {
            var expanded = Environment.ExpandEnvironmentVariables(requested.Trim().Trim('"'));
            if (expanded == "~") expanded = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            if (expanded.StartsWith("~/") || expanded.StartsWith("~\\"))
            {
                expanded = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), expanded[2..]);
            }

            // Bare names like "notes.txt" are relative to the first authorised root
            // (Desktop by default) so the assistant does not need absolute paths.
            if (!Path.IsPathRooted(expanded))
            {
                var baseRoot = _roots.FirstOrDefault() ??
                               Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
                expanded = Path.Combine(baseRoot, expanded);
            }

            var full = Path.GetFullPath(expanded);
            if (!IsInside(full))
            {
                error = $"{full} is outside the folders Friday is allowed to use. " +
                        "Add it under Settings > Access.";
                return false;
            }

            var lower = full.ToLowerInvariant();
            if (DeniedSegments.Any(seg => lower.Contains(seg)))
            {
                error = "That path holds credentials or system secrets; Friday refuses it.";
                return false;
            }

            var fileName = Path.GetFileName(lower);
            if (AlwaysDeniedNames.Contains(fileName))
            {
                error = "That file looks like a credential store; Friday refuses to read it.";
                return false;
            }

            if (mustExist && !File.Exists(full) && !Directory.Exists(full))
            {
                error = $"Nothing exists at {full}.";
                return false;
            }

            fullPath = full;
            return true;
        }
        catch (Exception ex)
        {
            error = "That path is not valid: " + ex.Message;
            return false;
        }
    }

    public string DescribeRoots() =>
        _roots.Count == 0 ? "(no folders authorised)" : string.Join(Environment.NewLine, _roots.Select(r => "  " + r));
}
