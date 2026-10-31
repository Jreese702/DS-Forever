using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using DsfDesktop.Bridge;
using DsfDesktop.GameLog;
using DsfDesktop.Pairing;
using DsfDesktop.Sunshine;
using DsfDesktop.Wow;

namespace DsfDesktop.App
{
    public enum StreamState { Unknown, Idle, Streaming }

    /// <summary>
    /// Everything Desktop does, without UI: the WoW folder and the log it follows, the bridge and its mDNS name, the bundled Sunshine
    /// (config, login, process, pairing), the add-ons, and "Focus WoW" when a stream starts. The window and tray only read this.
    /// </summary>
    public sealed class AppController : IBridgeHost, IDisposable
    {
        public readonly AppPaths Paths;
        public readonly AppSettings Settings;
        public PairingCodes Codes { get; } = new PairingCodes();
        public DeviceStore Devices { get; }
        public BridgeServer Bridge { get; private set; }
        public SunshineHost Sunshine { get; private set; }
        public SunshineLayout SunshineLayout { get; }
        public int SunshinePort { get; private set; } = -1;

        public WowInstall Wow { get; private set; }
        public string Flavor { get; private set; }
        public string LogPath => tailer?.Path;
        public string LogStatus { get; private set; } = "starting";
        public long DocsSeen;
        public DateTime? LastDocAt { get; private set; }
        public int Gaps => tailer?.Gaps ?? 0;
        public StreamState Stream { get; private set; } = StreamState.Unknown;
        public string AddonLine { get; private set; } = "";
        public AddonStatus Addons { get; private set; }
        public List<string> Notices { get; private set; } = new List<string>();
        public string BridgeStatus { get; private set; } = "starting";

        public event Action Changed;

        /// <summary>DSF_LOOPBACK=1: everything on 127.0.0.1 and no mDNS (a dev run that opens nothing on the LAN, so no firewall prompt)</summary>
        public static bool Loopback => Environment.GetEnvironmentVariable("DSF_LOOPBACK") == "1";

        LogTailer tailer;
        MdnsResponder mdns;
        SunshineApi api;
        Thread pollThread;
        Timer slowTimer;
        volatile bool running;
        DateTime followAt = DateTime.MinValue;
        readonly object gate = new object();

        public AppController(AppPaths paths)
        {
            Paths = paths;
            Directory.CreateDirectory(paths.DataDir);
            Log.Init(paths.LogFile);
            Settings = AppSettings.Load(paths.Settings);
            // the desktop id: 32 lowercase hex, made once (protocol: TXT "id", welcome "desktop")
            if (string.IsNullOrEmpty(Settings.HostId) || Settings.HostId.Length != 32) Settings.HostId = Guid.NewGuid().ToString("N");
            if (string.IsNullOrEmpty(Settings.SunshineUser)) Settings.SunshineUser = "dsforever";
            if (string.IsNullOrEmpty(Settings.SunshinePass) || Crypto.Unprotect(Settings.SunshinePass) == null)
                Settings.SunshinePass = Crypto.Protect(Crypto.Base64Url(Crypto.RandomBytes(18)).Replace("-", "a").Replace("_", "b"));
            SaveSettings();
            Devices = new DeviceStore(paths.Devices);
            MakeStarter();
            MakeSupervisor();
            var adbPath = DsfDesktop.Thor.AdbProcess.Locate(paths.AppDir);
            this.Thor = new DsfDesktop.Thor.ThorManager(adbPath != null ? new DsfDesktop.Thor.AdbProcess(adbPath) : null, new DsfDesktop.Thor.ReleaseFeed(), Path.Combine(paths.DataDir, "apk"));
            this.Thor.Changed += Fire;
            SunshineLayout = new SunshineLayout { ExeDir = paths.SunshineExeDir, DataDir = paths.SunshineData };
            Codes.Changed += Fire;
            Devices.Changed += Fire;
        }

        public void SaveSettings() { try { Settings.Save(Paths.Settings); } catch (Exception e) { Log.I("settings not saved: " + e.Message); } }

        void Fire() { try { Changed?.Invoke(); } catch (Exception) { } }

        string Pass => Crypto.Unprotect(Settings.SunshinePass);

        // ---------------------------------------------------------------- start / stop

        public void Start()
        {
            running = true;
            Log.I("DS Forever Desktop starting; data " + Paths.DataDir);
            LocateWow();
            StartBridge();
            StartSunshine();
            StartMdns();
            pollThread = new Thread(PollLoop) { IsBackground = true, Name = "log-poll" };
            pollThread.Start();
            slowTimer = new Timer(_ => SlowTick(), null, 1000, 1000);
            if (Settings.AutoAddons) Task.Run(() => AddonsAuto());
            Fire();
        }

        public void Dispose()
        {
            running = false;
            slowTimer?.Dispose();
            try { mdns?.Dispose(); } catch (Exception) { }
            try { Bridge?.Stop(); } catch (Exception) { }
            try { Sunshine?.Dispose(); } catch (Exception) { }
            api?.Dispose();
            Log.I("stopped");
        }

        // ---------------------------------------------------------------- WoW + the log

