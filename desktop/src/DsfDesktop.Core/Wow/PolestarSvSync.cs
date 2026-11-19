using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// POLESTAR PROGRESS FOLLOWS (the user's pick 2026-11-18: Polestar's progress between Local and Streaming; Desktop 0.2.42). Polestar's
    /// OWN saved tables (its .toc: "SavedVariables: PolestarDB", "SavedVariablesPerCharacter: PolestarCharDB"), both saved as Polestar.lua:
    ///  - account: WTF/Account/&lt;account&gt;/SavedVariables/Polestar.lua (PolestarDB), key "acct";
    ///  - per character: WTF/Account/&lt;account&gt;/&lt;realm&gt;/&lt;character&gt;/SavedVariables/Polestar.lua (PolestarCharDB), key "c:" + the
    ///    sha-256 of "realm/character" lower-cased (the Thor hashes its own folders the same way: no name ever crosses or is logged).
    /// Desktop only reads and writes these files, with WowSvSync's rules (the Thor merges, per entry, its local entries winning the first
    /// sync): never while WoW runs (the caller), never an empty / non-table text, only when the file is still exactly what was read
    /// (its hash), a dated backup first (dsf-sync-backups beside it, the newest five), the file as it was before the FIRST sync write kept
    /// for good (.presync), temp + replace. Only an EXISTING character folder is written (the game makes it at the first login there; a
    /// character never played on the PC gets its progress at the next session after that). No other add-on's file is ever touched.
    /// </summary>
    public static class PolestarSvSync
    {
        public const string File_ = "Polestar.lua";
        public const string AcctVar = "PolestarDB";
        public const string CharVar = "PolestarCharDB";
        public const string AcctKey = "acct";
        /// <summary>what one "ps_files" carries at most in texts (the Thor reads messages up to 2 MB); a file past it is listed "big"</summary>
        public const long MaxTotal = 1_600_000;
        public const long MaxFile = 900_000;

        /// <summary>a character folder's key: "c:" + sha-256 hex of "realm/character" lower-cased</summary>
        public static string CharKey(string realm, string character) =>
            "c:" + WowSvSync.Sha256Hex(Encoding.UTF8.GetBytes(((realm ?? "") + "/" + (character ?? "")).ToLowerInvariant()));

        /// <summary>every key under [accountDir] and its file (the account's, then each character folder that has a SavedVariables)</summary>
        public static List<KeyValuePair<string, string>> Paths(string accountDir)
        {
            var list = new List<KeyValuePair<string, string>> { new KeyValuePair<string, string>(AcctKey, Path.Combine(accountDir, "SavedVariables", File_)) };
            try
            {
                foreach (var realm in Directory.GetDirectories(accountDir))
                {
                    var rn = Path.GetFileName(realm);
                    if (rn.Equals("SavedVariables", StringComparison.OrdinalIgnoreCase)) continue;
                    foreach (var ch in Directory.GetDirectories(realm))
                    {
                        if (!Directory.Exists(Path.Combine(ch, "SavedVariables"))) continue;
                        list.Add(new KeyValuePair<string, string>(CharKey(rn, Path.GetFileName(ch)), Path.Combine(ch, "SavedVariables", File_)));
                    }
                }
            }
            catch (Exception) { }
            return list;
        }

        public static string VarOf(string key) => key == AcctKey ? AcctVar : CharVar;

        /// <summary>"ps_files"'s files: each key with exists / hash / text (while the message stays under <see cref="MaxTotal"/>)</summary>
        public static List<Dictionary<string, object>> Read(string accountDir)
        {
            var out_ = new List<Dictionary<string, object>>();
            long total = 0;
            foreach (var kv in Paths(accountDir))
            {
                var d = new Dictionary<string, object> { ["key"] = kv.Key, ["var"] = VarOf(kv.Key), ["exists"] = false, ["hash"] = "" };
                try
                {
                    var fi = new FileInfo(kv.Value);
                    if (fi.Exists)
                    {
                        d["exists"] = true;
                        // 0.2.43: when the PC's file was last written (Unix ms, UTC): the Thor lets the newer side win a conflict (its own
                        // file is only as fresh as its game's last clean exit)
                        d["mtime"] = (long)(fi.LastWriteTimeUtc - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
                        if (fi.Length > MaxFile || total + fi.Length > MaxTotal) { d["hash"] = "big"; d["big"] = true; }
                        else
                        {
                            var bytes = File.ReadAllBytes(kv.Value);
                            d["text"] = Encoding.UTF8.GetString(bytes);
                            d["hash"] = WowSvSync.Sha256Hex(bytes);
                            total += bytes.Length;
                        }
                    }
                }
                catch (Exception) { d["exists"] = true; d["hash"] = "unreadable"; }
                out_.Add(d);
            }
            return out_;
        }

        public static bool Usable(string key, string variable, string lua, long n) =>
            key != null && variable == VarOf(key) && n > 0 && lua != null && lua.Length > 2 && lua.Length <= MaxFile &&
            lua.StartsWith("{") && lua.EndsWith("}") && lua.IndexOf('\0') < 0;

        /// <summary>
        /// One Polestar file under [accountDir] from the Thor's merged table: "written" | "same" | "refused" | "changed" | "none" (no such
        /// character folder here) | "failed". Never while WoW runs (the caller checks).
        /// </summary>
        public static string Write(string accountDir, string key, string variable, string lua, long n, string ifHash, DateTime nowUtc)
        {
            if (!Usable(key, variable, lua, n)) return "refused";
            var path = Paths(accountDir).Where(kv => kv.Key == key).Select(kv => kv.Value).FirstOrDefault();
            if (path == null) return "none";
            var sv = Path.GetDirectoryName(path);
            if (!Directory.Exists(sv) && key != AcctKey) return "none";
            var tmp = path + ".dsf-tmp";
            try
            {
                var exists = File.Exists(path);
                var bytes = exists ? File.ReadAllBytes(path) : null;
                var hashNow = exists ? WowSvSync.Sha256Hex(bytes) : "";
                if (hashNow != (ifHash ?? "")) return "changed";
                var text = WowSvSync.FileText(variable, lua);
                if (exists && Encoding.UTF8.GetString(bytes) == text) return "same";
                Directory.CreateDirectory(sv);
                var backups = Path.Combine(sv, WowSvSync.BackupDir);
                if (exists && !WowSvSync.KeepFirst(path, backups)) return "failed";
                if (exists && !WowSvSync.Backup(path, backups, nowUtc)) return "failed";
                File.WriteAllText(tmp, text, new UTF8Encoding(false));
                if (exists) File.Replace(tmp, path, null); else File.Move(tmp, path);
                File.SetLastWriteTimeUtc(path, nowUtc);
                return "written";
            }
            catch (Exception) { try { if (File.Exists(tmp)) File.Delete(tmp); } catch (Exception) { } return "failed"; }
        }
    }
}
