using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace DsfDesktop.Sunshine
{
    /// <summary>where the bundled Sunshine lives and where its files go (all of them in OUR data dir, never Sunshine's defaults)</summary>
    public sealed class SunshineLayout
    {
        /// <summary>the folder holding sunshine.exe (bundled: &lt;app&gt;\sunshine)</summary>
        public string ExeDir;
        /// <summary>DataDir\sunshine: conf, state, credentials, apps, cert, log</summary>
        public string DataDir;

        public string Exe => Path.Combine(ExeDir, "sunshine.exe");
        public string Conf => Path.Combine(DataDir, "sunshine.conf");
        public string State => Path.Combine(DataDir, "sunshine_state.json");
        public string Credentials => Path.Combine(DataDir, "credentials.json");
        public string Apps => Path.Combine(DataDir, "apps.json");
        public string Cert => Path.Combine(DataDir, "cacert.pem");
        public string Pkey => Path.Combine(DataDir, "cakey.pem");
        public string LogFile => Path.Combine(DataDir, "sunshine.log");
    }

    public sealed class SunshineOptions
    {
        public string HostName = Environment.MachineName;
        /// <summary>Sunshine's base port: HTTP = base, HTTPS = base-5, web/API = base+1, RTSP = base+21, video/control/audio UDP = base+9..11</summary>
        public int Port = 47989;
        /// <summary>empty = Sunshine's own probe (NVENC, then AMF, then Quick Sync, then software); or nvenc / amdvce / quicksync</summary>
        public string Encoder = "";
        /// <summary>"" = all interfaces (the Thor reaches it over the LAN); tests use 127.0.0.1</summary>
        public string BindAddress = "";
        /// <summary>the exe Sunshine runs when a stream of the WoW app starts and stops (our own, with --event)</summary>
        public string EventExe;
        public string AppName = "WoW Forever";
    }

    /// <summary>
    /// Writes the headless Sunshine config. The choices, and why:
    ///  - system_tray = disabled, origin_web_ui_allowed = pc: the player never sees Sunshine; its API answers this PC only;
    ///  - encoder blank: Sunshine probes NVENC, AMF, Quick Sync (then software) itself, which is exactly "auto";
    ///  - hevc_mode = 0 / av1_mode = 0: advertise HEVC (and AV1) when the GPU can encode it; Moonlight then asks for HEVC by default.
    ///    Resolution, fps and bitrate (1080p60, ~20 Mbps) are the CLIENT's request in Moonlight's protocol (S2's defaults), not host config;
    ///  - leave no trace on the PC: install_steam_audio_drivers = disabled (no driver install), nvenc_opengl_vulkan_on_dxgi = disabled
    ///    (no NVIDIA driver-profile edits), upnp = disabled (no router changes), no display-device changes (dd_configuration_option off);
    ///  - every file (state, credentials, apps, cert, key, log) in our data dir.
    /// </summary>
    public static class SunshineConfig
    {
        static string P(string path) => path.Replace('\\', '/');

        public static string ConfText(SunshineLayout l, SunshineOptions o)
        {
            var kv = new List<KeyValuePair<string, string>>
            {
                new KeyValuePair<string, string>("sunshine_name", SafeName(o.HostName)),
                new KeyValuePair<string, string>("port", o.Port.ToString()),
                new KeyValuePair<string, string>("address_family", "ipv4"),
                new KeyValuePair<string, string>("origin_web_ui_allowed", "pc"),
                new KeyValuePair<string, string>("upnp", "disabled"),
                new KeyValuePair<string, string>("system_tray", "disabled"),
                new KeyValuePair<string, string>("notify_pre_releases", "disabled"),
                new KeyValuePair<string, string>("install_steam_audio_drivers", "disabled"),
                new KeyValuePair<string, string>("nvenc_opengl_vulkan_on_dxgi", "disabled"),
                new KeyValuePair<string, string>("dd_configuration_option", "disabled"),
                new KeyValuePair<string, string>("hevc_mode", "0"),
                new KeyValuePair<string, string>("av1_mode", "0"),
                new KeyValuePair<string, string>("min_log_level", "2"),
                new KeyValuePair<string, string>("file_state", P(l.State)),
                new KeyValuePair<string, string>("credentials_file", P(l.Credentials)),
                new KeyValuePair<string, string>("file_apps", P(l.Apps)),
                new KeyValuePair<string, string>("cert", P(l.Cert)),
                new KeyValuePair<string, string>("pkey", P(l.Pkey)),
                new KeyValuePair<string, string>("log_path", P(l.LogFile)),
            };
            if (!string.IsNullOrWhiteSpace(o.Encoder)) kv.Add(new KeyValuePair<string, string>("encoder", o.Encoder.Trim()));
            if (!string.IsNullOrWhiteSpace(o.BindAddress)) kv.Add(new KeyValuePair<string, string>("bind_address", o.BindAddress.Trim()));
            var sb = new StringBuilder("# Written by DS Forever Desktop. Edits are replaced at the next start.\n");
            foreach (var p in kv) sb.Append(p.Key).Append(" = ").Append(p.Value).Append('\n');
            return sb.ToString();
        }

        /// <summary>
        /// One app, the desktop itself (no cmd: WoW is already running, Desktop focuses it). Its prep commands tell Desktop a stream
        /// started / stopped, which is when "Focus WoW" runs.
        /// </summary>
        public static string AppsText(SunshineOptions o)
        {
            var app = new Dictionary<string, object>
            {
                ["name"] = o.AppName,
                ["image-path"] = "desktop.png",
                ["auto-detach"] = true,
                ["wait-all"] = true,
            };
            if (!string.IsNullOrEmpty(o.EventExe))
            {
                app["prep-cmd"] = new object[]
                {
                    new Dictionary<string, object>
                    {
                        ["do"] = "\"" + o.EventExe + "\" --event stream-start",
                        ["undo"] = "\"" + o.EventExe + "\" --event stream-stop",
                        ["elevated"] = false,
                    }
                };
            }
            return Json.Write(new Dictionary<string, object> { ["env"] = new Dictionary<string, object>(), ["apps"] = new object[] { app } });
        }

        public static string SafeName(string s)
        {
            var t = new string((s ?? "PC").Where(c => char.IsLetterOrDigit(c) || c == '-' || c == '_' || c == ' ').ToArray()).Trim();
            return t.Length == 0 ? "PC" : (t.Length > 40 ? t.Substring(0, 40) : t);
        }

        public static void Write(SunshineLayout l, SunshineOptions o)
        {
            Directory.CreateDirectory(l.DataDir);
            File.WriteAllText(l.Conf, ConfText(l, o), new UTF8Encoding(false));
            File.WriteAllText(l.Apps, AppsText(o), new UTF8Encoding(false));
        }
    }

    /// <summary>
    /// Picks Sunshine's base port. 47989 is what stock Moonlight assumes, but another Sunshine/Apollo may hold it (this PC runs
    /// Apollo on 47989/48989/49989); the Thor learns the port from the bridge (/v1/info and the mDNS TXT), so any free base works.
    /// </summary>
    public static class PortPlanner
    {
        public static readonly int[] Candidates = { 47989, 52989, 53989, 54989, 55989, 56989 };
        public static readonly int[] TcpOffsets = { -5, 0, 1, 21 };
        public static readonly int[] UdpOffsets = { 9, 10, 11 };

        public static int Pick(int sticky, Func<int, bool> tcpFree, Func<int, bool> udpFree)
        {
            var order = new List<int>();
            if (sticky > 0) order.Add(sticky);
            order.AddRange(Candidates.Where(c => c != sticky));
            foreach (var b in order)
                if (TcpOffsets.All(o => tcpFree(b + o)) && UdpOffsets.All(o => udpFree(b + o))) return b;
            return -1;
        }

        // read from the system's listener table rather than by binding: a test bind on all interfaces is itself what raises the
        // Windows Firewall prompt
        public static bool TcpFree(int port)
        {
            try { return !System.Net.NetworkInformation.IPGlobalProperties.GetIPGlobalProperties().GetActiveTcpListeners().Any(e => e.Port == port); }
            catch (Exception) { return true; }
        }

        public static bool UdpFree(int port)
        {
            try { return !System.Net.NetworkInformation.IPGlobalProperties.GetIPGlobalProperties().GetActiveUdpListeners().Any(e => e.Port == port); }
            catch (Exception) { return true; }
        }
    }
}
