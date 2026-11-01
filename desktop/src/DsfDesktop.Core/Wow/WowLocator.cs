using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace DsfDesktop.Wow
{
    public sealed class BuildInfoRow
    {
        public string Branch, Product, Version;
        public bool Active;
    }

    /// <summary>.build.info: a "Name!TYPE:n|..." header row, then one '|'-separated row per installed product</summary>
    public static class BuildInfo
    {
        public static List<BuildInfoRow> Parse(string text)
        {
            var rows = new List<BuildInfoRow>();
            if (string.IsNullOrEmpty(text)) return rows;
            var lines = text.Replace("\r", "").Split('\n').Where(l => l.Trim().Length > 0).ToList();
            if (lines.Count < 2) return rows;
            var cols = lines[0].Split('|').Select(c => c.Split('!')[0].Trim()).ToList();
            int iBranch = cols.IndexOf("Branch"), iProduct = cols.IndexOf("Product"), iVersion = cols.IndexOf("Version"), iActive = cols.IndexOf("Active");
            foreach (var l in lines.Skip(1))
            {
                var f = l.Split('|');
                string At(int i) => i >= 0 && i < f.Length ? f[i] : "";
                rows.Add(new BuildInfoRow { Branch = At(iBranch), Product = At(iProduct), Version = At(iVersion), Active = At(iActive) == "1" });
            }
            return rows;
        }

        public static List<BuildInfoRow> Read(string root)
        {
            try { return Parse(File.ReadAllText(System.IO.Path.Combine(root, ".build.info"))); }
            catch (Exception) { return new List<BuildInfoRow>(); }
        }
    }

    /// <summary>
    /// The WoW Forever flavor folders. Before the launch (2026-11-04) the game is the beta, product wow_classic_beta in
    /// _classic_beta_; from the launch it is product wowf in _forever_. With both installed the beta wins before the launch date and
    /// _forever_ from it (the user's ruling "Beta until launch", 2026-10-21) - unless the other log is the one being written (LogFollow).
    /// </summary>
    public static class WowFlavors
    {
        public const string Beta = "_classic_beta_";
        public const string Forever = "_forever_";
        public static readonly DateTime LaunchDate = new DateTime(2026, 10, 27);   // the user 2026-10-27: the beta is over, production from today
        public static readonly string[] All = { Beta, Forever };

        public static string[] Preferred(DateTime today) => today.Date >= LaunchDate ? new[] { Forever, Beta } : new[] { Beta, Forever };

        public static string FolderForProduct(string product)
        {
            switch ((product ?? "").Trim())
            {
                case "wowf": return Forever;
                case "wow_classic_beta": return Beta;
                case "wow": return "_retail_";
                case "wow_classic": return "_classic_";
                case "wow_classic_era": return "_classic_era_";
                case "wow_beta": return "_beta_";
                case "wowt": return "_ptr_";
                case "wow_classic_ptr": return "_classic_ptr_";
                default: return null;
            }
        }

        /// <summary>a flavor folder holding a game exe (Wow.exe, WowB.exe, WowT.exe, WowClassic*.exe)</summary>
        public static bool HasGame(string flavorDir)
        {
            try { return Directory.Exists(flavorDir) && Directory.GetFiles(flavorDir, "Wow*.exe").Length > 0; }
            catch (Exception) { return false; }
        }
    }

    public sealed class WowInstall
    {
        public string Root;
        /// <summary>the WoW Forever flavors present, in today's preference order</summary>
        public List<string> Flavors = new List<string>();
        public string FlavorDir(string flavor) => System.IO.Path.Combine(Root, flavor);
        public string LogPath(string flavor) => System.IO.Path.Combine(Root, flavor, "Logs", "General.log");
        public string AddOnsDir(string flavor) => System.IO.Path.Combine(Root, flavor, "Interface", "AddOns");
    }

    public static class WowLocator
    {
        /// <summary>the install root for a folder the user picked: the root itself, a flavor folder, or anything inside one</summary>
        public static string NormalizeRoot(string chosen)
        {
            if (string.IsNullOrWhiteSpace(chosen)) return null;
            try
            {
                var d = new DirectoryInfo(chosen.Trim());
                for (int i = 0; d != null && i < 6; i++, d = d.Parent)
                    if (LooksLikeRoot(d.FullName)) return d.FullName;
            }
            catch (Exception) { }
            return null;
        }

        public static bool LooksLikeRoot(string dir)
        {
            try
            {
                if (!Directory.Exists(dir)) return false;
                if (File.Exists(System.IO.Path.Combine(dir, ".build.info"))) return true;
                return WowFlavors.All.Any(f => WowFlavors.HasGame(System.IO.Path.Combine(dir, f)));
            }
            catch (Exception) { return false; }
        }

        public static WowInstall Describe(string root, DateTime today)
        {
            if (root == null || !LooksLikeRoot(root)) return null;
            var rows = BuildInfo.Read(root);
            var inst = new WowInstall { Root = root };
            foreach (var f in WowFlavors.Preferred(today))
            {
                bool listed = rows.Any(r => WowFlavors.FolderForProduct(r.Product) == f);
                if (WowFlavors.HasGame(System.IO.Path.Combine(root, f)) || (listed && Directory.Exists(System.IO.Path.Combine(root, f))))
                    inst.Flavors.Add(f);
            }
            return inst.Flavors.Count > 0 ? inst : null;
        }

        /// <summary>the user's folder first, then Battle.net's records, then the usual places; the first with a WoW Forever flavor wins</summary>
        public static WowInstall Find(string overrideRoot, DateTime today)
        {
            foreach (var c in CandidateRoots(overrideRoot))
            {
                var inst = Describe(c, today);
                if (inst != null) return inst;
            }
            return null;
        }

        public static IEnumerable<string> CandidateRoots(string overrideRoot)
        {
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            IEnumerable<string> All()
            {
                if (!string.IsNullOrWhiteSpace(overrideRoot)) yield return NormalizeRoot(overrideRoot) ?? overrideRoot;
                foreach (var r in FromUninstallKeys()) yield return r;
                foreach (var r in FromProductDb()) yield return r;
                var pf86 = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86);
                var pf = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
                if (!string.IsNullOrEmpty(pf86)) yield return System.IO.Path.Combine(pf86, "World of Warcraft");
                if (!string.IsNullOrEmpty(pf)) yield return System.IO.Path.Combine(pf, "World of Warcraft");
                foreach (var drive in SafeDrives())
                {
                    yield return System.IO.Path.Combine(drive, "World of Warcraft");
                    yield return System.IO.Path.Combine(drive, "Games", "World of Warcraft");
                    yield return System.IO.Path.Combine(drive, "Program Files (x86)", "World of Warcraft");
                    yield return System.IO.Path.Combine(drive, "Battle.net", "World of Warcraft");
                }
            }
            foreach (var r in All())
            {
                if (string.IsNullOrWhiteSpace(r)) continue;
                string full;
                try { full = System.IO.Path.GetFullPath(r.Trim().TrimEnd('\\', '/')); } catch (Exception) { continue; }
                if (seen.Add(full)) yield return full;
            }
        }

        static IEnumerable<string> SafeDrives()
        {
            DriveInfo[] ds;
            try { ds = DriveInfo.GetDrives(); } catch (Exception) { yield break; }
            foreach (var d in ds)
            {
                bool ok;
                try { ok = d.DriveType == DriveType.Fixed && d.IsReady; } catch (Exception) { ok = false; }
                if (ok) yield return d.RootDirectory.FullName;
            }
        }

        /// <summary>Battle.net registers each game under the Uninstall keys, with InstallLocation</summary>
        static IEnumerable<string> FromUninstallKeys()
        {
            var found = new List<string>();
            foreach (var hive in new[] { RegistryHive.LocalMachine, RegistryHive.CurrentUser })
            foreach (var view in new[] { RegistryView.Registry32, RegistryView.Registry64 })
            {
                try
                {
                    using (var bk = RegistryKey.OpenBaseKey(hive, view))
                    using (var un = bk.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall"))
                    {
                        if (un == null) continue;
                        foreach (var name in un.GetSubKeyNames())
                        {
                            using (var k = un.OpenSubKey(name))
                            {
                                var dn = k?.GetValue("DisplayName") as string ?? "";
                                if (dn.IndexOf("World of Warcraft", StringComparison.OrdinalIgnoreCase) < 0 &&
                                    dn.IndexOf("WoW Forever", StringComparison.OrdinalIgnoreCase) < 0) continue;
                                if (k.GetValue("InstallLocation") is string loc && loc.Length > 0) found.Add(loc);
                            }
                        }
                    }
                }
                catch (Exception) { }
            }
            return found;
        }

        /// <summary>Battle.net's Agent keeps every install path in product.db (protobuf); the paths are plain text inside it</summary>
        static IEnumerable<string> FromProductDb()
        {
            var found = new List<string>();
            try
            {
                var pd = Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData);
                var file = System.IO.Path.Combine(pd, "Battle.net", "Agent", "product.db");
                if (!File.Exists(file)) return found;
                byte[] bytes;
                using (var fs = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                {
                    bytes = new byte[Math.Min(fs.Length, 4 * 1024 * 1024)];
                    fs.Read(bytes, 0, bytes.Length);
                }
                foreach (var p in PathsInBlob(bytes)) found.Add(p);
            }
            catch (Exception) { }
            return found;
        }

        /// <summary>printable "X:/..." or "X:\..." runs in a binary blob</summary>
        public static List<string> PathsInBlob(byte[] bytes)
        {
            var text = Encoding.UTF8.GetString(bytes);
            var res = new List<string>();
            foreach (Match m in Regex.Matches(text, @"[A-Za-z]:[\\/][^\x00-\x1f""<>|?*]{2,240}"))
                res.Add(m.Value.TrimEnd(' ', '/', '\\'));
            return res;
        }

        /// <summary>
        /// Which General.log to follow (the APK's LogFollow): the one written most recently; with no log yet (or a tie), today's
        /// preferred flavor. candidates: (flavor, last-write time or null when there is no log).
        /// </summary>
        public static string ChooseFlavor(IList<KeyValuePair<string, DateTime?>> candidates, string[] preferred)
        {
            if (candidates == null || candidates.Count == 0) return null;
            int Rank(string f) { int i = Array.IndexOf(preferred, f); return i < 0 ? 99 : i; }
            var withLog = candidates.Where(c => c.Value.HasValue).ToList();
            if (withLog.Count == 0) return candidates.OrderBy(c => Rank(c.Key)).First().Key;
            var newest = withLog.Max(c => c.Value.Value);
            return withLog.Where(c => c.Value.Value == newest).OrderBy(c => Rank(c.Key)).First().Key;
        }

        public static string ChooseFlavor(WowInstall inst, DateTime today)
        {
            if (inst == null) return null;
            var c = inst.Flavors.Select(f =>
            {
                var p = inst.LogPath(f);
                DateTime? t = null;
                try { if (File.Exists(p)) t = File.GetLastWriteTimeUtc(p); } catch (Exception) { }
                return new KeyValuePair<string, DateTime?>(f, t);
            }).ToList();
            return ChooseFlavor(c, WowFlavors.Preferred(today));
        }
    }
}
