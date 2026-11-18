using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using DsfDesktop.Wow.Patch;

namespace DsfDesktop.Tests
{
    /// <summary>the self-patcher's test kit: TACT files built from bytes (BLTE, install manifest, encoding table, archive index) and a
    /// fake CDN (a plain HTTP/1.1 server on the loopback, so a test can cut, shorten or refuse any answer)</summary>
    public static class PatchKit
    {
        public static byte[] B(string s) => System.Text.Encoding.UTF8.GetBytes(s);
        public static string Md5(byte[] b) => Hex.Md5(b);

        public static byte[] Be32(long v) => new[] { (byte)(v >> 24), (byte)(v >> 16), (byte)(v >> 8), (byte)v };

        static uint Adler(byte[] d)
        {
            uint a = 1, b = 0;
            foreach (var x in d) { a = (a + x) % 65521; b = (b + a) % 65521; }
            return (b << 16) | a;
        }

        public static byte[] Zlib(byte[] d)
        {
            using (var ms = new MemoryStream())
            {
                ms.WriteByte(0x78); ms.WriteByte(0x9C);
                using (var z = new DeflateStream(ms, CompressionLevel.Optimal, true)) z.Write(d, 0, d.Length);
                ms.Write(Be32(Adler(d)), 0, 4);
                return ms.ToArray();
            }
        }

        /// <summary>a chunk: its mode byte and body</summary>
        public static byte[] Chunk(char mode, byte[] d) => new[] { (byte)mode }.Concat(mode == 'Z' ? Zlib(d) : d).ToArray();

        /// <summary>BLTE with a chunk table; [chunks] = (mode, decoded data); [badHash] = chunk index with a wrong table hash</summary>
        public static byte[] BlteOf(IList<Tuple<char, byte[]>> chunks, int badHash = -1)
        {
            var enc = chunks.Select(c => c.Item1 == 'E' ? new[] { (byte)'E' }.Concat(c.Item2).ToArray() : Chunk(c.Item1, c.Item2)).ToList();
            int hs = 12 + 24 * chunks.Count;
            var o = new List<byte>();
            o.AddRange(B("BLTE")); o.AddRange(Be32(hs));
            o.Add(0x0F); o.AddRange(Be32(chunks.Count).Skip(1));
            for (int i = 0; i < chunks.Count; i++)
            {
                o.AddRange(Be32(enc[i].Length)); o.AddRange(Be32(chunks[i].Item2.Length));
                var h = MD5.Create().ComputeHash(enc[i]);
                if (i == badHash) h[0] ^= 0xFF;
                o.AddRange(h);
            }
            foreach (var e in enc) o.AddRange(e);
            return o.ToArray();
        }

        /// <summary>[d] as BLTE in Z chunks of [size] bytes</summary>
        public static byte[] BlteZ(byte[] d, int size = 4096)
        {
            var cs = new List<Tuple<char, byte[]>>();
            for (int i = 0; i < d.Length; i += size) cs.Add(Tuple.Create(i % (size * 2) == 0 ? 'Z' : 'N', d.Skip(i).Take(size).ToArray()));
            if (cs.Count == 0) cs.Add(Tuple.Create('N', new byte[0]));
            return BlteOf(cs);
        }

        public static byte[] BlteSingle(byte[] d) => B("BLTE").Concat(Be32(0)).Concat(new[] { (byte)'N' }).Concat(d).ToArray();

        /// <summary>an install manifest: [tags] = (name, type); [files] = (name, data, tags)</summary>
        public static byte[] Install(IList<Tuple<string, int>> tags, IList<Tuple<string, byte[], string[]>> files)
        {
            var o = new List<byte>();
            o.AddRange(B("IN")); o.Add(1); o.Add(16);
            o.Add((byte)(tags.Count >> 8)); o.Add((byte)tags.Count);
            o.AddRange(Be32(files.Count));
            int mask = (files.Count + 7) / 8;
            foreach (var t in tags)
            {
                o.AddRange(B(t.Item1)); o.Add(0); o.Add((byte)(t.Item2 >> 8)); o.Add((byte)t.Item2);
                var m = new byte[mask];
                for (int i = 0; i < files.Count; i++) if (files[i].Item3.Contains(t.Item1)) m[i / 8] |= (byte)(0x80 >> (i % 8));
                o.AddRange(m);
            }
            foreach (var f in files)
            {
                o.AddRange(B(f.Item1)); o.Add(0);
                o.AddRange(MD5.Create().ComputeHash(f.Item2));
                o.AddRange(Be32(f.Item2.Length));
            }
            return o.ToArray();
        }

