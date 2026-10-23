using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Threading;

namespace DsfDesktop.Bridge
{
    /// <summary>a DNS record for the responder (name, type, class with the cache-flush bit, ttl, rdata)</summary>
    public sealed class DnsRecord
    {
        public string Name;
        public ushort Type;
        public ushort Class = 1;
        public uint Ttl;
        public byte[] Data;
        public const ushort A = 1, PTR = 12, TXT = 16, SRV = 33, ANY = 255;
    }

    public sealed class DnsQuestion { public string Name; public ushort Type; public bool UnicastResponse; }

    /// <summary>DNS wire format: the few pieces an mDNS responder needs (names with compression on read, none on write)</summary>
    public static class DnsWire
    {
        public static void WriteName(List<byte> b, string name)
        {
            foreach (var label in name.TrimEnd('.').Split('.'))
            {
                var l = Encoding.UTF8.GetBytes(label);
                if (l.Length > 63) throw new ArgumentException("label too long: " + label);
                b.Add((byte)l.Length);
                b.AddRange(l);
            }
            b.Add(0);
        }

        static void U16(List<byte> b, int v) { b.Add((byte)(v >> 8)); b.Add((byte)v); }
        static void U32(List<byte> b, uint v) { b.Add((byte)(v >> 24)); b.Add((byte)(v >> 16)); b.Add((byte)(v >> 8)); b.Add((byte)v); }

        public static byte[] NameBytes(string name) { var b = new List<byte>(); WriteName(b, name); return b.ToArray(); }

        public static byte[] Srv(ushort port, string target)
        {
            var b = new List<byte>();
            U16(b, 0); U16(b, 0); U16(b, port);
            WriteName(b, target);
            return b.ToArray();
        }

        public static byte[] Txt(IEnumerable<string> items)
        {
            var b = new List<byte>();
            foreach (var s in items)
            {
                var x = Encoding.UTF8.GetBytes(s);
                if (x.Length > 255) continue;
                b.Add((byte)x.Length);
                b.AddRange(x);
            }
            if (b.Count == 0) b.Add(0);
            return b.ToArray();
        }

        /// <summary>a response message (id 0, QR + AA) with the answers and additionals</summary>
        public static byte[] Response(IList<DnsRecord> answers, IList<DnsRecord> additional)
        {
            var b = new List<byte>();
            U16(b, 0); U16(b, 0x8400); U16(b, 0); U16(b, answers.Count); U16(b, 0); U16(b, additional?.Count ?? 0);
            foreach (var r in answers.Concat(additional ?? new DnsRecord[0]))
            {
                WriteName(b, r.Name);
                U16(b, r.Type); U16(b, r.Class); U32(b, r.Ttl);
                U16(b, r.Data.Length);
                b.AddRange(r.Data);
            }
            return b.ToArray();
        }

        public static string ReadName(byte[] m, ref int p)
        {
            var labels = new List<string>();
            int jumps = 0;
            int pos = p;
            bool jumped = false;
            while (true)
            {
                if (pos >= m.Length) throw new FormatException("name past end");
                int len = m[pos];
                if ((len & 0xC0) == 0xC0)
                {
                    if (pos + 1 >= m.Length || ++jumps > 16) throw new FormatException("bad pointer");
                    int target = ((len & 0x3F) << 8) | m[pos + 1];
                    if (!jumped) p = pos + 2;
                    jumped = true;
                    pos = target;
                    continue;
                }
                pos++;
                if (len == 0) break;
                if (pos + len > m.Length) throw new FormatException("label past end");
                labels.Add(Encoding.UTF8.GetString(m, pos, len));
                pos += len;
            }
            if (!jumped) p = pos;
            return string.Join(".", labels);
        }

        /// <summary>the questions of a QUERY message (responses and malformed packets give none)</summary>
        public static List<DnsQuestion> Questions(byte[] m)
        {
            var res = new List<DnsQuestion>();
            try
            {
                if (m.Length < 12) return res;
                int flags = (m[2] << 8) | m[3];
                if ((flags & 0x8000) != 0) return res;    // a response, not a query
                int qd = (m[4] << 8) | m[5];
                int p = 12;
                for (int i = 0; i < qd && i < 64; i++)
                {
                    var name = ReadName(m, ref p);
                    if (p + 4 > m.Length) break;
                    ushort type = (ushort)((m[p] << 8) | m[p + 1]);
                    int cls = (m[p + 2] << 8) | m[p + 3];
                    p += 4;
                    res.Add(new DnsQuestion { Name = name, Type = type, UnicastResponse = (cls & 0x8000) != 0 });
                }
            }
            catch (Exception) { }
            return res;
        }

