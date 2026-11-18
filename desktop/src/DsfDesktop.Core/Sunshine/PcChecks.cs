using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;

namespace DsfDesktop.Sunshine
{
    public enum TestState { Pass, Fail, Skip, NotRun }

    /// <summary>one check's answer: [Line] = what was seen (short), [Fix] = what to do (failures only)</summary>
    public sealed class TestStep
    {
        public string Id = "", Name = "", Line = "", Fix = "";
        public TestState State = TestState.NotRun;

        public static TestStep Pass(string line = "") => new TestStep { State = TestState.Pass, Line = line ?? "" };
        public static TestStep Fail(string line, string fix) => new TestStep { State = TestState.Fail, Line = line ?? "", Fix = fix ?? "" };
        public static TestStep Skip(string line) => new TestStep { State = TestState.Skip, Line = line ?? "" };

        public string Word => State == TestState.Pass ? "pass" : State == TestState.Fail ? "fail" : State == TestState.Skip ? "skip" : "not_run";
    }

    /// <summary>a whole run: the steps, and what the Thor's half needs (the screen a stream gets, its top rate, the ViGEmBus word)</summary>
    public sealed class PcTestResult
    {
        public DateTime At;
        public List<TestStep> Steps = new List<TestStep>();
        /// <summary>"own" | "real" | "none"</summary>
        public string Screen = "";
        public string ScreenLine = "";
        public int ScreenHz;
        public string Pad = "";

        public TestStep Failed => Steps.FirstOrDefault(s => s.State == TestState.Fail);

        /// <summary>the window's lines ("✓ Ports: all 6 ready" ...), the failure's fix last</summary>
        public List<string> Lines()
        {
            var res = Steps.Select(s => (s.State == TestState.Pass ? "✓ " : s.State == TestState.Fail ? "✗ " : s.State == TestState.Skip ? "– " : "· ")
                                        + s.Name + (s.Line.Length > 0 ? ": " + s.Line : "")).ToList();
            var f = Failed;
            if (f != null && f.Fix.Length > 0) { res.Add(""); res.Add(f.Fix); }
            return res;
        }

