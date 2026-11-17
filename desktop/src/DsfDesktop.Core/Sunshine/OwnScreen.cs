using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using DsfDesktop.Wow;

namespace DsfDesktop.Sunshine
{
    /// <summary>
    /// A way to make OUR virtual screen (Part 2, the user's pick 2026-11-17 "Sunshine + own virtual screen"). Implementations:
    /// <see cref="OurScreen"/> (SudoVDA, per-session, keyed by our GUID) and <see cref="VddScreen"/> (the MIT Virtual Display Driver's
    /// lasting monitor, attached to the desktop for the stream and detached after). Neither ever touches Apollo's screens.
    /// </summary>
    public interface IScreenDriver
    {
        string Name { get; }
        /// <summary>the driver is on this PC</summary>
        bool Installed();
        /// <summary>it can make OUR screen now (e.g. the VDD's monitor isn't one the player has on for themselves)</summary>
        bool Usable();
        /// <summary>makes it at the mode; null = made (GdiName set when Windows named it), else why not. A failed make leaves nothing
        /// of ours behind (reviewers 2026-11-17: a VDD monitor attached but never active stayed attached)</summary>
        string Make(int w, int h, int hz);
        /// <summary>
        /// Ours (it exists) to a new mode without taking it away first (reviewers 2026-11-17: remove-then-make moved WoW to another
        /// screen): null = done - in place (the VDD's monitor: its mode changed), or a new screen up beside the old one (SudoVDA: its
        /// mode is fixed at ADD), which <see cref="RetireOld"/> removes once WoW is on the new one. Else why not: nothing changed.
        /// </summary>
        string Resize(int w, int h, int hz);
        /// <summary>the old screen a <see cref="Resize"/> left beside the new one, removed; nothing to do when there is none</summary>
        void RetireOld(string why);
        /// <summary>the old screen a <see cref="Resize"/> left beside the new one ("\\.\DISPLAYn"), null = none</summary>
        string OldGdiName { get; }
        /// <summary>ours removed (and an old one beside it); null = gone, else why not (it is still there)</summary>
        string Remove(string why);
        bool Exists { get; }
        /// <summary>"\\.\DISPLAYn" of our screen, null = none / not named yet</summary>
        string GdiName { get; }
        (int w, int h, int hz) Mode { get; }
    }

    /// <summary>
    /// Our virtual screen's lifecycle: which driver makes it (in order: SudoVDA if installed, then the Virtual Display Driver), the
    /// 60 s grace after the last Thor/stream before it goes, never removed or made mid-stream when that would cut the stream, a
    /// 2-minute pause after a failed make, re-made when the Thor's mode changed while idle. NEVER A LOOP (the user's rule; reviewers
    /// 2026-11-17): <see cref="MaxFails"/> failed makes (or screens our Sunshine didn't list) in a Thor session and it gives up -
    /// a real screen is streamed, or a headless PC says it needs a virtual screen - until the Thor reconnects or the player presses
    /// Retry / Test (<see cref="Reset"/>); <see cref="MaxRemoveFails"/> failed removals and ours is left on, said once. Nothing is
    /// made in the grace (only kept or removed). Thread-safe; no Windows calls of its own (the drivers make them), so the tests
    /// drive it with fakes.
    /// </summary>
    public sealed class ScreenLife
    {
        public static readonly TimeSpan DefaultGrace = TimeSpan.FromSeconds(60), RetryAfter = TimeSpan.FromMinutes(2);
        public const string ModeChanged = "the Thor's mode changed", NotOnOurs = "our Sunshine isn't on it", ThorGone = "the Thor has gone";
        /// <summary>failed makes / not-listed screens in one Thor session before it gives up</summary>
        public const int MaxFails = 3;
        /// <summary>failed removals (a VDD detach Windows refused) before ours is left on</summary>
        public const int MaxRemoveFails = 3;

        readonly IList<IScreenDriver> drivers;
        readonly object gate = new object();
        readonly TimeSpan grace;
        DateTime lastThorAt = DateTime.MinValue, failedAt = DateTime.MinValue, removeFailedAt = DateTime.MinValue;
        int fails, removeFails;
        bool gaveUp, removeGaveUp;
        /// <summary>the mode ours was made FOR (the Thor's ask). A driver may give a nearer offered one (the VDD's vdd_settings.xml list):
        /// comparing the Thor's mode with that would remove and re-make ours on every tick. null = adopted, not known yet</summary>
        (int w, int h, int hz)? asked;

