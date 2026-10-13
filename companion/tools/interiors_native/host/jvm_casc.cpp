// HOST-ONLY CASC binding for the JVM end-to-end test (jvm_test/): the local PC install read through CascLib, standing in
// for the app's CascNative. Never built for Android. Local storage only: no online CDN route.
#include <jni.h>

#include "CascLib.h"

#include <string>
#include <vector>

extern "C" {

JNIEXPORT jlong JNICALL Java_com_jrees_wowcompanion_HostCasc_open(JNIEnv* env, jclass, jstring jpath) {
    const jchar* chars = env->GetStringChars(jpath, nullptr);
    std::wstring path(reinterpret_cast<const wchar_t*>(chars), env->GetStringLength(jpath));
    env->ReleaseStringChars(jpath, chars);
    // the launch client (wowf), else the beta (wow_classic_beta): whichever the folder's .build.info holds
    for (const wchar_t* code : {L"wowf", L"wow_classic_beta"}) {
        CASC_OPEN_STORAGE_ARGS args{};
        args.Size = sizeof(args);
        args.szLocalPath = path.c_str();
        args.szCodeName = code;
        args.dwLocaleMask = 2;
        HANDLE h = nullptr;
        if (CascOpenStorageEx(nullptr, &args, false, &h)) return reinterpret_cast<jlong>(h);
    }
    return 0;
}

JNIEXPORT jbyteArray JNICALL Java_com_jrees_wowcompanion_HostCasc_read(JNIEnv* env, jclass, jlong jh, jint fid) {
    HANDLE f = nullptr;
    if (!CascOpenFile(reinterpret_cast<HANDLE>(jh), CASC_FILE_DATA_ID(fid), 0, CASC_OPEN_BY_FILEID, &f)) return nullptr;
    ULONGLONG size = 0;
    jbyteArray out = nullptr;
    if (CascGetFileSize64(f, &size) && size < (1ull << 30)) {
        std::vector<unsigned char> buf(static_cast<size_t>(size));
        DWORD got = 0;
        // like the PC tools' casc.read_fileid: what CascLib returns (a DB2 with encrypted sections comes back short)
        if (CascReadFile(f, buf.data(), static_cast<DWORD>(size), &got)) {
            out = env->NewByteArray(static_cast<jsize>(got));
            if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(got), reinterpret_cast<const jbyte*>(buf.data()));
        }
    }
    CascCloseFile(f);
    return out;
}

JNIEXPORT void JNICALL Java_com_jrees_wowcompanion_HostCasc_close(JNIEnv*, jclass, jlong jh) {
    if (jh) CascCloseStorage(reinterpret_cast<HANDLE>(jh));
}

// the same entry point tools/harvest/casc_jni.cpp gives the phone
JNIEXPORT jbyteArray JNICALL Java_com_jrees_wowcompanion_IndoorGeneratorCasc_contentKey(JNIEnv* env, jclass, jlong jh, jint fid) {
    HANDLE f = nullptr;
    if (!CascOpenFile(reinterpret_cast<HANDLE>(jh), CASC_FILE_DATA_ID(fid), 0, CASC_OPEN_BY_FILEID, &f)) return nullptr;
    unsigned char key[16];
    size_t need = 0;
    const bool ok = CascGetFileInfo(f, CascFileContentKey, key, sizeof(key), &need) && need <= sizeof(key);
    CascCloseFile(f);
    if (!ok) return nullptr;
    jbyteArray out = env->NewByteArray(16);
    if (out) env->SetByteArrayRegion(out, 0, 16, reinterpret_cast<const jbyte*>(key));
    return out;
}

}  // extern "C"
