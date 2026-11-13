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
        /// <summary>null | "other_sunshine" | "other_app" | "not_listening"</summary>
        public string Problem;
        /// <summary>the window's line, "" when fine</summary>
        public string Line = "";
        /// <summary>the window's second line: how to stop it</summary>
        public string Hint = "";
        /// <summary>the Thor's reason ("pc busy: another Sunshine")</summary>
        public string ThorWhy = "";
        /// <summary>for the log: every watched port and its owner</summary>
        public string Detail = "";
        /// <summary>the foreign ports (sorted)</summary>
        public List<int> ForeignPorts = new List<int>();
        public bool Services;
    }

    /// <summary>
    /// A separately installed Sunshine (its Windows service "SunshineService" / sunshinesvc.exe) keeps the stock GameStream ports
    /// 47984/47989/47990/48010 (the user's PC, 2026-11-13). Our bundled sunshine.exe then moves to another base or can't bind, and a
    /// Thor that reaches the stock ports talks to the foreign Sunshine, which never paired it, and sits on "PC found". This tells who
    /// owns the ports and says so. It never stops, kills or changes other software: the user does that (Services).
    /// </summary>
    public static class PortGuard
    {
        /// <summary>stock GameStream TCP: https, http, web UI, RTSP (base 47989: -5, 0, +1, +21)</summary>
        public static readonly int[] StockPorts = { 47984, 47989, 47990, 48010 };

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
            if (name.IndexOf("sunshine", StringComparison.OrdinalIgnoreCase) >= 0) return PortOwner.OtherSunshine;
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
        /// </summary>
        public static PortReport Check(IEnumerable<TcpListenerRow> rows, int basePort, int ourPid, bool ourRunning, bool settled,
                                       string ourDir, Func<int, ProcInfo> who)
        {
            var byPort = new Dictionary<int, int>();
            foreach (var r in rows ?? Enumerable.Empty<TcpListenerRow>())
                if (!byPort.ContainsKey(r.Port)) byPort[r.Port] = r.Pid;
            var cache = new Dictionary<int, ProcInfo>();
            ProcInfo Who(int pid)
            {
                if (!cache.TryGetValue(pid, out var p)) { try { p = who?.Invoke(pid); } catch (Exception) { p = null; } cache[pid] = p; }
                return p;
            }
            PortOwner Owner(int port, out int pid)
            {
                if (!byPort.TryGetValue(port, out pid)) return PortOwner.Free;
                return Classify(pid, Who(pid) ?? new ProcInfo(null, null), ourPid, ourDir);
            }

            var ours = OurPorts(basePort);
            var watched = StockPorts.Concat(ours).Distinct().OrderBy(p => p).ToList();
            var rep = new PortReport();
            var detail = new List<string>();
            var foreign = new List<(int port, PortOwner kind, string name)>();
            foreach (var port in watched)
            {
                var k = Owner(port, out int pid);
                var name = k == PortOwner.Free ? null : Who(pid)?.Name ?? "?";
                detail.Add(port + " " + (k == PortOwner.Free ? "free" : k.ToString().ToLowerInvariant() + " (pid " + pid + " " + name + ")"));
                if (k == PortOwner.OtherSunshine || k == PortOwner.OtherApp || k == PortOwner.Unknown) foreign.Add((port, k, name));
            }
            rep.Detail = string.Join(", ", detail);
            rep.ForeignPorts = foreign.Select(f => f.port).ToList();

            // our own Sunshine up for a while but not on its base port: it couldn't bind (or it isn't answering)
            bool oursMissing = ourRunning && settled && basePort > 0 && Owner(basePort, out _) != PortOwner.Ours;

            if (foreign.Count > 0)
            {
                var ports = string.Join(", ", foreign.Select(f => f.port));
                if (foreign.Any(f => f.kind == PortOwner.OtherSunshine))
                {
                    rep.Problem = "other_sunshine";
                    rep.Line = "Another Sunshine is using the streaming ports (" + ports + ")";
                    rep.Hint = "Fix: Services > Sunshine Service > Startup type Disabled (or uninstall Sunshine), then restart the PC.";
                    rep.ThorWhy = "pc busy: another Sunshine";
                }
                else
                {
                    var names = string.Join(", ", foreign.Select(f => f.kind == PortOwner.Unknown || f.name == "?" ? "an unknown program" : f.name + ".exe").Distinct());
                    rep.Problem = "other_app";
                    rep.Line = "Another program (" + names + ") is using the streaming ports (" + ports + ")";
                    rep.Hint = "Close it, or stop its service in Services.";
                    rep.ThorWhy = "pc busy: another program has the streaming ports";
                }
                rep.Services = true;
                if (oursMissing) rep.Line += "; DS Forever's streaming host couldn't open its ports";
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
            => Check(TcpListeners(), basePort, ourPid, ourRunning, settled, ourDir, Who);

        [DllImport("iphlpapi.dll", SetLastError = true)]
        static extern uint GetExtendedTcpTable(IntPtr table, ref int size, bool sort, int af, int tableClass, int reserved);
        [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr OpenProcess(int access, bool inherit, int pid);
        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern bool QueryFullProcessImageName(IntPtr h, int flags, StringBuilder name, ref int size);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
    }
}
