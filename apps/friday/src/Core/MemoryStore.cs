using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;

namespace Friday.Core;

public sealed class MemoryEntry
{
    public string Id { get; set; } = Guid.NewGuid().ToString("n")[..10];
    public string Text { get; set; } = "";
    public List<string> Tags { get; set; } = new();
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
    /// <summary>Only user-approved memories are sent to the model.</summary>
    public bool Approved { get; set; }
    public string Source { get; set; } = "user";
}

/// <summary>
/// Persistent, explicitly approved memory. Friday may propose a memory through a
/// tool call, but nothing reaches the model's system prompt until the user has
/// approved it (locally or from the paired phone).
/// </summary>
public sealed class MemoryStore
{
    private readonly object _gate = new();
    private readonly List<MemoryEntry> _entries = new();

    public event Action? Changed;

    public MemoryStore()
    {
        Load();
    }

    public IReadOnlyList<MemoryEntry> All
    {
        get { lock (_gate) return _entries.ToList(); }
    }

    public MemoryEntry? Add(string text, IEnumerable<string>? tags = null, bool approved = false, string source = "user")
    {
        text = (text ?? "").Trim();
        if (text.Length == 0) return null;
        if (text.Length > 600) text = text[..600];

        var entry = new MemoryEntry
        {
            Text = text,
            Tags = (tags ?? Array.Empty<string>()).Where(t => !string.IsNullOrWhiteSpace(t))
                .Select(t => t.Trim()).Distinct(StringComparer.OrdinalIgnoreCase).Take(8).ToList(),
            Approved = approved,
            Source = source,
        };

        lock (_gate)
        {
            // Do not duplicate an identical memory.
            if (_entries.Any(e => string.Equals(e.Text, text, StringComparison.OrdinalIgnoreCase)))
                return _entries.First(e => string.Equals(e.Text, text, StringComparison.OrdinalIgnoreCase));
            _entries.Add(entry);
            if (_entries.Count > 500) _entries.RemoveRange(0, _entries.Count - 500);
        }

        Save();
        Changed?.Invoke();
        return entry;
    }

    public bool Approve(string id)
    {
        lock (_gate)
        {
            var entry = _entries.FirstOrDefault(e => e.Id == id);
            if (entry is null) return false;
            entry.Approved = true;
        }
        Save();
        Changed?.Invoke();
        return true;
    }

    public bool Remove(string id)
    {
        bool removed;
        lock (_gate) removed = _entries.RemoveAll(e => e.Id == id) > 0;
        if (removed)
        {
            Save();
            Changed?.Invoke();
        }
        return removed;
    }

    public string[] PromptLines(int max = 40)
    {
        lock (_gate)
        {
            return _entries.Where(e => e.Approved)
                .OrderByDescending(e => e.CreatedAt)
                .Take(max)
                .Select(e => "- " + e.Text)
                .ToArray();
        }
    }

    public string[] Search(string query)
    {
        query = (query ?? "").Trim();
        lock (_gate)
        {
            return _entries
                .Where(e => e.Approved && (query.Length == 0 ||
                            e.Text.Contains(query, StringComparison.OrdinalIgnoreCase) ||
                            e.Tags.Any(t => t.Contains(query, StringComparison.OrdinalIgnoreCase))))
                .OrderByDescending(e => e.CreatedAt)
                .Take(20)
                .Select(e => e.Text)
                .ToArray();
        }
    }

    private void Load()
    {
        try
        {
            if (!File.Exists(AppPaths.MemoryFile)) return;
            var list = JsonSerializer.Deserialize<List<MemoryEntry>>(File.ReadAllText(AppPaths.MemoryFile));
            if (list is not null)
            {
                lock (_gate) _entries.AddRange(list);
            }
        }
        catch (Exception ex)
        {
            FLog.Warn("memory", "Could not load memory.json: " + ex.Message);
        }
    }

    private void Save()
    {
        try
        {
            string json;
            lock (_gate)
            {
                json = JsonSerializer.Serialize(_entries, new JsonSerializerOptions { WriteIndented = true });
            }
            var temp = AppPaths.MemoryFile + ".tmp";
            File.WriteAllText(temp, json, new UTF8Encoding(false));
            File.Move(temp, AppPaths.MemoryFile, overwrite: true);
        }
        catch (Exception ex)
        {
            FLog.Warn("memory", "Could not save memory.json: " + ex.Message);
        }
    }
}
