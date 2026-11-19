using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using DsfDesktop.Thor;
using DsfDesktop.Wow;

namespace DsfDesktop.Tests
{
    /// <summary>fix/desk-idle-close (2026-11-19): the stream's WoW closes after a Desktop restart too, never the player's own; the
    /// update waits for every WoW; the helper waits out a held file</summary>
    public static class DeskIdleTests
    {
        static readonly DateTime Start = new DateTime(2026, 11, 19, 10, 40, 12, 345, DateTimeKind.Utc);

        [Test]
        public static void TheMarkerRoundTrips()
        {
            using (var t = new Tmp())
            {
                A.Null(StreamWowMark.Read(t.Dir), "no marker yet");
                A.True(StreamWowMark.Write(t.Dir, 41234, Start));
                var m = StreamWowMark.Read(t.Dir);
                A.NotNull(m);
                A.Eq(41234, m.Pid);
                A.Eq(Start.Ticks, m.StartUtcTicks);
                A.True(StreamWowMark.Write(t.Dir, 500, Start.AddHours(1)), "a later stream's WoW replaces it");
                A.Eq(500, StreamWowMark.Read(t.Dir).Pid);
                File.WriteAllText(StreamWowMark.PathIn(t.Dir), "garbage");
                A.Null(StreamWowMark.Read(t.Dir), "an unreadable marker marks nothing");
                StreamWowMark.Clear(t.Dir);
                A.False(File.Exists(StreamWowMark.PathIn(t.Dir)));
            }
        }

        [Test]
        public static void OnlyTheMarkedProcessMatches()
        {
            var m = new StreamWowMark { Pid = 41234, StartUtcTicks = Start.Ticks };
            A.True(StreamWowMark.Matches(m, 41234, Start), "the same process");
            A.True(StreamWowMark.Matches(m, 41234, Start.AddMilliseconds(400)), "rounding");
            A.True(StreamWowMark.Matches(m, 41234, Start.ToLocalTime()), "a local start time is the same instant");
            A.False(StreamWowMark.Matches(m, 41235, Start), "another pid");
            A.False(StreamWowMark.Matches(m, 41234, Start.AddMinutes(20)), "the pid reused by a WoW the player started later");
            A.False(StreamWowMark.Matches(m, 41234, Start.AddSeconds(-5)), "an earlier process");
            A.False(StreamWowMark.Matches(m, 41234, null), "start time unreadable: never");
            A.False(StreamWowMark.Matches(null, 41234, Start), "no marker: never");
        }

        [Test]
        public static void RestartArmsOnlyTheStreamsWow()
        {
            var m = new StreamWowMark { Pid = 41234, StartUtcTicks = Start.Ticks };
            A.True(StreamWowMark.ArmAtStart(false, false, 41234, Start, m), "the 11:06 restart: the stream's WoW, no stream, no Thor");
            A.False(StreamWowMark.ArmAtStart(true, false, 41234, Start, m), "a Thor already connected: the usual rule");
            A.False(StreamWowMark.ArmAtStart(false, true, 41234, Start, m), "a stream on");
            A.False(StreamWowMark.ArmAtStart(false, false, null, null, m), "no WoW running");
            A.False(StreamWowMark.ArmAtStart(false, false, 41234, Start.AddHours(2), m), "the player's own WoW on a reused pid");
            A.False(StreamWowMark.ArmAtStart(false, false, 9999, Start, m), "the player's own WoW");
            A.False(StreamWowMark.ArmAtStart(false, false, 41234, Start, null), "never marked (started on the PC, or from the tray)");
        }

        [Test]
        public static void TheMarkerMatchesARealProcess()
        {
            using (var me = Process.GetCurrentProcess())
            using (var t = new Tmp())
            {
                StreamWowMark.Write(t.Dir, me.Id, me.StartTime.ToUniversalTime());
                using (var again = Process.GetProcessById(me.Id))
                    A.True(StreamWowMark.Matches(StreamWowMark.Read(t.Dir), again.Id, again.StartTime.ToUniversalTime()), "read back through a new handle");
            }
        }

