package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * THE LAUNCH SCREEN DRAWN OFF THE DEVICE (2026-11-24): our swirl over BOTH screens while the game launches (the top's LaunchTopView and
 * the bottom's StartView from the same scene), the top's fade at the hand-over, then the bottom's own smaller swirl; and the user's
 * "painted stays" experiment beside the approved fade.
 *
 * The checks always run, on pictures of our own (gradients, made here: no game pixels). The pictures for the user run only when
 * LOADING_SHOTS_DIR is set, with PARTY_ART_ROOT = an art root with the harvested scene_<fid> pictures (read only), e.g.
 *   LOADING_SHOTS_DIR=C:/dsf-work/r1527-shots/launch PARTY_ART_ROOT=E:/dsf-issues/artroot-k gradlew :app:testModernDebugUnitTest --tests "*LaunchScreenShotsTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LaunchScreenShotsTest {
    private val out: File? = System.getenv("LOADING_SHOTS_DIR")?.let { File(it) }

    private fun root(f: File) {
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, f)
    }

    /** an art root of our own: home_swirl (ours, from the assets) and a gradient per scene picture */
    private fun ownArt(): File {
        val r = File(System.getProperty("java.io.tmpdir"), "dsf-launch-art-${System.nanoTime()}")
        val kit = File(r, "ui/kit").apply { mkdirs() }
        val swirl = listOf("../companion/app/src/main/assets/harvest/orig/home_swirl.png", "companion/app/src/main/assets/harvest/orig/home_swirl.png")
            .map(::File).first { it.isFile }
        swirl.copyTo(File(kit, "home_swirl.png"))
        for ((i, fid) in Scene.all.withIndex()) {
            val b = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val hue = i * 45f
            c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 1240f, 1080f, Color.HSVToColor(floatArrayOf(hue, 0.6f, 0.9f)),
                Color.HSVToColor(floatArrayOf(hue + 30f, 0.8f, 0.5f)), Shader.TileMode.CLAMP) })
            File(kit, "scene_$fid.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return r
    }

    @Before fun start() { LaunchLoad.reset(); LoadingScene.forceSolo = null; LoadingMath.paintedStays = LoadingMath.PAINTED_STAYS_DEFAULT; LoadingScene.sparkReadable = true }
    @After fun end() {
        LaunchLoad.reset(); LoadingScene.forceSolo = null; LoadingMath.paintedStays = LoadingMath.PAINTED_STAYS_DEFAULT; LoadingScene.sparkReadable = true
        SetupBridge.publish(null); ModeBridge.reset()
    }

    /** the luma (0..255) of [b] averaged over the ring [r0]..[r1] px round (x, y) */
    private fun ring(b: Bitmap, x: Float, y: Float, r0: Float, r1: Float): Float {
        var s = 0f; var n = 0
        val e = r1.toInt() + 1
        for (dy in -e..e) for (dx in -e..e) {
            val d = kotlin.math.hypot(dx.toFloat(), dy.toFloat())
            if (d < r0 || d > r1) continue
            val px = (x + dx).toInt(); val py = (y + dy).toInt()
            if (px !in 0 until b.width || py !in 0 until b.height) continue
            val c = b.getPixel(px, py)
            s += 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c); n++
        }
        return if (n == 0) 0f else s / n
    }

    /**
     * PAINTED STAYS' SPARK STAYS EASY TO SPOT (the user's pick 2026-11-24, "the spark is a little harder to spot"): over a painted
     * picture the spark's head stands off what is round it - its core much brighter than a ring just outside its glow's middle - and
     * clearly more than the fade's spark would on the same painting
     */
    @Test fun theSparkStandsOffAPaintedPicture() {
        root(ownArt())
        LaunchLoad.begin(now())
        assertTrue("painted stays is the default (the user's pick)", LoadingMath.PAINTED_STAYS_DEFAULT && LoadingMath.paintedStays)
        fun contrast(readable: Boolean): Float {
            LoadingScene.sparkReadable = readable
            LoadingScene.pin(0f, 11L, 1)
            var sum = 0f; var n = 0
            var t = 0.2f
            // two passes in: the newer spark runs over the older pass's painting
            while (t < LoadingMath.EVERY * 3) {
                LoadingScene.clock(t)
                val top = render(LaunchTopView(ctx), 1920, 1080)
                val bottom = render(StartView(ctx), 1240, 1080)
                for ((isTop, b) in listOf(true to top, false to bottom)) {
                    val sp = LoadingScene.sparkAt(isTop, b.width, b.height) ?: continue
                    val bg = ring(b, sp[0], sp[1], sp[2] * 0.35f, sp[2] * 0.5f)
                    if (t > LoadingMath.EVERY && bg < 25f) continue          // over black: nothing to stand off
                    sum += ring(b, sp[0], sp[1], 0f, 6f) - bg; n++
                }
                t += 0.7f
            }
            println("spark contrast (readable=$readable): ${sum / maxOf(1, n)} over $n samples")
            return sum / maxOf(1, n)
        }
        val before = contrast(false)
        val after = contrast(true)
        assertTrue("the spark's core stands off the painting ($after)", after >= 90f)
        assertTrue("more than the fade's own spark on the same painting ($before -> $after)", after >= before + 25f)
    }

    private fun render(v: View, w: Int, h: Int): Bitmap {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        return b
    }

    /** the share of [b]'s pixels that are not black (brightest channel over 20), on a 16 px grid */
    private fun lit(b: Bitmap): Float {
        var n = 0; var all = 0
        var y = 8
        while (y < b.height) { var x = 8; while (x < b.width) { val p = b.getPixel(x, y); if (maxOf(Color.red(p), Color.green(p), Color.blue(p)) > 20) n++; all++; x += 16 }; y += 16 }
        return n.toFloat() / all
    }

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun now() = SystemClock.uptimeMillis()

    /** both screens at scene time [t]: the top first (it claims the scene for both screens), then the bottom */
    private fun pair(t: Float, bottomLine: String = "Waiting for the game."): Pair<Bitmap, Bitmap> {
        LoadingScene.clock(t)
        val top = render(LaunchTopView(ctx), 1920, 1080)
        val bottom = render(StartView(ctx).also { it.line = bottomLine }, 1240, 1080)
        return top to bottom
    }

    @Test fun theLaunchCoversBothScreensThenTheBottomGoesOnAlone() {
        root(ownArt())
        LoadingScene.pin(0f, 11L, 1)
        LaunchLoad.begin(now())
        LaunchLoad.step("Launching game...", now())
        for (t in listOf(0.6f, 2.2f, 4.0f, LoadingMath.EVERY + 0.6f, LoadingMath.EVERY * 2 + 3f)) {
            val (top, bottom) = pair(t)
            val a = lit(top); val b = lit(bottom)
            println("launch at $t s: top lit $a, bottom lit $b")
            assertTrue("the top is covered by our screen at $t s ($a)", a > 0.08f)
            assertTrue("the bottom shows the same scene at $t s ($b)", b > 0.08f)
        }
        // the panel's words are there: the gold line's pixels in the box's middle
        // the hand-over: the top fades out (the view's own alpha), the bottom carries on alone
        LaunchLoad.windowMapped(now())
        repeat(LaunchLoad.PICTURE_FRAMES) { LaunchLoad.frame(true, now() - LaunchLoad.FADE_MS / 2) }
        assertEquals(LaunchLoad.Phase.FADING, LaunchLoad.phase)
        val v = LaunchTopView(ctx)
        render(v, 1920, 1080)
        assertTrue("half way through the fade (${v.alpha})", v.alpha in 0.2f..0.8f)
        LoadingScene.forceSolo = true
        val t0 = LoadingMath.EVERY * 2 + 3f
        for (dt in listOf(0.5f, LoadingMath.EVERY, LoadingMath.EVERY * 2, LoadingMath.EVERY * 3 + 1f)) {
            LoadingScene.clock(t0 + dt)
            val b = lit(render(StartView(ctx), 1240, 1080))
            println("bottom alone ${dt}s after the hand-over: lit $b")
            assertTrue("the bottom keeps its loading screen ($b at +$dt s)", b > 0.08f)
        }
    }

    @Test fun theBarShowsOnlyWithAProgress() {
        root(ownArt())
        LoadingScene.pin(0.6f, 11L, 1)
        LaunchLoad.begin(now())
        LaunchLoad.step("Installing Bionic components...", now())
        val none = render(LaunchTopView(ctx), 1920, 1080)
        LaunchLoad.progress(0.5f, now())
        val half = render(LaunchTopView(ctx), 1920, 1080)
        // the bar's row: between the gold line and the chord's line, left of the middle (filled) - gold only with a progress
        fun gold(b: Bitmap): Int { var n = 0; for (x in 600..940) for (y in 597..605) { val p = b.getPixel(x, y); if (Color.red(p) > 150 && Color.green(p) > 100 && Color.blue(p) < 90) n++ }; return n }
        println("gold pixels in the bar's row: no progress ${gold(none)}, half ${gold(half)}")
        assertTrue("a bar with a progress (${gold(half)} vs ${gold(none)})", gold(half) > gold(none) + 300)
    }

    // ------------------------------------------------------------------ the pictures for the user

    /** top over bottom as the device holds them, at [scale] (a frame of a sequence: a quarter, as a JPEG) */
    private fun device(top: Bitmap, bottom: Bitmap, file: File, scale: Float = 0.5f) {
        val b = Bitmap.createBitmap((1920 * scale).toInt(), ((1080 + 140 + 1080) * scale).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(40, 40, 44))
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(top, null, android.graphics.RectF(0f, 0f, 1920 * scale, 1080 * scale), p)
        c.drawBitmap(bottom, null, android.graphics.RectF((1920 - 1240) / 2f * scale, 1220f * scale, (1920 + 1240) / 2f * scale, 2300f * scale), p)
        file.parentFile?.mkdirs()
        val jpg = file.name.endsWith(".jpg")
        file.outputStream().use { b.compress(if (jpg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, if (jpg) 90 else 100, it) }
    }

    /** a stand-in for the game's first picture (ours: no game pixels): a dark slate with its name */
    private fun gameFrame(): Bitmap {
        val b = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, 1080f, Color.rgb(28, 42, 70), Color.rgb(8, 10, 18), Shader.TileMode.CLAMP) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 200, 210); textSize = 54f; textAlign = Paint.Align.CENTER }
        c.drawText("(the game's first picture: its login screen)", 960f, 860f, p)
        return b
    }

    /** [top] faded to [a] over the game's picture */
    private fun over(game: Bitmap, top: Bitmap, a: Float): Bitmap {
        val b = game.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(b).drawBitmap(top, 0f, 0f, Paint().apply { alpha = (a * 255).toInt().coerceIn(0, 255) })
        return b
    }

    @Test fun shots() {
        val d = out ?: return
        val part = System.getenv("LAUNCH_SHOTS_PART") ?: "all"
        val art = System.getenv("PARTY_ART_ROOT")?.let(::File) ?: ownArt()
        root(art)
        val game = gameFrame()
        LoadingScene.pin(0f, 11L, 1)
        LaunchLoad.begin(now())
        // the steps (the launch's status line), both screens
        fun at(name: String, t: Float, step: String?, p: Float = -1f) {
            step?.let { LaunchLoad.step(it, now()) }
            LaunchLoad.progress(p, now())
            val (top, bottom) = pair(t)
            device(top, bottom, File(d, "$name.png"))
        }
        if (part == "all" || part == "stills") stills(d, ::at, game)
        if (part == "all" || part == "handover")
            frames(File(d, "frames_handover"), game, stays = false, from = LoadingMath.EVERY * 2 - 2f, hand = LoadingMath.EVERY * 2 + 1f,
                to = LoadingMath.EVERY * 4 + 3f)
        if (part == "all" || part == "compare") comparison(File(d, "compare"), game)
        if (part == "all" || part == "final") finals(File(d, "final"), game)
    }

    private fun stills(d: File, shot: (String, Float, String?, Float) -> Unit, game: Bitmap) {
        fun at(name: String, t: Float, step: String?, p: Float = -1f) = shot(name, t, step, p)
        at("L1_play_starting_the_game", 0.6f, null)
        at("L2_installing_with_progress", 2.2f, "Installing Bionic components...", 0.42f)
        at("L3_preparing_the_game_system", 3.4f, "Preparing proton-10.0-arm64ec base...")
        at("L4_launching_the_game", 4.6f, "Launching game...")
        LaunchLoad.windowMapped(now())
        at("L5_opening_the_game_black_frames_under", LoadingMath.EVERY + 0.8f, null)
        // the hand-over: the top fades over the game's picture (the view draws itself at full strength; the fade is its alpha)
        val tHand = LoadingMath.EVERY * 2 + 1.0f
        val (topH, bottomH) = pair(tHand)
        for ((i, a) in listOf(1f, 0.75f, 0.5f, 0.25f, 0f).withIndex()) device(over(game, topH, a), bottomH, File(d, "L6_handover_fade_${i}_alpha_${(a * 100).toInt()}.png"))
        repeat(LaunchLoad.PICTURE_FRAMES) { LaunchLoad.frame(true, now()) }
        // the bottom alone after it: the running pass ends as it began, the next ones are smaller
        LoadingScene.forceSolo = true
        for ((name, dt) in listOf("L7_bottom_alone_running_pass_ends" to 1.5f, "L8_bottom_alone_smaller_swirl" to LoadingMath.EVERY * 1.4f,
                "L9_bottom_alone_later" to LoadingMath.EVERY * 3f)) {
            LoadingScene.clock(tHand + dt)
            device(game, render(StartView(ctx).also { it.line = "Waiting for the game." }, 1240, 1080), File(d, "$name.png"))
        }
        // the language: the same screen in Spanish and French
        LoadingScene.forceSolo = null
        for (lang in listOf("es", "fr")) {
            Tr.init(ctx); Tr.setChoice(ctx, lang)
            LoadingScene.pin(2.2f, 11L, 1)
            LaunchLoad.reset(); LaunchLoad.begin(now()); LaunchLoad.step("Launching game...", now())
            val (top, bottom) = pair(2.2f)
            device(top, bottom, File(d, "L10_launching_$lang.png"))
        }
        Tr.setChoice(ctx, "auto")
    }

    /** frames 10 a second: both screens until [hand], the top fading over the game for FADE_MS, then the bottom alone */
    private fun frames(dir: File, game: Bitmap, stays: Boolean, from: Float, hand: Float, to: Float, step: Float = 0.2f) {
        dir.mkdirs()
        LoadingMath.paintedStays = stays
        LoadingScene.forceSolo = false
        LoadingScene.pin(from, 11L, 1)
        LaunchLoad.reset(); LaunchLoad.begin(now()); LaunchLoad.step("Launching game...", now()); LaunchLoad.windowMapped(now())
        var t = from; var i = 0
        val fade = LaunchLoad.FADE_MS / 1000f
        while (t <= to + 1e-3f) {
            val n0 = System.nanoTime()
            LoadingScene.clock(t)
            if (t >= hand) LoadingScene.forceSolo = true
            val bottom = render(StartView(ctx).also { it.line = "Waiting for the game." }, 1240, 1080)
            val top = when {
                t < hand -> render(LaunchTopView(ctx), 1920, 1080)
                t < hand + fade -> { val a = 1f - (t - hand) / fade; over(game, render(LaunchTopView(ctx), 1920, 1080), a * a * (3 - 2 * a)) }
                else -> game
            }
            device(top, bottom, File(dir, "f%04d.jpg".format(i)), 0.25f)
            if (i < 3) println("frame $i of ${dir.name}: ${(System.nanoTime() - n0) / 1_000_000} ms")
            t += step; i++
        }
        LoadingScene.forceSolo = null
        LoadingMath.paintedStays = LoadingMath.PAINTED_STAYS_DEFAULT
    }

    private val passed = SetupFlow.Inputs(path = "/storage/emulated/0/WoW Forever", folderFound = true, loginSaved = true,
        art = SetupFlow.Art.READY, artPercent = 100, addonsInstalled = true, client = "1.60.1.70634 (up to date)",
        appLine = "DS Forever 1.0.40: up to date")

    /**
     * THE USER'S PICK, RENDERED (2026-11-24: "Painted stays" everywhere): DS Forever's own start-up loading (the checks on the top, the
     * chooser on the bottom) and the game's launch (both screens, the hand-over, the bottom alone), with the readable spark; plus the
     * spark before / after on the same painted moments
     */
    private fun finals(dir: File, game: Bitmap) {
        dir.mkdirs()
        LoadingMath.paintedStays = true
        // the start-up: 16 s, 5 frames a second
        val start = File(dir, "startup_frames").apply { mkdirs() }
        ModeBridge::class.java.getDeclaredField("route").apply { isAccessible = true }.set(ModeBridge, StartMode.Route.Chooser)
        SetupBridge.publish(passed)
        LoadingScene.forceSolo = false
        LoadingScene.pin(0f, 11L, 1)
        var t = 0f; var i = 0
        while (t <= 16f + 1e-3f) {
            LoadingScene.clock(t)
            val top = render(LoadingTopView(ctx), 1920, 1080)
            val chooser = ChooserPanel(ctx).also { it.update() }
            val bottom = if (chooser.visibility == View.VISIBLE) render(chooser, 1240, 1080) else render(StartView(ctx), 1240, 1080)
            device(top, bottom, File(start, "f%04d.jpg".format(i)), 0.25f)
            t += 0.2f; i++
        }
        SetupBridge.publish(null); ModeBridge.reset()
        // the launch: both screens until 10 s, the hand-over, then the bottom alone to 26 s
        frames(File(dir, "launch_frames"), game, stays = true, from = 0f, hand = 10f, to = 26f)
        // the spark before / after (the readable spark off / on), the same moments, painted stays
        LoadingMath.paintedStays = true
        for (readable in listOf(false, true)) {
            LoadingScene.sparkReadable = readable
            LoadingScene.forceSolo = false
            LoadingScene.pin(0f, 11L, 1)
            LaunchLoad.reset(); LaunchLoad.begin(now()); LaunchLoad.step("Launching game...", now())
            for (tt in listOf(LoadingMath.EVERY + 1.2f, LoadingMath.EVERY * 2 + 2.0f, LoadingMath.EVERY * 3 + 1.0f)) {
                val (top, bottom) = pair(tt)
                device(top, bottom, File(dir, "spark_${if (readable) "after" else "before"}_t${(tt * 10).toInt()}.png"))
            }
        }
        LoadingScene.sparkReadable = true
        LoadingScene.forceSolo = null
    }

    /**
     * the user's comparison (2026-11-24: "I also want to try keeping the swirls painted backgrounds ... to see how it looks"): the same
     * seed and times with the approved fade and with painted stays, both screens and the bottom alone
     */
    private fun comparison(dir: File, game: Bitmap) {
        for (stays in listOf(false, true)) {
            val name = if (stays) "stays" else "fade"
            // both screens: 18 s of the launch (5 frames a second)
            frames(File(dir, "${name}_two_screens"), game, stays, from = 0f, hand = 1e9f, to = 18f)
            // the bottom alone: the hand-over at 3 s, then 18 s on the bottom by itself
            frames(File(dir, "${name}_bottom_alone"), game, stays, from = 0f, hand = 3f, to = 21f)
        }
    }
}
