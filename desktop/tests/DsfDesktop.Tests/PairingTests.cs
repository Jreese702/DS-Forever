using System;
using System.Linq;
using DsfDesktop.Pairing;

namespace DsfDesktop.Tests
{
    public static class PairingTests
    {
        [Test]
        public static void CodeIsSixDigitsAndDisplaysSplit()
        {
            for (int i = 0; i < 200; i++)
            {
                var c = PairingCodes.NewCode();
                A.Eq(6, c.Length);
                A.True(c.All(char.IsDigit), c);
            }
            A.Eq("012 345", PairingCodes.Format("012345"));
        }

        [Test]
        public static void CodesSpreadOverTheRange()
        {
            var lows = Enumerable.Range(0, 2000).Select(_ => int.Parse(PairingCodes.NewCode())).ToList();
            A.True(lows.Any(v => v < 100_000), "leading zeros happen");
            A.True(lows.Any(v => v > 900_000));
            A.True(lows.Distinct().Count() > 1900, "practically no repeats");
        }

        [Test]
        public static void ProofIsTheDocumentedHmac()
        {
            // the vector in docs/STREAM_BRIDGE_PROTOCOL.md (computed independently with Python's hmac)
            A.Eq("1728773c570e30c5f0032df3751d125403088e2848828f1b49d2a68c808f50f9", PairingCodes.Proof("123456", "00ff", "thor-1"));
            A.Eq(PairingCodes.Proof("123456", "00ff", "thor-1"), PairingCodes.Proof("123 456", "00ff", "thor-1"), "the space is not part of the code");
        }

        [Test]
        public static void RightProofPairsOnceThenTheCodeChanges()
        {
            var p = new PairingCodes();
            var code = p.Current;
            A.Eq(PairCheck.Ok, p.Verify("n1", "thor", PairingCodes.Proof(code, "n1", "thor")));
            A.True(p.Current != code, "one code, one pairing");
            A.Eq(PairCheck.Wrong, p.Verify("n2", "thor", PairingCodes.Proof(code, "n2", "thor")), "a used code doesn't pair again");
        }

        [Test]
        public static void ProofIsBoundToNonceAndDevice()
        {
            var p = new PairingCodes();
            var code = p.Current;
            A.Eq(PairCheck.Wrong, p.Verify("n1", "thor", PairingCodes.Proof(code, "OTHER", "thor")));
            A.Eq(PairCheck.Wrong, p.Verify("n1", "thor", PairingCodes.Proof(code, "n1", "someone-else")));
            A.Eq(PairCheck.Ok, p.Verify("n1", "thor", PairingCodes.Proof(code, "n1", "thor").ToUpperInvariant()), "hex case doesn't matter");
        }

        [Test]
        public static void FiveWrongReplaceTheCode()
        {
            var p = new PairingCodes();
            var code = p.Current;
            for (int i = 0; i < PairingCodes.MaxAttempts - 1; i++) A.Eq(PairCheck.Wrong, p.Verify("n", "d", "00"));
            A.Eq(1, p.AttemptsLeft);
            A.Eq(PairCheck.Exhausted, p.Verify("n", "d", "00"));
            A.True(p.Current != code, "a new code after the fifth wrong one");
            A.Eq(PairingCodes.MaxAttempts, p.AttemptsLeft);
        }

        [Test]
        public static void TwentyWrongPausePairingUntilRefresh()
        {
            var p = new PairingCodes();
            PairCheck last = PairCheck.Wrong;
            for (int i = 0; i < PairingCodes.LockAfter; i++) last = p.Verify("n", "d", "00");
            A.Eq(PairCheck.Locked, last);
            A.True(p.Locked);
            A.Eq(PairCheck.Locked, p.Verify("n", "d", PairingCodes.Proof(p.Current, "n", "d")), "even the right code waits for the user");
            p.Refresh();
            A.False(p.Locked);
            A.Eq(PairCheck.Ok, p.Verify("n", "d", PairingCodes.Proof(p.Current, "n", "d")));
        }

