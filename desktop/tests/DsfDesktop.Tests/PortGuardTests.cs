using System.Collections.Generic;
using System.Linq;
using DsfDesktop.Sunshine;

namespace DsfDesktop.Tests
{
    /// <summary>a separately installed Sunshine / Apollo on the GameStream ports (2026-11-13, 2026-11-16): told apart from ours, no sockets</summary>
    public static class PortGuardTests
    {
        const string OurDir = @"E:\Apps\DsForeverDesktop\sunshine";
        const int OurPid = 57068, SvcPid = 14304;

        static TcpListenerRow[] Rows(int pid, params int[] ports) => ports.Select(p => new TcpListenerRow(p, pid)).ToArray();

        static ProcInfo Who(int pid) =>
            pid == OurPid ? new ProcInfo("sunshine", OurDir + @"\sunshine.exe")
            : pid == SvcPid ? new ProcInfo("sunshine", null)                 // the service's SYSTEM process: name only, path unreadable
            : pid == 900 ? new ProcInfo("apollo", null)
            : pid == 901 ? new ProcInfo("nginx", @"C:\nginx\nginx.exe")
            : pid == 902 ? new ProcInfo("sunshine", @"C:\Program Files\Sunshine\sunshine.exe")
            : pid == 904 ? new ProcInfo("sunshine", @"C:\Program Files\Apollo\sunshine.exe")    // Apollo ships a sunshine.exe
            : null;                                                            // gone / unreadable

        [Test]
        public static void ClassifiesOwners()
        {
            A.Eq(PortOwner.Ours, PortGuard.Classify(OurPid, Who(OurPid), OurPid, OurDir), "our child");
            A.Eq(PortOwner.Ours, PortGuard.Classify(1234, new ProcInfo("sunshine", OurDir + @"\sunshine.exe"), OurPid, OurDir), "a sunshine.exe under our folder");
            A.Eq(PortOwner.Ours, PortGuard.Classify(1234, new ProcInfo("sunshine", OurDir.ToUpperInvariant() + @"\SUNSHINE.EXE"), -1, OurDir), "case");
            A.Eq(PortOwner.OtherSunshine, PortGuard.Classify(SvcPid, Who(SvcPid), OurPid, OurDir), "the service: path unreadable");
            A.Eq(PortOwner.OtherSunshine, PortGuard.Classify(902, Who(902), OurPid, OurDir), "Program Files\\Sunshine");
            A.Eq(PortOwner.OtherSunshine, PortGuard.Classify(903, new ProcInfo(null, @"C:\Program Files\Sunshine\sunshine.exe"), OurPid, OurDir), "name from the path");
            A.Eq(PortOwner.OtherSunshine, PortGuard.Classify(903, new ProcInfo("sunshine", OurDir + @"-old\sunshine.exe"), OurPid, OurDir), "a look-alike folder is not ours");
            A.Eq(PortOwner.OtherApp, PortGuard.Classify(901, Who(901), OurPid, OurDir));
            A.Eq(PortOwner.Unknown, PortGuard.Classify(999, new ProcInfo(null, null), OurPid, OurDir), "nothing readable");
            A.Eq(PortOwner.Unknown, PortGuard.Classify(999, null, OurPid, OurDir), "gone");
            A.Eq(PortOwner.OtherSunshine, PortGuard.Classify(OurPid, Who(SvcPid), -1, OurDir), "ours not running: its old pid is not ours");
        }

        [Test]
        public static void TheUsersPc_ServiceOnStockPorts_OursOn52989()
        {
            // the coexistence rule (2026-11-17): another host on the stock ports while ours runs on its own is fine, said quietly
            var rows = Rows(SvcPid, 47984, 47989, 47990, 48010).Concat(Rows(OurPid, 52984, 52989, 52990, 53010)).Concat(Rows(4, 445));
            var r = PortGuard.Check(rows, 52989, OurPid, true, true, OurDir, Who);
            A.Null(r.Problem, "not a problem");
            A.Eq("", r.Line);
            A.Eq("", r.Hint);
            A.Eq("", r.ThorWhy, "nothing for the Thor's curtain");
            A.False(r.Services, "no Services button");
            A.Eq("Sunshine also runs on this PC (fine)", r.Info);
            A.Eq("47984,47989,47990,48010", string.Join(",", r.ForeignPorts));
            A.True(r.Detail.Contains("52989 ours") && r.Detail.Contains("47989 othersunshine (pid 14304 sunshine)"), r.Detail);
            A.Eq("Sunshine pid 14304 (47984, 47989, 47990, 48010)", string.Join("; ", r.Neighbours));
        }

