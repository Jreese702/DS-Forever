/*
 * DS FOREVER'S IDLE MEMORY INTO THE DEVICE'S OWN ZRAM (the user's picks, 2026-11-23: "Wine helpers" + "Our own app"; then "Yes,
 * carefully" for the WoW client itself: only idle (cold) memory, only at character select before Enter World and when memory is
 * critically low in the world, capped).
 * DS Forever's own code, written from the Linux man pages madvise(2) (MADV_PAGEOUT, Linux 5.4), mincore(2) and proc(5)
 * (/proc/self/maps). No root, no system setting: a process asks the kernel to reclaim ITS OWN private anonymous pages; the kernel
 * moves them into the swap device the firmware already has (zram on every Thor) and faults them back in on the next touch, so
 * nothing changes for the program but the time of that touch. No memory content is ever read: only the address ranges
 * (/proc/self/maps) and which pages are resident (mincore).
 *
 * One header for both users: the preload in the Wine helper processes (dsf_pageout_hook.c, run inside a signal handler: raw
 * system calls only, no libc, no allocation, static buffers) and DS Forever's own process (dsf_heap.c, JNI, its own thread).
 *
 * The budgeted page-out ([po_run]): the eligible regions ([po_region_ok]) in 1 MiB chunks, starting where the last request
 * stopped; a chunk with fewer than [PO_MIN_PAGES] resident pages is skipped; at most [cap] bytes of resident pages per request.
 * A chunk whose pages were mostly back in memory by the next request is in use: it is skipped for the next [PO_HOT_FOR] requests.
 * The kernel itself refuses locked (mlock), huge-TLB and PFN-mapped ranges (EINVAL) and leaves shared and pinned pages alone.
 *
 * THE GAME CLIENT (PO_CLIENT, [po_g_run]): a separate, COLD-ONLY mode, never the budgeted sweep above. A pass is two steps some
 * seconds apart: the probe sends a few sample pages of each resident 1 MiB chunk out ([PO_G_SLICES] slices of [PO_G_SLICE_PAGES]);
 * the commit pages a chunk out only if NONE of its sample pages came back in the meantime (nothing in it was touched: cold). A chunk
 * whose samples came back is in use and is skipped for [PO_G_HOT_FOR] passes, as is a chunk whose paged memory came straight back.
 * At most [PO_G_REQ_MAX] per request and [PO_G_PASS_MAX] per pass, samples included; never the whole process.
 * WHAT THIS CODE DOES IN THE GAME'S PROCESS, AND NOTHING ELSE (the ToS note): it reads /proc/self/maps (address ranges and their
 * kinds), /proc/self/cmdline and /proc/self/comm (to know it is the game), asks mincore(2) which of its own pages are resident and
 * calls madvise(MADV_PAGEOUT) on its own private anonymous ranges. It never reads or writes the game's memory contents, never
 * patches, hooks or calls a game function, and only answers DS Forever's own signal; the kernel brings every page back, unchanged,
 * on its next touch.
 */
#ifndef DSF_PAGEOUT_H
#define DSF_PAGEOUT_H

#include <stddef.h>
#include <stdint.h>
#include <sys/syscall.h>

#ifndef MADV_PAGEOUT
#define MADV_PAGEOUT 21
#endif

#define PO_CHUNK ((uintptr_t) 1 << 20)          /* 1 MiB */
#define PO_MIN_REGION ((uintptr_t) 64 << 10)    /* a region under 64 KiB is never worth a call */
#define PO_MIN_PAGES 16                         /* a chunk with fewer resident pages is skipped */
#define PO_CAP_MAX ((uint64_t) 32 << 20)        /* the hard cap per request, whatever is asked: 32 MiB */
#define PO_DONE_MAX 64
#define PO_HOT_MAX 128
#define PO_HOT_FOR 4
#define PO_VEC_MAX (PO_CHUNK / 4096)            /* mincore's answer for one chunk at the smallest page size */
#define PO_LINE_MAX 512                         /* a result line's room (the game's is the longer) */

/* the request's answers (the result line's rc=) */
#define PO_OK 0
#define PO_NOT_HELPER 1     /* this process is not a known Wine helper: nothing done */
#define PO_GAME 2           /* this process names the game: nothing done, ever */
#define PO_UNSUPPORTED 3    /* MADV_PAGEOUT is not in this kernel (EINVAL): nothing done, ever again */
#define PO_BAD_REQUEST 4
#define PO_NO_MAPS 5

/* the region rules: a Wine helper's heaps, DS Forever's own native allocator only, or the game client's heaps (cold-only, po_g_run) */
#define PO_HELPER 1
#define PO_SELF 2
#define PO_CLIENT 3

/* ------------------------------------------------------------------------------------------------ raw system calls */

#if defined(__aarch64__) && !defined(PO_HOST_TEST)
/* aarch64: the kernel's own convention (x8 = number, x0..x5 = arguments, x0 = result or -errno); no libc, no errno */
static inline long po_sc(long n, long a, long b, long c, long d, long e, long f) {
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5) : "memory", "cc");
    return x0;
}
#else
/* the host tests (x86_64 Linux): libc's syscall(), its errno turned into the same -errno result */
#include <errno.h>
#include <unistd.h>
static inline long po_sc(long n, long a, long b, long c, long d, long e, long f) {
    int saved = errno;
    long r = syscall(n, a, b, c, d, e, f);
    if (r == -1) r = -errno;
    errno = saved;
    return r;
}
#endif

#define PO_AT_FDCWD (-100)
#define PO_O_RDONLY 0
#define PO_O_WRONLY 1
#define PO_O_CREAT 0100
#define PO_O_TRUNC 01000
#define PO_O_CLOEXEC 02000000
#define PO_EINVAL 22
#define PO_ENOMEM 12
#define PO_EINTR 4

static inline long po_open(const char *path, int flags, int mode) { return po_sc(SYS_openat, PO_AT_FDCWD, (long) path, flags | PO_O_CLOEXEC, mode, 0, 0); }
static inline long po_read(long fd, void *buf, size_t n) {
    long r;
    do { r = po_sc(SYS_read, fd, (long) buf, (long) n, 0, 0, 0); } while (r == -PO_EINTR);
    return r;
}
static inline long po_write(long fd, const void *buf, size_t n) { return po_sc(SYS_write, fd, (long) buf, (long) n, 0, 0, 0); }
static inline void po_close(long fd) { po_sc(SYS_close, fd, 0, 0, 0, 0, 0); }
#ifdef PO_TEST_HOOKS
/* tools/pageout/test_game.c: a simulated memory (residency without a swap device) and the regions it may see */
static long po_test_madvise(uintptr_t a, size_t n, int advice);
static long po_test_mincore(uintptr_t a, size_t n, unsigned char *vec);
static int po_test_region(uintptr_t start, uintptr_t end);
static inline long po_madvise(uintptr_t a, size_t n, int advice) { return po_test_madvise(a, n, advice); }
static inline long po_mincore(uintptr_t a, size_t n, unsigned char *vec) { return po_test_mincore(a, n, vec); }
#else
static inline long po_madvise(uintptr_t a, size_t n, int advice) { return po_sc(SYS_madvise, (long) a, (long) n, advice, 0, 0, 0); }
static inline long po_mincore(uintptr_t a, size_t n, unsigned char *vec) { return po_sc(SYS_mincore, (long) a, (long) n, (long) vec, 0, 0, 0); }
#endif
static inline long po_getpid(void) { return po_sc(SYS_getpid, 0, 0, 0, 0, 0, 0); }
/* renameat, not renameat2: the call bionic's own rename() makes, so it is in every Android version's app seccomp list (a call
 * outside that list kills the process with SIGSYS) */
