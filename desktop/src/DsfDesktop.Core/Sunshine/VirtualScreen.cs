using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using DsfDesktop.Wow;
using Microsoft.Win32.SafeHandles;

namespace DsfDesktop.Sunshine
{
    /// <summary>
    /// What to do about the screen our Sunshine streams. F6 (0.2.31, the user's pick 2026-11-17 "Use one if installed, else offer
    /// ours"): a headless PC whose only screen is Apollo's per-session virtual one. Part 2 (0.2.32, the user's pick 2026-11-17
    /// "Sunshine + own virtual screen"): with "Stream on its own screen" on (the default), EVERY PC streams our own virtual screen at
    /// the Thor's mode; off = the 0.2.31 headless-only rule. Pure.
    /// </summary>
    public static class ScreenPlan
    {
        public enum Step
        {
            /// <summary>a screen exists and our own isn't wanted, or no Thor wants a stream: nothing to do</summary>
            None,
            /// <summary>ours already exists: keep it</summary>
            Keep,
            /// <summary>a driver can make our own virtual screen: make it</summary>
            MakeOurs,
            /// <summary>no driver: ask the player once to install one</summary>
            OfferInstall,
            /// <summary>headless, no driver, already asked: say so</summary>
            NeedsInstall,
            /// <summary>a real screen came back (or no Thor any more): remove ours</summary>
            RemoveOurs,
            /// <summary>own screen wanted but none can be made (no driver, or it failed): stream a real screen, with a one-line note</summary>
            Fallback,
            /// <summary>a make failed a moment ago and no screen to fall back on: wait</summary>
            Wait,
            /// <summary>ours failed <see cref="ScreenLife.MaxFails"/> times this Thor session: a real screen is streamed (or, headless,
            /// the PC says it needs a virtual screen); no more tries until the Thor reconnects or the player's Retry / Test</summary>
            GaveUp,
        }

        /// <summary>the 0.2.31 headless-only rule ("Stream on its own screen" off): [sudoVda] = a driver can make ours</summary>
        public static Step Decide(bool hasScreen, bool sudoVda, bool ours, bool thorWants, bool offered)
            => Decide(false, hasScreen, sudoVda, ours, thorWants, offered);

        /// <summary>
        /// [ownOn] = "Stream on its own screen"; [hasScreen] = a real monitor or a lasting virtual one is on, NOT counting ours or
        /// another host's session screen (Apollo's goes with its client); [driver] = a driver can make ours now; [ours] = our virtual
        /// screen exists; [thorWants] = a Thor is connected (bridge) or streaming, or was within the grace; [offered] = the install
        /// question was asked before (or a driver is installed but can't be used: nothing to offer).
        /// </summary>
        public static Step Decide(bool ownOn, bool hasScreen, bool driver, bool ours, bool thorWants, bool offered)
        {
            if (!thorWants) return ours ? Step.RemoveOurs : Step.None;
            if (ours) return ownOn || !hasScreen ? Step.Keep : Step.RemoveOurs;
            if (hasScreen && !ownOn) return Step.None;
            if (driver) return Step.MakeOurs;
            if (!offered) return Step.OfferInstall;
            return hasScreen ? Step.Fallback : Step.NeedsInstall;
        }

        /// <summary>Decide's [offered]: asked before, a driver installed (usable or not), or SudoVDA there but disabled (enabling it is
        /// the fix: installing a third driver beside it never is)</summary>
        public static bool Offered(bool askedBefore, bool driverInstalled, bool sudoVdaDisabled) => askedBefore || driverInstalled || sudoVdaDisabled;

        /// <summary>the line (and the welcome's problem word, null = none) for Fallback / NeedsInstall: a disabled SudoVDA names its fix</summary>
        public static (string line, string problem) NoDriverLine(bool hasScreen, bool sudoVdaDisabled) =>
            sudoVdaDisabled ? (EnableSudoVda, hasScreen ? null : ProblemScreenDisabled)
            : hasScreen ? (FallbackNote, (string)null) : (Install, ProblemNeedsScreen);

