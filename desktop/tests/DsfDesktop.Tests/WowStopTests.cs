using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using DsfDesktop.Wow;

namespace DsfDesktop.Tests
{
    /// <summary>
    /// Desktop 0.2.35: the 0.2.34 review's findings (one start per press, a Retry never outlives its connect, an older Thor's words
    /// and reconnect) and the judge's Step 0 (a missing wowf row = needs repair; Desktop's own copy of the last good row)
    /// </summary>
    public static class WowStopTests
    {
        sealed class Pc
        {
            public WowScreen Screen = WowScreen.None;
            public BnetState Bnet = BnetState.SignedIn;
            public bool Pending;
            public readonly List<string> Did = new List<string>();
            public WowScreen FreshGoesTo = WowScreen.Starting;
            public bool Failed;
            public GameCheck Game = GameCheck.Unknown;
            public readonly List<Action> Runs = new List<Action>();
            public bool QueueRuns;

            public WowSupervisor Make() => new WowSupervisor(
                screen: () => Screen, bnet: () => Bnet,
                startBnet: () => { Did.Add("bnet"); return null; },
                showBnetSignIn: () => { Did.Add("signin"); return null; },
                startWow: () => { Did.Add("start"); Screen = FreshGoesTo; return null; },
                startPending: () => Pending,
                closeWow: () => { Did.Add("close"); Screen = WowScreen.None; return null; },
                focusWow: () => { Did.Add("focus"); return null; },
                run: a => { if (QueueRuns) Runs.Add(a); else a(); }, failed: () => Failed, game: () => Game);
        }

        static string Did(Pc pc) => string.Join(",", pc.Did);

        /// <summary>0.2.34 set Tries 0 on Retry, so the press's client closing again without a 309 was started a second time</summary>
        [Test]
        public static void ARetryIsOneStartPerPress()
        {
            var pc = new Pc { Screen = WowScreen.None, Failed = true };
            var s = pc.Make();
            s.Connect();
            A.Eq("", Did(pc));
            pc.Failed = false;
            A.True(s.Retry());
            A.Eq("start", Did(pc));
            // its client closes itself, no server error this time: never a second start for the same press
            pc.Screen = WowScreen.None;
            for (int i = 0; i < 10; i++) s.Step();
            A.Eq("start", Did(pc), "one start for one press");
            A.True(s.NeedsYou, "then it is the player's");
            // the next press: once more
            A.True(s.Retry());
            A.Eq("start,start", Did(pc));
        }

        /// <summary>0.2.34's Retry cleared the restart's flag: a second press during the close (up to 25 s) ran a second close/start</summary>
        [Test]
        public static void ARetryDuringARestartIsThatSameRestart()
        {
            var pc = new Pc { Screen = WowScreen.Login, Failed = true, QueueRuns = true };
            var s = pc.Make();
            s.Connect();
            pc.Failed = false;
            A.True(s.Retry());
            A.Eq(1, pc.Runs.Count, "the restart runs"); A.True(s.Busy);
            A.False(s.Retry(), "a second press while it runs is dropped");
            s.Connect(); s.Step();
            A.Eq(1, pc.Runs.Count, "no second restart beside it");
            pc.Runs[0]();
            A.Eq("close,start", Did(pc));
            A.False(s.Busy);
            // a press while a start is under way (the starter waits for the window) is that start too
            pc.Pending = true;
            A.False(s.Retry());
            pc.Pending = false;
        }

        /// <summary>0.2.34: a Retry still pending (Battle.net signed out, or the version check under way) outlived the Thor leaving, and
        /// a later connect that found WoW at its login restarted it unasked</summary>
        [Test]
        public static void ConnectAndDisconnectForgetAnUnansweredRetry()
        {
            var pc = new Pc { Screen = WowScreen.Login, Bnet = BnetState.SignInNeeded, Failed = true };
            var s = pc.Make();
            s.Connect();
            pc.Failed = false; s.Retry();
            A.True(s.RetryPending, "waits for Battle.net's sign-in");
            s.Disconnect();
            A.False(s.RetryPending, "the Thor left: the press goes with it");
            pc.Bnet = BnetState.SignedIn;
            s.Connect();
            A.Eq("signin,focus", Did(pc), "WoW at its login: in front, never restarted for an old press");
            // the version check under way, then a connect
            var pc2 = new Pc { Screen = WowScreen.Login, Game = GameCheck.Checking, Failed = true };
            var s2 = pc2.Make();
            s2.Connect();
            pc2.Failed = false; s2.Retry();
            A.True(s2.RetryPending);
            pc2.Game = GameCheck.Current;
            s2.Connect();
            A.Eq("focus", Did(pc2), "a new connect is not the press");
        }

