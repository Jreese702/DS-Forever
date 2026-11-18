using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using DsfDesktop.Bridge;
using DsfDesktop.Sunshine;
using DsfDesktop.Wow;
using DsfDesktop.Wow.Patch;

namespace DsfDesktop.Tests
{
    /// <summary>
    /// feat/pause-power (the user, 2026-11-18: "When streaming this should also close wow on the PC and revert everything back and be the
    /// clean way to disconnect"): the Thor's "end_session" - the bridge's answers, the close of the streamed WoW only (SessionEnd), the
    /// put-back now (WowConfigKeep.PutBackNow) and the monitor with no grace (DisplayKeep.EndNow). Fakes and temp folders only.
    /// </summary>
    public static class SessionEndTests
    {
        const string Forever = @"C:\Games\World of Warcraft\_forever_";
        const string Retail = @"C:\Games\World of Warcraft\_retail_";

        sealed class Pcs
        {
            public readonly List<WowProc> Procs = new List<WowProc>();
            public readonly List<string> Did = new List<string>();
            /// <summary>pid -> what makes it go: "exit" (by itself while waited), "window" (its close), "kill", "never"</summary>
            public readonly Dictionary<int, string> Goes = new Dictionary<int, string>();
            public readonly HashSet<int> Asked = new HashSet<int>();
            public readonly HashSet<int> Killed = new HashSet<int>();
            public SessionEnd.Close Run(bool quitAsked) => SessionEnd.CloseWow(Forever, () => Procs.ToList(), quitAsked,
                closeWindow: pid => { Did.Add("close " + pid); Asked.Add(pid); },
                waitExit: (pid, ms) =>
                {
                    Did.Add("wait " + pid + " " + ms / 1000);
                    var g = Goes.TryGetValue(pid, out var x) ? x : "window";
                    return g == "exit" || (g == "window" && Asked.Contains(pid)) || Killed.Contains(pid);
                },
                kill: pid => { Did.Add("kill " + pid); if (Goes.TryGetValue(pid, out var g) && g == "never") return false; Killed.Add(pid); return true; });
        }

        [Test]
        public static void OnlyTheForeverClientIsClosedNeverRetail()
        {
            var pc = new Pcs();
            pc.Procs.Add(new WowProc { Pid = 10, Path = Retail + @"\Wow.exe" });
            pc.Procs.Add(new WowProc { Pid = 20, Path = Forever + @"\Wow.exe" });
            var r = pc.Run(false);
            A.Eq("closed", r.Wow);
            A.Eq("window", r.How);
            A.Eq("close 20,wait 20 20", string.Join(",", pc.Did), "its window's close (WM_CLOSE), retail never touched");
        }

        [Test]
        public static void TheGamesOwnExitGameIsWaitedOutFirst()
        {
            var pc = new Pcs();
            pc.Procs.Add(new WowProc { Pid = 20, Path = Forever + @"\Wow.exe" });
            pc.Goes[20] = "exit";
            var r = pc.Run(true);
            A.Eq("closed", r.Wow);
            A.Eq("exit", r.How);
            A.Eq("wait 20 30", string.Join(",", pc.Did), "the Thor tapped Exit Game: its countdown waited, no window close");
        }

        [Test]
        public static void EndedOnlyWhenItStaysAfterItsWindowsClose()
        {
            var pc = new Pcs();
            pc.Procs.Add(new WowProc { Pid = 20, Path = Forever + @"\Wow.exe" });
            pc.Goes[20] = "kill";
            var r = pc.Run(true);
            A.Eq("closed", r.Wow);
            A.Eq("ended", r.How);
            A.Eq("wait 20 30,close 20,wait 20 20,kill 20,wait 20 10", string.Join(",", pc.Did));
            var stuck = new Pcs();
            stuck.Procs.Add(new WowProc { Pid = 20, Path = Forever + @"\Wow.exe" });
            stuck.Goes[20] = "never";
            A.Eq("left", stuck.Run(false).Wow, "it couldn't be ended: said, nothing else");
        }

        [Test]
        public static void NothingOfOursOpenIsNoneAndAnUnreadableOneIsLeft()
        {
            var pc = new Pcs();
            A.Eq("none", pc.Run(false).Wow);
            pc.Procs.Add(new WowProc { Pid = 10, Path = Retail + @"\Wow.exe" });
            A.Eq("none", pc.Run(false).Wow, "retail alone: nothing of ours");
            A.Eq(0, pc.Did.Count);
            pc.Procs.Add(new WowProc { Pid = 30, Path = null });
            var r = pc.Run(false);
            A.Eq("left", r.Wow, "a client whose folder can't be read is never closed");
            A.Eq(0, pc.Did.Count);
            A.Eq("none", SessionEnd.CloseWow(null, () => pc.Procs, false, _ => { }, (_, __) => true, _ => true).Wow, "no WoW folder");
        }

        const string Cfg = "SET locale \"enUS\"\r\nSET accountName \"fake@example.invalid\"\r\nSET GamePadEnable \"0\"\r\nSET gxWindow \"1\"\r\n";

