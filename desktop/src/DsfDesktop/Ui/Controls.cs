using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Windows.Forms;

namespace DsfDesktop.Ui
{
    /// <summary>a framed card with a gold title; its content goes in Body</summary>
    sealed class Card : Panel
    {
        public readonly TableLayoutPanel Body;
        public string Title;
        public string Tag2;   // small right-aligned note in the title row (e.g. "1 connected")

        public Card(string title)
        {
            Title = title;
            DoubleBuffered = true;
            SetStyle(ControlStyles.ResizeRedraw | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint, true);
            BackColor = Theme.Ink;
            Padding = new Padding(20, 50, 20, 16);
            Margin = new Padding(8);
            Dock = DockStyle.Fill;
            Body = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, BackColor = Color.Transparent, AutoSize = false };
            Body.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            Controls.Add(Body);
        }

        protected override void OnLayout(LayoutEventArgs e)
        {
            // wrapped text takes the card's whole width (a Note can't know it before the card is laid out)
            if (Body == null) { base.OnLayout(e); return; }
            int w = Math.Max(120, ClientSize.Width - Padding.Horizontal - 8);
            foreach (Control c in Body.Controls)
                if (c is Note n && n.MaximumSize.Width != w) n.MaximumSize = new Size(w, 0);
            base.OnLayout(e);
        }

