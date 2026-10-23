using System;
using System.Diagnostics;
using System.Drawing;
using System.Linq;
using System.Windows.Forms;
using DsfDesktop.App;
using DsfDesktop.Wow;

namespace DsfDesktop.Ui
{
    /// <summary>
    /// The status window: the pairing code (with New code), the paired devices (select one, then Remove), the stream and bridge
    /// state, the game folder and its log, the add-ons, Start with Windows and Focus WoW. Refreshed from the controller once a second.
    /// </summary>
    sealed class MainForm : Form
    {
        static readonly Color Ink = Color.FromArgb(32, 33, 36), Muted = Color.FromArgb(104, 106, 112), Accent = Color.FromArgb(176, 122, 24);

        readonly AppController ctl;
        readonly Label code = new Label(), codeHint = new Label();
        readonly ListView devices = new ListView();
        readonly Button remove = new Button(), newCode = new Button(), chooseFolder = new Button(), addons = new Button(), focus = new Button(), testPage = new Button();
        readonly CheckBox autostart = new CheckBox();
        readonly Label sunshine = new Label(), stream = new Label(), bridge = new Label(), game = new Label(), log = new Label(), addonLine = new Label(), notices = new Label();
        readonly Timer tick = new Timer { Interval = 1000 };
        bool reallyClose, asked;

        public MainForm(AppController ctl)
        {
            this.ctl = ctl;
            Text = "DS Forever Desktop";
            Font = new Font("Segoe UI", 10f);
            ForeColor = Ink;
            BackColor = Color.FromArgb(250, 249, 246);
            AutoScaleMode = AutoScaleMode.Dpi;
            ClientSize = new Size(640, 700);
            MinimumSize = new Size(560, 600);
            StartPosition = FormStartPosition.CenterScreen;

            var root = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, Padding = new Padding(18), AutoScroll = true };
            Controls.Add(root);

            // -- pairing
            root.Controls.Add(Heading("Pair a device"));
            code.Font = new Font("Segoe UI Semibold", 30f);
            code.ForeColor = Accent;
            code.AutoSize = true;
            code.Margin = new Padding(0, 0, 0, 0);
            var codeRow = new FlowLayoutPanel { AutoSize = true, WrapContents = false, Margin = new Padding(0) };
            codeRow.Controls.Add(code);
            newCode.Text = "New code";
            newCode.AutoSize = true;
            newCode.Margin = new Padding(18, 18, 0, 0);
            newCode.Click += (s, e) => { ctl.Codes.Refresh(); RefreshAll(); };
            codeRow.Controls.Add(newCode);
            root.Controls.Add(codeRow);
            codeHint.AutoSize = true;
            codeHint.ForeColor = Muted;
            codeHint.MaximumSize = new Size(600, 0);
            root.Controls.Add(codeHint);

            // -- devices
            root.Controls.Add(Heading("Paired devices"));
            devices.View = View.Details;
            devices.FullRowSelect = true;
            devices.MultiSelect = false;
            devices.HideSelection = false;
            devices.Height = 120;
            devices.Dock = DockStyle.Top;
            devices.Columns.Add("Device", 170);
            devices.Columns.Add("Paired", 120);
            devices.Columns.Add("Last seen", 120);
            devices.Columns.Add("Stream", 70);
            devices.Columns.Add("Now", 90);
            devices.SelectedIndexChanged += (s, e) => remove.Enabled = devices.SelectedItems.Count == 1;
            root.Controls.Add(devices);
            remove.Text = "Remove device";
            remove.AutoSize = true;
            remove.Enabled = false;
            remove.Click += (s, e) =>
            {
                if (devices.SelectedItems.Count != 1) return;
                var id = (string)devices.SelectedItems[0].Tag;
                if (MessageBox.Show(this, "Remove " + devices.SelectedItems[0].Text + "? It will need a new code to connect again.", Text, MessageBoxButtons.OKCancel) == DialogResult.OK)
                    ctl.RemoveDevice(id);
                RefreshAll();
            };
            root.Controls.Add(remove);

            // -- status
            root.Controls.Add(Heading("Status"));
            foreach (var l in new[] { sunshine, stream, bridge, game, log, addonLine, notices })
            {
                l.AutoSize = true;
                l.MaximumSize = new Size(600, 0);
                l.Margin = new Padding(0, 2, 0, 2);
                root.Controls.Add(l);
            }
            notices.ForeColor = Color.FromArgb(160, 70, 40);

            var buttons = new FlowLayoutPanel { AutoSize = true, Margin = new Padding(0, 10, 0, 0) };
            chooseFolder.Text = "Choose WoW folder...";
            addons.Text = "Install / update add-ons";
            focus.Text = "Focus WoW";
            testPage.Text = "Bridge test page";
            foreach (var b in new[] { chooseFolder, addons, focus, testPage }) { b.AutoSize = true; buttons.Controls.Add(b); }
            chooseFolder.Click += (s, e) => AskFolder();
            addons.Click += (s, e) =>
            {
                var line = ctl.InstallAddons();
                MessageBox.Show(this, line, Text);
                RefreshAll();
            };
            focus.Click += (s, e) => { var why = ctl.FocusWow(); if (why != null) MessageBox.Show(this, why, Text); };
            testPage.Click += (s, e) => { if (ctl.Bridge != null) Process.Start("http://localhost:" + ctl.Bridge.Port + "/test"); };
            root.Controls.Add(buttons);

