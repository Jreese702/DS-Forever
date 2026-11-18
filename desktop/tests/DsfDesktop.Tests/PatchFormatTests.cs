using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using DsfDesktop.Wow;
using DsfDesktop.Wow.Patch;
using static DsfDesktop.Tests.PatchKit;

namespace DsfDesktop.Tests
{
    /// <summary>Desktop 0.2.36's self-patcher, its pure parts: BLTE, the install manifest (the REAL 70938 / 70959 ones), the encoding
    /// table, archive indexes, the patch server's tables, the .build.info row it writes, the Agent's log</summary>
    public static class PatchFormatTests
    {
        static List<InstallEntry> Pc(byte[] blte, string ckey, string tags = PatchFixtures.PcTags)
        {
            var d = Blte.Decode(blte);
            A.Eq(ckey, Hex.Md5(d), "the install manifest is its build config's");
            return InstallManifest.Select(InstallManifest.Parse(d), InstallManifest.TagsOf(tags));
        }

        /// <summary>THE PROOF (the judge's plan): the real manifests give exactly the 6 files 70938 -> 70959 changed on the PC, 127 MB</summary>
        [Test]
        public static void TheRealManifestsChangeExactlySixFiles()
        {
            var old = Pc(Convert.FromBase64String(PatchFixtures.Install70938Blte), PatchFixtures.Install70938CKey);
            var neu = Pc(Convert.FromBase64String(PatchFixtures.Install70959Blte), PatchFixtures.Install70959CKey);
            A.Eq(39, neu.Count, "the PC's client files (Windows x86_64 US enUS)");
            A.Eq(39, old.Count);
            var was = old.ToDictionary(e => e.Name, e => e.CKey, StringComparer.OrdinalIgnoreCase);
            var changed = neu.Where(e => !was.TryGetValue(e.Name, out var k) || k != e.CKey).OrderBy(e => InstallManifest.Rank(e.Name)).ThenBy(e => e.Name, StringComparer.Ordinal).ToList();
            A.Eq("Utils\\BlizzardBrowser.exe,Utils\\WindowsExceptionHandler.dll,Utils\\WowVoiceProxy.exe,Utils\\WowWindowsExceptionHandler.dll,wow_loader.dll,Wow.exe",
                string.Join(",", changed.Select(e => e.Name)));
            A.Eq(127425344L, changed.Sum(e => e.Size), "127 MB");
            // the 70938 content keys = the PC's files, read once (2026-11-18, read only): its Wow.exe and wow_loader.dll
            A.Eq("5720afa0e327507bf5b1cb29c143bee7", old.Single(e => e.Name == "Wow.exe").CKey);
            A.Eq("fd6f3db70938a507101bb9e4d23f20e3", changed.Single(e => e.Name == "Wow.exe").CKey);
            A.Eq("9f96c60cf267df80bb8675b5cf8455fe", old.Single(e => e.Name == "wow_loader.dll").CKey);
        }

        /// <summary>the CN Wow.exe has the same name: tags pick it, never the name</summary>
        [Test]
        public static void TheTagsPickTheRegionsWowExe()
        {
            var blte = Convert.FromBase64String(PatchFixtures.Install70959Blte);
            var us = Pc(blte, PatchFixtures.Install70959CKey).Single(e => e.Name == "Wow.exe");
            var cn = Pc(blte, PatchFixtures.Install70959CKey, PatchFixtures.PcTags.Replace("US?", "CN?")).Single(e => e.Name == "Wow.exe");
            A.Eq(80689360L, us.Size);
            A.Eq(103317712L, cn.Size, "the CN one");
            var m = InstallManifest.Parse(Blte.Decode(blte));
            A.Eq(2, m.Entries.Count(e => e.Name == "Wow.exe"), "two of that name in the manifest");
            // a locale picks too: deDE keeps the same client files here (they carry every locale)
            A.Eq(39, InstallManifest.Select(m, InstallManifest.TagsOf(PatchFixtures.PcTags.Replace("enUS", "deDE"))).Count);
            // no Windows / x86_64 = not this PC
            var e2 = Threw(() => InstallManifest.Select(m, InstallManifest.TagsOf("OSX US? enUS")));
            A.True(e2 is PatchFormatException, "OSX tags refused");
        }

        static Exception Threw(Action a) { try { a(); return null; } catch (Exception e) { return e; } }

        [Test]
        public static void InstallOrderIsUtilsThenLoaderThenWowExeLast()
        {
            var names = new[] { "Wow.exe", "wow_loader.dll", "Utils\\a.dll", "libxell.dll", "UTILS\\LIBCEF.DLL" };
            var sorted = names.Select((n, i) => new { n, i }).OrderBy(x => InstallManifest.Rank(x.n)).ThenBy(x => x.i).Select(x => x.n);
            A.Eq("Utils\\a.dll,UTILS\\LIBCEF.DLL,libxell.dll,wow_loader.dll,Wow.exe", string.Join(",", sorted));
        }