        [Test]
        public static void CodeExpires()
        {
            var t = new DateTime(2026, 10, 23, 12, 0, 0, DateTimeKind.Utc);
            var p = new PairingCodes(() => t) { Lifetime = TimeSpan.FromMinutes(10) };
            var code = p.Current;
            t = t.AddMinutes(9);
            A.Eq(code, p.Current);
            A.Eq(TimeSpan.FromMinutes(1), p.Remaining);
            t = t.AddMinutes(1);
            A.True(p.Current != code, "a new code after its lifetime");
            A.Eq(PairCheck.Wrong, p.Verify("n", "d", PairingCodes.Proof(code, "n", "d")), "the expired code no longer pairs");
        }

        [Test]
        public static void MoonlightPinIsFourDigits()
        {
            for (int i = 0; i < 100; i++)
            {
                var pin = PairingCodes.NewMoonlightPin();
                A.Eq(4, pin.Length);
                A.True(pin.All(char.IsDigit));
            }
        }
    }

    public static class DeviceStoreTests
    {
        [Test]
        public static void TokenValidatesOnlyForItsDevice()
        {
            using (var t = new Tmp())
            {
                var s = new DeviceStore(t.P("devices.json"));
                var tok = s.Issue("thor-1", "AYN Thor");
                A.True(s.Validate("thor-1", tok));
                A.False(s.Validate("thor-2", tok));
                A.False(s.Validate("thor-1", tok + "x"));
                A.False(s.Validate("thor-1", ""));
                A.False(s.Validate(null, tok));
                A.True(tok.Length >= 40, "32 random bytes");
            }
        }

        [Test]
        public static void TokenIsNotStoredInTheClear()
        {
            using (var t = new Tmp())
            {
                var s = new DeviceStore(t.P("devices.json"));
                var tok = s.Issue("thor-1", "AYN Thor");
                var text = System.IO.File.ReadAllText(t.P("devices.json"));
                A.False(text.Contains(tok), "only the hash is on disk");
                A.True(text.Contains(Crypto.Sha256Hex(tok)));
            }
        }

        [Test]
        public static void RePairReplacesTheOldToken()
        {
            using (var t = new Tmp())
            {
                var s = new DeviceStore(t.P("devices.json"));
                var a = s.Issue("thor-1", "AYN Thor");
                var b = s.Issue("thor-1", "AYN Thor");
                A.False(s.Validate("thor-1", a));
                A.True(s.Validate("thor-1", b));
                A.Eq(1, s.All().Count);
            }
        }

        [Test]
        public static void SurvivesARestartAndRemove()
        {
            using (var t = new Tmp())
            {
                var tok = new DeviceStore(t.P("devices.json")).Issue("thor-1", "AYN Thor");
                var s2 = new DeviceStore(t.P("devices.json"));
                A.True(s2.Validate("thor-1", tok), "reloaded from disk");
                A.Eq("AYN Thor", s2.All().Single().Name);
                s2.SetStreamPaired("thor-1", true);
                A.True(new DeviceStore(t.P("devices.json")).All().Single().StreamPaired);
                A.True(s2.Remove("thor-1"));
                var s3 = new DeviceStore(t.P("devices.json"));
                A.False(s3.Validate("thor-1", tok));
                A.Eq(TokenCheck.Revoked, s3.Check("thor-1", tok), "a removed device is told so");
                A.Eq(TokenCheck.Bad, s3.Check("never-paired", "x"));
                s3.Issue("thor-1", "AYN Thor");
                A.Eq(TokenCheck.Bad, s3.Check("thor-1", tok), "re-paired: the old token is just wrong now");
            }
        }

        [Test]
        public static void NamesAreCleaned()
        {
            A.Eq("Thor", DeviceStore.Clean("  Th\u0000or\n ", 64));
            A.Eq(64, DeviceStore.Clean(new string('x', 500), 64).Length);
        }
    }
}
