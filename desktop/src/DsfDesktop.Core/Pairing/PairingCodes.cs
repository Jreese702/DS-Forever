using System;
using System.Collections.Generic;
using System.Linq;

namespace DsfDesktop.Pairing
{
    public enum PairCheck { Ok, Wrong, Exhausted, NoCode, Locked }

    /// <summary>
    /// The 6-digit code Desktop shows ("Pair a device: 123 456"). One code at a time; it is used up by a successful pairing, replaced
    /// after <see cref="MaxAttempts"/> wrong tries or after <see cref="Lifetime"/>, and by the New code button. After
    /// <see cref="LockAfter"/> wrong tries in <see cref="LockWindow"/> pairing pauses until the user presses New code.
    ///
    /// The code never crosses the network: the Thor proves it with proof = HMAC-SHA256(key = code, "dsf-pair-v1|" + nonce + "|" +
    /// device_id), lower-case hex, against a nonce the bridge made for that one attempt (STREAM_BRIDGE_PROTOCOL.md section 3).
    /// Moonlight's PIN is a separate one-time 4-digit number the bridge hands over after the proof: Sunshine accepts exactly 4
    /// digits ("PIN must contain exactly 4 numeric digits").
    /// </summary>
    public sealed class PairingCodes
    {
        public static readonly TimeSpan DefaultLifetime = TimeSpan.FromMinutes(10);
        public const int MaxAttempts = 5;
        public const int LockAfter = 20;
        public static readonly TimeSpan LockWindow = TimeSpan.FromMinutes(10);

        readonly Func<DateTime> now;
        readonly object gate = new object();
        readonly Queue<DateTime> wrongTimes = new Queue<DateTime>();
        string code;
        DateTime madeUtc;
        int wrong;
        bool locked;

        public TimeSpan Lifetime { get; set; } = DefaultLifetime;
        public event Action Changed;

        public PairingCodes(Func<DateTime> utcNow = null)
        {
            now = utcNow ?? (() => DateTime.UtcNow);
            Refresh();
        }

        public static string NewCode() => Crypto.RandomBelow(1_000_000).ToString("D6");

        /// <summary>"123456" -> "123 456"</summary>
        public static string Format(string c) => c != null && c.Length == 6 ? c.Substring(0, 3) + " " + c.Substring(3) : c;

        /// <summary>what the user typed, digits only ("123 456" and "123456" are the same code)</summary>
        public static string Digits(string typed) => new string((typed ?? "").Where(char.IsDigit).ToArray());

        public static string Proof(string code, string nonce, string deviceId)
            => Crypto.HmacSha256Hex(Digits(code), "dsf-pair-v1|" + nonce + "|" + deviceId);

        /// <summary>the one-time 4-digit PIN for Moonlight's handshake</summary>
        public static string NewMoonlightPin() => Crypto.RandomBelow(10_000).ToString("D4");

        public string Current { get { lock (gate) { Expire(); return code; } } }
        public string Display => Format(Current);
        public bool Locked { get { lock (gate) return locked; } }
        public int AttemptsLeft { get { lock (gate) return MaxAttempts - wrong; } }

        public TimeSpan Remaining
        {
            get { lock (gate) { Expire(); var r = madeUtc + Lifetime - now(); return r < TimeSpan.Zero ? TimeSpan.Zero : r; } }
        }

        public void Refresh()
        {
            lock (gate)
            {
                code = NewCode();
                madeUtc = now();
                wrong = 0;
                locked = false;
                wrongTimes.Clear();
            }
            try { Changed?.Invoke(); } catch (Exception) { }
        }

        void Expire()
        {
            if (now() - madeUtc < Lifetime) return;
            code = NewCode();
            madeUtc = now();
            wrong = 0;
        }

        public PairCheck Verify(string nonce, string deviceId, string proofHex)
        {
            bool changed = false;
            PairCheck result;
            lock (gate)
            {
                Expire();
                if (code == null) return PairCheck.NoCode;
                if (locked) return PairCheck.Locked;
                if (Crypto.FixedTimeEquals(Proof(code, nonce, deviceId), (proofHex ?? "").Trim().ToLowerInvariant()))
                {
                    code = NewCode(); madeUtc = now(); wrong = 0; changed = true;   // one code, one pairing
                    result = PairCheck.Ok;
                }
                else if (NoteWrong()) { locked = true; changed = true; result = PairCheck.Locked; }
                else if (++wrong >= MaxAttempts) { code = NewCode(); madeUtc = now(); wrong = 0; changed = true; result = PairCheck.Exhausted; }
                else result = PairCheck.Wrong;
            }
            if (changed) try { Changed?.Invoke(); } catch (Exception) { }
            return result;
        }

        bool NoteWrong()
        {
            var t = now();
            wrongTimes.Enqueue(t);
            while (wrongTimes.Count > 0 && t - wrongTimes.Peek() > LockWindow) wrongTimes.Dequeue();
            return wrongTimes.Count >= LockAfter;
        }
    }
}
