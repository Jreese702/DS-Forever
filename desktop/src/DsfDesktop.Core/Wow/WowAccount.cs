using System;
using System.IO;
using System.Linq;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// THE REMEMBERED GAME ACCOUNT ON THE PC (the user's pick 2026-11-17, "Yes, remember it"): with several game accounts on one
    /// Battle.net login the client asks which one after its logon (the PC 2026-11-17: numGameAccounts 2, the account picker, then the
    /// region screen), and an auto sign-in stops there. The Thor remembers the pick the game's own way (GameConfigWtf, issue #47:
    /// Config.wtf's accountList, the remembered one marked "!", e.g. "WoW1|!WoW2|"), and the PC now does too: the Thor's remembered
    /// list (its "Game account: Remembered" setting; never sent with "Ask each time") goes into the PC's Config.wtf before WoW starts.
    ///  - ONLY that key; every other line stays as it is (the region and portal are never written: a region change locks nothing in).
    ///  - The PC's own remembered pick wins: a list there that already holds a "!" is WoW's own choice and is never replaced.
    ///  - The file is backed up beside itself once, before Desktop's first write (Config.wtf.dsf-bak; a later write keeps that
    ///    first backup), replaced whole (File.Replace; its temp copy never stays behind), never while WoW runs.
    ///  - The value is an account name: it is never logged; the log says the key's name and what was done.
    /// </summary>
    public static class WowAccount
    {
        public const string Key = "accountList";
        public const string Backup = ".dsf-bak";

        /// <summary>an accountList value that remembers a pick (one name marked "!"), as the Thor sends it (no quotes, one line)</summary>
        public static bool Valid(string list) =>
            !string.IsNullOrEmpty(list) && list.Length <= 512 && list.IndexOfAny(new[] { '"', '\r', '\n', '\0' }) < 0 && Remembers(list);

        /// <summary>the list marks a remembered game account</summary>
        public static bool Remembers(string value) => (value ?? "").Trim().Trim('"').Split('|').Any(a => a.Trim().StartsWith("!"));

        static bool IsKey(string line, out string value)
        {
            value = null;
            var t = line.Trim();
            if (!t.StartsWith("SET ", StringComparison.OrdinalIgnoreCase)) return false;
            var parts = t.Substring(4).Trim().Split(new[] { ' ' }, 2);
            if (!parts[0].Equals(Key, StringComparison.OrdinalIgnoreCase)) return false;
            value = parts.Length > 1 ? parts[1] : "";
            return true;
        }

        /// <summary>[text] with [list] as its accountList; unchanged when [list] is not <see cref="Valid"/> or the file already remembers
        /// a game account of its own. Pure</summary>
        public static string Apply(string text, string list)
        {
            if (!Valid(list) || text == null) return text;
            var nl = text.Contains("\r\n") ? "\r\n" : "\n";
            var lines = text.Split(new[] { nl }, StringSplitOptions.None).ToList();
            if (lines.Count > 0 && lines[lines.Count - 1].Length == 0) lines.RemoveAt(lines.Count - 1);
            var want = "SET " + Key + " \"" + list + "\"";
            int at = -1;
            for (int i = 0; i < lines.Count; i++)
            {
                if (!IsKey(lines[i], out var v)) continue;
                if (Remembers(v)) return text;            // WoW's own remembered pick on the PC
                if (at < 0) at = i;
            }
            if (at >= 0) lines[at] = want; else lines.Add(want);
            return string.Join(nl, lines) + nl;
        }

        /// <summary>the PC's Config.wtf at [path] remembers [list] (see the class); returns what was done, for the log (never a value).
        /// A game never started has no file yet: nothing is made then, the client writes its own</summary>
        public static string Ensure(string path, string list)
        {
            var tmp = path + ".dsf-tmp";
            try
            {
                if (!Valid(list)) return "nothing to remember";
                if (!File.Exists(path)) return "no Config.wtf yet";
                var was = File.ReadAllText(path);
                var now = Apply(was, list);
                if (now == was) return "kept (the PC's own)";
                File.WriteAllText(tmp, now);
                // the backup is the file as it was BEFORE Desktop first touched it: a later write never replaces it
                var bak = path + Backup;
                var first = !File.Exists(bak);
                File.Replace(tmp, path, first ? bak : null);
                return Key + " written (" + (first ? "backup " + Path.GetFileName(bak) : "backup kept") + ")";
            }
            catch (Exception e) { return "not written (" + e.GetType().Name + ")"; }
            finally
            {
                // the temp copy holds the whole file (account names): it never stays behind, e.g. when Config.wtf is read-only
                try { if (File.Exists(tmp)) File.Delete(tmp); } catch (Exception) { }
            }
        }
    }
}
