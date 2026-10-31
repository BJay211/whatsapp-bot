using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Friday.Core;

public sealed class AiConfig
{
    public bool Enabled { get; set; } = true;
    public string BaseUrl { get; set; } = "https://api.groq.com/openai/v1";
    public string Model { get; set; } = "llama-3.3-70b-versatile";
    public double Temperature { get; set; } = 0.3;
    public int MaxTokens { get; set; } = 1200;
    /// <summary>DPAPI-protected key. Never a plaintext secret on disk.</summary>
    public string ApiKeyProtected { get; set; } = "";
    public int MaxToolRounds { get; set; } = 6;
}

public sealed class VoiceConfig
{
    public bool Enabled { get; set; } = true;
    public bool WakeWordEnabled { get; set; } = true;
    public string WakeWord { get; set; } = "friday";

    /// <summary>
    /// How long the microphone keeps accepting commands without the wake word
    /// after one is given. Reset by every spoken phrase; 0 disables the window.
    /// </summary>
    public int FollowUpSeconds { get; set; } = 30;

    public bool PushToTalk { get; set; } = true;
    public bool AutoListenAfterReply { get; set; } = true;
    public bool InterruptOnSpeech { get; set; } = true;
    public string TtsVoice { get; set; } = "";
    public int TtsRate { get; set; } = 1;
    public int TtsVolume { get; set; } = 100;
    public string InputDevice { get; set; } = "default";
}

public sealed class UiConfig
{
    public bool StartFullscreen { get; set; } = true;
    public bool AlwaysOnTop { get; set; }
    public string VisualMode { get; set; } = "aurora";
    public string Accent { get; set; } = "gold";
    public int Fps { get; set; } = 60;
    public bool ReducedMotion { get; set; }
    public bool ShowParticles { get; set; } = true;
    public bool StartCompact { get; set; }
    public bool MinimizeToTray { get; set; } = true;
}

public sealed class ServerConfig
{
    public bool Enabled { get; set; } = true;
    public int Port { get; set; } = 8790;
    public bool AllowLan { get; set; } = true;
    public bool RequirePairing { get; set; } = true;
    public bool UseTls { get; set; } = true;
    public int PairingCodeMinutes { get; set; } = 10;
    public int MaxBodyKb { get; set; } = 1024;
    public bool AllowRemoteApproval { get; set; } = true;
}

public sealed class PolicyConfig
{
    /// <summary>Approve before every computer-changing action.</summary>
    public bool StrictConfirm { get; set; }
    public bool AutoApproveSafe { get; set; } = true;
    public bool AllowFreeformShell { get; set; }
    public bool AllowInputControl { get; set; } = true;
    public bool AllowScreenCapture { get; set; } = true;
    public bool AllowFileWrites { get; set; } = true;
    public bool AllowDelete { get; set; } = true;
    public bool AllowPowerControls { get; set; } = true;
    public bool AllowDownloads { get; set; } = true;
    public int CommandTimeoutSeconds { get; set; } = 120;
    public int MaxTaskSeconds { get; set; } = 1800;
    public int ApprovalTimeoutSeconds { get; set; } = 180;
    public List<string> FileRoots { get; set; } = new();
    public List<string> ShellAllowlist { get; set; } = DefaultShellAllowlist();
    public List<string> BlockedPatterns { get; set; } = DefaultBlocked();
    public List<string> TrustedApps { get; set; } = new();

    public static List<string> DefaultShellAllowlist() => new()
    {
        "git status*", "git diff*", "git log*", "git branch*", "git remote -v",
        "git show*", "git add *", "git commit *",
        "dotnet build*", "dotnet test*", "dotnet run*", "dotnet --info*",
        "npm test*", "npm run build*", "npm run lint*", "npm --version*",
        "node --version*", "node -v*",
        "gradlew *", ".\\gradlew *",
        "python --version*", "python -m pytest*", "python -m pip list*",
        "pytest*", "pip list*",
        "dir*", ".\\dir*", "Get-ChildItem*", "Get-Process*", "Get-Service*",
        "where*", "where.exe*", "tasklist*", "ipconfig*", "systeminfo*",
        "echo *", "hostname*", "whoami*", "Get-Date*", "Get-ComputerInfo*",
        "adb devices*", "java -version*", "javac -version*",
        "code --version*", "git --version*", "dotnet --list-sdks*",
        "type *", "Get-Content*",
    };

    public static List<string> DefaultBlocked() => new()
    {
        "format ", "diskpart", "bcdedit", "vssadmin delete",
        "reg delete", "reg add", "netsh ", "Set-ExecutionPolicy",
        "sc delete", "net user", "net localgroup", "takeown", "icacls",
        "Remove-Item -Recurse -Force C:\\", "rm -rf /", "del /f /s /q C:\\",
        "cipher /w", "wevtutil cl", "schtasks /create", "schtasks /change",
    };
}

public sealed class StartupConfig
{
    public bool LaunchAtLogin { get; set; }
    public bool StartMinimized { get; set; }
}

