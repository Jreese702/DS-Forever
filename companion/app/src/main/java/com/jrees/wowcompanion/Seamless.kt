package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * THE SEAMLESS WORLD MAP (b-seamless-map; the user: "Why didn't we implement openmap on our map?" - their picks: one continuous,
 * zoomable continent, the zones stitched and their borders blended, from the game's own map art, a "Show all / Explored only"
 * setting). The art harvest's [STEP] composites each continent ONCE from the world map pictures it already made (worldmap/<id>/base +
 * overlays): the continent's own painting underneath (sea, borders, anything a zone does not cover), then every zone by its world
 * rect (the worldmap recipe's, the frame the routes use), largest first, MASKED to the zone's own outline - its highlight picture as
 * the game places it on the continent (Map.lua's maphl sweep, kept by the Map room), else the union of its overlays - and feathered.
 * Two variants: "all" (every overlay: the whole zone revealed) and "base" (no overlays: the Map room lays the explored ones over it
 * for "Explored only"). Kept as a tile pyramid (seamless/<continent>/<variant>/<level>/<col>_<row>.webp, [TILE] px; level 0 is
 * [W0] x [H0] for the continent's art frame) - built on the device, never shipped; the room decodes only the tiles in view.
 */
object Seamless {
    const val W0 = 4008                 // the continent's art frame (1002 x 668) four times over
    const val H0 = 2672
    const val TILE = 256
    const val LEVELS = 4                // 4008, 2004, 1002, 501 px across
    const val FEATHER = 4               // px at level 0: a zone's edge blends over this much
    val VARIANTS = listOf("all", "base")

    /** a zone's place on its continent's art frame (0..1 of it: left, top, right, bottom), from the two world rects ([c, t, b, l, r]) */
    fun place(cont: MapHarvest.Rect, zone: MapHarvest.Rect): FloatArray = floatArrayOf(
        ((cont.l - zone.l) / (cont.l - cont.r)).toFloat(), ((cont.t - zone.t) / (cont.t - cont.b)).toFloat(),
        ((cont.l - zone.r) / (cont.l - cont.r)).toFloat(), ((cont.t - zone.b) / (cont.t - cont.b)).toFloat())

    /** the zones to stitch onto [cont]: zone maps (mapType 3) of its continent inside its rect, no capital (it lies in its zone's
     *  art), largest first so the smaller ones lie over them */
    fun zonesOf(contId: Int, model: WorldMapModel, rects: Map<Int, MapHarvest.Rect>): List<Int> {
        val c = rects[contId] ?: return emptyList()
        return model.maps.values.filter { m ->
            val r = rects[m.id]
            m.type == 3 && m.id !in WorldMapModel.CITY_ZONE.keys && r != null && r.c == c.c && r.t <= c.t + 1 && r.b >= c.b - 1 &&
                r.l <= c.l + 1 && r.r >= c.r - 1 && (r.t - r.b) < (c.t - c.b) * 0.9
        }.sortedByDescending { m -> rects[m.id]!!.let { (it.t - it.b) * (it.l - it.r) } }.map { it.id }
    }

    /** a soft edge: [a] (w x h alpha) box-blurred [r] px twice (a separable box blur, about a Gaussian) */
    fun feather(a: IntArray, w: Int, h: Int, r: Int): IntArray {
        if (r <= 0) return a
        var cur = a
        repeat(2) {
            val tmp = IntArray(w * h)
            for (y in 0 until h) { var s = 0; var n = 0
                for (x in -r until w + r) {
                    if (x + r < w) { s += cur[y * w + x + r]; n++ }
                    if (x - r - 1 >= 0) { s -= cur[y * w + x - r - 1]; n-- }
                    if (x in 0 until w) tmp[y * w + x] = if (n > 0) s / n else 0
                } }
            val out = IntArray(w * h)
            for (x in 0 until w) { var s = 0; var n = 0
                for (y in -r until h + r) {
                    if (y + r < h) { s += tmp[(y + r) * w + x]; n++ }
                    if (y - r - 1 >= 0) { s -= tmp[(y - r - 1) * w + x]; n-- }
                    if (y in 0 until h) out[y * w + x] = if (n > 0) s / n else 0
                } }
            cur = out
        }
        return cur
    }

    // ---------------------------------------------------------------- the harvest step

    val STEP = object : ArtHarvest.Step {
        override val id = "seamless"
        override val version = 1
        override val beforePlay = false
        override val weight = 120
        override fun run(job: ArtHarvest.Job) {
            val art = File(job.out, "worldmap")
            val man = JSONObject(File(art, "manifest.json").readText())
            val model = WorldMapModel()
            for (id in WorldMapModel.manifestIds(man)) File(art, "$id/index.json").takeIf { it.canRead() }?.let { model.addIndex(JSONObject(it.readText())) }
            // the highlight boxes the Map room kept (Map.lua's maphl sweep): each zone's outline on its continent
            runCatching { JSONObject(File(job.ctx.filesDir, "maproom_maphl.json").readText()) }.getOrNull()?.let { model.onMapHl(it) }
            val rects = MapHarvestSteps.zoneRects(job)
            val conts = model.maps.values.filter { it.type == 2 && rects.containsKey(it.id) && File(art, "${it.id}/base.webp").isFile }.map { it.id }
            var made = 0
            for ((ci, cont) in conts.withIndex()) {
                for (variant in VARIANTS) {
                    job.progress(ci * 2 + VARIANTS.indexOf(variant), conts.size * 2, model.name(cont))
                    val big = composite(art, model, rects, cont, variant)
                    pyramid(big) { rel, b -> job.writeWebp("seamless/$cont/$variant/$rel", b, 82) }
                    big.recycle()
                }
                job.writeBytes("seamless/$cont/index.json", JSONObject().put("w", W0).put("h", H0).put("tile", TILE).put("levels", LEVELS)
                    .put("variants", JSONArray(VARIANTS)).put("zones", JSONArray(zonesOf(cont, model, rects))).toString().toByteArray())
                made++
            }
            android.util.Log.i("WCMAP", "seamless: $made continents stitched")
        }
    }

    /** one continent's level-0 picture */
    fun composite(art: File, model: WorldMapModel, rects: Map<Int, MapHarvest.Rect>, cont: Int, variant: String): Bitmap {
        val big = Bitmap.createBitmap(W0, H0, Bitmap.Config.ARGB_8888)
        val c = Canvas(big)
        val pBmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        // the continent's own painting (and its overlays in "all")
        mapArt(art, model, cont, variant == "all")?.let { b -> c.drawBitmap(b, null, Rect(0, 0, W0, H0), pBmp); b.recycle() }
        val cr = rects[cont] ?: return big
        for (z in zonesOf(cont, model, rects)) {
            val zr = rects[z] ?: continue
            val p = place(cr, zr).let { RectF(it[0], it[1], it[2], it[3]) }
            val x0 = (p.left * W0).roundToInt(); val y0 = (p.top * H0).roundToInt()
            val w = max(2, (p.width() * W0).roundToInt()); val h = max(2, (p.height() * H0).roundToInt())
            val full = mapArt(art, model, z, variant == "all") ?: continue
            val zb = Bitmap.createScaledBitmap(full, w, h, true); if (zb !== full) full.recycle()
            val mask = mask(art, model, cont, z, p, w, h)
            if (mask == null) { zb.recycle(); continue }
            val px = IntArray(w * h); zb.getPixels(px, 0, w, 0, 0, w, h)
            for (k in px.indices) px[k] = (px[k] and 0x00FFFFFF) or (min(255, mask[k]) shl 24)
            zb.setHasAlpha(true)                                    // copied from an opaque picture: its mask would draw black
            zb.setPixels(px, 0, w, 0, 0, w, h)
            c.drawBitmap(zb, x0.toFloat(), y0.toFloat(), pBmp)
            zb.recycle()
        }
        return big
    }

    /** a map's picture at its own size: base, with every overlay over it when [overlays] */
    fun mapArt(art: File, model: WorldMapModel, id: Int, overlays: Boolean): Bitmap? {
        val base = BitmapFactory.decodeFile(File(art, "$id/base.webp").path) ?: return null
        val b = base.copy(Bitmap.Config.ARGB_8888, true); base.recycle()
        if (overlays) {
            val c = Canvas(b)
            for (o in model.maps[id]?.overlays ?: emptyList()) {
                val ov = BitmapFactory.decodeFile(File(art, "$id/ov_${o.id}.webp").path) ?: continue
                c.drawBitmap(ov, null, Rect(o.x, o.y, o.x + o.w, o.y + o.h), null); ov.recycle()
            }
        }
        return b
    }

    /**
     * zone [z]'s outline on [cont] at its [w] x [h] place [p]: its highlight picture where the game placed it (its brightness), else
     * the union of its overlays' alpha, widened a little; feathered. Null = no outline at all (a zone with neither: not stitched).
     */
    fun mask(art: File, model: WorldMapModel, cont: Int, z: Int, p: RectF, w: Int, h: Int): IntArray? {
        val m = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(m)
        var ok = false
        val hl = model.highlightOf(cont, z)
        val hb = hl?.let { BitmapFactory.decodeFile(File(art, "hl/${it.fid}.webp").path) }
        if (hl != null && hb != null) {
            // the picture's used part (texture coords 0..tpx, 0..tpy) laid on its box, in this zone's place
            val src = Rect(0, 0, max(1, (hl.tpx * hb.width).roundToInt()), max(1, (hl.tpy * hb.height).roundToInt()))
            val dst = RectF((hl.x - p.left) / p.width() * w, (hl.y - p.top) / p.height() * h,
                (hl.x + hl.w - p.left) / p.width() * w, (hl.y + hl.h - p.top) / p.height() * h)
            c.drawBitmap(hb, src, dst, Paint(Paint.FILTER_BITMAP_FLAG)); hb.recycle(); ok = true
        } else {
            val info = model.maps[z]
            if (info != null) for (o in info.overlays) {
                val ov = BitmapFactory.decodeFile(File(art, "$z/ov_${o.id}.webp").path) ?: continue
                val dst = RectF(o.x.toFloat() / info.w * w, o.y.toFloat() / info.h * h, (o.x + o.w).toFloat() / info.w * w, (o.y + o.h).toFloat() / info.h * h)
                c.drawBitmap(ov, null, dst, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN) }); ov.recycle(); ok = true
            }
        }
        if (!ok) { m.recycle(); return null }
        val px = IntArray(w * h); m.getPixels(px, 0, w, 0, 0, w, h); m.recycle()
        // the outline's strength: the highlight's brightness (scaled so its body is solid), else the overlays' alpha
        var peak = 1
        val a = IntArray(w * h) { k -> val v = px[k]
            val s = if (hl != null && hb != null) max((v shr 16) and 0xFF, max((v shr 8) and 0xFF, v and 0xFF)) else (v ushr 24) and 0xFF
            if (s > peak) peak = s; s }
        val gain = if (hl != null && hb != null) 255f / max(1, peak) * 1.6f else 1f
        for (k in a.indices) a[k] = min(255, (a[k] * gain).toInt())
        val r = max(1, FEATHER * w / max(1, (p.width() * W0).roundToInt()))
        val f = feather(a, w, h, r)
        // the overlays' union has seams between its pieces: doubled after the blur, they close and the edge stays soft
        if (!(hl != null && hb != null)) for (k in f.indices) f[k] = min(255, f[k] * 2)
        return f
    }

    /** [big] as a tile pyramid: [write] gets each tile's "<level>/<col>_<row>.webp" and its picture */
    fun pyramid(big: Bitmap, write: (String, Bitmap) -> Unit) {
        var lv = big
        for (level in 0 until LEVELS) {
            val cols = (lv.width + TILE - 1) / TILE; val rows = (lv.height + TILE - 1) / TILE
            for (r in 0 until rows) for (cc in 0 until cols) {
                val x = cc * TILE; val y = r * TILE
                val t = Bitmap.createBitmap(lv, x, y, min(TILE, lv.width - x), min(TILE, lv.height - y))
                write("$level/${cc}_$r.webp", t); if (t !== lv) t.recycle()
            }
            if (level + 1 < LEVELS) {
                val next = Bitmap.createScaledBitmap(lv, max(1, lv.width / 2), max(1, lv.height / 2), true)
                if (lv !== big) lv.recycle()
                lv = next
            }
        }
        if (lv !== big) lv.recycle()
    }

    /** a continent's pyramid is on the device (its index) */
    fun has(cont: Int): Boolean = ArtHarvest.file("seamless/$cont/index.json").isFile
    /** a tile's file */
    fun tile(cont: Int, variant: String, level: Int, col: Int, row: Int): File = ArtHarvest.file("seamless/$cont/$variant/$level/${col}_$row.webp")
}
