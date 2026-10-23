using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// companion/manifest.txt, the same file the APK ships: "set &lt;n&gt;", then "&lt;md5&gt;  &lt;relative path&gt;" per file of the add-on set.
    /// </summary>
    public sealed class AddonManifest
    {
        public int? Set;
        public List<KeyValuePair<string, string>> Files = new List<KeyValuePair<string, string>>();   // md5 -> rel path ('/')

        public IEnumerable<string> Folders => Files.Select(f => f.Value.Split('/')[0]).Distinct(StringComparer.OrdinalIgnoreCase);

        /// <summary>parsed exactly as LauncherSetup: the "set " line, and lines over 34 chars as 32 hex + 2 spaces + path</summary>
        public static AddonManifest Parse(string text)
        {
            var m = new AddonManifest();
            foreach (var raw in (text ?? "").Replace("\r", "").Split('\n'))
            {
                if (raw.StartsWith("set ", StringComparison.Ordinal))
                {
                    if (int.TryParse(raw.Substring(4).Trim(), NumberStyles.None, CultureInfo.InvariantCulture, out int s)) m.Set = s;
                    continue;
                }
                if (raw.Length <= 34) continue;
                var md5 = raw.Substring(0, 32).ToLowerInvariant();
                var rel = raw.Substring(34).Trim().Replace('\\', '/');
                if (rel.Length == 0 || rel.Contains("..") || System.IO.Path.IsPathRooted(rel)) continue;   // never outside AddOns
                m.Files.Add(new KeyValuePair<string, string>(md5, rel));
            }
            return m;
        }
    }

    public enum AddonState { NoGame, Missing, Outdated, Current, Differs }

    public sealed class AddonStatus
    {
        public AddonState State;
        public int? AppSet, GameSet;
        public int Differ, Total;
        public string AddOnsDir;
        public override string ToString()
        {
            switch (State)
            {
                case AddonState.NoGame: return "game folder not found";
                case AddonState.Missing: return "not installed (set " + AppSet + " ready)";
                case AddonState.Outdated: return "update ready: set " + GameSet + " -> " + AppSet + " (" + Differ + " files)";
                case AddonState.Current: return "up to date (set " + AppSet + ")";
                default: return Differ + " of " + Total + " files differ from set " + AppSet + " (not ours to overwrite without asking)";
            }
        }
    }

    /// <summary>
    /// Installs DS Forever's add-on set into a PC WoW's Interface/AddOns, by the APK's rules (LauncherSetup):
    ///  - install when a folder of the set is MISSING; update when the game's set stamp is OLDER than ours and files differ; an install
    ///    that differs with no older stamp is left alone unless the user presses Install (force);
    ///  - a backup of our folders first, every copied file checked by md5, the old set put back on any failure;
    ///  - files the set no longer has are removed, inside the set's own folders only;
    ///  - LEAVE NO TRACE: nothing outside Interface/AddOns/&lt;our folders&gt; is ever written - no WTF, no Config.wtf, no macros,
    ///    no bindings, no CVars. (The APK's Config.wtf step is deliberately not ported; <see cref="ConfigCheck"/> only reads.)
    /// </summary>
    public static class AddonInstaller
    {
        public const string Stamp = ".companion_set";

        public static string Md5Of(string file)
        {
            try
            {
                if (!File.Exists(file)) return null;
                using (var h = MD5.Create())
                using (var s = File.OpenRead(file)) return Crypto.Hex(h.ComputeHash(s));
            }
            catch (Exception) { return null; }
        }

        static string Under(string dir, string rel) => System.IO.Path.Combine(dir, rel.Replace('/', System.IO.Path.DirectorySeparatorChar));

        public static int? ReadStamp(string addOnsDir)
        {
            try
            {
                var f = System.IO.Path.Combine(addOnsDir, "WowCompanion", Stamp);
                if (!File.Exists(f)) return null;
                return int.TryParse(File.ReadAllText(f).Trim(), NumberStyles.None, CultureInfo.InvariantCulture, out int v) ? v : (int?)null;
            }
            catch (Exception) { return null; }
        }

        public static AddonStatus Compare(AddonManifest m, string addOnsDir)
        {
            var st = new AddonStatus { AppSet = m.Set, AddOnsDir = addOnsDir, Total = m.Files.Count };
            if (addOnsDir == null) { st.State = AddonState.NoGame; return st; }
            st.GameSet = ReadStamp(addOnsDir);
            bool missing = m.Folders.Any(f => !Directory.Exists(System.IO.Path.Combine(addOnsDir, f)));
            st.Differ = m.Files.Count(f => Md5Of(Under(addOnsDir, f.Value)) != f.Key);
            if (missing) st.State = AddonState.Missing;
            else if (st.Differ == 0) st.State = AddonState.Current;
            else if (m.Set != null && st.GameSet != null && st.GameSet < m.Set) st.State = AddonState.Outdated;
            else st.State = AddonState.Differs;
            return st;
        }

        /// <summary>what an automatic pass would do: install or update, never overwrite a differing set without an older stamp</summary>
        public static bool WantsAuto(AddonStatus s) => s.State == AddonState.Missing || s.State == AddonState.Outdated;

        /// <summary>
        /// Copies the set from sourceRoot (holding manifest.txt's "addons/&lt;rel&gt;") into addOnsDir. Returns one status line; throws
        /// nothing. force = the user pressed Install (any differing set is replaced).
        /// </summary>
        public static string Install(AddonManifest m, string sourceAddons, string addOnsDir, string backupRoot, bool force)
        {
            var before = Compare(m, addOnsDir);
            if (before.State == AddonState.NoGame) return "add-ons: game folder not found";
            if (before.State == AddonState.Current) return "add-ons: " + before;
            if (!force && !WantsAuto(before)) return "add-ons: " + before;

            // the source must itself be the set the manifest describes, before anything is touched
            var badSource = m.Files.Where(f => Md5Of(Under(sourceAddons, f.Value)) != f.Key).Select(f => f.Value).ToList();
            if (badSource.Count > 0) return "add-ons NOT installed: the app's own copy is damaged (" + badSource[0] + ")";

            var folders = m.Folders.ToList();
            string backup = System.IO.Path.Combine(backupRoot, "addons-" + DateTime.Now.ToString("yyyyMMdd-HHmmss-fff"));
            try
            {
                Directory.CreateDirectory(addOnsDir);
                foreach (var f in folders)
                {
                    var src = System.IO.Path.Combine(addOnsDir, f);
                    if (Directory.Exists(src)) CopyTree(src, System.IO.Path.Combine(backup, f));
                }
            }
            catch (Exception e) { return "add-ons NOT installed: backup failed (" + e.Message + ")"; }

            try
            {
                foreach (var f in m.Files)
                {
                    var to = Under(addOnsDir, f.Value);
                    Directory.CreateDirectory(System.IO.Path.GetDirectoryName(to));
                    var tmp = to + ".dsf-new";
                    File.Copy(Under(sourceAddons, f.Value), tmp, true);
                    if (File.Exists(to)) File.Delete(to);
                    File.Move(tmp, to);
                }
                var keep = new HashSet<string>(m.Files.Select(f => f.Value), StringComparer.OrdinalIgnoreCase);
                foreach (var folder in folders)
                {
                    var dir = System.IO.Path.Combine(addOnsDir, folder);
                    foreach (var file in Directory.GetFiles(dir, "*", SearchOption.AllDirectories))
                    {
                        if (System.IO.Path.GetFileName(file) == Stamp) continue;
                        var rel = file.Substring(addOnsDir.TrimEnd('\\', '/').Length + 1).Replace('\\', '/');
                        if (!keep.Contains(rel)) File.Delete(file);
                    }
                }
                int bad = m.Files.Count(f => Md5Of(Under(addOnsDir, f.Value)) != f.Key);
                if (bad > 0) throw new IOException(bad + " copied files do not match the set");
                if (m.Set != null) File.WriteAllText(System.IO.Path.Combine(addOnsDir, "WowCompanion", Stamp), m.Set.Value.ToString(CultureInfo.InvariantCulture) + "\n");
                return "add-ons " + (before.State == AddonState.Missing ? "installed" : "updated") + ": set " + m.Set + ", " + m.Files.Count + " files";
            }
            catch (Exception e)
            {
                foreach (var f in folders)
                {
                    // each folder on its own: one that can't be put back must not stop the others
                    try
                    {
                        var dir = System.IO.Path.Combine(addOnsDir, f);
                        var b = System.IO.Path.Combine(backup, f);
                        if (Directory.Exists(b)) { CopyTree(b, dir); RemoveExtra(dir, b); }
                        else if (Directory.Exists(dir)) Directory.Delete(dir, true);
                    }
                    catch (Exception) { }
                }
                return "add-ons NOT installed (" + e.Message + "); the old set is back";
            }
        }

        /// <summary>files in dir that the backup doesn't have (the new set's additions) go</summary>
        static void RemoveExtra(string dir, string backup)
        {
            foreach (var f in Directory.GetFiles(dir, "*", SearchOption.AllDirectories))
                if (!File.Exists(backup + f.Substring(dir.Length))) try { File.Delete(f); } catch (Exception) { }
        }

        static void CopyTree(string from, string to)
        {
            Directory.CreateDirectory(to);
            foreach (var d in Directory.GetDirectories(from, "*", SearchOption.AllDirectories))
                Directory.CreateDirectory(d.Replace(from, to));
            foreach (var f in Directory.GetFiles(from, "*", SearchOption.AllDirectories))
                File.Copy(f, f.Replace(from, to), true);
        }
    }

    /// <summary>
    /// READ-ONLY look at the flavor's WTF/Config.wtf for the settings the companion needs. Leave-no-trace: Desktop never writes CVars,
    /// it tells the player what to switch on in the game's own options.
    /// </summary>
    public static class ConfigCheck
    {
        public static List<string> Notices(string flavorDir)
        {
            var res = new List<string>();
            try
            {
                var f = System.IO.Path.Combine(flavorDir, "WTF", "Config.wtf");
                if (!File.Exists(f)) { res.Add("The game hasn't been started from this folder yet."); return res; }
                var lines = File.ReadAllLines(f);
                string Value(string k) => lines.Where(l => l.StartsWith("SET " + k + " ", StringComparison.OrdinalIgnoreCase))
                    .Select(l => { int a = l.IndexOf('"'), b = l.LastIndexOf('"'); return a >= 0 && b > a ? l.Substring(a + 1, b - a - 1) : null; })
                    .FirstOrDefault();
                if (Value("developerLog") != "1")
                    res.Add("The game's log is off, so the companion gets no data. In the game, type: /console developerLog 1");
                if (Value("InputDeviceInterfaceStyle") != "1")
                    res.Add("Controller mode is off in the game (Options > Controls > Gamepad).");
            }
            catch (Exception) { }
            return res;
        }
    }
}
