/*
 * libdsf-pageout.so: THE WINE HELPERS' IDLE MEMORY INTO ZRAM (the user's pick, 2026-11-23: "Only Wine's idle background processes
 * next to WoW (not WoW itself), only when memory runs low; measured in reports"), AND THE GAME CLIENT'S COLD MEMORY (the user's
 * later pick, the same day: "Yes, carefully": "Only idle (cold) memory, only at character select before Enter World and when memory
 * is critically low in the world; capped; same setting"). DS Forever's own code (dsf_pageout.h says from which documents). Preloaded (LD_PRELOAD, BionicProgramLauncherComponent, only with Settings > Performance > Memory > Compress
 * idle memory on) into the container's Wine processes, which are native arm64 programs under DS Forever's own uid (arm64ec Proton:
 * no emulator between them and the kernel).
 *
 * At load: nothing unless DSF_PAGEOUT_DIR is set (the launcher sets it), and then only a SIGURG handler, and only when SIGURG is
 * still at its default (ignore): Wine, wineserver and our other preloads leave SIGURG alone (Wine's own handlers are SIGINT, SIGFPE,
 * SIGABRT, SIGQUIT, SIGUSR1/2, SIGTRAP, SIGSEGV, SIGILL, SIGBUS), and a SIGURG a process has no handler for is ignored by the
 * kernel, so a stray one can never stop or kill anything.
 *
 * On a request (DS Forever's PageOut: sigqueue(SIGURG) carrying [PO_MAGIC], after writing <dir>/req "DSFPO1 <seq> <cap KiB>"):
 * the process checks what it is (dsf_pageout.h po_image_check: a known Wine helper, nothing naming the game; this helper request is
 * never sent to the game and the game refuses it), pages out at most 32 MiB of its own idle private anonymous memory (po_run) and
 * writes one line to <dir>/res-<pid>.
 * THE GAME CLIENT (Wow-arm64.exe, WowB-ARM64.exe, ...: dsf_pageout.h po_client_check), only with DSF_PAGEOUT_GAME_MODE=1 from the
 * launcher: the handler is installed in it too, and serves only DS Forever's GAME request ([PO_MAGIC_GAME], <dir>/greq "DSFPO2 <seq>
 * <op> <cap KiB>", answered in <dir>/gres-<pid>): the cold-only two-step pass of dsf_pageout.h po_g_run, at most 32 MiB a request and
 * 256 MiB a pass. A helper request is refused there as before (rc=2), and a game request anywhere else (rc=6). Every other process
 * that names the game (Battle.net, its Agent, our sign-in bridge, explorer.exe / start.exe starting the game) still gets nothing.
 * IN THE GAME'S PROCESS THIS CODE ONLY reads /proc/self/maps, /proc/self/cmdline and /proc/self/comm, asks mincore(2) and calls
 * madvise(MADV_PAGEOUT) on its own private anonymous memory: it never reads or writes the game's memory contents and never hooks,
 * patches or calls a game function (no symbol of the game is looked up; the library's only entry points are its constructor and
 * this signal handler).
 * Inside the handler: raw system calls only (no libc, no allocation, no lock), static buffers behind a busy flag, errno untouched,
 * Wine's asynchronous signals held until it returns. Built without the stack protector (Android.mk): its canary is read through
 * the thread pointer, and the handler runs on whatever Wine thread the signal lands on.
 */
#define _GNU_SOURCE
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "dsf_pageout.h"

#define PO_MAGIC 0x44534650     /* "DSFP": only DS Forever's own request is served */
#define PO_MAGIC_GAME 0x44534647 /* "DSFG": DS Forever's game request (the game client's cold-only pass) */

static char g_dir[200];
static size_t g_dirlen;
static char g_games[160];
static po_state g_st;
static int g_busy;
static uint64_t g_last_seq = (uint64_t) -1;
static char g_cmd[1024];
static char g_comm[32];
static char g_path[256];
static char g_tmp[256];
static char g_req[64];
static char g_out[PO_LINE_MAX];
static int g_game_mode;                /* DSF_PAGEOUT_GAME_MODE=1: the game client may be served (cold-only) */
static po_gstate g_gst;
static uint64_t g_last_gseq = (uint64_t) -1;

