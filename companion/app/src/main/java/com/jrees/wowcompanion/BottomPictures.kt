package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min

/**
 * SETTINGS > BOTTOM SCREEN'S PICTURES (the beautify pass; the user, 2026-11-17: "I REALLY REALLY love what you did with the top screen
 * settings menu with the pictures and want to do something similar to the other menus"; the design's section 3, "all MINI: real
 * miniatures of our own Home"). Each Bottom screen tile's picture, in the Top screen tiles' 216 x 108 space (the band's 438 x 220 is the
 * same picture, larger), made of, in order:
 *
 *  - CROPS of the real Home ([Stage]: PreviewStage's offscreen Home, at the tile's choice), never shrunk more than [MAX_SHRINK] into the
 *    well and never blown up past 1x into the band ([WIN_W] x [WIN_H] of Home's px is the window both use);
 *  - COMPOSED pictures where one crop would not read at the Thor's size (critic C5): the swing line drawn 3x thick over the controller's
 *    crop; the focused quest as two slot frames, the quest card's crop in the chosen one; the clock band drawn by Home's own UiKit.clockChip
 *    and UiKit.clockBand with sample values; the tap-to-cast bar as Home's two columns with real spell icons under the chosen one(s);
 *  - real art: the seamless world map's tiles (Explored only = the base painting, a sample of zones revealed), the companion's own
 *    keyboard (PreviewStage.keyboard), the kit's glyphs;
 *  - and, while none of that is on the phone (no game document yet, no kit), the old "What it does" drawing (SettingsPreview, read
 *    only) re-framed 2:1, or our own drawing.
 *
 * Sample values only (the ToS ruling): no screen reading, no hidden value. The crops are kept in [small] (the tiles', about 2.5 MB at
 * most) and [large] (the open band's), made lazily ONE per frame ([pump]) nearest the cursor first ([want]'s priority), and dropped at
 * Android's running-low trims and when Settings' Bottom screen goes out of view ([shown]).
 */
object BottomPictures {
    /** the tile's picture space (TilePictures' contract) */
    const val W = 216f
    const val H = 108f
    /** a crop may be shrunk at most this much into the 216 x 108 well (critic C5: the Thor's size) */
    const val MAX_SHRINK = 2.2f
    /** the window of Home's px a picture shows: the widest the well allows (216 x 2.2), never narrower than the band's 438 (so the
     *  band never shows Home blown up past 1x) */
    const val WIN_W = 475f
    const val WIN_H = WIN_W / 2f
    /** the band's large picture */
    const val BIG_W = 438f
    const val BIG_H = 220f

    /** the well's crop budget (critic C5's "about 2.5 MB": 11 tiles x 2-4 choices of 216 x 108 ARGB) */
    const val SMALL_BUDGET = 2_621_440L
    /** the open band's crops (a still and, for the animated ones, its fight frame) */
    const val LARGE_BUDGET = 2L * 438 * 220 * 4 + 1024

    /** how much a [srcW] x [srcH] window is shrunk into [dstW] x [dstH] (fitted, its own shape) */
    fun shrink(srcW: Float, srcH: Float, dstW: Float, dstH: Float): Float = max(srcW / dstW, srcH / dstH)
    /** the window may be shown in [dstW] x [dstH]: shrunk no more than [MAX_SHRINK], and never blown up past 1x */
    fun fits(srcW: Float, srcH: Float, dstW: Float, dstH: Float): Boolean = shrink(srcW, srcH, dstW, dstH).let { it <= MAX_SHRINK + 1e-3f && it >= 1f - 1e-3f }

    // ---------------------------------------------------------------- the stage (Home rendered at a look)

    /** one render of Home: the bitmap, its px per Home px, and the modules' boxes in Home's px (map / chat / controller / ...) */
    class Frame(val bmp: Bitmap, val scale: Float, val boxes: Map<String, RectF>)

    /** where the crops come from: Home at [look] ([fight] or not, the DS toolbar on / off / as it is) */
    interface Stage { fun frame(ctx: Context, look: HomeView.Companion.Look, fight: Boolean, toolbar: Boolean?): Frame? }

    /**
     * INTERIM (until the shared core's `PreviewStage.renderWith` + `boxes()` land - the design's section 1; bottom never edits
     * PreviewStage): its half-size cached Home and the map module's box; the chat column is the map's mirror, the controller band
     * everything above them. The integrator swaps this for the core's 1x render and its boxes.
     */
    object HalfStage : Stage {
        override fun frame(ctx: Context, look: HomeView.Companion.Look, fight: Boolean, toolbar: Boolean?): Frame? {
            val b = PreviewStage.home(ctx, look, fight, toolbar) ?: return null
            // the stage's mapBox is its LAST render's: a picture handed back from its cache may be another look's (a cast bar on, the
            // quest over the map), so each picture keeps the box it was drawn with (a picture seen first was just drawn)
            val mb = boxOf.getOrPut(b) { PreviewStage.mapBox?.let { RectF(it) } ?: return null }
            val w = HOME_W; val h = homeH()
            val map = RectF(mb.left * w, mb.top * h, mb.right * w, mb.bottom * h)
            val chat = RectF(w - map.right, map.top, w - map.left, map.bottom)
            return Frame(b, b.width / w, mapOf("map" to map, "chat" to chat, "controller" to RectF(0f, 0f, w, map.top)))
        }
    }
    var stage: Stage = HalfStage
    /** each stage picture's map box (HalfStage), as long as the stage keeps the picture */
    private val boxOf = java.util.WeakHashMap<Bitmap, RectF>()

    const val HOME_W = 1240f
    fun homeH(): Float = 1080f - Strip.HEIGHT

    // ---------------------------------------------------------------- the crop cache

    /** crops by key, least recently used first out once over [budget] bytes */
    class Cache(val budget: Long) {
        private val map = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
        var bytes = 0L
            private set
        val size get() = map.size
        operator fun get(k: String): Bitmap? = map[k]
        fun put(k: String, b: Bitmap) {
            map.remove(k)?.let { bytes -= it.byteCount }
            map[k] = b; bytes += b.byteCount
            val it = map.entries.iterator()
            while (bytes > budget && it.hasNext()) { val e = it.next(); if (e.key == k) continue; bytes -= e.value.byteCount; it.remove() }
        }
        /** everything goes; returns the bytes let go */
        fun clear(): Long { val b = bytes; map.clear(); bytes = 0L; return b }
        fun keys(): Set<String> = map.keys.toSet()
    }
    val small = Cache(SMALL_BUDGET)
    val large = Cache(LARGE_BUDGET)

