using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using DsfDesktop.Wow;
using DsfDesktop.Wow.Patch;
using static DsfDesktop.Tests.PatchKit;

namespace DsfDesktop.Tests
{
    /// <summary>
    /// Desktop 0.2.36's self-patcher end to end: an old _forever_ in a TEMP game folder (small fixture files, never the PC's game)
    /// patched from a fake CDN; staging, Cancel, resume, locks, rollback, the journal after a crash, the Agent, space; and PatchFlow
    /// (by itself once per connect, one attempt per Retry, WoW started once after it).
    /// </summary>
    public static class PatchRunTests
    {
        const string Header = "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|CDN Servers!STRING:0|Tags!STRING:0|Armadillo!STRING:0|Last Activated!STRING:0|Version!STRING:0|KeyRing!HEX:16|Product!STRING:0";
        const string Retail = "us|1|505802ef9c3ac9cfbaea9006ce9e464c|4831465d4b6a7db3302338ab0f1ac231|||tpr/wow|level3.blizzard.com us.cdn.blizzard.com|http://level3.blizzard.com/?maxhosts=8|" + PatchFixtures.PcTags + "|||12.1.5.70961|3ca57fe7319a297346440e4d2a03a0cd|wow";

        static byte[] Bytes(string seed, int n) { var r = new Random(seed.GetHashCode()); var b = new byte[n]; r.NextBytes(b); return b; }

        /// <summary>a temp game folder, its fake CDN and the patcher's env</summary>
        sealed class Game : IDisposable
        {
            public readonly Tmp T = new Tmp();
            public readonly FakeCdn Cdn = new FakeCdn();
            public readonly PatchEnv Env;
            public readonly Dictionary<string, byte[]> Old = new Dictionary<string, byte[]>(StringComparer.OrdinalIgnoreCase);
            public readonly Dictionary<string, byte[]> New = new Dictionary<string, byte[]>(StringComparer.OrdinalIgnoreCase);
            public readonly Dictionary<string, string> EKey = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            public readonly List<string> Swaps = new List<string>();
            public string BuildKey, CdnKey, ArchiveA, ArchiveB;
            public string Versions, Cdns;
            public DateTime Clock = new DateTime(2026, 11, 18, 14, 0, 0, DateTimeKind.Utc);
            public string BuildInfoText;
            public string Root => T.Dir;
            public string F(string rel) => Path.Combine(T.Dir, "_forever_", rel);

