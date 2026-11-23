/*
 * DS FOREVER'S IDLE MEMORY INTO THE DEVICE'S OWN ZRAM (the user's pick, 2026-11-23: "Wine helpers" + "Our own app", never the game).
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
#define PO_LINE_MAX 320                         /* a result line's room */

/* the request's answers (the result line's rc=) */
#define PO_OK 0
#define PO_NOT_HELPER 1     /* this process is not a known Wine helper: nothing done */
#define PO_GAME 2           /* this process names the game: nothing done, ever */
#define PO_UNSUPPORTED 3    /* MADV_PAGEOUT is not in this kernel (EINVAL): nothing done, ever again */
#define PO_BAD_REQUEST 4
#define PO_NO_MAPS 5

/* the region rules: a Wine helper's heaps, or DS Forever's own native allocator only */
#define PO_HELPER 1
#define PO_SELF 2

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
static inline long po_madvise(uintptr_t a, size_t n, int advice) { return po_sc(SYS_madvise, (long) a, (long) n, advice, 0, 0, 0); }
static inline long po_mincore(uintptr_t a, size_t n, unsigned char *vec) { return po_sc(SYS_mincore, (long) a, (long) n, (long) vec, 0, 0, 0); }
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
    if (mode == PO_SELF) return alloc;
    if (mode == PO_HELPER) return n == 0 || po_ieq(s, n, "[heap]") || alloc;
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

#endif
