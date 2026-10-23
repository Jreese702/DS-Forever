using System;
using System.IO;

namespace DsfDesktop
{
    /// <summary>the app's own log (DataDir\desktop.log, one previous generation kept). Never the game's files.</summary>
    public static class Log
    {
        static readonly object Gate = new object();
        static string path;
        public static event Action<string> Line;

        public static void Init(string file)
        {
            lock (Gate)
            {
                path = file;
                try
                {
                    var fi = new FileInfo(file);
                    if (fi.Exists && fi.Length > 4 * 1024 * 1024) File.Copy(file, file + ".1", true);
                    if (fi.Exists && fi.Length > 4 * 1024 * 1024) File.WriteAllText(file, "");
                }
                catch (Exception) { }
            }
        }

        public static void I(string msg)
        {
            var line = DateTime.Now.ToString("MM-dd HH:mm:ss.fff") + "  " + msg;
            lock (Gate)
            {
                if (path != null) try { File.AppendAllText(path, line + Environment.NewLine); } catch (Exception) { }
            }
            try { Line?.Invoke(line); } catch (Exception) { }
        }
    }
}
