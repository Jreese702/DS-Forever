"""Patches a COPY of CascLib's source for the phone (tools/harvest/build_native.sh runs it on the copy, never on the original):

1. FetchCascFile: a file CascLib has to DOWNLOAD (CASC_FEATURE_ALLOW_DOWNLOAD: the build's ENCODING / ROOT / DOWNLOAD tables the game
   client fetches from the CDN and does not keep locally - the cause of h1's "game files could not be opened", 2026-10-09) goes to the
   app's private cache (g_wcDownloadRoot, set over JNI), never into the game's own Data folder. Local files are read where they are.
2. FileStream: an open that asked for read-write and was refused falls back to read-only (Map's note: CascLib opens .build.info and the
   data spans without STREAM_FLAG_READ_ONLY; the harvest never writes to the game folder).

    python patch_casclib.py <copied CascLib src folder>
"""
import sys, os

src = sys.argv[1]


def patch(rel, pairs):
    p = os.path.join(src, rel)
    s = open(p, encoding='utf-8', errors='surrogateescape', newline='').read()
    crlf = '\r\n' in s
    s = s.replace('\r\n', '\n')
    for a, b in pairs:
        assert s.count(a) == 1, (rel, a[:60], s.count(a))
        s = s.replace(a, b)
    if crlf:
        s = s.replace('\n', '\r\n')
    open(p, 'w', encoding='utf-8', errors='surrogateescape', newline='').write(s)


patch('CascFiles.cpp', [
    ('''DWORD FetchCascFile(
    TCascStorage * hs,
    LPCTSTR szRootPath,''', '''extern char g_wcDownloadRoot[];      // DS Forever: the app's private download cache ("" = CascLib's own behaviour)
extern char g_wcNeed[];              // DS Forever: the cache path of the last file CascLib needed and could not download (the app fetches it)

DWORD FetchCascFile(
    TCascStorage * hs,
    LPCTSTR szRootPath,'''),
    ('''        // Sorry, the file was not found
        dwErrCode = ERROR_FILE_NOT_FOUND;''', '''        // DS Forever: the phone's own HTTPS fetches it (CascLib's sockets fail on Android, h2 2026-10-09); the app retries the open
        if(g_wcDownloadRoot[0] != 0)
            CascStrCopy(g_wcNeed, 1024, LocalPath);

        // Sorry, the file was not found
        dwErrCode = ERROR_FILE_NOT_FOUND;'''),
    ('''        // If this is not an online version, do nothing and return error
        if(!(hs->dwFeatures & CASC_FEATURE_ONLINE))
            return ERROR_FILE_NOT_FOUND;
''', '''        // If this is not an online version, do nothing and return error
        if(!(hs->dwFeatures & (CASC_FEATURE_ONLINE | CASC_FEATURE_ALLOW_DOWNLOAD)))
            return ERROR_FILE_NOT_FOUND;

        // DS Forever: a download goes to the app's private cache, never into the game's folder (an earlier one is reused)
        if(g_wcDownloadRoot[0] != 0)
        {
            LocalPath.Create(g_wcDownloadRoot, GetSubFolder(PathType), NULL);
            LocalPath.AppendEKey(pbEKey);
            LocalPath.AppendString(szExtension, false);
            if(FileAlreadyExists(LocalPath))
                return ERROR_SUCCESS;

            // Android owns HTTPS. CascLib's HttpDownloadFile can return success when
            // FileStream_Read fails, leaving no local file and no request to retry.
            // Keep the first missing file: optional manifests must not replace it.
            if(g_wcNeed[0] == 0)
                CascStrCopy(g_wcNeed, 1024, LocalPath);
            return ERROR_FILE_NOT_FOUND;
        }
'''),
])

patch('CascOpenFile.cpp', [
    ('''hf->bAllowDownloading  = (hs->dwFeatures & CASC_FEATURE_ONLINE)  ? true : false;''',
     '''hf->bAllowDownloading  = (hs->dwFeatures & (CASC_FEATURE_ONLINE | CASC_FEATURE_ALLOW_DOWNLOAD)) ? true : false;'''),
])

