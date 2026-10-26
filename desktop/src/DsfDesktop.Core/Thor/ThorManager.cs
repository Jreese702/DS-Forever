using System;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace DsfDesktop.Thor
{
    /// <summary>
    /// The Thor card: watches for a device on the USB cable (adb devices -l), tells what is installed, and installs or updates DS
    /// Forever with one button. The order of an install:
    ///  1. the APK: the latest public release, checked against its SHA256SUMS (or a file the tester picked);
    ///  2. a running game is closed by the app itself: am broadcast SAFE_CLOSE, then "safe close: done" in logcat; with no Wine
    ///     process there is nothing to close (the cached app couldn't answer anyway);
    ///  3. adb install -r (keeps the app's data). Never uninstall, never clear data.
    /// </summary>
    public sealed class ThorManager
    {
        readonly IAdb adb;
        readonly ReleaseFeed feed;
        readonly string apkCache;
        readonly object gate = new object();
        Release latest;
        DateTime latestAt = DateTime.MinValue;
        string lastSerial, model, installed;
        DateTime inspectedAt = DateTime.MinValue;
        volatile bool busy;

        public ThorStatus Status { get; private set; }
        public string Progress { get; private set; } = "";
        public bool Busy => busy;
        public Release Latest => latest;
        public event Action Changed;

        public TimeSpan SafeCloseWait = TimeSpan.FromSeconds(45);
        public int InstallTimeoutMs = 10 * 60 * 1000;

        public ThorManager(IAdb adb, ReleaseFeed feed, string apkCache)
        {
            this.adb = adb; this.feed = feed; this.apkCache = apkCache;
            Status = ThorGuide.Describe(adb != null, null, null, null, null);
        }

        void Fire() { try { Changed?.Invoke(); } catch (Exception) { } }
        void Say(string p) { Progress = p; Log.I("thor: " + p); Fire(); }

        string Shell(string serial, int ms, params string[] cmd)
            => adb.Run(ms, new[] { "-s", serial, "shell" }.Concat(cmd).ToArray()).Out;

        /// <summary>a few seconds' poll: the device list; the model and installed version when a device appears (and every 30 s)</summary>
        public void Refresh()
        {
            if (adb == null || busy) return;
            try
            {
                var devices = AdbParse.Devices(adb.Run(10000, "devices", "-l").Out);
                var ready = devices.FirstOrDefault(d => d.Usb && d.State == "device");
                if (ready != null && (ready.Serial != lastSerial || DateTime.UtcNow - inspectedAt > TimeSpan.FromSeconds(30)))
                {
                    model = Shell(ready.Serial, 10000, "getprop", "ro.product.model").Trim();
                    installed = AdbParse.VersionName(Shell(ready.Serial, 15000, "dumpsys", "package", ThorGuide.Package));
                    inspectedAt = DateTime.UtcNow;
                }
                lastSerial = ready?.Serial;
                if (ready == null) { model = null; installed = null; }
                lock (gate) Status = ThorGuide.Describe(true, devices, model, installed, latest?.Version);
            }
            catch (Exception e) { Log.I("thor refresh: " + e.Message); }
            Fire();
        }

        /// <summary>the newest public release (asked at most every 30 minutes)</summary>
        public async Task CheckRelease(bool force = false)
        {
            if (!force && DateTime.UtcNow - latestAt < TimeSpan.FromMinutes(30)) return;
            latestAt = DateTime.UtcNow;
            try { latest = await feed.Latest(CancellationToken.None).ConfigureAwait(false) ?? latest; }
            catch (Exception e) { Log.I("release check: " + e.Message); }
            Refresh();
        }

        /// <summary>the one button: the latest release, verified, then installed. null = done, else what went wrong.</summary>
        public async Task<string> InstallLatest()
        {
            if (latest == null) await CheckRelease(true).ConfigureAwait(false);
            var rel = latest;
            if (rel == null) return "Couldn't reach GitHub for DS Forever's latest release. Check the PC's internet connection.";
            string apk;
            try
            {
                busy = true;
                Say("Downloading DS Forever " + rel.Version + "...");
                apk = await feed.EnsureApk(rel, apkCache, (done, total) =>
                    Say("Downloading DS Forever " + rel.Version + ": " + (total > 0 ? (100 * done / total) + "%" : (done >> 20) + " MB")), CancellationToken.None).ConfigureAwait(false);
            }
            catch (Exception e) { busy = false; Say(""); return "The download didn't work: " + e.Message; }
            finally { busy = false; }
            return await Task.Run(() => InstallFile(apk, rel.Version)).ConfigureAwait(false);
        }

        /// <summary>a tester's own APK file (no checksum to check against: the file is the tester's choice)</summary>
        public Task<string> InstallLocal(string apk) => Task.Run(() => InstallFile(apk, null));

        public string InstallFile(string apk, string version)
        {
            if (adb == null) return "adb is missing.";
            busy = true;
            try
            {
                var devices = AdbParse.Devices(adb.Run(10000, "devices", "-l").Out);
                var d = devices.FirstOrDefault(x => x.Usb && x.State == "device");
                if (d == null) return "The Thor isn't connected (or USB debugging isn't allowed yet).";
                var serial = d.Serial;
                var have = AdbParse.VersionName(Shell(serial, 15000, "dumpsys", "package", ThorGuide.Package));
                bool wine = AdbParse.WineRunning(Shell(serial, 15000, "ps", "-A", "-o", "NAME"));
                if (ThorGuide.Decide(have != null, wine) == ThorGuide.SafeClose.Broadcast)
                {
                    Say("Closing the game on the Thor safely...");
                    bool done = adb.Watch((int)SafeCloseWait.TotalMilliseconds, line => line.Contains("safe close: done"),
                        () => adb.Run(15000, "-s", serial, "shell", "am", "broadcast", "-a", "app.dsforever.SAFE_CLOSE", "-p", ThorGuide.Package),
                        "-s", serial, "logcat", "-T", "1", "WCSETUP:I", "*:S");
                    if (!done && AdbParse.WineRunning(Shell(serial, 15000, "ps", "-A", "-o", "NAME")))
                        return "The game is still running on the Thor and DS Forever didn't confirm it closed it. Close the game on the Thor " +
                               "(or open DS Forever once), then press the button again.";
                }
                Say("Installing" + (version != null ? " DS Forever " + Versions.Clean(version) : "") + " on the " + (Status.Model ?? "device") + "... (keep the cable in)");
                var r = adb.Run(InstallTimeoutMs, "-s", serial, "install", "-r", apk);
                if (r.TimedOut) return "The install took too long. Check the cable, then try again.";
                var fail = AdbParse.InstallFailure(r.All);
                if (fail != null) return ThorGuide.ExplainFailure(fail);
                inspectedAt = DateTime.MinValue;
                Say("DS Forever " + (version != null ? Versions.Clean(version) + " " : "") + "is installed. Open it on the Thor.");
                return null;
            }
            catch (Exception e) { return "The install stopped: " + e.Message; }
            finally
            {
                busy = false;
                Refresh();
            }
        }
    }
}