        /// <summary>Thor 1487 reads neither "stopped" nor "pc_game": while stopped with WoW not running it gets "start_failed" ("WoW
        /// didn't start", never "Starting WoW on your PC" for ever) and the reason as "why"; WoW at its login keeps "running"</summary>
        [Test]
        public static void AnOlderThorHearsWhyDesktopStopped()
        {
            Dictionary<string, object> Msg(string state) => new Dictionary<string, object> { ["state"] = state, ["screen"] = "none" };
            var d = Msg("stopped");
            A.True(WowFlow.StopWords(d, false, GameCheck.Outdated, false));
            A.Eq(true, d["stopped"]); A.Eq("start_failed", d["state"]); A.Eq(WowFlow.OutdatedText, d["why"]);
            d = Msg("stopped");
            WowFlow.StopWords(d, true, GameCheck.Unknown, false);
            A.Eq("start_failed", d["state"], "a 309 with WoW closed (11-17 22:58)"); A.Eq(WowFlow.ConnectFailedText, d["why"]);
            d = Msg("stopped");
            WowFlow.StopWords(d, true, GameCheck.NeedsRepair, false);
            A.Eq(WowFlow.RepairText, d["why"], "a broken game says so first");
            d = Msg("running");
            WowFlow.StopWords(d, true, GameCheck.Unknown, false);
            A.Eq("running", d["state"], "WoW at its login: 1487 shows WoW's screen and its 309 line");
            d = Msg("starting");
            A.False(WowFlow.StopWords(d, false, GameCheck.Current, false));
            A.Eq("starting", d["state"]); A.False(d.ContainsKey("stopped")); A.False(d.ContainsKey("why"));
            d = Msg("running");
            A.False(WowFlow.StopWords(d, true, GameCheck.Outdated, true), "signed in: never stopped");
            foreach (var t in new[] { WowFlow.OutdatedText, WowFlow.RepairText, WowFlow.ConnectFailedText })
                A.False(t.IndexOf("on the PC", StringComparison.OrdinalIgnoreCase) >= 0, "never 'on the PC': " + t);
            // its reconnect is its Retry: only while stopped, and not more often than every 2 minutes
            A.True(WowFlow.LegacyReconnectRetries(true, 1e9));
            A.False(WowFlow.LegacyReconnectRetries(false, 1e9), "not stopped: a reconnect starts nothing");
            A.False(WowFlow.LegacyReconnectRetries(true, WowFlow.LegacyRetryEveryS - 1), "a flapping bridge never loops the game");
            A.True(WowFlow.LegacyReconnectRetries(true, WowFlow.LegacyRetryEveryS));
        }

        /// <summary>the no-start timeout never says "sign in on the PC" (the user's ruling 2026-11-18)</summary>
        [Test]
        public static void TheStartTimeoutNeverSendsThePlayerToThePc()
        {
            var now = new DateTime(2026, 11, 18, 7, 0, 0, DateTimeKind.Utc);
            var st = new WowStarter(() => false, () => false, () => new LaunchPlan { Exe = "Wow.exe", Args = "", WorkDir = "." }, p => null, () => now);
            st.Ensure();
            A.Eq(WowState.Starting, st.State);
            now = now.AddMinutes(4); st.Tick();
            A.Eq(WowState.StartFailed, st.State);
            A.False(st.Why.IndexOf("PC", StringComparison.Ordinal) >= 0, "why: " + st.Why);
        }

        // ---------------------------------------------------------------- Step 0: the missing wowf row

        /// <summary>the user's PC 2026-11-18 after 06:32: the retail row only (the Agent dropped wowf's), LF endings</summary>
        const string Header = "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|CDN Servers!STRING:0|Tags!STRING:0|Armadillo!STRING:0|Last Activated!STRING:0|Version!STRING:0|KeyRing!HEX:16|Product!STRING:0";
        const string Retail = "us|1|505802ef|4831465d|||tpr/wow|level3.blizzard.com us.cdn.blizzard.com|http://level3.blizzard.com/?maxhosts=8|Windows x86_64 US? acct-USA? geoip-US? enUS speech?:Windows x86_64 US? acct-USA? geoip-US? enUS text?|||12.1.5.70961|3ca5|wow";
        const string Forever = "us|1|36270000|4831465d|||tpr/wow|level3.blizzard.com us.cdn.blizzard.com|http://level3.blizzard.com/?maxhosts=8|Windows x86_64 US? acct-USA? geoip-US? enUS speech?:Windows x86_64 US? acct-USA? geoip-US? enUS text?|||1.60.1.70938||wowf";

