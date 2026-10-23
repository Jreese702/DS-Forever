using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using DsfDesktop.Sunshine;
using DsfDesktop.Wow;

namespace DsfDesktop.Tests
{
    public static class StartWowTests
    {
        const string BuildInfoText =
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|CDN Servers!STRING:0|Tags!STRING:0|Armadillo!STRING:0|Last Activated!STRING:0|Version!STRING:0|KeyRing!HEX:16|Product!STRING:0\n" +
            "us|1|18b4|3023|||tpr/wow|h|s|t|||12.1.5.70529||wow\n" +
            "us|1|ad18|3023|||tpr/wow|h|s|t|||1.60.1.70532||wow_classic_beta\n" +
            "us|1|2582|3023|||tpr/wow|h|s|t|||1.60.1.70532||wowf\n";

        static WowInstall Install(Tmp t, bool forever = true, bool beta = true)
        {
            var root = t.P("World of Warcraft");
            if (beta) { Directory.CreateDirectory(Path.Combine(root, "_classic_beta_")); File.WriteAllText(Path.Combine(root, "_classic_beta_", "WowB.exe"), ""); }
            if (forever) { Directory.CreateDirectory(Path.Combine(root, "_forever_")); File.WriteAllText(Path.Combine(root, "_forever_", "Wow.exe"), ""); }
            File.WriteAllText(Path.Combine(root, ".build.info"), BuildInfoText);
            return WowLocator.Describe(root, new DateTime(2026, 10, 23));
        }

        [Test]
        public static void PlanUsesBattleNetWithTheFlavorsProduct()
        {
            using (var t = new Tmp())
            {
                var inst = Install(t);
                var bnet = @"C:\Program Files (x86)\Battle.net\Battle.net.exe";
                var before = WowLaunch.Plan(inst, new DateTime(2026, 11, 3), bnet);
                A.True(before.ViaBattleNet);
                A.Eq("_classic_beta_", before.Flavor, "the beta until launch day");
                A.Eq("--exec=\"launch_uid wow_classic_beta\"", before.Args);
                var after = WowLaunch.Plan(inst, new DateTime(2026, 11, 4), bnet);
                A.Eq("_forever_", after.Flavor, "wowf from launch day");
                A.Eq("--exec=\"launch_uid wowf\"", after.Args);
            }
        }

        [Test]
        public static void PlanFallsBackToTheGameExe()
        {
            using (var t = new Tmp())
            {
                var inst = Install(t);
                var p = WowLaunch.Plan(inst, new DateTime(2026, 10, 23), null);
                A.False(p.ViaBattleNet);
                A.Eq(Path.Combine(inst.Root, "_classic_beta_", "WowB.exe"), p.Exe);
                A.Eq(Path.Combine(inst.Root, "_classic_beta_"), p.WorkDir);
                A.Eq("", p.Args, "no credentials, no arguments");
            }
            using (var t = new Tmp())
            {
                var inst = Install(t, forever: true, beta: false);
                A.Eq("_forever_", WowLaunch.Plan(inst, new DateTime(2026, 10, 23), null).Flavor, "only _forever_ installed: that one, even before launch day");
            }
            A.Null(WowLaunch.Plan(null, DateTime.Now, null));
        }

        sealed class Fake
        {
            public bool Window, Process;
            public int Starts;
            public string StartError;
            public DateTime Now = new DateTime(2026, 10, 23, 12, 0, 0, DateTimeKind.Utc);
            public LaunchPlan Plan = new LaunchPlan { Exe = "Battle.net.exe", Product = "wow_classic_beta", ViaBattleNet = true };
            public readonly List<string> States = new List<string>();
            public WowStarter Make()
            {
                var s = new WowStarter(() => Window, () => Process, () => Plan, p => { Starts++; return StartError; }, () => Now);
                s.Changed += (st, why) => States.Add(WowStarter.Word(st));
                return s;
            }
        }

        [Test]
        public static void StartsOnceThenSeesTheWindow()
        {
            var f = new Fake();
            var s = f.Make();
            A.False(s.Ensure());
            A.Eq(WowState.Starting, s.State);
            A.Eq(1, f.Starts);
            A.True(s.Why.Contains("Battle.net"));
            f.Process = true;
            A.False(s.Ensure());
            A.Eq(1, f.Starts, "a second stream / focus while it starts doesn't start it again");
            f.Now = f.Now.AddSeconds(30);
            s.Tick();
            A.Eq(WowState.Starting, s.State);
            f.Window = true;
            s.Tick();
            A.Eq(WowState.Running, s.State);
            A.True(s.Ensure(), "up: nothing to start");
            A.Eq(1, f.Starts);
            f.Window = false; f.Process = false;
            s.Tick();
            A.Eq(WowState.Stopped, s.State, "closed on the PC");
            A.Eq("starting,running,stopped", string.Join(",", f.States));
        }

        [Test]
        public static void AlreadyLoadingIsNotStartedAgain()
        {
            var f = new Fake { Process = true };
            var s = f.Make();
            s.Ensure();
            A.Eq(0, f.Starts, "a WoW process without its window yet is a game loading");
            A.Eq(WowState.Starting, s.State);
        }

