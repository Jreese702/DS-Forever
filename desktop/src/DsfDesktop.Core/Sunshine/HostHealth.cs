using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;

namespace DsfDesktop.Sunshine
{
    /// <summary>
    /// OUR SUNSHINE'S WHOLE PORT SET (the user's PC 2026-11-17: at boot Windows refused RTSP 53010 with WSAEACCES, our sunshine kept
    /// running with HTTP only, the old check looked at 52984/52989 and said "clear", and the Thor retried RTSP every 11 s for ever).
    /// Sunshine's offsets (its source: nvhttp PORT_HTTPS -5 / PORT_HTTP 0, confighttp +1, rtsp RTSP_SETUP_PORT +21, stream
    /// VIDEO_STREAM_PORT +9 / CONTROL_PORT +10 / AUDIO_STREAM_PORT +11). The TCP ones listen from its start; the UDP ones are bound only
    /// while a stream runs (stream.cpp's broadcast context starts with the first session), so at idle they must be free or ours and
    /// outside Windows' excluded ranges, not bound. Pure: the tables, the RTSP connect, the log and the ranges are passed in.
    /// </summary>
    public sealed class HealthReport
    {
        /// <summary>everything ours answers on, or will be able to bind</summary>
        public bool Healthy => Missing.Count == 0 && Blocked.Count == 0 && Excluded.Count == 0 && BindErrors.Count == 0;
        /// <summary>our TCP ports not listening by our sunshine (or RTSP not answering a connect)</summary>
        public List<int> Missing = new List<int>();
        /// <summary>our TCP ports another program listens on (a restart can't get them back: move)</summary>
        public List<int> Taken = new List<int>();
        /// <summary>our UDP ports another process holds (a stream would fail to bind them)</summary>
        public List<int> Blocked = new List<int>();
        /// <summary>who holds them (port, pid)</summary>
        public List<UdpRow> BlockedBy = new List<UdpRow>();
        /// <summary>the only problem is another process on our UDP ports (TCP all ours, nothing excluded, no bind error): often a
        /// passing socket (a DNS or discovery query on a random port), so the healer waits to see it again and restarts, never moves at once</summary>
        public bool UdpOnly => Blocked.Count > 0 && Missing.Count == 0 && Taken.Count == 0 && Excluded.Count == 0 && BindErrors.Count == 0;
        /// <summary>our ports inside Windows' excluded ranges (Hyper-V / WinNAT reserve blocks at boot)</summary>
        public List<int> Excluded = new List<int>();
        /// <summary>"Couldn't bind" lines in our sunshine.log since its start</summary>
        public List<BindError> BindErrors = new List<BindError>();
        /// <summary>a bind was refused with WSAEACCES (10013: an excluded / reserved port)</summary>
        public bool AccessDenied => BindErrors.Any(b => b.AccessDenied);
        /// <summary>for the log</summary>
        public string Detail = "";
    }

    public struct BindError
    {
        public string What; public int Port; public bool AccessDenied;
        public override string ToString() => What + " " + Port + (AccessDenied ? " (WSAEACCES)" : "");
    }

    public struct UdpRow
    {
        public int Port, Pid;
        public UdpRow(int port, int pid) { Port = port; Pid = pid; }
    }

    /// <summary>one excluded range from netsh (both ends included)</summary>
    public struct PortRange
    {
        public int Low, High;
        public PortRange(int low, int high) { Low = low; High = high; }
        public bool Has(int p) => p >= Low && p <= High;
        public override string ToString() => Low == High ? Low.ToString() : Low + "-" + High;
    }

    public static class HostHealth
    {
        /// <summary>the TCP ports that must listen: HTTPS (-5), HTTP (0), RTSP (+21)</summary>
        public static readonly int[] TcpNeeded = { -5, 0, 21 };
        public const int RtspOffset = 21;

        public static int[] Tcp(int basePort) => TcpNeeded.Select(o => basePort + o).ToArray();

        /// <summary>after Sunshine turns BUSY (an app launched): this long counts as a stream starting, before its UDP sockets bind</summary>
        public static readonly TimeSpan StartGrace = TimeSpan.FromSeconds(20);