        /// <summary>the install question waits while a Thor is connected or streaming: its window and modal box took the PC's front
        /// from WoW during the sign-in (the user's PC 2026-11-17 22:53, 13 "Windows kept another window in front")</summary>
        public static bool AskNow(bool pending, bool thorConnected, bool streaming) => pending && !thorConnected && !streaming;

        /// <summary>the PC's and the Thor's terse lines</summary>
        public const string Making = "No screen: making one";
        public const string Ready = "Virtual screen ready";
        public const string Install = "Install needed";
        public const string Failed = "No screen: couldn't make one";
        /// <summary>own screen on, a real one there: making ours</summary>
        public const string MakingOwn = "Making the stream's own screen";
        /// <summary>own screen on but none could be made: the real screen is streamed (the one-line note)</summary>
        public const string FallbackNote = "Streaming your screen: no virtual screen driver";
        public const string FallbackFailed = "Streaming your screen: virtual screen failed";
        /// <summary>headless and ours gave up (<see cref="Step.GaveUp"/>)</summary>
        public const string NeedsVirtual = "PC needs a virtual screen";
        /// <summary>the welcome's sunshine.problem words (the Thor's curtain)</summary>
        public const string ProblemNeedsScreen = "needs_screen", ProblemNoScreen = "no_screen";
        public const string ThorNeedsScreen = "pc needs a virtual screen: see Desktop", ThorNoScreen = "pc has no screen on";
        /// <summary>SudoVDA (Apollo's driver) is there but disabled in Windows (<see cref="SudoVda.Disabled"/>): no install is offered,
        /// the player enables it; Desktop never does. The PC's and the Thor's line, and (headless) the welcome's problem word</summary>
        public const string EnableSudoVda = "Enable SudoMaker display adapter in Device Manager";
        public const string ProblemScreenDisabled = "screen_disabled";
        public const string ThorScreenDisabled = "enable SudoMaker display adapter in Device Manager";

        /// <summary>the Thor's stream mode as SudoVDA's: 1920x1080 at 60 unless the Thor said otherwise; sane bounds</summary>
        public static (int w, int h, int hz) Mode(int w, int h, int fps)
        {
            if (w < 640 || w > 7680 || h < 360 || h > 4320) { w = 1920; h = 1080; }
            if (fps < 24 || fps > 240) fps = 60;
            return (w, h, fps);
        }
    }

    /// <summary>
    /// SudoVDA's control interface, from its own header and Apollo's client (cited so it can be checked):
    ///  - SudoMaker/SudoVDA Common/Include/sudovda-ioctl.h (also ClassicOldSong/Apollo third-party/sudovda/sudovda-ioctl.h): the device
    ///    interface GUID {e5bcc234-1e0c-418a-a0d4-ef8b7501414d}; IOCTLs CTL_CODE(FILE_DEVICE_UNKNOWN, 0x800.., METHOD_BUFFERED,
    ///    FILE_ANY_ACCESS): ADD 0x800, REMOVE 0x801, SET_RENDER_ADAPTER 0x802, GET_WATCHDOG 0x803, PING 0x888, GET_PROTOCOL_VERSION 0x8FF;
    ///    ADD's input {UINT Width, Height, RefreshRate (mHz); GUID MonitorGuid; CHAR DeviceName[14]; CHAR SerialNumber[14]} (56 bytes),
    ///    output {LUID AdapterLuid; UINT TargetId} (12); REMOVE's input {GUID MonitorGuid}; GET_WATCHDOG's output {UINT Timeout, Countdown}.
    ///  - Apollo third-party/sudovda/sudovda.h: OpenDevice (SetupDiGetClassDevs DIGCF_PRESENT|DIGCF_DEVICEINTERFACE + CreateFile),
    ///    isProtocolCompatible (same major, driver minor >= ours), GetAddedDisplayName (QueryDisplayConfig, the path whose target id is
    ///    TargetId, then the source's GDI name).
    ///  - SudoVDA's driver: one watchdog for the whole driver (default 3 s, HKLM\SOFTWARE\SudoMaker\SudoVDA\watchdog): any IOCTL resets
    ///    it, and at zero it removes EVERY virtual display. So ours pings about every second while it has a screen; displays are keyed
    ///    by GUID, so REMOVE with ours never touches Apollo's. Its INF gives Everyone read/write on the device: no admin.
    /// Nothing here changes Apollo's screens, its settings or the driver's registry.
    /// </summary>
    public sealed class SudoVda : IDisposable
    {
        public static readonly Guid Interface = new Guid("e5bcc234-1e0c-418a-a0d4-ef8b7501414d");
        public const byte ProtocolMajor = 0, ProtocolMinor = 2;
        static uint Ctl(int fn) => (0x22u << 16) | (0u << 14) | ((uint)fn << 2) | 0u;
        public static readonly uint IoctlAdd = Ctl(0x800), IoctlRemove = Ctl(0x801), IoctlWatchdog = Ctl(0x803), IoctlPing = Ctl(0x888), IoctlVersion = Ctl(0x8FF);

