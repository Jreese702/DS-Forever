using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using DsfDesktop.Wow;

namespace DsfDesktop.Tests
{
    public static class AddonTests
    {
        /// <summary>a source set (manifest + addons/) with the given files, the manifest made from their real md5s</summary>
        static AddonManifest MakeSource(Tmp t, int set, Dictionary<string, string> files)
        {
            var src = t.P("src", "addons");
            var lines = new List<string> { "set " + set };
            foreach (var f in files)
            {
                var p = Path.Combine(src, f.Key.Replace('/', '\\'));
                Directory.CreateDirectory(Path.GetDirectoryName(p));
                File.WriteAllText(p, f.Value);
                lines.Add(AddonInstaller.Md5Of(p) + "  " + f.Key);
            }
            var text = string.Join("\n", lines) + "\n";
            File.WriteAllText(t.P("src", "manifest.txt"), text);
            return AddonManifest.Parse(text);
        }

        static readonly Dictionary<string, string> SetA = new Dictionary<string, string>
        {
            ["WowCompanion/Core.lua"] = "-- core v1",
            ["WowCompanion/WowCompanion.toc"] = "## Interface: 11500",
            ["WowCompanion/Media/x.tga"] = "tga",
            ["WowCompanionQuests/Quests.lua"] = "-- quests v1",
        };

        [Test]
        public static void ParsesTheApksManifest()
        {
            var m = AddonManifest.Parse("set 239\r\n0b6843b8a43c2e7ed261eff27ae226c3  WowCompanion/ActionBar.lua\r\neecaf1e35adfae10dbd7d92cdbeef360  WowCompanion/Bags.lua\r\nshort\r\n");
            A.Eq(239, m.Set.Value);
            A.Eq(2, m.Files.Count);
            A.Eq("WowCompanion/ActionBar.lua", m.Files[0].Value);
            A.Eq("0b6843b8a43c2e7ed261eff27ae226c3", m.Files[0].Key);
            A.Eq("WowCompanion", m.Folders.Single());
        }

        [Test]
        public static void ManifestCannotEscapeAddOns()
        {
            var m = AddonManifest.Parse("set 1\n0b6843b8a43c2e7ed261eff27ae226c3  ../WTF/Config.wtf\n0b6843b8a43c2e7ed261eff27ae226c3  C:/Windows/x.lua\n");
            A.Eq(0, m.Files.Count);
        }

        [Test]
        public static void TheRealBundledManifestParses()
        {
            var dir = App.AppPaths.FindAddonSource(AppDomain.CurrentDomain.BaseDirectory);
            var f = Path.Combine(dir, "manifest.txt");
            if (!File.Exists(f)) throw new SkipException("no source tree here");
            var m = AddonManifest.Parse(File.ReadAllText(f));
            A.NotNull(m.Set);
            A.True(m.Files.Count > 20);
            A.True(m.Folders.Contains("WowCompanion"));
            // the APK's copy is the set: every listed file is present with its md5
            var bad = m.Files.Where(x => AddonInstaller.Md5Of(Path.Combine(dir, "addons", x.Value)) != x.Key).ToList();
            A.Eq(0, bad.Count, "bundled files matching the manifest");
        }

        [Test]
        public static void CompareStates()
        {
            using (var t = new Tmp())
            {
                var m = MakeSource(t, 239, SetA);
                var addOns = t.P("game", "Interface", "AddOns");
                A.Eq(AddonState.NoGame, AddonInstaller.Compare(m, null).State);
                Directory.CreateDirectory(addOns);
                A.Eq(AddonState.Missing, AddonInstaller.Compare(m, addOns).State);

                A.True(AddonInstaller.Install(m, t.P("src", "addons"), addOns, t.P("bk"), false).StartsWith("add-ons installed"));
                var st = AddonInstaller.Compare(m, addOns);
                A.Eq(AddonState.Current, st.State);
                A.Eq(239, st.GameSet.Value);

                File.WriteAllText(Path.Combine(addOns, "WowCompanion", "Core.lua"), "-- edited by hand");
                A.Eq(AddonState.Differs, AddonInstaller.Compare(m, addOns).State, "the same set stamp: someone else's edit");

                File.WriteAllText(Path.Combine(addOns, "WowCompanion", AddonInstaller.Stamp), "200\n");
                st = AddonInstaller.Compare(m, addOns);
                A.Eq(AddonState.Outdated, st.State);
                A.Eq(1, st.Differ);
            }
        }

        [Test]
        public static void AutoLeavesAForeignSetAloneButForceReplacesIt()
        {
            using (var t = new Tmp())
            {
                var m = MakeSource(t, 239, SetA);
                var addOns = t.P("AddOns");
                // an old install with no stamp (the PC's Sep-25 copy looks like this)
                Directory.CreateDirectory(Path.Combine(addOns, "WowCompanion"));
                Directory.CreateDirectory(Path.Combine(addOns, "WowCompanionQuests"));
                File.WriteAllText(Path.Combine(addOns, "WowCompanion", "Core.lua"), "-- old");
                var line = AddonInstaller.Install(m, t.P("src", "addons"), addOns, t.P("bk"), false);
                A.True(line.Contains("not ours to overwrite"), line);
                A.Eq("-- old", File.ReadAllText(Path.Combine(addOns, "WowCompanion", "Core.lua")));
                line = AddonInstaller.Install(m, t.P("src", "addons"), addOns, t.P("bk"), true);
                A.True(line.StartsWith("add-ons updated"), line);
                A.Eq(AddonState.Current, AddonInstaller.Compare(m, addOns).State);
            }
        }

