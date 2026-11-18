using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using DsfDesktop.Wow;
using DsfDesktop.Wow.Patch;
using static DsfDesktop.Tests.PatchKit;

namespace DsfDesktop.Tests
{
    /// <summary>
    /// The 0.2.36 review's crash / power-loss cases for the self-patcher (the PC has a history of Kernel-Power 41 hard resets): a
    /// reset or a failure at every step of the .build.info write and of the put-back, an unreadable journal, the order backups go
    /// in, the close of only _forever_'s client, a current client missing only its row, and a crashing build never re-installed by
    /// itself. All in TEMP game folders (PatchRunTests.Game), never the PC's game.
    /// </summary>
    public static partial class PatchRunTests
    {
        static string BuildInfo(Game g) => g.T.P(".build.info");
        static string ReadOrNull(string p) => File.Exists(p) ? File.ReadAllText(p) : null;

        /// <summary>the game folder (and the journal in it) back to a snapshot: the moment of a hard reset</summary>
        static void BackTo(Game g, Tmp snap)
        {
            foreach (var d in Directory.GetDirectories(g.T.Dir)) Directory.Delete(d, true);
            foreach (var f in Directory.GetFiles(g.T.Dir)) File.Delete(f);
            CopyTree(snap.Dir, g.T.Dir);
        }

        static void NoLeftovers(Game g, string when)
        {
            foreach (var x in new[] { ".dsf-tmp", ".dsf-bak2", ".dsf-undo" })
                A.False(File.Exists(BuildInfo(g) + x), ".build.info" + x + " left (" + when + ")");
        }

        /// <summary>what ReplaceFile does when it fails half way (ERROR_UNABLE_TO_MOVE_REPLACEMENT_2): the old file already renamed to
        /// the backup (or gone, with no backup), the new one not moved in</summary>
        static void HalfReplace(string tmp, string dest, string backup)
        {
            if (backup != null) { if (File.Exists(backup)) File.Delete(backup); File.Move(dest, backup); }
            else File.Delete(dest);
            throw new IOException("Unable to move the replacement file to the file to be replaced (1177)");
        }

        // ---- 1. .build.info's write fails half way

        [Test]
        public static void ABuildInfoReplaceThatFailsHalfWayPutsItBack()
        {
            using (var g = new Game(withRow: true))
            {
                bool once = false;
                g.Env.ReplaceFile = (t, d, b) => { if (d == BuildInfo(g) && !once) { once = true; HalfReplace(t, d, b); } else PatchEnv.DefaultReplace(t, d, b); };
                var r = new PcGamePatcher(g.Env).RunNow();
                A.Eq(PatchState.Failed, r.State);
                A.Eq(g.BuildInfoText, ReadOrNull(BuildInfo(g)), ".build.info back (it was only in .dsf-bak)");
                A.True(g.AllOld(), "the old client whole");
                A.False(File.Exists(g.Env.JournalPath), "put back: no journal");
                NoLeftovers(g, "after the put-back");
                A.False(File.Exists(BuildInfo(g) + ".dsf-bak"), "no install: no .dsf-bak");
                g.Env.ReplaceFile = PatchEnv.DefaultReplace;
                A.Eq(PatchState.Done, new PcGamePatcher(g.Env).RunNow().State, "a Retry finishes it");
                A.True(g.AllNew());
            }
        }

