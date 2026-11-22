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
        p.home.toolbarToast(null, MIRROR)
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
        // the fight's end: back down (the band's view draws again from the first frame)
        fightSignal(home, false)
        var downAt = -1
        val downB = ArrayList<Float>()
        for (i in 0 until 400) {
            frame()
            if (i % 8 == 0) home.tick()
            val b = boxes(home).getValue("BARS").bottom
            if (downAt < 0 && b > -16f + 0.01f) { downAt = i; assertFalse("the down-slide's start: the band draws again", p.cast.away) }
            if (downAt >= 0) { downB += b; if (downB.size == 6) shotNow(p, "bottom_fight_top_return50") }
            if (downAt >= 0 && downB.size > 24) break
        }
        assertTrue("the fight's end brought the band back down", downAt >= 0 && !home.flow.takeover)
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
        reLayMidSlide(p, rb)
        moduleInATopFight(p)
        // animations off (the system's duration scale 0): the band jumps
        animScale(0f)
        try {
            fightSignal(home, true)
            frame()
            assertEquals("animations off: away at once", -16f, boxes(home).getValue("BARS").bottom, 0.01f)
            assertTrue(p.cast.away)
        } finally { animScale(1f) }
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
            assertTrue("cut at $cutAt: the slide's time LEFT (${a.duration} ms)", a.duration <= Motion.TOP_SLIDE_UP - cutAt * 16L + 17L)
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
