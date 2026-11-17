using System;
using System.Collections.Generic;
using System.Linq;
using DsfDesktop.Sunshine;
using DsfDesktop.Wow;

namespace DsfDesktop.Tests
{
    /// <summary>
    /// Part 2 (the user's pick 2026-11-17 "Sunshine + own virtual screen"): every PC streams our own virtual screen at the Thor's
    /// mode, WoW moved onto it; SudoVDA first, then the Virtual Display Driver, else the real screen with a note; removed 60 s after
    /// the last Thor/stream, never mid-stream; "Stream on its own screen" off = the 0.2.31 headless-only rule. Fake drivers only.
    /// </summary>
    public static class OwnScreenTests
    {
        /// <summary>a driver that records what was asked of it</summary>
        sealed class Fake : IScreenDriver
        {
            public string Name { get; }
            public bool installed = true, usable = true, names = true;
            public string fail;
            public int makes, removes;
            public readonly List<string> log = new List<string>();
            public Fake(string name) { Name = name; }
            public bool Installed() => installed;
            public bool Usable() => usable;
            public string Make(int w, int h, int hz)
            {
                makes++;
                log.Add("make " + w + "x" + h + "@" + hz);
                if (fail != null) return fail;
                Exists = true;
                Mode = (w, h, hz);
                GdiName = names ? @"\\.\DISPLAY" + (20 + makes) : null;
                return null;
            }
            public void Remove(string why) { removes++; log.Add("remove " + why); Exists = false; GdiName = null; Mode = (0, 0, 0); }
            public bool Exists { get; set; }
            public string GdiName { get; set; }
            public (int w, int h, int hz) Mode { get; set; }
        }

        static readonly DateTime T0 = new DateTime(2026, 11, 17, 12, 0, 0, DateTimeKind.Utc);
        static readonly (int, int, int) M120 = (1920, 1080, 120);

        [Test]
        public static void OwnOnMakesOursEvenWithARealScreen()
        {
            // the user's pick: every PC, not only a headless one
            A.Eq(ScreenPlan.Step.MakeOurs, ScreenPlan.Decide(true, true, true, false, true, false), "a real screen and a driver: ours");
            A.Eq(ScreenPlan.Step.Keep, ScreenPlan.Decide(true, true, true, true, true, false), "ours stays beside a real screen");
            A.Eq(ScreenPlan.Step.OfferInstall, ScreenPlan.Decide(true, true, false, false, true, false), "no driver: asked once");
            A.Eq(ScreenPlan.Step.Fallback, ScreenPlan.Decide(true, true, false, false, true, true), "asked before: today's behaviour, with a note");
            A.Eq(ScreenPlan.Step.NeedsInstall, ScreenPlan.Decide(true, false, false, false, true, true), "headless and no driver: as 0.2.31");
            A.Eq(ScreenPlan.Step.None, ScreenPlan.Decide(true, true, true, false, false, false), "no Thor: nothing made");
            A.Eq(ScreenPlan.Step.RemoveOurs, ScreenPlan.Decide(true, true, true, true, false, false), "no Thor: ours goes");
        }

        [Test]
        public static void OwnOffIsThe0231HeadlessRule()
        {
            foreach (var has in new[] { false, true })
            foreach (var drv in new[] { false, true })
            foreach (var ours in new[] { false, true })
            foreach (var thor in new[] { false, true })
            foreach (var off in new[] { false, true })
                A.Eq(ScreenPlan.Decide(has, drv, ours, thor, off), ScreenPlan.Decide(false, has, drv, ours, thor, off),
                     "the old call is own-screen off: " + has + drv + ours + thor + off);
            A.Eq(ScreenPlan.Step.None, ScreenPlan.Decide(false, true, true, false, true, false), "off + a real screen: nothing made");
            A.Eq(ScreenPlan.Step.RemoveOurs, ScreenPlan.Decide(false, true, true, true, true, false), "off + a screen came back: ours goes");
            A.Eq(ScreenPlan.Step.MakeOurs, ScreenPlan.Decide(false, false, true, false, true, false), "off + headless: still made");
        }

