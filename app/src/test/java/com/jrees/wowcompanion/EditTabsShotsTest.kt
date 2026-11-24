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
 * EDIT HOME'S THREE TABS ON THE REAL HOME (the user, 2026-11-24: "tabs to the top of edit mode for out of combat, in combat and party
 * mode ... custom layouts for each mode ... in party and combat mode I DO want an option to gro those options to fill the space of the
 * bar better ... a setting in the combat bar area to toggle top screen or bottom screen or no grow"): the tab row over the zoomed page
 * (drawn by Home itself), LB / RB and a finger on a tab, each tab's page and look, the fight pencil's Combat bars bubble, the bar menu's
 * Slots, Grow on / off, and at runtime the In combat layout on our takeover (B on Home: Out of combat's again), Party mode's whenever
 * Party mode is on, its own Bottom screen growing the band in its fight. EDIT_TABS_SHOTS = the shots' folder (none: only the checks);
 * HOME_LAYOUT_ART = an art root.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class EditTabsShotsTest {
    private val out: File? = System.getenv("EDIT_TABS_SHOTS")?.let { File(it).apply { mkdirs() } }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private val homes = ArrayList<HomeView>()

    @After fun down() {
        for (h in homes) runCatching { h.editCancel() }
        setField(PartyModel.live, "party", null); HomeLayoutStore.character(null); HomeLayoutStore.set(HomeLayout())
    }

    private fun lay(v: View, w: Int = W, h: Int = H) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun settle(n: Int = 30) { for (i in 0 until n) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }
    private fun setField(o: Any, name: String, v: Any?) { o.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(o, v) }
    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)

    private val castDoc = JSONObject("""{"type":"castbar","v":"cb6","n":8,"pick":8,"pp":[39,19],"e":[
        {"s":1,"id":403,"i":136048,"c":0},{"s":2,"id":8042,"i":136026,"c":1},{"s":3,"id":331,"i":136052,"c":2},
        {"s":4,"id":324,"i":136051,"c":3},{"s":5,"id":8017,"i":136086,"c":4},{"s":6,"id":2484,"i":136102,"c":5},
        {"s":7,"id":8018,"i":136015,"c":6},{"s":8,"id":5730,"i":135824,"c":7}]}""")
    private val PAD = JSONObject().put("v", "pad22").put("x", 0).put("y", 12).put("cw", 6).put("ch", 2).put("cols", 40).put("pw", 1920).put("ph", 1080)
        .put("rows", JSONObject().put("qpick", 3).put("qact", 4).put("pfocus", 9))
        .put("sizes", JSONObject().put("qpick", 40).put("qact", 40).put("pfocus", 31))
    private fun quests(): JSONObject {
        val focused = JSONObject().put("id", 60).put("title", "Kobold Candles").put("zone", "Elwynn Forest").put("lv", 7).put("slot", 1)
            .put("watch", 1).put("focus", true)
            .put("obj", JSONArray().put(JSONObject().put("t", "Wax Candles: 3/8").put("have", 3).put("need", 8)))
        return JSONObject().put("plv", 7).put("focus", 60).put("rwMode", "id").put("list", JSONArray().put(focused)).put("pad", PAD).put("cb", false)
    }
    private val chatLines = listOf("general" to "[General] Thornbrook: LF2M Deadmines", "guild" to "[Guild] Mirelle: grats on 12!",
        "say" to "Oswin says: anyone near Goldshire?", "loot" to "You receive loot: Broken Boar Tusk.",
        "whisper" to "Seraphine whispers: inv pls", "party" to "[Party] Iselle: pulling next pack", "guild" to "[Guild] Aldric: gz on 20!",
        "say" to "Korvash says: on my way", "system" to "Quest accepted: Kobold Candles", "general" to "[Trade] Odwin: WTS Copper Bars")
    private val roster = listOf(
        """{"u":"player","i":0,"n":"Korvash","c":"SHAMAN","role":"HEALER","pt":"MANA","lv":20,"dead":false,"off":false,"col":"ff0070dd"}""",
        """{"u":"party1","i":1,"n":"Brannoc","c":"WARRIOR","role":"TANK","pt":"RAGE","lv":21,"dead":false,"off":false,"col":"ffc69b6d"}""",
        """{"u":"party2","i":2,"n":"Seraphine","c":"MAGE","role":"DAMAGER","pt":"MANA","lv":20,"dead":false,"off":false,"col":"ff3fc7eb"}""",
        """{"u":"party3","i":3,"n":"Tamsin","c":"ROGUE","role":"DAMAGER","pt":"ENERGY","lv":20,"dead":false,"off":false,"col":"fffff468"}""")
    private fun partyDoc(on: Boolean, fight: Boolean = false): JSONObject {
        val m = JSONArray(); for (r in roster) m.put(JSONObject(r))
        return JSONObject().put("type", "party").put("v", "m77").put("rs", 1)
            .put("p", JSONObject().put("on", on).put("box", 1).put("fight", fight).put("grp", true).put("sel", "bound").put("m", m))
    }

    private class Page(val home: HomeView, val cast: CastView, val strip: Strip)

    private fun art() { System.getenv("HOME_LAYOUT_ART")?.let { root ->
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) } }

    private fun page(layout: HomeLayout, party: Boolean = true, partyOn: Boolean = false, ct: Int = 1): Page {
        art()
        app.getSharedPreferences("home", 0).edit().clear().commit()
        HomeSettings.init(app); Settings.init(app); Tr.init(app)
        HomeLayoutStore.character(null)
        HomeLayoutStore.set(layout)
        CastStripModel.Live.pos = 0f; CastStripModel.Live.side = null
        setField(PartyModel.live, "party", null)
        if (party) PartyModel.live.onParty(PartyModel.parseParty(partyDoc(partyOn))!!, true, System.currentTimeMillis())
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        homes += home
        home.forceLive = true
        home.borrow()
        home.onPadMap(PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m188","m":{"page":1,"sets":[],"dpad":{"on":true,"want":true,"ct":$ct,"cc":0,"gsw":1}}}"""))!!)
        lay(home)
        cast.roomBarOn = true
        cast.roomBar = HoldTalk.DEFAULT_DIRS
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        val bar = CastStripModel.parse(castDoc)!!
        CastStripModel.Live.set(bar); home.onCastBar(bar)
        fightWord(home, cast, false)
        val q = quests()
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
        home.onEditChrome = { v ->
            strip.editRibbon = v?.let { ev -> { c: Canvas, w: Float, top: Float, g0: Float, g1: Float -> ev.drawRibbon(c, w, top, g0, g1) } }
            strip.editTick = v != null
            strip.editTouch = v?.let { ev -> { x: Float, y: Float, action: Int -> ev.ribbonTouch(x, y, action) } }
            strip.editBar = v?.let { ev -> { c: Canvas, layer: Int -> ev.drawBar(c, layer) } }
            strip.editBarTouch = v?.let { ev -> { e: MotionEvent -> ev.barTouch(e) } }
            strip.editPose = v?.let { ev -> { r: Room, o: FloatArray -> ev.roomPose(r, o) } }
            strip.editApps = v?.apps()
        }
        home.roomBar = strip
        strip.showOrder(RoomBar.shown(HomeSettings.layout), 0L)
        return Page(home, cast, strip)
    }

    private fun fightWord(home: HomeView, cast: CastView, on: Boolean) {
        val ab = JSONObject().put("hud", JSONObject().put("ok", true)).put("hudS", JSONObject().put("c", on).put("g", 5000.0))
        home.onFull(JSONObject().put("type", "full").put("map", JSONObject().put("zone", "Elwynn Forest").put("pvp", "contested"))
            .put("actionbar", ab), true)
        cast.update(ab)
        home.tick(); ShadowLooper.idleMainLooper()
    }

    /** the screen as the Thor shows it: Home (its own zoom and tab row while editing) over the room bar's strip */
    private fun shot(p: Page, name: String) {
        val o = out ?: return
        val b = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            setField(p.home, "clockText", "9:51 AM"); setField(p.home, "batteryPct", 98)
            lay(p.home)
            val c = Canvas(b); c.drawColor(Theme.ink)
            p.home.draw(c)
            p.strip.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Strip.HEIGHT, View.MeasureSpec.EXACTLY))
            p.strip.layout(0, 0, W, Strip.HEIGHT)
            c.save(); c.translate(0f, H.toFloat()); p.strip.draw(c); c.restore()
            if (pass == 0) settle(10)
        }
        File(o, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun boxes(home: HomeView): Map<String, RectF> = (field(home, "now") as Map<Any, RectF>).entries.associate { it.key.toString() to RectF(it.value) }
    private fun model(p: Page) = p.home.editModel()!!
    private fun key(p: Page, k: String, n: Int = 30) { p.home.editKey(k); settle(n) }
    private fun touch(p: Page, action: Int, x: Float, y: Float, down: Long) {
        val e = MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
        p.home.dispatchTouchEvent(e); e.recycle()
    }
    private fun tap(p: Page, x: Float, y: Float) {
        val t = android.os.SystemClock.uptimeMillis()
        touch(p, MotionEvent.ACTION_DOWN, x, y, t); settle(2); touch(p, MotionEvent.ACTION_UP, x, y, t); settle(20)
    }

    // ---------------------------------------------------------------- the records

    /** Out of combat: Classic-like (the bar under the chat); In combat: its own page (the map wide, the bar across, Grow); Party mode:
     *  the bar split, the list at the map's foot, Combat bars Bottom screen */
    private val REC = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
        .onTab(HomeTab.COMBAT) { it.copy(cast = HomeLayout.CastDock.ACROSS, chatShare = HomeLayout.Share.THIRD, card = HomeLayout.CardDock.OFF, grow = true) }
        .onTab(HomeTab.PARTY) { it.copy(cast = HomeLayout.CastDock.SPLIT, party = HomeLayout.PartyDock.ABOVE_CHAT, fight = HomeLayout.FightBand.GROW) }

    // ---------------------------------------------------------------- edit mode

    @Test fun theTabRowAndEachTabsPage() {
        val p = page(REC)
        p.home.editOpen(EditStop.CHAT); settle(40)
        val m = model(p)
        assertEquals("opens on what Home shows", HomeTab.OUT, m.tab)
        val z = p.home.pageZoom()
        assertNotNull("the page zoomed out under the tab row", z)
        assertEquals(HomeEditView.TAB_ROW, z!!.second, 0.5f)
        assertEquals((H - HomeEditView.TAB_ROW) / H, z.first, 0.002f)
        val rows = p.home.editChrome.tabRects(W.toFloat())
        assertEquals(3, rows.size)
        assertTrue("the tabs inside the row", rows.all { it.top >= 0f && it.bottom <= HomeEditView.TAB_ROW })
        shot(p, "01_tab_out_of_combat")
        assertEquals(HomeLayout.CastDock.UNDER_CHAT, HomeLayoutRules.shown(m.page).cast)
        p.home.editAct { it.openMenu(EditStop.CAST) }; settle(20)
        shot(p, "01b_out_of_combat_bar_menu_no_sizes")
        p.home.editAct { it.closeMenu() }; settle(10)
        // RB: In combat - its own page (the map wide, the bar across and grown), the fight look
        key(p, "PADRSHOULDER", 40)
        assertEquals(HomeTab.COMBAT, m.tab); assertTrue(m.fightPreview)
        val b = boxes(p.home)
        assertEquals("the chat a third", (1240f - 24f) / 3f, b.getValue("CHAT").width(), 0.5f)
        // (Grow steps back toward Same size here: the takeover's party rows under the grown band leave the third-wide chat under its
        // 5 lines - HomeLayoutSolver.places' step back - so the bar is as tall as the page lets it)
        assertTrue(m.page.grow); assertEquals(1224f, b.getValue("STRIP").width(), 0.5f)
        assertTrue("Across: no smaller than Same size", b.getValue("STRIP").height() >= 132f - 0.5f)
        assertEquals("the grown band (the fight look)", 416f, b.getValue("BARS").bottom, 0.5f)
        shot(p, "02_tab_in_combat")
        p.home.editAct { it.pencilTap(EditStop.FIGHT_PEN) }; settle(20)
        assertEquals(EditStop.FIGHT_PEN, m.menu)
        shot(p, "02b_in_combat_fight_bubble_combat_bars")
        p.home.editAct { it.closeMenu() }; settle(10)
        p.home.editAct { it.openMenu(EditStop.CAST) }; settle(20)
        shot(p, "02c_in_combat_bar_menu_slots_grow")
        p.home.editAct { it.closeMenu() }; settle(10)
        // RB: Party mode - its page (Split, the list wide), its look
        key(p, "PADRSHOULDER", 40)
        assertEquals(HomeTab.PARTY, m.tab); assertFalse(m.fightPreview)
        assertNotNull("Split's second half", boxes(p.home)["CAST2"])
        shot(p, "03_tab_party_mode")
        p.home.editAct { it.pencilTap(EditStop.PARTY_PEN) }; settle(20)
        assertEquals(EditStop.PARTY_PEN, m.menu)
        shot(p, "03b_party_mode_party_list_bubble")
        p.home.editAct { it.closeMenu() }; settle(10)
        // its Fight toggle: Bottom screen grows the band in the party fight (R0 lifted for this pick), the list in the takeover's rows
        p.home.editAct { it.toggleFight() }; settle(40)
        assertTrue(m.fightPreview)
        assertEquals("Party mode's Bottom screen grows the band", 416f, boxes(p.home).getValue("BARS").bottom, 0.5f)
        shot(p, "03c_party_mode_fight_bottom_screen")
        p.home.editAct { it.pencilTap(EditStop.FIGHT_PEN) }; settle(20)
        shot(p, "03d_party_mode_fight_bubble")
        p.home.editAct { it.closeMenu() }; settle(10)
        // LB twice: Out of combat's page again, every tab's own kept
        key(p, "PADLSHOULDER"); key(p, "PADLSHOULDER", 40)
        assertEquals(HomeTab.OUT, m.tab)
        assertEquals((1240f - 24f) / 2f, boxes(p.home).getValue("CHAT").width(), 0.5f)
        assertEquals(REC, m.draft)
        // a finger on the In combat tab (the row is never zoomed)
        val t = p.home.editChrome.tabRects(W.toFloat())[1]
        tap(p, t.centerX(), t.centerY())
        assertEquals(HomeTab.COMBAT, m.tab)
        // and a finger on the page maps through the zoom: the map's middle selects the map
        val mb = boxes(p.home).getValue("MAP")
        val pt = floatArrayOf(mb.centerX(), mb.centerY())
        val zm = android.graphics.Matrix(); val zz = p.home.pageZoom()!!
        zm.postScale(zz.first, zz.first, W / 2f, 0f); zm.postTranslate(0f, zz.second); zm.mapPoints(pt)
        tap(p, pt[0], pt[1])
        assertEquals(EditStop.MAP, m.cursor)
        p.home.editDone(); settle(40)
        assertNull("the zoom goes with the chrome", p.home.pageZoom())
    }

    /** Combat bars = Top screen on the In combat tab: the band away, the Cast bar row; and Grow on / off for the three bar shapes */
    @Test fun topScreenAndGrowOnOff() {
        val top = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
            .onTab(HomeTab.COMBAT) { it.copy(fight = HomeLayout.FightBand.TOP) }
        val p = page(top)
        p.home.editOpen(EditStop.CHAT); settle(40)
        key(p, "PADRSHOULDER", 40)
        p.home.editAct { it.pencilTap(EditStop.FIGHT_PEN) }; settle(20)
        shot(p, "02d_in_combat_top_screen_bubble")
        p.home.editAct { it.closeMenu() }; settle(10)
        assertTrue("the band away", boxes(p.home).getValue("BARS").bottom <= 0f)
        shot(p, "02e_in_combat_top_screen")
        p.home.editCancel()
        for ((dock, n) in listOf(HomeLayout.CastDock.ACROSS to "across", HomeLayout.CastDock.UNDER_CHAT to "under_chat",
                HomeLayout.CastDock.SPLIT to "split")) for (grow in listOf(false, true)) {
            val q = page(HomeLayout(cast = dock, card = HomeLayout.CardDock.OFF).onTab(HomeTab.COMBAT) { it.copy(grow = grow) }, party = false)
            q.home.editOpen(EditStop.CHAT); settle(40)
            key(q, "PADRSHOULDER", 40)
            val s = boxes(q.home).getValue("STRIP")
            assertEquals("$n grow $grow", if (!grow) 132f else if (dock == HomeLayout.CastDock.ACROSS) 163.5f else 161f, s.height(), 0.5f)
            shot(q, "04_grow_${n}_${if (grow) "on" else "off"}")
            q.home.editCancel()
        }
    }

    // ---------------------------------------------------------------- at runtime

    /** our takeover lays In combat's page (B on Home: Out of combat's again mid-fight); its bar Off slides away */
    @Test fun aFightSwitchesLayouts() {
        val rec = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
            .onTab(HomeTab.COMBAT) { it.copy(chatShare = HomeLayout.Share.TWO_THIRDS, cast = HomeLayout.CastDock.ACROSS, grow = true) }
        val p = page(rec, party = false)
        val rest = boxes(p.home)
        assertEquals((1240f - 24f) / 2f, rest.getValue("CHAT").width(), 0.5f)
        shot(p, "05a_rest_out_of_combat_layout")
        fightWord(p.home, p.cast, true); settle(40)
        assertTrue(p.home.fightShown())
        val f = boxes(p.home)
        assertEquals("In combat's chat: two thirds", (1240f - 24f) * 2f / 3f, f.getValue("CHAT").width(), 0.5f)
        assertEquals("In combat's bar: across, grown", 1224f, f.getValue("STRIP").width(), 0.5f)
        assertEquals(163.5f, f.getValue("STRIP").height(), 0.5f)
        shot(p, "05b_fight_in_combat_layout")
        // B on Home (after the grow's grace): the takeover given back - Out of combat's page mid-fight
        // (past the grow's 2 s grace: B in the pull's opening presses is a fight button - HomeView.GROW_B_GRACE_MS)
        setField(p.home, "grewAt", 0L)
        assertTrue(p.home.fightShown())
        p.home.pressB(); settle(40)
        val b = boxes(p.home)
        assertEquals((1240f - 24f) / 2f, b.getValue("CHAT").width(), 0.5f)
        assertEquals(132f, b.getValue("STRIP").height(), 0.5f)
        shot(p, "05c_fight_after_b_out_of_combat_layout")
        fightWord(p.home, p.cast, false); settle(40)
        // a fight tab with its bar Off: it slides away in the fight (cb7), back after
        val off = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT).onTab(HomeTab.COMBAT) { it.copy(cast = HomeLayout.CastDock.OFF) }, party = false)
        fightWord(off.home, off.cast, true); settle(40)
        assertEquals("slid away below the page", H.toFloat(), boxes(off.home).getValue("STRIP").top, 0.5f)
        shot(off, "05d_fight_bar_off_on_in_combat_slid_away")
    }

    /** Party mode on lays Party mode's page (solo too); its own Bottom screen grows the band in its fight, Party mode's controls on it */
    @Test fun partyModeLaysItsOwnPageAndGrowsTheBand() {
        val rec = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
            .onTab(HomeTab.PARTY) { it.copy(cast = HomeLayout.CastDock.SPLIT, fight = HomeLayout.FightBand.GROW) }
        val p = page(rec, party = true, partyOn = true)
        assertNotNull("Party mode's Split", boxes(p.home)["CAST2"])
        shot(p, "06a_party_mode_rest")
        PartyModel.live.onParty(PartyModel.parseParty(partyDoc(true, fight = true))!!, true, System.currentTimeMillis())
        p.home.partyChanged()
        fightWord(p.home, p.cast, true); settle(40)
        assertTrue(p.home.fightShown())
        assertEquals("Party mode's Bottom screen: the band grown", 416f, boxes(p.home).getValue("BARS").bottom, 0.5f)
        shot(p, "06b_party_mode_fight_bottom_screen_grown")
        // Party mode's own No grow (a record of before the tabs): the band stays (R0)
        val q = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT), party = true, partyOn = true)
        PartyModel.live.onParty(PartyModel.parseParty(partyDoc(true, fight = true))!!, true, System.currentTimeMillis())
        q.home.partyChanged()
        fightWord(q.home, q.cast, true); settle(40)
        assertTrue(boxes(q.home).getValue("BARS").bottom < 300f)
    }
}