        /// <summary>the log's / report's lines: no names, IPs cut (<see cref="PcChecks.Scrub"/>)</summary>
        public List<string> ReportLines()
        {
            var f = Failed;
            var res = new List<string> { "test " + At.ToString("yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture) + ": " + (f == null ? "passed" : "stopped at " + f.Name) };
            foreach (var s in Steps)
                res.Add("test   " + s.Id + " " + s.Word + (s.Line.Length > 0 ? " (" + s.Line + ")" : "") + (s.Fix.Length > 0 ? " -> " + s.Fix : ""));
            return res.Select(PcChecks.Scrub).ToList();
        }

        /// <summary>the bridge's pc_test_result</summary>
        public Dictionary<string, object> ToMessage() => new Dictionary<string, object>
        {
            ["t"] = "pc_test_result",
            ["steps"] = Steps.Select(s => (object)new Dictionary<string, object> { ["id"] = s.Id, ["name"] = s.Name, ["state"] = s.Word, ["line"] = PcChecks.Scrub(s.Line), ["fix"] = s.Fix }).ToList(),
            ["screen"] = Screen, ["screen_line"] = ScreenLine, ["screen_hz"] = ScreenHz, ["pad"] = Pad,
        };
    }

    /// <summary>
    /// "TEST" (the user's pick 2026-11-17: "Yes, build it" for a Test connection check): the PC's half, in the window's Test button and
    /// in the Thor's Test connection (the bridge's pc_test): our sunshine's ports bound, the RTSP connect, a video encoder found, the
    /// screen a stream gets (our own virtual screen made and removed, or a real one) and the controller's ViGEmBus. Stops at the first
    /// failure with a one-line reason and a fix. Pure: every check is passed in, so the tests drive it with fakes.
    /// </summary>
    public static class PcChecks
    {
        public const string FixRestart = "Restart DS Forever Desktop.";
        public const string FixEncoder = "Update the graphics driver, then restart DS Forever Desktop.";
        public const string FixScreen = "Turn on a screen, or install the virtual screen.";
        public const string FixVigem = "Install ViGEmBus: github.com/nefarius/ViGEmBus/releases";

        /// <summary>the checks in order: (id, name, check); a failure stops the run (the rest not run), a skip does not, a throw fails</summary>
        public static List<TestStep> Run(IEnumerable<(string id, string name, Func<TestStep> check)> checks, Action<TestStep> onStep = null)
        {
            var res = new List<TestStep>();
            bool stopped = false;
            foreach (var (id, name, check) in checks)
            {
                TestStep s;
                if (stopped) s = new TestStep();
                else
                {
                    try { s = check() ?? TestStep.Fail("No answer", "Run the test again."); }
                    catch (Exception e) { s = TestStep.Fail("Check failed (" + e.Message + ")", "Run the test again."); }
                }
                s.Id = id; s.Name = name;
                res.Add(s);
                if (!stopped) onStep?.Invoke(s);
                if (s.State == TestState.Fail) stopped = true;
            }
            return res;
        }

        // ---------------------------------------------------------------- the judgements

        /// <summary>our sunshine's port set (HostHealth): every TCP port listening by ours, the UDP ones free and not reserved</summary>
        /// <summary>[starting] = ours hasn't started yet (its encoder probe: <see cref="HostHealth.Started"/>): its ports aren't judged</summary>
        public static TestStep Ports(HealthReport h, int basePort, bool running, bool starting = false)
        {
            if (!running || basePort <= 0) return TestStep.Fail("The streaming host isn't running", FixRestart);
            if (starting) return TestStep.Fail("The streaming host is starting", "Run the test again in a minute.");
            if (h == null) return TestStep.Fail("Not checked", "Run the test again.");
            if (h.Excluded.Count > 0) return TestStep.Fail("Windows reserved port " + string.Join(", ", h.Excluded), "Wait a minute: Desktop moves its ports by itself. Else " + FixRestart);
            if (h.Taken.Count + h.Blocked.Count > 0) return TestStep.Fail("Another program has port " + string.Join(", ", h.Taken.Concat(h.Blocked)), "Wait a minute: Desktop moves its ports by itself. Else " + FixRestart);
            var miss = h.Missing.Concat(h.BindErrors.Select(b => b.Port)).Distinct().OrderBy(p => p).ToList();
            if (miss.Count > 0) return TestStep.Fail("Port " + string.Join(", ", miss) + " not open", FixRestart);
            return TestStep.Pass("All 6 ready (base " + basePort + ")");
        }

        public static TestStep Rtsp(bool connects, int port, bool streaming) =>
            streaming ? TestStep.Pass("Stream running") : connects ? TestStep.Pass("Port " + port) : TestStep.Fail("No answer on port " + port, FixRestart);

        static readonly Regex LogTime = new Regex(@"^\[(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d(?:\.\d+)?)\]", RegexOptions.Compiled);
        static readonly Regex Found = new Regex(@"Found (H\.264|HEVC|AV1) encoder: (\S+)", RegexOptions.Compiled);
        static readonly Regex NoEncoder = new Regex(@"Couldn't find any working encoder|Unable to find display or encoder", RegexOptions.Compiled | RegexOptions.IgnoreCase);

        /// <summary>
        /// Sunshine's encoder probe in its log since [sinceLocal] (its lines carry the PC's local time): "Found H.264 encoder: h264_amf
        /// [amdvce]" passes (the names), "Couldn't find any working encoder" fails; neither yet: skipped ([settled] = it has had a minute)
        /// </summary>
        public static TestStep Encoder(string log, DateTime sinceLocal, bool settled)
        {
            var names = new List<string>();
            bool none = false;
            foreach (var raw in (log ?? "").Split('\n'))
            {
                var line = raw.TrimEnd('\r');
                var t = LogTime.Match(line);
                if (t.Success && DateTime.TryParseExact(t.Groups[1].Value, new[] { "yyyy-MM-dd HH:mm:ss.fff", "yyyy-MM-dd HH:mm:ss" },
                        CultureInfo.InvariantCulture, DateTimeStyles.None, out var at) && at < sinceLocal.AddSeconds(-1)) continue;
                var f = Found.Match(line);
                if (f.Success) { none = false; if (!names.Contains(f.Groups[2].Value)) names.Add(f.Groups[2].Value); continue; }
                if (NoEncoder.IsMatch(line)) { none = true; names.Clear(); }
            }
            if (none) return TestStep.Fail("No video encoder", FixEncoder);
            if (names.Count > 0) return TestStep.Pass(string.Join(", ", names));
            return settled ? TestStep.Skip("Not in the streaming host's log") : TestStep.Skip("Still starting");
        }

        public static TestStep Pad(string word) =>
            word == "vigem" ? TestStep.Pass("ViGEmBus") : word == "missing" ? TestStep.Fail("ViGEmBus missing", FixVigem) : TestStep.Skip("Not readable");

        /// <summary>what the screen check saw (the live part fills it in)</summary>
        public sealed class ScreenFacts
        {
            /// <summary>"Stream on its own screen" On</summary>
            public bool OwnOn;
            /// <summary>our screen is there now, at [OursMode]</summary>
            public bool Ours;
            public (int w, int h, int hz) OursMode;
            /// <summary>a usable virtual screen driver (SudoVDA / the Virtual Display Driver)</summary>
            public bool Driver;
            /// <summary>the make-and-remove try: null = not tried, "" = made and removed, else why not</summary>
            public string Made;
            /// <summary>why it wasn't tried ("a stream is live", ...), null = it was or there was no need</summary>
            public string NotTried;
            /// <summary>a real screen is on</summary>
            public bool Real;
            /// <summary>the top refresh of the screen a stream gets (0 = unknown)</summary>
            public int Hz;
        }

        /// <summary>the screen a stream gets: ours (made and removed now, or on already), else a real one, else none</summary>
        public static (TestStep step, string word) Screen(ScreenFacts f)
        {
            if (f.Ours) return (TestStep.Pass("Own virtual screen " + f.OursMode.w + "x" + f.OursMode.h + "@" + f.OursMode.hz), "own");
            if (f.OwnOn && f.Driver && f.Made == "") return (TestStep.Pass("Virtual screen works"), "own");
            if (f.OwnOn && f.Driver && f.Made == null && f.NotTried != null)
                return (f.Real ? TestStep.Pass("Real screen (" + f.NotTried + ")") : TestStep.Skip("Not tried (" + f.NotTried + ")"), f.Real ? "real" : "own");
            if (f.OwnOn && f.Driver && !string.IsNullOrEmpty(f.Made))
                return f.Real ? (TestStep.Pass("Real screen (virtual screen failed)"), "real")
                              : (TestStep.Fail("Virtual screen failed: " + f.Made, FixRestart), "none");
            if (f.Real) return (TestStep.Pass("Real screen" + (f.OwnOn && !f.Driver ? " (no virtual screen driver)" : "")), "real");
            return (TestStep.Fail("No screen on the PC", FixScreen), "none");
        }

        /// <summary>the highest refresh offered at the current size (EnumDisplaySettings' list); 0 = none</summary>
        public static int MaxHzAt(IEnumerable<(int w, int h, int hz)> modes, int w, int h)
            => (modes ?? Enumerable.Empty<(int w, int h, int hz)>()).Where(m => m.w == w && m.h == h).Select(m => m.hz).DefaultIfEmpty(0).Max();

        static readonly Regex Ipv4 = new Regex(@"\b(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}\b", RegexOptions.Compiled);

        /// <summary>IPv6 with 3+ colons (never a time)</summary>
        static readonly Regex Ipv6 = new Regex(@"\b([0-9a-fA-F]{1,4}):(?:[0-9a-fA-F]{0,4}:){2,6}[0-9a-fA-F]{0,4}\b(?:%[0-9A-Za-z]+)?", RegexOptions.Compiled);
        /// <summary>IPv6's short forms with "::" (fe80::1, ::1, fe80::1%12; reviewers 2026-11-17): a time never has "::"</summary>
        static readonly Regex Ipv6Short = new Regex(@"(?<![0-9A-Za-z:.])((?:[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4}){0,6})?)::((?:[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4}){0,6})?)(?:%[0-9A-Za-z]+)?(?![0-9A-Za-z:])", RegexOptions.Compiled);

        /// <summary>IPv4 cut to a.b.x.x, IPv6 to its first group (fe80:x; ::1 -> ::x); no line of ours carries a name. The same rule as
        /// the Thor's ConnTest.scrub</summary>
        public static string Scrub(string s)
        {
            var v4 = Ipv4.Replace(s ?? "", m => m.Groups[1].Value + "." + m.Groups[2].Value + ".x.x");
            var v6 = Ipv6.Replace(v4, m => m.Groups[1].Value + ":x");
            return Ipv6Short.Replace(v6, m =>
                m.Groups[1].Value.Length > 0 ? m.Groups[1].Value.Split(':')[0] + ":x" : m.Groups[2].Value.Length > 0 ? "::x" : m.Value);
        }

        // ---------------------------------------------------------------- the live system

        /// <summary>a display's (\\.\DISPLAYn) top refresh at its current size; 0 = unreadable</summary>
        public static int MaxHz(string gdi)
        {
            try
            {
                var cur = New();
                if (!EnumDisplaySettings(gdi, -1, ref cur)) return 0;      // ENUM_CURRENT_SETTINGS
                var all = new List<(int, int, int)>();
                for (int i = 0; i < 2000; i++)
                {
                    var dm = New();
                    if (!EnumDisplaySettings(gdi, i, ref dm)) break;
                    all.Add((dm.dmPelsWidth, dm.dmPelsHeight, dm.dmDisplayFrequency));
                }
                return Math.Max(cur.dmDisplayFrequency, MaxHzAt(all, cur.dmPelsWidth, cur.dmPelsHeight));
            }
            catch (Exception) { return 0; }
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct DEVMODE
        {
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmDeviceName;
            public short dmSpecVersion, dmDriverVersion, dmSize, dmDriverExtra;
            public int dmFields;
            public int dmPositionX, dmPositionY, dmDisplayOrientation, dmDisplayFixedOutput;
            public short dmColor, dmDuplex, dmYResolution, dmTTOption, dmCollate;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmFormName;
            public short dmLogPixels;
            public int dmBitsPerPel, dmPelsWidth, dmPelsHeight, dmDisplayFlags, dmDisplayFrequency;
            public int dmICMMethod, dmICMIntent, dmMediaType, dmDitherType, dmReserved1, dmReserved2, dmPanningWidth, dmPanningHeight;
        }

        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern bool EnumDisplaySettings(string dev, int mode, ref DEVMODE dm);
        static DEVMODE New() => new DEVMODE { dmSize = (short)Marshal.SizeOf(typeof(DEVMODE)) };
    }
}
