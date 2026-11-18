using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using DsfDesktop.Wow;

namespace DsfDesktop.Tests
{
    /// <summary>settings follow (0.2.37): the PC's half of the Thor's sync of OUR two SavedVariables tables, and WoW's own sync keys. Temp
    /// folders only, fake account names only</summary>
    public static class SvSyncTests
    {
        static string Acct(Tmp t, string name) { var d = t.P("WTF", "Account", name, "SavedVariables"); Directory.CreateDirectory(d); return Path.GetDirectoryName(d); }

        [Test]
        public static void TheAccountFolderIsTheOneSigningIn()
        {
            using (var t = new Tmp())
            {
                var wtf = t.P("WTF");
                A.Null(WowSvSync.PickAccount(wtf, "", null, out var why), "no folder yet");
                A.Eq("no account folder yet", why);
                var one = Acct(t, "11111111#1");
                Directory.CreateDirectory(t.P("WTF", "Account", "SavedVariables"));            // the game's all-accounts folder is no account
                A.Eq(one, WowSvSync.PickAccount(wtf, "", null, out _), "the only one");
                var two = Acct(t, "11111111#2");
                A.Null(WowSvSync.PickAccount(wtf, "", null, out why), "two and nothing to tell them apart: none");
                A.True(why.StartsWith("several"), why);
                A.Eq(two, WowSvSync.PickAccount(wtf, WowSvSync.AccountHash("11111111#2"), null, out _), "the Thor's folder by its hash");
                A.Eq(two, WowSvSync.PickAccount(wtf, WowSvSync.AccountHash("11111111#2".ToUpperInvariant()), null, out _), "hash of the lower-cased name");
                A.Eq(one, WowSvSync.PickAccount(wtf, "deadbeef", "!WoW1|WoW2|", out _), "the remembered game account WoW1 = ...#1");
                A.Eq(two, WowSvSync.PickAccount(wtf, null, "WoW1|!WoW2|", out _), "WoW2 = ...#2");
                A.Null(WowSvSync.PickAccount(wtf, null, "WoW1|WoW2|", out _), "a list with no pick");
                var named = Acct(t, "FAKEACCT");
                A.Eq(named, WowSvSync.PickAccount(wtf, null, "!fakeacct|", out _), "a legacy folder named after the account");
            }
        }

