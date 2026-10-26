using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using DsfDesktop.Thor;

namespace DsfDesktop.Tests
{
    /// <summary>a scripted adb: answers by the command's text, records every call</summary>
    sealed class FakeAdb : IAdb
    {
        public string Path => "fake-adb";
        public readonly List<string> Calls = new List<string>();
        public readonly Dictionary<string, Func<AdbResult>> Answers = new Dictionary<string, Func<AdbResult>>();
        public string[] LogcatLines = new string[0];
        public bool LogcatAfterBroadcastOnly = true;

        public static AdbResult Ok(string o) => new AdbResult { Exit = 0, Out = o };

        public AdbResult Run(int timeoutMs, params string[] args)
        {
            var cmd = string.Join(" ", args);
            Calls.Add(cmd);
            foreach (var kv in Answers.OrderByDescending(k => k.Key.Length)) if (cmd.Contains(kv.Key)) return kv.Value();
            return Ok("");
        }

        public bool Watch(int timeoutMs, Func<string, bool> stopWhen, Action onStarted, params string[] args)
        {
            Calls.Add("WATCH " + string.Join(" ", args));
            onStarted?.Invoke();
            return LogcatLines.Any(stopWhen);
        }
    }

    public static class ThorTests
    {
        const string Listing =
            "List of devices attached\n" +
            "8e2a3f1c               device usb:1-4 product:thor model:AYN_Thor device:thor transport_id:3\n" +
            "192.168.1.50:5555      device product:thor model:AYN_Thor device:thor transport_id:1\n" +
            "adb-8e2a3f1c-Ab12Cd._adb-tls-connect._tcp device product:thor model:AYN_Thor device:thor transport_id:2\n";

        [Test]
        public static void ParsesDevicesAndKeepsOnlyTheCable()
        {
            var d = AdbParse.Devices(Listing);
            A.Eq(3, d.Count);
            A.Eq("8e2a3f1c", d[0].Serial);
            A.Eq("device", d[0].State);
            A.Eq("AYN Thor", d[0].ListedModel);
            A.Eq("1-4", d[0].Prop("usb"));
            A.True(d[0].Usb);
            A.False(d[1].Usb, "adb over Wi-Fi (host:port) is not the cable");
            A.False(d[2].Usb, "mDNS wireless debugging is not the cable");
        }

        [Test]
        public static void ParsesTheProblemStates()
        {
            var d = AdbParse.Devices(
                "* daemon not running; starting now at tcp:5037\n* daemon started successfully\nList of devices attached\n" +
                "R58N12345   unauthorized usb:1-2 transport_id:5\n" +
                "ABC         offline usb:1-3 transport_id:6\n" +
                "XYZ         no permissions (user in plugdev group; are your udev rules wrong?); see [http://developer.android.com/tools/device.html] usb:1-1 transport_id:7\n");
            A.Eq(3, d.Count);
            A.Eq("unauthorized", d[0].State);
            A.Eq("offline", d[1].State);
            A.Eq("no permissions", d[2].State);
            A.Eq("1-1", d[2].Prop("usb"));
            A.Eq(0, AdbParse.Devices("List of devices attached\n\n").Count);
        }

        [Test]
        public static void VersionsCompare()
        {
            A.True(Versions.Compare("1.0.10", "1.0.9") > 0, "10 > 9 numerically");
            A.True(Versions.Compare("v1.0.11", "1.0.11") == 0);
            A.True(Versions.Compare("1.0", "1.0.0") == 0);
            A.True(Versions.Compare("1.0.11", "1.1") < 0);
            A.True(Versions.Compare("1.0.12-rc1", "1.0.11") > 0);
            A.Eq("1.0.11", Versions.Clean("v1.0.11"));
        }