static inline long po_rename(const char *from, const char *to) { return po_sc(SYS_renameat, PO_AT_FDCWD, (long) from, PO_AT_FDCWD, (long) to, 0, 0); }

/* monotonic milliseconds (CLOCK_MONOTONIC = 1) */
static inline uint64_t po_ms(void) {
    struct { long s; long ns; } ts = {0, 0};
    po_sc(SYS_clock_gettime, 1, (long) &ts, 0, 0, 0, 0);
    return (uint64_t) ts.s * 1000u + (uint64_t) ts.ns / 1000000u;
}

/* PROT_READ|PROT_WRITE, MAP_PRIVATE|MAP_ANONYMOUS */
static inline long po_mmap_anon(size_t n) { return po_sc(SYS_mmap, 0, (long) n, 3, 0x22, -1, 0); }
static inline void po_munmap(uintptr_t a, size_t n) { po_sc(SYS_munmap, (long) a, (long) n, 0, 0, 0, 0); }

/* ------------------------------------------------------------------------------------------------ small string helpers */

static inline char po_lc(char c) { return (c >= 'A' && c <= 'Z') ? (char) (c - 'A' + 'a') : c; }
static inline size_t po_strlen(const char *s) { size_t n = 0; while (s[n]) n++; return n; }

/* [s, s+n) starts with [pre] (exact case) */
static inline int po_starts(const char *s, size_t n, const char *pre) {
    size_t i = 0;
    for (; pre[i]; i++) if (i >= n || s[i] != pre[i]) return 0;
    return 1;
}
/* [s, s+n) equals [w] without regard to ASCII case */
static inline int po_ieq(const char *s, size_t n, const char *w) {
    size_t i = 0;
    for (; w[i]; i++) if (i >= n || po_lc(s[i]) != po_lc(w[i])) return 0;
    return i == n;
}
/* [s, s+n) starts with [pre] without regard to ASCII case */
static inline int po_istarts(const char *s, size_t n, const char *pre) {
    size_t i = 0;
    for (; pre[i]; i++) if (i >= n || po_lc(s[i]) != po_lc(pre[i])) return 0;
    return 1;
}
/* [s, s+n) contains [w] without regard to ASCII case */
static inline int po_icontains(const char *s, size_t n, const char *w) {
    size_t m = po_strlen(w);
    if (m == 0) return 1;
    for (size_t i = 0; i + m <= n; i++) if (po_istarts(s + i, n - i, w)) return 1;
    return 0;
}

/* decimal digits for [v] into [out] (no terminator); the count */
static inline size_t po_utoa(uint64_t v, char *out) {
    char t[24]; size_t n = 0;
    do { t[n++] = (char) ('0' + v % 10); v /= 10; } while (v && n < sizeof t);
    for (size_t i = 0; i < n; i++) out[i] = t[n - 1 - i];
    return n;
}

/* ------------------------------------------------------------------------------------------------ /proc/self/maps */

typedef struct {
    uintptr_t start, end;
    char r, w, x, p;            /* the permissions: 'r' 'w' 'x' and 'p' (private) or 's' (shared) */
    int anon_dev;               /* the device field is 00:00 */
    unsigned long inode;
    const char *name;           /* the path or [kind] (not terminated), may be empty */
    size_t name_len;
} po_map;

static inline int po_hexval(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    c = po_lc(c);
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}

/* one maps line ("start-end perms offset dev inode   name", proc(5)) -> [m]; 0 = not such a line */
static inline int po_parse_maps_line(const char *l, size_t n, po_map *m) {
    size_t i = 0; uintptr_t v = 0; int d, any = 0;
    while (i < n && (d = po_hexval(l[i])) >= 0) { v = (v << 4) | (uintptr_t) d; i++; any = 1; }
    if (!any || i >= n || l[i] != '-') return 0;
    m->start = v; i++; v = 0; any = 0;
    while (i < n && (d = po_hexval(l[i])) >= 0) { v = (v << 4) | (uintptr_t) d; i++; any = 1; }
    if (!any || i >= n || l[i] != ' ' || v <= m->start) return 0;
    m->end = v; i++;
    if (i + 5 > n) return 0;
    m->r = l[i]; m->w = l[i + 1]; m->x = l[i + 2]; m->p = l[i + 3];
    if (l[i + 4] != ' ') return 0;
    i += 5;
    while (i < n && l[i] != ' ') i++;                      /* offset */
    if (i >= n) return 0;
    i++;
    size_t dev = i;
    while (i < n && l[i] != ' ') i++;                      /* device major:minor */
    if (i >= n) return 0;
    m->anon_dev = 1;
    for (size_t k = dev; k < i; k++) if (l[k] != '0' && l[k] != ':') m->anon_dev = 0;
    if (i == dev) return 0;
    i++;
    unsigned long ino = 0; any = 0;
    while (i < n && l[i] >= '0' && l[i] <= '9') { ino = ino * 10 + (unsigned long) (l[i] - '0'); i++; any = 1; }
    if (!any) return 0;
    m->inode = ino;
    while (i < n && l[i] == ' ') i++;
    size_t e = n;
    while (e > i && (l[e - 1] == '\n' || l[e - 1] == '\r')) e--;
    m->name = l + i; m->name_len = e - i;
    return 1;
}

/*
 * a region worth paging out for [mode]: private, readable, writable, not executable, anonymous (no file, device 00:00, inode 0),
 * at least [PO_MIN_REGION], and by name:
 *  PO_HELPER: unnamed (a Windows heap, VirtualAlloc'd memory), [heap], or the native allocator's ([anon:scudo:...],
 *             [anon:libc_malloc]); never a named stack ([stack], [anon:stack_and_tls:...], [anon:thread signal stack]), [vdso] /
 *             [vvar], a library's .bss or any other named kind. Wine allocates its threads' stacks itself (unnamed, like a heap):
 *             those are eligible, and an idle thread's cold stack pages may be paged (MADV_PAGEOUT never loses data; a touch
 *             brings them back); only the request's own stack is kept ([po_region]).
 *  PO_SELF:   DS Forever's own native allocator only ([anon:scudo:...], [anon:libc_malloc]): never the Java heap ([anon:dalvik-...]),
 *             the JIT, stacks, unnamed memory or anything a driver maps (kgsl, dmabuf, ashmem, memfd: files, never anonymous).
 * Guard pages (---p), shared memory ('s'), file mappings and device memory fail the first checks.
 */
