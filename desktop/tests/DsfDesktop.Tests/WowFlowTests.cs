using System.IO;
using System;
using System.Collections.Generic;
using DsfDesktop.Wow;
using DsfDesktop.GameLog;
using System.Linq;

namespace DsfDesktop.Tests
{
    /// <summary>the stream's WoW rules (the user, 2026-10-28): the screen, Battle.net, what to do on a connect, the one retry</summary>
    public static class WowFlowTests
    {
        static LoginLog Log(params string[] lines)
        {
            var l = new LoginLog();
            foreach (var x in lines) l.Line(x);
            return l;
        }

        // the user's PC, 2026-10-28 14:15 (_forever_\Logs\General.log)
        const string LoginScreen = "10/28 14:15:52.905  [N][Lua] Switching to screen=\"AccountLogin\" (from \"none\")";
        const string GlueStarted = "10/28 14:15:57.557  [N][Connection] Glue login started loginType=\"(enum)1\" launcherPortal=\"us.actual.battle.net\"";
        const string Fatal308 = "10/28 14:15:58.105  [N][Connection] Fatal login error received result=\" (308)\"";
        const string CharSelect = "10/28 14:20:00.000  [N][Lua] Switching to screen=\"CharacterSelect\" (from \"AccountLogin\")";

        [Test]
        public static void LoginLogReadsTheSignInLines()
        {
            var l = Log(LoginScreen);
            A.Eq("AccountLogin", l.LastScreen); A.False(l.PastLogin); A.Eq(null, l.Error);
            l.Line(GlueStarted); A.True(l.SignInStarted);
            l.Line(Fatal308); A.Eq("308", l.Error, "the result's number"); A.False(l.SignInStarted);
            l.Line(LoginScreen); A.Eq(null, l.Error, "a new login screen starts clean");
            l.Line(CharSelect); A.True(l.PastLogin, "any other screen = past the login screen");
            l.Reset(); A.Eq(null, l.LastScreen); A.False(l.PastLogin);
            A.Eq("Status has no name (14400)", LoginLog.Short("Status has no name. (14400)"));
            l.Line("10/28 14:15:58.088  [N][Connection] Battle.net logon response received result=\"ERROR_OK (0)\"");
            A.Eq(null, l.Error, "ERROR_OK is not a failure");
        }

        [Test]
        public static void ClassifiesTheScreen()
        {
            A.Eq(WowScreen.None, WowFlow.Classify(false, 999, 3, 0, null, 1));
            A.Eq(WowScreen.World, WowFlow.Classify(true, 999, 0, 0, null, 5), "the add-on writes only in the world");
            A.Eq(WowScreen.World, WowFlow.Classify(true, 999, 2, 0, null, null), "two realm connections");
            A.Eq(WowScreen.CharSelect, WowFlow.Classify(true, 999, 1, 0, null, null), "one realm connection");
            A.Eq(WowScreen.CharSelect, WowFlow.Classify(true, 999, 0, 0, Log(LoginScreen, CharSelect), null));
            A.Eq(WowScreen.Login, WowFlow.Classify(true, 30, 0, 1, Log(LoginScreen, GlueStarted, Fatal308), null), "a failed sign-in is the login screen");
            A.Eq(WowScreen.SigningIn, WowFlow.Classify(true, 30, 0, 1, Log(LoginScreen), null), "Battle.net's 1119 open: signing in");
            A.Eq(WowScreen.SigningIn, WowFlow.Classify(true, 30, 0, 0, Log(LoginScreen, GlueStarted), null));
            A.Eq(WowScreen.Starting, WowFlow.Classify(true, 10, 0, 0, null, null), "young and quiet: still loading");
            A.Eq(WowScreen.Login, WowFlow.Classify(true, 3600, 0, 0, null, null), "an hour old with no connection: sitting at the login screen (the log may not have flushed)");
            A.Eq(WowScreen.Login, WowFlow.Classify(true, 3600, 0, 0, null, 500), "the add-on's last document is old: not in the world now");
        }

        [Test]
        public static void ReadsBattleNet()
        {
            A.Eq(BnetState.Unknown, WowFlow.Bnet(false, false, 0, false, false), "no Battle.net on this PC: nothing to wait for");
            A.Eq(BnetState.NotRunning, WowFlow.Bnet(true, false, 0, false, false));
            A.Eq(BnetState.SignInNeeded, WowFlow.Bnet(true, true, 3, true, true), "its \"Battle.net Login\" window");
            A.Eq(BnetState.Starting, WowFlow.Bnet(true, true, 5, false, true), "just started");
            A.Eq(BnetState.Starting, WowFlow.Bnet(true, true, 60, false, false), "no window of its own yet");
            A.Eq(BnetState.SignedIn, WowFlow.Bnet(true, true, 60, false, true));
        }

        [Test]
        public static void DecidesWhatAConnectDoes()
        {
            var ok = BnetState.SignedIn;
            A.Eq(WowAction.StartWow, WowFlow.Decide(ok, WowScreen.None, 0, false), "not running: start it");
            A.Eq(WowAction.Focus, WowFlow.Decide(ok, WowScreen.CharSelect, 0, false), "signed in: leave it, focus");
            A.Eq(WowAction.Focus, WowFlow.Decide(ok, WowScreen.World, 1, false));
            A.Eq(WowAction.Focus, WowFlow.Decide(BnetState.SignInNeeded, WowScreen.World, 0, false), "a game in the world never waits for Battle.net");
            A.Eq(WowAction.Focus, WowFlow.Decide(ok, WowScreen.Login, 1, false), "at the login screen: the player signs in, never a restart");
            A.Eq(WowAction.Focus, WowFlow.Decide(ok, WowScreen.Login, 2, false));
            A.Eq(WowAction.NeedsYou, WowFlow.Decide(ok, WowScreen.None, 2, false), "the fresh one closed itself: stop");
            A.Eq(WowAction.StartWow, WowFlow.Decide(ok, WowScreen.None, 2, false, regionRetry: true), "it closed after a refused region: the player picks again");
            A.Eq(WowAction.Wait, WowFlow.Decide(ok, WowScreen.None, 2, true, regionRetry: true), "a start under way still waits");
            A.Eq(WowAction.Wait, WowFlow.Decide(ok, WowScreen.Starting, 1, false));
            A.Eq(WowAction.Wait, WowFlow.Decide(ok, WowScreen.SigningIn, 1, false), "Blizzard's queue: never restart it");
            A.Eq(WowAction.Wait, WowFlow.Decide(ok, WowScreen.Login, 0, true), "a start is under way");
            // Battle.net first again (the user, 2026-11-17: "we had it clipping the Battlenet screen before")
            A.Eq(WowAction.StartBattleNet, WowFlow.Decide(BnetState.NotRunning, WowScreen.None, 0, false), "not running: started first");
            A.Eq(WowAction.Wait, WowFlow.Decide(BnetState.Starting, WowScreen.None, 0, false), "coming up: wait for it");
            A.Eq(WowAction.ShowBattleNetSignIn, WowFlow.Decide(BnetState.SignInNeeded, WowScreen.None, 0, false), "signed out: its sign-in, on the Thor");
            A.Eq(WowAction.ShowBattleNetSignIn, WowFlow.Decide(BnetState.SignInNeeded, WowScreen.Login, 1, false), "WoW at its login and Battle.net signed out");
            A.Eq(WowAction.Wait, WowFlow.Decide(BnetState.SignInNeeded, WowScreen.SigningIn, 1, false), "a sign-in under way is never interrupted");
            A.Eq(WowAction.Focus, WowFlow.Decide(BnetState.SignInNeeded, WowScreen.CharSelect, 1, false), "signed in: never held for Battle.net");
            A.Eq(WowAction.StartWow, WowFlow.Decide(BnetState.Unknown, WowScreen.None, 0, false), "no Battle.net on this PC: WoW's own login");
            A.Eq(WowAction.Wait, WowFlow.Decide(BnetState.NotRunning, WowScreen.None, 0, true), "busy still waits");
        }

