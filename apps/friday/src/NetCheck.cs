using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Friday.Core;
using Friday.Net;
using TaskStatus = Friday.Core.TaskStatus;

namespace Friday;

/// <summary>
/// End-to-end check of the phone-facing service over a real TLS socket, using
/// the same requests the Android client sends (`friday/FridayClient.kt`).
///
/// It runs against a throw-away home directory (see --netcheck in Program.cs),
/// so it never touches the real configuration, device list or audit log. Every
/// step is a real HTTP round trip: ping, a rejected pairing code, pairing, token
/// authentication, an idempotent duplicate, a refused command, a real approval,
/// emergency stop and revocation.
/// </summary>
public static class NetCheck
{
    public static async Task<int> RunAsync()
    {
        var lines = new List<string>();
        var failures = 0;

        void Say(string line)
        {
            lines.Add(line);
            Console.WriteLine(line);
        }

        void Check(string name, Func<bool> test)
        {
            bool ok;
            try { ok = test(); }
            catch (Exception ex)
            {
                ok = false;
                Say("  ERROR " + ex.GetType().Name + ": " + ex.Message);
            }
            if (!ok) failures++;
            Say((ok ? "PASS  " : "FAIL  ") + name);
        }

        Say("Friday network self-test " + DateTimeOffset.Now.ToString("u"));
        Say("Home: " + AppPaths.Home);

        var config = FridayConfig.Load();
        config.Server.Enabled = true;
        config.Server.AllowLan = false;
        config.Server.Port = 18790;
        config.Server.UseTls = true;
        config.Server.RequirePairing = true;
        config.Server.AllowRemoteApproval = true;
        config.Policy.AllowFreeformShell = false;
        config.Voice.Enabled = false;
        config.Startup.LaunchAtLogin = false;

        using var runtime = new FridayRuntime(config);
        runtime.Voice.Dispose();
        runtime.Tasks.Start();
        runtime.Server.Start();

        Check("server starts on loopback", () => runtime.Server.Running);
        if (!runtime.Server.Running)
        {
            Say("RESULT: network checks could not run - " + runtime.Server.LastError);
            return failures == 0 ? 1 : failures;
        }

        // The loopback IP, not "localhost": on Windows "localhost" may be tried as
        // ::1 first and fall back after a ~2 s delay, which would drown out the
        // numbers this check exists to report.
        var baseUrl = $"https://127.0.0.1:{runtime.Server.Port}";
        var pin = runtime.Server.Fingerprint;
        Say("  url: " + baseUrl);
        Say("  pinned fingerprint: " + pin);
        Check("server publishes a SHA-256 fingerprint", () => pin.Length == 64);

        using var http = Client(baseUrl, pin);
        using var wrongPin = Client(baseUrl, new string('0', 64));

        // ---- pinning ---------------------------------------------------
        var wrongPinRefused = false;
        try
        {
            using var response = await wrongPin.GetAsync("/v1/ping").ConfigureAwait(false);
            _ = response.StatusCode;
        }
        catch (HttpRequestException)
        {
            wrongPinRefused = true;
        }
        Check("a different certificate is refused before any request is served", () => wrongPinRefused);

        // ---- ping ------------------------------------------------------
        JsonNode? ping = null;
        var pingOk = false;
        try
        {
            using var response = await http.GetAsync("/v1/ping").ConfigureAwait(false);
            var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
            ping = JsonNode.Parse(body);
            pingOk = response.IsSuccessStatusCode && ping?["name"]?.GetValue<string>() == "Friday";
        }
        catch (Exception ex)
        {
            Say("  ping error: " + ex.Message);
        }
        Check("ping answers without a token", () => pingOk);
        Check("ping reports the same pinned fingerprint",
            () => ping?["fingerprint"]?.GetValue<string>() == pin);
        Check("ping reports protocol 1.0",
            () => ping?["protocol"]?.GetValue<string>() == "1.0");

        // ---- authentication before pairing -----------------------------
        Check("status without a token is 401",
            () => Status(http, "/v1/status") == 401);

        // ---- pairing ---------------------------------------------------
        Check("a wrong pairing code is refused",
            () => Post(http, "/v1/pair", new JsonObject
            {
                ["code"] = "ZZZZZZZZ",
                ["device_name"] = "netcheck",
                ["platform"] = "android",
            }).GetAwaiter().GetResult().Status == 403);

        var code = runtime.Pairing.CurrentCode;
        var pair = await Post(http, "/v1/pair", new JsonObject
        {
            ["code"] = code,
            ["device_name"] = "netcheck",
            ["platform"] = "android",
        }).ConfigureAwait(false);
        var pairJson = TryParse(pair.Body);
        var token = pairJson?["token"]?.GetValue<string>() ?? "";
        var deviceId = pairJson?["device_id"]?.GetValue<string>() ?? "";
        Check("pairing code returns a device token",
            () => pair.Status == 200 && token.Length >= 20 && deviceId.Length > 0);
        Check("pairing response carries the certificate fingerprint",
            () => pairJson?["fingerprint"]?.GetValue<string>() == pin);

        var reused = await Post(http, "/v1/pair", new JsonObject
        {
            ["code"] = code,
            ["device_name"] = "netcheck",
            ["platform"] = "android",
        }).ConfigureAwait(false);
        Check("a pairing code cannot be used twice", () => reused.Status == 403);

        var authed = Client(baseUrl, pin, token);
        var status = await Get(authed, "/v1/status").ConfigureAwait(false);
        Check("the paired token authenticates", () => status.Status == 200 && status.Json?["ok"]?.GetValue<bool>() == true);
        Check("status names the laptop and the device",
            () => status.Json?["machine"]?.GetValue<string>()?.Length > 0 &&
                  status.Json?["device"]?["id"]?.GetValue<string>() == deviceId);

        // ---- command + idempotency -------------------------------------
        var requestId = "netcheck-" + Guid.NewGuid().ToString("n")[..10];
        var commandClock = System.Diagnostics.Stopwatch.StartNew();
        var command = await Post(authed, "/v1/command", new JsonObject
        {
            ["protocol"] = "1.0",
            ["text"] = "what time is it",
            ["request_id"] = requestId,
        }).ConfigureAwait(false);
        commandClock.Stop();
        var taskId = command.Json?["task_id"]?.GetValue<string>() ?? "";
        Check("a command is accepted and runs to success",
            () => command.Json?["status"]?.GetValue<string>() == "succeeded" && taskId.Length > 0);
        Say($"  command round trip: {commandClock.ElapsedMilliseconds} ms");

        var duplicate = await Post(authed, "/v1/command", new JsonObject
        {
            ["protocol"] = "1.0",
            ["text"] = "what time is it",
            ["request_id"] = requestId,
        }).ConfigureAwait(false);
        Check("the same request id is not run twice",
            () => duplicate.Json?["duplicate"]?.GetValue<bool>() == true &&
                  duplicate.Json?["task_id"]?.GetValue<string>() == taskId);

        // ---- latency sample (local command, no AI key involved) ----------
        var samples = new List<long>();
        for (var i = 0; i < 10; i++)
        {
            var clock = System.Diagnostics.Stopwatch.StartNew();
            await Post(authed, "/v1/command", new JsonObject
            {
                ["protocol"] = "1.0",
                ["text"] = "what time is it",
                ["request_id"] = requestId + "-lat-" + i,
            }).ConfigureAwait(false);
            clock.Stop();
            samples.Add(clock.ElapsedMilliseconds);
        }
        samples.Sort();
        Say($"  local command latency over 10 calls: median {samples[samples.Count / 2]} ms, " +
            $"best {samples[0]} ms, worst {samples[^1]} ms");

        var badProtocol = await Post(authed, "/v1/command", new JsonObject
        {
            ["protocol"] = "2.0",
            ["text"] = "what time is it",
            ["request_id"] = "netcheck-proto-" + requestId,
        }).ConfigureAwait(false);
        Check("an unknown protocol is refused with 426", () => badProtocol.Status == 426);

        // ---- a blocked command ----------------------------------------
        var blocked = await Post(authed, "/v1/command", new JsonObject
        {
            ["protocol"] = "1.0",
            ["text"] = "run `format C: /y`",
            ["request_id"] = "netcheck-blocked-" + requestId,
        }).ConfigureAwait(false);
        var blockedFinal = await WaitAsync(authed, blocked.Json?["task_id"]?.GetValue<string>() ?? "").ConfigureAwait(false);
        Check("a blocked command ends failed, not running",
            () => blockedFinal?["status"]?.GetValue<string>() == "failed" &&
                  (blockedFinal?["error"]?.GetValue<string>() ?? "")
                      .Contains("blocked", StringComparison.OrdinalIgnoreCase));

        // ---- approval over the wire -----------------------------------
        config.Policy.AllowFreeformShell = true;
        var gated = await Post(authed, "/v1/command", new JsonObject
        {
            ["protocol"] = "1.0",
            ["text"] = "run `Get-Location`",
            ["request_id"] = "netcheck-gated-" + requestId,
        }).ConfigureAwait(false);
        var gatedId = gated.Json?["task_id"]?.GetValue<string>() ?? "";

        var approvalId = "";
        var deadline = DateTimeOffset.UtcNow.AddSeconds(20);
        while (DateTimeOffset.UtcNow < deadline)
        {
            var approvals = await Get(authed, "/v1/approvals").ConfigureAwait(false);
            var first = approvals.Json?["approvals"]?.AsArray().FirstOrDefault();
            approvalId = first?["approval_id"]?.GetValue<string>() ?? "";
            if (approvalId.Length > 0) break;
            await Task.Delay(200).ConfigureAwait(false);
        }
        Check("a command outside the allowlist waits for approval", () => approvalId.Length > 0);

        var approved = await Post(authed, "/v1/approve", new JsonObject
        {
            ["approval_id"] = approvalId,
            ["approved"] = true,
            ["remember"] = false,
        }).ConfigureAwait(false);
        Check("approval from the phone is accepted", () => approved.Json?["ok"]?.GetValue<bool>() == true);

        var gatedFinal = await WaitAsync(authed, gatedId).ConfigureAwait(false);
        Check("the approved command then runs to success", () => gatedFinal?["status"]?.GetValue<string>() == "succeeded");
        config.Policy.AllowFreeformShell = false;

        // ---- emergency stop -------------------------------------------
        var stop = await Post(authed, "/v1/emergency-stop", new JsonObject { ["engage"] = true }).ConfigureAwait(false);
        Check("emergency stop engages over the wire",
            () => stop.Json?["emergency_stop"]?.GetValue<bool>() == true);

        var refused = await Post(authed, "/v1/command", new JsonObject
        {
            ["protocol"] = "1.0",
            ["text"] = "what time is it",
            ["request_id"] = "netcheck-stop-" + requestId,
        }).ConfigureAwait(false);
        Check("commands are refused with 423 while stopped", () => refused.Status == 423);

        var release = await Post(authed, "/v1/emergency-stop", new JsonObject { ["engage"] = false }).ConfigureAwait(false);
        Check("emergency stop releases",
            () => release.Json?["emergency_stop"]?.GetValue<bool>() == false);

        // ---- revocation ------------------------------------------------
        var revoke = await Post(authed, "/v1/devices/revoke", new JsonObject { ["device_id"] = deviceId }).ConfigureAwait(false);
        Check("the phone can revoke its own device", () => revoke.Json?["ok"]?.GetValue<bool>() == true);
        Check("a revoked token stops working",
            () => Status(authed, "/v1/status") == 401);

        Say("");
        Say(failures == 0 ? "RESULT: all network checks passed" : $"RESULT: {failures} network check(s) failed");
        Say("requests served: " + runtime.Server.RequestCount);

        try
        {
            var report = string.Join(Environment.NewLine, lines);
            System.IO.File.WriteAllText(
                System.IO.Path.Combine(AppPaths.LogsDir, "netcheck.txt"), report, new UTF8Encoding(false));
        }
        catch
        {
            // The console copy is the primary output.
        }

        runtime.Server.Stop();
        return failures == 0 ? 0 : 1;
    }

