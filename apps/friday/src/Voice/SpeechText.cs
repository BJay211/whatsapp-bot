using System;
using System.Collections.Generic;
using System.Text;
using System.Text.RegularExpressions;

namespace Friday.Voice;

/// <summary>
/// Turns model output into something worth listening to, and splits it at
/// sentence boundaries. The on-screen answer is never modified.
/// </summary>
public static class SpeechText
{
    private static readonly Regex CodeFence = new(@"```[\s\S]*?```", RegexOptions.Compiled);
    private static readonly Regex MarkdownLink = new(@"\[([^\]]+)\]\([^)]*\)", RegexOptions.Compiled);
    private static readonly Regex InlineCode = new(@"`([^`]*)`", RegexOptions.Compiled);
    private static readonly Regex Url = new(@"https?://\S+", RegexOptions.Compiled | RegexOptions.IgnoreCase);
    private static readonly Regex Heading = new(@"(?m)^#{1,6}\s*", RegexOptions.Compiled);
    private static readonly Regex Bullet = new(@"(?m)^\s*[-*+]\s+", RegexOptions.Compiled);
    private static readonly Regex Emphasis = new(@"[*_~]{1,3}", RegexOptions.Compiled);
    private static readonly Regex Metadata = new(
        @"(?m)^\s*(—|--|-)\s*(AI|source|model|tokens?)\b.*$",
        RegexOptions.Compiled | RegexOptions.IgnoreCase);
    private static readonly Regex Whitespace = new(@"\s+", RegexOptions.Compiled);
    private static readonly Regex Jsonish = new(@"^\s*[\[{].*[\]}]\s*$",
        RegexOptions.Compiled | RegexOptions.Singleline);

    public static string Clean(string raw)
    {
        var text = (raw ?? "").Trim();
        if (text.Length == 0) return "";
        if (Jsonish.IsMatch(text)) return "The details are on screen.";

        text = CodeFence.Replace(text, " Code is on screen. ");
        text = MarkdownLink.Replace(text, "$1");
        text = InlineCode.Replace(text, "$1");
        text = Url.Replace(text, " a link ");
        text = Metadata.Replace(text, "");
        text = Heading.Replace(text, "");
        text = Bullet.Replace(text, "");
        text = Emphasis.Replace(text, "");
        text = text.Replace("`", "");
        text = text.Replace("•", " ");
        text = Whitespace.Replace(text, " ").Trim();

        var slashes = 0;
        foreach (var ch in text)
            if (ch is '/' or '\\') slashes++;
        return slashes > 6 ? "The result is on screen." : text;
    }

    public static IReadOnlyList<string> Split(string raw)
    {
        var text = Clean(raw);
        if (text.Length == 0) return Array.Empty<string>();

        var result = new List<string>();
        var current = new StringBuilder();
        for (var i = 0; i < text.Length; i++)
        {
            var ch = text[i];
            current.Append(ch);
            var boundary = (ch is '.' or '!' or '?' or '\n') &&
                           (i + 1 >= text.Length || char.IsWhiteSpace(text[i + 1]));
            if (!boundary) continue;
            var sentence = current.ToString().Trim();
            current.Clear();
            if (sentence.Length > 1) result.Add(sentence);
        }
        var tail = current.ToString().Trim();
        if (tail.Length > 0) result.Add(tail);
        return result;
    }

    /// <summary>Splits over-long unpunctuated sentences into listenable chunks.</summary>
    public static IEnumerable<string> Chunks(IReadOnlyList<string> sentences, int max = 220)
    {
        foreach (var sentence in sentences)
        {
            if (sentence.Length <= max)
            {
                yield return sentence;
                continue;
            }
            for (var i = 0; i < sentence.Length; i += max)
                yield return sentence.Substring(i, Math.Min(max, sentence.Length - i));
        }
    }
}
