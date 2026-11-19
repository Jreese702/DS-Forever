using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using DsfDesktop.Wow;

namespace DsfDesktop.Tests
{
    /// <summary>
    /// Desktop clicks the pad (0.2.42): the conversion with the PC's own numbers (WoW borderless 1920x1080 at 0,0, 96 DPI), clicks only
    /// inside the client rect, the order of what goes to Windows in both modes, the gates. Every Windows call is a RECORDER here: no
    /// input ever reaches this PC. The one real Win32 test posts to a message-only window of the test's own (never WoW).
    /// </summary>
    public static class PadClickTests
    {
        sealed class FakeOs : IPadClickOs
        {
            public readonly List<string> Calls = new List<string>();
            public IntPtr Hwnd = (IntPtr)0x1234;
            public Displays.RECT Rect = new Displays.RECT { Left = 0, Top = 0, Right = 1920, Bottom = 1080 };
            public bool Fg = true, FocusWorks = true, Stream = true;
            public double Idle = 60;
            public int Cx = 3000, Cy = 500;      // the real cursor: on the PC's other monitor
            public List<Displays.RECT> Mons = new List<Displays.RECT> { new Displays.RECT { Left = 0, Top = 0, Right = 1920, Bottom = 1080 } };
            public DateTime T = new DateTime(2026, 11, 18, 20, 0, 0, DateTimeKind.Utc);
            public bool WarpAfterSet;
            public IntPtr WowWindow() => Hwnd;
            public Displays.RECT? Client(IntPtr h) => h == Hwnd ? Rect : (Displays.RECT?)null;
            public bool IsForeground(IntPtr h) => Fg;
            public string Focus(IntPtr h) { Calls.Add("focus"); if (FocusWorks) Fg = true; return FocusWorks ? null : "kept"; }
            public bool Streaming() => Stream;
            public double IdleSeconds() => Idle;
            public bool PcBusy;
            public bool PcInUse() => PcBusy;
            public IList<Displays.RECT> Monitors() => Mons;
            public bool GetCursor(out int x, out int y) { x = Cx; y = Cy; return true; }
            public bool SetCursor(int x, int y) { Calls.Add("set " + x + "," + y); Cx = x; Cy = y; if (WarpAfterSet) { Cx = 960; Cy = 429; } return true; }
            public bool Post(IntPtr h, int msg, IntPtr w, IntPtr l)
            {
                int v = l.ToInt32();
                Calls.Add((msg == PadClick.WM_MOUSEMOVE ? "move" : msg == PadClick.WM_LBUTTONDOWN ? "down" : msg == PadClick.WM_LBUTTONUP ? "up" : "msg" + msg) +
                          " " + (v & 0xFFFF) + "," + ((v >> 16) & 0xFFFF));
                return true;
            }
            public bool Button(bool down) { Calls.Add(down ? "btn down" : "btn up"); return true; }
            public void Sleep(int ms) { T = T.AddMilliseconds(ms); }
            public DateTime Now() => T;
        }

        static Dictionary<string, object> Msg(long id, string mode, bool park, params double[] xyw)
        {
            var taps = new List<object>();
            for (int i = 0; i + 2 < xyw.Length; i += 3)
                taps.Add(new Dictionary<string, object> { ["x"] = xyw[i], ["y"] = xyw[i + 1], ["w"] = xyw[i + 2] });
            return new Dictionary<string, object> { ["t"] = "pad_click", ["id"] = id, ["mode"] = mode, ["park"] = park, ["pw"] = 1920.0, ["ph"] = 1080.0,
                ["sink"] = new object[] { 960.0, 429.0 }, ["taps"] = taps.ToArray() };
        }

        static Dictionary<string, object> RunOne(FakeOs os, Dictionary<string, object> m)
        {
            var c = new PadClicker(os) { Log = null };
            var taps = PadClick.Parse(m, out var pw, out var ph, out var why);
            A.Null(why, "parsed");
            return c.Run(new PadBatch { Id = (long)m["id"], Mode = (string)m["mode"], Park = (bool)m["park"], Pw = pw, Ph = ph,
                Sink = new[] { 960.0, 429.0 }, Taps = taps, At = os.T });
        }