        [Test]
        public static void DumpsysAndPsAndInstallOutput()
        {
            A.Eq("1.0.11", AdbParse.VersionName("Packages:\n  Package [app.dsforever.companion] (a1b2):\n    versionCode=111 minSdk=26 targetSdk=34\n    versionName=1.0.11\n"));
            A.Null(AdbParse.VersionName("Unable to find package: app.dsforever.companion\n"));
            A.True(AdbParse.WineRunning("NAME\ninit\nsystem_server\nwineserver\napp.dsforever.companion\n"));
            A.True(AdbParse.WineRunning("USER PID NAME\nu0_a1 123 /data/local/wine64-preloader\n"));
            A.False(AdbParse.WineRunning("NAME\ninit\napp.dsforever.companion\nwinelike.app\n"));
            A.Null(AdbParse.InstallFailure("Performing Streamed Install\nSuccess\n"));
            A.Eq("INSTALL_FAILED_UPDATE_INCOMPATIBLE", AdbParse.InstallFailure("adb: failed to install x.apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package app.dsforever.companion signatures do not match]"));
            A.True(ThorGuide.ExplainFailure("INSTALL_FAILED_UPDATE_INCOMPATIBLE").Contains("never uninstalls"));
        }

        [Test]
        public static void CardTextForEveryState()
        {
            A.Eq(ThorPhase.NoAdb, ThorGuide.Describe(false, null, null, null, null).Phase);
            var none = ThorGuide.Describe(true, AdbParse.Devices("List of devices attached\n" + "192.168.1.50:5555 device model:AYN_Thor\n"), null, null, "1.0.11");
            A.Eq(ThorPhase.NoDevice, none.Phase, "a Wi-Fi Thor isn't a cable Thor");
            A.True(none.Detail.Contains("USB debugging"));
            var un = ThorGuide.Describe(true, AdbParse.Devices("R5 unauthorized usb:1-2 model:AYN_Thor\n"), null, null, null);
            A.Eq(ThorPhase.Unauthorized, un.Phase);
            A.True(un.Detail.Contains("Allow USB debugging"));
            var fresh = ThorGuide.Describe(true, AdbParse.Devices(Listing), "AYN Thor", null, "1.0.11");
            A.Eq("AYN Thor connected", fresh.Title);
            A.Eq("Install DS Forever", fresh.Button);
            A.True(fresh.CanInstall);
            var old = ThorGuide.Describe(true, AdbParse.Devices(Listing), "AYN Thor", "1.0.9", "v1.0.11");
            A.Eq("Update to 1.0.11", old.Button);
            A.True(old.Detail.Contains("1.0.9 installed"));
            var current = ThorGuide.Describe(true, AdbParse.Devices(Listing), "AYN Thor", "1.0.11", "1.0.11");
            A.Null(current.Button);
            A.True(current.Detail.Contains("up to date"));
            A.False(current.CanInstall);
        }

        [Test]
        public static void SafeCloseDecision()
        {
            A.Eq(ThorGuide.SafeClose.Broadcast, ThorGuide.Decide(true, true));
            A.Eq(ThorGuide.SafeClose.NotNeeded, ThorGuide.Decide(true, false), "no Wine: nothing to close (a cached app can't answer)");
            A.Eq(ThorGuide.SafeClose.NotNeeded, ThorGuide.Decide(false, false));
        }

        static FakeAdb Thor(string installed, bool wine)
        {
            var a = new FakeAdb();
            a.Answers["devices -l"] = () => FakeAdb.Ok(Listing);
            a.Answers["getprop ro.product.model"] = () => FakeAdb.Ok("AYN Thor\n");
            a.Answers["dumpsys package"] = () => FakeAdb.Ok(installed == null ? "Unable to find package: app.dsforever.companion" : "versionName=" + installed + "\n");
            a.Answers["ps -A -o NAME"] = () => FakeAdb.Ok(wine ? "NAME\nwineserver\n" : "NAME\ninit\n");
            a.Answers[" install -r "] = () => FakeAdb.Ok("Performing Streamed Install\nSuccess\n");
            return a;
        }

        [Test]
        public static void InstallWithTheGameRunningClosesItFirst()
        {
            var a = Thor("1.0.9", wine: true);
            a.LogcatLines = new[] { "I/WCSETUP( 123): safe close: done (the game exited)" };
            var m = new ThorManager(a, new ReleaseFeed(), "unused");
            A.Null(m.InstallFile(@"C:\x\DS-Forever-1.0.11.apk", "1.0.11"));
            int bc = a.Calls.FindIndex(c => c.Contains("am broadcast -a app.dsforever.SAFE_CLOSE -p app.dsforever.companion"));
            int watch = a.Calls.FindIndex(c => c.StartsWith("WATCH") && c.Contains("logcat"));
            int inst = a.Calls.FindIndex(c => c.Contains("install -r"));
            A.True(watch >= 0 && bc > watch && inst > bc, "logcat watched, then the broadcast, then the install: " + string.Join(" | ", a.Calls));
            A.True(a.Calls[inst].StartsWith("-s 8e2a3f1c install -r"), "the cable device, by serial");
            A.False(a.Calls.Any(c => c.Contains("uninstall") || c.Contains("pm clear")), "never uninstall, never clear data");
        }

