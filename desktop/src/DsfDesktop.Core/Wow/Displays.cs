using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;

namespace DsfDesktop.Wow
{
    public sealed class SunshineDisplay
    {
        public string DeviceId;     // "{632454dc-...}": what Sunshine's output_name takes
        public string DisplayName;  // "\\.\DISPLAY6": what Windows calls the monitor
        public string Friendly;
        public bool Primary;
        /// <summary>Sunshine printed its "info" (origin, primary): the display is part of the desktop</summary>
        public bool Active;
        /// <summary>a virtual display (Apollo's SudoVDA, another IddCx driver): from Windows' adapter, else the friendly name</summary>
        public bool Virtual;
    }

    /// <summary>what a display is: a real monitor, a virtual one that a streaming host makes per session and removes again
    /// (SudoVDA, Parsec), or a virtual one that stays (Virtual Display Driver, usbmmidd)</summary>
    public enum DisplayKind { Real, VirtualSession, VirtualPersistent }

    /// <summary>one Windows display (EnumDisplayDevices): "\\.\DISPLAYn", its adapter's name and hardware id</summary>
    public sealed class WinDisplay
    {
        public string Name, Adapter, AdapterId;
        public bool Active, Primary;
        public DisplayKind Kind;
        public bool Virtual => Kind != DisplayKind.Real;
        public override string ToString() => Name + " " + Adapter + (Active ? " (on" + (Primary ? ", primary" : "") + ")" : " (off)");
    }

    /// <summary>
    /// Which monitor Sunshine captures, and the monitor WoW is on. Sunshine captures the PRIMARY display unless output_name names
    /// a display device id; it prints every display (device_id, display_name, primary) at start ("Currently available display
    /// devices:"), which is how a Windows monitor name is turned into the id it wants.
    /// </summary>
    public static class Displays
    {
        /// <summary>the display list from the LAST "Currently available display devices:" block of a Sunshine log</summary>
        public static List<SunshineDisplay> ParseSunshineLog(string log)
        {
            var res = new List<SunshineDisplay>();
            if (string.IsNullOrEmpty(log)) return res;
            const string marker = "Currently available display devices:";
            int at = log.LastIndexOf(marker, StringComparison.Ordinal);
            if (at < 0) return res;
            var lines = log.Substring(at + marker.Length).Replace("\r", "").Split('\n').Skip(1);
            // the JSON runs until the next log entry ("[2026-10-23 13:40:39.300]: ...")
            var json = string.Join("\n", lines.TakeWhile(l => !System.Text.RegularExpressions.Regex.IsMatch(l, @"^\[\d{4}-\d\d-\d\d ")));
            if (!(Json.Read(json.Trim()) is object[] arr)) return res;
            foreach (var d in arr.OfType<Dictionary<string, object>>())
            {
                var info = d.TryGetValue("info", out var i) ? i as Dictionary<string, object> : null;
                res.Add(new SunshineDisplay
                {
                    DeviceId = d.Str("device_id"),
                    DisplayName = d.Str("display_name"),
                    Friendly = d.Str("friendly_name"),
                    Primary = info != null && info.Bool("primary"),
                    Active = info != null,
                    Virtual = KindOf(d.Str("friendly_name"), null) != DisplayKind.Real,
                });
            }
            return res;
        }

        // ---- virtual displays (F3, 2026-11-17: Apollo's per-session SudoVDA screen was the only screen on a headless PC; ours
        //      followed WoW onto it and lost the picture when Apollo's client left)

        static readonly string[] SessionNames = { "SudoVDA", "SudoMaker", "Parsec" };
        static readonly string[] PersistentNames = { "Virtual Display Driver", "MttVDD", "IddSampleDriver", "usbmmidd", "Amyuni", "Virtual Display" };

