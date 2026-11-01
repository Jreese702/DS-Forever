using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Reflection;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using DsfDesktop.GameLog;
using DsfDesktop.Pairing;

namespace DsfDesktop.Bridge
{
    /// <summary>what the bridge asks of the app (implemented by AppController; tests use a fake)</summary>
    public interface IBridgeHost
    {
        /// <summary>public facts for /v1/info and the welcome (no secrets)</summary>
        Dictionary<string, object> Info();
        PairingCodes Codes { get; }
        DeviceStore Devices { get; }
        /// <summary>Sunshine's half of pairing: hand it the PIN when the device's Moonlight handshake arrives; done(null) = paired</summary>
        void StreamPair(string deviceId, string name, string pin, string peerIp, Action<string> done);
        Dictionary<string, object> Focus();
        /// <summary>Battle.net's sign-in window in front and its form found, or why not (BnetForm); keys are never read or logged here</summary>
        Dictionary<string, object> BnetReady();
        /// <summary>the Thor's saved login for WoW's login.txt; never logged</summary>
        void LoginFile(string email, string password);
        Dictionary<string, object> AddonsStatus();
        Dictionary<string, object> AddonsInstall();
    }

    /// <summary>the documents since the current log began (what a fresh LogTail would read from the file), capped</summary>
    public sealed class DocHub
    {
        public const long MaxChars = 8L * 1024 * 1024;
        public const int MaxDocs = 50_000;
        readonly LinkedList<string> docs = new LinkedList<string>();
        long chars;

        public int Count => docs.Count;

        public void Add(string msg)
        {
            docs.AddLast(msg);
            chars += msg.Length;
            while (docs.Count > 0 && (chars > MaxChars || docs.Count > MaxDocs))
            {
                chars -= docs.First.Value.Length;
                docs.RemoveFirst();
            }
        }

        public void Clear() { docs.Clear(); chars = 0; }
        public List<string> Snapshot() => docs.ToList();
    }

    public sealed class BridgeSession
    {
        public readonly WsConn Ws;
        public readonly string DeviceId, Name, Peer;
        public readonly long SinceMs = DeviceStore.NowMs();
        readonly BlockingCollection<string> queue = new BlockingCollection<string>(8192);

        public BridgeSession(WsConn ws, string id, string name, string peer) { Ws = ws; DeviceId = id; Name = name; Peer = peer; }

        public void Enqueue(Dictionary<string, object> msg) => Enqueue(Json.Write(msg));

        public void Enqueue(string msg)
        {
            try
            {
                // a client that cannot keep up is dropped; it reconnects and gets the backlog again
                if (!queue.TryAdd(msg)) { Log.I("bridge: " + Name + " too slow, dropped"); queue.CompleteAdding(); Ws.Close(1008); }
            }
            catch (InvalidOperationException) { }
        }

        public void StartWriter()
        {
            new Thread(() =>
            {
                try { foreach (var m in queue.GetConsumingEnumerable()) Ws.SendText(m); }
                catch (Exception) { Ws.Close(1011); }
            }) { IsBackground = true, Name = "bridge-send" }.Start();
        }

        public void Stop() { try { queue.CompleteAdding(); } catch (Exception) { } }
    }

    /// <summary>
    /// The bridge, docs/STREAM_BRIDGE_PROTOCOL.md v1: GET /v1/info, the WebSocket at /v1/ws (pairing by the code, proved with an HMAC,
    /// then the token-protected stream of the add-on's documents and the control messages), and a loopback-only test page at /test.
    /// The same port also speaks TLS (wss://, Desktop's self-signed certificate, fingerprint in the mDNS TXT "fp" with "tls=1"):
    /// the first byte of a connection tells a TLS ClientHello (0x16) from plain HTTP.
    /// </summary>
    public sealed class BridgeServer : IDisposable
    {
        public const int DefaultPort = 48210;
        public const int Proto = 1;

        readonly IBridgeHost host;
        readonly IPAddress bind;
        readonly int wantPort;
        readonly X509Certificate2 cert;
        readonly object gate = new object();
        readonly List<BridgeSession> sessions = new List<BridgeSession>();
        readonly DocHub hub = new DocHub();
        readonly SemaphoreSlim pairGate = new SemaphoreSlim(1, 1);
        TcpListener listener;
        Timer pinger;
        volatile bool running;

