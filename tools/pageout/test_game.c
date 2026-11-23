/*
 * Host tests for the GAME CLIENT's cold-only mode of dsforever/src/main/jni/dsf/dsf_pageout.h (po_g_run; Linux x86_64, e.g. WSL),
 * run through tools/pageout/test.sh. A host has no zram, so madvise / mincore are simulated (PO_TEST_HOOKS): a region of this
 * process is a "memory" whose pages are resident or not; a page-out clears them, a touch sets them again. Everything else (the
 * /proc/self/maps walk, the region rules, the probe table, the caps) is the real code.
 */
#define PO_HOST_TEST 1
#define PO_TEST_HOOKS 1
#include "../../dsforever/src/main/jni/dsf/dsf_pageout.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

static int fails = 0, checks = 0;
#define CHECK(c) do { checks++; if (!(c)) { fails++; printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); } } while (0)

/* ------------------------------------------------------------------ the simulated memory */
#define PG 4096u
static uintptr_t sim_lo, sim_hi;            /* the simulated region */
static unsigned char *sim_res;              /* one byte a page: 1 = resident */
static long sim_madvise_calls;
static uint64_t sim_paged_bytes;            /* bytes of resident pages the simulation sent out */

static long po_test_madvise(uintptr_t a, size_t n, int advice) {
    sim_madvise_calls++;
    if (advice != MADV_PAGEOUT) return -PO_EINVAL;
    for (uintptr_t p = a & ~(uintptr_t) (PG - 1); p < a + n; p += PG)
        if (p >= sim_lo && p < sim_hi && sim_res[(p - sim_lo) / PG]) { sim_res[(p - sim_lo) / PG] = 0; sim_paged_bytes += PG; }
    return 0;
}
static long po_test_mincore(uintptr_t a, size_t n, unsigned char *vec) {
    size_t i = 0;
    for (uintptr_t p = a; p < a + n; p += PG, i++) vec[i] = (p >= sim_lo && p < sim_hi) ? sim_res[(p - sim_lo) / PG] : 0;
    return 0;
}
/* only the simulated region is seen (it may be merged with a neighbour in maps: pages outside it read as not resident) */
static int po_test_region(uintptr_t start, uintptr_t end) { return start < sim_hi && end > sim_lo; }