        [Test]
        public static void SudoVdaFirstThenTheVirtualDisplayDriver()
        {
            var sudo = new Fake("SudoVDA");
            var vdd = new Fake("Virtual Display Driver");
            var life = new ScreenLife(new List<IScreenDriver> { sudo, vdd });
            A.Null(life.Make(1920, 1080, 120, T0));
            A.Eq("SudoVDA", life.Current.Name);
            A.Eq(0, vdd.makes, "the VDD isn't touched when SudoVDA made it");

            // SudoVDA not installed: the VDD
            sudo = new Fake("SudoVDA") { installed = false };
            vdd = new Fake("Virtual Display Driver");
            life = new ScreenLife(new List<IScreenDriver> { sudo, vdd });
            A.Null(life.Make(1920, 1080, 60, T0));
            A.Eq("Virtual Display Driver", life.Current.Name);
            A.Eq(0, sudo.makes);

            // SudoVDA fails: the VDD next, and the screen is the VDD's
            sudo = new Fake("SudoVDA") { fail = "add: busy" };
            vdd = new Fake("Virtual Display Driver");
            life = new ScreenLife(new List<IScreenDriver> { sudo, vdd });
            A.Null(life.Make(1920, 1080, 60, T0));
            A.Eq("Virtual Display Driver", life.Current.Name);
            A.Eq(@"\\.\DISPLAY21", life.GdiName);
        }

        [Test]
        public static void ThePlayersOwnVddMonitorIsNeverUsed()
        {
            var sudo = new Fake("SudoVDA") { installed = false };
            var vdd = new Fake("Virtual Display Driver") { usable = false };
            var life = new ScreenLife(new List<IScreenDriver> { sudo, vdd });
            var (inst, use) = life.Survey();
            A.True(inst, "a driver is there");
            A.False(use, "but not ours to use");
            var why = life.Make(1920, 1080, 60, T0);
            A.NotNull(why);
            A.True(why.Contains("in use"), why);
            A.Eq(0, vdd.makes, "never sized or attached");
        }

        [Test]
        public static void NoDriverOrAllFail()
        {
            var life = new ScreenLife(new List<IScreenDriver> { new Fake("SudoVDA") { installed = false }, new Fake("Virtual Display Driver") { installed = false } });
            A.Eq("no virtual screen driver", life.Make(1920, 1080, 60, T0));
            A.Eq((false, false), life.Survey());
            var a = new Fake("SudoVDA") { fail = "add: x" };
            var b = new Fake("Virtual Display Driver") { fail = "display settings refused (-1)" };
            life = new ScreenLife(new List<IScreenDriver> { a, b });
            var why = life.Make(1920, 1080, 60, T0);
            A.True(why.Contains("SudoVDA: add: x") && why.Contains("Virtual Display Driver: display settings refused"), why);
            A.False(life.Exists);
            // a screen Windows never named is undone
            var c = new Fake("SudoVDA") { names = false };
            life = new ScreenLife(new List<IScreenDriver> { c });
            A.True(life.Make(1920, 1080, 60, T0).Contains("didn't appear"));
            A.Eq(1, c.removes);
            A.False(life.Exists);
        }

        [Test]
        public static void TheLifecycleWithGrace()
        {
            var sudo = new Fake("SudoVDA");
            var life = new ScreenLife(new List<IScreenDriver> { sudo });
            var t = T0;
            // a Thor connects: make
            bool wants = life.ThorWants(t, true, false);
            A.Eq(ScreenPlan.Step.MakeOurs, life.Decide(t, true, true, true, wants, false, M120, false).step);
            A.Null(life.Make(1920, 1080, 120, t));
            // streaming: keep
            t = t.AddMinutes(10);
            wants = life.ThorWants(t, true, true);
            A.Eq(ScreenPlan.Step.Keep, life.Decide(t, true, true, true, wants, false, M120, true).step);
            // the stream ended and the Thor left: kept through the grace
            t = t.AddSeconds(2);
            wants = life.ThorWants(t, false, false);
            A.True(wants, "inside the 60 s grace");
            A.Eq(ScreenPlan.Step.Keep, life.Decide(t, true, true, true, wants, false, M120, false).step);
            t = t.AddSeconds(57);
            A.Eq(ScreenPlan.Step.Keep, life.Decide(t, true, true, true, life.ThorWants(t, false, false), false, M120, false).step, "59 s");
            // after the grace: removed
            t = t.AddSeconds(2);
            wants = life.ThorWants(t, false, false);
            A.False(wants);
            var d = life.Decide(t, true, true, true, wants, false, M120, false);
            A.Eq(ScreenPlan.Step.RemoveOurs, d.step);
            A.True(d.why.Contains("60 s"), d.why);
            life.Remove(d.why);
            A.Eq(1, sudo.removes);
            A.False(life.Exists);
            A.Eq(ScreenPlan.Step.None, life.Decide(t, true, true, true, wants, false, M120, false).step, "gone: nothing to do");
        }

