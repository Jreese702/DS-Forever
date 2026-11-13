using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Windows.Forms;

namespace DsfDesktop.Ui
{
    /// <summary>
    /// The DS Forever Desktop window, in DS Forever's own look (Theme): a header with the mark, four cards (Pairing, Your Thor,
    /// Streaming, Game and add-ons) and the two settings in the footer. It shows a <see cref="ViewState"/> snapshot once a second;
    /// the buttons go to <see cref="IDesktopActions"/>.
    /// </summary>
    sealed class MainForm : Form
    {
        readonly Func<ViewState> state;
        readonly IDesktopActions act;
        ViewState v = new ViewState();
        bool reallyClose, applying;

        // pairing
        readonly Label code = new Label();
        readonly Note codeHint = new Note();
        readonly GoldButton newCode = new GoldButton("New code");
        readonly DeviceList devices = new DeviceList();
        readonly GoldButton remove = new GoldButton("Remove device");
        readonly Card pairCard = new Card("Pair a device");
        // thor
        readonly Card thorCard = new Card("Your Thor");
        readonly Label thorTitle = new Label();
        readonly Note thorDetail = new Note(), thorProgress = new Note("", 9.75f, Theme.Gold);
        readonly GoldButton thorInstall = new GoldButton("Install DS Forever", primary: true);
        readonly GoldButton thorFile = new GoldButton("Install a file...");
        // streaming
        readonly StatusLine host = new StatusLine("Streaming host"), stream = new StatusLine("Stream"), wow = new StatusLine("WoW on this PC"),
                            monitor = new StatusLine("Streamed monitor"), link = new StatusLine("Companion link");
        readonly GoldButton focus = new GoldButton("Focus WoW");
        readonly Note hostHint = new Note("", 9.25f, Theme.Warn);
        readonly GoldButton services = new GoldButton("Open Services");
        // game + add-ons
        readonly StatusLine game = new StatusLine("Game folder"), gameLog = new StatusLine("Game log"), addons = new StatusLine("Add-ons");
        readonly Note notices = new Note("", 9.5f, Theme.Warn);
        readonly GoldButton addonsBtn = new GoldButton("Install / update add-ons"), folderBtn = new GoldButton("Choose WoW folder...");
        // footer
        readonly Toggle startWindows = new Toggle("Start with Windows"), startWow = new Toggle("Start WoW when streaming"),
            matchStream = new Toggle("Match PC screen to stream");
        readonly LinkLabel testPage = new LinkLabel();
        readonly Timer tick = new Timer { Interval = 1000 };

