using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;
using Friday.Core;
using Friday.Net;
using Friday.Tools;
using TaskStatus = Friday.Core.TaskStatus;

namespace Friday.UI;

/// <summary>
/// The assistant surface: the animated core on the left and the real
/// conversation on the right. Nothing here invents state — every label, task row
/// and approval card is fed by <see cref="FridayRuntime"/>.
/// </summary>
public sealed class MainForm : Form
{
    private enum UiMode { Fullscreen, Windowed, Compact }

    private const int HotkeyPushToTalk = 0x4A01;
    private const int HotkeyToggle = 0x4A02;
    private const int HotkeyStop = 0x4A03;

    private const uint ModAlt = 0x0001;
    private const uint ModControl = 0x0002;
    private const uint ModNoRepeat = 0x4000;
    private const uint WmHotkey = 0x0312;
    private const int WmNclButtonDown = 0x00A1;
    private const int HtCaption = 0x0002;

    [DllImport("user32.dll")]
    private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint modifiers, uint key);

    [DllImport("user32.dll")]
    private static extern bool UnregisterHotKey(IntPtr hWnd, int id);

    [DllImport("user32.dll")]
    private static extern bool ReleaseCapture();

    [DllImport("user32.dll")]
    private static extern IntPtr SendMessage(IntPtr hWnd, int msg, IntPtr wParam, IntPtr lParam);

    private readonly FridayRuntime _runtime;
    private readonly bool _startCompact;

    private JarvisCore _core = new(new StateHub());
    private RichTextBox _conversation = new();
    private TextBox _input = new();
    private Panel _inputShell = new();
    private BufferedPanel _header = new();
    private BufferedPanel _status = new();
    private BufferedPanel _coreHost = new();
    private TableLayoutPanel _body = new();
    private TableLayoutPanel _side = new();
    private TableLayoutPanel _inputBar = new();
    private ListView _tasks = new();

    private Label _stateLabel = new();
    private Label _detailLabel = new();
    private Label _micLabel = new();
    private Label _aiLabel = new();
    private Label _serverLabel = new();
    private Label _relayLabel = new();
    private Label _title = new();
    private Label _subtitle = new();
    private Label _sideHeader = new();
    private Label _approvalTitle = new();
    private Label _approvalDetail = new();

    private FButton _send = new();
    private FButton _mic = new();
    private FButton _stop = new();
    private FButton _settings = new();
    private FButton _compact = new();
    private FButton _minimize = new();
    private FButton _close = new();
    private FButton _modeButton = new();
    private FButton _emergency = new();
    private FButton _approve = new();
    private FButton _deny = new();
    private FButton _clear = new();

    private BufferedPanel _approval = new();
    private NotifyIcon _tray = new();
    private ToolTip _tips = new();
    private System.Windows.Forms.Timer _refresh = new();
    private System.Windows.Forms.Timer _hud = new();

    private UiMode _mode = UiMode.Windowed;
    private bool _exiting;
    private bool _startHidden;
    private bool _loaded;
    private string _taskSignature = "";
    private string? _approvalId;
    private readonly List<string> _inputHistory = new();
    private int _historyIndex = -1;

    public MainForm(FridayRuntime runtime, bool startCompact)
    {
        _runtime = runtime;
        _startCompact = startCompact;

        SuspendLayout();
        BuildWindow();
        BuildHeader();
        BuildBody();
        BuildInputBar();
        BuildStatusBar();
        // The fill body must be docked *last*, which means it has to sit at the
        // front of the z-order. Otherwise it claims the whole client area and
        // the header and status bar overlap it.
        Controls.SetChildIndex(_body, 0);
        BuildTray();
        WireRuntime();
        ResumeLayout(performLayout: true);

        _refresh.Interval = 1200;
        _refresh.Tick += (_, _) =>
        {
            RefreshTasks();
            UpdateServer();
        };
        _refresh.Start();

        _hud.Interval = 400;
        _hud.Tick += (_, _) => UpdateHudClock();
        _hud.Start();

        FormClosing += OnFormClosing;
        Shown += OnShown;
        KeyDown += OnKeyDown;
        Resize += (_, _) => { if (_mode == UiMode.Compact) _compact.Left = Width - 96; };
        ApplyMode(startCompact ? UiMode.Compact : runtime.Config.Ui.StartFullscreen ? UiMode.Fullscreen : UiMode.Windowed);
        ApplyVisuals();
    }

    // ---------------------------------------------------------------- layout

    private void BuildWindow()
    {
        Text = "FRIDAY";
        BackColor = Theme.Bg;
        ForeColor = Theme.Text;
        Font = Theme.Ui(9.75f);
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(760, 540);
        ClientSize = new Size(1320, 840);
        DoubleBuffered = true;
        KeyPreview = true;
        Icon = Theme.CreateIcon(Theme.Gold);
        AutoScaleMode = AutoScaleMode.Dpi;
    }

    private void BuildHeader()
    {
        _header.Dock = DockStyle.Top;
        _header.Height = 72;
        _header.BackColor = Theme.Bg;
        _header.Paint += (_, e) =>
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            var y = _header.Height - 1;

            // A gold scan-line that fades out at both ends.
            var rect = new RectangleF(24, y - 1, Math.Max(1, _header.Width - 48), 2);
            using (var brush = new LinearGradientBrush(rect, Color.Transparent, Color.Transparent,
                       LinearGradientMode.Horizontal))
            {
                brush.InterpolationColors = new ColorBlend
                {
                    Colors = new[]
                    {
                        Color.FromArgb(0, Theme.Gold),
                        Color.FromArgb(120, Theme.Gold),
                        Color.FromArgb(0, Theme.Gold),
                    },
                    Positions = new[] { 0f, 0.55f, 1f },
                };
                g.FillRectangle(brush, rect);
            }

            // Fine ticks under the header, like a HUD frame.
            using var tick = new Pen(Color.FromArgb(26, Theme.Gold));
            for (var x = 24; x < _header.Width - 24; x += 14)
                g.DrawLine(tick, x, _header.Height - 4, x, _header.Height - 2);

            // Accent bar beside the wordmark.
            using var bar = new SolidBrush(Color.FromArgb(200, Theme.Gold));
            g.FillRectangle(bar, 16, 18, 2, _header.Height - 36);
        };
        _header.MouseDown += (_, e) =>
        {
            if (_mode == UiMode.Compact && e.Button == MouseButtons.Left)
                DragWindow();
        };

        _title = new Label
        {
            Text = "F R I D A Y",
            AutoSize = true,
            Font = Theme.Display(24f),
            ForeColor = Theme.Gold,
            BackColor = Color.Transparent,
            Location = new Point(26, 12),
        };
        _title.MouseDown += (_, e) => { if (_mode == UiMode.Compact) DragWindow(); };
        _subtitle = new Label
        {
            Text = "VOICE INTERFACE  ·  ONLINE  ·  " + Environment.MachineName.ToUpperInvariant(),
            AutoSize = true,
            Font = Theme.Ui(7.75f, FontStyle.Bold),
            ForeColor = Theme.Faint,
            BackColor = Color.Transparent,
            Location = new Point(220, 26),
        };

        _header.Controls.Add(_title);
        _header.Controls.Add(_subtitle);

        var right = new FlowLayoutPanel
        {
            Dock = DockStyle.Right,
            FlowDirection = FlowDirection.RightToLeft,
            WrapContents = false,
            AutoSize = true,
            Padding = new Padding(0, 18, 18, 0),
            BackColor = Color.Transparent,
        };

        _close = SmallButton("EXIT", 58, Color.FromArgb(220, 96, 96));
        _close.Click += (_, _) => { ExitApplication(); };
        _compact = SmallButton("COMPACT", 74, Theme.Gold);
        _compact.Click += (_, _) => ApplyMode(_mode == UiMode.Compact ? UiMode.Windowed : UiMode.Compact);
        _minimize = SmallButton("MIN", 48, Theme.Dim);
        _minimize.Click += (_, _) => WindowState = FormWindowState.Minimized;
        _settings = SmallButton("SETTINGS", 74, Theme.Gold);
        _settings.Click += (_, _) => OpenSettings();

        right.Controls.Add(_close);
        right.Controls.Add(_compact);
        right.Controls.Add(_settings);
        right.Controls.Add(_minimize);
        _header.Controls.Add(right);

        Controls.Add(_header);
    }

    private FButton SmallButton(string text, int width, Color accent)
    {
        return new FButton(text)
        {
            Width = width,
            Height = 30,
            Compact = true,
            Font = Theme.Ui(7.75f, FontStyle.Bold),
            AccentColor = accent,
            Margin = new Padding(6, 0, 0, 0),
        };
    }

    private void BuildBody()
    {
        _body.Dock = DockStyle.Fill;
        _body.ColumnCount = 2;
        _body.RowCount = 1;
        _body.BackColor = Theme.Bg;
        _body.ColumnStyles.Clear();
        _body.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 58f));
        _body.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 42f));
        _body.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));

        _coreHost.Dock = DockStyle.Fill;
        _coreHost.BackColor = Theme.Bg;
        _coreHost.Padding = new Padding(4);
        _coreHost.Margin = new Padding(0);

        _core = new JarvisCore(_runtime.State)
        {
            Dock = DockStyle.Fill,
            Accent = _runtime.Config.Ui.Accent,
            VisualMode = _runtime.Config.Ui.VisualMode,
            ShowParticles = _runtime.Config.Ui.ShowParticles,
            ReducedMotion = _runtime.Config.Ui.ReducedMotion,
            TargetFps = _runtime.Config.Ui.Fps,
        };
        _core.CoreClicked += (_, _) => ToggleVoice();
        _coreHost.Controls.Add(_core);
        _body.Controls.Add(_coreHost, 0, 0);

        BuildSide();
        _body.Controls.Add(_side, 1, 0);
        Controls.Add(_body);
    }

    private void BuildSide()
    {
        _side.Dock = DockStyle.Fill;
        _side.BackColor = Theme.Bg;
        _side.Padding = new Padding(10, 6, 16, 8);
        _side.ColumnCount = 1;
        _side.RowCount = 4;
        _side.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
        _side.RowStyles.Add(new RowStyle(SizeType.Absolute, 34f));
        _side.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));
        _side.RowStyles.Add(new RowStyle(SizeType.Absolute, 0f));
        _side.RowStyles.Add(new RowStyle(SizeType.Absolute, 168f));
        _side.Margin = new Padding(0);

        // Conversation header
        var header = new BufferedPanel { Dock = DockStyle.Fill, BackColor = Theme.Bg, Margin = new Padding(0) };
        _sideHeader = new Label
        {
            Text = "ACTIVITY",
            AutoSize = true,
            Font = Theme.Ui(7.75f, FontStyle.Bold),
            ForeColor = Theme.Faint,
            Location = new Point(4, 8),
            BackColor = Color.Transparent,
        };
        _clear = new FButton("CLEAR")
        {
            Width = 56,
            Height = 22,
            Compact = true,
            Font = Theme.Ui(7f, FontStyle.Bold),
            AccentColor = Theme.Dim,
            Anchor = AnchorStyles.Top | AnchorStyles.Right,
            Location = new Point(header.Width - 60, 3),
        };
        _clear.Click += (_, _) => { _conversation.Clear(); AppendSystem("Screen cleared. The transcript on disk is unchanged."); };
        header.Controls.Add(_sideHeader);
        header.Controls.Add(_clear);
        header.Resize += (_, _) => _clear.Left = Math.Max(4, header.Width - _clear.Width - 2);
        _side.Controls.Add(header, 0, 0);

        _conversation = new RichTextBox
        {
            Dock = DockStyle.Fill,
            BorderStyle = BorderStyle.None,
            BackColor = Theme.Panel,
            ForeColor = Theme.Text,
            Font = Theme.Ui(9.75f),
            ReadOnly = true,
            DetectUrls = false,
            HideSelection = false,
            ScrollBars = RichTextBoxScrollBars.None,
            WordWrap = true,
            Margin = new Padding(0, 0, 0, 10),
        };
        _side.Controls.Add(_conversation, 0, 1);

        BuildApprovalCard();
        _side.Controls.Add(_approval, 0, 2);

        _tasks = new ListView
        {
            Dock = DockStyle.Fill,
            View = View.Details,
            HeaderStyle = ColumnHeaderStyle.None,
            FullRowSelect = true,
            MultiSelect = false,
            HideSelection = false,
            BorderStyle = BorderStyle.None,
            BackColor = Theme.Bg,
            ForeColor = Theme.Text,
            Font = Theme.Mono(8.25f),
            Margin = new Padding(0),
            Scrollable = false,
            SmallImageList = null,
        };
        _tasks.Columns.Add("STATE", 86);
        _tasks.Columns.Add("TASK", 220);
        _tasks.Columns.Add("PROGRESS", 170);
        _tasks.DoubleClick += (_, _) => ShowSelectedTask();
        _side.Controls.Add(_tasks, 0, 3);
    }

    private void BuildApprovalCard()
    {
        _approval = new BufferedPanel
        {
            Dock = DockStyle.Fill,
            BackColor = Theme.PanelRaised,
            Margin = new Padding(0, 0, 0, 10),
            Padding = new Padding(14, 10, 14, 10),
        };
        _approval.Paint += (_, e) =>
        {
            var bounds = new RectangleF(0.5f, 0.5f, _approval.Width - 1.5f, _approval.Height - 1.5f);
            Draw2D.StrokeRounded(e.Graphics, bounds, 8f, Color.FromArgb(120, Theme.GoldDeep), 1.2f);
            using var brush = new SolidBrush(Theme.GoldDeep);
            e.Graphics.FillRectangle(brush, 1, 12, 3, _approval.Height - 24);
        };

        _approvalTitle = new Label
        {
            Text = "Permission required",
            AutoSize = true,
            Font = Theme.Ui(10.5f, FontStyle.Bold),
            ForeColor = Theme.GoldHot,
            BackColor = Color.Transparent,
            Location = new Point(16, 10),
        };
        _approvalDetail = new Label
        {
            AutoSize = false,
            Font = Theme.Ui(8.5f),
            ForeColor = Theme.Dim,
            BackColor = Color.Transparent,
            Location = new Point(16, 34),
            Height = 44,
            AutoEllipsis = true,
        };

        var actions = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.RightToLeft,
            WrapContents = false,
            AutoSize = true,
            BackColor = Color.Transparent,
            Anchor = AnchorStyles.Right | AnchorStyles.Bottom,
        };
        _approve = new FButton("APPROVE")
        {
            Width = 92,
            Height = 28,
            Primary = true,
            Compact = true,
            Font = Theme.Ui(7.75f, FontStyle.Bold),
            Margin = new Padding(6, 0, 0, 0),
        };
        _approve.Click += (_, _) => ResolveApproval(true);
        _deny = new FButton("DENY")
        {
            Width = 74,
            Height = 28,
            Compact = true,
            Font = Theme.Ui(7.75f, FontStyle.Bold),
            AccentColor = Color.FromArgb(220, 96, 96),
            Margin = new Padding(6, 0, 0, 0),
        };
        _deny.Click += (_, _) => ResolveApproval(false);
        actions.Controls.Add(_approve);
        actions.Controls.Add(_deny);

        _approval.Controls.Add(_approvalTitle);
        _approval.Controls.Add(_approvalDetail);
        _approval.Controls.Add(actions);
        _approval.Resize += (_, _) =>
        {
            _approvalDetail.Width = Math.Max(120, _approval.Width - 32);
            actions.Location = new Point(
                Math.Max(8, _approval.Width - actions.Width - 12),
                Math.Max(8, _approval.Height - actions.Height - 10));
        };
    }

    private void BuildInputBar()
    {
        _inputBar.Dock = DockStyle.Bottom;
        _inputBar.Height = 66;
        _inputBar.BackColor = Theme.Bg;
        _inputBar.ColumnCount = 4;
        _inputBar.RowCount = 1;
        _inputBar.Padding = new Padding(24, 8, 24, 12);
        _inputBar.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
        _inputBar.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 60f));
        _inputBar.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 82f));
        _inputBar.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 76f));
        _inputBar.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));
        _inputBar.Paint += (_, e) =>
        {
            using var pen = new Pen(Theme.Line);
            e.Graphics.DrawLine(pen, 24, 0, _inputBar.Width - 24, 0);
        };

        _inputShell = new Panel
        {
            Dock = DockStyle.Fill,
            BackColor = Theme.PanelRaised,
            Padding = new Padding(12, 9, 12, 8),
            Margin = new Padding(0, 0, 10, 0),
        };
        _inputShell.Paint += (_, e) =>
        {
            var bounds = new RectangleF(0.5f, 0.5f, _inputShell.Width - 1.5f, _inputShell.Height - 1.5f);
            Draw2D.StrokeRounded(e.Graphics, bounds, 6f, Theme.Line);
        };
        _input = new TextBox
        {
            Dock = DockStyle.Fill,
            BorderStyle = BorderStyle.None,
            BackColor = Theme.PanelRaised,
            ForeColor = Theme.Text,
            Font = Theme.Ui(11f),
            PlaceholderText = "Speak, or type — say “Friday, open Chrome”…",
        };
        _input.KeyDown += OnInputKeyDown;
        _inputShell.Controls.Add(_input);
        _inputBar.Controls.Add(_inputShell, 0, 0);

        _mic = new FButton("MIC") { Dock = DockStyle.Fill, AccentColor = Theme.Gold, Height = 40, Margin = new Padding(0, 0, 8, 0) };
        _mic.Click += (_, _) => ToggleVoice();
        _inputBar.Controls.Add(_mic, 1, 0);

        _send = new FButton("SEND") { Dock = DockStyle.Fill, Primary = true, Height = 40, Margin = new Padding(0, 0, 8, 0) };
        _send.Click += (_, _) => SendFromInput();
        _inputBar.Controls.Add(_send, 2, 0);

        _stop = new FButton("STOP") { Dock = DockStyle.Fill, Height = 40, AccentColor = Color.FromArgb(220, 96, 96) };
        _stop.Click += (_, _) => StopEverything();
        _inputBar.Controls.Add(_stop, 3, 0);

        Controls.Add(_inputBar);
    }

    private void BuildStatusBar()
    {
        _status.Dock = DockStyle.Bottom;
        _status.Height = 40;
        _status.BackColor = Color.FromArgb(6, 6, 8);
        _status.Paint += (_, e) =>
        {
            using var pen = new Pen(Theme.Line);
            e.Graphics.DrawLine(pen, 0, 0, _status.Width, 0);
        };

        var grid = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 8,
            RowCount = 1,
            Padding = new Padding(18, 0, 14, 0),
            BackColor = Color.Transparent,
        };
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 168f));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 104f));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 122f));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 175f));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 112f));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 92f));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 156f));
        grid.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));

        _stateLabel = StatusLabel("● IDLE", Theme.Text, FontStyle.Bold);
        _detailLabel = StatusLabel("Starting…", Theme.Faint);
        _micLabel = StatusLabel("○ MIC READY", Theme.Dim);
        _aiLabel = StatusLabel("AI", Theme.Dim);
        _serverLabel = StatusLabel("PHONE OFF", Theme.Dim);
        _relayLabel = StatusLabel("RELAY OFF", Theme.Faint);
        _modeButton = new FButton("AURORA")
        {
            Dock = DockStyle.Fill,
            Compact = true,
            Font = Theme.Ui(7f, FontStyle.Bold),
            AccentColor = Theme.Dim,
            Height = 26,
            Margin = new Padding(6, 6, 6, 6),
        };
        _modeButton.Click += (_, _) => CycleVisualMode();
        _emergency = new FButton("EMERGENCY STOP")
        {
            Dock = DockStyle.Fill,
            Compact = true,
            Font = Theme.Ui(7.25f, FontStyle.Bold),
            AccentColor = Color.FromArgb(220, 96, 96),
            Height = 26,
            Margin = new Padding(4, 6, 0, 6),
        };
        _emergency.Click += (_, _) => ToggleEmergency();

        grid.Controls.Add(_stateLabel, 0, 0);
        grid.Controls.Add(_detailLabel, 1, 0);
        grid.Controls.Add(_micLabel, 2, 0);
        grid.Controls.Add(_aiLabel, 3, 0);
        grid.Controls.Add(_serverLabel, 4, 0);
        grid.Controls.Add(_relayLabel, 5, 0);
        grid.Controls.Add(_modeButton, 6, 0);
        grid.Controls.Add(_emergency, 7, 0);
        _status.Controls.Add(grid);
        Controls.Add(_status);
    }

    private static Label StatusLabel(string text, Color color, FontStyle style = FontStyle.Regular) => new()
    {
        Text = text,
        Dock = DockStyle.Fill,
        TextAlign = ContentAlignment.MiddleLeft,
        AutoEllipsis = true,
        ForeColor = color,
        Font = Theme.Ui(7.75f, style),
        BackColor = Color.Transparent,
        Margin = new Padding(0),
    };

    private void BuildTray()
    {
        var menu = new ContextMenuStrip
        {
            BackColor = Theme.PanelRaised,
            ForeColor = Theme.Text,
            Renderer = new ToolStripProfessionalRenderer(new DarkColors()),
        };
        menu.Items.Add("Open Friday", null, (_, _) => RestoreFromTray());
        menu.Items.Add("Compact mode", null, (_, _) => { RestoreFromTray(); ApplyMode(UiMode.Compact); });
        menu.Items.Add("Push to talk", null, (_, _) => { RestoreFromTray(); PushToTalk(); });
        menu.Items.Add(new ToolStripSeparator());
        var emergencyItem = new ToolStripMenuItem("Emergency stop") { CheckOnClick = false };
        emergencyItem.Click += (_, _) => ToggleEmergency();
        menu.Items.Add(emergencyItem);
        menu.Items.Add("Settings", null, (_, _) => { RestoreFromTray(); OpenSettings(); });
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Exit", null, (_, _) => ExitApplication());

        _tray = new NotifyIcon
        {
            Icon = Theme.CreateIcon(Theme.Gold),
            Text = "Friday — local assistant",
            Visible = true,
            ContextMenuStrip = menu,
        };
        _tray.DoubleClick += (_, _) => RestoreFromTray();
        _tray.BalloonTipClicked += (_, _) => RestoreFromTray();
        _runtime.EmergencyStopChanged += (engaged, _) => UpdateTray(engaged);
    }

    private void UpdateTray(bool engaged)
    {
        _tray.Text = engaged ? "Friday — EMERGENCY STOP ENGAGED" : "Friday — local assistant";
        if (engaged)
            _tray.ShowBalloonTip(4000, "Friday stopped", "Emergency stop is engaged. Release it on the laptop to allow actions again.", ToolTipIcon.Warning);
    }

    // ---------------------------------------------------------------- wiring

    private void WireRuntime()
    {
        _runtime.State.Changed += (state, detail) => OnUi(() => UpdateState(state, detail));
        _runtime.Conversation.Added += entry => OnUi(() => AppendConversation(entry.Role, entry.Text));
        _runtime.Tasks.Changed += _ => OnUi(RefreshTasks);
        _runtime.Tasks.ApprovalRequested += pending => OnUi(() => ShowApproval(pending));
        _runtime.Tasks.ApprovalResolved += _ => OnUi(HideApproval);
        _runtime.Voice.Heard += text => OnUi(() =>
        {
            _core.Pulse();
            Submit(text, "voice");
        });
        _runtime.Voice.WakeWordHeard += _ => OnUi(() =>
        {
            // The engine has already opened the 30-second window and is still
            // listening. Do not switch to a one-shot capture here.
            _core.Pulse();
            try { System.Media.SystemSounds.Asterisk.Play(); } catch { }
            UpdateMic();
            _stateLabel.Text = "● LISTENING";
            _stateLabel.ForeColor = Theme.Gold;
        });
        _runtime.Voice.StatusChanged += message => OnUi(() =>
        {
            if (!string.IsNullOrWhiteSpace(message)) AppendSystem(message);
            UpdateMic();
        });
        _runtime.Server.StatusChanged += () => OnUi(UpdateServer);
        _runtime.EmergencyStopChanged += (engaged, _) => OnUi(() => UpdateEmergency(engaged));

        foreach (var entry in _runtime.Conversation.Recent(30))
            AppendConversation(entry.Role, entry.Text);
    }

    private void OnShown(object? sender, EventArgs e)
    {
        _loaded = true;
        Native.DarkScrollbars(_conversation.Handle);
        Native.DarkScrollbars(_tasks.Handle);
        Activate();
        BringToFront();
        UpdateState(_runtime.State.State, _runtime.State.Detail);
        UpdateMic();
        UpdateServer();
        UpdateEmergency(_runtime.EmergencyStopped);
        RefreshTasks();

        if (_startHidden || _runtime.Config.Startup.StartMinimized || (_runtime.Config.Ui.StartCompact && !_startCompact))
        {
            if (_runtime.Config.Ui.StartCompact && !_startCompact) ApplyMode(UiMode.Compact);
            if (_runtime.Config.Ui.MinimizeToTray) HideToTray(announce: true);
        }

        if (_runtime.Config.Voice.WakeWordEnabled && _runtime.Config.Voice.Enabled)
        {
            if (_runtime.Voice.StartListening(wakeMode: true))
                AppendSystem("Always listening. Say \"" + _runtime.Config.Voice.WakeWord +
                             "\" — after a command I stay hands-free for " +
                             _runtime.Config.Voice.FollowUpSeconds + " seconds of silence.");
        }

        if (!_runtime.Brain.AiConfigured)
            AppendSystem("No AI key yet. Laptop commands work offline; open Settings to add a Groq or OpenAI-compatible key.");

        if (!_runtime.Voice.Available && _runtime.Config.Voice.Enabled && _runtime.Voice.AvailabilityError.Length > 0)
            AppendSystem(_runtime.Voice.AvailabilityError);

        if (_runtime.Tasks.PendingApprovals().FirstOrDefault() is { } pending)
            ShowApproval(pending);
    }

    public void StartMinimizedToTray()
    {
        _startHidden = true;
    }

    // --------------------------------------------------------------- actions

    private void SendFromInput()
    {
        var text = _input.Text.Trim();
        if (text.Length == 0) return;
        _input.Clear();
        Submit(text, "desktop");
    }

    private void Submit(string text, string source)
    {
        text = (text ?? "").Trim();
        if (text.Length == 0) return;

        if (_runtime.EmergencyStopped)
        {
            AppendSystem("Emergency stop is engaged. Release it before asking me to do anything.");
            return;
        }

        _inputHistory.Add(text);
        if (_inputHistory.Count > 40) _inputHistory.RemoveAt(0);
        _historyIndex = _inputHistory.Count;

        AppendConversation("you", text);
        _runtime.Conversation.Add("you", text);
        try
        {
            _runtime.Submit(text, source);
        }
        catch (Exception ex)
        {
            AppendSystem("I could not queue that: " + ex.Message);
        }
    }

    private void ToggleVoice()
    {
        if (!_runtime.Config.Voice.Enabled)
        {
            AppendSystem("Voice is turned off in Settings.");
            return;
        }
        if (!_runtime.Voice.Available)
        {
            AppendSystem(_runtime.Voice.AvailabilityError.Length > 0
                ? _runtime.Voice.AvailabilityError
                : "Speech recognition is not available on this machine.");
            return;
        }

        if (_runtime.Voice.IsListening)
        {
            _runtime.Voice.StopListening();
            _core.FollowUpSeconds = 0;
        }
        else
        {
            // With the wake word on, the microphone stays open and waits for the
            // name. Without it, the button captures one phrase.
            if (_runtime.Config.Voice.WakeWordEnabled)
                _runtime.Voice.StartListening(wakeMode: true);
            else
                _runtime.Voice.ListenOnce();
            _core.Pulse();
        }
        UpdateMic();
    }

    /// <summary>Push-to-talk: one phrase, no wake word required.</summary>
    private void PushToTalk()
    {
        if (!_runtime.Config.Voice.Enabled)
        {
            AppendSystem("Voice is turned off in Settings.");
            return;
        }
        if (!_runtime.Voice.Available)
        {
            AppendSystem(_runtime.Voice.AvailabilityError.Length > 0
                ? _runtime.Voice.AvailabilityError
                : "Speech recognition is not available on this machine.");
            return;
        }

        _runtime.Voice.ListenOnce();
        _core.Pulse();
        UpdateMic();
    }

    private void StopEverything()
    {
        _runtime.Tasks.CancelAll("Stopped from the desktop.");
        _runtime.Voice.StopSpeaking();
        _runtime.Voice.StopListening();
        _runtime.State.Flash(FridayState.Success, "Stopped");
        AppendSystem("Stopped. Nothing else is running.");
        UpdateMic();
    }

    private void ToggleEmergency()
    {
        var engage = !_runtime.EmergencyStopped;
        _runtime.SetEmergencyStop(engage, "desktop");
        UpdateEmergency(engage);
        AppendSystem(engage
            ? "Emergency stop engaged. Every task was cancelled and new work is blocked."
            : "Emergency stop released. I can act again.");
    }

    private void ShowApproval(PendingApproval pending)
    {
        _approvalId = pending.Id;
        _approvalTitle.Text = (pending.Destructive ? "Confirm destructive action" : "Permission required")
                              + (pending.Source == "phone" ? "  ·  FROM PHONE" : "");
        _approvalDetail.Text = pending.Title + "\n" + pending.Detail;
        _side.RowStyles[2] = new RowStyle(SizeType.Absolute, 132f);
        _approval.Visible = true;
        _side.PerformLayout();

        if (WindowState == FormWindowState.Minimized || !Visible)
            _tray.ShowBalloonTip(4000, "Friday needs permission", pending.Title, ToolTipIcon.Info);

        _approvalDetail.Height = 52;
        _approvalTitle.ForeColor = pending.Destructive ? Color.FromArgb(255, 150, 130) : Theme.GoldHot;
    }

    private void HideApproval()
    {
        _approvalId = null;
        _approval.Visible = false;
        _side.RowStyles[2] = new RowStyle(SizeType.Absolute, 0f);
        _side.PerformLayout();
    }

    private void ResolveApproval(bool approved)
    {
        if (_approvalId is null) return;
        var id = _approvalId;
        var ok = _runtime.Tasks.ResolveApproval(id, approved, remember: false);
        _runtime.Audit("approval", "desktop", approved ? "approved" : "denied", id, ok);
        if (ok) AppendSystem(approved ? "Approved here." : "Denied here.");
        HideApproval();
    }

    private void ShowSelectedTask()
    {
        if (_tasks.SelectedItems.Count == 0) return;
        var index = _tasks.SelectedItems[0].Tag as string;
        var task = index is null ? null : _runtime.Tasks.Get(index);
        if (task is null) return;
        AppendConversation("friday", task.Summary.Length > 0 ? task.Summary : task.Progress);
    }

    private void OpenSettings()
    {
        using var dialog = new SettingsForm(_runtime);
        dialog.ShowDialog(this);
        dialog.ApplyToRuntime();
        ApplyVisuals();
        UpdateState(_runtime.State.State, _runtime.State.Detail);
        UpdateServer();

        // Turning the wake word on should take effect immediately, and turning
        // it off should release the microphone rather than leave it half-open.
        if (_runtime.Config.Voice.Enabled && _runtime.Config.Voice.WakeWordEnabled &&
            !_runtime.Voice.IsListening)
        {
            _runtime.Voice.StartListening(wakeMode: true);
        }
        else if ((!_runtime.Config.Voice.Enabled || !_runtime.Config.Voice.WakeWordEnabled) &&
                 _runtime.Voice.IsListening)
        {
            _runtime.Voice.StopListening();
        }

        UpdateMic();
    }

    private void ApplyVisuals()
    {
        var ui = _runtime.Config.Ui;
        _core.Accent = ui.Accent;
        _core.VisualMode = ui.VisualMode;
        _core.ShowParticles = ui.ShowParticles;
        _core.ReducedMotion = ui.ReducedMotion;
        _core.TargetFps = ui.Fps;
        _modeButton.Text = ui.VisualMode.ToUpperInvariant();
        _settings.AccentName = ui.Accent;
        _modeButton.AccentName = ui.Accent;
        _mic.AccentColor = Theme.Accent(ui.Accent);
        _input.Focus();
        if (_mode == UiMode.Windowed) TopMost = ui.AlwaysOnTop;
    }

    private void CycleVisualMode()
    {
        var modes = new[] { "aurora", "minimal" };
        var current = Array.IndexOf(modes, _runtime.Config.Ui.VisualMode.ToLowerInvariant());
        _runtime.Config.Ui.VisualMode = modes[(current + 1 + modes.Length) % modes.Length];
        _runtime.Config.Save();
        ApplyVisuals();
    }

    // ------------------------------------------------------------- rendering

    private void AppendConversation(string role, string text)
    {
        if (!_loaded) { /* still appended, conversation text box exists */ }
        var label = role switch
        {
            "you" => "YOU",
            "friday" => "FRIDAY",
            "action" => "ACTION",
            _ => "SYSTEM",
        };
        var color = role switch
        {
            "you" => Theme.Gold,
            "friday" => Theme.Text,
            "action" => Theme.Faint,
            _ => Color.FromArgb(150, 158, 170),
        };

        _conversation.SelectionStart = _conversation.TextLength;
        _conversation.SelectionLength = 0;
        _conversation.SelectionFont = Theme.Ui(7.25f, FontStyle.Bold);
        _conversation.SelectionColor = role switch
        {
            "you" => Theme.GoldDeep,
            "friday" => Theme.Gold,
            _ => Theme.Faint,
        };
        _conversation.AppendText(label + "\r\n");
        _conversation.SelectionFont = Theme.Ui(9.75f);
        _conversation.SelectionColor = color;
        _conversation.AppendText(text.Trim() + "\r\n\r\n");
        _conversation.SelectionColor = Theme.Text;

        if (_conversation.TextLength > 120_000)
        {
            var keep = _conversation.TextLength - 90_000;
            _conversation.Select(0, keep);
            _conversation.SelectedText = "";
        }

        _conversation.SelectionStart = _conversation.TextLength;
        _conversation.ScrollToCaret();
    }

    private void AppendSystem(string text)
    {
        if (string.IsNullOrWhiteSpace(text)) return;
        AppendConversation("system", text);
    }

    private void UpdateState(FridayState state, string detail)
    {
        _stateLabel.Text = "● " + Theme.PrettyState(state);
        _stateLabel.ForeColor = Theme.StateColor(state, _runtime.Config.Ui.Accent);
        _detailLabel.Text = detail;
        UpdateMic();
    }

    private void UpdateMic()
    {
        if (!_runtime.Voice.Available)
        {
            _micLabel.Text = _runtime.Config.Voice.Enabled ? "○ MIC N/A" : "○ MIC OFF";
            _micLabel.ForeColor = Theme.Faint;
            _mic.Text = "MIC";
            _mic.Primary = false;
            return;
        }

        var follow = _runtime.Voice.FollowUpRemaining;
        var listening = _runtime.Voice.IsListening;
        var wake = _runtime.Config.Voice.WakeWordEnabled;
        _core.FollowUpSeconds = follow;

        if (!listening)
        {
            _micLabel.Text = "○ MIC READY";
            _micLabel.ForeColor = Theme.Dim;
            _mic.Text = wake ? "LISTEN" : "MIC";
            _mic.Primary = false;
            _mic.AccentColor = Theme.Accent(_runtime.Config.Ui.Accent);
            return;
        }

        if (follow > 0)
        {
            _micLabel.Text = $"● MIC · {follow}s";
            _micLabel.ForeColor = Theme.Gold;
        }
        else if (wake)
        {
            var word = string.IsNullOrWhiteSpace(_runtime.Config.Voice.WakeWord)
                ? "FRIDAY"
                : _runtime.Config.Voice.WakeWord.ToUpperInvariant();
            _micLabel.Text = "● WAITING FOR " + word;
            _micLabel.ForeColor = Theme.Dim;
        }
        else
        {
            _micLabel.Text = "● MIC LIVE";
            _micLabel.ForeColor = Theme.Gold;
        }

        _mic.Text = wake ? "PAUSE" : "STOP";
        _mic.Primary = true;
        _mic.AccentColor = follow > 0 ? Theme.Gold : Theme.Accent(_runtime.Config.Ui.Accent);
    }

    private void UpdateHudClock()
    {
        _core.FollowUpSeconds = _runtime.Voice.FollowUpRemaining;
        UpdateMic();
        if (!_loaded) return;
        var state = _runtime.Voice.IsListening ? "LISTENING" : "STANDBY";
        _subtitle.Text = $"VOICE INTERFACE  ·  {state}  ·  {Environment.MachineName.ToUpperInvariant()}  ·  {DateTime.Now:HH:mm:ss}";
    }

    private void UpdateServer()
    {
        if (_runtime.Server.Running)
        {
            var url = _runtime.Server.Urls().FirstOrDefault() ?? "";
            _serverLabel.Text = "◉ PHONE  " + url.Replace("https://", "").Replace("http://", "");
            _serverLabel.ForeColor = Theme.Dim;
            _tips.SetToolTip(_serverLabel,
                "Pairing code: " + PairingCodeFormat.Pretty(_runtime.Pairing.CurrentCode) +
                "\nFingerprint: " + _runtime.Server.Fingerprint +
                "\nURLs: " + string.Join("  ", _runtime.Server.Urls()));
        }
        else
        {
            _serverLabel.Text = "○ PHONE OFF";
            _serverLabel.ForeColor = Theme.Faint;
            _tips.SetToolTip(_serverLabel, _runtime.Server.LastError);
        }

        _aiLabel.Text = _runtime.Brain.AiConfigured ? "AI READY" : "AI KEY NEEDED";
        _aiLabel.ForeColor = _runtime.Brain.AiConfigured ? Theme.Dim : Color.FromArgb(214, 150, 90);
        _aiLabel.ForeColor = _runtime.Brain.AiConfigured ? Theme.Dim : Color.FromArgb(214, 150, 90);

        var relay = _runtime.Relay;
        if (!_runtime.Config.Relay.Enabled)
        {
            _relayLabel.Text = "RELAY OFF";
            _relayLabel.ForeColor = Theme.Faint;
            _tips.SetToolTip(_relayLabel, "Internet relay is disabled in Settings.");
        }
        else if (relay.Running)
        {
            _relayLabel.Text = "● RELAY  " + relay.Forwarded;
            _relayLabel.ForeColor = Theme.Dim;
            _tips.SetToolTip(_relayLabel,
                relay.StatusText + "\nLaptop id: " + _runtime.Config.Relay.LaptopId);
        }
        else
        {
            _relayLabel.Text = "RELAY RETRY";
            _relayLabel.ForeColor = Color.FromArgb(214, 150, 90);
            _tips.SetToolTip(_relayLabel, relay.StatusText);
        }
    }

    private void UpdateEmergency(bool engaged)
    {
        _emergency.Text = engaged ? "RELEASE STOP" : "EMERGENCY STOP";
        _emergency.Primary = engaged;
        _emergency.AccentColor = engaged ? Color.FromArgb(255, 120, 110) : Color.FromArgb(220, 96, 96);
        _mic.Enabled = !engaged;
        _send.Enabled = !engaged;
        UpdateTray(engaged);
    }

    private void RefreshTasks()
    {
        var tasks = _runtime.Tasks.Recent(9);
        var signature = string.Join("|", tasks.Select(t =>
            t.Id + ":" + t.Status + ":" + t.Progress.Length + ":" + t.Summary.Length));
        if (signature == _taskSignature) return;
        _taskSignature = signature;

        var selected = _tasks.SelectedItems.Count > 0 ? _tasks.SelectedItems[0].Tag as string : null;
        _tasks.BeginUpdate();
        _tasks.Items.Clear();
        foreach (var task in tasks)
        {
            var item = new ListViewItem(StatusWord(task.Status))
            {
                ForeColor = TaskColor(task.Status),
                Tag = task.Id,
            };
            item.SubItems.Add(Clip(task.Instruction, 90));
            var detail = !string.IsNullOrWhiteSpace(task.Progress) ? task.Progress
                : !string.IsNullOrWhiteSpace(task.Summary) ? task.Summary
                : task.Error;
            item.SubItems.Add(Clip(detail, 70));
            _tasks.Items.Add(item);
            if (task.Id == selected) item.Selected = true;
        }
        _tasks.EndUpdate();

        var running = tasks.FirstOrDefault(t => !t.IsTerminal);
        _core.Progress = -1f;
        if (running is not null && running.Status == TaskStatus.Running)
            _core.Progress = -1f;

        _sideHeader.Text = tasks.Count == 0 ? "ACTIVITY" : $"ACTIVITY  ·  {tasks.Count(t => !t.IsTerminal)} ACTIVE";
    }

    private static string StatusWord(TaskStatus status) => status switch
    {
        TaskStatus.Received => "QUEUED",
        TaskStatus.Validating => "CHECK",
        TaskStatus.AwaitingApproval => "APPROVE",
        TaskStatus.Running => "RUNNING",
        TaskStatus.Succeeded => "DONE",
        TaskStatus.Failed => "FAILED",
        TaskStatus.Cancelled => "STOPPED",
        _ => "?",
    };

    private static Color TaskColor(TaskStatus status) => status switch
    {
        TaskStatus.Succeeded => Color.FromArgb(126, 210, 152),
        TaskStatus.Failed => Color.FromArgb(226, 118, 112),
        TaskStatus.Cancelled => Color.FromArgb(128, 128, 128),
        TaskStatus.AwaitingApproval => Color.FromArgb(240, 190, 100),
        TaskStatus.Running => Color.FromArgb(235, 200, 140),
        _ => Theme.Dim,
    };

    private static string Clip(string text, int max)
    {
        if (string.IsNullOrWhiteSpace(text)) return "";
        text = text.Replace("\r", " ").Replace("\n", " ").Trim();
        return text.Length <= max ? text : text[..max] + "…";
    }

    // ------------------------------------------------------------- windowing

    private void ApplyMode(UiMode mode)
    {
        _mode = mode;
        switch (mode)
        {
            case UiMode.Fullscreen:
                _side.Visible = true;
                _body.ColumnStyles[1] = new ColumnStyle(SizeType.Percent, 42f);
                FormBorderStyle = FormBorderStyle.None;
                WindowState = FormWindowState.Normal;
                var screen = Screen.FromControl(this);
                Bounds = screen.Bounds;
                // Cover the taskbar too: the assistant is meant to fill the
                // display. Escape drops back to a normal window.
                TopMost = true;
                break;

            case UiMode.Windowed:
                _side.Visible = true;
                _body.ColumnStyles[1] = new ColumnStyle(SizeType.Percent, 42f);
                FormBorderStyle = FormBorderStyle.Sizable;
                WindowState = FormWindowState.Normal;
                var area = Screen.FromControl(this).WorkingArea;
                Size = new Size(Math.Min(1320, area.Width - 80), Math.Min(840, area.Height - 80));
                CenterToScreen();
                TopMost = _runtime.Config.Ui.AlwaysOnTop;
                break;

            case UiMode.Compact:
                _side.Visible = false;
                _body.ColumnStyles[1] = new ColumnStyle(SizeType.Absolute, 0f);
                FormBorderStyle = FormBorderStyle.None;
                WindowState = FormWindowState.Normal;
                ClientSize = new Size(400, 560);
                TopMost = true;
                break;
        }

        _compact.Text = mode == UiMode.Compact ? "FULL" : "COMPACT";
        _subtitle.Visible = mode != UiMode.Compact;
        _title.Text = mode == UiMode.Compact ? "FRIDAY" : "F R I D A Y";
        _header.Height = mode == UiMode.Compact ? 56 : 72;
        _title.Location = new Point(26, mode == UiMode.Compact ? 10 : 12);
        _title.Font = Theme.Display(mode == UiMode.Compact ? 19f : 24f);
        _subtitle.Location = new Point(220, 26);
        _inputBar.Height = mode == UiMode.Compact ? 60 : 66;
        _inputBar.Padding = new Padding(mode == UiMode.Compact ? 12 : 24, 8,
            mode == UiMode.Compact ? 12 : 24, mode == UiMode.Compact ? 10 : 12);
        _input.Focus();
    }

    private void DragWindow()
    {
        if (_mode != UiMode.Compact) return;
        ReleaseCapture();
        SendMessage(Handle, WmNclButtonDown, HtCaption, IntPtr.Zero);
    }

    private void HideToTray(bool announce)
    {
        Hide();
        ShowInTaskbar = false;
        if (announce)
        {
            try
            {
                _tray.ShowBalloonTip(2500, "Friday is still running",
                    "The gold ring stays in the notification area. Double-click it to come back.",
                    ToolTipIcon.Info);
            }
            catch { }
        }
    }

    private void RestoreFromTray()
    {
        ShowInTaskbar = true;
        Show();
        WindowState = FormWindowState.Normal;
        Activate();
        BringToFront();
        if (_mode != UiMode.Fullscreen && _mode != UiMode.Compact)
        {
            _mode = UiMode.Windowed;
        }
        ApplyMode(_mode);
    }

    private void ExitApplication()
    {
        _exiting = true;
        _refresh.Stop();
        _hud.Stop();
        Close();
    }

    private void OnFormClosing(object? sender, FormClosingEventArgs e)
    {
        if (!_exiting && e.CloseReason == CloseReason.UserClosing && _runtime.Config.Ui.MinimizeToTray)
        {
            e.Cancel = true;
            HideToTray(announce: true);
            return;
        }

        try { _tray.Visible = false; _tray.Dispose(); } catch { }
        try { _hud.Stop(); _hud.Dispose(); } catch { }
        try { _runtime.Voice.StopListening(); } catch { }
    }

    // --------------------------------------------------------------- keyboard

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        RegisterHotKey(Handle, HotkeyPushToTalk, ModControl | ModAlt | ModNoRepeat, (uint)Keys.Space);
        RegisterHotKey(Handle, HotkeyToggle, ModControl | ModAlt | ModNoRepeat, (uint)Keys.F);
        RegisterHotKey(Handle, HotkeyStop, ModControl | ModAlt | ModNoRepeat, (uint)Keys.Escape);
    }

    protected override void OnHandleDestroyed(EventArgs e)
    {
        UnregisterHotKey(Handle, HotkeyPushToTalk);
        UnregisterHotKey(Handle, HotkeyToggle);
        UnregisterHotKey(Handle, HotkeyStop);
        base.OnHandleDestroyed(e);
    }

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == WmHotkey)
        {
            switch (m.WParam.ToInt32())
            {
                case HotkeyPushToTalk:
                    RestoreFromTray();
                    PushToTalk();
                    return;
                case HotkeyToggle:
                    if (Visible && WindowState != FormWindowState.Minimized) HideToTray(false);
                    else RestoreFromTray();
                    return;
                case HotkeyStop:
                    StopEverything();
                    return;
            }
        }
        base.WndProc(ref m);
    }

    private void OnInputKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.KeyCode == Keys.Enter && !e.Shift)
        {
            e.SuppressKeyPress = true;
            SendFromInput();
            return;
        }

        if (e.KeyCode == Keys.Up && _inputHistory.Count > 0)
        {
            _historyIndex = Math.Max(0, _historyIndex - 1);
            _input.Text = _inputHistory[Math.Min(_historyIndex, _inputHistory.Count - 1)];
            _input.SelectionStart = _input.TextLength;
            e.SuppressKeyPress = true;
        }
        else if (e.KeyCode == Keys.Down && _inputHistory.Count > 0)
        {
            _historyIndex = Math.Min(_inputHistory.Count, _historyIndex + 1);
            _input.Text = _historyIndex >= _inputHistory.Count ? "" : _inputHistory[_historyIndex];
            _input.SelectionStart = _input.TextLength;
            e.SuppressKeyPress = true;
        }
    }

    private void OnKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.KeyCode == Keys.Escape)
        {
            if (_mode == UiMode.Fullscreen)
            {
                ApplyMode(UiMode.Windowed);
                e.Handled = true;
            }
            else if (_mode == UiMode.Compact)
            {
                ApplyMode(UiMode.Windowed);
                e.Handled = true;
            }
            else
            {
                StopEverything();
                e.Handled = true;
            }
        }
        else if (e.Control && e.KeyCode == Keys.Oemcomma)
        {
            OpenSettings();
            e.Handled = true;
        }
        else if (e.Control && e.KeyCode == Keys.L)
        {
            _input.Focus();
            e.Handled = true;
        }
    }

    private void OnUi(Action action)
    {
        if (IsDisposed || Disposing) return;
        try
        {
            if (InvokeRequired) BeginInvoke(action);
            else action();
        }
        catch (ObjectDisposedException) { }
        catch (InvalidOperationException) { }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _refresh?.Dispose();
            _tips?.Dispose();
            _tray?.Dispose();
            _core?.Dispose();
            _conversation?.Dispose();
        }
        base.Dispose(disposing);
    }

    private sealed class DarkColors : ProfessionalColorTable
    {
        public override Color MenuItemSelected => Color.FromArgb(28, 26, 22);
        public override Color MenuItemBorder => Theme.GoldDeep;
        public override Color MenuBorder => Theme.Line;
        public override Color ToolStripDropDownBackground => Theme.PanelRaised;
        public override Color ImageMarginGradientBegin => Theme.PanelRaised;
        public override Color ImageMarginGradientMiddle => Theme.PanelRaised;
        public override Color ImageMarginGradientEnd => Theme.PanelRaised;
        public override Color SeparatorDark => Theme.Line;
        public override Color SeparatorLight => Theme.Line;
        public override Color MenuItemSelectedGradientBegin => Color.FromArgb(28, 26, 22);
        public override Color MenuItemSelectedGradientEnd => Color.FromArgb(28, 26, 22);
        public override Color MenuItemPressedGradientBegin => Color.FromArgb(34, 30, 24);
        public override Color MenuItemPressedGradientEnd => Color.FromArgb(34, 30, 24);
    }
}