        /// <summary>the .dsf-bak2 case: a patch folded into an install still awaiting its login, its .build.info write half done</summary>
        [Test]
        public static void ABuildInfoReplaceHalfDoneOnAFoldedInstallPutsBackTheOneFromBeforeBoth()
        {
            using (var g = new Game(withRow: true))
            {
                A.Eq(PatchState.Done, new PcGamePatcher(g.Env).RunNow().State);
                var text = File.ReadAllText(BuildInfo(g));
                File.WriteAllText(BuildInfo(g), text.Replace(g.Row() + "\n", ""));   // the Agent drops the row
                File.WriteAllBytes(g.F("wow_loader.dll"), Bytes("agent-wrote", 500));
                bool once = false;
                g.Env.ReplaceFile = (t, d, b) => { if (d == BuildInfo(g) && !once) { once = true; HalfReplace(t, d, b); } else PatchEnv.DefaultReplace(t, d, b); };
                var r = new PcGamePatcher(g.Env).RunNow();
                A.Eq(PatchState.Failed, r.State);
                A.Eq(g.BuildInfoText, ReadOrNull(BuildInfo(g)), "the .build.info from before both");
                A.True(g.AllOld(), "the client from before both");
                A.False(File.Exists(g.Env.JournalPath));
                NoLeftovers(g, "folded");
            }
        }

        /// <summary>a hard reset at EVERY durable write of an install (each journal save, the .build.info write), before it and right
        /// after it: the next start leaves the old client whole (or, after the last, the new one awaiting its login), .build.info valid</summary>
        [Test]
        public static void AResetAtEveryWriteOfTheInstallIsRecovered()
        {
            int writes;
            using (var g = new Game(withRow: true))
            {
                int n = 0;
                g.Env.ReplaceFile = (t, d, b) => { n++; PatchEnv.DefaultReplace(t, d, b); };
                A.Eq(PatchState.Done, new PcGamePatcher(g.Env).RunNow().State);
                writes = n;
            }
            A.True(writes >= 8, "journal saves + .build.info: " + writes);
            foreach (bool after in new[] { false, true })
                for (int at = 1; at <= writes; at++)
                    using (var g = new Game(withRow: true))
                    using (var snap = new Tmp())
                    {
                        int n = 0; bool cut = false;
                        g.Env.ReplaceFile = (t, d, b) =>
                        {
                            if (!cut && ++n == at && !after) { cut = true; CopyTree(g.T.Dir, snap.Dir); throw new Crash(); }
                            PatchEnv.DefaultReplace(t, d, b);
                            if (!cut && n == at && after) { cut = true; CopyTree(g.T.Dir, snap.Dir); throw new Crash(); }
                        };
                        new PcGamePatcher(g.Env).RunNow();
                        A.True(cut, "cut at " + at);
                        BackTo(g, snap);
                        g.Env.ReplaceFile = PatchEnv.DefaultReplace;
                        var p = new PcGamePatcher(g.Env);
                        var why = p.Recover();
                        var where = (after ? "right after" : "before") + " write " + at + "/" + writes + ": " + why;
                        if (after && at == writes)
                        {
                            A.True(why != null && why.Contains("first good login"), where);
                            A.True(g.AllNew(), where);
                        }
                        else
                        {
                            A.True(why == null || why.Contains("undone"), where);
                            A.True(g.AllOld(), "the old client whole, " + where);
                            A.Eq(g.BuildInfoText, ReadOrNull(BuildInfo(g)), ".build.info, " + where);
                            A.False(File.Exists(g.Env.JournalPath), "no journal, " + where);
                            NoLeftovers(g, where);
                        }
                    }
        }

