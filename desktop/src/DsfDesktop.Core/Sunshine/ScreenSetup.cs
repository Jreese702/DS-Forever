using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;

namespace DsfDesktop.Sunshine
{
    /// <summary>
    /// The virtual screen's setup in Desktop's own window, QUIET BY DEFAULT (the user's ruling 2026-11-18 "Yes, quiet by default":
    /// "what worries me ... the users who absolutely do not care about this at all"). Shown only when the player turned "Stream on its
    /// own screen" on or no screen is on (<see cref="ScreenPlan.Looks"/>); never a pop-up. It says what the driver is and offers the one
    /// fix there is: SudoVDA disabled -> Turn on (<see cref="SudoVdaTurnOn"/>); no driver -> the MIT Virtual Display Driver
    /// (<see cref="VddInstall"/>); a driver ready -> the headless check (a test of our screen, start at sign-in, the sign-in note).
    /// Pure: the window and the tests read it.
    /// </summary>
    public static class ScreenSetup
    {
        public enum Panel
        {
            /// <summary>a monitor is on and "Stream on its own screen" is off: nothing is shown, looked for or said</summary>
            Hidden,
            /// <summary>looked for, no answer yet</summary>
            Checking,
            /// <summary>a driver can make our screen</summary>
            Ready,
            /// <summary>Apollo's SudoVDA is installed but turned off in Windows: Turn on (never a second driver over it)</summary>
            TurnOn,
            /// <summary>no virtual screen driver: the Virtual Display Driver is offered</summary>
            Offer,
            /// <summary>the player's install is running</summary>
            Installing,
            /// <summary>a driver is installed but its screen isn't ours to use (the player's own Virtual Display Driver monitor)</summary>
            Unusable,
        }

        public static Panel For(bool ownOn, bool hasScreen, bool surveyed, bool installed, bool usable, bool sudoVdaDisabled, bool installing)
        {
            if (!ScreenPlan.Looks(ownOn, hasScreen)) return Panel.Hidden;
            if (usable) return Panel.Ready;
            if (sudoVdaDisabled) return Panel.TurnOn;
            if (installing) return Panel.Installing;
            if (!surveyed) return Panel.Checking;
            if (installed) return Panel.Unusable;
            return Panel.Offer;
        }

        /// <summary>the section's title note: why it is shown</summary>
        public static string Why(bool hasScreen) => hasScreen ? "Stream on its own screen is on" : "No monitor is on";

        /// <summary>the driver line</summary>
        public static string Line(Panel p)
        {
            switch (p)
            {
                case Panel.Checking: return "Looking for a virtual screen driver...";
                case Panel.Ready: return "Virtual screen driver ready";
                case Panel.TurnOn: return "SudoMaker adapter (Apollo's) is turned off";
                case Panel.Offer: return "No virtual screen driver";
                case Panel.Installing: return "Installing the virtual screen driver...";
                case Panel.Unusable: return "Virtual Display Driver's screen is in use";
                default: return "";
            }
        }

        /// <summary>the Virtual Display Driver's offer, said plainly: it adds a screen Windows always shows</summary>
        public const string OfferNote = "Virtual Display Driver (free, open source, MIT) adds a screen that Windows always shows. " +
                                        "Windows asks for admin to install it.";
        public const string TurnOnNote = "Turning it on needs Windows' admin prompt. Nothing new is installed.";
        /// <summary>a headless PC's sign-in: only said, never set up (Desktop never touches passwords or autologon)</summary>
        public const string SignInNote = "A PC with no monitor has to sign in to Windows by itself after a restart, or Desktop can't start. " +
                                         "Set that up in Windows.";
        public const string StartAtSignIn = "Start Desktop when I sign in";

        /// <summary>the test's line (our screen made and removed once, on the player's click)</summary>
        public static string TestLine(string why) => why == null ? "Test passed: a virtual screen was made and removed" : "Test failed: " + why;
    }