        public void Add(Control c) { c.Margin = c.Margin == new Padding(3) ? new Padding(0, 0, 0, 6) : c.Margin; Body.Controls.Add(c); }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            Theme.Frame(g, new Rectangle(0, 0, Width - 1, Height - 1));
            g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
            using (var f = Theme.Head(13.5f))
            using (var b = new SolidBrush(Theme.Gold))
                g.DrawString(Title, f, b, 18, 14);
            if (!string.IsNullOrEmpty(Tag2))
                using (var f = Theme.Body(9f))
                using (var b = new SolidBrush(Theme.TextDim))
                {
                    var sz = g.MeasureString(Tag2, f);
                    g.DrawString(Tag2, f, b, Width - 20 - sz.Width, 19);
                }
            using (var pen = new Pen(Color.FromArgb(110, Theme.Bronze), 1f))
                g.DrawLine(pen, 18, 42, Width - 18, 42);
        }
    }

    /// <summary>a WoW-ish button: dark plate with a bronze frame, or (Primary) a gold plate; the gold box cursor on focus</summary>
    sealed class GoldButton : Control
    {
        public bool Primary;
        bool hot, down;

        public GoldButton(string text, bool primary = false)
        {
            Text = text;
            Primary = primary;
            DoubleBuffered = true;
            SetStyle(ControlStyles.Selectable | ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.SupportsTransparentBackColor, true);
            Font = Theme.Body(10f, FontStyle.Bold);
            Cursor = Cursors.Hand;
            BackColor = Color.Transparent;
            Margin = new Padding(0, 4, 10, 4);
            Fit();
        }

        public void Fit()
        {
            using (var g = CreateGraphics())
            {
                var s = TextRenderer.MeasureText(g, Text ?? "", Font);
                Size = new Size(Math.Max(96, s.Width + 34), Primary ? 40 : 34);
            }
        }

        protected override void OnTextChanged(EventArgs e) { base.OnTextChanged(e); Fit(); Invalidate(); }
        protected override void OnMouseEnter(EventArgs e) { hot = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { hot = false; down = false; Invalidate(); base.OnMouseLeave(e); }
        protected override void OnMouseDown(MouseEventArgs e) { down = true; Focus(); Invalidate(); base.OnMouseDown(e); }
        protected override void OnMouseUp(MouseEventArgs e) { down = false; Invalidate(); base.OnMouseUp(e); }
        protected override void OnEnabledChanged(EventArgs e) { Cursor = Enabled ? Cursors.Hand : Cursors.Default; Invalidate(); base.OnEnabledChanged(e); }
        protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
        protected override void OnLostFocus(EventArgs e) { Invalidate(); base.OnLostFocus(e); }
        protected override bool IsInputKey(Keys k) => k == Keys.Enter || k == Keys.Space || base.IsInputKey(k);
        protected override void OnKeyDown(KeyEventArgs e) { if (e.KeyCode == Keys.Enter || e.KeyCode == Keys.Space) OnClick(EventArgs.Empty); base.OnKeyDown(e); }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            var r = new RectangleF(2.5f, 2.5f, Width - 6f, Height - 6f);
            using (var path = Theme.Round(r, 4))
            {
                if (!Enabled)
                {
                    using (var b = new SolidBrush(Theme.Field)) g.FillPath(b, path);
                    using (var p = new Pen(Theme.BronzeDim)) g.DrawPath(p, path);
                }
                else if (Primary)
                {
                    var top = down ? Color.FromArgb(200, 150, 20) : hot ? Color.FromArgb(255, 222, 90) : Color.FromArgb(246, 200, 50);
                    var bottom = down ? Color.FromArgb(150, 100, 10) : Color.FromArgb(176, 122, 24);
                    using (var b = new LinearGradientBrush(Rectangle.Round(r), top, bottom, LinearGradientMode.Vertical)) g.FillPath(b, path);
                    using (var p = new Pen(Color.FromArgb(90, 60, 10), 1.2f)) g.DrawPath(p, path);
                }
                else
                {
                    using (var b = new LinearGradientBrush(Rectangle.Round(r), hot ? Theme.Selected : Color.FromArgb(36, 29, 19), Theme.Field, LinearGradientMode.Vertical)) g.FillPath(b, path);
                    using (var p = new Pen(hot ? Theme.Gold : Theme.Bronze, 1.2f)) g.DrawPath(p, path);
                }
            }
            var color = !Enabled ? Theme.Dim : Primary ? Color.FromArgb(40, 24, 4) : hot ? Theme.Gold : Theme.Text;
            TextRenderer.DrawText(g, Text, Font, new Rectangle(0, down ? 1 : 0, Width, Height), color,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine);
            if (Focused && ShowFocusCues) Theme.BoxCursor(g, new Rectangle(1, 1, Width - 3, Height - 3));
        }
    }

    /// <summary>one status line: a coloured dot, a dim label, a bright value</summary>
    sealed class StatusLine : Control
    {
        public string Label, Value;
        public Color Dot = Theme.Dim;

        public StatusLine(string label)
        {
            Label = label;
            DoubleBuffered = true;
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.SupportsTransparentBackColor | ControlStyles.ResizeRedraw, true);
            BackColor = Color.Transparent;
            Font = Theme.Body(10f);
            Height = 26;
            Dock = DockStyle.Top;
            Margin = new Padding(0, 1, 0, 1);
        }

        public void Set(string value, Color dot)
        {
            if (value == Value && dot == Dot) return;
            Value = value; Dot = dot;
            using (var g = CreateGraphics())
            {
                var w = Math.Max(50, Width - 140);
                var s = TextRenderer.MeasureText(g, value ?? "", Font, new Size(w, 1000), TextFormatFlags.WordBreak);
                Height = Math.Max(26, s.Height + 8);
            }
            Invalidate();
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using (var b = new SolidBrush(Dot)) g.FillEllipse(b, 1, 8, 9, 9);
            using (var lf = Theme.Body(9.5f))
                TextRenderer.DrawText(g, Label, lf, new Rectangle(18, 4, 118, Height), Theme.TextDim, TextFormatFlags.Left | TextFormatFlags.Top);
            TextRenderer.DrawText(g, Value ?? "", Font, new Rectangle(136, 4, Width - 136, Height), Theme.Text, TextFormatFlags.Left | TextFormatFlags.Top | TextFormatFlags.WordBreak);
        }
    }

    /// <summary>plain text in the theme (word-wrapped to its width)</summary>
    sealed class Note : Label
    {
        public Note(string text = "", float size = 9.5f, Color? color = null)
        {
            Text = text;
            AutoSize = true;
            Font = Theme.Body(size);
            ForeColor = color ?? Theme.TextDim;
            BackColor = Color.Transparent;
            Margin = new Padding(0, 2, 0, 6);
        }


    }

    public sealed class DeviceRow
    {
        public string Id, Name, Meta;
        public bool Online, Stream;
    }

    /// <summary>the paired devices: rows drawn in the theme, one selected at a time (then the card's single Remove acts on it)</summary>
    sealed class DeviceList : Control
    {
        List<DeviceRow> rows = new List<DeviceRow>();
        public string SelectedId { get; private set; }
        public event Action SelectionChanged;
        const int RowH = 44;

        public DeviceList()
        {
            DoubleBuffered = true;
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.Selectable | ControlStyles.ResizeRedraw, true);
            BackColor = Theme.Field;
            Height = RowH * 2 + 4;
            Dock = DockStyle.Top;
            Margin = new Padding(0, 4, 0, 8);
        }

        public void SetRows(List<DeviceRow> r)
        {
            rows = r;
            if (SelectedId != null && !rows.Exists(x => x.Id == SelectedId)) { SelectedId = null; SelectionChanged?.Invoke(); }
            Height = RowH * Math.Max(1, Math.Min(4, rows.Count)) + 4;
            Invalidate();
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            int i = (e.Y - 2) / RowH;
            var id = i >= 0 && i < rows.Count ? rows[i].Id : null;
            SelectedId = id == SelectedId ? null : id;
            SelectionChanged?.Invoke();
            Invalidate();
            base.OnMouseDown(e);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using (var p = new Pen(Theme.BronzeDim)) g.DrawRectangle(p, 0, 0, Width - 1, Height - 1);
            if (rows.Count == 0)
            {
                TextRenderer.DrawText(g, "No devices yet: pair one with the code above.", Theme.Body(9.5f), new Rectangle(12, 2, Width - 20, RowH), Theme.Dim, TextFormatFlags.VerticalCenter);
                return;
            }
            for (int i = 0; i < rows.Count && i < 4; i++)
            {
                var r = new Rectangle(2, 2 + i * RowH, Width - 5, RowH - 2);
                var row = rows[i];
                if (row.Id == SelectedId) { using (var b = new SolidBrush(Theme.Selected)) g.FillRectangle(b, r); Theme.BoxCursor(g, Rectangle.Inflate(r, -1, -1)); }
                using (var b = new SolidBrush(row.Online ? Theme.Ok : Theme.Dim)) g.FillEllipse(b, r.Left + 10, r.Top + 16, 9, 9);
                using (var f = Theme.Body(10.5f, FontStyle.Bold))
                    TextRenderer.DrawText(g, row.Name, f, new Rectangle(r.Left + 28, r.Top + 3, r.Width - 150, 20), Theme.Text, TextFormatFlags.Left);
                using (var f = Theme.Body(8.75f))
                    TextRenderer.DrawText(g, row.Meta, f, new Rectangle(r.Left + 28, r.Top + 22, r.Width - 40, 18), Theme.TextDim, TextFormatFlags.Left);
                var tag = row.Online ? "connected" : row.Stream ? "paired" : "stream not paired";
                using (var f = Theme.Body(8.75f, FontStyle.Bold))
                    TextRenderer.DrawText(g, tag, f, new Rectangle(r.Right - 140, r.Top + 4, 130, 18), row.Online ? Theme.Ok : row.Stream ? Theme.SoftGold : Theme.Warn, TextFormatFlags.Right);
            }
        }
    }

    /// <summary>a themed tick box</summary>
    sealed class Toggle : CheckBox
    {
        public Toggle(string text)
        {
            Text = text;
            AutoSize = true;
            ForeColor = Theme.Text;
            BackColor = Color.Transparent;
            Font = Theme.Body(9.75f);
            FlatStyle = FlatStyle.Flat;
            FlatAppearance.BorderColor = Theme.Bronze;
            FlatAppearance.CheckedBackColor = Theme.Selected;
            FlatAppearance.MouseOverBackColor = Theme.Selected;
            Margin = new Padding(0, 6, 22, 6);
            Cursor = Cursors.Hand;
        }
    }

    /// <summary>the tray menu in the same colours</summary>
    sealed class DarkMenuRenderer : ToolStripProfessionalRenderer
    {
        public DarkMenuRenderer() : base(new DarkColors()) { RoundedEdges = false; }
        protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e)
        {
            e.TextColor = e.Item.Enabled ? (e.Item.Selected ? Theme.Gold : Theme.Text) : Theme.Dim;
            base.OnRenderItemText(e);
        }

        sealed class DarkColors : ProfessionalColorTable
        {
            public override Color ToolStripDropDownBackground => Theme.Raised;
            public override Color ImageMarginGradientBegin => Theme.Raised;
            public override Color ImageMarginGradientMiddle => Theme.Raised;
            public override Color ImageMarginGradientEnd => Theme.Raised;
            public override Color MenuBorder => Theme.Bronze;
            public override Color MenuItemBorder => Theme.Gold;
            public override Color MenuItemSelected => Theme.Selected;
            public override Color MenuItemSelectedGradientBegin => Theme.Selected;
            public override Color MenuItemSelectedGradientEnd => Theme.Selected;
            public override Color SeparatorDark => Theme.BronzeDim;
            public override Color SeparatorLight => Theme.BronzeDim;
        }
    }
}
