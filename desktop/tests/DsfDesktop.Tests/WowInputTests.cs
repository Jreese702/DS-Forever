using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using DsfDesktop.Wow;

namespace DsfDesktop.Tests
{
    /// <summary>
    /// Issues #304 / #357 (the user's pick, 2026-11-16): Desktop says when the Thor's taps can't reach WoW - WoW run as administrator
    /// (Windows drops Sunshine's mouse and keys on it: UIPI), or WoW's client area not filling the captured monitor (the companion's
    /// taps are shares of the whole stream)
    /// </summary>
    public static class WowInputTests
    {
        static Displays.RECT R(int l, int t, int w, int h) => new Displays.RECT { Left = l, Top = t, Right = l + w, Bottom = t + h };
        const int Medium = 0x2000, High = 0x3000;
        static readonly Displays.RECT Mon = R(0, 0, 1920, 1080), Second = R(1920, 0, 2560, 1440);

        [Test]
        public static void ElevatedWowIsSaidAboveEverythingElse()
        {
            A.Eq(WowInput.Elevated, WowInput.Word(High, Medium, Mon, Mon));
            A.Eq(WowInput.Elevated, WowInput.Word(High, Medium, R(100, 100, 800, 600), Mon), "before a window that doesn't fill");
            A.Eq(WowInput.Elevated, WowInput.Word(High, Medium, null, null), "a minimised elevated WoW too");
            // Desktop (so Sunshine) elevated too, or UAC off: the same level, nothing blocked
            A.Eq("", WowInput.Word(High, High, Mon, Mon));
            A.Eq("", WowInput.Word(Medium, Medium, Mon, Mon));
            // a level that could not be read is not a problem
            A.Eq("", WowInput.Word(null, Medium, Mon, Mon));
            A.Eq("", WowInput.Word(High, null, Mon, Mon));
        }

        [Test]
        public static void AWindowThatDoesntFillTheStreamedMonitorIsSaid()
        {
            A.Eq("", WowInput.Word(Medium, Medium, Mon, Mon), "windowed fullscreen / fullscreen");
            A.Eq("", WowInput.Word(Medium, Medium, R(-1, 1, 1922, 1079), Mon), "within the slack");
            A.Eq("", WowInput.Word(Medium, Medium, R(-8, -8, 1936, 1096), Mon), "larger than the monitor still fills it");
            A.Eq(WowInput.NotFilling, WowInput.Word(Medium, Medium, R(0, 23, 1920, 1017), Mon), "a maximised window under its title bar");
            A.Eq(WowInput.NotFilling, WowInput.Word(Medium, Medium, R(200, 150, 1280, 720), Mon), "a plain window");
            A.Eq(WowInput.NotFilling, WowInput.Word(Medium, Medium, Mon, Second), "on another monitor than the captured one");
            A.Eq("", WowInput.Word(Medium, Medium, Second, Second), "filling the captured second monitor");
            // nothing known: nothing said
            A.Eq("", WowInput.Word(Medium, Medium, null, Mon), "minimised / no window");
            A.Eq("", WowInput.Word(Medium, Medium, Mon, null), "captured monitor not known");
            A.Eq("", WowInput.Word(Medium, Medium, R(0, 0, 0, 0), Mon), "an empty client area");
        }

        [Test]
        public static void TheWindowAndTheThorAreToldInTerseWords()
        {
            A.True(WowInput.Notice(WowInput.Elevated).Contains("administrator"));
            A.True(WowInput.Notice(WowInput.NotFilling).Contains("fullscreen"));
            A.Null(WowInput.Notice(""));
            A.Null(WowInput.Notice(null));
            // the Thor's status line ("PC: " + text) takes 110 characters
            foreach (var w in new[] { WowInput.Elevated, WowInput.NotFilling })
                A.True(WowInput.ThorWhy(w) != null && ("PC: " + WowInput.ThorWhy(w)).Length <= 110, w);
            A.True(("PC: " + WowInput.ThorFine).Length <= 110);
            A.Null(WowInput.ThorWhy(""));
        }

        [Test]
        public static void AWordIsSaidOnlyOnceItHeld()
        {
            var s = new WowInput.Steady();
            var t0 = new DateTime(2026, 11, 16, 12, 0, 0, DateTimeKind.Utc);
            A.Eq("", s.Step("", t0));
            A.Eq("", s.Step(WowInput.NotFilling, t0.AddSeconds(1)), "a resolution change at the stream's start");
            A.Eq("", s.Step("", t0.AddSeconds(3)), "gone before it held");
            A.Eq("", s.Step(WowInput.NotFilling, t0.AddSeconds(4)));
            A.Eq("", s.Step(WowInput.NotFilling, t0.AddSeconds(7)));
            A.Eq(WowInput.NotFilling, s.Step(WowInput.NotFilling, t0.AddSeconds(8)), "held 4 s");
            A.Eq(WowInput.NotFilling, s.Step(WowInput.Elevated, t0.AddSeconds(9)), "a new word holds first too");
            A.Eq(WowInput.Elevated, s.Step(WowInput.Elevated, t0.AddSeconds(13)));
            A.Eq("", s.Step("", t0.AddSeconds(14)), "fine is said at once");
            A.Eq("", s.Step(null, t0.AddSeconds(15)));
        }

        [DllImport("user32.dll")] static extern IntPtr GetDesktopWindow();

        [Test]
        public static void TheReadsWorkOnThisPc()
        {
            // the test's own process and a child it starts run at the same level: never "elevated"
            var own = WowInput.OwnIntegrity();
            A.NotNull(own, "own integrity level");
            A.True(own.Value >= Medium, "medium or above: 0x" + own.Value.ToString("x"));
            A.Eq(own, WowInput.IntegrityOf(Process.GetCurrentProcess().Id));
            using (var child = Process.Start(new ProcessStartInfo("ping.exe", "-n 3 127.0.0.1") { UseShellExecute = false, CreateNoWindow = true }))
            {
                A.Eq(own, WowInput.IntegrityOf(child.Id), "a child process");
                A.Eq("", WowInput.Word(WowInput.IntegrityOf(child.Id), own, null, null));
                try { child.Kill(); } catch (Exception) { }
            }
            A.Null(WowInput.IntegrityOf(-1), "no such process");
            // the desktop window's client area starts at the primary monitor's corner
            var d = WowInput.ClientOnScreen(GetDesktopWindow());
            A.NotNull(d);
            A.True(d.Value.Left == 0 && d.Value.Top == 0 && d.Value.W > 0 && d.Value.H > 0, d.Value.Left + "," + d.Value.Top + " " + d.Value.W + "x" + d.Value.H);
            A.Null(WowInput.ClientOnScreen(IntPtr.Zero));
        }
    }
}