        [Test]
        public static void ApolloOnStockPorts_OursHealthy_InfoOnly()
        {
            var ours = Rows(OurPid, 52984, 52989, 52990, 53010);
            var r = PortGuard.Check(Rows(904, 47984, 47989, 47990, 48010, 48989).Concat(ours), 52989, OurPid, true, true, OurDir, Who);
            A.Null(r.Problem);
            A.Eq("Apollo also runs on this PC (fine)", r.Info);
            A.Eq("Apollo pid 904 (47984, 47989, 47990, 48010, 48989)", r.Neighbours[0], "every port it listens on");
            var named = PortGuard.Check(Rows(900, 47989).Concat(ours), 52989, OurPid, true, true, OurDir, Who);
            A.Null(named.Problem, "named apollo");
            A.Eq("Apollo also runs on this PC (fine)", named.Info);
            // the SYSTEM service's sunshine.exe (path unreadable) with an Apollo service installed: Apollo
            var svc = PortGuard.Check(Rows(SvcPid, 47989).Concat(ours), 52989, OurPid, true, true, OurDir, Who, apolloInstalled: true);
            A.Eq("Apollo also runs on this PC (fine)", svc.Info);
            // before ours starts (no base yet): still only info
            A.Null(PortGuard.Check(Rows(904, 47989), -1, -1, false, false, OurDir, Who).Problem, "before start");
            foreach (var x in new[] { r, named, svc })
                A.False((x.Line + x.Hint + x.Info).ToLowerInvariant().Contains("uninstall") || (x.Line + x.Hint + x.Info).Contains("Services"), "never suggests removing it");
        }

        [Test]
        public static void ApolloPresent_OursMissing_IsAProblem()
        {
            var r = PortGuard.Check(Rows(904, 47984, 47989, 47990, 48010), 52989, OurPid, true, true, OurDir, Who);
            A.Eq("not_listening", r.Problem);
            A.True(r.Line.Contains("52989"), r.Line);
            A.Eq("Apollo also runs on this PC (fine)", r.Info);
            A.False(r.Hint.ToLowerInvariant().Contains("uninstall"), r.Hint);
        }

        [Test]
        public static void AForeignProcessOnOurBase_IsAProblem()
        {
            var r = PortGuard.Check(Rows(904, 52989).Concat(Rows(OurPid, 52984, 52990, 53010)), 52989, OurPid, true, true, OurDir, Who);
            A.Eq("ports_taken", r.Problem);
            A.Eq("DS Forever's streaming ports (52989) are taken by Apollo", r.Line);
            A.Eq("pc busy: its streaming ports are taken", r.ThorWhy);
            A.False(r.Hint.ToLowerInvariant().Contains("uninstall") || r.Hint.Contains("Services"), r.Hint);
            A.True(r.Hint.Contains("Restart DS Forever Desktop"), r.Hint);
            var n = PortGuard.Check(Rows(901, 52990).Concat(Rows(OurPid, 52984, 52989, 53010)), 52989, OurPid, true, true, OurDir, Who);
            A.Eq("ports_taken", n.Problem);
            A.Eq("DS Forever's streaming ports (52990) are taken by nginx.exe", n.Line);
        }

