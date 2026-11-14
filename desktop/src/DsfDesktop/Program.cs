using System;
using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using DsfDesktop.App;

namespace DsfDesktop.Ui
{
    static class Program
    {
        /// <summary>DSF_INSTANCE=&lt;name&gt;: a second, separate instance (a test copy beside the real one: its own mutex and pipe)</summary>
        static readonly string Instance = string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("DSF_INSTANCE")) ? "" : "-" + Environment.GetEnvironmentVariable("DSF_INSTANCE").Trim();
        static readonly string PipeName = "DSForeverDesktop-" + Environment.UserName + Instance;

        /// <summary>
        /// DsForeverDesktop.exe [--tray]           the app (--tray: start hidden, as Start with Windows does)
        /// DsForeverDesktop.exe --apply-update ...  the update helper (DesktopUpdate.Begin starts it)
        /// DsForeverDesktop.exe --event &lt;name&gt;    hands an event to the running app and exits (Sunshine's prep commands:
        ///                                          stream-start / stream-stop)
        /// </summary>
        [STAThread]
        static int Main(string[] args)
        {
            // the update helper (a copy of the new exe in DataDir\update\helper): swaps the files once this app has closed
            if (args.Contains(DsfDesktop.Thor.UpdateHelper.Flag)) return DsfDesktop.Thor.UpdateHelper.Run(args);
            string evt = null;
            int i = Array.IndexOf(args, "--event");
            if (i >= 0 && i + 1 < args.Length) evt = args[i + 1];
            bool tray = args.Contains("--tray");

            using (var mutex = new Mutex(true, @"Local\DSForeverDesktop" + Instance, out bool first))
            {
                if (!first) { Send(evt != null ? "event " + evt : "show"); return 0; }
                if (evt != null) return 0;   // an event with no app running: nothing to tell

                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                var ctl = new AppController(AppPaths.Default());
                var ctx = new TrayContext(ctl, !tray);
                var pipe = new Thread(() => Serve(ctx)) { IsBackground = true, Name = "events" };
                pipe.Start();
                ctl.Start();
                Application.Run(ctx);
                ctl.Dispose();
            }
            return 0;
        }

        static void Send(string line)
        {
            try
            {
                using (var c = new NamedPipeClientStream(".", PipeName, PipeDirection.Out))
                {
                    c.Connect(2000);
                    var b = Encoding.UTF8.GetBytes(line + "\n");
                    c.Write(b, 0, b.Length);
                    c.Flush();
                }
            }
            catch (Exception) { }
        }

        static void Serve(TrayContext ctx)
        {
            while (true)
            {
                try
                {
                    using (var s = new NamedPipeServerStream(PipeName, PipeDirection.In, 1, PipeTransmissionMode.Byte))
                    {
                        s.WaitForConnection();
                        using (var r = new StreamReader(s, Encoding.UTF8))
                        {
                            var line = (r.ReadLine() ?? "").Trim();
                            if (line.Length > 0) ctx.Command(line);
                        }
                    }
                }
                catch (Exception) { Thread.Sleep(500); }
            }
        }
    }
}
