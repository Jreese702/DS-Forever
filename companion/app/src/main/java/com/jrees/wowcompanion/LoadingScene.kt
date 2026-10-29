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
    private fun picture(i: Int): Bitmap? = UiKit.tex("scene_" + Scene.all[i % Scene.all.size])
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

    /**
     * Draws [top]'s (or the bottom's) part of the scene over the whole of [r] (the view's size): black, each pass's picture under its
     * light (oldest first, the newer over it), then the arm's tail, the spark and its motes added on top.
     */
    fun draw(c: Canvas, r: RectF, top: Boolean) {
        c.save()
        c.clipRect(r)
        c.drawColor(Color.BLACK)
        val m = maps()
        if (m == null) { c.restore(); return }
        val t = now()
        val reg = LoadingMath.region(top)
        val sx = r.width() / reg.width; val sy = r.height() / reg.height
        val n = Scene.all.size
        val passes = LoadingMath.passes(seed, t, n, first)
        val g = grids[if (top) 0 else 1]
        val gw = ceil(r.width() / GRID).toInt().coerceAtLeast(2); val gh = ceil(r.height() / GRID).toInt().coerceAtLeast(2)
        if (g.w != gw || g.h != gh) { g.w = gw; g.h = gh; for (i in 0 until 3) { g.bmp[i]?.recycle(); g.bmp[i] = null; g.px[i] = null; g.key[i] = Long.MIN_VALUE } }
        // 1. the pictures under their light
        for ((slot, p) in passes.withIndex()) {
            if (slot >= 3) break
            val pic = picture(p.picture) ?: continue
            val px = g.px[slot] ?: IntArray(gw * gh).also { g.px[slot] = it }
            val key = (t * 1000f / FRAME_MS).toLong() * 1000L + p.k
            if (g.key[slot] != key || g.bmp[slot] == null) {
                var any = false
                for (gy in 0 until gh) {
                    val vy = reg.top + (gy + 0.5f) * GRID / sy
                    for (gx in 0 until gw) {
                        val vx = reg.left + (gx + 0.5f) * GRID / sx
                        val a = (LoadingMath.lit(m, p, t, vx, vy, tmp) * 255f).toInt().coerceIn(0, 255)
                        if (a > 0) any = true
                        px[gy * gw + gx] = (a shl 24) or 0xFFFFFF
                    }
                }
                val bmp = g.bmp[slot] ?: Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888).also { g.bmp[slot] = it }
                bmp.setPixels(px, 0, gw, 0, 0, gw, gh)
                g.key[slot] = key; g.lit[slot] = any
            }
            if (!g.lit[slot]) continue
            val bmp = g.bmp[slot]!!
            val layer = c.saveLayer(r, null)
            // the picture covers the whole virtual canvas (its shape kept), this screen's part of it
            val k = max(LoadingMath.VW / pic.width, LoadingMath.VH / pic.height)
            val vl = (LoadingMath.VW - pic.width * k) / 2f; val vt = (LoadingMath.VH - pic.height * k) / 2f
            c.drawBitmap(pic, null, RectF(r.left + (vl - reg.left) * sx, r.top + (vt - reg.top) * sy,
                r.left + (vl + pic.width * k - reg.left) * sx, r.top + (vt + pic.height * k - reg.top) * sy), pPic)
            c.drawBitmap(bmp, null, RectF(r.left, r.top, r.left + gw * GRID.toFloat(), r.top + gh * GRID.toFloat()), pMask)
            c.restoreToCount(layer)
        }
        // 2. the arm's comet tail, the spark and its motes, in the picture's colour, added
        for (p in passes) {
            val tc = t - p.start
            if (tc > LoadingMath.TRAVEL + LoadingMath.TAIL + LoadingMath.MOTE_LIFE) continue
            val tint = tint(p.picture)
            val col = Color.rgb((tint[0] * 255).toInt(), (tint[1] * 255).toInt(), (tint[2] * 255).toInt())
            tail(m, p, tc)?.let { tb ->
                val mx = Matrix()
                // the maps' square -> virtual (as Pass.toVirtual) -> this screen
                val kk = LoadingMath.SWIRL / LoadingMath.N
                mx.postTranslate(-LoadingMath.M / 2f, -LoadingMath.M / 2f)
                mx.postScale(if (p.mirror) -kk else kk, kk)
                mx.postRotate(Math.toDegrees(p.angle.toDouble()).toFloat())
                mx.postTranslate(p.cx - reg.left, p.cy - reg.top)
                mx.postScale(sx, sy)
                mx.postTranslate(r.left, r.top)
                pAdd.shader = null
                pAdd.colorFilter = PorterDuffColorFilter(col, PorterDuff.Mode.SRC_IN)
                pAdd.alpha = 205
                c.drawBitmap(tb, mx, pAdd)
                pAdd.colorFilter = null; pAdd.alpha = 255
            }
            val q = LoadingMath.progress(p, t)
            if (q in 0f..1f) {
                LoadingMath.head(m, p, q, tmp)
                val hx = r.left + (tmp[0] - reg.left) * sx; val hy = r.top + (tmp[1] - reg.top) * sy
                val rad = 420f * sx
                val glow = Color.rgb(((0.6f * tint[0] + 0.5f).coerceAtMost(1f) * 255).toInt(), ((0.6f * tint[1] + 0.5f).coerceAtMost(1f) * 255).toInt(),
                    ((0.6f * tint[2] + 0.5f).coerceAtMost(1f) * 255).toInt())
                if (hx > r.left - rad && hx < r.right + rad && hy > r.top - rad && hy < r.bottom + rad) {
                    pAdd.shader = RadialGradient(hx, hy, rad, intArrayOf(glow, Color.argb(120, Color.red(glow), Color.green(glow), Color.blue(glow)),
                        Color.TRANSPARENT), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
                    c.drawCircle(hx, hy, rad, pAdd)
                    pAdd.shader = null
                }
            }
            // the motes thrown off the spark in the last MOTE_LIFE s
            val i0 = max(0, ((tc - LoadingMath.MOTE_LIFE) * LoadingMath.MOTES_PER_S).toInt())
            val i1 = (tc * LoadingMath.MOTES_PER_S).toInt()
            for (i in i0..i1) {
                if (!LoadingMath.mote(m, p, i, t, tmp)) continue
                val mx = r.left + (tmp[0] - reg.left) * sx; val my = r.top + (tmp[1] - reg.top) * sy
                val rr = (25f * tmp[2] + 3f) * sx * 2.5f
                if (mx < r.left - rr || mx > r.right + rr || my < r.top - rr || my > r.bottom + rr) continue
                val a = (255 * tmp[2]).toInt()
                pAdd.shader = RadialGradient(mx, my, rr, intArrayOf(Color.argb(a, 255, 255, 255), Color.argb(a / 2, Color.red(col), Color.green(col), Color.blue(col)),
                    Color.TRANSPARENT), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(mx, my, rr, pAdd)
                pAdd.shader = null
            }
        }
        c.restore()
    }

    // ------------------------------------------------------------------ the arm's tail (shared by both screens: one per pass and frame)

    private var tailBmp: Bitmap? = null
    private var tailPx: IntArray? = null
    private var tailKey = Long.MIN_VALUE

    /** the arm behind the spark: brightest where it just passed, gone [LoadingMath.TAIL] s later; null = none now */
    private fun tail(m: LoadingMath.Maps, p: LoadingMath.Pass, tc: Float): Bitmap? {
        if (tc < 0f || tc > LoadingMath.TRAVEL + LoadingMath.TAIL) return null
        val key = p.k.toLong() * 100_000L + (tc * 1000f / FRAME_MS).toLong()
        val mm = LoadingMath.M
        if (key == tailKey) return tailBmp
        val px = tailPx ?: IntArray(mm * mm).also { tailPx = it }
        for (i in 0 until mm * mm) {
            val pos = m.pos[i]
            px[i] = if (pos >= 2f) 0 else {
                val age = tc - pos * LoadingMath.TRAVEL
                if (age < 0f || age > LoadingMath.TAIL) 0 else {
                    val a = (min(1f, m.arm[i] * 1.4f) * (0.25f + 0.75f * (1f - age / LoadingMath.TAIL)) * 255f).toInt().coerceIn(0, 255)
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
    }
}
