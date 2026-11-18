/*
 * DS FOREVER'S NATIVE HEAP HANDED BACK (test build 1489, the user's pick 2026-11-18, #56): at each memory relief (the game's start, its
 * world entry, every loading screen, the art's let-go: MemWatch.relieve) the allocator returns the pages it holds free to Android.
 * The 1.0.33 / 1.0.34 reports: native 147 MB resident with 83 MB in use (the heap grown to 263 MB, p90 368). Bionic's mallopt:
 * M_PURGE_ALL (Android 14+: also the secondary cache), else M_PURGE (Android 9+); 1 = done, 0 = not supported here.
 */
#include <jni.h>
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
