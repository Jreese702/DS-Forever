package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock

/**
 * THE SCENE behind the start screen and behind a room that waits for the game: the game's OWN
 * loading pictures for WoW: Forever, each in its one colour (user picks 2026-09-29: "a wow
 * forever colorless background setting", "3, as the game tints them", "The one where my
 * character is").
 *
 * The pictures are the game's art. They are read from the device's kit folder
 * (`ui/kit/scene_<fileDataID>.png`, cut by `extract_kit.py` from the user's own client) and are
 * NEVER packed into the app. Without them the ground is the companion's plain dark.
 *
 * THE PICTURES ARE LET GO WHEN NOTHING DRAWS THEM (issue triage 2026-11-14, the 1.0.29 - 1.0.31 low-memory cluster #236 - #276; the
 * user, 2026-11-13: "yes on the triage things"): each one is 1240 x 1080 decoded (5.1 MB), the loading reveal takes all [all] in turn,
 * and they sat in UiKit's kit cache for the whole run: every report's "kit" climbed 10 -> 46 MB in 5 MB steps while the start screen
 * showed and stayed there in the world, where Android then closed DS Forever (and the game with it) while the GAME loaded. Now they
 * are this object's own ([pic]: loading pages and a waiting room's ground only) and go once none was asked for [IDLE_MS] (or at a
 * running-low trim / MemWatch's relief once none was asked for [TRIM_IDLE_MS]); a page that shows again decodes its picture again.
 * Main thread only (the views' draws); MemWatch reads only [held].
 */
object Scene {

    // ---------------------------------------------------------------- the decoded pictures (main thread)
    private val pics = HashMap<Int, Bitmap>()
    private val missingAt = HashMap<Int, Long>()
    /** the pictures' bytes held now (MemWatch's "scenes"; written on the main thread, read on WCMEM's) */
    @Volatile var held = 0L
        private set
    /** when a picture was last asked for (uptime ms), 0 = never */
    @Volatile private var askedAt = 0L
    const val IDLE_MS = 15_000L
    const val TRIM_IDLE_MS = 2_000L
    /** a missing picture is looked for again after this long (a kit built while the app runs) */
    private const val MISSING_MS = 5_000L

    /** the pictures may go: some are held and none was asked for [idleMs] (pure: the tests) */
    fun mayGo(nowMs: Long, askedAtMs: Long, heldBytes: Long, idleMs: Long): Boolean =
        heldBytes > 0L && nowMs - askedAtMs >= idleMs

    /** the decoded loading picture [fid] (`ui/kit/scene_<fid>.png`), null while the kit has none; main thread */
    fun pic(fid: Int): Bitmap? {
        val now = SystemClock.uptimeMillis()
        askedAt = now
        armIdle()
        pics[fid]?.let { return it }
        missingAt[fid]?.let { if (now - it < MISSING_MS) return null }
        val f = ArtHarvest.file("ui/kit/scene_$fid.png")
        val b = if (f.canRead()) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
        if (b == null) { missingAt[fid] = now; return null }
        missingAt.remove(fid)
        pics[fid] = b
        held += b.byteCount.toLong()
        return b
    }

    /** every decoded picture goes (their bytes); main thread. A draw that comes later decodes its own again */
    fun letGo(): Long {
        val b = held
        pics.clear(); held = 0L
        return b
    }

    private var idleArmed = false
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val idleCheck = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (held == 0L) { idleArmed = false; return }
            if (mayGo(now, askedAt, held, IDLE_MS)) {
                val b = letGo(); idleArmed = false
                android.util.Log.i("WCMEM", "loading pictures let go (${(b + (1 shl 19)) shr 20} MB): none drawn for ${IDLE_MS / 1000} s")
            } else main.postDelayed(this, IDLE_MS - (now - askedAt))
        }
    }
    private fun armIdle() {
        if (idleArmed) return
        idleArmed = runCatching { main.postDelayed(idleCheck, IDLE_MS) }.getOrDefault(false)
    }

    init {
        MemWatch.source("scenes") { held }
        MemWatch.onTrim("scenes") { level ->
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
                mayGo(SystemClock.uptimeMillis(), askedAt, held, TRIM_IDLE_MS)) letGo() else 0L
        }
    }

    /**
     * The game's own tie of a map to its loading picture (Map.db2's loading screen, then
     * LoadingScreens.db2's picture), for the eight pictures this client holds. A map is a
     * continent or an instance: 0 Eastern Kingdoms, 1 Kalimdor, 2997 Darkspear Islands ...
     */
    private val byMap = mapOf(
        0 to 7963776, 269 to 7963776, 2720 to 7963776, 2853 to 7963776, 2868 to 7963776, 2980 to 7963776,
        3021 to 7963776, 3109 to 7963776,
        1 to 7963779, 2806 to 7963779, 2817 to 7963779,
        2991 to 7950679, 2959 to 7963775, 2998 to 7963777, 3065 to 7963781, 2999 to 7963782, 2997 to 8423398,
    )
    /** every picture of the kit, for a preview that wants to show them all */
    val all = listOf(7963776, 7963779, 7950679, 7963775, 7963777, 7963781, 7963782, 8423398)

    /** the picture shown while the map is not known, or has none of its own here: the last one that was shown; first the Eastern Kingdoms' */
    private var last = 7963776

    /**
     * Where the character stands: the position document's `inst` (the continent's or the
     * instance's map). A map whose own picture this client lacks (the old dungeons) keeps the
     * picture of where the character came from.
     */
    fun note(map: Int) {
        byMap[map]?.let { last = it }
    }

    /** the picture's fileDataID that [draw] shows now */
    fun picture(): Int = last

    private val pArt = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // turned down, so that words and gold stand on it
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.62f, 0.62f, 0.62f, 1f) })
    }
    private val pShade = Paint()

    /**
     * The scene over [r], cut to it (the picture's middle, never stretched), darker towards its
     * head and much darker towards its foot, where words stand. [picture] = one of [all], or 0
     * for the one of where the character is.
     */
    fun draw(c: Canvas, r: RectF, picture: Int = 0) {
        val b = pic(if (picture != 0) picture else last)
        if (b == null) {
            pShade.shader = null; pShade.color = Theme.ink
            c.drawRect(r, pShade)
            return
        }
        // cover: the picture keeps its shape and fills the rectangle
        val k = maxOf(r.width() / b.width, r.height() / b.height)
        val sw = (r.width() / k).toInt().coerceAtMost(b.width)
        val sh = (r.height() / k).toInt().coerceAtMost(b.height)
        val sx = (b.width - sw) / 2
        val sy = (b.height - sh) / 2
        c.drawBitmap(b, Rect(sx, sy, sx + sw, sy + sh), r, pArt)
        val head = minOf(200f, r.height() * 0.2f)
        pShade.shader = LinearGradient(0f, r.top, 0f, r.top + head, Color.argb(110, 6, 5, 4), Color.argb(0, 6, 5, 4), Shader.TileMode.CLAMP)
        c.drawRect(r.left, r.top, r.right, r.top + head, pShade)
        val foot = minOf(400f, r.height() * 0.4f)
        pShade.shader = LinearGradient(0f, r.bottom - foot, 0f, r.bottom, Color.argb(0, 6, 5, 4), Color.argb(205, 6, 5, 4), Shader.TileMode.CLAMP)
        c.drawRect(r.left, r.bottom - foot, r.right, r.bottom, pShade)
        pShade.shader = null
    }
}
