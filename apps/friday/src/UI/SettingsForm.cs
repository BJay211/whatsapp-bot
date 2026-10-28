using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using Friday.Core;
using Friday.Net;

namespace Friday.UI;

/// <summary>
/// Everything the user can change, edited on a deep copy so DISCARD really
/// discards. Only SAVE writes config.json and restarts the phone service.
/// </summary>
public sealed class SettingsForm : Form
{
    private const int WmNclButtonDown = 0x00A1;
    private const int HtCaption = 0x0002;

    [DllImport("user32.dll")]
    private static extern bool ReleaseCapture();

    [DllImport("user32.dll")]
    private static extern IntPtr SendMessage(IntPtr hWnd, int msg, IntPtr wParam, IntPtr lParam);

    private readonly FridayRuntime _runtime;
    private readonly FridayConfig _draft;
    private readonly (bool Enabled, int Port, bool Lan, bool Tls) _serverBefore;
    private readonly int _pairingMinutesBefore;

    private readonly Panel _host = new();
    private Label _statusLine = new();
    private readonly FlowLayoutPanel _nav = new();
    private readonly List<(string Key, FButton Button, Control Page)> _pages = new();
    private readonly System.Windows.Forms.Timer _toast = new();

    private TextBox _keyInput = new();
    private Label _keyStatus = new();
    private ComboBox _voiceCombo = new();
    private Label _pairingCode = new();
    private Label _pairingExpiry = new();
    private ListView _deviceList = new();
    private ListBox _rootList = new();
    private ListBox _memoryList = new();
    private TextBox _memoryInput = new();

    private bool _saved;

    public SettingsForm(FridayRuntime runtime)
    {
        _runtime = runtime;
        _draft = runtime.Config.Clone();
        _serverBefore = (runtime.Config.Server.Enabled, runtime.Config.Server.Port,
            runtime.Config.Server.AllowLan, runtime.Config.Server.UseTls);
        _pairingMinutesBefore = runtime.Config.Server.PairingCodeMinutes;

        SuspendLayout();
        BuildWindow();
        BuildNav();
        BuildPages();
        ResumeLayout(performLayout: true);

        _toast.Interval = 4200;
        _toast.Tick += (_, _) =>
        {
            _toast.Stop();
            _statusLine.Text = "";
        };

        ShowPage(0);
    }

    // ---------------------------------------------------------------- chrome