        readonly SafeFileHandle h;
        SudoVda(SafeFileHandle h) { this.h = h; }

        /// <summary>the driver's control device path, null = not installed (or not present)</summary>
        public static string DevicePath()
        {
            var g = Interface;
            var set = SetupDiGetClassDevs(ref g, IntPtr.Zero, IntPtr.Zero, 0x2 /* DIGCF_PRESENT */ | 0x10 /* DIGCF_DEVICEINTERFACE */);
            if (set == IntPtr.Zero || set == new IntPtr(-1)) return null;
            try
            {
                var did = new SP_DEVICE_INTERFACE_DATA { cbSize = Marshal.SizeOf(typeof(SP_DEVICE_INTERFACE_DATA)) };
                if (!SetupDiEnumDeviceInterfaces(set, IntPtr.Zero, ref g, 0, ref did)) return null;
                SetupDiGetDeviceInterfaceDetail(set, ref did, IntPtr.Zero, 0, out int need, IntPtr.Zero);
                if (need <= 0) return null;
                var buf = Marshal.AllocHGlobal(need);
                try
                {
                    Marshal.WriteInt32(buf, IntPtr.Size == 8 ? 8 : 6);   // SP_DEVICE_INTERFACE_DETAIL_DATA_W.cbSize
                    if (!SetupDiGetDeviceInterfaceDetail(set, ref did, buf, need, out _, IntPtr.Zero)) return null;
                    return Marshal.PtrToStringUni(buf + 4);
                }
                finally { Marshal.FreeHGlobal(buf); }
            }
            catch (Exception) { return null; }
            finally { SetupDiDestroyDeviceInfoList(set); }
        }

        public static bool Installed() => DevicePath() != null;

        /// <summary>SudoVDA's hardware id (its INF, as Windows lists the adapter "SudoMaker Virtual Display Adapter")</summary>
        public const string HardwareId = @"root\sudomaker\sudovda";
        /// <summary>the Display adapters class</summary>
        static readonly Guid DisplayClass = new Guid("4d36e968-e325-11ce-bfc1-08002be10318");
        /// <summary>CM_PROB_DISABLED: the device was disabled in Device Manager</summary>
        public const int ProblemDisabled = 22;

        /// <summary>
        /// The SudoVDA adapter's device problem code, read only (SetupAPI + CM_Get_DevNode_Status; nothing is enabled, changed or
        /// installed): 0 = fine, 22 = disabled (the user's PC 2026-11-17: Apollo's adapter there but disabled, so <see cref="DevicePath"/>
        /// found no interface and it read as "not installed"), null = no such adapter
        /// </summary>
        public static int? AdapterProblem()
        {
            var g = DisplayClass;
            var set = SetupDiGetClassDevs(ref g, IntPtr.Zero, IntPtr.Zero, 0x2 /* DIGCF_PRESENT */);
            if (set == IntPtr.Zero || set == new IntPtr(-1)) return null;
            try
            {
                var buf = new byte[4096];
                for (int i = 0; i < 64; i++)
                {
                    var dev = new SP_DEVINFO_DATA { cbSize = Marshal.SizeOf(typeof(SP_DEVINFO_DATA)) };
                    if (!SetupDiEnumDeviceInfo(set, i, ref dev)) break;
                    Array.Clear(buf, 0, buf.Length);
                    if (!SetupDiGetDeviceRegistryProperty(set, ref dev, 1 /* SPDRP_HARDWAREID */, out _, buf, buf.Length, out _)) continue;
                    if (!HasId(Encoding.Unicode.GetString(buf))) continue;
                    if (CM_Get_DevNode_Status(out _, out uint problem, dev.DevInst, 0) != 0) return null;
                    return (int)problem;
                }
                return null;
            }
            catch (Exception) { return null; }
            finally { SetupDiDestroyDeviceInfoList(set); }
        }