patch('CascReadFile.cpp', [
    ('''#ifdef CASCLIB_DEBUG
static unsigned int table_16C57A8''', '''// DS Forever: recover a damaged local single-span entry using a private loose file.
// Never alter the storage entry or the shared data archive.
extern char g_wcDownloadRoot[];
bool wc_reopen_cached(HANDLE source, HANDLE * target)
{
    TCascFile * hf = TCascFile::IsValid(source);
    if(hf == NULL || hf->hs == NULL || !hf->bAllowDownloading || !g_wcDownloadRoot[0] || hf->SpanCount != 1)
        return false;
    PCASC_CKEY_ENTRY entry = hf->pCKeyEntry;
    if(!(entry->Flags & CASC_CE_FILE_IS_LOCAL) || !CascIsValidMD5(entry->EKey))
        return false;
    CASC_PATH<TCHAR> path;
    CASC_ARCHIVE_INFO archive = {0};
    CPATH_TYPE type = (entry->Flags & CASC_CE_FILE_PATCH) ? PathTypePatch : PathTypeData;
    if(FetchCascFile(hf->hs, type, entry->EKey, NULL, path, &archive) != ERROR_SUCCESS)
        return false;
    // This recovery supports loose downloads, not an entire CDN archive.
    if(CascIsValidMD5(archive.ArchiveKey))
        return false;
    return CascOpenLocalFile(path, CASC_STRICT_DATA_CHECK, target);
}

#ifdef CASCLIB_DEBUG
static unsigned int table_16C57A8'''),
])

patch('common/FileStream.cpp', [
    ('''        handle = open(szFileName, oflag | O_LARGEFILE);''',
     '''        handle = open(szFileName, oflag | O_LARGEFILE);
        // DS Forever: a refused read-write open of the game's files is retried read-only (nothing is ever written there)
        // (ENOENT too: Android's shared-storage layer can answer a refused read-write open of another app's file as "not found"; a
        // file that is really absent fails the read-only open the same way)
        int wcFirstErr = (handle == -1) ? errno : 0;
        if(handle == -1 && oflag == O_RDWR && (errno == EACCES || errno == EROFS || errno == EPERM || errno == ENOENT))
            handle = open(szFileName, O_RDONLY | O_LARGEFILE);
        // DS Forever: every open that still fails goes to logcat (tag WCCASC), so a device run says exactly which file was missing
        if(handle == -1) __android_log_print(5, "WCCASC", "open failed: %s (errno %d, first %d, %s)", szFileName, errno, wcFirstErr, oflag == O_RDWR ? "rw" : "ro");
        else if(wcFirstErr != 0) __android_log_print(4, "WCCASC", "opened read-only after rw errno %d: %s", wcFirstErr, szFileName);'''),
    ('''#include "../CascCommon.h"''', '''#include "../CascCommon.h"
#include <android/log.h>'''),
])
# DS Forever 1.0.8 (b-art-update-size): the DOWNLOAD manifest is OPTIONAL to CascLib (it opens without it), but its miss was recorded as
# "the first missing file", so after a game update the app fetched it (49 MB) only to learn that ROOT was the one needed. Its miss no
# longer counts: the file the app fetches is always one the open really needs (ENCODING, ROOT).
patch('CascOpenStorage.cpp', [
    ('''            dwErrCode = LoadDownloadManifest(hs);
            dwErrCode = (dwErrCode == ERROR_FILE_NOT_FOUND) ? ERROR_SUCCESS : dwErrCode;''',
     '''            char wcNeedBefore[1024];
            memcpy(wcNeedBefore, g_wcNeed, sizeof(wcNeedBefore));
            dwErrCode = LoadDownloadManifest(hs);
            if(dwErrCode == ERROR_FILE_NOT_FOUND)
                memcpy(g_wcNeed, wcNeedBefore, sizeof(wcNeedBefore));    // DS Forever: optional, never the file to fetch
            dwErrCode = (dwErrCode == ERROR_FILE_NOT_FOUND) ? ERROR_SUCCESS : dwErrCode;'''),
    ('''static DWORD LoadCascStorage(TCascStorage * hs, PCASC_OPEN_STORAGE_ARGS pArgs, LPCTSTR szMainFile, CBLD_TYPE BuildFileType, DWORD dwFeatures)''',
     '''extern char g_wcNeed[];              // DS Forever: the file the app fetches next (casc_jni.cpp)

static DWORD LoadCascStorage(TCascStorage * hs, PCASC_OPEN_STORAGE_ARGS pArgs, LPCTSTR szMainFile, CBLD_TYPE BuildFileType, DWORD dwFeatures)'''),
])
print('patched', src)
