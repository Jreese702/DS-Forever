using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using DsfDesktop.Sunshine;
using DsfDesktop.Wow;

namespace DsfDesktop.Tests
{
    /// <summary>the user, 2026-11-18: "the PC resolution should also be restored after streaming is done" - the monitor's mode
    /// (DisplayKeep) and WoW's saved window (WowConfigKeep.WindowKeys). Fake Windows calls and temp folders only</summary>
    public static class DisplayKeepTests
    {
        const string Mon = @"\\.\DISPLAY1";

        sealed class Win : IVddWindows
        {
            public (int w, int h, int hz) mode = (2560, 1440, 165);
            public (int x, int y) at = (0, 0);
            public string setFail;
            public readonly List<string> sets = new List<string>();
            public List<WinDisplay> Displays() => new List<WinDisplay>();
            public List<(int w, int h, int hz)> Modes(string dev) => new List<(int, int, int)>();
            public (int w, int h, int hz) Current(string dev) => dev == Mon ? mode : (0, 0, 0);
            public (int x, int y)? Position(string dev) => dev == Mon ? at : ((int, int)?)null;
            public int RightEdge() => mode.w;
            public string Set(string dev, int w, int h, int hz, int x, int y)
            {
                sets.Add(dev + " " + w + "x" + h + "@" + hz + " at " + x + "," + y);
                if (setFail != null) return setFail;
                mode = (w, h, hz); at = (x, y);
                return null;
            }
            public string Detach(string dev) => null;
            public void Sleep(int ms) { }
        }

        sealed class Rig : IDisposable
        {
            public readonly Tmp T = new Tmp();
            public readonly Win W = new Win();
            public DateTime Now = new DateTime(2026, 11, 18, 12, 0, 0, DateTimeKind.Utc);
            public DisplayKeep K;
            public Rig() { K = Make(); }
            public DisplayKeep Make() => new DisplayKeep(T.P("data", "display_keep.json"), W, () => Now);
            public void Tick(bool streaming, bool match = true, string dev = Mon) { K.Tick(streaming, dev, match); Now += TimeSpan.FromSeconds(2); }
            public void Idle(int s) { for (int i = 0; i < s / 2; i++) Tick(false); }
            public void Dispose() => T.Dispose();
        }

        /// <summary>a stream with Match on: Sunshine sets the Thor's mode, and (here) never reverts it</summary>
        static void Stream(Rig r, (int, int, int)? thor = null)
        {
            r.Tick(true);                              // stream-start arrives a moment after Sunshine set the mode? the idle one is kept
            r.W.mode = thor ?? (1920, 1080, 60);
            r.Tick(true); r.Tick(true);
        }

        [Test]
        public static void TheMonitorGoesBackWhenSunshineDidNot()
        {
            using (var r = new Rig())
            {
                r.W.at = (-2560, 0);
                r.Idle(10);
                Stream(r);
                A.True(r.K.Pending, "recorded");
                A.True(File.Exists(r.T.P("data", "display_keep.json")));
                r.Idle(10);
                A.Eq(0, r.W.sets.Count, "not inside the grace (a Thor reconnecting)");
                r.Idle(30);
                A.Eq(Mon + " 2560x1440@165 at -2560,0", string.Join(" | ", r.W.sets), "size, rate and place");
                A.False(r.K.Pending);
                A.False(File.Exists(r.T.P("data", "display_keep.json")));
            }
        }

        [Test]
        public static void SunshinesOwnRevertIsLeftAlone()
        {
            using (var r = new Rig())
            {
                r.Idle(10);
                Stream(r);
                r.W.mode = (2560, 1440, 165);          // Sunshine reverted as the client went
                r.Idle(40);
                A.Eq(0, r.W.sets.Count);
                A.False(r.K.Pending);
            }
        }

        [Test]
        public static void APlayersOwnModeAfterTheStreamIsKept()
        {
            using (var r = new Rig())
            {
                r.Idle(10);
                Stream(r);
                r.W.mode = (3840, 2160, 60);            // neither theirs from before nor the stream's: their own pick
                r.Idle(40);
                A.Eq(0, r.W.sets.Count);
                A.False(r.K.Pending);
            }
        }

        [Test]
        public static void AModeSunshineIsJustSettingIsNotThePlayers()
        {
            using (var r = new Rig())
            {
                r.Idle(10);
                r.W.mode = (1920, 1080, 60);            // Sunshine set it before Desktop saw the stream (serverinfo every 2 s)
                r.Tick(false);
                Stream(r);
                r.Idle(40);
                A.Eq(Mon + " 2560x1440@165 at 0,0", string.Join(" | ", r.W.sets), "the mode that had held, not the new one");
            }
        }

        [Test]
        public static void NothingWithMatchOffOrOurOwnScreen()
        {
            using (var r = new Rig())
            {
                r.Idle(10);
                r.Tick(true, match: false); r.W.mode = (1920, 1080, 60); r.Tick(true, match: false);
                A.False(r.K.Pending, "Match off or Apollo running: Sunshine doesn't change the monitor, Apollo's settings are its own");
                r.W.mode = (2560, 1440, 165);
                r.Idle(10);
                r.Tick(true, dev: null); r.Tick(true, dev: null);
                A.False(r.K.Pending, "our own virtual screen");
            }
        }

