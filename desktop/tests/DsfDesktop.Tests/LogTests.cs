using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using DsfDesktop.GameLog;

namespace DsfDesktop.Tests
{
    public static class AssemblerTests
    {
        const string Pfx = "10/21 20:00:55.592  [N][Lua] ";

        static List<LogEvent> Feed(WcbAssembler a, params string[] lines)
        {
            var ev = new List<LogEvent>();
            foreach (var l in lines) a.Line(l, ev);
            return ev;
        }

        static List<LogEvent> Docs(List<LogEvent> ev) => ev.Where(e => e.Kind == LogEventKind.Doc).ToList();

        [Test]
        public static void SingleDocument()
        {
            var ev = Docs(Feed(new WcbAssembler(), Pfx + "WCB1|7|1/1|{\"t\":\"pos\",\"x\":1}"));
            A.Eq(1, ev.Count);
            A.Eq(LogEventKind.Doc, ev[0].Kind);
            A.Eq(7, ev[0].Seq);
            A.Eq("{\"t\":\"pos\",\"x\":1}", ev[0].Text);
        }

        [Test]
        public static void FragmentsReassembleInOrder()
        {
            var a = new WcbAssembler();
            var ev = Feed(a, Pfx + "WCB1|10|1/3|{\"a\":\"he", Pfx + "WCB1|11|2/3|llo wo", Pfx + "WCB1|12|3/3|rld\"}");
            var d = Docs(ev);
            A.Eq(1, d.Count);
            A.Eq("{\"a\":\"hello world\"}", d[0].Text);
            A.Eq(10, d[0].Seq, "a document is keyed by its first part's seq");
            A.Eq(0, a.Pending);
        }

        [Test]
        public static void FragmentsOutOfOrderStillJoin()
        {
            // the seq is monotonic on the wire; this checks the slotting only (the gap reports are expected)
            var a = new WcbAssembler();
            var ev = Feed(a, Pfx + "WCB1|22|3/3|C", Pfx + "WCB1|20|1/3|A", Pfx + "WCB1|21|2/3|B");
            A.Eq("ABC", Docs(ev).Single().Text);
        }

        [Test]
        public static void CrlfIsStrippedAtFragmentBoundaries()
        {
            var ev = Feed(new WcbAssembler(), Pfx + "WCB1|1|1/2|{\"k\":\"ab\r", Pfx + "WCB1|2|2/2|cd\"}\r\n");
            A.Eq("{\"k\":\"abcd\"}", Docs(ev).Single().Text);
        }

        [Test]
        public static void ChatterPaddingAndProbesIgnored()
        {
            var ev = Feed(new WcbAssembler(),
                "10/21 20:00:55.592  [N][Connection] Client connection initialized",
                Pfx + "WCB1|pad|xxxxxxxxxxxxxxxxxxxxxxxx",
                Pfx + "WCB1|probe900|ppppp|END",
                Pfx + "WCB1|garbage",
                "");
            A.Eq(0, Docs(ev).Count);
            A.Eq(0, ev.Count(e => e.Kind == LogEventKind.Gap));
        }

        [Test]
        public static void GapIsReported()
        {
            var a = new WcbAssembler();
            var ev = Feed(a, Pfx + "WCB1|1|1/1|{}", Pfx + "WCB1|2|1/1|{}", Pfx + "WCB1|5|1/1|{}");
            var gaps = ev.Where(e => e.Kind == LogEventKind.Gap).ToList();
            A.Eq(1, gaps.Count);
            A.Eq(5, gaps[0].Seq);
            A.Eq(3, gaps[0].Expected);
            A.Eq(1, a.Gaps);
            A.Eq(3, Docs(ev).Count, "the documents around a gap still arrive");
        }

        [Test]
        public static void ResetForgetsSeqAndFragments()
        {
            var a = new WcbAssembler();
            Feed(a, Pfx + "WCB1|40|1/2|{\"x\":");
            a.Reset();
            var ev = Feed(a, Pfx + "WCB1|1|1/1|{}");
            A.Eq(0, ev.Count(e => e.Kind == LogEventKind.Gap), "a restarted client's seq 1 is not a gap");
            A.Eq(0, a.Pending);
        }

        [Test]
        public static void PendingFragmentsAreCapped()
        {
            var a = new WcbAssembler();
            for (int i = 0; i < WcbAssembler.MaxPending + 50; i++) Feed(a, Pfx + "WCB1|" + (i * 2 + 1) + "|1/2|x");
            A.Eq(WcbAssembler.MaxPending, a.Pending);
        }

        [Test]
        public static void GameTimeMatchesLogTail()
        {
            // 10/21: day-of-year 273 + 21 = 294; 20:00:55.592
            long want = ((294L * 24 + 20) * 60 + 0) * 60_000L + 55_592;
            A.Eq(want, WcbAssembler.GameTime(Pfx + "WCB1|1|1/1|{}", Pfx.Length));
            A.Eq(want, Docs(Feed(new WcbAssembler(), Pfx + "WCB1|1|1/1|{}")).Single().GameTime);
            A.Eq(-1L, WcbAssembler.GameTime("WCB1|1|1/1|{}", 0));
        }