        /// <summary>a REG_MULTI_SZ of hardware ids holds SudoVDA's (case-insensitive)</summary>
        public static bool HasId(string multiSz) =>
            (multiSz ?? "").Split('\0').Any(x => string.Equals(x.Trim(), HardwareId, StringComparison.OrdinalIgnoreCase));

        /// <summary>the SudoVDA adapter is in Windows but disabled: nothing to install, the player enables it</summary>
        public static bool Disabled() => AdapterProblem() == ProblemDisabled;

        /// <summary>opens the control device; null with [why] when it can't</summary>
        public static SudoVda Open(out string why)
        {
            why = null;
            var path = DevicePath();
            if (path == null) { why = "not installed"; return null; }
            var fh = CreateFile(path, 0xC0000000 /* GENERIC_READ | GENERIC_WRITE */, 3 /* SHARE_READ | SHARE_WRITE */, IntPtr.Zero, 3 /* OPEN_EXISTING */, 0x80, IntPtr.Zero);
            if (fh.IsInvalid) { why = "open: " + new Win32Exception(Marshal.GetLastWin32Error()).Message; return null; }
            var v = new SudoVda(fh);
            var ver = v.Version();
            if (ver == null) { v.Dispose(); why = "no version answer"; return null; }
            if (!Compatible(ver.Value.major, ver.Value.minor)) { v.Dispose(); why = "protocol " + ver.Value.major + "." + ver.Value.minor + " not supported"; return null; }
            return v;
        }

        public static bool Compatible(byte major, byte minor) => major == ProtocolMajor && minor >= ProtocolMinor;

        bool Io(uint code, byte[] input, byte[] output, out int got)
        {
            got = 0;
            if (h.IsInvalid || h.IsClosed) return false;
            return DeviceIoControl(h, code, input, input?.Length ?? 0, output, output?.Length ?? 0, out got, IntPtr.Zero);
        }

        public (byte major, byte minor, byte incremental, bool test)? Version()
        {
            var o = new byte[4];
            if (!Io(IoctlVersion, null, o, out int n) || n < 4) return null;
            return (o[0], o[1], o[2], o[3] != 0);
        }

        public bool Ping() => Io(IoctlPing, null, null, out _);

        /// <summary>the driver's watchdog (seconds; 0 = off) and its countdown</summary>
        public (uint timeout, uint countdown)? Watchdog()
        {
            var o = new byte[8];
            if (!Io(IoctlWatchdog, null, o, out int n) || n < 8) return null;
            return (BitConverter.ToUInt32(o, 0), BitConverter.ToUInt32(o, 4));
        }

        /// <summary>ADD's input, laid out as sudovda-ioctl.h's VIRTUAL_DISPLAY_ADD_PARAMS (56 bytes, default packing)</summary>
        public static byte[] AddParams(int w, int h, int hz, Guid id, string name, string serial)
        {
            var b = new byte[56];
            BitConverter.GetBytes((uint)w).CopyTo(b, 0);
            BitConverter.GetBytes((uint)h).CopyTo(b, 4);
            BitConverter.GetBytes((uint)hz * 1000u).CopyTo(b, 8);       // millihertz
            id.ToByteArray().CopyTo(b, 12);                              // GUID's in-memory layout (Data1-3 little-endian)
            Ansi(name, 13).CopyTo(b, 28);
            Ansi(serial, 13).CopyTo(b, 42);
            return b;
        }

        static byte[] Ansi(string s, int max) => Encoding.ASCII.GetBytes(new string((s ?? "").Where(c => c >= 0x20 && c < 0x7f).Take(max).ToArray()));

