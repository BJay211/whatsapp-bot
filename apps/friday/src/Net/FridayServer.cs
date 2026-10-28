using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Friday.Core;
using Friday.Tools;
using TaskStatus = Friday.Core.TaskStatus;

namespace Friday.Net;

/// <summary>
/// The phone-facing service. TLS only, token authenticated, with pairing,
/// idempotent request ids, task polling, approvals, revocation and an emergency
/// stop. It deliberately speaks a small hand-written HTTP/1.1 subset instead of
/// using HttpListener, so it needs no URL ACL and no administrator rights.
/// </summary>
public sealed class FridayServer : IDisposable
{
    private readonly FridayRuntime _runtime;
    private TcpListener? _listener;
    private CancellationTokenSource? _cts;
    private Task? _acceptLoop;
    private readonly ConcurrentDictionary<string, List<DateTimeOffset>> _rate = new();
    private readonly ConcurrentDictionary<string, (int Count, DateTimeOffset First)> _failures = new();
    private readonly JsonSerializerOptions _json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        WriteIndented = false,
    };

    public ServerIdentity? Identity { get; private set; }
    public bool Running { get; private set; }
    public string LastError { get; private set; } = "";
    public int Port { get; private set; }
    private int _requestCount;
    public int RequestCount => Volatile.Read(ref _requestCount);

    public event Action? StatusChanged;

    public FridayServer(FridayRuntime runtime) => _runtime = runtime;

    public string Fingerprint => Identity?.Fingerprint ?? "";

    public string[] Urls()
    {
        var port = Port == 0 ? _runtime.Config.Server.Port : Port;
        var scheme = _runtime.Config.Server.UseTls ? "https" : "http";
        var hosts = _runtime.Config.Server.AllowLan
            ? ServerIdentity.LocalAddresses().Select(ip => ip.ToString()).Append("localhost")
            : new[] { "localhost" };
        return hosts.Select(h => $"{scheme}://{h}:{port}").ToArray();
    }

    public void Start()
    {
        if (Running) return;
        if (!_runtime.Config.Server.Enabled)
        {
            LastError = "The phone service is switched off in Settings.";
            StatusChanged?.Invoke();
            return;
        }

        try
        {
            Identity = ServerIdentity.LoadOrCreate();
            Port = _runtime.Config.Server.Port;
            var bind = _runtime.Config.Server.AllowLan ? IPAddress.Any : IPAddress.Loopback;
            _listener = new TcpListener(bind, Port);
            _listener.Start();
            _cts = new CancellationTokenSource();
            _acceptLoop = Task.Run(() => AcceptLoopAsync(_cts.Token));
            Running = true;
            LastError = "";
            FLog.Info("server", $"Listening on {(bind.Equals(IPAddress.Any) ? "all interfaces" : "localhost")}:{Port} (TLS)");
            _runtime.Audit("server", "system", "started", $"port {Port}", true);
        }
        catch (SocketException ex)
        {
            Running = false;
            LastError = ex.SocketErrorCode == SocketError.AddressAlreadyInUse
                ? $"Port {_runtime.Config.Server.Port} is already in use. Pick another port in Settings."
                : $"Could not start the phone service: {ex.Message}";
            FLog.Error("server", LastError);
        }
        catch (Exception ex)
        {
            Running = false;
            LastError = "Could not start the phone service: " + ex.Message;
            FLog.Error("server", LastError);
        }

        StatusChanged?.Invoke();
    }

    public void Stop()
    {
        try { _cts?.Cancel(); } catch { }
        try { _listener?.Stop(); } catch { }
        Running = false;
        _listener = null;
        FLog.Info("server", "Stopped.");
        StatusChanged?.Invoke();
    }

    public void Restart()
    {
        Stop();
        Thread.Sleep(250);
        Start();
    }

    private async Task AcceptLoopAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener!.AcceptTcpClientAsync(token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (Exception ex)
            {
                if (token.IsCancellationRequested) break;
                FLog.Warn("server", "Accept failed: " + ex.Message);
                continue;
            }

            _ = Task.Run(() => HandleClientAsync(client, token), CancellationToken.None);
        }
    }

    private async Task HandleClientAsync(TcpClient client, CancellationToken serverToken)
    {
        using (client)
        {
            client.NoDelay = true;
            client.ReceiveTimeout = 20000;
            client.SendTimeout = 20000;
            var remote = "?";
            try { remote = client.Client.RemoteEndPoint?.ToString() ?? "?"; } catch { }

            Stream stream = client.GetStream();
            SslStream? ssl = null;
            try
            {
                if (_runtime.Config.Server.UseTls && Identity is not null)
                {
                    ssl = new SslStream(stream, leaveInnerStreamOpen: false);
                    using var handshake = CancellationTokenSource.CreateLinkedTokenSource(serverToken);
                    handshake.CancelAfter(TimeSpan.FromSeconds(30));
                    await ssl.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
                    {
                        ServerCertificate = Identity.Certificate,
                        ClientCertificateRequired = false,
                        EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                        CertificateRevocationCheckMode = System.Security.Cryptography.X509Certificates.X509RevocationMode.NoCheck,
                    }, handshake.Token).ConfigureAwait(false);
                    stream = ssl;
                    ssl.ReadTimeout = 20000;
                    ssl.WriteTimeout = 20000;
                }

                // One connection carries as many requests as the client sends. The
                // phone polls a few times a second, so keeping the TLS session open
                // removes a handshake from every poll.
                while (!serverToken.IsCancellationRequested)
                {
                    using var timeout = CancellationTokenSource.CreateLinkedTokenSource(serverToken);
                    timeout.CancelAfter(TimeSpan.FromSeconds(30));

                    var request = await HttpRequestLite.ReadAsync(
                        stream, _runtime.Config.Server.MaxBodyKb * 1024, timeout.Token).ConfigureAwait(false);
                    if (request is null) return;

                    Interlocked.Increment(ref _requestCount);
                    var (status, body) = await RouteAsync(request, remote).ConfigureAwait(false);

                    var keepAlive = request.KeepAlive && !request.HasExtraBytes &&
                                    !serverToken.IsCancellationRequested;
                    await WriteAsync(stream, status, body, keepAlive, timeout.Token).ConfigureAwait(false);
                    if (!keepAlive) return;
                }
            }
            catch (AuthenticationException ex)
            {
                FLog.Warn("server", $"TLS handshake from {remote} failed: {ex.Message}");
            }
            catch (OperationCanceledException)
            {
                // Client went away, or the 30s request budget expired.
            }
            catch (InvalidDataException ex)
            {
                FLog.Warn("server", $"Rejected request from {remote}: {ex.Message}");
            }
            catch (Exception ex)
            {
                FLog.Warn("server", $"Request from {remote} failed: {ex.Message}");
            }
            finally
            {
                try { ssl?.Dispose(); } catch { }
            }
        }
    }

    private async Task<(int Status, JsonObject Body)> RouteAsync(HttpRequestLite request, string remote)
    {
        var path = request.Path.TrimEnd('/');
        if (path.Length == 0) path = "/";

        // ---- unauthenticated -------------------------------------------------
        if (path == "/v1/ping" && request.Method == "GET") return (200, Ping());

        if (path == "/v1/pair" && request.Method == "POST")
            return Pair(request, remote);

        // ---- authenticated ---------------------------------------------------
        var device = Authenticate(request, remote, out var authError);
        if (device is null)
            return (401, Error("unauthorized", authError));

        if (!RateOk(device.Id))
            return (429, Error("rate_limited", "Too many requests. Wait a moment."));

        var server = _runtime.Config.Server;
        switch (request.Method)
        {
            case "GET" when path == "/v1/status":
                return (200, Status(device));

            case "GET" when path == "/v1/tasks":
                return (200, Tasks());

            case "GET" when path == "/v1/task":
            {
                var id = Query(request, "task_id");
                var task = _runtime.Tasks.Get(id);
                return task is null
                    ? (404, Error("not_found", "No task with that id."))
                    : (200, TaskJson(task));
            }

            case "GET" when path.StartsWith("/v1/task/"):
            {
                var id = path["/v1/task/".Length..];
                var task = _runtime.Tasks.Get(id);
                return task is null
                    ? (404, Error("not_found", "No task with that id."))
                    : (200, TaskJson(task));
            }

            case "POST" when path == "/v1/command":
                return await CommandAsync(request, device, remote).ConfigureAwait(false);

            case "POST" when path.EndsWith("/cancel"):
            {
                var id = ExtractTaskId(path, "/cancel");
                if (id is null) return (400, Error("bad_request", "Missing task id."));
                var cancelled = _runtime.Tasks.Cancel(id, "Cancelled from the phone.");
                _runtime.Audit("task.cancel", "phone:" + device.Name, cancelled ? "cancelled" : "not-cancelled",
                    id, cancelled, device.Id);
                return (200, new JsonObject
                {
                    ["ok"] = cancelled,
                    ["task_id"] = id,
                    ["status"] = cancelled ? "cancelled" : "not_running",
                    ["summary"] = cancelled ? "Cancelled." : "That task is not running.",
                });
            }

            case "POST" when path == "/v1/approve":
            case "POST" when path.EndsWith("/approve"):
                return Approve(request, device);

            case "GET" when path == "/v1/approvals":
                return (200, Approvals());

            case "GET" when path == "/v1/events":
            {
                long.TryParse(Query(request, "since"), out var since);
                var events = _runtime.Events.Since(since);
                return (200, new JsonObject
                {
                    ["ok"] = true,
                    ["latest"] = _runtime.Events.Latest,
                    ["events"] = new JsonArray(events.Select(e => (JsonNode)EventJson(e)).ToArray()),
                });
            }

            case "POST" when path == "/v1/emergency-stop":
            {
                var engage = true;
                try
                {
                    var body = Parse(request.Body);
                    if (body?["engage"] is not null) engage = body["engage"]!.GetValue<bool>();
                }
                catch { }

                _runtime.SetEmergencyStop(engage, "phone:" + device.Name);
                return (200, new JsonObject
                {
                    ["ok"] = true,
                    ["emergency_stop"] = _runtime.EmergencyStopped,
                    ["summary"] = _runtime.EmergencyStopped
                        ? "Emergency stop engaged. All tasks cancelled."
                        : "Emergency stop released.",
                });
            }

            case "POST" when path == "/v1/devices/revoke":
            {
                var id = "";
                try
                {
                    var body = Parse(request.Body);
                    id = body?["device_id"]?.GetValue<string>() ?? "";
                }
                catch { }
                if (id.Length == 0) return (400, Error("bad_request", "No device id."));

                var revoked = _runtime.Pairing.Revoke(id);
                _runtime.Audit("device.revoke", "phone:" + device.Name, revoked ? "revoked" : "not-found",
                    id, revoked, device.Id);
                return (200, new JsonObject
                {
                    ["ok"] = revoked,
                    ["summary"] = revoked ? "Device revoked." : "No such device.",
                });
            }

            case "GET" when path == "/v1/health":
                return (200, Status(device));
        }

        return (404, Error("not_found", $"No route for {request.Method} {path}."));
    }

    private JsonObject Ping() => new()
    {
        ["ok"] = true,
        ["name"] = "Friday",
        ["version"] = "1.0.0",
        ["protocol"] = "1.0",
        ["machine"] = Environment.MachineName,
        ["tls"] = _runtime.Config.Server.UseTls,
        ["fingerprint"] = Fingerprint,
        ["paired_devices"] = _runtime.Pairing.ActiveCount,
        ["pairing_required"] = _runtime.Config.Server.RequirePairing,
        ["emergency_stop"] = _runtime.EmergencyStopped,
    };

    private (int, JsonObject) Pair(HttpRequestLite request, string remote)
    {
        JsonObject? body;
        try { body = Parse(request.Body); }
        catch { return (400, Error("bad_request", "The body must be JSON.")); }

        var code = body?["code"]?.GetValue<string>() ?? "";
        var name = body?["device_name"]?.GetValue<string>() ?? "Phone";
        var platform = body?["platform"]?.GetValue<string>() ?? "android";

        var result = _runtime.Pairing.Pair(code, name, platform);
        if (!result.Ok)
        {
            FLog.Audit("pair", "unknown:" + remote, "failed", result.Error, false);
            // Slow brute force down: 500ms on every bad code.
            Thread.Sleep(500);
            return (403, Error("pairing_failed", result.Error));
        }

        _runtime.Audit("pair", "phone:" + name, "paired", platform + " from " + remote, true, result.Device?.Id);
        _runtime.Events.Publish("device", "", "paired", name + " paired");
        FLog.Info("pairing", $"{name} paired from {remote}.");
        return (200, new JsonObject
        {
            ["ok"] = true,
            ["device_id"] = result.Device!.Id,
            ["device_name"] = result.Device.Name,
            ["token"] = result.Token,
            ["fingerprint"] = Fingerprint,
            ["server_name"] = "Friday",
            ["protocol"] = "1.0",
        });
    }

    private async Task<(int, JsonObject)> CommandAsync(HttpRequestLite request, PairedDevice device, string remote)
    {
        JsonObject? body;
        try { body = Parse(request.Body); }
        catch { return (400, Error("bad_request", "The body must be JSON.")); }

        var protocol = body?["protocol"]?.GetValue<string>() ?? "1.0";
        if (!protocol.StartsWith("1.", StringComparison.Ordinal))
            return (426, Error("protocol_mismatch",
                $"Friday speaks protocol 1.x; this app sent {protocol}. Update the app or Friday."));

        var text = (body?["text"]?.GetValue<string>() ?? "").Trim();
        var requestId = (body?["request_id"]?.GetValue<string>() ?? "").Trim();
        if (text.Length == 0) return (400, Error("bad_request", "The command text is empty."));
        if (text.Length > 4000) return (400, Error("bad_request", "That command is too long."));
        if (requestId.Length < 4 || requestId.Length > 100)
            return (400, Error("bad_request", "request_id is required (4-100 characters)."));

        if (_runtime.EmergencyStopped)
        {
            return (423, Error("emergency_stop",
                "Emergency stop is engaged on the laptop. Release it on Friday's screen first."));
        }

        // Idempotency: the same request id never runs twice.
        var existing = _runtime.Tasks.FindByRequest(requestId);
        if (existing is not null)
        {
            _runtime.Audit("command", "phone:" + device.Name, "duplicate", requestId, true, device.Id, requestId);
            return (200, TaskJson(existing, duplicate: true));
        }

        _runtime.Audit("command", "phone:" + device.Name, "accepted",
            $"{requestId}: {Clip(text, 300)}", true, device.Id, requestId);
        FLog.Info("phone", $"{device.Name} ({remote}): {Clip(text, 200)}");

        var task = _runtime.Tasks.Enqueue(text, "phone", device.Id, requestId);
        _runtime.Pairing.Touch(device.Id);

        // Give very fast commands a moment so the phone usually gets the answer
        // in one round trip. Long work still returns 202 with a task id.
        var quick = await _runtime.Tasks.WaitForAsync(task.Id, TimeSpan.FromSeconds(6)).ConfigureAwait(false);
        return quick is not null
            ? (200, TaskJson(quick))
            : (202, TaskJson(_runtime.Tasks.Get(task.Id) ?? task));
    }

    private (int, JsonObject) Approve(HttpRequestLite request, PairedDevice device)
    {
        JsonObject? body;
        try { body = Parse(request.Body); }
        catch { return (400, Error("bad_request", "The body must be JSON.")); }

        var approvalId = body?["approval_id"]?.GetValue<string>() ?? "";
        var approved = body?["approved"]?.GetValue<bool>() ?? false;
        var remember = body?["remember"]?.GetValue<bool>() ?? false;

        var pending = _runtime.Tasks.PendingApprovals()
            .FirstOrDefault(p => p.Id == approvalId);
        if (pending is null)
            return (404, Error("not_found", "That approval request is no longer waiting."));

        if (!_runtime.Config.Server.AllowRemoteApproval)
            return (403, Error("forbidden",
                "Remote approval is switched off. Approve it on the laptop screen."));

        var ok = _runtime.Tasks.ResolveApproval(approvalId, approved, remember);
        _runtime.Audit("approval", "phone:" + device.Name, approved ? "approved" : "denied",
            pending.Title + ": " + pending.Detail, ok, device.Id);
        return (200, new JsonObject
        {
            ["ok"] = ok,
            ["approval_id"] = approvalId,
            ["approved"] = approved,
            ["summary"] = ok
                ? (approved ? "Approved on the phone." : "Denied on the phone.")
                : "That approval was already resolved.",
        });
    }

    private JsonObject Status(PairedDevice device)
    {
        var running = _runtime.Tasks.Recent(50).Count(t => !t.IsTerminal);
        var tasks = _runtime.Tasks.Recent(6).Select(t => (JsonNode)TaskJson(t)).ToArray();
        return new JsonObject
        {
            ["ok"] = true,
            ["name"] = "Friday",
            ["version"] = "1.0.0",
            ["protocol"] = "1.0",
            ["machine"] = Environment.MachineName,
            ["state"] = _runtime.State.State.ToString().ToLowerInvariant(),
            ["detail"] = _runtime.State.Detail,
            ["ai_configured"] = _runtime.Brain.AiConfigured,
            ["voice"] = _runtime.Voice.Available,
            ["running_tasks"] = running,
            ["emergency_stop"] = _runtime.EmergencyStopped,
            ["fingerprint"] = Fingerprint,
            ["paired_devices"] = _runtime.Pairing.ActiveCount,
            ["device"] = new JsonObject
            {
                ["id"] = device.Id,
                ["name"] = device.Name,
                ["last_seen_at"] = device.LastSeenAt,
            },
            ["recent_tasks"] = new JsonArray(tasks),
            ["server_urls"] = new JsonArray(Urls().Select(u => (JsonNode)u!).ToArray()),
        };
    }

    private JsonObject Tasks()
    {
        var tasks = _runtime.Tasks.Recent(20).Select(t => (JsonNode)TaskJson(t)).ToArray();
        return new JsonObject { ["ok"] = true, ["tasks"] = new JsonArray(tasks) };
    }

    private JsonObject Approvals()
    {
        var pending = _runtime.Tasks.PendingApprovals();
        return new JsonObject
        {
            ["ok"] = true,
            ["approvals"] = new JsonArray(pending.Select(p => (JsonNode)new JsonObject
            {
                ["approval_id"] = p.Id,
                ["task_id"] = p.TaskId,
                ["title"] = p.Title,
                ["detail"] = p.Detail,
                ["capability"] = p.Capability.ToString().ToLowerInvariant(),
                ["destructive"] = p.Destructive,
                ["created_at"] = p.CreatedAt,
            }).ToArray()),
        };
    }

    private JsonObject TaskJson(FridayTask task, bool duplicate = false)
    {
        var json = new JsonObject
        {
            ["ok"] = true,
            ["duplicate"] = duplicate,
            ["request_id"] = task.RequestId,
            ["task_id"] = task.Id,
            ["status"] = StatusText(task),
            ["summary"] = task.Summary,
            ["progress"] = task.Progress,
            ["instruction"] = task.Instruction,
            ["source"] = task.Source,
            ["needs_approval"] = task.NeedsApproval,
            ["approval_id"] = task.ApprovalId,
            ["error"] = task.Error,
            ["created_at"] = task.CreatedAt,
            ["updated_at"] = task.UpdatedAt,
            ["is_terminal"] = task.IsTerminal,
        };

        if (task.Data is { Length: > 0 })
        {
            try { json["data"] = JsonNode.Parse(task.Data); }
            catch { json["data"] = task.Data; }
        }

        if (task.NeedsApproval && task.ApprovalId is not null)
        {
            var pending = _runtime.Tasks.PendingApprovals().FirstOrDefault(p => p.Id == task.ApprovalId);
            if (pending is not null)
            {
                json["approval"] = new JsonObject
                {
                    ["approval_id"] = pending.Id,
                    ["title"] = pending.Title,
                    ["detail"] = pending.Detail,
                    ["capability"] = pending.Capability.ToString().ToLowerInvariant(),
                    ["destructive"] = pending.Destructive,
                };
            }
        }

        return json;
    }

    private static string StatusText(FridayTask task) => task.Status switch
    {
        TaskStatus.Received => "received",
        TaskStatus.Validating => "validating",
        TaskStatus.AwaitingApproval => "awaiting_approval",
        TaskStatus.Running => "running",
        TaskStatus.Succeeded => "succeeded",
        TaskStatus.Failed => "failed",
        TaskStatus.Cancelled => "cancelled",
        _ => "unknown",
    };

    private static JsonObject EventJson(FridayEvent e) => new()
    {
        ["seq"] = e.Seq,
        ["type"] = e.Type,
        ["task_id"] = e.TaskId,
        ["status"] = e.Status,
        ["summary"] = e.Summary,
        ["at"] = e.At,
    };

    private PairedDevice? Authenticate(HttpRequestLite request, string remote, out string error)
    {
        error = "";
        var header = request.Header("Authorization");
        var token = header is not null && header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase)
            ? header[7..].Trim()
            : request.Header("X-Friday-Token")?.Trim();

        if (string.IsNullOrWhiteSpace(token))
        {
            error = "This endpoint needs a paired device token.";
            return null;
        }

        var device = _runtime.Pairing.Authenticate(token);
        if (device is null)
        {
            error = "That device token is not valid. Pair the phone again.";
            if (string.IsNullOrEmpty(remote)) remote = "?";
            var entry = _failures.AddOrUpdate(remote,
                _ => (1, DateTimeOffset.UtcNow),
                (_, old) => DateTimeOffset.UtcNow - old.First > TimeSpan.FromMinutes(10)
                    ? (1, DateTimeOffset.UtcNow)
                    : (old.Count + 1, old.First));

            FLog.Audit("auth", "unknown:" + remote, "rejected",
                $"bad token ({entry.Count} in 10 min)", false);
        }

        return device;
    }

    private bool RateOk(string deviceId)
    {
        var now = DateTimeOffset.UtcNow;
        var list = _rate.GetOrAdd(deviceId, _ => new List<DateTimeOffset>());
        lock (list)
        {
            list.RemoveAll(t => now - t > TimeSpan.FromMinutes(1));
            if (list.Count >= 120) return false;
            list.Add(now);
            return true;
        }
    }

    private static JsonObject? Parse(string body)
    {
        if (string.IsNullOrWhiteSpace(body)) return null;
        return JsonNode.Parse(body)?.AsObject();
    }

    private static string Query(HttpRequestLite request, string name) =>
        request.Query.TryGetValue(name, out var value) ? value : "";

    private static string? ExtractTaskId(string path, string suffix)
    {
        if (!path.EndsWith(suffix, StringComparison.OrdinalIgnoreCase)) return null;
        var trimmed = path[..^suffix.Length].TrimEnd('/');
        var slash = trimmed.LastIndexOf('/');
        var id = slash >= 0 ? trimmed[(slash + 1)..] : trimmed;
        return id.Length >= 3 ? id : null;
    }

    private static JsonObject Error(string code, string message) => new()
    {
        ["ok"] = false,
        ["error"] = new JsonObject { ["code"] = code, ["message"] = message },
        ["message"] = message,
    };

    private async Task WriteAsync(Stream stream, int status, JsonObject body, bool keepAlive,
        CancellationToken token)
    {
        var payload = Encoding.UTF8.GetBytes(body.ToJsonString(_json));
        var reason = status switch
        {
            200 => "OK",
            202 => "Accepted",
            400 => "Bad Request",
            401 => "Unauthorized",
            403 => "Forbidden",
            404 => "Not Found",
            405 => "Method Not Allowed",
            423 => "Locked",
            426 => "Upgrade Required",
            429 => "Too Many Requests",
            500 => "Internal Server Error",
            _ => "OK",
        };
        var header = $"HTTP/1.1 {status} {reason}\r\n" +
                     "Content-Type: application/json; charset=utf-8\r\n" +
                     "Cache-Control: no-store\r\n" +
                     "X-Content-Type-Options: nosniff\r\n" +
                     "Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n" +
                     $"Content-Length: {payload.Length}\r\n\r\n";
        await stream.WriteAsync(Encoding.ASCII.GetBytes(header), token).ConfigureAwait(false);
        await stream.WriteAsync(payload, token).ConfigureAwait(false);
        await stream.FlushAsync(token).ConfigureAwait(false);
    }

    private static string Clip(string text, int max) =>
        string.IsNullOrEmpty(text) ? "" : (text.Length <= max ? text : text[..max] + "…");

    public void Dispose()
    {
        Stop();
        try { _cts?.Dispose(); } catch { }
    }
}
