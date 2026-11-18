using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text;
using DsfDesktop.Wow;

namespace DsfDesktop.Tests
{
    /// <summary>the Thor's add-ons on the PC (PcAddons): QuestieDB, the Thor's pinned packages, its imported add-ons, their enabled state.
    /// Temp folders only, a fake HTTP and a fake Thor; never the real WoW folder</summary>
    public static class PcAddonsTests
    {
        sealed class FakeHttp : IPcHttp
        {
            public readonly Dictionary<string, string> Text = new Dictionary<string, string>();
            public readonly Dictionary<string, byte[]> Files = new Dictionary<string, byte[]>();
            public int Calls;
            public bool Offline;
            /// <summary>a download stops after this many bytes once (a broken line)</summary>
            public long CutAt = -1;
            public string GetText(string url)
            {
                Calls++;
                if (Offline) throw new System.Net.Http.HttpRequestException("offline");
                return Text.TryGetValue(url, out var t) ? t : throw new IOException("HTTP 404");
            }
            public void Download(string url, string part, long expect, Func<long, bool> progress)
            {
                Calls++;
                if (Offline) throw new System.Net.Http.HttpRequestException("offline");
                var b = Files[url];
                long have = File.Exists(part) ? new FileInfo(part).Length : 0;
                long end = b.Length;
                if (CutAt > 0) { end = Math.Min(end, CutAt); CutAt = -1; }
                using (var o = new FileStream(part, FileMode.Append)) o.Write(b, (int)have, (int)(end - have));
                progress(end);
            }
        }

        static byte[] Zip(params (string name, string text)[] files)
        {
            using (var ms = new MemoryStream())
            {
                using (var z = new ZipArchive(ms, ZipArchiveMode.Create, true))
                    foreach (var (n, t) in files)
                        using (var w = new StreamWriter(z.CreateEntry(n).Open())) w.Write(t);
                return ms.ToArray();
            }
        }

        static string Sha(byte[] b) { using (var h = System.Security.Cryptography.SHA256.Create()) return Crypto.Hex(h.ComputeHash(b)); }

        static string Flavor(Tmp t)
        {
            var f = t.P("WoW", "_forever_");
            Directory.CreateDirectory(Path.Combine(f, "Interface", "AddOns"));
            return f;
        }

        static string AddOns(string flavor, params string[] more) => Path.Combine(new[] { flavor, "Interface", "AddOns" }.Concat(more).ToArray());

        static void Mine(string flavor, string folder, string toc = "## Version: 9.9\n")
        {
            Directory.CreateDirectory(AddOns(flavor, folder));
            File.WriteAllText(AddOns(flavor, folder, folder + ".toc"), toc);
        }

        const string Api = "http://127.0.0.1:1/api";

        static string QuestieRelease(string ver, byte[] zip, string url) => Json.Write(new Dictionary<string, object>
        {
            ["tag_name"] = "v" + ver,
            ["assets"] = new object[]
            {
                new Dictionary<string, object> { ["name"] = "QuestieDB-" + ver + "-all.zip", ["size"] = 5, ["browser_download_url"] = "http://127.0.0.1:1/other" },
                new Dictionary<string, object> { ["name"] = "QuestieDB-" + ver + "-Forever.zip", ["size"] = zip.Length, ["digest"] = "sha256:" + Sha(zip), ["browser_download_url"] = url },
            },
        });

        static PcAddons Make(Tmp t, FakeHttp http, Func<bool> running = null) =>
            new PcAddons(t.P("data"), http, running ?? (() => false)) { Api = Api };

