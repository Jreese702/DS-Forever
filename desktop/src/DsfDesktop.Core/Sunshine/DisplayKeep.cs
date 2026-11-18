using System;
using System.Collections.Generic;
using System.IO;

namespace DsfDesktop.Sunshine
{
    /// <summary>
    /// THE PC'S SCREEN MODE PUT BACK AFTER A STREAM (the user, 2026-11-18: "the PC resolution should also be restored after streaming is
    /// done"). What changes it today: "Match PC screen to stream" (AppSettings.MatchStreamResolution, off while Apollo runs) writes our
    /// Sunshine's display-device options (SunshineConfig: dd_configuration_option = ensure_active, dd_resolution_option and
    /// dd_refresh_rate_option = auto, dd_config_revert_on_disconnect = enabled), so Sunshine itself sets the captured monitor to the
    /// Thor's resolution and rate as a stream starts and reverts it when the client goes. Sunshine keeps that revert in its own state
    /// file and retries it at its next start; this is the net under it, for a revert that didn't happen (Sunshine killed with Desktop,
    /// its revert refused, Desktop gone before Sunshine came back):
    ///  - while no stream runs, the captured monitor's mode (size, rate, place) is watched; the one that has held for
    ///    <see cref="Stable"/> is the player's;
    ///  - as a stream starts with Match on, that mode is recorded in Desktop's data folder (one small JSON), and the mode seen during
    ///    the stream with it;
    ///  - once no stream has run for <see cref="Grace"/> (a Thor reconnecting keeps it), the monitor is looked at: back to the player's =
    ///    Sunshine did it (the record goes); still the stream's mode = put back here; anything else = the player's own pick, kept;
    ///  - a record left by a crash is looked at the same way after Desktop's next start;
    ///  - only that one monitor's mode and place; never our own virtual screen (it goes away by itself), never with Match off or Apollo
    ///    running (Apollo's display settings are its own).
    /// </summary>
    public sealed class DisplayKeep
    {
        readonly string file;
        readonly IVddWindows win;
        readonly Func<DateTime> now;
        readonly object gate = new object();

        public TimeSpan Stable = TimeSpan.FromSeconds(5);
        public TimeSpan Grace = TimeSpan.FromSeconds(20);

        public sealed class Mode
        {
            public string Dev;
            public int W, H, Hz, X, Y;
            public bool Same(Mode o) => o != null && string.Equals(Dev, o.Dev, StringComparison.OrdinalIgnoreCase) && W == o.W && H == o.H && Hz == o.Hz;
            public bool SamePlace(Mode o) => Same(o) && X == o.X && Y == o.Y;
            public override string ToString() => W + "x" + H + "@" + Hz;
        }

        Mode seen, stable;
        DateTime seenAt;
        DateTime? quietSince;
        Mode before, during;

        public DisplayKeep(string file, IVddWindows win, Func<DateTime> now = null)
        {
            this.file = file; this.win = win; this.now = now ?? (() => DateTime.UtcNow);
            Load();
        }

        public bool Pending { get { lock (gate) return before != null; } }

        /// <summary>feat/pause-power: the Thor's clean disconnect: once no stream runs, the monitor is looked at with no grace (for the
        /// next <see cref="EndNowFor"/>; a stream starting again meanwhile keeps the usual rule)</summary>
        public void EndNow() { lock (gate) endNowAt = now(); }
        public TimeSpan EndNowFor = TimeSpan.FromMinutes(5);
        DateTime? endNowAt;
        bool Ending(DateTime t) => endNowAt.HasValue && t - endNowAt.Value < EndNowFor;

        Mode Read(string dev)
        {
            if (string.IsNullOrEmpty(dev)) return null;
            var m = win.Current(dev);
            if (m.w <= 0 || m.h <= 0) return null;
            var p = win.Position(dev);
            if (!p.HasValue) return null;
            return new Mode { Dev = dev, W = m.w, H = m.h, Hz = m.hz, X = p.Value.x, Y = p.Value.y };
        }

