using System;
using System.IO;
using Microsoft.Win32;

namespace Friday.Core;

/// <summary>
/// Opt-in "start with Windows". It writes one HKCU Run value pointing at this
/// executable; nothing is installed as a service and nothing runs elevated.
/// </summary>
public static class StartupManager
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "FridayAssistant";

    public static bool IsEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: false);
            return key?.GetValue(ValueName) is string value && value.Length > 0;
        }
        catch
        {
            return false;
        }
    }

    public static bool Apply(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey, writable: true);
            if (key is null) return false;

            if (enabled)
            {
                var exe = Environment.ProcessPath;
                if (string.IsNullOrWhiteSpace(exe) || !File.Exists(exe)) return false;
                key.SetValue(ValueName, "\"" + exe + "\" --background");
            }
            else
            {
                key.DeleteValue(ValueName, throwOnMissingValue: false);
            }
            return true;
        }
        catch (Exception ex)
        {
            FLog.Warn("startup", "Could not change the startup entry: " + ex.Message);
            return false;
        }
    }
}
