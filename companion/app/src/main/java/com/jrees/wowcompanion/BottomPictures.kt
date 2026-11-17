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
            val mb = PreviewStage.mapBox ?: return null
            val w = HOME_W; val h = homeH()
            val map = RectF(mb.left * w, mb.top * h, mb.right * w, mb.bottom * h)
            val chat = RectF(w - map.right, map.top, w - map.left, map.bottom)
            return Frame(b, b.width / w, mapOf("map" to map, "chat" to chat, "controller" to RectF(0f, 0f, w, map.top)))
        }
    }
    var stage: Stage = HalfStage

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
    fun drop(): Long { pending.clear(); failed.clear(); madeAt.clear(); redone.clear(); kb = null; worldArt.clear(); return small.clear() + large.clear() }

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
    private fun faces(b: Map<String, RectF>) = b["controller"]?.let { win(it.left + it.width() * 0.715f, it.top + WIN_H / 2f) }
    private fun dpad(b: Map<String, RectF>) = b["controller"]?.let { win(it.left + it.width() * 0.27f, it.top + WIN_H / 2f) }
    /** the top of a column (the focused quest's frames): [QW] x [QH] of Home at its top-left */
    private const val QW = 236f
    private const val QH = 220f
    private fun colTop(b: Map<String, RectF>, key: String) = b[key]?.let { RectF(it.left, it.top, it.left + QW, it.top + QH) }
    /** the chat's newest lines (its foot) when the card is not on it */
    private fun colFoot(b: Map<String, RectF>, key: String) = b[key]?.let { RectF(it.left, it.bottom - QH, it.left + QW, it.bottom) }
    /** the minimap, [k] of the window (indoors: narrower, the same map closer) */
    private fun mapWin(b: Map<String, RectF>, k: Float) = b["map"]?.let { win(it.centerX(), it.centerY(), WIN_W * k) }

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
        pF.color = Theme.ink; c.drawRect(r, pF)
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
                if (b != null) blit(c, b, r) else fallback(c, r, "Controller on Home", look, tMs)
            }
            BottomTiles.COMBAT -> {
                val grow = HomeSettings.combatGrow
                val restB = crop("combat|rest|$sz", shot(player(), false, null, ::faces), prio)
                val fightB = crop("combat|$grow|$sz", shot(player(grow = grow), true, null, ::faces), prio)
                if (restB == null || fightB == null) { fallback(c, r, "Combat layout", look, tMs); return }
                // the tile: the fight (frame 0 of the loop); the band: rest -> the fight -> rest
                val k = if (tMs == null) 1f else loop(t, 4.4f)
                blit(c, restB, r)
                if (k > 0f) { pBmp.alpha = (255 * k).toInt(); blit(c, fightB, r); pBmp.alpha = 255; UiKit.combatGlow(c, r, 0.75f * k, r.height() * 0.16f) }
            }
            BottomTiles.SWING -> {
                val on = HomeSettings.swingTimer
                val b = crop("swing|fight|$sz", shot(player(swing = false), true, null, ::faces), prio)
                if (b == null) { fallback(c, r, "Swing timer", look, tMs); return }
                blit(c, b, r)
                UiKit.combatGlow(c, r, 0.55f, r.height() * 0.14f)
                if (on) swingLine(c, r, if (tMs == null) 0.62f else (t % 2.6f) / 2.6f)
            }
            BottomTiles.QUEST -> quest(c, ctx, r, prio, big)
            BottomTiles.CLOCK -> clock(c, r)
            BottomTiles.TOOLBAR -> {
                val on = look.env.dpad?.want == true
                val b = crop("toolbar|$on|$sz", shot(player(), false, on, ::dpad), prio)
                if (b != null) blit(c, b, r) else fallback(c, r, "DS WoW toolbar", look, tMs)
            }
            BottomTiles.TALK -> talk(c, r, look, tMs)
            BottomTiles.CASTBAR -> castBar(c, r, tMs)
            BottomTiles.ZOOM -> {
                val out = Settings.minimapYd; val inn = Settings.minimapIndoorYd
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

    /** the space's px per unit of the 216 x 108 picture */
    private fun k(r: RectF) = r.width() / W

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

    // ---- Focused quest: two slot frames (the chat's top, the minimap's top), the quest card in the chosen one

    private fun quest(c: Canvas, ctx: Context, r: RectF, prio: Int, big: Boolean) {
        val over = HomeSettings.questOverMap
        val s = k(r)
        val gap = 8f * s
        val fw = (r.width() - 3f * gap) / 2f; val fh = r.height() - 2f * gap
        val frames = listOf(RectF(r.left + gap, r.top + gap, r.left + gap + fw, r.top + gap + fh),
            RectF(r.right - gap - fw, r.top + gap, r.right - gap, r.top + gap + fh))
        val lk = player(overMap = over)
        val sw = (if (big) 210 else 104); val sh = (sw * QH / QW).toInt()
        for ((i, key) in listOf("chat", "map").withIndex()) {
            val f = frames[i]
            val chosen = (i == 1) == over
            val b = crop("quest|$key|$over|${if (big) "L" else "S"}", Shot(lk, false, null, { if (key == "chat" && over) colFoot(it, key) else colTop(it, key) }, sw, sh), prio)
            pF.color = Theme.field; c.drawRect(f, pF)
            if (b != null) {
                val h = f.width() * sh / sw
                c.drawBitmap(b, null, RectF(f.left, f.top, f.right, f.top + min(h, f.height())), pBmp)
            } else {
                if (i == 1) { pF.color = Color.rgb(52, 70, 46); c.drawCircle(f.centerX(), f.centerY() + 8f * s, min(f.width(), f.height()) * 0.36f, pF) }
                else { pF.color = Theme.textDim; for (j in 0..2) c.drawRect(f.left + 8f * s, f.bottom - (14f + j * 12f) * s, f.right - (10f + j * 14f) * s, f.bottom - (10f + j * 12f) * s, pF) }
                if (chosen) card(c, RectF(f.left + 4f * s, f.top + 4f * s, f.right - 4f * s, f.top + 30f * s))
            }
            if (!chosen) { pF.color = Color.argb(150, 0, 0, 0); c.drawRect(f, pF) }
            pS.color = if (chosen) Theme.gold else Theme.bronzeDim; pS.strokeWidth = (if (chosen) 2.4f else 1.2f) * s
            c.drawRect(f, pS)
        }
    }

    /** a quest card (our own drawing, sample words): the number disc and a title */
    private fun card(c: Canvas, b: RectF) {
        val s = b.height() / 26f
        pF.color = Theme.raised; c.drawRoundRect(b, 3f * s, 3f * s, pF)
        pF.color = Theme.gold; c.drawRect(b.left, b.top + 2f * s, b.left + 2f * s, b.bottom - 2f * s, pF)
        pF.color = Color.rgb(230, 170, 40); c.drawCircle(b.left + 12f * s, b.centerY(), 7f * s, pF)
        val p = UiKit.bodyPaint(9f * s, Color.rgb(255, 230, 60), shadow = false)
        c.drawText("Wolves Across…", b.left + 23f * s, b.centerY() + 3.5f * s, p)
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

    // ---- Hold to talk: the chat bar, the mic, the hold ring round the D-pad's Left; the band: hold, then the words, then sent / shown

    private fun talk(c: Canvas, r: RectF, look: Look, tMs: Long?) {
        val who = look.env.who
        val on = HomeSettings.holdTalk(who)
        val now = HomeSettings.talkSendNow(who)
        val kk = r.width() / 432f
        c.save(); c.translate(r.left, r.top); c.scale(kk, kk)
        pF.shader = LinearGradient(0f, 0f, 0f, 216f, Theme.panel, Theme.ink, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, 432f, 216f, pF); pF.shader = null
        val t = if (tMs == null) 1.0f else (tMs / 1000f) % 4f
        val hold = (t / 1.2f).coerceIn(0f, 1f)                                   // the ring fills while D-pad Left is held
        // D-pad Left with the hold ring
        val gx = 78f; val gy = 92f
        UiKit.pad(c, "gen_left", gx, gy, 72f, if (on) 255 else 120)
        pS.color = Theme.bronzeDim; pS.strokeWidth = 6f; c.drawCircle(gx, gy, 50f, pS)
        if (on) { pS.color = Theme.gold; c.drawArc(RectF(gx - 50f, gy - 50f, gx + 50f, gy + 50f), -90f, 360f * hold, false, pS) }
        // the mic
        VoiceUi.mic(c, 196f, gy, 84f, if (on) Theme.gold else Theme.dim, crossed = !on)
        // the chat bar: the words come after the hold; Send now = sent at once, Show = waits for A
        val bar = RectF(250f, 62f, 414f, 122f)
        pF.color = Theme.field; c.drawRoundRect(bar, 8f, 8f, pF)
        if (!UiKit.ringBox(c, bar, 0.6f)) { pS.color = Theme.bronze; pS.strokeWidth = 2f; c.drawRoundRect(bar, 8f, 8f, pS) }
        if (on) {
            val shown = t >= 1.3f
            if (shown) {
                val p = UiKit.bodyPaint(22f, Theme.text, shadow = false)
                c.drawText(fit(p, "lfg Razor Hill", bar.width() - 20f), bar.left + 10f, bar.centerY() + 8f, p)
                if (!now) UiKit.pad(c, UiKit.okGlyph(), bar.right - 6f, bar.bottom + 30f, 52f)
                else if (t >= 1.8f) {
                    val q = UiKit.bodyPaint(20f, Theme.gold, shadow = false).apply { textAlign = Paint.Align.CENTER }
                    c.drawText(Tr.t("Sent"), bar.centerX(), bar.bottom + 40f, q)
                }
            }
        }
        c.restore()
    }

    // ---- Tap-to-cast bar: Home's columns, the bar of real spell icons under the chosen one(s); In combat: Hide slides it away

    /** a level-6 Shaman's first spells (CastBarAsk's picture: sample icons) */
    private val SPELL_ICONS = intArrayOf(136048, 136026, 136052, 136051, 136086, 136102, 136015, 135824)

    private fun castBar(c: Canvas, r: RectF, tMs: Long?) {
        val side = HomeSettings.castBarSide
        val hide = HomeSettings.castBarCombat == CastStripModel.Combat.HIDE
        val kk = r.width() / 432f
        c.save(); c.translate(r.left, r.top); c.scale(kk, kk)
        // the band's loop: rest, a fight (the red edge), the bar sliding away (Hide) or staying (Stay), back
        val fight = if (tMs == null || side == CastStripModel.Side.OFF) 0f else loop(tMs / 1000f, 4.4f)
        val slide = if (hide) fight else 0f
        pF.color = Theme.ink; c.drawRect(0f, 0f, 432f, 216f, pF)
        // the controller band: two small diamond groups
        pF.color = Theme.panel; c.drawRect(0f, 0f, 432f, 46f, pF)
        for (gx in listOf(86f, 346f)) for ((dx, dy) in listOf(0f to -11f, 11f to 0f, 0f to 11f, -11f to 0f)) {
            pS.color = Theme.bronzeDim; pS.strokeWidth = 2f; c.drawCircle(gx + dx, 23f + dy, 7f, pS)
        }
        val g = 8f
        val barH = 46f
        val left = side == CastStripModel.Side.LEFT || side == CastStripModel.Side.BOTH
        val right = side == CastStripModel.Side.RIGHT || side == CastStripModel.Side.BOTH
        val room = (barH + g) * (1f - slide)
        val chat = RectF(g, 46f + g, 216f - g / 2f, 216f - g - (if (left) room else 0f))
        val map = RectF(216f + g / 2f, 46f + g, 432f - g, 216f - g - (if (right) room else 0f))
        for (col in listOf(chat, map)) {
            pF.color = Theme.raised; c.drawRoundRect(col, 6f, 6f, pF)
            pS.color = Theme.bronzeDim; pS.strokeWidth = 2f; c.drawRoundRect(col, 6f, 6f, pS)
        }
        pF.color = Theme.textDim
        for (j in 0..2) { val y = chat.bottom - 14f - j * 16f; if (y > chat.top + 10f) c.drawRect(chat.left + 10f, y - 5f, chat.right - 10f - j * 26f, y, pF) }
        val mr = min(map.width(), map.height()) / 2f - 8f
        pF.color = Color.rgb(52, 70, 46); c.drawCircle(map.centerX(), map.centerY(), mr, pF)
        pS.color = Theme.bronze; c.drawCircle(map.centerX(), map.centerY(), mr, pS)
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
        if (a == null || (!all && base == null)) { fallback(c, r, "World map", look, tMs); return }
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
