using System;
using System.Collections.Generic;
using System.Linq;
using System.Speech.Recognition;
using System.Speech.Synthesis;
using System.Threading;
using Friday.Core;

namespace Friday.Voice;

/// <summary>
/// Windows speech input and output (System.Speech). Audio is never written to
/// disk and never leaves the machine: recognition is local, using the Windows
/// speech recogniser the user already has.
///
/// Limitations are reported rather than hidden: System.Speech uses the default
/// capture and output devices, so device selection is limited to "Default" here.
/// </summary>
public sealed class VoiceEngine : IDisposable
{
    private readonly FridayConfig _config;
    private readonly StateHub _state;
    private SpeechRecognitionEngine? _recognizer;
    private SpeechSynthesizer? _synthesizer;
    private bool _speaking;
    private bool _wantListening;
    private bool _wakeMode;
    private bool _resumeWake;
    private RecognizeMode _mode = RecognizeMode.Multiple;

    // Streamed replies are queued sentence by sentence. SpeechCompleted fires
    // once per queued prompt, so the idle transition waits for both the last
    // prompt and the end of the stream.
    private readonly object _speechGate = new();
    private bool _streamOpen;
    private int _pendingPrompts;

    // The conversation window. After a command the microphone keeps listening
    // without the wake word, and every spoken phrase pushes the deadline out.
    // Total silence for FollowUpSeconds returns to wake-word-only.
    private readonly object _followGate = new();
    private System.Threading.Timer? _followTimer;
    private DateTime _followUntil = DateTime.MinValue;
    private bool _inFollowUp;

    public event Action<string>? Heard;
    public event Action<string>? WakeWordHeard;
    public event Action<string>? Spoken;
    public event Action? SpeechFinished;
    public event Action<string>? StatusChanged;

    public bool Available { get; private set; }
    public bool TtsAvailable { get; private set; }
    public string AvailabilityError { get; private set; } = "";
    public bool IsListening => _wantListening;

    /// <summary>True while the follow-up conversation window is open.</summary>
    public bool InFollowUp
    {
        get { lock (_followGate) return _inFollowUp; }
    }

    /// <summary>Whole seconds left in the conversation window, or 0.</summary>
    public int FollowUpRemaining
    {
        get
        {
            lock (_followGate)
            {
                if (!_inFollowUp) return 0;
                return Math.Max(0, (int)Math.Ceiling((_followUntil - DateTime.UtcNow).TotalSeconds));
            }
        }
    }

    public string WakeWordPhrase =>
        string.IsNullOrWhiteSpace(_config.Voice.WakeWord) ? "friday" : _config.Voice.WakeWord.Trim();

    public VoiceEngine(FridayConfig config, StateHub state)
    {
        _config = config;
        _state = state;
    }

    public void Initialize()
    {
        if (!_config.Voice.Enabled)
        {
            AvailabilityError = "Voice is switched off in Settings.";
            return;
        }

        try
        {
            var installed = SpeechRecognitionEngine.InstalledRecognizers();
            if (installed.Count == 0)
            {
                AvailabilityError =
                    "Windows has no speech recogniser installed. Add one under Settings > " +
                    "Time & language > Speech, then restart Friday.";
                StatusChanged?.Invoke(AvailabilityError);
                return;
            }

            var preferred = installed.FirstOrDefault(r => r.Culture.Name.StartsWith("en", StringComparison.OrdinalIgnoreCase))
                            ?? installed[0];
            _recognizer = new SpeechRecognitionEngine(preferred);
            _recognizer.LoadGrammar(new DictationGrammar());
            _recognizer.SetInputToDefaultAudioDevice();
            _recognizer.SpeechDetected += (_, _) =>
            {
                // Deliberate barge-in happens on recognition of the wake word, so
                // Friday's own voice is not mistaken for the user's.
                if (_speaking && _config.Voice.InterruptOnSpeech && !_wakeMode) StopSpeaking();
                if (!_speaking) TouchFollowUp();
            };
            _recognizer.AudioLevelUpdated += (_, e) => _state.SetAmplitude(e.AudioLevel / 100f);
            _recognizer.SpeechRecognized += OnSpeechRecognized;
            _recognizer.RecognizeCompleted += OnRecognizeCompleted;
            _recognizer.SpeechRecognitionRejected += (_, _) => { };

            Available = true;
            AvailabilityError = "";
            FLog.Info("voice", "Recogniser ready: " + preferred.Culture.DisplayName);
        }
        catch (Exception ex)
        {
            Available = false;
            AvailabilityError = "Speech recognition could not start: " + ex.Message;
            FLog.Warn("voice", AvailabilityError);
        }

        try
        {
            _synthesizer = new SpeechSynthesizer();
            _synthesizer.SetOutputToDefaultAudioDevice();
            _synthesizer.Rate = _config.Voice.TtsRate;
            _synthesizer.Volume = _config.Voice.TtsVolume;
            if (!string.IsNullOrWhiteSpace(_config.Voice.TtsVoice))
            {
                try { _synthesizer.SelectVoice(_config.Voice.TtsVoice); }
                catch { FLog.Warn("voice", "Saved TTS voice is not installed; using the default."); }
            }
            _synthesizer.SpeakCompleted += (_, e) =>
            {
                // A cancelled prompt (replaced by a newer reply or barge-in) must
                // not flip the UI to idle while the new prompt is still playing.
                if (e.Cancelled) return;
                bool finish;
                lock (_speechGate)
                {
                    if (_pendingPrompts > 0) _pendingPrompts--;
                    finish = !_streamOpen && _pendingPrompts <= 0;
                }
                if (!finish) return;
                FinishSpeech();
            };
            TtsAvailable = true;
        }
        catch (Exception ex)
        {
            TtsAvailable = false;
            FLog.Warn("voice", "Text to speech is unavailable: " + ex.Message);
        }

        StatusChanged?.Invoke(AvailabilityError);
    }

