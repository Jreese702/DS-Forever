using System;
using System.Net.Sockets;
using System.Runtime.InteropServices;

namespace DsfDesktop.Bridge
{
    /// <summary>
    /// Desktop's own sockets stay Desktop's (issue #414, "Zombie sunshine.exe after a failed session holds the ports"): .NET Framework
    /// makes every socket inheritable, and Process.Start with redirected output hands EVERY inheritable handle to the child. So the
    /// bundled sunshine.exe held Desktop's bridge listener (48210) and its mDNS socket too; when a sunshine.exe hung on its way out
    /// after a failed stream, the bridge port stayed taken for minutes, the next Desktop opened 48211 and the Thor kept dialling 48210.
    /// (Wow.exe is already started through the shell for the same reason: WowStarter, 2026-10-30.) Each socket is marked
    /// non-inheritable the moment Desktop has it.
    /// </summary>
    public static class SocketHandles
    {
        const uint HANDLE_FLAG_INHERIT = 0x1;

        [DllImport("kernel32.dll", SetLastError = true)] static extern bool SetHandleInformation(IntPtr h, uint mask, uint flags);
        [DllImport("kernel32.dll", SetLastError = true)] static extern bool GetHandleInformation(IntPtr h, out uint flags);

        /// <summary>no child process gets [s]; false when Windows refused (the socket works as before)</summary>
        public static bool KeepToSelf(Socket s)
        {
            try { return s != null && SetHandleInformation(s.Handle, HANDLE_FLAG_INHERIT, 0); }
            catch (Exception) { return false; }
        }

        /// <summary>would a child process started now get [s]? (tests and diagnostics)</summary>
        public static bool Inheritable(Socket s)
        {
            try { return s != null && GetHandleInformation(s.Handle, out var f) && (f & HANDLE_FLAG_INHERIT) != 0; }
            catch (Exception) { return false; }
        }
    }
}
