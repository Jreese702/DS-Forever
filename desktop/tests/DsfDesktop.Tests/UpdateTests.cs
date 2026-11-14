using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Threading;
using DsfDesktop.Thor;

namespace DsfDesktop.Tests
{
    /// <summary>Desktop's own update (2026-11-13: 0.2.26 downloaded but never went in): versions, checksums, when, and the file swap</summary>
    public static class UpdateTests
    {
        [Test]
        public static void VersionsCompareByNumber()
        {
            A.True(Versions.Compare("0.2.28", "0.2.27") > 0);
            A.True(Versions.Compare("0.2.10", "0.2.9") > 0, "10 > 9, not text order");
            A.True(Versions.Compare("v0.3", "0.2.99") > 0);
            A.Eq(0, Versions.Compare("1.0", "1.0.0"));
            A.True(Versions.Compare("0.2.27", "0.2.28") < 0);
            var r = new Release { DesktopName = "DsForeverDesktop-0.2.28-win-x64.zip" };
            A.True(DesktopUpdate.Newer(r, "0.2.27"));
            A.False(DesktopUpdate.Newer(r, "0.2.28"), "the same");
            A.False(DesktopUpdate.Newer(r, "0.2.30"), "never backwards");
            A.False(DesktopUpdate.Newer(new Release(), "0.2.1"), "no zip");
        }

        [Test]
        public static void ChecksumsDecide()
        {
            using (var t = new Tmp())
            {
                var f = t.P("DsForeverDesktop-0.2.28-win-x64.zip");
                File.WriteAllText(f, "zip bytes");
                var sha = ReleaseFeed.Sha256File(f);
                A.Null(ReleaseFeed.Verify(f, sha + "  DsForeverDesktop-0.2.28-win-x64.zip\n", "DsForeverDesktop-0.2.28-win-x64.zip"));
                A.Null(ReleaseFeed.Verify(f, sha.ToUpperInvariant() + " *DsForeverDesktop-0.2.28-win-x64.zip\r\n", "DsForeverDesktop-0.2.28-win-x64.zip"), "binary form, upper case");
                A.True(ReleaseFeed.Verify(f, new string('0', 64) + "  DsForeverDesktop-0.2.28-win-x64.zip\n", "DsForeverDesktop-0.2.28-win-x64.zip").Contains("mismatch"));
                A.True(ReleaseFeed.Verify(f, sha + "  other.zip\n", "DsForeverDesktop-0.2.28-win-x64.zip").Contains("doesn't list"));
            }
        }

        // ------------------------------------------------------------ a fake release on disk (DSF_UPDATE_FEED's shape)

        static string FakeRelease(Tmp t, string ver, bool badSum = false, bool nested = false)
        {
            var build = t.P("build-" + ver);
            var root = nested ? Path.Combine(build, "DsForeverDesktop") : build;
            Directory.CreateDirectory(Path.Combine(root, "sunshine"));
            File.WriteAllText(Path.Combine(root, "DsForeverDesktop.exe"), "exe " + ver);
            File.WriteAllText(Path.Combine(root, "DsfDesktop.Core.dll"), "core " + ver);
            File.WriteAllText(Path.Combine(root, "sunshine", "sunshine.exe"), "sun " + ver);
            var rel = t.P("rel-" + ver);
            Directory.CreateDirectory(rel);
            var zipName = "DsForeverDesktop-" + ver + "-win-x64.zip";
            var zip = Path.Combine(rel, zipName);
            ZipFile.CreateFromDirectory(build, zip);
            var sha = badSum ? new string('a', 64) : ReleaseFeed.Sha256File(zip);
            File.WriteAllText(Path.Combine(rel, "SHA256SUMS"), sha + "  " + zipName + "\n");
            File.WriteAllText(Path.Combine(rel, "DS-Forever-1.0.31.apk"), "apk");
            Func<string, string> js = s => s.Replace("\\", "\\\\");
            var json = "{\"tag_name\":\"v1.0.31\",\"assets\":["
                + "{\"name\":\"DS-Forever-1.0.31.apk\",\"browser_download_url\":\"" + js(Path.Combine(rel, "DS-Forever-1.0.31.apk")) + "\",\"size\":3},"
                + "{\"name\":\"" + zipName + "\",\"browser_download_url\":\"" + js(zip) + "\",\"size\":" + new FileInfo(zip).Length + "},"
                + "{\"name\":\"SHA256SUMS\",\"browser_download_url\":\"" + js(Path.Combine(rel, "SHA256SUMS")) + "\",\"size\":1}]}";
            var latest = Path.Combine(rel, "latest.json");
            File.WriteAllText(latest, json);
            return latest;
        }