        /// <summary>a wrong code's reply waits this long (serialised across connections): with 20 wrong codes pausing pairing, guessing is hopeless</summary>
        public TimeSpan WrongCodeDelay = TimeSpan.FromSeconds(1);
        /// <summary>how long the bridge waits for the device's Moonlight pairing request to reach Sunshine</summary>
        public int StreamPairSeconds = 90;
        /// <summary>no frame at all for this long (the Thor pings when idle 15 s) closes a session</summary>
        public int IdleMs = 60_000;
        public event Action SessionsChanged;
        public int Port { get; private set; }
        public bool Tls => cert != null;
        public string Fingerprint => cert == null ? null : BridgeCert.Fingerprint(cert);

        public BridgeServer(IBridgeHost host, X509Certificate2 cert = null, int port = DefaultPort, IPAddress bind = null)
        {
            this.host = host;
            this.cert = cert;
            wantPort = port;
            this.bind = bind ?? IPAddress.Any;
        }

        public void Start()
        {
            listener = new TcpListener(bind, wantPort);
            listener.Start();
            Port = ((IPEndPoint)listener.LocalEndpoint).Port;
            running = true;
            new Thread(AcceptLoop) { IsBackground = true, Name = "bridge-accept" }.Start();
            pinger = new Timer(_ => PingAll(), null, 15000, 15000);
            Log.I("bridge listening on " + bind + ":" + Port + (Tls ? " (ws and wss, fingerprint " + Fingerprint + ")" : " (ws)"));
        }

        public void Stop()
        {
            running = false;
            try { listener?.Stop(); } catch (Exception) { }
            pinger?.Dispose();
            List<BridgeSession> all;
            lock (gate) { all = sessions.ToList(); sessions.Clear(); }
            foreach (var s in all) { s.Stop(); s.Ws.Close(1001); }
        }

        public void Dispose() => Stop();

        public List<BridgeSession> Sessions() { lock (gate) return sessions.ToList(); }
        public int DocCount { get { lock (gate) return hub.Count; } }

        // ---------------------------------------------------------------- pushes from the app

        public void PublishDoc(LogEvent e)
        {
            var msg = Json.Write(new Dictionary<string, object> { ["t"] = "doc", ["seq"] = e.Seq, ["gt"] = e.GameTime, ["doc"] = e.Text });
            lock (gate)
            {
                hub.Add(msg);
                foreach (var s in sessions) s.Enqueue(msg);
            }
        }

        /// <summary>event: "restart" (the client emptied its log) or "switch" (another WoW log is followed); both empty the backlog</summary>
        public void PublishLog(string evt, string path, string flavor)
        {
            var msg = Json.Write(new Dictionary<string, object> { ["t"] = "log", ["event"] = evt, ["path"] = path, ["flavor"] = flavor });
            lock (gate)
            {
                hub.Clear();
                foreach (var s in sessions) s.Enqueue(msg);
            }
        }

        public void Publish(Dictionary<string, object> msg)
        {
            var text = Json.Write(msg);
            lock (gate) foreach (var s in sessions) s.Enqueue(text);
        }

        void PingAll()
        {
            foreach (var s in Sessions())
                try { s.Ws.SendPing(); } catch (Exception) { s.Ws.Close(1011); }
        }

        // ---------------------------------------------------------------- connections

        void AcceptLoop()
        {
            while (running)
            {
                TcpClient c;
                try { c = listener.AcceptTcpClient(); }
                catch (Exception) { if (!running) return; continue; }
                new Thread(() => Handle(c)) { IsBackground = true, Name = "bridge-conn" }.Start();
            }
        }

        bool OriginAllowed(string origin)
        {
            // a native client (OkHttp on the Thor) sends no Origin. A browser always does: only our own test page's is accepted,
            // so no web page the user visits can talk to the bridge
            if (string.IsNullOrEmpty(origin)) return true;
            return origin == "http://localhost:" + Port || origin == "http://127.0.0.1:" + Port;
        }