    private sealed record Reply(int Status, string Body, JsonNode? Json);

    private static HttpClient Client(string baseUrl, string pin, string? token = null)
    {
        var handler = new SocketsHttpHandler
        {
            SslOptions =
            {
                RemoteCertificateValidationCallback = (_, certificate, _, _) =>
                {
                    if (certificate is null) return false;
                    var actual = Convert.ToHexString(SHA256.HashData(certificate.GetRawCertData()));
                    return actual.Equals(pin, StringComparison.OrdinalIgnoreCase);
                },
            },
        };
        var client = new HttpClient(handler)
        {
            BaseAddress = new Uri(baseUrl),
            Timeout = TimeSpan.FromSeconds(30),
        };
        if (!string.IsNullOrEmpty(token))
            client.DefaultRequestHeaders.Authorization =
                new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", token);
        return client;
    }

    private static async Task<Reply> Get(HttpClient client, string path)
    {
        using var response = await client.GetAsync(path).ConfigureAwait(false);
        var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
        return new Reply((int)response.StatusCode, body, TryParse(body));
    }

    private static async Task<Reply> Post(HttpClient client, string path, JsonObject payload)
    {
        using var content = new StringContent(payload.ToJsonString(), Encoding.UTF8, "application/json");
        using var response = await client.PostAsync(path, content).ConfigureAwait(false);
        var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
        return new Reply((int)response.StatusCode, body, TryParse(body));
    }

    private static int Status(HttpClient client, string path) => Get(client, path).GetAwaiter().GetResult().Status;

    private static JsonNode? TryParse(string body)
    {
        try { return JsonNode.Parse(body); }
        catch { return null; }
    }

    /// <summary>Polls /v1/task/{id} until it reaches a terminal state.</summary>
    private static async Task<JsonNode?> WaitAsync(HttpClient client, string taskId)
    {
        if (taskId.Length == 0) return null;
        var deadline = DateTimeOffset.UtcNow.AddSeconds(40);
        while (DateTimeOffset.UtcNow < deadline)
        {
            var reply = await Get(client, "/v1/task/" + taskId).ConfigureAwait(false);
            if (reply.Status != 200) return null;
            var json = reply.Json;
            if (json?["is_terminal"]?.GetValue<bool>() == true) return json;
            await Task.Delay(300).ConfigureAwait(false);
        }
        return null;
    }
}
