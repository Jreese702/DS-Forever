using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Threading;
using System.Threading.Tasks;
using DsfDesktop.Pairing;
using DsfDesktop.Sunshine;

namespace DsfDesktop.Tests
{
    /// <summary>
    /// The real thing, only with --sunshine &lt;folder holding sunshine.exe&gt;: our config and login written, the real Sunshine started
    /// on 127.0.0.1 (bind_address, so no firewall prompt and nothing on the LAN) at a spare port, and a Moonlight-protocol client
    /// paired with it by a PIN that our API code hands to Sunshine. No web page anywhere. Everything lives in a temp folder.
    /// </summary>
    public static class SunshineLive
    {
        public static string ExeDir;

        [Test]
        public static void PairsByPinWithNoWebPage()
        {
            if (ExeDir == null) throw new SkipException("run with --sunshine <dir> for the live test");
            using (var t = new Tmp())
            {
                var layout = new SunshineLayout { ExeDir = ExeDir, DataDir = t.P("sunshine") };
                int port = PortPlanner.Pick(57989, PortPlanner.TcpFree, PortPlanner.UdpFree);
                A.True(port > 0, "a free port");
                SunshineConfig.Write(layout, new SunshineOptions { Port = port, BindAddress = "127.0.0.1", HostName = "DSF-SelfTest" });
                var host = new SunshineHost(layout);
                A.True(host.SetCredentials("dsforever", "selftest123"), "--creds wrote our credentials file");
                try
                {
                    host.Start();
                    using (var api = new SunshineApi(port, "dsforever", "selftest123", layout.Cert))
                    {
                        var until = DateTime.UtcNow.AddSeconds(60);
                        while (!api.Alive(CancellationToken.None).Result)
                        {
                            if (DateTime.UtcNow > until) throw new AssertFailed("Sunshine's API never came up");
                            Thread.Sleep(500);
                        }
                        Console.WriteLine("  sunshine API up on " + (port + 1));
                        A.Eq(0, api.Clients(CancellationToken.None).Result.Count);

                        using (var bad = new SunshineApi(port, "dsforever", "wrong", layout.Cert))
                        {
                            try { bad.Pending(CancellationToken.None).Wait(); throw new AssertFailed("a wrong login was accepted"); }
                            catch (AggregateException e) when (e.InnerException is UnauthorizedAccessException) { }
                        }

                        var pin = PairingCodes.NewMoonlightPin();
                        var client = new MoonlightPairClient("127.0.0.1", port) { DeviceName = "selftest" };
                        var pairing = client.Pair(pin, CancellationToken.None);
                        var err = api.CompletePairing(pin, "AYN Thor (selftest)", "127.0.0.1", TimeSpan.FromSeconds(30), CancellationToken.None).Result;
                        A.Null(err, "our side handed Sunshine the PIN");
                        var result = pairing.Result;
                        A.Null(result, "the Moonlight handshake completed");
                        var clients = api.Clients(CancellationToken.None).Result;
                        Console.WriteLine("  paired clients: " + Json.Write(clients));
                        A.True(clients.Any(c => c.Str("name") == "AYN Thor (selftest)"), "Sunshine lists the device under our name");

                        // a WRONG pin: the client must notice (the server's challenge response won't match)
                        var client2 = new MoonlightPairClient("127.0.0.1", port) { DeviceName = "selftest2" };
                        var p2 = client2.Pair("1111", CancellationToken.None);
                        var sw = System.Diagnostics.Stopwatch.StartNew();
                        var bridgeSide = api.CompletePairing("2222", "Wrong PIN", "127.0.0.1", TimeSpan.FromSeconds(30), CancellationToken.None).Result;
                        Console.WriteLine("  wrong PIN, our side: " + bridgeSide + " (" + sw.ElapsedMilliseconds + " ms)");
                        A.NotNull(bridgeSide, "our side reports the failure too");
                        var r2 = p2.Result;
                        A.NotNull(r2, "a wrong PIN does not pair");
                        Console.WriteLine("  wrong PIN: " + r2);

                        var uuid = clients.First(c => c.Str("name") == "AYN Thor (selftest)").Str("uuid");
                        A.True(api.Unpair(uuid, CancellationToken.None).Result, "unpair through the API");
                        A.False(api.Clients(CancellationToken.None).Result.Any(c => c.Str("name") == "AYN Thor (selftest)"));
                    }
                }
                finally
                {
                    host.Dispose();
                }
                A.True(File.Exists(layout.Cert), "Sunshine's cert lives in our data dir");
            }
        }
    }
}
