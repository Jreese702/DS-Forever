using System;
using System.IO;
using System.Reflection;

namespace DsfDesktop.App
{
    public sealed class AppSettings
    {
        /// <summary>the WoW install root the user picked (null = found automatically)</summary>
        public string WowRoot { get; set; }
        /// <summary>Sunshine's base port last used (sticky, so the Thor's remembered address keeps working)</summary>
        public int SunshinePort { get; set; }
        public int BridgePort { get; set; } = Bridge.BridgeServer.DefaultPort;
        /// <summary>"" = Sunshine's own probe; nvenc / amdvce / quicksync to force one</summary>
        public string Encoder { get; set; } = "";
        public string SunshineUser { get; set; }
        /// <summary>DPAPI-protected (this Windows user only)</summary>
        public string SunshinePass { get; set; }
        public string HostId { get; set; }
        public bool AutoAddons { get; set; } = true;
        public bool FocusOnStream { get; set; } = true;
        /// <summary>"Start WoW when streaming": a stream (or the Thor's focus) starts WoW Forever when it isn't running</summary>
        public bool StartWowOnStream { get; set; } = true;
        /// <summary>"Match the PC's screen to the stream": the streamed screen takes the Thor's resolution while it is connected, then goes back
        /// (the user 2026-11-01: "Everything just looks so tiny on my thor")</summary>
        public bool MatchStreamResolution { get; set; } = true;
        /// <summary>the display device id Sunshine captures (the monitor WoW was last seen on); null = the primary display</summary>
        public string CaptureDevice { get; set; }
        /// <summary>our own virtual screen's id in SudoVDA (made once; displays are keyed by it, so ours never touches Apollo's)</summary>
        public string VirtualScreenId { get; set; }
        /// <summary>the virtual-screen install question was asked (once)</summary>
        public bool VirtualScreenOffered { get; set; }
        /// <summary>"Stream on its own screen" (Part 2, the user's pick 2026-11-17): a Thor's stream gets our own virtual screen at its
        /// mode on every PC, WoW moved onto it; off = only a PC with no screen on gets one (0.2.31)</summary>
        public bool OwnScreen { get; set; } = true;
        /// <summary>we attached the Virtual Display Driver's monitor and haven't detached it yet (detached when Desktop next can)</summary>
        public bool VddAttached { get; set; }
        /// <summary>the player said yes to our Virtual Display Driver install: its monitor is ours</summary>
        public bool VddInstalledByUs { get; set; }
        /// <summary>the Virtual Display Driver monitor we attached (its display name): the only VDD monitor we adopt or detach</summary>
        public string VddDevice { get; set; }
        /// <summary>the Sunshine device id our own screen had last time, and the driver that made it: written as output_name before
        /// the restart, so one restart does instead of two</summary>
        public string OwnScreenDriver { get; set; }
        public string OwnScreenDevice { get; set; }
        /// <summary>the monitor WoW was on before it moved to our screen (kept across a crash: WoW goes back there at the next start)</summary>
        public string WowHome { get; set; }
        /// <summary>the folder question is asked once; after that the user uses "Choose folder" themselves</summary>
        public bool AskedForFolder { get; set; }

        public static AppSettings Load(string file)
        {
            try
            {
                if (File.Exists(file))
                    return new System.Web.Script.Serialization.JavaScriptSerializer().Deserialize<AppSettings>(File.ReadAllText(file)) ?? new AppSettings();
            }
            catch (Exception e) { Log.I("settings unreadable, defaults used: " + e.Message); }
            return new AppSettings();
        }

        public void Save(string file)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(file));
            var tmp = file + ".tmp";
            File.WriteAllText(tmp, Json.Write(this));
            if (File.Exists(file)) File.Replace(tmp, file, null);
            else File.Move(tmp, file);
        }
    }

    /// <summary>where things are. DSF_DESKTOP_DATA / DSF_SUNSHINE_DIR / DSF_ADDON_SOURCE override (dev and tests).</summary>
    public sealed class AppPaths
    {
        public string AppDir, DataDir, SunshineExeDir, AddonSource;

        public string Settings => Path.Combine(DataDir, "settings.json");
        public string Devices => Path.Combine(DataDir, "devices.json");
        public string LogFile => Path.Combine(DataDir, "desktop.log");
        public string SunshineData => Path.Combine(DataDir, "sunshine");
        public string Backups => Path.Combine(DataDir, "backups");
        public string Exe;

        public static AppPaths Default()
        {
            var exe = Assembly.GetEntryAssembly()?.Location ?? Assembly.GetExecutingAssembly().Location;
            var app = Path.GetDirectoryName(exe);
            var p = new AppPaths
            {
                Exe = exe,
                AppDir = app,
                DataDir = Env("DSF_DESKTOP_DATA") ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "DSForeverDesktop"),
                SunshineExeDir = Env("DSF_SUNSHINE_DIR") ?? Path.Combine(app, "sunshine"),
                AddonSource = Env("DSF_ADDON_SOURCE") ?? FindAddonSource(app),
            };
            return p;
        }

        static string Env(string k) { var v = Environment.GetEnvironmentVariable(k); return string.IsNullOrWhiteSpace(v) ? null : v; }

        /// <summary>the release layout (&lt;app&gt;\companion), else the repo's own add-on set when run from a source tree</summary>
        public static string FindAddonSource(string appDir)
        {
            var bundled = Path.Combine(appDir, "companion");
            if (File.Exists(Path.Combine(bundled, "manifest.txt"))) return bundled;
            var d = new DirectoryInfo(appDir);
            for (int i = 0; d != null && i < 8; i++, d = d.Parent)
            {
                var c = Path.Combine(d.FullName, "companion", "app", "src", "main", "assets", "companion");
                if (File.Exists(Path.Combine(c, "manifest.txt"))) return c;
            }
            return bundled;
        }
    }
}