        /// <summary>a stream session is up: our sunshine holds one of its UDP ports (video / control / audio bind only while one runs)</summary>
        public static bool SessionBound(int basePort, int ourPid, IEnumerable<UdpRow> udp)
        {
            if (basePort <= 0 || ourPid <= 0 || udp == null) return false;
            var ports = Udp(basePort);
            return udp.Any(r => r.Pid == ourPid && ports.Contains(r.Port));
        }

        /// <summary>
        /// A stream is LIVE (nothing may restart ours). Sunshine's serverinfo BUSY means "an app launched", not "a client streams": it
        /// stays BUSY after a client leaves and while the Thor's RTSP fails (the user's PC 2026-11-17: BUSY 13:02-13:50, RTSP 110 every
        /// 11 s). So BUSY counts only with a bound session, or within <see cref="StartGrace"/> of turning BUSY (a launch on its way to
        /// RTSP). [sessionBound] null = the UDP table couldn't be read: BUSY is trusted. [thorAsked] = the Thor's host_check (its RTSP
        /// already failed: no grace).
        /// </summary>
        public static bool StreamLive(bool busy, bool? sessionBound, TimeSpan busyFor, bool thorAsked = false)
        {
            if (!busy) return false;
            if (!sessionBound.HasValue || sessionBound.Value) return true;
            return !thorAsked && busyFor < StartGrace;
        }
        public static int[] Udp(int basePort) => PortPlanner.UdpOffsets.Select(o => basePort + o).ToArray();

        /// <summary>
        /// [rtspAnswers] = a TCP connect to 127.0.0.1:RTSP worked (null = not tried); [log] = our sunshine.log's text (null = unread),
        /// read from [sinceLocal] (its lines carry the PC's local time); [ourDir] = the folder of our sunshine.exe
        /// </summary>
        public static HealthReport Check(int basePort, int ourPid, string ourDir, IEnumerable<TcpListenerRow> tcp, IEnumerable<UdpRow> udp,
                                         bool? rtspAnswers, IEnumerable<PortRange> tcpExcluded, IEnumerable<PortRange> udpExcluded,
                                         string log, DateTime sinceLocal, Func<int, ProcInfo> who)
        {
            var rep = new HealthReport();
            if (basePort <= 0) return rep;
            var cache = new Dictionary<int, ProcInfo>();
            bool Ours(int pid)
            {
                if (ourPid > 0 && pid == ourPid) return true;
                if (!cache.TryGetValue(pid, out var p)) { try { p = who?.Invoke(pid); } catch (Exception) { p = null; } cache[pid] = p; }
                return PortGuard.Classify(pid, p, ourPid, ourDir) == PortOwner.Ours;
            }
            var tcpRows = (tcp ?? Enumerable.Empty<TcpListenerRow>()).ToList();
            var udpRows = (udp ?? Enumerable.Empty<UdpRow>()).ToList();
            var tx = (tcpExcluded ?? Enumerable.Empty<PortRange>()).ToList();
            var ux = (udpExcluded ?? Enumerable.Empty<PortRange>()).ToList();
            var detail = new List<string>();
            foreach (var p in Tcp(basePort))
            {
                bool listening = tcpRows.Any(r => r.Port == p && Ours(r.Pid));
                bool taken = tcpRows.Any(r => r.Port == p && !Ours(r.Pid));
                if (p == basePort + RtspOffset && rtspAnswers == false) listening = false;
                if (!listening) rep.Missing.Add(p);
                if (!listening && taken) rep.Taken.Add(p);
                // a port ours already holds stays ours whatever Windows reserves later
                bool excl = !listening && tx.Any(r => r.Has(p));
                if (excl) rep.Excluded.Add(p);
                detail.Add("tcp " + p + (listening ? " ours" : taken ? " taken" : " missing") + (excl ? " excluded" : ""));
            }
            foreach (var p in Udp(basePort))
            {
                var others = udpRows.Where(r => r.Port == p && !Ours(r.Pid)).ToList();
                bool mine = udpRows.Any(r => r.Port == p && Ours(r.Pid));
                if (others.Count > 0) { rep.Blocked.Add(p); rep.BlockedBy.AddRange(others); }
                if (ux.Any(r => r.Has(p))) rep.Excluded.Add(p);
                detail.Add("udp " + p + (others.Count > 0 ? " held by pid " + others[0].Pid : mine ? " ours" : " free") + (ux.Any(r => r.Has(p)) ? " excluded" : ""));
            }
            if (rtspAnswers.HasValue) detail.Add("rtsp connect " + (rtspAnswers.Value ? "ok" : "failed"));
            rep.BindErrors = BindErrors(log, sinceLocal);
            if (rep.BindErrors.Count > 0) detail.Add("log: " + string.Join(", ", rep.BindErrors));
            rep.Detail = string.Join(", ", detail);
            return rep;
        }

