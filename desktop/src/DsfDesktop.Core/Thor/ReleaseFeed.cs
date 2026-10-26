using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace DsfDesktop.Thor
{
    public sealed class Release
    {
        public string Tag, Version, ApkName, ApkUrl, SumsUrl;
        public long ApkSize;
    }

    /// <summary>
    /// DS Forever's latest PUBLIC release on GitHub (Jreese702/DS-Forever): its DS-Forever-&lt;ver&gt;.apk, checked against the release's
    /// SHA256SUMS before anything is installed. Downloads go to DataDir\apk (one APK kept) and resume after a break.
    /// </summary>
    public sealed class ReleaseFeed
    {
        public const string LatestUrl = "https://api.github.com/repos/Jreese702/DS-Forever/releases/latest";
        static readonly HttpClient Http = MakeClient();

        static HttpClient MakeClient()
        {
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            var h = new HttpClient(new HttpClientHandler { AllowAutoRedirect = true }) { Timeout = Timeout.InfiniteTimeSpan };
            h.DefaultRequestHeaders.UserAgent.ParseAdd("DSForeverDesktop/" + typeof(ReleaseFeed).Assembly.GetName().Version.ToString(3));
            return h;
        }

        public static Release ParseLatest(string json)
        {
            var o = Json.ReadObject(json);
            if (o == null || o.Str("tag_name") == null) return null;
            var r = new Release { Tag = o.Str("tag_name"), Version = Versions.Clean(o.Str("tag_name")) };
            if (o.TryGetValue("assets", out var a) && a is object[] assets)
                foreach (var x in assets.OfType<Dictionary<string, object>>())
                {
                    var name = x.Str("name") ?? "";
                    if (Regex.IsMatch(name, @"^DS-Forever-[0-9][^/\\]*\.apk$", RegexOptions.IgnoreCase))
                    {
                        r.ApkName = name; r.ApkUrl = x.Str("browser_download_url"); r.ApkSize = x.Long("size");
                    }
                    else if (name == "SHA256SUMS") r.SumsUrl = x.Str("browser_download_url");
                }
            return r.ApkUrl != null ? r : null;
        }

        /// <summary>"&lt;sha256&gt;  &lt;file&gt;" lines (sha256sum's text and binary "*" forms)</summary>
        public static Dictionary<string, string> ParseSums(string text)
        {
            var res = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (var raw in (text ?? "").Replace("\r", "").Split('\n'))
            {
                var m = Regex.Match(raw.Trim(), @"^([0-9a-fA-F]{64})\s+\*?(.+)$");
                if (m.Success) res[m.Groups[2].Value.Trim()] = m.Groups[1].Value.ToLowerInvariant();
            }
            return res;
        }

        public static string Sha256File(string path)
        {
            using (var sha = SHA256.Create())
            using (var f = File.OpenRead(path)) return Crypto.Hex(sha.ComputeHash(f));
        }

        /// <summary>null = the file is what the sums say, else why not</summary>
        public static string Verify(string file, string sumsText, string name)
        {
            if (!ParseSums(sumsText).TryGetValue(name, out var want)) return "SHA256SUMS doesn't list " + name;
            if (!File.Exists(file)) return "missing";
            var got = Sha256File(file);
            return got == want ? null : "checksum mismatch (" + got.Substring(0, 12) + " vs " + want.Substring(0, 12) + ")";
        }

        public async Task<Release> Latest(CancellationToken ct)
        {
            using (var r = await Http.GetAsync(LatestUrl, ct).ConfigureAwait(false))
            {
                r.EnsureSuccessStatusCode();
                return ParseLatest(await r.Content.ReadAsStringAsync().ConfigureAwait(false));
            }
        }

        /// <summary>the verified APK in cacheDir (downloaded or resumed as needed); throws when it can't be verified</summary>
        public async Task<string> EnsureApk(Release rel, string cacheDir, Action<long, long> progress, CancellationToken ct)
        {
            if (rel.SumsUrl == null) throw new InvalidOperationException("the release has no SHA256SUMS: not installing an unverified APK");
            Directory.CreateDirectory(cacheDir);
            string sums;
            using (var r = await Http.GetAsync(rel.SumsUrl, ct).ConfigureAwait(false))
            {
                r.EnsureSuccessStatusCode();
                sums = await r.Content.ReadAsStringAsync().ConfigureAwait(false);
            }
            var file = Path.Combine(cacheDir, rel.ApkName);
            if (Verify(file, sums, rel.ApkName) == null) return file;

            var part = file + ".part";
            for (int attempt = 0; attempt < 2; attempt++)
            {
                long have = File.Exists(part) ? new FileInfo(part).Length : 0;
                var req = new HttpRequestMessage(HttpMethod.Get, rel.ApkUrl);
                if (have > 0) req.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(have, null);
                using (var r = await Http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false))
                {
                    r.EnsureSuccessStatusCode();
                    bool resumed = r.StatusCode == HttpStatusCode.PartialContent;
                    if (!resumed) have = 0;
                    long total = rel.ApkSize > 0 ? rel.ApkSize : have + (r.Content.Headers.ContentLength ?? 0);
                    using (var src = await r.Content.ReadAsStreamAsync().ConfigureAwait(false))
                    using (var dst = new FileStream(part, resumed ? FileMode.Append : FileMode.Create, FileAccess.Write))
                    {
                        var buf = new byte[1 << 16];
                        int n;
                        long done = have, last = 0;
                        while ((n = await src.ReadAsync(buf, 0, buf.Length, ct).ConfigureAwait(false)) > 0)
                        {
                            await dst.WriteAsync(buf, 0, n, ct).ConfigureAwait(false);
                            done += n;
                            if (done - last > (1 << 20)) { last = done; progress?.Invoke(done, total); }
                        }
                        progress?.Invoke(done, total);
                    }
                }
                var why = Verify(part, sums, rel.ApkName);
                if (why == null)
                {
                    if (File.Exists(file)) File.Delete(file);
                    File.Move(part, file);
                    // keep one APK (the one just verified)
                    foreach (var old in Directory.GetFiles(cacheDir, "*.apk").Where(f => !string.Equals(f, file, StringComparison.OrdinalIgnoreCase)))
                        try { File.Delete(old); } catch (Exception) { }
                    return file;
                }
                File.Delete(part);   // a bad resume: once more from the start
                if (attempt == 1) throw new InvalidDataException("the downloaded APK failed its SHA256SUMS check: " + why);
            }
            throw new InvalidDataException("download failed");
        }
    }
}
