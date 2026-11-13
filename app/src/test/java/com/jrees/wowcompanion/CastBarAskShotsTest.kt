package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * THE ONE-TIME "TRY THE NEW TAP-TO-CAST BAR?" PROMPT (the user, 2026-11-13: "a one time pop up asking people if they want to try the
 * new touch input bar" and "show a little picture with the pop up showing how it looks with it animating away in combat"): the real
 * [SetupView] with the question's picture - touch Yes / No, the D-pad's gold box + A, B = No - and with CASTBAR_SHOTS_DIR set the
 * prompt at rest, mid-slide and slid away (ask_prompt_rest / _mid / _hidden.png, plus ask_prompt.png = rest with the pad's glyphs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CastBarAskShotsTest {
    private val out: File? = System.getenv("CASTBAR_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240; private val H = 1080 - Strip.HEIGHT

    private val PIC_H = 400f

    /** the picture's spells: a level-6 Shaman's book (the player's own bar empty: their first known spells) */
    private val known = listOf(403 to 136048, 8042 to 136026, 331 to 136052, 324 to 136051, 8017 to 136086, 2484 to 136102,
        8018 to 136015, 5730 to 135824)

    private fun prompt(answers: MutableList<Boolean>, frames: PreviewStage.AskFrames? = null): SetupView {
        val v = SetupView(app)
        val f = frames ?: PreviewStage.AskFrames(app, CastBarAsk.pictureBar(null, known, { true }), PIC_H / 1080f)
        v.ask(CompanionSetup.Prompt(Tr.t(CastBarAsk.TITLE), emptyList(), ""), "Yes", "No",
            onYes = { answers.add(true) }, onNo = { answers.add(false) },
            picture = SetupView.Picture(PIC_H, animated = true) { c, r, t -> f.draw(c, r, t) }, cursor = true)
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, W, H)
        return v
    }

    private fun render(v: View): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Theme.ink); v.draw(c)
        return b
    }

    private fun tapAt(v: View, x: Float, y: Float) {
        val t0 = SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0); v.onTouchEvent(d); d.recycle()
        val u = MotionEvent.obtain(t0, t0 + 50, MotionEvent.ACTION_UP, x, y, 0); v.onTouchEvent(u); u.recycle()
    }

    /** the buttons' centres (SetupView lays them 20 px either side of the middle, 300 wide, 111 tall, 50 above the box's foot) */
    private fun buttonY(v: SetupView): Float {
        // the bottom-most row of the gold-edged buttons: found from the drawing's hit boxes
        val f = SetupView::class.java.getDeclaredField("hits"); f.isAccessible = true
        render(v)
        @Suppress("UNCHECKED_CAST") val hits = f.get(v) as List<Pair<android.graphics.RectF, String>>
        return hits.first { it.second == "setup" }.first.centerY()
    }

    @Test fun touchYesAndNo() {
        val a = ArrayList<Boolean>()
        val v = prompt(a)
        val y = buttonY(v)
        tapAt(v, W / 2f - 170f, y); assertEquals(listOf(true), a)
        tapAt(v, W / 2f + 170f, y); assertEquals(listOf(true, false), a)
    }

    @Test fun padBoxAndA_BIsNo() {
        val a = ArrayList<Boolean>()
        val v = prompt(a)
        // attached (the press lands after its 120 ms pushed-in look: View.postDelayed needs a window)
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        act.setContentView(v); idle()
        v.padKey("PAD1"); idle(); assertTrue("no pad until Home holds it", a.isEmpty())
        v.padOn = true
        assertTrue(v.focusedYes)
        v.padKey("PADDRIGHT"); assertFalse(v.focusedYes)
        v.padKey("PAD1"); idle(); assertEquals(listOf(false), a)                  // A on the boxed No
        v.padKey("PADDLEFT"); assertTrue(v.focusedYes)
        v.padKey("PAD1"); idle(); assertEquals(listOf(false, true), a)
        v.padKey("PAD2"); idle(); assertEquals(listOf(false, true, false), a)    // B = No, wherever the box is
    }

    @Test fun animatesOnlyWhileShown() {
        val v = prompt(ArrayList())
        v.close()
        assertEquals(View.GONE, v.visibility)
        // closed: the picture is dropped, so nothing keeps asking for frames
        val f = SetupView::class.java.getDeclaredField("picture"); f.isAccessible = true
        assertEquals(null, f.get(v))
    }

    private fun idle() { ShadowSystemClock.advanceBy(Duration.ofMillis(200)); ShadowLooper.idleMainLooper() }

    private fun artRoot() {
        val root = System.getenv("CLOCK_ART_ROOT") ?: return
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root))
    }

    /** the game's documents as the stage gets them on the device: the full one (the controller's eight spells, the minimap's place in
     *  Durotar, the bags), a few chat lines */
    private fun feedStage() {
        val icons = listOf(136048, 136026, 135990, 136052, 135813, 136015, 135824, 136075)
        val n = JSONArray()
        for (p in 1..8) n.put(JSONObject().put("p", p).put("k", "spell").put("id", 400 + p).put("i", icons[p - 1]))
        PreviewStage.feedFull(JSONObject().put("type", "full")
            .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
                .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", n)))
                .put("hudS", JSONObject().put("c", false).put("g", 1000.0)))
            .put("map", JSONObject().put("ok", true).put("mapID", 1411).put("zone", "Durotar").put("subzone", "Razor Hill")
                .put("rect", JSONObject().put("c", 1).put("t", 1808.33).put("b", -1716.67).put("l", -1962.5).put("r", -7250.0))
                .put("x", 0.5177).put("y", 0.4279).put("wx", 300.0).put("wy", -4700.0).put("facing", 2.11))
            .put("bags", JSONObject().put("money", 1_234_567L).put("freeGeneral", 14).put("free", 14).put("total", 66)))
        val lines = JSONArray()
        for ((ch, who, text) in listOf(Triple("say", "Grukk", "anyone up for Burning Blade Coven?"), Triple("guild", "Zalaya", "grats on 6!"),
                Triple("party", "Mogra", "I'll tank, pull on 3"), Triple("system", "", "You have learned a new spell: Earth Shock."),
                Triple("say", "Torrik", "lfg Razor Hill quests"))) lines.put(JSONObject().put("ch", ch).put("who", who).put("text", text).put("i", lines.length() + 1))
        PreviewStage.feedChat(JSONObject().put("lines", lines).put("s", 1))
    }

    private fun settle() { ShadowLooper.idleMainLooper(); Thread.sleep(300); ShadowSystemClock.advanceBy(Duration.ofMillis(300)); ShadowLooper.idleMainLooper() }

    /** the real Home's frames: every one drawn (both passes), the minimap's tiles decoded between them */
    @Test fun theFramesAreTheRealHome() {
        artRoot(); feedStage()
        val f = PreviewStage.AskFrames(app, CastBarAsk.pictureBar(null, known, { true }), PIC_H / 1080f)
        var n = 0
        while (!f.finished && n < 100) { assertTrue(f.advance()); n++; if (n % 6 == 0) settle() }
        assertTrue(f.done)
        assertTrue(f.frames.all { it != null })
        assertEquals((1240 * PIC_H / 1080f).toInt(), f.frames[0]!!.width)
        // rest and slid away differ (the bar went, the columns grew)
        assertFalse(f.frames[0]!!.sameAs(f.frames[f.frames.size - 1]))
    }

    @Test fun shots() {
        val d = out ?: return
        d.mkdirs()
        artRoot(); feedStage()
        val f = PreviewStage.AskFrames(app, CastBarAsk.pictureBar(null, known, { true }), PIC_H / 1080f)
        var n = 0
        while (!f.finished && n < 100) { f.advance(); n++; if (n % 6 == 0) settle() }
        settle()
        val v = prompt(ArrayList(), f)
        v.padOn = true
        val start = SystemClock.uptimeMillis()
        for ((ms, tag) in listOf(200L to "rest", 900L to "fight", 1_275L to "mid", 2_000L to "hidden", 2_900L to "back")) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(start + ms - SystemClock.uptimeMillis()))
            val b = render(v)
            File(d, "ask_prompt_$tag.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (tag == "rest") File(d, "ask_prompt.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // the picture alone at full size (Home's 1240 x 1080), rest and slid away
        for ((i, tag) in listOf(0 to "rest", f.frames.size - 1 to "hidden")) {
            val big = PreviewStage.AskFrames(app, CastBarAsk.pictureBar(null, known, { true }), 1f)
            var m = 0
            while (!big.finished && m < 100) { big.advance(); m++; if (m % 6 == 0) settle() }
            big.frames[i]?.let { b -> File(d, "ask_picture_full_$tag.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }
}