            public Game(bool withRow = false)
            {
                // the client: changed files (loose on the CDN, in an archive, the same as _retail_'s), one unchanged, one new; a CN Wow.exe
                Old["Utils\\BlizzardBrowser.exe"] = Bytes("bb-old", 9000); New["Utils\\BlizzardBrowser.exe"] = Bytes("bb-new", 9500);
                Old["Utils\\FromRetail.dll"] = Bytes("fr-old", 3000); New["Utils\\FromRetail.dll"] = Bytes("fr-new", 3100);
                Old["Utils\\Same.dll"] = Bytes("same", 2000); New["Utils\\Same.dll"] = Old["Utils\\Same.dll"];
                Old["wow_loader.dll"] = Bytes("wl-old", 60000); New["wow_loader.dll"] = Bytes("wl-new", 64000);
                Old["Wow.exe"] = Bytes("exe-old", 120000); New["Wow.exe"] = Bytes("exe-new", 130000);
                New["libnew.dll"] = Bytes("libnew", 1500);
                var cnExe = Bytes("exe-cn", 140000);
                foreach (var kv in Old) { Directory.CreateDirectory(Path.GetDirectoryName(F(kv.Key))); File.WriteAllBytes(F(kv.Key), kv.Value); }
                Directory.CreateDirectory(T.P("_retail_", "Utils"));
                File.WriteAllBytes(T.P("_retail_", "Utils", "FromRetail.dll"), New["Utils\\FromRetail.dll"]);
                Directory.CreateDirectory(T.P("Data", "data"));
                File.WriteAllBytes(T.P("Data", "data", "data.000"), B("the shared CASC store: never written"));

                var tags = new List<Tuple<string, int>> { T2("Windows", 1), T2("OSX", 1), T2("x86_64", 2), T2("arm64", 2), T2("enUS", 3), T2("deDE", 3), T2("US", 4), T2("CN", 4), T2("speech", 5), T2("text", 5) };
                string[] all = { "Windows", "x86_64", "enUS", "deDE", "US", "CN", "speech", "text" };
                var files = New.Select(kv => Tuple.Create(kv.Key, kv.Value, kv.Key == "Wow.exe" ? all.Where(x => x != "CN").ToArray() : all)).ToList();
                files.Add(Tuple.Create("Wow.exe", cnExe, all.Where(x => x != "US").ToArray()));
                files.Add(Tuple.Create("Wow.app", Bytes("mac", 10), new[] { "OSX", "x86_64", "enUS", "US", "speech", "text" }));
                var install = Install(tags, files);
                var installE = Key("install-e");
                Cdn.Put("data", installE, BlteZ(install, 1000));

                // the files' encoded forms: Wow.exe and wow_loader.dll loose; BlizzardBrowser and libnew in archive A (local index);
                // nothing in B, whose index is not on the PC (it is fetched)
                var enc = new Dictionary<string, string>();
                var archive = new List<byte>();
                var entries = new List<Tuple<string, long, long>>();
                foreach (var kv in New)
                {
                    var blte = BlteZ(kv.Value, 16384);
                    var ek = Md5(blte);
                    EKey[kv.Key] = ek;
                    enc[Md5(kv.Value)] = ek;
                    if (kv.Key == "Wow.exe" || kv.Key == "wow_loader.dll") Cdn.Put("data", ek, blte);
                    else { entries.Add(Tuple.Create(ek, (long)blte.Length, (long)archive.Count)); archive.AddRange(blte); }
                }
                for (int i = 0; i < 200; i++) enc[Key("other-c" + i)] = Key("other-e" + i);
                var encTable = Enc(enc, 1);
                var encE = Key("encoding-e");
                Cdn.Put("data", encE, BlteZ(encTable, 900));
                var ixA = Index(entries);
                ArchiveA = ixA.Item1;
                Cdn.Put("data", ArchiveA, archive.ToArray());
                Directory.CreateDirectory(T.P("Data", "indices"));
                File.WriteAllBytes(T.P("Data", "indices", ArchiveA + ".index"), ixA.Item2);
                var ixB = Index(new[] { Tuple.Create(Key("b1"), 10L, 0L) });
                ArchiveB = ixB.Item1;
                Cdn.Put("data", ArchiveB + ".index", ixB.Item2);

                var bc = B("# Build Configuration\n\ninstall = " + Md5(install) + " " + installE + "\ninstall-size = " + install.Length + " 999\nencoding = " + Md5(encTable) + " " + encE + "\n");
                BuildKey = Md5(bc);
                Cdn.Put("config", BuildKey, bc);
                var cc = B("# CDN Configuration\n\narchives = " + ArchiveA + " " + ArchiveB + "\n");
                CdnKey = Md5(cc);
                Cdn.Put("config", CdnKey, cc);
                Versions = "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|BuildId!DEC:4|VersionsName!String:0\n## seqn = 1\nus|" + BuildKey + "|" + CdnKey + "||70959|1.60.1.70959\n";
                Cdns = "Name!STRING:0|Path!STRING:0|Hosts!STRING:0|Servers!STRING:0\nus|tpr/wow|" + Cdn.Host + "|http://" + Cdn.Host + "/?maxhosts=1\n";

                BuildInfoText = Header + "\n" + Retail + "\n" + (withRow
                    ? "us|1|" + Key("old-bc") + "|" + CdnKey + "|||tpr/wow|" + Cdn.Host + "|http://old/?x|" + PatchFixtures.PcTags + "|||1.60.1.70938||wowf\n" : "");
                File.WriteAllText(T.P(".build.info"), BuildInfoText);

                Env = new PatchEnv
                {
                    Root = T.Dir,
                    FetchText = url => url.EndsWith("/versions") ? Versions : url.EndsWith("/cdns") ? Cdns : null,
                    PatchBase = r => "http://patch.test/" + r + "/wowf",
                    Cdn = (hosts, path) => new CdnClient(hosts, path, idle: TimeSpan.FromSeconds(3), https: false),
                    JournalPath = T.P("desktop", "patch", "journal.json"),
                    SavedRowPath = T.P("desktop", "patch", "wowf_row.json"),
                    FreeBytes = _ => 50L << 30,
                    Sleep = _ => { },
                    Now = () => Clock = Clock.AddSeconds(1),
                };
                Env.Swap = (a, b, c) => { lock (Swaps) Swaps.Add(b.Substring(F("").Length).TrimStart('\\')); PatchEnv.DefaultSwap(a, b, c); };
            }

