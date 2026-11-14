package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * fix/radials-clip-castbar (the user on 1.0.32-1469: "The spells and ds tool bar radials both also clip that bar"): the real [HomeView]
 * and [HomeRadialView] wired as the host wires them (homeSlot / under), the tap-to-cast bar Left / Right / Both, every spell radial
 * (Hold and Tap) and the DS toolbar's D-pad Up wheel: the wheel's painted box never meets the bar's box as it stands (its slide
 * included), at rest and through a fight's start / end while it is open (the bug: the page laid again under an open wheel). With SLIDES_SHOTS_DIR set (and CLOCK_ART_ROOT for the art), the pictures (radials_*).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RadialsClipShotsTest {
    private val out: File? = System.getenv("SLIDES_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val L = CastStripModel.Side.LEFT
    private val R = CastStripModel.Side.RIGHT
    private val B = CastStripModel.Side.BOTH
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    @After fun down() { CastStripModel.Live.pos = 0f; CastStripModel.Live.side = null; HomeSettings.init(app); HomeSettings.castBarSide = CastStripModel.Side.OFF }

    private val bar get() = CastStripModel.parse(JSONObject("""{"type":"castbar","v":"cb6","n":8,"pick":8,"pp":[39,19],"e":[
        {"s":1,"id":403,"i":136048,"c":0},{"s":2,"id":8042,"i":136026,"c":1},{"s":3,"id":331,"i":136052,"c":2},
        {"s":4,"id":324,"i":136051,"c":3},{"s":5,"id":8017,"i":136086,"c":4},{"s":6,"id":2484,"i":136102,"c":5},
        {"s":7,"id":8018,"i":136015,"c":6},{"s":8,"id":5730,"i":135824,"c":7}]}"""))!!

    /** two spell radials (slots 1 and 2), five spells and six */
    private val spells = JSONObject("""{"type":"sradial","v":"q2","r":{"list":[
        {"u":1,"items":[{"id":403,"col":0,"n":"Lightning Bolt","i":136048},{"id":331,"col":1,"n":"Healing Wave","i":136052},
          {"id":8042,"col":2,"n":"Earth Shock","i":136026},{"id":324,"col":3,"n":"Lightning Shield","i":136051},
          {"id":8017,"col":4,"n":"Rockbiter Weapon","i":136086}]},
        {"u":2,"items":[{"id":2484,"col":5,"n":"Earthbind Totem","i":136102},{"id":8018,"col":6,"n":"Rockbiter","i":136015},
          {"id":5730,"col":7,"n":"Stoneclaw Totem","i":135824},{"id":403,"col":8,"n":"Lightning Bolt","i":136048},
          {"id":331,"col":9,"n":"Healing Wave","i":136052},{"id":8042,"col":10,"n":"Earth Shock","i":136026}]}],
        "max":6,"per":8,"row":"sradial","cells":40,"pad":true}}""")
    /** the D-pad Up wheel's items (the DS toolbar's consumables) */
    private val items = JSONObject("""{"type":"radial","r":{"fight":false,"items":[
        {"col":0,"id":4540,"c":5,"g":1,"n":"Tough Hunk of Bread","i":133964},{"col":1,"id":159,"c":6,"g":1,"n":"Refreshing Spring Water","i":132794},
        {"col":2,"id":118,"c":2,"g":2,"n":"Minor Healing Potion","i":134829},{"col":3,"id":1251,"c":4,"g":3,"n":"Linen Bandage","i":133685}]}}""")

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private class Page(val home: HomeView, val wheel: HomeRadialView, val strip: Strip)

    private fun page(side: CastStripModel.Side): Page {
        System.getenv("CLOCK_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
        HomeSettings.init(app)
        HomeSettings.castBarSide = side
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        lay(home, W, H)
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        CastStripModel.Live.set(bar)
        home.onCastBar(bar)
        home.tick()
        ShadowLooper.idleMainLooper()
        lay(home, W, H)
        val strip = Strip(app) { }
        lay(strip, W, Strip.HEIGHT)
        val wheel = HomeRadialView(app, strip, { null }) { false }
        lay(wheel, W, 1080)
        // as the host wires it (CompanionHost): Home and the wheel's view share their origin here
        wheel.homeSlot = { open -> if (!open) { home.wheelSlot(false); null } else home.wheelSlot(true) }
        wheel.under = { on -> home.wheelPainter = if (!on) null else { c -> wheel.drawUnder(c) } }
        wheel.underRedraw = { home.wheelRedraw() }
        home.onWheelSlot = { r -> wheel.reslot(r) }
        wheel.onDoc(spells); wheel.onDoc(items)
        return Page(home, wheel, strip)
    }

    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)
    private fun castStrip(home: HomeView) = field(home, "castStrip") as CastStripView
    private fun slotOf(w: HomeRadialView) = field(w, "slot") as RectF?
    private fun settle() { for (i in 0 until 50) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }

    /** the bar as it stands now (its slide included) */
    @Suppress("UNCHECKED_CAST")
    private fun barBox(home: HomeView): RectF { val s = castStrip(home)
        // Home's laid box (no window here, so the view's own layout pass lags a re-lay) and the bar's slide
        val r = (field(home, "now") as Map<Any, RectF>).entries.first { it.key.toString() == "STRIP" }.value
        return RectF(r.left + s.translationX, r.top + s.translationY, r.right + s.translationX, r.bottom + s.translationY) }

    /** the shared reader's held keys (PadHub.buttons): the key that opened a HOLD wheel is still down while it shows */
    private fun padHeld(mask: Int) {
        val hub = Class.forName("com.jrees.wowcompanion.PadHub")
        hub.getDeclaredField("buttons").apply { isAccessible = true }.setInt(hub.getDeclaredField("INSTANCE").get(null), mask)
    }
    private val upMask = 1 shl PadInput.SLOT_DPAD_UP

    private fun open(p: Page, src: String, r: Int) {
        RootPolicy.allowed = false                 // as inside the launcher: no su reader (it would reset the held keys)
        assertTrue(PadInput.isHeld(upMask, PadInput.SLOT_DPAD_UP))
        padHeld(upMask)
        p.wheel.open(src, r); settle()
    }

    @After fun keysUp() { padHeld(0); RootPolicy.allowed = true }

    /** each wheel (every spell radial Hold and Tap, the DS toolbar's D-pad Up) per side: its painted box is clear of the bar */
    @Test fun everyWheelClearsTheBar() {
        val wheels = listOf("sradial" to 1, "sradial" to 2, "radial" to 1)
        for (tap in listOf(false, true)) for ((src, r) in wheels) for (side in listOf(L, R, B)) {
            HomeSettings.init(app)
            HomeSettings.radialTap = tap
            HomeSettings.spellTap = tap
            val p = page(side)
            val barRest = barBox(p.home)
            open(p, src, r)
            val slot = slotOf(p.wheel) ?: error("$src#$r $side: no Home slot")
            val paint = UiKit.consumableRadialBox(slot)
            val bar = barBox(p.home)
            val what = "$src#$r ${if (tap) "tap" else "hold"} $side: wheel $paint bar $bar"
            assertTrue(what, !RectF.intersects(paint, bar))
            when (side) {
                B -> assertEquals("$what: Both's bar stays", barRest.left, bar.left, 0.5f)
                L -> assertTrue("$what: Left's bar goes left", bar.left < barRest.left)
                else -> assertTrue("$what: Right's bar goes right", bar.left > barRest.left)
            }
            // a normal size: as tall as the space under the bars allows, at least a third of the page
            assertTrue("$what: normal size", slot.width() >= H / 3f)
            p.wheel.close(); settle()
            assertEquals("$what: the bar is back", barRest.left, barBox(p.home).left, 0.5f)
        }
    }

    private fun fight(home: HomeView, on: Boolean) {
        home.onFull(JSONObject().put("type", "full").put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true))
            .put("hudS", JSONObject().put("c", on))), true)
        home.tick()
        settle()
    }

    private fun clear(p: Page, what: String) {
        val slot = slotOf(p.wheel) ?: error("$what: no Home slot")
        val paint = UiKit.consumableRadialBox(slot)
        val bar = barBox(p.home)
        assertTrue("$what: wheel $paint bar $bar", !RectF.intersects(paint, bar) || castStrip(p.home).visibility != View.VISIBLE)
        assertTrue("$what: the wheel in the page $paint", paint.top >= 0f && paint.bottom <= H + 0.5f)
    }

    /**
     * the wheel in a fight: opened in one (Stay: the bar laid in the takeover; Hide: slid away), a fight starting or ending while it is
     * open (the page lays itself again under the wheel: the bar comes back up / the controller grows)
     */
    @Test fun wheelClearsTheBarThroughAFight() {
        for (combat in CastStripModel.Combat.values()) for (side in listOf(L, R, B)) for (src in listOf("sradial", "radial")) {
            val what = "$src $side ${combat.name}"
            HomeSettings.init(app); HomeSettings.castBarCombat = combat
            run {
                val p = page(side); HomeSettings.castBarCombat = combat
                fight(p.home, true)
                open(p, src, 1); clear(p, "$what: opened in a fight")
                fight(p.home, false); clear(p, "$what: the fight ended while open")
                p.wheel.close(); settle()
            }
            run {
                val p = page(side); HomeSettings.castBarCombat = combat
                open(p, src, 1); clear(p, "$what: opened at rest")
                fight(p.home, true); clear(p, "$what: a fight began while open")
                p.wheel.close(); settle()
                fight(p.home, false)
                assertEquals("$what: the bar back in place", 0f, castStrip(p.home).translationX, 0.5f)
            }
        }
    }

    // ---------------------------------------------------------------- the pictures

    private val pRect = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.MAGENTA }

    private fun shot(p: Page, name: String) {
        val d = out ?: return
        d.mkdirs()
        val bm = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            val c = Canvas(bm); c.drawColor(Theme.ink)
            p.home.draw(c)
            c.save(); c.translate(0f, H.toFloat()); p.strip.draw(c); c.restore()
            p.wheel.draw(c)
            slotOf(p.wheel)?.let { c.drawRect(UiKit.consumableRadialBox(it), pRect) }
            if (pass == 0) { ShadowLooper.idleMainLooper(); Thread.sleep(150); ShadowLooper.idleMainLooper() }
        }
        File(d, "$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun radialShots() {
        if (out == null) return
        val tag = System.getenv("RADIALS_TAG") ?: "after"
        for ((side, s) in listOf(L to "left", B to "both")) {
            val p = page(side); HomeSettings.castBarCombat = CastStripModel.Combat.HIDE
            fight(p.home, true); open(p, "sradial", 1); fight(p.home, false)
            shot(p, "radials_spell_${s}_fightended_$tag")
            val q = page(side); HomeSettings.castBarCombat = CastStripModel.Combat.STAY
            fight(q.home, true); open(q, "sradial", 1)
            shot(q, "radials_spell_${s}_infight_stay_$tag")
        }
        for ((side, s) in listOf(L to "left", R to "right", B to "both")) {
            for ((src, n) in listOf("sradial" to "spell", "radial" to "dstoolbar")) {
                val p = page(side)
                open(p, src, 1)
                shot(p, "radials_${n}_${s}_$tag")
            }
        }
    }
}
