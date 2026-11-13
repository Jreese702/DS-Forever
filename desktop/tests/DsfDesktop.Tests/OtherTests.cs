using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using DsfDesktop.Bridge;
using DsfDesktop.GameLog;
using DsfDesktop.Pairing;
using DsfDesktop.Sunshine;

namespace DsfDesktop.Tests
{
    /// <summary>issue #54: the welcome says whether Sunshine can make the Thor's controller pad (ViGEmBus)</summary>
    public static class PadDriverTests
    {
        [Test]
        public static void SaysWhetherTheControllerDriverIsThere()
        {
            A.Eq("vigem", PadDriver.Word(true));
            A.Eq("missing", PadDriver.Word(false));
            A.Eq("unknown", PadDriver.Word(null));
            var now = PadDriver.Now();
            A.True(now == "vigem" || now == "missing" || now == "unknown", "one of the three words: " + now);
            A.Eq(now, PadDriver.Now(), "cached between welcomes");
        }
    }

    public static class WsTests
    {
        [Test]
        public static void AcceptKeyRfcVector()
        {
            A.Eq("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsConn.AcceptKey("dGhlIHNhbXBsZSBub25jZQ=="));
        }

        /// <summary>two WsConns over a loopback socket pair</summary>
        static void Pair(out WsConn server, out WsConn client, out TcpListener l)
        {
            l = new TcpListener(IPAddress.Loopback, 0);
            l.Start();
            var c = new TcpClient();
            c.Connect(IPAddress.Loopback, ((IPEndPoint)l.LocalEndpoint).Port);
            var sc = l.AcceptTcpClient();
            server = new WsConn(sc.GetStream());
            client = new WsConn(c.GetStream(), isClient: true);
        }

        [Test]
        public static void FramesRoundTripAllLengths()
        {
            Pair(out var server, out var client, out var l);
            try
            {
                foreach (var n in new[] { 0, 5, 125, 126, 1000, 65535, 65536, 300_000 })
                {
                    var text = new string('é', n / 2) + new string('a', n % 2);
                    var t = new Thread(() => client.SendText(text));
                    t.Start();
                    A.Eq(text, server.ReadText(), "client->server " + n);
                    t.Join();
                    t = new Thread(() => server.SendText(text));
                    t.Start();
                    A.Eq(text, client.ReadText(), "server->client " + n);
                    t.Join();
                }
            }
            finally { l.Stop(); }
        }

        [Test]
        public static void PingIsAnsweredAndFragmentsJoin()
        {
            Pair(out var server, out var client, out var l);
            try
            {
                client.Send(0x9, Encoding.UTF8.GetBytes("hi"));            // ping: the server answers while reading
                client.Send(0x1 & 0x0f, new byte[0]);                       // (an empty text message)
                A.Eq("", server.ReadText());
                // a fragmented message: text (FIN=0) + continuation (FIN=1), sent as raw frames
                var raw = new List<byte>();
                raw.AddRange(new byte[] { 0x01, 0x83, 0, 0, 0, 0 }); raw.AddRange(Encoding.ASCII.GetBytes("abc"));
                raw.AddRange(new byte[] { 0x80, 0x83, 0, 0, 0, 0 }); raw.AddRange(Encoding.ASCII.GetBytes("def"));
                var field = typeof(WsConn).GetField("s", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance);
                var cs = (Stream)field.GetValue(client);
                cs.Write(raw.ToArray(), 0, raw.Count);
                A.Eq("abcdef", server.ReadText());
                server.Close(1000);
                A.Null(client.ReadText(), "close seen (the pong first is skipped)");
                A.Eq(1000, client.CloseCode);
            }
            finally { l.Stop(); }
        }

        [Test]
        public static void HttpRequestParse()
        {
            var r = HttpRequest.Parse("GET /v1/ws?x=1 HTTP/1.1\r\nHost: a\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\nSec-WebSocket-Key: abc\r\n\r\n");
            A.Eq("/v1/ws", r.Path);
            A.Eq("x=1", r.Query);
            A.True(r.IsUpgrade);
            A.Eq("a", r.Header("host"));
        }
    }

    public static class MdnsTests
    {
        static byte[] Query(string name, ushort type, bool qu = false)
        {
            var b = new List<byte> { 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0 };
            DnsWire.WriteName(b, name);
            b.Add((byte)(type >> 8)); b.Add((byte)type);
            b.Add((byte)(qu ? 0x80 : 0)); b.Add(1);
            return b.ToArray();
        }