        [Test]
        public static void AMissingRowIsAddedBackUnderTheFilesOwnHeader()
        {
            var lf = Header + "\n" + Retail + "\n";
            A.Eq(null, BuildInfoFile.RowLine(lf, "wowf"));
            A.Eq(Retail, BuildInfoFile.RowLine(lf, "wow"));
            var added = BuildInfoFile.AddRow(lf, "wowf", Header, Forever);
            A.Eq(lf + Forever + "\n", added, "the retail row byte for byte, the row appended, LF kept");
            A.Eq(Forever, BuildInfoFile.RowLine(added, "wowf"));
            var crlf = Header + "\r\n" + Retail;
            A.Eq(crlf + "\r\n" + Forever, BuildInfoFile.AddRow(crlf, "wowf", Header, Forever), "CRLF kept, no trailing newline added");
            A.Eq(null, BuildInfoFile.AddRow(added, "wowf", Header, Forever), "a row that is there is never replaced");
            A.Eq(null, BuildInfoFile.AddRow(lf, "wowf", Header, Forever.Replace("36270000", "")), "a blank Build Key never goes in");
            A.Eq(null, BuildInfoFile.AddRow(lf, "wowf", Header, Retail), "another product's row is not this one");
            // a saved row under an older header (one column fewer) maps by name
            var oldHeader = Header.Replace("|KeyRing!HEX:16", "");
            var oldRow = Forever.Replace("1.60.1.70938||wowf", "1.60.1.70938|wowf");
            var mapped = BuildInfoFile.AddRow(lf, "wowf", oldHeader, oldRow);
            A.Eq(Forever, BuildInfoFile.RowLine(mapped, "wowf"), "mapped onto the file's own columns");
            A.Eq("1.60.1.70938", BuildInfoFile.VersionIn("1.60.1.70938"));
            A.Eq("1.60.1.70938", BuildInfoFile.VersionIn("Version 1.60.1.70938"), "Wow.exe's ProductVersion form");
            A.Eq(null, BuildInfoFile.VersionIn("160.1.7093"));
        }

        [Test]
        public static void WowExesVersionIsReadFromItsFile()
        {
            using (var t = new Tmp())
            {
                A.Eq(null, BuildInfoFile.ExeVersion(t.P("Wow.exe")), "no exe");
                File.WriteAllText(t.P("Wow.exe"), "not a program");
                A.Eq("", BuildInfoFile.ExeVersion(t.P("Wow.exe")), "an exe with no version: there, unreadable");
            }
            // a real version resource (this test exe's own, built with VER): the text form, as Wow.exe's "1.60.1.70938"
            var me = typeof(WowStopTests).Assembly.Location;
            var v = BuildInfoFile.ExeVersion(me);
            A.True(v != null && v.Split('.').Length == 4, "four parts: " + v);
        }

        [Test]
        public static void DesktopKeepsACopyOfTheLastGoodRow()
        {
            using (var t = new Tmp())
            {
                var root = t.P("wow"); Directory.CreateDirectory(root);
                var bi = Path.Combine(root, ".build.info");
                var cache = t.P("data", "patch", "wowf_row.json");
                var k = new BuildInfoKeeper(() => root, cache);
                var both = Header + "\n" + Retail + "\n" + Forever + "\n";
                File.WriteAllText(bi, both);
                k.Remember("1.60.1.70959");
                A.False(File.Exists(cache), "a row of another build than Wow.exe's is not 'good'");
                k.Remember("1.60.1.70938");
                A.True(File.Exists(cache), "saved");
                A.Eq("1.60.1.70938", k.Saved().Item3);
                A.Eq(both, File.ReadAllText(bi), "Remember never writes .build.info");
                // the Agent drops the row (06:32)
                var dropped = Header + "\n" + Retail + "\n";
                File.WriteAllText(bi, dropped);
                A.False(k.Repair("1.60.1.70959"), "the copy is of another build than Wow.exe now: not used");
                A.Eq(dropped, File.ReadAllText(bi));
                A.True(k.Repair("1.60.1.70938"));
                A.Eq(both, File.ReadAllText(bi), "the row back, the retail row untouched");
                A.Eq(dropped, File.ReadAllText(bi + ".dsf-bak"), "the file as it was, kept beside it");
                A.False(k.Repair("1.60.1.70938"), "there now: never written again");
                // no copy at all: nothing written
                var k2 = new BuildInfoKeeper(() => root, t.P("none", "wowf_row.json"));
                File.WriteAllText(bi, dropped);
                A.False(k2.Repair("1.60.1.70938"));
                A.Eq(dropped, File.ReadAllText(bi));
                // no install: nothing at all
                A.False(new BuildInfoKeeper(() => null, cache).Repair("1.60.1.70938"));
            }
        }