        [Test]
        public static void QuestieDbFirstFromItsLatestReleaseThenCheckedEverySixHours()
        {
            using (var t = new Tmp())
            {
                var fl = Flavor(t);
                var http = new FakeHttp();
                var z1 = Zip(("QuestieDB/QuestieDB.toc", "## Version: 1.1.1\n"), ("QuestieDB/db.lua", "x"), ("LICENSE", "MIT"));
                http.Text[Api + "/repos/Questie/QuestieDB/releases/latest"] = QuestieRelease("1.1.1", z1, "https://x/q111.zip");
                http.Files["https://x/q111.zip"] = z1;
                var now = new DateTime(2026, 11, 18, 10, 0, 0, DateTimeKind.Utc);
                var pc = Make(t, http); pc.Now = () => now;
                var r = pc.Run(fl, PcWant.QuestieOnly(), null);
                A.Eq("installed", r.Of("QuestieDB").State, r.Line());
                A.Eq("1.1.1", PcAddons.TocVersion(AddOns(fl, "QuestieDB")));
                A.True(File.Exists(AddOns(fl, "QuestieDB", "LICENSE")), "the licence goes in with it");
                A.False(Directory.Exists(Path.Combine(fl, "Interface", PcAddons.Staging)), "no staging left");
                // within 6 h: no network at all
                http.Calls = 0;
                A.Eq("ok", pc.Run(fl, PcWant.QuestieOnly(), null).Of("QuestieDB").State);
                A.Eq(0, http.Calls, "no call within the recheck");
                // offline later: kept, quietly
                now = now.AddHours(7); http.Offline = true;
                var off = pc.Run(fl, PcWant.QuestieOnly(), null).Of("QuestieDB");
                A.Eq("ok", off.State); A.True(off.Why.Contains("kept (offline)"), off.Why);
                // a newer release: updated, the old copy in the backup (never deleted)
                http.Offline = false;
                var z2 = Zip(("QuestieDB/QuestieDB.toc", "## Version: 1.1.2\n"), ("QuestieDB/db.lua", "y"));
                http.Text[Api + "/repos/Questie/QuestieDB/releases/latest"] = QuestieRelease("1.1.2", z2, "https://x/q112.zip");
                http.Files["https://x/q112.zip"] = z2;
                A.Eq("updated", pc.Run(fl, PcWant.QuestieOnly(), null).Of("QuestieDB").State);
                A.Eq("1.1.2", PcAddons.TocVersion(AddOns(fl, "QuestieDB")));
                A.Eq(1, Directory.GetDirectories(Path.Combine(fl, "Interface", PcAddons.Backup), "QuestieDB-1.1.1-*").Length, "1.1.1 in the backup");
                // deleted by something: put back at the next run (6 h or not)
                Directory.Delete(AddOns(fl, "QuestieDB"), true);
                A.Eq("installed", pc.Run(fl, PcWant.QuestieOnly(), null).Of("QuestieDB").State);
                A.True(File.Exists(AddOns(fl, "QuestieDB", "QuestieDB.toc")));
            }
        }

        [Test]
        public static void QuestieDbOfThePlayersOwnIsLeftAlone()
        {
            using (var t = new Tmp())
            {
                var fl = Flavor(t);
                Mine(fl, "QuestieDB", "## Version: 1.0.0\n");
                var http = new FakeHttp();
                var r = Make(t, http).Run(fl, PcWant.QuestieOnly(), null).Of("QuestieDB");
                A.Eq("yours", r.State);
                A.Eq(0, http.Calls);
                A.Eq("1.0.0", PcAddons.TocVersion(AddOns(fl, "QuestieDB")));
            }
        }

        static PcPkg Manners(byte[] zip, string sha = null) => new PcPkg
        {
            Id = "manners", Key = "core", Title = "Manners", Tag = "1.11.0", Asset = "Manners-v1.11.0.zip", Bytes = zip.Length, Sha256 = sha ?? Sha(zip),
            Url = PcAddons.CdnPrefix + "9408/996/Manners-v1.11.0.zip", Folders = new List<string> { "Manners" }, Want = true, License = "MIT",
        };

        static PcWant Thor(params PcPkg[] p) => new PcWant { FromThor = true, QuestieDb = false, Managed = p.ToList() };