            static Tuple<string, int> T2(string n, int t) => Tuple.Create(n, t);

            public string Md5Of(string rel) => Hex.Md5File(F(rel));
            public bool AllOld() => Old.All(kv => Md5Of(kv.Key) == Md5(kv.Value)) && !File.Exists(F("libnew.dll"));
            public bool AllNew() => New.All(kv => Md5Of(kv.Key) == Md5(kv.Value));
            public string Row(string product = "wowf") => BuildInfoFile.RowLine(File.ReadAllText(T.P(".build.info")), product);

            public void Dispose() { Cdn.Dispose(); T.Dispose(); }
        }

        /// <summary>THE END TO END (the PC's case today: no wowf row): the old _forever_ becomes the new one, Wow.exe last, the row added</summary>
        [Test]
        public static void AnOldForeverBecomesTheNewOne()
        {
            using (var g = new Game())
            {
                var p = new PcGamePatcher(g.Env);
                var states = new List<PatchState>();
                p.Changed += () => { var s = p.Status.State; if (states.Count == 0 || states.Last() != s) states.Add(s); };
                var r = p.RunNow();
                A.Eq(PatchState.Done, r.State, r.Code + " " + r.Why);
                A.True(g.AllNew(), "every file is the new build's");
                A.Eq("Utils\\BlizzardBrowser.exe,Utils\\FromRetail.dll,libnew.dll,wow_loader.dll,Wow.exe", string.Join(",", g.Swaps), "Utils first, Wow.exe LAST");
                A.Eq(5, r.Files);
                A.Eq(9500L + 3100 + 1500 + 64000 + 130000, r.Total);
                A.Eq("Checking,Downloading,Installing,Done", string.Join(",", states.Distinct()));
                // FromRetail.dll was COPIED from _retail_ (the same content key): never asked of the CDN
                A.Eq(0, g.Cdn.Got(g.EKey["Utils\\FromRetail.dll"]).Count, "copied, not downloaded");
                A.Eq(1, g.Cdn.Got(g.EKey["Wow.exe"]).Count);
                A.True(g.Cdn.Got("/data/" + g.ArchiveA.Substring(0, 2)).Any(x => x.Contains("bytes=")), "an archived file by its range");
                // the row: added from the retail row's tags, the new build's keys; the retail line byte for byte
                var row = BuildInfoFile.Values(Header, g.Row());
                A.Eq("1.60.1.70959", row["Version"]);
                A.Eq(g.BuildKey, row["Build Key"]);
                A.Eq(g.CdnKey, row["CDN Key"]);
                A.Eq(PatchFixtures.PcTags, row["Tags"]);
                A.Eq("http://" + g.Cdn.Host + "/?maxhosts=1", row["CDN Servers"]);
                A.Eq("", row["KeyRing"]);
                A.True(File.ReadAllText(g.T.P(".build.info")).StartsWith(Header + "\n" + Retail + "\n"), "the retail row untouched");
                A.Eq(g.BuildInfoText, File.ReadAllText(g.T.P(".build.info.dsf-bak")), "the old .build.info kept as .dsf-bak");
                // the configs (content-named) and the missing index; the CASC store untouched
                A.True(File.Exists(g.T.P("Data", "config", g.BuildKey.Substring(0, 2), g.BuildKey.Substring(2, 2), g.BuildKey)));
                A.True(ArchiveIndex.FileOk(g.T.P("Data", "indices", g.ArchiveB + ".index"), g.ArchiveB), "the missing index fetched");
                A.Eq(1, Directory.GetFiles(g.T.P("Data", "data")).Length, "Data/data as it was");
                // ONE backup until the first good login, the staging gone
                var bak = g.F(Path.Combine(".dsf-backup", "old"));
                A.Eq(Md5(g.Old["Wow.exe"]), Hex.Md5File(Path.Combine(bak, "Wow.exe")), "the old Wow.exe kept");
                A.False(Directory.Exists(g.F(".dsf-patch")), "staging removed");
                A.Eq("1.60.1.70959", p.AwaitingLogin);
                A.False(p.GoodLogin("1.60.1.70938"), "a login on another build is not this one's");
                A.True(p.GoodLogin("1.60.1.70959"));
                A.False(Directory.Exists(g.F(".dsf-backup")), "the backup goes after the first good login");
                A.Null(p.AwaitingLogin);
                // nothing left to do: a second run changes nothing
                g.Swaps.Clear();
                var again = p.RunNow();
                A.Eq(PatchState.Done, again.State);
                A.Eq(0, g.Swaps.Count, "already current");
            }
        }