        [Test]
        public static void BlteModesNAndZAndManyChunks()
        {
            var data = Enumerable.Range(0, 50000).Select(i => (byte)(i * 7 % 251)).ToArray();
            A.Eq(Md5(data), Md5(Blte.Decode(BlteZ(data, 4096))), "N and Z chunks");
            A.Eq(Md5(data), Md5(Blte.Decode(BlteSingle(data))), "one chunk, no table");
            // the streamed form counts the encoded bytes as it goes
            var enc = BlteZ(data, 10000);
            long seen = 0;
            using (var i = new MemoryStream(enc)) using (var o = new MemoryStream()) { Blte.Decode(i, o, n => seen = n); A.Eq(50000L, o.Length); }
            A.Eq((long)enc.Length, seen);
        }

        [Test]
        public static void BlteRefusesCutEncryptedAndDamagedData()
        {
            var data = Enumerable.Range(0, 20000).Select(i => (byte)(i % 13)).ToArray();
            var good = BlteZ(data, 8192);
            A.True(Threw(() => Blte.Decode(good.Take(good.Length - 20).ToArray())) is PatchFormatException, "a cut stream");
            var e = Threw(() => Blte.Decode(BlteOf(new[] { Tuple.Create('E', new byte[] { 1, 2, 3 }) })));
            A.True(e is PatchFormatException && e.Message.Contains("mode E"), "E is a hard error: " + e?.Message);
            A.True(Threw(() => Blte.Decode(BlteOf(new[] { Tuple.Create('F', new byte[] { 1 }) }))) is PatchFormatException, "F too");
            A.True(Threw(() => Blte.Decode(BlteOf(new[] { Tuple.Create('Z', data) }, badHash: 0))) is PatchFormatException, "a chunk's checksum");
            // a zlib chunk that inflates short of its table size
            var rnd = new byte[20000]; new Random(7).NextBytes(rnd);
            var z = BlteOf(new[] { Tuple.Create('Z', rnd) });
            var cut = z.Take(z.Length - 200).ToArray();
            // fix the table's encoded size so only the zlib stream is cut
            var tableFixed = (byte[])cut.Clone();
            var size = Be32(cut.Length - 36);
            Buffer.BlockCopy(size, 0, tableFixed, 12, 4);
            var hash = System.Security.Cryptography.MD5.Create().ComputeHash(tableFixed, 36, tableFixed.Length - 36);
            Buffer.BlockCopy(hash, 0, tableFixed, 20, 16);
            A.True(Threw(() => Blte.Decode(tableFixed)) is PatchFormatException, "a cut zlib chunk");
            A.True(Threw(() => Blte.Decode(B("<html>sign in</html>"))) is PatchFormatException, "a portal page");
        }

        /// <summary>the encoding table by ranges: pages across chunk boundaries, a key on a page boundary, a key that isn't there</summary>
        [Test]
        public static void EncodingLookupAcrossPagesAndChunks()
        {
            var map = new Dictionary<string, string>();
            for (int i = 0; i < 300; i++) map[Key("c" + i)] = Key("e" + i);
            var table = Enc(map, 1);
            var blte = BlteZ(table, 700);           // chunks of 700 bytes: every 1 KiB page spans two
            int fetches = 0;
            var r = new RangedBlte((o, n) => { fetches++; return blte.Skip((int)o).Take(n).ToArray(); });
            var sortedKeys = map.Keys.OrderBy(k => k, StringComparer.Ordinal).ToList();
            var want = new List<string> { sortedKeys[0], sortedKeys[26], sortedKeys[27], sortedKeys[299], sortedKeys[150], Key("not there") };
            var got = EncodingTable.Find(r.Read, want);
            A.Eq(5, got.Count);
            foreach (var k in want.Take(5)) A.Eq(map[k], got[k], k);
            A.False(got.ContainsKey(Key("not there")));
            A.True(fetches < 40, "ranged reads only (" + fetches + ")");
        }