        [Test]
        public static void PinnedPackageInstalledOnlyWithItsPinnedHash()
        {
            using (var t = new Tmp())
            {
                var fl = Flavor(t);
                var http = new FakeHttp();
                var z = Zip(("Manners/Manners_Camelot.toc", "## Version: 1.11.0\n"), ("Manners/m.lua", "x"));
                var p = Manners(z, new string('0', 64));
                http.Files[p.Url] = z;
                var pc = Make(t, http);
                var bad = pc.Run(fl, Thor(p), null).Of("Manners");
                A.Eq("failed", bad.State); A.True(bad.Why.Contains("checksum"), bad.Why);
                A.False(Directory.Exists(AddOns(fl, "Manners")), "nothing goes in");
                A.Eq(0, Directory.GetFiles(t.P("data", "pcaddons", "dl")).Length, "the bad download is not kept");
                // a broken line: resumed from where it stopped
                p = Manners(z);
                http.CutAt = z.Length / 2;
                A.Eq("deferred", pc.Run(fl, Thor(p), null).Of("Manners").State, "half a download waits");
                A.Eq("installed", pc.Run(fl, Thor(p), null).Of("Manners").State);
                A.Eq("1.11.0", PcAddons.TocVersion(AddOns(fl, "Manners")));
                A.True(pc.Ours(fl).Contains("Manners"));
                http.Calls = 0;
                A.Eq("ok", pc.Run(fl, Thor(p), null).Of("Manners").State);
                A.Eq(0, http.Calls, "an installed pin needs no network");
            }
        }

        [Test]
        public static void GitHubPinFoundByItsTagAndSize()
        {
            using (var t = new Tmp())
            {
                var fl = Flavor(t);
                var http = new FakeHttp();
                var z = Zip(("Polestar/Polestar.toc", "## Version: 0.2.5\n"), ("Polestar_Nav/Polestar_Nav.toc", "## Version: 0.2.5\n"),
                    ("Polestar_QuestDB/Polestar_QuestDB.toc", "## Version: 0.2.5\n"), ("Recorder.txt", "not an add-on"), ("LICENSE", "GPL-3.0"), ("THIRD_PARTY.md", "notes"));
                var p = new PcPkg { Id = "polestar", Key = "core", Title = "Polestar", Tag = "v0.2.5", Asset = "Polestar-0.2.5.zip", Bytes = z.Length, Sha256 = Sha(z), Want = true, Folders = new List<string> { "Polestar", "Polestar_Nav" } };
                http.Text[Api + "/repos/fitzforge/polestar/releases/tags/v0.2.5"] = Json.Write(new Dictionary<string, object>
                { ["assets"] = new object[] { new Dictionary<string, object> { ["name"] = "Polestar-0.2.5.zip", ["size"] = z.Length + 1, ["browser_download_url"] = "https://gh/p.zip" } } });
                http.Files["https://gh/p.zip"] = z;
                var pc = Make(t, http);
                var r = pc.Run(fl, Thor(p), null).Of("Polestar");
                A.Eq("failed", r.State, "a release asset of another size"); A.True(r.Why.Contains("size"), r.Why);
                http.Text[Api + "/repos/fitzforge/polestar/releases/tags/v0.2.5"] = Json.Write(new Dictionary<string, object>
                { ["assets"] = new object[] { new Dictionary<string, object> { ["name"] = "Polestar-0.2.5.zip", ["size"] = z.Length, ["browser_download_url"] = "https://gh/p.zip" } } });
                A.Eq("installed", pc.Run(fl, Thor(p), null).Of("Polestar").State);
                foreach (var f in new[] { "Polestar", "Polestar_Nav", "Polestar_QuestDB" })
                {
                    A.True(File.Exists(AddOns(fl, f, f + ".toc")), f + " in (every folder with a .toc, as the Thor)");
                    A.True(File.Exists(AddOns(fl, f, "LICENSE")) && File.Exists(AddOns(fl, f, "THIRD_PARTY.md")), f + " carries the licence");
                }
                A.False(File.Exists(AddOns(fl, "Recorder.txt")), "nothing else of the zip");
            }
        }

