using System;
using System.Threading.Tasks;
using Friday.Ai;
using Friday.Net;
using Friday.Security;
using Friday.Tools;
using Friday.Voice;

namespace Friday.Core;

/// <summary>
/// Owns every long-lived component and wires the real state to the UI. There is
/// exactly one of these per running Friday.
/// </summary>
public sealed class FridayRuntime : IDisposable
{
    public FridayConfig Config { get; }
    public StateHub State { get; } = new();
    public PathGuard Paths { get; }
    public MemoryStore Memory { get; }
    public EventLog Events { get; } = new();
    public ConversationLog Conversation { get; } = new();
    public PairingStore Pairing { get; }
    public ToolRegistry Tools { get; } = new();
    public Orchestrator Brain { get; }
    public TaskQueue Tasks { get; }
    public VoiceEngine Voice { get; }
    public FridayServer Server { get; }

    public bool EmergencyStopped { get; private set; }
    public string? LastProjectPath { get; set; }
    public DateTimeOffset StartedAt { get; } = DateTimeOffset.Now;

    public event Action<bool, string>? EmergencyStopChanged;

    public FridayRuntime(FridayConfig config)
    {
        Config = config;
        Paths = new PathGuard(config.Policy.FileRoots);
        Memory = new MemoryStore();
        Pairing = new PairingStore();
        Brain = new Orchestrator(this);
        Tasks = new TaskQueue(this);
        Voice = new VoiceEngine(config, State);
        Server = new FridayServer(this);

        Conversation.Added += entry =>
        {
            if (Config.Privacy.StoreConversation) Conversation.Persist(entry.Role, entry.Text);
        };
        if (Config.Privacy.StoreConversation) Conversation.LoadRecent(30);

        Events.Published += e =>
        {
            if (e.Type is "approval" or "task")
                FLog.Info("event", $"{e.Status}: {e.Summary}");
        };
    }

    public void Start()
    {
        FLog.Info("app", $"Friday starting in {AppPaths.Home}");
        State.Set(FridayState.Idle, "Starting…");
        FLog.PruneOlderThan(Config.Privacy.RetentionDays);
        Voice.Initialize();
        Tasks.Start();
        Server.Start();

        var key = Config.EffectiveApiKey(out var source);
        FLog.Info("app", string.IsNullOrWhiteSpace(key)
            ? "No AI key configured; local commands only."
            : $"AI configured ({source}), model {Config.EffectiveModel()}.");

        State.Set(FridayState.Idle, "Ready");
    }

    public FridayTask Submit(string instruction, string source, string? deviceId = null, string? requestId = null)
    {
        if (string.IsNullOrWhiteSpace(instruction))
        {
            throw new ArgumentException("The instruction is empty.", nameof(instruction));
        }
        return Tasks.Enqueue(instruction, source, deviceId, requestId ?? Guid.NewGuid().ToString("n"));
    }

    public void SetEmergencyStop(bool engage, string actor)
    {
        if (engage == EmergencyStopped) return;
        EmergencyStopped = engage;

        if (engage)
        {
            Voice.StopListening();
            Voice.StopSpeaking();
            Tasks.CancelAll("Emergency stop engaged.");
            State.Set(FridayState.Error, "Emergency stop — all execution blocked");
            State.MicActive = false;
        }
        else
        {
            State.Set(FridayState.Idle, "Ready");
        }

        FLog.Audit("emergency_stop", actor, engage ? "engaged" : "released",
            engage ? "All tasks cancelled" : "Execution allowed again", true);
        Events.Publish("safety", "", engage ? "emergency_stop" : "released",
            engage ? "Emergency stop engaged" : "Emergency stop released");
        EmergencyStopChanged?.Invoke(engage, actor);
    }

    public void Audit(string action, string actor, string decision, string detail, bool ok,
        string? deviceId = null, string? requestId = null) =>
        FLog.Audit(action, actor, decision, detail, ok, deviceId, requestId);

    public void Dispose()
    {
        try { Server.Dispose(); } catch { }
        try { Tasks.StopAsync().GetAwaiter().GetResult(); } catch { }
        try { Voice.Dispose(); } catch { }
        Config.Save();
        FLog.Info("app", "Friday stopped.");
    }
}
