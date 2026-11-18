using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// THE PLAYER'S OWN CONTROLLER SETTINGS PUT BACK (the user, 2026-11-18: "that would be a really cool thing to add, yeah lets do it"):
    /// <see cref="WowConfig"/> turns the game's controller mode on in the PC's Config.wtf before a stream's WoW starts, and WoW keeps it,
    /// so the player's next game on the PC itself was still in controller mode. Now:
    ///  - Before Desktop's write, each key it changes is recorded with the line it had (or "absent"), one small JSON per Config.wtf in
    ///    Desktop's data folder; a record already there (a session not yet put back) is kept, only keys new to it are added.
    ///  - Once WoW is closed (WoW rewrites Config.wtf as it exits: never touched while any client runs), no DS Forever session is
    ///    active (no stream, no WoW starting) and that has held for <see cref="Grace"/> with the file unwritten for
    ///    <see cref="Settle"/>, each key goes back: its old line, or the line removed when it had none - but only where the file still
    ///    holds what Desktop wrote (a value the player changed in the game is theirs). A Thor reconnecting or a Retry restarting WoW
    ///    inside the grace keeps controller mode on; a later session records afresh. A record left by a crash goes back the same way
    ///    after Desktop's next start.
    ///  - Only these keys' lines are ever looked at or changed; every other byte (account lines included) stays as it was, the line
    ///    endings too (the file is handled as Latin-1, one char per byte), replaced whole through a temp file (File.Replace).
    ///  - THE WINDOW TOO (the user, 2026-11-18: "the PC resolution should also be restored after streaming is done"): WoW saves the
    ///    window it ran in as it closes (<see cref="WindowKeys"/>: GxWindowedResolution, GxFullscreenResolution, GxMaximize, GxMonitor,
    ///    gxWindow, gxRefresh), and during a stream that is the streamed screen's. Their lines are recorded as they are before the
    ///    session (before Desktop starts WoW, or at the stream's start when WoW was already up: the file then holds what WoW saved
    ///    when it last closed) and put back by the same rule of when, but whatever the file holds then: a pick in the game's options
    ///    during a stream can't be told from the stream's own mode, so the player's pre-session window wins.
    ///  - The log names the keys and their values only (never another line).
    /// </summary>
    public sealed class WowConfigKeep
    {
        readonly string dir;
        readonly Func<bool> wowRunning;
        readonly Func<bool> sessionActive;
        readonly Func<DateTime> now;
        readonly object gate = new object();
        DateTime? quietSince;

        /// <summary>no session and no WoW for this long before anything is put back (a Thor reconnecting, a Retry's restart)</summary>
        public TimeSpan Grace = TimeSpan.FromSeconds(30);
        /// <summary>Config.wtf unwritten for this long (WoW's own write as it closes is done)</summary>
        public TimeSpan Settle = TimeSpan.FromSeconds(5);

        /// <summary>the window WoW saves as it closes (the PC's Config.wtf, 2026-11-18: GxWindowedResolution "2560x1440",
        /// GxFullscreenResolution, GxMaximize, GxMonitor; gxWindow and gxRefresh on other installs)</summary>
        public static readonly string[] WindowKeys = { "GxWindowedResolution", "GxFullscreenResolution", "GxMaximize", "GxMonitor", "gxWindow", "gxRefresh" };

        static readonly Encoding Bytes = Encoding.GetEncoding(28591);   // Latin-1: every byte round-trips unchanged

        public WowConfigKeep(string dir, Func<bool> wowRunning, Func<bool> sessionActive, Func<DateTime> now = null)
        {
            this.dir = dir; this.wowRunning = wowRunning; this.sessionActive = sessionActive;
            this.now = now ?? (() => DateTime.UtcNow);
        }

        /// <summary>the record file for [config] (its flavor folder's name and a short hash of the whole path)</summary>
        public string RecordPath(string config)
        {
            var full = Path.GetFullPath(config);
            var flavor = Path.GetFileName(Path.GetDirectoryName(Path.GetDirectoryName(full)) ?? "") ?? "";
            flavor = new string(flavor.Where(c => char.IsLetterOrDigit(c) || c == '_' || c == '-').ToArray());
            string hash;
            using (var sha = SHA256.Create())
                hash = string.Concat(sha.ComputeHash(Encoding.UTF8.GetBytes(full.ToLowerInvariant())).Take(4).Select(b => b.ToString("x2")));
            return Path.Combine(dir, "controller-" + (flavor.Length > 0 ? flavor : "wow") + "-" + hash + ".json");
        }

        // ---------------------------------------------------------------- lines of the keys (nothing else is looked at)

        internal static bool IsKeyLine(string line, string key) =>
            line.IndexOf('\n') < 0 && line.StartsWith("SET " + key + " ", StringComparison.OrdinalIgnoreCase);

        /// <summary>the first line setting [key] in [text] (as WowConfig.Apply finds it), null = absent</summary>
        internal static string KeyLine(string text, string key) =>
            Lines(text, out _).FirstOrDefault(l => IsKeyLine(l, key));

        static List<string> Lines(string text, out string nl)
        {
            nl = text.Contains("\r\n") ? "\r\n" : "\n";
            return text.Split(new[] { nl }, StringSplitOptions.None).ToList();
        }

        static string Value(string line) => line == null ? null : line.Substring(line.IndexOf(' ', 4) + 1).Trim().Trim('"');

        /// <summary>"2560x1440" when the windowed size went back, else the keys and values</summary>
        static string WindowWord(List<string> said)
        {
            var size = said.FirstOrDefault(x => x.StartsWith("GxWindowedResolution ", StringComparison.OrdinalIgnoreCase))
                       ?? said.FirstOrDefault(x => x.StartsWith("GxFullscreenResolution ", StringComparison.OrdinalIgnoreCase));
            return size != null && !size.EndsWith(" removed") ? size.Substring(size.IndexOf(' ') + 1) : "(" + string.Join(", ", said) + ")";
        }

        static string Read(string path) => Bytes.GetString(File.ReadAllBytes(path));

        // ---------------------------------------------------------------- record (before Desktop's write)

        /// <summary>Config.wtf at [path] is about to go from [was] to [now] (WowConfig): each key that changes is recorded with its old
        /// line first (unless a record already holds it). False when the record couldn't be saved</summary>
        public bool Record(string path, string was, string now)
        {
            lock (gate)
            {
                try
                {
                    var rec = Load(RecordPath(path)) ?? new Dictionary<string, object> { ["config"] = Path.GetFullPath(path), ["keys"] = new Dictionary<string, object>() };
                    var keys = (Dictionary<string, object>)rec["keys"];
                    bool added = false;
                    foreach (var k in WowConfig.Keys)
                    {
                        var before = KeyLine(was, k);
                        var after = KeyLine(now, k);
                        if (after == null || before == after || keys.ContainsKey(k)) continue;
                        keys[k] = new Dictionary<string, object> { ["was"] = before, ["wrote"] = after };
                        added = true;
                    }
                    if (added) Save(path, rec);
                    return true;
                }
                catch (Exception e) { Log.I("WoW config: player's settings not recorded (" + e.GetType().Name + ")"); return false; }
            }
        }

        /// <summary>the window lines of Config.wtf at [path] as they are now (absent ones too), before a DS Forever session; a record
        /// already holding them (a session not yet put back) is kept. Only reads the file: fine while WoW runs. False = not recorded</summary>
        public bool RecordWindow(string path)
        {
            lock (gate)
            {
                try
                {
                    if (path == null || !File.Exists(path)) return false;
                    var text = Read(path);
                    var rec = Load(RecordPath(path)) ?? new Dictionary<string, object> { ["config"] = Path.GetFullPath(path), ["keys"] = new Dictionary<string, object>() };
                    var keys = (Dictionary<string, object>)rec["keys"];
                    bool added = false;
                    foreach (var k in WindowKeys)
                    {
                        if (keys.ContainsKey(k)) continue;
                        keys[k] = new Dictionary<string, object> { ["was"] = KeyLine(text, k), ["window"] = true };
                        added = true;
                    }
                    if (added) Save(path, rec);
                    return true;
                }
                catch (Exception e) { Log.I("WoW config: window not recorded (" + e.GetType().Name + ")"); return false; }
            }
        }

        void Save(string path, Dictionary<string, object> rec)
        {
            Directory.CreateDirectory(dir);
            var file = RecordPath(path);
            File.WriteAllText(file + ".tmp", Json.Write(rec));
            if (File.Exists(file)) File.Replace(file + ".tmp", file, null); else File.Move(file + ".tmp", file);
        }

        static Dictionary<string, object> Load(string file)
        {
            if (!File.Exists(file)) return null;
            var d = Json.ReadObject(File.ReadAllText(file));
            if (d == null || d.Str("config") == null || !(d.TryGetValue("keys", out var k) && k is Dictionary<string, object>)) return null;
            return d;
        }

        /// <summary>a record waits to be put back</summary>
        public bool Pending => Directory.Exists(dir) && Directory.GetFiles(dir, "controller-*.json").Length > 0;

        // ---------------------------------------------------------------- put back (WoW closed, no session)

        /// <summary>every second or so: puts each record back once WoW is closed and no session has been active for the grace</summary>
        public void Tick()
        {
            lock (gate)
            {
                if (!Directory.Exists(dir)) { quietSince = null; return; }
                if (wowRunning() || sessionActive()) { quietSince = null; return; }
                var t = now();
                if (quietSince == null) { quietSince = t; return; }
                if (t - quietSince.Value < Grace) return;
                foreach (var file in Directory.GetFiles(dir, "controller-*.json"))
                {
                    if (wowRunning() || sessionActive()) { quietSince = null; return; }
                    try { PutBack(file, t); }
                    catch (Exception e) { Log.I("WoW config: controller mode not put back (" + e.GetType().Name + ")"); }
                }
            }
        }

        /// <summary>
        /// feat/pause-power: THE CLEAN DISCONNECT puts back NOW (the user, 2026-11-18: "revert everything back"): the Thor ended the
        /// session and Desktop closed the streamed WoW, so no grace and no session check (Sunshine's state can stay BUSY a while after
        /// the client goes) - but still never while any WoW runs, and still only once Config.wtf has settled (WoW's own write as it
        /// closed), waiting up to [waitMs] for that. True = nothing is left to put back
        /// </summary>
        public bool PutBackNow(int waitMs = 8000, Action<int> sleep = null)
        {
            sleep = sleep ?? (ms => System.Threading.Thread.Sleep(ms));
            int waited = 0;
            while (true)
            {
                lock (gate)
                {
                    if (!Directory.Exists(dir) || Directory.GetFiles(dir, "controller-*.json").Length == 0) return true;
                    if (wowRunning()) { Log.I("WoW config: not put back now (a WoW runs)"); return false; }
                    var t = now();
                    foreach (var file in Directory.GetFiles(dir, "controller-*.json"))
                    {
                        if (wowRunning()) return false;
                        try { PutBack(file, t, now: true); }
                        catch (Exception e) { Log.I("WoW config: controller mode not put back (" + e.GetType().Name + ")"); }
                    }
                    if (Directory.GetFiles(dir, "controller-*.json").Length == 0) { quietSince = null; return true; }
                }
                if (waited >= waitMs) { Log.I("WoW config: not put back yet (Config.wtf still settling): the usual put-back follows"); return false; }
                sleep(500); waited += 500;
            }
        }

        void PutBack(string file, DateTime t, bool now = false)
        {
            var rec = Load(file);
            if (rec == null) { File.Delete(file); return; }
            var cfg = rec.Str("config");
            if (!File.Exists(cfg)) { File.Delete(file); Log.I("WoW config: controller mode not put back (no Config.wtf)"); return; }
            if (t - File.GetLastWriteTimeUtc(cfg) < Settle) return;   // WoW's own write as it closed: next tick
            var text = Read(cfg);
            var lines = Lines(text, out var nl);
            var said = new List<string>();
            var window = new List<string>();
            var removeAt = new List<int>();
            foreach (var kv in (Dictionary<string, object>)rec["keys"])
            {
                var k = kv.Key;
                var e = kv.Value as Dictionary<string, object>;
                if (e == null) continue;
                var was = e.Str("was");
                if (e.Bool("window"))
                {
                    if (!WindowKeys.Contains(k)) continue;
                    int w = lines.FindIndex(l => IsKeyLine(l, k));
                    if (w >= 0 && was != null) { if (lines[w] != was) { lines[w] = was; window.Add(k + " " + Value(was)); } }
                    else if (w >= 0) { removeAt.Add(w); window.Add(k + " removed"); }
                    else if (was != null) { lines.Insert(lines.Count > 0 && lines[lines.Count - 1].Length == 0 ? lines.Count - 1 : lines.Count, was); window.Add(k + " " + Value(was)); }
                    continue;
                }
                if (!WowConfig.Keys.Contains(k)) continue;
                var wrote = e.Str("wrote");
                int at = lines.FindIndex(l => IsKeyLine(l, k));
                var cur = at >= 0 ? lines[at] : null;
                if (cur == null || wrote == null || Value(cur) != Value(wrote)) { said.Add(k + " kept, changed in game"); continue; }
                if (was != null) { lines[at] = was; said.Add(k + " " + Value(was)); }
                else { removeAt.Add(at); said.Add(k + " removed"); }
            }
            foreach (var i in removeAt.OrderByDescending(i => i)) lines.RemoveAt(i);
            var back = string.Join(nl, lines);
            if (back != text)
            {
                var tmp = cfg + ".dsf-tmp";
                try
                {
                    File.WriteAllBytes(tmp, Bytes.GetBytes(back));
                    // the last look before the write: a client starting now keeps controller mode, the record waits
                    if (wowRunning() || (!now && sessionActive())) { quietSince = null; return; }
                    File.Replace(tmp, cfg, null);
                }
                finally { try { if (File.Exists(tmp)) File.Delete(tmp); } catch (Exception) { } }
            }
            File.Delete(file);
            if (said.Count > 0) Log.I("WoW config: controller mode put back (" + string.Join(", ", said) + ")");
            if (window.Count > 0) Log.I("WoW config: window " + WindowWord(window) + " put back");
        }
    }
}
