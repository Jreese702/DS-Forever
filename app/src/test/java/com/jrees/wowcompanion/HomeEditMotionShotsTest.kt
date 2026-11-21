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
import kotlin.math.abs
import kotlin.math.hypot

/**
 * THE iPHONE-STYLE EDIT MODE ON THE REAL [HomeView] (the user, 2026-11-21: "resemble more of like an iPhone app edit mode with apps kind
 * of animating zoomed smaller a bit and being more animated generally"), at fixed frame times on a pinned motion clock: the open's
 * shrink (0 / 120 / 240 ms), the wiggle's extremes, the lift, a spring glide mid-way, the drop's plop, a layout flip's cascade with the
 * chip's name sliding, a menu's pop on the swapped big map, the ribbon's slide, the leave back to the very Classic page, the system's
 * animations off (the static shrink), and a fight stilling it all at once. The views' properties are checked against the motion's poses;
 * the model's boxes never move for it. With HOME_EDIT_SHOTS (+ HOME_LAYOUT_ART) every frame is drawn:
 *   HOME_EDIT_SHOTS=<dir> HOME_LAYOUT_ART=<root> gradlew :app:testModernDebugUnitTest --tests "*HomeEditMotionShotsTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeEditMotionShotsTest {
    private val out: File? = System.getenv("HOME_EDIT_SHOTS")?.let { File(it, "motion").apply { mkdirs() } }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

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
        "say" to "Korvash says: on my way", "system" to "Quest accepted: Kobold Candles")
    private fun full(fight: Boolean) = JSONObject().put("type", "full").put("map", JSONObject().put("zone", "Elwynn Forest").put("pvp", "contested"))
        .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true).put("hudS", JSONObject().put("c", fight))))

    private class Page(val home: HomeView, val strip: Strip)

    private fun page(layout: HomeLayout): Page {
        System.getenv("HOME_LAYOUT_ART")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
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
        home.onFull(full(false), true)
        val q = quests(false)
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
        // the host's wiring (CompanionHost): the ribbon in the XP track (its slide), the disc's tick, the room bar's chrome and poses
        home.onEditChrome = { v ->
            strip.editRibbon = v?.let { ev -> { c: Canvas, w: Float, top: Float, g0: Float, g1: Float -> ev.drawRibbon(c, w, top, g0, g1) } }
            strip.editTick = v != null
            strip.editRibbonK = v?.let { ev -> { ev.ribbonK() } }
            strip.editBar = v?.let { ev -> { c: Canvas, layer: Int -> ev.drawBar(c, layer) } }
            strip.editBarTouch = v?.let { ev -> { e: MotionEvent -> ev.barTouch(e) } }
            strip.editPose = v?.let { ev -> { r: Room, o: FloatArray -> ev.roomPose(r, o) } }
        }
        home.roomBar = strip
        strip.showOrder(RoomBar.rooms(HomeSettings.layout.rooms), 0L)
        home.forceLive = true
        return Page(home, strip)
    }

    @Suppress("UNCHECKED_CAST")
    private fun boxes(home: HomeView): Map<String, RectF> = (field(home, "now") as Map<Any, RectF>).entries.associate { it.key.toString() to RectF(it.value) }
    @Suppress("UNCHECKED_CAST")
    private fun views(home: HomeView): Map<String, View> = (field(home, "views") as Map<Any, View>).entries.associate { it.key.toString() to it.value }
    private fun model(p: Page): HomeEditModel = p.home.editModel()!!

    /** the motion's pinned clock */
    private var clock = 100_000L
    private fun pin(p: Page, scale: Float = 1f) { p.home.editMotionNow = { clock }; p.home.editMotionScale = { scale } }
    /** one frame of the motion at [t] (the views' properties and the chrome as the ticker would set them) */
    private fun frame(p: Page, t: Long) {
        clock = t
        HomeView::class.java.getDeclaredMethod("editDecor").apply { isAccessible = true }.invoke(p.home)
    }
    /** the page drawn now (Home and the room bar under it), no layout pass, no clock */
    private fun draw(p: Page): Bitmap {
        val b = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        setField(p.home, "clockText", "9:51 AM"); setField(p.home, "batteryPct", 98)
        val c = Canvas(b); c.drawColor(Theme.ink); p.home.draw(c)
        p.strip.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Strip.HEIGHT, View.MeasureSpec.EXACTLY))
        p.strip.layout(0, 0, W, Strip.HEIGHT)
        c.translate(0f, H.toFloat()); p.strip.draw(c)
        return b
    }
    private fun shot(p: Page, name: String): Bitmap {
        val b = draw(p)
        out?.let { d -> File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return b
    }
    private fun glideAt(home: HomeView, k: Float) { (field(home, "anim") as? android.animation.ValueAnimator)?.setCurrentFraction(k) }
    private fun glideMs(home: HomeView): Long = (field(home, "anim") as android.animation.ValueAnimator).duration

    /** each view's scale / turn is the motion's pose for its widget at [t] */
    private fun posesMatch(p: Page, t: Long, what: String) {
        val m = p.home.editMotion()
        val b = boxes(p.home)
        val pose = HomeEditMotion.Pose()
        for ((name, key, owner) in listOf(Triple("CHAT", EditStop.CHAT.ordinal, "CHAT"), Triple("MAP", EditStop.MAP.ordinal, "MAP"),
            Triple("BARS", EditStop.BAND.ordinal, "BARS"))) {
            val v = views(p.home).getValue(name)
            val r = b.getValue(owner)
            m.pose(key, t, 0.5f * hypot(r.width(), r.height()), pose)
            assertEquals("$what: $name scale", pose.scale, v.scaleX, 1e-4f)
            assertEquals("$what: $name turn", pose.rot, v.rotation, 1e-3f)
        }
    }

    private val classic = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)

    // ---------------------------------------------------------------- the open, the wiggle, the ribbon

    @Test fun theOpenShrinksWithARippleAndEveryWidgetWiggles() {
        val p = page(classic)
        val home = p.home
        pin(p)
        val before = boxes(home)
        val t0 = clock
        assertTrue(home.editOpen(EditStop.MAP))
        settle(12)                                                          // the chrome's fade in (the motion's clock is pinned)
        frame(p, t0)
        assertEquals("the map at its own size at the open's first frame", 1f, views(home).getValue("MAP").scaleX, 1e-4f)
        assertEquals("the ribbon below the XP track", 0f, home.editChrome.ribbonK(), 1e-3f)
        shot(p, "m01_enter_000")
        frame(p, t0 + 120)
        val map120 = views(home).getValue("MAP").scaleX; val chat120 = views(home).getValue("CHAT").scaleX
        assertTrue("shrinking: $map120", map120 < 0.97f && map120 > Motion.REST_SCALE - 0.01f)
        assertTrue("the chat later than the map (the ripple from it): $chat120 vs $map120", chat120 >= map120)
        val rk = home.editChrome.ribbonK()
        assertTrue("the ribbon on its way up: $rk", rk > 0.2f && rk < 1.05f)
        posesMatch(p, t0 + 120, "120 ms")
        shot(p, "m02_enter_120")
        frame(p, t0 + 240)
        posesMatch(p, t0 + 240, "240 ms")
        assertEquals("near its rest at 240 ms", Motion.REST_SCALE, views(home).getValue("MAP").scaleX, 0.01f)
        shot(p, "m03_enter_240")
        assertEquals("the model's boxes never move for it", before, boxes(home))
        // the wiggle's extremes: turning, within its corner budget, the two frames apart
        frame(p, t0 + 1000)
        val a = views(home).mapValues { it.value.rotation }
        posesMatch(p, t0 + 1000, "1000 ms")
        shot(p, "m04_wiggle_1000")
        frame(p, t0 + 1077)
        val b = views(home).mapValues { it.value.rotation }
        shot(p, "m05_wiggle_1077")
        for (n in listOf("CHAT", "MAP", "BARS")) {
            val r = boxes(home).getValue(n)
            val hd = 0.5f * hypot(r.width(), r.height())
            assertTrue("$n turns within its budget", abs(a.getValue(n)) <= HomeEditMotion.amp(hd) + 1e-4f && abs(b.getValue(n)) <= HomeEditMotion.amp(hd) + 1e-4f)
            assertNotEquals("$n wiggles", a.getValue(n), b.getValue(n))
        }
        assertTrue("the widgets each on their own phase", a.getValue("CHAT") / HomeEditMotion.amp(1f) != a.getValue("MAP") / HomeEditMotion.amp(1f))
        assertEquals(before, boxes(home))
        assertTrue("the ribbon is up", abs(home.editChrome.ribbonK() - 1f) < 1e-3f)
        // the rooms on the bar wiggle too (a pose for each)
        val pose = FloatArray(4)
        assertTrue(home.editChrome.roomPose(Room.values().first { it != Room.HOME && it.onBar }, pose))
        assertEquals(Motion.REST_SCALE, pose[0], 1e-4f)
    }

    // ---------------------------------------------------------------- lift, glide, drop

    @Test fun theLiftGrowsTheGlideSpringsAndTheDropPlops() {
        val p = page(classic)
        val home = p.home
        pin(p)
        val t0 = clock
        home.editOpen(EditStop.MAP)
        settle(12)                                                          // the chrome's fade in (the motion's clock is pinned)
        frame(p, t0 + 2000)
        val l0 = t0 + 2000
        home.editKey("PAD1")                                                // A: the map lifted
        assertEquals(EditStop.MAP, model(p).lifted)
        frame(p, l0)
        assertEquals(Motion.REST_SCALE, views(home).getValue("MAP").scaleX, 1e-3f)
        frame(p, l0 + 80)
        val s80 = views(home).getValue("MAP").scaleX
        assertTrue("growing: $s80", s80 > 1f)
        shot(p, "m06_lift_080")
        frame(p, l0 + 300)
        val map = views(home).getValue("MAP")
        assertEquals("lifted at 1.04", Motion.LIFT, map.scaleX, 0.002f)
        assertEquals("a lifted widget is still", 0f, map.rotation, 0f)
        assertTrue("above the others", map.translationZ > 0f)
        shot(p, "m07_lift_300")
        // the lifted map steps a dock: every box glides on a spring (longer than the old curve: it settles), the others wiggling on
        val from = boxes(home).getValue("CHAT")
        home.editKey("PADDLEFT")
        assertEquals(HomeEditMotion.glideMs(HomeEditModel.EDIT_STEP), glideMs(home))
        glideAt(home, 0.3f)
        val mid = boxes(home).getValue("CHAT")
        val to = run { glideAt(home, 1f); boxes(home).getValue("CHAT") }
        assertNotEquals("the step moves the chat", from.left, to.left)
        assertTrue("the chat mid-way: ${mid.left} between ${from.left} and ${to.left}",
            mid.left in minOf(from.left, to.left) - 8f..maxOf(from.left, to.left) + 8f && mid.left != from.left)
        home.editKey("PADDRIGHT")                                           // back: from where it is, at its speed
        glideAt(home, 0.5f)
        frame(p, l0 + 400)
        shot(p, "m08_glide_mid")
        glideAt(home, 1f); settle(4)
        // A drops it: the plop at its overshoot (~0.93), then its rest
        val d0 = l0 + 1000
        clock = d0
        home.editKey("PAD1")
        assertEquals(null, model(p).lifted)
        val peak = d0 + (Math.PI / (Motion.DROP_W * kotlin.math.sqrt(1.0 - Motion.DROP_ZETA * Motion.DROP_ZETA)) * 1000).toLong()
        frame(p, peak)
        val low = views(home).getValue("MAP").scaleX
        assertTrue("the plop: $low", low < Motion.REST_SCALE - 0.005f && low > 0.92f)
        shot(p, "m09_drop_peak")
        frame(p, d0 + 900)
        assertEquals(Motion.REST_SCALE, views(home).getValue("MAP").scaleX, 1e-4f)
        assertEquals(0f, views(home).getValue("MAP").translationZ, 0f)
        shot(p, "m10_drop_settled")
    }

    // ---------------------------------------------------------------- a layout flip, a menu's pop

    @Test fun aFlipCascadesAndTheChipNameSlides() {
        val p = page(classic)
        val home = p.home
        pin(p)
        val t0 = clock
        home.editOpen(EditStop.CHAT)
        settle(12)                                                          // the chrome's fade in (the motion's clock is pinned)
        frame(p, t0 + 2000)
        val f0 = t0 + 2000
        val was = model(p).chipName
        home.editKey("PADRSHOULDER")                                        // RB: the next layout, live
        assertNotEquals(was, model(p).chipName)
        val dur = glideMs(home)
        assertEquals(HomeEditMotion.glideMs(Motion.MODULE_OPEN), dur)
        val from = boxes(home)
        for (t in listOf(0L, 80L, 160L, 400L)) {
            glideAt(home, (t.toFloat() / dur).coerceAtMost(1f))
            frame(p, f0 + t)
            val k = home.editMotion().chipK(f0 + t)
            if (t == 80L) assertTrue("the chip's name sliding over: $k", k > 0f && k < 1f)
            shot(p, "m11_flip_%03d".format(t))
        }
        glideAt(home, 1f)
        assertEquals(1f, home.editMotion().chipK(f0 + 400), 0f)
        assertEquals(+1, home.editMotion().chipDir)
        assertNotEquals("the page laid the next layout", from, boxes(home))
    }

    @Test fun aMenuPopsOutOfItsWidgetOnTheSwappedBigMap() {
        val swapped = HomeLayout(chatSide = HomeLayout.Side.RIGHT, chatShare = HomeLayout.Share.THIRD, cast = HomeLayout.CastDock.UNDER_CHAT)
        val p = page(swapped)
        val home = p.home
        pin(p)
        val t0 = clock
        home.editOpen(EditStop.MAP)
        settle(12)                                                          // the chrome's fade in (the motion's clock is pinned)
        frame(p, t0 + 2000)
        val m0 = t0 + 2000
        home.editKey("PAD3")                                                // X: the map's menu
        assertEquals(EditStop.MAP, model(p).menu)
        frame(p, m0)
        assertEquals("closed at its first frame", 0f, home.editMotion().popK(HomeEditMotion.MENU, m0), 1e-4f)
        shot(p, "m12_menu_000")
        frame(p, m0 + 60)
        val k60 = home.editMotion().popK(HomeEditMotion.MENU, m0 + 60)
        assertTrue("popping: $k60", k60 > 0.2f && k60 < 1.1f)
        shot(p, "m13_menu_060")
        frame(p, m0 + 180)
        assertEquals(1f, home.editMotion().popK(HomeEditMotion.MENU, m0 + 180), 0.06f)
        shot(p, "m14_menu_180")
        // the menu fits the page (build 1) and its pop never changes where it lies (touch reads the laid box)
        val box = home.editChrome.menuBox(EditStop.MAP)
        assertTrue("on the page: $box", box.left >= 0f && box.right <= W && box.top >= 0f && box.bottom <= H)
    }

    // ---------------------------------------------------------------- the leave, no motion, a fight

    @Test fun theLeaveLandsOnTheVeryPageEditModeOpenedOn() {
        val p = page(classic)
        val home = p.home
        pin(p)
        val pre = shot(p, "m00_pre_edit")
        val t0 = clock
        home.editOpen(EditStop.CHAT)
        settle(12)                                                          // the chrome's fade in (the motion's clock is pinned)
        frame(p, t0 + 2000)
        val l0 = t0 + 2000
        clock = l0
        home.editKey("PAD2")                                                // B: nothing changed, edit mode ends
        assertFalse(home.editPresent)
        for (t in listOf(0L, 120L, 400L)) { frame(p, l0 + t); shot(p, "m15_leave_%03d".format(t)) }
        val s120 = run { frame(p, l0 + 120); views(home).getValue("CHAT").scaleX }
        assertTrue("growing back: $s120", s120 > Motion.REST_SCALE && s120 <= 1.01f)
        clock = l0 + 2000
        settle()                                                            // the chrome's fade; the ticker sees the leave landed
        assertFalse("the motion is off", home.editMotion().on)
        for ((n, v) in views(home)) {
            assertEquals("$n scale", 1f, v.scaleX, 0f); assertEquals("$n turn", 0f, v.rotation, 0f)
            assertEquals("$n x", 0f, v.translationX, 0f); assertEquals("$n y", 0f, v.translationY, 0f)
        }
        lay(home)
        val post = shot(p, "m19_after_leave")
        var diff = 0
        // the columns under the band (the band's own pictures and the room bar's living disc run on their own state and clock; their
        // views' transforms are checked above)
        val top = boxes(home).getValue("BARS").bottom.toInt() + 1
        for (y in top until H step 2) for (x in 0 until W step 2) if (pre.getPixel(x, y) != post.getPixel(x, y)) diff++
        assertEquals("the page after the leave is the page before the open", 0, diff)
    }

    @Test fun animationsOffIsTheStaticShrink() {
        val p = page(classic)
        val home = p.home
        pin(p, scale = 0f)
        val t0 = clock
        home.editOpen(EditStop.MAP)
        settle(12)                                                          // the chrome's fade in (the motion's clock is pinned)
        frame(p, t0)
        val a = shot(p, "m16_still_000")
        val props0 = views(home).mapValues { listOf(it.value.scaleX, it.value.rotation, it.value.translationX, it.value.translationY) }
        frame(p, t0 + 400)
        val b = shot(p, "m17_still_400")
        val props1 = views(home).mapValues { listOf(it.value.scaleX, it.value.rotation, it.value.translationX, it.value.translationY) }
        assertEquals("nothing moves between frames", props0, props1)
        for (n in listOf("CHAT", "MAP", "BARS")) {
            assertEquals("$n at rest at once", Motion.REST_SCALE, views(home).getValue(n).scaleX, 0f)
            assertEquals("$n never turns", 0f, views(home).getValue(n).rotation, 0f)
        }
        assertTrue("the same picture", a.sameAs(b))
        assertFalse("no ticker to run", home.editMotion().busy(t0 + 400))
    }

    @Test fun aFightStillsEveryWidgetAtOnce() {
        val p = page(classic)
        val home = p.home
        pin(p)
        val t0 = clock
        home.editOpen(EditStop.MAP)
        settle(12)                                                          // the chrome's fade in (the motion's clock is pinned)
        frame(p, t0 + 1000)
        assertTrue(views(home).values.any { it.rotation != 0f })
        home.onFull(full(true), true); home.onQuests(quests(true), true); home.tick()
        assertTrue(model(p).parked)
        for ((n, v) in views(home)) { assertEquals("$n scale", 1f, v.scaleX, 0f); assertEquals("$n turn", 0f, v.rotation, 0f) }
        assertFalse(home.editMotion().on)
        clock = t0 + 1100
        settle(3)
        assertFalse("no ticker posted", field(home, "tickerPosted") as Boolean)
        for ((n, v) in views(home)) { assertEquals("$n scale", 1f, v.scaleX, 0f); assertEquals("$n turn", 0f, v.rotation, 0f) }
        shot(p, "m18_fight_parked")
        // the fight's end: back, shrinking again
        home.onFull(full(false), true); home.onQuests(quests(false), true)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(25)); home.tick(); settle()
        assertFalse(model(p).parked)
        assertTrue(home.editMotion().on)
        frame(p, clock + 600)
        assertEquals(Motion.REST_SCALE, views(home).getValue("CHAT").scaleX, 2e-3f)
        assertNotNull(home.editModel())
    }
}