        /// <summary>the judge's Step 0: _forever_\Wow.exe there and no wowf row = needs repair (0.2.34 read Unknown and would start
        /// WoW); Desktop's copy put back when it fits; a row of another build than Wow.exe's is never overwritten and never started</summary>
        [Test]
        public static void AMissingRowIsARepairAndNeverStarted()
        {
            var ok = BnetState.SignedIn;
            A.Eq(WowAction.Repair, WowFlow.Decide(ok, WowScreen.None, 0, false, game: GameCheck.NeedsRepair));
            A.Eq(WowAction.Repair, WowFlow.Decide(ok, WowScreen.Login, 1, false, retry: true, game: GameCheck.NeedsRepair), "a Retry never restarts it");
            A.Eq(WowAction.Focus, WowFlow.Decide(ok, WowScreen.World, 0, false, game: GameCheck.NeedsRepair), "in the world: left alone");
            var pc = new Pc { Screen = WowScreen.None, Game = GameCheck.NeedsRepair };
            var s = pc.Make();
            s.Connect();
            A.Eq("", Did(pc)); A.Eq(WowAction.Repair, s.StoppedFor); A.False(s.Active);
            A.Eq(WowFlow.RepairText, WowFlow.StopText(s.StoppedFor));

            var now = new DateTime(2026, 11, 18, 7, 0, 0, DateTimeKind.Utc);
            var text = Header + "\n" + Retail + "\n";
            string exe = "1.60.1.70938";
            var repairs = new List<string>(); var remembered = new List<string>(); var urls = new List<string>();
            bool canRepair = false;
            var versions = "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|BuildId!DEC:4|VersionsName!String:0|ProductConfig!HEX:16\nus|ad88|4831||70938|1.60.1.70938|0d21\n";
            var c = new GameVersionCheck(() => BuildInfo.Parse(text), u => { urls.Add(u); return versions; }, "wowf", () => now, a => a(),
                exeVersion: () => exe,
                repair: v => { repairs.Add(v); if (!canRepair) return false; text = text + Forever + "\n"; return true; },
                remember: v => remembered.Add(v));
            A.Eq(GameCheck.NeedsRepair, c.Poll(), "Wow.exe there, no row");
            A.Eq("1.60.1.70938", c.Installed, "the installed build from Wow.exe itself");
            A.Eq(0, urls.Count, "Blizzard is not asked for a broken install");
            A.Eq("1.60.1.70938", repairs.Single(), "the copy was tried for Wow.exe's build");
            A.Eq(GameCheck.NeedsRepair, c.Poll(), "cached for a few seconds");
            A.Eq(1, repairs.Count);
            // before a start: read again now; this time Desktop's copy fits
            canRepair = true;
            A.Eq(GameCheck.Current, c.PollNow(), "row back: checked as usual");
            A.Eq(1, urls.Count); A.Eq("1.60.1.70938", remembered.Last(), "and remembered as good");
            A.Eq(GameCheck.Current, c.Peek());
            // the Agent half-way through its own update: a row of another build than Wow.exe = repair, never overwritten
            text = Header + "\n" + Retail + "\n" + Forever.Replace("1.60.1.70938", "1.60.1.70959") + "\n";
            int r0 = repairs.Count;
            A.Eq(GameCheck.NeedsRepair, c.PollNow());
            A.Eq(r0, repairs.Count, "a row that is there is never replaced");
            // no _forever_ Wow.exe: as before (no row = unknown, a start goes ahead)
            exe = null; text = Header + "\n" + Retail + "\n";
            A.Eq(GameCheck.Unknown, c.PollNow());
            // an exe whose version can't be read and no row: still a repair (Wow.exe is there)
            exe = "";
            A.Eq(GameCheck.NeedsRepair, c.PollNow());
        }

        /// <summary>the "wow" message's check runs only while a Thor is connected: Peek reads nothing and asks nothing</summary>
        [Test]
        public static void PeekAsksNothing()
        {
            int reads = 0, fetches = 0;
            var c = new GameVersionCheck(() => { reads++; return new List<BuildInfoRow>(); }, u => { fetches++; return null; }, "wowf",
                () => DateTime.UtcNow, a => a());
            A.Eq(GameCheck.Unknown, c.Peek());
            A.Eq(0, reads); A.Eq(0, fetches);
            c.Poll();
            A.Eq(1, reads);
            c.Peek(); c.Peek();
            A.Eq(1, reads); A.Eq(0, fetches);
        }
    }
}
