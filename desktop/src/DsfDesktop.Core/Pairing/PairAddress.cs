using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Text;

namespace DsfDesktop.Pairing
{
    /// <summary>
    /// "OTHER NETWORK": THE PC'S ADDRESS AS A SECOND, SHORT CODE (the user's picks 2026-11-18: "Address inside the pairing code", then
    /// design "B: Two steps"). Everyone types the 6-digit secret (<see cref="PairingCodes"/>, unchanged) exactly as before. A Thor that
    /// can't see the PC's mDNS advert (another subnet, a guest / mesh Wi-Fi that drops multicast, Tailscale / WireGuard) also types this
    /// network code, which Desktop shows under the secret, quieter, as "Other network". Digits only (the Thor's keypad), always whole
    /// groups of 3:
    /// <code>
    ///   net   = entry+ check
    ///   entry = 1 + 5 digits   192.168.0.0/16   (the offset in the range)
    ///         | 2 + 8 digits   10.0.0.0/8
    ///         | 3 + 7 digits   172.16.0.0/12
    ///         | 4 + 7 digits   100.64.0.0/10    (Tailscale's range)
    ///         | 5 + 10 digits  any IPv4
    ///         | 6 + 5 digits   the bridge port, when it is not 48210 (at most once; Desktop puts it first)
    ///         | 7 + 5 + 7      192.168.0.0/16 then 100.64.0.0/10: the home network, then Tailscale (the common pair)
    ///         | 0, 8, 9        reserved: a Thor refuses the code
    ///   check = 1 to 3 digits that bring the code to whole groups of 3, the last always Damm's check digit over everything before
    ///           it (so Damm over the whole code gives 0, however its kind digits read):
    ///             1 = Damm; 2 = Damm of the entries read backwards, then Damm; 3 = ISO 7064 MOD 97-10's two (as an IBAN's), then Damm
    ///           except the commonest code, a lone "1" entry (6 digits), which has none: it stays at two groups
    /// </code>
    /// Examples: home 192.168.1.23 = "100 279" (6 digits, no check); with Tailscale 100.71.133.93 too = "700 279 049 289 390" (15;
    /// the vectors are in PairAddressTests). Every single wrong digit and every swap of neighbours is caught wherever there is a check
    /// (the whole-code Damm; a wrong kind digit that moves where the entries end is caught the same way); in the 6-digit code only a wrong
    /// kind digit or an offset past 65535 is (a wrong address there just doesn't answer as this PC; see the trust note).
    /// The address is only where to dial: the secret's proof (HMAC keyed by the 6 secret digits only) and the certificate pins are what
    /// the Thor trusts, exactly as for a PC it found by mDNS (STREAM_BRIDGE_PROTOCOL.md section 3a).
    /// </summary>
    public static class PairAddress
    {
        public const int DefaultBridgePort = 48210;
        /// <summary>at most this many addresses go in a code (the home network's and Tailscale's)</summary>
        public const int MaxAddresses = 2;
        public const char PortKind = '6';
        public const char HomeTailscaleKind = '7';
        /// <summary>the longest network code (a port + two "any IPv4" entries + 2 check digits)</summary>
        public const int MaxLength = 30;

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

        static Kind KindOf(uint v) => Kinds.First(x => v >= x.Base && v - (ulong)x.Base < x.Count);

        /// <summary>the network code for these IPv4 addresses (the first <see cref="MaxAddresses"/> distinct ones, in order) and the
        /// bridge port; "" when there is no usable address</summary>
        public static string Encode(IEnumerable<IPAddress> addresses, int bridgePort = DefaultBridgePort)
        {
            var used = new List<uint>();
            foreach (var a in addresses ?? Enumerable.Empty<IPAddress>())
            {
                if (a == null || a.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork) continue;
                var v = ToUint(a);
                if (v == 0 || IPAddress.IsLoopback(a) || used.Contains(v)) continue;
                used.Add(v);
                if (used.Count >= MaxAddresses) break;
            }
            if (used.Count == 0) return "";
            var sb = new StringBuilder();
            if (bridgePort != DefaultBridgePort && bridgePort > 0 && bridgePort <= 65535) sb.Append(PortKind).Append(bridgePort.ToString("D5"));
            if (used.Count == 2 && KindOf(used[0]).K == '1' && KindOf(used[1]).K == '4')
                sb.Append(HomeTailscaleKind).Append((used[0] - 0xC0A80000u).ToString("D5")).Append((used[1] - 0x64400000u).ToString("D7"));
            else
                foreach (var v in used) { var k = KindOf(v); sb.Append(k.K).Append((v - k.Base).ToString().PadLeft(k.Digits, '0')); }
            var body = sb.ToString();
            return body + Check(body, CheckCount(body));
        }

