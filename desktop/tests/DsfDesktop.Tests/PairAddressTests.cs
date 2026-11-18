using System.Collections.Generic;
using System.Linq;
using System.Net;
using DsfDesktop.Pairing;

namespace DsfDesktop.Tests
{
    /// <summary>"Other network": the PC's address as a second, short code (PairAddress; the user's picks 2026-11-18, design B). The
    /// Thor's PairCode decodes the same vectors (dsforever PairCodeTest).</summary>
    public static class PairAddressTests
    {
        static List<IPAddress> Ips(params string[] a) => a.Select(IPAddress.Parse).ToList();

        [Test]
        public static void TheDocumentedVectors()
        {
            A.Eq("100279", PairAddress.Encode(Ips("192.168.1.23")), "home only: 6 digits, no check");
            A.Eq("482 913 · 100 279", PairAddress.Display("482913", "100279"));
            var both = PairAddress.Encode(Ips("192.168.1.23", "100.71.133.93"));
            A.Eq("700279049289390", both, "home + Tailscale: kind 7, 13 digits + 2 check digits");
            A.Eq("482 913 · 700 279 049 289 390", PairAddress.Display("482913", both));
            A.Eq("482 913", PairAddress.Display("482913", ""), "no address: the code is the 6 digits, as before");
            A.Eq('4', PairAddress.Damm("572"), "Damm's own example");
            A.Eq("82", PairAddress.Mod97("32142829123456987654321611"), "ISO 7064 as an IBAN uses it: GB82 WEST 1234 5698 7654 32");
        }

        [Test]
        public static void EveryCodeIsWholeGroupsOfThree()
        {
            var lens = new Dictionary<string, int>
            {
                { "192.168.1.23", 6 }, { "10.1.2.3", 12 }, { "172.20.1.2", 9 }, { "100.100.1.2", 9 }, { "8.8.4.4", 12 },
            };
            foreach (var kv in lens)
            {
                A.Eq(kv.Value, PairAddress.Encode(Ips(kv.Key)).Length, kv.Key);
                A.Eq(0, PairAddress.Encode(Ips(kv.Key), 48213).Length % 3, kv.Key + " + a port");
            }
            A.Eq(15, PairAddress.Encode(Ips("192.168.1.23", "100.71.133.93")).Length);
            A.Eq(18, PairAddress.Encode(Ips("10.0.0.5", "100.71.133.93")).Length, "another home range + Tailscale: two entries");
            A.Eq(15, PairAddress.Encode(Ips("192.168.1.23"), 48213).Length, "a port + home");
        }

        [Test]
        public static void EveryKindRoundTrips()
        {
            var cases = new[]
            {
                "192.168.0.0", "192.168.255.255", "10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.255", "100.64.0.1", "100.127.255.255",
                "8.8.4.4", "255.255.255.255", "172.32.0.1", "100.128.0.1", "1.2.3.4",
            };
            foreach (var c in cases)
            {
                foreach (var port in new[] { 48210, 48211, 52000, 1, 65535 })
                {
                    var net = PairAddress.Encode(Ips(c), port);
                    A.True(PairAddress.TryDecode(net, out var got, out var p), c + " " + net);
                    A.Eq(c, got.Single().ToString(), net);
                    A.Eq(port, p, net);
                    A.True(net.All(char.IsDigit) && net.Length % 3 == 0, net);
                    foreach (var c2 in new[] { "100.71.133.93", "10.9.8.7" })
                    {
                        if (c2 == c) continue;
                        var two = PairAddress.Encode(Ips(c, c2), port);
                        A.True(PairAddress.TryDecode(two, out var got2, out var p2), c + " " + c2 + " " + two);
                        A.Eq(c + "," + c2, string.Join(",", got2.Select(x => x.ToString())), two);
                        A.Eq(port, p2, two);
                        A.True(two.Length % 3 == 0 && two.Length <= PairAddress.MaxLength, two);
                    }
                }
            }
            A.Eq('1', PairAddress.Encode(Ips("192.168.4.4"))[0]);
            A.Eq('2', PairAddress.Encode(Ips("10.1.2.3"))[0]);
            A.Eq('3', PairAddress.Encode(Ips("172.20.1.2"))[0]);
            A.Eq('4', PairAddress.Encode(Ips("100.100.1.2"))[0]);
            A.Eq('5', PairAddress.Encode(Ips("172.32.1.2"))[0], "outside 172.16/12: any IPv4");
            A.Eq('7', PairAddress.Encode(Ips("192.168.4.4", "100.100.1.2"))[0], "home then Tailscale");
            A.Eq('1', PairAddress.Encode(Ips("100.100.1.2", "192.168.4.4"))[1 + 7], "Tailscale first: two plain entries, the order kept");
            A.Eq('6', PairAddress.Encode(Ips("192.168.4.4"), 50000)[0], "a port goes first");
        }

