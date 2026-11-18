using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using DsfDesktop.App;
using DsfDesktop.Sunshine;

namespace DsfDesktop.Tests
{
    /// <summary>
    /// The virtual screen QUIET BY DEFAULT (the user's ruling 2026-11-18 "Yes, quiet by default"): a monitor on = the real monitor
    /// streamed with no prompt, no pop-up and no Thor line about drivers; headless = the Thor's one terse line and the setup in
    /// Desktop's window; "Stream on its own screen" an opt-in; a disabled SudoVDA = Turn on through Windows' admin prompt (a fake
    /// runner here: never pnputil, winget, runas or the Run key on the test PC).
    /// </summary>
    public static class ScreenSetupTests
    {
        sealed class FakeRunner : IElevatedRunner
        {
            public readonly List<string> calls = new List<string>();
            public int? exit = 0;
            public string why;
            public int? Run(string exe, string args, out string why)
            {
                calls.Add(exe + " " + args);
                why = this.why;
                return exit;
            }
        }

        static readonly bool[] Both = { false, true };

        [Test]
        public static void AMonitorOnIsQuietByDefault()
        {
            A.False(new AppSettings().OwnScreen, "Stream on its own screen: off by default");
            A.False(ScreenPlan.Looks(false, true), "a monitor on and the setting off: no driver looked for");
            foreach (var surveyed in Both) foreach (var inst in Both) foreach (var use in Both) foreach (var dis in Both) foreach (var ing in Both)
                A.Eq(ScreenSetup.Panel.Hidden, ScreenSetup.For(false, true, surveyed, inst, use, dis, ing), "nothing in the window");
            // the plan: nothing made, nothing said, whatever the drivers
            foreach (var drv in Both) foreach (var thor in Both)
                A.Eq(ScreenPlan.Step.None, ScreenPlan.Decide(false, true, drv, false, thor, true), "nothing to do");
            // no driver line ever reaches the Thor's status line; only a screen being made does
            foreach (var l in new[] { ScreenPlan.FallbackNote, ScreenPlan.FallbackFailed, ScreenPlan.EnableSudoVda, ScreenPlan.Install, ScreenPlan.Failed,
                                      ScreenPlan.NeedsVirtual, AppController.NoScreenLine, "" })
                A.Null(ScreenPlan.ThorStatus(l), "not for the Thor: " + l);
            A.Eq(ScreenPlan.MakingOwn, ScreenPlan.ThorStatus(ScreenPlan.MakingOwn));
            A.Eq(ScreenPlan.Ready, ScreenPlan.ThorStatus(ScreenPlan.Ready));
            // the controller as it starts on such a PC: no panel, no problem word, no Thor line
            using (var t = new Tmp())
            {
                var c = Controller(t, null);
                A.Eq(ScreenSetup.Panel.Hidden, c.SetupPanel);
                A.Null(c.ScreenProblem);
                A.Eq("", c.HostWhy);
                A.Eq("", c.SetupResult);
            }
        }

        [Test]
        public static void HeadlessGetsTheThorsOneLineAndTheWindowsSetup()
        {
            A.True(ScreenPlan.Looks(false, false), "no screen on: looked for with the setting off");
            A.Eq(ScreenSetup.Panel.Checking, ScreenSetup.For(false, false, false, false, false, false, false));
            A.Eq(ScreenSetup.Panel.Offer, ScreenSetup.For(false, false, true, false, false, false, false), "no driver: the install offered in the window");
            A.Eq(ScreenSetup.Panel.TurnOn, ScreenSetup.For(false, false, true, true, false, true, false), "SudoVDA turned off: Turn on, no second driver");
            A.Eq(ScreenSetup.Panel.Installing, ScreenSetup.For(false, false, true, false, false, false, true));
            A.Eq(ScreenSetup.Panel.Ready, ScreenSetup.For(false, false, true, true, true, false, false), "ready: the headless check");
            A.Eq(ScreenSetup.Panel.Unusable, ScreenSetup.For(false, false, true, true, false, false, false));
            A.Eq("No monitor is on", ScreenSetup.Why(false));
            // the plan: headless and no driver says so (never the old one-time question)
            A.Eq(ScreenPlan.Step.NeedsInstall, ScreenPlan.Decide(false, false, false, false, true, true));
            A.Eq(ScreenPlan.Step.MakeOurs, ScreenPlan.Decide(false, false, true, false, true, true), "a driver: ours is made");
            // every headless screen problem is ONE word for the Thor, its line "set it up in Desktop"
            foreach (var w in new[] { ScreenPlan.ProblemNeedsScreen, ScreenPlan.ProblemNoScreen, ScreenPlan.ProblemScreenDisabled })
                A.Eq(ScreenPlan.ProblemNeedsScreen, ScreenPlan.ThorWord(w), w);
            A.Eq("ports_taken", ScreenPlan.ThorWord("ports_taken"), "other words as they are");
            A.Null(ScreenPlan.ThorWord(null));
            A.Eq("pc needs a virtual screen: set it up in Desktop", ScreenPlan.ThorNeedsScreen);
            A.False(ScreenPlan.ThorNeedsScreen.Contains("Device Manager"), "never asks for what the Thor can't do");
            A.Eq(ScreenPlan.ProblemScreenDisabled, ScreenPlan.NoDriverLine(false, true).problem, "the window still knows which fix");
            // the notes the window shows: the offer says the screen stays; the sign-in is only a note
            A.True(ScreenSetup.OfferNote.Contains("Windows always shows"));
            A.True(ScreenSetup.SignInNote.Contains("sign in"));
            A.Eq("Start Desktop when I sign in", ScreenSetup.StartAtSignIn);
            A.Eq("Test passed: a virtual screen was made and removed", ScreenSetup.TestLine(null));
            A.Eq("Test failed: add: no", ScreenSetup.TestLine("add: no"));
        }

