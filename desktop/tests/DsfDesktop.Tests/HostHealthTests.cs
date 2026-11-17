using System;
using System.Collections.Generic;
using System.Linq;
using DsfDesktop.Sunshine;

namespace DsfDesktop.Tests
{
    /// <summary>the user's PC 2026-11-17: Windows refused RTSP 53010 at boot (WSAEACCES), HTTP stayed up, the old check said "clear"</summary>
    public static class HostHealthTests
    {
        const string OurDir = @"E:\Apps\DsForeverDesktop\sunshine";
        const int OurPid = 49280;
        static readonly DateTime Start = new DateTime(2026, 11, 17, 12, 26, 41);

        static ProcInfo Who(int pid) =>
            pid == OurPid ? new ProcInfo("sunshine", OurDir + @"\sunshine.exe")
            : pid == 14216 ? new ProcInfo("sunshine", null)
            : pid == 901 ? new ProcInfo("nginx", @"C:\nginx\nginx.exe")
            : null;

        static TcpListenerRow[] Tcp(int pid, params int[] ports) => ports.Select(p => new TcpListenerRow(p, pid)).ToArray();
        static UdpRow[] Udp(int pid, params int[] ports) => ports.Select(p => new UdpRow(p, pid)).ToArray();

        const string TodaysLog =
            "[2026-11-17 12:26:41.278]: Info: config: 'port' = 52989\r\n" +
            "[2026-11-17 12:27:06.123]: Info: // Ignore any errors mentioned above, they are not relevant. //\r\n" +
            "[2026-11-17 12:27:06.130]: Fatal: Couldn't bind RTSP server to port [53010], An attempt was made to access a socket in a way forbidden by its access permissions\r\n";

        static HealthReport Check(IEnumerable<TcpListenerRow> tcp, IEnumerable<UdpRow> udp = null, bool? rtsp = true, string log = null,
                                  IEnumerable<PortRange> tx = null, IEnumerable<PortRange> ux = null)
            => HostHealth.Check(52989, OurPid, OurDir, tcp, udp, rtsp, tx, ux, log, Start, Who);

        [Test]
        public static void TodaysFailure_RtspNeverBound_IsAProblem()
        {
            // what the PC had at 12:27:12: 52984/52989/52990 ours, 53010 free, the Fatal line in the log
            var r = Check(Tcp(OurPid, 52984, 52989, 52990).Concat(Tcp(14216, 47984, 47989, 47990, 48010)), rtsp: false, log: TodaysLog);
            A.False(r.Healthy, "not healthy");
            A.Eq("53010", string.Join(",", r.Missing));
            A.True(r.AccessDenied, "WSAEACCES seen");
            A.Eq("RTSP server 53010 (WSAEACCES)", string.Join(";", r.BindErrors));
            A.True(r.Detail.Contains("tcp 53010 missing"), r.Detail);
        }

        [Test]
        public static void AllSixFine_IsHealthy()
        {
            var r = Check(Tcp(OurPid, 52984, 52989, 52990, 53010));
            A.True(r.Healthy, r.Detail);
            // the UDP ones are bound only during a stream (Sunshine's broadcast context): free at idle is fine, ours during a stream too
            A.True(Check(Tcp(OurPid, 52984, 52989, 53010), Udp(OurPid, 52998, 52999, 53000)).Healthy, "ours during a stream");
        }

        [Test]
        public static void TheTableSaysListeningButRtspDoesNotAnswer()
        {
            var r = Check(Tcp(OurPid, 52984, 52989, 53010), rtsp: false);
            A.Eq("53010", string.Join(",", r.Missing));
            A.True(r.Detail.Contains("rtsp connect failed"), r.Detail);
            A.True(Check(Tcp(OurPid, 52984, 52989, 53010), rtsp: null).Healthy, "not tried: the table decides");
        }

