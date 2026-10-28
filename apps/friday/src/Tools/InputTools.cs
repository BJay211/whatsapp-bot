using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using System.Windows.Forms;
using Friday.Core;

namespace Friday.Tools;

public static class InputTools
{
    public static IEnumerable<ToolDescriptor> Describe()
    {
        yield return new ToolDescriptor
        {
            Name = "type_text",
            Description = "Type text into whatever application currently has keyboard focus.",
            Capability = Capability.Input,
            Risk = RiskLevel.Confirm,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}
            """),
            Handler = (ctx, args) =>
            {
                var text = Args.Str(args, "text");
                if (text.Length == 0) return Task.FromResult(ToolResult.Fail("There was no text to type."));
                Native.SendUnicodeText(text);
                return Task.FromResult(ToolResult.Good($"Typed {text.Length} characters into the focused window."));
            },
        };
        yield return new ToolDescriptor
        {
            Name = "press_keys",
            Description = "Press a keyboard shortcut or key using SendKeys syntax, for example ^s (Ctrl+S), %{TAB} (Alt+Tab).",
            Capability = Capability.Input,
            Risk = RiskLevel.Confirm,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"keys":{"type":"string"}},"required":["keys"],"additionalProperties":false}
            """),
            Handler = (ctx, args) =>
            {
                var keys = Args.Str(args, "keys");
                if (keys.Length == 0) return Task.FromResult(ToolResult.Fail("No keys were given."));
                SendKeys.SendWait(keys);
                return Task.FromResult(ToolResult.Good($"Pressed {keys}."));
            },
        };
        yield return new ToolDescriptor
        {
            Name = "move_mouse",
            Description = "Move the mouse cursor to an absolute screen position.",
            Capability = Capability.Input,
            Risk = RiskLevel.Confirm,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"}},"required":["x","y"],"additionalProperties":false}
            """),
            Handler = (ctx, args) =>
            {
                Native.SetCursorPos(Args.Int(args, "x"), Args.Int(args, "y"));
                return Task.FromResult(ToolResult.Good($"Moved the cursor to {Args.Int(args, "x")},{Args.Int(args, "y")}."));
            },
        };
        yield return new ToolDescriptor
        {
            Name = "click",
            Description = "Click the mouse at the current position, or at x,y when given.",
            Capability = Capability.Input,
            Risk = RiskLevel.Confirm,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"button":{"type":"string","enum":["left","right","middle"]},"double":{"type":"boolean"},"x":{"type":"integer"},"y":{"type":"integer"}},"additionalProperties":false}
            """),
            Handler = (ctx, args) =>
            {
                if (args["x"] is not null && args["y"] is not null)
                    Native.SetCursorPos(Args.Int(args, "x"), Args.Int(args, "y"));

                var button = Args.Str(args, "button", "left").ToLowerInvariant();
                var (down, up) = button switch
                {
                    "right" => (Native.MOUSEEVENTF_RIGHTDOWN, Native.MOUSEEVENTF_RIGHTUP),
                    "middle" => (Native.MOUSEEVENTF_MIDDLEDOWN, Native.MOUSEEVENTF_MIDDLEUP),
                    _ => (Native.MOUSEEVENTF_LEFTDOWN, Native.MOUSEEVENTF_LEFTUP),
                };
                var times = Args.Bool(args, "double") ? 2 : 1;
                for (var i = 0; i < times; i++)
                {
                    Native.mouse_event(down, 0, 0, 0, UIntPtr.Zero);
                    Native.mouse_event(up, 0, 0, 0, UIntPtr.Zero);
                    if (times > 1) Thread.Sleep(60);
                }
                return Task.FromResult(ToolResult.Good($"Clicked {button}{(times > 1 ? " twice" : "")}."));
            },
        };
        yield return new ToolDescriptor
        {
            Name = "scroll",
            Description = "Scroll the focused window. Positive values scroll up, negative down.",
            Capability = Capability.Input,
            Risk = RiskLevel.Confirm,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"amount":{"type":"integer","description":"Wheel clicks; negative scrolls down."}},"required":["amount"],"additionalProperties":false}
            """),
            Handler = (ctx, args) =>
            {
                var amount = Args.Int(args, "amount", 3);
                Native.mouse_event(Native.MOUSEEVENTF_WHEEL, 0, 0, amount * 120, UIntPtr.Zero);
                return Task.FromResult(ToolResult.Good($"Scrolled {(amount >= 0 ? "up" : "down")} {Math.Abs(amount)} clicks."));
            },
        };
        yield return new ToolDescriptor
        {
            Name = "screenshot",
            Description = "Take a screenshot and save it in Friday's screenshots folder. Returns the file path.",
            Capability = Capability.Screen,
            Risk = RiskLevel.Confirm,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"label":{"type":"string"}},"additionalProperties":false}
            """),
            Handler = (ctx, args) =>
            {
                var label = Args.Str(args, "label");
                var suffix = string.IsNullOrWhiteSpace(label)
                    ? ""
                    : "_" + new string(label.Where(char.IsLetterOrDigit).Take(24).ToArray());
                var path = Path.Combine(AppPaths.ScreenshotsDir,
                    $"friday_{DateTime.Now:yyyyMMdd_HHmmss}{suffix}.png");
                CaptureAllScreens(path);
                var info = new FileInfo(path);
                return Task.FromResult(ToolResult.Good(
                    $"Screenshot saved to {path} ({info.Length / 1024} KB).",
                    new JsonObject { ["path"] = path, ["bytes"] = info.Length }.ToJsonString()));
            },
        };
    }

    private static void CaptureAllScreens(string path)
    {
        var bounds = SystemInformation.VirtualScreen;
        using var bitmap = new Bitmap(bounds.Width, bounds.Height, PixelFormat.Format32bppArgb);
        using (var graphics = Graphics.FromImage(bitmap))
        {
            graphics.CopyFromScreen(bounds.Left, bounds.Top, 0, 0, bounds.Size, CopyPixelOperation.SourceCopy);
        }
        bitmap.Save(path, ImageFormat.Png);
    }
}
