using System;
using Microsoft.Win32;

namespace DsfDesktop.Sunshine
{
    /// <summary>
    /// Issue #54 (1.0.17: "on pc stream I cant use my ayn thor to play, input doesnt work"): the Thor's controller reaches WoW as Sunshine's
    /// virtual Xbox pad, and Sunshine makes that pad with the ViGEmBus driver, which this installer deliberately does not install
    /// (installer/DsForeverDesktop.iss). Without it the picture streams and the controller does nothing, and neither side said so.
    /// Read-only: whether the driver's service is registered (HKLM\SYSTEM\CurrentControlSet\Services\ViGEmBus, readable without admin).
    /// "vigem" = installed, "missing" = not registered, "unknown" = the registry could not be read.
    /// </summary>
    public static class PadDriver
    {
        public const string ServiceKey = @"SYSTEM\CurrentControlSet\Services\ViGEmBus";

        /// <summary>the word for a registry answer: true = the key exists, false = it does not, null = could not read</summary>
        public static string Word(bool? present) => present == null ? "unknown" : present.Value ? "vigem" : "missing";

        static string cached;
        static DateTime cachedAt = DateTime.MinValue;

        /// <summary>the driver's word now (read at most every 30 s: the welcome asks on every Thor connection)</summary>
        public static string Now()
        {
            if (cached != null && (DateTime.UtcNow - cachedAt).TotalSeconds < 30) return cached;
            bool? present;
            try
            {
                using (var k = Registry.LocalMachine.OpenSubKey(ServiceKey)) present = k != null;
            }
            catch (Exception) { present = null; }
            cached = Word(present);
            cachedAt = DateTime.UtcNow;
            return cached;
        }
    }
}
