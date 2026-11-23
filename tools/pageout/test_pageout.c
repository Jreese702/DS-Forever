/*
 * Host tests for dsforever/src/main/jni/dsf/dsf_pageout.h (Linux x86_64, e.g. WSL): the maps parser, the region rules, the process
 * check (never the game), the request parser, and the page-out on this process's own memory. Run through tools/pageout/test.sh.
 */
#define PO_HOST_TEST 1
#include "../../dsforever/src/main/jni/dsf/dsf_pageout.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

static int fails = 0, checks = 0;
#define CHECK(c) do { checks++; if (!(c)) { fails++; printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); } } while (0)

static int parse(const char *l, po_map *m) { return po_parse_maps_line(l, strlen(l), m); }
static int ok(const char *l, int mode) { po_map m; return parse(l, &m) && po_region_ok(&m, mode); }

static void test_maps(void) {
    po_map m;
    CHECK(parse("7f12340000-7f12380000 rw-p 00000000 00:00 0                          [heap]\n", &m));
    CHECK(m.start == 0x7f12340000ul && m.end == 0x7f12380000ul && m.r == 'r' && m.w == 'w' && m.x == '-' && m.p == 'p');
    CHECK(m.anon_dev && m.inode == 0 && m.name_len == 6 && memcmp(m.name, "[heap]", 6) == 0);
    CHECK(parse("7000000000-7000100000 r-xp 00012000 fd:05 123456  /system/lib64/libc.so", &m));
    CHECK(!m.anon_dev && m.inode == 123456 && m.x == 'x');
    CHECK(parse("1000-2000 rw-p 00000000 00:00 0", &m) && m.name_len == 0);
    CHECK(!parse("", &m));
    CHECK(!parse("zzzz-2000 rw-p 0 00:00 0", &m));
    CHECK(!parse("2000-1000 rw-p 0 00:00 0", &m));       /* end before start */
    CHECK(!parse("1000-2000 rw-p", &m));
    CHECK(parse("1000-2000 rw-p 00000000 00:00 0   /memfd:wine (deleted)", &m) && m.name_len == 21);
}

