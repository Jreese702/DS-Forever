using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace DsfDesktop.Sunshine
{
    /// <summary>
    /// DIAGNOSTICS ONLY (issue #219, 1.0.27: "Tls pairing failure after update": after a Desktop update the Thor's https serverinfo got
    /// 401, and every re-pair passed the PIN steps on the http port and then got 401 "Certificate verification failed" on the https
    /// pairchallenge). What could not be told from the Thor's log, Desktop's log now says: how many clients Sunshine's state file
    /// holds (name + a short fingerprint of each certificate, never the certificate), Sunshine's own pairing / certificate lines
    /// around a pairing, other sunshine.exe processes already running when ours starts, and who else listens on the GameStream ports.
    /// Nothing here changes behaviour.
    /// </summary>
    public static class SunshineDiag
    {
        /// <summary>sunshine_state.json -> "2 paired: AYN Thor (1a2b3c4d) [fp 0123456789ab], ..." ("unreadable" / "no file")</summary>
        public static string StateSummary(string json)
        {
            if (json == null) return "no file";
            Dictionary<string, object> o;
            try { o = Json.ReadObject(json); } catch (Exception) { o = null; }
            if (o == null) return "unreadable";
            var root = o.TryGetValue("root", out var r) && r is Dictionary<string, object> rd ? rd : o;
            var items = new List<string>();
            // Sunshine 0.23+: root.named_devices [{name, cert, uuid}]
            if (root.TryGetValue("named_devices", out var nd) && nd is object[] named)
                foreach (var d in named.OfType<Dictionary<string, object>>())
                    items.Add(DeviceName(d.Str("name")) + " [fp " + Fp(d.Str("cert")) + "]");
            // older: root.devices [{uniqueid, certs: [pem]}]
            else if (root.TryGetValue("devices", out var dv) && dv is object[] devs)
                foreach (var d in devs.OfType<Dictionary<string, object>>())
                    if (d.TryGetValue("certs", out var cs) && cs is object[] certs)
                        foreach (var c in certs) items.Add("(old format) [fp " + Fp(Convert.ToString(c)) + "]");
            return items.Count + " paired" + (items.Count > 0 ? ": " + string.Join(", ", items) : "");
        }

        public static string StateSummaryOfFile(string path)
        {
            try
            {
                if (!File.Exists(path)) return "no file";
                using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                using (var rd = new StreamReader(fs)) return StateSummary(rd.ReadToEnd()) + " (written " + File.GetLastWriteTime(path).ToString("yyyy-MM-dd HH:mm:ss") + ")";
            }
            catch (Exception e) { return "unreadable: " + e.Message; }
        }

        static string DeviceName(string s)
        {
            var t = new string((s ?? "").Where(c => char.IsLetterOrDigit(c) || " -_()".IndexOf(c) >= 0).Take(48).ToArray()).Trim();
            return t.Length == 0 ? "(no name)" : t;
        }

        /// <summary>the first 12 hex of SHA-256 over a PEM certificate's DER (the Thor logs the same prefix of its own)</summary>
        public static string Fp(string pem)
        {
            try
            {
                var b64 = string.Concat((pem ?? "").Split('\n').Where(l => !l.StartsWith("-----")).Select(l => l.Trim()));
                if (b64.Length == 0) return "none";
                using (var sha = SHA256.Create())
                    return Crypto.Hex(sha.ComputeHash(Convert.FromBase64String(b64))).ToLowerInvariant().Substring(0, 12);
            }
            catch (Exception) { return "bad"; }
        }

        static readonly Regex PairLine = new Regex(@"pair|cert|verif|ssl|unauthori|named|client|bind|address already|in use|https", RegexOptions.IgnoreCase);

        /// <summary>the lines of Sunshine's log text that talk about pairing, certificates or the ports (the last [max])</summary>
        public static List<string> PairLines(string logText, int max = 25)
        {
            var res = new List<string>();
            foreach (var raw in (logText ?? "").Split('\n'))
            {
                var l = raw.TrimEnd('\r');
                if (l.Length == 0 || !PairLine.IsMatch(l)) continue;
                // never a PIN or a certificate body
                if (l.IndexOf("BEGIN CERTIFICATE", StringComparison.OrdinalIgnoreCase) >= 0) continue;
                l = Regex.Replace(l, @"\b(pin|PIN)\b(\W{0,3})\d{4,}", "$1$2<pin>");
                res.Add(l.Length > 300 ? l.Substring(0, 300) : l);
            }
            return res.Count > max ? res.GetRange(res.Count - max, max) : res;
        }

        /// <summary>Sunshine's log length now (read the new part later with [LogSince])</summary>
        public static long LogLength(string path)
        {
            try { return File.Exists(path) ? new FileInfo(path).Length : 0; } catch (Exception) { return 0; }
        }

        public static string LogSince(string path, long offset)
        {
            try
            {
                using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                {
                    if (offset < 0 || offset > fs.Length) offset = 0;
                    if (fs.Length - offset > 512 * 1024) offset = fs.Length - 512 * 1024;
                    fs.Seek(offset, SeekOrigin.Begin);
                    using (var rd = new StreamReader(fs, Encoding.UTF8)) return rd.ReadToEnd();
                }
            }
            catch (Exception) { return ""; }
        }

        /// <summary>other sunshine.exe processes (a stale one from before an update could still hold our ports)</summary>
        public static List<string> OtherSunshines(int ourPid)
        {
            var res = new List<string>();
            try
            {
                foreach (var p in Process.GetProcessesByName("sunshine"))
                {
                    using (p)
                    {
                        if (p.Id == ourPid) continue;
                        string path = "?", started = "?";
                        try { path = p.MainModule?.FileName ?? "?"; } catch (Exception) { }
                        try { started = p.StartTime.ToString("yyyy-MM-dd HH:mm:ss"); } catch (Exception) { }
                        res.Add("pid " + p.Id + " started " + started + " (" + path + ")");
                    }
                }
            }
            catch (Exception e) { res.Add("couldn't list: " + e.Message); }
            return res;
        }

        /// <summary>"47984 listening, 47989 listening, 52984 free, 52989 free": which GameStream ports something already holds</summary>
        public static string Listeners(IEnumerable<int> ports, Func<int, bool> tcpFree)
            => string.Join(", ", ports.Distinct().Select(p => p + (tcpFree(p) ? " free" : " listening")));
    }
}