        public ScreenLife(IList<IScreenDriver> drivers, TimeSpan? grace = null)
        {
            this.drivers = drivers ?? new List<IScreenDriver>();
            this.grace = grace ?? DefaultGrace;
        }

        /// <summary>the driver whose screen exists now, null = none</summary>
        public IScreenDriver Current { get; private set; }
        public bool Exists { get { lock (gate) return Adopt() != null; } }
        public string GdiName { get { lock (gate) return Adopt()?.GdiName; } }
        public (int w, int h, int hz) Mode { get { lock (gate) return Adopt()?.Mode ?? (0, 0, 0); } }
        /// <summary>it gave up making ours this Thor session (<see cref="MaxFails"/>): no more tries until <see cref="Reset"/></summary>
        public bool GaveUp { get { lock (gate) return gaveUp; } }
        /// <summary>it gave up removing ours (<see cref="MaxRemoveFails"/>): left on until <see cref="Reset"/></summary>
        public bool RemoveGaveUp { get { lock (gate) return removeGaveUp; } }
        public int Fails { get { lock (gate) return fails; } }

        /// <summary>the screen that exists now and the mode it was made for, read WITHOUT adopting (a pure look for <see cref="Peek"/>)</summary>
        (IScreenDriver cur, (int w, int h, int hz)? asked) View()
        {
            if (Current != null && Has(Current)) return (Current, asked);
            var d = drivers.FirstOrDefault(Has);
            if (d == null) return (null, null);
            if (ReferenceEquals(d, Current)) return (d, asked);
            var m = d.Mode;
            return (d, m.w > 0 ? m : ((int, int, int)?)null);
        }

        static bool Has(IScreenDriver d) { try { return d.Exists; } catch (Exception) { return false; } }

        /// <summary>a screen a driver still has from before (a VDD monitor we attached and Desktop didn't detach): ours</summary>
        IScreenDriver Adopt()
        {
            var (cur, a) = View();
            Current = cur;
            asked = a;
            return Current;
        }

        /// <summary>the mode ours was made for (null = none, or a leftover of unknown mode)</summary>
        public (int w, int h, int hz)? Asked { get { lock (gate) { Adopt(); return asked; } } }

        /// <summary>ours was made but couldn't be used (our Sunshine didn't list it): the same 2-minute pause as a failed make, and a
        /// try counted towards <see cref="MaxFails"/></summary>
        public void Failed(DateTime now) { lock (gate) { failedAt = now; Fail("our Sunshine didn't list it"); } }

        void Fail(string what)
        {
            fails++;
            if (fails < MaxFails || gaveUp) return;
            gaveUp = true;
            Log.I("virtual screen: " + MaxFails + " tries failed this session (" + what + "): no more until the Thor reconnects, Retry or Test");
        }

        /// <summary>
        /// The tries start over: [byHand] = the player's Retry / Test (the pause goes too); else a Thor (re)connected (the counts only:
        /// a Thor that reconnects every few seconds still waits the 2-minute pause between tries).
        /// </summary>
        public void Reset(bool byHand)
        {
            lock (gate)
            {
                fails = 0; gaveUp = false; removeFails = 0; removeGaveUp = false;
                if (byHand) { failedAt = DateTime.MinValue; removeFailedAt = DateTime.MinValue; }
            }
        }

        /// <summary>a Thor is connected or streaming, or was within the grace</summary>
        public bool ThorWants(DateTime now, bool thorConnected, bool streaming)
        {
            lock (gate)
            {
                if (thorConnected || streaming) lastThorAt = now;
                return thorConnected || streaming || now - lastThorAt < grace;
            }
        }

        /// <summary>(any driver installed, one usable now)</summary>
        public (bool installed, bool usable) Survey()
        {
            bool inst = false, use = false;
            foreach (var d in drivers)
            {
                try { if (d.Installed()) { inst = true; if (d.Usable()) use = true; } } catch (Exception) { }
            }
            return (inst, use);
        }

