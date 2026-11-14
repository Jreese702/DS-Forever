using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using DsfDesktop.Bridge;
using DsfDesktop.GameLog;
using DsfDesktop.Pairing;

namespace DsfDesktop.Tests
{
    sealed class FakeHost : IBridgeHost, IDisposable
    {
        public readonly Tmp T = new Tmp();
        public PairingCodes Codes { get; } = new PairingCodes();
        public DeviceStore Devices { get; }
        public readonly List<string> Pins = new List<string>();
        public string StreamPairResult;   // null = the Sunshine side succeeds
        public string SunWhy = "";        // the PC can't stream: the Thor's reason ("pc busy: another Sunshine")
        public FakeHost() { Devices = new DeviceStore(T.P("devices.json")); }
        public Dictionary<string, object> Info() => new Dictionary<string, object>
        {
            ["host_id"] = "0123456789abcdef0123456789abcdef", ["pc_name"] = "TESTPC",
            ["sunshine"] = new Dictionary<string, object> { ["running"] = true, ["http_port"] = 52989, ["https_port"] = 52984, ["why"] = SunWhy },
        };
        public void StreamPair(string deviceId, string name, string pin, string peerIp, Action<string> done)
        {
            lock (Pins) Pins.Add(pin);
            ThreadPool.QueueUserWorkItem(_ => done(StreamPairResult));
        }
        public void LoginFile(string email, string password) { }
        public Dictionary<string, object> BnetReady() => new Dictionary<string, object> { ["ok"] = false, ["why"] = "no sign-in window" };
        public Dictionary<string, object> Focus() => new Dictionary<string, object> { ["ok"] = false, ["why"] = "WoW isn't running" };
        public Dictionary<string, object> AddonsStatus() => new Dictionary<string, object> { ["state"] = "current" };
        public Dictionary<string, object> AddonsInstall() => new Dictionary<string, object> { ["state"] = "current", ["result"] = "ok" };
        public void Dispose() => T.Dispose();
    }

    /// <summary>a test client: TCP (+ TLS when asked, recording the server's fingerprint), the upgrade by hand, then WsConn</summary>
    sealed class Client : IDisposable
    {
        readonly TcpClient tcp;
        public WsConn Ws;
        public string HandshakeStatus;
        public string SeenFingerprint;

        public Client(int port, bool tls = false, string origin = null, string path = "/v1/ws")
        {
            tcp = new TcpClient();
            tcp.Connect(IPAddress.Loopback, port);
            Stream ns = tcp.GetStream();
            ns.ReadTimeout = 8000;
            if (tls)
            {
                var ssl = new SslStream(ns, false, (s, cert, chain, errs) => { SeenFingerprint = BridgeCert.Fingerprint(cert); return true; });
                ssl.AuthenticateAsClient("dsforever");
                ns = ssl;
            }
            var key = Convert.ToBase64String(Crypto.RandomBytes(16));
            var req = "GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key +
                      "\r\nSec-WebSocket-Version: 13\r\n" + (origin != null ? "Origin: " + origin + "\r\n" : "") + "\r\n";
            var b = Encoding.ASCII.GetBytes(req);
            ns.Write(b, 0, b.Length);
            ns.Flush();
            var head = new StringBuilder();
            try { while (!head.ToString().EndsWith("\r\n\r\n")) { int c = ns.ReadByte(); if (c < 0) break; head.Append((char)c); } }
            catch (IOException) { }
            HandshakeStatus = head.Length > 0 ? head.ToString().Split('\r')[0] : "(closed)";
            if (HandshakeStatus.Contains("101"))
            {
                A.True(head.ToString().Contains("Sec-WebSocket-Accept: " + WsConn.AcceptKey(key)), "accept key");
                Ws = new WsConn(ns, isClient: true);
            }
        }

        public void Send(Dictionary<string, object> m) => Ws.SendText(Json.Write(m));
        public Dictionary<string, object> Next() { var t = Ws.ReadText(); return t == null ? null : Json.ReadObject(t); }