    private void BuildWindow()
    {
        Text = "Friday settings";
        BackColor = Theme.Bg;
        ForeColor = Theme.Text;
        Font = Theme.Ui(9.5f);
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.CenterParent;
        ClientSize = new Size(1000, 700);
        MinimumSize = new Size(880, 620);
        DoubleBuffered = true;
        KeyPreview = true;
        Icon = Theme.CreateIcon(Theme.Gold);
        AutoScaleMode = AutoScaleMode.Dpi;

        var edge = new BufferedPanel { Dock = DockStyle.Fill, BackColor = Theme.Bg };
        edge.Paint += (_, e) =>
        {
            using var pen = new Pen(Theme.Line);
            e.Graphics.DrawRectangle(pen, 0, 0, edge.Width - 1, edge.Height - 1);
        };
        Controls.Add(edge);

        var shell = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 3,
            BackColor = Theme.Bg,
        };
        shell.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 178f));
        shell.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
        shell.RowStyles.Add(new RowStyle(SizeType.Absolute, 62f));
        shell.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));
        shell.RowStyles.Add(new RowStyle(SizeType.Absolute, 62f));
        edge.Controls.Add(shell);

        var header = BuildHeaderPanel();
        shell.Controls.Add(header, 0, 0);
        shell.SetColumnSpan(header, 2);

        var navHost = new Panel { Dock = DockStyle.Fill, BackColor = Color.FromArgb(6, 6, 8) };
        _nav.Dock = DockStyle.Fill;
        _nav.FlowDirection = FlowDirection.TopDown;
        _nav.WrapContents = false;
        _nav.AutoScroll = true;
        _nav.Padding = new Padding(14, 8, 8, 8);
        _nav.BackColor = Color.Transparent;
        navHost.Controls.Add(_nav);
        shell.Controls.Add(navHost, 0, 1);

        _host.Dock = DockStyle.Fill;
        _host.BackColor = Theme.Bg;
        _host.Padding = new Padding(20, 8, 18, 8);
        shell.Controls.Add(_host, 1, 1);

        var footer = BuildFooterPanel();
        shell.Controls.Add(footer, 0, 2);
        shell.SetColumnSpan(footer, 2);
    }

    private Panel BuildHeaderPanel()
    {
        var header = new BufferedPanel { Dock = DockStyle.Fill, BackColor = Theme.Bg };
        header.Paint += (_, e) =>
        {
            using var pen = new Pen(Theme.Line);
            e.Graphics.DrawLine(pen, 20, header.Height - 1, header.Width - 20, header.Height - 1);
        };
        header.MouseDown += (_, e) => Drag(e);

        var title = new Label
        {
            Text = "SETTINGS",
            AutoSize = true,
            Font = Theme.Display(17f),
            ForeColor = Theme.Gold,
            BackColor = Color.Transparent,
            Location = new Point(22, 12),
        };
        title.MouseDown += (_, e) => Drag(e);

        var subtitle = new Label
        {
            Text = "Friday 1.0  ·  changes apply when you save",
            AutoSize = true,
            Font = Theme.Ui(7.75f),
            ForeColor = Theme.Faint,
            BackColor = Color.Transparent,
            Location = new Point(24, 39),
        };
        subtitle.MouseDown += (_, e) => Drag(e);

        var close = new FButton("CLOSE")
        {
            Width = 72,
            Height = 28,
            Compact = true,
            Font = Theme.Ui(7.5f, FontStyle.Bold),
            AccentColor = Theme.Dim,
            Anchor = AnchorStyles.Top | AnchorStyles.Right,
            Location = new Point(header.Width - 88, 17),
        };
        close.Click += (_, _) => Close();
        header.Resize += (_, _) => close.Left = header.Width - close.Width - 18;

        header.Controls.Add(title);
        header.Controls.Add(subtitle);
        header.Controls.Add(close);
        return header;
    }

    private Panel BuildFooterPanel()
    {
        var footer = new BufferedPanel { Dock = DockStyle.Fill, BackColor = Theme.Bg };
        footer.Paint += (_, e) =>
        {
            using var pen = new Pen(Theme.Line);
            e.Graphics.DrawLine(pen, 20, 0, footer.Width - 20, 0);
        };

        _statusLine = new Label
        {
            AutoSize = false,
            TextAlign = ContentAlignment.MiddleLeft,
            ForeColor = Theme.GoldHot,
            Font = Theme.Ui(8.5f),
            BackColor = Color.Transparent,
            Location = new Point(24, 18),
            Size = new Size(520, 28),
        };

        var save = new FButton("SAVE") { Width = 110, Height = 32, Primary = true, Anchor = AnchorStyles.Top | AnchorStyles.Right };
        save.Click += (_, _) => SaveSettings();
        var discard = new FButton("DISCARD") { Width = 100, Height = 32, AccentColor = Theme.Dim, Anchor = AnchorStyles.Top | AnchorStyles.Right };
        discard.Click += (_, _) => Close();

        footer.Resize += (_, _) =>
        {
            save.Location = new Point(footer.Width - save.Width - 20, 14);
            discard.Location = new Point(save.Left - discard.Width - 8, 14);
        };

        footer.Controls.Add(_statusLine);
        footer.Controls.Add(save);
        footer.Controls.Add(discard);
        return footer;
    }

    private void BuildNav()
    {
        var keys = new[] { "AI", "VOICE", "PHONE", "ACCESS", "APPEARANCE", "PRIVACY", "MEMORY", "ABOUT" };
        foreach (var key in keys)
        {
            var button = new FButton(key)
            {
                Width = 144,
                Height = 32,
                Compact = true,
                Font = Theme.Ui(7.5f, FontStyle.Bold),
                AccentColor = Theme.Dim,
                Margin = new Padding(0, 2, 0, 2),
            };
            var index = _pages.Count;
            button.Click += (_, _) => ShowPage(index);
            _nav.Controls.Add(button);
            _pages.Add((key, button, new Panel()));
        }
    }

    private void BuildPages()
    {
        var builders = new Func<Control>[]
        {
            BuildAiPage,
            BuildVoicePage,
            BuildPhonePage,
            BuildAccessPage,
            BuildLookPage,
            BuildPrivacyPage,
            BuildMemoryPage,
            BuildAboutPage,
        };

        for (var i = 0; i < builders.Length; i++)
        {
            var page = builders[i]();
            page.Dock = DockStyle.Fill;
            page.Visible = false;
            _host.Controls.Add(page);
            var (key, button, _) = _pages[i];
            _pages[i] = (key, button, page);
        }
    }

    private void ShowPage(int index)
    {
        for (var i = 0; i < _pages.Count; i++)
        {
            var (_, button, page) = _pages[i];
            page.Visible = i == index;
            button.Primary = i == index;
            button.Invalidate();
        }
    }

    private void Drag(MouseEventArgs e)
    {
        if (e.Button != MouseButtons.Left) return;
        ReleaseCapture();
        SendMessage(Handle, WmNclButtonDown, HtCaption, IntPtr.Zero);
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (e.KeyCode == Keys.Escape)
        {
            Close();
            e.Handled = true;
        }
        base.OnKeyDown(e);
    }

    // ----------------------------------------------------------------- pages

    private Control BuildAiPage()
    {
        var page = Page();
        var stack = Stack();
        page.Controls.Add(stack);

        stack.Controls.Add(Section("REASONING"));
        stack.Controls.Add(DarkCheck("Enable the AI brain (function calling)", _draft.Ai.Enabled, v => _draft.Ai.Enabled = v));
        stack.Controls.Add(Pair("Base URL", DarkText(_draft.Ai.BaseUrl), "OpenAI-compatible endpoint. Groq by default."));
        stack.Controls.Add(Pair("Model", DarkText(_draft.Ai.Model), "For example llama-3.3-70b-versatile, or gpt-4o-mini."));
        stack.Controls.Add(Pair("Temperature", Number(0m, 1.5m, (decimal)_draft.Ai.Temperature, 1,
            v => _draft.Ai.Temperature = (double)v)));
        stack.Controls.Add(Pair("Max reply tokens", Number(128, 8192, _draft.Ai.MaxTokens, 64,
            v => _draft.Ai.MaxTokens = (int)v)));
        stack.Controls.Add(Pair("Tool rounds", Number(1, 12, _draft.Ai.MaxToolRounds, 1,
            v => _draft.Ai.MaxToolRounds = (int)v),
            "How many tool steps Friday may take before it stops and reports."));

        stack.Controls.Add(Section("API KEY"));
        _keyStatus = new Label
        {
            AutoSize = true,
            MaximumSize = new Size(700, 0),
            ForeColor = Theme.Dim,
            Font = Theme.Ui(8.5f),
            BackColor = Color.Transparent,
            Margin = new Padding(0, 2, 0, 8),
        };
        stack.Controls.Add(_keyStatus);

        var keyRow = new TableLayoutPanel
        {
            ColumnCount = 3,
            Dock = DockStyle.Top,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            BackColor = Color.Transparent,
            Margin = new Padding(0, 0, 0, 6),
        };
        keyRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
        keyRow.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 108f));
        keyRow.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 108f));

        _keyInput = DarkText("");
        _keyInput.UseSystemPasswordChar = true;
        _keyInput.PlaceholderText = "Paste a key to store it (DPAPI-encrypted)";
        _keyInput.Dock = DockStyle.Fill;
        _keyInput.Margin = new Padding(0, 0, 8, 0);

        var store = new FButton("STORE KEY") { Dock = DockStyle.Fill, Height = 30, Primary = true, Compact = true, Font = Theme.Ui(7.25f, FontStyle.Bold) };
        store.Click += (_, _) => StoreKey();
        var remove = new FButton("REMOVE") { Dock = DockStyle.Fill, Height = 30, Compact = true, Font = Theme.Ui(7.25f, FontStyle.Bold), AccentColor = Color.FromArgb(210, 110, 100) };
        remove.Click += (_, _) => RemoveKey();

        keyRow.Controls.Add(_keyInput, 0, 0);
        keyRow.Controls.Add(store, 1, 0);
        keyRow.Controls.Add(remove, 2, 0);
        stack.Controls.Add(keyRow);

        stack.Controls.Add(Hint(
            "The environment variable FRIDAY_AI_KEY always wins over the stored key, so you can run Friday " +
            "without saving one here. The key is never written to logs, the audit file or the phone."));

        RefreshKeyStatus();
        return page;
    }

    private Control BuildVoicePage()
    {
        var page = Page();
        var stack = Stack();
        page.Controls.Add(stack);

        stack.Controls.Add(Section("SPEECH"));
        stack.Controls.Add(DarkCheck("Voice enabled", _draft.Voice.Enabled, v => _draft.Voice.Enabled = v));
        stack.Controls.Add(DarkCheck("Listen for a wake word continuously", _draft.Voice.WakeWordEnabled,
            v => _draft.Voice.WakeWordEnabled = v));
        stack.Controls.Add(Pair("Wake word", DarkText(_draft.Voice.WakeWord, v => _draft.Voice.WakeWord = v),
            "Say \"Friday, open Chrome\". Push-to-talk (Ctrl+Alt+Space) works even with this off."));
        stack.Controls.Add(DarkCheck("Keep the push-to-talk fallback", _draft.Voice.PushToTalk,
            v => _draft.Voice.PushToTalk = v));
        stack.Controls.Add(DarkCheck("Interrupt Friday when I start speaking", _draft.Voice.InterruptOnSpeech,
            v => _draft.Voice.InterruptOnSpeech = v));
        stack.Controls.Add(DarkCheck("Listen again after a reply", _draft.Voice.AutoListenAfterReply,
            v => _draft.Voice.AutoListenAfterReply = v));

        stack.Controls.Add(Section("VOICE OUTPUT"));
        var voices = _runtime.Voice.Voices().ToList();
        if (voices.Count == 0) voices.Add("(no Windows voices installed)");
        _voiceCombo = DarkCombo(voices.ToArray());
        var savedVoice = string.IsNullOrWhiteSpace(_draft.Voice.TtsVoice) ? voices[0] : _draft.Voice.TtsVoice;
        _voiceCombo.SelectedIndex = Math.Max(0, voices.IndexOf(savedVoice));
        _voiceCombo.SelectedIndexChanged += (_, _) =>
        {
            var chosen = _voiceCombo.Text;
            _draft.Voice.TtsVoice = chosen.StartsWith("(") ? "" : chosen;
        };
        stack.Controls.Add(Pair("Windows voice", _voiceCombo));

        stack.Controls.Add(Pair("Rate", Track(-10, 10, _draft.Voice.TtsRate, out var rateValue,
                v => _draft.Voice.TtsRate = v)));
        stack.Controls.Add(Pair("Volume", Track(0, 100, _draft.Voice.TtsVolume, out var volumeValue,
                v => _draft.Voice.TtsVolume = v)));

        var test = new FButton("TEST VOICE (saved settings)") { Width = 210, Height = 30, Compact = true, Margin = new Padding(0, 6, 0, 6) };
        test.Click += (_, _) => _runtime.Voice.Speak("Good evening. All systems are ready.");
        stack.Controls.Add(test);

        stack.Controls.Add(Hint(
            "Speech recognition uses the Windows recogniser for the default microphone; audio never leaves " +
            "this laptop. If no recogniser is installed, install one under Settings > Time & language > Speech. " +
            "System.Speech always uses the default output device, so switching speakers must be done in Windows."));

        stack.Controls.Add(Hint("Recognition: " +
            (_runtime.Voice.Available ? "available" : "not available — " + _runtime.Voice.AvailabilityError)));
        return page;
    }

    private Control BuildPhonePage()
    {
        var page = Page();
        var stack = Stack();
        page.Controls.Add(stack);

        stack.Controls.Add(Section("PHONE LINK"));
        stack.Controls.Add(DarkCheck("Run the phone service", _draft.Server.Enabled, v => _draft.Server.Enabled = v));
        stack.Controls.Add(Pair("Port", Number(1024, 65535, _draft.Server.Port, 1, v => _draft.Server.Port = (int)v)));
        stack.Controls.Add(DarkCheck("Accept connections from the local network", _draft.Server.AllowLan,
            v => _draft.Server.AllowLan = v));
        stack.Controls.Add(DarkCheck("Encrypt with TLS (recommended)", _draft.Server.UseTls, v => _draft.Server.UseTls = v));
        stack.Controls.Add(DarkCheck("Require a paired device", _draft.Server.RequirePairing, v => _draft.Server.RequirePairing = v));
        stack.Controls.Add(DarkCheck("Allow approvals from the phone", _draft.Server.AllowRemoteApproval,
            v => _draft.Server.AllowRemoteApproval = v));
        stack.Controls.Add(Pair("Pairing code lifetime (minutes)",
            Number(1, 60, _draft.Server.PairingCodeMinutes, 1, v => _draft.Server.PairingCodeMinutes = (int)v)));

        stack.Controls.Add(Section("PAIRING"));
        _pairingCode = new Label
        {
            AutoSize = true,
            Font = Theme.Display(24f),
            ForeColor = Theme.GoldHot,
            BackColor = Color.Transparent,
            Margin = new Padding(0, 2, 0, 0),
        };
        _pairingExpiry = new Label
        {
            AutoSize = true,
            Font = Theme.Ui(8f),
            ForeColor = Theme.Faint,
            BackColor = Color.Transparent,
            Margin = new Padding(0, 0, 0, 6),
        };
        stack.Controls.Add(_pairingCode);
        stack.Controls.Add(_pairingExpiry);

        var pairRow = new FlowLayoutPanel { AutoSize = true, WrapContents = false, BackColor = Color.Transparent, Margin = new Padding(0, 0, 0, 8) };
        var newCode = new FButton("NEW CODE") { Width = 110, Height = 30, Compact = true };
        newCode.Click += (_, _) =>
        {
            _runtime.Pairing.NewCode(_draft.Server.PairingCodeMinutes);
            RefreshPairing();
            Toast("New pairing code generated. The old one no longer works.");
        };
        var copyFp = new FButton("COPY FINGERPRINT") { Width = 146, Height = 30, Compact = true, Margin = new Padding(8, 0, 0, 0) };
        copyFp.Click += (_, _) =>
        {
            var text = _runtime.Server.Fingerprint;
            if (text.Length == 0)
            {
                Toast("The phone service has not started yet, so there is no fingerprint to copy.");
                return;
            }
            Clipboard.SetText(text);
            Toast("Fingerprint copied.");
        };
        pairRow.Controls.Add(newCode);
        pairRow.Controls.Add(copyFp);
        stack.Controls.Add(pairRow);

        var fingerprint = DarkText(_runtime.Server.Fingerprint);
        fingerprint.ReadOnly = true;
        fingerprint.Font = Theme.Mono(8.5f);
        stack.Controls.Add(Pair("TLS fingerprint", fingerprint,
            "The phone must show exactly this SHA-256 value after pairing. If it differs, do not continue."));

        var urls = new Label
        {
            AutoSize = true,
            MaximumSize = new Size(700, 0),
            ForeColor = Theme.Dim,
            Font = Theme.Mono(8.5f),
            BackColor = Color.Transparent,
            Margin = new Padding(0, 2, 0, 8),
        };
        urls.Text = "Addresses: " + (_runtime.Server.Running
            ? string.Join("   ", _runtime.Server.Urls())
            : _runtime.Server.LastError);
        stack.Controls.Add(urls);

        stack.Controls.Add(Section("PAIRED DEVICES"));
        _deviceList = new ListView
        {
            Height = 110,
            View = View.Details,
            FullRowSelect = true,
            MultiSelect = false,
            BorderStyle = BorderStyle.FixedSingle,
            BackColor = Theme.Panel,
            ForeColor = Theme.Text,
            Font = Theme.Ui(8.25f),
            Dock = DockStyle.Top,
        };
        _deviceList.Columns.Add("DEVICE", 220);
        _deviceList.Columns.Add("PLATFORM", 110);
        _deviceList.Columns.Add("PAIRED", 150);
        _deviceList.Columns.Add("LAST SEEN", 150);
        stack.Controls.Add(_deviceList);

        var revoke = new FButton("REVOKE SELECTED") { Width = 150, Height = 30, Compact = true, AccentColor = Color.FromArgb(210, 110, 100), Margin = new Padding(0, 6, 0, 4) };
        revoke.Click += (_, _) => RevokeSelected();
        stack.Controls.Add(revoke);

        stack.Controls.Add(Hint(
            "Pairing is a one-time code. Each phone gets its own random token, stored only as a SHA-256 hash " +
            "on this laptop. Revoking a device blocks it immediately. Off-LAN access needs a private tunnel " +
            "such as Tailscale; do not port-forward this service to the internet."));

        RefreshPairing();
        RefreshDevices();
        return page;
    }

    private Control BuildAccessPage()
    {
        var page = Page();
        var stack = Stack();
        page.Controls.Add(stack);

        stack.Controls.Add(Section("CONFIRMATION"));
        stack.Controls.Add(DarkCheck("Ask before every computer-changing action", _draft.Policy.StrictConfirm,
            v => _draft.Policy.StrictConfirm = v));
        stack.Controls.Add(DarkCheck("Auto-approve safe actions (reads, launches, media)", _draft.Policy.AutoApproveSafe,
            v => _draft.Policy.AutoApproveSafe = v));
        stack.Controls.Add(DarkCheck("Allow freeform shell commands (still confirmed every time)",
            _draft.Policy.AllowFreeformShell, v => _draft.Policy.AllowFreeformShell = v));
        stack.Controls.Add(DarkCheck("Allow mouse, keyboard and text input", _draft.Policy.AllowInputControl,
            v => _draft.Policy.AllowInputControl = v));
        stack.Controls.Add(DarkCheck("Allow screenshots", _draft.Policy.AllowScreenCapture,
            v => _draft.Policy.AllowScreenCapture = v));
        stack.Controls.Add(DarkCheck("Allow file writes and moves", _draft.Policy.AllowFileWrites,
            v => _draft.Policy.AllowFileWrites = v));
        stack.Controls.Add(DarkCheck("Allow deleting files", _draft.Policy.AllowDelete, v => _draft.Policy.AllowDelete = v));
        stack.Controls.Add(DarkCheck("Allow downloads", _draft.Policy.AllowDownloads, v => _draft.Policy.AllowDownloads = v));
        stack.Controls.Add(DarkCheck("Allow sleep, restart and shutdown", _draft.Policy.AllowPowerControls,
            v => _draft.Policy.AllowPowerControls = v));

        stack.Controls.Add(Section("LIMITS"));
        stack.Controls.Add(Pair("Command timeout (seconds)",
            Number(5, 3600, _draft.Policy.CommandTimeoutSeconds, 5, v => _draft.Policy.CommandTimeoutSeconds = (int)v)));
        stack.Controls.Add(Pair("Maximum task length (seconds)",
            Number(30, 86400, _draft.Policy.MaxTaskSeconds, 30, v => _draft.Policy.MaxTaskSeconds = (int)v)));
        stack.Controls.Add(Pair("Approval timeout (seconds)",
            Number(15, 1800, _draft.Policy.ApprovalTimeoutSeconds, 15, v => _draft.Policy.ApprovalTimeoutSeconds = (int)v)));

        stack.Controls.Add(Section("AUTHORISED FOLDERS"));
        _rootList = new ListBox
        {
            Height = 104,
            Dock = DockStyle.Top,
            BackColor = Theme.Panel,
            ForeColor = Theme.Text,
            BorderStyle = BorderStyle.FixedSingle,
            Font = Theme.Mono(8.25f),
            IntegralHeight = false,
        };
        foreach (var root in _draft.Policy.FileRoots) _rootList.Items.Add(root);
        stack.Controls.Add(_rootList);

        var rootRow = new FlowLayoutPanel { AutoSize = true, WrapContents = false, BackColor = Color.Transparent, Margin = new Padding(0, 6, 0, 10) };
        var addRoot = new FButton("ADD FOLDER") { Width = 112, Height = 30, Compact = true };
        addRoot.Click += (_, _) => AddRoot();
        var removeRoot = new FButton("REMOVE") { Width = 94, Height = 30, Compact = true, Margin = new Padding(8, 0, 0, 0) };
        removeRoot.Click += (_, _) =>
        {
            if (_rootList.SelectedIndex < 0) return;
            _draft.Policy.FileRoots.RemoveAt(_rootList.SelectedIndex);
            _rootList.Items.RemoveAt(_rootList.SelectedIndex);
        };
        rootRow.Controls.Add(addRoot);
        rootRow.Controls.Add(removeRoot);
        stack.Controls.Add(rootRow);

        stack.Controls.Add(Section("SHELL ALLOWLIST"));
        var allow = DarkMultiline(string.Join(Environment.NewLine, _draft.Policy.ShellAllowlist), 108);
        stack.Controls.Add(allow);
        stack.Controls.Add(Hint(
            "One pattern per line. * matches anything. Anything not listed is refused unless freeform shell " +
            "is enabled above, and even then it is confirmed on screen. Credential folders are always refused."));
        allow.TextChanged += (_, _) =>
        {
            _draft.Policy.ShellAllowlist = allow.Text.Split('\n')
                .Select(l => l.Trim()).Where(l => l.Length > 0).ToList();
        };

        stack.Controls.Add(Section("BLOCKED PATTERNS"));
        var blocked = DarkMultiline(string.Join(Environment.NewLine, _draft.Policy.BlockedPatterns), 84);
        stack.Controls.Add(blocked);
        stack.Controls.Add(Hint("Refused outright, whatever else is configured."));
        blocked.TextChanged += (_, _) =>
        {
            _draft.Policy.BlockedPatterns = blocked.Text.Split('\n')
                .Select(l => l.Trim()).Where(l => l.Length > 0).ToList();
        };

        return page;
    }

    private Control BuildLookPage()
    {
        var page = Page();
        var stack = Stack();
        page.Controls.Add(stack);

        stack.Controls.Add(Section("WINDOW"));
        stack.Controls.Add(DarkCheck("Start full screen", _draft.Ui.StartFullscreen, v => _draft.Ui.StartFullscreen = v));
        stack.Controls.Add(DarkCheck("Start in compact mode", _draft.Ui.StartCompact, v => _draft.Ui.StartCompact = v));
        stack.Controls.Add(DarkCheck("Always on top", _draft.Ui.AlwaysOnTop, v => _draft.Ui.AlwaysOnTop = v));
        stack.Controls.Add(DarkCheck("Keep running in the notification area when closed", _draft.Ui.MinimizeToTray,
            v => _draft.Ui.MinimizeToTray = v));
        stack.Controls.Add(DarkCheck("Start with Windows", StartupManager.IsEnabled(), v =>
        {
            _draft.Startup.LaunchAtLogin = v;
        }));
        _draft.Startup.LaunchAtLogin = StartupManager.IsEnabled();
        stack.Controls.Add(DarkCheck("Start hidden when launched at login", _draft.Startup.StartMinimized,
            v => _draft.Startup.StartMinimized = v));

        stack.Controls.Add(Section("CORE"));
        var accents = new[] { "gold", "ember", "ice", "jade" };
        var accentCombo = DarkCombo(accents.Select(a => a.ToUpperInvariant()).ToArray());
        accentCombo.SelectedIndex = Math.Max(0, Array.IndexOf(accents, _draft.Ui.Accent.ToLowerInvariant()));
        accentCombo.SelectedIndexChanged += (_, _) =>
        {
            if (accentCombo.SelectedIndex >= 0)
                _draft.Ui.Accent = accents[accentCombo.SelectedIndex];
        };
        stack.Controls.Add(Pair("Accent", accentCombo, "Gold is the reference look; ember, ice and jade are alternates."));

        var modes = new[] { "aurora", "minimal" };
        var modeCombo = DarkCombo(modes.Select(m => m.ToUpperInvariant()).ToArray());
        modeCombo.SelectedIndex = Math.Max(0, Array.IndexOf(modes, _draft.Ui.VisualMode.ToLowerInvariant()));
        modeCombo.SelectedIndexChanged += (_, _) =>
        {
            if (modeCombo.SelectedIndex >= 0)
                _draft.Ui.VisualMode = modes[modeCombo.SelectedIndex];
        };
        stack.Controls.Add(Pair("Visual mode", modeCombo, "Aurora draws the full corona; minimal halves the detail for slower machines."));

        stack.Controls.Add(Pair("Animation frames per second",
            Number(15, 120, _draft.Ui.Fps, 5, v => _draft.Ui.Fps = (int)v)));
        stack.Controls.Add(DarkCheck("Draw particles", _draft.Ui.ShowParticles, v => _draft.Ui.ShowParticles = v));
        stack.Controls.Add(DarkCheck("Reduce motion", _draft.Ui.ReducedMotion, v => _draft.Ui.ReducedMotion = v));

        stack.Controls.Add(Section("HOTKEYS"));
        stack.Controls.Add(Hint("Ctrl+Alt+Space  push to talk        Ctrl+Alt+F  show or hide"));
        stack.Controls.Add(Hint("Ctrl+Alt+Esc  stop everything        Esc  leave full screen / stop"));
        return page;
    }

    private Control BuildPrivacyPage()
    {
        var page = Page();
        var stack = Stack();
        page.Controls.Add(stack);

        stack.Controls.Add(Section("HISTORY"));
        stack.Controls.Add(DarkCheck("Keep a conversation transcript", _draft.Privacy.StoreConversation,
            v => _draft.Privacy.StoreConversation = v));
        stack.Controls.Add(DarkCheck("Keep an audit log of actions and decisions", _draft.Privacy.StoreAudit,
            v => _draft.Privacy.StoreAudit = v));
        stack.Controls.Add(DarkCheck("Log tool output in the audit entry", _draft.Privacy.LogToolOutput,
            v => _draft.Privacy.LogToolOutput = v));
        stack.Controls.Add(DarkCheck("Speak replies out loud", _draft.Privacy.SpeakReplies,
            v => _draft.Privacy.SpeakReplies = v));
        stack.Controls.Add(Pair("Keep records for (days)",
            Number(1, 3650, _draft.Privacy.RetentionDays, 30, v => _draft.Privacy.RetentionDays = (int)v)));

        stack.Controls.Add(Section("FILES"));
        stack.Controls.Add(Hint("Everything Friday writes lives in one folder. Nothing is installed elsewhere."));
        var buttons = new FlowLayoutPanel { AutoSize = true, WrapContents = false, BackColor = Color.Transparent, Margin = new Padding(0, 4, 0, 8) };
        buttons.Controls.Add(SmallLink("DATA FOLDER", () => OpenFolder(AppPaths.Home)));
        buttons.Controls.Add(SmallLink("AUDIT LOG", () => OpenFile(AppPaths.AuditFile)));
        buttons.Controls.Add(SmallLink("TRANSCRIPT", () => OpenFile(AppPaths.ConversationFile)));
        buttons.Controls.Add(SmallLink("RUNTIME LOG", () => OpenFile(AppPaths.RuntimeLog)));
        stack.Controls.Add(buttons);

        stack.Controls.Add(Hint("Runtime log"));
        var logs = DarkMultiline(string.Join(Environment.NewLine, FLog.RecentLines().TakeLast(22)), 170);
        logs.ReadOnly = true;
        logs.Font = Theme.Mono(7.75f);
        stack.Controls.Add(logs);

        stack.Controls.Add(Hint(
            "Friday does not record audio, does not take screenshots on its own, and sends nothing to the " +
            "phone except task status and approvals. The AI provider receives only the words you send it, the " +
            "approved memories and tool results — never your API key in a URL, and never the transcript file."));
        return page;
    }

    private Control BuildMemoryPage()
    {
        var page = Page();
        var stack = Stack();
        page.Controls.Add(stack);

        stack.Controls.Add(Section("LONG-TERM MEMORY"));
        _memoryList = new ListBox
        {
            Height = 190,
            Dock = DockStyle.Top,
            BackColor = Theme.Panel,
            ForeColor = Theme.Text,
            BorderStyle = BorderStyle.FixedSingle,
            Font = Theme.Ui(8.5f),
            IntegralHeight = false,
        };
        stack.Controls.Add(_memoryList);

        _memoryInput = DarkText("");
        _memoryInput.PlaceholderText = "A fact Friday may keep, e.g. \"my main project is Vendora\"";
        stack.Controls.Add(Pair("New memory", _memoryInput));

        var actions = new FlowLayoutPanel { AutoSize = true, WrapContents = false, BackColor = Color.Transparent, Margin = new Padding(0, 6, 0, 8) };
        var add = new FButton("ADD AS APPROVED") { Width = 148, Height = 30, Compact = true, Primary = true };
        add.Click += (_, _) => AddMemory();
        var approve = new FButton("APPROVE") { Width = 96, Height = 30, Compact = true, Margin = new Padding(8, 0, 0, 0) };
        approve.Click += (_, _) => ApproveMemory();
        var remove = new FButton("FORGET") { Width = 92, Height = 30, Compact = true, AccentColor = Color.FromArgb(210, 110, 100), Margin = new Padding(8, 0, 0, 0) };
        remove.Click += (_, _) => ForgetMemory();
        var refresh = new FButton("REFRESH") { Width = 92, Height = 30, Compact = true, Margin = new Padding(8, 0, 0, 0) };
        refresh.Click += (_, _) => RefreshMemory();
        actions.Controls.Add(add);
        actions.Controls.Add(approve);
        actions.Controls.Add(remove);
        actions.Controls.Add(refresh);
        stack.Controls.Add(actions);

        stack.Controls.Add(Hint(
            "Only approved memories are sent to the AI. Unapproved entries are marked (proposed). " +
            "Memory is stored in memory.json on this laptop and is never uploaded as a file."));
        stack.Controls.Add(Hint("Tip: you can also say \"remember that …\" or \"what do you remember about …\"."));

        RefreshMemory();
        return page;
    }

    private Control BuildAboutPage()
    {
        var page = Page();
        var stack = Stack();
        page.Controls.Add(stack);

        stack.Controls.Add(Section("FRIDAY"));
        var key = _runtime.Config.EffectiveApiKey(out var keySource);
        var info = new Label
        {
            AutoSize = true,
            MaximumSize = new Size(720, 0),
            ForeColor = Theme.Dim,
            Font = Theme.Ui(9f),
            BackColor = Color.Transparent,
            Margin = new Padding(0, 2, 0, 12),
        };
        info.Text =
            "Version 1.0.0 (protocol 1.0)\r\n" +
            "Machine: " + Environment.MachineName + "  ·  user " + Environment.UserName + "\r\n" +
            "Data folder: " + AppPaths.Home + "\r\n" +
            "AI: " + (string.IsNullOrWhiteSpace(key)
                ? "no key configured (offline laptop commands only)"
                : _runtime.Config.EffectiveModel() + " via " + _runtime.Config.EffectiveBaseUrl() + " — key from " + keySource) + "\r\n" +
            "Phone service: " + (_runtime.Server.Running
                ? "running on port " + _runtime.Server.Port
                : "stopped — " + _runtime.Server.LastError) + "\r\n" +
            "Speech recognition: " + (_runtime.Voice.Available ? "available" : "unavailable") +
            "  ·  speech output: " + (_runtime.Voice.TtsAvailable ? "available" : "unavailable");
        stack.Controls.Add(info);

        stack.Controls.Add(Section("WHAT FRIDAY WILL NOT DO"));
        var limits = new Label
        {
            AutoSize = true,
            MaximumSize = new Size(720, 0),
            ForeColor = Theme.Faint,
            Font = Theme.Ui(8.5f),
            BackColor = Color.Transparent,
            Margin = new Padding(0, 2, 0, 10),
        };
        limits.Text =
            "• Run without asking when an action is destructive or privileged\r\n" +
            "• Expose a shell to the network, or elevate itself to administrator\r\n" +
            "• Read credential stores, private keys or Windows system secrets\r\n" +
            "• Invent command output, file contents or test results\r\n" +
            "• Control the phone's own hardware — the Android app does that locally";
        stack.Controls.Add(limits);

        stack.Controls.Add(Section("EVIDENCE"));
        var evidence = new FlowLayoutPanel { AutoSize = true, WrapContents = false, BackColor = Color.Transparent, Margin = new Padding(0, 4, 0, 8) };
        evidence.Controls.Add(SmallLink("SELF-TEST REPORT", () => OpenFile(Path.Combine(AppPaths.LogsDir, "selftest.txt"))));
        evidence.Controls.Add(SmallLink("OPEN DATA FOLDER", () => OpenFolder(AppPaths.Home)));
        evidence.Controls.Add(SmallLink("OPEN LOG", () => OpenFile(AppPaths.RuntimeLog)));
        stack.Controls.Add(evidence);

        stack.Controls.Add(Hint(
            "Run \"friday.exe --selftest\" from a terminal to regenerate the self-test report. It exercises the " +
            "real policy, pairing, TLS identity, memory gate and task queue, and states honestly what it could not test."));
        return page;
    }

    // --------------------------------------------------------------- actions

    private void StoreKey()
    {
        var raw = _keyInput.Text.Trim();
        if (raw.Length < 8)
        {
            Toast("That does not look like an API key. Nothing was stored.");
            return;
        }

        try
        {
            _draft.Ai.ApiKeyProtected = FridayConfig.ProtectSecret(raw);
            _keyInput.Clear();
            RefreshKeyStatus();
            Toast("Key encrypted and staged. Press SAVE to keep it.");
        }
        catch (Exception ex)
        {
            Toast("Could not protect the key: " + ex.Message);
        }
    }

    private void RemoveKey()
    {
        if (!_draft.HasStoredKey)
        {
            Toast("There is no stored key to remove.");
            return;
        }
        _draft.Ai.ApiKeyProtected = "";
        RefreshKeyStatus();
        Toast("Stored key removed. Press SAVE to keep that change.");
    }

    private void RefreshKeyStatus()
    {
        var env = Environment.GetEnvironmentVariable("FRIDAY_AI_KEY");
        var stored = _draft.HasStoredKey;
        if (!string.IsNullOrWhiteSpace(env))
            _keyStatus.Text = "A key is set in FRIDAY_AI_KEY and takes priority over anything stored here.";
        else if (stored)
            _keyStatus.Text = "A key is stored, encrypted with Windows DPAPI for this user.";
        else
            _keyStatus.Text = "No key stored. Laptop commands still work; open questions need a key.";
        _keyStatus.ForeColor = stored || !string.IsNullOrWhiteSpace(env) ? Theme.Dim : Color.FromArgb(216, 160, 96);
    }

    private void RefreshPairing()
    {
        _pairingCode.Text = PairingCodeFormat.Pretty(_runtime.Pairing.CurrentCode);
        var left = _runtime.Pairing.CodeExpires - DateTimeOffset.Now;
        _pairingExpiry.Text = left > TimeSpan.Zero
            ? "Single use · expires in " + Math.Max(1, (int)left.TotalMinutes) + " min"
            : "Expired — generate a new code";
    }

    private void RefreshDevices()
    {
        _deviceList.Items.Clear();
        foreach (var device in _runtime.Pairing.Devices)
        {
            var item = new ListViewItem(device.Name + (device.Revoked ? "  (revoked)" : ""))
            {
                ForeColor = device.Revoked ? Theme.Faint : Theme.Text,
                Tag = device.Id,
            };
            item.SubItems.Add(device.Platform);
            item.SubItems.Add(device.CreatedAt.ToLocalTime().ToString("yyyy-MM-dd HH:mm"));
            item.SubItems.Add(device.LastSeenAt.ToLocalTime().ToString("yyyy-MM-dd HH:mm"));
            _deviceList.Items.Add(item);
        }
        if (_deviceList.Items.Count == 0)
            _deviceList.Items.Add(new ListViewItem("No phone paired yet") { ForeColor = Theme.Faint });
    }

    private void RevokeSelected()
    {
        if (_deviceList.SelectedItems.Count == 0 || _deviceList.SelectedItems[0].Tag is not string id)
        {
            Toast("Select a paired device first.");
            return;
        }

        if (MessageBox.Show(this, "Revoke this device?\n\nIt will have to pair again with a new code.",
                "Friday", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes)
            return;

        if (_runtime.Pairing.Revoke(id))
        {
            _runtime.Audit("device.revoke", "desktop", "revoked", id, true);
            Toast("Device revoked.");
        }
        else
        {
            Toast("That device could not be found.");
        }
        RefreshDevices();
    }

    private void AddRoot()
    {
        using var dialog = new FolderBrowserDialog
        {
            Description = "Choose a folder Friday may read and write",
            UseDescriptionForTitle = true,
            ShowNewFolderButton = false,
        };
        if (dialog.ShowDialog(this) != DialogResult.OK) return;

        if (_draft.Policy.FileRoots.Any(r => r.Equals(dialog.SelectedPath, StringComparison.OrdinalIgnoreCase)))
        {
            Toast("That folder is already authorised.");
            return;
        }

        _draft.Policy.FileRoots.Add(dialog.SelectedPath);
        _rootList.Items.Add(dialog.SelectedPath);
        Toast("Folder added. Press SAVE to keep it.");
    }

    private void RefreshMemory()
    {
        _memoryList.Items.Clear();
        foreach (var entry in _runtime.Memory.All.OrderByDescending(e => e.CreatedAt))
        {
            var marker = entry.Approved ? "" : "  (proposed)";
            _memoryList.Items.Add(new MemoryRow(entry.Id, entry.Text + marker));
        }
        if (_memoryList.Items.Count == 0)
            _memoryList.Items.Add(new MemoryRow("", "Nothing stored yet."));
    }

    private void AddMemory()
    {
        var text = _memoryInput.Text.Trim();
        if (text.Length == 0)
        {
            Toast("Type something first.");
            return;
        }
        var entry = _runtime.Memory.Add(text, approved: true, source: "desktop-settings");
        if (entry is null) Toast("That memory already exists.");
        else
        {
            _memoryInput.Clear();
            Toast("Memory added.");
        }
        RefreshMemory();
    }

    private void ApproveMemory()
    {
        if (_memoryList.SelectedItem is not MemoryRow row || row.Id.Length == 0) return;
        Toast(_runtime.Memory.Approve(row.Id) ? "Memory approved." : "That entry is gone.");
        RefreshMemory();
    }

    private void ForgetMemory()
    {
        if (_memoryList.SelectedItem is not MemoryRow row || row.Id.Length == 0) return;
        if (MessageBox.Show(this, "Forget this memory permanently?", "Friday",
                MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
        Toast(_runtime.Memory.Remove(row.Id) ? "Memory removed." : "That entry is gone.");
        RefreshMemory();
    }

    private void SaveSettings()
    {
        if (!Uri.TryCreate(_draft.Ai.BaseUrl, UriKind.Absolute, out var uri) ||
            uri.Scheme is not ("http" or "https"))
        {
            Toast("The AI base URL must start with http:// or https://");
            return;
        }

        if (_draft.Voice.WakeWord.Trim().Length == 0)
        {
            Toast("The wake word cannot be empty. Use \"friday\" if unsure.");
            return;
        }

        try
        {
            _runtime.Config.CopyFrom(_draft);
            _runtime.Config.Save();
            StartupManager.Apply(_runtime.Config.Startup.LaunchAtLogin);
            _runtime.Voice.ApplySettings();

            var after = _runtime.Config.Server;
            if (_serverBefore.Enabled != after.Enabled || _serverBefore.Port != after.Port ||
                _serverBefore.Lan != after.AllowLan || _serverBefore.Tls != after.UseTls)
            {
                _runtime.Server.Restart();
            }

            if (_pairingMinutesBefore != after.PairingCodeMinutes)
                _runtime.Pairing.NewCode(after.PairingCodeMinutes);

            _saved = true;
            Toast("Saved. Changes are live.");
        }
        catch (Exception ex)
        {
            Toast("Could not save: " + ex.Message);
        }
    }

    /// <summary>Called by MainForm after the dialog closes.</summary>
    public void ApplyToRuntime()
    {
        if (!_saved) return;
        _runtime.Voice.ApplySettings();
    }

    private void Toast(string message)
    {
        _statusLine.Text = message;
        _toast.Stop();
        _toast.Start();
    }

    private static void OpenFolder(string path)
    {
        try
        {
            Directory.CreateDirectory(path);
            Process.Start(new ProcessStartInfo("explorer.exe", "\"" + path + "\"") { UseShellExecute = true });
        }
        catch { }
    }

    private static void OpenFile(string path)
    {
        try
        {
            if (!File.Exists(path))
            {
                MessageBox.Show("That file does not exist yet:\n" + path, "Friday",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
        }
        catch { }
    }

    // --------------------------------------------------------------- helpers

    private static Panel Page()
    {
        return new BufferedPanel
        {
            Dock = DockStyle.Fill,
            BackColor = Theme.Bg,
            AutoScroll = true,
            Padding = new Padding(6, 4, 12, 12),
        };
    }

    private static TableLayoutPanel Stack()
    {
        var stack = new TableLayoutPanel
        {
            ColumnCount = 1,
            Dock = DockStyle.Top,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            BackColor = Color.Transparent,
            Margin = new Padding(0),
        };
        stack.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
        return stack;
    }

    private static Label Section(string text) => new()
    {
        Text = text,
        AutoSize = true,
        Font = Theme.Ui(7.5f, FontStyle.Bold),
        ForeColor = Theme.Faint,
        BackColor = Color.Transparent,
        Margin = new Padding(0, 16, 0, 6),
    };

    private static Label Hint(string text) => new()
    {
        Text = text,
        AutoSize = true,
        MaximumSize = new Size(700, 0),
        Font = Theme.Ui(8f),
        ForeColor = Theme.Faint,
        BackColor = Color.Transparent,
        Margin = new Padding(0, 2, 0, 8),
    };

    private static TableLayoutPanel Pair(string label, Control control, string? hint = null)
    {
        var row = new TableLayoutPanel
        {
            ColumnCount = 2,
            RowCount = 1,
            Dock = DockStyle.Top,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            BackColor = Color.Transparent,
            Margin = new Padding(0, 3, 0, 3),
        };
        row.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 208f));
        row.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));

        var caption = new Label
        {
            Text = label,
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleLeft,
            ForeColor = Theme.Dim,
            Font = Theme.Ui(8.75f),
            BackColor = Color.Transparent,
            AutoSize = false,
            Height = 30,
            Margin = new Padding(0),
        };
        row.Controls.Add(caption, 0, 0);

        if (hint is null)
        {
            control.Dock = DockStyle.Fill;
            control.Margin = new Padding(0);
            row.Controls.Add(control, 1, 0);
            return row;
        }

        var holder = new TableLayoutPanel
        {
            ColumnCount = 1,
            RowCount = 2,
            Dock = DockStyle.Top,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            BackColor = Color.Transparent,
            Margin = new Padding(0),
        };
        holder.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));

        control.Dock = DockStyle.Top;
        control.Margin = new Padding(0);

        var note = new Label
        {
            Text = hint,
            AutoSize = true,
            MaximumSize = new Size(700, 0),
            ForeColor = Theme.Faint,
            Font = Theme.Ui(7.75f),
            BackColor = Color.Transparent,
            Margin = new Padding(0, 2, 0, 4),
        };

        holder.Controls.Add(control, 0, 0);
        holder.Controls.Add(note, 0, 1);
        row.Controls.Add(holder, 1, 0);
        return row;
    }

    private static CheckBox DarkCheck(string text, bool value, Action<bool> onChanged)
    {
        var check = new CheckBox
        {
            Text = text,
            Checked = value,
            AutoSize = true,
            FlatStyle = FlatStyle.Flat,
            ForeColor = Theme.Text,
            BackColor = Color.Transparent,
            Font = Theme.Ui(8.75f),
            Margin = new Padding(0, 4, 0, 2),
        };
        check.CheckedChanged += (_, _) => onChanged(check.Checked);
        return check;
    }

    private static TextBox DarkText(string value, Action<string>? onChanged = null)
    {
        var box = new TextBox
        {
            Text = value,
            BorderStyle = BorderStyle.FixedSingle,
            BackColor = Theme.PanelRaised,
            ForeColor = Theme.Text,
            Font = Theme.Ui(9f),
            Height = 28,
        };
        if (onChanged is not null)
            box.TextChanged += (_, _) => onChanged(box.Text);
        return box;
    }

    private static TextBox DarkMultiline(string value, int height)
    {
        return new TextBox
        {
            Text = value,
            Multiline = true,
            Height = height,
            ScrollBars = ScrollBars.Vertical,
            BorderStyle = BorderStyle.FixedSingle,
            BackColor = Theme.Panel,
            ForeColor = Theme.Text,
            Font = Theme.Mono(8.25f),
            Dock = DockStyle.Top,
            Margin = new Padding(0, 2, 0, 4),
        };
    }

    private static ComboBox DarkCombo(string[] items)
    {
        var combo = new ComboBox
        {
            DropDownStyle = ComboBoxStyle.DropDownList,
            FlatStyle = FlatStyle.Flat,
            BackColor = Theme.PanelRaised,
            ForeColor = Theme.Text,
            Font = Theme.Ui(9f),
            Height = 28,
        };
        combo.Items.AddRange(items);
        combo.DrawMode = DrawMode.OwnerDrawFixed;
        combo.DrawItem += (_, e) =>
        {
            e.DrawBackground();
            var selected = (e.State & DrawItemState.Selected) == DrawItemState.Selected;
            using var back = new SolidBrush(selected ? Color.FromArgb(40, Theme.GoldDeep) : Theme.PanelRaised);
            e.Graphics.FillRectangle(back, e.Bounds);
            if (e.Index >= 0)
            {
                var text = combo.Items[e.Index]?.ToString() ?? "";
                TextRenderer.DrawText(e.Graphics, text, combo.Font, e.Bounds,
                    selected ? Theme.GoldHot : Theme.Text,
                    TextFormatFlags.Left | TextFormatFlags.VerticalCenter);
            }
        };
        return combo;
    }

    private static NumericUpDown Number(int min, int max, int value, int step, Action<decimal> onChanged)
    {
        var number = new NumericUpDown
        {
            Minimum = min,
            Maximum = max,
            Value = Math.Clamp(value, min, max),
            Increment = step,
            BackColor = Theme.PanelRaised,
            ForeColor = Theme.Text,
            BorderStyle = BorderStyle.FixedSingle,
            Font = Theme.Ui(9f),
            Height = 28,
        };
        number.ValueChanged += (_, _) => onChanged(number.Value);
        return number;
    }

    private static NumericUpDown Number(decimal min, decimal max, decimal value, int step, Action<decimal> onChanged)
    {
        var number = new NumericUpDown
        {
            Minimum = min,
            Maximum = max,
            Value = Math.Clamp(value, min, max),
            Increment = step,
            DecimalPlaces = 1,
            BackColor = Theme.PanelRaised,
            ForeColor = Theme.Text,
            BorderStyle = BorderStyle.FixedSingle,
            Font = Theme.Ui(9f),
            Height = 28,
        };
        number.ValueChanged += (_, _) => onChanged(number.Value);
        return number;
    }

    private static Control Track(int min, int max, int value, out Action<int> setter, Action<int> onChanged)
    {
        var slider = new TrackBar
        {
            Minimum = min,
            Maximum = max,
            Value = Math.Clamp(value, min, max),
            TickStyle = TickStyle.None,
            BackColor = Theme.Bg,
            Height = 30,
        };
        var caption = new Label
        {
            Text = slider.Value.ToString(),
            AutoSize = false,
            Width = 40,
            TextAlign = ContentAlignment.MiddleRight,
            ForeColor = Theme.Dim,
            Font = Theme.Mono(8f),
            BackColor = Color.Transparent,
        };

        var holder = new TableLayoutPanel
        {
            ColumnCount = 2,
            Dock = DockStyle.Top,
            Height = 32,
            BackColor = Color.Transparent,
            Margin = new Padding(0),
        };
        holder.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
        holder.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 42f));
        slider.Dock = DockStyle.Fill;
        caption.Dock = DockStyle.Fill;
        holder.Controls.Add(slider, 0, 0);
        holder.Controls.Add(caption, 1, 0);

        setter = v =>
        {
            slider.Value = Math.Clamp(v, min, max);
            caption.Text = slider.Value.ToString();
        };

        slider.ValueChanged += (_, _) =>
        {
            caption.Text = slider.Value.ToString();
            onChanged(slider.Value);
        };

        return holder;
    }

    private FButton SmallLink(string text, Action onClick)
    {
        var button = new FButton(text)
        {
            Width = 132,
            Height = 30,
            Compact = true,
            Font = Theme.Ui(7.25f, FontStyle.Bold),
            AccentColor = Theme.Dim,
            Margin = new Padding(0, 0, 8, 0),
        };
        button.Click += (_, _) => onClick();
        return button;
    }

    private sealed record MemoryRow(string Id, string Text)
    {
        public override string ToString() => Text;
    }
}
