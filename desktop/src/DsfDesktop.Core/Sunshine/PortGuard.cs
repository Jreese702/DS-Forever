using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;

namespace DsfDesktop.Sunshine
{
    /// <summary>who holds one TCP listening port</summary>
    public enum PortOwner { Free, Ours, OtherSunshine, OtherApp, Unknown }

    /// <summary>one row of the system's TCP listener table</summary>
    public struct TcpListenerRow
    {
        public int Port, Pid;
        public TcpListenerRow(int port, int pid) { Port = port; Pid = pid; }
    }

    /// <summary>a process as far as this user can read it: [Name] without ".exe" (null = gone), [Path] null when unreadable (SYSTEM)</summary>
    public sealed class ProcInfo
    {
        public string Name, Path;
        public ProcInfo(string name, string path) { Name = name; Path = path; }
    }

    /// <summary>what the port check found: [Problem] null = fine</summary>
    public sealed class PortReport
    {
        /// <summary>null | "ports_taken" (another program holds OUR ports) | "not_listening"</summary>
        public string Problem;
        /// <summary>the window's line, "" when fine</summary>
        public string Line = "";
        /// <summary>the window's second line: what to do</summary>
        public string Hint = "";
        /// <summary>the Thor's reason ("pc busy: its streaming ports are taken")</summary>
        public string ThorWhy = "";
        /// <summary>a quiet line when another streaming host (Apollo, Sunshine) runs beside ours: "Apollo also runs on this PC (fine)"; "" = none</summary>
        public string Info = "";
        /// <summary>for the log: every watched port and its owner</summary>
        public string Detail = "";
        /// <summary>the foreign ports among the watched ones (sorted)</summary>
        public List<int> ForeignPorts = new List<int>();
        /// <summary>the other streaming hosts, each with every TCP port it listens on: "Apollo pid 900 (47984, 47989)"</summary>
        public List<string> Neighbours = new List<string>();
        /// <summary>the names in [Neighbours] ("Apollo", "Sunshine")</summary>
        public List<string> Hosts = new List<string>();
        public bool Services;
    }

    /// <summary>
    /// A separately installed Sunshine or Apollo keeps the stock GameStream ports 47984/47989/47990/48010 (the user's PC 2026-11-13;
    /// a player's Apollo 2026-11-16). Ours never uses that block (PortPlanner) and the Thor dials only the ports Desktop reports, so
    /// the other host is only named, quietly. A problem is our OWN ports held by someone else, or ours not listening. It never
    /// stops, kills or changes other software.
    /// </summary>
    public static class PortGuard
    {
        /// <summary>stock GameStream TCP: https, http, web UI, RTSP (base 47989: -5, 0, +1, +21)</summary>
        public static readonly int[] StockPorts = { 47984, 47989, 47990, 48010 };