        static MdnsResponder Make() => new MdnsResponder("JREES-PC", 48210, () => new Dictionary<string, string> { ["proto"] = "1", ["id"] = "0123456789abcdef0123456789abcdef", ["pc"] = "JREES-PC", ["sunshine"] = "52989", ["tls"] = "1", ["fp"] = "ab12" });

        [Test]
        public static void AnswersServiceBrowse()
        {
            var r = Make();
            var reply = r.Answer(Query("_dsforever._tcp.local", DnsRecord.PTR));
            A.NotNull(reply);
            var ans = DnsWire.Answers(reply);
            A.True(ans.Any(a => a.Key == "12 _dsforever._tcp.local" && a.Value == "JREES-PC._dsforever._tcp.local"), string.Join(" | ", ans));
            A.True(ans.Any(a => a.Key.StartsWith("33 ") && a.Value == "48210 JREES-PC-dsforever.local"), "SRV with the bridge port");
            A.True(ans.Any(a => a.Key.StartsWith("16 ") && a.Value.Contains("sunshine=52989") && a.Value.Contains("proto=1") && a.Value.Contains("tls=1") && a.Value.Contains("fp=ab12")), "TXT");
        }

        [Test]
        public static void AnswersSrvAndServiceEnumeration()
        {
            var r = Make();
            var srv = DnsWire.Answers(r.Answer(Query(r.InstanceName, DnsRecord.SRV)));
            A.True(srv[0].Key.StartsWith("33 "));
            var en = DnsWire.Answers(r.Answer(Query("_services._dns-sd._udp.local", DnsRecord.PTR)));
            A.Eq("_dsforever._tcp.local", en[0].Value);
        }

        [Test]
        public static void IgnoresOthersAndResponses()
        {
            var r = Make();
            A.Null(r.Answer(Query("_nvstream._tcp.local", DnsRecord.PTR)), "Sunshine's own service is Sunshine's");
            A.Null(r.Answer(Query("JREES-PC.local", DnsRecord.A)), "the PC's own name is Windows'");
            var resp = Query("_dsforever._tcp.local", DnsRecord.PTR);
            resp[2] = 0x84;
            A.Null(r.Answer(resp), "responses are not questions");
            A.Null(r.Answer(new byte[] { 1, 2, 3 }), "garbage");
        }

        [Test]
        public static void ReadsCompressedNames()
        {
            // "a.local" at 12, then a pointer-compressed "b.<ptr to local>"
            var m = new List<byte> { 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0 };
            DnsWire.WriteName(m, "a.local"); m.AddRange(new byte[] { 0, 1, 0, 1 });
            m.Add(1); m.Add((byte)'b'); m.Add(0xC0); m.Add(14); m.AddRange(new byte[] { 0, 12, 0, 1 });
            var qs = DnsWire.Questions(m.ToArray());
            A.Eq("a.local", qs[0].Name);
            A.Eq("b.local", qs[1].Name);
            A.Eq(DnsRecord.PTR, qs[1].Type);
        }
    }

    public static class SunshineConfigTests
    {
        [Test]
        public static void ConfIsHeadlessAndLeavesNoTrace()
        {
            var l = new SunshineLayout { ExeDir = @"C:\App\sunshine", DataDir = @"C:\Users\x\AppData\Local\DSForeverDesktop\sunshine" };
            var text = SunshineConfig.ConfText(l, new SunshineOptions { Port = 52989, HostName = "JREES-PC" });
            var kv = text.Split('\n').Where(x => x.Contains(" = ")).ToDictionary(x => x.Split(new[] { " = " }, 2, StringSplitOptions.None)[0], x => x.Split(new[] { " = " }, 2, StringSplitOptions.None)[1]);
            A.Eq("disabled", kv["system_tray"]);
            A.Eq("pc", kv["origin_web_ui_allowed"]);
            A.Eq("disabled", kv["upnp"]);
            A.Eq("disabled", kv["install_steam_audio_drivers"]);
            A.Eq("disabled", kv["nvenc_opengl_vulkan_on_dxgi"]);
            A.Eq("0", kv["hevc_mode"]);
            A.Eq("52989", kv["port"]);
            A.False(kv.ContainsKey("encoder"), "auto: Sunshine's probe");
            A.Eq("C:/Users/x/AppData/Local/DSForeverDesktop/sunshine/sunshine_state.json", kv["file_state"], "forward slashes: Sunshine's parser eats backslashes");
            A.True(kv.Values.All(v => !v.Contains("\\")));
        }