        sealed class Pc
        {
            public WowScreen Screen = WowScreen.None;
            public BnetState Bnet = BnetState.SignedIn;
            public bool Pending;
            public readonly List<string> Did = new List<string>();
            /// <summary>where a freshly started client goes</summary>
            public WowScreen FreshGoesTo = WowScreen.Starting;
            public string Crash;
            /// <summary>LoginLog.ConnectFailures</summary>
            public int Refusals;
            /// <summary>WowSupervisor's loginAfterFailure: the client stayed at its login after the 309, signed in by login.txt</summary>
            public bool LoginAfter;

            public WowSupervisor Make(Func<DateTime> now = null) => new WowSupervisor(
                screen: () => Screen, bnet: () => Bnet,
                startBnet: () => { Did.Add("bnet"); return null; },
                showBnetSignIn: () => { Did.Add("signin"); return null; },
                startWow: () => { Did.Add("start"); Screen = FreshGoesTo; return null; },
                startPending: () => Pending,
                closeWow: () => { Did.Add("close"); Screen = WowScreen.None; return null; },
                focusWow: () => { Did.Add("focus"); return null; },
                now: now, run: a => a(), crash: () => Crash, connectFailures: () => Refusals,
                loginAfterFailure: () => LoginAfter);
        }

        /// <summary>
        /// the user's pick 2026-11-17 (region "should be changable"): WoW closes itself after every refused region (309), and every such
        /// close starts it again at its region screen without using up the one retry, so a second or third refusal never ends in
        /// "Sign-in needs you"; a client that closes with no refusal still does, and the region starts are capped
        /// </summary>
        [Test]
        public static void ARefusedRegionNeverUsesUpTheRetry()
        {
            var pc = new Pc { Screen = WowScreen.None, FreshGoesTo = WowScreen.SigningIn };    // the region screen reads as signing in
            var s = pc.Make();
            s.Connect();
            A.Eq("start", string.Join(",", pc.Did)); A.Eq(1, s.Tries);
            A.False(s.RegionRetryPending);
            pc.Refusals = 1; pc.Screen = WowScreen.None; s.Step();
            A.Eq("start,start", string.Join(",", pc.Did), "refused, closed itself: started again");
            A.Eq(1, s.Tries, "the one retry is still there"); A.Eq(1, s.RegionTries);
            pc.Refusals = 2; pc.Screen = WowScreen.None; s.Step();
            A.Eq("start,start,start", string.Join(",", pc.Did), "a second refusal: started again too");
            A.False(s.NeedsYou); A.Eq(1, s.Tries);
            // the third refusal comes after the supervisor put WoW in front and stopped (309 = the login screen): AppController
            // wants WoW again for it, and the new connect keeps the count
            pc.Screen = WowScreen.Login; s.Step(); A.False(s.Active);
            pc.Refusals = 3; pc.Screen = WowScreen.None;
            A.True(s.RegionRetryPending, "a refusal not answered yet");
            s.Connect();
            A.Eq(4, pc.Did.FindAll(d => d == "start").Count); A.Eq(3, s.RegionTries, "kept across the connect it brought");
            A.False(s.RegionRetryPending, "answered");
            // no refusal: the client closing is the old rule (a connect at no client has two starts, then the player)
            pc.Screen = WowScreen.None; s.Step();
            A.Eq(5, pc.Did.FindAll(d => d == "start").Count); A.Eq(1, s.Tries);
            pc.Screen = WowScreen.None; s.Step();
            A.Eq(6, pc.Did.FindAll(d => d == "start").Count); A.Eq(2, s.Tries);
            pc.Screen = WowScreen.None; s.Step();
            A.True(s.NeedsYou, "closed with no refusal, the retry spent: the player");
            // the cap: refusals WoW might make on its own never loop the game
            var pc2 = new Pc { Screen = WowScreen.None, FreshGoesTo = WowScreen.SigningIn };
            var s2 = pc2.Make();
            s2.Connect();
            for (int i = 1; i <= WowFlow.RegionRetryMax + 2; i++) { pc2.Refusals = i; pc2.Screen = WowScreen.None; s2.Step(); }
            A.Eq(WowFlow.RegionRetryMax, s2.RegionTries);
            A.True(s2.NeedsYou, "past the cap the old rule stands");
            A.False(s2.RegionRetryPending, "the player is asked: nothing waits");
            // a connect with no refusal waiting starts the count over
            pc2.Screen = WowScreen.Login; s2.Connect();
            A.Eq(0, s2.RegionTries);
        }

        /// <summary>
        /// reviewers 2026-11-17 (0.2.33 draft): the user's 22:58 run, a 309 with Battle.net signed in, and WoW closes itself after the
        /// supervisor had put it in front and stopped: the new connect starts it again (the draft read that 309 as "realm unreachable"
        /// and nothing started WoW: "Starting WoW on your PC" for ever)
        /// </summary>
        [Test]
        public static void A309ThatClosedWowAfterTheFocusStartsItAgain()
        {
            var pc = new Pc { Screen = WowScreen.Login };
            var s = pc.Make();
            s.Connect();
            A.Eq("focus", string.Join(",", pc.Did)); A.False(s.Active);
            pc.Refusals = 1; pc.Screen = WowScreen.None;
            A.True(s.RegionRetryPending, "every 309 counts: AppController.WowTick wants WoW again");
            s.Connect();
            A.Eq("focus,start", string.Join(",", pc.Did), "started again at its region screen");
            A.Eq(1, s.RegionTries); A.False(s.RegionRetryPending);
        }

