using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace DsfDesktop.Thor
{
    public enum SwapKind { Add, Replace, Keep, Remove }

    public sealed class SwapOp
    {
        public SwapKind Kind;
        public string Rel;
        public override string ToString() => Kind + " " + Rel;
    }

    /// <summary>
    /// What an update does to the folder the user unzipped Desktop to (pure). File by file, never the folder itself: the 0.2.x swap
    /// renamed the whole folder, and Windows refuses that while anything has a file in it open (a leftover sunshine.exe, the adb
    /// server from platform-tools, an Explorer window) - it gave up after 30 s and started the old copy again, every time.
    /// A file that is in use can still be renamed aside, so here each shipped file is moved into .dsf-old and the new one copied in.
    /// User data never lives in the app folder (it is in %LOCALAPPDATA%\DSForeverDesktop: settings, pairings, Sunshine's conf, state
    /// and credentials), but anything that looks like it is kept anyway.
    /// </summary>
    public static class SwapPlan
    {
        public const string OldDir = ".dsf-old";
        /// <summary>the files the last update put in (so a file a later release drops can go; nothing else is ever removed)</summary>
        public const string Manifest = ".dsf-files.txt";

        public static string Norm(string rel) => (rel ?? "").Replace('/', '\\').Trim().TrimStart('\\');

        static bool Internal(string rel) =>
            rel.Length == 0 || rel.Equals(Manifest, StringComparison.OrdinalIgnoreCase) || rel.StartsWith(OldDir + "\\", StringComparison.OrdinalIgnoreCase)
            || rel.Equals(OldDir, StringComparison.OrdinalIgnoreCase);

        static readonly string[] UserNames = { "sunshine.conf", "sunshine_state.json", "credentials.json", "apps.json", "settings.json", "devices.json" };
        static readonly string[] UserExts = { ".log", ".pem", ".pfx", ".key" };

        /// <summary>Sunshine's config folder and anything that looks like settings, pairings, keys or a log: never overwritten or removed</summary>
        public static bool IsUserData(string rel)
        {
            rel = Norm(rel);
            if (rel.StartsWith("sunshine\\config\\", StringComparison.OrdinalIgnoreCase)) return true;
            var name = Path.GetFileName(rel);
            if (UserNames.Any(n => n.Equals(name, StringComparison.OrdinalIgnoreCase))) return true;
            if (name.StartsWith("bridge.pfx", StringComparison.OrdinalIgnoreCase)) return true;
            var ext = Path.GetExtension(name);
            return UserExts.Any(e => e.Equals(ext, StringComparison.OrdinalIgnoreCase));
        }

        /// <summary>[incoming]: the new build's files; [existing]: what the app folder has; [lastManifest]: what the last update shipped</summary>
        public static List<SwapOp> Make(IEnumerable<string> incoming, IEnumerable<string> existing, IEnumerable<string> lastManifest)
        {
            var have = new HashSet<string>((existing ?? new string[0]).Select(Norm), StringComparer.OrdinalIgnoreCase);
            var inc = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var ops = new List<SwapOp>();
            foreach (var raw in incoming ?? new string[0])
            {
                var r = Norm(raw);
                if (Internal(r) || !inc.Add(r)) continue;
                ops.Add(new SwapOp { Rel = r, Kind = !have.Contains(r) ? SwapKind.Add : IsUserData(r) ? SwapKind.Keep : SwapKind.Replace });
            }
            foreach (var raw in lastManifest ?? new string[0])
            {
                var r = Norm(raw);
                if (Internal(r) || inc.Contains(r) || !have.Contains(r) || IsUserData(r)) continue;
                if (ops.Any(o => o.Kind == SwapKind.Remove && o.Rel.Equals(r, StringComparison.OrdinalIgnoreCase))) continue;
                ops.Add(new SwapOp { Rel = r, Kind = SwapKind.Remove });
            }
            return ops.OrderBy(o => o.Kind == SwapKind.Remove ? 1 : 0).ThenBy(o => o.Rel, StringComparer.OrdinalIgnoreCase).ToList();
        }

        public static List<string> Files(string root)
        {
            if (!Directory.Exists(root)) return new List<string>();
            var full = Path.GetFullPath(root).TrimEnd('\\') + "\\";
            return Directory.GetFiles(full, "*", SearchOption.AllDirectories).Select(f => f.Substring(full.Length)).Where(r => !Internal(r)).ToList();
        }

        public static List<string> ReadManifest(string app)
        {
            try { var f = Path.Combine(app, Manifest); return File.Exists(f) ? File.ReadAllLines(f).Where(l => l.Trim().Length > 0).ToList() : new List<string>(); }
            catch (Exception) { return new List<string>(); }
        }

        /// <summary>null = the folder takes new files (no admin needed), else a terse reason</summary>
        public static string CanWrite(string dir)
        {
            try
            {
                var probe = Path.Combine(dir, ".dsf-write-" + Guid.NewGuid().ToString("N").Substring(0, 8));
                File.WriteAllText(probe, "x");
                File.Delete(probe);
                return null;
            }
            catch (UnauthorizedAccessException) { return "read-only"; }
            catch (Exception e) { return e.Message; }
        }

        /// <summary>
        /// Carries the plan out: every replaced or removed file is first moved to app\.dsf-old\&lt;stamp&gt;\ (a rename, which works even
        /// on a running exe), then the new one copied in. Anything failing puts every file back. null = done, else why not.
        /// </summary>
        public static string Execute(string next, string app, List<SwapOp> ops, Action<string> log = null)
        {
            var backup = Path.Combine(app, OldDir, DateTime.UtcNow.ToString("yyyyMMddHHmmss"));
            var done = new List<SwapOp>();
            try
            {
                foreach (var op in ops)
                {
                    if (op.Kind == SwapKind.Keep) continue;
                    var dst = Path.Combine(app, op.Rel);
                    if (op.Kind == SwapKind.Replace || op.Kind == SwapKind.Remove)
                    {
                        var aside = Path.Combine(backup, op.Rel);
                        Directory.CreateDirectory(Path.GetDirectoryName(aside));
                        File.Move(dst, aside);
                    }
                    done.Add(op);
                    if (op.Kind != SwapKind.Remove)
                    {
                        Directory.CreateDirectory(Path.GetDirectoryName(dst));
                        File.Copy(Path.Combine(next, op.Rel), dst, true);
                    }
                }
                var shipped = ops.Where(o => o.Kind != SwapKind.Remove).Select(o => o.Rel).ToList();
                File.WriteAllLines(Path.Combine(app, Manifest), shipped, new UTF8Encoding(false));
                return null;
            }
            catch (Exception e)
            {
                log?.Invoke("swap failed at " + (done.LastOrDefault()?.Rel ?? "the start") + ": " + e.Message + "; putting the old files back");
                for (int i = done.Count - 1; i >= 0; i--)
                {
                    var op = done[i];
                    var dst = Path.Combine(app, op.Rel);
                    try
                    {
                        if (op.Kind != SwapKind.Remove && File.Exists(dst)) File.Delete(dst);
                        var aside = Path.Combine(backup, op.Rel);
                        if (op.Kind != SwapKind.Add && File.Exists(aside)) File.Move(aside, dst);
                    }
                    catch (Exception e2) { log?.Invoke("restore " + op.Rel + ": " + e2.Message); }
                }
                return e.Message;
            }
        }

        /// <summary>best effort: earlier backups (a file still running when it was moved aside stays until it isn't)</summary>
        public static void CleanOld(string app)
        {
            var d = Path.Combine(app, OldDir);
            if (!Directory.Exists(d)) return;
            try { Directory.Delete(d, true); }
            catch (Exception)
            {
                foreach (var f in Directory.GetFiles(d, "*", SearchOption.AllDirectories)) try { File.Delete(f); } catch (Exception) { }
                try { Directory.Delete(d, true); } catch (Exception) { }
            }
        }
    }

    public sealed class GateInput
    {
        public string ReadyVersion;          // a verified newer build waiting; null = none
        public string Writable;              // null = the app folder takes files, else why not
        public bool Streaming;
        public bool WowFromDesktop;          // WoW started for (or kept open by) a Thor session
        public double IdleForS;              // how long neither has been true
        public string FailedVersion;         // the last helper run failed on this version...
        public double FailedAgoS = double.MaxValue;   // ...this long ago
    }

    /// <summary>when a ready update may go in (pure): never during a stream or while Desktop's WoW is up; a minute of quiet first</summary>
    public static class UpdateGate
    {
        public const double QuietS = 60, RetryAfterFailS = 6 * 3600;

        /// <summary>go = apply now; line = the window's update status ("" = nothing to say)</summary>
        public static (bool go, string line) Decide(GateInput i)
        {
            var v = i.ReadyVersion;
            if (v == null) return (false, "");
            if (i.Writable != null) return (false, v + " is out: can't write this folder (unzip Desktop to Documents)");
            if (i.FailedVersion != null && Versions.Compare(i.FailedVersion, v) == 0 && i.FailedAgoS < RetryAfterFailS)
                return (false, "update to " + v + " failed, retrying later");
            if (i.Streaming) return (false, v + " ready: after the stream");
            if (i.WowFromDesktop) return (false, v + " ready: once WoW closes");
            if (i.IdleForS < QuietS) return (false, v + " ready");
            return (true, "Updating to " + v);
        }
    }

    /// <summary>
    /// The helper: a copy of the NEW DsForeverDesktop.exe run from DataDir\update\helper (outside the app folder) with
    /// --apply-update. It waits for the old Desktop and its Sunshine to exit, swaps the files, records the result and starts Desktop.
    /// </summary>
    public static class UpdateHelper
    {
        public const string Flag = "--apply-update";
        public const string Exe = "DsForeverDesktop.exe";
        public const string ResultFile = "last-result.txt";

        public static string Arg(string[] a, string k) { int i = Array.IndexOf(a, k); return i >= 0 && i + 1 < a.Length ? a[i + 1] : null; }

        public static string[] Args(int pid, string next, string app, string data, string version) =>
            new[] { Flag, "--pid", pid.ToString(), "--from", next, "--to", app, "--data", data, "--version", version };

        public static string Quote(IEnumerable<string> args) => string.Join(" ", args.Select(a => "\"" + a.TrimEnd('\\') + "\""));

        /// <summary>"ok|fail &lt;version&gt; &lt;utc ticks&gt; &lt;why&gt;"</summary>
        public static void WriteResult(string updateDir, bool ok, string version, string why)
        {
            try { File.WriteAllText(Path.Combine(updateDir, ResultFile), (ok ? "ok" : "fail") + " " + version + " " + DateTime.UtcNow.Ticks + " " + (why ?? "").Replace("\r", " ").Replace("\n", " ")); }
            catch (Exception) { }
        }

        public static (bool ok, string version, DateTime at, string why)? ReadResult(string updateDir)
        {
            try
            {
                var f = Path.Combine(updateDir, ResultFile);
                if (!File.Exists(f)) return null;
                var p = File.ReadAllText(f).Trim().Split(new[] { ' ' }, 4);
                if (p.Length < 3 || !long.TryParse(p[2], out var t)) return null;
                return (p[0] == "ok", p[1], new DateTime(t, DateTimeKind.Utc), p.Length > 3 ? p[3] : "");
            }
            catch (Exception) { return null; }
        }

        public static int Run(string[] args)
        {
            var data = Arg(args, "--data");
            var next = Arg(args, "--from");
            var app = Arg(args, "--to");
            var ver = Arg(args, "--version") ?? "?";
            int.TryParse(Arg(args, "--pid"), out var pid);
            if (data != null) Log.Init(Path.Combine(data, "desktop.log"));
            var updateDir = data != null ? Path.Combine(data, "update") : Path.GetTempPath();
            if (next == null || app == null || !File.Exists(Path.Combine(next, Exe)))
            {
                Log.I("update helper: bad arguments");
                WriteResult(updateDir, false, ver, "bad arguments");
                return 2;
            }
            Log.I("update helper: waiting for Desktop (pid " + pid + ") to close");
            WaitExit(pid, 120);
            WaitForAppProcesses(app, 30);
            var why = SwapPlan.CanWrite(app);
            if (why == null)
            {
                var ops = SwapPlan.Make(SwapPlan.Files(next), SwapPlan.Files(app), SwapPlan.ReadManifest(app));
                Log.I("update helper: " + ops.Count(o => o.Kind == SwapKind.Replace) + " replaced, " + ops.Count(o => o.Kind == SwapKind.Add) + " added, "
                    + ops.Count(o => o.Kind == SwapKind.Remove) + " removed, " + ops.Count(o => o.Kind == SwapKind.Keep) + " kept");
                why = SwapPlan.Execute(next, app, ops, s => Log.I("update helper: " + s));
            }
            else why = "the folder is " + why;
            WriteResult(updateDir, why == null, ver, why);
            Log.I(why == null ? "update helper: Desktop " + ver + " is in" : "update helper: " + ver + " not applied: " + why);
            if (why == null) { try { Directory.Delete(next, true); } catch (Exception) { } SwapPlan.CleanOld(app); }
            try
            {
                Process.Start(new ProcessStartInfo(Path.Combine(app, Exe), "--tray") { UseShellExecute = true, WorkingDirectory = app })?.Dispose();
            }
            catch (Exception e) { Log.I("update helper: couldn't start Desktop: " + e.Message); }
            return why == null ? 0 : 1;
        }

        static void WaitExit(int pid, int seconds)
        {
            if (pid <= 0) return;
            try { using (var p = Process.GetProcessById(pid)) p.WaitForExit(seconds * 1000); }
            catch (ArgumentException) { }   // already gone
            catch (Exception) { }
        }

        /// <summary>Desktop's own processes left in the folder: sunshine.exe is waited for, then ended (it would hold the streaming ports
        /// against the new one); anything else (the adb server) is left: its file is renamed aside while it runs</summary>
        static void WaitForAppProcesses(string app, int seconds)
        {
            var root = Path.GetFullPath(app).TrimEnd('\\') + "\\";
            var until = DateTime.UtcNow.AddSeconds(seconds);
            while (true)
            {
                var ours = Processes(root).Where(x => x.name.Equals("sunshine.exe", StringComparison.OrdinalIgnoreCase)).ToList();
                if (ours.Count == 0) return;
                if (DateTime.UtcNow > until)
                {
                    foreach (var o in ours)
                        try { using (var p = Process.GetProcessById(o.pid)) { Log.I("update helper: ending sunshine.exe pid " + o.pid); p.Kill(); p.WaitForExit(5000); } }
                        catch (Exception e) { Log.I("update helper: sunshine pid " + o.pid + ": " + e.Message); }
                    return;
                }
                Thread.Sleep(500);
            }
        }

        static List<(int pid, string name)> Processes(string root)
        {
            var res = new List<(int, string)>();
            foreach (var p in Process.GetProcesses())
            {
                try
                {
                    var path = ImagePath(p.Id);
                    if (path != null && path.StartsWith(root, StringComparison.OrdinalIgnoreCase)) res.Add((p.Id, Path.GetFileName(path)));
                }
                catch (Exception) { }
                finally { p.Dispose(); }
            }
            return res;
        }

        [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr OpenProcess(int access, bool inherit, int pid);
        [DllImport("kernel32.dll", SetLastError = true)] static extern bool CloseHandle(IntPtr h);
        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern bool QueryFullProcessImageName(IntPtr h, int flags, StringBuilder name, ref int size);

        public static string ImagePath(int pid)
        {
            var h = OpenProcess(0x1000 /* PROCESS_QUERY_LIMITED_INFORMATION */, false, pid);
            if (h == IntPtr.Zero) return null;
            try
            {
                var sb = new StringBuilder(1024);
                int n = sb.Capacity;
                return QueryFullProcessImageName(h, 0, sb, ref n) ? sb.ToString(0, n) : null;
            }
            finally { CloseHandle(h); }
        }
    }
}
