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
            /// <summary>the mode it really gives (the VDD's nearest offered one); null = as asked</summary>
            public (int, int, int)? gives;
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
                Mode = gives ?? (w, h, hz);
                GdiName = names ? @"\\.\DISPLAY" + (20 + makes) : null;
                return null;
            }
            /// <summary>a removal Windows refuses (a VDD detach); null = removed</summary>
            public string removeFail;
            /// <summary>Resize: null = in place, else refused with this</summary>
            public string resizeFail = "can't";
            public int resizes, retires;
            public string Remove(string why)
            {
                removes++; log.Add("remove " + why);
                if (removeFail != null) return removeFail;
                Exists = false; GdiName = null; Mode = (0, 0, 0); return null;
            }
            public string Resize(int w, int h, int hz)
            {
                resizes++; log.Add("resize " + w + "x" + h + "@" + hz);
                if (resizeFail != null) return resizeFail;
                Mode = gives ?? (w, h, hz);
                return null;
            }
            public void RetireOld(string why) { retires++; OldGdiName = null; }
            public string OldGdiName { get; set; }
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

        /// <summary>
        /// the user's PC 2026-11-17: Apollo's SudoVDA there but disabled in Windows (CM_PROB_DISABLED) read as "no driver", so Desktop
        /// offered to install a third one, in a modal box on the PC during the Thor's sign-in. Now: no offer, one line naming the fix,
        /// and the question (when one is due) waits until no Thor is connected or streaming
        /// </summary>
        [Test]
        public static void ADisabledSudoVdaIsNamedNeverOffered()
        {
            A.True(ScreenPlan.Offered(false, false, true), "disabled SudoVDA: nothing to offer");
            A.False(ScreenPlan.Offered(false, false, false), "no driver at all: still asked once");
            A.True(ScreenPlan.Offered(true, false, false));
            A.Eq(ScreenPlan.Step.Fallback, ScreenPlan.Decide(true, true, false, false, true, ScreenPlan.Offered(false, false, true)), "a real screen is streamed");
            A.Eq(ScreenPlan.Step.NeedsInstall, ScreenPlan.Decide(true, false, false, false, true, ScreenPlan.Offered(false, false, true)), "headless: says so");
            A.Eq((ScreenPlan.EnableSudoVda, (string)null), ScreenPlan.NoDriverLine(true, true), "a real screen: the line, no problem");
            A.Eq((ScreenPlan.EnableSudoVda, ScreenPlan.ProblemScreenDisabled), ScreenPlan.NoDriverLine(false, true), "headless: the Thor's curtain says it");
            A.Eq((ScreenPlan.FallbackNote, (string)null), ScreenPlan.NoDriverLine(true, false), "as before without one");
            A.Eq((ScreenPlan.Install, ScreenPlan.ProblemNeedsScreen), ScreenPlan.NoDriverLine(false, false));
            A.True(ScreenPlan.EnableSudoVda.Length <= 60, "terse");
            A.Eq(ScreenPlan.EnableSudoVda, char.ToUpperInvariant(ScreenPlan.ThorScreenDisabled[0]) + ScreenPlan.ThorScreenDisabled.Substring(1), "an older Thor capitalises the why");
            A.False(ScreenPlan.AskNow(true, true, false), "a Thor connected: not now");
            A.False(ScreenPlan.AskNow(true, false, true), "streaming: not now");
            A.True(ScreenPlan.AskNow(true, false, false), "the Thor gone: asked");
            A.False(ScreenPlan.AskNow(false, false, false));
            A.True(SudoVda.HasId("ROOT\\SudoMaker\\SudoVDA\0\0"), "its hardware id, any case");
            A.False(SudoVda.HasId("root\\parsec\\vdd\0\0"), "Parsec's adapter is not ours to use");
            A.False(SudoVda.HasId(null));
            // read only, on this PC: whatever it finds, it never throws
            var problem = SudoVda.AdapterProblem();
            A.True(problem == null || problem >= 0);
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
            var sudo = new Fake("SudoVDA") { resizeFail = null };
            var life = new ScreenLife(new List<IScreenDriver> { sudo });
            A.Null(life.Make(1920, 1080, 60, T0));
            // own screen switched off during a stream (a real screen on): kept until it ends
            A.Eq(ScreenPlan.Step.Keep, life.Decide(T0, false, true, true, true, false, (1920, 1080, 60), true).step);
            // the Thor's mode changed mid-stream: kept
            A.Eq(ScreenPlan.Step.Keep, life.Decide(T0, true, true, true, true, false, M120, true).step);
            // idle: the mode change remakes it in place (verifier 2026-11-17: RemoveOurs then MakeOurs was two Sunshine restarts and
            // two WoW moves): one MakeOurs, the old screen removed inside it
            var d = life.Decide(T0, true, true, true, true, false, M120, false);
            A.Eq(ScreenPlan.Step.MakeOurs, d.step);
            A.Eq(ScreenLife.ModeChanged, d.why);
            A.Null(life.Make(1920, 1080, 120, T0));
            A.Eq((1920, 1080, 120), life.Mode);
            A.Eq(0, sudo.removes, "never taken away first (a new one beside it, the old one retired once WoW is off it)");
            A.Eq(1, sudo.resizes);
            A.Eq(ScreenPlan.Step.Keep, life.Decide(T0, true, true, true, true, false, M120, false).step, "then it stays");
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
            // the VDD's monitor to use: an off one (ours to attach); an on one only while we have it attached, by its name
            var vddOff = W(@"\\.\DISPLAY8", "Virtual Display Driver", @"Root\MttVDD", false);
            A.Eq(@"\\.\DISPLAY8", VddScreen.Pick(new List<WinDisplay> { real, vddOn, vddOff }, false)?.Name);
            A.Null(VddScreen.Pick(new List<WinDisplay> { real, vddOn }, false), "the player's own on monitor: never ours");
            A.Eq(@"\\.\DISPLAY7", VddScreen.Pick(new List<WinDisplay> { real, vddOn }, true, vddOn.Name)?.Name, "attached by us (by its name)");
            A.Null(VddScreen.Pick(new List<WinDisplay> { real }, false), "no VDD");
            A.True(VddScreen.IsVdd(vddOn));
            A.False(VddScreen.IsVdd(W(@"\\.\DISPLAY9", "SudoMaker Virtual Display Adapter", @"Root\SudoMaker\SudoVDA", true)), "never Apollo's");
        }

        [Test]
        public static void ANearerModeFromTheDriverIsNotRemadeEveryTick()
        {
            // verifier 2026-11-17: the VDD gives 1920x1080@60 for the Thor's 720p (not in vdd_settings.xml); comparing the Thor's mode
            // with the given one removed and re-made ours (each a Sunshine restart) on every idle tick, for ever
            var vdd = new Fake("Virtual Display Driver") { gives = (1920, 1080, 60) };
            var life = new ScreenLife(new List<IScreenDriver> { vdd });
            var want = (1280, 720, 60);
            A.Eq(ScreenPlan.Step.MakeOurs, life.Decide(T0, true, true, true, true, false, want, false).step);
            A.Null(life.Make(1280, 720, 60, T0));
            A.Eq((1920, 1080, 60), life.Mode, "the nearest it offers");
            for (int i = 1; i <= 10; i++)
                A.Eq(ScreenPlan.Step.Keep, life.Decide(T0.AddSeconds(2 * i), true, true, true, true, false, want, false).step, "tick " + i);
            A.Eq(1, vdd.makes);
            A.Eq(0, vdd.removes);
            // the Thor really changed its mode: one remake
            A.Eq(ScreenPlan.Step.MakeOurs, life.Decide(T0.AddMinutes(1), true, true, true, true, false, (1600, 900, 60), false).step);
        }

        [Test]
        public static void ALeftoverOfUnknownModeIsKeptAsItIs()
        {
            // verifier 2026-11-17: an adopted VDD monitor had Mode (0,0,0), so it was removed and re-made at once
            var vdd = new Fake("Virtual Display Driver") { Exists = true, GdiName = @"\\.\DISPLAY7", Mode = (0, 0, 0) };
            var life = new ScreenLife(new List<IScreenDriver> { vdd });
            for (int i = 0; i < 5; i++)
                A.Eq(ScreenPlan.Step.Keep, life.Decide(T0.AddSeconds(2 * i), true, true, true, true, false, M120, false).step, "tick " + i);
            A.Eq(0, vdd.removes);
            // a leftover whose mode is known and differs: one remake, then it settles
            var vdd2 = new Fake("Virtual Display Driver") { Exists = true, GdiName = @"\\.\DISPLAY7", Mode = (1920, 1080, 60), resizeFail = null };
            var life2 = new ScreenLife(new List<IScreenDriver> { vdd2 });
            var d = life2.Decide(T0, true, true, true, true, false, M120, false);
            A.Eq(ScreenPlan.Step.MakeOurs, d.step);
            A.Null(life2.Make(1920, 1080, 120, T0));
            A.Eq(0, vdd2.removes);
            A.Eq(1, vdd2.resizes);
            A.Eq(ScreenPlan.Step.Keep, life2.Decide(T0.AddSeconds(2), true, true, true, true, false, M120, false).step);
        }

        [Test]
        public static void AScreenSunshineDidNotListWaitsTwoMinutes()
        {
            // verifier 2026-11-17: made, not listed by our Sunshine, removed - and made again at the next tick, for ever
            var sudo = new Fake("SudoVDA");
            var life = new ScreenLife(new List<IScreenDriver> { sudo });
            A.Null(life.Make(1920, 1080, 120, T0));
            life.Remove("Sunshine didn't list it");
            life.Failed(T0);
            A.Eq(ScreenPlan.Step.Fallback, life.Decide(T0.AddSeconds(2), true, true, true, true, false, M120, false).step);
            A.Eq(ScreenPlan.Step.Fallback, life.Decide(T0.AddSeconds(119), true, true, true, true, false, M120, false).step);
            A.Eq(ScreenPlan.Step.MakeOurs, life.Decide(T0.AddSeconds(121), true, true, true, true, false, M120, false).step);
            A.Eq(1, sudo.makes);
        }

        [Test]
        public static void OnlyTheVddMonitorWeAttachedIsOurs()
        {
            static WinDisplay W(string name, bool active)
                => new WinDisplay { Name = name, Adapter = "Virtual Display Driver", AdapterId = @"Root\MttVDD", Active = active, Kind = Displays.KindOf("Virtual Display Driver", @"Root\MttVDD") };
            var a = W(@"\\.\DISPLAY7", true);
            var b = W(@"\\.\DISPLAY8", true);
            var all = new List<WinDisplay> { a, b };
            A.Eq(@"\\.\DISPLAY8", VddScreen.Ours(all, true, @"\\.\DISPLAY8")?.Name, "the one we named, not the first on");
            A.Null(VddScreen.Ours(new List<WinDisplay> { a }, true, @"\\.\DISPLAY8"), "ours is off: the player's other one is never taken");
            A.Null(VddScreen.Ours(new List<WinDisplay> { a }, true, null), "no name kept: nothing is ours (the install names its monitor)");
            A.Null(VddScreen.Ours(all, false, @"\\.\DISPLAY7"), "detached by us: any monitor on now is the player's");
            // after our install's monitor was detached, the player turning it on: not ours (the install no longer counts)
            A.Null(VddScreen.Pick(new List<WinDisplay> { a }, false), "never used, sized or detached");
            A.Eq(@"\\.\DISPLAY8", VddScreen.Pick(all, true, @"\\.\DISPLAY8")?.Name);
        }

        [Test]
        public static void ALeftoverAdoptedWhileAThorIsHereIsPointedAt()
        {
            // verifier 2026-11-17: Desktop restarted with our VDD monitor still on and the Thor already connected: Keep, so our
            // Sunshine never captured it and WoW never moved there. Now: MakeOurs on it (no new screen: our Sunshine pointed at it)
            var vdd = new Fake("Virtual Display Driver") { Exists = true, GdiName = @"\\.\DISPLAY7", Mode = (1920, 1080, 120) };
            var life = new ScreenLife(new List<IScreenDriver> { vdd });
            var d = life.Decide(T0, true, true, true, life.ThorWants(T0, true, false), false, M120, false, sunshineOnOurs: false);
            A.Eq(ScreenPlan.Step.MakeOurs, d.step);
            A.Eq(ScreenLife.NotOnOurs, d.why);
            A.Null(life.Make(1920, 1080, 120, T0));
            A.Eq(0, vdd.makes + vdd.removes, "the same screen");
            A.Eq(ScreenPlan.Step.Keep, life.Decide(T0, true, true, true, true, false, M120, false, sunshineOnOurs: true).step);
            A.Eq(ScreenPlan.Step.Keep, life.Decide(T0, true, true, true, true, false, M120, true, sunshineOnOurs: false).step, "never mid-stream");
            // our Sunshine didn't list it: kept, pointed at again after the 2-minute pause, not every tick
            life.Failed(T0);
            A.Eq(ScreenPlan.Step.Keep, life.Decide(T0.AddSeconds(2), true, false, true, true, false, M120, false, sunshineOnOurs: false).step);
            A.Eq(ScreenPlan.Step.MakeOurs, life.Decide(T0.AddSeconds(121), true, false, true, true, false, M120, false, sunshineOnOurs: false).step);
        }

        [Test]
        public static void OnlyTheMonitorOurInstallBroughtIsOurs()
        {
            // verifier 2026-11-17: the flag was set at the player's yes; after a declined admin prompt the first VDD monitor the player
            // put on later was adopted and detached. Now the install's new monitor is named when it appears, and only then
            static WinDisplay W(string name, bool active)
                => new WinDisplay { Name = name, Adapter = "Virtual Display Driver", AdapterId = @"Root\MttVDD", Active = active, Kind = Displays.KindOf("Virtual Display Driver", @"Root\MttVDD") };
            var real = new WinDisplay { Name = @"\\.\DISPLAY1", Adapter = "AMD Radeon RX 7800 XT", AdapterId = @"PCI\VEN_1002", Active = true };
            var none = new List<string>();
            var on = new List<WinDisplay> { real, W(@"\\.\DISPLAY7", true) };
            A.Null(VddInstallWatch.Adopt(T0, T0.AddSeconds(30), null, none, new List<WinDisplay> { real }), "not there yet");
            A.Eq(@"\\.\DISPLAY7", VddInstallWatch.Adopt(T0, T0.AddMinutes(2), null, none, on), "it appeared: ours by name");
            A.Eq(@"\\.\DISPLAY7", VddInstallWatch.Adopt(T0, T0.AddMinutes(2), 0, none, on), "installer done");
            A.Null(VddInstallWatch.Adopt(T0, T0.AddMinutes(2), 1, none, on), "declined (non-zero): nothing is ours");
            A.True(VddInstallWatch.Over(T0, T0.AddMinutes(2), 1));
            A.Null(VddInstallWatch.Adopt(T0, T0.AddMinutes(16), null, none, on), "the player's own install, later: theirs");
            A.True(VddInstallWatch.Over(T0, T0.AddMinutes(16), null));
            A.Null(VddInstallWatch.Adopt(T0, T0.AddMinutes(2), null, new List<string> { @"\\.\DISPLAY7" }, on), "there before: theirs");
            A.Null(VddInstallWatch.Adopt(T0, T0.AddMinutes(2), null, none, new List<WinDisplay> { real, W(@"\\.\DISPLAY7", false) }), "off: not yet");
            A.Null(VddInstallWatch.Adopt(DateTime.MinValue, T0, null, none, on), "no install of ours");
            // and with no name kept, no active VDD monitor is ours
            A.Null(VddScreen.Pick(on, true, null), "attached flag with no name: the active one is never taken");
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

        // ---------------------------------------------------------------- reviewers 2026-11-17: never a loop

        [Test]
        public static void AMakeThatKeepsFailingGivesUpAfterThree()
        {
            // the user's rule: never loop for ever. Before: a failed make only paused 2 minutes, then MakeOurs again, for ever
            var sudo = new Fake("SudoVDA") { fail = "add: x" };
            var life = new ScreenLife(new List<IScreenDriver> { sudo });
            var t = T0;
            for (int i = 1; i <= ScreenLife.MaxFails; i++)
            {
                A.Eq(ScreenPlan.Step.MakeOurs, life.Decide(t, true, true, true, true, false, M120, false).step, "try " + i);
                A.NotNull(life.Make(1920, 1080, 120, t));
                t = t.AddMinutes(2).AddSeconds(1);
            }
            A.True(life.GaveUp);
            for (int i = 0; i < 10; i++, t = t.AddMinutes(3))
            {
                A.Eq(ScreenPlan.Step.GaveUp, life.Decide(t, true, true, true, true, false, M120, false).step, "a real screen: streamed, no more tries");
                A.Eq(ScreenPlan.Step.GaveUp, life.Decide(t, true, false, true, true, false, M120, false).step, "headless: the PC says it needs one");
            }
            A.Eq(ScreenLife.MaxFails, sudo.makes);
            // the Thor reconnects: the tries start over (the 2-minute pause between tries stays)
            life.Reset(byHand: false);
            A.False(life.GaveUp);
            A.Eq(ScreenPlan.Step.MakeOurs, life.Decide(t, true, true, true, true, false, M120, false).step);
            A.NotNull(life.Make(1920, 1080, 120, t));
            A.Eq(ScreenPlan.Step.Fallback, life.Decide(t.AddSeconds(5), true, true, true, true, false, M120, false).step, "reconnects don't skip the pause");
            // the player's Retry / Test: the pause goes too
            life.Reset(byHand: true);
            A.Eq(ScreenPlan.Step.MakeOurs, life.Decide(t.AddSeconds(6), true, true, true, true, false, M120, false).step);
            A.True(ScreenPlan.NeedsVirtual.Length <= 48 && !ScreenPlan.NeedsVirtual.EndsWith("."), ScreenPlan.NeedsVirtual);
        }

        [Test]
        public static void AScreenSunshineNeverListsGivesUpAfterThree()
        {
            // AppController.MakeOurs: made, not listed, removed (a real screen) or kept (headless), Failed - before: every 2 minutes for ever
            var sudo = new Fake("SudoVDA");
            var life = new ScreenLife(new List<IScreenDriver> { sudo });
            var t = T0;
            for (int i = 1; i <= ScreenLife.MaxFails; i++)
            {
                A.Eq(ScreenPlan.Step.MakeOurs, life.Decide(t, true, true, true, true, false, M120, false).step, "try " + i);
                A.Null(life.Make(1920, 1080, 120, t));
                life.Remove("Sunshine didn't list it");
                life.Failed(t);
                t = t.AddMinutes(2).AddSeconds(1);
            }
            A.Eq(ScreenPlan.Step.GaveUp, life.Decide(t.AddHours(1), true, true, true, true, false, M120, false).step);
            A.Eq(ScreenLife.MaxFails, sudo.makes);

            // headless: ours is kept (the only screen) and pointed at again - three times, then left as it is
            var vdd = new Fake("Virtual Display Driver");
            var h = new ScreenLife(new List<IScreenDriver> { vdd });
            A.Null(h.Make(1920, 1080, 120, T0));
            t = T0;
            for (int i = 1; i <= ScreenLife.MaxFails; i++)
            {
                h.Failed(t);
                t = t.AddMinutes(2).AddSeconds(1);
                var d = h.Decide(t, true, false, true, true, false, M120, false, sunshineOnOurs: false);
                if (i < ScreenLife.MaxFails) A.Eq(ScreenLife.NotOnOurs, d.why, "pointed at again " + i);
                else A.Eq(ScreenPlan.Step.GaveUp, d.step, "gave up: kept, and said (verifier 2026-11-17: it stayed 'Ready' with no line)");
            }
            A.Eq(ScreenPlan.Step.GaveUp, h.Decide(t.AddHours(1), true, false, true, true, false, M120, false, sunshineOnOurs: false).step);
            A.Eq(1, vdd.makes, "never made again");
            A.Eq(0, vdd.removes, "kept: the only screen");
            // Retry by hand: pointed at again at once
            h.Reset(byHand: true);
            A.Eq(ScreenLife.NotOnOurs, h.Decide(t.AddHours(1), true, false, true, true, false, M120, false, sunshineOnOurs: false).why);
        }

        [Test]
        public static void ARefusedRemovalIsNotRetriedEveryTick()
        {
            // a VDD detach Windows refused: before, the monitor was adopted again at the next tick and detached again, for ever
            var vdd = new Fake("Virtual Display Driver") { Exists = true, GdiName = @"\\.\DISPLAY7", Mode = (1920, 1080, 60), removeFail = "display settings refused (-1)" };
            var life = new ScreenLife(new List<IScreenDriver> { vdd });
            var t = T0;
            for (int i = 1; i <= ScreenLife.MaxRemoveFails; i++)
            {
                var d = life.Decide(t, true, true, true, false, false, M120, false);
                A.Eq(ScreenPlan.Step.RemoveOurs, d.step, "try " + i);
                A.False(life.Remove(d.why, t));
                for (int s = 2; s < 120; s += 2)
                    A.Eq(ScreenPlan.Step.Keep, life.Decide(t.AddSeconds(s), true, true, true, false, false, M120, false).step, "paused, tick " + s);
                t = t.AddMinutes(2).AddSeconds(1);
            }
            A.True(life.RemoveGaveUp);
            A.Eq(ScreenPlan.Step.Keep, life.Decide(t.AddHours(1), true, true, true, false, false, M120, false).step, "left on, said once");
            A.Eq(ScreenLife.MaxRemoveFails, vdd.removes);
            A.True(life.Exists, "still ours (a Thor that comes uses it)");
            // a removal that works again resets the count
            vdd.removeFail = null;
            life.Reset(byHand: true);
            A.True(life.Remove("again", t));
            A.False(life.Exists);
        }

        [Test]
        public static void NothingIsMadeForAThorThatHasGone()
        {
            // in the 60 s grace ThorWants is true: before, ours was made (or remade at a new mode) for a Thor that had left
            var sudo = new Fake("SudoVDA");
            var life = new ScreenLife(new List<IScreenDriver> { sudo });
            life.ThorWants(T0, true, false);
            var t = T0.AddSeconds(10);
            bool wants = life.ThorWants(t, false, false);
            A.True(wants, "inside the grace");
            var d = life.Decide(t, true, true, true, wants, false, M120, false, thorHere: false);
            A.Eq(ScreenPlan.Step.None, d.step);
            A.Eq(ScreenLife.ThorGone, d.why);
            A.Eq(ScreenPlan.Step.None, life.Decide(t, true, false, true, wants, false, M120, false, thorHere: false).step, "headless too");
            // ours there at another mode, or not pointed at: kept, not remade
            A.Null(life.Make(1920, 1080, 60, t));
            A.Eq(ScreenPlan.Step.Keep, life.Decide(t, true, true, true, wants, false, M120, false, thorHere: false).step);
            A.Eq(ScreenPlan.Step.Keep, life.Decide(t, true, true, true, wants, false, (1920, 1080, 60), false, sunshineOnOurs: false, thorHere: false).step);
            // and removed after the grace as before
            t = T0.AddSeconds(61);
            A.Eq(ScreenPlan.Step.RemoveOurs, life.Decide(t, true, true, true, life.ThorWants(t, false, false), false, M120, false, thorHere: false).step);
        }

        [Test]
        public static void PeekWritesNothing()
        {
            // the Thor's wait word asked Decide, which adopted a leftover and took the Thor's mode as the one ours was made for
            var vdd = new Fake("Virtual Display Driver") { Exists = true, GdiName = @"\\.\DISPLAY7", Mode = (0, 0, 0) };
            var life = new ScreenLife(new List<IScreenDriver> { vdd });
            A.Eq(ScreenPlan.Step.Keep, life.Peek(T0, true, true, true, true, false, M120, false).step);
            A.Null(life.Current, "not adopted by a look");
            A.Eq(ScreenPlan.Step.Keep, life.Peek(T0, true, true, true, true, false, (1280, 720, 60), false).step, "still unknown: kept as it is");
            A.Eq(ScreenPlan.Step.Keep, life.Decide(T0, true, true, true, true, false, M120, false).step);
            A.Eq(M120, life.Asked.Value, "Decide takes it");
            A.Eq(ScreenPlan.Step.MakeOurs, life.Peek(T0, true, true, true, true, false, (1280, 720, 60), false).step);
            A.Eq(M120, life.Asked.Value, "a look leaves it");
            // a failed make's pause and the give-up are read, never counted
            var s = new ScreenLife(new List<IScreenDriver> { new Fake("SudoVDA") { fail = "x" } });
            for (int i = 0; i < 10; i++) s.Peek(T0, true, true, true, true, false, M120, false);
            A.Eq(0, s.Fails);
        }

        [Test]
        public static void ANewModeIsSetWithoutTakingOursAway()
        {
            // reviewers 2026-11-17: remove-then-make moved WoW to the player's screen. A driver that resizes: no removal at all
            var vdd = new Fake("Virtual Display Driver") { resizeFail = null };
            var life = new ScreenLife(new List<IScreenDriver> { vdd });
            A.Null(life.Make(1920, 1080, 60, T0));
            A.Eq(ScreenPlan.Step.MakeOurs, life.Decide(T0, true, true, true, true, false, M120, false).step);
            A.Null(life.Make(1920, 1080, 120, T0));
            A.Eq(1, vdd.makes);
            A.Eq(0, vdd.removes);
            A.Eq(1, vdd.resizes);
            A.Eq((1920, 1080, 120), life.Mode);
            A.Eq(ScreenPlan.Step.Keep, life.Decide(T0, true, true, true, true, false, M120, false).step);
            // one that makes a new screen beside the old one: the old one goes when asked (WoW moved first)
            var sudo = new Fake("SudoVDA") { resizeFail = null };
            var l2 = new ScreenLife(new List<IScreenDriver> { sudo });
            A.Null(l2.Make(1920, 1080, 60, T0));
            A.Null(l2.Make(1920, 1080, 120, T0));
            sudo.OldGdiName = @"\\.\DISPLAY21";      // what SudoVDA's resize leaves beside the new one
            A.Eq(@"\\.\DISPLAY21", l2.OldGdiName);
            l2.RetireOld("WoW is on the new one");
            A.Eq(1, sudo.retires);
            A.Null(l2.OldGdiName);
            // a driver that refuses the new mode: ours stays at its old one (verifier 2026-11-17: it was removed and made again, the
            // detach moving WoW to a real screen first) - a failed try, paused, capped, never removed
            var stuck = new Fake("Virtual Display Driver");
            var l3 = new ScreenLife(new List<IScreenDriver> { stuck });
            A.Null(l3.Make(1920, 1080, 60, T0));
            A.NotNull(l3.Make(1920, 1080, 120, T0));
            A.Eq(1, stuck.makes);
            A.Eq(0, stuck.removes, "never taken away");
            A.True(l3.Exists);
            A.Eq((1920, 1080, 60), l3.Mode, "still streamed at its mode");
            A.Eq(1, l3.Fails);
            A.Eq(ScreenPlan.Step.Keep, l3.Decide(T0.AddMinutes(1), true, true, true, true, false, M120, false).step, "paused");
            var t3 = T0;
            for (int i = 2; i <= ScreenLife.MaxFails; i++) { t3 = t3.AddMinutes(2).AddSeconds(1); l3.Make(1920, 1080, 120, t3); }
            A.True(l3.GaveUp);
            A.Eq(ScreenPlan.Step.Keep, l3.Decide(t3.AddHours(1), true, true, true, true, false, M120, false).step, "kept as it is, no more tries");
            A.Eq(0, stuck.removes);
        }

        // ---------------------------------------------------------------- the VDD with fake Windows calls

        sealed class FakeWin : IVddWindows
        {
            public readonly List<WinDisplay> list = new List<WinDisplay>();
            public bool comesOn = true;
            public string setFail, detachFail;
            public readonly List<string> calls = new List<string>();
            public Func<string> onSet = () => null;
            public (int, int)? at;
            public FakeWin() { list.Add(new WinDisplay { Name = @"\\.\DISPLAY1", Adapter = "NVIDIA", AdapterId = @"PCI\VEN_10DE", Active = true, Primary = true }); list.Add(Vdd(false)); }
            static WinDisplay Vdd(bool on) => new WinDisplay { Name = @"\\.\DISPLAY7", Adapter = "Virtual Display Driver", AdapterId = @"Root\MttVDD", Active = on };
            WinDisplay V => list.First(d => d.Name == @"\\.\DISPLAY7");
            public List<WinDisplay> Displays() => list.Select(d => new WinDisplay { Name = d.Name, Adapter = d.Adapter, AdapterId = d.AdapterId, Active = d.Active, Primary = d.Primary }).ToList();
            public List<(int w, int h, int hz)> Modes(string dev) => new List<(int, int, int)> { (1920, 1080, 60), (1920, 1080, 120), (2560, 1440, 60) };
            public (int w, int h, int hz) Current(string dev) => (1920, 1080, 60);
            public (int x, int y)? Position(string dev) => at;
            public int RightEdge() => 2560;
            public string Set(string dev, int w, int h, int hz, int x, int y)
            {
                calls.Add("set " + w + "x" + h + "@" + hz + " at " + x + "," + y + " " + onSet());
                if (setFail != null) return setFail;
                if (comesOn) V.Active = true;
                return null;
            }
            public string Detach(string dev) { calls.Add("detach"); if (detachFail != null) return detachFail; V.Active = false; return null; }
            public void Sleep(int ms) { }
        }

        [Test]
        public static void AVddMonitorThatNeverComesOnIsDetachedAgain()
        {
            // reviewers 2026-11-17: attached (registry too), not active within ~3 s, "made" with no name - and left attached
            var w = new FakeWin { comesOn = false };
            bool attached = false; string dev = null;
            var vdd = new VddScreen(() => attached, v => attached = v, () => dev, v => dev = v, w);
            A.Eq("it didn't come on", vdd.Make(1920, 1080, 120));
            A.Eq("detach", w.calls.Last(), string.Join(" | ", w.calls));
            A.False(attached, "not ours any more");
            A.Null(dev);
            A.Null(vdd.GdiName);
            // a refused attach: undone the same way
            var r = new FakeWin { setFail = "display settings refused (-2)" };
            attached = false; dev = null;
            var v2 = new VddScreen(() => attached, v => attached = v, () => dev, v => dev = v, r);
            A.Eq("display settings refused (-2)", v2.Make(1920, 1080, 120));
            A.Eq("detach", r.calls.Last());
            A.False(attached);
        }

        [Test]
        public static void TheVddMonitorIsRecordedAsOursBeforeItIsAttached()
        {
            // a crash between the attach and the record left a monitor nobody detached at the next start
            var w = new FakeWin();
            bool attached = false; string dev = null;
            w.onSet = () => attached + " " + dev;
            var vdd = new VddScreen(() => attached, v => attached = v, () => dev, v => dev = v, w);
            A.Null(vdd.Make(1920, 1080, 120));
            A.Eq(@"set 1920x1080@120 at 2560,0 True \\.\DISPLAY7", w.calls[0]);
            A.True(vdd.Exists);
            // the next start (a new VddScreen on the same record): adopted and detached
            var again = new VddScreen(() => attached, v => attached = v, () => dev, v => dev = v, w);
            var life = new ScreenLife(new List<IScreenDriver> { again });
            A.True(life.Exists, "adopted");
            A.True(life.Remove("left from before"));
            A.False(attached);
            A.Eq("detach", w.calls.Last());
        }

        [Test]
        public static void AFailedUndoKeepsTheMonitorOurs()
        {
            // verifier 2026-11-17: a refused detach in the undo still cleared the record - attached, but nobody's for ever
            var w = new FakeWin { comesOn = false, detachFail = "display settings refused (-1)" };
            bool attached = false; string dev = null;
            var now = T0;
            var vdd = new VddScreen(() => attached, v => attached = v, () => dev, v => dev = v, w, () => now);
            A.Eq("it didn't come on", vdd.Make(1920, 1080, 120));
            A.True(attached, "still ours");
            A.Eq(@"\\.\DISPLAY7", dev);
            // not on: the next look detaches it again (registry too) - and once that works it is no longer ours
            A.False(vdd.Exists);
            A.True(attached, "refused again: kept");
            w.detachFail = null;
            A.False(vdd.Exists);
            A.True(attached, "within the pause: not tried again yet");
            A.Eq(2, w.calls.Count(c => c == "detach"));
            now = now + VddScreen.StaleAgain;
            A.False(vdd.Exists);
            A.False(attached);
            A.Null(dev);
            A.Eq(3, w.calls.Count(c => c == "detach"));
            // refused every time: never a loop - after VddScreen.MaxStaleFails the record goes, said once
            var w2 = new FakeWin { detachFail = "refused" };
            bool at2 = true; string d2 = @"\\.\DISPLAY7";
            var t2 = T0;
            var v2 = new VddScreen(() => at2, v => at2 = v, () => d2, v => d2 = v, w2, () => t2);
            // verifier 2026-11-17: Exists runs several times per tick - one tick's looks are one try, not the whole cap
            for (int i = 0; i < 5; i++) A.False(v2.Exists);
            A.Eq(1, w2.calls.Count(c => c == "detach"), "one try per pause");
            A.True(at2, "still ours");
            for (int i = 0; i < VddScreen.MaxStaleFails + 2; i++) { t2 = t2 + VddScreen.StaleAgain; A.False(v2.Exists); }
            A.False(at2);
            A.Eq(VddScreen.MaxStaleFails, w2.calls.Count(c => c == "detach"));
        }

        [Test]
        public static void ACrashBeforeTheMonitorCameOnIsUndoneAtTheNextStart()
        {
            // verifier 2026-11-17: recorded, attached in the registry, Desktop gone before it came on. At the next start the record was
            // wiped without a detach: the registry attachment could come on later as nobody's monitor
            var w = new FakeWin();
            bool attached = true; string dev = @"\\.\DISPLAY7";
            var life = new ScreenLife(new List<IScreenDriver> { new VddScreen(() => attached, v => attached = v, () => dev, v => dev = v, w) });
            A.False(life.Exists, "not on: nothing to adopt");
            A.Eq("detach", w.calls.Single(), "its registry attachment undone");
            A.False(attached);
            A.Null(dev);
            // a recorded name that isn't the VDD's any more: nothing to undo
            var w2 = new FakeWin();
            bool a2 = true; string d2 = @"\\.\DISPLAY9";
            A.False(new VddScreen(() => a2, v => a2 = v, () => d2, v => d2 = v, w2).Exists);
            A.Eq(0, w2.calls.Count);
            A.False(a2);
        }

        [Test]
        public static void TheOldScreenGoesOnlyOnceWowIsOffIt()
        {
            // verifier 2026-11-17: removed after 20 s whatever happened - WoW on it was then put on the player's screen by Windows
            const string old = @"\\.\DISPLAY21", fresh = @"\\.\DISPLAY22";
            A.Eq(OldScreen.Next.Retire, OldScreen.Decide(true, fresh, old, 0));
            A.Eq(OldScreen.Next.Retire, OldScreen.Decide(false, null, old, 0), "no WoW: nothing to move");
            A.Eq(OldScreen.Next.Later, OldScreen.Decide(true, old, old, 0), "still on it: kept");
            A.Eq(OldScreen.Next.Later, OldScreen.Decide(true, null, old, 0), "unreadable: not seen off it");
            A.Eq(OldScreen.Next.Later, OldScreen.Decide(true, old.ToLowerInvariant(), old, OldScreen.MaxTries - 2));
            A.Eq(OldScreen.Next.LeaveOn, OldScreen.Decide(true, old, old, OldScreen.MaxTries - 1), "never a loop");
            A.Eq(OldScreen.Next.Retire, OldScreen.Decide(true, fresh, null, 5), "none: nothing to do");
        }

        [Test]
        public static void ASecondModeChangeWaitsForTheOldScreen()
        {
            // verifier 2026-11-17: a second SudoVDA mode change removed the old screen (RetireLocked "a newer mode") while WoW was still
            // on it. While an old one is up: ours stays at its mode, no resize, nothing removed, not a failed try
            var sudo = new Fake("SudoVDA") { resizeFail = null };
            var life = new ScreenLife(new List<IScreenDriver> { sudo });
            A.Null(life.Make(1920, 1080, 60, T0));
            A.Null(life.Make(1920, 1080, 120, T0));
            A.Eq(1, sudo.resizes);
            sudo.OldGdiName = @"\\.\DISPLAY21";      // WoW not seen off it yet (RetireOldScreen: Later / LeaveOn)
            var m144 = (2560, 1440, 144);
            A.Eq(ScreenPlan.Step.Keep, life.Decide(T0.AddSeconds(30), true, true, true, true, false, m144, false).step, "the old one is still up");
            A.Null(life.Make(2560, 1440, 144, T0.AddSeconds(30)));
            A.Eq(1, sudo.resizes, "no second resize while the old one is up");
            A.Eq(0, sudo.retires);
            A.Eq(0, sudo.removes);
            A.Eq(0, life.Fails, "a wait, not a failed try");
            A.Eq((1920, 1080, 120), life.Mode);
            // the old one retired (WoW seen off it): the new mode now
            life.RetireOld("WoW is on the new one");
            A.Eq(ScreenPlan.Step.MakeOurs, life.Decide(T0.AddSeconds(60), true, true, true, true, false, m144, false).step);
            A.Null(life.Make(2560, 1440, 144, T0.AddSeconds(60)));
            A.Eq(2, sudo.resizes);
            A.Eq((2560, 1440, 144), life.Mode);
        }

        [Test]
        public static void TheVddModeChangesInPlaceAndARefusedDetachStaysOurs()
        {
            var w = new FakeWin();
            bool attached = false; string dev = null;
            var vdd = new VddScreen(() => attached, v => attached = v, () => dev, v => dev = v, w);
            var life = new ScreenLife(new List<IScreenDriver> { vdd });
            A.Null(life.Make(1920, 1080, 60, T0));
            w.at = (2560, 0);
            A.Null(life.Make(1920, 1080, 120, T0), "the Thor's new mode");
            A.Eq(@"set 1920x1080@120 at 2560,0 ", w.calls.Last());
            A.False(w.calls.Contains("detach"), "never taken off the desktop: WoW stays on it");
            A.Eq((1920, 1080, 120), life.Mode);
            A.Eq(@"\\.\DISPLAY7", life.GdiName);
            // a detach Windows refuses: still ours, still on, and said
            w.detachFail = "display settings refused (-1)";
            A.False(life.Remove("no Thor", T0));
            A.True(attached, "still ours");
            A.True(life.Exists);
        }
    }
}
