using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using DsfDesktop.Bridge;
using DsfDesktop.Wow;

namespace DsfDesktop.App
{
    /// <summary>
    /// THE THOR'S ADD-ONS ON THE PC, the app's side (PcAddons does the work): at Desktop's start (QuestieDB, and the last Thor's list),
    /// at every Thor connect ("addons_want"), and right before Desktop starts WoW. One run at a time, in the background; a run asked for
    /// while one works runs right after it with the newest list. Nothing moves while WoW runs on the PC (PcAddons checks before every
    /// move). The Thor sees each run's progress and its outcome ("addons_pc").
    /// </summary>
    public sealed partial class AppController
    {
        PcAddons pcAddons;
        readonly object pcGate = new object();
        Task pcTask;
        Tuple<string, PcWant, IPcTransfer> pcNext;
        PcTransfer pcTransfer;
        /// <summary>the last run's line (the window's add-ons row)</summary>
        public string PcAddonLine { get; private set; } = "";
        /// <summary>a start of WoW waits at most this long for a wanted add-on that is missing entirely (QuestieDB above all)</summary>
        public const double PcWaitS = 90;

        PcAddons Pc()
        {
            lock (pcGate) return pcAddons ?? (pcAddons = new PcAddons(Paths.DataDir, new PcHttp(), WowRunsNow) { Progress = PcProgress });
        }

        DateTime pcSaidAt;
        void PcProgress(string line, long done, long total)
        {
            if (DateTime.UtcNow - pcSaidAt < TimeSpan.FromSeconds(1) && done < total) return;
            pcSaidAt = DateTime.UtcNow;
            Bridge?.Publish(new Dictionary<string, object> { ["t"] = "addons_pc", ["state"] = "working", ["line"] = line, ["done"] = done, ["total"] = total });
        }

        /// <summary>Desktop's start: QuestieDB, then whatever the last Thor wanted (its imported add-ons wait for a Thor)</summary>
        void PcAddonsAtStart() => PcRun("Desktop start", Pc().LoadWant(), null);

        public void AddonsWant(Dictionary<string, object> m, BridgeSession session)
        {
            var w = PcWant.Parse(m, out var why);
            if (w == null) { Log.I("pc add-ons: the Thor's list not read (" + why + ")"); return; }
            Pc().SaveWant(w);
            var t = new PcTransfer(session);
            lock (pcGate) { pcTransfer?.Stop(); pcTransfer = t; }
            Log.I("pc add-ons: the Thor's list: " + w.Managed.Count(p => p.Want) + " managed wanted, " + w.Imported.Count + " imported, " + w.Enabled.Count + " on/off");
            PcRun("a Thor connected", w, t);
        }

        public void AddonFile(Dictionary<string, object> m) { PcTransfer t; lock (pcGate) t = pcTransfer; t?.Answer(m); }

        Task PcRun(string why, PcWant w, IPcTransfer t)
        {
            lock (pcGate)
            {
                if (pcTask != null && !pcTask.IsCompleted) { pcNext = Tuple.Create(why, w, t); return pcTask; }
                return pcTask = Task.Run(() => PcLoop(why, w, t));
            }
        }

        void PcLoop(string why, PcWant w, IPcTransfer t)
        {
            while (true)
            {
                try
                {
                    var dir = FlavorDirOrNull;
                    if (dir == null) { PcAddonLine = "game folder not found"; Log.I("pc add-ons (" + why + "): game folder not found"); }
                    else
                    {
                        // the enabled state goes into the account the Thor signs in with (its hash; WowSvSync's pick), else every account
                        if (w.FromThor) w.AcctDir = WowSvSync.PickAccount(Path.Combine(dir, "WTF"), w.Acct, gameAccountList, out _);
                        var r = Pc().Run(dir, w, t);
                        PcAddonLine = r.Line();
                        Log.I("pc add-ons (" + why + "): " + PcAddonLine);
                        var q = r.Of("QuestieDB");
                        if (q != null && (q.State == "failed" || q.State == "deferred")) Log.I("pc add-ons: QuestieDB NOT installed (" + q.Why + "): the map's quest givers need it");
                        var msg = r.ToJson();
                        msg["t"] = "addons_pc"; msg["state"] = "done";
                        Bridge?.Publish(msg);
                    }
                }
                catch (Exception e) { Log.I("pc add-ons: " + e.Message); }
                lock (pcGate)
                {
                    if (pcNext == null) return;
                    why = pcNext.Item1; w = pcNext.Item2; t = pcNext.Item3; pcNext = null;
                }
                Fire();
            }
        }

