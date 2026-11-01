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

        /// <summary>a new client run (the log was emptied): nothing is known any more</summary>
        public void Reset() { LastScreen = null; Error = null; SignInStarted = false; }

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
                }
                return;
            }
            if (line.IndexOf("[Connection]", StringComparison.Ordinal) < 0) return;
            if (line.IndexOf("Glue login started", StringComparison.Ordinal) >= 0) { SignInStarted = true; Error = null; return; }
            var f = Fatal.Match(line);
            if (f.Success) { Error = Short(f.Groups[1].Value); SignInStarted = false; }
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
        public bool PastLogin => LastScreen != null && LastScreen != LoginScreen && Error == null;
    }

    /// <summary>the pure rules (tested): where the game is, and what a stream that wants it should do</summary>
    public static class WowFlow
    {
        /// <summary>a client this young with no connection is still loading, not "sitting at the login screen"</summary>
        public const double LoadGraceS = 25;
        /// <summary>the add-on writes a document every few seconds in the world (addons do not run before it)</summary>
        public const double WorldDocS = 90;
        /// <summary>WoW's login form as shares of its window, measured on the PC's 2560x1440 client 2026-10-31 (centred: it scales with the height)</summary>
        public const double LoginEmailY = 0.510, LoginPasswordY = 0.577, LoginButtonY = 0.668;

        /// <summary>the login may go in: its form is up, no error, and this client has not had its one attempt</summary>
        public static bool LoginReady(WowScreen screen, string error, int pid, int triedPid) =>
            screen == WowScreen.Login && string.IsNullOrEmpty(error) && pid != triedPid;

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
        /// the player. Battle.net plays no part: WoW starts without it. [busy] = a start is under way.
        /// </summary>
        public static WowAction Decide(BnetState bnet, WowScreen screen, int tries, bool busy)
        {
            if (busy) return WowAction.Wait;
            switch (screen)
            {
                case WowScreen.None: return tries < 2 ? WowAction.StartWow : WowAction.NeedsYou;
                case WowScreen.Starting:
                case WowScreen.SigningIn: return WowAction.Wait;
                default: return WowAction.Focus;   // the login screen, character select, the world
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

        /// <summary>the message for the player when the one retry did not get WoW signed in</summary>
        public const string NeedsYouText = "Sign-in needs you on the PC's Battle.net";
    }
}
