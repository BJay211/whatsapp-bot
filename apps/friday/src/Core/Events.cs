using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Friday.Core;

public sealed record FridayEvent(
    long Seq,
    string Type,
    string TaskId,
    string Status,
    string Summary,
    DateTimeOffset At);

/// <summary>
/// A small in-memory event ring the phone can poll without holding a request
/// open. Long tasks therefore never block an HTTP connection.
/// </summary>
public sealed class EventLog
{
    private readonly object _gate = new();
    private readonly LinkedList<FridayEvent> _items = new();
    private long _seq;

    public event Action<FridayEvent>? Published;

    public FridayEvent Publish(string type, string taskId, string status, string summary)
    {
        var item = new FridayEvent(0, type, taskId, status, summary, DateTimeOffset.UtcNow);
        lock (_gate)
        {
            item = item with { Seq = ++_seq };
            _items.AddLast(item);
            while (_items.Count > 300) _items.RemoveFirst();
        }
        Published?.Invoke(item);
        return item;
    }

    public IReadOnlyList<FridayEvent> Since(long seq)
    {
        lock (_gate)
        {
            return _items.Where(e => e.Seq > seq).Take(100).ToList();
        }
    }

    public long Latest
    {
        get { lock (_gate) return _seq; }
    }
}

public sealed record ConversationEntry(string Role, string Text, DateTimeOffset At);

/// <summary>Conversation shown in the app and (optionally) persisted.</summary>
public sealed class ConversationLog
{
    private readonly object _gate = new();
    private readonly List<ConversationEntry> _items = new();

    public event Action<ConversationEntry>? Added;

    public IReadOnlyList<ConversationEntry> Recent(int count)
    {
        lock (_gate) return _items.TakeLast(count).ToList();
    }

    public void Add(string role, string text)
    {
        text = (text ?? "").Trim();
        if (text.Length == 0) return;
        var entry = new ConversationEntry(role, text, DateTimeOffset.UtcNow);
        lock (_gate)
        {
            _items.Add(entry);
            while (_items.Count > 300) _items.RemoveAt(0);
        }
        Added?.Invoke(entry);
    }

    public void Persist(string role, string text)
    {
        try
        {
            var line = JsonSerializer.Serialize(new
            {
                at = DateTimeOffset.UtcNow,
                role,
                text = text.Length > 4000 ? text[..4000] : text,
            });
            File.AppendAllText(AppPaths.ConversationFile, line + Environment.NewLine, new UTF8Encoding(false));
        }
        catch (Exception ex)
        {
            FLog.Warn("conversation", "Could not persist a turn: " + ex.Message);
        }
    }

    /// <summary>
    /// Rebuilds the in-memory transcript from yesterday's turns so a restart
    /// does not look like the conversation never happened. Read-only: nothing is
    /// re-persisted, and nothing is sent to the AI provider.
    /// </summary>
    public void LoadRecent(int count)
    {
        try
        {
            if (!File.Exists(AppPaths.ConversationFile)) return;
            var lines = File.ReadLines(AppPaths.ConversationFile).TakeLast(count * 2).ToList();
            var loaded = new List<ConversationEntry>();
            foreach (var line in lines)
            {
                if (string.IsNullOrWhiteSpace(line)) continue;
                try
                {
                    using var document = JsonDocument.Parse(line);
                    var root = document.RootElement;
                    var role = root.TryGetProperty("role", out var r) ? r.GetString() ?? "" : "";
                    var text = root.TryGetProperty("text", out var t) ? t.GetString() ?? "" : "";
                    var at = root.TryGetProperty("at", out var a) && a.TryGetDateTimeOffset(out var parsed)
                        ? parsed
                        : DateTimeOffset.UtcNow;
                    if (role.Length > 0 && text.Length > 0)
                        loaded.Add(new ConversationEntry(role, text, at));
                }
                catch
                {
                    // A half-written line from a previous crash is skipped.
                }
            }

            lock (_gate)
            {
                _items.InsertRange(0, loaded.TakeLast(count));
                while (_items.Count > 300) _items.RemoveAt(0);
            }
        }
        catch (Exception ex)
        {
            FLog.Warn("conversation", "Could not load the transcript: " + ex.Message);
        }
    }
}