        [Test]
        public static void AnInstallsOwnRowIsUpdatedAndTheBackupIsNamedByItsBuild()
        {
            using (var g = new Game(withRow: true))
            {
                var p = new PcGamePatcher(g.Env);
                var r = p.RunNow();
                A.Eq(PatchState.Done, r.State, r.Why);
                A.Eq("1.60.1.70938", r.From);
                A.Eq("1.60.1.70959", r.To);
                var row = BuildInfoFile.Values(Header, g.Row());
                A.Eq("1.60.1.70959", row["Version"]);
                A.Eq("http://" + g.Cdn.Host + "/?maxhosts=1", row["CDN Servers"]);
                A.Eq(3, BuildInfoFile.Lines(File.ReadAllText(g.T.P(".build.info"))).Count, "set, not added");
                A.True(Directory.Exists(g.F(Path.Combine(".dsf-backup", "70938"))));
                // a first start that fails: the old client back (RestoreOld), .build.info too
                A.True(p.RestoreOld("crash"));
                A.True(g.AllOld(), "the old files back");
                A.Eq(g.BuildInfoText, File.ReadAllText(g.T.P(".build.info")));
                A.Eq(PatchState.Failed, p.Status.State);
                A.Eq("new_failed", p.Status.Code);
            }
        }

        /// <summary>Cancel (B) during the download: the old files stay; the next try reuses whole staged files and resumes</summary>
        [Test]
        public static void CancelLeavesTheOldGameAndTheNextTryGoesOn()
        {
            using (var g = new Game())
            {
                var p = new PcGamePatcher(g.Env);
                var cts = new CancellationTokenSource();
                p.Changed += () => { if (p.Status.FileNo == 4) cts.Cancel(); };
                var r = p.RunNow(cts.Token);
                A.Eq(PatchState.Cancelled, r.State);
                A.True(g.AllOld(), "the old game whole");
                A.Eq(g.BuildInfoText, File.ReadAllText(g.T.P(".build.info")), ".build.info untouched");
                A.Eq(0, g.Swaps.Count);
                A.False(Directory.Exists(g.F(".dsf-backup")));
                A.True(File.Exists(g.F(Path.Combine(".dsf-patch", "70959", "Utils", "BlizzardBrowser.exe"))), "the staged files stay");
                int archiveAsks = g.Cdn.Got("/" + g.ArchiveA).Count;
                var again = new PcGamePatcher(g.Env).RunNow();
                A.Eq(PatchState.Done, again.State, again.Why);
                A.True(g.AllNew());
                A.Eq(archiveAsks, g.Cdn.Got("/" + g.ArchiveA).Count, "the whole staged files were used again, not fetched");
            }
        }