    public string[] Voices()
    {
        try
        {
            return SpeechSynthesisVoice() ?? Array.Empty<string>();
        }
        catch
        {
            return Array.Empty<string>();
        }
    }

    private string[]? SpeechSynthesisVoice()
    {
        using var probe = new SpeechSynthesizer();
        return probe.GetInstalledVoices()
            .Where(v => v.Enabled)
            .Select(v => v.VoiceInfo.Name)
            .ToArray();
    }

    public bool StartListening(bool wakeMode = false)
    {
        if (_recognizer is null)
        {
            StatusChanged?.Invoke(AvailabilityError.Length > 0
                ? AvailabilityError
                : "Speech recognition is not available.");
            return false;
        }

        try
        {
            _wakeMode = wakeMode;
            _wantListening = true;
            _mode = RecognizeMode.Multiple;
            _resumeWake = false;
            DisarmFollowUp();
            _recognizer.RecognizeAsyncCancel();
            _recognizer.RecognizeAsync(_mode);
            _state.MicActive = true;
            _state.Set(FridayState.Listening, ListeningDetail());
            return true;
        }
        catch (Exception ex)
        {
            _wantListening = false;
            _state.MicActive = false;
            AvailabilityError = "Could not start listening: " + ex.Message;
            StatusChanged?.Invoke(AvailabilityError);
            return false;
        }
    }

    /// <summary>One-shot capture for push-to-talk: stops after the first phrase.</summary>
    public bool ListenOnce()
    {
        if (_recognizer is null) return StartListening(false);
        try
        {
            _wakeMode = false;
            _wantListening = true;
            _mode = RecognizeMode.Single;
            // A push-to-talk phrase should not silently switch always-on
            // listening off; after the capture the engine returns to wake mode.
            _resumeWake = _config.Voice.WakeWordEnabled;
            DisarmFollowUp();
            _recognizer.RecognizeAsyncCancel();
            _recognizer.RecognizeAsync(RecognizeMode.Single);
            _state.MicActive = true;
            _state.Set(FridayState.Listening, "Listening…");
            return true;
        }
        catch (Exception ex)
        {
            _wantListening = false;
            _state.MicActive = false;
            AvailabilityError = "Could not start listening: " + ex.Message;
            StatusChanged?.Invoke(AvailabilityError);
            return false;
        }
    }

    public void StopListening()
    {
        _wantListening = false;
        _resumeWake = false;
        _state.MicActive = false;
        DisarmFollowUp();
        try { _recognizer?.RecognizeAsyncCancel(); } catch { }
        if (!_speaking) _state.Set(FridayState.Idle, "Ready");
    }

    private string ListeningDetail()
    {
        if (!_wakeMode) return "Listening…";
        return InFollowUp
            ? $"Listening · {FollowUpRemaining}s"
            : $"Say \"{WakeWordPhrase}\"";
    }