        [Test]
        public static void TimeoutAndFailuresAreReported()
        {
            var f = new Fake();
            var s = f.Make();
            s.Ensure();
            f.Now = f.Now.AddMinutes(4);
            s.Tick();
            A.Eq(WowState.StartFailed, s.State);
            A.True(s.Why.Contains("sign in"), s.Why);
            s.Ensure();
            A.Eq(2, f.Starts, "after a failure the next stream tries again");

            var g = new Fake { StartError = "file not found" };
            var s2 = g.Make();
            s2.Ensure();
            A.Eq(WowState.StartFailed, s2.State);
            A.True(s2.Why.Contains("file not found"));

            var h = new Fake { Plan = null };
            var s3 = h.Make();
            s3.Ensure();
            A.Eq(WowState.StartFailed, s3.State);
            A.True(s3.Why.Contains("isn't installed"));
        }

        // the block Sunshine 2026.914 prints at start (from the dev PC's log, trimmed)
        const string SunshineLog =
            "[2026-10-23 13:40:39.250]: Info: Sunshine version: 2026.914.233613\n" +
            "[2026-10-23 13:40:39.260]: Info: Currently available display devices:\n" +
            "[\n  {\n    \"device_id\": \"{0555c8df-4293-5213-8560-b3be55b8ee89}\",\n    \"display_name\": \"\\\\\\\\.\\\\DISPLAY5\",\n" +
            "    \"friendly_name\": \"DELL P2317H\",\n    \"info\": {\n      \"origin_point\": { \"x\": -1920, \"y\": 0 },\n      \"primary\": false\n    }\n  },\n" +
            "  {\n    \"device_id\": \"{632454dc-9d1e-58df-8330-a72a87581e8a}\",\n    \"display_name\": \"\\\\\\\\.\\\\DISPLAY6\",\n" +
            "    \"friendly_name\": \"PG32UCDP\",\n    \"info\": {\n      \"origin_point\": { \"x\": 0, \"y\": 0 },\n      \"primary\": true\n    }\n  }\n]\n" +
            "[2026-10-23 13:40:39.300]: Info: next line\n";

        [Test]
        public static void SunshineDisplayListParses()
        {
            var list = Displays.ParseSunshineLog("old stuff\n" + SunshineLog.Replace("PG32UCDP", "OLD") + SunshineLog);
            A.Eq(2, list.Count);
            A.Eq(@"\\.\DISPLAY6", list[1].DisplayName);
            A.Eq("{632454dc-9d1e-58df-8330-a72a87581e8a}", list[1].DeviceId);
            A.Eq("PG32UCDP", list[1].Friendly, "the LAST block wins");
            A.True(list[1].Primary);
            A.Eq(@"\\.\DISPLAY6", Displays.Captured(list, null), "unset: the primary");
            A.Eq(@"\\.\DISPLAY5", Displays.Captured(list, "{0555c8df-4293-5213-8560-b3be55b8ee89}"));
            A.Eq(0, Displays.ParseSunshineLog("no list here").Count);
        }

        [Test]
        public static void CaptureDecision()
        {
            A.Eq(Displays.Fix.None, Displays.Decide(@"\\.\DISPLAY6", @"\\.\DISPLAY6", true, true));
            A.Eq(Displays.Fix.RecaptureSunshine, Displays.Decide(@"\\.\DISPLAY5", @"\\.\DISPLAY6", false, true), "idle: Sunshine follows WoW");
            A.Eq(Displays.Fix.MoveWow, Displays.Decide(@"\\.\DISPLAY5", @"\\.\DISPLAY6", true, true), "streaming: WoW comes to the stream");
            A.Eq(Displays.Fix.None, Displays.Decide(@"\\.\DISPLAY9", @"\\.\DISPLAY6", false, false), "unknown to Sunshine: leave it");
            A.Eq(Displays.Fix.None, Displays.Decide(null, @"\\.\DISPLAY6", true, true));
        }

        static Displays.RECT R(int l, int t, int r, int b) => new Displays.RECT { Left = l, Top = t, Right = r, Bottom = b };

        [Test]
        public static void MovingAWindowBetweenMonitors()
        {
            var left = R(-1920, 0, 0, 1080);
            var main = R(0, 0, 2560, 1440);
            var full = Displays.Translate(R(-1920, 0, 0, 1080), left, main);
            A.Eq(0, full.Left); A.Eq(2560, full.Right); A.Eq(1440, full.Bottom);   // windowed fullscreen fills the new monitor
            var win = Displays.Translate(R(-1800, 100, -800, 900), left, main);
            A.Eq(120, win.Left); A.Eq(100, win.Top); A.Eq(1000, win.W); A.Eq(800, win.H);
            var big = Displays.Translate(R(0, 0, 2400, 1400), main, left);         // bigger than the target: clamped inside it
            A.Eq(-1920, big.Left); A.Eq(1920, big.W); A.Eq(1080, big.H);
        }

        [Test]
        public static void ConfNamesTheCapturedDisplay()
        {
            var l = new SunshineLayout { ExeDir = @"C:\a", DataDir = @"C:\b" };
            A.True(SunshineConfig.ConfText(l, new SunshineOptions { OutputName = "{632454dc-9d1e-58df-8330-a72a87581e8a}" })
                .Contains("output_name = {632454dc-9d1e-58df-8330-a72a87581e8a}\n"));
            A.False(SunshineConfig.ConfText(l, new SunshineOptions()).Contains("output_name"), "unset: Sunshine's primary display");
            A.False(SunshineConfig.AppsText(new SunshineOptions { EventExe = "" }).Contains("prep-cmd"), "no prep command (portable Sunshine can't run it)");
        }
    }
}