        [Test]
        public static void ThePcsNumbersMapEachRowToItsOwnCentrePixel()
        {
            // test 1496's rows (2 px tall): pnav 14-16, uisw 114-116, iact 52-54; the Thor aims at the centre pixel's middle (top + 1.5)
            foreach (var top in new[] { 14, 52, 114 })
            {
                A.True(PadClick.ToClient(1169.5, top + 1.5, 1920, 1080, 1920, 1080, out var x, out var y));
                A.Eq(1169, x); A.Eq(top + 1, y, "the row's own centre pixel, never a shared edge");
            }
            // a pad on a smaller game screen than the client (the Thor's 960x540 numbers on a 1920x1080 client): scaled
            A.True(PadClick.ToClient(480.25, 7.75, 960, 540, 1920, 1080, out var sx, out var sy));
            A.Eq(960, sx); A.Eq(15, sy);
            // outside: never clicked
            A.False(PadClick.ToClient(1920, 10, 1920, 1080, 1920, 1080, out _, out _), "x = width is outside");
            A.False(PadClick.ToClient(-0.1, 10, 1920, 1080, 1920, 1080, out _, out _));
            A.False(PadClick.ToClient(10, 1080.2, 1920, 1080, 1920, 1080, out _, out _));
            A.False(PadClick.ToClient(double.NaN, 10, 1920, 1080, 1920, 1080, out _, out _));
            A.Eq(0x000F0491, PadClick.LParam(1169, 15).ToInt32(), "lParam = y << 16 | x");
        }

        [Test]
        public static void PostedClicksNeverMoveTheRealCursor()
        {
            var os = new FakeOs();
            var r = RunOne(os, Msg(7, "post", true, 1169.5, 15.5, 0, 1169.5, 115.5, 120));
            A.Eq("done", r.Str("state")); A.Eq(2L, r.Long("n")); A.Eq("post", r.Str("mode"));
            A.Eq("move 1169,15|down 1169,15|up 1169,15|move 1169,115|down 1169,115|up 1169,115", string.Join("|", os.Calls));
            A.Eq(3000, os.Cx, "the real cursor stayed on the other monitor");
        }

        [Test]
        public static void RealClicksGoToTheExactPixelAndTheCursorGoesBack()
        {
            var os = new FakeOs { Mons = new List<Displays.RECT> { new Displays.RECT { Right = 1920, Bottom = 1080 }, new Displays.RECT { Left = 1920, Right = 3840, Bottom = 1080 } } };
            var r = RunOne(os, Msg(8, "input", false, 1169.5, 53.5, 0));
            A.Eq("done", r.Str("state"));
            A.Eq("set 1169,53|btn down|btn up|set 3000,500", string.Join("|", os.Calls));
            // a client not at 0,0 (a window on the second monitor): screen = client origin + client pixel
            var os2 = new FakeOs { Rect = new Displays.RECT { Left = 1920, Top = 0, Right = 3840, Bottom = 1080 }, Cx = 100, Cy = 100 };
            RunOne(os2, Msg(9, "input", false, 10.5, 15.5, 0));
            A.Eq("set 1930,15|btn down|btn up|set 100,100", string.Join("|", os2.Calls));
        }

        [Test]
        public static void AWarpedCursorIsNeverPressed()
        {
            var os = new FakeOs { WarpAfterSet = true };
            var r = RunOne(os, Msg(10, "input", false, 1169.5, 15.5, 0));
            A.Eq("failed", r.Str("state"));
            A.False(os.Calls.Any(c => c.StartsWith("btn")), "no button when the game moved the cursor off the pixel");
        }

        [Test]
        public static void OnePointOutsideTheClientRefusesTheWholeBatch()
        {
            var os = new FakeOs { Rect = new Displays.RECT { Left = 0, Top = 0, Right = 1280, Bottom = 720 } };
            // the pad says 1920x1080 and its first tap maps inside, but a point past the pad's own screen is refused
            var m = Msg(11, "post", false, 100.5, 15.5, 0, 1920.5, 15.5, 0);
            var taps = PadClick.Parse(m, out var pw, out var ph, out _);
            var r = new PadClicker(os) { Log = null }.Run(new PadBatch { Id = 11, Mode = "post", Pw = pw, Ph = ph, Taps = taps, At = os.T });
            A.Eq("refused", r.Str("state")); A.Eq("outside", r.Str("why"));
            A.Eq(0, os.Calls.Count, "nothing clicked");
        }

