using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;
using Friday.Ai;
using Friday.Tools;

namespace Friday.Core;

public enum TaskStatus
{
    Received,
    Validating,
    AwaitingApproval,
    Running,
    Succeeded,
    Failed,
    Cancelled,
}

public sealed class FridayTask
{
    public string Id { get; set; } = "";
    public string RequestId { get; set; } = "";
    public string Instruction { get; set; } = "";
    public string Source { get; set; } = "ui";
    public string? DeviceId { get; set; }
    public TaskStatus Status { get; set; } = TaskStatus.Received;
    public string Summary { get; set; } = "";
    public string Progress { get; set; } = "";
    public string? Data { get; set; }
    public string Error { get; set; } = "";
    public bool NeedsApproval { get; set; }
    public string? ApprovalId { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;

    [JsonIgnore] public CancellationTokenSource? Cancellation { get; set; }

    public bool IsTerminal => Status is TaskStatus.Succeeded or TaskStatus.Failed or TaskStatus.Cancelled;
}

public sealed record PendingApproval(
    string Id,
    string TaskId,
    string Title,
    string Detail,
    Capability Capability,
    bool Destructive,
    string Source,
    DateTimeOffset CreatedAt);

/// <summary>
/// A single-worker task queue with durable history, real cancellation and a
/// two-party approval gate (desktop UI or paired phone).
/// </summary>
public sealed class TaskQueue
{
    private readonly FridayRuntime _runtime;
    private readonly ConcurrentQueue<FridayTask> _queue = new();
    private readonly SemaphoreSlim _signal = new(0);
    private readonly Dictionary<string, FridayTask> _tasks = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, PendingApproval> _pending = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, TaskCompletionSource<ApprovalDecision>> _waiters = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>
    /// One alarm clock per running task, so /v1/command can answer the instant the
    /// task ends instead of waking up every 120 ms to look. Removed as soon as it
    /// fires.
    /// </summary>
    private readonly ConcurrentDictionary<string, TaskCompletionSource<bool>> _finished = new(StringComparer.OrdinalIgnoreCase);
    private readonly object _gate = new();
    private readonly CancellationTokenSource _stop = new();
    private readonly JsonSerializerOptions _json = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        Converters = { new JsonStringEnumConverter(JsonNamingPolicy.SnakeCaseLower) },
    };

    private Task? _worker;

    public event Action<FridayTask>? Changed;
    public event Action<PendingApproval>? ApprovalRequested;
    public event Action<string>? ApprovalResolved;

    public TaskQueue(FridayRuntime runtime)
    {
        _runtime = runtime;
        Load();
    }

    public void Start()
    {
        _worker ??= Task.Run(WorkerAsync);
        foreach (var task in Snapshot().Where(t => t.Status == TaskStatus.Received))
        {
            _queue.Enqueue(task);
            _signal.Release();
        }
    }

    public async Task StopAsync()
    {
        try { _stop.Cancel(); } catch { }
        CancelAll("Friday is shutting down.");
        try
        {
            if (_worker is not null) await Task.WhenAny(_worker, Task.Delay(2500)).ConfigureAwait(false);
        }
        catch { }
        Save();
    }

    public FridayTask Enqueue(string instruction, string source, string? deviceId, string requestId)
    {
        var task = new FridayTask
        {
            Id = NewId(),
            RequestId = requestId,
            Instruction = instruction.Trim(),
            Source = source,
            DeviceId = deviceId,
            Status = TaskStatus.Received,
            Summary = "Queued.",
        };

        lock (_gate)
        {
            _tasks[task.Id] = task;
            TrimHistory();
        }

        _queue.Enqueue(task);
        _signal.Release();
        Update(task);
        _runtime.Events.Publish("task", task.Id, "received", "Queued: " + Clip(instruction, 90));
        Save();
        return task;
    }

    public FridayTask? Get(string id)
    {
        lock (_gate) return _tasks.TryGetValue(id ?? "", out var task) ? task : null;
    }

