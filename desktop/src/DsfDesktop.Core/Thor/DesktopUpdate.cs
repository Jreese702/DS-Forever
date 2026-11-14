using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace DsfDesktop.Thor
{
    /// <summary>
    /// DESKTOP UPDATES ITSELF (the user 2026-11-01, and 11-13 "we need this shit to have an auto update feature"): the zip of the latest
    /// public release, checked against its SHA256SUMS, unpacked to DataDir\update\next. It goes in on its own once no stream is on and
    /// Desktop's WoW is closed (UpdateGate): a copy of the NEW exe runs from DataDir\update\helper, waits for this one and its Sunshine
    /// to exit, swaps the files one by one (SwapPlan) and starts Desktop again. Settings, pairings and Sunshine's state live in DataDir,
    /// so they stay. DSF_UPDATE_FEED points the check at another latest-release JSON (a local file for a test); DSF_NO_UPDATE=1 turns
    /// it off.
    /// </summary>
    public sealed class DesktopUpdate
    {
        public const double CheckEveryS = 3 * 3600, FirstCheckS = 20;
        const string Exe = UpdateHelper.Exe;

        readonly string dataDir, dir;
        readonly ReleaseFeed feed;
        volatile string ready, readyVersion;
        volatile string status = "";

        public DesktopUpdate(string dataDir, string feedUrl = null)
        {
            this.dataDir = dataDir;
            dir = Path.Combine(dataDir, "update");
            feed = new ReleaseFeed(feedUrl ?? Environment.GetEnvironmentVariable("DSF_UPDATE_FEED"));
        }

        public static bool Disabled => Environment.GetEnvironmentVariable("DSF_NO_UPDATE") == "1";

        public string UpdateDir => dir;
        /// <summary>what the last check found or did ("" = up to date / not checked yet)</summary>
        public string Status => status;
        public bool Ready => ready != null;
        public string ReadyVersion => readyVersion;
        public DateTime? LastCheck { get; private set; }

        /// <summary>the version in the zip's name; null = not one of ours</summary>
        public static string ZipVersion(string name)
        {
            var m = Regex.Match(name ?? "", @"^DsForeverDesktop-([0-9][0-9.]*)-win-x64\.zip$", RegexOptions.IgnoreCase);
            return m.Success ? m.Groups[1].Value : null;
        }

        public static bool Newer(Release rel, string mine) =>
            rel?.DesktopName != null && Versions.Compare(ZipVersion(rel.DesktopName), mine) > 0;

        int busy;

        /// <summary>one look at the feed: a newer verified build unpacked and waiting. Failures wait for the next look</summary>
        public async Task Check(string mine, CancellationToken ct)
        {
            if (Interlocked.Exchange(ref busy, 1) == 1) return;
            try
            {
                LastCheck = DateTime.UtcNow;
                var rel = await feed.Latest(ct).ConfigureAwait(false);
                if (!Newer(rel, mine)) { if (ready == null) status = ""; return; }
                var ver = ZipVersion(rel.DesktopName);
                if (ready != null && Versions.Compare(readyVersion, ver) >= 0) return;
                Log.I("update: Desktop " + ver + " found, downloading");
                status = "downloading " + ver;
                var zip = await feed.EnsureFile(rel.DesktopName, rel.DesktopUrl, rel.DesktopSize, rel.SumsUrl, dir, "*.zip",
                    (done, total) => { if (total > 0) status = "downloading " + ver + " (" + (100 * done / total) + "%)"; }, ct).ConfigureAwait(false);
                var next = Path.Combine(dir, "next");
                if (Directory.Exists(next)) Directory.Delete(next, true);
                ZipFile.ExtractToDirectory(zip, next);
                // a zip with everything in one top folder (DsForeverDesktop\...) is taken from that folder
                if (!File.Exists(Path.Combine(next, Exe)))
                {
                    var subs = Directory.GetDirectories(next);
                    if (subs.Length == 1 && File.Exists(Path.Combine(subs[0], Exe)) && Directory.GetFiles(next).Length == 0)
                    {
                        var tmp = next + "-x";
                        if (Directory.Exists(tmp)) Directory.Delete(tmp, true);
                        Directory.Move(subs[0], tmp);
                        Directory.Delete(next, true);
                        Directory.Move(tmp, next);
                    }
                }
                if (!File.Exists(Path.Combine(next, Exe))) { Directory.Delete(next, true); throw new InvalidDataException("the zip has no " + Exe); }
                readyVersion = ver;
                ready = next;
                status = ver + " ready";
                Log.I("update: " + ver + " verified and unpacked; it goes in when no stream is on and WoW is closed");
            }
            catch (Exception e) { Log.I("update: " + e.Message); if (ready == null) status = "update check failed"; }
            finally { Interlocked.Exchange(ref busy, 0); }
        }

        /// <summary>copies the new exe out as the helper and starts it; true = the app must now quit</summary>
        public bool Begin(string appDir)
        {
            try
            {
                var helper = Path.Combine(dir, "helper");
                if (Directory.Exists(helper)) try { Directory.Delete(helper, true); } catch (Exception) { }
                Directory.CreateDirectory(helper);
                foreach (var f in Directory.GetFiles(ready))
                {
                    var n = Path.GetFileName(f);
                    if (n.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) || n.EndsWith(".dll", StringComparison.OrdinalIgnoreCase) || n.EndsWith(".config", StringComparison.OrdinalIgnoreCase))
                        File.Copy(f, Path.Combine(helper, n), true);
                }
                var me = Process.GetCurrentProcess();
                var args = UpdateHelper.Quote(UpdateHelper.Args(me.Id, ready, appDir, dataDir, readyVersion));
                Process.Start(new ProcessStartInfo(Path.Combine(helper, Exe), args) { UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = helper })?.Dispose();
                Log.I("update: closing for " + readyVersion + " (helper " + helper + ")");
                return true;
            }
            catch (Exception e)
            {
                Log.I("update: helper didn't start: " + e.Message);
                UpdateHelper.WriteResult(dir, false, readyVersion, "helper didn't start: " + e.Message);
                return false;
            }
        }

        /// <summary>at start: what the last helper run did (null = nothing new to say), and its leftovers cleaned</summary>
        public (bool ok, string version, DateTime at, string why)? LastResult() => UpdateHelper.ReadResult(dir);

        public void CleanUp(string appDir)
        {
            try { var h = Path.Combine(dir, "helper"); if (Directory.Exists(h)) Directory.Delete(h, true); } catch (Exception) { }
            SwapPlan.CleanOld(appDir);
        }
    }
}