        /// <summary>the .build.info write cut at each of its own steps (temp written; Replace half way; Replace done), by a reset</summary>
        [Test]
        public static void AResetAtEachStepOfTheBuildInfoWriteIsRecovered()
        {
            foreach (var step in new[] { "tmp", "half", "done" })
                using (var g = new Game(withRow: true))
                using (var snap = new Tmp())
                {
                    bool cut = false;
                    g.Env.ReplaceFile = (t, d, b) =>
                    {
                        if (cut || d != BuildInfo(g)) { PatchEnv.DefaultReplace(t, d, b); return; }
                        cut = true;
                        if (step == "half") { File.Move(d, b); }
                        else if (step == "done") PatchEnv.DefaultReplace(t, d, b);
                        CopyTree(g.T.Dir, snap.Dir);
                        throw new Crash();
                    };
                    new PcGamePatcher(g.Env).RunNow();
                    BackTo(g, snap);
                    if (step == "half") A.False(File.Exists(BuildInfo(g)), "the reset left no .build.info");
                    g.Env.ReplaceFile = PatchEnv.DefaultReplace;
                    var p = new PcGamePatcher(g.Env);
                    A.True(p.Unsettled, "an install cut short is unsettled (" + step + ")");
                    var why = p.Recover();
                    A.True(why != null && why.Contains("undone"), step + ": " + why);
                    A.Eq(g.BuildInfoText, ReadOrNull(BuildInfo(g)), step);
                    A.True(g.AllOld(), step);
                    NoLeftovers(g, step);
                    A.False(p.Unsettled);
                }
        }

        // ---- 1/2. the put-back of .build.info

        /// <summary>a reset at each step of the put-back (each file move, the .build.info replace before / half way): the next try ends it</summary>
        [Test]
        public static void AResetAtEachStepOfThePutBackIsFinishedNextTime()
        {
            int moves;
            using (var g = new Game(withRow: true))
            {
                var p = new PcGamePatcher(g.Env);
                A.Eq(PatchState.Done, p.RunNow().State);
                int n = 0;
                g.Env.MoveFile = (a, b) => { n++; File.Move(a, b); };
                A.True(p.RestoreOld("crash"));
                moves = n;
                A.True(g.AllOld());
            }
            A.True(moves >= 8, "moves " + moves);
            for (int at = 1; at <= moves + 2; at++)
                using (var g = new Game(withRow: true))
                using (var snap = new Tmp())
                {
                    A.Eq(PatchState.Done, new PcGamePatcher(g.Env).RunNow().State);
                    int n = 0; bool cut = false;
                    g.Env.MoveFile = (a, b) =>
                    {
                        if (!cut && ++n == at) { cut = true; CopyTree(g.T.Dir, snap.Dir); throw new Crash(); }
                        File.Move(a, b);
                    };
                    int extra = at - moves;   // 1 = before the .build.info replace, 2 = half way (no backup: the old one gone)
                    g.Env.ReplaceFile = (t, d, b) =>
                    {
                        if (!cut && d == BuildInfo(g) && extra >= 1)
                        {
                            cut = true;
                            if (extra == 2) File.Delete(d);
                            CopyTree(g.T.Dir, snap.Dir);
                            throw new Crash();
                        }
                        PatchEnv.DefaultReplace(t, d, b);
                    };
                    new PcGamePatcher(g.Env).RestoreOld("crash");
                    A.True(cut, "cut at " + at);
                    BackTo(g, snap);
                    g.Env.MoveFile = (a, b) => File.Move(a, b);
                    g.Env.ReplaceFile = PatchEnv.DefaultReplace;
                    // the journal still says "installed" (a reset before the put-back saved anything): the next start's RestoreOld
                    var p = new PcGamePatcher(g.Env);
                    A.True(p.RestoreOld("crash") || p.Recover() != null, "at " + at);
                    A.True(g.AllOld(), "the old client whole, reset at put-back step " + at);
                    A.Eq(g.BuildInfoText, ReadOrNull(BuildInfo(g)), ".build.info, put-back step " + at);
                    A.False(File.Exists(g.Env.JournalPath), "at " + at);
                    NoLeftovers(g, "put-back step " + at);
                }
        }

