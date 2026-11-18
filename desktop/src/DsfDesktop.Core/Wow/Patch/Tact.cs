using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Security.Cryptography;
using System.Text;

namespace DsfDesktop.Wow.Patch
{
    /// <summary>
    /// THE PC GAME'S OWN UPDATER, its pure parts (the user's ruling 2026-11-18: Desktop patches an outdated WoW Forever itself from
    /// Blizzard's CDN, no Battle.net). The formats as the Thor's updater reads them (WowClientDownloader.kt, the reference): the patch
    /// server's PSV tables, CDN configs, BLTE, the install manifest, the encoding table, archive indexes. Nothing here touches a file
    /// or the network.
    /// </summary>
    public sealed class PatchFormatException : Exception { public PatchFormatException(string m) : base(m) { } }

    public static class Hex
    {
        const string Digits = "0123456789abcdef";
        public static string Of(byte[] b, int off, int n)
        {
            var c = new char[n * 2];
            for (int i = 0; i < n; i++) { int v = b[off + i]; c[i * 2] = Digits[v >> 4]; c[i * 2 + 1] = Digits[v & 15]; }
            return new string(c);
        }
        public static string Of(byte[] b) => Of(b, 0, b.Length);
        public static string Md5(byte[] b) { using (var m = MD5.Create()) return Of(m.ComputeHash(b)); }
        public static string Md5(byte[] b, int off, int n) { using (var m = MD5.Create()) return Of(m.ComputeHash(b, off, n)); }

