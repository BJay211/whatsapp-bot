using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Friday.Core;

namespace Friday.Tools;

public enum Capability
{
    Apps,
    Windows,
    Input,
    Screen,
    Files,
    Shell,
    System,
    Web,
    Project,
    Memory,
    Tasks,
}

public enum RiskLevel
{
    Safe = 0,
    Confirm = 1,
    Destructive = 2,
}

public sealed record ToolResult(
    bool Ok,
    string Summary,
    string? Data = null,
    bool Denied = false,
    bool Cancelled = false)
{
    public static ToolResult Good(string summary, string? data = null) => new(true, summary, data);
    public static ToolResult Fail(string summary, string? data = null) => new(false, summary, data);
    public static ToolResult Refused(string summary) => new(false, summary, null, Denied: true);
}

public sealed record ApprovalRequest(
    string Id,
    string Title,
    string Detail,
    Capability Capability,
    string ToolName,
    bool Destructive,
    string Source);

public sealed record ApprovalDecision(bool Approved, bool Remember);

public sealed class ToolContext
{
    public required FridayConfig Config { get; init; }
    public required string Source { get; init; }
    public required Security.PathGuard Paths { get; init; }
    public string? DeviceId { get; init; }
    public string RequestId { get; init; } = "";
    public CancellationToken Token { get; init; }
    public Action<string>? Progress { get; init; }
    public Func<ApprovalRequest, Task<ApprovalDecision>>? RequestApproval { get; init; }
    public required FridayRuntime Runtime { get; init; }

    public void Report(string message) => Progress?.Invoke(message);
}

public sealed class ToolDescriptor
{
    public required string Name { get; init; }
    public required string Description { get; init; }
    public required Capability Capability { get; init; }
    public required RiskLevel Risk { get; init; }
    public required JsonObject Parameters { get; init; }
    public required Func<ToolContext, JsonObject, Task<ToolResult>> Handler { get; init; }
}

public static class Args
{
    public static string Str(JsonObject args, string name, string fallback = "")
    {
        var node = args[name];
        if (node is null) return fallback;
        try { return node.GetValue<string>(); }
        catch
        {
            try { return node.ToJsonString().Trim('"'); }
            catch { return fallback; }
        }
    }

    public static int Int(JsonObject args, string name, int fallback = 0)
    {
        var node = args[name];
        if (node is null) return fallback;
        try { return node.GetValue<int>(); }
        catch
        {
            return int.TryParse(node.ToString(), out var value) ? value : fallback;
        }
    }

    public static bool Bool(JsonObject args, string name, bool fallback = false)
    {
        var node = args[name];
        if (node is null) return fallback;
        try { return node.GetValue<bool>(); }
        catch
        {
            return bool.TryParse(node.ToString(), out var value) ? value : fallback;
        }
    }

    public static string[] StrArray(JsonObject args, string name)
    {
        if (args[name] is not JsonArray array) return Array.Empty<string>();
        return array.Select(item => item?.ToString() ?? "").Where(s => s.Length > 0).ToArray();
    }
}

/// <summary>
/// The controlled execution layer. The model can only ask for a named tool with
/// JSON arguments; everything below validates capability switches, directory
/// restrictions, approval requirements and audit logging before anything runs.
/// </summary>
public sealed class ToolRegistry
{
    private readonly Dictionary<string, ToolDescriptor> _tools = new(StringComparer.OrdinalIgnoreCase);

    public IEnumerable<ToolDescriptor> All => _tools.Values.OrderBy(t => t.Name, StringComparer.Ordinal);

    public ToolRegistry()
    {
        foreach (var descriptor in AppTools.Describe()) Add(descriptor);
        foreach (var descriptor in InputTools.Describe()) Add(descriptor);
        foreach (var descriptor in FileTools.Describe()) Add(descriptor);
        foreach (var descriptor in ShellTools.Describe()) Add(descriptor);
        foreach (var descriptor in SystemTools.Describe()) Add(descriptor);
        foreach (var descriptor in WebTools.Describe()) Add(descriptor);
        foreach (var descriptor in ProjectTools.Describe()) Add(descriptor);
        foreach (var descriptor in MemoryTools.Describe()) Add(descriptor);
    }

    private void Add(ToolDescriptor descriptor) => _tools[descriptor.Name] = descriptor;

    public ToolDescriptor? Find(string name) =>
        _tools.TryGetValue(name ?? "", out var descriptor) ? descriptor : null;

    /// <summary>Tools the model is shown. Capability switches are enforced again on call.</summary>
    public JsonArray SchemaFor(bool includeDestructive = true)
    {
        var array = new JsonArray();
        foreach (var tool in All)
        {
            if (!includeDestructive && tool.Risk == RiskLevel.Destructive) continue;
            array.Add(new JsonObject
            {
                ["type"] = "function",
                ["function"] = new JsonObject
                {
                    ["name"] = tool.Name,
                    ["description"] = tool.Description,
                    ["parameters"] = tool.Parameters.DeepClone(),
                },
            });
        }
        return array;
    }

