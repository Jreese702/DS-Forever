using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;

namespace DsfDesktop.Tests
{
    [AttributeUsage(AttributeTargets.Method)] public sealed class TestAttribute : Attribute { }

    public sealed class AssertFailed : Exception { public AssertFailed(string m) : base(m) { } }

    public static class A
    {
        public static void True(bool c, string what = "expected true") { if (!c) throw new AssertFailed(what); }
        public static void False(bool c, string what = "expected false") { if (c) throw new AssertFailed(what); }
        public static void Eq<T>(T want, T got, string what = null)
        {
            if (!EqualityComparer<T>.Default.Equals(want, got))
                throw new AssertFailed((what != null ? what + ": " : "") + "want <" + want + "> got <" + got + ">");
        }
        public static void Null(object o, string what = "expected null") { if (o != null) throw new AssertFailed(what + " (got " + o + ")"); }
        public static void NotNull(object o, string what = "expected a value") { if (o == null) throw new AssertFailed(what); }
    }

    /// <summary>a temp folder per test, removed afterwards</summary>
    public sealed class Tmp : IDisposable
    {
        public readonly string Dir = Path.Combine(Path.GetTempPath(), "dsf-test-" + Guid.NewGuid().ToString("N").Substring(0, 8));
        public Tmp() { Directory.CreateDirectory(Dir); }
        public string P(params string[] parts) => Path.Combine(new[] { Dir }.Concat(parts).ToArray());
        public void Dispose() { try { Directory.Delete(Dir, true); } catch (Exception) { } }
    }

    static class Runner
    {
        /// <summary>DsfDesktop.Tests.exe [filter] [--sunshine &lt;dir with sunshine.exe&gt;]: the live Sunshine test runs only with --sunshine</summary>
        static int Main(string[] args)
        {
            int si = Array.IndexOf(args, "--sunshine");
            if (si >= 0 && si + 1 < args.Length) SunshineLive.ExeDir = args[si + 1];
            if (args.Contains("--serve")) { EndToEnd.Serve(); return 0; }
            var filter = args.FirstOrDefault(a => !a.StartsWith("--") && (si < 0 || a != args.ElementAtOrDefault(si + 1)));
            var tests = typeof(Runner).Assembly.GetTypes()
                .SelectMany(t => t.GetMethods(BindingFlags.Public | BindingFlags.Static).Where(m => m.GetCustomAttribute<TestAttribute>() != null))
                .Where(m => filter == null || (m.DeclaringType.Name + "." + m.Name).IndexOf(filter, StringComparison.OrdinalIgnoreCase) >= 0)
                .OrderBy(m => m.DeclaringType.Name).ThenBy(m => m.Name).ToList();
            int fail = 0, skip = 0;
            var sw = Stopwatch.StartNew();
            foreach (var m in tests)
            {
                var name = m.DeclaringType.Name + "." + m.Name;
                var t0 = sw.ElapsedMilliseconds;
                try
                {
                    var r = m.Invoke(null, null);
                    if (r is System.Threading.Tasks.Task task) task.GetAwaiter().GetResult();
                    Console.WriteLine("PASS " + name + " (" + (sw.ElapsedMilliseconds - t0) + " ms)");
                }
                catch (Exception e)
                {
                    var inner = e is TargetInvocationException tie ? tie.InnerException : e;
                    if (inner is SkipException) { Console.WriteLine("SKIP " + name + ": " + inner.Message); skip++; continue; }
                    fail++;
                    Console.WriteLine("FAIL " + name + ": " + inner.GetType().Name + ": " + inner.Message);
                    if (!(inner is AssertFailed)) Console.WriteLine(inner.StackTrace);
                }
            }
            Console.WriteLine();
            Console.WriteLine((tests.Count - fail - skip) + "/" + tests.Count + " passed, " + fail + " failed, " + skip + " skipped, " + sw.ElapsedMilliseconds + " ms");
            return fail;
        }
    }

    public sealed class SkipException : Exception { public SkipException(string m) : base(m) { } }
}
