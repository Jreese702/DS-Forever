using System;
using System.Threading.Tasks;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// A stream wants WoW (the Thor connected): apply <see cref="WowFlow.Decide"/> every tick until the game is signed in and in
    /// front, or the one retry is spent ("Press Play in Battle.net", shown on the Thor). The pieces are injected so tests drive it
    /// without a game, Battle.net or a clock.
    /// </summary>
    public sealed class WowSupervisor
    {
        readonly Func<WowScreen> screen;
        readonly Func<BnetState> bnet;
        readonly Func<string> startBnet;
        readonly Func<string> showBnetSignIn;
        readonly Func<string> startWow;
        readonly Func<bool> startPending;
        readonly Func<string> closeWow;
        readonly Func<string> focusWow;
        readonly Func<DateTime> now;
        readonly Func<string> crash;
        readonly Func<bool> failed;
        readonly Func<GameCheck> game;
        readonly Action<Action> run;
        readonly object gate = new object();

        bool active, restarting;
        DateTime bnetStartedAt = DateTime.MinValue;
        /// <summary>when this connect first waited for Battle.net to start (null = not yet)</summary>
        DateTime? bnetWaitSince;
        bool bnetGaveUp;

        /// <summary>how long a connect waits for the PC's Battle.net to come up (not running / starting) before WoW starts without it.
        /// Its sign-in window never times out: the player is signing in</summary>
        public const double BnetGiveUpS = 90;

        /// <summary>clients that have tried to sign in during this connect (see Decide)</summary>
        public int Tries { get; private set; }
        /// <summary>the Thor's Retry (<see cref="Retry"/>) not answered by a start yet: WoW is started, or restarted at its login
        /// screen, ONCE for it</summary>
        public bool RetryPending { get { lock (gate) return retry; } }
        bool retry;
        /// <summary>stopped for a server error (<see cref="WowAction.Stopped"/>) or an outdated PC game (<see cref="WowAction.Outdated"/>):
        /// the Thor's Retry is what goes on; null = not stopped</summary>
        public WowAction? StoppedFor { get; private set; }
        public WowAction Last { get; private set; } = WowAction.Wait;
        /// <summary>the one retry is spent and WoW is still not signed in</summary>
        public bool NeedsYou { get; private set; }
        /// <summary>the client crashed during this connect: "update" (Battle.net has an update for it) / "crash"; null = none. Nothing
        /// is started again then (a client that cannot start would crash in a loop): the player updates it in Battle.net</summary>
        public string Crash { get; private set; }
        public bool Active { get { lock (gate) return active; } }
        /// <summary>(action, detail) when the action changes</summary>
        public event Action<WowAction, string> Changed;

        /// <param name="startPending">a start is under way (the starter waits for the new client's window)</param>
        /// <param name="closeWow">closes the old client (gracefully, then only if still at the login screen by force); blocking; null = gone</param>
        /// <param name="run">how a blocking restart runs (a background task; the tests run it inline)</param>
        /// <param name="failed">a server error stands (LoginLog.ConnectFailed): stop, the Thor shows it with a Retry (WowFlow.Decide)</param>
        /// <param name="game">the PC game against Blizzard's version (GameVersionCheck.Poll): outdated = WoW is not started</param>
        public WowSupervisor(Func<WowScreen> screen, Func<BnetState> bnet, Func<string> startBnet, Func<string> showBnetSignIn,
                             Func<string> startWow, Func<bool> startPending, Func<string> closeWow, Func<string> focusWow,
                             Func<DateTime> now = null, Action<Action> run = null, Func<string> crash = null, Func<bool> failed = null,
                             Func<GameCheck> game = null)
        {
            this.failed = failed ?? (() => false);
            this.game = game ?? (() => GameCheck.Unknown);
            this.screen = screen; this.bnet = bnet; this.startBnet = startBnet; this.showBnetSignIn = showBnetSignIn;
            this.startWow = startWow; this.startPending = startPending; this.closeWow = closeWow; this.focusWow = focusWow;
            this.now = now ?? (() => DateTime.UtcNow);
            this.run = run ?? (a => Task.Run(a));
            this.crash = crash ?? (() => null);
        }

        /// <summary>the Thor connected (a stream starts): a client already sitting at the login screen counts as the first try</summary>
        public void Connect()
        {
            lock (gate)
            {
                // a restart under way keeps its flag (it clears it when it is done): a connect never starts a second one beside it.
                // A Retry not answered by an earlier connect is not carried into this one (0.2.35: it was, and a later connect that
                // found WoW at its login restarted it unasked)
                active = true; NeedsYou = false; Crash = null; StoppedFor = null; retry = false;
                bnetWaitSince = null; bnetGaveUp = false;
                var s = screen();
                Tries = s == WowScreen.Login || s == WowScreen.SigningIn || s == WowScreen.Starting ? 1 : 0;
                Last = WowAction.Wait;
            }
            Step();
        }

        /// <summary>
        /// The Thor's Retry after a stop (the user's ruling 2026-11-18, "Stop and show it": Retry restarts WoW once per press). The
        /// caller has cleared the failure (LoginLog.ClearFailure) and asked Blizzard's version again first. A client at its login
        /// screen is restarted once (its new run asks the Thor for the login again: AppController.BeforeStart), a closed one started;
        /// Battle.net's sign-in still comes first while it is signed out, and an outdated game is still never started.
        /// </summary>
        /// <remarks>0.2.35: ONE start per press. A press while a restart or a start is under way is not a second one (0.2.34 cleared
        /// the restart's flag, so a second press during its close could run a second close/start beside it): it is dropped, false.
        /// Tries starts at 1, so the press's own client closing again without a server error is "Press Play in Battle.net", never a
        /// second start (0.2.34 set 0, and Decide's tries &lt; 2 started it twice)</remarks>
        public bool Retry()
        {
            lock (gate)
            {
                if (Busy)
                {
                    Log.I("stream wants WoW: the Thor's Retry while a " + (restarting ? "restart" : "start") + " is under way: that one counts");
                    return false;
                }
                active = true; NeedsYou = false; StoppedFor = null; retry = true;
                bnetWaitSince = null; bnetGaveUp = false;
                Tries = 1;
                Last = WowAction.Wait;
            }
            Step();
            return true;
        }

        /// <summary>a restart (close, then start) or a start is under way: a Retry now is not another one</summary>
        public bool Busy { get { lock (gate) return restarting || startPending(); } }

        /// <summary>the Thor left: nothing goes on for it, and a Retry it pressed and that was not answered yet goes too</summary>
        public void Disconnect() { lock (gate) { active = false; retry = false; } }

        /// <summary>a healthy client runs again (the player updated and started it): the crash is behind us</summary>
        public void CrashOver() { lock (gate) Crash = null; }

        void Set(WowAction a, string detail)
        {
            if (a == Last) return;
            Last = a;
            Log.I("stream wants WoW: " + a + (detail != null ? " (" + detail + ")" : ""));
            try { Changed?.Invoke(a, detail); } catch (Exception) { }
        }

        /// <summary>called on connect and every tick while active</summary>
        public void Step()
        {
            lock (gate)
            {
                if (!active) return;
                var c = crash();
                if (c != null)
                {
                    active = false;
                    Crash = c;
                    Set(WowAction.Crashed, c);
                    return;
                }
                var b = bnet();
                var s = screen();
                // Battle.net that never comes up must not hold WoW for ever: after BnetGiveUpS, WoW's own login only
                if (b == BnetState.NotRunning || b == BnetState.Starting)
                {
                    if (!bnetWaitSince.HasValue) bnetWaitSince = now();
                    if ((now() - bnetWaitSince.Value).TotalSeconds >= BnetGiveUpS)
                    {
                        if (!bnetGaveUp) { bnetGaveUp = true; Log.I("stream wants WoW: Battle.net didn't come up in " + (int)BnetGiveUpS + " s: WoW starts without it"); }
                        b = BnetState.Unknown;
                    }
                }
                // a server error stops here (never a start by ourselves: the user's ruling 2026-11-18); the Thor's Retry starts WoW once
                var a = WowFlow.Decide(b, s, Tries, restarting || startPending(), failed(), retry, game());
                switch (a)
                {
                    case WowAction.Wait:
                        Set(a, null);
                        break;
                    case WowAction.StartBattleNet:
                        // once per half minute: Battle.net takes its time to come up
                        if (now() - bnetStartedAt > TimeSpan.FromSeconds(30)) { bnetStartedAt = now(); Set(a, startBnet()); }
                        break;
                    case WowAction.ShowBattleNetSignIn:
                        // its sign-in window in front, once (never pulled back over a browser the sign-in may open); the player signs in
                        if (Last != a) Set(a, showBnetSignIn());
                        break;
                    case WowAction.StartWow:
                        Tries++; retry = false;
                        Set(a, startWow());
                        break;
                    case WowAction.RestartWow:
                        Tries++; retry = false;
                        restarting = true;
                        Set(a, null);
                        run(() =>
                        {
                            string err = null;
                            try { err = closeWow(); if (err == null) err = startWow(); }
                            catch (Exception e) { err = e.Message; }
                            if (err != null) Log.I("restart WoW: " + err);
                            lock (gate) restarting = false;
                        });
                        break;
                    case WowAction.Focus:
                        active = false; retry = false;
                        Set(a, focusWow());
                        break;
                    case WowAction.Stopped:
                    case WowAction.Outdated:
                    case WowAction.Repair:
                        // stop and show it: the Thor shows the error (or the versions) with a Retry; nothing more by ourselves
                        active = false; retry = false;
                        StoppedFor = a;
                        Set(a, WowFlow.StopText(a));
                        break;
                    case WowAction.NeedsYou:
                        active = false; retry = false;
                        NeedsYou = true;
                        Set(a, WowFlow.NeedsYouText);
                        break;
                }
            }
        }
    }
}
