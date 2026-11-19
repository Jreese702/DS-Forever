using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// DESKTOP CLICKS THE PAD (the user's pick 2026-11-18, "Desktop clicks on the PC"; Desktop 0.2.42). While streaming, the Thor's
    /// presses of the add-on's hidden TapPad buttons no longer go as a touch over the video (Moonlight touch -> Sunshine -> mouse): that
    /// path landed +/- 1 px off the aimed pixel (test 1496's taps.log) and the pad's rows are 2 px tall. The Thor sends each batch
    /// ("pad_click") in the pad's own game pixels (GetPhysicalScreenSize: pw x ph, top-left), and Desktop clicks the exact pixel in WoW's
    /// client area. AND NO CURSOR ON THE STREAM (the user, 2026-11-18: "the cursor is also visible for all of the clicks unlike when
    /// playing on the thor which is really distracting"):
    ///  - "post" (tried first): WM_MOUSEMOVE + WM_LBUTTONDOWN / UP posted to WoW's window with the client point. The real cursor never
    ///    moves, so nothing moves on the stream. Whether WoW's UI takes posted mouse messages can't be known from here (it may read the
    ///    cursor itself); the Thor checks every batch against the add-on's own press ring (TapPad pad23 rx) and switches to "input" for
    ///    the app's life once posted clicks are not seen (DeskClicks.kt).
    ///  - "input": the real cursor to the pixel (SetCursorPos: exact, no absolute-coordinate rounding), left down / up by SendInput, and
    ///    the cursor put back ~2 frames later - the shortest real click; WoW may show its own cursor for those frames.
    ///  - "park" (the Thor asks while its Trackpad is not open): a real cursor resting inside WoW's client is moved off the stream first
    ///    (onto another monitor when there is one; else onto the game's own freelook park point, which the add-on's stray-press sink
    ///    covers and where WoW keeps its hidden cursor in controller play), only while nobody has touched the PC for <see cref="IdleS"/>.
    ///    Sunshine v2026.914 has no setting to leave the cursor out of the capture (only the client's Ctrl+Alt+Shift+N toggle, which
    ///    needs Alt: never sent, the key-chord rule), so keeping the cursor off the streamed screen is the way.
    /// SAFETY: only from a paired, pinned wss session (BridgeServer); only the _forever_ client's own window (path match); only while a
    /// stream runs. 0.2.43 (the user, 2026-11-19: "it just takes the cursor straight back to my game"): a posted click never needs WoW in
    /// front and never brings it there; a real click needs it in front (brought forward once) and is refused while the PC's own user is
    /// busy (PcUse: physical input in the last 10 s), as is a park; every outcome is one log line. Every point inside the client rect or the
    /// whole batch is refused before anything is clicked; one batch at a time, in order; at most <see cref="MaxPerSecond"/> clicks a
    /// second; a batch that waited too long in the queue is dropped (its moment has passed). Terse log lines, no names.
    /// </summary>
    public static class PadClick
    {
        public const int MaxTaps = 32;
        public const int MaxQueue = 16;
        public const int MaxPerSecond = 25;
        public const int MaxWaitMs = 1000;
        public const double StaleS = 2.5;
        public const double IdleS = 3.0;
        /// <summary>the press is held this long (the game sees two events, as the touch path's 16 ms)</summary>
        public const int HoldMs = 16;
        /// <summary>"input": the cursor goes back this long after the release (the game has read the click)</summary>
        public const int RestoreMs = 16;
        /// <summary>after a batch (the touch path's tail rest)</summary>
        public const int TailMs = 30;
        /// <summary>"input": the cursor rests on the pixel this long before the press (0.2.43: the game sees the move, leaving its
        /// controller cursor, before the button)</summary>
        public const int MoveSettleMs = 16;
        /// <summary>after a park, before the first posted click (the game has seen the cursor go)</summary>
        public const int ParkSettleMs = 50;

        public const int WM_MOUSEMOVE = 0x0200, WM_LBUTTONDOWN = 0x0201, WM_LBUTTONUP = 0x0202, MK_LBUTTON = 0x0001;

        /// <summary>one tap of a batch: the pad's game px and the wait before it (ms)</summary>
        public struct Tap { public double X, Y; public int WaitMs; }

        /// <summary>
        /// The client pixel of game px ([gx], [gy]) on the pad's [pw] x [ph] screen, for a client [cw] x [ch]: floor(g * c / p) - the
        /// pixel the game px falls in (the Thor aims at a pixel's middle, x.5). False = outside the client (never clicked).
        /// </summary>
        public static bool ToClient(double gx, double gy, double pw, double ph, int cw, int ch, out int cx, out int cy)
        {
            cx = cy = -1;
            if (!(pw > 0) || !(ph > 0) || cw <= 0 || ch <= 0 || double.IsNaN(gx) || double.IsNaN(gy)) return false;
            if (gx < 0 || gy < 0 || gx >= pw || gy >= ph) return false;
            cx = (int)Math.Floor(gx * cw / pw);
            cy = (int)Math.Floor(gy * ch / ph);
            return cx >= 0 && cy >= 0 && cx < cw && cy < ch;
        }

        /// <summary>a mouse message's lParam: the client point (x low word, y high word)</summary>
        public static IntPtr LParam(int x, int y) => (IntPtr)(((y & 0xFFFF) << 16) | (x & 0xFFFF));

        public static bool Inside(Displays.RECT r, int sx, int sy) => sx >= r.Left && sy >= r.Top && sx < r.Right && sy < r.Bottom;

        /// <summary>the batch's taps from the Thor's message (null + [why] = refused as a whole)</summary>
        public static List<Tap> Parse(Dictionary<string, object> m, out double pw, out double ph, out string why)
        {
            why = null;
            pw = Num(m, "pw"); ph = Num(m, "ph");
            if (!(pw >= 100) || !(ph >= 100) || pw > 16384 || ph > 16384) { why = "bad_screen"; return null; }
            var arr = m.TryGetValue("taps", out var v) && v is object[] a ? a : null;
            if (arr == null || arr.Length == 0) { why = "no_taps"; return null; }
            if (arr.Length > MaxTaps) { why = "too_many"; return null; }
            var list = new List<Tap>();
            foreach (var o in arr)
            {
                if (!(o is Dictionary<string, object> d)) { why = "bad_tap"; return null; }
                var t = new Tap { X = Num(d, "x"), Y = Num(d, "y"), WaitMs = (int)Math.Max(0, Math.Min(MaxWaitMs, Num(d, "w"))) };
                if (double.IsNaN(t.X) || double.IsNaN(t.Y)) { why = "bad_tap"; return null; }
                list.Add(t);
            }
            return list;
        }

        public static double Num(Dictionary<string, object> m, string k)
        {
            if (m == null || !m.TryGetValue(k, out var v) || v == null) return double.NaN;
            try { return Convert.ToDouble(v, CultureInfo.InvariantCulture); } catch (Exception) { return double.NaN; }
        }

        /// <summary>
        /// Where a resting real cursor goes so the stream shows none: a point on another monitor outside [client] (the stream's screen
        /// is WoW's), else the game's park point [sink] (screen px) inside it; null = nowhere to go.
        /// </summary>
        public static int[] ParkPoint(Displays.RECT client, IList<Displays.RECT> monitors, int[] sink)
        {
            foreach (var m in monitors ?? new List<Displays.RECT>())
            {
                // a monitor that doesn't overlap WoW's client at all: its centre is off the streamed screen
                bool overlap = m.Left < client.Right && client.Left < m.Right && m.Top < client.Bottom && client.Top < m.Bottom;
                if (!overlap && m.W > 0 && m.H > 0) return new[] { m.Left + m.W / 2, m.Top + m.H / 2 };
            }
            if (sink != null && Inside(client, sink[0], sink[1])) return sink;
            return null;
        }
    }

    /// <summary>what the clicker needs from Windows (Win32PadClickOs; the tests use a recorder: no input ever reaches the PC)</summary>
    public interface IPadClickOs
    {
        /// <summary>the _forever_ client's main window (path match), or zero</summary>
        IntPtr WowWindow();
        Displays.RECT? Client(IntPtr h);
        bool IsForeground(IntPtr h);
        /// <summary>null = in front now, else why not</summary>
        string Focus(IntPtr h);
        bool Streaming();
        double IdleSeconds();
        /// <summary>0.2.43: someone used the PC's own keyboard or mouse lately (PcUse: physical input only, never Sunshine's)</summary>
        bool PcInUse();
        IList<Displays.RECT> Monitors();
        bool GetCursor(out int x, out int y);
        bool SetCursor(int x, int y);
        bool Post(IntPtr h, int msg, IntPtr wParam, IntPtr lParam);
        /// <summary>the left button down / up where the cursor is (SendInput)</summary>
        bool Button(bool down);
        void Sleep(int ms);
        DateTime Now();
    }

    /// <summary>one batch from the Thor</summary>
    public sealed class PadBatch
    {
        public long Id;
        public string Mode = "post";
        public bool Park;
        public double Pw, Ph;
        public double[] Sink;
        public List<PadClick.Tap> Taps;
        public DateTime At;
        public Action<Dictionary<string, object>> Done;
    }

    /// <summary>the one serial worker: batches in order, each all-or-nothing up to its first click</summary>
    public sealed class PadClicker : IDisposable
    {
        readonly IPadClickOs os;
        readonly BlockingCollection<PadBatch> queue = new BlockingCollection<PadBatch>(PadClick.MaxQueue);
        readonly Queue<DateTime> recent = new Queue<DateTime>();
        Thread worker;
        /// <summary>how many clicks went out (the window / tests)</summary>
        public int Clicks { get; private set; }
        public Action<string> Log = DsfDesktop.Log.I;

        public PadClicker(IPadClickOs os) { this.os = os; }

        /// <summary>queue a batch from the Thor's message; false = refused at once (Done already called)</summary>
        public bool Enqueue(Dictionary<string, object> m, Action<Dictionary<string, object>> done)
        {
            long id = 0;
            try { id = Convert.ToInt64(m.TryGetValue("id", out var iv) ? iv : 0, CultureInfo.InvariantCulture); } catch (Exception) { }
            var taps = PadClick.Parse(m, out var pw, out var ph, out var why);
            if (taps == null) { done?.Invoke(Result(id, "refused", why, null, 0)); return false; }
            var mode = (m.TryGetValue("mode", out var mv) ? mv as string : null) == "input" ? "input" : "post";
            double[] sink = null;
            if (m.TryGetValue("sink", out var sv) && sv is object[] sa && sa.Length == 2)
            {
                var sx = PadClick.Num(new Dictionary<string, object> { ["v"] = sa[0] }, "v");
                var sy = PadClick.Num(new Dictionary<string, object> { ["v"] = sa[1] }, "v");
                if (!double.IsNaN(sx) && !double.IsNaN(sy)) sink = new[] { sx, sy };
            }
            var b = new PadBatch { Id = id, Mode = mode, Park = m.TryGetValue("park", out var pv) && pv is bool pb && pb, Pw = pw, Ph = ph,
                                   Sink = sink, Taps = taps, At = os.Now(), Done = done };
            Start();
            if (!queue.TryAdd(b)) { done?.Invoke(Result(id, "refused", "busy", mode, 0)); return false; }
            return true;
        }

        void Start()
        {
            lock (queue)
            {
                if (worker != null) return;
                worker = new Thread(() =>
                {
                    try { foreach (var b in queue.GetConsumingEnumerable()) { Dictionary<string, object> r; try { r = Run(b); } catch (Exception e) { r = Result(b.Id, "failed", e.GetType().Name, b.Mode, 0); } try { b.Done?.Invoke(r); } catch (Exception) { } } }
                    catch (Exception) { }
                }) { IsBackground = true, Name = "pad-click" };
                worker.Start();
            }
        }

        static Dictionary<string, object> Result(long id, string state, string why, string mode, int n)
        {
            var d = new Dictionary<string, object> { ["id"] = id, ["state"] = state, ["n"] = n };
            if (why != null) d["why"] = why;
            if (mode != null) d["mode"] = mode;
            return d;
        }

        /// <summary>one batch (the worker; the tests call it directly)</summary>
        public Dictionary<string, object> Run(PadBatch b)
        {
            var now = os.Now();
            if ((now - b.At).TotalSeconds > PadClick.StaleS) return Refuse(b, "stale");
            if (!os.Streaming()) return Refuse(b, "not_streaming");
            var h = os.WowWindow();
            if (h == IntPtr.Zero) return Refuse(b, "no_wow");
            bool input = b.Mode == "input";
            // 0.2.43 (the user, 2026-11-19: "do the quiet clicks keep the real cursor free? ... it just takes the cursor straight back to
            // my game"): a POSTED click goes to WoW's window wherever it stands - never brought to the front for it; the real cursor and
            // the keyboard stay the PC user's. Only a real click needs WoW in front, and never while the PC's own user is busy
            if (input)
            {
                if (os.PcInUse()) return Refuse(b, "pc_in_use");
                if (!os.IsForeground(h))
                {
                    var fw = os.Focus(h);
                    if (fw != null || !os.IsForeground(h)) return Refuse(b, "not_foreground");
                }
            }
            var c = os.Client(h);
            if (c == null || c.Value.W <= 0 || c.Value.H <= 0) return Refuse(b, "no_client");
            var cl = c.Value;
            // every point first: one outside the client refuses the whole batch (nothing clicked)
            var pts = new List<int[]>();
            foreach (var t in b.Taps)
            {
                if (!PadClick.ToClient(t.X, t.Y, b.Pw, b.Ph, cl.W, cl.H, out var cx, out var cy)) return Refuse(b, "outside");
                pts.Add(new[] { cx, cy });
            }
            // the rate: at most MaxPerSecond clicks in any second
            while (recent.Count > 0 && (now - recent.Peek()).TotalSeconds >= 1) recent.Dequeue();
            if (recent.Count + pts.Count > PadClick.MaxPerSecond) return Refuse(b, "rate");
            // the park: only before a posted batch (a real click puts the cursor back where it was anyway), never inside one, never
            // while the PC is in use
            if (b.Park && !input && !os.PcInUse() && Park(cl, b)) os.Sleep(PadClick.ParkSettleMs);
            int n = 0;
            for (int i = 0; i < pts.Count; i++)
            {
                if (b.Taps[i].WaitMs > 0) os.Sleep(b.Taps[i].WaitMs);
                // between taps: the window gone or resized (its client points mean other pixels now) cuts the batch; a window that only
                // moved keeps its client points (a real click aims at the new place); a real click also needs it still in front
                if (i > 0)
                {
                    var c2 = os.Client(h);
                    if (c2 == null || c2.Value.W != cl.W || c2.Value.H != cl.H) return Done(b, "partial", "moved", n, now);
                    cl = c2.Value;
                    if (input && !os.IsForeground(h)) return Done(b, "partial", "lost_front", n, now);
                    if (input && os.PcInUse()) return Done(b, "partial", "pc_in_use", n, now);
                }
                bool ok = input ? Real(h, cl, pts[i][0], pts[i][1]) : Posted(h, pts[i][0], pts[i][1]);
                if (!ok) return Done(b, n == 0 ? "failed" : "partial", input ? "input_refused" : "post_refused", n, now);
                n++; Clicks++;
                recent.Enqueue(os.Now());
            }
            os.Sleep(PadClick.TailMs);
            return Done(b, "done", null, n, now);
        }

        string lastRefusal;
        DateTime lastRefusalAt;

        Dictionary<string, object> Refuse(PadBatch b, string why)
        {
            // one terse line per batch; the same refusal again within 10 s is not repeated (a busy PC refuses many in a row)
            var t = os.Now();
            if (why != lastRefusal || (t - lastRefusalAt).TotalSeconds >= 10)
                Log?.Invoke("pad click: batch " + b.Id + " " + b.Mode + " refused (" + why + ")" + (why == "pc_in_use" ? ": PC in use, nothing pressed" : ""));
            lastRefusal = why; lastRefusalAt = t;
            return Result(b.Id, "refused", why, b.Mode, 0);
        }

        Dictionary<string, object> Done(PadBatch b, string state, string why, int n, DateTime start)
        {
            // 0.2.43: every batch's outcome, one terse line (0.2.42's log had almost none)
            Log?.Invoke("pad click: batch " + b.Id + " " + b.Mode + " " + state + (why != null ? " (" + why + ")" : "") + " " + n + "/" + b.Taps.Count +
                        " " + (int)(os.Now() - start).TotalMilliseconds + " ms");
            return Result(b.Id, state, why, b.Mode, n);
        }

        bool Posted(IntPtr h, int cx, int cy)
        {
            var lp = PadClick.LParam(cx, cy);
            if (!os.Post(h, PadClick.WM_MOUSEMOVE, IntPtr.Zero, lp)) return false;
            if (!os.Post(h, PadClick.WM_LBUTTONDOWN, (IntPtr)PadClick.MK_LBUTTON, lp)) return false;
            os.Sleep(PadClick.HoldMs);
            os.Post(h, PadClick.WM_LBUTTONUP, IntPtr.Zero, lp);
            return true;
        }

        bool Real(IntPtr h, Displays.RECT cl, int cx, int cy)
        {
            int sx = cl.Left + cx, sy = cl.Top + cy;
            if (!PadClick.Inside(cl, sx, sy)) return false;
            bool had = os.GetCursor(out var ox, out var oy);
            if (!os.SetCursor(sx, sy)) return false;
            // the game may warp its cursor (its freelook park): only a cursor on the pixel is pressed
            if (!os.GetCursor(out var nx, out var ny) || nx != sx || ny != sy) { if (had) os.SetCursor(ox, oy); return false; }
            os.Sleep(PadClick.MoveSettleMs);
            if (!os.Button(true)) { if (had) os.SetCursor(ox, oy); return false; }
            os.Sleep(PadClick.HoldMs);
            os.Button(false);
            os.Sleep(PadClick.RestoreMs);
            if (had) os.SetCursor(ox, oy);
            return true;
        }

        /// <summary>a real cursor resting on WoW's client goes off the stream first (only while the PC is idle)</summary>
        bool Park(Displays.RECT cl, PadBatch b)
        {
            if (!os.GetCursor(out var x, out var y) || !PadClick.Inside(cl, x, y)) return false;
            if (os.IdleSeconds() < PadClick.IdleS) return false;
            int[] sink = null;
            if (b.Sink != null && PadClick.ToClient(b.Sink[0], b.Sink[1], b.Pw, b.Ph, cl.W, cl.H, out var kx, out var ky)) sink = new[] { cl.Left + kx, cl.Top + ky };
            var p = PadClick.ParkPoint(cl, os.Monitors(), sink);
            if (p == null || (p[0] == x && p[1] == y)) return false;
            if (!os.SetCursor(p[0], p[1])) return false;
            Log?.Invoke("pad click: cursor parked " + (PadClick.Inside(cl, p[0], p[1]) ? "on the game's park point" : "off the streamed screen"));
            return true;
        }

        public void Dispose() { try { queue.CompleteAdding(); } catch (Exception) { } }
    }

    /// <summary>the real Windows half (never used by the tests)</summary>
    public sealed class Win32PadClickOs : IPadClickOs
    {
        readonly Func<string> foreverDir;
        readonly Func<bool> streaming;
        public Win32PadClickOs(Func<string> foreverDir, Func<bool> streaming) { this.foreverDir = foreverDir; this.streaming = streaming; }

        public IntPtr WowWindow()
        {
            var dir = foreverDir();
            if (string.IsNullOrEmpty(dir)) return IntPtr.Zero;
            using (var p = DsfDesktop.Wow.WowWindow.Find(dir))
            {
                if (p == null) return IntPtr.Zero;
                try
                {
                    // the _forever_ client only: Find falls back to any WoW, a click never does
                    if (!string.Equals(Path.GetDirectoryName(p.MainModule.FileName), dir.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase)) return IntPtr.Zero;
                    return p.MainWindowHandle;
                }
                catch (Exception) { return IntPtr.Zero; }
            }
        }

        public Displays.RECT? Client(IntPtr h) => WowInput.ClientOnScreen(h);
        public bool IsForeground(IntPtr h) => DsfDesktop.Wow.WowWindow.IsForegroundHandle(h);
        public string Focus(IntPtr h) => DsfDesktop.Wow.WowWindow.FocusHandle(h);
        public bool Streaming() => streaming();
        public double IdleSeconds() => DsfDesktop.Wow.WowWindow.PcIdleSeconds();
        public bool PcInUse() => PcUse.InUse();

        public IList<Displays.RECT> Monitors()
        {
            var list = new List<Displays.RECT>();
            EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero, (IntPtr hm, IntPtr dc, ref Displays.RECT r, IntPtr d) => { list.Add(r); return true; }, IntPtr.Zero);
            return list;
        }

        public bool GetCursor(out int x, out int y)
        {
            var ok = GetCursorPos(out var p);
            x = p.X; y = p.Y;
            return ok;
        }

        public bool SetCursor(int x, int y) => SetCursorPos(x, y);
        public bool Post(IntPtr h, int msg, IntPtr wParam, IntPtr lParam) => PostMessage(h, (uint)msg, wParam, lParam);

        public bool Button(bool down)
        {
            var i = new INPUT { type = 0 /* INPUT_MOUSE */ };
            i.mi.dwFlags = down ? 0x0002u /* LEFTDOWN */ : 0x0004u /* LEFTUP */;
            return SendInput(1, new[] { i }, Marshal.SizeOf(typeof(INPUT))) == 1;
        }

        public void Sleep(int ms) { if (ms > 0) Thread.Sleep(ms); }
        public DateTime Now() => DateTime.UtcNow;

        [StructLayout(LayoutKind.Sequential)] struct POINT { public int X, Y; }
        [StructLayout(LayoutKind.Sequential)] struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr dwExtraInfo; }
        // MOUSEINPUT is the union's largest member: INPUT = 40 bytes on x64 (28 on x86), as SendInput's cbSize wants
        [StructLayout(LayoutKind.Sequential)] struct INPUT { public uint type; public MOUSEINPUT mi; }
        delegate bool MonitorEnum(IntPtr hm, IntPtr dc, ref Displays.RECT r, IntPtr d);

        [DllImport("user32.dll")] static extern bool GetCursorPos(out POINT p);
        [DllImport("user32.dll")] static extern bool SetCursorPos(int x, int y);
        [DllImport("user32.dll")] static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
        [DllImport("user32.dll", SetLastError = true)] static extern uint SendInput(uint n, INPUT[] inputs, int size);
        [DllImport("user32.dll")] static extern bool EnumDisplayMonitors(IntPtr dc, IntPtr clip, MonitorEnum cb, IntPtr d);
    }
}
