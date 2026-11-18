using System;
using System.IO;
using System.Linq;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// THE CONTROLLER ON THE PC'S WOW (the user's pick 2026-10-30: "Desktop sets it"; the stream's pad was not taken as in Local): the two
    /// settings the Thor's Local client forces (GameConfigWtf), written before WoW starts, while no client can overwrite them.
    /// </summary>
    public static class WowConfig
    {
        public static readonly string[] Keys = { "GamePadEnable", "InputDeviceInterfaceStyle" };

        /// <summary>[text] with both settings on; unchanged when they already are</summary>
        public static string Apply(string text)
        {
            var nl = text.Contains("\r\n") ? "\r\n" : "\n";
            var lines = text.Split(new[] { nl }, StringSplitOptions.None).ToList();
            if (lines.Count > 0 && lines[lines.Count - 1].Length == 0) lines.RemoveAt(lines.Count - 1);
            foreach (var k in Keys)
            {
                var want = "SET " + k + " \"1\"";
                int at = lines.FindIndex(l => l.StartsWith("SET " + k + " ", StringComparison.OrdinalIgnoreCase));
                if (at >= 0) lines[at] = want; else lines.Add(want);
            }
            return string.Join(nl, lines) + nl;
        }

        /// <summary>
        /// fix/1492-feedback (the user, 2026-11-18, test 1492: "while it said it was 1080p it certainly did not look like my UI was for
        /// 1080P"): THE STREAM'S WINDOW. The PC's Config.wtf keeps the player's own window (GxWindowedResolution "2560x1440" on the
        /// user's PC); with "Match PC screen to stream" Sunshine now really switches the monitor to the Thor's mode (1920x1080@120 at
        /// 15:07:46, two seconds before WoW started), so WoW is started for THAT screen: windowed fullscreen (GxMaximize 1) at the
        /// stream's size (GxWindowedResolution / GxFullscreenResolution). Only these three keys' lines; WowConfigKeep records the
        /// player's own lines first (RecordWindow) and puts them back after the session, as it does for the window WoW saves.
        /// </summary>
        public static readonly string[] StreamWindowKeys = { "GxWindowedResolution", "GxFullscreenResolution", "GxMaximize" };

        /// <summary>[text] with the stream's window: [w]x[h] (null = the size left as it is, only maximized), maximized</summary>
        public static string ApplyWindow(string text, int? w, int? h)
        {
            var nl = text.Contains("\r\n") ? "\r\n" : "\n";
            var lines = text.Split(new[] { nl }, StringSplitOptions.None).ToList();
            if (lines.Count > 0 && lines[lines.Count - 1].Length == 0) lines.RemoveAt(lines.Count - 1);
            void Set(string k, string v)
            {
                int at = lines.FindIndex(l => l.StartsWith("SET " + k + " ", StringComparison.OrdinalIgnoreCase));
                if (at >= 0)
                {
                    // the key spelled as the file has it (WoW writes "GxMaximize", other installs "gxMaximize")
                    var spelled = lines[at].Substring(4, k.Length);
                    lines[at] = "SET " + spelled + " \"" + v + "\"";
                }
                else lines.Add("SET " + k + " \"" + v + "\"");
            }
            if (w.HasValue && h.HasValue && w > 0 && h > 0)
            {
                Set("GxWindowedResolution", w + "x" + h);
                Set("GxFullscreenResolution", w + "x" + h);
            }
            Set("GxMaximize", "1");
            return string.Join(nl, lines) + nl;
        }

        /// <summary>the stream's window into Config.wtf at [path] (<see cref="ApplyWindow"/>) - ONLY when [keep] has recorded the player's
        /// own window lines first (never a write that could not be put back). Never fails the start</summary>
        public static void EnsureWindow(string path, WowConfigKeep keep, int? w, int? h)
        {
            try
            {
                if (!File.Exists(path)) return;
                if (keep == null || !keep.RecordWindow(path)) { Log.I("WoW config: the stream's window not set (the player's window not recorded)"); return; }
                var latin1 = System.Text.Encoding.GetEncoding(28591);         // every byte round-trips (WowConfigKeep's rule)
                var was = latin1.GetString(File.ReadAllBytes(path));
                var now = ApplyWindow(was, w, h);
                if (now == was) { Log.I("WoW config: window already the stream's" + (w.HasValue ? " (" + w + "x" + h + ")" : "")); return; }
                File.WriteAllBytes(path, latin1.GetBytes(now));
                Log.I("WoW config: window for the stream: " + (w.HasValue ? w + "x" + h + ", " : "") + "maximized (the player's put back after the session)");
            }
            catch (Exception e) { Log.I("WoW config: window: " + e.Message); }
        }

        /// <summary>the UI scale lines (values only), for the log: "useUiScale 1, uiScale 0.64" / "not set in Config.wtf ..."</summary>
        public static string UiScaleWord(string text)
        {
            string Val(string k)
            {
                var l = (text ?? "").Split('\n').Select(x => x.TrimEnd('\r')).FirstOrDefault(x => x.StartsWith("SET " + k + " ", StringComparison.OrdinalIgnoreCase));
                return l?.Substring(4 + k.Length).Trim().Trim('"');
            }
            var use = Val("useUiScale"); var sc = Val("uiScale");
            return use == null && sc == null ? "not set in Config.wtf (the game's default)" : "useUiScale " + (use ?? "-") + ", uiScale " + (sc ?? "-");
        }

        /// <summary>a game never started has no file yet; failing here never stops the game starting. [keep] records the player's
        /// own lines first, to be put back once the session is over (WowConfigKeep)</summary>
        public static void Ensure(string path, WowConfigKeep keep = null)
        {
            try
            {
                if (!File.Exists(path)) return;
                var was = File.ReadAllText(path);
                var now = Apply(was);
                if (now == was) return;
                keep?.Record(path, was, now);
                File.WriteAllText(path, now);
                Log.I("WoW config: controller on");
            }
            catch (Exception e) { Log.I("WoW config: " + e.Message); }
        }
    }
}
