using System;
using System.Text.RegularExpressions;

namespace DsfDesktop.Wow
{
    /// <summary>where the game is (the "wow" message's screen)</summary>
    public enum WowScreen { None, Starting, Login, SigningIn, CharSelect, World }

    /// <summary>the PC's Battle.net app: what it needs before WoW can sign in through it</summary>
    public enum BnetState { Unknown, NotRunning, Starting, SignInNeeded, SignedIn }

    /// <summary>what to do now, for a stream that wants WoW (the user, 2026-10-28)</summary>
    /// <remarks>[Stopped] = a server error (a 309) during the sign-in: nothing starts again by itself, the Thor shows it with a Retry
    /// (the user's ruling 2026-11-18, "Stop and show it"). [Outdated] = the PC's game is older than Blizzard's current version: WoW is
    /// not started (the user's ruling 2026-11-18: Desktop patches it itself; until that lands, the Thor's Retry checks again).
    /// [Repair] (0.2.35) = Wow.exe is there but the game's install record (.build.info's wowf row) is missing or of another build:
    /// WoW is not started (a client without its row can't find its data)</remarks>
    public enum WowAction { Wait, StartBattleNet, ShowBattleNetSignIn, StartWow, RestartWow, Focus, NeedsYou, Crashed, Stopped, Outdated, Repair }

    /// <summary>
    /// What the game wrote, as far as signing in goes. WoW's General.log is block-buffered (~48 KiB): on the PC, a client at the
    /// login screen may have flushed nothing yet (2026-10-28: the login lines of a failed sign-in were written only when the client
    /// closed). So the log is ONE signal among several (<see cref="WowFlow.Classify"/>): the game's connections and the add-on's
    /// documents carry the rest. The lines (measured on WoW Forever, the APK's HomeLogin):
    ///   [N][Lua] Switching to screen="AccountLogin" (from "none")      the login screen
    ///   [N][Lua] Switching to screen="SuperDistrictUI"                 the region pick after Battle.net's logon: still signing in
    ///   [N][Lua] Switching to screen="..."                             any other screen: past the login screen
    ///   [N][Connection] Glue login started ...                         a sign-in began
    ///   [N][Connection] Fatal login error received result=" (308)"     it failed (308 = Battle.net wanted a verify-it's-you step)
    /// </summary>
    public sealed class LoginLog
    {
        static readonly Regex Screen = new Regex("\\[Lua\\] Switching to screen=\"([^\"]+)\"", RegexOptions.Compiled);
        static readonly Regex Fatal = new Regex("Fatal login error received result=\"([^\"]*)\"", RegexOptions.Compiled);

        /// <summary>the last screen the log named, null = none yet in this client run</summary>
        public string LastScreen { get; private set; }
        /// <summary>the reason of a failed sign-in at the current login screen, null = none</summary>
        public string Error { get; private set; }
        public bool SignInStarted { get; private set; }

        public const string LoginScreen = "AccountLogin";
        /// <summary>
        /// The client's region pick (the user's PC 2026-11-17, "the auto sign in on streaming didnt work": Battle.net's logon OK, then
        /// screen="SuperDistrictUI" for 18 s until the player picked, then "Fatal login error (309)"). Not character select: the sign-in
        /// is not over, so the screen reads "login", not "charselect".
        /// </summary>
        public const string RegionScreen = "SuperDistrictUI";

        /// <summary>
        /// The game's "Fatal login error (309)" (the user's PC 2026-11-17 22:57 and 22:58: Battle.net's logon OK, the region screen,
        /// superDistrictID="2" asked, then 309). WHAT THE LOG DOES NOT SAY: whether the district was refused or its realm was out of
        /// reach. Its "subRegionCount" is Battle.net's sub-region count (1 for almost every account), not the region screen's list
        /// (the super districts, which log no count), so nothing in the log tells the two apart. Desktop therefore says ONE neutral
        /// thing to the Thor (login_error "309", "connect" "failed": "WoW can't reach its servers") and never "Region refused".
        /// </summary>
        public const string ConnectFailedCode = "309";

