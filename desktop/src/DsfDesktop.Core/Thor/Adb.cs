using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;

namespace DsfDesktop.Thor
{
    public sealed class AdbResult
    {
        public int Exit = -1;
        public string Out = "", Err = "";
        public bool TimedOut;
        public string All => (Out + "\n" + Err).Trim();
    }

    /// <summary>runs adb; the real one starts adb.exe, tests script the answers</summary>
    public interface IAdb
    {
        string Path { get; }
        AdbResult Run(int timeoutMs, params string[] args);
        /// <summary>
        /// Runs a long command (logcat), calls onStarted once it is running, and reads lines until stopWhen says so (true) or the
        /// time runs out (false). The process is ended either way.
        /// </summary>
        bool Watch(int timeoutMs, Func<string, bool> stopWhen, Action onStarted, params string[] args);
    }

    public sealed class AdbProcess : IAdb
    {
        public string Path { get; }
        public AdbProcess(string path) { Path = path; }

        /// <summary>
        /// The adb to use: DSF_ADB, else one already on PATH (its server is the one other tools on this PC use, so Desktop doesn't
        /// fight it with a second version), else the platform-tools bundled with Desktop.
        /// </summary>
        public static string Locate(string appDir)
        {
            var env = Environment.GetEnvironmentVariable("DSF_ADB");
            if (!string.IsNullOrWhiteSpace(env) && File.Exists(env)) return env;
            foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';'))
            {
                try
                {
                    var p = System.IO.Path.Combine(dir.Trim().Trim('"'), "adb.exe");
                    if (dir.Trim().Length > 0 && File.Exists(p)) return p;
                }
                catch (Exception) { }
            }
            var bundled = System.IO.Path.Combine(appDir, "platform-tools", "adb.exe");
            return File.Exists(bundled) ? bundled : null;
        }

        public static string Quote(string a) => a.Length > 0 && a.IndexOfAny(new[] { ' ', '\t', '"' }) < 0 ? a : "\"" + a.Replace("\"", "\\\"") + "\"";

        ProcessStartInfo Psi(string[] args) => new ProcessStartInfo(Path, string.Join(" ", args.Select(Quote)))
        {
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8,
        };

        public AdbResult Run(int timeoutMs, params string[] args)
        {
            var r = new AdbResult();
            using (var p = new Process { StartInfo = Psi(args) })
            {
                var o = new StringBuilder();
                var e = new StringBuilder();
                p.OutputDataReceived += (s, a) => { if (a.Data != null) lock (o) o.AppendLine(a.Data); };
                p.ErrorDataReceived += (s, a) => { if (a.Data != null) lock (e) e.AppendLine(a.Data); };
                p.Start();
                p.BeginOutputReadLine();
                p.BeginErrorReadLine();
                if (!p.WaitForExit(timeoutMs)) { r.TimedOut = true; try { p.Kill(); } catch (Exception) { } }
                else p.WaitForExit();
                lock (o) r.Out = o.ToString();
                lock (e) r.Err = e.ToString();
                r.Exit = r.TimedOut ? -1 : p.ExitCode;
            }
            return r;
        }

        public bool Watch(int timeoutMs, Func<string, bool> stopWhen, Action onStarted, params string[] args)
        {
            using (var p = new Process { StartInfo = Psi(args) })
            using (var hit = new ManualResetEventSlim(false))
            {
                p.OutputDataReceived += (s, a) => { if (a.Data != null && stopWhen(a.Data)) hit.Set(); };
                p.ErrorDataReceived += (s, a) => { };
                p.Start();
                p.BeginOutputReadLine();
                p.BeginErrorReadLine();
                Thread.Sleep(300);   // logcat attached before the event it waits for is triggered
                onStarted?.Invoke();
                bool ok = hit.Wait(timeoutMs);
                try { if (!p.HasExited) p.Kill(); } catch (Exception) { }
                return ok;
            }
        }
    }
}