        /// <summary>
        /// reviewers 2026-11-17: the user's 22:57 run, a 309 and WoW STAYS at its login screen, its login.txt already read and deleted:
        /// with Battle.net signed out its sign-in comes first (on the Thor), then the client is restarted so the new run asks the Thor
        /// for the login again; never while WoW sits at the region screen or for a client the player signed in by hand
        /// </summary>
        [Test]
        public static void A309AtTheLoginRestartsWowForTheThorsLogin()
        {
            A.Eq(WowAction.RestartWow, WowFlow.Decide(BnetState.SignedIn, WowScreen.Login, 1, false, regionRetry: true, loginAfterFailure: true));
            A.Eq(WowAction.Focus, WowFlow.Decide(BnetState.SignedIn, WowScreen.Login, 1, false, regionRetry: true, loginAfterFailure: false),
                "the region screen (or a hand sign-in): the player picks there");
            A.Eq(WowAction.Focus, WowFlow.Decide(BnetState.SignedIn, WowScreen.Login, 1, false, regionRetry: false, loginAfterFailure: true),
                "the region starts used up: no restart loop");
            A.Eq(WowAction.ShowBattleNetSignIn, WowFlow.Decide(BnetState.SignInNeeded, WowScreen.Login, 1, false, true, true),
                "Battle.net signed out: its sign-in first");
            var pc = new Pc { Screen = WowScreen.Login, Bnet = BnetState.SignInNeeded };
            var s = pc.Make();
            pc.Refusals = 1; pc.LoginAfter = true;
            s.Connect();
            A.Eq("signin", string.Join(",", pc.Did)); A.True(s.Active, "waits for the Battle.net sign-in");
            pc.Bnet = BnetState.SignedIn; s.Step();
            A.Eq("signin,close,start", string.Join(",", pc.Did), "signed in: the client restarted");
            A.Eq(1, s.Tries, "the one retry is not used"); A.Eq(1, s.RegionTries);
            A.False(s.RegionRetryPending, "answered");
            pc.Screen = WowScreen.Login; pc.LoginAfter = true; s.Step();
            A.Eq("signin,close,start,focus", string.Join(",", pc.Did), "no new 309: never restarted again");
        }

        [Test]
        public static void AtTheLoginScreenThePlayerSignsIn()
        {
            var pc = new Pc { Screen = WowScreen.Login };
            var s = pc.Make();
            s.Connect();
            A.Eq("focus", string.Join(",", pc.Did), "WoW in front, never closed");
            A.False(s.NeedsYou); A.False(s.Active);
        }

        [Test]
        public static void ACrashStopsTheStartsAndSaysWhy()
        {
            // the user's PC 2026-10-29: the client crashed on start (older than the game's data) - never started again in a loop
            var report = "Error: ERROR #134 (0x85100086) Fatal condition!\r\nDescription: Invalid schema WOWSTATIC_12_1_5_70077 for Table StartupFiles\r\n";
            A.Eq("update", WowFlow.CrashKind(report));
            A.Eq("crash", WowFlow.CrashKind("Error: ERROR #132 (0x85100084) Fatal exception!"));
            A.Eq("Invalid schema WOWSTATIC_12_1_5_70077 for Table StartupFiles", WowFlow.CrashLine(report));
            var pc = new Pc { Screen = WowScreen.None };
            var s = pc.Make();
            s.Connect(); A.Eq("start", string.Join(",", pc.Did));
            pc.Crash = "update"; pc.Screen = WowScreen.Login; s.Step();
            A.Eq(WowAction.Crashed, s.Last); A.Eq("update", s.Crash); A.False(s.Active);
            A.Eq("start", string.Join(",", pc.Did), "no restart after a crash");
            s.CrashOver(); A.Eq(null, s.Crash);
            pc.Crash = null; pc.Screen = WowScreen.None; s.Connect(); A.Eq(null, s.Crash, "a new connect starts clean");
        }

        // Battle.net's sign-in as measured 2026-10-30 (362 x 713): box lines, the eye at the right, the blue button
        static int[] Sheet(bool eye, params int[] boxes)
        {
            const int w = 362, h = 713;
            var px = new int[w * h];
            for (int i = 0; i < px.Length; i++) px[i] = unchecked((int)0xFF14161C);
            Action<int> line = y => { for (int x = 23; x < 337; x++) px[y * w + x] = unchecked((int)0xFF6B6C70); };
            foreach (var top in boxes)
            {
                line(top); line(top + 39);
                if (eye && top == boxes[boxes.Length - 1])
                    for (int y = top + 15; y < top + 25; y++) for (int x = 308; x < 324; x++) px[y * w + x] = unchecked((int)0xFF0074E0);
            }
            for (int y = 349; y < 389; y++) for (int x = 23; x < 337; x++) px[y * w + x] = unchecked((int)0xFF0074E0);
            return px;
        }

        [Test]
        public static void WowGoesFiveMinutesAfterTheStreamUnlessThePcIsInUse()
        {
            A.True(!WowFlow.CloseAfterStream(299, 1000), "not yet");
            A.True(!WowFlow.CloseAfterStream(1000, 10), "someone is at the PC");
            A.True(WowFlow.CloseAfterStream(300, 300), "gone");
        }

        [Test]
        public static void TheLoginFileIsShortLived()
        {
            A.True(WowFlow.LoginFileWanted(WowScreen.None)); A.True(WowFlow.LoginFileWanted(WowScreen.Starting));
            A.True(!WowFlow.LoginFileWanted(WowScreen.Login), "the client has passed its read");
            A.True(!WowFlow.LoginFileWanted(WowScreen.World));
            A.True(!WowFlow.LoginFileGoes(10, null, true), "waiting for the client");
            A.True(!WowFlow.LoginFileGoes(30, 19, true), "the client reads it twice");
            A.True(WowFlow.LoginFileGoes(30, 20, true));
            A.True(WowFlow.LoginFileGoes(300, null, true), "never read");
            A.True(WowFlow.LoginFileGoes(1, null, false), "the Thor left");
            A.Eq(null, WowFlow.LoginFileWhy(30, 19, true));
            A.Eq("sign-in seen 20 s ago", WowFlow.LoginFileWhy(30, 20, true));
            A.Eq("not taken in 300 s", WowFlow.LoginFileWhy(300, null, true));
            A.Eq("the Thor left", WowFlow.LoginFileWhy(1, null, false));
        }

