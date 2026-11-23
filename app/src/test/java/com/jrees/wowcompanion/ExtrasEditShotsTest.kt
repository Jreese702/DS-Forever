package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * THE EXTRAS REORDERED IN EDIT HOME (feat/extras-polish; the user, 2026-11-23: "I also want to be able to change the order on these"), on
 * the real Home: the controller (A lifts the extra, Left / Right carry it, A drops) and a finger (a drag along the row: the others make way,
 * the drop settles it), the order saved with this character's arrangement (autosaved), shown outside edit mode, Reset Home to Classic
 * putting today's back; the pet happiness extra in the row only while a pet with happiness is out. Pictures with EXTRAS_SHOTS_DIR
 * (the band, 2x): <tag>_edit_*.png; the assertions run always.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ExtrasEditShotsTest {
    private val out: File? = System.getenv("EXTRAS_SHOTS_DIR")?.let { File(it).apply { mkdirs() } }
    private val tag = System.getenv("EXTRAS_SHOTS_TAG") ?: "after"
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private val homes = ArrayList<HomeView>()

    @After fun down() {
        for (h in homes) h.editCancel()
        HomeView::class.java.getDeclaredField("liveDraft").apply { isAccessible = true }.set(null, null)
        HomeLayoutStore.character(null); HomeLayoutStore.set(HomeLayout())
        ItemCd.reset()
    }

    private fun lay(v: View, w: Int = W, h: Int = H) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun settle(n: Int = 30) { for (i in 0 until n) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }
    private fun setField(o: Any, name: String, v: Any?) { o.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(o, v) }
    @Suppress("UNCHECKED_CAST")
    private fun cells(home: HomeView): Map<String, RectF> =
        (home.javaClass.getDeclaredField("bandCells").apply { isAccessible = true }.get(home) as Map<String, RectF>).mapValues { RectF(it.value) }
    private fun bars(home: HomeView): RectF {
        @Suppress("UNCHECKED_CAST") val m = home.javaClass.getDeclaredField("now").apply { isAccessible = true }.get(home) as Map<Any, RectF>
        return RectF(m.entries.first { it.key.toString() == "BARS" }.value)
    }

    private fun page(): HomeView {
        System.getenv("CLOCK_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
        ItemCd.reset()
        app.getSharedPreferences("home", 0).edit().clear().commit()
        HomeSettings.init(app); Settings.init(app)
        HomeLayoutStore.character(null)
        HomeLayoutStore.set(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT))
        HomeSettings.clockHearth = true; HomeSettings.clockTeleport = false; HomeSettings.clockGold = true; HomeSettings.clockBags = true
        HomeSettings.clockAmmo = true; HomeSettings.clockPet = true
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        homes += home
        home.borrow()
        lay(home)
        val icons = listOf(136048, 136026, 135990, 136052, 135813, 136015, 135824, 136075)
        val n = JSONArray()
        for (p in 1..8) n.put(JSONObject().put("p", p).put("k", "spell").put("id", 400 + p).put("i", icons[p - 1]))
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", n))))
        // the user's bags: 60 / 66 used, a 16-slot quiver, 1843 arrows worn; a happy hunter's pet
        val eq = JSONObject().put("slots", JSONArray())
            .put("ammo", JSONObject().put("id", 2512).put("icon", 132382).put("count", 1843).put("stack", 200)).put("ammoNeeded", true)
        val bags = JSONArray()
            .put(JSONObject().put("bag", 0).put("slots", 16).put("free", 2)).put(JSONObject().put("bag", 1).put("slots", 18).put("free", 1))
            .put(JSONObject().put("bag", 2).put("slots", 16).put("free", 2)).put(JSONObject().put("bag", 3).put("slots", 16).put("free", 1))
            .put(JSONObject().put("bag", 4).put("slots", 16).put("free", 0).put("fam", 1).put("icon", 134407))
        home.onFull(JSONObject().put("type", "full").put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true)))
            .put("bags", JSONObject().put("money", 1_234_567L).put("bags", bags).put("equipped", eq)), true)
        HomeLayoutStore.character("Korvash-Realm")
        HomeLayoutStore.set(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT))
        ItemCd.onDoc(JSONObject("""{"type":"itemcd","v":"r28","who":"Korvash-Realm","hs":{"have":true,"i":134414},"pet":{"h":3}}"""))
        home.tick()
        ShadowLooper.idleMainLooper()
        lay(home)
        HomeView::class.java.getDeclaredMethod("changed").apply { isAccessible = true }.invoke(home)
        setField(home, "clockText", "10:42 AM"); setField(home, "batteryPct", 76); setField(home, "charging", false)
        settle()
        lay(home)
        return home
    }

    private fun draw(home: HomeView): Bitmap {
        val b = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        setField(home, "clockText", "10:42 AM"); setField(home, "batteryPct", 76)
        val c = Canvas(b); c.drawColor(Theme.ink); home.draw(c)
        return b
    }
    private fun shot(home: HomeView, name: String) {
        val d = out ?: return
        val b = draw(home)
        val bar = bars(home)
        val band = Bitmap.createBitmap(b, 300, bar.top.toInt().coerceAtLeast(0), 640, 240)
        Bitmap.createScaledBitmap(band, 1280, 480, true).let { s ->
            File(d, "${tag}_edit_$name.png").outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private var downAt = 0L
    private fun touch(home: HomeView, action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) downAt = android.os.SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(downAt, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
        home.dispatchTouchEvent(e); e.recycle()
    }

    private fun rowOrder(home: HomeView): List<String> =
        cells(home).filterKeys { it != "gold" && it != "plus" }.entries.sortedBy { it.value.centerX() }.map { it.key }

    @Test fun reorderInEditHome() {
        val home = page()
        home.forceLive = true
        shot(home, "00_rest")
        assertTrue(home.editOpen(EditStop.EXTRAS)); settle(40)
        draw(home)
        val m = home.editModel()!!
        assertEquals("the walk: the gold line, then the row (the pet out: its face too)", listOf("gold", "hearth", "bags", "ammo", "pet"), m.extrasOn)
        assertEquals(listOf("hearth", "bags", "ammo", "pet"), rowOrder(home))
        shot(home, "01_open")
        // THE CONTROLLER: Right to the Hearthstone, A lifts it, Right twice carries it past the bags and the ammo, A drops
        home.editKey("PADDRIGHT"); settle(4)
        assertEquals("hearth", m.extra)
        home.editKey("PAD1"); settle(12)
        assertEquals("hearth", m.extraLifted)
        shot(home, "02_ctl_lifted")
        home.editKey("PADDRIGHT"); settle(3)
        shot(home, "03_ctl_step_gliding")
        home.editKey("PADDRIGHT"); settle(20)
        assertEquals(listOf("bags", "ammo", "hearth", "pet"), rowOrder(home))
        shot(home, "04_ctl_carried")
        home.editKey("PAD1"); settle(30)
        assertNull(m.extraLifted)
        shot(home, "05_ctl_dropped")
        // (the class teleport is off: it keeps its place in the whole order)
        assertEquals("autosaved with this character's arrangement", listOf("teleport", "bags", "ammo", "hearth", "pet"),
            HomeSettings.layout.extras)
        // A FINGER: the pet carried from the row's end to its start
        draw(home)
        val c0 = cells(home)
        val pet = c0.getValue("pet"); val first = c0.getValue("bags")
        val y = pet.top + pet.height() * 0.6f
        touch(home, MotionEvent.ACTION_DOWN, pet.centerX(), y); settle(2)
        var x = pet.centerX()
        val to = first.centerX()
        while (x > to) { x = maxOf(to, x - 24f); touch(home, MotionEvent.ACTION_MOVE, x, y); settle(2) }
        settle(8); draw(home)
        assertEquals("the finger lifted the pet", "pet", m.extraLifted)
        assertNotNull(m.drag)
        shot(home, "06_drag_mid")
        touch(home, MotionEvent.ACTION_UP, x, y); settle(40)
        assertNull(m.drag); assertNull(m.extraLifted)
        assertEquals(listOf("pet", "bags", "ammo", "hearth"), rowOrder(home))
        shot(home, "07_drag_dropped")
        assertEquals(listOf("teleport", "pet", "bags", "ammo", "hearth"), HomeSettings.layout.extras)
        // Done: Home's row in the new order (outside edit mode)
        home.editKey("PADFORWARD"); settle(40)
        assertFalse(home.editing)
        shot(home, "08_done")
        // the order is this character's: another one keeps today's
        HomeLayoutStore.character("Thrandil-Realm")
        assertEquals(ClockBand.DEFAULT, HomeSettings.layout.extras)
        HomeLayoutStore.character("Korvash-Realm")
        assertEquals(listOf("teleport", "pet", "bags", "ammo", "hearth"), HomeSettings.layout.extras)
        // Reset Home to Classic: today's order back
        HomeSettings.resetHomeToClassic(); settle(20)
        assertEquals(ClockBand.DEFAULT, HomeSettings.layout.extras)
        shot(home, "09_reset_classic")
    }

    /** the pet's face goes with the pet: no pet with happiness out = no pet extra, in the row and in edit mode (nothing to arrange) */
    @Test fun noPetNoPetExtra() {
        val home = page()
        ItemCd.onDoc(JSONObject("""{"type":"itemcd","v":"r28","who":"Korvash-Realm","hs":{"have":true,"i":134414}}"""))
        settle(4)
        val items = HomeView::class.java.getDeclaredMethod("bandItems").apply { isAccessible = true }.invoke(home) as List<*>
        assertEquals("the Hearthstone, the bags, the ammo", 3, items.size)
        assertTrue(home.editOpen(EditStop.EXTRAS)); settle(30)
        val m = home.editModel()!!
        assertFalse("pet" in m.extrasOn); assertFalse("pet" in m.extrasOff)
        shot(home, "10_no_pet")
        // a pet called: its face comes in, in its place in the order
        ItemCd.onDoc(JSONObject("""{"type":"itemcd","v":"r28","who":"Korvash-Realm","hs":{"have":true,"i":134414},"pet":{"h":2}}"""))
        home.editKey("PADDRIGHT"); settle(10)
        assertTrue("pet" in m.extrasOn)
        draw(home)
        assertEquals(listOf("hearth", "bags", "ammo", "pet"), rowOrder(home))
        shot(home, "11_pet_called")
    }
}
