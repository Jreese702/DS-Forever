using System;
using System.IO;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// THE GAME'S OWN SIGN-IN ON THE PC (the user 2026-11-01: the txt file, not keys: "thats against blizzards tos"): the client reads
    /// login.txt next to its exe at its login screen, as in Local (the Thor's NativeLogin). Written from the Thor's sealed login just
    /// before WoW starts, readable by this user only, gone soon after (WowFlow.LoginFileGoes). Nothing of it is ever logged.
    /// </summary>
    public static class WowLoginFile
    {
        const string Name = "login.txt";

        /// <summary>null = written, else why not</summary>
        public static string Write(string flavorDir, string email, string password)
        {
            try
            {
                var me = new FileSecurity();
                me.SetAccessRuleProtection(true, false);
                me.AddAccessRule(new FileSystemAccessRule(WindowsIdentity.GetCurrent().User, FileSystemRights.FullControl, AccessControlType.Allow));
                var bytes = new UTF8Encoding(false).GetBytes(email + "\n" + password + "\n");
                using (var f = new FileStream(Path.Combine(flavorDir, Name), FileMode.Create, FileSystemRights.Write, FileShare.None, 4096, FileOptions.WriteThrough, me))
                    f.Write(bytes, 0, bytes.Length);
                return null;
            }
            catch (Exception e) { return e.GetType().Name; }
        }

        public static void Delete(string flavorDir)
        {
            try { File.Delete(Path.Combine(flavorDir, Name)); }
            catch (Exception e) { Log.I("login file: not deleted (" + e.GetType().Name + ")"); }
        }
    }
}
