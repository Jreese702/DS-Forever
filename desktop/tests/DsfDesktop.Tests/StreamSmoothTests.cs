using System;
using System.IO;
using DsfDesktop.Sunshine;
using DsfDesktop.Wow;

namespace DsfDesktop.Tests
{
    /// <summary>
    /// STREAM SMOOTHNESS (a tester's report 2026-11-23: stutter and "about half its FPS"; the user's pick "Keep the higher rate": "Match
    /// changes only the resolution and keeps the monitor's own higher refresh rate"): our own screen's rate, the match's refresh option,
    /// and the stream-start look at the screens and the game's own frame settings (read only).
    /// </summary>
    public static class StreamSmoothTests
    {
        [Test]
        public static void OurOwnScreenIsNeverSlowerThanTheStreamOrTheMonitor()
        {
            A.Eq(144, ScreenPlan.OwnHz(60, 144), "a 144 Hz monitor's PC: ours at 144 for a 60 fps stream");
            A.Eq(120, ScreenPlan.OwnHz(120, 60), "a 60 Hz monitor, a 120 fps stream: the stream's");
            A.Eq(60, ScreenPlan.OwnHz(60, 0), "headless: the stream's");
            A.Eq(240, ScreenPlan.OwnHz(60, 360), "within 240");
            A.Eq(60, ScreenPlan.OwnHz(0, 0), "no stream rate yet: 60");
            A.Eq(165, ScreenPlan.OwnHz(5, 165));
        }

        [Test]
        public static void MatchKeepsTheMonitorsOwnRate()
        {
            A.Eq("disabled", SunshineConfig.RefreshOption);
            var on = SunshineConfig.ConfText(new SunshineLayout { ExeDir = "a", DataDir = "b" }, new SunshineOptions { MatchStream = true });
            A.True(on.Contains("dd_resolution_option = auto"), "the resolution is still the Thor's");
            A.True(on.Contains("dd_refresh_rate_option = disabled"), on);
            A.False(on.Contains("max_bitrate"), "no bit-rate cap of ours (the Thor's pick, up to 150 Mbps, goes through)");
            var off = SunshineConfig.ConfText(new SunshineLayout { ExeDir = "a", DataDir = "b" }, new SunshineOptions { MatchStream = false });
            A.False(off.Contains("dd_refresh_rate_option") || off.Contains("max_bitrate"));
        }

        const string Cfg = "SET locale \"enUS\"\r\nSET gxWindow \"1\"\r\nSET gxMaximize \"1\"\r\nSET vsync \"1\"\r\nSET maxFPS \"120\"\r\n" +
                           "SET maxFPSBk \"30\"\r\nSET useMaxFPSBk \"1\"\r\nSET accountName \"someone@example.com\"\r\n";

        [Test]
        public static void TheGamesFrameSettingsAreRead()
        {
            var d = WowFrameCaps.Parse(Cfg);
            A.Eq("1", d["vsync"]); A.Eq("120", d["maxFPS"]); A.Eq("30", d["maxFPSBk"]); A.Eq("1", d["useMaxFPSBk"]);
            A.False(d.ContainsKey("accountName"), "only the frame keys");
            A.Eq("vsync 1, maxFPS 120 (useMaxFPS -), maxFPSBk 30 (useMaxFPSBk 1), gxMaximize 1, gxWindow 1 (- = the game's default)", WowFrameCaps.Word(Cfg));
            A.True(WowFrameCaps.Word("").StartsWith("vsync -, maxFPS - (useMaxFPS -)"), "an empty file: every default");
            // the last line of a key wins; an odd value is never logged as is; lower-case spelling
            A.Eq("60", WowFrameCaps.Parse("SET maxFPS \"200\"\nSET maxfps \"60\"\n")["maxFPS"]);
            A.Eq("?", WowFrameCaps.Parse("SET vsync \"some long words here\"\n")["vsync"]);
            A.False(WowFrameCaps.Word(Cfg).Contains("example"), "nothing else of the file");
        }

        [Test]
        public static void TheFileIsOnlyRead()
        {
            using (var t = new Tmp())
            {
                var p = t.P("Config.wtf");
                File.WriteAllText(p, Cfg);
                var before = File.ReadAllBytes(p);
                var at = new DateTime(2026, 11, 1, 12, 0, 0, DateTimeKind.Utc);
                File.SetLastWriteTimeUtc(p, at);
                // held open by "the game" for writing: still read
                using (new FileStream(p, FileMode.Open, FileAccess.ReadWrite, FileShare.ReadWrite))
                    A.True(WowFrameCaps.Read(p).StartsWith("vsync 1, maxFPS 120"), WowFrameCaps.Read(p));
                A.Eq(Convert.ToBase64String(before), Convert.ToBase64String(File.ReadAllBytes(p)), "the bytes unchanged");
                A.Eq(at, File.GetLastWriteTimeUtc(p), "never written");
                A.Eq("Config.wtf not there yet", WowFrameCaps.Read(t.P("none.wtf")));
            }
        }

        [Test]
        public static void TheScreensWords()
        {
            A.Eq(@"\\.\DISPLAY1 1920x1080@60 (top 144)", WowFrameCaps.ScreenWord(@"\\.\DISPLAY1", false, 1920, 1080, 60, 144));
            A.Eq(@"\\.\DISPLAY3 virtual 1920x1080@120", WowFrameCaps.ScreenWord(@"\\.\DISPLAY3", true, 1920, 1080, 120, 0));
            A.Eq(@"\\.\DISPLAY2 mode unreadable", WowFrameCaps.ScreenWord(@"\\.\DISPLAY2", false, 0, 0, 0, 0));
        }
    }
}