static void test_regions(void) {
    const char *big = "7f00000000-7f00400000";                /* 4 MiB */
    char l[256];
#define L(perms, dev, ino, name) (snprintf(l, sizeof l, "%s %s 00000000 %s %s %s", big, perms, dev, ino, name), l)
    /* a Wine helper: its heaps (unnamed, [heap], the allocator's) */
    CHECK(ok(L("rw-p", "00:00", "0", ""), PO_HELPER));
    CHECK(ok(L("rw-p", "00:00", "0", "[heap]"), PO_HELPER));
    CHECK(ok(L("rw-p", "00:00", "0", "[anon:scudo:primary]"), PO_HELPER));
    CHECK(ok(L("rw-p", "00:00", "0", "[anon:libc_malloc]"), PO_HELPER));
    /* never: stacks, guard pages, vdso/vvar, shared, files, GPU / dmabuf / ashmem / memfd, executable, .bss, the Java heap */
    CHECK(!ok(L("rw-p", "00:00", "0", "[stack]"), PO_HELPER));
    CHECK(!ok(L("rw-p", "00:00", "0", "[anon:stack_and_tls:1234]"), PO_HELPER));
    CHECK(!ok(L("rw-p", "00:00", "0", "[anon:thread signal stack]"), PO_HELPER));
    CHECK(!ok(L("---p", "00:00", "0", ""), PO_HELPER));
    CHECK(!ok(L("r--p", "00:00", "0", "[vvar]"), PO_HELPER));
    CHECK(!ok(L("r-xp", "00:00", "0", "[vdso]"), PO_HELPER));
    CHECK(!ok(L("rw-s", "00:00", "0", ""), PO_HELPER));
    CHECK(!ok(L("rw-s", "00:01", "4096", "/dev/ashmem/wine (deleted)"), PO_HELPER));
    CHECK(!ok(L("rw-s", "00:0f", "77", "/dev/kgsl-3d0"), PO_HELPER));
    CHECK(!ok(L("rw-p", "00:0f", "77", "/dev/kgsl-3d0"), PO_HELPER));
    CHECK(!ok(L("rw-s", "00:0c", "9", "/dmabuf:"), PO_HELPER));
    CHECK(!ok(L("rw-p", "00:01", "12", "/memfd:wine-shared (deleted)"), PO_HELPER));
    CHECK(!ok(L("rw-p", "fd:05", "1234", "/data/app/lib/libwine.so"), PO_HELPER));
    CHECK(!ok(L("rwxp", "00:00", "0", ""), PO_HELPER));
    CHECK(!ok(L("rw-p", "00:00", "0", "[anon:.bss]"), PO_HELPER));
    CHECK(!ok(L("rw-p", "00:00", "0", "[anon:dalvik-main space (region space)]"), PO_HELPER));
    CHECK(!ok(L("rw-p", "00:00", "0", "[anon:scudo:primary_reserve]"), PO_HELPER));
    /* DS Forever's own process: the native allocator only */
    CHECK(ok(L("rw-p", "00:00", "0", "[anon:scudo:primary]"), PO_SELF));
    CHECK(ok(L("rw-p", "00:00", "0", "[anon:scudo:secondary]"), PO_SELF));
    CHECK(ok(L("rw-p", "00:00", "0", "[anon:libc_malloc]"), PO_SELF));
    CHECK(!ok(L("rw-p", "00:00", "0", ""), PO_SELF));
    CHECK(!ok(L("rw-p", "00:00", "0", "[heap]"), PO_SELF));
    CHECK(!ok(L("rw-p", "00:00", "0", "[anon:dalvik-main space (region space)]"), PO_SELF));
    CHECK(!ok(L("rw-p", "00:00", "0", "[anon:dalvik-LinearAlloc]"), PO_SELF));
    CHECK(!ok(L("rw-p", "00:00", "0", "[anon:dalvik-jit-code-cache]"), PO_SELF));
    CHECK(!ok(L("rw-p", "00:00", "0", "[anon:stack_and_tls:main]"), PO_SELF));
    CHECK(!ok(L("rw-p", "00:00", "0", "[anon:scudo:secondary_reserve]"), PO_SELF));
    /* too small */
    CHECK(!ok("7f00000000-7f00008000 rw-p 00000000 00:00 0 ", PO_HELPER));
#undef L
}

/* argv joined with NUL (as /proc/<pid>/cmdline) */
static size_t cmd(char *out, const char *const *argv) {
    size_t n = 0;
    for (int i = 0; argv[i]; i++) { size_t k = strlen(argv[i]); memcpy(out + n, argv[i], k); n += k; out[n++] = 0; }
    return n;
}
static int who(const char *comm, const char *const *argv) {
    char b[1024]; size_t n = cmd(b, argv);
    return po_image_check(b, n, comm, comm ? strlen(comm) : 0, "Wow-arm64.exe:WowB-ARM64.exe");
}

