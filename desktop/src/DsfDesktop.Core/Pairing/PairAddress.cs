using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Text;

namespace DsfDesktop.Pairing
{
    /// <summary>
    /// THE PC'S ADDRESS INSIDE THE PAIRING CODE (the user's pick 2026-11-18: "Address inside the pairing code"): a Thor that can't see the
    /// PC's mDNS advert (another subnet, a guest / mesh Wi-Fi that drops multicast, Tailscale / WireGuard) pairs by typing the code alone.
    /// The code Desktop shows = the 6-digit secret (<see cref="PairingCodes"/>, unchanged) + this tail, digits only (the Thor's keypad):
    /// <code>
    ///   tail  = entry+ check        (empty when Desktop knows no address: the code is the 6 digits, as before)
    ///   entry = kind digit + payload
    ///     1 + 5 digits   192.168.0.0/16   (the offset in the range)
    ///     2 + 8 digits   10.0.0.0/8
    ///     3 + 7 digits   172.16.0.0/12
    ///     4 + 7 digits   100.64.0.0/10    (Tailscale's CGNAT range)
    ///     5 + 10 digits  any IPv4
    ///     6 + 5 digits   the bridge port, when it is not 48210 (at most once)
    ///     0, 7, 8, 9     reserved: a Thor refuses the code
    ///   check = one Damm check digit over the tail's entries (never over the secret)
    /// </code>
    /// Example: secret 482913, LAN 192.168.1.23 -> tail "1" + "00279" + check "0" -> shown "482 913 · 100 2790" (13 digits); with Tailscale
    /// 100.71.133.93 too: "482 913 · 100 279 404 928 933" (21).
    /// The address is only where to dial: the secret's proof (HMAC keyed by the 6 secret digits only) and the certificate pins are what
    /// the Thor trusts, exactly as for a PC it found by mDNS (STREAM_BRIDGE_PROTOCOL.md section 3).
    /// </summary>
    public static class PairAddress
    {
        public const int DefaultBridgePort = 48210;
        /// <summary>at most this many addresses go in a code (the home network's and Tailscale's)</summary>
        public const int MaxAddresses = 2;
        public const char PortKind = '6';

        struct Kind { public char K; public uint Base; public int Digits; public ulong Count; }

        static readonly Kind[] Kinds =
        {
            new Kind { K = '1', Base = 0xC0A80000u, Digits = 5, Count = 1UL << 16 },
            new Kind { K = '2', Base = 0x0A000000u, Digits = 8, Count = 1UL << 24 },
            new Kind { K = '3', Base = 0xAC100000u, Digits = 7, Count = 1UL << 20 },
            new Kind { K = '4', Base = 0x64400000u, Digits = 7, Count = 1UL << 22 },
            new Kind { K = '5', Base = 0u, Digits = 10, Count = 1UL << 32 },
        };

        static uint ToUint(IPAddress a)
        {
            var b = a.GetAddressBytes();
            return ((uint)b[0] << 24) | ((uint)b[1] << 16) | ((uint)b[2] << 8) | b[3];
        }

        static IPAddress FromUint(uint v) => new IPAddress(new[] { (byte)(v >> 24), (byte)(v >> 16), (byte)(v >> 8), (byte)v });

        /// <summary>the tail for these IPv4 addresses (the first <see cref="MaxAddresses"/> distinct ones, in order) and the bridge port;
        /// "" when there is no usable address</summary>
        public static string Encode(IEnumerable<IPAddress> addresses, int bridgePort = DefaultBridgePort)
        {
            var sb = new StringBuilder();
            var used = new HashSet<uint>();
            foreach (var a in addresses ?? Enumerable.Empty<IPAddress>())
            {
                if (a == null || a.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork) continue;
                var v = ToUint(a);
                if (v == 0 || IPAddress.IsLoopback(a) || !used.Add(v)) continue;
                var k = Kinds.First(x => v >= x.Base && v - (ulong)x.Base < x.Count);
                sb.Append(k.K).Append((v - k.Base).ToString().PadLeft(k.Digits, '0'));
                if (used.Count >= MaxAddresses) break;
            }
            if (sb.Length == 0) return "";
            if (bridgePort != DefaultBridgePort && bridgePort > 0 && bridgePort <= 65535) sb.Append(PortKind).Append(bridgePort.ToString("D5"));
            sb.Append(Damm(sb.ToString()));
            return sb.ToString();
        }

