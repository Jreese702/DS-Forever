using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text.RegularExpressions;

namespace DsfDesktop.Thor
{
    public sealed class AdbDevice
    {
        public string Serial;
        /// <summary>device / unauthorized / offline / no permissions / recovery / sideload / bootloader ...</summary>
        public string State;
        public readonly Dictionary<string, string> Props = new Dictionary<string, string>();
        public string Prop(string k) => Props.TryGetValue(k, out var v) ? v : null;

        /// <summary>
        /// Connected by cable: "usb:" in the -l listing, or a plain serial. Network devices (host:port, mDNS "._adb-tls-connect"
        /// names, emulators) are not this feature's: a Thor on adb-over-Wi-Fi is left alone.
        /// </summary>
        public bool Usb => Props.ContainsKey("usb") ||
                           (!Serial.Contains(":") && !Serial.StartsWith("emulator-") && !Serial.Contains("._adb-tls") && !Serial.Contains("._tcp"));

        /// <summary>the -l model with its underscores as spaces ("AYN_Thor" -> "AYN Thor")</summary>
        public string ListedModel => Prop("model")?.Replace('_', ' ');
    }

    public static class AdbParse
    {
        /// <summary>adb devices -l</summary>
        public static List<AdbDevice> Devices(string text)
        {
            var res = new List<AdbDevice>();
            foreach (var raw in (text ?? "").Replace("\r", "").Split('\n'))
            {
                var line = raw.Trim();
                if (line.Length == 0 || line.StartsWith("List of devices") || line.StartsWith("*") || line.StartsWith("adb ")) continue;
                var parts = Regex.Split(line, @"\s+");
                if (parts.Length < 2) continue;
                var d = new AdbDevice { Serial = parts[0] };
                int i = 1;
                if (parts[1] == "no" && parts.Length > 2 && parts[2] == "permissions")
                {
                    d.State = "no permissions";
                    i = 3;
                    while (i < parts.Length && !parts[i].Contains(":") ) i++;   // "(user ... ); see [http://...]"
                    while (i < parts.Length && parts[i].StartsWith("[")) i++;
                }
                else { d.State = parts[1]; i = 2; }
                for (; i < parts.Length; i++)
                {
                    int c = parts[i].IndexOf(':');
                    if (c > 0 && !parts[i].StartsWith("http")) d.Props[parts[i].Substring(0, c)] = parts[i].Substring(c + 1);
                }
                res.Add(d);
            }
            return res;
        }

        /// <summary>dumpsys package &lt;pkg&gt; -> versionName, or null when the package isn't installed</summary>
        public static string VersionName(string dumpsys)
        {
            var m = Regex.Match(dumpsys ?? "", @"versionName=([^\s]+)");
            return m.Success ? m.Groups[1].Value.Trim() : null;
        }

        /// <summary>
        /// ps -A -o NAME (or plain ps -A): any Wine process (wineserver, wine, wine64, wine*-preloader) means the game's container
        /// is up, and the app must close it safely before it is replaced
        /// </summary>
        public static bool WineRunning(string ps)
        {
            foreach (var raw in (ps ?? "").Replace("\r", "").Split('\n'))
            {
                var parts = Regex.Split(raw.Trim(), @"\s+");
                var name = parts.Length == 0 ? "" : parts[parts.Length - 1];
                name = name.Substring(name.LastIndexOf('/') + 1);
                if (Regex.IsMatch(name, @"^wine(server|64|-preloader|64-preloader)?$", RegexOptions.IgnoreCase)) return true;
            }
            return false;
        }

        /// <summary>"Failure [INSTALL_FAILED_X: ...]" -> INSTALL_FAILED_X; "Success" -> null</summary>
        public static string InstallFailure(string output)
        {
            if (output == null) return "NO_OUTPUT";
            if (Regex.IsMatch(output, @"^\s*Success\s*$", RegexOptions.Multiline)) return null;
            var m = Regex.Match(output, @"(INSTALL_[A-Z_]+|DELETE_[A-Z_]+)");
            return m.Success ? m.Groups[1].Value : "UNKNOWN";
        }
    }

    public static class Versions
    {
        /// <summary>"v1.0.11" / "1.0.11" / "1.0.11-rc2" -> {1,0,11}</summary>
        public static int[] Parse(string v)
        {
            if (string.IsNullOrWhiteSpace(v)) return new int[0];
            var core = v.Trim().TrimStart('v', 'V');
            var m = Regex.Match(core, @"^\d+(\.\d+)*");
            return m.Success ? m.Value.Split('.').Select(x => int.Parse(x, CultureInfo.InvariantCulture)).ToArray() : new int[0];
        }

        /// <summary>&lt;0: a older than b; 0 same; &gt;0 newer. Missing parts count as 0 (1.0 == 1.0.0)</summary>
        public static int Compare(string a, string b)
        {
            var x = Parse(a); var y = Parse(b);
            for (int i = 0; i < Math.Max(x.Length, y.Length); i++)
            {
                int p = i < x.Length ? x[i] : 0, q = i < y.Length ? y[i] : 0;
                if (p != q) return p.CompareTo(q);
            }
            return 0;
        }

        public static string Clean(string v) => (v ?? "").Trim().TrimStart('v', 'V');
    }

    public enum ThorPhase { NoAdb, NoDevice, Unauthorized, Offline, NoPermissions, OtherState, Ready }