        /// <summary>after this long ours counts as started whatever its log says (the user's PC: about 26 s from start to its ports)</summary>
        public static readonly TimeSpan StartupMax = TimeSpan.FromSeconds(90);
        /// <summary>with its log unreadable: this long</summary>
        public static readonly TimeSpan StartupNoLog = TimeSpan.FromSeconds(45);
        /// <summary>a start-done line, then this long for the other servers' binds (they start together)</summary>
        public static readonly TimeSpan StartupLineGrace = TimeSpan.FromSeconds(2);

        static readonly Regex StartedLine = new Regex(@"Configuration UI available|Registered Sunshine mDNS|Couldn't bind|Couldn't find any working encoder|Unable to find display or encoder",
            RegexOptions.Compiled | RegexOptions.IgnoreCase);

        /// <summary>
        /// Ours has STARTED: its ports' outcome is known (verifier 2026-11-17: Sunshine probes its encoders before it binds anything -
        /// the user's PC: started 13:50:17, encoders 13:50:42.9, "Configuration UI available" 13:50:42.9, so about 26 s; a 4 s
        /// settle restarted a healthy host twice on every start). Started = every TCP port ours already, or a start-done line in its log
        /// since [sinceLocal] ("Configuration UI available", "Registered Sunshine mDNS", "Couldn't bind", no encoder) at least
        /// <see cref="StartupLineGrace"/> before [nowLocal], or <see cref="StartupMax"/> up (<see cref="StartupNoLog"/> with no log).
        /// </summary>
        public static bool Started(TimeSpan up, bool allTcpOurs, string log, DateTime sinceLocal, DateTime nowLocal)
        {
            if (allTcpOurs || up >= StartupMax) return true;
            if (log == null) return up >= StartupNoLog;
            foreach (var raw in log.Split('\n'))
            {
                var line = raw.TrimEnd('\r');
                if (!StartedLine.IsMatch(line)) continue;
                var m = LogLine.Match(line);
                if (!m.Success || !DateTime.TryParseExact(m.Groups[1].Value, new[] { "yyyy-MM-dd HH:mm:ss.fff", "yyyy-MM-dd HH:mm:ss" },
                        CultureInfo.InvariantCulture, DateTimeStyles.None, out var at)) continue;
                if (at < sinceLocal.AddSeconds(-1)) continue;
                if (nowLocal - at >= StartupLineGrace) return true;
            }
            return false;
        }

        static readonly Regex LogLine = new Regex(@"^\[(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d(?:\.\d+)?)\]:\s*(\w+):\s*(.*)$", RegexOptions.Compiled);
        static readonly Regex BindLine = new Regex(@"Couldn't bind (.+?) to port \[?(\d+)\]?", RegexOptions.Compiled | RegexOptions.IgnoreCase);

        /// <summary>sunshine.log's "Couldn't bind RTSP server to port [53010], An attempt was made to access a socket in a way forbidden by
        /// its access permissions" lines at or after [sinceLocal]; a line without a readable time counts</summary>
        public static List<BindError> BindErrors(string log, DateTime sinceLocal)
        {
            var res = new List<BindError>();
            if (string.IsNullOrEmpty(log)) return res;
            foreach (var raw in log.Split('\n'))
            {
                var line = raw.TrimEnd('\r');
                var b = BindLine.Match(line);
                if (!b.Success) continue;
                var m = LogLine.Match(line);
                if (m.Success && DateTime.TryParseExact(m.Groups[1].Value, new[] { "yyyy-MM-dd HH:mm:ss.fff", "yyyy-MM-dd HH:mm:ss" },
                        CultureInfo.InvariantCulture, DateTimeStyles.None, out var at) && at < sinceLocal.AddSeconds(-1)) continue;
                bool denied = line.IndexOf("forbidden by its access permissions", StringComparison.OrdinalIgnoreCase) >= 0
                              || line.IndexOf("10013", StringComparison.Ordinal) >= 0 || line.IndexOf("WSAEACCES", StringComparison.OrdinalIgnoreCase) >= 0;
                res.Add(new BindError { What = b.Groups[1].Value.Trim(), Port = int.Parse(b.Groups[2].Value, CultureInfo.InvariantCulture), AccessDenied = denied });
            }
            return res;
        }

