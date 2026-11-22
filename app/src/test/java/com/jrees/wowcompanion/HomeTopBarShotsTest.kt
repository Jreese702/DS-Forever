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
 * THE TAP-TO-CAST BAR ABOVE ITS COLUMN (the user on 1516: "The tap to cast should be able to move to be above the chat or map as well")
 * on the real [HomeView]: Above chat / Above map laid as the solver says at 1/2 and 1/3, following a swap; a fight's Hide slides it up
 * BEHIND the band (clipped at the band's foot: the band's pixels are the same with and without it); edit mode's controller ladder (Up
 * from Under = Above) and the finger's drag to the top; the bar's menu opens below it and the host column's menu starts under it; hold to
 * talk's pop-up covers it and swallows its taps. With HOME_TOP_BAR_SHOTS (and HOME_LAYOUT_ART = an art root) every state is drawn:
 *   HOME_TOP_BAR_SHOTS=<dir> HOME_LAYOUT_ART=<root> gradlew :app:testModernDebugUnitTest --tests "*HomeTopBarShotsTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeTopBarShotsTest {
    private val out: File? = System.getenv("HOME_TOP_BAR_SHOTS")?.let { File(it).apply { mkdirs() } }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    private val homes = ArrayList<HomeView>()
    @After fun down() {
        for (h in homes) h.editCancel()
        HomeView::class.java.getDeclaredField("liveDraft").apply { isAccessible = true }.set(null, null)
        setField(PartyModel.live, "party", null); HomeLayoutStore.character(null); HomeLayoutStore.set(HomeLayout())
    }

    private fun lay(v: View, w: Int = W, h: Int = H) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun settle(n: Int = 30) { for (i in 0 until n) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }
    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)
    private fun setField(o: Any, name: String, v: Any?) { o.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(o, v) }

    private val castDoc = JSONObject("""{"type":"castbar","v":"cb6","n":8,"pick":8,"pp":[39,19],"e":[
        {"s":1,"id":403,"i":136048,"c":0},{"s":2,"id":8042,"i":136026,"c":1},{"s":3,"id":331,"i":136052,"c":2},
        {"s":4,"id":324,"i":136051,"c":3},{"s":5,"id":8017,"i":136086,"c":4},{"s":6,"id":2484,"i":136102,"c":5},
        {"s":7,"id":8018,"i":136015,"c":6},{"s":8,"id":5730,"i":135824,"c":7}]}""")
    private val PAD = JSONObject().put("v", "pad22").put("x", 0).put("y", 12).put("cw", 6).put("ch", 2).put("cols", 40).put("pw", 1920).put("ph", 1080)
        .put("rows", JSONObject().put("qpick", 3).put("qact", 4).put("pfocus", 9))
        .put("sizes", JSONObject().put("qpick", 40).put("qact", 40).put("pfocus", 31))
    private fun quests(fight: Boolean): JSONObject {
        val focused = JSONObject().put("id", 60).put("title", "Kobold Candles").put("zone", "Elwynn Forest").put("lv", 7).put("slot", 1)
            .put("watch", 1).put("focus", true)
            .put("obj", JSONArray().put(JSONObject().put("t", "Wax Candles: 3/8").put("have", 3).put("need", 8)))
        return JSONObject().put("plv", 7).put("focus", 60).put("rwMode", "id").put("list", JSONArray().put(focused)).put("pad", PAD).put("cb", fight)
    }
    private val chatLines = listOf("general" to "[General] Thornbrook: LF2M Deadmines", "guild" to "[Guild] Mirelle: grats on 12!",
        "say" to "Oswin says: anyone near Goldshire?", "loot" to "You receive loot: Broken Boar Tusk.",
        "whisper" to "Seraphine whispers: inv pls", "party" to "[Party] Iselle: pulling next pack",
        "say" to "Korvash says: on my way", "system" to "Quest accepted: Kobold Candles")

    private class Page(val home: HomeView, val cast: CastView)

    private fun art() { System.getenv("HOME_LAYOUT_ART")?.let { root ->
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) } }

    private fun full(fight: Boolean) = JSONObject().put("type", "full").put("map", JSONObject().put("zone", "Elwynn Forest").put("pvp", "contested"))
        .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true).put("hudS", JSONObject().put("c", fight))))

    private fun page(layout: HomeLayout, fight: Boolean = false): Page {
        art()
        app.getSharedPreferences("home", 0).edit().clear().commit()
        HomeSettings.init(app); Settings.init(app)
        HomeLayoutStore.character(null)
        HomeLayoutStore.set(layout)
        CastStripModel.Live.pos = 0f; CastStripModel.Live.side = null
        setField(PartyModel.live, "party", null)
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        homes += home
        home.borrow()
        lay(home)
        cast.roomBarOn = true
        cast.roomBar = HoldTalk.DEFAULT_DIRS
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        val bar = CastStripModel.parse(castDoc)!!
        CastStripModel.Live.set(bar); home.onCastBar(bar)
        home.onFull(full(fight), true)
        val q = quests(fight)
        QuestItem.Cd.onQuests(q, true); map.onQuests(q); home.onQuests(q, true)
        val lines = JSONArray()
        for ((i, l) in chatLines.withIndex()) lines.put(JSONObject().put("i", i + 1).put("ch", l.first).put("text", l.second))
        home.onChat(JSONObject().put("v", "chat40").put("s", 1).put("n", chatLines.size).put("lines", lines))
        home.partyChanged()
        home.tick()
        ShadowLooper.idleMainLooper()
        lay(home)
        HomeView::class.java.getDeclaredMethod("changed").apply { isAccessible = true }.invoke(home)
        settle()
        lay(home)
        return Page(home, cast)
    }

    @Suppress("UNCHECKED_CAST")
    private fun boxes(home: HomeView): Map<String, RectF> = (field(home, "now") as Map<Any, RectF>).entries.associate { it.key.toString() to RectF(it.value) }
    @Suppress("UNCHECKED_CAST")
    private fun views(home: HomeView): Map<String, View> = (field(home, "views") as Map<Any, View>).entries.associate { it.key.toString() to it.value }
    private fun strip(p: Page) = views(p.home).getValue("STRIP") as CastStripView

    private fun draw(p: Page, relay: Boolean = true): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        setField(p.home, "clockText", "9:51 AM"); setField(p.home, "batteryPct", 98)
        if (relay) lay(p.home)
        val c = Canvas(b); c.drawColor(Theme.ink); p.home.draw(c)
        return b
    }
    private fun shot(p: Page, name: String, relay: Boolean = true) {
        val d = out ?: return
        draw(p, relay)
        if (relay) { Thread.sleep(150); settle(10) }
        val b = draw(p, relay)
        File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun glideAt(home: HomeView, k: Float) {
        val a = field(home, "anim") as? android.animation.ValueAnimator ?: return
        a.setCurrentFraction(k)
    }
    private fun touch(p: Page, action: Int, x: Float, y: Float, down: Long) {
        val e = MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
        p.home.dispatchTouchEvent(e); e.recycle()
    }

    // ---------------------------------------------------------------- laid, and after a swap

    @Test fun aboveChatAndAboveMapFollowTheSwap() {
        for (share in listOf(HomeLayout.Share.HALF, HomeLayout.Share.THIRD)) for (dock in listOf(HomeLayout.CastDock.ABOVE_CHAT, HomeLayout.CastDock.ABOVE_MAP)) {
            var l = HomeLayout(chatShare = share, cast = dock)
            var p = page(l)
            for (pass in 0..1) {
                val b = boxes(p.home)
                val s = b.getValue("STRIP"); val chat = b.getValue("CHAT"); val map = b.getValue("MAP"); val bars = b.getValue("BARS")
                val col = if (dock == HomeLayout.CastDock.ABOVE_CHAT) chat else map
                val tag = "$share $dock ${if (pass == 0) "laid" else "swapped"}"
                assertEquals("$tag: in its column", col.left, s.left, 0.01f); assertEquals(col.right, s.right, 0.01f)
                assertEquals("$tag: first under the band", bars.bottom + 8f, s.top, 0.01f)
                assertEquals("$tag: its column under it", s.bottom + 8f, if (dock == HomeLayout.CastDock.ABOVE_MAP) map.top else b["QUESTS"]?.takeIf { l.card == HomeLayout.CardDock.ABOVE_CHAT }?.top ?: chat.top, 0.01f)
                assertEquals("$tag: the other column from the band", bars.bottom + 8f, if (dock == HomeLayout.CastDock.ABOVE_MAP) chat.top else map.top, 0.01f)
                assertEquals("$tag: the strip's screen side", l.castSide, strip(p).side)
                assertTrue("$tag: laid on top", field(p.home, "laidTop") as Boolean)
                assertTrue("$tag: never clipped at rest", strip(p).clipBounds == null)
                shot(p, "01_${dock.name.lowercase()}_${share.name.lowercase()}_${if (pass == 0) "laid" else "swapped"}")
                if (pass == 0) {
                    // Select (the swap): the bar keeps its module (and its edge) and glides with it
                    l = l.swapped()
                    assertEquals(dock, l.cast)
                    p = page(l)
                }
            }
        }
    }

    // ---------------------------------------------------------------- a fight's Hide: up behind the band

    @Test fun aFightSlidesTheTopBarBehindTheBand() {
        val l = HomeLayout(cast = HomeLayout.CastDock.ABOVE_MAP)
        val rest = boxes(page(l).home)
        val p = page(l, fight = true)
        val fight = boxes(p.home)
        val bars = fight.getValue("BARS"); val s = fight.getValue("STRIP")
        assertTrue("Hide in combat (the default): hidden", field(p.home, "laidHidden") as Boolean)
        assertEquals("its foot on the band's foot", bars.bottom, s.bottom, 0.01f)
        assertEquals("the map takes the room back up to the band", bars.bottom + 8f, fight.getValue("MAP").top, 0.01f)
        val v = strip(p)
        assertNotNull("clipped at the band's foot", v.clipBounds)
        assertEquals("all of it behind the band", v.height, v.clipBounds!!.top)
        // the band's pixels are the same with and without the bar (whatever the band's paint)
        val with = draw(p)
        v.visibility = View.INVISIBLE
        val without = draw(p)
        v.visibility = View.VISIBLE
        lay(p.home)
        val bandB = bars.bottom.toInt()
        var diff = 0
        for (y in 0 until bandB step 2) for (x in 0 until W step 2) if (with.getPixel(x, y) != without.getPixel(x, y)) diff++
        assertEquals("the band's pixels with the bar behind it", 0, diff)
        shot(p, "02_fight_hidden_100")
        // the slide's frames (the page's one motion, seeked): rest -> hidden, clipped at the band's foot all the way
        @Suppress("UNCHECKED_CAST")
        val now = field(p.home, "now") as HashMap<Any, RectF>
        val parts = (field(p.home, "views") as Map<Any, View>).keys
        v.sliding = true
        for (k in listOf(0f, 0.5f, 1f)) {
            for (part in parts) {
                val f = rest[part.toString()] ?: continue; val t = fight[part.toString()] ?: f
                now[part] = RectF(f.left + (t.left - f.left) * k, f.top + (t.top - f.top) * k, f.right + (t.right - f.right) * k, f.bottom + (t.bottom - f.bottom) * k)
            }
            HomeView::class.java.getDeclaredMethod("placeFrame").apply { isAccessible = true }.invoke(p.home)
            val band = now.entries.first { it.key.toString() == "BARS" }.value
            val clip = v.clipBounds
            assertNotNull("frame $k: clipped", clip)
            assertEquals("frame $k: the clip at the band's foot + the gap", (band.bottom + 8f - v.top).toInt().coerceIn(0, v.height), clip!!.top)
            shot(p, "02_fight_slide_${(k * 100).toInt()}", relay = false)
        }
        v.sliding = false
        // Show in combat (Stay) with Grow: the bar rides down with the band, tappable, not clipped
        HomeSettings.castBarCombat = CastStripModel.Combat.STAY
        val q = page(l, fight = true)
        HomeSettings.castBarCombat = CastStripModel.Combat.STAY
        HomeView::class.java.getDeclaredMethod("changed").apply { isAccessible = true }.invoke(q.home); settle(); lay(q.home)
        val st = boxes(q.home)
        if (!(field(q.home, "laidHidden") as Boolean)) {
            assertEquals(st.getValue("BARS").bottom + 8f, st.getValue("STRIP").top, 0.01f)
            assertEquals(st.getValue("STRIP").bottom + 8f, st.getValue("MAP").top, 0.01f)
            assertNull(strip(q).clipBounds)
            shot(q, "03_fight_stay_grow")
        }
        HomeSettings.castBarCombat = CastStripModel.Combat.HIDE
    }

    // ---------------------------------------------------------------- edit mode: the ladder, the drag, the menus

    @Test fun theControllerAndAFingerPutTheBarOnTop() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT))
        val home = p.home
        home.forceLive = true
        assertTrue(home.editOpen(EditStop.CAST, lift = true)); settle()
        val m = home.editModel()!!
        assertEquals(EditStop.CAST, m.lifted)
        // Up: above the chat (the gold arrows show only real moves)
        home.editKey("PADDUP")
        glideAt(home, 0.5f)
        shot(p, "04_up_glide_50", relay = false)
        glideAt(home, 1f); settle()
        assertEquals(HomeLayout.CastDock.ABOVE_CHAT, m.draft.cast)
        val b = boxes(home)
        assertEquals(b.getValue("BARS").bottom + 8f, b.getValue("STRIP").top, 0.01f)
        assertEquals(b.getValue("STRIP").bottom + 8f, b.getValue("CHAT").top, 0.01f)
        assertNull("lifted in edit mode: never clipped", strip(p).clipBounds)
        assertEquals(HomeEditView.Corner.FOOT_LEFT, home.editChrome.cornerOf(EditStop.CAST, b.getValue("STRIP")))
        shot(p, "05_lifted_above_chat")
        assertNull("the top of the ladder", m.nextDock(EditStop.CAST, m.draft, -1, horizontal = false))
        home.editKey("PADDRIGHT"); settle()
        assertEquals("Right: the map's column, the edge kept", HomeLayout.CastDock.ABOVE_MAP, m.draft.cast)
        shot(p, "06_lifted_above_map")
        home.editKey("PADDDOWN"); settle()
        assertEquals(HomeLayout.CastDock.UNDER_MAP, m.draft.cast)
        home.editKey("PAD1"); settle()
        assertNull(m.lifted)
        // a finger: the bar dragged up the map's column lands above it
        val s0 = boxes(home).getValue("STRIP")
        val x0 = s0.centerX()
        val t0 = android.os.SystemClock.uptimeMillis()
        touch(p, MotionEvent.ACTION_DOWN, x0, s0.centerY(), t0)
        for (y in listOf(s0.centerY() - 40f, s0.centerY() - 200f, 400f, 320f)) { touch(p, MotionEvent.ACTION_MOVE, x0, y, t0); settle(1) }
        ShadowSystemClock.advanceBy(Duration.ofMillis(90))
        home.editAct { it.dragTick(android.os.SystemClock.uptimeMillis()) }
        glideAt(home, 1f); settle(4)
        shot(p, "07_drag_to_top", relay = false)
        touch(p, MotionEvent.ACTION_UP, x0, 320f, t0); settle()
        assertEquals(HomeLayout.CastDock.ABOVE_MAP, m.draft.cast)
        // Save: the record (this character's) holds it; Classic never does
        home.editKey("PADFORWARD"); settle()
        assertEquals(HomeLayout.CastDock.ABOVE_MAP, HomeSettings.layout.cast)
        assertFalse("not Classic's places", HomeSettings.layout.classicPlace)
    }

    @Test fun theTopBarsMenuOpensBelowIt() {
        for (dock in listOf(HomeLayout.CastDock.ABOVE_CHAT, HomeLayout.CastDock.ABOVE_MAP)) {
            val p = page(HomeLayout(chatShare = HomeLayout.Share.THIRD, cast = dock))
            val home = p.home
            assertTrue(home.editOpen(EditStop.CAST)); settle()
            val s = boxes(home).getValue("STRIP")
            home.editAct { it.openMenu(EditStop.CAST); emptyList() }; settle()
            val box = home.editChrome.menuBox(EditStop.CAST)
            assertTrue("$dock: under the bar (${box.top} vs ${s.bottom})", box.top >= s.bottom + 7.5f)
            assertTrue("$dock: inside the frame", box.bottom <= H - 8f + 0.5f)
            val rows = HomeView::class.java.getDeclaredMethod("editMenuRows", EditStop::class.java).apply { isAccessible = true }
                .invoke(home, EditStop.CAST) as List<*>
            assertTrue("$dock: the Above / Below row", rows.any { (it as MenuRow).key == HomeEditModel.K_CAST_EDGE })
            shot(p, "08_menu_${dock.name.lowercase()}")
            home.editAct { it.closeMenu(); emptyList() }
            // the column the bar sits on top of: its host's menu (the other widget's) starts under the bar
            val over = if (dock == HomeLayout.CastDock.ABOVE_CHAT) EditStop.MAP else EditStop.CHAT
            home.editAct { it.openMenu(over); emptyList() }; settle()
            val mb = home.editChrome.menuBox(over)
            assertTrue("$dock: $over's menu under the bar (${mb.top} vs ${s.bottom})", mb.top >= s.bottom + 7.5f)
            shot(p, "09_menu_${over.name.lowercase()}_under_${dock.name.lowercase()}")
            home.editAct { it.closeMenu(); emptyList() }
            home.editCancel()
        }
    }

    // ---------------------------------------------------------------- hold to talk's pop-up over it

    @Test fun holdToTalkCoversTheTopBarAndSwallowsItsTaps() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.ABOVE_CHAT))
        val s = boxes(p.home).getValue("STRIP")
        val v = strip(p)
        val x = s.left + 70f; val y = s.centerY()
        // no pop-up: a finger on the slot is the bar's
        var t0 = android.os.SystemClock.uptimeMillis()
        touch(p, MotionEvent.ACTION_DOWN, x, y, t0)
        assertTrue("the bar takes its own slot's touch", (field(v, "downAt") as Int) >= 0)
        touch(p, MotionEvent.ACTION_CANCEL, x, y, t0); settle()
        // the pop-up up (under the D-pad, the page's width): drawn over the bar, its touches its own
        p.cast.talkPanel = CastView.TalkPanel(listOf(TalkTabs.Tab("say", "Say", android.graphics.Color.WHITE)), 0,
            CastView.TalkFace(CastView.TalkFace.Mode.READY, android.graphics.Color.WHITE), "", "")
        draw(p)
        val panel = field(p.cast, "talkPanelBox") as RectF
        assertTrue("the pop-up covers the top bar ($panel / $s)", RectF.intersects(panel, s))
        val px = maxOf(x, panel.left + 10f); val py = maxOf(y, panel.top + 10f).coerceAtMost(s.bottom - 4f)
        assertTrue(panel.contains(px, py) && s.contains(px, py))
        setField(v, "downAt", -1)
        t0 = android.os.SystemClock.uptimeMillis()
        touch(p, MotionEvent.ACTION_DOWN, px, py, t0)
        assertEquals("swallowed: the bar never sees it", -1, field(v, "downAt") as Int)
        touch(p, MotionEvent.ACTION_UP, px, py, t0); settle()
        shot(p, "10_hold_to_talk_over_the_top_bar")
        p.cast.talkPanel = null
    }
}
