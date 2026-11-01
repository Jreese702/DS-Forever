using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using Microsoft.Win32;

namespace DsfDesktop.Wow
{
    /// <summary>how WoW Forever is started on this PC</summary>
    public sealed class LaunchPlan
    {
        public string Flavor;      // _classic_beta_ / _forever_
        public string Product;     // Battle.net's product uid: wow_classic_beta / wowf
        public string Exe;         // the program started
        public string Args;
        public string WorkDir;
        public bool ViaBattleNet;
        public override string ToString() => "\"" + Exe + "\" " + Args;
    }

    public static class WowLaunch
    {
        /// <summary>Battle.net.exe from its Uninstall key, else its usual folder; null when not installed</summary>
        public static string FindBattleNet()
        {
            foreach (var view in new[] { RegistryView.Registry32, RegistryView.Registry64 })
            {
                try
                {
                    using (var bk = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, view))
                    using (var k = bk.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\Battle.net"))
                    {
                        if (k?.GetValue("InstallLocation") is string loc)
                        {
                            var exe = Path.Combine(loc, "Battle.net.exe");
                            if (File.Exists(exe)) return exe;
                        }
                    }
                }
                catch (Exception) { }
            }
            var pf86 = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86);
            var dflt = Path.Combine(pf86, "Battle.net", "Battle.net.exe");
            return File.Exists(dflt) ? dflt : null;
        }

        public static string DefaultProduct(string flavor) => flavor == WowFlavors.Forever ? "wowf" : flavor == WowFlavors.Beta ? "wow_classic_beta" : null;

        /// <summary>the flavor's game exe: Wow.exe / WowB.exe / WowT.exe, else the first Wow*.exe</summary>
        public static string GameExe(string flavorDir)
        {
            foreach (var n in new[] { "Wow.exe", "WowB.exe", "WowT.exe" })
            {
                var p = Path.Combine(flavorDir, n);
                if (File.Exists(p)) return p;
            }
            try { return Directory.GetFiles(flavorDir, "Wow*.exe").FirstOrDefault(f => !Path.GetFileName(f).StartsWith("WowError", StringComparison.OrdinalIgnoreCase)); }
            catch (Exception) { return null; }
        }

