using System;
using System.Text.RegularExpressions;

namespace Friday.Voice;

/// <summary>
/// Decides whether a recognised sentence is addressed to Friday, and returns the
/// instruction without the wake word. Kept separate from the engine so the rule
/// can be tested without a microphone.
/// </summary>
public static class WakeWord
{
    /// <summary>
    /// Extracts the instruction from "Friday, open Chrome" (also "hey Friday …",
    /// "okay Friday …", and a leading "um/uh/so"). A bare wake word returns true
    /// with an empty instruction.
    /// </summary>
    public static bool TryExtract(string text, string wakeWord, out string command)
    {
        command = "";
        var trimmed = (text ?? "").Trim();
        if (trimmed.Length == 0) return false;

        var word = string.IsNullOrWhiteSpace(wakeWord) ? "friday" : wakeWord.Trim();
        var pattern = @"^(?:(?:hey|hi|ok|okay|yo|um|uh|er|so|and)\s+)?" +
                      Regex.Escape(word) +
                      @"\b[\s,.:;!\-–—]*(?<rest>.*)$";

        var match = Regex.Match(trimmed, pattern, RegexOptions.IgnoreCase);
        if (!match.Success) return false;

        command = match.Groups["rest"].Value.Trim();
        return true;
    }

    public static bool IsAddressed(string text, string wakeWord) =>
        TryExtract(text, wakeWord, out _);
}