        /// <summary>a lost link mid-file: the download goes on from its .part (Range bytes=n-), never from the start</summary>
        [Test]
        public static void ACutDownloadResumesFromItsPart()
        {
            using (var g = new Game())
            {
                var ek = g.EKey["Wow.exe"];
                int calls = 0;
                g.Cdn.Answer = (path, range) =>
                {
                    if (!path.EndsWith(ek) || ++calls > 1) return null;
                    byte[] f; lock (g.Cdn.Files) f = g.Cdn.Files[PatchKit.CdnPath("data", ek)];
                    return new FakeCdn.Reply { Body = f, SendOnly = f.Length / 2 };
                };
                var r = new PcGamePatcher(g.Env).RunNow();
                A.Eq(PatchState.Done, r.State, r.Why);
                var asks = g.Cdn.Got(ek);
                A.Eq(2, asks.Count, string.Join(" / ", asks));
                A.True(asks[1].Contains("bytes=") && !asks[1].Contains("bytes=0-"), "resumed: " + asks[1]);
                A.True(g.AllNew());
            }
        }

        /// <summary>a game file held open: waited for, then "busy"; nothing of the install left</summary>
        [Test]
        public static void ALockedFileWaitsThenFailsAndLeavesTheOldGame()
        {
            using (var g = new Game())
            {
                g.Env.LockWaitS = 3;
                FileStream held = null;
                var p = new PcGamePatcher(g.Env);
                p.Changed += () => { if (p.Status.State == PatchState.Installing && held == null) held = new FileStream(g.F("wow_loader.dll"), FileMode.Open, FileAccess.Read, FileShare.None); };
                try
                {
                    var r = p.RunNow();
                    A.Eq(PatchState.Failed, r.State);
                    A.Eq("busy", r.Code, r.Why);
                }
                finally { held?.Dispose(); }
                A.True(g.AllOld());
                A.Eq(0, g.Swaps.Count);
                A.False(File.Exists(g.Env.JournalPath), "no journal left");
            }
        }

        /// <summary>a failure after the 3rd file: the first three put back, nothing new left behind</summary>
        [Test]
        public static void AFailureAfterTheThirdFileRestoresEverything()
        {
            using (var g = new Game())
            {
                int n = 0;
                g.Env.Swap = (a, b, c) => { if (++n == 4) throw new IOException("disk said no"); PatchEnv.DefaultSwap(a, b, c); };
                var r = new PcGamePatcher(g.Env).RunNow();
                A.Eq(PatchState.Failed, r.State);
                A.True(g.AllOld(), "everything as before");
                A.Eq(g.BuildInfoText, File.ReadAllText(g.T.P(".build.info")));
                A.False(File.Exists(g.Env.JournalPath));
                // a Retry finishes it (the staged files were kept)
                g.Env.Swap = PatchEnv.DefaultSwap;
                A.Eq(PatchState.Done, new PcGamePatcher(g.Env).RunNow().State);
                A.True(g.AllNew());
            }
        }

        static void CopyTree(string from, string to)
        {
            foreach (var d in Directory.GetDirectories(from, "*", SearchOption.AllDirectories)) Directory.CreateDirectory(d.Replace(from, to));
            foreach (var f in Directory.GetFiles(from, "*", SearchOption.AllDirectories)) { Directory.CreateDirectory(Path.GetDirectoryName(f.Replace(from, to))); File.Copy(f, f.Replace(from, to), true); }
        }

        sealed class Crash : Exception { }

