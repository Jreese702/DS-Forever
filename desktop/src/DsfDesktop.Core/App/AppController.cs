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
using DsfDesktop.Wow.Patch;

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
        /// <summary>the last streaming-port check (a foreign Sunshine on the GameStream ports, ours not listening); null = not yet</summary>
        public PortReport Ports { get; private set; }
        /// <summary>what is wrong with streaming on this PC, for the window and the Thor (null = nothing): the port check, or our
        /// sunshine.exe exiting on its own</summary>
        public string HostProblem => Sunshine != null && Sunshine.Exited ? "exited" : HealProblem ?? Ports?.Problem ?? ScreenProblem;
        public string HostWhy => Sunshine != null && Sunshine.Exited ? "pc busy: the streaming host stopped"
            : HealProblem == ProblemNotResponding ? WhyNotResponding : HealProblem == ProblemRestarting ? WhyRestarting
            : Ports?.Problem != null ? Ports.ThorWhy : ScreenProblem != null ? ScreenPlan.ThorNeedsScreen : "";

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
            ConfigKeep = new WowConfigKeep(Path.Combine(paths.DataDir, "wowconfig"), WowWindow.ProcessRunning,
                () => Stream == StreamState.Streaming || Starter.State == WowState.Starting);
            ScreenKeep = new DisplayKeep(Path.Combine(paths.DataDir, "display_keep.json"), new VddScreen.Native());
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
            // an install of the self-patcher cut short by a crash is undone before anything reads the game (0.2.36)
            // (the Agent asked once here; while it is at work the put-back goes on in the background, WoW not started meanwhile)
            try
            {
                var pr = EnsurePatcher();
                var r = pr?.Recover(agentWaitS: 0);
                if (r != null) Log.I("game patch: " + r);
                if (pr != null && pr.Unsettled) RecoverInBackground(pr);
            }
            catch (Exception e) { Log.I("game patch: recovery: " + e.Message); }
            StartBridge();
            StartSunshine();
            StartMdns();
            pollThread = new Thread(PollLoop) { IsBackground = true, Name = "log-poll" };
            pollThread.Start();
            slowTimer = new Timer(_ => SlowTick(), null, 1000, 1000);
            // Windows' displays changed (WM_DISPLAYCHANGE, through SystemEvents' own window): re-read them at the next tick
            try { Microsoft.Win32.SystemEvents.DisplaySettingsChanged += OnDisplaysChanged; } catch (Exception e) { Log.I("display events: " + e.Message); }
            // our own virtual screen left by a Desktop that didn't close cleanly (only ours, by its id; Apollo's are never touched)
            if (Guid.TryParse(Settings.VirtualScreenId ?? "", out var vid)) Task.Run(() => { try { OurScreen.RemoveLeftover(vid); } catch (Exception) { } });
            // WoW's monitor from before a crash: it goes back once no screen of ours is there (ScreenTick)
            wowHomeNow = Settings.WowHome;
            // a Virtual Display Driver monitor we attached (recorded before the attach): detached now when no Thor is here, else
            // adopted by the screen plan and detached once no Thor wants it
            if (Settings.VddAttached) Task.Run(() => RemoveLeftoverVdd());
            if (Settings.AutoAddons) Task.Run(() => AddonsAuto());
            Fire();
        }

        void RemoveLeftoverVdd()
        {
            if (!TakeScreen()) return;
            try
            {
                if ((Bridge?.Sessions().Count ?? 0) > 0 || StreamLive()) return;
                var life = Screens;
                if (!life.Exists) return;
                Log.I("virtual screen: ours from before (" + life.Current?.Name + ", Desktop didn't close cleanly): removed");
                RemoveOurs("left from before");
            }
            catch (Exception e) { Log.I("virtual screen: leftover: " + e.Message); }
            finally { FreeScreen(); }
        }

        /// <summary>the Thor's Retry by hand ("screen_retry"): our screen's tries start over (it gave up after ScreenLife.MaxFails)</summary>
        public void ScreenRetry()
        {
            Log.I("bridge: the Thor's Retry: our screen's tries start over");
            Screens.Reset(byHand: true);
            displaysDirty = true;
        }

        public void Dispose()
        {
            if (loginFileAt.HasValue && FlavorDirOrNull != null) WowLoginFile.Delete(FlavorDirOrNull);
            running = false;
            slowTimer?.Dispose();
            try { Microsoft.Win32.SystemEvents.DisplaySettingsChanged -= OnDisplaysChanged; } catch (Exception) { }
            try { screens?.Remove("Desktop closing"); } catch (Exception) { }
            // WoW back where the player had it (it was only queued after the screen went)
            try { var home = wowHome; if (home != null) { WowHome(home); wowHome = null; } } catch (Exception) { }
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
                if (tailer == null) { tailer = new LogTailer(path); tailer.RawLine = l => { lock (loginLog) loginLog.Line(l); }; tailer.Began = LogBegan; loginLogAt = null; }
                else if (tailer.Path != path) { tailer.SetPath(path); lock (loginLog) loginLog.Reset(); clientDocAt = null; loginLogAt = null; }
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
                            case LogEventKind.Restart:      // the log state was reset in LogBegan, before this run's first lines
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

        /// <summary>the tailer starts reading the file from its start (or its capped backlog), on the polling thread inside Poll, before
        /// any of its lines: the first read, the file back after it was missing, a path or flavor switch, a client restart. Whatever
        /// client is up now wrote these lines (WoW empties its log at launch), so from here they count as this run's
        /// (WowFlow.LogIsThisRun). Reset here, not on the Restart event, so the lines read in the same poll are kept. login.txt's
        /// sign-in clock is NOT reset: only a fresh login_file write starts it again ("deleted 20 s after FIRST read").</summary>
        void LogBegan()
        {
            lock (loginLog) loginLog.Reset();
            clientDocAt = null;
            loginLogAt = DateTime.Now;
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
                loginLogAt = null;             // nothing of the new file read yet: LogBegan sets it at the first read
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
            // issue #219 diagnostics: a stale sunshine.exe still holding our ports, another GameStream host, the
            // pairings Sunshine's state file holds: logged before ours starts
            foreach (var o in SunshineDiag.OtherSunshines(-1)) Log.I("sunshine: another sunshine.exe is running before ours: " + o);
            int sticky = Settings.SunshinePort;
            // 0.2.31: the stock block is never ours; a saved 47989 moves to 52989 once (a paired Thor follows the welcome's port)
            if (PortPlanner.Migrate(sticky) != sticky)
            {
                Log.I("sunshine: saved port " + sticky + " is in the stock GameStream block; moving off it (a paired Thor learns the new port from the bridge)");
                Settings.SunshinePort = sticky = 0;
                SaveSettings();
            }
            var gs = new List<int> { 47984, 47989 };
            if (sticky > 0) { gs.Add(sticky - 5); gs.Add(sticky); }
            Log.I("sunshine: GameStream ports before start: " + SunshineDiag.Listeners(gs, PortPlanner.TcpFree));
            CheckPorts("before start");
            LogNeighbours();
            Log.I("sunshine: state file: " + SunshineDiag.StateSummaryOfFile(SunshineLayout.State));
            // Windows' excluded ranges too (2026-11-17: 53010 reserved at boot, WSAEACCES): a base with a reserved port is skipped
            var excl = HostHealth.ExcludedNow(0);
            if (excl.tcp.Count + excl.udp.Count > 0) Log.I("sunshine: Windows' excluded ports: tcp " + string.Join(" ", excl.tcp) + "; udp " + string.Join(" ", excl.udp));
            int port = PortPlanner.Pick(Settings.SunshinePort, PortPlanner.TcpFree, PortPlanner.UdpFree, p => excl.tcp.Any(r => r.Has(p)), p => excl.udp.Any(r => r.Has(p)));
            if (port < 0) { Log.I("sunshine: no free port set"); CheckPorts("no free port set"); return; }
            if (sticky > 0 && port != sticky) Log.I("sunshine: port " + sticky + " was busy, moving to " + port + " (a paired Thor learns it from the bridge)");
            if (port != Settings.SunshinePort) { Settings.SunshinePort = port; SaveSettings(); }
            SunshinePort = port;
            WriteSunshineConfig();
            if (!File.Exists(SunshineLayout.Credentials) && !Sunshine.SetCredentials(Settings.SunshineUser, Pass))
                Log.I("sunshine: setting its login failed");
            api = new SunshineApi(port, Settings.SunshineUser, Pass, SunshineLayout.Cert);
            Sunshine.Start();
            foreach (var o in SunshineDiag.OtherSunshines(Sunshine.Pid)) Log.I("sunshine: another sunshine.exe runs beside ours: " + o);
            // issue #54: Sunshine's virtual Xbox pad needs ViGEmBus; without it the Thor's controller does nothing on the PC
            var pad = PadDriver.Now();
            Log.I("controller driver (ViGEmBus): " + pad + (pad == "missing" ? " - the Thor's controller can't reach WoW until it is installed" : ""));
        }

        string lastPortsKey;

        /// <summary>F5: one start line naming the other streaming hosts, their ports, the virtual displays and the drivers present</summary>
        void LogNeighbours()
        {
            try
            {
                var win = Displays.WindowsDisplays();
                var virt = win.Where(d => d.Virtual).Select(d => d.ToString()).ToList();
                // 0.2.37: a disabled adapter has no interface; it is "installed, turned off", not "not installed"
                var sudo = ScreenPlan.SudoVdaWord(SudoVda.Installed(), SudoVda.AdapterProblem());
                Log.I("neighbours: hosts " + (Ports != null && Ports.Neighbours.Count > 0 ? string.Join("; ", Ports.Neighbours) : "none") +
                      "; virtual displays " + (virt.Count > 0 ? string.Join("; ", virt) : "none") +
                      "; SudoVDA " + sudo + "; Virtual Display Driver " + (win.Any(VddScreen.IsVdd) ? "installed" : "not installed") +
                      "; own screen " + (Settings.OwnScreen ? "on" : "off") +
                      "; screens " + (win.Count > 0 ? string.Join("; ", win.Where(d => !d.Virtual).Select(d => d.ToString())) : "unread"));
            }
            catch (Exception e) { Log.I("neighbours: " + e.Message); }
        }

        /// <summary>who holds the GameStream ports (ours / another Sunshine / another program): logged and shown when it changes, and
        /// the Thor told; never stops or changes the other program</summary>
        void CheckPorts(string when)
        {
            try
            {
                var sh = Sunshine;
                bool up = sh != null && sh.Running;
                bool settled = up && HostStarted;
                var r = PortGuard.Now(SunshinePort, sh?.Pid ?? -1, up, settled, SunshineLayout.ExeDir);
                Ports = r;
                SetApollo(r.Hosts.Contains("Apollo"));
                var key = (HostProblem ?? "") + "|" + string.Join(",", r.ForeignPorts);
                if (when != null) Log.I("sunshine: ports " + when + ": " + r.Detail);
                if (key == lastPortsKey) return;
                bool first = lastPortsKey == null;
                lastPortsKey = key;
                if (when == null) Log.I("sunshine: ports: " + r.Detail);
                if (HostProblem != null) Log.I("sunshine: " + HostProblem + ": " + r.Line);
                else if (!first) Log.I("sunshine: the streaming ports are clear again");
                // the Thor's status line (an existing message: "PC: <text>")
                if (HostProblem != null) Bridge?.Publish(new Dictionary<string, object> { ["t"] = "status", ["text"] = HostWhy });
                Fire();
            }
            catch (Exception e) { Log.I("sunshine: port check: " + e.Message); }
        }

        // ---------------------------------------------------------------- self-healing (HostHealth, HostHealer)

        public const string ProblemRestarting = "restarting", ProblemNotResponding = "not_responding";
        public const string WhyRestarting = "PC streaming host restarting", WhyNotResponding = "PC streaming host not responding";
        readonly HostHealer healer = new HostHealer();
        readonly object healGate = new object();
        /// <summary>the last whole-set check (null = not yet)</summary>
        public HealthReport Health { get; private set; }
        string lastHealthKey;

        /// <summary>"restarting" while a heal runs on a broken set, "not_responding" once its tries are spent; null = fine</summary>
        public string HealProblem => healer.GaveUp ? ProblemNotResponding : healer.Healing && Health != null && !Health.Healthy ? ProblemRestarting : null;
        /// <summary>the window's lines for it</summary>
        public string HealLine => healer.GaveUp ? "The streaming host isn't answering on port " + string.Join(", ", MissingPorts())
            : "Restarting the streaming host (" + healer.Tries + "/" + HostHealer.MaxTries + ")";
        public string HealHint => healer.GaveUp ? "Restart DS Forever Desktop. If it stays, restart the PC."
            : "Port " + string.Join(", ", MissingPorts()) + " wasn't open.";

        IEnumerable<int> MissingPorts()
        {
            var h = Health;
            return h == null ? new int[0] : h.Missing.Concat(h.Blocked).Concat(h.Excluded).Concat(h.BindErrors.Select(b => b.Port)).Distinct().OrderBy(p => p);
        }

        /// <summary>
        /// Our sunshine.exe on ALL its ports (the user's PC 2026-11-17: RTSP refused at boot, HTTP up, "clear" for ever): HostHealth's
        /// check, then HostHealer's step - a restart while no stream is live, or a new base for reserved / taken ports, then the report.
        /// Never touches another program. [rtsp] = also connect to RTSP; [wait] = wait for a check already running (the Thor's ask).
        /// </summary>
        void HealthTick(bool rtsp, bool wait = false, bool thorAsked = false)
        {
            if (wait) Monitor.Enter(healGate);
            else if (!Monitor.TryEnter(healGate)) return;
            try
            {
                var sh = Sunshine;
                if (sh == null || !sh.Available || SunshinePort <= 0) return;
                // ours exited (SunshineHost's own restart waits up to 60 s): left to it, unless a heal runs (the Thor asked, or one
                // was under way) - then it is a host with every port missing, restarted on the heal's clock and reported
                bool exited = !sh.Running;
                if (exited && !healer.Healing) return;
                bool streaming = !exited && StreamLive(thorAsked);
                var x = HostHealth.ExcludedNow(healer.Healing ? 10 : 300);
                var udp = PortGuard.UdpBound();
                var tcp = PortGuard.TcpListeners();
                var log = exited ? null : HostHealth.ReadLog(SunshineLayout.LogFile);
                var since = sh.StartedUtc.ToLocalTime();
                HealthReport Look(bool? rtspOk) => HostHealth.Check(SunshinePort, exited ? -1 : sh.Pid, SunshineLayout.ExeDir, tcp, udp, rtspOk,
                    x.tcp, x.udp, log, since, PortGuard.Who);
                var h = Look(exited ? false : (bool?)null);
                // ours STARTED (verifier 2026-11-17: Sunshine probes its encoders before it binds, about 26 s on the user's PC; a 4 s
                // settle restarted a healthy host on every start); our screen being made restarts ours too (MakeOurs, the Test's
                // screen check): the heal waits for both, never a restart on top
                bool started = exited || HostHealth.Started(DateTime.UtcNow - sh.StartedUtc, h.Missing.Count == 0, log, since, DateTime.Now);
                if (started && !exited) startedFor = sh.StartedUtc;
                bool settled = !screenBusy && started;
                if (!exited && rtsp && settled && !streaming) h = Look(HostHealth.Connects(SunshinePort + HostHealth.RtspOffset));
                if (exited) h.Detail = "exited, " + h.Detail;
                else if (!started) h.Detail = "starting, " + h.Detail;
                Health = h;
                var key = h.Healthy + "|" + h.Detail;
                if (key != lastHealthKey) { lastHealthKey = key; Log.I("sunshine: health " + (h.Healthy ? "ok" : "PROBLEM") + ": " + h.Detail); }
                var step = healer.Tick(DateTime.UtcNow, h, streaming, settled);
                switch (step)
                {
                    case HealStep.Restart:
                        Log.I("sunshine: heal " + healer.Tries + "/" + HostHealer.MaxTries + ": restarting ours (missing " + string.Join(", ", MissingPorts()) + ")");
                        RestartSunshine();
                        break;
                    case HealStep.Move:
                        if (!MoveBase(h)) RestartSunshine();
                        break;
                    case HealStep.GiveUp:
                        Log.I("sunshine: heal: " + HostHealer.MaxTries + " tries and still missing " + string.Join(", ", MissingPorts()) + ": reported");
                        Bridge?.Publish(HostResult("failed"));
                        break;
                    case HealStep.Healed:
                        Log.I("sunshine: heal: every port is open again (base " + SunshinePort + ")");
                        // the Thor's own ask found it fine: its answer is HostCheck's return, not a second message
                        if (!thorAsked) Bridge?.Publish(HostResult("ok"));
                        break;
                }
                if (step != HealStep.None) Fire();
                CheckPorts(null);
            }
            catch (Exception e) { Log.I("sunshine: health check: " + e.Message); }
            finally { Monitor.Exit(healGate); }
        }

        DateTime busySince = DateTime.MinValue;
        /// <summary>the start of ours that has been seen STARTED (its ports' outcome known: <see cref="HostHealth.Started"/>)</summary>
        DateTime startedFor = DateTime.MinValue;

        /// <summary>ours is running and has started (its ports bound, or their failure logged): until then nothing judges its ports</summary>
        public bool HostStarted
        {
            get
            {
                var sh = Sunshine;
                if (sh == null || !sh.Running) return false;
                return startedFor == sh.StartedUtc || DateTime.UtcNow - sh.StartedUtc >= HostHealth.StartupMax;
            }
        }

        /// <summary>a stream is live: BUSY with a bound session (see <see cref="HostHealth.StreamLive"/>); BUSY alone is an app launched</summary>
        public bool StreamLive(bool thorAsked = false)
        {
            if (Stream != StreamState.Streaming) return false;
            bool? bound = null;
            try
            {
                var sh = Sunshine;
                var rows = PortGuard.UdpBound();
                if (rows.Count > 0) bound = sh != null && sh.Running && HostHealth.SessionBound(SunshinePort, sh.Pid, rows);
            }
            catch (Exception) { }
            return HostHealth.StreamLive(true, bound, DateTime.UtcNow - busySince, thorAsked);
        }

        Dictionary<string, object> HostResult(string state) =>
            new Dictionary<string, object> { ["t"] = "host_check_result", ["state"] = state, ["sunshine"] = SunshineInfo() };

        /// <summary>a new base whose whole set is free, outside Windows' excluded ranges and never the stock block; saved, and the Thor
        /// told (the "sunshine" message; the welcome and mDNS carry it too). false = none found</summary>
        bool MoveBase(HealthReport h)
        {
            var x = HostHealth.ExcludedNow(0);
            int from = SunshinePort;
            int nb = PortPlanner.Pick(0, PortPlanner.TcpFree, PortPlanner.UdpFree, p => x.tcp.Any(r => r.Has(p)), p => x.udp.Any(r => r.Has(p)), avoid: from);
            if (nb < 0) { Log.I("sunshine: heal: no other free port set; restarting on " + from); return false; }
            var why = h.Excluded.Count > 0 ? "excluded by Windows: " + string.Join(", ", h.Excluded)
                : h.Taken.Count + h.Blocked.Count > 0 ? "held by another program: " + string.Join(", ", h.Taken.Concat(h.Blocked))
                : "bind refused (WSAEACCES)";
            Log.I("sunshine: heal " + healer.Tries + "/" + HostHealer.MaxTries + ": moving from base " + from + " to " + nb + " (" + why + ")");
            Sunshine.Stop();
            SunshinePort = nb;
            Settings.SunshinePort = nb;
            SaveSettings();
            var old = api;
            api = new SunshineApi(nb, Settings.SunshineUser, Pass, SunshineLayout.Cert);
            try { old?.Dispose(); } catch (Exception) { }
            WriteSunshineConfig();
            Sunshine.Start();
            displaysRead = null;
            Bridge?.Publish(new Dictionary<string, object> { ["t"] = "sunshine", ["sunshine"] = SunshineInfo() });
            return true;
        }

        /// <summary>
        /// The Thor's "host_check" (its RTSP timed out twice while HTTP answered): the check at once and, when something is missing and
        /// no stream is live, the first heal step now. "ok" = every port is open (the Thor retries), "restarting" = a host_check_result
        /// follows ("ok" / "failed"), "failed" = nothing left to try, "streaming" = a stream is live (nothing touched).
        /// </summary>
        public Dictionary<string, object> HostCheck()
        {
            Log.I("bridge: the Thor asks for a streaming host check");
            if (Sunshine == null || !Sunshine.Available || SunshinePort <= 0) return HostResult("failed");
            // the Thor's RTSP already failed: BUSY alone (an app launched) is no stream; only a bound session is
            if (StreamLive(thorAsked: true)) return HostResult("streaming");
            lock (healGate) healer.Force(DateTime.UtcNow);
            HealthTick(true, wait: true, thorAsked: true);
            var state = healer.GaveUp ? "failed" : healer.Healing ? "restarting" : "ok";
            Log.I("sunshine: host check for the Thor: " + state);
            return HostResult(state);
        }

        // ---------------------------------------------------------------- Test (PcTest; the user's pick 2026-11-17: "Yes, build it")

        readonly object testGate = new object();
        /// <summary>the last run (the window's Test or the Thor's Test connection); null = none since Desktop started</summary>
        public PcTestResult LastTest { get; private set; }

        /// <summary>the Thor's "pc_test": the same checks as the window's Test</summary>
        public Dictionary<string, object> PcTest() => RunPcTest("the Thor").ToMessage();

        /// <summary>
        /// The PC's half of Test connection: our sunshine's ports bound, the RTSP connect, a video encoder in its log, the screen a stream
        /// gets (ours made and removed when none is on and no stream is live) and ViGEmBus. Never touches another program. Written to
        /// desktop.log and last_test.txt (no names, IPs cut) for a problem report.
        /// </summary>
        public PcTestResult RunPcTest(string who)
        {
            lock (testGate)
            {
                Log.I("test: run for " + who);
                // the player's Test: our screen's tries start over (it gave up after ScreenLife.MaxFails)
                Screens.Reset(byHand: true);
                // ours still starting (its encoder probe, about 26 s): a short wait, else the steps would say ports are closed
                for (int i = 0; i < 30 && Sunshine != null && Sunshine.Running && !HostStarted; i++)
                {
                    if (i == 0) Task.Run(() => HealthTick(false));
                    Thread.Sleep(500);
                    if (i % 4 == 3) Task.Run(() => HealthTick(false));
                }
                bool starting = Sunshine != null && Sunshine.Running && !HostStarted;
                var sh = Sunshine;
                bool running = sh != null && sh.Available && sh.Running && SunshinePort > 0;
                bool streaming = StreamLive();
                int rtspPort = SunshinePort + HostHealth.RtspOffset;
                var r = new PcTestResult { At = DateTime.Now, Pad = PadDriver.Now() };
                r.Steps = PcChecks.Run(new (string, string, Func<TestStep>)[]
                {
                    ("ports", "Ports", () =>
                    {
                        HealthReport h = null;
                        if (running)
                        {
                            var x = HostHealth.ExcludedNow(10);
                            h = HostHealth.Check(SunshinePort, sh.Pid, SunshineLayout.ExeDir, PortGuard.TcpListeners(), PortGuard.UdpBound(), null,
                                x.tcp, x.udp, HostHealth.ReadLog(SunshineLayout.LogFile), sh.StartedUtc.ToLocalTime(), PortGuard.Who);
                        }
                        return PcChecks.Ports(h, SunshinePort, running, starting);
                    }),
                    ("rtsp", "RTSP", () => PcChecks.Rtsp(!streaming && HostHealth.Connects(rtspPort), rtspPort, streaming)),
                    ("encoder", "Encoder", () => PcChecks.Encoder(HostHealth.ReadLog(SunshineLayout.LogFile, 1024 * 1024), sh.StartedUtc.ToLocalTime(),
                        DateTime.UtcNow - sh.StartedUtc > TimeSpan.FromSeconds(60))),
                    ("screen", "Screen", () =>
                    {
                        var f = ScreenFactsNow(streaming);
                        var (st, word) = PcChecks.Screen(f);
                        r.Screen = word; r.ScreenLine = st.Line; r.ScreenHz = f.Hz;
                        return st;
                    }),
                    ("controller", "Controller", () => PcChecks.Pad(r.Pad)),
                });
                LastTest = r;
                var lines = r.ReportLines();
                foreach (var l in lines) Log.I(l);
                try { File.WriteAllLines(Path.Combine(Paths.DataDir, "last_test.txt"), lines); } catch (Exception) { }
                return r;
            }
        }

        /// <summary>the screen a stream gets now; ours is made at the Thor's mode and removed again when none is there and nothing streams</summary>
        PcChecks.ScreenFacts ScreenFactsNow(bool streaming)
        {
            var life = Screens;
            var win = Displays.WindowsDisplays();
            var f = new PcChecks.ScreenFacts { OwnOn = Settings.OwnScreen, Real = Displays.HasRealScreen(win), Driver = life.Survey().usable };
            if (life.Exists)
            {
                f.Ours = true;
                f.OursMode = life.Mode;
                f.Hz = Math.Max(PcChecks.MaxHz(life.GdiName), life.Mode.hz);
                return f;
            }
            if (f.OwnOn && f.Driver)
            {
                if (streaming) f.NotTried = "a stream is live";
                else
                {
                    // the screen worker first (it may be making ours now: up to about 45 s), never a second make beside it
                    bool got = false;
                    for (int i = 0; i < 180 && !(got = TakeScreen()); i++) Thread.Sleep(250);
                    if (!got) f.NotTried = "it is being made";
                    else
                    {
                        try
                        {
                            if (life.Exists)
                            {
                                // made by the worker while the Test waited
                                f.Ours = true;
                                f.OursMode = life.Mode;
                                f.Hz = Math.Max(PcChecks.MaxHz(life.GdiName), life.Mode.hz);
                                return f;
                            }
                            var why = life.Make(thorW, thorH, thorFps, DateTime.UtcNow);
                            if (why == null)
                            {
                                f.Hz = Math.Max(PcChecks.MaxHz(life.GdiName), life.Mode.hz);
                                var drv = life.Current?.Name;
                                life.Remove("test");
                                f.Made = "";
                                Log.I("test: our virtual screen made and removed (" + drv + ")");
                            }
                            else f.Made = why;
                        }
                        catch (Exception e) { f.Made = e.Message; }
                        finally { FreeScreen(); }
                        if (f.Made == "") return f;
                    }
                }
            }
            var real = win.FirstOrDefault(d => d.Active && d.Primary && !d.Virtual) ?? win.FirstOrDefault(d => d.Active && !d.Virtual);
            if (real != null) f.Hz = PcChecks.MaxHz(real.Name);
            return f;
        }

        // no prep command: the portable Sunshine can't start ours (error 5, tracking 252f5ec7); the Thor's bridge "focus" covers it
        void WriteSunshineConfig() => SunshineConfig.Write(SunshineLayout, new SunshineOptions
        {
            Port = SunshinePort, Encoder = Settings.Encoder, EventExe = "", HostName = Environment.MachineName,
            BindAddress = Loopback ? "127.0.0.1" : "", OutputName = CurrentOutput, MatchStream = MatchStreamNow,
        });

        /// <summary>the display id our Sunshine captures: our own virtual screen's for the session, else the remembered real one</summary>
        string CurrentOutput => sessionOutput ?? Settings.CaptureDevice;

        /// <summary>"Match PC screen to stream" as written: off while Apollo runs (both change the display setup and revert each other)</summary>
        bool MatchStreamNow => Settings.MatchStreamResolution && !apolloRunning;

        volatile bool apolloRunning;
        /// <summary>the config changed while a stream ran: written and restarted once the stream ends</summary>
        volatile bool configStale;

        void SetApollo(bool on)
        {
            if (on == apolloRunning) return;
            apolloRunning = on;
            if (!Settings.MatchStreamResolution) { Log.I("apollo: " + (on ? "running" : "gone")); return; }
            Log.I(on ? "match screen: off while Apollo runs (both change the display setup)" : "match screen: back on (Apollo gone)");
            ApplyConfig("match screen");
        }

        /// <summary>writes the config and restarts ours now when idle, else after the stream</summary>
        void ApplyConfig(string why)
        {
            if (Sunshine == null || !Sunshine.Running) { if (SunshinePort > 0) WriteSunshineConfig(); return; }
            if (StreamLive()) { configStale = true; Log.I("sunshine: " + why + ": applied after the stream"); return; }
            configStale = false;
            WriteSunshineConfig();
            RestartSunshine();
        }

        public void SetMatchStream(bool on)
        {
            Settings.MatchStreamResolution = on;
            SaveSettings();
            WriteSunshineConfig();
            RestartSunshine();
        }

        public void RestartSunshine()
        {
            Sunshine?.Stop();
            Sunshine?.Start();
            displaysRead = null;
        }

        // ---------------------------------------------------------------- the screen our Sunshine streams (F3 / F6 / Part 2)

        /// <summary>the PC's line on the streamed screen ("No screen: making one", ...); "" = a screen is there</summary>
        public string ScreenLine { get; private set; } = "";
        /// <summary>the welcome's problem word for the screen (needs_screen / no_screen), null = fine</summary>
        public string ScreenProblem { get; private set; }
        /// <summary>the window's line on a headless PC while no Thor is connected</summary>
        public const string NoScreenLine = "No screen is on at this PC";
        ScreenLife screens;
        string sessionOutput;
        /// <summary>the monitor WoW was on before it moved to our screen: it goes back there after</summary>
        volatile string wowHomeNow;
        /// <summary>saved too (verifier 2026-11-17: only in memory, so after a crash or a close WoW never went back)</summary>
        string wowHome
        {
            get => wowHomeNow;
            set
            {
                wowHomeNow = value;
                if (Settings.WowHome == value) return;
                Settings.WowHome = value;
                try { SaveSettings(); } catch (Exception) { }
            }
        }
        volatile bool displaysDirty = true;
        string topoKey;
        List<WinDisplay> lastWin = new List<WinDisplay>();
        DateTime lastDisplayRestart = DateTime.MinValue, driversAt = DateTime.MinValue;
        bool driverInstalled, driverUsable, driversRead, displayCheckPending = true, lookedLast;
        /// <summary>the screen worker (ScreenTick, MakeOurs, the Test's make, the start's cleanup) runs: one at a time (reviewers
        /// 2026-11-17: ScreenFactsNow and ScreenTick read and set a plain bool, so both could make ours at once)</summary>
        int screenBusyN;
        bool screenBusy => Volatile.Read(ref screenBusyN) != 0;
        bool TakeScreen() => Interlocked.CompareExchange(ref screenBusyN, 1, 0) == 0;
        void FreeScreen() => Volatile.Write(ref screenBusyN, 0);
        int thorW = 1920, thorH = 1080, thorFps = 60;
        /// <summary>when the Thor's bridge connected, and whether its stream_mode came since: ours waits for the mode (else it is
        /// made at 1080p60 and made again at 120, two Sunshine restarts each)</summary>
        DateTime thorSince = DateTime.MinValue;
        volatile bool modeHeard;
        bool thorWas;

        /// <summary>the drivers for our own screen, in the order they are tried: SudoVDA (no admin), then the Virtual Display Driver</summary>
        ScreenLife Screens
        {
            get
            {
                if (screens != null) return screens;
                if (string.IsNullOrEmpty(Settings.VirtualScreenId) || !Guid.TryParse(Settings.VirtualScreenId, out _))
                {
                    Settings.VirtualScreenId = Guid.NewGuid().ToString("D");
                    SaveSettings();
                }
                var sudo = new OurScreen(Guid.Parse(Settings.VirtualScreenId));
                var vdd = new VddScreen(() => Settings.VddAttached,
                                        on => { if (Settings.VddAttached != on) { Settings.VddAttached = on; if (!on) Settings.VddInstalledByUs = false; SaveSettings(); } },
                                        () => Settings.VddDevice,
                                        dev => { if (Settings.VddDevice != dev) { Settings.VddDevice = dev; SaveSettings(); } });
                return screens = new ScreenLife(new List<IScreenDriver> { sudo, vdd });
            }
        }

        void OnDisplaysChanged(object sender, EventArgs e) { displaysDirty = true; }

        /// <summary>the Thor's stream mode (its "stream_mode" message): our virtual screen is made at it</summary>
        public void StreamMode(int w, int h, int fps)
        {
            var m = ScreenPlan.Mode(w, h, fps);
            modeHeard = true;
            if (m == (thorW, thorH, thorFps)) return;
            (thorW, thorH, thorFps) = m;
            Log.I("thor stream mode: " + thorW + "x" + thorH + "@" + thorFps);
        }

        /// <summary>SudoVDA's adapter is in Windows but disabled (SudoVda.Disabled, surveyed with the drivers)</summary>
        bool sudoVdaDisabled;
        /// <summary>a screen is on that is not ours (the last display tick)</summary>
        volatile bool hasScreenNow = true;

        /// <summary>
        /// The window's virtual-screen section (quiet by default, the user's ruling 2026-11-18): Hidden unless "Stream on its own screen"
        /// is on or no screen is on. Never a pop-up: the 0.2.32-0.2.36 install question (a modal box, asked once) is gone.
        /// </summary>
        public ScreenSetup.Panel SetupPanel => ScreenSetup.For(Settings.OwnScreen, hasScreenNow, driversRead, driverInstalled, driverUsable,
                                                               sudoVdaDisabled, Installing);
        /// <summary>no screen is on at this PC (the last display tick)</summary>
        public bool Headless => !hasScreenNow;
        /// <summary>the player's install (VddInstall) is running</summary>
        public bool Installing { get { lock (vddWatchGate) return vddWatchAt != DateTime.MinValue; } }
        /// <summary>the section's last result (Turn on, the test): "" = none</summary>
        public string SetupResult { get; private set; } = "";
        /// <summary>Turn on or the test is running</summary>
        public bool SetupBusy => Volatile.Read(ref setupBusyN) != 0;
        int setupBusyN;
        /// <summary>Windows' admin prompt and the adapter's id for Turn on (the tests pass fakes: never pnputil on a test PC)</summary>
        public IElevatedRunner Elevated = new WindowsElevatedRunner();
        public Func<string> SudoVdaId = SudoVda.AdapterInstanceId;

        /// <summary>the window's Turn on (the player's click): pnputil /enable-device through Windows' admin prompt; null = turned on</summary>
        public string TurnOnSudoVda()
        {
            if (Interlocked.CompareExchange(ref setupBusyN, 1, 0) != 0) return "busy";
            try
            {
                var why = SudoVdaTurnOn.Run(SudoVdaId, Elevated);
                SetupResult = why == null ? "SudoMaker display adapter turned on"
                    : why == SudoVdaTurnOn.Restart ? "SudoMaker display adapter " + why : "Not turned on: " + why;
                driversAt = DateTime.MinValue;   // looked at again at the next tick
                displaysDirty = true;
                Fire();
                return why == SudoVdaTurnOn.Restart ? null : why;
            }
            finally { Volatile.Write(ref setupBusyN, 0); }
        }

        /// <summary>the headless check's test (the player's click): our screen made at the Thor's mode and removed once; null = passed</summary>
        public string TestOwnScreen()
        {
            if (Interlocked.CompareExchange(ref setupBusyN, 1, 0) != 0) return "busy";
            try
            {
                string why;
                if (StreamLive()) why = "a stream is live";
                else
                {
                    bool got = false;
                    for (int i = 0; i < 180 && !(got = TakeScreen()); i++) Thread.Sleep(250);
                    if (!got) why = "the screen is being made";
                    else
                    {
                        try
                        {
                            var life = Screens;
                            // the player's click: the tries start over (as Test connection's), so one failed test never counts
                            // towards a Thor session's give-up
                            life.Reset(byHand: true);
                            if (life.Exists) why = null;   // ours is up already: it works
                            else
                            {
                                why = life.Make(thorW, thorH, thorFps, DateTime.UtcNow);
                                if (why == null)
                                {
                                    var drv = life.Current?.Name;
                                    life.Remove("the window's test");
                                    Log.I("virtual screen: the window's test made and removed ours (" + drv + ")");
                                }
                            }
                        }
                        catch (Exception e) { why = e.Message; }
                        finally { FreeScreen(); }
                    }
                }
                SetupResult = ScreenSetup.TestLine(why);
                Log.I("virtual screen: " + SetupResult);
                Fire();
                return why;
            }
            finally { Volatile.Write(ref setupBusyN, 0); }
        }

        /// <summary>the player's Install in the window: the driver's own installer (Windows asks for admin); null = started. Its monitor is ours.</summary>
        public string InstallVirtualScreen()
        {
            if (Installing) return null;
            Log.I("virtual screen: install (the player's click in the window)");
            // nothing is ours yet: the monitor the install brings is named when it appears (VddInstallWatch), so a declined admin
            // prompt leaves nothing marked ours
            var before = Displays.WindowsDisplays().Where(VddScreen.IsVdd).Select(d => d.Name).ToList();
            var err = VddInstall.Start(out var p);
            if (err != null) return err;
            lock (vddWatchGate) { vddWatchAt = DateTime.UtcNow; vddWatchBefore = before; try { vddWatchProc?.Dispose(); } catch (Exception) { } vddWatchProc = p; }
            driversAt = DateTime.MinValue;
            return null;
        }

        readonly object vddWatchGate = new object();
        DateTime vddWatchAt = DateTime.MinValue;
        List<string> vddWatchBefore = new List<string>();
        System.Diagnostics.Process vddWatchProc;

        /// <summary>each display tick while our install runs: its new monitor is ours (on by itself; detached once no Thor wants it)</summary>
        void VddWatchTick(List<WinDisplay> win)
        {
            lock (vddWatchGate)
            {
                if (vddWatchAt == DateTime.MinValue) return;
                int? exit = null;
                try { if (vddWatchProc != null && vddWatchProc.HasExited) exit = vddWatchProc.ExitCode; } catch (Exception) { }
                var now = DateTime.UtcNow;
                var name = VddInstallWatch.Adopt(vddWatchAt, now, exit, vddWatchBefore, win);
                if (name == null && !VddInstallWatch.Over(vddWatchAt, now, exit)) return;
                if (name != null)
                {
                    Settings.VddInstalledByUs = true;
                    Settings.VddAttached = true;
                    Settings.VddDevice = name;
                    SaveSettings();
                    driversAt = DateTime.MinValue;
                    Log.I("virtual screen: our install's Virtual Display Driver monitor " + name + " is ours");
                }
                else Log.I("virtual screen: the install " + (exit.HasValue ? "ended (" + exit.Value + ")" : "window passed") + " with no new monitor: nothing is ours");
                vddWatchAt = DateTime.MinValue;
                try { vddWatchProc?.Dispose(); } catch (Exception) { }
                vddWatchProc = null;
            }
        }

        /// <summary>"Stream on its own screen" On/Off (off by default, 0.2.37): the next tick makes or removes ours; on = drivers are
        /// looked for and the window's setup shows. The player's own pick: kept across updates (AppSettings.OwnScreenChosen)</summary>
        public void SetOwnScreen(bool on)
        {
            if (Settings.OwnScreen == on && Settings.OwnScreenChosen) return;
            Settings.OwnScreen = on;
            Settings.OwnScreenChosen = true;
            SaveSettings();
            if (on) driversAt = DateTime.MinValue;
            Log.I("own screen: " + (on ? "on" : "off"));
            displaysDirty = true;
        }

        void Say(string line, string problem)
        {
            if (line == ScreenLine && problem == ScreenProblem) return;
            ScreenLine = line;
            ScreenProblem = problem;
            Log.I("screen: " + (line.Length > 0 ? line : "fine") + (problem != null ? " (" + problem + ")" : ""));
            // quiet by default (0.2.37): only a screen being made goes to the Thor's status line; a driver's absence is the window's
            // (headless: the welcome's / "sunshine"'s problem word, the Thor's one line)
            var thor = ScreenPlan.ThorStatus(line);
            if (thor != null) Bridge?.Publish(new Dictionary<string, object> { ["t"] = "status", ["text"] = thor });
            lastPortsKey = null;   // the host problem line follows
            Fire();
        }

        /// <summary>every 2 s: Windows' display list, what changed, and the screen plan</summary>
        void DisplayTick()
        {
            var win = Displays.WindowsDisplays();
            if (win.Count == 0) return;
            lastWin = win;
            var key = Displays.TopologyKey(win);
            if (key != topoKey || displaysDirty)
            {
                if (key != topoKey) Log.I("displays: " + string.Join("; ", win.Where(d => d.Active).Select(d => d.ToString())));
                topoKey = key;
                displaysDirty = false;
                displaysRead = null;
                displayCheckPending = true;
            }
            bool streaming = StreamLive();
            if (configStale && !streaming) ApplyConfig("config");
            VddWatchTick(win);
            ScreenTick(win, streaming);
            WaitTick();
            // a remembered display that has gone, or a capture target that went away: once the change has settled
            var sh = Sunshine;
            if (!displayCheckPending || sh == null || !sh.Running || DateTime.UtcNow - sh.StartedUtc < TimeSpan.FromSeconds(6) || screenBusy) return;
            var list = SunshineDisplays();
            var act = Displays.OnChange(list, win, Settings.CaptureDevice, CurrentOutput, streaming);
            if (act == Displays.Change.None) { displayCheckPending = false; return; }
            if (DateTime.UtcNow - lastDisplayRestart < TimeSpan.FromSeconds(30)) return;
            lastDisplayRestart = DateTime.UtcNow;
            displayCheckPending = false;
            if (act == Displays.Change.ClearAndRestart)
            {
                Log.I("capture: the remembered display " + Settings.CaptureDevice + " is gone or virtual: back to the primary (restart)");
                Settings.CaptureDevice = null;
                SaveSettings();
            }
            else Log.I("capture: Sunshine's display is gone or a screen came back: restart to re-read the displays");
            WriteSunshineConfig();
            RestartSunshine();
        }

        void ScreenTick(List<WinDisplay> win, bool streaming)
        {
            if (!TakeScreen()) return;
            bool handed = false;
            try { handed = ScreenStep(win, streaming); }
            finally { if (!handed) FreeScreen(); }
        }

        /// <summary>ScreenTick's step, the worker taken; true = handed to MakeOurs (it frees the worker)</summary>
        bool ScreenStep(List<WinDisplay> win, bool streaming)
        {
            var life = Screens;
            var now = DateTime.UtcNow;
            // a WoW home left from before (a crash, or a close mid-move) with no screen of ours now: WoW goes back there
            if (wowHomeNow != null && sessionOutput == null && !life.Exists)
            {
                var home = wowHome;
                wowHome = null;
                Task.Run(() => WowHome(home));
            }
            bool thor = (Bridge?.Sessions().Count ?? 0) > 0;
            // the mode is forgotten when the Thor goes, not when this tick first sees it: its stream_mode follows the welcome at
            // once and usually lands before this tick (verifier 2026-11-17: reset here, every make waited the full 5 s)
            // a Thor (re)connected: our screen's tries start over (it gave up after ScreenLife.MaxFails)
            if (thor && !thorWas) { thorSince = now; life.Reset(byHand: false); }
            if (!thor && thorWas) modeHeard = false;
            thorWas = thor;
            bool thorWants = life.ThorWants(now, thor, streaming);
            // an old screen WoW hadn't left yet (RetireOldScreen): tried again, never in a loop (OldScreen.MaxTries)
            if (now >= retireAt) { retireAt = DateTime.MaxValue; Task.Run(() => RetireOldScreen()); }
            bool hasScreen = Displays.HasScreen(win, life.GdiName);
            if (hasScreen != hasScreenNow) { hasScreenNow = hasScreen; Fire(); }
            // quiet by default (the user's ruling 2026-11-18): a driver is looked for only with "Stream on its own screen" on or no
            // screen on; a PC with a monitor and the setting off never hears about drivers
            bool looks = ScreenPlan.Looks(Settings.OwnScreen, hasScreen);
            if (looks && !lookedLast) driversAt = DateTime.MinValue;
            lookedLast = looks;
            if (looks && now - driversAt > TimeSpan.FromSeconds(30))
            {
                driversAt = now;
                (driverInstalled, driverUsable) = life.Survey();
                // Apollo's SudoVDA disabled in Windows reads as "not installed" (no active interface): it is there, so nothing is
                // offered and the window's Turn on is the fix (read here; turned on only by the player's click)
                bool was = sudoVdaDisabled;
                sudoVdaDisabled = !driverUsable && SudoVda.Disabled();
                if (sudoVdaDisabled != was) Log.I("virtual screen: SudoVDA adapter " + (sudoVdaDisabled ? "found disabled in Windows" : "no longer disabled"));
                if (!driversRead) { driversRead = true; Fire(); }
            }
            // no question is asked any more (0.2.37: the window's setup offers the install), so never OfferInstall
            var (step, why) = life.Decide(now, Settings.OwnScreen, hasScreen, looks && driverUsable, thorWants, offered: true, (thorW, thorH, thorFps), streaming,
                                          sunshineOnOurs: sessionOutput != null, thorHere: thor || streaming);
            switch (step)
            {
                case ScreenPlan.Step.None:
                    // no screen and no Thor: the window says so; nothing goes to a Thor
                    if (!hasScreen) { if (ScreenProblem != null || ScreenLine != NoScreenLine) { ScreenProblem = null; ScreenLine = NoScreenLine; Fire(); } }
                    else Say("", null);
                    break;
                case ScreenPlan.Step.Keep:
                case ScreenPlan.Step.Wait:
                    break;
                case ScreenPlan.Step.RemoveOurs:
                    RemoveOurs(why);
                    break;
                case ScreenPlan.Step.MakeOurs:
                    if (thor && !modeHeard && !streaming && now - thorSince < TimeSpan.FromSeconds(5)) break;   // its stream_mode follows the welcome
                    if (why != null) Log.I("virtual screen: " + why);
                    Say(hasScreen ? ScreenPlan.MakingOwn : ScreenPlan.Making, null);
                    Task.Run(() => MakeOurs(hasScreen));
                    return true;
                case ScreenPlan.Step.GaveUp:
                    // the user's rule: never a loop. A real screen is streamed; headless, the PC says it needs a virtual screen (the
                    // Thor's Retry or a Test start the tries over)
                    if (hasScreen) { if (ScreenLine != ScreenPlan.FallbackFailed) Say(ScreenPlan.FallbackFailed, null); }
                    else Say(ScreenPlan.NeedsVirtual, ScreenPlan.ProblemNeedsScreen);
                    break;
                case ScreenPlan.Step.NeedsInstall:
                    { var (l, pr) = ScreenPlan.NoDriverLine(false, sudoVdaDisabled); Say(l, pr); }
                    break;
                case ScreenPlan.Step.Fallback:
                    // today's behaviour: Sunshine captures a real screen; one line says why (a disabled SudoVDA: how to enable it)
                    if (ScreenLine != ScreenPlan.FallbackFailed)
                    {
                        if (driverUsable) Say(ScreenPlan.FallbackFailed, null);
                        else { var (l, pr) = ScreenPlan.NoDriverLine(true, sudoVdaDisabled); Say(l, pr); }
                    }
                    break;
            }
            return false;
        }

        void MakeOurs(bool hasScreen)
        {
            var life = Screens;
            try
            {
                var why = life.Make(thorW, thorH, thorFps, DateTime.UtcNow);
                if (why != null)
                {
                    Log.I("virtual screen: couldn't make one: " + why);
                    // a mode change the driver refused: ours is still there at its old mode, and still streamed
                    if (life.Exists && sessionOutput != null) { Say(ScreenPlan.Ready, null); return; }
                    if (hasScreen) Say(ScreenPlan.FallbackFailed, null);
                    else Say(ScreenPlan.Failed, ScreenPlan.ProblemNoScreen);
                    return;
                }
                var drv = life.Current;
                var gdi = life.GdiName;
                // a stream that started meanwhile on a real screen: a restart would cut it; ours waits for the next one
                if (hasScreen && StreamLive())
                {
                    Log.I("virtual screen: a stream started first: it stays on the real screen");
                    life.Remove("a stream started first");
                    Say(ScreenPlan.FallbackFailed, null);
                    return;
                }
                // our Sunshine lists displays at its start. The id our screen had last time: written now, one restart, then checked;
                // else (or it changed): restart, find ours in its list, point output_name at it, restart again
                SunshineDisplay Mine() { displaysRead = null; return SunshineDisplays().FirstOrDefault(d => d.Active && string.Equals(d.DisplayName, gdi, StringComparison.OrdinalIgnoreCase)); }
                bool known = Settings.OwnScreenDriver == drv.Name && !string.IsNullOrEmpty(Settings.OwnScreenDevice);
                sessionOutput = known ? Settings.OwnScreenDevice : null;
                WriteSunshineConfig();
                RestartSunshine();
                SunshineDisplay mine = null;
                for (int i = 0; i < 30 && mine == null; i++) { Thread.Sleep(500); mine = Mine(); }
                bool oneRestart = known && mine?.DeviceId != null && string.Equals(mine.DeviceId, sessionOutput, StringComparison.OrdinalIgnoreCase);
                if (mine?.DeviceId != null && !oneRestart)
                {
                    sessionOutput = mine.DeviceId;
                    Settings.OwnScreenDriver = drv.Name;
                    Settings.OwnScreenDevice = mine.DeviceId;
                    SaveSettings();
                    WriteSunshineConfig();
                    RestartSunshine();
                }
                if (mine?.DeviceId != null) Log.I("virtual screen: our Sunshine captures " + gdi + " (" + mine.DeviceId + ", " + drv.Name + (oneRestart ? ", one restart" : "") + ")");
                else
                {
                    Log.I("virtual screen: our Sunshine didn't list " + gdi + "; it captures " + (hasScreen ? "the real screen" : "its primary"));
                    bool hadOutput = sessionOutput != null;
                    sessionOutput = null;
                    if (hasScreen)
                    {
                        life.Remove("Sunshine didn't list it");
                        life.Failed(DateTime.UtcNow);      // the 2-minute pause, not a make/remove loop
                        if (hadOutput) { WriteSunshineConfig(); RestartSunshine(); }
                        Say(ScreenPlan.FallbackFailed, null);
                        return;
                    }
                    if (hadOutput) { WriteSunshineConfig(); RestartSunshine(); }
                    life.Failed(DateTime.UtcNow);          // kept (headless: the only screen), pointed at again in 2 minutes, not every tick
                    lastDisplayRestart = DateTime.UtcNow;
                    // verifier 2026-11-17: after the last try this still said Ready. Ours isn't captured: the PC needs a virtual screen
                    // (the Thor's Retry or a Test start the tries over). No old screen is retired: Sunshine isn't on the new one
                    if (life.GaveUp) Say(ScreenPlan.NeedsVirtual, ScreenPlan.ProblemNeedsScreen);
                    else Say(ScreenPlan.Ready, null);
                    Task.Run(() => { Thread.Sleep(3000); CheckCapture(); });
                    return;
                }
                lastDisplayRestart = DateTime.UtcNow;
                Say(ScreenPlan.Ready, null);
                retireTries = 0;
                Task.Run(() => { Thread.Sleep(3000); CheckCapture(); RetireOldScreen(); });
            }
            catch (Exception e) { Log.I("virtual screen: " + e.Message); Say(hasScreen ? ScreenPlan.FallbackFailed : ScreenPlan.Failed, hasScreen ? null : ScreenPlan.ProblemNoScreen); }
            finally { FreeScreen(); }
        }

        void RemoveOurs(string why)
        {
            // Windows refused (a VDD detach): still ours and on - paused, then left on after ScreenLife.MaxRemoveFails
            if (!Screens.Remove(why)) return;
            bool had = sessionOutput != null;
            sessionOutput = null;
            Say("", null);
            if (had) ApplyConfig("our virtual screen went");
            // WoW back where the player had it (Windows put it on a remaining screen when ours went)
            var home = wowHome;
            wowHome = null;
            if (home != null) Task.Run(() => WowHome(home));
        }

        /// <summary>
        /// A new mode on a driver that can't change one in place (SudoVDA) is a new screen beside the old one (reviewers 2026-11-17:
        /// remove-then-make moved WoW to the player's screen): WoW to the new one first (CheckCapture), then the old one goes.
        /// </summary>
        void RetireOldScreen()
        {
            if (Interlocked.CompareExchange(ref retiringN, 1, 0) != 0) return;
            try
            {
                var old = Screens.OldGdiName;
                if (old == null) return;
                var next = OldScreen.Next.Later;
                for (int i = 0; i < 20; i++)
                {
                    bool running; string on;
                    using (var p = WowWindow.Find(FlavorDirOrNull)) { running = p != null; on = p == null ? null : Displays.MonitorOf(p.MainWindowHandle); }
                    next = OldScreen.Decide(running, on, old, retireTries);
                    if (next == OldScreen.Next.Retire) break;
                    Thread.Sleep(1000);
                    CheckCapture();
                }
                switch (next)
                {
                    case OldScreen.Next.Retire:
                        retireTries = 0;
                        Screens.RetireOld("WoW is on the new one");
                        break;
                    case OldScreen.Next.Later:
                        retireTries++;
                        retireAt = DateTime.UtcNow + OldScreen.Again;
                        Log.I("virtual screen: WoW is still on the old one (" + old + "): kept, tried again in " + (int)OldScreen.Again.TotalSeconds + " s (" + retireTries + "/" + OldScreen.MaxTries + ")");
                        break;
                    default:
                        retireAt = DateTime.MaxValue;
                        Log.I("virtual screen: WoW stayed on the old one (" + old + ") after " + OldScreen.MaxTries + " tries: it stays on until ours goes");
                        break;
                }
            }
            catch (Exception e) { Log.I("virtual screen: retire the old one: " + e.Message); }
            finally { Volatile.Write(ref retiringN, 0); }
        }

        /// <summary>the old-screen retire: one at a time; its next try (MaxValue = none); tries for this old screen</summary>
        int retiringN;
        DateTime retireAt = DateTime.MaxValue;
        int retireTries;

        void WowHome(string home)
        {
            try
            {
                Thread.Sleep(1500);
                using (var p = WowWindow.Find(FlavorDirOrNull))
                {
                    if (p == null || Displays.MonitorRect(home) == null) return;
                    if (string.Equals(Displays.MonitorOf(p.MainWindowHandle), home, StringComparison.OrdinalIgnoreCase)) return;
                    Log.I("capture: WoW back to " + home + ": " + (Displays.MoveTo(p.MainWindowHandle, home) ?? "done"));
                }
            }
            catch (Exception e) { Log.I("capture: WoW back: " + e.Message); }
        }

        public void StreamPair(string deviceId, string name, string pin, string peerIp, Action<string> done)
        {
            Task.Run(async () =>
            {
                string err;
                long logAt = SunshineDiag.LogLength(SunshineLayout.LogFile);
                try
                {
                    if (api == null || Sunshine == null || !Sunshine.Running) err = "the streaming host isn't running";
                    else err = await api.CompletePairing(pin, SunshineClientName(deviceId, name), peerIp, TimeSpan.FromSeconds(Bridge?.StreamPairSeconds ?? 90), CancellationToken.None).ConfigureAwait(false);
                }
                catch (Exception e) { err = e.Message; }
                Log.I("stream pairing for " + name + ": " + (err ?? "done"));
                done(err);
                // issue #219 diagnostics: the device's TLS check (its last step) comes after the PIN was taken; a few seconds on, what
                // Sunshine saved and said
                try
                {
                    await Task.Delay(6000).ConfigureAwait(false);
                    Log.I("stream pairing for " + name + ": sunshine state: " + SunshineDiag.StateSummaryOfFile(SunshineLayout.State));
                    if (api != null)
                    {
                        try { Log.I("stream pairing for " + name + ": sunshine lists " + (await api.Clients(CancellationToken.None).ConfigureAwait(false)).Count + " client(s)"); }
                        catch (Exception e) { Log.I("stream pairing: sunshine client list: " + e.Message); }
                    }
                    foreach (var l in SunshineDiag.PairLines(SunshineDiag.LogSince(SunshineLayout.LogFile, logAt))) Log.I("sunshine log: " + l);
                    foreach (var o in SunshineDiag.OtherSunshines(Sunshine?.Pid ?? -1)) Log.I("sunshine: another sunshine.exe runs beside ours: " + o);
                }
                catch (Exception e) { Log.I("stream pairing diagnostics: " + e.Message); }
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

        // ---------------------------------------------------------------- Desktop's own update (DesktopUpdate, UpdateGate)

        DsfDesktop.Thor.DesktopUpdate update;
        DateTime nextUpdateCheck = DateTime.UtcNow.AddSeconds(DsfDesktop.Thor.DesktopUpdate.FirstCheckS);
        DateTime quietSince = DateTime.UtcNow, lastAskedCheck = DateTime.MinValue;
        DateTime? goSince;
        string appWritable = "";   // "" = not looked yet, null = writable
        string updateNote = "";
        (bool ok, string version, DateTime at, string why)? lastResult;

        /// <summary>the window's update line: "up to date", "0.2.29 ready: after the stream", "Updating to 0.2.29", ...</summary>
        public string UpdateLine { get; private set; } = "";
        /// <summary>a line for the tray balloon once (the last update went in / failed); null after it is read</summary>
        public string TakeUpdateNote() { var n = updateNote; updateNote = null; return string.IsNullOrEmpty(n) ? null : n; }

        DsfDesktop.Thor.DesktopUpdate Updater()
        {
            if (update != null) return update;
            update = new DsfDesktop.Thor.DesktopUpdate(Paths.DataDir);
            var last = lastResult = update.LastResult();
            if (last.HasValue && (DateTime.UtcNow - last.Value.at).TotalMinutes < 30)
            {
                var r = last.Value;
                updateNote = r.ok ? (DsfDesktop.Thor.Versions.Compare(r.version, Version) == 0 ? "Updated to " + Version : "") : "Update to " + r.version + " failed: " + r.why;
            }
            update.CleanUp(Paths.AppDir);
            return update;
        }

        /// <summary>ask for a check now (a Thor connected, or it runs a newer add-on set); [force] skips the 10-minute spacing</summary>
        public void RequestUpdateCheck(string why, bool force = false)
        {
            if (DsfDesktop.Thor.DesktopUpdate.Disabled) return;
            if (!force && DateTime.UtcNow - lastAskedCheck < TimeSpan.FromMinutes(10)) return;
            if (force && DateTime.UtcNow - lastAskedCheck < TimeSpan.FromMinutes(2)) return;
            lastAskedCheck = DateTime.UtcNow;
            Log.I("update: checking now (" + why + ")");
            nextUpdateCheck = DateTime.UtcNow;
        }

        void OnThorAddonSet(int thorSet)
        {
            var mine = Addons?.AppSet;
            if (mine.HasValue && thorSet > mine.Value) RequestUpdateCheck("the Thor runs add-on set " + thorSet + ", this Desktop bundles " + mine.Value, true);
        }

        bool WowFromDesktop()
        {
            if (Starter.State == WowState.Starting) return true;
            bool thor = Bridge != null && Bridge.Sessions().Count > 0;
            return (thor || thorWasHere) && WowUp();
        }

        void UpdateTick()
        {
            if (DsfDesktop.Thor.DesktopUpdate.Disabled) { UpdateLine = "updates off"; return; }
            var u = Updater();
            if (appWritable == "") appWritable = DsfDesktop.Thor.SwapPlan.CanWrite(Paths.AppDir);
            if (DateTime.UtcNow >= nextUpdateCheck)
            {
                nextUpdateCheck = DateTime.UtcNow.AddSeconds(DsfDesktop.Thor.DesktopUpdate.CheckEveryS);
                Task.Run(() => u.Check(Version, CancellationToken.None));
            }
            bool streaming = Stream == StreamState.Streaming, wow = false;
            try { wow = WowFromDesktop(); } catch (Exception) { }
            if (streaming || wow) quietSince = DateTime.UtcNow;
            var last = lastResult;
            var gate = new DsfDesktop.Thor.GateInput
            {
                ReadyVersion = u.ReadyVersion, Writable = appWritable, Streaming = streaming, WowFromDesktop = wow,
                IdleForS = (DateTime.UtcNow - quietSince).TotalSeconds,
                FailedVersion = last.HasValue && !last.Value.ok ? last.Value.version : null,
                FailedAgoS = last.HasValue ? (DateTime.UtcNow - last.Value.at).TotalSeconds : double.MaxValue,
            };
            var d = DsfDesktop.Thor.UpdateGate.Decide(gate);
            goSince = d.go ? goSince ?? DateTime.UtcNow : (DateTime?)null;
            if (d.line.Length > 0) UpdateLine = d.line;
            else if (u.Status.Length > 0) UpdateLine = u.Status;
            else if (appWritable != null) UpdateLine = "can't update in this folder: unzip Desktop to Documents";
            else UpdateLine = u.LastCheck.HasValue ? "up to date" : "";
        }

        /// <summary>a verified new Desktop and the PC idle for a minute, shown as "Updating to x" for 3 s: the helper is started and the
        /// app must quit (the tray asks every 3 s, on the UI thread)</summary>
        public bool StartPendingUpdate()
        {
            if (update == null || !update.Ready || !goSince.HasValue || DateTime.UtcNow - goSince.Value < TimeSpan.FromSeconds(3)) return false;
            if (update.Begin(Paths.AppDir)) return true;
            goSince = null;
            lastResult = update.LastResult();
            return false;
        }

        DateTime? loginFileAt, signInSeenAt;
        /// <summary>when the game's log state last began from the file's start or an emptied file (LogBegan: the first read, the file
        /// back after it was missing, a client restart, a path or flavor switch; local time); null while nothing of the file is read</summary>
        DateTime? loginLogAt;
        bool loginFileSwept;
        /// <summary>the last login.txt went unread (its 5 minutes ran out with no client: the player was signing in to Battle.net
        /// first): the next start asks the Thor for the login again, as after a closed run</summary>
        bool loginFileUnread;
        /// <summary>the client run the login.txt was written for: the first Wow.exe seen after the write (WowFlow.LoginRunOver); kept
        /// after the file went, until the next start asked the Thor again, the run signed in, or the Thor left</summary>
        int? loginRunPid;
        /// <summary>the login.txt run reached character select or the world (WowFlow.LoginRunSettled): no run to ask again for</summary>
        bool loginRunSettled;
        /// <summary>the Thor's remembered game account (WowAccount; never logged), for the PC's Config.wtf before WoW starts; null = none</summary>
        string gameAccountList;
        readonly object loginGate = new object();

        public void LoginFile(string email, string password, string accountList)
        {
            lock (loginGate)
            {
                // the game account first: a login that is not wanted now still tells which game account the Thor remembers
                gameAccountList = WowAccount.Valid(accountList) ? accountList : null;
                var dir = FlavorDirOrNull;
                if (dir == null) return;
                var s = ScreenNow();
                if (s == WowScreen.None && gameAccountList != null) RememberGameAccount(dir);
                if (string.IsNullOrEmpty(email) || string.IsNullOrEmpty(password) || !WowFlow.LoginFileWanted(s)) return;
                var why = WowLoginFile.Write(dir, email, password);
                if (why == null) { loginFileAt = DateTime.UtcNow; signInSeenAt = null; loginRunPid = null; loginRunSettled = false; loginFileUnread = false; }
                Log.I("login file: " + (why ?? "written"));
            }
        }

        /// <summary>the Thor's remembered game account into the PC's Config.wtf (WowAccount: only that key, the PC's own pick wins); only
        /// while no client runs (WoW writes the file when it closes)</summary>
        void RememberGameAccount(string flavorDir)
        {
            var list = gameAccountList;
            if (list == null || flavorDir == null) return;
            Log.I("game account: " + WowAccount.Ensure(Path.Combine(flavorDir, "WTF", "Config.wtf"), list));
        }

        /// <summary>
        /// Just before Desktop starts WoW: the remembered game account goes in, and when the client of the last login.txt run closed
        /// itself (a refused region, 2026-11-17), its file goes (it was for that run only) and the Thor is asked for the login again
        /// ("login_again"; the user's pick 2026-11-17: "Ask the Thor again"). Its answer is an ordinary login_file: written for the new
        /// run, deleted by the same rule.
        /// </summary>
        void BeforeStart(LaunchPlan p)
        {
            bool ask;
            lock (loginGate)
            {
                try
                {
                    // the started client's own folder (a start through Battle.net works in Battle.net's folder)
                    RememberGameAccount(p?.Flavor != null && Wow != null ? Wow.FlavorDir(p.Flavor) : FlavorDirOrNull);
                    var dir = FlavorDirOrNull;
                    int? pidNow = null;
                    using (var w = WowWindow.FindAny(dir)) if (w != null) pidNow = w.Id;
                    var over = WowFlow.LoginRunOver(loginRunPid, pidNow);
                    if (over && loginFileAt.HasValue && dir != null)
                    {
                        WowLoginFile.Delete(dir);
                        loginFileAt = null;
                        Log.I("login file: deleted (its client closed)");
                    }
                    ask = WowFlow.LoginAgainWanted(loginFileAt.HasValue, over || loginFileUnread, Bridge != null && Bridge.Sessions().Count > 0);
                    if (ask) { loginRunPid = null; loginFileUnread = false; }
                }
                catch (Exception e) { Log.I("before start: " + e.GetType().Name); return; }
            }
            if (!ask) return;
            Log.I("login file: its client closed; asking the Thor again");
            Bridge?.Publish(new Dictionary<string, object> { ["t"] = "login_again" });
            // the new client reads login.txt as its login screen appears: give the Thor's answer a moment to land first
            var until = DateTime.UtcNow.AddSeconds(WowFlow.LoginAgainWaitS);
            while (DateTime.UtcNow < until)
            {
                lock (loginGate) if (loginFileAt.HasValue) { Log.I("login file: back from the Thor before the start"); return; }
                System.Threading.Thread.Sleep(100);
            }
            Log.I("login file: no answer from the Thor in " + (int)WowFlow.LoginAgainWaitS + " s; starting without it");
        }

        void LoginFileTick()
        {
            lock (loginGate)
            try
            {
                var dir = FlavorDirOrNull;
                if (dir == null) return;
                if (!loginFileSwept) { loginFileSwept = true; if (!loginFileAt.HasValue) WowLoginFile.Delete(dir); }   // a crash's leftover
                var thorHere = Bridge != null && Bridge.Sessions().Count > 0;
                // the Thor left: nothing to ask again, and its game account is not kept (an account name)
                if (!thorHere) { loginRunPid = null; gameAccountList = null; loginFileUnread = false; }
                int? pidNow = null;
                if (loginFileAt.HasValue || loginRunPid.HasValue)
                    using (var w = WowWindow.FindAny(dir)) if (w != null) pidNow = w.Id;
                // the run signed in (character select or the world): its client closing later is the player quitting, never a reason
                // to ask the Thor for the login again
                bool logAtSignIn;
                lock (loginLog) logAtSignIn = loginLog.AtSignInScreens;
                if (loginRunPid.HasValue && pidNow == loginRunPid && WowFlow.LoginRunSettled(LastScreen, logAtSignIn))
                {
                    loginRunPid = null; loginRunSettled = true;
                    Log.I("login file: its run signed in");
                }
                if (!loginFileAt.HasValue) return;
                if (!loginRunPid.HasValue && !loginRunSettled && pidNow.HasValue) loginRunPid = pidNow;      // the run this file is for
                // only THIS client run's sign-in counts (its connections, or its log once its restart was seen): a stale log made a
                // 0.5 s old Wow.exe "CharSelect" and the 20 s ran from the launch (the user, 2026-11-17)
                var s = ScreenNow();
                if (!signInSeenAt.HasValue && (s == WowScreen.SigningIn || SignedIn(s)))
                {
                    signInSeenAt = DateTime.UtcNow;
                    Log.I("login file: sign-in seen (" + WowFlow.Word(s) + ")");
                }
                var now = DateTime.UtcNow;
                var why = WowFlow.LoginFileWhy((now - loginFileAt.Value).TotalSeconds, signInSeenAt.HasValue ? (now - signInSeenAt.Value).TotalSeconds : (double?)null,
                        thorHere, WowFlow.LoginRunOver(loginRunPid, pidNow));
                if (why == null) return;
                WowLoginFile.Delete(dir);
                loginFileAt = null;
                // no client ever came for it (Battle.net's sign-in first): the next start asks the Thor again
                loginFileUnread = thorHere && !pidNow.HasValue && !loginRunPid.HasValue;
                Log.I("login file: deleted (" + why + ")");
            }
            catch (Exception e) { Log.I("login file: " + e.GetType().Name); }
        }

        void SlowTick()
        {
            LoginFileTick();
            CloseAfterStream();
            UpdateTick();
            try
            {
                // the streaming ports: 5 s after the start (ours has bound by then), then every 15 s
                if (tickN == 5 || tickN % 15 == 0) CheckPorts(null);
                // the whole port set (HostHealth): 6 s after the start, then every 15 s; every 2 s while a heal runs or ours is
                // still starting (so its first started look comes at once). RTSP is connected to once a minute when fine (each
                // connect is a line in its log), at every look while healing, and at the first look after a start
                bool starting = Sunshine != null && Sunshine.Running && !HostStarted;
                if (tickN == 6 || tickN % 15 == 0 || ((healer.Healing || starting) && tickN % 2 == 0))
                {
                    bool rtsp = tickN % 60 == 0 || healer.Healing || tickN == 6 || starting;
                    Task.Run(() => HealthTick(rtsp));
                }
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
                    try { DisplayTick(); } catch (Exception e) { Log.I("display tick: " + e.Message); }
                    try { KeepTick(); } catch (Exception e) { Log.I("keep tick: " + e.Message); }
                    CheckCapture();
                }
            }
            catch (Exception) { }
            // every 2 s, beside CheckCapture's tick (outside its try: a serverinfo that throws doesn't skip it)
            if (tickN % 2 == 1) try { CheckInput(); } catch (Exception e) { Log.I("input check: " + e.Message); }
            try { Starter.Tick(); } catch (Exception) { }
            try { ConfigKeep?.Tick(); } catch (Exception) { }
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
            if (s == StreamState.Streaming) busySince = DateTime.UtcNow;
            Log.I("stream: " + s);
            if (s != StreamState.Streaming && configStale) Task.Run(() => ApplyConfig("after the stream"));
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
                if (!ok) s["why"] = Supervisor.NeedsYou ? WowFlow.NeedsYouText
                    : WowFlow.StopText(Supervisor.StoppedFor) ?? StopNow()
                    ?? (Starter.State == WowState.Starting ? "starting WoW" : (Starter.Why ?? "WoW isn't signed in yet"));
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
        /// <summary>the player's own controller settings, put back once a DS Forever session is over (WowConfigKeep)</summary>
        WowConfigKeep ConfigKeep;
        /// <summary>the PC monitor's own mode, put back after a stream if Sunshine's revert didn't (DisplayKeep)</summary>
        DisplayKeep ScreenKeep;

        /// <summary>after a stream, the player's own settings back: the monitor's mode (every 2 s), WoW's controller and window lines</summary>
        void KeepTick()
        {
            var streaming = Stream == StreamState.Streaming;
            var list = SunshineDisplays();
            var cap = sessionOutput == null && list.Count > 0 ? list.FirstOrDefault(d => d.DisplayName == Displays.Captured(list, CurrentOutput)) : null;
            ScreenKeep?.Tick(streaming, cap != null && !cap.Virtual ? cap.DisplayName : null, MatchStreamNow);
            var dir = FlavorDirOrNull;
            if (streaming && dir != null) ConfigKeep?.RecordWindow(Path.Combine(dir, "WTF", "Config.wtf"));
        }

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
                start: p => { BeforeStart(p); return WowStarter.StartProcess(p, ConfigKeep); });
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
        /// The log and the add-on's documents count only once they are this client run's (<see cref="WowFlow.LogIsThisRun"/>): for
        /// login.txt, the supervisor and the Thor alike, so a new Wow.exe never reads as the previous run's screen. Its connections
        /// always count.
        public WowScreen ScreenNow()
        {
            var p = WowWindow.FindAny(FlavorDirOrNull);
            if (p == null) return WowScreen.None;
            try { return ScreenOf(p, useLog: true); }
            finally { p.Dispose(); }
        }

        /// <summary>the screen of THIS client process (the patch's close asks it of the very process it closes); [useLog] = the log
        /// and the documents are this process's folder's (Desktop follows it), else its connections only</summary>
        WowScreen ScreenOf(System.Diagnostics.Process p, bool useLog)
        {
            {
                double up = 0;
                bool thisRun = false;
                try { var started = p.StartTime; up = (DateTime.Now - started).TotalSeconds; thisRun = useLog && WowFlow.LogIsThisRun(loginLogAt, started); } catch (Exception) { }
                var (game, auth) = WowProbe.Connections(p.Id);
                double? docAge = thisRun && clientDocAt.HasValue ? (DateTime.UtcNow - clientDocAt.Value).TotalSeconds : (double?)null;
                lock (loginLog) return WowFlow.Classify(true, up, game, auth, thisRun ? loginLog : null, docAge);
            }
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
            bool wanted = WowFlow.BnetSignInPending(LastBnet, LastScreen) || sv.Crash != null || sv.NeedsYou;
            if (!wanted) return null;
            var b = WowProbe.Bnet();
            var h = b.Login != IntPtr.Zero ? b.Login : WowProbe.BnetMain();
            if (h == IntPtr.Zero) return null;
            var list = SunshineDisplays();
            var captured = list.Count > 0 ? Displays.Captured(list, CurrentOutput) : null;
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
            var captured = list.Count > 0 ? Displays.Captured(list, CurrentOutput) : null;
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
            // never Battle.net while the self-patcher puts the old client back or is about to (a first start that crashed): its Agent
            // would work on the folder beside the put-back (the 0.2.36 review)
            var pp = patcher;
            if (pp != null && (pp.Busy || pp.Unsettled || pp.AwaitingLogin != null))
            {
                Log.I("WoW crashed: Battle.net left alone (the game update puts the old client back first)");
                return;
            }
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
                startWow: () =>
                {
                    // the install read again right before the start (the Agent dropped the wowf row at 06:32 on 2026-11-18): a missing
                    // row is added back from Desktop's copy when it fits; still missing, or outdated = no start (the judge's Step 0)
                    // never beside the self-patcher (0.2.36): it replaces the client's files
                    // nor over an install cut short that is not put back yet (half one build, half the other)
                    if (patcher?.Busy == true || patcher?.Unsettled == true) return "the game is being updated";
                    var g = GameCheckNow(beforeStart: true);
                    if (g == GameCheck.NeedsRepair) return WowFlow.RepairText;
                    if (g == GameCheck.Outdated) return WowFlow.OutdatedText;
                    Starter.Ensure(); return Starter.State == WowState.StartFailed ? Starter.Why : null;
                },
                startPending: () => Starter.State == WowState.Starting,
                closeWow: CloseForRestart,
                focusWow: () => { CheckCapture(); return FocusWow(); },
                crash: CrashNow,
                failed: () => { lock (loginLog) return loginLog.ConnectFailed; },
                game: GameNow);
            Supervisor.Changed += (a, detail) =>
            {
                if (a == WowAction.NeedsYou) needsYouAt = DateTime.UtcNow;
                if (a == WowAction.Crashed) { needsYouAt = DateTime.UtcNow; Task.Run(AfterCrash); }
                var text = a == WowAction.StartBattleNet ? "Starting Battle.net on your PC…"
                         : a == WowAction.ShowBattleNetSignIn ? "Sign in to Battle.net"
                         : a == WowAction.RestartWow ? "WoW was at its login screen: starting it again…"
                         : a == WowAction.NeedsYou ? WowFlow.NeedsYouText
                         : a == WowAction.Stopped ? WowFlow.ConnectFailedText
                         : a == WowAction.Outdated ? WowFlow.OutdatedText
                         : a == WowAction.Repair ? WowFlow.RepairText
                         : a == WowAction.Crashed ? (detail == "update" ? WowFlow.UpdateText : WowFlow.CrashText) : null;
                if (text != null) Bridge?.Publish(new Dictionary<string, object> { ["t"] = "status", ["text"] = text });
                PublishWow(force: true);
            };
        }

        /// <summary>Blizzard's patch server (plain HTTP on 1119, as the Thor's own updater reads it): a few seconds at most, never on
        /// the tick (GameVersionCheck runs it in the background)</summary>
        static readonly HttpClient Patch = new HttpClient { Timeout = TimeSpan.FromSeconds(5) };

        GameVersionCheck gameCheck;
        BuildInfoKeeper rowKeeper;
        readonly object gameGate = new object();

        /// <summary>_forever_'s Wow.exe's own version (read only), null = no such exe</summary>
        string ForeverExeVersion() =>
            Wow != null ? BuildInfoFile.ExeVersion(Path.Combine(Wow.FlavorDir(WowFlavors.Forever), "Wow.exe")) : null;

        /// <summary>the PC's WoW Forever against Blizzard's version (the user's PC 2026-11-18: 70938 installed, 70959 current, so
        /// "no servers available"): Unknown when there is no install or no answer (offline never blocks a start); NeedsRepair when
        /// Wow.exe is there and its .build.info row is not (0.2.35). [beforeStart] = the install read again now</summary>
        GameCheck GameCheckNow(bool beforeStart = false)
        {
            lock (gameGate)
                if (gameCheck == null)
                {
                    rowKeeper = new BuildInfoKeeper(() => Wow?.Root, Path.Combine(Paths.DataDir, "patch", "wowf_row.json"), "wowf");
                    gameCheck = new GameVersionCheck(
                        rows: () => Wow != null ? BuildInfo.Read(Wow.Root) : new List<BuildInfoRow>(),
                        fetch: url =>
                        {
                            try { using (var r = Patch.GetAsync(url).Result) return r.IsSuccessStatusCode ? r.Content.ReadAsStringAsync().Result : null; }
                            catch (Exception) { return null; }
                        },
                        product: "wowf",
                        exeVersion: ForeverExeVersion,
                        repair: v => rowKeeper.Repair(v),
                        remember: v => rowKeeper.Remember(v));
                }
            return beforeStart ? gameCheck.PollNow() : gameCheck.Poll();
        }

        /// <summary>the supervisor's check (it only runs while a stream wants WoW)</summary>
        GameCheck GameNow() => GameCheckNow();

        /// <summary>the check for the "wow" message: asked only while a Thor is connected (0.2.34 asked Blizzard every 5 minutes with
        /// no Thor at all); otherwise the last state, nothing read or asked</summary>
        GameCheck GameForThor()
        {
            bool thorHere = Bridge != null && Bridge.Sessions().Count > 0;
            if (thorHere) return GameCheckNow();
            lock (gameGate) return gameCheck?.Peek() ?? GameCheck.Unknown;
        }

        /// <summary>why Desktop is stopped now for the Thor (a 309, an outdated or broken PC game), null = not stopped</summary>
        string StopNow()
        {
            bool failed;
            lock (loginLog) failed = loginLog.ConnectFailed;
            if (SignedIn(LastScreen)) return null;
            var g = GameForThor();
            return g == GameCheck.NeedsRepair ? WowFlow.RepairText : g == GameCheck.Outdated ? WowFlow.OutdatedText
                 : failed ? WowFlow.ConnectFailedText : null;
        }

        DateTime legacyRetryAt = DateTime.MinValue;

        /// <summary>
        /// A Thor signed in. An older one (1487: no "wow_stop" in its hello) has no Retry button and never sends "wow_retry", so after
        /// a 309 with WoW closed it said "Starting WoW on your PC" for ever (the 0.2.34 review): its reconnect counts as its Retry
        /// while Desktop is stopped, once per WowFlow.LegacyRetryEveryS at most. A Thor that shows the stop is never retried
        /// for: its player presses Retry.
        /// </summary>
        void OnThorHello(BridgeSession s)
        {
            // a Thor connected: the self-patcher may try once by itself again (0.2.36: one automatic attempt per connect)
            if (s != null) patchFlow?.Connect();
            if (s == null || s.KnowsStop || !Settings.StartWowOnStream || Supervisor == null) return;
            if (!WowFlow.LegacyReconnectRetries(Supervisor.StoppedFor != null || StopNow() != null, (DateTime.UtcNow - legacyRetryAt).TotalSeconds)) return;
            legacyRetryAt = DateTime.UtcNow;
            Log.I("bridge: an older Thor (no Retry button) reconnected while stopped: that counts as its Retry");
            Task.Run(() => WowRetry());
        }

        /// <summary>
        /// The Thor's Retry ("wow_retry") after a stop (the user's ruling 2026-11-18, "Stop and show it"): the server error is
        /// answered and Blizzard's version asked again; then WoW is started, or restarted at its login screen, ONCE for this press
        /// (WowSupervisor.Retry; its new run asks the Thor for the login again, BeforeStart). An outdated game is still not started.
        /// </summary>
        public void WowRetry()
        {
            // the self-patcher's Retry first (0.2.36): a failed or cancelled patch, or a game that still needs one, gets ONE more
            // attempt per press; WoW starts by itself once it is done (PatchFlow's afterDone)
            if (EnsurePatcher() != null && patchFlow.Retry()) { PublishWow(force: true); return; }
            // one start per press: a press while the last one's restart or start is still under way is that same one (0.2.35)
            if (Supervisor != null && Supervisor.Busy)
            {
                Log.I("bridge: the Thor's Retry while WoW is being started: that one counts");
                PublishWow(force: true);
                return;
            }
            Log.I("bridge: the Thor's Retry: WoW once more");
            lock (loginLog) loginLog.ClearFailure();
            GameCheckNow();
            gameCheck?.Refresh();
            needsYouAt = DateTime.MinValue;
            if (Settings.StartWowOnStream && Supervisor != null) Supervisor.Retry();
            PublishWow(force: true);
        }

        // ---------------------------------------------------------------- the self-patcher (0.2.36, the user's rulings 2026-11-18)

        PcGamePatcher patcher;
        PatchFlow patchFlow;
        string patcherRoot;
        bool loginSeen;

        /// <summary>the PC game's own updater for the install found (null = no WoW Forever here); made again when the folder changes</summary>
        PcGamePatcher EnsurePatcher()
        {
            lock (gameGate)
            {
                var root = Wow?.Root;
                if (root == null || !Directory.Exists(Path.Combine(root, WowFlavors.Forever))) return null;
                if (patcher != null && (patcherRoot == root || patcher.Busy)) return patcher;
                var forever = Path.Combine(root, WowFlavors.Forever);
                patcherRoot = root;
                var made = new PcGamePatcher(new PatchEnv
                {
                    Root = root,
                    Flavor = WowFlavors.Forever,
                    Product = "wowf",
                    FetchText = url =>
                    {
                        try { using (var r = Patch.GetAsync(url).Result) return r.IsSuccessStatusCode ? r.Content.ReadAsStringAsync().Result : null; }
                        catch (Exception) { return null; }
                    },
                    WowRunning = () => WowWindow.RunningIn(forever),
                    // the outdated client open at its login (the user's 11-18 06:20 case: no servers) is closed BY THE ATTEMPT (auto or
                    // Retry), as the supervisor's restart closes it; a signed-in one never, nor retail, nor one whose folder can't be read
                    // (ForeverClose: the 0.2.36 review)
                    CloseWow = () => CloseForeverForPatch(forever),
                    AgentBusy = () => AgentWatch.Busy(root, "wowf", DateTime.UtcNow, AgentWatch.DefaultAgentDir, out var why) ? why : null,
                    JournalPath = Path.Combine(Paths.DataDir, "patch", "journal.json"),
                    SavedRowPath = Path.Combine(Paths.DataDir, "patch", "wowf_row.json"),
                    FailedBuildPath = Path.Combine(Paths.DataDir, "patch", "new_failed.json"),
                });
                made.Changed += () => PublishWow(force: false);
                patchFlow = new PatchFlow(() => made.Status, why => made.Start(why), () => made.Cancel(), GameForThor, AfterPatch,
                                          autoStart: why => made.Start(why, auto: true));
                patcher = made;
                return patcher;
            }
        }

        /// <summary>a patch is done: the game read again, then WoW started ONCE (as the Thor's Retry starts it)</summary>
        void AfterPatch()
        {
            Log.I("game patch: done: WoW once on the new build");
            gameCheck?.Refresh();
            needsYouAt = DateTime.MinValue;
            if (Settings.StartWowOnStream && Supervisor != null && Bridge != null && Bridge.Sessions().Count > 0) Supervisor.Retry();
            PublishWow(force: true);
        }

        /// <summary>every tick: the patch by itself when the game needs one (once per connect), and its first start watched: a good
        /// login removes the backup (one backup until then, the user's ruling); a crash brings the old client back</summary>
        void PatchTick(bool thorHere)
        {
            var p = EnsurePatcher();
            if (p == null) return;
            // never by itself while WoW is signed in (the servers took that client: nothing to close it for, and its failure would sit
            // under the player's login later)
            patchFlow.Tick(thorHere && Settings.StartWowOnStream && !SignedIn(LastScreen));
            // a failed or cancelled attempt is not shown any more once the game is current by another route (the 0.2.36 review: the
            // stale "Update failed ... Retry (A)" over WoW's login); a new client's failed first start stays until a press
            var ps = p.Status;
            if ((ps.State == PatchState.Failed || ps.State == PatchState.Cancelled) && ps.Code != "new_failed" && GameForThor() == GameCheck.Current) p.Reset();
            // the first start watched only while Desktop follows WoW Forever (a retail login is not the patched client's)
            bool forever = Flavor == WowFlavors.Forever;
            bool signedIn = forever && SignedIn(LastScreen);
            if (signedIn && !loginSeen && !p.Busy && p.AwaitingLogin != null) Task.Run(() => p.GoodLogin(ForeverExeVersion()));
            loginSeen = signedIn;
            // a crash on the first start: the old client back once the crashed one is gone (RestoreOld waits for it); asked again
            // while it stays (one at a time), so a client still closing never leaves the new build in place
            if (p.Unsettled && !p.Busy && !WowWindow.RunningIn(Path.Combine(patcherRoot, WowFlavors.Forever))) RecoverInBackground(p);
            bool crashed = forever && Supervisor?.Crash != null;
            if (crashed && !p.Busy && p.AwaitingLogin != null && Interlocked.CompareExchange(ref restoring, 1, 0) == 0)
                Task.Run(() =>
                {
                    try { if (p.RestoreOld("WoW " + Supervisor?.Crash + " on its first start")) { gameCheck?.Refresh(); PublishWow(force: true); } }
                    finally { Interlocked.Exchange(ref restoring, 0); }
                });
        }

        int restoring;

        /// <summary>an install cut short put back off the tick (it waits for Blizzard's updater); one at a time; busy meanwhile</summary>
        void RecoverInBackground(PcGamePatcher p)
        {
            if (Interlocked.CompareExchange(ref restoring, 1, 0) != 0) return;
            Task.Run(() =>
            {
                try { var r = p.Recover(); if (r != null) Log.I("game patch: " + r); gameCheck?.Refresh(); PublishWow(force: true); }
                catch (Exception e) { Log.I("game patch: recovery: " + e.Message); }
                finally { Interlocked.Exchange(ref restoring, 0); }
            });
        }

        /// <summary>the self-patcher's close (env.CloseWow): only the client running from [foreverDir] (ForeverClose)</summary>
        string CloseForeverForPatch(string foreverDir)
        {
            var procs = new Dictionary<int, System.Diagnostics.Process>();
            try
            {
                return ForeverClose.Run(foreverDir,
                    list: () =>
                    {
                        var l = new List<WowProc>();
                        foreach (var n in new[] { "Wow", "WowB", "WowT", "WowClassic", "WowClassicB", "WowClassicT" })
                        {
                            System.Diagnostics.Process[] ps;
                            try { ps = System.Diagnostics.Process.GetProcessesByName(n); } catch (Exception) { continue; }
                            foreach (var x in ps)
                            {
                                string path = null;
                                try { path = x.MainModule?.FileName; } catch (Exception) { }
                                procs[x.Id] = x;
                                l.Add(new WowProc { Pid = x.Id, Path = path });
                            }
                        }
                        return l;
                    },
                    signedIn: pid => procs.TryGetValue(pid, out var x) && SignedIn(ScreenOf(x, useLog: Flavor == WowFlavors.Forever)),
                    closeWindow: pid => { if (procs.TryGetValue(pid, out var x)) x.CloseMainWindow(); },
                    waitExit: (pid, ms) => !procs.TryGetValue(pid, out var x) || x.WaitForExit(ms),
                    kill: pid =>
                    {
                        if (!procs.TryGetValue(pid, out var x)) return true;
                        try { x.Kill(); return true; } catch (Exception e) { Log.I("game patch: couldn't end WoW: " + e.Message); return false; }
                    });
            }
            finally { foreach (var x in procs.Values) x.Dispose(); }
        }

        /// <summary>the Thor's Cancel (B) on the game update's bar ("pc_patch_cancel")</summary>
        public void PatchCancel()
        {
            bool asked = EnsurePatcher() != null && patchFlow.Cancel();
            Log.I("bridge: the Thor's Cancel on the game update" + (asked ? "" : ": nothing to cancel"));
            PublishWow(force: true);
        }

        /// <summary>the game and Battle.net for the Thor (the "wow" message, the welcome's wow): its curtain lifts on charselect / world + focused</summary>
        public Dictionary<string, object> WowInfo()
        {
            string err;
            bool connectFailed;
            lock (loginLog) { err = loginLog.Error; connectFailed = loginLog.ConnectFailed; }
            var d = new Dictionary<string, object>
            {
                ["state"] = WowStarter.Word(WowUp() ? WowState.Running : Starter.State),
                ["screen"] = WowFlow.Word(LastScreen),
                ["focused"] = WowWindow.IsForeground(),
                // the PC's Battle.net again (0.2.8 to 0.2.32 said "unknown" always, so the Thor never showed its sign-in window):
                // "signin_needed" + "show" = the Thor shows Battle.net's window alone and types the saved login into it
                ["bnet"] = WowFlow.ThorBnet(LastBnet, LastScreen),
                ["needs_you"] = Supervisor?.NeedsYou ?? false,
            };
            if (Supervisor?.Crash != null) d["crash"] = Supervisor.Crash;
            var show = ShowRectNow();
            if (show != null) d["show"] = show;
            if (Starter.Why != null) d["why"] = Starter.Why;
            if (err != null) d["login_error"] = err;
            // a 309 (LoginLog.ConnectFailed): the Thor says one neutral line, "WoW can't reach its servers"; the player picks a region
            // again on WoW's own screen (Desktop never picks or saves one). Never "region" "refused" any more (0.2.31 and 0.2.32): the
            // log cannot tell a refused district from an unreachable realm, and the user's 309 was no region error (2026-11-17)
            if (connectFailed)
            {
                d["connect"] = "failed";
                // WoW's own code with it: the screen switch after the error clears login_error, this stays until Retry
                d["connect_code"] = LoginLog.ConnectFailedCode;
            }
            // the PC game older than Blizzard's (the user's ruling 2026-11-18): WoW is not started; the Thor says the two versions.
            // Asked only while a Thor is connected (0.2.35)
            var g = GameForThor();
            if ((g == GameCheck.Outdated || g == GameCheck.NeedsRepair) && gameCheck != null)
            {
                d["pc_game"] = g == GameCheck.Outdated ? "outdated" : "repair";
                d["game_installed"] = gameCheck.Installed ?? "";
                d["game_current"] = gameCheck.Current ?? "";
            }
            // 0.2.34: Desktop stopped and waits for the Thor's Retry ("wow_retry"): a server error or an outdated (0.2.35: or broken)
            // game while WoW is not signed in. 0.2.35: the words an older Thor (1487) shows too (WowFlow.StopWords)
            WowFlow.StopWords(d, connectFailed, g, SignedIn(LastScreen));
            // the self-patcher (0.2.36): the Thor's bar, its rows and Cancel (B) / Retry (A); an older Thor (no bar) hears its line as "why"
            var ps = patcher?.Status;
            var pw = PatchFlow.Words(ps, starting: Starter.State == WowState.Starting || LastScreen == WowScreen.Starting);
            if (pw != null)
            {
                d["pc_patch"] = pw;
                d["why"] = PatchFlow.Line(ps);
                // while it patches (and the new client loads) WoW IS being started: "starting", never "start_failed" (an older Thor,
                // 1487, put "WoW didn't start: ... sign in on the PC" in its status line for that, rewritten every 500 ms; it says
                // "Starting WoW on your PC" now, without the bar). A failed or cancelled patch stays a stop
                if (ps.Busy || ps.State == PatchState.Done) d["state"] = WowStarter.Word(WowState.Starting);
            }
            return WithInput(d);
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
            // a 309 (LoginLog.ConnectFailed) starts nothing any more (the user's ruling 2026-11-18, "Stop and show it": 0.2.33
            // restarted WoW every ~20 s while Blizzard said "no servers available"): the Thor shows it with a Retry (WowRetry). While a
            // Thor is here the PC game is checked against Blizzard's version too (WowInfo says it; the supervisor never starts an
            // outdated game)
            bool thorHere = Bridge != null && Bridge.Sessions().Count > 0;
            if (thorHere) GameCheckNow();
            PatchTick(thorHere);
            if (Supervisor.Active) Supervisor.Step();
            FocusRetry();
            PublishWow(force: false);
        }

        /// <summary>Windows can refuse the one focus at the start, and nothing asked again: WoW stayed behind (5 times in the user's log).
        /// While a Thor is connected it is asked again for a minute at most, never after that (the PC may be in use)</summary>
        int focusTries;
        DateTime nextFocus = DateTime.MinValue;
        int focusBusy;

        void FocusRetry()
        {
            if (Bridge == null || Bridge.Sessions().Count == 0 || LastScreen == WowScreen.None) { focusTries = 0; return; }
            // Battle.net's sign-in is the player's step (the Thor shows that window alone): WoW is never pulled over it
            if (WowFlow.BnetSignInPending(LastBnet, LastScreen)) return;
            if (!WowFlow.FocusRetryWanted(LastScreen, WowWindow.IsForeground(), focusTries, DateTime.UtcNow >= nextFocus)) return;
            if (Interlocked.CompareExchange(ref focusBusy, 1, 0) != 0) return;
            focusTries++;
            nextFocus = DateTime.UtcNow.AddSeconds(WowFlow.FocusRetryEveryS);
            Task.Run(() => { try { FocusWow(); } finally { Interlocked.Exchange(ref focusBusy, 0); } });
        }

        void PublishWow(bool force)
        {
            var w = WowInfo();
            // a nested value (pc_patch) by its JSON: its progress is a change
            var key = string.Join("|", w.OrderBy(kv => kv.Key).Select(kv => kv.Key + "=" + (kv.Value is Dictionary<string, object> nd ? Json.Write(nd) : kv.Value)));
            if (!force && key == lastWowKey) return;
            lastWowKey = key;
            var msg = new Dictionary<string, object> { ["t"] = "wow" };
            foreach (var kv in w) msg[kv.Key] = kv.Value;
            Bridge?.Publish(msg);
        }

        public string CaptureStatus { get; private set; } = "";
        DateTime lastRecapture = DateTime.MinValue, lastMove = DateTime.MinValue;
        readonly object captureGate = new object();

        List<SunshineDisplay> displaysRead;
        DateTime displaysAt = DateTime.MinValue;

        /// <summary>Sunshine's display list from its log (read once for CheckCapture and CheckInput in the same tick)</summary>
        List<SunshineDisplay> SunshineDisplays()
        {
            var cached = displaysRead;
            if (cached != null && DateTime.UtcNow - displaysAt < TimeSpan.FromSeconds(1)) return cached;
            List<SunshineDisplay> list;
            try
            {
                using (var fs = new FileStream(SunshineLayout.LogFile, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                using (var r = new StreamReader(fs)) list = Displays.ParseSunshineLog(r.ReadToEnd());
            }
            catch (Exception) { list = new List<SunshineDisplay>(); }
            Displays.MarkVirtual(list, lastWin.Count > 0 ? lastWin : Displays.WindowsDisplays());
            displaysRead = list;
            displaysAt = DateTime.UtcNow;
            return list;
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
                var captured = Displays.Captured(list, CurrentOutput);
                var target = list.FirstOrDefault(d => string.Equals(d.DisplayName, wowDisplay, StringComparison.OrdinalIgnoreCase));
                // WoW on a virtual display (Apollo's session screen): never remembered; WoW comes to the captured screen instead
                bool wowOnVirtual = target != null ? target.Virtual
                    : lastWin.Any(w => w.Virtual && string.Equals(w.Name, wowDisplay, StringComparison.OrdinalIgnoreCase));
                if (ScreenLine.Length > 0 && ScreenLine != ScreenPlan.Ready) CaptureStatus = ScreenLine;
                bool own = sessionOutput != null;
                switch (Displays.Decide(wowDisplay, captured, Stream == StreamState.Streaming, Displays.MayRemember(target), wowOnVirtual, own))
                {
                    case Displays.Fix.RecaptureSunshine:
                        if (sessionOutput != null) return;          // our own virtual screen is the session's: WoW comes to it
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
                        // off the player's desktop onto ours: remembered, so it goes back after (never a virtual display)
                        if (own && wowHome == null && !wowOnVirtual) wowHome = wowDisplay;
                        var why = Displays.MoveTo(p.MainWindowHandle, captured);
                        CaptureStatus = why == null ? "moved WoW to the streamed monitor" : "couldn't move WoW to the streamed monitor: " + why;
                        Log.I("capture: " + (own ? "our own screen" : Stream == StreamState.Streaming ? "stream running" : "WoW on a virtual display") + ", WoW on " + wowDisplay + ", captured " + captured + ": " + CaptureStatus);
                        FocusWow();
                        break;
                    default:
                        var cap = list.FirstOrDefault(d => d.DisplayName == captured);
                        CaptureStatus = ScreenLine.Length > 0 && ScreenLine != ScreenPlan.Ready ? ScreenLine
                            : "capturing " + (sessionOutput != null ? "DS Forever's virtual screen" : cap?.Friendly ?? captured) + (wowDisplay == captured ? ", where WoW is" : "");
                        break;
                }
            }
            catch (Exception e) { Log.I("capture: " + e.Message); }
            finally { Monitor.Exit(captureGate); }
        }

        /// <summary>why the Thor's taps can't reach WoW ([WowInput]: "elevated" / "not_filling"; "" = fine or not known)</summary>
        public string InputProblem { get; private set; } = "";
        readonly WowInput.Steady inputSteady = new WowInput.Steady();
        string inputDetail = "";

        /// <summary>
        /// Issues #304 / #357 (the user's pick, 2026-11-16): WoW above Desktop's integrity level (run as administrator: Windows drops
        /// Sunshine's mouse and keys), or WoW's client area not filling the captured monitor (the companion's taps land elsewhere).
        /// Said in the window, in the welcome's / wow message's wow.input, and as a Thor status when it changes. Read-only.
        /// </summary>
        void CheckInput()
        {
            string word = "", detail = "";
            try
            {
                using (var p = WowWindow.Find(FlavorDirOrNull))
                if (p != null)
                {
                    var list = SunshineDisplays();
                    var captured = list.Count > 0 ? Displays.Captured(list, CurrentOutput) : null;
                    var mon = Displays.MonitorRect(captured);
                    var client = WowInput.ClientOnScreen(p.MainWindowHandle);
                    int? wowLevel = WowInput.IntegrityOf(p.Id), own = WowInput.OwnIntegrity();
                    word = WowInput.Word(wowLevel, own, client, mon);
                    string R(Displays.RECT? r) => r.HasValue ? r.Value.Left + "," + r.Value.Top + " " + r.Value.W + "x" + r.Value.H : "none";
                    detail = "integrity WoW " + (wowLevel.HasValue ? "0x" + wowLevel.Value.ToString("x") : "unread") + ", ours " +
                             (own.HasValue ? "0x" + own.Value.ToString("x") : "unread") + "; client " + R(client) + ", captured " + (captured ?? "none") + " " + R(mon);
                }
            }
            catch (Exception e) { Log.I("input check: " + e.Message); }
            if (word.Length > 0) inputDetail = detail;
            var said = inputSteady.Step(word, DateTime.UtcNow);
            if (said == InputProblem) return;
            var was = InputProblem;
            InputProblem = said;
            Log.I("WoW input: " + (said.Length == 0 ? "fine" : said + " (" + inputDetail + ")"));
            var text = said.Length > 0 ? WowInput.ThorWhy(said) : was.Length > 0 ? WowInput.ThorFine : null;
            if (text != null) Bridge?.Publish(new Dictionary<string, object> { ["t"] = "status", ["text"] = text });
            Fire();
        }

        /// <summary>the wow dictionary with [InputProblem] (an older Thor ignores both fields)</summary>
        Dictionary<string, object> WithInput(Dictionary<string, object> d)
        {
            d["input"] = InputProblem;
            var why = WowInput.ThorWhy(InputProblem);
            if (why != null) d["input_why"] = why;
            return d;
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
                    Bridge.ThorConnected += () => RequestUpdateCheck("a Thor connected");
                    Bridge.ThorHello += OnThorHello;
                    Bridge.ThorAddonSet += OnThorAddonSet;
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

        /// <summary>the welcome's / "sunshine" message's / host_check_result's "sunshine" object</summary>
        Dictionary<string, object> SunshineInfo() => new Dictionary<string, object>
        {
            ["running"] = Sunshine?.Running ?? false,
            ["http_port"] = SunshinePort,
            ["https_port"] = SunshinePort > 0 ? SunshinePort - 5 : -1,
            ["app_name"] = "WoW Forever",
            ["uniqueid"] = SunshineId,
            // why streaming can't work on this PC ("ports_taken" | "not_listening" | "exited" | "restarting" | "not_responding" |
            // "needs_screen" | "no_screen"; "" = fine) and the Thor's line for it; an older Thor ignores both
            // (0.2.37: every headless screen problem goes as "needs_screen", the Thor's one line: set it up in Desktop)
            ["problem"] = ScreenPlan.ThorWord(HostProblem) ?? "",
            ["why"] = HostWhy,
            // the Thor holds its stream start while this is said (verifier 2026-11-17: its stream started with the bridge, before
            // ours was made, so the first stream of each session stayed on the real screen): "screen" | "starting" | "" = go
            ["wait"] = StreamWait(),
        };

        string lastWait;
        int lastWaitSessions;

        /// <summary>
        /// What a Thor's stream start should wait for now: "screen" = our virtual screen is being made, or would be for a Thor (each
        /// make restarts ours); "starting" = ours is starting (its encoder probe, about 26 s) or a heal is restarting it; "" = go.
        /// Said in the welcome and every "sunshine" message; a change is sent at once.
        /// </summary>
        public string StreamWait()
        {
            try
            {
                var sh = Sunshine;
                if (sh == null || !sh.Running || SunshinePort <= 0) return "";
                if (StreamLive()) return "";
                if (screenBusy || WouldMakeScreen()) return "screen";
                if (!HostStarted || HealProblem == ProblemRestarting) return "starting";
                return "";
            }
            catch (Exception) { return ""; }
        }

        /// <summary>the screen plan for a Thor that is here now: ours would be made (or remade, or pointed at)</summary>
        bool WouldMakeScreen()
        {
            var life = Screens;
            var now = DateTime.UtcNow;
            var win = lastWin.Count > 0 ? lastWin : Displays.WindowsDisplays();
            bool hasScreen = Displays.HasScreen(win, life.GdiName);
            // quiet by default (0.2.37): a monitor on and "Stream on its own screen" off = nothing is made, no driver looked for
            if (!ScreenPlan.Looks(Settings.OwnScreen, hasScreen)) return false;
            if (driversAt == DateTime.MinValue) { driversAt = now; (driverInstalled, driverUsable) = life.Survey(); }
            // a pure look (reviewers 2026-11-17: Decide adopted a leftover and wrote the mode ours was made for)
            var (step, _) = life.Peek(now, Settings.OwnScreen, hasScreen, driverUsable, true, offered: true,
                                      (thorW, thorH, thorFps), false, sunshineOnOurs: sessionOutput != null, thorHere: true);
            return step == ScreenPlan.Step.MakeOurs;
        }

        /// <summary>the wait word changed: the Thor told now (it starts its stream when it goes "")</summary>
        void WaitTick()
        {
            int n = Bridge?.Sessions().Count ?? 0;
            if (n == 0) { lastWait = null; lastWaitSessions = 0; return; }
            // a new connection's welcome said its own word: the next change is sent whatever was said before
            if (n != lastWaitSessions) { lastWaitSessions = n; lastWait = null; }
            var w = StreamWait();
            if (w == lastWait) return;
            lastWait = w;
            Log.I("stream wait: " + (w.Length > 0 ? w : "go"));
            Bridge.Publish(new Dictionary<string, object> { ["t"] = "sunshine", ["sunshine"] = SunshineInfo() });
        }

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

        /// <summary>the PC's Tailscale addresses (IPv4 first): the Thor remembers them, so a Thor that only ever paired at home can
        /// still reach the PC away when Tailscale is on (the user, 2026-11-11: streaming away fixes)</summary>
        public static List<object> TailscaleAddresses()
        {
            var v4 = new List<object>(); var v6 = new List<object>();
            try
            {
                foreach (var ni in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                    if (ni.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback) continue;
                    foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                    {
                        var b = ua.Address.GetAddressBytes();
                        if (!IsTailscale(b)) continue;
                        var s = new IPAddress(b).ToString();     // an IPv6 without its %scope
                        var to = b.Length == 4 ? v4 : v6;
                        if (!to.Contains(s)) to.Add(s);
                    }
                }
            }
            catch (Exception) { }
            v4.AddRange(v6);
            return v4;
        }

        /// <summary>
        /// The addresses the pairing code carries (<see cref="PairAddress"/>): the home network's IPv4 on the interface with a default
        /// gateway (not a Hyper-V / WSL / VirtualBox switch, which have none; else the first home-network address), then the first
        /// Tailscale IPv4. Empty when the bridge listens on loopback only.
        /// </summary>
        public static List<IPAddress> PairAddresses()
        {
            var res = new List<IPAddress>();
            if (Loopback) return res;
            try
            {
                foreach (var ni in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                    if (ni.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback ||
                        ni.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Tunnel) continue;
                    var ip = ni.GetIPProperties();
                    if (!ip.GatewayAddresses.Any(g => g.Address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any))) continue;
                    var a = ip.UnicastAddresses.Select(u => u.Address)
                        .FirstOrDefault(x => x.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork && IsLan(x.GetAddressBytes()));
                    if (a != null) { res.Add(a); break; }
                }
            }
            catch (Exception) { }
            if (res.Count == 0 && LanAddresses().FirstOrDefault() is string lan && IPAddress.TryParse(lan, out var la)) res.Add(la);
            var ts = TailscaleAddresses().OfType<string>().Select(s => IPAddress.TryParse(s, out var t) ? t : null)
                .FirstOrDefault(t => t != null && t.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork);
            if (ts != null) res.Add(ts);
            return res;
        }

        string pairTail = "";
        DateTime pairTailUtc = DateTime.MinValue;

        /// <summary>the pairing code as the window shows it: the secret + the PC's addresses (looked up again every 5 s: a new
        /// DHCP lease or Tailscale going up shows in the next code line)</summary>
        public string PairCodeDisplay
        {
            get
            {
                var now = DateTime.UtcNow;
                if (now - pairTailUtc > TimeSpan.FromSeconds(5))
                {
                    try { pairTail = PairAddress.Encode(PairAddresses(), Bridge?.Port ?? PairAddress.DefaultBridgePort); } catch (Exception) { pairTail = ""; }
                    pairTailUtc = now;
                }
                return PairAddress.Display(Codes.Current, pairTail);
            }
        }

        /// <summary>Tailscale's ranges: 100.64.0.0/10 and fd7a:115c:a1e0::/48</summary>
        public static bool IsTailscale(byte[] b) =>
            (b.Length == 4 && b[0] == 100 && b[1] >= 64 && b[1] <= 127) ||
            (b.Length == 16 && b[0] == 0xfd && b[1] == 0x7a && b[2] == 0x11 && b[3] == 0x5c && b[4] == 0xa1 && b[5] == 0xe0);

        public Dictionary<string, object> Info() => new Dictionary<string, object>
        {
            ["app"] = "DS Forever Desktop",
            ["version"] = Version,
            ["host_id"] = Settings.HostId,
            ["pc_name"] = Environment.MachineName,
            // the PC's home-network IPv4s (not loopback, not Tailscale's 100.64/10): the Thor tries these first, Tailscale after
            // (the user, 2026-10-28: "Yes, Wi-Fi first" - with the Thor's Tailscale off it only knew the Tailscale address)
            ["lan"] = LanAddresses(),
            // and its Tailscale ones, for away from home (an older Thor ignores the field)
            ["tailscale"] = TailscaleAddresses(),
            ["sunshine"] = SunshineInfo(),
            ["stream"] = StreamWord,
            // issue #54: whether Sunshine can make the Thor's controller pad (ViGEmBus): "vigem" / "missing" / "unknown" (an older Thor ignores it)
            ["pad_driver"] = PadDriver.Now(),
            // issues #304 / #357: wow.input / input_why, why the Thor's taps can't reach WoW (WithInput)
            ["wow"] = WithInput(new Dictionary<string, object>
            {
                ["found"] = Wow != null, ["flavor"] = Flavor, ["running"] = WowWindow.IsRunning(), ["focused"] = WowWindow.IsForeground(),
                // running / starting / stopped / start_failed (+ why): "Starting WoW on your PC..." on the Thor
                ["state"] = WowStarter.Word(WowUp() ? WowState.Running : Starter.State), ["why"] = Starter.Why,
                // where it is and what Battle.net needs (0.2.1): the Thor keeps its curtain until charselect / world + focused
                ["screen"] = WowFlow.Word(LastScreen), ["bnet"] = WowFlow.ThorBnet(LastBnet, LastScreen), ["needs_you"] = Supervisor?.NeedsYou ?? false,
            }),
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