        /// <summary>
        /// The step now, with its reason for the log. [hasScreen] = a screen on that is not ours (see <see cref="ScreenPlan.Decide(bool,bool,bool,bool,bool,bool)"/>);
        /// [want] = the Thor's mode; [sunshineOnOurs] = our Sunshine captures ours now (false: a leftover adopted while a Thor wants it,
        /// verifier 2026-11-17 - kept, but output_name was never pointed at it and WoW never moved there); [thorHere] = a Thor is
        /// connected or streaming NOW (false in the grace: ours is kept or removed, never made - reviewers 2026-11-17).
        /// MakeOurs on a screen that exists = point our Sunshine at it, or (the Thor's mode changed) change its mode without taking it
        /// away (<see cref="IScreenDriver.Resize"/>): WoW stays on ours. Adopts a leftover (see <see cref="Peek"/> for a pure look).
        /// </summary>
        public (ScreenPlan.Step step, string why) Decide(DateTime now, bool ownOn, bool hasScreen, bool driverUsable, bool thorWants, bool offered,
            (int w, int h, int hz) want, bool streaming, bool sunshineOnOurs = true, bool thorHere = true)
        {
            lock (gate)
            {
                var ours = Adopt();
                var r = Plan(now, ownOn, hasScreen, driverUsable, thorWants, offered, want, streaming, sunshineOnOurs, thorHere, ours, asked, out var a);
                asked = a;
                return r;
            }
        }

        /// <summary>
        /// <see cref="Decide"/>'s answer with nothing written (reviewers 2026-11-17: the Thor's wait word asked Decide, which adopted a
        /// leftover and took the Thor's mode as the one ours was made for): for "would ours be made now?"
        /// </summary>
        public (ScreenPlan.Step step, string why) Peek(DateTime now, bool ownOn, bool hasScreen, bool driverUsable, bool thorWants, bool offered,
            (int w, int h, int hz) want, bool streaming, bool sunshineOnOurs = true, bool thorHere = true)
        {
            lock (gate)
            {
                var (ours, a) = View();
                return Plan(now, ownOn, hasScreen, driverUsable, thorWants, offered, want, streaming, sunshineOnOurs, thorHere, ours, a, out _);
            }
        }

        (ScreenPlan.Step step, string why) Plan(DateTime now, bool ownOn, bool hasScreen, bool driverUsable, bool thorWants, bool offered,
            (int w, int h, int hz) want, bool streaming, bool sunshineOnOurs, bool thorHere, IScreenDriver ours,
            (int w, int h, int hz)? askedIn, out (int w, int h, int hz)? askedOut)
        {
            askedOut = askedIn;
            var step = ScreenPlan.Decide(ownOn, hasScreen, driverUsable, ours != null, thorWants, offered);
            switch (step)
            {
                case ScreenPlan.Step.RemoveOurs:
                    // a removal mid-stream cuts it: after the stream
                    if (streaming) return (ScreenPlan.Step.Keep, "streaming");
                    // a removal Windows refused: not again every tick (paused), and not at all after MaxRemoveFails
                    if (removeGaveUp) return (ScreenPlan.Step.Keep, null);
                    if (now - removeFailedAt < RetryAfter) return (ScreenPlan.Step.Keep, null);
                    return (step, !thorWants ? "no Thor for " + (int)grace.TotalSeconds + " s" : "a screen is on");
                case ScreenPlan.Step.Keep:
                    // a leftover of unknown mode is taken as it is (no remake loop)
                    if (!askedIn.HasValue) askedOut = want;
                    if (streaming || !thorHere || gaveUp) return (step, null);
                    if (now - failedAt < RetryAfter) return (step, null);
                    if (askedOut.Value != want) return (ScreenPlan.Step.MakeOurs, ModeChanged);
                    if (!sunshineOnOurs) return (ScreenPlan.Step.MakeOurs, NotOnOurs);
                    return (step, null);
                case ScreenPlan.Step.MakeOurs:
                    // the grace is for keeping ours a moment, not for making one for a Thor that has gone
                    if (!thorHere) return (ScreenPlan.Step.None, ThorGone);
                    // making ours restarts our Sunshine: never under a running stream that already shows a real screen
                    if (streaming && hasScreen) return (ScreenPlan.Step.Fallback, "a stream started first");
                    if (gaveUp) return (ScreenPlan.Step.GaveUp, MaxFails + " tries failed");
                    if (now - failedAt < RetryAfter) return (hasScreen ? ScreenPlan.Step.Fallback : ScreenPlan.Step.Wait, "failed a moment ago");
                    return (step, null);
                default:
                    return (step, null);
            }
        }