        /// <summary>Desktop killed mid-install, at every step: at its next start the journal undoes it (the old client whole)</summary>
        [Test]
        public static void AnInstallCutShortIsUndoneAtTheNextStart()
        {
            for (int at = 1; at <= 5; at++)
            {
                using (var g = new Game())
                using (var snap = new Tmp())
                {
                    int n = 0;
                    // the moment of the "crash": the whole folder and the journal as they are, before swap [at]
                    g.Env.Swap = (a, b, c) =>
                    {
                        if (++n == at) { CopyTree(g.T.Dir, snap.Dir); throw new Crash(); }
                        PatchEnv.DefaultSwap(a, b, c);
                    };
                    A.Eq(PatchState.Failed, new PcGamePatcher(g.Env).RunNow().State);
                    // back to the moment of the crash, the journal with it
                    foreach (var d in Directory.GetDirectories(g.T.Dir)) Directory.Delete(d, true);
                    foreach (var f in Directory.GetFiles(g.T.Dir)) File.Delete(f);
                    CopyTree(snap.Dir, g.T.Dir);
                    A.True(File.Exists(g.Env.JournalPath), "the journal of the cut install (" + at + ")");
                    var why = new PcGamePatcher(g.Env).Recover();
                    A.True(why != null && why.Contains("undone"), why);
                    A.True(g.AllOld(), "the old client whole after a crash before swap " + at);
                    A.Eq(g.BuildInfoText, File.ReadAllText(g.T.P(".build.info")));
                    A.False(File.Exists(g.Env.JournalPath));
                }
            }
            // the crash after .build.info was written, before the journal said "installed": undone too, .build.info back
            using (var g = new Game())
            {
                A.Eq(PatchState.Done, new PcGamePatcher(g.Env).RunNow().State);
                File.WriteAllText(g.Env.JournalPath, File.ReadAllText(g.Env.JournalPath).Replace("\"installed\"", "\"installing\""));
                A.True(new PcGamePatcher(g.Env).Recover().Contains("undone"));
                A.True(g.AllOld());
                A.Eq(g.BuildInfoText, File.ReadAllText(g.T.P(".build.info")));
            }
            // an install waiting for its first login is left as it is
            using (var g = new Game())
            {
                A.Eq(PatchState.Done, new PcGamePatcher(g.Env).RunNow().State);
                A.True(new PcGamePatcher(g.Env).Recover().Contains("first good login"));
                A.True(g.AllNew());
            }
        }

        [Test]
        public static void TheAgentAtWorkIsWaitedForThenRetried()
        {
            using (var g = new Game())
            {
                int asks = 0;
                g.Env.AgentBusy = () => ++asks <= 2 ? "its log has an operation on wowf under way" : null;
                var p = new PcGamePatcher(g.Env);
                var states = new List<PatchState>();
                p.Changed += () => states.Add(p.Status.State);
                A.Eq(PatchState.Done, p.RunNow().State);
                A.True(states.Contains(PatchState.Waiting), "it waited");
            }
            using (var g = new Game())
            {
                g.Env.AgentBusy = () => "it wrote the game's records in the last 120 s";
                g.Env.AgentWaitS = 20;
                var r = new PcGamePatcher(g.Env).RunNow();
                A.Eq(PatchState.Failed, r.State);
                A.Eq("agent_busy", r.Code);
                A.True(g.AllOld());
                A.Eq(0, g.Cdn.Requests.Count, "nothing fetched beside it");
            }
        }

        [Test]
        public static void SpaceWowRunningNoAnswerAndADamagedFileFailCleanly()
        {
            using (var g = new Game())
            {
                g.Env.FreeBytes = _ => 1000;
                var r = new PcGamePatcher(g.Env).RunNow();
                A.Eq("space", r.Code);
                A.True(r.NeedMb >= 1024, "the MB it needs: " + r.NeedMb);
                A.True(g.AllOld());
            }
            using (var g = new Game())
            {
                g.Env.WowRunning = () => true;
                A.Eq("busy", new PcGamePatcher(g.Env).RunNow().Code);
            }
            using (var g = new Game())
            {
                g.Env.FetchText = _ => null;
                A.Eq("no_answer", new PcGamePatcher(g.Env).RunNow().Code);
                g.Env.FetchText = _ => "<html>portal</html>";
                A.Eq("no_answer", new PcGamePatcher(g.Env).RunNow().Code);
            }
            using (var g = new Game())
            {
                // the CDN serves another file under wow_loader.dll's key
                g.Cdn.Put("data", g.EKey["wow_loader.dll"], BlteZ(Bytes("wrong", 64000)));
                var r = new PcGamePatcher(g.Env).RunNow();
                A.Eq("checksum", r.Code, r.Why);
                A.True(g.AllOld());
            }
            using (var g = new Game())
            {
                g.Cdn.Files.Remove(PatchKit.CdnPath("data", g.EKey["Wow.exe"]));
                var r = new PcGamePatcher(g.Env).RunNow();
                A.Eq("missing", r.Code, r.Why);
                A.True(g.AllOld());
            }
        }

        // ---- PatchFlow

