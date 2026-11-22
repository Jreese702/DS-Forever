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
 * shrink (0 / 120 / 240 ms), the wiggle's extremes, the lift, a spring glide mid-way, the drop's plop, a swap's glide with the card
 * riding its map, a menu's pop on the swapped big map, the ribbon's slide, the leave back to the very Classic page, the system's
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
        // the clock as the device has it from the start (the clock's edit box at Centre is the chip as drawn: its time's width counts)
        setField(home, "clockText", "9:51 AM"); setField(home, "batteryPct", 98)
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
            strip.editApps = v?.apps()                                       // 1518 build 2: the badges, the "+" spot
        }
        home.roomBar = strip
        strip.showOrder(RoomBar.shown(HomeSettings.layout), 0L)
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
        for ((name, key, owner) in listOf(Triple("CHAT", EditStop.CHAT.key, "CHAT"), Triple("MAP", EditStop.MAP.key, "MAP"))) {
            val v = views(p.home).getValue(name)
            val r = b.getValue(owner)
            m.pose(key, t, 0.5f * hypot(r.width(), r.height()), pose)
            assertEquals("$what: $name scale", pose.scale, v.scaleX, 1e-4f)
            assertEquals("$what: $name turn", pose.rot, v.rotation, 1e-3f)
        }
        // 1518 build 3: the band never moves (the user's ruling 2026-11-22: the top bar stays still in edit mode)
        val bars = views(p.home).getValue("BARS")
        assertEquals("$what: the band's own scale", 1f, bars.scaleX, 1e-4f)
        assertEquals("$what: the band's own turn", 0f, bars.rotation, 1e-3f)
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
        // 1518 build 3: the band never wiggles (the user's ruling 2026-11-22: nothing on it moves)
        assertEquals("the band still", 0f, b.getValue("BARS"), 0f)
        for (n in listOf("CHAT", "MAP")) {
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

    /** 1518 (F2): a lift that ends or is replaced by a touch - a tap on the map, a tap on a room, a room dragged, the lifted bar removed
     *  - plops the old widget into its dock: never left grown, still and raised for the rest of the session */
    @Test fun aLiftEndedByATouchNeverLeavesAStalePose() {
        val cases = listOf<Triple<String, EditStop, (HomeEditModel) -> List<HomeEditModel.Fx>>>(
            Triple("a tap on the map", EditStop.CHAT) { m -> m.tap(EditStop.MAP) },
            Triple("a tap on a room", EditStop.CHAT) { m -> m.barTap(2) },
            Triple("a room dragged", EditStop.CHAT) { m -> m.barDragStart(1, 100f, 1000f, HomeLayoutSolver.Box(60f, 960f, 140f, 1040f)) },
            Triple("the lifted bar removed", EditStop.CAST) { m -> m.remove(EditStop.CAST) })
        for ((what, s, act) in cases) {
            val p = page(classic)
            val home = p.home
            pin(p)
            val t0 = clock
            home.editOpen(EditStop.CHAT)
            settle(12)
            frame(p, t0 + 2000)
            home.editAct { it.startOn(s, true) }                                // A on it: lifted
            assertEquals(what, s, model(p).lifted)
            frame(p, t0 + 2300)
            assertTrue("$what: lifted", home.editMotion().liftK(s.key, clock) > 0.9f)
            home.editAct(act)
            assertTrue("$what: the old lift is not the motion's", home.editMotion().lifted != s.key)
            frame(p, t0 + 3300)
            assertEquals("$what: plopped back", 0f, home.editMotion().liftK(s.key, clock), 1e-3f)
            if (s == EditStop.CHAT) {
                val v = views(home).getValue("CHAT")
                assertEquals("$what: the chat at its rest size", Motion.REST_SCALE, v.scaleX, 0.02f)
                assertEquals("$what: not raised", 0f, v.translationZ, 0f)
            }
            if (model(p).drag != null) home.editAct { it.dragCancel() }
            home.editCancel()
        }
    }

    /** [v]'s drawn box (its pivot, scale, turn and translation; the AABB of the turned box), Home's px */
    private fun drawnBox(v: View): RectF {
        val m = android.graphics.Matrix()
        m.postScale(v.scaleX, v.scaleY, v.pivotX, v.pivotY); m.postRotate(v.rotation, v.pivotX, v.pivotY)
        m.postTranslate(v.left + v.translationX, v.top + v.translationY)
        val r = v.clipBounds?.let { RectF(it) } ?: RectF(0f, 0f, v.width.toFloat(), v.height.toFloat())
        m.mapRect(r)
        return r
    }
    private fun inside(r: RectF) = r.left >= -0.5f && r.top >= -0.5f && r.right <= W + 0.5f && r.bottom <= H + 0.5f

    /**
     * the user's pick 2026-11-21 (the shot pass): a lifted full-height widget at 1.04 (Classic's map: the band's foot to the frame's) grew
     * past Home's foot and the room bar / the ribbon drew over it, its bottom edge cut off. Now the grown box stays inside Home's frame on
     * every frame of the lift (its ~1.048 overshoot included) and its paint (the well's edge, the zone band) with it - every preset x
     * dock, swapped, the map and the chat - while the touch targets (the model's boxes, the chrome's widget boxes) never move for it
     */
    @Test fun aLiftedWidgetStaysInsideHomesFrame() {
        val cases = ArrayList<Pair<String, HomeLayout>>()
        for (dock in listOf(HomeLayout.CastDock.UNDER_CHAT, HomeLayout.CastDock.UNDER_MAP, HomeLayout.CastDock.ABOVE_MAP))
            for ((id, l) in HomeArrangements.on(HomeLayout(cast = dock))) cases += "${id}_${dock.name.lowercase()}" to l
        cases += "swapped_third" to HomeLayout(chatSide = HomeLayout.Side.RIGHT, chatShare = HomeLayout.Share.THIRD, cast = HomeLayout.CastDock.UNDER_CHAT)
        var checked = 0; var grewPast = 0; var shotted = false
        for ((name, l) in cases) for ((stop, part) in listOf(EditStop.MAP to "MAP", EditStop.CHAT to "CHAT")) {
            val p = page(l)
            val home = p.home
            pin(p)
            val t0 = clock
            if (!home.editOpen(stop)) continue
            settle(12)
            frame(p, t0 + 2000)
            if (model(p).cursor != stop || !boxes(home).containsKey(part)) { home.editCancel(); continue }
            val laid = boxes(home)
            val hits = home.editChrome.widgetBoxes().mapValues { RectF(it.value) }
            val r = laid.getValue(part)
            // the old lift (1.04 about its centre, no shift) would reach past the frame here
            if (r.centerY() + r.height() * Motion.LIFT / 2f > H + 0.5f || r.centerY() - r.height() * Motion.LIFT / 2f < -0.5f) grewPast++
            val l0 = t0 + 2000
            home.editKey("PAD1")
            assertEquals("$name: $part lifted", stop, model(p).lifted)
            for (dt in 0L..400L step 8L) {
                frame(p, l0 + dt)
                val d = drawnBox(views(home).getValue(part))
                assertTrue("$name: $part lifted +$dt ms (scale ${views(home).getValue(part).scaleX}) drawn ${d.toShortString()} inside 0,0,$W,$H", inside(d))
                if (part == "MAP") {
                    val paint = posed(home, "MAP", laid.getValue("MAP"))
                    assertTrue("$name: the map's paint +$dt ms ${paint.toShortString()} inside the frame", inside(paint))
                    assertEquals("$name: the paint on the map", d.centerY(), paint.centerY(), 0.5f)
                }
            }
            assertEquals("$name: lifted at 1.04", Motion.LIFT, views(home).getValue(part).scaleX, 0.002f)
            if (name == "${HomeArrangements.CLASSIC}_under_chat" && part == "MAP") {
                shotted = true
                val b = shot(p, "m20_lift_map_inside")
                val d = drawnBox(views(home).getValue("MAP"))
                // its bottom edge is drawn (the well's edge, inside the frame), not under the room bar
                val y = (d.bottom - 2f).toInt(); val x = d.centerX().toInt()
                assertTrue("the map's foot drawn at $y", (y - 4..y).any { b.getPixel(x, it) != Theme.ink } && y < H)
            }
            assertEquals("$name: the model's boxes never move for it", laid, boxes(home))
            assertEquals("$name: the touch targets never move for it", hits, home.editChrome.widgetBoxes().mapValues { RectF(it.value) })
            checked++
            home.editCancel()
        }
        assertTrue("checked $checked lifts", checked >= 30)
        assertTrue("Classic's lifted map drawn", shotted)
        assertTrue("the full-height lifts that grew past the frame before: $grewPast", grewPast >= 6)
        // review round 3: the full-width tap-to-cast bar (ACROSS: one bar 8 .. 1232; SPLIT: two halves posed as one) lifted at 1.04 was
        // 1273 px wide, its ends 16.5 px past both page edges: now drawn no bigger than the frame holds, every half inside, touch unmoved
        var bars = 0
        for (dock in listOf(HomeLayout.CastDock.ACROSS, HomeLayout.CastDock.SPLIT)) for ((id, arr) in HomeArrangements.on(HomeLayout(cast = dock))) {
            val name = "${id}_${dock.name.lowercase()}"
            val p = page(arr.copy(cast = dock))
            val home = p.home
            pin(p)
            val t0 = clock
            if (!home.editOpen(EditStop.CAST)) continue
            settle(12)
            frame(p, t0 + 2000)
            // the halves laid (the pose turns about their union, HomeView.ownerBox) and the ones drawn
            val laidHalves = listOf("STRIP", "CAST2").filter { boxes(home).containsKey(it) }
            val halves = laidHalves.filter { views(home).getValue(it).visibility == View.VISIBLE }
            if (model(p).cursor != EditStop.CAST || halves.isEmpty()) { home.editCancel(); continue }
            val laid = boxes(home)
            val hits = home.editChrome.widgetBoxes().mapValues { RectF(it.value) }
            val union = RectF(laid.getValue(laidHalves[0])).also { u -> for (h in laidHalves) u.union(laid.getValue(h)) }
            assertTrue("$name: the bar laid across the page (${union.toShortString()}; laid $laidHalves, drawn $halves)", union.width() * Motion.LIFT > W + 1f)
            val l0 = t0 + 2000
            home.editKey("PAD1")
            assertEquals("$name: the bar lifted", EditStop.CAST, model(p).lifted)
            for (dt in 0L..400L step 8L) {
                frame(p, l0 + dt)
                for (h in halves) {
                    val d = drawnBox(views(home).getValue(h))
                    assertTrue("$name: $h lifted +$dt ms (scale ${views(home).getValue(h).scaleX}) drawn ${d.toShortString()} inside 0,0,$W,$H", inside(d))
                }
            }
            val sc = views(home).getValue(halves[0]).scaleX
            assertTrue("$name: grown past its rest, no bigger than the frame: $sc", sc > Motion.REST_SCALE + 0.03f && sc * union.width() <= W + 0.5f)
            if (name == "${HomeArrangements.CLASSIC}_across") shot(p, "m21_lift_bar_inside")
            assertEquals("$name: the model's boxes never move for it", laid, boxes(home))
            assertEquals("$name: the touch targets never move for it", hits, home.editChrome.widgetBoxes().mapValues { RectF(it.value) })
            bars++
            home.editCancel()
        }
        assertTrue("checked $bars full-width bar lifts", bars >= 4)
    }

    // ---------------------------------------------------------------- a menu's pop

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
        for (n in listOf("CHAT", "MAP")) {
            assertEquals("$n at rest at once", Motion.REST_SCALE, views(home).getValue(n).scaleX, 0f)
            assertEquals("$n never turns", 0f, views(home).getValue(n).rotation, 0f)
        }
        assertEquals("the band is no widget: its own size", 1f, views(home).getValue("BARS").scaleX, 0f)
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

    /**
     * the user's pick 2026-11-21, "Never rest": no input for 16 s, a minute, ten minutes - every widget still wiggles and the ticker runs;
     * Home off screen stops the ticker (Home again starts it), and the leave's landing stops it for good
     */
    @Test fun theWiggleNeverRestsUntilHomeGoesAway() {
        val p = page(classic)
        val home = p.home
        pin(p)
        val t0 = clock
        home.editOpen(EditStop.CHAT)
        settle(12)
        for (t in listOf(t0 + 16_000L, t0 + 60_000L, t0 + 600_000L)) {
            clock = t; settle(2)
            assertTrue("the ticker runs at +${t - t0} ms", field(home, "tickerPosted") as Boolean)
            val a = views(home).mapValues { it.value.rotation }
            clock = t + 37; settle(1)
            for (n in listOf("CHAT", "MAP")) {
                assertNotEquals("$n still wiggling at +${t - t0} ms", a.getValue(n), views(home).getValue(n).rotation)
                val r = boxes(home).getValue(n)
                val most = HomeEditMotion.amp(0.5f * hypot(r.width(), r.height()))
                assertTrue("$n within its turn", abs(views(home).getValue(n).rotation) <= most + 1e-4f)
            }
        }
        shot(p, "m21_wiggle_600s")
        // Home off screen: the ticker's next frame posts nothing more
        home.forceLive = false
        settle(3)
        assertFalse("Home away: the ticker stopped", field(home, "tickerPosted") as Boolean)
        assertTrue("edit mode still open", home.editMotion().on)
        // Home again (laid again): it runs on
        home.forceLive = true
        home.requestLayout(); lay(home)
        assertTrue("Home again: the ticker runs", field(home, "tickerPosted") as Boolean)
        settle(2)
        assertTrue(field(home, "tickerPosted") as Boolean)
        // the leave lands: the ticker stops
        home.editCancel()
        clock += 3000; settle(30)
        assertFalse(home.editMotion().on)
        assertFalse("edit mode closed: no ticker", field(home, "tickerPosted") as Boolean)
    }

    // ---------------------------------------------------------------- review round 2: no jumps

    private val partClass by lazy { Class.forName("com.jrees.wowcompanion.HomeView\$Part") }
    private fun part(name: String): Any = partClass.enumConstants.first { (it as Enum<*>).name == name }
    /** [box] as drawn through [partName]'s pose now (HomeView.partMatrix: the chrome's and the paint's) */
    private fun posed(home: HomeView, partName: String, box: RectF): RectF {
        val m = android.graphics.Matrix()
        val on = HomeView::class.java.getDeclaredMethod("partMatrix", partClass, android.graphics.Matrix::class.java).apply { isAccessible = true }
            .invoke(home, part(partName), m) as Boolean
        return RectF(box).also { if (on) m.mapRect(it) }
    }
    /** the gold cursor's box as drawn now ([stop]'s box, its flight's offset, its widget's pose) */
    private fun cursorDrawn(home: HomeView, stop: EditStop, partName: String, t: Long): RectF {
        val b = home.editChrome.widgetBoxes().getValue(stop)
        val off = FloatArray(4); home.editMotion().cursorOffset(t, off)
        return posed(home, partName, RectF(b.left + off[0], b.top + off[1], b.right + off[2], b.bottom + off[3]))
    }
    /** a view's centre as drawn (its pivot, scale, turn and translation), Home's px */
    private fun drawnCentre(v: View): Pair<Float, Float> {
        val m = android.graphics.Matrix()
        m.postScale(v.scaleX, v.scaleY, v.pivotX, v.pivotY); m.postRotate(v.rotation, v.pivotX, v.pivotY)
        m.postTranslate(v.left + v.translationX, v.top + v.translationY)
        val pt = floatArrayOf(v.width / 2f, v.height / 2f); m.mapPoints(pt)
        return pt[0] to pt[1]
    }
    private fun far(a: Pair<Float, Float>, b: Pair<Float, Float>) = hypot(a.first - b.first, a.second - b.second)

    /** a swap (Select): the card on the map stays inside the map on every frame (it glides as the map's rider), and every part lands
     *  on its own spring - none snapped to its place at the end (1518: no layout flips, no cascade) */
    @Test fun aSwapKeepsTheCardOnItsMapAndLandsEveryPart() {
        val p = page(classic)
        val home = p.home
        pin(p)
        val t0 = clock
        home.editOpen(EditStop.CHAT)
        settle(12)
        frame(p, t0 + 2000)
        assertTrue("the card on the map", boxes(home).containsKey("QUESTS"))
        home.editKey("PADBACK")
        val dur = glideMs(home)
        assertEquals("the spring's glide, no cascade on top", HomeEditMotion.glideMs(Motion.MODULE_OPEN), dur)
        val n = (dur / 16L).toInt()
        var last = boxes(home)
        var lastStep = 0f
        for (i in 1..n) {
            glideAt(home, i.toFloat() / n)
            val b = boxes(home)
            val q = b.getValue("QUESTS"); val m = b.getValue("MAP")
            assertTrue("frame $i: the card ${q} inside the map $m",
                q.left >= m.left - 0.5f && q.right <= m.right + 0.5f && q.top >= m.top - 0.5f && q.bottom <= m.bottom + 0.5f)
            if (i == n) lastStep = b.keys.filter { it in last }.maxOf { k ->
                val a = last.getValue(k); val c = b.getValue(k)
                maxOf(abs(a.left - c.left), abs(a.top - c.top), abs(a.right - c.right), abs(a.bottom - c.bottom)) }
            last = b
        }
        assertTrue("the last frame is a frame's step, not a snap: $lastStep px", lastStep <= 2f)
    }

    /** the gold cursor's box as drawn now (HomeEditView's goldBox): its four corners (x, y each) through its widget's whole pose and the
     *  cursor's own turn about the box's centre (review round 3: the corners are compared, the turn's flight included) */
    private fun cursorCorners(home: HomeView, stop: EditStop, partName: String, t: Long): FloatArray {
        val b = home.editChrome.widgetBoxes().getValue(stop)
        val off = FloatArray(4); home.editMotion().cursorOffset(t, off)
        val box = RectF(b.left + off[0], b.top + off[1], b.right + off[2], b.bottom + off[3])
        val m = android.graphics.Matrix()
        HomeView::class.java.getDeclaredMethod("partMatrix", partClass, android.graphics.Matrix::class.java).apply { isAccessible = true }
            .invoke(home, part(partName), m)
        m.preRotate(home.editMotion().cursorTurn(t), box.centerX(), box.centerY())
        val pts = floatArrayOf(box.left, box.top, box.right, box.top, box.right, box.bottom, box.left, box.bottom)
        m.mapPoints(pts)
        return pts
    }

    /** the gold cursor leaves from where it was DRAWN: its first frame on the new widget is the old drawn box, every corner (no 0.06 x
     *  the centres' jump; no snap by the two widgets' turns either - up to 1.6 deg apart at 0.8 each, ~10 px at the chat's corners - its
     *  turn flies from the old one's; review round 3) */
    @Test fun theCursorFliesFromWhereItWasDrawn() {
        val p = page(classic)
        val home = p.home
        pin(p)
        val t0 = clock
        home.editOpen(EditStop.CHAT)
        settle(12)
        for (t in listOf(t0 + 2000, t0 + 2037, t0 + 16_000, t0 + 60_000)) {  // mid-wiggle at its full turn, and past the old 15 s rest
            frame(p, t)
            assertEquals(EditStop.CHAT, model(p).cursor)
            assertTrue("both turning at $t", views(home).getValue("CHAT").rotation != 0f && views(home).getValue("MAP").rotation != 0f)
            val before = cursorCorners(home, EditStop.CHAT, "CHAT", t)
            val turnGap = views(home).getValue("CHAT").rotation - views(home).getValue("MAP").rotation
            val turnWas = home.editMotion().cursorTurn(t)                   // the last flight's turn still drawn (the rounds' clocks overlap)
            home.editKey("PADDRIGHT")
            assertEquals(EditStop.MAP, model(p).cursor)
            frame(p, t)
            val after = cursorCorners(home, EditStop.MAP, "MAP", t)
            assertEquals("$t: its turn flies from the chat's angle", turnGap + turnWas, home.editMotion().cursorTurn(t), 0.01f)
            for (i in before.indices)
                assertEquals("$t: corner ${i / 2} drawn where it was: ${before.toList()} -> ${after.toList()}", before[i], after[i], 0.5f)
            frame(p, t + 400)
            assertEquals("$t: the turn landed on the map's", 0f, home.editMotion().cursorTurn(t + 400), 0.02f)
            val landed = cursorDrawn(home, EditStop.MAP, "MAP", t + 400)
            assertEquals("and it lands on the map", posed(home, "MAP", home.editChrome.widgetBoxes().getValue(EditStop.MAP)).centerX(), landed.centerX(), 1f)
            home.editKey("PADDLEFT")                                        // back to the chat for the next round
            frame(p, t + 1400)
        }
    }

    /** the card on the map: lifted, it grows from where it is (its pose moves from the map's to its own, no pivot switch); dropped, it
     *  plops and rides the map's pose again - no frame jumps */
    @Test fun theCardOnTheMapLiftsAndDropsWithoutAJump() {
        val p = page(classic)
        val home = p.home
        pin(p)
        val t0 = clock
        home.editOpen(EditStop.QUEST)
        settle(12)
        val t = t0 + 2000                                                   // the wiggle at its full turn (it never rests now)
        frame(p, t)
        val card = views(home).getValue("QUESTS")
        val c0 = drawnCentre(card)
        home.editKey("PAD1")
        assertEquals(EditStop.QUEST, model(p).lifted)
        frame(p, t)
        assertTrue("lifted at its first frame: where it was (${far(c0, drawnCentre(card))} px)", far(c0, drawnCentre(card)) < 0.5f)
        var was = drawnCentre(card)
        var most = 0f
        for (dt in 16L..400L step 16L) { frame(p, t + dt); val c = drawnCentre(card); most = maxOf(most, far(was, c)); was = c }
        assertEquals("lifted to 1.04", Motion.LIFT, card.scaleX, 0.003f)
        val d0 = t + 400
        clock = d0
        home.editKey("PAD1")
        assertEquals(null, model(p).lifted)
        for (dt in 0L..900L step 16L) { frame(p, d0 + dt); val c = drawnCentre(card); most = maxOf(most, far(was, c)); was = c }
        assertTrue("no frame jumps over the lift and the drop: $most px", most < 4f)
        assertEquals("back on the map's rest", Motion.REST_SCALE, card.scaleX, 2e-3f)
        // and riding the map again: its drawn centre is the map's pose (the map view's own transform) of its laid centre
        val q = boxes(home).getValue("QUESTS")
        val onMap = drawnPoint(views(home).getValue("MAP"), q.centerX(), q.centerY())
        assertTrue("and riding the map again (${far(onMap, was)} px off the map's pose)", far(onMap, was) < 1f)
    }
    /** Home's point ([x], [y]) drawn through view [v]'s transform (its pivot, scale, turn and translation) */
    private fun drawnPoint(v: View, x: Float, y: Float): Pair<Float, Float> {
        val m = android.graphics.Matrix()
        m.postScale(v.scaleX, v.scaleY, v.pivotX, v.pivotY); m.postRotate(v.rotation, v.pivotX, v.pivotY)
        m.postTranslate(v.left + v.translationX, v.top + v.translationY)
        val pt = floatArrayOf(x - v.left, y - v.top); m.mapPoints(pt)
        return pt[0] to pt[1]
    }

    /** a room on the bar picked up and put down: from its 0.94 and its turn to the lift and back, never 1.0 for a frame */
    @Test fun aRoomLiftsAndDropsFromItsPose() {
        val p = page(classic)
        val s = p.strip
        s.editPose = { _, o -> o[0] = Motion.REST_SCALE; o[1] = 1.1f; o[2] = 0f; o[3] = 0.4f; true }
        draw(p)
        val r = Room.values().first { it != Room.HOME && it.onBar }
        val icon = s.roomDrawn(r)!!.width()
        s.liftRoom = r
        assertEquals("picked up: at its pose's size", icon * Motion.REST_SCALE, s.roomDrawn(r)!!.width(), 0.5f)
        draw(p)
        ShadowSystemClock.advanceBy(Duration.ofMillis(HomeEditModel.LIFT_MS * 3)); draw(p)
        assertEquals("lifted", icon * HomeEditModel.LIFT_SCALE, s.roomDrawn(r)!!.width(), 0.5f)
        s.liftRoom = null
        assertEquals("the drop starts at the lift", icon * HomeEditModel.LIFT_SCALE, s.roomDrawn(r)!!.width(), 0.5f)
        ShadowSystemClock.advanceBy(Duration.ofMillis(HomeEditModel.LIFT_MS * 3)); draw(p)
        assertEquals("dropped: back at its pose's size", icon * Motion.REST_SCALE, s.roomDrawn(r)!!.width(), 0.5f)
    }

    /** 1518 build 2: every room is its own app - each wiggles on its own phase (its "-" badge drawn in its pose, so with it), and the
     *  "+" spot at the bar's end has its own wiggle too; the touch boxes stay at rest */
    @Test fun theRoomsWiggleAsAppsWithTheirBadgesAndThePlusSpot() {
        val p = page(classic)
        val home = p.home
        pin(p)
        val t0 = clock
        home.editOpen(EditStop.MAP); settle(12)
        home.editAct { it.hideRoom("BAGS") }; settle(4)
        draw(p)
        val plus = p.strip.plusBox()
        assertTrue("a room hidden: the \"+\" spot at the bar's end", plus != null && plus.right > W - 120f)
        val badge0 = p.strip.badgeBox(Room.MAP)!!
        val o = FloatArray(4)
        frame(p, t0 + 1400); val a = RoomBar.DEFAULT.filter { it != "BAGS" }.associateWith { n ->
            home.editChrome.roomPose(Room.valueOf(n), o); o[1] }
        home.editChrome.plusPose(o); val pa = o[1]
        shot(p, "m15_rooms_as_apps_1400")
        frame(p, t0 + 1650); val b = RoomBar.DEFAULT.filter { it != "BAGS" }.associateWith { n ->
            home.editChrome.roomPose(Room.valueOf(n), o); o[1] }
        home.editChrome.plusPose(o); val pb = o[1]
        shot(p, "m16_rooms_as_apps_1650")
        for (n in a.keys) assertNotEquals("$n wiggles", a.getValue(n), b.getValue(n))
        assertNotEquals("the \"+\" spot wiggles", pa, pb)
        assertTrue("each room on its own phase", a.values.toSet().size == a.size)
        assertTrue("\"+\" on its own phase", a.values.none { it == pa })
        assertEquals("the badge's touch box stays at rest", badge0, p.strip.badgeBox(Room.MAP))
    }

    /** Discard from a bigger map: its paint (the well edge, the zone band) stays on the map through the whole glide, the part after the
     *  chrome's fade included (the crop's pivot share is the view's, not the paint's) */
    @Test fun theMapsPaintStaysOnTheMapThroughADiscard() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.UNDER_MAP))
        val home = p.home
        home.editOpen(EditStop.MAP); settle(12)
        home.editKey("PAD4"); settle(30)                                    // Y: the map 2/3
        home.editKey("PADDDOWN"); assertEquals(EditStop.CAST, model(p).cursor)
        home.editKey("PAD1"); home.editKey("PADDLEFT"); home.editKey("PAD1"); settle(30)
        assertEquals(HomeLayout.CastDock.UNDER_CHAT, model(p).draft.cast)
        val big = boxes(home).getValue("MAP")
        home.editAct { it.discard() }
        // the glide (spring: longer than the chrome's 200 ms fade) and the fade, stepped by hand (the off-device animators)
        val glide = field(home, "anim") as android.animation.ValueAnimator
        assertTrue("a spring glide longer than the fade: ${glide.duration}", glide.duration > Motion.MODULE_CLOSE)
        glideAt(home, 0.3f)
        assertNotNull("the map's crop holds it", field(home, "mapScaleTo"))
        assertEquals("chrome on: the map's paint on the map", 0f, home.mapPaintDx(), 1f)
        (field(home, "chromeAnim") as android.animation.ValueAnimator).end()
        assertEquals("the chrome faded", null, field(home, "chromeModel"))
        for (k in listOf(0.6f, 0.8f, 0.95f)) {
            glideAt(home, k)
            assertNotNull("$k: the crop still holds it", field(home, "mapScaleTo"))
            assertEquals("$k (the chrome faded): the map's paint on the map", 0f, home.mapPaintDx(), 1f)
        }
        glideAt(home, 1f); settle(4)
        assertTrue("the map shrank both ways", boxes(home).getValue("MAP").let { it.width() < big.width() && it.height() < big.height() })
    }

    /** the middle wheel opened right after Done: the leave's ticker never puts the columns it parted back over it */
    @Test fun theMiddleWheelOpensRightAfterDone() {
        val p = page(classic)
        val home = p.home
        home.editOpen(EditStop.CHAT); settle(12)
        home.editDone()
        assertTrue("the leave's springs still run", home.editMotion().on)
        val slot = home.wheelSlot(true)!!
        settle(50)
        assertFalse("the leave landed", home.editMotion().on)
        for (n in listOf("CHAT", "MAP")) {
            val v = views(home).getValue(n)
            val d = RectF(boxes(home).getValue(n)).apply { offset(v.translationX, v.translationY) }
            assertFalse("$n drawn $d over the wheel $slot", RectF.intersects(d, slot))
        }
        home.wheelSlot(false); settle(30)
    }
}
