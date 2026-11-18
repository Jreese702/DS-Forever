using System;
using System.Text.RegularExpressions;

namespace DsfDesktop.Wow
{
    /// <summary>where the game is (the "wow" message's screen)</summary>
    public enum WowScreen { None, Starting, Login, SigningIn, CharSelect, World }

    /// <summary>the PC's Battle.net app: what it needs before WoW can sign in through it</summary>
    public enum BnetState { Unknown, NotRunning, Starting, SignInNeeded, SignedIn }

    /// <summary>what to do now, for a stream that wants WoW (the user, 2026-10-28)</summary>
    public enum WowAction { Wait, StartBattleNet, ShowBattleNetSignIn, StartWow, RestartWow, Focus, NeedsYou, Crashed }

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

        /// <summary>the game's refusal of the region the player picked on its region screen (309, the user's PC 2026-11-17)</summary>
        public const string RegionRefusedCode = "309";

        /// <summary>
        /// The game refused the region picked on its own region screen (<see cref="RegionScreen"/>; "Fatal login error (309)") and no
        /// character select since. The player picks again on WoW's screen (the user's pick 2026-11-17: "it shouldnt auto pick it should
        /// be changable, just it changing shouldnt lock it in"): Desktop never picks or saves a region, it only says so to the Thor.
        /// Unlike <see cref="Error"/>, a screen switch back to the login or the region screen keeps it.
        /// </summary>
        public bool RegionRefused { get; private set; }

        /// <summary>when <see cref="RegionRefused"/> last turned on (<see cref="Now"/>'s clock), null = never in this Desktop run</summary>
        public DateTime? RegionRefusedAt { get; private set; }

        /// <summary>
        /// Refusals of a region the player picked on WoW's own region screen (a 309 while <see cref="RegionScreen"/> was the last
        /// screen), counted for this Desktop run and never reset: each one lets the supervisor start the self-closed client again
        /// without using up its one retry (WowSupervisor), so the player can always pick another region
        /// </summary>
        public int RegionRefusals { get; private set; }

        /// <summary>the client's log is at the login or region screen (its sign-in is not over, whatever its connections say)</summary>
        public bool AtSignInScreens => LastScreen == LoginScreen || LastScreen == RegionScreen;

        /// <summary>the clock for <see cref="RegionRefusedAt"/> (UTC; a test sets its own)</summary>
        public Func<DateTime> Now = () => DateTime.UtcNow;

        /// <summary>
        /// How long a refused region outlasts its client: WoW closes itself after a 309 (the user's PC 2026-11-17) and Desktop starts
        /// it once more (WowFlow.Decide), so the line belongs on the NEXT client's login and region screens too. Never longer than
        /// this: a client started much later is a new try, not the refused one.
        /// </summary>
        public const double RegionCarryS = 300;

        /// <summary>a new client run (the log was emptied): nothing is known any more, except a region refused within
        /// <see cref="RegionCarryS"/> (its client closed itself; the restarted one shows the same region screen)</summary>
        public void Reset()
        {
            LastScreen = null; Error = null; SignInStarted = false;
            RegionRefused = RegionRefused && RegionRefusedAt.HasValue && (Now() - RegionRefusedAt.Value).TotalSeconds < RegionCarryS;
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
                    else if (LastScreen != RegionScreen) RegionRefused = false;     // past the login: the region was taken
                }
                return;
            }
            if (line.IndexOf("[Connection]", StringComparison.Ordinal) < 0) return;
            if (line.IndexOf("Glue login started", StringComparison.Ordinal) >= 0) { SignInStarted = true; Error = null; return; }
            var f = Fatal.Match(line);
            if (f.Success)
            {
                Error = Short(f.Groups[1].Value); SignInStarted = false; RegionRefused = Error == RegionRefusedCode;
                if (RegionRefused) { RegionRefusedAt = Now(); if (LastScreen == RegionScreen) RegionRefusals++; }
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
        /// the player. Battle.net plays no part: WoW starts without it. [busy] = a start is under way. [regionRetry] = the client
        /// closed itself after a region the player picked was refused (309) and the region starts are not used up
        /// (<see cref="RegionRetryMax"/>): it starts again whatever [tries] says, so the player picks another region on WoW's screen
        /// (the user's pick 2026-11-17: the region "should be changable")
        /// </summary>
        public static WowAction Decide(BnetState bnet, WowScreen screen, int tries, bool busy, bool regionRetry = false)
        {
            if (busy) return WowAction.Wait;
            switch (screen)
            {
                case WowScreen.None: return tries < 2 || regionRetry ? WowAction.StartWow : WowAction.NeedsYou;
                case WowScreen.Starting:
                case WowScreen.SigningIn: return WowAction.Wait;
                default: return WowAction.Focus;   // the login screen, character select, the world
            }
        }

        /// <summary>starts after refused regions per connect: each needs a new refusal of a region picked on WoW's screen; the cap
        /// only stops a loop should WoW ever refuse without the player's pick</summary>
        public const int RegionRetryMax = 5;

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

        /// <summary>the Thor's line while WoW's region screen waits after a refused region (<see cref="LoginLog.RegionRefused"/>)</summary>
        public const string RegionRefusedText = "Region refused: pick another region";

        /// <summary>the message for the player when the one retry did not get WoW signed in</summary>
        public const string NeedsYouText = "Sign-in needs you on the PC's Battle.net";
    }
}