        [Test]
        public static void ARecordLeftByACrashIsLookedAtAfterTheNextStart()
        {
            using (var r = new Rig())
            {
                r.Idle(10);
                Stream(r);
                r.K = r.Make();                         // Desktop killed mid-stream; the next start, the monitor still at the Thor's
                A.True(r.K.Pending);
                r.Tick(true);                           // a stream right away: still waits
                r.Idle(10);
                A.Eq(0, r.W.sets.Count);
                r.Idle(30);
                A.Eq(1, r.W.sets.Count);
                A.Eq((2560, 1440, 165), r.W.mode);
            }
        }

        [Test]
        public static void ARefusedPutBackIsTriedAgain()
        {
            using (var r = new Rig())
            {
                r.Idle(10);
                Stream(r);
                r.W.setFail = "display settings refused (-2)";
                r.Idle(30);
                A.Eq(1, r.W.sets.Count);
                A.True(r.K.Pending);
                r.W.setFail = null;
                r.Idle(30);
                A.Eq((2560, 1440, 165), r.W.mode);
                A.False(r.K.Pending);
            }
        }

        // ---------------------------------------------------------------- WoW's saved window (Config.wtf)

        static readonly System.Text.Encoding L1 = System.Text.Encoding.GetEncoding(28591);

        [Test]
        public static void WowsWindowGoesBackWhateverTheStreamSaved()
        {
            using (var t = new Tmp())
            {
                Directory.CreateDirectory(t.P("_forever_", "WTF"));
                var cfg = t.P("_forever_", "WTF", "Config.wtf");
                var mine = "SET accountName \"fake@example.invalid\"\r\nSET GxFullscreenResolution \"2560x1440\"\r\nSET GxWindowedResolution \"2560x1440\"\r\nSET GxMaximize \"1\"\r\nSET GamePadEnable \"0\"\r\n";
                File.WriteAllBytes(cfg, L1.GetBytes(mine));
                bool wow = false, session = false;
                var now = DateTime.UtcNow.AddMinutes(10);
                var keep = new WowConfigKeep(t.P("data", "wowconfig"), () => wow, () => session, () => now);
                A.True(keep.RecordWindow(cfg));
                WowConfig.Ensure(cfg, keep);
                // the stream's WoW closed on the Thor's screen: it saved that window, a monitor, and dropped GxMaximize
                var played = L1.GetString(File.ReadAllBytes(cfg)).Replace("GxWindowedResolution \"2560x1440\"", "GxWindowedResolution \"1920x1080\"")
                    .Replace("SET GxMaximize \"1\"\r\n", "") + "SET GxMonitor \"1\"\r\n";
                File.WriteAllBytes(cfg, L1.GetBytes(played));
                A.False(File.ReadAllText(keep.RecordPath(cfg)).Contains("fake@"), "only the window and controller keys");
                wow = true; keep.Tick(); now += keep.Grace; keep.Tick();
                A.Eq(played, L1.GetString(File.ReadAllBytes(cfg)), "never while WoW runs");
                wow = false; keep.Tick(); now += keep.Grace + TimeSpan.FromSeconds(1); keep.Tick();
                var back = L1.GetString(File.ReadAllBytes(cfg));
                A.Eq("SET accountName \"fake@example.invalid\"\r\nSET GxFullscreenResolution \"2560x1440\"\r\nSET GxWindowedResolution \"2560x1440\"\r\nSET GamePadEnable \"0\"\r\nSET GxMaximize \"1\"\r\n", back,
                    "the size back, GxMonitor (absent before) removed, GxMaximize back (at the end: WoW reads keys, not places), controller back");
                A.False(keep.Pending);
            }
        }

        [Test]
        public static void TheWindowRecordedAtAStreamsStartIsTheOneBefore()
        {
            using (var t = new Tmp())
            {
                Directory.CreateDirectory(t.P("_retail_", "WTF"));
                var cfg = t.P("_retail_", "WTF", "Config.wtf");
                var mine = "SET GxWindowedResolution \"3799x2032\"\nSET GxMonitor \"2\"\n";
                File.WriteAllBytes(cfg, L1.GetBytes(mine));
                bool wow = true, session = true;
                var now = DateTime.UtcNow.AddMinutes(10);
                var keep = new WowConfigKeep(t.P("data", "wowconfig"), () => wow, () => session, () => now);
                keep.RecordWindow(cfg);                 // WoW already up as the stream started: the file holds what it saved last
                File.WriteAllBytes(cfg, L1.GetBytes("SET GxWindowedResolution \"1920x1080\"\nSET GxMonitor \"1\"\n"));
                keep.RecordWindow(cfg);                 // every tick of the stream: the first record is kept
                wow = false; session = false; keep.Tick(); now += keep.Grace + TimeSpan.FromSeconds(1); keep.Tick();
                A.Eq(mine, L1.GetString(File.ReadAllBytes(cfg)));
            }
        }
    }
}