        /// <summary>
        /// Makes ours through the first usable driver that manages it (in order); null = made, Current set and named, else every
        /// driver's reason (a try towards <see cref="MaxFails"/>). A driver that made one but Windows didn't name is undone. Ours at
        /// another mode is resized (<see cref="IScreenDriver.Resize"/>) - only when the driver can't is it removed and made again.
        /// </summary>
        public string Make(int w, int h, int hz, DateTime now)
        {
            lock (gate)
            {
                var cur = Adopt();
                if (cur != null)
                {
                    // there already: as it is (a leftover of unknown mode too), or at the Thor's new mode
                    if (!asked.HasValue || asked.Value == (w, h, hz)) return null;
                    string rs;
                    try { rs = cur.Resize(w, h, hz); } catch (Exception e) { rs = e.Message; }
                    if (rs == null) { asked = (w, h, hz); return null; }
                    Log.I("virtual screen: " + cur.Name + " can't change its mode (" + rs + "): removed and made again");
                    string rm;
                    try { rm = cur.Remove(ModeChanged); } catch (Exception e) { rm = e.Message; }
                    if (rm != null)
                    {
                        failedAt = now;
                        Fail("the old one couldn't be removed");
                        return cur.Name + ": the old one couldn't be removed (" + rm + ")";
                    }
                    Current = null;
                    asked = null;
                }
                var whys = new List<string>();
                foreach (var d in drivers)
                {
                    try
                    {
                        if (!d.Installed()) continue;
                        if (!d.Usable()) { whys.Add(d.Name + ": in use"); continue; }
                        var why = d.Make(w, h, hz);
                        if (why == null && d.GdiName == null) { d.Remove("no display name"); why = "it didn't appear"; }
                        if (why == null) { Current = d; asked = (w, h, hz); return null; }
                        whys.Add(d.Name + ": " + why);
                    }
                    catch (Exception e) { whys.Add(d.Name + ": " + e.Message); }
                }
                failedAt = now;
                var all = whys.Count > 0 ? string.Join("; ", whys) : "no virtual screen driver";
                Fail(all);
                return all;
            }
        }

        /// <summary>the old screen a resize left beside the new one, removed (WoW is on the new one now)</summary>
        public void RetireOld(string why)
        {
            lock (gate)
            {
                try { Current?.RetireOld(why); } catch (Exception e) { Log.I("virtual screen: retire the old one: " + e.Message); }
            }
        }

        /// <summary>the old screen a resize left beside ours ("\\.\DISPLAYn"), null = none</summary>
        public string OldGdiName { get { lock (gate) { try { return Current?.OldGdiName; } catch (Exception) { return null; } } } }

        /// <summary>ours removed; false = Windows refused (it stays ours: tried again after the pause, left on after
        /// <see cref="MaxRemoveFails"/>, said once)</summary>
        public bool Remove(string why) => Remove(why, DateTime.UtcNow);

        public bool Remove(string why, DateTime now)
        {
            lock (gate)
            {
                var c = Adopt();
                if (c == null) return true;
                string err;
                try { err = c.Remove(why); } catch (Exception e) { err = e.Message; }
                if (err != null)
                {
                    removeFailedAt = now;
                    removeFails++;
                    Log.I("virtual screen: " + c.Name + " couldn't be removed (" + removeFails + "/" + MaxRemoveFails + "): " + err);
                    if (removeFails >= MaxRemoveFails && !removeGaveUp)
                    {
                        removeGaveUp = true;
                        Log.I("virtual screen: " + MaxRemoveFails + " removals refused: ours is left on (turn it off in Windows' display settings)");
                    }
                    return false;
                }
                removeFails = 0;
                Current = null;
                asked = null;
                return true;
            }
        }
    }

    /// <summary>
    /// Our Virtual Display Driver install (the player's yes): the monitor it brings - a VDD monitor that wasn't there before, on,
    /// within <see cref="Window"/> of the start, and the installer not failed (a declined admin prompt exits non-zero) - is ours,
    /// by its name. Nothing is ours before that (verifier 2026-11-17: the flag was set at the yes, so after a declined install the
    /// first VDD monitor the player put on later was adopted and detached). Pure.
    /// </summary>
    public static class VddInstallWatch
    {
        public static readonly TimeSpan Window = TimeSpan.FromMinutes(15);