        /// <summary>messages until one of type t (the ones before it are returned too)</summary>
        public List<Dictionary<string, object>> Until(string t)
        {
            var all = new List<Dictionary<string, object>>();
            while (true)
            {
                var m = Next();
                if (m == null) throw new AssertFailed("closed before '" + t + "' (" + string.Join(",", all.Select(x => x.Str("t"))) + ")");
                all.Add(m);
                if (m.Str("t") == t) return all;
            }
        }

        public void Dispose() { try { tcp.Close(); } catch (Exception) { } }
    }

    public static class BridgeTests
    {
        static X509Certificate2 cert;
        static readonly Tmp CertDir = new Tmp();
        static X509Certificate2 Cert() => cert ?? (cert = BridgeCert.LoadOrCreate(CertDir.Dir, "test"));

        static BridgeServer Start(FakeHost h)
        {
            var s = new BridgeServer(h, Cert(), 0, IPAddress.Loopback) { WrongCodeDelay = TimeSpan.Zero };
            s.Start();
            return s;
        }

        /// <summary>the Thor's side of section 3 on an open client; returns pair_ok</summary>
        static Dictionary<string, object> Pair(Client c, FakeHost h, string deviceId, bool stream = true)
        {
            c.Send(new Dictionary<string, object> { ["t"] = "pair_hello", ["proto"] = 1, ["device_id"] = deviceId, ["name"] = "AYN Thor", ["stream"] = stream });
            var ch = c.Next();
            A.Eq("pair_challenge", ch.Str("t"));
            c.Send(new Dictionary<string, object> { ["t"] = "pair_proof", ["proof"] = PairingCodes.Proof(h.Codes.Current, ch.Str("nonce"), deviceId) });
            var ok = c.Next();
            A.Eq("pair_ok", ok.Str("t"), Json.Write(ok));
            return ok;
        }

        static Dictionary<string, object> Hello(string id, string token) => new Dictionary<string, object> { ["t"] = "hello", ["proto"] = 1, ["device_id"] = id, ["token"] = token };

        [Test]
        public static void CertificateIsKeptAndFingerprinted()
        {
            using (var t = new Tmp())
            {
                var a = BridgeCert.LoadOrCreate(t.Dir, "x");
                var b = BridgeCert.LoadOrCreate(t.Dir, "x");
                A.Eq(BridgeCert.Fingerprint(a), BridgeCert.Fingerprint(b), "made once, then reused (the Thor pins it)");
                A.Eq(64, BridgeCert.Fingerprint(a).Length);
                A.True(a.HasPrivateKey);
            }
        }

        [Test]
        public static void InfoEndpointPlainAndTls()
        {
            using (var h = new FakeHost())
            using (var s = Start(h))
            using (var wc = new WebClient())
            {
                var info = Json.ReadObject(wc.DownloadString("http://127.0.0.1:" + s.Port + "/v1/info"));
                A.Eq("info", info.Str("t"));
                A.Eq(1L, info.Long("proto"));
                A.Eq("TESTPC", info.Str("pc_name"));
                A.Eq((long)s.Port, info.Long("bridge_port"));
                A.True(info.Bool("tls"));
                A.Eq(s.Fingerprint, info.Str("fp"));
                A.True(wc.DownloadString("http://127.0.0.1:" + s.Port + "/test").Contains("DS Forever bridge test"), "test page on loopback");
                ServicePointManager.ServerCertificateValidationCallback = (o, c, ch, e) => true;
                try { A.Eq("TESTPC", Json.ReadObject(wc.DownloadString("https://127.0.0.1:" + s.Port + "/v1/info")).Str("pc_name"), "the same over TLS"); }
                finally { ServicePointManager.ServerCertificateValidationCallback = null; }
            }
        }