static void sim_make(size_t mib) {
    size_t n = (mib + 1) << 20;
    unsigned char *a = mmap(0, n, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
    CHECK(a != MAP_FAILED);
    sim_lo = ((uintptr_t) a + PO_CHUNK - 1) & ~(PO_CHUNK - 1);
    sim_hi = sim_lo + (mib << 20);
    free(sim_res);
    sim_res = malloc((mib << 20) / PG);
    memset(sim_res, 1, (mib << 20) / PG);
    sim_paged_bytes = 0;
}
static uintptr_t chunk(int i) { return sim_lo + (uintptr_t) i * PO_CHUNK; }
static void touch(uintptr_t a, size_t n) { for (uintptr_t p = a; p < a + n; p += PG) if (p >= sim_lo && p < sim_hi) sim_res[(p - sim_lo) / PG] = 1; }
static long resident_pages(uintptr_t a, size_t n) { long r = 0; for (uintptr_t p = a; p < a + n; p += PG) r += sim_res[(p - sim_lo) / PG]; return r; }

/* ------------------------------------------------------------------ which process is the game client */
static size_t cmd(char *out, const char *const *argv) {
    size_t n = 0;
    for (int i = 0; argv[i]; i++) { size_t k = strlen(argv[i]); memcpy(out + n, argv[i], k); n += k; out[n++] = 0; }
    return n;
}
static int client(const char *comm, const char *const *argv) {
    char b[1024]; size_t n = cmd(b, argv);
    return po_client_check(b, n, comm, comm ? strlen(comm) : 0, "Wow-arm64.exe:WowB-ARM64.exe");
}

static void test_client(void) {
    /* the client: our launcher's two, the other flavours, as Wine shows them and as its loader starts them */
    { const char *a[] = {"G:\\_forever_\\Wow-arm64.exe", "-launcherlogin", 0}; CHECK(client("Wow-arm64.exe\n", a)); }
    { const char *a[] = {"G:\\_classic_beta_\\WowB-ARM64.exe", 0}; CHECK(client("WowB-ARM64.exe", a)); }
    { const char *a[] = {"/x/wine", "G:\\_forever_\\Wow-arm64.exe", 0}; CHECK(client("wine", a)); }
    { const char *a[] = {"wine64-preloader", "wine64", "G:\\_forever_\\Wow-arm64.exe", 0}; CHECK(client("wine64-preloade", a)); }
    { const char *a[] = {"C:\\Program Files\\World of Warcraft\\_classic_\\WowClassic.exe", 0}; CHECK(client("WowClassic.exe", a)); }
    { const char *a[] = {"C:\\x\\WowClassic-arm64.exe", 0}; CHECK(client("WowClassic-arm", a)); }    /* comm cut at 15 */
    { const char *a[] = {"C:\\x\\Wow.exe", 0}; CHECK(client("Wow.exe", a)); }
    { const char *a[] = {"C:\\x\\Wow-arm64.exe", 0}; CHECK(client(0, a)); }
    /* never the client: everything else that names the game, and anything unsure */
    { const char *a[] = {"C:\\windows\\explorer.exe", "/desktop=shell,1920x1080", "G:\\_forever_\\Wow-arm64.exe", 0}; CHECK(!client("explorer.exe", a)); }
    { const char *a[] = {"C:\\windows\\system32\\start.exe", "/exec", "G:\\_forever_\\Wow-arm64.exe", 0}; CHECK(!client("start.exe", a)); }
    { const char *a[] = {"G:\\_forever_\\wow-session-bridge.exe", "--port", "1", 0}; CHECK(!client("wow-session-bri", a)); }
    { const char *a[] = {"C:\\Program Files (x86)\\Battle.net\\Battle.net.exe", 0}; CHECK(!client("Battle.net.exe", a)); }
    { const char *a[] = {"C:\\ProgramData\\Battle.net\\Agent\\Agent.exe", 0}; CHECK(!client("Agent.exe", a)); }
    { const char *a[] = {"C:\\x\\BlizzardError.exe", 0}; CHECK(!client("BlizzardError.e", a)); }
    { const char *a[] = {"C:\\x\\WowError.exe", 0}; CHECK(!client("WowError.exe", a)); }
    { const char *a[] = {"C:\\x\\Wow-arm64.exe", 0}; CHECK(!client("Battle.net.exe", a)); }    /* the comm says otherwise */
    { const char *a[] = {"C:\\windows\\system32\\services.exe", 0}; CHECK(!client("services.exe", a)); }
    { const char *a[] = {"/x/wine", 0}; CHECK(!client("wine", a)); }
    { const char *a[] = {"C:\\x\\Wow-arm64.exe.bak", 0}; CHECK(!client("Wow-arm64.exe.b", a)); }
    CHECK(!po_client_check("", 0, "", 0, 0));
    /* the launcher's own names count too */
    { char b[256]; const char *a[] = {"C:\\games\\game.exe", 0}; size_t n = cmd(b, a);
      CHECK(po_client_check(b, n, "game.exe", 8, "game.exe")); CHECK(!po_client_check(b, n, "game.exe", 8, "other.exe")); }
    /* the helpers' own check is unchanged: the client is still "the game" there (a helper request is refused in it) */
    { char b[256]; const char *a[] = {"G:\\_forever_\\Wow-arm64.exe", 0}; size_t n = cmd(b, a);
      CHECK(po_image_check(b, n, "Wow-arm64.exe", 13, "Wow-arm64.exe") == PO_GAME); }
}

static void test_greq(void) {
    uint64_t seq = 0, cap = 0; char op = 0;
    CHECK(po_parse_greq("DSFPO2 7 P 32768\n", 17, &seq, &op, &cap) && seq == 7 && op == 'P' && cap == 32ull << 20);
    CHECK(po_parse_greq("DSFPO2 8 c 999999", 17, &seq, &op, &cap) && op == 'c' && cap == PO_G_REQ_MAX);   /* never more than 32 MiB */
    CHECK(po_parse_greq("DSFPO2 9 k 0", 12, &seq, &op, &cap) && op == 'k' && cap == PO_CHUNK);
    CHECK(po_parse_greq("DSFPO2 9 p 1024", 15, &seq, &op, &cap) && op == 'p');
    CHECK(!po_parse_greq("DSFPO2 9 x 1024", 15, &seq, &op, &cap));
    CHECK(!po_parse_greq("DSFPO1 9 32768", 14, &seq, &op, &cap));
    CHECK(!po_parse_greq("DSFPO2 9 c", 10, &seq, &op, &cap));
    CHECK(!po_parse_greq("DSFPO2 c 9 1", 12, &seq, &op, &cap));
    /* and a helper request is not a game request, nor the other way round */
    CHECK(!po_parse_request("DSFPO2 7 c 32768\n", 17, &seq, &cap));
    CHECK(PO_G_REQ_MAX == (uint64_t) 32 << 20 && PO_G_PASS_MAX == (uint64_t) 256 << 20);
}

static void test_rules(void) {
    const char *big = "7f00000000-7f00400000";
    char l[256]; po_map m;
    uintptr_t keep_lo = sim_lo, keep_hi = sim_hi;
    sim_lo = 0; sim_hi = ~(uintptr_t) 0;                 /* every region is "seen" for the rules */
#define OK(perms, dev, ino, name) (snprintf(l, sizeof l, "%s %s 00000000 %s %s %s", big, perms, dev, ino, name), \
        po_parse_maps_line(l, strlen(l), &m) && po_region_ok(&m, PO_CLIENT))
    CHECK(OK("rw-p", "00:00", "0", ""));                 /* Wine's VirtualAlloc'd memory */
    CHECK(OK("rw-p", "00:00", "0", "[heap]"));
    CHECK(OK("rw-p", "00:00", "0", "[anon:scudo:primary]"));
    CHECK(!OK("rw-s", "00:0f", "77", "/dev/kgsl-3d0"));  /* GPU */
    CHECK(!OK("rw-p", "00:0f", "77", "/dev/kgsl-3d0"));
    CHECK(!OK("rw-s", "00:0c", "9", "/dmabuf:"));
    CHECK(!OK("rw-s", "00:00", "0", ""));                /* shared */
    CHECK(!OK("rw-p", "00:01", "12", "/memfd:wine-shared (deleted)"));
    CHECK(!OK("rw-s", "00:01", "4096", "/dev/ashmem/wine (deleted)"));
    CHECK(!OK("rw-p", "fd:05", "1234", "/x/Wow-arm64.exe"));   /* file-backed */
    CHECK(!OK("rwxp", "00:00", "0", ""));                /* executable */
    CHECK(!OK("r-xp", "00:00", "0", ""));
    CHECK(!OK("---p", "00:00", "0", ""));
    CHECK(!OK("rw-p", "00:00", "0", "[stack]"));
    CHECK(!OK("rw-p", "00:00", "0", "[anon:stack_and_tls:77]"));
    CHECK(!OK("rw-p", "00:00", "0", "[anon:thread signal stack]"));
    CHECK(!OK("rw-p", "00:00", "0", "[anon:dalvik-main space]"));
#undef OK
    sim_lo = keep_lo; sim_hi = keep_hi;
}