static inline int po_region_ok(const po_map *m, int mode) {
    if (m->r != 'r' || m->w != 'w' || m->x != '-' || m->p != 'p') return 0;
    if (!m->anon_dev || m->inode != 0) return 0;
    if (m->end - m->start < PO_MIN_REGION) return 0;
    const char *s = m->name; size_t n = m->name_len;
    int alloc = (po_starts(s, n, "[anon:scudo:") && !po_icontains(s, n, "reserve")) || po_ieq(s, n, "[anon:libc_malloc]");
#ifdef PO_TEST_HOOKS
    if (!po_test_region(m->start, m->end)) return 0;
#endif
    if (mode == PO_SELF) return alloc;
    /* the game client: the same kinds as a helper's (Wine's VirtualAlloc'd and heap memory is unnamed anonymous); every GPU, dmabuf,
     * kgsl, memfd, ashmem, file, shared or executable mapping failed above */
    if (mode == PO_HELPER || mode == PO_CLIENT) return n == 0 || po_ieq(s, n, "[heap]") || alloc;
    return 0;
}

/* ------------------------------------------------------------------------------------------------ which process this is */

/* the last path part of [a, a+n): after the last '/' or '\' */
static inline const char *po_base(const char *a, size_t n, size_t *bn) {
    size_t k = n;
    while (k > 0 && a[k - 1] != '/' && a[k - 1] != '\\') k--;
    *bn = n - k;
    return a + k;
}

/* Wine's own loader binaries: the process's name is the argument after them */
static inline int po_is_loader(const char *b, size_t n) {
    return po_ieq(b, n, "wine") || po_ieq(b, n, "wine64") || po_ieq(b, n, "wine-preloader") || po_ieq(b, n, "wine64-preloader");
}

/*
 * Wine's own background processes next to the game (the user's pick: "Only Wine's idle background processes next to WoW"): an
 * ALLOW list, so anything else - the game, a launcher, an unknown program, a process still starting under the loader's name - is
 * left alone. With or without ".exe". Not wineserver: every Windows call of the game goes through it (not idle).
 */
static const char *const PO_HELPERS[] = {
    "services", "winedevice", "plugplay", "svchost", "rpcss", "explorer", "conhost", "tabtip", "start", "wineboot",
    "winemenubuilder", "winhandler", 0
};
static inline int po_is_helper(const char *b, size_t n) {
    size_t m = n;
    if (m > 4 && po_ieq(b + m - 4, 4, ".exe")) m -= 4;
    for (int i = 0; PO_HELPERS[i]; i++) if (po_ieq(b, m, PO_HELPERS[i])) return 1;
    return 0;
}

/* a name that is (or launches) the game: Wow*.exe of any flavour (Wow-arm64.exe, WowB-ARM64.exe, WowClassic.exe, our
 * wow-session-bridge.exe), anything Blizzard / Battle.net, or one of [games] (the launcher's ':'-separated exe names) */
static inline int po_is_gameish(const char *b, size_t n, const char *games) {
    if (po_istarts(b, n, "wow") || po_icontains(b, n, "blizzard") || po_icontains(b, n, "battle.net")) return 1;
    if (games) {
        const char *g = games;
        while (*g) {
            const char *e = g; while (*e && *e != ':') e++;
            if (e > g) {
                size_t gn = (size_t) (e - g), i = 0;
                if (gn == n) { for (; i < n; i++) if (po_lc(b[i]) != po_lc(g[i])) break; if (i == n) return 1; }
            }
            g = *e ? e + 1 : e;
        }
    }
    return 0;
}

/*
 * THE ONE QUESTION BEFORE ANY PAGE-OUT IN A WINE PROCESS: [cmd, cmd+n) = /proc/self/cmdline (NUL-separated arguments), [comm,
 * comm+cn) = /proc/self/comm (Wine sets it to the exe's name). PO_OK only when the image (the first argument that is not Wine's
 * loader, as its last path part) is a known helper AND no argument and not the comm names the game ([po_is_gameish]: explorer's
 * "/desktop=shell,... G:\_forever_\Wow-arm64.exe" is the game's starter, so it is left alone too). Anything unsure: not OK.
 */
static inline int po_image_check(const char *cmd, size_t n, const char *comm, size_t cn, const char *games) {
    while (cn > 0 && (comm[cn - 1] == '\n' || comm[cn - 1] == 0)) cn--;
    if (cn > 0 && po_is_gameish(comm, cn, games)) return PO_GAME;
    const char *img = 0; size_t imgn = 0;
    size_t i = 0;
    while (i < n) {
        size_t e = i; while (e < n && cmd[e]) e++;
        size_t bn; const char *b = po_base(cmd + i, e - i, &bn);
        if (bn > 0 && po_is_gameish(b, bn, games)) return PO_GAME;
        /* an argument may hold a quoted Windows path with spaces: its last path part names the game too */
        if (e > i && (po_icontains(cmd + i, e - i, "\\wow") || po_icontains(cmd + i, e - i, "/wow") ||
                      po_icontains(cmd + i, e - i, "world of warcraft") || po_icontains(cmd + i, e - i, "battle.net") ||
                      po_icontains(cmd + i, e - i, "blizzard"))) return PO_GAME;
        if (!img && bn > 0 && !po_is_loader(b, bn)) { img = b; imgn = bn; }
        i = e + 1;
    }
    if (!img) return PO_NOT_HELPER;
    return po_is_helper(img, imgn) ? PO_OK : PO_NOT_HELPER;
}

/* ------------------------------------------------------------------------------------------------ the request */

/* "DSFPO1 <seq> <cap KiB>" -> [seq], [cap] bytes (clamped to 1 MiB .. [PO_CAP_MAX]); 0 = not a request */
static inline int po_parse_request(const char *s, size_t n, uint64_t *seq, uint64_t *cap) {
    if (!po_starts(s, n, "DSFPO1 ")) return 0;
    size_t i = 7; uint64_t a = 0, b = 0; int any = 0;
    while (i < n && s[i] >= '0' && s[i] <= '9') { a = a * 10 + (uint64_t) (s[i] - '0'); i++; any = 1; if (a > 1000000000000ull) return 0; }
    if (!any || i >= n || s[i] != ' ') return 0;
    i++; any = 0;
    while (i < n && s[i] >= '0' && s[i] <= '9') { b = b * 10 + (uint64_t) (s[i] - '0'); i++; any = 1; if (b > 100000000ull) return 0; }
    if (!any) return 0;
    if (i < n && s[i] != '\n' && s[i] != ' ' && s[i] != 0) return 0;
    uint64_t bytes = b * 1024u;
    if (bytes < PO_CHUNK) bytes = PO_CHUNK;
    if (bytes > PO_CAP_MAX) bytes = PO_CAP_MAX;
    *seq = a; *cap = bytes;
    return 1;
}

