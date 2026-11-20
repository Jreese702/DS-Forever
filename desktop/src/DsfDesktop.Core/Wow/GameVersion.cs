using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;

namespace DsfDesktop.Wow
{
    /// <summary>the PC game against Blizzard's current version: Unknown = not known (no WoW Forever here, offline, no answer: never
    /// blocks a start), Checking = Blizzard's answer is on its way (the first check of a run, or the Thor's Retry). NeedsRepair (0.2.35,
    /// the judge's Step 0) = _forever_\Wow.exe is there but .build.info has no wowf row (the Agent dropped it, 2026-11-18 06:32) or
    /// a row of another build than Wow.exe's own: never started</summary>
    public enum GameCheck { Unknown, Checking, Current, Outdated, NeedsRepair }

    /// <summary>
    /// Is the PC's WoW Forever older than Blizzard's? (the user's PC 2026-11-18 06:20: .build.info said "us ... wowf 1.60.1.70938"
    /// while us.patch.battle.net said 1.60.1.70959; Battle.net was signed out, so nothing patched it, and WoW found "no servers
    /// available".) The pure rules: the install's row of .build.info (its Branch = the region, its Version) and the patch server's
    /// versions table ("Region!STRING:0|BuildConfig!HEX:16|...|BuildId!DEC:4|VersionsName!String:0|...", one row per region).
    /// </summary>
    public static class GameVersion
    {
        /// <summary>Blizzard's HTTPS version service, in the order asked (us, then eu when us does not answer). Each lists every
        /// region's build, like the plain-HTTP [region].patch.battle.net:1119 it replaces (2026-11-20, after WoW Forever for Android
        /// v2.3.7 7d0b00d0, as the Thor's own updater now reads it). Never plain HTTP: its cdns table names the CDN servers</summary>
        public static readonly string[] PatchServers = { "https://us.version.battle.net/v2/products", "https://eu.version.battle.net/v2/products" };

        /// <summary>a product's patch-server bases ("https://us.version.battle.net/v2/products/wowf"), in the order asked</summary>
        public static List<string> PatchBases(string product) => PatchServers.Select(s => s + "/" + product).ToList();

        /// <summary>the patch server's versions table of a product (the region's row is read out of it: <see cref="Current"/>)</summary>
        public static string VersionsUrl(string region, string product) => PatchBases(product)[0] + "/versions";

        /// <summary>every versions URL in the order asked</summary>
        public static List<string> VersionsUrls(string product) => PatchBases(product).Select(b => b + "/versions").ToList();

        /// <summary>a region the patch server has (us, eu, kr, tw, cn); anything else = us</summary>
        public static string Region(string branch)
        {
            var b = (branch ?? "").Trim().ToLowerInvariant();
            return b == "us" || b == "eu" || b == "kr" || b == "tw" || b == "cn" ? b : "us";
        }

        /// <summary>the active .build.info row of [product] (its first row when none is marked active), null = none</summary>
        public static BuildInfoRow Installed(List<BuildInfoRow> rows, string product)
        {
            var mine = (rows ?? new List<BuildInfoRow>()).Where(r => string.Equals((r.Product ?? "").Trim(), product, StringComparison.Ordinal)).ToList();
            return mine.FirstOrDefault(r => r.Active) ?? mine.FirstOrDefault();
        }

        /// <summary>the versions table's VersionsName for [region] ("1.60.1.70959"), null = not in it</summary>
        public static string Current(string versionsText, string region)
        {
            if (string.IsNullOrEmpty(versionsText)) return null;
            var lines = versionsText.Replace("\r", "").Split('\n').Where(l => l.Trim().Length > 0 && !l.StartsWith("##")).ToList();
            if (lines.Count < 2) return null;
            var cols = lines[0].Split('|').Select(c => c.Split('!')[0].Trim()).ToList();
            int iRegion = cols.FindIndex(c => c.Equals("Region", StringComparison.OrdinalIgnoreCase));
            int iName = cols.FindIndex(c => c.Equals("VersionsName", StringComparison.OrdinalIgnoreCase));
            if (iRegion < 0 || iName < 0) return null;
            var want = Region(region);
            foreach (var l in lines.Skip(1))
            {
                var f = l.Split('|');
                if (f.Length <= Math.Max(iRegion, iName)) continue;
                if (f[iRegion].Trim().Equals(want, StringComparison.OrdinalIgnoreCase)) return f[iName].Trim().Length > 0 ? f[iName].Trim() : null;
            }
            return null;
        }