        public void LocateWow()
        {
            var inst = WowLocator.Find(Settings.WowRoot, DateTime.Now);
            lock (gate)
            {
                Wow = inst;
                Flavor = WowLocator.ChooseFlavor(inst, DateTime.Now);
                var path = inst != null && Flavor != null ? inst.LogPath(Flavor) : null;
                if (tailer == null) { tailer = new LogTailer(path); tailer.RawLine = l => { lock (loginLog) loginLog.Line(l); }; }
                else if (tailer.Path != path) { tailer.SetPath(path); lock (loginLog) loginLog.Reset(); clientDocAt = null; }
                Notices = inst != null && Flavor != null ? ConfigCheck.Notices(inst.FlavorDir(Flavor)) : new List<string>();
            }
            Log.I(inst == null ? "WoW Forever not found" : "WoW at " + inst.Root + " (" + string.Join(", ", inst.Flavors) + "), following " + Flavor);
            RefreshAddons();
            Fire();
        }

        /// <summary>the user picked a folder (asked once when none was found); false when it isn't a WoW Forever install</summary>
        public bool SetWowFolder(string chosen)
        {
            var root = WowLocator.NormalizeRoot(chosen);
            if (root == null || WowLocator.Describe(root, DateTime.Now) == null) return false;
            Settings.WowRoot = root;
            SaveSettings();
            LocateWow();
            if (Settings.AutoAddons) Task.Run(() => AddonsAuto());
            return true;
        }

        void PollLoop()
        {
            while (running)
            {
                try
                {
                    if (DateTime.UtcNow - followAt > TimeSpan.FromSeconds(3)) { followAt = DateTime.UtcNow; Follow(); }
                    List<LogEvent> evs;
                    lock (gate) evs = tailer?.Poll() ?? new List<LogEvent>();
                    bool changed = false;
                    foreach (var e in evs)
                    {
                        switch (e.Kind)
                        {
                            case LogEventKind.Doc:
                                Interlocked.Increment(ref DocsSeen);
                                LastDocAt = DateTime.Now;
                                clientDocAt = DateTime.UtcNow;
                                Bridge?.PublishDoc(e);
                                break;
                            case LogEventKind.Gap:
                                Bridge?.Publish(new Dictionary<string, object> { ["t"] = "gap", ["seq"] = e.Seq, ["expected"] = e.Expected });
                                Log.I(e.Text);
                                changed = true;
                                break;
                            case LogEventKind.Restart:
                                lock (loginLog) loginLog.Reset();
                                clientDocAt = null;
                                Bridge?.PublishLog("restart", LogPath, Flavor);
                                LogStatus = e.Text; Log.I(e.Text); changed = true;
                                break;
                            case LogEventKind.Status:
                                Bridge?.Publish(new Dictionary<string, object> { ["t"] = "status", ["text"] = e.Text });
                                LogStatus = e.Text; changed = true;
                                break;
                        }
                    }
                    if (changed) Fire();
                }
                catch (Exception e) { LogStatus = "tail error: " + e.Message; }
                Thread.Sleep(50);
            }
        }

        /// <summary>LogFollow: switch to the other flavor's log when that one is the one being written</summary>
        void Follow()
        {
            var inst = Wow;
            if (inst == null) return;
            var f = WowLocator.ChooseFlavor(inst, DateTime.Now);
            if (f == null || f == Flavor) return;
            lock (gate)
            {
                Flavor = f;
                tailer.SetPath(inst.LogPath(f));
                lock (loginLog) loginLog.Reset();
                clientDocAt = null;
                Notices = ConfigCheck.Notices(inst.FlavorDir(f));
            }
            Log.I("following the game's log: " + f);
            Bridge?.PublishLog("switch", LogPath, Flavor);
            RefreshAddons();
            Fire();
        }

        // ---------------------------------------------------------------- add-ons

        AddonManifest Manifest()
        {
            var f = Path.Combine(Paths.AddonSource ?? "", "manifest.txt");
            return File.Exists(f) ? AddonManifest.Parse(File.ReadAllText(f)) : null;
        }

        public void RefreshAddons()
        {
            var m = Manifest();
            if (m == null) { AddonLine = "the add-on set isn't bundled (" + Paths.AddonSource + ")"; Addons = null; return; }
            Addons = AddonInstaller.Compare(m, Wow != null && Flavor != null ? Wow.AddOnsDir(Flavor) : null);
            AddonLine = Addons.ToString();
        }

        /// <summary>install into every WoW Forever flavor present (both folders exist around the launch)</summary>
        string InstallAll(bool force)
        {
            var m = Manifest();
            if (m == null) return "the add-on set isn't bundled";
            if (Wow == null) return "game folder not found";
            var lines = Wow.Flavors.Select(f => f + ": " + AddonInstaller.Install(m, Path.Combine(Paths.AddonSource, "addons"), Wow.AddOnsDir(f), Paths.Backups, force)).ToList();
            var line = string.Join("; ", lines);
            Log.I(line);
            RefreshAddons();
            Fire();
            return line;
        }

        void AddonsAuto() { try { InstallAll(false); } catch (Exception e) { Log.I("add-ons: " + e.Message); } }

        public string InstallAddons() => InstallAll(true);

        public Dictionary<string, object> AddonsStatus()
        {
            RefreshAddons();
            var s = Addons;
            return new Dictionary<string, object>
            {
                ["state"] = s == null ? "not_bundled" : s.State.ToString().ToLowerInvariant(),
                ["app_set"] = s?.AppSet, ["game_set"] = s?.GameSet, ["differ"] = s?.Differ ?? 0, ["text"] = AddonLine,
            };
        }