static po_state st;
static po_gstate g;

static void run(char op, uint64_t cap, po_gresult *r) { po_g_run(&g, op, cap, r); }

/* a pass of 64 MiB: the chunks whose samples were touched between probe and commit are never paged (cold-only) */
static void test_cold_only(void) {
    sim_make(64);
    po_state_init(&st, PG); po_g_init(&g, &st);
    po_gresult r;
    run('c', 32u << 20, &r); CHECK(r.rc == PO_NO_PASS && r.paged_kb == 0);          /* no pass: nothing */
    run('P', 0, &r);
    printf("probe: rc=%d probed=%u sampled=%llu KB more=%d ms=%llu\n", r.rc, r.probed, (unsigned long long) r.sampled_kb, r.more,
        (unsigned long long) r.ms);
    CHECK(r.rc == PO_OK && r.probed == 64 && r.more == 0);
    CHECK(r.sampled_kb == 64u * PO_G_SLICES * PO_G_SLICE_PAGES * PG / 1024u);       /* 8 sample pages a chunk */
    CHECK(r.paged_kb == r.sampled_kb && r.cold == 0);
    for (int i = 0; i < 64; i++) CHECK(resident_pages(chunk(i), PO_CHUNK) == 256 - 8);   /* only the samples left */
    /* chunks 0..9 in use: the program touched (some of) each one's sample pages; chunk 20: a page next to a sample */
    for (int i = 0; i < 10; i++) touch(chunk(i) + (3 * PO_CHUNK) / 8, PG);
    /* chunk 30 is no longer such a region (read-only now): never touched again */
    CHECK(mprotect((void *) chunk(30), PO_CHUNK, PROT_READ) == 0);
    uint64_t total = 0; int reqs = 0, more = 1; uint32_t inuse = 0, cold = 0;
    while (more && reqs < 20) {
        run('c', 32u << 20, &r);
        CHECK(r.rc == PO_OK);
        CHECK(r.resident_kb <= 32u * 1024);                                       /* a request: at most 32 MiB */
        total += r.paged_kb; inuse += r.inuse; cold += r.cold; more = r.more; reqs++;
    }
    printf("commit: %d requests, %u cold, %u in use, %llu KB paged, pass %llu KB\n", reqs, cold, inuse, (unsigned long long) total,
        (unsigned long long) r.pass_kb);
    CHECK(reqs == 2);                                                              /* 53 cold chunks of 992 KiB: 33 + 20 */
    CHECK(inuse == 10 && cold == 53);
    for (int i = 0; i < 10; i++) CHECK(resident_pages(chunk(i), PO_CHUNK) == 256 - 8 + 1);   /* in use: never paged */
    CHECK(resident_pages(chunk(30), PO_CHUNK) == 256 - 8);                       /* not eligible any more: left as it was */
    for (int i = 10; i < 64; i++) if (i != 30) CHECK(resident_pages(chunk(i), PO_CHUNK) == 0);
    CHECK(total == 53u * 248 * PG / 1024u);
    CHECK(r.pass_kb == total + 64u * 8 * PG / 1024u && r.pass_kb <= 256u * 1024);
    run('c', 32u << 20, &r); CHECK(r.paged_kb == 0 && r.more == 0);              /* nothing left in this pass */
    mprotect((void *) chunk(30), PO_CHUNK, PROT_READ | PROT_WRITE);
    /* what came straight back: half of chunk 12 and all of 13 are touched again; 'k' measures, and they are in use for a while */
    touch(chunk(12), PO_CHUNK / 2); touch(chunk(13), PO_CHUNK);
    run('k', 0, &r);
    printf("check: back %llu KB\n", (unsigned long long) r.back_kb);
    CHECK(r.rc == PO_OK && r.back_kb == (128u + 248u) * PG / 1024u);
    /* the next pass: 0..9 (in use at the last commit) and 12, 13 (came back) are not probed */
    run('P', 0, &r);
    CHECK(r.rc == PO_OK && r.inuse == 12);
    CHECK(resident_pages(chunk(13), PO_CHUNK) == 256);
}