        [Test]
        public static void NotInFrontNotStreamingOrStaleRefuses()
        {
            var os = new FakeOs { Fg = false, FocusWorks = false };
            A.Eq("not_foreground", RunOne(os, Msg(1, "input", false, 10.5, 15.5, 0)).Str("why"));
            A.Eq("focus", string.Join("|", os.Calls), "a real click: brought forward once, nothing clicked");
            var os2 = new FakeOs { Fg = false, FocusWorks = true };
            A.Eq("done", RunOne(os2, Msg(2, "input", false, 10.5, 15.5, 0)).Str("state"), "in front after the focus: clicked");
            var os3 = new FakeOs { Stream = false };
            A.Eq("not_streaming", RunOne(os3, Msg(3, "post", false, 10.5, 15.5, 0)).Str("why"));
            var os4 = new FakeOs { Hwnd = IntPtr.Zero };
            A.Eq("no_wow", RunOne(os4, Msg(4, "post", false, 10.5, 15.5, 0)).Str("why"));
            var os5 = new FakeOs();
            var taps = PadClick.Parse(Msg(5, "post", false, 10.5, 15.5, 0), out var pw, out var ph, out _);
            var r = new PadClicker(os5) { Log = null }.Run(new PadBatch { Id = 5, Mode = "post", Pw = pw, Ph = ph, Taps = taps, At = os5.T.AddSeconds(-3) });
            A.Eq("stale", r.Str("why"));
        }

        [Test]
        public static void TheRateIsCapped()
        {
            var os = new FakeOs();
            var c = new PadClicker(os) { Log = null };
            var xs = new List<double>();
            for (int i = 0; i < 20; i++) { xs.Add(10.5 + i); xs.Add(15.5); xs.Add(0); }
            var taps = PadClick.Parse(Msg(1, "post", false, xs.ToArray()), out var pw, out var ph, out _);
            A.Eq("done", c.Run(new PadBatch { Id = 1, Mode = "post", Pw = pw, Ph = ph, Taps = taps, At = os.T }).Str("state"));
            A.Eq("rate", c.Run(new PadBatch { Id = 2, Mode = "post", Pw = pw, Ph = ph, Taps = taps, At = os.T }).Str("why"), "40 in a second: refused");
            os.T = os.T.AddSeconds(1.1);
            A.Eq("done", c.Run(new PadBatch { Id = 3, Mode = "post", Pw = pw, Ph = ph, Taps = taps, At = os.T }).Str("state"), "a second later: fine");
            A.Eq("too_many", Fail(PadClick.Parse(Msg(4, "post", false, Enumerable.Range(0, 33).SelectMany(i => new[] { 10.5, 15.5, 0 }).ToArray()), out _, out _, out var why2), why2));
        }

        static string Fail(object parsed, string why) { A.Null(parsed); return why; }

        [Test]
        public static void ARestingCursorOnTheStreamIsParked()
        {
            // a second monitor: off the streamed screen
            var os = new FakeOs { Cx = 500, Cy = 500, Mons = new List<Displays.RECT> { new Displays.RECT { Right = 1920, Bottom = 1080 }, new Displays.RECT { Left = 1920, Right = 3840, Bottom = 1080 } } };
            RunOne(os, Msg(1, "post", true, 10.5, 15.5, 0));
            A.Eq("set 2880,540", os.Calls[0], "onto the other monitor's middle");
            // one screen only: the game's own park point (the add-on's sink covers it)
            var os2 = new FakeOs { Cx = 500, Cy = 500 };
            RunOne(os2, Msg(2, "post", true, 10.5, 15.5, 0));
            A.Eq("set 960,429", os2.Calls[0]);
            // someone is using the PC's mouse, or the Thor's Trackpad is open (park false): left where it is
            var os3 = new FakeOs { Cx = 500, Cy = 500, Idle = 1 };
            RunOne(os3, Msg(3, "post", true, 10.5, 15.5, 0));
            A.False(os3.Calls.Any(c => c.StartsWith("set")), "not while the PC is in use");
            var os4 = new FakeOs { Cx = 500, Cy = 500 };
            RunOne(os4, Msg(4, "post", false, 10.5, 15.5, 0));
            A.False(os4.Calls.Any(c => c.StartsWith("set")), "not while the Trackpad is open");
        }

