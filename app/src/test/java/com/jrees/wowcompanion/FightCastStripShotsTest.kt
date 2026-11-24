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
 * THE CAST BAR IN A TOP-SCREEN FIGHT ON THE REAL [HomeView] + [CastView] (the user's ruling 2026-11-23: "When the controller moves to the
 * top, the cast bar should still show across the top of the bottom screen ... Same thing with the swing timer"). Pinned: with In fights =
 * Top screen and Cast bar in fights = Bottom screen (the default) the fight lays the strip across the very top of Home (8, 8 .. 1232, 36),
 * the chat / map / tap-to-cast / party list under it, the band's own line left out (CastView.lineAway) and the strip PAINTING the cast
 * (its name inside) and the swing timer (both hands) - nothing painted there with nothing running; the rooms' bar leaves the cast to Home;
 * with Cast bar in fights = Top screen (the add-on's `cc` 1) no strip, the columns from the top gap and the rooms' bar quiet too (the
 * game's own bar shows it on the top screen); out of the fight the band draws its line again. With FIGHT_CASTBAR_SHOTS set (and
 * HOME_LAYOUT_ART = an art root) the pictures: Classic, chat / map swapped with the party list at the top of the map's column (Party
 * mode's fight), the tap-to-cast bar above the chat, the Top screen setting, and Edit Home's Fight preview.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class FightCastStripShotsTest {
    private val out: File? = System.getenv("FIGHT_CASTBAR_SHOTS")?.let { File(it).apply { mkdirs() } }
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
    private val roster = listOf(
        """{"u":"player","i":0,"n":"Korvash","c":"SHAMAN","role":"HEALER","pt":"MANA","lv":20,"dead":false,"off":false,"col":"ff0070dd"}""",
        """{"u":"party1","i":1,"n":"Brannoc","c":"WARRIOR","role":"TANK","pt":"RAGE","lv":21,"dead":false,"off":false,"col":"ffc69b6d"}""",
        """{"u":"party2","i":2,"n":"Seraphine","c":"MAGE","role":"DAMAGER","pt":"MANA","lv":20,"dead":false,"off":false,"col":"ff3fc7eb"}""")
    private fun partyDoc(): JSONObject {
        val m = JSONArray(); for (r in roster) m.put(JSONObject(r))
        return JSONObject().put("type", "party").put("v", "m77").put("rs", 1)
            .put("p", JSONObject().put("on", true).put("box", 1).put("fight", false).put("grp", true).put("sel", "bound").put("m", m))
    }
    private val chatLines = listOf("general" to "[General] Thornbrook: LF2M Deadmines", "guild" to "[Guild] Mirelle: grats on 12!",
        "say" to "Oswin says: anyone near Goldshire?", "loot" to "You receive loot: Broken Boar Tusk.",
        "whisper" to "Seraphine whispers: inv pls", "party" to "[Party] Iselle: pulling next pack", "system" to "Quest accepted: Kobold Candles")

    private class Page(val home: HomeView, val cast: CastView)

    private fun art() { System.getenv("HOME_LAYOUT_ART")?.let { root ->
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) } }

    /** a Home page for [layout] ([fight] = the add-on's fight word; [party] = Party mode on with a three-player party) with the add-on's
     *  padmap: the Wings flag `ct` 1 and the cast bar's screen [cc] */
    private fun page(layout: HomeLayout, fight: Boolean, party: Boolean = false, cc: Int? = 0, hudS: JSONObject? = null, gsw: Int? = null): Page {
        art()
        // the settings objects keep the FIRST test's prefs (init sets them only while null): another class's test left its writes there
        // (HomeEditShotsTest's band bubble turns Swing timer off - this class's swing checks then painted nothing). Each page starts on
        // this test's own cleared files, as HomePlacesGoldenTest does
        for ((o, name) in listOf(HomeSettings to "home", Settings to "settings", CastSettings to "cast")) {
            val sp = app.getSharedPreferences(name, 0).apply { edit().clear().commit() }
            o.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.set(null, sp)
        }
        HomeSettings.init(app); Settings.init(app); Tr.init(app)
        HomeLayoutStore.character(null)
        HomeLayoutStore.set(layout)
        CastStripModel.Live.pos = 0f; CastStripModel.Live.side = null
        setField(PartyModel.live, "party", null)
        if (party) PartyModel.live.onParty(PartyModel.parseParty(partyDoc())!!, true, System.currentTimeMillis())
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.pageZoomOn = false                                       // (edit tabs: this suite samples Home's own px; the zoom + tab row: EditTabsShotsTest)
        home.borrow()
        val ccJson = (if (cc == null) "" else ",\"cc\":$cc") + (if (gsw == null) "" else ",\"gsw\":$gsw")
        home.onPadMap(PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m188","m":{"page":1,"sets":[],"dpad":{"on":true,"want":true,"ct":1$ccJson}}}"""))!!)
        lay(home)
        cast.roomBarOn = true
        cast.roomBar = HoldTalk.DEFAULT_DIRS
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        val bar = CastStripModel.parse(castDoc)!!
        CastStripModel.Live.set(bar); home.onCastBar(bar)
        val st = (hudS ?: JSONObject()).put("c", fight).put("g", G)
        val ab = JSONObject().put("hud", JSONObject().put("ok", true)).put("hudS", st)
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

    private fun sw(k: Int, ago: Double, d: Double) = JSONObject().put("k", k).put("s", G - ago).put("d", d)
    private fun casting(name: String) = JSONObject().put("k", "c").put("s", G - 0.9).put("e", G + 1.1).put("nm", name)

    /** the page drawn (Home over the room bar's place); [name] saved when FIGHT_CASTBAR_SHOTS is set */
    private fun render(p: Page, name: String?): Bitmap {
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
        val d = out
        if (name != null && d != null) {
            File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // the strip and the columns' tops, enlarged
            val top = Bitmap.createBitmap(b, 0, 0, W, 120)
            File(d, "${name}_top.png").outputStream().use { Bitmap.createScaledBitmap(top, W, 240, true).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return b
    }

    /** pixels in the strip's box that are not the page's ink */
    private fun inked(b: Bitmap, r: RectF): Int {
        var n = 0
        val ink = Theme.ink
        for (y in r.top.toInt() until r.bottom.toInt()) for (x in r.left.toInt() until r.right.toInt()) if (b.getPixel(x, y) != ink) n++
        return n
    }

    private val STRIP = RectF(8f, 8f, 1232f, 36f)

    @Test fun theStripCarriesTheCastAndTheSwingAcrossTheTop() {
        val l = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.TOP)
        // a cast running in the fight
        val p = page(l, fight = true, hudS = JSONObject().put("aa", true).put("sw", sw(0, 1.2, 2.6)).put("cs", casting("Frostbolt")))
        val b = boxes(p.home)
        assertTrue("the band away", p.cast.away)
        assertEquals("the strip across the very top", STRIP, b.getValue("FIGHT_CAST"))
        for (part in listOf("CHAT", "MAP", "STRIP")) assertTrue("$part under the strip", b.getValue(part).top >= STRIP.bottom + 8f - 0.01f)
        assertEquals(44f, b.getValue("CHAT").top, 0.01f); assertEquals(44f, b.getValue("MAP").top, 0.01f)
        val v = views(p.home).getValue("FIGHT_CAST") as FightCastStripView
        assertTrue("the strip carries the line", v.on && p.cast.lineAway)
        assertEquals(View.VISIBLE, v.visibility)
        // (CastView.ownCastBarShown also needs the view on a window: the rooms' bar's half is pinned in HomeLayoutShotsTest's slide frames)
        val bm = render(p, "fight_bottom_classic_casting")
        assertTrue("the strip paints the cast (${inked(bm, STRIP)} px)", inked(bm, STRIP) > 4000)
        // the swing alone (dual wield: two lines)
        val s = page(l, fight = true, hudS = JSONObject().put("aa", true).put("sw", sw(0, 1.6, 2.6)).put("so", sw(1, 0.5, 1.8)))
        val sm = render(s, "fight_bottom_classic_swing_dual")
        assertTrue("the strip paints the swing (${inked(sm, STRIP)} px)", inked(sm, STRIP) > 2000)
        // nothing running: the strip's place kept, nothing painted
        val idle = page(l, fight = true)
        val im = render(idle, "fight_bottom_classic_idle")
        assertEquals("nothing running: nothing painted in the strip", 0, inked(im, RectF(STRIP.left + 20f, STRIP.top + 2f, STRIP.right - 20f, STRIP.bottom - 2f)))
        assertEquals(44f, boxes(idle.home).getValue("CHAT").top, 0.01f)
        // out of the fight: the band draws its own line again, the strip carries nothing
        val rest = page(l, fight = false)
        assertFalse(rest.cast.away); assertFalse(rest.cast.lineAway)
        assertFalse((views(rest.home).getValue("FIGHT_CAST") as FightCastStripView).on)
    }

    @Test fun swappedWithThePartyListOnTopAndTheBarAboveTheChat() {
        // chat / map swapped, the party list at the top of the map's column, Party mode's fight (the band away, the rows fill the strip)
        val l = HomeLayout(chatSide = HomeLayout.Side.RIGHT, cast = HomeLayout.CastDock.UNDER_CHAT, party = HomeLayout.PartyDock.ABOVE_MAP,
            fight = HomeLayout.FightBand.TOP)
        val p = page(l, fight = true, party = true, hudS = JSONObject().put("aa", true).put("sw", sw(0, 1.0, 2.4)).put("cs", casting("Lightning Bolt")))
        val b = boxes(p.home)
        assertTrue("Party mode's Top fight: the band away", p.cast.away)
        assertEquals(STRIP, b.getValue("FIGHT_CAST"))
        val party = b.getValue("PARTY")
        assertTrue("the party list under the strip ($party)", party.top + PartyModuleView.RISE >= STRIP.bottom + 8f - 0.01f)
        assertTrue("the party list in the map's column, on the left", party.left < 620f)
        assertTrue(b.getValue("CHAT").left > b.getValue("MAP").left)
        for ((k, r) in b) if (k != "FIGHT_CAST" && k != "BARS" && k != "AURAS") assertFalse("$k clear of the strip", RectF.intersects(r, STRIP))
        render(p, "fight_bottom_swapped_party_top_casting")
        val s = page(l, fight = true, party = true, hudS = JSONObject().put("aa", true).put("sw", sw(0, 1.6, 2.6)).put("so", sw(1, 0.5, 1.8)))
        val sb = render(s, "fight_bottom_swapped_party_top_swing")
        assertTrue("Party mode's Top fight (no takeover): the strip paints the swing too (${inked(sb, STRIP)} px)", inked(sb, STRIP) > 2000)
        // the tap-to-cast bar above the chat: first under the strip
        val a = page(l.copy(cast = HomeLayout.CastDock.ABOVE_CHAT, party = HomeLayout.PartyDock.ABOVE_CHAT), fight = true,
            hudS = JSONObject().put("aa", true).put("sw", sw(0, 1.2, 2.6)))
        assertEquals("the bar above the chat a gap under the strip", 44f, boxes(a.home).getValue("STRIP").top, 0.01f)
        render(a, "fight_bottom_swapped_bar_above_chat_swing")
    }

    @Test fun topScreenLeavesTheBottomScreenToTheColumns() {
        val l = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.TOP, fightCast = HomeLayout.FightCast.TOP)
        val hud = JSONObject().put("aa", true).put("sw", sw(0, 1.2, 2.6)).put("cs", casting("Frostbolt"))
        val p = page(l, fight = true, cc = 1, hudS = hud)
        val b = boxes(p.home)
        assertNull("the game's bar on the top screen: no strip", b["FIGHT_CAST"])
        assertEquals(8f, b.getValue("CHAT").top, 0.01f); assertEquals(8f, b.getValue("MAP").top, 0.01f)
        assertFalse(p.cast.lineAway)
        assertTrue("the game's own bar shows the cast up top (the rooms' bar leaves it out: CastView.lineTop)", p.cast.lineTop)
        render(p, "fight_top_setting_casting")
        // the add-on not saying it yet (cc 0) or an older add-on (no cc): the strip stays (a cast bar never on neither screen)
        for (cc in listOf(0, null)) {
            val q = page(l, fight = true, cc = cc, hudS = JSONObject(hud.toString()))
            assertEquals("cc $cc: the strip", STRIP, boxes(q.home).getValue("FIGHT_CAST"))
            assertTrue(q.cast.lineAway)
        }
    }

    /** the user's picks 2026-11-23 ("Keep ours on the bottom" / "One place"): Cast bar in fights = Top screen with WoW's own swing timer
     *  OFF (PadMap m188 `gsw` 0) - the strip stays across the top of the bottom screen with the SWING alone (the cast is the game's own bar
     *  on the top screen, so the strip paints none of it); with the game's swing timer on (`gsw` 1, or an older add-on): as built, no strip */
    @Test fun topScreenCastWithTheGamesSwingOffKeepsTheSwingStrip() {
        val l = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.TOP, fightCast = HomeLayout.FightCast.TOP)
        val cast = page(l, fight = true, cc = 1, gsw = 0, hudS = JSONObject().put("aa", true).put("cs", casting("Frostbolt")))
        assertEquals("the swing strip across the very top", STRIP, boxes(cast.home).getValue("FIGHT_CAST"))
        assertTrue("it carries the line, the swing alone", cast.cast.lineAway && cast.cast.stripNoCast)
        assertEquals(44f, boxes(cast.home).getValue("CHAT").top, 0.01f)
        val cm = render(cast, "fight_top_cast_swing_off_casting")
        assertEquals("a cast alone: nothing in the strip (the game's bar on the top screen has it)", 0,
            inked(cm, RectF(STRIP.left + 20f, STRIP.top + 2f, STRIP.right - 20f, STRIP.bottom - 2f)))
        val swing = page(l, fight = true, cc = 1, gsw = 0, hudS = JSONObject().put("aa", true).put("sw", sw(0, 1.2, 2.6)).put("cs", casting("Frostbolt")))
        val sm = render(swing, "fight_top_cast_swing_off_swing")
        assertTrue("the swing painted through the cast (${inked(sm, STRIP)} px)", inked(sm, STRIP) > 2000)
        assertFalse("the band's own state keeps the cast (restored after the strip's paint)", (field(swing.cast, "castStartMs") as Double).isNaN())
        // the game's swing timer on: as built (the game's bar and the Wings' swing lines up top, nothing here)
        for (g in listOf(1, null)) {
            val q = page(l, fight = true, cc = 1, gsw = g, hudS = JSONObject().put("aa", true).put("sw", sw(0, 1.2, 2.6)))
            assertNull("gsw $g: no strip", boxes(q.home)["FIGHT_CAST"])
            assertFalse(q.cast.stripNoCast)
        }
        // Bottom screen (the default): the strip carries both, never the swing alone
        val b = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.TOP), fight = true, gsw = 0,
            hudS = JSONObject().put("aa", true).put("cs", casting("Frostbolt")))
        assertFalse(b.cast.stripNoCast)
        assertTrue("Bottom screen: the cast painted (${inked(render(b, null), STRIP)} px)", inked(render(b, null), STRIP) > 4000)
    }

    @Test fun theFightPreviewShowsTheStrip() {
        val l = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.TOP)
        val p = page(l, fight = false)
        assertTrue(p.home.editOpen(EditStop.FIGHT)); settle(20)
        p.home.fightLookOn(); settle(40)
        val b = boxes(p.home)
        assertEquals("the Fight preview: the band away", -HomeLayoutSolver.AWAY_PAST, b.getValue("BARS").bottom, 0.01f)
        assertEquals("the Fight preview lays the strip", STRIP, b.getValue("FIGHT_CAST"))
        assertTrue((views(p.home).getValue("FIGHT_CAST") as FightCastStripView).on)
        assertEquals(44f, b.getValue("CHAT").top, 0.01f)
        // the stand-in swing loops in the strip (P4)
        ShadowSystemClock.advanceBy(Duration.ofMillis(900))
        val bm = render(p, "fight_preview_strip")
        assertTrue("the preview's stand-in swing in the strip (${inked(bm, STRIP)} px)", inked(bm, STRIP) > 1000)
        // the fight pencil's bubble: In fights, Cast bar, Swing timer
        p.home.editAct { it.pencilTap(EditStop.FIGHT_PEN) }; settle(10)
        @Suppress("UNCHECKED_CAST")
        val rows = (HomeView::class.java.getDeclaredMethod("editMenuRows", EditStop::class.java).apply { isAccessible = true }
            .invoke(p.home, EditStop.FIGHT_PEN) as List<*>).filterIsInstance<MenuRow>()
        assertEquals(listOf(BottomTiles.COMBAT, BottomTiles.FIGHT_CAST, BottomTiles.SWING), rows.map { it.key })
        render(p, "fight_preview_bubble")
        p.home.editCancel()
    }

    /** the review: the fight bubble with Top screen (its new Cast bar row) in Spanish and French - every row's buttons inside the row and
     *  clear of its label (a long row puts them under it), the bubble on the screen */
    @Suppress("UNCHECKED_CAST")
    @Test fun theFightBubbleFitsInSpanishAndFrench() {
        val rowsOf = HomeView::class.java.getDeclaredMethod("editMenuRows", EditStop::class.java).apply { isAccessible = true }
        try {
            for (lang in listOf("es", "fr")) {
                val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.TOP), fight = false)
                listOf(File("../companion/app/src/main/assets/companion/i18n/$lang.json"), File("companion/app/src/main/assets/companion/i18n/$lang.json"))
                    .firstOrNull { it.canRead() }?.let { Tr.load(lang, it.readText()) }
                Tr.setChoice(app, lang)
                assertTrue(p.home.editOpen(EditStop.FIGHT)); settle(20)
                p.home.fightLookOn(); settle(40)
                p.home.editAct { it.pencilTap(EditStop.FIGHT_PEN) }; settle(10)
                render(p, "fight_preview_bubble_$lang")
                val ev = p.home.editChrome
                val box = ev.menuBox(EditStop.FIGHT_PEN)
                assertTrue("$lang: the bubble on the screen ($box)", box.left >= 0f && box.right <= W.toFloat() && box.bottom <= H.toFloat())
                val btns = field(ev, "menuBoxes") as List<List<RectF>>
                val rows = field(ev, "menuRowBoxes") as List<RectF>
                val words = rowsOf.invoke(p.home, EditStop.FIGHT_PEN) as List<MenuRow>
                assertEquals("$lang: In fights, Cast bar, Swing timer", listOf(BottomTiles.COMBAT, BottomTiles.FIGHT_CAST, BottomTiles.SWING), words.map { it.key })
                assertEquals("$lang: one row box per row", words.size, rows.size)
                val lp = UiKit.bodyPaint(28f)
                for ((i, r) in words.withIndex()) {
                    val rr = rows[i]
                    val labelRight = rr.left + 18f + lp.measureText(Tr.t(r.label))
                    for (b in btns[i]) {
                        assertTrue("$lang '${Tr.t(r.label)}': button inside its row ($b in $rr)", b.left >= rr.left - 1f && b.right <= rr.right + 1f)
                        assertTrue("$lang '${Tr.t(r.label)}': buttons clear of the label ($b vs $labelRight)",
                            b.left >= labelRight - 1f || b.top >= rr.top + 0.3f * rr.height())
                    }
                }
                p.home.editCancel()
            }
        } finally { Tr.setChoice(app, "auto") }
    }

    /** EDIT TABS (2026-11-24): the fight look - solo the In combat tab, with Party mode on its own tab's Fight toggle */
    private fun HomeView.fightLookOn() {
        val m = editModel() ?: return
        if (m.tab == HomeTab.PARTY) { if (!m.fightPreview) editKey("PAD1") } else editAct { it.tabTo(HomeTab.COMBAT) }
    }
    @Suppress("unused")
    private fun HomeView.fightToggle() {
        val m = editModel() ?: return
        if (m.tab == HomeTab.PARTY) editAct { it.toggleFight() } else editAct { it.tabTo(if (m.tab == HomeTab.COMBAT) HomeTab.OUT else HomeTab.COMBAT) }
    }
}
