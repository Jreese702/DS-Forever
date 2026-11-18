using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace DsfDesktop.Wow.Patch
{
    public enum PatchState { Idle, Checking, Waiting, Downloading, Installing, Done, Failed, Cancelled }

    /// <summary>what the patch is doing, for the Thor's bar (the "wow" message's pc_patch) and the log</summary>
    public sealed class PatchStatus
    {
        public PatchState State = PatchState.Idle;
        /// <summary>the installed and the target version ("1.60.1.70938" -> "1.60.1.70959"); "" = not known yet</summary>
        public string From = "", To = "";
        /// <summary>bytes of the changed client files done / in all (decoded sizes: 70938 -> 70959 = 127,425,344)</summary>
        public long Done, Total;
        public string File = "";
        public int FileNo, Files;
        /// <summary>a failure's code (no_answer, missing, checksum, space, busy, agent_busy, no_write, format, new_failed, error), or
        /// a wait's ("agent"); "" = none</summary>
        public string Code = "";
        /// <summary>the failure in words, for the log (never shown on the Thor as it is)</summary>
        public string Why = "";
        /// <summary>space: the MB the patch needs free</summary>
        public long NeedMb;
        public double EtaS = -1;

        public PatchStatus Copy() => (PatchStatus)MemberwiseClone();
        public bool Busy => State == PatchState.Checking || State == PatchState.Waiting || State == PatchState.Downloading || State == PatchState.Installing;
    }

    /// <summary>what the patcher reads and writes, injected (the tests use a temp game folder and a fake CDN)</summary>
    public sealed class PatchEnv
    {
        public string Root;
        public string Flavor = "_forever_";
        public string Product = "wowf";
        /// <summary>a patch server url -> its body, null = no answer</summary>
        public Func<string, string> FetchText;
        /// <summary>the patch server of a region ("http://us.patch.battle.net:1119/wowf")</summary>
        public Func<string, string> PatchBase;
        public Func<IEnumerable<string>, string, CdnClient> Cdn = (hosts, path) => new CdnClient(hosts, path);
        /// <summary>WoW of this folder is running</summary>
        public Func<bool> WowRunning = () => false;
        /// <summary>closes WoW of this folder when it is NOT signed in (an outdated client at its login, the user's 11-18 06:20 case):
        /// null = closed, else why it stays (signed in, wouldn't close); no func = never closed (a "busy" failure)</summary>
        public Func<string> CloseWow;
        /// <summary>how long a crashed client may take to go before the old one is put back (its Fatal Error window, then the kill)</summary>
        public double WowExitWaitS = 60;
        /// <summary>Blizzard's updater at work on the folder: why, null = not</summary>
        public Func<string> AgentBusy = () => null;
        public Func<string, long> FreeBytes = p => new DriveInfo(Path.GetPathRoot(Path.GetFullPath(p))).AvailableFreeSpace;
        /// <summary>Desktop's journal of an install (DataDir\patch\journal.json)</summary>
        public string JournalPath;
        /// <summary>Desktop's copy of the last good row (DataDir\patch\wowf_row.json): a missing row's template</summary>
        public string SavedRowPath;
        public Func<DateTime> Now = () => DateTime.UtcNow;
        public Action<int> Sleep = ms => Thread.Sleep(ms);
        public double AgentWaitS = 300, LockWaitS = 60, AgentPollS = 5;
        public long SpareBytes = 1L << 30;
        /// <summary>file moves (the tests make one fail): (from, to, backup or null)</summary>
        public Action<string, string, string> Swap = DefaultSwap;

        public static void DefaultSwap(string from, string to, string backup)
        {
            if (backup != null && System.IO.File.Exists(to))
            {
                Directory.CreateDirectory(Path.GetDirectoryName(backup));
                if (System.IO.File.Exists(backup)) System.IO.File.Delete(backup);
                System.IO.File.Replace(from, to, backup, true);
            }
            else
            {
                Directory.CreateDirectory(Path.GetDirectoryName(to));
                if (System.IO.File.Exists(to)) System.IO.File.Delete(to);
                System.IO.File.Move(from, to);
            }
        }
    }

    sealed class PatchFailed : Exception
    {
        public readonly string Code; public readonly long NeedMb;
        public PatchFailed(string code, string why, long needMb = 0) : base(why) { Code = code; NeedMb = needMb; }
    }

    /// <summary>
    /// DESKTOP PATCHES THE PC'S WOW FOREVER ITSELF (the user's ruling 2026-11-18; the judge's merged plan, Steps 1-4): Blizzard's CDN,
    /// no Battle.net, the Thor's own updater's method (WowClientDownloader.sync). It writes only four things: the configs in Data/config
    /// (content-named), any missing Data/indices/*.index (new files), the changed _forever_ client files (checked by MD5, Wow.exe last)
    /// and the wowf row of .build.info (last). Never the shared CASC store (Data/data), never .product.db.
    ///   Checking -> (Waiting while the Agent is at work) -> Downloading (staged in _forever_\.dsf-patch\&lt;build&gt;, each file
    ///   checked against its content key; identical files COPIED from another flavor folder) -> Installing (journal; each file swapped
    ///   in with its old one kept in _forever_\.dsf-backup\&lt;old build&gt;; any failure undoes the swaps) -> Done | Failed | Cancelled.
    /// One backup is kept until the first good login on the new build (<see cref="GoodLogin"/>), then deleted; a first start that
    /// fails brings the old client back (<see cref="RestoreOld"/>).
    /// </summary>
    public sealed class PcGamePatcher
    {
        readonly PatchEnv env;
        readonly object gate = new object();
        PatchStatus st = new PatchStatus();
        CancellationTokenSource cts;
        DateTime startedAt;
        long doneBefore;

        public static readonly string[] DefaultHosts = { "level3.blizzard.com", "us.cdn.blizzard.com" };

        public PcGamePatcher(PatchEnv env) { this.env = env; }

        public event Action Changed;

        public PatchStatus Status { get { lock (gate) return st.Copy(); } }
        public bool Busy { get { lock (gate) return st.Busy; } }

        string FlavorDir => Path.Combine(env.Root, env.Flavor);
        string BuildInfoPath => Path.Combine(env.Root, ".build.info");

        void Set(Action<PatchStatus> f, bool tell = true)
        {
            lock (gate) f(st);
            if (tell) try { Changed?.Invoke(); } catch (Exception) { }
        }

        /// <summary>one attempt in the background; false = one is running already (a press then is that one)</summary>
        public bool Start(string why)
        {
            lock (gate)
            {
                if (st.Busy) return false;
                cts = new CancellationTokenSource();
                st = new PatchStatus { State = PatchState.Checking };
            }
            Log.I("game patch: starting (" + why + ")");
            var token = cts.Token;
            Task.Run(() => Run(token));
            try { Changed?.Invoke(); } catch (Exception) { }
            return true;
        }

        /// <summary>the same attempt on the caller's thread (the tests)</summary>
        public PatchStatus RunNow(CancellationToken ct = default)
        {
            lock (gate) st = new PatchStatus { State = PatchState.Checking };
            Run(ct);
            return Status;
        }

        /// <summary>the player's Cancel (B): true = asked. Never once the install began (it is short, and a half install is worse)</summary>
        public bool Cancel()
        {
            lock (gate)
            {
                if (!st.Busy || st.State == PatchState.Installing || cts == null) return false;
                cts.Cancel();
                return true;
            }
        }

        /// <summary>back to Idle (the game is current again by another route); a running attempt is left alone</summary>
        public void Reset() { lock (gate) if (!st.Busy) st = new PatchStatus(); }

        void Run(CancellationToken ct)
        {
            startedAt = env.Now();
            depth = 0;
            try
            {
                var r = Patch(ct);
                Set(s => { s.State = PatchState.Done; s.Done = s.Total; s.EtaS = 0; s.Code = ""; s.File = ""; });
                Log.I("game patch: done (" + r + ")");
            }
            catch (OperationCanceledException)
            {
                Set(s => { s.State = PatchState.Cancelled; s.Code = "cancelled"; s.EtaS = -1; });
                Log.I("game patch: cancelled (the staged files stay for the next try)");
            }
            catch (PatchFailed e)
            {
                Set(s => { s.State = PatchState.Failed; s.Code = e.Code; s.Why = e.Message; s.NeedMb = e.NeedMb; s.EtaS = -1; });
                Log.I("game patch: failed, " + e.Code + ": " + e.Message);
            }
            catch (Exception e)
            {
                var code = e is CdnException ? "no_answer" : e is PatchFormatException ? "format" : e is UnauthorizedAccessException ? "no_write" : "error";
                Set(s => { s.State = PatchState.Failed; s.Code = code; s.Why = e.Message; s.EtaS = -1; });
                Log.I("game patch: failed, " + code + ": " + e.GetType().Name + ": " + e.Message);
            }
        }

        static string ReadShared(string path)
        {
            using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
            using (var r = new StreamReader(fs, new UTF8Encoding(false), true)) return r.ReadToEnd();
        }

        /// <summary>the row the new one is made from when there is none: Desktop's copy, else the retail row, else any row</summary>
        Tuple<string, string> Template(string text)
        {
            try
            {
                if (env.SavedRowPath != null && System.IO.File.Exists(env.SavedRowPath))
                {
                    var o = Json.ReadObject(System.IO.File.ReadAllText(env.SavedRowPath));
                    var h = o.Str("header"); var l = o.Str("row");
                    if (!string.IsNullOrEmpty(h) && !string.IsNullOrEmpty(l)) return Tuple.Create(h, l);
                }
            }
            catch (Exception) { }
            var lines = BuildInfoFile.Lines(text);
            if (lines.Count < 2) return null;
            var line = BuildInfoFile.RowLine(text, "wow") ?? lines[1];
            return Tuple.Create(lines[0], line);
        }

        int depth;

        string Patch(CancellationToken ct)
        {
            // ---- Checking: the install and Blizzard's current build for its region
            if (!System.IO.File.Exists(BuildInfoPath)) throw new PatchFailed("format", "no .build.info in the game folder");
            var text = ReadShared(BuildInfoPath);
            var lines = BuildInfoFile.Lines(text);
            if (lines.Count < 1) throw new PatchFailed("format", ".build.info is empty");
            var header = lines[0];
            var rowLine = BuildInfoFile.RowLine(text, env.Product);
            var row = rowLine != null ? BuildInfoFile.Values(header, rowLine) : null;
            var template = rowLine != null ? Tuple.Create(header, rowLine) : Template(text);
            var tv = template != null ? BuildInfoFile.Values(template.Item1, template.Item2) : new Dictionary<string, string>();
            string V(Dictionary<string, string> d, string k) => d != null && d.TryGetValue(k, out var v) ? v.Trim() : "";
            var exeVer = BuildInfoFile.ExeVersion(Path.Combine(FlavorDir, "Wow.exe"));
            var from = V(row, "Version").Length > 0 ? V(row, "Version") : (exeVer ?? "");
            var region = GameVersion.Region(V(row, "Branch").Length > 0 ? V(row, "Branch") : V(tv, "Branch"));
            Set(s => s.From = from);

            var pbase = env.PatchBase?.Invoke(region) ?? ("http://" + region + ".patch.battle.net:1119/" + env.Product);
            var versions = env.FetchText(pbase + "/versions");
            ct.ThrowIfCancellationRequested();
            var vrow = Psv.Row(Psv.Parse(versions), "Region", region);
            var buildKey = V(vrow, "BuildConfig"); var cdnKey = V(vrow, "CDNConfig");
            var target = V(vrow, "VersionsName").Length > 0 ? V(vrow, "VersionsName") : V(vrow, "VersionName");
            if (!Hex.IsKey(buildKey) || !Hex.IsKey(cdnKey) || GameVersion.Build(target) <= 0)
                throw new PatchFailed("no_answer", "Blizzard's patch server gave no " + region + " build");
            var crow = Psv.Row(Psv.Parse(env.FetchText(pbase + "/cdns")), "Name", region);
            var hosts = (V(crow, "Hosts").Length > 0 ? V(crow, "Hosts") : V(row, "CDN Hosts").Length > 0 ? V(row, "CDN Hosts") : V(tv, "CDN Hosts"))
                .Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries).ToList();
            if (hosts.Count == 0) hosts = DefaultHosts.ToList();
            var path = V(crow, "Path").Length > 0 ? V(crow, "Path") : V(row, "CDN Path").Length > 0 ? V(row, "CDN Path") : "tpr/wow";
            var servers = V(crow, "Servers");
            Set(s => s.To = target);
            Log.I("game patch: " + env.Product + " " + (from.Length > 0 ? from : "?") + " -> " + target + " (" + region + ", build config " + buildKey + ")");

            // ---- preflight: WoW closed (an outdated client at its login is closed: part of the attempt, never a step for the
            // player), the Agent quiet, the folder writable
            CloseWowFirst();
            WaitForAgent(ct, PatchState.Checking);
            // an install a crash or a failed put-back left half done is undone first (the old client whole), then the patch reads
            // the install again from the start
            var left = LoadJournal();
            if (left != null && left.State != "installed")
            {
                Log.I("game patch: an install of " + left.To + " left half done: undoing it first");
                if (!Undo(left)) throw new PatchFailed("busy", "the last install couldn't be put back yet");
                if (depth++ > 0) throw new PatchFailed("error", "the install changed again while it was read");
                return Patch(ct);
            }
            try
            {
                var probe = Path.Combine(FlavorDir, ".dsf-probe");
                System.IO.File.WriteAllText(probe, "probe");
                System.IO.File.Delete(probe);
            }
            catch (Exception e) { throw new PatchFailed("no_write", "can't write in " + env.Flavor + ": " + e.Message); }

            var cdn = env.Cdn(hosts, path);
            var buildCfg = Config(cdn, buildKey, ct);
            var cdnCfg = Config(cdn, cdnKey, ct);
            var bc = ConfigFile.Parse(Encoding.UTF8.GetString(buildCfg));
            var cc = ConfigFile.Parse(Encoding.UTF8.GetString(cdnCfg));

            // ---- the install manifest: this install's files by tag
            var instC = ConfigFile.Get(bc, "install", 0); var instE = ConfigFile.Get(bc, "install", 1);
            if (!Hex.IsKey(instC) || !Hex.IsKey(instE)) throw new PatchFailed("format", "the build config has no install manifest");
            var instEnc = cdn.Fetch("data", instE, ct: ct) ?? throw new PatchFailed("missing", "the install manifest is not on the CDN");
            var inst = Blte.Decode(instEnc);
            if (Hex.Md5(inst) != instC) throw new PatchFailed("checksum", "the install manifest is damaged");
            var manifest = InstallManifest.Parse(inst);
            var tagsCell = BuildInfoFile.RegionTags(V(row, "Tags").Length > 0 ? V(row, "Tags") : V(tv, "Tags"), region);
            if (tagsCell.Trim().Length == 0) tagsCell = "Windows x86_64 " + region.ToUpperInvariant() + "? enUS speech?:Windows x86_64 " + region.ToUpperInvariant() + "? enUS text?";
            var files = InstallManifest.Select(manifest, InstallManifest.TagsOf(tagsCell));
            if (files.Count == 0) throw new PatchFailed("format", "no Windows x86_64 client files in this build");
            var pending = files.Where(e => Hex.Md5File(Dest(e)) != e.CKey)
                .Select((e, i) => new { e, i }).OrderBy(x => InstallManifest.Rank(x.e.Name)).ThenBy(x => x.i).Select(x => x.e).ToList();

            // ---- the archive indexes the build needs (none for 70938 -> 70959: the same CDN config)
            var indexDir = Path.Combine(env.Root, "Data", "indices");
            var required = new[] { "archives", "patch-archives" }.SelectMany(k => cc.TryGetValue(k, out var l) ? l : new List<string>())
                .Concat(new[] { "file-index", "patch-file-index", "archive-group", "patch-archive-group" }.Select(k => ConfigFile.Get(cc, k)).Where(k => k != null))
                .Distinct().ToList();
            var missingIdx = required.Where(k => !ArchiveIndex.FileOk(Path.Combine(indexDir, k + ".index"), k)).ToList();

            bool rowCurrent = row != null && V(row, "Version") == target && V(row, "Build Key") == buildKey && V(row, "CDN Key") == cdnKey;
            if (pending.Count == 0 && missingIdx.Count == 0 && rowCurrent) return "already " + target;

            long total = pending.Sum(e => e.Size);
            int count = pending.Count;
            Set(s => { s.Total = total; s.Files = count; });
            Log.I("game patch: " + count + " client files to change (" + (total / 1000000) + " MB): " + string.Join(", ", pending.Select(e => e.Name))
                  + (missingIdx.Count > 0 ? "; " + missingIdx.Count + " archive indexes to fetch" : ""));
            long need = total + (pending.Count > 0 ? pending.Max(e => e.Size) : 0) + env.SpareBytes;
            long free = env.FreeBytes(env.Root);
            if (free < need) throw new PatchFailed("space", "needs " + need / 1000000 + " MB free, has " + free / 1000000 + " MB", (need - free + 999999) / 1000000);

            // ---- Downloading: staged beside the game, each checked against its content key
            int build = GameVersion.Build(target);
            var staging = Path.Combine(FlavorDir, ".dsf-patch", build.ToString());
            Set(s => { s.State = PatchState.Downloading; });
            Dictionary<string, string> ekeys = null;
            long done = 0;
            for (int n = 0; n < pending.Count; n++)
            {
                ct.ThrowIfCancellationRequested();
                var e = pending[n];
                var staged = Path.Combine(staging, Rel(e.Name));
                int no = n + 1;
                Set(s => { s.File = e.Name; s.FileNo = no; });
                if (Hex.Md5File(staged) == e.CKey) { done += e.Size; Progress(done); continue; }
                var copy = CopySource(e);
                if (copy != null)
                {
                    var tmp = staged + ".copy";
                    Directory.CreateDirectory(Path.GetDirectoryName(staged));
                    System.IO.File.Copy(copy, tmp, true);
                    if (Hex.Md5File(tmp) == e.CKey)
                    {
                        if (System.IO.File.Exists(staged)) System.IO.File.Delete(staged);
                        System.IO.File.Move(tmp, staged);
                        Log.I("game patch: " + e.Name + " copied from " + copy.Substring(env.Root.Length).TrimStart('\\') + " (the same file)");
                        done += e.Size; Progress(done);
                        continue;
                    }
                    System.IO.File.Delete(tmp);
                }
                if (ekeys == null)
                {
                    var encE = ConfigFile.Get(bc, "encoding", 1);
                    if (!Hex.IsKey(encE)) throw new PatchFailed("format", "the build config has no encoding table");
                    var need2 = pending.Select(x => x.CKey).ToList();
                    var enc = new RangedBlte((o, len) => cdn.Fetch("data", encE, o, o + len - 1, ct: ct) ?? throw new PatchFailed("missing", "the encoding table is not on the CDN"));
                    ekeys = EncodingTable.Find(enc.Read, need2);
                    locateWanted = new HashSet<string>(ekeys.Values, StringComparer.Ordinal);
                    located = null;
                }
                if (!ekeys.TryGetValue(e.CKey, out var ekey)) throw new PatchFailed("missing", e.Name + " is not in the encoding table");
                long baseDone = done; long size = e.Size;
                Fetch(cdn, cc, ekey, e, staged, enc2 => Progress(baseDone + size * enc2.Item1 / Math.Max(1, enc2.Item2)), ct);
                done += e.Size; Progress(done);
            }
            for (int n = 0; n < missingIdx.Count; n++)
            {
                ct.ThrowIfCancellationRequested();
                var k = missingIdx[n];
                var dest = Path.Combine(indexDir, k + ".index");
                if (!cdn.FetchTo("data", k + ".index", dest, verify: p => ArchiveIndex.FileOk(p, k), ct: ct))
                    throw new PatchFailed("missing", "archive index " + k + " is not on the CDN");
                AgentWatch.Mine(dest);
            }
            ct.ThrowIfCancellationRequested();

            // the Agent asked again right before the install (minutes later: Battle.net may have started meanwhile, and the Agent
            // works on the folder by itself; never patch beside it). Cancel still counts while it waits
            WaitForAgent(ct, PatchState.Downloading);
            ct.ThrowIfCancellationRequested();

            // ---- Installing: no Cancel from here
            Set(s => { s.State = PatchState.Installing; s.File = ""; });
            if (env.WowRunning()) throw new PatchFailed("busy", "WoW started meanwhile");
            // the files to replace AND the new ones (antivirus scanning a freshly written Wow.exe makes File.Replace fail half way)
            WaitForLocks(pending.Select(Dest).Where(System.IO.File.Exists).Concat(pending.Select(e => Path.Combine(staging, Rel(e.Name)))).ToList());
            // an install still waiting for its first good login (its backup is the ONE backup, the user's ruling) is folded in, never
            // replaced: its backup stays the backup of the client before it, files it changed already are not backed up again (their
            // backup holds the original), and a put-back still brings back the client from before both
            var prev = LoadJournal();
            if (prev != null && prev.State != "installed") prev = null;
            var fromBuild = GameVersion.Build(from) > 0 ? GameVersion.Build(from).ToString() : "old";
            var backupDir = prev?.BackupDir ?? Path.Combine(FlavorDir, ".dsf-backup", fromBuild);
            KeepOneBackup(backupDir);
            var j = new Journal
            {
                From = prev?.From ?? from, To = target, Root = env.Root, Flavor = env.Flavor, State = "installing", BackupDir = backupDir,
                BuildInfoBak = prev?.BuildInfoBak, BuildInfoWritten = prev?.BuildInfoWritten,
            };
            var before = new Dictionary<string, JFile>(StringComparer.OrdinalIgnoreCase);
            if (prev != null) foreach (var f in prev.Files) before[f.Dest] = f;
            var swaps = new List<JFile>();
            foreach (var e in pending)
            {
                var dest = Dest(e); var staged = Path.Combine(staging, Rel(e.Name));
                if (before.TryGetValue(dest, out var was))
                {
                    before.Remove(dest);
                    swaps.Add(new JFile { Dest = dest, Staged = staged, Backup = was.Backup, Existed = was.Existed, OldMd5 = was.OldMd5, Kept = true });
                }
                else swaps.Add(new JFile { Dest = dest, Staged = staged, Backup = Path.Combine(backupDir, Rel(e.Name)), Existed = System.IO.File.Exists(dest), OldMd5 = Hex.Md5File(dest) ?? "" });
            }
            j.Files.AddRange(before.Values);   // the earlier install's other files: swapped already, put back with the rest
            j.Files.AddRange(swaps);
            SaveJournal(j);
            try
            {
                foreach (var f in swaps)
                {
                    env.Swap(f.Staged, f.Dest, f.Existed && !f.Kept ? f.Backup : null);
                    f.Done = true;
                    SaveJournal(j);
                }
                SaveConfig(buildKey, buildCfg);
                SaveConfig(cdnKey, cdnCfg);
                // .build.info LAST, read again right before (the Agent may have written it since the check)
                var now = ReadShared(BuildInfoPath);
                var set = new Dictionary<string, string>
                {
                    ["Branch"] = region, ["Active"] = "1", ["Build Key"] = buildKey, ["CDN Key"] = cdnKey,
                    ["Install Key"] = instE, ["IM Size"] = ConfigFile.Get(bc, "install-size", 1) ?? "", ["CDN Path"] = path,
                    ["CDN Hosts"] = string.Join(" ", hosts), ["CDN Servers"] = servers, ["Version"] = target, ["KeyRing"] = V(vrow, "KeyRing"),
                };
                var nowRow = BuildInfoFile.RowLine(now, env.Product);
                var next = BuildInfoFile.Upsert(now, env.Product, set, template?.Item1, template?.Item2)
                    ?? throw new PatchFailed("format", "the new " + env.Product + " row doesn't fit .build.info");
                if (next != now)
                {
                    // the .build.info from before an earlier install still awaiting its login stays the one put back
                    bool keepBak = !string.IsNullOrEmpty(j.BuildInfoBak) && System.IO.File.Exists(j.BuildInfoBak);
                    if (!keepBak) j.BuildInfoBak = BuildInfoPath + ".dsf-bak";
                    j.BuildInfoWritten = Hex.Md5(Encoding.UTF8.GetBytes(next));
                    SaveJournal(j);
                    WriteBuildInfo(now, next, keepBak ? BuildInfoPath + ".dsf-bak2" : j.BuildInfoBak);
                    if (keepBak) TryDelete(BuildInfoPath + ".dsf-bak2");
                }
                Log.I("game patch: .build.info's " + env.Product + " row " + (nowRow == null ? "added" : "set") + " for " + target);
            }
            catch (Exception e)
            {
                Log.I("game patch: the install failed (" + e.Message + "): undoing it");
                if (!Undo(j)) throw new PatchFailed("busy", "the install failed (" + e.Message + ") and a file couldn't be put back yet: the next try does it first");
                if (e is PatchFailed) throw;
                if (e is IOException || e is UnauthorizedAccessException) throw new PatchFailed("busy", "a game file couldn't be replaced: " + e.Message);
                throw;
            }
            j.State = "installed";
            SaveJournal(j);
            try { Directory.Delete(Path.Combine(FlavorDir, ".dsf-patch"), true); } catch (Exception) { }
            return count + " files, " + (total / 1000000) + " MB; the old files kept in " + backupDir.Substring(env.Root.Length).TrimStart('\\') + " until the first good login";
        }

        int toldAt = Environment.TickCount;

        /// <summary>the bar's bytes; the Thor is told at most every 500 ms (a read is 64 KiB: 2,000 of them for 127 MB)</summary>
        void Progress(long done)
        {
            var el = (env.Now() - startedAt).TotalSeconds;
            bool tell;
            lock (gate)
            {
                st.Done = Math.Min(done, st.Total);
                double rate = el > 1 ? st.Done / el : 0;
                st.EtaS = rate > 0 ? (st.Total - st.Done) / rate : -1;
                int now = Environment.TickCount;
                tell = st.Done >= st.Total || unchecked(now - toldAt) >= 500;
                if (tell) toldAt = now;
            }
            if (tell) try { Changed?.Invoke(); } catch (Exception) { }
        }

        static string Rel(string name) => name.Replace('/', '\\');
        string Dest(InstallEntry e) => Path.Combine(FlavorDir, Rel(e.Name));

        /// <summary>the same file (its content key) in another flavor folder of this game (_retail_, _classic_, ...), null = none</summary>
        string CopySource(InstallEntry e)
        {
            try
            {
                foreach (var d in Directory.GetDirectories(env.Root))
                {
                    var n = Path.GetFileName(d);
                    if (!(n.Length > 2 && n.StartsWith("_") && n.EndsWith("_")) || n.Equals(env.Flavor, StringComparison.OrdinalIgnoreCase)) continue;
                    var p = Path.Combine(d, Rel(e.Name));
                    if (System.IO.File.Exists(p) && new FileInfo(p).Length == e.Size && Hex.Md5File(p) == e.CKey) return p;
                }
            }
            catch (Exception) { }
            return null;
        }

        byte[] Config(CdnClient cdn, string key, CancellationToken ct)
        {
            var local = ConfigPath(key);
            try { if (System.IO.File.Exists(local)) { var b = System.IO.File.ReadAllBytes(local); if (ConfigFile.Ok(b, key)) return b; } } catch (Exception) { }
            return cdn.Fetch("config", key, verify: b => ConfigFile.Ok(b, key), ct: ct) ?? throw new PatchFailed("missing", "config " + key + " is not on the CDN");
        }

        string ConfigPath(string key) => Path.Combine(env.Root, "Data", "config", key.Substring(0, 2), key.Substring(2, 2), key);

        /// <summary>a config written only when it is missing or not its name's (content-named: a new file beside the old ones)</summary>
        void SaveConfig(string key, byte[] bytes)
        {
            var p = ConfigPath(key);
            try { if (System.IO.File.Exists(p) && ConfigFile.Ok(System.IO.File.ReadAllBytes(p), key)) return; } catch (Exception) { }
            Directory.CreateDirectory(Path.GetDirectoryName(p));
            var tmp = p + ".dsf-tmp";
            System.IO.File.WriteAllBytes(tmp, bytes);
            if (System.IO.File.Exists(p)) System.IO.File.Delete(p);
            System.IO.File.Move(tmp, p);
            AgentWatch.Mine(p);
            AgentWatch.Mine(Path.Combine(env.Root, "Data", "config"));
        }

        /// <summary>one client file: the loose file, else its range of the archive that holds it; decoded and MD5-checked into [staged]</summary>
        void Fetch(CdnClient cdn, Dictionary<string, List<string>> cc, string ekey, InstallEntry e, string staged, Action<Tuple<long, long>> progress, CancellationToken ct)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(staged));
            var blte = staged + ".blte";
            Exception last = null;
            for (int attempt = 0; attempt < 3; attempt++)
            {
                ct.ThrowIfCancellationRequested();
                try
                {
                    bool got = cdn.FetchTo("data", ekey, blte, progress: (n, whole) => progress(Tuple.Create(n, whole > 0 ? whole : Math.Max(n, e.Size))), ct: ct);
                    if (!got)
                    {
                        var at = Locate(cdn, cc, ekey, ct) ?? throw new PatchFailed("missing", e.Name + " is not on the CDN");
                        long size = at.Item2;
                        got = cdn.FetchTo("data", at.Item1, blte, at.Item3, at.Item3 + size - 1, progress: (n, whole) => progress(Tuple.Create(n, size)), ct: ct);
                        if (!got) throw new PatchFailed("missing", e.Name + "'s archive is not on the CDN");
                    }
                    Decode(blte, staged, e);
                    return;
                }
                catch (CdnException x) { last = x; Log.I("game patch: " + e.Name + ": " + x.Message + (attempt < 2 ? ": again" : "")); env.Sleep(1000 * (attempt + 1)); }
            }
            throw new PatchFailed("no_answer", e.Name + ": " + last?.Message);
        }

        /// <summary>[blte] decoded into [staged] (through a .tmp) with its MD5 counted on the way; a mismatch is a checksum failure</summary>
        static void Decode(string blte, string staged, InstallEntry e)
        {
            var tmp = staged + ".tmp";
            string md5;
            try
            {
                using (var i = new FileStream(blte, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 20))
                using (var o = new FileStream(tmp, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 20))
                using (var m = new Md5Sink(o))
                {
                    Blte.Decode(i, m);
                    md5 = m.Finish();
                }
            }
            catch (PatchFormatException x)
            {
                TryDelete(tmp); TryDelete(blte);
                if (x.Message.Contains("mode")) throw new PatchFailed("format", e.Name + ": " + x.Message);
                throw new PatchFailed("checksum", e.Name + ": " + x.Message);
            }
            TryDelete(blte);
            if (md5 != e.CKey) { TryDelete(tmp); throw new PatchFailed("checksum", e.Name + " didn't match its checksum"); }
            if (System.IO.File.Exists(staged)) System.IO.File.Delete(staged);
            System.IO.File.Move(tmp, staged);
        }

        static void TryDelete(string p) { try { if (System.IO.File.Exists(p)) System.IO.File.Delete(p); } catch (Exception) { } }

        /// <summary>the files' places in the archives, found in ONE pass over the archive indexes (the PC's 1,405 of them take
        /// ~10 s to read: measured 2026-11-18, once per file was 7-16 s each)</summary>
        Dictionary<string, Tuple<string, long, long>> located;
        ICollection<string> locateWanted;

        /// <summary>the archive holding [ekey] by the install's own archive indexes (fetched when one is missing); (archive, size, offset)</summary>
        Tuple<string, long, long> Locate(CdnClient cdn, Dictionary<string, List<string>> cc, string ekey, CancellationToken ct)
        {
            if (located == null || !located.ContainsKey(ekey) && !(locateWanted?.Contains(ekey) ?? false))
            {
                var want = new HashSet<string>(locateWanted ?? new string[0]) { ekey };
                located = LocateAll(cdn, cc, want, ct);
                locateWanted = want;
            }
            return located.TryGetValue(ekey, out var at) ? at : null;
        }

        Dictionary<string, Tuple<string, long, long>> LocateAll(CdnClient cdn, Dictionary<string, List<string>> cc, ICollection<string> wanted, CancellationToken ct)
        {
            var found = new Dictionary<string, Tuple<string, long, long>>(StringComparer.Ordinal);
            var left = new HashSet<string>(wanted, StringComparer.Ordinal);
            var dir = Path.Combine(env.Root, "Data", "indices");
            foreach (var a in cc.TryGetValue("archives", out var l) ? l : new List<string>())
            {
                if (left.Count == 0) break;
                ct.ThrowIfCancellationRequested();
                var p = Path.Combine(dir, a + ".index");
                byte[] idx = null;
                if (ArchiveIndex.FileOk(p, a)) idx = ReadAll(p);
                else
                {
                    var b = cdn.Fetch("data", a + ".index", verify: x => ArchiveIndex.FooterOk(x, a), ct: ct);
                    if (b == null) continue;
                    idx = b;
                }
                foreach (var kv in ArchiveIndex.Find(idx, left.ToList())) { found[kv.Key] = Tuple.Create(a, kv.Value.Item1, kv.Value.Item2); left.Remove(kv.Key); }
            }
            return found;
        }

        static byte[] ReadAll(string p)
        {
            using (var fs = new FileStream(p, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
            using (var ms = new MemoryStream((int)fs.Length)) { fs.CopyTo(ms); return ms.ToArray(); }
        }

        /// <summary>WoW of the folder closed for the patch: one NOT signed in is closed (env.CloseWow: the outdated client at its
        /// login, which can't reach the servers anyway); a signed-in one, or one that won't close, = "busy"</summary>
        void CloseWowFirst()
        {
            if (!env.WowRunning()) return;
            var why = env.CloseWow != null ? env.CloseWow() : "WoW is running from " + env.Flavor;
            if (why == null && !env.WowRunning()) { Log.I("game patch: the old client at its login closed for the update"); return; }
            throw new PatchFailed("busy", why ?? "WoW is still running from " + env.Flavor);
        }

        /// <summary>while Blizzard's updater (the Agent) is at work on the folder: Waiting, [AgentWaitS] at most, then "agent_busy";
        /// [after] = the state again once it is quiet</summary>
        void WaitForAgent(CancellationToken ct, PatchState after)
        {
            double waited = 0;
            bool waitedAny = false;
            for (string why; (why = env.AgentBusy()) != null;)
            {
                if (!waitedAny) Log.I("game patch: Blizzard's updater is at work (" + why + "): waiting up to " + (int)env.AgentWaitS + " s");
                waitedAny = true;
                if (waited >= env.AgentWaitS) throw new PatchFailed("agent_busy", "Blizzard's updater stayed busy " + (int)waited + " s: " + why);
                Set(s => { s.State = PatchState.Waiting; s.Code = "agent"; });
                for (int i = 0; i < 10; i++) { ct.ThrowIfCancellationRequested(); env.Sleep((int)(env.AgentPollS * 100)); }
                waited += env.AgentPollS;
            }
            if (waitedAny || after == PatchState.Checking) Set(s => { s.State = after; s.Code = ""; });
        }

        /// <summary>every file to replace opened alone (antivirus scanning a new exe, a closing WoW), [LockWaitS] at most</summary>
        void WaitForLocks(List<string> paths)
        {
            var until = env.Now().AddSeconds(env.LockWaitS);
            foreach (var p in paths)
                while (true)
                {
                    try { using (new FileStream(p, FileMode.Open, FileAccess.ReadWrite, FileShare.None)) break; }
                    catch (IOException e)
                    {
                        if (env.Now() >= until) throw new PatchFailed("busy", Path.GetFileName(p) + " stayed in use: " + e.Message);
                        env.Sleep(1000);
                    }
                }
        }

        /// <summary>one backup only (the user's ruling): any other backup folder goes before this one is made</summary>
        void KeepOneBackup(string keep)
        {
            var root = Path.GetDirectoryName(keep);
            if (!Directory.Exists(root)) return;
            foreach (var d in Directory.GetDirectories(root))
                if (!d.Equals(keep, StringComparison.OrdinalIgnoreCase)) try { Directory.Delete(d, true); Log.I("game patch: an older backup removed (" + Path.GetFileName(d) + ")"); } catch (Exception) { }
        }

        void WriteBuildInfo(string readText, string next, string backup)
        {
            bool bom;
            using (var fs = new FileStream(BuildInfoPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                bom = fs.Length >= 3 && fs.ReadByte() == 0xEF && fs.ReadByte() == 0xBB && fs.ReadByte() == 0xBF;
            var tmp = BuildInfoPath + ".dsf-tmp";
            System.IO.File.WriteAllBytes(tmp, new UTF8Encoding(bom).GetPreamble().Concat(new UTF8Encoding(false).GetBytes(next)).ToArray());
            if (ReadShared(BuildInfoPath) != readText) { TryDelete(tmp); throw new PatchFailed("agent_busy", ".build.info changed while it was being written"); }
            System.IO.File.Replace(tmp, BuildInfoPath, backup, true);
            AgentWatch.Mine(BuildInfoPath);
        }

        // ---- the journal

        /// <summary>one file of an install: [OldMd5] = the file it replaced ("" = none, or an older journal); [Kept] = its backup
        /// holds the client from before an earlier install still awaiting its login (not backed up again)</summary>
        sealed class JFile { public string Dest, Staged, Backup, OldMd5 = ""; public bool Existed, Done, Kept; }
        sealed class Journal
        {
            public string From, To, Root, Flavor, State, BackupDir, BuildInfoBak, BuildInfoWritten;
            public List<JFile> Files = new List<JFile>();
        }

        void SaveJournal(Journal j)
        {
            if (env.JournalPath == null) return;
            var d = new Dictionary<string, object>
            {
                ["from"] = j.From, ["to"] = j.To, ["root"] = j.Root, ["flavor"] = j.Flavor, ["state"] = j.State, ["backup_dir"] = j.BackupDir,
                ["build_info_bak"] = j.BuildInfoBak ?? "", ["build_info_written"] = j.BuildInfoWritten ?? "",
                ["files"] = j.Files.Select(f => new Dictionary<string, object> { ["dest"] = f.Dest, ["staged"] = f.Staged, ["backup"] = f.Backup, ["existed"] = f.Existed, ["done"] = f.Done, ["old_md5"] = f.OldMd5 ?? "", ["kept"] = f.Kept }).ToList(),
            };
            Directory.CreateDirectory(Path.GetDirectoryName(env.JournalPath));
            var tmp = env.JournalPath + ".tmp";
            System.IO.File.WriteAllText(tmp, Json.Write(d));
            if (System.IO.File.Exists(env.JournalPath)) System.IO.File.Replace(tmp, env.JournalPath, null); else System.IO.File.Move(tmp, env.JournalPath);
        }

        Journal LoadJournal()
        {
            try
            {
                if (env.JournalPath == null || !System.IO.File.Exists(env.JournalPath)) return null;
                var o = Json.ReadObject(System.IO.File.ReadAllText(env.JournalPath));
                var j = new Journal
                {
                    From = o.Str("from"), To = o.Str("to"), Root = o.Str("root"), Flavor = o.Str("flavor"), State = o.Str("state"),
                    BackupDir = o.Str("backup_dir"), BuildInfoBak = o.Str("build_info_bak"), BuildInfoWritten = o.Str("build_info_written"),
                };
                if (o.TryGetValue("files", out var fs) && fs is System.Collections.IEnumerable list)
                    foreach (var x in list)
                        if (x is Dictionary<string, object> f)
                            j.Files.Add(new JFile { Dest = f.Str("dest"), Staged = f.Str("staged"), Backup = f.Str("backup"), Existed = f.Bool("existed"), Done = f.Bool("done"), OldMd5 = f.Str("old_md5") ?? "", Kept = f.Bool("kept") });
                return j;
            }
            catch (Exception) { return null; }
        }

        void DropJournal() { try { if (env.JournalPath != null && System.IO.File.Exists(env.JournalPath)) System.IO.File.Delete(env.JournalPath); } catch (Exception) { } }

        /// <summary>
        /// The swaps undone in reverse, by what is ON DISK, not only by Done (the 0.2.36 review: File.Replace can fail half way with
        /// ERROR_UNABLE_TO_MOVE_REPLACEMENT_2, the old file already the backup and the destination gone; a crash or a hard reset can
        /// land between a swap and the journal's write): a replaced file that is not its old self comes back from its backup whatever
        /// Done says; a new file goes back to staging. .build.info back when it is still what was written. true = all put back and the
        /// journal gone; false = something couldn't be (a lock): the journal STAYS (state "undoing", per-file Done saved) and the next
        /// try or Desktop's next start does it again. A file whose old copy is nowhere is logged and left to the next patch (it writes
        /// it new), never a dead end.
        /// </summary>
        bool Undo(Journal j)
        {
            bool ok = true;
            foreach (var f in Enumerable.Reverse(j.Files))
            {
                try
                {
                    if (f.Existed)
                    {
                        var now = Hex.Md5File(f.Dest);
                        bool old = !string.IsNullOrEmpty(f.OldMd5) ? now == f.OldMd5 : !f.Done && now != null && !System.IO.File.Exists(f.Backup);
                        if (old) { f.Done = false; continue; }
                        if (System.IO.File.Exists(f.Backup))
                        {
                            // the new file goes back to staging (a Retry uses it again)
                            if (now != null)
                            {
                                Directory.CreateDirectory(Path.GetDirectoryName(f.Staged));
                                if (System.IO.File.Exists(f.Staged)) System.IO.File.Delete(f.Staged);
                                System.IO.File.Move(f.Dest, f.Staged);
                            }
                            System.IO.File.Move(f.Backup, f.Dest);
                            f.Done = false;
                            continue;
                        }
                        Log.I("game patch: " + f.Dest + " has no old copy left (" + (now == null ? "missing" : "not the old file") + "): the next patch writes it new");
                        f.Done = false;
                    }
                    else
                    {
                        if (System.IO.File.Exists(f.Dest) && (f.Done || !System.IO.File.Exists(f.Staged)))
                        {
                            Directory.CreateDirectory(Path.GetDirectoryName(f.Staged));
                            if (System.IO.File.Exists(f.Staged)) System.IO.File.Delete(f.Dest); else System.IO.File.Move(f.Dest, f.Staged);
                        }
                        f.Done = false;
                    }
                }
                catch (Exception e) { ok = false; Log.I("game patch: couldn't put back " + f.Dest + ": " + e.Message); }
            }
            try
            {
                if (!string.IsNullOrEmpty(j.BuildInfoWritten) && System.IO.File.Exists(BuildInfoPath) && System.IO.File.Exists(j.BuildInfoBak)
                    && Hex.Md5(Encoding.UTF8.GetBytes(ReadShared(BuildInfoPath))) == j.BuildInfoWritten)
                {
                    System.IO.File.Copy(j.BuildInfoBak, BuildInfoPath, true);
                    AgentWatch.Mine(BuildInfoPath);
                }
            }
            catch (Exception e) { ok = false; Log.I("game patch: couldn't put .build.info back: " + e.Message); }
            if (ok) { DropJournal(); return true; }
            j.State = "undoing";
            try { SaveJournal(j); } catch (Exception e) { Log.I("game patch: couldn't save the journal: " + e.Message); }
            Log.I("game patch: the put-back is not finished: the journal stays for the next try");
            return false;
        }

        /// <summary>WoW of the folder gone (a crashed client's Fatal Error window, then its kill), [WowExitWaitS] at most</summary>
        bool WaitWowGone()
        {
            var until = env.Now().AddSeconds(env.WowExitWaitS);
            while (env.WowRunning())
            {
                if (env.Now() >= until) return false;
                env.Sleep(1000);
            }
            return true;
        }

        /// <summary>at Desktop's start: an install a crash cut short is undone (the old client whole again); one awaiting its first
        /// login stays. The reason for the log, null = nothing to do</summary>
        public string Recover()
        {
            var j = LoadJournal();
            if (j == null) return null;
            if (j.State == "installed") return "the " + j.To + " install waits for its first good login (backup kept)";
            if (env.WowRunning()) return "an install of " + j.To + " cut short waits for WoW to close (the next patch try undoes it)";
            return Undo(j) ? "an install of " + j.To + " cut short was undone" : "an install of " + j.To + " cut short: not all put back yet (tried again next time)";
        }

        /// <summary>an install of a new build waits for its first good login (its backup is kept until then)</summary>
        public string AwaitingLogin { get { var j = LoadJournal(); return j != null && j.State == "installed" ? j.To : null; } }

        /// <summary>WoW signed in on [exeVersion]: when it is the patched build, the backup and the journal go (one backup until the first
        /// good login, the user's ruling)</summary>
        public bool GoodLogin(string exeVersion)
        {
            var j = LoadJournal();
            if (j == null || j.State != "installed" || GameVersion.Build(exeVersion) != GameVersion.Build(j.To)) return false;
            try { if (Directory.Exists(j.BackupDir)) Directory.Delete(j.BackupDir, true); } catch (Exception e) { Log.I("game patch: couldn't remove the backup: " + e.Message); }
            try { var root = Path.GetDirectoryName(j.BackupDir); if (Directory.Exists(root) && !Directory.EnumerateFileSystemEntries(root).Any()) Directory.Delete(root); } catch (Exception) { }
            DropJournal();
            Log.I("game patch: the first login on " + j.To + " was good: the backup of " + j.From + " is removed");
            return true;
        }

        /// <summary>the new build's first start failed: the old client back from the backup; the Thor shows it with a Retry</summary>
        public bool RestoreOld(string why)
        {
            var j = LoadJournal();
            if (j == null || j.State != "installed") return false;
            // the crashed client goes first (its Fatal Error window is still up when the crash is seen; Desktop kills it beside this):
            // a move under a running WoW fails, and a put-back half done must never lose the old file
            if (!WaitWowGone()) { Log.I("game patch: the first start of " + j.To + " failed, but WoW is still open: the old client goes back once it closed"); return false; }
            Log.I("game patch: the first start of " + j.To + " failed (" + why + "): the old client goes back");
            if (Undo(j)) try { Directory.Delete(Path.Combine(FlavorDir, ".dsf-patch"), true); } catch (Exception) { }
            Set(s => { s.State = PatchState.Failed; s.Code = "new_failed"; s.Why = why; s.From = j.From; s.To = j.To; });
            return true;
        }
    }

    /// <summary>a write-through stream that counts the MD5 of what passes</summary>
    sealed class Md5Sink : Stream
    {
        readonly Stream inner; readonly MD5 md5 = MD5.Create();
        public Md5Sink(Stream inner) { this.inner = inner; }
        public override void Write(byte[] b, int o, int n) { md5.TransformBlock(b, o, n, null, 0); inner.Write(b, o, n); }
        public string Finish() { md5.TransformFinalBlock(new byte[0], 0, 0); return Hex.Of(md5.Hash); }
        public override bool CanRead => false; public override bool CanSeek => false; public override bool CanWrite => true;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() => inner.Flush();
        public override int Read(byte[] b, int o, int n) => throw new NotSupportedException();
        public override long Seek(long o, SeekOrigin s) => throw new NotSupportedException();
        public override void SetLength(long v) => throw new NotSupportedException();
        protected override void Dispose(bool d) { if (d) md5.Dispose(); base.Dispose(d); }
    }
}