        [Test]
        public static void PairByCodeThenDocsFlow()
        {
            using (var h = new FakeHost())
            using (var s = Start(h))
            using (var c = new Client(s.Port))
            {
                var code = h.Codes.Current;
                var ok = Pair(c, h, "thor-1");
                A.True(h.Devices.Validate("thor-1", ok.Str("token")), "the token works");
                A.Eq(43, ok.Str("token").Length, "32 bytes, base64url without padding");
                var pin = ok.Str("moonlight_pin");
                A.Eq(4, pin.Length);
                A.Eq("0123456789abcdef0123456789abcdef", ok.Str("host_id"));
                A.Eq(52989L, ((Dictionary<string, object>)ok["sunshine"]).Long("http_port"));
                A.True(h.Codes.Current != code, "the code is used up");
                var msgs = c.Until("pair_stream");
                A.Eq("welcome", msgs[0].Str("t"));
                A.Eq("thor-1", msgs[0].Str("device_id"));
                A.True(msgs.Any(m => m.Str("t") == "backlog_end"));
                A.True(msgs.Last().Bool("ok"));
                lock (h.Pins) A.Eq(pin, h.Pins.Single(), "Sunshine got the same PIN the device got");
                A.True(h.Devices.All().Single().StreamPaired);

                s.PublishDoc(new LogEvent { Kind = LogEventKind.Doc, Seq = 5, GameTime = 123, Text = "{\"t\":\"pos\"}" });
                var doc = c.Until("doc").Last();
                A.Eq("{\"t\":\"pos\"}", doc.Str("doc"));
                A.Eq(5L, doc.Long("seq"));
                A.Eq(123L, doc.Long("gt"));
            }
        }

        /// <summary>another Sunshine holds the GameStream ports: right after the welcome the Thor's status line says so</summary>
        [Test]
        public static void WelcomeSaysThePcIsBusy()
        {
            using (var h = new FakeHost { SunWhy = "pc busy: another Sunshine" })
            using (var s = Start(h))
            using (var c = new Client(s.Port))
            {
                Pair(c, h, "thor-busy", stream: false);
                var msgs = c.Until("status");
                A.Eq("welcome", msgs[0].Str("t"));
                A.Eq("pc busy: another Sunshine", ((Dictionary<string, object>)msgs[0]["sunshine"]).Str("why"));
                A.Eq("pc busy: another Sunshine", msgs.Last().Str("text"));
            }
            using (var h = new FakeHost())
            using (var s = Start(h))
            using (var c = new Client(s.Port))
            {
                Pair(c, h, "thor-fine", stream: false);
                var msgs = c.Until("backlog_end");
                A.False(msgs.Any(m => m.Str("t") == "status"), "no status when the PC is fine");
                s.Publish(new Dictionary<string, object> { ["t"] = "ping_marker" });
                A.Eq("ping_marker", c.Next().Str("t"), "nothing queued between backlog_end and the next message");
            }
        }

        [Test]
        public static void PairingOverTlsPinsTheAdvertisedCertificate()
        {
            using (var h = new FakeHost())
            using (var s = Start(h))
            using (var c = new Client(s.Port, tls: true))
            {
                A.Eq(s.Fingerprint, c.SeenFingerprint, "what the TXT fp says is what TLS shows");
                var ok = Pair(c, h, "thor-tls", stream: false);
                A.Eq(s.Fingerprint, ok.Str("fp"));
                A.Eq("welcome", c.Next().Str("t"));
            }
        }

        [Test]
        public static void WrongCodeIsRefusedAndRetryWorks()
        {
            using (var h = new FakeHost())
            using (var s = Start(h))
            using (var c = new Client(s.Port))
            {
                c.Send(new Dictionary<string, object> { ["t"] = "pair_hello", ["device_id"] = "thor-1", ["name"] = "Thor" });
                var ch = c.Next();
                A.Eq(5L, ch.Long("attempts_left"));
                A.False(ch.Bool("locked"));
                var wrong = h.Codes.Current == "000000" ? "111111" : "000000";
                c.Send(new Dictionary<string, object> { ["t"] = "pair_proof", ["proof"] = PairingCodes.Proof(wrong, ch.Str("nonce"), "thor-1") });
                var err = c.Next();
                A.Eq("pair_error", err.Str("t"));
                A.Eq("wrong_code", err.Str("error"));
                A.Eq(4L, err.Long("attempts_left"));
                // the nonce was used: another proof needs a new pair_hello
                c.Send(new Dictionary<string, object> { ["t"] = "pair_proof", ["proof"] = "00" });
                A.Eq("pair_hello_first", c.Next().Str("error"));
                A.Eq(0, h.Devices.All().Count);
                A.Eq(43, Pair(c, h, "thor-1", stream: false).Str("token").Length, "a second try with the right code pairs");
            }
        }