        [Test]
        public static void MagicMidLineAfterOtherText()
        {
            var ev = Feed(new WcbAssembler(), "9/27 08:41:53.221  [N][Lua] stuff before WCB1|3|1/1|{\"ok\":true}");
            A.Eq("{\"ok\":true}", Docs(ev).Single().Text);
        }
    }

    public static class TailerTests
    {
        const string Pfx = "10/21 20:00:55.592  [N][Lua] ";

        static string Doc(int seq, string json) => Pfx + "WCB1|" + seq + "|1/1|" + json + "\r\n";

        static void Append(string f, string text)
        {
            using (var fs = new FileStream(f, FileMode.Append, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete))
            {
                var b = Encoding.UTF8.GetBytes(text);
                fs.Write(b, 0, b.Length);
            }
        }

        static void Append(string f, byte[] b)
        {
            using (var fs = new FileStream(f, FileMode.Append, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete)) fs.Write(b, 0, b.Length);
        }

        /// <summary>rewrite in place (same file, so the same file index): what WoW's truncate-at-launch looks like</summary>
        static void Truncate(string f, string text)
        {
            using (var fs = new FileStream(f, FileMode.Open, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete))
            {
                fs.SetLength(0);
                var b = Encoding.UTF8.GetBytes(text);
                fs.Write(b, 0, b.Length);
            }
        }

        static List<string> DocTexts(List<LogEvent> ev) => ev.Where(e => e.Kind == LogEventKind.Doc).Select(e => e.Text).ToList();

        [Test]
        public static void MissingFileWaitsThenReads()
        {
            using (var t = new Tmp())
            {
                var f = t.P("General.log");
                var tail = new LogTailer(f);
                var ev = tail.Poll();
                A.True(ev.Any(e => e.Kind == LogEventKind.Status && e.Text == "waiting for game"));
                A.Eq(0, tail.Poll().Count, "the same status is not repeated");
                File.WriteAllText(f, Doc(1, "{\"a\":1}"));
                A.Eq("{\"a\":1}", DocTexts(tail.Poll()).Single());
            }
        }

        [Test]
        public static void PartialLineCarriesAcrossPolls()
        {
            using (var t = new Tmp())
            {
                var f = t.P("General.log");
                File.WriteAllText(f, "");
                var tail = new LogTailer(f);
                tail.Poll();
                var line = Doc(1, "{\"long\":\"value\"}");
                Append(f, line.Substring(0, 20));
                A.Eq(0, DocTexts(tail.Poll()).Count);
                Append(f, line.Substring(20));
                A.Eq("{\"long\":\"value\"}", DocTexts(tail.Poll()).Single());
            }
        }

        [Test]
        public static void Utf8SplitAcrossReadsSurvives()
        {
            using (var t = new Tmp())
            {
                var f = t.P("General.log");
                File.WriteAllText(f, "");
                var tail = new LogTailer(f);
                tail.Poll();
                var bytes = Encoding.UTF8.GetBytes(Doc(1, "{\"n\":\"Ærøskøbing ☃\"}"));
                int cut = Encoding.UTF8.GetBytes(Pfx + "WCB1|1|1/1|{\"n\":\"Ærø").Length - 1; // inside the 2-byte 'ø'
                Append(f, bytes.Take(cut).ToArray());
                tail.Poll();
                Append(f, bytes.Skip(cut).ToArray());
                A.Eq("{\"n\":\"Ærøskøbing ☃\"}", DocTexts(tail.Poll()).Single());
            }
        }

        [Test]
        public static void FragmentsAcrossPollsReassemble()
        {
            using (var t = new Tmp())
            {
                var f = t.P("General.log");
                File.WriteAllText(f, Pfx + "WCB1|1|1/2|{\"q\":\r\n");
                var tail = new LogTailer(f);
                A.Eq(0, DocTexts(tail.Poll()).Count);
                Append(f, Pfx + "WCB1|2|2/2|42}\r\n");
                A.Eq("{\"q\":42}", DocTexts(tail.Poll()).Single());
            }
        }

        [Test]
        public static void TruncationIsARestart()
        {
            using (var t = new Tmp())
            {
                var f = t.P("General.log");
                File.WriteAllText(f, Doc(1, "{\"a\":1}") + Doc(2, "{\"a\":2}") + Doc(3, "{\"a\":3}"));
                var tail = new LogTailer(f);
                A.Eq(3, DocTexts(tail.Poll()).Count);
                Truncate(f, Doc(1, "{\"b\":1}"));
                var ev = tail.Poll();
                A.True(ev.Any(e => e.Kind == LogEventKind.Restart), "restart reported");
                A.Eq("{\"b\":1}", DocTexts(ev).Single());
                A.False(ev.Any(e => e.Kind == LogEventKind.Gap), "seq 1 after a restart is no gap");
            }
        }

