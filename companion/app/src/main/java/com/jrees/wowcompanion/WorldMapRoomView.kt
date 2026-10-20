package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Path
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.min

/**
 * THE MAP ROOM, LIKE THE GAME'S OWN WORLD MAP (the user, 2026-10-07: "I want the home map module and thr map room to be distinctly
 * different. The uom map module is fine to stay as is,, but the map room should function like wows in game maps"; then "when going
 * into a map, I dont want to use the super detailed area maps, I want to use the normal zone maps"; unexplored areas "Like the game:
 * only what I've explored"; the extras "Under Show:, off to start"; the quest list "On the right, like the game's quest panel").
 * Home keeps its MapView module; this view is the Map room only, drawn as UI Art's sheet (SHEET_home_vs_maproom_v2).
 *
 * - The art: the game's painted world-map art (a base parchment + one picture per WorldMapOverlay) from the device's art set,
 *   /sdcard/WowCompanion/worldmap/<uiMapID>/{base.webp, ov_<id>.webp, index.json}; an overlay is painted only when this character
 *   has explored it (Map.lua's mapexpl; WorldMapModel).
 * - It opens on the player's WHOLE zone: no following, no auto zoom. LB / RB zoom like the game's map; LB at the whole zone goes UP
 *   to the parent map, as the game's map does when zoomed out. On a continent / the world the D-pad moves between its zones (the
 *   game's area label: name and level range) and A or RB opens one. B steps back through what was browsed, then leaves (Home).
 * - The breadcrumb (World > continent > zone, each a drop-down of its neighbours), the quest panel on the right (the zone's quests,
 *   numbered like its discs), the Show: menu (Y), coordinates, the panel toggle.
 */
class WorldMapRoomView(context: Context) : View(context) {

    val model = WorldMapModel()