        /// <summary>
        /// `netsh interface ipv4 show excludedportrange protocol=tcp` (no admin): the rows under "Start Port  End Port", an
        /// administered one marked "*"
        /// </summary>
        public static List<PortRange> ParseExcluded(string text)
        {
            var res = new List<PortRange>();
            if (string.IsNullOrEmpty(text)) return res;
            foreach (var raw in text.Split('\n'))
            {
                var m = Regex.Match(raw, @"^\s*(\d{1,5})\s+(\d{1,5})\s*\*?\s*$");
                if (!m.Success) continue;
                int a = int.Parse(m.Groups[1].Value, CultureInfo.InvariantCulture), b = int.Parse(m.Groups[2].Value, CultureInfo.InvariantCulture);
                if (a > 0 && b >= a && b < 65536) res.Add(new PortRange(a, b));
            }
            return res;
        }

        // ---------------------------------------------------------------- the live system

        /// <summary>a plain TCP connect to 127.0.0.1:[port] within [ms] (closed at once; Sunshine's RTSP server drops it)</summary>
        public static bool Connects(int port, int ms = 1500)
        {
            try
            {
                using (var c = new TcpClient())
                {
                    var t = c.ConnectAsync("127.0.0.1", port);
                    return t.Wait(ms) && c.Connected;
                }
            }
            catch (Exception) { return false; }
        }

        static DateTime excludedAt = DateTime.MinValue;
        static List<PortRange> tcpX = new List<PortRange>(), udpX = new List<PortRange>();
        static readonly object xGate = new object();

        /// <summary>Windows' excluded port ranges (IPv4 and IPv6, TCP and UDP) from netsh, read at most once per [maxAgeS]</summary>
        public static (List<PortRange> tcp, List<PortRange> udp) ExcludedNow(int maxAgeS = 60)
        {
            lock (xGate)
            {
                if ((DateTime.UtcNow - excludedAt).TotalSeconds < maxAgeS) return (tcpX, udpX);
                excludedAt = DateTime.UtcNow;
                tcpX = Netsh("ipv4", "tcp").Concat(Netsh("ipv6", "tcp")).ToList();
                udpX = Netsh("ipv4", "udp").Concat(Netsh("ipv6", "udp")).ToList();
                return (tcpX, udpX);
            }
        }

        static List<PortRange> Netsh(string family, string proto)
        {
            try
            {
                var psi = new ProcessStartInfo("netsh", "interface " + family + " show excludedportrange protocol=" + proto)
                { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
                using (var p = Process.Start(psi))
                {
                    var t = p.StandardOutput.ReadToEndAsync();
                    if (!p.WaitForExit(5000)) { try { p.Kill(); } catch (Exception) { } return new List<PortRange>(); }
                    return ParseExcluded(t.Result);
                }
            }
            catch (Exception) { return new List<PortRange>(); }
        }

        /// <summary>the tail of our sunshine.log (the last [maxBytes]); null = unreadable</summary>
        public static string ReadLog(string path, int maxBytes = 256 * 1024)
        {
            try
            {
                using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                {
                    if (fs.Length > maxBytes) fs.Seek(-maxBytes, SeekOrigin.End);
                    using (var r = new StreamReader(fs, Encoding.UTF8)) return r.ReadToEnd();
                }
            }
            catch (Exception) { return null; }
        }
    }

    /// <summary>what the healer asks the app to do now</summary>
    public enum HealStep { None, Restart, Move, GiveUp, Healed }