/* ------------------------------------------------------------------------------------------------ the page-out */

typedef struct { uintptr_t addr; uint32_t pages, left; } po_done; /* a chunk the last request paged out: pages moved, pages left */
typedef struct { uintptr_t addr; uint32_t until; } po_hot;       /* a chunk that came straight back: skipped until request [until] */

typedef struct {
    uint32_t req;               /* requests served */
    uintptr_t cursor;           /* where the last request stopped (0 = from the start) */
    int supported;              /* 0 = not probed, 1 = MADV_PAGEOUT works, -1 = not in this kernel */
    uintptr_t sp;               /* this request's own stack pointer: the chunks around it are never paged (they would come straight back) */
    size_t page;                /* the page size */
    int ndone, nhot;
    po_done done[PO_DONE_MAX];
    po_hot hot[PO_HOT_MAX];
    unsigned char vec[PO_VEC_MAX];
    char buf[4096];             /* /proc/self/maps, read in pieces */
    char line[512];             /* one maps line (a longer name is cut: only its start matters) */
} po_state;

typedef struct {
    int rc;
    uint32_t regions;           /* eligible regions */
    uint64_t eligible_kb;       /* their size */
    uint64_t resident_kb;       /* their resident pages in the chunks looked at */
    uint64_t paged_kb;          /* pages that left memory */
    uint64_t back_kb;           /* pages of the last request's chunks found back in memory */
    uint32_t hot_skipped;       /* chunks skipped as in use */
    uint64_t ms;
} po_result;

static inline void po_state_init(po_state *st, size_t page) {
    st->req = 0; st->cursor = 0; st->supported = 0; st->sp = 0; st->ndone = 0; st->nhot = 0;
    st->page = (page >= 4096 && page <= 65536 && (page & (page - 1)) == 0) ? page : 4096;
}

/* resident pages of [a, a+n) (mincore), -1 = not mapped any more */
static inline long po_resident(po_state *st, uintptr_t a, size_t n) {
    size_t pages = (n + st->page - 1) / st->page;
    if (pages > PO_VEC_MAX) pages = PO_VEC_MAX;
    if (po_mincore(a, pages * st->page, st->vec) != 0) return -1;
    long r = 0;
    for (size_t i = 0; i < pages; i++) r += st->vec[i] & 1;
    return r;
}

static inline int po_is_hot(const po_state *st, uintptr_t a) {
    for (int i = 0; i < st->nhot; i++) if (st->hot[i].addr == a) return 1;
    return 0;
}

/* does this kernel know MADV_PAGEOUT? One page of our own, touched and paged out; EINVAL = no (Linux before 5.4) */
static inline int po_probe(po_state *st) {
    if (st->supported) return st->supported;
    long a = po_mmap_anon(st->page);
    if (a < 0) return 0;                                   /* no page to try with: ask again next time */
    *(volatile char *) a = 1;
    long r = po_madvise((uintptr_t) a, st->page, MADV_PAGEOUT);
    po_munmap((uintptr_t) a, st->page);
    st->supported = (r == -PO_EINVAL) ? -1 : 1;
    return st->supported;
}

/* the chunks of the last request whose pages came back: in use, skipped for [PO_HOT_FOR] requests; then the old marks expire */
static inline void po_age(po_state *st, po_result *res) {
    for (int i = 0; i < st->ndone; i++) {
        long r = po_resident(st, st->done[i].addr, PO_CHUNK);
        if (r < 0) continue;
        uint32_t back = (uint32_t) r > st->done[i].left ? (uint32_t) r - st->done[i].left : 0;
        if (back > st->done[i].pages) back = st->done[i].pages;
        res->back_kb += (uint64_t) back * st->page / 1024u;
        if (back * 2u >= st->done[i].pages && st->nhot < PO_HOT_MAX) {
            st->hot[st->nhot].addr = st->done[i].addr; st->hot[st->nhot].until = st->req + PO_HOT_FOR; st->nhot++;
        }
    }
    st->ndone = 0;
    int k = 0;
    for (int i = 0; i < st->nhot; i++) if (st->hot[i].until > st->req) st->hot[k++] = st->hot[i];
    st->nhot = k;
}

/* one region's chunks in [from, to) (to = 0: no end): paged until [*budget] is spent; 1 = the budget ran out (cursor set) */
static inline int po_region(po_state *st, const po_map *m, uintptr_t from, uintptr_t to, uint64_t *budget, po_result *res) {
    uintptr_t c = m->start;
    while (c < m->end) {
        uintptr_t next = (c & ~(PO_CHUNK - 1)) + PO_CHUNK;
        if (next > m->end || next <= c) next = m->end;
        if (c >= from && (to == 0 || c < to)) {
            if (po_is_hot(st, c)) { res->hot_skipped++; c = next; continue; }
            /* the stack this request runs on (a Wine helper's signal stack or thread stack is unnamed anonymous memory, so it is
             * eligible like any heap): 64 KiB below to 256 KiB above the stack pointer stay */
            if (st->sp && c < st->sp + ((uintptr_t) 256 << 10) && next > st->sp - ((uintptr_t) 64 << 10)) { c = next; continue; }
            long r = po_resident(st, c, next - c);
            if (r >= PO_MIN_PAGES) {
                res->resident_kb += (uint64_t) r * st->page / 1024u;
                long ad = po_madvise(c, next - c, MADV_PAGEOUT);
                if (ad == 0) {
                    long r2 = po_resident(st, c, next - c);
                    long moved = (r2 >= 0 && r2 < r) ? r - r2 : 0;
                    res->paged_kb += (uint64_t) moved * st->page / 1024u;
                    if (moved > 0 && st->ndone < PO_DONE_MAX) {
                        st->done[st->ndone].addr = c; st->done[st->ndone].pages = (uint32_t) moved;
                        st->done[st->ndone].left = (uint32_t) (r - moved); st->ndone++;
                    }
                } else if (ad == -PO_EINVAL && st->supported == 1) {
                    /* a locked / huge-page / PFN range (the kernel refuses those): skip the region */
                    return 0;
                }
                uint64_t spent = (uint64_t) r * st->page;
                if (spent >= *budget) { *budget = 0; st->cursor = next; return 1; }
                *budget -= spent;
            }
        }
        c = next;
    }
    return 0;
}

/* one pass over /proc/self/maps: count the eligible regions when [count] (the whole file), page the chunks in [from, to) while
 *  [*budget] lasts */
