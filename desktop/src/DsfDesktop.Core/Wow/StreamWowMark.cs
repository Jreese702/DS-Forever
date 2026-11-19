using System;
using System.Globalization;
using System.IO;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// THE WOW A STREAM STARTED, REMEMBERED ACROSS A DESKTOP RESTART (fix/desk-idle-close, 2026-11-19: Desktop restarted at 11:06 while a
    /// stream's WoW ran with no stream on, and that WoW stayed open from 11:08 to 11:43: the 5-minute close lived only in memory).
    /// When Desktop itself starts WoW for a stream, the client's pid and its process start time go into one small file in Desktop's
    /// data folder. At the next Desktop start, with no stream and no Thor, a running WoW that is EXACTLY that process (same pid AND same
    /// start time, so a reused pid never counts) gets the usual 5-minute close. HARD RULE (the user's): a WoW the player started on the
    /// PC is never closed this way - it has no marker, or a marker of another process.
    /// </summary>
    public sealed class StreamWowMark
    {
        public const string FileName = "stream_wow.txt";
        /// <summary>a process's start time as read now and as written then may differ by rounding only</summary>
        public static readonly TimeSpan StartSlack = TimeSpan.FromSeconds(1);

        public int Pid;
        public long StartUtcTicks;

        public static string PathIn(string dataDir) => Path.Combine(dataDir, FileName);

        /// <summary>"&lt;pid&gt; &lt;start utc ticks&gt;"; best effort</summary>
        public static bool Write(string dataDir, int pid, DateTime startUtc)
        {
            try
            {
                Directory.CreateDirectory(dataDir);
                var f = PathIn(dataDir);
                var tmp = f + ".tmp";
                File.WriteAllText(tmp, pid.ToString(CultureInfo.InvariantCulture) + " " + startUtc.ToUniversalTime().Ticks.ToString(CultureInfo.InvariantCulture));
                if (File.Exists(f)) File.Delete(f);
                File.Move(tmp, f);
                return true;
            }
            catch (Exception) { return false; }
        }

        /// <summary>null = no marker, or one that can't be read (then nothing is ever closed by it)</summary>
        public static StreamWowMark Read(string dataDir)
        {
            try
            {
                var f = PathIn(dataDir);
                if (!File.Exists(f)) return null;
                var p = File.ReadAllText(f).Trim().Split(' ');
                if (p.Length != 2) return null;
                if (!int.TryParse(p[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out var pid) || pid <= 0) return null;
                if (!long.TryParse(p[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out var t) || t <= 0) return null;
                return new StreamWowMark { Pid = pid, StartUtcTicks = t };
            }
            catch (Exception) { return null; }
        }

        public static void Clear(string dataDir) { try { File.Delete(PathIn(dataDir)); } catch (Exception) { } }

        /// <summary>the running WoW ([pid], started [startUtc]) is the one the marker names. Pure</summary>
        public static bool Matches(StreamWowMark m, int pid, DateTime? startUtc)
        {
            if (m == null || pid <= 0 || !startUtc.HasValue || m.Pid != pid) return false;
            return Math.Abs(startUtc.Value.ToUniversalTime().Ticks - m.StartUtcTicks) <= StartSlack.Ticks;
        }

        /// <summary>at Desktop's start: arm the 5-minute close for the running WoW? Only with no Thor, no stream, and that WoW the
        /// marker's own. [wowPid] null = no WoW running. Pure</summary>
        public static bool ArmAtStart(bool thorConnected, bool streaming, int? wowPid, DateTime? wowStartUtc, StreamWowMark m) =>
            !thorConnected && !streaming && wowPid.HasValue && Matches(m, wowPid.Value, wowStartUtc);
    }
}