        /// <summary>
        /// A 309 (<see cref="ConnectFailedCode"/>) and no character select since. WoW may close itself after it (22:58) or stay at its
        /// login screen (22:57). From 0.2.34 nothing starts it again by itself (the user's ruling 2026-11-18, "Stop and show it": 0.2.33
        /// restarted WoW every ~20 s while Blizzard said "no servers available"): the supervisor stops (WowAction.Stopped) and the Thor
        /// shows the error with a Retry, which starts WoW once per press (<see cref="ClearFailure"/>). Desktop never picks or saves a
        /// region. Unlike <see cref="Error"/>, a screen switch back to the login or the region screen keeps it.
        /// </summary>
        public bool ConnectFailed { get; private set; }

        /// <summary>when <see cref="ConnectFailed"/> last turned on (<see cref="Now"/>'s clock), null = never in this Desktop run</summary>
        public DateTime? ConnectFailedAt { get; private set; }

        /// <summary>
        /// The 309s seen in this Desktop run, never reset (for the log and the tests; nothing starts WoW for one)
        /// </summary>
        public int ConnectFailures { get; private set; }

        /// <summary>the client's log is at the login or region screen (its sign-in is not over, whatever its connections say)</summary>
        public bool AtSignInScreens => LastScreen == LoginScreen || LastScreen == RegionScreen;

        /// <summary>the Thor's Retry (the user's ruling 2026-11-18, "Stop and show it"): the 309 is answered; WoW is started again
        /// once for it (WowSupervisor.Retry), so what is known of the failed sign-in goes</summary>
        public void ClearFailure() { ConnectFailed = false; ConnectFailedAt = null; Error = null; }

        /// <summary>the clock for <see cref="ConnectFailedAt"/> (UTC; a test sets its own)</summary>
        public Func<DateTime> Now = () => DateTime.UtcNow;

        /// <summary>
        /// How long a 309 outlasts its client: WoW may close itself after it (the user's PC 2026-11-17); a client started soon after
        /// by anything but the Thor's Retry (which clears it: <see cref="ClearFailure"/>) is the same failed try. Never longer than
        /// this: a client started much later is a new try.
        /// </summary>
        public const double RegionCarryS = 300;

        /// <summary>a new client run (the log was emptied): nothing is known any more, except a 309 within <see cref="RegionCarryS"/>
        /// (its client closed itself; the restarted one shows the same region screen)</summary>
        public void Reset()
        {
            LastScreen = null; Error = null; SignInStarted = false;
            ConnectFailed = ConnectFailed && ConnectFailedAt.HasValue && (Now() - ConnectFailedAt.Value).TotalSeconds < RegionCarryS;
        }

        public void Line(string line)
        {
            if (string.IsNullOrEmpty(line)) return;
            if (line.IndexOf("[Lua] Switching to screen=", StringComparison.Ordinal) >= 0)
            {
                var m = Screen.Match(line);
                if (m.Success)
                {
                    LastScreen = m.Groups[1].Value;
                    // a new login screen starts clean; any other screen means the sign-in is behind us
                    Error = null;
                    if (LastScreen == LoginScreen) SignInStarted = false;
                    else if (LastScreen != RegionScreen) ConnectFailed = false;     // past the login: a region was taken
                }
                return;
            }
            if (line.IndexOf("[Connection]", StringComparison.Ordinal) < 0) return;
            if (line.IndexOf("Glue login started", StringComparison.Ordinal) >= 0) { SignInStarted = true; Error = null; return; }
            var f = Fatal.Match(line);
            if (f.Success)
            {
                Error = Short(f.Groups[1].Value); SignInStarted = false;
                // every 309 alike: the log has no word that tells a refused district from an unreachable realm (see ConnectFailedCode)
                ConnectFailed = Error == ConnectFailedCode;
                if (ConnectFailed) { ConnectFailedAt = Now(); ConnectFailures++; }
            }
        }

        /// <summary>" (308)" -> "308"; "Status has no name. (14400)" -> "Status has no name (14400)"</summary>
        public static string Short(string raw)
        {
            var r = Regex.Replace((raw ?? "").Trim(), "\\.\\s*\\(", " (");
            if (r.StartsWith("(") && r.EndsWith(")")) r = r.Substring(1, r.Length - 2);
            if (r.Length == 0) r = "unknown";
            return r.Length <= 60 ? r : r.Substring(0, 57) + "...";
        }