static inline void po_pass(po_state *st, int mode, uintptr_t from, uintptr_t to, int count, uint64_t *budget, po_result *res) {
    long fd = po_open("/proc/self/maps", PO_O_RDONLY, 0);
    if (fd < 0) { res->rc = PO_NO_MAPS; return; }
    size_t ln = 0;
    for (;;) {
        long got = po_read(fd, st->buf, sizeof st->buf);
        if (got <= 0) break;
        for (long i = 0; i < got; i++) {
            char ch = st->buf[i];
            /* a line longer than [line] keeps its start: addresses, permissions and the name's first 400-odd characters */
            if (ch != 10) { if (ln < sizeof st->line) st->line[ln++] = ch; continue; }   /* 10 = '\n' */
            po_map m;
            if (po_parse_maps_line(st->line, ln, &m) && po_region_ok(&m, mode)) {
                if (count) { res->regions++; res->eligible_kb += (m.end - m.start) / 1024u; }
                if (*budget > 0) po_region(st, &m, from, to, budget, res);
            }
            ln = 0;
        }
        if (!count && *budget == 0) break;
    }
    po_close(fd);
}

/*
 * ONE REQUEST: at most [cap] bytes of resident pages of this process's eligible regions ([mode]) paged out, from where the last
 * request stopped round to it again. [res] says what happened. Not reentrant: the caller holds it (the hook: a busy flag; ours: a
 * mutex). Only system calls: safe inside a signal handler.
 */
static inline void po_run(po_state *st, int mode, uint64_t cap, po_result *res) {
    uint64_t t0 = po_ms();
    res->rc = PO_OK; res->regions = 0; res->eligible_kb = 0; res->resident_kb = 0; res->paged_kb = 0; res->back_kb = 0;
    res->hot_skipped = 0; res->ms = 0;
    if (cap > PO_CAP_MAX) cap = PO_CAP_MAX;
    st->sp = (uintptr_t) __builtin_frame_address(0);
    int sup = po_probe(st);
    if (sup < 0) { res->rc = PO_UNSUPPORTED; return; }
    po_age(st, res);
    uint64_t budget = cap;
    uintptr_t start = st->cursor;
    st->cursor = 0;
    po_pass(st, mode, start, 0, 1, &budget, res);
    if (res->rc == PO_OK && budget > 0 && start > 0) po_pass(st, mode, 0, start, 0, &budget, res);
    st->req++;
    res->ms = po_ms() - t0;
}

/* the result line (the app reads it): "DSFPO1 seq=12 rc=0 regions=14 eligible_kb=.. resident_kb=.. paged_kb=.. back_kb=..
 *  hot=2 ms=7\n" -> [out] (at least 256 bytes); its length */
static inline size_t po_result_line(uint64_t seq, const po_result *r, char *out) {
    size_t n = 0;
#define PO_PUT(s) do { const char *_s = (s); while (*_s) out[n++] = *_s++; } while (0)
#define PO_NUM(v) do { n += po_utoa((uint64_t) (v), out + n); } while (0)
    PO_PUT("DSFPO1 seq="); PO_NUM(seq);
    PO_PUT(" rc="); PO_NUM(r->rc);
    PO_PUT(" regions="); PO_NUM(r->regions);
    PO_PUT(" eligible_kb="); PO_NUM(r->eligible_kb);
    PO_PUT(" resident_kb="); PO_NUM(r->resident_kb);
    PO_PUT(" paged_kb="); PO_NUM(r->paged_kb);
    PO_PUT(" back_kb="); PO_NUM(r->back_kb);
    PO_PUT(" hot="); PO_NUM(r->hot_skipped);
    PO_PUT(" ms="); PO_NUM(r->ms);
    PO_PUT("\n");
#undef PO_PUT
#undef PO_NUM
    return n;
}

/* ------------------------------------------------------------------------------------------------ the game client (cold-only) */

/*
 * THE GAME CLIENT'S OWN EXE NAMES (with or without ".exe"), or one of the launcher's ([games]): only such a process, with the
 * launcher's game mode on, is ever paged as the game, and only cold-only ([po_g_run]). Everything else that names the game stays
 * refused as before: Battle.net and its Agent, anything Blizzard, our sign-in bridge (wow-session-bridge.exe), explorer.exe or
 * start.exe starting the game.
 */
static const char *const PO_CLIENTS[] = {
    "wow", "wow-arm64", "wowb", "wowb-arm64", "wowt", "wowt-arm64", "wowclassic", "wowclassic-arm64", 0
};
static inline int po_is_client_name(const char *b, size_t n, const char *games) {
    size_t m = n;
    if (m > 4 && po_ieq(b + m - 4, 4, ".exe")) m -= 4;
    for (int i = 0; PO_CLIENTS[i]; i++) if (po_ieq(b, m, PO_CLIENTS[i])) return 1;
    if (games) {
        const char *g = games;
        while (*g) {
            const char *e = g; while (*e && *e != ':') e++;
            size_t gn = (size_t) (e - g), i = 0;
            if (gn > 0 && gn == n) { for (; i < n; i++) if (po_lc(b[i]) != po_lc(g[i])) break; if (i == n) return 1; }
            g = *e ? e + 1 : e;
        }
    }
    return 0;
}

/*
 * THIS PROCESS IS THE GAME CLIENT: its image (the first argument that is not Wine's loader, as its last path part) is one of the
 * client's exe names, and its comm is either still Wine's loader's ("wine...", before Wine renames the process) or the start of that
 * same image name (the kernel keeps 15 characters). 1 = the client; 0 = anything else, unsure included.
 */
static inline int po_client_check(const char *cmd, size_t n, const char *comm, size_t cn, const char *games) {
    while (cn > 0 && (comm[cn - 1] == '\n' || comm[cn - 1] == 0)) cn--;
    const char *img = 0; size_t imgn = 0;
    size_t i = 0;
    while (i < n && !img) {
        size_t e = i; while (e < n && cmd[e]) e++;
        size_t bn; const char *b = po_base(cmd + i, e - i, &bn);
        if (bn > 0 && !po_is_loader(b, bn)) { img = b; imgn = bn; }
        i = e + 1;
    }
    if (!img || !po_is_client_name(img, imgn, games)) return 0;
    if (cn == 0 || po_istarts(comm, cn, "wine")) return 1;
    if (cn > imgn) return 0;
    for (size_t k = 0; k < cn; k++) if (po_lc(comm[k]) != po_lc(img[k])) return 0;
    return 1;
}

#define PO_G_SLICES 4                           /* sample slices per chunk, at 1/8, 3/8, 5/8 and 7/8 of it */
#define PO_G_SLICE_PAGES 2                      /* pages per slice */
#define PO_G_MIN_SAMPLE 4                       /* fewer sample pages sent out: no verdict, the chunk is never paged */
#define PO_G_MIN_CHUNK ((uintptr_t) 256 << 10)  /* a chunk piece under 256 KiB is not probed */
#define PO_G_PROBE_MAX 512                      /* chunks probed per pass (the table) */
#define PO_G_PROBE_REQ 128                      /* chunks probed per request */
#define PO_G_SCAN_REQ 4096                      /* chunks looked at per request */
#define PO_G_REQ_MAX ((uint64_t) 32 << 20)      /* a commit request: at most 32 MiB of resident pages */
#define PO_G_PASS_MAX ((uint64_t) 256 << 20)    /* a pass: at most 256 MiB, its samples included */
#define PO_G_DONE_MAX 320
#define PO_G_HOT_MAX 512
#define PO_G_HOT_FOR 4                          /* passes a chunk in use is left alone */

