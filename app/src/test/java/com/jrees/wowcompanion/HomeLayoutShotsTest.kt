package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * MOVABLE HOME, PHASE 1 ON THE REAL [HomeView] (SPEC.txt section 15 HomeLayoutShotsTest): each preset at rest, in a fight (Grow) and with
 * Party mode's rows, Classic swapped (Mirrored), and the glide between them. Pinned: Home's boxes are the solver's; after a swap the chat
 * is on the right with the party module's Select tab on the right edge; the LB picture and the pause wheel slide the LEFT half left
 * whatever it holds (the map after a swap), the class wheel's LB+LT uncovers the left column (D8); the D-pad toast draws on Home.
 * With HOME_LAYOUT_SHOTS set (and HOME_LAYOUT_ART = an art root: ui/kit, fonts, icons.pak) the pictures, the room bar under them:
 *   HOME_LAYOUT_SHOTS=<dir> HOME_LAYOUT_ART=<root> gradlew :app:testModernDebugUnitTest --tests "*HomeLayoutShotsTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeLayoutShotsTest {
    private val out: File? = System.getenv("HOME_LAYOUT_SHOTS")?.let { File(it).apply { mkdirs() } }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    /** HomeSettings keeps the first test's prefs for the whole JVM: the layout goes back to Classic for the tests after this one */
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
    private val PAD = JSONObject().put("v", "pad22").put("x", 0).put("y", 12).put("cw", 6).put("ch", 2).put("cols", 40).put("pw", 1920).put("ph", 1080)
        .put("rows", JSONObject().put("qpick", 3).put("qact", 4).put("pfocus", 9))
        .put("sizes", JSONObject().put("qpick", 40).put("qact", 40).put("pfocus", 31))
    private fun quests(fight: Boolean): JSONObject {
        val focused = JSONObject().put("id", 60).put("title", "Kobold Candles").put("zone", "Elwynn Forest").put("lv", 7).put("slot", 1)
            .put("watch", 1).put("focus", true)
            .put("obj", JSONArray().put(JSONObject().put("t", "Wax Candles: 3/8").put("have", 3).put("need", 8)))
        return JSONObject().put("plv", 7).put("focus", 60).put("rwMode", "id").put("list", JSONArray().put(focused)).put("pad", PAD).put("cb", fight)
    }
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
        "whisper" to "Seraphine whispers: inv pls", "party" to "[Party] Iselle: pulling next pack", "guild" to "[Guild] Aldric: gz on 20!",
        "system" to "Quest accepted: Kobold Candles", "general" to "[Trade] Odwin: WTS Copper Bars")

    private class Page(val home: HomeView, val cast: CastView)

    private fun art() { System.getenv("HOME_LAYOUT_ART")?.let { root ->
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) } }

    /** [ct] the add-on's top-screen Wings flag in a padmap fed first (the 1515 merge's review: Top screen is honoured only at ct 1);
     *  null = no padmap */
    private fun page(layout: HomeLayout, fight: Boolean = false, party: Boolean = false, dirs: Map<Int, String>? = null, ct: Int? = null): Page {
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
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        ct?.let { home.onPadMap(PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m183","m":{"page":1,"sets":[],"dpad":{"on":true,"want":true,"ct":$it}}}"""))!!) }
        lay(home)
        cast.roomBarOn = true
        cast.roomBar = dirs ?: HoldTalk.DEFAULT_DIRS
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        val bar = CastStripModel.parse(castDoc)!!
        CastStripModel.Live.set(bar); home.onCastBar(bar)
        home.onFull(JSONObject().put("type", "full").put("map", JSONObject().put("zone", "Elwynn Forest").put("pvp", "contested"))
            .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true).put("hudS", JSONObject().put("c", fight)))), true)
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

    private fun shot(p: Page, name: String) {
        val d = out ?: return
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
            if (pass == 0) { Thread.sleep(150); settle(10) }
        }
        File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun solver(l: HomeLayout, home: HomeView): Map<String, HomeLayoutSolver.Box> {
        val b = boxes(home)
        return b.mapValues { HomeLayoutSolver.Box(it.value.left, it.value.top, it.value.right, it.value.bottom) }
    }

    // ---------------------------------------------------------------- every preset

    @Test fun eachPresetLaysAsTheSolverSays() {
        val base = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
        for (preset in HomePresets.OFFERED) {
            val l = preset.apply(base)
            for ((state, fight, party) in listOf(Triple("rest", false, false), Triple("fight", true, false), Triple("party", false, true))) {
                val p = page(l, fight = fight, party = party, dirs = if (l.mirrored) MIRROR else null)
                val b = boxes(p.home)
                val chat = b.getValue("CHAT"); val map = b.getValue("MAP")
                assertEquals("${preset.id} $state: chat side", l.chatSide == HomeLayout.Side.LEFT, chat.left < map.left)
                val want = (1216f * l.chatShare.num / l.chatShare.den)
                assertEquals("${preset.id} $state: chat width", want, chat.width(), 0.6f)
                if (l.cast != HomeLayout.CastDock.OFF && !fight) {
                    val s = b.getValue("STRIP")
                    when (l.cast) {
                        HomeLayout.CastDock.UNDER_CHAT -> assertEquals(chat.left, s.left, 0.01f)
                        HomeLayout.CastDock.UNDER_MAP -> assertEquals(map.left, s.left, 0.01f)
                        else -> assertEquals(8f, s.left, 0.01f)
                    }
                    assertTrue("${preset.id}: the slot size", s.height() <= l.castSize.px + 20.01f)
                }
                if (fight && l.fight == HomeLayout.FightBand.GROW) assertEquals("${preset.id}: the grow", 416f, b.getValue("BARS").bottom, 0.01f)
                if (party) {
                    val pv = views(p.home).getValue("PARTY") as PartyModuleView
                    assertEquals(l.chatSide == HomeLayout.Side.RIGHT, pv.tabRight)
                    val pb = b.getValue("PARTY")
                    if (l.chatSide == HomeLayout.Side.RIGHT) assertEquals(1240f, pb.right, 0.01f) else assertEquals(0f, pb.left, 0.01f)
                }
                shot(p, "${preset.id.lowercase().replace(' ', '_')}_$state")
            }
        }
    }

    // ---------------------------------------------------------------- the swap and what follows it

    private val MIRROR = mapOf(1 to "map", 2 to "radial", 3 to "chat", 4 to "home")

    @Test fun theSwapAndItsSlides() {
        val classic = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
        val swapped = classic.swapped()
        // the LB picture: the LEFT half goes left whatever it holds (the map after a swap), the right half right
        val p = page(swapped, party = true, dirs = MIRROR)
        p.home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
        val v = views(p.home)
        assertTrue("the map (left) goes left", v.getValue("MAP").translationX < 0f)
        assertTrue("the chat (right) goes right", v.getValue("CHAT").translationX > 0f)
        assertTrue("the party module with the chat", v.getValue("PARTY").translationX > 0f)
        assertTrue("the bar under the chat (right) with it", v.getValue("STRIP").translationX > 0f)
        shot(p, "swapped_lb_picture")
        p.home.pauseSlide(0f, LbPartyView.SIDE_PX)
        p.home.pauseSlide(UiKit.PAUSE_OPEN_MS)
        assertTrue(v.getValue("MAP").translationX < 0f && v.getValue("CHAT").translationX > 0f)
        p.home.pauseSlide(0f)
        // D8: LB+LT's class wheel uncovers the LEFT column (the map after a swap)
        val q = page(swapped, dirs = MIRROR)
        val flow = field(q.cast, "classFlow")!!
        flow.javaClass.getDeclaredField("side").apply { isAccessible = true }.setInt(flow, 1)
        HomeView::class.java.getDeclaredMethod("classLayerShow", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(q.home, true)
        settle(40)
        val qv = views(q.home)
        assertTrue("LB+LT slides the map (the left column)", qv.getValue("MAP").translationX < 0f)
        assertEquals("the chat stays", 0f, qv.getValue("CHAT").translationX, 0.5f)
        assertTrue(Rect(field(q.home, "classRect") as Rect).centerX() < W / 2)
    }

    @Test fun mirroredWithTheToastAndTheGlide() {
        val classic = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
        val a = page(classic)
        val from = boxes(a.home)
        shot(a, "classic_rest")
        val p = page(HomePresets.byId(HomePresets.MIRRORED)!!.apply(classic), party = true, dirs = MIRROR)
        val to = boxes(p.home)
        p.home.toolbarToast(MIRROR)
        settle(5)
        shot(p, "mirrored_toast")
        assertTrue(to.getValue("CHAT").left > to.getValue("MAP").left)
        // the glide: every box between the two on one progress (the page's one motion, HomeView.apply)
        val g = page(classic)
        for (k in listOf(0.25f, 0.5f, 0.75f)) {
            glide(g.home, from, to, k)
            val b = boxes(g.home)
            val f = from.getValue("CHAT"); val t = to.getValue("CHAT")
            assertEquals(f.left + (t.left - f.left) * k, b.getValue("CHAT").left, 0.6f)
            shot(g, "glide_classic_to_mirrored_${(k * 100).toInt()}")
        }
    }

    /** one frame of the page's one motion (HomeView.apply's lerp) between two laid pages, by part */
    @Suppress("UNCHECKED_CAST")
    private fun glide(home: HomeView, a: Map<String, RectF>, b: Map<String, RectF>, k: Float) {
        val now = field(home, "now") as HashMap<Any, RectF>
        val parts = (field(home, "views") as Map<Any, View>).keys
        for (part in parts) {
            val f = a[part.toString()] ?: continue; val t = b[part.toString()] ?: f
            now[part] = RectF(f.left + (t.left - f.left) * k, f.top + (t.top - f.top) * k, f.right + (t.right - f.right) * k, f.bottom + (t.bottom - f.bottom) * k)
        }
        HomeView::class.java.getDeclaredMethod("placeFrame").apply { isAccessible = true }.invoke(home)
    }

    // ---------------------------------------------------------------- phase 3: In fights = Top screen (SPEC section 11, final_10; D1 opt-in)

    @Test fun topScreenKeepsTheRestBoxesWithTheUpCue() {
        val l = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.TOP)
        val rest = boxes(page(l, ct = 1).home)
        val p = page(l, fight = true, ct = 1)
        assertTrue("the fight's takeover is on (the band's In-combat pictures)", p.home.flow.takeover)
        val fight = boxes(p.home)
        for (part in listOf("BARS", "MAP", "QUESTS"))
            assertEquals("$part keeps its rest box in the fight", rest.getValue(part), fight.getValue(part))
        // the chat's top stays (its bottom only gains the tap-to-cast bar's room while the bar hides in the fight, cb7, as today)
        assertEquals("the chat's top", rest.getValue("CHAT").top, fight.getValue("CHAT").top, 0f)
        assertEquals("the band at its rest size (226)", 226f, fight.getValue("BARS").bottom, 0.01f)
        // the 1.5 s "up" plate at the clock spot from the fight's start
        assertTrue("the up cue shows", p.home.topCueAlpha() > 0.9f)
        shot(p, "bottom_fight_top_cue")
        // B in the fight: nothing grew - no shrink, no red reminder (the takeover stays)
        setField(p.home, "grewAt", 0L)
        HomeView::class.java.getDeclaredMethod("onB").apply { isAccessible = true }.invoke(p.home)
        assertTrue("B in the fight does not shrink Top screen", p.home.flow.takeover)
        settle(100)
        assertEquals("the cue gone after 1.5 s", 0f, p.home.topCueAlpha(), 0f)
        shot(p, "bottom_fight_top")
        // the same layout with Grow (D1's default) grows and B shrinks it, as today
        val g = page(l.copy(fight = HomeLayout.FightBand.GROW), fight = true)
        assertEquals(416f, boxes(g.home).getValue("BARS").bottom, 0.01f)
        assertEquals("Grow: no cue", 0f, g.home.topCueAlpha(), 0f)
        setField(g.home, "grewAt", 0L)
        HomeView::class.java.getDeclaredMethod("onB").apply { isAccessible = true }.invoke(g.home)
        assertFalse("Grow: B shrinks", g.home.flow.takeover)
        // the 1515 merge's review (P5): Top screen with the Wings NOT on the top screen (no key while the add-on asks or after Later:
        // no ct; an older add-on; not confirmed yet) grows the band as Grow does - no "up" cue, and B shrinks it
        for (ct in listOf(null, 0)) {
            val n = page(l, fight = true, ct = ct)
            assertTrue(n.home.flow.takeover)
            assertEquals("ct=$ct: the band grows", 416f, boxes(n.home).getValue("BARS").bottom, 0.01f)
            assertEquals("ct=$ct: no up cue", 0f, n.home.topCueAlpha(), 0f)
            setField(n.home, "grewAt", 0L)
            HomeView::class.java.getDeclaredMethod("onB").apply { isAccessible = true }.invoke(n.home)
            assertFalse("ct=$ct: B shrinks", n.home.flow.takeover)
        }
    }

    @Test fun theInFightsTileIsThreeWay() {
        page(HomeLayout())
        val env = BottomTiles.Env(null, null)
        assertEquals("In fights", BottomTiles.NAMES[BottomTiles.COMBAT])
        assertEquals("D1: Grow by default", "Grow", BottomTiles.value(BottomTiles.COMBAT, env))
        assertEquals(listOf("Grow", "Same size", "Top screen"), BottomTiles.lines(BottomTiles.COMBAT, env).single().words)
        BottomTiles.press(BottomTiles.COMBAT, BottomTiles.COMBAT, 2, env) { }
        assertEquals(HomeLayout.FightBand.TOP, HomeSettings.layout.fight)
        assertEquals("Top screen", BottomTiles.value(BottomTiles.COMBAT, env))
        assertEquals(2, BottomTiles.lines(BottomTiles.COMBAT, env).single().picked)
        // an add-on without the flag says so (state only); with it, nothing
        val old = PadMapModel.Dpad(true, emptyMap(), emptyList(), emptyList(), true, 1, 2, "Korvash", "Realm")
        assertEquals("Needs the newer Spells add-on", BottomTiles.status(BottomTiles.COMBAT, BottomTiles.Env(old, null)))
        assertEquals(null, BottomTiles.status(BottomTiles.COMBAT, BottomTiles.Env(old.copy(ct = 1), null)))
        BottomTiles.press(BottomTiles.COMBAT, BottomTiles.COMBAT, 1, env) { }
        assertEquals(HomeLayout.FightBand.SAME, HomeSettings.layout.fight)
        BottomTiles.press(BottomTiles.COMBAT, BottomTiles.COMBAT, 0, env) { }
        assertEquals(HomeLayout.FightBand.GROW, HomeSettings.layout.fight)
        // the Fighter preset from the Home layout tile: the player's own opt-in
        BottomTiles.press(BottomTiles.LAYOUT, BottomTiles.LAYOUT, HomePresets.OFFERED.indexOfFirst { it.id == HomePresets.FIGHTER }, env) { }
        assertEquals(HomeLayout.FightBand.TOP, HomeSettings.layout.fight)
        assertEquals("Fighter", BottomTiles.value(BottomTiles.LAYOUT, env))
        assertTrue("Fighter folds the card", HomeSettings.questMin)
        HomeSettings.questMin = false                      // (HomeSettings keeps its prefs for the whole JVM: the tests after this one)
    }

    @Test fun theSettingsTileNamesTheLayout() {
        page(HomeLayout())
        assertEquals("Classic", BottomTiles.value(BottomTiles.LAYOUT, BottomTiles.Env(null, null)))
        BottomTiles.press(BottomTiles.LAYOUT, BottomTiles.LAYOUT, 1, BottomTiles.Env(null, null)) { }
        assertEquals("Mirrored", BottomTiles.value(BottomTiles.LAYOUT, BottomTiles.Env(null, null)))
        assertTrue(HomeSettings.layout.mirrored)
        BottomTiles.press(BottomTiles.LAYOUT, "chatside", 0, BottomTiles.Env(null, null)) { }
        assertFalse(HomeSettings.layout.mirrored)
        BottomTiles.press(BottomTiles.LAYOUT, "savehere", 2, BottomTiles.Env(null, null)) { }
        assertEquals(HomeSettings.layout.copy(name = "3"), HomeLayoutStore.slots()[2])
        assertEquals(BottomTiles.LAYOUT, BottomTiles.HOME_TILES.first())
        // the bar's place in words that follow the widgets
        BottomTiles.press(BottomTiles.CASTBAR, BottomTiles.CASTBAR, 2, BottomTiles.Env(null, null)) { }
        assertEquals(HomeLayout.CastDock.UNDER_MAP, HomeSettings.layout.cast)
        assertEquals("Under map", BottomTiles.value(BottomTiles.CASTBAR, BottomTiles.Env(null, null)))
    }
}
