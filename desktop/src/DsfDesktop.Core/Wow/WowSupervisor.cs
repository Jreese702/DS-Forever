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
        readonly Func<int> connectFailures;
        readonly Func<bool> loginAfterFailure;
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
        /// <summary>starts after 309s (WowFlow.RegionRetryMax); they never count as <see cref="Tries"/>. Kept across the connects a
        /// 309 brings (AppController wants WoW again for it), from 0 again on a connect with no 309 waiting</summary>
        public int RegionTries { get; private set; }
        /// <summary>the 309s (LoginLog.ConnectFailures) already answered by a start</summary>
        int regionAnswered;

        /// <summary>a 309 not answered by a start yet, and the region starts are not used up</summary>
        public bool RegionRetryPending { get { lock (gate) return RegionPending(connectFailures()); } }
        bool RegionPending(int refusals) => refusals > regionAnswered && RegionTries < WowFlow.RegionRetryMax;
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
        /// <param name="connectFailures">LoginLog.ConnectFailures: a new one = a 309 (the client closed itself, or stayed at its login)</param>
        /// <param name="loginAfterFailure">the client stayed at its own login screen after the 309, and its run was signed in by the
        /// Thor's login.txt (WowFlow.Decide's [loginAfterFailure]): it is restarted so the new run asks the Thor again</param>
        public WowSupervisor(Func<WowScreen> screen, Func<BnetState> bnet, Func<string> startBnet, Func<string> showBnetSignIn,
                             Func<string> startWow, Func<bool> startPending, Func<string> closeWow, Func<string> focusWow,
                             Func<DateTime> now = null, Action<Action> run = null, Func<string> crash = null, Func<int> connectFailures = null,
                             Func<bool> loginAfterFailure = null)
        {
            this.connectFailures = connectFailures ?? (() => 0);
            this.loginAfterFailure = loginAfterFailure ?? (() => false);
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
                active = true; NeedsYou = false; restarting = false; Crash = null;
                bnetWaitSince = null; bnetGaveUp = false;
                var s = screen();
                Tries = s == WowScreen.Login || s == WowScreen.SigningIn || s == WowScreen.Starting ? 1 : 0;
                if (connectFailures() <= regionAnswered) RegionTries = 0;
                Last = WowAction.Wait;
            }
            Step();
        }

        public void Disconnect() { lock (gate) active = false; }

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
                // after a 309 the client starts again (it closed itself) or is restarted (it stayed at its login, signed in by
                // login.txt) without using up the one retry: the player picks a region again on WoW's own screen (the user's pick
                // 2026-11-17)
                var refusals = connectFailures();
                bool regionRetry = RegionPending(refusals);
                var a = WowFlow.Decide(b, s, Tries, restarting || startPending(), regionRetry, regionRetry && loginAfterFailure());
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
                        if (regionRetry) { RegionTries++; regionAnswered = refusals; }
                        else Tries++;
                        Set(a, startWow());
                        break;
                    case WowAction.RestartWow:
                        if (regionRetry) { RegionTries++; regionAnswered = refusals; }
                        else Tries++;
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
                        active = false;
                        if (s == WowScreen.CharSelect || s == WowScreen.World) { RegionTries = 0; regionAnswered = refusals; }   // signed in
                        Set(a, focusWow());
                        break;
                    case WowAction.NeedsYou:
                        active = false;
                        regionAnswered = refusals;      // the player is asked: no refusal so far starts WoW again
                        NeedsYou = true;
                        Set(a, WowFlow.NeedsYouText);
                        break;
                }
            }
        }
    }
}
