package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * The game's own icon art, by texture fileDataID -- the number every addon payload already
 * carries (`icon` on a bag row, an action slot, an equipped item). SHARED: any room may call
 * [get]. The bags session owns this file; ask in AGENT_LOG for changes.
 *
 * The on-device harvest builds the fixed startup references plus this game's observed UI
 * requests into a private icons.pak. A new request invalidates the icon stamp and queues a
 * checked rebuild. Missing required art fails the harvest; no game art ships in the APK.
 * [get] returns null while the request is being harvested; the host redraws on completion.
 *
 * icons.pak layout (little-endian, "WCI1"):
 *   u32 magic, u32 count, u32 blobBase, u32 ids[count] (ascending), u32 offs[count+1], blobs[]
 */
object Icons {
    const val PAK_PATH = "/sdcard/WowCompanion/icons.pak"

    /** WoW's own "unknown item" art, used exactly where the game itself would show it */
    const val QUESTION_MARK = 134400

    private class Pak(val buf: ByteBuffer, val ids: IntArray, val offs: IntArray, val base: Int)

    @Volatile private var pak: Pak? = null
    private var lastTry = 0L
    // ~16 KB per 64 px ARGB bitmap: 600 icons = ~10 MB, a full bag plus both action bars
    private val cache = LruCache<Int, Bitmap>(600)

    /** true once a pack is mapped (callers can skip icon layout work until then) */
    fun ready(): Boolean = open() != null

    /** the 64 px icon for a texture fileDataID, or null if unknown / no pack on the device */
    fun get(fileID: Int?): Bitmap? {
        if (fileID == null || fileID <= 0) return null
        cache.get(fileID)?.let { return it }
        val p = open()
        // Track C, test23: the background all-icons step's packs (art/icons_all/*.pak) after the required pack; an icon found there
        // needs no required harvest; only one neither has is asked for
        val fromMain = p?.let { blob(it, fileID) }
        val bytes = fromMain ?: extras().firstNotNullOfOrNull { blob(it, fileID) }
        if (fromMain != null || bytes == null) IconRequirements.require(fileID)      // the required set as before
        if (bytes == null) return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        cache.put(fileID, bmp)
        return bmp
    }

    private fun blob(p: Pak, fileID: Int): ByteArray? {
        val i = p.ids.binarySearch(fileID)
        if (i < 0) return null
        val start = p.base + p.offs[i]
        val len = p.offs[i + 1] - p.offs[i]
        return ByteArray(len).also { p.buf.duplicate().apply { position(start) }.get(it) }
    }

    /** the all-icons step's packs (resolved once per [generation]) */
    @Volatile private var extraPaks: List<Pak>? = null
    @Synchronized private fun extras(): List<Pak> {
        extraPaks?.let { return it }
        val dir = ArtHarvest.dir(AllIcons.DIR)
        return (dir.listFiles { f -> f.name.endsWith(".pak") }?.sortedBy { it.name }?.mapNotNull { load(it) } ?: emptyList()).also { extraPaks = it }
    }

    /** [get], falling back to the game's question-mark icon the way the game itself does */
    fun getOrUnknown(fileID: Int?): Bitmap? = get(fileID) ?: get(QUESTION_MARK)

    /**
     * The game's own UI textures (bag slot well, quality frame, selected glow, coins...), by the
     * short names `tools/icons/extract_ui.py` writes, pushed beside the pack:
     *
     *   adb push ui_bags/. /sdcard/WowCompanion/ui/
     *
     * Same rule as the icons: Blizzard art stays on the device, generated from the user's own
     * client. null when missing, and callers draw a plain stand-in.
     */
    fun ui(name: String): Bitmap? {
        uiCache[name]?.let { return it }
        if (uiMissing.contains(name)) return null
        val f = ArtHarvest.file("ui/$name.png")
        val bmp = if (f.canRead()) BitmapFactory.decodeFile(f.path) else null
        if (bmp == null) uiMissing.add(name) else uiCache[name] = bmp
        return bmp
    }

    const val UI_DIR = "/sdcard/WowCompanion/ui"
    private val uiCache = HashMap<String, Bitmap>()
    private val uiMissing = HashSet<String>()

    /** bumped by every [refresh] (a rebuilt pack): a view that remembered "the pack has no such icon" asks again (D's test18 proof:
     *  Lightning Shield, missing at its first ask, stayed blank on the toolbar after the harvest added it) */
    @Volatile var generation = 0
        private set

    @Synchronized fun refresh() {
        generation++
        pak = null
        extraPaks = null
        lastTry = 0L
        cache.evictAll()
    }

    @Synchronized
    private fun open(): Pak? {
        pak?.let { return it }
        // retry a missing pack every few seconds, so pushing it while the app runs just works
        val now = SystemClock.uptimeMillis()
        if (lastTry != 0L && now - lastTry < 5000) return null
        lastTry = now
        return load(ArtHarvest.file("icons.pak"))?.also { pak = it }
    }

    /** a WCI1 pack, or null */
    private fun load(f: File): Pak? {
        return try {
            if (!f.canRead()) return null
            val ch = RandomAccessFile(f, "r").channel
            val buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.getInt(0) != 0x31494357) return null          // "WCI1"
            val count = buf.getInt(4)
            val base = buf.getInt(8)
            val ids = IntArray(count) { buf.getInt(12 + it * 4) }
            val offBase = 12 + count * 4
            val offs = IntArray(count + 1) { buf.getInt(offBase + it * 4) }
            Pak(buf, ids, offs, base)
        } catch (t: Throwable) {
            null
        }
    }
}