        sealed class Flow
        {
            public PatchStatus S = new PatchStatus();
            public GameCheck G = GameCheck.Outdated;
            public int Starts, Dones;
            public PatchFlow Make() => new PatchFlow(() => S, w => { Starts++; S = new PatchStatus { State = PatchState.Checking }; return true; }, () => true, () => G, () => Dones++);
        }

        [Test]
        public static void ThePatchStartsByItselfOncePerConnect()
        {
            var f = new Flow();
            var flow = f.Make();
            flow.Tick(wanted: false);
            A.Eq(0, f.Starts, "no Thor: nothing");
            flow.Tick(wanted: true);
            A.Eq(1, f.Starts, "outdated + a Thor = by itself");
            f.S = new PatchStatus { State = PatchState.Failed, Code = "no_answer" };
            for (int i = 0; i < 20; i++) flow.Tick(true);
            A.Eq(1, f.Starts, "never again by itself (server errors: stop and show)");
            A.True(flow.Retry());
            A.Eq(2, f.Starts, "one attempt per press");
            A.True(flow.Retry(), "a press while it runs is that one");
            A.Eq(2, f.Starts);
            f.S = new PatchStatus { State = PatchState.Cancelled };
            flow.Connect();
            flow.Tick(true);
            A.Eq(3, f.Starts, "a new connect: once by itself again");
            // done: WoW once
            f.S = new PatchStatus { State = PatchState.Done };
            f.G = GameCheck.Current;
            flow.Tick(true); flow.Tick(true);
            A.Eq(1, f.Dones, "WoW started once after it");
            A.False(flow.Retry(), "a current game: the Retry is WoW's");
            f.G = GameCheck.NeedsRepair;
            f.S = new PatchStatus();
            flow.Connect();
            flow.Tick(true);
            A.Eq(4, f.Starts, "a missing install record patches too");
        }

        [Test]
        public static void TheThorsWordsForThePatch()
        {
            A.Null(PatchFlow.Words(new PatchStatus(), false));
            var s = new PatchStatus { State = PatchState.Downloading, From = "1.60.1.70938", To = "1.60.1.70959", Done = 64000000, Total = 127425344, File = "Wow.exe", FileNo = 6, Files = 6, EtaS = 41.4 };
            var w = PatchFlow.Words(s, false);
            A.Eq("downloading", w["state"]);
            A.Eq("70938", w["from"]); A.Eq("70959", w["to"]);
            A.Eq(64000000L, w["done"]); A.Eq(127425344L, w["total"]);
            A.Eq(41L, w["eta_s"]);
            A.Eq("Updating the game 70938 -> 70959 (64 / 127 MB)", PatchFlow.Line(s));
            A.Eq("Blizzard's updater is busy, waiting...", PatchFlow.Line(new PatchStatus { State = PatchState.Waiting, Code = "agent" }));
            var done = new PatchStatus { State = PatchState.Done, From = "1.60.1.70938", To = "1.60.1.70959" };
            A.Null(PatchFlow.Words(done, starting: false), "done and WoW not starting: nothing");
            A.Eq("loading", PatchFlow.Words(done, starting: true)["state"], "the new client streams its data");
            var fail = PatchFlow.Words(new PatchStatus { State = PatchState.Failed, Code = "space", NeedMb = 2048 }, false);
            A.Eq("failed", fail["state"]); A.Eq("space", fail["code"]); A.Eq(2048L, fail["need_mb"]);
            A.False(PatchFlow.Line(s).Contains("PC"), "never \"on the PC\"");
        }

        /// <summary>the supervisor never starts WoW while the PC game needs its patch (the patch, not WoW, goes first)</summary>
        [Test]
        public static void AnOutdatedGameIsNeverStartedWhileItPatches()
        {
            int starts = 0;
            var sv = new WowSupervisor(() => WowScreen.None, () => BnetState.SignedIn, () => null, () => null,
                () => { starts++; return null; }, () => false, () => null, () => null, run: a => a(), game: () => GameCheck.Outdated);
            sv.Connect();
            for (int i = 0; i < 5; i++) sv.Step();
            A.Eq(0, starts);
            A.Eq(WowAction.Outdated, sv.StoppedFor);
        }
    }
}