        /// <summary>the log says the client is past the login screen</summary>
        public bool PastLogin => LastScreen != null && LastScreen != LoginScreen && LastScreen != RegionScreen && Error == null;
    }

    /// <summary>the pure rules (tested): where the game is, and what a stream that wants it should do</summary>
    public static class WowFlow
    {
        /// <summary>a client this young with no connection is still loading, not "sitting at the login screen"</summary>
        public const double LoadGraceS = 25;
        /// <summary>the add-on writes a document every few seconds in the world (addons do not run before it)</summary>
        public const double WorldDocS = 90;
        public const double FocusRetryEveryS = 5;
        public const int FocusRetryMax = 12;

        /// <summary>WoW has a window, is behind something, and the tries are not used up</summary>
        public static bool FocusRetryWanted(WowScreen s, bool inFront, int tries, bool due) =>
            s != WowScreen.None && s != WowScreen.Starting && !inFront && tries < FocusRetryMax && due;

        /// <summary>login.txt: 20 s after the sign-in began (the client reads it twice, as measured on the Thor), 5 minutes at the most</summary>
        public const double LoginFileAfterS = 20, LoginFileMaxS = 300;

        /// <summary>the client only reads it as its login screen appears, so only a client not there yet gets one</summary>
        public static bool LoginFileWanted(WowScreen s) => s == WowScreen.None || s == WowScreen.Starting;

        public static bool LoginFileGoes(double ageS, double? sinceSignInS, bool thorHere, bool clientGone = false) =>
            LoginFileWhy(ageS, sinceSignInS, thorHere, clientGone) != null;

        /// <summary>why login.txt goes now (for the log: a reason, never its contents), null = it stays. [clientGone] = the client run
        /// the file was written for has closed (<see cref="LoginRunOver"/>): the file was for that run only</summary>
        public static string LoginFileWhy(double ageS, double? sinceSignInS, bool thorHere, bool clientGone = false) =>
            !thorHere ? "the Thor left"
            : clientGone ? "its client closed"
            : sinceSignInS.HasValue && sinceSignInS.Value >= LoginFileAfterS ? "sign-in seen " + (int)LoginFileAfterS + " s ago"
            : ageS >= LoginFileMaxS ? "not taken in " + (int)LoginFileMaxS + " s"
            : null;

        /// <summary>
        /// login.txt is for ONE client run (the user's pick 2026-11-17, "Ask the Thor again"): [runPid] = the Wow.exe first seen after
        /// the file was written (null = none seen yet, or that run is settled); [pidNow] = the Wow.exe up now (null = none). A different
        /// one, or none, = that run is over (WoW closed itself, e.g. after a refused region).
        /// </summary>
        public static bool LoginRunOver(int? runPid, int? pidNow) => runPid.HasValue && pidNow != runPid;

        /// <summary>
        /// Desktop starts WoW again after a client of a login.txt run closed itself: it asks the Thor (the "login_again" message) for the
        /// sealed login once more, so this run signs itself in too. The Thor answers only while it is connected and its auto sign-in is
        /// on; Desktop never keeps the login. [fileOnDisk] = a login.txt still waits (this start reads that one).
        /// </summary>
        public static bool LoginAgainWanted(bool fileOnDisk, bool runOver, bool thorHere) => !fileOnDisk && runOver && thorHere;

        /// <summary>a login.txt run that reached character select or the world signed in: its client closing later is the player
        /// quitting (or a crash), not a refused sign-in, so it asks the Thor for nothing (<see cref="LoginAgainWanted"/>).
        /// [logAtSignIn] = the client's own log is at the login or region screen (<see cref="LoginLog.AtSignInScreens"/>): a
        /// connection held at the region screen must not read as signed in, or a 309 there would ask the Thor for nothing</summary>
        public static bool LoginRunSettled(WowScreen s, bool logAtSignIn = false) =>
            (s == WowScreen.CharSelect || s == WowScreen.World) && !logAtSignIn;

        /// <summary>how long a start waits for the Thor's answer to "login_again" (its login_file), so the new client finds login.txt
        /// at its login screen; the start goes ahead without it after that</summary>
        public const double LoginAgainWaitS = 3;