        /// <summary>how many check digits follow [body] (its entries): enough for whole groups of 3, 1 to 3; none for a lone home
        /// entry (the 6-digit code)</summary>
        public static int CheckCount(string body)
        {
            if (body.Length == 6 && body[0] == '1') return 0;
            int n = (3 - body.Length % 3) % 3;
            return n == 0 ? 3 : n;
        }

        /// <summary>the [count] check digits of [body]</summary>
        public static string Check(string body, int count)
        {
            switch (count)
            {
                case 0: return "";
                case 1: return Damm(body).ToString();
                case 2: var r = new string(body.Reverse().ToArray()); var d = Damm(r).ToString(); return d + Damm(body + d);
                default: var m = Mod97(body); return m + Damm(body + m);
            }
        }

        /// <summary>a network code back to its addresses and bridge port; false = not a code Desktop makes (a typo: a check digit, a
        /// length, a range or a reserved kind). An empty code is valid: no address, the default port (the 6 digits alone).</summary>
        public static bool TryDecode(string net, out List<IPAddress> addresses, out int bridgePort)
        {
            addresses = new List<IPAddress>();
            bridgePort = DefaultBridgePort;
            net = net ?? "";
            if (net.Length == 0) return true;
            if (net.Length < 6 || net.Length > MaxLength || net.Length % 3 != 0 || net.Any(c => c < '0' || c > '9')) return false;
            bool port = false;
            int i = 0;
            // entries are 6 digits or more and the check 3 at most: the code's length says where the entries stop
            while (net.Length - i > 3)
            {
                var c = net[i++];
                if (c == PortKind)
                {
                    if (port || i + 5 > net.Length) return false;
                    var p = int.Parse(net.Substring(i, 5));
                    if (p < 1 || p > 65535) return false;
                    bridgePort = p; port = true; i += 5;
                    continue;
                }
                if (c == HomeTailscaleKind)
                {
                    if (i + 12 > net.Length) return false;
                    var home = ulong.Parse(net.Substring(i, 5));
                    var ts = ulong.Parse(net.Substring(i + 5, 7));
                    if (home >= 1UL << 16 || ts >= 1UL << 22) return false;
                    addresses.Add(FromUint((uint)(0xC0A80000u + home)));
                    addresses.Add(FromUint((uint)(0x64400000u + ts)));
                    i += 12;
                    continue;
                }
                var k = Kinds.FirstOrDefault(x => x.K == c);
                if (k.K == '\0' || i + k.Digits > net.Length) return false;
                var off = ulong.Parse(net.Substring(i, k.Digits));
                if (off >= k.Count) return false;
                i += k.Digits;
                addresses.Add(FromUint((uint)(k.Base + off)));
            }
            var body = net.Substring(0, i);
            if (addresses.Count == 0 || net.Length - i != CheckCount(body)) return false;
            return net.Substring(i) == Check(body, net.Length - i);
        }

        /// <summary>the code as the window gets it: "482 913" alone, or "482 913 · 100 279" (the secret, then the network code in
        /// threes; the window draws each part as boxes)</summary>
        public static string Display(string secret, string net)
        {
            var s = PairingCodes.Format(secret);
            return string.IsNullOrEmpty(net) ? s : s + " · " + Groups(net);
        }

        /// <summary>"700279404928939" -> "700 279 404 928 939"</summary>
        public static string Groups(string digits)
        {
            var parts = new List<string>();
            for (int i = 0; i < digits.Length; i += 3) parts.Add(digits.Substring(i, Math.Min(3, digits.Length - i)));
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

        /// <summary>ISO 7064 MOD 97-10's two check digits (as an IBAN's): 98 - (digits x 100 mod 97); every single wrong digit and every
        /// swap of neighbours is caught (97 is prime and divides no 10^k x d, |d| &lt;= 9)</summary>
        public static string Mod97(string digits)
        {
            int r = 0;
            foreach (var c in digits) r = (r * 10 + (c - '0')) % 97;
            r = r * 100 % 97;
            return (98 - r).ToString("D2");
        }
    }
}
