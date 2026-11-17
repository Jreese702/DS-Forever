package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.ln

/**
 * HOME'S MAP ZOOMED OUT ON THE TERRAIN (the user, 2026-11-17: "on the high fidelity map, I dont think that I want the transition to the
 * map overlay, it would be really great if we just kept it as high fidelity there or made it an option toggle"; Settings > Performance >
 * "Map style when zoomed out": Terrain, the default, or Painted). The minimap tiles (MinimapTiles: one 512 px picture per ADT) at lower
 * resolutions, so the whole world can be terrain without decoding thousands of tiles each frame: level [L] is a [TILE] px picture of
 * 2^L x 2^L ADTs (256 / 2^L px an ADT: level 1 = 128 px, level 4 = 16 px). Levels up to [FROM_FILES_MAX] are drawn from the minimap
 * tiles themselves (decoded at 1/2^(L+1) size), higher ones from four of the level below. Each is made once per minimap build and kept
 * on disk ([dir]: the app's cache, never the art folder; a new minimap index = a new stamp); in memory they live in the Map room's ONE
 * picture cache (72 MB, MemWatch's "worldroom"), with its rules (freed on zoom-in, a minute after the module closes, Android's
 * warning). Nothing here is shipped or harvested: it is drawn from what the minimap step already made.
 *
 * A continent is drawn only from its OWN folder (minimap/<continent>/): an ADT a continent does not have is sea, never another
 * continent's tile (the user's Kalimdor report).
 */
object TerrainOverview {
    const val TILE = 256
    const val LEVEL_MIN = 1
    const val LEVEL_MAX = 6
    const val FROM_FILES_MAX = 3
    /** the deep sea where a continent has no ADT: the minimap tiles' own open water (measured: (8, 17, 16) on the harvested Eastern
     *  Kingdoms tiles, (4, 6, 8) on Kalimdor's), so the coast's tiles do not stand out as squares */
    val SEA = Color.rgb(6, 12, 13)

    /** 2^[level] ADTs a side */
    fun span(level: Int): Int = 1 shl level

    /** the level for an ADT [adtPx] px on the screen: the coarsest whose own px an ADT are at least that (sharp, no more) */
    fun level(adtPx: Float): Int {
        if (adtPx <= 0f) return LEVEL_MAX
        val l = floor(ln(TILE / adtPx) / ln(2.0)).toInt()
        return l.coerceIn(LEVEL_MIN, LEVEL_MAX)
    }

    /** an ADT's key (MapHarvest's col / row) */
    private fun k(col: Int, row: Int) = (col shl 8) or row

    // ---- the minimap step's index (minimap/index.json): which ADTs each continent has

    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var idx: Map<Int, Set<Int>>? = null
    @Volatile private var stamp: String? = null
    @Volatile private var loading = false

    /** the continents with tiles and their ADTs (empty: no index yet, the step still building), or null while it is read ([client]
     *  is redrawn when it has been); any thread */
    fun index(client: android.view.View? = null): Map<Int, Set<Int>>? {
        idx?.let { return it }
        if (!loading) { loading = true; exec.execute { load(); client?.postInvalidate() } }
        return null
    }
    /** read it now (the tests; the loader) */
    fun load() {
        try {
            val f = ArtHarvest.rootDir?.let { File(it, MinimapTiles.INDEX) }
            if (f == null || !f.isFile) { stamp = null; idx = emptyMap(); return }
            val o = JSONObject(f.readText()).optJSONObject("continents") ?: return
            val out = HashMap<Int, Set<Int>>()
            for (c in o.keys()) {
                val id = c.toIntOrNull() ?: continue
                val a = o.optJSONObject(c)?.optJSONArray("tiles") ?: continue
                val s = HashSet<Int>()
                for (i in 0 until a.length()) a.optJSONArray(i)?.let { if (it.length() >= 2) s.add(k(it.optInt(0), it.optInt(1))) }
                // only a continent whose folder is there (the index lists what the step made; a folder can go)
                if (s.isNotEmpty() && File(ArtHarvest.rootDir, "minimap/$id").isDirectory) out[id] = s
            }
            stamp = "${f.lastModified()}-${f.length()}"
            idx = out
        } catch (t: Throwable) {
            android.util.Log.w("WCMAP", "terrain overview: the minimap index did not read: $t")
        } finally { loading = false }
    }
    /** the minimap step made more (MinimapTiles.refresh): read the index again */
    fun refresh() { idx = null; stamp = null }