    /** one crop to make: Home at [look] / [fight] / [toolbar], the [win]dow of Home's px, made [w] x [h] */
    class Shot(val look: HomeView.Companion.Look, val fight: Boolean, val toolbar: Boolean?, val win: (Map<String, RectF>) -> RectF?,
               val w: Int, val h: Int)

    private val pending = LinkedHashMap<String, Pair<Shot, Int>>()
    /** a key whose crop cannot be made (no window in that render): not asked again until the cache drops */
    private val failed = HashSet<String>()

    /** the crop [key] is wanted (made by a later [pump]); [prio] lower = sooner (the cursor's tile, then its neighbours) */
    fun want(key: String, shot: Shot, prio: Int) {
        if (key in failed) return
        val old = pending[key]
        if (old == null || prio < old.second) pending[key] = shot to prio
    }
    fun pendingCount(): Int = pending.size

    /** ONE crop made (the most wanted); true = more wait (the caller draws again next frame) */
    fun pump(ctx: Context): Boolean {
        val next = pending.entries.minByOrNull { it.value.second } ?: return false
        pending.remove(next.key)
        val (shot, _) = next.value
        val big = shot.w > W
        val f = try { stage.frame(ctx, shot.look, shot.fight, shot.toolbar) } catch (e: Throwable) { null }
        if (f == null) { pending.clear(); return false }                         // no game yet: the drawings stand in
        val win = shot.win(f.boxes)
        if (win == null) { failed += next.key; return pending.isNotEmpty() }
        val out = Bitmap.createBitmap(shot.w, shot.h, Bitmap.Config.ARGB_8888)
        val src = Rect((win.left * f.scale).toInt(), (win.top * f.scale).toInt(), (win.right * f.scale).toInt(), (win.bottom * f.scale).toInt())
        src.intersect(0, 0, f.bmp.width, f.bmp.height)
        Canvas(out).drawBitmap(f.bmp, src, RectF(0f, 0f, shot.w.toFloat(), shot.h.toFloat()), pBmp)
        (if (big) large else small).put(next.key, out)
        if (next.key !in madeAt) madeAt[next.key] = android.os.SystemClock.uptimeMillis()
        return pending.isNotEmpty()
    }

    /** the crop [key] ([big] = the band's), else asks for it and returns null. A crop is made ONCE more [AGAIN_MS] after the first (the
     *  minimap's tiles decode on their own thread: the first render may lack them; PreviewStage.AskFrames' second pass), the old one
     *  drawn meanwhile */
    fun crop(key: String, shot: Shot, prio: Int): Bitmap? {
        val c = (if (shot.w > W) large else small)[key]
        if (c == null) want(key, shot, prio)
        else madeAt[key]?.let { t -> if (android.os.SystemClock.uptimeMillis() - t > AGAIN_MS && redone.add(key)) want(key, shot, prio + 20) }
        return c
    }
    const val AGAIN_MS = 3_500L
    private val madeAt = HashMap<String, Long>()
    private val redone = HashSet<String>()

    /** every crop goes (a trim, Settings' Bottom screen out of view); the bytes let go */
    fun drop(): Long { pending.clear(); failed.clear(); madeAt.clear(); redone.clear(); kb = null; worldArt.clear(); spotLooked = false; spotMemo = null; return small.clear() + large.clear() }

    private val viewers = HashSet<Int>()
    /** a Bottom screen tile view came into view ([on]) or went; the last one going drops the crops */
    fun shown(id: Int, on: Boolean) { if (on) viewers += id else if (viewers.remove(id) && viewers.isEmpty()) drop() }

