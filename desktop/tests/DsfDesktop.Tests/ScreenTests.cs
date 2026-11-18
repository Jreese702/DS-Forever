using System;
using System.Collections.Generic;
using System.Linq;
using DsfDesktop.Sunshine;
using DsfDesktop.Wow;

namespace DsfDesktop.Tests
{
    /// <summary>
    /// F3 / F6 (2026-11-17): a headless PC whose only screen was Apollo's per-session SudoVDA display. Virtual displays are told
    /// apart, never remembered, a gone one is cleared, and our own virtual screen is made only when no screen is on. No drivers here.
    /// </summary>
    public static class ScreenTests
    {
        static WinDisplay W(string name, string adapter, string id, bool active = true, bool primary = false)
            => new WinDisplay { Name = name, Adapter = adapter, AdapterId = id, Active = active, Primary = primary, Kind = Displays.KindOf(adapter, id) };

        static readonly WinDisplay Real = W(@"\\.\DISPLAY1", "NVIDIA GeForce RTX 4080", @"PCI\VEN_10DE&DEV_2704", primary: true);
        static readonly WinDisplay RealOff = W(@"\\.\DISPLAY1", "NVIDIA GeForce RTX 4080", @"PCI\VEN_10DE&DEV_2704", active: false);
        static readonly WinDisplay Apollo = W(@"\\.\DISPLAY9", "SudoMaker Virtual Display Adapter", @"Root\SudoMaker\SudoVDA", primary: true);
        static readonly WinDisplay Vdd = W(@"\\.\DISPLAY7", "Virtual Display Driver", @"Root\MttVDD");

        static SunshineDisplay S(string id, string name, bool active = true, bool primary = false, string friendly = "Monitor")
            => new SunshineDisplay { DeviceId = id, DisplayName = name, Active = active, Primary = primary, Friendly = friendly };

        [Test]
        public static void DisplayKinds()
        {
            A.Eq(DisplayKind.Real, Displays.KindOf("NVIDIA GeForce RTX 4080", @"PCI\VEN_10DE&DEV_2704"));
            A.Eq(DisplayKind.Real, Displays.KindOf("DisplayLink USB Device", @"USB\VID_17E9"), "a DisplayLink dock is a real monitor");
            A.Eq(DisplayKind.Real, Displays.KindOf("Microsoft Basic Display Adapter", @"PCI\VEN_1002"));
            A.Eq(DisplayKind.VirtualSession, Displays.KindOf("SudoMaker Virtual Display Adapter", @"Root\SudoMaker\SudoVDA"), "Apollo's");
            A.Eq(DisplayKind.VirtualSession, Displays.KindOf("Parsec Virtual Display Adapter", @"Root\Parsec\VDA"));
            A.Eq(DisplayKind.VirtualPersistent, Displays.KindOf("Virtual Display Driver", @"Root\MttVDD"));
            A.Eq(DisplayKind.VirtualPersistent, Displays.KindOf("Some IddCx Adapter", @"ROOT\DISPLAY\0000"), "any ROOT\\ software adapter");
            A.Eq(DisplayKind.Real, Displays.KindOf(null, null));
        }

        [Test]
        public static void SunshineDisplaysAreMarkedFromWindows()
        {
            // Apollo names its screen after the client ("Thor"): the friendly name alone doesn't tell, Windows' adapter does
            var sun = new List<SunshineDisplay> { S("{a}", @"\\.\DISPLAY1", friendly: "PG32UCDP"), S("{b}", @"\\.\DISPLAY9", primary: true, friendly: "Thor") };
            Displays.MarkVirtual(sun, new List<WinDisplay> { Real, Apollo });
            A.False(sun[0].Virtual);
            A.True(sun[1].Virtual, "Apollo's SudoVDA screen");
            A.False(Displays.MayRemember(sun[1]), "never remembered");
            A.True(Displays.MayRemember(sun[0]));
            A.False(Displays.MayRemember(null));
            // a SudoVDA friendly name is enough even without Windows' list
            var log = "[2026-11-16 10:00:00.000]: Info: Currently available display devices:\n" +
                      "[{\"device_id\":\"{c}\",\"display_name\":\"\\\\\\\\.\\\\DISPLAY9\",\"friendly_name\":\"SudoVDA\",\"info\":{\"primary\":true}}," +
                      "{\"device_id\":\"{d}\",\"display_name\":\"\\\\\\\\.\\\\DISPLAY1\",\"friendly_name\":\"DELL\",\"info\":null}]\n";
            var parsed = Displays.ParseSunshineLog(log);
            A.Eq(2, parsed.Count);
            A.True(parsed[0].Virtual && parsed[0].Active && parsed[0].Primary);
            A.False(parsed[1].Active, "info null: not part of the desktop");
        }