        [Test]
        public static void AppsHasTheWowEntryWithEvents()
        {
            var j = Json.ReadObject(SunshineConfig.AppsText(new SunshineOptions { EventExe = @"C:\App\DsForeverDesktop.exe" }));
            var app = (Dictionary<string, object>)((object[])j["apps"])[0];
            A.Eq("WoW Forever", app.Str("name"));
            A.False(app.ContainsKey("cmd"), "the desktop itself: WoW is already running");
            var prep = (Dictionary<string, object>)((object[])app["prep-cmd"])[0];
            A.Eq("\"C:\\App\\DsForeverDesktop.exe\" --event stream-start", prep.Str("do"));
            A.Eq("\"C:\\App\\DsForeverDesktop.exe\" --event stream-stop", prep.Str("undo"));
        }

        [Test]
        public static void Issue219StateSummaryCountsPairingsWithoutCerts()
        {
            var id = DsfDesktop.Sunshine.SunshineDiag.Fp("-----BEGIN CERTIFICATE-----\nAAECAw==\n-----END CERTIFICATE-----\n");
            A.Eq(12, id.Length);
            var json = "{\"root\":{\"uniqueid\":\"X\",\"named_devices\":[{\"name\":\"AYN Thor (1a2b3c4d)\",\"cert\":\"-----BEGIN CERTIFICATE-----\nAAECAw==\n-----END CERTIFICATE-----\n\",\"uuid\":\"u1\"}]}}";
            var s = DsfDesktop.Sunshine.SunshineDiag.StateSummary(json);
            A.Eq("1 paired: AYN Thor (1a2b3c4d) [fp " + id + "]", s);
            A.False(s.Contains("AAECAw"), "never the certificate");
            A.Eq("0 paired", DsfDesktop.Sunshine.SunshineDiag.StateSummary("{\"root\":{\"named_devices\":[]}}"));
            A.Eq("no file", DsfDesktop.Sunshine.SunshineDiag.StateSummary(null));
            A.Eq("unreadable", DsfDesktop.Sunshine.SunshineDiag.StateSummary("not json"));
        }

        [Test]
        public static void Issue219PairLinesKeepPairingAndHidePins()
        {
            var log = "[2026-11-12 18:59:03.879]: Info: Executing [Desktop]\r\n[2026-11-12 18:59:03.912]: Warning: SSL Verification error :: self signed certificate\r\n" +
                      "[x]: Info: pin 1234 accepted for pairing\n-----BEGIN CERTIFICATE-----\n";
            var l = DsfDesktop.Sunshine.SunshineDiag.PairLines(log);
            A.Eq(2, l.Count);
            A.True(l[0].Contains("SSL Verification error"));
            A.False(l[1].Contains("1234"), "the PIN is masked");
            A.Eq("47984 listening, 52984 free", DsfDesktop.Sunshine.SunshineDiag.Listeners(new[] { 47984, 52984, 47984 }, p => p != 47984));
        }

        [Test]
        public static void PortPlannerSkipsBusyBases()
        {
            var busy = new HashSet<int> { 47989, 47990, 48989, 49989 };
            A.Eq(52989, PortPlanner.Pick(0, p => !busy.Contains(p), p => true));
            A.Eq(47989, PortPlanner.Pick(0, p => true, p => true));
            A.Eq(53989, PortPlanner.Pick(53989, p => true, p => true), "sticky first");
            A.Eq(52989, PortPlanner.Pick(0, p => true, p => p != 47998), "a busy UDP port counts too");
            A.Eq(-1, PortPlanner.Pick(0, p => false, p => true));
        }

        [Test]
        public static void CertSignatureParsesDer()
        {
            var c = new MoonlightPairClient("127.0.0.1", 1);
            var pem = MoonlightPairClient.Pem(new System.Security.Cryptography.X509Certificates.X509Certificate2(
                new System.Security.Cryptography.X509Certificates.X509Certificate2(Convert.FromBase64String(SelfSigned()))));
            A.True(pem.StartsWith("-----BEGIN CERTIFICATE-----\n"));
            var der = Convert.FromBase64String(SelfSigned());
            A.Eq(256, MoonlightPairClient.CertSignature(der).Length, "RSA-2048 signature");
        }

        static string selfSigned;
        static string SelfSigned()
        {
            if (selfSigned != null) return selfSigned;
            using (var rsa = new System.Security.Cryptography.RSACng(2048))
            {
                var req = new System.Security.Cryptography.X509Certificates.CertificateRequest("CN=t", rsa, System.Security.Cryptography.HashAlgorithmName.SHA256, System.Security.Cryptography.RSASignaturePadding.Pkcs1);
                return selfSigned = Convert.ToBase64String(req.CreateSelfSigned(DateTimeOffset.UtcNow, DateTimeOffset.UtcNow.AddDays(1)).RawData);
            }
        }
    }
}