        public Dictionary<string, object> AddonsInstall()
        {
            var line = InstallAll(true);
            var d = AddonsStatus();
            d["result"] = line;
            return d;
        }

        // ---------------------------------------------------------------- Sunshine

        void StartSunshine()
        {
            Sunshine = new SunshineHost(SunshineLayout);
            Sunshine.Status += _ => Fire();
            if (!Sunshine.Available) { Log.I("sunshine not bundled at " + SunshineLayout.Exe); return; }
            int port = PortPlanner.Pick(Settings.SunshinePort, PortPlanner.TcpFree, PortPlanner.UdpFree);
            if (port < 0) { Log.I("sunshine: no free port set"); return; }
            if (port != Settings.SunshinePort) { Settings.SunshinePort = port; SaveSettings(); }
            SunshinePort = port;
            WriteSunshineConfig();
            if (!File.Exists(SunshineLayout.Credentials) && !Sunshine.SetCredentials(Settings.SunshineUser, Pass))
                Log.I("sunshine: setting its login failed");
            api = new SunshineApi(port, Settings.SunshineUser, Pass, SunshineLayout.Cert);
            Sunshine.Start();
        }

        // no prep command: the portable Sunshine can't start ours (error 5, tracking 252f5ec7); the Thor's bridge "focus" covers it
        void WriteSunshineConfig() => SunshineConfig.Write(SunshineLayout, new SunshineOptions
        {
            Port = SunshinePort, Encoder = Settings.Encoder, EventExe = "", HostName = Environment.MachineName,
            BindAddress = Loopback ? "127.0.0.1" : "", OutputName = Settings.CaptureDevice,
        });

        public void RestartSunshine()
        {
            Sunshine?.Stop();
            Sunshine?.Start();
        }

        public void StreamPair(string deviceId, string name, string pin, string peerIp, Action<string> done)
        {
            Task.Run(async () =>
            {
                string err;
                try
                {
                    if (api == null || Sunshine == null || !Sunshine.Running) err = "the streaming host isn't running";
                    else err = await api.CompletePairing(pin, SunshineClientName(deviceId, name), peerIp, TimeSpan.FromSeconds(Bridge?.StreamPairSeconds ?? 90), CancellationToken.None).ConfigureAwait(false);
                }
                catch (Exception e) { err = e.Message; }
                Log.I("stream pairing for " + name + ": " + (err ?? "done"));
                done(err);
            });
        }

        static readonly HttpClient Local = new HttpClient { Timeout = TimeSpan.FromSeconds(3) };

        /// <summary>Sunshine's uniqueid (its serverinfo / _nvstream advert): TXT "sid", so the Thor ties the two adverts together</summary>
        public string SunshineId { get; private set; }
        int tickN;
        DateTime lastEventAt = DateTime.MinValue;

        /// <summary>every second: the window refresh; every 2 s Sunshine's own state (BUSY = streaming) and its uniqueid</summary>
        /// <summary>
        /// WoW goes when the stream has (the user, 2026-10-30: "Wow should close itself when disconnecting the stream after like 5 minutes
        /// of being disconnected"): asked once, by its window, as its X. Someone using the PC keeps it open. The Thor's bridge says the
        /// stream has gone: Sunshine's serverinfo stays BUSY after the client leaves (measured 2026-10-30, no client and still BUSY).
        /// </summary>
        DateTime? streamEndedAt;
        bool thorWasHere;

        void CloseAfterStream()
        {
            try
            {
                if (Bridge != null && Bridge.Sessions().Count > 0) { thorWasHere = true; streamEndedAt = null; return; }
                if (!thorWasHere) return;
                if (!streamEndedAt.HasValue) streamEndedAt = DateTime.UtcNow;
                if (!WowFlow.CloseAfterStream((DateTime.UtcNow - streamEndedAt.Value).TotalSeconds, WowWindow.PcIdleSeconds())) return;
                thorWasHere = false; streamEndedAt = null;
                Supervisor?.Disconnect();
                using (var p = WowWindow.FindAny(FlavorDirOrNull))
                {
                    if (p == null) return;
                    Log.I("WoW: no stream for 5 minutes, closing it (pid " + p.Id + ")");
                    p.CloseMainWindow();
                }
            }
            catch (Exception e) { Log.I("closing WoW after the stream: " + e.Message); }
        }

        void SlowTick()
        {
            CloseAfterStream();
            try
            {
                if (tickN++ % 2 == 0)
                {
                    if (SunshinePort > 0 && Sunshine != null && Sunshine.Running)
                    {
                        var xml = Local.GetStringAsync("http://127.0.0.1:" + SunshinePort + "/serverinfo?uniqueid=0123456789ABCDEF").Result;
                        var st = Regex.Match(xml, "<state>([^<]*)</state>").Groups[1].Value;
                        var id = Regex.Match(xml, "<uniqueid>([^<]*)</uniqueid>").Groups[1].Value;
                        if (id.Length > 0) SunshineId = id;
                        // Sunshine's prep commands (OnEvent) land a moment before its serverinfo changes: they win for a few seconds
                        if (DateTime.UtcNow - lastEventAt > TimeSpan.FromSeconds(10))
                            SetStream(st.IndexOf("BUSY", StringComparison.OrdinalIgnoreCase) >= 0 ? StreamState.Streaming : StreamState.Idle);
                    }
                    else SetStream(StreamState.Unknown);
                    CheckCapture();
                }
            }
            catch (Exception) { }
            try { Starter.Tick(); } catch (Exception) { }
            try { WowTick(); } catch (Exception e) { Log.I("wow tick: " + e.Message); }
            if (ThorWatching && DateTime.UtcNow - thorPolledAt > TimeSpan.FromSeconds(3) && !Thor.Busy)
            {
                thorPolledAt = DateTime.UtcNow;
                Task.Run(async () => { await Thor.CheckRelease().ConfigureAwait(false); Thor.Refresh(); });
            }
            Fire();
        }