        [Test]
        public static void ForeignHoldersAndExcludedRanges()
        {
            var t = Check(Tcp(OurPid, 52984, 52989).Concat(Tcp(901, 53010)));
            A.Eq("53010", string.Join(",", t.Taken), "another program on our RTSP");
            var u = Check(Tcp(OurPid, 52984, 52989, 53010), Udp(901, 52999));
            A.Eq("52999", string.Join(",", u.Blocked), "another program on our control port");
            A.False(u.Healthy);
            var x = Check(Tcp(OurPid, 52984, 52989), tx: new[] { new PortRange(53000, 53099) });
            A.Eq("53010", string.Join(",", x.Excluded), "RTSP inside an excluded range");
            var ux = Check(Tcp(OurPid, 52984, 52989, 53010), ux: new[] { new PortRange(52998, 52998) });
            A.Eq("52998", string.Join(",", ux.Excluded), "video UDP excluded: a stream would fail");
            // a TCP port ours already holds stays ours, whatever Windows reserves afterwards
            A.True(Check(Tcp(OurPid, 52984, 52989, 53010), tx: new[] { new PortRange(52980, 53020) }).Healthy, "held before the reservation");
        }

        [Test]
        public static void OldBindErrorsBeforeOurStartDoNotCount()
        {
            var errs = HostHealth.BindErrors(TodaysLog, Start.AddMinutes(30));
            A.Eq(0, errs.Count, "a line from before the restart");
            A.Eq(1, HostHealth.BindErrors(TodaysLog, Start).Count);
            A.Eq(0, HostHealth.BindErrors(null, Start).Count);
            var plain = HostHealth.BindErrors("[2026-11-17 12:30:00.000]: Error: Couldn't bind Video server to port [52998], in use\n", Start);
            A.Eq(1, plain.Count);
            A.False(plain[0].AccessDenied, "not WSAEACCES");
        }

        [Test]
        public static void ParsesNetshExcludedRanges()
        {
            const string netsh = "\r\nProtocol tcp Port Exclusion Ranges\r\n\r\nStart Port    End Port\r\n----------    --------\r\n" +
                                 "      5357        5357\r\n     50000       50059     *\r\n     52966       53065\r\n\r\n* - Administered port exclusions.\r\n\r\n";
            var r = HostHealth.ParseExcluded(netsh);
            A.Eq("5357,50000-50059,52966-53065", string.Join(",", r));
            A.True(r[2].Has(53010));
            A.Eq(0, HostHealth.ParseExcluded("").Count);
        }

        [Test]
        public static void Healer_ThreeRestartsWithBackoff_ThenReport()
        {
            var h = new HostHealer();
            var bad = Check(Tcp(OurPid, 52984, 52989), rtsp: false);
            var t = Start;
            A.Eq(HealStep.None, h.Tick(t, bad, false, true), "first sight: wait 5 s");
            A.True(h.Healing);
            A.Eq(HealStep.None, h.Tick(t.AddSeconds(4), bad, false, true));
            A.Eq(HealStep.Restart, h.Tick(t = t.AddSeconds(5), bad, false, true), "try 1 at 5 s");
            A.Eq(HealStep.None, h.Tick(t.AddSeconds(14), bad, false, true));
            A.Eq(HealStep.Restart, h.Tick(t = t.AddSeconds(15), bad, false, true), "try 2 15 s later");
            A.Eq(HealStep.None, h.Tick(t.AddSeconds(44), bad, false, true));
            A.Eq(HealStep.Move, h.Tick(t = t.AddSeconds(45), bad, false, true), "try 3 45 s later: a new base (no move yet)");
            A.Eq(3, h.Tries);
            A.Eq(HealStep.GiveUp, h.Tick(t = t.AddSeconds(HostHealer.LastLookS), bad, false, true), "then the report");
            A.True(h.GaveUp);
            A.Eq(HealStep.None, h.Tick(t.AddMinutes(10), bad, false, true), "never loops after giving up");
            A.Eq(HealStep.Healed, h.Tick(t.AddMinutes(11), Check(Tcp(OurPid, 52984, 52989, 53010)), false, true), "fixed by hand: said once");
            A.False(h.GaveUp);
        }

