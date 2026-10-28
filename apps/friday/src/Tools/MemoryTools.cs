using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json.Nodes;
using System.Threading.Tasks;

namespace Friday.Tools;

public static class MemoryTools
{
    public static IEnumerable<ToolDescriptor> Describe()
    {
        yield return new ToolDescriptor
        {
            Name = "remember",
            Description = "Propose a long-term fact about the user to remember. It is stored only after the user approves it.",
            Capability = Capability.Memory,
            Risk = RiskLevel.Confirm,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"text":{"type":"string"},"tags":{"type":"array","items":{"type":"string"}}},"required":["text"],"additionalProperties":false}
            """),
            Handler = (ctx, args) =>
            {
                var text = Args.Str(args, "text").Trim();
                if (text.Length == 0) return Task.FromResult(ToolResult.Fail("There was nothing to remember."));
                var entry = ctx.Runtime.Memory.Add(text, Args.StrArray(args, "tags"), approved: true, source: ctx.Source);
                return Task.FromResult(entry is null
                    ? ToolResult.Fail("I could not store that.")
                    : ToolResult.Good("Remembered: " + entry.Text));
            },
        };
        yield return new ToolDescriptor
        {
            Name = "recall",
            Description = "Search the user's approved long-term memories.",
            Capability = Capability.Memory,
            Risk = RiskLevel.Safe,
            Parameters = ToolJson.Params("""
            {"type":"object","properties":{"query":{"type":"string"}},"additionalProperties":false}
            """),
            Handler = (ctx, args) =>
            {
                var matches = ctx.Runtime.Memory.Search(Args.Str(args, "query"));
                return Task.FromResult(ToolResult.Good(matches.Length == 0
                    ? "I have nothing stored about that."
                    : string.Join(Environment.NewLine, matches.Select(m => "- " + m))));
            },
        };
    }
}