        /// <summary>
        /// the user, 2026-11-17: "the auto sign in on streaming didnt work". The previous client run's log (past its login screen)
        /// made a 0.5 s old Wow.exe "CharSelect" before the tailer saw the new client's restart, so login.txt's 20 s ran from the launch.
        /// </summary>
        [Test]
        public static void AStaleLogDoesNotStartTheLoginFilesClock()
        {
            var start = new DateTime(2026, 11, 17, 14, 15, 19);
            A.True(!WowFlow.LogIsThisRun(null, start), "never read: nothing is this run's");
            A.True(!WowFlow.LogIsThisRun(start.AddMinutes(-20), start), "the restart before this Wow.exe was the previous run's");
            A.True(WowFlow.LogIsThisRun(start.AddSeconds(1.4), start), "this client emptied its log");
            var stale = Log(LoginScreen, CharSelect);
            A.Eq(WowScreen.CharSelect, WowFlow.Classify(true, 0.5, 0, 0, stale, null), "the old rule: a stale log reads as signed in");
            var s = WowFlow.Classify(true, 0.5, 0, 0, WowFlow.LogIsThisRun(null, start) ? stale : null, null);
            A.Eq(WowScreen.Starting, s, "the login file's view: still loading");
            A.True(s != WowScreen.SigningIn && s != WowScreen.CharSelect && s != WowScreen.World, "no sign-in seen: the 20 s have not begun");
            A.True(!WowFlow.LoginFileGoes(20.4, null, true), "14:15:39.5 (start + 20.4 s, the login screen up 6 s): the file stays");
            A.Eq(WowScreen.SigningIn, WowFlow.Classify(true, 16, 0, 1, null, null), "this run's Battle.net 1119 still counts without the log");
            A.Eq(WowScreen.CharSelect, WowFlow.Classify(true, 40, 1, 0, null, null), "and its realm connection");
        }

        [Test]
        public static void TheRegionPickIsStillSigningIn()
        {
            // the user's PC 2026-11-17 ("the auto sign in on streaming didnt work"): Battle.net's logon OK, then the region pick, then 309
            var l = new LoginLog();
            l.Line("11/17 14:15:33.099  [N][Lua] Switching to screen=\"AccountLogin\" (from \"none\")");
            l.Line("11/17 14:15:35.329  [N][Connection] Glue login started loginType=\"(enum)0\"");
            l.Line("11/17 14:15:48.516  [N][Lua] Switching to screen=\"AccountLogin\" (from \"login\")");
            l.Line("11/17 14:15:48.916  [N][Lua] Switching to screen=\"SuperDistrictUI\" (from \"login\")");
            A.False(l.PastLogin, "the region pick is not character select");
            A.Eq(WowScreen.Login, WowFlow.Classify(true, 30, 0, 0, l, null), "the player picks at the login: \"login\", not \"charselect\"");
            l.Line("11/17 14:16:07.625  [N][Connection] Fatal login error received result=\" (309)\"");
            A.Eq("309", l.Error);
            A.Eq(WowScreen.Login, WowFlow.Classify(true, 49, 0, 0, l, null));
            l.Line(CharSelect); A.True(l.PastLogin, "character select after it: signed in");
        }

        [Test]
        public static void AClientUpWhenTheLogWasReadOwnsIt()
        {
            var start = new DateTime(2026, 11, 17, 14, 15, 19);
            A.True(WowFlow.LogIsThisRun(start.AddMinutes(5), start), "Desktop read the file from its start while this client was up: its own log");
            A.True(!WowFlow.LogIsThisRun(start.AddSeconds(-0.5), start), "a Wow.exe started after that read, its restart not seen yet: stale");
        }

        /// <summary>the tailer wired as AppController wires it (RawLine -> LoginLog, Began -> reset + the log's "this run" time)</summary>
        sealed class Wired
        {
            public readonly LogTailer Tail;
            public readonly LoginLog Login = new LoginLog();
            public int Began;
            public DateTime? LogAt;
            public readonly List<string> Order = new List<string>();
            public Wired(string f)
            {
                Tail = new LogTailer(f);
                Tail.RawLine = l => { Login.Line(l); Order.Add("line"); };
                Tail.Began = () => { Login.Reset(); Began++; LogAt = DateTime.Now; Order.Add("began"); };
            }
        }

        /// <summary>
        /// review of the 2026-11-17 fix ("the auto sign in on streaming didnt work"): no General.log when Desktop started, then a new
        /// client makes it. No Restart is reported for that, so the log's "this run" time has to come from the read itself, or the whole
        /// run's log is dropped as stale and a 308 (Battle.net's verify-it's-you step) never reaches the Thor as login_error.
        /// </summary>
        [Test]
        public static void ALogMadeAfterDesktopStartedIsThisRuns()
        {
            using (var t = new Tmp())
            {
                var f = t.P("General.log");
                var w = new Wired(f);
                w.Tail.Poll();
                A.Eq(0, w.Began, "nothing to read yet");
                A.Eq(null, w.LogAt);
                var processStart = DateTime.Now.AddSeconds(-2);
                File.WriteAllText(f, LoginScreen + "\r\n" + GlueStarted + "\r\n" + Fatal308 + "\r\n");
                var ev = w.Tail.Poll();
                A.False(ev.Any(e => e.Kind == LogEventKind.Restart), "a file made anew after none: no restart event");
                A.Eq(1, w.Began, "but the read began");
                A.Eq("began", w.Order.First(), "before the file's lines");
                A.True(WowFlow.LogIsThisRun(w.LogAt, processStart), "the running client's log");
                A.Eq("308", w.Login.Error, "the failure is there for login_error");
                A.Eq(WowScreen.Login, WowFlow.Classify(true, 10, 0, 1, WowFlow.LogIsThisRun(w.LogAt, processStart) ? w.Login : null, null));

                // the file gone (the client closed, the folder cleaned) and made again by the next client: again this run's
                File.Delete(f);
                w.Tail.Poll();
                var nextStart = DateTime.Now;
                System.Threading.Thread.Sleep(20);
                File.WriteAllText(f, LoginScreen + "\r\n");
                w.Tail.Poll();
                A.Eq(2, w.Began);
                A.True(WowFlow.LogIsThisRun(w.LogAt, nextStart));
                A.Eq(null, w.Login.Error, "the last run's 308 is gone");
            }
        }

