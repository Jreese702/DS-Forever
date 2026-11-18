using System;
using System.IO;
using System.Linq;
using DsfDesktop.Sunshine;
using DsfDesktop.Wow;

namespace DsfDesktop.Tests
{
    /// <summary>the user, 2026-11-18: "my stream settings are not changing my desktop resolution like it should. I would expect it to
    /// change the desktop resolution, and then change it back once its complete" - Match stood down only while Apollo STREAMS, not
    /// whenever its service runs. Fake socket tables only</summary>
    public static class MatchScreenTests
    {
        const int Apollo = 14216, Ours = 57068;
        const string OurDir = @"E:\Apps\DsForeverDesktop\sunshine";

        static ProcInfo Who(int pid) =>
            pid == Apollo ? new ProcInfo("sunshine", @"C:\Program Files\Apollo\sunshine.exe")
            : pid == Ours ? new ProcInfo("sunshine", OurDir + @"\sunshine.exe") : null;

        // the user's PC 2026-11-18: Apollo's service on the stock block, ours on 52989
        static TcpListenerRow[] Tcp() => new[] { 47984, 47989, 47990, 48010 }.Select(p => new TcpListenerRow(p, Apollo))
            .Concat(new[] { 52984, 52989, 52990, 53010 }.Select(p => new TcpListenerRow(p, Ours))).ToArray();

        static bool ApolloStreaming(params UdpRow[] udp)
        {
            var r = PortGuard.Check(Tcp(), 52989, Ours, true, true, OurDir, Who);
            A.Eq("Apollo", string.Join(",", r.HostList.Select(h => h.Name)));
            return r.HostList.Any(h => PortGuard.HostStreaming(h, udp));
        }

        static string Conf(bool match) =>
            SunshineConfig.ConfText(new SunshineLayout { ExeDir = "a", DataDir = "b" }, new SunshineOptions { MatchStream = match });

        [Test]
        public static void ApolloIdle_TheScreenIsMatched()
        {
            // Apollo running, no session: only its TCP listeners (and other programs' UDP, ours idle too)
            bool streaming = ApolloStreaming(new UdpRow(5353, 1234), new UdpRow(1900, Apollo));
            A.False(streaming, "a running Apollo is not a streaming one");
            A.True(MatchScreen.Write(true, streaming));
            var c = Conf(MatchScreen.Write(true, streaming));
            A.True(c.Contains("dd_configuration_option = ensure_active"), c);
            A.True(c.Contains("dd_resolution_option = auto"), "the Thor's resolution");
            A.True(c.Contains("dd_refresh_rate_option = auto"), "the Thor's rate");
            A.True(c.Contains("dd_config_revert_on_disconnect = enabled"), "back when the Thor goes");
            A.True(c.Contains("dd_config_revert_delay = 3000"));
            A.True(c.Contains("dd_hdr_option = disabled"), "HDR left as the player has it");
            A.False(c.Contains("ensure_only_display"), "the player's other monitors stay on");
        }

        [Test]
        public static void ApolloStreaming_StandsDown()
        {
            foreach (var port in new[] { 47998, 47999, 48000 })
            {
                bool streaming = ApolloStreaming(new UdpRow(port, Apollo));
                A.True(streaming, "Apollo's session UDP " + port);
                A.False(MatchScreen.Write(true, streaming));
                var c = Conf(MatchScreen.Write(true, streaming));
                A.True(c.Contains("dd_configuration_option = disabled"));
                A.False(c.Contains("dd_resolution_option"));
            }
            A.False(ApolloStreaming(new UdpRow(47998, 999)), "another process on that port is not Apollo's session");
            A.False(ApolloStreaming(new UdpRow(53000, Ours)), "our own session is not Apollo's");
        }

