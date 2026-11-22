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
 *    crop; the focused quest as Home's two columns, the chosen one wide with Home's own UiKit.questCard in it; the clock band drawn by
 *    Home's own UiKit.clockChip and UiKit.clockBand with sample values; the tap-to-cast bar as Home's two columns with real spell icons
 *    under the chosen one(s). The CHAT is always drawn (round 3: a crop of the half-size stage was soft in the band and cut lines through
 *    the middle): sample lines laid the way Home's pane lays them ([chat]); the map from the minimap's own tiles ([terrain]), else our
 *    drawing ([mapDrawn]), never a blown-up crop;
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
     * THE 1x STAGE (the design's section 3.1, critic C5: "Render at 1x into ONE reused 1240 x 1007 bitmap (about 5 MB, freed with the
     * cache), crop, then drop"): PreviewStage.render at Home's own size into one bitmap kept for the last look asked, so the crops of one
     * look come from one render. It replaced the half-size cached Home, whose controller crops were soft (a 202 px crop blown up into
     * the band's 438). The bitmap goes with the crops ([drop]: a trim, Settings' Bottom screen out of view).
     */
    object FullStage : Stage {
        private var bmp: Bitmap? = null
        private var key: String? = null
        private var boxes: Map<String, RectF>? = null
        override fun frame(ctx: Context, look: HomeView.Companion.Look, fight: Boolean, toolbar: Boolean?): Frame? {
            // the theme in use too (General's Theme tile crops this render: a theme change must render Home again)
            val k = "$look/$fight/$toolbar/${Theme.cur.id}/${Theme.cur.hashCode()}"
            val b = bmp ?: Bitmap.createBitmap(PreviewStage.W, PreviewStage.H, Bitmap.Config.ARGB_8888).also { bmp = it }
            if (k != key || boxes == null) {
                key = k
                boxes = PreviewStage.render(ctx, look, fight, toolbar, b)
            }
            return boxes?.let { Frame(b, 1f, it) }
        }
        /** the render goes (the next crop renders again: the game's documents moved on) */
        fun stale() { key = null }
        fun drop(): Long { val n = bmp?.byteCount?.toLong() ?: 0L; bmp = null; key = null; boxes = null; return n }
    }
    var stage: Stage = FullStage

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
    // Light 8 (LIGHT8_PLAN.md change 1): the crops counted on the 8 GB Thors' memory line ("bottompics"); registered only
    init { MemWatch.source8("bottompics") { small.bytes + large.bytes } }

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
        // the queue emptied: the next ask renders Home again (its documents keep coming)
        if (pending.isEmpty()) FullStage.stale()
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
    fun drop(): Long { pending.clear(); failed.clear(); madeAt.clear(); redone.clear(); kb = null; worldArt.clear(); spotLooked = false; spotMemo = null; return small.clear() + large.clear() + FullStage.drop() }

    private val viewers = HashSet<Int>()
    /** a Bottom screen tile view came into view ([on]) or went; the last one going drops the crops */
    fun shown(id: Int, on: Boolean) { if (on) viewers += id else if (viewers.remove(id) && viewers.isEmpty()) drop() }

    init {
        MemWatch.onTrim("bottomTiles") { level -> if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) drop() else 0L }
    }

    // ---------------------------------------------------------------- General's Theme tile (the user's ruling, 2026-11-17)

    /**
     * General's Theme tile uses the REAL Home miniature (the user's ruling, 2026-11-17: "General's Theme tile uses the real Home miniature
     * (the same renderer as the theme maker preview and Bottom tab tiles, with its reuse/free rules; the drawn mini room only as the
     * before-first-data fallback)"): a crop of the 1x stage ([FullStage], PreviewStage.render in the theme in use, the player's look, no
     * fight) made by this object's queue ([crop] / [pump]) into the large cache, so it is freed as the Bottom tiles' crops are (a
     * running-low trim, the last viewer out of view: [shown]). The window is Home's whole width ([themeWindow]): the controller over the
     * top of the minimap and chat panes, their header bars whole. null = not made yet (the caller pumps and draws the mini room).
     */
    fun themeHome(w: Int, h: Int, prio: Int = 0): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val look = player()
        return crop(themeKey(look, w, h), Shot(look, false, null, { b -> themeWindow(b, w / h.toFloat()) }, w, h), prio)
    }

    /** the Theme tile's crop is in the cache (the tile draws it at once) */
    fun themeHomeReady(w: Int, h: Int): Boolean = (if (w > W) large else small)[themeKey(player(), w, h)] != null

    /** the theme in use is in the key: a theme change makes a new crop (the old one ages out of the cache) */
    private fun themeKey(look: HomeView.Companion.Look, w: Int, h: Int) = "theme|${Theme.cur.id}|${Theme.cur.hashCode()}|$look|${w}x$h"

    /** the panes' header bars kept whole under the controller (Home's px below the map box's top) */
    const val THEME_HEAD = 72f

    /**
     * the Theme tile's window ([aspect] = its width / height): Home's whole width, as tall as the aspect makes it, its foot [THEME_HEAD]
     * under the panes' top (the 2026-11-17 review: a crop that cut the map's header in half read as no theme at all); null = no map box
     * in the render
     */
    fun themeWindow(b: Map<String, RectF>, aspect: Float): RectF? {
        val map = b["map"] ?: return null
        val wh = HOME_W / aspect
        val top = (map.top + THEME_HEAD - wh).coerceIn(0f, max(0f, homeH() - wh))
        return RectF(0f, top, HOME_W, top + wh)
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
    /**
     * the fight's window (round 3, the verifier: "cut the A button in half at the right edge and the Y badge at the bottom"): the
     * widest the 2.2x rule allows ([WIN_W]), centred on the grown diamond's middle column (X over B) so X, Y and A stand whole with
     * their badges; B's lower half runs out of the foot, faded into the well ([sides]' foot), the swing line on that faded strip
     */
    private fun fightFaces(b: Map<String, RectF>) = b["controller"]?.let {
        val x = (it.left + it.width() * FIGHT_CX - WIN_W / 2f).coerceIn(0f, max(0f, HOME_W - WIN_W))
        RectF(x, it.top + FIGHT_TOP, x + WIN_W, it.top + FIGHT_TOP + WIN_H)
    }
    /** the grown diamond's middle (X over B) across the controller band, and the window's top in Home's px (X's ring just inside) */
    private const val FIGHT_CX = 0.712f
    private const val FIGHT_TOP = 36f

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
            BottomTiles.LAYOUT, BottomTiles.HOME_RESET -> {
                // movable Home (final_11): the live miniature of Home in the active layout (Reset Home: the record Reset would make), the
                // page's whole width from the band down
                val lay = HomeSettings.layout.let { if (id == BottomTiles.HOME_RESET) it.classic() else it }
                val b = crop("layout|${HomeLayoutJson.json(lay)}|$sz", shot(player().copy(layout = lay), false, null,
                    { _ -> RectF(0f, 0f, 1240f, minOf(homeH(), 1240f * ch / cw)) }), prio)
                if (b != null) blit(c, b, r) else fallback(c, r, "Home layout", look, tMs)
            }
            BottomTiles.REST -> {
                val rest = HomeSettings.rest
                val b = crop("rest|$rest|$sz", shot(player(rest = rest), false, null, ::faces), prio)
                if (b != null) { blit(c, b, r); sides(c, r) } else fallback(c, r, "Controller on Home", look, tMs)
            }
            BottomTiles.COMBAT -> {
                // In fights: Grow / Same size / Top screen (the band keeps its rest box as Same size does; the controls go up: the plate)
                val band = HomeSettings.fightBand
                val grow = band == HomeLayout.FightBand.GROW
                val restB = crop("combat|rest|$sz", shot(player(), false, null, ::fightFaces), prio)
                val fightB = crop("combat|$grow|$sz", shot(player(grow = grow), true, null, ::fightFaces), prio)
                if (restB == null || fightB == null) { fallback(c, r, "Combat layout", look, tMs); return }
                // the tile: the fight (frame 0 of the loop); the band opens on the fight too (as the tile shows it), then rest, the fight
                val k = if (tMs == null) 1f else loop(t + 4.4f * 0.45f, 4.4f)
                blit(c, restB, r)
                if (k > 0f) { pBmp.alpha = (255 * k).toInt(); blit(c, fightB, r); pBmp.alpha = 255 }
                sides(c, r, FIGHT_SIDE, FOOT)
                if (k > 0f) UiKit.combatGlow(c, r, 0.75f * k, r.height() * 0.16f)
                // Top screen: the "up" plate on the fight's frames (the Wings are on the game screen)
                if (band == HomeLayout.FightBand.TOP && k > 0f) {
                    // in the frame's upper left corner, clear of the faces (the band's own cue sits on the clock spot)
                    val s = r.height() * 0.26f
                    HomeView.upPlate(c, r.left + s * 0.5f + r.height() * 0.08f, r.top + s * 0.5f + r.height() * 0.08f, s, k, pF, path)
                }
            }
            BottomTiles.SWING -> {
                val on = HomeSettings.swingTimer
                val b = crop("swing|fight|$sz", shot(player(swing = false), true, null, ::fightFaces), prio)
                if (b == null) { fallback(c, r, "Swing timer", look, tMs); return }
                blit(c, b, r); sides(c, r, FIGHT_SIDE, FOOT)
                UiKit.combatGlow(c, r, 0.55f, r.height() * 0.14f)
                if (on) swingLine(c, r, if (tMs == null) 0.62f else ((t + 0.62f * 2.6f) % 2.6f) / 2.6f)   // the band opens where the tile is
            }
            BottomTiles.QUEST -> quest(c, r)
            BottomTiles.CLOCK -> clock(c, r)
            BottomTiles.TOOLBAR -> {
                val on = look.env.dpad?.want == true
                val b = crop("toolbar|$on|$sz", shot(player(), false, on, ::dpad), prio)
                if (b != null) { blit(c, b, r); sides(c, r) } else toolbarDrawn(c, r)
            }
            BottomTiles.TALK -> talk(c, r, look, tMs)
            BottomTiles.CASTBAR -> castBar(c, r, tMs)
            BottomTiles.RANK -> ranks(c, r, HomeSettings.rankBadges)
            BottomTiles.XP -> xpTrack(c, r, HomeSettings.layout.xpBar)
            BottomTiles.ZOOM -> zoom(c, r, Settings.minimapYd, Settings.minimapIndoorYd, look.zoomIndoor)
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
    private fun sides(c: Canvas, r: RectF, frac: Float = 0.1f, foot: Float = 0f) {
        val w = r.width() * frac
        val clear = Color.argb(0, Color.red(WORLD), Color.green(WORLD), Color.blue(WORLD))
        pF.shader = LinearGradient(r.left, 0f, r.left + w, 0f, WORLD, clear, Shader.TileMode.CLAMP)
        c.drawRect(r.left, r.top, r.left + w, r.bottom, pF)
        pF.shader = LinearGradient(r.right - w, 0f, r.right, 0f, clear, WORLD, Shader.TileMode.CLAMP)
        c.drawRect(r.right - w, r.top, r.right, r.bottom, pF)
        if (foot > 0f) {
            // the foot: what runs out of the window's bottom fades into the well (never a hard cut through a button)
            val h = r.height() * foot
            pF.shader = LinearGradient(0f, r.bottom - h, 0f, r.bottom, clear, WORLD, Shader.TileMode.CLAMP)
            c.drawRect(r.left, r.bottom - h, r.right, r.bottom, pF)
            // the fight's top-right corner: the R trigger box's edge reaches in there (above A, which starts half way down), covered
            pF.shader = LinearGradient(r.right - r.width() * CORNER_W, 0f, r.right - r.width() * CORNER_W * 0.55f, 0f, clear, WORLD, Shader.TileMode.CLAMP)
            c.drawRect(r.right - r.width() * CORNER_W, r.top, r.right, r.top + r.height() * CORNER_H, pF)
        }
        pF.shader = null
    }
    /** the fight pictures' top-right corner covered (the R trigger box): its width and height, fractions of the picture */
    private const val CORNER_W = 0.14f
    private const val CORNER_H = 0.42f
    /** the fight pictures' foot fade (B's lower half, under the swing line); their sides barely (Y and A reach the window's edges) */
    private const val FOOT = 0.3f
    private const val FIGHT_SIDE = 0.035f

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
        // on the faded foot, under X / Y / A and their badges (never over a badge)
        val y = r.bottom - 9f * s
        val l = r.left + 34f * s; val rr = r.right - 34f * s
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
        // short words (round 3: "Vile Familiar slain: 4/12" ran out of the card at the tile's size): Durotar's "Lazy Peons"
        UiKit.QuestCard("Lazy Peons", 4, Theme.questGreen, listOf("Peons Awoken: 2/5" to false), number = 1)
    }

    /**
     * round 3 (the verifier: "the quest card text ... is about 6 px in the tile well", "the chat column is cut mid-word"): the CHOSEN
     * column is the wide one (70 %), Home's own card in it at 0.42 of the Map room's type (its title about 12 px in the tile, near Home's size in
     * the band); the other column narrow and dimmed. Both columns are DRAWN (no crop): the chat as Home's pane lays it (whole wrapped
     * lines from the foot, [chat]), the map from the minimap's own tiles ([terrain]) or our drawing ([mapDrawn])
     */
    private fun quest(c: Canvas, r: RectF) {
        val over = HomeSettings.questOverMap
        val s = k(r)
        val gap = 6f * s
        val wide = (r.width() - 3f * gap) * 0.7f; val narrow = r.width() - 3f * gap - wide
        val chatF = RectF(r.left + gap, r.top + gap, r.left + gap + (if (over) narrow else wide), r.bottom - gap)
        val mapF = RectF(chatF.right + gap, r.top + gap, r.right - gap, r.bottom - gap)
        // the card at 0.42 of the Map room's type (UiKit.questCard's scale 1 = its 400 px rows)
        val sc = 0.42f * s
        for ((i, f) in listOf(chatF, mapF).withIndex()) {
            val isMap = i == 1
            val chosen = isMap == over
            c.save(); c.clipRect(f)
            val cardH = if (chosen) UiKit.questCardHeight(SAMPLE_QUEST, sc) else 0f
            val card = RectF(f.left + 2f * s, f.top + 2f * s, f.right - 2f * s, f.top + 2f * s + cardH)
            if (isMap) {
                if (!terrain(c, f, 150f)) mapDrawn(c, f)
                val ay = if (chosen) (card.bottom + f.bottom) / 2f else f.centerY()
                arrow(c, f.centerX(), ay, 20f * s)
            } else {
                pF.color = Theme.field; c.drawRect(f, pF)
                chat(c, RectF(f.left, if (chosen) card.bottom else f.top, f.right, f.bottom), CHAT_TILE * s)
            }
            if (chosen) UiKit.questCard(c, card, SAMPLE_QUEST, scale = sc)
            c.restore()
            if (!chosen) { pF.color = Color.argb(150, 0, 0, 0); c.drawRect(f, pF) }
            pS.color = if (chosen) Theme.gold else Theme.bronzeDim; pS.strokeWidth = (if (chosen) 2.4f else 1.2f) * s
            c.drawRect(f, pS)
        }
    }

    // ---- the chat, drawn the way Home's pane lays it (HomeView.ChatPane: wrapped whole lines from the foot, the top message cut to the
    // ---- lines that fit with an ellipsis), sample lines in the game's channel colours (ChatView's, copied: they are private there)

    private class ChatLn(val ch: String, val who: String, val text: String)
    /** sample lines (the ToS ruling: sample values only) */
    private val SAMPLE_CHAT = listOf(ChatLn("say", "Grukk", "anyone up for Burning Blade Coven?"), ChatLn("guild", "Zalaya", "grats on 6!"),
        ChatLn("party", "Mogra", "I'll tank, pull on 3"), ChatLn("system", "", "You have learned a new spell: Earth Shock."),
        ChatLn("say", "Torrik", "lfg Razor Hill quests"))
    private val CH_COLOUR = mapOf("say" to Color.WHITE, "guild" to Color.parseColor("#40FF40"), "party" to Color.parseColor("#AAABFE"),
        "system" to Color.parseColor("#FFFF00"))
    /** the chat's type in the tile's 216 px space (the band's is the same, scaled) */
    private const val CHAT_TILE = 11f
    /** the sample player's own line (Hold to talk) and its words */
    private const val SAMPLE_ME = "Kargath"
    private const val SAMPLE_SAID = "on my way!"

    /** [lines] into [r] at [size] px, the newest at the foot; [lit] = that line's index lit (just sent) */
    private fun chat(c: Canvas, r: RectF, size: Float, lines: List<ChatLn> = SAMPLE_CHAT, lit: Int = -1) {
        val tp = android.text.TextPaint(UiKit.bodyPaint(size, Theme.text, shadow = size > 14f))
        val pad = size * 0.45f
        val w = (r.width() - 2f * pad).toInt()
        if (w < 10) return
        val top = r.top + pad * 0.5f
        var y = r.bottom - pad * 0.6f
        fun build(l: ChatLn, max: Int): android.text.StaticLayout {
            val s = if (l.who.isEmpty()) l.text else "${l.who}: ${l.text}"
            val sp = android.text.SpannableString(s)
            sp.setSpan(android.text.style.ForegroundColorSpan(CH_COLOUR[l.ch] ?: Theme.textDim), 0, s.length, 0)
            return android.text.StaticLayout.Builder.obtain(sp, 0, s.length, tp, w).setIncludePad(false).setLineSpacing(0f, 1.12f)
                .setMaxLines(max).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        }
        c.save(); c.clipRect(r)
        for (i in lines.indices.reversed()) {
            var lay = build(lines[i], Int.MAX_VALUE)
            if (y - lay.height < top) {
                // whole lines only (Home's rule: a line cut through the middle was seen on the Thor)
                val lineH = lay.height.toFloat() / lay.lineCount.coerceAtLeast(1)
                val n = ((y - top) / lineH).toInt()
                if (n < 1) break
                lay = build(lines[i], n)
            }
            y -= lay.height
            if (i == lit) { val lw = (0 until lay.lineCount).maxOf { lay.getLineWidth(it) }; pF.color = Theme.focusA(80); c.drawRoundRect(RectF(r.left + pad * 0.4f, y - size * 0.1f, r.left + pad + lw + pad * 0.6f, y + lay.height + size * 0.1f), size * 0.25f, size * 0.25f, pF) }
            c.save(); c.translate(r.left + pad, y); lay.draw(c); c.restore()
            y -= size * 0.22f
            if (y < top) break
        }
        c.restore()
    }

    /** no minimap art on the phone: our own drawing of the ground round the player (earth, a road, darker patches; no words) */
    private fun mapDrawn(c: Canvas, r: RectF) {
        val w = r.width(); val h = r.height()
        pF.shader = LinearGradient(r.left, r.top, r.right, r.bottom, Color.rgb(138, 96, 58), Color.rgb(100, 66, 40), Shader.TileMode.CLAMP)
        c.drawRect(r, pF); pF.shader = null
        pF.color = Color.argb(60, 52, 30, 16)
        for ((fx, fy, fr) in listOf(Triple(0.18f, 0.28f, 0.24f), Triple(0.78f, 0.72f, 0.3f), Triple(0.62f, 0.14f, 0.16f), Triple(0.3f, 0.86f, 0.18f)))
            c.drawCircle(r.left + w * fx, r.top + h * fy, min(w, h) * fr, pF)
        path.reset(); path.moveTo(r.left - 4f, r.top + h * 0.82f)
        path.cubicTo(r.left + w * 0.3f, r.top + h * 0.5f, r.left + w * 0.62f, r.top + h * 0.78f, r.right + 4f, r.top + h * 0.22f)
        pS.color = Color.rgb(182, 146, 98); pS.strokeWidth = max(2f, min(w, h) * 0.05f); c.drawPath(path, pS)
        pF.color = Color.rgb(84, 60, 40)
        for ((fx, fy) in listOf(0.44f to 0.42f, 0.52f to 0.36f, 0.47f to 0.3f)) {
            val q = min(w, h) * 0.05f
            c.drawRect(r.left + w * fx - q, r.top + h * fy - q, r.left + w * fx + q, r.top + h * fy + q, pF)
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
        if (HomeSettings.clockBags) items += UiKit.BandItem.Icon(Icons.get(BAG_ICON), "52/80")
        if (HomeSettings.clockAmmo) items += UiKit.BandItem.Icon(Icons.get(AMMO_ICON), "812/1000")
        val floor = 6f + ClockBand.chipBottom(gold) + ClockBand.CHIP_PAD
        // the row 1.6x Home's size here (the tile is small), centred under the chip
        c.save(); c.translate(cx, floor); c.scale(1.6f, 1.6f); c.translate(-cx, -floor)
        if (items.isNotEmpty()) UiKit.clockBand(c, floatArrayOf(cx - 160f, floor, cx + 160f, floor + ClockBand.CELL_H, cx, floor + ClockBand.CELL_H / 2f), items, floor)
        c.restore()
        c.restore()
    }

    // ---- Hold to talk: the real chat behind, the kit's chat box with the mic, the D-pad's chat arrow (Left; Right after a swap: the add-on's
    // ---- `d`, HoldTalk.chatArrowRight - the row's own words) with Home's hold ring; the band: hold, the
    // ---- words in the box, then sent into the chat (Send now) or waiting for A (Show, A sends)

    private fun talk(c: Canvas, r: RectF, look: Look, tMs: Long?) {
        val who = look.env.who
        val on = HomeSettings.holdTalk(who)
        val now = HomeSettings.talkSendNow(who)
        val kk = r.width() / 432f
        val chatH = 140f
        val t = if (tMs == null) 1.6f else (tMs / 1000f) % 4f                     // the tile: the hold done, the words in the box
        val sent = on && now && t >= 2.1f
        c.save(); c.translate(r.left, r.top); c.scale(kk, kk)
        pF.color = WORLD; c.drawRect(0f, 0f, 432f, 216f, pF)
        // the upper part: the chat's newest lines, drawn as Home's pane lays them (whole lines; Send now: the sample player's line
        // lands as the newest, lit a moment)
        val lines = if (sent) SAMPLE_CHAT + ChatLn("say", SAMPLE_ME, SAMPLE_SAID) else SAMPLE_CHAT
        chat(c, RectF(0f, 0f, 432f, chatH - 4f), 22f, lines, lit = if (sent) lines.size - 1 else -1)
        // the lower part: the game's chat box on the frame, the chat's D-pad arrow with Home's hold ring, the mic in the box
        pF.shader = LinearGradient(0f, chatH - 30f, 0f, chatH, Color.argb(0, 0, 0, 0), Color.argb(230, 10, 10, 12), Shader.TileMode.CLAMP)
        c.drawRect(0f, chatH - 30f, 432f, chatH, pF); pF.shader = null
        pF.color = Color.argb(230, 10, 10, 12); c.drawRect(0f, chatH, 432f, 216f, pF)
        val hold = (t / 1.2f).coerceIn(0f, 1f)                                   // the ring fills while the arrow is held
        val gx = 46f; val gy = 178f
        // fix round 1 (P5: the pictures never lie): the arrow hold to talk answers to now (Right once the toolbar's `d` is mirrored)
        UiKit.pad(c, talkGlyph(look.env.dpad?.dirs), gx, gy, 58f, if (on) 255 else 150)
        if (on) UiKit.holdRing(c, gx, gy, 36f, hold)
        val box = RectF(92f, 154f, 424f, 202f)
        UiKit.inputBox(c, box)
        VoiceUi.mic(c, box.right - 26f, box.centerY(), 34f, if (on) Theme.gold else Theme.dim, crossed = !on)
        if (on && t >= 1.3f) {
            // the words heard, in the box (sent: the box empties, the line is the chat's newest above)
            val p = UiKit.bodyPaint(24f, Theme.text, shadow = false)
            if (!sent) c.drawText(fit(p, SAMPLE_SAID, box.width() - 110f), box.left + 14f, box.centerY() + 8f, p)
            if (!now) UiKit.pad(c, UiKit.okGlyph(), box.right - 72f, box.centerY(), 36f)
        }
        c.restore()
    }

    /** the Hold to talk tile's arrow: the D-pad side the chat is on in the add-on's `d` ([HoldTalk.chatArrowRight]) */
    fun talkGlyph(dirs: Map<Int, String>?): String = if (HoldTalk.chatArrowRight(dirs)) "gen_right" else "gen_left"

    // ---- Tap-to-cast bar: Home's two columns (the real chat, the minimap's own art), the bar of real spell icons under the chosen
    // ---- one(s); In combat: Hide slides it away

    /** a level-6 Shaman's first spells (CastBarAsk's picture: sample icons) */
    private val SPELL_ICONS = intArrayOf(136048, 136026, 136052, 136051, 136086, 136102, 136015, 135824)

    private fun castBar(c: Canvas, r: RectF, tMs: Long?) {
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
        // Above chat / Above map: the bar at its column's TOP (the column starts under it), sliding UP for Hide (Home: behind the band)
        val atTop = HomeLayoutRules.shown(HomeSettings.layout).cast.top
        val top = 0f
        val chatCol = if (atTop) RectF(g, top + g + (if (left) room else 0f), 216f - g / 2f, 216f - g)
            else RectF(g, top + g, 216f - g / 2f, 216f - g - (if (left) room else 0f))
        val map = if (atTop) RectF(216f + g / 2f, top + g + (if (right) room else 0f), 432f - g, 216f - g)
            else RectF(216f + g / 2f, top + g, 432f - g, 216f - g - (if (right) room else 0f))
        c.save(); c.translate(r.left, r.top); c.scale(kk, kk)
        pF.color = WORLD; c.drawRect(0f, 0f, 432f, 216f, pF)
        // the chat column, drawn as Home's pane lays it (its foot: the newest lines stay at the bottom as the column grows)
        pF.color = Theme.field; c.drawRect(chatCol, pF)
        chat(c, chatCol, 20f)
        // the map column: the minimap's own tiles (our drawing without them), the arrow in its middle
        c.save(); c.clipRect(map)
        if (!terrain(c, map, 160f)) mapDrawn(c, map)
        arrow(c, map.centerX(), map.centerY(), 26f)
        c.restore()
        for (col in listOf(chatCol, map)) { pS.color = Theme.bronzeDim; pS.strokeWidth = 2f; c.drawRect(col, pS) }
        fun bar(l: Float, rr: Float, n: Int, first: Int) {
            val dy = (barH + g + 2f) * slide
            if (slide >= 1f) return
            val well = if (atTop) RectF(l, g - dy, rr, g + barH - dy) else RectF(l, 216f - g - barH + dy, rr, 216f - g + dy)
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
            CastStripModel.Side.LEFT -> bar(chatCol.left, chatCol.right, 4, 0)
            CastStripModel.Side.RIGHT -> bar(map.left, map.right, 4, 4)
            CastStripModel.Side.BOTH -> bar(chatCol.left, map.right, 8, 0)
            CastStripModel.Side.OFF -> {}
        }
        if (fight > 0f) UiKit.combatGlow(c, RectF(0f, 0f, 432f, 216f), 0.8f * fight, 28f)
        c.restore()
    }

    // ---- Spell rank badges (2026-11-20): four of Home's spell buttons with real icons, #471's "R3" chips on them while On (the top
    // ---- known rank gold, a lower one grey, as UiKit.spellRank draws them on Home), bare while Off

    private fun ranks(c: Canvas, r: RectF, on: Boolean) {
        val kk = r.width() / 432f
        c.save(); c.translate(r.left, r.top); c.scale(kk, kk)
        pF.color = WORLD; c.drawRect(0f, 0f, 432f, 216f, pF)
        val slot = 84f; val gap = 20f
        val x0 = (432f - 4 * slot - 3 * gap) / 2f; val y0 = (216f - slot) / 2f
        val badges = listOf(SpellRanks.Badge(4, true), SpellRanks.Badge(2, true), SpellRanks.Badge(2, false), SpellRanks.Badge(3, true))
        for (j in 0 until 4) {
            val box = RectF(x0 + j * (slot + gap), y0, x0 + j * (slot + gap) + slot, y0 + slot)
            val ic = Icons.get(SPELL_ICONS[j])
            if (ic != null) c.drawBitmap(ic, null, box, pBmp)
            else { pF.color = Color.rgb(60 + 20 * j, 90, 140 - 10 * j); c.drawRect(box, pF) }
            pS.color = Theme.bronzeDim; pS.strokeWidth = 2f; c.drawRect(box, pS)
            if (on) UiKit.spellRank(c, box, badges[j])
        }
        c.restore()
    }

    /** the XP bar on Home (1518 build 2): the room bar's top, close up - its XP track over the rooms' row (Show), or the row alone with
     *  Home reaching down to it (Hide) */
    private fun xpTrack(c: Canvas, r: RectF, on: Boolean) {
        val kk = r.width() / 432f
        c.save(); c.translate(r.left, r.top); c.scale(kk, kk)
        pF.color = WORLD; c.drawRect(0f, 0f, 432f, 216f, pF)
        val top = if (on) 70f else 104f
        pF.color = Theme.panel; c.drawRect(0f, top, 432f, 216f, pF)
        if (on) UiKit.track(c, RectF(0f, top, 432f, top + 34f), "xp", 0.62f, 0.74f, 5, 0f, 0f, 8f, 1.5f)
        // three rooms' discs under it (the bar's row)
        val cy = 160f
        for (j in 0 until 3) {
            val cx = 64f + j * 104f
            pF.color = Theme.raised; c.drawCircle(cx, cy, 34f, pF)
            pS.color = Theme.bronzeDim; pS.strokeWidth = 2.5f; c.drawCircle(cx, cy, 34f, pS)
        }
        c.restore()
    }

    // ---- Minimap zoom: the minimap's own tiles round the player, the yards each choice shows framed (the line the cursor is on lit,
    // ---- the rest of the picture dimmed), the yards under it

    /** [out] / [inn] yards across, on the minimap's own tiles (our drawing while none is on the phone) */
    private fun zoom(c: Canvas, r: RectF, out: Int, inn: Int, indoor: Boolean) {
        val s = k(r)
        // the picture's height in yards: twice the wider choice (its frame half the picture across), never closer than 120 yd (the
        // tiles' 0.96 px / yd at most about 1.8x in the band)
        val ydH = max(120f, max(out, inn) * 2f)
        if (!terrain(c, r, ydH)) mapDrawn(c, r)                                   // no minimap art: our drawing (never a blown-up crop)
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
