using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Friday.Core;

namespace Friday.Net;

public sealed class PairedDevice
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Platform { get; set; } = "";
    public string TokenHash { get; set; } = "";
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
    public DateTimeOffset LastSeenAt { get; set; } = DateTimeOffset.UtcNow;
    public bool Revoked { get; set; }
}

/// <summary>
/// Pairing codes are short-lived, single use and only valid over TLS. A paired
/// device gets its own random token; only its SHA-256 hash is written to disk,
/// so the file cannot be replayed as a credential if it leaks.
/// </summary>
public sealed class PairingStore
{
    private const string Alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private readonly object _gate = new();
    private readonly List<PairedDevice> _devices = new();
    private readonly JsonSerializerOptions _json = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
    };

    private string _code = "";
    private DateTimeOffset _codeExpires = DateTimeOffset.MinValue;

    public event Action? Changed;

    public PairingStore()
    {
        Load();
        NewCode(10);
    }

    public string CurrentCode
    {
        get
        {
            lock (_gate)
            {
                if (_code.Length == 0 || DateTimeOffset.UtcNow > _codeExpires) NewCode(10);
                return _code;
            }
        }
    }

    public DateTimeOffset CodeExpires
    {
        get { lock (_gate) return _codeExpires; }
    }

    public string NewCode(int minutes)
    {
        var chars = new char[8];
        for (var i = 0; i < chars.Length; i++)
            chars[i] = Alphabet[RandomNumberGenerator.GetInt32(Alphabet.Length)];
        lock (_gate)
        {
            _code = new string(chars);
            _codeExpires = DateTimeOffset.UtcNow.AddMinutes(Math.Clamp(minutes, 1, 60));
        }
        Changed?.Invoke();
        return new string(chars);
    }

    public void InvalidateCode()
    {
        lock (_gate)
        {
            _code = "";
            _codeExpires = DateTimeOffset.MinValue;
        }
        Changed?.Invoke();
    }

    public (bool Ok, string Token, PairedDevice? Device, string Error) Pair(
        string code, string deviceName, string platform)
    {
        lock (_gate)
        {
            if (_code.Length == 0 || DateTimeOffset.UtcNow > _codeExpires)
                return (false, "", null, "The pairing code has expired. Generate a new one on the laptop.");
            if (!FixedEquals(NormaliseCode(code), _code))
                return (false, "", null, "That pairing code does not match.");

            // Single use.
            _code = "";
            _codeExpires = DateTimeOffset.MinValue;

            var token = Base64Url(RandomNumberGenerator.GetBytes(32));
            var device = new PairedDevice
            {
                Id = "d" + Guid.NewGuid().ToString("n")[..10],
                Name = string.IsNullOrWhiteSpace(deviceName) ? "Phone" : Trim(deviceName, 60),
                Platform = Trim(platform, 40),
                TokenHash = HashToken(token),
            };
            _devices.Add(device);
            SaveLocked();
            Changed?.Invoke();
            return (true, token, device, "");
        }
    }

    public PairedDevice? Authenticate(string token)
    {
        if (string.IsNullOrWhiteSpace(token)) return null;
        var hash = HashToken(token);
        lock (_gate)
        {
            var device = _devices.FirstOrDefault(d => !d.Revoked && FixedEquals(d.TokenHash, hash));
            if (device is null) return null;
            device.LastSeenAt = DateTimeOffset.UtcNow;
            return device;
        }
    }

    public bool Revoke(string deviceId)
    {
        bool changed;
        lock (_gate)
        {
            var device = _devices.FirstOrDefault(d => d.Id == deviceId);
            if (device is null) return false;
            device.Revoked = true;
            changed = true;
            SaveLocked();
        }
        if (changed) Changed?.Invoke();
        return true;
    }

    public IReadOnlyList<PairedDevice> Devices
    {
        get { lock (_gate) return _devices.OrderByDescending(d => d.CreatedAt).ToList(); }
    }

    public int ActiveCount
    {
        get { lock (_gate) return _devices.Count(d => !d.Revoked); }
    }

    internal void Touch(string deviceId)
    {
        lock (_gate)
        {
            var device = _devices.FirstOrDefault(d => d.Id == deviceId);
            if (device is null) return;
            device.LastSeenAt = DateTimeOffset.UtcNow;
        }
    }

    private static string NormaliseCode(string code) =>
        new((code ?? "").Where(char.IsLetterOrDigit).Select(char.ToUpperInvariant).ToArray());

    private static bool FixedEquals(string a, string b)
    {
        if (a.Length != b.Length) return false;
        return CryptographicOperations.FixedTimeEquals(
            Encoding.UTF8.GetBytes(a), Encoding.UTF8.GetBytes(b));
    }

    private static string HashToken(string token) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token)));

    internal static string Base64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static string Trim(string value, int max) =>
        string.IsNullOrWhiteSpace(value) ? "" : (value.Length <= max ? value.Trim() : value[..max].Trim());

    private void SaveLocked()
    {
        try
        {
            var json = JsonSerializer.Serialize(_devices, _json);
            var temp = AppPaths.DevicesFile + ".tmp";
            File.WriteAllText(temp, json, new UTF8Encoding(false));
            File.Move(temp, AppPaths.DevicesFile, overwrite: true);
        }
        catch (Exception ex)
        {
            FLog.Warn("pairing", "Could not save devices.json: " + ex.Message);
        }
    }

    private void Load()
    {
        try
        {
            if (!File.Exists(AppPaths.DevicesFile)) return;
            var list = JsonSerializer.Deserialize<List<PairedDevice>>(
                File.ReadAllText(AppPaths.DevicesFile), _json);
            if (list is not null) _devices.AddRange(list);
        }
        catch (Exception ex)
        {
            FLog.Warn("pairing", "Could not load devices.json: " + ex.Message);
        }
    }
}

public static class PairingCodeFormat
{
    /// <summary>ABCD-EFGH, easier to read aloud or type.</summary>
    public static string Pretty(string code) =>
        code.Length == 8 ? code[..4] + "-" + code[4..] : code;
}
