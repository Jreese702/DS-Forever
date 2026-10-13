// THE HARVEST'S FILE READER (Home, 2026-10-02): a thin JNI layer over CascLib (Ladislav Zezula, MIT licence) so the app can read the
// user's OWN game files (WoW Forever's local CASC storage) on the device. Read-only: CascLib only opens the storage's index and data
// files for reading. The user's picks: "CascLib (MIT), built for the phone"; no game art ships in the APK, it is built from these reads.
// Built by tools/harvest/build_native.sh into app/src/main/jniLibs/arm64-v8a/libcasc.so (not in git).
#include <jni.h>
#include "CascLib.h"
#include <vector>
#include <cstdio>
#include <android/log.h>

// DS Forever's download cache for CascLib's ALLOW_DOWNLOAD (tools/harvest/patch_casclib.py reads it in FetchCascFile)
char g_wcDownloadRoot[1024] = {0};
// the cache path of the last file CascLib needed and could not fetch itself (patch_casclib.py): the app downloads it and opens again
char g_wcNeed[1024] = {0};
static int g_openErr = 0;                // CascLib's error code of the last failed open (0 = the last open worked)
static thread_local int g_status = 0;
static thread_local int g_readErr = 0;
bool wc_reopen_cached(HANDLE source, HANDLE * target);

static jbyteArray read_file(JNIEnv *env, HANDLE fh) {
    for (int attempt = 0; attempt < 2; ++attempt) {
        ULONGLONG sz = 0;
        DWORD got = 0;
        bool sized = CascGetFileSize64(fh, &sz);
        int err = sized ? 0 : GetCascError();
        std::vector<unsigned char> buf;
        bool ok = false;
        if (sized && sz > 0 && sz <= 256ull * 1024 * 1024) {
            buf.resize((size_t)sz);
            SetCascError(ERROR_SUCCESS);
            ok = CascReadFile(fh, buf.data(), (DWORD)sz, &got);
            err = ok && got == sz ? 0 : GetCascError();
            if (err == 0 && got != sz) err = ERROR_HANDLE_EOF;
        } else if (sized) {
            err = ERROR_BAD_FORMAT;
        }
        if (ok && got == sz) {
            CascCloseFile(fh);
            g_status = 0;
            g_readErr = 0;
            jbyteArray out = env->NewByteArray((jsize)sz);
            if (out) env->SetByteArrayRegion(out, 0, (jsize)sz, (const jbyte *)buf.data());
            return out;
        }
        g_readErr = err;
        g_status = err == ERROR_FILE_ENCRYPTED ? 3 :
            (err == ERROR_FILE_NOT_FOUND || err == ERROR_HANDLE_EOF ||
             err == ERROR_FILE_INCOMPLETE || err == ERROR_FILE_OFFLINE ? 2 : 4);
        __android_log_print(5, "WCCASC", "read failed: error %d, size %llu, read %u, needs '%s'",
                            err, (unsigned long long)sz, got, g_wcNeed);
        bool recoverable = (!sized || sz <= 256ull * 1024 * 1024) &&
            (err == ERROR_FILE_NOT_FOUND || err == ERROR_HANDLE_EOF || err == ERROR_FILE_INCOMPLETE ||
             err == ERROR_FILE_OFFLINE || err == ERROR_FILE_CORRUPT || err == ERROR_BAD_FORMAT);
        HANDLE cached = nullptr;
        if (attempt == 0 && recoverable && wc_reopen_cached(fh, &cached)) {
            CascCloseFile(fh);
            fh = cached;
            __android_log_print(4, "WCCASC", "retrying damaged local entry from loose cache");
            continue;
        }
        CascCloseFile(fh);
        return nullptr;
    }
    return nullptr;
}

