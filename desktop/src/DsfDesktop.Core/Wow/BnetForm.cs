using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// WHERE BATTLE.NET'S SIGN-IN FORM IS (the user's ruling 2026-10-30: the Thor signs in with its saved login). The window exposes no
    /// fields to UI Automation (measured: only Minimize and Close), and it has two layouts: "Welcome back" (a password box) and a fresh
    /// one (email and password), so the form is read from the window's own picture, in memory; nothing is saved. Refusing is safe (the
    /// Thor shows the window and the keyboard), so only what is certain passes: the blue Log in button, the one or two boxes above it,
    /// and the blue eye that only a password box has (a code box has none, and is never typed a password).
    /// </summary>
    public static class BnetForm
    {
        public sealed class Form
        {
            /// <summary>the email box's centre in window pixels; null = "Welcome back", the account is remembered</summary>
            public Point? Email;
            public Point Password;
            public Point Button;
        }

        const int LineMax = 4;      // a box's border is a thin band of rows; the button a thick one
        const int ButtonMin = 24;
        const int BoxMin = 28, BoxMax = 64;

        static bool Bright(int argb) => Math.Max((argb >> 16) & 255, Math.Max((argb >> 8) & 255, argb & 255)) > 70;
        static bool Blue(int argb) => (argb & 255) > 170 && ((argb >> 16) & 255) < 70;

        /// <summary>[px] = the window's pixels (w * h, 0xAARRGGBB); null = not a form this is sure of</summary>
        public static Form Detect(int[] px, int w, int h)
        {
            if (px == null || w < 100 || h < 100 || px.Length < w * h) return null;
            int x0 = w / 10, x1 = w * 9 / 10;
            var lines = new List<int[]>();          // { top, height } of each thin band, top to bottom
            int button = -1, buttonH = 0, start = -1, blue = 0;
            for (int y = 0; y <= h && button < 0; y++)
            {
                int n = 0, b = 0;
                if (y < h) for (int x = x0; x < x1; x++) { int c = px[y * w + x]; if (Bright(c)) { n++; if (Blue(c)) b++; } }
                bool on = y < h && n >= (x1 - x0) * 7 / 10;
                if (on && start < 0) { start = y; blue = b; }
                if (on || start < 0) continue;
                int band = y - start;
                if (band >= ButtonMin && blue >= (x1 - x0) * 7 / 10) { button = start; buttonH = band; }
                else if (band <= LineMax) lines.Add(new[] { start, band });
                else return null;                   // something this does not know
                start = -1;
            }
            if (button < 0) return null;
            var boxes = new List<int[]>();          // { top, bottom }
            for (int i = 0; i + 1 < lines.Count; i++)
            {
                int gap = lines[i + 1][0] - lines[i][0];
                if (gap < BoxMin || gap > BoxMax) continue;
                boxes.Add(new[] { lines[i][0], lines[i + 1][0] });
                i++;
            }
            if (boxes.Count < 1 || boxes.Count > 2) return null;
            var pass = boxes[boxes.Count - 1];
            if (!HasEye(px, w, pass[0], pass[1])) return null;
            int mid = w / 2;
            var f = new Form { Password = new Point(mid, (pass[0] + pass[1]) / 2), Button = new Point(mid, button + buttonH / 2) };
            if (boxes.Count == 2)
            {
                if (HasEye(px, w, boxes[0][0], boxes[0][1])) return null;
                f.Email = new Point(mid, (boxes[0][0] + boxes[0][1]) / 2);
            }
            return f;
        }

        /// <summary>the eye at the box's right end: blue pixels inside the box (a focused box's blue border is not inside it)</summary>
        static bool HasEye(int[] px, int w, int top, int bottom)
        {
            int n = 0;
            for (int y = top + 4; y < bottom - 3; y++)
                for (int x = w * 80 / 100; x < w * 95 / 100; x++) if (Blue(px[y * w + x])) n++;
            return n >= 8;
        }

        /// <summary>the window's pixels now, in memory only; it need not be in front. null = could not read it</summary>
        public static int[] Read(IntPtr hwnd, out int w, out int h)
        {
            w = h = 0;
            if (hwnd == IntPtr.Zero || !GetWindowRect(hwnd, out Displays.RECT r) || r.W <= 0 || r.H <= 0) return null;
            w = r.W; h = r.H;
            try
            {
                using (var b = new Bitmap(w, h, PixelFormat.Format32bppArgb))
                {
                    using (var g = Graphics.FromImage(b))
                    {
                        var dc = g.GetHdc();
                        try { if (!PrintWindow(hwnd, dc, 2 /* PW_RENDERFULLCONTENT */)) return null; }
                        finally { g.ReleaseHdc(dc); }
                    }
                    var d = b.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
                    try
                    {
                        var px = new int[w * h];
                        for (int y = 0; y < h; y++) Marshal.Copy(IntPtr.Add(d.Scan0, y * d.Stride), px, y * w, w);
                        return px;
                    }
                    finally { b.UnlockBits(d); }
                }
            }
            catch (Exception) { return null; }
        }

        /// <summary>a window pixel as the Thor's point: "x,y" as shares of the monitor</summary>
        public static string Share(Point p, Displays.RECT win, Displays.RECT mon)
        {
            var ci = System.Globalization.CultureInfo.InvariantCulture;
            return ((win.Left + p.X - mon.Left) / (double)mon.W).ToString("0.0000", ci) + "," + ((win.Top + p.Y - mon.Top) / (double)mon.H).ToString("0.0000", ci);
        }

        [DllImport("user32.dll")] static extern bool PrintWindow(IntPtr h, IntPtr dc, uint flags);
        [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h, out Displays.RECT r);
    }
}
