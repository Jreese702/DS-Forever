package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import android.graphics.Paint
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * THE CLOCK + EXTRAS' OFF-DEVICE RENDERS (the user, 2026-11-13: "the clock and extras all kind of look like poop. lets center them where
 * the ZR and LR appear (fade them out when holding ZL and ZR) and make them space out evenly based on what else is turned on"): the whole
 * Home page at rest (1240 x 1080, the Thor's bottom screen) with 1, 3 and 5 extras on, the triggers held, the Hearthstone ready; each as
 * <name>.png and the band alone, 2x, as <name>_band.png. The clock + battery stay the top chip, as at 2e564ee8 (the user: "No the time
 * and Battery still needs to be centered at the top like it was before, do not change that"): [theClockChipIsWhereItWasBefore].
 *
 * Runs only when CLOCK_SHOTS_DIR is set; CLOCK_ART_ROOT = an art root laid out like the app's (ui/kit, fonts, icons.pak). Written against
 * reflection for what only the new code has (the Ammo row), so the same file draws the before pictures on the old code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClockShotsTest {
    private val out: File? = System.getenv("CLOCK_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()

    private fun artRoot() {
        val root = System.getenv("CLOCK_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun settle() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        Thread.sleep(250)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    private fun setField(o: Any, name: String, v: Any) {
        val f = o.javaClass.getDeclaredField(name); f.isAccessible = true; f.set(o, v)
    }

    /** a HomeSettings switch by its property name; false = this code has no such row (the before pictures) */
    private fun setting(name: String, on: Boolean): Boolean {
        val m = HomeSettings::class.java.methods.firstOrNull { it.name == "set" + name.replaceFirstChar { c -> c.uppercase() } } ?: return false
        m.invoke(HomeSettings, on); return true
    }

    private fun save(b: Bitmap, name: String) {
        val d = out ?: return
        d.mkdirs()
        File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // the band alone at 2x (y 0..260)
        val band = Bitmap.createBitmap(b, 0, 0, 1240, 260)
        Bitmap.createScaledBitmap(band, 2480, 520, true).let { s ->
            File(d, "${name}_band.png").outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private class Page(val home: HomeView, val cast: CastView)

    private fun page(): Page {
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        val h = 1080 - Strip.HEIGHT
        lay(home, 1240, h)
        val icons = listOf(136048, 136026, 135990, 136052, 135813, 136015, 135824, 136075)
        val n = JSONArray()
        for (p in 1..8) n.put(JSONObject().put("p", p).put("k", "spell").put("id", 400 + p).put("i", icons[p - 1]))
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", n))))
        home.tick()
        val look = HomeView.Companion.Look(HomeRest.DIAMONDS, false, false, false)
        HomeView::class.java.methods.firstOrNull { it.name.startsWith("previewSettle") }?.invoke(home, look)
        lay(home, 1240, h)
        return Page(home, cast)
    }

    private fun feed(p: Page, ammo: Boolean) {
        val eq = JSONObject().put("slots", JSONArray())
        if (ammo) eq.put("ammo", JSONObject().put("id", 2512).put("icon", 132382).put("count", 1843)).put("ammoNeeded", true)
        p.home.onFull(JSONObject().put("type", "full")
            .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true)))
            .put("bags", JSONObject().put("money", 1_234_567L).put("freeGeneral", 14).put("free", 14).put("total", 66).put("equipped", eq)), true)
        setField(p.home, "clockText", "10:42 AM"); setField(p.home, "batteryPct", 76); setField(p.home, "charging", false)
    }

    private fun draw(p: Page): Bitmap {
        val strip = Strip(app) { }
        val b = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            lay(p.home, 1240, 1080 - Strip.HEIGHT)
            val c = Canvas(b)
            c.drawColor(Theme.ink)
            p.home.draw(c)
            strip.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Strip.HEIGHT, View.MeasureSpec.EXACTLY))
            strip.layout(0, 0, 1240, Strip.HEIGHT)
            c.save(); c.translate(0f, (1080 - Strip.HEIGHT).toFloat()); strip.draw(c); c.restore()
            if (pass == 0) settle()
        }
        return b
    }

    /** ZL and ZR held (the Cast view's own pad callback), the join's and the group's fades run out */
    private fun triggers(p: Page, lt: Boolean, rt: Boolean) {
        val m = CastView::class.java.getDeclaredMethod("onPad", Boolean::class.java, Boolean::class.java, Boolean::class.java, Boolean::class.java)
        m.isAccessible = true
        m.invoke(p.cast, lt, rt, false, false)
        ShadowSystemClock.advanceBy(Duration.ofMillis(400))
    }

    private fun items(hearth: Boolean, tele: Boolean, gold: Boolean, bags: Boolean, ammo: Boolean) {
        HomeSettings.init(app)
        HomeSettings.clockHearth = hearth; HomeSettings.clockTeleport = tele; HomeSettings.clockGold = gold; HomeSettings.clockBags = bags
        setting("clockAmmo", ammo)
    }

    private fun hearth(cooling: Boolean) {
        ItemCd.reset()
        val t = if (cooling) """"t":{"6948":{"d":1800,"l":754}},""" else ""
        ItemCd.onDoc(JSONObject("""{"type":"itemcd",$t"hs":{"have":true,"i":134414},"tp":{"id":556,"n":"Astral Recall","i":136010}}"""))
    }

    /** every drawText Home makes: (text, x, y, size, align) */
    private class Rec(b: Bitmap) : Canvas(b) {
        class T(val text: String, val x: Float, val y: Float, val size: Float, val align: Paint.Align)
        val texts = ArrayList<T>()
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            texts += T(text, x, y, paint.textSize, paint.textAlign); super.drawText(text, x, y, paint)
        }
    }

    /**
     * THE CLOCK CHIP AS AT 2e564ee8 (HomeView: UiKit.clockChip(c, b.centerX(), b.top + 10f, ...)): the time centred on the bars' middle
     * at 32 px, its baseline at top + 31 centred; the battery's percentage at 21 px right of the 28 x 14 case, at top + 62 - with
     * every extra on, ZL + ZR held or not (the chip does not fade under the triggers, as before)
     */
    @Test fun theClockChipIsWhereItWasBefore() {
        artRoot()                                                             // the same art as the shots (first test to load it)
        for (held in listOf(false, true)) {
            items(true, true, true, true, true); hearth(true)
            val p = page(); feed(p, true)
            if (held) triggers(p, true, true)
            lay(p.home, 1240, 1080 - Strip.HEIGHT)
            val rec = Rec(Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888))
            p.home.draw(rec)
            val f = HomeView::class.java.getDeclaredField("now"); f.isAccessible = true
            @Suppress("UNCHECKED_CAST") val b = (f.get(p.home) as Map<Any, RectF>).entries.first { it.key.toString() == "BARS" }.value
            val top = b.top + 10f
            val pt = UiKit.numberPaint(32f); val pp = UiKit.numberPaint(21f)
            val time = rec.texts.firstOrNull { it.text == "10:42 AM" }
            assertNotNull("held=$held: the time", time)
            assertEquals(32f, time!!.size, 0f); assertEquals(Paint.Align.CENTER, time.align)
            assertEquals(b.centerX(), time.x, 0.01f)
            assertEquals(top + 31f - (pt.ascent() + pt.descent()) / 2f, time.y, 0.01f)
            val pct = rec.texts.firstOrNull { it.text == "76%" }
            assertNotNull("held=$held: the battery", pct)
            val row = 28f + 3f + 9f + pp.measureText("76%")
            assertEquals(21f, pct!!.size, 0f); assertEquals(Paint.Align.LEFT, pct.align)
            assertEquals(b.centerX() - row / 2f + 28f + 12f, pct.x, 0.01f)
            assertEquals(top + 62f - (pp.ascent() + pp.descent()) / 2f, pct.y, 0.01f)
        }
        ItemCd.reset()
    }

    @Test fun shots() {
        assumeTrue(out != null)
        artRoot()
        val tag = System.getenv("CLOCK_SHOTS_TAG") ?: "fix"
        // 1 extra: the Hearthstone (cooling) under the clock chip
        items(true, false, false, false, false); hearth(true)
        page().also { feed(it, true); save(draw(it), "${tag}_n1") }
        // 3 extras: Hearthstone (cooling), gold, free bag slots
        items(true, false, true, true, false); hearth(true)
        page().also { feed(it, true); save(draw(it), "${tag}_n3") }
        // 5 extras: Hearthstone, class teleport, gold, free bag slots, ammo
        items(true, true, true, true, true); hearth(true)
        page().also { feed(it, true); save(draw(it), "${tag}_n5") }
        // 5 extras, the Hearthstone ready (its glow)
        items(true, true, true, true, true); hearth(false)
        page().also { feed(it, true); save(draw(it), "${tag}_n5_ready") }
        // 5 extras, ZL + ZR held: the join in the spot, the row faded out, the clock chip as it was
        items(true, true, true, true, true); hearth(true)
        page().also { feed(it, true); triggers(it, true, true); save(draw(it), "${tag}_n5_zlzr_held") }
        ItemCd.reset()
    }
}