// every loading stage CascLib reports, to logcat (tag WCCASC): a device run then says how far the open got (h3, 2026-10-09)
static bool WINAPI wc_progress(void *, CASC_PROGRESS_MSG m, LPCSTR obj, DWORD cur, DWORD total) {
    static const char *names[] = {"loading file", "loading manifest", "downloading file", "loading index files", "downloading archive indexes"};
    if (m != CascProgressLoadingIndexes || cur == 0)
        __android_log_print(4, "WCCASC", "stage: %s %s %u/%u", (unsigned)m < 5 ? names[m] : "?", obj ? obj : "", (unsigned)cur, (unsigned)total);
    return false;
}   // of the last read: 0 ok, 1 unknown id, 2 indexed but not present, 3 encrypted, 4 other

extern "C" {

JNIEXPORT jlong JNICALL Java_com_jrees_wowcompanion_CascNative_open(JNIEnv *env, jclass, jstring jpath, jstring jproduct, jint jlocales) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    const char *product = env->GetStringUTFChars(jproduct, nullptr);
    CASC_OPEN_STORAGE_ARGS args = {};
    args.Size = sizeof(args);
    args.szLocalPath = path;
    args.szCodeName = product;
    // THE INSTALL'S OWN LOCALE (Map, 2026-10-09: the map art has its labels baked in; an all-locales open gave a Korean Stormwind)
    args.dwLocaleMask = jlocales != 0 ? (DWORD)jlocales : 0x2;
    // the build's internal tables (ENCODING / ROOT) the game client fetches from the CDN and does not keep: downloaded into the cache
    args.dwFlags = g_wcDownloadRoot[0] ? CASC_FEATURE_ALLOW_DOWNLOAD : 0;
    args.PfnProgressCallback = wc_progress;
    __android_log_print(4, "WCCASC", "open %s product %s locales 0x%x flags 0x%x cache '%s'", path, product, (unsigned)args.dwLocaleMask, (unsigned)args.dwFlags, g_wcDownloadRoot);
    HANDLE h = nullptr;
    bool ok = CascOpenStorageEx(nullptr, &args, false, &h);
    g_openErr = ok ? 0 : (int)GetCascError();
    __android_log_print(ok ? 4 : 5, "WCCASC", "open %s (error %d, needs '%s')", ok ? "OK" : "FAILED", g_openErr, g_wcNeed);
    env->ReleaseStringUTFChars(jpath, path);
    env->ReleaseStringUTFChars(jproduct, product);
    return ok ? (jlong)(intptr_t)h : 0;
}

JNIEXPORT jbyteArray JNICALL Java_com_jrees_wowcompanion_CascNative_read(JNIEnv *env, jclass, jlong jh, jint fid) {
    HANDLE h = (HANDLE)(intptr_t)jh;
    HANDLE fh = nullptr;
    if (!h || !CascOpenFile(h, CASC_FILE_DATA_ID((unsigned)fid), 0, CASC_OPEN_BY_FILEID, &fh)) {
        g_readErr = h ? GetCascError() : ERROR_INVALID_HANDLE;
        g_status = 1;
        return nullptr;
    }
    return read_file(env, fh);
}

// Only DB2 tables may contain deliberately unreadable sections. Db2 skips all
// TACT-keyed sections; leave their bytes zero while reading the remaining table.
JNIEXPORT jbyteArray JNICALL Java_com_jrees_wowcompanion_CascNative_readDb2(JNIEnv *env, jclass, jlong jh, jint fid) {
    HANDLE h = (HANDLE)(intptr_t)jh;
    HANDLE fh = nullptr;
    if (!h || !CascOpenFile(h, CASC_FILE_DATA_ID((unsigned)fid), 0,
                          CASC_OPEN_BY_FILEID | CASC_STRICT_DATA_CHECK | CASC_OVERCOME_ENCRYPTED, &fh)) {
        g_readErr = h ? GetCascError() : ERROR_INVALID_HANDLE;
        g_status = 1;
        return nullptr;
    }
    return read_file(env, fh);
}