        /// <summary>an encoding table with [pageKb] KiB pages: ckey -> ekey (sorted, several pages)</summary>
        public static byte[] Enc(IDictionary<string, string> map, int pageKb = 1)
        {
            int ps = pageKb * 1024, entry = 6 + 32;
            var keys = map.Keys.OrderBy(k => k, StringComparer.Ordinal).ToList();
            var pages = new List<byte[]>(); var first = new List<string>();
            var cur = new List<byte>(); string firstKey = null;
            foreach (var k in keys)
            {
                if (cur.Count + entry > ps) { pages.Add(cur.Concat(new byte[ps - cur.Count]).ToArray()); first.Add(firstKey); cur = new List<byte>(); firstKey = null; }
                firstKey = firstKey ?? k;
                cur.Add(1); cur.AddRange(new byte[] { 0, 0, 0, 0, 1 });
                cur.AddRange(FromHex(k)); cur.AddRange(FromHex(map[k]));
            }
            if (cur.Count > 0) { pages.Add(cur.Concat(new byte[ps - cur.Count]).ToArray()); first.Add(firstKey); }
            var o = new List<byte>();
            o.AddRange(B("EN")); o.Add(1); o.Add(16); o.Add(16);
            o.Add((byte)(pageKb >> 8)); o.Add((byte)pageKb); o.Add((byte)(pageKb >> 8)); o.Add((byte)pageKb);
            o.AddRange(Be32(pages.Count)); o.AddRange(Be32(0)); o.Add(0); o.AddRange(Be32(0));
            for (int i = 0; i < pages.Count; i++) { o.AddRange(FromHex(first[i])); o.AddRange(MD5.Create().ComputeHash(pages[i])); }
            foreach (var p in pages) o.AddRange(p);
            return o.ToArray();
        }

        /// <summary>an archive index (4 KiB blocks, 16-byte keys, 4-byte size and offset) and its name (the MD5 of its footer)</summary>
        public static Tuple<string, byte[]> Index(IList<Tuple<string, long, long>> entries)
        {
            const int block = 4096, entry = 24;
            int per = block / entry;
            var sorted = entries.OrderBy(e => e.Item1, StringComparer.Ordinal).ToList();
            int blocks = Math.Max(1, (sorted.Count + per - 1) / per);
            var data = new byte[blocks * block];
            for (int i = 0; i < sorted.Count; i++)
            {
                int p = (i / per) * block + (i % per) * entry;
                Buffer.BlockCopy(FromHex(sorted[i].Item1), 0, data, p, 16);
                Buffer.BlockCopy(Be32(sorted[i].Item2), 0, data, p + 16, 4);
                Buffer.BlockCopy(Be32(sorted[i].Item3), 0, data, p + 20, 4);
            }
            var toc = new byte[blocks * (16 + 8)];
            var footer = new List<byte>();
            footer.AddRange(new byte[8]); footer.Add(1); footer.Add(0); footer.Add(0); footer.Add(4); footer.Add(4); footer.Add(4); footer.Add(16); footer.Add(8);
            footer.AddRange(BitConverter.GetBytes((uint)sorted.Count)); footer.AddRange(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
            var all = data.Concat(toc).Concat(footer).ToArray();
            return Tuple.Create(Hex.Md5(all, all.Length - 28, 28), all);
        }

        public static byte[] FromHex(string h)
        {
            var b = new byte[h.Length / 2];
            for (int i = 0; i < b.Length; i++) b[i] = Convert.ToByte(h.Substring(i * 2, 2), 16);
            return b;
        }

        public static string Key(string seed) => Md5(B(seed));
        public static string CdnPath(string kind, string key) => "/tpr/wow/" + kind + "/" + key.Substring(0, 2) + "/" + key.Substring(2, 2) + "/" + key;
    }