/* the game's answers beyond the shared ones */
#define PO_NOT_CLIENT 6     /* a game request in a process that is not the game client: nothing done */
#define PO_NO_PASS 7        /* a probe / commit with no pass started: nothing done */

typedef struct { uintptr_t addr, end; uint8_t mask, used; } po_gprobe;  /* a probed chunk: which sample pages went out */
typedef struct { uintptr_t addr, end; uint32_t pages, left; } po_gdone; /* a chunk a commit paged out: pages moved, pages left */
typedef struct { uintptr_t addr; uint32_t until; } po_ghot;             /* a chunk in use: left alone until pass [until] */

typedef struct {
    po_state *st;               /* the page size, the probe of MADV_PAGEOUT, the maps buffers (shared with the helper mode) */
    uint32_t pass;              /* passes started */
    int inpass;                 /* a pass is open (its probe started) */
    uintptr_t gcursor;          /* where the next pass's probe starts (0 = the start) */
    uintptr_t pstart, pcur;     /* this pass's probe: where it started, where it goes on */
    int leg;                    /* 0 = [pstart, end), 1 = [0, pstart) */
    int pdone;                  /* the probe walked everything (or the table / the pass is full) */
    uint64_t pass_spent;        /* bytes of this pass: samples sent out and resident pages a commit paged */
    uint64_t pass_paged_kb;
    int nprobe, ndone, nhot;
    po_gprobe probe[PO_G_PROBE_MAX];
    po_gdone done[PO_G_DONE_MAX];
    po_ghot hot[PO_G_HOT_MAX];
} po_gstate;

typedef struct {
    int rc;
    char op;                    /* 'P' a new pass's probe, 'p' its probe goes on, 'c' a commit, 'k' a check only */
    uint32_t probed;            /* the pass's table size */
    uint64_t sampled_kb;        /* sample pages sent out by this request */
    uint32_t cold;              /* chunks paged out by this request */
    uint32_t inuse;             /* chunks found in use (samples back) or skipped as in use by this request */
    uint64_t resident_kb;       /* resident pages of the chunks this request paged */
    uint64_t paged_kb;          /* pages that left memory (this request, samples included) */
    uint64_t back_kb;           /* pages of the last pass's committed chunks found back in memory ('P' / 'k') */
    uint64_t pass_kb;           /* KiB this pass has sent out so far */
    int more;                   /* 1 = ask again (the probe goes on / the commit has more cold chunks) */
    uint64_t ms;
} po_gresult;

static inline void po_g_init(po_gstate *g, po_state *st) {
    g->st = st; g->pass = 0; g->inpass = 0; g->gcursor = 0; g->pstart = 0; g->pcur = 0; g->leg = 0; g->pdone = 0;
    g->pass_spent = 0; g->pass_paged_kb = 0; g->nprobe = 0; g->ndone = 0; g->nhot = 0;
}

/* "DSFPO2 <seq> <op> <cap KiB>" -> [seq], [op] (P p c k), [cap] bytes (a commit's: 1 MiB .. [PO_G_REQ_MAX]); 0 = not a game request */
static inline int po_parse_greq(const char *s, size_t n, uint64_t *seq, char *op, uint64_t *cap) {
    if (!po_starts(s, n, "DSFPO2 ")) return 0;
    size_t i = 7; uint64_t a = 0, b = 0; int any = 0;
    while (i < n && s[i] >= '0' && s[i] <= '9') { a = a * 10 + (uint64_t) (s[i] - '0'); i++; any = 1; if (a > 1000000000000ull) return 0; }
    if (!any || i + 2 >= n || s[i] != ' ') return 0;
    char o = s[i + 1];
    if (o != 'P' && o != 'p' && o != 'c' && o != 'k') return 0;
    if (s[i + 2] != ' ') return 0;
    i += 3; any = 0;
    while (i < n && s[i] >= '0' && s[i] <= '9') { b = b * 10 + (uint64_t) (s[i] - '0'); i++; any = 1; if (b > 100000000ull) return 0; }
    if (!any) return 0;
    if (i < n && s[i] != '\n' && s[i] != ' ' && s[i] != 0) return 0;
    uint64_t bytes = b * 1024u;
    if (bytes < PO_CHUNK) bytes = PO_CHUNK;
    if (bytes > PO_G_REQ_MAX) bytes = PO_G_REQ_MAX;
    *seq = a; *op = o; *cap = bytes;
    return 1;
}

static inline int po_g_is_hot(const po_gstate *g, uintptr_t a) {
    for (int i = 0; i < g->nhot; i++) if (g->hot[i].addr == a) return 1;
    return 0;
}
static inline void po_g_mark_hot(po_gstate *g, uintptr_t a) {
    if (po_g_is_hot(g, a) || g->nhot >= PO_G_HOT_MAX) return;
    g->hot[g->nhot].addr = a; g->hot[g->nhot].until = g->pass + PO_G_HOT_FOR; g->nhot++;
}

/* the chunks around the request's own stack pointer (64 KiB below to 256 KiB above it): never probed or paged */
static inline int po_g_near_sp(const po_gstate *g, uintptr_t c, uintptr_t next) {
    uintptr_t sp = g->st->sp;
    return sp && c < sp + ((uintptr_t) 256 << 10) && next > sp - ((uintptr_t) 64 << 10);
}

/* slice [k] of the chunk piece [c, next): its first page and its length (page multiples; 0 = none) */
static inline uintptr_t po_g_slice(const po_gstate *g, uintptr_t c, uintptr_t next, int k, size_t *len) {
    size_t page = g->st->page;
    uintptr_t span = next - c;
    uintptr_t at = c + (span * (uintptr_t) (2 * k + 1) / (2 * PO_G_SLICES));
    at &= ~((uintptr_t) page - 1);
    if (at < c) at = c;
    size_t l = (size_t) PO_G_SLICE_PAGES * page;
    if (at + l > next) l = (next > at) ? ((size_t) (next - at) / page) * page : 0;
    *len = l;
    return at;
}

/* the resident pages of the sample slices as a bit mask (bit k * PO_G_SLICE_PAGES + j); -1 = a slice is not mapped any more */
static inline int po_g_sample_bits(po_gstate *g, uintptr_t c, uintptr_t next) {
    int bits = 0;
    for (int k = 0; k < PO_G_SLICES; k++) {
        size_t len; uintptr_t at = po_g_slice(g, c, next, k, &len);
        if (len == 0) continue;
        if (po_mincore(at, len, g->st->vec) != 0) return -1;
        size_t pages = len / g->st->page;
        for (size_t j = 0; j < pages && j < PO_G_SLICE_PAGES; j++) if (g->st->vec[j] & 1) bits |= 1 << (k * PO_G_SLICE_PAGES + (int) j);
    }
    return bits;
}