    /// <summary>
    /// Opens (or extends) the hands-free window. Called after a command, when the
    /// wake word is said on its own, and again after Friday finishes speaking.
    /// </summary>
    private void ArmFollowUp()
    {
        var seconds = Math.Clamp(_config.Voice.FollowUpSeconds, 0, 600);
        if (!_wakeMode || !_config.Voice.WakeWordEnabled || seconds == 0)
        {
            DisarmFollowUp();
            return;
        }

        lock (_followGate)
        {
            _inFollowUp = true;
            _followUntil = DateTime.UtcNow.AddSeconds(seconds);
            _followTimer ??= new System.Threading.Timer(_ => OnFollowUpTimer(), null,
                Timeout.Infinite, Timeout.Infinite);
            _followTimer.Change(seconds * 1000, Timeout.Infinite);
        }
    }

    private void TouchFollowUp()
    {
        var seconds = Math.Clamp(_config.Voice.FollowUpSeconds, 0, 600);
        if (seconds == 0) return;
        lock (_followGate)
        {
            if (!_inFollowUp) return;
            _followUntil = DateTime.UtcNow.AddSeconds(seconds);
            _followTimer?.Change(seconds * 1000, Timeout.Infinite);
        }
    }

    private void DisarmFollowUp()
    {
        lock (_followGate)
        {
            _inFollowUp = false;
            _followUntil = DateTime.MinValue;
            _followTimer?.Change(Timeout.Infinite, Timeout.Infinite);
        }
    }

    private void OnFollowUpTimer()
    {
        lock (_followGate)
        {
            if (_inFollowUp && DateTime.UtcNow < _followUntil)
            {
                var left = (int)Math.Ceiling((_followUntil - DateTime.UtcNow).TotalSeconds);
                _followTimer?.Change(Math.Max(500, left * 1000), Timeout.Infinite);
                return;
            }
            _inFollowUp = false;
        }

        if (!_wantListening) return;
        if (_wakeMode)
        {
            _state.Set(FridayState.Listening, $"Say \"{WakeWordPhrase}\"");
        }
        else
        {
            _state.Set(FridayState.Idle, "Ready");
        }
    }

    private void OnSpeechRecognized(object? sender, SpeechRecognizedEventArgs e)
    {
        var text = e.Result?.Text?.Trim() ?? "";
        var confidence = e.Result?.Confidence ?? 0;
        if (text.Length == 0 || confidence < 0.25) return;

        // While Friday is speaking, only a deliberate wake word is accepted.
        // Anything else is almost certainly Friday hearing its own voice.
        if (_speaking)
        {
            if (!_wakeMode) return;
            if (!WakeWord.TryExtract(text, WakeWordPhrase, out var duringSpeech)) return;
            if (duringSpeech.Length == 0) return;
            StopSpeaking();
            text = duringSpeech;
        }

        if (_wakeMode)
        {
            var inFollowUp = InFollowUp;
            if (WakeWord.TryExtract(text, WakeWordPhrase, out var command))
            {
                if (command.Length == 0)
                {
                    ArmFollowUp();
                    _state.Set(FridayState.Listening, ListeningDetail());
                    WakeWordHeard?.Invoke(text);
                    return;
                }
                text = command;
            }
            else if (!inFollowUp)
            {
                // Continuous listening, but not a command for Friday.
                return;
            }
        }

        _state.MicActive = false;
        if (_mode == RecognizeMode.Single && !_resumeWake) _wantListening = false;
        ArmFollowUp();
        _state.Set(FridayState.Listening, ListeningDetail());
        Heard?.Invoke(text);
    }

    private void OnRecognizeCompleted(object? sender, RecognizeCompletedEventArgs e)
    {
        _state.MicActive = false;
        if (e.Error is not null)
        {
            FLog.Warn("voice", "Recognition stopped: " + e.Error.Message);
            _state.Set(FridayState.Idle, "Microphone error");
            _wantListening = false;
            return;
        }

        if (_wantListening && !_speaking && _config.Voice.WakeWordEnabled)
        {
            // Continuous wake-word mode is restarted after every completion, and
            // push-to-talk hands the microphone back to the wake word.
            if (_resumeWake)
            {
                _wakeMode = true;
                _mode = RecognizeMode.Multiple;
                _resumeWake = false;
            }
            try { _recognizer?.RecognizeAsync(RecognizeMode.Multiple); }
            catch { _wantListening = false; }
            _state.Set(FridayState.Listening, ListeningDetail());
        }
        else
        {
            _wantListening = false;
            if (!_speaking) _state.Set(FridayState.Idle, "Ready");
        }
    }