        /// <summary>the monitor our install brought; null = none (yet)</summary>
        public static string Adopt(DateTime startedUtc, DateTime nowUtc, int? exitCode, IEnumerable<string> before, List<WinDisplay> win)
        {
            if (Over(startedUtc, nowUtc, exitCode)) return null;
            var b = new HashSet<string>(before ?? Enumerable.Empty<string>(), StringComparer.OrdinalIgnoreCase);
            return (win ?? new List<WinDisplay>()).Where(d => VddScreen.IsVdd(d) && d.Active && !b.Contains(d.Name ?? ""))
                                                  .Select(d => d.Name).FirstOrDefault(n => !string.IsNullOrEmpty(n));
        }

        /// <summary>the watch is over: none started, the installer failed, or the window passed</summary>
        public static bool Over(DateTime startedUtc, DateTime nowUtc, int? exitCode)
            => startedUtc == DateTime.MinValue || (exitCode.HasValue && exitCode.Value != 0) || nowUtc - startedUtc > Window;
    }

    /// <summary>the VDD monitor's mode closest to the Thor's (the driver offers only the modes in its vdd_settings.xml). Pure.</summary>
    public static class VddModes
    {
        /// <summary>
        /// Exact if offered; else the same size at the highest rate not above the asked one (else the lowest above); else the offered
        /// size of the same shape nearest in area, same rate rule; else the nearest in area. No list (unread): the asked mode.
        /// </summary>
        public static (int w, int h, int hz) Pick(IEnumerable<(int w, int h, int hz)> offered, int w, int h, int hz)
        {
            var list = (offered ?? Enumerable.Empty<(int w, int h, int hz)>()).Where(m => m.w > 0 && m.h > 0 && m.hz > 0).Distinct().ToList();
            if (list.Count == 0 || list.Contains((w, h, hz))) return (w, h, hz);
            double shape = (double)w / h;
            long area = (long)w * h;
            var sizes = list.Select(m => (m.w, m.h)).Distinct().ToList();
            var same = sizes.Where(s => s.w == w && s.h == h).ToList();
            if (same.Count == 0) same = sizes.Where(s => Math.Abs((double)s.w / s.h - shape) < 0.01).OrderBy(s => Math.Abs((long)s.w * s.h - area)).Take(1).ToList();
            if (same.Count == 0) same = sizes.OrderBy(s => Math.Abs((long)s.w * s.h - area)).Take(1).ToList();
            var size = same[0];
            var rates = list.Where(m => m.w == size.w && m.h == size.h).Select(m => m.hz).ToList();
            int rate = rates.Where(r => r <= hz).DefaultIfEmpty(-1).Max();
            if (rate < 0) rate = rates.Min();
            return (size.w, size.h, rate);
        }
    }

    /// <summary>the Windows calls <see cref="VddScreen"/> makes (user32; no admin); a fake in the tests</summary>
    public interface IVddWindows
    {
        List<WinDisplay> Displays();
        /// <summary>the modes the monitor offers (EnumDisplaySettings)</summary>
        List<(int w, int h, int hz)> Modes(string dev);
        /// <summary>its mode now; (0,0,0) = unreadable</summary>
        (int w, int h, int hz) Current(string dev);
        /// <summary>its place on the desktop, null = not on it</summary>
        (int x, int y)? Position(string dev);
        /// <summary>the right edge of every screen on (ours goes to the right of them)</summary>
        int RightEdge();
        /// <summary>attaches / changes it (ChangeDisplaySettingsEx CDS_UPDATEREGISTRY, then applied); null = done</summary>
        string Set(string dev, int w, int h, int hz, int x, int y);
        /// <summary>off the desktop (size 0, registry too); null = done</summary>
        string Detach(string dev);
        void Sleep(int ms);
    }

