using System;
using System.Collections.Generic;
using System.Linq;
using DsfDesktop.Sunshine;

namespace DsfDesktop.Tests
{
    /// <summary>"Test" / the Thor's Test connection (the user's pick 2026-11-17: "Yes, build it"): the step runner with fakes, the checks</summary>
    public static class PcTestTests
    {
        const string OurDir = @"E:\Apps\DsForeverDesktop\sunshine";
        const int OurPid = 49280;
        static readonly DateTime Start = new DateTime(2026, 11, 17, 13, 50, 17);

        static ProcInfo Who(int pid) => pid == OurPid ? new ProcInfo("sunshine", OurDir + @"\sunshine.exe") : null;
        static TcpListenerRow[] Tcp(params int[] ports) => ports.Select(p => new TcpListenerRow(p, OurPid)).ToArray();

        static (string, string, Func<TestStep>) S(string id, Func<TestStep> f) => (id, id, f);

        [Test]
        public static void TheRunnerStopsAtTheFirstFailure()
        {
            var ran = new List<string>();
            var heard = new List<string>();
            var res = PcChecks.Run(new[]
            {
                S("ports", () => { ran.Add("ports"); return TestStep.Pass("ok"); }),
                S("rtsp", () => { ran.Add("rtsp"); return TestStep.Fail("No answer on port 53010", PcChecks.FixRestart); }),
                S("encoder", () => { ran.Add("encoder"); return TestStep.Pass(); }),
                S("screen", () => { ran.Add("screen"); return TestStep.Pass(); }),
            }, s => heard.Add(s.Id));
            A.Eq("ports,rtsp", string.Join(",", ran));
            A.Eq("ports,rtsp", string.Join(",", heard));
            A.Eq("pass,fail,not_run,not_run", string.Join(",", res.Select(s => s.Word)));
            A.Eq("rtsp", res.First(s => s.State == TestState.Fail).Id);
        }

        [Test]
        public static void ASkipGoesOn_AThrowIsAFailure()
        {
            var res = PcChecks.Run(new[]
            {
                S("encoder", () => TestStep.Skip("Still starting")),
                S("screen", () => throw new InvalidOperationException("boom")),
                S("controller", () => TestStep.Pass()),
            });
            A.Eq("skip,fail,not_run", string.Join(",", res.Select(s => s.Word)));
            A.True(res[1].Line.Contains("boom"), res[1].Line);
        }

        [Test]
        public static void TodaysPc_PortsFailOnRtsp()
        {
            // 2026-11-17: 52984/52989/52990 ours, 53010 never bound (WSAEACCES)
            var log = "[2026-11-17 13:50:20.130]: Fatal: Couldn't bind RTSP server to port [53010], An attempt was made to access a socket in a way forbidden by its access permissions\r\n";
            var h = HostHealth.Check(52989, OurPid, OurDir, Tcp(52984, 52989, 52990), null, null, null, null, log, Start, Who);
            var s = PcChecks.Ports(h, 52989, true);
            A.Eq(TestState.Fail, s.State);
            A.Eq("Port 53010 not open", s.Line);
            A.Eq(PcChecks.FixRestart, s.Fix);
            var ok = PcChecks.Ports(HostHealth.Check(52989, OurPid, OurDir, Tcp(52984, 52989, 52990, 53010), null, null, null, null, "", Start, Who), 52989, true);
            A.Eq(TestState.Pass, ok.State, ok.Line);
            A.Eq(TestState.Fail, PcChecks.Ports(null, 52989, false).State);
            // verifier 2026-11-17: run in the ~26 s after ours started (its encoder probe), nothing bound yet: not "Port ... not open"
            var probing = HostHealth.Check(52989, OurPid, OurDir, Tcp(), null, null, null, null, "", Start, Who);
            var st = PcChecks.Ports(probing, 52989, true, starting: true);
            A.Eq("The streaming host is starting", st.Line);
            A.False(st.Line.Contains("not open"), st.Line);
            var reserved = HostHealth.Check(52989, OurPid, OurDir, Tcp(52984, 52989, 52990), null, null, new[] { new PortRange(52980, 53079) }, null, "", Start, Who);
            A.True(PcChecks.Ports(reserved, 52989, true).Line.StartsWith("Windows reserved port 53010"), PcChecks.Ports(reserved, 52989, true).Line);
        }

        [Test]
        public static void RtspConnect()
        {
            A.Eq(TestState.Pass, PcChecks.Rtsp(true, 53010, false).State);
            A.Eq("No answer on port 53010", PcChecks.Rtsp(false, 53010, false).Line);
            A.Eq(TestState.Pass, PcChecks.Rtsp(false, 53010, streaming: true).State);
        }

        [Test]
        public static void TheEncoderFromTodaysLog()
        {
            // the lines of E:/dsf-logcopy/out/sunshine/sunshine.log, 2026-11-17
            var log = "[2026-11-17 13:50:41.637]: Info: Encoder [nvenc] is not supported on this GPU\r\n" +
                      "[2026-11-17 13:50:42.901]: Info: Found H.264 encoder: h264_amf [amdvce]\r\n" +
                      "[2026-11-17 13:50:42.901]: Info: Found HEVC encoder: hevc_amf [amdvce]\r\n" +
                      "[2026-11-17 13:50:42.901]: Info: Found AV1 encoder: av1_amf [amdvce]\r\n";
            var s = PcChecks.Encoder(log, Start, true);
            A.Eq(TestState.Pass, s.State);
            A.Eq("h264_amf, hevc_amf, av1_amf", s.Line);
            // a run before ours started doesn't count
            A.Eq(TestState.Skip, PcChecks.Encoder(log, Start.AddHours(1), true).State);
            A.Eq("Still starting", PcChecks.Encoder("", Start, false).Line);
            var none = PcChecks.Encoder("[2026-11-17 13:50:43.000]: Fatal: Couldn't find any working encoder\r\n", Start, true);
            A.Eq(TestState.Fail, none.State);
            A.Eq(PcChecks.FixEncoder, none.Fix);
        }