        /// <summary>
        /// The flavor the APK's WowProduct would pick (the beta until 2026-11-04, wowf from then; whichever is installed otherwise),
        /// its product uid from .build.info, then Battle.net's own launch (--exec="launch_uid &lt;uid&gt;") when Battle.net is installed,
        /// else the game exe itself. No credentials are ever involved: the player signs in on the PC.
        /// </summary>
        public static LaunchPlan Plan(WowInstall inst, DateTime today, string battleNet)
        {
            if (inst == null) return null;
            var flavor = WowFlavors.Preferred(today).FirstOrDefault(f => inst.Flavors.Contains(f) && GameExe(inst.FlavorDir(f)) != null)
                         ?? inst.Flavors.FirstOrDefault();
            if (flavor == null) return null;
            var product = BuildInfo.Read(inst.Root).Where(r => WowFlavors.FolderForProduct(r.Product) == flavor).Select(r => r.Product).FirstOrDefault()
                          ?? DefaultProduct(flavor);
            // the game exe ITSELF first (the user's test 2026-10-23: Battle.net's --exec="launch_uid <uid>" only SELECTS the game in its
            // window, "Selecting game by uid ... prioritizeUpdate", and never presses Play); WoW started directly opens its own login
            var exe = GameExe(inst.FlavorDir(flavor));
            // plain, as the Thor's Local does (the user's pick 2026-10-31): WoW's own login screen, the player signs in on the Thor.
            // -launcherlogin only works from Battle.net's own Play, which hands the game a fresh sign-in first (its log 2026-10-31: same
            // arguments, "without a pending launch" from us, then Fatal login error 308 on every start)
            if (exe != null)
                return new LaunchPlan { Flavor = flavor, Product = product, Exe = exe, Args = "", WorkDir = inst.FlavorDir(flavor) };
            if (battleNet != null && product != null)
                return new LaunchPlan { Flavor = flavor, Product = product, Exe = battleNet, Args = "--exec=\"launch_uid " + product + "\"", WorkDir = Path.GetDirectoryName(battleNet), ViaBattleNet = true };
            return null;
        }
    }

    public enum WowState { Stopped, Starting, Running, StartFailed }

    /// <summary>
    /// Starts WoW when a stream wants it, once: while it is starting nothing starts it again; when its window appears it is
    /// focused (the stream then shows the game); if no window comes in <see cref="Timeout"/> the state is StartFailed (the player
    /// may be at Battle.net's sign-in on the PC). The pieces are injected so tests drive it without a game.
    /// </summary>
    public sealed class WowStarter
    {
        readonly Func<bool> windowUp;
        readonly Func<bool> processUp;
        readonly Func<LaunchPlan> plan;
        readonly Func<LaunchPlan, string> start;
        readonly Func<DateTime> now;
        readonly object gate = new object();
        DateTime startedAt;

        public TimeSpan Timeout = TimeSpan.FromMinutes(3);
        public WowState State { get; private set; } = WowState.Stopped;
        public string Why { get; private set; }
        public LaunchPlan LastPlan { get; private set; }
        /// <summary>(new state, why) on every change; Running means the window just appeared</summary>
        public event Action<WowState, string> Changed;

        public WowStarter(Func<bool> windowUp, Func<bool> processUp, Func<LaunchPlan> plan, Func<LaunchPlan, string> start, Func<DateTime> now = null)
        {
            this.windowUp = windowUp; this.processUp = processUp; this.plan = plan; this.start = start;
            this.now = now ?? (() => DateTime.UtcNow);
        }

        public static string StartProcess(LaunchPlan p)
        {
            try
            {
                WowConfig.Ensure(Path.Combine(p.WorkDir ?? "", "WTF", "Config.wtf"));
                // through the shell: the game must not inherit Desktop's handles (2026-10-30: a crashed Wow.exe kept the bridge port 48210)
                Process.Start(new ProcessStartInfo(p.Exe, p.Args) { WorkingDirectory = p.WorkDir, UseShellExecute = true })?.Dispose();
                return null;
            }
            catch (Exception e) { return e.Message; }
        }

        void Set(WowState s, string why)
        {
            if (s == State && why == Why) return;
            State = s; Why = why;
            Log.I("WoW: " + s + (why != null ? " (" + why + ")" : ""));
            try { Changed?.Invoke(s, why); } catch (Exception) { }
        }

        /// <summary>make sure WoW is (or is becoming) up; true when its window is already there</summary>
        public bool Ensure()
        {
            lock (gate)
            {
                if (windowUp()) { Set(WowState.Running, null); return true; }
                if (State == WowState.Starting) return false;
                if (processUp()) { startedAt = now(); Set(WowState.Starting, "the game is loading"); return false; }
                var p = plan();
                LastPlan = p;
                if (p == null) { Set(WowState.StartFailed, "WoW Forever isn't installed on this PC"); return false; }
                var err = start(p);
                if (err != null) { Set(WowState.StartFailed, "couldn't start " + Path.GetFileName(p.Exe) + ": " + err); return false; }
                startedAt = now();
                Set(WowState.Starting, p.ViaBattleNet ? "started through Battle.net (" + p.Product + ")" : "started " + Path.GetFileName(p.Exe));
                return false;
            }
        }

        /// <summary>called every second or so: notices the window appearing, the game closing, the time running out</summary>
        public void Tick()
        {
            lock (gate)
            {
                bool up = windowUp();
                if (up) { if (State != WowState.Running) Set(WowState.Running, null); return; }
                if (State == WowState.Running) { Set(WowState.Stopped, null); return; }
                if (State == WowState.Starting && now() - startedAt > Timeout)
                    Set(WowState.StartFailed, "no WoW window after " + (int)Timeout.TotalSeconds + " s: is Battle.net waiting for you to sign in on the PC?");
            }
        }

        public static string Word(WowState s) => s == WowState.Running ? "running" : s == WowState.Starting ? "starting" : s == WowState.StartFailed ? "start_failed" : "stopped";
    }
}
