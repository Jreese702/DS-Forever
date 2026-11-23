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

    /** a frame of a motion as it stands (one pass, no settle: [shot]'s second pass would move the clock on); the art warmed by a
     *  [shot] before it */
    private fun shotNow(p: Page, name: String) {
        val d = out ?: return
        val b = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        setField(p.home, "clockText", "9:51 AM"); setField(p.home, "batteryPct", 98)
        val c = Canvas(b); c.drawColor(Theme.ink); p.home.draw(c)
        val st = Strip(app) { }
        st.active = Room.HOME
        st.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Strip.HEIGHT, View.MeasureSpec.EXACTLY))
        st.layout(0, 0, W, Strip.HEIGHT)
        c.translate(0f, H.toFloat()); st.draw(c)
        File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun solver(l: HomeLayout, home: HomeView): Map<String, HomeLayoutSolver.Box> {
        val b = boxes(home)
        return b.mapValues { HomeLayoutSolver.Box(it.value.left, it.value.top, it.value.right, it.value.bottom) }
    }

    // ---------------------------------------------------------------- every arrangement (1517's six presets, frozen: HomeArrangements)

    @Test fun eachArrangementLaysAsTheSolverSays() {
        for ((id, l) in HomeArrangements.ALL) {
            for ((state, fight, party) in listOf(Triple("rest", false, false), Triple("fight", true, false), Triple("party", false, true))) {
                val p = page(l, fight = fight, party = party, dirs = if (l.mirrored) MIRROR else null)
                val b = boxes(p.home)
                val chat = b.getValue("CHAT"); val map = b.getValue("MAP")
                assertEquals("$id $state: chat side", l.chatSide == HomeLayout.Side.LEFT, chat.left < map.left)
                val want = (1216f * l.chatShare.num / l.chatShare.den)
                assertEquals("$id $state: chat width", want, chat.width(), 0.6f)
                if (l.cast != HomeLayout.CastDock.OFF && !fight) {
                    val s = b.getValue("STRIP")
                    when (l.cast) {
                        HomeLayout.CastDock.UNDER_CHAT -> assertEquals(chat.left, s.left, 0.01f)
                        HomeLayout.CastDock.UNDER_MAP -> assertEquals(map.left, s.left, 0.01f)
                        else -> assertEquals(8f, s.left, 0.01f)
                    }
                    assertTrue("$id: the slot size", s.height() <= l.castSize.px + 20.01f)
                }
                if (fight && l.fight == HomeLayout.FightBand.GROW) assertEquals("$id: the grow", 416f, b.getValue("BARS").bottom, 0.01f)
                if (party) {
                    val pv = views(p.home).getValue("PARTY") as PartyModuleView
                    assertEquals(l.chatSide == HomeLayout.Side.RIGHT, pv.tabRight)
                    val pb = b.getValue("PARTY")
                    if (l.chatSide == HomeLayout.Side.RIGHT) assertEquals(1240f, pb.right, 0.01f) else assertEquals(0f, pb.left, 0.01f)
                }
                shot(p, "${id.lowercase().replace(' ', '_')}_$state")
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
        val p = page(HomeArrangements.of(HomeArrangements.MIRRORED, classic), party = true, dirs = MIRROR)
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

    /** HomeView's private apply (the page's one motion): [animate] true runs the real animator on the shadow clock (the page is not on
     *  a window here, so changed() alone would lay it at once) */
    private fun applyNow(home: HomeView, s: HomeScreen, animate: Boolean) {
        HomeView::class.java.getDeclaredMethod("apply", HomeScreen::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(home, s, animate)
    }
    private fun frame() { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() }
    /** the animators' duration scale (the system's "animations off" = 0); Robolectric keeps the last one set */
    private fun animScale(k: Float) {
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(null, k)
    }

    /**
     * IN FIGHTS = TOP SCREEN, THE SLIDE (TopSlide; the user, 2026-11-22: "the bottom [band] does not transition out during combat like it
     * should so it shows in both places. It should be a slick animation looking like it slides up to the top screen"; the pick "Chat and
     * map grow up"): with the add-on's Wings on (ct 1) the fight's takeover slides the band up off the page at its rest size while the
     * chat and map columns grow up into the strip; B changes nothing; the band's view stays VISIBLE (its pad reader) but draws nothing
     * once away; the reverse at the fight's end; a reversal mid-way goes on from where the parts are. No Wings (ct null / 0): Grow.
     */
    @Test fun topScreenSlidesTheBandUpAndColumnsGrow() {
        val l = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.TOP)
        val restPage = page(l, ct = 1)
        val rest = boxes(restPage.home)
        val p = page(l, fight = true, ct = 1)
        assertTrue("the fight's takeover is on (the band's In-combat pictures)", p.home.flow.takeover)
        val away = boxes(p.home)
        val rb = rest.getValue("BARS"); val ab = away.getValue("BARS")
        assertEquals("the band above the page: its bottom 16 over the top edge", -16f, ab.bottom, 0.01f)
        assertEquals("the band its rest size (no re-measure)", rb.height(), ab.height(), 0.01f)
        assertEquals(rb.width(), ab.width(), 0.01f)
        assertEquals("the chat's column grown up to the top gap", 8f, away.getValue("CHAT").top, 0.01f)
        assertEquals("the map's", 8f, away.getValue("MAP").top, 0.01f)
        assertEquals("the columns' x unchanged", rest.getValue("CHAT").left, away.getValue("CHAT").left, 0.01f)
        assertEquals(View.VISIBLE, views(p.home).getValue("BARS").visibility)
        assertTrue("the band's view stays shown (its pad reader runs)", p.cast.visibility == View.VISIBLE)
        assertTrue("away: the band draws nothing", p.cast.away)
        assertFalse("away: its cast bar is not the shown one (the rooms' bar takes it)", p.cast.ownCastBarShown())
        assertEquals("Home's band bottom for its followers: 0, never negative", 0f, p.home.barsBottom(), 0f)
        // THE TAP-TO-CAST BAR STAYS (the 11-22 shots: Show in combat Off, the default, had slid it away under the room bar while the band
        // left too - no fight control on the bottom screen): on the page under the grown chat, where it rests, tappable, its fight layer
        assertEquals("(the player's setting: Show in combat Off)", CastStripModel.Combat.HIDE, HomeSettings.castBarCombat)
        val sb = away.getValue("STRIP")
        assertEquals("the bar where it rests", rest.getValue("STRIP"), sb)
        assertTrue("under the grown chat, on the page ($sb)", sb.top >= away.getValue("CHAT").bottom + 8f - 0.01f && sb.bottom <= H + 0.01f)
        val strip = views(p.home).getValue("STRIP") as CastStripView
        assertEquals(View.VISIBLE, strip.visibility)
        assertFalse("not slid away", strip.fightHidden)
        assertTrue("it takes touches in the fight", strip.takesTouch)
        assertTrue("its fight layer shows", strip.fightNow)
        shot(p, "bottom_fight_top_away")
        // B in the fight: nothing changes (no shrink, no red reminder)
        setField(p.home, "grewAt", 0L)
        HomeView::class.java.getDeclaredMethod("onB").apply { isAccessible = true }.invoke(p.home)
        assertTrue("B in the fight does not shrink Top screen", p.home.flow.takeover)
        settle(20)
        assertEquals("B: the band still away", -16f, boxes(p.home).getValue("BARS").bottom, 0.01f)

        // the same layout with Grow (D1's default) grows and B shrinks it, as today
        val g = page(l.copy(fight = HomeLayout.FightBand.GROW), fight = true)
        assertEquals(416f, boxes(g.home).getValue("BARS").bottom, 0.01f)
        assertFalse(g.cast.away)
        assertTrue("Grow keeps Show in combat Off: the bar slid away below the page", boxes(g.home).getValue("STRIP").top >= H - 0.01f)
        assertTrue((views(g.home).getValue("STRIP") as CastStripView).fightHidden)
        setField(g.home, "grewAt", 0L)
        HomeView::class.java.getDeclaredMethod("onB").apply { isAccessible = true }.invoke(g.home)
        assertFalse("Grow: B shrinks", g.home.flow.takeover)
        // the 1515 merge's review (P5): Top screen with the Wings NOT on the top screen (no key; an older add-on; not confirmed) grows the
        // band as Grow does, and B shrinks it
        for (ct in listOf(null, 0)) {
            val n = page(l, fight = true, ct = ct)
            assertTrue(n.home.flow.takeover)
            assertEquals("ct=$ct: the band grows", 416f, boxes(n.home).getValue("BARS").bottom, 0.01f)
            assertFalse("ct=$ct: the band on the page", n.cast.away)
            setField(n.home, "grewAt", 0L)
            HomeView::class.java.getDeclaredMethod("onB").apply { isAccessible = true }.invoke(n.home)
            assertFalse("ct=$ct: B shrinks", n.home.flow.takeover)
        }
    }

    /**
     * the tap-to-cast bar's docks keep working in a Top-screen fight (the 11-22 shots): Above chat rides up with its grown column (at the
     * top gap, the chat under it), Under map stays under the map; Party mode's fight sends the band up too (ruling C), so the bar stays
     * there as well - with Bar in Party mode on or off, Show in combat Off (the 1519 audit: tap-to-cast stays in Top fights "always")
     */
    @Test fun topScreenKeepsTheTapToCastBarInItsDock() {
        val above = page(HomeLayout(cast = HomeLayout.CastDock.ABOVE_CHAT, fight = HomeLayout.FightBand.TOP), fight = true, ct = 1)
        assertTrue(above.cast.away)
        val a = boxes(above.home)
        assertEquals("Above chat: the bar at the top of the grown column", 8f, a.getValue("STRIP").top, 0.01f)
        assertTrue("the chat's column under it", a.getValue("CHAT").top >= a.getValue("STRIP").bottom + 8f - 0.01f)
        assertTrue((views(above.home).getValue("STRIP") as CastStripView).takesTouch)
        shot(above, "bottom_fight_top_away_above_chat")
        val underMap = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_MAP, fight = HomeLayout.FightBand.TOP), fight = true, ct = 1)
        val m = boxes(underMap.home)
        assertTrue("Under map: under the grown map", m.getValue("STRIP").top >= m.getValue("MAP").bottom + 8f - 0.01f && m.getValue("STRIP").bottom <= H + 0.01f)
        assertEquals("in the map's column", m.getValue("MAP").left, m.getValue("STRIP").left, 0.01f)
        assertTrue((views(underMap.home).getValue("STRIP") as CastStripView).takesTouch)
        val party = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.TOP), fight = true, party = true, ct = 1)
        assertTrue("Party mode's fight: the band slides up too (the user's ruling C, 11-22)", party.cast.away)
        val ps = views(party.home).getValue("STRIP") as CastStripView
        assertFalse("Party mode: Bar in Party mode (on by default) keeps it, as ever", ps.fightHidden)
        try {
            HomeSettings.castBarPartyShow = false
            party.home.tick(); settle()
            assertFalse("Party mode, Bar in Party mode off: the Top fight keeps the bar all the same (Show in combat Off)", ps.fightHidden)
            assertTrue("Party mode, Bar in Party mode off: it takes touches", ps.takesTouch)
            val b = boxes(party.home).getValue("STRIP")
            assertTrue("Party mode: the bar on the page ($b)", b.top >= 0f && b.bottom <= H + 0.01f)
        } finally { HomeSettings.castBarPartyShow = true }
    }

    /** the fight's signal as the add-on sends it (hudS.c), then Home's tick: the flow's takeover follows */
    private fun fightSignal(home: HomeView, on: Boolean) {
        home.onFull(JSONObject().put("type", "full").put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true)
            .put("hudS", JSONObject().put("c", on)))), true)
        home.onQuests(quests(on), true)
        home.tick()
    }

    /**
     * THE SLIDE'S REAL MOTION (TopSlide in HomeView.apply), on a window so Home animates its own changes (isShown): the fight's signal
     * comes -> the band slides up off the page on its ease-in while the chat / map columns lead on Motion.curve, sampled every 16 ms on the
     * shadow clock; it lands away (the band's view then draws nothing); the fight ends -> back down; a blip mid-way reverses from where
     * every part is. Shots: bottom_fight_top_slide50 / _away / _return50
     */
    @Config(qualifiers = "w1240dp-h1080dp-mdpi")
    @Test fun topScreenTheSlidesRealFrames() {
        // frames only as the clock moves (16 ms each), as HomeMapControlsShotsTest drives them; the choreographer as it was afterwards
        val delayWas = org.robolectric.shadows.ShadowChoreographer.getFrameDelay()
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        try { slideFrames() } finally {
            org.robolectric.shadows.ShadowChoreographer.setPaused(false)
            org.robolectric.shadows.ShadowChoreographer.setFrameDelay(delayWas)
        }
    }

    private fun slideFrames() {
        val l = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.TOP)
        val p = page(l, ct = 1)
        val home = p.home
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        act.setContentView(home, android.view.ViewGroup.LayoutParams(W, H))
        ShadowLooper.idleMainLooper(); lay(home); settle(10)
        assertTrue("on a window (Home animates its own changes)", home.isShown)
        val rest = boxes(home)
        val rb = rest.getValue("BARS")
        assertTrue("at rest: the band on the page", rb.bottom > 100f && !p.cast.away)
        shot(p, "bottom_fight_top_rest")                                 // (warms the art for the motion's frames)
        // the fight: up
        fightSignal(home, true)
        assertTrue("the fight's takeover", home.flow.takeover)
        val bandB = ArrayList<Float>(); val chatT = ArrayList<Float>()
        var shotMid = false
        for (i in 0 until 24) {
            frame()
            val b = boxes(home)
            bandB += b.getValue("BARS").bottom; chatT += b.getValue("CHAT").top
            pageFrame("up frame $i", b, rest)
            if (!shotMid && b.getValue("BARS").bottom < (rb.bottom - 16f) / 2f) { shotMid = true; shotNow(p, "bottom_fight_top_slide50") }
            if (i == 3) shotNow(p, "bottom_fight_top_slide25")
        }
        for (i in 1 until bandB.size) assertTrue("up: the band never goes back down (frame $i: $bandB)", bandB[i] <= bandB[i - 1] + 0.01f)
        for (i in 1 until chatT.size) assertTrue("up: the chat's top never goes back down (frame $i: $chatT)", chatT[i] <= chatT[i - 1] + 0.01f)
        assertTrue("on its way for ~240 ms (frames: $bandB)", bandB[4] > -15f && bandB.last() == -16f)
        val end = boxes(home)
        assertEquals("landed away", -16f, end.getValue("BARS").bottom, 0.01f)
        assertEquals("the chat grown up", 8f, end.getValue("CHAT").top, 0.01f)
        assertEquals("the map grown up", 8f, end.getValue("MAP").top, 0.01f)
        assertTrue("landed: the band's view draws nothing", p.cast.away)
        // one curve: the band eases in (slow off the mark) and the chat's top rides its foot every frame (never over the band)
        val f4b = (rb.bottom - bandB[3]) / (rb.bottom + 16f)
        assertTrue("the band eases in (slow off the mark: $f4b at 64 ms of 240)", f4b < 64f / 240f)
        for (i in bandB.indices) assertTrue("frame $i: the chat's top ${chatT[i]} under the band's foot ${bandB[i]}", chatT[i] >= bandB[i] + 8f - 0.01f)
        shot(p, "bottom_fight_top_away")
        // the fight's end: back down (the band's view draws again from the first frame) - after the HOLD: the band waits above the page
        // until the add-on's Wings are mostly off the top screen (TopSlide.DOWN_HOLD_MS; the 11-22 check sheet: at end + 48 ms both
        // showed), the columns waiting grown with it (one motion)
        fightSignal(home, false)
        var offAt: Int? = if (!home.flow.takeover) -1 else null
        var downAt = -1
        val downB = ArrayList<Float>()
        for (i in 0 until 400) {
            frame()
            if (i % 8 == 0) home.tick()
            if (offAt == null && !home.flow.takeover) offAt = i
            val bx = boxes(home)
            val b = bx.getValue("BARS").bottom
            if (offAt != null && downAt < 0 && b <= -16f + 0.01f) {
                assertEquals("frame $i inside the hold: the columns wait grown with the band", 8f, bx.getValue("CHAT").top, 0.01f)
                // the band still above the page: its cast bar stays given to the rooms' bar (the player's cast seen on the bottom screen)
                assertTrue("frame $i inside the hold: the band still away", p.cast.away)
                assertFalse(p.cast.ownCastBarShown())
            }
            if (downAt < 0 && b > 0.01f) { downAt = i; assertFalse("the down-slide's first frame on the page: the band draws again", p.cast.away) }
            if (downAt >= 0) { downB += b; pageFrame("down frame $i", bx, rest); if (downB.size == 6) shotNow(p, "bottom_fight_top_return50") }
            if (downAt >= 0 && downB.size > 24) break
        }
        assertTrue("the fight's end brought the band back down", downAt >= 0 && !home.flow.takeover)
        val heldMs = (downAt - offAt!!) * 16L
        assertTrue("the way down handed back: the band on the page $heldMs ms after the fight's end (hold ${TopSlide.HAND_BACK_MS})",
            heldMs >= TopSlide.HAND_BACK_MS && heldMs <= TopSlide.HAND_BACK_MS + 64L)
        // ruling D: its first frames on the page are small (it starts from the edge on a slow-start curve), never a drop-in
        assertTrue("the band's first frame on the page is small (${downB.first()} px)", downB.first() <= 8f)
        assertTrue("and its second (${downB[1]} px)", downB[1] <= 40f)
        for (i in 1 until downB.size) assertTrue("down: the band never goes back up ($downB)", downB[i] >= downB[i - 1] - 0.01f)
        val landed = boxes(home)
        for (part in listOf("BARS", "CHAT", "MAP")) assertEquals("$part back at rest", rest.getValue(part), landed.getValue(part))
        // a blip: the fight comes and goes mid-slide - the motion turns where the parts are (no jump) and the band never goes away
        fightSignal(home, true)
        for (i in 0 until 7) frame()
        val at = boxes(home)
        assertTrue("mid-way up", at.getValue("BARS").bottom < rb.bottom - 1f && at.getValue("BARS").bottom > -15f)
        // (B gives the fight back out of the fight's lockdown: the takeover goes; here the signal's end does it)
        fightSignal(home, false)
        var turned: Map<String, RectF>? = null
        for (i in 0 until 400) {
            frame()
            if (i % 8 == 0) home.tick()
            if (!home.flow.takeover) { frame(); turned = boxes(home); break }
        }
        val next = turned ?: error("the blip never ended")
        for (part in listOf("BARS", "CHAT", "MAP")) {
            val a = at.getValue(part); val n = next.getValue(part)
            assertTrue("$part: no jump at the turn (${a.top} -> ${n.top})", kotlin.math.abs(n.top - a.top) < 60f)
        }
        for (i in 0 until 30) frame()
        assertEquals("the blip over: at rest", rest.getValue("BARS"), boxes(home).getValue("BARS"))
        assertFalse("cut short: the band's view never went away", p.cast.away)
        downHoldReversals(p, rest)
        reLayMidSlide(p, rb)
        moduleInATopFight(p)
        lateCutHolds(p, rest)
        // the hold is WALL-clock time (the 11-22 review: folded into the animator it was scaled by the system's animator duration scale,
        // the add-on's Wings never are): at 0.5x the band still waits HAND_BACK_MS after the fight's end, then slides at the scaled pace
        animScale(0.5f)
        try {
            fightSignal(home, true)
            for (i in 0 until 24) frame()
            assertTrue("0.5x: landed away", p.cast.away)
            val held = heldDown(home, rest)
            assertTrue("0.5x: the band waited the whole wall-clock hold ($held ms, hold ${TopSlide.HAND_BACK_MS})",
                held >= TopSlide.HAND_BACK_MS && held <= TopSlide.HAND_BACK_MS + 64L)
        } finally { animScale(1f) }
        // animations off (the system's duration scale 0): the band jumps - up at once; down after the same wall-clock hold
        animScale(0f)
        try {
            fightSignal(home, true)
            frame()
            assertEquals("animations off: away at once", -16f, boxes(home).getValue("BARS").bottom, 0.01f)
            assertTrue(p.cast.away)
            val held = heldDown(home, rest)
            assertTrue("animations off: the band jumped back only after the Wings' hold ($held ms, hold ${TopSlide.HAND_BACK_MS})",
                held >= TopSlide.HAND_BACK_MS && held <= TopSlide.HAND_BACK_MS + 32L)
            frame()
            assertEquals("animations off: back at rest in one jump", rest.getValue("BARS"), boxes(home).getValue("BARS"))
        } finally { animScale(1f) }
    }

    /** the band laid away: the fight ends; the ms (16 ms frames, from the takeover's drop) the band stays above the page, every one of
     *  them with the columns grown and its cast given to the rooms' bar; then it comes down and lands at rest */
    private fun heldDown(home: HomeView, rest: Map<String, RectF>): Long {
        fightSignal(home, false)
        var offAt: Int? = if (!home.flow.takeover) -1 else null
        var downAt = -1
        for (i in 0 until 400) {
            frame()
            if (i % 8 == 0) home.tick()
            if (offAt == null && !home.flow.takeover) offAt = i
            if (offAt != null && boxes(home).getValue("BARS").bottom > 0.01f) { downAt = i; break }
            if (offAt != null) assertEquals("inside the hold (frame $i): the columns wait grown", 8f, boxes(home).getValue("CHAT").top, 0.01f)
        }
        assertTrue("the band came down", downAt >= 0 && offAt != null)
        for (i in 0 until 40) frame()
        assertEquals("landed at rest", rest.getValue("BARS"), boxes(home).getValue("BARS"))
        return (downAt - offAt!!) * 16L
    }

    /**
     * the 11-22 review: the fight's end LATE in the up-slide (a one-hit kill 200-230 ms in) - the band 90 % off the page, the Wings
     * nearly all in on the top screen. It turned back down at once (no hold: the band had not landed), so both showed for several frames.
     * Now it holds where it stands for TopSlide.reversalHoldMs of the up-slide's age (the Wings reversed from where they had got, mostly
     * off), the columns with it, then comes down; an early cut (the blip above) turns back about at once.
     */
    private fun lateCutHolds(p: Page, rest: Map<String, RectF>) {
        val home = p.home
        val t0 = android.os.SystemClock.uptimeMillis()
        fightSignal(home, true)
        assertTrue(home.flow.takeover)
        for (i in 0 until 20) { frame(); if (boxes(home).getValue("BARS").bottom < 30f) break }
        val cut = boxes(home)
        val cb = cut.getValue("BARS").bottom
        assertTrue("late in the up-slide: mostly off the page, not landed ($cb)", cb < 30f && cb > -15f)
        fightSignal(home, false)
        assertFalse("the fight's end heard inside the up-slide", home.flow.takeover)
        val upMs = android.os.SystemClock.uptimeMillis() - t0
        val want = TopSlide.reversalHoldMs(upMs)
        assertTrue("a late cut holds ($want ms at $upMs ms)", want > 0L && want <= TopSlide.HAND_BACK_MS)
        var movedAt = -1
        for (i in 0 until 60) {
            frame()
            val b = boxes(home)
            if (b.getValue("BARS") != cut.getValue("BARS")) { movedAt = i; break }
            assertEquals("held (frame $i): the chat waits with the band", cut.getValue("CHAT"), b.getValue("CHAT"))
        }
        val heldMs = (movedAt + 1) * 16L
        assertTrue("the band held where it stood until the Wings were mostly off ($heldMs ms, want $want)", movedAt >= 0 && heldMs >= want)
        assertTrue("then came down without waiting more ($heldMs, want $want; the animator's first frames as the hold test allows)", heldMs <= want + 64L)
        val b1 = boxes(home).getValue("BARS").bottom
        // (the first move: at most the way down's first frames from where it stood, not a jump)
        val first = (rest.getValue("BARS").bottom - cb) * TopSlide.partK(48f / Motion.TOP_SLIDE_DOWN, up = false)
        assertTrue("down from where it stood, no jump ($cb -> $b1, at most $first)", b1 >= cb - 0.01f && b1 - cb <= first)
        for (i in 0 until 40) frame()
        assertEquals("back at rest", rest.getValue("BARS"), boxes(home).getValue("BARS"))
        assertFalse(p.cast.away)
    }

    /**
     * the 11-22 shots, Above chat: the tap-to-cast bar rides its column up and down with the slide on the real animator - every frame
     * under the band's foot, the column's next part under it, nothing behind the room bar; it stays touchable once landed in the fight.
     */
    @Config(qualifiers = "w1240dp-h1080dp-mdpi")
    @Test fun topScreenAboveChatBarRidesItsColumn() {
        val delayWas = org.robolectric.shadows.ShadowChoreographer.getFrameDelay()
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        try {
            val p = page(HomeLayout(cast = HomeLayout.CastDock.ABOVE_CHAT, fight = HomeLayout.FightBand.TOP), ct = 1)
            val home = p.home
            val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
            act.setContentView(home, android.view.ViewGroup.LayoutParams(W, H))
            ShadowLooper.idleMainLooper(); lay(home); settle(10)
            val rest = boxes(home)
            assertEquals("at rest: the bar under the band", rest.getValue("BARS").bottom + 8f, rest.getValue("STRIP").top, 0.01f)
            val strip = views(home).getValue("STRIP") as CastStripView
            fun check(what: String) {
                val b = boxes(home)
                val band = b.getValue("BARS"); val st = b.getValue("STRIP")
                assertTrue("$what: the bar under the band's foot (${st.top} vs ${band.bottom})", st.top >= band.bottom + 8f - 0.01f)
                assertTrue("$what: the chat's column under the bar", b.getValue("CHAT").top >= st.bottom + 8f - 0.01f)
                for ((part, r) in b) if (part != "BARS") assertTrue("$what: $part never below the page ($r)", r.bottom <= H + 0.01f)
            }
            fightSignal(home, true)
            val tops = ArrayList<Float>()
            for (i in 0 until 24) { frame(); check("up frame $i"); tops += boxes(home).getValue("STRIP").top }
            for (i in 1 until tops.size) assertTrue("up: the bar rides up, never down ($tops)", tops[i] <= tops[i - 1] + 0.01f)
            assertEquals("landed: at the top gap", 8f, boxes(home).getValue("STRIP").top, 0.01f)
            assertTrue("landed: touchable in the fight", strip.takesTouch && !strip.fightHidden)
            fightSignal(home, false)
            for (i in 0 until 400) {
                frame(); if (i % 8 == 0) home.tick(); check("down frame $i")
                if (!home.flow.takeover && boxes(home).getValue("STRIP") == rest.getValue("STRIP")) break
            }
            for (i in 0 until 20) frame()
            assertEquals("back at rest", rest.getValue("STRIP"), boxes(home).getValue("STRIP"))
        } finally {
            org.robolectric.shadows.ShadowChoreographer.setPaused(false)
            org.robolectric.shadows.ShadowChoreographer.setFrameDelay(delayWas)
        }
    }

    /** one frame of a Top-screen slide: nothing goes behind the room bar (every part's box over the page's foot; the band leaves by the
     *  TOP edge), and the tap-to-cast bar under the chat stays where it rests, seen and touchable (the 11-22 shots: it slid down behind
     *  the room bar, clipped, mid-slide) */
    private fun pageFrame(what: String, b: Map<String, RectF>, rest: Map<String, RectF>) {
        for ((part, r) in b) if (part != "BARS") assertTrue("$what: $part never below the page, behind the room bar ($r)", r.bottom <= H + 0.01f)
        assertEquals("$what: the bar under the chat stays put", rest.getValue("STRIP"), b.getValue("STRIP"))
        assertTrue("$what: the band leaves by the top edge", b.getValue("BARS").bottom <= rest.getValue("BARS").bottom + 0.01f)
    }

    /**
     * the hold and the reversals (the 11-22 check sheet): a fight back INSIDE the down-slide's hold keeps the band above the page (it
     * never showed); a fight back ON the way down (past the hold) turns the band back up at once from where it stands - no hold, no jump
     */
    private fun downHoldReversals(p: Page, rest: Map<String, RectF>) {
        val home = p.home
        fightSignal(home, true)
        for (i in 0 until 24) frame()
        assertTrue(p.cast.away)
        fightSignal(home, false)
        for (i in 0 until 40) { if (!home.flow.takeover) break; frame(); if (i % 8 == 0) home.tick() }
        assertFalse(home.flow.takeover)
        for (i in 0 until 5) {
            frame()
            assertEquals("inside the hold (frame $i): the band waits above the page", -16f, boxes(home).getValue("BARS").bottom, 0.01f)
        }
        fightSignal(home, true)
        assertTrue(home.flow.takeover)
        for (i in 0 until 24) {
            frame()
            assertEquals("back in the fight inside the hold (frame $i): the band never came down", -16f, boxes(home).getValue("BARS").bottom, 0.01f)
            assertEquals(8f, boxes(home).getValue("CHAT").top, 0.01f)
        }
        assertTrue("landed away again", p.cast.away)
        // on the way down, past the hold: the fight comes back -> up at once from where it stands
        fightSignal(home, false)
        var b0 = -16f
        for (i in 0 until 60) { frame(); if (i % 8 == 0) home.tick(); b0 = boxes(home).getValue("BARS").bottom; if (b0 > 20f) break }
        assertTrue("on its way down ($b0)", b0 > 20f && b0 < rest.getValue("BARS").bottom - 1f)
        fightSignal(home, true)
        assertTrue(home.flow.takeover)
        val ups = ArrayList<Float>()
        for (i in 0 until 24) { frame(); ups += boxes(home).getValue("BARS").bottom }
        assertTrue("no jump at the turn (${ups[0]} from $b0)", kotlin.math.abs(ups[0] - b0) < 30f)
        assertTrue("turned back up at once, no hold ($ups)", ups[4] < b0 - 0.5f)
        for (i in 1 until ups.size) assertTrue("up again: never back down ($ups)", ups[i] <= ups[i - 1] + 0.01f)
        assertEquals("landed away", -16f, ups.last(), 0.01f)
        assertTrue(p.cast.away)
        fightSignal(home, false)
        for (i in 0 until 400) { frame(); if (i % 8 == 0) home.tick(); if (!home.flow.takeover && boxes(home).getValue("BARS") == rest.getValue("BARS")) break }
        for (i in 0 until 20) frame()
        assertEquals("back at rest", rest.getValue("BARS"), boxes(home).getValue("BARS"))
        assertFalse(p.cast.away)
    }

    /**
     * the 11-22 review: a re-lay INSIDE the up-slide (the same tick's edit-mode park, a card, the aura pane, the cast bar's side, the XP
     * track's inset) cancelled the slide and its glide never set CastView.away: the band sat off the page claiming the cast bar for the
     * whole fight. Now the re-lay goes on with the same slide (its time left, its curve: the band never back down, the chat under its
     * foot, landed by the slide's 240 ms) and whatever lands away sets away - a re-lay in the very first frame (fraction 0) too.
     */
    private fun reLayMidSlide(p: Page, rb: RectF) {
        val home = p.home
        for (cutAt in listOf(0, 5)) {
            fightSignal(home, true)
            assertTrue(home.flow.takeover)
            val bandB = ArrayList<Float>(); val chatT = ArrayList<Float>()
            for (i in 0 until cutAt) { frame(); boxes(home).let { bandB += it.getValue("BARS").bottom; chatT += it.getValue("CHAT").top } }
            applyNow(home, home.flow.screen, animate = true)                 // the re-lay, the band's way unchanged
            val a = field(home, "anim") as android.animation.ValueAnimator
            assertTrue("cut at $cutAt: still the slide's linear time (its curve per part)", a.interpolator is android.view.animation.LinearInterpolator)
            assertTrue("cut at $cutAt: the slide's time LEFT (${a.duration} ms)", a.duration <= TopSlide.COLS_UP_MS - cutAt * 16L + 17L)
            for (i in 0 until 24) { frame(); boxes(home).let { bandB += it.getValue("BARS").bottom; chatT += it.getValue("CHAT").top } }
            for (i in 1 until bandB.size) assertTrue("cut at $cutAt: the band never back down ($bandB)", bandB[i] <= bandB[i - 1] + 0.01f)
            for (i in bandB.indices) assertTrue("cut at $cutAt: frame $i chat ${chatT[i]} under the band's foot ${bandB[i]}", chatT[i] >= bandB[i] + 8f - 0.01f)
            assertTrue("cut at $cutAt: the band left the page's top ($bandB)", bandB.first() < rb.bottom + 0.01f)
            assertEquals("cut at $cutAt: landed away", -16f, boxes(home).getValue("BARS").bottom, 0.01f)
            assertTrue("cut at $cutAt: the band's view is away (it gives up its cast bar)", p.cast.away)
            assertFalse("cut at $cutAt: its cast bar is not the shown one", p.cast.ownCastBarShown())
            // a re-lay once landed (laid away already): away stays
            applyNow(home, home.flow.screen, animate = true)
            for (i in 0 until 20) frame()
            assertTrue("cut at $cutAt: a later re-lay keeps it away", p.cast.away)
            fightSignal(home, false)
            for (i in 0 until 400) { frame(); if (i % 8 == 0) home.tick(); if (!home.flow.takeover && boxes(home).getValue("BARS") == rb) break }
            for (i in 0 until 20) frame()
            assertEquals("cut at $cutAt: back at rest", rb, boxes(home).getValue("BARS"))
            assertFalse(p.cast.away)
        }
    }

    /**
     * the 11-22 review: a module opened by touch in a Top-screen fight and closed again. The touch no longer minimizes the grow (nothing
     * grew; the Wings stay up top), so the close brings the takeover straight back: the band slides back above the page and is away - not
     * left at its rest box with a red reminder under the Wings. The open / close themselves keep the module's time and curve (the map's
     * morph in step with its box), never the slide's.
     */
    private fun moduleInATopFight(p: Page) {
        val home = p.home
        fightSignal(home, true)
        for (i in 0 until 24) frame()
        assertTrue(p.cast.away)
        assertTrue("the small map opens in the fight", home.openMapModule())
        assertEquals(HomeModule.MAP, home.flow.full)
        assertFalse(home.flow.takeover)
        val a = field(home, "anim") as android.animation.ValueAnimator
        assertFalse("the open: the module's curve, not the slide's linear time", a.interpolator is android.view.animation.LinearInterpolator)
        // (the takeover going as the module opens: the fight-time open's own COMBAT_SHRINK, as with Grow - never the slide's)
        assertEquals("the open: the fight-time open's time, not the slide's", Motion.COMBAT_SHRINK, a.duration)
        assertFalse("the open: the band no longer away", p.cast.away)
        for (i in 0 until 24) frame()
        home.homeDisc()                                                    // closed (every close route ends in changed())
        assertNull(home.flow.full)
        assertTrue("closed in the Top fight: the takeover is back at once", home.flow.takeover)
        val c = field(home, "anim") as android.animation.ValueAnimator
        assertFalse("the close: the module's curve", c.interpolator is android.view.animation.LinearInterpolator)
        for (i in 0 until 24) frame()
        home.tick()
        for (i in 0 until 4) frame()
        assertEquals("closed: the band back above the page", -16f, boxes(home).getValue("BARS").bottom, 0.01f)
        assertTrue("closed: away (the cast bar given up)", p.cast.away)
        assertFalse("no red reminder (nothing was minimized)", field(home, "redShown") as Boolean)
        fightSignal(home, false)
        for (i in 0 until 400) { frame(); if (i % 8 == 0) home.tick(); if (!home.flow.takeover && !p.cast.away && boxes(home).getValue("BARS").bottom > 100f) break }
        for (i in 0 until 20) frame()
    }

    /**
     * PARTY MODE KEEPS THE TOP SCREEN (the user's ruling C, 11-22: "It should stay at the top in party mode but the party bars should
     * fill the area"): a fight with Party mode on and In fights = Top screen slides the band up (the Wings carry the Party layer up top)
     * and the party module fills the freed strip - its well from the top gap down to the foot it had in a Party fight, the rows taller
     * (three rows of ~163 px), the chat where it was, the map grown up; the plates' touches follow the bigger cells. Out of the fight: at
     * rest as ever. Shots: bottom_fight_top_party, its real frames _start / _mid / _end and the way back's _return.
     */
    @Test fun topScreenPartyModeFillsTheStrip() {
        val l = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.TOP)
        val p = page(l, fight = true, party = true, ct = 1)
        val b = boxes(p.home)
        assertTrue("the band away", p.cast.away)
        assertEquals(-16f, b.getValue("BARS").bottom, 0.01f)
        val pb = b.getValue("PARTY")
        assertEquals("the party well from the top gap", 8f, pb.top, 0.01f)
        assertEquals("its foot where a Party fight has it (234 + 3 rows of 88)", 538f, pb.bottom, 0.01f)
        assertEquals("the chat where it was", 546f, b.getValue("CHAT").top, 0.01f)
        assertEquals("the map grown up", 8f, b.getValue("MAP").top, 0.01f)
        val pv = views(p.home).getValue("PARTY") as PartyModuleView
        assertEquals(PartyLayout.Mode.FIGHT_TOP, pv.mode)
        val m = pv.module()
        assertEquals("the rows fill the strip", (530f - 24f - 16f) / 3f, m.cells[0].h, 0.05f)
        assertEquals("the well is the view", pv.height.toFloat(), m.well.b, 1f)
        shot(p, "bottom_fight_top_party")
        // Grow keeps b1e28506's Party fight (the band on the page, FIGHT_ON rows)
        val g = page(l.copy(fight = HomeLayout.FightBand.GROW), fight = true, party = true, ct = 1)
        assertFalse(g.cast.away)
        assertEquals(234f, boxes(g.home).getValue("PARTY").top, 0.01f)
        assertEquals(PartyLayout.Mode.FIGHT_ON, (views(g.home).getValue("PARTY") as PartyModuleView).mode)
    }

    /**
     * THE 1519 INTEGRATION (feat/home-apps2's party docks x ruling C's fill): a Party-mode Top fight fills the freed strip for EVERY
     * party dock - the top or the foot of the chat's or the map's column, either side - in the column the party list lives in: its box
     * grows by the strip the band freed (234 -> 8), the column's other part keeps the height it has in the Party fight with the band on
     * the page (Grow: b1e28506's), a top dock's well from the top gap, a foot dock's foot where it was with the chat / map ridden up to
     * the top gap; the module's rows fill its view, its tab on its column's outer edge. Shots: bottom_fight_top_party_<dock>_<side>.
     */
    @Test fun topScreenPartyModeFillsTheStripForEveryDock() {
        for (dock in HomeLayout.PartyDock.values()) for (side in HomeLayout.Side.values()) {
            val l = HomeLayout(chatSide = side, cast = HomeLayout.CastDock.UNDER_CHAT, party = dock, fight = HomeLayout.FightBand.TOP)
            val tag = "$dock chat ${side.name.lowercase()}"
            val g = page(l.copy(fight = HomeLayout.FightBand.GROW), fight = true, party = true, ct = 1)
            val ref = boxes(g.home)
            assertFalse("$tag: the Party fight with the band on the page", g.cast.away)
            val p = page(l, fight = true, party = true, ct = 1)
            val b = boxes(p.home)
            assertTrue("$tag: the band away", p.cast.away)
            assertEquals(-16f, b.getValue("BARS").bottom, 0.01f)
            val pb = b.getValue("PARTY"); val rp = ref.getValue("PARTY")
            val part = if (dock.column == HomeModule.CHAT) "CHAT" else "MAP"
            val other = if (part == "CHAT") "MAP" else "CHAT"
            assertEquals("$tag: the party list takes the freed strip", rp.height() + 226f, pb.height(), 0.5f)
            assertEquals("$tag: the $part keeps its height", ref.getValue(part).height(), b.getValue(part).height(), 0.5f)
            if (dock.top) {
                assertEquals("$tag: the well from the top gap", 8f, pb.top, 0.01f)
                assertEquals("$tag: its foot where the Party fight has it", rp.bottom, pb.bottom, 0.01f)
            } else {
                assertEquals("$tag: the $part ridden up to the top gap", 8f, b.getValue(part).top, 0.01f)
                assertEquals("$tag: the well's foot where it was", rp.bottom, pb.bottom, 0.01f)
            }
            assertEquals("$tag: the $other grown up", 8f, b.getValue(other).top, 0.01f)
            val col = b.getValue(part)
            assertTrue("$tag: the party list in its column ($pb / $col)", if (HomeLayoutSolver.partyRight(l)) pb.left >= col.left - 0.5f
                else pb.right <= col.right + 0.5f)
            val pv = views(p.home).getValue("PARTY") as PartyModuleView
            assertEquals(PartyLayout.Mode.FIGHT_TOP, pv.mode)
            assertEquals("$tag: the tab on its column's outer edge", HomeLayoutSolver.partyRight(l), pv.tabRight)
            val m = pv.module()
            assertEquals("$tag: the rows fill the view", pv.height.toFloat(), m.well.b, 1f)
            assertTrue("$tag: taller rows than the Party fight's", m.cells[0].h > PartyLayout.FIGHT_H + 40f)
            shot(p, "bottom_fight_top_party_${dock.name.lowercase()}_${side.name.lowercase()}")
        }
    }

    @Config(qualifiers = "w1240dp-h1080dp-mdpi")
    @Test fun topScreenPartyModeSlidesRealFrames() {
        val delayWas = org.robolectric.shadows.ShadowChoreographer.getFrameDelay()
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        try {
            val l = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.TOP)
            val p = page(l, party = true, ct = 1)
            val home = p.home
            val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
            act.setContentView(home, android.view.ViewGroup.LayoutParams(W, H))
            ShadowLooper.idleMainLooper(); lay(home); settle(10)
            val rest = boxes(home)
            val pv = views(home).getValue("PARTY") as PartyModuleView
            assertEquals(PartyLayout.Mode.REST, pv.mode)
            assertFalse(p.cast.away)
            shot(p, "bottom_fight_top_party_start")
            fightSignal(home, true)
            assertFalse("Party mode: no takeover", home.flow.takeover)
            val partyT = ArrayList<Float>(); val rowH = ArrayList<Float>()
            var mid = false
            for (i in 0 until 26) {
                frame()
                val bx = boxes(home)
                val band = bx.getValue("BARS").bottom
                val pt = bx.getValue("PARTY").top
                partyT += pt
                val m = pv.module()
                rowH += m.cells[0].h
                assertTrue("up frame $i: the party module under the band's foot ($pt vs $band)", pt >= band + 8f - 0.01f)
                assertEquals("up frame $i: the rows fill the moving box", pv.height.toFloat(), m.well.b, 1.5f)
                if (!mid && pt < (rest.getValue("PARTY").top + 8f) / 2f) { mid = true; shotNow(p, "bottom_fight_top_party_mid") }
            }
            for (i in 1 until partyT.size) assertTrue("up: never back down ($partyT)", partyT[i] <= partyT[i - 1] + 0.01f)
            for (i in 1 until rowH.size) assertTrue("up: the rows only grow ($rowH)", rowH[i] >= rowH[i - 1] - 0.05f)
            // the columns ease into the top gap (ruling D): the last moving frame small
            val moves = partyT.zipWithNext { a, c -> a - c }.filter { it > 0.01f }
            assertTrue("the arrival eases (the last moving frame ${moves.last()} px of $moves)", moves.last() < 6f)
            assertTrue(p.cast.away)
            assertEquals(8f, boxes(home).getValue("PARTY").top, 0.01f)
            assertEquals(PartyLayout.Mode.FIGHT_TOP, pv.mode)
            assertFalse("landed: the rows no longer stretched", pv.stretch)
            shot(p, "bottom_fight_top_party_end")
            // the fight's end: the hold, then back down with the rows shrinking with the box - to the rest's 64 with no jump
            fightSignal(home, false)
            val downT = ArrayList<Float>(); val downH = ArrayList<Float>()
            for (i in 0 until 60) {
                frame()
                if (i % 8 == 0) home.tick()
                val bx = boxes(home)
                downT += bx.getValue("PARTY").top; downH += pv.module().cells[0].h
                assertTrue("down frame $i: the party module under the band's foot", bx.getValue("PARTY").top >= bx.getValue("BARS").bottom + 8f - 0.01f)
                if (downT.size == 14) shotNow(p, "bottom_fight_top_party_return")
            }
            for (i in 1 until downT.size) assertTrue("down: never back up ($downT)", downT[i] >= downT[i - 1] - 0.01f)
            for (i in 1 until downH.size) assertTrue("down: the rows only shrink, no jump ($downH)", downH[i] <= downH[i - 1] + 0.05f && downH[i - 1] - downH[i] < 30f)
            for (i in 0 until 20) frame()
            assertEquals("back at rest", rest.getValue("PARTY"), boxes(home).getValue("PARTY"))
            assertEquals(PartyLayout.Mode.REST, pv.mode)
            assertEquals(PartyLayout.REST_H, pv.module().cells[0].h, 0.05f)
            assertFalse(p.cast.away)
        } finally {
            org.robolectric.shadows.ShadowChoreographer.setPaused(false)
            org.robolectric.shadows.ShadowChoreographer.setFrameDelay(delayWas)
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
        // Top screen from the In fights tile: the player's own opt-in (1518: no Fighter preset; the card's fold is left alone)
        HomeSettings.questMin = false
        BottomTiles.press(BottomTiles.COMBAT, BottomTiles.COMBAT, 2, env) { }
        assertEquals(HomeLayout.FightBand.TOP, HomeSettings.layout.fight)
        assertFalse("nothing folds the card", HomeSettings.questMin)
    }

    /** 1518: Settings' Home tiles name no layout - "Edit Home" first (its A opens edit mode, no value word, no band), "Reset Home" last
     *  with its two buttons (this character's Home back to Classic; this Home for all characters) */
    @Test fun theSettingsTilesNameNoLayout() {
        page(HomeArrangements.of(HomeArrangements.BIG_CHAT, HomeLayout()).swapped())
        val env = BottomTiles.Env(null, null)
        assertEquals(BottomTiles.LAYOUT, BottomTiles.HOME_TILES.first())
        assertEquals("Edit Home", BottomTiles.NAMES[BottomTiles.LAYOUT])
        assertEquals("", BottomTiles.value(BottomTiles.LAYOUT, env))
        assertTrue(BottomTiles.lines(BottomTiles.LAYOUT, env).isEmpty())
        assertEquals(BottomTiles.HOME_RESET, BottomTiles.HOME_TILES.last())
        assertEquals(listOf("Reset Home to Classic", "Use this Home for all characters"),
            BottomTiles.lines(BottomTiles.HOME_RESET, env).map { it.words.single() })
        // no whole character key: Reset is not offered (nothing is written without one); a character's own Home resets
        assertFalse(BottomTiles.resetLines().first { it.key == BottomTiles.HOME_CLASSIC }.enabled)
        HomeLayoutStore.character("Korvash-Realm")
        assertTrue(HomeSettings.layout.mirrored)
        BottomTiles.press(BottomTiles.HOME_RESET, BottomTiles.HOME_CLASSIC, 0, BottomTiles.Env(null, "Korvash-Realm")) { }
        assertFalse(HomeSettings.layout.mirrored)
        assertTrue(HomeSettings.layout.classicPlace)
        assertEquals(HomeLayout.Share.HALF, HomeSettings.layout.chatShare)
        // the bar's place in words that follow the widgets
        BottomTiles.press(BottomTiles.CASTBAR, BottomTiles.CASTBAR, 2, BottomTiles.Env(null, null)) { }
        assertEquals(HomeLayout.CastDock.UNDER_MAP, HomeSettings.layout.cast)
        assertEquals("Under map", BottomTiles.value(BottomTiles.CASTBAR, BottomTiles.Env(null, null)))
    }
}