        [Test]
        public static void TheQueueAnswersEveryBatchInOrder()
        {
            var os = new FakeOs();
            var got = new System.Collections.Concurrent.ConcurrentQueue<string>();
            using (var c = new PadClicker(os) { Log = null })
            {
                for (int i = 1; i <= 3; i++) c.Enqueue(Msg(i, "post", false, 10.5 * i, 15.5, 0), r => got.Enqueue(r.Long("id") + ":" + r.Str("state")));
                A.False(c.Enqueue(new Dictionary<string, object> { ["id"] = 9L, ["pw"] = 1920.0, ["ph"] = 1080.0 }, r => got.Enqueue(r.Long("id") + ":" + r.Str("why"))));
                var until = DateTime.UtcNow.AddSeconds(5);
                while (got.Count < 4 && DateTime.UtcNow < until) Thread.Sleep(10);
            }
            A.True(got.Contains("9:no_taps"), "a batch with no taps refused at once");
            A.Eq("1:done,2:done,3:done", string.Join(",", got.Where(g => g != "9:no_taps")), "the rest in order");
        }

        /// <summary>0.2.43 (the user, 2026-11-19: "it just takes the cursor straight back to my game"): a posted click never needs the front</summary>
        [Test]
        public static void PostedClicksReachWowBehindAnotherWindowAndNeverFocusIt()
        {
            var os = new FakeOs { Fg = false, FocusWorks = true };
            var r = RunOne(os, Msg(1, "post", false, 10.5, 15.5, 0, 20.5, 15.5, 0));
            A.Eq("done", r.Str("state")); A.Eq(2L, r.Long("n"));
            A.False(os.Calls.Contains("focus"), "never brought forward");
            A.False(os.Calls.Any(c => c.StartsWith("set")), "the real cursor never moved");
            A.False(os.Fg, "the PC user's window stays in front");
        }

        [Test]
        public static void NoRealClickOrParkWhileThePcIsInUse()
        {
            var os = new FakeOs { PcBusy = true, Fg = false };
            A.Eq("pc_in_use", RunOne(os, Msg(1, "input", false, 10.5, 15.5, 0)).Str("why"));
            A.Eq(0, os.Calls.Count, "no focus, no cursor, no button");
            var os2 = new FakeOs { PcBusy = true, Cx = 500, Cy = 500 };
            A.Eq("done", RunOne(os2, Msg(2, "post", true, 10.5, 15.5, 0)).Str("state"), "a posted click still goes");
            A.False(os2.Calls.Any(c => c.StartsWith("set")), "no park while the PC is in use");
            // a real batch: the PC user starts using the PC halfway: the rest is cut (the Thor sends it once more)
            var os3 = new FakeOs();
            var c = new PadClicker(os3) { Log = null };
            var taps = PadClick.Parse(Msg(3, "input", false, 10.5, 15.5, 0, 20.5, 15.5, 0), out var pw, out var ph, out _);
            os3.Calls.Clear();
            var b = new PadBatch { Id = 3, Mode = "input", Pw = pw, Ph = ph, Taps = taps, At = os3.T };
            // the first click flips the PC to busy (as a physical key would)
            var r = new PadClicker(new BusyAfterFirst(os3)) { Log = null }.Run(b);
            A.Eq("partial", r.Str("state")); A.Eq("pc_in_use", r.Str("why")); A.Eq(1L, r.Long("n"));
        }

        sealed class BusyAfterFirst : IPadClickOs
        {
            readonly FakeOs o; int ups;
            public BusyAfterFirst(FakeOs o) { this.o = o; }
            public IntPtr WowWindow() => o.WowWindow();
            public Displays.RECT? Client(IntPtr h) => o.Client(h);
            public bool IsForeground(IntPtr h) => o.IsForeground(h);
            public string Focus(IntPtr h) => o.Focus(h);
            public bool Streaming() => o.Streaming();
            public double IdleSeconds() => o.IdleSeconds();
            public bool PcInUse() => ups > 0;
            public IList<Displays.RECT> Monitors() => o.Monitors();
            public bool GetCursor(out int x, out int y) => o.GetCursor(out x, out y);
            public bool SetCursor(int x, int y) => o.SetCursor(x, y);
            public bool Post(IntPtr h, int msg, IntPtr w, IntPtr l) => o.Post(h, msg, w, l);
            public bool Button(bool down) { if (!down) ups++; return o.Button(down); }
            public void Sleep(int ms) => o.Sleep(ms);
            public DateTime Now() => o.Now();
        }