/// <summary>
/// Internet relay. Friday dials out to a small relay service, so the phone can
/// reach the laptop from any network without port forwarding. The laptop id is
/// public routing information; the relay key proves which laptop owns that id
/// and is DPAPI-protected like the AI key.
/// </summary>
public sealed class RelayConfig
{
    public bool Enabled { get; set; }
    public string Url { get; set; } = "";
    public string LaptopId { get; set; } = "";
    public string KeyProtected { get; set; } = "";
}

public sealed class PrivacyConfig
{
    public bool StoreConversation { get; set; } = true;
    public bool StoreAudit { get; set; } = true;
    public int RetentionDays { get; set; } = 90;
    public bool SpeakReplies { get; set; } = true;
    public bool LogToolOutput { get; set; } = true;
}

/// <summary>
/// User configuration stored at %APPDATA%\Friday\config.json. Secrets are
/// DPAPI-encrypted for the current Windows user; environment variables win over
/// the file so a key can be supplied without ever writing it to disk.
/// </summary>
public sealed class FridayConfig
{
    public int Version { get; set; } = 2;
    public string OwnerName { get; set; } = "";
    public AiConfig Ai { get; set; } = new();
    public VoiceConfig Voice { get; set; } = new();
    public UiConfig Ui { get; set; } = new();
    public ServerConfig Server { get; set; } = new();
    public RelayConfig Relay { get; set; } = new();
    public PolicyConfig Policy { get; set; } = new();
    public StartupConfig Startup { get; set; } = new();
    public PrivacyConfig Privacy { get; set; } = new();