        string StreamWord => Stream == StreamState.Streaming ? "started" : Stream == StreamState.Idle ? "stopped" : "unknown";

        void SetStream(StreamState s)
        {
            if (s == Stream) return;
            var was = Stream;
            Stream = s;
            Log.I("stream: " + s);
            Bridge?.Publish(new Dictionary<string, object> { ["t"] = "stream", ["state"] = StreamWord });
            if (s == StreamState.Streaming && was != StreamState.Streaming)
                Task.Run(() =>
                {
                    // the stream shows the real desktop: WoW must be up, signed in and in front (the user's rules: WowFlow.Decide)
                    if (Settings.StartWowOnStream) Want("stream started");
                    else if (Settings.FocusOnStream) { CheckCapture(); FocusWow(); }
                });
        }

        /// <summary>from Sunshine's prep commands (--event stream-start / stream-stop, through the single-instance pipe)</summary>
        public void OnEvent(string evt)
        {
            Log.I("event: " + evt);
            lastEventAt = DateTime.UtcNow;
            if (evt == "stream-start") SetStream(StreamState.Streaming);
            else if (evt == "stream-stop") SetStream(StreamState.Idle);
            Fire();
        }

        public string FocusWow()
        {
            var why = WowWindow.Focus(Wow != null && Flavor != null ? Wow.FlavorDir(Flavor) : null);
            Log.I("focus WoW: " + (why ?? "done"));
            return why;
        }

        public Dictionary<string, object> Focus()
        {
            if (Settings.StartWowOnStream)
            {
                Want("the Thor asked");
                var s = WowInfo();
                bool ok = SignedIn(LastScreen) && WowWindow.IsForeground();
                s["ok"] = ok;
                if (!ok) s["why"] = Supervisor.NeedsYou ? WowFlow.NeedsYouText : Starter.State == WowState.Starting ? "starting WoW" : (Starter.Why ?? "WoW isn't signed in yet");
                return s;
            }
            CheckCapture();
            var why = FocusWow();
            var d = new Dictionary<string, object> { ["ok"] = why == null };
            if (why != null) d["why"] = why;
            return d;
        }

        // ---------------------------------------------------------------- starting WoW, and the monitor Sunshine captures

        public WowStarter Starter { get; private set; }

        /// <summary>the USB install of DS Forever on the Thor (the Thor card)</summary>
        public DsfDesktop.Thor.ThorManager Thor { get; private set; }
        /// <summary>the window is showing the Thor card: only then is adb asked for devices (every 3 s)</summary>
        public bool ThorWatching { get; set; }
        DateTime thorPolledAt = DateTime.MinValue;

        string FlavorDirOrNull => Wow != null && Flavor != null ? Wow.FlavorDir(Flavor) : null;

        /// <summary>a WoW window is up (any flavor)</summary>
        public bool WowUp() => WowWindow.Find(FlavorDirOrNull) != null;

        void MakeStarter()
        {
            Starter = new WowStarter(
                windowUp: WowUp,
                processUp: WowWindow.ProcessRunning,
                plan: () => WowLaunch.Plan(Wow, DateTime.Now, WowLaunch.FindBattleNet()),
                start: WowStarter.StartProcess);
            Starter.Changed += (state, why) =>
            {
                if (Supervisor != null) PublishWow(force: true);
                else
                {
                    var msg = new Dictionary<string, object> { ["t"] = "wow", ["state"] = WowStarter.Word(state) };
                    if (why != null) msg["why"] = why;
                    Bridge?.Publish(msg);
                }
                var text = state == WowState.Starting ? "Starting WoW on your PC…" : state == WowState.StartFailed ? "WoW didn't start: " + why
                         : state == WowState.Running ? "WoW is running on your PC" : "WoW was closed on your PC";
                Bridge?.Publish(new Dictionary<string, object> { ["t"] = "status", ["text"] = text });
                if (state == WowState.Running)
                    Task.Run(() =>
                    {
                        Thread.Sleep(2000);   // let the new window settle before it is moved or raised
                        CheckCapture();
                        FocusWow();
                    });
                Fire();
            };
        }


        // ---------------------------------------------------------------- the stream wants WoW signed in (the user, 2026-10-28)

        readonly LoginLog loginLog = new LoginLog();
        DateTime? clientDocAt;
        DateTime needsYouAt = DateTime.MinValue;
        public WowSupervisor Supervisor { get; private set; }
        public WowScreen LastScreen { get; private set; } = WowScreen.None;
        public BnetState LastBnet { get; private set; } = BnetState.Unknown;
        string lastWowKey;