    public static bool CapabilityEnabled(ToolDescriptor tool, FridayConfig config) => tool.Capability switch
    {
        Capability.Input => config.Policy.AllowInputControl,
        Capability.Screen => config.Policy.AllowScreenCapture,
        Capability.Files => true,
        Capability.Shell => true,
        Capability.System => true,
        Capability.Web => true,
        _ => true,
    };

    /// <summary>
    /// The switch that turns this tool off, or null when it is allowed. Input and
    /// screen are whole capabilities; the rest are narrower than a capability, so
    /// they are matched by tool name. Each label matches the switch drawn in
    /// Settings > Access.
    /// </summary>
    public static string? DisabledReason(ToolDescriptor tool, FridayConfig config)
    {
        if (!CapabilityEnabled(tool, config))
            return tool.Capability == Capability.Screen ? "Screenshots" : "Input control";

        return tool.Name switch
        {
            "write_file" or "create_folder" or "create_document" or
            "copy_path" or "move_path" or "zip_path" or "unzip"
                when !config.Policy.AllowFileWrites => "File writes and moves",

            "delete_path" when !config.Policy.AllowDelete => "Deleting files",

            "power_action" when !config.Policy.AllowPowerControls =>
                "Sleep, restart and shutdown",

            "download_file" when !config.Policy.AllowDownloads => "Downloads",

            _ => null,
        };
    }

    public async Task<ToolResult> ExecuteAsync(string name, JsonObject args, ToolContext context)
    {
        var tool = Find(name);
        if (tool is null)
        {
            return ToolResult.Fail($"There is no tool called \"{name}\". I will not guess at one.");
        }

        var disabled = DisabledReason(tool, context.Config);
        if (disabled is not null)
        {
            FLog.Audit(tool.Name, context.Source, "refused-capability", disabled, false,
                context.DeviceId, context.RequestId);
            return ToolResult.Refused(
                $"{disabled} is switched off in Friday's settings, so I did not run {tool.Name}.");
        }

        // Safe tools run on their own only while the user allows it; switching
        // "Auto-approve safe actions" off makes even a folder listing ask first.
        if (context.Config.Policy.StrictConfirm ||
            tool.Risk >= RiskLevel.Confirm ||
            (tool.Risk == RiskLevel.Safe && !context.Config.Policy.AutoApproveSafe))
        {
            if (context.RequestApproval is null)
            {
                return ToolResult.Refused(
                    $"{tool.Name} needs your approval, and there is no way to ask you from {context.Source}.");
            }

            var request = new ApprovalRequest(
                Guid.NewGuid().ToString("n")[..10],
                tool.Risk == RiskLevel.Destructive ? "Confirm a destructive action" : "Permission required",
                ApprovalDetail(tool, args),
                tool.Capability,
                tool.Name,
                tool.Risk == RiskLevel.Destructive,
                context.Source);

            var decision = await context.RequestApproval(request).ConfigureAwait(false);
            FLog.Audit(tool.Name, context.Source,
                decision.Approved ? "approved" : "denied",
                ApprovalDetail(tool, args), decision.Approved, context.DeviceId, context.RequestId);

            if (!decision.Approved)
            {
                return ToolResult.Refused("You declined that action, so I did not run it.");
            }
        }

        try
        {
            var result = await tool.Handler(context, args).ConfigureAwait(false);
            FLog.Audit(tool.Name, context.Source, result.Ok ? "succeeded" : "failed",
                result.Summary, result.Ok, context.DeviceId, context.RequestId);
            return result;
        }
        catch (OperationCanceledException)
        {
            FLog.Audit(tool.Name, context.Source, "cancelled", "cancelled by user", false,
                context.DeviceId, context.RequestId);
            return new ToolResult(false, "Cancelled.", null, Cancelled: true);
        }
        catch (Exception ex)
        {
            FLog.Error("tools", $"{tool.Name} threw: {ex.Message}");
            FLog.Audit(tool.Name, context.Source, "error", ex.Message, false,
                context.DeviceId, context.RequestId);
            return ToolResult.Fail($"{tool.Name} failed: {ex.Message}");
        }
    }

    private static string ApprovalDetail(ToolDescriptor tool, JsonObject args)
    {
        var json = args.ToJsonString();
        if (json.Length > 900) json = json[..900] + "…";
        return $"{tool.Name}  {json}";
    }
}

public static class ToolJson
{
    public static JsonObject Params(string raw) => JsonNode.Parse(raw)!.AsObject();

    public static JsonObject Describe(string name, string description, Capability capability,
        RiskLevel risk, string schema) => new()
    {
        ["name"] = name,
        ["description"] = description,
        ["capability"] = capability.ToString(),
        ["risk"] = risk.ToString(),
        ["parameters"] = schema,
    };
}