        [Test]
        public static void PinsFromElsewhereAreRefused()
        {
            var z = new byte[10];
            var ok = Manners(z);
            A.Null(ok.Invalid());
            var p = Manners(z); p.Id = "evil"; A.NotNull(p.Invalid(), "unknown add-on");
            p = Manners(z); p.Url = "https://example.com/Manners-v1.11.0.zip"; A.NotNull(p.Invalid(), "another host");
            p = Manners(z); p.Url = PcAddons.CdnPrefix + "../x/Manners-v1.11.0.zip"; A.NotNull(p.Invalid(), "a climb");
            p = Manners(z); p.Folders = new List<string> { "WowCompanion" }; A.NotNull(p.Invalid(), "our own folder");
            p = Manners(z); p.Sha256 = "abc"; A.NotNull(p.Invalid(), "no checksum");
            p = Manners(z); p.Id = "polestar"; A.NotNull(p.Invalid(), "a GitHub package never comes by a URL sent");
            using (var t = new Tmp())
            {
                var fl = Flavor(t);
                var http = new FakeHttp();
                var bad = Manners(z); bad.Url = "https://example.com/Manners-v1.11.0.zip";
                A.Eq("refused", Make(t, http).Run(fl, Thor(bad), null).Of("Manners").State);
                A.Eq(0, http.Calls, "nothing fetched");
            }
        }

        [Test]
        public static void PlayersOwnAddOnsAreNeverTouched()
        {
            using (var t = new Tmp())
            {
                var fl = Flavor(t);
                foreach (var f in new[] { "TradeSkillMaster", "Auctionator", "Manners" }) Mine(fl, f);
                var http = new FakeHttp();
                var z = Zip(("Manners/Manners.toc", "## Version: 1.11.0\n"));
                var p = Manners(z); http.Files[p.Url] = z;
                var pc = Make(t, http);
                A.Eq("yours", pc.Run(fl, Thor(p), null).Of("Manners").State, "the player's own Manners");
                A.Eq("9.9", PcAddons.TocVersion(AddOns(fl, "Manners")), "left as it was");
                p.Want = false;
                pc.Run(fl, Thor(p), null);
                A.True(Directory.Exists(AddOns(fl, "Manners")), "not ours: never moved out");
                A.True(Directory.Exists(AddOns(fl, "TradeSkillMaster")) && Directory.Exists(AddOns(fl, "Auctionator")));
                A.False(Directory.Exists(Path.Combine(fl, "Interface", PcAddons.Backup)), "nothing of theirs in a backup");
            }
        }

        [Test]
        public static void SwitchedOffMovesOutOnlyWhatDesktopPutIn()
        {
            using (var t = new Tmp())
            {
                var fl = Flavor(t);
                Mine(fl, "TradeSkillMaster");
                var http = new FakeHttp();
                var z = Zip(("Manners/Manners.toc", "## Version: 1.11.0\n"));
                var p = Manners(z); http.Files[p.Url] = z;
                var pc = Make(t, http);
                A.Eq("installed", pc.Run(fl, Thor(p), null).Of("Manners").State);
                p.Want = false;
                A.Eq("removed", pc.Run(fl, Thor(p), null).Of("Manners").State);
                A.False(Directory.Exists(AddOns(fl, "Manners")));
                A.Eq(1, Directory.GetDirectories(Path.Combine(fl, "Interface", PcAddons.Backup), "Manners-*").Length, "in the backup, not deleted");
                A.False(pc.Ours(fl).Contains("Manners"));
                A.True(Directory.Exists(AddOns(fl, "TradeSkillMaster")));
                // a later Thor that no longer lists a package at all: ours goes out too
                p.Want = true;
                A.Eq("installed", pc.Run(fl, Thor(p), null).Of("Manners").State);
                A.Eq("removed", pc.Run(fl, Thor(), null).Of("manners/core").State);
                // with no Thor's list (Desktop's own start, nothing kept): nothing is moved out
                p.Want = true; pc.Run(fl, Thor(p), null);
                pc.Run(fl, PcWant.QuestieOnly().With(q => q.QuestieDb = false), null);
                A.True(Directory.Exists(AddOns(fl, "Manners")), "kept without a Thor's word");
            }
        }