        void Handle(TcpClient c)
        {
            using (c)
            {
                try
                {
                    c.NoDelay = true;
                    var ns = c.GetStream();
                    ns.ReadTimeout = 15000;
                    var peer = (IPEndPoint)c.Client.RemoteEndPoint;
                    bool local = IPAddress.IsLoopback(peer.Address);
                    int first = ns.ReadByte();
                    if (first < 0) return;
                    Stream s = new PrefixStream((byte)first, ns);
                    if (first == 0x16)   // a TLS ClientHello: wss / https
                    {
                        if (cert == null) return;
                        var ssl = new SslStream(s, false);
                        ssl.AuthenticateAsServer(cert, false, SslProtocols.Tls12, false);
                        s = ssl;
                    }
                    var req = HttpRequest.Read(s);
                    if (req == null) return;
                    if (req.Method != "GET") { HttpRequest.Respond(s, 405, "Method Not Allowed", "text/plain", "GET only"); return; }
                    switch (req.Path)
                    {
                        case "/v1/info":
                            HttpRequest.Respond(s, 200, "OK", "application/json", Json.Write(InfoMsg("info")));
                            return;
                        case "/test":
                            if (!local) { HttpRequest.Respond(s, 403, "Forbidden", "text/plain", "this PC only"); return; }
                            HttpRequest.Respond(s, 200, "OK", "text/html; charset=utf-8", TestPage());
                            return;
                        case "/v1/ws":
                            if (!req.IsUpgrade) { HttpRequest.Respond(s, 400, "Bad Request", "text/plain", "WebSocket upgrade expected"); return; }
                            if (!OriginAllowed(req.Header("Origin"))) { HttpRequest.Respond(s, 403, "Forbidden", "text/plain", "origin not allowed"); return; }
                            var accept = WsConn.AcceptKey(req.Header("Sec-WebSocket-Key"));
                            var h = Encoding.ASCII.GetBytes("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n");
                            s.Write(h, 0, h.Length);
                            s.Flush();
                            RunWs(new WsConn(s), s, peer);
                            return;
                        default:
                            HttpRequest.Respond(s, 404, "Not Found", "text/plain", "not found");
                            return;
                    }
                }
                catch (Exception e) { Log.I("bridge connection: " + e.Message); }
            }
        }

        Dictionary<string, object> InfoMsg(string t)
        {
            var m = new Dictionary<string, object> { ["t"] = t, ["proto"] = Proto, ["bridge_port"] = Port };
            if (Tls) { m["tls"] = true; m["fp"] = Fingerprint; }
            foreach (var kv in host.Info() ?? new Dictionary<string, object>()) m[kv.Key] = kv.Value;
            return m;
        }

        sealed class PairAttempt { public string Id, Name, Nonce; public bool Stream = true; }

        static Dictionary<string, object> Err(string error, string detail = null)
        {
            var d = new Dictionary<string, object> { ["t"] = "error", ["error"] = error };
            if (detail != null) d["detail"] = detail;
            return d;
        }

        static void SendNow(WsConn ws, Dictionary<string, object> m) { try { ws.SendText(Json.Write(m)); } catch (Exception) { } }
        static void SetTimeout(Stream s, int ms) { try { s.ReadTimeout = ms; } catch (Exception) { } }

