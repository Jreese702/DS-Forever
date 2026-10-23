using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using DsfDesktop.App;
using DsfDesktop.Pairing;
using DsfDesktop.Sunshine;

namespace DsfDesktop.Tests
{
    /// <summary>
    /// The whole Desktop in-process (AppController, loopback mode: nothing on the LAN) against a fake WoW install in a temp folder
    /// and the REAL bundled Sunshine (needs --sunshine). A client pairs by the 6-digit code over the bridge, runs Moonlight's
    /// handshake with the PIN it was given, and then receives the add-on's documents as the fake game writes them.
    /// </summary>
    public static class EndToEnd
    {
        const string Pfx = "10/23 12:00:00.000  [N][Lua] ";

        public static AppController Make(Tmp t, out string log)
        {
            Environment.SetEnvironmentVariable("DSF_LOOPBACK", "1");
            var root = t.P("World of Warcraft");
            var flavor = Path.Combine(root, "_classic_beta_");
            Directory.CreateDirectory(Path.Combine(flavor, "Logs"));
            Directory.CreateDirectory(Path.Combine(flavor, "WTF"));
            File.WriteAllText(Path.Combine(flavor, "WowB.exe"), "");
            File.WriteAllText(Path.Combine(flavor, "WTF", "Config.wtf"), "SET developerLog \"1\"\r\nSET InputDeviceInterfaceStyle \"1\"\r\n");
            log = Path.Combine(flavor, "Logs", "General.log");
            File.WriteAllText(log, "10/23 12:00:00.000  [N][Connection] Client connection initialized\r\n");
            var paths = new AppPaths
            {
                Exe = typeof(EndToEnd).Assembly.Location,
                AppDir = AppDomain.CurrentDomain.BaseDirectory,
                DataDir = t.P("data"),
                SunshineExeDir = SunshineLive.ExeDir ?? t.P("no-sunshine"),
                AddonSource = AppPaths.FindAddonSource(AppDomain.CurrentDomain.BaseDirectory),
            };
            Directory.CreateDirectory(paths.DataDir);
            File.WriteAllText(paths.Settings, Json.Write(new Dictionary<string, object> { ["WowRoot"] = root, ["AutoAddons"] = false, ["SunshinePort"] = 57989 }));
            return new AppController(paths);
        }

        public static void Write(string log, int seq, string json)
        {
            using (var fs = new FileStream(log, FileMode.Append, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete))
            {
                var b = Encoding.UTF8.GetBytes(Pfx + "WCB1|" + seq + "|1/1|" + json + "\r\n");
                fs.Write(b, 0, b.Length);
            }
        }

        [Test]
        public static void PairByCodeStreamPairAndLiveDocs()
        {
            if (SunshineLive.ExeDir == null) throw new SkipException("run with --sunshine <dir> for the end-to-end test");
            using (var t = new Tmp())
            {
                var ctl = Make(t, out var log);
                try
                {
                    ctl.Start();
                    A.NotNull(ctl.Bridge, "bridge up");
                    A.Eq("_classic_beta_", ctl.Flavor);
                    // Sunshine's API up?
                    using (var api = new SunshineApi(ctl.SunshinePort, ctl.Settings.SunshineUser, Crypto.Unprotect(ctl.Settings.SunshinePass), ctl.SunshineLayout.Cert))
                    {
                        var until = DateTime.UtcNow.AddSeconds(60);
                        while (!api.Alive(CancellationToken.None).Result) { if (DateTime.UtcNow > until) throw new AssertFailed("Sunshine never came up"); Thread.Sleep(500); }
                    }
                    Write(log, 1, "{\"t\":\"hello\",\"n\":1}");

                    using (var c = new Client(ctl.Bridge.Port, tls: true))
                    {
                        A.Eq(ctl.Bridge.Fingerprint, c.SeenFingerprint, "the TLS certificate the TXT advertises");
                        c.Send(new Dictionary<string, object> { ["t"] = "pair_hello", ["proto"] = 1, ["device_id"] = "e2e00000000000000000000000000001", ["name"] = "AYN Thor" });
                        var ch = c.Next();
                        c.Send(new Dictionary<string, object> { ["t"] = "pair_proof", ["proof"] = PairingCodes.Proof(ctl.Codes.Current, ch.Str("nonce"), "e2e00000000000000000000000000001") });
                        var ok = c.Next();
                        A.Eq("pair_ok", ok.Str("t"), Json.Write(ok));
                        var sun = (Dictionary<string, object>)ok["sunshine"];
                        A.Eq((long)ctl.SunshinePort, sun.Long("http_port"));
                        Console.WriteLine("  paired; Sunshine on " + sun.Long("http_port") + ", PIN " + ok.Str("moonlight_pin"));

                        // the Thor's side: Moonlight's handshake with the PIN it was handed
                        var ml = new MoonlightPairClient("127.0.0.1", (int)sun.Long("http_port")) { DeviceName = "thor-e2e" };
                        var r = ml.Pair(ok.Str("moonlight_pin"), CancellationToken.None).Result;
                        A.Null(r, "Moonlight pairing");
                        var msgs = c.Until("pair_stream");
                        A.True(msgs.Last().Bool("ok"), Json.Write(msgs.Last()));
                        A.True(msgs.Any(m => m.Str("t") == "doc" && m.Str("doc") == "{\"t\":\"hello\",\"n\":1}"), "the backlog doc");
                        A.True(ctl.Devices.All().Single().StreamPaired);

                        for (int i = 2; i <= 4; i++) Write(log, i, "{\"t\":\"pos\",\"n\":" + i + "}");
                        var got = new List<Dictionary<string, object>>();
                        while (got.Count < 3) { var m = c.Next(); if (m.Str("t") == "doc") got.Add(m); }
                        A.Eq("{\"t\":\"pos\",\"n\":4}", got.Last().Str("doc"));
                        A.True(got.Last().Long("gt") > 0, "the game's clock from the line's timestamp");

                        // the game restarts: the log is emptied and starts over
                        using (var fs = new FileStream(log, FileMode.Open, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete)) fs.SetLength(0);
                        Write(log, 1, "{\"t\":\"again\"}");
                        var after = c.Until("doc");
                        A.True(after.Any(m => m.Str("t") == "log" && m.Str("event") == "restart"), "restart told to the device");
                        A.Eq("{\"t\":\"again\"}", after.Last().Str("doc"));
                    }
                }
                finally { ctl.Dispose(); }
            }
        }

        /// <summary>
        /// DsfDesktop.Tests.exe --serve --sunshine &lt;dir&gt;: the same setup kept running for a manual look (the bridge test page in a
        /// browser): prints the code and writes a document every second until Enter.
        /// </summary>
        public static void Serve()
        {
            var t = new Tmp();
            var ctl = Make(t, out var log);
            ctl.Start();
            Console.WriteLine("bridge: http://localhost:" + ctl.Bridge.Port + "/test   code: " + ctl.Codes.Display);
            ctl.Codes.Changed += () => Console.WriteLine("new code: " + ctl.Codes.Display);
            int seq = 1;
            var stop = false;
            new Thread(() => { Console.ReadLine(); stop = true; }) { IsBackground = true }.Start();
            while (!stop)
            {
                Write(log, seq, "{\"t\":\"pos\",\"seq\":" + seq + ",\"x\":" + (seq * 1.5).ToString(System.Globalization.CultureInfo.InvariantCulture) + ",\"zone\":\"Elwynn Forest\"}");
                seq++;
                Thread.Sleep(1000);
            }
            ctl.Dispose();
            t.Dispose();
        }
    }
}
