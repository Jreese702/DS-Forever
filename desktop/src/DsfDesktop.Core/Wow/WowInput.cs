using System;
using System.Runtime.InteropServices;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// Issues #304 / #357 ("cannot use controls for streaming on pc"; the user's pick, 2026-11-16: "Desktop warns (window notice + a
    /// reason to the Thor in the welcome/status) when WoW's client area doesn't fill the captured monitor, and when WoW runs elevated
    /// (administrator) so Sunshine's input is blocked (UIPI)"). The companion's clicks are shares of the whole stream (the Thor's
    /// StreamKeys.tap), so a WoW window smaller than the streamed monitor takes them in the wrong place; and Windows drops the mouse and
    /// keys Sunshine sends (Desktop starts it, so it runs at Desktop's integrity level) to a window above that level, while the virtual
    /// pad still works. Read-only: the window's client rectangle and the process token's integrity level; nothing is changed or sent.
    /// </summary>
    public static class WowInput
    {
        /// <summary>the words (the welcome's and the wow message's wow.input; "" = fine, or not known)</summary>
        public const string Elevated = "elevated", NotFilling = "not_filling";
        /// <summary>a client area this many pixels short of a monitor edge still fills it</summary>
        public const int Slack = 2;
        /// <summary>a new word holds this long before it is said (a resolution change at a stream's start, a window being moved)</summary>
        public static readonly TimeSpan Hold = TimeSpan.FromSeconds(4);

        /// <summary>
        /// [wowLevel] / [ownLevel] = the integrity levels (0x2000 medium, 0x3000 high = elevated; null = unreadable); [client] = WoW's
        /// client area on the screen (null = no window or minimised); [monitor] = the captured monitor (null = not known)
        /// </summary>
        public static string Word(int? wowLevel, int? ownLevel, Displays.RECT? client, Displays.RECT? monitor)
        {
            if (wowLevel.HasValue && ownLevel.HasValue && wowLevel.Value > ownLevel.Value) return Elevated;
            if (client.HasValue && monitor.HasValue && client.Value.W > 0 && client.Value.H > 0 && monitor.Value.W > 0 && monitor.Value.H > 0
                && !Fills(client.Value, monitor.Value)) return NotFilling;
            return "";
        }

        public static bool Fills(Displays.RECT c, Displays.RECT m) =>
            c.Left <= m.Left + Slack && c.Top <= m.Top + Slack && c.Right >= m.Right - Slack && c.Bottom >= m.Bottom - Slack;

        /// <summary>the main window's notice (null = none)</summary>
        public static string Notice(string word) =>
            word == Elevated ? "WoW runs as administrator, so the Thor's taps and keys can't reach it. Start Battle.net and WoW without 'Run as administrator'."
            : word == NotFilling ? "WoW doesn't fill the streamed monitor, so the Thor's taps miss. Set WoW's display mode to fullscreen."
            : null;

        /// <summary>the Thor's line (the welcome's wow.input_why and a status; null = none)</summary>
        public static string ThorWhy(string word) =>
            word == Elevated ? "WoW runs as administrator: taps can't reach it"
            : word == NotFilling ? "WoW doesn't fill the streamed screen: taps miss"
            : null;

        /// <summary>the status after a problem cleared</summary>
        public const string ThorFine = "taps reach WoW again";

        /// <summary>a word said only once it held for [Hold]; "fine" is said at once</summary>
        public sealed class Steady
        {
            string seen = "", said = "";
            DateTime since;

            public string Step(string word, DateTime now)
            {
                word = word ?? "";
                if (word != seen) { seen = word; since = now; }
                if (word.Length == 0 || now - since >= Hold) said = word;
                return said;
            }
        }

        // ---- Win32 (read-only)

        /// <summary>the window's client area in screen pixels; null = no window or minimised</summary>
        public static Displays.RECT? ClientOnScreen(IntPtr hwnd)
        {
            if (hwnd == IntPtr.Zero || IsIconic(hwnd) || !GetClientRect(hwnd, out var r)) return null;
            var p = new POINT();
            if (!ClientToScreen(hwnd, ref p)) return null;
            return new Displays.RECT { Left = p.X, Top = p.Y, Right = p.X + r.W, Bottom = p.Y + r.H };
        }

        /// <summary>a process's integrity level (its token's mandatory label RID); null = it could not be read</summary>
        public static int? IntegrityOf(int pid)
        {
            var h = OpenProcess(0x1000 /* PROCESS_QUERY_LIMITED_INFORMATION */, false, pid);
            if (h == IntPtr.Zero) return null;
            try { return LevelOfProcess(h); }
            finally { CloseHandle(h); }
        }

        /// <summary>Desktop's own integrity level (Sunshine, its child, runs at it); null = unreadable</summary>
        public static int? OwnIntegrity() => LevelOfProcess(GetCurrentProcess());

        static int? LevelOfProcess(IntPtr process)
        {
            if (!OpenProcessToken(process, 0x0008 /* TOKEN_QUERY */, out var token)) return null;
            try
            {
                GetTokenInformation(token, 25 /* TokenIntegrityLevel */, IntPtr.Zero, 0, out int len);
                if (len <= 0) return null;
                var buf = Marshal.AllocHGlobal(len);
                try
                {
                    if (!GetTokenInformation(token, 25, buf, len, out len)) return null;
                    var sid = Marshal.ReadIntPtr(buf);                 // TOKEN_MANDATORY_LABEL.Label.Sid
                    int n = Marshal.ReadByte(GetSidSubAuthorityCount(sid));
                    return n < 1 ? (int?)null : Marshal.ReadInt32(GetSidSubAuthority(sid, (uint)(n - 1)));
                }
                finally { Marshal.FreeHGlobal(buf); }
            }
            finally { CloseHandle(token); }
        }

        [StructLayout(LayoutKind.Sequential)] struct POINT { public int X, Y; }
        [DllImport("user32.dll")] static extern bool GetClientRect(IntPtr h, out Displays.RECT r);
        [DllImport("user32.dll")] static extern bool ClientToScreen(IntPtr h, ref POINT p);
        [DllImport("user32.dll")] static extern bool IsIconic(IntPtr h);
        [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
        [DllImport("kernel32.dll")] static extern IntPtr GetCurrentProcess();
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
        [DllImport("advapi32.dll", SetLastError = true)] static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
        [DllImport("advapi32.dll", SetLastError = true)] static extern bool GetTokenInformation(IntPtr token, int cls, IntPtr info, int len, out int retLen);
        [DllImport("advapi32.dll")] static extern IntPtr GetSidSubAuthorityCount(IntPtr sid);
        [DllImport("advapi32.dll")] static extern IntPtr GetSidSubAuthority(IntPtr sid, uint index);
    }
}