        [Test]
        public static void Healer_NeverRestartsDuringAStream_OrBeforeItSettles()
        {
            var h = new HostHealer();
            var bad = Check(Tcp(OurPid, 52984, 52989), rtsp: false);
            h.Tick(Start, bad, false, true);
            A.Eq(HealStep.None, h.Tick(Start.AddMinutes(5), bad, true, true), "a live stream");
            A.Eq(HealStep.None, h.Tick(Start.AddMinutes(5), bad, false, false), "ours just restarted");
            A.Eq(HealStep.Restart, h.Tick(Start.AddMinutes(5), bad, false, true));
        }

        [Test]
        public static void Healer_MovesForExcludedTakenOrRepeatedWsaeacces()
        {
            var t = Start;
            var x = Check(Tcp(OurPid, 52984, 52989), tx: new[] { new PortRange(53000, 53099) });
            var h = new HostHealer();
            h.Tick(t, x, false, true);
            A.Eq(HealStep.Move, h.Tick(t.AddSeconds(5), x, false, true), "excluded: a new base at once");

            var taken = Check(Tcp(OurPid, 52984, 52989).Concat(Tcp(901, 53010)));
            h = new HostHealer();
            h.Tick(t, taken, false, true);
            A.Eq(HealStep.Move, h.Tick(t.AddSeconds(5), taken, false, true), "another program on our port");

            var denied = Check(Tcp(OurPid, 52984, 52989), rtsp: false, log: TodaysLog);
            h = new HostHealer();
            h.Tick(t, denied, false, true);
            A.Eq(HealStep.Restart, h.Tick(t = t.AddSeconds(5), denied, false, true), "WSAEACCES once: a restart");
            A.Eq(HealStep.Move, h.Tick(t.AddSeconds(15), denied, false, true), "WSAEACCES again: move");
        }

        [Test]
        public static void Healer_TheLastTryMovesWhenNothingSaidWhy()
        {
            // verifier 2026-11-17: RTSP missing with no "Couldn't bind" line and no excluded range: three restarts on the same base,
            // then the report - never the "Yes, move". Now the last try moves; one that moved already doesn't move again for that
            var h = new HostHealer();
            var bad = Check(Tcp(OurPid, 52984, 52989), rtsp: false);
            var t = Start;
            h.Force(t);
            A.Eq(HealStep.Restart, h.Tick(t, bad, false, true));
            A.Eq(HealStep.Restart, h.Tick(t = t.AddSeconds(15), bad, false, true));
            A.Eq(HealStep.Move, h.Tick(t = t.AddSeconds(45), bad, false, true), "the last try");
            A.Eq(HealStep.GiveUp, h.Tick(t.AddSeconds(HostHealer.LastLookS), bad, false, true));

            var g = new HostHealer();
            var taken = Check(Tcp(OurPid, 52984, 52989).Concat(Tcp(901, 53010)));
            g.Force(t);
            A.Eq(HealStep.Move, g.Tick(t, taken, false, true), "moved on try 1");
            A.Eq(HealStep.Restart, g.Tick(t = t.AddSeconds(15), bad, false, true));
            A.Eq(HealStep.Restart, g.Tick(t.AddSeconds(45), bad, false, true), "moved already: the last try restarts");
        }