        [Test]
        public static void StreamOnItsOwnScreenIsAnOptIn()
        {
            using (var t = new Tmp())
            {
                // 0.2.32-0.2.36 wrote its default "on" into every file: read as off; the player's pick from 0.2.37 on is kept
                File.WriteAllText(t.P("a.json"), "{\"OwnScreen\":true,\"VirtualScreenOffered\":true}");
                A.False(AppSettings.Load(t.P("a.json")).OwnScreen, "an old default on: off");
                File.WriteAllText(t.P("b.json"), "{\"OwnScreen\":true,\"OwnScreenChosen\":true}");
                A.True(AppSettings.Load(t.P("b.json")).OwnScreen, "chosen: kept");
                File.WriteAllText(t.P("c.json"), "{\"OwnScreen\":false}");
                A.False(AppSettings.Load(t.P("c.json")).OwnScreen);

                var c = Controller(t, "{\"OwnScreen\":true}");
                A.False(c.Settings.OwnScreen, "an existing player's old default: off");
                A.Eq(ScreenSetup.Panel.Hidden, c.SetupPanel);
                c.SetOwnScreen(true);
                A.True(c.Settings.OwnScreen && c.Settings.OwnScreenChosen);
                A.True(AppSettings.Load(c.Paths.Settings).OwnScreen, "saved as the player's pick");
                A.Eq(ScreenSetup.Panel.Checking, c.SetupPanel, "on: the setup shows, the drivers looked for");
                c.SetOwnScreen(false);
                A.Eq(ScreenSetup.Panel.Hidden, c.SetupPanel);
                A.False(AppSettings.Load(c.Paths.Settings).OwnScreen);
            }
            // on with a monitor: no driver = the real screen streamed, said in the window only; never the old question
            A.Eq(ScreenSetup.Panel.Offer, ScreenSetup.For(true, true, true, false, false, false, false));
            A.Eq(ScreenPlan.Step.Fallback, ScreenPlan.Decide(true, true, false, false, true, true));
            A.Null(ScreenPlan.ThorStatus(ScreenPlan.NoDriverLine(true, false).line), "the Thor isn't told");
            foreach (var on in Both) foreach (var has in Both) foreach (var drv in Both) foreach (var ours in Both) foreach (var thor in Both)
                A.True(ScreenPlan.Decide(on, has, drv, ours, thor, true) != ScreenPlan.Step.OfferInstall, "no question any more");
            A.Eq("Stream on its own screen is on", ScreenSetup.Why(true));
        }