    /// <summary>
    /// Waits for a task to finish, for the short-hold HTTP fast path. The wait is
    /// event-driven: it wakes the moment the task reaches a terminal state, so a
    /// command that finishes in 8 ms is answered in 8 ms, not at the next poll.
    /// </summary>
    public async Task<FridayTask?> WaitForAsync(string id, TimeSpan timeout)
    {
        var task = Get(id);
        if (task is null) return null;
        if (task.IsTerminal) return task;

        var waiter = _finished.GetOrAdd(id,
            _ => new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously));
        // The task may have finished between the check above and the GetOrAdd.
        if (Get(id)?.IsTerminal == true) waiter.TrySetResult(true);
        try
        {
            await waiter.Task.WaitAsync(timeout).ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            // The caller gets the task's current state below and decides.
        }
        finally
        {
            if (waiter.Task.IsCompleted) _finished.TryRemove(id, out _);
        }
        return Get(id);
    }

    public FridayTask? FindByRequest(string requestId)
    {
        lock (_gate) return _tasks.Values.FirstOrDefault(t =>
            string.Equals(t.RequestId, requestId, StringComparison.OrdinalIgnoreCase));
    }

    public IReadOnlyList<FridayTask> Recent(int count)
    {
        lock (_gate)
        {
            return _tasks.Values.OrderByDescending(t => t.CreatedAt).Take(count).ToList();
        }
    }

    private List<FridayTask> Snapshot()
    {
        lock (_gate) return _tasks.Values.ToList();
    }

    public bool Cancel(string id, string reason = "Cancelled by the user.")
    {
        var task = Get(id);
        if (task is null || task.IsTerminal) return false;

        try { task.Cancellation?.Cancel(); } catch { }
        task.Status = TaskStatus.Cancelled;
        task.Summary = reason;
        task.NeedsApproval = false;
        task.ApprovalId = null;
        task.UpdatedAt = DateTimeOffset.UtcNow;
        CancelPendingForTask(task.Id);
        Update(task);
        _runtime.Events.Publish("task", task.Id, "cancelled", reason);
        Save();
        return true;
    }

    public void CancelAll(string reason)
    {
        foreach (var task in Snapshot().Where(t => !t.IsTerminal))
        {
            Cancel(task.Id, reason);
        }
    }

    private async Task WorkerAsync()
    {
        while (!_stop.IsCancellationRequested)
        {
            try { await _signal.WaitAsync(_stop.Token).ConfigureAwait(false); }
            catch (OperationCanceledException) { break; }

            while (_queue.TryDequeue(out var task))
            {
                if (task.Status == TaskStatus.Cancelled) continue;
                await ProcessAsync(task).ConfigureAwait(false);
            }
        }
    }

    private async Task ProcessAsync(FridayTask task)
    {
        if (_runtime.EmergencyStopped)
        {
            task.Status = TaskStatus.Failed;
            task.Error = "Emergency stop is engaged. Release it on the laptop first.";
            task.Summary = task.Error;
            Update(task);
            return;
        }

        task.Cancellation = new CancellationTokenSource(TimeSpan.FromSeconds(
            Math.Clamp(_runtime.Config.Policy.MaxTaskSeconds, 30, 86400)));
        var token = task.Cancellation.Token;
        var replySpoken = false;

        try
        {
            task.Status = TaskStatus.Validating;
            task.Progress = "Checking permissions…";
            Update(task);
            _runtime.Events.Publish("task", task.Id, "validating", "Checking the request");

            var context = new ToolContext
            {
                Config = _runtime.Config,
                Source = task.Source,
                DeviceId = task.DeviceId,
                RequestId = task.RequestId,
                Token = token,
                Paths = _runtime.Paths,
                Runtime = _runtime,
                Progress = message =>
                {
                    task.Progress = message;
                    task.UpdatedAt = DateTimeOffset.UtcNow;
                    Changed?.Invoke(task);
                    _runtime.Events.Publish("progress", task.Id, "running", message);
                },
                RequestApproval = request => RequestApprovalAsync(task, request, token),
            };

            task.Status = TaskStatus.Running;
            task.Progress = "Working…";
            Update(task);
            _runtime.Events.Publish("task", task.Id, "running", Clip(task.Instruction, 90));

            var reply = await _runtime.Brain.AskAsync(task.Instruction, context).ConfigureAwait(false);
            replySpoken = reply.Spoken;

            if (token.IsCancellationRequested)
            {
                task.Status = TaskStatus.Cancelled;
                task.Summary = "Cancelled.";
            }
            else
            {
                task.Status = reply.Ok ? TaskStatus.Succeeded : TaskStatus.Failed;
                task.Summary = reply.Text;
                task.Data = reply.Data;
                task.Error = reply.Ok ? "" : reply.Text;
                task.Progress = "";
            }
        }
        catch (OperationCanceledException)
        {
            task.Status = TaskStatus.Cancelled;
            task.Summary = "Cancelled.";
        }
        catch (Exception ex)
        {
            task.Status = TaskStatus.Failed;
            task.Error = ex.Message;
            task.Summary = "I hit an error: " + ex.Message;
            FLog.Error("task", $"{task.Id}: {ex}");
        }
        finally
        {
            task.NeedsApproval = false;
            task.ApprovalId = null;
            task.UpdatedAt = DateTimeOffset.UtcNow;
            try { task.Cancellation?.Dispose(); } catch { }
            task.Cancellation = null;
            Update(task);
            _runtime.Events.Publish("task", task.Id,
                task.Status.ToString().ToLowerInvariant(), Clip(task.Summary, 140));
            Save();

            if (task.Status == TaskStatus.Succeeded && _runtime.Config.Privacy.SpeakReplies && !replySpoken)
                _runtime.Voice.Speak(task.Summary);
        }
    }

    private async Task<ApprovalDecision> RequestApprovalAsync(
        FridayTask task, ApprovalRequest request, CancellationToken token)
    {
        var pending = new PendingApproval(
            request.Id, task.Id, request.Title, request.Detail,
            request.Capability, request.Destructive, request.Source, DateTimeOffset.UtcNow);

        var waiter = new TaskCompletionSource<ApprovalDecision>(TaskCreationOptions.RunContinuationsAsynchronously);
        lock (_gate)
        {
            _pending[request.Id] = pending;
            _waiters[request.Id] = waiter;
        }

        task.Status = TaskStatus.AwaitingApproval;
        task.NeedsApproval = true;
        task.ApprovalId = request.Id;
        task.Progress = request.Title;
        Update(task);

        _runtime.Events.Publish("approval", task.Id, "awaiting_approval",
            request.Title + ": " + Clip(request.Detail, 120));
        _runtime.State.Set(FridayState.AwaitingApproval,
            request.Destructive ? "Destructive action — waiting for you" : "Waiting for permission");
        ApprovalRequested?.Invoke(pending);

        var timeout = TimeSpan.FromSeconds(Math.Clamp(_runtime.Config.Policy.ApprovalTimeoutSeconds, 15, 1800));
        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(token);
        timeoutCts.CancelAfter(timeout);

        ApprovalDecision decision;
        try
        {
            decision = await waiter.Task.WaitAsync(timeoutCts.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            decision = new ApprovalDecision(false, false);
            if (!token.IsCancellationRequested)
                task.Error = "No answer within " + (int)timeout.TotalSeconds + " seconds, so I did not run it.";
        }

        lock (_gate)
        {
            _pending.Remove(request.Id);
            _waiters.Remove(request.Id);
        }

        task.NeedsApproval = false;
        task.ApprovalId = null;
        task.Status = TaskStatus.Running;
        Update(task);
        ApprovalResolved?.Invoke(request.Id);

        return decision;
    }

    public bool ResolveApproval(string approvalId, bool approved, bool remember)
    {
        TaskCompletionSource<ApprovalDecision>? waiter;
        PendingApproval? pending;
        lock (_gate)
        {
            _waiters.TryGetValue(approvalId ?? "", out waiter);
            _pending.TryGetValue(approvalId ?? "", out pending);
        }
        if (waiter is null) return false;

        if (remember && approved && pending is not null)
        {
            var capability = pending.Capability;
            FLog.Info("approval", $"User chose to remember approval for {capability}.");
        }

        waiter.TrySetResult(new ApprovalDecision(approved, remember));
        return true;
    }

    public IReadOnlyList<PendingApproval> PendingApprovals()
    {
        lock (_gate) return _pending.Values.OrderBy(p => p.CreatedAt).ToList();
    }

    private void CancelPendingForTask(string taskId)
    {
        List<TaskCompletionSource<ApprovalDecision>> waiters;
        lock (_gate)
        {
            waiters = _pending.Where(kv => kv.Value.TaskId == taskId)
                .Select(kv => _waiters.TryGetValue(kv.Key, out var w) ? w : null)
                .Where(w => w is not null).Cast<TaskCompletionSource<ApprovalDecision>>().ToList();
            foreach (var id in _pending.Where(kv => kv.Value.TaskId == taskId).Select(kv => kv.Key).ToList())
            {
                _pending.Remove(id);
                _waiters.Remove(id);
            }
        }
        foreach (var waiter in waiters) waiter.TrySetResult(new ApprovalDecision(false, false));
    }

    private void Update(FridayTask task)
    {
        task.UpdatedAt = DateTimeOffset.UtcNow;
        Changed?.Invoke(task);
        if (task.IsTerminal && _finished.TryRemove(task.Id, out var waiter))
            waiter.TrySetResult(true);
    }

    private static string NewId() => "t" + Guid.NewGuid().ToString("n")[..11];

    private static string Clip(string text, int max) =>
        string.IsNullOrEmpty(text) ? "" : (text.Length <= max ? text : text[..max] + "…");

    private void TrimHistory()
    {
        if (_tasks.Count <= 120) return;
        foreach (var old in _tasks.Values.Where(t => t.IsTerminal)
                     .OrderBy(t => t.CreatedAt).Take(_tasks.Count - 120).ToList())
        {
            _tasks.Remove(old.Id);
            _finished.TryRemove(old.Id, out _);
        }
    }

    private void Save()
    {
        try
        {
            string json;
            lock (_gate)
            {
                json = JsonSerializer.Serialize(_tasks.Values.OrderByDescending(t => t.CreatedAt).Take(120).ToList(), _json);
            }
            var temp = AppPaths.TasksFile + ".tmp";
            File.WriteAllText(temp, json, new UTF8Encoding(false));
            File.Move(temp, AppPaths.TasksFile, overwrite: true);
        }
        catch (Exception ex)
        {
            FLog.Warn("tasks", "Could not save tasks.json: " + ex.Message);
        }
    }

    private void Load()
    {
        try
        {
            if (!File.Exists(AppPaths.TasksFile)) return;
            var list = JsonSerializer.Deserialize<List<FridayTask>>(
                File.ReadAllText(AppPaths.TasksFile), _json);
            if (list is null) return;
            lock (_gate)
            {
                foreach (var task in list)
                {
                    if (task.Status is TaskStatus.Running or TaskStatus.AwaitingApproval or TaskStatus.Validating)
                    {
                        task.Status = TaskStatus.Failed;
                        task.Error = "Friday restarted while this task was running.";
                        task.Summary = "Interrupted: Friday restarted before this finished.";
                    }
                    task.NeedsApproval = false;
                    task.ApprovalId = null;
                    _tasks[task.Id] = task;
                }
            }
        }
        catch (Exception ex)
        {
            FLog.Warn("tasks", "Could not load tasks.json: " + ex.Message);
        }
    }
}