        [Test]
        public static void BusyIsNotALiveStream()
        {
            // the user's PC 2026-11-17: serverinfo BUSY from 13:02 to 13:50 while the Thor's RTSP failed (110) every 11 s; BUSY alone
            // switched the heal off, and HostCheck answered "streaming" (the Thor gave up with no heal)
            A.True(HostHealth.SessionBound(52989, OurPid, Udp(OurPid, 52999)), "control port ours: a session");
            A.False(HostHealth.SessionBound(52989, OurPid, Udp(OurPid, 5353).Concat(Udp(14216, 47999))), "nothing of ours on 52998-53000");
            var long_ = TimeSpan.FromMinutes(43);
            A.False(HostHealth.StreamLive(true, false, long_), "BUSY for 43 min, no session: not live");
            A.False(HostHealth.StreamLive(true, false, TimeSpan.FromSeconds(5), thorAsked: true), "the Thor's RTSP failed: no grace");
            A.True(HostHealth.StreamLive(true, false, TimeSpan.FromSeconds(5)), "a launch on its way to RTSP");
            A.True(HostHealth.StreamLive(true, true, long_, thorAsked: true), "a session up: live");
            A.True(HostHealth.StreamLive(true, null, long_, thorAsked: true), "UDP table unread: BUSY trusted");
            A.False(HostHealth.StreamLive(false, true, long_));
            // today replayed: the Thor asks, nothing live, so the heal starts at once
            var h = new HostHealer();
            var bad = Check(Tcp(OurPid, 52984, 52989, 52990), rtsp: false, log: TodaysLog);
            bool live = HostHealth.StreamLive(true, HostHealth.SessionBound(52989, OurPid, Udp(OurPid, 5353)), long_, thorAsked: true);
            h.Force(Start);
            A.Eq(HealStep.Restart, h.Tick(Start, bad, live, true));
        }

        [Test]
        public static void Healer_TheThorsAskStartsAtOnce()
        {
            var h = new HostHealer();
            var bad = Check(Tcp(OurPid, 52984, 52989), rtsp: false);
            h.Force(Start);
            A.Eq(HealStep.Restart, h.Tick(Start, bad, false, true), "no 5 s wait");
            // given up earlier: the ask starts over
            var g = new HostHealer();
            var t = Start;
            g.Tick(t, bad, false, true);
            for (int i = 0; i < 4; i++) g.Tick(t = t.AddMinutes(1), bad, false, true);
            A.True(g.GaveUp);
            g.Force(t);
            A.False(g.GaveUp);
            A.Eq(HealStep.Restart, g.Tick(t, bad, false, true));
            // asked while fine: "healed" at once (the Thor retries)
            var f = new HostHealer();
            f.Force(Start);
            A.Eq(HealStep.Healed, f.Tick(Start, Check(Tcp(OurPid, 52984, 52989, 53010)), false, true));
        }

        [Test]
        public static void PickSkipsExcludedAndTheFailedBase()
        {
            Func<int, bool> free = p => true;
            A.Eq(53989, PortPlanner.Pick(52989, free, free, p => p >= 52966 && p <= 53065), "the base with RTSP reserved is skipped");
            A.Eq(53989, PortPlanner.Pick(52989, free, free, avoid: 52989), "never the base that just failed");
            A.Eq(54989, PortPlanner.Pick(0, free, free, null, p => p == 53000 || p == 52999, avoid: 53989), "UDP excluded counts");
            // the whole candidate list reserved: a base below Windows' dynamic range
            int b = PortPlanner.Pick(0, free, free, p => p >= 49152);
            A.Eq(PortPlanner.Fallbacks[0], b);
            A.False(PortPlanner.InStockBlock(b), "never the stock block");
            A.False(PortPlanner.Fallbacks.Any(PortPlanner.InStockBlock), "no fallback in 47984-48010");
            A.False(PortPlanner.Fallbacks.Any(f => PortPlanner.TcpOffsets.Concat(PortPlanner.UdpOffsets).Any(o => f + o >= 48210 && f + o <= 48219)), "clear of the bridge's ports");
        }

        [Test]
        public static void ReadsThisPcsUdpTable()
        {
            A.True(PortGuard.UdpBound().All(r => r.Port >= 0 && r.Port < 65536), "ports in range");
            using (var u = new System.Net.Sockets.UdpClient(0))
            {
                int port = ((System.Net.IPEndPoint)u.Client.LocalEndPoint).Port;
                int me = System.Diagnostics.Process.GetCurrentProcess().Id;
                A.True(PortGuard.UdpBound().Any(r => r.Port == port && r.Pid == me), "our own socket, by our pid");
            }
        }
    }
}
