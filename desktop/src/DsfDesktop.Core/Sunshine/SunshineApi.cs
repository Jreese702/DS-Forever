using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace DsfDesktop.Sunshine
{
    public sealed class PendingPairing
    {
        public string Id;
        public Dictionary<string, object> Raw;
        public override string ToString() => Id + " " + Json.Write(Raw);
    }

    /// <summary>
    /// Sunshine's local web API (https://127.0.0.1:&lt;base+1&gt;/api/...), basic auth with the credentials our config set. Only this PC
    /// can reach it (origin_web_ui_allowed = pc). Measured against Sunshine 2026.914.233613:
    ///  - GET  /api/pin           -> {"pairings":[{"id":"&lt;32 hex&gt;", ...}]}: Moonlight clients waiting in their pair handshake;
    ///  - POST /api/pin           {"pairing_id","pin","name"} -> {"status":true|false}; the pin must be EXACTLY 4 digits;
    ///  - GET  /api/clients/list  -> {"named_certs":[...],"status":true};
    ///  - GET  /api/csrf-token    -> {"csrf_token":"..."}, sent back as X-CSRF-Token on POSTs (requests without an Origin pass today,
    ///    the token keeps us right if that tightens).
    /// The TLS certificate is Sunshine's own self-signed one: accepted only for 127.0.0.1, and pinned to our cert file when it exists.
    /// </summary>
    public sealed class SunshineApi : IDisposable
    {
        readonly HttpClient http;
        readonly string baseUrl;
        readonly string certFile;
        string csrf;

        static SunshineApi() { ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12; }

        public SunshineApi(int basePort, string user, string pass, string certFile = null)
        {
            this.certFile = certFile;
            baseUrl = "https://127.0.0.1:" + (basePort + 1);
            var h = new HttpClientHandler { ServerCertificateCustomValidationCallback = CheckCert, UseProxy = false };
            // per-request timeouts (Within): a PIN submit waits on the device's handshake, everything else is quick
            http = new HttpClient(h) { Timeout = System.Threading.Timeout.InfiniteTimeSpan };
            http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Basic", Convert.ToBase64String(Encoding.UTF8.GetBytes(user + ":" + pass)));
        }

        bool CheckCert(HttpRequestMessage req, X509Certificate2 cert, X509Chain chain, System.Net.Security.SslPolicyErrors errors)
        {
            if (req.RequestUri.Host != "127.0.0.1") return false;
            try
            {
                if (certFile != null && File.Exists(certFile))
                {
                    var pem = File.ReadAllText(certFile);
                    var b64 = string.Concat(pem.Split('\n').Where(l => !l.StartsWith("-----")).Select(l => l.Trim()));
                    var mine = new X509Certificate2(Convert.FromBase64String(b64));
                    return mine.Thumbprint == cert.Thumbprint;
                }
            }
            catch (Exception) { }
            return true;
        }

        public void Dispose() => http.Dispose();

        static CancellationTokenSource Within(CancellationToken ct, int seconds)
        {
            var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(TimeSpan.FromSeconds(seconds));
            return cts;
        }

        async Task<Dictionary<string, object>> Get(string path, CancellationToken outer)
        {
            using (var cts = Within(outer, 10))
            using (var r = await http.GetAsync(baseUrl + path, cts.Token).ConfigureAwait(false))
            {
                var body = await r.Content.ReadAsStringAsync().ConfigureAwait(false);
                if (r.StatusCode == HttpStatusCode.Unauthorized) throw new UnauthorizedAccessException("Sunshine refused our login");
                return Json.ReadObject(body) ?? new Dictionary<string, object> { ["_status"] = (int)r.StatusCode, ["_body"] = body };
            }
        }

        async Task<Dictionary<string, object>> Post(string path, object body, CancellationToken outer, int seconds = 10)
        {
            using (var cts = Within(outer, seconds))
                return await PostIn(path, body, cts.Token).ConfigureAwait(false);
        }

        async Task<Dictionary<string, object>> PostIn(string path, object body, CancellationToken ct)
        {
            if (csrf == null) csrf = (await Get("/api/csrf-token", ct).ConfigureAwait(false)).Str("csrf_token");
            for (int attempt = 0; attempt < 2; attempt++)
            {
                var req = new HttpRequestMessage(HttpMethod.Post, baseUrl + path) { Content = new StringContent(Json.Write(body), Encoding.UTF8, "application/json") };
                if (csrf != null) req.Headers.TryAddWithoutValidation("X-CSRF-Token", csrf);
                using (var r = await http.SendAsync(req, ct).ConfigureAwait(false))
                {
                    var text = await r.Content.ReadAsStringAsync().ConfigureAwait(false);
                    if (r.StatusCode == HttpStatusCode.Unauthorized) throw new UnauthorizedAccessException("Sunshine refused our login");
                    var o = Json.ReadObject(text) ?? new Dictionary<string, object> { ["_status"] = (int)r.StatusCode, ["_body"] = text };
                    var err = o.Str("error") ?? "";
                    if (attempt == 0 && err.IndexOf("CSRF", StringComparison.OrdinalIgnoreCase) >= 0)
                    {
                        csrf = (await Get("/api/csrf-token", ct).ConfigureAwait(false)).Str("csrf_token");
                        continue;
                    }
                    return o;
                }
            }
            return new Dictionary<string, object>();
        }

        public async Task<bool> Alive(CancellationToken ct)
        {
            try { return (await Get("/api/clients/list", ct).ConfigureAwait(false)).ContainsKey("named_certs"); }
            catch (Exception) { return false; }
        }

        public async Task<List<PendingPairing>> Pending(CancellationToken ct)
        {
            var o = await Get("/api/pin", ct).ConfigureAwait(false);
            var res = new List<PendingPairing>();
            if (o.TryGetValue("pairings", out var arr) && arr is object[] items)
                foreach (var it in items.OfType<Dictionary<string, object>>())
                    res.Add(new PendingPairing { Id = it.Str("id"), Raw = it });
            return res;
        }

        public async Task<Dictionary<string, object>> SubmitPin(string pairingId, string pin, string name, CancellationToken ct)
            => await Post("/api/pin", new Dictionary<string, object> { ["pairing_id"] = pairingId, ["pin"] = pin, ["name"] = name }, ct, PinWaitSeconds).ConfigureAwait(false);

        /// <summary>
        /// Sunshine answers a PIN submit only when the device's handshake has gone through (measured: at once with the right PIN). With
        /// a wrong PIN the device stops mid-handshake and the submit is never answered: after this long it counts as refused.
        /// </summary>
        public int PinWaitSeconds = 20;

        public async Task<List<Dictionary<string, object>>> Clients(CancellationToken ct)
        {
            var o = await Get("/api/clients/list", ct).ConfigureAwait(false);
            if (o.TryGetValue("named_certs", out var arr) && arr is object[] items) return items.OfType<Dictionary<string, object>>().ToList();
            return new List<Dictionary<string, object>>();
        }

        public async Task<bool> Unpair(string uuid, CancellationToken ct)
            => (await Post("/api/clients/unpair", new Dictionary<string, object> { ["uuid"] = uuid }, ct).ConfigureAwait(false)).Bool("status");

        /// <summary>
        /// The pairing step our bridge runs once the Thor has proved the 6-digit code: wait (up to timeout) for the Thor's Moonlight
        /// client to appear as a pending pairing, then hand Sunshine the one-time PIN we gave the Thor. With several pending at once
        /// the one whose fields mention the peer's address or name is taken; failing that, the newest (the last listed).
        /// </summary>
        public async Task<string> CompletePairing(string pin, string name, string peerHint, TimeSpan timeout, CancellationToken ct)
        {
            var until = DateTime.UtcNow + timeout;
            var tried = new HashSet<string>();
            while (DateTime.UtcNow < until && !ct.IsCancellationRequested)
            {
                List<PendingPairing> list;
                try { list = await Pending(ct).ConfigureAwait(false); }
                catch (UnauthorizedAccessException) { throw; }
                catch (Exception) { list = new List<PendingPairing>(); }
                var open = list.Where(p => p.Id != null && !tried.Contains(p.Id)).ToList();
                if (open.Count > 0)
                {
                    var pick = open.FirstOrDefault(p => Mentions(p.Raw, peerHint)) ?? open.FirstOrDefault(p => Mentions(p.Raw, name)) ?? open.Last();
                    tried.Add(pick.Id);
                    Dictionary<string, object> r;
                    try { r = await SubmitPin(pick.Id, pin, name, ct).ConfigureAwait(false); }
                    catch (OperationCanceledException) when (!ct.IsCancellationRequested)
                    {
                        return "the device didn't finish the stream pairing (a wrong PIN, or it gave up)";
                    }
                    if (r.Bool("status")) return null;
                    Log.I("sunshine pin refused for " + pick + ": " + Json.Write(r));
                    // with others waiting, the pick may have been another device's request: try those; else it was ours
                    if (open.Count == 1) return "the stream pairing was refused (the PIN didn't match)";
                }
                await Task.Delay(400, ct).ConfigureAwait(false);
            }
            return "the device's stream pairing didn't arrive in time";
        }

        static bool Mentions(Dictionary<string, object> raw, string hint)
        {
            if (raw == null || string.IsNullOrEmpty(hint)) return false;
            return raw.Values.Any(v => v != null && Convert.ToString(v).IndexOf(hint, StringComparison.OrdinalIgnoreCase) >= 0);
        }
    }
}