static void test_image(void) {
    /* Wine's own helpers, as Wine shows them (argv rewritten to the Windows path) and as the loader starts them */
    { const char *a[] = {"C:\\windows\\system32\\services.exe", 0}; CHECK(who("services.exe\n", a) == PO_OK); }
    { const char *a[] = {"C:\\windows\\system32\\winedevice.exe", 0}; CHECK(who("winedevice.exe", a) == PO_OK); }
    { const char *a[] = {"C:\\windows\\system32\\plugplay.exe", 0}; CHECK(who("plugplay.exe", a) == PO_OK); }
    { const char *a[] = {"C:\\windows\\system32\\svchost.exe", "-k", "LocalServiceNetworkRestricted", 0}; CHECK(who("svchost.exe", a) == PO_OK); }
    { const char *a[] = {"C:\\windows\\system32\\rpcss.exe", 0}; CHECK(who("rpcss.exe", a) == PO_OK); }
    { const char *a[] = {"C:\\WINDOWS\\SYSTEM32\\CONHOST.EXE", "--unix", 0}; CHECK(who("conhost.exe", a) == PO_OK); }
    { const char *a[] = {"/data/user/0/app/files/imagefs/opt/wine/bin/wine", "C:\\windows\\system32\\services.exe", 0}; CHECK(who("wine", a) == PO_OK); }
    { const char *a[] = {"wine64-preloader", "wine64", "plugplay.exe", 0}; CHECK(who(0, a) == PO_OK); }
    { const char *a[] = {"C:\\windows\\explorer.exe", "/desktop", 0}; CHECK(who("explorer.exe", a) == PO_OK); }
    /* THE GAME, never: by its image, by any argument, by its comm, by our own exe names, any flavour */
    { const char *a[] = {"G:\\_forever_\\Wow-arm64.exe", "-launcherlogin", "-uid", "wowf", 0}; CHECK(who("Wow-arm64.exe", a) == PO_GAME); }
    { const char *a[] = {"G:\\_classic_beta_\\WowB-ARM64.exe", 0}; CHECK(who("WowB-ARM64.exe", a) == PO_GAME); }
    { const char *a[] = {"C:\\Program Files\\World of Warcraft\\_classic_\\WowClassic.exe", 0}; CHECK(who("WowClassic.exe", a) == PO_GAME); }
    { const char *a[] = {"/x/wine", "G:\\_forever_\\Wow-arm64.exe", 0}; CHECK(who("wine", a) == PO_GAME); }
    { const char *a[] = {"C:\\windows\\system32\\services.exe", 0}; CHECK(who("Wow-arm64.exe", a) == PO_GAME); }   /* comm wins */
    { const char *a[] = {"/x/wine", "explorer", "/desktop=shell,1920x1080", "G:\\_forever_\\Wow-arm64.exe", 0}; CHECK(who("explorer.exe", a) == PO_GAME); }
    { const char *a[] = {"C:\\windows\\explorer.exe", "/desktop=shell,1920x1080", "\"G:\\_forever_\\Wow-arm64.exe\" -d3d11", 0}; CHECK(who("explorer.exe", a) == PO_GAME); }
    { const char *a[] = {"C:\\windows\\explorer.exe", "/desktop=shell,1920x1080", "wow-session-bridge.exe", "--port", "1", 0}; CHECK(who("explorer.exe", a) == PO_GAME); }
    { const char *a[] = {"C:\\windows\\system32\\start.exe", "/unix", "/x/World of Warcraft/game.exe", 0}; CHECK(who("start.exe", a) == PO_GAME); }
    { const char *a[] = {"C:\\Program Files (x86)\\Battle.net\\Battle.net.exe", 0}; CHECK(who("Battle.net.exe", a) == PO_GAME); }
    { const char *a[] = {"C:\\ProgramData\\Battle.net\\Agent\\Agent.exe", 0}; CHECK(who("Agent.exe", a) == PO_GAME); }
    { const char *a[] = {"C:\\x\\BlizzardError.exe", 0}; CHECK(who("BlizzardError.e", a) == PO_GAME); }
    /* an exe named by the launcher (DSF_PAGEOUT_GAME) */
    { char b[256]; const char *a[] = {"C:\\games\\game.exe", 0}; size_t n = cmd(b, a);
      CHECK(po_image_check(b, n, "game.exe", 8, "game.exe") == PO_GAME); CHECK(po_image_check(b, n, "game.exe", 8, "other.exe:x") == PO_NOT_HELPER); }
    /* unsure = nothing: the loader alone, wineserver (not idle), unknown programs, empty */
    { const char *a[] = {"/x/wine", 0}; CHECK(who("wine", a) == PO_NOT_HELPER); }
    { const char *a[] = {"/x/bin/wineserver", 0}; CHECK(who("wineserver", a) == PO_NOT_HELPER); }
    { const char *a[] = {"C:\\windows\\system32\\lsass.exe", 0}; CHECK(who("lsass.exe", a) == PO_NOT_HELPER); }
    { const char *a[] = {"C:\\windows\\system32\\rundll32.exe", "x.dll,Entry", 0}; CHECK(who("rundll32.exe", a) == PO_NOT_HELPER); }
    { const char *a[] = {"C:\\games\\game.exe", 0}; CHECK(who("game.exe", a) == PO_NOT_HELPER); }
    { const char *a[] = {"services.exe.bak", 0}; CHECK(who(0, a) == PO_NOT_HELPER); }
    CHECK(po_image_check("", 0, "", 0, 0) == PO_NOT_HELPER);
}

