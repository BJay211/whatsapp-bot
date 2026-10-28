using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace Friday.UI;

/// <summary>A panel that paints itself without flicker, used for the custom cards.</summary>
public class BufferedPanel : Panel
{
    public BufferedPanel()
    {
        SetStyle(ControlStyles.AllPaintingInWmPaint |
                 ControlStyles.UserPaint |
                 ControlStyles.OptimizedDoubleBuffer |
                 ControlStyles.ResizeRedraw, true);
        DoubleBuffered = true;
    }
}

internal static class Draw2D
{
    public static GraphicsPath Rounded(RectangleF bounds, float radius)
    {
        var path = new GraphicsPath();
        var d = Math.Max(2f, radius * 2);
        if (radius <= 0.5f)
        {
            path.AddRectangle(bounds);
            return path;
        }

        path.AddArc(bounds.Left, bounds.Top, d, d, 180, 90);
        path.AddArc(bounds.Right - d, bounds.Top, d, d, 270, 90);
        path.AddArc(bounds.Right - d, bounds.Bottom - d, d, d, 0, 90);
        path.AddArc(bounds.Left, bounds.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }

    public static void FillRounded(Graphics g, RectangleF bounds, float radius, Color fill)
    {
        using var path = Rounded(bounds, radius);
        using var brush = new SolidBrush(fill);
        g.FillPath(brush, path);
    }

    public static void StrokeRounded(Graphics g, RectangleF bounds, float radius, Color stroke, float width = 1f)
    {
        using var path = Rounded(bounds, radius);
        using var pen = new Pen(stroke, width);
        g.DrawPath(pen, path);
    }
}

/// <summary>
/// A flat, rounded button painted to match the JARVIS surface. WinForms' stock
/// button refuses to be fully dark, so the few buttons Friday needs are drawn.
/// </summary>
public sealed class FButton : Control
{
    private bool _hover;
    private bool _down;

    public Color AccentColor { get; set; } = Theme.Gold;
    public bool Primary { get; set; }
    public bool Compact { get; set; }

    public FButton(string text = "")
    {
        Text = text;
        SetStyle(ControlStyles.AllPaintingInWmPaint |
                 ControlStyles.UserPaint |
                 ControlStyles.OptimizedDoubleBuffer |
                 ControlStyles.ResizeRedraw |
                 ControlStyles.SupportsTransparentBackColor, true);
        BackColor = Color.Transparent;
        ForeColor = Theme.Text;
        Font = Theme.Ui(9.5f, FontStyle.Bold);
        Cursor = Cursors.Hand;
        TabStop = true;
        Height = 32;
    }

    protected override void OnMouseEnter(EventArgs e)
    {
        _hover = true;
        Invalidate();
        base.OnMouseEnter(e);
    }

    protected override void OnMouseLeave(EventArgs e)
    {
        _hover = false;
        _down = false;
        Invalidate();
        base.OnMouseLeave(e);
    }

    protected override void OnMouseDown(MouseEventArgs e)
    {
        _down = true;
        Focus();
        Invalidate();
        base.OnMouseDown(e);
    }

    protected override void OnMouseUp(MouseEventArgs e)
    {
        _down = false;
        Invalidate();
        base.OnMouseUp(e);
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (e.KeyCode is Keys.Space or Keys.Enter)
        {
            OnClick(EventArgs.Empty);
            e.Handled = true;
        }
        base.OnKeyDown(e);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;

        var accent = Enabled ? AccentColor : Theme.Faint;
        var bounds = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        var radius = Compact ? 4f : 6f;

        Color fill;
        if (Primary)
        {
            fill = Enabled
                ? (_down ? Color.FromArgb(56, accent) : _hover ? Color.FromArgb(40, accent) : Color.FromArgb(26, accent))
                : Theme.PanelRaised;
        }
        else
        {
            fill = _down ? Theme.PanelRaised
                : _hover ? Color.FromArgb(22, 20, 18)
                : Color.FromArgb(10, 10, 12);
        }

        Draw2D.FillRounded(g, bounds, radius, fill);
        Draw2D.StrokeRounded(g, bounds, radius,
            Primary && Enabled ? Color.FromArgb(150, accent) : Theme.Line,
            Primary ? 1.2f : 1f);

        if (Focused)
            Draw2D.StrokeRounded(g, RectangleF.Inflate(bounds, -2, -2), radius, Color.FromArgb(110, accent), 1f);

        var textColor = !Enabled ? Theme.Faint : Primary ? Theme.AccentHot(_accentName) : Theme.Text;
        TextRenderer.DrawText(g, Text, Font,
            new Rectangle(0, 0, Width, Height), textColor,
            TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter |
            TextFormatFlags.EndEllipsis | TextFormatFlags.NoPrefix);
    }

    private string _accentName = "gold";

    public string AccentName
    {
        get => _accentName;
        set
        {
            _accentName = value ?? "gold";
            AccentColor = Theme.Accent(_accentName);
            Invalidate();
        }
    }
}