        [Test]
        public static void AWindowThatOnlyMovedKeepsTheBatchAResizedOneCutsIt()
        {
            var os = new MovingOs();
            var taps = PadClick.Parse(Msg(1, "input", false, 10.5, 15.5, 0, 20.5, 15.5, 0), out var pw, out var ph, out _);
            var r = new PadClicker(os) { Log = null }.Run(new PadBatch { Id = 1, Mode = "input", Pw = pw, Ph = ph, Taps = taps, At = os.T });
            A.Eq("done", r.Str("state"), "moved, not resized: both pressed");
            A.True(os.Calls.Contains("set 120,115"), "the second aimed at the window's new place (100,100 + 20,15)");
            var os2 = new MovingOs { Resize = true };
            var r2 = new PadClicker(os2) { Log = null }.Run(new PadBatch { Id = 2, Mode = "post", Pw = pw, Ph = ph, Taps = taps, At = os2.T });
            A.Eq("partial", r2.Str("state")); A.Eq("moved", r2.Str("why")); A.Eq(1L, r2.Long("n"));
        }

        sealed class MovingOs : IPadClickOs
        {
            public readonly List<string> Calls = new List<string>();
            public DateTime T = new DateTime(2026, 11, 18, 20, 0, 0, DateTimeKind.Utc);
            public bool Resize; int asks; int cx = 3000, cy = 500;
            public IntPtr WowWindow() => (IntPtr)0x1234;
            public Displays.RECT? Client(IntPtr h)
            {
                asks++;
                if (asks == 1) return new Displays.RECT { Left = 0, Top = 0, Right = 1920, Bottom = 1080 };
                return Resize ? new Displays.RECT { Left = 0, Top = 0, Right = 1280, Bottom = 720 } : new Displays.RECT { Left = 100, Top = 100, Right = 2020, Bottom = 1180 };
            }
            public bool IsForeground(IntPtr h) => true;
            public string Focus(IntPtr h) => null;
            public bool Streaming() => true;
            public double IdleSeconds() => 60;
            public bool PcInUse() => false;
            public IList<Displays.RECT> Monitors() => new List<Displays.RECT>();
            public bool GetCursor(out int x, out int y) { x = cx; y = cy; return true; }
            public bool SetCursor(int x, int y) { Calls.Add("set " + x + "," + y); cx = x; cy = y; return true; }
            public bool Post(IntPtr h, int msg, IntPtr w, IntPtr l) { Calls.Add("post"); return true; }
            public bool Button(bool down) { Calls.Add(down ? "btn down" : "btn up"); return true; }
            public void Sleep(int ms) { T = T.AddMilliseconds(ms); }
            public DateTime Now() => T;
        }

        /// <summary>0.2.43: only the PC's OWN keyboard / mouse holds Desktop back; Sunshine's (and our) injected input never does</summary>
        [Test]
        public static void ThePcIsInUseOnlyAfterPhysicalInputForTenSeconds()
        {
            var g = new PcUseGate();
            var t = new DateTime(2026, 11, 19, 1, 0, 0, DateTimeKind.Utc);
            A.False(g.InUse(t), "nothing yet");
            A.True(g.MayFocus(t, wowInFront: false), "a quiet PC: WoW may come forward");
            g.Note(injected: true, t);
            A.False(g.InUse(t.AddSeconds(1)), "Sunshine's injected input (no device) never counts");
            g.Note(injected: false, t.AddSeconds(2));
            A.True(g.InUse(t.AddSeconds(3)), "a real key / mouse: in use");
            A.False(g.MayFocus(t.AddSeconds(3), wowInFront: false), "never over the PC user's window");
            A.True(g.MayFocus(t.AddSeconds(3), wowInFront: true), "WoW already in front: fine");
            A.True(g.InUse(t.AddSeconds(11.9)), "still within 10 s");
            A.False(g.InUse(t.AddSeconds(12.1)), "quiet again after 10 s: resumes");
            g.Note(injected: true, t.AddSeconds(13));
            A.False(g.InUse(t.AddSeconds(13)), "injected input does not restart it");
        }