        /// <summary>the records of a RESPONSE (for tests and diagnostics): name, type, port for SRV, text for TXT</summary>
        public static List<KeyValuePair<string, string>> Answers(byte[] m)
        {
            var res = new List<KeyValuePair<string, string>>();
            int an = ((m[6] << 8) | m[7]) + ((m[10] << 8) | m[11]);
            int p = 12;
            for (int i = 0; i < an; i++)
            {
                var name = ReadName(m, ref p);
                int type = (m[p] << 8) | m[p + 1];
                int len = (m[p + 8] << 8) | m[p + 9];
                p += 10;
                string val;
                if (type == DnsRecord.SRV) { int q = p + 6; val = ((m[p + 4] << 8) | m[p + 5]) + " " + ReadName(m, ref q); }
                else if (type == DnsRecord.PTR) { int q = p; val = ReadName(m, ref q); }
                else if (type == DnsRecord.A) val = new IPAddress(m.Skip(p).Take(4).ToArray()).ToString();
                else if (type == DnsRecord.TXT)
                {
                    var items = new List<string>();
                    for (int q = p; q < p + len;) { int l = m[q++]; items.Add(Encoding.UTF8.GetString(m, q, l)); q += l; }
                    val = string.Join(";", items);
                }
                else val = len + " bytes";
                res.Add(new KeyValuePair<string, string>(type + " " + name, val));
                p += len;
            }
            return res;
        }
    }

    /// <summary>
    /// Advertises the bridge as "&lt;PC&gt;._dsforever._tcp.local" (plan: the Thor finds the PC by mDNS; Sunshine itself already
    /// advertises _nvstream._tcp). A small responder on UDP 5353 shared with Windows' own mDNS (SO_REUSEADDR): it answers PTR / SRV /
    /// TXT / A questions for our names and announces itself at start, and says goodbye (TTL 0) at stop.
    /// TXT: proto, id (host id), pc (name), sunshine (base port), ver.
    /// </summary>
    public sealed class MdnsResponder : IDisposable
    {
        public const string Service = "_dsforever._tcp.local";
        static readonly IPAddress Group = IPAddress.Parse("224.0.0.251");
        const int MdnsPort = 5353;

        readonly string instance, hostName;
        readonly ushort port;
        readonly Func<Dictionary<string, string>> txt;
        UdpClient udp;
        Thread thread;
        Timer again;
        volatile bool running;

        public MdnsResponder(string pcName, ushort port, Func<Dictionary<string, string>> txt)
        {
            var clean = new string((pcName ?? "PC").Where(c => char.IsLetterOrDigit(c) || c == '-').ToArray());
            if (clean.Length == 0) clean = "PC";
            if (clean.Length > 40) clean = clean.Substring(0, 40);
            instance = clean + "." + Service;   // the instance name is the PC's name (protocol section 1)
            // our own host label: never the PC's own name, which Windows answers for itself
            hostName = clean + "-dsforever.local";
            this.port = port;
            this.txt = txt;
        }

        public string InstanceName => instance;
        public string HostName => hostName;

        public static List<IPAddress> LanAddresses()
        {
            var res = new List<IPAddress>();
            try
            {
                foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                    if (ni.NetworkInterfaceType == NetworkInterfaceType.Tunnel) continue;
                    foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                    {
                        var a = ua.Address;
                        if (a.AddressFamily != AddressFamily.InterNetwork || IPAddress.IsLoopback(a)) continue;
                        if (a.GetAddressBytes()[0] == 169 && a.GetAddressBytes()[1] == 254) continue;
                        res.Add(a);
                    }
                }
            }
            catch (Exception) { }
            return res;
        }

