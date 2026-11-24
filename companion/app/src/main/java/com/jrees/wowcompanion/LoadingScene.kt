package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * THE LOADING REVEAL ON BOTH SCREENS (the user, 2026-10-28: "I love this"; the design and every ruling in [LoadingMath]). One scene
 * behind the two screens: the top screen ([LoadingTopView], the launcher's start screen) and the bottom's loading pages (StartView,
 * SetupView, SetupPanel, ChooserPanel) each draw their own part of it ([draw], top = true / false) from ONE clock and seed, so the
 * spark that leaves the top screen arrives on the bottom one.
 *
 * The pictures are the game's own loading pictures from the device's kit ([Scene.all], `scene_<fid>`, cut on the phone from the
 * user's own client; never in the app). The first is random each start, then they take turns. Before they exist the spark, its tail
 * and its motes still travel over black. A caller redraws about 30 times a second while it shows ([FRAME_MS]).
 */
object LoadingScene {
    const val FRAME_MS = 33L
    /** the light's grid: one value per [GRID] px of the screen, drawn filtered (the light is soft anyway) */
    private const val GRID = 8
    /** the picture's brightness under the light (the study's 0.8) */
    private const val DIM = 0.8f

    private var t0 = 0L
    private var started = false
    private var seed = java.util.Random().nextLong()
    private var first = java.util.Random().nextInt(1 shl 16)

    @Volatile private var maps: LoadingMath.Maps? = null
    private var mapsTried = false

    /** the scene's clock, s (starts at the first draw of either screen) */
    fun now(): Float {
        val ms = SystemClock.uptimeMillis()
        if (!started) { started = true; t0 = ms }
        return (ms - t0) / 1000f
    }

    /** the maps from our own swirl (home_swirl, assets/harvest/orig: always installed); null until it can be read */
    private fun maps(): LoadingMath.Maps? {
        maps?.let { return it }
        if (mapsTried && UiKit.tex("home_swirl") == null) return null
        mapsTried = true
        val b = UiKit.tex("home_swirl") ?: return null
        val n = min(b.width, b.height)
        val px = IntArray(n * n)
        b.getPixels(px, 0, n, 0, 0, n, n)
        val lum = FloatArray(n * n) { val p = px[it]; max(Color.red(p), max(Color.green(p), Color.blue(p))) / 255f }
        return LoadingMath.maps(lum, n).also { maps = it }
    }

    // ------------------------------------------------------------------ the pictures and their colours

    private val tints = HashMap<Int, FloatArray>()
    // issue triage 2026-11-14 (low-memory cluster): Scene's own pictures, let go once no loading page draws (no longer UiKit's kit cache)
    private fun picture(i: Int): Bitmap? = Scene.pic(Scene.all[i % Scene.all.size])
    private fun tint(i: Int): FloatArray {
        val fid = Scene.all[i % Scene.all.size]
        tints[fid]?.let { return it }
        val b = picture(i) ?: return WHITE_BLUE
        val s = Bitmap.createScaledBitmap(b, 8, 8, true)
        var r = 0f; var g = 0f; var bl = 0f
        for (y in 0 until 8) for (x in 0 until 8) { val p = s.getPixel(x, y); r += Color.red(p); g += Color.green(p); bl += Color.blue(p) }
        if (s !== b) s.recycle()
        return LoadingMath.tint(r / 64f / 255f, g / 64f / 255f, bl / 64f / 255f).also { tints[fid] = it }
    }
    /** the spark's colour before the pictures exist: the swirl's own blue */
    private val WHITE_BLUE = floatArrayOf(0.25f, 0.55f, 1f)

    // ------------------------------------------------------------------ drawing

    private val pPic = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(DIM, DIM, DIM, 1f) })
    }
    private val pMask = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val pAdd = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val tmp = FloatArray(3)

    /** each screen's own light grids (top / bottom), one per pass slot */
    private class Grids(var w: Int = 0, var h: Int = 0, val bmp: Array<Bitmap?> = arrayOfNulls(3), val px: Array<IntArray?> = arrayOfNulls(3),
        /** the frame and pass each slot was last filled for (several stacked bottom pages draw the same frame: fill it once) */
        val key: LongArray = LongArray(3) { Long.MIN_VALUE }, val lit: BooleanArray = BooleanArray(3))
    private val grids = arrayOf(Grids(), Grids())

    // ------------------------------------------------------------------ the passes: both screens, or the bottom alone (2026-11-24)

    private var schedule = LoadingMath.Schedule(seed, first)
    /** when a top screen last drew the scene ([draw] with claim), uptime ms; MIN = none yet (the start: both screens assumed) */
    private var topAt = Long.MIN_VALUE
    /** the off-device pictures: force the bottom-alone choice (null = from the top screen's draws) */
    @Volatile var forceSolo: Boolean? = null

    /**
     * a top screen drew the scene within [TOP_GAP_MS]: new passes span both screens; else (the game is on the top screen: the launch
     * handed over, or the game runs) they start on the bottom alone, smaller ("to fit on that screen")
     */
    private fun solo(ms: Long): Boolean = forceSolo ?: (topAt != Long.MIN_VALUE && ms - topAt > TOP_GAP_MS)
    const val TOP_GAP_MS = 400L

    /** the bottom screen is on its own now (new passes start there alone) */
    fun soloNow(): Boolean = solo(SystemClock.uptimeMillis())

    /**
     * Draws [top]'s (or the bottom's) part of the scene over the whole of [r] (the view's size): black (PAINTED STAYS: the pictures
     * kept so far), each pass's picture under its light (oldest first, the newer over it), then the arm's tail, the spark and its motes
     * added on top. [claim] = this top screen counts as showing the scene (false while it fades out: the launch's hand-over).
     */
    fun draw(c: Canvas, r: RectF, top: Boolean, claim: Boolean = true) {
        c.save()
        c.clipRect(r)
        c.drawColor(Color.BLACK)
        val m = maps()
        if (m == null) { c.restore(); return }
        val t = now()
        val ms = SystemClock.uptimeMillis()
        if (top && claim) {
            // a top screen back after a gap (the game closed, a restart): a pass at once, so it is never black until the next one
            if (forceSolo == null && solo(ms)) schedule.restart(t)
            topAt = ms
        }
        val stays = LoadingMath.paintedStays
        val n = Scene.all.size
        val passes = schedule.at(t, n, solo(ms), stays)
        val g = grids[if (top) 0 else 1]
        val gw = ceil(r.width() / GRID).toInt().coerceAtLeast(2); val gh = ceil(r.height() / GRID).toInt().coerceAtLeast(2)
        if (g.w != gw || g.h != gh) { g.w = gw; g.h = gh; for (i in 0 until SLOTS) { g.bmp[i]?.recycle(); g.bmp[i] = null; g.px[i] = null; g.key[i] = Long.MIN_VALUE } }
        // 0. PAINTED STAYS: what earlier passes left painted (this screen's own picture of it). The bottom on its own = no top screen
        //    draws the scene: the top's kept picture goes (the launch's hand-over gives its 1.7 MB back at once)
        if (!top && solo(ms)) dropKept(true)
        val keep = if (stays) kept(top, r) else { dropKept(top); null }
        keep?.let { k -> c.drawBitmap(k.bmp, null, r, pKept) }
        // 1. the pictures under their light (the newest SLOTS: more are alive only for a moment, at a top screen's restart)
        val shown = if (passes.size > SLOTS) passes.subList(passes.size - SLOTS, passes.size) else passes
        for ((slot, p) in shown.withIndex()) {
            val reg = LoadingMath.region(top, p) ?: continue
            if (keep != null && p.k <= keep.upTo) continue
            val pic = picture(p.picture) ?: continue
            if (!fill(g, slot, m, p, t, reg, r, gw, gh, stays)) continue
            if (keep != null && t >= p.settled()) {
                // settled: its light never changes again, so it goes into the kept picture (and is drawn from there from now on)
                pass(Canvas(keep.bmp), RectF(0f, 0f, keep.bmp.width.toFloat(), keep.bmp.height.toFloat()), pic, g.bmp[slot]!!, reg, p, gw, gh, r)
                keep.upTo = p.k
                c.drawBitmap(keep.bmp, null, r, pKept)
                continue
            }
            pass(c, r, pic, g.bmp[slot]!!, reg, p, gw, gh, r)
        }
        // 2. the arm's comet tail, the spark and its motes, in the picture's colour, added
        for (p in passes) {
            val reg = LoadingMath.region(top, p) ?: continue
            val sx = r.width() / reg.width; val sy = r.height() / reg.height
            val tc = t - p.start
            if (tc > LoadingMath.JOURNEY + LoadingMath.TAIL + LoadingMath.MOTE_LIFE) continue
            val tint = tint(p.picture)
            val col = Color.rgb((tint[0] * 255).toInt(), (tint[1] * 255).toInt(), (tint[2] * 255).toInt())
            // PAINTED STAYS' READABLE SPARK (the user's pick 2026-11-24, "the spark is a little harder to spot" on a painted picture): a
            // soft dark halo under the spark's head and its comet tail's newest part, so its light stands off the painting round it
            val q = LoadingMath.progress(p, t)
            val readable = stays && sparkReadable && q in 0f..1f
            if (readable) {
                LoadingMath.head(m, p, q, tmp)
                val hx = r.left + (tmp[0] - reg.left) * sx; val hy = r.top + (tmp[1] - reg.top) * sy
                val hr = (HALO_R + HALO_GROW * q) * sx * p.scale
                if (hx > r.left - hr && hx < r.right + hr && hy > r.top - hr && hy < r.bottom + hr) {
                    pHalo.shader = RadialGradient(hx, hy, hr, intArrayOf(Color.argb(HALO_A, 0, 0, 0), Color.argb(HALO_A * 2 / 3, 0, 0, 0), Color.TRANSPARENT),
                        floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                    c.drawCircle(hx, hy, hr, pHalo)
                    pHalo.shader = null
                }
            }
            tail(m, p, tc)?.let { tb ->
                val mx = Matrix()
                // the maps' square -> virtual (as Pass.toVirtual) -> this screen
                val kk = LoadingMath.SWIRL * p.scale / LoadingMath.N
                mx.postTranslate(-LoadingMath.M / 2f, -LoadingMath.M / 2f)
                mx.postScale(if (p.mirror) -kk else kk, kk)
                mx.postRotate(Math.toDegrees(p.angle.toDouble()).toFloat())
                mx.postTranslate(p.cx - reg.left, p.cy - reg.top)
                mx.postScale(sx, sy)
                mx.postTranslate(r.left, r.top)
                pAdd.shader = null
                pAdd.colorFilter = PorterDuffColorFilter(col, PorterDuff.Mode.SRC_IN)
                pAdd.alpha = 128          // v8: the arm's tint 0.5 ("the glowing effect is a bit too strong")
                c.drawBitmap(tb, mx, pAdd)
                pAdd.colorFilter = null; pAdd.alpha = 255
            }
            if (q in 0f..1f) {
                LoadingMath.head(m, p, q, tmp)
                val hx = r.left + (tmp[0] - reg.left) * sx; val hy = r.top + (tmp[1] - reg.top) * sy
                // v8: the glow small at the middle and growing along the arm (the study's sigma 30 + 40 q), and softer (0.35 tint + 0.3)
                val rad = (210f + 280f * q) * sx * p.scale
                val glow = Color.rgb(((0.35f * tint[0] + 0.3f).coerceAtMost(1f) * 255).toInt(), ((0.35f * tint[1] + 0.3f).coerceAtMost(1f) * 255).toInt(),
                    ((0.35f * tint[2] + 0.3f).coerceAtMost(1f) * 255).toInt())
                if (hx > r.left - rad && hx < r.right + rad && hy > r.top - rad && hy < r.bottom + rad) {
                    pAdd.shader = RadialGradient(hx, hy, rad, intArrayOf(glow, Color.argb(120, Color.red(glow), Color.green(glow), Color.blue(glow)),
                        Color.TRANSPARENT), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
                    c.drawCircle(hx, hy, rad, pAdd)
                    pAdd.shader = null
                    if (readable) {
                        // ... and a small white-hot core, so the head itself reads as the brightest thing on the screen
                        val cr = rad * CORE_SHARE
                        pAdd.shader = RadialGradient(hx, hy, cr, intArrayOf(Color.argb(235, 255, 255, 255), Color.argb(110, 255, 255, 255), Color.TRANSPARENT),
                            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
                        c.drawCircle(hx, hy, cr, pAdd)
                        pAdd.shader = null
                    }
                }
            }
            // the motes thrown off the spark in the last MOTE_LIFE s
            val i0 = max(0, ((tc - LoadingMath.MOTE_LIFE) * LoadingMath.MOTES_PER_S).toInt())
            val i1 = (tc * LoadingMath.MOTES_PER_S).toInt()
            for (i in i0..i1) {
                if (!LoadingMath.mote(m, p, i, t, tmp)) continue
                val mx = r.left + (tmp[0] - reg.left) * sx; val my = r.top + (tmp[1] - reg.top) * sy
                val rr = (25f * tmp[2] + 3f) * sx * 2.5f * p.scale
                if (mx < r.left - rr || mx > r.right + rr || my < r.top - rr || my > r.bottom + rr) continue
                val a = (255 * 0.6f * tmp[2]).toInt()          // v8: the motes at 0.6
                pAdd.shader = RadialGradient(mx, my, rr, intArrayOf(Color.argb(a, 255, 255, 255), Color.argb(a / 2, Color.red(col), Color.green(col), Color.blue(col)),
                    Color.TRANSPARENT), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(mx, my, rr, pAdd)
                pAdd.shader = null
            }
        }
        c.restore()
    }

    /** grid slot [slot]'s light for pass [p] at [t] (filled once per frame and pass); false = nothing of it lit on this screen */
    private fun fill(g: Grids, slot: Int, m: LoadingMath.Maps, p: LoadingMath.Pass, t: Float, reg: LoadingMath.Region, r: RectF,
                     gw: Int, gh: Int, stays: Boolean): Boolean {
        val sx = r.width() / reg.width; val sy = r.height() / reg.height
        val px = g.px[slot] ?: IntArray(gw * gh).also { g.px[slot] = it }
        val key = (t * 1000f / FRAME_MS).toLong() * 1000L + p.k
        if (g.key[slot] != key || g.bmp[slot] == null) {
            var any = false
            for (gy in 0 until gh) {
                val vy = reg.top + (gy + 0.5f) * GRID / sy
                for (gx in 0 until gw) {
                    val vx = reg.left + (gx + 0.5f) * GRID / sx
                    val a = (LoadingMath.lit(m, p, t, vx, vy, tmp, stays) * 255f).toInt().coerceIn(0, 255)
                    if (a > 0) any = true
                    px[gy * gw + gx] = (a shl 24) or 0xFFFFFF
                }
            }
            val bmp = g.bmp[slot] ?: Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888).also { g.bmp[slot] = it }
            bmp.setPixels(px, 0, gw, 0, 0, gw, gh)
            g.key[slot] = key; g.lit[slot] = any
        }
        return g.lit[slot]
    }

    /**
     * one pass's picture under its light into [dst] (the screen, or the kept picture): the picture covers the pass's whole canvas (its
     * shape kept), this screen's part of it, masked by the light grid ([view] = the screen's rect the grid was made for)
     */
    private fun pass(c: Canvas, dst: RectF, pic: Bitmap, light: Bitmap, reg: LoadingMath.Region, p: LoadingMath.Pass, gw: Int, gh: Int, view: RectF) {
        val sx = dst.width() / reg.width; val sy = dst.height() / reg.height
        val layer = c.saveLayer(dst, null)
        val k = max(p.canvasW / pic.width, p.canvasH / pic.height)
        val vl = (p.canvasW - pic.width * k) / 2f; val vt = (p.canvasH - pic.height * k) / 2f
        c.drawBitmap(pic, null, RectF(dst.left + (vl - reg.left) * sx, dst.top + (vt - reg.top) * sy,
            dst.left + (vl + pic.width * k - reg.left) * sx, dst.top + (vt + pic.height * k - reg.top) * sy), pPic)
        val gs = dst.width() / view.width()
        c.drawBitmap(light, null, RectF(dst.left, dst.top, dst.left + gw * GRID * gs, dst.top + gh * GRID * gs), pMask)
        c.restoreToCount(layer)
    }

    /** painted stays' readable spark: on (the default) = the dark halo and the white core; off = the fade's own spark (the before / after) */
    @Volatile var sparkReadable = true
    /** the halo: its radius (virtual px, at the inner end and the extra at the tail), its darkness at the middle (0..255) */
    const val HALO_R = 150f
    const val HALO_GROW = 170f
    const val HALO_A = 150
    /** the core's radius, a share of the glow's */
    const val CORE_SHARE = 0.16f
    private val pHalo = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * where the newest running spark is on [top]'s screen (or the bottom's) of [w] x [h] px, null = none on it (the tests: the spark's
     * contrast). Main thread
     */
    fun sparkAt(top: Boolean, w: Int, h: Int): FloatArray? {
        val m = maps() ?: return null
        val t = now()
        for (p in schedule.at(t, Scene.all.size, solo(SystemClock.uptimeMillis()), LoadingMath.paintedStays).asReversed()) {
            val reg = LoadingMath.region(top, p) ?: continue
            val q = LoadingMath.progress(p, t)
            if (q !in 0f..1f) continue
            LoadingMath.head(m, p, q, tmp)
            val x = (tmp[0] - reg.left) * w / reg.width; val y = (tmp[1] - reg.top) * h / reg.height
            if (x in 0f..w.toFloat() && y in 0f..h.toFloat()) return floatArrayOf(x, y, (210f + 280f * q) * w / reg.width * p.scale)
        }
        return null
    }

    // ------------------------------------------------------------------ PAINTED STAYS: each screen's kept picture

    /** a screen's kept picture (RGB_565: opaque, half the bytes; at most the scene pictures' own width) and the last pass it holds */
    private class Kept(val bmp: Bitmap, var upTo: Int)
    private val keptPics = arrayOfNulls<Kept>(2)

    private fun kept(top: Boolean, r: RectF): Kept {
        val i = if (top) 0 else 1
        val s = min(1f, KEPT_MAX_W / r.width())
        val w = max(2, (r.width() * s).toInt()); val h = max(2, (r.height() * s).toInt())
        keptPics[i]?.let { if (it.bmp.width == w && it.bmp.height == h) return it; it.bmp.recycle() }
        return Kept(Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).also { it.eraseColor(Color.BLACK) }, Int.MIN_VALUE).also { keptPics[i] = it }
    }

    private fun dropKept(top: Boolean) { val i = if (top) 0 else 1; keptPics[i]?.bmp?.recycle(); keptPics[i] = null }

    /** the kept pictures go (a loading page that shows again starts black, as at the start); main thread */
    fun letGoKept() { dropKept(true); dropKept(false) }
    private const val KEPT_MAX_W = 1240f
    private val pKept = Paint(Paint.FILTER_BITMAP_FLAG)
    private const val SLOTS = 3

    // ------------------------------------------------------------------ the arm's tail (shared by both screens: one per pass and frame)

    private var tailBmp: Bitmap? = null
    private var tailPx: IntArray? = null
    private var tailKey = Long.MIN_VALUE

    /** the arm behind the spark: brightest where it just passed, gone [LoadingMath.TAIL] s later on a squared curve (v8: "a true tail
     *  fade"); null = none now */
    private fun tail(m: LoadingMath.Maps, p: LoadingMath.Pass, tc: Float): Bitmap? {
        if (tc < 0f || tc > LoadingMath.JOURNEY + LoadingMath.TAIL) return null
        val key = p.k.toLong() * 100_000L + (tc * 1000f / FRAME_MS).toLong()
        val mm = LoadingMath.M
        if (key == tailKey) return tailBmp
        val px = tailPx ?: IntArray(mm * mm).also { tailPx = it }
        for (i in 0 until mm * mm) {
            val pos = m.pos[i]
            px[i] = if (pos >= 2f) 0 else {
                val age = tc - LoadingMath.arrive(pos)
                if (age < 0f || age > LoadingMath.TAIL) 0 else {
                    val f = 1f - age / LoadingMath.TAIL
                    val a = (min(1f, m.arm[i] * 1.4f) * f * f * 255f).toInt().coerceIn(0, 255)
                    (a shl 24) or 0xFFFFFF
                }
            }
        }
        val b = tailBmp ?: Bitmap.createBitmap(mm, mm, Bitmap.Config.ARGB_8888).also { tailBmp = it }
        b.setPixels(px, 0, mm, 0, 0, mm, mm)
        tailKey = key
        return b
    }

    /** the off-device pictures: the scene at [t] s with a fixed [seed] and first picture (both screens' frames at the same moment) */
    fun pin(t: Float, seed: Long, first: Int) {
        started = true; t0 = SystemClock.uptimeMillis() - (t * 1000f).toLong(); this.seed = seed; this.first = first; tailKey = Long.MIN_VALUE
        schedule = LoadingMath.Schedule(seed, first); topAt = Long.MIN_VALUE; letGoKept()
        for (g in grids) for (i in g.key.indices) g.key[i] = Long.MIN_VALUE
    }

    /** the off-device pictures: the scene's clock moved to [t] s, the passes so far kept (a sequence of frames, a hand-over) */
    fun clock(t: Float) { started = true; t0 = SystemClock.uptimeMillis() - (t * 1000f).toLong() }
}
