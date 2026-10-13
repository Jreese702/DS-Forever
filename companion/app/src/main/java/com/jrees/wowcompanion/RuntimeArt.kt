package com.jrees.wowcompanion

import android.content.Context
import android.util.Log
import java.io.File

/**
 * THE RUNTIME ART FOR THE PRIVATE SHARE (Home, 2026-10-02; the user: "I'm planing on sharing privately for now. Can we include this all
 * to speed things up for a private share?"). The APK of a share build carries what the app reads from /sdcard/WowCompanion at run time
 * (ui/, fonts/, minimap/, icons.pak; packed by tools/runtime_art/snapshot.sh, NOT in git - it is Blizzard's art). On a device that
 * lacks it, or holds an older pack, it is copied out ONCE, before any screen loads a picture: the readers (UiKit, Icons, MinimapTiles)
 * remember a missing picture for the whole run, so nothing may look before the copy is done.
 *
 * For a PUBLIC release this is replaced by the on-device harvest from the user's own game files (branch home-harvest).
 */
object RuntimeArt {
    const val ROOT = "/sdcard/WowCompanion"
    private const val STAMP = ".runtime"
    private const val TAG = "WCRUNTIME"

    class Entry(val md5: String, val size: Long, val path: String)

    /** the manifest's lines -> (pack id, files); null when this build carries no runtime art */
    fun parse(lines: List<String>): Pair<String, List<Entry>>? {
        val id = lines.firstOrNull()?.trim()?.takeIf { it.startsWith("runtime ") }?.removePrefix("runtime ")?.trim() ?: return null
        val files = lines.drop(1).mapNotNull { l ->
            val p = l.trim().split(Regex("\\s+"), limit = 3)
            if (p.size == 3 && p[0].length == 32) p[1].toLongOrNull()?.let { Entry(p[0], it, p[2]) } else null
        }
        return id to files
    }

    fun manifest(ctx: Context): Pair<String, List<Entry>>? = try {
        ctx.assets.open("runtime/manifest.txt").bufferedReader().use { parse(it.readLines()) }
    } catch (_: Throwable) { null }

    /** the pack id this device already holds (the stamp the last copy wrote), or null */
    fun installed(): String? = try { File(ROOT, STAMP).readText().trim().ifEmpty { null } } catch (_: Throwable) { null }

    /** true when this build carries a pack the device does not hold yet */
    fun needed(ctx: Context): Boolean {
        if (!BuildConfig.SHARE) return false             // our own builds: the art is pushed by our tools, never unpacked
        val m = manifest(ctx) ?: return false
        return installed() != m.first
    }

    /**
     * Copies the pack out, file by file (a temp name, its size checked, then renamed), reporting 0..100 to [progress] (any thread).
     * The stamp is written LAST, so a copy cut short is simply done again at the next start. Returns null on success, else why.
     */
    fun unpack(ctx: Context, progress: (Int) -> Unit): String? {
        val (id, files) = manifest(ctx) ?: return "this build carries no pictures"
        val root = File(ROOT)
        if (!root.isDirectory && !root.mkdirs()) return "cannot write $ROOT (allow All files access)"
        val total = files.sumOf { it.size }.coerceAtLeast(1)
        var done = 0L
        val buf = ByteArray(1 shl 16)
        var lastPct = -1
        for (e in files) {
            val out = File(root, e.path)
            if (out.isFile && out.length() == e.size) { done += e.size; continue }      // already there (a copy cut short before)
            out.parentFile?.mkdirs()
            val tmp = File(out.path + ".part")
            try {
                ctx.assets.open("runtime/${e.path}").use { i -> tmp.outputStream().use { o ->
                    while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n) }
                } }
            } catch (t: Throwable) {
                tmp.delete(); return "cannot write ${e.path}: ${t.message}"
            }
            if (tmp.length() != e.size) { tmp.delete(); return "${e.path} came out the wrong size" }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) { tmp.delete(); return "cannot place ${e.path}" }
            done += e.size
            val pct = (100L * done / total).toInt()
            if (pct != lastPct) { lastPct = pct; progress(pct) }
        }
        File(root, STAMP).writeText(id)
        Log.i(TAG, "unpacked pack $id: ${files.size} files, ${total / 1024 / 1024} MB")
        progress(100)
        return null
    }
}
