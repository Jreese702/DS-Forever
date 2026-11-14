using System.Collections.Generic;
using System.Linq;
using DsfDesktop.Sunshine;

namespace DsfDesktop.Tests
{
    /// <summary>a separately installed Sunshine on the GameStream ports (the user's PC, 2026-11-13): told apart from ours, no sockets</summary>
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
            var rows = Rows(SvcPid, 47984, 47989, 47990, 48010).Concat(Rows(OurPid, 52984, 52989, 52990, 53010)).Concat(Rows(4, 445));
            var r = PortGuard.Check(rows, 52989, OurPid, true, true, OurDir, Who);
            A.Eq("other_sunshine", r.Problem);
            A.Eq("Another Sunshine is using the streaming ports (47984, 47989, 47990, 48010)", r.Line);
            A.True(r.Hint.Contains("Services") && r.Hint.Contains("Sunshine Service") && r.Hint.Contains("uninstall"), r.Hint);
            A.Eq("pc busy: another Sunshine", r.ThorWhy);
            A.True(r.Services, "the Services button");
            A.Eq("47984,47989,47990,48010", string.Join(",", r.ForeignPorts));
            A.True(r.Detail.Contains("52989 ours") && r.Detail.Contains("47989 othersunshine (pid 14304 sunshine)"), r.Detail);
        }

        [Test]
        public static void OursCouldNotBind_TheServiceHasOurBase()
        {
            // ours on the stock base, but the service holds it: ours runs and has no port
            var r = PortGuard.Check(Rows(SvcPid, 47984, 47989, 47990, 48010), 47989, OurPid, true, true, OurDir, Who);
            A.Eq("other_sunshine", r.Problem);
            A.True(r.Line.EndsWith("couldn't open its ports"), r.Line);
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
            var ours = Rows(OurPid, 52984, 52989, 52990, 53010);
            var r = PortGuard.Check(Rows(901, 47990).Concat(ours), 52989, OurPid, true, true, OurDir, Who);
            A.Eq("other_app", r.Problem);
            A.Eq("Another program (nginx.exe) is using the streaming ports (47990)", r.Line);
            var u = PortGuard.Check(Rows(999, 48010).Concat(ours), 52989, OurPid, true, true, OurDir, Who);
            A.Eq("other_app", u.Problem, "unknown owner = foreign");
            A.True(u.Line.Contains("an unknown program"), u.Line);
            // Apollo is a Sunshine fork but named apollo: another program, still the Services hint
            var ap = PortGuard.Check(Rows(900, 47989).Concat(ours), 52989, OurPid, true, true, OurDir, Who);
            A.Eq("other_app", ap.Problem);
            A.True(ap.Services);
        }

        [Test]
        public static void ALookupThatThrowsIsUnknown()
        {
            var r = PortGuard.Check(Rows(777, 47989), -1, -1, false, false, OurDir, pid => throw new System.InvalidOperationException("denied"));
            A.Eq("other_app", r.Problem);
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