        [Test]
        public static void HostNames()
        {
            A.Eq("Apollo", PortGuard.HostName(new ProcInfo("sunshine", @"C:\Program Files\Apollo\sunshine.exe")));
            A.Eq("Apollo", PortGuard.HostName(new ProcInfo("apollo", null)));
            A.Eq("Sunshine", PortGuard.HostName(new ProcInfo("sunshine", @"C:\Program Files\Sunshine\sunshine.exe")));
            A.Eq("Sunshine", PortGuard.HostName(new ProcInfo("sunshine", null)), "unreadable, no Apollo service");
            A.Eq("Apollo", PortGuard.HostName(new ProcInfo("sunshine", null), true), "unreadable, an Apollo service installed");
            A.Null(PortGuard.HostName(new ProcInfo("nginx", null)));
            A.Null(PortGuard.HostName(null));
        }

        [Test]
        public static void OursCouldNotBind_TheServiceHasOurBase()
        {
            // an old saved stock base: the service holds it, ours runs and has no port
            var r = PortGuard.Check(Rows(SvcPid, 47984, 47989, 47990, 48010), 47989, OurPid, true, true, OurDir, Who);
            A.Eq("ports_taken", r.Problem);
            A.True(r.Line.EndsWith("are taken by Sunshine"), r.Line);
        }

        [Test]
        public static void AllClear()
        {
            A.Null(PortGuard.Check(Rows(OurPid, 47984, 47989, 47990, 48010), 47989, OurPid, true, true, OurDir, Who).Problem, "ours on stock");
            A.Null(PortGuard.Check(Rows(OurPid, 52984, 52989, 52990, 53010), 52989, OurPid, true, true, OurDir, Who).Problem, "ours on 52989, stock free");
            A.Null(PortGuard.Check(new TcpListenerRow[0], -1, -1, false, false, OurDir, Who).Problem, "nothing at all");
            var r = PortGuard.Check(Rows(4, 445), 52989, OurPid, true, false, OurDir, Who);
            A.Null(r.Problem, "ours just started: not settled yet");
            A.Eq("", r.ThorWhy);
        }

        [Test]
        public static void OursNotListening()
        {
            var r = PortGuard.Check(Rows(4, 445), 52989, OurPid, true, true, OurDir, Who);
            A.Eq("not_listening", r.Problem);
            A.True(r.Line.Contains("52989"), r.Line);
            A.Eq("pc busy: the streaming host isn't listening", r.ThorWhy);
            A.False(r.Services);
        }

        [Test]
        public static void OtherPrograms()
        {
            // the stock ports no longer matter to ours: a program there is in the log only
            var ours = Rows(OurPid, 52984, 52989, 52990, 53010);
            var r = PortGuard.Check(Rows(901, 47990).Concat(ours), 52989, OurPid, true, true, OurDir, Who);
            A.Null(r.Problem);
            A.Eq("", r.Info, "not a streaming host");
            A.True(r.Detail.Contains("47990 otherapp (pid 901 nginx)"), r.Detail);
            var u = PortGuard.Check(Rows(999, 48010).Concat(ours), 52989, OurPid, true, true, OurDir, Who);
            A.Null(u.Problem, "unknown owner of a stock port");
        }

        [Test]
        public static void ALookupThatThrowsIsUnknown()
        {
            var r = PortGuard.Check(Rows(777, 52989), 52989, -1, false, false, OurDir, pid => throw new System.InvalidOperationException("denied"));
            A.Eq("ports_taken", r.Problem);
            A.True(r.Line.Contains("an unknown program"), r.Line);
        }

        [Test]
        public static void OurPortsFromTheBase()
        {
            A.Eq("47984,47989,47990,48010", string.Join(",", PortGuard.OurPorts(47989)));
            A.Eq("52984,52989,52990,53010", string.Join(",", PortGuard.OurPorts(52989)));
            A.Eq(0, PortGuard.OurPorts(-1).Length);
        }

        [Test]
        public static void ReadsThisPcsListenerTable()
        {
            // the live table: not empty on any Windows PC (it always listens on 135 / 445), every port in range
            var rows = PortGuard.TcpListeners();
            A.True(rows.Count > 0, "some listeners");
            A.True(rows.All(x => x.Port > 0 && x.Port < 65536), "ports in range");
            A.NotNull(PortGuard.Who(System.Diagnostics.Process.GetCurrentProcess().Id), "our own process");
        }

    }
}
