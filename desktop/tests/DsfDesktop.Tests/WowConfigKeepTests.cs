using System;
using System.IO;
using System.Linq;
using DsfDesktop.Wow;

namespace DsfDesktop.Tests
{
    /// <summary>the user, 2026-11-18: the player's own controller settings go back once a DS Forever session is over (WowConfigKeep).
    /// Temp folders only; fake account lines only</summary>
    public static class WowConfigKeepTests
    {
        sealed class Rig : IDisposable
        {
            public readonly Tmp T = new Tmp();
            public bool Wow, Session;
            public DateTime Now = DateTime.UtcNow.AddMinutes(10);   // ahead of the file times: the file has settled
            public readonly WowConfigKeep Keep;
            public readonly string Cfg;
            public Rig(string text)
            {
                Directory.CreateDirectory(T.P("_retail_", "WTF"));
                Cfg = T.P("_retail_", "WTF", "Config.wtf");
                if (text != null) File.WriteAllBytes(Cfg, System.Text.Encoding.GetEncoding(28591).GetBytes(text));
                Keep = Make();
            }
            public WowConfigKeep Make() => new WowConfigKeep(T.P("data", "wowconfig"), () => Wow, () => Session, () => Now);
            public string Text => System.Text.Encoding.GetEncoding(28591).GetString(File.ReadAllBytes(Cfg));
            /// <summary>a quiet tick, then one after the grace</summary>
            public void Quiet(WowConfigKeep k = null) { k = k ?? Keep; k.Tick(); Now += k.Grace + TimeSpan.FromSeconds(1); k.Tick(); }
            public void Dispose() => T.Dispose();
        }

        const string Cfg = "SET locale \"enUS\"\r\nSET accountName \"fake@example.invalid\"\r\nSET GamePadEnable \"0\"\r\nSET gxWindow \"1\"\r\n";

        [Test]
        public static void TheControllerSettingsGoBackAfterTheSession()
        {
            using (var r = new Rig(Cfg))
            {
                WowConfig.Ensure(r.Cfg, r.Keep);
                A.True(r.Text.Contains("SET GamePadEnable \"1\"") && r.Text.Contains("SET InputDeviceInterfaceStyle \"1\""), "on for the stream");
                A.True(r.Keep.Pending, "recorded");
                r.Quiet();
                A.Eq(Cfg, r.Text, "the player's 0 back, the absent key removed, every other byte as it was");
                A.False(r.Keep.Pending, "the record goes");
                A.False(File.Exists(r.Cfg + ".dsf-tmp"));
                r.Quiet();
                A.Eq(Cfg, r.Text, "nothing more");
            }
        }

        [Test]
        public static void TheSecondStartKeepsTheFirstRecord()
        {
            using (var r = new Rig(Cfg))
            {
                WowConfig.Ensure(r.Cfg, r.Keep);
                // WoW (or the player) left only one key on before the next start: the record still holds the player's own lines
                File.WriteAllText(r.Cfg, r.Text.Replace("SET InputDeviceInterfaceStyle \"1\"\r\n", ""));
                WowConfig.Ensure(r.Cfg, r.Keep);
                r.Quiet();
                A.Eq(Cfg, r.Text);
            }
        }

        [Test]
        public static void APlayersChangeInTheGameIsKept()
        {
            using (var r = new Rig(Cfg))
            {
                WowConfig.Ensure(r.Cfg, r.Keep);
                // in the game the player set the interface style to 2 and controller off (WoW drops a default line on exit)
                var played = r.Text.Replace("SET InputDeviceInterfaceStyle \"1\"", "SET InputDeviceInterfaceStyle \"2\"").Replace("SET GamePadEnable \"1\"\r\n", "");
                File.WriteAllText(r.Cfg, played);
                r.Quiet();
                A.Eq(played, r.Text, "both theirs");
                A.False(r.Keep.Pending);
            }
            using (var r = new Rig(Cfg))
            {
                WowConfig.Ensure(r.Cfg, r.Keep);
                File.WriteAllText(r.Cfg, r.Text.Replace("SET InputDeviceInterfaceStyle \"1\"", "SET InputDeviceInterfaceStyle \"2\""));
                r.Quiet();
                A.True(r.Text.Contains("SET GamePadEnable \"0\""), "the untouched key goes back");
                A.True(r.Text.Contains("SET InputDeviceInterfaceStyle \"2\""), "the changed one stays");
            }
        }

