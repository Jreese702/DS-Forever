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
        /// <summary>"paused" (too many wrong codes) in place of the boxes</summary>
        readonly Label code = new Label();
        /// <summary>the code as the Thor shows it (design B, the user's pick 2026-11-18): the 6 boxes beside New code, then, quieter,
        /// "Other network" and the network code's boxes (PairAddress) for a Thor that can't see this PC by mDNS</summary>
        readonly CodeBoxes codeBoxes = new CodeBoxes(), otherBoxes = new CodeBoxes();
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
        readonly GoldButton test = new GoldButton("Test");
        readonly Note hostHint = new Note("", 9.25f, Theme.Warn);
        readonly GoldButton services = new GoldButton("Open Services");
        // game + add-ons
        readonly StatusLine game = new StatusLine("Game folder"), gameLog = new StatusLine("Game log"), addons = new StatusLine("Add-ons");
        readonly Note notices = new Note("", 9.5f, Theme.Warn);
        readonly GoldButton addonsBtn = new GoldButton("Install / update add-ons"), folderBtn = new GoldButton("Choose WoW folder...");
        // footer
        readonly Toggle startWindows = new Toggle("Start with Windows"), startWow = new Toggle("Start WoW when streaming"),
            matchStream = new Toggle("Match PC screen to stream"), ownScreen = new Toggle("Stream on its own screen");
        readonly LinkLabel testPage = new LinkLabel();
        // the virtual screen's setup (0.2.37, quiet by default): only with "Stream on its own screen" on or no screen on
        readonly Card vsCard = new Card("Virtual screen");
        readonly StatusLine vsDriver = new StatusLine("Driver");
        readonly GoldButton vsTurnOn = new GoldButton("Turn on", primary: true), vsInstall = new GoldButton("Install driver", primary: true),
            vsTest = new GoldButton("Test virtual screen");
        readonly Note vsFixNote = new Note("", 9f, Theme.TextDim), vsResult = new Note("", 9.25f, Theme.Gold),
            vsSignInNote = new Note(DsfDesktop.Sunshine.ScreenSetup.SignInNote, 9f, Theme.TextDim);
        readonly Toggle vsStartAtSignIn = new Toggle(DsfDesktop.Sunshine.ScreenSetup.StartAtSignIn);
        TableLayoutPanel grid;
        /// <summary>the section's row height, and how much the window grew for it (given back when it hides)</summary>
        const int VsRowHeight = 190;
        static readonly Size BaseMin = new Size(940, 700);
        int vsGrew;
        bool vsRunning;
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
            MinimumSize = BaseMin;
            StartPosition = FormStartPosition.CenterScreen;
            DoubleBuffered = true;

            grid = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 3, Padding = new Padding(10, 6, 10, 0), BackColor = Theme.Ink };
            grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 52));
            grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 48));
            // 57 / 43 (was 54 / 46): the pairing card's code boxes + Other network; the Streaming card had ~60 px spare
            grid.RowStyles.Add(new RowStyle(SizeType.Percent, 57));
            grid.RowStyles.Add(new RowStyle(SizeType.Percent, 43));
            grid.RowStyles.Add(new RowStyle(SizeType.Absolute, 0));

            // Pairing
            code.AutoSize = true;
            code.Font = Theme.Head(24f);
            code.ForeColor = Theme.Gold;
            code.BackColor = Color.Transparent;
            code.Margin = new Padding(0, 8, 18, 0);
            code.Visible = false;
            codeBoxes.MaxBox = 38; codeBoxes.GroupsPerRow = 2; codeBoxes.Anchor = AnchorStyles.Left;
            codeBoxes.FixedWidth = codeBoxes.Width = 270;
            codeBoxes.Margin = new Padding(0, 2, 18, 0);
            var codeRow = new FlowLayoutPanel { AutoSize = true, WrapContents = false, BackColor = Color.Transparent, Margin = new Padding(0) };
            codeRow.Controls.Add(code);
            codeRow.Controls.Add(codeBoxes);
            newCode.Margin = new Padding(0, 12, 0, 0);
            codeRow.Controls.Add(newCode);
            pairCard.Add(codeRow);
            otherBoxes.Quiet = true; otherBoxes.Label = "Other network"; otherBoxes.MaxBox = 24; otherBoxes.MinBox = 18; otherBoxes.GroupsPerRow = 5;
            otherBoxes.Margin = new Padding(0, 2, 0, 2);
            otherBoxes.Visible = false;
            pairCard.Add(otherBoxes);
            codeHint.Margin = new Padding(0, 2, 0, 2);
            pairCard.Add(codeHint);
            // Remove device beside the "Paired devices" heading (it was under the list, and cut off at the minimum window size)
            var devHead = new TableLayoutPanel { AutoSize = true, ColumnCount = 2, RowCount = 1, BackColor = Color.Transparent, Dock = DockStyle.Top,
                                                 Margin = new Padding(0, 2, 0, 0), Padding = new Padding(0) };
            devHead.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            devHead.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            devHead.Controls.Add(new Note("Paired devices", 9.5f, Theme.Bronze) { Font = Theme.Head(10.5f), Anchor = AnchorStyles.Left, Margin = new Padding(0, 4, 0, 0) }, 0, 0);
            remove.Enabled = false;
            remove.Margin = new Padding(8, 0, 0, 0);
            devHead.Controls.Add(remove, 1, 0);
            pairCard.Add(devHead);
            pairCard.Add(devices);

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
            streamCard = new Card("Streaming");
            foreach (var l in new[] { host, stream, wow, monitor, link }) streamCard.Add(l);
            hostHint.Visible = services.Visible = false;
            streamCard.Add(hostHint);
            sb = new FlowLayoutPanel { AutoSize = true, BackColor = Color.Transparent, Margin = new Padding(0, 8, 0, 0) };
            sb.Controls.Add(focus);
            sb.Controls.Add(test);
            sb.Controls.Add(services);
            streamCard.Add(sb);

            // Game + add-ons
            gameCard = new Card("Game and add-ons");
            foreach (var l in new[] { game, gameLog, addons }) gameCard.Add(l);
            gameCard.Add(notices);
            gb = new FlowLayoutPanel { AutoSize = true, BackColor = Color.Transparent, Margin = new Padding(0, 6, 0, 0) };
            gb.Controls.Add(addonsBtn);
            gb.Controls.Add(folderBtn);
            gameCard.Add(gb);

            grid.Controls.Add(pairCard, 0, 0);
            grid.Controls.Add(thorCard, 1, 0);
            grid.Controls.Add(streamCard, 0, 1);
            grid.Controls.Add(gameCard, 1, 1);
            BuildVirtualScreen();
            grid.Controls.Add(vsCard, 0, 2);
            grid.SetColumnSpan(vsCard, 2);

            var footer = new FlowLayoutPanel { Dock = DockStyle.Bottom, Height = 46, Padding = new Padding(22, 6, 18, 0), BackColor = Theme.Ink };
            footer.Controls.Add(startWindows);
            footer.Controls.Add(startWow);
            footer.Controls.Add(matchStream);
            footer.Controls.Add(ownScreen);
            testPage.Text = "Bridge test page";
            testPage.AutoSize = true;
            testPage.LinkColor = Theme.SoftGold;
            testPage.ActiveLinkColor = Theme.Gold;
            testPage.Font = Theme.Body(9.5f);
            testPage.Margin = new Padding(16, 8, 0, 0);
            footer.Controls.Add(testPage);

            Controls.Add(grid);
            Controls.Add(footer);
            Controls.Add(header = new Header(this));

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

        /// <summary>the Virtual screen section: the driver and its one fix on the left, the headless check's sign-in on the right</summary>
        void BuildVirtualScreen()
        {
            vsCard.Visible = false;
            vsCard.Padding = new Padding(20, 50, 20, 8);
            var cols = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 1, BackColor = Color.Transparent, Margin = new Padding(0) };
            cols.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 54));
            cols.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 46));
            var left = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, BackColor = Color.Transparent, Margin = new Padding(0) };
            var right = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, BackColor = Color.Transparent, Margin = new Padding(12, 0, 0, 0) };
            vsDriver.Margin = new Padding(0, 0, 0, 2);
            vsDriver.Dock = DockStyle.None;   // in a flow column Top would take the widest sibling's width, not the column's
            left.Controls.Add(vsDriver);
            // the one fix's button with its note beside it (what it does, said plainly), the last result under them
            var buttons = new FlowLayoutPanel { AutoSize = true, WrapContents = false, BackColor = Color.Transparent, Margin = new Padding(0) };
            foreach (var b in new[] { vsTurnOn, vsInstall, vsTest }) buttons.Controls.Add(b);
            vsFixNote.Margin = new Padding(4, 6, 0, 0);
            buttons.Controls.Add(vsFixNote);
            left.Controls.Add(buttons);
            vsResult.Margin = new Padding(0, 4, 0, 0);
            left.Controls.Add(vsResult);
            var head = new Note("Headless check", 9.5f, Theme.Bronze) { Font = Theme.Head(10.5f), Margin = new Padding(0, 2, 0, 4) };
            right.Controls.Add(head);
            vsStartAtSignIn.Margin = new Padding(0, 0, 0, 4);
            right.Controls.Add(vsStartAtSignIn);
            right.Controls.Add(vsSignInNote);
            // the notes wrap at their column's width (a Note can't know it before the column is laid out)
            left.Resize += (s, e) =>
            {
                int w = Math.Max(120, left.ClientSize.Width - 8);
                vsResult.MaximumSize = new Size(w, 0);
                vsDriver.Width = w;
                vsFixNote.MaximumSize = new Size(Math.Max(120, w - 170), 0);
            };
            right.Resize += (s, e) => { vsSignInNote.MaximumSize = new Size(Math.Max(120, right.ClientSize.Width - 8), 0); };
            cols.Controls.Add(left, 0, 0);
            cols.Controls.Add(right, 1, 0);
            vsCard.Body.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            vsCard.Add(cols);
            cols.Margin = new Padding(0);
        }

        /// <summary>a button of the section: on a worker (Windows' admin prompt, winget, our screen made and removed), then a fresh look</summary>
        async void VsRun(GoldButton b, Func<string> run, string busy)
        {
            if (act == null) return;
            if (vsRunning) return;
            vsRunning = true;
            var was = b.Text;
            vsTurnOn.Enabled = vsInstall.Enabled = vsTest.Enabled = false;
            b.Text = busy;
            try { await System.Threading.Tasks.Task.Run(run); }
            catch (Exception ex) { DsfDesktop.Log.I("virtual screen: " + ex.Message); }
            finally { b.Text = was; vsRunning = false; Pull(); }
        }

        /// <summary>the section shown or hidden: its row, and the window taller by it (given back when it goes) when the screen has room</summary>
        void ShowVirtualScreen(bool on)
        {
            if (vsCard.Visible == on) return;
            vsCard.Visible = on;
            grid.RowStyles[2].Height = on ? VsRowHeight : 0;
            var area = Screen.FromControl(this).WorkingArea;
            if (on)
            {
                if (WindowState == FormWindowState.Normal)
                {
                    int room = Math.Max(0, area.Bottom - Bottom);
                    vsGrew = Math.Min(VsRowHeight, room + Math.Max(0, Top - area.Top));
                    if (vsGrew > room) Top = Math.Max(area.Top, Top - (vsGrew - room));
                    Height += vsGrew;
                }
                // the cards keep their room (never shrunk under the section), as far as the screen allows
                MinimumSize = new Size(BaseMin.Width, Math.Min(BaseMin.Height + VsRowHeight, Math.Max(BaseMin.Height, area.Height)));
            }
            else
            {
                MinimumSize = BaseMin;
                if (WindowState == FormWindowState.Normal && vsGrew > 0) Height -= vsGrew;
                vsGrew = 0;
            }
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
            test.Click += async (s, e) =>
            {
                if (act == null) return;
                test.Enabled = false;
                test.Text = "Testing...";
                try
                {
                    var lines = await System.Threading.Tasks.Task.Run(() => act.RunTest());
                    MessageBox.Show(this, string.Join(Environment.NewLine, lines), "Test");
                }
                catch (Exception ex) { MessageBox.Show(this, "The test failed: " + ex.Message, "Test"); }
                finally { test.Text = "Test"; test.Enabled = true; }
            };
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
            ownScreen.CheckedChanged += (s, e) => { if (!applying) { act?.SetOwnScreen(ownScreen.Checked); Pull(); } };
            vsTurnOn.Click += (s, e) => VsRun(vsTurnOn, () => act.TurnOnSudoVda(), "Turning on...");
            vsInstall.Click += (s, e) => VsRun(vsInstall, () => act.InstallVirtualScreen(), "Starting...");
            vsTest.Click += (s, e) => VsRun(vsTest, () => act.TestOwnScreen(), "Testing...");
            // the same HKCU Run entry as "Start with Windows" (off by default; only the player's click sets it)
            vsStartAtSignIn.CheckedChanged += (s, e) =>
            {
                if (applying || act == null) return;
                try { act.SetStartWithWindows(vsStartAtSignIn.Checked); }
                catch (Exception ex) { MessageBox.Show(this, "Couldn't change " + DsfDesktop.Sunshine.ScreenSetup.StartAtSignIn + ": " + ex.Message, Text); }
                Pull();
            };
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

        Header header;
        string shownUpdate;

        /// <summary>shows a snapshot (also how the mockups are drawn)</summary>

        public void Apply(ViewState s)
        {
            v = s;
            if (header != null && s.Update != shownUpdate) { shownUpdate = s.Update; header.Invalidate(); }
            applying = true;
            SuspendLayout();
            // "482 913 · 100 279": the secret's boxes beside New code, the network code's under "Other network" (PairAddress)
            var cut = s.CodeLocked ? -1 : (s.Code ?? "").IndexOf(" · ", StringComparison.Ordinal);
            code.Text = s.CodeLocked ? s.Code : "";
            code.Visible = s.CodeLocked;
            codeBoxes.Visible = !s.CodeLocked;
            codeBoxes.Digits = s.CodeLocked ? "" : (cut >= 0 ? s.Code.Substring(0, cut) : s.Code ?? "").Replace(" ", "");
            otherBoxes.Digits = cut >= 0 ? s.Code.Substring(cut + 3).Replace(" ", "") : "";
            otherBoxes.Visible = cut >= 0;
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
            ownScreen.Checked = s.OwnScreen;
            ApplyVirtualScreen(s);
            ResumeLayout(true);
            foreach (Control c in new Control[] { pairCard, thorCard }) c.Invalidate();
            applying = false;
        }

        void ApplyVirtualScreen(ViewState s)
        {
            var vs = s.VScreen ?? new VScreenView();
            var P = DsfDesktop.Sunshine.ScreenSetup.Panel.Hidden;
            ShowVirtualScreen(vs.Panel != P);
            if (vs.Panel == P) return;
            vsCard.Tag2 = DsfDesktop.Sunshine.ScreenSetup.Why(!vs.Headless);
            var p = vs.Panel;
            var dot = p == DsfDesktop.Sunshine.ScreenSetup.Panel.Ready ? Theme.Ok : p == DsfDesktop.Sunshine.ScreenSetup.Panel.Checking
                || p == DsfDesktop.Sunshine.ScreenSetup.Panel.Installing ? Theme.Gold : Theme.Warn;
            vsDriver.Set(DsfDesktop.Sunshine.ScreenSetup.Line(p), dot);
            vsTurnOn.Visible = p == DsfDesktop.Sunshine.ScreenSetup.Panel.TurnOn;
            vsInstall.Visible = p == DsfDesktop.Sunshine.ScreenSetup.Panel.Offer;
            vsTest.Visible = p == DsfDesktop.Sunshine.ScreenSetup.Panel.Ready;
            vsTurnOn.Enabled = vsInstall.Enabled = vsTest.Enabled = !vs.Busy && !vsRunning;
            vsFixNote.Text = p == DsfDesktop.Sunshine.ScreenSetup.Panel.TurnOn ? DsfDesktop.Sunshine.ScreenSetup.TurnOnNote
                : p == DsfDesktop.Sunshine.ScreenSetup.Panel.Offer ? DsfDesktop.Sunshine.ScreenSetup.OfferNote : "";
            vsFixNote.Visible = vsFixNote.Text.Length > 0;
            vsResult.Text = vs.Result ?? "";
            vsResult.Visible = vsResult.Text.Length > 0;
            vsStartAtSignIn.Checked = s.StartWithWindows;
            vsCard.Invalidate();
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

        FlowLayoutPanel sb, gb;
        Card streamCard, gameCard;
        bool? compact;

        /// <summary>
        /// Near the minimum size (940 x 700) the four cards are short: the code's boxes a little smaller and the status lines 22 px
        /// instead of 26, so the pairing card keeps its list and Remove device, and Streaming / Game keep their buttons (the pairing
        /// card's 57 % share took ~17 px from them).
        /// </summary>
        void FitHeight()
        {
            bool c = ClientSize.Height < 730;
            if (compact == c) return;
            compact = c;
            SuspendLayout();
            codeBoxes.MaxBox = c ? 34 : 38;
            codeBoxes.Height = codeBoxes.GetPreferredSize(Size.Empty).Height;
            foreach (var l in new[] { host, stream, wow, monitor, link, game, gameLog, addons }) { l.Compact = c; l.Margin = new Padding(0, c ? 0 : 1, 0, c ? 0 : 1); }
            if (sb != null) sb.Margin = new Padding(0, c ? 0 : 8, 0, 0);
            if (gb != null) gb.Margin = new Padding(0, c ? 2 : 6, 0, 0);
            foreach (var card in new[] { pairCard, thorCard, streamCard, gameCard }) if (card != null) card.Padding = new Padding(20, 50, 20, c ? 8 : 16);
            ResumeLayout(true);
        }

        protected override void OnResize(EventArgs e)
        {
            base.OnResize(e);
            FitHeight();
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
                    var up = form.v.Update ?? "";
                    if (up.Length > 0)
                    {
                        bool bad = up.Contains("failed") || up.Contains("can't");
                        bool hot = up.StartsWith("Updating") || up.Contains("ready") || up.Contains("downloading");
                        using (var b = new SolidBrush(bad ? Theme.Warn : hot ? Theme.Gold : Theme.Dim))
                        {
                            var usz = g.MeasureString(up, f);
                            g.DrawString(up, f, b, Width - usz.Width - 26, 40);
                        }
                    }
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