        [Test]
        public static void ANewerVerifiedBuildGetsReady()
        {
            using (var t = new Tmp())
            {
                var u = new DesktopUpdate(t.P("data"), FakeRelease(t, "0.2.28"));
                u.Check("0.2.27", CancellationToken.None).GetAwaiter().GetResult();
                A.True(u.Ready, "ready");
                A.Eq("0.2.28", u.ReadyVersion);
                A.Eq("exe 0.2.28", File.ReadAllText(t.P("data", "update", "next", "DsForeverDesktop.exe")));
                A.True(File.Exists(t.P("data", "update", "next", "sunshine", "sunshine.exe")));

                var same = new DesktopUpdate(t.P("data2"), FakeRelease(t, "0.2.29"));
                same.Check("0.2.29", CancellationToken.None).GetAwaiter().GetResult();
                A.False(same.Ready, "already on it");
            }
        }

        [Test]
        public static void ABadChecksumIsNeverReady()
        {
            using (var t = new Tmp())
            {
                var u = new DesktopUpdate(t.P("data"), FakeRelease(t, "0.2.28", badSum: true));
                u.Check("0.2.27", CancellationToken.None).GetAwaiter().GetResult();
                A.False(u.Ready);
                A.False(Directory.Exists(t.P("data", "update", "next")), "nothing unpacked");
                A.False(File.Exists(t.P("data", "update", "DsForeverDesktop-0.2.28-win-x64.zip")), "nothing kept");
            }
        }

        [Test]
        public static void AZipWithOneTopFolderWorksToo()
        {
            using (var t = new Tmp())
            {
                var u = new DesktopUpdate(t.P("data"), FakeRelease(t, "0.2.28", nested: true));
                u.Check("0.2.27", CancellationToken.None).GetAwaiter().GetResult();
                A.True(u.Ready);
                A.True(File.Exists(t.P("data", "update", "next", "DsForeverDesktop.exe")));
            }
        }

        // ------------------------------------------------------------ when

        [Test]
        public static void TheGateWaitsForQuiet()
        {
            var g = new GateInput { ReadyVersion = "0.2.28", IdleForS = 120 };
            A.Eq((true, "Updating to 0.2.28"), UpdateGate.Decide(g));
            A.Eq((false, ""), UpdateGate.Decide(new GateInput { IdleForS = 999 }), "nothing ready");
            A.Eq((false, "0.2.28 ready: after the stream"), UpdateGate.Decide(new GateInput { ReadyVersion = "0.2.28", Streaming = true, IdleForS = 999 }));
            A.Eq((false, "0.2.28 ready: once WoW closes"), UpdateGate.Decide(new GateInput { ReadyVersion = "0.2.28", WowFromDesktop = true, IdleForS = 999 }));
            A.Eq((false, "0.2.28 ready"), UpdateGate.Decide(new GateInput { ReadyVersion = "0.2.28", IdleForS = 30 }), "a minute of quiet first");
            A.False(UpdateGate.Decide(new GateInput { ReadyVersion = "0.2.28", Writable = "read-only", IdleForS = 999 }).go, "a read-only folder");
            A.True(UpdateGate.Decide(new GateInput { ReadyVersion = "0.2.28", Writable = "read-only" }).line.Contains("Documents"), "with the hint");
            A.False(UpdateGate.Decide(new GateInput { ReadyVersion = "0.2.28", FailedVersion = "0.2.28", FailedAgoS = 60, IdleForS = 999 }).go, "no loop after a failure");
            A.True(UpdateGate.Decide(new GateInput { ReadyVersion = "0.2.28", FailedVersion = "0.2.28", FailedAgoS = 7 * 3600, IdleForS = 999 }).go, "tried again later");
            A.True(UpdateGate.Decide(new GateInput { ReadyVersion = "0.2.29", FailedVersion = "0.2.28", FailedAgoS = 60, IdleForS = 999 }).go, "a newer one goes");
        }

        // ------------------------------------------------------------ the swap

        static SwapOp Op(List<SwapOp> ops, string rel) => ops.FirstOrDefault(o => o.Rel.Equals(rel, StringComparison.OrdinalIgnoreCase));

