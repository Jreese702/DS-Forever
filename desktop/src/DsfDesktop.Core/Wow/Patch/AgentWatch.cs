using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;

namespace DsfDesktop.Wow.Patch
{
    /// <summary>
    /// Is Blizzard's updater (the Battle.net Agent) working on the game folder? (the user's ruling 2026-11-18: then WAIT a few minutes,
    /// then Retry; never patch beside it.) Two signs, read only: its Operations log has an OP_UPDATE / OP_REPAIR / OP_INSTALL for the
    /// product that has not finished (measured on the PC 2026-11-18: "Active operation nullptr replaced by OP_UPDATE for 'wowf'" ...
    /// "Process finished OP_UPDATE for 'wowf'" / "OP_UPDATE for 'wowf' done Canceling"; times in UTC), or it wrote the folder's
    /// records (.build.info, .product.db, Data/config, Data/indices) in the last <see cref="WriteQuietS"/>.
    /// </summary>
    public static class AgentWatch
    {
        public const double WriteQuietS = 120;
        /// <summary>an operation started longer ago than this and never finished is the log of an Agent that stopped, not a busy one</summary>
        public const double StaleS = 1800;

        static readonly Regex Line = new Regex(@"^\[\w\s+(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)(?:\.\d+)?\]\s*(.*)$");

        /// <summary>pure: an unfinished operation on [product] in an Operations log ([nowUtc] for the staleness)</summary>
        public static bool LogBusy(string text, string product, DateTime nowUtc)
        {
            DateTime? started = null;
            var q = "'" + product + "'";
            foreach (var raw in (text ?? "").Replace("\r", "").Split('\n'))
            {
                var m = Line.Match(raw);
                if (!m.Success) continue;
                var msg = m.Groups[2].Value;
                if (msg.IndexOf(q, StringComparison.Ordinal) < 0) continue;
                bool op = Regex.IsMatch(msg, @"\bOP_(UPDATE|REPAIR|INSTALL|BACKFILL)\b");
                if (!op) continue;
                if (msg.StartsWith("Active operation", StringComparison.Ordinal) && msg.Contains("replaced by OP_"))
                {
                    if (DateTime.TryParseExact(m.Groups[1].Value, "yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture,
                            DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var t)) started = t;
                }
                else if (msg.StartsWith("Process finished", StringComparison.Ordinal) || Regex.IsMatch(msg, @"for " + Regex.Escape(q) + @" (done|completed|failed|canceled|cancelled)", RegexOptions.IgnoreCase))
                    started = null;
            }
            return started.HasValue && (nowUtc - started.Value).TotalSeconds < StaleS;
        }

        /// <summary>the newest Operations-*.log of the newest Agent.* folder under [agentDir] (C:\ProgramData\Battle.net\Agent), null = none</summary>
        public static string NewestLog(string agentDir)
        {
            try
            {
                if (!Directory.Exists(agentDir)) return null;
                return Directory.GetDirectories(agentDir, "Agent.*").Select(d => Path.Combine(d, "Logs"))
                    .Where(Directory.Exists).SelectMany(d => Directory.GetFiles(d, "Operations-*.log"))
                    .OrderByDescending(File.GetLastWriteTimeUtc).FirstOrDefault();
            }
            catch (Exception) { return null; }
        }

        static string ReadShared(string path)
        {
            using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
            {
                // the tail is enough (the log of one Agent run is ~100 lines; a long one is cut to its last 256 KiB)
                if (fs.Length > 256 * 1024) fs.Seek(-256 * 1024, SeekOrigin.End);
                using (var r = new StreamReader(fs)) return r.ReadToEnd();
            }
        }

        static readonly Dictionary<string, DateTime> own = new Dictionary<string, DateTime>(StringComparer.OrdinalIgnoreCase);

        /// <summary>Desktop wrote [path] itself (its row repair, the patch's .build.info and configs): that write, and its folder's
        /// change with it, are not the Agent's</summary>
        public static void Mine(string path)
        {
            try
            {
                var full = Path.GetFullPath(path);
                lock (own)
                {
                    if (File.Exists(full)) own[full] = File.GetLastWriteTimeUtc(full);
                    else if (Directory.Exists(full)) own[full] = Directory.GetLastWriteTimeUtc(full);
                    var dir = Path.GetDirectoryName(full);
                    if (dir != null && Directory.Exists(dir)) own[dir] = Directory.GetLastWriteTimeUtc(dir);
                }
            }
            catch (Exception) { }
        }

        static bool IsMine(string full, DateTime t) { lock (own) return own.TryGetValue(full, out var m) && m == t; }

        /// <summary>the folder's records written in the last [quietS] (the Agent at work); [root] = the game root. A write Desktop
        /// made itself (<see cref="Mine"/>) is not counted</summary>
        public static bool RecentlyWritten(string root, DateTime nowUtc, double quietS = WriteQuietS)
        {
            foreach (var rel in new[] { ".build.info", ".product.db", ".patch.result", "Data\\config", "Data\\indices" })
            {
                var p = Path.GetFullPath(Path.Combine(root, rel));
                try
                {
                    DateTime t = File.Exists(p) ? File.GetLastWriteTimeUtc(p) : Directory.Exists(p) ? Directory.GetLastWriteTimeUtc(p) : DateTime.MinValue;
                    if (t == DateTime.MinValue || IsMine(p, t)) continue;
                    if ((nowUtc - t).TotalSeconds < quietS && t <= nowUtc.AddMinutes(5)) return true;
                }
                catch (Exception) { }
            }
            return false;
        }

        /// <summary>the Agent at work on [product] in [root]: its log, or its writes; [why] says which (for the log)</summary>
        public static bool Busy(string root, string product, DateTime nowUtc, string agentDir, out string why)
        {
            why = null;
            try
            {
                var log = NewestLog(agentDir);
                if (log != null && LogBusy(ReadShared(log), product, nowUtc)) { why = "its log has an operation on " + product + " under way"; return true; }
            }
            catch (Exception) { }
            if (RecentlyWritten(root, nowUtc)) { why = "it wrote the game's records in the last " + (int)WriteQuietS + " s"; return true; }
            return false;
        }

        public static string DefaultAgentDir =>
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "Battle.net", "Agent");
    }
}
