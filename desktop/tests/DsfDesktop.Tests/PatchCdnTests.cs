using System;
using System.IO;
using System.Linq;
using System.Threading;
using DsfDesktop.Wow.Patch;
using static DsfDesktop.Tests.PatchKit;

namespace DsfDesktop.Tests
{
    /// <summary>Desktop 0.2.36's CDN client against a fake CDN on the loopback: the Thor's integrity rules (jaredgei PR #54)</summary>
    public static class PatchCdnTests
    {
        static CdnClient Client(params FakeCdn[] cdns) => new CdnClient(cdns.Select(c => c.Host), "tpr/wow", idle: TimeSpan.FromSeconds(2), https: false);

        static Exception Threw(Action a) { try { a(); return null; } catch (Exception e) { return e; } }

        [Test]
        public static void ARangeMustBeA206OfExactlyItsLength()
        {
            using (var cdn = new FakeCdn())
            {
                var data = Enumerable.Range(0, 5000).Select(i => (byte)i).ToArray();
                var k = Key("enc");
                cdn.Put("data", k, data);
                var c = Client(cdn);
                A.Eq(Md5(data.Skip(100).Take(50).ToArray()), Md5(c.Fetch("data", k, 100, 149)));
                // a server that ignores the Range (a 200 with the whole file) is not the range
                cdn.Answer = (p, r) => new FakeCdn.Reply { Code = 200, Body = data };
                A.True(Threw(() => c.Fetch("data", k, 100, 149)) is CdnException, "a 200 to a range");
                // a 206 of the wrong length
                cdn.Answer = (p, r) => new FakeCdn.Reply { Code = 206, Body = data.Take(10).ToArray(), ContentRange = "bytes 100-109/5000" };
                A.True(Threw(() => c.Fetch("data", k, 100, 149)) is CdnException, "a short 206");
                // a 206 from another place
                cdn.Answer = (p, r) => new FakeCdn.Reply { Code = 206, Body = data.Take(50).ToArray(), ContentRange = "bytes 0-49/5000" };
                A.True(Threw(() => c.Fetch("data", k, 100, 149)) is CdnException, "a 206 from 0");
                A.Eq(null, CdnClient.BodyProblem(206, 50, 100, 100, 149, 50));
                A.NotNull(CdnClient.BodyProblem(200, 50, -1, -1, -1, 49), "a short body");
            }
        }

        [Test]
        public static void HostsInOrder404IsMissingAnd5xxGoesOn()
        {
            using (var bad = new FakeCdn())
            using (var good = new FakeCdn())
            {
                var k = Key("cfg");
                var cfg = B("# Build Configuration\ninstall = a b\n");
                var ck = Md5(cfg);
                good.Put("config", ck, cfg);
                bad.Answer = (p, r) => new FakeCdn.Reply { Code = 503 };
                A.Eq(Md5(cfg), Md5(Client(bad, good).Fetch("config", ck, verify: b => ConfigFile.Ok(b, ck))), "the next host after a 503");
                A.Eq(1, bad.Got(ck).Count);
                // a portal's "200" page is not the config: the next host answers
                bad.Answer = (p, r) => new FakeCdn.Reply { Body = B("<html>Sign in to the hotel Wi-Fi</html>") };
                A.Eq(Md5(cfg), Md5(Client(bad, good).Fetch("config", ck, verify: b => ConfigFile.Ok(b, ck))), "the portal page refused");
                // missing everywhere = null (not an error); missing on one and failing on the other = an error (retry, never "missing")
                A.Null(Client(good).Fetch("data", k));
                bad.Answer = (p, r) => new FakeCdn.Reply { Code = 500 };
                A.True(Threw(() => Client(good, bad).Fetch("data", k)) is CdnException, "a 404 and a 500 is a failure");
                // the portal on every host = a failure
                bad.Answer = (p, r) => new FakeCdn.Reply { Body = B("<html>portal</html>") };
                A.True(Threw(() => Client(bad).Fetch("config", ck, verify: b => ConfigFile.Ok(b, ck))) is CdnException);
            }
            // nobody listening at all
            var dead = new CdnClient(new[] { "127.0.0.1:1" }, "tpr/wow", idle: TimeSpan.FromSeconds(1), https: false);
            A.True(Threw(() => dead.Fetch("config", Key("x"))) is CdnException, "no answer");
        }

