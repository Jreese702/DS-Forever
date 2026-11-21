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
import org.junit.Assert.assertNotEquals
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
 * MOVABLE HOME, PHASE 2: EDIT MODE ON THE REAL [HomeView] (SPEC.txt section 15 HomeEditShotsTest): the 450 ms hold on the map's body (the
 * ring from 150 ms; the tap's map module never opens), the chrome (dim, dashed frames, tags, the golden box, the divider, the ribbon in the
 * room bar's XP track, the disc's gold tick), a finger's drag across the centre (the chat glides over, the drop well), the controller's
 * lift / steps / swap / Y, every widget's menu, the tap-to-cast bar's editor and Split's two halves, the Layouts sheet, remove / "+", the
 * Save / Discard prompt, a fight parking the draft and its end bringing it back, the map's picture scaled along a width glide, Save
 * writing the record. With HOME_EDIT_SHOTS (and HOME_LAYOUT_ART = an art root) every state is drawn, the room bar under it:
 *   HOME_EDIT_SHOTS=<dir> HOME_LAYOUT_ART=<root> gradlew :app:testModernDebugUnitTest --tests "*HomeEditShotsTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeEditShotsTest {
    private val out: File? = System.getenv("HOME_EDIT_SHOTS")?.let { File(it).apply { mkdirs() } }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    /** every Home made here: its edit mode closed after the test (the companion's live draft must not reach the next test's Home) */
    private val homes = ArrayList<HomeView>()
    @After fun down() {
        for (h in homes) h.editCancel()
        HomeView::class.java.getDeclaredField("liveDraft").apply { isAccessible = true }.set(null, null)
        setField(PartyModel.live, "party", null); HomeLayoutStore.character(null); HomeLayoutStore.set(HomeLayout())
        BottomTiles.editHome = null
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
        "whisper" to "Seraphine whispers: inv pls", "party" to "[Party] Iselle: pulling next pack", "guild" to "[Guild] Aldric: gz on 20!",
        "say" to "Korvash says: on my way", "system" to "Quest accepted: Kobold Candles", "general" to "[Trade] Odwin: WTS Copper Bars")

    private class Page(val home: HomeView, val cast: CastView, val strip: Strip, val rooms: ArrayList<Room>)

    private fun art() { System.getenv("HOME_LAYOUT_ART")?.let { root ->
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) } }

    private fun full(fight: Boolean) = JSONObject().put("type", "full").put("map", JSONObject().put("zone", "Elwynn Forest").put("pvp", "contested"))
        .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true).put("hudS", JSONObject().put("c", fight))))

    private val roster = listOf(
        """{"u":"player","i":0,"n":"Korvash","c":"SHAMAN","role":"HEALER","pt":"MANA","lv":20,"dead":false,"off":false,"col":"ff0070dd"}""",
        """{"u":"party1","i":1,"n":"Brannoc","c":"WARRIOR","role":"TANK","pt":"RAGE","lv":21,"dead":false,"off":false,"col":"ffc69b6d"}""",
        """{"u":"party2","i":2,"n":"Seraphine","c":"MAGE","role":"DAMAGER","pt":"MANA","lv":20,"dead":false,"off":false,"col":"ff3fc7eb"}""")
    private fun partyDoc(): JSONObject {
        val m = JSONArray(); for (r in roster) m.put(JSONObject(r))
        return JSONObject().put("type", "party").put("v", "m77").put("rs", 1)
            .put("p", JSONObject().put("on", true).put("box", 1).put("fight", false).put("grp", true).put("sel", "bound").put("m", m))
    }

    private fun page(layout: HomeLayout, fight: Boolean = false, party: Boolean = false, roomBar: Boolean = false): Page {
        art()
        app.getSharedPreferences("home", 0).edit().clear().commit()
        HomeSettings.init(app); Settings.init(app)
        HomeLayoutStore.character(null)
        HomeLayoutStore.set(layout)
        CastStripModel.Live.pos = 0f; CastStripModel.Live.side = null
        setField(PartyModel.live, "party", null)
        if (party) PartyModel.live.onParty(PartyModel.parseParty(partyDoc())!!, true, System.currentTimeMillis())
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val rooms = ArrayList<Room>()
        val home = HomeView(app, map, cast, chat) { r -> rooms += r }
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
        val strip = Strip(app) { }
        strip.active = Room.HOME
        // the host's wiring (CompanionHost): the ribbon in the XP track, the disc's tick
        home.onEditChrome = { v ->
            strip.editRibbon = v?.let { ev -> { c: Canvas, w: Float, top: Float, g0: Float, g1: Float -> ev.drawRibbon(c, w, top, g0, g1) } }
            strip.editTick = v != null
            strip.editTouch = v?.let { ev -> { x: Float, y: Float, up: Boolean -> ev.ribbonTouch(x, y, up) } }
            // P4 (the host's wiring): the room bar's chrome and its rooms' row's touches
            if (roomBar) {
                strip.editBar = v?.let { ev -> { c: Canvas, layer: Int -> ev.drawBar(c, layer) } }
                strip.editBarTouch = v?.let { ev -> { e: MotionEvent -> ev.barTouch(e) } }
            }
        }
        if (roomBar) {
            home.roomBar = strip
            strip.showOrder(RoomBar.rooms(HomeSettings.layout.rooms), 0L)
        }
        return Page(home, cast, strip, rooms)
    }

    @Suppress("UNCHECKED_CAST")
    private fun boxes(home: HomeView): Map<String, RectF> = (field(home, "now") as Map<Any, RectF>).entries.associate { it.key.toString() to RectF(it.value) }
    @Suppress("UNCHECKED_CAST")
    private fun views(home: HomeView): Map<String, View> = (field(home, "views") as Map<Any, View>).entries.associate { it.key.toString() to it.value }
    private fun model(p: Page): HomeEditModel = p.home.editModel()!!

    private val shots = ArrayList<Pair<String, Bitmap>>()
    private fun shot(p: Page, name: String, frames: Int = 0) {
        val d = out ?: return
        val b = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            setField(p.home, "clockText", "9:51 AM"); setField(p.home, "batteryPct", 98)
            if (frames == 0) lay(p.home)
            val c = Canvas(b); c.drawColor(Theme.ink); p.home.draw(c)
            p.strip.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Strip.HEIGHT, View.MeasureSpec.EXACTLY))
            p.strip.layout(0, 0, W, Strip.HEIGHT)
            c.translate(0f, H.toFloat()); p.strip.draw(c)
            if (pass == 0 && frames == 0) { Thread.sleep(150); settle(10) }
        }
        File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shots += name to Bitmap.createScaledBitmap(b, W / 4, 1080 / 4, true)
    }

    /** the page's one motion at [k] (HomeView.apply's animator, seeked: the off-device clock runs a glide to its end in one idle) */
    private fun glideAt(home: HomeView, k: Float) {
        val a = field(home, "anim") as? android.animation.ValueAnimator ?: return
        a.setCurrentFraction(k)
    }

    private var t0 = 1_000L
    private fun touch(p: Page, action: Int, x: Float, y: Float, down: Long = t0) {
        val e = MotionEvent.obtain(down, ShadowSystemClockNow(), action, x, y, 0)
        p.home.dispatchTouchEvent(e); e.recycle()
    }
    private fun ShadowSystemClockNow(): Long = android.os.SystemClock.uptimeMillis()

    // ---------------------------------------------------------------- the whole tour (every state drawn)

    @Test fun editModeTour() {
        val classic = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
        val p = page(classic)
        val home = p.home
        home.forceLive = true                                               // the glides run on the test clock (Home is not attached here)
        feedStage()
        val b0 = boxes(home)
        val map0 = b0.getValue("MAP")
        // WAY 1: the hold on the map's body: the ring from 150 ms, edit mode at 450 with the map lifted; the tap's module never opens
        val hx = map0.centerX(); val hy = map0.top + 420f
        touch(p, MotionEvent.ACTION_DOWN, hx, hy)
        settle(18)                                                          // ~290 ms: the ring part way
        assertFalse(home.editing)
        shot(p, "01_hold_ring", frames = 1)
        settle(12)                                                          // past 450 ms
        assertTrue("the hold opened edit mode", home.editing)
        assertEquals(EditStop.MAP, model(p).lifted)
        touch(p, MotionEvent.ACTION_UP, hx, hy)
        settle()
        assertNull("released without a move: selected, not lifted", model(p).lifted)
        assertEquals(EditStop.MAP, model(p).cursor)
        assertNull("the map module never opened", home.flow.full)
        assertTrue("no room opened", p.rooms.isEmpty())
        assertTrue(p.strip.editTick)
        shot(p, "02_edit_mode_map")
        // the controller: Left to the chat, Up to the band, Right to the clock
        home.editKey("PADDLEFT"); settle(); assertEquals(EditStop.CHAT, model(p).cursor)
        shot(p, "03_cursor_chat")
        home.editKey("PADDUP"); home.editKey("PADDRIGHT"); settle(); assertEquals(EditStop.CLOCK, model(p).cursor)
        shot(p, "04_cursor_clock")
        // a FINGER drags the map across the centre: past 48 px, after 80 ms the chat glides right; the drop well; the release settles
        val m1 = boxes(home).getValue("MAP")
        val dy = m1.top + 400f
        t0 = android.os.SystemClock.uptimeMillis()
        touch(p, MotionEvent.ACTION_DOWN, m1.centerX(), dy)
        for (x in listOf(m1.centerX() - 40f, m1.centerX() - 160f, m1.centerX() - 300f)) {
            touch(p, MotionEvent.ACTION_MOVE, x, dy); settle(1)
        }
        assertEquals(EditStop.MAP, model(p).lifted)
        // past the divider by more than 48 px: asked for (the drop well), laid once the finger has stayed 80 ms
        touch(p, MotionEvent.ACTION_MOVE, m1.centerX() - 420f, dy)
        assertNotNull(model(p).drag?.cand)
        assertFalse(model(p).draft.mirrored)
        shot(p, "05_drag_map_drop_well", frames = 1)
        ShadowSystemClock.advanceBy(Duration.ofMillis(90))
        home.editAct { it.dragTick(android.os.SystemClock.uptimeMillis()) }   // the hover ran out: the chat glides right (240 ms)
        assertTrue("the draft swapped while the finger is down", model(p).draft.mirrored)
        assertEquals("the band previews the draft's D-pad (P5's one exception): Left = the map", "map", p.cast.roomBar?.get(1))
        glideAt(home, 0.5f)
        shot(p, "06_drag_chat_gliding_right", frames = 1)
        glideAt(home, 1f)
        settle(4)
        shot(p, "06b_drag_map_over_left", frames = 1)
        touch(p, MotionEvent.ACTION_UP, m1.centerX() - 420f, dy)
        settle()
        val b1 = boxes(home)
        assertTrue("the map now left of the chat", b1.getValue("MAP").left < b1.getValue("CHAT").left)
        assertNull(model(p).lifted)
        assertEquals("the toolbar's mirror waits for Save", false, HomeSettings.layout.mirrored)
        shot(p, "07_swapped_draft")
        // the controller lifts the chat (A), the arrows toward its docks; B puts it back
        home.editKey("PADDRIGHT"); settle()
        assertEquals(EditStop.CHAT, model(p).cursor)
        home.editKey("PAD1"); settle()
        assertEquals(EditStop.CHAT, model(p).lifted)
        shot(p, "08_lifted_chat")
        home.editKey("PADDLEFT"); glideAt(home, 0.5f)
        shot(p, "09_lifted_chat_step_glide", frames = 1)
        glideAt(home, 1f); settle()
        assertFalse("the chat moved left: not mirrored any more", model(p).draft.mirrored)
        home.editKey("PAD2"); settle()
        assertTrue("B put it back", model(p).draft.mirrored)
        // every widget's menu (X), over the other column
        home.editKey("PAD3"); settle(); assertEquals(EditStop.CHAT, model(p).menu); shot(p, "10_menu_chat")
        home.editKey("PAD2")
        home.editKey("PADDLEFT"); home.editKey("PAD3"); settle(); assertEquals(EditStop.MAP, model(p).menu); shot(p, "11_menu_map")
        home.editKey("PAD2")
        home.editKey("PADDUP"); settle()
        assertEquals(EditStop.QUEST, model(p).cursor)
        home.editKey("PAD3"); settle(); shot(p, "12_menu_quest")
        home.editKey("PAD2")
        home.editKey("PADDUP"); settle()
        home.editKey("PAD1"); settle()
        val bandOrClock = model(p).menu
        assertNotNull("A on the band / the clock opens its menu", bandOrClock)
        shot(p, "13_menu_${bandOrClock!!.name.lowercase()}")
        home.editKey("PAD2")
        home.editKey(if (bandOrClock == EditStop.CLOCK) "PADDLEFT" else "PADDRIGHT"); home.editKey("PAD1"); settle()
        model(p).menu?.let { shot(p, "14_menu_${it.name.lowercase()}") }
        home.editKey("PAD2")
        // THE TAP-TO-CAST BAR'S EDITOR: Place = Split (two 4-slot halves, no scroll, the same cells), Size M, the remove chip
        val m = model(p)
        home.editAct { it.tap(EditStop.CAST) }; settle()
        assertEquals(EditStop.CAST, m.cursor)
        home.editKey("PAD3"); settle()
        assertEquals(EditStop.CAST, m.menu)
        repeat(3) { home.editKey("PADDRIGHT") }
        settle()
        assertEquals(HomeLayout.CastDock.SPLIT, m.draft.cast)
        home.editKey("PADDDOWN"); home.editKey("PADDLEFT"); settle()
        assertEquals(HomeLayout.SlotSize.M, m.draft.castSize)
        shot(p, "15_cast_bar_editor_split")
        home.editKey("PAD2"); settle()
        val b2 = boxes(home)
        val s1 = b2.getValue("STRIP"); val s2 = b2.getValue("CAST2")
        assertTrue("Split: one half under each column", s1.right < s2.left)
        assertEquals(s1.height(), s2.height(), 0.01f)
        assertEquals(1, (views(home).getValue("STRIP") as CastStripView).splitHalf)
        assertEquals(2, (views(home).getValue("CAST2") as CastStripView).splitHalf)
        shot(p, "16_split_bar_selected")
        // remove: the red X asks once ("Remove?"), then the bar goes behind the room bar; "+" shows; its tray brings it back
        home.editAct { it.removeChip(EditStop.CAST) }; settle()
        assertEquals(EditStop.CAST, m.removeAsk)
        shot(p, "17_remove_pill")
        home.editAct { it.removeChip(EditStop.CAST) }
        settle()
        assertEquals(HomeLayout.CastDock.OFF, m.draft.cast)
        assertTrue(m.plusShown)
        shot(p, "18_bar_removed_plus")
        home.editAct { it.tap(EditStop.PLUS) }; settle()
        assertEquals(HomeEditModel.Sheet.ADD, m.sheet)
        shot(p, "19_add_tray")
        home.editKey("PAD1"); settle()
        assertEquals(HomeLayout.CastDock.SPLIT, m.draft.cast)
        // the divider (reached from the clock above it) steps the width; the map's picture scales along the glide
        home.editAct { it.tap(EditStop.DIVIDER) }; settle()
        shot(p, "20_divider")
        home.editKey("PADDRIGHT")
        val mapSlot = views(home).getValue("MAP")
        glideAt(home, 0.25f)
        val nowMap = boxes(home).getValue("MAP")
        assertEquals("mid-glide the map is laid once at its larger size", 1216f * 2f / 3f, mapSlot.width.toFloat(), 1.5f)
        assertTrue("and cropped to the gliding box", nowMap.width() < 800f && mapSlot.clipBounds != null)
        shot(p, "21_width_glide_map_25", frames = 1)
        glideAt(home, 0.5f); assertEquals(1216f * 2f / 3f, mapSlot.width.toFloat(), 1.5f); shot(p, "22_width_glide_map_50", frames = 1)
        glideAt(home, 0.75f); shot(p, "23_width_glide_map_75", frames = 1)
        glideAt(home, 1f)
        settle()
        assertEquals("landed: no scale, the viewport re-laid", 1f, mapSlot.scaleX, 0.0001f)
        assertNull(mapSlot.clipBounds)
        assertEquals(boxes(home).getValue("MAP").width(), mapSlot.width.toFloat(), 1.5f)
        // THE LAYOUTS SHEET (A on the chip): six presets as live pictures, Mine's three slots, All characters
        home.editAct { it.tap(EditStop.CHIP) }; settle()
        assertEquals(HomeEditModel.Sheet.LAYOUTS, m.sheet)
        shot(p, "24_layouts_sheet")
        home.editKey("PAD2"); settle()
        // the chip's flip (live, the glide)
        home.editKey("PADDRIGHT"); settle()
        shot(p, "25_chip_flipped")
        // B with changes: Save / Discard / Keep editing, Save lit
        home.editKey("PAD2"); settle()
        assertTrue(m.prompt)
        shot(p, "26_prompt")
        home.editKey("PAD2"); settle()
        // A FIGHT mid-edit: the chrome goes, Home plays the SAVED layout's fight; its end brings the draft back
        val draft = m.draft
        home.onFull(full(true), true); home.onQuests(quests(true), true); home.tick(); settle()
        assertTrue(m.parked)
        assertFalse(home.homeOwnGuestWanted())
        assertFalse("parked: Home is Home (B, touch-cast, Party mode's D-pad)", home.editing)
        assertEquals("the saved layout's fight (Classic: the chat left)", true, boxes(home).getValue("CHAT").left < boxes(home).getValue("MAP").left)
        shot(p, "27_fight_parked")
        home.onFull(full(false), true); home.onQuests(quests(false), true)
        // the fight's word lapses (the grow's), then Home ticks
        ShadowSystemClock.advanceBy(Duration.ofSeconds(25)); home.tick(); settle()
        assertFalse(m.parked)
        assertEquals(draft, m.draft)
        shot(p, "28_fight_over_resumed")
        // Start: Save. The record is the draft, the chrome fades, the disc's tick goes
        home.editKey("PADFORWARD"); settle()
        assertFalse(home.editing)
        assertTrue(HomeSettings.layout.samePlace(draft))
        assertFalse(p.strip.editTick)
        assertEquals("after Save the pictures are the add-on's `d` again until it confirms the mirror", "chat", p.cast.roomBar?.get(1))
        shot(p, "29_saved")
        contact()
    }

    @Test fun neverInAFight() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT), fight = true)
        assertTrue(p.home.inFight())
        assertFalse(p.home.editOpen(EditStop.CHAT))
        assertFalse(p.home.editing)
        // a slow touch in a fight: the hold is never armed (no ring, no red edge) - it stays today's tap (combat-touch)
        val c = boxes(p.home).getValue("CHAT")
        setField(p.home, "editFlash", null)                                 // editOpen's own refusal above
        touch(p, MotionEvent.ACTION_DOWN, c.centerX(), c.bottom - 60f)
        settle(30)
        assertNull("no hold armed in a fight", field(p.home, "holdStop"))
        assertNull("no red refusal for a body hold in a fight", field(p.home, "editFlash"))
        shot(p, "31_fight_slow_touch_no_hold", frames = 1)
        touch(p, MotionEvent.ACTION_UP, c.centerX(), c.bottom - 60f)
        settle()
        assertFalse(p.home.editing)
    }

    /** the fix pass: Y on the band (and the band menu's Rest row) previews LIVE - the band's box changes before Save; Discard puts it back */
    @Test fun theBandsRestPreviewsLive() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT))
        assertEquals(HomeRest.DIAMONDS, HomeSettings.rest)
        val b0 = boxes(p.home).getValue("BARS")
        assertTrue(p.home.editOpen(EditStop.BAND)); settle()
        p.home.editKey("PAD4"); settle()
        assertEquals(HomeRest.ROW, model(p).draft.rest)
        val b1 = boxes(p.home).getValue("BARS")
        assertNotEquals("the band's box follows the draft's rest before Save (Diamonds -> Thin row)", b0.bottom, b1.bottom, 0.5f)
        assertEquals("nothing saved yet", HomeRest.DIAMONDS, HomeSettings.rest)
        shot(p, "32_band_thin_row_live")
        // the band menu's Rest row: back to Diamonds, live
        p.home.editKey("PAD3"); settle()
        assertEquals(EditStop.BAND, model(p).menu)
        p.home.editKey("PADDLEFT"); settle()
        assertEquals(HomeRest.DIAMONDS, model(p).draft.rest)
        assertEquals(b0.bottom, boxes(p.home).getValue("BARS").bottom, 0.5f)
        p.home.editKey("PAD2"); p.home.editKey("PAD4"); settle()
        // Discard: the saved rest again
        p.home.editCancel(); settle()
        assertEquals(b0.bottom, boxes(p.home).getValue("BARS").bottom, 0.5f)
        assertEquals(HomeRest.DIAMONDS, HomeSettings.rest)
    }

    private fun lang(l: String) {
        if (l != "en") listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
            .firstOrNull { it.canRead() }?.let { Tr.load(l, it.readText()) }
        Tr.setChoice(app, l)
    }

    /**
     * the fix pass: every widget menu FITS - at a third of the width (Big map's chat, Big chat's map) and in Spanish / French: no row's
     * buttons over its label (a long row puts them under it), every button inside its row, a column menu no lower than its column
     */
    @Suppress("UNCHECKED_CAST")
    @Test fun everyMenuFitsAtAThirdAndInSpanishAndFrench() {
        val layouts = listOf("bigmap" to HomePresets.byId(HomePresets.BIG_MAP)!!.apply(HomeLayout()),
            "bigchat" to HomePresets.byId(HomePresets.BIG_CHAT)!!.apply(HomeLayout()))
        val rowsOf = HomeView::class.java.getDeclaredMethod("editMenuRows", EditStop::class.java).apply { isAccessible = true }
        try {
            for (l in listOf("en", "es", "fr")) {
                lang(l)
                for ((ln, layout) in layouts) {
                    val p = page(layout)
                    assertTrue(p.home.editOpen(EditStop.CHAT)); settle()
                    for (s in listOf(EditStop.CHAT, EditStop.MAP, EditStop.QUEST, EditStop.CAST, EditStop.BAND)) {
                        p.home.editAct { it.openMenu(s); emptyList() }; settle()
                        val img = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
                        p.home.draw(Canvas(img))
                        val ev = p.home.editChrome
                        val btns = field(ev, "menuBoxes") as List<List<RectF>>
                        val rows = field(ev, "menuRowBoxes") as List<RectF>
                        val words = rowsOf.invoke(p.home, s) as List<MenuRow>
                        assertEquals("$l $ln $s: one row box per row", words.size, rows.size)
                        val lp = UiKit.bodyPaint(28f)
                        for ((i, r) in words.withIndex()) {
                            val rr = rows[i]
                            val labelRight = rr.left + 18f + lp.measureText(Tr.t(r.label))
                            for (b in btns[i]) {
                                assertTrue("$l $ln $s '${Tr.t(r.label)}': button inside its row", b.left >= rr.left - 1f && b.right <= rr.right + 1f)
                                if (r.kind == MenuRow.Kind.BUTTON && r.label == r.words.first()) continue
                                assertTrue("$l $ln $s '${Tr.t(r.label)}': buttons clear of the label ($b vs ${labelRight})",
                                    b.left >= labelRight - 1f || b.top >= rr.top + 0.3f * rr.height())
                            }
                        }
                        if (s == EditStop.CHAT || s == EditStop.MAP) {
                            val host = boxes(p.home).getValue(if (s == EditStop.CHAT) "MAP" else "CHAT")
                            val box = ev.menuBox(s)
                            // (a column too short even for the tightened rows lets the menu run on a little: MENU_K_MIN)
                            assertTrue("$l $ln $s: the menu ends at its column's bottom (${box.bottom} vs ${host.bottom})", box.bottom <= host.bottom + 60f)
                        }
                        if (l != "en" || ln == "bigmap") shot(p, "33_menu_${l}_${ln}_${s.name.lowercase()}")
                        p.home.editAct { it.closeMenu(); emptyList() }
                    }
                    p.home.editCancel()
                }
            }
        } finally { Tr.setChoice(app, "auto") }
    }

    @Test fun partyModeIsFramedNeverPicked() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT).swapped(), party = true)
        p.home.forceLive = true
        assertTrue(p.home.editOpen(EditStop.CHAT)); settle()
        assertFalse("Party mode's D-pad waits while editing", p.home.partyHoldsDpad())
        val party = boxes(p.home).getValue("PARTY")
        assertTrue("the party module follows the chat (right)", party.right >= 1239f)
        assertTrue("never a stop", EditStop.values().none { it.name == "PARTY" })
        shot(p, "30_party_mode_edit_mirrored")
        p.home.editKey("PAD3"); settle()
        shot(p, "30b_party_mode_chat_menu")
    }

    @Test fun aShortTapStillOpensTheChatRoom() {
        val p = page(HomeLayout())
        val c = boxes(p.home).getValue("CHAT")
        touch(p, MotionEvent.ACTION_DOWN, c.centerX(), c.bottom - 60f)
        settle(10)
        touch(p, MotionEvent.ACTION_UP, c.centerX(), c.bottom - 60f)
        settle()
        assertFalse(p.home.editing)
        assertEquals(listOf(Room.CHAT), p.rooms)
        // a finger that moves 30 px is no hold either
        touch(p, MotionEvent.ACTION_DOWN, c.centerX(), c.bottom - 60f)
        touch(p, MotionEvent.ACTION_MOVE, c.centerX() + 30f, c.bottom - 60f)
        settle(32)
        assertFalse(p.home.editing)
        touch(p, MotionEvent.ACTION_UP, c.centerX() + 30f, c.bottom - 60f)
    }

    @Test fun discardGlidesBackAndWritesNothing() {
        val classic = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
        val p = page(classic)
        assertTrue(p.home.editOpen(EditStop.MAP))
        p.home.editKey("PADBACK"); settle()
        assertTrue(boxes(p.home).getValue("MAP").left < boxes(p.home).getValue("CHAT").left)
        p.home.editKey("PAD2"); p.home.editKey("PADDRIGHT"); p.home.editKey("PAD1"); settle()
        assertFalse(p.home.editing)
        assertEquals(classic, HomeSettings.layout.copy(name = classic.name))
        assertTrue("back where it was saved", boxes(p.home).getValue("CHAT").left < boxes(p.home).getValue("MAP").left)
        // the hold's touch suspension: no band touch-cast while editing (the chrome takes every touch)
        p.home.editOpen(EditStop.CHAT); settle()
        assertTrue(p.home.homeOwnGuestWanted())
        assertFalse(p.home.partyHoldsDpad())
    }

    @Test fun theSettingsTileOpensEditMode() {
        page(HomeLayout())
        var asked = 0
        BottomTiles.editHome = { asked++ }
        Settings.init(app); SettingsTabs.init(app); GameUi.init(app)
        val v = SettingsView(app) { }
        val r = SettingsRoom(app, v) { null }
        r.show(SettingsTabs.Tab.BOTTOM, force = true)
        val img = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        for (k in 0..2) { ShadowLooper.idleMainLooper(); lay(r); r.draw(Canvas(img)) }
        val part = r.bottomTiles.first { BottomTiles.LAYOUT in it.tileIds() }
        assertEquals("the first tile", BottomTiles.LAYOUT, part.tileIds().first())
        val t = part.focusTargets().first()
        val d = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, t.centerX(), t.centerY(), 0); part.dispatchTouchEvent(d); d.recycle()
        val u = MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, t.centerX(), t.centerY(), 0); part.dispatchTouchEvent(u); u.recycle()
        assertEquals("A / a finger on Home layout = Edit Home", 1, asked)
        assertNull("no band opened", part.openTile)
        out?.let { d2 ->
            val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(Theme.ink); r.draw(c)
            File(d2, "00_settings_home_layout_tile.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    // ---------------------------------------------------------------- PHASE 4: the room bar (D3: reorder only; XP bar on / off)

    /** the host's frame: Home's view reaches under the room bar's XP track (HomeView.trackInset), the bar drawn at the frame's foot */
    private val HV = H + Strip.TRACKS
    private fun drawStrip(p: Page, c: Canvas? = null) {
        p.strip.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Strip.HEIGHT, View.MeasureSpec.EXACTLY))
        p.strip.layout(0, 0, W, Strip.HEIGHT)
        p.strip.draw(c ?: Canvas(Bitmap.createBitmap(W, Strip.HEIGHT, Bitmap.Config.ARGB_8888)))
    }
    private fun shot4(p: Page, name: String, settleFirst: Boolean = true) {
        if (settleFirst) settle()
        val d = out ?: run { drawStrip(p); return }
        val b = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            setField(p.home, "clockText", "9:51 AM"); setField(p.home, "batteryPct", 98)
            if (settleFirst) lay(p.home, W, HV)
            val c = Canvas(b); c.drawColor(Theme.ink); p.home.draw(c)
            c.translate(0f, H.toFloat()); drawStrip(p, c)
            if (pass == 0 && settleFirst) { Thread.sleep(150); settle(10) }
        }
        File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shots += name to Bitmap.createScaledBitmap(b, W / 4, 1080 / 4, true)
    }
    private fun stripTouch(p: Page, action: Int, x: Float, y: Float): Boolean {
        val e = MotionEvent.obtain(t0, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
        val r = p.strip.dispatchTouchEvent(e); e.recycle(); return r
    }
    private fun names(s: Strip) = s.order.map { it.name }

    @Test fun roomBarTour() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT), roomBar = true)
        val home = p.home
        home.forceLive = true
        home.trackInset(Strip.TRACKS, animate = false)                     // the host's: the frame ends at the track's top
        lay(home, W, HV)
        HomeView::class.java.getDeclaredMethod("changed").apply { isAccessible = true }.invoke(home)   // onSizeChanged's post (not attached here)
        settle(); lay(home, W, HV)
        drawStrip(p)
        assertEquals("the frame is still 935 over the track", H.toFloat(), boxes(home).getValue("MAP").bottom + UiKit.MODULE_GAP, 1f)
        assertEquals(RoomBar.DEFAULT, names(p.strip))
        shot4(p, "p4_00_home_classic")
        // the cursor: from the map Down to the room bar (its gold edge, the golden box on its first room)
        assertTrue(home.editOpen(EditStop.MAP)); settle()
        shot4(p, "p4_01a_edit_bar_dimmed_tagged")
        home.editKey("PADDDOWN"); settle()
        val m = model(p)
        assertEquals(EditStop.ROOMS, m.cursor)
        shot4(p, "p4_01_bar_selected")
        repeat(7) { home.editKey("PADDRIGHT") }
        assertEquals("BAGS", m.draft.rooms[m.barSel])
        shot4(p, "p4_02_cursor_on_bags")
        // A: Bags lifted (1.03, the shadow, the gold arrows)
        home.editKey("PAD1"); settle()
        assertEquals(Room.BAGS, p.strip.liftRoom)
        shot4(p, "p4_03_bags_lifted")
        // Left: one place, a 120 ms glide (EDIT_STEP); caught halfway, then landed
        home.editKey("PADDLEFT")
        drawStrip(p)
        ShadowSystemClock.advanceBy(Duration.ofMillis(60))
        shot4(p, "p4_04_bags_step_gliding", settleFirst = false)
        settle()
        assertEquals("the bar's order is the draft's at once (the touch boxes, the bar cursor)", "BAGS", names(p.strip)[6])
        // Left again: over the disc into the left half
        home.editKey("PADDLEFT"); settle()
        assertEquals("BAGS", names(p.strip)[5])
        shot4(p, "p4_05_bags_over_the_disc")
        assertEquals("nothing written before Save", RoomBar.DEFAULT, HomeSettings.layout.rooms)
        // A drops; X = the bar's menu: XP bar Show / Hide, Order Reset (final_08)
        home.editKey("PAD1"); home.editKey("PAD3"); settle()
        assertEquals(EditStop.ROOMS, m.menu)
        shot4(p, "p4_06_room_bar_menu")
        home.editKey("PADDRIGHT"); settle()                                // XP bar: Hide (the draft)
        assertFalse(m.draft.xpBar)
        assertTrue("edit mode keeps the track: its ribbon", HomeSettings.layout.xpBar)
        shot4(p, "p4_07_menu_xp_hide")
        home.editKey("PAD2"); settle()
        // a FINGER carries Settings to the far left: lifted at once, the others make way after the 80 ms hover, the release lays it
        val sx = p.strip.slotBox(11)!!.centerX(); val sy = p.strip.slotBox(11)!!.centerY()
        t0 = android.os.SystemClock.uptimeMillis()
        stripTouch(p, MotionEvent.ACTION_DOWN, sx, sy)
        for (x in listOf(sx - 40f, sx - 200f, sx - 420f)) { stripTouch(p, MotionEvent.ACTION_MOVE, x, sy); settle(1) }
        assertEquals("SETTINGS", m.barLifted)
        ShadowSystemClock.advanceBy(Duration.ofMillis(90))
        home.editAct { it.dragTick(android.os.SystemClock.uptimeMillis()) }
        drawStrip(p)
        ShadowSystemClock.advanceBy(Duration.ofMillis(110))
        shot4(p, "p4_08_finger_carries_settings", settleFirst = false)
        val x0 = p.strip.slotBox(0)!!.centerX()
        stripTouch(p, MotionEvent.ACTION_MOVE, x0 + 4f, sy); settle(1)
        stripTouch(p, MotionEvent.ACTION_UP, x0 + 4f, sy)
        settle()
        assertEquals("SETTINGS", names(p.strip)[0])
        assertEquals("every room still on the bar (D3)", RoomBar.DEFAULT.toSet(), names(p.strip).toSet())
        assertTrue("no room opened by the edit touches", p.rooms.isEmpty())
        shot4(p, "p4_09_settings_far_left")
        // a tap on the selected room: the bar's menu
        val s0 = p.strip.slotBox(0)!!
        stripTouch(p, MotionEvent.ACTION_DOWN, s0.centerX(), s0.centerY()); stripTouch(p, MotionEvent.ACTION_UP, s0.centerX(), s0.centerY())
        assertEquals(EditStop.ROOMS, m.menu)
        home.editKey("PAD2")
        // Start: Save. The record keeps the order and the hidden track (per character); the host lays Home into the track's place
        home.editKey("PADFORWARD"); settle()
        assertFalse(home.editing)
        assertEquals("SETTINGS", HomeSettings.layout.rooms[0])
        assertEquals("BAGS", HomeSettings.layout.rooms[6])
        assertFalse(HomeSettings.layout.xpBar)
        // the host's trackSync (Home on screen, the record's track hidden): the track slides away, every part glides into its 34 px
        p.strip.trackShown(false, Motion.MODULE_CLOSE)
        home.trackInset(0, animate = true, ms = Motion.MODULE_CLOSE)
        glideAt(home, 0.5f)
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        shot4(p, "p4_10_xp_track_going", settleFirst = false)
        glideAt(home, 1f); settle()
        assertEquals("Home's frame takes the track's 34 px", HV.toFloat(), boxes(home).getValue("MAP").bottom + UiKit.MODULE_GAP, 1f)
        assertEquals(Strip.TRACKS.toFloat(), p.strip.trackGone, 0.01f)
        shot4(p, "p4_11_saved_reordered_xp_hidden")
        // a touch in the track's place is Home's (the strip lets it through), the disc keeps its own
        assertFalse(stripTouch(p, MotionEvent.ACTION_DOWN, 200f, 10f))
        assertTrue(stripTouch(p, MotionEvent.ACTION_DOWN, W / 2f, 40f))
        // the bar cursor (HomeBarSelect) walks the bar as drawn: Settings first now
        val sel = HomeBarSelect(app, p.strip, { null }) { _, _ -> }
        @Suppress("UNCHECKED_CAST")
        val order = HomeBarSelect::class.java.getDeclaredMethod("order").apply { isAccessible = true }.invoke(sel) as List<Room>
        assertEquals(Room.SETTINGS, order.first())
        assertEquals(names(p.strip), order.map { it.name })
        contact4()
    }

    @Test fun roomBarDiscardPutsTheOrderBack() {
        val p = page(HomeLayout(), roomBar = true)
        p.home.forceLive = true
        drawStrip(p)
        assertTrue(p.home.editOpen(EditStop.MAP)); settle()
        p.home.editKey("PADDDOWN"); settle()
        assertEquals(EditStop.ROOMS, model(p).cursor)
        p.home.editKey("PAD1"); p.home.editKey("PADDRIGHT"); p.home.editKey("PADDRIGHT"); p.home.editKey("PAD1"); settle()
        assertEquals("MAP", names(p.strip)[2])
        // B: Save / Discard / Keep editing; Discard: the order glides back, nothing written
        p.home.editKey("PAD2"); p.home.editKey("PADDRIGHT"); p.home.editKey("PAD1"); settle()
        assertFalse(p.home.editing)
        assertEquals(RoomBar.DEFAULT, names(p.strip))
        assertEquals(RoomBar.DEFAULT, HomeSettings.layout.rooms)
        assertNull(p.strip.liftRoom)
    }

    /** the phase-4 states on one sheet (p4_overview.png) */
    private fun contact4() {
        val d = out ?: return
        if (shots.isEmpty()) return
        val cols = 4
        val rows = (shots.size + cols - 1) / cols
        val cw = W / 4; val ch = 1080 / 4 + 26
        val b = Bitmap.createBitmap(cols * cw, rows * ch, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(android.graphics.Color.BLACK)
        val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 16f }
        for ((i, s) in shots.withIndex()) {
            val x = (i % cols) * cw; val y = (i / cols) * ch
            c.drawBitmap(s.second, x.toFloat(), y.toFloat(), null)
            c.drawText(s.first, x + 4f, y + 1080 / 4 + 19f, tp)
        }
        File(d, "p4_overview.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** the Layouts sheet's live pictures: the settings stage fed a game (PreviewStage) */
    private fun feedStage() {
        val icons = listOf(136048, 136026, 135990, 136052, 135813, 136015, 135824, 136075)
        val nn = JSONArray()
        for (p in 1..8) nn.put(JSONObject().put("p", p).put("k", "spell").put("id", 400 + p).put("i", icons[p - 1]))
        PreviewStage.feedFull(JSONObject().put("type", "full")
            .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
                .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", nn)))
                .put("hudS", JSONObject().put("c", false).put("g", 1000.0)))
            .put("map", JSONObject().put("ok", true).put("zone", "Elwynn Forest").put("pvp", "contested")))
        PreviewStage.feedQuests(quests(false))
        val lines = JSONArray()
        for ((i, l) in chatLines.withIndex()) lines.put(JSONObject().put("i", i + 1).put("ch", l.first).put("text", l.second))
        PreviewStage.feedChat(JSONObject().put("lines", lines).put("s", 1))
    }

    /** every state on one sheet (00_overview.png) */
    private fun contact() {
        val d = out ?: return
        if (shots.isEmpty()) return
        val cols = 5
        val rows = (shots.size + cols - 1) / cols
        val cw = W / 4; val ch = 1080 / 4 + 26
        val b = Bitmap.createBitmap(cols * cw, rows * ch, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(android.graphics.Color.BLACK)
        val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 16f }
        for ((i, s) in shots.withIndex()) {
            val x = (i % cols) * cw; val y = (i / cols) * ch
            c.drawBitmap(s.second, x.toFloat(), y.toFloat(), null)
            c.drawText(s.first, x + 4f, y + 1080 / 4 + 19f, tp)
        }
        File(d, "00_overview.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