        void RunWs(WsConn ws, Stream st, IPEndPoint peer)
        {
            BridgeSession s = null;
            PairAttempt pair = null;
            SetTimeout(st, 120_000);  // before authentication: hello now, or a pairing the user is typing a code for
            try
            {
                while (true)
                {
                    var text = ws.ReadText();
                    if (text == null) break;
                    var m = Json.ReadObject(text);
                    var t = m.Str("t");
                    if (t == null) { if (s != null) s.Enqueue(Err("bad_message")); else SendNow(ws, Err("bad_message")); continue; }

                    if (s == null)
                    {
                        if (t == "hello")
                        {
                            var id = m.Str("device_id");
                            var check = host.Devices.Check(id, m.Str("token"));
                            if (check != TokenCheck.Ok)
                            {
                                SendNow(ws, Err("unauthorized", check == TokenCheck.Revoked
                                    ? "this device was removed in DS Forever Desktop: pair again with the code on the PC"
                                    : "unknown device or token: pair again with the code on the PC"));
                                ws.Close(4401);
                                break;
                            }
                            var dev = host.Devices.All().FirstOrDefault(d => d.Id == id);
                            s = Authenticate(ws, st, id, dev?.Name ?? id, peer);
                        }
                        else if (t == "pair_hello")
                        {
                            var id = DeviceStore.Clean(m.Str("device_id"), 128);
                            if (id.Length == 0) { SendNow(ws, Err("bad_message", "device_id required")); continue; }
                            pair = new PairAttempt { Id = id, Name = DeviceStore.Clean(m.Str("name"), 64), Nonce = Crypto.Hex(Crypto.RandomBytes(16)), Stream = !m.ContainsKey("stream") || m.Bool("stream") };
                            SendNow(ws, new Dictionary<string, object>
                            {
                                ["t"] = "pair_challenge", ["nonce"] = pair.Nonce,
                                ["attempts_left"] = host.Codes.AttemptsLeft, ["locked"] = host.Codes.Locked,
                            });
                        }
                        else if (t == "pair_proof")
                        {
                            if (pair == null || pair.Nonce == null) { SendNow(ws, Err("pair_hello_first")); continue; }
                            var nonce = pair.Nonce;
                            pair.Nonce = null;   // one proof per challenge
                            PairCheck r;
                            pairGate.Wait();
                            try
                            {
                                r = host.Codes.Verify(nonce, pair.Id, m.Str("proof"));
                                if (r != PairCheck.Ok) Thread.Sleep(WrongCodeDelay);
                            }
                            finally { pairGate.Release(); }
                            if (r != PairCheck.Ok)
                            {
                                var why = r == PairCheck.Locked ? "locked" : r == PairCheck.Exhausted ? "code_replaced" : "wrong_code";
                                Log.I("bridge: pairing refused for " + pair.Name + " (" + why + ") from " + peer.Address);
                                SendNow(ws, new Dictionary<string, object> { ["t"] = "pair_error", ["error"] = why, ["attempts_left"] = host.Codes.AttemptsLeft });
                                continue;
                            }
                            var token = host.Devices.Issue(pair.Id, pair.Name);
                            var pin = pair.Stream ? PairingCodes.NewMoonlightPin() : null;
                            var info = host.Info() ?? new Dictionary<string, object>();
                            var ok = new Dictionary<string, object>
                            {
                                ["t"] = "pair_ok", ["device_id"] = pair.Id, ["token"] = token,
                                ["host_id"] = info.Str("host_id"), ["pc_name"] = info.Str("pc_name"),
                                ["sunshine"] = info.TryGetValue("sunshine", out var sun) ? sun : null,
                            };
                            if (Tls) ok["fp"] = Fingerprint;
                            if (pin != null) ok["moonlight_pin"] = pin;
                            SendNow(ws, ok);
                            Log.I("bridge: paired " + pair.Name + " (" + pair.Id + ") from " + peer.Address);
                            s = Authenticate(ws, st, pair.Id, pair.Name, peer);
                            if (pin != null)
                            {
                                var sess = s;
                                var devId = pair.Id;
                                host.StreamPair(pair.Id, pair.Name, pin, peer.Address.ToString(), err =>
                                {
                                    if (err == null) host.Devices.SetStreamPaired(devId, true);
                                    var msg = new Dictionary<string, object> { ["t"] = "pair_stream", ["ok"] = err == null };
                                    if (err != null) msg["error"] = err;
                                    sess.Enqueue(msg);
                                });
                            }
                        }
                        else SendNow(ws, Err("hello_first"));
                        continue;
                    }

                    switch (t)
                    {
                        case "ping": s.Enqueue(new Dictionary<string, object> { ["t"] = "pong", ["id"] = m.Str("id") }); break;
                        case "focus":
                            var sf = s;
                            Task.Run(() => sf.Enqueue(With("focus_result", host.Focus())));
                            break;
                        case "bnet_ready":
                            var sb = s;
                            Task.Run(() => sb.Enqueue(With("bnet_ready_result", host.BnetReady())));
                            break;
                        case "login_file":
                            var em = m.Str("email");
                            var pw = m.Str("password");
                            Task.Run(() => host.LoginFile(em, pw));
                            break;
                        case "addons_status": s.Enqueue(With("addons", host.AddonsStatus())); break;
                        case "addons_install":
                            var sa = s;
                            Task.Run(() => sa.Enqueue(With("addons", host.AddonsInstall())));
                            break;
                        case "unpair":
                            host.Devices.Remove(s.DeviceId);
                            s.Enqueue(new Dictionary<string, object> { ["t"] = "unpaired" });
                            Thread.Sleep(200);
                            ws.Close(1000);
                            break;
                        default: break;   // unknown types are ignored (protocol section 2)
                    }
                }
            }
            catch (Exception e) { Log.I("bridge session: " + e.Message); }
            finally
            {
                if (s != null)
                {
                    bool removed;
                    lock (gate) removed = sessions.Remove(s);
                    s.Stop();
                    if (removed) Log.I("bridge: " + s.Name + " disconnected");
                    try { SessionsChanged?.Invoke(); } catch (Exception) { }
                }
                ws.Close(1000);
            }
        }