        /// <summary>a big body to a .part, resumed by a Range request after a cut, renamed only when whole</summary>
        [Test]
        public static void ABigBodyResumesFromItsPart()
        {
            using (var cdn = new FakeCdn())
            using (var t = new Tmp())
            {
                var data = Enumerable.Range(0, 300000).Select(i => (byte)(i * 31)).ToArray();
                var k = Key("exe");
                cdn.Put("data", k, data);
                // the first answer breaks off after 100,000 bytes
                int calls = 0;
                cdn.Answer = (p, r) => ++calls == 1 ? new FakeCdn.Reply { Body = data, SendOnly = 100000 } : null;
                var dest = t.P("Wow.exe.blte");
                var c = Client(cdn);
                A.True(Threw(() => c.FetchTo("data", k, dest)) is CdnException, "a cut answer");
                A.False(File.Exists(dest), "never the whole name while cut");
                A.Eq(100000L, new FileInfo(dest + ".part").Length, "the part kept");
                long seenTotal = -1;
                A.True(c.FetchTo("data", k, dest, progress: (n, whole) => seenTotal = whole));
                A.Eq(Md5(data), Md5(File.ReadAllBytes(dest)));
                A.False(File.Exists(dest + ".part"));
                A.True(cdn.Requests.Last().EndsWith("bytes=100000-"), "resumed: " + cdn.Requests.Last());
                A.Eq(300000L, seenTotal, "the whole length for the bar");
                // a server that answers a resume with the whole file: started over, still right
                File.WriteAllBytes(dest + ".part", data.Take(5000).ToArray());
                File.Delete(dest);
                cdn.Answer = (p, r) => new FakeCdn.Reply { Body = data };
                A.True(c.FetchTo("data", k, dest));
                A.Eq(Md5(data), Md5(File.ReadAllBytes(dest)));
                // a verify that fails deletes the part
                cdn.Answer = null;
                A.True(Threw(() => c.FetchTo("data", k, t.P("x.index"), verify: p => false)) is CdnException);
                A.False(File.Exists(t.P("x.index.part")));
            }
        }

        [Test]
        public static void NoDataForTheIdleTimeIsAFailureAndCancelStopsAtOnce()
        {
            using (var cdn = new FakeCdn())
            using (var t = new Tmp())
            {
                var k = Key("slow");
                cdn.Answer = (p, r) => new FakeCdn.Reply { Body = new byte[100000], StallMs = 4000 };
                var c = new CdnClient(new[] { cdn.Host }, "tpr/wow", idle: TimeSpan.FromSeconds(1), https: false);
                var sw = System.Diagnostics.Stopwatch.StartNew();
                A.True(Threw(() => c.FetchTo("data", k, t.P("a"))) is CdnException, "stalled");
                A.True(sw.ElapsedMilliseconds < 3500, "the idle limit, not the stall (" + sw.ElapsedMilliseconds + " ms)");
                var cts = new CancellationTokenSource(300);
                sw.Restart();
                var e = Threw(() => new CdnClient(new[] { cdn.Host }, "tpr/wow", idle: TimeSpan.FromSeconds(30), https: false).FetchTo("data", k, t.P("b"), ct: cts.Token));
                A.True(e is OperationCanceledException, "cancelled: " + e?.GetType().Name);
                A.True(sw.ElapsedMilliseconds < 2000, "at once (" + sw.ElapsedMilliseconds + " ms)");
            }
        }
    }
}