        [Test]
        public static void ARestartKeepsTheLinesReadWithIt()
        {
            using (var t = new Tmp())
            {
                var f = t.P("General.log");
                File.WriteAllText(f, LoginScreen + "\r\n" + GlueStarted + "\r\n" + CharSelect + "\r\n");
                var w = new Wired(f);
                w.Tail.Poll();
                A.True(w.Login.PastLogin);
                using (var fs = new FileStream(f, FileMode.Open, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete))
                {
                    fs.SetLength(0);
                    var b = System.Text.Encoding.UTF8.GetBytes(LoginScreen + "\r\n" + Fatal308 + "\r\n");
                    fs.Write(b, 0, b.Length);
                }
                var ev = w.Tail.Poll();
                A.True(ev.Any(e => e.Kind == LogEventKind.Restart));
                A.Eq(2, w.Began);
                A.False(w.Login.PastLogin, "the last run's character select is gone");
                A.Eq("308", w.Login.Error, "the new run's lines from the same poll are kept");
            }
        }

        [Test]
        public static void TheLoginFileIsWrittenAndGoes()
        {
            var dir = Path.Combine(Path.GetTempPath(), "dsf-login-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(dir);
            try
            {
                A.Eq(null, WowLoginFile.Write(dir, "a@b.c", "p w"));
                A.Eq("a@b.c\np w\n", File.ReadAllText(Path.Combine(dir, "login.txt")));
                WowLoginFile.Delete(dir);
                A.True(!File.Exists(Path.Combine(dir, "login.txt")));
            }
            finally { Directory.Delete(dir, true); }
        }

        [Test]
        public static void DesktopKnowsANewerZip()
        {
            A.Eq("0.2.11", DsfDesktop.Thor.DesktopUpdate.ZipVersion("DsForeverDesktop-0.2.11-win-x64.zip"));
            A.Eq(null, DsfDesktop.Thor.DesktopUpdate.ZipVersion("DS-Forever-1.0.13.apk"));
            var r = DsfDesktop.Thor.ReleaseFeed.ParseLatest("{\"tag_name\":\"v1.0.13\",\"assets\":[{\"name\":\"DS-Forever-1.0.13.apk\",\"browser_download_url\":\"a\",\"size\":1},"
                + "{\"name\":\"DsForeverDesktop-0.2.11-win-x64.zip\",\"browser_download_url\":\"z\",\"size\":2},{\"name\":\"SHA256SUMS\",\"browser_download_url\":\"s\",\"size\":3}]}");
            A.Eq("z", r.DesktopUrl);
            A.True(DsfDesktop.Thor.DesktopUpdate.Newer(r, "0.2.10"));
            A.True(!DsfDesktop.Thor.DesktopUpdate.Newer(r, "0.2.11"), "the same");
            A.True(!DsfDesktop.Thor.DesktopUpdate.Newer(r, "0.3.0"), "never backwards");
            r.DesktopName = null;
            A.True(!DsfDesktop.Thor.DesktopUpdate.Newer(r, "0.2.10"), "no zip in the release");
        }

        [Test]
        public static void WowIsAskedForwardForAMinuteAtMost()
        {
            A.True(WowFlow.FocusRetryWanted(WowScreen.SigningIn, false, 0, true));
            A.True(!WowFlow.FocusRetryWanted(WowScreen.Starting, false, 0, true), "no window yet");
            A.True(!WowFlow.FocusRetryWanted(WowScreen.World, true, 0, true), "in front");
            A.True(!WowFlow.FocusRetryWanted(WowScreen.World, false, 0, false), "not due");
            A.True(!WowFlow.FocusRetryWanted(WowScreen.World, false, WowFlow.FocusRetryMax, true), "the PC may be in use");
        }

        [Test]
        public static void TheScreenMatchesTheStreamWhenAsked()
        {
            var l = new DsfDesktop.Sunshine.SunshineLayout { ExeDir = "a", DataDir = "b" };
            var on = DsfDesktop.Sunshine.SunshineConfig.ConfText(l, new DsfDesktop.Sunshine.SunshineOptions { MatchStream = true });
            A.True(on.Contains("dd_configuration_option = ensure_active"));
            A.True(on.Contains("dd_resolution_option = auto"));
            A.True(on.Contains("dd_config_revert_on_disconnect = enabled"), "the screen goes back");
            var off = DsfDesktop.Sunshine.SunshineConfig.ConfText(l, new DsfDesktop.Sunshine.SunshineOptions());
            A.True(off.Contains("dd_configuration_option = disabled"));
            A.True(!off.Contains("dd_resolution_option"));
        }

        [Test]
        public static void TheControllerSettingsAreOnOnce()
        {
            var a = WowConfig.Apply("SET locale \"enUS\"\r\nSET GamePadEnable \"0\"\r\n");
            A.Eq("SET locale \"enUS\"\r\nSET GamePadEnable \"1\"\r\nSET InputDeviceInterfaceStyle \"1\"\r\n", a);
            A.Eq(a, WowConfig.Apply(a));
        }

        [Test]
        public static void TheSignInFormIsReadFromItsPicture()
        {
            var back = BnetForm.Detect(Sheet(true, 241), 362, 713);
            A.True(back != null, "Welcome back");
            A.True(back.Email == null, "the account is remembered");
            A.Eq(260, back.Password.Y);
            A.Eq(369, back.Button.Y);
            var fresh = BnetForm.Detect(Sheet(true, 180, 241), 362, 713);
            A.True(fresh != null && fresh.Email.HasValue, "email and password");
            A.Eq(199, fresh.Email.Value.Y);
            A.True(BnetForm.Detect(Sheet(false, 241), 362, 713) == null, "a box with no eye is never a password box");
            A.True(BnetForm.Detect(Sheet(true, 120, 180, 241), 362, 713) == null, "three boxes: not a form we know");
            A.True(BnetForm.Detect(new int[362 * 713], 362, 713) == null, "nothing there");
        }

        [Test]
        public static void ShowRectIsTheWindowOnTheCapturedMonitor()
        {
            // the captured monitor at 1920,0 (2560 x 1440); Battle.net's sign-in 400 x 600 at its middle
            var mon = new Displays.RECT { Left = 1920, Top = 0, Right = 4480, Bottom = 1440 };
            var win = new Displays.RECT { Left = 1920 + 1080, Top = 420, Right = 1920 + 1480, Bottom = 1020 };
            A.Eq("0.4195,0.2875,0.1609,0.4250", Displays.ShowRect(win, mon));
            A.Eq(null, Displays.ShowRect(new Displays.RECT { Left = 0, Top = 0, Right = 400, Bottom = 600 }, mon), "on another monitor");
        }

        [Test]
        public static void NotRunningStartsThenWaitsForThePlayer()
        {
            var pc = new Pc { Screen = WowScreen.None };
            var s = pc.Make();
            s.Connect(); A.Eq("start", string.Join(",", pc.Did));
            pc.Screen = WowScreen.Login; s.Step();
            A.Eq("start,focus", string.Join(",", pc.Did), "no restart"); A.False(s.NeedsYou); A.False(s.Active);
        }

        [Test]
        public static void SignedInIsOnlyFocused()
        {
            var pc = new Pc { Screen = WowScreen.World };
            var s = pc.Make();
            s.Connect();
            A.Eq("focus", string.Join(",", pc.Did)); A.Eq(0, s.Tries);
        }

        /// <summary>
        /// the Battle.net sign-in on the Thor, as up to 0.2.7 (taken out by 0.2.8, 8e10f536): not running = started; signed out = its
        /// sign-in in front (the Thor shows it alone and types the saved login); signed in = WoW starts (plainly); a Battle.net that never
        /// comes up holds WoW for BnetGiveUpS at the most
        /// </summary>
        [Test]
        public static void BattleNetSignsInFirst()
        {
            var t = new DateTime(2026, 11, 17, 23, 0, 0, DateTimeKind.Utc);
            var pc = new Pc { Screen = WowScreen.None, Bnet = BnetState.NotRunning };
            var s = pc.Make(() => t);
            s.Connect();
            A.Eq("bnet", string.Join(",", pc.Did), "not running: Battle.net starts, WoW waits");
            pc.Bnet = BnetState.Starting; t = t.AddSeconds(10); s.Step();
            A.Eq("bnet", string.Join(",", pc.Did), "coming up: nothing more");
            pc.Bnet = BnetState.SignInNeeded; t = t.AddSeconds(10); s.Step(); s.Step();
            A.Eq("bnet,signin", string.Join(",", pc.Did), "signed out: its sign-in in front, once");
            t = t.AddMinutes(10); s.Step();
            A.Eq("bnet,signin", string.Join(",", pc.Did), "the sign-in never times out: the player is on it");
            A.True(s.Active);
            pc.Bnet = BnetState.SignedIn; s.Step();
            A.Eq("bnet,signin,start", string.Join(",", pc.Did), "signed in: WoW starts");
            A.Eq(1, s.Tries);

            // a Battle.net that never comes up: WoW starts without it after BnetGiveUpS
            var t2 = t;
            var pc2 = new Pc { Screen = WowScreen.None, Bnet = BnetState.NotRunning };
            var s2 = pc2.Make(() => t2);
            s2.Connect();
            t2 = t2.AddSeconds(WowSupervisor.BnetGiveUpS - 1); s2.Step();
            A.False(pc2.Did.Contains("start"), "still waiting (Battle.net asked again each half minute)");
            A.True(pc2.Did.Count <= 4, "never a tight loop");
            t2 = t2.AddSeconds(2); s2.Step();
            A.Eq("start", pc2.Did[pc2.Did.Count - 1], "gave up on Battle.net: WoW's own login");

            // no Battle.net on the PC at all: as 0.2.8
            var pc3 = new Pc { Screen = WowScreen.None, Bnet = BnetState.Unknown };
            pc3.Make().Connect();
            A.Eq("start", string.Join(",", pc3.Did));
        }

        /// <summary>the Thor's "bnet": signin_needed only while WoW is not signed in (StreamCurtain shows Battle.net's window alone for it)</summary>
        [Test]
        public static void TheThorHearsBattleNetAgain()
        {
            A.Eq("signin_needed", WowFlow.ThorBnet(BnetState.SignInNeeded, WowScreen.None));
            A.Eq("signin_needed", WowFlow.ThorBnet(BnetState.SignInNeeded, WowScreen.Login));
            A.Eq("unknown", WowFlow.ThorBnet(BnetState.SignInNeeded, WowScreen.World), "a game in the world is never hidden behind Battle.net");
            A.Eq("unknown", WowFlow.ThorBnet(BnetState.SignInNeeded, WowScreen.CharSelect));
            A.Eq("not_running", WowFlow.ThorBnet(BnetState.NotRunning, WowScreen.None));
            A.Eq("signed_in", WowFlow.ThorBnet(BnetState.SignedIn, WowScreen.Login));
            A.True(WowFlow.BnetSignInPending(BnetState.SignInNeeded, WowScreen.Login));
            A.False(WowFlow.BnetSignInPending(BnetState.SignInNeeded, WowScreen.World));
            A.False(WowFlow.BnetSignInPending(BnetState.SignedIn, WowScreen.Login));
            A.False(WowFlow.NeedsYouText.Contains("PC"), "never 'sign in on the PC' (the user, 2026-11-17)");
        }

        /// <summary>the welcome's "lan": home networks only, never Tailscale (the user, 2026-10-28: "Yes, Wi-Fi first")</summary>
        [Test]
        public static void LanAddressesAreHomeNetworksOnly()
        {
            A.True(DsfDesktop.App.AppController.IsLan(new byte[] { 192, 168, 1, 11 }));
            A.True(DsfDesktop.App.AppController.IsLan(new byte[] { 10, 0, 0, 5 }));
            A.True(DsfDesktop.App.AppController.IsLan(new byte[] { 172, 20, 1, 1 }));
            A.False(DsfDesktop.App.AppController.IsLan(new byte[] { 100, 69, 36, 86 }), "Tailscale");
            A.False(DsfDesktop.App.AppController.IsLan(new byte[] { 169, 254, 3, 4 }), "link-local");
            A.False(DsfDesktop.App.AppController.IsLan(new byte[] { 8, 8, 8, 8 }));
            foreach (var a in DsfDesktop.App.AppController.LanAddresses()) A.True(a is string, "plain strings in the welcome");
        }

        /// <summary>the welcome's "tailscale": Tailscale's two ranges only (the user, 2026-11-11: streaming away fixes)</summary>
        [Test]
        public static void TailscaleAddressesAreTailscaleOnly()
        {
            A.True(DsfDesktop.App.AppController.IsTailscale(new byte[] { 100, 69, 36, 86 }));
            A.True(DsfDesktop.App.AppController.IsTailscale(new byte[] { 100, 64, 0, 1 }));
            A.True(DsfDesktop.App.AppController.IsTailscale(new byte[] { 100, 127, 255, 254 }));
            A.False(DsfDesktop.App.AppController.IsTailscale(new byte[] { 100, 128, 0, 1 }), "past 100.64/10");
            A.False(DsfDesktop.App.AppController.IsTailscale(new byte[] { 100, 63, 0, 1 }));
            A.False(DsfDesktop.App.AppController.IsTailscale(new byte[] { 192, 168, 1, 11 }), "home network");
            A.True(DsfDesktop.App.AppController.IsTailscale(System.Net.IPAddress.Parse("fd7a:115c:a1e0::1234").GetAddressBytes()));
            A.False(DsfDesktop.App.AppController.IsTailscale(System.Net.IPAddress.Parse("fd7a:115c:a1e1::1").GetAddressBytes()), "past the /48");
            A.False(DsfDesktop.App.AppController.IsTailscale(System.Net.IPAddress.Parse("fe80::1").GetAddressBytes()));
            var all = DsfDesktop.App.AppController.TailscaleAddresses();
            foreach (var a in all) A.True(a is string s && !s.Contains("%"), "plain strings, no scope");
            var firstV6 = all.FindIndex(a => ((string)a).Contains(":"));
            if (firstV6 >= 0) A.True(System.Linq.Enumerable.All(System.Linq.Enumerable.Skip(all, firstV6), a => ((string)a).Contains(":")), "IPv4 first");
        }

        const string Glue0 = "11/17 22:53:45.161  [N][Connection] Glue login started loginType=\"(enum)0\" launcherPortal=\"nullopt\"";
        const string OneRegion = "11/17 22:57:09.652  [N][Connection] Battle.net subregion list received code=\"ERROR_OK (0)\" realmListRequestToken=\"0\" isCurrentToken=\"true\" subRegionCount=\"1\"";
        const string TwoRegions = "11/17 22:57:09.652  [N][Connection] Battle.net subregion list received code=\"ERROR_OK (0)\" realmListRequestToken=\"0\" isCurrentToken=\"true\" subRegionCount=\"2\"";
        const string PickScreen = "11/17 22:57:10.471  [N][Lua] Switching to screen=\"SuperDistrictUI\" (from \"login\")";
        const string District = "11/17 22:57:11.436  [N][Connection] Battle.net super district realm connection requested superDistrictID=\"2\"";
        const string BackToLogin = "11/17 22:57:11.436  [N][Lua] Switching to screen=\"AccountLogin\" (from \"superdistrict\")";
        const string Fatal309 = "11/17 22:57:11.785  [N][Connection] Fatal login error received result=\" (309)\"";

        /// <summary>
        /// the user's PC 2026-11-17 22:57 and 22:58 ("saying that its a region error when its not"): the region screen, district 2
        /// asked, back to the login, 309. Its "subRegionCount" (1) is Battle.net's sub-region count, not the region screen's list, and
        /// nothing else in the log tells a refused district from an unreachable realm (reviewers 2026-11-17): every 309 is one neutral
        /// ConnectFailed, counted for the region start, whatever the count says
        /// </summary>
        [Test]
        public static void Every309IsOneNeutralConnectFailure()
        {
            var l = Log(LoginScreen, Glue0, OneRegion, LoginScreen, PickScreen, District, BackToLogin, Fatal309);
            A.Eq("309", l.Error);
            A.True(l.ConnectFailed);
            A.Eq(1, l.ConnectFailures, "counted: the client starts again at its region screen");
            A.True(l.BackAtLoginAfterFailure, "it stayed at its login (22:57)");
            A.Eq(WowScreen.Login, WowFlow.Classify(true, 200, 0, 0, l, null));
            var two = Log(LoginScreen, Glue0, TwoRegions, PickScreen, District, Fatal309);
            A.True(two.ConnectFailed, "a count of 2 reads the same: the count is no region list");
            A.False(two.BackAtLoginAfterFailure, "still at the region screen: the player picks there");
            A.True(Log(LoginScreen, Fatal309).ConnectFailed, "a 309 with no region pick in the log (block-buffered) counts too");
            A.Eq("WoW can't reach its servers", WowFlow.ConnectFailedText);
            A.False(WowFlow.ConnectFailedText.Contains("egion"), "never 'Region refused' (the user 2026-11-17)");
        }

        /// <summary>
        /// the user's pick 2026-11-17 (the region "should be changable"): a 309 is said to the Thor while WoW's own screens wait; it
        /// stays through the screen switching back to the login or the region pick, and goes once a character select shows or a new
        /// client runs much later
        /// </summary>
        [Test]
        public static void A309IsSaidUntilTheSignInGoesOn()
        {
            var l = Log(LoginScreen, Glue0, OneRegion, PickScreen);
            A.False(l.ConnectFailed, "the pick itself is no failure");
            l.Line(District); l.Line(BackToLogin); l.Line(Fatal309);
            A.True(l.ConnectFailed);
            A.Eq(1, l.ConnectFailures, "counted, though the screen was already back at the login when the 309 came");
            l.Line(PickScreen);
            A.True(l.ConnectFailed, "back at the region screen: still said");
            A.False(l.BackAtLoginAfterFailure);
            A.Eq(null, l.Error, "the error itself is cleared by the screen switch as before");
            l.Line(LoginScreen);
            A.True(l.ConnectFailed, "the login screen: still said");
            A.True(l.BackAtLoginAfterFailure);
            l.Line(Fatal308);
            A.False(l.ConnectFailed, "another error is no 309");
            l.Line(Fatal309);
            l.Line(CharSelect);
            A.False(l.ConnectFailed, "character select: a region was taken");
            // WoW closes itself after a 309 and Desktop starts it once more: the restarted client's screens still say it
            var now = new DateTime(2026, 11, 17, 14, 16, 8, DateTimeKind.Utc);
            l.Now = () => now;
            l.Line(Fatal309);
            A.True(l.ConnectFailed);
            now = now.AddSeconds(20);
            l.Reset();
            A.True(l.ConnectFailed, "the restarted client (its log emptied) still shows it");
            A.Eq(null, l.Error, "the rest of the run is forgotten");
            l.Line(LoginScreen);
            l.Line(PickScreen);
            A.True(l.ConnectFailed, "the restarted client's region screen: said");
            l.Line(CharSelect);
            A.False(l.ConnectFailed, "a region taken: gone");
            l.Line(Glue0); l.Line(District); l.Line(Fatal309);
            A.True(l.ConnectFailed);
            now = now.AddSeconds(LoginLog.RegionCarryS);
            l.Reset();
            A.False(l.ConnectFailed, "a client started much later is a new try");
            var c = new LoginLog();
            c.Line(LoginScreen); A.True(c.AtSignInScreens);
            c.Line(Glue0); c.Line(PickScreen); A.True(c.AtSignInScreens, "the region screen is still the sign-in");
            c.Line(District); c.Line(Fatal309); A.Eq(1, c.ConnectFailures);
            c.Line(Fatal308); A.Eq(1, c.ConnectFailures, "another error is no 309");
            c.Reset(); A.Eq(1, c.ConnectFailures, "a new client run keeps the count");
            c.Line(CharSelect); A.False(c.AtSignInScreens);
        }

        /// <summary>
        /// the user's pick 2026-11-17 ("Ask the Thor again"): login.txt is for one client run. Its client closing ends it (the file goes),
        /// and the next start asks the Thor for the login again, only while a Thor is connected and no file waits
        /// </summary>
        [Test]
        public static void AClosedClientsLoginIsAskedForAgain()
        {
            A.False(WowFlow.LoginRunOver(null, null), "no client seen for the file yet");
            A.False(WowFlow.LoginRunOver(null, 4242), "a client, not tied yet");
            A.False(WowFlow.LoginRunOver(4242, 4242), "its client still runs");
            A.True(WowFlow.LoginRunOver(4242, null), "its client closed itself (the refused region)");
            A.True(WowFlow.LoginRunOver(4242, 5151), "another client now");
            A.Eq("its client closed", WowFlow.LoginFileWhy(5, null, true, clientGone: true), "the file was for that run only");
            A.Eq("the Thor left", WowFlow.LoginFileWhy(5, null, false, clientGone: true));
            A.Eq(null, WowFlow.LoginFileWhy(5, null, true, clientGone: false), "the old rules stand");
            A.True(WowFlow.LoginAgainWanted(fileOnDisk: false, runOver: true, thorHere: true), "ask the Thor again");
            A.False(WowFlow.LoginAgainWanted(fileOnDisk: true, runOver: true, thorHere: true), "a file still waits: this start reads it");
            A.False(WowFlow.LoginAgainWanted(false, true, false), "no Thor: nothing to ask");
            A.False(WowFlow.LoginAgainWanted(false, false, true), "the first start of a connect: the Thor sent it with its welcome");
            // a run that signed in is over with the player's quit or a crash: nothing is asked again then
            A.True(WowFlow.LoginRunSettled(WowScreen.CharSelect));
            A.True(WowFlow.LoginRunSettled(WowScreen.World));
            A.False(WowFlow.LoginRunSettled(WowScreen.Login), "the region screen reads as the login: not settled");
            A.False(WowFlow.LoginRunSettled(WowScreen.SigningIn));
            A.False(WowFlow.LoginRunSettled(WowScreen.None));
            A.False(WowFlow.LoginRunSettled(WowScreen.CharSelect, logAtSignIn: true), "a connection held at the region screen is no character select");
            A.False(WowFlow.LoginRunSettled(WowScreen.World, logAtSignIn: true));
            A.True(WowFlow.LoginAgainWaitS > 0 && WowFlow.LoginAgainWaitS <= 5, "a start waits a moment, never long");
        }

        /// <summary>the user's pick 2026-11-17 ("Yes, remember it"): the PC's Config.wtf remembers the Thor's game account, only that key,
        /// and the PC's own remembered pick wins (fake names only)</summary>
        [Test]
        public static void ThePcRemembersTheGameAccount()
        {
            const string list = "Fake1|!Fake2|";
            var cfg = "SET portal \"eu\"\r\nSET accountName \"fake@example.invalid\"\r\nSET GamePadEnable \"1\"\r\n";
            var added = WowAccount.Apply(cfg, list);
            A.Eq(cfg + "SET accountList \"Fake1|!Fake2|\"\r\n", added, "added; every other line as it was (portal, accountName)");
            var unmarked = "SET accountList \"Fake1|Fake2|\"\nSET portal \"us\"\n";
            A.Eq("SET accountList \"Fake1|!Fake2|\"\nSET portal \"us\"\n", WowAccount.Apply(unmarked, list), "an unmarked list remembers nothing: replaced");
            var own = "SET accountList \"!Fake1|Fake2|\"\nSET portal \"us\"\n";
            A.Eq(own, WowAccount.Apply(own, list), "WoW's own remembered pick on the PC wins");
            A.Eq(cfg, WowAccount.Apply(cfg, "Fake1|Fake2|"), "a list with no pick is no choice");
            A.Eq(cfg, WowAccount.Apply(cfg, null));
            A.Eq(cfg, WowAccount.Apply(cfg, "Fake1|!Fake2\"\r\nSET portal \"kr"), "nothing but one plain value");
            A.Eq(cfg, WowAccount.Apply(cfg, new string('!', 600)));
            using (var t = new Tmp())
            {
                var f = t.P("Config.wtf");
                A.Eq("no Config.wtf yet", WowAccount.Ensure(f, list), "a game never started: the client writes its own");
                A.False(File.Exists(f));
                File.WriteAllText(f, cfg);
                A.True(WowAccount.Ensure(f, list).StartsWith("accountList written"), "written");
                A.Eq(added, File.ReadAllText(f));
                A.Eq(cfg, File.ReadAllText(f + WowAccount.Backup), "backed up first");
                A.False(File.Exists(f + ".dsf-tmp"));
                A.Eq("kept (the PC's own)", WowAccount.Ensure(f, list), "the second start changes nothing");
                // WoW later saved a list without a pick: written again, and the backup stays the file from before Desktop's first write
                File.WriteAllText(f, unmarked);
                A.Eq("accountList written (backup kept)", WowAccount.Ensure(f, list));
                A.Eq(cfg, File.ReadAllText(f + WowAccount.Backup), "the first backup is kept");
                // a read-only Config.wtf: not written, and no temp copy (it holds the whole file) stays behind
                File.WriteAllText(f, unmarked);
                File.SetAttributes(f, FileAttributes.ReadOnly);
                try
                {
                    A.True(WowAccount.Ensure(f, list).StartsWith("not written"), "read-only");
                    A.False(File.Exists(f + ".dsf-tmp"), "the temp copy goes");
                    A.Eq(unmarked, File.ReadAllText(f));
                }
                finally { File.SetAttributes(f, FileAttributes.Normal); }
                foreach (var said in new[] { WowAccount.Ensure(f, list), WowAccount.Ensure(f, "x") })
                    A.False(said.Contains("Fake"), "the log never names an account");
            }
        }
    }
}