        [Test]
        public static void ThePutBackRunsNowWithNoGraceAndNoSessionCheck()
        {
            using (var t = new Tmp())
            {
                Directory.CreateDirectory(t.P("_forever_", "WTF"));
                var cfg = t.P("_forever_", "WTF", "Config.wtf");
                File.WriteAllBytes(cfg, System.Text.Encoding.GetEncoding(28591).GetBytes(Cfg));
                bool wow = false, session = true;              // Sunshine's state still BUSY after the Thor left
                var now = DateTime.UtcNow.AddMinutes(10);       // the file has settled
                var keep = new WowConfigKeep(t.P("data", "wowconfig"), () => wow, () => session, () => now);
                WowConfig.Ensure(cfg, keep);
                A.True(keep.Pending);
                keep.Tick();
                A.True(keep.Pending, "the usual rule waits for the session and the grace");
                wow = true;
                A.False(keep.PutBackNow(0, _ => { }), "never while a WoW runs");
                A.True(keep.Pending);
                wow = false;
                A.True(keep.PutBackNow(0, _ => { }), "now: no grace, the session flag not asked");
                A.Eq(Cfg, System.Text.Encoding.GetEncoding(28591).GetString(File.ReadAllBytes(cfg)), "the player's own lines back");
                A.False(keep.Pending);
                A.True(keep.PutBackNow(0, _ => { }), "nothing left: true");
            }
        }

        [Test]
        public static void ThePutBackNowWaitsForConfigWtfToSettle()
        {
            using (var t = new Tmp())
            {
                Directory.CreateDirectory(t.P("_forever_", "WTF"));
                var cfg = t.P("_forever_", "WTF", "Config.wtf");
                File.WriteAllBytes(cfg, System.Text.Encoding.GetEncoding(28591).GetBytes(Cfg));
                var now = DateTime.UtcNow.AddMinutes(10);
                var keep = new WowConfigKeep(t.P("data", "wowconfig"), () => false, () => false, () => now);
                WowConfig.Ensure(cfg, keep);
                File.SetLastWriteTimeUtc(cfg, now.AddSeconds(-1));      // WoW wrote it as it closed, a second ago
                int slept = 0;
                A.True(keep.PutBackNow(8000, ms => { slept += ms; now = now.AddMilliseconds(ms); }), "put back once settled");
                A.True(slept >= 4000 && slept <= 5000, "waited out the settle: " + slept);
                File.SetLastWriteTimeUtc(cfg, now);
                WowConfig.Ensure(cfg, keep);
                File.SetLastWriteTimeUtc(cfg, now);
                A.False(keep.PutBackNow(1000, ms => { now = now.AddMilliseconds(ms); }), "still settling at the wait's end: the usual rule follows");
                A.True(keep.Pending);
            }
        }

        sealed class Win : IVddWindows
        {
            public (int w, int h, int hz) mode = (2560, 1440, 165);
            public readonly List<string> sets = new List<string>();
            public List<WinDisplay> Displays() => new List<WinDisplay>();
            public List<(int w, int h, int hz)> Modes(string dev) => new List<(int, int, int)>();
            public (int w, int h, int hz) Current(string dev) => mode;
            public (int x, int y)? Position(string dev) => (0, 0);
            public int RightEdge() => mode.w;
            public string Set(string dev, int w, int h, int hz, int x, int y) { sets.Add(w + "x" + h + "@" + hz); mode = (w, h, hz); return null; }
            public string Detach(string dev) => null;
            public void Sleep(int ms) { }
        }

        [Test]
        public static void TheMonitorIsLookedAtAsSoonAsTheStreamHasGone()
        {
            using (var t = new Tmp())
            {
                var w = new Win();
                var now = new DateTime(2026, 11, 18, 12, 0, 0, DateTimeKind.Utc);
                var k = new DisplayKeep(t.P("data", "display_keep.json"), w, () => now);
                void Tick(bool s) { k.Tick(s, @"\\.\DISPLAY1", true); now += TimeSpan.FromSeconds(2); }
                for (int i = 0; i < 5; i++) Tick(false);
                Tick(true); w.mode = (1920, 1080, 60); Tick(true); Tick(true);
                A.True(k.Pending);
                k.EndNow();
                Tick(true);                                     // Sunshine still BUSY: nothing yet
                A.Eq(0, w.sets.Count);
                Tick(false);                                    // the stream has gone: at once, no 20 s grace
                A.Eq("2560x1440@165", string.Join(" | ", w.sets));
                A.False(k.Pending);
                // the next stream keeps the usual grace
                for (int i = 0; i < 5; i++) Tick(false);
                Tick(true); w.mode = (1920, 1080, 60); Tick(true); Tick(true);
                Tick(false); Tick(false);
                A.Eq(1, w.sets.Count, "the grace again for a stream that wasn't ended by the Thor");
            }
        }

        [Test]
        public static void TheBridgeAnswersEndingThenEnded()
        {
            using (var h = new FakeHost())
            using (var s = BridgeTests.Start(h))
            using (var c = new Client(s.Port))
            {
                BridgeTests.Pair(c, h, "thor-end", stream: false);
                c.Until("backlog_end");
                c.Send(new Dictionary<string, object> { ["t"] = "end_session", ["quit_asked"] = true });
                var msgs = c.Until("session_ended");
                var types = msgs.Select(m => m.Str("t")).Where(x => x.StartsWith("session_")).ToList();
                A.Eq("session_ending,session_ended", string.Join(",", types), "heard at once, then the result");
                var end = msgs.Last();
                A.Eq("closed", end.Str("wow"));
                A.True(end.Bool("put_back"));
                A.Eq(1, h.EndSessions);
                A.True(h.EndAsked, "quit_asked reaches the host");
            }
        }
    }
}