        [Test]
        public static void ReadThenWriteOnlyOurFilesWithBackups()
        {
            using (var t = new Tmp())
            {
                var acct = Acct(t, "11111111#1");
                var sv = Path.Combine(acct, "SavedVariables");
                File.WriteAllText(Path.Combine(sv, "WowCompanion.lua"), "\nWowCompanionDB = {\n[\"a\"] = 1,\n}\n");
                File.WriteAllText(Path.Combine(sv, "Questie.lua"), "not ours");
                File.WriteAllText(Path.Combine(acct, "bindings-cache.wtf"), "the game's own");
                var files = WowSvSync.Read(acct);
                A.Eq(2, files.Count);
                var db = files.First(f => f.Str("var") == "WowCompanionDB");
                var spells = files.First(f => f.Str("var") == "WowCompanionSpellsDB");
                A.True(db["exists"] is bool b && b);
                A.Eq(false, spells["exists"]);
                A.Eq("", spells.Str("hash"));
                var now = new DateTime(2026, 11, 18, 12, 0, 0, DateTimeKind.Utc);
                // an empty or malformed table is never written
                A.Eq("refused", WowSvSync.Write(acct, "WowCompanionDB", "{}", 0, db.Str("hash"), now));
                A.Eq("refused", WowSvSync.Write(acct, "WowCompanionDB", "{[\"a\"]=1", 1, db.Str("hash"), now));
                A.Eq("refused", WowSvSync.Write(acct, "Questie", "{[\"a\"]=1}", 1, "", now));
                // the game saved in between: not written over
                A.Eq("changed", WowSvSync.Write(acct, "WowCompanionDB", "{[\"a\"]=2}", 1, "0000", now));
                A.Eq("written", WowSvSync.Write(acct, "WowCompanionDB", "{[\"a\"]=2,[\"b\"]=3}", 2, db.Str("hash"), now));
                A.Eq("\nWowCompanionDB = {[\"a\"]=2,[\"b\"]=3}\n", File.ReadAllText(Path.Combine(sv, "WowCompanion.lua")));
                A.Eq(now, File.GetLastWriteTimeUtc(Path.Combine(sv, "WowCompanion.lua")));
                var baks = Directory.GetFiles(Path.Combine(sv, WowSvSync.BackupDir), "*.bak");
                A.Eq(1, baks.Length, "a dated backup before the write");
                A.Eq("\nWowCompanionDB = {\n[\"a\"] = 1,\n}\n", File.ReadAllText(baks[0]));
                // a missing file: written when it is still missing
                A.Eq("written", WowSvSync.Write(acct, "WowCompanionSpellsDB", "{[\"x\"]=true}", 1, "", now));
                // the same text again
                var h = WowSvSync.Read(acct).First(f => f.Str("var") == "WowCompanionDB").Str("hash");
                A.Eq("same", WowSvSync.Write(acct, "WowCompanionDB", "{[\"a\"]=2,[\"b\"]=3}", 2, h, now));
                // nothing else touched, no temp file left
                A.Eq("not ours", File.ReadAllText(Path.Combine(sv, "Questie.lua")));
                A.Eq("the game's own", File.ReadAllText(Path.Combine(acct, "bindings-cache.wtf")));
                A.Eq(0, Directory.GetFiles(sv, "*.dsf-tmp").Length);
                // the newest five backups stay
                for (int i = 0; i < 8; i++)
                {
                    var hh = WowSvSync.Read(acct).First(f => f.Str("var") == "WowCompanionDB").Str("hash");
                    A.Eq("written", WowSvSync.Write(acct, "WowCompanionDB", "{[\"a\"]=" + (10 + i) + "}", 1, hh, now.AddSeconds(i + 1)));
                }
                A.Eq(5, Directory.GetFiles(Path.Combine(sv, WowSvSync.BackupDir), "WowCompanion.lua.*.bak").Length);
                // the settings fix (2026-11-18): the PC's own file before the FIRST sync write stays for good, never rotated or replaced
                var first = Path.Combine(sv, WowSvSync.BackupDir, "WowCompanion.lua" + WowSvSync.PreSync);
                A.Eq("\nWowCompanionDB = {\n[\"a\"] = 1,\n}\n", File.ReadAllText(first), "the PC's file before the first sync, kept for good");
                A.False(File.Exists(Path.Combine(sv, WowSvSync.BackupDir, "WowCompanionSpells.lua" + WowSvSync.PreSync)), "a file that was not there: nothing to keep");
            }
        }

        [Test]
        public static void SvMessagesReachTheHost()
        {
            using (var h = new FakeHost())
            using (var s = BridgeTests.Start(h))
            using (var c = new Client(s.Port))
            {
                BridgeTests.Pair(c, h, "thor-sv", stream: false);
                c.Until("backlog_end");
                c.Send(new Dictionary<string, object> { ["t"] = "sv_read", ["acct"] = "abc" });
                var files = c.Until("sv_files").Last();
                A.Eq("ok", files.Str("state"));
                A.Eq("abc", h.SvAcct);
                c.Send(new Dictionary<string, object> { ["t"] = "sv_write", ["acct"] = "abc",
                    ["docs"] = new object[] { new Dictionary<string, object> { ["var"] = "WowCompanionDB", ["lua"] = "{[\"a\"]=1}", ["n"] = 1, ["if_hash"] = "" } } });
                var w = c.Until("sv_written").Last();
                A.Eq(1, h.SvWrites);
                A.True(Json.Write(w).Contains("\"written\""), Json.Write(w));
            }
        }
    }
}