        // ---- the one real Win32 check: posted mouse messages carry the client point (a message-only window of this test; never WoW)

        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern IntPtr CreateWindowEx(int ex, string cls, string name, int style, int x, int y, int w, int h, IntPtr parent, IntPtr menu, IntPtr inst, IntPtr param);
        [DllImport("user32.dll")] static extern bool DestroyWindow(IntPtr h);
        [DllImport("user32.dll")] static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
        [DllImport("user32.dll")] static extern bool PeekMessage(out MSG m, IntPtr h, uint min, uint max, uint remove);
        [StructLayout(LayoutKind.Sequential)] struct MSG { public IntPtr hwnd; public uint message; public IntPtr wParam, lParam; public uint time; public int x, y; }

        [Test]
        public static void APostedClickReachesAProbeWindowWithItsClientPoint()
        {
            var h = CreateWindowEx(0, "STATIC", "dsf-probe", 0, 0, 0, 0, 0, (IntPtr)(-3) /* HWND_MESSAGE */, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
            if (h == IntPtr.Zero) throw new SkipException("no message-only window here");
            try
            {
                A.True(PadClick.ToClient(1169.5, 15.5, 1920, 1080, 1920, 1080, out var x, out var y));
                var lp = PadClick.LParam(x, y);
                A.True(PostMessage(h, PadClick.WM_MOUSEMOVE, IntPtr.Zero, lp));
                A.True(PostMessage(h, PadClick.WM_LBUTTONDOWN, (IntPtr)PadClick.MK_LBUTTON, lp));
                A.True(PostMessage(h, PadClick.WM_LBUTTONUP, IntPtr.Zero, lp));
                var seen = new List<string>();
                while (PeekMessage(out var m, h, 0x0200, 0x020E, 1))
                    seen.Add(m.message.ToString("x") + "@" + (m.lParam.ToInt64() & 0xFFFF) + "," + ((m.lParam.ToInt64() >> 16) & 0xFFFF));
                A.Eq("200@1169,15|201@1169,15|202@1169,15", string.Join("|", seen));
            }
            finally { DestroyWindow(h); }
        }

        [Test]
        public static void OnlyAPinnedWssSessionGetsClicksOrPolestarFiles()
        {
            using (var h = new FakeHost())
            using (var s = BridgeTests.Start(h))
            {
                var token = h.Devices.Issue("thor-1", "Thor");
                var hello = new Dictionary<string, object> { ["t"] = "hello", ["proto"] = 1, ["device_id"] = "thor-1", ["token"] = token };
                using (var c = new Client(s.Port))
                {
                    c.Send(hello);
                    var w = c.Until("backlog_end").First(m => m.Str("t") == "welcome");
                    A.True(w.TryGetValue("caps", out var caps) && caps is object[] ca && ca.Contains("pad_click") && ca.Contains("polestar_sync"), "the welcome says what it does");
                    c.Send(Msg(5, "post", false, 10.5, 15.5, 0));
                    var r = c.Until("pad_click_result").Last();
                    A.Eq("refused", r.Str("state")); A.Eq("not_pinned", r.Str("why")); A.Eq(5L, r.Long("id"));
                    c.Send(new Dictionary<string, object> { ["t"] = "ps_read", ["acct"] = "x" });
                    A.Eq("not_pinned", c.Until("ps_files").Last().Str("why"));
                    A.Eq(0, h.PadClicks); A.Eq(0, h.PsReads);
                }
                using (var c = new Client(s.Port, tls: true))
                {
                    c.Send(hello);
                    c.Until("backlog_end");
                    c.Send(Msg(6, "post", false, 10.5, 15.5, 0));
                    var r = c.Until("pad_click_result").Last();
                    A.Eq("done", r.Str("state")); A.Eq(6L, r.Long("id"));
                    c.Send(new Dictionary<string, object> { ["t"] = "ps_read", ["acct"] = "x" });
                    c.Until("ps_files");
                    A.Eq(1, h.PadClicks); A.Eq(1, h.PsReads);
                }
            }
        }

        // ---- Polestar's files (temp folders, fake names only)

        static string Acct(Tmp t, string name) { var d = t.P("WTF", "Account", name, "SavedVariables"); Directory.CreateDirectory(d); return Path.GetDirectoryName(d); }

        [Test]
        public static void PolestarFilesAreReadByKeyNeverByName()
        {
            using (var t = new Tmp())
            {
                var acct = Acct(t, "11111111#1");
                File.WriteAllText(Path.Combine(acct, "SavedVariables", "Polestar.lua"), "\nPolestarDB = {\n[\"schema\"] = 1,\n}\n");
                File.WriteAllText(Path.Combine(acct, "SavedVariables", "Questie.lua"), "not ours");
                var ch = Path.Combine(acct, "FakeRealm", "Fakechar", "SavedVariables");
                Directory.CreateDirectory(ch);
                File.WriteAllText(Path.Combine(ch, "Polestar.lua"), "\nPolestarCharDB = {\n[\"schema\"] = 1,\n}\n");
                Directory.CreateDirectory(Path.Combine(acct, "FakeRealm", "Other", "SavedVariables"));      // a character with no Polestar file yet
                var files = PolestarSvSync.Read(acct);
                A.Eq(3, files.Count);
                var key = PolestarSvSync.CharKey("FakeRealm", "Fakechar");
                A.Eq(key, PolestarSvSync.CharKey("fakerealm", "FAKECHAR"), "lower-cased");
                A.True(files.Any(f => f.Str("key") == "acct" && f.Str("var") == "PolestarDB" && f.Str("text").Contains("schema")));
                A.True(files.Any(f => f.Str("key") == key && f.Str("var") == "PolestarCharDB"));
                A.True(files.Any(f => f.Str("key") == PolestarSvSync.CharKey("FakeRealm", "Other") && f["exists"] is bool b && !b));
                A.False(Json.Write(new Dictionary<string, object> { ["files"] = files.ToArray() }).Contains("Fakechar"), "no character name crosses");
            }
        }

        [Test]
        public static void PolestarWritesKeepWowSvSyncsRules()
        {
            using (var t = new Tmp())
            {
                var acct = Acct(t, "11111111#1");
                var ch = Path.Combine(acct, "FakeRealm", "Fakechar", "SavedVariables");
                Directory.CreateDirectory(ch);
                var file = Path.Combine(ch, "Polestar.lua");
                File.WriteAllText(file, "\nPolestarCharDB = {\n[\"manual\"] = {},\n}\n");
                var key = PolestarSvSync.CharKey("FakeRealm", "Fakechar");
                var hash = PolestarSvSync.Read(acct).First(f => f.Str("key") == key).Str("hash");
                var now = new DateTime(2026, 11, 18, 21, 0, 0, DateTimeKind.Utc);
                A.Eq("refused", PolestarSvSync.Write(acct, key, "PolestarCharDB", "{}", 0, hash, now), "never empty");
                A.Eq("refused", PolestarSvSync.Write(acct, key, "PolestarDB", "{[\"a\"]=1}", 1, hash, now), "the account's table never into a character's file");
                A.Eq("refused", PolestarSvSync.Write(acct, key, "PolestarCharDB", "{[\"a\"]=1", 1, hash, now), "a bare table only");
                A.Eq("changed", PolestarSvSync.Write(acct, key, "PolestarCharDB", "{[\"a\"]=1}", 1, "deadbeef", now), "not what was read");
                A.Eq("none", PolestarSvSync.Write(acct, PolestarSvSync.CharKey("FakeRealm", "Nobody"), "PolestarCharDB", "{[\"a\"]=1}", 1, "", now), "no such character folder: never made");
                A.Eq("written", PolestarSvSync.Write(acct, key, "PolestarCharDB", "{[\"a\"]=1}", 1, hash, now));
                A.Eq("\nPolestarCharDB = {[\"a\"]=1}\n", File.ReadAllText(file));
                var backups = Path.Combine(ch, WowSvSync.BackupDir);
                A.True(File.Exists(Path.Combine(backups, "Polestar.lua" + WowSvSync.PreSync)), "the file before the first sync, kept for good");
                A.Eq(1, Directory.GetFiles(backups, "Polestar.lua.*.bak").Length, "a dated backup");
                var h2 = PolestarSvSync.Read(acct).First(f => f.Str("key") == key).Str("hash");
                A.Eq("same", PolestarSvSync.Write(acct, key, "PolestarCharDB", "{[\"a\"]=1}", 1, h2, now));
                // the account's own file, missing until now: written from the Thor's (no backup to keep)
                A.Eq("written", PolestarSvSync.Write(acct, "acct", "PolestarDB", "{[\"schema\"]=1}", 1, "", now));
                A.True(File.ReadAllText(Path.Combine(acct, "SavedVariables", "Polestar.lua")).StartsWith("\nPolestarDB = "));
            }
        }
    }
}