        [Test]
        public static void ArchiveIndexFooterAndLookup()
        {
            var entries = Enumerable.Range(0, 400).Select(i => Tuple.Create(Key("k" + i), (long)(100 + i), (long)(i * 1000))).ToList();
            var ix = Index(entries);
            var tail = ix.Item2.Skip(ix.Item2.Length - 28).ToArray();
            A.True(ArchiveIndex.FooterOk(tail, ix.Item1), "named by its footer");
            A.False(ArchiveIndex.FooterOk(tail, Key("another")), "another name");
            A.False(ArchiveIndex.FooterOk(B("<html><body>Hotel Wi-Fi: sign in to continue</body></html>"), ix.Item1), "a portal page");
            var hit = ArchiveIndex.Find(ix.Item2, new[] { entries[0].Item1, entries[399].Item1, entries[170].Item1, Key("none") });
            A.Eq(3, hit.Count);
            A.Eq(Tuple.Create(499L, 399000L), hit[entries[399].Item1]);
            A.Eq(Tuple.Create(270L, 170000L), hit[entries[170].Item1]);
            using (var t = new Tmp())
            {
                File.WriteAllBytes(t.P(ix.Item1 + ".index"), ix.Item2);
                A.True(ArchiveIndex.FileOk(t.P(ix.Item1 + ".index"), ix.Item1));
                File.WriteAllBytes(t.P("cut.index"), ix.Item2.Take(ix.Item2.Length - 3).ToArray());
                A.False(ArchiveIndex.FileOk(t.P("cut.index"), ix.Item1), "a cut file");
            }
        }

        [Test]
        public static void PatchServerTablesAndConfigs()
        {
            var v = Psv.Row(Psv.Parse(PatchFixtures.Versions), "Region", "us");
            A.Eq("1.60.1.70959", v["VersionsName"]);
            A.Eq(PatchFixtures.Build70959, v["BuildConfig"]);
            A.Eq("1.60.1.70938", Psv.Row(Psv.Parse(PatchFixtures.Versions), "Region", "kr")["VersionsName"], "kr is still on 70938");
            A.Eq("us", Psv.Row(Psv.Parse(PatchFixtures.Versions), "Region", "xx")["Region"], "unknown = us");
            var c = Psv.Row(Psv.Parse(PatchFixtures.Cdns), "Name", "eu");
            A.Eq("level3.blizzard.com", c["Hosts"]);
            A.Eq("tpr/wow", c["Path"]);
            A.Null(Psv.Row(Psv.Parse("<html>portal</html>"), "Region", "us"), "a portal page has no rows");
            var cfg = ConfigFile.Parse("# Build Configuration\n\ninstall = aa bb\ninstall-size = 1 2\nencoding = cc dd\n");
            A.Eq("bb", ConfigFile.Get(cfg, "install", 1));
            A.Eq("cc", ConfigFile.Get(cfg, "encoding"));
            var bytes = B("# CDN Configuration\narchives = x\n");
            A.True(ConfigFile.Ok(bytes, Md5(bytes)));
            A.False(ConfigFile.Ok(B("<html>"), Md5(bytes)), "a sign-in page is not the config");
        }

        const string Header = "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|CDN Servers!STRING:0|Tags!STRING:0|Armadillo!STRING:0|Last Activated!STRING:0|Version!STRING:0|KeyRing!HEX:16|Product!STRING:0";
        const string Retail = "us|1|505802ef9c3ac9cfbaea9006ce9e464c|4831465d4b6a7db3302338ab0f1ac231|||tpr/wow|level3.blizzard.com us.cdn.blizzard.com|http://level3.blizzard.com/?maxhosts=8|" + PatchFixtures.PcTags + "|||12.1.5.70961|3ca57fe7319a297346440e4d2a03a0cd|wow";

        static Dictionary<string, string> Set70959() => new Dictionary<string, string>
        {
            ["Branch"] = "us", ["Build Key"] = PatchFixtures.Build70959, ["CDN Key"] = PatchFixtures.CdnConfig, ["Install Key"] = PatchFixtures.Install70959EKey,
            ["IM Size"] = "23151", ["CDN Path"] = "tpr/wow", ["CDN Hosts"] = "level3.blizzard.com us.cdn.blizzard.com", ["CDN Servers"] = "", ["Version"] = "1.60.1.70959", ["KeyRing"] = "",
        };

        /// <summary>the PC today: only the retail row. The wowf row is added from it (its Tags, not its keys); the retail line stays byte for byte</summary>
        [Test]
        public static void AMissingRowIsAddedFromTheRetailRow()
        {
            foreach (var nl in new[] { "\n", "\r\n" })
            {
                var text = Header + nl + Retail + nl;
                var next = BuildInfoFile.Upsert(text, "wowf", Set70959(), Header, Retail);
                A.NotNull(next);
                A.True(next.StartsWith(text), "the header and the retail row byte for byte");
                var row = BuildInfoFile.Values(Header, BuildInfoFile.RowLine(next, "wowf"));
                A.Eq("1.60.1.70959", row["Version"]);
                A.Eq(PatchFixtures.PcTags, row["Tags"], "the retail row's tags");
                A.Eq("", row["KeyRing"], "never retail's KeyRing");
                A.Eq("", row["Last Activated"]);
                A.Eq("1", row["Active"]);
                A.Eq("http://level3.blizzard.com/?maxhosts=8", row["CDN Servers"], "a blank value never replaces one (the template's servers)");
                A.True(next.EndsWith(nl) && (nl == "\n" ? !next.Contains("\r") : next.Split(new[] { "\r\n" }, StringSplitOptions.None).Length == 4), "its line ending");
                A.Eq(Retail, BuildInfoFile.RowLine(next, "wow"));
            }
        }

