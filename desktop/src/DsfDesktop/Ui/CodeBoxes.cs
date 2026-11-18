using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Windows.Forms;

namespace DsfDesktop.Ui
{
    /// <summary>
    /// The pairing code drawn as the Thor shows it (design B, the user's pick 2026-11-18): one box per digit, groups of 3, rows of
    /// <see cref="GroupsPerRow"/> groups, so the player copies box for box. Boxes shrink to the width they get (down to
    /// <see cref="MinBox"/>) before a row wraps. <see cref="Quiet"/> + <see cref="Label"/> = the "Other network" code: smaller, softer
    /// boxes under a globe and the label (the same words and mark as the Thor's).
    /// </summary>
    sealed class CodeBoxes : Control
    {
        string digits = "";
        public int GroupsPerRow = 5;
        public float MaxBox = 34, MinBox = 20;
        public bool Quiet;
        /// <summary>drawn above the boxes with the globe; null = none</summary>
        public string Label;
        /// <summary>a fixed width (the six boxes beside New code); 0 = the width the layout gives</summary>
        public int FixedWidth;

        public CodeBoxes()
        {
            DoubleBuffered = true;
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw |
                     ControlStyles.SupportsTransparentBackColor, true);
            BackColor = Color.Transparent;
            Anchor = AnchorStyles.Left | AnchorStyles.Right;
            Margin = new Padding(0, 4, 0, 6);
        }

        public string Digits
        {
            get => digits;
            set
            {
                value = value ?? "";
                if (value == digits) return;
                digits = value;
                AccessibleName = (Label != null ? Label + ": " : "") + string.Join(" ", digits.ToCharArray());
                var h = HeightFor(Width > 0 ? Width : 400);
                if (Height != h) Height = h;
                Parent?.PerformLayout(this, "Digits");
                Invalidate();
            }
        }

        int Groups => Math.Max(1, (digits.Length + 2) / 3);

        // the row geometry for box size b: the gap between boxes in a group, the gap between groups
        static float Gap(float b) => Math.Max(3, b * 0.14f);
        static float GGap(float b) => b * 0.5f;
        static float RowWidth(int groups, float b) => 3 * groups * b + 2 * groups * Gap(b) + (groups - 1) * GGap(b);

        int perRow = 1;
        float box = 20;

        void Fit(float width)
        {
            perRow = Math.Min(Groups, GroupsPerRow);
            while (true)
            {
                float b = MaxBox;
                while (b > 8 && RowWidth(perRow, b) > width - 2) b -= 0.25f;
                if (b >= MinBox || perRow == 1) { box = b; return; }
                perRow--;
            }
        }

        float BoxH => box * 1.2f;          // the Thor's boxes are 50 x 60 dp
        float RowGap => box * 0.22f;
        const int LabelH = 22;
        float LabelTop => string.IsNullOrEmpty(Label) ? 0 : LabelH;

        int HeightFor(int w)
        {
            Fit(Math.Max(120, FixedWidth > 0 ? FixedWidth : w));
            int rows = (Groups + perRow - 1) / perRow;
            return (int)Math.Ceiling(LabelTop + rows * BoxH + (rows - 1) * RowGap + 2);
        }

        public override Size GetPreferredSize(Size proposed)
        {
            int w = FixedWidth > 0 ? FixedWidth : (proposed.Width > 0 && proposed.Width < 5000 ? proposed.Width : Math.Max(120, Width));
            return new Size(w, HeightFor(w));
        }

        protected override void OnResize(EventArgs e)
        {
            base.OnResize(e);
            var h = HeightFor(Width);
            if (Height != h) Height = h;
        }

        /// <summary>the "Other network" mark (the Thor draws the same): a circle, a meridian, three parallels</summary>
        public static void Globe(Graphics g, RectangleF r, Color c, float w)
        {
            using (var p = new Pen(c, w))
            {
                g.DrawEllipse(p, r);
                g.DrawEllipse(p, r.X + r.Width * 0.28f, r.Y, r.Width * 0.44f, r.Height);
                g.DrawLine(p, r.X, r.Y + r.Height / 2, r.Right, r.Y + r.Height / 2);
                g.DrawLine(p, r.X + r.Width * 0.13f, r.Y + r.Height * 0.25f, r.Right - r.Width * 0.13f, r.Y + r.Height * 0.25f);
                g.DrawLine(p, r.X + r.Width * 0.13f, r.Y + r.Height * 0.75f, r.Right - r.Width * 0.13f, r.Y + r.Height * 0.75f);
            }
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Fit(Math.Max(120, Width));
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
            if (!string.IsNullOrEmpty(Label))
            {
                Globe(g, new RectangleF(1, 3, 14, 14), Theme.TextDim, 1.3f);
                using (var f = Theme.Body(9.5f, FontStyle.Bold)) using (var b = new SolidBrush(Theme.TextDim)) g.DrawString(Label, f, b, 20, 1);
            }
            var stroke = Quiet ? Color.FromArgb(150, Theme.Bronze) : Theme.Bronze;
            var ink = Quiet ? Theme.SoftGold : Theme.Gold;
            using (var font = new Font("Bahnschrift SemiBold", BoxH * 0.52f, FontStyle.Regular, GraphicsUnit.Pixel))
            using (var sf = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center })
            using (var pen = new Pen(stroke, Quiet ? 1f : 1.3f))
            using (var bg = new SolidBrush(Color.FromArgb(42, 36, 28)))   // the Thor's box colour (StreamLook.BOX)
            using (var tb = new SolidBrush(ink))
            {
                int gi = 0;
                for (int i = 0; i < digits.Length; i += 3, gi++)
                {
                    int row = gi / perRow, col = gi % perRow;
                    float x = col * (3 * box + 2 * Gap(box) + GGap(box));
                    float y = LabelTop + row * (BoxH + RowGap);
                    for (int k = 0; k < 3 && i + k < digits.Length; k++)
                    {
                        var r = new RectangleF(x + k * (box + Gap(box)) + 0.5f, y + 0.5f, box - 1, BoxH - 1);
                        using (var path = Theme.Round(r, Math.Max(3, box * 0.2f))) { g.FillPath(bg, path); g.DrawPath(pen, path); }
                        g.DrawString(digits[i + k].ToString(), font, tb, new RectangleF(r.X, r.Y + 1, r.Width, r.Height), sf);
                    }
                }
            }
        }
    }
}