        static PcWant With(this PcWant w, Action<PcWant> a) { a(w); return w; }

        [Test]
        public static void NothingMovesWhileWowRuns()
        {
            using (var t = new Tmp())
            {
                var fl = Flavor(t);
                var http = new FakeHttp();
                var z = Zip(("Manners/Manners.toc", "## Version: 1.11.0\n"));
                var p = Manners(z); http.Files[p.Url] = z;
                bool running = true;
                var pc = Make(t, http, () => running);
                var r = pc.Run(fl, Thor(p), null).Of("Manners");
                A.Eq("deferred", r.State); A.True(r.Why.Contains("WoW is running"), r.Why);
                A.False(Directory.Exists(AddOns(fl, "Manners")), "nothing in while WoW runs");
                A.False(Directory.Exists(Path.Combine(fl, "Interface", PcAddons.Staging)), "no staging left");
                running = false;
                A.Eq("installed", pc.Run(fl, Thor(p), null).Of("Manners").State, "at the next run, WoW closed");
                running = true; p.Want = false;
                A.Eq("deferred", pc.Run(fl, Thor(p), null).Of("Manners").State, "no move out under WoW");
                A.True(Directory.Exists(AddOns(fl, "Manners")));
            }
        }

        // ---------------------------------------------------------------- the imported ones, from the Thor

        sealed class FakeThor : IPcTransfer
        {
            public readonly Dictionary<string, byte[]> Files = new Dictionary<string, byte[]>();
            public int Calls; public int DropAt = -1; public List<long> Offsets = new List<long>();
            public byte[] Get(string folder, string path, long offset, int length)
            {
                Calls++;
                if (DropAt >= 0 && Calls > DropAt) { DropAt = -1; return null; }
                Offsets.Add(offset);
                var b = Files[folder + "/" + path];
                var n = (int)Math.Min(length, b.Length - offset);
                var res = new byte[n]; Array.Copy(b, offset, res, 0, n); return res;
            }
        }

        static PcImport Import(FakeThor thor, string folder, Dictionary<string, byte[]> files)
        {
            var i = new PcImport { Folder = folder, Version = "2.0", Enabled = true };
            foreach (var kv in files) { thor.Files[folder + "/" + kv.Key] = kv.Value; i.Files.Add(new PcFile { P = kv.Key, N = kv.Value.Length, H = Sha(kv.Value) }); }
            i.Bytes = i.Files.Sum(f => f.N);
            return i;
        }