        [Test]
        public static void ThePlanSwapsShippedFilesAndKeepsData()
        {
            var incoming = new[] { "DsForeverDesktop.exe", "DsfDesktop.Core.dll", "sunshine/sunshine.exe", "sunshine\\config\\apps.json", "companion\\new.lua", "sunshine\\config\\sunshine.conf" };
            var existing = new[] { "DsForeverDesktop.exe", "DsfDesktop.Core.dll", "sunshine\\sunshine.exe", "sunshine\\config\\apps.json", "old.dll", "mine.txt",
                "desktop.log", ".dsf-files.txt", ".dsf-old\\x\\DsForeverDesktop.exe" };
            var manifest = new[] { "DsForeverDesktop.exe", "old.dll", "desktop.log" };
            var ops = SwapPlan.Make(incoming, existing, manifest);
            A.Eq(SwapKind.Replace, Op(ops, "DsForeverDesktop.exe").Kind);
            A.Eq(SwapKind.Replace, Op(ops, "sunshine\\sunshine.exe").Kind, "slashes normalised");
            A.Eq(SwapKind.Keep, Op(ops, "sunshine\\config\\apps.json").Kind, "Sunshine's config is never overwritten");
            A.Eq(SwapKind.Add, Op(ops, "sunshine\\config\\sunshine.conf").Kind, "but a missing one is added");
            A.Eq(SwapKind.Add, Op(ops, "companion\\new.lua").Kind);
            A.Eq(SwapKind.Remove, Op(ops, "old.dll").Kind, "dropped by the release that shipped it");
            A.Null(Op(ops, "mine.txt"), "a file we never shipped stays");
            A.Null(Op(ops, "desktop.log"), "a log is never removed");
            A.True(ops.All(o => !o.Rel.StartsWith(".dsf")), "our own bookkeeping is not swapped");
            A.Eq(SwapKind.Remove, ops.Last().Kind, "removals last");
            A.True(SwapPlan.IsUserData("sunshine\\credentials.json") && SwapPlan.IsUserData("bridge.pfx.key") && SwapPlan.IsUserData("x\\cakey.pem"));
            A.False(SwapPlan.IsUserData("DsfDesktop.Core.dll"));
        }

        static void Fill(string dir, string tag, params string[] rels)
        {
            foreach (var r in rels) { var f = Path.Combine(dir, r); Directory.CreateDirectory(Path.GetDirectoryName(f)); File.WriteAllText(f, tag + " " + r); }
        }

        [Test]
        public static void TheSwapReplacesFilesInUse()
        {
            using (var t = new Tmp())
            {
                var app = t.P("my unzip", "DS Forever Desktop"); var next = t.P("next");
                Fill(app, "old", "DsForeverDesktop.exe", "sunshine\\config\\sunshine_state.json");
                Fill(next, "new", "DsForeverDesktop.exe", "DsfDesktop.Core.dll", "sunshine\\config\\sunshine_state.json");
                // a program running inside the app folder with its working folder there, as the adb server (it outlives Desktop) and a
                // leftover sunshine.exe (cwd = app\sunshine) are: the 0.2.x whole-folder rename fails
                var busy = Path.Combine(app, "platform-tools", "adb.exe");
                Directory.CreateDirectory(Path.GetDirectoryName(busy));
                File.Copy(Path.Combine(Environment.SystemDirectory, "ping.exe"), busy);
                Fill(next, "new", "platform-tools\\adb.exe");
                using (var p = Process.Start(new ProcessStartInfo(busy, "-n 30 127.0.0.1") { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, WorkingDirectory = Path.GetDirectoryName(busy) }))
                {
                    try
                    {
                        Thread.Sleep(300);
                        A.False(p.HasExited, "the stand-in is running");
                        bool renamed = true;
                        try { Directory.Move(app, app + ".old"); Directory.Move(app + ".old", app); } catch (IOException) { renamed = false; } catch (UnauthorizedAccessException) { renamed = false; }
                        A.False(renamed, "Windows won't rename a folder with a running exe in it (why 0.2.26 never went in)");

                        var ops = SwapPlan.Make(SwapPlan.Files(next), SwapPlan.Files(app), SwapPlan.ReadManifest(app));
                        A.Null(SwapPlan.Execute(next, app, ops));
                        A.Eq("new DsForeverDesktop.exe", File.ReadAllText(Path.Combine(app, "DsForeverDesktop.exe")));
                        A.Eq("new DsfDesktop.Core.dll", File.ReadAllText(Path.Combine(app, "DsfDesktop.Core.dll")));
                        A.Eq("new platform-tools\\adb.exe", File.ReadAllText(busy), "the running exe was renamed aside and replaced");
                        A.Eq("old sunshine\\config\\sunshine_state.json", File.ReadAllText(Path.Combine(app, "sunshine", "config", "sunshine_state.json")), "pairings kept");
                        A.True(SwapPlan.ReadManifest(app).Contains("DsfDesktop.Core.dll"), "manifest written");
                    }
                    finally { try { p.Kill(); p.WaitForExit(5000); } catch (Exception) { } }
                }
                SwapPlan.CleanOld(app);
                A.False(Directory.Exists(Path.Combine(app, SwapPlan.OldDir)), "backups cleaned once nothing runs");
            }
        }

