using System;

namespace Friday.Core;

public enum FridayState
{
    Offline,
    Idle,
    Listening,
    Thinking,
    Speaking,
    Executing,
    AwaitingApproval,
    Success,
    Error,
}

/// <summary>
/// The single source of truth for what Friday is doing. The animated core reads
/// this; it is never animated independently of the real assistant state.
/// </summary>
public sealed class StateHub
{
    private readonly object _gate = new();
    private FridayState _state = FridayState.Idle;
    private string _detail = "";
    private DateTime _since = DateTime.UtcNow;
    private float _amplitude;
    private int _transientToken;

    public event Action<FridayState, string>? Changed;
    public event Action<float>? AmplitudeChanged;

    public FridayState State
    {
        get { lock (_gate) return _state; }
    }

    public string Detail
    {
        get { lock (_gate) return _detail; }
    }

    public TimeSpan Since
    {
        get { lock (_gate) return DateTime.UtcNow - _since; }
    }

    public float Amplitude
    {
        get { lock (_gate) return _amplitude; }
    }

    /// <summary>True while the microphone is actually capturing.</summary>
    public bool MicActive { get; set; }

    public void Set(FridayState state, string? detail = null)
    {
        FridayState previous;
        lock (_gate)
        {
            previous = _state;
            _state = state;
            _detail = detail ?? "";
            _since = DateTime.UtcNow;
        }

        if (previous != state || detail is not null)
            Changed?.Invoke(state, detail ?? "");
    }

    /// <summary>Brief success/error flash that returns to idle by itself.</summary>
    public void Flash(FridayState state, string detail, int milliseconds = 2200)
    {
        int token;
        lock (_gate) token = ++_transientToken;
        Set(state, detail);
        _ = Task.Delay(milliseconds).ContinueWith(_ =>
        {
            lock (_gate)
            {
                if (token != _transientToken) return;
            }
            if (State is FridayState.Success or FridayState.Error)
                Set(FridayState.Idle, "Ready");
        }, TaskScheduler.Default);
    }

    public void SetAmplitude(float value)
    {
        value = Math.Clamp(value, 0f, 1f);
        bool changed;
        lock (_gate)
        {
            changed = Math.Abs(_amplitude - value) > 0.01f;
            _amplitude = value;
        }
        if (changed) AmplitudeChanged?.Invoke(value);
    }

    public void ClearTransient()
    {
        lock (_gate) _transientToken++;
    }
}