            autostart.Text = "Start with Windows";
            autostart.AutoSize = true;
            autostart.Margin = new Padding(0, 12, 0, 0);
            try { autostart.Checked = AutoStart.IsOn(); } catch (Exception) { }
            autostart.CheckedChanged += (s, e) =>
            {
                try { AutoStart.Set(autostart.Checked, ctl.Paths.Exe); }
                catch (Exception ex) { MessageBox.Show(this, "Couldn't change Start with Windows: " + ex.Message, Text); }
            };
            root.Controls.Add(autostart);

            tick.Tick += (s, e) => RefreshAll();
            Shown += (s, e) =>
            {
                RefreshAll();
                tick.Start();
                // the folder question, once, when nothing was found
                if (!asked && ctl.Wow == null && !ctl.Settings.AskedForFolder)
                {
                    asked = true;
                    ctl.Settings.AskedForFolder = true;
                    ctl.SaveSettings();
                    BeginInvoke((Action)AskFolder);
                }
            };
        }

        static Label Heading(string text) => new Label
        {
            Text = text,
            AutoSize = true,
            Font = new Font("Segoe UI Semibold", 12f),
            Margin = new Padding(0, 14, 0, 4),
        };

        void AskFolder()
        {
            using (var d = new FolderBrowserDialog { Description = "Where is World of Warcraft installed? (the folder with _classic_beta_ or _forever_ in it)", ShowNewFolderButton = false })
            {
                if (d.ShowDialog(this) != DialogResult.OK) return;
                if (!ctl.SetWowFolder(d.SelectedPath))
                    MessageBox.Show(this, "That folder doesn't hold WoW Forever (no _classic_beta_ or _forever_ game folder found).", Text);
            }
            RefreshAll();
        }

        public void ReallyClose() { reallyClose = true; tick.Stop(); Close(); }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (reallyClose) return;
            base.OnFormClosing(e);
        }

        static string Ago(long ms)
        {
            if (ms <= 0) return "-";
            var t = DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime;
            return t.Date == DateTime.Today ? t.ToString("HH:mm") : t.ToString("d MMM");
        }

        void RefreshAll()
        {
            if (!Visible) return;
            var codes = ctl.Codes;
            if (codes.Locked)
            {
                code.Text = "paused";
                codeHint.Text = "Pairing paused after too many wrong codes. Press New code to start again.";
            }
            else
            {
                code.Text = codes.Display;
                var r = codes.Remaining;
                codeHint.Text = "Type this code in DS Forever on your Thor (Streaming). A new code comes in " + (int)r.TotalMinutes + ":" + r.Seconds.ToString("00") + ".";
            }

            var online = (ctl.Bridge?.Sessions() ?? new System.Collections.Generic.List<Bridge.BridgeSession>()).Select(s => s.DeviceId).ToList();
            var selected = devices.SelectedItems.Count == 1 ? (string)devices.SelectedItems[0].Tag : null;
            var all = ctl.Devices.All();
            devices.BeginUpdate();
            devices.Items.Clear();
            foreach (var d in all.OrderByDescending(x => x.LastSeenMs))
            {
                var it = new ListViewItem(new[] { d.Name, Ago(d.PairedMs), Ago(d.LastSeenMs), d.StreamPaired ? "yes" : "not yet", online.Contains(d.Id) ? "connected" : "" }) { Tag = d.Id };
                devices.Items.Add(it);
                if (d.Id == selected) it.Selected = true;
            }
            if (all.Count == 0) devices.Items.Add(new ListViewItem("No devices yet") { ForeColor = Muted, Tag = null });
            devices.EndUpdate();
            remove.Enabled = devices.SelectedItems.Count == 1 && devices.SelectedItems[0].Tag != null;

            var sh = ctl.Sunshine;
            sunshine.Text = "Streaming host: " + (sh == null ? "starting" : !sh.Available ? "not installed with this copy (Sunshine missing)" : sh.Running ? "ready (port " + ctl.SunshinePort + ")" : sh.LastStatus);
            stream.Text = "Stream: " + (ctl.Stream == StreamState.Streaming ? "streaming to your device" : ctl.Stream == StreamState.Idle ? "not streaming" : "-");
            bridge.Text = "Companion link: " + ctl.BridgeStatus + (online.Count > 0 ? ", " + online.Count + " connected" : "");
            game.Text = ctl.Wow == null ? "Game: WoW Forever not found" : "Game: " + ctl.Wow.Root + " (" + ctl.Flavor + ")";
            log.Text = "Game log: " + ctl.LogStatus + (ctl.DocsSeen > 0 ? ", " + ctl.DocsSeen + " updates, last " + ctl.LastDocAt?.ToString("HH:mm:ss") : "") + (ctl.Gaps > 0 ? ", " + ctl.Gaps + " gaps" : "");
            addonLine.Text = "Add-ons: " + ctl.AddonLine;
            notices.Text = string.Join(Environment.NewLine, ctl.Notices);
            notices.Visible = ctl.Notices.Count > 0;
        }
    }
}