        public List<DnsRecord> Records(uint ttl)
        {
            var t = txt?.Invoke() ?? new Dictionary<string, string>();
            var recs = new List<DnsRecord>
            {
                new DnsRecord { Name = Service, Type = DnsRecord.PTR, Ttl = ttl == 0 ? 0u : 4500u, Data = DnsWire.NameBytes(instance) },
                new DnsRecord { Name = instance, Type = DnsRecord.SRV, Class = 0x8001, Ttl = ttl == 0 ? 0u : 120u, Data = DnsWire.Srv(port, hostName) },
                new DnsRecord { Name = instance, Type = DnsRecord.TXT, Class = 0x8001, Ttl = ttl == 0 ? 0u : 4500u, Data = DnsWire.Txt(t.Select(kv => kv.Key + "=" + kv.Value)) },
            };
            foreach (var a in LanAddresses())
                recs.Add(new DnsRecord { Name = hostName, Type = DnsRecord.A, Class = 0x8001, Ttl = ttl == 0 ? 0u : 120u, Data = a.GetAddressBytes() });
            return recs;
        }

        /// <summary>the reply to a query packet, or null when it asks nothing of ours</summary>
        public byte[] Answer(byte[] query)
        {
            var qs = DnsWire.Questions(query);
            if (qs.Count == 0) return null;
            var all = Records(1);
            var answers = new List<DnsRecord>();
            foreach (var q in qs)
            {
                if (q.Name.Equals("_services._dns-sd._udp.local", StringComparison.OrdinalIgnoreCase) && (q.Type == DnsRecord.PTR || q.Type == DnsRecord.ANY))
                {
                    answers.Add(new DnsRecord { Name = q.Name, Type = DnsRecord.PTR, Ttl = 4500, Data = DnsWire.NameBytes(Service) });
                    continue;
                }
                answers.AddRange(all.Where(r => r.Name.Equals(q.Name, StringComparison.OrdinalIgnoreCase) && (q.Type == r.Type || q.Type == DnsRecord.ANY)));
            }
            if (answers.Count == 0) return null;
            var extra = all.Where(r => !answers.Contains(r) && answers.Any(a => a.Type == DnsRecord.PTR && a.Name == Service || a.Type == DnsRecord.SRV)).ToList();
            return DnsWire.Response(answers.Distinct().ToList(), extra);
        }

        public void Start()
        {
            udp = new UdpClient { ExclusiveAddressUse = false };
            udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            udp.Client.Bind(new IPEndPoint(IPAddress.Any, MdnsPort));
            foreach (var a in LanAddresses())
                try { udp.JoinMulticastGroup(Group, a); } catch (Exception e) { Log.I("mdns: join on " + a + ": " + e.Message); }
            udp.MulticastLoopback = true;
            running = true;
            thread = new Thread(Loop) { IsBackground = true, Name = "mdns" };
            thread.Start();
            Announce(4500);
            again = new Timer(_ => { if (running) Announce(4500); }, null, 1000, Timeout.Infinite);   // RFC 6762: announce twice
            Log.I("mdns: advertising " + instance + " -> " + hostName + ":" + port);
        }

        void Announce(uint ttl)
        {
            var msg = DnsWire.Response(Records(ttl), null);
            SendMulticast(msg);
        }

        void SendMulticast(byte[] msg)
        {
            foreach (var a in LanAddresses())
            {
                try
                {
                    udp.Client.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastInterface, a.GetAddressBytes());
                    udp.Send(msg, msg.Length, new IPEndPoint(Group, MdnsPort));
                }
                catch (Exception) { }
            }
        }

        void Loop()
        {
            while (running)
            {
                try
                {
                    var from = new IPEndPoint(IPAddress.Any, 0);
                    var data = udp.Receive(ref from);
                    var reply = Answer(data);
                    if (reply == null) continue;
                    var qs = DnsWire.Questions(data);
                    // a one-shot (legacy) querier asks from another port and wants a unicast reply
                    if (from.Port != MdnsPort || qs.Any(q => q.UnicastResponse))
                    {
                        reply[0] = data[0]; reply[1] = data[1];   // a unicast reply carries the query's id
                        udp.Send(reply, reply.Length, from);
                    }
                    else SendMulticast(reply);
                }
                catch (Exception) { if (!running) return; }
            }
        }

        public void Dispose()
        {
            if (!running) return;
            try { Announce(0); } catch (Exception) { }
            running = false;
            again?.Dispose();
            try { udp?.Close(); } catch (Exception) { }
        }
    }
}