    public void Speak(string text)
    {
        if (!TtsAvailable || _synthesizer is null || string.IsNullOrWhiteSpace(text)) return;
        if (!_config.Privacy.SpeakReplies) return;

        try
        {
            lock (_speechGate)
            {
                _streamOpen = false;
                _pendingPrompts = 0;
            }
            _speaking = true;
            _state.Set(FridayState.Speaking, "Speaking…");
            try { _synthesizer.SpeakAsyncCancelAll(); } catch { }
            _synthesizer.SpeakAsync(text);
            Spoken?.Invoke(text);
        }
        catch (Exception ex)
        {
            _speaking = false;
            FLog.Warn("voice", "Could not speak: " + ex.Message);
        }
    }

    /// <summary>
    /// Starts a streamed reply. Complete sentences are added with
    /// <see cref="EnqueueSpeech"/>; <see cref="EndStreamedSpeech"/> closes the
    /// stream so the idle/follow-up transition happens exactly once.
    /// </summary>
    public bool BeginStreamedSpeech()
    {
        if (!TtsAvailable || _synthesizer is null) return false;
        if (!_config.Privacy.SpeakReplies) return false;

        try { _synthesizer.SpeakAsyncCancelAll(); } catch { }
        lock (_speechGate)
        {
            _streamOpen = true;
            _pendingPrompts = 0;
        }
        _speaking = true;
        _state.Set(FridayState.Speaking, "Speaking…");
        return true;
    }

    /// <summary>Queues one complete sentence while the model is still writing.</summary>
    public bool EnqueueSpeech(string sentence)
    {
        if (!TtsAvailable || _synthesizer is null) return false;
        if (!_config.Privacy.SpeakReplies || string.IsNullOrWhiteSpace(sentence)) return false;

        lock (_speechGate) _pendingPrompts++;
        try
        {
            _synthesizer.SpeakAsync(sentence);
            Spoken?.Invoke(sentence);
            return true;
        }
        catch (Exception ex)
        {
            lock (_speechGate) if (_pendingPrompts > 0) _pendingPrompts--;
            FLog.Warn("voice", "Could not queue speech: " + ex.Message);
            return false;
        }
    }

    public void EndStreamedSpeech()
    {
        bool finish;
        lock (_speechGate)
        {
            _streamOpen = false;
            finish = _pendingPrompts <= 0;
        }
        if (finish) FinishSpeech();
    }

    private void FinishSpeech()
    {
        _speaking = false;
        if (_wakeMode && _wantListening)
        {
            // The hands-free window starts after Friday stops talking, not while
            // it is talking, so the user gets the full time.
            if (InFollowUp && _config.Voice.AutoListenAfterReply) ArmFollowUp();
            _state.Set(FridayState.Listening, ListeningDetail());
        }
        else
        {
            _state.Set(FridayState.Idle, "Ready");
        }
        SpeechFinished?.Invoke();
        ResumeIfNeeded();
    }

    public void StopSpeaking()
    {
        try { _synthesizer?.SpeakAsyncCancelAll(); } catch { }
        lock (_speechGate)
        {
            _streamOpen = false;
            _pendingPrompts = 0;
        }
        _speaking = false;
    }

    private void ResumeIfNeeded()
    {
        if (!_wantListening || _recognizer is null) return;
        try
        {
            _recognizer.RecognizeAsync(_wakeMode
                ? RecognizeMode.Multiple
                : RecognizeMode.Single);
            _state.MicActive = true;
            _state.Set(FridayState.Listening, ListeningDetail());
        }
        catch { }
    }

    public void ApplySettings()
    {
        if (_synthesizer is not null)
        {
            try
            {
                _synthesizer.Rate = _config.Voice.TtsRate;
                _synthesizer.Volume = _config.Voice.TtsVolume;
                if (!string.IsNullOrWhiteSpace(_config.Voice.TtsVoice))
                    _synthesizer.SelectVoice(_config.Voice.TtsVoice);
            }
            catch { }
        }
    }

    public void Dispose()
    {
        StopListening();
        StopSpeaking();
        lock (_followGate)
        {
            _followTimer?.Dispose();
            _followTimer = null;
        }
        try { _recognizer?.Dispose(); } catch { }
        try { _synthesizer?.Dispose(); } catch { }
        _recognizer = null;
        _synthesizer = null;
    }
}