        /// <summary>adds (or, for an id that exists, finds) our display; false with [why]</summary>
        public bool Add(Guid id, int w, int h, int hz, out long adapterLuid, out uint targetId, out string why)
        {
            adapterLuid = 0; targetId = 0; why = null;
            var o = new byte[12];
            if (!Io(IoctlAdd, AddParams(w, h, hz, id, "DS Forever", "DSF0001"), o, out int n) || n < 12)
            {
                why = new Win32Exception(Marshal.GetLastWin32Error()).Message;
                return false;
            }
            adapterLuid = (long)BitConverter.ToUInt32(o, 0) | ((long)BitConverter.ToInt32(o, 4) << 32);
            targetId = BitConverter.ToUInt32(o, 8);
            return true;
        }

        /// <summary>removes OUR display only (by our GUID)</summary>
        public bool Remove(Guid id) => Io(IoctlRemove, id.ToByteArray(), null, out _);

        public void Dispose() { try { h.Dispose(); } catch (Exception) { } }

        /// <summary>"\\.\DISPLAYn" of the added display (QueryDisplayConfig's active path with that target id), null = not yet</summary>
        public static string GdiName(long adapterLuid, uint targetId)
        {
            try
            {
                if (GetDisplayConfigBufferSizes(2 /* QDC_ONLY_ACTIVE_PATHS */, out uint np, out uint nm) != 0) return null;
                var paths = new byte[np * 72];
                var modes = new byte[nm * 64];
                if (QueryDisplayConfig(2, ref np, paths, ref nm, modes, IntPtr.Zero) != 0) return null;
                for (int i = 0; i < np; i++)
                {
                    int at = i * 72;
                    uint tid = BitConverter.ToUInt32(paths, at + 28);         // targetInfo.id (targetInfo at 20, after its LUID)
                    if (tid != targetId) continue;
                    var req = new DISPLAYCONFIG_SOURCE_DEVICE_NAME
                    {
                        type = 1 /* GET_SOURCE_NAME */, size = Marshal.SizeOf(typeof(DISPLAYCONFIG_SOURCE_DEVICE_NAME)),
                        adapterLow = (uint)(adapterLuid & 0xffffffff), adapterHigh = (int)(adapterLuid >> 32),
                        id = BitConverter.ToUInt32(paths, at + 8),                 // sourceInfo.id
                    };
                    if (DisplayConfigGetDeviceInfo(ref req) == 0 && !string.IsNullOrEmpty(req.viewGdiDeviceName)) return req.viewGdiDeviceName;
                }
            }
            catch (Exception) { }
            return null;
        }

