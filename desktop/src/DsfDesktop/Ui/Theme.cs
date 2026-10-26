using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.IO;
using System.Linq;
using System.Reflection;

namespace DsfDesktop.Ui
{
    /// <summary>
    /// DS Forever's look, from the Thor app's Theme.kt / UiKit: near-black warm ink, raised panels, bronze rules, the game's gold
    /// for headings and the active thing, a gold box frame for focus. Drawn with GDI+: no game art, no extra runtime.
    /// </summary>
    static class Theme
    {
        public static readonly Color Ink = Hex("#0E0B08");        // page ground
        public static readonly Color Field = Hex("#141009");      // recessed areas
        public static readonly Color Panel = Hex("#17120C");      // header
        public static readonly Color Raised = Hex("#1B1610");     // cards
        public static readonly Color Selected = Hex("#2E2411");   // selected row, hovered button
        public static readonly Color Gold = Hex("#FFD100");       // headings, the code, the active thing
        public static readonly Color SoftGold = Color.FromArgb(226, 170, 80);
        public static readonly Color Bronze = Hex("#C8AA6E");     // frames and rules
        public static readonly Color BronzeDim = Hex("#4A3A1C");
        public static readonly Color Text = Hex("#E8DCC0");
        public static readonly Color TextDim = Hex("#B3A48A");
        public static readonly Color Dim = Hex("#8F8168");
        public static readonly Color Warn = Hex("#D99A4E");
        public static readonly Color Ok = Hex("#8FC47A");
        public static readonly Color Bad = Hex("#CC4D38");
        public static readonly Color Swirl = Hex("#7FC4E8");      // the mark's swirl blue (Theme.flight)

        public static Color Hex(string h) => ColorTranslator.FromHtml(h);

        // a book face for headings (Palatino Linotype ships with Windows), Segoe UI for the rest
        static readonly string HeadFamily = new InstalledFontCollection().Families.Any(f => f.Name == "Palatino Linotype") ? "Palatino Linotype" : "Georgia";
        public static Font Head(float size, FontStyle st = FontStyle.Bold) => new Font(HeadFamily, size, st, GraphicsUnit.Point);
        public static Font Body(float size, FontStyle st = FontStyle.Regular) => new Font("Segoe UI", size, st, GraphicsUnit.Point);
        public static Font Mono(float size) => new Font("Bahnschrift SemiBold", size, FontStyle.Regular, GraphicsUnit.Point);

        static Image mark;
        /// <summary>DS Forever's own mark (the app's launcher icon: two screens and the swirl)</summary>
        public static Image Mark
        {
            get
            {
                if (mark != null) return mark;
                try
                {
                    using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("DsfDesktop.mark.png"))
                        if (s != null) return mark = Image.FromStream(new MemoryStream(ReadAll(s)));
                }
                catch (Exception) { }
                return null;
            }
        }

        static byte[] ReadAll(Stream s) { using (var m = new MemoryStream()) { s.CopyTo(m); return m.ToArray(); } }

        public static Icon AppIcon(int size)
        {
            using (var bmp = new Bitmap(size, size))
            using (var g = Graphics.FromImage(bmp))
            {
                g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                g.SmoothingMode = SmoothingMode.AntiAlias;
                if (Mark != null) g.DrawImage(Mark, 0, 0, size, size);
                else { g.Clear(Raised); using (var p = new Pen(Gold, 2)) g.DrawRectangle(p, 1, 1, size - 3, size - 3); }
                return Icon.FromHandle(bmp.GetHicon());
            }
        }

        public static GraphicsPath Round(RectangleF r, float rad)
        {
            var p = new GraphicsPath();
            float d = rad * 2;
            p.AddArc(r.Left, r.Top, d, d, 180, 90);
            p.AddArc(r.Right - d, r.Top, d, d, 270, 90);
            p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
            p.AddArc(r.Left, r.Bottom - d, d, d, 90, 90);
            p.CloseFigure();
            return p;
        }

        /// <summary>the card frame: a dark raised plate, a bronze rule, a faint inner light, and gold corner studs</summary>
        public static void Frame(Graphics g, Rectangle r, bool hot = false)
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            var rf = new RectangleF(r.X + 0.5f, r.Y + 0.5f, r.Width - 1.5f, r.Height - 1.5f);
            using (var path = Round(rf, 6))
            using (var bg = new LinearGradientBrush(r, Color.FromArgb(31, 25, 17), Color.FromArgb(20, 16, 11), LinearGradientMode.Vertical))
            {
                g.FillPath(bg, path);
                using (var pen = new Pen(hot ? Gold : Bronze, hot ? 1.6f : 1.2f)) g.DrawPath(pen, path);
            }
            var inner = RectangleF.Inflate(rf, -3, -3);
            using (var path = Round(inner, 4))
            using (var pen = new Pen(Color.FromArgb(40, 255, 209, 0), 1f)) g.DrawPath(pen, path);
            using (var b = new SolidBrush(hot ? Gold : SoftGold))
            {
                const int s = 5;
                foreach (var p in new[] { new PointF(rf.Left - 1, rf.Top - 1), new PointF(rf.Right - s + 1, rf.Top - 1),
                                          new PointF(rf.Left - 1, rf.Bottom - s + 1), new PointF(rf.Right - s + 1, rf.Bottom - s + 1) })
                    g.FillRectangle(b, p.X, p.Y, s, s);
            }
        }

        /// <summary>the gold box cursor (DS Forever's focus frame): four gold corners around a rectangle</summary>
        public static void BoxCursor(Graphics g, Rectangle r)
        {
            using (var pen = new Pen(Gold, 2f))
            {
                int l = Math.Min(10, Math.Min(r.Width, r.Height) / 3);
                g.DrawLines(pen, new[] { new Point(r.Left, r.Top + l), new Point(r.Left, r.Top), new Point(r.Left + l, r.Top) });
                g.DrawLines(pen, new[] { new Point(r.Right - l, r.Top), new Point(r.Right, r.Top), new Point(r.Right, r.Top + l) });
                g.DrawLines(pen, new[] { new Point(r.Left, r.Bottom - l), new Point(r.Left, r.Bottom), new Point(r.Left + l, r.Bottom) });
                g.DrawLines(pen, new[] { new Point(r.Right - l, r.Bottom), new Point(r.Right, r.Bottom), new Point(r.Right, r.Bottom - l) });
            }
        }
    }
}
