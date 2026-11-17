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
        /// <summary>makes it at the mode; null = made (GdiName set when Windows named it), else why not</summary>
        string Make(int w, int h, int hz);
        void Remove(string why);
        bool Exists { get; }
        /// <summary>"\\.\DISPLAYn" of our screen, null = none / not named yet</summary>
        string GdiName { get; }
        (int w, int h, int hz) Mode { get; }
    }

    /// <summary>
    /// Our virtual screen's lifecycle: which driver makes it (in order: SudoVDA if installed, then the Virtual Display Driver), the
    /// 60 s grace after the last Thor/stream before it goes, never removed or made mid-stream when that would cut the stream, a
    /// 2-minute pause after a failed make, re-made when the Thor's mode changed while idle. Thread-safe; no Windows calls of its
    /// own (the drivers make them), so the tests drive it with fakes.
    /// </summary>
    public sealed class ScreenLife
    {
        public static readonly TimeSpan DefaultGrace = TimeSpan.FromSeconds(60), RetryAfter = TimeSpan.FromMinutes(2);

        readonly IList<IScreenDriver> drivers;
        readonly object gate = new object();
        readonly TimeSpan grace;
        DateTime lastThorAt = DateTime.MinValue, failedAt = DateTime.MinValue;
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

        /// <summary>a screen a driver still has from before (a VDD monitor we attached and Desktop didn't detach): ours</summary>
        IScreenDriver Adopt()
        {
            if (Current != null && Current.Exists) return Current;
            var was = Current;
            Current = drivers.FirstOrDefault(d => { try { return d.Exists; } catch (Exception) { return false; } });
            // a leftover: made for its own mode as far as we know (unknown = taken as it is)
            if (Current != null && !ReferenceEquals(Current, was)) { var m = Current.Mode; asked = m.w > 0 ? m : ((int, int, int)?)null; }
            if (Current == null) asked = null;
            return Current;
        }

        /// <summary>the mode ours was made for (null = none, or a leftover of unknown mode)</summary>
        public (int w, int h, int hz)? Asked { get { lock (gate) { Adopt(); return asked; } } }

        /// <summary>ours was made but couldn't be used (our Sunshine didn't list it): the same 2-minute pause as a failed make</summary>
        public void Failed(DateTime now) { lock (gate) failedAt = now; }

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
        /// [want] = the Thor's mode.
        /// </summary>
        public (ScreenPlan.Step step, string why) Decide(DateTime now, bool ownOn, bool hasScreen, bool driverUsable, bool thorWants, bool offered,
            (int w, int h, int hz) want, bool streaming)
        {
            lock (gate)
            {
                var ours = Adopt();
                var step = ScreenPlan.Decide(ownOn, hasScreen, driverUsable, ours != null, thorWants, offered);
                switch (step)
                {
                    case ScreenPlan.Step.RemoveOurs:
                        // a removal mid-stream cuts it: after the stream
                        if (streaming) return (ScreenPlan.Step.Keep, "streaming");
                        return (step, !thorWants ? "no Thor for " + (int)grace.TotalSeconds + " s" : "a screen is on");
                    case ScreenPlan.Step.Keep:
                        // a leftover of unknown mode is taken as it is (no remake loop)
                        if (!asked.HasValue) asked = want;
                        if (!streaming && asked.Value != want) return (ScreenPlan.Step.RemoveOurs, "the Thor's mode changed");
                        return (step, null);
                    case ScreenPlan.Step.MakeOurs:
                        // making ours restarts our Sunshine: never under a running stream that already shows a real screen
                        if (streaming && hasScreen) return (ScreenPlan.Step.Fallback, "a stream started first");
                        if (now - failedAt < RetryAfter) return (hasScreen ? ScreenPlan.Step.Fallback : ScreenPlan.Step.Wait, "failed a moment ago");
                        return (step, null);
                    default:
                        return (step, null);
                }
            }
        }

        /// <summary>
        /// Makes ours through the first usable driver that manages it (in order); null = made, Current set and named, else every
        /// driver's reason. A driver that made one but Windows didn't name is undone.
        /// </summary>
        public string Make(int w, int h, int hz, DateTime now)
        {
            lock (gate)
            {
                if (Adopt() != null) return null;
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
                return whys.Count > 0 ? string.Join("; ", whys) : "no virtual screen driver";
            }
        }

        public void Remove(string why)
        {
            lock (gate)
            {
                var c = Adopt();
                if (c == null) return;
                try { c.Remove(why); } catch (Exception e) { Log.I("virtual screen: remove: " + e.Message); }
                Current = null;
                asked = null;
            }
        }
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
    /// </summary>
    public sealed class VddScreen : IScreenDriver
    {
        readonly Func<bool> attachedByUs;
        readonly Action<bool> setAttached;
        readonly Func<string> ourDevice;
        readonly Action<string> setDevice;
        readonly object gate = new object();

        /// <summary>[attachedByUs]/[setAttached] = a lasting flag (settings): we attached it and haven't detached it yet (our install sets
        /// it: its first monitor comes up on by itself); [ourDevice]/[setDevice] = the display name we attached (lasting), so a second
        /// VDD monitor the player has on is never adopted or detached</summary>
        public VddScreen(Func<bool> attachedByUs, Action<bool> setAttached, Func<string> ourDevice = null, Action<string> setDevice = null)
        {
            this.attachedByUs = attachedByUs;
            this.setAttached = setAttached;
            this.ourDevice = ourDevice ?? (() => null);
            this.setDevice = setDevice ?? (_ => { });
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
        /// The active VDD monitor that is OURS: only while we have it attached, the one we named when we did; with no name kept (our
        /// install's monitor, which came up on by itself) the first active one. null = none (never another active VDD monitor).
        /// </summary>
        public static WinDisplay Ours(List<WinDisplay> win, bool attachedByUs, string ourDevice)
        {
            if (!attachedByUs) return null;
            var on = (win ?? new List<WinDisplay>()).Where(d => IsVdd(d) && d.Active).ToList();
            return string.IsNullOrEmpty(ourDevice) ? on.FirstOrDefault()
                : on.FirstOrDefault(d => string.Equals(d.Name, ourDevice, StringComparison.OrdinalIgnoreCase));
        }

        public bool Installed() => Displays.WindowsDisplays().Any(IsVdd);
        public bool Usable() => Pick(Displays.WindowsDisplays(), attachedByUs(), ourDevice()) != null;

        public bool Exists
        {
            get
            {
                // only while we have it attached (our install counts as attached until its first detach): a VDD monitor the player
                // turns on for themselves later is never adopted or detached
                if (!attachedByUs()) return false;
                var all = Displays.WindowsDisplays().Where(IsVdd).ToList();
                if (all.Count == 0) return false;          // not installed (yet): our install's flag waits for it
                var d = Ours(all, true, ourDevice());
                if (d == null) { setAttached(false); setDevice(null); return false; }
                if (!string.Equals(GdiName, d.Name, StringComparison.OrdinalIgnoreCase))
                {
                    GdiName = d.Name;
                    // a leftover (Desktop restarted): its mode as Windows has it now
                    Mode = Native.Current(d.Name);
                    setDevice(d.Name);
                }
                return true;
            }
        }

        public string GdiName { get; private set; }
        public (int w, int h, int hz) Mode { get; private set; }

        public string Make(int w, int h, int hz)
        {
            lock (gate)
            {
                var d = Pick(Displays.WindowsDisplays(), attachedByUs(), ourDevice());
                if (d == null) return "its monitor is one of your screens";
                var mode = VddModes.Pick(Native.Modes(d.Name), w, h, hz);
                // to the right of every screen on: the player's own stay where they are
                int x = Displays.Monitors().Values.Select(r => r.Right).DefaultIfEmpty(0).Max();
                var err = Native.Set(d.Name, mode.w, mode.h, mode.hz, x, 0);
                if (err != null) return err;
                setAttached(true);
                setDevice(d.Name);
                string name = null;
                for (int ms = 50; ms <= 1600 && name == null; ms *= 2)
                {
                    Thread.Sleep(ms);
                    name = Displays.WindowsDisplays().FirstOrDefault(v => v.Active && string.Equals(v.Name, d.Name, StringComparison.OrdinalIgnoreCase))?.Name;
                }
                GdiName = name;
                Mode = mode;
                Log.I("virtual screen: Virtual Display Driver's " + d.Name + " on at " + mode.w + "x" + mode.h + "@" + mode.hz +
                      (mode != (w, h, hz) ? " (nearest it offers to " + w + "x" + h + "@" + hz + ")" : "") + (name == null ? ", not on yet" : ""));
                return null;
            }
        }

        public void Remove(string why)
        {
            lock (gate)
            {
                // only the monitor we attached: never "whichever VDD monitor is on" (a second one may be the player's)
                var name = GdiName ?? Ours(Displays.WindowsDisplays(), attachedByUs(), ourDevice())?.Name;
                if (name == null) { setAttached(false); setDevice(null); return; }
                var err = Native.Detach(name);
                Log.I("virtual screen: Virtual Display Driver's " + name + " off (" + why + ")" + (err != null ? ": " + err : ""));
                if (err == null) { setAttached(false); setDevice(null); }
                GdiName = null;
                Mode = (0, 0, 0);
            }
        }

        /// <summary>ChangeDisplaySettingsEx / EnumDisplaySettings (user32; no admin)</summary>
        static class Native
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

            /// <summary>its mode now (ENUM_CURRENT_SETTINGS); (0,0,0) = unreadable</summary>
            public static (int w, int h, int hz) Current(string dev)
            {
                try
                {
                    var dm = New();
                    if (EnumDisplaySettings(dev, -1, ref dm) && dm.dmPelsWidth > 0) return (dm.dmPelsWidth, dm.dmPelsHeight, dm.dmDisplayFrequency);
                }
                catch (Exception) { }
                return (0, 0, 0);
            }

            public static List<(int w, int h, int hz)> Modes(string dev)
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

            public static string Set(string dev, int w, int h, int hz, int x, int y)
            {
                var dm = New();
                dm.dmFields = DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT | DM_DISPLAYFREQUENCY | DM_BITSPERPEL;
                dm.dmPelsWidth = w; dm.dmPelsHeight = h; dm.dmDisplayFrequency = hz; dm.dmBitsPerPel = 32;
                dm.dmPositionX = x; dm.dmPositionY = y;
                return Apply(dev, dm);
            }

            /// <summary>off the desktop: size 0 at its place (the documented way to detach a monitor)</summary>
            public static string Detach(string dev)
            {
                var dm = New();
                dm.dmFields = DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT;
                return Apply(dev, dm);
            }
        }
    }
}