        [Test]
        public static void ImportedAddOnCopiedFromTheThorResumedAndChecked()
        {
            using (var t = new Tmp())
            {
                var fl = Flavor(t);
                var thor = new FakeThor();
                var big = new byte[PcAddons.Chunk * 2 + 123]; new Random(1).NextBytes(big);
                var i = Import(thor, "Bagnon", new Dictionary<string, byte[]> { ["Bagnon.toc"] = Encoding.UTF8.GetBytes("## Version: 2.0\n"), ["media/bg.blp"] = big });
                var w = new PcWant { FromThor = true, QuestieDb = false, Imported = { i } };
                var pc = Make(t, new FakeHttp());
                A.Eq("deferred", pc.Run(fl, w, null).Of("Bagnon").State, "no Thor connected: waits");
                thor.DropAt = 2;                                         // the Thor goes away part way
                A.Eq("deferred", pc.Run(fl, w, thor).Of("Bagnon").State);
                A.False(Directory.Exists(AddOns(fl, "Bagnon")));
                thor.Offsets.Clear();
                A.Eq("installed", pc.Run(fl, w, thor).Of("Bagnon").State);
                A.Eq((long)PcAddons.Chunk, thor.Offsets.First(), "resumed where it stopped, not started over: " + string.Join(",", thor.Offsets));
                A.True(File.ReadAllBytes(AddOns(fl, "Bagnon", "media", "bg.blp")).SequenceEqual(big));
                thor.Calls = 0;
                A.Eq("ok", pc.Run(fl, w, thor).Of("Bagnon").State);
                A.Eq(0, thor.Calls, "the same content: nothing copied");
                // the Thor's copy changed: a file whose bytes don't match its hash is refused, the old copy stays
                var j = Import(thor, "Bagnon", new Dictionary<string, byte[]> { ["Bagnon.toc"] = Encoding.UTF8.GetBytes("## Version: 2.1\n") });
                j.Files[0].H = new string('a', 64);
                var bad = pc.Run(fl, new PcWant { FromThor = true, QuestieDb = false, Imported = { j } }, thor).Of("Bagnon");
                A.Eq("failed", bad.State); A.True(bad.Why.Contains("checksum"), bad.Why);
                A.Eq("2.0", PcAddons.TocVersion(AddOns(fl, "Bagnon")), "the old copy stays");
                // gone from the Thor's list: Desktop's copy to the backup
                A.Eq("removed", pc.Run(fl, new PcWant { FromThor = true, QuestieDb = false }, thor).Of("Bagnon").State);
                A.False(Directory.Exists(AddOns(fl, "Bagnon")));
            }
        }

        [Test]
        public static void ImportedNeverOverAPlayersFolderOrABadList()
        {
            using (var t = new Tmp())
            {
                var fl = Flavor(t);
                Mine(fl, "Auctionator");
                var thor = new FakeThor();
                var i = Import(thor, "Auctionator", new Dictionary<string, byte[]> { ["Auctionator.toc"] = Encoding.UTF8.GetBytes("## Version: 1\n") });
                var pc = Make(t, new FakeHttp());
                A.Eq("yours", pc.Run(fl, new PcWant { FromThor = true, QuestieDb = false, Imported = { i } }, thor).Of("Auctionator").State);
                A.Eq("9.9", PcAddons.TocVersion(AddOns(fl, "Auctionator")));
                var bad = Import(thor, "Evil", new Dictionary<string, byte[]> { ["Evil.toc"] = new byte[1], ["../../x.lua"] = new byte[1] });
                A.Eq("refused", pc.Run(fl, new PcWant { FromThor = true, QuestieDb = false, Imported = { bad } }, thor).Of("Evil").State);
                var own = Import(thor, "WowCompanionX", new Dictionary<string, byte[]> { ["WowCompanionX.toc"] = new byte[1] });
                A.Eq("refused", pc.Run(fl, new PcWant { FromThor = true, QuestieDb = false, Imported = { own } }, thor).Of("WowCompanionX").State);
                var off = Import(thor, "Bagnon", new Dictionary<string, byte[]> { ["Bagnon.toc"] = new byte[1] }); off.Enabled = false;
                A.Eq("skipped", pc.Run(fl, new PcWant { FromThor = true, QuestieDb = false, Imported = { off } }, thor).Of("Bagnon").State, "off on the Thor: not copied");
                var big = Import(thor, "Huge", new Dictionary<string, byte[]> { ["Huge.toc"] = new byte[1] }); big.Big = true;
                A.Eq("waiting", pc.Run(fl, new PcWant { FromThor = true, QuestieDb = false, Imported = { big } }, thor).Of("Huge").State, "a big one waits for the OK");
            }
        }

        // ---------------------------------------------------------------- the enabled state