        [Test]
        public static void UpdateRemovesStaleFilesOnlyInsideOurFolders()
        {
            using (var t = new Tmp())
            {
                var m = MakeSource(t, 239, SetA);
                var addOns = t.P("AddOns");
                AddonInstaller.Install(m, t.P("src", "addons"), addOns, t.P("bk"), false);
                File.WriteAllText(Path.Combine(addOns, "WowCompanion", "Removed.lua"), "-- dropped from the set");
                Directory.CreateDirectory(Path.Combine(addOns, "SomeoneElses"));
                File.WriteAllText(Path.Combine(addOns, "SomeoneElses", "Their.lua"), "-- not ours");
                File.WriteAllText(Path.Combine(addOns, "WowCompanion", AddonInstaller.Stamp), "1\n");
                File.WriteAllText(Path.Combine(addOns, "WowCompanion", "Core.lua"), "-- old");

                var line = AddonInstaller.Install(m, t.P("src", "addons"), addOns, t.P("bk"), false);
                A.True(line.StartsWith("add-ons updated"), line);
                A.False(File.Exists(Path.Combine(addOns, "WowCompanion", "Removed.lua")), "stale file removed");
                A.True(File.Exists(Path.Combine(addOns, "SomeoneElses", "Their.lua")), "other add-ons untouched");
                A.True(Directory.GetDirectories(t.P("bk")).Length == 1, "a backup was made");
                A.True(File.Exists(Path.Combine(Directory.GetDirectories(t.P("bk"))[0], "WowCompanion", "Removed.lua")), "the backup holds the old set");
            }
        }

        [Test]
        public static void DamagedSourceTouchesNothing()
        {
            using (var t = new Tmp())
            {
                var m = MakeSource(t, 239, SetA);
                File.WriteAllText(t.P("src", "addons", "WowCompanion", "Core.lua"), "-- corrupted after the manifest was made");
                var addOns = t.P("AddOns");
                Directory.CreateDirectory(addOns);
                var line = AddonInstaller.Install(m, t.P("src", "addons"), addOns, t.P("bk"), true);
                A.True(line.Contains("damaged"), line);
                A.Eq(0, Directory.GetDirectories(addOns).Length);
            }
        }

        [Test]
        public static void FailureMidCopyRestoresTheOldSet()
        {
            using (var t = new Tmp())
            {
                var m = MakeSource(t, 239, SetA);
                var addOns = t.P("AddOns");
                Directory.CreateDirectory(Path.Combine(addOns, "WowCompanion"));
                Directory.CreateDirectory(Path.Combine(addOns, "WowCompanionQuests"));
                File.WriteAllText(Path.Combine(addOns, "WowCompanion", "Core.lua"), "-- old core");
                File.WriteAllText(Path.Combine(addOns, "WowCompanion", AddonInstaller.Stamp), "100\n");
                // a file held open (readable, so the backup works, but not replaceable) makes the copy fail half way
                var locked = Path.Combine(addOns, "WowCompanionQuests", "Quests.lua");
                File.WriteAllText(locked, "-- old quests");
                using (new FileStream(locked, FileMode.Open, FileAccess.Read, FileShare.Read))
                {
                    var line = AddonInstaller.Install(m, t.P("src", "addons"), addOns, t.P("bk"), false);
                    A.True(line.Contains("NOT installed") && line.Contains("old set is back"), line);
                }
                A.Eq("-- old core", File.ReadAllText(Path.Combine(addOns, "WowCompanion", "Core.lua")), "old set back");
            }
        }

        [Test]
        public static void ConfigCheckOnlyReads()
        {
            using (var t = new Tmp())
            {
                var flavor = t.P("_classic_beta_");
                Directory.CreateDirectory(Path.Combine(flavor, "WTF"));
                var cfg = Path.Combine(flavor, "WTF", "Config.wtf");
                File.WriteAllText(cfg, "SET developerLog \"1\"\r\nSET gxApi \"D3D12\"\r\n");
                var before = File.ReadAllBytes(cfg);
                var n = ConfigCheck.Notices(flavor);
                A.Eq(1, n.Count, "controller mode missing");
                A.True(n[0].Contains("Controller"));
                A.True(before.SequenceEqual(File.ReadAllBytes(cfg)), "Config.wtf untouched");
                File.WriteAllText(cfg, "SET gxApi \"D3D12\"\r\n");
                A.True(ConfigCheck.Notices(flavor).Any(x => x.Contains("log is off")));
            }
        }
    }