/* [g_dir]/[name][pid] into [out] (terminated); 0 = too long */
static size_t po_path(char *out, size_t cap, const char *name, long pid) {
    size_t n = 0, k = po_strlen(name);
    if (g_dirlen + 1 + k + 24 >= cap) return 0;
    for (size_t i = 0; i < g_dirlen; i++) out[n++] = g_dir[i];
    out[n++] = '/';
    for (size_t i = 0; i < k; i++) out[n++] = name[i];
    if (pid >= 0) n += po_utoa((uint64_t) pid, out + n);
    out[n] = 0;
    return n;
}

/* the whole of a small file into [buf] (terminated); its length, -1 = unreadable */
static long po_slurp(const char *path, char *buf, size_t cap) {
    long fd = po_open(path, PO_O_RDONLY, 0);
    if (fd < 0) return -1;
    size_t n = 0;
    while (n + 1 < cap) {
        long r = po_read(fd, buf + n, cap - 1 - n);
        if (r <= 0) break;
        n += (size_t) r;
    }
    po_close(fd);
    buf[n] = 0;
    return (long) n;
}

/* [g_out]'s [n] bytes as <dir>/<name><pid>, whole (temp + rename) */
static void po_put(const char *tmpname, const char *name, size_t n) {
    long pid = po_getpid();
    if (!po_path(g_tmp, sizeof g_tmp, tmpname, pid) || !po_path(g_path, sizeof g_path, name, pid)) return;
    long fd = po_open(g_tmp, PO_O_WRONLY | PO_O_CREAT | PO_O_TRUNC, 0600);
    if (fd < 0) return;
    long w = po_write(fd, g_out, n);
    po_close(fd);
    if (w == (long) n) po_rename(g_tmp, g_path);      /* whole lines only: the app never reads half of one */
}

static void po_answer(uint64_t seq, const po_result *res) {
    po_put("res.tmp-", "res-", po_result_line(seq, res, g_out));
}

static void po_serve(void) {
    if (!po_path(g_path, sizeof g_path, "req", -1)) return;
    long n = po_slurp(g_path, g_req, sizeof g_req);
    uint64_t seq, cap;
    if (n <= 0 || !po_parse_request(g_req, (size_t) n, &seq, &cap)) return;
    if (seq == g_last_seq) return;                     /* one answer per request */
    g_last_seq = seq;
    po_result res;
    res.rc = PO_OK; res.regions = 0; res.eligible_kb = 0; res.resident_kb = 0; res.paged_kb = 0; res.back_kb = 0;
    res.hot_skipped = 0; res.ms = 0;
    long cn = po_slurp("/proc/self/cmdline", g_cmd, sizeof g_cmd);
    long mn = po_slurp("/proc/self/comm", g_comm, sizeof g_comm);
    int who = cn <= 0 ? PO_NOT_HELPER : po_image_check(g_cmd, (size_t) cn, g_comm, mn > 0 ? (size_t) mn : 0, g_games);
    if (who != PO_OK) res.rc = who;
    else po_run(&g_st, PO_HELPER, cap, &res);
    po_answer(seq, &res);
}

/* the game client's request: only with the game mode on and only in the game client itself (checked again at every request) */
static void po_gserve(void) {
    if (!po_path(g_path, sizeof g_path, "greq", -1)) return;
    long n = po_slurp(g_path, g_req, sizeof g_req);
    uint64_t seq, cap; char op;
    if (n <= 0 || !po_parse_greq(g_req, (size_t) n, &seq, &op, &cap)) return;
    if (seq == g_last_gseq) return;                    /* one answer per request */
    g_last_gseq = seq;
    po_gresult res;
    res.rc = PO_OK; res.op = op; res.probed = 0; res.sampled_kb = 0; res.cold = 0; res.inuse = 0; res.resident_kb = 0;
    res.paged_kb = 0; res.back_kb = 0; res.pass_kb = 0; res.more = 0; res.ms = 0;
    long cn = po_slurp("/proc/self/cmdline", g_cmd, sizeof g_cmd);
    long mn = po_slurp("/proc/self/comm", g_comm, sizeof g_comm);
    int client = g_game_mode && cn > 0 && po_client_check(g_cmd, (size_t) cn, g_comm, mn > 0 ? (size_t) mn : 0, g_games);
    if (!client) res.rc = PO_NOT_CLIENT;
    else po_g_run(&g_gst, op, cap, &res);
    po_put("gres.tmp-", "gres-", po_gresult_line(seq, &res, g_out));
}