        [Test]
        public static void TwoAddressesAtMostAndNoDuplicates()
        {
            var net = PairAddress.Encode(Ips("192.168.1.23", "192.168.1.23", "100.71.133.93", "10.0.0.5"));
            A.True(PairAddress.TryDecode(net, out var got, out _));
            A.Eq("192.168.1.23,100.71.133.93", string.Join(",", got.Select(x => x.ToString())));
            A.Eq("", PairAddress.Encode(Ips()));
            A.Eq("", PairAddress.Encode(Ips("127.0.0.1", "0.0.0.0", "::1")), "loopback, any and IPv6 never go in a code");
        }

        static IEnumerable<string> Typos(string net)
        {
            for (int i = 0; i < net.Length; i++)
            {
                for (char d = '0'; d <= '9'; d++) if (d != net[i]) yield return net.Substring(0, i) + d + net.Substring(i + 1);
                if (i + 1 < net.Length && net[i] != net[i + 1]) yield return net.Substring(0, i) + net[i + 1] + net[i] + net.Substring(i + 2);
            }
        }

        [Test]
        public static void EveryTypoIsCaughtWhereThereIsACheck()
        {
            var codes = new List<string>();
            foreach (var a in new[] { "192.168.1.23", "10.20.30.40", "172.20.1.2", "100.71.133.93", "8.8.4.4" })
                foreach (var port in new[] { 48210, 48213 })
                {
                    codes.Add(PairAddress.Encode(Ips(a), port));
                    codes.Add(PairAddress.Encode(Ips(a, a == "100.71.133.93" ? "192.168.7.7" : "100.71.133.93"), port));
                }
            foreach (var net in codes)
            {
                if (net.Length == 6) continue;   // the 6-digit home code: no check (below)
                foreach (var typo in Typos(net)) A.False(PairAddress.TryDecode(typo, out _, out _), net + " -> " + typo);
                A.False(PairAddress.TryDecode(net.Substring(0, net.Length - 1), out _, out _), "a digit missing");
                A.False(PairAddress.TryDecode(net + "0", out _, out _), "a digit too many");
                A.False(PairAddress.TryDecode(net.Substring(0, net.Length - 3), out _, out _), "a group missing");
            }
            // the 6-digit home code has no check digit (the user's call: two groups of 3): a wrong kind digit or an offset past 65535
            // is still refused; another offset is another home address, which won't answer as this PC
            var home = PairAddress.Encode(Ips("192.168.1.23"));
            A.False(PairAddress.TryDecode("700279", out _, out _));
            A.False(PairAddress.TryDecode("170000", out _, out _), "past 192.168.255.255");
            A.True(PairAddress.TryDecode("100278", out var other, out _) && other.Single().ToString() == "192.168.1.22");
            A.False(PairAddress.TryDecode(home.Substring(0, 5), out _, out _));
        }

        [Test]
        public static void ReservedKindsAndBadRangesAreRefused()
        {
            foreach (var body in new[] { "000000000", "800000", "900000000", "1999999", "6000001002790", "600000", "64821" })
                foreach (var n in new[] { 0, 1, 2, 3 })
                    A.False(PairAddress.TryDecode(body + PairAddress.Check(body, n), out _, out _), body + "+" + n);
            // a port alone (no address) is no address
            A.False(PairAddress.TryDecode("648211" + PairAddress.Check("648211", 3), out _, out _));
            A.True(PairAddress.TryDecode("", out var none, out var p0) && none.Count == 0 && p0 == 48210, "no network code = an old 6-digit code");
            A.False(PairAddress.TryDecode("1002790", out _, out _), "407e162d's 7-digit tail is not a code: not whole groups");
        }

        [Test]
        public static void TheProofStillUsesOnlyTheSecret()
        {
            // the Thor proves the 6 secret digits; the network code is public and never keys the HMAC
            var p = new PairingCodes();
            var secret = p.Current;
            A.Eq(PairCheck.Ok, p.Verify("n1", "thor", PairingCodes.Proof(secret, "n1", "thor")));
            var p2 = new PairingCodes();
            var full = p2.Current + PairAddress.Encode(Ips("192.168.1.23"));
            A.Eq(PairCheck.Wrong, p2.Verify("n1", "thor", PairingCodes.Proof(full, "n1", "thor")), "the whole code is not the key");
        }
    }
}