        /// <summary>
        /// THE GAME'S LOG SPEAKS FOR THIS CLIENT RUN only once its own restart was seen (the user, 2026-11-17: "the auto sign in on
        /// streaming didnt work"). Desktop's log state lasts until the tailer notices the new client emptied its log, 0.1-1.7 s after
        /// the new Wow.exe appears; in that gap the PREVIOUS run's lines (past the login screen) made a 0.5 s old client "CharSelect",
        /// so login.txt's 20 s were counted from Wow.exe's start, not from the sign-in (every PC run 11-13..11-17: deleted 20.2-20.9 s
        /// after "WoW: Starting"). [logRunAt] = when the log state last began from an empty file or from the file's start (a client
        /// restart, Desktop's first read, a path or flavor switch; local time; null = never); [processStart] = the found Wow.exe's start
        /// (local time). A client already up when the file was read from its start wrote that file itself (WoW empties it at launch).
        /// </summary>
        public static bool LogIsThisRun(DateTime? logRunAt, DateTime processStart) => logRunAt.HasValue && logRunAt.Value >= processStart;

        /// <summary>how long WoW stays after the stream ended, and how long the PC itself must have sat untouched</summary>
        public const double StreamGoneS = 300;

        public static bool CloseAfterStream(double sinceStreamS, double pcIdleS) => sinceStreamS >= StreamGoneS && pcIdleS >= StreamGoneS;

        /// <summary>Battle.net just started: its sign-in window (or its main one) takes a moment to appear</summary>
        public const double BnetGraceS = 20;

        /// <summary>
        /// Where the game is. [gameConns] = its established TCP connections to anything but HTTP(S) and Battle.net's 1119 (realm
        /// servers: 1 at character select, 2 in the world, measured on the Thor 2026-10-06); [authConns] = to port 1119 (a sign-in
        /// in progress: the login screen itself holds none, it opens only on Log In); [docAgeS] = since the add-on's last document
        /// (null = none since this client started).
        /// </summary>
        public static WowScreen Classify(bool processUp, double uptimeS, int gameConns, int authConns, LoginLog log, double? docAgeS)
        {
            if (!processUp) return WowScreen.None;
            if (docAgeS.HasValue && docAgeS.Value < WorldDocS) return WowScreen.World;
            if (gameConns >= 2) return WowScreen.World;
            if (gameConns == 1) return WowScreen.CharSelect;
            if (log != null && log.PastLogin) return WowScreen.CharSelect;
            if (log != null && log.Error != null) return WowScreen.Login;
            if (authConns > 0) return WowScreen.SigningIn;
            if (log != null && log.SignInStarted) return WowScreen.SigningIn;
            if (uptimeS < LoadGraceS) return WowScreen.Starting;
            return WowScreen.Login;
        }

        /// <summary>
        /// The Battle.net app: [loginWindow] = its "Battle.net Login" window is up (it is signed out; on the user's PC 2026-10-28 a
        /// Qt window of Battle.net.exe titled exactly that); [anyWindow] = any of its windows exists (a tray-only signed-in app has
        /// its hidden ones). Nothing is read from its files and nothing is typed into it.
        /// </summary>
        public static BnetState Bnet(bool installed, bool running, double uptimeS, bool loginWindow, bool anyWindow)
        {
            if (!installed) return BnetState.Unknown;
            if (!running) return BnetState.NotRunning;
            if (loginWindow) return BnetState.SignInNeeded;
            if (!anyWindow || uptimeS < BnetGraceS) return BnetState.Starting;
            return BnetState.SignedIn;
        }