        [Test]
        public static void ADisabledSudoVdaIsTurnedOnThroughWindowsAdminPrompt()
        {
            var r = new FakeRunner();
            A.Null(SudoVdaTurnOn.Run(() => @"ROOT\DISPLAY\0000", r, "pnputil.exe"));
            A.Eq("pnputil.exe /enable-device \"ROOT\\DISPLAY\\0000\"", r.calls.Single());
            // the id is the one Windows has (found by the hardware id), never a fixed 0000
            A.Null(SudoVdaTurnOn.Run(() => @"ROOT\DISPLAY\0003", r, "pnputil.exe"));
            A.Eq("pnputil.exe /enable-device \"ROOT\\DISPLAY\\0003\"", r.calls.Last());
            int n = r.calls.Count;
            A.Eq(SudoVdaTurnOn.NotFound, SudoVdaTurnOn.Run(() => null, r, "pnputil.exe"), "no adapter");
            A.Eq("its device id looks wrong", SudoVdaTurnOn.Run(() => "ROOT\\DISPLAY\\0000\" & calc", r, "pnputil.exe"), "nothing breaks out of the quotes");
            A.Eq(SudoVdaTurnOn.NotFound, SudoVdaTurnOn.Run(() => throw new InvalidOperationException(), r, "pnputil.exe"));
            A.Eq(n, r.calls.Count, "none of those ran anything");
            r.exit = null; r.why = "the admin prompt was declined";
            A.Eq("the admin prompt was declined", SudoVdaTurnOn.Run(() => @"ROOT\DISPLAY\0000", r, "pnputil.exe"));
            r.exit = 3010; r.why = null;
            A.Eq(SudoVdaTurnOn.Restart, SudoVdaTurnOn.Run(() => @"ROOT\DISPLAY\0000", r, "pnputil.exe"));
            r.exit = 5;
            A.Eq("pnputil said 5", SudoVdaTurnOn.Run(() => @"ROOT\DISPLAY\0000", r, "pnputil.exe"));
            A.True(SudoVdaTurnOn.SafeId(@"ROOT\DISPLAY\0000") && SudoVdaTurnOn.SafeId(@"PCI\VEN_10DE&DEV_2204\4&1A2B"));
            A.False(SudoVdaTurnOn.SafeId("") || SudoVdaTurnOn.SafeId("0000") || SudoVdaTurnOn.SafeId(@"ROOT\DISPLAY\0000 /remove-device"));
            A.True(Path.IsPathRooted(SudoVdaTurnOn.Exe()) && SudoVdaTurnOn.Exe().EndsWith("pnputil.exe", StringComparison.OrdinalIgnoreCase));
            // the window's button through the controller: the fake runner, only when clicked
            using (var t = new Tmp())
            {
                var c = Controller(t, null);
                var f = new FakeRunner();
                c.Elevated = f;
                c.SudoVdaId = () => @"ROOT\DISPLAY\0001";
                A.Eq(0, f.calls.Count, "nothing ran before the click");
                A.Null(c.TurnOnSudoVda());
                A.Eq(1, f.calls.Count);
                A.True(f.calls[0].EndsWith("/enable-device \"ROOT\\DISPLAY\\0001\""));
                A.Eq("SudoMaker display adapter turned on", c.SetupResult);
                f.exit = null; f.why = "the admin prompt was declined";
                A.Eq("the admin prompt was declined", c.TurnOnSudoVda());
                A.Eq("Not turned on: the admin prompt was declined", c.SetupResult);
            }
        }

        /// <summary>the start's "neighbours:" line said "SudoVDA not installed" for the user's disabled adapter (2026-11-17)</summary>
        [Test]
        public static void TheNeighboursLineNamesADisabledAdapter()
        {
            A.Eq("installed", ScreenPlan.SudoVdaWord(true, 0));
            A.Eq("installed, turned off in Windows", ScreenPlan.SudoVdaWord(false, SudoVda.ProblemDisabled));
            A.Eq("installed, device problem 10", ScreenPlan.SudoVdaWord(false, 10));
            A.Eq("installed, no control device", ScreenPlan.SudoVdaWord(false, 0));
            A.Eq("not installed", ScreenPlan.SudoVdaWord(false, null));
            // read only on this PC: whatever it finds, it never throws
            var id = SudoVda.AdapterInstanceId();
            A.True(id == null || SudoVdaTurnOn.SafeId(id), "a found id is one pnputil can take");
        }

        /// <summary>a controller in a temp folder, never started (no Sunshine, no bridge, no displays touched)</summary>
        static AppController Controller(Tmp t, string settingsJson)
        {
            var paths = new AppPaths
            {
                Exe = typeof(ScreenSetupTests).Assembly.Location,
                AppDir = AppDomain.CurrentDomain.BaseDirectory,
                DataDir = t.P("data"),
                SunshineExeDir = t.P("no-sunshine"),
                AddonSource = t.P("no-addons"),
            };
            Directory.CreateDirectory(paths.DataDir);
            if (settingsJson != null) File.WriteAllText(paths.Settings, settingsJson);
            return new AppController(paths);
        }
    }
}