        /// <summary>every couple of seconds: [streaming] now, [dev] the captured monitor ("\\.\DISPLAY1"; null = none, or our own
        /// virtual screen), [match] Sunshine matches the screen to the stream (Match on, no Apollo)</summary>
        public void Tick(bool streaming, string dev, bool match)
        {
            lock (gate)
            {
                var t = now();
                var cur = Read(dev);
                if (streaming)
                {
                    quietSince = null;
                    if (before == null && match && dev != null)
                    {
                        var mine = stable ?? seen;
                        if (mine != null && string.Equals(mine.Dev, dev, StringComparison.OrdinalIgnoreCase)) { before = mine; during = null; Save(); }
                    }
                    // the stream's mode on that monitor (Sunshine's match), kept to tell it from a pick of the player's later
                    if (before != null && cur != null && string.Equals(cur.Dev, before.Dev, StringComparison.OrdinalIgnoreCase) && !cur.Same(before)
                        && (during == null || !during.SamePlace(cur))) { during = cur; Save(); }
                    seen = stable = null;
                    return;
                }
                if (before == null)
                {
                    // the player's own mode: the one that has held a moment (not one Sunshine is just setting)
                    if (cur == null) { seen = stable = null; return; }
                    if (seen == null || !seen.SamePlace(cur)) { seen = cur; seenAt = t; }
                    else if (t - seenAt >= Stable) stable = cur;
                    return;
                }
                if (quietSince == null) { quietSince = t; if (!Ending(t)) return; }
                if (!Ending(t) && t - quietSince.Value < Grace) return;
                var now2 = Read(before.Dev);
                if (now2 == null) return;   // the monitor isn't on now: the record waits for it
                if (now2.Same(before)) Log.I("display: " + before + " back");
                else if (during != null && now2.Same(during))
                {
                    var err = win.Set(before.Dev, before.W, before.H, before.Hz, before.X, before.Y);
                    Log.I("display: " + before + (err == null ? " put back" : " not put back (" + err + ")"));
                    if (err != null) { quietSince = t; return; }   // tried again after another grace
                }
                else Log.I("display: " + now2 + " kept (not the stream's mode)");
                before = during = null; seen = stable = null; endNowAt = null;
                Save();
            }
        }

        // ---------------------------------------------------------------- the record (Desktop's data folder)

        static Dictionary<string, object> Obj(Mode m) => m == null ? null : new Dictionary<string, object>
            { ["dev"] = m.Dev, ["w"] = m.W, ["h"] = m.H, ["hz"] = m.Hz, ["x"] = m.X, ["y"] = m.Y };

        static Mode FromObj(object o)
        {
            var d = o as Dictionary<string, object>;
            if (d == null || d.Str("dev") == null) return null;
            return new Mode { Dev = d.Str("dev"), W = (int)d.Long("w"), H = (int)d.Long("h"), Hz = (int)d.Long("hz"), X = (int)d.Long("x"), Y = (int)d.Long("y") };
        }

        void Save()
        {
            try
            {
                if (before == null) { if (File.Exists(file)) File.Delete(file); return; }
                Directory.CreateDirectory(Path.GetDirectoryName(file));
                File.WriteAllText(file + ".tmp", Json.Write(new Dictionary<string, object> { ["before"] = Obj(before), ["during"] = Obj(during) }));
                if (File.Exists(file)) File.Replace(file + ".tmp", file, null); else File.Move(file + ".tmp", file);
            }
            catch (Exception e) { Log.I("display: record not saved (" + e.GetType().Name + ")"); }
        }

        void Load()
        {
            try
            {
                if (!File.Exists(file)) return;
                var d = Json.ReadObject(File.ReadAllText(file));
                before = d != null && d.TryGetValue("before", out var b) ? FromObj(b) : null;
                during = before != null && d.TryGetValue("during", out var u) ? FromObj(u) : null;
                if (before == null) File.Delete(file);
                else Log.I("display: " + before + " recorded before a stream; looked at once no stream runs");
            }
            catch (Exception) { }
        }
    }
}