    internal static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        PropertyNameCaseInsensitive = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
    };

    public static FridayConfig Load()
    {
        AppPaths.Ensure();
        FridayConfig config;
        try
        {
            if (File.Exists(AppPaths.ConfigFile))
            {
                var json = File.ReadAllText(AppPaths.ConfigFile);
                config = JsonSerializer.Deserialize<FridayConfig>(json, JsonOptions) ?? new FridayConfig();
            }
            else
            {
                config = new FridayConfig();
            }
        }
        catch (Exception ex)
        {
            FLog.Warn("config", "Could not read config.json, using defaults: " + ex.Message);
            config = new FridayConfig();
        }

        var migrated = !File.Exists(AppPaths.ConfigFile) || config.Version < 2;
        config.Normalise();
        if (migrated) config.Save();
        return config;
    }

    public void Normalise()
    {
        // Version 2 turned the wake word into the primary input and added the
        // hands-free conversation window. Existing configs are migrated once so
        // the new behaviour is actually on after an update.
        if (Version < 2)
        {
            Voice.WakeWordEnabled = true;
            Voice.AutoListenAfterReply = true;
            if (Voice.FollowUpSeconds <= 0) Voice.FollowUpSeconds = 30;
            Version = 2;
        }

        if (Policy.FileRoots.Count == 0)
            Policy.FileRoots = AppPaths.DefaultFileRoots().ToList();
        if (Policy.ShellAllowlist.Count == 0)
            Policy.ShellAllowlist = PolicyConfig.DefaultShellAllowlist();
        if (Policy.BlockedPatterns.Count == 0)
            Policy.BlockedPatterns = PolicyConfig.DefaultBlocked();

        Ai.BaseUrl = string.IsNullOrWhiteSpace(Ai.BaseUrl)
            ? "https://api.groq.com/openai/v1"
            : Ai.BaseUrl.Trim().TrimEnd('/');
        Ai.Model = string.IsNullOrWhiteSpace(Ai.Model) ? "llama-3.3-70b-versatile" : Ai.Model.Trim();
        Ai.Temperature = Math.Clamp(Ai.Temperature, 0, 1.5);
        Ai.MaxTokens = Math.Clamp(Ai.MaxTokens, 128, 8192);

        Server.Port = Server.Port is > 0 and < 65536 ? Server.Port : 8790;
        Server.MaxBodyKb = Math.Clamp(Server.MaxBodyKb, 8, 8192);

        Relay.Url = (Relay.Url ?? "").Trim().TrimEnd('/');
        Relay.LaptopId = (Relay.LaptopId ?? "").Trim();
        if (Relay.Enabled) EnsureRelayIdentity();

        Ui.Fps = Math.Clamp(Ui.Fps, 15, 120);
        if (string.IsNullOrWhiteSpace(Ui.VisualMode)) Ui.VisualMode = "aurora";
        if (string.IsNullOrWhiteSpace(Ui.Accent)) Ui.Accent = "gold";

        Voice.WakeWord = string.IsNullOrWhiteSpace(Voice.WakeWord) ? "friday" : Voice.WakeWord.Trim();
        Voice.FollowUpSeconds = Math.Clamp(Voice.FollowUpSeconds, 0, 600);
        Voice.TtsRate = Math.Clamp(Voice.TtsRate, -10, 10);
        Voice.TtsVolume = Math.Clamp(Voice.TtsVolume, 0, 100);

        Policy.CommandTimeoutSeconds = Math.Clamp(Policy.CommandTimeoutSeconds, 5, 3600);
        Policy.ApprovalTimeoutSeconds = Math.Clamp(Policy.ApprovalTimeoutSeconds, 15, 1800);
        Privacy.RetentionDays = Math.Clamp(Privacy.RetentionDays, 1, 3650);
    }

    public void Save()
    {
        try
        {
            AppPaths.Ensure();
            var json = JsonSerializer.Serialize(this, JsonOptions);
            var temp = AppPaths.ConfigFile + ".tmp";
            File.WriteAllText(temp, json, new UTF8Encoding(false));
            File.Move(temp, AppPaths.ConfigFile, overwrite: true);
        }
        catch (Exception ex)
        {
            FLog.Error("config", "Could not save config.json: " + ex.Message);
        }
    }

    /// <summary>Environment variable first, then the DPAPI-protected file value.</summary>
    public string EffectiveApiKey(out string source)
    {
        var env = Environment.GetEnvironmentVariable("FRIDAY_AI_KEY");
        if (!string.IsNullOrWhiteSpace(env))
        {
            source = "environment (FRIDAY_AI_KEY)";
            return env.Trim();
        }

        if (!string.IsNullOrWhiteSpace(Ai.ApiKeyProtected))
        {
            try
            {
                var bytes = ProtectedData.Unprotect(
                    Convert.FromBase64String(Ai.ApiKeyProtected), null, DataProtectionScope.CurrentUser);
                source = "encrypted config (DPAPI)";
                return Encoding.UTF8.GetString(bytes).Trim();
            }
            catch (Exception ex)
            {
                FLog.Warn("config", "Stored API key could not be decrypted: " + ex.Message);
            }
        }

        source = "not configured";
        return "";
    }

    public void StoreApiKey(string? key)
    {
        if (string.IsNullOrWhiteSpace(key))
        {
            Ai.ApiKeyProtected = "";
            Save();
            return;
        }

        Ai.ApiKeyProtected = ProtectSecret(key);
        Save();
    }

    /// <summary>DPAPI-protect a secret for this Windows user without writing it.</summary>
    public static string ProtectSecret(string secret) =>
        Convert.ToBase64String(ProtectedData.Protect(
            Encoding.UTF8.GetBytes(secret.Trim()), null, DataProtectionScope.CurrentUser));

    public bool HasStoredKey => !string.IsNullOrWhiteSpace(Ai.ApiKeyProtected);

    // ------------------------------------------------------------ relay identity

    /// <summary>
    /// Creates the laptop id and relay key on first use. The id is 128-bit random
    /// routing information; the key is a separate 256-bit secret. Neither is
    /// derived from the machine name or Windows account.
    /// </summary>
    public void EnsureRelayIdentity()
    {
        if (string.IsNullOrWhiteSpace(Relay.LaptopId))
            Relay.LaptopId = "lap" + Convert.ToHexString(RandomNumberGenerator.GetBytes(10)).ToLowerInvariant();
        if (string.IsNullOrWhiteSpace(Relay.KeyProtected))
            Relay.KeyProtected = ProtectSecret(Base64Url(RandomNumberGenerator.GetBytes(32)));
        Save();
    }

    public string EffectiveRelayKey()
    {
        var env = Environment.GetEnvironmentVariable("FRIDAY_RELAY_KEY");
        if (!string.IsNullOrWhiteSpace(env)) return env.Trim();
        if (string.IsNullOrWhiteSpace(Relay.KeyProtected)) return "";
        try
        {
            var bytes = ProtectedData.Unprotect(
                Convert.FromBase64String(Relay.KeyProtected), null, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(bytes).Trim();
        }
        catch (Exception ex)
        {
            FLog.Warn("config", "Stored relay key could not be decrypted: " + ex.Message);
            return "";
        }
    }

    /// <summary>Rotates the relay identity. Old relay sessions stop working.</summary>
    public void RotateRelayIdentity()
    {
        Relay.LaptopId = "";
        Relay.KeyProtected = "";
        EnsureRelayIdentity();
    }

    private static string Base64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    /// <summary>
    /// A deep copy used by the settings dialog so edits can be cancelled without
    /// touching the running assistant.
    /// </summary>
    public FridayConfig Clone()
    {
        var json = JsonSerializer.Serialize(this, JsonOptions);
        return JsonSerializer.Deserialize<FridayConfig>(json, JsonOptions) ?? new FridayConfig();
    }

    /// <summary>Adopt an edited copy in place (the runtime holds this instance).</summary>
    public void CopyFrom(FridayConfig other)
    {
        Version = other.Version;
        OwnerName = other.OwnerName;
        Ai = other.Ai;
        Voice = other.Voice;
        Ui = other.Ui;
        Server = other.Server;
        Relay = other.Relay;
        Policy = other.Policy;
        Startup = other.Startup;
        Privacy = other.Privacy;
        Normalise();
    }

    public string EffectiveBaseUrl()
    {
        var env = Environment.GetEnvironmentVariable("FRIDAY_AI_BASE");
        return string.IsNullOrWhiteSpace(env) ? Ai.BaseUrl : env.Trim().TrimEnd('/');
    }

    public string EffectiveModel()
    {
        var env = Environment.GetEnvironmentVariable("FRIDAY_AI_MODEL");
        return string.IsNullOrWhiteSpace(env) ? Ai.Model : env.Trim();
    }
}