        [Test]
        public static void NeverRemovedOrRemadeMidStream()
        {
            var sudo = new Fake("SudoVDA");
            var life = new ScreenLife(new List<IScreenDriver> { sudo });
            A.Null(life.Make(1920, 1080, 60, T0));
            // own screen switched off during a stream (a real screen on): kept until it ends
            A.Eq(ScreenPlan.Step.Keep, life.Decide(T0, false, true, true, true, false, (1920, 1080, 60), true).step);
            // the Thor's mode changed mid-stream: kept
            A.Eq(ScreenPlan.Step.Keep, life.Decide(T0, true, true, true, true, false, M120, true).step);
            // idle: the mode change remakes it (removed now, made at the next tick)
            var d = life.Decide(T0, true, true, true, true, false, M120, false);
            A.Eq(ScreenPlan.Step.RemoveOurs, d.step);
            A.Eq("the Thor's mode changed", d.why);
            life.Remove(d.why);
            A.Eq(ScreenPlan.Step.MakeOurs, life.Decide(T0, true, true, true, true, false, M120, false).step);
            A.Null(life.Make(1920, 1080, 120, T0));
            A.Eq((1920, 1080, 120), life.Mode);
            // own screen off, idle, a real screen: ours goes
            A.Eq(ScreenPlan.Step.RemoveOurs, life.Decide(T0, false, true, true, true, false, M120, false).step);
        }

        [Test]
        public static void AStreamOnARealScreenIsNotCut()
        {
            var life = new ScreenLife(new List<IScreenDriver> { new Fake("SudoVDA") });
            // the Thor's stream started before ours could be made: making ours restarts Sunshine, so the real screen stays
            A.Eq(ScreenPlan.Step.Fallback, life.Decide(T0, true, true, true, true, false, M120, true).step);
            // headless: nothing to cut (no picture without ours)
            A.Eq(ScreenPlan.Step.MakeOurs, life.Decide(T0, true, false, true, true, false, M120, true).step);
        }

        [Test]
        public static void AFailedMakeWaitsTwoMinutes()
        {
            var sudo = new Fake("SudoVDA") { fail = "add: x" };
            var life = new ScreenLife(new List<IScreenDriver> { sudo });
            A.NotNull(life.Make(1920, 1080, 60, T0));
            A.Eq(ScreenPlan.Step.Fallback, life.Decide(T0.AddSeconds(30), true, true, true, true, false, M120, false).step, "a real screen: streamed meanwhile");
            A.Eq(ScreenPlan.Step.Wait, life.Decide(T0.AddSeconds(30), true, false, true, true, false, M120, false).step, "headless: wait");
            A.Eq(ScreenPlan.Step.MakeOurs, life.Decide(T0.AddMinutes(2).AddSeconds(1), true, true, true, true, false, M120, false).step, "then again");
        }

        [Test]
        public static void ALeftoverScreenIsAdoptedAndRemoved()
        {
            // a VDD monitor we attached before Desktop closed uncleanly: ours, removed when no Thor wants it
            var vdd = new Fake("Virtual Display Driver") { Exists = true, GdiName = @"\\.\DISPLAY7", Mode = (1920, 1080, 60) };
            var life = new ScreenLife(new List<IScreenDriver> { new Fake("SudoVDA"), vdd });
            A.True(life.Exists);
            A.Eq(@"\\.\DISPLAY7", life.GdiName);
            var d = life.Decide(T0, true, true, true, life.ThorWants(T0, false, false), false, M120, false);
            A.Eq(ScreenPlan.Step.RemoveOurs, d.step);
            life.Remove(d.why);
            A.Eq(1, vdd.removes);
        }