        [Test]
        public static void AFailedSwapPutsEverythingBack()
        {
            using (var t = new Tmp())
            {
                var app = t.P("app"); var next = t.P("next");
                Fill(app, "old", "DsForeverDesktop.exe", "DsfDesktop.Core.dll", "z.dll");
                Fill(next, "new", "DsForeverDesktop.exe", "DsfDesktop.Core.dll", "a-new.dll");
                var ops = SwapPlan.Make(SwapPlan.Files(next), SwapPlan.Files(app), new[] { "z.dll" });
                File.Delete(Path.Combine(next, "DsfDesktop.Core.dll"));   // the copy of the second file fails
                A.NotNull(SwapPlan.Execute(next, app, ops), "it says why");
                A.Eq("old DsForeverDesktop.exe", File.ReadAllText(Path.Combine(app, "DsForeverDesktop.exe")));
                A.Eq("old DsfDesktop.Core.dll", File.ReadAllText(Path.Combine(app, "DsfDesktop.Core.dll")));
                A.Eq("old z.dll", File.ReadAllText(Path.Combine(app, "z.dll")));
                A.False(File.Exists(Path.Combine(app, "a-new.dll")), "the added file is gone again");
                A.False(File.Exists(Path.Combine(app, SwapPlan.Manifest)));
            }
        }

        [Test]
        public static void TheHelperSwapsAndRecords()
        {
            using (var t = new Tmp())
            {
                var app = t.P("app"); var next = t.P("data", "update", "next"); var data = t.P("data");
                Fill(app, "old", "DsfDesktop.Core.dll");
                // the "Desktop" the helper starts afterwards: a harmless system exe under our name
                File.Copy(Path.Combine(Environment.SystemDirectory, "hostname.exe"), Path.Combine(app, "DsForeverDesktop.exe"));
                Directory.CreateDirectory(next);
                File.Copy(Path.Combine(Environment.SystemDirectory, "hostname.exe"), Path.Combine(next, "DsForeverDesktop.exe"));
                Fill(next, "new", "DsfDesktop.Core.dll");
                var rc = UpdateHelper.Run(UpdateHelper.Args(0, next, app, data, "0.2.28"));
                A.Eq(0, rc);
                A.Eq("new DsfDesktop.Core.dll", File.ReadAllText(Path.Combine(app, "DsfDesktop.Core.dll")));
                var r = UpdateHelper.ReadResult(Path.Combine(data, "update"));
                A.True(r.HasValue && r.Value.ok && r.Value.version == "0.2.28", "result recorded");
                A.False(Directory.Exists(next), "the unpacked build is cleaned up");
                A.True(File.ReadAllText(Path.Combine(data, "desktop.log")).Contains("update helper: Desktop 0.2.28 is in"), "logged, not silent");
                Thread.Sleep(500);   // the started hostname.exe
            }
        }

        [Test]
        public static void TheHelperQuotesPathsWithSpaces()
        {
            var q = UpdateHelper.Quote(UpdateHelper.Args(12, @"C:\a b\next\", @"C:\Users\x\DS Forever Desktop", @"C:\d", "0.2.28"));
            A.Eq("\"--apply-update\" \"--pid\" \"12\" \"--from\" \"C:\\a b\\next\" \"--to\" \"C:\\Users\\x\\DS Forever Desktop\" \"--data\" \"C:\\d\" \"--version\" \"0.2.28\"", q);
            var back = UpdateHelper.Args(12, @"C:\a b\next", @"C:\Users\x\DS Forever Desktop", @"C:\d", "0.2.28");
            A.Eq(@"C:\Users\x\DS Forever Desktop", UpdateHelper.Arg(back, "--to"));
        }
    }
}