        /// <summary>"Apollo" / "Sunshine" for another streaming host (its name or install path), else null. Apollo is a Sunshine fork and
        /// ships a sunshine.exe, so its folder tells them apart; [apolloInstalled] = a service under an Apollo folder is installed
        /// (used for a SYSTEM process whose path can't be read)</summary>
        public static string HostName(ProcInfo p, bool apolloInstalled = false)
        {
            if (p == null) return null;
            var name = p.Name;
            if (string.IsNullOrEmpty(name) && !string.IsNullOrEmpty(p.Path)) name = System.IO.Path.GetFileNameWithoutExtension(p.Path);
            if (name != null && name.IndexOf("apollo", StringComparison.OrdinalIgnoreCase) >= 0) return "Apollo";
            if (p.Path != null && p.Path.IndexOf(@"\Apollo\", StringComparison.OrdinalIgnoreCase) >= 0) return "Apollo";
            if (name != null && name.IndexOf("sunshine", StringComparison.OrdinalIgnoreCase) >= 0) return p.Path == null && apolloInstalled ? "Apollo" : "Sunshine";
            return null;
        }

        public static int[] OurPorts(int basePort) => basePort > 0 ? PortPlanner.TcpOffsets.Select(o => basePort + o).OrderBy(p => p).ToArray() : new int[0];

        /// <summary>[ourDir] = the folder our sunshine.exe lives in: a sunshine.exe under it is ours even when its pid is not our child's</summary>
        public static PortOwner Classify(int pid, ProcInfo p, int ourPid, string ourDir)
        {
            if (pid <= 0 && p == null) return PortOwner.Free;
            if (ourPid > 0 && pid == ourPid) return PortOwner.Ours;
            var path = p?.Path;
            if (!string.IsNullOrEmpty(path) && UnderDir(path, ourDir)) return PortOwner.Ours;
            var name = p?.Name;
            if (string.IsNullOrEmpty(name) && !string.IsNullOrEmpty(path)) name = System.IO.Path.GetFileNameWithoutExtension(path);
            if (string.IsNullOrEmpty(name)) return PortOwner.Unknown;
            if (name.IndexOf("sunshine", StringComparison.OrdinalIgnoreCase) >= 0 || name.IndexOf("apollo", StringComparison.OrdinalIgnoreCase) >= 0)
                return PortOwner.OtherSunshine;
            return PortOwner.OtherApp;
        }

        public static bool UnderDir(string path, string dir)
        {
            if (string.IsNullOrEmpty(path) || string.IsNullOrEmpty(dir)) return false;
            try
            {
                var d = System.IO.Path.GetFullPath(dir).TrimEnd('\\', '/') + "\\";
                var f = System.IO.Path.GetFullPath(path);
                return f.StartsWith(d, StringComparison.OrdinalIgnoreCase);
            }
            catch (Exception) { return false; }
        }

        /// <summary>
        /// The check itself (pure: the listener rows and the process lookup are passed in). [basePort] = our Sunshine's base (-1 = none),
        /// [ourRunning] = our sunshine.exe is up, [settled] = it has had time to open its ports (a few seconds after its start).
        /// Another host on the stock ports is NOT a problem (the user's coexistence rule: Apollo beside ours is fine; never suggest
        /// stopping or removing it): only our own ports held by someone else, or ours not listening.
        /// </summary>
        public static PortReport Check(IEnumerable<TcpListenerRow> rows, int basePort, int ourPid, bool ourRunning, bool settled,
                                       string ourDir, Func<int, ProcInfo> who, bool apolloInstalled = false)
        {
            var all = (rows ?? Enumerable.Empty<TcpListenerRow>()).ToList();
            var byPort = new Dictionary<int, int>();
            foreach (var r in all)
                if (!byPort.ContainsKey(r.Port)) byPort[r.Port] = r.Pid;
            var cache = new Dictionary<int, ProcInfo>();
            ProcInfo Who(int pid)
            {
                if (!cache.TryGetValue(pid, out var p)) { try { p = who?.Invoke(pid); } catch (Exception) { p = null; } cache[pid] = p; }
                return p;
            }
            PortOwner Kind(int pid) => Classify(pid, Who(pid) ?? new ProcInfo(null, null), ourPid, ourDir);
            PortOwner Owner(int port, out int pid)
            {
                if (!byPort.TryGetValue(port, out pid)) return PortOwner.Free;
                return Kind(pid);
            }

            var ours = OurPorts(basePort);
            var watched = StockPorts.Concat(ours).Distinct().OrderBy(p => p).ToList();
            var rep = new PortReport();
            var detail = new List<string>();
            var foreign = new List<(int port, PortOwner kind, string name, int pid)>();
            foreach (var port in watched)
            {
                var k = Owner(port, out int pid);
                var name = k == PortOwner.Free ? null : Who(pid)?.Name ?? "?";
                detail.Add(port + " " + (k == PortOwner.Free ? "free" : k.ToString().ToLowerInvariant() + " (pid " + pid + " " + name + ")"));
                if (k == PortOwner.OtherSunshine || k == PortOwner.OtherApp || k == PortOwner.Unknown) foreign.Add((port, k, name, pid));
            }
            rep.Detail = string.Join(", ", detail);
            rep.ForeignPorts = foreign.Select(f => f.port).ToList();

            // the other streaming hosts and every port each one listens on (the start log line)
            foreach (var g in all.Where(r => Kind(r.Pid) == PortOwner.OtherSunshine).GroupBy(r => r.Pid).OrderBy(g => g.Key))
            {
                var hn = HostName(Who(g.Key), apolloInstalled) ?? "Sunshine";
                if (!rep.Hosts.Contains(hn)) rep.Hosts.Add(hn);
                rep.Neighbours.Add(hn + " pid " + g.Key + " (" + string.Join(", ", g.Select(r => r.Port).Distinct().OrderBy(p => p)) + ")");
            }
            if (rep.Hosts.Count > 0) rep.Info = string.Join(" and ", rep.Hosts) + " also runs on this PC (fine)";

            // our own Sunshine up for a while but not on its base port: it couldn't bind (or it isn't answering)
            bool oursMissing = ourRunning && settled && basePort > 0 && Owner(basePort, out _) != PortOwner.Ours;
            var onOurs = foreign.Where(f => ours.Contains(f.port)).ToList();

            if (onOurs.Count > 0)
            {
                var ports = string.Join(", ", onOurs.Select(f => f.port));
                var names = string.Join(", ", onOurs.Select(f => f.kind == PortOwner.OtherSunshine ? HostName(Who(f.pid), apolloInstalled) ?? "Sunshine"
                    : f.kind == PortOwner.Unknown || f.name == "?" ? "an unknown program" : f.name + ".exe").Distinct());
                rep.Problem = "ports_taken";
                rep.Line = "DS Forever's streaming ports (" + ports + ") are taken by " + names;
                rep.Hint = "Restart DS Forever Desktop: it moves to free ports.";
                rep.ThorWhy = "pc busy: its streaming ports are taken";
                return rep;
            }
            if (oursMissing)
            {
                rep.Problem = "not_listening";
                rep.Line = "The streaming host started but isn't listening on port " + basePort;
                rep.Hint = "Restart DS Forever Desktop. If it stays, another program may be blocking the port.";
                rep.ThorWhy = "pc busy: the streaming host isn't listening";
            }
            return rep;
        }

        // ---------------------------------------------------------------- the live system

        /// <summary>the PC's TCP listeners with their owner pids (IPv4 and IPv6); empty if the table can't be read</summary>
        public static List<TcpListenerRow> TcpListeners()
        {
            var res = new List<TcpListenerRow>();
            Read(2 /* AF_INET */, 24, 8, 20, res);
            Read(23 /* AF_INET6 */, 56, 20, 52, res);
            return res;
        }

        static void Read(int af, int rowSize, int portOff, int pidOff, List<TcpListenerRow> res)
        {
            int len = 0;
            GetExtendedTcpTable(IntPtr.Zero, ref len, false, af, 3 /* TCP_TABLE_OWNER_PID_LISTENER */, 0);
            for (int attempt = 0; attempt < 3 && len > 0; attempt++)
            {
                var buf = Marshal.AllocHGlobal(len);
                try
                {
                    uint rc = GetExtendedTcpTable(buf, ref len, false, af, 3, 0);
                    if (rc == 122 /* ERROR_INSUFFICIENT_BUFFER */) continue;
                    if (rc != 0) return;
                    int n = Marshal.ReadInt32(buf);
                    for (int i = 0; i < n; i++)
                    {
                        int at = 4 + i * rowSize;
                        if (at + rowSize > len) break;
                        uint p = (uint)Marshal.ReadInt32(buf, at + portOff);
                        int port = (int)(((p & 0xff) << 8) | ((p >> 8) & 0xff));
                        res.Add(new TcpListenerRow(port, Marshal.ReadInt32(buf, at + pidOff)));
                    }
                    return;
                }
                catch (Exception) { return; }
                finally { Marshal.FreeHGlobal(buf); }
            }
        }

        /// <summary>a pid's name and full path; the path is null for a process this user can't open (a SYSTEM service)</summary>
        public static ProcInfo Who(int pid)
        {
            if (pid == 0) return new ProcInfo("Idle", null);
            if (pid == 4) return new ProcInfo("System", null);
            string name = null, path = null;
            try { using (var p = Process.GetProcessById(pid)) name = p.ProcessName; } catch (Exception) { }
            var h = OpenProcess(0x1000 /* PROCESS_QUERY_LIMITED_INFORMATION */, false, pid);
            if (h != IntPtr.Zero)
            {
                try
                {
                    var sb = new StringBuilder(1024);
                    int size = sb.Capacity;
                    if (QueryFullProcessImageName(h, 0, sb, ref size)) path = sb.ToString(0, size);
                }
                finally { CloseHandle(h); }
            }
            if (name == null && path == null) return null;
            return new ProcInfo(name ?? System.IO.Path.GetFileNameWithoutExtension(path), path);
        }

        /// <summary>the check on this PC right now</summary>
        public static PortReport Now(int basePort, int ourPid, bool ourRunning, bool settled, string ourDir)
            => Check(TcpListeners(), basePort, ourPid, ourRunning, settled, ourDir, Who, ApolloInstalled());

        /// <summary>a Windows service named Apollo, or running from an Apollo folder, is installed (the services' registry, read-only; no admin)</summary>
        public static bool ApolloInstalled()
        {
            try
            {
                using (var svcs = Microsoft.Win32.Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Services"))
                {
                    if (svcs == null) return false;
                    foreach (var n in svcs.GetSubKeyNames())
                    {
                        if (n.IndexOf("apollo", StringComparison.OrdinalIgnoreCase) >= 0) return true;
                        using (var k = svcs.OpenSubKey(n))
                            if ((k?.GetValue("ImagePath") as string)?.IndexOf(@"\Apollo\", StringComparison.OrdinalIgnoreCase) >= 0) return true;
                    }
                }
            }
            catch (Exception) { }
            return false;
        }

        [DllImport("iphlpapi.dll", SetLastError = true)]
        static extern uint GetExtendedTcpTable(IntPtr table, ref int size, bool sort, int af, int tableClass, int reserved);
        [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr OpenProcess(int access, bool inherit, int pid);
        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern bool QueryFullProcessImageName(IntPtr h, int flags, StringBuilder name, ref int size);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
    }
}
