using System;
using System.Security.Cryptography;
using System.Text;

namespace DsfDesktop
{
    public static class Crypto
    {
        static readonly RandomNumberGenerator Rng = RandomNumberGenerator.Create();

        public static byte[] RandomBytes(int n)
        {
            var b = new byte[n];
            lock (Rng) Rng.GetBytes(b);
            return b;
        }

        /// <summary>a uniform integer in [0, max), by rejection (no modulo bias)</summary>
        public static int RandomBelow(int max)
        {
            if (max <= 0) throw new ArgumentOutOfRangeException(nameof(max));
            uint limit = uint.MaxValue - (uint.MaxValue % (uint)max);
            while (true)
            {
                uint v = BitConverter.ToUInt32(RandomBytes(4), 0);
                if (v < limit) return (int)(v % (uint)max);
            }
        }

        public static string Hex(byte[] b)
        {
            var sb = new StringBuilder(b.Length * 2);
            foreach (var x in b) sb.Append(x.ToString("x2"));
            return sb.ToString();
        }

        public static byte[] FromHex(string s)
        {
            if (s == null || s.Length % 2 != 0) throw new FormatException("odd hex");
            var b = new byte[s.Length / 2];
            for (int i = 0; i < b.Length; i++) b[i] = Convert.ToByte(s.Substring(i * 2, 2), 16);
            return b;
        }

        public static string Sha256Hex(string s)
        {
            using (var h = SHA256.Create()) return Hex(h.ComputeHash(Encoding.UTF8.GetBytes(s)));
        }

        public static string HmacSha256Hex(string key, string msg)
        {
            using (var h = new HMACSHA256(Encoding.UTF8.GetBytes(key))) return Hex(h.ComputeHash(Encoding.UTF8.GetBytes(msg)));
        }

        /// <summary>compare without an early exit, so the time taken says nothing about where two secrets differ</summary>
        public static bool FixedTimeEquals(string a, string b)
        {
            if (a == null || b == null) return false;
            var x = Encoding.UTF8.GetBytes(a);
            var y = Encoding.UTF8.GetBytes(b);
            int diff = x.Length ^ y.Length;
            for (int i = 0; i < Math.Max(x.Length, y.Length); i++)
                diff |= (i < x.Length ? x[i] : 0) ^ (i < y.Length ? y[i] : 0);
            return diff == 0;
        }

        public static string Base64Url(byte[] b) => Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');

        /// <summary>DPAPI (current user) for the Sunshine password we keep in settings</summary>
        public static string Protect(string plain)
            => Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(plain), null, DataProtectionScope.CurrentUser));

        public static string Unprotect(string b64)
        {
            try { return Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(b64), null, DataProtectionScope.CurrentUser)); }
            catch (Exception) { return null; }
        }
    }
}