        [Test]
        public static void EnabledStateMirroredOnlyForDesktopsFolders()
        {
            var text = "ï»¿TradeSkillMaster: enabled\r\nPolestar: enabled\r\nManners: disabled\r\nAuctionator: disabled\r\n";
            var (o, n) = AddonsTxt.Apply(text, new Dictionary<string, bool> { ["Polestar"] = false, ["Manners"] = true, ["Polestar_Nav"] = false, ["Bagnon"] = true });
            A.Eq("ï»¿TradeSkillMaster: enabled\r\nPolestar: disabled\r\nManners: enabled\r\nAuctionator: disabled\r\nPolestar_Nav: disabled\r\n", o);
            A.Eq(3, n);
            A.Eq(0, AddonsTxt.Apply(o, new Dictionary<string, bool> { ["Polestar"] = false, ["Manners"] = true }).Item2, "the same again: nothing");
            using (var t = new Tmp())
            {
                var fl = Flavor(t);
                Mine(fl, "TradeSkillMaster");
                var acct = Path.Combine(fl, "WTF", "Account", "FAKE#1");
                Directory.CreateDirectory(Path.Combine(acct, "Realm", "Char"));
                Directory.CreateDirectory(Path.Combine(acct, "SavedVariables", "X"));
                File.WriteAllText(Path.Combine(acct, "Realm", "Char", "AddOns.txt"), "TradeSkillMaster: enabled\nManners: enabled\n");
                File.WriteAllText(Path.Combine(acct, "SavedVariables", "X", "AddOns.txt"), "Manners: enabled\n");
                var http = new FakeHttp();
                var z = Zip(("Manners/Manners.toc", "## Version: 1.11.0\n"));
                var p = Manners(z); http.Files[p.Url] = z;
                var w = Thor(p);
                w.Enabled["Manners"] = false; w.Enabled["TradeSkillMaster"] = false; w.Enabled["WowCompanion"] = false;
                bool running = false;
                var pc = Make(t, http, () => running);
                var r = pc.Run(fl, w, null);
                A.Eq("installed", r.Of("Manners").State);
                A.Eq("TradeSkillMaster: enabled\nManners: disabled\n", File.ReadAllText(Path.Combine(acct, "Realm", "Char", "AddOns.txt")), "ours only; the player's TSM stays on");
                A.Eq("Manners: enabled\n", File.ReadAllText(Path.Combine(acct, "SavedVariables", "X", "AddOns.txt")), "no file under SavedVariables");
                w.Enabled["Manners"] = true; running = true;
                A.Eq("deferred", pc.Run(fl, w, null).Of("enabled state").State, "not while WoW runs");
                A.True(File.ReadAllText(Path.Combine(acct, "Realm", "Char", "AddOns.txt")).Contains("Manners: disabled"));
            }
        }

        [Test]
        public static void TheThorsListReadAndKept()
        {
            var m = Json.ReadObject(Json.Write(new Dictionary<string, object>
            {
                ["t"] = "addons_want", ["acct"] = "ab",
                ["questiedb"] = new Dictionary<string, object> { ["want"] = true },
                ["managed"] = new object[] { Manners(new byte[3]).ToJson() },
                ["imported"] = new object[] { new Dictionary<string, object> { ["folder"] = "Bagnon", ["files"] = new object[] { new Dictionary<string, object> { ["p"] = "Bagnon.toc", ["n"] = 3, ["h"] = new string('b', 64) } } } },
                ["enabled"] = new Dictionary<string, object> { ["Manners"] = false, ["bad/name"] = true },
            }));
            var w = PcWant.Parse(m, out _);
            A.True(w.FromThor && w.QuestieDb);
            A.Eq(1, w.Managed.Count); A.Eq("manners", w.Managed[0].Id); A.True(w.Managed[0].Want);
            A.Eq(3L, w.Imported[0].Bytes);
            A.Eq(1, w.Enabled.Count, "a bad name dropped");
            using (var t = new Tmp())
            {
                var pc = Make(t, new FakeHttp());
                A.False(pc.LoadWant().FromThor, "nothing kept yet: QuestieDB alone");
                A.True(pc.LoadWant().QuestieDb);
                pc.SaveWant(w);
                var back = pc.LoadWant();
                A.True(back.FromThor); A.Eq(1, back.Managed.Count); A.Eq(w.Imported[0].Tree(), back.Imported[0].Tree());
            }
        }
    }
}
