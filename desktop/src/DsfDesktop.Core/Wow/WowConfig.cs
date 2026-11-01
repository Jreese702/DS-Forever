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
        static readonly string[] Keys = { "GamePadEnable", "InputDeviceInterfaceStyle" };

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

        /// <summary>a game never started has no file yet; failing here never stops the game starting</summary>
        public static void Ensure(string path)
        {
            try
            {
                if (!File.Exists(path)) return;
                var was = File.ReadAllText(path);
                var now = Apply(was);
                if (now == was) return;
                File.WriteAllText(path, now);
                Log.I("WoW config: controller on");
            }
            catch (Exception e) { Log.I("WoW config: " + e.Message); }
        }
    }
}
