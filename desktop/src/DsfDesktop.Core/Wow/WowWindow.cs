using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// "Focus WoW": Sunshine streams the real desktop, so the game's window must be in front when a stream starts. Restores it if
    /// minimised and brings it to the foreground. Never synthesises keys (the key-chord rule: no Alt tricks); the foreground lock
    /// is passed with AttachThreadInput instead.
    /// </summary>
    public static class WowWindow
    {
        static readonly string[] Names = { "Wow", "WowB", "WowT", "WowClassic", "WowClassicB", "WowClassicT" };

        public static Process Find(string preferDir = null)
        {
            var all = Names.SelectMany(n => { try { return Process.GetProcessesByName(n); } catch (Exception) { return new Process[0]; } })
                           .Where(p => { try { return p.MainWindowHandle != IntPtr.Zero; } catch (Exception) { return false; } })
                           .ToList();
            if (all.Count == 0) return null;
            if (preferDir != null)
            {
                var hit = all.FirstOrDefault(p =>
                {
                    try { return string.Equals(Path.GetDirectoryName(p.MainModule.FileName), preferDir.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase); }
                    catch (Exception) { return false; }
                });
                if (hit != null) return hit;
            }
            return all[0];
        }

        public static bool IsRunning() => Find() != null;

        /// <summary>a WoW client process, with or without its window yet (the one in [preferDir] first); null = none</summary>
        public static Process FindAny(string preferDir = null)
        {
            var all = Names.SelectMany(n => { try { return Process.GetProcessesByName(n); } catch (Exception) { return new Process[0]; } }).ToList();
            if (all.Count == 0) return null;
            if (preferDir != null)
            {
                var hit = all.FirstOrDefault(p =>
                {
                    try { return string.Equals(Path.GetDirectoryName(p.MainModule.FileName), preferDir.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase); }
                    catch (Exception) { return false; }
                });
                if (hit != null) return hit;
            }
            return all[0];
        }

        /// <summary>a WoW process exists (it may not have a window yet while it loads)</summary>
        public static bool ProcessRunning()
        {
            foreach (var n in Names)
            {
                try
                {
                    var ps = Process.GetProcessesByName(n);
                    bool any = ps.Length > 0;
                    foreach (var x in ps) x.Dispose();
                    if (any) return true;
                }
                catch (Exception) { }
            }
            return false;
        }

        /// <summary>is a WoW client the foreground window (protocol status "focus")</summary>
        public static bool IsForeground()
        {
            try
            {
                var fg = GetForegroundWindow();
                if (fg == IntPtr.Zero) return false;
                GetWindowThreadProcessId(fg, out uint pid);
                using (var p = Process.GetProcessById((int)pid)) return Names.Contains(p.ProcessName, StringComparer.OrdinalIgnoreCase);
            }
            catch (Exception) { return false; }
        }

        /// <summary>null = WoW is now in front, else why not</summary>
        public static string Focus(string preferDir = null)
        {
            var p = Find(preferDir);
            if (p == null) return "WoW isn't running";
            return FocusHandle(p.MainWindowHandle);
        }

        /// <summary>any top-level window to the front (WoW's, or Battle.net's sign-in window); null = it is in front, else why not</summary>
        public static string FocusHandle(IntPtr h)
        {
            if (h == IntPtr.Zero) return "no window";
            if (IsIconic(h)) ShowWindow(h, 9 /* SW_RESTORE */);
            if (SetForegroundWindow(h)) return null;
            var fg = GetForegroundWindow();
            uint fgThread = GetWindowThreadProcessId(fg, out _);
            uint me = GetCurrentThreadId();
            bool attached = fgThread != 0 && fgThread != me && AttachThreadInput(me, fgThread, true);
            try
            {
                BringWindowToTop(h);
                ShowWindow(h, 5 /* SW_SHOW */);
                SetForegroundWindow(h);
            }
            finally { if (attached) AttachThreadInput(me, fgThread, false); }
            return GetForegroundWindow() == h ? null : "Windows kept another window in front";
        }

        /// <summary>seconds since anyone touched the PC's own keyboard or mouse</summary>
        public static double PcIdleSeconds()
        {
            var i = new LASTINPUTINFO { cbSize = (uint)Marshal.SizeOf(typeof(LASTINPUTINFO)) };
            return GetLastInputInfo(ref i) ? unchecked((uint)Environment.TickCount - i.dwTime) / 1000.0 : 0;
        }

        [StructLayout(LayoutKind.Sequential)] struct LASTINPUTINFO { public uint cbSize; public uint dwTime; }
        [DllImport("user32.dll")] static extern bool GetLastInputInfo(ref LASTINPUTINFO i);

        public static bool IsForegroundHandle(IntPtr h) => h != IntPtr.Zero && GetForegroundWindow() == h;

        [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr h);
        [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr h, int cmd);
        [DllImport("user32.dll")] static extern bool IsIconic(IntPtr h);
        [DllImport("user32.dll")] static extern bool BringWindowToTop(IntPtr h);
        [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
        [DllImport("user32.dll")] static extern bool AttachThreadInput(uint a, uint b, bool attach);
        [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
    }

    /// <summary>Start with Windows: one value under HKCU\...\Run (per user, no admin), only when the user ticks the box</summary>
    public static class AutoStart
    {
        const string Key = @"Software\Microsoft\Windows\CurrentVersion\Run";
        const string Value = "DS Forever Desktop";

        public static bool IsOn()
        {
            using (var k = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(Key)) return k?.GetValue(Value) != null;
        }

        public static void Set(bool on, string exe)
        {
            using (var k = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(Key))
            {
                if (on) k.SetValue(Value, "\"" + exe + "\" --tray");
                else if (k.GetValue(Value) != null) k.DeleteValue(Value);
            }
        }
    }
}