        [Test]
        public static void AnExistingRowIsSetAndABlankNeverReplacesAValue()
        {
            var mine = "us|1|" + PatchFixtures.Build70938 + "|" + PatchFixtures.CdnConfig + "|c8becf78b4f1c9e9e355b93beba90236|23151|tpr/wow|level3.blizzard.com|http://s/?x|" + PatchFixtures.PcTags + "|||1.60.1.70938||wowf";
            var text = Header + "\n" + Retail + "\n" + mine + "\n";
            var next = BuildInfoFile.Upsert(text, "wowf", Set70959());
            var row = BuildInfoFile.Values(Header, BuildInfoFile.RowLine(next, "wowf"));
            A.Eq("1.60.1.70959", row["Version"]);
            A.Eq(PatchFixtures.Build70959, row["Build Key"]);
            A.Eq("http://s/?x", row["CDN Servers"], "kept");
            A.Eq(Header + "\n" + Retail + "\n", next.Substring(0, Header.Length + Retail.Length + 2));
            A.Eq(3, BuildInfoFile.Lines(next).Count, "replaced, not added");
            // a saved copy as the template, and the region tag follows the region
            var saved = mine.Replace("US?", "EU?");
            var eu = BuildInfoFile.Upsert(Header + "\n" + Retail + "\n", "wowf", new Dictionary<string, string>(Set70959()) { ["Branch"] = "eu" }, Header, saved);
            var er = BuildInfoFile.Values(Header, BuildInfoFile.RowLine(eu, "wowf"));
            A.True(er["Tags"].Contains("EU?") && !er["Tags"].Contains(" US?"), er["Tags"]);
            A.Eq("http://s/?x", er["CDN Servers"], "the saved copy's own columns");
            // a needed value missing = not written
            A.Null(BuildInfoFile.Upsert(Header + "\n", "wowf", new Dictionary<string, string> { ["Version"] = "1" }));
        }

        [Test]
        public static void TheAgentsLogSaysWhenItWorksOnWowf()
        {
            var now = new DateTime(2026, 11, 18, 13, 25, 0, DateTimeKind.Utc);
            var busy = "[I 2026-11-18 13:21:36.0032] Reservation Created for wowf:OP_UPDATE\n[I 2026-11-18 13:21:36.0032] Queue operation - OP_UPDATE for 'wowf'\n[I 2026-11-18 13:21:36.0032] Active operation nullptr replaced by OP_UPDATE for 'wowf'\n";
            A.True(AgentWatch.LogBusy(busy, "wowf", now), "under way");
            A.False(AgentWatch.LogBusy(busy, "wow", now), "another product");
            A.False(AgentWatch.LogBusy(busy + "[I 2026-11-18 13:21:38.0068] Process finished OP_UPDATE for 'wowf'\n[I 2026-11-18 13:21:38.0068] OP_UPDATE for 'wowf' completed\n", "wowf", now), "finished");
            A.False(AgentWatch.LogBusy(busy + "[W 2026-11-18 13:20:54.0503] Canceling operation OP_UPDATE due to running instance of 'wowf' being detected\n[I 2026-11-18 13:20:55.0868] OP_UPDATE for 'wowf' done Canceling\n", "wowf", now), "cancelled");
            A.False(AgentWatch.LogBusy(busy, "wowf", now.AddHours(2)), "a stale start is an Agent that stopped");
            A.False(AgentWatch.LogBusy("[I 2026-11-18 14:03:31.0389] Concurrent operation OP_VERSION for 'wow' completed\n", "wowf", now));
            using (var t = new Tmp())
            {
                File.WriteAllText(t.P(".build.info"), "x");
                A.True(AgentWatch.RecentlyWritten(t.Dir, DateTime.UtcNow), "just written by someone");
                AgentWatch.Mine(t.P(".build.info"));
                Directory.CreateDirectory(t.P("Data")); // an older folder
                Directory.SetLastWriteTimeUtc(t.P("Data"), DateTime.UtcNow.AddHours(-1));
                A.False(AgentWatch.RecentlyWritten(t.Dir, DateTime.UtcNow), "Desktop's own write is not the Agent's");
                File.SetLastWriteTimeUtc(t.P(".build.info"), DateTime.UtcNow.AddSeconds(-1));
                A.True(AgentWatch.RecentlyWritten(t.Dir, DateTime.UtcNow), "written again since");
                File.SetLastWriteTimeUtc(t.P(".build.info"), DateTime.UtcNow.AddMinutes(-5));
                A.False(AgentWatch.RecentlyWritten(t.Dir, DateTime.UtcNow), "quiet for 5 minutes");
            }
        }
    }
}
