using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text.RegularExpressions;
using System.Threading;

namespace DsfDesktop.Wow.Patch
{
    /// <summary>a CDN or patch-server failure worth a Retry: no answer, a 5xx, a wrong or cut answer. "missing" is not one (null)</summary>
    public sealed class CdnException : Exception { public CdnException(string m) : base(m) { } }

    /// <summary>
    /// Blizzard's CDN over HTTPS only (the hosts in Blizzard's order; 2026-11-20 after WoW Forever for Android v2.3.7 7d0b00d0 and
    /// v2.3.9 efa6e700: Level 3 under its HTTPS name, a host whose CERTIFICATE fails skipped for the rest of this client's run, as
    /// Korea's blizzard.gcdn.cloudn.co.kr; certificate checks are never turned off). The Thor's updater's integrity rules, jaredgei PR #54: a Range
    /// answer must be a 206 of exactly the asked length; a body as long as its Content-Length; configs are checked by the MD5 in their
    /// name and indexes by their footer by the callers' [verify], so a hotel sign-in page's "200" is never game data. 403 / 404 =
    /// missing (null / false); a 5xx, a timeout or a failed check = the next host, then a <see cref="CdnException"/>. Big bodies go to
    /// "&lt;dest&gt;.part" and resume with a Range request.
    /// </summary>
    public sealed class CdnClient
    {
        readonly List<string> bases;
        readonly HttpClient http;
        readonly TimeSpan idle;

        public const int SmallMax = 64 * 1024 * 1024;

        /// <param name="hosts">"level3.blizzard.com us.cdn.blizzard.com" (or "127.0.0.1:port" in the tests)</param>
        /// <param name="path">"tpr/wow"</param>
        /// <param name="https">https://host (Level 3 as level3.ssl.blizzard.com); false = http://host, only for the tests' plain listener</param>
        public CdnClient(IEnumerable<string> hosts, string path, HttpMessageHandler handler = null, TimeSpan? idle = null, bool https = true)
        {
            try { ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12; } catch (Exception) { }
            var hs = hosts.Where(h => !string.IsNullOrWhiteSpace(h)).Select(h => h.Trim()).Distinct().ToList();
            bases = hs.Select(h => (https ? "https://" + HttpsHost(h) : "http://" + h) + "/" + path.Trim('/')).Distinct().ToList();
            http = handler != null ? new HttpClient(handler, false) : new HttpClient();
            http.Timeout = System.Threading.Timeout.InfiniteTimeSpan;
            this.idle = idle ?? TimeSpan.FromSeconds(30);
        }

        /// <summary>a CDN host's HTTPS name (Level 3's plain host has an HTTPS twin)</summary>
        public static string HttpsHost(string host) =>
            string.Equals(host, "level3.blizzard.com", StringComparison.OrdinalIgnoreCase) ? "level3.ssl.blizzard.com" : host.ToLowerInvariant();

        /// <summary>pure: the hosts of the https entries of a cdns row's Servers ("https://level3.ssl.blizzard.com/?maxhosts=4"), in
        /// order; none = [hosts] as they are</summary>
        public static List<string> HttpsHosts(string servers, IEnumerable<string> hosts)
        {
            var https = (servers ?? "").Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries)
                .Where(u => u.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
                .Select(u => u.Substring(8).Split('/', '?')[0].ToLowerInvariant()).Where(h => h.Length > 0).Distinct().ToList();
            return https.Count > 0 ? https : hosts.ToList();
        }

        /// <summary>pure: [e] (or an inner one) is a refused certificate, not a network hiccup</summary>
        public static bool IsCertificateFailure(Exception e)
        {
            for (int depth = 0; e != null && depth < 8; depth++)
            {
                if (e is System.Security.Authentication.AuthenticationException) return true;
                if (e is WebException we && we.Status == WebExceptionStatus.TrustFailure) return true;
                if (e is AggregateException ae && ae.InnerExceptions.Count == 1) { e = ae.InnerExceptions[0]; continue; }
                e = e.InnerException;
            }
            return false;
        }

        /// <summary>the bases whose certificate failed in this client's run: asked no more while another one is left</summary>
        readonly HashSet<string> untrusted = new HashSet<string>();

        public static string Url(string b, string kind, string key) => b + "/" + kind + "/" + key.Substring(0, 2) + "/" + key.Substring(2, 4 - 2) + "/" + key;

        /// <summary>a small body (a config, the install manifest, an encoding range) in memory; null = missing on every host that answered</summary>
        public byte[] Fetch(string kind, string key, long from = -1, long to = -1, Func<byte[], bool> verify = null, CancellationToken ct = default)
        {
            return (byte[])Each(kind, key, ct, () => from >= 0 ? Tuple.Create(from, to) : null, (resp, b) =>
            {
                using (var s = resp.Content.ReadAsStreamAsync().Result)
                using (var ms = new MemoryStream())
                {
                    long cap = from >= 0 ? to - from + 1 : SmallMax;
                    long said = resp.Content.Headers.ContentLength ?? -1;
                    if (said > cap) throw new CdnException(kind + "/" + key + " is " + said + " B: too big");
                    Pump(s, ms, cap, ct, null);
                    var bytes = ms.ToArray();
                    var why = BodyProblem((int)resp.StatusCode, said, RangeStart(resp), from, to, bytes.Length);
                    if (why != null) throw new CdnException(why + " from " + b);
                    if (verify != null && !verify(bytes)) throw new CdnException(kind + "/" + key + " from " + b + " is not that file");
                    return bytes;
                }
            });
        }

