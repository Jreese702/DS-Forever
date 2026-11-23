package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * THE PARTY LIST ACROSS AND ITS SIZES ON THE REAL [HomeView] (the user, 2026-11-23: "the party frames should also expand across the
 * opposit right or left side of the screen if its in the top or bottom possitions"; "dynamic sizing options to the party mode windows"
 * - the picks: Player picks, all three; Settings + Edit Home). Pinned on the laid page: in Party mode the list spans both columns from
 * each dock (either swap), the chat and the map under / over it, its plates across ([PartyModuleView.module] over its view); each pick's
 * rows in a fight with the controller on the bottom screen (Grow / Same size / Shrink to make room) and on the top screen (Fill the freed
 * space / Grow a bit / Same size), Grows = Whenever Party mode is on at rest and Off in a fight; Edit Home's party pencil and its bubble
 * (beside the list, never on it), its Fight preview, and its preview of Party mode's look with Party mode off; Settings > Bottom screen >
 * Party's tile. With PARTY_SIZING_SHOTS set (and HOME_LAYOUT_ART = an art root) the pictures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PartySizingShotsTest {
    private val out: File? = System.getenv("PARTY_SIZING_SHOTS")?.let { File(it).apply { mkdirs() } }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private val G = 5000.0

    @After fun down() { setField(PartyModel.live, "party", null); HomeLayoutStore.character(null); HomeLayoutStore.set(HomeLayout()) }

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
    // (sample characters: the golden's roster)
    private val roster = listOf(
        """{"u":"player","i":0,"n":"Korvash","c":"SHAMAN","role":"HEALER","pt":"MANA","lv":20,"dead":false,"off":false,"col":"ff0070dd"}""",
        """{"u":"party1","i":1,"n":"Brannoc","c":"WARRIOR","role":"TANK","pt":"RAGE","lv":21,"dead":false,"off":false,"col":"ffc69b6d"}""",
        """{"u":"party2","i":2,"n":"Seraphine","c":"MAGE","role":"DAMAGER","pt":"MANA","lv":20,"dead":false,"off":false,"col":"ff3fc7eb"}""",
        """{"u":"party3","i":3,"n":"Tamsin","c":"ROGUE","role":"DAMAGER","pt":"ENERGY","lv":20,"dead":false,"off":false,"col":"fffff468"}""",
        """{"u":"party4","i":4,"n":"Odwin","c":"PRIEST","role":"HEALER","pt":"MANA","lv":19,"dead":false,"off":false,"col":"ffffffff"}""")
    private fun partyDoc(on: Boolean, n: Int): JSONObject {
        val m = JSONArray(); for (r in roster.take(n)) m.put(JSONObject(r))
        return JSONObject().put("type", "party").put("v", "m77").put("rs", 1)
            .put("p", JSONObject().put("on", on).put("box", 1).put("fight", false).put("grp", n > 1).put("sel", "bound").put("m", m))
    }
    private val chatLines = listOf("general" to "[General] Thornbrook: LF2M Deadmines", "guild" to "[Guild] Mirelle: grats on 12!",
        "say" to "Oswin says: anyone near Goldshire?", "loot" to "You receive loot: Broken Boar Tusk.",
        "whisper" to "Seraphine whispers: inv pls", "party" to "[Party] Iselle: pulling next pack", "system" to "Quest accepted: Kobold Candles")

    private class Page(val home: HomeView, val cast: CastView)

    private fun art() { System.getenv("HOME_LAYOUT_ART")?.let { root ->
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) } }

    /** a Home page for [layout] ([fight] = the add-on's fight word) with a party of [n] (Party mode [on]) and the add-on's padmap (the
     *  Wings flag `ct` 1, the cast bar's screen `cc` 0: the fight strip) */
    private fun page(layout: HomeLayout, fight: Boolean, on: Boolean = true, n: Int = 5): Page {
        art()
        app.getSharedPreferences("home", 0).edit().clear().commit()
        HomeSettings.init(app); Settings.init(app); Tr.init(app)
        HomeLayoutStore.character(null)
        HomeLayoutStore.set(layout)
        CastStripModel.Live.pos = 0f; CastStripModel.Live.side = null
        setField(PartyModel.live, "party", null)
        PartyModel.live.onParty(PartyModel.parseParty(partyDoc(on, n))!!, true, System.currentTimeMillis())
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        home.onPadMap(PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m187","m":{"page":1,"sets":[],"dpad":{"on":true,"want":true,"ct":1,"cc":0}}}"""))!!)
        lay(home)
        cast.roomBarOn = true
        cast.roomBar = HoldTalk.DEFAULT_DIRS
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        val bar = CastStripModel.parse(castDoc)!!
        CastStripModel.Live.set(bar); home.onCastBar(bar)
        val ab = JSONObject().put("hud", JSONObject().put("ok", true)).put("hudS", JSONObject().put("c", fight).put("g", G))
        home.onFull(JSONObject().put("type", "full").put("map", JSONObject().put("zone", "Elwynn Forest").put("pvp", "contested"))
            .put("actionbar", ab), true)
        cast.update(ab)
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
    private fun pv(p: Page) = views(p.home).getValue("PARTY") as PartyModuleView
    private fun editView(p: Page) = p.home.editChrome

    /** the page drawn (Home over the room bar's place); [name] saved when PARTY_SIZING_SHOTS is set */
    private fun render(p: Page, name: String) {
        val b = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            setField(p.home, "clockText", "9:51 AM"); setField(p.home, "batteryPct", 98)
            lay(p.home)
            val c = Canvas(b); c.drawColor(Theme.ink); p.home.draw(c)
            val st = Strip(app) { }
            st.active = Room.HOME
            st.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Strip.HEIGHT, View.MeasureSpec.EXACTLY))
            st.layout(0, 0, W, Strip.HEIGHT)
            c.translate(0f, H.toFloat()); st.draw(c)
            if (pass == 0) { Thread.sleep(150); ShadowLooper.idleMainLooper() }
        }
        out?.let { d -> File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        b.recycle()
    }

    /** the rows' height the party view lays (its first plate's) */
    private fun rowH(p: Page): Float { lay(p.home); return pv(p).module().cells[0].h }

    @Test fun fullWidthFromEveryDockBothSwaps() {
        for (dock in HomeLayout.PartyDock.values()) for (side in HomeLayout.Side.values()) {
            val l = HomeLayout(chatSide = side, party = dock, cast = HomeLayout.CastDock.UNDER_CHAT)
            val p = page(l, fight = false)
            val b = boxes(p.home)
            val tag = "$dock chat ${side.name.lowercase()}"
            val party = b.getValue("PARTY"); val chat = b.getValue("CHAT"); val map = b.getValue("MAP")
            val right = HomeLayoutSolver.partyRight(l)
            assertEquals("$tag: the tab's edge", right, pv(p).tabRight)
            if (right) { assertEquals(tag, 1240f, party.right, 0.5f); assertEquals(tag, minOf(chat.left, map.left) + 1f, party.left, 0.5f) }
            else { assertEquals(tag, 0f, party.left, 0.5f); assertEquals(tag, maxOf(chat.right, map.right) - 1f, party.right, 0.5f) }
            if (dock.top) for (c in listOf(chat, map)) assertEquals("$tag: under the list", party.bottom + PartyLayout.CHAT_GAP, c.top, 0.5f)
            else for (c in listOf(chat, map)) assertEquals("$tag: over the list", party.top - PartyLayout.CHAT_GAP, c.bottom, 0.5f)
            // the plates across: you alone on the first row, the members two to a row, each half the well
            val m = pv(p).module()
            assertEquals(5, m.cells.size)
            assertEquals(tag, m.cells[1].w, m.cells[2].w, 0.5f)
            assertTrue("$tag: a member's plate wider than a column's half (${m.cells[1].w})", m.cells[1].w > 500f)
            assertNull("$tag: the rest's own rows", pv(p).sizedH)
            render(p, "wide_${dock.name.lowercase()}_chat_${side.name.lowercase()}")
        }
        // Party mode off: the list keeps its column
        val off = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT), fight = false, on = false)
        assertEquals(615f, boxes(off.home).getValue("PARTY").right, 0.5f)
        render(off, "narrow_party_mode_off")
    }

    /** the controller stays on the bottom screen (In fights = Grow): Grow (88, today's) / Same size (64) / Shrink to make room (56) */
    @Test fun theBottomControllersPicksInAFight() {
        val l = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.GROW)
        val want = mapOf(HomeLayout.PartyBottomFight.GROW to 88f, HomeLayout.PartyBottomFight.SAME to 64f, HomeLayout.PartyBottomFight.SHRINK to 56f)
        for ((pick, h) in want) {
            val p = page(l.copy(partyBottom = pick), fight = true)
            assertFalse("Party mode: no takeover", p.home.flow.takeover)
            assertEquals(PartyLayout.Mode.FIGHT_ON, pv(p).mode)
            assertEquals(pick.name, h, rowH(p), 0.5f)
            assertEquals(if (pick == HomeLayout.PartyBottomFight.GROW) null else h, pv(p).sizedH)
            render(p, "fight_bottom_${pick.name.lowercase()}")
        }
        // Grows = Whenever Party mode is on: 88 at rest; Grows = Off: Grow keeps the rest's 64 in the fight
        val always = page(l.copy(partyGrow = HomeLayout.PartyGrow.ALWAYS), fight = false)
        assertEquals(PartyLayout.Mode.REST, pv(always).mode)
        assertEquals(88f, rowH(always), 0.5f)
        render(always, "rest_grows_whenever_party_mode")
        val offRest = page(l.copy(partyGrow = HomeLayout.PartyGrow.ALWAYS), fight = false, on = false)
        assertEquals("Party mode off: the rest's rows whatever is picked", 64f, rowH(offRest), 0.5f)
        val never = page(l.copy(partyGrow = HomeLayout.PartyGrow.OFF), fight = true)
        assertEquals(64f, rowH(never), 0.5f)
        render(never, "fight_bottom_grows_off")
    }

    /** the controller on the top screen (In fights = Top screen): Fill the freed space (today's) / Grow a bit (88) / Same size (64) */
    @Test fun theTopControllersPicksInAFight() {
        val l = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.TOP)
        for (pick in HomeLayout.PartyTopFight.values()) {
            val p = page(l.copy(partyTop = pick), fight = true)
            val b = boxes(p.home)
            assertTrue("${pick.name}: the band away", p.cast.away)
            assertEquals(PartyLayout.Mode.FIGHT_TOP, pv(p).mode)
            val party = b.getValue("PARTY")
            assertEquals("${pick.name}: under the fight strip", 8f + HomeLayoutSolver.FIGHT_STRIP_H + 8f, party.top, 0.5f)
            when (pick) {
                HomeLayout.PartyTopFight.FILL -> assertTrue("the fill (${rowH(p)})", rowH(p) > 88f)
                HomeLayout.PartyTopFight.GROW -> assertEquals(88f, rowH(p), 0.5f)
                HomeLayout.PartyTopFight.SAME -> assertEquals(64f, rowH(p), 0.5f)
            }
            for (c in listOf("CHAT", "MAP")) assertEquals("${pick.name}: the $c under the list", party.bottom + PartyLayout.CHAT_GAP, b.getValue(c).top, 0.5f)
            render(p, "fight_top_${pick.name.lowercase()}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rows(p: Page): List<MenuRow> = (HomeView::class.java.getDeclaredMethod("editMenuRows", EditStop::class.java).apply { isAccessible = true }
        .invoke(p.home, EditStop.PARTY_PEN) as List<*>).filterIsInstance<MenuRow>()

    /** Edit Home: the party list's pencil on its far corner from the tab; its bubble beside the list (under a top list, over a foot
     *  one), its three picks and the preview; Fight lays the list in its fight size; with Party mode off the bubble shows Party mode's look */
    @Test fun editHomesPartyPencilAndItsPreview() {
        val l = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
        val p = page(l, fight = false)
        assertTrue(p.home.editOpen(EditStop.CHAT)); settle(30)
        val ev = editView(p)
        val party = boxes(p.home).getValue("PARTY")
        val pen = ev.partyPenBox()!!
        assertTrue("the pencil on the list's far corner ($pen / $party)", pen.right <= party.right && pen.left > party.centerX() && pen.top >= party.top)
        assertTrue(EditStop.PARTY_PEN in ev.stops())
        p.home.editAct { it.pencilTap(EditStop.PARTY_PEN) }; settle(20)
        assertEquals(EditStop.PARTY_PEN, p.home.editModel()!!.menu)
        assertEquals(listOf(HomeEditModel.K_PARTY_GROW, HomeEditModel.K_PARTY_TOP, HomeEditModel.K_PARTY_BOTTOM, HomeEditModel.K_PARTY_PREVIEW), rows(p).map { it.key })
        val bubble = ev.menuBox(EditStop.PARTY_PEN)
        assertFalse("the bubble never on the list ($bubble / $party)", RectF.intersects(bubble, party))
        assertTrue("under it", bubble.top > party.bottom)
        assertTrue(ev.bubbleTailBox(EditStop.PARTY_PEN) != null)
        render(p, "edit_party_bubble")
        // a pick: the page lays it at once (Shrink: nothing at rest; Grows = Whenever Party mode is on: 88 at rest)
        p.home.editAct { it.menuTouch(0, 2, rows(p)) }; settle(30)
        assertEquals(HomeLayout.PartyGrow.ALWAYS, HomeSettings.layout.partyGrow)
        assertEquals(88f, rowH(p), 0.5f)
        render(p, "edit_party_bubble_grows_whenever")
        // the preview: Fight (Grow: the Party fight's 88; Shrink: 56)
        p.home.editAct { it.menuTouch(2, 2, rows(p)) }; settle(10)
        p.home.editAct { it.menuTouch(3, 1, rows(p)) }; settle(40)
        val m = p.home.editModel()!!
        assertTrue(m.fightPreview); assertEquals(EditStop.PARTY_PEN, m.menu)
        assertEquals(PartyLayout.Mode.FIGHT_ON, pv(p).mode)
        assertEquals(56f, rowH(p), 0.5f)
        assertFalse(p.home.fightShown())
        render(p, "edit_party_bubble_fight_preview_shrink")
        p.home.editAct { it.menuTouch(2, 0, rows(p)) }; settle(40)
        assertEquals(88f, rowH(p), 0.5f)
        render(p, "edit_party_bubble_fight_preview_grow")
        p.home.editCancel(); settle(40)
        // a list at the foot: the bubble over it
        val f = page(l.copy(party = HomeLayout.PartyDock.UNDER_MAP), fight = false)
        assertTrue(f.home.editOpen(EditStop.CHAT)); settle(30)
        f.home.editAct { it.pencilTap(EditStop.PARTY_PEN) }; settle(20)
        val fb = editView(f).menuBox(EditStop.PARTY_PEN); val fp = boxes(f.home).getValue("PARTY")
        assertTrue("over a foot list ($fb / $fp)", fb.bottom < fp.top && !RectF.intersects(fb, fp))
        render(f, "edit_party_bubble_foot_list")
        f.home.editCancel(); settle(40)
        // Party mode off: the bubble lays the list as Party mode does (across), closing it puts the column back
        val o = page(l, fight = false, on = false)
        assertTrue(o.home.editOpen(EditStop.CHAT)); settle(30)
        assertEquals(615f, boxes(o.home).getValue("PARTY").right, 0.5f)
        o.home.editAct { it.pencilTap(EditStop.PARTY_PEN) }; settle(40)
        assertEquals("the preview: across", 1231f, boxes(o.home).getValue("PARTY").right, 0.5f)
        render(o, "edit_party_bubble_party_mode_off")
        o.home.editAct { it.pencilTap(EditStop.PARTY_PEN) }; settle(40)
        assertEquals("closed: its column again", 615f, boxes(o.home).getValue("PARTY").right, 0.5f)
        o.home.editCancel()
    }

    /** Settings > Bottom screen > Party: its one tile, "Party list", its band's three lines (this character's record); a press writes it */
    @Test fun theSettingsTile() {
        Settings.init(app); SettingsTabs.init(app); GameUi.init(app); HomeSettings.init(app); Tr.init(app)
        art()
        app.getSharedPreferences("home", 0).edit().clear().commit()
        HomeLayoutStore.character(null); HomeLayoutStore.set(HomeLayout(fight = HomeLayout.FightBand.TOP))
        GameUi.character = "Sample-Realm"
        try {
            val r = SettingsRoom(app, SettingsView(app) { }) { null }
            r.homeSection.dpadState = { PadMapModel.Dpad(true, emptyMap(), emptyList(), emptyList(), true, 1, 2, null, null) }
            r.show(SettingsTabs.Tab.BOTTOM, force = true)
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            fun layR() { for (k in 0..2) { ShadowLooper.idleMainLooper(); lay(r); r.draw(Canvas(bmp)) } }
            layR()
            val part = r.bottomTiles.single { it.group == SettingsTabs.Group.PARTY_LIST }
            assertEquals(listOf(BottomTiles.PARTY), part.tileIds())
            val t = part.focusTargets()[0]
            val d = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, t.centerX(), t.centerY(), 0); part.dispatchTouchEvent(d); d.recycle()
            val u = android.view.MotionEvent.obtain(0, 40, android.view.MotionEvent.ACTION_UP, t.centerX(), t.centerY(), 0); part.dispatchTouchEvent(u); u.recycle()
            layR()
            assertEquals(BottomTiles.PARTY, part.openTile)
            // the band in view
            val sc = SettingsRoom::class.java.getDeclaredField("scroll").apply { isAccessible = true }.get(r) as android.widget.ScrollView
            var y = 0; var cur: View = part
            while (cur !== sc.getChildAt(0)) { y += cur.top; cur = cur.parent as View }
            sc.scrollTo(0, (y - 120).coerceAtLeast(0)); layR()
            repeat(30) { layR(); if (BottomPictures.pendingCount() == 0) return@repeat }
            Thread.sleep(200); layR()
            val c = Canvas(bmp); c.drawColor(Theme.ink); r.draw(c)
            out?.let { o -> File(o, "settings_party_tile.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            BottomTiles.press(BottomTiles.PARTY, HomeEditModel.K_PARTY_BOTTOM, 2, BottomTiles.Env(null, null)) { }
            assertEquals(HomeLayout.PartyBottomFight.SHRINK, HomeSettings.layout.partyBottom)
        } finally { GameUi.character = null; BottomPictures.drop() }
    }

    /** a Top-screen fight's Fight preview from the bubble: the band away, the list filling the strip under the fight strip */
    @Test fun theBubblesFightPreviewWithTheControllerOnTop() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.TOP), fight = false)
        assertTrue(p.home.editOpen(EditStop.CHAT)); settle(30)
        p.home.editAct { it.pencilTap(EditStop.PARTY_PEN) }; settle(20)
        p.home.editAct { it.menuTouch(3, 1, rows(p)) }; settle(40)
        val b = boxes(p.home)
        assertEquals(-HomeLayoutSolver.AWAY_PAST, b.getValue("BARS").bottom, 0.5f)
        assertEquals(PartyLayout.Mode.FIGHT_TOP, pv(p).mode)
        assertTrue(rowH(p) > 88f)
        render(p, "edit_party_bubble_fight_preview_top_fill")
        p.home.editAct { it.menuTouch(1, 2, rows(p)) }; settle(40)
        assertEquals(64f, rowH(p), 0.5f)
        render(p, "edit_party_bubble_fight_preview_top_same")
        p.home.editCancel()
    }
}
