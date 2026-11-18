using System.Collections.Generic;
using System.Linq;
using System.Net;
using DsfDesktop.Pairing;

namespace DsfDesktop.Tests
{
    /// <summary>the PC's address inside the pairing code (PairAddress; the user's pick 2026-11-18). The Thor's PairCode decodes the same
    /// vectors (dsforever PairCodeTest).</summary>
    public static class PairAddressTests
    {
        static List<IPAddress> Ips(params string[] a) => a.Select(IPAddress.Parse).ToList();

        [Test]
        public static void TheDocumentedVectors()
        {
            A.Eq("1002790", PairAddress.Encode(Ips("192.168.1.23")));
            A.Eq("482 913 · 100 2790", PairAddress.Display("482913", "1002790"));
            A.Eq("100279404928933", PairAddress.Encode(Ips("192.168.1.23", "100.71.133.93")));
            A.Eq("482 913 · 100 279 404 928 933", PairAddress.Display("482913", "100279404928933"));
            A.Eq("482 913", PairAddress.Display("482913", ""), "no address: the code is the 6 digits, as before");
            A.Eq('4', PairAddress.Damm("572"), "Damm's own example");
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
                    var tail = PairAddress.Encode(Ips(c), port);
                    A.True(PairAddress.TryDecode(tail, out var got, out var p), c + " " + tail);
                    A.Eq(c, got.Single().ToString(), tail);
                    A.Eq(port, p, tail);
                    A.True(tail.All(char.IsDigit));
                }
            }
            A.Eq('1', PairAddress.Encode(Ips("192.168.4.4"))[0]);
            A.Eq('2', PairAddress.Encode(Ips("10.1.2.3"))[0]);
            A.Eq('3', PairAddress.Encode(Ips("172.20.1.2"))[0]);
            A.Eq('4', PairAddress.Encode(Ips("100.100.1.2"))[0]);
            A.Eq('5', PairAddress.Encode(Ips("172.32.1.2"))[0], "outside 172.16/12: any IPv4");
        }

        [Test]
        public static void TwoAddressesAtMostAndNoDuplicates()
        {
            var tail = PairAddress.Encode(Ips("192.168.1.23", "192.168.1.23", "100.71.133.93", "10.0.0.5"));
            A.True(PairAddress.TryDecode(tail, out var got, out _));
            A.Eq("192.168.1.23,100.71.133.93", string.Join(",", got.Select(x => x.ToString())));
            A.Eq("", PairAddress.Encode(Ips()));
            A.Eq("", PairAddress.Encode(Ips("127.0.0.1", "0.0.0.0", "::1")), "loopback, any and IPv6 never go in a code");
        }

        [Test]
        public static void EveryTypoInTheAddressIsCaught()
        {
            foreach (var tail in new[] { PairAddress.Encode(Ips("192.168.1.23")), PairAddress.Encode(Ips("192.168.1.23", "100.71.133.93"), 48213) })
            {
                for (int i = 0; i < tail.Length; i++)
                {
                    for (char d = '0'; d <= '9'; d++)
                    {
                        if (d == tail[i]) continue;
                        var typo = tail.Substring(0, i) + d + tail.Substring(i + 1);
                        A.False(PairAddress.TryDecode(typo, out _, out _), "one wrong digit: " + typo);
                    }
                    if (i + 1 < tail.Length && tail[i] != tail[i + 1])
                    {
                        var swap = tail.Substring(0, i) + tail[i + 1] + tail[i] + tail.Substring(i + 2);
                        A.False(PairAddress.TryDecode(swap, out _, out _), "two neighbours swapped: " + swap);
                    }
                }
                A.False(PairAddress.TryDecode(tail.Substring(0, tail.Length - 1), out _, out _), "a digit missing");
                A.False(PairAddress.TryDecode(tail + "0", out _, out _), "a digit too many");
            }
        }

        [Test]
        public static void ReservedKindsAndBadRangesAreRefused()
        {
            foreach (var body in new[] { "7", "0", "8123", "9", "199999", "6000001002790", "1", "600000" })
                A.False(PairAddress.TryDecode(body + PairAddress.Damm(body), out _, out _), body);
            // a port alone (no address) is no address
            A.False(PairAddress.TryDecode("648211" + PairAddress.Damm("648211"), out _, out _));
            A.True(PairAddress.TryDecode("", out var none, out var p0) && none.Count == 0 && p0 == 48210, "an empty tail = an old 6-digit code");
        }

        [Test]
        public static void TheProofStillUsesOnlyTheSecret()
        {
            // the Thor proves the 6 secret digits; the address part is public and never keys the HMAC
            var p = new PairingCodes();
            var secret = p.Current;
            A.Eq(PairCheck.Ok, p.Verify("n1", "thor", PairingCodes.Proof(secret, "n1", "thor")));
            var p2 = new PairingCodes();
            var full = p2.Current + PairAddress.Encode(Ips("192.168.1.23"));
            A.Eq(PairCheck.Wrong, p2.Verify("n1", "thor", PairingCodes.Proof(full, "n1", "thor")), "the whole code is not the key");
        }
    }
}
