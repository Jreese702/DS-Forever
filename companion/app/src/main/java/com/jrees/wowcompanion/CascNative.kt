package com.jrees.wowcompanion

import android.util.Log

/**
 * THE HARVEST'S FILE READER (Home, 2026-10-02): CascLib (MIT, Ladislav Zezula) over JNI (tools/harvest/casc_jni.cpp, built by
 * tools/harvest/build_native.sh into libcasc.so). Reads the user's OWN game files from the game's local storage, read-only, so the
 * companion's pictures are built on the device and no game art ships in the APK (the user's release rulings).
 *
 * A build without libcasc.so (it is not in git) has no harvest: [available] is false and nothing else changes.
 */
object CascNative {
    /** the status of the last [read] on this thread */
    const val OK = 0
    const val UNKNOWN = 1          // no such fileDataID in this build's index
    const val NOT_PRESENT = 2      // indexed, but the install never downloaded it (the client streams some files)
    const val ENCRYPTED = 3
    const val OTHER = 4

    val available: Boolean = try {
        System.loadLibrary("casc"); true
    } catch (t: Throwable) {
        Log.w("WCHARVEST", "no libcasc.so in this build: ${t.message}"); false
    }

    /** opens [path] (the folder that holds .build.info and Data) for [product] (e.g. "wow_classic_beta") with CASC [locales] (enUS = 0x2);
     *  0 = failed */
    @JvmStatic external fun open(path: String, product: String, locales: Int): Long
    /** the whole file, or null (then [lastStatus] says why) */
    @JvmStatic external fun read(handle: Long, fileDataId: Int): ByteArray?
    /** DB2 only: zero unavailable encrypted frames; Db2 excludes TACT-keyed sections. */
    @JvmStatic external fun readDb2(handle: Long, fileDataId: Int): ByteArray?
    /** the whole file by its game path (e.g. "interface/worldmap/elwynn/elwynnhighlight.blp"), or null */
    @JvmStatic external fun readName(handle: Long, name: String): ByteArray?
    @JvmStatic external fun lastStatus(): Int
    @JvmStatic external fun lastReadError(): Int
    /** Register a key supplied by the installed game's own TactKey tables, never by the APK. */
    @JvmStatic external fun addEncryptionKey(handle: Long, name: Long, key: ByteArray): Boolean
    /** CascLib's error code of the last failed [open] (2 = a file it needs is not there), 0 after a good one */
    @JvmStatic external fun lastOpenError(): Int
    /** where CascLib may download the build's internal tables the device does not keep (app-private; never the game folder) */
    @JvmStatic external fun setDownloadRoot(dir: String)
    /** the cache path of the file the last [open] needed and could not download itself (then cleared), or null */
    @JvmStatic external fun takeNeed(): String?
    @JvmStatic external fun close(handle: Long)
}
