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
 * THE MACROS ROOM AND THE CALENDAR BUTTON, THE OFF-DEVICE RENDER (the user, 2026-11-13: "Lets scrap the smart ranged attack and start
 * building a macro room. The calender can be moved up to a button on the right side of the map name make it small. Use the room slot for
 * macros."). The room is drawn from the add-on's own documents (tools/test_macro_room.py with MACRO_DUMP=<MACRO_SHOTS_DIR>/docs): the
 * grid, the editor, the pick list, the recipe tiles, the keyboard, the Bind box it hosts, the lock; Home is drawn with the Calendar
 * button at rest (a long zone name, an invitation waiting), in the pause menu's slide and in the fight view. Runs only when
 * MACRO_SHOTS_DIR is set; MACRO_ART_ROOT = an art root laid out like the app's. The geometry checks run always.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MacrosShotsTest {
    private val out: File? = System.getenv("MACRO_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    private fun art() {
        System.getenv("MACRO_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
    }

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun settle() { for (i in 0 until 30) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }

    private fun doc(name: String): JSONObject = JSONObject(File(out, "docs/$name.json").readText())

    /** the room on the bar's screen: the room above, the bar under it (Macros lit) */
    private fun roomShot(name: String, overlay: View? = null) {
        val d = out ?: return
        val v = MacrosView(app).apply { dryRun = true }
        v.onDoc(doc(name), live = false)
        lay(v, W, H); settle(); lay(v, W, H)
        overlay?.let { lay(it, W, 1080) }
        val bm = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        val strip = Strip(app) { }.apply { active = Room.MACROS }
        for (pass in 0..1) {
            val c = Canvas(bm); c.drawColor(Theme.ink)
            v.draw(c)
            lay(strip, W, Strip.HEIGHT)
            c.save(); c.translate(0f, H.toFloat()); strip.draw(c); c.restore()
            overlay?.draw(c)
            if (pass == 0) { ShadowLooper.idleMainLooper(); Thread.sleep(120); ShadowLooper.idleMainLooper() }
        }
        File(d, "room_$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun roomShots() {
        if (out == null || !File(out, "docs/grid.json").isFile) return
        art()
        for (n in listOf("grid", "edit", "pick", "recipes", "kbd", "off")) roomShot(n)
        // (Spells s10 review) a bind of a name two macros share, refused ("Name taken"); the row made but not placed ("No pad row")
        for (n in listOf("taken", "norow")) if (File(out, "docs/$n.json").isFile) roomShot(n)
        // the Bind box the room hosts (Book b63): SpellsOverlay over the room, the macro where a spell stands
        val ov = SpellsOverlay(app, otherReader = { true })
        SpellsModel.parseCtl(JSONObject(File(out, "docs/bind_ctl.json").readText()))?.let { ov.onCtl(it) }
        ov.visibility = View.VISIBLE
        roomShot("bind", ov)
    }

    // ---------------------------------------------------------------- Home's Calendar button

    private lateinit var cast: CastView

    private fun home(zone: String): HomeView {
        art()
        HomeSettings.init(app)
        val holder = FrameLayout(app)
        val map = MapView(app); cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        lay(home, W, H)
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        home.onFull(JSONObject().put("type", "full").put("map", JSONObject().put("zone", zone).put("pvp", "contested"))
            .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true))), true)
        home.calPending = true
        home.tick()
        ShadowLooper.idleMainLooper()
        lay(home, W, H)
        // the page laid once it has its size (on the device the size's post does it; a detached view never runs a post)
        HomeView::class.java.getDeclaredMethod("changed").apply { isAccessible = true }.invoke(home)
        settle()
        lay(home, W, H)
        return home
    }

    private fun fight(home: HomeView, on: Boolean) {
        home.onFull(JSONObject().put("type", "full").put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true))
            .put("hudS", JSONObject().put("c", on))), true)
        home.tick()
        settle()
        lay(home, W, H)
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapBox(home: HomeView): RectF {
        val f = HomeView::class.java.getDeclaredField("now"); f.isAccessible = true
        val now = f.get(home) as Map<Any, RectF>
        val fs = HomeView::class.java.getDeclaredField("shown"); fs.isAccessible = true
        return RectF(now.entries.firstOrNull { it.key.toString() == "MAP" }?.value ?: error("no MAP: ${now.keys} shown=${fs.get(home)} w=${home.width}"))
    }

    private val pBox = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.MAGENTA }

    private fun homeShot(home: HomeView, name: String, mark: RectF? = null) {
        val d = out ?: return
        d.mkdirs()
        val bm = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        val strip = Strip(app) { }
        for (pass in 0..1) {
            val c = Canvas(bm); c.drawColor(Theme.ink)
            home.draw(c)
            mark?.let { c.drawRect(it, pBox) }
            lay(strip, W, Strip.HEIGHT)
            c.save(); c.translate(0f, H.toFloat()); strip.draw(c); c.restore()
            if (pass == 0) { ShadowLooper.idleMainLooper(); Thread.sleep(150); ShadowLooper.idleMainLooper() }
        }
        File(d, "$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** the button at rest: the band's right end, the band shortened; the map's box as laid */
    @Test fun calendarButtonAtRest() {
        val home = home("Hillsbrad Foothills")
        val m = mapBox(home)
        val b = HomeCalendarButton.box(m.right, m.top)
        assertEquals(m.right - 8f, b[2], 0.5f)
        assertEquals(m.top + 8f, b[1], 0.5f)
        assertTrue("the button is inside the map", b[0] > m.left && b[3] < m.bottom)
        homeShot(home, "home_calendar_rest", if (System.getenv("MACRO_MARK") != null) RectF(b[0], b[1], b[2], b[3]) else null)
        homeShot(home("The Great Sea of Endless Storms and Grief"), "home_calendar_long_name")
    }

    @Test fun calendarButtonInThePauseSlide() {
        if (out == null) return
        // the pause menu's slide: the button goes out with the band and the map, on the same curve
        for ((k, tag) in listOf(0.08f to "start", 0.2f to "mid")) {
            val home = home("Stormwind City")
            home.pauseSlide(UiKit.PAUSE_OPEN_MS * k)
            homeShot(home, "home_calendar_pause_$tag")
        }
    }

    @Test fun calendarButtonInAFight() {
        val home = home("Elwynn Forest")
        fight(home, true)
        assertTrue(home.fightShown())
        val m = mapBox(home)
        val b = HomeCalendarButton.box(m.right, m.top)
        assertTrue("the fight view's map has the button", b[0] > m.left)
        homeShot(home, "home_calendar_fight")
    }
}