        static bool SignedIn(WowScreen s) => s == WowScreen.CharSelect || s == WowScreen.World;

        /// <summary>where the game is now (its process, its connections, its log, the add-on's documents)</summary>
        public WowScreen ScreenNow()
        {
            var p = WowWindow.FindAny(FlavorDirOrNull);
            if (p == null) return WowScreen.None;
            try
            {
                double up = 0;
                try { up = (DateTime.Now - p.StartTime).TotalSeconds; } catch (Exception) { }
                var (game, auth) = WowProbe.Connections(p.Id);
                double? docAge = clientDocAt.HasValue ? (DateTime.UtcNow - clientDocAt.Value).TotalSeconds : (double?)null;
                lock (loginLog) return WowFlow.Classify(true, up, game, auth, loginLog, docAge);
            }
            finally { p.Dispose(); }
        }

        public BnetState BnetNow()
        {
            var w = WowProbe.Bnet();
            return WowFlow.Bnet(WowLaunch.FindBattleNet() != null, w.Running, w.UptimeS, w.Login != IntPtr.Zero, w.Any);
        }

        DateTime wantedAt = DateTime.MaxValue;
        int crashedPid = -1;

        /// <summary>
        /// The client crashed (the user's PC 2026-10-29: "Fatal Error - Wow.exe", the client older than the game's data): its crash
        /// window is up, or it is gone and wrote a crash report since the Thor asked for it. "update" / "crash", null = no crash.
        /// </summary>
        string CrashNow()
        {
            var p = WowWindow.FindAny(FlavorDirOrNull);
            try
            {
                if (p != null && !WowProbe.FatalErrorShown(p.Id)) return null;
                var since = p != null ? SafeStart(p).AddSeconds(-5) : wantedAt;
                if (since == DateTime.MaxValue) return null;
                var report = WowProbe.CrashReport(FlavorDirOrNull, since);
                if (report == null && p == null) return null;
                if (p != null) crashedPid = p.Id;
                Log.I("WoW crashed: " + WowFlow.CrashLine(report));
                return WowFlow.CrashKind(report);
            }
            finally { p?.Dispose(); }
        }

        DateTime lastShowMove = DateTime.MinValue;

        /// <summary>
        /// While the player has to use Battle.net through the stream (its sign-in, an update after a crash, a sign-in that needs them):
        /// Battle.net's window on the captured monitor (moved there if it is elsewhere) as the Thor's "show" rectangle, so the Thor shows
        /// THAT window alone, never the desktop round it. null = nothing to show.
        /// </summary>
        string ShowRectNow()
        {
            var sv = Supervisor;
            if (sv == null) return null;
            bool wanted = LastBnet == BnetState.SignInNeeded || sv.Crash != null || sv.NeedsYou;
            if (!wanted) return null;
            var b = WowProbe.Bnet();
            var h = b.Login != IntPtr.Zero ? b.Login : WowProbe.BnetMain();
            if (h == IntPtr.Zero) return null;
            var list = SunshineDisplays();
            var captured = list.Count > 0 ? Displays.Captured(list, Settings.CaptureDevice) : null;
            if (captured == null) return null;
            if (!string.Equals(Displays.MonitorOf(h), captured, StringComparison.OrdinalIgnoreCase) && DateTime.UtcNow - lastShowMove > TimeSpan.FromSeconds(5))
            {
                lastShowMove = DateTime.UtcNow;
                Log.I("Battle.net to the captured monitor " + captured + ": " + (Displays.MoveTo(h, captured) ?? "done"));
            }
            var mon = Displays.MonitorRect(captured);
            var win = Displays.Visible(h);
            return mon.HasValue && win.HasValue ? Displays.ShowRect(win.Value, mon.Value) : null;
        }

        /// <summary>
        /// The Thor is about to type its saved login (the user's ruling 2026-10-30): keys go to whichever window has the focus, so "ok" means
        /// Battle.net's sign-in window is in front on the streamed monitor, NOW, and its form was found; the answer gives the Thor's clicks.
        /// </summary>
        public Dictionary<string, object> BnetReady()
        {
            var no = new Func<string, Dictionary<string, object>>(why => { Log.I("sign-in form: " + why); return new Dictionary<string, object> { ["ok"] = false, ["why"] = why }; });
            var h = WowProbe.Bnet().Login;
            if (h == IntPtr.Zero) return no("no sign-in window");
            var list = SunshineDisplays();
            var captured = list.Count > 0 ? Displays.Captured(list, Settings.CaptureDevice) : null;
            if (captured == null) return no("no captured monitor");
            if (!string.Equals(Displays.MonitorOf(h), captured, StringComparison.OrdinalIgnoreCase))
            {
                var moved = Displays.MoveTo(h, captured);
                if (moved != null) return no(moved);
                System.Threading.Thread.Sleep(300);
            }
            var focus = WowWindow.FocusHandle(h);
            if (focus != null) return no(focus);
            System.Threading.Thread.Sleep(150);
            var px = BnetForm.Read(h, out int w, out int ht);
            var form = BnetForm.Detect(px, w, ht);
            if (form == null) return no("the form is not one we know");
            var mon = Displays.MonitorRect(captured);
            if (!mon.HasValue || !Displays.GetRect(h, out var win)) return no("no window rectangle");
            if (!WowWindow.IsForegroundHandle(h)) return no("another window took the front");
            var d = new Dictionary<string, object>
            {
                ["ok"] = true,
                ["password"] = BnetForm.Share(form.Password, win, mon.Value),
                ["button"] = BnetForm.Share(form.Button, win, mon.Value),
            };
            if (form.Email.HasValue) d["email"] = BnetForm.Share(form.Email.Value, win, mon.Value);
            Log.I("sign-in form found (" + (form.Email.HasValue ? "email and password" : "password") + ")");
            return d;
        }