        /// <summary>the put-back's .build.info replace fails (not a reset): the journal stays, the next start ends it; through a temp
        /// file, never a copy in place</summary>
        [Test]
        public static void AFailedBuildInfoPutBackKeepsTheJournal()
        {
            using (var g = new Game(withRow: true))
            {
                var p = new PcGamePatcher(g.Env);
                A.Eq(PatchState.Done, p.RunNow().State);
                var targets = new List<string>();
                g.Env.ReplaceFile = (t, d, b) =>
                {
                    targets.Add(Path.GetFileName(t) + ">" + Path.GetFileName(d));
                    if (d == BuildInfo(g)) throw new IOException("in use");
                    PatchEnv.DefaultReplace(t, d, b);
                };
                A.True(p.RestoreOld("crash"));
                A.True(targets.Contains(".build.info.dsf-undo>.build.info"), "through its temp file: " + string.Join(", ", targets));
                A.True(File.Exists(g.Env.JournalPath), "not finished: the journal stays");
                A.True(p.Unsettled);
                g.Env.ReplaceFile = PatchEnv.DefaultReplace;
                var why = new PcGamePatcher(g.Env).Recover();
                A.True(why.Contains("undone"), why);
                A.Eq(g.BuildInfoText, ReadOrNull(BuildInfo(g)));
                A.True(g.AllOld());
            }
        }

        /// <summary>.build.info missing and no old copy anywhere: NEVER reported as put back (the journal stays: needs repair)</summary>
        [Test]
        public static void NoBuildInfoAndNoOldCopyIsNeverASuccess()
        {
            using (var g = new Game(withRow: true))
            {
                A.Eq(PatchState.Done, new PcGamePatcher(g.Env).RunNow().State);
                File.WriteAllText(g.Env.JournalPath, File.ReadAllText(g.Env.JournalPath).Replace("\"installed\"", "\"installing\""));
                File.Delete(BuildInfo(g));
                File.Delete(BuildInfo(g) + ".dsf-bak");
                var why = new PcGamePatcher(g.Env).Recover();
                A.True(why.Contains("not all put back"), why);
                A.True(File.Exists(g.Env.JournalPath), "the journal stays");
                A.True(g.AllOld(), "the files are back all the same");
                // a broken (torn) .build.info counts as missing: put back from .dsf-bak once there is one
                File.WriteAllText(BuildInfo(g), "Bran");
                File.WriteAllText(BuildInfo(g) + ".dsf-bak", g.BuildInfoText);
                why = new PcGamePatcher(g.Env).Recover();
                A.True(why.Contains("undone"), why);
                A.Eq(g.BuildInfoText, ReadOrNull(BuildInfo(g)));
                A.False(File.Exists(g.Env.JournalPath));
            }
        }

        // ---- 3. the journal