    /// <summary>
    /// The MIT Virtual Display Driver (github.com/VirtualDrivers/Virtual-Display-Driver; winget id
    /// "VirtualDrivers.Virtual-Display-Driver", manifest 25.7.23 in microsoft/winget-pkgs on 2026-11-17). From its own source
    /// ("Virtual Display Driver (HDR)/MttVDD/Driver.cpp") and README:
    ///  - it makes &lt;monitors&gt;&lt;count&gt; LASTING monitors (count 0 is read as 1) with the modes listed in vdd_settings.xml
    ///    (C:\VirtualDisplayDriver, or HKLM\SOFTWARE\MikeTheTech\VirtualDisplayDriver "VDDPATH"): every &lt;resolution&gt; at its
    ///    &lt;refresh_rate&gt;, plus every resolution at every &lt;global&gt;&lt;g_refresh_rate&gt; (the default file: 1920x1080 /
    ///    2560x1440 / 3840x2160 at 60, 90, 120, 144, 165, 240);
    ///  - its pipe \\.\pipe\MTTVirtualDisplayPipe (Everyone) takes SETDISPLAYCOUNT / RELOAD_DRIVER etc., but they rewrite the
    ///    driver's file and reload every one of its monitors, so we never use it.
    /// So "making" ours = attaching its (inactive) monitor to the desktop at the offered mode nearest the Thor's, and removing =
    /// detaching it again (ChangeDisplaySettingsEx, the player's own display settings API: no admin). Its monitor is ours only when it
    /// was off (we attach it) or our install put it there; one the player keeps on for themselves is never used, sized or detached.
    /// A new mode is set on the monitor in place (it stays on the desktop, WoW stays on it). The monitor is recorded as ours BEFORE
    /// it is attached, so a crash between the two still leaves it ours to detach at the next start.
    /// </summary>
    public sealed class VddScreen : IScreenDriver
    {
        readonly Func<bool> attachedByUs;
        readonly Action<bool> setAttached;
        readonly Func<string> ourDevice;
        readonly Action<string> setDevice;
        readonly IVddWindows win;
        readonly object gate = new object();

        /// <summary>[attachedByUs]/[setAttached] = a lasting flag (settings): we attached it and haven't detached it yet (our install sets
        /// it: its first monitor comes up on by itself); [ourDevice]/[setDevice] = the display name we attached (lasting), so a second
        /// VDD monitor the player has on is never adopted or detached; [win] = the Windows calls (null = the live ones)</summary>
        public VddScreen(Func<bool> attachedByUs, Action<bool> setAttached, Func<string> ourDevice = null, Action<string> setDevice = null,
                         IVddWindows win = null)
        {
            this.attachedByUs = attachedByUs;
            this.setAttached = setAttached;
            this.ourDevice = ourDevice ?? (() => null);
            this.setDevice = setDevice ?? (_ => { });
            this.win = win ?? new Native();
        }

        public string Name => "Virtual Display Driver";

        public static bool IsVdd(WinDisplay d)
            => d != null && ((d.Adapter ?? "").IndexOf("Virtual Display Driver", StringComparison.OrdinalIgnoreCase) >= 0
                             || (d.AdapterId ?? "").IndexOf("MttVDD", StringComparison.OrdinalIgnoreCase) >= 0);

        /// <summary>the VDD's monitor to use: the active one we attached (see <see cref="Ours"/>), else an inactive one (ours to
        /// attach); null = none. Only "attached by us" counts: once we detach it, a monitor the player turns on is theirs</summary>
        public static WinDisplay Pick(List<WinDisplay> win, bool attachedByUs, string ourDevice = null)
        {
            var vdd = (win ?? new List<WinDisplay>()).Where(IsVdd).ToList();
            if (vdd.Count == 0) return null;
            var on = Ours(vdd, attachedByUs, ourDevice);
            if (on != null) return on;
            return vdd.FirstOrDefault(d => !d.Active);
        }

        /// <summary>
        /// The active VDD monitor that is OURS: only while we have it attached, and only the one we named when we did (our install's
        /// monitor is named by <see cref="VddInstallWatch"/> when it appears). null = none: never another active VDD monitor, and with
        /// no name kept none at all (verifier 2026-11-17: "the first active one" took a monitor the player put on after a declined
        /// install).
        /// </summary>
        public static WinDisplay Ours(List<WinDisplay> win, bool attachedByUs, string ourDevice)
        {
            if (!attachedByUs || string.IsNullOrEmpty(ourDevice)) return null;
            return (win ?? new List<WinDisplay>()).FirstOrDefault(d => IsVdd(d) && d.Active && string.Equals(d.Name, ourDevice, StringComparison.OrdinalIgnoreCase));
        }

        public bool Installed() => win.Displays().Any(IsVdd);
        public bool Usable() => Pick(win.Displays(), attachedByUs(), ourDevice()) != null;