    /// <summary>runs a program through Windows' own admin prompt (UAC) and waits; the tests pass a fake</summary>
    public interface IElevatedRunner
    {
        /// <summary>the exit code, or null with [why] (the prompt declined, it didn't start, it took too long)</summary>
        int? Run(string exe, string args, out string why);
    }

    public sealed class WindowsElevatedRunner : IElevatedRunner
    {
        public int? Run(string exe, string args, out string why)
        {
            why = null;
            try
            {
                using (var p = Process.Start(new ProcessStartInfo(exe, args) { UseShellExecute = true, Verb = "runas", WindowStyle = ProcessWindowStyle.Hidden }))
                {
                    if (p == null) { why = "it didn't start"; return null; }
                    if (!p.WaitForExit(120000)) { why = "it took too long"; return null; }
                    return p.ExitCode;
                }
            }
            catch (Win32Exception e) when (e.NativeErrorCode == 1223) { why = "the admin prompt was declined"; return null; }
            catch (Exception e) { why = e.Message; return null; }
        }
    }

    /// <summary>
    /// The window's Turn on for Apollo's SudoVDA adapter disabled in Windows (CM_PROB_DISABLED, the user's PC 2026-11-17): pnputil
    /// /enable-device with the adapter's instance id, found by its hardware id <see cref="SudoVda.HardwareId"/> (never a fixed
    /// ROOT\DISPLAY\0000), through Windows' admin prompt - only on the player's click. Apollo itself never disables or enables it; its
    /// displays are keyed by GUID, so ours beside Apollo's is safe. Nothing else is changed or installed.
    /// </summary>
    public static class SudoVdaTurnOn
    {
        /// <summary>pnputil from System32 by its full path (a 32-bit process on 64-bit Windows: Sysnative)</summary>
        public static string Exe()
        {
            var win = Environment.GetFolderPath(Environment.SpecialFolder.Windows);
            var sys = !Environment.Is64BitProcess && Environment.Is64BitOperatingSystem ? Path.Combine(win, "Sysnative") : Environment.SystemDirectory;
            return Path.Combine(sys, "pnputil.exe");
        }

        /// <summary>a device instance id as Windows writes it (ROOT\DISPLAY\0000): nothing that could break out of the quotes</summary>
        public static bool SafeId(string id) =>
            !string.IsNullOrEmpty(id) && id.Length <= 200 && id.Contains('\\') &&
            id.All(c => (c < 128 && char.IsLetterOrDigit(c)) || c == '\\' || c == '&' || c == '_' || c == '-' || c == '.' || c == '{' || c == '}');

        public static string Args(string id) => "/enable-device \"" + id + "\"";

        /// <summary>pnputil's "done, a restart finishes it" (ERROR_SUCCESS_REBOOT_REQUIRED)</summary>
        public const int RebootRequired = 3010;
        public const string NotFound = "SudoMaker display adapter not found", Restart = "turned on: restart the PC to finish";

        /// <summary>null = turned on; <see cref="Restart"/> = turned on, a restart finishes it; else why not. [instanceId] =
        /// <see cref="SudoVda.AdapterInstanceId"/>; [runner] = Windows' admin prompt (a fake in the tests)</summary>
        public static string Run(Func<string> instanceId, IElevatedRunner runner, string exe = null)
        {
            string id;
            try { id = instanceId(); } catch (Exception) { id = null; }
            if (id == null) return NotFound;
            if (!SafeId(id)) { Log.I("virtual screen: SudoVDA's instance id looks wrong, not used: " + id); return "its device id looks wrong"; }
            exe = exe ?? Exe();
            Log.I("virtual screen: turning SudoVDA on (" + id + ") through Windows' admin prompt (the player's click)");
            var exit = runner.Run(exe, Args(id), out var why);
            if (exit == null) { Log.I("virtual screen: SudoVDA not turned on: " + why); return why; }
            Log.I("virtual screen: pnputil /enable-device exit " + exit.Value);
            if (exit.Value == 0) return null;
            if (exit.Value == RebootRequired) return Restart;
            return "pnputil said " + exit.Value;
        }
    }
}