    // ---------------------------------------------------------------- the art set on the device
    private val io = Executors.newSingleThreadExecutor()
    private val bmps = object : LruCache<String, Bitmap>(72 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val inFlight = HashSet<String>()
    @Volatile private var artReady = false
    private var artNote: String? = null
    private fun artDir() = ArtHarvest.dir("worldmap")

    /** read the art set's manifest and indexes off the UI thread (60 small files) */
    fun loadArt() {
        laysFor = -1; seamZones.clear()                 // the layers follow the art read again
        // the world map's continents, each by its own UiMapAssignment row (b-world-zoom: the recipe's "assign")
        runCatching { context.assets.open("harvest/worldmap.json").use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) } }
            .getOrNull()?.optJSONObject("assign")?.let { model.setAssign(it) }
        io.execute {
            val dir = artDir()
            val note = try {
                val man = JSONObject(File(dir, "manifest.json").readText())
                for (id in WorldMapModel.manifestIds(man)) {
                    val f = File(dir, "$id/index.json")
                    if (f.canRead()) post { model.addIndex(JSONObject(f.readText())) }
                }
                null
            } catch (t: Throwable) { "The world map pictures are not on this device yet" }
            post { artNote = note; artReady = note == null; if (canvas == 0) jumpTo(startMap()); invalidate() }
            restoreKept()          // the last exploration / highlight boxes / zones' quests, until the game sends new ones
        }
    }

    private fun bmp(key: String): Bitmap? {
        bmps.get(key)?.let { return it }
        if (key in inFlight) return null
        inFlight.add(key)
        io.execute {
            val b = try { BitmapFactory.decodeFile(File(artDir(), "$key.webp").path) } catch (t: Throwable) { null }
            post { inFlight.remove(key); if (b != null) bmps.put(key, b); invalidate() }
        }
        return null
    }

    /** a picture by its file, decoded off the UI thread at 1/[sample] size, cached as [key] (the seamless tiles, sampled overlays) */
    private fun bmpFile(key: String, f: File, sample: Int = 1): Bitmap? {
        bmps.get(key)?.let { return it }
        if (key in inFlight) return null
        inFlight.add(key)
        io.execute {
            val b = try { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) } catch (t: Throwable) { null }
            post { inFlight.remove(key); if (b != null) bmps.put(key, b); invalidate() }
        }
        return null
    }

    // ---------------------------------------------------------------- THE LAYERED MAP (b-maproom-layers)
    // The user on test63: "The map zoom in and zoom out is really inconsistent on so many zones. Can we make it work more exactly like
    // OpenMap does, so that it kind of just layers opacity down to the next level of the zoom, not literally bringing me to a map that I
    // can't go to." So the room never hands over to another map as it zooms: it draws ONE CANVAS - the world map, or a continent's
    // stitched picture (Seamless: the harvest's tile pyramid of the game's own zone art, blended at the zones' outlines) - with every
    // finer map a LAYER on it at its place (WorldMapModel.layersOn): the continents on the world, their zones, the capitals in them. A
    // layer's own art fades in over its outline as the zoom makes it fill the view (WorldMapModel.fade: from 55% of the view's width to
    // 85%), and back out as it zooms out. The zoom, the view and the cursor live in the canvas's frame, so the view never jumps; the
    // world hands its canvas to a continent only where the continent covers the view whole, drawn exactly as the world drew it. What
    // the view's middle is on ([shown]: the deepest layer at least half shown there) names the crumbs, the title and the list; A on a
    // zone, a crumb or More actions glides the view until that map fits it ([goTo]). "World map: Explored only" draws the zones'
    // unrevealed art ("base") with only the overlays this character has explored over it.
    private val seamZones = HashMap<Int, List<Int>>()
    private fun seamless(id: Int): Boolean = (model.maps[id]?.type == 2) && Seamless.has(id)
    private fun seamZonesOf(cont: Int): List<Int> = seamZones.getOrPut(cont) {
        runCatching { JSONObject(ArtHarvest.file("seamless/$cont/index.json").readText()).optJSONArray("zones") }.getOrNull()
            ?.let { a -> List(a.length()) { a.optInt(it) } } ?: emptyList()
    }
    /** a continent's zones, largest first: the stitched picture's, else its zones with a place on it */
    private fun zonesOf(cont: Int): List<Int> = seamZonesOf(cont).ifEmpty {
        model.maps.values.filter { it.type == 3 && model.continentOf(it.id) == cont && model.zonePlace(cont, it.id) != null }
            .sortedByDescending { m -> model.zonePlace(cont, m.id)!!.let { (it[2] - it[0]) * (it[3] - it[1]) } }.map { it.id }
    }

    /** the map the view is drawn on: its frame holds [zoom], [panX] / [panY] and the cursor ([ccx] / [ccy]) */
    private var canvas = 0
    private var ccx = 0.5f
    private var ccy = 0.5f
    /** [shown]'s frame on the canvas: the marks, areas and pins stay in [shown]'s own 0..1 and go through it ([sx] / [sy]) */
    private val pl = RectF(0f, 0f, 1f, 1f)
    /** the cursor in [shown]'s 0..1 */
    private var curX: Float get() = (ccx - pl.left) / pl.width(); set(v) { ccx = pl.left + v * pl.width() }
    private var curY: Float get() = (ccy - pl.top) / pl.height(); set(v) { ccy = pl.top + v * pl.height() }
    /** the map a glide was asked for: it names the view while the middle stays on it (a zone's middle can lie in a neighbour's outline) */
    private var hereTarget = 0

    private class Lay(val id: Int, val kind: Int, val p: RectF, val parent: Int?) {
        var a = 0f                       // its own art's opacity at this zoom
        var vis = 1f                     // its parents' (a capital shows only as much as its zone)
        val shown get() = a * vis
    }
    private var lays: List<Lay> = emptyList()
    private var laysFor = -1
    private var laysN = -1
    /** the canvas's layers, each with its opacity at this zoom */
    private fun layers(): List<Lay> {
        if (laysFor != canvas || laysN != model.maps.size) {
            laysFor = canvas; laysN = model.maps.size
            lays = model.layersOn(canvas, ::zonesOf).map { Lay(it.id, it.kind, RectF(it.place[0], it.place[1], it.place[2], it.place[3]), it.parent) }
            // the zones' outlines (their highlight pictures) load now: the layers' masks, and what the view's middle is on
            if (model.maps[canvas]?.type == 2) for (l in lays) model.highlightOf(canvas, l.id)?.let { bmp("hl/${it.fid}") }
        }
        val by = HashMap<Int, Lay>()
        for (l in lays) {
            l.a = if (l.kind == WorldMapModel.CONTINENT) WorldMapModel.fadeContinent(zoom, l.p.width()) else WorldMapModel.fade(zoom * l.p.width())
            l.vis = l.parent?.let { by[it] }?.let { it.vis * it.a } ?: 1f
            by[l.id] = l
        }
        return lays
    }
    private fun layOf(id: Int): Lay? = layers().firstOrNull { it.id == id }
    /** map [id]'s place on the canvas */
    private fun placeOf(id: Int): RectF? = model.placeOn(id, canvas)?.let { RectF(it[0], it[1], it[2], it[3]) }

    /** the canvas to [to]: the view, the cursor and a glide's target kept where they are on the screen */
    private fun switchCanvas(to: Int) {
        model.placeOn(canvas, to)?.let { p ->
            val w = p[2] - p[0]; val h = p[3] - p[1]
            zoom /= w
            panX = p[0] + panX * w; panY = p[1] + panY * h
            ccx = p[0] + ccx * w; ccy = p[1] + ccy * h
            if (flying()) { flyZ /= w; flyX = p[0] + flyX * w; flyY = p[1] + flyY * h }
        }
        android.util.Log.i("WCMAP", "map room: canvas $canvas -> $to at zoom ${"%.2f".format(zoom)}")
        canvas = to; laysFor = -1
        pl.set(placeOf(shown)?.takeIf { it.width() > 0f && it.height() > 0f } ?: RectF(0f, 0f, 1f, 1f))
    }

    /**
     * The canvas the view needs: a continent seen whole and less goes to the world (by its assignment there, where the world draws
     * it the same); on the world, a continent covering the view whole becomes the canvas (so its zones and capitals can show)
     */
    private fun relayer() {
        var guard = 0
        while (guard++ < 3) {
            val m = model.maps[canvas] ?: return
            if (m.type == 2 && zoom < 1f) {
                val w = model.parentOf(canvas)?.takeIf { p -> model.assignsOf(p).isNotEmpty() && model.placeOn(canvas, p) != null }
                if (w != null) { switchCanvas(w); continue }
            }
            if (model.assignsOf(canvas).isNotEmpty()) {
                val h = 0.5f / zoom
                val k = layers().firstOrNull { l -> l.kind == WorldMapModel.CONTINENT && zoom * l.p.width() >= 0.999f &&
                    l.p.left <= panX - h + EDGE_EPS && l.p.right >= panX + h - EDGE_EPS && l.p.top <= panY - h + EDGE_EPS && l.p.bottom >= panY + h - EDGE_EPS }
                if (k != null) { switchCanvas(k.id); continue }
            }
            return
        }
    }

    /** the continent on the world canvas whose box (the one it is drawn in) holds ([u], [v]) */
    private fun contAt(u: Float, v: Float): Lay? = layers().firstOrNull { l -> l.kind == WorldMapModel.CONTINENT &&
        model.maps[l.id]?.world?.continent?.let { model.assignOf(canvas, it)?.holds(u, v) } == true }

    /** layer [l]'s outline (the game's highlight of it on the continent) at ([u], [v]) of the canvas: true / false, null = not known */
    private fun inOutline(l: Lay, u: Float, v: Float): Boolean? {
        val h = model.highlightOf(canvas, l.id) ?: return null
        if (h.w <= 0f || h.h <= 0f || bmp("hl/${h.fid}") == null) return null
        if (u < h.x || u > h.x + h.w || v < h.y || v > h.y + h.h) return false
        return hlLit(h.fid, (u - h.x) / h.w * h.tpx, (v - h.y) / h.h * h.tpy)
    }
    /** how deep ([u], [v]) lies in [l]'s place: its nearest edge, a share of the place */
    private fun depth(l: Lay, u: Float, v: Float): Float =
        min(min(u - l.p.left, l.p.right - u) / l.p.width(), min(v - l.p.top, l.p.bottom - v) / l.p.height())

    /** the zone of [ls] at ([u], [v]): [prefer] while its outline holds the spot, else the one whose outline does, else the deepest */
    private fun zoneUnder(ls: List<Lay>, u: Float, v: Float, prefer: Int?): Lay? {
        val on = ls.filter { u in it.p.left..it.p.right && v in it.p.top..it.p.bottom }
        if (on.isEmpty()) return null
        on.firstOrNull { it.id == prefer && inOutline(it, u, v) != false }?.let { return it }
        return on.firstOrNull { inOutline(it, u, v) == true } ?: on.maxByOrNull { depth(it, u, v) }
    }

    /** what the view's middle is on: the deepest layer at least half shown there (a glide's target while it holds the middle) */
    private fun pickHere() {
        val ls = layers()
        val u = panX; val v = panY
        var here = canvas
        contAt(u, v)?.takeIf { it.shown >= 0.5f }?.let { here = it.id }
        val target = hereTarget.takeIf { it != 0 }
        zoneUnder(ls.filter { it.kind == WorldMapModel.ZONE && it.shown >= 0.5f }, u, v, target ?: (WorldMapModel.CITY_ZONE[shown] ?: shown))
            ?.let { here = it.id }
        ls.filter { it.kind == WorldMapModel.CITY && it.shown >= 0.5f && u in it.p.left..it.p.right && v in it.p.top..it.p.bottom }
            .let { cs -> cs.firstOrNull { it.id == target } ?: cs.firstOrNull() }?.let { here = it.id }
        if (target != null && here != target) hereTarget = 0
        if (here != shown) setHere(here)
    }

    /** the view's middle is on [id] now: the crumbs, the title, the drawer and the list follow; the view stays */
    private fun setHere(id: Int) {
        android.util.Log.i("WCMAP", "map room: here $shown -> $id (canvas $canvas, zoom ${"%.2f".format(zoom)})")
        shown = id
        pl.set(placeOf(id)?.takeIf { it.width() > 0f && it.height() > 0f } ?: RectF(0f, 0f, 1f, 1f))
        nearFor = -1
        setDrawer(panelWanted())
        if (region == MapRoomKeys.Region.MAP) refreshTop()
        updateHover(prefer = hoverQuest)
        syncB()
    }

    /** straight to map [id], fitted (the room opening, a map on another root) */
    private fun jumpTo(id: Int) {
        if (!model.maps.containsKey(id)) return
        flyZ = Float.NaN
        canvas = model.canvasFor(id); laysFor = -1
        val p = placeOf(id) ?: RectF(0f, 0f, 1f, 1f)
        zoom = (1f / max(p.width(), p.height())).coerceIn(zoomMin(), zoomMax())
        panX = p.centerX(); panY = p.centerY()
        relayer(); clampPan()
        hereTarget = id
        setHere(id)
    }

    // THE GLIDE (A on a zone, a crumb, More actions, LB / RB): the zoom and the view ease to a target in the canvas's frame (the zoom
    // by its log: the same feel at every scale), the layers fading as they pass; a change of canvas on the way carries the target
    private var flyZ = Float.NaN
    private var flyX = 0.5f
    private var flyY = 0.5f
    private var flyAt = 0L
    private var flyDone: (() -> Unit)? = null
    private fun flying() = !flyZ.isNaN()
    private fun fly(z: Float, x: Float, y: Float, done: (() -> Unit)? = null) {
        flyZ = z; flyX = x; flyY = y; flyAt = 0L; flyDone = done
        invalidate()
    }
    private fun stepFly() {
        if (!flying()) return
        val now = SystemClock.uptimeMillis()
        val dt = if (flyAt == 0L) 1f / 60f else ((now - flyAt) / 1000f).coerceIn(0f, 0.05f)
        flyAt = now
        val k = 1f - exp(-dt * FLY_RATE)
        zoom = exp(ln(zoom) + (ln(flyZ) - ln(zoom)) * k)
        panX += (flyX - panX) * k; panY += (flyY - panY) * k
        relayer()
        val z = zoom.coerceIn(zoomMin(), zoomMax())
        if (z != zoom) { zoom = z; flyZ = z }                   // past the range: it stops at its end
        if (abs(ln(flyZ / zoom)) < 0.004f && abs(flyX - panX) * zoom < 0.0015f && abs(flyY - panY) * zoom < 0.0015f) {
            zoom = flyZ; panX = flyX; panY = flyY; flyZ = Float.NaN
            relayer(); clampPan(); pickHere()
            val d = flyDone; flyDone = null; d?.invoke()
        } else { pickHere(); postInvalidateOnAnimation() }
    }

    /** after the stick's zoom: the canvas, the range, the view, what the middle is on */
    private fun afterZoom() {
        relayer()
        zoom = zoom.coerceIn(zoomMin(), zoomMax())
        clampPan(); pickHere(); invalidate()
    }

    /** the zoom's range on the canvas: below 1x a continent goes to the world (relayer); in until the finest layer is well past whole */
    private fun zoomMin() = 1f
    private fun zoomMax(): Float {
        val ls = layers()
        if (model.assignsOf(canvas).isNotEmpty())
            return ls.filter { it.kind == WorldMapModel.CONTINENT }.maxOfOrNull { 1.25f / it.p.width() } ?: 3.2f
        val finest = ls.minOfOrNull { it.p.width() } ?: return 3.2f
        return max(3.2f, min(64f, 1.6f / finest))
    }

    // ---- THE CANVAS'S PICTURES
    /** map [id]'s own picture over its place [p] on the canvas: a continent's stitched pyramid, else its base and overlays ("World map:
     *  Show all" reveals every zone's whole art, so the fade never goes from revealed to fogged; "Explored only" the game's own rule) */
    private fun drawPicture(c: Canvas, id: Int, p: RectF) {
        if (seamless(id)) { drawPyramid(c, id, p); return }
        val info = model.maps[id] ?: return
        val base = bmp("$id/base")
        if (base == null) {
            if (id == canvas) { pFill.color = Color.rgb(196, 168, 116); c.drawRect(sxc(p.left), syc(p.top), sxc(p.right), syc(p.bottom), pFill) }
            return
        }
        dst.set(sxc(p.left), syc(p.top), sxc(p.right), syc(p.bottom))
        c.drawBitmap(base, null, dst, pBmp)
        for (o in if (Settings.worldMapFog == "all") info.overlays else model.paintedOverlays(id)) {
            val b = bmp("$id/ov_${o.id}") ?: continue
            overlayDst(p, info, o)
            c.drawBitmap(b, null, dst, pBmp)
        }
    }
    /** [dst] = overlay [o] of map [info] at its place [p] on the screen */
    private fun overlayDst(p: RectF, info: WorldMapModel.MapInfo, o: WorldMapModel.Overlay) {
        dst.set(sxc(p.left + o.x / info.w.toFloat() * p.width()), syc(p.top + o.y / info.h.toFloat() * p.height()),
            sxc(p.left + (o.x + o.w) / info.w.toFloat() * p.width()), syc(p.top + (o.y + o.h) / info.h.toFloat() * p.height()))
    }

    /** a continent's stitched picture at place [p]: the coarsest level whole under the level whose pixels are at least the screen's */
    private fun drawPyramid(c: Canvas, cont: Int, p: RectF) {
        val variant = if (Settings.worldMapFog == "explored") "base" else "all"
        val screenW = artR.width() * zoom * p.width()
        var level = 0
        while (level + 1 < Seamless.LEVELS && (Seamless.W0 shr (level + 1)) >= screenW) level++
        val u0 = (uxc(canvasR.left) - p.left) / p.width(); val u1 = (uxc(canvasR.right) - p.left) / p.width()
        val v0 = (uyc(canvasR.top) - p.top) / p.height(); val v1 = (uyc(canvasR.bottom) - p.top) / p.height()
        for (lv in listOf(Seamless.LEVELS - 1, level).distinct()) {
            val lw = Seamless.W0 shr lv; val lh = Seamless.H0 shr lv
            val cols = (lw + Seamless.TILE - 1) / Seamless.TILE; val rows = (lh + Seamless.TILE - 1) / Seamless.TILE
            for (r in 0 until rows) for (col in 0 until cols) {
                val tu0 = col * Seamless.TILE.toFloat() / lw; val tu1 = min(1f, (col + 1) * Seamless.TILE.toFloat() / lw)
                val tv0 = r * Seamless.TILE.toFloat() / lh; val tv1 = min(1f, (r + 1) * Seamless.TILE.toFloat() / lh)
                if (tu1 < u0 || tu0 > u1 || tv1 < v0 || tv0 > v1) continue
                val b = bmpFile("seam:$cont/$variant/$lv/${col}_$r", Seamless.tile(cont, variant, lv, col, r)) ?: continue
                dst.set(sxc(p.left + tu0 * p.width()), syc(p.top + tv0 * p.height()), sxc(p.left + tu1 * p.width()), syc(p.top + tv1 * p.height()))
                c.drawBitmap(b, null, dst, pBmp)
            }
        }
        // Explored only: each zone's explored overlays over its unrevealed art, decoded no larger than they show
        if (variant == "base") for (z in seamZonesOf(cont)) {
            val zp = model.zonePlace(cont, z)?.let { WorldMapModel.compose(floatArrayOf(p.left, p.top, p.right, p.bottom), it) } ?: continue
            if (sxc(zp[2]) < canvasR.left || sxc(zp[0]) > canvasR.right || syc(zp[3]) < canvasR.top || syc(zp[1]) > canvasR.bottom) continue
            val info = model.maps[z] ?: continue
            val zr = RectF(zp[0], zp[1], zp[2], zp[3])
            val zw = sxc(zr.right) - sxc(zr.left)
            var sample = 1
            while (sample < 8 && info.w / (sample * 2) >= zw) sample *= 2
            for (o in model.paintedOverlays(z)) {
                val b = bmpFile("seamov:$z/${o.id}@$sample", File(ArtHarvest.dir("worldmap"), "$z/ov_${o.id}.webp"), sample) ?: continue
                overlayDst(zr, info, o)
                c.drawBitmap(b, null, dst, pBmp)
            }
        }
    }

    /** the canvas and its layers: each layer's own art at its opacity, over its outline */
    private fun drawArt(c: Canvas) {
        drawPicture(c, canvas, UNIT_R)
        val mid = contAt(panX, panY)
        for (l in layers().sortedBy { if (it === mid) 1 else 0 }) {
            val a = l.shown
            if (a <= 0.004f) continue
            val sr = RectF(sxc(l.p.left), syc(l.p.top), sxc(l.p.right), syc(l.p.bottom))
            if (!sr.intersect(canvasR)) continue
            if (l.kind == WorldMapModel.CONTINENT) drawContinentLayer(c, l, a) else drawMasked(c, l, a, sr)
        }
    }

    /** a continent on the world: its stitched picture in the box the world draws it in, the box widening to its whole frame as it
     *  fades in, its edge soft until it is whole (where the canvas becomes the continent's, drawn the same) */
    private fun drawContinentLayer(c: Canvas, l: Lay, a: Float) {
        val box = RectF(l.p)
        model.maps[l.id]?.world?.continent?.let { model.assignOf(canvas, it) }?.let { s ->
            box.intersect(s.u0.toFloat(), s.v0.toFloat(), s.u1.toFloat(), s.v1.toFloat())
        }
        val k = l.a
        val cl = RectF(sxc(box.left + (l.p.left - box.left) * k), syc(box.top + (l.p.top - box.top) * k),
            sxc(box.right + (l.p.right - box.right) * k), syc(box.bottom + (l.p.bottom - box.bottom) * k))
        val sr = RectF(cl)
        if (!sr.intersect(canvasR)) return
        pLayer.alpha = (255 * a).toInt().coerceIn(0, 255)
        c.saveLayer(sr, pLayer)
        c.clipRect(sr)
        drawPicture(c, l.id, l.p)
        // the soft edge, pushed out past the frame over the fade's last quarter: none left once it is whole
        val push = ((k - 0.75f) / 0.25f).coerceIn(0f, 1f)
        val gx = cl.width() * SOFT_IN * push; val gy = cl.height() * SOFT_IN * push
        c.saveLayer(sr, pMaskIn)
        dst.set(cl.left - gx, cl.top - gy, cl.right + gx, cl.bottom + gy)
        c.drawBitmap(softEdge, null, dst, pBmp)
        c.restore()
        c.restore()
    }

    /** a zone's / capital's own art at [a], cut to its outline: the game's highlight picture of the zone (as the stitch was), else
     *  its overlays, else a soft-edged box (a capital) */
    private fun drawMasked(c: Canvas, l: Lay, a: Float, sr: RectF) {
        pLayer.alpha = (255 * a).toInt().coerceIn(0, 255)
        c.saveLayer(sr, pLayer)
        drawPicture(c, l.id, l.p)
        c.saveLayer(sr, pMaskIn)
        val h = if (l.kind == WorldMapModel.ZONE) model.highlightOf(canvas, l.id) else null
        val hb = h?.let { bmp("hl/${it.fid}") }
        val info = model.maps[l.id]
        if (h != null && hb != null) {
            // its brightness as the mask, full from half its brightest (the hover's rule), its own soft edge outward
            val km = 510f / hlPeak(h.fid, hb)[1]
            pMaskHl.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                0f, 0f, 0f, 0f, 0f,  0f, 0f, 0f, 0f, 0f,  0f, 0f, 0f, 0f, 0f,  km, km, km, 0f, 0f)))
            srcR.set(0, 0, max(1, (hb.width * h.tpx).toInt()), max(1, (hb.height * h.tpy).toInt()))
            dst.set(sxc(h.x), syc(h.y), sxc(h.x + h.w), syc(h.y + h.h))
            c.drawBitmap(hb, srcR, dst, pMaskHl)
        } else if (l.kind == WorldMapModel.ZONE && info != null && info.overlays.isNotEmpty()) {
            for (o in info.overlays) { val b = bmp("${l.id}/ov_${o.id}") ?: continue; overlayDst(l.p, info, o); c.drawBitmap(b, null, dst, pBmp) }
        } else {
            dst.set(sxc(l.p.left), syc(l.p.top), sxc(l.p.right), syc(l.p.bottom))
            c.drawBitmap(softEdge, null, dst, pBmp)
        }
        c.restore(); c.restore()
    }
    private val pLayer = Paint()
    private val pMaskIn = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val pMaskHl = Paint(Paint.FILTER_BITMAP_FLAG)
    /** a box's soft edge (14% of each side feathered) as a mask */
    private val softEdge: Bitmap by lazy {
        val n = 64
        fun e(t: Int): Float { val d = (min(t, n - 1 - t) / (n * 0.14f)).coerceIn(0f, 1f); return d * d * (3f - 2f * d) }
        val px = IntArray(n * n) { k -> ((255 * e(k % n) * e(k / n)).toInt() shl 24) or 0xFFFFFF }
        Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    // ---------------------------------------------------------------- live data
    private var mapDoc: JSONObject? = null
    private var playerMap = 0
    private var px = Float.NaN; private var py = Float.NaN; private var facing = Float.NaN
    private val questLog = ArrayList<JSONObject>()
    private var docFocus = 0                       // the game's focus, as its quests document says
    /** the focus as SHOWN: the room's own pending focus at once, until the game's document says so or the wait runs out */
    private val focusQid: Int get() = focusPending?.takeIf { System.currentTimeMillis() - it.second < OPTIMISTIC_MS }?.first ?: docFocus
    private var focusPending: Pair<Int, Long>? = null
    private class Pending(val tracked: Boolean, val at: Long)
    private val trackPending = HashMap<Int, Pending>()
    private var padDoc: JSONObject? = null
    private var qdbPins: QdbPins.Pins? = null
    private var qdbNodes: QdbPins.Nodes? = null

    /** the full map document (MainActivity's "full" route) */
    fun update(m: JSONObject) {
        mapDoc = m
        playerMap = m.optInt("mapID", 0)
        px = m.optDouble("x", Double.NaN).toFloat(); py = m.optDouble("y", Double.NaN).toFloat()
        facing = m.optDouble("facing", Double.NaN).toFloat()
        if (canvas == 0 && artReady) jumpTo(startMap())
        if (isShown && shown == playerMap) updateHover(prefer = hoverQuest)
        if (isShown) invalidate()
    }

    /** a position document: the player moves on the map; nothing else changes */
    fun updatePos(pos: JSONObject) {
        val mid = pos.optInt("mapID", playerMap)
        if (mid != playerMap) { playerMap = mid; mapDoc?.put("mapID", mid) }
        px = pos.optDouble("x", Double.NaN).toFloat(); py = pos.optDouble("y", Double.NaN).toFloat()
        facing = pos.optDouble("facing", facing.toDouble()).toFloat()
        if (pos.has("grpN")) group = GroupMembers.parse(pos.optJSONArray("grp"))   // the group (b-group-map)
        if (isShown) invalidate()
    }
    private var group: List<GroupMembers.Member> = emptyList()

    // ---- TRAVEL (b-travel-layer): the map module's scene (its "Travel" filter), on the zone or the continent shown; a zone names its
    // points; the cursor on one (a tap, the stick) shows its name and where it goes
    var travelScene: (() -> Travel.Scene?)? = null

    // ---- THE QUEST PANEL (the user on test61: "Selecting a quest with A opens that quest in the quest panel"): the map module's own
    // (QuestPanel), its Share / Abandon through this room's Quests actions (its pending answers, its Abandon question)
    private val qp = QuestPanel(object : QuestPanel.Host {
        override fun quest(id: Int): JSONObject? = logEntry(id)
        override fun tracked(id: Int): Boolean = logEntry(id)?.let { trackedQ(it) } ?: false
        override fun colour(q: JSONObject): Int = Theme.gold
        override fun party(): Boolean = questsParty
        override fun note(text: String) = say(text)
        override fun share(id: Int) { if (pendingBusy()) say("Waiting for the game to answer") else sendOp(QuestPad.Act.SHARE, id) }
        override fun abandonArm(id: Int) { if (pendingBusy()) say("Waiting for the game to answer") else sendOp(QuestPad.Act.ARM, id) }
        override fun abandonConfirm(id: Int) { confirmId = id; confirmAt = System.currentTimeMillis(); confirmAbandon() }
        override fun changed() { syncB(); invalidate() }
        override fun redrawIn(ms: Long) { postInvalidateDelayed(ms) }
    })
    private var travelDrawn: List<Pair<Travel.Point, FloatArray>> = emptyList()
    private fun drawTravel(c: Canvas) {
        travelDrawn = emptyList()
        val s = travelScene?.invoke() ?: return
        // a world map: each continent through its own assignment (b-world-zoom: the icons sat in the ocean)
        val assigns = model.assignsOf(shown)
        if (assigns.isNotEmpty()) {
            travelDrawn = assigns.flatMap { a ->
                TravelPaint.draw(c, s, a.map, { _, wy -> sx(a.u(wy).toFloat()) }, { wx, _ -> sy(a.v(wx).toFloat()) }, canvasR, compact = false, labels = false)
            }
            return
        }
        val w = model.maps[shown]?.world ?: return
        travelDrawn = TravelPaint.draw(c, s, w.continent, { _, wy -> sx(w.nx(wy).toFloat()) }, { wx, _ -> sy(w.ny(wx).toFloat()) },
            canvasR, compact = false, labels = isZoneView())
    }
    /** the travel point under the cursor (within [TRAVEL_HOVER_PX]), while the cursor is on the map and no quest is hovered */
    private fun travelHover(): Pair<Travel.Point, FloatArray>? {
        if (region != MapRoomKeys.Region.MAP || hoverQuest != null) return null
        val cx = sx(curX); val cy = sy(curY)
        return travelDrawn.minByOrNull { (_, xy) -> Math.hypot((xy[0] - cx).toDouble(), (xy[1] - cy).toDouble()) }
            ?.takeIf { (_, xy) -> Math.hypot((xy[0] - cx).toDouble(), (xy[1] - cy).toDouble()) <= TRAVEL_HOVER_PX }
    }
    private fun drawTravelTip(c: Canvas) {
        val (p, xy) = travelHover() ?: return
        val title = p.text.substringBefore(": ")
        val rest = p.text.substringAfter(": ", "")
        val lines = if (rest.isEmpty()) emptyList() else listOf(UiKit.TipLine(rest, Color.WHITE))
        val right = min(artR.right + mapShift(), drawerLeft()) - 8f
        var x = xy[0] + TravelPaint.R + 8f
        if (x + TIP_W > right) x = xy[0] - TravelPaint.R - 8f - TIP_W
        val y = (xy[1] - TravelPaint.R).coerceIn(canvasR.top + 8f, canvasR.bottom - 110f)
        UiKit.tooltip(c, x, y, TIP_W, title, Theme.gold, lines)
    }
    private val TRAVEL_HOVER_PX = 34f

    /** the group on the map shown (b-group-map): each member on its continent's maps, named (the user's pick: names in the room) */
    private fun drawGroup(c: Canvas) {
        val w = model.maps[shown]?.world ?: return
        val world = model.assignsOf(shown).isNotEmpty()
        for (gm in group) {
            if (!world && gm.cont != null && gm.cont != w.continent) continue
            // a world map: the member's continent through its own assignment (b-world-zoom)
            val uv = if (world) gm.cont?.let { model.project(shown, it, gm.wx, gm.wy) } ?: continue
                else floatArrayOf(w.nx(gm.wy).toFloat(), w.ny(gm.wx).toFloat())
            val u = uv[0]; val v = uv[1]
            if (u !in 0f..1f || v !in 0f..1f) continue
            GroupMembers.draw(c, gm, sx(u), sy(v), named = true)
        }
    }

    /** the quests document (its pad carries the guest cells) */
    fun onQuests(q: JSONObject) {
        q.optJSONObject("pad")?.let { padDoc = it; if (holdAsk.padArrived()) askHold() }
        docFocus = q.optInt("focus", 0)
        questsParty = q.optBoolean("party", false)
        if (focusPending?.first == docFocus) focusPending = null      // the game says what the room showed: done
        questLog.clear()
        q.optJSONArray("list")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { questLog.add(it) } }
        // a pending track / untrack the game's document now shows is done
        trackPending.entries.removeAll { (id, p) -> logEntry(id)?.let { docTracked(it) == p.tracked } ?: true }
        if (region == MapRoomKeys.Region.LIST && selQuest != 0) {
            val i = listRows().indexOfFirst { it is MapQuestBook.Row.Quest && it.id == selQuest }
            if (i >= 0) listIndex = i
        }
        if (isShown) invalidate()
    }

    fun onMapExpl(d: JSONObject) { model.onMapExpl(d); keep("mapexpl", d); if (isShown) invalidate() }
    /** Map.lua's maphl: where the game puts each zone's highlight picture on the world map / a continent */
    fun onMapHl(d: JSONObject) { model.onMapHl(d); keep("maphl", d); nearFor = -1; if (isShown) invalidate() }

    // the game sends exploration / highlight boxes once a login and the zones' quests on changes: an app started later (an install, a
    // restart) would show every zone unexplored. So the last of each is kept on the device and read back as the room loads; a live
    // document always wins. An update (all = false) is merged into the kept whole set, so the kept file stays whole.
    private val liveDocs = HashSet<String>()
    private fun keepFile(type: String) = File(context.filesDir, "maproom_$type.json")
    private fun keep(type: String, d: JSONObject) {
        // an exploration UPDATE (one zone) is merged into the kept whole set: read from the file first when the room has not yet
        // (an update can come before the kept set is read back), and a whole sweep becomes the set later updates merge into; only
        // a whole sweep counts as live (an update alone must not stop the kept set from being shown)
        val whole = when {
            type != "mapexpl" -> { liveDocs.add(type); d }
            d.optBoolean("all", false) -> { liveDocs.add(type); keptExpl = JSONObject(d.toString()); d }
            else -> mergeExpl(d)
        }
        val text = whole.toString()
        io.execute { try { keepFile(type).writeText(text) } catch (t: Throwable) { } }
    }
    private var keptExpl: JSONObject? = null
    private fun readKept(type: String): JSONObject? =
        try { keepFile(type).takeIf { it.exists() }?.readText()?.let { JSONObject(it) } } catch (t: Throwable) { null }
    private fun mergeExpl(upd: JSONObject): JSONObject {
        val base = keptExpl ?: readKept("mapexpl") ?: JSONObject().put("type", "mapexpl").put("all", true).put("maps", JSONObject())
        val maps = base.optJSONObject("maps") ?: JSONObject().also { base.put("maps", it) }
        upd.optJSONObject("maps")?.let { m -> val k = m.keys(); while (k.hasNext()) { val id = k.next(); maps.put(id, m.opt(id)) } }
        base.put("all", true)
        keptExpl = base
        return base
    }
    /** read back what was kept (on the art loader's thread); applied on the UI thread unless a live document came first */
    private fun restoreKept() {
        for (type in listOf("mapexpl", "maphl", "mapquests")) {
            val d = readKept(type) ?: continue
            post {
                if (type in liveDocs) return@post
                when (type) {
                    // an update that came first was merged into the kept set already (mergeExpl): show that, it is the newer
                    "mapexpl" -> { val k = keptExpl ?: d.also { keptExpl = it }; model.onMapExpl(k) }
                    "maphl" -> { model.onMapHl(d); nearFor = -1 }
                    "mapquests" -> applyMapQuests(d)
                }
                if (isShown) invalidate()
            }
        }
    }

    /** Map.lua's "mapquests": the quest markers of EVERY zone (the user after b101: "I dont really like that I cant see the quests on
     *  the map"; the map document has only the map the player stands on) */
    fun onMapQuests(d: JSONObject) { keep("mapquests", d); applyMapQuests(d) }
    private fun applyMapQuests(d: JSONObject) {
        zoneQuests.clear()
        d.optJSONObject("maps")?.let { m ->
            val keys = m.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val id = k.toIntOrNull() ?: continue
                val a = m.optJSONArray(k) ?: continue
                zoneQuests[id] = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { r ->
                    val done = r.optInt("d", 0) == 1
                    var x = r.optDouble(if (done && r.has("tx")) "tx" else "x", Double.NaN).toFloat()
                    var y = r.optDouble(if (done && r.has("ty")) "ty" else "y", Double.NaN).toFloat()
                    if (x.isNaN() || y.isNaN()) { x = r.optDouble("x", Double.NaN).toFloat(); y = r.optDouble("y", Double.NaN).toFloat() }
                    if (x.isNaN() || y.isNaN()) null else Mark(r.optInt("q", 0), x, y, done, r.optString("t", "").ifEmpty { null })
                } }.filter { it.id != 0 }
            }
        }
        if (isShown) { updateHover(prefer = hoverQuest); invalidate() }
    }
    private class Mark(val id: Int, val x: Float, val y: Float, val done: Boolean, val title: String? = null)

    /**
     * Where the game's own zone maps show quest [qid] (Map.lua's mapquests: every zone's GetQuestsOnMap, kept across restarts; a ready
     * quest at its turn-in), in WORLD yards, [continent, wx, wy] each: Home's minimap arrow for a quest in another zone (the user,
     * 2026-10-07: "I am in stormwind and I dont have a marker for quests in elwyn forest").
     */
    fun questWorldSpots(qid: Int): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for ((map, ms) in zoneQuests) for (mk in ms) if (mk.id == qid) {
            val w = model.world(map) ?: continue
            out.add(doubleArrayOf(w.continent.toDouble(), w.wx(mk.y.toDouble()), w.wy(mk.x.toDouble())))
        }
        return out
    }
    /** a quest's spots on the zone maps: [map, x, y, continent, wx, wy] each (Polestar's goal: b-route-polestar) */
    fun questZoneSpots(qid: Int): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for ((map, ms) in zoneQuests) for (mk in ms) if (mk.id == qid) {
            val w = model.world(map) ?: continue
            out.add(doubleArrayOf(map.toDouble(), mk.x.toDouble(), mk.y.toDouble(), w.continent.toDouble(), w.wx(mk.y.toDouble()), w.wy(mk.x.toDouble())))
        }
        return out
    }
    private val zoneQuests = HashMap<Int, List<Mark>>()
    fun onQdbPins(d: JSONObject) { QdbPins.pins(d)?.let { qdbPins = it; if (isShown) invalidate() } }

    /** the minimap probe's options to the add-on (Map.lua mp1: the pin lane's op digit 2, then the bits); Home's map keeps them */
    fun sendProbeOptions(bits: Int): Boolean = TapBridge.tapsSpaced(context, padDoc, MapRoomPad.optCells(bits), MapRoomPad.GAP_MS)
    fun onQdbNodes(d: JSONObject) { QdbPins.nodes(d)?.let { qdbNodes = it; if (isShown) invalidate() } }

    var onFocusQuest: ((Int) -> Unit)? = null
    var onUnfocusQuest: ((Int) -> Unit)? = null
    var onUntrackQuest: ((Int) -> Unit)? = null
    var onLeave: (() -> Unit)? = null

    // ---------------------------------------------------------------- what is shown
    private var shown = 0
    private val history = WorldMapModel.History()
    private var cursor: Int? = null              // a continent's / the world's zone under the map cursor
    // THE CURSOR (MapRoomKeys): on the MAP (the game's free cursor: a spot of the shown map, 0..1, and the zone / capital / quest
    // marker under it) or on the quest LIST (selQuest)
    private var region = MapRoomKeys.Region.MAP
    private var hoverQuest: Int? = null
    private var hoverCity: Int? = null
    private var hoverNear: Int? = null           // a neighbouring zone under the cursor in a zone's art (the user after b109)
    private var zoom = 1f
    private var panX = 0.5f; private var panY = 0.5f
    private var panelOn = false
    private var drawerFrom = 0f
    private var drawerTo = 0f
    private var drawerAt = 0L
    private var shiftFrom = 0f
    private var shiftTo = 0f
    private var shiftAt = 0L

    /** how far the drawer is out, 0..1, eased (the bar's curve) */
    private fun drawerOut(): Float {
        val t = ((System.currentTimeMillis() - drawerAt) / DRAWER_MS.toFloat()).coerceIn(0f, 1f)
        return drawerFrom + (drawerTo - drawerFrom) * ease(t)
    }
    private fun drawerLeft(): Float = width - PANEL_W * drawerOut()

    /** the map's shift left (px, eased): only while the lit quest would sit under the drawer, and back when it closes */
    private fun mapShift(): Float {
        val t = ((System.currentTimeMillis() - shiftAt) / SHIFT_MS.toFloat()).coerceIn(0f, 1f)
        return shiftFrom + (shiftTo - shiftFrom) * ease(t)
    }
    private fun aimShift() {
        // inside a capital the map centres in the space left of the drawer (eased as the drawer moves); anywhere, the map's cursor
        // (the list's lit quest while the list has the cursor) is kept clear of the drawer by the EDGE PAN (MapRoomKeys.edgePan; the
        // user after b108: "I dont like that the map slides back in as I move back left, I would rather it slide left when I hit the
        // left side of the map"): the map stays put while it moves inside, slides left as it pushes the drawer's edge, back as it
        // pushes the left edge
        val base = 0f          // (a capital no longer centres left of the drawer: the layered view never moves by itself)
        val keepX = if (region == MapRoomKeys.Region.MAP) curX else litQuest()?.let { lit -> marks().firstOrNull { it.id == lit }?.x }
        push = when {
            drawerTo < 0.5f -> 0f
            keepX == null -> push
            else -> MapRoomKeys.edgePan(push, sx0(keepX) + base + push, canvasR.left + SHIFT_MARGIN, width - PANEL_W - SHIFT_MARGIN, PANEL_W)
        }
        val target = base + push
        if (target != shiftTo) {
            // the cursor's own pushes carry the map with it at once; a new base (the drawer, a capital) and the list's jumps ease
            if (base == lastBase && region == MapRoomKeys.Region.MAP) { shiftFrom = target; shiftTo = target; shiftAt = 0L }
            else { shiftFrom = mapShift(); shiftTo = target; shiftAt = System.currentTimeMillis() }
        }
        lastBase = base
    }
    private var push = 0f          // the edge pan's slide (px, -PANEL_W..0): kept until the cursor pushes the other edge
    private var lastBase = 0f
    private fun animating(): Boolean = System.currentTimeMillis() - drawerAt < DRAWER_MS || System.currentTimeMillis() - shiftAt < SHIFT_MS

    /** the drawer (the quest side bar) is on every map but the world (Azeroth); out while the player keeps it out (X) */
    private fun sideBarHere(id: Int = shown) = (model.maps[id]?.type ?: 3) > 1
    private fun panelWanted(id: Int = shown) = sideBarHere(id) && sideBarOn()

    /** slide the drawer out / in ([now] = no slide, as the room opens) */
    private fun setDrawer(on: Boolean, now: Boolean = false) {
        if (on == panelOn && !now) return
        drawerFrom = if (now) (if (on) 1f else 0f) else drawerOut(); drawerTo = if (on) 1f else 0f
        drawerAt = if (now) 0L else System.currentTimeMillis()
        panelOn = on
        if (!on) region = MapRoomKeys.Region.MAP
        clampPan(); invalidate()
    }
    private var selQuest = 0
    private var note: String? = null
    private var noteAt = 0L

    private fun startMap(): Int = when {
        playerMap != 0 && model.maps.containsKey(playerMap) -> playerMap
        model.maps.containsKey(947) -> 947
        else -> model.maps.keys.firstOrNull() ?: 0
    }

    private fun isZoneView(id: Int = shown) = (model.maps[id]?.type ?: 3) >= 3

    /**
     * Glide to map [id] (A on a zone or a capital, a crumb, More actions, up a level, the list's quest): the view eases until the map
     * fits it, its art fading in on the way; nothing is handed over. The cursor goes onto it when it is elsewhere (the player's arrow
     * when that is on it). [fromList] = the list's cursor took the map to its quest's zone: the list keeps still (UI Art: never re-sort
     * while the cursor is in it) and the quest is lit once there.
     */
    private fun goTo(id: Int, fromList: Boolean = false) {
        if (id == 0 || !model.maps.containsKey(id)) return
        menuOpen = false; crumbMenu = null; optQuest = 0; confirmId = 0
        if (!fromList) region = MapRoomKeys.Region.MAP
        val p = placeOf(id)
        if (p == null || p.width() <= 0f) { jumpTo(id); placeCursorStart(); syncB(); invalidate(); return }    // no world over both
        hereTarget = id
        if (!p.contains(ccx, ccy)) { val me = playerOn(p); ccx = me?.get(0) ?: p.centerX(); ccy = me?.get(1) ?: p.centerY() }
        fly(1f / max(p.width(), p.height()), p.centerX(), p.centerY()) {
            if (fromList && questMarks().any { it.first == selQuest }) select(selQuest) else updateHover(prefer = hoverQuest)
            syncB(); invalidate()
        }
        syncB()
        invalidate()
    }
    /** the player's spot on the canvas when it lies in [p], else null */
    private fun playerOn(p: RectF): FloatArray? {
        if (px.isNaN() || playerMap == 0) return null
        val q = model.placeOn(playerMap, canvas) ?: return null
        val u = q[0] + px * (q[2] - q[0]); val v = q[1] + py * (q[3] - q[1])
        return if (p.contains(u, v)) floatArrayOf(u, v) else null
    }
    /** a spot ([x], [y]) of map [map] in [shown]'s 0..1 (through the canvas: a neighbour's spot too), or null */
    private fun toShown(map: Int, x: Float, y: Float): FloatArray? {
        if (map == shown) return floatArrayOf(x, y)
        val q = model.placeOn(map, canvas) ?: return model.carryUp(map, x, y, shown)
        return floatArrayOf((q[0] + x * (q[2] - q[0]) - pl.left) / pl.width(), (q[1] + y * (q[3] - q[1]) - pl.top) / pl.height())
    }

    // ---------------------------------------------------------------- THE GAME'S MAP CURSOR (MapRoomKeys)
    // The user after b107: "lets bring back the map cursor instead. but I want it to look just like the one that wow forver does, with
    // the golden lines and cross hair". Forever's SoftCursor on its map: the left stick moves it freely (faster the further it is
    // tilted), whatever is under it is hovered (a quest marker, a capital, a zone on a continent / the world), A / X change map; it
    // never snaps. The user's pick: it slows down over a marker, never jumps.

    /** the quest markers the cursor can rest on (while Show: has them): id, x, y */
    private fun questMarks(): List<Triple<Int, Float, Float>> =
        if (!on("quests")) emptyList()
        else layerMarks().filter { it.a >= 0.5f }.map { Triple(it.m.id, (it.u - pl.left) / pl.width(), (it.v - pl.top) / pl.height()) }

    /** where the things sit (to put the cursor on one): quest markers and the zone's capitals (a capital's key is minus its map id) in
     *  a zone, zones on a continent / the world */
    private fun things(): List<Triple<Int, Float, Float>> =
        if (isZoneView()) questMarks() + model.citiesIn(shown).map { (city, r) -> Triple(-city, (r[0] + r[1]) / 2f, (r[2] + r[3]) / 2f) }
        else model.selectables(shown)

    /** what the cursor rests on: a quest marker, a map to open (a capital or a neighbouring zone: minus its id), a continent's zone */
    private fun currentThing(): Int? = if (isZoneView()) (hoverQuest ?: (hoverCity ?: hoverNear)?.let { -it }) else cursor

    /** the cursor onto [id] (going up: the map left; back from the list: its quest), which it then hovers */
    private fun select(id: Int?) {
        val at = id?.let { t -> things().firstOrNull { it.first == t } }
        if (at != null) { curX = at.second; curY = at.third }
        updateHover(prefer = id)
    }

    /** the cursor where a map opens: on the player's arrow (on this map, or carried up to it), else the map's middle */
    private fun placeCursorStart() {
        val me = if (px.isNaN() || playerMap == 0) null else toShown(playerMap, px, py)
        curX = (me?.get(0) ?: 0.5f).coerceIn(0f, 1f); curY = (me?.get(1) ?: 0.5f).coerceIn(0f, 1f)
        follow()
        updateHover()
    }

    /**
     * What is under the cursor: in a zone the quest marker within its own drawn size (the game: a pin within 12 px of its cursor;
     * [prefer] wins among overlapping ones), else the capital whose area holds it; on a continent / the world the zone under it (the
     * game's GetMapInfoAtPosition: its highlight and its label).
     */
    private fun updateHover(prefer: Int? = null) {
        // a room not laid out yet (its first open) has no screen to measure on: the first draw finds the hover
        if (width <= 0) { hoverQuest = null; hoverCity = null; hoverNear = null; cursor = null; hoverDirty = true; return }
        hoverDirty = false
        layout()
        // a quest disc anywhere it shows (a neighbour's too, the layers'), then what the map has under the cursor
        val cx = sx(curX); val cy = sy(curY)
        val pts = questMarks().map { Triple(it.first, sx(it.second), sy(it.third)) }
        val q = prefer?.takeIf { p -> pts.any { it.first == p && Math.hypot((it.second - cx).toDouble(), (it.third - cy).toDouble()) <= HOVER_PX } }
            ?: MapRoomKeys.underPx(pts, cx, cy, HOVER_PX)
        hoverQuest = q
        if (!isZoneView()) { hoverCity = null; hoverNear = null; cursor = if (q != null) null else childUnder(); return }
        cursor = null
        hoverCity = if (q != null) null else model.citiesIn(shown).firstOrNull { (_, r) -> curX in r[0]..r[1] && curY in r[2]..r[3] }?.first
        // a neighbouring zone where the game's own shapes put one (never inside this zone's own shape)
        hoverNear = if (q != null || hoverCity != null) null else model.neighbourAt(curX, curY, nearbys(), nearOwn, ::hlLit)
    }

    // ---------------------------------------------------------------- THE NEIGHBOURS (the user after b109: "when looking at a zone,
    // I should be able to select nearby zones to open that map"): the game's own zone shapes (each zone's highlight picture on the
    // continent, maphl) carried into the shown zone's art through the world rects (WorldMapModel.neighbours), so what can be picked is
    // what the art paints around the edges
    private var nearFor = -1
    private var nearList: List<WorldMapModel.Shape> = emptyList()
    private var nearOwn: WorldMapModel.Shape? = null
    private fun nearbys(): List<WorldMapModel.Shape> {
        if (nearFor != shown) {
            nearFor = shown
            nearList = model.neighbours(shown); nearOwn = model.shapeIn(shown, shown)
            for (n in nearList + listOfNotNull(nearOwn)) bmp("hl/${n.fid}")         // their pictures load now (the first hover finds them)
        }
        return nearList
    }
    private val hlPeaks = HashMap<Int, IntArray>()    // a picture's brightest channel and brightest R+G+B
    private fun hlPeak(fid: Int, b: Bitmap): IntArray = hlPeaks.getOrPut(fid) {
        var m = 1; var sum = 1
        for (y in 0 until b.height step 2) for (x in 0 until b.width step 2) {
            val px = b.getPixel(x, y); val r = Color.red(px); val g = Color.green(px); val bl = Color.blue(px)
            m = max(m, max(r, max(g, bl))); sum = max(sum, r + g + bl)
        }
        intArrayOf(m, sum)
    }
    /** a zone picture lit at ([u], [v]) of its own 0..1: brighter than half its brightest (the game ADDS them: black is none) */
    private fun hlLit(fid: Int, u: Float, v: Float): Boolean {
        val b = bmp("hl/$fid") ?: return false
        val px = b.getPixel((u * b.width).toInt().coerceIn(0, b.width - 1), (v * b.height).toInt().coerceIn(0, b.height - 1))
        return max(Color.red(px), max(Color.green(px), Color.blue(px))) * 2 >= hlPeak(fid, b)[0]
    }

    private var hoverDirty = false

    /** the map under the cursor on a continent (a zone: its outline, else the deepest) or the world (the continent's box) */
    private fun childUnder(): Int? {
        if (model.assignsOf(shown).isNotEmpty()) return contAt(ccx, ccy)?.id
        val zs = layers().filter { it.kind == WorldMapModel.ZONE && (it.parent == null || it.parent == shown) }
        return zoneUnder(zs, ccx, ccy, null)?.id ?: model.childAtAny(shown, curX, curY)?.takeIf { model.maps[it]?.type == 3 }
    }

    /** a zoomed-in map follows the cursor (it never leaves the view: the game's edge pan) */
    private fun follow() {
        if (zoom <= 1.01f) return
        val hw = 0.5f / zoom * 0.85f; val hh = 0.5f / zoom * 0.85f
        if (ccx < panX - hw) panX = ccx + hw
        if (ccx > panX + hw) panX = ccx - hw
        if (ccy < panY - hh) panY = ccy + hh
        if (ccy > panY + hh) panY = ccy - hh
        clampPan()
    }

    private fun keyCtx(): MapRoomKeys.Ctx {
        val rows = if (panelOn) listRows() else emptyList()
        val header = (rows.getOrNull(listIndex) as? MapQuestBook.Row.Header)?.zone?.takeIf { region == MapRoomKeys.Region.LIST }
        return MapRoomKeys.Ctx(region, isZoneView() || hoverQuest != null, cursor, hoverQuest, selQuest.takeIf { it != 0 },
            hasParent = model.parentOf(shown) != null, listHasQuests = rows.isNotEmpty(),
            listHeader = header, hoverCity = hoverCity ?: hoverNear, hoverFocused = hoverQuest != null && hoverQuest == focusQid,
            hoverTracked = hoverQuest?.let { q -> logEntry(q)?.let { trackedQ(it) } } ?: false, sideBarOn = sideBarOn(), sideBarHere = sideBarHere())
    }

    // ---------------------------------------------------------------- THE QUEST BOOK (the user after b104: every quest, by zone)
    private val book = MapQuestBook()
    private var listIndex = 0
    private var listScroll = 0f
    private fun entries() = questLog.map { MapQuestBook.Entry(it.optInt("id", 0), it.optString("zone", "")) }
    private fun listRows() = book.rows(entries())

    /** the shown map's headers first and open (its quests' headers, then its own name); the map's side only */
    private fun refreshTop() {
        book.topZones = MapQuestBook.topZonesFor(model.name(shown), marks().map { it.id }, entries())
        listIndex = 0; listScroll = 0f
        if (region == MapRoomKeys.Region.LIST) setListCursor(follow = false)
    }

    /** the list cursor's row -> selQuest; on a quest row, after the user's quarter second the map follows it to its zone (UI Art:
     *  scrolling past rows must not flicker the map) */
    private fun setListCursor(follow: Boolean = true) {
        val rows = listRows()
        listIndex = listIndex.coerceIn(0, max(0, rows.size - 1))
        selQuest = (rows.getOrNull(listIndex) as? MapQuestBook.Row.Quest)?.id ?: 0
        removeCallbacks(followRow)
        if (follow && selQuest != 0) postDelayed(followRow, UiKit.TipFade.DELAY)
    }
    private val followRow = Runnable {
        if (region != MapRoomKeys.Region.LIST || selQuest == 0 || !isShown) return@Runnable
        val e = logEntry(selQuest) ?: return@Runnable
        val withMarks = HashMap<Int, List<Int>>()
        for ((m, ms) in zoneQuests) withMarks[m] = ms.map { it.id }
        if (playerMap != 0) mapDoc?.optJSONArray("quests")?.let { a ->
            withMarks[playerMap] = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optInt("questID", 0)?.takeIf { q -> q != 0 } }
        }
        val names = model.maps.mapValues { it.value.name }
        val where = MapQuestBook.whereIs(selQuest, e.optString("zone", ""), withMarks, names) { m -> isZoneView(m) }
        if (where != null && where != shown) goTo(where, fromList = true)
        invalidate()
    }

    private fun moveList(d: Int) {
        if (listRows().isEmpty()) return
        listIndex += d
        setListCursor()
    }

    // ---------------------------------------------------------------- the quest actions (A: track + focus; X: track / untrack)
    private fun logEntry(id: Int): JSONObject? = questLog.firstOrNull { it.optInt("id", 0) == id }
    private fun slotOf(q: JSONObject) = if (q.has("slot") && !q.isNull("slot")) q.optInt("slot", -1) else -1
    private fun docTracked(q: JSONObject) = q.has("watch") && !q.isNull("watch")
    /** tracked as SHOWN: the room's own pending change at once, until the game's document says so or the wait runs out */
    private fun trackedQ(q: JSONObject): Boolean {
        val p = trackPending[q.optInt("id", 0)]
        return if (p != null && System.currentTimeMillis() - p.at < OPTIMISTIC_MS) p.tracked else docTracked(q)
    }

    /** the game's answer to a quest tap (questack): a refusal takes the room's pending change back at once, with the game's reason */
    fun onQuestAck(msg: JSONObject) {
        val op = msg.optString("op")
        val ok = msg.optBoolean("ok", true)
        val id = msg.optInt("id", 0)
        if (op == "arm" || op == "share" || op == "abandon") {
            // only the answer this room waits for acts (a replayed one from the backlog, or the Quests room's, never does), and it
            // must name the quest this room meant (the Quests room's rule)
            if (pendingOp != op) return
            pendingOp = null
            val meant = pendingId; pendingId = 0
            if (meant != 0 && id != 0 && id != meant) { say("Refused: the game answered for another quest"); invalidate(); return }
            when {
                op == "arm" && ok -> {
                    // the add-on has named the quest it would abandon: now, and only now, the question (with ITS title)
                    confirmId = if (id != 0) id else meant
                    confirmTitle = msg.optString("title", "").ifEmpty { logEntry(confirmId)?.optString("title", "") ?: "" }.ifEmpty { "this quest" }
                    confirmAt = System.currentTimeMillis()
                    postDelayed({ invalidate() }, CONFIRM_MS + 50L)
                }
                ok -> say(if (op == "share") "Shared" else "Abandoned")
                else -> say(msg.optString("why", "").ifEmpty { "Refused" })
            }
            syncB(); invalidate(); return
        }
        if (ok) return
        when (op) {
            "watch", "unwatch" -> trackPending.remove(id)
            "track", "untrack" -> if (focusPending?.first == id || focusPending?.first == 0 || id == 0) focusPending = null
            else -> return
        }
        msg.optString("why", "").ifEmpty { null }?.let { say(it) }
        invalidate()
    }
    private val pendingExpire = Runnable { invalidate() }

    /** the quest actions [acts] for quest [id], as the Quests room sends them (pick + act each); spaced when a pick repeats */
    private fun sendQuestActs(id: Int, acts: List<QuestPad.Act>): Boolean {
        val q = logEntry(id) ?: run { say("This quest is not in your quest log"); return false }
        val slot = slotOf(q)
        if (slot < 0) { say("This quest has no place on the game's tap pad yet"); return false }
        val cells = ArrayList<Pair<String, Int>>()
        for (a in acts) cells += QuestPad.cells(padDoc, a, slot) ?: run { say("The game has not published its tap pad yet"); return false }
        val ok = if (acts.size > 1) TapBridge.tapsSpaced(context, padDoc, cells, QUEST_GAP_MS) { why -> post { say(why) } }
                 else TapBridge.taps(context, padDoc, cells) { why -> post { say(why) } }
        if (!ok) say("Waiting for the game")
        return ok
    }

    private fun trackFocus(id: Int) {
        val q = logEntry(id) ?: run { say("This quest is not in your quest log"); return }
        val acts = MapRoomKeys.trackFocusActs(trackedQ(q), id == focusQid)
        if (acts.isEmpty()) { say("Already tracked and focused"); return }
        if (sendQuestActs(id, acts)) {
            val now = System.currentTimeMillis()
            if (QuestPad.Act.WATCH in acts) trackPending[id] = Pending(true, now)
            if (QuestPad.Act.FOCUS in acts) focusPending = id to now
            expireLater()
        }
    }

    private fun toggleTrack(id: Int) {
        val q = logEntry(id) ?: run { say("This quest is not in your quest log"); return }
        val now = !trackedQ(q)
        if (sendQuestActs(id, listOf(MapRoomKeys.toggleTrackAct(!now)))) { trackPending[id] = Pending(now, System.currentTimeMillis()); expireLater() }
    }

    // ---------------------------------------------------------------- A QUEST'S OPTIONS (Y in the list: the game's quest-log menu)
    // The user after b108: "When browsing the quests pane, Y should have a more options on the quest like with wow to share and abandon
    // quests." The game's QuestMapLogTitleButton_CreateContextMenu (MapRoomKeys.questOptions), its actions on the Quests room's own
    // paths (QuestPad: SHARE; ARM then CONFIRM; the add-on answers questack): Abandon is two steps by design, the question names the
    // quest the ADD-ON armed, and the confirm drops whatever is armed within its 10 s.
    private var optQuest = 0                       // the quest whose options are open (0: none)
    private var optSel = 0
    private val optHits = ArrayList<Pair<RectF, Int>>()
    private var optAnchor: RectF? = null           // its row in the list (drawQuestRow): the menu hangs under it, as the game's
    private var questsParty = false                // the quests document: the player is in a party (Share's rule)
    private var pendingOp: String? = null          // the share / arm / abandon this room sent, until the game answers
    private var pendingId = 0
    private var pendingAt = 0L
    private var confirmId = 0                      // Abandon's question is up for this quest (the add-on armed it)
    private var confirmTitle = ""
    private var confirmAt = 0L
    private val confirmHits = ArrayList<Pair<RectF, Boolean>>()

    private fun opts(id: Int): List<MapRoomKeys.Opt> {
        val q = logEntry(id) ?: return emptyList()
        return MapRoomKeys.questOptions(trackedQ(q), id == focusQid, q.optBoolean("canAbandon", true))
    }
    private fun optLabel(o: MapRoomKeys.Opt): String = when (o) {
        MapRoomKeys.Opt.TRACK -> "Track Quest"
        MapRoomKeys.Opt.UNTRACK -> "Untrack Quest"
        MapRoomKeys.Opt.FOCUS -> "Focus Quest"
        MapRoomKeys.Opt.STOP_FOCUS -> "Stop Focusing"
        MapRoomKeys.Opt.SHARE -> "Share Quest"
        MapRoomKeys.Opt.ABANDON -> "Abandon Quest"
    }
    /** why Share Quest cannot be used now, in the game's words (as the Quests room's shareReason), or null when it can */
    private fun shareReason(q: JSONObject): String? = when {
        !questsParty -> "You are not in a party."          // ERR_QUEST_PUSH_NOT_IN_PARTY_S
        !q.optBoolean("push") -> "That quest cannot be shared."                // ERR_QUEST_PUSH_NOT_ALLOWED_S
        else -> null
    }
    private fun openOptions(id: Int) { if (logEntry(id) != null) { optQuest = id; optSel = 0 } }
    /** a menu or a question is up: the stick and the map's keys wait */
    private fun modal() = menuOpen || crumbMenu != null || optQuest != 0 || confirmId != 0

    private fun runOpt(o: MapRoomKeys.Opt) {
        val id = optQuest
        val q = logEntry(id) ?: run { optQuest = 0; return }
        if (o == MapRoomKeys.Opt.SHARE) shareReason(q)?.let { say(it); return }        // grey: the game's reason, the menu stays
        if (pendingBusy()) { say("Waiting for the game to answer"); return }
        val now = System.currentTimeMillis()
        when (o) {
            MapRoomKeys.Opt.TRACK, MapRoomKeys.Opt.UNTRACK -> toggleTrack(id)
            MapRoomKeys.Opt.FOCUS -> if (sendQuestActs(id, listOf(QuestPad.Act.FOCUS))) { focusPending = id to now; expireLater() }
            MapRoomKeys.Opt.STOP_FOCUS -> if (sendQuestActs(id, listOf(QuestPad.Act.UNFOCUS))) { focusPending = 0 to now; expireLater() }
            MapRoomKeys.Opt.SHARE -> sendOp(QuestPad.Act.SHARE, id)
            MapRoomKeys.Opt.ABANDON -> sendOp(QuestPad.Act.ARM, id)
        }
        optQuest = 0
    }
    private fun pendingBusy() = pendingOp != null && System.currentTimeMillis() - pendingAt < PENDING_MS
    private fun sendOp(act: QuestPad.Act, id: Int) {
        if (sendQuestActs(id, listOf(act))) { pendingOp = act.reply; pendingId = id; pendingAt = System.currentTimeMillis() }
    }
    /** Abandon's yes: the add-on drops whatever it armed (no pick), within its 10 s */
    private fun confirmAbandon() {
        val id = confirmId; confirmId = 0
        if (id == 0) return
        if (System.currentTimeMillis() - confirmAt > CONFIRM_MS) { say("Too late: choose Abandon Quest again"); return }
        val cells = QuestPad.cells(padDoc, QuestPad.Act.CONFIRM, 0) ?: run { say("The game has not published its tap pad yet"); return }
        if (TapBridge.taps(context, padDoc, cells) { why -> post { pendingOp = null; say(why) } }) {
            pendingOp = QuestPad.Act.CONFIRM.reply; pendingId = id; pendingAt = System.currentTimeMillis()
        } else say("Waiting for the game")
    }

    /** a pending change the game never confirmed falls back to the game's state when the wait runs out */
    private fun expireLater() { invalidate(); removeCallbacks(pendingExpire); postDelayed(pendingExpire, OPTIMISTIC_MS + 50L) }


    private fun say(t: String) { note = t; noteAt = System.currentTimeMillis(); invalidate() }

    // ---------------------------------------------------------------- the left stick: the game's free map cursor; a row a push in the list
    private var reader: PadInput? = null
    private var stickX = 0f
    private var stickY = 0f
    private var cursorRunning = false
    private var cursorFrameAt = 0L
    private val cursorFrame = Runnable { moveCursor() }

    /** one frame of the cursor's move while the stick is pushed (the game's SoftCursor OnUpdate: its spot + speed x elapsed) */
    private fun moveCursor() {
        if (!isShown || region != MapRoomKeys.Region.MAP || modal() || !MapRoomKeys.stickMoves(stickX, stickY)) {
            cursorRunning = false; return
        }
        val now = SystemClock.uptimeMillis()
        val dt = if (cursorFrameAt == 0L) 1f / 60f else ((now - cursorFrameAt) / 1000f).coerceIn(0f, 0.05f)
        cursorFrameAt = now
        if (artR.width() > 0f) {
            val (dx, dy) = MapRoomKeys.cursorStep(stickX, stickY, dt, CURSOR_PX_S, overMarker = hoverQuest != null)
            ccx = (ccx + dx / (artR.width() * zoom)).coerceIn(0f, 1f)        // in the canvas's frame: across every layer
            ccy = (ccy + dy / (artR.height() * zoom)).coerceIn(0f, 1f)
            keepOffLegend()
            follow()
            updateHover(prefer = hoverQuest)
        }
        invalidate()
        postOnAnimation(cursorFrame)
    }
    /** the legend box overlaps the map's bottom edge: the cursor stops just above it where it spans (on the Thor it hid under it) */
    private fun keepOffLegend() {
        val lb = legendBox
        if (lb.isEmpty) return
        val half = RETICLE_PX / 2f
        if (sx(curX) in (lb.left - half)..(lb.right + half) && sy(curY) > lb.top - half) ccy = max(0f, uyc(lb.top - half))
    }
    private val legendBox = RectF()

    // ---- THE RIGHT STICK'S ZOOM: while it is pushed up or down, the zone map zooms smoothly (exponential: the same feel at every
    // zoom; ZOOM_STICK_RATE per second at full tilt), in toward the cursor, out about the view; LB / RB keep their 1.6x steps
    private var zoomY = 0f
    private val ZOOM_STICK_RATE = 1.6f        // e-folds a second at full tilt: 1x to the 3.2x maximum in about 0.7 s
    private var zoomRunning = false
    private var zoomFrameAt = 0L
    private val zoomFrame = Runnable { stepZoom() }
    private fun startZoom() {
        if (zoomRunning) return
        zoomRunning = true; zoomFrameAt = 0L
        postOnAnimation(zoomFrame)
    }
    private fun stepZoom() {
        if (!isShown || modal() || abs(zoomY) <= MapRoomKeys.STICK_DEAD) { zoomRunning = false; return }
        val now = SystemClock.uptimeMillis()
        val dt = if (zoomFrameAt == 0L) 1f / 60f else ((now - zoomFrameAt) / 1000f).coerceIn(0f, 0.05f)
        zoomFrameAt = now
        // past the dead zone, scaled 0..1; up (y < 0) zooms in
        val tilt = (abs(zoomY) - MapRoomKeys.STICK_DEAD) / (1f - MapRoomKeys.STICK_DEAD)
        val was = zoom
        flyZ = Float.NaN; flyDone = null; hereTarget = 0
        zoom *= exp(-sign(zoomY) * tilt * ZOOM_STICK_RATE * dt)
        if (zoom > was) { val k = min(1f, dt * 3f); panX += (ccx - panX) * k; panY += (ccy - panY) * k }   // in: toward the cursor
        afterZoom()
        postOnAnimation(zoomFrame)
    }

    private fun startCursor() {
        if (cursorRunning) return
        cursorRunning = true; cursorFrameAt = 0L
        postOnAnimation(cursorFrame)
    }

    // in the quest list the stick steps a row a push, then the D-pad's own hold-repeat while it stays pushed (MapRoomKeys.StickSteps)
    private val steps = MapRoomKeys.StickSteps()
    private val stickRepeat = Runnable { stickStep(); postStickRepeat(DirectPadKeys.REPEAT_MS) }
    private fun postStickRepeat(ms: Long) { removeCallbacks(stickRepeat); postDelayed(stickRepeat, ms) }
    private fun stickStep() {
        val d = steps.dir ?: return
        if (!isShown || modal() || region != MapRoomKeys.Region.LIST) return
        if (d.second != 0f && d.first == 0f) moveList(if (d.second > 0f) 1 else -1) else if (d.first < 0f) key("PADDLEFT")
        syncB(); invalidate()
    }
    private fun startStick() {
        if (reader != null) return
        reader = PadInput(onPad = { _, _, _, _ -> }, onStatus = { _, _ -> },
            onStickVector = { stick, x, y ->
                // the RIGHT stick zooms (the user, 2026-10-17: "use the right stick to zoom in and out"): up in, down out, by how far
                if (stick == 1) { zoomY = y; if (abs(y) > MapRoomKeys.STICK_DEAD) startZoom(); return@PadInput }
                if (stick != 0) return@PadInput
                stickX = x; stickY = y
                if (region == MapRoomKeys.Region.MAP && MapRoomKeys.stickMoves(x, y)) startCursor()
                if (steps.update(x, y)) { if (region == MapRoomKeys.Region.LIST) { stickStep(); postStickRepeat(DirectPadKeys.REPEAT_FIRST_MS) } }
                else if (!steps.engaged) removeCallbacks(stickRepeat)
            }).also { it.start() }
    }
    private fun stopStick() {
        reader?.stop(); reader = null; steps.update(0f, 0f); stickX = 0f; stickY = 0f
        removeCallbacks(stickRepeat); removeCallbacks(cursorFrame); cursorRunning = false
    }

    /** the room opened (MainActivity): the player's zone, whole, nothing browsed yet */
    fun onShown() {
        history.clear()
        cursor = null; menuOpen = false; crumbMenu = null
        jumpTo(startMap())
        region = MapRoomKeys.Region.MAP
        selQuest = 0
        setDrawer(panelWanted(), now = true)
        shiftFrom = 0f; shiftTo = 0f; shiftAt = 0L; push = 0f
        optQuest = 0; confirmId = 0; pendingOp = null
        placeCursorStart()
        cursorShownAt = System.currentTimeMillis()      // the game's cursor fades in as the map opens (its IntroAnim)
        refreshTop()
        lastPadN = -1; lastMapN = -1; bSaid = "home"; openTapped = false; openPending = false
        invalidate()
    }

    /** the hand-over's take (RoomHandOver MAP_ENTER, every route in; after onShown): the hold, asked again until answered */
    fun touchedIn() {
        holdAsk.shown(); askHold()
        startStick()
    }

    /** the hand-over's let-go (RoomHandOver MAP_LEAVE, every route out but a fight's leave; before onHidden) */
    fun touchedOut() {
        holdAsk.hidden(); removeCallbacks(holdTick)
        if (ownRow()) freeAgain() else guestTap(GuestPad.cells(padDoc, "map", hold = false))
    }

    fun onHidden() {
        held = false
        holdAsk.hidden(); removeCallbacks(holdTick)     // the let-go itself is the hand-over's (touchedOut)
        stopStick()
    }

    // ---------------------------------------------------------------- the quests of the shown map (the panel's list, in log order)
    private fun gameQuestIds(): List<Int> = marks().map { it.id }

    /** the quest markers of the shown map: the player's own map from the live map document, any other from Map.lua's mapquests */
    private fun marks(): List<Mark> = marksOf(shown)
    /** map [id]'s quest markers (its own 0..1) */
    private fun marksOf(id: Int): List<Mark> {
        val m = mapDoc
        if (id == playerMap && m != null) {
            val a = m.optJSONArray("quests") ?: return emptyList()
            val out = ArrayList<Mark>()
            for (i in 0 until a.length()) {
                val q = a.optJSONObject(i) ?: continue
                val done = q.optBoolean("done", false)
                var x = q.optDouble(if (done && q.has("tx")) "tx" else "x", Double.NaN).toFloat()
                var y = q.optDouble(if (done && q.has("ty")) "ty" else "y", Double.NaN).toFloat()
                if (x.isNaN() || y.isNaN()) { x = q.optDouble("x", Double.NaN).toFloat(); y = q.optDouble("y", Double.NaN).toFloat() }
                if (!x.isNaN() && !y.isNaN()) out.add(Mark(q.optInt("questID", 0), x, y, done, q.optString("title", "").ifEmpty { null }))
            }
            return out.filter { it.id != 0 }
        }
        return zoneQuests[id] ?: emptyList()
    }

    // ---------------------------------------------------------------- Show: (the extras off to start; the game's marks on)
    private val prefs = context.getSharedPreferences("maproom", Context.MODE_PRIVATE)
    private class Filter(val key: String, val label: String, val default: Boolean, val ours: Boolean)
    private val filters = listOf(
        Filter("quests", "Quest Objectives", true, false),
        Filter("flight", "Flight Points", true, false),
        Filter("services", "Vendors and trainers", false, true),
        Filter("herb", "Herbs", false, true),
        Filter("ore", "Ore", false, true),
        Filter("chest", "Chests", false, true),
        Filter("rare", "Rares", false, true),
        Filter("guess", "QuestieDB guesses", false, true))
    private fun on(key: String): Boolean = prefs.getBoolean(key, filters.first { it.key == key }.default)
    private fun toggle(key: String) { prefs.edit().putBoolean(key, !on(key)).apply(); invalidate() }
    private var menuOpen = false
    private var menuSel = 0
    private val menuHits = ArrayList<Pair<RectF, Int>>()
    private val menuBox = RectF()

    // ---------------------------------------------------------------- layout
    private val TOP = 70f
    private val BOTTOM = 62f
    private val PANEL_W = 380f
    private val canvasR = RectF()
    private val artR = RectF()
    private val panelR = RectF()
    private val funnelR = RectF()
    private val toggleR = RectF()
    private val crumbHits = ArrayList<Pair<RectF, Int>>()
    private val rowHits = ArrayList<Pair<RectF, Int>>()
    private var crumbMenu: Int? = null          // the crumb whose neighbours' list is open (its map id)
    private val crumbMenuHits = ArrayList<Pair<RectF, Int>>()

    private fun layout() {
        val w = width.toFloat(); val h = height.toFloat()
        // THE DRAWER (the user's pick after b101: "B: the panel slides over the map like a drawer"; "The map looks really bad
        // shrunk down next to the quests bar"): the map keeps its full size and place, the quest panel slides over its right side
        canvasR.set(0f, TOP, w, h - BOTTOM)
        val dl = drawerLeft()
        panelR.set(dl, TOP, dl + PANEL_W, h - BOTTOM)
        val info = model.maps[canvas]
        val aspect = if (info != null && info.h > 0) info.w.toFloat() / info.h else 1.5f
        var aw = canvasR.width(); var ah = aw / aspect
        if (ah > canvasR.height()) { ah = canvasR.height(); aw = ah * aspect }
        artR.set(canvasR.centerX() - aw / 2f, canvasR.centerY() - ah / 2f, canvasR.centerX() + aw / 2f, canvasR.centerY() + ah / 2f)
        // the funnel (Select / View) at the VISIBLE map's top right, just left of the drawer
        funnelR.set(dl - 82f, canvasR.top + 10f, dl - 14f, canvasR.top + 78f)
        // UI Art (the game's World Map): the quest-panel toggle at the MAP's bottom right, inset ~2 px, the game's own pictures
        // (with the drawer out: at the drawer's bottom-left edge)
        val tr = min(artR.right, dl) - 2f
        toggleR.set(tr - TOGGLE_PX, artR.bottom - 2f - TOGGLE_PX, tr, artR.bottom - 2f)
    }

    /**
     * The view inside the canvas (a glide keeps its own way). On the world the bounds close in from the world's frame to the frame of
     * the continent nearest the middle as it fades in (with the zoom: no jump), so where it fills the view the view is on it whole and
     * the canvas becomes the continent's.
     */
    private fun clampPan() {
        if (flying()) return
        val h = 0.5f / zoom
        val k = if (model.assignsOf(canvas).isNotEmpty()) contNear(panX, panY) else null
        val a = k?.a ?: 0f
        val l = (k?.p?.left ?: 0f) * a; val t = (k?.p?.top ?: 0f) * a
        val r = 1f + ((k?.p?.right ?: 1f) - 1f) * a; val bt = 1f + ((k?.p?.bottom ?: 1f) - 1f) * a
        panX = if (2f * h >= r - l) (l + r) / 2f else panX.coerceIn(l + h, r - h)
        panY = if (2f * h >= bt - t) (t + bt) / 2f else panY.coerceIn(t + h, bt - h)
    }
    /** the continent on the world canvas whose box's middle is nearest ([u], [v]) */
    private fun contNear(u: Float, v: Float): Lay? = layers().filter { it.kind == WorldMapModel.CONTINENT }.minByOrNull { l ->
        val b = model.maps[l.id]?.world?.continent?.let { model.assignOf(canvas, it) }
        val cx = b?.let { ((it.u0 + it.u1) / 2).toFloat() } ?: l.p.centerX(); val cy = b?.let { ((it.v0 + it.v1) / 2).toFloat() } ?: l.p.centerY()
        (cx - u) * (cx - u) + (cy - v) * (cy - v)
    }

    // the canvas's frame on the screen (sxc / syc, back by uxc / uyc), and [shown]'s through its place on it (sx / sy, ux / uy)
    private fun sxc(u: Float): Float { val hw = 0.5f / zoom; return artR.left + (u - (panX - hw)) / (2f * hw) * artR.width() + mapShift() }
    private fun syc(v: Float): Float { val hh = 0.5f / zoom; return artR.top + (v - (panY - hh)) / (2f * hh) * artR.height() }
    private fun uxc(x: Float): Float { val hw = 0.5f / zoom; return (panX - hw) + (x - mapShift() - artR.left) / artR.width() * 2f * hw }
    private fun uyc(y: Float): Float { val hh = 0.5f / zoom; return (panY - hh) + (y - artR.top) / artR.height() * 2f * hh }
    private fun sx(u: Float): Float = sxc(pl.left + u * pl.width())
    private fun sx0(u: Float): Float = sx(u) - mapShift()
    private fun sy(v: Float): Float = syc(pl.top + v * pl.height())
    private fun ux(x: Float): Float = (uxc(x) - pl.left) / pl.width()
    private fun uy(y: Float): Float = (uyc(y) - pl.top) / pl.height()

    // ---------------------------------------------------------------- paints
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val pAddBmp = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val srcR = Rect()
    private val glowShader = RadialGradient(0f, 0f, 1f,
        intArrayOf(Color.argb(120, 255, 196, 110), Color.argb(70, 230, 150, 60), Color.argb(0, 200, 120, 40)),
        floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
    private val dst = RectF()

    override fun onDraw(c: Canvas) {
        layout()
        c.drawColor(Color.rgb(16, 12, 9))
        if (!artReady || model.maps.isEmpty()) {
            val p = UiKit.bodyPaint(30f, Theme.text).apply { textAlign = Paint.Align.CENTER }
            c.drawText(artNote ?: "Opening the map", width / 2f, height / 2f, p)
            return
        }
        if (canvas == 0) jumpTo(startMap())
        stepFly()
        relayer(); pickHere()
        if (hoverDirty) updateHover()
        aimShift()
        c.save()
        c.clipRect(canvasR)
        drawArt(c)
        if (isZoneView()) drawZoneMarks(c) else { drawChildren(c); drawLayerQuests(c) }
        drawWaypoint(c)
        drawTravel(c)
        drawGroup(c)
        drawPlayer(c)
        drawAreaLabel(c)
        drawQuestTip(c)
        drawTravelTip(c)
        // the quest panel (A on a quest circle: b-maproom-quests), at the map's left, over it
        if (qp.isOpen) qp.draw(c, canvasR.left + 16f, canvasR.top + 16f, canvasR.bottom - 16f, min(680f, canvasR.width() * 0.45f))
        c.restore()
        if (drawerOut() > 0.001f) { drawDrawerShadow(c); drawPanel(c) } else rowHits.clear()   // a closed drawer's rows take no taps
        drawFurniture(c)
        drawBottom(c)
        if (animating()) postInvalidateOnAnimation()
        drawCrumbs(c)
        if (menuOpen) drawShowMenu(c)
        crumbMenu?.let { drawCrumbMenu(c, it) }
        drawQuestOptions(c)
        drawConfirm(c)
    }

    /** the game's marks on a zone (quest discs numbered like the panel, the turn-in "?", flight points, the corpse) + Show:'s extras */
    private fun drawZoneMarks(c: Canvas) {
        val m = mapDoc
        val here = shown == playerMap && m != null
        if (here) drawExtras(c)
        if (here && on("flight")) m!!.optJSONArray("taxi")?.let { a ->
            for (i in 0 until a.length()) {
                val t = a.optJSONObject(i) ?: continue
                val x = t.optDouble("x", Double.NaN).toFloat(); val y = t.optDouble("y", Double.NaN).toFloat()
                if (!x.isNaN() && !y.isNaN()) UiKit.taxiNode(c, sx(x), sy(y), 34f, UiKit.Taxi.CAN)
            }
        }
        drawCityLight(c)
        drawNearLight(c)
        if (on("quests")) drawQuestAreas(c)
        drawLayerQuests(c)                  // the other layers' discs and counts
        drawCursor(c)                       // over the art and the objective areas, under every marker (the user: "under that numbered circle")
        if (on("quests")) {
            val fadeA = layOf(shown)?.shown ?: 1f
            val order = mapOrder()
            // THE HOVERED MARKER IS LIT (the user after b101: "I would rather the cursor sort of just highlight the selectable thing"): a
            // filled soft glow under it, never an outline; after b107 the game's reticle sits UNDER the numbered disc ("when highlighting
            // a quest it should be under that numbered circle"), so the disc keeps its size (SEL_SCALE 1: the game never grows a pin
            // under its cursor). The map's hover, or, with the panel open, the list's row (the game lights the same marker); the glow
            // eases in (~120 ms). A focused marker keeps its gold disc and glow.
            val lit = litQuest()
            if (lit != lastLit) { prevLit = lastLit; lastLit = lit; litAt = System.currentTimeMillis() }
            val e = ease(((System.currentTimeMillis() - litAt) / SEL_EASE_MS.toFloat()).coerceIn(0f, 1f))
            for (q in marks().sortedBy { (if (it.id == prevLit) 1 else 0) + (if (it.id == lit) 2 else 0) }) {
                val n = order.indexOf(q.id) + 1
                val cx = sx(q.x); val cy = sy(q.y)
                val k = when (q.id) { lit -> e; prevLit -> 1f - e; else -> 0f }
                if (k > 0.01f) selectGlow(c, cx, cy, SEL_GLOW_R * (0.6f + 0.4f * k), k)
                // EVERY quest a yellow disc (the user on test62: "show ALL of the quests on the maps with the yellow circles"); the
                // focused one stands out by the game's glow and a bright ring, not by another colour
                val focused = q.id == focusQid
                val size = DISC_PX * (1f + (SEL_SCALE - 1f) * k)
                withAlpha(c, cx, cy, size, fadeA) {
                    UiKit.questDisc(c, cx, cy, size, if (q.done) UiKit.Quest.TURN_IN else UiKit.Quest.PROGRESS,
                        focus = true, glow = focused, number = if (q.done || n <= 0) null else n)
                    if (focused) {
                        pRing.color = Color.argb(240, 255, 236, 140); pRing.strokeWidth = 3.5f
                        c.drawCircle(cx, cy, size * 0.42f, pRing)
                    }
                }
            }
            if (e < 1f) postInvalidateOnAnimation()
        }
        m?.optJSONObject("corpse")?.let { k ->
            val x = k.optDouble("x", Double.NaN).toFloat(); val y = k.optDouble("y", Double.NaN).toFloat()
            if (here && !x.isNaN() && !y.isNaN()) UiKit.tex("mm_corpse")?.let { b ->
                dst.set(sx(x) - 22f, sy(y) - 22f, sx(x) + 22f, sy(y) + 22f); c.drawBitmap(b, null, dst, pBmp)
            }
        }
    }

    /**
     * THE OBJECTIVE AREAS (the user after b104: "when Highlighting a quest it should show the highlighted areas where the objectives
     * are at"): the lit quest's and the focused quest's areas, on a zone map, under the markers, as the game's QuestBlobDataProvider
     * draws the highlighted / focused quest's. Home's paints exactly (the user's pick 09-29: the game's QuestBlob colours). From
     * QuestieDB's spawn areas of this map (QdbAreas, as Home's map draws them; Quests' add-on sends the player's map), grouped and
     * rounded in this map's yards; a ready quest has none (its "?" says it). (The baked AzerothCore shapes left the app with the
     * art harvest, the user 2026-10-09.)
     */
    private fun drawQuestAreas(c: Canvas) {
        val w = model.world(shown) ?: return
        val qa = QdbAreas.latestFor(shown) ?: return
        val ax = w.left - w.right; val ay = w.top - w.bottom
        if (ax <= 0.0 || ay <= 0.0) return
        val lit = litQuest()
        val ids = LinkedHashSet<Int>()
        if (focusQid != 0) ids.add(focusQid)
        lit?.let { ids.add(it) }
        val e = ease(((System.currentTimeMillis() - litAt) / SEL_EASE_MS.toFloat()).coerceIn(0f, 1f))
        for (id in ids) {
            val q = logEntry(id)
            if (q?.optBoolean("ready") == true || q?.optBoolean("done") == true || marks().firstOrNull { it.id == id }?.done == true) continue
            val groups = qa.objs.filter { it.qid == id }.flatMap { QdbAreas.groups(it.pts, ax, ay) }
            if (groups.isEmpty()) continue
            val k = if (id == lit && id != focusQid) e else 1f
            for (g in QdbAreas.keep(groups, focused = id == focusQid)) {
                if (g.outline.size < 3) continue
                areaPath.reset()
                for ((j, p) in g.outline.withIndex()) {
                    val x = sx(p[0].toFloat()); val y = sy(p[1].toFloat())
                    if (j == 0) areaPath.moveTo(x, y) else areaPath.lineTo(x, y)
                }
                areaPath.close()
                pAreaFill.color = Color.argb((128 * k).toInt(), 57, 109, 255); c.drawPath(areaPath, pAreaFill)
                pAreaRim.color = Color.argb((191 * k).toInt(), 150, 190, 255); c.drawPath(areaPath, pAreaRim)
            }
        }
    }
    private val areaPath = Path()
    private val pAreaFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pAreaRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }

    /**
     * THE LIT CAPITAL, AS THE GAME SHOWS IT (the user after round 3: "the Stromwind highlight does not look how it does in the game.
     * thats kind of obnoxious"): the game's map lights a map only from its highlight art (MapHighlightDataProvider), and the capitals
     * have none (UiMapArt highlight 0); what it shows for the area under its cursor is the AREA LABEL, the map's name at the top of the
     * map (AreaLabelDataProvider: C_Map.GetMapInfoAtPosition, WorldMapTextFont). So: the name at the top, no light.
     */
    private fun drawCityLight(c: Canvas) {
        val city = hoverCity ?: return
        if (region != MapRoomKeys.Region.MAP) return
        // the user after b105: "The city should still have a subtle highlight when highlighting it" (round 3's glow was "obnoxious"):
        // UI Art's softened light, near the game's zone highlight: an ellipse inset 15% in the city's rect, (255,222,150) at a peak of
        // 85, ADDED, its edge feathered over 30% of the rect's shorter side, clipped to the visible map
        model.citiesIn(shown).firstOrNull { it.first == city }?.second?.let { r ->
            val l0 = sx(r[0]); val t0 = sy(r[2]); val r0 = sx(r[1]); val b0 = sy(r[3])
            val w = r0 - l0; val h = b0 - t0
            val l = l0 + w * 0.15f; val rr = r0 - w * 0.15f; val t = t0 + h * 0.15f; val b = b0 - h * 0.15f
            val rx = (rr - l) / 2f; val ry = (b - t) / 2f
            val feather = 0.3f * min(w, h)
            val inner = (1f - feather / max(1f, min(rx, ry))).coerceIn(0.05f, 0.95f)
            c.save()
            c.clipRect(canvasR.left, artR.top, min(canvasR.right, drawerLeft()), artR.bottom)
            c.translate((l + rr) / 2f, (t + b) / 2f); c.scale(rx, ry)
            pGlow.shader = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(85, 255, 222, 150), Color.argb(85, 255, 222, 150), Color.argb(0, 255, 222, 150)),
                floatArrayOf(0f, inner, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(0f, 0f, 1f, pGlow)
            pGlow.shader = null
            c.restore()
        }
    }

    /**
     * THE HOVERED NEIGHBOUR, as subtle as the capital's light (the user's ask, tracking: "the name at the top + a soft light, the
     * game's way"): the zone's own game shape (its continent highlight picture) where it reaches into this art, as a mask for the
     * capital's warm light (255,222,150, a flat 60 ADDED: the capital's peaked 85 averages about that), clipped to the visible map;
     * its name and levels: drawAreaLabel.
     */
    private fun drawNearLight(c: Canvas) {
        val id = hoverNear ?: return
        if (region != MapRoomKeys.Region.MAP) return
        nearList.firstOrNull { it.id == id }?.let { n ->
            bmp("hl/${n.fid}")?.let { b ->
                // the shape as a MASK, full from half the picture's brightest (where the hover counts) and fading only OUTWARD on the
                // picture's own soft edge (UI Art after the b110 Thor shot: a thin sliver must still get the full 85), then the warm light
                // filled into it
                val km = 510f / hlPeak(n.fid, b)[1]
                pNear.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                    0f, 0f, 0f, 0f, 0f,  0f, 0f, 0f, 0f, 0f,  0f, 0f, 0f, 0f, 0f,  km, km, km, 0f, 0f)))
                val clip = RectF(max(canvasR.left, sx(0f)), max(canvasR.top, sy(0f)), min(min(canvasR.right, sx(1f)), drawerLeft()), min(canvasR.bottom, sy(1f)))
                if (clip.width() <= 0f || clip.height() <= 0f) return@let
                // in a layer: the neighbour's light, then this zone's OWN shape cut out of it (UI Art: the light stays outside the shown
                // zone, the shapes' soft edges overlap), the layer ADDED onto the map
                c.saveLayer(clip, pNearLayer)
                srcR.set(0, 0, max(1, (b.width * n.tpx).toInt()), max(1, (b.height * n.tpy).toInt()))
                dst.set(sx(n.box[0]), sy(n.box[2]), sx(n.box[1]), sy(n.box[3]))
                c.drawBitmap(b, srcR, dst, pNear)
                c.drawRect(clip, pNearWarm)
                nearOwn?.let { o -> bmp("hl/${o.fid}")?.let { ob ->
                    val ko = 510f / hlPeak(o.fid, ob)[1]          // fully cut from half the own picture's brightest up
                    pNearCut.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                        0f, 0f, 0f, 0f, 0f,  0f, 0f, 0f, 0f, 0f,  0f, 0f, 0f, 0f, 0f,  ko, ko, ko, 0f, 0f)))
                    srcR.set(0, 0, max(1, (ob.width * o.tpx).toInt()), max(1, (ob.height * o.tpy).toInt()))
                    dst.set(sx(o.box[0]), sy(o.box[2]), sx(o.box[1]), sy(o.box[3]))
                    c.drawBitmap(ob, srcR, dst, pNearCut)
                } }
                c.restore()
            }
        }
    }
    private val pNear = Paint(Paint.FILTER_BITMAP_FLAG)                 // the shape's mask, into the layer
    // the capital's warm (255,222,150) as a FLAT fill at 60 ADDED = (60,52,35): about what the capital's peaked light (85 in its middle,
    // blurred) averages, so a big neighbour (Westfall from Elwynn) is no louder than the light the user accepted (UI Art, mr13)
    private val pNearWarm = Paint().apply { color = Color.rgb(60, 52, 35); xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
    private val pNearCut = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val pNearLayer = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }

    /** Show:'s extras on the player's map: QuestieDB services, spawn spots (pictures on painted maps: UI Art), rares, guesses */
    private fun drawExtras(c: Canvas) {
        // only the NPCs DS Forever has SEEN (the user, 2026-10-12: "Only NPCs actually seen"; SeenNpcs), in the game's own icons
        if (on("services")) qdbPins?.takeIf { it.m == playerMap }?.let { p ->
            val w = model.world(playerMap)
            val ax = w?.let { it.left - it.right } ?: 1.0; val ay = w?.let { it.top - it.bottom } ?: 1.0
            for (v in QdbPins.dropPedestals(p.svc, 1.5, 1.0)) {
                if (v.bits == 0) continue
                val s = SeenNpcs.get(playerMap, v.id) ?: continue
                val at = SeenNpcs.spot(v.x, v.y, s, ax, ay)
                NpcSheet.draw(c, NpcIcons.icon(NpcKind.key(v.bits, v.sub), v.bits), sx(at[0].toFloat()), sy(at[1].toFloat()), 32f, pBmp, this)
            }
        }
        qdbNodes?.takeIf { it.m == playerMap }?.let { n ->
            fun layer(a: List<QdbPins.Node>, kind: UiKit.Spawn) { for (q in a) UiKit.spawnSpot(c, sx(q.x.toFloat()), sy(q.y.toFloat()), kind, SPAWN_PX) }
            if (on("herb")) layer(n.herb, UiKit.Spawn.HERB)
            if (on("ore")) layer(n.ore, UiKit.Spawn.ORE)
        }
        // chests: only the ones Home's minimap probe SAW (SeenChests; b-ghost-chests), never QuestieDB's spawn spots
        if (on("chest")) for (ch in SeenChests.on(playerMap, android.os.SystemClock.elapsedRealtime()))
            UiKit.spawnSpot(c, sx(ch.nx.toFloat()), sy(ch.ny.toFloat()), UiKit.Spawn.CHEST, SPAWN_PX)
        if (on("rare")) qdbPins?.takeIf { it.m == playerMap }?.let { p ->
            for (r in p.rares) for (q in r.pts) UiKit.spawnSpot(c, sx(q[0].toFloat()), sy(q[1].toFloat()),
                if (r.rank == 2) UiKit.Spawn.RARE_ELITE else UiKit.Spawn.RARE, if (r.rank == 2) 44f else 38f)
        }
        if (on("guess")) mapDoc?.let { m ->
            val tracked = questLog.filter { it.has("watch") && !it.isNull("watch") }
            for (s in QdbSpots.derive(m, tracked, 1.5, 1.0)) {
                if (s.turnIn) UiKit.questDisc(c, sx(s.x.toFloat()), sy(s.y.toFloat()), 46f, UiKit.Quest.TURN_IN, ghost = true)
                else UiKit.ghostDot(c, sx(s.x.toFloat()), sy(s.y.toFloat()), 18f)
            }
        }
    }

    /** a continent / the world: its zones; the one under the cursor lit (its area label: drawAreaLabel) */
    // ---- THE QUESTS ON THE MAPS (the user on test61: "show all of the quests on the maps with the yellow circles"; "Before going into
    // the zone, the yellow circle just says the number of quests in that zone"). A zone's quests are its marks (the game's own
    // GetQuestsOnMap, Map.lua mapquests; a capital's counted in its zone); on a continent each zone with any gets ONE yellow circle
    // with their count at its middle, on the world map each continent one. Show:'s Quest Objectives shows / hides them (X did until
    // test12; X is the side bar's now, and its old "questcircles" switch is no longer read).
    // THE SIDE BAR KEPT (the user after test12: "I meant the side bar to give more map space"): X collapses / brings out the quest
    // drawer, the map the room's whole width; kept across app lives with Show:'s switches ("sidebar")
    private fun sideBarOn(): Boolean = prefs.getBoolean("sidebar", true)
    private fun toggleSideBar() { prefs.edit().putBoolean("sidebar", !sideBarOn()).apply(); setDrawer(panelWanted()) }
    /** the distinct quests on map [zone] and the capitals in it */
    private fun questsIn(zone: Int): Set<Int> {
        val out = HashSet<Int>()
        zoneQuests[zone]?.forEach { out.add(it.id) }
        for ((city, parent) in WorldMapModel.CITY_ZONE) if (parent == zone) zoneQuests[city]?.forEach { out.add(it.id) }
        if (zone == playerMap) mapDoc?.optJSONArray("quests")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optInt("questID", 0)?.takeIf { it != 0 }?.let { out.add(it) } }
        return out
    }
    /** this frame's count circles (their zone, where drawn), for the cursor's A */
    private val countHits = ArrayList<Pair<Int, FloatArray>>()
    private val COUNT_PX = 54f
    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    /** a quest marker of a layer: its spot on the canvas, how much it shows (its map's fade) */
    private class LMark(val m: Mark, val map: Int, val u: Float, val v: Float, val a: Float)
    /** the quest markers of the shown map (a zone's, a capital's) and of every layer shown at all */
    private fun layerMarks(): List<LMark> {
        val out = ArrayList<LMark>()
        if (isZoneView()) {
            val a = layOf(shown)?.shown ?: 1f
            for (m in marks()) out.add(LMark(m, shown, pl.left + m.x * pl.width(), pl.top + m.y * pl.height(), a))
        }
        for (l in layers()) {
            if (l.kind == WorldMapModel.CONTINENT || l.id == shown) continue
            val a = l.shown
            if (a <= 0.004f) continue
            for (m in marksOf(l.id)) out.add(LMark(m, l.id, l.p.left + m.x * l.p.width(), l.p.top + m.y * l.p.height(), a))
        }
        return out
    }
    /**
     * THE LAYERS' QUESTS (the user: counts fade into individual circles): each continent's (on the world), zone's and capital's count
     * circle fading out as its own art fades in, its own yellow discs fading in with it; the shown zone's numbered discs are
     * drawZoneMarks'. A circle sits at the middle of its map (a continent's: of the box the world draws it in).
     */
    private fun drawLayerQuests(c: Canvas) {
        countHits.clear()
        if (!on("quests")) return
        for (l in layers()) {
            val ca = l.vis * (1f - l.a)
            if (ca <= 0.004f) continue
            val cont = model.maps[l.id]?.world?.continent
            val n = when (l.kind) {
                WorldMapModel.CONTINENT -> model.maps.values.filter { it.type == 3 && it.world?.continent == cont }.flatMap { questsIn(it.id) }.toSet().size
                WorldMapModel.ZONE -> questsIn(l.id).size
                else -> marksOf(l.id).map { it.id }.toSet().size
            }
            if (n == 0) continue
            val box = if (l.kind == WorldMapModel.CONTINENT) cont?.let { model.assignOf(canvas, it) } else null
            val x = sxc(box?.let { ((it.u0 + it.u1) / 2).toFloat() } ?: l.p.centerX())
            val y = syc(box?.let { ((it.v0 + it.v1) / 2).toFloat() } ?: l.p.centerY())
            if (x < canvasR.left || x > canvasR.right || y < canvasR.top || y > canvasR.bottom) continue
            withAlpha(c, x, y, COUNT_PX, ca) { UiKit.questDisc(c, x, y, COUNT_PX, focus = true, number = n) }  // the game's yellow disc, the count on it
            countHits.add(l.id to floatArrayOf(x, y))
        }
        for (lm in layerMarks()) {
            if (lm.map == shown) continue
            val x = sxc(lm.u); val y = syc(lm.v)
            if (x < canvasR.left || x > canvasR.right || y < canvasR.top || y > canvasR.bottom) continue
            withAlpha(c, x, y, DISC_PX, lm.a) {
                UiKit.questDisc(c, x, y, DISC_PX, if (lm.m.done) UiKit.Quest.TURN_IN else UiKit.Quest.PROGRESS, focus = true, glow = lm.m.id == focusQid)
            }
        }
    }
    /** [draw] at opacity [a] (a layer of [size] around the spot) */
    private fun withAlpha(c: Canvas, x: Float, y: Float, size: Float, a: Float, draw: () -> Unit) {
        if (a >= 0.996f) { draw(); return }
        c.saveLayerAlpha(x - size, y - size, x + size, y + size, (255 * a).toInt().coerceIn(0, 255))
        draw()
        c.restore()
    }

    private fun drawChildren(c: Canvas) {
        val cur = cursor
        val r = cur?.let { id ->
            model.maps[id]?.world?.continent?.let { model.assignOf(shown, it) }?.let { a -> floatArrayOf(a.u0.toFloat(), a.u1.toFloat(), a.v0.toFloat(), a.v1.toFloat()) }
                ?: model.rectOf(id) ?: model.highlightOf(shown, id)?.let { h -> floatArrayOf(h.x, h.x + h.w, h.y, h.y + h.h) } }
        if (cur == null || r == null) { drawCursor(c); return }
        val l = sx(r[0]); val t = sy(r[2]); val rr = sx(r[1]); val b = sy(r[3])
        // the GAME's highlight: the zone art's own picture (art set hl/<fid>), ADD, at the box the game answers (Map.lua maphl:
        // C_Map.GetMapHighlightInfoAtPosition), cropped to texPct as its MapHighlightDataProvider does
        val h = model.highlightOf(shown, cur)
        val hb = h?.let { bmp("hl/${it.fid}") }
        if (h != null && hb != null) {
            srcR.set(0, 0, max(1, (hb.width * h.tpx).toInt()), max(1, (hb.height * h.tpy).toInt()))
            dst.set(sx(h.x), sy(h.y), sx(h.x + h.w), sy(h.y + h.h))
            c.drawBitmap(hb, srcR, dst, pAddBmp)
        } else {
            // UI Art: until the game's box is known, a soft warm ADD glow over the zone's rect, never a gold-edged box (it reads
            // as a UI selection box)
            c.save()
            c.translate((l + rr) / 2f, (t + b) / 2f); c.scale((rr - l) / 2f, (b - t) / 2f)
            pGlow.shader = glowShader
            c.drawCircle(0f, 0f, 1f, pGlow)
            c.restore()
        }
        drawCursor(c)
    }

    /**
     * THE AREA LABEL (the game's AreaLabelDataProvider): the name and levels of the map under the cursor (a continent's zone; in a zone
     * a capital or a neighbour) at the top of the visible map, OVER the golden lines and the markers (the game puts it in the HIGH
     * strata; UI Art after the b110 Thor shot, where the line ran through it); the levels only when the game gives a range
     */
    private fun drawAreaLabel(c: Canvas) {
        val id = if (isZoneView()) (if (region == MapRoomKeys.Region.MAP) hoverCity ?: hoverNear else null) else cursor
        if (id == null) return
        val mid = (canvasR.left + min(canvasR.right, drawerLeft())) / 2f
        c.drawText(model.name(id), mid, canvasR.top + 56f, UiKit.titlePaint(40f).apply { textAlign = Paint.Align.CENTER })
        model.levelsOf(id)?.takeIf { it[1] > 0 }?.let {
            c.drawText("(${it[0]} - ${it[1]})", mid, canvasR.top + 88f, UiKit.bodyPaint(24f, Theme.text).apply { textAlign = Paint.Align.CENTER })
        }
    }

    /**
     * THE MAP PIN the game holds (Map.lua map.uwp {m, x, y, st}), wherever it was placed: on its own map, or carried up to the map
     * shown (a zone's pin on its continent), as the game's own map shows it. The game's pin picture from UI Art's kit
     * (map_waypoint, map_waypoint_tracked while super-tracked); nothing is drawn without it.
     */
    private fun drawWaypoint(c: Canvas) {
        val u = mapDoc?.optJSONObject("uwp") ?: return
        val m = u.optInt("m", 0)
        val x = u.optDouble("x", Double.NaN).toFloat(); val y = u.optDouble("y", Double.NaN).toFloat()
        if (m == 0 || x.isNaN() || y.isNaN()) return
        val p = toShown(m, x, y) ?: return
        val art = UiKit.tex(if (u.optInt("st", 0) == 1) "map_waypoint_tracked" else "map_waypoint") ?: UiKit.tex("map_waypoint") ?: return
        val sc = 48f / max(art.width, art.height)
        val cx = sx(p[0]); val cy = sy(p[1])
        // the pin's point is its bottom centre, on the spot
        dst.set(cx - art.width * sc / 2f, cy - art.height * sc, cx + art.width * sc / 2f, cy)
        c.drawBitmap(art, null, dst, pBmp)
    }

    /** the cursor shows on the map's side only (the game hides its cursor overlay, the lines and the cursor, while its quest log has
     *  the focus) */
    private fun cursorShows() = region == MapRoomKeys.Region.MAP

    /** the game's cursor: the golden lines, then the reticle (both under the markers: drawn before them) */
    private fun drawCursor(c: Canvas) {
        if (!cursorShows()) return
        drawCursorLines(c)
        drawReticle(c)
    }

    /**
     * THE GOLDEN LINES (the game's MapCanvasScrollControllerMixin:UpdateGamepadCursorLines): four lines in NORMAL_FONT_COLOR from the
     * reticle's edge to the VISIBLE map's edges (clipped: never under the drawer), one level over the map's art, so the markers, the
     * player's arrow and the tooltip sit over them. UI Art at our scale (the game's x1.8): 4 px, from half the reticle less 2 px.
     */
    private fun drawCursorLines(c: Canvas) {
        val l = max(canvasR.left, sxc(0f)); val r = min(min(canvasR.right, sxc(1f)), drawerLeft())
        val t = max(canvasR.top, syc(0f)); val b = min(canvasR.bottom, syc(1f))
        if (r <= l || b <= t) return
        val cx = sx(curX); val cy = sy(curY)
        val off = RETICLE_PX / 2f - 2f
        val h = LINE_PX / 2f
        c.save()
        c.clipRect(l, t, r, b)
        c.drawRect(l, cy - h, cx - off, cy + h, pLines)
        c.drawRect(cx + off, cy - h, r, cy + h, pLines)
        c.drawRect(cx - h, t, cx + h, cy - off, pLines)
        c.drawRect(cx - h, cy + off, cx + h, b, pLines)
        c.restore()
    }
    private val pLines = Paint().apply { style = Paint.Style.FILL; color = Color.rgb(255, 209, 0) }

    /**
     * THE GAME'S CURSOR (SoftCursor, MetallicSoftCursorPointerTemplate; UI Art's kit map_reticle, the game's x2 art): cropped to its
     * TexCoords .08-.95, RETICLE_PX wide, a black copy 2 px right under it (its shadow); as the map opens it fades in from 1.5x over
     * 250 ms (the template's IntroAnim, smoothing IN). No art: the lines alone.
     */
    private fun drawReticle(c: Canvas) {
        val b = UiKit.tex("map_reticle") ?: return
        val t = ((System.currentTimeMillis() - cursorShownAt) / INTRO_MS.toFloat()).coerceIn(0f, 1f)
        val e = t * t
        val size = RETICLE_PX * (1.5f - 0.5f * e)
        val a = (255 * e).toInt()
        srcR.set((b.width * 0.08f).toInt(), (b.height * 0.08f).toInt(), (b.width * 0.95f).toInt(), (b.height * 0.95f).toInt())
        val cx = sx(curX); val cy = sy(curY)
        dst.set(cx - size / 2f + 2f, cy - size / 2f, cx + size / 2f + 2f, cy + size / 2f)
        pReticleShadow.alpha = a; c.drawBitmap(b, srcR, dst, pReticleShadow)
        dst.offset(-2f, 0f)
        pBmp.alpha = a; c.drawBitmap(b, srcR, dst, pBmp); pBmp.alpha = 255
        if (t < 1f) postInvalidateOnAnimation()
    }
    private var cursorShownAt = 0L
    private val pReticleShadow = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
    }

    /** the lit marker's filled soft glow (the user: no outlines; "Use our filled soft light"), under the marker; [k] eases it in */
    private fun selectGlow(c: Canvas, cx: Float, cy: Float, r: Float, k: Float = 1f) {
        // UI Art (mr4 review): a little stronger, to read on the yellow-green parchment at arm's length; still a filled light, no ring
        val a = (230 * k).toInt(); val b = (140 * k).toInt()
        pCursor.shader = RadialGradient(cx, cy, r, intArrayOf(Color.argb(a, 255, 209, 0), Color.argb(b, 255, 190, 0),
            Color.argb(0, 255, 190, 0)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, pCursor)
        pCursor.shader = null
    }

    /** the marker lit now: the map's selection, or the list's row while the panel is open */
    private fun litQuest(): Int? = when {
        region == MapRoomKeys.Region.MAP -> hoverQuest
        selQuest != 0 -> selQuest
        else -> null
    }
    private var lastLit: Int? = null
    private var prevLit: Int? = null
    private var litAt = 0L
    /** the bar's gold curve (ease out) */
    private fun ease(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)
    private val pCursor = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun drawPlayer(c: Canvas) {
        if (px.isNaN() || py.isNaN() || playerMap == 0) return
        val p = toShown(playerMap, px, py) ?: return
        val art = UiKit.tex("mm_player") ?: return
        val cx = sx(p[0]); val cy = sy(p[1])
        val sc = 44f / max(art.width, art.height)
        c.save()
        if (!facing.isNaN()) c.rotate(-Math.toDegrees(facing.toDouble()).toFloat(), cx, cy)
        dst.set(cx - art.width * sc / 2f, cy - art.height * sc / 2f, cx + art.width * sc / 2f, cy + art.height * sc / 2f)
        c.drawBitmap(art, null, dst, pBmp)
        c.restore()
    }

    /** the filter funnel (top right) and the panel toggle (bottom right), the game's own pieces when the kit has them */
    private fun drawFurniture(c: Canvas) {
        val fb = UiKit.tex(if (menuOpen) "map_filter_btn_down" else "map_filter_btn")
        if (fb != null) c.drawBitmap(fb, null, funnelR, pBmp)
        else { pFill.color = Color.argb(230, 10, 8, 6); c.drawCircle(funnelR.centerX(), funnelR.centerY(), funnelR.width() / 2f, pFill) }
        // Select / View opens Show: (the game's own filter button; Start until the user's 2026-10-09 ruling made Start the pause wheel
        // in every room): its glyph just LEFT of the funnel, where the game's gamepad mode draws its own
        UiKit.pad(c, "gen_back", funnelR.left - GLYPH_PX / 2f - 6f, funnelR.centerY(), GLYPH_PX)
        // (the quest panel's toggle is gone: the user after b104, "remove the Y to open quest bar and keep it open")
    }

    /**
     * THE LIT QUEST'S TOOLTIP (the user's yes; UI Art's spec, the game's own pin tooltip): the kit's game tooltip beside the lit
     * marker, its title in quest gold and what is left to do (the unfinished objectives, as the game lists them); the user's quarter
     * second (UiKit.TipFade: 250 ms, then a 160 ms fade). Right of the marker (the game anchors right), flipped left when it would cross
     * the visible map's edge. Only while the drawer is closed (open, the row glow says it); no A / X lines (the hint row has them); it
     * never takes a touch.
     */
    private fun drawQuestTip(c: Canvas) {
        // UI Art (round 3): by WHERE THE CURSOR IS - on the map always (the user approved it), in the list never (its row says it)
        val id = if (region == MapRoomKeys.Region.MAP) hoverQuest else null
        val a = tipFade.alpha(id)
        if (tipFade.animating) postInvalidateOnAnimation()
        if (id == null || a <= 0) return
        val lm = layerMarks().firstOrNull { it.m.id == id } ?: return
        val m = lm.m
        val e = logEntry(id)
        val title = e?.optString("title", "")?.ifEmpty { null } ?: m.title ?: return
        val lines = ArrayList<UiKit.TipLine>()
        if (e?.optBoolean("ready") == true || e?.optBoolean("done") == true || m.done) lines += UiKit.TipLine("Ready for turn-in", Color.WHITE)
        else e?.optJSONArray("obj")?.let { o ->
            for (j in 0 until o.length()) o.optJSONObject(j)?.let { if (!it.optBoolean("f")) lines += UiKit.TipLine("- " + it.optString("t"), Color.WHITE) }
        }
        val cx = sxc(lm.u); val cy = syc(lm.v)
        val half = DISC_PX * SEL_SCALE / 2f
        val right = min(artR.right + mapShift(), drawerLeft()) - 8f
        var x = cx + half + 5f
        if (x + TIP_W > right) x = cx - half - 5f - TIP_W
        val estH = 70f + lines.size * 30f
        val y = (cy - half + 2f).coerceIn(canvasR.top + 8f, canvasR.bottom - estH - 8f)
        UiKit.tooltip(c, x, y, TIP_W, title, Theme.gold, lines, alpha = a)
    }
    private val tipFade = UiKit.TipFade()

    /** the drawer's soft shadow on the map, left of its edge */
    private fun drawDrawerShadow(c: Canvas) {
        val l = panelR.left
        pShadow.shader = LinearGradient(l - SHADOW_PX, 0f, l, 0f, Color.argb(0, 0, 0, 0), Color.argb((150 * drawerOut()).toInt(), 0, 0, 0),
            Shader.TileMode.CLAMP)
        c.drawRect(l - SHADOW_PX, panelR.top, l, panelR.bottom, pShadow)
        pShadow.shader = null
    }
    private val pShadow = Paint()

    private fun drawPanel(c: Canvas) {
        // the game's quest-log panel (UI Art's kit: qlog_bg the book, qlog_divider under the title), else our window
        UiKit.tex("qlog_bg")?.let { c.drawBitmap(it, null, panelR, pBmp) } ?: UiKit.windowGround(c, panelR)
        val title = UiKit.titlePaint(34f).apply { textAlign = Paint.Align.CENTER }
        c.drawText(model.name(shown), panelR.centerX(), panelR.top + 46f, title)
        UiKit.tex("qlog_divider")?.let { dst.set(panelR.left + 24f, panelR.top + 56f, panelR.right - 24f, panelR.top + 72f); c.drawBitmap(it, null, dst, pBmp) }
            ?: UiKit.divider(c, panelR.left + 24f, panelR.top + 64f, panelR.right - 24f, panelR.top + 64f)
        rowHits.clear(); checkHits.clear(); visibleRowQuests.clear(); optAnchor = null
        val rows = listRows()
        val top = panelR.top + 84f; val bottom = panelR.bottom - 8f
        if (rows.isEmpty()) { c.drawText("No quests", panelR.left + 24f, top + 24f, UiKit.bodyPaint(20f, Theme.text)); return }
        val hs = rows.map { rowHeight(it) }
        // the cursor's row stays in view
        val total = hs.sum() + ROW_GAP * (hs.size - 1)
        if (region == MapRoomKeys.Region.LIST && listIndex in hs.indices) {
            var y0 = 0f
            for (i in 0 until listIndex) y0 += hs[i] + ROW_GAP
            val y1 = y0 + hs[listIndex]
            if (y0 < listScroll) listScroll = y0
            if (y1 > listScroll + (bottom - top)) listScroll = y1 - (bottom - top)
        }
        listScroll = listScroll.coerceIn(0f, max(0f, total - (bottom - top)))
        val numbers = mapOrder()
        c.save()
        c.clipRect(panelR.left, top, panelR.right, bottom)
        var y = top - listScroll
        for ((i, r) in rows.withIndex()) {
            val h = hs[i]
            if (y + h >= top && y <= bottom) when (r) {
                is MapQuestBook.Row.Header -> drawHeader(c, r, RectF(panelR.left + 8f, y, panelR.right - 8f, y + h), i)
                is MapQuestBook.Row.Quest -> drawQuestRow(c, r.id, RectF(panelR.left + 8f, y, panelR.right - 8f, y + h), i, numbers)
            }
            y += h + ROW_GAP
        }
        c.restore()
    }

    private val titleP = TextPaint(UiKit.bodyPaint(24f, Theme.gold))
    private fun titleOf(id: Int): String {
        val q = logEntry(id)
        // test27 (the user: "the Level number in front of the quests text is confusing, trim that from the quests"): no "[level]"; its colour keeps the difficulty
        return q?.optString("title", "")?.ifEmpty { null } ?: marks().firstOrNull { it.id == id }?.title ?: "Quest"
    }
    /** the title wraps to a second line before the tick box, as the game's does */
    private fun titleLayout(id: Int, w: Float): StaticLayout =
        StaticLayout.Builder.obtain(titleOf(id), 0, titleOf(id).length, titleP, max(10f, w).toInt()).setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 0.95f).build()
    private fun titleW() = PANEL_W - 16f - 52f - CHECK_PX - 18f
    private fun objLines(id: Int): Int {
        val q = logEntry(id) ?: return 0
        if (q.optBoolean("ready") || q.optBoolean("done")) return 1
        return q.optJSONArray("obj")?.length() ?: 0
    }
    private fun rowHeight(r: MapQuestBook.Row): Float = when (r) {
        is MapQuestBook.Row.Header -> HEAD_H
        is MapQuestBook.Row.Quest -> 12f + max(CHECK_PX, titleLayout(r.id, titleW()).height.toFloat()) + objLines(r.id) * 26f + 8f
    }

    /** the game's ListHeaderVisualTemplate: list_heading as a 3-slice bar, the zone name in light text with a shadow on the left,
     *  list_minus (open) / list_plus (closed) at the right; the looked-at zone's header on qlog_header_glow */
    private fun drawHeader(c: Canvas, h: MapQuestBook.Row.Header, r: RectF, i: Int) {
        if (h.zone in book.topZones) UiKit.tex("qlog_header_glow")?.let { c.drawBitmap(it, null, r, pBmp) }
        val bar = UiKit.tex("list_heading")
        if (bar != null) threeSlice(c, bar, r, HEAD_CAP) else { pFill.color = Color.argb(200, 40, 30, 20); c.drawRect(r, pFill) }
        val tp = UiKit.bodyPaint(24f, Color.rgb(230, 222, 200)).apply { setShadowLayer(2f, 1f, 1f, Color.BLACK) }
        c.drawText(ellipsize(tp, h.zone, r.width() - 90f), r.left + 20f, r.centerY() - (tp.ascent() + tp.descent()) / 2f, tp)
        // the game's +/- at their own aspect (UI Art: list_minus is 26x8, not a square), centred where the plus's centre sits, at the
        // plus's scale
        UiKit.tex(if (h.open) "list_minus" else "list_plus")?.let { b ->
            val sc = 28f / 26f
            val bw = b.width * sc; val bh = b.height * sc
            val cx = r.right - 18f - 14f; val cy = r.centerY()
            dst.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f); c.drawBitmap(b, null, dst, pBmp)
        }
        if (region == MapRoomKeys.Region.LIST && i == listIndex) UiKit.focus(c, r)
        rowHits.add(r to i)
    }

    /** a 3-slice bar: the bitmap's two ends (each [cap] of its height wide) kept, the middle stretched */
    private fun threeSlice(c: Canvas, b: Bitmap, r: RectF, cap: Float) {
        val capS = (b.height * cap).toInt().coerceIn(1, b.width / 2)
        val capD = r.height() * cap
        srcR.set(0, 0, capS, b.height); dst.set(r.left, r.top, r.left + capD, r.bottom); c.drawBitmap(b, srcR, dst, pBmp)
        srcR.set(capS, 0, b.width - capS, b.height); dst.set(r.left + capD, r.top, r.right - capD, r.bottom); c.drawBitmap(b, srcR, dst, pBmp)
        srcR.set(b.width - capS, 0, b.width, b.height); dst.set(r.right - capD, r.top, r.right, r.bottom); c.drawBitmap(b, srcR, dst, pBmp)
    }

    private fun drawQuestRow(c: Canvas, id: Int, row: RectF, i: Int, numbers: List<Int>) {
        val q = logEntry(id)
        val ready = q?.optBoolean("ready") == true || q?.optBoolean("done") == true || marks().firstOrNull { it.id == id }?.done == true
        // like the game's quest panel: the focused (super-tracked) quest sits on qlog_quest_glow behind its title; the list cursor is
        // the gold box; the marker the map cursor rests on lights its row softly (the game's link)
        if (id == focusQid) {
            dst.set(row.left, row.top - 4f, row.right, row.top + 40f)
            UiKit.tex("qlog_quest_glow")?.let { c.drawBitmap(it, null, dst, pBmp) } ?: UiKit.selectedRow(c, dst)
        }
        // ONE gold box, wherever the pad is (UI Art): while this quest's options or Abandon's question are up, the pad is there and
        // the row shows the passive link (the game's quest-log glow, softer), as the map's hovered marker links its row
        val padAway = optQuest != 0 || confirmId != 0
        val linked = (id == hoverQuest && region == MapRoomKeys.Region.MAP) || (padAway && (id == optQuest || id == confirmId))
        if (region == MapRoomKeys.Region.LIST && i == listIndex && !padAway) UiKit.focus(c, row)
        else if (linked && id != focusQid) {
            dst.set(row.left, row.top - 4f, row.right, row.top + 40f)
            // the row that owns the open menu / question: the game's own HighlightTexture strength (full); the map's hover link softer
            val owner = padAway && (id == optQuest || id == confirmId)
            UiKit.tex("qlog_quest_glow")?.let { pBmp.alpha = if (owner) 255 else LINK_ALPHA; c.drawBitmap(it, null, dst, pBmp); pBmp.alpha = 255 }
        }
        val n = numbers.indexOf(id) + 1
        UiKit.questDisc(c, row.left + 26f, row.top + 26f, 40f, if (ready) UiKit.Quest.TURN_IN else UiKit.Quest.PROGRESS,
            focus = id == focusQid, number = if (ready || n <= 0) null else n)
        val tl = titleLayout(id, titleW())
        c.save(); c.translate(row.left + 52f, row.top + 10f); tl.draw(c); c.restore()
        // THE TRACK TICK (the game's QuestLogTrackCheckboxTemplate) at the row's top right: X or a touch toggles tracking
        val box = RectF(row.right - 10f - CHECK_PX, row.top + 8f, row.right - 10f, row.top + 8f + CHECK_PX)
        UiKit.tex("qlog_check_box")?.let { c.drawBitmap(it, null, box, pBmp) } ?: UiKit.check(c, box.centerX(), box.centerY(), CHECK_PX, false)
        if (q != null && trackedQ(q)) UiKit.tex("qlog_check_mark")?.let { m ->
            val sc = CHECK_PX / 28f
            val mw = m.width.toFloat() / m.height * CHECK_PX
            dst.set(box.centerX() - mw / 2f + sc, box.top + sc, box.centerX() + mw / 2f + sc, box.bottom + sc)
            c.drawBitmap(m, null, dst, pBmp)
        }
        checkHits.add(RectF(box.left - 8f, box.top - 8f, box.right + 8f, box.bottom + 8f) to id)
        var oy = row.top + 12f + max(CHECK_PX, tl.height.toFloat()) + 18f
        val objP = UiKit.bodyPaint(20f, Theme.text)
        val doneP = UiKit.bodyPaint(20f, Color.rgb(120, 200, 110))
        if (ready) c.drawText("Ready for turn-in", row.left + 60f, oy, doneP)
        else q?.optJSONArray("obj")?.let { objs ->
            for (j in 0 until objs.length()) {
                val o = objs.optJSONObject(j) ?: continue
                c.drawText(ellipsize(objP, "- " + o.optString("t"), row.width() - 70f), row.left + 60f, oy, if (o.optBoolean("f")) doneP else objP)
                oy += 26f
            }
        }
        rowHits.add(row to i)
        visibleRowQuests.add(id)
        if (id == optQuest) optAnchor = RectF(row)
    }

    /** the shown map's quests in the list's order (the book's groups), for the markers' numbers and their rows' */
    private fun mapOrder(): List<Int> {
        val on = marks().map { it.id }
        val inBook = book.groups(entries()).flatMap { it.second }.filter { it in on }
        return inBook + on.filter { it !in inBook }
    }
    private val checkHits = ArrayList<Pair<RectF, Int>>()
    private val visibleRowQuests = HashSet<Int>()

    private fun drawBottom(c: Canvas) {
        val y0 = height - BOTTOM
        val p = if (px.isNaN()) null else if (playerMap == shown) floatArrayOf(px, py) else model.carryUp(playerMap, px, py, shown)
        if (p != null && shown == playerMap) {
            val t = "%.1f, %.1f".format(p[0] * 100f, p[1] * 100f)
            val tp = UiKit.numberPaint(24f, Theme.text)
            pFill.color = Color.argb(200, 8, 6, 4)
            c.drawRoundRect(16f, y0 + 10f, 32f + tp.measureText(t), y0 + 50f, 6f, 6f, pFill)
            c.drawText(t, 24f, y0 + 38f, tp)
        }
        // THE GAME'S LEGEND (the user after b107: "make the legendjust like how forver does it"): its map footer's look (UI Art), centred
        // in the band, kept inside the room
        val lh = LEG_ICON + 2f * LEG_PAD
        drawLegend(c, legendPrompts(), width / 2f, min(y0 + BOTTOM / 2f, height - lh / 2f - 1f))
        // a short answer (a refused action, the pin's refusal) for a few seconds, on a dark plate at the map's bottom left (the
        // legend keeps its band)
        val n = note ?: pinNote
        if (n != null && (note == null || System.currentTimeMillis() - noteAt < 4000L)) {
            val tp = UiKit.bodyPaint(22f, Theme.text)
            val t = ellipsize(tp, n, canvasR.width() - 60f)
            pFill.color = Color.argb(200, 8, 6, 4)
            c.drawRoundRect(canvasR.left + 16f, canvasR.bottom - 50f, canvasR.left + 36f + tp.measureText(t), canvasR.bottom - 14f, 6f, 6f, pFill)
            c.drawText(t, canvasR.left + 26f, canvasR.bottom - 24f, tp)
        }
    }

    /** one prompt of the legend: its button(s) (two = the game's double prompt, "A/X") and its words */
    private class Prompt(val glyphs: List<String>, val text: String)

    /**
     * The legend's prompts. The map side: the game's (MapRoomKeys.legend): "A/X Change map", or on a quest marker the user's words
     * (MapRoomKeys.questPrompts: A "Focus/Track" while untracked, "Focus" while tracked, none on the focused one; X "Track" /
     * "Untrack"); "B Back" (it goes Home, the same as leaving the map); "D-pad right Quests" while the panel is out. The list: A on
     * its row (a quest's in the same words), Y Options on a quest, B, "D-pad left World Map" (the game's quest-log footer). A menu:
     * A and B Close; Abandon's question: none (its buttons carry the glyphs).
     */
    private fun legendPrompts(): List<Prompt> {
        val back = Prompt(listOf(glyph("b")), "Back")
        // the quest panel (b-maproom-quests): its own keys
        if (qp.isOpen) return if (qp.focus < 0) listOf(Prompt(listOf(glyph("right")), "Into the quest"), Prompt(listOf(glyph("b")), "Close"))
            else if (qp.focus == 0) listOf(Prompt(listOf(glyph("up"), glyph("down")), "Scroll"), Prompt(listOf(glyph("b")), "Back"))
            else listOf(Prompt(listOf(glyph("a")), "Press"), Prompt(listOf(glyph("b")), "Back"))
        if (confirmId != 0) return emptyList()      // the question's own buttons say A Yes / B No (UI Art: the clutter rule)
        if (optQuest != 0) return listOf(Prompt(listOf(glyph("a")), "Select"), Prompt(listOf(glyph("b")), "Close"))
        if (menuOpen) return listOf(Prompt(listOf(glyph("a")), "Toggle"), Prompt(listOf(glyph("b")), "Close"))
        if (crumbMenu != null) return listOf(Prompt(listOf(glyph("a")), "Open"), Prompt(listOf(glyph("b")), "Close"))
        if (region == MapRoomKeys.Region.LIST) {
            val out = ArrayList<Prompt>()
            when (val row = listRows().getOrNull(listIndex)) {
                is MapQuestBook.Row.Header -> out += Prompt(listOf(glyph("a")), if (row.open) "Close" else "Open")
                is MapQuestBook.Row.Quest -> {
                    out += MapRoomKeys.questPrompts(logEntry(row.id)?.let { trackedQ(it) } ?: false, row.id == focusQid).map { prompt(it) }
                    out += Prompt(listOf(glyph("y")), "Options")
                }
                null -> {}
            }
            out += back
            out += Prompt(listOf(glyph("left")), "World Map")
            return out
        }
        return MapRoomKeys.legend(keyCtx()).map { prompt(it) }
    }
    private fun prompt(p: MapRoomKeys.Prompt): Prompt = when (p) {
        MapRoomKeys.Prompt.CHANGE_MAP -> Prompt(listOf(glyph("a"), glyph("x")), "Change map")
        MapRoomKeys.Prompt.FOCUS_TRACK -> Prompt(listOf(glyph("a")), "Focus/Track")
        MapRoomKeys.Prompt.FOCUS -> Prompt(listOf(glyph("a")), "Focus")
        MapRoomKeys.Prompt.TRACK -> Prompt(listOf(glyph("x")), "Track")
        MapRoomKeys.Prompt.UNTRACK -> Prompt(listOf(glyph("x")), "Untrack")
        MapRoomKeys.Prompt.BACK -> Prompt(listOf(glyph("b")), "Back")
        MapRoomKeys.Prompt.QUESTS -> Prompt(listOf(glyph("right")), "Quests")
        MapRoomKeys.Prompt.OPEN -> Prompt(listOf(glyph("a")), "Open")
        MapRoomKeys.Prompt.OPEN_QUEST -> Prompt(listOf(glyph("a")), "Open quest")
        MapRoomKeys.Prompt.SHOW_QUESTS -> Prompt(listOf(glyph("x")), "Show quests")
        MapRoomKeys.Prompt.HIDE_QUESTS -> Prompt(listOf(glyph("x")), "Hide quests")
        MapRoomKeys.Prompt.MORE -> Prompt(listOf(glyph("y")), "More")
        MapRoomKeys.Prompt.ZOOM -> Prompt(listOf(MapRoomKeys.ZOOM_GLYPH), "Zoom")
    }

    /**
     * THE GAME'S INPUT LEGEND (InputPromptLegend's default style; UI Art at our scale, the game's x1.8): legend_bg, then legend_frame
     * over it, both nine-sliced (9 px corners of their 32), 18 px in; each prompt its button(s), 44 px (two of one prompt with the
     * game's slash between, 4 px each side, 2 px up), 7 px, its words in the game's gold (27 px, a shadow); 27 px between prompts.
     */
    private fun drawLegend(c: Canvas, prompts: List<Prompt>, cx: Float, cy: Float) {
        if (prompts.isEmpty()) { legendBox.setEmpty(); return }
        val tp = legendP
        fun promptW(p: Prompt) = p.glyphs.size * LEG_ICON + (p.glyphs.size - 1) * (LEG_SLASH_W + 8f) + LEG_TEXT_GAP + tp.measureText(p.text)
        val w = prompts.sumOf { promptW(it).toDouble() }.toFloat() + LEG_GAP * (prompts.size - 1) + 2f * LEG_PAD
        val h = LEG_ICON + 2f * LEG_PAD
        val box = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        legendBox.set(box)
        val bg = UiKit.tex("legend_bg")
        if (bg != null) nine(c, bg, box, 9, LEG_CORNER) else { pFill.color = Color.argb(220, 8, 6, 4); c.drawRoundRect(box, 8f, 8f, pFill) }
        UiKit.tex("legend_frame")?.let { nine(c, it, box, 9, LEG_CORNER) }
        val slash = UiKit.tex("legend_slash")
        var x = box.left + LEG_PAD
        val mid = box.top + LEG_PAD + LEG_ICON / 2f
        for (p in prompts) {
            for ((i, g) in p.glyphs.withIndex()) {
                if (i > 0) {
                    x += 4f
                    if (slash != null) {
                        dst.set(x, mid - LEG_SLASH_H / 2f - 2f, x + LEG_SLASH_W, mid + LEG_SLASH_H / 2f - 2f)
                        c.drawBitmap(slash, null, dst, pBmp)
                    } else c.drawText("/", x + 4f, mid - (tp.ascent() + tp.descent()) / 2f, tp)
                    x += LEG_SLASH_W + 4f
                }
                UiKit.pad(c, g, x + LEG_ICON / 2f, mid, LEG_ICON)
                x += LEG_ICON
            }
            x += LEG_TEXT_GAP
            c.drawText(p.text, x, mid - (tp.ascent() + tp.descent()) / 2f, tp)
            x += tp.measureText(p.text) + LEG_GAP
        }
    }
    private val legendP by lazy { UiKit.bodyPaint(27f, Theme.gold) }     // the game's GameFontNormal: Friz, gold

    /** a nine-slice of [b] into [r]: [m] px corners of the bitmap, drawn [md] px */
    private fun nine(c: Canvas, b: Bitmap, r: RectF, m: Int, md: Float) {
        val xs = intArrayOf(0, m, b.width - m, b.width); val ys = intArrayOf(0, m, b.height - m, b.height)
        val dx = floatArrayOf(r.left, r.left + md, r.right - md, r.right); val dy = floatArrayOf(r.top, r.top + md, r.bottom - md, r.bottom)
        for (i in 0..2) for (j in 0..2) {
            srcR.set(xs[i], ys[j], xs[i + 1], ys[j + 1]); dst.set(dx[i], dy[j], dx[i + 1], dy[j + 1])
            c.drawBitmap(b, srcR, dst, pBmp)
        }
    }

    private fun drawCrumbs(c: Canvas) {
        crumbHits.clear()
        var x = 16f
        val y0 = 12f
        val tp = UiKit.bodyPaint(26f, Theme.text)
        for ((i, id) in model.ancestors(shown).withIndex()) {
            if (i > 0) {
                // UI Art: the plain small gold chevron (menu_next), not a button
                val nx = UiKit.tex("menu_next")
                if (nx != null) { dst.set(x, y0 + 9f, x + 30f, y0 + 39f); c.drawBitmap(nx, null, dst, pBmp) }
                else UiKit.arrow(c, "right", x + 12f, y0 + 24f, 22f)
                x += 34f
            }
            val w = tp.measureText(model.name(id)) + 120f       // dropDown keeps 104 px for its ends and arrow
            val r = RectF(x, y0, x + w, y0 + 48f)
            UiKit.dropDown(c, r, model.name(id), open = crumbMenu == id)
            crumbHits.add(r to id)
            x += w + 8f
        }
    }

    /** a breadcrumb's list: the neighbours of [crumb] (its parent's children, by name) */
    private fun crumbKids(crumb: Int): List<Int> = model.parentOf(crumb)?.let { p -> model.children(p).sortedBy { model.name(it) } } ?: emptyList()
    /** its rows a column: every sibling (a continent has ~25 zones), two columns when the list is long */
    private fun crumbPerCol(n: Int): Int { val cols = if (n > CRUMB_ROWS) 2 else 1; return max(1, (n + cols - 1) / cols) }

    /** open [crumb]'s list (a crumb's arrow, or Y: the game's More actions on the shown map), its own row under the cursor */
    private fun openCrumbMenu(crumb: Int) {
        crumbMenu = crumb
        crumbSel = crumbKids(crumb).indexOf(crumb).coerceAtLeast(0)
    }
    private var crumbSel = 0

    private fun drawCrumbMenu(c: Canvas, crumb: Int) {
        crumbMenuHits.clear()
        val kids = crumbKids(crumb)
        if (kids.isEmpty()) return
        val anchor = crumbHits.firstOrNull { it.second == crumb }?.first ?: return
        // ONE label size for the whole list (the kit's rule: a row left to itself shrinks only its own long label, and the list reads
        // uneven); the pad's row wears the gold box, the one highlight
        val rowH = 44f
        val perCol = crumbPerCol(kids.size)
        val cols = (kids.size + perCol - 1) / perCol
        val colW = 400f
        val left = min(anchor.left, width - 12f - cols * colW)
        val box = RectF(left, anchor.bottom + 4f, left + cols * colW, anchor.bottom + 4f + 16f + perCol * rowH)
        UiKit.menu(c, box)
        for ((i, k) in kids.withIndex()) {
            val col = i / perCol; val row = i % perCol
            val r = RectF(box.left + 8f + col * colW, box.top + 8f + row * rowH, box.left + (col + 1) * colW - 8f, box.top + 8f + (row + 1) * rowH)
            UiKit.menuRow(c, r, model.name(k), i == crumbSel, size = CRUMB_TEXT)
            crumbMenuHits.add(r to k)
        }
    }

    /** a quest's options under its row in the list (the game anchors its menu at the row's bottom left), above it near the bottom */
    private fun drawQuestOptions(c: Canvas) {
        optHits.clear()
        val id = optQuest.takeIf { it != 0 } ?: return
        val os = opts(id)
        if (os.isEmpty()) { optQuest = 0; return }
        optSel = optSel.coerceIn(0, os.size - 1)
        val a = optAnchor ?: RectF(panelR.left + 8f, panelR.top + 84f, panelR.right - 8f, panelR.top + 130f)
        val h = 16f + os.size * OPT_ROW
        var top = a.bottom + 2f
        if (top + h > canvasR.bottom) top = max(canvasR.top, a.top - 2f - h)
        val box = RectF(a.left, top, a.right, top + h)
        UiKit.menu(c, box, solid = true)                 // over the list: nothing behind may shine through
        val q = logEntry(id)
        for ((i, o) in os.withIndex()) {
            val r = RectF(box.left + 8f, box.top + 8f + i * OPT_ROW, box.right - 8f, box.top + 8f + (i + 1) * OPT_ROW)
            val why = if (o == MapRoomKeys.Opt.SHARE && q != null) shareReason(q) else null
            UiKit.menuRow(c, r, optLabel(o), i == optSel, enabled = why == null, size = 28f)
            optHits.add(r to i)
            // a greyed entry under the cursor says why, in the game's words, in the game's tooltip beside the menu (UI Art)
            if (why != null && i == optSel) UiKit.tooltip(c, box.left - 8f - OPT_TIP_W, r.top, OPT_TIP_W, optLabel(o), Color.WHITE,
                listOf(UiKit.TipLine(why, Color.rgb(255, 32, 32))))
        }
    }

    /** Abandon's question, the game's ABANDON_QUEST popup (ABANDON_QUEST_CONFIRM, Yes / No), naming the quest the ADD-ON armed; it
     *  goes after the confirm's 10 s */
    private fun drawConfirm(c: Canvas) {
        confirmHits.clear()
        if (confirmId == 0) return
        if (System.currentTimeMillis() - confirmAt > CONFIRM_MS) { confirmId = 0; say("Too late: choose Abandon Quest again"); syncB(); return }
        val r = RectF(width / 2f - 330f, canvasR.centerY() - 150f, width / 2f + 330f, canvasR.centerY() + 150f)
        val inner = UiKit.dialog(c, r)
        val tp = TextPaint(UiKit.bodyPaint(30f, Theme.gold))
        val text = "Abandon \"$confirmTitle\"?"
        val sl = StaticLayout.Builder.obtain(text, 0, text.length, tp, max(10, inner.width().toInt()))
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build()
        c.save(); c.translate(inner.left, inner.top + 6f); sl.draw(c); c.restore()
        val bw = (inner.width() - 24f) / 2f
        val yes = RectF(inner.left, inner.bottom - 64f, inner.left + bw, inner.bottom)
        val no = RectF(inner.right - bw, inner.bottom - 64f, inner.right, inner.bottom)
        UiKit.button(c, yes, "Yes", glyph("a")); UiKit.button(c, no, "No", glyph("b"))
        confirmHits.add(yes to true); confirmHits.add(no to false)
    }

    private fun drawShowMenu(c: Canvas) {
        menuHits.clear()
        val rowH = 50f
        val h = 70f + filters.size * rowH + 20f
        menuBox.set(funnelR.left - 380f, funnelR.top, funnelR.left - 12f, funnelR.top + h)
        UiKit.menu(c, menuBox)
        c.drawText("Show:", menuBox.left + 22f, menuBox.top + 44f, UiKit.titlePaint(28f))
        var y = menuBox.top + 62f
        for ((i, f) in filters.withIndex()) {
            if (i > 0 && f.ours && !filters[i - 1].ours) {
                UiKit.divider(c, menuBox.left + 16f, y + 2f, menuBox.right - 16f, y + 2f); y += 6f
            }
            val r = RectF(menuBox.left + 10f, y, menuBox.right - 10f, y + rowH)
            if (i == menuSel) UiKit.selectedRow(c, r)
            UiKit.check(c, r.left + 28f, r.centerY(), 34f, on(f.key))
            c.drawText(f.label, r.left + 58f, r.centerY() + 9f, UiKit.bodyPaint(24f, Theme.text))
            menuHits.add(r to i)
            y += rowH
        }
    }

    private fun glyph(k: String): String = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> when (k) {
            "a" -> "ltr_a"; "b" -> "ltr_b"; "x" -> "ltr_x"; "y" -> "ltr_y"; "lb" -> "ltr_lshoulder"; "rb" -> "ltr_rshoulder"
            "right" -> "ltr_right"; "left" -> "ltr_left"; "down" -> "ltr_down"; else -> "ltr_up" }
        CastSettings.ButtonLayout.PLAYSTATION -> when (k) {
            "a" -> "shp_cross"; "b" -> "shp_circle"; "x" -> "shp_square"; "y" -> "shp_triangle"; "lb" -> "shp_lshoulder"
            "rb" -> "shp_rshoulder"; "right" -> "shp_right"; "left" -> "shp_left"; "down" -> "shp_down"; else -> "shp_up" }
        else -> when (k) {
            "a" -> "rev_a"; "b" -> "rev_b"; "x" -> "rev_x"; "y" -> "rev_y"; "lb" -> "rev_lshoulder"; "rb" -> "rev_rshoulder"
            "right" -> "rev_right"; "left" -> "rev_left"; "down" -> "rev_down"
            else -> "gen_up" }
    }

    // ---------------------------------------------------------------- the controller
    // Map.lua mr1 (the user's v2): the room holds the controller as "map" itself, on its own pad row "mapw" (MapRoomPad), and opens
    // the game's world map behind it once the hold is real; an older Map.lua without the row: the generic guest "map", as before
    private var held = false
    private var win: MapRoomPad.Win? = null
    private var openTapped = false
    /** the open cell tapped and the map not yet said open (mapwin): the way out then closes too (MapRoomPad.leaveCols) */
    private var openPending = false
    private var lastMapN = -1
    private fun ownRow() = MapRoomPad.has(padDoc)
    private fun rowTap(col: Int): Boolean = TapBridge.taps(context, padDoc, MapRoomPad.cell(col))
    private var lastPadN = -1
    private var bSaid = "home"
    private val holdAsk = HoldAsk()
    private val holdTick = Runnable { askHold() }
    private fun askHold() {
        removeCallbacks(holdTick)
        if (!holdAsk.due()) return
        val sent = if (ownRow()) rowTap(MapRoomPad.HOLD) else guestTap(GuestPad.cells(padDoc, "map", hold = true))
        if (holdAsk.asked(sent)) postDelayed(holdTick, HoldAsk.EVERY_MS)
    }
    private fun guestTap(cells: List<Pair<String, Int>>?): Boolean = cells != null && TapBridge.taps(context, padDoc, cells)

    /** where B leads, for the Home button's glyph: "back" (a menu, or something browsed) or "home" */
    fun bLeads(): String = if (modal() || !MapRoomKeys.bLeadsHome(keyCtx())) "back" else "home"

    private fun syncB() {
        if (!held) return
        val w = bLeads()
        if (w == bSaid) return
        val sent = if (ownRow()) rowTap(if (w == "home") MapRoomPad.BH else MapRoomPad.BB) else guestTap(GuestPad.bnext(padDoc, home = w == "home"))
        if (sent) bSaid = w
    }

    /**
     * Map.lua's "mapwin": the room's hold and the game's window. Once the hold is real the game's map is opened behind the room (the
     * open cell, once). True = the GAME opened its map itself (M, the radial): MainActivity brings the room up.
     */
    fun onMapWin(d: JSONObject): Boolean {
        val before = win
        val w = MapRoomPad.Win.from(d)
        win = w
        if (w.held) holdAsk.answered()
        held = w.active
        if (w.open) openPending = false
        if (isShown && MapRoomPad.wantOpen(w, openTapped) && rowTap(MapRoomPad.OPEN)) { openTapped = true; openPending = true }
        if (held && isShown) syncB()
        if (isShown) invalidate()
        return MapRoomPad.gameOpened(before, w)
    }

    /** Map.lua's "mappad": a press, the hold lost, or BACK (the player's B closed the game's map: the room leaves for Home) */
    fun onMapPad(msg: JSONObject) {
        holdAsk.answered()
        if (msg.has("lost")) { held = false; invalidate(); return }
        val n = msg.optInt("n", -1)
        if (n == lastMapN) return
        lastMapN = n
        val k = msg.optString("k")
        if (k == "BACK") { onLeave?.invoke(); return }
        held = true
        if (key(k)) leave()
        syncB()
        invalidate()
    }

    /** Map.lua's "mapack": the hold's answer (either way the add-on has spoken) and the pin's (placed / removed / cleared / refused) */
    fun onMapAck(msg: JSONObject) {
        when (msg.optString("op")) {
            "hold" -> holdAsk.answered()
            "pin" -> pinNote = if (msg.optBoolean("ok")) null else msg.optString("why").ifEmpty { null }
        }
        invalidate()
    }
    private var pinNote: String? = null

    /** the room is not on screen but still holds (MainActivity's one rule): let go its own way, the game's map closed with it */
    fun freeAgain(): Boolean {
        val w = win
        if (w != null && !w.held && !w.open && !openPending) return false
        // an open tapped and not yet answered: the close too, before the free (MapRoomPad.leaveCols)
        val cols = MapRoomPad.leaveCols(w ?: MapRoomPad.Win(), openPending)
        val sent = TapBridge.tapsSpaced(context, padDoc, cols.flatMap { MapRoomPad.cell(it) }, MapRoomPad.GAP_MS)
        if (sent) openPending = false
        return sent
    }


    fun onGuestAck(msg: JSONObject) {
        when (msg.optString("op")) {
            "hold" -> { holdAsk.answered(); if (msg.optBoolean("ok")) { held = true; bSaid = "home"; syncB() } }
            "free", "leave" -> held = false
        }
        invalidate()
    }

    fun onPad(msg: JSONObject) {
        holdAsk.answered()
        if (msg.has("lost")) { held = false; invalidate(); return }
        val n = msg.optInt("n", -1)
        if (n == lastPadN) return
        lastPadN = n
        held = true
        if (key(msg.optString("k"))) leave()
        syncB()
        invalidate()
    }

    /** one press; true = B with nothing left to close or step back from (the room leaves) */
    private fun key(k: String): Boolean {
        if (confirmId != 0) {
            when (k) { "PAD1" -> confirmAbandon(); "PAD2", "PAD4" -> confirmId = 0 }
            return false
        }
        // the quest panel (b-maproom-quests): Right into it, Up / Down / A in it, Left / B out, B on the map closes it
        if (qp.isOpen && qp.key(k)) return false
        if (optQuest != 0) {
            val os = opts(optQuest)
            when (k) {
                "PAD1" -> os.getOrNull(optSel)?.let { runOpt(it) }
                "PAD2", "PAD4" -> optQuest = 0
                else -> optSel = MapRoomKeys.menuMove(optSel, os.size, max(1, os.size), k)
            }
            return false
        }
        if (menuOpen) {
            when (k) {
                "PADDUP" -> menuSel = (menuSel - 1 + filters.size) % filters.size
                "PADDDOWN" -> menuSel = (menuSel + 1) % filters.size
                "PAD1" -> toggle(filters[menuSel].key)
                "PAD2", "PAD4" -> menuOpen = false
            }
            return false
        }
        crumbMenu?.let { crumb ->
            val kids = crumbKids(crumb)
            when (k) {
                "PAD2", "PAD4" -> crumbMenu = null
                "PAD1" -> { crumbMenu = null; kids.getOrNull(crumbSel)?.let { goTo(it) } }
                else -> crumbSel = MapRoomKeys.menuMove(crumbSel, kids.size, crumbPerCol(kids.size), k)
            }
            return false
        }
        when (val d = MapRoomKeys.press(k, keyCtx())) {
            MapRoomKeys.Do.Home -> return true
            MapRoomKeys.Do.Up -> model.parentOf(shown)?.let { goTo(it) }      // the map's side: the list re-sorts for the new zone
            MapRoomKeys.Do.ShowMenu -> { menuOpen = true; menuSel = 0 }
            MapRoomKeys.Do.MoreActions -> openCrumbMenu(shown)
            is MapRoomKeys.Do.QuestOptions -> openOptions(d.quest)
            MapRoomKeys.Do.ToList -> {
                region = MapRoomKeys.Region.LIST
                val rows = listRows()
                val onLit = rows.indexOfFirst { it is MapQuestBook.Row.Quest && it.id == hoverQuest }
                listIndex = if (onLit >= 0) onLit else rows.indexOfFirst { it is MapQuestBook.Row.Quest }.coerceAtLeast(0)
                setListCursor(follow = false)
            }
            MapRoomKeys.Do.ToMap -> {
                region = MapRoomKeys.Region.MAP
                if (questMarks().any { it.first == selQuest }) select(selQuest) else updateHover()
                follow()
            }
            is MapRoomKeys.Do.ToggleGroup -> {
                book.toggle(d.zone)
                listIndex = listRows().indexOfFirst { it is MapQuestBook.Row.Header && it.zone == d.zone }.coerceAtLeast(0)
                setListCursor(follow = false)
            }
            // LB / RB: a 1.6x glide (in toward the cursor), from where a glide under way is going
            MapRoomKeys.Do.ZoomIn -> { hereTarget = 0; fly((if (flying()) flyZ else zoom) * 1.6f, ccx, ccy) }
            MapRoomKeys.Do.ZoomOut -> { hereTarget = 0; fly((if (flying()) flyZ else zoom) / 1.6f, if (flying()) flyX else panX, if (flying()) flyY else panY) }
            is MapRoomKeys.Do.Open -> goTo(d.zone)
            MapRoomKeys.Do.ToggleSideBar -> toggleSideBar()
            is MapRoomKeys.Do.OpenQuest -> qp.open(d.quest)
            is MapRoomKeys.Do.TrackFocus -> trackFocus(d.quest)
            is MapRoomKeys.Do.ToggleTrack -> toggleTrack(d.quest)
            is MapRoomKeys.Do.ListMove -> moveQuest(d.d)
            MapRoomKeys.Do.None -> {}
        }
        return false
    }

    private fun moveQuest(d: Int) = moveList(d)


    private fun leave() {
        // the own row: Home's show() hides the room, whose onHidden lets go its own way (the game's close / the free)
        if (ownRow()) { onLeave?.invoke(); return }
        if (!guestTap(GuestPad.leaveB(padDoc))) onLeave?.invoke()
    }

    // ---------------------------------------------------------------- touch: the same things a finger can reach
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) { downAt = e.eventTime; return true }
        if (e.action != MotionEvent.ACTION_UP) return true
        val x = e.x; val y = e.y
        val long = e.eventTime - downAt >= LONG_PRESS_MS
        if (confirmId != 0) {
            confirmHits.firstOrNull { it.first.contains(x, y) }?.let { (_, yes) -> if (yes) confirmAbandon() else confirmId = 0 }
            syncB(); invalidate(); return true
        }
        if (optQuest != 0) {
            optHits.firstOrNull { it.first.contains(x, y) }?.let { (_, i) -> optSel = i; opts(optQuest).getOrNull(i)?.let { runOpt(it) } }
                ?: run { optQuest = 0 }
            syncB(); invalidate(); return true
        }
        if (menuOpen) {
            menuHits.firstOrNull { it.first.contains(x, y) }?.let { menuSel = it.second; toggle(filters[it.second].key); return true }
            if (!menuBox.contains(x, y)) { menuOpen = false; syncB(); invalidate() }
            return true
        }
        crumbMenu?.let {
            crumbMenuHits.firstOrNull { h -> h.first.contains(x, y) }?.let { h -> crumbMenu = null; goTo(h.second); return true }
            crumbMenu = null; syncB(); invalidate(); return true
        }
        crumbHits.firstOrNull { it.first.contains(x, y) }?.let { (r, id) ->
            if (x > r.right - 44f) openCrumbMenu(id) else goTo(id)
            syncB(); invalidate(); return true
        }
        if (funnelR.contains(x, y)) { menuOpen = true; menuSel = 0; syncB(); invalidate(); return true }
        checkHits.firstOrNull { it.first.contains(x, y) }?.let { (_, id) -> toggleTrack(id); invalidate(); return true }
        rowHits.firstOrNull { it.first.contains(x, y) }?.let { (_, i) ->
            when (val r = listRows().getOrNull(i)) {
                is MapQuestBook.Row.Header -> { book.toggle(r.zone); region = MapRoomKeys.Region.LIST; listIndex = i; setListCursor(follow = false) }
                is MapQuestBook.Row.Quest ->
                    if (long) { region = MapRoomKeys.Region.LIST; listIndex = i; setListCursor(follow = false); openOptions(r.id) }
                    else if (listIndex == i && region == MapRoomKeys.Region.LIST) trackFocus(r.id)
                    else { region = MapRoomKeys.Region.LIST; listIndex = i; setListCursor() }
                null -> {}
            }
            syncB(); invalidate(); return true
        }
        if (canvasR.contains(x, y) && x < panelR.left) {
            // a tap moves the cursor there (hovering what is under it); a second tap on the hovered thing does what A does there
            val before = currentThing()
            region = MapRoomKeys.Region.MAP
            ccx = uxc(x).coerceIn(0f, 1f); ccy = uyc(y).coerceIn(0f, 1f)
            keepOffLegend()
            updateHover()
            val hit = currentThing()
            if (hit != null && hit == before) { if (hit < 0) goTo(-hit) else if (isZoneView()) trackFocus(hit) else goTo(hit) }
            syncB(); invalidate()
        }
        return true
    }

    private var downAt = 0L

    private fun ellipsize(p: Paint, t: String, w: Float): String {
        if (p.measureText(t) <= w) return t
        var n = t.length
        while (n > 1 && p.measureText(t, 0, n) + p.measureText("...") > w) n--
        return t.substring(0, n).trimEnd() + "..."
    }

    private companion object {
        const val SPAWN_PX = 26f          // UI Art: on painted maps a spawn spot is the ghost PICTURE (24 px and up), never the dot
        const val DISC_PX = 58f           // a quest marker
        const val HOVER_PX = DISC_PX / 2f // a quest marker under the cursor: within its own drawn size
        const val SEL_SCALE = 1f          // the hovered one keeps its size (the reticle sits under it: the user after b107)
        const val SEL_GLOW_R = 56f        // on a filled soft glow this wide
        const val SEL_EASE_MS = 120L      // the change eased (UI Art: the selection travels, no snap jump)
        const val CRUMB_ROWS = 13          // a breadcrumb's list: this many rows a column
        const val CRUMB_TEXT = 27f         // one label size for every row of it
        const val LINK_ALPHA = 170         // the hovered marker's row: the game's quest-log glow, softer than the list's gold box
        const val TIP_W = 420f             // the lit quest's tooltip (UI Art: ~420 px)
        const val HEAD_H = 56f            // a zone header (the game's ListHeaderVisualTemplate)
        const val HEAD_CAP = 30f / 56f    // its 3-slice ends
        const val ROW_GAP = 4f
        const val CHECK_PX = 38f          // the track tick (UI Art: smaller is lost on the drawer)
        const val OPTIMISTIC_MS = 4000L    // a track / focus shown at once waits this long for the game's document, then falls back
        const val DRAWER_MS = 160L        // the drawer slides (UI Art: ~160 ms, the bar's ease-out)
        const val SHIFT_MS = 120L         // the map eases left to keep the lit quest clear of the drawer
        const val SHIFT_MARGIN = 60f      // that far from the drawer's edge
        const val SHADOW_PX = 22f         // the drawer's soft shadow on the map
        const val TOGGLE_PX = 52f         // the game's 32 px panel toggle, drawn larger (UI Art: ~48-56)
        const val GLYPH_PX = 44f          // the pad glyph beside the toggle / the funnel
        const val PRESS_MS = 160L         // the toggle's pressed picture after Y / a touch
        const val QUEST_GAP_MS = 150L     // track + focus = two pick + act pairs: the pick cell twice, 0.3 s apart (the pad's guard)
        const val CURSOR_PX_S = 900f      // the cursor at full tilt (the game's SoftCursor max speed at our scale: the map in ~1.3 s)
        const val RETICLE_PX = 54f        // the game's 30 px reticle at our scale (UI Art: x1.8)
        const val LINE_PX = 4f            // the golden lines (the game's 2 px)
        const val INTRO_MS = 250L         // the reticle's fade-in as the map opens
        const val LEG_ICON = 44f          // THE LEGEND (UI Art, the game's x1.8): a button
        const val LEG_PAD = 18f           // in from its frame
        const val LEG_GAP = 27f           // between prompts
        const val LEG_TEXT_GAP = 7f       // a button to its words
        const val LEG_SLASH_W = 20f       // the "/" of a double prompt
        const val LEG_SLASH_H = 35f
        const val LEG_CORNER = 16f        // the frame's 9 px corners, drawn
        const val OPT_ROW = 50f           // a quest option's row
        const val OPT_TIP_W = 380f        // a greyed option's reason
        const val PENDING_MS = 5000L      // a share / arm / abandon waits this long for the game's answer
        const val CONFIRM_MS = 10000L     // Abandon's question: the add-on's armed window
        const val LONG_PRESS_MS = 500L    // a long press on a quest row: its options (the game's right click)
        const val FLY_RATE = 9f           // the glide: e-folds a second (about a third of a second to settle)
        const val SOFT_IN = 0.14f / 0.72f // softEdge's feather (14% a side) over its solid middle: the push that makes it all solid
        const val EDGE_EPS = 0.002f       // the world hands its canvas to a continent that covers the view to this much
        val UNIT_R = RectF(0f, 0f, 1f, 1f)
    }
}