        /// <summary>
        /// No WoW = start it; WoW at its login screen = bring it to the front, the player signs in there (the user's pick 2026-10-31,
        /// as in Local; a restart would only clear what they typed); signed in = bring it to the front. [tries] = clients started
        /// during this connect (a client found at the login screen counts as the first); from 2, a client that closed itself needs
        /// the player. Battle.net first (see the body; restored 2026-11-17 after 0.2.8 took it out). [busy] = a start is under way.
        /// [failed] = a server error stands (<see cref="LoginLog.ConnectFailed"/>): STOP (the user's ruling 2026-11-18, "Stop and show
        /// it"; 0.2.33 restarted WoW every ~20 s): nothing is started or restarted, the Thor shows the error with a Retry.
        /// [retry] = the Thor's Retry was pressed and not answered yet: a client at its login screen is restarted ONCE (its new run
        /// asks the Thor for the login again, BeforeStart), a closed one is started. [game] = the PC game against Blizzard's version
        /// (<see cref="GameVersionCheck"/>): outdated = WoW is not started (<see cref="WowAction.Outdated"/>); being checked = wait
        /// for the answer (a few seconds at most: offline never blocks).
        /// </summary>
        public static WowAction Decide(BnetState bnet, WowScreen screen, int tries, bool busy, bool failed = false, bool retry = false,
                                       GameCheck game = GameCheck.Unknown)
        {
            if (busy) return WowAction.Wait;
            // signed in: Battle.net never holds a game that is in
            if (screen == WowScreen.CharSelect || screen == WowScreen.World) return WowAction.Focus;
            bool notIn = screen == WowScreen.None || screen == WowScreen.Login;
            // a server error during the sign-in: stop and show it, never a start by ourselves
            if (failed && notIn) return WowAction.Stopped;
            // an outdated PC game is never started (it can't reach the servers), nor one without its install record; a check under
            // way is waited for
            if (game == GameCheck.NeedsRepair && notIn) return WowAction.Repair;
            if (game == GameCheck.Outdated && notIn) return WowAction.Outdated;
            if (game == GameCheck.Checking && notIn) return WowAction.Wait;
            // BATTLE.NET FIRST, as up to 0.2.7 (the user, 2026-11-17: "we had it clipping the Battlenet screen before"): the PC's app
            // not running = start it; signed out = its sign-in window in front, which the Thor shows alone (bnet "signin_needed" plus
            // the "show" rectangle) and types the saved login into. A sign-in under way in WoW is never interrupted. WoW itself still
            // starts plainly (WowLaunch.Plan: never -launcherlogin, the 308 loop of 0.2.7). Unknown = no Battle.net on this PC, or the
            // supervisor gave up waiting for it (WowSupervisor.BnetGiveUpS): WoW's own login only
            if (screen != WowScreen.SigningIn)
            {
                if (bnet == BnetState.NotRunning) return WowAction.StartBattleNet;
                if (bnet == BnetState.Starting) return WowAction.Wait;
                if (bnet == BnetState.SignInNeeded) return WowAction.ShowBattleNetSignIn;
            }
            switch (screen)
            {
                case WowScreen.None: return tries < 2 || retry ? WowAction.StartWow : WowAction.NeedsYou;
                case WowScreen.Starting:
                case WowScreen.SigningIn: return WowAction.Wait;
                case WowScreen.Login: return retry ? WowAction.RestartWow : WowAction.Focus;
                default: return WowAction.Focus;   // character select, the world
            }
        }

        public static string Word(WowScreen s)
        {
            switch (s)
            {
                case WowScreen.Starting: return "starting";
                case WowScreen.Login: return "login";
                case WowScreen.SigningIn: return "signing_in";
                case WowScreen.CharSelect: return "charselect";
                case WowScreen.World: return "world";
                default: return "none";
            }
        }

        /// <summary>
        /// Battle.net as the Thor hears it: "signin_needed" lifts its curtain onto Battle.net's window alone (StreamCurtain), so it is
        /// only said while WoW is NOT signed in; a game at character select or in the world is never hidden behind Battle.net.
        /// (0.2.8 to 0.2.32 always said "unknown", which is what took the Thor's Battle.net sign-in away.)
        /// </summary>
        public static string ThorBnet(BnetState s, WowScreen screen) =>
            s == BnetState.SignInNeeded && (screen == WowScreen.CharSelect || screen == WowScreen.World) ? Word(BnetState.Unknown) : Word(s);

        /// <summary>Battle.net's sign-in is the step the player is on: it is signed out and WoW is not signed in</summary>
        public static bool BnetSignInPending(BnetState s, WowScreen screen) =>
            s == BnetState.SignInNeeded && screen != WowScreen.CharSelect && screen != WowScreen.World;

        public static string Word(BnetState s)
        {
            switch (s)
            {
                case BnetState.NotRunning: return "not_running";
                case BnetState.Starting: return "starting";
                case BnetState.SignInNeeded: return "signin_needed";
                case BnetState.SignedIn: return "signed_in";
                default: return "unknown";
            }
        }