static void po_on_urg(int sig, siginfo_t *si, void *uc) {
    (void) sig; (void) uc;
    /* DS Forever's own request only (sigqueue with our value): never a socket's urgent-data SIGURG, never anyone else's kill */
    if (!si || si->si_code != SI_QUEUE) return;
    int v = si->si_value.sival_int;
    if (v != PO_MAGIC && v != PO_MAGIC_GAME) return;
    if (__atomic_exchange_n(&g_busy, 1, __ATOMIC_ACQUIRE)) return;
    if (v == PO_MAGIC) po_serve(); else po_gserve();
    __atomic_store_n(&g_busy, 0, __ATOMIC_RELEASE);
}

__attribute__((constructor)) static void po_init(void) {
    const char *dir = getenv("DSF_PAGEOUT_DIR");
    if (!dir || dir[0] != '/') return;
    size_t n = strlen(dir);
    if (n >= sizeof g_dir) return;
    memcpy(g_dir, dir, n + 1); g_dirlen = n;
    const char *games = getenv("DSF_PAGEOUT_GAME");
    if (games && strlen(games) < sizeof g_games) memcpy(g_games, games, strlen(games) + 1);
    const char *gm = getenv("DSF_PAGEOUT_GAME_MODE");
    g_game_mode = gm && gm[0] == '1' && gm[1] == 0;
    po_state_init(&g_st, (size_t) sysconf(_SC_PAGESIZE));
    po_g_init(&g_gst, &g_st);
    /* a process that names the game (Wine starts it with its exe among the arguments): nothing is installed at all, the library stays
     * inert - except in the game client itself with the game mode on (its cold-only pass, po_gserve) */
    long cn = po_slurp("/proc/self/cmdline", g_cmd, sizeof g_cmd);
    long mn = po_slurp("/proc/self/comm", g_comm, sizeof g_comm);
    if (cn > 0 && po_image_check(g_cmd, (size_t) cn, g_comm, mn > 0 ? (size_t) mn : 0, g_games) == PO_GAME &&
        !(g_game_mode && po_client_check(g_cmd, (size_t) cn, g_comm, mn > 0 ? (size_t) mn : 0, g_games))) return;
    struct sigaction old;
    memset(&old, 0, sizeof old);
    if (sigaction(SIGURG, NULL, &old) != 0) return;
    if (old.sa_handler != SIG_DFL || (old.sa_flags & SA_SIGINFO)) return;   /* someone else's: leave it */
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = po_on_urg;
    sa.sa_flags = SA_SIGINFO | SA_RESTART | SA_ONSTACK;
    /* while a request runs, Wine's own asynchronous signals wait (the set Wine itself blocks around every wineserver call:
     * SIGALRM, SIGIO, SIGINT, SIGHUP, SIGUSR1, SIGUSR2, SIGCHLD), so Wine's thread suspend (SIGUSR1) never lands inside the
     * handler and saves its registers as the thread's context; the synchronous ones (SIGSEGV, SIGBUS, ...) stay open */
    sigemptyset(&sa.sa_mask);
    sigaddset(&sa.sa_mask, SIGALRM); sigaddset(&sa.sa_mask, SIGIO); sigaddset(&sa.sa_mask, SIGINT); sigaddset(&sa.sa_mask, SIGHUP);
    sigaddset(&sa.sa_mask, SIGUSR1); sigaddset(&sa.sa_mask, SIGUSR2); sigaddset(&sa.sa_mask, SIGCHLD);
    sigaction(SIGURG, &sa, NULL);
}
