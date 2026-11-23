using System.Linq;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// THE THOR'S REMEMBERED GAME ACCOUNT, AS DESKTOP HEARS IT (the user's pick 2026-11-17, "Yes, remember it": the Thor sends its
    /// remembered list, Config.wtf's accountList with the remembered one marked "!", e.g. "WoW1|!WoW2|", only with "Game account:
    /// Remembered"). Desktop uses it ONLY to find which WTF/Account folder our own settings tables follow (WowSvSync.PickAccount).
    ///  - NOTHING IS WRITTEN INTO THE PC'S Config.wtf (the user's ruling 2026-11-23, "Remove it (Recommended)"): the "!" mark Desktop
    ///    used to put in accountList where the PC had none of its own is gone. WoW's own file is left alone from now on; a mark an
    ///    earlier Desktop wrote is NOT edited either (removing one would be a Config.wtf write of its own: a separate ruling).
    ///  - The value is an account name: it is never logged.
    /// </summary>
    public static class WowAccount
    {
        /// <summary>an accountList value that remembers a pick (one name marked "!"), as the Thor sends it (no quotes, one line)</summary>
        public static bool Valid(string list) =>
            !string.IsNullOrEmpty(list) && list.Length <= 512 && list.IndexOfAny(new[] { '"', '\r', '\n', '\0' }) < 0 && Remembers(list);

        /// <summary>the list marks a remembered game account</summary>
        public static bool Remembers(string value) => (value ?? "").Trim().Trim('"').Split('|').Any(a => a.Trim().StartsWith("!"));
    }
}
