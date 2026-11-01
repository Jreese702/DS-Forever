using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace DsfDesktop.Thor
{
    /// <summary>
    /// DESKTOP UPDATES ITSELF (the user 2026-11-01: "Can we add an auto update feature to the desktop app?"): the zip of the latest public
    /// release, checked against its SHA256SUMS like the APK, unpacked beside the data; it goes in once no Thor is connected. A small script
    /// outlives the app, swaps the folders and starts the new one; anything going wrong starts the old folder again. Settings and pairing
    /// live in DataDir, so they stay.
    /// </summary>
    public sealed class DesktopUpdate
    {
        public const double CheckEveryS = 6 * 3600, FirstCheckS = 60;
        const string Exe = "DsForeverDesktop.exe";

        readonly string dir;
        readonly ReleaseFeed feed = new ReleaseFeed();
        volatile string ready;

        public DesktopUpdate(string dataDir) { dir = Path.Combine(dataDir, "update"); }

        /// <summary>the version in the zip's name; null = not one of ours</summary>
        public static string ZipVersion(string name)
        {
            var m = Regex.Match(name ?? "", @"^DsForeverDesktop-([0-9][0-9.]*)-win-x64\.zip$", RegexOptions.IgnoreCase);
            return m.Success ? m.Groups[1].Value : null;
        }

        public static bool Newer(Release rel, string mine) =>
            rel?.DesktopName != null && Versions.Compare(ZipVersion(rel.DesktopName), mine) > 0;

        /// <summary>one look at GitHub: a newer verified build unpacked and waiting. Failures wait for the next look</summary>
        public async Task Check(string mine, CancellationToken ct)
        {
            if (ready != null) return;
            var rel = await feed.Latest(ct).ConfigureAwait(false);
            if (!Newer(rel, mine)) return;
            Log.I("update: Desktop " + ZipVersion(rel.DesktopName) + " found, downloading");
            var zip = await feed.EnsureFile(rel.DesktopName, rel.DesktopUrl, rel.DesktopSize, rel.SumsUrl, dir, "*.zip", null, ct).ConfigureAwait(false);
            var next = Path.Combine(dir, "next");
            if (Directory.Exists(next)) Directory.Delete(next, true);
            ZipFile.ExtractToDirectory(zip, next);
            if (!File.Exists(Path.Combine(next, Exe))) { Directory.Delete(next, true); throw new InvalidDataException("the zip has no " + Exe); }
            ready = next;
            Log.I("update: ready, it goes in when no Thor is connected");
        }

        public bool Ready => ready != null;

        /// <summary>starts the script that waits for this process to end; true = the app must now quit</summary>
        public bool Begin()
        {
            try
            {
                var me = Process.GetCurrentProcess();
                var script = Path.Combine(dir, "apply.cmd");
                File.WriteAllText(script, Script(me.Id, ready, Path.GetDirectoryName(me.MainModule.FileName)), new UTF8Encoding(false));
                Process.Start(new ProcessStartInfo("cmd.exe", "/c \"\"" + script + "\"\"") { UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = dir })?.Dispose();
                Log.I("update: restarting into the new Desktop");
                return true;
            }
            catch (Exception e) { Log.I("update: " + e.Message); ready = null; return false; }
        }

        /// <summary>the swap: the new folder is copied beside the app first, so the renames cannot be left half done across drives</summary>
        public static string Script(int pid, string next, string app)
        {
            Func<string, string> q = s => s.TrimEnd('\\').Replace("%", "%%");
            var name = Path.GetFileName(app.TrimEnd('\\')).Replace("%", "%%");
            var s2 = new StringBuilder();
            s2.Append("@echo off\r\nchcp 65001 >nul\r\n");
            s2.Append("set \"APP=" + q(app) + "\"\r\nset \"NEXT=" + q(next) + "\"\r\nset \"NAME=" + name + "\"\r\n");
            s2.Append("cd /d \"%~dp0\"\r\n");
            s2.Append(":wait\r\ntasklist /FI \"PID eq " + pid + "\" /NH | find \" " + pid + " \" >nul && (ping -n 2 127.0.0.1 >nul & goto wait)\r\n");
            s2.Append("if exist \"%APP%.new\" rmdir /s /q \"%APP%.new\"\r\nif exist \"%APP%.old\" rmdir /s /q \"%APP%.old\"\r\n");
            s2.Append("robocopy \"%NEXT%\" \"%APP%.new\" /E /R:2 /W:1 >nul\r\nif errorlevel 8 goto fail\r\n");
            s2.Append("set /a TRIES=0\r\n:swap\r\nren \"%APP%\" \"%NAME%.old\" 2>nul && goto swapped\r\n");
            s2.Append("set /a TRIES+=1\r\nif %TRIES% geq 30 goto fail\r\nping -n 2 127.0.0.1 >nul\r\ngoto swap\r\n");
            s2.Append(":swapped\r\nren \"%APP%.new\" \"%NAME%\" 2>nul && goto done\r\nren \"%APP%.old\" \"%NAME%\"\r\n");
            s2.Append(":fail\r\nif exist \"%APP%.new\" rmdir /s /q \"%APP%.new\"\r\nstart \"\" \"%APP%\\" + Exe + "\" --tray\r\nexit /b 1\r\n");
            s2.Append(":done\r\nstart \"\" \"%APP%\\" + Exe + "\" --tray\r\nrmdir /s /q \"%NEXT%\"\r\nrmdir /s /q \"%APP%.old\"\r\nexit /b 0\r\n");
            return s2.ToString();
        }
    }
}