        public MainForm(Func<ViewState> state, IDesktopActions act)
        {
            this.state = state;
            this.act = act;
            Text = "DS Forever Desktop";
            Font = Theme.Body(10f);
            ForeColor = Theme.Text;
            BackColor = Theme.Ink;
            AutoScaleMode = AutoScaleMode.Dpi;
            ClientSize = new Size(1060, 760);
            MinimumSize = new Size(940, 700);
            StartPosition = FormStartPosition.CenterScreen;
            DoubleBuffered = true;

            var grid = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 2, Padding = new Padding(10, 6, 10, 0), BackColor = Theme.Ink };
            grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 52));
            grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 48));
            grid.RowStyles.Add(new RowStyle(SizeType.Percent, 54));
            grid.RowStyles.Add(new RowStyle(SizeType.Percent, 46));

            // Pairing
            code.AutoSize = true;
            code.Font = Theme.Head(38f);
            code.ForeColor = Theme.Gold;
            code.BackColor = Color.Transparent;
            code.Margin = new Padding(0, 0, 18, 0);
            var codeRow = new FlowLayoutPanel { AutoSize = true, WrapContents = false, BackColor = Color.Transparent, Margin = new Padding(0) };
            codeRow.Controls.Add(code);
            newCode.Margin = new Padding(0, 20, 0, 0);
            codeRow.Controls.Add(newCode);
            pairCard.Add(codeRow);
            pairCard.Add(codeHint);
            pairCard.Add(new Note("Paired devices", 9.5f, Theme.Bronze) { Font = Theme.Head(10.5f), Margin = new Padding(0, 6, 0, 0) });
            pairCard.Add(devices);
            remove.Enabled = false;
            pairCard.Add(remove);

            // Thor
            thorTitle.AutoSize = true;
            thorTitle.Font = Theme.Body(14f, FontStyle.Bold);
            thorTitle.ForeColor = Theme.Text;
            thorTitle.BackColor = Color.Transparent;
            thorTitle.Margin = new Padding(0, 4, 0, 2);
            thorCard.Add(thorTitle);
            thorCard.Add(thorDetail);
            thorCard.Add(thorProgress);
            var thorButtons = new FlowLayoutPanel { AutoSize = true, BackColor = Color.Transparent, Margin = new Padding(0, 6, 0, 0) };
            thorButtons.Controls.Add(thorInstall);
            thorButtons.Controls.Add(thorFile);
            thorCard.Add(thorButtons);
            thorCard.Add(new Note("Over the USB cable, from DS Forever's public release (checked against its SHA256SUMS). Your app data stays: " +
                                  "Desktop updates in place and never uninstalls.", 8.75f, Theme.Dim) { Margin = new Padding(0, 10, 0, 0) });

            // Streaming
            var streamCard = new Card("Streaming");
            foreach (var l in new[] { host, stream, wow, monitor, link }) streamCard.Add(l);
            hostHint.Visible = services.Visible = false;
            streamCard.Add(hostHint);
            var sb = new FlowLayoutPanel { AutoSize = true, BackColor = Color.Transparent, Margin = new Padding(0, 8, 0, 0) };
            sb.Controls.Add(focus);
            sb.Controls.Add(services);
            streamCard.Add(sb);

            // Game + add-ons
            var gameCard = new Card("Game and add-ons");
            foreach (var l in new[] { game, gameLog, addons }) gameCard.Add(l);
            gameCard.Add(notices);
            var gb = new FlowLayoutPanel { AutoSize = true, BackColor = Color.Transparent, Margin = new Padding(0, 6, 0, 0) };
            gb.Controls.Add(addonsBtn);
            gb.Controls.Add(folderBtn);
            gameCard.Add(gb);

            grid.Controls.Add(pairCard, 0, 0);
            grid.Controls.Add(thorCard, 1, 0);
            grid.Controls.Add(streamCard, 0, 1);
            grid.Controls.Add(gameCard, 1, 1);

            var footer = new FlowLayoutPanel { Dock = DockStyle.Bottom, Height = 46, Padding = new Padding(22, 6, 18, 0), BackColor = Theme.Ink };
            footer.Controls.Add(startWindows);
            footer.Controls.Add(startWow);
            footer.Controls.Add(matchStream);
            testPage.Text = "Bridge test page";
            testPage.AutoSize = true;
            testPage.LinkColor = Theme.SoftGold;
            testPage.ActiveLinkColor = Theme.Gold;
            testPage.Font = Theme.Body(9.5f);
            testPage.Margin = new Padding(16, 8, 0, 0);
            footer.Controls.Add(testPage);

            Controls.Add(grid);
            Controls.Add(footer);
            Controls.Add(new Header(this));

            Wire();
            tick.Tick += (s, e) => Pull();
            Shown += (s, e) =>
            {
                Pull();
                tick.Start();
                act?.ThorWatching(true);
                if (act != null && act.AskForFolderOnce()) BeginInvoke((Action)AskFolder);
            };
            VisibleChanged += (s, e) => act?.ThorWatching(Visible);
        }

        void Wire()
        {
            newCode.Click += (s, e) => { act?.NewCode(); Pull(); };
            devices.SelectionChanged += () => remove.Enabled = devices.SelectedId != null;
            remove.Click += (s, e) =>
            {
                if (act == null || devices.SelectedId == null) return;
                var row = v.Devices.Find(d => d.Id == devices.SelectedId);
                if (MessageBox.Show(this, "Remove " + row?.Name + "? It needs a new code to connect again.", Text, MessageBoxButtons.OKCancel) == DialogResult.OK)
                    act.RemoveDevice(devices.SelectedId);
                Pull();
            };
            thorInstall.Click += async (s, e) =>
            {
                if (act == null) return;
                thorInstall.Enabled = thorFile.Enabled = false;
                var err = await act.ThorInstall();
                if (err != null) MessageBox.Show(this, err, "DS Forever on your Thor");
                Pull();
            };
            thorFile.Click += async (s, e) =>
            {
                if (act == null) return;
                using (var d = new OpenFileDialog { Title = "Install an APK on the Thor (for testers)", Filter = "Android app (*.apk)|*.apk" })
                {
                    if (d.ShowDialog(this) != DialogResult.OK) return;
                    thorInstall.Enabled = thorFile.Enabled = false;
                    var err = await act.ThorInstallFile(d.FileName);
                    if (err != null) MessageBox.Show(this, err, "DS Forever on your Thor");
                }
                Pull();
            };
            services.Click += (s, e) => act?.OpenServices();
            focus.Click += (s, e) => { var why = act?.FocusWow(); if (why != null) MessageBox.Show(this, why, Text); };
            addonsBtn.Click += (s, e) => { if (act != null) { MessageBox.Show(this, act.InstallAddons(), Text); Pull(); } };
            folderBtn.Click += (s, e) => AskFolder();
            startWindows.CheckedChanged += (s, e) =>
            {
                if (applying || act == null) return;
                try { act.SetStartWithWindows(startWindows.Checked); }
                catch (Exception ex) { MessageBox.Show(this, "Couldn't change Start with Windows: " + ex.Message, Text); }
            };
            startWow.CheckedChanged += (s, e) => { if (!applying) act?.SetStartWow(startWow.Checked); };
            matchStream.CheckedChanged += (s, e) => { if (!applying) act?.SetMatchStream(matchStream.Checked); };
            testPage.LinkClicked += (s, e) => { if (v.BridgePort > 0) Process.Start("http://localhost:" + v.BridgePort + "/test"); };
        }

        void AskFolder()
        {
            if (act == null) return;
            using (var d = new FolderBrowserDialog { Description = "Where is World of Warcraft installed? (the folder with _classic_beta_ or _forever_ in it)", ShowNewFolderButton = false })
            {
                if (d.ShowDialog(this) != DialogResult.OK) return;
                if (!act.SetWowFolder(d.SelectedPath))
                    MessageBox.Show(this, "That folder doesn't hold WoW Forever (no _classic_beta_ or _forever_ game folder found).", Text);
            }
            Pull();
        }

        void Pull()
        {
            if (!Visible && act != null) return;
            try { Apply(state()); } catch (Exception) { }
        }

        /// <summary>shows a snapshot (also how the mockups are drawn)</summary>
        public void Apply(ViewState s)
        {
            v = s;
            applying = true;
            SuspendLayout();
            code.Text = s.Code;
            code.Font = s.CodeLocked ? Theme.Head(24f) : Theme.Head(38f);
            codeHint.Text = s.CodeHint;
            devices.SetRows(s.Devices);
            remove.Enabled = devices.SelectedId != null;
            pairCard.Tag2 = s.Devices.Count == 0 ? "" : s.Online + " of " + s.Devices.Count + " connected";

            var t = s.Thor;
            thorTitle.Text = t.Title ?? "";
            thorDetail.Text = t.Detail ?? "";
            thorProgress.Text = s.ThorProgress ?? "";
            thorProgress.Visible = !string.IsNullOrEmpty(s.ThorProgress);
            thorInstall.Text = t.Button ?? (t.Phase == Thor.ThorPhase.Ready ? "Up to date" : "Install DS Forever");
            thorInstall.Enabled = t.CanInstall && !s.ThorBusy;
            thorFile.Enabled = t.Phase == Thor.ThorPhase.Ready && !s.ThorBusy;
            thorCard.Tag2 = t.Serial != null ? "USB " + t.Serial : "";

            host.Set(s.Host, s.HostDot);
            hostHint.Text = s.HostHint ?? "";
            hostHint.Visible = !string.IsNullOrEmpty(s.HostHint);
            services.Visible = s.HostServices;
            stream.Set(s.Stream, s.StreamDot);
            wow.Set(s.Wow, s.WowDot);
            monitor.Set(s.Monitor, Theme.Dim);
            link.Set(s.Link, s.LinkDot);
            game.Set(s.Game, s.GameDot);
            gameLog.Set(s.GameLog, s.LogDot);
            addons.Set(s.Addons, s.AddonsDot);
            notices.Text = string.Join(Environment.NewLine, s.Notices);
            notices.Visible = s.Notices.Count > 0;
            addonsBtn.Enabled = s.AddonsAction;
            startWindows.Checked = s.StartWithWindows;
            startWow.Checked = s.StartWow;
            matchStream.Checked = s.MatchStream;
            ResumeLayout(true);
            foreach (Control c in new Control[] { pairCard, thorCard }) c.Invalidate();
            applying = false;
        }

        // a dark title bar to match (Windows 10 20H1+ / 11), and on 11 the caption in the header's colour
        [System.Runtime.InteropServices.DllImport("dwmapi.dll")]
        static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

        static int Ref(Color c) => c.R | (c.G << 8) | (c.B << 16);   // a COLORREF is 0x00BBGGRR

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            try
            {
                int on = 1;
                DwmSetWindowAttribute(Handle, 20, ref on, 4);                     // DWMWA_USE_IMMERSIVE_DARK_MODE
                int caption = Ref(Color.FromArgb(30, 24, 15)), text = Ref(Theme.Text), border = Ref(Theme.BronzeDim);
                DwmSetWindowAttribute(Handle, 35, ref caption, 4);                 // DWMWA_CAPTION_COLOR
                DwmSetWindowAttribute(Handle, 36, ref text, 4);                    // DWMWA_TEXT_COLOR
                DwmSetWindowAttribute(Handle, 34, ref border, 4);                  // DWMWA_BORDER_COLOR
            }
            catch (Exception) { }
        }

        public void ReallyClose() { reallyClose = true; tick.Stop(); Close(); }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (reallyClose) return;
            base.OnFormClosing(e);
        }

        /// <summary>the band across the top: the DS Forever mark, the name, what the app is for</summary>
        sealed class Header : Control
        {
            readonly MainForm form;
            public Header(MainForm f)
            {
                form = f;
                Dock = DockStyle.Top;
                Height = 88;
                DoubleBuffered = true;
                SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
            }

            protected override void OnPaint(PaintEventArgs e)
            {
                var g = e.Graphics;
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
                using (var b = new LinearGradientBrush(ClientRectangle, Color.FromArgb(30, 24, 15), Theme.Ink, LinearGradientMode.Vertical)) g.FillRectangle(b, ClientRectangle);
                if (Theme.Mark != null)
                {
                    var r = new Rectangle(24, 14, 60, 60);
                    using (var path = Theme.Round(r, 10)) { g.SetClip(path); g.DrawImage(Theme.Mark, r); g.ResetClip(); using (var p = new Pen(Theme.Bronze, 1.5f)) g.DrawPath(p, path); }
                }
                using (var f = Theme.Head(21f))
                using (var gold = new SolidBrush(Theme.Gold))
                    g.DrawString("DS Forever Desktop", f, gold, 98, 14);
                using (var f = Theme.Body(10f))
                using (var dim = new SolidBrush(Theme.TextDim))
                    g.DrawString("Stream WoW Forever from this PC to your Thor", f, dim, 101, 52);
                using (var f = Theme.Body(9f))
                using (var dim = new SolidBrush(Theme.Dim))
                {
                    var ver = "version " + form.v.Version;
                    var sz = g.MeasureString(ver, f);
                    g.DrawString(ver, f, dim, Width - sz.Width - 26, 20);
                }
                using (var b = new LinearGradientBrush(new Rectangle(0, Height - 2, Width, 2), Color.FromArgb(0, 255, 209, 0), Theme.Gold, LinearGradientMode.Horizontal))
                {
                    var blend = new ColorBlend { Colors = new[] { Color.FromArgb(0, 200, 170, 110), Theme.Bronze, Color.FromArgb(0, 200, 170, 110) }, Positions = new[] { 0f, 0.5f, 1f } };
                    b.InterpolationColors = blend;
                    g.FillRectangle(b, 0, Height - 2, Width, 2);
                }
            }
        }
    }
}