        [Test]
        public static void FiveWrongReplaceTwentyLock()
        {
            using (var h = new FakeHost())
            using (var s = Start(h))
            using (var c = new Client(s.Port))
            {
                string Try()
                {
                    c.Send(new Dictionary<string, object> { ["t"] = "pair_hello", ["device_id"] = "x", ["name"] = "x" });
                    var ch = c.Next();
                    c.Send(new Dictionary<string, object> { ["t"] = "pair_proof", ["proof"] = "00" });
                    return c.Next().Str("error");
                }
                for (int i = 0; i < 4; i++) A.Eq("wrong_code", Try());
                A.Eq("code_replaced", Try());
                for (int i = 0; i < 14; i++) Try();
                A.Eq("locked", Try());
                c.Send(new Dictionary<string, object> { ["t"] = "pair_hello", ["device_id"] = "x", ["name"] = "x" });
                A.True(c.Next().Bool("locked"), "the challenge says pairing is paused");
            }
        }

        [Test]
        public static void StreamPairFailureIsReported()
        {
            using (var h = new FakeHost { StreamPairResult = "the device's stream pairing didn't arrive in time" })
            using (var s = Start(h))
            using (var c = new Client(s.Port))
            {
                Pair(c, h, "thor-2");
                var st = c.Until("pair_stream").Last();
                A.False(st.Bool("ok"));
                A.True(st.Str("error").Contains("didn't arrive"));
                A.False(h.Devices.All().Single().StreamPaired, "the bridge token stays; only the stream half is missing");
            }
        }

        [Test]
        public static void TheThorsAddonSetAsksForAnUpdateCheck()
        {
            using (var h = new FakeHost())
            using (var s = Start(h))
            {
                var sets = new System.Collections.Concurrent.ConcurrentQueue<int>();
                int connects = 0;
                s.ThorAddonSet += n => sets.Enqueue(n);
                s.ThorConnected += () => System.Threading.Interlocked.Increment(ref connects);
                var token = h.Devices.Issue("thor-1", "Thor");
                using (var c = new Client(s.Port))
                {
                    var hello = Hello("thor-1", token);
                    hello["addon_set"] = 391;
                    c.Send(hello);
                    c.Until("backlog_end");
                    c.Send(new Dictionary<string, object> { ["t"] = "addons_status" });   // an older Thor: no set
                    c.Until("addons");
                    c.Send(new Dictionary<string, object> { ["t"] = "addons_status", ["addon_set"] = 392 });
                    c.Until("addons");
                }
                A.Eq(1, connects);
                A.Eq("391,392", string.Join(",", sets));
            }
        }