static inline int po_popcount8(int v) { int n = 0; for (int i = 0; i < 8; i++) n += (v >> i) & 1; return n; }

/* the chunks the last pass paged out: how much came back; a chunk half back or more is in use (left alone for a while) */
static inline void po_g_age(po_gstate *g, po_gresult *res) {
    for (int i = 0; i < g->ndone; i++) {
        long r = po_resident(g->st, g->done[i].addr, (size_t) (g->done[i].end - g->done[i].addr));
        if (r < 0) continue;
        uint32_t back = (uint32_t) r > g->done[i].left ? (uint32_t) r - g->done[i].left : 0;
        if (back > g->done[i].pages) back = g->done[i].pages;
        res->back_kb += (uint64_t) back * g->st->page / 1024u;
        if (back * 2u >= g->done[i].pages) po_g_mark_hot(g, g->done[i].addr);
    }
    g->ndone = 0;
    int k = 0;
    for (int i = 0; i < g->nhot; i++) if (g->hot[i].until > g->pass) g->hot[k++] = g->hot[i];
    g->nhot = k;
}

/* one chunk piece probed: its resident sample pages sent out; into the table when enough went out. 1 = probed */
static inline int po_g_probe_chunk(po_gstate *g, uintptr_t c, uintptr_t next, po_gresult *res) {
    if (g->nprobe >= PO_G_PROBE_MAX) return 0;
    long r = po_resident(g->st, c, (size_t) (next - c));
    long chunk_pages = (long) ((next - c) / g->st->page);
    if (r < 0 || r * 4 < chunk_pages || r < PO_MIN_PAGES) return 0;    /* under a quarter resident: not worth it */
    int before = po_g_sample_bits(g, c, next);
    if (before < 0 || po_popcount8(before) < PO_G_MIN_SAMPLE) return 0;
    for (int k = 0; k < PO_G_SLICES; k++) {
        size_t len; uintptr_t at = po_g_slice(g, c, next, k, &len);
        if (len > 0) po_madvise(at, len, MADV_PAGEOUT);
    }
    int after = po_g_sample_bits(g, c, next);
    if (after < 0) return 0;
    int out = before & ~after;
    int nout = po_popcount8(out);
    uint64_t kb = (uint64_t) nout * g->st->page / 1024u;
    res->sampled_kb += kb; res->paged_kb += kb;
    g->pass_spent += (uint64_t) nout * g->st->page; g->pass_paged_kb += kb;
    if (nout < PO_G_MIN_SAMPLE) return 1;                              /* the samples did not leave: no verdict */
    po_gprobe *p = &g->probe[g->nprobe++];
    p->addr = c; p->end = next; p->mask = (uint8_t) out; p->used = 0;
    return 1;
}

/* the table in address order (a probe's second leg comes before its first): insertion sort, nearly sorted already */
static inline void po_g_sort(po_gstate *g) {
    for (int i = 1; i < g->nprobe; i++) {
        po_gprobe t = g->probe[i]; int j = i - 1;
        while (j >= 0 && g->probe[j].addr > t.addr) { g->probe[j + 1] = g->probe[j]; j--; }
        g->probe[j + 1] = t;
    }
}
static inline po_gprobe *po_g_find(po_gstate *g, uintptr_t c, uintptr_t next) {
    int lo = 0, hi = g->nprobe - 1;
    while (lo <= hi) {
        int mid = (lo + hi) / 2;
        if (g->probe[mid].addr == c) return g->probe[mid].end == next ? &g->probe[mid] : 0;
        if (g->probe[mid].addr < c) lo = mid + 1; else hi = mid - 1;
    }
    return 0;
}

/* walk /proc/self/maps' game-client regions ([po_region_ok] PO_CLIENT, read again by every request: a chunk is only ever touched
 * while it is still such a region), calling [each] on every chunk piece; it returns 1 to stop the walk. 1 = stopped */
typedef int (*po_g_each_fn)(po_gstate *g, uintptr_t c, uintptr_t next, void *arg);
static inline int po_g_walk(po_gstate *g, po_g_each_fn each, void *arg, po_gresult *res) {
    po_state *st = g->st;
    long fd = po_open("/proc/self/maps", PO_O_RDONLY, 0);
    if (fd < 0) { res->rc = PO_NO_MAPS; return 1; }
    size_t ln = 0; int stop = 0;
    while (!stop) {
        long got = po_read(fd, st->buf, sizeof st->buf);
        if (got <= 0) break;
        for (long i = 0; i < got && !stop; i++) {
            char ch = st->buf[i];
            if (ch != 10) { if (ln < sizeof st->line) st->line[ln++] = ch; continue; }   /* 10 = '\n' */
            po_map m;
            if (po_parse_maps_line(st->line, ln, &m) && po_region_ok(&m, PO_CLIENT)) {
                uintptr_t c = m.start;
                while (c < m.end && !stop) {
                    uintptr_t next = (c & ~(PO_CHUNK - 1)) + PO_CHUNK;
                    if (next > m.end || next <= c) next = m.end;
                    stop = each(g, c, next, arg);
                    c = next;
                }
            }
            ln = 0;
        }
    }
    po_close(fd);
    return stop;
}

typedef struct { po_gresult *res; uint32_t probed, scanned; uintptr_t lo, hi; } po_g_probe_arg;
static inline int po_g_probe_each(po_gstate *g, uintptr_t c, uintptr_t next, void *argp) {
    po_g_probe_arg *a = (po_g_probe_arg *) argp;
    if (c < a->lo || (a->hi && c >= a->hi)) return 0;
    if (a->probed >= PO_G_PROBE_REQ || a->scanned >= PO_G_SCAN_REQ || g->nprobe >= PO_G_PROBE_MAX ||
        g->pass_spent + (uint64_t) PO_G_SLICES * PO_G_SLICE_PAGES * g->st->page > PO_G_PASS_MAX) { g->pcur = c; return 1; }
    a->scanned++;
    if (po_g_is_hot(g, c)) { a->res->inuse++; return 0; }
    if (next - c < PO_G_MIN_CHUNK || po_g_near_sp(g, c, next)) return 0;
    if (po_g_probe_chunk(g, c, next, a->res)) a->probed++;
    return 0;
}

/* the probe: from where it goes on, at most [PO_G_PROBE_REQ] chunks this request */
static inline void po_g_probe(po_gstate *g, po_gresult *res) {
    po_g_probe_arg a = { res, 0, 0, g->pcur, g->leg == 0 ? 0 : g->pstart };
    int stopped = po_g_walk(g, po_g_probe_each, &a, res);
    if (res->rc != PO_OK) return;
    if (!stopped) {                                            /* the end of this leg */
        if (g->leg == 0 && g->pstart > 0) { g->leg = 1; g->pcur = 0; }
        else { g->pdone = 1; g->gcursor = 0; }
    } else if (g->nprobe >= PO_G_PROBE_MAX || g->pass_spent + (uint64_t) PO_G_SLICES * PO_G_SLICE_PAGES * g->st->page > PO_G_PASS_MAX) {
        g->pdone = 1; g->gcursor = g->pcur;                    /* the next pass probes on from here */
    }
    po_g_sort(g);
    res->more = !g->pdone;
}