        static DateTime SafeStart(System.Diagnostics.Process p) { try { return p.StartTime; } catch (Exception) { return DateTime.Now; } }

        /// <summary>after a crash: the dead client (its crash window) goes, Battle.net comes to the front so the player can update WoW
        /// through the stream (nothing is pressed for them)</summary>
        void AfterCrash()
        {
            try
            {
                if (crashedPid > 0)
                    using (var p = System.Diagnostics.Process.GetProcessById(crashedPid))
                        if (WowProbe.FatalErrorShown(p.Id)) { Log.I("WoW crashed: closing its crash window (pid " + p.Id + ")"); p.Kill(); p.WaitForExit(5000); }
            }
            catch (Exception) { }
            var b = WowProbe.Bnet();
            if (!b.Running) { WowProbe.StartBattleNet(WowLaunch.FindBattleNet()); return; }
            var h = b.Login != IntPtr.Zero ? b.Login : WowProbe.BnetMain();
            if (h != IntPtr.Zero) Log.I("WoW crashed: Battle.net to the front: " + (WowWindow.FocusHandle(h) ?? "done"));
        }

        /// <summary>a stream (the Thor) wants WoW: start the supervisor unless it is already at work; never again for 2 minutes after
        /// it gave up (a flaky bridge reconnecting must not loop the game)</summary>
        void Want(string why)
        {
            if (Supervisor.Active) { Supervisor.Step(); return; }
            if (Supervisor.NeedsYou && DateTime.UtcNow - needsYouAt < TimeSpan.FromMinutes(2)) return;
            Log.I("WoW wanted: " + why);
            wantedAt = DateTime.Now;
            Supervisor.Connect();
        }

        /// <summary>
        /// The old client at the login screen goes: its window is asked to close (WM_CLOSE, as its X; no keys are ever sent); after
        /// 15 s, if it is still there AND still not signed in, it is ended. null = gone.
        /// </summary>
        string CloseForRestart()
        {
            var p = WowWindow.FindAny(FlavorDirOrNull);
            if (p == null) return null;
            try
            {
                Log.I("restart WoW: closing the client at the login screen (pid " + p.Id + ")");
                try { p.CloseMainWindow(); } catch (Exception) { }
                if (p.WaitForExit(15000)) return null;
                if (SignedIn(ScreenNow())) return "WoW signed in meanwhile";
                Log.I("restart WoW: still open after 15 s at the login screen: ending it");
                try { p.Kill(); } catch (Exception e) { return "couldn't close WoW: " + e.Message; }
                return p.WaitForExit(10000) ? null : "WoW didn't close";
            }
            finally { p.Dispose(); }
        }

        void MakeSupervisor()
        {
            Supervisor = new WowSupervisor(
                screen: () => LastScreen = ScreenNow(),
                bnet: () => LastBnet = BnetNow(),
                startBnet: () => WowProbe.StartBattleNet(WowLaunch.FindBattleNet()),
                showBnetSignIn: () => WowWindow.FocusHandle(WowProbe.Bnet().Login),
                startWow: () => { Starter.Ensure(); return Starter.State == WowState.StartFailed ? Starter.Why : null; },
                startPending: () => Starter.State == WowState.Starting,
                closeWow: CloseForRestart,
                focusWow: () => { CheckCapture(); return FocusWow(); },
                crash: CrashNow);
            Supervisor.Changed += (a, detail) =>
            {
                if (a == WowAction.NeedsYou) needsYouAt = DateTime.UtcNow;
                if (a == WowAction.Crashed) { needsYouAt = DateTime.UtcNow; Task.Run(AfterCrash); }
                var text = a == WowAction.StartBattleNet ? "Starting Battle.net on your PC…"
                         : a == WowAction.ShowBattleNetSignIn ? "Sign in to Battle.net"
                         : a == WowAction.RestartWow ? "WoW was at its login screen: starting it again…"
                         : a == WowAction.NeedsYou ? WowFlow.NeedsYouText
                         : a == WowAction.Crashed ? (detail == "update" ? WowFlow.UpdateText : WowFlow.CrashText) : null;
                if (text != null) Bridge?.Publish(new Dictionary<string, object> { ["t"] = "status", ["text"] = text });
                PublishWow(force: true);
            };
        }

        /// <summary>the game and Battle.net for the Thor (the "wow" message, the welcome's wow): its curtain lifts on charselect / world + focused</summary>
        public Dictionary<string, object> WowInfo()
        {
            string err;
            lock (loginLog) err = loginLog.Error;
            var d = new Dictionary<string, object>
            {
                ["state"] = WowStarter.Word(WowUp() ? WowState.Running : Starter.State),
                ["screen"] = WowFlow.Word(LastScreen),
                ["focused"] = WowWindow.IsForeground(),
                ["bnet"] = WowFlow.Word(LastBnet),
                ["needs_you"] = Supervisor?.NeedsYou ?? false,
            };
            if (Supervisor?.Crash != null) d["crash"] = Supervisor.Crash;
            var show = ShowRectNow();
            if (show != null) d["show"] = show;
            if (Starter.Why != null) d["why"] = Starter.Why;
            if (err != null) d["login_error"] = err;
            return d;
        }

