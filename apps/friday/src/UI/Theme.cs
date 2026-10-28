using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Windows.Forms;
using Friday.Core;

namespace Friday.UI;

public static class Theme
{
    public static readonly Color Bg = Color.FromArgb(4, 4, 6);
    public static readonly Color Panel = Color.FromArgb(7, 7, 10);
    public static readonly Color PanelRaised = Color.FromArgb(13, 12, 14);
    public static readonly Color Line = Color.FromArgb(38, 33, 26);
    public static readonly Color Text = Color.FromArgb(228, 224, 216);
    public static readonly Color Dim = Color.FromArgb(138, 132, 122);
    public static readonly Color Faint = Color.FromArgb(84, 80, 74);

    public static readonly Color Gold = Color.FromArgb(255, 178, 78);
    public static readonly Color GoldHot = Color.FromArgb(255, 231, 173);
    public static readonly Color GoldDeep = Color.FromArgb(214, 96, 12);

    public static Color Accent(string name) => name?.ToLowerInvariant() switch
    {
        "ember" => Color.FromArgb(255, 104, 42),
        "ice" => Color.FromArgb(128, 226, 255),
        "jade" => Color.FromArgb(112, 240, 178),
        _ => Gold,
    };

    public static Color AccentHot(string name) => name?.ToLowerInvariant() switch
    {
        "ember" => Color.FromArgb(255, 206, 158),
        "ice" => Color.FromArgb(220, 250, 255),
        "jade" => Color.FromArgb(214, 255, 236),
        _ => GoldHot,
    };

    public static Color StateColor(FridayState state, string accent)
    {
        return state switch
        {
            FridayState.Error => Color.FromArgb(255, 92, 92),
            FridayState.Offline => Color.FromArgb(120, 130, 145),
            FridayState.AwaitingApproval => Color.FromArgb(255, 196, 92),
            FridayState.Success => Color.FromArgb(120, 226, 150),
            _ => Accent(accent),
        };
    }

    public static Font Ui(float size, FontStyle style = FontStyle.Regular) =>
        new("Segoe UI", size, style, GraphicsUnit.Point);

    public static Font Mono(float size, FontStyle style = FontStyle.Regular) =>
        new("Consolas", size, style, GraphicsUnit.Point);

    public static Font Display(float size, FontStyle style = FontStyle.Bold) =>
        new("Bahnschrift SemiCondensed", size, style, GraphicsUnit.Point);

    public static string PrettyState(FridayState state) => state switch
    {
        FridayState.Idle => "IDLE",
        FridayState.Listening => "LISTENING",
        FridayState.Thinking => "THINKING",
        FridayState.Speaking => "SPEAKING",
        FridayState.Executing => "EXECUTING",
        FridayState.AwaitingApproval => "AWAITING APPROVAL",
        FridayState.Success => "DONE",
        FridayState.Error => "ERROR",
        FridayState.Offline => "OFFLINE",
        _ => state.ToString().ToUpperInvariant(),
    };

    public static Icon CreateIcon(Color accent)
    {
        using var bitmap = new Bitmap(64, 64);
        using (var g = Graphics.FromImage(bitmap))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);

            using var outer = new Pen(Color.FromArgb(180, accent), 3f);
            g.DrawEllipse(outer, 4, 4, 56, 56);
            using var inner = new SolidBrush(Color.FromArgb(210, accent));
            g.FillEllipse(inner, 24, 24, 16, 16);
            using var ring = new Pen(Color.FromArgb(120, Theme.GoldHot), 1f);
            g.DrawEllipse(ring, 14, 14, 36, 36);
        }

        var handle = bitmap.GetHicon();
        try
        {
            return (Icon)Icon.FromHandle(handle).Clone();
        }
        finally
        {
            NativeMethods.DestroyIcon(handle);
        }
    }

    private static class NativeMethods
    {
        [System.Runtime.InteropServices.DllImport("user32.dll", SetLastError = true)]
        public static extern bool DestroyIcon(IntPtr handle);
    }
}