    public static class LocatorTests
    {
        const string BuildInfoText =
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|CDN Servers!STRING:0|Tags!STRING:0|Armadillo!STRING:0|Last Activated!STRING:0|Version!STRING:0|KeyRing!HEX:16|Product!STRING:0\n" +
            "us|1|18b4|3023|||tpr/wow|h|s|t|||12.1.5.70529||wow\n" +
            "us|1|ad18|3023|||tpr/wow|h|s|t|||1.60.1.70532||wow_classic_beta\n" +
            "us|1|2582|3023|||tpr/wow|h|s|t|||1.60.1.70532||wowf\n";

        [Test]
        public static void BuildInfoRows()
        {
            var rows = BuildInfo.Parse(BuildInfoText);
            A.Eq(3, rows.Count);
            A.Eq("wowf", rows[2].Product);
            A.Eq("1.60.1.70532", rows[2].Version);
            A.Eq(WowFlavors.Forever, WowFlavors.FolderForProduct(rows[2].Product));
            A.Eq(WowFlavors.Beta, WowFlavors.FolderForProduct(rows[1].Product));
        }

        [Test]
        public static void BetaUntilLaunchThenForever()
        {
            A.Eq(WowFlavors.Beta, WowFlavors.Preferred(new DateTime(2026, 11, 3))[0]);
            A.Eq(WowFlavors.Forever, WowFlavors.Preferred(new DateTime(2026, 11, 4))[0]);
        }

        static KeyValuePair<string, DateTime?> C(string f, DateTime? t) => new KeyValuePair<string, DateTime?>(f, t);

        [Test]
        public static void FollowTheLogWrittenLast()
        {
            var pre = WowFlavors.Preferred(new DateTime(2026, 10, 23));
            var t0 = new DateTime(2026, 10, 23, 10, 0, 0);
            A.Eq(WowFlavors.Forever, WowLocator.ChooseFlavor(new[] { C(WowFlavors.Beta, t0), C(WowFlavors.Forever, t0.AddMinutes(5)) }, pre));
            A.Eq(WowFlavors.Beta, WowLocator.ChooseFlavor(new[] { C(WowFlavors.Beta, t0.AddMinutes(5)), C(WowFlavors.Forever, t0) }, pre));
            A.Eq(WowFlavors.Beta, WowLocator.ChooseFlavor(new[] { C(WowFlavors.Beta, null), C(WowFlavors.Forever, null) }, pre), "no logs: today's preference");
            A.Eq(WowFlavors.Beta, WowLocator.ChooseFlavor(new[] { C(WowFlavors.Beta, t0), C(WowFlavors.Forever, t0) }, pre), "a tie: today's preference");
            A.Eq(WowFlavors.Forever, WowLocator.ChooseFlavor(new[] { C(WowFlavors.Beta, null), C(WowFlavors.Forever, t0) }, pre), "only one log exists");
        }

        [Test]
        public static void DescribesAnInstallAndNormalisesPicks()
        {
            using (var t = new Tmp())
            {
                var root = t.P("World of Warcraft");
                Directory.CreateDirectory(Path.Combine(root, "_classic_beta_", "Interface", "AddOns"));
                Directory.CreateDirectory(Path.Combine(root, "_forever_"));
                Directory.CreateDirectory(Path.Combine(root, "_retail_"));
                File.WriteAllText(Path.Combine(root, "_classic_beta_", "WowB.exe"), "");
                File.WriteAllText(Path.Combine(root, "_forever_", "Wow.exe"), "");
                File.WriteAllText(Path.Combine(root, "_retail_", "Wow.exe"), "");
                File.WriteAllText(Path.Combine(root, ".build.info"), BuildInfoText);

                var inst = WowLocator.Describe(root, new DateTime(2026, 10, 23));
                A.NotNull(inst);
                A.Eq(2, inst.Flavors.Count, "retail is not WoW Forever");
                A.Eq(WowFlavors.Beta, inst.Flavors[0]);
                A.Eq(Path.Combine(root, "_classic_beta_", "Logs", "General.log"), inst.LogPath(WowFlavors.Beta));
                A.Eq(Path.GetFullPath(root), WowLocator.NormalizeRoot(Path.Combine(root, "_classic_beta_", "Interface", "AddOns")));
                A.Null(WowLocator.NormalizeRoot(t.Dir), "a folder above the install is not it");
                A.Null(WowLocator.Describe(t.Dir, DateTime.Now));
            }
        }

        [Test]
        public static void PathsInProductDbBlob()
        {
            var blob = new byte[] { 0x0a, 0x10 }.Concat(System.Text.Encoding.UTF8.GetBytes("C:/Program Files (x86)/World of Warcraft"))
                .Concat(new byte[] { 0x12, 0x03 }).Concat(System.Text.Encoding.UTF8.GetBytes("wowf")).Concat(new byte[] { 0x1a, 0x05 })
                .Concat(System.Text.Encoding.UTF8.GetBytes("D:\\Games\\World of Warcraft")).Concat(new byte[] { 0 }).ToArray();
            var ps = WowLocator.PathsInBlob(blob);
            A.True(ps.Contains("C:/Program Files (x86)/World of Warcraft"), string.Join("|", ps));
            A.True(ps.Contains("D:\\Games\\World of Warcraft"), string.Join("|", ps));
        }
    }
}