        [Test]
        public static void NoWineMeansNoBroadcast()
        {
            var a = Thor("1.0.9", wine: false);
            var m = new ThorManager(a, new ReleaseFeed(), "unused");
            A.Null(m.InstallFile("x.apk", "1.0.11"));
            A.False(a.Calls.Any(c => c.Contains("SAFE_CLOSE")));
            A.True(a.Calls.Any(c => c.Contains("install -r x.apk")));
        }

        [Test]
        public static void UnconfirmedSafeCloseStopsTheInstall()
        {
            var a = Thor("1.0.9", wine: true);
            a.LogcatLines = new[] { "I/WCSETUP: something else" };
            var m = new ThorManager(a, new ReleaseFeed(), "unused");
            var err = m.InstallFile("x.apk", "1.0.11");
            A.NotNull(err);
            A.True(err.Contains("still running"));
            A.False(a.Calls.Any(c => c.Contains("install -r")), "nothing installed over a running game");
        }

        [Test]
        public static void InstallFailureIsExplained()
        {
            var a = Thor("1.0.9", wine: false);
            a.Answers[" install -r "] = () => FakeAdb.Ok("adb: failed to install: Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]");
            var err = new ThorManager(a, new ReleaseFeed(), "unused").InstallFile("x.apk", "1.0.11");
            A.True(err.Contains("out of space"), err);
        }

        [Test]
        public static void RefreshDescribesTheThor()
        {
            var a = Thor("1.0.9", wine: false);
            var m = new ThorManager(a, new ReleaseFeed(), "unused");
            m.Refresh();
            A.Eq("AYN Thor connected", m.Status.Title);
            A.Eq("1.0.9", m.Status.Installed);
        }

        // ---- the release feed: the real API's shape (v1.0.11, 2026-10-26)
        const string LatestJson = "{\"tag_name\":\"v1.0.11\",\"assets\":[" +
            "{\"name\":\"DS-Forever-1.0.11-source.zip\",\"size\":54975263,\"browser_download_url\":\"https://x/src.zip\"}," +
            "{\"name\":\"DS-Forever-1.0.11.apk\",\"size\":198346746,\"browser_download_url\":\"https://x/DS-Forever-1.0.11.apk\"}," +
            "{\"name\":\"SHA256SUMS\",\"size\":184,\"browser_download_url\":\"https://x/SHA256SUMS\"}]}";

        [Test]
        public static void ReleaseJsonPicksTheApkAndSums()
        {
            var r = ReleaseFeed.ParseLatest(LatestJson);
            A.Eq("1.0.11", r.Version);
            A.Eq("DS-Forever-1.0.11.apk", r.ApkName);
            A.Eq("https://x/SHA256SUMS", r.SumsUrl);
            A.Eq(198346746L, r.ApkSize);
            A.Null(ReleaseFeed.ParseLatest("{\"message\":\"Not Found\"}"));
        }

        [Test]
        public static void ShaCheckAgainstSums()
        {
            using (var t = new Tmp())
            {
                var f = t.P("DS-Forever-1.0.11.apk");
                File.WriteAllText(f, "pretend apk");
                var sha = ReleaseFeed.Sha256File(f);
                var sums = "cbc6a90f0d6240ccd75fe3177979a5cad71fa355119f712c419d51f55cf75315  DS-Forever-1.0.11-source.zip\n" + sha + "  DS-Forever-1.0.11.apk\n";
                A.Null(ReleaseFeed.Verify(f, sums, "DS-Forever-1.0.11.apk"));
                A.Null(ReleaseFeed.Verify(f, sha + " *DS-Forever-1.0.11.apk\n", "DS-Forever-1.0.11.apk"), "binary-mode lines too");
                File.AppendAllText(f, "tampered");
                A.True(ReleaseFeed.Verify(f, sums, "DS-Forever-1.0.11.apk").Contains("mismatch"));
                A.True(ReleaseFeed.Verify(f, "", "DS-Forever-1.0.11.apk").Contains("doesn't list"));
            }
        }
    }
}