        public bool Exists
        {
            get
            {
                lock (gate)
                {
                    // only while we have it attached (our install counts as attached until its first detach): a VDD monitor the player
                    // turns on for themselves later is never adopted or detached
                    if (!attachedByUs()) return false;
                    var all = win.Displays().Where(IsVdd).ToList();
                    if (all.Count == 0) return false;          // not installed (yet): our install's flag waits for it
                    var d = Ours(all, true, ourDevice());
                    if (d == null) { setAttached(false); setDevice(null); GdiName = null; return false; }
                    if (!string.Equals(GdiName, d.Name, StringComparison.OrdinalIgnoreCase))
                    {
                        GdiName = d.Name;
                        // a leftover (Desktop restarted): its mode as Windows has it now
                        Mode = win.Current(d.Name);
                    }
                    return true;
                }
            }
        }

        public string GdiName { get; private set; }
        public (int w, int h, int hz) Mode { get; private set; }
        /// <summary>the VDD's mode changes in place: never an old screen beside ours</summary>
        public string OldGdiName => null;
        public void RetireOld(string why) { }

        /// <summary>how long Make waits for Windows to put the attached monitor on (50 + 100 + ... + 1600 ms)</summary>
        public const int OnWaitMs = 3150;

        public string Make(int w, int h, int hz)
        {
            lock (gate)
            {
                var d = Pick(win.Displays(), attachedByUs(), ourDevice());
                if (d == null) return "its monitor is one of your screens";
                var mode = VddModes.Pick(win.Modes(d.Name), w, h, hz);
                // to the right of every screen on: the player's own stay where they are
                int x = win.RightEdge();
                // recorded first: a crash between the attach and the record would leave an attached monitor nobody detaches
                setAttached(true);
                setDevice(d.Name);
                var err = win.Set(d.Name, mode.w, mode.h, mode.hz, x, 0);
                if (err != null) { Undo(d.Name, "attach refused"); return err; }
                string name = null;
                for (int ms = 50; ms <= 1600 && name == null; ms *= 2)
                {
                    win.Sleep(ms);
                    name = win.Displays().FirstOrDefault(v => v.Active && string.Equals(v.Name, d.Name, StringComparison.OrdinalIgnoreCase))?.Name;
                }
                if (name == null)
                {
                    // reviewers 2026-11-17: attached (registry too) but never active, then left attached: what we attached is undone
                    Undo(d.Name, "not on after " + OnWaitMs / 1000.0 + " s");
                    return "it didn't come on";
                }
                GdiName = name;
                Mode = mode;
                Log.I("virtual screen: Virtual Display Driver's " + d.Name + " on at " + mode.w + "x" + mode.h + "@" + mode.hz +
                      (mode != (w, h, hz) ? " (nearest it offers to " + w + "x" + h + "@" + hz + ")" : ""));
                return null;
            }
        }

        /// <summary>a make that didn't work: detached (registry too) and no longer ours</summary>
        void Undo(string dev, string why)
        {
            var err = win.Detach(dev);
            Log.I("virtual screen: Virtual Display Driver's " + dev + " undone (" + why + ")" + (err != null ? ": " + err : ""));
            setAttached(false);
            setDevice(null);
            GdiName = null;
            Mode = (0, 0, 0);
        }

        public string Resize(int w, int h, int hz)
        {
            lock (gate)
            {
                var name = GdiName ?? Ours(win.Displays(), attachedByUs(), ourDevice())?.Name;
                if (name == null) return "not on";
                var mode = VddModes.Pick(win.Modes(name), w, h, hz);
                if (mode == Mode) return null;
                var at = win.Position(name) ?? (win.RightEdge(), 0);
                var err = win.Set(name, mode.w, mode.h, mode.hz, at.x, at.y);
                if (err != null) return err;
                Log.I("virtual screen: Virtual Display Driver's " + name + " to " + mode.w + "x" + mode.h + "@" + mode.hz + " in place" +
                      (mode != (w, h, hz) ? " (nearest it offers to " + w + "x" + h + "@" + hz + ")" : ""));
                Mode = mode;
                return null;
            }
        }

        public string Remove(string why)
        {
            lock (gate)
            {
                // only the monitor we attached: never "whichever VDD monitor is on" (a second one may be the player's)
                var name = GdiName ?? Ours(win.Displays(), attachedByUs(), ourDevice())?.Name;
                if (name == null) { setAttached(false); setDevice(null); return null; }
                var err = win.Detach(name);
                Log.I("virtual screen: Virtual Display Driver's " + name + " off (" + why + ")" + (err != null ? ": " + err : ""));
                if (err != null) return err;          // still ours, still on: ScreenLife pauses, then gives up after a few
                setAttached(false); setDevice(null);
                GdiName = null;
                Mode = (0, 0, 0);
                return null;
            }
        }