        /// <summary>every tick: the screen and Battle.net looked at, the supervisor stepped, a "wow" message when anything changed</summary>
        void WowTick()
        {
            LastScreen = ScreenNow();
            LastBnet = BnetNow();
            // the player updated and started WoW again (a client that is not the crashed one, without a crash window)
            if (Supervisor.Crash != null && LastScreen != WowScreen.None)
                using (var p = WowWindow.FindAny(FlavorDirOrNull))
                    if (p != null && p.Id != crashedPid && !WowProbe.FatalErrorShown(p.Id)) Supervisor.CrashOver();
            if (Supervisor.Active) Supervisor.Step();
            PublishWow(force: false);
        }

        void PublishWow(bool force)
        {
            var w = WowInfo();
            var key = string.Join("|", w.OrderBy(kv => kv.Key).Select(kv => kv.Key + "=" + kv.Value));
            if (!force && key == lastWowKey) return;
            lastWowKey = key;
            var msg = new Dictionary<string, object> { ["t"] = "wow" };
            foreach (var kv in w) msg[kv.Key] = kv.Value;
            Bridge?.Publish(msg);
        }

        public string CaptureStatus { get; private set; } = "";
        DateTime lastRecapture = DateTime.MinValue, lastMove = DateTime.MinValue;
        readonly object captureGate = new object();