        /// <summary>a tail back to its addresses and bridge port; false = not a tail Desktop makes (a typo: the check digit, a length,
        /// a range or a reserved kind). An empty tail is valid: no address, the default port.</summary>
        public static bool TryDecode(string tail, out List<IPAddress> addresses, out int bridgePort)
        {
            addresses = new List<IPAddress>();
            bridgePort = DefaultBridgePort;
            tail = tail ?? "";
            if (tail.Length == 0) return true;
            if (tail.Length < 2 || tail.Any(c => c < '0' || c > '9') || Damm(tail) != '0') return false;
            var body = tail.Substring(0, tail.Length - 1);
            bool port = false;
            int i = 0;
            while (i < body.Length)
            {
                var c = body[i++];
                if (c == PortKind)
                {
                    if (port || i + 5 > body.Length) return false;
                    var p = int.Parse(body.Substring(i, 5));
                    if (p < 1 || p > 65535) return false;
                    bridgePort = p; port = true; i += 5;
                    continue;
                }
                var k = Kinds.FirstOrDefault(x => x.K == c);
                if (k.K == '\0' || i + k.Digits > body.Length) return false;
                var off = ulong.Parse(body.Substring(i, k.Digits));
                if (off >= k.Count) return false;
                i += k.Digits;
                addresses.Add(FromUint((uint)(k.Base + off)));
            }
            return addresses.Count > 0;
        }

        /// <summary>the code as Desktop shows it: "482 913" alone, or "482 913 · 100 2790" (the tail in threes, a last four instead of a
        /// lone digit)</summary>
        public static string Display(string secret, string tail)
        {
            var s = PairingCodes.Format(secret);
            return string.IsNullOrEmpty(tail) ? s : s + " · " + Groups(tail);
        }

        public static string Groups(string tail)
        {
            var parts = new List<string>();
            int i = 0;
            while (i < tail.Length)
            {
                int n = tail.Length - i == 4 ? 4 : Math.Min(3, tail.Length - i);
                parts.Add(tail.Substring(i, n));
                i += n;
            }
            return string.Join(" ", parts);
        }

        // Damm's quasigroup (order 10, weakly totally anti-symmetric): every single-digit error and every swap of neighbours is caught
        static readonly int[,] Q =
        {
            { 0, 3, 1, 7, 5, 9, 8, 6, 4, 2 }, { 7, 0, 9, 2, 1, 5, 4, 8, 6, 3 }, { 4, 2, 0, 6, 8, 7, 1, 3, 5, 9 }, { 1, 7, 5, 0, 9, 8, 3, 4, 2, 6 },
            { 6, 1, 2, 3, 0, 4, 5, 9, 7, 8 }, { 3, 6, 7, 4, 2, 0, 9, 5, 8, 1 }, { 5, 8, 6, 9, 7, 2, 0, 1, 3, 4 }, { 8, 9, 4, 5, 3, 6, 2, 0, 1, 7 },
            { 9, 4, 3, 8, 6, 1, 7, 2, 0, 5 }, { 2, 5, 8, 1, 4, 3, 6, 7, 9, 0 },
        };

        /// <summary>the Damm check digit of <paramref name="digits"/> (digits followed by their check digit give '0')</summary>
        public static char Damm(string digits)
        {
            int x = 0;
            foreach (var c in digits) x = Q[x, c - '0'];
            return (char)('0' + x);
        }
    }
}
