using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Management;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Friday.Core;

namespace Friday.Tools;

public static class SystemTools
{
    private static ulong _lastIdle, _lastKernel, _lastUser;
    private static DateTime _lastCpuSample = DateTime.MinValue;
    private static double _lastCpu;

    public static IEnumerable<ToolDescriptor> Describe()
    {
        yield return new ToolDescriptor
        {
            Name = "system_status",
            Description = "Report battery, CPU, memory, disk, network and uptime for this laptop.",
            Capability = Capability.System,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""{"type":"object","properties":{},"additionalProperties":false}"""),
            Handler = (ctx, args) => Task.FromResult(ToolResult.Good(BuildStatus())),
        };
        yield return new ToolDescriptor
        {
            Name = "set_volume",
            Description = "Change the system volume: up, down, mute, or a level from 0 to 100 (approximate, in 2% steps).",
            Capability = Capability.System,
            Risk = RiskLevel.Confirm,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"action":{"type":"string","enum":["up","down","mute","level"]},"level":{"type":"integer"}},"required":["action"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(Volume(Args.Str(args, "action"), Args.Int(args, "level", 50))),
        };
        yield return new ToolDescriptor
        {
            Name = "set_brightness",
            Description = "Set the built-in display brightness to a percentage (0-100). Fails honestly on desktops that do not support it.",
            Capability = Capability.System,
            Risk = RiskLevel.Confirm,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"level":{"type":"integer"}},"required":["level"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(Brightness(Args.Int(args, "level", 50))),
        };
        yield return new ToolDescriptor
        {
            Name = "media_control",
            Description = "Control media playback: play_pause, next, previous, stop.",
            Capability = Capability.System,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"action":{"type":"string","enum":["play_pause","next","previous","stop"]}},"required":["action"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(Media(Args.Str(args, "action"))),
        };
        yield return new ToolDescriptor
        {
            Name = "lock_screen",
            Description = "Lock the Windows session immediately.",
            Capability = Capability.System,
            Risk = RiskLevel.Confirm,
            Parameters = ToolJson.Params("""{"type":"object","properties":{},"additionalProperties":false}"""),
            Handler = (ctx, args) =>
            {
                if (!ctx.Config.Policy.AllowPowerControls)
                    return Task.FromResult(ToolResult.Refused("Power controls are switched off."));
                return Task.FromResult(Native.LockWorkStation()
                    ? ToolResult.Good("Locked the laptop.")
                    : ToolResult.Fail("Windows refused the lock request."));
            },
        };
        yield return new ToolDescriptor
        {
            Name = "power_action",
            Description = "Sleep, restart, shutdown or cancel a pending shutdown. Always requires confirmation.",
            Capability = Capability.System,
            Risk = RiskLevel.Destructive,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"action":{"type":"string","enum":["sleep","restart","shutdown","cancel_shutdown"]},"delay_seconds":{"type":"integer"}},"required":["action"],"additionalProperties":false}
            """),
            Handler = (ctx, args) => Task.FromResult(Power(Args.Str(args, "action"), Args.Int(args, "delay_seconds", 30))),
        };
        yield return new ToolDescriptor
        {
            Name = "list_processes",
            Description = "List running processes with their memory use.",
            Capability = Capability.System,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""{"type":"object","properties":{"filter":{"type":"string"}},"additionalProperties":false}"""),
            Handler = (ctx, args) =>
            {
                var filter = Args.Str(args, "filter");
                var lines = Process.GetProcesses()
                    .Where(p => filter.Length == 0 || p.ProcessName.Contains(filter, StringComparison.OrdinalIgnoreCase))
                    .Select(p =>
                    {
                        long memory = 0;
                        try { memory = p.WorkingSet64; } catch { }
                        return new { p.ProcessName, p.Id, Memory = memory };
                    })
                    .OrderByDescending(p => p.Memory)
                    .Take(40)
                    .Select(p => $"{p.ProcessName,-28} pid {p.Id,-7} {p.Memory / 1024 / 1024} MB");
                return Task.FromResult(ToolResult.Good(string.Join(Environment.NewLine, lines)));
            },
        };
        yield return new ToolDescriptor
        {
            Name = "connected_devices",
            Description = "List connected USB and Bluetooth devices reported by Windows.",
            Capability = Capability.System,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""{"type":"object","properties":{},"additionalProperties":false}"""),
            Handler = (ctx, args) => Task.FromResult(Devices()),
        };
    }

    public static string BuildStatus()
    {
        var sb = new StringBuilder();
        sb.AppendLine($"Laptop: {Environment.MachineName}  ({Environment.UserName})");
        sb.AppendLine($"Windows: {Environment.OSVersion.VersionString}");
        sb.AppendLine($"Uptime: {TimeSpan.FromMilliseconds(Native.GetTickCount64()):d\\d\\ hh\\:mm}");

        try
        {
            var power = SystemInformation.PowerStatus;
            var percent = (int)Math.Round(power.BatteryLifePercent * 100);
            var state = power.PowerLineStatus == PowerLineStatus.Online ? "plugged in" : "on battery";
            sb.AppendLine(percent is >= 0 and <= 100
                ? $"Battery: {percent}% ({state})"
                : "Battery: not reported by this machine");
        }
        catch
        {
            sb.AppendLine("Battery: unavailable");
        }

        sb.AppendLine($"CPU: {CpuLoad():0.0}% used");
        sb.AppendLine($"Memory: {MemoryUsedPercent():0.0}% used ({AvailableMemoryMb()} MB free)");

        foreach (var drive in DriveInfo.GetDrives().Where(d => d.IsReady && d.DriveType == DriveType.Fixed))
        {
            try
            {
                var used = drive.TotalSize - drive.AvailableFreeSpace;
                sb.AppendLine($"Disk {drive.Name}: {used / 1024 / 1024 / 1024} GB used of {drive.TotalSize / 1024 / 1024 / 1024} GB");
            }
            catch { }
        }

        try
        {
            var active = NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up &&
                            n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
                .Select(n => $"{n.Name} ({n.NetworkInterfaceType})")
                .Take(4);
            sb.AppendLine("Network: " + string.Join(", ", active));
            foreach (var address in NetworkAddresses()) sb.AppendLine("  IPv4: " + address);
        }
        catch
        {
            sb.AppendLine("Network: unavailable");
        }

        return sb.ToString().Trim();
    }

    public static IEnumerable<string> NetworkAddresses()
    {
        var list = new List<string>();
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces()
                         .Where(n => n.OperationalStatus == OperationalStatus.Up &&
                                     n.NetworkInterfaceType != NetworkInterfaceType.Loopback))
            {
                foreach (var address in ni.GetIPProperties().UnicastAddresses)
                {
                    if (address.Address.AddressFamily == AddressFamily.InterNetwork &&
                        !System.Net.IPAddress.IsLoopback(address.Address))
                        list.Add(address.Address.ToString());
                }
            }
        }
        catch { }
        return list.Distinct().ToArray();
    }

    private static double CpuLoad()
    {
        try
        {
            var now = DateTime.UtcNow;
            if (_lastCpuSample != DateTime.MinValue && (now - _lastCpuSample).TotalMilliseconds < 200)
                return _lastCpu;

            if (!Native.GetSystemTimes(out var idle, out var kernel, out var user)) return 0;
            var idleDelta = idle.Value - _lastIdle;
            var kernelDelta = kernel.Value - _lastKernel;
            var userDelta = user.Value - _lastUser;
            _lastIdle = idle.Value;
            _lastKernel = kernel.Value;
            _lastUser = user.Value;
            _lastCpuSample = now;

            var total = kernelDelta + userDelta;
            if (total == 0) return _lastCpu;
            _lastCpu = Math.Clamp((total - idleDelta) * 100.0 / total, 0, 100);
            return _lastCpu;
        }
        catch
        {
            return 0;
        }
    }

    private static (ulong Total, ulong Available) Memory()
    {
        var status = new MEMORYSTATUSEX { dwLength = (uint)Marshal.SizeOf<MEMORYSTATUSEX>() };
        return GlobalMemoryStatusEx(ref status)
            ? (status.ullTotalPhys, status.ullAvailPhys)
            : (0, 0);
    }

    private static double MemoryUsedPercent()
    {
        var (total, available) = Memory();
        return total == 0 ? 0 : (total - available) * 100.0 / total;
    }

    private static ulong AvailableMemoryMb() => Memory().Available / 1024 / 1024;

    private static ToolResult Volume(string action, int level)
    {
        switch ((action ?? "").ToLowerInvariant())
        {
            case "mute":
                Tap(Native.VK_VOLUME_MUTE);
                return ToolResult.Good("Toggled mute.");
            case "up":
                for (var i = 0; i < 5; i++) Tap(Native.VK_VOLUME_UP);
                return ToolResult.Good("Turned the volume up.");
            case "down":
                for (var i = 0; i < 5; i++) Tap(Native.VK_VOLUME_DOWN);
                return ToolResult.Good("Turned the volume down.");
            case "level":
                level = Math.Clamp(level, 0, 100);
                // Windows has no public per-app volume setter without a third-party
                // library. Each media-key step is about 2%, so floor it and climb.
                for (var i = 0; i < 55; i++) Tap(Native.VK_VOLUME_DOWN);
                for (var i = 0; i < level / 2; i++) Tap(Native.VK_VOLUME_UP);
                return ToolResult.Good($"Set the volume to roughly {level}% (2% steps).");
            default:
                return ToolResult.Fail("Ask for up, down, mute or a level.");
        }
    }

    private static void Tap(byte key)
    {
        Native.keybd_event(key, 0, 0, UIntPtr.Zero);
        Native.keybd_event(key, 0, 2, UIntPtr.Zero);
        Thread.Sleep(12);
    }

    public static int? CurrentBrightness()
    {
        try
        {
            using var searcher = new ManagementObjectSearcher(
                new ManagementScope(@"\\.\root\wmi"),
                new ObjectQuery("SELECT CurrentBrightness FROM WmiMonitorBrightness"));
            foreach (ManagementObject monitor in searcher.Get())
            {
                using (monitor)
                {
                    var value = monitor["CurrentBrightness"];
                    if (value is not null) return Convert.ToInt32(value);
                }
            }
        }
        catch
        {
            // No WMI brightness support (common on desktops and external screens).
        }
        return null;
    }

    private static ToolResult Brightness(int level)
    {
        level = Math.Clamp(level, 0, 100);
        try
        {
            using var searcher = new ManagementObjectSearcher(
                new ManagementScope(@"\\.\root\wmi"),
                new ObjectQuery("SELECT * FROM WmiMonitorBrightnessMethods"));
            foreach (ManagementObject monitor in searcher.Get())
            {
                using (monitor)
                {
                    monitor.InvokeMethod("WmiSetBrightness",
                        new object[] { (uint)1, (byte)level });
                }
            }
            return ToolResult.Good($"Set the display brightness to {level}%.");
        }
        catch (Exception ex)
        {
            return ToolResult.Fail(
                "This display does not expose brightness control through Windows WMI " +
                $"(external monitors usually don't). {ex.Message}");
        }
    }

    private static ToolResult Media(string action)
    {
        var key = (action ?? "").ToLowerInvariant() switch
        {
            "play_pause" => Native.VK_MEDIA_PLAY_PAUSE,
            "next" => Native.VK_MEDIA_NEXT,
            "previous" => Native.VK_MEDIA_PREV,
            "stop" => Native.VK_MEDIA_STOP,
            _ => (byte)0,
        };
        if (key == 0) return ToolResult.Fail("Ask for play_pause, next, previous or stop.");
        Tap(key);
        return ToolResult.Good("Sent " + (action ?? "").Replace('_', ' ') + ".");
    }

    private static ToolResult Power(string action, int delaySeconds)
    {
        delaySeconds = Math.Clamp(delaySeconds, 5, 600);
        switch ((action ?? "").ToLowerInvariant())
        {
            case "sleep":
                if (!Native.SetSuspendState(false, false, false))
                    return ToolResult.Fail("Windows refused the sleep request.");
                return ToolResult.Good("Putting the laptop to sleep.");
            case "restart":
                RunShutdown($"/r /t {delaySeconds} /c \"Friday restart requested by the user\"");
                return ToolResult.Good($"Restart scheduled in {delaySeconds}s. Run \"cancel shutdown\" to stop it.");
            case "shutdown":
                RunShutdown($"/s /t {delaySeconds} /c \"Friday shutdown requested by the user\"");
                return ToolResult.Good($"Shutdown scheduled in {delaySeconds}s. Run \"cancel shutdown\" to stop it.");
            case "cancel_shutdown":
                RunShutdown("/a");
                return ToolResult.Good("Cancelled any pending shutdown.");
            default:
                return ToolResult.Fail("Ask for sleep, restart, shutdown or cancel_shutdown.");
        }
    }

    private static void RunShutdown(string arguments)
    {
        Process.Start(new ProcessStartInfo("shutdown.exe", arguments)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
        });
    }

    private static ToolResult Devices()
    {
        try
        {
            using var searcher = new ManagementObjectSearcher(
                "SELECT Name, PNPClass, Status FROM Win32_PnPEntity WHERE PNPClass='USB' OR PNPClass='Bluetooth'");
            var names = new List<string>();
            foreach (ManagementObject device in searcher.Get())
            {
                using (device)
                {
                    var name = device["Name"]?.ToString();
                    var status = device["Status"]?.ToString();
                    if (!string.IsNullOrWhiteSpace(name)) names.Add($"{name}  [{status}]");
                }
            }
            var unique = names.Distinct().OrderBy(n => n).Take(40).ToArray();
            return ToolResult.Good(unique.Length == 0
                ? "Windows reported no USB or Bluetooth devices."
                : string.Join(Environment.NewLine, unique));
        }
        catch (Exception ex)
        {
            return ToolResult.Fail("Could not query devices: " + ex.Message);
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MEMORYSTATUSEX
    {
        public uint dwLength;
        public uint dwMemoryLoad;
        public ulong ullTotalPhys;
        public ulong ullAvailPhys;
        public ulong ullTotalPageFile;
        public ulong ullAvailPageFile;
        public ulong ullTotalVirtual;
        public ulong ullAvailVirtual;
        public ulong ullAvailExtendedVirtual;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GlobalMemoryStatusEx(ref MEMORYSTATUSEX buffer);
}