        static Dictionary<string, object> With(string t, Dictionary<string, object> body)
        {
            var d = new Dictionary<string, object> { ["t"] = t };
            if (body != null) foreach (var kv in body) d[kv.Key] = kv.Value;
            return d;
        }

        BridgeSession Authenticate(WsConn ws, Stream st, string id, string name, IPEndPoint peer)
        {
            host.Devices.Touch(id);
            SetTimeout(st, IdleMs);   // from here on any frame (the Thor's pings and pongs included) keeps it open
            var s = new BridgeSession(ws, id, name, peer.Address.ToString());
            List<BridgeSession> replaced;
            lock (gate)
            {
                // one session per device: a new one replaces the old (closed with 4409)
                replaced = sessions.Where(x => x.DeviceId == id).ToList();
                foreach (var r in replaced) sessions.Remove(r);
                var backlog = hub.Snapshot();
                var welcome = InfoMsg("welcome");
                welcome["device_id"] = id;
                welcome["backlog"] = backlog.Count;
                s.Enqueue(welcome);
                foreach (var d in backlog) s.Enqueue(d);
                s.Enqueue(new Dictionary<string, object> { ["t"] = "backlog_end", ["count"] = backlog.Count });
                sessions.Add(s);
            }
            foreach (var r in replaced) { r.Stop(); r.Ws.Close(4409); }
            s.StartWriter();
            Log.I("bridge: " + name + " connected from " + peer.Address);
            try { SessionsChanged?.Invoke(); } catch (Exception) { }
            return s;
        }

        static string testPage;
        static string TestPage()
        {
            if (testPage != null) return testPage;
            using (var st = Assembly.GetExecutingAssembly().GetManifestResourceStream("DsfDesktop.testpage.html"))
            using (var r = new StreamReader(st, Encoding.UTF8)) return testPage = r.ReadToEnd();
        }
    }

    /// <summary>a stream that gives back one already-read byte first (the TLS-or-plain sniff)</summary>
    sealed class PrefixStream : Stream
    {
        readonly Stream inner;
        int prefix;

        public PrefixStream(byte first, Stream inner) { prefix = first; this.inner = inner; }

        public override int Read(byte[] buffer, int offset, int count)
        {
            if (count == 0) return 0;
            if (prefix >= 0) { buffer[offset] = (byte)prefix; prefix = -1; return 1; }
            return inner.Read(buffer, offset, count);
        }

        public override void Write(byte[] buffer, int offset, int count) => inner.Write(buffer, offset, count);
        public override void Flush() => inner.Flush();
        public override bool CanRead => true;
        public override bool CanWrite => true;
        public override bool CanSeek => false;
        public override bool CanTimeout => inner.CanTimeout;
        public override int ReadTimeout { get => inner.ReadTimeout; set => inner.ReadTimeout = value; }
        public override int WriteTimeout { get => inner.WriteTimeout; set => inner.WriteTimeout = value; }
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override long Seek(long o, SeekOrigin so) => throw new NotSupportedException();
        public override void SetLength(long v) => throw new NotSupportedException();
        protected override void Dispose(bool disposing) { if (disposing) inner.Dispose(); base.Dispose(disposing); }
    }
}