    /// <summary>what the Thor card shows and whether its one button may act</summary>
    public sealed class ThorStatus
    {
        public ThorPhase Phase;
        public string Serial, Model;
        public string Installed;      // versionName on the device, null = not installed
        public string Latest;         // the newest release's version (or the picked APK's), null = unknown
        public string Title, Detail;  // "AYN Thor connected", "DS Forever 1.0.11 installed"
        public string Button;         // "Install DS Forever" / "Update to 1.0.12" / null when nothing to do
        public bool CanInstall => Phase == ThorPhase.Ready && Button != null;
    }

    public static class ThorGuide
    {
        public const string Package = "app.dsforever.companion";

        /// <summary>the card's text for the cable-connected device (the first one in device state wins; else the most useful problem)</summary>
        public static ThorStatus Describe(bool haveAdb, List<AdbDevice> devices, string model, string installed, string latest)
        {
            var s = new ThorStatus { Latest = latest };
            if (!haveAdb)
            {
                s.Phase = ThorPhase.NoAdb;
                s.Title = "USB install unavailable";
                s.Detail = "This copy of DS Forever Desktop has no adb (platform-tools). Reinstall Desktop.";
                return s;
            }
            var usb = (devices ?? new List<AdbDevice>()).Where(d => d.Usb).ToList();
            var ready = usb.FirstOrDefault(d => d.State == "device");
            if (ready == null)
            {
                var d = usb.FirstOrDefault();
                s.Serial = d?.Serial;
                s.Model = d?.ListedModel;
                switch (d?.State)
                {
                    case null:
                        s.Phase = ThorPhase.NoDevice;
                        s.Title = "Connect your Thor with a USB cable";
                        s.Detail = "Not showing up? On the Thor: Settings > About > tap Build number 7 times, then Settings > System > " +
                                   "Developer options > turn on USB debugging.";
                        break;
                    case "unauthorized":
                        s.Phase = ThorPhase.Unauthorized;
                        s.Title = (s.Model ?? "Your device") + " is waiting for you";
                        s.Detail = "On the Thor, tap Allow on \"Allow USB debugging?\" (tick \"Always allow from this computer\"). " +
                                   "No prompt? Unplug the cable and plug it in again.";
                        break;
                    case "offline":
                        s.Phase = ThorPhase.Offline;
                        s.Title = (s.Model ?? "Your device") + " isn't answering";
                        s.Detail = "Unplug the cable and plug it in again; if that doesn't help, turn USB debugging off and on.";
                        break;
                    case "no permissions":
                        s.Phase = ThorPhase.NoPermissions;
                        s.Title = "Windows can't talk to the device";
                        s.Detail = "Try another USB port or cable (a data cable, not a charge-only one).";
                        break;
                    default:
                        s.Phase = ThorPhase.OtherState;
                        s.Title = (s.Model ?? "A device") + " is in " + d.State + " mode";
                        s.Detail = "Start Android normally, then connect again.";
                        break;
                }
                return s;
            }

            s.Phase = ThorPhase.Ready;
            s.Serial = ready.Serial;
            s.Model = !string.IsNullOrWhiteSpace(model) ? model.Trim() : ready.ListedModel ?? "Android device";
            s.Installed = string.IsNullOrWhiteSpace(installed) ? null : installed.Trim();
            s.Title = s.Model + " connected";
            if (s.Installed == null)
            {
                s.Detail = "DS Forever isn't installed" + (latest != null ? "; " + Versions.Clean(latest) + " is ready." : ".");
                s.Button = "Install DS Forever";
            }
            else if (latest != null && Versions.Compare(latest, s.Installed) > 0)
            {
                s.Detail = "DS Forever " + Versions.Clean(s.Installed) + " installed; " + Versions.Clean(latest) + " is available.";
                s.Button = "Update to " + Versions.Clean(latest);
            }
            else
            {
                s.Detail = "DS Forever " + Versions.Clean(s.Installed) + " installed" + (latest != null ? ", up to date." : ".");
                s.Button = null;
            }
            return s;
        }

        /// <summary>what blocks an install, in the player's words</summary>
        public static string ExplainFailure(string code)
        {
            switch (code)
            {
                case null: return null;
                case "INSTALL_FAILED_UPDATE_INCOMPATIBLE":
                    return "The DS Forever on the Thor was signed by a different build, so Android won't update it in place. Nothing was changed. " +
                           "Desktop never uninstalls (that would delete your settings): ask in DS Forever's support how to move over.";
                case "INSTALL_FAILED_VERSION_DOWNGRADE":
                    return "The Thor has a newer DS Forever than this file. Nothing was changed.";
                case "INSTALL_FAILED_INSUFFICIENT_STORAGE":
                    return "The Thor is out of space for the update. Free some storage, then try again.";
                case "INSTALL_FAILED_USER_RESTRICTED":
                    return "The Thor refused installs over USB. In Developer options turn on \"Install via USB\" (if it has one), then try again.";
                case "INSTALL_FAILED_INVALID_APK":
                case "INSTALL_PARSE_FAILED_NOT_APK":
                case "INSTALL_PARSE_FAILED_NO_CERTIFICATES":
                    return "The APK is damaged. Desktop will download it again next time.";
                default:
                    return "The Thor said: " + code + ". Nothing was uninstalled; try again, or reconnect the cable.";
            }
        }

        public enum SafeClose { NotNeeded, Broadcast }

        /// <summary>
        /// Before replacing a running DS Forever: only a running game (Wine up) needs the app's safe close. With no Wine process
        /// there is nothing to close, and a cached, idle app can't answer the broadcast anyway.
        /// </summary>
        public static SafeClose Decide(bool installed, bool wineRunning) => installed && wineRunning ? SafeClose.Broadcast : SafeClose.NotNeeded;
    }
}
