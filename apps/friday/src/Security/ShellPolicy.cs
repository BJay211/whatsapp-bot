using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;
using Friday.Core;

namespace Friday.Security;

public enum ShellVerdict
{
    Allowed,
    NeedsApproval,
    Blocked,
}

public sealed record ShellDecision(ShellVerdict Verdict, string Reason);

/// <summary>
/// An explicit command policy. Nothing is executed by string concatenation, no
/// remote shell is exposed, and the dangerous patterns are refused outright
/// rather than being left to a model's judgement.
/// </summary>
public sealed class ShellPolicy
{
    private readonly FridayConfig _config;

    public ShellPolicy(FridayConfig config) => _config = config;

    public ShellDecision Evaluate(string command)
    {
        command = (command ?? "").Trim();
        if (command.Length == 0) return new ShellDecision(ShellVerdict.Blocked, "The command was empty.");
        if (command.Length > 4000) return new ShellDecision(ShellVerdict.Blocked, "That command is too long.");

        var lower = command.ToLowerInvariant();

        // Absolute refusals, whatever the allowlist says.
        foreach (var pattern in _config.Policy.BlockedPatterns)
        {
            if (pattern.Length > 2 && lower.Contains(pattern.ToLowerInvariant()))
                return new ShellDecision(ShellVerdict.Blocked,
                    $"\"{pattern}\" is on Friday's blocked list. It cannot be run from here.");
        }

        if (Regex.IsMatch(lower, @"(invoke-webrequest|iwr|curl|wget).*(\||;).*(iex|invoke-expression)"))
            return new ShellDecision(ShellVerdict.Blocked, "Downloading and executing a script in one step is refused.");

        if (Regex.IsMatch(lower, @"(^|[;&|])\s*(format|diskpart|bcdedit|vssadmin)\b"))
            return new ShellDecision(ShellVerdict.Blocked, "Disk and boot configuration changes are refused.");

        foreach (var allowed in _config.Policy.ShellAllowlist)
        {
            if (WildcardMatch(lower, allowed.ToLowerInvariant()))
            {
                return new ShellDecision(ShellVerdict.Allowed, $"Matches allowlist rule \"{allowed}\".");
            }
        }

        return _config.Policy.AllowFreeformShell
            ? new ShellDecision(ShellVerdict.NeedsApproval,
                "Not on the allowlist. Freeform shell is enabled, so this needs your approval.")
            : new ShellDecision(ShellVerdict.Blocked,
                "That command is not on Friday's allowlist. Add a rule in Settings > Access if you trust it.");
    }

    private static bool WildcardMatch(string text, string pattern)
    {
        if (!pattern.Contains('*')) return text == pattern;
        var regex = "^" + Regex.Escape(pattern).Replace("\\*", ".*") + "$";
        return Regex.IsMatch(text, regex, RegexOptions.Singleline);
    }

    public string DescribeAllowlist() => string.Join(", ", _config.Policy.ShellAllowlist);
}
