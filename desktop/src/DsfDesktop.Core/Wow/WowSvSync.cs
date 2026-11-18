using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// SETTINGS FOLLOW THE PLAYER (the user, 2026-11-18: "my keybinds and stuff on a character in local does not carry over to that character
    /// in streaming"; the pick "Ours + WoW's server sync"). The Thor keeps the latest copy of DS Forever's OWN two saved tables
    /// (WowCompanionDB, WowCompanionSpellsDB); at a stream session's start, before WoW starts here, Desktop hands it the PC's two files
    /// ("sv_read" -> "sv_files"), the Thor merges them per entry into its copy (its local entries win every conflict: the user's hard rule
    /// "dont delete my current hunters settings on local") and sends the result back ("sv_write"), which is written here
    /// ("sv_written"). Desktop never merges or decides; it only reads and writes OUR two files, with these rules:
    ///  - only WTF/Account/&lt;the account signing in&gt;/SavedVariables/WowCompanion.lua and WowCompanionSpells.lua; never another file;
    ///  - the account folder: the one whose name hashes to the Thor's (the same Battle.net account), else the Thor's remembered game account
    ///    (accountList's "!" pick: the folder of that name, or "...#N" for "WoWN"), else the only one; anything else = none (written to
    ///    none, a terse log line). A folder name is never logged or sent;
    ///  - never while WoW runs; never an empty table; only when the file is still exactly what was read (its hash), so a game that saved
    ///    in between is never written over;
    ///  - a dated backup first (SavedVariables/dsf-sync-backups, the newest five per file), then temp + replace.
    /// </summary>
    public static class WowSvSync
    {
        public static readonly IReadOnlyDictionary<string, string> Files = new Dictionary<string, string>
        {
            ["WowCompanionSpellsDB"] = "WowCompanionSpells.lua",
            ["WowCompanionDB"] = "WowCompanion.lua",
        };
        public const string BackupDir = "dsf-sync-backups";
        public const int KeepBackups = 5;
        public const long MaxBytes = 4L * 1024 * 1024;

        public static string Sha256Hex(byte[] b)
        {
            using (var h = SHA256.Create()) return string.Concat(h.ComputeHash(b).Select(x => x.ToString("x2")));
        }

        /// <summary>the hash the Thor sends for its account folder (lower-cased name, UTF-8)</summary>
        public static string AccountHash(string folderName) => Sha256Hex(Encoding.UTF8.GetBytes((folderName ?? "").ToLowerInvariant()));

        /// <summary>the remembered game account's name in an accountList ("WoW1|!WoW2|" -> "WoW2"); null = none</summary>
        public static string Picked(string accountList) =>
            (accountList ?? "").Split('|').Select(a => a.Trim()).Where(a => a.StartsWith("!")).Select(a => a.Substring(1).Trim()).FirstOrDefault(a => a.Length > 0);

        /// <summary>the account folder under [wtfDir] for this sign-in (see the class); null + [why] when none or ambiguous</summary>
        public static string PickAccount(string wtfDir, string acctHash, string accountList, out string why)
        {
            why = null;
            var root = Path.Combine(wtfDir ?? "", "Account");
            var all = Directory.Exists(root)
                ? Directory.GetDirectories(root).Where(d => !Path.GetFileName(d).Equals("SavedVariables", StringComparison.OrdinalIgnoreCase)).ToList()
                : new List<string>();
            if (all.Count == 0) { why = "no account folder yet"; return null; }
            if (!string.IsNullOrEmpty(acctHash))
            {
                var byHash = all.Where(d => AccountHash(Path.GetFileName(d)) == acctHash.ToLowerInvariant()).ToList();
                if (byHash.Count == 1) return byHash[0];
            }
            var pick = Picked(accountList);
            if (pick != null)
            {
                var m = Regex.Match(pick, "^wow(\\d+)$", RegexOptions.IgnoreCase);
                var byPick = all.Where(d =>
                {
                    var n = Path.GetFileName(d);
                    return n.Equals(pick, StringComparison.OrdinalIgnoreCase) || (m.Success && n.EndsWith("#" + m.Groups[1].Value));
                }).ToList();
                if (byPick.Count == 1) return byPick[0];
            }
            if (all.Count == 1) return all[0];
            why = "several game accounts, none of them the one signing in";
            return null;
        }

        /// <summary>"sv_files"'s files for [accountDir]: each of ours with exists / text / hash (a missing file: exists false, hash "")</summary>
        public static List<Dictionary<string, object>> Read(string accountDir)
        {
            var out_ = new List<Dictionary<string, object>>();
            foreach (var kv in Files)
            {
                var p = Path.Combine(accountDir, "SavedVariables", kv.Value);
                var d = new Dictionary<string, object> { ["var"] = kv.Key, ["exists"] = false, ["hash"] = "" };
                try
                {
                    var fi = new FileInfo(p);
                    if (fi.Exists && fi.Length <= MaxBytes)
                    {
                        var bytes = File.ReadAllBytes(p);
                        d["exists"] = true;
                        d["text"] = Encoding.UTF8.GetString(bytes);
                        d["hash"] = Sha256Hex(bytes);
                        d["at"] = (long)(fi.LastWriteTimeUtc - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
                    }
                    else if (fi.Exists) { d["exists"] = true; d["hash"] = "too-big"; }
                }
                catch (Exception) { d["exists"] = true; d["hash"] = "unreadable"; }
                out_.Add(d);
            }
            return out_;
        }

        /// <summary>the file's text as the game reads it</summary>
        public static string FileText(string variable, string lua) => "\n" + variable + " = " + lua + "\n";

        /// <summary>a table's text the Thor sent is usable: one of ours, a bare table, not empty</summary>
        public static bool Usable(string variable, string lua, long n) =>
            variable != null && Files.ContainsKey(variable) && n > 0 && lua != null && lua.Length > 2 && lua.Length <= MaxBytes &&
            lua.StartsWith("{") && lua.EndsWith("}") && lua.IndexOf('\0') < 0;

        /// <summary>
        /// One of OUR files in [accountDir] from the Thor's merged table: "written" | "same" | "refused" (not usable) | "changed" (the file
        /// is no longer what was read: [ifHash]) | "failed". Never while WoW runs (the caller checks). A dated backup first
        /// </summary>
        public static string Write(string accountDir, string variable, string lua, long n, string ifHash, DateTime nowUtc)
        {
            if (!Usable(variable, lua, n)) return "refused";
            var sv = Path.Combine(accountDir, "SavedVariables");
            var path = Path.Combine(sv, Files[variable]);
            var tmp = path + ".dsf-tmp";
            try
            {
                var exists = File.Exists(path);
                var bytes = exists ? File.ReadAllBytes(path) : null;
                var hashNow = exists ? Sha256Hex(bytes) : "";
                if (hashNow != (ifHash ?? "")) return "changed";
                var text = FileText(variable, lua);
                if (exists && Encoding.UTF8.GetString(bytes) == text) return "same";
                Directory.CreateDirectory(sv);
                if (exists && !Backup(path, Path.Combine(sv, BackupDir), nowUtc)) return "failed";
                File.WriteAllText(tmp, text, new UTF8Encoding(false));
                if (exists) File.Replace(tmp, path, null); else File.Move(tmp, path);
                File.SetLastWriteTimeUtc(path, nowUtc);
                return "written";
            }
            catch (Exception) { try { if (File.Exists(tmp)) File.Delete(tmp); } catch (Exception) { } return "failed"; }
        }

        /// <summary>a dated copy of [path] in [dir]; the newest <see cref="KeepBackups"/> of its name stay</summary>
        public static bool Backup(string path, string dir, DateTime nowUtc)
        {
            try
            {
                Directory.CreateDirectory(dir);
                var name = Path.GetFileName(path);
                File.Copy(path, Path.Combine(dir, name + "." + nowUtc.ToString("yyyyMMdd-HHmmss-fff") + ".bak"), true);
                foreach (var old in Directory.GetFiles(dir, name + ".*.bak").OrderByDescending(f => Path.GetFileName(f), StringComparer.Ordinal).Skip(KeepBackups))
                    File.Delete(old);
                Log.I("settings backup kept before sync");
                return true;
            }
            catch (Exception) { return false; }
        }
    }

    /// <summary>
    /// WOW'S OWN SERVER SYNC ON THE PC (the user's pick 2026-11-18, "Ours + WoW's server sync"; a user-ruled exception to leave-no-trace):
    /// the Thor's Settings > General "Sync game keybinds &amp; macros" (off by default) sets these keys in the PC's Config.wtf before WoW
    /// starts, recording each prior value once beside it (Config.wtf's folder, <see cref="Prior"/>); off again puts them back and drops the
    /// record. Checked in WoW Forever's Wow.exe: synchronizeBindings and synchronizeConfig exist (synchronizeSettings / synchronizeMacros do
    /// not). Separate from the controller writer (WowConfig) and never restored after a stream.
    /// </summary>
    public static class WowSyncCvars
    {
        public static readonly string[] Keys = { "synchronizeBindings", "synchronizeConfig" };
        public const string Prior = ".dsf-sync-prior";

        static string KeyOf(string line, out string value)
        {
            value = null;
            var t = line.Trim();
            if (!t.StartsWith("SET ", StringComparison.OrdinalIgnoreCase)) return null;
            var parts = t.Substring(4).Trim().Split(new[] { ' ' }, 2);
            var k = Keys.FirstOrDefault(x => x.Equals(parts[0], StringComparison.OrdinalIgnoreCase));
            if (k != null) value = parts.Length > 1 ? parts[1].Trim() : "";
            return k;
        }

        /// <summary>[text] with [want]'s keys set (values as written) and [drop]'s taken out. Pure</summary>
        public static string Apply(string text, IDictionary<string, string> want, ICollection<string> drop)
        {
            var nl = text.Contains("\r\n") ? "\r\n" : "\n";
            var lines = text.Split(new[] { nl }, StringSplitOptions.None).ToList();
            if (lines.Count > 0 && lines[lines.Count - 1].Length == 0) lines.RemoveAt(lines.Count - 1);
            var done = new HashSet<string>();
            var out_ = new List<string>();
            foreach (var l in lines)
            {
                var k = KeyOf(l, out _);
                if (k == null) { out_.Add(l); continue; }
                if (drop != null && drop.Contains(k)) continue;
                if (want.ContainsKey(k)) { if (done.Add(k)) out_.Add("SET " + k + " " + want[k]); continue; }
                out_.Add(l);
            }
            foreach (var kv in want) if (!done.Contains(kv.Key)) out_.Add("SET " + kv.Key + " " + kv.Value);
            return string.Join(nl, out_) + nl;
        }

        /// <summary>the keys' values now (as written); a missing key is absent</summary>
        public static Dictionary<string, string> Values(string text)
        {
            var d = new Dictionary<string, string>();
            foreach (var l in text.Split('\n')) { var k = KeyOf(l.TrimEnd('\r'), out var v); if (k != null) d[k] = v; }
            return d;
        }

        /// <summary>the PC's Config.wtf at [path] for [on]; what was done (key names only). No file yet: the game makes its own</summary>
        public static string Ensure(string path, bool on)
        {
            var prior = Path.Combine(Path.GetDirectoryName(path) ?? "", Prior);
            var tmp = path + ".dsf-sync-tmp";
            try
            {
                if (!File.Exists(path)) return "game sync: no Config.wtf yet";
                var was = File.ReadAllText(path);
                string now;
                if (on)
                {
                    if (!File.Exists(prior))
                    {
                        var v = Values(was);
                        File.WriteAllText(prior, string.Join("\n", Keys.Select(k => v.TryGetValue(k, out var x) ? k + "=" + x : k)) + "\n");
                    }
                    now = Apply(was, Keys.ToDictionary(k => k, k => "\"1\""), null);
                }
                else
                {
                    if (!File.Exists(prior)) return "game sync: off";
                    var want = new Dictionary<string, string>();
                    var drop = new HashSet<string>();
                    foreach (var l in File.ReadAllLines(prior))
                    {
                        var k = Keys.FirstOrDefault(x => x == l.Split('=')[0].Trim());
                        if (k == null) continue;
                        if (l.Contains("=")) want[k] = l.Substring(l.IndexOf('=') + 1); else drop.Add(k);
                    }
                    now = Apply(was, want, drop);
                }
                if (now != was) { File.WriteAllText(tmp, now); File.Replace(tmp, path, null); }
                if (!on) File.Delete(prior);
                return on ? "game sync: on (" + string.Join(" ", Keys) + ")" : "game sync: off (" + string.Join(" ", Keys) + " as they were)";
            }
            catch (Exception e) { try { if (File.Exists(tmp)) File.Delete(tmp); } catch (Exception) { } return "game sync: " + e.GetType().Name; }
        }
    }
}
