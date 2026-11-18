using System;
using System.IO;
using DsfDesktop.App;
using DsfDesktop.Wow;

namespace DsfDesktop.Tests
{
    /// <summary>fix/1492-feedback (the user, 2026-11-18, test 1492: "while it said it was 1080p it certainly did not look like my UI was
    /// for 1080P"): the stream's WoW is started for the screen Sunshine matched (windowed fullscreen at the Thor's size), and the player's
    /// own window lines go back after the session. Temp folders only; fake account lines only</summary>
    public static class StreamWindowTests
    {
        static readonly System.Text.Encoding Latin1 = System.Text.Encoding.GetEncoding(28591);
        // the user's PC's own window (WowConfigKeep's note: GxWindowedResolution "2560x1440"), WoW's own spelling
        const string Cfg = "SET locale \"enUS\"\r\nSET accountName \"fake@example.invalid\"\r\nSET GxWindowedResolution \"2560x1440\"\r\n" +
                           "SET GxMaximize \"0\"\r\nSET GxMonitor \"0\"\r\nSET gxRefresh \"144\"\r\n";

        sealed class Rig : IDisposable
        {
            public readonly Tmp T = new Tmp();
            public DateTime Now = DateTime.UtcNow.AddMinutes(10);
            public readonly WowConfigKeep Keep;
            public readonly string Path_;
            public Rig(string text)
            {
                Directory.CreateDirectory(T.P("_forever_", "WTF"));
                Path_ = T.P("_forever_", "WTF", "Config.wtf");
                File.WriteAllBytes(Path_, Latin1.GetBytes(text));
                Keep = new WowConfigKeep(T.P("data", "wowconfig"), () => false, () => false, () => Now);
            }
            public string Text => Latin1.GetString(File.ReadAllBytes(Path_));
            public void Dispose() => T.Dispose();
        }

        [Test]
        public static void TheWindowIsTheStreamsThenThePlayersAgain()
        {
            using (var r = new Rig(Cfg))
            {
                WowConfig.EnsureWindow(r.Path_, r.Keep, 1920, 1080);
                A.True(r.Text.Contains("SET GxWindowedResolution \"1920x1080\"\r\n"), "the stream's size");
                A.True(r.Text.Contains("SET GxFullscreenResolution \"1920x1080\"\r\n"), "the fullscreen size too");
                A.True(r.Text.Contains("SET GxMaximize \"1\"\r\n"), "windowed fullscreen");
                A.True(r.Text.Contains("SET GxMonitor \"0\"") && r.Text.Contains("SET gxRefresh \"144\"") && r.Text.Contains("fake@example.invalid"),
                       "every other line as it was");
                A.True(r.Keep.Pending, "the player's window recorded first");
                A.True(r.Keep.PutBackNow(0, _ => { }), "put back after the session");
                A.Eq(Cfg, r.Text, "the player's 2560x1440 window, not maximized, the absent fullscreen line removed");
            }
        }

        [Test]
        public static void OnlyMaximizedOnOurOwnScreenAndNeverWithoutARecord()
        {
            using (var r = new Rig(Cfg))
            {
                WowConfig.EnsureWindow(r.Path_, r.Keep, null, null);
                A.True(r.Text.Contains("SET GxWindowedResolution \"2560x1440\"") && r.Text.Contains("SET GxMaximize \"1\""), "size kept, maximized");
            }
            using (var r = new Rig(Cfg))
            {
                WowConfig.EnsureWindow(r.Path_, null, 1920, 1080);
                A.Eq(Cfg, r.Text, "no record: nothing written");
            }
        }

        [Test]
        public static void ApplyWindowKeepsTheFilesSpellingAndLineEnds()
        {
            var lf = "SET gxMaximize \"0\"\nSET gxWindowedResolution \"1280x720\"\n";
            var got = WowConfig.ApplyWindow(lf, 1920, 1080);
            A.Eq("SET gxMaximize \"1\"\nSET gxWindowedResolution \"1920x1080\"\nSET GxFullscreenResolution \"1920x1080\"\n", got);
            A.Eq(got, WowConfig.ApplyWindow(got, 1920, 1080), "a second write changes nothing");
        }

        [Test]
        public static void TheStreamWindowRule()
        {
            A.True(AppController.StreamWindowFor(false, false, true, 1920, 1080) == null, "no stream: the player's window");
            var m = AppController.StreamWindowFor(true, false, true, 1920, 1080);
            A.True(m.HasValue && m.Value.w == 1920 && m.Value.h == 1080, "matched: the Thor's mode");
            var own = AppController.StreamWindowFor(true, true, true, 1920, 1080);
            A.True(own.HasValue && own.Value.w == null, "our own screen: maximized only");
            A.True(AppController.StreamWindowFor(true, false, false, 1920, 1080) == null, "no match: the player's window");
        }

        [Test]
        public static void TheLogLines()
        {
            A.Eq("match screen: Sunshine sets the PC's screen to the Thor's 1920x1080@120 for this stream (back 3 s after it ends)",
                 AppController.MatchLine(true, false, 1920, 1080, 120));
            A.True(AppController.MatchLine(false, false, 1920, 1080, 120).StartsWith("match screen: off"));
            A.Eq("useUiScale 1, uiScale 0.64", WowConfig.UiScaleWord("SET useUiScale \"1\"\r\nSET uiScale \"0.64\"\r\n"));
            A.True(WowConfig.UiScaleWord(Cfg).StartsWith("not set"));
        }
    }
}
