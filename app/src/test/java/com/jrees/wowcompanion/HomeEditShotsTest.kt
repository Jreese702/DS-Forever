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
import org.junit.Assert.fail
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
 * lift / steps / swap / Y, every widget's menu, the tap-to-cast bar's editor and Split's two halves, remove / "+", the ribbon's Done
 * (held: its ring towards Discard), a fight parking edit mode and its end bringing it back, the map's picture scaled along a width
 * glide; test build 1518: every settled change written as it is made, Done / Discard (a long press, a held B). With HOME_EDIT_SHOTS (and HOME_LAYOUT_ART = an art root) every state is drawn, the room bar under it:
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
    private fun partyDoc(on: Boolean = true): JSONObject {
        val m = JSONArray(); for (r in roster) m.put(JSONObject(r))
        return JSONObject().put("type", "party").put("v", "m77").put("rs", 1)
            .put("p", JSONObject().put("on", on).put("box", 1).put("fight", false).put("grp", true).put("sel", "bound").put("m", m))
    }

    private fun page(layout: HomeLayout, fight: Boolean = false, party: Boolean = false, roomBar: Boolean = false, partyOn: Boolean = true): Page {
        art()
        app.getSharedPreferences("home", 0).edit().clear().commit()
        HomeSettings.init(app); Settings.init(app)
        HomeLayoutStore.character(null)
        HomeLayoutStore.set(layout)
        CastStripModel.Live.pos = 0f; CastStripModel.Live.side = null
        setField(PartyModel.live, "party", null)
        if (party) PartyModel.live.onParty(PartyModel.parseParty(partyDoc(partyOn))!!, true, System.currentTimeMillis())
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
            strip.editTouch = v?.let { ev -> { x: Float, y: Float, action: Int -> ev.ribbonTouch(x, y, action) } }
            // P4 (the host's wiring): the room bar's chrome and its rooms' row's touches
            if (roomBar) {
                strip.editBar = v?.let { ev -> { c: Canvas, layer: Int -> ev.drawBar(c, layer) } }
                strip.editBarTouch = v?.let { ev -> { e: MotionEvent -> ev.barTouch(e) } }
                strip.editPose = v?.let { ev -> { r: Room, o: FloatArray -> ev.roomPose(r, o) } }
                strip.editApps = v?.apps()                                   // 1518 build 2: the badges, the "+" spot
            }
        }
        if (roomBar) {
            home.roomBar = strip
            strip.showOrder(RoomBar.shown(HomeSettings.layout), 0L)
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
        // the controller: Left to the chat (the band has no stops: the top bar stays still - the user's ruling 2026-11-22)
        home.editKey("PADDLEFT"); settle(); assertEquals(EditStop.CHAT, model(p).cursor)
        shot(p, "03_cursor_chat")
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
        assertEquals("1518: the drop is saved as it is made (the add-on hears it at Done)", true, HomeSettings.layout.mirrored)
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
        // the divider (Up from a column's top reaches it) steps the width; the map's picture scales along the glide
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
        // landed: the glide's crop scale gone, the viewport re-laid; only edit mode's rest shrink is on it (the iPhone-style motion)
        assertEquals("landed: no glide scale, the viewport re-laid", Motion.REST_SCALE, mapSlot.scaleX, 0.0001f)
        assertNull(mapSlot.clipBounds)
        assertEquals(boxes(home).getValue("MAP").width(), mapSlot.width.toFloat(), 1.5f)
        // 1518: THE RIBBON'S ONE BUTTON, Done: held, its gold ring fills towards Discard; let go early, nothing happens
        val ev = home.editChrome
        val done = ev.ribbon(W.toFloat()).done
        assertTrue(ev.ribbonTouch(done.centerX(), done.centerY(), MotionEvent.ACTION_DOWN))
        settle(18)
        shot(p, "24_done_held_ring", frames = 1)
        ev.ribbonTouch(done.centerX(), done.centerY(), MotionEvent.ACTION_CANCEL); settle()
        assertTrue("let go early: still editing", home.editing)
        assertTrue("the draft kept", m.draft.mirrored)
        // A FIGHT mid-edit: the chrome goes, Home plays the record's fight (the draft, saved as it went); its end brings edit mode back
        val draft = m.draft
        home.onFull(full(true), true); home.onQuests(quests(true), true); home.tick(); settle()
        assertTrue(m.parked)
        assertFalse(home.homeOwnGuestWanted())
        assertFalse("parked: Home is Home (B, touch-cast, Party mode's D-pad)", home.editing)
        assertEquals("the record's fight (saved as it went: the chat right)", true, boxes(home).getValue("CHAT").left > boxes(home).getValue("MAP").left)
        shot(p, "27_fight_parked")
        home.onFull(full(false), true); home.onQuests(quests(false), true)
        // the fight's word lapses (the grow's), then Home ticks
        ShadowSystemClock.advanceBy(Duration.ofSeconds(25)); home.tick(); settle()
        assertFalse(m.parked)
        assertEquals(draft, m.draft)
        shot(p, "28_fight_over_resumed")
        // Start: Done. The record is the draft, the chrome fades, the disc's tick goes
        home.editKey("PADFORWARD"); settle()
        assertFalse(home.editing)
        assertTrue(HomeSettings.layout.samePlace(draft))
        assertFalse(p.strip.editTick)
        // fix round 3: after Done the pictures keep the record's sides until the add-on's `d` says them (no slide back and forth)
        assertEquals(if (HomeLayoutSolver.mirrorBit(HomeSettings.layout) == 1) "map" else "chat", p.cast.roomBar?.get(1))
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

    // ---------------------------------------------------------------- the top bar's pieces (the user's rulings 2026-11-22, ALL FOUR)

    /** a D-pad Left with an In-combat spell (PadMap m44: the bare set's COMBAT overlay slot): Lightning Bolt in fights, Healing Wave else */
    private val fightSets = """[{"set":1,"slots":[{"pos":1,"s":1,"kind":"combat","ov":true,"base":"spell","c":{"n":"Lightning Bolt","id":403,"i":136048},"o":{"n":"Healing Wave","id":331,"i":136052}}]}]"""
    private fun padMapDoc(ct: Int? = null, sets: String = "[]") = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m183","m":{"page":1,"sets":$sets,"dpad":{"on":true,"want":true,
        "cols":{"on":1,"off":2},"d":{"1":"chat","2":"radial","3":"map","4":"home"},"who":{"n":"Korvash","r":"Realm","rs":"guid"}${if (ct != null) ",\"ct\":$ct" else ""}}}}"""))!!
    /** a page whose band has the toolbar's word (a character's key, the DS WoW toolbar on), Diamonds at rest unless [l] says */
    private fun bandPage(l: HomeLayout = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT), ct: Int? = null, roomBar: Boolean = false,
                         sets: String = "[]", party: Boolean = false, partyOn: Boolean = true, fight: Boolean = false): Page {
        ItemCd.reset()                                                      // the extras' own data: none (another test's must not show)
        val p = page(l, fight = fight, roomBar = roomBar, party = party, partyOn = partyOn)
        HomeLayoutStore.character("Korvash-Realm")
        HomeLayoutStore.set(l)
        p.home.onPadMap(padMapDoc(ct, sets))
        setField(p.home, "clockText", "9:51 AM"); setField(p.home, "batteryPct", 98)
        settle()
        return p
    }
    /** the band's pixels as Home draws it now */
    private fun bandPx(p: Page, bars: RectF): IntArray {
        setField(p.home, "clockText", "9:51 AM"); setField(p.home, "batteryPct", 98)
        // the band's own clock: its hud as just received (a hud older than CastView's STALE_MS draws the bar greyed; a new hud document
        // would play its own focus glow)
        setField(p.cast, "lastRxMs", android.os.SystemClock.elapsedRealtime())
        lay(p.home)
        val img = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(img); c.drawColor(Theme.ink); p.home.draw(c)
        val w = bars.width().toInt(); val h = bars.height().toInt()
        return IntArray(w * h).also { img.getPixels(it, 0, w, bars.left.toInt(), bars.top.toInt(), w, h) }
    }
    /** a failed pixel check's two pictures and their difference (red) side by side, with HOME_EDIT_SHOTS */
    private fun dumpDiff(name: String, a: IntArray, b: IntArray, w: Int, h: Int) {
        val d = out ?: return
        val img = Bitmap.createBitmap(w, h * 3, Bitmap.Config.ARGB_8888)
        img.setPixels(a, 0, w, 0, 0, w, h); img.setPixels(b, 0, w, 0, h, w, h)
        val x = IntArray(a.size) { if (a[it] != b[it]) android.graphics.Color.RED else android.graphics.Color.BLACK }
        img.setPixels(x, 0, w, 0, 2 * h, w, h)
        File(d, "$name.png").outputStream().use { img.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun geo(p: Page): HomeEditView.BandGeo? = HomeView::class.java.getDeclaredMethod("editBand").apply { isAccessible = true }.invoke(p.home) as HomeEditView.BandGeo?

    /**
     * the user's ruling 2026-11-22 after 1518 ("What the hell is the dpad swapping buttons or clock move? ... remove that") kept with the
     * picker's ALL FOUR: in edit mode THE BAND ITSELF STAYS STILL - no transform on it, its buttons where they were, and its pixels at two
     * wiggle times differ only inside its pieces (the pencils, the Look chip, the extras with their badges and "+", the gold line): only
     * the extras wiggle; the pencils and the chip are still. Every stop on the band is one of its pieces, and each piece stands inside it
     */
    @Test fun theTopBarStaysStillOutsideItsPieces() {
        for (cast in listOf(HomeLayout.CastDock.UNDER_CHAT, HomeLayout.CastDock.ABOVE_MAP)) {
            val p = bandPage(HomeLayout(cast = cast))
            val bars = boxes(p.home).getValue("BARS")
            val w = bars.width().toInt(); val h = bars.height().toInt()
            val off = bandPx(p, bars)
            val slots0 = (0 until 8).map { RectF().also { r -> p.cast.slotRect(it, r) } }
            assertTrue(p.home.editOpen(EditStop.ROOMS)); settle(70)               // past the open: the wiggle runs
            shot(p, "40_band_pieces_${cast.name.lowercase()}", frames = 1)
            val g = geo(p) ?: error("$cast: no band pieces")
            assertTrue("$cast: Diamonds at rest: the extras shown", g.extras.isNotEmpty())
            // where the pieces may change the band: each piece, a badge and a wiggle round it (Home px -> band px)
            val allowed = ArrayList<RectF>()
            // the clock block: edit mode's row shows every extra turned on (and the gold line), so the block's grown size is laid for them;
            // the buttons' pictures are CastView's own (the toolbar's `d` pictures and their cross-fade run on their own clock): their
            // place is checked below (unmoved), not their pictures
            for (r in listOfNotNull(g.padPen, g.facePen, g.look, g.plus, g.chip) + g.extras.map { it.second }) allowed += RectF(r).apply { inset(-16f, -16f) }
            for (r in listOf(g.pad, g.face)) allowed += RectF(r).apply { inset(-32f, -32f) }          // a button's glow round it
            val block = RectF(g.chip!!); for ((_, r) in g.extras) block.union(r); g.plus?.let { block.union(it) }; allowed += block.apply { inset(-16f, -16f) }
            fun inside(k: Int): Boolean { val x = bars.left + k % w; val y = bars.top + k / w; return allowed.any { it.contains(x, y) } }
            val a = bandPx(p, bars)
            val outside = a.indices.filter { a[it] != off[it] && !inside(it) }
            if (outside.isNotEmpty()) dumpDiff("40_diff_${cast.name.lowercase()}", off, a, w, h)
            if (outside.isNotEmpty()) fail("$cast: ${outside.size} band px changed outside the pieces, x ${outside.minOf { it % w }}..${outside.maxOf { it % w }} " +
                "y ${outside.minOf { it / w }}..${outside.maxOf { it / w }}")
            settle(7)
            val b = bandPx(p, bars)
            var moved = 0
            for (k in a.indices) if (a[k] != b[k]) {
                moved++
                val x = bars.left + k % w; val y = bars.top + k / w
                // the extras wiggle; the buttons' pictures run on CastView's own clock (the toolbar's pictures' cross-fade, their glow)
                assertTrue("$cast: ($x, $y) moved between two wiggle times outside the extras",
                    g.extras.any { RectF(it.second).apply { inset(-16f, -16f) }.contains(x, y) } || g.plus?.let { RectF(it).apply { inset(-16f, -16f) }.contains(x, y) } == true ||
                        listOf(g.pad, g.face).any { RectF(it).apply { inset(-32f, -32f) }.contains(x, y) })
                fun core(r: RectF?) = r != null && RectF(r).apply { inset(6f, 6f) }.contains(x, y)
                assertFalse("$cast: a pencil or the Look chip moved", core(g.padPen) || core(g.facePen) || core(g.look))
            }
            // the extras wiggle (a small picture's turn is a pixel or less at its edge: its pose, not only its pixels)
            val poseAt = HomeView::class.java.getDeclaredMethod("extraPoseAt", String::class.java).apply { isAccessible = true }
            assertTrue("$cast: the extras wiggle", moved > 0 || g.extras.any { poseAt.invoke(p.home, it.first) as Boolean })
            assertTrue("$cast: the Hearthstone wiggles", poseAt.invoke(p.home, "hearth") as Boolean)
            val v = views(p.home).getValue("BARS")
            assertEquals(0f, v.rotation, 0f); assertEquals(1f, v.scaleX, 0f); assertEquals(0f, v.translationX, 0f)
            for (i in 0 until 8) { val r = RectF().also { p.cast.slotRect(i, it) }; assertEquals("$cast: button $i unmoved", slots0[i], r) }
            val ev = p.home.editChrome
            for ((s, bx) in ev.stops()) {
                if (s.band) assertTrue("$cast: $s inside the band", bx.t >= bars.top - 0.5f && bx.b <= bars.bottom + 0.5f)
                else assertTrue("$cast: $s at ${bx.t} below the band (${bars.bottom})", bx.t >= bars.bottom - 0.5f)
            }
            for (s in listOf(EditStop.PAD_PEN, EditStop.FACE_PEN, EditStop.LOOK, EditStop.EXTRAS)) assertTrue("$cast: $s a stop", s in ev.stops())
            p.home.editCancel()
        }
    }

    /** CLASSIC IS PIXEL-IDENTICAL OUTSIDE EDIT MODE: a session that used every piece (each bubble, a hide and its "+", the Look chip both
     *  ways, the fight preview and its bubble on and off) and set everything back, then Done: the band drawn exactly as after a session
     *  that touched nothing (an open and Done: the toolbar's pictures keep the record's sides until the add-on's `d` says them - 1518's
     *  own rule), each on its own Home at the same point of its clock */
    @Test fun classicIsPixelIdenticalOutsideEditMode() {
        for (l in listOf(HomeLayout(), HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT))) {
            var lastCast: CastView? = null
            fun session(full: Boolean): IntArray {
                val p = bandPage(l)
                lastCast = p.cast
                // the row's extras show by their own data (ItemCd's, app-wide): only the gold line is on here (no purse said: no line),
                // so the row is the same whatever another test left in that data
                HomeSettings.clockHearth = false; HomeSettings.clockTeleport = false; HomeSettings.clockBags = false; HomeSettings.clockAmmo = false
                assertTrue(p.home.editOpen(EditStop.CHAT)); settle(40)
                val m = model(p)
                var frames = 0
                fun st(n: Int) { settle(n); frames += n }
                if (full) {
                    for (s in listOf(EditStop.PAD_PEN, EditStop.FACE_PEN)) { p.home.editAct { it.pencilTap(s) }; st(4); p.home.editKey("PAD2"); st(4) }
                    p.home.editAct { it.hideExtra("gold") }; st(4)
                    p.home.editAct { it.openExtraTray() }; st(4)
                    assertEquals("the tray lists every hidden extra", HomeEditModel.EXTRA_KEYS, m.extrasOff)
                    p.home.editKey("PADDRIGHT"); p.home.editKey("PADDRIGHT"); p.home.editKey("PAD1"); st(4)
                    assertTrue("gold back", HomeSettings.clockGold)
                    assertFalse(HomeSettings.clockHearth)
                    p.home.editAct { it.lookFlip() }; st(20); p.home.editAct { it.lookFlip() }; st(20)
                    p.home.editAct { it.toggleFight() }; st(20)
                    assertTrue(p.cast.swingPreview)
                    p.home.editAct { it.pencilTap(EditStop.FIGHT_PEN) }; st(4); p.home.editKey("PAD2")
                    p.home.editAct { it.toggleFight() }; st(20)
                    assertFalse(m.fightPreview)
                    // fix round: the session ends (Done) with the preview SHOWING - the page after it is still Classic's
                    p.home.editAct { it.toggleFight() }; st(20)
                    assertTrue(m.fightPreview)
                }
                settle(200 - frames)
                p.home.editKey("PADFORWARD"); settle(40)
                assertFalse(p.home.editing)
                assertFalse("the stand-in swing is edit mode's only", p.cast.swingPreview)
                val items = HomeView::class.java.getDeclaredMethod("bandItems").apply { isAccessible = true }.invoke(p.home) as List<*>
                return bandPx(p, boxes(p.home).getValue("BARS"))
            }
            val before = session(false)
            val after = session(true)
            val home = homes.last()
            val bars = boxes(home).getValue("BARS")
            val bw = bars.width().toInt(); val bh = bars.height().toInt()
            // the buttons' own pictures run on CastView's own clock (the toolbar's `d` pictures and their hold, the hud's age): their
            // boxes are left out here (the session never draws on them: theTopBarStaysStillOutsideItsPieces); the band's look is checked
            val cv = lastCast!!
            assertEquals(CastView.Layout.DIAMONDS, cv.layout); assertFalse(cv.castTop); assertTrue(cv.castOnly); assertFalse(cv.swingPreview)
            val buttons = (0 until 8).map { i -> RectF().also { cv.slotRect(i, it); it.inset(-32f, -32f) } }
            val diff = before.indices.filter { k -> before[k] != after[k] && buttons.none { it.contains((k % bw).toFloat(), (k / bw).toFloat()) } }
            if (diff.isNotEmpty()) {
                dumpDiff("49_diff_${l.cast.name.lowercase()}", before, after, bw, bh)
                fail("${l.cast}: the band drawn as after a session that touched nothing: ${diff.size} px differ, x ${diff.minOf { it % bw }}..${diff.maxOf { it % bw }} y ${diff.minOf { it / bw }}..${diff.maxOf { it / bw }}")
            }
        }
    }

    /** THE BAND'S CHOICES AUTOSAVE AND SURVIVE DISCARD (1518's ruling for behaviour rows, kept for the top bar's: they are Settings'
     *  own setters, app-wide or the character's band look - not the layout): hide Gold and the Hearthstone, the Thin row, the rank badges,
     *  Same size, the swing off; a widget moved too; then Discard (Done's long press): the widget goes back, every band choice stays and
     *  Settings > Bottom screen's tiles show it */
    @Test fun bandChoicesAutosaveAndSurviveDiscard() {
        val p = bandPage()
        val env = BottomTiles.Env(null, null)
        assertTrue(p.home.editOpen(EditStop.EXTRAS)); settle(20)
        p.home.editAct { it.hideExtra("gold") }
        p.home.editAct { it.hideExtra("hearth") }
        p.home.editAct { it.pencilTap(EditStop.FACE_PEN) }; settle(4)
        p.home.editKey("PADDDOWN"); p.home.editKey("PADDLEFT")                   // rank badges On
        p.home.editKey("PAD2")
        p.home.editAct { it.toggleFight() }; settle(10)
        p.home.editAct { it.pencilTap(EditStop.FIGHT_PEN) }
        p.home.editKey("PADDRIGHT")                                              // Same size
        p.home.editKey("PADDDOWN"); p.home.editKey("PADDRIGHT")                  // the swing timer Off
        p.home.editKey("PAD2"); p.home.editAct { it.toggleFight() }; settle(10)
        p.home.editAct { it.lookFlip() }; settle(20)                             // the Thin row, live
        assertEquals(HomeRest.ROW, HomeSettings.rest)
        assertEquals(CastView.Layout.ROW, p.cast.layout)
        assertFalse("the band's choices are no change Discard undoes", model(p).changed)
        p.home.editAct { it.swap() }; settle(20)                                 // a layout change: Discard's
        assertTrue(HomeSettings.layout.mirrored)
        p.home.editAct { it.discard() }; settle(40)
        assertFalse(p.home.editing)
        assertFalse("Discard put the swap back", HomeSettings.layout.mirrored)
        assertFalse(HomeSettings.clockGold); assertFalse(HomeSettings.clockHearth)
        assertTrue(HomeSettings.rankBadges); assertFalse(HomeSettings.swingTimer)
        assertEquals(HomeLayout.FightBand.SAME, HomeSettings.fightBand)
        assertEquals("the resting look stays the Thin row (a behaviour row: 1518's ruling)", HomeRest.ROW, HomeSettings.rest)
        assertEquals(CastView.Layout.ROW, p.cast.layout)
        // Settings > Bottom screen says the same (one setter: the two never disagree)
        val clock = BottomTiles.lines(BottomTiles.CLOCK, env).associate { it.key to it.picked }
        assertEquals(1, clock["gold"]); assertEquals(1, clock["hearth"]); assertEquals(0, clock["bags"])
        assertEquals(0, BottomTiles.lines(BottomTiles.REST, env).single().picked)
        assertEquals(0, BottomTiles.lines(BottomTiles.RANK, env).single().picked)
        assertEquals(1, BottomTiles.lines(BottomTiles.COMBAT, env).single().picked)
        assertEquals(1, BottomTiles.lines(BottomTiles.SWING, env).single().picked)
    }

    /** P3: the Look chip flips the resting look and the band redraws live (no Done): Diamonds -> the Thin row -> Diamonds; the cursor
     *  stays on the chip, the extras' stop goes with the Diamonds */
    @Test fun theLookChipRedrawsLive() {
        val p = bandPage()
        assertTrue(p.home.editOpen(EditStop.LOOK)); settle(30)
        val tall = boxes(p.home).getValue("BARS").height()
        p.home.editKey("PAD1"); settle(30)
        assertEquals(CastView.Layout.ROW, p.cast.layout)
        assertTrue("the band is the thin row now", boxes(p.home).getValue("BARS").height() < tall - 40f)
        assertEquals(EditStop.LOOK, model(p).cursor)
        assertFalse("no extras in the thin row", EditStop.EXTRAS in p.home.editChrome.stops())
        shot(p, "41_look_thin_row")
        p.home.editKey("PAD1"); settle(30)
        assertEquals(CastView.Layout.DIAMONDS, p.cast.layout)
        assertEquals(tall, boxes(p.home).getValue("BARS").height(), 0.5f)
        assertTrue(p.home.editing)
        // a finger on the chip while the chat is lifted and carried over: the chat drops where it is, the move is kept (and saved), the
        // look flips
        p.home.editAct { it.lift(EditStop.CHAT) }
        p.home.editKey("PADDRIGHT"); settle(10)
        assertTrue(model(p).draft.mirrored)
        p.home.editAct { it.lookFlip() }; settle(30)
        assertNull(model(p).lifted)
        assertTrue("the carried move kept", model(p).draft.mirrored)
        assertTrue("and saved", HomeSettings.layout.mirrored)
        assertEquals(HomeRest.ROW, HomeSettings.rest)
        assertEquals(HomeRest.ROW, model(p).draft.rest)
    }

    /** P4: THE FIGHT PREVIEW SENDS NOTHING - no behaviour press, no fight state (Home shows no fight, edit mode never parks, the guest is
     *  kept), and each In fights look renders: Grow grows the band, Same size keeps its box, Top screen (the Wings word on) slides the band away */
    @Test fun theFightPreviewSendsNothing() {
        val sizes = HashMap<HomeLayout.FightBand, Float>()
        for (band in HomeLayout.FightBand.values()) {
            val p = bandPage(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = band), ct = 1)
            var presses = 0; var lives = 0
            p.home.onEditPress = { _, _ -> presses++ }
            assertTrue(p.home.editOpen(EditStop.FIGHT)); settle(20)
            p.home.onEditLive = { lives++ }
            val rest = boxes(p.home).getValue("BARS").height()
            p.home.editKey("PAD1"); settle(40)
            assertTrue(model(p).fightPreview)
            assertFalse("$band: Home shows no fight", p.home.fightShown())
            assertFalse("$band: never parked", model(p).parked)
            assertTrue(p.home.editing)
            assertEquals("$band: no press reached the host", 0, presses)
            assertEquals("$band: edit mode stayed live", 0, lives)
            assertTrue("$band: the stand-in swing", p.cast.swingPreview)
            assertFalse("$band: the swing line shows", p.cast.castOnly)
            sizes[band] = boxes(p.home).getValue("BARS").height()
            when (band) {
                HomeLayout.FightBand.GROW -> { assertTrue("Grow: the band grows", sizes[band]!! > rest + 100f); assertTrue(p.cast.castTop) }
                HomeLayout.FightBand.SAME -> assertEquals("Same size: its box", rest, sizes[band]!!, 0.5f)
                HomeLayout.FightBand.TOP -> {
                    assertEquals("Top screen: its box", rest, sizes[band]!!, 0.5f)
                    // (the merge with feat/topbar-fight: no "up" cue any more - the band slides up off the page, TopSlide)
                    assertTrue("Top screen: the band laid above the page", boxes(p.home).getValue("BARS").bottom <= 0f)
                }
            }
            assertTrue("$band: the fight pencil", EditStop.FIGHT_PEN in p.home.editChrome.stops())
            assertFalse("$band: no column in the preview (D4)", EditStop.CHAT in p.home.editChrome.stops())
            p.home.editAct { it.pencilTap(EditStop.FIGHT_PEN) }; settle(10)
            shot(p, "42_fight_preview_${band.name.lowercase()}")
            p.home.editKey("PAD2")
            p.home.editAct { it.toggleFight() }; settle(40)
            assertEquals("$band: back to the rest look", rest, boxes(p.home).getValue("BARS").height(), 0.5f)
            assertFalse(p.cast.swingPreview)
            p.home.editCancel()
        }
    }

    /**
     * THE 1519 INTEGRATION (feat/home-apps2's Fight preview x feat/topbar-fight's slide): with the add-on's Wings word on, Top screen's
     * preview shows what the Top fight shows - the band slid up off the page (no "up" cue any more: the slide is the cue), the chat and
     * the map grown up into the freed strip, the tap-to-cast bar staying in its dock - and with Party mode on the Party-mode Top fight's
     * page: no takeover, the band away all the same, the party list filling the strip (PartyLayout.Mode.FIGHT_TOP). Edit mode keeps a
     * way to its In fights choice: the band's pieces left with the band (no stop above the page), the fight pencil stands at the page's
     * top centre where the band went, its bubble hangs under it on the screen. The preview off: the band back at its rest box
     */
    @Test fun theTopScreenPreviewSlidesTheBandAway() {
        for (party in listOf(false, true)) {
            val l = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.TOP)
            val p = bandPage(l, ct = 1, party = party)
            val n = if (party) "Party mode" else "solo"
            assertTrue(p.home.editOpen(EditStop.FIGHT)); settle(20)
            val rest = boxes(p.home)
            assertTrue("$n: the band on the page at rest", rest.getValue("BARS").bottom > 100f)
            p.home.editKey("PAD1"); settle(40)
            assertTrue(model(p).fightPreview)
            assertFalse("$n: Home shows no fight", p.home.fightShown())
            val b = boxes(p.home)
            assertEquals("$n: the band laid above the page", -HomeLayoutSolver.AWAY_PAST, b.getValue("BARS").bottom, 0.01f)
            assertTrue("$n: the band draws nothing on the page", p.cast.away)
            assertEquals("$n: the map grown up", 8f, b.getValue("MAP").top, 0.01f)
            val strip = b.getValue("STRIP")
            assertTrue("$n: the tap-to-cast bar stays in its dock ($strip)", strip.bottom <= H + 0.5f && strip.top > b.getValue("CHAT").top)
            if (party) {
                val pv = views(p.home).getValue("PARTY") as PartyModuleView
                assertEquals("Party mode: the Party-mode Top fight's rows", PartyLayout.Mode.FIGHT_TOP, pv.mode)
                assertEquals("Party mode: the party list from the top gap", 8f, b.getValue("PARTY").top, 0.01f)
                assertTrue("Party mode: the party list fills the freed strip", b.getValue("PARTY").height() > rest.getValue("PARTY").height() + 150f)
                assertEquals("Party mode: the chat where the Party fight has it", b.getValue("PARTY").bottom + PartyLayout.CHAT_GAP, b.getValue("CHAT").top, 0.01f)
            } else assertEquals("solo: the chat grown up", 8f, b.getValue("CHAT").top, 0.01f)
            val stops = p.home.editChrome.stops()
            for ((st, x) in stops) assertTrue("$n: $st on the page ($x)", x.t >= -0.01f && x.b <= H + Strip.HEIGHT + 0.5f)
            assertFalse("$n: the D-pad's pencil left with the band", EditStop.PAD_PEN in stops)
            assertFalse("$n: the buttons' pencil left with the band", EditStop.FACE_PEN in stops)
            val pen = stops[EditStop.FIGHT_PEN]
            assertNotNull("$n: the fight pencil stays reachable", pen)
            assertEquals("$n: at the page's top centre", W / 2f, pen!!.cx, 1f)
            assertTrue("$n: on the page's top ($pen)", pen.t >= 0f && pen.b <= 60f)
            p.home.editAct { it.pencilTap(EditStop.FIGHT_PEN) }; settle(10)
            assertEquals(EditStop.FIGHT_PEN, model(p).menu)
            val bubble = p.home.editChrome.menuBox(EditStop.FIGHT_PEN)
            assertTrue("$n: the bubble under the pencil, on the screen ($bubble / $pen)", bubble.top >= pen.b && bubble.bottom <= H + 0.5f && bubble.height() > 150f)
            val tail = p.home.editChrome.bubbleTailBox(EditStop.FIGHT_PEN)
            assertNotNull("$n: its tail up to the pencil", tail)
            assertTrue("$n: the tail's tip at the pencil ($tail / $pen)", tail!!.top >= pen.b - 0.5f && tail.top <= pen.b + 8f)
            shot(p, "43_fight_preview_top_${if (party) "party" else "solo"}")
            p.home.editKey("PAD2")
            p.home.editAct { it.toggleFight() }; settle(40)
            assertFalse(model(p).fightPreview)
            assertEquals("$n: the band back at its rest box", rest.getValue("BARS"), boxes(p.home).getValue("BARS"))
            assertFalse("$n: the band draws again", p.cast.away)
            if (party) assertEquals(PartyLayout.Mode.REST, (views(p.home).getValue("PARTY") as PartyModuleView).mode)
            p.home.editCancel()
        }
    }

    /**
     * the 1519 audit (finding 1): THE FIRST PICK OF TOP SCREEN PREVIEWS THE TOP LOOK. A character that never used Top screen: the add-on
     * has the flag (`ct` 0 - the menu offers Top as a live choice, only `ct` null greys it), and edit mode holds the flag's send back
     * (CompanionHost.topCheck waits while edit mode is live: `ct` turns 1 only after Done). The Fight preview shows what the first fight
     * after Done shows - the band slid up off the page, the columns grown, with Party mode on the party list filling the strip - never
     * Grow's grown band. No flag at all (`ct` null): Top screen greyed and nothing to preview but Grow, as ever
     */
    /** the fight pencil's In fights row as its bubble lists it */
    private fun fightRow(home: HomeView): MenuRow = (HomeView::class.java.getDeclaredMethod("editMenuRows", EditStop::class.java)
        .apply { isAccessible = true }.invoke(home, EditStop.FIGHT_PEN) as List<*>).filterIsInstance<MenuRow>().first { it.key == BottomTiles.COMBAT }
    @Test fun aFirstTopScreenPickPreviewsTheTopLook() {
        for (party in listOf(false, true)) {
            val n = if (party) "Party mode" else "solo"
            val p = bandPage(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.GROW), ct = 0, party = party)
            assertTrue(p.home.editOpen(EditStop.FIGHT)); settle(20)
            val rest = boxes(p.home)
            val row = fightRow(p.home)
            assertFalse("$n: ct 0 offers Top screen", HomeLayout.FightBand.TOP.ordinal in row.off)
            p.home.editAct { listOf(HomeEditModel.Fx.Press(BottomTiles.COMBAT, HomeLayout.FightBand.TOP.ordinal)) }; settle(20)
            assertEquals(HomeLayout.FightBand.TOP, HomeSettings.fightBand)
            p.home.editKey("PAD1"); settle(40)
            assertTrue(model(p).fightPreview)
            val b = boxes(p.home)
            assertEquals("$n: the band laid above the page (not Grow's grown band)", -HomeLayoutSolver.AWAY_PAST, b.getValue("BARS").bottom, 0.01f)
            assertTrue("$n: the band draws nothing on the page", p.cast.away)
            assertEquals("$n: the map grown up", 8f, b.getValue("MAP").top, 0.01f)
            if (party) {
                assertEquals("Party mode: the Party-mode Top fight's rows", PartyLayout.Mode.FIGHT_TOP, (views(p.home).getValue("PARTY") as PartyModuleView).mode)
                assertEquals("Party mode: the party list from the top gap", 8f, b.getValue("PARTY").top, 0.01f)
            } else assertEquals("solo: the chat grown up", 8f, b.getValue("CHAT").top, 0.01f)
            p.home.editAct { it.toggleFight() }; settle(40)
            assertEquals("$n: the band back at its rest box", rest.getValue("BARS"), boxes(p.home).getValue("BARS"))
            p.home.editCancel()
        }
        // no flag (an add-on without it / no key): Top screen greyed; a record that says Top previews Grow (the Wings cannot show)
        val g = bandPage(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.GROW), ct = null)
        assertTrue(g.home.editOpen(EditStop.FIGHT)); settle(20)
        assertTrue(HomeLayout.FightBand.TOP.ordinal in fightRow(g.home).off)
        g.home.editCancel()
        val t = bandPage(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.TOP), ct = null)
        assertTrue(t.home.editOpen(EditStop.FIGHT)); settle(20)
        val restT = boxes(t.home).getValue("BARS").height()
        t.home.editKey("PAD1"); settle(40)
        assertTrue(model(t).fightPreview)
        assertTrue("no flag: Grow's band", boxes(t.home).getValue("BARS").height() > restT + 100f)
        assertFalse(t.cast.away)
        t.home.editCancel()
    }

    /**
     * the 1519 audit (finding 2): the solo Fight preview lays the party list as the TAKEOVER does. Grouped with Party mode off, the party
     * module shows (QOL > Party mode on, a party document); a real fight is the takeover and lays it FIGHT_OFF (56 px rows). The preview
     * gives the solver the takeover, so the party list takes the takeover's mode and box too - not the rest's 64 px rows - for every
     * In fights look (Top screen with the Wings word, Grow, Same size). (Show in combat On here: the preview never slides the bar - P4,
     * display only - so the pages are compared with a bar both keep)
     */
    @Test fun theSoloPreviewLaysThePartyListAsTheTakeoverDoes() {
        // HomeSettings keeps its prefs across tests: the default (Hide) put back whatever happens
        try { soloPreviewParty() } finally { HomeSettings.castBarCombat = CastStripModel.Combat.HIDE }
    }
    private fun soloPreviewParty() {
        for (band in HomeLayout.FightBand.values()) for (dock in listOf(HomeLayout.PartyDock.ABOVE_CHAT, HomeLayout.PartyDock.UNDER_MAP)) {
            val l = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = band, party = dock)
            val n = "$band $dock"
            val real = bandPage(l, ct = 1, party = true, partyOn = false, fight = true)
            HomeSettings.castBarCombat = CastStripModel.Combat.STAY; real.home.tick(); settle()
            assertTrue("$n: the real fight is the takeover", real.home.flow.takeover)
            val want = boxes(real.home)
            assertEquals("$n: the takeover's rows", PartyLayout.Mode.FIGHT_OFF, (views(real.home).getValue("PARTY") as PartyModuleView).mode)
            val p = bandPage(l, ct = 1, party = true, partyOn = false)
            HomeSettings.castBarCombat = CastStripModel.Combat.STAY
            assertTrue(p.home.editOpen(EditStop.FIGHT)); settle(20)
            p.home.editKey("PAD1"); settle(40)
            assertTrue(model(p).fightPreview)
            assertFalse("$n: Home shows no fight", p.home.fightShown())
            assertEquals("$n: the preview's rows are the takeover's", PartyLayout.Mode.FIGHT_OFF, (views(p.home).getValue("PARTY") as PartyModuleView).mode)
            val b = boxes(p.home)
            for (part in listOf("PARTY", "CHAT", "MAP")) assertEquals("$n: $part as the takeover lays it", want.getValue(part), b.getValue(part))
            p.home.editCancel()
        }
    }

    /**
     * fix round (the review, two findings): THE PREVIEW ENDS CLEAN BY EVERY WAY, not only by its toggle - Done (Start), B at the top level,
     * a Discard that changed nothing, a cancel, another room over Home (the session parked; the room takes the band next) and a real fight:
     * the D-pad's own pictures again (no In-combat spell drawn - nor read by the bar's prediction - out of a fight), no stand-in swing,
     * the cast bar alone at rest, the band at its rest size and look
     */
    @Test fun thePreviewEndsCleanByEveryWay() {
        val ways = listOf<Pair<String, (Page) -> Unit>>(
            "Done (Start)" to { p -> p.home.editKey("PADFORWARD") },
            "B at the top level" to { p -> p.home.editKey("PAD2") },
            "Discard with no change" to { p -> p.home.editAct { it.discard() } },
            "a cancel" to { p -> p.home.editCancel() },
            "another room" to { p -> p.home.giveBack() },
            "a real fight" to { p -> p.home.onFull(full(true), true); p.home.onQuests(quests(true), true); p.home.tick() })
        for ((n, end) in ways) {
            val p = bandPage(sets = fightSets)
            assertTrue("$n: out of a fight, no In-combat picture", p.cast.fightDpad.isNullOrEmpty())
            val rest = boxes(p.home).getValue("BARS").height()
            assertTrue(p.home.editOpen(EditStop.FIGHT)); settle(20)
            p.home.editKey("PAD1"); settle(40)
            assertTrue(model(p).fightPreview)
            assertEquals("$n: the preview draws the In-combat picture", 136048, p.cast.fightDpad?.get(1))
            assertTrue(p.cast.swingPreview); assertTrue(p.cast.castTop); assertFalse(p.cast.castOnly)
            end(p); settle(40)
            when (n) {
                "another room", "a real fight" -> assertTrue("$n: parked", model(p).parked)
                else -> assertFalse("$n: ended", p.home.editing)
            }
            assertFalse("$n: no stand-in swing", p.cast.swingPreview)
            if (n == "a real fight") {
                // the fight's own look (the takeover), then its end: the D-pad's own pictures again
                p.home.onFull(full(false), true); p.home.onQuests(quests(false), true)
                ShadowSystemClock.advanceBy(Duration.ofSeconds(25)); p.home.tick(); settle(40)
                assertFalse(model(p).fightPreview)
            }
            assertTrue("$n: the D-pad's own pictures (${p.cast.fightDpad})", p.cast.fightDpad.isNullOrEmpty())
            assertTrue("$n: no In-combat spell id", p.cast.fightIds.isNullOrEmpty())
            assertTrue("$n: the cast bar alone", p.cast.castOnly)
            assertFalse("$n: the strip, not the top edge", p.cast.castTop)
            // (another room: the band is that room's now - its own layout; Home's look comes back with Home)
            if (n != "another room") {
                assertEquals("$n: the rest look", CastView.Layout.DIAMONDS, p.cast.layout)
                assertEquals("$n: the rest size", rest, boxes(p.home).getValue("BARS").height(), 0.5f)
            }
            if (p.home.editing) { if (n == "another room") p.home.borrow(); settle(); p.home.editCancel(); settle() }
        }
    }

    /**
     * fix round (the review: the room bar's 52 px badge reach on a 28 px picture covered most of it, so a tap meant to select the
     * Hearthstone hid it): on an extra's picture only its drawn "-" disc hides it - the picture's centre and most of it select the extra;
     * off the picture the badge keeps its full reach
     */
    @Test fun aTapOnAnExtrasPictureSelectsIt() {
        val p = bandPage()
        HomeSettings.clockHearth = true; HomeSettings.clockTeleport = true
        assertTrue(p.home.editOpen(EditStop.CHAT)); settle(30)
        val m = model(p)
        val ev = p.home.editChrome
        val g = geo(p)!!
        for (k in listOf("hearth", "teleport")) {
            val r = g.extras.first { it.first == k }.second
            val pic = ev.extraPicture(k, r)
            assertEquals("$k: the 28 px picture at the cell's scale", ClockBand.ICON * r.height() / ClockBand.CELL_H, pic.width(), 0.5f)
            var hides = 0; var all = 0
            var y = pic.top + 0.5f
            while (y < pic.bottom) { var x = pic.left + 0.5f; while (x < pic.right) { all++; if (ev.extraBadgeHit(k, r, x, y)) hides++; x += 1f }; y += 1f }
            assertTrue("$k: at most a quarter of the picture hides it ($hides of $all)", hides * 4 <= all)
            assertFalse("$k: its centre selects", ev.extraBadgeHit(k, r, pic.centerX(), pic.centerY()))
            assertTrue("$k: the badge's own centre hides", ev.extraBadgeHit(k, r, r.left + HomeEditView.BADGE_IN, r.top + HomeEditView.BADGE_IN))
            assertTrue("$k: off the picture the full reach", ev.extraBadgeHit(k, r, r.left - 14f, r.top - 14f))
        }
        // a finger on the Hearthstone's picture: the extra selected, nothing hidden
        val hr = ev.extraPicture("hearth", g.extras.first { it.first == "hearth" }.second)
        t0 = android.os.SystemClock.uptimeMillis()
        touch(p, MotionEvent.ACTION_DOWN, hr.centerX() + 3f, hr.centerY() + 3f); touch(p, MotionEvent.ACTION_UP, hr.centerX() + 3f, hr.centerY() + 3f)
        settle(8)
        assertTrue("still on", HomeSettings.clockHearth)
        assertEquals(EditStop.EXTRAS, m.cursor); assertEquals("hearth", m.extra)
        // the readability pass: the cursor hugs the 28 px picture (not the taller cell with the picture at its top)
        val cur = ev.bandBox(EditStop.EXTRAS)!!
        assertEquals("the cursor's box is the picture", hr, cur)
        assertTrue("the cell is taller than its picture", g.extras.first { it.first == "hearth" }.second.height() > cur.height() + 8f)
        p.home.editCancel()
    }

    /** fix round 3 (the review: an extra's "-" badge kept its full reach off its picture, ON the D-pad's Right button too, so a tap on
     *  that button's lower part hid the first extra): on the D-pad's and the ABXY's buttons (their pencils' too) an extra takes a finger
     *  only on its own cell or its drawn badge; every other point of a button opens that button's bubble */
    @Test fun theDpadsButtonsKeepTheirTapsBesideTheExtras() {
        val p = bandPage()
        HomeSettings.clockHearth = true; HomeSettings.clockTeleport = true; HomeSettings.clockGold = true; HomeSettings.clockBags = true
        HomeSettings.clockAmmo = true
        assertTrue(p.home.editOpen(EditStop.CHAT)); settle(30)
        val ev = p.home.editChrome
        val g = geo(p)!!
        assertTrue(g.extras.isNotEmpty())
        val bars = boxes(p.home).getValue("BARS")
        fun badge(k: String, r: RectF) = if (k == "gold") Pair(r.left - HomeEditView.EXTRA_BADGE_R - 2f, r.centerY())
            else Pair(r.left + HomeEditView.BADGE_IN, r.top + HomeEditView.BADGE_IN)
        fun grown(r: RectF?) = r?.let { RectF(it).apply { inset(-maxOf(0f, (HomeEditView.TOUCH_MIN - width()) / 2f), -maxOf(0f, (HomeEditView.TOUCH_MIN - height()) / 2f)) } }
        val own = listOfNotNull(grown(g.look), grown(g.fightPen), g.plus)
        var bad = 0; var first: String? = null; var near = 0
        val slot = RectF()
        for (pos in 0 until 8) {
            p.cast.slotRect(pos, slot); slot.offset(bars.left, bars.top)
            val want = if (pos < 4) EditStop.PAD_PEN else EditStop.FACE_PEN
            var y = slot.top + 0.5f
            while (y < slot.bottom) {
                var x = slot.left + 0.5f
                while (x < slot.right) {
                    val onExtra = g.extras.any { (k, r) -> r.contains(x, y) ||
                        badge(k, r).let { (bx, by) -> kotlin.math.hypot(x - bx, y - by) <= HomeEditView.EXTRA_BADGE_R + HomeEditView.EXTRA_BADGE_SLOP } }
                    if (!onExtra && own.none { it.contains(x, y) }) {
                        if (g.extras.any { (k, r) -> badge(k, r).let { (bx, by) -> kotlin.math.hypot(x - bx, y - by) <= Strip.BADGE_HIT / 2f } }) near++
                        val got = ev.stopAt(x, y)
                        if (got != want) { bad++; if (first == null) first = "slot $pos at ($x, $y): $got" }
                    }
                    x += 1f
                }
                y += 1f
            }
        }
        assertTrue("the badges' reach does come onto the buttons (the case checked: $near points)", near > 0)
        assertEquals("every point of a button off the extras opens its bubble (first: $first)", 0, bad)
        p.home.editCancel()
    }

    /** fix round (the review: the Fight toggle right of the disc took about 130 px of the hints): the Fight toggle sits left of the disc,
     *  clear of "+", and the hints have their 1518 room again; the readability pass: a state's hints are all kept at a smaller text
     *  before anything is dropped, so EVERY hint shows in every state in English, Spanish and French (French lifts excepted: their
     *  three never fit beside "Abandonner"'s Done, as in 1518) */
    /** a failed state's hints' widths at the smallest and the full text (the message's reading) */
    private fun hintWidths(ev: HomeEditView, pairs: List<Pair<String, String>>): String {
        val hw = HomeEditView::class.java.getDeclaredMethod("hintsW", List::class.java, Int::class.javaPrimitiveType, android.graphics.Paint::class.java).apply { isAccessible = true }
        val bp = HomeEditView::class.java.declaredMethods.first { it.name.startsWith("bodyP") && it.parameterTypes.size == 3 }.apply { isAccessible = true }
        val p18 = bp.invoke(ev, 18f, Theme.text, android.graphics.Paint.Align.LEFT)
        val p22 = bp.invoke(ev, 22f, Theme.text, android.graphics.Paint.Align.LEFT)
        return "w18=${hw.invoke(ev, pairs, pairs.size, p18)} w22=${hw.invoke(ev, pairs, pairs.size, p22)} done=${ev.ribbon(W.toFloat()).done.width()} words=${pairs.map { Tr.t(it.second) }}"
    }
    @Test fun theRibbonKeepsEveryHintInEveryLanguage() {
        val rowsOf = HomeView::class.java.getDeclaredMethod("editMenuRows", EditStop::class.java).apply { isAccessible = true }
        val lost = ArrayList<String>()
        try {
            for (l in listOf("en", "es", "fr")) {
                lang(l)
                val p = bandPage(roomBar = true)
                drawStrip(p)
                assertTrue(p.home.editOpen(EditStop.CHAT)); settle()
                val ev = p.home.editChrome
                val g1 = W / 2f + Strip.GAP_HALF
                val r = ev.ribbon(W.toFloat())
                assertTrue("$l: Fight left of the disc", r.fight.right <= W / 2f - Strip.GAP_HALF)
                assertTrue("$l: Fight clear of +", r.fight.left > r.plusHit.right)
                val states = listOf<Pair<String, (HomeEditModel) -> List<HomeEditModel.Fx>>>(
                    "top" to { m -> m.startOn(EditStop.CHAT, false) },
                    "top map" to { m -> m.startOn(EditStop.MAP, false) },
                    "top card" to { m -> m.startOn(EditStop.QUEST, false) },
                    "top bar" to { m -> m.startOn(EditStop.CAST, false) },
                    "top room" to { m -> m.startOn(EditStop.ROOMS, false) },
                    "the D-pad's pencil" to { m -> m.startOn(EditStop.PAD_PEN, false) },
                    "the Look chip" to { m -> m.startOn(EditStop.LOOK, false) },
                    "an extra" to { m -> HomeSettings.clockGold = true; m.startOn(EditStop.EXTRAS, false) },
                    "Fight" to { m -> m.startOn(EditStop.FIGHT, false) },
                    "Done" to { m -> m.startOn(EditStop.DONE, false) },
                    "the divider" to { m -> m.startOn(EditStop.DIVIDER, false) },
                    "lifted chat" to { m -> m.startOn(EditStop.CHAT, true) },
                    "lifted card" to { m -> m.startOn(EditStop.QUEST, true) },
                    "lifted bar" to { m -> m.startOn(EditStop.CAST, true) },
                    "lifted room" to { m -> m.startOn(EditStop.ROOMS, true) },
                    "chat menu" to { m -> m.openMenu(EditStop.CHAT); emptyList() },
                    "D-pad bubble" to { m -> m.startOn(EditStop.PAD_PEN, false); m.pencilTap(EditStop.PAD_PEN) },
                    "ABXY bubble" to { m -> m.startOn(EditStop.FACE_PEN, false); m.pencilTap(EditStop.FACE_PEN) },
                    "extras tray" to { m -> HomeSettings.clockGold = false; m.startOn(EditStop.EXTRAS, false); m.openExtraTray() })
                for ((n, f) in states) {
                    p.home.editAct { it.drop(); it.closeMenu(); it.closeSheet(); f(it) }; settle()
                    val m = model(p)
                    @Suppress("UNCHECKED_CAST")
                    val pairs = ev.hintsFor(m, m.menu?.let { s -> (rowsOf.invoke(p.home, s) as List<MenuRow>).getOrNull(m.menuRow)?.kind })
                    val c = Canvas(Bitmap.createBitmap(W, Strip.TRACKS, Bitmap.Config.ARGB_8888))
                    ev.hintsKeptDrawn = -1; ev.hintsLeftDrawn = Float.NaN
                    ev.drawRibbon(c, W.toFloat(), 0f, W / 2f - Strip.GAP_HALF, g1)
                    assertTrue("$l $n: clear of the disc", ev.hintsLeftDrawn >= g1)
                    if (m.menu != null || m.sheet != null || m.lifted != null)
                        assertEquals("$l $n: B's hint is there", UiKit.backGlyph(), pairs.last().first)
                    // the readability pass (the shots: only "A Move" with the chat selected, room to spare): EVERY hint of every state
                    // shows in all three languages (the text shrinks to RIBBON_TEXT_MIN before any is dropped)
                    // the one exception: French lifts (Côté / Déplacer, Lâcher, Remettre beside "Abandonner"'s wide Done: 356 px of
                    // hints at the smallest text in a 322 px room) never fitted, in 1518 either - the least needed (B's) goes there
                    if (l == "fr" && m.lifted != null) assertTrue("$l $n: Move and Drop kept", ev.hintsKeptDrawn >= pairs.size - 1)
                    else if (pairs.size != ev.hintsKeptDrawn) lost += "$l $n: ${ev.hintsKeptDrawn} of ${pairs.map { it.second }} " +
                        "(room ${ev.ribbon(W.toFloat()).done.left - 18f - g1 - HomeEditView.HINT_GAP}) needs ${hintWidths(ev, pairs)}"
                }
                p.home.editCancel()
            }
        } finally { Tr.setChoice(app, "auto") }
        assertTrue("hints lost: $lost", lost.isEmpty())
    }

    /** fix round (the review: under a tap-to-cast bar at a column's top a pencil bubble's tail landed on that bar): the bubble a bar pushed
     *  down has no tail; with no bar there, its tail hangs from the band */
    @Test fun aBubbleUnderATopCastBarHasNoTail() {
        for (cast in listOf(HomeLayout.CastDock.UNDER_CHAT, HomeLayout.CastDock.UNDER_CHAT.withTop(true))) {
            val p = bandPage(l = HomeLayout(cast = cast))
            assertTrue(p.home.editOpen(EditStop.PAD_PEN)); settle(30)
            p.home.editAct { it.pencilTap(EditStop.PAD_PEN) }; settle(8)
            val ev = p.home.editChrome
            val box = ev.menuBox(EditStop.PAD_PEN)
            val bars = boxes(p.home).getValue("BARS")
            val tail = ev.bubbleTailBox(EditStop.PAD_PEN)
            if (cast.top) {
                assertTrue("$cast: the bubble under the bar", box.top > bars.bottom + 60f)
                assertNull("$cast: no tail onto the bar", tail)
                shot(p, "50_bubble_under_top_cast_bar", frames = 1)
            } else {
                assertNotNull("$cast: the tail", tail)
                assertTrue("$cast: the tail's tip at the band", tail!!.top >= bars.bottom - 1f && tail.top <= bars.bottom + UiKit.MODULE_GAP + 2f)
            }
            p.home.editCancel()
        }
    }

    /** fix round 3 (the review: a pencil's 640 px bubble overhangs the other column by about 12 px, so a top tap-to-cast bar THERE pushed
     *  it about 140 px down, tailless, over its own empty column): a bar the bubble only overhangs narrows the bubble instead - it still
     *  hangs from the band under its pencil, with its tail, clear of the bar, at least MENU_MIN_W wide */
    @Test fun aBubbleKeepsItsTailBesideATopCastBarInTheOtherColumn() {
        for ((cast, pen) in listOf(HomeLayout.CastDock.ABOVE_MAP to EditStop.PAD_PEN, HomeLayout.CastDock.ABOVE_CHAT to EditStop.FACE_PEN)) {
            val p = bandPage(l = HomeLayout(cast = cast))
            assertTrue(p.home.editOpen(pen)); settle(30)
            p.home.editAct { it.pencilTap(pen) }; settle(8)
            val ev = p.home.editChrome
            val box = ev.menuBox(pen)
            val all = boxes(p.home)
            val bars = all.getValue("BARS")
            val bar = all.getValue("STRIP")
            assertTrue("$cast: the bar at its column's top", bar.top < bars.bottom + 40f)
            assertFalse("$cast: the bubble clear of the bar ($box, $bar)", RectF.intersects(box, bar))
            assertTrue("$cast: the bubble hangs from the band ($box)", box.top <= bars.bottom + UiKit.MODULE_GAP + HomeEditView.BUBBLE_TAIL + 1f)
            assertTrue("$cast: still a bubble's width (${box.width()})", box.width() >= HomeEditView.MENU_MIN_W)
            val tail = ev.bubbleTailBox(pen)
            assertNotNull("$cast: the tail", tail)
            val pc = ev.bandBox(pen)!!.centerX()
            assertTrue("$cast: the tail under its pencil", kotlin.math.abs(tail!!.centerX() - pc) < 1f)
            if (pen == EditStop.PAD_PEN) shot(p, "51_bubble_beside_other_column_bar", frames = 1)
            p.home.editCancel()
        }
    }

    /** fix round (the review, two findings): while the Same size / Top screen preview shows, the clock block is a fight's - the extras
     *  with data only, no "+" cell, nothing wiggling (no piece of it can be edited then) */
    @Test fun theSameSizePreviewDrawsTheFightsClockRow() {
        val clock = HomeView::class.java.getDeclaredMethod("editClock").apply { isAccessible = true }
        val pose = HomeView::class.java.getDeclaredMethod("extraPoseAt", String::class.java).apply { isAccessible = true }
        // (the merge with feat/topbar-fight: Top screen's preview lays the band above the page - TopSlide - so only Same size keeps the
        // band's clock block on the page)
        for (band in listOf(HomeLayout.FightBand.SAME)) {
            val p = bandPage(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = band), ct = 1)
            HomeSettings.clockAmmo = false; HomeSettings.clockBags = true             // "+" exists, the bags said nothing yet
            assertTrue(p.home.editOpen(EditStop.FIGHT)); settle(20)
            lay(p.home)
            p.home.draw(Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)))
            val editKeys = field(p.home, "editRowDrawn") as List<*>
            assertTrue("$band: the edit row has the bags and + ($editKeys)", "bags" in editKeys && editKeys.size > 1)
            p.home.editKey("PAD1"); settle(40)
            assertTrue(model(p).fightPreview)
            assertFalse("$band: the clock block is not edit mode's", clock.invoke(p.home) as Boolean)
            for (k in HomeEditModel.EXTRA_KEYS) assertFalse("$band: $k still", pose.invoke(p.home, k) as Boolean)
            val g = geo(p)!!
            assertTrue("$band: no extras pieces", g.extras.isEmpty()); assertNull(g.plus)
            // two frames a wiggle apart draw the band the same outside its buttons (nothing on the clock block turns)
            val bars = boxes(p.home).getValue("BARS")
            val a = bandPx(p, bars)
            ShadowSystemClock.advanceBy(Duration.ofMillis(110))
            val b = bandPx(p, bars)
            val cv = p.cast
            val buttons = (0 until 8).map { i -> RectF().also { cv.slotRect(i, it); it.inset(-32f, -32f) } } +
                listOfNotNull(g.fightPen?.let { RectF(it).apply { offset(-bars.left, -bars.top); inset(-6f, -6f) } })
            // the clock block's column (the spot between the D-pad and the faces; the swing line's stand-in loops along the foot)
            val sp = cv.clockSpot()!!
            val bw = bars.width().toInt()
            val foot = bars.height() - 24f                                         // the band's foot: the swing line and Top screen's cue
            val diff = a.indices.filter { k -> a[k] != b[k] && (k % bw) >= sp[0] && (k % bw) <= sp[2] && (k / bw) < foot &&
                buttons.none { it.contains((k % bw).toFloat(), (k / bw).toFloat()) } }
            if (diff.isNotEmpty()) dumpDiff("51_preview_clock_${band.name.lowercase()}", a, b, bw, bars.height().toInt())
            assertEquals("$band: nothing on the clock block moves (x ${diff.minOfOrNull { it % bw }}..${diff.maxOfOrNull { it % bw }} " +
                "y ${diff.minOfOrNull { it / bw }}..${diff.maxOfOrNull { it / bw }}, chip ${g.chip}, bars $bars)", 0, diff.size)
            p.home.editCancel()
        }
    }

    /** a finger over the band (P1 - P4): a pencil opens its bubble (its buttons too), the Look chip flips, a "-" badge hides, "+" opens
     *  the tray, a tray row puts it back; the ribbon's Fight toggles the preview; every bubble hangs under the band, on the screen */
    @Test fun aFingerOnTheBandsPieces() {
        val p = bandPage(roomBar = true)
        assertTrue(p.home.editOpen(EditStop.CHAT)); settle(40)
        val m = model(p)
        fun tap(r: RectF) { t0 = android.os.SystemClock.uptimeMillis(); touch(p, MotionEvent.ACTION_DOWN, r.centerX(), r.centerY()); touch(p, MotionEvent.ACTION_UP, r.centerX(), r.centerY()); settle(8) }
        val bars = boxes(p.home).getValue("BARS")
        var g = geo(p)!!
        tap(g.padPen); assertEquals(EditStop.PAD_PEN, m.menu)
        val bubble = p.home.editChrome.menuBox(EditStop.PAD_PEN)
        assertTrue("the bubble under the band", bubble.top >= bars.bottom)
        assertTrue("on the screen", bubble.left >= 16f && bubble.right <= W - 16f && bubble.bottom <= H)
        shot(p, "43_bubble_dpad", frames = 1)
        tap(g.padPen); assertNull("the same tap closes it", m.menu)
        // the faces' buttons are their pencil's too
        val f = RectF(g.face).apply { set(centerX() - 4f, bottom - 30f, centerX() + 4f, bottom - 22f) }
        tap(f); assertEquals(EditStop.FACE_PEN, m.menu)
        shot(p, "44_bubble_buttons", frames = 1)
        tap(RectF(600f, 900f, 602f, 902f)); assertNull("a tap off it closes it", m.menu)
        // a "-" badge hides that extra
        val hearth = g.extras.first { it.first == "hearth" }.second
        tap(RectF(hearth.left + 1f, hearth.top + 1f, hearth.left + 3f, hearth.top + 3f))
        assertFalse(HomeSettings.clockHearth)
        settle(10); g = geo(p)!!
        assertNotNull("the + spot", g.plus)
        tap(g.plus!!); assertEquals(HomeEditModel.Sheet.EXTRAS, m.sheet)
        val tray = p.home.editChrome.extraTrayBox()!!
        assertTrue("the tray on the screen, under the band", tray.top >= bars.bottom && tray.bottom <= H && tray.left >= 16f && tray.right <= W - 16f)
        shot(p, "45_extras_tray", frames = 1)
        // a frame drawn (as the screen does: the tray's rows are hit where they were drawn - with HOME_EDIT_SHOTS the shot drew one)
        p.home.draw(Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)))
        val row = RectF(tray.left + 40f, tray.top + HomeEditView.TRAY_HEAD + 20f, tray.left + 42f, tray.top + HomeEditView.TRAY_HEAD + 22f)
        tap(row); assertTrue("back", HomeSettings.clockHearth); assertNull(m.sheet)
        // the Look chip
        g = geo(p)!!
        tap(g.look!!); assertEquals(HomeRest.ROW, HomeSettings.rest); settle(20)
        shot(p, "46_thin_row_pieces", frames = 1)
        tap(geo(p)!!.look!!); assertEquals(HomeRest.DIAMONDS, HomeSettings.rest); settle(20)
        // the ribbon's Fight
        val ev = p.home.editChrome
        val fr = ev.ribbon(W.toFloat()).fight
        assertTrue("Fight left of the disc (fix round)", fr.right <= W / 2f - Strip.GAP_HALF)
        ev.ribbonTouch(fr.centerX(), fr.centerY(), MotionEvent.ACTION_DOWN); ev.ribbonTouch(fr.centerX(), fr.centerY(), MotionEvent.ACTION_UP)
        settle(30)
        assertTrue(m.fightPreview)
        shot(p, "47_fight_preview_grow")
        // the controller reaches the band from the ribbon, B closes a bubble
        p.home.editKey("PADDUP"); assertTrue(m.cursor.band)
        p.home.editKey("PAD1"); p.home.editKey("PAD2"); assertNull(m.menu)
        p.home.editKey("PADFORWARD"); settle(30)
        assertFalse(p.home.editing)
    }

    /** the bubbles and the ribbon's Fight in Spanish and French: every row inside its bubble, the Fight toggle clear of the disc and the
     *  hints clear of it */
    @Test fun theBandsBubblesInSpanishAndFrench() {
        try {
            for (l in listOf("es", "fr")) {
                lang(l)
                val p = bandPage()
                assertTrue(p.home.editOpen(EditStop.PAD_PEN)); settle(30)
                for (s in listOf(EditStop.PAD_PEN, EditStop.FACE_PEN)) {
                    p.home.editAct { it.pencilTap(s) }; settle(8)
                    val box = p.home.editChrome.menuBox(s)
                    assertTrue("$l $s on the screen", box.left >= 16f && box.right <= W - 16f && box.bottom <= H)
                    shot(p, "48_${l}_bubble_${s.name.lowercase()}", frames = 1)
                    p.home.editKey("PAD2")
                }
                p.home.editAct { it.toggleFight() }; settle(20)
                p.home.editAct { it.pencilTap(EditStop.FIGHT_PEN) }; settle(8)
                val fb = p.home.editChrome.menuBox(EditStop.FIGHT_PEN)
                assertTrue("$l fight bubble under the grown band", fb.top >= boxes(p.home).getValue("BARS").bottom)
                shot(p, "48_${l}_bubble_fight", frames = 1)
                val ev = p.home.editChrome
                val r = ev.ribbon(W.toFloat())
                assertTrue("$l: Fight left of the disc (fix round)", r.fight.right <= W / 2f - Strip.GAP_HALF)
                assertTrue("$l: Fight right of +", r.fight.left > r.plusHit.right)
                p.home.editCancel()
            }
        } finally { lang("en") }
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
        val layouts = listOf("bigmap" to HomeArrangements.of(HomeArrangements.BIG_MAP, HomeLayout()),
            "bigchat" to HomeArrangements.of(HomeArrangements.BIG_CHAT, HomeLayout()),
            // the bar above the third-wide chat: its menu's Above / Below row, the map's menu under the bar
            "bigmap_topbar" to HomeArrangements.of(HomeArrangements.BIG_MAP, HomeLayout()).copy(cast = HomeLayout.CastDock.ABOVE_CHAT))
        val rowsOf = HomeView::class.java.getDeclaredMethod("editMenuRows", EditStop::class.java).apply { isAccessible = true }
        try {
            for (l in listOf("en", "es", "fr")) {
                lang(l)
                for ((ln, layout) in layouts) {
                    val p = page(layout)
                    assertTrue(p.home.editOpen(EditStop.CHAT)); settle()
                    for (s in listOf(EditStop.CHAT, EditStop.MAP, EditStop.QUEST, EditStop.CAST)) {
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
                        if (layout.cast.top && (s == EditStop.CAST || s == EditStop.MAP)) {
                            val bar = boxes(p.home).getValue("STRIP")
                            assertTrue("$l $ln $s: the menu opens under the top bar", ev.menuBox(s).top >= bar.bottom + 7.5f)
                            if (s == EditStop.CAST) assertTrue("$l $ln: the Above / Below row", words.any { it.key == HomeEditModel.K_CAST_EDGE })
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

    /**
     * after 1518, the user's ruling 2026-11-22 ("Why can't I move the party mode or the tap to cast to the top?"): the party list is a
     * widget - the D-pad walks to it, A lifts it (it grows and stops wiggling), the D-pad carries it to the foot of the chat's column,
     * the map's foot, the map's top (its Select tab on that column's outer edge), A drops it (saved as it goes), Select carries it with
     * the map, X its menu over the other column, and Discard puts the opening record back; its own gold row box stays hidden
     */
    @Test fun thePartyListMovesWithTheController() {
        val classic = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
        val p = page(classic, party = true)
        p.home.forceLive = true
        assertTrue(p.home.editOpen(EditStop.CHAT)); settle(40)
        assertFalse("Party mode's D-pad waits while editing", p.home.partyHoldsDpad())
        val pv = field(p.home, "partyView") as PartyModuleView
        assertFalse("fix round 1: the party's own gold row box hidden while editing (no second cursor)", pv.boxShown())
        val ev = p.home.editChrome
        assertTrue("a stop now", EditStop.PARTY in ev.stops())
        assertEquals(boxes(p.home).getValue("PARTY"), ev.widgetBoxes()[EditStop.PARTY])
        shot(p, "30_party_list_edit")
        p.home.editKey("PADDUP"); settle()
        assertEquals("Up from the chat: the party list over it", EditStop.PARTY, model(p).cursor)
        assertEquals(listOf(UiKit.okGlyph() to "Move", UiKit.xGlyph() to "Options"), ev.hintsFor(model(p)))
        shot(p, "30a_party_list_picked", frames = 1)
        p.home.editKey("PAD1"); settle(20)
        assertEquals(EditStop.PARTY, model(p).lifted)
        assertEquals("the four-way Move", HomeEditView.DPAD4 to "Move", ev.hintsFor(model(p)).first())
        assertTrue("lifted: it grows over the others", pv.scaleX > views(p.home).getValue("CHAT").scaleX + 0.01f)
        assertTrue("raised over the others", pv.translationZ > views(p.home).getValue("CHAT").translationZ)
        shot(p, "30b_party_list_lifted", frames = 1)
        // Down: the chat's foot, over the bar under the chat
        p.home.editKey("PADDDOWN"); settle(30)
        var b = boxes(p.home)
        assertEquals(HomeLayout.PartyDock.UNDER_CHAT, model(p).draft.party)
        assertEquals(b.getValue("STRIP").top - 8f, b.getValue("PARTY").bottom, 0.5f)
        assertEquals(b.getValue("PARTY").top - PartyLayout.CHAT_GAP, b.getValue("CHAT").bottom, 0.5f)
        assertEquals(0f, b.getValue("PARTY").left, 0.5f)
        assertEquals("lifted: not saved yet", classic, HomeSettings.layout)
        shot(p, "30c_party_list_under_chat", frames = 1)
        // Right: the map's foot (the page's foot: no bar there), its tab on the right edge
        p.home.editKey("PADDRIGHT"); settle(30)
        b = boxes(p.home)
        assertEquals(HomeLayout.PartyDock.UNDER_MAP, model(p).draft.party)
        assertEquals(927f, b.getValue("PARTY").bottom, 0.5f)
        assertEquals(1240f, b.getValue("PARTY").right, 0.5f)
        assertTrue(pv.tabRight)
        assertTrue("the card on the map ends over it", b.getValue("QUESTS").bottom <= b.getValue("PARTY").top)
        shot(p, "30d_party_list_under_map", frames = 1)
        // Up: the map's top (the card under the map's zone band below it)
        p.home.editKey("PADDUP"); settle(30)
        b = boxes(p.home)
        assertEquals(HomeLayout.PartyDock.ABOVE_MAP, model(p).draft.party)
        assertEquals(b.getValue("BARS").bottom + 8f, b.getValue("PARTY").top, 0.5f)
        assertEquals(b.getValue("PARTY").bottom + PartyLayout.CHAT_GAP, b.getValue("MAP").top, 0.5f)
        assertEquals(b.getValue("MAP").top + HomeView.ZONE_BAND_BOTTOM + 4f, b.getValue("QUESTS").top, 0.5f)
        // A: dropped and saved
        p.home.editKey("PAD1"); settle(30)
        assertNull(model(p).lifted)
        assertEquals(classic.copy(party = HomeLayout.PartyDock.ABOVE_MAP), HomeSettings.layout)
        shot(p, "30e_party_list_above_map", frames = 1)
        // Select: the modules swap, the party list stays the map's (now on the left, its tab on the left edge)
        p.home.editKey("PADBACK"); settle(30)
        b = boxes(p.home)
        assertEquals(0f, b.getValue("PARTY").left, 0.5f)
        assertFalse(pv.tabRight)
        assertTrue(b.getValue("MAP").left < b.getValue("CHAT").left)
        assertEquals(b.getValue("PARTY").bottom + PartyLayout.CHAT_GAP, b.getValue("MAP").top, 0.5f)
        shot(p, "30f_party_list_swapped", frames = 1)
        // X: its menu over the chat's column (the one it is not in), inside the screen, under the band
        p.home.editKey("PAD3"); settle(20)
        assertEquals(EditStop.PARTY, model(p).menu)
        val menu = ev.menuBox(EditStop.PARTY)
        assertTrue("menu $menu over the chat's column", menu.left >= b.getValue("CHAT").left - 1f && menu.right <= b.getValue("CHAT").right + 1f)
        assertTrue(menu.top >= b.getValue("BARS").bottom && menu.bottom <= H)
        assertFalse("never over the party list", RectF.intersects(menu, b.getValue("PARTY")))
        shot(p, "30g_party_list_menu", frames = 1)
        // its Edge row: Below = the map's foot
        p.home.editKey("PADDDOWN"); p.home.editKey("PADDRIGHT"); settle(30)
        assertEquals(HomeLayout.PartyDock.UNDER_MAP, model(p).draft.party)
        assertEquals(HomeLayout.PartyDock.UNDER_MAP, HomeSettings.layout.party)
        p.home.editKey("PAD2"); settle()
        // Discard: the opening record back, the party list over the chat again
        p.home.editB(true); settle(45)
        assertFalse(p.home.editing)
        assertEquals(classic, HomeSettings.layout)
        b = boxes(p.home)
        assertEquals(0f, b.getValue("PARTY").left, 0.5f)
        assertEquals(b.getValue("PARTY").bottom + PartyLayout.CHAT_GAP, b.getValue("CHAT").top, 0.5f)
        assertTrue("edit mode over: the row box again", pv.boxShown())
    }

    /** a finger drags the party list: from the chat's top down to the map's foot (the release lays the dock under it), and back up
     *  over the chat; the drop well shows where it goes */
    @Test fun thePartyListMovesWithAFinger() {
        val classic = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
        val p = page(classic, party = true)
        p.home.forceLive = true
        assertTrue(p.home.editOpen(EditStop.CHAT)); settle(40)
        var party = boxes(p.home).getValue("PARTY")
        val x0 = party.centerX(); val y0 = party.centerY()
        t0 = android.os.SystemClock.uptimeMillis()
        touch(p, MotionEvent.ACTION_DOWN, x0, y0)
        for (i in 1..12) { touch(p, MotionEvent.ACTION_MOVE, x0 + 52f * i, y0 + 48f * i); settle(2) }
        assertEquals("a drag lifts it", EditStop.PARTY, model(p).lifted)
        assertNotNull(model(p).drag)
        shot(p, "31_party_list_dragged", frames = 1)
        touch(p, MotionEvent.ACTION_UP, x0 + 52f * 12, y0 + 48f * 12); settle(40)
        assertNull(model(p).lifted)
        assertEquals(HomeLayout.PartyDock.UNDER_MAP, HomeSettings.layout.party)
        party = boxes(p.home).getValue("PARTY")
        assertEquals(927f, party.bottom, 0.5f); assertEquals(1240f, party.right, 0.5f)
        shot(p, "31a_party_list_dropped_under_map", frames = 1)
        // and back over the chat's top
        val x1 = party.centerX(); val y1 = party.centerY()
        t0 = android.os.SystemClock.uptimeMillis()
        touch(p, MotionEvent.ACTION_DOWN, x1, y1)
        for (i in 1..12) { touch(p, MotionEvent.ACTION_MOVE, x1 - 52f * i, y1 - 48f * i); settle(2) }
        touch(p, MotionEvent.ACTION_UP, x1 - 52f * 12, y1 - 48f * 12); settle(40)
        assertEquals(classic, HomeSettings.layout)
        party = boxes(p.home).getValue("PARTY")
        assertEquals(0f, party.left, 0.5f)
        assertEquals(party.bottom + PartyLayout.CHAT_GAP, boxes(p.home).getValue("CHAT").top, 0.5f)
        p.home.editCancel()
    }

    /** the party list's menu fits in English, Spanish and French, at a third of the width, from each column */
    @Suppress("UNCHECKED_CAST")
    @Test fun thePartyListsMenuFitsInSpanishAndFrench() {
        val rowsOf = HomeView::class.java.getDeclaredMethod("editMenuRows", EditStop::class.java).apply { isAccessible = true }
        try {
            for (l in listOf("en", "es", "fr")) {
                lang(l)
                for (dock in HomeLayout.PartyDock.values()) for (base in listOf(HomeArrangements.of(HomeArrangements.BIG_MAP, HomeLayout()),
                        HomeArrangements.of(HomeArrangements.BIG_CHAT, HomeLayout()).swapped())) {
                    val layout = base.copy(party = dock)
                    val p = page(layout, party = true)
                    assertTrue(p.home.editOpen(EditStop.CHAT)); settle()
                    p.home.editAct { it.openMenu(EditStop.PARTY); emptyList() }; settle()
                    val img = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
                    p.home.draw(Canvas(img))
                    val ev = p.home.editChrome
                    val btns = field(ev, "menuBoxes") as List<List<RectF>>
                    val rows = field(ev, "menuRowBoxes") as List<RectF>
                    val words = rowsOf.invoke(p.home, EditStop.PARTY) as List<MenuRow>
                    val tag = "$l $dock ${layout.chatSide} ${layout.chatShare}"
                    assertEquals(tag, 2, words.size); assertEquals(tag, words.size, rows.size)
                    val lp = UiKit.bodyPaint(28f)
                    for ((i, r) in words.withIndex()) {
                        val rr = rows[i]
                        val labelRight = rr.left + 18f + lp.measureText(Tr.t(r.label))
                        for (bt in btns[i]) {
                            assertTrue("$tag '${Tr.t(r.label)}': button inside its row", bt.left >= rr.left - 1f && bt.right <= rr.right + 1f)
                            assertTrue("$tag '${Tr.t(r.label)}': buttons clear of the label", bt.left >= labelRight - 1f || bt.top >= rr.top + 0.3f * rr.height())
                        }
                    }
                    val box = ev.menuBox(EditStop.PARTY)
                    val b = boxes(p.home)
                    assertTrue("$tag: inside the screen, under the band", box.left >= -0.5f && box.right <= W + 0.5f &&
                        box.top >= b.getValue("BARS").bottom - 0.5f && box.bottom <= H + 0.5f)
                    for (c in ev.castBoxes()) assertFalse("$tag: never over the bar", RectF.intersects(box, c))
                    if (l != "en") shot(p, "33p_menu_${l}_${dock.name.lowercase()}_${layout.chatShare.name.lowercase()}")
                    p.home.editCancel()
                }
            }
        } finally { Tr.setChoice(app, "auto") }
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

    /** 1518: a long press on Done (and a held B on the direct feed) is Discard - the record edit mode opened on written back, Home
     *  glides back to it; a tap on Done, a short B: Done (the change kept, it was saved as it was made) */
    @Test fun aLongPressOnDoneOrAHeldBDiscards() {
        val classic = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
        val p = page(classic)
        p.home.forceLive = true
        assertTrue(p.home.editOpen(EditStop.MAP))
        p.home.editKey("PADBACK"); settle()
        assertTrue(boxes(p.home).getValue("MAP").left < boxes(p.home).getValue("CHAT").left)
        assertTrue("saved as it is made", HomeSettings.layout.mirrored)
        // the long press on Done (DISCARD_HOLD_MS): Discard fires while the finger is still down; its release does nothing more
        val ev = p.home.editChrome
        val done = ev.ribbon(W.toFloat()).done
        assertTrue(ev.ribbonTouch(done.centerX(), done.centerY(), MotionEvent.ACTION_DOWN))
        settle(10)
        assertTrue("not yet", p.home.editing)
        settle(40)
        assertFalse("held past ${HomeEditModel.DISCARD_HOLD_MS} ms: discarded", p.home.editing)
        ev.ribbonTouch(done.centerX(), done.centerY(), MotionEvent.ACTION_UP); settle()
        assertEquals(classic, HomeSettings.layout)
        assertTrue("back where it was", boxes(p.home).getValue("CHAT").left < boxes(p.home).getValue("MAP").left)
        // a held B on the direct feed: the same
        assertTrue(p.home.editOpen(EditStop.CHAT)); settle()
        p.home.editKey("PADBACK"); settle()
        assertTrue(HomeSettings.layout.mirrored)
        p.home.editB(true); settle(45)
        assertFalse("B held: discarded", p.home.editing)
        p.home.editB(false); settle()
        assertEquals(classic, HomeSettings.layout)
        // a short B, a tap on Done: Done, the change kept
        assertTrue(p.home.editOpen(EditStop.CHAT)); settle()
        p.home.editKey("PADBACK"); settle()
        p.home.editB(true); settle(5); p.home.editB(false); settle()
        assertFalse("a short B at the top: Done", p.home.editing)
        assertTrue("kept", HomeSettings.layout.mirrored)
        assertTrue(p.home.editOpen(EditStop.CHAT)); settle()
        p.home.editKey("PADBACK"); settle()
        assertTrue(ev.ribbonTouch(done.centerX(), done.centerY(), MotionEvent.ACTION_DOWN)); settle(3)
        ev.ribbonTouch(done.centerX(), done.centerY(), MotionEvent.ACTION_UP); settle()
        assertFalse("a tap on Done: Done", p.home.editing)
        assertEquals(classic, HomeSettings.layout.copy(name = classic.name))
        // the hold's touch suspension: no band touch-cast while editing (the chrome takes every touch)
        p.home.editOpen(EditStop.CHAT); settle()
        assertTrue(p.home.homeOwnGuestWanted())
        assertFalse(p.home.partyHoldsDpad())
    }

    /**
     * the review of 1518: Done wears B's glyph only where B is Done (Start's under a menu, a tray, a lift); a short B or tap never
     * flashes "Discard" (the hold shows from RING_FROM_MS); the ribbon's "+" and Done take a finger 48 px tall (into the rooms' row's
     * top); Done is a controller stop - Right from the bar's end, X Discard (the relayed feed, which sees no held B); a D-pad icon that
     * cannot move promises no move
     */
    @Test fun theRibbonsReviewFixes() {
        val classic = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
        val p = page(classic, roomBar = true)
        p.home.forceLive = true
        drawStrip(p)
        assertTrue(p.home.editOpen(EditStop.CHAT)); settle()
        val ev = p.home.editChrome
        assertEquals(UiKit.backGlyph(), ev.doneGlyph(model(p)))
        p.home.editKey("PAD3"); settle()
        assertNotNull("the chat's menu", model(p).menu)
        assertEquals("B is Back here: Start's glyph on Done", Glyphs.start(), ev.doneGlyph(model(p)))
        p.home.editKey("PAD2"); settle()
        p.home.editKey("PAD1"); settle()
        assertEquals(EditStop.CHAT, model(p).lifted)
        assertEquals("B is Put back here", Glyphs.start(), ev.doneGlyph(model(p)))
        p.home.editKey("PAD2"); settle()
        assertEquals(UiKit.backGlyph(), ev.doneGlyph(model(p)))
        // a short B: no ring, no "Discard" (only from RING_FROM_MS); it is Done on its release
        p.home.editB(true); settle(5)
        assertNull("80 ms: nothing shows", p.home.editHoldK())
        settle(5)
        assertNotNull("160 ms: the ring starts", p.home.editHoldK())
        p.home.editB(false); settle()
        assertFalse(p.home.editing)
        // the ribbon's touch boxes: 48 px; a down 12 px under the ribbon over Done is Done's, over the rooms the rooms'
        assertTrue(p.home.editOpen(EditStop.CHAT)); settle()
        val r = ev.ribbon(W.toFloat())
        assertTrue(r.doneHit.height() >= HomeEditView.TOUCH_MIN && r.doneHit.width() >= HomeEditView.TOUCH_MIN)
        assertTrue(r.plusHit.height() >= HomeEditView.TOUCH_MIN && r.plusHit.width() >= HomeEditView.TOUCH_MIN)
        assertFalse(ev.ribbonTouch(400f, Strip.TRACKS + 12f, MotionEvent.ACTION_DOWN))
        assertTrue(ev.ribbonTouch(r.done.centerX(), Strip.TRACKS + 12f, MotionEvent.ACTION_DOWN)); settle(3)
        ev.ribbonTouch(r.done.centerX(), Strip.TRACKS + 12f, MotionEvent.ACTION_UP); settle()
        assertFalse("a tap just under Done: Done", p.home.editing)
        assertTrue(p.home.editOpen(EditStop.CHAT)); settle()
        p.home.editAct { it.remove(EditStop.CAST) }; settle()
        assertTrue(model(p).plusShown)
        assertTrue(ev.ribbonTouch(r.plus.centerX(), Strip.TRACKS + 12f, MotionEvent.ACTION_DOWN))
        ev.ribbonTouch(r.plus.centerX(), Strip.TRACKS + 12f, MotionEvent.ACTION_UP); settle()
        assertEquals("a tap just under \"+\": its tray", HomeEditModel.Sheet.ADD, model(p).sheet)
        p.home.editKey("PAD2"); settle()
        // the controller's Discard with no held B: Right past the bar's end is Done, X there Discards
        p.home.editKey("PADBACK"); settle()
        assertTrue(HomeSettings.layout.mirrored)
        p.home.editAct { it.startOn(EditStop.ROOMS, false) }; settle()
        repeat(RoomBar.DEFAULT.size + 2) { if (model(p).cursor != EditStop.DONE) p.home.editKey("PADDRIGHT") }
        assertEquals(EditStop.DONE, model(p).cursor)
        assertEquals(listOf(UiKit.xGlyph() to "Discard"), ev.hintsFor(model(p)))
        shot4(p, "p4_20_done_stop")
        p.home.editKey("PAD3"); settle()
        assertFalse("X on Done: discarded", p.home.editing)
        assertEquals(classic, HomeSettings.layout)
    }

    /** 1518 (F3): the ribbon's slop (the rooms' row's top 14 px, so "+" and Done meet 48 px) never takes a down a room's "-" badge
     *  claims: a hide tap on the end rooms' badges hides the room - never Done (the right end) nor the Add tray (the left end) */
    @Test fun theRibbonsSlopLeavesTheBadgesTheirTaps() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT), roomBar = true)
        p.home.forceLive = true
        drawStrip(p)
        assertTrue(p.home.editOpen(EditStop.CHAT)); settle()
        p.home.editAct { it.remove(EditStop.CAST) }; settle(); drawStrip(p)        // "+" on the ribbon too
        assertTrue(model(p).plusShown)
        val ev = p.home.editChrome
        val r = ev.ribbon(W.toFloat())
        val y = Strip.TRACKS + 4f                                                // inside the slop, inside the badges' boxes
        var checked = 0
        for ((what, hit) in listOf("Done" to r.doneHit, "the Add tray's +" to r.plusHit)) {
            val room = p.strip.order.firstOrNull { RoomBar.canHide(it.name) && it.name !in model(p).draft.hidden &&
                p.strip.badgeBox(it)?.let { b -> b.top <= y && b.bottom >= y && b.left < hit.right && b.right > hit.left } == true } ?: continue
            val b = p.strip.badgeBox(room)!!
            val x = (maxOf(b.left, hit.left) + minOf(b.right, hit.right)) / 2f
            assertTrue("$what's hit covers the point", hit.contains(x, y))
            assertTrue(p.strip.slopClaimed(x, y))
            stripTouch(p, MotionEvent.ACTION_DOWN, x, y); stripTouch(p, MotionEvent.ACTION_UP, x, y); settle(); drawStrip(p)
            assertTrue("$what: still editing", p.home.editing)
            assertNull("$what: no tray", model(p).sheet)
            assertTrue("$what: ${room.name} hidden by its badge", room.name in model(p).draft.hidden)
            checked++
        }
        assertTrue("an end room's badge under the ribbon's slop", checked > 0)
        // the slop over Done with no badge there is still Done's (the ribbon's own top: always the ribbon's)
        stripTouch(p, MotionEvent.ACTION_DOWN, r.done.centerX(), r.done.centerY()); stripTouch(p, MotionEvent.ACTION_UP, r.done.centerX(), r.done.centerY())
        settle()
        assertFalse("a tap on Done: Done", p.home.editing)
    }

    /** 1518 (F4 / F5): a finger held on Done when a fight parks edit mode lets go (the first ribbon tap after the fight is a tap, not
     *  swallowed); a finger slid off Done by more than the tap slop lets the hold go - no ring, no Discard at 600 ms, its UP nothing */
    @Test fun doneHeldThroughAFightOrSlidOffLetsGo() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT), roomBar = true)
        val home = p.home
        home.forceLive = true
        drawStrip(p)
        assertTrue(home.editOpen(EditStop.CHAT)); settle()
        home.editAct { it.remove(EditStop.CAST) }; settle(); drawStrip(p)
        val ev = home.editChrome
        val r = ev.ribbon(W.toFloat())
        val m = model(p)
        for (upWhileParked in listOf(true, false)) {
            // F4: the finger on Done, then a fight
            assertTrue(stripTouch(p, MotionEvent.ACTION_DOWN, r.done.centerX(), r.done.centerY()))
            assertTrue(ev.doneDown && p.strip.ribbonDown)
            home.onFull(full(true), true); home.onQuests(quests(true), true); home.tick(); settle(); drawStrip(p)
            assertTrue(m.parked)
            assertFalse("parked: Done's down let go", ev.doneDown)
            assertFalse("the chrome gone: the Strip keeps no ribbon finger", p.strip.ribbonDown)
            assertNull(home.editHoldK())
            if (upWhileParked) stripTouch(p, MotionEvent.ACTION_UP, r.done.centerX(), r.done.centerY())
            home.onFull(full(false), true); home.onQuests(quests(false), true)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(25)); home.tick(); settle(); drawStrip(p)
            assertFalse(m.parked)
            assertTrue("no Discard from the held finger", home.editing)
            // the first ribbon tap after the fight: "+" opens its tray (never swallowed)
            stripTouch(p, MotionEvent.ACTION_DOWN, r.plus.centerX(), r.plus.centerY()); stripTouch(p, MotionEvent.ACTION_UP, r.plus.centerX(), r.plus.centerY())
            settle()
            assertEquals("up while parked $upWhileParked: the first tap is a tap", HomeEditModel.Sheet.ADD, m.sheet)
            home.editKey("PAD2"); settle()
            assertNull(m.sheet)
        }
        // F5: down on Done, the ring starts; the finger slides off: the hold goes, no Discard at 600 ms, the UP does nothing
        assertTrue(stripTouch(p, MotionEvent.ACTION_DOWN, r.done.centerX(), r.done.centerY())); settle(12)
        assertNotNull("the ring", home.editHoldK())
        stripTouch(p, MotionEvent.ACTION_MOVE, r.done.centerX() + 6f, r.done.centerY()); settle(1)
        assertNotNull("within the tap slop: the hold goes on", home.editHoldK())
        stripTouch(p, MotionEvent.ACTION_MOVE, r.doneHit.left - HomeEditView.TAP_SLOP - 30f, r.done.centerY()); settle(1)
        assertNull("slid off: no ring", home.editHoldK())
        assertFalse(ev.doneDown)
        settle(50)
        assertTrue("no Discard at ${HomeEditModel.DISCARD_HOLD_MS} ms", home.editing)
        stripTouch(p, MotionEvent.ACTION_MOVE, r.done.centerX(), r.done.centerY())
        stripTouch(p, MotionEvent.ACTION_UP, r.done.centerX(), r.done.centerY()); settle()
        assertTrue("its UP does nothing (back over Done too)", home.editing)
        assertEquals(HomeLayout.CastDock.OFF, HomeSettings.layout.cast)
        // a hold that stays on Done (a small slide): Discard as before
        assertTrue(stripTouch(p, MotionEvent.ACTION_DOWN, r.done.centerX(), r.done.centerY())); settle(5)
        stripTouch(p, MotionEvent.ACTION_MOVE, r.done.centerX() - 8f, r.done.centerY() + 4f)
        settle(45)
        assertFalse("held on Done: discarded", home.editing)
        stripTouch(p, MotionEvent.ACTION_UP, r.done.centerX() - 8f, r.done.centerY() + 4f); settle()
        assertEquals(HomeLayout.CastDock.UNDER_CHAT, HomeSettings.layout.cast)
        // the last check: a finger that RESTS on Done past its Discard (still, no MOVE) while the chrome fades away; its UP never
        // reaches the ribbon. The next session's first ribbon tap is still a tap (editEnd lets the finger go)
        assertTrue(home.editOpen(EditStop.CHAT)); settle()
        home.editAct { it.remove(EditStop.CAST) }; settle(); drawStrip(p)
        val r2 = home.editChrome.ribbon(W.toFloat())
        assertTrue(stripTouch(p, MotionEvent.ACTION_DOWN, r2.done.centerX(), r2.done.centerY()))
        settle(60); drawStrip(p)
        assertFalse("held still on Done: discarded", home.editing)
        assertFalse("edit mode ended: Done's down let go", home.editChrome.doneDown)
        stripTouch(p, MotionEvent.ACTION_UP, r2.done.centerX(), r2.done.centerY()); settle()
        assertTrue(home.editOpen(EditStop.CHAT)); settle()
        home.editAct { it.remove(EditStop.CAST) }; settle(); drawStrip(p)
        val r3 = home.editChrome.ribbon(W.toFloat())
        stripTouch(p, MotionEvent.ACTION_DOWN, r3.plus.centerX(), r3.plus.centerY()); stripTouch(p, MotionEvent.ACTION_UP, r3.plus.centerX(), r3.plus.centerY())
        settle()
        assertEquals("the next session's first tap is a tap", HomeEditModel.Sheet.ADD, model(p).sheet)
        home.editKey("PAD2"); settle()
    }

    /** the review: in Spanish and French the ribbon's hints never run under the Home disc (the least needed pairs go first) */
    @Test fun theRibbonsHintsStayClearOfTheDiscInSpanishAndFrench() {
        try {
            for (l in listOf("en", "es", "fr")) {
                lang(l)
                val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT), roomBar = true)
                HomeLayoutStore.character("Korvash-Realm")
                p.home.onPadMap(PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m183","m":{"page":1,"sets":[],"dpad":{"on":true,"want":true,
                    "cols":{"on":1,"off":2},"d":{"1":"chat","2":"radial","3":"map","4":"home"},"who":{"n":"Korvash","r":"Realm","rs":"guid"}}}}"""))!!)
                settle()
                drawStrip(p)
                assertTrue(p.home.editOpen(EditStop.CHAT)); settle()
                val ev = p.home.editChrome
                val g1 = W / 2f + 64f + 6f + 2f
                val states = listOf<Pair<String, (HomeEditModel) -> List<HomeEditModel.Fx>>>(
                    "top" to { m -> m.startOn(EditStop.CHAT, false) },
                    "top map" to { m -> m.startOn(EditStop.MAP, false) },
                    "top card" to { m -> m.startOn(EditStop.QUEST, false) },
                    "top bar" to { m -> m.startOn(EditStop.CAST, false) },
                    "top room" to { m -> m.startOn(EditStop.ROOMS, false) },
                    "the D-pad's pencil" to { m -> m.startOn(EditStop.PAD_PEN, false) },
                    "the Look chip" to { m -> m.startOn(EditStop.LOOK, false) },
                    "an extra" to { m -> HomeSettings.clockGold = true; m.startOn(EditStop.EXTRAS, false) },
                    "Fight" to { m -> m.startOn(EditStop.FIGHT, false) },
                    "Done" to { m -> m.startOn(EditStop.DONE, false) },
                    "the divider" to { m -> m.startOn(EditStop.DIVIDER, false) },
                    "lifted chat" to { m -> m.startOn(EditStop.CHAT, true) },
                    "lifted room" to { m -> m.startOn(EditStop.ROOMS, true) },
                    "menu" to { m -> m.openMenu(EditStop.CHAT); emptyList() })
                for ((n, f) in states) {
                    p.home.editAct { it.drop(); it.closeMenu(); f(it) }; settle()
                    val c = Canvas(Bitmap.createBitmap(W, Strip.TRACKS, Bitmap.Config.ARGB_8888))
                    ev.hintsLeftDrawn = Float.NaN
                    ev.drawRibbon(c, W.toFloat(), 0f, W / 2f - 72f, g1)
                    assertTrue("$l $n: hints drawn", !ev.hintsLeftDrawn.isNaN())
                    assertTrue("$l $n: clear of the disc (${ev.hintsLeftDrawn} vs $g1)", ev.hintsLeftDrawn >= g1)
                }
                p.home.editCancel()
            }
        } finally { Tr.setChoice(app, "auto") }
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
        assertEquals("A / a finger on the Edit Home tile = edit mode", 1, asked)
        assertNull("no band opened", part.openTile)
        out?.let { d2 ->
            val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(Theme.ink); r.draw(c)
            File(d2, "00_settings_home_layout_tile.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    // ---------------------------------------------------------------- PHASE 4: the room bar (1518 build 2: each room an app - reorder, hide, "+")

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
        val classicSlots = p.strip.slotCentres()
        val classicIcon = p.strip.slotBox(0)!!.width()
        shot4(p, "p4_00_home_classic")
        // edit mode: each room an app (its own wiggle, a "-" badge; Settings none), no frame / tag / gold edge round the bar
        assertTrue(home.editOpen(EditStop.MAP)); settle()
        assertNull("nothing hidden: no \"+\" spot", p.strip.plusBox())
        assertTrue(p.strip.badgeBox(Room.MAP) != null)
        assertNull("Settings has no badge", p.strip.badgeBox(Room.SETTINGS)?.let { b -> p.strip.badgeAt(b.centerX(), b.centerY()) })
        shot4(p, "p4_01a_edit_rooms_as_apps")
        home.editKey("PADDDOWN"); settle()
        val m = model(p)
        assertEquals(EditStop.ROOMS, m.cursor)
        shot4(p, "p4_01_cursor_on_the_first_room")
        repeat(7) { home.editKey("PADDRIGHT") }
        assertEquals("BAGS", m.barRoom)
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
        assertEquals("carried: nothing written before the drop", RoomBar.DEFAULT, HomeSettings.layout.rooms)
        // A drops (saved now); X hides it: the next room slides into its place, "+" stands at the bar's end - with one room hidden the
        // twelve cells are exactly today's (the rooms' centres and size unchanged, "+" in Settings' old cell)
        home.editKey("PAD1"); settle()
        assertEquals("the dropped room saved", "BAGS", HomeSettings.layout.rooms[5])
        home.editKey("PAD3"); settle(); drawStrip(p)
        assertEquals(listOf("BAGS"), m.draft.hidden)
        assertEquals("saved as it is made", listOf("BAGS"), HomeSettings.layout.hidden)
        assertFalse("BAGS" in names(p.strip))
        assertEquals("GROUPFINDER", m.barRoom)
        val plus = p.strip.plusBox()!!
        assertEquals(classicSlots.last(), plus.centerX(), 0.5f)
        assertEquals(classicSlots.take(11).map { Math.round(it) }, p.strip.slotCentres().map { Math.round(it) })
        assertEquals(classicIcon, p.strip.slotBox(0)!!.width(), 0.01f)
        assertNull("no menu (a room is an app)", m.menu)
        shot4(p, "p4_06_bags_hidden_plus_at_the_end")
        // a finger on Quests' badge: hidden in one tap (no "Remove?")
        val qb = p.strip.badgeBox(Room.QUESTS)!!
        t0 = android.os.SystemClock.uptimeMillis()
        stripTouch(p, MotionEvent.ACTION_DOWN, qb.centerX(), qb.centerY()); stripTouch(p, MotionEvent.ACTION_UP, qb.centerX(), qb.centerY())
        settle(); drawStrip(p)
        assertEquals(listOf("QUESTS", "BAGS"), m.draft.hidden)
        assertTrue("no room opened by the edit touches", p.rooms.isEmpty())
        shot4(p, "p4_07_badge_tap_hides_quests")
        // Settings never leaves the bar: X there is one red pulse
        repeat(20) { if (!m.barOnPlus) home.editKey("PADDRIGHT") }           // (Right past "+" is the ribbon's Done: the review)
        assertTrue(m.barOnPlus); assertEquals(EditStop.ROOMS, m.cursor)
        home.editKey("PADDLEFT")
        assertEquals("SETTINGS", m.barRoom)
        home.editKey("PAD3"); settle(2)
        assertEquals(listOf("QUESTS", "BAGS"), m.draft.hidden)
        assertEquals(EditStop.ROOMS, (field(home, "editFlash") as Pair<*, *>).first)
        shot4(p, "p4_08_settings_refuses", settleFirst = false)
        // "+": A opens the small tray of the hidden rooms (over Home's foot, rising from "+", clear of the screen's edges)
        home.editKey("PADDRIGHT"); home.editKey("PAD1"); settle()
        assertEquals(HomeEditModel.Sheet.ROOMS, m.sheet)
        assertEquals(listOf("QUESTS", "BAGS"), m.hiddenRooms)
        val tray = home.editChrome.roomTrayBox()!!
        assertTrue("$tray", tray.left >= 16f && tray.right <= W - 16f && tray.top >= 0f && tray.bottom <= HV.toFloat())
        assertTrue("right-aligned to the \"+\" spot", tray.right >= plus.centerX())
        shot4(p, "p4_09_plus_tray")
        // Down, A: Bags back where it was (before Group Finder), the cursor on it; the tray closes
        home.editKey("PADDDOWN"); home.editKey("PAD1"); settle(); drawStrip(p)
        assertNull(m.sheet)
        assertEquals("BAGS", m.barRoom)
        assertEquals(names(p.strip).indexOf("GROUPFINDER") - 1, names(p.strip).indexOf("BAGS"))
        // a finger on "+" and on the tray's row: Quests back after Map
        val pb = p.strip.plusBox()!!
        stripTouch(p, MotionEvent.ACTION_DOWN, pb.centerX(), pb.centerY()); stripTouch(p, MotionEvent.ACTION_UP, pb.centerX(), pb.centerY())
        settle()
        assertEquals(HomeEditModel.Sheet.ROOMS, m.sheet)
        lay(home, W, HV); home.draw(Canvas(Bitmap.createBitmap(W, HV, Bitmap.Config.ARGB_8888)))   // the tray's rows as drawn
        val t2 = home.editChrome.roomTrayBox()!!
        val ry = t2.top + HomeEditView.TRAY_HEAD + 20f
        for (a in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(t0, android.os.SystemClock.uptimeMillis(), a, t2.centerX(), ry, 0); home.editChrome.touch(e); e.recycle()
        }
        settle(); drawStrip(p)
        assertTrue(m.draft.hidden.isEmpty())
        assertEquals(1, names(p.strip).indexOf("QUESTS"))
        assertNull("nothing hidden: \"+\" gone, the bar today's twelve", p.strip.plusBox())
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
        shot4(p, "p4_10_finger_carries_settings", settleFirst = false)
        val x0 = p.strip.slotBox(0)!!.centerX()
        stripTouch(p, MotionEvent.ACTION_MOVE, x0 + 4f, sy); settle(1)
        stripTouch(p, MotionEvent.ACTION_UP, x0 + 4f, sy)
        settle()
        assertEquals("SETTINGS", names(p.strip)[0])
        assertTrue("no room opened by the edit touches", p.rooms.isEmpty())
        shot4(p, "p4_11_settings_far_left")
        // a tap on a room: the cursor there, nothing opens (no menu)
        val s3 = p.strip.slotBox(3)!!
        stripTouch(p, MotionEvent.ACTION_DOWN, s3.centerX(), s3.centerY()); stripTouch(p, MotionEvent.ACTION_UP, s3.centerX(), s3.centerY())
        assertEquals(3, m.barSel); assertNull(m.menu); assertNull(m.sheet)
        // Macros hidden, the tray open, then A FIGHT: the tray closes, no badge, no "+" (the rooms open as always); its end: back
        home.editAct { it.hideRoom("MACROS") }; home.editAct { it.openRoomTray() }; settle()
        assertEquals(HomeEditModel.Sheet.ROOMS, m.sheet)
        home.onFull(full(true), true); home.onQuests(quests(true), true); home.tick(); settle(); drawStrip(p)
        assertTrue(m.parked)
        assertNull(m.sheet)
        assertNull("parked: no \"+\" spot", p.strip.plusBox())
        assertFalse("MACROS" in names(p.strip))
        shot4(p, "p4_12_fight_parked_no_badges")
        home.onFull(full(false), true); home.onQuests(quests(false), true)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(25)); home.tick(); settle(); drawStrip(p)
        assertFalse(m.parked)
        assertTrue("back: \"+\" again", p.strip.plusBox() != null)
        // Start: Done. The record keeps the order and the hidden room (per character); out of edit mode the bar is 11 rooms, 6 | 5, one
        // picture size, no "+"
        home.editKey("PADFORWARD"); settle(); drawStrip(p)
        assertFalse(home.editing)
        assertEquals("SETTINGS", HomeSettings.layout.rooms[0])
        assertEquals(listOf("MACROS"), HomeSettings.layout.hidden)
        assertNull(p.strip.plusBox())
        assertEquals(RoomBar.DEFAULT.size - 1, names(p.strip).size)
        assertTrue((0 until 11).all { Math.abs(p.strip.slotBox(it)!!.width() - p.strip.slotBox(0)!!.width()) < 0.01f })
        assertTrue("6 left of the disc", p.strip.slotBox(5)!!.right < W / 2f && p.strip.slotBox(6)!!.left > W / 2f)
        // the XP bar is Settings > Bottom screen's now (Home's tiles: Show / Hide, this character's record)
        BottomTiles.press("", BottomTiles.XP, 1, BottomTiles.Env(null, null)) { }
        assertFalse(HomeSettings.layout.xpBar)
        // the host's trackSync (Home on screen, the record's track hidden): the track slides away, every part glides into its 34 px
        p.strip.trackShown(false, Motion.MODULE_CLOSE)
        home.trackInset(0, animate = true, ms = Motion.MODULE_CLOSE)
        glideAt(home, 0.5f)
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        shot4(p, "p4_13_xp_track_going", settleFirst = false)
        glideAt(home, 1f); settle()
        assertEquals("Home's frame takes the track's 34 px", HV.toFloat(), boxes(home).getValue("MAP").bottom + UiKit.MODULE_GAP, 1f)
        assertEquals(Strip.TRACKS.toFloat(), p.strip.trackGone, 0.01f)
        shot4(p, "p4_14_saved_reordered_hidden_xp_hidden")
        // a touch in the track's place is Home's (the strip lets it through), the disc keeps its own
        assertFalse(stripTouch(p, MotionEvent.ACTION_DOWN, 200f, 10f))
        assertTrue(stripTouch(p, MotionEvent.ACTION_DOWN, W / 2f, 40f))
        // the bar cursor (HomeBarSelect) walks the bar as drawn: Settings first now, Macros nowhere
        val sel = HomeBarSelect(app, p.strip, { null }) { _, _ -> }
        @Suppress("UNCHECKED_CAST")
        val order = HomeBarSelect::class.java.getDeclaredMethod("order").apply { isAccessible = true }.invoke(sel) as List<Room>
        assertEquals(Room.SETTINGS, order.first())
        assertFalse(Room.MACROS in order)
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
        assertEquals("saved at the drop", "MAP", HomeSettings.layout.rooms[2])
        // two rooms hidden (X, X): saved as they go
        p.home.editKey("PAD3"); p.home.editKey("PAD3"); settle()
        assertEquals(listOf("MAP", "TALENTS"), HomeSettings.layout.hidden)
        drawStrip(p)
        assertTrue(p.strip.plusBox() != null)
        // a held B: Discard - the order and the hidden rooms glide back, the record as it was
        p.home.editB(true); settle(45); p.home.editB(false); settle(); drawStrip(p)
        assertFalse(p.home.editing)
        assertEquals(RoomBar.DEFAULT, names(p.strip))
        assertEquals(RoomBar.DEFAULT, HomeSettings.layout.rooms)
        assertTrue(HomeSettings.layout.hidden.isEmpty())
        assertNull(p.strip.liftRoom)
        assertNull(p.strip.plusBox())
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

    // ---------------------------------------------------------------- movable Home fix round 1 (the review's findings)

    /** the prefs the layout store writes (HomeSettings' file, or another test's stand-in kept by HomeSettings.init) */
    private fun storePrefs(): android.content.SharedPreferences =
        HomeLayoutStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }.get(null) as android.content.SharedPreferences
    private fun rec(key: String): String? = storePrefs().getString(HomeLayoutStore.KEY_PREFIX + key, null)
    private fun editViewOf(h: HomeView): HomeEditView = (field(h, "editView\$delegate") as Lazy<*>).value as HomeEditView

    /** P2: edit mode open (or parked by a room) on Korvash, the game switches to Thrandil: it ends, nothing more written - Korvash's
     *  record keeps what was saved as it was made, Thrandil's record untouched */
    @Test fun anotherCharacterEndsEditMode() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT))
        HomeLayoutStore.character("Thrandil-Realm"); HomeLayoutStore.set(HomeLayout(card = HomeLayout.CardDock.OFF, cast = HomeLayout.CastDock.ACROSS))
        HomeLayoutStore.character("Korvash-Realm"); HomeLayoutStore.set(HomeLayout(chatSide = HomeLayout.Side.RIGHT, cast = HomeLayout.CastDock.UNDER_CHAT))
        settle()
        val thrandil = rec("Thrandil-Realm")
        val korvash = rec("Korvash-Realm")
        assertTrue(p.home.editOpen(EditStop.CHAT)); settle()
        p.home.editKey("PADBACK"); settle()
        assertTrue(model(p).changed)
        val swapped = rec("Korvash-Realm")
        assertEquals("1518: Korvash's swap saved as it was made", HomeLayout.Side.LEFT, HomeLayoutJson.parse(swapped).chatSide)
        HomeLayoutStore.character("Thrandil-Realm"); settle()
        assertNull("another character: edit mode ended", p.home.editModel())
        assertFalse(p.home.editing)
        assertEquals("Thrandil's record never written", thrandil, rec("Thrandil-Realm"))
        assertEquals("Korvash's as it was saved", swapped, rec("Korvash-Realm"))
        assertTrue(korvash != swapped)
        // parked by a room, the switch meanwhile, then Home again: ended, nothing resumed, nothing written
        HomeLayoutStore.character("Korvash-Realm"); settle()
        assertTrue(p.home.editOpen(EditStop.CHAT)); settle()
        p.home.editKey("PADBACK"); settle()
        p.home.giveBack()
        assertTrue(model(p).parked)
        val again = rec("Korvash-Realm")
        HomeLayoutStore.character("Thrandil-Realm"); settle()
        p.home.borrow(); lay(p.home); settle()
        assertNull(p.home.editModel())
        assertEquals(thrandil, rec("Thrandil-Realm"))
        assertEquals("Korvash's: the parked session's saved swap, nothing after", again, rec("Korvash-Realm"))
        assertEquals(HomeLayout.Side.RIGHT, HomeLayoutJson.parse(again).chatSide)
    }

    /** P1: Korvash named before his realm (Home on the default), edit mode changes only the width (held: no key to write it under),
     *  the key arrives: only that change is laid on Korvash's own record (his chat side and rest kept), and edit mode goes on there */
    @Test fun theLateKeyKeepsTheCharactersOwnRecord() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT))
        HomeLayoutStore.character("Korvash-Realm")
        HomeLayoutStore.set(HomeLayout(chatSide = HomeLayout.Side.RIGHT, rest = HomeRest.ROW, cast = HomeLayout.CastDock.UNDER_CHAT))
        HomeLayoutStore.character(null, "Korvash"); settle()
        assertTrue(HomeLayoutStore.pending)
        assertTrue(p.home.editOpen(EditStop.CHAT)); settle()
        p.home.editKey("PAD4"); settle()
        val share = model(p).draft.chatShare
        assertNotEquals(HomeLayout.Share.HALF, share)
        assertTrue("held, never under a partial key", storePrefs().all.keys.none { it.startsWith(HomeLayoutStore.KEY_PREFIX + "Korvash") && it != HomeLayoutStore.KEY_PREFIX + "Korvash-Realm" })
        HomeLayoutStore.character("Korvash-Realm"); settle()
        assertNotNull("the same character's key: edit mode stays", p.home.editModel())
        assertEquals("rebased on his real record", HomeLayout.Side.RIGHT, model(p).draft.chatSide)
        p.home.editDone(); settle()
        val k = HomeLayoutJson.parse(rec("Korvash-Realm"))
        assertEquals("the session's change", share, k.chatShare)
        assertEquals("his own chat side kept", HomeLayout.Side.RIGHT, k.chatSide)
        assertEquals("his own rest kept", HomeRest.ROW, k.rest)
    }

    /** 1518 (F1): the same late key, then Discard: the character's own record comes back (his width, not the pending default's) - after
     *  the rebase (settled when the key came) and before it (a widget lifted when the key came: the rebase waits for the drop) */
    @Test fun aLateKeysDiscardPutsBackTheCharactersOwnRecord() {
        for (liftedWhenKeyCame in listOf(false, true)) {
            val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT))
            HomeLayoutStore.character("Korvash-Realm")
            HomeLayoutStore.set(HomeLayout(chatSide = HomeLayout.Side.RIGHT, chatShare = HomeLayout.Share.THIRD, rest = HomeRest.ROW,
                cast = HomeLayout.CastDock.UNDER_CHAT))
            val own = rec("Korvash-Realm")
            HomeLayoutStore.character(null, "Korvash"); settle()
            assertTrue(p.home.editOpen(EditStop.CHAT)); settle()
            p.home.editKey("PAD4"); settle()                                         // the width: a field his record has its own value for
            assertNotEquals(HomeLayout.Share.THIRD, model(p).draft.chatShare)
            if (liftedWhenKeyCame) { p.home.editKey("PAD1"); settle(); assertEquals(EditStop.CHAT, model(p).lifted) }
            HomeLayoutStore.character("Korvash-Realm"); settle()
            assertNotNull(p.home.editModel())
            p.home.editAct { it.discard() }; settle()
            assertNull(p.home.editModel())
            assertEquals("lifted $liftedWhenKeyCame: his own record back", HomeLayoutJson.parse(own), HomeLayoutJson.parse(rec("Korvash-Realm")))
            assertEquals(HomeLayout.Share.THIRD, HomeSettings.layout.chatShare)
        }
        // the store's prefs outlive this test's app (HomeSettings keeps the first one's): no record of Korvash's for the next test
        HomeLayoutStore.character(null); storePrefs().edit().clear().commit(); HomeLayoutStore.resetDone()
    }

    /** 1518 (the checkers' F1-a / F1-b): the late key when nothing of the session was saved yet (Chat lifted and stepped: written at the
     *  drop) - dropped then Discard, Discard still lifted, a room parks it (its save) then Home again; and a change held, a room parks it,
     *  the key comes, a Settings reset (not Layouts) runs: every Discard puts his own record back, never the pending default's values */
    @Test fun aLateKeyWithNothingSavedYetDiscardsToTheCharactersOwnRecord() {
        for (how in listOf("drop", "lifted", "room", "held, room, reset")) {
            val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT))
            HomeLayoutStore.character("Korvash-Realm")
            HomeLayoutStore.set(HomeLayout(chatSide = HomeLayout.Side.RIGHT, chatShare = HomeLayout.Share.THIRD, rest = HomeRest.ROW,
                cast = HomeLayout.CastDock.UNDER_CHAT))
            val own = rec("Korvash-Realm")
            HomeLayoutStore.character(null, "Korvash"); settle()
            val pendingShare = HomeSettings.layout.chatShare
            assertTrue(p.home.editOpen(EditStop.CHAT)); settle()
            if (how == "held, room, reset") {
                p.home.editKey("PAD4"); settle()                                     // saved at once: held for the pending name
                p.home.giveBack(); assertTrue(model(p).parked)                       // the Settings room
                HomeLayoutStore.character("Korvash-Realm"); settle()                 // the key comes while parked
                HomeSettings.resetDone()                                             // a Settings reset (not Layouts): edit mode waits on
                p.home.borrow(); lay(p.home); settle()
            } else {
                p.home.editKey("PAD1"); settle(); assertEquals(EditStop.CHAT, model(p).lifted)
                p.home.editKey("PAD4"); settle()                                     // the width, lifted: written at the drop
                assertNotEquals(pendingShare, model(p).draft.chatShare)
                HomeLayoutStore.character("Korvash-Realm"); settle()                 // the key comes, Chat still lifted
                when (how) {
                    "drop" -> { p.home.editKey("PAD1"); settle(); assertNull(model(p).lifted) }
                    "room" -> { p.home.giveBack(); assertTrue(model(p).parked); p.home.borrow(); lay(p.home); settle() }
                }
            }
            assertNotNull(how, p.home.editModel())
            p.home.editAct { it.discard() }; settle()
            assertNull(how, p.home.editModel())
            assertEquals("$how: his own record back", HomeLayoutJson.parse(own), HomeLayoutJson.parse(rec("Korvash-Realm")))
            assertEquals(how, HomeLayout.Share.THIRD, HomeSettings.layout.chatShare)
            HomeLayoutStore.character(null); storePrefs().edit().clear().commit(); HomeLayoutStore.resetDone()
        }
    }

    /** a Layouts reset while edit mode is parked by the Settings room (the host's resetApply: editCancel first): nothing written after it */
    @Test fun aLayoutsResetLeavesNoDraftToSave() {
        val p = page(HomeLayout(chatSide = HomeLayout.Side.RIGHT, cast = HomeLayout.CastDock.UNDER_CHAT))
        assertTrue(p.home.editOpen(EditStop.CHAT)); settle()
        p.home.editKey("PAD4"); settle()
        assertTrue(model(p).changed)
        p.home.giveBack()                                                     // Settings opens: the draft parked
        assertTrue(model(p).parked)
        p.home.editCancel()                                                   // resetApply, LAYOUTS
        storePrefs().edit().remove(HomeLayoutStore.KEY_DEFAULT).commit()
        HomeSettings.resetDone()
        p.home.borrow(); lay(p.home); settle()
        assertNull("no session to resume", p.home.editModel())
        assertEquals("Classic again", HomeLayout.Side.LEFT, HomeSettings.layout.chatSide)
        p.home.editDone(); p.home.editKey("PADFORWARD"); settle()
        assertEquals("nothing put the old record back", HomeLayout.Side.LEFT, HomeSettings.layout.chatSide)
    }

    /** the hold's timer never opens edit mode on a Home that was left (another room) or covered (a host overlay) meanwhile */
    @Test fun aHoldNeverOpensOnALeftOrCoveredHome() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT))
        val home = p.home
        home.forceLive = true
        val m = boxes(home).getValue("MAP")
        val x = m.centerX(); val y = m.top + 420f
        t0 = android.os.SystemClock.uptimeMillis()
        touch(p, MotionEvent.ACTION_DOWN, x, y); settle(10)
        home.giveBack()                                                       // a room opens, the thumb still resting
        settle(30)
        assertNull("another room: no edit mode on the hidden Home", home.editModel())
        touch(p, MotionEvent.ACTION_UP, x, y)
        home.borrow(); lay(home); settle()
        var cover = false
        home.overlayOpen = { cover }
        t0 = android.os.SystemClock.uptimeMillis()
        touch(p, MotionEvent.ACTION_DOWN, x, y); settle(10)
        cover = true                                                          // the pause / a radial / the bar select over Home
        settle(30)
        assertNull("covered: no edit mode", home.editModel())
        touch(p, MotionEvent.ACTION_UP, x, y); settle()
        cover = false
        t0 = android.os.SystemClock.uptimeMillis()
        touch(p, MotionEvent.ACTION_DOWN, x, y); settle(30)
        assertTrue("nothing in the way: the hold opens it as always", home.editing)
        touch(p, MotionEvent.ACTION_UP, x, y); settle()
    }

    /** a fight parks edit mode: the class wheel's / the LB picture's slide offsets on the columns are never written over by a layout */
    @Test fun aParkedSessionLeavesTheSlidesTheirOffsets() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT))
        val home = p.home
        home.forceLive = true
        assertTrue(home.editOpen(EditStop.CHAT)); settle()
        home.editKey("PADBACK"); settle()
        home.onFull(full(true), true); home.onQuests(quests(true), true); home.tick(); settle()
        assertTrue(model(p).parked)
        val chat = views(home).getValue("CHAT")
        chat.translationX = -120f; chat.translationZ = 7f                     // classLayerShow's slide on the column
        lay(home); settle(); lay(home)
        assertEquals(-120f, chat.translationX, 0.01f)
        assertEquals(7f, chat.translationZ, 0.01f)
    }

    /** the lifted chat lies over the map's own paint: its box is clipped out of the zone band / well edge / buttons ([HomeView.liftedBoxes]) */
    @Test fun aLiftedChatIsDrawnOverTheMapsPaint() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT))
        val home = p.home
        home.forceLive = true
        assertTrue(home.liftedBoxes().isEmpty())
        assertTrue(home.editOpen(EditStop.CHAT)); settle()
        assertTrue("selected, not lifted: nothing over the map", home.liftedBoxes().isEmpty())
        home.editKey("PAD1"); settle()
        val lb = home.liftedBoxes()
        val c = boxes(home).getValue("CHAT")
        assertEquals(1, lb.size)
        assertTrue("the chat's drawn box (1.03)", lb[0].contains(c.centerX(), c.centerY()) && lb[0].width() > c.width())
        home.editKey("PADRIGHT"); settle()
        assertTrue("stepped over the map: the box is where the chat is drawn", home.liftedBoxes().single().intersect(RectF(boxes(home).getValue("MAP"))))
        home.editKey("PAD1"); settle()
        assertTrue("dropped: none", home.liftedBoxes().isEmpty())
        home.editCancel(); settle()
        assertTrue(home.editOpen(EditStop.MAP)); home.editKey("PAD1"); settle()
        assertEquals(EditStop.MAP, model(p).lifted)
        assertTrue("the map lifted: its own paint follows it", home.liftedBoxes().isEmpty())
    }

    /** the Card / the bar Off from its own menu: the cursor goes on ("+"), the golden box always on something drawn */
    @Test fun offFromItsOwnMenuLeavesNoCursorOnNothing() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT))
        val home = p.home
        assertTrue(home.editOpen(EditStop.QUEST)); settle()
        home.editKey("PAD3"); settle()
        assertEquals(EditStop.QUEST, model(p).menu)
        // Place: On map -> Above chat -> Off
        home.editKey("PADDRIGHT"); home.editKey("PADDRIGHT"); settle()
        assertEquals(HomeLayout.CardDock.OFF, model(p).draft.card)
        assertNull(model(p).menu)
        assertEquals(EditStop.PLUS, model(p).cursor)
        val ev = editViewOf(home)
        assertTrue("its stop is drawn", EditStop.PLUS in ev.stops())
    }

    /** the pictures never lie: Hold to talk's tile arrow follows `d`; the Home layout tile's stand-in is drawn; narrow tags on the edge */
    @Test fun thePicturesFollowTheRecord() {
        val swapped = HoldTalk.DEFAULT_DIRS + mapOf(1 to HoldTalk.DEFAULT_DIRS.getValue(3), 3 to HoldTalk.DEFAULT_DIRS.getValue(1))
        assertEquals("gen_left", BottomPictures.talkGlyph(HoldTalk.DEFAULT_DIRS))
        assertEquals("gen_right", BottomPictures.talkGlyph(swapped))
        assertEquals("gen_left", BottomPictures.talkGlyph(null))
        // the Home layout tile with no game yet: its stand-in (never an empty well)
        val b = Bitmap.createBitmap(438, 300, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(android.graphics.Color.BLACK)
        SettingsPreview.draw(c, RectF(0f, 0f, 438f, 300f), "Home layout", SettingsPreview.State(), 0L, SettingsPreview.Glyph { _, _, _, _, _, _ -> })
        var lit = 0
        for (yy in 0 until 300 step 4) for (xx in 0 until 438 step 4) if (b.getPixel(xx, yy) != android.graphics.Color.BLACK) lit++
        assertTrue("the record's boxes drawn ($lit)", lit > 2000)
        out?.let { d -> File(d, "40_home_layout_tile_standin.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        // the tags: a third's column puts the chat's / the card's on the frame's top edge, a half keeps the top right corner
        val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT))
        val ev = editViewOf(p.home)
        assertEquals(HomeEditView.Corner.TOP_EDGE_RIGHT, ev.cornerOf(EditStop.CHAT, RectF(8f, 200f, 410f, 900f)))
        assertEquals(HomeEditView.Corner.TOP_EDGE_RIGHT, ev.cornerOf(EditStop.QUEST, RectF(830f, 260f, 1232f, 400f)))
        assertEquals(HomeEditView.Corner.TOP_RIGHT, ev.cornerOf(EditStop.CHAT, RectF(8f, 200f, 616f, 900f)))
        assertEquals(HomeEditView.Corner.BOTTOM_LEFT, ev.cornerOf(EditStop.MAP, RectF(830f, 200f, 1232f, 900f)))
    }

    // ---------------------------------------------------------------- fix round 3

    /** finding 4: hold to talk's pop-up lies over the chat's and the map's tops; a finger resting there (a tab picked) never arms the hold */
    @Test fun aHoldNeverArmsUnderHoldToTalk() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT))
        val home = p.home
        home.forceLive = true
        val m = boxes(home).getValue("MAP")
        val x = m.centerX(); val y = m.top + 420f
        p.cast.talkPanel = CastView.TalkPanel(listOf(TalkTabs.Tab("say", "Say", android.graphics.Color.WHITE)), 0,
            CastView.TalkFace(CastView.TalkFace.Mode.READY, android.graphics.Color.WHITE), "", "")
        t0 = android.os.SystemClock.uptimeMillis()
        touch(p, MotionEvent.ACTION_DOWN, x, y); settle(40)
        assertNull("hold to talk up: no edit mode", home.editModel())
        touch(p, MotionEvent.ACTION_UP, x, y); settle()
        p.cast.talkPanel = null
        t0 = android.os.SystemClock.uptimeMillis()
        touch(p, MotionEvent.ACTION_DOWN, x, y); settle(40)
        assertTrue("the pop-up gone: the hold opens it as always", home.editing)
        touch(p, MotionEvent.ACTION_UP, x, y); settle()
    }

    /** finding 5: back from the Filters route (a room over a parked session), Home is laid with the DRAFT in the same call: the Chat
     *  room's closing glide (chatPaneBox, read right after borrow) lands on the draft's chat pane, not the saved one's */
    @Test fun backFromARoomThePaneIsTheDrafts() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT))
        val home = p.home
        home.forceLive = true
        val saved = home.chatPaneBox()!!
        assertTrue(home.editOpen(EditStop.CHAT)); settle()
        home.editKey("PADBACK"); settle(); lay(home); settle()                // Select: the chat on the right in the draft
        val draft = home.chatPaneBox()!!
        assertTrue("the draft moved the pane", draft.left > saved.left + 100f)
        home.giveBack()                                                       // Filters: the Chat room over Home, the session parked
        assertTrue(model(p).parked)
        home.borrow()                                                         // B: Home again (its resume posted)
        assertEquals("the glide's target is the draft's pane at once", draft, home.chatPaneBox())
        settle(); lay(home); settle()
        assertTrue(home.editing)
        assertEquals(draft, home.chatPaneBox())
        home.editAct { it.discard() }; settle(); lay(home); settle()
        assertEquals("Discard: the pane it opened with again", saved, home.chatPaneBox())
    }

    /** finding 7: Done keeps the D-pad pictures on the record's sides until the add-on's `d` says them (no slide back and forth); the
     *  resend given up = a cross-fade back to `d` */
    @Test fun doneKeepsTheToolbarPicturesUntilTheAddonSaysThem() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT))
        val home = p.home
        home.forceLive = true
        val cast = p.cast
        val swapped = HoldTalk.DEFAULT_DIRS + mapOf(1 to "map", 3 to "chat")
        cast.roomBar = home.roomBarFor(HoldTalk.DEFAULT_DIRS)
        assertTrue(home.editOpen(EditStop.CHAT)); settle()
        home.editKey("PADBACK"); settle()
        assertEquals("the draft's preview", swapped, cast.roomBar)
        cast.roomMotionK(android.os.SystemClock.elapsedRealtime() + 10_000L)  // the preview's slide done
        home.editDone(); settle(2)
        assertEquals("Done: still the record's sides", swapped, cast.roomBar)
        assertNull("nothing moved", cast.roomMotionK())
        // the add-on still answers with the old `d` (a padmap before the taps landed): the pictures hold
        cast.roomBar = home.roomBarFor(HoldTalk.DEFAULT_DIRS)
        assertEquals(swapped, cast.roomBar); assertNull(cast.roomMotionK())
        // the confirming `d`: the same pictures, nothing moves
        cast.roomBar = home.roomBarFor(swapped)
        assertEquals(swapped, cast.roomBar); assertNull(cast.roomMotionK())
        // a Done the add-on never confirms: the host gives up and the pictures cross-fade back to `d` (never a slide)
        assertTrue(home.editOpen(EditStop.CHAT)); settle()
        home.editKey("PADBACK"); settle()
        cast.roomMotionK(android.os.SystemClock.elapsedRealtime() + 10_000L)
        home.editDone(); settle(2)
        assertEquals(HoldTalk.DEFAULT_DIRS, cast.roomBar)
        home.roomBarHoldEnd()
        assertEquals(swapped, cast.roomBar)
        assertNotNull(cast.roomMotionK())
        assertFalse("a cross-fade", cast.roomMotionSwap)
    }

    /** finding 9: on a stepper row (the Map's Zoom out / in) the ribbon shows the D-pad's Change, never A's */
    @Test fun aStepperRowsHintIsTheDpad() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT))
        val home = p.home
        assertTrue(home.editOpen(EditStop.MAP)); settle()
        home.editKey("PAD3"); settle()
        assertEquals(EditStop.MAP, model(p).menu)
        val ev = editViewOf(home)
        assertEquals(listOf(HomeEditView.DPAD to "Change", UiKit.backGlyph() to "Back"), ev.hintsFor(model(p), MenuRow.Kind.STEP))
        assertEquals(UiKit.okGlyph() to "Change", ev.hintsFor(model(p), MenuRow.Kind.SEG).first())
    }

    /** the toolbar's Left / Right pictures: a swap slides them (no one-frame jump), another change cross-fades in 120 ms */
    @Test fun theToolbarPicturesSlideOnASwap() {
        val cast = CastView(app) { }
        cast.layout = CastView.Layout.DIAMONDS                             // Home's band (the room's FULL bar has no toolbar row)
        cast.roomBarOn = true
        cast.roomBar = HoldTalk.DEFAULT_DIRS
        assertNull(cast.roomMotionK())
        cast.roomBar = HoldTalk.DEFAULT_DIRS + mapOf(1 to "map", 3 to "chat")
        assertNotNull("a swap runs a motion", cast.roomMotionK())
        assertTrue(cast.roomMotionSwap)
        val at = android.os.SystemClock.elapsedRealtime()
        assertTrue(cast.roomMotionK(at + Motion.MODULE_OPEN / 2)!! in 0.05f..0.95f)
        assertNull("done after the glide", cast.roomMotionK(at + Motion.MODULE_OPEN + 20))
        cast.roomBar = HoldTalk.DEFAULT_DIRS + mapOf(1 to "map", 3 to "home")
        assertNotNull(cast.roomMotionK())
        assertFalse("not a swap: a cross-fade", cast.roomMotionSwap)
        assertNull("120 ms", cast.roomMotionK(android.os.SystemClock.elapsedRealtime() + CastView.ROOM_FADE_MS + 20))
        cast.roomBar = cast.roomBar!! + mapOf(2 to "open")
        assertNull("Up / Down only: nothing", cast.roomMotionK())
    }

    /** the settings stage fed a game (PreviewStage: the Settings tiles' live pictures) */
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
