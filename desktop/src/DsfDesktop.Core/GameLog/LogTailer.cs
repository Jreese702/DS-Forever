using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace DsfDesktop.GameLog
{
    /// <summary>
    /// Follows one General.log and turns new bytes into <see cref="LogEvent"/>s. Poll-driven (the host calls <see cref="Poll"/> every
    /// ~50 ms, as the APK does) and synchronous, so tests drive it with real temp files and no timers.
    ///
    /// What it has to survive, from the APK's measured facts plus Windows:
    ///  - the log is block-buffered (~48 KiB): data arrives in bursts, and a read can end mid-line or mid UTF-8 character, so the
    ///    carry is kept as BYTES and only whole lines are decoded;
    ///  - WoW truncates the log at every launch: size &lt; offset means a restart, resync from 0;
    ///  - a restart can also outrun a poll (the new log already longer than our offset), or the file can be deleted and made again:
    ///    the file's identity (volume + file index) and its first bytes are remembered, and either changing is a restart too. The
    ///    creation time is NOT used: Windows' file-system tunnelling gives a re-made file the old creation time;
    ///  - the file is opened per poll with FileShare.ReadWrite | Delete and closed at once, so WoW can always truncate, rotate or
    ///    delete it under us;
    ///  - NULs (a sparse hole) are dropped, as LogTail.feed does;
    ///  - a fresh start against a big log reads at most <see cref="MaxBacklog"/> from its end, starting at a line boundary.
    /// </summary>
    public sealed class LogTailer
    {
        public const long MaxBacklog = 8L * 1024 * 1024;
        public const int MaxRead = 8 * 1024 * 1024;
        const int HeadBytes = 64;
        const int MaxCarry = 1024 * 1024;

        readonly WcbAssembler asm = new WcbAssembler();
        string path;
        long offset;
        byte[] carry = new byte[0];
        bool fresh = true;
        bool skipToNewline;
        string identity;
        byte[] head;
        string lastStatus;

        public LogTailer(string path) { this.path = path; }

        public string Path => path;
        public long Offset => offset;
        public int Gaps => asm.Gaps;
        public bool FileExists { get; private set; }
        /// <summary>every whole line as it is read (the sign-in lines: <see cref="DsfDesktop.Wow.LoginLog"/>), on the polling thread</summary>
        public Action<string> RawLine;

        /// <summary>follow another file (LogFollow switched flavor): starts it like a fresh client, from its capped backlog</summary>
        public void SetPath(string p)
        {
            if (string.Equals(p, path, StringComparison.OrdinalIgnoreCase)) return;
            path = p;
            ResetAll();
            fresh = true;
        }

        void ResetAll()
        {
            offset = 0;
            carry = new byte[0];
            asm.Reset();
            identity = null;
            head = null;
            skipToNewline = false;
        }

        void Say(List<LogEvent> ev, string s)
        {
            if (s == lastStatus) return;
            lastStatus = s;
            ev.Add(LogEvent.Status(s));
        }

        public List<LogEvent> Poll()
        {
            var ev = new List<LogEvent>();
            if (string.IsNullOrEmpty(path)) { Say(ev, "no game folder"); return ev; }
            FileStream fs;
            try
            {
                fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 65536, FileOptions.SequentialScan);
            }
            catch (FileNotFoundException) { return Missing(ev); }
            catch (DirectoryNotFoundException) { return Missing(ev); }
            catch (IOException e) { Say(ev, "log busy: " + e.Message); return ev; }
            catch (UnauthorizedAccessException e) { Say(ev, "log not readable: " + e.Message); return ev; }

            using (fs)
            {
                FileExists = true;
                long len = fs.Length;
                string id = Identity(fs.SafeFileHandle);
                byte[] newHead = len >= HeadBytes ? ReadAt(fs, 0, HeadBytes) : null;

                bool restarted = false;
                if (!fresh)
                {
                    if (len < offset) restarted = true;
                    else if (identity != null && id != null && id != identity) restarted = true;
                    else if (head != null && newHead != null && !Same(head, newHead)) restarted = true;
                }
                if (restarted)
                {
                    ResetAll();
                    ev.Add(new LogEvent { Kind = LogEventKind.Restart, Text = "client restarted, resyncing" });
                    lastStatus = null;
                }
                identity = id;
                if (newHead != null && head == null) head = newHead;

                if (fresh)
                {
                    fresh = false;
                    if (len > MaxBacklog) { offset = len - MaxBacklog; skipToNewline = true; }
                    Say(ev, "reading the game's log");
                }
                if (len == offset) return ev;

                int want = (int)Math.Min(len - offset, MaxRead);
                var buf = ReadAt(fs, offset, want);
                if (buf.Length == 0) return ev;
                offset += buf.Length;
                Feed(buf, ev);
                // a backlog bigger than one read: the next poll carries on from the new offset
            }
            return ev;
        }

        List<LogEvent> Missing(List<LogEvent> ev)
        {
            if (FileExists) { ResetAll(); fresh = true; }
            FileExists = false;
            Say(ev, "waiting for game");
            return ev;
        }

        static byte[] ReadAt(FileStream fs, long at, int n)
        {
            fs.Seek(at, SeekOrigin.Begin);
            var buf = new byte[n];
            int got = 0;
            while (got < n)
            {
                int r = fs.Read(buf, got, n - got);
                if (r <= 0) break;
                got += r;
            }
            if (got == n) return buf;
            var cut = new byte[got];
            Buffer.BlockCopy(buf, 0, cut, 0, got);
            return cut;
        }

        static bool Same(byte[] a, byte[] b)
        {
            if (a.Length != b.Length) return false;
            for (int i = 0; i < a.Length; i++) if (a[i] != b[i]) return false;
            return true;
        }

        /// <summary>split new bytes into whole lines (NULs dropped), keep a partial last line as bytes</summary>
        void Feed(byte[] buf, List<LogEvent> ev)
        {
            var all = new byte[carry.Length + buf.Length];
            int n = 0;
            foreach (var b in carry) all[n++] = b;
            foreach (var b in buf) if (b != 0) all[n++] = b;

            int start = 0;
            if (skipToNewline)
            {
                int nl = Array.IndexOf(all, (byte)'\n', 0, n);
                if (nl < 0) { carry = Slice(all, 0, Math.Min(n, MaxCarry)); return; }
                start = nl + 1;
                skipToNewline = false;
            }
            int lineStart = start;
            for (int i = start; i < n; i++)
            {
                if (all[i] != (byte)'\n') continue;
                var text = Encoding.UTF8.GetString(all, lineStart, i - lineStart);
                try { RawLine?.Invoke(text); } catch (Exception) { }
                asm.Line(text, ev);
                lineStart = i + 1;
            }
            int rest = n - lineStart;
            // a "line" over 1 MB with no newline is not the add-on's; drop it rather than grow
            carry = rest > MaxCarry ? new byte[0] : Slice(all, lineStart, rest);
        }

        static byte[] Slice(byte[] a, int at, int n)
        {
            var r = new byte[n];
            Buffer.BlockCopy(a, at, r, 0, n);
            return r;
        }

        // ---- the file's identity: volume serial + 64-bit file index (changes when a file is deleted and made again)
        [StructLayout(LayoutKind.Sequential)]
        struct ByHandleFileInformation
        {
            public uint FileAttributes;
            public System.Runtime.InteropServices.ComTypes.FILETIME CreationTime, LastAccessTime, LastWriteTime;
            public uint VolumeSerialNumber, FileSizeHigh, FileSizeLow, NumberOfLinks, FileIndexHigh, FileIndexLow;
        }

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool GetFileInformationByHandle(SafeFileHandle h, out ByHandleFileInformation info);

        static string Identity(SafeFileHandle h)
        {
            try
            {
                if (!GetFileInformationByHandle(h, out var i)) return null;
                return i.VolumeSerialNumber.ToString("x8") + ":" + i.FileIndexHigh.ToString("x8") + i.FileIndexLow.ToString("x8");
            }
            catch (Exception) { return null; }
        }
    }
}
