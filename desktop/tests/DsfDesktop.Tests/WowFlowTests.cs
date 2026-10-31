using System;
using System.Collections.Generic;
using DsfDesktop.Wow;

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
            A.Eq(WowAction.RestartWow, WowFlow.Decide(ok, WowScreen.Login, 1, false), "at the login screen: close it, start a fresh one");
            A.Eq(WowAction.NeedsYou, WowFlow.Decide(ok, WowScreen.Login, 2, false), "the fresh one at the login screen too: stop");
            A.Eq(WowAction.NeedsYou, WowFlow.Decide(ok, WowScreen.None, 2, false), "the fresh one closed itself: stop");
            A.Eq(WowAction.Wait, WowFlow.Decide(ok, WowScreen.Starting, 1, false));
            A.Eq(WowAction.Wait, WowFlow.Decide(ok, WowScreen.SigningIn, 1, false), "Blizzard's queue: never restart it");
            A.Eq(WowAction.Wait, WowFlow.Decide(ok, WowScreen.Login, 0, true), "a restart is under way");
            A.Eq(WowAction.StartBattleNet, WowFlow.Decide(BnetState.NotRunning, WowScreen.None, 0, false));
            A.Eq(WowAction.Wait, WowFlow.Decide(BnetState.Starting, WowScreen.None, 0, false));
            A.Eq(WowAction.ShowBattleNetSignIn, WowFlow.Decide(BnetState.SignInNeeded, WowScreen.Login, 1, false), "signed out: its sign-in first");
            A.Eq(WowAction.StartWow, WowFlow.Decide(BnetState.Unknown, WowScreen.None, 0, false), "no Battle.net: the plain start");
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

            public WowSupervisor Make() => new WowSupervisor(
                screen: () => Screen, bnet: () => Bnet,
                startBnet: () => { Did.Add("bnet"); return null; },
                showBnetSignIn: () => { Did.Add("signin"); return null; },
                startWow: () => { Did.Add("start"); Screen = FreshGoesTo; return null; },
                startPending: () => Pending,
                closeWow: () => { Did.Add("close"); Screen = WowScreen.None; return null; },
                focusWow: () => { Did.Add("focus"); return null; },
                run: a => a(), crash: () => Crash);
        }

        [Test]
        public static void AtTheLoginScreenRestartsOnceThenGivesUp()
        {
            var pc = new Pc { Screen = WowScreen.Login, FreshGoesTo = WowScreen.Starting };
            var s = pc.Make();
            s.Connect();
            A.Eq("close,start", string.Join(",", pc.Did), "the old client closed, a fresh one started");
            A.Eq(2, s.Tries);
            pc.Screen = WowScreen.SigningIn; s.Step(); A.Eq(WowAction.Wait, s.Last);
            pc.Screen = WowScreen.Login; s.Step();                      // the fresh one lands at the login screen too
            A.True(s.NeedsYou, "never a loop"); A.False(s.Active);
            A.Eq("close,start", string.Join(",", pc.Did), "no second restart");
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
        public static void NotRunningStartsAndRetriesOnce()
        {
            var pc = new Pc { Screen = WowScreen.None };
            var s = pc.Make();
            s.Connect(); A.Eq("start", string.Join(",", pc.Did));
            pc.Screen = WowScreen.Login; s.Step();
            A.Eq("start,close,start", string.Join(",", pc.Did), "the one retry");
            pc.Screen = WowScreen.CharSelect; s.Step();
            A.Eq("start,close,start,focus", string.Join(",", pc.Did)); A.False(s.NeedsYou); A.False(s.Active, "done: signed in and in front");
        }

        [Test]
        public static void SignedInIsOnlyFocused()
        {
            var pc = new Pc { Screen = WowScreen.World };
            var s = pc.Make();
            s.Connect();
            A.Eq("focus", string.Join(",", pc.Did)); A.Eq(0, s.Tries);
        }

        [Test]
        public static void BattleNetSignInComesFirst()
        {
            var pc = new Pc { Screen = WowScreen.None, Bnet = BnetState.NotRunning };
            var s = pc.Make();
            s.Connect(); A.Eq("bnet", string.Join(",", pc.Did), "Battle.net started plainly");
            s.Step(); A.Eq("bnet", string.Join(",", pc.Did), "not again at once");
            pc.Bnet = BnetState.SignInNeeded; s.Step(); s.Step();
            A.Eq("bnet,signin", string.Join(",", pc.Did), "its sign-in window in front, once");
            A.Eq(WowAction.ShowBattleNetSignIn, s.Last);
            pc.Bnet = BnetState.SignedIn; s.Step();
            A.Eq("bnet,signin,start", string.Join(",", pc.Did), "signed in: WoW starts the normal way");
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
    }
}
