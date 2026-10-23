using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

namespace DsfDesktop.Sunshine
{
    /// <summary>
    /// Runs the bundled sunshine.exe as a plain child process of Desktop: NOT a Windows service, no window (its own tray is off in the
    /// config), started with our config file and stopped with us. A Windows job object with KILL_ON_JOB_CLOSE makes sure it also goes
    /// when Desktop is killed. It is restarted (with a back-off) if it dies on its own.
    /// </summary>
    public sealed class SunshineHost : IDisposable
    {
        readonly SunshineLayout layout;
        readonly object gate = new object();
        Process proc;
        IntPtr job = IntPtr.Zero;
        bool wanted;
        int crashes;
        Timer restartTimer;

        public event Action<string> Status;
        public bool Running { get { lock (gate) return proc != null && !proc.HasExited; } }
        public string LastStatus { get; private set; } = "stopped";

        public SunshineHost(SunshineLayout layout) { this.layout = layout; }

        void Say(string s) { LastStatus = s; Log.I("sunshine: " + s); try { Status?.Invoke(s); } catch (Exception) { } }

        public bool Available => File.Exists(layout.Exe);

        /// <summary>sunshine.exe &lt;conf&gt; --creds user pass: writes the API login into our credentials file, then exits</summary>
        public bool SetCredentials(string user, string pass)
        {
            if (!Available) return false;
            var psi = Psi("\"" + layout.Conf + "\" --creds " + user + " " + pass);
            using (var p = Process.Start(psi))
            {
                p.StandardOutput.ReadToEnd();
                if (!p.WaitForExit(30000)) { try { p.Kill(); } catch (Exception) { } return false; }
            }
            return File.Exists(layout.Credentials);
        }

        ProcessStartInfo Psi(string args) => new ProcessStartInfo(layout.Exe, args)
        {
            // Sunshine resolves assets/ (web files, shaders) against the working directory
            WorkingDirectory = layout.ExeDir,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            RedirectStandardInput = true,
        };

        public void Start()
        {
            lock (gate)
            {
                wanted = true;
                if (proc != null && !proc.HasExited) return;
                if (!Available) { Say("not bundled (" + layout.Exe + " missing)"); return; }
                try
                {
                    var p = new Process { StartInfo = Psi("\"" + layout.Conf + "\""), EnableRaisingEvents = true };
                    p.OutputDataReceived += (s, e) => { };
                    p.ErrorDataReceived += (s, e) => { };
                    p.Exited += OnExited;
                    p.Start();
                    p.BeginOutputReadLine();
                    p.BeginErrorReadLine();
                    AddToJob(p);
                    proc = p;
                    Say("running (pid " + p.Id + ")");
                }
                catch (Exception e) { Say("failed to start: " + e.Message); }
            }
        }

        void OnExited(object sender, EventArgs e)
        {
            int code = -1;
            try { code = ((Process)sender).ExitCode; } catch (Exception) { }
            lock (gate)
            {
                if (!ReferenceEquals(sender, proc)) return;
                proc = null;
                if (!wanted) { Say("stopped"); return; }
                crashes++;
                int delay = Math.Min(60, 2 << Math.Min(crashes, 5));
                Say("exited (code " + code + "), restarting in " + delay + " s");
                restartTimer?.Dispose();
                restartTimer = new Timer(_ => { if (wanted) Start(); }, null, delay * 1000, Timeout.Infinite);
            }
        }

        public void Stop()
        {
            Process p;
            lock (gate)
            {
                wanted = false;
                restartTimer?.Dispose();
                restartTimer = null;
                p = proc;
                proc = null;
            }
            if (p == null) return;
            try
            {
                if (!p.HasExited)
                {
                    // a windowless child has no console to send Ctrl+C to, so it is ended; Sunshine saves its state (the
                    // pairings) when they change, not at exit, so nothing is lost
                    p.Kill();
                    p.WaitForExit(5000);
                }
            }
            catch (Exception) { }
            Say("stopped");
        }

        public void Dispose()
        {
            Stop();
            if (job != IntPtr.Zero) { CloseHandle(job); job = IntPtr.Zero; }
        }

        // ---- the job object: the child dies with us
        void AddToJob(Process p)
        {
            try
            {
                if (job == IntPtr.Zero)
                {
                    job = CreateJobObject(IntPtr.Zero, null);
                    var info = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
                    info.BasicLimitInformation.LimitFlags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
                    int len = Marshal.SizeOf(typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION));
                    var ptr = Marshal.AllocHGlobal(len);
                    try
                    {
                        Marshal.StructureToPtr(info, ptr, false);
                        if (!SetInformationJobObject(job, 9 /* ExtendedLimitInformation */, ptr, (uint)len)) throw new Win32Exception();
                    }
                    finally { Marshal.FreeHGlobal(ptr); }
                }
                AssignProcessToJobObject(job, p.Handle);
            }
            catch (Exception e) { Log.I("job object: " + e.Message); }
        }

        [StructLayout(LayoutKind.Sequential)]
        struct JOBOBJECT_BASIC_LIMIT_INFORMATION
        {
            public long PerProcessUserTimeLimit, PerJobUserTimeLimit;
            public uint LimitFlags;
            public UIntPtr MinimumWorkingSetSize, MaximumWorkingSetSize;
            public uint ActiveProcessLimit;
            public UIntPtr Affinity;
            public uint PriorityClass, SchedulingClass;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct IO_COUNTERS { public ulong a, b, c, d, e, f; }

        [StructLayout(LayoutKind.Sequential)]
        struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION
        {
            public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
            public IO_COUNTERS IoInfo;
            public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern IntPtr CreateJobObject(IntPtr a, string name);
        [DllImport("kernel32.dll", SetLastError = true)] static extern bool SetInformationJobObject(IntPtr job, int cls, IntPtr info, uint len);
        [DllImport("kernel32.dll", SetLastError = true)] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr proc);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
    }
}