        /// <summary>a file's MD5, null = no such file (read shared: the game or the Agent may hold it)</summary>
        public static string Md5File(string path)
        {
            if (!File.Exists(path)) return null;
            using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 1 << 20))
            using (var m = MD5.Create()) return Of(m.ComputeHash(fs));
        }

        public static bool IsKey(string s) => s != null && s.Length == 32 && s.All(ch => (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'));

        internal static int Be16(byte[] b, int o) => (b[o] << 8) | b[o + 1];
        internal static int Be32(byte[] b, int o) => (b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3];
        internal static long BeN(byte[] b, int o, int n) { long v = 0; for (int i = 0; i < n; i++) v = (v << 8) | b[o + i]; return v; }
    }

    /// <summary>the patch server's tables ("Region!STRING:0|BuildConfig!HEX:16|...", "## seqn" lines skipped)</summary>
    public static class Psv
    {
        public static List<Dictionary<string, string>> Parse(string text)
        {
            var rows = new List<Dictionary<string, string>>();
            var lines = (text ?? "").Replace("\r", "").Split('\n').Where(l => l.Trim().Length > 0 && !l.StartsWith("#")).ToList();
            if (lines.Count < 1) return rows;
            var cols = lines[0].Split('|').Select(c => c.Split('!')[0].Trim()).ToList();
            foreach (var l in lines.Skip(1))
            {
                var f = l.Split('|');
                var d = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                for (int i = 0; i < cols.Count; i++) d[cols[i]] = i < f.Length ? f[i].Trim() : "";
                rows.Add(d);
            }
            return rows;
        }

        /// <summary>the row of [region] in the column [key]; else us's; else the first; null = none (the Thor's remoteRow)</summary>
        public static Dictionary<string, string> Row(List<Dictionary<string, string>> rows, string key, string region)
        {
            if (rows == null || rows.Count == 0) return null;
            string V(Dictionary<string, string> r) => r.TryGetValue(key, out var v) ? v : "";
            return rows.FirstOrDefault(r => !string.IsNullOrEmpty(region) && V(r).Equals(region, StringComparison.OrdinalIgnoreCase))
                ?? rows.FirstOrDefault(r => V(r) == "us") ?? rows[0];
        }
    }

    /// <summary>a CDN config file (build config, CDN config): "key = value value ..."</summary>
    public static class ConfigFile
    {
        public static Dictionary<string, List<string>> Parse(string text)
        {
            var d = new Dictionary<string, List<string>>(StringComparer.Ordinal);
            foreach (var raw in (text ?? "").Replace("\r", "").Split('\n'))
            {
                if (raw.StartsWith("#")) continue;
                int eq = raw.IndexOf('=');
                if (eq <= 0) continue;
                d[raw.Substring(0, eq).Trim()] = raw.Substring(eq + 1).Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries).ToList();
            }
            return d;
        }

        /// <summary>the [i]th value of [key], null = none</summary>
        public static string Get(Dictionary<string, List<string>> c, string key, int i = 0) =>
            c != null && c.TryGetValue(key, out var v) && v.Count > i ? v[i] : null;

        /// <summary>a config is named by the MD5 of its bytes (a sign-in page's "200" is not it)</summary>
        public static bool Ok(byte[] bytes, string key) => bytes != null && Hex.Md5(bytes) == (key ?? "").ToLowerInvariant();
    }

    /// <summary>
    /// BLTE, streamed: modes N (plain) and Z (zlib) only; E (encrypted), F (frame) or any other mode is a hard error (the judge's plan:
    /// a client file Blizzard encrypts is not ours to patch). Each chunk's decoded size is checked against the chunk table, so a cut
    /// zlib stream is an error, never a short file.
    /// </summary>
    public static class Blte
    {
        /// <summary>[input] decoded to [output]; returns the decoded byte count. [onChunk] = encoded bytes consumed so far</summary>
        public static long Decode(Stream input, Stream output, Action<long> onChunk = null)
        {
            var head = ReadExact(input, 8);
            if (head[0] != 'B' || head[1] != 'L' || head[2] != 'T' || head[3] != 'E') throw new PatchFormatException("not BLTE data");
            int headerSize = Hex.Be32(head, 4);
            if (headerSize == 0) return DecodeChunk(input, output, -1, -1);
            if (headerSize < 12) throw new PatchFormatException("a bad BLTE header");
            var rest = ReadExact(input, headerSize - 8);
            if (rest[0] != 0x0F) throw new PatchFormatException("an unknown BLTE chunk table");
            int count = Hex.Be32(rest, 0) & 0xFFFFFF;
            if (12 + 24L * count > headerSize) throw new PatchFormatException("a cut BLTE chunk table");
            long total = 0, consumed = headerSize;
            for (int i = 0; i < count; i++)
            {
                int o = 4 + i * 24;
                long enc = (uint)Hex.Be32(rest, o), dec = (uint)Hex.Be32(rest, o + 4);
                var hash = Hex.Of(rest, o + 8, 16);
                var chunk = ReadExact(input, checked((int)enc));
                if (hash != "00000000000000000000000000000000" && Hex.Md5(chunk) != hash) throw new PatchFormatException("a BLTE chunk's checksum is wrong");
                using (var ms = new MemoryStream(chunk, false)) total += DecodeChunk(ms, output, enc, dec);
                consumed += enc;
                onChunk?.Invoke(consumed);
            }
            return total;
        }

        public static byte[] Decode(byte[] encoded)
        {
            using (var i = new MemoryStream(encoded, false))
            using (var o = new MemoryStream()) { Decode(i, o); return o.ToArray(); }
        }

        /// <summary>one chunk (mode byte, then its body: the rest of [input]); [want] = its decoded size from the table, -1 = not known</summary>
        static long DecodeChunk(Stream input, Stream output, long encSize, long want)
        {
            int mode = input.ReadByte();
            if (mode < 0) throw new PatchFormatException("an empty BLTE chunk");
            long n;
            switch ((char)mode)
            {
                case 'N': n = Copy(input, output); break;
                case 'Z': n = Inflate(input, output); break;
                default: throw new PatchFormatException("BLTE chunk mode " + (char)mode + " is not supported");
            }
            if (want >= 0 && n != want) throw new PatchFormatException("a BLTE chunk decoded to " + n + " of " + want + " bytes (cut)");
            return n;
        }

        static long Copy(Stream i, Stream o)
        {
            var buf = new byte[1 << 16]; long n = 0; int r;
            while ((r = i.Read(buf, 0, buf.Length)) > 0) { o.Write(buf, 0, r); n += r; }
            return n;
        }

        static long Inflate(Stream i, Stream o)
        {
            // zlib = a 2-byte header, raw deflate, an Adler-32 trailer (DeflateStream reads the raw part)
            int a = i.ReadByte(), b = i.ReadByte();
            if (a < 0 || b < 0 || (a & 0x0F) != 8 || ((a << 8) | b) % 31 != 0) throw new PatchFormatException("a bad zlib header");
            try
            {
                using (var z = new DeflateStream(i, CompressionMode.Decompress, true)) return Copy(z, o);
            }
            catch (InvalidDataException e) { throw new PatchFormatException("a broken zlib chunk: " + e.Message); }
        }

        internal static byte[] ReadExact(Stream s, int n)
        {
            var b = new byte[n]; int got = 0;
            while (got < n) { int r = s.Read(b, got, n - got); if (r <= 0) throw new PatchFormatException("cut BLTE data"); got += r; }
            return b;
        }
    }

    /// <summary>a BLTE file read by ranges of its decoded bytes ([fetch] = encoded offset, length -> bytes): the encoding table is
    /// 141 MB, only its header, page index and a few 4 KiB pages are ever fetched</summary>
    public sealed class RangedBlte
    {
        readonly Func<long, int, byte[]> fetch;
        readonly List<long[]> chunks = new List<long[]>();   // encoded offset, encoded size, decoded offset, decoded size
        long[] cachedChunk; byte[] cachedData;

        public RangedBlte(Func<long, int, byte[]> fetch)
        {
            this.fetch = fetch;
            var h = fetch(0, 8);
            if (h.Length != 8 || h[0] != 'B' || h[1] != 'L' || h[2] != 'T' || h[3] != 'E') throw new PatchFormatException("not BLTE data");
            int hs = Hex.Be32(h, 4);
            if (hs < 12) throw new PatchFormatException("a single-chunk BLTE can't be read by ranges");
            var t = fetch(0, hs);
            int count = Hex.Be32(t, 8) & 0xFFFFFF;
            long enc = hs, dec = 0;
            for (int i = 0; i < count; i++)
            {
                long cs = (uint)Hex.Be32(t, 12 + i * 24), ds = (uint)Hex.Be32(t, 16 + i * 24);
                chunks.Add(new[] { enc, cs, dec, ds });
                enc += cs; dec += ds;
            }
        }

        readonly Dictionary<long, int> modes = new Dictionary<long, int>();

        public byte[] Read(long start, int length)
        {
            var o = new byte[length]; int w = 0;
            foreach (var c in chunks)
            {
                long end = c[2] + c[3];
                if (end <= start || c[2] >= start + length) continue;
                long from = Math.Max(start, c[2]) - c[2];
                int n = (int)(Math.Min(start + length, end) - c[2] - from);
                // a plain chunk is read where the bytes are (the Thor's EncodingLookup): never its whole tens of MB for one 4 KiB page
                if (!modes.TryGetValue(c[0], out var mode)) modes[c[0]] = mode = fetch(c[0], 1)[0];
                if (mode == 'N') { Buffer.BlockCopy(fetch(c[0] + 1 + from, n), 0, o, w, n); w += n; continue; }
                var data = Decoded(c);
                Buffer.BlockCopy(data, (int)from, o, w, n);
                w += n;
            }
            if (w != length) throw new PatchFormatException("a read past the end of the BLTE file");
            return o;
        }

        byte[] Decoded(long[] c)
        {
            if (cachedChunk == c) return cachedData;
            var raw = fetch(c[0], (int)c[1]);
            using (var i = new MemoryStream(raw, false))
            using (var o = new MemoryStream((int)c[3]))
            {
                int mode = i.ReadByte();
                if (mode == 'N') i.CopyTo(o);
                else if (mode == 'Z')
                {
                    i.ReadByte(); i.ReadByte();
                    using (var z = new DeflateStream(i, CompressionMode.Decompress, true)) z.CopyTo(o);
                }
                else throw new PatchFormatException("BLTE chunk mode " + (char)mode + " is not supported");
                if (o.Length != c[3]) throw new PatchFormatException("a cut BLTE chunk");
                cachedChunk = c; cachedData = o.ToArray();
            }
            return cachedData;
        }
    }

    public sealed class InstallEntry
    {
        public string Name, CKey;
        public long Size;
        public HashSet<string> Tags;
        public override string ToString() => Name + " " + CKey + " " + Size;
    }

    /// <summary>
    /// The install manifest: every client file with its content MD5 (ckey) and tags. The PC's files are picked by TAG, never by name:
    /// the CN Wow.exe has the same name as the one for us/eu/kr/tw (70959's manifest: two "Wow.exe" rows).
    /// </summary>
    public static class InstallManifest
    {
        public sealed class Parsed
        {
            public List<InstallEntry> Entries = new List<InstallEntry>();
            /// <summary>tag name -> its type (1 platform, 2 arch, 3 locale, 4 region, 5 content, ...)</summary>
            public Dictionary<string, int> TagTypes = new Dictionary<string, int>(StringComparer.Ordinal);
        }

        public static Parsed Parse(byte[] d)
        {
            if (d == null || d.Length < 10 || d[0] != 'I' || d[1] != 'N') throw new PatchFormatException("not an install manifest");
            int hashSize = d[3], tagCount = Hex.Be16(d, 4), entryCount = Hex.Be32(d, 6);
            if (hashSize != 16) throw new PatchFormatException("an install manifest with " + hashSize + "-byte keys");
            int mask = (entryCount + 7) / 8, pos = 10;
            string Str()
            {
                int e = Array.IndexOf(d, (byte)0, pos);
                if (e < 0) throw new PatchFormatException("a cut install manifest");
                var s = Encoding.UTF8.GetString(d, pos, e - pos); pos = e + 1; return s;
            }
            var p = new Parsed();
            var tags = new List<Tuple<string, int>>();
            for (int t = 0; t < tagCount; t++)
            {
                var name = Str();
                int type = Hex.Be16(d, pos); pos += 2;
                tags.Add(Tuple.Create(name, pos)); pos += mask;
                p.TagTypes[name] = type;
            }
            for (int i = 0; i < entryCount; i++)
            {
                var name = Str();
                if (pos + hashSize + 4 > d.Length) throw new PatchFormatException("a cut install manifest");
                var e = new InstallEntry { Name = name, CKey = Hex.Of(d, pos, hashSize), Size = (uint)Hex.Be32(d, pos + hashSize), Tags = new HashSet<string>(StringComparer.Ordinal) };
                pos += hashSize + 4;
                foreach (var t in tags) if ((d[t.Item2 + i / 8] & (0x80 >> (i % 8))) != 0) e.Tags.Add(t.Item1);
                p.Entries.Add(e);
            }
            return p;
        }

        /// <summary>the tag names of a .build.info Tags cell ("Windows x86_64 US? acct-USA? geoip-US? enUS speech?:... text?")</summary>
        public static HashSet<string> TagsOf(string tagsCell) =>
            new HashSet<string>((tagsCell ?? "").Split(new[] { ' ', ':' }, StringSplitOptions.RemoveEmptyEntries).Select(t => t.TrimEnd('?')), StringComparer.Ordinal);

        /// <summary>
        /// The files of this install: for every tag TYPE that has a selected tag, the file has one of the selected tags of that type
        /// (Windows + x86_64 + the row's region + its locale + speech/text). Two files of one name selected = an error (never a guess).
        /// </summary>
        public static List<InstallEntry> Select(Parsed m, IEnumerable<string> selected)
        {
            var sel = new HashSet<string>(selected.Where(t => m.TagTypes.ContainsKey(t)), StringComparer.Ordinal);
            var byType = sel.GroupBy(t => m.TagTypes[t]).ToDictionary(g => g.Key, g => new HashSet<string>(g, StringComparer.Ordinal));
            foreach (var need in new[] { "Windows", "x86_64" })
                if (!sel.Contains(need)) throw new PatchFormatException("the install's tags lack " + need);
            var r = m.Entries.Where(e => byType.Values.All(set => set.Overlaps(e.Tags))).ToList();
            var dup = r.GroupBy(e => e.Name, StringComparer.OrdinalIgnoreCase).FirstOrDefault(g => g.Count() > 1);
            if (dup != null) throw new PatchFormatException("two files named " + dup.Key + " for these tags");
            return r;
        }

        /// <summary>the install order: Utils\ first, then the rest, then wow_loader.dll, Wow.exe LAST (it makes the folder this build)</summary>
        public static int Rank(string name)
        {
            var n = name.Replace('/', '\\');
            if (n.Equals("Wow.exe", StringComparison.OrdinalIgnoreCase)) return 3;
            if (n.Equals("wow_loader.dll", StringComparison.OrdinalIgnoreCase)) return 2;
            return n.StartsWith("Utils\\", StringComparison.OrdinalIgnoreCase) ? 0 : 1;
        }
    }

    /// <summary>the encoding table: content key -> encoded key, read page by page ([read] = decoded offset, length)</summary>
    public static class EncodingTable
    {
        public static Dictionary<string, string> Find(Func<long, int, byte[]> read, ICollection<string> wanted)
        {
            var h = read(0, 22);
            if (h[0] != 'E' || h[1] != 'N') throw new PatchFormatException("not an encoding table");
            int ck = h[3], ek = h[4], pageSize = Hex.Be16(h, 5) * 1024, pages = Hex.Be32(h, 9), espec = Hex.Be32(h, 18);
            long indexStart = 22L + espec;
            int entry = ck + 16;
            var index = read(indexStart, pages * entry);
            var first = new string[pages];
            for (int i = 0; i < pages; i++) first[i] = Hex.Of(index, i * entry, ck);
            long pagesStart = indexStart + (long)pages * entry;
            var found = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (var g in wanted.Distinct().GroupBy(w => PageOf(first, w)))
            {
                if (g.Key < 0) continue;
                var data = read(pagesStart + (long)g.Key * pageSize, pageSize);
                var want = new HashSet<string>(g, StringComparer.Ordinal);
                int pos = 0;
                while (pos + 6 + ck <= data.Length && data[pos] != 0)
                {
                    int keys = data[pos];
                    var c = Hex.Of(data, pos + 6, ck);
                    if (want.Contains(c) && pos + 6 + ck + ek <= data.Length) found[c] = Hex.Of(data, pos + 6 + ck, ek);
                    pos += 6 + ck + keys * ek;
                }
            }
            return found;
        }

        /// <summary>the page whose first key is the last one at or below [key]; -1 = before the first</summary>
        public static int PageOf(string[] first, string key)
        {
            int lo = 0, hi = first.Length - 1, r = -1;
            while (lo <= hi)
            {
                int mid = (lo + hi) / 2;
                if (string.CompareOrdinal(first[mid], key) <= 0) { r = mid; lo = mid + 1; } else hi = mid - 1;
            }
            return r;
        }
    }

    /// <summary>an archive index ("&lt;hash&gt;.index"): named by the MD5 of its 28-byte footer (checksum size 8 at 13 from the end,
    /// checked on the PC's 1,485 .index files by the Thor's updater); its entries = encoded key, size, offset in the archive</summary>
    public static class ArchiveIndex
    {
        public static bool FooterOk(byte[] tail, string name)
        {
            if (tail == null || tail.Length < 28) return false;
            int o = tail.Length - 28;
            return tail[tail.Length - 13] == 8 && Hex.Md5(tail, o, 28) == (name ?? "").ToLowerInvariant();
        }

        public static bool FileOk(string path, string name)
        {
            try
            {
                var fi = new FileInfo(path);
                if (!fi.Exists || fi.Length < 28) return false;
                using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                {
                    fs.Seek(-28, SeekOrigin.End);
                    return FooterOk(Blte.ReadExact(fs, 28), name);
                }
            }
            catch (Exception) { return false; }
        }

        /// <summary>[wanted] found in [index] (a whole .index file): ekey -> (size, offset)</summary>
        public static Dictionary<string, Tuple<long, long>> Find(byte[] index, ICollection<string> wanted)
        {
            var r = new Dictionary<string, Tuple<long, long>>(StringComparer.Ordinal);
            if (index == null || index.Length < 28) return r;
            int f = index.Length - 28;
            int blockSize = index[f + 11] * 1024, offBytes = index[f + 12], sizeBytes = index[f + 13], keySize = index[f + 14];
            long elements = BitConverter.ToUInt32(index, f + 16);
            int entry = keySize + sizeBytes + offBytes;
            if (blockSize <= 0 || entry <= 0 || keySize > 16) return r;
            int perBlock = blockSize / entry;
            long blocks = perBlock > 0 ? (elements + perBlock - 1) / perBlock : 0;
            var want = new HashSet<string>(wanted, StringComparer.Ordinal);
            for (long b = 0; b < blocks && want.Count > 0; b++)
            {
                long start = b * blockSize;
                for (int i = 0; i < perBlock; i++)
                {
                    long p = start + (long)i * entry;
                    if (p + entry > f) break;
                    var k = Hex.Of(index, (int)p, keySize);
                    if (k.Trim('0').Length == 0) break;
                    // a 9-byte key (CASC's local indexes) matches the start of a 16-byte one
                    var hit = keySize == 16 ? (want.Contains(k) ? k : null) : want.FirstOrDefault(w => w.StartsWith(k, StringComparison.Ordinal));
                    if (hit == null) continue;
                    r[hit] = Tuple.Create(Hex.BeN(index, (int)p + keySize, sizeBytes), Hex.BeN(index, (int)p + keySize + sizeBytes, offBytes));
                    want.Remove(hit);
                }
            }
            return r;
        }
    }
}