        /// <summary>
        /// A display adapter's kind from its name and hardware id: a session-made virtual one (SudoVDA, Parsec), a lasting virtual one
        /// (Virtual Display Driver, usbmmidd, any other software adapter enumerated under ROOT\), else a real monitor (PCI / USB:
        /// a DisplayLink dock is real).
        /// </summary>
        public static DisplayKind KindOf(string adapter, string adapterId)
        {
            var a = adapter ?? "";
            var id = adapterId ?? "";
            if (SessionNames.Any(n => a.IndexOf(n, StringComparison.OrdinalIgnoreCase) >= 0 || id.IndexOf(n, StringComparison.OrdinalIgnoreCase) >= 0))
                return DisplayKind.VirtualSession;
            if (PersistentNames.Any(n => a.IndexOf(n, StringComparison.OrdinalIgnoreCase) >= 0 || id.IndexOf(n, StringComparison.OrdinalIgnoreCase) >= 0))
                return DisplayKind.VirtualPersistent;
            if (id.StartsWith(@"ROOT\", StringComparison.OrdinalIgnoreCase)) return DisplayKind.VirtualPersistent;
            return DisplayKind.Real;
        }

        /// <summary>Sunshine's list marked virtual from Windows' own (by "\\.\DISPLAYn"; Apollo names its screen after the client,
        /// so the friendly name alone doesn't tell)</summary>
        public static void MarkVirtual(List<SunshineDisplay> sun, List<WinDisplay> win)
        {
            foreach (var d in sun ?? new List<SunshineDisplay>())
            {
                var w = win?.FirstOrDefault(x => string.Equals(x.Name, d.DisplayName, StringComparison.OrdinalIgnoreCase));
                if (w != null && w.Virtual) d.Virtual = true;
            }
        }

        /// <summary>a screen to stream exists: a real monitor on, or a lasting virtual one on (not a session's, which goes away)</summary>
        public static bool HasScreen(List<WinDisplay> win) => HasScreen(win, null);

        /// <summary>the same, not counting OUR virtual screen ([ours] = its GDI name; the Virtual Display Driver's is a lasting kind)</summary>
        public static bool HasScreen(List<WinDisplay> win, string ours)
            => (win ?? new List<WinDisplay>()).Any(d => d.Active && d.Kind != DisplayKind.VirtualSession
                                                       && !(ours != null && string.Equals(d.Name, ours, StringComparison.OrdinalIgnoreCase)));

        public static bool HasRealScreen(List<WinDisplay> win) => (win ?? new List<WinDisplay>()).Any(d => d.Active && d.Kind == DisplayKind.Real);

        /// <summary>what changed when the display list changes: the active displays and their kinds, in one comparable line</summary>
        public static string TopologyKey(List<WinDisplay> win)
            => string.Join(";", (win ?? new List<WinDisplay>()).Where(d => d.Active).OrderBy(d => d.Name, StringComparer.OrdinalIgnoreCase)
                .Select(d => d.Name + (d.Primary ? "*" : "") + ":" + d.Kind));

        /// <summary>a display id may be remembered in settings only when it is a real monitor (a virtual one may go away)</summary>
        public static bool MayRemember(SunshineDisplay d) => d != null && d.DeviceId != null && !d.Virtual;

        public enum Change { None, Restart, ClearAndRestart }

        /// <summary>
        /// After Windows' displays changed (or on the 2 s check): a remembered display that has gone is cleared; our Sunshine is
        /// restarted (it reads the displays at its start) when what it captures is gone or a screen came back while it had none.
        /// Never during a stream (a restart cuts it). [output] = the display id written as output_name now (remembered or the
        /// session's own virtual screen), [remembered] = the one in settings.
        /// </summary>
        public static Change OnChange(List<SunshineDisplay> sun, List<WinDisplay> win, string remembered, string output, bool streaming)
        {
            if (streaming || win == null || win.Count == 0) return Change.None;
            bool ActiveNow(string displayName) => displayName != null && win.Any(w => w.Active && string.Equals(w.Name, displayName, StringComparison.OrdinalIgnoreCase));
            if (remembered != null)
            {
                var r = sun?.FirstOrDefault(x => string.Equals(x.DeviceId, remembered, StringComparison.OrdinalIgnoreCase));
                if (r == null || r.Virtual || !ActiveNow(r.DisplayName)) return Change.ClearAndRestart;
            }
            if (sun == null || sun.Count == 0) return Change.None;      // Sunshine hasn't listed yet
            var captured = Captured(sun, output);
            if (captured == null || !ActiveNow(captured)) return Change.Restart;
            // a screen is on that Sunshine didn't list at its start
            if (win.Any(w => w.Active && !sun.Any(x => x.Active && string.Equals(x.DisplayName, w.Name, StringComparison.OrdinalIgnoreCase)))
                && !sun.Any(x => x.Active && !x.Virtual))
                return Change.Restart;
            return Change.None;
        }

        public enum Fix { None, RecaptureSunshine, MoveWow }

        /// <summary>
        /// What to do when WoW is on another monitor than the captured one: with no stream running, point Sunshine at WoW's monitor
        /// (a restart of Sunshine, harmless then, and remembered); during a stream (a restart would cut it) move the WoW window to
        /// the captured monitor instead. [ownScreen] = our own virtual screen is the session's (Part 2): WoW always comes to it,
        /// off the player's desktop; Sunshine never follows WoW back.
        /// </summary>
        public static Fix Decide(string wowDisplay, string capturedDisplay, bool streaming, bool canMapWowDisplay, bool wowOnVirtual = false, bool ownScreen = false)
        {
            if (wowDisplay == null || capturedDisplay == null) return Fix.None;
            if (string.Equals(wowDisplay, capturedDisplay, StringComparison.OrdinalIgnoreCase)) return Fix.None;
            if (streaming || ownScreen) return Fix.MoveWow;
            // never follow WoW onto a virtual display (another host's session screen goes away with its client): WoW comes to ours
            if (wowOnVirtual) return Fix.MoveWow;
            return canMapWowDisplay ? Fix.RecaptureSunshine : Fix.None;
        }

        /// <summary>the display Sunshine captures: the one whose id is configured, else the primary</summary>
        public static string Captured(List<SunshineDisplay> list, string configuredId)
        {
            var d = (configuredId != null ? list.FirstOrDefault(x => string.Equals(x.DeviceId, configuredId, StringComparison.OrdinalIgnoreCase)) : null)
                    ?? list.FirstOrDefault(x => x.Primary);
            return d?.DisplayName;
        }

        // ---- Win32: Windows' display list, the monitor a window is on, monitor rectangles, moving a window

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct DISPLAY_DEVICE
        {
            public int cb;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string DeviceName;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceString;
            public int StateFlags;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceID;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceKey;
        }
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern bool EnumDisplayDevices(string device, uint i, ref DISPLAY_DEVICE dd, uint flags);

        /// <summary>every display output Windows knows (read-only), with its adapter and kind; empty if it can't be read</summary>
        public static List<WinDisplay> WindowsDisplays()
        {
            var res = new List<WinDisplay>();
            try
            {
                for (uint i = 0; i < 64; i++)
                {
                    var dd = new DISPLAY_DEVICE { cb = Marshal.SizeOf(typeof(DISPLAY_DEVICE)) };
                    if (!EnumDisplayDevices(null, i, ref dd, 0)) break;
                    if ((dd.StateFlags & 0x8 /* MIRRORING_DRIVER */) != 0) continue;
                    res.Add(new WinDisplay
                    {
                        Name = dd.DeviceName, Adapter = dd.DeviceString, AdapterId = dd.DeviceID,
                        Active = (dd.StateFlags & 0x1 /* ATTACHED_TO_DESKTOP */) != 0, Primary = (dd.StateFlags & 0x4 /* PRIMARY_DEVICE */) != 0,
                        Kind = KindOf(dd.DeviceString, dd.DeviceID),
                    });
                }
            }
            catch (Exception) { }
            return res;
        }

        [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; public int W => Right - Left; public int H => Bottom - Top; }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct MONITORINFOEX
        {
            public int cbSize;
            public RECT rcMonitor, rcWork;
            public uint dwFlags;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string szDevice;
        }

        delegate bool MonitorEnumProc(IntPtr hMonitor, IntPtr hdc, ref RECT rc, IntPtr data);
        [DllImport("user32.dll")] static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint flags);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern bool GetMonitorInfo(IntPtr hMonitor, ref MONITORINFOEX info);
        [DllImport("user32.dll")] static extern bool EnumDisplayMonitors(IntPtr hdc, IntPtr clip, MonitorEnumProc proc, IntPtr data);
        [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h, out RECT r);
        [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
        [DllImport("user32.dll")] static extern bool IsZoomed(IntPtr h);
        [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr h, int cmd);

        static MONITORINFOEX Info(IntPtr mon)
        {
            var mi = new MONITORINFOEX { cbSize = Marshal.SizeOf(typeof(MONITORINFOEX)) };
            GetMonitorInfo(mon, ref mi);
            return mi;
        }

        /// <summary>"\\.\DISPLAYn" of the monitor most of the window is on</summary>
        public static string MonitorOf(IntPtr hwnd)
        {
            if (hwnd == IntPtr.Zero) return null;
            var mon = MonitorFromWindow(hwnd, 2 /* MONITOR_DEFAULTTONEAREST */);
            return mon == IntPtr.Zero ? null : Info(mon).szDevice;
        }

        public static Dictionary<string, RECT> Monitors()
        {
            var res = new Dictionary<string, RECT>(StringComparer.OrdinalIgnoreCase);
            EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero, (IntPtr m, IntPtr dc, ref RECT r, IntPtr d) =>
            {
                var mi = Info(m);
                res[mi.szDevice] = mi.rcMonitor;
                return true;
            }, IntPtr.Zero);
            return res;
        }

        /// <summary>
        /// The window's rectangle moved from one monitor to another: a window filling its monitor (WoW's windowed fullscreen) fills
        /// the target; any other keeps its offset and size, clamped inside the target.
        /// </summary>
        public static RECT Translate(RECT win, RECT from, RECT to)
        {
            bool fills = win.Left <= from.Left && win.Top <= from.Top && win.Right >= from.Right && win.Bottom >= from.Bottom;
            if (fills) return to;
            int w = Math.Min(win.W, to.W), h = Math.Min(win.H, to.H);
            int x = to.Left + Math.Max(0, Math.Min(win.Left - from.Left, to.W - w));
            int y = to.Top + Math.Max(0, Math.Min(win.Top - from.Top, to.H - h));
            return new RECT { Left = x, Top = y, Right = x + w, Bottom = y + h };
        }

        /// <summary>
        /// The window's place on the captured monitor as the Thor's "show" word: "x,y,w,h", each a share (0..1) of the monitor, with a
        /// small margin round it; null = not on that monitor. The Thor zooms the stream onto it and blacks out the rest (the user,
        /// 2026-10-30: "I kind of expected a simple sign in window for bnet to appear on the top screen. I dont really want to see my
        /// desktop screen for any reason").
        /// </summary>
        public static string ShowRect(RECT win, RECT mon)
        {
            if (mon.W <= 0 || mon.H <= 0 || win.W <= 0 || win.H <= 0) return null;
            if (win.Right <= mon.Left || win.Left >= mon.Right || win.Bottom <= mon.Top || win.Top >= mon.Bottom) return null;
            double m = 6;
            double l = Math.Max(mon.Left, win.Left - m), t = Math.Max(mon.Top, win.Top - m);
            double r = Math.Min(mon.Right, win.Right + m), b = Math.Min(mon.Bottom, win.Bottom + m);
            var ci = System.Globalization.CultureInfo.InvariantCulture;
            return string.Join(",", new[] { (l - mon.Left) / mon.W, (t - mon.Top) / mon.H, (r - l) / mon.W, (b - t) / mon.H }
                .Select(v => v.ToString("0.0000", ci)));
        }

        /// <summary>the window's VISIBLE rectangle (without the invisible resize borders round it), null = none</summary>
        public static RECT? Visible(IntPtr hwnd)
        {
            if (hwnd == IntPtr.Zero) return null;
            if (DwmGetWindowAttribute(hwnd, 9 /* DWMWA_EXTENDED_FRAME_BOUNDS */, out RECT r, Marshal.SizeOf(typeof(RECT))) == 0 && r.W > 0) return r;
            return GetWindowRect(hwnd, out r) ? r : (RECT?)null;
        }

        /// <summary>the window's whole rectangle (what PrintWindow draws)</summary>
        public static bool GetRect(IntPtr hwnd, out RECT r) => GetWindowRect(hwnd, out r);

        public static RECT? MonitorRect(string display) => display != null && Monitors().TryGetValue(display, out var r) ? r : (RECT?)null;

        [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr h, int attr, out RECT r, int size);

        /// <summary>moves the window to the named monitor; null = done, else why not</summary>
        public static string MoveTo(IntPtr hwnd, string display)
        {
            var mons = Monitors();
            var cur = MonitorOf(hwnd);
            if (cur == null || !mons.ContainsKey(cur) || display == null || !mons.ContainsKey(display)) return "monitor not found";
            bool max = IsZoomed(hwnd);
            if (max) ShowWindow(hwnd, 9 /* SW_RESTORE */);
            GetWindowRect(hwnd, out var r);
            var t = Translate(r, mons[cur], mons[display]);
            SetWindowPos(hwnd, IntPtr.Zero, t.Left, t.Top, t.W, t.H, 0x0004 /* NOZORDER */ | 0x0010 /* NOACTIVATE */);
            if (max) ShowWindow(hwnd, 3 /* SW_MAXIMIZE */);
            return string.Equals(MonitorOf(hwnd), display, StringComparison.OrdinalIgnoreCase) ? null : "the window didn't move";
        }
    }
}