typedef struct { po_gresult *res; uint64_t budget, pass_left; } po_g_commit_arg;
static inline int po_g_commit_each(po_gstate *g, uintptr_t c, uintptr_t next, void *argp) {
    po_g_commit_arg *a = (po_g_commit_arg *) argp;
    po_gprobe *p = po_g_find(g, c, next);
    if (!p || p->used) return 0;
    if (a->budget == 0) { a->res->more = a->pass_left > 0; return 1; }   /* a probed chunk is left: ask again */
    p->used = 1;
    if (po_g_is_hot(g, c) || po_g_near_sp(g, c, next)) return 0;
    int now = po_g_sample_bits(g, c, next);
    if (now < 0) return 0;                                     /* not mapped as it was: left alone */
    if (now & p->mask) { po_g_mark_hot(g, c); a->res->inuse++; return 0; }   /* touched since the probe: in use */
    long r = po_resident(g->st, c, (size_t) (next - c));
    if (r < PO_MIN_PAGES) return 0;
    uint64_t spent = (uint64_t) r * g->st->page;
    /* the caps are hard: a chunk that does not fit the pass ends it; one that does not fit this request waits for the next */
    if (spent > a->pass_left) { p->used = 0; a->res->more = 0; a->pass_left = 0; return 1; }
    if (spent > a->budget) { p->used = 0; a->res->more = 1; return 1; }
    long ad = po_madvise(c, (size_t) (next - c), MADV_PAGEOUT);
    if (ad != 0) return 0;                                     /* locked / huge / PFN: the kernel refused, nothing moved */
    long r2 = po_resident(g->st, c, (size_t) (next - c));
    long moved = (r2 >= 0 && r2 < r) ? r - r2 : 0;
    a->res->cold++;
    a->res->resident_kb += (uint64_t) r * g->st->page / 1024u;
    uint64_t kb = (uint64_t) moved * g->st->page / 1024u;
    a->res->paged_kb += kb; g->pass_paged_kb += kb;
    if (moved > 0 && g->ndone < PO_G_DONE_MAX) {
        po_gdone *d = &g->done[g->ndone++];
        d->addr = c; d->end = next; d->pages = (uint32_t) moved; d->left = (uint32_t) (r - moved);
    }
    g->pass_spent += spent;
    a->budget -= spent; a->pass_left -= spent;
    return 0;
}

/* the commit: the probed chunks whose samples stayed out (cold), at most [cap] of resident pages and what the pass has left */
static inline void po_g_commit(po_gstate *g, uint64_t cap, po_gresult *res) {
    uint64_t left = g->pass_spent >= PO_G_PASS_MAX ? 0 : PO_G_PASS_MAX - g->pass_spent;
    po_g_commit_arg a = { res, cap < left ? cap : left, left };
    if (a.budget == 0) { res->more = 0; return; }
    po_g_walk(g, po_g_commit_each, &a, res);
    if (a.pass_left == 0) res->more = 0;
}

/*
 * ONE GAME REQUEST ([op]; [cap] a commit's bytes, at most [PO_G_REQ_MAX]): 'P' starts a pass (the last one's paged chunks checked
 * for what came back, the table emptied, the probe begun), 'p' probes on, 'c' pages out cold chunks, 'k' only checks what came back.
 * Only system calls: safe inside a signal handler; not reentrant (the hook's busy flag).
 */
static inline void po_g_run(po_gstate *g, char op, uint64_t cap, po_gresult *res) {
    uint64_t t0 = po_ms();
    res->rc = PO_OK; res->op = op; res->probed = 0; res->sampled_kb = 0; res->cold = 0; res->inuse = 0; res->resident_kb = 0;
    res->paged_kb = 0; res->back_kb = 0; res->pass_kb = 0; res->more = 0; res->ms = 0;
    if (cap > PO_G_REQ_MAX) cap = PO_G_REQ_MAX;
    g->st->sp = (uintptr_t) __builtin_frame_address(0);
    int sup = po_probe(g->st);
    if (sup < 0) { res->rc = PO_UNSUPPORTED; return; }
    if (op == 'k') {
        po_g_age(g, res);
    } else if (op == 'P') {
        po_g_age(g, res);
        g->pass++; g->inpass = 1; g->nprobe = 0; g->pass_spent = 0; g->pass_paged_kb = 0;
        g->pstart = g->gcursor; g->pcur = g->gcursor; g->leg = 0; g->pdone = 0;
        po_g_probe(g, res);
    } else if (!g->inpass) {
        res->rc = PO_NO_PASS;
    } else if (op == 'p') {
        if (!g->pdone) po_g_probe(g, res);
    } else if (op == 'c') {
        po_g_commit(g, cap, res);
    }
    res->probed = (uint32_t) g->nprobe;
    res->pass_kb = g->pass_paged_kb;
    res->ms = po_ms() - t0;
}

/* "DSFPO2 seq=5 rc=0 op=c probed=412 sampled_kb=.. cold=.. inuse=.. resident_kb=.. paged_kb=.. back_kb=.. pass_kb=.. more=1 ms=12\n"
 *  -> [out] (at least PO_LINE_MAX bytes); its length */
static inline size_t po_gresult_line(uint64_t seq, const po_gresult *r, char *out) {
    size_t n = 0;
#define PO_PUT(s) do { const char *_s = (s); while (*_s) out[n++] = *_s++; } while (0)
#define PO_NUM(v) do { n += po_utoa((uint64_t) (v), out + n); } while (0)
    PO_PUT("DSFPO2 seq="); PO_NUM(seq);
    PO_PUT(" rc="); PO_NUM(r->rc);
    PO_PUT(" op="); out[n++] = r->op ? r->op : '?';
    PO_PUT(" probed="); PO_NUM(r->probed);
    PO_PUT(" sampled_kb="); PO_NUM(r->sampled_kb);
    PO_PUT(" cold="); PO_NUM(r->cold);
    PO_PUT(" inuse="); PO_NUM(r->inuse);
    PO_PUT(" resident_kb="); PO_NUM(r->resident_kb);
    PO_PUT(" paged_kb="); PO_NUM(r->paged_kb);
    PO_PUT(" back_kb="); PO_NUM(r->back_kb);
    PO_PUT(" pass_kb="); PO_NUM(r->pass_kb);
    PO_PUT(" more="); PO_NUM(r->more);
    PO_PUT(" ms="); PO_NUM(r->ms);
    PO_PUT("\n");
#undef PO_PUT
#undef PO_NUM
    return n;
}

#endif