    /// <summary>
    /// SELF-HEALING (the user's picks 2026-11-17: "Full: PC + Thor", "Yes, move"): ours missing a port is restarted - only while no stream
    /// is live - 3 tries, 5 s / 15 s / 45 s apart, then reported. A port in Windows' excluded ranges, or a bind refused with WSAEACCES
    /// twice, moves the whole set to a new base instead of restarting on the same one. The Thor's "host_check" ([Force]) starts at once.
    /// Another process on our UDP ports alone (<see cref="HealthReport.UdpOnly"/>) counts only once the same holder is seen again
    /// <see cref="UdpConfirm"/> later (reviewers 2026-11-17: a passing socket moved the whole set at once), and is restarted, not
    /// moved (only the last try moves). Never anything while a stream is live. Pure: the clock and the report are passed in.
    /// </summary>
    public sealed class HostHealer
    {
        public static readonly int[] BackoffS = { 5, 15, 45 };
        public const int MaxTries = 3;
        /// <summary>WSAEACCES this many times in a row: the ports are reserved, move</summary>
        public const int AccessMoves = 2;
        /// <summary>after the last try: the look that decides the report (no sooner than the moved host has started: [settled])</summary>
        public const int LastLookS = 10;
        /// <summary>a UDP-only conflict counts when the same holder is still there this long after it was first seen (two checks 15 s apart)</summary>
        public static readonly TimeSpan UdpConfirm = TimeSpan.FromSeconds(15);

        public int Tries { get; private set; }
        public bool GaveUp { get; private set; }
        /// <summary>a heal is under way (unhealthy seen, not given up yet)</summary>
        public bool Healing => dueAt.HasValue;
        public DateTime? DueAt => dueAt;
        int denied;
        bool moved;
        DateTime? dueAt;
        /// <summary>a UDP-only conflict: when first seen, and by whom (port, pid)</summary>
        DateTime? udpSince;
        HashSet<(int, int)> udpHolders;

        /// <summary>[settled] = ours has STARTED since its last start (<see cref="HostHealth.Started"/>: about 26 s on the user's PC) and
        /// nothing else is restarting it; nothing is judged before that, so each try (and the last look) waits for the host it
        /// restarted. [streaming] = a stream is live (never restart)</summary>
        public HealStep Tick(DateTime now, HealthReport h, bool streaming, bool settled)
        {
            if (h == null) return HealStep.None;
            if (h.Healthy)
            {
                if (!settled) return HealStep.None;
                bool was = Tries > 0 || GaveUp || dueAt.HasValue;
                Reset();
                return was ? HealStep.Healed : HealStep.None;
            }
            if (!settled || GaveUp) return HealStep.None;
            if (streaming) return HealStep.None;
            if (h.UdpOnly)
            {
                var holders = new HashSet<(int, int)>(h.BlockedBy.Select(r => (r.Port, r.Pid)));
                if (!udpSince.HasValue || udpHolders == null || !udpHolders.Overlaps(holders)) { udpSince = now; udpHolders = holders; return HealStep.None; }
                udpHolders.IntersectWith(holders);
                if (now - udpSince.Value < UdpConfirm) return HealStep.None;
            }
            else { udpSince = null; udpHolders = null; }
            if (!dueAt.HasValue) { dueAt = now.AddSeconds(BackoffS[0]); return HealStep.None; }
            if (now < dueAt.Value) return HealStep.None;
            if (Tries >= MaxTries) { GaveUp = true; dueAt = null; return HealStep.GiveUp; }
            denied = h.AccessDenied ? denied + 1 : 0;
            Tries++;
            // the next try 15 s / 45 s on; after the last one, one more look once it has settled, then the report
            dueAt = now.AddSeconds(Tries < MaxTries ? BackoffS[Tries] : LastLookS);
            // a restart can't win back a reserved port or one another program holds: a new base. The last try moves when none has
            // yet (a reservation the log and netsh didn't show still ends on a new base, the user's "Yes, move")
            bool move = h.Excluded.Count > 0 || h.Taken.Count > 0 || (h.Blocked.Count > 0 && !h.UdpOnly) || denied >= AccessMoves || (Tries == MaxTries && !moved);
            if (move) moved = true;
            return move ? HealStep.Move : HealStep.Restart;
        }

        /// <summary>the Thor asked: the next try due now. A heal under way keeps its count (its host may still be starting: the try
        /// waits for [settled], never a restart on top); one given up, or none, starts over</summary>
        public void Force(DateTime now)
        {
            if (!dueAt.HasValue || GaveUp) Reset();
            if (!dueAt.HasValue || dueAt.Value > now) dueAt = now;
        }

        public void Reset() { Tries = 0; GaveUp = false; denied = 0; moved = false; dueAt = null; udpSince = null; udpHolders = null; }
    }
}
