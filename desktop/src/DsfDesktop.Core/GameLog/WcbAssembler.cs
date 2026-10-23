using System;
using System.Collections.Generic;
using System.Globalization;

namespace DsfDesktop.GameLog
{
    public enum LogEventKind { Doc, Gap, Status, Restart }

    public sealed class LogEvent
    {
        public LogEventKind Kind;
        /// <summary>Doc: the seq of the document's FIRST part; Gap: the seq that arrived</summary>
        public int Seq;
        /// <summary>Gap: the seq that was expected</summary>
        public int Expected;
        /// <summary>Doc: the line's own game timestamp (ms on a day-of-year scale, as the app's LogTail), or -1</summary>
        public long GameTime = -1;
        /// <summary>Doc: the reassembled JSON text (passed through untouched); Status / Restart: a status line</summary>
        public string Text;

        public static LogEvent Status(string s) => new LogEvent { Kind = LogEventKind.Status, Text = s };
        public override string ToString() => Kind + ":" + Seq + ":" + Text;
    }

    /// <summary>
    /// The add-on's WCB1 lines back into documents. A line-for-line port of the APK's LogTail.handleLine so the PC and the Thor read
    /// the log identically:
    ///  - "&lt;game prefix&gt;WCB1|&lt;seq&gt;|&lt;i&gt;/&lt;n&gt;|&lt;payload&gt;"; everything else in General.log is engine chatter;
    ///  - "WCB1|pad|..." is flush padding, "WCB1|probeN|..." the line-length probe: both ignored (neither has a numeric seq);
    ///  - seq increments BEFORE each write, so a gap means a refused line: reported, never hidden;
    ///  - part i of a document has seq = first + (i - 1), so every part shares the key seq - (i - 1);
    ///  - CR is stripped (the client writes CRLF), else it lands at every fragment boundary.
    /// </summary>
    public sealed class WcbAssembler
    {
        public const string Magic = "WCB1|";
        /// <summary>half-built documents kept at most (a part that never arrives must not grow memory for ever)</summary>
        public const int MaxPending = 256;

        static readonly int[] DaysBefore = { 0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334 };

        readonly Dictionary<int, string[]> frags = new Dictionary<int, string[]>();
        readonly Queue<int> order = new Queue<int>();
        int lastSeq = -1;

        public int Gaps { get; private set; }
        public int Pending => frags.Count;

        public void Reset()
        {
            frags.Clear();
            order.Clear();
            lastSeq = -1;
        }

        public void Line(string rawIn, List<LogEvent> output)
        {
            if (rawIn == null) return;
            var raw = rawIn.TrimEnd('\r', '\n');
            int i = raw.IndexOf(Magic, StringComparison.Ordinal);
            if (i < 0) return;
            var body = raw.Substring(i + Magic.Length);
            if (body.StartsWith("pad|", StringComparison.Ordinal)) return;

            int p1 = body.IndexOf('|');
            if (p1 <= 0) return;
            if (!int.TryParse(body.Substring(0, p1), NumberStyles.None, CultureInfo.InvariantCulture, out int seq)) return;
            int p2 = body.IndexOf('|', p1 + 1);
            if (p2 < 0) return;
            var spec = body.Substring(p1 + 1, p2 - p1 - 1);
            var payload = body.Substring(p2 + 1);

            if (lastSeq >= 0 && seq != lastSeq + 1)
            {
                Gaps++;
                output.Add(new LogEvent { Kind = LogEventKind.Gap, Seq = seq, Expected = lastSeq + 1, Text = "gap at seq " + seq + " (expected " + (lastSeq + 1) + ")" });
            }
            lastSeq = seq;

            int slash = spec.IndexOf('/');
            if (slash < 0) return;
            if (!int.TryParse(spec.Substring(0, slash), NumberStyles.None, CultureInfo.InvariantCulture, out int idx)) return;
            if (!int.TryParse(spec.Substring(slash + 1), NumberStyles.None, CultureInfo.InvariantCulture, out int total)) return;
            if (total < 1 || idx < 1 || idx > total) return;

            long gt = GameTime(raw, i);
            if (total == 1)
            {
                output.Add(new LogEvent { Kind = LogEventKind.Doc, Seq = seq, GameTime = gt, Text = payload });
                return;
            }

            int key = seq - (idx - 1);
            if (!frags.TryGetValue(key, out var slots) || slots.Length != total)
            {
                slots = new string[total];
                frags[key] = slots;
                order.Enqueue(key);
                while (frags.Count > MaxPending && order.Count > 0) frags.Remove(order.Dequeue());
            }
            slots[idx - 1] = payload;
            foreach (var s in slots) if (s == null) return;
            frags.Remove(key);
            output.Add(new LogEvent { Kind = LogEventKind.Doc, Seq = key, GameTime = gt, Text = string.Concat(slots) });
        }

        /// <summary>
        /// The line's own timestamp ("10/21 20:00:55.592  [N][Lua] ..."), as ms on a day-of-year + time-of-day scale, or -1. Identical to
        /// LogTail.gameTime: the app stamps "_gt" with it because the log reaches readers in bursts, and only the game's own clock has the
        /// real spacing between position lines.
        /// </summary>
        public static long GameTime(string raw, int magicAt)
        {
            try
            {
                var head = raw.Substring(0, magicAt).Trim();
                int sp = head.IndexOf(' ');
                if (sp < 0) return -1;
                var date = head.Substring(0, sp);
                var time = head.Substring(sp + 1).Trim();
                int tsp = time.IndexOf(' ');
                if (tsp >= 0) time = time.Substring(0, tsp);
                int slash = date.IndexOf('/');
                int mon = int.Parse(date.Substring(0, slash), CultureInfo.InvariantCulture);
                int day = int.Parse(date.Substring(slash + 1), CultureInfo.InvariantCulture);
                int c1 = time.IndexOf(':'), c2 = time.IndexOf(':', c1 + 1);
                int h = int.Parse(time.Substring(0, c1), CultureInfo.InvariantCulture);
                int mi = int.Parse(time.Substring(c1 + 1, c2 - c1 - 1), CultureInfo.InvariantCulture);
                long secMs = (long)(double.Parse(time.Substring(c2 + 1), NumberStyles.Float, CultureInfo.InvariantCulture) * 1000.0);
                long doy = DaysBefore[Math.Max(0, Math.Min(11, mon - 1))] + day;
                return ((doy * 24L + h) * 60L + mi) * 60_000L + secMs;
            }
            catch (Exception) { return -1; }
        }
    }
}
