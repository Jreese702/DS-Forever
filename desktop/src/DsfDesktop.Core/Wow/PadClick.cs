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
    /// ("pad_click") in the pad's own game pixels (GetPhysicalScreenSize: pw x ph, top-left), and Desktop POSTS the click to the exact
    /// pixel of WoW's client area: WM_MOUSEMOVE + WM_LBUTTONDOWN / UP posted to WoW's window with the client point.
    /// QUIET CLICKS ONLY (the user's ruling 2026-11-19, Desktop 0.2.43: "Off once quiet clicks work"): a pad press is only ever a posted
    /// click. Desktop never calls SendInput for it, never moves the real cursor (no real click, no cursor park) and never brings WoW to the
    /// front for it: the PC's own user keeps the mouse, the keyboard and the window in front. A batch that asks for "input" or a park (an
    /// older Thor) is posted all the same. If posted presses stop landing, the Thor says so once ("pad_click_unseen") and that is one log
    /// line here - there is no fallback.
    /// SAFETY: only from a paired, pinned wss session (BridgeServer); only the _forever_ client's own window (path match); only while a
    /// stream runs. Every point inside the client rect or the whole batch is refused before anything is posted; one batch at a time, in
    /// order; at most <see cref="MaxPerSecond"/> clicks a second; a batch that waited too long in the queue is dropped (its moment has
    /// passed); every outcome is one terse log line, no names.
    /// </summary>
    public static class PadClick
    {
        public const int MaxTaps = 32;
        public const int MaxQueue = 16;
        public const int MaxPerSecond = 25;
        public const int MaxWaitMs = 1000;
        public const double StaleS = 2.5;
        /// <summary>the press is held this long (the game sees two events, as the touch path's 16 ms)</summary>
        public const int HoldMs = 16;
        /// <summary>after a batch (the touch path's tail rest)</summary>
        public const int TailMs = 30;

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
    }

    /// <summary>what the clicker needs from Windows (Win32PadClickOs; the tests use a recorder: no input ever reaches the PC)</summary>
    public interface IPadClickOs
    {
        /// <summary>the _forever_ client's main window (path match), or zero</summary>
        IntPtr WowWindow();
        Displays.RECT? Client(IntPtr h);
        bool Streaming();
        bool Post(IntPtr h, int msg, IntPtr wParam, IntPtr lParam);
        void Sleep(int ms);
        DateTime Now();
    }

    /// <summary>one batch from the Thor</summary>
    public sealed class PadBatch
    {
        public long Id;
        /// <summary>always "post" (0.2.43: quiet clicks only)</summary>
        public string Mode = "post";
        public double Pw, Ph;
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
            // 0.2.43 (the user's ruling 2026-11-19): quiet clicks only - an older Thor's "input" / "park" is posted all the same
            const string mode = "post";
            var b = new PadBatch { Id = id, Mode = mode, Pw = pw, Ph = ph, Taps = taps, At = os.Now(), Done = done };
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
            // 0.2.43 (the user, 2026-11-19: "it just takes the cursor straight back to my game"; ruling "Off once quiet clicks work"):
            // a POSTED click goes to WoW's window wherever it stands - never brought to the front; the real cursor and the keyboard stay
            // the PC user's
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
            int n = 0;
            for (int i = 0; i < pts.Count; i++)
            {
                if (b.Taps[i].WaitMs > 0) os.Sleep(b.Taps[i].WaitMs);
                // between taps: the window gone or resized (its client points mean other pixels now) cuts the batch; a window that only
                // moved keeps its client points
                if (i > 0)
                {
                    var c2 = os.Client(h);
                    if (c2 == null || c2.Value.W != cl.W || c2.Value.H != cl.H) return Done(b, "partial", "moved", n, now);
                    cl = c2.Value;
                }
                if (!Posted(h, pts[i][0], pts[i][1])) return Done(b, n == 0 ? "failed" : "partial", "post_refused", n, now);
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
                Log?.Invoke("pad click: batch " + b.Id + " " + b.Mode + " refused (" + why + ")");
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
        public bool Streaming() => streaming();
        public bool Post(IntPtr h, int msg, IntPtr wParam, IntPtr lParam) => PostMessage(h, (uint)msg, wParam, lParam);
        public void Sleep(int ms) { if (ms > 0) Thread.Sleep(ms); }
        public DateTime Now() => DateTime.UtcNow;

        // 0.2.43: PostMessage only - no SendInput, SetCursorPos or focus call lives in the clicker any more
        [DllImport("user32.dll")] static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
    }
}