        [Test]
        public static void ApolloOnAnotherBase_ItsOwnSessionPorts()
        {
            var h = new NeighbourHost { Name = "Apollo", Pid = Apollo, Ports = { 40995, 41000, 41001, 41021 } };
            A.Eq(41000, PortGuard.HostBase(h.Ports));
            A.True(PortGuard.HostStreaming(h, new[] { new UdpRow(41010, Apollo) }));
            A.False(PortGuard.HostStreaming(h, new[] { new UdpRow(47998, Apollo) }), "not the stock block when its base is elsewhere");
            A.Eq(PortGuard.StockBase, PortGuard.HostBase(new[] { 47990 }), "unreadable base: the stock one");
            A.False(PortGuard.HostStreaming(h, null), "the UDP table unread: not streaming (the match stays the player's)");
        }

        [Test]
        public static void MatchOff_Nothing()
        {
            A.False(MatchScreen.Write(false, false));
            A.False(MatchScreen.Write(false, true));
            var c = Conf(MatchScreen.Write(false, false));
            A.True(c.Contains("dd_configuration_option = disabled"));
            A.False(c.Contains("dd_resolution_option") || c.Contains("dd_refresh_rate_option") || c.Contains("dd_hdr_option"));
        }

        [Test]
        public static void TheModeIsPutBackAfterTheStream_DisplayKeep()
        {
            // the net under Sunshine's own revert: with the match written (Apollo idle) the player's mode is recorded and put back
            const string mon = @"\\.\DISPLAY1";
            var dir = Path.Combine(Path.GetTempPath(), "dsf-match-" + Guid.NewGuid().ToString("N"));
            try
            {
                var w = new FakeWin(mon);
                var now = new DateTime(2026, 11, 18, 12, 0, 0, DateTimeKind.Utc);
                var k = new DisplayKeep(Path.Combine(dir, "display_keep.json"), w, () => now);
                bool match = MatchScreen.Write(true, ApolloStreaming());
                void Tick(bool s) { k.Tick(s, mon, match); now += TimeSpan.FromSeconds(2); }
                for (int i = 0; i < 5; i++) Tick(false);
                Tick(true);
                w.mode = (1280, 800, 60);            // Sunshine's match: the Thor's mode
                Tick(true); Tick(true);
                A.True(k.Pending, "recorded");
                for (int i = 0; i < 20; i++) Tick(false);
                A.Eq(mon + " 2560x1440@165", w.set, "the player's mode back");
                A.False(k.Pending);

                // Apollo streaming: ours wrote no match, so nothing is recorded or changed
                w.set = null;
                match = MatchScreen.Write(true, ApolloStreaming(new UdpRow(47998, Apollo)));
                for (int i = 0; i < 5; i++) Tick(false);
                Tick(true); w.mode = (1920, 1080, 60); Tick(true);
                A.False(k.Pending);
                for (int i = 0; i < 20; i++) Tick(false);
                A.Null(w.set, "Apollo's display setup is its own");
            }
            finally { try { Directory.Delete(dir, true); } catch (Exception) { } }
        }

        sealed class FakeWin : IVddWindows
        {
            readonly string mon;
            public (int w, int h, int hz) mode = (2560, 1440, 165);
            public string set;
            public FakeWin(string mon) { this.mon = mon; }
            public System.Collections.Generic.List<WinDisplay> Displays() => new System.Collections.Generic.List<WinDisplay>();
            public System.Collections.Generic.List<(int w, int h, int hz)> Modes(string dev) => new System.Collections.Generic.List<(int, int, int)>();
            public (int w, int h, int hz) Current(string dev) => dev == mon ? mode : (0, 0, 0);
            public (int x, int y)? Position(string dev) => dev == mon ? (0, 0) : ((int, int)?)null;
            public int RightEdge() => mode.w;
            public string Set(string dev, int w, int h, int hz, int x, int y) { set = dev + " " + w + "x" + h + "@" + hz; mode = (w, h, hz); return null; }
            public string Detach(string dev) => null;
            public void Sleep(int ms) { }
        }
    }
}