        List<SunshineDisplay> SunshineDisplays()
        {
            try
            {
                using (var fs = new FileStream(SunshineLayout.LogFile, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                using (var r = new StreamReader(fs)) return Displays.ParseSunshineLog(r.ReadToEnd());
            }
            catch (Exception) { return new List<SunshineDisplay>(); }
        }

        /// <summary>
        /// Keeps the captured monitor and WoW's together: no stream running, Sunshine is pointed at WoW's monitor (output_name, a
        /// Sunshine restart, remembered in settings); during a stream WoW's window is moved to the captured monitor instead.
        /// </summary>
        void CheckCapture()
        {
            if (!Monitor.TryEnter(captureGate)) return;
            try
            {
                if (Sunshine == null || !Sunshine.Running) return;
                var p = WowWindow.Find(FlavorDirOrNull);
                if (p == null) return;
                var wowDisplay = Displays.MonitorOf(p.MainWindowHandle);
                var list = SunshineDisplays();
                if (list.Count == 0) return;
                var captured = Displays.Captured(list, Settings.CaptureDevice);
                var target = list.FirstOrDefault(d => string.Equals(d.DisplayName, wowDisplay, StringComparison.OrdinalIgnoreCase));
                switch (Displays.Decide(wowDisplay, captured, Stream == StreamState.Streaming, target?.DeviceId != null))
                {
                    case Displays.Fix.RecaptureSunshine:
                        if (DateTime.UtcNow - lastRecapture < TimeSpan.FromSeconds(90)) return;
                        lastRecapture = DateTime.UtcNow;
                        Settings.CaptureDevice = target.DeviceId;
                        SaveSettings();
                        CaptureStatus = "capturing " + (target.Friendly ?? target.DisplayName) + ", the monitor WoW is on";
                        Log.I("capture: WoW is on " + wowDisplay + ", Sunshine captured " + captured + ": now " + target.DeviceId + " (restart)");
                        Bridge?.Publish(new Dictionary<string, object> { ["t"] = "status", ["text"] = "Switching the stream to the monitor WoW is on" });
                        WriteSunshineConfig();
                        RestartSunshine();
                        break;
                    case Displays.Fix.MoveWow:
                        if (DateTime.UtcNow - lastMove < TimeSpan.FromSeconds(15)) return;
                        lastMove = DateTime.UtcNow;
                        var why = Displays.MoveTo(p.MainWindowHandle, captured);
                        CaptureStatus = why == null ? "moved WoW to the streamed monitor" : "couldn't move WoW to the streamed monitor: " + why;
                        Log.I("capture: stream running, WoW on " + wowDisplay + ", captured " + captured + ": " + CaptureStatus);
                        FocusWow();
                        break;
                    default:
                        var cap = list.FirstOrDefault(d => d.DisplayName == captured);
                        CaptureStatus = "capturing " + (cap?.Friendly ?? captured) + (wowDisplay == captured ? ", where WoW is" : "");
                        break;
                }
            }
            catch (Exception e) { Log.I("capture: " + e.Message); }
            finally { Monitor.Exit(captureGate); }
        }

        // ---------------------------------------------------------------- bridge + mDNS

        System.Security.Cryptography.X509Certificates.X509Certificate2 cert;

        void StartBridge()
        {
            for (int i = 0; i < 10; i++)
            {
                try
                {
                    cert = cert ?? BridgeCert.LoadOrCreate(Paths.DataDir, Settings.HostId);
                    Bridge = new BridgeServer(this, cert, Settings.BridgePort + i, Loopback ? IPAddress.Loopback : IPAddress.Any);
                    Bridge.SessionsChanged += Fire;
                    Bridge.Start();
                    BridgeStatus = "listening on port " + Bridge.Port;
                    return;
                }
                catch (Exception e) { Log.I("bridge port " + (Settings.BridgePort + i) + ": " + e.Message); Bridge = null; }
            }
            BridgeStatus = "couldn't open a port";
        }

        void StartMdns()
        {
            if (Bridge == null || Loopback) return;
            try
            {
                mdns = new MdnsResponder(Environment.MachineName, (ushort)Bridge.Port, () =>
                {
                    var txt = new Dictionary<string, string>
                    {
                        ["proto"] = BridgeServer.Proto.ToString(),
                        ["id"] = Settings.HostId,
                        ["pc"] = Environment.MachineName,
                        ["sunshine"] = SunshinePort.ToString(),
                        ["ver"] = Version,
                    };
                    if (Bridge.Tls) { txt["tls"] = "1"; txt["fp"] = Bridge.Fingerprint; }
                    if (SunshineId != null) txt["sid"] = SunshineId;
                    return txt;
                });
                mdns.Start();
            }
            catch (Exception e) { Log.I("mdns: " + e.Message); }
        }

        public static string Version => typeof(AppController).Assembly.GetName().Version.ToString(3);

        /// <summary>/v1/info and the welcome: no secrets</summary>
        /// <summary>the PC's up, non-loopback IPv4s on its own networks (Ethernet / Wi-Fi), Tailscale's CGNAT range left out</summary>
        public static List<object> LanAddresses()
        {
            var out_ = new List<object>();
            try
            {
                foreach (var ni in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                    if (ni.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback ||
                        ni.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Tunnel) continue;
                    foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                    {
                        var a = ua.Address;
                        if (a.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork || IPAddress.IsLoopback(a)) continue;
                        if (IsLan(a.GetAddressBytes())) out_.Add(a.ToString());
                    }
                }
            }
            catch (Exception) { }
            return out_;
        }

        /// <summary>a private home-network IPv4 (10/8, 172.16/12, 192.168/16); never Tailscale (100.64/10) or link-local</summary>
        public static bool IsLan(byte[] b) =>
            b.Length == 4 && (b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) || (b[0] == 192 && b[1] == 168));

        public Dictionary<string, object> Info() => new Dictionary<string, object>
        {
            ["app"] = "DS Forever Desktop",
            ["version"] = Version,
            ["host_id"] = Settings.HostId,
            ["pc_name"] = Environment.MachineName,
            // the PC's home-network IPv4s (not loopback, not Tailscale's 100.64/10): the Thor tries these first, Tailscale after
            // (the user, 2026-10-28: "Yes, Wi-Fi first" - with the Thor's Tailscale off it only knew the Tailscale address)
            ["lan"] = LanAddresses(),
            ["sunshine"] = new Dictionary<string, object>
            {
                ["running"] = Sunshine?.Running ?? false,
                ["http_port"] = SunshinePort,
                ["https_port"] = SunshinePort > 0 ? SunshinePort - 5 : -1,
                ["app_name"] = "WoW Forever",
                ["uniqueid"] = SunshineId,
            },
            ["stream"] = StreamWord,
            ["wow"] = new Dictionary<string, object>
            {
                ["found"] = Wow != null, ["flavor"] = Flavor, ["running"] = WowWindow.IsRunning(), ["focused"] = WowWindow.IsForeground(),
                // running / starting / stopped / start_failed (+ why): "Starting WoW on your PC..." on the Thor
                ["state"] = WowStarter.Word(WowUp() ? WowState.Running : Starter.State), ["why"] = Starter.Why,
                // where it is and what Battle.net needs (0.2.1): the Thor keeps its curtain until charselect / world + focused
                ["screen"] = WowFlow.Word(LastScreen), ["bnet"] = WowFlow.Word(LastBnet), ["needs_you"] = Supervisor?.NeedsYou ?? false,
            },
            ["pairing_open"] = !Codes.Locked,
        };

        /// <summary>the name the device's Moonlight pairing gets inside Sunshine, unique per device so Remove can unpair it there too</summary>
        public static string SunshineClientName(string id, string name)
            => DeviceStore.Clean(name, 40) + " (" + new string((id ?? "").Where(char.IsLetterOrDigit).Take(8).ToArray()) + ")";

        public void RemoveDevice(string id)
        {
            var dev = Devices.All().FirstOrDefault(d => d.Id == id);
            if (dev != null && api != null)
            {
                var want = SunshineClientName(dev.Id, dev.Name);
                Task.Run(async () =>
                {
                    try
                    {
                        foreach (var c in await api.Clients(CancellationToken.None).ConfigureAwait(false))
                            if (c.Str("name") == want && c.Str("uuid") != null) await api.Unpair(c.Str("uuid"), CancellationToken.None).ConfigureAwait(false);
                    }
                    catch (Exception e) { Log.I("sunshine unpair: " + e.Message); }
                });
            }
            Devices.Remove(id);
            foreach (var s in Bridge?.Sessions() ?? new List<BridgeSession>())
                if (s.DeviceId == id) { s.Enqueue(new Dictionary<string, object> { ["t"] = "unpaired" }); Thread.Sleep(100); s.Ws.Close(4401); }
        }
    }
}
