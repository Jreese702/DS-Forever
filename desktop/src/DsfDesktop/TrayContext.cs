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

        public TrayContext(AppController ctl, bool show)
        {
            this.ctl = ctl;
            invoker = new Control();
            invoker.CreateControl();
            form = new MainForm(ctl) { Icon = MakeIcon(32) };
            form.FormClosing += (s, e) =>
            {
                if (e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; form.Hide(); }
            };
            var menu = new ContextMenuStrip();
            menu.Items.Add("Open DS Forever Desktop", null, (s, e) => ShowForm());
            menu.Items.Add("Focus WoW", null, (s, e) => ctl.FocusWow());
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Quit", null, (s, e) => Quit());
            icon = new NotifyIcon { Icon = MakeIcon(16), Text = "DS Forever Desktop", Visible = true, ContextMenuStrip = menu };
            icon.DoubleClick += (s, e) => ShowForm();
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

        /// <summary>the app's mark drawn at run time (no image files shipped): a gold ring with "DS"</summary>
        public static Icon MakeIcon(int size)
        {
            using (var bmp = new Bitmap(size, size))
            using (var g = Graphics.FromImage(bmp))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;
                g.Clear(Color.Transparent);
                using (var bg = new SolidBrush(Color.FromArgb(28, 30, 36))) g.FillEllipse(bg, 0, 0, size - 1, size - 1);
                using (var pen = new Pen(Color.FromArgb(224, 176, 74), Math.Max(1.5f, size / 12f))) g.DrawEllipse(pen, 1, 1, size - 3, size - 3);
                using (var f = new Font("Segoe UI", size * 0.36f, FontStyle.Bold, GraphicsUnit.Pixel))
                using (var fg = new SolidBrush(Color.FromArgb(240, 232, 214)))
                {
                    var sf = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
                    g.DrawString("DS", f, fg, new RectangleF(0, 0, size, size), sf);
                }
                return Icon.FromHandle(bmp.GetHicon());
            }
        }
    }
}
