using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Threading.Tasks;
using DsfDesktop.App;
using DsfDesktop.Thor;
using DsfDesktop.Wow;

namespace DsfDesktop.Ui
{
    /// <summary>everything the window shows, as one snapshot (built from the controller once a second; the mockups build it by hand)</summary>
    public sealed class ViewState
    {
        public string Version = "";
        public string Code = "", CodeHint = "";
        public bool CodeLocked;
        public List<DeviceRow> Devices = new List<DeviceRow>();
        public int Online;

        public string Host = "", Stream = "", Wow = "", Monitor = "", Link = "";
        public Color HostDot = Theme.Dim, StreamDot = Theme.Dim, WowDot = Theme.Dim, LinkDot = Theme.Dim;
        /// <summary>how to fix what the host line says is wrong (another Sunshine on the ports, ...); "" = nothing wrong</summary>
        public string HostHint = "";
        /// <summary>show "Open Services" (another program holds the streaming ports)</summary>
        public bool HostServices;

        public string Game = "", GameLog = "", Addons = "";
        public Color GameDot = Theme.Dim, LogDot = Theme.Dim, AddonsDot = Theme.Dim;
        public bool AddonsAction;
        public List<string> Notices = new List<string>();

        public ThorStatus Thor = new ThorStatus { Title = "Looking for your Thor...", Detail = "" };
        public string ThorProgress = "";
        public bool ThorBusy;

        public bool StartWithWindows, StartWow, MatchStream;
        public int BridgePort;
    }

    /// <summary>what the window's buttons do (the controller; the mockups pass none)</summary>
    public interface IDesktopActions
    {
        void NewCode();
        void RemoveDevice(string id);
        bool SetWowFolder(string path);
        string InstallAddons();
        string FocusWow();
        void SetStartWithWindows(bool on);
        void SetStartWow(bool on);
        void SetMatchStream(bool on);
        Task<string> ThorInstall();
        Task<string> ThorInstallFile(string apk);
        void ThorWatching(bool on);
        void OpenServices();
        bool AskForFolderOnce();
    }

    static class ViewBuilder
    {
        static string Ago(long ms)
        {
            if (ms <= 0) return "-";
            var t = DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime;
            return t.Date == DateTime.Today ? "today " + t.ToString("HH:mm") : t.ToString("d MMM");
        }