    init {
        MemWatch.onTrim("bottomTiles") { level -> if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) drop() else 0L }
    }

    // ---------------------------------------------------------------- the windows (Home's px)

    /** a 2:1 window [w] wide centred on ([cx], [cy]), kept inside Home */
    private fun win(cx: Float, cy: Float, w: Float = WIN_W, h: Float = w / 2f): RectF {
        val x = (cx - w / 2f).coerceIn(0f, max(0f, HOME_W - w)); val y = (cy - h / 2f).coerceIn(0f, max(0f, homeH() - h))
        return RectF(x, y, x + w, y + h)
    }
    /** the controller's face buttons (the right group) / its D-pad (the left group), from the band's top */
    private fun faces(b: Map<String, RectF>) = b["controller"]?.let { win(it.left + it.width() * 0.715f, it.top + PAD_WIN / 4f, PAD_WIN) }
    private fun dpad(b: Map<String, RectF>) = b["controller"]?.let { win(it.left + it.width() * 0.278f, it.top + PAD_WIN / 4f, PAD_WIN) }
    /** the controller's groups' window: just the group (the clock band's icons and the L / R trigger boxes beside it stay out, so the
     *  miniature has clean edges); 404 of Home's px into the band's 438 */
    private const val PAD_WIN = 404f
    /** the fight's window: the grown buttons need about the band's 438 (its edges softened: [sides]), the R trigger box just outside */
    private fun fightFaces(b: Map<String, RectF>) = b["controller"]?.let { win(it.left + it.width() * 0.713f, it.top + FIGHT_WIN / 4f, FIGHT_WIN) }
    private const val FIGHT_WIN = 436f
    /** the top of a column (the focused quest's frames): [QW] x [QH] of Home at its top-left */
    private const val QW = 236f
    private const val QH = 220f
    private fun colTop(b: Map<String, RectF>, key: String) = b[key]?.let { RectF(it.left, it.top, it.left + QW, it.top + QH) }
    /** the chat's newest lines (its foot) when the card is not on it */
    private fun colFoot(b: Map<String, RectF>, key: String) = b[key]?.let { RectF(it.left, it.bottom - QH, it.left + QW, it.bottom) }
    /** the minimap, [k] of the window (indoors: narrower, the same map closer) */
    private fun mapWin(b: Map<String, RectF>, k: Float) = b["map"]?.let { win(it.centerX(), it.centerY(), WIN_W * k) }

    /** a window of module [key] the shape of [dw] x [dh], [k] Home px per unit, kept inside the module; [anchor] -1 its top, 0 its
     *  middle, 1 its foot */
    private fun modWin(b: Map<String, RectF>, key: String, dw: Float, dh: Float, k: Float, anchor: Int, left: Boolean = false): RectF? = b[key]?.let { m ->
        var w = dw * k; var h = dh * k
        if (w > m.width()) { h *= m.width() / w; w = m.width() }
        if (h > m.height()) { w *= m.height() / h; h = m.height() }
        // the chat's words start at its left: its window keeps that edge (a few px in, past the module's frame)
        val x = if (left) m.left + 6f else m.centerX() - w / 2f
        val y = when (anchor) { -1 -> m.top; 1 -> m.bottom - h; else -> m.centerY() - h / 2f }
        RectF(x, y, x + w, y + h)
    }

    /** the well's ground: the game's world colour (the design's 0.2: not a theme token, it stands for the game) */
    val WORLD = Color.rgb(14, 16, 18)

    /** a view to draw again when art that was loading lands (the minimap tiles decode on their own thread) */
    var onArt: (() -> Unit)? = null

    // ---------------------------------------------------------------- the minimap's own art (Minimap zoom, the cast bar's map column)

    /** a sample place (sample values only, the ToS ruling): its continent, world x (north) / y (west) and facing */
    private class Spot(val cont: Int, val wx: Double, val wy: Double, val facing: Float)
    /** Razor Hill (Durotar) first, Goldshire after: the first whose tile is on the phone */
    private val SPOTS = listOf(Spot(1, 300.0, -4700.0, 2.11f), Spot(0, -9460.0, 60.0, 0.6f))
    private fun tileCol(wy: Double) = kotlin.math.floor(32 - wy / MinimapTiles.ADT).toInt()
    private fun tileRow(wx: Double) = kotlin.math.floor(32 - wx / MinimapTiles.ADT).toInt()
    private var spotMemo: Spot? = null
    private var spotLooked = false
    private fun spot(): Spot? {
        if (!spotLooked) {
            spotLooked = true
            spotMemo = SPOTS.firstOrNull { s -> MinimapTiles.has(s.cont) && ArtHarvest.file("minimap/${s.cont}/${tileCol(s.wy)}_${tileRow(s.wx)}.webp").isFile }
        }
        return spotMemo
    }

    /**
     * the minimap's tiles round the sample place into [r], [ydH] yards from its top to its bottom (the player in the middle), the game's
     * own arrow on top; false = no tile on the phone yet (the caller draws a crop or a drawing instead). The tiles are the full 512 px ones
     * (0.96 px / yd): the band's 220 px show about 230 yd at 1x - never blown up the way a crop of the half-size stage was
     */
    private fun terrain(c: Canvas, r: RectF, ydH: Float): Boolean {
        val s = spot() ?: return false
        val k = r.height() / ydH                                                  // px per yard
        val halfW = r.width() / 2f / k; val halfH = r.height() / 2f / k
        val c0 = tileCol(s.wy + halfW); val c1 = tileCol(s.wy - halfW)
        val r0 = tileRow(s.wx + halfH); val r1 = tileRow(s.wx - halfH)
        if ((c1 - c0 + 1) * (r1 - r0 + 1) > 9) return false
        val adt = MinimapTiles.ADT
        fun sx(wy: Double) = (r.centerX() + (s.wy - wy) * k).toFloat()
        fun sy(wx: Double) = (r.centerY() + (s.wx - wx) * k).toFloat()
        var drew = false
        c.save(); c.clipRect(r)
        for (row in r0..r1) for (col in c0..c1) {
            val dst = RectF(sx((32 - col) * adt) - 0.5f, sy((32 - row) * adt) - 0.5f, sx((31 - col) * adt) + 0.5f, sy((31 - row) * adt) + 0.5f)
            MinimapTiles.get(s.cont, col, row) { onArt?.invoke() }?.let { c.drawBitmap(it, null, dst, pBmp); drew = true }
        }
        c.restore()
        if (!drew) return MinimapTiles.has(s.cont)                                 // still loading: the ground meanwhile, the arrow on it
        return true
    }

    /** the game's minimap arrow (UI Art's mm_player) at the sample place's facing, [size] px; the drawn arrow without the kit */
    private fun arrow(c: Canvas, cx: Float, cy: Float, size: Float) {
        val f = spot()?.facing ?: 0f
        c.save(); c.rotate(-Math.toDegrees(f.toDouble()).toFloat(), cx, cy)
        val art = UiKit.tex("mm_player")
        if (art != null && art.width > 0) c.drawBitmap(art, null, RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f), pBmp)
        else {
            path.reset(); path.moveTo(cx, cy - size * 0.45f); path.lineTo(cx - size * 0.3f, cy + size * 0.32f); path.lineTo(cx, cy + size * 0.12f)
            path.lineTo(cx + size * 0.3f, cy + size * 0.32f); path.close()
            pF.color = Color.rgb(170, 211, 114); c.drawPath(path, pF)
        }
        c.restore()
    }

    // ---------------------------------------------------------------- the pictures

    /** the choices a picture shows (the settings as they are now, or the band's line under the cursor) */
    class Look(val env: BottomTiles.Env, val zoomIndoor: Boolean = false)

    private fun player(rest: HomeRest = HomeSettings.rest, grow: Boolean = HomeSettings.combatGrow, overMap: Boolean = HomeSettings.questOverMap,
                       swing: Boolean = HomeSettings.swingTimer) = HomeView.Companion.Look(rest, grow, overMap, swing)

    private val pF = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pS = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /**
     * tile [id]'s picture into [r] (its own rounded clip; the canvas left as it was): the well's ([r] 216 x 108) or the band's (larger);
     * [tMs] = the band's animation clock (null = the tile's still, frame 0); [prio] = how soon its crops are wanted
     */
    fun draw(c: Canvas, ctx: Context, id: String, r: RectF, look: Look, tMs: Long?, prio: Int) {
        val save = c.save()
        val rad = 8f * r.width() / W
        path.reset(); path.addRoundRect(r, rad, rad, Path.Direction.CW)
        c.clipPath(path)
        pF.color = WORLD; c.drawRect(r, pF)
        val big = r.width() > W + 1f
        runCatching { picture(c, ctx, id, r, look, tMs, prio, big) }
        c.restoreToCount(save)
    }

    private fun picture(c: Canvas, ctx: Context, id: String, r: RectF, look: Look, tMs: Long?, prio: Int, big: Boolean) {
        val t = (tMs ?: 0L) / 1000f
        val cw = if (big) BIG_W.toInt() else W.toInt(); val ch = if (big) BIG_H.toInt() else H.toInt()
        val sz = if (big) "L" else "S"
        fun shot(lk: HomeView.Companion.Look, fight: Boolean, toolbar: Boolean?, w: (Map<String, RectF>) -> RectF?, ww: Int = cw, hh: Int = ch) =
            Shot(lk, fight, toolbar, w, ww, hh)
        when (id) {
            BottomTiles.REST -> {
                val rest = HomeSettings.rest
                val b = crop("rest|$rest|$sz", shot(player(rest = rest), false, null, ::faces), prio)
                if (b != null) { blit(c, b, r); sides(c, r) } else fallback(c, r, "Controller on Home", look, tMs)
            }
            BottomTiles.COMBAT -> {
                val grow = HomeSettings.combatGrow
                val restB = crop("combat|rest|$sz", shot(player(), false, null, ::fightFaces), prio)
                val fightB = crop("combat|$grow|$sz", shot(player(grow = grow), true, null, ::fightFaces), prio)
                if (restB == null || fightB == null) { fallback(c, r, "Combat layout", look, tMs); return }
                // the tile: the fight (frame 0 of the loop); the band opens on the fight too (as the tile shows it), then rest, the fight
                val k = if (tMs == null) 1f else loop(t + 4.4f * 0.45f, 4.4f)
                blit(c, restB, r)
                if (k > 0f) { pBmp.alpha = (255 * k).toInt(); blit(c, fightB, r); pBmp.alpha = 255 }
                sides(c, r, 0.12f)
                if (k > 0f) UiKit.combatGlow(c, r, 0.75f * k, r.height() * 0.16f)
            }
            BottomTiles.SWING -> {
                val on = HomeSettings.swingTimer
                val b = crop("swing|fight|$sz", shot(player(swing = false), true, null, ::fightFaces), prio)
                if (b == null) { fallback(c, r, "Swing timer", look, tMs); return }
                blit(c, b, r); sides(c, r, 0.12f)
                UiKit.combatGlow(c, r, 0.55f, r.height() * 0.14f)
                if (on) swingLine(c, r, if (tMs == null) 0.62f else ((t + 0.62f * 2.6f) % 2.6f) / 2.6f)   // the band opens where the tile is
            }
            BottomTiles.QUEST -> quest(c, ctx, r, prio, big)
            BottomTiles.CLOCK -> clock(c, r)
            BottomTiles.TOOLBAR -> {
                val on = look.env.dpad?.want == true
                val b = crop("toolbar|$on|$sz", shot(player(), false, on, ::dpad), prio)
                if (b != null) { blit(c, b, r); sides(c, r) } else toolbarDrawn(c, r)
            }
            BottomTiles.TALK -> talk(c, r, look, tMs, prio, big)
            BottomTiles.CASTBAR -> castBar(c, r, tMs, prio, big)
            BottomTiles.ZOOM -> {
                val out = Settings.minimapYd; val inn = Settings.minimapIndoorYd
                if (zoom(c, r, out, inn, look.zoomIndoor)) return
                val k = if (look.zoomIndoor) (inn.toFloat() / out.coerceAtLeast(1)).coerceIn(0.4f, 1f) else 1f
                val lk = player(overMap = false)
                val b = crop("zoom|${look.zoomIndoor}|$inn|$out|$sz", shot(lk, false, null, { mapWin(it, k) }), prio)
                if (b != null) { blit(c, b, r); yards(c, r, if (look.zoomIndoor) inn else out, look.zoomIndoor) }
                else fallback(c, r, if (look.zoomIndoor) "Minimap zoom indoors" else "Minimap zoom outdoors", look, tMs)
            }
            BottomTiles.WORLD -> world(c, r, Settings.worldMapFog == "all", look, tMs)
            BottomTiles.INPUT -> input(c, ctx, r, Settings.chatDefaultInput == "quick", big)
        }
    }

    /** 0..1..0 over [period] s: rest, ease up, hold, ease down (the band's loop) */
    private fun loop(t: Float, period: Float): Float {
        val p = t % period
        fun ease(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        return when { p < period * 0.27f -> 0f; p < period * 0.36f -> ease((p - period * 0.27f) / (period * 0.09f)); p < period * 0.77f -> 1f
            p < period * 0.86f -> 1f - ease((p - period * 0.77f) / (period * 0.09f)); else -> 0f }
    }

    private fun blit(c: Canvas, b: Bitmap, r: RectF) = c.drawBitmap(b, null, r, pBmp)

    /** the crop's left and right edges faded into the well (whatever of Home's next module reaches the window's edge goes) */
    private fun sides(c: Canvas, r: RectF, frac: Float = 0.1f) {
        val w = r.width() * frac
        pF.shader = LinearGradient(r.left, 0f, r.left + w, 0f, WORLD, Color.argb(0, Color.red(WORLD), Color.green(WORLD), Color.blue(WORLD)), Shader.TileMode.CLAMP)
        c.drawRect(r.left, r.top, r.left + w, r.bottom, pF)
        pF.shader = LinearGradient(r.right - w, 0f, r.right, 0f, Color.argb(0, Color.red(WORLD), Color.green(WORLD), Color.blue(WORLD)), WORLD, Shader.TileMode.CLAMP)
        c.drawRect(r.right - w, r.top, r.right, r.bottom, pF)
        pF.shader = null
    }

    /** the space's px per unit of the 216 x 108 picture */
    private fun k(r: RectF) = r.width() / W

    /** no game yet: the DS toolbar's diamond of four spells on the D-pad (sample icons, no words on the picture) */
    private fun toolbarDrawn(c: Canvas, r: RectF) {
        val s = k(r)
        val cx = r.centerX(); val cy = r.centerY(); val d = 26f * s; val half = 15f * s
        for ((j, o) in listOf(0f to -1f, 1f to 0f, 0f to 1f, -1f to 0f).withIndex()) {
            val box = RectF(cx + o.first * d * 1.25f - half, cy + o.second * d - half, cx + o.first * d * 1.25f + half, cy + o.second * d + half)
            val ic = Icons.get(SPELL_ICONS[j])
            if (ic != null) c.drawBitmap(ic, null, box, pBmp) else { pF.color = Theme.raised; c.drawRect(box, pF) }
            pS.color = Theme.bronze; pS.strokeWidth = 1.5f * s; c.drawRect(box, pS)
        }
    }

    // ---- Swing timer: the line drawn 3x thick over the controller's crop

    private fun swingLine(c: Canvas, r: RectF, fill: Float) {
        val s = k(r)
        val y = r.bottom - 14f * s
        val l = r.left + 30f * s; val rr = r.right - 30f * s
        pF.color = Color.argb(220, 10, 8, 6); c.drawRoundRect(RectF(l - 2f * s, y - 5f * s, rr + 2f * s, y + 5f * s), 4f * s, 4f * s, pF)
        pF.shader = LinearGradient(l, 0f, rr, 0f, Color.rgb(200, 150, 40), Theme.gold, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(l, y - 3f * s, l + (rr - l) * fill, y + 3f * s), 3f * s, 3f * s, pF)
        pF.shader = null
        pS.color = Theme.bronze; pS.strokeWidth = 1.2f * s
        c.drawRoundRect(RectF(l - 2f * s, y - 5f * s, rr + 2f * s, y + 5f * s), 4f * s, 4f * s, pS)
    }

    // ---- Focused quest: two slot frames (the chat's foot, the minimap), Home's own quest card at the top of the chosen one

    /** the sample focused quest (sample words, the ToS ruling): Home's card object, drawn by Home's own UiKit.questCard */
    private val SAMPLE_QUEST by lazy {
        UiKit.QuestCard("Vile Familiars", 6, Theme.questYellow, listOf("Vile Familiar slain: 4/12" to false), number = 1)
    }

    private fun quest(c: Canvas, ctx: Context, r: RectF, prio: Int, big: Boolean) {
        val over = HomeSettings.questOverMap
        val s = k(r)
        val gap = 8f * s
        val fw = (r.width() - 3f * gap) / 2f; val fh = r.height() - 2f * gap
        val frames = listOf(RectF(r.left + gap, r.top + gap, r.left + gap + fw, r.top + gap + fh),
            RectF(r.right - gap - fw, r.top + gap, r.right - gap, r.top + gap + fh))
        val lk = player(overMap = over)
        for ((i, key) in listOf("chat", "map").withIndex()) {
            val f = frames[i]
            val chosen = (i == 1) == over
            pF.color = Theme.field; c.drawRect(f, pF)
            c.save(); c.clipRect(f)
            if (key == "map") {
                // the minimap's own tiles (crisp at both sizes), the arrow in the open part under the card
                if (terrain(c, f, 150f)) arrow(c, f.centerX(), f.centerY() + (if (chosen) f.height() * 0.18f else 0f), 22f * s)
                else { pF.color = Color.rgb(52, 70, 46); c.drawCircle(f.centerX(), f.centerY() + 8f * s, min(f.width(), f.height()) * 0.36f, pF) }
            } else {
                // the chat's newest lines (its foot): a crop of the real Home, at most 2.2x shrunk into the well
                val ow = f.width().toInt().coerceAtLeast(1); val oh = f.height().toInt().coerceAtLeast(1)
                val b = crop("quest|chat|$over|${if (big) "L" else "S"}", Shot(lk, false, null,
                    { modWin(it, "chat", f.width() / s, f.height() / s, MAX_SHRINK, 1, left = true) }, ow, oh), prio)
                if (b != null) c.drawBitmap(b, null, f, pBmp)
                else { pF.color = Theme.textDim; for (j in 0..2) c.drawRect(f.left + 8f * s, f.bottom - (14f + j * 12f) * s, f.right - (10f + j * 14f) * s, f.bottom - (10f + j * 12f) * s, pF) }
            }
            if (chosen) {
                // Home's card, laid out at the Map room's 400 px and scaled to the frame
                val sc = (f.width() - 4f * s) / 400f
                val ch = UiKit.questCardHeight(SAMPLE_QUEST)
                c.save(); c.translate(f.left + 2f * s, f.top + 2f * s); c.scale(sc, sc)
                UiKit.questCard(c, RectF(0f, 0f, 400f, ch), SAMPLE_QUEST)
                c.restore()
            }
            c.restore()
            if (!chosen) { pF.color = Color.argb(150, 0, 0, 0); c.drawRect(f, pF) }
            pS.color = if (chosen) Theme.gold else Theme.bronzeDim; pS.strokeWidth = (if (chosen) 2.4f else 1.2f) * s
            c.drawRect(f, pS)
        }
    }

    // ---- Clock band: Home's own clock chip and extras row, the extras as they are on

    /** the sample extras' icons: the Hearthstone, a class teleport (Astral Recall), the backpack, arrows */
    private const val TELEPORT_ICON = 136010
    private const val BAG_ICON = 133633
    private const val AMMO_ICON = 132382
    private const val SAMPLE_GOLD = 1_234_567L

    /** Home's px of the band drawn (the chip and the row under it): its middle 432 x 216 */
    private fun clock(c: Canvas, r: RectF) {
        val kk = r.width() / 432f
        c.save(); c.translate(r.left, r.top); c.scale(kk, kk)
        pF.shader = LinearGradient(0f, 0f, 0f, 216f, Theme.panel, Theme.ink, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, 432f, 216f, pF); pF.shader = null
        val gold = HomeSettings.clockGold
        val cx = 216f
        UiKit.clockChip(c, cx, 6f, "12:30", 76, gold = if (gold) SAMPLE_GOLD else null)
        val items = ArrayList<UiKit.BandItem>()
        if (HomeSettings.clockHearth) items += UiKit.BandItem.Icon(Icons.get(ItemCd.HEARTH_ICON), "", glow = true)
        if (HomeSettings.clockTeleport) items += UiKit.BandItem.Icon(Icons.get(TELEPORT_ICON), "")
        if (HomeSettings.clockBags) items += UiKit.BandItem.Icon(Icons.get(BAG_ICON), "14")
        if (HomeSettings.clockAmmo) items += UiKit.BandItem.Icon(Icons.get(AMMO_ICON), "812")
        val floor = 6f + ClockBand.chipBottom(gold) + ClockBand.CHIP_PAD
        // the row 1.6x Home's size here (the tile is small), centred under the chip
        c.save(); c.translate(cx, floor); c.scale(1.6f, 1.6f); c.translate(-cx, -floor)
        if (items.isNotEmpty()) UiKit.clockBand(c, floatArrayOf(cx - 160f, floor, cx + 160f, floor + ClockBand.CELL_H, cx, floor + ClockBand.CELL_H / 2f), items, floor)
        c.restore()
        c.restore()
    }

    // ---- Hold to talk: the real chat behind, the kit's chat box with the mic, D-pad Left with Home's hold ring; the band: hold, the
    // ---- words in the box, then sent into the chat (Send now) or waiting for A (Show, A sends)

    private fun talk(c: Canvas, r: RectF, look: Look, tMs: Long?, prio: Int, big: Boolean) {
        val who = look.env.who
        val on = HomeSettings.holdTalk(who)
        val now = HomeSettings.talkSendNow(who)
        val kk = r.width() / 432f
        // the upper part: the chat's newest lines (a crop of the real Home, its left edge kept; our dim lines until the game is in)
        val chatH = 140f
        val b = crop("talk|chat|${if (big) "L" else "S"}", Shot(player(), false, null,
            { modWin(it, "chat", 432f, chatH, 1.1f, 1, left = true) }, r.width().toInt(), (chatH * kk).toInt().coerceAtLeast(1)), prio)
        c.save(); c.translate(r.left, r.top); c.scale(kk, kk)
        pF.color = WORLD; c.drawRect(0f, 0f, 432f, 216f, pF)
        if (b != null) c.drawBitmap(b, null, RectF(0f, 0f, 432f, chatH), pBmp)
        else {
            pF.color = Theme.textDim
            for (j in 0..3) c.drawRoundRect(RectF(16f, 22f + j * 28f, 300f - j * 46f, 34f + j * 28f), 4f, 4f, pF)
        }
        // the lower part: the game's chat box on the frame, D-pad Left with Home's hold ring, the mic in the box
        pF.shader = LinearGradient(0f, chatH - 30f, 0f, chatH, Color.argb(0, 0, 0, 0), Color.argb(230, 10, 10, 12), Shader.TileMode.CLAMP)
        c.drawRect(0f, chatH - 30f, 432f, chatH, pF); pF.shader = null
        pF.color = Color.argb(230, 10, 10, 12); c.drawRect(0f, chatH, 432f, 216f, pF)
        val t = if (tMs == null) 1.6f else (tMs / 1000f) % 4f                     // the tile: the hold done, the words in the box
        val hold = (t / 1.2f).coerceIn(0f, 1f)                                   // the ring fills while D-pad Left is held
        val gx = 46f; val gy = 178f
        UiKit.pad(c, "gen_left", gx, gy, 58f, if (on) 255 else 150)
        if (on) UiKit.holdRing(c, gx, gy, 36f, hold)
        val box = RectF(92f, 154f, 424f, 202f)
        UiKit.inputBox(c, box)
        VoiceUi.mic(c, box.right - 26f, box.centerY(), 34f, if (on) Theme.gold else Theme.dim, crossed = !on)
        if (on && t >= 1.3f) {
            val sent = now && t >= 2.1f
            val p = UiKit.bodyPaint(24f, Theme.text, shadow = false)
            if (!sent) c.drawText(fit(p, "lfg Razor Hill", box.width() - 110f), box.left + 14f, box.centerY() + 8f, p)
            if (!now) UiKit.pad(c, UiKit.okGlyph(), box.right - 72f, box.centerY(), 36f)
            if (sent) {
                // sent: the line is the chat's newest, lit a moment
                val q = UiKit.bodyPaint(24f, Color.rgb(255, 255, 255), shadow = true)
                val line = RectF(0f, chatH - 38f, 432f, chatH)
                pF.color = WORLD; c.drawRect(line, pF)                            // the line takes the newest place in the chat
                pF.color = Theme.focusA(80); c.drawRoundRect(RectF(8f, chatH - 36f, 340f, chatH - 4f), 6f, 6f, pF)
                c.drawText(fit(q, "${who?.substringBefore('-')?.ifEmpty { null } ?: "You"}: lfg Razor Hill", 320f), 16f, chatH - 12f, q)
            }
        }
        c.restore()
    }

    // ---- Tap-to-cast bar: Home's two columns (the real chat, the minimap's own art), the bar of real spell icons under the chosen
    // ---- one(s); In combat: Hide slides it away

    /** a level-6 Shaman's first spells (CastBarAsk's picture: sample icons) */
    private val SPELL_ICONS = intArrayOf(136048, 136026, 136052, 136051, 136086, 136102, 136015, 135824)

    private fun castBar(c: Canvas, r: RectF, tMs: Long?, prio: Int, big: Boolean) {
        val side = HomeSettings.castBarSide
        val hide = HomeSettings.castBarCombat == CastStripModel.Combat.HIDE
        val kk = r.width() / 432f
        // the band's loop: rest, a fight (the red edge), the bar sliding away (Hide) or staying (Stay), back
        val fight = if (tMs == null || side == CastStripModel.Side.OFF) 0f else loop(tMs / 1000f, 4.4f)
        val slide = if (hide) fight else 0f
        val g = 8f
        val barH = 46f
        val left = side == CastStripModel.Side.LEFT || side == CastStripModel.Side.BOTH
        val right = side == CastStripModel.Side.RIGHT || side == CastStripModel.Side.BOTH
        val room = (barH + g) * (1f - slide)
        val top = 0f
        val chat = RectF(g, top + g, 216f - g / 2f, 216f - g - (if (left) room else 0f))
        val map = RectF(216f + g / 2f, top + g, 432f - g, 216f - g - (if (right) room else 0f))
        val sz = if (big) "L" else "S"
        // the chat column's crop: the full column's shape at rest (the columns grow over the bar's place as it goes)
        val full = RectF(g, top + g, 216f - g / 2f, 216f - g)
        val chatB = crop("cast|chat|$sz", Shot(player(), false, null, { modWin(it, "chat", full.width(), full.height(), 1.1f, 1, left = true) },
            (full.width() * kk).toInt(), (full.height() * kk).toInt()), prio)
        c.save(); c.translate(r.left, r.top); c.scale(kk, kk)
        pF.color = WORLD; c.drawRect(0f, 0f, 432f, 216f, pF)
        // the chat column (its foot: the newest lines stay at the bottom as the column grows)
        c.save(); c.clipRect(chat)
        if (chatB != null) c.drawBitmap(chatB, null, RectF(full.left, chat.bottom - full.height(), full.right, chat.bottom), pBmp)
        else {
            pF.color = Theme.raised; c.drawRect(chat, pF)
            pF.color = Theme.textDim
            for (j in 0..2) { val y = chat.bottom - 14f - j * 16f; if (y > chat.top + 10f) c.drawRect(chat.left + 10f, y - 5f, chat.right - 10f - j * 26f, y, pF) }
        }
        c.restore()
        // the map column: the minimap's own tiles, the arrow in its middle
        c.save(); c.clipRect(map)
        if (terrain(c, map, 160f)) arrow(c, map.centerX(), map.centerY(), 26f)
        else {
            pF.color = Theme.raised; c.drawRect(map, pF)
            val mr = min(map.width(), map.height()) / 2f - 8f
            pF.color = Color.rgb(52, 70, 46); c.drawCircle(map.centerX(), map.centerY(), mr, pF)
        }
        c.restore()
        for (col in listOf(chat, map)) { pS.color = Theme.bronzeDim; pS.strokeWidth = 2f; c.drawRect(col, pS) }
        fun bar(l: Float, rr: Float, n: Int, first: Int) {
            val dy = (barH + g + 2f) * slide
            if (slide >= 1f) return
            val well = RectF(l, 216f - g - barH + dy, rr, 216f - g + dy)
            c.save(); c.clipRect(0f, 0f, 432f, 216f)
            pF.color = Theme.field; c.drawRoundRect(well, 6f, 6f, pF)
            pS.color = Theme.gold; pS.strokeWidth = 2f; c.drawRoundRect(well, 6f, 6f, pS)
            val slot = 36f
            val gap = (well.width() - 8f - n * slot) / n
            for (j in 0 until n) {
                val x = well.left + 4f + gap / 2f + j * (slot + gap)
                val box = RectF(x, well.centerY() - slot / 2f, x + slot, well.centerY() + slot / 2f)
                val ic = Icons.get(SPELL_ICONS[(first + j) % SPELL_ICONS.size])
                if (ic != null) c.drawBitmap(ic, null, box, pBmp)
                else { pF.color = Color.rgb(60 + 20 * j, 90, 140 - 10 * j); c.drawRect(box, pF) }
                pS.color = Theme.bronzeDim; pS.strokeWidth = 1.5f; c.drawRect(box, pS)
            }
            c.restore()
        }
        when (side) {
            CastStripModel.Side.LEFT -> bar(chat.left, chat.right, 4, 0)
            CastStripModel.Side.RIGHT -> bar(map.left, map.right, 4, 4)
            CastStripModel.Side.BOTH -> bar(chat.left, map.right, 8, 0)
            CastStripModel.Side.OFF -> {}
        }
        if (fight > 0f) UiKit.combatGlow(c, RectF(0f, 0f, 432f, 216f), 0.8f * fight, 28f)
        c.restore()
    }

    // ---- Minimap zoom: the minimap's own tiles round the player, the yards each choice shows framed (the line the cursor is on lit,
    // ---- the rest of the picture dimmed), the yards under it

    /** [out] / [inn] yards across; true = drawn (false: no minimap tile on the phone, the caller crops Home instead) */
    private fun zoom(c: Canvas, r: RectF, out: Int, inn: Int, indoor: Boolean): Boolean {
        val s = k(r)
        // the picture's height in yards: twice the wider choice (its frame half the picture across), never closer than 120 yd (the
        // tiles' 0.96 px / yd at most about 1.8x in the band)
        val ydH = max(120f, max(out, inn) * 2f)
        if (!terrain(c, r, ydH)) return false
        val k = r.height() / ydH
        // Home's minimap shape (the module's box), else square
        val aspect = PreviewStage.mapBox?.let { m -> (m.width() * HOME_W) / (m.height() * homeH()).coerceAtLeast(1f) }?.coerceIn(0.6f, 1.6f) ?: 1f
        fun frame(yd: Int): RectF { val w = yd * k; val h = w / aspect; return RectF(r.centerX() - w / 2f, r.centerY() - h / 2f, r.centerX() + w / 2f, r.centerY() + h / 2f) }
        val lit = frame(if (indoor) inn else out)
        val other = frame(if (indoor) out else inn)
        // outside the lit frame: dimmed
        path.reset(); path.fillType = Path.FillType.EVEN_ODD
        path.addRect(r, Path.Direction.CW); path.addRect(lit, Path.Direction.CW)
        pF.color = Color.argb(150, 0, 0, 0); c.drawPath(path, pF)
        path.fillType = Path.FillType.WINDING
        pS.color = Theme.bronzeDim; pS.strokeWidth = 1.4f * s; c.drawRect(other, pS)
        pS.color = Color.argb(200, 0, 0, 0); pS.strokeWidth = 3.6f * s; c.drawRect(lit, pS)
        pS.color = Theme.gold; pS.strokeWidth = 1.8f * s; c.drawRect(lit, pS)
        arrow(c, r.centerX(), r.centerY(), 16f * s)
        yards(c, r, if (indoor) inn else out, indoor)
        return true
    }

    // ---- Minimap zoom: the yards across, under the crop

    private fun yards(c: Canvas, r: RectF, yd: Int, indoor: Boolean) {
        val s = k(r)
        val p = UiKit.numberPaint(15f * s, Theme.gold).apply { textAlign = Paint.Align.RIGHT }
        val y = r.bottom - 8f * s
        val w = p.measureText("$yd yd") + 12f * s
        pF.color = Color.argb(190, 0, 0, 0); c.drawRoundRect(RectF(r.right - 8f * s - w, y - 17f * s, r.right - 4f * s, y + 5f * s), 5f * s, 5f * s, pF)
        c.drawText("$yd yd", r.right - 10f * s, y, p)
        if (indoor) {
            // a roof over the yards: indoors
            pS.color = Theme.gold; pS.strokeWidth = 1.6f * s
            val x0 = r.right - 8f * s - w - 16f * s
            path.reset(); path.moveTo(x0, y - 4f * s); path.lineTo(x0 + 7f * s, y - 12f * s); path.lineTo(x0 + 14f * s, y - 4f * s)
            c.drawPath(path, pS); c.drawRect(x0 + 2f * s, y - 4f * s, x0 + 12f * s, y + 3f * s, pS)
        }
    }

    // ---- World map: the seamless continent's art (Show all = every zone; Explored only = the base painting, a few zones revealed)

    private val CONTINENTS = intArrayOf(1415, 1414, 1945, 113)
    /** the continent pictures at their smallest level, by variant ("all" / "base"); decoded once, dropped with the crops */
    private val worldArt = HashMap<String, Bitmap?>()

    private fun continent(variant: String): Bitmap? = worldArt.getOrPut(variant) {
        val lvl = Seamless.LEVELS - 1
        val cont = CONTINENTS.firstOrNull { Seamless.tile(it, variant, lvl, 0, 0).isFile } ?: return@getOrPut null
        val w = Seamless.W0 shr lvl; val h = Seamless.H0 shr lvl
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        for (row in 0..(h - 1) / Seamless.TILE) for (col in 0..(w - 1) / Seamless.TILE) {
            val f = Seamless.tile(cont, variant, lvl, col, row)
            if (f.isFile) BitmapFactory.decodeFile(f.path)?.let { cv.drawBitmap(it, (col * Seamless.TILE).toFloat(), (row * Seamless.TILE).toFloat(), null) }
        }
        out
    }

    private fun world(c: Canvas, r: RectF, all: Boolean, look: Look, tMs: Long?) {
        val a = continent("all")
        val base = continent("base")
        if (a == null || (!all && base == null)) { worldDrawn(c, r, all); return }
        // the middle 2:1 of the continent's picture (its smallest level: under 2.2x into the well)
        val w = a.width.toFloat(); val h = a.height.toFloat()
        val sw = min(w, h * 2f) * 0.92f; val sh = sw / 2f
        val src = RectF((w - sw) / 2f, (h - sh) / 2f, (w + sw) / 2f, (h + sh) / 2f)
        val srcR = Rect(src.left.toInt(), src.top.toInt(), src.right.toInt(), src.bottom.toInt())
        c.drawBitmap(if (all) a else base!!, srcR, r, pBmp)
        if (!all) {
            // the zones explored (a sample: a patch round the middle-left) the way the Map room lays them over the base
            val save = c.save()
            path.reset(); path.addCircle(r.left + r.width() * 0.38f, r.top + r.height() * 0.55f, r.height() * 0.32f, Path.Direction.CW)
            c.clipPath(path); c.drawBitmap(a, srcR, r, pBmp)
            c.restoreToCount(save)
            pF.color = Color.argb(90, 10, 8, 6); c.drawRect(r, pF)
        }
    }

    /** no map art on the phone yet: our own drawing of a continent (no words on the picture), the fog over it when Explored only */
    private fun worldDrawn(c: Canvas, r: RectF, all: Boolean) {
        val s = k(r)
        pF.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.rgb(28, 46, 62), Color.rgb(16, 28, 40), Shader.TileMode.CLAMP)
        c.drawRect(r, pF); pF.shader = null
        fun land(vararg p: Float) {
            path.reset(); path.moveTo(r.left + p[0] * s, r.top + p[1] * s)
            var i = 2
            while (i + 3 < p.size) { path.quadTo(r.left + p[i] * s, r.top + p[i + 1] * s, r.left + p[i + 2] * s, r.top + p[i + 3] * s); i += 4 }
            path.close()
            pF.color = Color.rgb(112, 120, 74); c.drawPath(path, pF)
            pS.color = Color.rgb(196, 170, 110); pS.strokeWidth = 1.6f * s; c.drawPath(path, pS)
        }
        land(40f, 14f, 70f, 6f, 92f, 22f, 104f, 46f, 96f, 70f, 90f, 96f, 66f, 98f, 40f, 98f, 34f, 70f, 26f, 40f, 40f, 14f)
        land(130f, 20f, 160f, 10f, 184f, 30f, 198f, 56f, 182f, 76f, 166f, 94f, 146f, 84f, 126f, 70f, 132f, 44f, 128f, 26f, 130f, 20f)
        if (!all) {
            val save = c.save()
            path.reset(); path.fillType = Path.FillType.EVEN_ODD
            path.addRect(r, Path.Direction.CW); path.addCircle(r.left + 70f * s, r.top + 58f * s, 26f * s, Path.Direction.CW)
            pF.color = Color.argb(170, 8, 8, 10); c.drawPath(path, pF)
            path.fillType = Path.FillType.WINDING
            c.restoreToCount(save)
        }
    }

    // ---- Message input: the companion's own keyboard (its left keys) or the quick chat's phrase rows

    private var kb: Bitmap? = null
    private var kbBig = false

    private fun input(c: Canvas, ctx: Context, r: RectF, quick: Boolean, big: Boolean) {
        if (quick) {
            val kk = r.width() / 432f
            c.save(); c.translate(r.left, r.top); c.scale(kk, kk)
            pF.color = Theme.panel; c.drawRect(0f, 0f, 432f, 216f, pF)
            val phrases = listOf("Hello!", "Thanks!", "Ready?", "On my way")
            for ((i, ph) in phrases.withIndex()) {
                val rr = RectF(14f, 10f + i * 50f, 418f, 56f + i * 50f)
                UiKit.menuRow(c, rr, Tr.t(ph), i == 0, size = 26f)
            }
            c.restore()
            return
        }
        // the keyboard's left 475 x 238 (the 2.2x rule), drawn once per size and kept with the crops
        if (kb == null || kbBig != big) {
            val w = if (big) BIG_W.toInt() else W.toInt(); val h = if (big) BIG_H.toInt() else H.toInt()
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(b)
            val fh = ChatKeyboard.fullHeight().toFloat()
            val s = w / WIN_W
            cv.scale(s, s); cv.translate(-8f, -(fh - WIN_H).coerceAtLeast(0f) * 0.15f)
            runCatching { PreviewStage.keyboard(ctx, cv, HOME_W, fh) }
            kb = b; kbBig = big
        }
        kb?.let { blit(c, it, r) }
    }

    // ---- the fallback: the old "What it does" drawing, re-framed 2:1

    private val glyph = SettingsPreview.Glyph { c, action, set, cx, cy, size ->
        val name = when (set) {
            1 -> if (action == "select") "ltr_view" else "ltr_$action"
            2 -> "shp_" + when (action) { "a" -> "cross"; "b" -> "circle"; "x" -> "square"; "y" -> "triangle"; "select" -> "share"; else -> action }
            else -> if (action == "select") "rev_minus" else "rev_$action"
        }
        UiKit.pad(c, name, cx, cy, size)
    }

    /** the settings now, as the old panel's drawings take them */
    private fun state(look: Look) = SettingsPreview.State(
        diamonds = HomeSettings.rest == HomeRest.DIAMONDS, combatGrow = HomeSettings.combatGrow, swing = HomeSettings.swingTimer,
        questOverMap = HomeSettings.questOverMap, toolbar = look.env.dpad?.want,
        glyphSet = CastSettings.ButtonLayout.values().indexOf(CastSettings.buttonLayout),
        holdTalk = HomeSettings.holdTalk(look.env.who), talkSendNow = HomeSettings.talkSendNow(look.env.who),
        miniOutYd = Settings.minimapYd, miniInYd = Settings.minimapIndoorYd, quickChat = Settings.chatDefaultInput == "quick",
        clockHearth = HomeSettings.clockHearth, clockTeleport = HomeSettings.clockTeleport, clockGold = HomeSettings.clockGold,
        clockBags = HomeSettings.clockBags, clockAmmo = HomeSettings.clockAmmo,
        castBar = CastStripModel.Side.values().indexOf(HomeSettings.castBarSide),
        castCombatHide = HomeSettings.castBarCombat == CastStripModel.Combat.HIDE, worldMapAll = Settings.worldMapFog == "all")

    /** the panel's own picture box (438 x 520): its drawing scaled to the picture's height, the middle 2:1 of it shown */
    private fun fallback(c: Canvas, r: RectF, row: String, look: Look, tMs: Long?) {
        val pw = 438f; val ph = 300f
        val kk = r.width() / pw
        c.save(); c.translate(r.left, r.top + (r.height() - ph * kk) / 2f); c.scale(kk, kk)
        SettingsPreview.draw(c, RectF(0f, 0f, pw, ph), row, state(look), tMs ?: 0L, glyph)
        c.restore()
    }

    private fun fit(p: Paint, s: String, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        val n = p.breakText(s, true, (maxW - p.measureText("…")).coerceAtLeast(0f), null)
        return s.substring(0, n).trimEnd() + "…"
    }
}