        [Test]
        public static void RestartThatOutgrewTheOffsetIsCaught()
        {
            using (var t = new Tmp())
            {
                var f = t.P("General.log");
                File.WriteAllText(f, "10/21 20:00:00.000  [N][Connection] first launch\r\n" + Doc(1, "{\"a\":1}"));
                var tail = new LogTailer(f);
                A.Eq(1, DocTexts(tail.Poll()).Count);
                // the game restarted and wrote MORE than we had read before our next poll: size alone can't tell
                var sb = new StringBuilder("10/22 09:15:00.000  [N][Connection] second launch\r\n");
                for (int i = 1; i <= 5; i++) sb.Append(Doc(i, "{\"b\":" + i + "}"));
                Truncate(f, sb.ToString());
                var ev = tail.Poll();
                A.True(ev.Any(e => e.Kind == LogEventKind.Restart), "restart reported");
                A.Eq(5, DocTexts(ev).Count, "the whole new log is read from 0");
            }
        }

        [Test]
        public static void DeletedAndRecreatedIsARestart()
        {
            using (var t = new Tmp())
            {
                var f = t.P("General.log");
                var head = "10/21 20:00:00.000  [N][Connection] same first line, same length, same everything\r\n";
                File.WriteAllText(f, head + Doc(1, "{\"a\":1}"));
                var tail = new LogTailer(f);
                tail.Poll();
                File.Delete(f);
                // identical head (so only the file identity can tell), longer than before
                File.WriteAllText(f, head + Doc(1, "{\"a\":1}") + Doc(2, "{\"a\":2}"));
                var ev = tail.Poll();
                A.True(ev.Any(e => e.Kind == LogEventKind.Restart), "a new file (new file index) is a restart");
                A.Eq(2, DocTexts(ev).Count);
            }
        }

        [Test]
        public static void NulsFromASparseHoleAreDropped()
        {
            using (var t = new Tmp())
            {
                var f = t.P("General.log");
                File.WriteAllText(f, "");
                var tail = new LogTailer(f);
                tail.Poll();
                Append(f, new byte[300]);
                Append(f, Doc(9, "{\"after\":\"hole\"}"));
                A.Eq("{\"after\":\"hole\"}", DocTexts(tail.Poll()).Single());
            }
        }

        [Test]
        public static void BigBacklogStartsAtALineBoundary()
        {
            using (var t = new Tmp())
            {
                var f = t.P("General.log");
                var filler = new string('z', 1000);
                using (var w = new StreamWriter(f, false, new UTF8Encoding(false)))
                {
                    long written = 0;
                    int i = 0;
                    while (written < LogTailer.MaxBacklog + 200_000)
                    {
                        var l = Doc(++i, "{\"f\":\"" + filler + "\"}");
                        w.Write(l);
                        written += l.Length;
                    }
                    w.Write(Doc(++i, "{\"last\":true}"));
                }
                var tail = new LogTailer(f);
                var all = new List<LogEvent>();
                for (int k = 0; k < 4; k++) all.AddRange(tail.Poll());
                var docs = DocTexts(all);
                A.True(docs.Count > 1000, "the capped backlog is read");
                A.True(docs.Count < 9000, "not the whole file");
                A.Eq("{\"last\":true}", docs.Last());
                A.True(docs.All(d => d.StartsWith("{\"")), "no half line at the cut");
            }
        }

        [Test]
        public static void SwitchingPathStartsFresh()
        {
            using (var t = new Tmp())
            {
                var a = t.P("a.log");
                var b = t.P("b.log");
                File.WriteAllText(a, Doc(1, "{\"from\":\"a\"}"));
                File.WriteAllText(b, Doc(50, "{\"from\":\"b\"}"));
                var tail = new LogTailer(a);
                A.Eq("{\"from\":\"a\"}", DocTexts(tail.Poll()).Single());
                tail.SetPath(b);
                var ev = tail.Poll();
                A.Eq("{\"from\":\"b\"}", DocTexts(ev).Single());
                A.False(ev.Any(e => e.Kind == LogEventKind.Gap), "another log's seq is not a gap");
            }
        }

        [Test]
        public static void OpenFileDoesNotBlockTheWriter()
        {
            using (var t = new Tmp())
            {
                var f = t.P("General.log");
                // WoW holds its log open for writing the whole session
                using (var game = new FileStream(f, FileMode.Create, FileAccess.Write, FileShare.Read))
                {
                    var tail = new LogTailer(f);
                    var b = Encoding.UTF8.GetBytes(Doc(1, "{\"live\":1}"));
                    game.Write(b, 0, b.Length);
                    game.Flush();
                    A.Eq("{\"live\":1}", DocTexts(tail.Poll()).Single());
                    game.SetLength(0);   // and truncates it while we read
                    game.Write(b, 0, b.Length);
                    game.Flush();
                    tail.Poll();
                }
            }
        }
    }
}