        /// <summary>ChangeDisplaySettingsEx / EnumDisplaySettings (user32; no admin)</summary>
        sealed class Native : IVddWindows
        {
            [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
            struct DEVMODE
            {
                [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmDeviceName;
                public short dmSpecVersion, dmDriverVersion, dmSize, dmDriverExtra;
                public int dmFields;
                public int dmPositionX, dmPositionY, dmDisplayOrientation, dmDisplayFixedOutput;
                public short dmColor, dmDuplex, dmYResolution, dmTTOption, dmCollate;
                [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmFormName;
                public short dmLogPixels;
                public int dmBitsPerPel, dmPelsWidth, dmPelsHeight, dmDisplayFlags, dmDisplayFrequency;
                public int dmICMMethod, dmICMIntent, dmMediaType, dmDitherType, dmReserved1, dmReserved2, dmPanningWidth, dmPanningHeight;
            }
            const int DM_POSITION = 0x20, DM_BITSPERPEL = 0x40000, DM_PELSWIDTH = 0x80000, DM_PELSHEIGHT = 0x100000, DM_DISPLAYFREQUENCY = 0x400000;
            const uint CDS_UPDATEREGISTRY = 0x1, CDS_NORESET = 0x10000000;

            [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern bool EnumDisplaySettings(string dev, int mode, ref DEVMODE dm);
            [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int ChangeDisplaySettingsEx(string dev, ref DEVMODE dm, IntPtr hwnd, uint flags, IntPtr param);
            [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int ChangeDisplaySettingsEx(string dev, IntPtr dm, IntPtr hwnd, uint flags, IntPtr param);

            static DEVMODE New() => new DEVMODE { dmSize = (short)Marshal.SizeOf(typeof(DEVMODE)) };

            public List<WinDisplay> Displays() => DsfDesktop.Wow.Displays.WindowsDisplays();
            public void Sleep(int ms) => Thread.Sleep(ms);
            public int RightEdge() => DsfDesktop.Wow.Displays.Monitors().Values.Select(r => r.Right).DefaultIfEmpty(0).Max();
            public (int x, int y)? Position(string dev) { var r = DsfDesktop.Wow.Displays.MonitorRect(dev); return r.HasValue ? (r.Value.Left, r.Value.Top) : ((int, int)?)null; }

            public (int w, int h, int hz) Current(string dev)
            {
                try
                {
                    var dm = New();
                    if (EnumDisplaySettings(dev, -1, ref dm) && dm.dmPelsWidth > 0) return (dm.dmPelsWidth, dm.dmPelsHeight, dm.dmDisplayFrequency);
                }
                catch (Exception) { }
                return (0, 0, 0);
            }

            public List<(int w, int h, int hz)> Modes(string dev)
            {
                var res = new List<(int, int, int)>();
                try
                {
                    for (int i = 0; i < 2000; i++)
                    {
                        var dm = New();
                        if (!EnumDisplaySettings(dev, i, ref dm)) break;
                        res.Add((dm.dmPelsWidth, dm.dmPelsHeight, dm.dmDisplayFrequency));
                    }
                }
                catch (Exception) { }
                return res;
            }

            static string Apply(string dev, DEVMODE dm)
            {
                int r = ChangeDisplaySettingsEx(dev, ref dm, IntPtr.Zero, CDS_UPDATEREGISTRY | CDS_NORESET, IntPtr.Zero);
                if (r != 0) return "display settings refused (" + r + ")";
                r = ChangeDisplaySettingsEx(null, IntPtr.Zero, IntPtr.Zero, 0, IntPtr.Zero);
                return r == 0 ? null : "display settings not applied (" + r + ")";
            }

            public string Set(string dev, int w, int h, int hz, int x, int y)
            {
                var dm = New();
                dm.dmFields = DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT | DM_DISPLAYFREQUENCY | DM_BITSPERPEL;
                dm.dmPelsWidth = w; dm.dmPelsHeight = h; dm.dmDisplayFrequency = hz; dm.dmBitsPerPel = 32;
                dm.dmPositionX = x; dm.dmPositionY = y;
                return Apply(dev, dm);
            }

            /// <summary>off the desktop: size 0 at its place (the documented way to detach a monitor), in the registry too</summary>
            public string Detach(string dev)
            {
                var dm = New();
                dm.dmFields = DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT;
                return Apply(dev, dm);
            }
        }
    }
}
