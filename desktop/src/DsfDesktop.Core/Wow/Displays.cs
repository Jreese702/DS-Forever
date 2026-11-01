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
                });
            }
            return res;
        }

        public enum Fix { None, RecaptureSunshine, MoveWow }

        /// <summary>
        /// What to do when WoW is on another monitor than the captured one: with no stream running, point Sunshine at WoW's monitor
        /// (a restart of Sunshine, harmless then, and remembered); during a stream (a restart would cut it) move the WoW window to
        /// the captured monitor instead.
        /// </summary>
        public static Fix Decide(string wowDisplay, string capturedDisplay, bool streaming, bool canMapWowDisplay)
        {
            if (wowDisplay == null || capturedDisplay == null) return Fix.None;
            if (string.Equals(wowDisplay, capturedDisplay, StringComparison.OrdinalIgnoreCase)) return Fix.None;
            if (streaming) return Fix.MoveWow;
            return canMapWowDisplay ? Fix.RecaptureSunshine : Fix.None;
        }

        /// <summary>the display Sunshine captures: the one whose id is configured, else the primary</summary>
        public static string Captured(List<SunshineDisplay> list, string configuredId)
        {
            var d = (configuredId != null ? list.FirstOrDefault(x => string.Equals(x.DeviceId, configuredId, StringComparison.OrdinalIgnoreCase)) : null)
                    ?? list.FirstOrDefault(x => x.Primary);
            return d?.DisplayName;
        }

        // ---- Win32: the monitor a window is on, monitor rectangles, moving a window

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