        public static ViewState From(AppController c)
        {
            var v = new ViewState { Version = AppController.Version, BridgePort = c.Bridge?.Port ?? 0 };
            if (c.Codes.Locked) { v.CodeLocked = true; v.Code = "paused"; v.CodeHint = "Too many wrong codes. Press New code to start pairing again."; }
            else
            {
                v.Code = c.Codes.Display;
                var r = c.Codes.Remaining;
                v.CodeHint = "On your Thor: DS Forever > Streaming > enter this code.  A new code in " + (int)r.TotalMinutes + ":" + r.Seconds.ToString("00");
            }
            var online = (c.Bridge?.Sessions() ?? new List<Bridge.BridgeSession>()).Select(s => s.DeviceId).ToList();
            v.Online = online.Distinct().Count();
            v.Devices = c.Devices.All().OrderByDescending(d => d.LastSeenMs).Select(d => new DeviceRow
            {
                Id = d.Id, Name = d.Name, Online = online.Contains(d.Id), Stream = d.StreamPaired,
                Meta = "paired " + Ago(d.PairedMs) + "  ·  last seen " + Ago(d.LastSeenMs),
            }).ToList();

            var sh = c.Sunshine;
            if (sh == null) { v.Host = "starting"; }
            else if (!sh.Available) { v.Host = "missing from this copy (reinstall Desktop)"; v.HostDot = Theme.Bad; }
            else if (sh.Running) { v.Host = "ready on port " + c.SunshinePort; v.HostDot = Theme.Ok; }
            else { v.Host = sh.LastStatus; v.HostDot = Theme.Warn; }
            var pr = c.Ports;
            if (c.HostProblem == "exited")
            {
                v.Host = "stopped on its own: " + sh.LastStatus;
                v.HostHint = "It restarts by itself. If it keeps stopping, restart DS Forever Desktop.";
                v.HostDot = Theme.Bad;
            }
            else if (pr != null && pr.Problem != null)
            {
                v.Host = pr.Line;
                v.HostHint = pr.Hint;
                v.HostServices = pr.Services;
                v.HostDot = Theme.Bad;
            }

            v.Stream = c.Stream == StreamState.Streaming ? "streaming to your Thor" : c.Stream == StreamState.Idle ? "not streaming" : "-";
            v.StreamDot = c.Stream == StreamState.Streaming ? Theme.Gold : Theme.Dim;

            var st = c.WowUp() ? WowState.Running : c.Starter.State;
            v.Wow = st == WowState.Running ? "running" : st == WowState.Starting ? "starting (" + c.Starter.Why + ")" : st == WowState.StartFailed ? "didn't start: " + c.Starter.Why : "not running";
            v.WowDot = st == WowState.Running ? Theme.Ok : st == WowState.Starting ? Theme.Gold : st == WowState.StartFailed ? Theme.Warn : Theme.Dim;
            v.Monitor = c.CaptureStatus.Length > 0 ? c.CaptureStatus : "the primary display";
            v.Link = c.BridgeStatus + (v.Online > 0 ? ", " + v.Online + " connected" : "");
            v.LinkDot = c.Bridge == null ? Theme.Bad : v.Online > 0 ? Theme.Ok : Theme.Dim;

            v.Game = c.Wow == null ? "WoW Forever not found" : c.Wow.Root + "  (" + c.Flavor + ")";
            v.GameDot = c.Wow == null ? Theme.Warn : Theme.Ok;
            v.GameLog = c.LogStatus + (c.DocsSeen > 0 ? ", " + c.DocsSeen + " updates, last " + c.LastDocAt?.ToString("HH:mm:ss") : "") + (c.Gaps > 0 ? ", " + c.Gaps + " gaps" : "");
            v.LogDot = c.DocsSeen > 0 ? Theme.Ok : Theme.Dim;
            v.Addons = c.AddonLine;
            var a = c.Addons?.State;
            v.AddonsDot = a == AddonState.Current ? Theme.Ok : a == AddonState.Missing || a == AddonState.Outdated ? Theme.Warn : Theme.Dim;
            v.AddonsAction = a != null && a != AddonState.NoGame;
            v.Notices = c.Notices.ToList();

            v.Thor = c.Thor.Status;
            v.ThorProgress = c.Thor.Progress;
            v.ThorBusy = c.Thor.Busy;
            try { v.StartWithWindows = AutoStart.IsOn(); } catch (Exception) { }
            v.StartWow = c.Settings.StartWowOnStream;
            v.MatchStream = c.Settings.MatchStreamResolution;
            return v;
        }
    }

    sealed class ControllerActions : IDesktopActions
    {
        readonly AppController c;
        public ControllerActions(AppController c) { this.c = c; }
        public void NewCode() => c.Codes.Refresh();
        public void RemoveDevice(string id) => c.RemoveDevice(id);
        public bool SetWowFolder(string path) => c.SetWowFolder(path);
        public string InstallAddons() => c.InstallAddons();
        public string FocusWow() => c.FocusWow();
        public void SetStartWithWindows(bool on) => AutoStart.Set(on, c.Paths.Exe);
        public void SetStartWow(bool on) { c.Settings.StartWowOnStream = on; c.SaveSettings(); }
        public void SetMatchStream(bool on) => c.SetMatchStream(on);
        public Task<string> ThorInstall() => c.Thor.InstallLatest();
        public Task<string> ThorInstallFile(string apk) => c.Thor.InstallLocal(apk);
        public void ThorWatching(bool on) => c.ThorWatching = on;
        // opens the Services window only; the user stops or disables the other Sunshine there (Desktop never changes a service)
        public void OpenServices() { try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("services.msc") { UseShellExecute = true }); } catch (Exception) { } }
        public bool AskForFolderOnce()
        {
            if (c.Wow != null || c.Settings.AskedForFolder) return false;
            c.Settings.AskedForFolder = true;
            c.SaveSettings();
            return true;
        }
    }
}
