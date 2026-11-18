using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// .build.info as text (the judge's Step 0, 2026-11-18): its header line and one line per product, kept byte for byte. The
    /// Battle.net Agent dropped the wowf row at 06:32 on the user's PC (only the retail row was left), so 0.2.34 read "no install
    /// row" = Unknown and would have started WoW. The pure rules: find a product's row, add one back under the file's own header
    /// (other lines untouched, the file's own line ending kept). Never .product.db, never the CASC store.
    /// </summary>
    public static class BuildInfoFile
    {
        /// <summary>the header's column names ("Build Key!HEX:16" -> "Build Key")</summary>
        public static List<string> Columns(string header) =>
            (header ?? "").Split('|').Select(c => c.Split('!')[0].Trim()).ToList();

        /// <summary>the file's lines without their endings, empty ones dropped</summary>
        public static List<string> Lines(string text) =>
            (text ?? "").Replace("\r\n", "\n").Split('\n').Where(l => l.Trim().Length > 0).ToList();

        /// <summary>the line of [product] (its active one first), null = none</summary>
        public static string RowLine(string text, string product)
        {
            var lines = Lines(text);
            if (lines.Count < 2) return null;
            var cols = Columns(lines[0]);
            int iProduct = cols.IndexOf("Product"), iActive = cols.IndexOf("Active");
            if (iProduct < 0) return null;
            var mine = lines.Skip(1).Where(l => Field(l, iProduct) == product).ToList();
            return mine.FirstOrDefault(l => Field(l, iActive) == "1") ?? mine.FirstOrDefault();
        }

        static string Field(string line, int i)
        {
            var f = line.Split('|');
            return i >= 0 && i < f.Length ? f[i].Trim() : "";
        }

        /// <summary>a row as column name -> value under [header]</summary>
        public static Dictionary<string, string> Values(string header, string line)
        {
            var cols = Columns(header);
            var f = (line ?? "").Split('|');
            var d = new Dictionary<string, string>(StringComparer.Ordinal);
            for (int i = 0; i < cols.Count; i++) d[cols[i]] = i < f.Length ? f[i] : "";
            return d;
        }

        /// <summary>the columns a row must have filled to be a usable install record (the game finds its data through them)</summary>
        public static readonly string[] Needed = { "Branch", "Build Key", "CDN Key", "Version", "Product" };

        /// <summary>
        /// [text] with [product]'s row added back from a saved one ([savedHeader] + [savedLine]), mapped by column name onto the file's
        /// own header; every other line stays as it was, the file's line ending is kept. null = not done: the file has a row of
        /// [product] already (never replaced: the Agent may be writing a newer one), no header, or the saved row lacks a needed value.
        /// </summary>
        public static string AddRow(string text, string product, string savedHeader, string savedLine)
        {
            if (string.IsNullOrEmpty(text) || RowLine(text, product) != null) return null;
            var lines = Lines(text);
            if (lines.Count < 1) return null;
            var saved = Values(savedHeader, savedLine);
            if (!saved.TryGetValue("Product", out var p) || p.Trim() != product) return null;
            foreach (var n in Needed) if (!saved.TryGetValue(n, out var v) || v.Trim().Length == 0) return null;
            var cols = Columns(lines[0]);
            foreach (var n in Needed) if (!cols.Contains(n)) return null;
            var row = string.Join("|", cols.Select(c => saved.TryGetValue(c, out var v) ? v : ""));
            string nl = text.Contains("\r\n") ? "\r\n" : "\n";
            bool endsWithNl = text.EndsWith("\n");
            return (endsWithNl ? text : text + nl) + row + (endsWithNl ? nl : "");
        }

        /// <summary>the columns another product's row holds for itself, emptied when it is the template of a new row</summary>
        public static readonly string[] OwnColumns = { "Build Key", "CDN Key", "Install Key", "IM Size", "Version", "KeyRing", "Armadillo", "Last Activated" };

        /// <summary>
        /// THE PATCH'S LAST STEP (Desktop 0.2.36's self-patcher): [text] with [product]'s row set to [set] (column name -> value; a blank
        /// value never replaces one: an empty CDN Servers crashes the client at start). A missing row is made from [templateHeader] +
        /// [templateLine] (Desktop's copy of the last good row, else another product's row: its Tags and CDN columns; that product's own
        /// columns emptied), Active 1. Every other line byte for byte, the file's line ending kept. null = not done (no header, or the
        /// row would lack a needed value).
        /// </summary>
        public static string Upsert(string text, string product, IDictionary<string, string> set, string templateHeader = null, string templateLine = null)
        {
            if (string.IsNullOrEmpty(text)) return null;
            var parts = text.Split('\n');
            var header = parts[0].TrimEnd('\r');
            var cols = Columns(header);
            int iProduct = cols.IndexOf("Product"), iActive = cols.IndexOf("Active");
            if (iProduct < 0 || cols.Count < 2) return null;
            int at = -1;
            for (int i = 1; i < parts.Length; i++)
            {
                var l = parts[i].TrimEnd('\r');
                if (l.Trim().Length == 0 || Field(l, iProduct) != product) continue;
                if (at < 0 || (Field(l, iActive) == "1" && Field(parts[at].TrimEnd('\r'), iActive) != "1")) at = i;
            }
            Dictionary<string, string> row;
            if (at >= 0) row = Values(header, parts[at].TrimEnd('\r'));
            else
            {
                row = cols.ToDictionary(c => c, c => "", StringComparer.Ordinal);
                if (!string.IsNullOrEmpty(templateHeader) && !string.IsNullOrEmpty(templateLine))
                {
                    var t = Values(templateHeader, templateLine);
                    bool other = !t.TryGetValue("Product", out var tp) || tp.Trim() != product;
                    foreach (var c in cols)
                        if (t.TryGetValue(c, out var v) && !(other && OwnColumns.Contains(c))) row[c] = v;
                }
                row["Active"] = "1";
                row["Product"] = product;
            }
            foreach (var kv in set)
                if (row.ContainsKey(kv.Key) && !string.IsNullOrWhiteSpace(kv.Value)) row[kv.Key] = kv.Value;
            if (set.TryGetValue("Branch", out var region) && !string.IsNullOrWhiteSpace(region) && row.ContainsKey("Tags"))
                row["Tags"] = RegionTags(row["Tags"], region);
            foreach (var n in Needed) if (!row.TryGetValue(n, out var v) || v.Trim().Length == 0) return null;
            var line = string.Join("|", cols.Select(c => row[c]));
            if (at >= 0)
            {
                parts[at] = line + (parts[at].EndsWith("\r") ? "\r" : "");
                return string.Join("\n", parts);
            }
            string nl = text.Contains("\r\n") ? "\r\n" : "\n";
            bool endsWithNl = text.EndsWith("\n");
            return (endsWithNl ? text : text + nl) + line + (endsWithNl ? nl : "");
        }

        /// <summary>Tags with its region tag ("US?", "EU?", "KR?", "TW?", "CN?") made [region]'s (the Thor's regionTags)</summary>
        public static string RegionTags(string tags, string region) =>
            Regex.Replace(tags ?? "", @"(?<=^|[ :])(US|EU|KR|TW|CN)\?(?=$|[ :])", region.Trim().ToUpperInvariant() + "?");

        /// <summary>the version in an exe's file version text ("1.60.1.70938"; the numeric parts of Wow.exe's version resource are not
        /// it: measured 2026-11-18 on the user's 70938 Wow.exe, FileMajorPart 160 / FileBuildPart 7093), null = none</summary>
        public static string VersionIn(string fileVersion)
        {
            var m = Regex.Match(fileVersion ?? "", @"\d+\.\d+\.\d+\.\d+");
            return m.Success ? m.Value : null;
        }

        /// <summary>Wow.exe's own version, read only (FileVersionInfo): null = no such file, "" = a file with no readable version</summary>
        public static string ExeVersion(string exe)
        {
            try
            {
                if (string.IsNullOrEmpty(exe) || !File.Exists(exe)) return null;
                var vi = FileVersionInfo.GetVersionInfo(exe);
                return VersionIn(vi.FileVersion) ?? VersionIn(vi.ProductVersion) ?? "";
            }
            catch (Exception) { return ""; }
        }
    }

    /// <summary>
    /// Desktop's own copy of the last good row of one product (the judge's Step 0): saved whenever .build.info has the row and its
    /// Version is Wow.exe's own; written back only when the row is MISSING and the saved row is for the build Wow.exe is now. The
    /// file is replaced through a temp file with a .build.info.dsf-bak backup. Paths are injected so tests use temp folders only.
    /// </summary>
    public sealed class BuildInfoKeeper
    {
        readonly Func<string> root;
        readonly string cacheFile;
        readonly string product;
        string savedText;

        /// <param name="root">the install's root (the folder of .build.info), null = none</param>
        /// <param name="cacheFile">Desktop's copy (DataDir\patch\wowf_row.json)</param>
        public BuildInfoKeeper(Func<string> root, string cacheFile, string product = "wowf")
        {
            this.root = root; this.cacheFile = cacheFile; this.product = product;
        }

        string FilePath { get { var r = root(); return r == null ? null : Path.Combine(r, ".build.info"); } }

        static string ReadShared(string path)
        {
            using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
            using (var r = new StreamReader(fs, new UTF8Encoding(false))) return r.ReadToEnd();
        }

        /// <summary>the saved (header, line, version), null = none</summary>
        public Tuple<string, string, string> Saved()
        {
            try
            {
                if (!File.Exists(cacheFile)) return null;
                var o = Json.ReadObject(File.ReadAllText(cacheFile));
                var h = o.Str("header"); var l = o.Str("row"); var v = o.Str("version");
                return string.IsNullOrEmpty(h) || string.IsNullOrEmpty(l) ? null : Tuple.Create(h, l, v ?? "");
            }
            catch (Exception) { return null; }
        }

        /// <summary>the row is there and is the build Wow.exe is ([exeVersion]): Desktop's copy is brought up to date</summary>
        public void Remember(string exeVersion)
        {
            try
            {
                var path = FilePath;
                if (path == null || !File.Exists(path)) return;
                var text = ReadShared(path);
                var line = BuildInfoFile.RowLine(text, product);
                if (line == null) return;
                var header = BuildInfoFile.Lines(text)[0];
                var v = BuildInfoFile.Values(header, line);
                var ver = v.TryGetValue("Version", out var x) ? x.Trim() : "";
                if (GameVersion.Build(ver) <= 0 || GameVersion.Build(ver) != GameVersion.Build(exeVersion)) return;
                if (BuildInfoFile.Needed.Any(n => !v.TryGetValue(n, out var y) || y.Trim().Length == 0)) return;
                var json = Json.Write(new Dictionary<string, object> { ["product"] = product, ["version"] = ver, ["header"] = header, ["row"] = line });
                if (json == savedText) return;
                if (File.Exists(cacheFile) && File.ReadAllText(cacheFile) == json) { savedText = json; return; }
                Directory.CreateDirectory(Path.GetDirectoryName(cacheFile));
                var tmp = cacheFile + ".tmp";
                File.WriteAllText(tmp, json);
                if (File.Exists(cacheFile)) File.Replace(tmp, cacheFile, null); else File.Move(tmp, cacheFile);
                savedText = json;
                Log.I("game record: saved Desktop's copy of the " + product + " row (" + ver + ")");
            }
            catch (Exception e) { Log.I("game record: couldn't save its copy: " + e.Message); }
        }

        /// <summary>
        /// The row is missing: add Desktop's copy back when it is for the build Wow.exe is ([exeVersion]). true = written (the caller
        /// reads .build.info again). Never replaces a row that is there.
        /// </summary>
        public bool Repair(string exeVersion)
        {
            try
            {
                var path = FilePath;
                if (path == null || !File.Exists(path)) return false;
                var text = ReadShared(path);
                if (BuildInfoFile.RowLine(text, product) != null) return false;
                var saved = Saved();
                if (saved == null) { Log.I("game record: the " + product + " row is missing and Desktop has no copy of it"); return false; }
                if (GameVersion.Build(saved.Item3) <= 0 || GameVersion.Build(saved.Item3) != GameVersion.Build(exeVersion))
                {
                    Log.I("game record: the " + product + " row is missing; Desktop's copy is " + saved.Item3 + ", the game is " + (string.IsNullOrEmpty(exeVersion) ? "unknown" : exeVersion) + ": not used");
                    return false;
                }
                var next = BuildInfoFile.AddRow(text, product, saved.Item1, saved.Item2);
                if (next == null) { Log.I("game record: Desktop's copy doesn't fit .build.info: not used"); return false; }
                var tmp = path + ".dsf-tmp";
                bool bom;
                using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                    bom = fs.Length >= 3 && fs.ReadByte() == 0xEF && fs.ReadByte() == 0xBB && fs.ReadByte() == 0xBF;
                File.WriteAllBytes(tmp, new UTF8Encoding(bom).GetPreamble().Concat(new UTF8Encoding(false).GetBytes(next)).ToArray());
                // the Agent may have written meanwhile: only over the text this row was added to
                if (ReadShared(path) != text) { File.Delete(tmp); Log.I("game record: .build.info changed meanwhile: not written"); return false; }
                File.Replace(tmp, path, path + ".dsf-bak");
                Patch.AgentWatch.Mine(path);
                Log.I("game record: the " + product + " row was missing: added back from Desktop's copy (" + saved.Item3 + ")");
                return true;
            }
            catch (Exception e) { Log.I("game record: couldn't add the row back: " + e.Message); return false; }
        }
    }
}