        [Test]
        public static void NothingGoesBackWhileWowRunsOrASessionIsActive()
        {
            using (var r = new Rig(Cfg))
            {
                WowConfig.Ensure(r.Cfg, r.Keep);
                var on = r.Text;
                r.Wow = true; r.Quiet(); r.Quiet();
                A.Eq(on, r.Text, "WoW running");
                r.Wow = false; r.Session = true; r.Quiet(); r.Quiet();
                A.Eq(on, r.Text, "a stream on");
                // the stream ended, then a Retry restarted WoW inside the grace: still on
                r.Session = false; r.Keep.Tick(); r.Now += TimeSpan.FromSeconds(10); r.Keep.Tick();
                r.Wow = true; r.Keep.Tick(); r.Now += r.Keep.Grace; r.Keep.Tick();
                r.Wow = false; r.Keep.Tick(); r.Now += TimeSpan.FromSeconds(10); r.Keep.Tick();
                A.Eq(on, r.Text, "the grace starts over");
                A.True(r.Keep.Pending);
                // just written by WoW as it closed: waits for the file to settle
                File.WriteAllText(r.Cfg, on);
                r.Keep.Settle = TimeSpan.FromSeconds(90);
                r.Wow = true; r.Keep.Tick(); r.Wow = false;
                r.Now = DateTime.UtcNow; r.Quiet();
                A.Eq(on, r.Text, "file not settled");
                r.Now += r.Keep.Settle; r.Keep.Tick();
                A.Eq(Cfg, r.Text, "then back");
            }
        }

        [Test]
        public static void ARecordLeftByACrashGoesBackAfterTheNextStart()
        {
            using (var r = new Rig(Cfg))
            {
                WowConfig.Ensure(r.Cfg, r.Keep);
                var next = r.Make();    // a new Desktop: only the record on disk
                A.True(next.Pending);
                r.Quiet(next);
                A.Eq(Cfg, r.Text);
                A.False(next.Pending);
            }
        }

        [Test]
        public static void LfFilesAndOtherLinesStayByteForByte()
        {
            // LF endings, no final newline, a non-ASCII byte and a BOM elsewhere in the file
            var cfg = "\xEF\xBB\xBFSET locale \"frFR\"\nSET realmName \"Caf\xE9\"\nSET gamepadenable \"0\"\nSET InputDeviceInterfaceStyle \"0\"";
            using (var r = new Rig(cfg))
            {
                var bytes = File.ReadAllBytes(r.Cfg);
                // the write itself, as WoW would leave it after a stream (Desktop's own Apply reads with UTF-8)
                var on = cfg.Replace("SET gamepadenable \"0\"", "SET GamePadEnable \"1\"").Replace("SET InputDeviceInterfaceStyle \"0\"", "SET InputDeviceInterfaceStyle \"1\"");
                A.True(r.Keep.Record(r.Cfg, cfg, on));
                File.WriteAllBytes(r.Cfg, System.Text.Encoding.GetEncoding(28591).GetBytes(on));
                r.Quiet();
                A.True(bytes.SequenceEqual(File.ReadAllBytes(r.Cfg)), "byte-identical");
            }
        }

        [Test]
        public static void NoWriteNoRecord()
        {
            using (var r = new Rig("SET GamePadEnable \"1\"\r\nSET InputDeviceInterfaceStyle \"1\"\r\n"))
            {
                WowConfig.Ensure(r.Cfg, r.Keep);
                A.False(r.Keep.Pending, "already on: nothing to put back");
            }
            using (var r = new Rig(null))
            {
                WowConfig.Ensure(r.Cfg, r.Keep);
                A.False(r.Keep.Pending, "no Config.wtf yet");
                A.False(File.Exists(r.Cfg));
            }
            using (var r = new Rig(Cfg))
            {
                WowConfig.Ensure(r.Cfg, r.Keep);
                File.Delete(r.Cfg);
                r.Quiet();
                A.False(r.Keep.Pending, "the file went: the record goes too");
                A.False(File.Exists(r.Cfg), "nothing made");
            }
        }

        [Test]
        public static void TheRecordHoldsOnlyTheControllerKeys()
        {
            using (var r = new Rig(Cfg))
            {
                WowConfig.Ensure(r.Cfg, r.Keep);
                var rec = File.ReadAllText(r.Keep.RecordPath(r.Cfg));
                A.False(rec.Contains("fake@"), "no account line");
                A.False(rec.Contains("locale"));
                A.True(Path.GetFileName(r.Keep.RecordPath(r.Cfg)).StartsWith("controller-_retail_-"), "one per flavor folder");
            }
        }
    }
}
