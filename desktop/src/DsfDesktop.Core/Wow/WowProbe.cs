using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Net;
using System.Runtime.InteropServices;
using System.Text;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// What Windows can say about the game and Battle.net without touching either: the game's TCP connections (by its process id,
    /// from the system's connection table) and Battle.net's windows (their titles only). Nothing is read from Battle.net's files,
    /// nothing is typed, no credential is ever seen.
    /// </summary>
    public static class WowProbe
    {
        // ---------------------------------------------------------------- the game's connections

        /// <summary>(realm-like, Battle.net auth on 1119) established connections of process [pid]; loopback, 80 and 443 are not counted</summary>
        public static (int game, int auth) Connections(int pid)
        {
            int game = 0, auth = 0;
            foreach (var (remote, port) in Established(pid))
            {
                if (IPAddress.IsLoopback(remote)) continue;
                if (port == 80 || port == 443) continue;
                if (port == 1119) auth++; else game++;
            }
            return (game, auth);
        }

        const int AF_INET = 2, AF_INET6 = 23;
        const int TCP_TABLE_OWNER_PID_CONNECTIONS = 4;
        const uint MIB_TCP_STATE_ESTAB = 5;

        [DllImport("iphlpapi.dll", SetLastError = true)]
        static extern uint GetExtendedTcpTable(IntPtr table, ref int size, bool order, int af, int tableClass, uint reserved);

        static IEnumerable<(IPAddress, int)> Established(int pid)
        {
            var list = new List<(IPAddress, int)>();
            foreach (var af in new[] { AF_INET, AF_INET6 })
            {
                int size = 0;
                GetExtendedTcpTable(IntPtr.Zero, ref size, false, af, TCP_TABLE_OWNER_PID_CONNECTIONS, 0);
                if (size <= 0) continue;
                var buf = Marshal.AllocHGlobal(size);
                try
                {
                    if (GetExtendedTcpTable(buf, ref size, false, af, TCP_TABLE_OWNER_PID_CONNECTIONS, 0) != 0) continue;
                    int n = Marshal.ReadInt32(buf);
                    if (af == AF_INET)
                    {
                        // MIB_TCPROW_OWNER_PID: state, localAddr, localPort, remoteAddr, remotePort, owningPid (6 x 4 bytes)
                        for (int i = 0; i < n; i++)
                        {
                            var row = buf + 4 + i * 24;
                            if ((uint)Marshal.ReadInt32(row) != MIB_TCP_STATE_ESTAB || Marshal.ReadInt32(row + 20) != pid) continue;
                            var addr = new IPAddress(BitConverter.GetBytes(Marshal.ReadInt32(row + 12)));
                            list.Add((addr, Port(Marshal.ReadInt32(row + 16))));
                        }
                    }
                    else
                    {
                        // MIB_TCP6ROW_OWNER_PID: localAddr[16], localScope, localPort, remoteAddr[16], remoteScope, remotePort, state, pid
                        for (int i = 0; i < n; i++)
                        {
                            var row = buf + 4 + i * 56;
                            if ((uint)Marshal.ReadInt32(row + 48) != MIB_TCP_STATE_ESTAB || Marshal.ReadInt32(row + 52) != pid) continue;
                            var b = new byte[16];
                            Marshal.Copy(row + 24, b, 0, 16);
                            var addr = new IPAddress(b);
                            if (addr.IsIPv4MappedToIPv6) addr = addr.MapToIPv4();
                            list.Add((addr, Port(Marshal.ReadInt32(row + 44))));
                        }
                    }
                }
                finally { Marshal.FreeHGlobal(buf); }
            }
            return list;
        }

        /// <summary>the table keeps ports in network byte order in the low 16 bits</summary>
        static int Port(int raw) => ((raw & 0xFF) << 8) | ((raw >> 8) & 0xFF);

        // ---------------------------------------------------------------- Battle.net's windows

        public sealed class BnetWindows
        {
            public bool Running;
            public double UptimeS;
            /// <summary>its "Battle.net Login" window (signed out), Zero = none</summary>
            public IntPtr Login;
            public bool Any;
        }

        /// <summary>the title of Battle.net's sign-in window (measured on the user's PC 2026-10-28, Battle.net in English)</summary>
        public const string LoginTitle = "Battle.net Login";

        public static BnetWindows Bnet()
        {
            var r = new BnetWindows();
            Process[] ps;
            try { ps = Process.GetProcessesByName("Battle.net"); } catch (Exception) { return r; }
            if (ps.Length == 0) return r;
            r.Running = true;
            var pids = new HashSet<uint>(ps.Select(p => (uint)p.Id));
            try { r.UptimeS = ps.Select(p => { try { return (DateTime.Now - p.StartTime).TotalSeconds; } catch (Exception) { return 0.0; } }).Max(); }
            catch (Exception) { }
            foreach (var p in ps) p.Dispose();
            EnumWindows((h, l) =>
            {
                GetWindowThreadProcessId(h, out uint pid);
                if (!pids.Contains(pid)) return true;
                var sb = new StringBuilder(256);
                GetWindowText(h, sb, sb.Capacity);
                var title = sb.ToString();
                if (title.Length > 0) r.Any = true;
                if (IsWindowVisible(h) && title.StartsWith(LoginTitle, StringComparison.OrdinalIgnoreCase)) r.Login = h;
                return true;
            }, IntPtr.Zero);
            return r;
        }

        /// <summary>start the Battle.net app itself, plainly (never --exec: Battle.net's launch commands started RETAIL once)</summary>
        public static string StartBattleNet(string exe)
        {
            if (exe == null) return "Battle.net isn't installed";
            try { Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true })?.Dispose(); return null; }
            catch (Exception e) { return e.Message; }
        }

        delegate bool EnumProc(IntPtr h, IntPtr l);
        [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc p, IntPtr l);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
        [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
        [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    }
}
