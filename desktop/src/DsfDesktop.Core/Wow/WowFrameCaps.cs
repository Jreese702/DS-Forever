using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// STREAM SMOOTHNESS (a tester's report 2026-11-23: "the game runs at about half its FPS" while streaming): what the game's own
    /// Config.wtf says about its frame rate, for one desktop.log line at a stream's start - V-sync, the foreground cap (maxFPS /
    /// useMaxFPS), the background cap (maxFPSBk / useMaxFPSBk: the game drops to it when its window is not in front) and the window mode.
    /// READ ONLY: the file is opened for reading with every share flag (the game may hold it), never written, never kept; the leave-no-trace
    /// rule. A key the file lacks is the game's default ("-"). Values are cut to short plain words (nothing else of the file is logged).
    /// </summary>
    public static class WowFrameCaps
    {
        /// <summary>the keys looked at, in the line's order</summary>
        public static readonly string[] Keys = { "vsync", "maxFPS", "useMaxFPS", "maxFPSBk", "useMaxFPSBk", "gxMaximize", "gxWindow" };

        static readonly Regex Plain = new Regex(@"^[0-9A-Za-z.\-]{1,12}$", RegexOptions.Compiled);

        /// <summary>key -> value for <see cref="Keys"/> found in [text] (the last line of a key wins, as the game reads it). Pure</summary>
        public static Dictionary<string, string> Parse(string text)
        {
            var r = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (var raw in (text ?? "").Split('\n'))
            {
                var l = raw.TrimEnd('\r').Trim();
                if (!l.StartsWith("SET ", StringComparison.OrdinalIgnoreCase)) continue;
                var rest = l.Substring(4).TrimStart();
                int sp = rest.IndexOf(' ');
                if (sp <= 0) continue;
                var k = rest.Substring(0, sp);
                var key = Keys.FirstOrDefault(x => string.Equals(x, k, StringComparison.OrdinalIgnoreCase));
                if (key == null) continue;
                var v = rest.Substring(sp + 1).Trim().Trim('"');
                r[key] = Plain.IsMatch(v) ? v : "?";
            }
            return r;
        }

        /// <summary>the log's words: "vsync 1, maxFPS 120 (useMaxFPS 1), maxFPSBk 30 (useMaxFPSBk 1), gxMaximize 1, gxWindow -". Pure</summary>
        public static string Word(string text)
        {
            var d = Parse(text);
            string V(string k) => d.TryGetValue(k, out var v) ? v : "-";
            return "vsync " + V("vsync") + ", maxFPS " + V("maxFPS") + " (useMaxFPS " + V("useMaxFPS") + "), maxFPSBk " + V("maxFPSBk") +
                   " (useMaxFPSBk " + V("useMaxFPSBk") + "), gxMaximize " + V("gxMaximize") + ", gxWindow " + V("gxWindow") + " (- = the game's default)";
        }

        /// <summary>the file at [path] read (only read) and put in <see cref="Word"/>; a missing or unreadable file says so</summary>
        public static string Read(string path)
        {
            try
            {
                if (!File.Exists(path)) return "Config.wtf not there yet";
                using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                {
                    if (fs.Length > 4 * 1024 * 1024) return "Config.wtf too big to read";
                    var bytes = new byte[fs.Length];
                    int n = 0;
                    while (n < bytes.Length) { int got = fs.Read(bytes, n, bytes.Length - n); if (got <= 0) break; n += got; }
                    return Word(System.Text.Encoding.GetEncoding(28591).GetString(bytes, 0, n));
                }
            }
            catch (Exception e) { return "Config.wtf unreadable (" + e.GetType().Name + ")"; }
        }

        /// <summary>one screen's words: "\\.\DISPLAY1 1920x1080@60 (top 144)" (+ " virtual"). Pure</summary>
        public static string ScreenWord(string name, bool isVirtual, int w, int h, int hz, int topHz) =>
            (name ?? "?") + (isVirtual ? " virtual" : "") + " " + (w > 0 ? w + "x" + h + "@" + hz : "mode unreadable") + (topHz > 0 ? " (top " + topHz + ")" : "");
    }
}
