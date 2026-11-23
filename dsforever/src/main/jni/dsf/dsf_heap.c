/*
 * DS FOREVER'S NATIVE HEAP HANDED BACK (test build 1489, the user's pick 2026-11-18, #56): at each memory relief (the game's start, its
 * world entry, every loading screen, the art's let-go: MemWatch.relieve) the allocator returns the pages it holds free to Android.
 * The 1.0.33 / 1.0.34 reports: native 147 MB resident with 83 MB in use (the heap grown to 263 MB, p90 368). Bionic's mallopt:
 * M_PURGE_ALL (Android 14+: also the secondary cache), else M_PURGE (Android 9+); 1 = done, 0 = not supported here.
 */
#include <jni.h>
#include <errno.h>
#include <malloc.h>

#ifndef M_PURGE
#define M_PURGE (-101)
#endif
#ifndef M_PURGE_ALL
#define M_PURGE_ALL (-104)
#endif

/* 2 = M_PURGE_ALL done, 1 = M_PURGE done, 0 = neither is supported */
JNIEXPORT jint JNICALL Java_com_jrees_wowcompanion_NativeHeap_purge(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    if (mallopt(M_PURGE_ALL, 0) == 1) return 2;
    if (mallopt(M_PURGE, 0) == 1) return 1;
    return 0;
}

/*
 * DS FOREVER'S OWN IDLE MEMORY INTO ZRAM (the user's pick, 2026-11-23: "DS Forever's own idle memory when memory runs low";
 * PageOut.kt, on MemWatch's thread, never the UI thread): at most [capKib] KiB of the resident pages of this process's native
 * allocator regions (dsf_pageout.h PO_SELF: [anon:scudo:...] / [anon:libc_malloc] only - never the Java heap, the JIT, stacks or a
 * driver's memory) paged out with MADV_PAGEOUT. Answer: {rc, regions, eligible KB, resident KB, paged KB, came-back KB, chunks
 * skipped as in use, ms}; null = no memory for the answer.
 */
#include <pthread.h>
#include <signal.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>
#include "dsf_pageout.h"

static po_state g_self;
static int g_self_init;
static pthread_mutex_t g_self_lock = PTHREAD_MUTEX_INITIALIZER;

JNIEXPORT jlongArray JNICALL Java_com_jrees_wowcompanion_NativeHeap_pageOutSelf(JNIEnv *env, jclass cls, jint capKib) {
    (void) cls;
    po_result r;
    pthread_mutex_lock(&g_self_lock);
    if (!g_self_init) { po_state_init(&g_self, (size_t) sysconf(_SC_PAGESIZE)); g_self_init = 1; }
    po_run(&g_self, PO_SELF, capKib > 0 ? (uint64_t) capKib * 1024u : 0, &r);
    pthread_mutex_unlock(&g_self_lock);
    jlong v[8] = { r.rc, r.regions, (jlong) r.eligible_kb, (jlong) r.resident_kb, (jlong) r.paged_kb, (jlong) r.back_kb,
                   r.hot_skipped, (jlong) r.ms };
    jlongArray out = (*env)->NewLongArray(env, 8);
    if (out) (*env)->SetLongArrayRegion(env, out, 0, 8, v);
    return out;
}

/*
 * THE WINE HELPERS' REQUEST (PageOut.kt): SIGURG with DS Forever's value to [pid] (a helper of our own uid that PageOut checked:
 * never the game). libdsf-pageout.so's handler serves only this value. 0 = sent, else -errno.
 */
JNIEXPORT jint JNICALL Java_com_jrees_wowcompanion_NativeHeap_pageOutSignal(JNIEnv *env, jclass cls, jint pid) {
    (void) env; (void) cls;
    if (pid <= 1 || pid == getpid()) return -22;
    union sigval v;
    v.sival_int = 0x44534650;      /* PO_MAGIC */
    return sigqueue((pid_t) pid, SIGURG, v) == 0 ? 0 : -errno;
}

/*
 * THE GAME CLIENT'S REQUEST (PageOut.kt GamePageOut, the user's pick 2026-11-23 "Yes, carefully"): SIGURG with DS Forever's GAME value
 * to the game client [pid] (our own uid, checked by name and by its handler), sent ONLY to its thread [tid] (one asleep and nearly
 * idle, so no busy game thread runs the request) with rt_tgsigqueueinfo, the call bionic's own pthread_sigqueue makes (in every app's
 * seccomp list since Android 10). Never to the process as a whole: the kernel would hand that one to the first thread not blocking
 * it, the game's main (render) thread first, which would then run the page-out between two frames. A thread that is gone answers
 * -ESRCH (GamePageOut picks another). libdsf-pageout.so's handler serves this value only in the game client, cold-only.
 * 0 = sent, else -errno (-EINVAL for no thread).
 */
JNIEXPORT jint JNICALL Java_com_jrees_wowcompanion_NativeHeap_pageOutSignalGame(JNIEnv *env, jclass cls, jint pid, jint tid) {
    (void) env; (void) cls;
    if (pid <= 1 || pid == getpid() || tid <= 0) return -22;
    siginfo_t si;
    memset(&si, 0, sizeof si);
    si.si_signo = SIGURG;
    si.si_code = SI_QUEUE;
    si.si_pid = getpid();
    si.si_uid = getuid();
    si.si_value.sival_int = 0x44534647;   /* PO_MAGIC_GAME */
    return syscall(__NR_rt_tgsigqueueinfo, (pid_t) pid, (pid_t) tid, SIGURG, &si) == 0 ? 0 : -errno;
}