        /// <summary>a version's build number, its last part ("1.60.1.70938" -> 70938); -1 = none</summary>
        public static int Build(string version)
        {
            var v = (version ?? "").Trim();
            int dot = v.LastIndexOf('.');
            return int.TryParse(dot >= 0 ? v.Substring(dot + 1) : v, out int n) && n > 0 ? n : -1;
        }

        /// <summary>the installed build is older than Blizzard's (both known); a newer or equal one is current</summary>
        public static bool Outdated(string installed, string current)
        {
            int a = Build(installed), b = Build(current);
            return a > 0 && b > 0 && a < b;
        }

        /// <summary>the Thor's short form of a version: its build number ("70938"), else the whole of it</summary>
        public static string Short(string version) { int b = Build(version); return b > 0 ? b.ToString() : (version ?? ""); }
    }

    /// <summary>
    /// The live check: .build.info read again every <see cref="LocalS"/> (a patch by any route shows at once), Blizzard's versions
    /// asked at most every <see cref="CacheS"/> in the background (never on the caller's thread). No answer = <see cref="GameCheck.Unknown"/>
    /// (offline never blocks a start) and asked again after <see cref="OfflineS"/>. The pieces are injected so tests need no game or
    /// network.
    /// </summary>
    public sealed class GameVersionCheck
    {
        public const double CacheS = 300, OfflineS = 60, LocalS = 10;

        readonly Func<List<BuildInfoRow>> rows;
        readonly Func<string, string> fetch;
        readonly Func<DateTime> now;
        readonly Action<Action> run;
        readonly string product;
        readonly object gate = new object();

        readonly Func<string> exeVersion;
        readonly Func<string, bool> repair;
        readonly Action<string> remember;

        DateTime localAt = DateTime.MinValue, remoteAt = DateTime.MinValue;
        BuildInfoRow local;
        string exe;
        bool needsRepair;
        string remote;
        bool remoteOk, fetching;
        string remoteRegion;
        GameCheck last = GameCheck.Unknown;

        /// <param name="rows">the install's .build.info rows (empty = no install found)</param>
        /// <param name="fetch">url -> the body, null = no answer (offline, a timeout, an error); may block (it runs in [run])</param>
        /// <param name="exeVersion">the game exe's own version (Wow.exe's FileVersionInfo, read only): null = no exe, "" = unreadable</param>
        /// <param name="repair">the row is missing: add Desktop's copy back for this exe version (BuildInfoKeeper.Repair); true = written</param>
        /// <param name="remember">the row is there and is the exe's build: Desktop's copy is refreshed (BuildInfoKeeper.Remember)</param>
        public GameVersionCheck(Func<List<BuildInfoRow>> rows, Func<string, string> fetch, string product = "wowf",
                                Func<DateTime> now = null, Action<Action> run = null, Func<string> exeVersion = null,
                                Func<string, bool> repair = null, Action<string> remember = null)
        {
            this.rows = rows; this.fetch = fetch; this.product = product;
            this.now = now ?? (() => DateTime.UtcNow);
            this.run = run ?? (a => Task.Run(a));
            this.exeVersion = exeVersion; this.repair = repair; this.remember = remember;
        }

        /// <summary>the installed version ("1.60.1.70938"): the row's, else Wow.exe's own; null = not known</summary>
        public string Installed { get { lock (gate) return local?.Version ?? (string.IsNullOrEmpty(exe) ? null : exe); } }

        /// <summary>the last state <see cref="Poll"/> gave, without reading or asking anything (no Thor here: nothing is checked)</summary>
        public GameCheck Peek() { lock (gate) return last; }

