using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace DsfDesktop.Wow.Patch
{
    /// <summary>a WoW client process as the patch's close sees it: [Path] = its exe, null = it can't be read (elevated, gone)</summary>
    public sealed class WowProc
    {
        public int Pid;
        public string Path;
    }

    /// <summary>
    /// The self-patcher's close of the outdated client (the 0.2.36 review): ONLY a client whose exe is in the _forever_ folder (path
    /// match; never retail, never "the first WoW found"); a client whose path can't be read is never closed (the patch stays "busy"
    /// rather than guess); sign-in checked on THAT process right before the close AND right before the kill (a player who signed
    /// in meanwhile keeps the game). Pure but for the injected pieces, so the tests drive it with retail and _forever_ both open.
    /// </summary>
    public static class ForeverClose
    {
        /// <param name="foreverDir">the _forever_ folder</param>
        /// <param name="list">the WoW client processes now</param>
        /// <param name="signedIn">that process is past its login (char select / the world)</param>
        /// <param name="closeWindow">asks that process's window to close (WM_CLOSE, as its X; never keys)</param>
        /// <param name="waitExit">(pid, ms): true = it is gone</param>
        /// <param name="kill">ends that process: false = couldn't</param>
        /// <returns>null = no _forever_ client left; else why one stays</returns>
        public static string Run(string foreverDir, Func<IList<WowProc>> list, Func<int, bool> signedIn, Action<int> closeWindow,
                                 Func<int, int, bool> waitExit, Func<int, bool> kill, int closeWaitMs = 15000, int killWaitMs = 10000)
        {
            var dir = (foreverDir ?? "").TrimEnd('\\', '/');
            var ps = list() ?? new List<WowProc>();
            var unread = ps.FirstOrDefault(p => string.IsNullOrEmpty(p.Path));
            if (unread != null) return "a WoW client (pid " + unread.Pid + ") whose folder can't be read is open: not closed";
            var mine = ps.Where(p => string.Equals(System.IO.Path.GetDirectoryName(p.Path)?.TrimEnd('\\', '/'), dir, StringComparison.OrdinalIgnoreCase)).ToList();
            foreach (var p in mine)
            {
                if (signedIn(p.Pid)) return "WoW is signed in";
                Log.I("game patch: closing the old client at its login screen (pid " + p.Pid + ")");
                try { closeWindow(p.Pid); } catch (Exception) { }
                if (waitExit(p.Pid, closeWaitMs)) continue;
                if (signedIn(p.Pid)) return "WoW signed in meanwhile";
                Log.I("game patch: the old client still open after " + closeWaitMs / 1000 + " s at its login screen: ending it (pid " + p.Pid + ")");
                if (!kill(p.Pid)) return "couldn't close WoW";
                if (!waitExit(p.Pid, killWaitMs)) return "WoW didn't close";
            }
            return null;
        }
    }
}
