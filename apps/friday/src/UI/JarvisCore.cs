using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Windows.Forms;
using Friday.Core;

namespace Friday.UI;

/// <summary>
/// The living JARVIS core from the supplied reference: black field, a hot gold
/// centre, concentric rings, a spiked corona and drifting particles. Everything
/// on screen is driven by the real assistant state and the real microphone
/// level, never by an unrelated loop.
/// </summary>
public sealed class JarvisCore : Control
{
    private readonly StateHub _state;
    private readonly System.Windows.Forms.Timer _timer;
    private readonly Stopwatch _clock = Stopwatch.StartNew();
    private readonly List<Particle> _particles = new();
    private readonly Random _random = new(7);

    private Bitmap? _glowBitmap;
    private string _accent = "gold";
    private string _mode = "aurora";
    private float _smoothedAmplitude;
    private float _smoothedPulse = 0.5f;
    private double _lastTick;
    private int _fps = 60;
    private float _progress = -1f;
    private float _flash;

    public bool ShowParticles { get; set; } = true;
    public bool ReducedMotion { get; set; }

    public string Accent
    {
        get => _accent;
        set
        {
            if (_accent == value) return;
            _accent = value;
            RebuildGlow();
            Invalidate();
        }
    }

    public string VisualMode
    {
        get => _mode;
        set
        {
            _mode = string.IsNullOrWhiteSpace(value) ? "aurora" : value;
            RebuildGlow();
            Invalidate();
        }
    }

    public int TargetFps
    {
        get => _fps;
        set
        {
            _fps = Math.Clamp(value, 15, 120);
            _timer.Interval = Math.Max(8, 1000 / _fps);
        }
    }

    /// <summary>0..1 for a determinate executing ring, or -1 for indeterminate.</summary>
    public float Progress
    {
        get => _progress;
        set => _progress = value;
    }

    public event EventHandler? CoreClicked;

    public JarvisCore(StateHub state)
    {
        _state = state;
        SetStyle(ControlStyles.AllPaintingInWmPaint |
                 ControlStyles.UserPaint |
                 ControlStyles.OptimizedDoubleBuffer |
                 ControlStyles.ResizeRedraw, true);
        BackColor = Theme.Bg;
        TabStop = false;
        _timer = new System.Windows.Forms.Timer { Interval = 16 };
        _timer.Tick += (_, _) =>
        {
            Step();
            Invalidate();
        };
        _timer.Start();
        RebuildGlow();
        for (var i = 0; i < 110; i++) _particles.Add(NewParticle());
    }

