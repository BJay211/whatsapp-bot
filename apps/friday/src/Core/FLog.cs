using System;
using System.Collections.Concurrent;
using System.IO;
using System.Text;
using System.Text.Json;

namespace Friday.Core;

/// <summary>
/// Small, thread-safe logger. Two streams are kept:
///   friday.log   - runtime diagnostics
///   audit.jsonl  - one JSON object per security-relevant decision or action
/// The audit file is append-only and is what the dashboard tab reads.
/// </summary>
public static class FLog
{
    private static readonly object Gate = new();
    private static readonly ConcurrentQueue<string> Recent = new();
    private const int RecentLimit = 400;

    public static event Action<string>? LineWritten;

    public static void Info(string area, string message) => Write("INFO", area, message);
    public static void Warn(string area, string message) => Write("WARN", area, message);
    public static void Error(string area, string message) => Write("ERROR", area, message);

    private static void Write(string level, string area, string message)
    {
        var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}  {level,-5}  {area,-9}  {message}";
        lock (Gate)
        {
            Recent.Enqueue(line);
            while (Recent.Count > RecentLimit) Recent.TryDequeue(out _);
            try
            {
                AppPaths.Ensure();
                File.AppendAllText(AppPaths.RuntimeLog, line + Environment.NewLine, new UTF8Encoding(false));
            }
            catch
            {
                // Logging must never take the assistant down.
            }
        }

        try { LineWritten?.Invoke(line); } catch { }
    }

    public static string[] RecentLines() => Recent.ToArray();

    public static void Audit(
        string action,
        string actor,
        string decision,
        string detail,
        bool ok,
        string? deviceId = null,
        string? requestId = null)
    {
        try
        {
            AppPaths.Ensure();
            var entry = new
            {
                at = DateTimeOffset.UtcNow,
                action,
                actor,
                device_id = deviceId,
                request_id = requestId,
                decision,
                ok,
                detail = Trim(detail, 2000),
            };
            var json = JsonSerializer.Serialize(entry);
            lock (Gate)
            {
                File.AppendAllText(AppPaths.AuditFile, json + Environment.NewLine, new UTF8Encoding(false));
            }
        }
        catch (Exception ex)
        {
            Write("WARN", "audit", "Could not write audit entry: " + ex.Message);
        }
    }

    public static string[] ReadAuditTail(int count)
    {
        try
        {
            if (!File.Exists(AppPaths.AuditFile)) return Array.Empty<string>();
            var lines = File.ReadAllLines(AppPaths.AuditFile);
            var take = Math.Min(count, lines.Length);
            var result = new string[take];
            Array.Copy(lines, lines.Length - take, result, 0, take);
            Array.Reverse(result);
            return result;
        }
        catch
        {
            return Array.Empty<string>();
        }
    }

    /// <summary>
    /// Enforces the "keep history for N days" setting for the two files that hold
    /// personal content: the audit trail and the conversation transcript. Lines
    /// without a readable timestamp are kept rather than guessed at.
    /// </summary>
    public static void PruneOlderThan(int days)
    {
        if (days <= 0) return;
        var cutoff = DateTimeOffset.UtcNow.AddDays(-days);
        foreach (var file in new[] { AppPaths.AuditFile, AppPaths.ConversationFile })
        {
            try
            {
                if (!File.Exists(file)) continue;

                var kept = new System.Collections.Generic.List<string>();
                var removed = 0;
                foreach (var line in File.ReadAllLines(file))
                {
                    if (string.IsNullOrWhiteSpace(line)) continue;
                    if (TryTimestamp(line, out var at) && at < cutoff)
                    {
                        removed++;
                        continue;
                    }
                    kept.Add(line);
                }

                if (removed == 0) continue;
                var temp = file + ".tmp";
                File.WriteAllLines(temp, kept, new UTF8Encoding(false));
                File.Move(temp, file, overwrite: true);
                Write("INFO", "privacy",
                    $"Removed {removed} line(s) older than {days} days from {Path.GetFileName(file)}.");
            }
            catch (Exception ex)
            {
                Write("WARN", "privacy", "Could not prune " + Path.GetFileName(file) + ": " + ex.Message);
            }
        }
    }

    private static bool TryTimestamp(string jsonLine, out DateTimeOffset at)
    {
        at = default;
        try
        {
            using var document = JsonDocument.Parse(jsonLine);
            return document.RootElement.TryGetProperty("at", out var value) &&
                   value.TryGetDateTimeOffset(out at);
        }
        catch
        {
            return false;
        }
    }

    private static string Trim(string value, int max) =>
        string.IsNullOrEmpty(value) ? "" : (value.Length <= max ? value : value[..max] + "…");
}