// by path (WoW's root carries name hashes, so no listfile is needed); same status codes as read()
JNIEXPORT jbyteArray JNICALL Java_com_jrees_wowcompanion_CascNative_readName(JNIEnv *env, jclass, jlong jh, jstring jname) {
    HANDLE h = (HANDLE)(intptr_t)jh;
    HANDLE fh = nullptr;
    const char *name = env->GetStringUTFChars(jname, nullptr);
    bool opened = h && CascOpenFile(h, name, 0, CASC_OPEN_BY_NAME, &fh);
    env->ReleaseStringUTFChars(jname, name);
    if (!opened) {
        g_readErr = h ? GetCascError() : ERROR_INVALID_HANDLE;
        g_status = 1;
        return nullptr;
    }
    return read_file(env, fh);
}

JNIEXPORT jstring JNICALL Java_com_jrees_wowcompanion_CascNative_takeNeed(JNIEnv *env, jclass) {
    if (!g_wcNeed[0]) return nullptr;
    jstring s = env->NewStringUTF(g_wcNeed);
    g_wcNeed[0] = 0;
    return s;
}

JNIEXPORT jint JNICALL Java_com_jrees_wowcompanion_CascNative_lastOpenError(JNIEnv *, jclass) { return g_openErr; }

JNIEXPORT void JNICALL Java_com_jrees_wowcompanion_CascNative_setDownloadRoot(JNIEnv *env, jclass, jstring jdir) {
    const char *d = env->GetStringUTFChars(jdir, nullptr);
    snprintf(g_wcDownloadRoot, sizeof(g_wcDownloadRoot), "%s", d);
    env->ReleaseStringUTFChars(jdir, d);
}

JNIEXPORT jint JNICALL Java_com_jrees_wowcompanion_CascNative_lastStatus(JNIEnv *, jclass) { return g_status; }
JNIEXPORT jint JNICALL Java_com_jrees_wowcompanion_CascNative_lastReadError(JNIEnv *, jclass) { return g_readErr; }

JNIEXPORT jboolean JNICALL Java_com_jrees_wowcompanion_CascNative_addEncryptionKey(JNIEnv *env, jclass, jlong jh, jlong name, jbyteArray key) {
    if (!jh || !key || env->GetArrayLength(key) != 16) return JNI_FALSE;
    unsigned char bytes[16];
    env->GetByteArrayRegion(key, 0, 16, reinterpret_cast<jbyte *>(bytes));
    if (env->ExceptionCheck()) return JNI_FALSE;
    return CascAddEncryptionKey((HANDLE)(intptr_t)jh, (ULONGLONG)name, bytes) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_jrees_wowcompanion_CascNative_close(JNIEnv *, jclass, jlong jh) {
    HANDLE h = (HANDLE)(intptr_t)jh;
    if (h) CascCloseStorage(h);
}

}

// Track B (interiors): the CASC content key (CKey, 16 bytes) of a file, without reading its body. The interiors catalogue
// digests a building's inputs from these, so unchanged files keep their finished buildings across game updates. Bound to
// com.jrees.wowcompanion.IndoorGeneratorCasc (IndoorGeneratorNative.kt); null when the file is not in this build's index.
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_jrees_wowcompanion_IndoorGeneratorCasc_contentKey(JNIEnv *env, jclass, jlong jh, jint fid) {
    HANDLE fh = nullptr;
    if (!CascOpenFile((HANDLE)(intptr_t)jh, CASC_FILE_DATA_ID(fid), 0, CASC_OPEN_BY_FILEID, &fh)) return nullptr;
    unsigned char key[16];
    size_t need = 0;
    const bool ok = CascGetFileInfo(fh, CascFileContentKey, key, sizeof(key), &need) && need <= sizeof(key);
    CascCloseFile(fh);
    if (!ok) return nullptr;
    jbyteArray out = env->NewByteArray(16);
    if (out) env->SetByteArrayRegion(out, 0, 16, (const jbyte *)key);
    return out;
}