        [Test]
        public static void HelloWithTokenGetsBacklogThenLive()
        {
            using (var h = new FakeHost())
            using (var s = Start(h))
            {
                var token = h.Devices.Issue("thor-1", "Thor");
                for (int i = 1; i <= 3; i++) s.PublishDoc(new LogEvent { Kind = LogEventKind.Doc, Seq = i, Text = "{\"n\":" + i + "}" });
                using (var c = new Client(s.Port))
                {
                    c.Send(Hello("thor-1", token));
                    var msgs = c.Until("backlog_end");
                    A.Eq("welcome", msgs[0].Str("t"));
                    A.Eq(3L, msgs[0].Long("backlog"));
                    A.Eq("{\"n\":1}", msgs[1].Str("doc"));
                    A.Eq("{\"n\":3}", msgs[3].Str("doc"));
                    s.PublishLog("restart", "x", "_classic_beta_");
                    A.Eq("restart", c.Next().Str("event"));
                    s.PublishDoc(new LogEvent { Kind = LogEventKind.Doc, Seq = 1, Text = "{\"n\":\"new\"}" });
                    A.Eq("{\"n\":\"new\"}", c.Next().Str("doc"));
                    c.Send(new Dictionary<string, object> { ["t"] = "ping", ["id"] = "p1" });
                    A.Eq("p1", c.Until("pong").Last().Str("id"));
                    c.Send(new Dictionary<string, object> { ["t"] = "focus" });
                    A.False(c.Until("focus_result").Last().Bool("ok"));
                    c.Send(new Dictionary<string, object> { ["t"] = "something_new" });   // unknown types are ignored
                    c.Send(new Dictionary<string, object> { ["t"] = "addons_status" });
                    A.Eq("current", c.Until("addons").Last().Str("state"));
                }
                using (var c2 = new Client(s.Port))
                {
                    c2.Send(Hello("thor-1", token));
                    A.Eq(1L, c2.Until("welcome").Last().Long("backlog"), "a restart empties the backlog");
                }
            }
        }

        [Test]
        public static void BadOrRemovedTokenIsRefusedAndANewSessionReplacesTheOld()
        {
            using (var h = new FakeHost())
            using (var s = Start(h))
            {
                var token = h.Devices.Issue("thor-1", "Thor");
                using (var c = new Client(s.Port))
                {
                    c.Send(Hello("thor-1", "nope"));
                    A.Eq("unauthorized", c.Next().Str("error"));
                    A.Null(c.Next(), "then closed");
                    A.Eq(4401, c.Ws.CloseCode);
                }
                using (var first = new Client(s.Port))
                {
                    first.Send(Hello("thor-1", token));
                    first.Until("backlog_end");
                    using (var second = new Client(s.Port))
                    {
                        second.Send(Hello("thor-1", token));
                        second.Until("welcome");
                        while (first.Next() != null) { }
                        A.Eq(4409, first.Ws.CloseCode, "one session per device");
                    }
                }
                h.Devices.Remove("thor-1");
                using (var c = new Client(s.Port))
                {
                    c.Send(Hello("thor-1", token));
                    var e = c.Next();
                    A.Eq("unauthorized", e.Str("error"));
                    A.True(e.Str("detail").Contains("removed"));
                    A.Null(c.Next());
                    A.Eq(4401, c.Ws.CloseCode);
                }
            }
        }

        [Test]
        public static void NothingBeforeHello()
        {
            using (var h = new FakeHost())
            using (var s = Start(h))
            using (var c = new Client(s.Port))
            {
                s.PublishDoc(new LogEvent { Kind = LogEventKind.Doc, Seq = 1, Text = "{}" });
                c.Send(new Dictionary<string, object> { ["t"] = "focus" });
                A.Eq("hello_first", c.Next().Str("error"));
            }
        }

        [Test]
        public static void BrowserOriginsAreRefused()
        {
            using (var h = new FakeHost())
            using (var s = Start(h))
            {
                using (var evil = new Client(s.Port, origin: "https://evil.example")) A.True(evil.HandshakeStatus.Contains("403"), evil.HandshakeStatus);
                using (var evil = new Client(s.Port, tls: true, origin: "https://evil.example")) A.True(evil.HandshakeStatus.Contains("403"), "over TLS too");
                using (var ours = new Client(s.Port, origin: "http://localhost:" + s.Port)) A.True(ours.HandshakeStatus.Contains("101"), "our own test page");
            }
        }

        [Test]
        public static void UnpairRemovesTheDevice()
        {
            using (var h = new FakeHost())
            using (var s = Start(h))
            using (var c = new Client(s.Port))
            {
                var token = Pair(c, h, "thor-9", stream: false).Str("token");
                c.Until("backlog_end");
                c.Send(new Dictionary<string, object> { ["t"] = "unpair" });
                c.Until("unpaired");
                A.False(h.Devices.Validate("thor-9", token));
            }
        }
    }
}