        [StructLayout(LayoutKind.Sequential)] struct SP_DEVICE_INTERFACE_DATA { public int cbSize; public Guid InterfaceClassGuid; public int Flags; public IntPtr Reserved; }
        [StructLayout(LayoutKind.Sequential)] struct SP_DEVINFO_DATA { public int cbSize; public Guid ClassGuid; public uint DevInst; public IntPtr Reserved; }
        [DllImport("setupapi.dll", SetLastError = true)] static extern bool SetupDiEnumDeviceInfo(IntPtr set, int index, ref SP_DEVINFO_DATA d);
        [DllImport("setupapi.dll", SetLastError = true, CharSet = CharSet.Unicode, EntryPoint = "SetupDiGetDeviceRegistryPropertyW")]
        static extern bool SetupDiGetDeviceRegistryProperty(IntPtr set, ref SP_DEVINFO_DATA d, int property, out int regType, byte[] buf, int size, out int need);
        [DllImport("cfgmgr32.dll")] static extern int CM_Get_DevNode_Status(out uint status, out uint problem, uint devInst, int flags);
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct DISPLAYCONFIG_SOURCE_DEVICE_NAME
        {
            public int type, size;
            public uint adapterLow; public int adapterHigh;
            public uint id;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string viewGdiDeviceName;
        }
        [DllImport("setupapi.dll", SetLastError = true)] static extern IntPtr SetupDiGetClassDevs(ref Guid g, IntPtr enumerator, IntPtr parent, int flags);
        [DllImport("setupapi.dll", SetLastError = true)] static extern bool SetupDiEnumDeviceInterfaces(IntPtr set, IntPtr info, ref Guid g, int index, ref SP_DEVICE_INTERFACE_DATA d);
        [DllImport("setupapi.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern bool SetupDiGetDeviceInterfaceDetail(IntPtr set, ref SP_DEVICE_INTERFACE_DATA d, IntPtr detail, int size, out int need, IntPtr info);
        [DllImport("setupapi.dll")] static extern bool SetupDiDestroyDeviceInfoList(IntPtr set);
        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern SafeFileHandle CreateFile(string name, uint access, uint share, IntPtr sec, uint disp, uint flags, IntPtr template);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool DeviceIoControl(SafeFileHandle h, uint code, byte[] inBuf, int inSize, byte[] outBuf, int outSize, out int got, IntPtr overlapped);
        [DllImport("user32.dll")] static extern int GetDisplayConfigBufferSizes(uint flags, out uint paths, out uint modes);
        [DllImport("user32.dll")] static extern int QueryDisplayConfig(uint flags, ref uint np, byte[] paths, ref uint nm, byte[] modes, IntPtr topology);
        [DllImport("user32.dll")] static extern int DisplayConfigGetDeviceInfo(ref DISPLAYCONFIG_SOURCE_DEVICE_NAME req);
    }

    /// <summary>
    /// OUR virtual screen made through an installed SudoVDA: added at the Thor's stream mode, pinged while it lives (the driver's
    /// watchdog), removed when our session ends. One at a time; thread-safe. The first driver <see cref="ScreenLife"/> tries.
    /// </summary>
    public sealed class OurScreen : IScreenDriver, IDisposable
    {
        public string Name => "SudoVDA";
        public bool Installed() => SudoVda.Installed();
        /// <summary>SudoVDA keys displays by GUID: ours never touches Apollo's, so an installed one is always usable</summary>
        public bool Usable() => Installed();

        /// <summary>our two GUIDs: a new mode is a new screen under the other one, up beside the old one until WoW is on it</summary>
        readonly Guid id, alt;
        readonly object gate = new object();
        SudoVda dev;
        Timer pinger;
        Guid cur;
        Guid? old;
        public string GdiName { get; private set; }
        public string OldGdiName { get; private set; }
        public (int w, int h, int hz) Mode { get; private set; }
        public bool Exists { get { lock (gate) return dev != null; } }

        public OurScreen(Guid id) { this.id = id; alt = Alt(id); cur = id; }

        /// <summary>our second GUID: [id] with its last bit flipped (the same on every start, so a leftover of either is found)</summary>
        public static Guid Alt(Guid id) { var b = id.ToByteArray(); b[15] ^= 0x01; return new Guid(b); }

        /// <summary>screens left by a Desktop that didn't close cleanly (either of our GUIDs): removed; Apollo's are never touched</summary>
        public static void RemoveLeftover(Guid id)
        {
            using (var d = SudoVda.Open(out _)) { d?.Remove(id); d?.Remove(Alt(id)); }
        }

        /// <summary>makes it; null = done (GdiName set), else why not</summary>
        public string Make(int w, int h, int hz)
        {
            lock (gate)
            {
                if (dev != null) return null;
                var d = SudoVda.Open(out string why);
                if (d == null) return why;
                var wd = d.Watchdog();
                cur = id;
                if (!d.Add(cur, w, h, hz, out long luid, out uint target, out why)) { d.Dispose(); return "add: " + why; }
                dev = d;
                Mode = (w, h, hz);
                // ping well inside the driver's watchdog (its default 3 s: every second)
                int every = wd.HasValue && wd.Value.timeout > 0 ? (int)Math.Max(300, Math.Min(1000, wd.Value.timeout * 1000 / 3)) : 1000;
                pinger = new Timer(_ => { lock (gate) dev?.Ping(); }, null, every, every);
                var name = NameOf(luid, target);
                GdiName = name;
                Log.I("virtual screen: made " + w + "x" + h + "@" + hz + " as " + (name ?? "(no name yet)") + ", watchdog " + (wd.HasValue ? wd.Value.timeout + " s" : "unread") + ", ping " + every + " ms");
                return null;
            }
        }

        static string NameOf(long luid, uint target)
        {
            string name = null;
            for (int ms = 20; ms <= 1280 && name == null; ms *= 2) { Thread.Sleep(ms); name = SudoVda.GdiName(luid, target); }
            return name;
        }

        /// <summary>SudoVDA's mode is fixed at ADD: the new mode is a second screen of ours, the old one kept until <see cref="RetireOld"/></summary>
        public string Resize(int w, int h, int hz)
        {
            lock (gate)
            {
                if (dev == null) return "not made";
                // verifier 2026-11-17: this removed the old screen with WoW maybe still on it. The old one goes only through RetireOld
                if (old.HasValue) return "the old screen is still up";
                var next = cur == id ? alt : id;
                if (!dev.Add(next, w, h, hz, out long luid, out uint target, out string why)) return "add: " + why;
                var name = NameOf(luid, target);
                if (name == null) { dev.Remove(next); return "it didn't appear"; }
                old = cur; OldGdiName = GdiName;
                cur = next; GdiName = name; Mode = (w, h, hz);
                Log.I("virtual screen: made " + w + "x" + h + "@" + hz + " as " + name + " beside the old one (" + (OldGdiName ?? "unnamed") + ")");
                return null;
            }
        }

        public void RetireOld(string why) { lock (gate) RetireLocked(why); }

        void RetireLocked(string why)
        {
            if (dev == null || !old.HasValue) return;
            bool ok = dev.Remove(old.Value);
            Log.I("virtual screen: the old one " + (OldGdiName ?? "") + " removed (" + why + ")" + (ok ? "" : ", the driver didn't answer"));
            old = null; OldGdiName = null;
        }

        public string Remove(string why)
        {
            lock (gate)
            {
                if (dev == null) return null;
                pinger?.Dispose(); pinger = null;
                RetireLocked(why);
                bool ok = dev.Remove(cur);
                dev.Dispose(); dev = null;
                // the driver's watchdog removes it anyway once the pings stop: gone either way
                Log.I("virtual screen: removed (" + why + ")" + (ok ? "" : ", the driver didn't answer"));
                GdiName = null;
                return null;
            }
        }

        public void Dispose() => Remove("Desktop closing");
    }

    /// <summary>
    /// The one-time offer when no virtual display driver is installed: Virtual Display Driver (github.com/VirtualDrivers/
    /// Virtual-Display-Driver, MIT, releases signed through SignPath) through winget, which checks the installer against the hash in its
    /// manifest; Windows' admin prompt is the player's. Only after the player's yes; never silent, never bundled. Checked 2026-11-17:
    /// the id is in microsoft/winget-pkgs (manifests/v/VirtualDrivers/Virtual-Display-Driver, 25.7.23) and is the project README's
    /// own "winget install --id=VirtualDrivers.Virtual-Display-Driver -e". How its monitor is used: <see cref="VddScreen"/>.
    /// </summary>
    public static class VddInstall
    {
        public const string WingetId = "VirtualDrivers.Virtual-Display-Driver";
        public const string ReleasePage = "https://github.com/VirtualDrivers/Virtual-Display-Driver/releases";

        public static string WingetArgs => "install --id " + WingetId + " -e --source winget";

        /// <summary>starts the install in its own window; null = started ([proc] = winget, null when Windows gave none), else why not
        /// (then the release page opens instead)</summary>
        public static string Start() => Start(out _);

        public static string Start(out Process proc)
        {
            proc = null;
            try
            {
                proc = Process.Start(new ProcessStartInfo("winget", WingetArgs) { UseShellExecute = true });
                Log.I("virtual screen: install started (winget " + WingetArgs + ")");
                return null;
            }
            catch (Exception e)
            {
                Log.I("virtual screen: winget not available (" + e.Message + "); opening " + ReleasePage);
                try { Process.Start(new ProcessStartInfo(ReleasePage) { UseShellExecute = true }); } catch (Exception) { }
                return "winget isn't available: the driver's release page is open";
            }
        }
    }
}