    /** continent [cont] has terrain to draw */
    fun has(cont: Int): Boolean = index()?.get(cont)?.isNotEmpty() == true

    /** level [level]'s picture ([oc], [or]) of [cont] holds at least one of its ADTs */
    fun has(cont: Int, level: Int, oc: Int, or: Int): Boolean {
        val s = index()?.get(cont) ?: return false
        val n = span(level)
        for (c in oc * n until oc * n + n) for (r in or * n until or * n + n) if (k(c, r) in s) return true
        return false
    }

    /** the picture's key in the room's cache */
    fun key(cont: Int, level: Int, oc: Int, or: Int) = "tov:$cont/$level/${oc}_$or"

    /** where the pictures are kept (the app's cache folder, CompanionHost / MapView set it) */
    @Volatile var dir: File? = null

    private fun file(cont: Int, level: Int, oc: Int, or: Int): File? {
        val d = dir ?: return null
        val s = stamp ?: return null
        return File(d, "terrain-ov/$s/$cont/$level/${oc}_$or.webp")
    }

    /**
     * Level [level]'s picture ([oc], [or]) of continent [cont]: from the disk, else made (and kept). Off the UI thread (the room's reader).
     * Null = the continent has nothing there.
     */
    fun make(cont: Int, level: Int, oc: Int, or: Int): Bitmap? {
        if (!has(cont, level, oc, or)) return null
        stamp?.let { if (swept != it) { swept = it; sweepOld() } }
        val f = file(cont, level, oc, or)
        if (f != null && f.isFile) runCatching {
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
        }.getOrNull()?.let { return it }
        val b = build(cont, level, oc, or) ?: return null
        if (f != null) runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.outputStream().use { @Suppress("DEPRECATION") b.compress(Bitmap.CompressFormat.WEBP, 85, it) }
            if (!tmp.renameTo(f)) tmp.delete()
        }
        return b
    }

    private val pDraw = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun build(cont: Int, level: Int, oc: Int, or: Int): Bitmap? {
        val out = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        c.drawColor(SEA)
        val n = span(level)
        var got = 0
        if (level <= FROM_FILES_MAX) {
            val s = index()?.get(cont) ?: return null.also { out.recycle() }
            val px = TILE / n
            val opt = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565; inSampleSize = 2 * n }
            val dst = Rect()
            for (dc in 0 until n) for (dr in 0 until n) {
                val col = oc * n + dc; val row = or * n + dr
                if (k(col, row) !in s) continue
                val t = ArtHarvest.file("minimap/$cont/${col}_$row.webp")
                val b = runCatching { BitmapFactory.decodeFile(t.path, opt) }.getOrNull() ?: continue
                dst.set(dc * px, dr * px, dc * px + px, dr * px + px)
                c.drawBitmap(b, null, dst, pDraw); b.recycle(); got++
            }
        } else {
            val h = TILE / 2
            val dst = Rect()
            for (dc in 0..1) for (dr in 0..1) {
                val b = make(cont, level - 1, oc * 2 + dc, or * 2 + dr) ?: continue
                dst.set(dc * h, dr * h, dc * h + h, dr * h + h)
                c.drawBitmap(b, null, dst, pDraw); b.recycle(); got++
            }
        }
        if (got == 0) { out.recycle(); return null }
        return out
    }

    @Volatile private var swept: String? = null
    /** the pictures of an older minimap build go (one stamp kept) */
    private fun sweepOld() {
        val s = stamp ?: return
        val root = dir?.let { File(it, "terrain-ov") } ?: return
        root.listFiles()?.forEach { if (it.name != s) it.deleteRecursively() }
    }
}