        [Test]
        public static void AnUnreadableJournalStopsThePatch()
        {
            foreach (var junk in new[] { "", "{\"state\":\"install", "not json", "{}" })
                using (var g = new Game(withRow: true))
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(g.Env.JournalPath));
                    File.WriteAllText(g.Env.JournalPath, junk);
                    var p = new PcGamePatcher(g.Env);
                    var r = p.RunNow();
                    A.Eq(PatchState.Failed, r.State, "[" + junk + "]");
                    A.Eq("busy", r.Code);
                    A.True(r.Why.Contains("needs repair"), r.Why);
                    A.Eq(0, g.Swaps.Count, "never patched over it");
                    A.True(g.AllOld());
                    A.Eq(junk, File.ReadAllText(g.Env.JournalPath), "the journal left as it is");
                    var why = p.Recover();
                    A.True(why.Contains("needs repair"), why);
                    A.Eq(junk, File.ReadAllText(g.Env.JournalPath), "not deleted by the recovery either");
                    A.True(p.JournalBroken);
                    A.Null(p.AwaitingLogin);
                    A.False(p.GoodLogin("1.60.1.70959"));
                    A.False(p.RestoreOld("crash"));
                }
            // a journal held open by another program: the same (never "no journal")
            using (var g = new Game(withRow: true))
            {
                A.Eq(PatchState.Done, new PcGamePatcher(g.Env).RunNow().State);
                using (new FileStream(g.Env.JournalPath, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
                {
                    var r = new PcGamePatcher(g.Env).RunNow();
                    A.Eq("busy", r.Code, r.Why);
                }
                A.True(g.AllNew(), "the installed client left");
            }
        }

        /// <summary>the journal written through a flushed temp file and a replace (never in place)</summary>
        [Test]
        public static void TheJournalIsWrittenThroughATempFile()
        {
            using (var g = new Game(withRow: true))
            {
                var seen = new List<string>();
                g.Env.ReplaceFile = (t, d, b) => { if (d == g.Env.JournalPath) seen.Add(Path.GetFileName(t)); PatchEnv.DefaultReplace(t, d, b); };
                A.Eq(PatchState.Done, new PcGamePatcher(g.Env).RunNow().State);
                A.True(seen.Count >= 3 && seen.All(x => x == "journal.json.tmp"), string.Join(",", seen));
                A.False(File.Exists(g.Env.JournalPath + ".tmp"));
            }
        }

        // ---- 3/4. backups

        [Test]
        public static void ASwapNeverDeletesAnExistingBackupFirst()
        {
            using (var t = new Tmp())
            {
                string to = t.P("f.dll"), from = t.P("new.dll"), bak = t.P("bak", "f.dll");
                Directory.CreateDirectory(t.P("bak"));
                // a stale backup: replaced only by a checked copy of the old file, none of the fresh copy left
                File.WriteAllText(to, "old"); File.WriteAllText(from, "new"); File.WriteAllText(bak, "stale");
                PatchEnv.DefaultSwap(from, to, bak);
                A.Eq("new", File.ReadAllText(to));
                A.Eq("old", File.ReadAllText(bak));
                A.False(File.Exists(bak + ".dsf-new"));
                // a backup that already IS the old file: kept as it is
                File.WriteAllText(to, "old"); File.WriteAllText(from, "newer");
                var at = File.GetLastWriteTimeUtc(bak);
                PatchEnv.DefaultSwap(from, to, bak);
                A.Eq("newer", File.ReadAllText(to));
                A.Eq("old", File.ReadAllText(bak));
            }
            // a reset between the fresh copy and its rename: the put-back finds the old file in backup.dsf-new
            using (var g = new Game(withRow: true))
            {
                var p = new PcGamePatcher(g.Env);
                A.Eq(PatchState.Done, p.RunNow().State);
                var bak = g.F(Path.Combine(".dsf-backup", "70938", "Wow.exe"));
                File.Move(bak, bak + ".dsf-new");
                File.WriteAllText(bak, "stale");
                A.True(p.RestoreOld("crash"));
                A.True(g.AllOld(), "the old Wow.exe from .dsf-new, never the stale backup");
                A.False(File.Exists(bak + ".dsf-new"));
            }
        }

        /// <summary>a file of an earlier install counts as backed up only when its backup is there and is its old file</summary>
        [Test]
        public static void AFoldedFileIsBackedUpAgainWhenItsBackupIsGone()
        {
            using (var g = new Game(withRow: true))
            {
                A.Eq(PatchState.Done, new PcGamePatcher(g.Env).RunNow().State);
                var bak = g.F(Path.Combine(".dsf-backup", "70938", "wow_loader.dll"));
                File.WriteAllText(bak, "damaged");                                       // not the old file any more
                var agent = Bytes("agent-wrote", 500);
                File.WriteAllBytes(g.F("wow_loader.dll"), agent);                         // the Agent wrote it meanwhile
                File.WriteAllText(BuildInfo(g), File.ReadAllText(BuildInfo(g)).Replace(g.Row() + "\n", ""));
                var p = new PcGamePatcher(g.Env);
                A.Eq(PatchState.Done, p.RunNow().State);
                A.Eq(Md5(agent), Hex.Md5File(bak), "backed up again as it was (the damaged copy replaced by a checked one)");
                A.Eq(Md5(g.Old["Wow.exe"]), Hex.Md5File(g.F(Path.Combine(".dsf-backup", "70938", "Wow.exe"))), "the others' backups kept");
                A.True(p.RestoreOld("crash"));
                A.Eq(Md5(agent), g.Md5Of("wow_loader.dll"), "the best old copy there was");
                A.Eq(Md5(g.Old["Wow.exe"]), g.Md5Of("Wow.exe"));
            }
        }

        /// <summary>the good login: the journal FIRST, then the backup; a journal that can't go keeps the backup</summary>
        [Test]
        public static void AGoodLoginDropsTheJournalBeforeTheBackup()
        {
            using (var g = new Game(withRow: true))
            {
                var p = new PcGamePatcher(g.Env);
                A.Eq(PatchState.Done, p.RunNow().State);
                A.True(File.Exists(BuildInfo(g) + ".dsf-bak"), "ONE .dsf-bak until the first good login");
                NoLeftovers(g, "after the install");
                using (new FileStream(g.Env.JournalPath, FileMode.Open, FileAccess.Read, FileShare.Read))
                    A.False(p.GoodLogin("1.60.1.70959"), "the journal can't go");
                A.True(Directory.Exists(g.F(".dsf-backup")), "so the backup stays");
                A.True(File.Exists(BuildInfo(g) + ".dsf-bak"));
                A.Eq("1.60.1.70959", p.AwaitingLogin);
                A.True(p.GoodLogin("1.60.1.70959"));
                A.False(File.Exists(g.Env.JournalPath));
                A.False(Directory.Exists(g.F(".dsf-backup")));
                A.False(File.Exists(BuildInfo(g) + ".dsf-bak"), ".dsf-bak goes with the first good login");
            }
        }

        [Test]
        public static void LeftoversOfTheBuildInfoWriteGoAfterASuccess()
        {
            using (var g = new Game(withRow: true))
            {
                File.WriteAllText(BuildInfo(g) + ".dsf-bak2", "stale");
                File.WriteAllText(BuildInfo(g) + ".dsf-undo", "stale");
                A.Eq(PatchState.Done, new PcGamePatcher(g.Env).RunNow().State);
                NoLeftovers(g, "after a success");
                A.Eq(g.BuildInfoText, ReadOrNull(BuildInfo(g) + ".dsf-bak"));
            }
        }

        // ---- 5. busy while putting back; the Agent

        [Test]
        public static void APutBackIsBusyAndWaitsForTheAgent()
        {
            using (var g = new Game(withRow: true))
            {
                var p = new PcGamePatcher(g.Env);
                A.Eq(PatchState.Done, p.RunNow().State);
                bool sawBusy = false, startRefused = false;
                int asks = 0;
                g.Env.WowRunning = () => { sawBusy |= p.Busy; startRefused |= !p.Start("test"); return false; };
                g.Env.AgentBusy = () => ++asks <= 2 ? "its log has an operation on wowf under way" : null;
                g.Env.AgentWaitS = 20;
                A.True(p.RestoreOld("crash"));
                A.True(sawBusy, "busy while it puts back (WoW not started)");
                A.True(startRefused, "no patch beside it");
                A.Eq(3, asks, "the Agent waited for");
                A.True(g.AllOld());
                A.False(p.Busy, "not busy after it");
            }
            using (var g = new Game(withRow: true))
            {
                var p = new PcGamePatcher(g.Env);
                A.Eq(PatchState.Done, p.RunNow().State);
                g.Env.AgentBusy = () => "it wrote the game's records in the last 120 s";
                g.Env.AgentWaitS = 10;
                A.False(p.RestoreOld("crash"), "the Agent at work: not yet");
                A.True(g.AllNew(), "nothing moved beside it");
                A.Eq("1.60.1.70959", p.AwaitingLogin, "asked again later");
            }
            // the start's recovery: the Agent asked once (0 s); busy meanwhile
            using (var g = new Game(withRow: true))
            {
                A.Eq(PatchState.Done, new PcGamePatcher(g.Env).RunNow().State);
                File.WriteAllText(g.Env.JournalPath, File.ReadAllText(g.Env.JournalPath).Replace("\"installed\"", "\"installing\""));
                var p = new PcGamePatcher(g.Env);
                bool busy = false;
                g.Env.AgentBusy = () => { busy = p.Busy; return "busy"; };
                var why = p.Recover(agentWaitS: 0);
                A.True(why.Contains("Blizzard's updater"), why);
                A.True(busy, "busy while it asks");
                A.True(p.Unsettled, "left for the background");
                g.Env.AgentBusy = () => null;
                A.True(p.Recover().Contains("undone"));
                A.True(g.AllOld());
            }
        }

        /// <summary>the Agent asked again after the lock wait: busy then = nothing swapped</summary>
        [Test]
        public static void TheAgentIsAskedAgainAfterTheLockWait()
        {
            using (var g = new Game())
            {
                int asks = 0;
                g.Env.AgentBusy = () => ++asks >= 3 ? "its log has an operation on wowf under way" : null;
                g.Env.AgentWaitS = 10;
                var r = new PcGamePatcher(g.Env).RunNow();
                A.Eq("agent_busy", r.Code, r.Why);
                A.True(r.Why.Contains("waited for"), r.Why);
                A.Eq(0, g.Swaps.Count);
                A.True(g.AllOld());
                A.False(File.Exists(g.Env.JournalPath));
            }
        }

        // ---- 6. the close: only _forever_'s client

        [Test]
        public static void OnlyForeversClientIsClosed()
        {
            const string forever = @"C:\Games\World of Warcraft\_forever_";
            var retail = new WowProc { Pid = 10, Path = @"C:\Games\World of Warcraft\_retail_\Wow.exe" };
            var fov = new WowProc { Pid = 20, Path = forever + @"\Wow.exe" };
            var closed = new List<int>(); var killed = new List<int>(); var asked = new List<int>();
            Func<int, int, bool> exits = (pid, ms) => true;
            string Run(IList<WowProc> l, Func<int, bool> signed, Func<int, int, bool> wait = null)
            {
                closed.Clear(); killed.Clear(); asked.Clear();
                return ForeverClose.Run(forever, () => l, pid => { asked.Add(pid); return signed(pid); }, pid => closed.Add(pid),
                                        wait ?? exits, pid => { killed.Add(pid); return true; });
            }
            // retail AND _forever_ open: only _forever_'s closed, retail never asked
            A.Null(Run(new[] { retail, fov }, _ => false));
            A.Eq("20", string.Join(",", closed));
            A.False(asked.Contains(10), "retail never even asked");
            // retail alone (signed in or not): nothing closed
            A.Null(Run(new[] { retail }, _ => true));
            A.Eq(0, closed.Count);
            // a client whose path can't be read: nothing closed at all
            A.NotNull(Run(new[] { new WowProc { Pid = 30, Path = null }, fov }, _ => false));
            A.Eq(0, closed.Count + killed.Count);
            // _forever_ signed in: never closed
            A.Eq("WoW is signed in", Run(new[] { retail, fov }, pid => pid == 20));
            A.Eq(0, closed.Count);
            // signed in while it was asked to close: never killed (asked again on the SAME process)
            int n = 0;
            A.Eq("WoW signed in meanwhile", Run(new[] { fov }, pid => n++ > 0, (pid, ms) => false));
            A.Eq("20", string.Join(",", closed));
            A.Eq(0, killed.Count);
            A.Eq("20,20", string.Join(",", asked));
            // still at its login after the wait: killed
            int w = 0;
            A.Null(Run(new[] { fov }, _ => false, (pid, ms) => ++w > 1));
            A.Eq("20", string.Join(",", killed));
            // a path in a folder beside it (_forever_ptr_) is not _forever_
            A.Null(Run(new[] { new WowProc { Pid = 40, Path = forever + @"ptr_\Wow.exe" } }, _ => false));
            A.Eq(0, closed.Count);
        }

        /// <summary>a current client missing only its wowf row: the row repaired, WoW NEVER closed, no backup and nothing to wait for</summary>
        [Test]
        public static void ACurrentClientMissingOnlyItsRowIsNotClosed()
        {
            using (var g = new Game())
            {
                foreach (var kv in g.New) { Directory.CreateDirectory(Path.GetDirectoryName(g.F(kv.Key))); File.WriteAllBytes(g.F(kv.Key), kv.Value); }
                File.WriteAllBytes(g.T.P("Data", "indices", g.ArchiveB + ".index"), g.Cdn.Files[PatchKit.CdnPath("data", g.ArchiveB + ".index")]);
                int closes = 0;
                g.Env.WowRunning = () => true;
                g.Env.CloseWow = () => { closes++; return null; };
                var p = new PcGamePatcher(g.Env);
                var r = p.RunNow();
                A.Eq(PatchState.Done, r.State, r.Code + " " + r.Why);
                A.Eq(0, closes, "never closed for a row");
                A.Eq(0, g.Swaps.Count);
                A.Eq("1.60.1.70959", BuildInfoFile.Values(Header, g.Row())["Version"]);
                A.False(File.Exists(g.Env.JournalPath), "nothing to put back later");
                A.Null(p.AwaitingLogin);
                A.False(Directory.Exists(g.F(".dsf-backup")));
                A.False(File.Exists(BuildInfo(g) + ".dsf-bak"));
                NoLeftovers(g, "row only");
                A.True(File.ReadAllText(BuildInfo(g)).StartsWith(Header + "\n" + Retail + "\n"), "the retail row untouched");
            }
        }

        // ---- 7. a crashing build is not re-installed by itself

        [Test]
        public static void AFailedFirstStartIsReinstalledOnlyByARetry()
        {
            using (var g = new Game(withRow: true))
            {
                g.Env.FailedBuildPath = g.T.P("desktop", "patch", "new_failed.json");
                A.Eq(PatchState.Done, new PcGamePatcher(g.Env).RunNow(auto: true).State);
                A.True(new PcGamePatcher(g.Env).RestoreOld("crash"));
                A.True(g.AllOld());
                // Desktop restarted, a Thor connects: the attempt by itself stops at once
                var p = new PcGamePatcher(g.Env);
                A.Eq(70959, p.FailedBuild());
                g.Swaps.Clear();
                int asked = g.Cdn.Got(g.EKey["Wow.exe"]).Count;
                var r = p.RunNow(auto: true);
                A.Eq(PatchState.Failed, r.State);
                A.Eq("new_failed", r.Code, r.Why);
                A.Eq(0, g.Swaps.Count);
                A.Eq(asked, g.Cdn.Got(g.EKey["Wow.exe"]).Count, "nothing downloaded");
                A.True(g.AllOld());
                // the Retry installs it
                A.Eq(PatchState.Done, p.RunNow(auto: false).State);
                A.True(g.AllNew());
                A.True(p.GoodLogin("1.60.1.70959"));
                A.Eq(0, new PcGamePatcher(g.Env).FailedBuild(), "a good login forgets it");
            }
            // PatchFlow: by itself = the auto start; the Retry = the plain one
            int autos = 0, plains = 0;
            var s = new PatchStatus();
            var flow = new PatchFlow(() => s, w => { plains++; return true; }, () => true, () => GameCheck.Outdated, () => { },
                                     autoStart: w => { autos++; return true; });
            flow.Tick(true);
            A.Eq(1, autos); A.Eq(0, plains);
            s = new PatchStatus { State = PatchState.Failed, Code = "new_failed" };
            flow.Tick(true);
            A.Eq(1, autos, "once per connect");
            A.True(flow.Retry());
            A.Eq(1, plains);
        }
    }
}
