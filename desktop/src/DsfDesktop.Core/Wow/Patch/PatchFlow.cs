using System;
using System.Collections.Generic;

namespace DsfDesktop.Wow.Patch
{
    /// <summary>
    /// When the self-patcher runs (the user's rulings 2026-11-18): BY ITSELF as soon as the PC game is found outdated (or without its
    /// install record) while a Thor is here, ONE automatic attempt per connect; after that only the Thor's Retry starts one, one per
    /// press; the Thor's Cancel (B) stops one. A patch that is done starts WoW once (<see cref="afterDone"/>). Pure but for the
    /// injected pieces, so tests drive it without a game or a CDN.
    /// </summary>
    public sealed class PatchFlow
    {
        readonly Func<PatchStatus> status;
        readonly Func<string, bool> start;
        readonly Func<bool> cancel;
        readonly Func<GameCheck> game;
        readonly Action afterDone;
        readonly object gate = new object();
        bool tried;
        PatchState seen = PatchState.Idle;

        /// <param name="status">the patcher's status now</param>
        /// <param name="start">one attempt in the background (PcGamePatcher.Start); false = one runs already</param>
        /// <param name="cancel">the player's Cancel (PcGamePatcher.Cancel)</param>
        /// <param name="game">the PC game against Blizzard's version (GameVersionCheck)</param>
        /// <param name="afterDone">a patch was done: the game is checked again and WoW started once</param>
        public PatchFlow(Func<PatchStatus> status, Func<string, bool> start, Func<bool> cancel, Func<GameCheck> game, Action afterDone)
        {
            this.status = status; this.start = start; this.cancel = cancel; this.game = game; this.afterDone = afterDone;
        }

        public static bool Needed(GameCheck g) => g == GameCheck.Outdated || g == GameCheck.NeedsRepair;

        /// <summary>a Thor connected: one automatic attempt again</summary>
        public void Connect() { lock (gate) tried = false; }

        /// <summary>every tick: [wanted] = a Thor is here and Desktop starts WoW for it. A patch that just finished starts WoW once</summary>
        public void Tick(bool wanted)
        {
            var s = status();
            bool done;
            lock (gate)
            {
                done = s.State == PatchState.Done && seen != PatchState.Done;
                seen = s.State;
            }
            if (done) { try { afterDone(); } catch (Exception e) { Log.I("game patch: after it: " + e.Message); } }
            if (!wanted || s.Busy) return;
            if (!Needed(game())) return;
            lock (gate)
            {
                if (tried) return;
                tried = true;
            }
            start("the PC game is " + (game() == GameCheck.Outdated ? "outdated" : "missing its install record") + ": by itself, once per connect");
        }

        /// <summary>the Thor's Retry: true = it was the patch's (one attempt started, or one runs already); false = not a patch matter</summary>
        public bool Retry()
        {
            var s = status();
            if (s.Busy) { Log.I("game patch: the Thor's Retry while it runs: that one counts"); return true; }
            bool mine = s.State == PatchState.Failed || s.State == PatchState.Cancelled || Needed(game());
            if (!mine) return false;
            lock (gate) tried = true;
            start("the Thor's Retry");
            return true;
        }

        public bool Cancel() => cancel();

        /// <summary>
        /// The "wow" message's pc_patch (the Thor's bar and its rows): {state, from, to, done, total, file, file_no, files, code,
        /// need_mb, eta_s}; null = nothing to show. A patch that is done says "loading" while the new client starts ([starting]: WoW
        /// streams the new build's data then), else nothing.
        /// </summary>
        public static Dictionary<string, object> Words(PatchStatus s, bool starting)
        {
            if (s == null || s.State == PatchState.Idle) return null;
            if (s.State == PatchState.Done && !starting) return null;
            var d = new Dictionary<string, object>
            {
                ["state"] = Word(s.State),
                ["from"] = GameVersion.Short(s.From),
                ["to"] = GameVersion.Short(s.To),
            };
            if (s.State == PatchState.Done) return d;
            d["done"] = s.Done;
            d["total"] = s.Total;
            if (s.Files > 0) { d["files"] = s.Files; d["file_no"] = s.FileNo; }
            if (!string.IsNullOrEmpty(s.File)) d["file"] = s.File;
            if (!string.IsNullOrEmpty(s.Code)) d["code"] = s.Code;
            if (s.NeedMb > 0) d["need_mb"] = s.NeedMb;
            if (s.EtaS >= 0) d["eta_s"] = (long)Math.Round(s.EtaS);
            return d;
        }

        public static string Word(PatchState s)
        {
            switch (s)
            {
                case PatchState.Checking: return "checking";
                case PatchState.Waiting: return "waiting";
                case PatchState.Downloading: return "downloading";
                case PatchState.Installing: return "installing";
                case PatchState.Done: return "loading";
                case PatchState.Failed: return "failed";
                case PatchState.Cancelled: return "cancelled";
                default: return "idle";
            }
        }

        /// <summary>one line in English (the log; an older Thor's "why", which has no bar): never "on the PC"</summary>
        public static string Line(PatchStatus s)
        {
            if (s == null) return null;
            var head = "Updating the game" + (s.From.Length > 0 && s.To.Length > 0 ? " " + GameVersion.Short(s.From) + " -> " + GameVersion.Short(s.To) : "");
            switch (s.State)
            {
                case PatchState.Checking: return head;
                case PatchState.Waiting: return "Blizzard's updater is busy, waiting...";
                case PatchState.Downloading: return head + " (" + s.Done / 1000000 + " / " + s.Total / 1000000 + " MB)";
                case PatchState.Installing: return "Installing...";
                case PatchState.Done: return "Loading new game data...";
                case PatchState.Cancelled: return "Update cancelled";
                case PatchState.Failed: return "Update failed";
                default: return null;
            }
        }
    }
}