/* a big process: the probe runs over several requests, and the pass stops at 256 MiB, samples included */
static void test_caps(void) {
    sim_make(600);
    po_state_init(&st, PG); po_g_init(&g, &st);
    po_gresult r;
    run('P', 0, &r);
    int probes = 1;
    CHECK(r.rc == PO_OK && r.probed == PO_G_PROBE_REQ && r.more == 1);          /* 128 chunks a request */
    while (r.more && probes < 20) { run('p', 0, &r); probes++; CHECK(r.rc == PO_OK); }
    printf("probe: %d requests, %u probed, pass %llu KB\n", probes, r.probed, (unsigned long long) r.pass_kb);
    CHECK(probes == 4 && r.probed == PO_G_PROBE_MAX);                            /* the table: 512 chunks, then the probe stops */
    uint64_t sampled = r.pass_kb;
    uint64_t total = 0; int reqs = 0, paging = 0;
    do {
        run('c', 32u << 20, &r);
        CHECK(r.resident_kb <= 32u * 1024);
        if (r.paged_kb) paging++;
        total += r.paged_kb; reqs++;
    } while (r.more && reqs < 40);
    printf("commit: %d requests (%d paging), %llu KB paged, pass %llu KB\n", reqs, paging, (unsigned long long) total,
        (unsigned long long) r.pass_kb);
    CHECK(r.more == 0);
    CHECK(r.pass_kb <= 256u * 1024 && r.pass_kb == total + sampled);           /* the pass: at most 256 MiB */
    CHECK(r.pass_kb > 255u * 1024);
    CHECK(sim_paged_bytes / 1024u == r.pass_kb);                                  /* nothing beyond what was counted */
    CHECK(paging == 8);                                                            /* 32 MiB at a time */
    /* the next pass probes on where this one stopped (chunk 512 on), then wraps round */
    run('P', 0, &r);
    CHECK(r.rc == PO_OK && r.probed > 0);
    CHECK(g.pstart == chunk(PO_G_PROBE_MAX));
}

static void test_line(void) {
    po_gresult r = {0, 'c', 412, 3264, 33, 4, 32768, 32736, 1024, 40000, 1, 41};
    char out[PO_LINE_MAX];
    size_t n = po_gresult_line(12, &r, out); out[n] = 0;
    CHECK(strcmp(out, "DSFPO2 seq=12 rc=0 op=c probed=412 sampled_kb=3264 cold=33 inuse=4 resident_kb=32768 paged_kb=32736 "
        "back_kb=1024 pass_kb=40000 more=1 ms=41\n") == 0);
    po_gresult big = {7, 'P', 4000000000u, ~0ull, 4000000000u, 4000000000u, ~0ull, ~0ull, ~0ull, ~0ull, 1, ~0ull};
    CHECK(po_gresult_line(~0ull, &big, out) < PO_LINE_MAX);
}

int main(void) {
    test_client();
    test_greq();
    sim_make(4);
    test_rules();
    test_cold_only();
    test_caps();
    test_line();
    printf("game mode: %d checks, %d failed\n", checks, fails);
    return fails ? 1 : 0;
}