        [Test]
        public static void WoWIsNeverFollowedOntoAVirtualDisplay()
        {
            A.Eq(Displays.Fix.MoveWow, Displays.Decide(@"\\.\DISPLAY9", @"\\.\DISPLAY1", false, false, wowOnVirtual: true), "idle: WoW comes back");
            A.Eq(Displays.Fix.MoveWow, Displays.Decide(@"\\.\DISPLAY9", @"\\.\DISPLAY1", false, true, wowOnVirtual: true), "even when it could be mapped");
            A.Eq(Displays.Fix.RecaptureSunshine, Displays.Decide(@"\\.\DISPLAY5", @"\\.\DISPLAY1", false, true), "a real one: as before");
            A.Eq(Displays.Fix.None, Displays.Decide(@"\\.\DISPLAY9", @"\\.\DISPLAY9", false, false, true));
        }

        [Test]
        public static void ARememberedDisplayThatWentIsCleared()
        {
            var sun = new List<SunshineDisplay> { S("{a}", @"\\.\DISPLAY1", primary: true), S("{b}", @"\\.\DISPLAY9") };
            var win = new List<WinDisplay> { Real };
            A.Eq(Displays.Change.ClearAndRestart, Displays.OnChange(sun, win, "{b}", "{b}", false), "remembered DISPLAY9 is no longer on");
            A.Eq(Displays.Change.ClearAndRestart, Displays.OnChange(sun, win, "{zzz}", "{zzz}", false), "not in Sunshine's list");
            A.Eq(Displays.Change.None, Displays.OnChange(sun, win, "{b}", "{b}", true), "never during a stream");
            A.Eq(Displays.Change.None, Displays.OnChange(sun, win, "{a}", "{a}", false), "still there");
            A.Eq(Displays.Change.None, Displays.OnChange(sun, win, null, null, false), "the primary, still there");
            // a remembered virtual one (an old Desktop saved Apollo's): cleared
            var v = new List<SunshineDisplay> { S("{a}", @"\\.\DISPLAY1"), new SunshineDisplay { DeviceId = "{b}", DisplayName = @"\\.\DISPLAY9", Active = true, Virtual = true } };
            A.Eq(Displays.Change.ClearAndRestart, Displays.OnChange(v, new List<WinDisplay> { Real, Apollo }, "{b}", "{b}", false));
        }

        [Test]
        public static void SunshineRereadsWhenItsScreenWentOrOneCameBack()
        {
            // captured Apollo's screen (the primary while its client was on); Apollo's client left
            var sun = new List<SunshineDisplay> { S("{b}", @"\\.\DISPLAY9", primary: true), S("{a}", @"\\.\DISPLAY1", active: false) };
            A.Eq(Displays.Change.Restart, Displays.OnChange(sun, new List<WinDisplay> { RealOff }, null, null, false), "nothing it captures is on");
            // headless at its start, then a monitor was turned on
            var none = new List<SunshineDisplay> { S("{a}", @"\\.\DISPLAY1", active: false) };
            A.Eq(Displays.Change.Restart, Displays.OnChange(none, new List<WinDisplay> { Real }, null, null, false));
            A.Eq(Displays.Change.None, Displays.OnChange(new List<SunshineDisplay>(), new List<WinDisplay> { Real }, null, null, false), "no list yet");
            A.Eq(Displays.Change.None, Displays.OnChange(sun, new List<WinDisplay>(), null, null, false), "Windows' list unread");
        }

        [Test]
        public static void TheScreenPlan()
        {
            // headless + SudoVDA present: ours is made
            A.Eq(ScreenPlan.Step.MakeOurs, ScreenPlan.Decide(false, true, false, true, false));
            // headless + no driver: asked once, then said
            A.Eq(ScreenPlan.Step.OfferInstall, ScreenPlan.Decide(false, false, false, true, false));
            A.Eq(ScreenPlan.Step.NeedsInstall, ScreenPlan.Decide(false, false, false, true, true));
            // a real screen present: none made, and ours goes
            A.Eq(ScreenPlan.Step.None, ScreenPlan.Decide(true, true, false, true, false));
            A.Eq(ScreenPlan.Step.RemoveOurs, ScreenPlan.Decide(true, true, true, true, false));
            // no Thor: nothing made; ours goes
            A.Eq(ScreenPlan.Step.None, ScreenPlan.Decide(false, true, false, false, false));
            A.Eq(ScreenPlan.Step.RemoveOurs, ScreenPlan.Decide(false, true, true, false, false));
            A.Eq(ScreenPlan.Step.Keep, ScreenPlan.Decide(false, true, true, true, false));
        }

