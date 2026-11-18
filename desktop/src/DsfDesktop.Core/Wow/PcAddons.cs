using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// THE THOR'S ADD-ONS ON THE PC (the user, 2026-11-18: "My desktop seems to be missing the required addons for the app like polestar and
    /// questie"; "I would have expected those to import with being enabled"; "questie especially"; "part of the DS forever startup should be
    /// maintaining/updating enabled addons").
    ///
    /// What the Thor manages, Desktop manages the same way on the PC, from the SAME official sources:
    ///  - QuestieDB (Questie/QuestieDB's latest GitHub release, its "QuestieDB[-x.y.z]-Forever.zip", GitHub's own sha256 digest or the
    ///    release's SHA256SUMS; the Thor's DependencyAddons rule, rechecked every 6 h) - always wanted, FIRST, and wanted even before any
    ///    Thor has connected;
    ///  - the Thor's pinned packages (ThirdPartyAddons: Polestar, Manners, Spoken + its packs, QuestTrail), exactly the pins the Thor
    ///    sends (tag, asset, size, sha256), each only from its own known source (<see cref="Sources"/>: the project's GitHub releases, or
    ///    CurseForge's file CDN), each only while the Thor wants it (its choice / Install / module switch);
    ///  - the player's IMPORTED add-ons (Thor Settings > Add-ons > Import), file by file from the Thor over the bridge, each file's size
    ///    and sha256 checked, resumable.
    /// The rules on the PC: only folders Desktop itself put in are ever updated, disabled or moved out (its own state file); a folder of
    /// that name the player has there is "yours" and never touched; nothing is deleted (a replaced or removed copy goes to
    /// Interface/AddOns-backup-dsforever, the Thor's own folder name); every package is unpacked into Interface/AddOns-staging-dsforever and
    /// moved in folder by folder (same volume: a rename), every old copy put back on a failure; NOTHING moves while WoW runs on the PC
    /// (it is checked before every move: a later run - the next Thor connect, Desktop's next start, the next start of WoW - does it).
    /// The Thor's enabled / disabled state of those folders goes into the PC's AddOns.txt files (<see cref="AddonsTxt"/>), only for
    /// Desktop's folders.
    /// </summary>
    public sealed class PcAddons
    {
        public const string Staging = "AddOns-staging-dsforever";
        public const string Backup = "AddOns-backup-dsforever";
        public const long Margin = 200L * 1000 * 1000;
        public const long MaxPkg = 2_000_000_000L;
        public const long MaxQuestieDb = 64L * 1024 * 1024;
        public const int MaxEntries = 40_000;
        /// <summary>the Thor's import caps (ImportedAddons: MAX_ENTRIES, MAX_TOTAL)</summary>
        public const int MaxImportFiles = 20_000;
        public const long MaxImportBytes = 512L * 1024 * 1024;
        public const int Chunk = 256 * 1024;
        public static readonly TimeSpan QuestieRecheck = TimeSpan.FromHours(6);
        public static readonly Regex QuestieAsset = new Regex(@"^QuestieDB(-\d+(\.\d+)*)?-Forever\.zip$");
        public const string QuestieRepo = "Questie/QuestieDB";
        public const string CdnPrefix = "https://mediafilez.forgecdn.net/files/";

        /// <summary>the add-ons Desktop takes from a Thor, and where each may come from: "gh:&lt;repo&gt;" (that repo's release by its pinned
        /// tag) or "cdn" (CurseForge's file CDN, the pinned URL). Anything else the Thor names is refused</summary>
        public static readonly Dictionary<string, string> Sources = new Dictionary<string, string>
        {
            ["polestar"] = "gh:fitzforge/polestar",
            ["spoken"] = "gh:rusty-key/spoken-wow",
            ["manners"] = "cdn",
            ["questtrail"] = "cdn",
        };

        readonly string dataDir;
        readonly IPcHttp http;
        readonly Func<bool> gameRunning;
        public string Api = "https://api.github.com";
        /// <summary>a step's line while a run works (the Thor's status line: "addons_pc" progress)</summary>
        public Action<string, long, long> Progress = (line, done, total) => { };
        /// <summary>the clock (the tests move it)</summary>
        public Func<DateTime> Now = () => DateTime.UtcNow;
        /// <summary>free room on a folder's volume (the tests set their own)</summary>
        public Func<string, long> Free = dir => { try { return new DriveInfo(Path.GetPathRoot(Path.GetFullPath(dir))).AvailableFreeSpace; } catch (Exception) { return long.MaxValue; } };

        public PcAddons(string dataDir, IPcHttp http, Func<bool> gameRunning)
        {
            this.dataDir = Path.Combine(dataDir, "pcaddons");
            this.http = http;
            this.gameRunning = gameRunning;
        }

        string StateFile => Path.Combine(dataDir, "state.json");
        string DlDir => Path.Combine(dataDir, "dl");
        string ImpDir => Path.Combine(dataDir, "imp");
        public string WantFile => Path.Combine(dataDir, "want.json");

        // ---------------------------------------------------------------- the Thor's last want, kept for Desktop's own start

        public void SaveWant(PcWant w)
        {
            try { Directory.CreateDirectory(dataDir); WriteAtomic(WantFile, Json.Write(w.ToJson())); }
            catch (Exception e) { Log.I("pc add-ons: the Thor's list not kept (" + e.Message + ")"); }
        }

        /// <summary>the last Thor's list, or QuestieDB alone when no Thor has sent one yet</summary>
        public PcWant LoadWant()
        {
            try { if (File.Exists(WantFile)) return PcWant.Parse(Json.ReadObject(File.ReadAllText(WantFile)), out _) ?? PcWant.QuestieOnly(); }
            catch (Exception) { }
            return PcWant.QuestieOnly();
        }

        // ---------------------------------------------------------------- state (what Desktop put in, per flavor folder)

        Dictionary<string, object> LoadState()
        {
            try { return File.Exists(StateFile) ? Json.ReadObject(File.ReadAllText(StateFile)) ?? new Dictionary<string, object>() : new Dictionary<string, object>(); }
            catch (Exception) { return new Dictionary<string, object>(); }
        }

        void SaveState(Dictionary<string, object> s) { Directory.CreateDirectory(dataDir); WriteAtomic(StateFile, Json.Write(s)); }

        static Dictionary<string, object> Sub(Dictionary<string, object> d, string k)
        {
            if (d.TryGetValue(k, out var v) && v is Dictionary<string, object> o) return o;
            var n = new Dictionary<string, object>(); d[k] = n; return n;
        }

        static List<string> Strings(Dictionary<string, object> d, string k) =>
            d != null && d.TryGetValue(k, out var v) && v is object[] a ? a.Select(x => Convert.ToString(x, CultureInfo.InvariantCulture)).ToList()
            : d != null && d.TryGetValue(k, out var v2) && v2 is List<string> l ? l : new List<string>();

        /// <summary>every AddOns folder Desktop put into [flavorDir] (managed packages and imported add-ons)</summary>
        public HashSet<string> Ours(string flavorDir)
        {
            var st = Sub(LoadState(), Key(flavorDir));
            var res = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var kv in st) if (kv.Value is Dictionary<string, object> e) foreach (var f in Strings(e, "folders")) res.Add(f);
            return res;
        }

        static string Key(string flavorDir) => Path.GetFullPath(flavorDir).TrimEnd('\\', '/').ToLowerInvariant();

        // ---------------------------------------------------------------- one run

        /// <summary>
        /// QuestieDB first, then every package the Thor pins (in or out), then the Thor's imported add-ons ([transfer] = the Thor's files;
        /// null = no Thor connected: imported ones wait), then the enabled state. One result; throws nothing.
        /// </summary>
        public PcResult Run(string flavorDir, PcWant want, IPcTransfer transfer, CancellationToken ct = default(CancellationToken))
        {
            var r = new PcResult();
            if (flavorDir == null || !Directory.Exists(flavorDir)) { r.Add("add-ons", "failed", "game folder not found"); return r; }
            var addons = Path.Combine(flavorDir, "Interface", "AddOns");
            try { Directory.CreateDirectory(addons); } catch (Exception e) { r.Add("add-ons", "failed", e.Message); return r; }
            SweepStaging(flavorDir);
            want = want ?? PcWant.QuestieOnly();
            // 1. QuestieDB (the user: "questie especially"): wanted always, as on the Thor
            if (want.QuestieDb) Guard(r, "QuestieDB", () => QuestieDb(flavorDir, r, ct));
            // 2. the Thor's pinned packages
            foreach (var p in want.Managed)
            {
                if (ct.IsCancellationRequested) break;
                Guard(r, p.Name, () => Managed(flavorDir, p, r, ct));
            }
            // a package Desktop put in that the Thor no longer lists at all (a newer Thor dropped it): out, as a "not wanted"
            if (want.FromThor)
            {
                var listed = new HashSet<string>(want.Managed.Select(p => p.StateKey), StringComparer.OrdinalIgnoreCase);
                var st = Sub(LoadState(), Key(flavorDir));
                foreach (var k in st.Keys.ToList())
                    if (k.StartsWith("pkg:", StringComparison.Ordinal) && !listed.Contains(k.Substring(4)))
                        Guard(r, k.Substring(4), () => MoveOut(flavorDir, k, k.Substring(4), r));
            }
            // 3. the imported ones
            if (want.FromThor) Imported(flavorDir, want, transfer, r, ct);
            // 4. their enabled state (only Desktop's folders)
            if (want.FromThor && want.Enabled.Count > 0)
            {
                if (gameRunning()) r.Add("enabled state", "deferred", "WoW is running");
                else
                {
                    var ours = Ours(flavorDir);
                    var map = want.Enabled.Where(kv => ours.Contains(kv.Key) && !kv.Key.StartsWith("WowCompanion", StringComparison.OrdinalIgnoreCase))
                        .ToDictionary(kv => kv.Key, kv => kv.Value, StringComparer.OrdinalIgnoreCase);
                    if (map.Count > 0)
                    {
                        var m = AddonsTxt.Mirror(Path.Combine(flavorDir, "WTF"), want.AcctDir, map, gameRunning);
                        r.EnabledLine = m;
                        if (m.Length > 0) Log.I("pc add-ons: AddOns.txt: " + m);
                    }
                }
            }
            return r;
        }

        void Guard(PcResult r, string name, Action a)
        {
            try { a(); }
            catch (Later e) { r.Add(name, "deferred", e.Message); }
            catch (Exception e) { r.Add(name, "failed", Reason(e)); }
        }

        public static string Reason(Exception e)
        {
            for (var x = e; x != null; x = x.InnerException)
                if (x is WebException || x is HttpRequestException || x is System.Net.Sockets.SocketException) return "offline";
            return e.Message;
        }

        /// <summary>not a failure: WoW started, not enough room, the Thor gone: the next run carries on</summary>
        public sealed class Later : Exception { public Later(string m) : base(m) { } }

        // ---------------------------------------------------------------- QuestieDB

        void QuestieDb(string flavorDir, PcResult r, CancellationToken ct)
        {
            const string name = "QuestieDB";
            var addons = Path.Combine(flavorDir, "Interface", "AddOns");
            var target = Path.Combine(addons, "QuestieDB");
            var all = LoadState();
            var st = Sub(all, Key(flavorDir));
            const string sk = "questiedb";
            var mine = st.TryGetValue(sk, out var v) ? v as Dictionary<string, object> : null;
            bool ours = mine != null;
            var have = TocVersion(target);
            if (Directory.Exists(target) && !ours) { r.Add(name, "yours", (have ?? "a copy") + " of yours is there: left as it is"); return; }
            if (ours && have != null && mine.Str("in") == have &&
                DateTime.TryParse(mine.Str("checked"), CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var at) && Now() - at < QuestieRecheck)
            { r.Add(name, "ok", have); return; }
            // the latest release, as the Thor's DependencyAddons reads it
            string rel;
            try { rel = http.GetText(Api + "/repos/" + QuestieRepo + "/releases/latest"); }
            catch (Exception e)
            {
                if (have != null) { r.Add(name, "ok", have + " kept (" + Reason(e) + ")"); return; }
                throw;
            }
            var o = Json.ReadObject(rel) ?? throw new IOException("no release");
            var tag = o.Str("tag_name") ?? throw new IOException("no release");
            var latest = tag.StartsWith("v") ? tag.Substring(1) : tag;
            if (have != null && CompareDotted(have, latest) >= 0)
            {
                mine["checked"] = Now().ToString("o"); SaveState(all);
                r.Add(name, "ok", have); return;
            }
            var assets = (o.TryGetValue("assets", out var av) && av is object[] arr ? arr : new object[0]).OfType<Dictionary<string, object>>().ToList();
            var asset = assets.FirstOrDefault(a => QuestieAsset.IsMatch(a.Str("name") ?? "")) ?? throw new IOException("no QuestieDB-Forever.zip in " + tag);
            long size = asset.Long("size");
            var sha = (asset.Str("digest") ?? "").Replace("sha256:", "").ToLowerInvariant();
            if (!Regex.IsMatch(sha, "^[0-9a-f]{64}$"))
            {
                var sums = assets.FirstOrDefault(a => string.Equals(a.Str("name"), "SHA256SUMS", StringComparison.OrdinalIgnoreCase));
                sha = "";
                if (sums != null && DsfDesktop.Thor.ReleaseFeed.ParseSums(http.GetText(sums.Str("browser_download_url"))).TryGetValue(asset.Str("name"), out var s2)) sha = s2;
            }
            if (size < 1 || size > MaxQuestieDb) throw new IOException("QuestieDB has an unexpected size (" + size + ")");
            if (!Regex.IsMatch(sha, "^[0-9a-f]{64}$")) throw new IOException("QuestieDB has no checksum");
            var p = new PcPkg { Id = "questiedb", Key = "core", Title = name, Tag = latest, Asset = asset.Str("name"), Bytes = size, Sha256 = sha, Url = asset.Str("browser_download_url") };
            var zip = Fetch(p, ct);
            InstallZip(flavorDir, p, zip, sk, r, have == null ? "installed" : "updated", requireFolder: "QuestieDB");
            all = LoadState();
            var e2 = Sub(Sub(all, Key(flavorDir)), sk);
            e2["checked"] = Now().ToString("o");
            SaveState(all);
        }

        // ---------------------------------------------------------------- the Thor's pinned packages

        void Managed(string flavorDir, PcPkg p, PcResult r, CancellationToken ct)
        {
            var bad = p.Invalid();
            if (bad != null) { r.Add(p.Name, "refused", bad); return; }
            var sk = "pkg:" + p.StateKey;
            var st = Sub(LoadState(), Key(flavorDir));
            var mine = st.TryGetValue(sk, out var v) ? v as Dictionary<string, object> : null;
            if (!p.Want)
            {
                if (mine != null) MoveOut(flavorDir, sk, p.Name, r);
                return;
            }
            var addons = Path.Combine(flavorDir, "Interface", "AddOns");
            if (mine != null && mine.Str("in") == p.Tag)
            {
                var folders = Strings(mine, "folders");
                var broken = folders.FirstOrDefault(f => TocVersion(Path.Combine(addons, f)) == null);
                if (broken == null) { r.Add(p.Name, "ok", p.Tag); return; }
                Log.I("pc add-ons: " + p.Name + ": " + broken + " is missing or has no .toc: installed again");
            }
            // a folder of the package's own names that Desktop didn't put in: the player's, left alone
            var theirs = p.Folders.FirstOrDefault(f => Directory.Exists(Path.Combine(addons, f)) && !(mine != null && Strings(mine, "folders").Contains(f, StringComparer.OrdinalIgnoreCase)));
            if (theirs != null) { r.Add(p.Name, "yours", theirs + " of yours is there: left as it is"); return; }
            var zip = Fetch(p, ct);
            InstallZip(flavorDir, p, zip, sk, r, mine == null ? "installed" : "updated", requireFolder: null);
        }

        // ---------------------------------------------------------------- download (resumed, size + sha256)

        string Fetch(PcPkg p, CancellationToken ct)
        {
            Directory.CreateDirectory(DlDir);
            var zip = Path.Combine(DlDir, Safe(p.Id) + "-" + Safe(p.Key) + "-" + Safe(p.Asset));
            if (File.Exists(zip) && new FileInfo(zip).Length == p.Bytes && Sha256File(zip) == p.Sha256) return zip;
            var part = zip + ".part";
            long have = File.Exists(part) ? new FileInfo(part).Length : 0;
            if (have > p.Bytes) { File.Delete(part); have = 0; }
            if (Free(DlDir) < p.Bytes - have + Margin) throw new Later("needs " + Mb(p.Bytes - have + Margin) + " free to download");
            var url = p.Url;
            if (url == null)
            {
                // the release asset by its pinned tag and name, its size as pinned (ThirdPartyAddons.releaseUrl)
                var repo = Sources[p.Id].Substring(3);
                var rel = Json.ReadObject(http.GetText(Api + "/repos/" + repo + "/releases/tags/" + Uri.EscapeDataString(p.Tag))) ?? throw new IOException("no release " + p.Tag);
                var a = (rel.TryGetValue("assets", out var av) && av is object[] arr ? arr : new object[0]).OfType<Dictionary<string, object>>()
                    .FirstOrDefault(x => x.Str("name") == p.Asset) ?? throw new IOException("no " + p.Asset + " in " + p.Tag);
                if (a.Long("size") != p.Bytes) throw new IOException(p.Asset + " is not the size it was pinned at");
                url = a.Str("browser_download_url");
            }
            if (url == null || !url.StartsWith("https://", StringComparison.OrdinalIgnoreCase) && !url.StartsWith("http://127.0.0.1", StringComparison.Ordinal))
                throw new IOException("not an HTTPS download");
            Progress("Downloading " + p.Name, have, p.Bytes);
            http.Download(url, part, p.Bytes, got => { Progress("Downloading " + p.Name, got, p.Bytes); return !ct.IsCancellationRequested; });
            if (new FileInfo(part).Length != p.Bytes) throw new Later("the download of " + p.Name + " is not complete yet");
            if (Sha256File(part) != p.Sha256) { File.Delete(part); throw new IOException("the download's checksum is wrong"); }
            if (File.Exists(zip)) File.Delete(zip);
            File.Move(part, zip);
            return zip;
        }

        // ---------------------------------------------------------------- unpack, stage, swap

        static readonly Regex Notice = new Regex(@"^(LICEN[CS]E|COPYING|THIRD[_-]?PARTY|NOTICES?|CREDITS)([._-].*)?$", RegexOptions.IgnoreCase);

        void InstallZip(string flavorDir, PcPkg p, string zip, string sk, PcResult r, string verb, string requireFolder)
        {
            var iface = Path.Combine(flavorDir, "Interface");
            var stage = Path.Combine(iface, Staging, Safe(p.Id) + "-" + Safe(p.Key));
            if (Free(iface) < p.Bytes * 2 + Margin) throw new Later("needs " + Mb(p.Bytes * 2 + Margin) + " free for the game");
            try
            {
                if (Directory.Exists(stage)) Directory.Delete(stage, true);        // our own leftover only
                Progress("Unpacking " + p.Name, 0, p.Bytes);
                Unpack(zip, stage, p.Bytes * 3 / 2 + 50_000_000);
                // the zip's licence and notice files into every add-on folder (ThirdPartyAddons.keepNotices)
                var notices = Directory.GetFiles(stage).Where(f => Notice.IsMatch(Path.GetFileName(f))).ToList();
                var folders = Directory.GetDirectories(stage).Where(d => TocVersion(d) != null).Select(Path.GetFileName).ToList();
                if (folders.Count == 0) throw new IOException("the package has no add-on in it");
                if (requireFolder != null && !folders.Contains(requireFolder, StringComparer.OrdinalIgnoreCase)) throw new IOException("the package has no " + requireFolder + " .toc");
                var bad = folders.FirstOrDefault(f => Protected(f));
                if (bad != null) throw new IOException("the package brings " + bad + ", not an add-on Desktop puts in");
                foreach (var d in folders) foreach (var n in notices)
                {
                    var t = Path.Combine(stage, d, Path.GetFileName(n));
                    if (!File.Exists(t)) File.Copy(n, t);
                }
                Swap(flavorDir, stage, folders, sk, p.Tag, null, p.Name);
                r.Add(p.Name, verb, p.Tag);
                Log.I("pc add-ons: " + p.Name + " " + p.Tag + " " + verb);
                try { File.Delete(zip); } catch (Exception) { }                      // in: the download's room back
            }
            finally { try { if (Directory.Exists(stage)) Directory.Delete(stage, true); } catch (Exception) { } TryRemoveEmpty(Path.Combine(iface, Staging)); }
        }

        /// <summary>the staged [folders] into AddOns: each of Desktop's old copies to the backup, the new one in (a rename); any failure puts
        /// back every folder moved so far. A folder that is there and not Desktop's stops the whole package before anything moves</summary>
        void Swap(string flavorDir, string stage, List<string> folders, string sk, string tag, Dictionary<string, object> extra, string name)
        {
            var iface = Path.Combine(flavorDir, "Interface");
            var addons = Path.Combine(iface, "AddOns");
            var all = LoadState();
            var st = Sub(all, Key(flavorDir));
            var mine = st.TryGetValue(sk, out var v) ? v as Dictionary<string, object> : null;
            var ours = Ours(flavorDir);
            var theirs = folders.FirstOrDefault(f => Directory.Exists(Path.Combine(addons, f)) && !ours.Contains(f));
            if (theirs != null) throw new IOException(theirs + " of yours is there: left as it is");
            if (gameRunning()) throw new Later("WoW is running: at the next start");
            var stamp = Now().ToLocalTime().ToString("yyyyMMdd-HHmmss", CultureInfo.InvariantCulture);
            var done = new List<KeyValuePair<string, string>>();               // target, its old copy in the backup (or null)
            try
            {
                foreach (var f in folders)
                {
                    var target = Path.Combine(addons, f);
                    string old = Directory.Exists(target) ? MoveToBackup(iface, target, stamp) : null;
                    done.Add(new KeyValuePair<string, string>(target, old));
                    MoveDir(Path.Combine(stage, f), target);
                }
            }
            catch (Exception)
            {
                PutBack(done);
                throw;
            }
            // Desktop's folders of this package that the new version no longer has go to the backup too
            if (mine != null)
                foreach (var gone in Strings(mine, "folders").Where(f => !folders.Contains(f, StringComparer.OrdinalIgnoreCase)))
                {
                    var t = Path.Combine(addons, gone);
                    if (Directory.Exists(t)) try { MoveToBackup(iface, t, stamp); } catch (Exception e) { Log.I("pc add-ons: " + gone + ": " + e.Message); }
                }
            var e2 = new Dictionary<string, object> { ["in"] = tag, ["folders"] = folders.ToArray(), ["at"] = Now().ToString("o") };
            if (extra != null) foreach (var kv in extra) e2[kv.Key] = kv.Value;
            all = LoadState();
            Sub(all, Key(flavorDir))[sk] = e2;
            SaveState(all);
            PruneBackups(iface, folders);
        }

        void MoveOut(string flavorDir, string sk, string name, PcResult r)
        {
            var iface = Path.Combine(flavorDir, "Interface");
            var all = LoadState();
            var st = Sub(all, Key(flavorDir));
            if (!(st.TryGetValue(sk, out var v) && v is Dictionary<string, object> mine)) return;
            if (gameRunning()) throw new Later("WoW is running: moves out at the next start");
            var stamp = Now().ToLocalTime().ToString("yyyyMMdd-HHmmss", CultureInfo.InvariantCulture);
            foreach (var f in Strings(mine, "folders"))
            {
                var t = Path.Combine(iface, "AddOns", f);
                if (Directory.Exists(t)) MoveToBackup(iface, t, stamp);
            }
            st.Remove(sk);
            SaveState(all);
            r.Add(name, "removed", "moved to the backup");
            Log.I("pc add-ons: " + name + " moved to the backup (the Thor doesn't use it)");
        }

        void PutBack(List<KeyValuePair<string, string>> done)
        {
            for (int i = done.Count - 1; i >= 0; i--)
            {
                var target = done[i].Key; var old = done[i].Value;
                try { if (Directory.Exists(target)) Directory.Delete(target, true); } catch (Exception) { }   // only what this run put in
                try { if (old != null) MoveDir(old, target); } catch (Exception e) { Log.I("pc add-ons: " + Path.GetFileName(target) + ": the old copy stays in the backup (" + e.Message + ")"); }
            }
        }

        static string MoveToBackup(string iface, string target, string stamp)
        {
            var home = Path.Combine(iface, Backup);
            Directory.CreateDirectory(home);
            var baseName = Path.GetFileName(target) + "-" + BackupWord(TocVersion(target)) + "-" + stamp;
            var b = Path.Combine(home, baseName);
            int n = 2;
            while (Directory.Exists(b) || File.Exists(b)) b = Path.Combine(home, baseName + "~" + n++);
            MoveDir(target, b);
            return b;
        }

        /// <summary>Desktop's own backups of [folders]: the newest one of each kept (the PC's C: is small); never another name's</summary>
        static void PruneBackups(string iface, List<string> folders)
        {
            var home = Path.Combine(iface, Backup);
            if (!Directory.Exists(home)) return;
            foreach (var f in folders)
            {
                var mine = Directory.GetDirectories(home, f + "-*").Where(d => Regex.IsMatch(Path.GetFileName(d), "^" + Regex.Escape(f) + @"-[A-Za-z0-9._-]+-\d{8}-\d{6}(~\d+)?$"))
                    .OrderByDescending(d => Directory.GetCreationTimeUtc(d)).Skip(1).ToList();
                foreach (var d in mine) try { Directory.Delete(d, true); } catch (Exception) { }
            }
        }

        public static string BackupWord(string v)
        {
            var w = new string((v ?? "").Select(c => c < 128 && char.IsLetterOrDigit(c) || c == '.' || c == '_' || c == '-' ? c : '_').ToArray());
            w = Regex.Replace(w, @"\.{2,}", "_").Trim('.');
            if (w.Length > 40) w = w.Substring(0, 40);
            return w.Length == 0 ? "unknown" : w;
        }

        static void MoveDir(string from, string to)
        {
            try { Directory.Move(from, to); }
            catch (IOException) when (!SameRoot(from, to))
            {
                CopyTree(from, to);
                Directory.Delete(from, true);
            }
        }

        static bool SameRoot(string a, string b) => string.Equals(Path.GetPathRoot(Path.GetFullPath(a)), Path.GetPathRoot(Path.GetFullPath(b)), StringComparison.OrdinalIgnoreCase);

        static void CopyTree(string from, string to)
        {
            Directory.CreateDirectory(to);
            foreach (var d in Directory.GetDirectories(from, "*", SearchOption.AllDirectories)) Directory.CreateDirectory(to + d.Substring(from.Length));
            foreach (var f in Directory.GetFiles(from, "*", SearchOption.AllDirectories)) File.Copy(f, to + f.Substring(from.Length), false);
        }

        static void TryRemoveEmpty(string d) { try { if (Directory.Exists(d) && !Directory.EnumerateFileSystemEntries(d).Any()) Directory.Delete(d); } catch (Exception) { } }

        /// <summary>our own staging leftovers (a Desktop killed mid-install) go at every run; nothing else in Interface is touched</summary>
        void SweepStaging(string flavorDir)
        {
            var s = Path.Combine(flavorDir, "Interface", Staging);
            if (!Directory.Exists(s)) return;
            foreach (var d in Directory.GetDirectories(s)) try { Directory.Delete(d, true); } catch (Exception) { }
            TryRemoveEmpty(s);
        }

        /// <summary>the zip into [to]: no path outside it, at most <see cref="MaxEntries"/> files and [maxBytes] unpacked</summary>
        public static void Unpack(string zip, string to, long maxBytes)
        {
            Directory.CreateDirectory(to);
            var root = Path.GetFullPath(to).TrimEnd('\\') + "\\";
            int files = 0; long bytes = 0;
            var buf = new byte[65536];
            using (var z = ZipFile.OpenRead(zip))
                foreach (var e in z.Entries)
                {
                    var name = e.FullName.Replace('\\', '/');
                    if (name.Length == 0) continue;
                    if (name.StartsWith("/") || Regex.IsMatch(name, "^[A-Za-z]:") || name.Split('/').Any(s => s == "..")) throw new IOException("the package has a bad path (" + name + ")");
                    var out1 = Path.GetFullPath(Path.Combine(to, name.Replace('/', '\\')));
                    if (!out1.StartsWith(root, StringComparison.OrdinalIgnoreCase)) throw new IOException("the package has a bad path (" + name + ")");
                    if (name.EndsWith("/")) { Directory.CreateDirectory(out1); continue; }
                    if (++files > MaxEntries) throw new IOException("the package has too many files");
                    Directory.CreateDirectory(Path.GetDirectoryName(out1));
                    using (var i = e.Open())
                    using (var o = File.Create(out1))
                        for (int n; (n = i.Read(buf, 0, buf.Length)) > 0;)
                        {
                            bytes += n;
                            if (bytes > maxBytes) throw new IOException("the package unpacks too large");
                            o.Write(buf, 0, n);
                        }
                }
            if (files == 0) throw new IOException("the package is empty");
        }

        // ---------------------------------------------------------------- the player's imported add-ons (file by file from the Thor)

        void Imported(string flavorDir, PcWant want, IPcTransfer transfer, PcResult r, CancellationToken ct)
        {
            var addons = Path.Combine(flavorDir, "Interface", "AddOns");
            var listed = new HashSet<string>(want.Imported.Select(i => i.Folder), StringComparer.OrdinalIgnoreCase);
            // removed from the Thor's Imported list: Desktop's copy out to the backup
            var st = Sub(LoadState(), Key(flavorDir));
            foreach (var k in st.Keys.ToList())
                if (k.StartsWith("imp:", StringComparison.Ordinal) && !listed.Contains(k.Substring(4)))
                    Guard(r, k.Substring(4), () => MoveOut(flavorDir, k, k.Substring(4), r));
            foreach (var i in want.Imported)
            {
                if (ct.IsCancellationRequested) break;
                Guard(r, i.Folder, () => ImportOne(flavorDir, addons, i, transfer, r, ct));
            }
        }

        void ImportOne(string flavorDir, string addons, PcImport i, IPcTransfer transfer, PcResult r, CancellationToken ct)
        {
            var bad = i.Invalid();
            if (bad != null) { r.Add(i.Folder, "refused", bad); return; }
            var sk = "imp:" + i.Folder;
            var st = Sub(LoadState(), Key(flavorDir));
            var mine = st.TryGetValue(sk, out var v) ? v as Dictionary<string, object> : null;
            var target = Path.Combine(addons, i.Folder);
            if (mine == null && Directory.Exists(target)) { r.Add(i.Folder, "yours", "a copy of yours is there: left as it is"); return; }
            var tree = i.Tree();
            if (mine != null && mine.Str("tree") == tree && Directory.Exists(target) && Directory.GetFiles(target, "*.toc").Length > 0) { r.Add(i.Folder, "ok", i.Version ?? ""); return; }
            if (!i.Enabled) { r.Add(i.Folder, "skipped", "off on the Thor"); return; }
            if (i.Big && !i.Allowed) { r.Add(i.Folder, "waiting", "waits for your OK on the Thor (" + Mb(i.Bytes) + ")"); return; }
            if (transfer == null) { r.Add(i.Folder, "deferred", "copied from the Thor when it connects"); return; }
            var cache = Path.Combine(ImpDir, i.Folder);
            var root = Path.GetFullPath(cache).TrimEnd('\\') + "\\";
            if (Free(dataDir) < i.Bytes + Margin) throw new Later("needs " + Mb(i.Bytes + Margin) + " free to copy it");
            long doneBytes = 0;
            foreach (var f in i.Files)
            {
                var local = Path.GetFullPath(Path.Combine(cache, f.P.Replace('/', '\\')));
                if (!local.StartsWith(root, StringComparison.OrdinalIgnoreCase)) throw new IOException("a bad path in the Thor's list");
                if (File.Exists(local) && new FileInfo(local).Length == f.N && Sha256File(local) == f.H) { doneBytes += f.N; continue; }
                Directory.CreateDirectory(Path.GetDirectoryName(local));
                var part = local + ".part";
                long off = File.Exists(part) ? new FileInfo(part).Length : 0;
                if (off > f.N) { File.Delete(part); off = 0; }
                using (var o = new FileStream(part, FileMode.Append, FileAccess.Write))
                    while (off < f.N)
                    {
                        if (ct.IsCancellationRequested) throw new Later("stopped");
                        var len = (int)Math.Min(Chunk, f.N - off);
                        var b = transfer.Get(i.Folder, f.P, off, len) ?? throw new Later("the Thor went away");
                        if (b.Length == 0 || b.Length > len) throw new IOException("the Thor sent a bad part of " + f.P);
                        o.Write(b, 0, b.Length);
                        off += b.Length;
                        Progress("Copying " + i.Folder + " from the Thor", doneBytes + off, i.Bytes);
                    }
                if (Sha256File(part) != f.H) { File.Delete(part); throw new IOException(f.P + " didn't arrive whole (checksum)"); }
                if (File.Exists(local)) File.Delete(local);
                File.Move(part, local);
                doneBytes += f.N;
            }
            // nothing in the cache the list doesn't name
            var want = new HashSet<string>(i.Files.Select(f => Path.GetFullPath(Path.Combine(cache, f.P.Replace('/', '\\')))), StringComparer.OrdinalIgnoreCase);
            foreach (var x in Directory.GetFiles(cache, "*", SearchOption.AllDirectories)) if (!want.Contains(x)) File.Delete(x);
            if (Directory.GetFiles(cache, "*.toc").Length == 0) throw new IOException("no .toc in it");
            var iface = Path.Combine(flavorDir, "Interface");
            var stage = Path.Combine(iface, Staging, "imp-" + Safe(i.Folder));
            try
            {
                if (Directory.Exists(stage)) Directory.Delete(stage, true);
                Directory.CreateDirectory(stage);
                CopyTree(cache, Path.Combine(stage, i.Folder));
                Swap(flavorDir, stage, new List<string> { i.Folder }, sk, i.Version ?? "", new Dictionary<string, object> { ["tree"] = tree }, i.Folder);
                r.Add(i.Folder, mine == null ? "installed" : "updated", i.Version ?? "");
                Log.I("pc add-ons: imported " + i.Folder + " " + (i.Version ?? "") + " copied from the Thor");
                try { Directory.Delete(cache, true); } catch (Exception) { }
            }
            finally { try { if (Directory.Exists(stage)) Directory.Delete(stage, true); } catch (Exception) { } TryRemoveEmpty(Path.Combine(iface, Staging)); }
        }

        // ---------------------------------------------------------------- small things

        /// <summary>DS Forever's own folders, Blizzard's and our staging / backup names: never put in from a list</summary>
        public static bool Protected(string f)
        {
            var n = f.ToLowerInvariant();
            return n.StartsWith("wowcompanion") || n.StartsWith("blizzard_") || n.StartsWith(Staging.ToLowerInvariant()) || n.StartsWith(Backup.ToLowerInvariant());
        }

        static readonly Regex BadChars = new Regex(@"[\\/:*?""<>|\x00-\x1F]");
        /// <summary>one plain AddOns folder name (ImportedAddons.validFolder)</summary>
        public static bool ValidFolder(string n) => !string.IsNullOrEmpty(n) && n.Length <= 100 && !BadChars.IsMatch(n) && n.Trim() == n && !n.StartsWith(".") && !n.EndsWith(".");

        /// <summary>numeric parts, left to right ("1.0.10" > "1.0.9"; DependencyAddons.compare)</summary>
        public static int CompareDotted(string a, string b)
        {
            var x = Regex.Matches(a ?? "", @"\d+").Cast<Match>().Select(m => long.Parse(m.Value, CultureInfo.InvariantCulture)).ToList();
            var y = Regex.Matches(b ?? "", @"\d+").Cast<Match>().Select(m => long.Parse(m.Value, CultureInfo.InvariantCulture)).ToList();
            for (int i = 0; i < Math.Max(x.Count, y.Count); i++)
            {
                int d = (i < x.Count ? x[i] : 0).CompareTo(i < y.Count ? y[i] : 0);
                if (d != 0) return d;
            }
            return 0;
        }

        static string Safe(string s) => Regex.Replace(s ?? "", @"[^A-Za-z0-9._-]", "_");

        /// <summary>"## Version: x" of the folder's .toc files, "" for a .toc with no Version, null when there is no .toc</summary>
        public static string TocVersion(string dir)
        {
            try
            {
                if (!Directory.Exists(dir)) return null;
                var tocs = Directory.GetFiles(dir, "*.toc");
                if (tocs.Length == 0) return null;
                foreach (var t in tocs)
                    foreach (var l in File.ReadLines(t))
                    {
                        var m = Regex.Match(l.Trim(), @"^##\s*Version\s*:\s*v?(\S+)", RegexOptions.IgnoreCase);
                        if (m.Success) return m.Groups[1].Value;
                    }
                return null;
            }
            catch (Exception) { return null; }
        }

        public static string Sha256File(string path)
        {
            using (var sha = SHA256.Create())
            using (var f = File.OpenRead(path)) return Crypto.Hex(sha.ComputeHash(f));
        }

        public static string Mb(long b) => b >= 1_000_000_000 ? (b / 1e9).ToString("0.0", CultureInfo.InvariantCulture) + " GB" : ((b + 999_999) / 1_000_000) + " MB";

        static void WriteAtomic(string file, string text)
        {
            var tmp = file + ".new";
            File.WriteAllText(tmp, text);
            if (File.Exists(file)) File.Replace(tmp, file, null); else File.Move(tmp, file);
        }
    }

    /// <summary>one package the Thor pins (ThirdPartyAddons.Pkg): [Want] = the Thor wants it in now</summary>
    public sealed class PcPkg
    {
        public string Id, Key, Title, Tag, Asset, Sha256, Url, License;
        public long Bytes;
        public bool Want;
        public List<string> Folders = new List<string>();
        public string StateKey => Id + "/" + Key;
        public string Name => Key == "core" ? Title : Title + " " + (Key.Length > 0 ? char.ToUpperInvariant(Key[0]) + Key.Substring(1) : Key);

        /// <summary>why this pin is not taken; null = it is</summary>
        public string Invalid()
        {
            if (Id == null || !PcAddons.Sources.TryGetValue(Id, out var src)) return "not an add-on Desktop knows";
            if (string.IsNullOrEmpty(Key) || !Regex.IsMatch(Key, "^[a-z0-9_]{1,20}$")) return "a bad package name";
            if (string.IsNullOrEmpty(Tag) || Tag.Length > 60 || string.IsNullOrEmpty(Asset) || !Regex.IsMatch(Asset, @"^[A-Za-z0-9._ -]{1,100}\.zip$")) return "a bad pin";
            if (!Regex.IsMatch(Sha256 ?? "", "^[0-9a-f]{64}$")) return "no checksum";
            if (Bytes < 1 || Bytes > PcAddons.MaxPkg) return "a bad size";
            if (Folders.Any(f => !PcAddons.ValidFolder(f) || PcAddons.Protected(f))) return "a bad folder name";
            if (src == "cdn")
            {
                if (Url == null || !Url.StartsWith(PcAddons.CdnPrefix, StringComparison.Ordinal) || !Url.EndsWith("/" + Asset, StringComparison.Ordinal) || Url.Contains("..")) return "not from its own source";
            }
            else if (Url != null) return "not from its own source";          // a GitHub package is found by its tag, never by a URL sent
            return null;
        }

        public static PcPkg Of(Dictionary<string, object> d) => d == null ? null : new PcPkg
        {
            Id = d.Str("id"), Key = d.Str("key"), Title = d.Str("title") ?? d.Str("id"), Tag = d.Str("tag"), Asset = d.Str("asset"), Bytes = d.Long("bytes"),
            Sha256 = (d.Str("sha256") ?? "").ToLowerInvariant(), Url = string.IsNullOrEmpty(d.Str("url")) ? null : d.Str("url"), License = d.Str("license"),
            Want = d.Bool("want"),
            Folders = (d.TryGetValue("folders", out var f) && f is object[] a ? a : new object[0]).Select(x => Convert.ToString(x, CultureInfo.InvariantCulture)).ToList(),
        };

        public Dictionary<string, object> ToJson() => new Dictionary<string, object>
        {
            ["id"] = Id, ["key"] = Key, ["title"] = Title, ["tag"] = Tag, ["asset"] = Asset, ["bytes"] = Bytes, ["sha256"] = Sha256, ["url"] = Url ?? "",
            ["license"] = License ?? "", ["want"] = Want, ["folders"] = Folders.ToArray(),
        };
    }

    public sealed class PcFile { public string P, H; public long N; }

    /// <summary>one add-on the player imported on the Thor: its files (path, size, sha256), enabled on the Thor, [Big] = asked first</summary>
    public sealed class PcImport
    {
        public string Folder, Version;
        public long Bytes;
        public bool Enabled = true, Big, Allowed;
        public List<PcFile> Files = new List<PcFile>();

        /// <summary>the content's one hash: sha256 over the sorted "path\nsize\nsha256\n" lines (the Thor's PcAddonsWant.tree)</summary>
        public string Tree()
        {
            var sb = new StringBuilder();
            foreach (var f in Files.OrderBy(x => x.P, StringComparer.Ordinal)) sb.Append(f.P).Append('\n').Append(f.N.ToString(CultureInfo.InvariantCulture)).Append('\n').Append(f.H).Append('\n');
            return Crypto.Sha256Hex(sb.ToString());
        }

        public string Invalid()
        {
            if (!PcAddons.ValidFolder(Folder) || PcAddons.Protected(Folder)) return "a bad folder name";
            if (Files.Count == 0 || Files.Count > PcAddons.MaxImportFiles) return "a bad file list";
            if (Files.Sum(f => f.N) > PcAddons.MaxImportBytes) return "too big";
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var f in Files)
            {
                var p = f.P ?? "";
                if (p.Length == 0 || p.Length > 400 || p.StartsWith("/") || p.Contains("\\") || p.Contains(":") || p.Split('/').Any(s => s.Length == 0 || s == "." || s == ".." || !PcAddons.ValidFolder(s)))
                    return "a bad path in its list";
                if (f.N < 0 || !Regex.IsMatch(f.H ?? "", "^[0-9a-f]{64}$")) return "a bad file in its list";
                if (!seen.Add(p)) return "a name twice";
            }
            if (!Files.Any(f => !f.P.Contains("/") && f.P.EndsWith(".toc", StringComparison.OrdinalIgnoreCase))) return "no .toc in it";
            return null;
        }

        public static PcImport Of(Dictionary<string, object> d) => d == null ? null : new PcImport
        {
            Folder = d.Str("folder"), Version = d.Str("version"), Enabled = !d.ContainsKey("enabled") || d.Bool("enabled"), Big = d.Bool("big"), Allowed = d.Bool("allowed"),
            Files = (d.TryGetValue("files", out var f) && f is object[] a ? a : new object[0]).OfType<Dictionary<string, object>>()
                .Select(x => new PcFile { P = x.Str("p"), N = x.Long("n", -1), H = (x.Str("h") ?? "").ToLowerInvariant() }).ToList(),
            Bytes = 0,
        }.WithBytes();

        PcImport WithBytes() { Bytes = Files.Sum(x => Math.Max(0, x.N)); return this; }

        public Dictionary<string, object> ToJson() => new Dictionary<string, object>
        {
            ["folder"] = Folder, ["version"] = Version ?? "", ["enabled"] = Enabled, ["big"] = Big, ["allowed"] = Allowed,
            ["files"] = Files.Select(f => new Dictionary<string, object> { ["p"] = f.P, ["n"] = f.N, ["h"] = f.H }).ToArray(),
        };
    }

    /// <summary>the Thor's "addons_want": what it manages and wants, its imported add-ons, and which folders are on / off</summary>
    public sealed class PcWant
    {
        public bool QuestieDb = true, FromThor;
        public string Acct;
        public List<PcPkg> Managed = new List<PcPkg>();
        public List<PcImport> Imported = new List<PcImport>();
        public Dictionary<string, bool> Enabled = new Dictionary<string, bool>(StringComparer.OrdinalIgnoreCase);
        /// <summary>the PC account folder the enabled state goes into (AppController fills it from Acct: WowSvSync.PickAccount); null = every one</summary>
        public string AcctDir;

        public static PcWant QuestieOnly() => new PcWant();

        public static PcWant Parse(Dictionary<string, object> m, out string why)
        {
            why = null;
            if (m == null) { why = "no list"; return null; }
            var w = new PcWant { FromThor = true, Acct = m.Str("acct") };
            if (m.TryGetValue("questiedb", out var q) && q is Dictionary<string, object> qd && qd.ContainsKey("want")) w.QuestieDb = qd.Bool("want");
            if (m.TryGetValue("managed", out var mv) && mv is object[] ma)
                w.Managed = ma.OfType<Dictionary<string, object>>().Select(PcPkg.Of).Where(p => p != null).Take(64).ToList();
            if (m.TryGetValue("imported", out var iv) && iv is object[] ia)
                w.Imported = ia.OfType<Dictionary<string, object>>().Select(PcImport.Of).Where(p => p != null).Take(200).ToList();
            if (m.TryGetValue("enabled", out var ev) && ev is Dictionary<string, object> ed)
                foreach (var kv in ed.Take(2000)) if (kv.Value is bool b && PcAddons.ValidFolder(kv.Key)) w.Enabled[kv.Key] = b;
            return w;
        }

        public Dictionary<string, object> ToJson() => new Dictionary<string, object>
        {
            ["t"] = "addons_want", ["v"] = 1, ["acct"] = Acct ?? "",
            ["questiedb"] = new Dictionary<string, object> { ["want"] = QuestieDb },
            ["managed"] = Managed.Select(p => p.ToJson()).ToArray(),
            // the kept copy keeps the file lists: Desktop's own start can tell an imported add-on is whole (copied only while a Thor is connected)
            ["imported"] = Imported.Select(i => i.ToJson()).ToArray(),
            ["enabled"] = Enabled.ToDictionary(kv => kv.Key, kv => (object)kv.Value),
        };
    }

    public sealed class PcItem { public string Name, State, Why; }

    public sealed class PcResult
    {
        public readonly List<PcItem> Items = new List<PcItem>();
        public string EnabledLine = "";
        public void Add(string name, string state, string why) { Items.Add(new PcItem { Name = name, State = state, Why = why ?? "" }); }
        public PcItem Of(string name) => Items.LastOrDefault(i => string.Equals(i.Name, name, StringComparison.OrdinalIgnoreCase));
        /// <summary>every one in or as it should be (ok / installed / updated / removed / skipped / yours)</summary>
        public bool AllGood => Items.All(i => i.State != "failed" && i.State != "deferred" && i.State != "refused");
        public string Line()
        {
            var bad = Items.Where(i => i.State != "ok" && i.State != "skipped").Select(i => i.Name + " " + i.State + (i.Why.Length > 0 ? " (" + i.Why + ")" : ""));
            var l = string.Join("; ", bad);
            var okN = Items.Count(i => i.State == "ok");
            return (l.Length > 0 ? l + (okN > 0 ? "; " + okN + " up to date" : "") : okN + " up to date") + (EnabledLine.Length > 0 ? "; AddOns.txt: " + EnabledLine : "");
        }
        public Dictionary<string, object> ToJson() => new Dictionary<string, object>
        {
            ["line"] = Line(), ["ok"] = AllGood,
            ["items"] = Items.Select(i => new Dictionary<string, object> { ["name"] = i.Name, ["state"] = i.State, ["why"] = i.Why }).ToArray(),
        };
    }

    /// <summary>the downloads (the real one: HttpClient; the tests: a fake)</summary>
    public interface IPcHttp
    {
        string GetText(string url);
        /// <summary>[url] appended to [part] from its length (an HTTP Range), at most [expect] bytes; [progress] false = stop</summary>
        void Download(string url, string part, long expect, Func<long, bool> progress);
    }

    /// <summary>one part of an imported add-on's file from the Thor; null = the Thor went away</summary>
    public interface IPcTransfer { byte[] Get(string folder, string path, long offset, int length); }

    public sealed class PcHttp : IPcHttp
    {
        static readonly HttpClient Http = Make();
        static HttpClient Make()
        {
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            var h = new HttpClient(new HttpClientHandler { AllowAutoRedirect = true }) { Timeout = TimeSpan.FromMinutes(30) };
            h.DefaultRequestHeaders.UserAgent.ParseAdd("DSForeverDesktop");
            return h;
        }

        public string GetText(string url)
        {
            using (var req = new HttpRequestMessage(HttpMethod.Get, url))
            {
                req.Headers.Accept.ParseAdd("application/vnd.github+json");
                using (var r = Http.SendAsync(req).GetAwaiter().GetResult())
                {
                    if (!r.IsSuccessStatusCode) throw new IOException("HTTP " + (int)r.StatusCode);
                    return r.Content.ReadAsStringAsync().GetAwaiter().GetResult();
                }
            }
        }

        public void Download(string url, string part, long expect, Func<long, bool> progress)
        {
            long have = File.Exists(part) ? new FileInfo(part).Length : 0;
            using (var req = new HttpRequestMessage(HttpMethod.Get, url))
            {
                if (have > 0) req.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(have, null);
                using (var r = Http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead).GetAwaiter().GetResult())
                {
                    if (!r.IsSuccessStatusCode) throw new IOException("HTTP " + (int)r.StatusCode);
                    if (have > 0 && r.StatusCode != HttpStatusCode.PartialContent) have = 0;      // sent whole: start again
                    if (r.RequestMessage.RequestUri.Scheme != "https" && !url.StartsWith("http://127.0.0.1", StringComparison.Ordinal)) throw new IOException("a redirect off HTTPS");
                    using (var i = r.Content.ReadAsStreamAsync().GetAwaiter().GetResult())
                    using (var o = new FileStream(part, have > 0 ? FileMode.Append : FileMode.Create, FileAccess.Write))
                    {
                        var buf = new byte[256 * 1024];
                        long got = have, told = have;
                        for (int n; (n = i.Read(buf, 0, buf.Length)) > 0;)
                        {
                            o.Write(buf, 0, n); got += n;
                            if (got > expect) throw new IOException("the download is bigger than it should be");
                            if (got - told >= 4_000_000 || got == expect) { told = got; if (!progress(got)) return; }
                        }
                    }
                }
            }
        }
    }

    /// <summary>
    /// THE THOR'S ENABLED STATE IN THE PC'S AddOns.txt, only for Desktop's folders. The game keeps one per character
    /// (WTF/Account/&lt;account&gt;/&lt;realm&gt;/&lt;character&gt;/AddOns.txt) and, when the client keeps one, one per account: "&lt;folder&gt;: enabled" /
    /// "disabled". Only lines of the folders in [want] change (their value), a folder to be off that has no line gets one; every other line
    /// stays byte for byte (the player's own add-ons, TradeSkillMaster, Auctionator...). Written beside, read back, then replaced; nothing
    /// while WoW runs. Counts only (the account and character names are never logged).
    /// </summary>
    public static class AddonsTxt
    {
        public static string Mirror(string wtf, string acctDir, Dictionary<string, bool> want, Func<bool> gameRunning)
        {
            if (gameRunning()) return "not written: WoW is running";
            var accounts = acctDir != null ? new[] { acctDir } : Directory.Exists(Path.Combine(wtf, "Account"))
                ? Directory.GetDirectories(Path.Combine(wtf, "Account")).Where(d => !Path.GetFileName(d).Equals("SavedVariables", StringComparison.OrdinalIgnoreCase)).ToArray() : new string[0];
            int files = 0, written = 0, lines = 0;
            foreach (var a in accounts)
            {
                if (!Directory.Exists(a)) continue;
                var list = new List<string> { Path.Combine(a, "AddOns.txt") };
                foreach (var realm in Dirs(a)) foreach (var ch in Dirs(realm)) list.Add(Path.Combine(ch, "AddOns.txt"));
                foreach (var f in list)
                {
                    if (!File.Exists(f)) continue;
                    files++;
                    var raw = File.ReadAllBytes(f);
                    var latin = Encoding.GetEncoding(28591);
                    var (text, n) = Apply(latin.GetString(raw), want);
                    if (n == 0) continue;
                    if (gameRunning()) return files + " checked; stopped: WoW started";
                    var bytes = latin.GetBytes(text);
                    var tmp = f + ".dsf-new";
                    File.WriteAllBytes(tmp, bytes);
                    if (!File.ReadAllBytes(tmp).SequenceEqual(bytes)) { File.Delete(tmp); continue; }
                    File.Replace(tmp, f, null);
                    written++; lines += n;
                }
            }
            return files == 0 ? "" : files + " checked" + (written > 0 ? ", " + lines + " lines set in " + written : "");
        }

        static IEnumerable<string> Dirs(string d) => Directory.GetDirectories(d).Where(x => !Path.GetFileName(x).Equals("SavedVariables", StringComparison.OrdinalIgnoreCase));

        static readonly Regex Line = new Regex(@"^((?:ï»¿)?[ \t]*)([^:\r\n]+?)([ \t]*:[ \t]*)(enabled|disabled)([ \t]*)$");

        /// <summary>[text] with each wanted folder's value set (its line's spacing, ending and a BOM kept) and an off folder with no line
        /// given one; how many lines changed</summary>
        public static (string, int) Apply(string text, Dictionary<string, bool> want)
        {
            int n = 0;
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var sb = new StringBuilder(text.Length + 64);
            int start = 0;
            string eol = text.Contains("\r\n") ? "\r\n" : "\n";
            while (start <= text.Length)
            {
                int nl = text.IndexOf('\n', start);
                int end = nl < 0 ? text.Length : nl;
                bool cr = end > start && text[end - 1] == '\r';
                var body = text.Substring(start, (cr ? end - 1 : end) - start);
                var m = Line.Match(body);
                if (m.Success && want.TryGetValue(m.Groups[2].Value, out var on))
                {
                    seen.Add(m.Groups[2].Value);
                    var v = on ? "enabled" : "disabled";
                    if (m.Groups[4].Value != v) { body = m.Groups[1].Value + m.Groups[2].Value + m.Groups[3].Value + v + m.Groups[5].Value; n++; }
                }
                sb.Append(body);
                if (cr) sb.Append('\r');
                if (nl < 0) break;
                sb.Append('\n'); start = nl + 1;
            }
            foreach (var kv in want.Where(kv => !kv.Value && !seen.Contains(kv.Key)).OrderBy(kv => kv.Key, StringComparer.Ordinal))
            {
                if (sb.Length > 0 && sb[sb.Length - 1] != '\n') sb.Append(eol);
                sb.Append(kv.Key).Append(": disabled").Append(eol);
                n++;
            }
            return (sb.ToString(), n);
        }
    }
}
