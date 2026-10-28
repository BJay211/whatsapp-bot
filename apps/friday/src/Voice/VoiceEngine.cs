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
    private RecognizeMode _mode = RecognizeMode.Multiple;

    public event Action<string>? Heard;
    public event Action<string>? WakeWordHeard;
    public event Action<string>? Spoken;
    public event Action? SpeechFinished;
    public event Action<string>? StatusChanged;

    public bool Available { get; private set; }
    public bool TtsAvailable { get; private set; }
    public string AvailabilityError { get; private set; } = "";
    public bool IsListening => _wantListening;

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
                if (_config.Voice.InterruptOnSpeech && _speaking) StopSpeaking();
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
            _synthesizer.SpeakCompleted += (_, _) =>
            {
                _speaking = false;
                _state.Set(FridayState.Idle, "Ready");
                SpeechFinished?.Invoke();
                ResumeIfNeeded();
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
            _recognizer.RecognizeAsyncCancel();
            _recognizer.RecognizeAsync(_mode);
            _state.MicActive = true;
            _state.Set(FridayState.Listening, wakeMode ? "Waiting for the wake word" : "Listening…");
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
        _state.MicActive = false;
        try { _recognizer?.RecognizeAsyncCancel(); } catch { }
        if (!_speaking) _state.Set(FridayState.Idle, "Ready");
    }

    private void OnSpeechRecognized(object? sender, SpeechRecognizedEventArgs e)
    {
        var text = e.Result?.Text?.Trim() ?? "";
        var confidence = e.Result?.Confidence ?? 0;
        if (text.Length == 0 || confidence < 0.25) return;

        if (_wakeMode)
        {
            var match = System.Text.RegularExpressions.Regex.Match(text,
                @"^(?:hey\s+)?friday\b[\s,.:;-]*(?<rest>.*)$",
                System.Text.RegularExpressions.RegexOptions.IgnoreCase);
            if (!match.Success) return;
            var rest = match.Groups["rest"].Value.Trim();
            if (rest.Length == 0)
            {
                WakeWordHeard?.Invoke(text);
                return;
            }
            text = rest;
        }

        _state.MicActive = false;
        if (_mode == RecognizeMode.Single) _wantListening = false;
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
            // Continuous wake-word mode is restarted after every completion.
            try { _recognizer?.RecognizeAsync(RecognizeMode.Multiple); }
            catch { _wantListening = false; }
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

    public void StopSpeaking()
    {
        try { _synthesizer?.SpeakAsyncCancelAll(); } catch { }
        _speaking = false;
    }

    private void ResumeIfNeeded()
    {
        if (_wantListening && _recognizer is not null)
        {
            try
            {
                _recognizer.RecognizeAsync(_config.Voice.WakeWordEnabled
                    ? RecognizeMode.Multiple
                    : RecognizeMode.Single);
            }
            catch { }
        }
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
        try { _recognizer?.Dispose(); } catch { }
        try { _synthesizer?.Dispose(); } catch { }
        _recognizer = null;
        _synthesizer = null;
    }
}