        /// <summary>
        /// A crash report (the game's Errors\*.txt, 2026-10-29 on the user's PC: "ERROR #134 (0x85100086) Fatal condition! ... Invalid
        /// schema WOWSTATIC_12_1_5_70077 for Table StartupFiles" = the client's files are older than the game's data: Battle.net has an
        /// update for it) -> "update"; any other crash -> "crash".
        /// </summary>
        public static string CrashKind(string report)
        {
            var r = report ?? "";
            return r.IndexOf("Invalid schema", StringComparison.OrdinalIgnoreCase) >= 0 ? "update" : "crash";
        }

        /// <summary>the report's one line worth logging (its Description, else its Error line)</summary>
        public static string CrashLine(string report)
        {
            string err = null;
            foreach (var raw in (report ?? "").Split('\n'))
            {
                var l = raw.Trim();
                if (l.StartsWith("Description:")) return l.Substring(12).Trim();
                if (err == null && l.StartsWith("Error:")) err = l.Substring(6).Trim();
            }
            return err ?? "";
        }

        public const string UpdateText = "Update WoW in Battle.net";
        public const string CrashText = "WoW crashed on your PC";


        /// <summary>the message for the player when the one retry did not get WoW signed in: Battle.net's window is shown on the Thor
        /// (AppController.ShowRectNow), so its Play is pressed through the stream. Never "on the PC" (the user, 2026-11-17: "signing in
        /// on the PC wont always be an option")</summary>
        public const string NeedsYouText = "Press Play in Battle.net";

        /// <summary>the Thor's one line after a 309 (<see cref="LoginLog.ConnectFailed"/>; the wire's "connect" "failed"): neutral,
        /// because the log cannot tell a refused district from an unreachable realm</summary>
        public const string ConnectFailedText = "WoW can't reach its servers";

        /// <summary>the status line while the PC's game is older than Blizzard's version (<see cref="WowAction.Outdated"/>). Never "on
        /// the PC" (the user's ruling 2026-11-18)</summary>
        public const string OutdatedText = "PC game needs an update";

        /// <summary>the status line while the game's install record is missing (<see cref="WowAction.Repair"/>). Never "on the PC"</summary>
        public const string RepairText = "PC game needs a repair";

        /// <summary>the stop's one line for the Thor (a 309, an outdated or broken PC game), null = not stopped</summary>
        public static string StopText(WowAction? a) =>
            a == WowAction.Stopped ? ConnectFailedText : a == WowAction.Outdated ? OutdatedText : a == WowAction.Repair ? RepairText : null;

        /// <summary>
        /// The "wow" message while Desktop is stopped ([connectFailed] = a 309 stands; [game] = the PC game's check; [signedIn] = WoW
        /// is at character select or in the world, never stopped then): "stopped" true for a Thor with its Retry (0.2.34), and for an
        /// older Thor (1487), which reads neither "stopped" nor "pc_game" and said "Starting WoW on your PC" for ever (the 0.2.34
        /// review), the words it does show: with WoW not running, state "start_failed" ("WoW didn't start"), and the reason as "why".
        /// WoW running at its login keeps "running": 1487 then shows WoW's own screen with its 309 line. A Thor that reads "stopped"
        /// shows its own red row first, whatever the state. true = stopped.
        /// </summary>
        public static bool StopWords(System.Collections.Generic.Dictionary<string, object> d, bool connectFailed, GameCheck game, bool signedIn)
        {
            bool broken = game == GameCheck.NeedsRepair, old = game == GameCheck.Outdated;
            if (signedIn || !(connectFailed || broken || old)) return false;
            d["stopped"] = true;
            if (!(d.TryGetValue("state", out var st) && (st as string) == WowStarter.Word(WowState.Running)))
                d["state"] = WowStarter.Word(WowState.StartFailed);
            d["why"] = broken ? RepairText : old ? OutdatedText : ConnectFailedText;
            return true;
        }

        /// <summary>an older Thor's reconnect (no "wow_stop" in its hello: it has no Retry) counts as its Retry: only while Desktop is
        /// stopped ([stopped]), and once per <see cref="LegacyRetryEveryS"/> at most ([sinceLastS] = since the last one)</summary>
        public static bool LegacyReconnectRetries(bool stopped, double sinceLastS) => stopped && sinceLastS >= LegacyRetryEveryS;

        public const double LegacyRetryEveryS = 120;
    }
}