        [Test]
        public static void TheScreen()
        {
            var own = PcChecks.Screen(new PcChecks.ScreenFacts { OwnOn = true, Ours = true, OursMode = (1920, 1080, 120), Driver = true, Real = true });
            A.Eq("own", own.word); A.Eq("Own virtual screen 1920x1080@120", own.step.Line);
            var made = PcChecks.Screen(new PcChecks.ScreenFacts { OwnOn = true, Driver = true, Made = "", Real = true });
            A.Eq("own", made.word); A.Eq(TestState.Pass, made.step.State);
            var failedReal = PcChecks.Screen(new PcChecks.ScreenFacts { OwnOn = true, Driver = true, Made = "it didn't appear", Real = true });
            A.Eq("real", failedReal.word); A.Eq(TestState.Pass, failedReal.step.State);
            var failedHeadless = PcChecks.Screen(new PcChecks.ScreenFacts { OwnOn = true, Driver = true, Made = "it didn't appear", Real = false });
            A.Eq("none", failedHeadless.word); A.Eq(TestState.Fail, failedHeadless.step.State);
            var noDriver = PcChecks.Screen(new PcChecks.ScreenFacts { OwnOn = true, Real = true });
            A.Eq("Real screen (no virtual screen driver)", noDriver.step.Line);
            A.Eq("Real screen", PcChecks.Screen(new PcChecks.ScreenFacts { OwnOn = false, Real = true }).step.Line);
            var live = PcChecks.Screen(new PcChecks.ScreenFacts { OwnOn = true, Driver = true, NotTried = "a stream is live", Real = true });
            A.Eq("real", live.word); A.Eq("Real screen (a stream is live)", live.step.Line);
            var nothing = PcChecks.Screen(new PcChecks.ScreenFacts { OwnOn = true });
            A.Eq("none", nothing.word); A.Eq(PcChecks.FixScreen, nothing.step.Fix);
        }

        [Test]
        public static void PadAndRates()
        {
            A.Eq(TestState.Pass, PcChecks.Pad("vigem").State);
            A.Eq(PcChecks.FixVigem, PcChecks.Pad("missing").Fix);
            A.Eq(TestState.Skip, PcChecks.Pad("unknown").State);
            var modes = new[] { (1920, 1080, 60), (1920, 1080, 120), (2560, 1440, 165), (1920, 1080, 144) };
            A.Eq(144, PcChecks.MaxHzAt(modes, 1920, 1080));
            A.Eq(0, PcChecks.MaxHzAt(modes, 3840, 2160));
        }

        [Test]
        public static void TheReport_HasNoAddresses_AndTheMessageCarriesTheThorsHalf()
        {
            var r = new PcTestResult { At = new DateTime(2026, 11, 17, 14, 5, 0), Screen = "own", ScreenLine = "Virtual screen works", ScreenHz = 120, Pad = "vigem" };
            r.Steps = PcChecks.Run(new[]
            {
                S("ports", () => TestStep.Pass("All 6 ready (base 52989)")),
                S("rtsp", () => TestStep.Fail("No answer on 192.168.1.20:53010", PcChecks.FixRestart)),
            });
            var lines = r.ReportLines();
            A.Eq("test 2026-11-17 14:05: stopped at rtsp", lines[0]);
            A.True(lines.Any(l => l.Contains("192.168.x.x:53010")), string.Join(" | ", lines));
            A.False(lines.Any(l => l.Contains("192.168.1.20")), "an address");
            A.False(lines.Any(l => l.Contains(Environment.MachineName)), "the PC's name");
            var m = r.ToMessage();
            A.Eq("pc_test_result", (string)m["t"]);
            A.Eq("own", (string)m["screen"]); A.Eq(120, (int)m["screen_hz"]); A.Eq("vigem", (string)m["pad"]);
            var steps = (List<object>)m["steps"];
            A.Eq(2, steps.Count);
            A.Eq("fail", (string)((Dictionary<string, object>)steps[1])["state"]);
            A.Eq("No answer on 192.168.x.x:53010", (string)((Dictionary<string, object>)steps[1])["line"]);
            A.True(r.Lines().Last() == PcChecks.FixRestart, string.Join(" | ", r.Lines()));
        }

        [Test]
        public static void IPv6ShortFormsAreCutToo()
        {
            // reviewers 2026-11-17: fe80::1 (two colons) went into the report whole
            A.Eq("fe80:x, ::x, 2001:x, fe80:x and 192.168.x.x at 12:30:45",
                 PcChecks.Scrub("fe80::1, ::1, 2001:db8::5, fe80::1c2:3%12 and 192.168.1.20 at 12:30:45"));
            A.Eq("ports ok (52984, 52989)", PcChecks.Scrub("ports ok (52984, 52989)"), "port lists and times stay");
            A.Eq("std::string", PcChecks.Scrub("std::string"), "not hex: kept");
        }
    }
}