        [Test]
        public static void OursIsNotCountedAsTheScreenThatIsOn()
        {
            static WinDisplay W(string name, string adapter, string id, bool active)
                => new WinDisplay { Name = name, Adapter = adapter, AdapterId = id, Active = active, Kind = Displays.KindOf(adapter, id) };
            var vddOn = W(@"\\.\DISPLAY7", "Virtual Display Driver", @"Root\MttVDD", true);
            var realOff = W(@"\\.\DISPLAY1", "NVIDIA GeForce RTX 4080", @"PCI\VEN_10DE", false);
            var real = W(@"\\.\DISPLAY1", "NVIDIA GeForce RTX 4080", @"PCI\VEN_10DE", true);
            A.True(Displays.HasScreen(new List<WinDisplay> { realOff, vddOn }), "someone's lasting virtual screen counts");
            A.False(Displays.HasScreen(new List<WinDisplay> { realOff, vddOn }, @"\\.\DISPLAY7"), "but not when it is ours");
            A.True(Displays.HasScreen(new List<WinDisplay> { real, vddOn }, @"\\.\DISPLAY7"));
            // the VDD's monitor to use: an off one (ours to attach); an on one only when we attached it or installed the driver
            var vddOff = W(@"\\.\DISPLAY8", "Virtual Display Driver", @"Root\MttVDD", false);
            A.Eq(@"\\.\DISPLAY8", VddScreen.Pick(new List<WinDisplay> { real, vddOn, vddOff }, false, false)?.Name);
            A.Null(VddScreen.Pick(new List<WinDisplay> { real, vddOn }, false, false), "the player's own on monitor: never ours");
            A.Eq(@"\\.\DISPLAY7", VddScreen.Pick(new List<WinDisplay> { real, vddOn }, true, false)?.Name, "attached by us");
            A.Eq(@"\\.\DISPLAY7", VddScreen.Pick(new List<WinDisplay> { real, vddOn }, false, true)?.Name, "installed by us");
            A.Null(VddScreen.Pick(new List<WinDisplay> { real }, false, false), "no VDD");
            A.True(VddScreen.IsVdd(vddOn));
            A.False(VddScreen.IsVdd(W(@"\\.\DISPLAY9", "SudoMaker Virtual Display Adapter", @"Root\SudoMaker\SudoVDA", true)), "never Apollo's");
        }

        [Test]
        public static void TheVddModeNearestTheThors()
        {
            // the driver's default vdd_settings.xml: 3 sizes x (60, 90, 120, 144, 165, 240)
            var dflt = new[] { (1920, 1080), (2560, 1440), (3840, 2160) }.SelectMany(s => new[] { 60, 90, 120, 144, 165, 240 }.Select(r => (s.Item1, s.Item2, r))).ToList();
            A.Eq((1920, 1080, 120), VddModes.Pick(dflt, 1920, 1080, 120), "exact");
            A.Eq((1920, 1080, 60), VddModes.Pick(dflt, 1920, 1080, 60));
            A.Eq((1920, 1080, 90), VddModes.Pick(dflt, 1920, 1080, 100), "highest rate not above");
            A.Eq((1920, 1080, 60), VddModes.Pick(dflt, 1920, 1080, 30), "lowest above when none below");
            A.Eq((1920, 1080, 60), VddModes.Pick(dflt, 1280, 720, 60), "same shape, nearest size");
            A.Eq((1920, 1080, 120), VddModes.Pick(dflt, 1920, 1200, 120), "no same shape: nearest area");
            A.Eq((1280, 720, 60), VddModes.Pick(new List<(int, int, int)>(), 1280, 720, 60), "modes unread: as asked");
        }

        [Test]
        public static void WowComesToOurScreenAndSunshineNeverFollowsBack()
        {
            A.Eq(Displays.Fix.MoveWow, Displays.Decide(@"\\.\DISPLAY1", @"\\.\DISPLAY21", false, true, false, ownScreen: true), "idle, a real monitor WoW could be mapped to: WoW moves");
            A.Eq(Displays.Fix.RecaptureSunshine, Displays.Decide(@"\\.\DISPLAY1", @"\\.\DISPLAY2", false, true, false, ownScreen: false), "without ours: as before");
            A.Eq(Displays.Fix.None, Displays.Decide(@"\\.\DISPLAY21", @"\\.\DISPLAY21", false, true, false, ownScreen: true));
        }

        [Test]
        public static void TheNewLinesAreTerse()
        {
            foreach (var l in new[] { ScreenPlan.MakingOwn, ScreenPlan.FallbackNote, ScreenPlan.FallbackFailed })
                A.True(l.Length <= 48 && !l.EndsWith("."), l);
            A.Eq("Streaming your screen: no virtual screen driver", ScreenPlan.FallbackNote);
            A.Eq("VirtualDrivers.Virtual-Display-Driver", VddInstall.WingetId, "the id in microsoft/winget-pkgs");
        }
    }
}
