using System;
using System.Runtime.InteropServices;
using System.Threading;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// THE PC'S OWN USER (the user, 2026-11-19: "do the quiet clicks keep the real cursor free? sometimes I try typing over here in claude
    /// while you are testing and it just takes the cursor straight back to my game"; Desktop 0.2.43). Desktop never pulls WoW to the front
    /// while someone is using the PC itself (and pad clicks are only ever posted: never a real click or a cursor move, at any time): for
    /// <see cref="QuietS"/> after the last PHYSICAL key
    /// or mouse input, and never over a window that isn't WoW in that time. Physical vs injected: Raw Input (RIDEV_INPUTSINK, a
    /// message-only window of ours) names the device of each input; SendInput's - Sunshine's for the stream - has
    /// none (hDevice == NULL), so only a real keyboard or mouse counts. Passive: it reads timestamps only, never what was typed, and
    /// adds no hook to anyone's input. The decision is <see cref="PcUseGate"/> (pure, tested).
    /// </summary>
    public sealed class PcUseGate
    {
        public const double QuietS = 10.0;
        long lastPhysicalTicks = DateTime.MinValue.Ticks;

        /// <summary>one input at [at]: [injected] (no device: SendInput, Sunshine) never counts</summary>
        public void Note(bool injected, DateTime at)
        {
            if (injected) return;
            Interlocked.Exchange(ref lastPhysicalTicks, at.Ticks);
        }

        public DateTime LastPhysical => new DateTime(Interlocked.Read(ref lastPhysicalTicks), DateTimeKind.Utc);

        /// <summary>someone used the PC's own keyboard or mouse within <see cref="QuietS"/> of [now]</summary>
        public bool InUse(DateTime now)
        {
            var t = Interlocked.Read(ref lastPhysicalTicks);
            if (t == DateTime.MinValue.Ticks) return false;
            var s = (now - new DateTime(t, DateTimeKind.Utc)).TotalSeconds;
            return s >= 0 && s < QuietS;
        }

        /// <summary>
        /// may Desktop bring WoW to the front now: always when nobody is using the PC, or when WoW is in front already; never over
        /// another window while the PC is in use
        /// </summary>
        public bool MayFocus(DateTime now, bool wowInFront) => wowInFront || !InUse(now);
    }

    /// <summary>the real watcher: Raw Input on a message-only window of its own thread (started once; never in the tests)</summary>
    public static class PcUse
    {
        public static readonly PcUseGate Gate = new PcUseGate();
        public const string InUseWhy = "PC in use";
        static int started;
        static WndProc proc;

        public static bool InUse() => Gate.InUse(DateTime.UtcNow);

        public static void Start()
        {
            if (Interlocked.Exchange(ref started, 1) != 0) return;
            var t = new Thread(Run) { IsBackground = true, Name = "pc-use" };
            t.Start();
        }

        static void Run()
        {
            try
            {
                proc = Proc;
                var wc = new WNDCLASS { lpfnWndProc = proc, lpszClassName = "DsfPcUse", hInstance = GetModuleHandle(null) };
                RegisterClass(ref wc);
                var h = CreateWindowEx(0, "DsfPcUse", "", 0, 0, 0, 0, 0, (IntPtr)(-3) /* HWND_MESSAGE */, IntPtr.Zero, wc.hInstance, IntPtr.Zero);
                if (h == IntPtr.Zero) { Log.I("pc use: no watcher (window)"); return; }
                var devs = new[]
                {
                    new RAWINPUTDEVICE { usUsagePage = 1, usUsage = 2, dwFlags = 0x100 /* RIDEV_INPUTSINK */, hwndTarget = h },   // mouse
                    new RAWINPUTDEVICE { usUsagePage = 1, usUsage = 6, dwFlags = 0x100, hwndTarget = h },                           // keyboard
                };
                if (!RegisterRawInputDevices(devs, 2, Marshal.SizeOf(typeof(RAWINPUTDEVICE)))) { Log.I("pc use: no watcher (raw input)"); return; }
                Log.I("pc use: watching the PC's own keyboard and mouse (times only)");
                while (GetMessage(out var m, IntPtr.Zero, 0, 0) > 0) { TranslateMessage(ref m); DispatchMessage(ref m); }
            }
            catch (Exception e) { Log.I("pc use: no watcher (" + e.GetType().Name + ")"); }
        }

        static IntPtr Proc(IntPtr h, uint msg, IntPtr w, IntPtr l)
        {
            if (msg == 0x00FF /* WM_INPUT */)
            {
                int size = Marshal.SizeOf(typeof(RAWINPUTHEADER));
                if (GetRawInputData(l, 0x10000005 /* RID_HEADER */, out var hdr, ref size, Marshal.SizeOf(typeof(RAWINPUTHEADER))) > 0)
                    Gate.Note(hdr.hDevice == IntPtr.Zero, DateTime.UtcNow);
            }
            return DefWindowProc(h, msg, w, l);
        }

        delegate IntPtr WndProc(IntPtr h, uint msg, IntPtr w, IntPtr l);
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct WNDCLASS
        {
            public uint style; public WndProc lpfnWndProc; public int cbClsExtra, cbWndExtra; public IntPtr hInstance, hIcon, hCursor, hbrBackground;
            public string lpszMenuName, lpszClassName;
        }
        [StructLayout(LayoutKind.Sequential)] struct RAWINPUTDEVICE { public ushort usUsagePage, usUsage; public uint dwFlags; public IntPtr hwndTarget; }
        [StructLayout(LayoutKind.Sequential)] struct RAWINPUTHEADER { public uint dwType, dwSize; public IntPtr hDevice, wParam; }
        [StructLayout(LayoutKind.Sequential)] struct POINT { public int X, Y; }
        [StructLayout(LayoutKind.Sequential)] struct MSG { public IntPtr hwnd; public uint message; public IntPtr wParam, lParam; public uint time; public POINT pt; }

        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern ushort RegisterClass(ref WNDCLASS wc);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern IntPtr CreateWindowEx(int ex, string cls, string name, int style, int x, int y, int w, int h, IntPtr parent, IntPtr menu, IntPtr inst, IntPtr param);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern IntPtr DefWindowProc(IntPtr h, uint msg, IntPtr w, IntPtr l);
        [DllImport("user32.dll")] static extern bool RegisterRawInputDevices(RAWINPUTDEVICE[] devs, uint n, int size);
        [DllImport("user32.dll")] static extern int GetRawInputData(IntPtr raw, uint cmd, out RAWINPUTHEADER data, ref int size, int headerSize);
        [DllImport("user32.dll")] static extern int GetMessage(out MSG m, IntPtr h, uint min, uint max);
        [DllImport("user32.dll")] static extern bool TranslateMessage(ref MSG m);
        [DllImport("user32.dll")] static extern IntPtr DispatchMessage(ref MSG m);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern IntPtr GetModuleHandle(string name);
    }
}