        /// <summary>
        /// A body to [dest] through "[dest].part", checked, then renamed. A whole-file fetch resumes a .part left by an earlier try
        /// (Range: bytes=n-); [progress] = (bytes in the .part so far, the whole body's length or -1). true = there; false = missing everywhere.
        /// </summary>
        public bool FetchTo(string kind, string key, string dest, long from = -1, long to = -1, Func<string, bool> verify = null,
                            Action<long, long> progress = null, CancellationToken ct = default)
        {
            var part = dest + ".part";
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(dest)));
            for (int attempt = 0; ; attempt++)
            {
                try
                {
                    var r = Each(kind, key, ct, () =>
                    {
                        long have = from < 0 && File.Exists(part) ? new FileInfo(part).Length : 0;
                        return have > 0 ? Tuple.Create(have, -1L) : (from >= 0 ? Tuple.Create(from, to) : null);
                    }, (resp, b) => { Take(resp, b, part, from, to, progress, ct); return "ok"; });
                    if (r == null) return false;
                    break;
                }
                catch (RangeGone)
                {
                    // the .part is longer than the file (another file under that name meanwhile): start it over, once
                    try { File.Delete(part); } catch (Exception) { }
                    if (attempt > 0) throw new CdnException(kind + "/" + key + ": the resume was refused");
                }
            }
            if (verify != null && !verify(part)) { try { File.Delete(part); } catch (Exception) { } throw new CdnException(kind + "/" + key + " is not that file"); }
            if (File.Exists(dest)) File.Delete(dest);
            File.Move(part, dest);
            return true;
        }

        void Take(HttpResponseMessage resp, string b, string part, long from, long to, Action<long, long> progress, CancellationToken ct)
        {
            long have = from < 0 && File.Exists(part) ? new FileInfo(part).Length : 0;
            int code = (int)resp.StatusCode;
            long start = RangeStart(resp);
            // a resume answered from where the .part ends; a 200 to a resume = the whole file again (the .part starts over)
            bool append = from < 0 && have > 0 && code == 206 && start == have;
            if (from < 0 && have > 0 && code == 206 && !append) throw new CdnException("a resume answered from " + start + " (" + b + ")");
            long said = resp.Content.Headers.ContentLength ?? -1;
            using (var s = resp.Content.ReadAsStreamAsync().Result)
            using (var fs = new FileStream(part, append ? FileMode.Append : FileMode.Create, FileAccess.Write, FileShare.None, 1 << 20))
            {
                long baseLen = append ? have : 0;
                long cap = from >= 0 ? to - from + 1 : said >= 0 ? said : long.MaxValue;
                long whole = from >= 0 ? to - from + 1 : append ? (RangeTotal(resp) >= 0 ? RangeTotal(resp) : said >= 0 ? have + said : -1) : said;
                long got = Pump(s, fs, cap, ct, n => progress?.Invoke(baseLen + n, whole));
                if (from >= 0)
                {
                    var why = BodyProblem(code, said, start, from, to, got);
                    if (why != null) throw new CdnException(why + " from " + b);
                }
                else if (append)
                {
                    if (said >= 0 && got != said) throw new CdnException("a short answer (" + got + " of " + said + " B) from " + b);
                    long total = RangeTotal(resp);
                    if (total >= 0 && baseLen + got != total) throw new CdnException("a resumed file of the wrong length from " + b);
                }
                else if (code != 200 || (said >= 0 && got != said)) throw new CdnException("a short answer (" + got + " of " + said + " B) from " + b);
            }
        }

        /// <summary>pure: what is wrong with an answer, null = fine (a Range answer = a 206 of exactly the asked length from its start)</summary>
        public static string BodyProblem(int code, long contentLength, long rangeStart, long from, long to, long got)
        {
            if (from >= 0)
            {
                if (code != 206) return "not the asked range (HTTP " + code + ")";
                if (got != to - from + 1) return "not the asked range (" + got + " of " + (to - from + 1) + " B)";
                if (rangeStart >= 0 && rangeStart != from) return "not the asked range (from " + rangeStart + ")";
            }
            if (contentLength >= 0 && contentLength != got) return "a short answer (" + got + " of " + contentLength + " B)";
            return null;
        }

        static long RangeStart(HttpResponseMessage r)
        {
            var cr = r.Content.Headers.ContentRange;
            return cr != null && cr.From.HasValue ? cr.From.Value : -1;
        }

        static long RangeTotal(HttpResponseMessage r)
        {
            var cr = r.Content.Headers.ContentRange;
            return cr != null && cr.Length.HasValue ? cr.Length.Value : -1;
        }

        /// <summary>[s] to [o], at most [cap] bytes; [idle] with no data = a timeout; [ct] = the player's Cancel</summary>
        long Pump(Stream s, Stream o, long cap, CancellationToken ct, Action<long> progress)
        {
            var buf = new byte[1 << 16];
            long n = 0;
            using (var cts = CancellationTokenSource.CreateLinkedTokenSource(ct))
            {
                while (n < cap)
                {
                    ct.ThrowIfCancellationRequested();
                    var t = s.ReadAsync(buf, 0, (int)Math.Min(buf.Length, cap - n), cts.Token);
                    var waited = TimeSpan.Zero;
                    while (!t.Wait(250))
                    {
                        waited += TimeSpan.FromMilliseconds(250);
                        if (ct.IsCancellationRequested || waited >= idle)
                        {
                            cts.Cancel();
                            try { s.Dispose(); } catch (Exception) { }
                            ct.ThrowIfCancellationRequested();
                            throw new CdnException("no data for " + (int)idle.TotalSeconds + " s");
                        }
                    }
                    int r;
                    try { r = t.Result; }
                    catch (AggregateException ae) { throw new CdnException("the answer broke off: " + (ae.InnerException ?? ae).Message); }
                    if (r <= 0) break;
                    o.Write(buf, 0, r); n += r;
                    progress?.Invoke(n);
                }
            }
            return n;
        }

        sealed class RangeGone : Exception { }

        /// <summary>every base in turn: [take] reads a 2xx answer (it throws when the answer fails its checks: the next base is tried).
        /// null = missing (403 / 404) on every base that answered; a failure anywhere else = a CdnException</summary>
        object Each(string kind, string key, CancellationToken ct, Func<Tuple<long, long>> range, Func<HttpResponseMessage, string, object> take)
        {
            if (key == null || key.Length < 32 || !Hex.IsKey(key.Substring(0, 32))) throw new CdnException("a bad key " + key);
            Exception failure = null, refused = null;
            bool missing = false;
            List<string> usable;
            lock (untrusted) usable = bases.Where(x => !untrusted.Contains(x)).ToList();
            if (usable.Count == 0) usable = bases;           // all of them refused (a Wi-Fi sign-in page): asked again, still checked
            foreach (var b in usable)
            {
                ct.ThrowIfCancellationRequested();
                var asked = range();
                var req = new HttpRequestMessage(HttpMethod.Get, Url(b, kind, key));
                if (asked != null) req.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(asked.Item1, asked.Item2 >= 0 ? asked.Item2 : (long?)null);
                try
                {
                    HttpResponseMessage resp;
                    using (var hct = CancellationTokenSource.CreateLinkedTokenSource(ct))
                    {
                        hct.CancelAfter(TimeSpan.FromSeconds(15));
                        try { resp = http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, hct.Token).Result; }
                        catch (AggregateException ae)
                        {
                            if (ct.IsCancellationRequested) throw new OperationCanceledException(ct);
                            if (IsCertificateFailure(ae))
                            {
                                // its certificate failed (Korea's cloudn.co.kr): skipped from now on; the other hosts mirror it, so this
                                // is neither a failure nor a "missing" of its own
                                lock (untrusted) untrusted.Add(b);
                                refused = new CdnException("the certificate of " + b + " failed its check");
                                continue;
                            }
                            throw new CdnException((ae.InnerException?.InnerException ?? ae.InnerException ?? ae).Message + " (" + b + ")");
                        }
                    }
                    using (resp)
                    {
                        int code = (int)resp.StatusCode;
                        if (code == 403 || code == 404) { missing = true; continue; }
                        if (code == 416) throw new RangeGone();
                        if (code < 200 || code > 299) { failure = new CdnException("HTTP " + code + " from " + b); continue; }
                        return take(resp, b);
                    }
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
                catch (Exception e) when (!(e is CdnException) && IsCertificateFailure(e))
                {
                    lock (untrusted) untrusted.Add(b);
                    refused = new CdnException("the certificate of " + b + " failed its check");
                }
                catch (RangeGone) { throw; }
                catch (CdnException e) { failure = e; }
                catch (PatchFormatException e) { failure = e; }
                catch (AggregateException e) { failure = new CdnException((e.InnerException ?? e).Message + " (" + b + ")"); }
                catch (IOException e) { failure = new CdnException(e.Message + " (" + b + ")"); }
                catch (HttpRequestException e) { failure = new CdnException(e.Message + " (" + b + ")"); }
                catch (ObjectDisposedException e) { failure = new CdnException(e.Message + " (" + b + ")"); }
            }
            if (missing && failure == null) return null;
            if (failure is CdnException ce) throw ce;
            if (failure == null && refused != null) throw new CdnException("every CDN host failed its certificate check (" + refused.Message + ")");
            throw new CdnException(failure?.Message ?? "no CDN hosts");
        }
    }
}