        [Test]
        public static void ApollosScreenPresentButOursNeeded()
        {
            // Apollo's session screen is on (its client connected) and no real one: it is not a screen we can keep, ours is made
            var win = new List<WinDisplay> { RealOff, Apollo };
            A.False(Displays.HasScreen(win), "Apollo's screen goes with its client");
            A.Eq(ScreenPlan.Step.MakeOurs, ScreenPlan.Decide(Displays.HasScreen(win), true, false, true, false));
            A.True(Displays.HasScreen(new List<WinDisplay> { Real, Apollo }), "a real monitor too");
            A.True(Displays.HasScreen(new List<WinDisplay> { RealOff, Vdd }), "a lasting virtual screen (Virtual Display Driver) is a screen");
            A.False(Displays.HasRealScreen(new List<WinDisplay> { RealOff, Vdd }));
            A.False(Displays.HasScreen(new List<WinDisplay> { RealOff }), "headless");
        }

        [Test]
        public static void SudoVdaWireFormat()
        {
            // CTL_CODE(FILE_DEVICE_UNKNOWN 0x22, fn, METHOD_BUFFERED, FILE_ANY_ACCESS) as sudovda-ioctl.h
            A.Eq(0x00222000u, SudoVda.IoctlAdd);
            A.Eq(0x00222004u, SudoVda.IoctlRemove);
            A.Eq(0x0022200Cu, SudoVda.IoctlWatchdog);
            A.Eq(0x00222220u, SudoVda.IoctlPing);
            A.Eq(0x002223FCu, SudoVda.IoctlVersion);
            var g = new Guid("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
            var b = SudoVda.AddParams(1920, 1080, 120, g, "DS Forever", "DSF0001-too-long-serial");
            A.Eq(56, b.Length);
            A.Eq(1920u, BitConverter.ToUInt32(b, 0));
            A.Eq(1080u, BitConverter.ToUInt32(b, 4));
            A.Eq(120000u, BitConverter.ToUInt32(b, 8), "millihertz");
            A.Eq(g, new Guid(b.Skip(12).Take(16).ToArray()));
            A.Eq("DS Forever", System.Text.Encoding.ASCII.GetString(b, 28, 14).TrimEnd('\0'));
            A.Eq("DSF0001-too-l", System.Text.Encoding.ASCII.GetString(b, 42, 14).TrimEnd('\0'), "13 chars and a NUL");
            A.True(SudoVda.Compatible(0, 2));
            A.True(SudoVda.Compatible(0, 3));
            A.False(SudoVda.Compatible(0, 1));
            A.False(SudoVda.Compatible(1, 2));
        }

        [Test]
        public static void TheThorsModeForOurScreen()
        {
            A.Eq((1920, 1080, 120), ScreenPlan.Mode(1920, 1080, 120));
            A.Eq((1280, 720, 60), ScreenPlan.Mode(1280, 720, 60));
            A.Eq((1920, 1080, 60), ScreenPlan.Mode(0, 0, 0), "unknown: 1080p60");
            A.Eq((1920, 1080, 60), ScreenPlan.Mode(1920, 1080, 999));
        }

        [Test]
        public static void TheLinesAreTerse()
        {
            A.Eq("No screen: making one", ScreenPlan.Making);
            A.Eq("Virtual screen ready", ScreenPlan.Ready);
            A.Eq("Needs a virtual screen", ScreenPlan.Install);
            A.Eq("pc needs a virtual screen: set it up in Desktop", ScreenPlan.ThorNeedsScreen);
            A.True(VddInstall.WingetArgs.Contains("VirtualDrivers.Virtual-Display-Driver") && VddInstall.WingetArgs.Contains("-e"), VddInstall.WingetArgs);
        }

        [Test]
        public static void ReadsThisPcsDisplays()
        {
            // read-only: Windows' list and the SudoVDA probe answer without admin (the result depends on this PC)
            var win = Displays.WindowsDisplays();
            A.True(win.All(d => d.Name != null && d.Name.StartsWith(@"\\.\")), string.Join("; ", win));
            SudoVda.Installed();
            A.NotNull(Displays.TopologyKey(win));
        }
    }
}