        /// <summary>.build.info and Wow.exe read again now (before every start: the Agent may have changed the row since)</summary>
        public GameCheck PollNow()
        {
            lock (gate) localAt = DateTime.MinValue;
            return Poll();
        }

        /// <summary>the install's row and exe read (the row added back when it is missing and Desktop's copy fits); under [gate]</summary>
        void ReadLocal()
        {
            try { local = GameVersion.Installed(rows(), product); } catch (Exception) { local = null; }
            try { exe = exeVersion?.Invoke(); } catch (Exception) { exe = ""; }
            if (GameVersion.Build(exe) > 0 && local == null && repair != null)
            {
                bool wrote = false;
                try { wrote = repair(exe); } catch (Exception) { wrote = false; }
                if (wrote) try { local = GameVersion.Installed(rows(), product); } catch (Exception) { local = null; }
            }
            // Wow.exe there and no row (or a row of another build: never overwritten, the Agent may be mid-update) = needs repair
            int eb = GameVersion.Build(exe);
            needsRepair = exe != null && (local == null || (eb > 0 && GameVersion.Build(local.Version) != eb));
            if (exe != null && local != null && !needsRepair && eb > 0)
                try { remember?.Invoke(exe); } catch (Exception) { }
        }
        /// <summary>Blizzard's version for the install's region, null = not known</summary>
        public string Current { get { lock (gate) return remoteOk ? remote : null; } }

        /// <summary>the Thor's Retry: Blizzard is asked again now (and the install read again)</summary>
        public void Refresh()
        {
            lock (gate) { localAt = DateTime.MinValue; remoteAt = DateTime.MinValue; remoteOk = false; remote = null; }
            Poll();
        }

        /// <summary>the state now; asks Blizzard in the background when its answer is old</summary>
        public GameCheck Poll()
        {
            bool go = false;
            string url = null, region;
            GameCheck state;
            lock (gate)
            {
                var t = now();
                if ((t - localAt).TotalSeconds >= LocalS)
                {
                    localAt = t;
                    ReadLocal();
                }
                if (needsRepair) return last = GameCheck.NeedsRepair;
                if (local == null || GameVersion.Build(local.Version) <= 0) return last = GameCheck.Unknown;
                region = GameVersion.Region(local.Branch);
                if (region != remoteRegion) { remoteRegion = region; remoteAt = DateTime.MinValue; remoteOk = false; remote = null; }
                double age = (t - remoteAt).TotalSeconds;
                if (!fetching && age >= (remoteOk ? CacheS : OfflineS))
                {
                    fetching = true; go = true; remoteAt = t;
                    url = string.Join("\n", GameVersion.VersionsUrls(product));
                }
                state = fetching && !remoteOk ? GameCheck.Checking
                      : !remoteOk ? GameCheck.Unknown
                      : GameVersion.Outdated(local.Version, remote) ? GameCheck.Outdated : GameCheck.Current;
            }
            if (go)
            {
                var u = url; var reg = region;
                run(() =>
                {
                    // us first, then eu: the first one with the region's row
                    string cur = null;
                    foreach (var one in u.Split('\n'))
                    {
                        string body = null;
                        try { body = fetch(one); } catch (Exception) { body = null; }
                        cur = GameVersion.Current(body, reg);
                        if (cur != null) break;
                    }
                    lock (gate)
                    {
                        fetching = false;
                        if (reg != remoteRegion) return;
                        remoteOk = cur != null; remote = cur;
                        remoteAt = now();
                    }
                    Log.I("game version: " + (cur == null ? "Blizzard's " + reg + " version not known (no answer)" : "installed " + Installed + ", Blizzard's " + reg + " " + cur));
                });
                // a check run inline (the tests) has its answer already
                lock (gate)
                    if (!fetching)
                        state = !remoteOk ? GameCheck.Unknown
                              : GameVersion.Outdated(local?.Version, remote) ? GameCheck.Outdated : GameCheck.Current;
            }
            lock (gate) last = state;
            return state;
        }
    }
}
