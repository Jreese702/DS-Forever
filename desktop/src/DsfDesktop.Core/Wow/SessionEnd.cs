using System;
using System.Collections.Generic;
using System.Linq;
using DsfDesktop.Wow.Patch;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// THE CLEAN DISCONNECT'S CLOSE OF THE PC'S WOW (feat/pause-power; the user, 2026-11-18: "When streaming this should also close wow on
    /// the PC and revert everything back and be the clean way to disconnect"). The Thor's "end_session" (its pause menu's power button,
    /// its waiting screen's Exit game):
    ///  - ONLY the streamed client: a WoW running from the _forever_ folder (path match, as <see cref="ForeverClose"/>); never retail,
    ///    never another flavour, never a client whose path can't be read (it is left and said);
    ///  - the game's own Exit Game first when the Thor tapped it over the stream ([quitAsked]): its 20-second countdown is waited out
    ///    (its SavedVariables written as it exits);
    ///  - then its window is asked to close (WM_CLOSE, as its own X; never keys), the graceful route Desktop already uses after a stream;
    ///  - only when it is still running after that, it is ended (the player asked for the close; logged).
    /// Pure but for the injected pieces, so the tests drive it with fakes.
    /// </summary>
    public static class SessionEnd
    {
        /// <summary>what became of the PC's WoW: "closed", "none" (no client of ours was open) or "left" (it stays: [why])</summary>
        public sealed class Close
        {
            public string Wow;
            public string Why = "";
            /// <summary>how it went: "exit" (by itself), "window" (its X), "ended" (killed)</summary>
            public string How = "";
        }

        public const int SelfWaitMs = 30000;
        public const int CloseWaitMs = 20000;
        public const int KillWaitMs = 10000;

        public static Close CloseWow(string foreverDir, Func<IList<WowProc>> list, bool quitAsked, Action<int> closeWindow,
                                     Func<int, int, bool> waitExit, Func<int, bool> kill,
                                     int selfWaitMs = SelfWaitMs, int closeWaitMs = CloseWaitMs, int killWaitMs = KillWaitMs)
        {
            var dir = (foreverDir ?? "").TrimEnd('\\', '/');
            if (dir.Length == 0) return new Close { Wow = "none", Why = "no WoW folder" };
            var ps = list() ?? new List<WowProc>();
            var mine = ps.Where(p => !string.IsNullOrEmpty(p.Path) &&
                string.Equals(System.IO.Path.GetDirectoryName(p.Path)?.TrimEnd('\\', '/'), dir, StringComparison.OrdinalIgnoreCase)).ToList();
            var unread = ps.Count(p => string.IsNullOrEmpty(p.Path));
            if (mine.Count == 0)
                return new Close { Wow = unread > 0 ? "left" : "none", Why = unread > 0 ? "a WoW whose folder can't be read is open: not closed" : "" };
            var r = new Close { Wow = "closed" };
            foreach (var p in mine)
            {
                if (quitAsked)
                {
                    Log.I("session end: waiting for WoW's own Exit Game (pid " + p.Pid + ", " + selfWaitMs / 1000 + " s at most)");
                    if (waitExit(p.Pid, selfWaitMs)) { r.How = Worst(r.How, "exit"); continue; }
                }
                Log.I("session end: closing WoW's window (pid " + p.Pid + ")");
                try { closeWindow(p.Pid); } catch (Exception) { }
                if (waitExit(p.Pid, closeWaitMs)) { r.How = Worst(r.How, "window"); continue; }
                Log.I("session end: WoW still open " + closeWaitMs / 1000 + " s after its window's close: ending it (pid " + p.Pid + ")");
                if (!kill(p.Pid) || !waitExit(p.Pid, killWaitMs)) return new Close { Wow = "left", Why = "WoW didn't close", How = r.How };
                r.How = Worst(r.How, "ended");
            }
            if (unread > 0) r.Why = "another WoW whose folder can't be read stays open";
            return r;
        }

        static string Worst(string a, string b)
        {
            int Rank(string s) => s == "ended" ? 3 : s == "window" ? 2 : s == "exit" ? 1 : 0;
            return Rank(b) > Rank(a) ? b : a;
        }
    }
}