    private Particle NewParticle()
    {
        var angle = (float)(_random.NextDouble() * Math.PI * 2);
        var radius = 0.55f + (float)_random.NextDouble() * 0.62f;
        var speed = 0.06f + (float)_random.NextDouble() * 0.30f;
        return new Particle
        {
            Angle = angle,
            Radius = radius,
            Speed = speed,
            Size = 0.6f + (float)_random.NextDouble() * 1.9f,
            Phase = (float)(_random.NextDouble() * Math.PI * 2),
            Depth = (float)_random.NextDouble(),
        };
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _timer.Stop();
            _timer.Dispose();
            _glowBitmap?.Dispose();
        }
        base.Dispose(disposing);
    }

    protected override void OnClick(EventArgs e)
    {
        CoreClicked?.Invoke(this, e);
        base.OnClick(e);
    }

    private void Step()
    {
        var now = _clock.Elapsed.TotalSeconds;
        var dt = (float)Math.Min(0.08, now - _lastTick);
        _lastTick = now;

        var target = _state.Amplitude;
        _smoothedAmplitude += (target - _smoothedAmplitude) * Math.Min(1f, dt * 7f);

        var state = _state.State;
        var pulseSpeed = state switch
        {
            FridayState.Listening => 1.9f,
            FridayState.Thinking => 2.4f,
            FridayState.Speaking => 5.2f,
            FridayState.Executing => 1.4f,
            FridayState.AwaitingApproval => 2.6f,
            _ => 0.55f,
        };

        _flash = Math.Max(0f, _flash - dt * 1.6f);
        if (state is FridayState.Success or FridayState.Error) _flash = 1f;

        var targetPulse = 0.5f + 0.5f * (float)Math.Sin(now * pulseSpeed);
        if (state == FridayState.Speaking)
        {
            var synthetic = 0.5f + 0.5f * (float)(Math.Sin(now * 11.0) * Math.Sin(now * 4.3));
            targetPulse = 0.35f + 0.65f * synthetic;
        }
        _smoothedPulse += (targetPulse - _smoothedPulse) * Math.Min(1f, dt * 9f);

        if (ShowParticles && !ReducedMotion)
        {
            for (var i = 0; i < _particles.Count; i++)
            {
                var p = _particles[i];
                p.Angle += p.Speed * dt * (state == FridayState.Thinking ? 1.9f : 1f);
                p.Phase += dt * 1.4f;
                if (p.Angle > Math.PI * 2) p.Angle -= (float)(Math.PI * 2);
                _particles[i] = p;
            }
        }
    }

    private void RebuildGlow()
    {
        _glowBitmap?.Dispose();
        _glowBitmap = BuildGlow(Theme.AccentHot(_accent), Theme.Accent(_accent));
    }

    private static Bitmap BuildGlow(Color hot, Color accent)
    {
        const int size = 256;
        var bitmap = new Bitmap(size, size, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
        using var g = Graphics.FromImage(bitmap);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.Clear(Color.Transparent);

        using (var path = new GraphicsPath())
        {
            path.AddEllipse(2, 2, size - 5, size - 5);
            using var brush = new PathGradientBrush(path)
            {
                CenterColor = Color.FromArgb(255, hot),
                SurroundColors = new[] { Color.FromArgb(0, accent) },
                CenterPoint = new PointF(size / 2f, size / 2f),
                FocusScales = new PointF(0.02f, 0.02f),
            };
            g.FillPath(brush, path);
        }

        using (var path = new GraphicsPath())
        {
            path.AddEllipse(size * 0.14f, size * 0.14f, size * 0.72f, size * 0.72f);
            using var brush = new PathGradientBrush(path)
            {
                CenterColor = Color.FromArgb(170, accent),
                SurroundColors = new[] { Color.FromArgb(0, accent) },
                CenterPoint = new PointF(size / 2f, size / 2f),
                FocusScales = new PointF(0.15f, 0.15f),
            };
            g.FillPath(brush, path);
        }

        return bitmap;
    }

    private struct Particle
    {
        public float Angle;
        public float Radius;
        public float Speed;
        public float Size;
        public float Phase;
        public float Depth;
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.InterpolationMode = InterpolationMode.HighQualityBicubic;
        g.PixelOffsetMode = PixelOffsetMode.HighQuality;
        g.Clear(Theme.Bg);

        var state = _state.State;
        var accent = state switch
        {
            FridayState.Error => Color.FromArgb(255, 90, 90),
            FridayState.Offline => Color.FromArgb(120, 130, 145),
            FridayState.AwaitingApproval => Color.FromArgb(255, 190, 90),
            FridayState.Success => Color.FromArgb(126, 232, 158),
            _ => Theme.Accent(_accent),
        };
        var hot = state switch
        {
            FridayState.Error => Color.FromArgb(255, 210, 205),
            FridayState.Offline => Color.FromArgb(200, 205, 212),
            FridayState.AwaitingApproval => Color.FromArgb(255, 240, 200),
            FridayState.Success => Color.FromArgb(226, 255, 236),
            _ => Theme.AccentHot(_accent),
        };

        var cx = Width / 2f;
        var cy = Height / 2f - Math.Min(Height * 0.02f, 14f);
        var baseR = Math.Min(Width, Height) * (Width < Height ? 0.455f : 0.455f);
        var now = (float)_clock.Elapsed.TotalSeconds;
        var amplitude = _smoothedAmplitude;
        var pulse = _smoothedPulse;

        // Faint background haze keeps the black field from looking flat.
        DrawHaze(g, cx, cy, baseR, accent, state);

        if (state == FridayState.Offline)
        {
            DrawOffline(g, cx, cy, baseR, now);
            DrawCaption(g, cx, cy, baseR, state, accent);
            return;
        }

        var rotation = ReducedMotion ? 0f : now * (state == FridayState.Thinking ? 0.55f : 0.18f);
        var counter = ReducedMotion ? 0f : -now * (state == FridayState.Thinking ? 0.9f : 0.11f);

        DrawCorona(g, cx, cy, baseR, now, accent, amplitude, rotation, state);
        DrawParticles(g, cx, cy, baseR, accent, state);
        DrawRings(g, cx, cy, baseR, now, accent, pulse, rotation, counter, state, amplitude);
        DrawCore(g, cx, cy, baseR, accent, hot, pulse, amplitude, now, state);
        DrawStateArcs(g, cx, cy, baseR, now, accent, state, amplitude);
        DrawCaption(g, cx, cy, baseR, state, accent);
    }

    private void DrawHaze(Graphics g, float cx, float cy, float baseR, Color accent, FridayState state)
    {
        var radius = baseR * 1.6f;
        using var path = new GraphicsPath();
        path.AddEllipse(cx - radius, cy - radius, radius * 2, radius * 2);
        using var brush = new PathGradientBrush(path)
        {
            CenterColor = Color.FromArgb(state == FridayState.Offline ? 4 : 16, accent),
            SurroundColors = new[] { Color.FromArgb(0, Color.Black) },
            CenterPoint = new PointF(cx, cy),
        };
        g.FillPath(brush, path);
    }

    private void DrawCorona(Graphics g, float cx, float cy, float baseR, float now,
        Color accent, float amplitude, float rotation, FridayState state)
    {
        var spikes = VisualMode == "minimal" ? 72 : 144;
        var inner = baseR * 0.90f;

        for (var i = 0; i < spikes; i++)
        {
            var fraction = (float)i / spikes;
            var angle = fraction * MathF.PI * 2 + rotation * 0.6f;
            var noise = Noise(i, now);
            var major = i % 12 == 0;
            var length = baseR * (0.035f + 0.115f * noise) * (major ? 1.55f : 1f);
            length *= 1f + amplitude * 0.55f;

            var alpha = (int)((major ? 120 : 45) + 90 * noise);
            if (VisualMode == "minimal") alpha /= 2;

            var x1 = cx + MathF.Cos(angle) * inner;
            var y1 = cy + MathF.Sin(angle) * inner;
            var x2 = cx + MathF.Cos(angle) * (inner + length);
            var y2 = cy + MathF.Sin(angle) * (inner + length);

            using var pen = new Pen(Color.FromArgb(Math.Clamp(alpha, 0, 255), accent), major ? 1.6f : 1f);
            g.DrawLine(pen, x1, y1, x2, y2);
        }

        // A soft outer halo made of short dashes.
        using var halo = new Pen(Color.FromArgb(30, accent), 6f);
        g.DrawEllipse(halo, cx - baseR * 1.01f, cy - baseR * 1.01f, baseR * 2.02f, baseR * 2.02f);
    }

    private void DrawRings(Graphics g, float cx, float cy, float baseR, float now, Color accent,
        float pulse, float rotation, float counter, FridayState state, float amplitude)
    {
        // Outer tick ring.
        var ticks = 180;
        for (var i = 0; i < ticks; i++)
        {
            var angle = (float)i / ticks * MathF.PI * 2 + rotation * 0.25f;
            var major = i % 15 == 0;
            var r1 = baseR * (major ? 0.955f : 0.972f);
            var r2 = baseR * (major ? 0.985f : 0.982f);
            var alpha = major ? 150 : 45;
            using var pen = new Pen(Color.FromArgb(alpha, accent), major ? 1.5f : 1f);
            g.DrawLine(pen,
                cx + MathF.Cos(angle) * r1, cy + MathF.Sin(angle) * r1,
                cx + MathF.Cos(angle) * r2, cy + MathF.Sin(angle) * r2);
        }

        // Main segmented ring.
        var ringR = baseR * 0.80f;
        using (var pen = new Pen(Color.FromArgb(120, accent), 1.1f))
        {
            pen.DashStyle = DashStyle.Custom;
            pen.DashPattern = new[] { 3.2f, 5.4f };
            g.DrawArc(pen, cx - ringR, cy - ringR, ringR * 2, ringR * 2,
                rotation * 57.3f % 360f, 360f);
        }

        using (var pen = new Pen(Color.FromArgb(70, accent), 0.9f))
        {
            pen.DashStyle = DashStyle.Custom;
            pen.DashPattern = new[] { 1f, 11f };
            g.DrawArc(pen, cx - ringR * 0.92f, cy - ringR * 0.92f,
                ringR * 1.84f, ringR * 1.84f, counter * 57.3f % 360f, 360f);
        }

        // Inner radial spokes, like the reference's fan of fine lines.
        var spokes = VisualMode == "minimal" ? 40 : 84;
        for (var i = 0; i < spokes; i++)
        {
            var angle = (float)i / spokes * MathF.PI * 2 + counter * 0.8f;
            var inner = baseR * (0.40f + 0.02f * pulse);
            var outer = baseR * (0.52f + 0.035f * amplitude * (float)Math.Sin(now * 2 + i));
            var alpha = 34 + (int)(34 * Math.Abs(Math.Sin(now * 1.5f + i * 0.4f)));
            using var pen = new Pen(Color.FromArgb(alpha, accent), 0.9f);
            g.DrawLine(pen,
                cx + MathF.Cos(angle) * inner, cy + MathF.Sin(angle) * inner,
                cx + MathF.Cos(angle) * outer, cy + MathF.Sin(angle) * outer);
        }

        if (state == FridayState.AwaitingApproval)
        {
            var r = baseR * (0.88f + 0.02f * pulse);
            using var pen = new Pen(Color.FromArgb(190, 255, 196, 92), 2f);
            pen.DashStyle = DashStyle.Dash;
            g.DrawEllipse(pen, cx - r, cy - r, r * 2, r * 2);
        }
    }

    private void DrawParticles(Graphics g, float cx, float cy, float baseR, Color accent, FridayState state)
    {
        if (!ShowParticles || ReducedMotion) return;
        var count = VisualMode == "minimal" ? 24 : _particles.Count;
        for (var i = 0; i < count; i++)
        {
            var p = _particles[i];
            var wobble = 0.012f * (float)Math.Sin(p.Phase);
            var radius = baseR * (p.Radius + wobble);
            var x = cx + MathF.Cos(p.Angle) * radius;
            var y = cy + MathF.Sin(p.Angle) * radius * 0.985f;
            var alpha = (int)(50 + 140 * p.Depth);
            var size = p.Size * (0.8f + _smoothedAmplitude * 1.1f);

            using var glow = new SolidBrush(Color.FromArgb(Math.Clamp(alpha / 3, 0, 255), accent));
            g.FillEllipse(glow, x - size * 2.4f, y - size * 2.4f, size * 4.8f, size * 4.8f);

            using var dot = new SolidBrush(Color.FromArgb(Math.Clamp(alpha, 0, 255), Theme.AccentHot(_accent)));
            g.FillEllipse(dot, x - size / 2f, y - size / 2f, size, size);
        }
    }

    private void DrawCore(Graphics g, float cx, float cy, float baseR, Color accent, Color hot,
        float pulse, float amplitude, float now, FridayState state)
    {
        var diameter = baseR * (1.28f + pulse * 0.10f + amplitude * 0.10f);
        if (_glowBitmap is not null)
        {
            var rect = new RectangleF(cx - diameter / 2f, cy - diameter / 2f, diameter, diameter);
            var attributes = new ImageAttributes();
            attributes.SetWrapMode(WrapMode.TileFlipXY);
            g.DrawImage(_glowBitmap, Rectangle.Round(rect), 0, 0, _glowBitmap.Width, _glowBitmap.Height,
                GraphicsUnit.Pixel, attributes);
            attributes.Dispose();
        }

        var coreR = baseR * (0.20f + 0.020f * pulse + amplitude * 0.035f);
        using (var path = new GraphicsPath())
        {
            path.AddEllipse(cx - coreR, cy - coreR, coreR * 2, coreR * 2);
            using var brush = new PathGradientBrush(path)
            {
                CenterColor = Color.FromArgb(255, Color.White),
                SurroundColors = new[] { Color.FromArgb(160, hot) },
                CenterPoint = new PointF(cx, cy),
                FocusScales = new PointF(0.25f, 0.25f),
            };
            g.FillPath(brush, path);
        }

        using (var flashBrush = new SolidBrush(Color.FromArgb((int)(120 * _flash), Color.White)))
        {
            g.FillEllipse(flashBrush, cx - coreR * 0.55f, cy - coreR * 0.55f, coreR * 1.1f, coreR * 1.1f);
        }

        // Iris blades rotating around the core.
        var blades = 6;
        for (var i = 0; i < blades; i++)
        {
            var angle = (float)i / blades * MathF.PI * 2 + now * (state == FridayState.Thinking ? 0.55f : 0.14f);
            var r1 = coreR * 1.25f;
            var r2 = coreR * (1.65f + 0.10f * pulse);
            using var pen = new Pen(Color.FromArgb(state == FridayState.Speaking ? 210 : 130, hot), 1.6f);
            g.DrawLine(pen,
                cx + MathF.Cos(angle) * r1, cy + MathF.Sin(angle) * r1,
                cx + MathF.Cos(angle + 0.35f) * r2, cy + MathF.Sin(angle + 0.35f) * r2);
        }

        if (state == FridayState.Speaking)
        {
            DrawWaveform(g, cx, cy, baseR, accent, now, amplitude);
        }
    }

    private void DrawWaveform(Graphics g, float cx, float cy, float baseR, Color accent, float now, float amplitude)
    {
        var points = 160;
        var radius = baseR * 0.70f;
        var path = new GraphicsPath();
        var first = true;
        for (var i = 0; i <= points; i++)
        {
            var angle = (float)i / points * MathF.PI * 2;
            var wave = (float)(Math.Sin(angle * 7 + now * 9) * Math.Sin(angle * 3 - now * 4));
            var r = radius + wave * baseR * (0.012f + amplitude * 0.06f);
            var x = cx + MathF.Cos(angle) * r;
            var y = cy + MathF.Sin(angle) * r;
            if (first) { path.AddLine(x, y, x, y); first = false; }
            else path.AddLine(x, y, x, y);
        }
        using var pen = new Pen(Color.FromArgb(180, accent), 1.4f);
        g.DrawPath(pen, path);
        path.Dispose();
    }

    private void DrawStateArcs(Graphics g, float cx, float cy, float baseR, float now, Color accent,
        FridayState state, float amplitude)
    {
        if (state == FridayState.Thinking)
        {
            var radius = baseR * 0.64f;
            for (var i = 0; i < 3; i++)
            {
                var start = (now * 260 + i * 120) % 360;
                using var pen = new Pen(Color.FromArgb(200 - i * 45, accent), 2.4f - i * 0.5f);
                pen.StartCap = LineCap.Round;
                pen.EndCap = LineCap.Round;
                g.DrawArc(pen, cx - radius, cy - radius, radius * 2, radius * 2, start, 52);
            }
        }
        else if (state == FridayState.Executing)
        {
            var radius = baseR * 0.88f;
            using var track = new Pen(Color.FromArgb(40, accent), 3f);
            g.DrawEllipse(track, cx - radius, cy - radius, radius * 2, radius * 2);

            using var pen = new Pen(Color.FromArgb(220, accent), 3.2f);
            pen.StartCap = LineCap.Round;
            pen.EndCap = LineCap.Round;
            if (_progress >= 0)
            {
                g.DrawArc(pen, cx - radius, cy - radius, radius * 2, radius * 2, -90, 360 * Math.Clamp(_progress, 0f, 1f));
            }
            else
            {
                g.DrawArc(pen, cx - radius, cy - radius, radius * 2, radius * 2, (now * 220) % 360, 70);
            }
        }
        else if (state == FridayState.Listening)
        {
            var radius = baseR * (0.58f + amplitude * 0.22f);
            using var pen = new Pen(Color.FromArgb((int)(70 + amplitude * 120), accent), 1.8f);
            g.DrawEllipse(pen, cx - radius, cy - radius, radius * 2, radius * 2);
        }
        else if (state == FridayState.Success || state == FridayState.Error)
        {
            var radius = baseR * (0.95f + (1f - _flash) * 0.15f);
            using var pen = new Pen(Color.FromArgb((int)(170 * _flash), accent), 2f);
            g.DrawEllipse(pen, cx - radius, cy - radius, radius * 2, radius * 2);
        }
    }

    private void DrawCaption(Graphics g, float cx, float cy, float baseR, FridayState state, Color accent)
    {
        var text = Theme.PrettyState(state);
        var detail = _state.Detail;
        using var font = Theme.Display(baseR * 0.075f + 8f);
        using var brush = new SolidBrush(Color.FromArgb(220, accent));
        var size = g.MeasureString(text, font);
        var y = cy + baseR * 1.20f;
        g.DrawString(text, font, brush, cx - size.Width / 2f, y);

        if (!string.IsNullOrWhiteSpace(detail))
        {
            using var small = Theme.Ui(9f);
            using var dim = new SolidBrush(Color.FromArgb(150, Theme.Dim));
            var clipped = detail.Length > 64 ? detail[..64] + "…" : detail;
            var detailSize = g.MeasureString(clipped, small);
            g.DrawString(clipped, small, dim, cx - detailSize.Width / 2f, y + size.Height + 2);
        }
    }

    private void DrawOffline(Graphics g, float cx, float cy, float baseR, float now)
    {
        using var pen = new Pen(Color.FromArgb(56, 64, 78), 1f);
        g.DrawEllipse(pen, cx - baseR * 0.78f, cy - baseR * 0.78f, baseR * 1.56f, baseR * 1.56f);
        using var dash = new Pen(Color.FromArgb(44, 52, 66), 1f) { DashStyle = DashStyle.Dash };
        g.DrawEllipse(dash, cx - baseR * 0.9f, cy - baseR * 0.9f, baseR * 1.8f, baseR * 1.8f);
        using var core = new SolidBrush(Color.FromArgb(90, 100, 116));
        g.FillEllipse(core, cx - baseR * 0.12f, cy - baseR * 0.12f, baseR * 0.24f, baseR * 0.24f);
    }

    private static float Noise(int i, float t)
    {
        var value = MathF.Sin(i * 12.9898f + t * 1.7f) * 43758.5453f;
        return value - MathF.Floor(value);
    }
}
