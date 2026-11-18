using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using DsfDesktop.App;

namespace DsfDesktop.Ui
{
    /// <summary>the tray icon (Open, Focus WoW, Quit) and the one status window; closing the window keeps Desktop running in the tray</summary>
    sealed class TrayContext : ApplicationContext
    {
        readonly AppController ctl;
        readonly NotifyIcon icon;
        readonly MainForm form;
        readonly Control invoker;
        bool said;

        public TrayContext(AppController ctl, bool show)
        {
            this.ctl = ctl;
            invoker = new Control();
            invoker.CreateControl();
            form = new MainForm(() => ViewBuilder.From(ctl), new ControllerActions(ctl)) { Icon = Theme.AppIcon(32) };
            form.FormClosing += (s, e) =>
            {
                if (e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; form.Hide(); }
            };
            // minimise hides to the tray (the user, 2026-10-26): no taskbar button while minimised; the tray icon brings it back
            form.Resize += (s, e) => { if (form.WindowState == FormWindowState.Minimized) form.Hide(); };
            var menu = new ContextMenuStrip { Renderer = new DarkMenuRenderer(), Font = Theme.Body(9.75f), ShowImageMargin = false };
            menu.Items.Add("Open DS Forever Desktop", null, (s, e) => ShowForm());
            menu.Items.Add("Focus WoW", null, (s, e) => ctl.FocusWow());
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Close DS Forever Desktop", null, (s, e) => Quit());
            icon = new NotifyIcon { Icon = Theme.AppIcon(16), Text = "DS Forever Desktop", Visible = true, ContextMenuStrip = menu };
            icon.DoubleClick += (s, e) => ShowForm();
            icon.MouseClick += (s, e) => { if (e.Button == MouseButtons.Left) ShowForm(); };
            // the tray's hover text says what Desktop is doing
            var hover = new Timer { Interval = 3000 };
            hover.Tick += (s, e) =>
            {
                var note = ctl.TakeUpdateNote();
                if (note != null) icon.ShowBalloonTip(4000, "DS Forever Desktop", note, ToolTipIcon.None);
                bool updating = ctl.UpdateLine.StartsWith("Updating to");
                if (updating && !said) { said = true; icon.ShowBalloonTip(3000, "DS Forever Desktop", ctl.UpdateLine, ToolTipIcon.None); }
                if (ctl.StartPendingUpdate()) { hover.Stop(); Quit(); return; }
                if (ctl.TakeScreenOffer()) AskScreen();
                if (!updating) said = false;
                var t = updating ? "updating" : ctl.Stream == App.StreamState.Streaming ? "streaming to your Thor" : (ctl.Bridge?.Sessions().Count ?? 0) > 0 ? "your Thor is connected" : "ready";
                icon.Text = "DS Forever Desktop: " + t;
            };
            hover.Start();
            if (show) ShowForm();
        }

        /// <summary>a line from another instance: "show", or "event &lt;name&gt;" from Sunshine's prep commands</summary>
        public void Command(string line)
        {
            invoker.BeginInvoke((Action)(() =>
            {
                if (line == "show") ShowForm();
                else if (line.StartsWith("event ")) ctl.OnEvent(line.Substring(6).Trim());
            }));
        }

        /// <summary>F6 / Part 2: no virtual display driver (a headless PC, or the stream's own screen): asked once; only a yes installs
        /// (Windows asks for admin itself, once)</summary>
        void AskScreen()
        {
            ShowForm();
            var why = ctl.ScreenOfferHeadless ? "No screen is on at this PC, so there is nothing to stream.\n\n"
                : "The stream gets its own screen, so WoW leaves your desktop. That needs a virtual screen.\n\n";
            var yes = MessageBox.Show(form,
                why + "Install a virtual screen? (Virtual Display Driver: free, open source, MIT.) Windows asks for admin to install it.",
                "DS Forever Desktop", MessageBoxButtons.YesNo, MessageBoxIcon.Question) == DialogResult.Yes;
            DsfDesktop.Log.I("virtual screen: install " + (yes ? "accepted" : "declined"));
            if (!yes) return;
            var err = ctl.InstallVirtualScreen();
            if (err != null) MessageBox.Show(form, err, "DS Forever Desktop");
        }

        void ShowForm()
        {
            form.Show();
            if (form.WindowState == FormWindowState.Minimized) form.WindowState = FormWindowState.Normal;
            form.Activate();
        }

        void Quit()
        {
            icon.Visible = false;
            form.ReallyClose();
            ExitThread();
        }
    }
}
