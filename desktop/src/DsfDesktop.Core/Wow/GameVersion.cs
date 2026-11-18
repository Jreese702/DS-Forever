using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;

namespace DsfDesktop.Wow
{
    /// <summary>the PC game against Blizzard's current version: Unknown = not known (no install row, offline, no answer: never
    /// blocks a start), Checking = Blizzard's answer is on its way (the first check of a run, or the Thor's Retry)</summary>
    public enum GameCheck { Unknown, Checking, Current, Outdated }

    /// <summary>
    /// Is the PC's WoW Forever older than Blizzard's? (the user's PC 2026-11-18 06:20: .build.info said "us ... wowf 1.60.1.70938"
    /// while us.patch.battle.net said 1.60.1.70959; Battle.net was signed out, so nothing patched it, and WoW found "no servers
    /// available".) The pure rules: the install's row of .build.info (its Branch = the region, its Version) and the patch server's
    /// versions table ("Region!STRING:0|BuildConfig!HEX:16|...|BuildId!DEC:4|VersionsName!String:0|...", one row per region).
    /// </summary>
    public static class GameVersion
    {
        /// <summary>the patch server's versions table of a product for a region (Blizzard's own, plain HTTP on 1119, as the Thor's
        /// own updater reads it)</summary>
        public static string VersionsUrl(string region, string product) =>
            "http://" + Region(region) + ".patch.battle.net:1119/" + product + "/versions";

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

        DateTime localAt = DateTime.MinValue, remoteAt = DateTime.MinValue;
        BuildInfoRow local;
        string remote;
        bool remoteOk, fetching;
        string remoteRegion;

        /// <param name="rows">the install's .build.info rows (empty = no install found)</param>
        /// <param name="fetch">url -> the body, null = no answer (offline, a timeout, an error); may block (it runs in [run])</param>
        public GameVersionCheck(Func<List<BuildInfoRow>> rows, Func<string, string> fetch, string product = "wowf",
                                Func<DateTime> now = null, Action<Action> run = null)
        {
            this.rows = rows; this.fetch = fetch; this.product = product;
            this.now = now ?? (() => DateTime.UtcNow);
            this.run = run ?? (a => Task.Run(a));
        }

        /// <summary>the installed version ("1.60.1.70938"), null = not known</summary>
        public string Installed { get { lock (gate) return local?.Version; } }
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
                    try { local = GameVersion.Installed(rows(), product); } catch (Exception) { local = null; }
                }
                if (local == null || GameVersion.Build(local.Version) <= 0) return GameCheck.Unknown;
                region = GameVersion.Region(local.Branch);
                if (region != remoteRegion) { remoteRegion = region; remoteAt = DateTime.MinValue; remoteOk = false; remote = null; }
                double age = (t - remoteAt).TotalSeconds;
                if (!fetching && age >= (remoteOk ? CacheS : OfflineS))
                {
                    fetching = true; go = true; remoteAt = t;
                    url = GameVersion.VersionsUrl(region, product);
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
                    string body = null;
                    try { body = fetch(u); } catch (Exception) { body = null; }
                    var cur = GameVersion.Current(body, reg);
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
            return state;
        }
    }
}