static void test_request(void) {
    uint64_t seq = 0, cap = 0;
    CHECK(po_parse_request("DSFPO1 7 32768\n", 15, &seq, &cap) && seq == 7 && cap == 32ull << 20);
    CHECK(po_parse_request("DSFPO1 8 999999", 15, &seq, &cap) && cap == PO_CAP_MAX);      /* never more than 32 MiB */
    CHECK(po_parse_request("DSFPO1 9 1", 10, &seq, &cap) && cap == PO_CHUNK);
    CHECK(!po_parse_request("DSFPO2 7 32768\n", 15, &seq, &cap));
    CHECK(!po_parse_request("DSFPO1 x 32768\n", 15, &seq, &cap));
    CHECK(!po_parse_request("DSFPO1 7\n", 9, &seq, &cap));
    CHECK(!po_parse_request("DSFPO1 7 12abc", 14, &seq, &cap));
    CHECK(!po_parse_request("", 0, &seq, &cap));
}

static void test_line(void) {
    po_result r = {0, 14, 20480, 9000, 8192, 1024, 2, 7};
    char out[PO_LINE_MAX];
    size_t n = po_result_line(12, &r, out); out[n] = 0;
    CHECK(strcmp(out, "DSFPO1 seq=12 rc=0 regions=14 eligible_kb=20480 resident_kb=9000 paged_kb=8192 back_kb=1024 hot=2 ms=7\n") == 0);
    po_result big = {5, 4000000000u, ~0ull, ~0ull, ~0ull, ~0ull, 4000000000u, ~0ull};
    CHECK(po_result_line(~0ull, &big, out) < PO_LINE_MAX);
}

static void test_run(void) {
    size_t page = (size_t) sysconf(_SC_PAGESIZE);
    size_t n = 24u << 20;
    unsigned char *a = mmap(0, n, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    CHECK(a != MAP_FAILED);
    for (size_t i = 0; i < n; i += page) a[i] = (unsigned char) (i >> 12);
    static po_state st;
    po_state_init(&st, page);
    po_result r;
    po_run(&st, PO_HELPER, 8u << 20, &r);
    printf("run 1: rc=%d regions=%u eligible=%llu KB resident=%llu KB paged=%llu KB back=%llu KB hot=%u ms=%llu supported=%d\n", r.rc,
        r.regions, (unsigned long long) r.eligible_kb, (unsigned long long) r.resident_kb, (unsigned long long) r.paged_kb,
        (unsigned long long) r.back_kb, r.hot_skipped, (unsigned long long) r.ms, st.supported);
    if (st.supported < 0) { CHECK(r.rc == PO_UNSUPPORTED); munmap(a, n); return; }
    CHECK(r.rc == PO_OK);
    CHECK(r.regions >= 1 && r.eligible_kb >= 24u * 1024);
    /* the budget: at most 8 MiB of resident pages looked at, plus the one chunk that crossed it */
    CHECK(r.resident_kb <= 8u * 1024 + 1024);
    CHECK(r.resident_kb >= 8u * 1024);
    CHECK(st.cursor != 0);
    /* the memory is still ours and intact (a page-out never changes content) */
    int same = 1;
    for (size_t i = 0; i < n; i += page) if (a[i] != (unsigned char) (i >> 12)) same = 0;
    CHECK(same);
    /* everything touched again: whatever was paged came straight back; the next request sees it and skips those chunks */
    po_run(&st, PO_HELPER, 8u << 20, &r);
    printf("run 2: rc=%d paged=%llu KB back=%llu KB hot=%u resident=%llu KB\n", r.rc, (unsigned long long) r.paged_kb,
        (unsigned long long) r.back_kb, r.hot_skipped, (unsigned long long) r.resident_kb);
    CHECK(r.rc == PO_OK);
    po_run(&st, PO_HELPER, 8u << 20, &r);
    CHECK(r.rc == PO_OK && st.req == 3);
    /* a request of 0 does nothing but look */
    po_run(&st, PO_HELPER, 0, &r);
    CHECK(r.rc == PO_OK && r.paged_kb == 0 && r.resident_kb == 0);
    munmap(a, n);
}

int main(void) {
    test_maps();
    test_regions();
    test_image();
    test_request();
    test_line();
    test_run();
    printf("%d checks, %d failed\n", checks, fails);
    return fails ? 1 : 0;
}
