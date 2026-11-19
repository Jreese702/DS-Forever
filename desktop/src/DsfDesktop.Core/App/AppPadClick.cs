using System;
using System.Collections.Generic;
using System.Linq;
using DsfDesktop.Wow;

namespace DsfDesktop.App
{
    /// <summary>
    /// 0.2.42, the user's picks 2026-11-18: DESKTOP CLICKS THE PAD while streaming (PadClick: the exact pixel in the _forever_ client,
    /// no cursor on the stream when WoW takes posted clicks) and POLESTAR PROGRESS FOLLOWS between Local and Streaming
    /// (PolestarSvSync: the account's and each character's Polestar.lua, the Thor merging). Both only reach here from a pinned wss
    /// session (BridgeServer).
    /// </summary>
    public sealed partial class AppController
    {
        PadClicker padClicker;
        readonly object padGate = new object();

        public void PadClick(Dictionary<string, object> m, Action<Dictionary<string, object>> done)
        {
            PadClicker c;
            lock (padGate)
                c = padClicker ?? (padClicker = new PadClicker(new Win32PadClickOs(() => FlavorDirOrNull, () => Stream == StreamState.Streaming)));
            c.Enqueue(m, done);
        }

        public Dictionary<string, object> PsRead(string acctHash)
        {
            lock (svGate)
            {
                if (WowRunsNow()) { Log.I("polestar sync: WoW is running; not read"); return new Dictionary<string, object> { ["state"] = "running" }; }
                var acct = SvAccountDir(acctHash, out var why);
                if (acct == null) { Log.I("polestar sync: nothing read (" + why + ")"); return new Dictionary<string, object> { ["state"] = "none", ["why"] = why }; }
                var files = PolestarSvSync.Read(acct);
                Log.I("polestar sync: read " + files.Count(f => f["exists"] is bool b && b) + " of " + files.Count + " Polestar files for the Thor");
                return new Dictionary<string, object> { ["state"] = "ok", ["acct"] = acctHash ?? "", ["files"] = files };
            }
        }

        public Dictionary<string, object> PsWrite(string acctHash, List<Dictionary<string, object>> docs)
        {
            lock (svGate)
            {
                var results = new List<Dictionary<string, object>>();
                var running = WowRunsNow();
                var acct = running ? null : SvAccountDir(acctHash, out _);
                var counts = new Dictionary<string, int>();
                foreach (var d in docs ?? new List<Dictionary<string, object>>())
                {
                    var key = d.Str("key");
                    var state = running ? "running" : acct == null ? "none"
                        : PolestarSvSync.Write(acct, key, d.Str("var"), d.Str("lua"), d.Long("n"), d.Str("if_hash"), DateTime.UtcNow);
                    counts[state] = counts.TryGetValue(state, out var n) ? n + 1 : 1;
                    results.Add(new Dictionary<string, object> { ["key"] = key ?? "?", ["state"] = state });
                }
                // one terse line: the outcomes counted (a character's key is never logged)
                Log.I("polestar sync: " + string.Join(", ", counts.Select(kv => kv.Value + " " + kv.Key)));
                return new Dictionary<string, object> { ["results"] = results };
            }
        }
    }
}