    /// <summary>
    /// A fake CDN: GET with an optional "Range: bytes=a-b" / "bytes=a-". [Files] = path -> bytes (a 206 for a range); [Answer] = a
    /// test's own answer for a path (null = the normal one). Every request is in [Requests] ("GET path range").
    /// </summary>
    public sealed class FakeCdn : IDisposable
    {
        public sealed class Reply { public int Code = 200; public byte[] Body = new byte[0]; public long ContentLength = -1; public string ContentRange; public int SendOnly = -1; public int StallMs; }

        readonly TcpListener l;
        readonly Thread t;
        volatile bool stop;
        public readonly Dictionary<string, byte[]> Files = new Dictionary<string, byte[]>();
        public Func<string, string, Reply> Answer;
        public readonly List<string> Requests = new List<string>();
        public string Host => "127.0.0.1:" + ((IPEndPoint)l.LocalEndpoint).Port;

        public FakeCdn()
        {
            l = new TcpListener(IPAddress.Loopback, 0);
            l.Start();
            t = new Thread(Loop) { IsBackground = true };
            t.Start();
        }

        public void Put(string kind, string key, byte[] b) { lock (Files) Files[PatchKit.CdnPath(kind, key)] = b; }

        public List<string> Got(string contains) { lock (Requests) return Requests.Where(r => r.Contains(contains)).ToList(); }

        void Loop()
        {
            while (!stop)
            {
                TcpClient c;
                try { c = l.AcceptTcpClient(); } catch (Exception) { return; }
                new Thread(() => Serve(c)) { IsBackground = true }.Start();
            }
        }

        void Serve(TcpClient c)
        {
            try
            {
                using (c)
                using (var s = c.GetStream())
                {
                    var head = new StringBuilder();
                    int b;
                    while ((b = s.ReadByte()) >= 0) { head.Append((char)b); if (head.Length >= 4 && head.ToString().EndsWith("\r\n\r\n")) break; }
                    var lines = head.ToString().Split(new[] { "\r\n" }, StringSplitOptions.None);
                    var path = lines[0].Split(' ')[1];
                    var range = lines.Select(x => x.Trim()).FirstOrDefault(x => x.StartsWith("Range:", StringComparison.OrdinalIgnoreCase))?.Substring(6).Trim() ?? "";
                    lock (Requests) Requests.Add("GET " + path + " " + range);
                    var r = Answer?.Invoke(path, range) ?? Normal(path, range);
                    var reason = r.Code == 200 ? "OK" : r.Code == 206 ? "Partial Content" : r.Code == 404 ? "Not Found" : "Status";
                    var hb = new StringBuilder("HTTP/1.1 " + r.Code + " " + reason + "\r\nConnection: close\r\nContent-Type: application/octet-stream\r\n");
                    hb.Append("Content-Length: " + (r.ContentLength >= 0 ? r.ContentLength : r.Body.Length) + "\r\n");
                    if (r.ContentRange != null) hb.Append("Content-Range: " + r.ContentRange + "\r\n");
                    hb.Append("\r\n");
                    var hbytes = Encoding.ASCII.GetBytes(hb.ToString());
                    s.Write(hbytes, 0, hbytes.Length);
                    int n = r.SendOnly >= 0 ? Math.Min(r.SendOnly, r.Body.Length) : r.Body.Length;
                    if (r.StallMs > 0) { s.Write(r.Body, 0, Math.Min(n, 10)); s.Flush(); Thread.Sleep(r.StallMs); return; }
                    s.Write(r.Body, 0, n);
                    s.Flush();
                }
            }
            catch (Exception) { }
        }

        Reply Normal(string path, string range)
        {
            byte[] f;
            lock (Files) if (!Files.TryGetValue(path, out f)) return new Reply { Code = 404 };
            if (range.StartsWith("bytes="))
            {
                var p = range.Substring(6).Split('-');
                long a = long.Parse(p[0]);
                long z = p.Length > 1 && p[1].Length > 0 ? long.Parse(p[1]) : f.Length - 1;
                if (a >= f.Length) return new Reply { Code = 416 };
                z = Math.Min(z, f.Length - 1);
                return new Reply { Code = 206, Body = f.Skip((int)a).Take((int)(z - a + 1)).ToArray(), ContentRange = "bytes " + a + "-" + z + "/" + f.Length };
            }
            return new Reply { Body = f };
        }

        public void Dispose() { stop = true; try { l.Stop(); } catch (Exception) { } }
    }
}