        [Test]
        public static void TheGateWaitsForAnyWow()
        {
            // 11:07:37: 0.2.44 went in after a restart with the stream's WoW up (no longer "from Desktop")
            A.Eq((false, "0.2.44 ready: once WoW closes"), UpdateGate.Decide(new GateInput { ReadyVersion = "0.2.44", WowRunning = true, IdleForS = 999 }));
            A.Eq((false, "0.2.44 ready: after the stream"), UpdateGate.Decide(new GateInput { ReadyVersion = "0.2.44", WowRunning = true, Streaming = true, IdleForS = 999 }));
            A.True(UpdateGate.Decide(new GateInput { ReadyVersion = "0.2.44", IdleForS = 999 }).go, "WoW closed, no stream");
        }

        static (string next, string app, System.Collections.Generic.List<SwapOp> ops) Fixture(Tmp t)
        {
            var next = t.P("next"); var app = t.P("app");
            Directory.CreateDirectory(next); Directory.CreateDirectory(app);
            foreach (var n in new[] { "DsfDesktop.Core.dll", "DsForeverDesktop.exe" })
            {
                File.WriteAllText(Path.Combine(next, n), "new " + n);
                File.WriteAllText(Path.Combine(app, n), "old " + n);
            }
            return (next, app, SwapPlan.Make(SwapPlan.Files(next), SwapPlan.Files(app), SwapPlan.ReadManifest(app)));
        }

        [Test]
        public static void TheSwapWaitsOutAHeldFile()
        {
            using (var t = new Tmp())
            {
                var (next, app, ops) = Fixture(t);
                var core = Path.Combine(app, "DsfDesktop.Core.dll");
                // held as a closing process would hold it: read sharing only, no delete sharing (a rename fails with a sharing violation)
                var held = new FileStream(core, FileMode.Open, FileAccess.Read, FileShare.Read);
                var release = new Timer(_ => held.Dispose(), null, 1200, Timeout.Infinite);
                var lines = new System.Collections.Generic.List<string>();
                var sw = Stopwatch.StartNew();
                A.Null(SwapPlan.Execute(next, app, ops, l => { lock (lines) lines.Add(l); }), "it went in once the file was let go");
                release.Dispose();
                A.True(sw.ElapsedMilliseconds >= 1000, "it waited (" + sw.ElapsedMilliseconds + " ms)");
                A.Eq("new DsfDesktop.Core.dll", File.ReadAllText(core));
                A.Eq("new DsForeverDesktop.exe", File.ReadAllText(Path.Combine(app, "DsForeverDesktop.exe")));
                A.True(lines.Any(l => l.Contains("is in use, waiting")), "said so");
            }
        }

        [Test]
        public static void AFileHeldTooLongIsPutBack()
        {
            using (var t = new Tmp())
            {
                var (next, app, ops) = Fixture(t);
                var core = Path.Combine(app, "DsfDesktop.Core.dll");
                string why;
                using (new FileStream(core, FileMode.Open, FileAccess.Read, FileShare.Read))
                    why = SwapPlan.Execute(next, app, ops, null, TimeSpan.FromMilliseconds(600));
                A.NotNull(why, "it gives up after the wait");
                A.Eq("old DsfDesktop.Core.dll", File.ReadAllText(core), "the old files stay");
                A.Eq("old DsForeverDesktop.exe", File.ReadAllText(Path.Combine(app, "DsForeverDesktop.exe")));
            }
        }

        [Test]
        public static void SharingViolationsAreTold()
        {
            using (var t = new Tmp())
            {
                var f = t.P("x.dll");
                File.WriteAllText(f, "x");
                Exception got = null;
                using (new FileStream(f, FileMode.Open, FileAccess.Read, FileShare.Read))
                    try { File.Move(f, t.P("y.dll")); } catch (Exception e) { got = e; }
                A.NotNull(got);
                A.True(SwapPlan.IsSharingViolation(got), "a held file: " + got.GetType().Name + " " + got.HResult.ToString("x"));
                A.False(SwapPlan.IsSharingViolation(new FileNotFoundException("gone")), "a missing file is not waited for");
                A.False(SwapPlan.IsSharingViolation(new UnauthorizedAccessException()), "nor a read-only folder");
            }
        }
    }
}
