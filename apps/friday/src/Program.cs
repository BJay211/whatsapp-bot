using System;
using System.Diagnostics;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;
using Friday.Core;
using Friday.Tools;
using Friday.UI;

namespace Friday;

internal static class Program
{
    private const string MutexName = @"Local\Friday.Assistant.SingleInstance";
    private static Mutex? _singleInstance;

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AttachConsole(int processId);

    [STAThread]
    private static int Main(string[] args)
    {
        // The network self-test must never read or write the real configuration,
        // device list or audit log, so it runs in a throw-away home directory.
        var netcheck = args.Any(a => a.Equals("--netcheck", StringComparison.OrdinalIgnoreCase));
        if (netcheck)
        {
            var temp = System.IO.Path.Combine(
                System.IO.Path.GetTempPath(), "friday-netcheck-" + Guid.NewGuid().ToString("n")[..8]);
            Environment.SetEnvironmentVariable("FRIDAY_HOME", temp);
        }

        AppPaths.Ensure();

        var selftest = args.Any(a => a.Equals("--selftest", StringComparison.OrdinalIgnoreCase));
        var background = args.Any(a => a.Equals("--background", StringComparison.OrdinalIgnoreCase));
        var compact = args.Any(a => a.Equals("--compact", StringComparison.OrdinalIgnoreCase));

        if (netcheck)
        {
            try { AttachConsole(-1); } catch { }
            return NetCheck.RunAsync().GetAwaiter().GetResult();
        }

        if (selftest)
        {
            // A WinExe has no console of its own; borrow the caller's so the
            // evidence run can be watched live as well as read from the log.
            try { AttachConsole(-1); } catch { }
            return SelfTest.RunAsync().GetAwaiter().GetResult();
        }

        _singleInstance = new Mutex(initiallyOwned: true, MutexName, out var isNew);
        if (!isNew)
        {
            // Bring the already-running window forward rather than starting a
            // second assistant with a second microphone and a second server.
            var existing = Native.FindWindow(null, "FRIDAY");
            if (existing != IntPtr.Zero)
            {
                Native.ShowWindow(existing, Native.SW_RESTORE);
                Native.SetForegroundWindow(existing);
            }
            else if (!background)
            {
                MessageBox.Show(
                    "Friday is already running. Look for the gold ring in the notification area.",
                    "Friday", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
            return 0;
        }

        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        try
        {
            var config = FridayConfig.Load();
            using var runtime = new FridayRuntime(config);
            runtime.Start();

            if (background)
                FLog.Info("app", "Started in the background from the startup entry.");

            using var form = new MainForm(runtime, startCompact: compact);
            if (background)
            {
                form.StartMinimizedToTray();
            }

            Application.Run(form);
            return 0;
        }
        catch (Exception ex)
        {
            FLog.Error("app", "Friday could not start: " + ex);
            MessageBox.Show(
                "Friday could not start.\n\n" + ex.Message +
                "\n\nDetails were written to:\n" + AppPaths.RuntimeLog,
                "Friday", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }
}