        /// <summary>right before Desktop starts WoW: a run (moves happen only now, WoW not up yet); the start waits for it only while a
        /// wanted add-on is missing entirely (QuestieDB first), at most <see cref="PcWaitS"/></summary>
        void PcBeforeStart()
        {
            var dir = FlavorDirOrNull;
            if (dir == null) return;
            var w = Pc().LoadWant();
            Task task;
            PcTransfer tr;
            lock (pcGate) tr = pcTransfer;
            task = PcRun("before WoW starts", w, tr);
            var missing = MissingWanted(dir, w);
            if (missing.Count == 0) return;
            Log.I("pc add-ons: WoW waits for " + string.Join(", ", missing) + " (at most " + (int)PcWaitS + " s)");
            var until = DateTime.UtcNow.AddSeconds(PcWaitS);
            while (DateTime.UtcNow < until)
            {
                if (task.Wait(500)) { lock (pcGate) task = pcTask; if (task.IsCompleted || MissingWanted(dir, w).Count == 0) break; }
            }
            var left = MissingWanted(dir, w);
            if (left.Count > 0) Log.I("pc add-ons: WoW starts without " + string.Join(", ", left));
        }

        /// <summary>the wanted add-ons with none of their folders in AddOns (QuestieDB; each wanted managed package's first folder)</summary>
        static List<string> MissingWanted(string flavorDir, PcWant w)
        {
            var addons = Path.Combine(flavorDir, "Interface", "AddOns");
            var res = new List<string>();
            if (w.QuestieDb && !Directory.Exists(Path.Combine(addons, "QuestieDB"))) res.Add("QuestieDB");
            // a big pack (Spoken's voice packs) never holds WoW's start: it goes in at a later run
            foreach (var p in w.Managed.Where(p => p.Want && !PcAddons.IsBig(p) && p.Invalid() == null && p.Folders.Count > 0))
                if (!Directory.Exists(Path.Combine(addons, p.Folders[0]))) res.Add(p.Name);
            return res;
        }
    }

    /// <summary>an imported add-on's files from the Thor, a part at a time ("addon_file_get" -> "addon_file"); 30 s per part</summary>
    sealed class PcTransfer : IPcTransfer
    {
        readonly BridgeSession s;
        readonly ConcurrentDictionary<string, TaskCompletionSource<Dictionary<string, object>>> waiting = new ConcurrentDictionary<string, TaskCompletionSource<Dictionary<string, object>>>();
        int next;
        volatile bool stopped;
        public static TimeSpan Timeout = TimeSpan.FromSeconds(30);

        public PcTransfer(BridgeSession s) { this.s = s; }

        public void Stop() { stopped = true; foreach (var w in waiting.Values) w.TrySetResult(null); }

        public byte[] Get(string folder, string path, long offset, int length)
        {
            if (stopped) return null;
            var id = Interlocked.Increment(ref next).ToString(System.Globalization.CultureInfo.InvariantCulture);
            var tcs = new TaskCompletionSource<Dictionary<string, object>>();
            waiting[id] = tcs;
            s.Enqueue(new Dictionary<string, object> { ["t"] = "addon_file_get", ["id"] = id, ["folder"] = folder, ["p"] = path, ["off"] = offset, ["len"] = length });
            var got = tcs.Task.Wait(Timeout) ? tcs.Task.Result : null;
            waiting.TryRemove(id, out _);
            if (got == null) return null;
            if (got.Str("t") == "addon_file_err") throw new IOException("the Thor couldn't send " + path + " (" + (got.Str("why") ?? "?") + ")");
            if (got.Long("off", -1) != offset || got.Str("p") != path) throw new IOException("the Thor sent another part");
            return Convert.FromBase64String(got.Str("data") ?? "");
        }

        public void Answer(Dictionary<string, object> m)
        {
            var id = m.Str("id");
            if (id != null && waiting.TryGetValue(id, out var t)) t.TrySetResult(m);
        }
    }
}
