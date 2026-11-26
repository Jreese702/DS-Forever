package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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
 * SINGLE SCREEN MODE, CORE (the user, 2026-11-25: "a single screen mode where the game shifts to the top screen when a button is pushed
 * ... The button to shift it needs to be in the pause menu below the edit mode button"): the REAL HomePauseView / HomeView / Strip /
 * BagsView off-device. The wheel's new button below Edit on the bottom screen (two screens: the picture "One screen") and on the 1920 x
 * 1080 top screen (the picture "Two screens", the corners at the screen's corners, the hub Home's glance); a room 1:1 in the centred
 * panel over the dimmed game (the pick "Centred panel"); Home's glance; the four refusals; the controller hints; the button's sheet;
 * playing (nothing of ours over the game). SINGLE_SHOTS_DIR = the shots' folder (none: only the checks), SINGLE_TOP_BG = the blurred
 * game frame (else a plain dark one), SINGLE_TOP_PLAY = the sharp one, POWER_ART_ROOT = an art root.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SingleCoreShotsTest {
    private val out: File? = System.getenv("SINGLE_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val TW = 1920
    private val TH = 1080
    private val BW = 1240
    private val BH = 1080

    @Before fun up() {
        if (System.getenv("SINGLE_SHOTS_LOG") != null) org.robolectric.shadows.ShadowLog.stream = System.out
        System.getenv("POWER_ART_ROOT")?.let { root -> ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
    }
    @After fun down() {
        UiKit.pauseRoomy = false; UiKit.pauseBarsBottom = 0f; UiKit.pauseStripTop = 0f
        CastSettings.buttonLayout = CastSettings.ButtonLayout.NINTENDO; CastSettings.abxyLocation = CastSettings.AbxyLocation.NINTENDO
        SingleScreen.offered = false
    }

    private fun lay(v: View, l: Int, t: Int, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(l, t, l + w, t + h)
    }
    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)
    private fun settle(ms: Long = 900) { var t = 0L; while (t < ms) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper(); t += 16 } }
    private fun save(b: Bitmap, name: String) {
        val d = out ?: return
        d.mkdirs()
        File(d, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** the game's frame on the top screen: SINGLE_TOP_BG's picture, else a plain dark one */
    private fun backdrop(env: String = "SINGLE_TOP_BG"): Bitmap {
        val b = Bitmap.createBitmap(TW, TH, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(46, 52, 58))
        System.getenv(env)?.let { p -> BitmapFactory.decodeFile(p) }?.let { g -> c.drawBitmap(g, null, RectF(0f, 0f, TW.toFloat(), TH.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG)) }
        return b
    }
    /** the top window's dim over the game (SingleLook's) */
    private fun dim(c: Canvas, a: Float) = c.drawColor(Color.argb((255 * a).toInt(), 0, 0, 0))

    // ---------------------------------------------------------------- the stages

    private class Stage(val root: FrameLayout, val home: HomeView?, val strip: Strip, val pause: HomePauseView, val did: ArrayList<String>)

    /** two screens: Home, the bar and the wheel on the 1240 x 1080 bottom screen, wired as CompanionHost wires them */
    private fun bottomStage(xbox: Boolean = false): Stage {
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        HomeSettings.init(app); CastSettings.init(app)
        CastSettings.buttonLayout = if (xbox) CastSettings.ButtonLayout.XBOX else CastSettings.ButtonLayout.NINTENDO
        val holder = FrameLayout(act)
        val map = MapView(act); val cast = CastView(act) { }; val chat = ChatView(act) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(act, map, cast, chat) { }
        home.borrow()
        val strip = Strip(act) { }
        val did = ArrayList<String>()
        val pause = HomePauseView(act, strip, { null }) { true }
        val root = FrameLayout(act)
        root.addView(home); root.addView(strip); root.addView(pause)
        act.setContentView(root)
        val hh = BH - Strip.HEIGHT
        lay(root, 0, 0, BW, BH); lay(home, 0, 0, BW, hh); lay(strip, 0, hh, BW, Strip.HEIGHT); lay(pause, 0, 0, BW, BH)
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        home.tick(); ShadowLooper.idleMainLooper(); lay(home, 0, 0, BW, hh)
        pause.barsBottom = { home.barsBottom() }
        pause.onSlide = { t -> home.pauseSlide(t, barsUp = true); strip.translationY = if (pause.isOpen) (strip.height + 4f) * (t / UiKit.PAUSE_OPEN_MS).coerceIn(0f, 1f) else 0f }
        pause.onClose = { _ -> home.pauseSlide(0f, barsUp = true); strip.translationY = 0f }
        pause.onPick = { s -> did += "pick:${s.key}" }
        return Stage(root, home, strip, pause, did)
    }

    /** single screen mode with nothing under the wheel (the PLAY state): the wheel over the whole 1920 x 1080 top screen, the bar not shown */
    private fun topStage(xbox: Boolean = false, refusal: String? = null): Stage {
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        HomeSettings.init(app); CastSettings.init(app)
        CastSettings.buttonLayout = if (xbox) CastSettings.ButtonLayout.XBOX else CastSettings.ButtonLayout.NINTENDO
        val strip = Strip(act) { }
        val did = ArrayList<String>()
        val pause = HomePauseView(act, strip, { null }) { true }
        pause.single = true
        pause.screensRefusal = { refusal }
        val root = FrameLayout(act)
        root.addView(strip); root.addView(pause)
        strip.visibility = View.GONE                          // the rooms column is GONE in the PLAY state (SingleLook)
        act.setContentView(root)
        lay(root, 0, 0, TW, TH); lay(pause, 0, 0, TW, TH)
        pause.onPick = { s -> did += "pick:${s.key}" }
        return Stage(root, null, strip, pause, did)
    }

    private val buttons = HomePauseView::class.java.getDeclaredMethod("buttons", Int::class.javaPrimitiveType).apply { isAccessible = true }
    private fun open(s: Stage, start: Boolean = true) {
        s.pause.open(byStart = start)
        if (start) { buttons.invoke(s.pause, 1 shl PadInput.SLOT_START); buttons.invoke(s.pause, 0) } else buttons.invoke(s.pause, 0)
        settle()
    }
    private fun press(v: HomePauseView, slot: Int) {
        if (field(v, "seeded") == false) buttons.invoke(v, 0)
        buttons.invoke(v, 1 shl slot); buttons.invoke(v, 0)
        ShadowLooper.idleMainLooper()
    }
    private fun tap(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
        ShadowLooper.idleMainLooper()
    }
    private fun draw(v: View, w: Int, h: Int): Bitmap {
        val warm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); v.draw(Canvas(warm)); ShadowLooper.idleMainLooper()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); v.draw(Canvas(b)); return b
    }

    // ---------------------------------------------------------------- the button below Edit

    /** the bottom screen's own size (the activity's window lays the stage: 1240 x 1080 here, not the class's 1920 x 1080) */
    @Config(qualifiers = "w1240dp-h1080dp-mdpi")
    @Test fun twoScreensTheButtonSitsBelowEditAndYSwitches() {
        val screen = RectF(0f, 0f, BW.toFloat(), BH.toFloat())
        val b = UiKit.pauseScreensBox(screen)
        assertEquals(RectF(1107f, 149f, 1224f, 266f), b)
        val e = UiKit.pauseEditBox(screen)
        assertEquals("Edit's width", e.width(), b.width(), 0.01f); assertEquals("right under Edit", e.bottom + 16f, b.top, 0.01f)
        // no second screen: not drawn, Y does nothing (a one-screen device)
        SingleScreen.offered = false
        val s0 = bottomStage(); open(s0)
        press(s0.pause, PadInput.SLOT_FACE_TOP)
        assertTrue("the wheel stays", s0.pause.isOpen); assertTrue(s0.did.isEmpty())
        // a second screen: Y folds the wheel and asks for one screen
        SingleScreen.offered = true
        val s = bottomStage(); open(s)
        save(draw(s.root, BW, BH), "core_01_wheel_two_screens.png")
        press(s.pause, PadInput.SLOT_FACE_TOP)
        settle(300)
        assertFalse(s.pause.isOpen)
        assertEquals(listOf("pick:screens"), s.did)
        // a finger lifted on it = Y
        val t = bottomStage(); open(t, start = false)
        assertTrue("offered", SingleScreen.offered)
        assertTrue("on the button", UiKit.pauseScreensHit(screen, b.centerX(), b.centerY()))
        assertEquals("outside the wheel", -1, UiKit.pauseRadialHit(screen, b.centerX(), b.centerY()))
        tap(t.pause, b.centerX(), b.centerY())
        settle(300)
        assertEquals(listOf("pick:screens"), t.did)
        // the hub still closes on two screens (no glance: nothing picked)
        val h = bottomStage(); open(h, start = false)
        val hub = UiKit.pauseDisc(screen, android.graphics.PointF(BW / 2f, BH - 60f), 140f, UiKit.PAUSE_OPEN_MS)
        assertEquals(UiKit.PAUSE_HUB, UiKit.pauseRadialHit(screen, hub.cx, hub.cy))
        tap(h.pause, hub.cx, hub.cy)
        settle(300)
        assertFalse(h.pause.isOpen); assertTrue(h.did.isEmpty())
    }

    @Test fun oneScreenTheCornersAtTheScreensCornersTheHubIsHomesGlance() {
        val screen = RectF(0f, 0f, TW.toFloat(), TH.toFloat())
        assertEquals(RectF(1787f, 149f, 1904f, 266f), UiKit.pauseScreensBox(screen))
        assertEquals(RectF(1787f, 16f, 1904f, 133f), UiKit.pauseEditBox(screen))
        assertEquals(RectF(16f, 16f, 133f, 133f), UiKit.pausePowerBox(screen))
        SingleScreen.offered = true
        val s = topStage(); open(s)
        val pic = backdrop(); val c = Canvas(pic); dim(c, SingleLook.DIM_PANEL); c.drawBitmap(draw(s.root, TW, TH), 0f, 0f, null)
        save(pic, "core_02_wheel_one_screen.png")
        // A with nothing lit: Home's glance
        press(s.pause, PadInput.SLOT_FACE_BOTTOM)
        settle(300)
        assertFalse(s.pause.isOpen); assertEquals(listOf("pick:home"), s.did)
        // a tap on the hub: the same
        val h = topStage(); open(h, start = false)
        val hub = UiKit.pauseDisc(screen, android.graphics.PointF(TW / 2f, TH - Strip.HEIGHT / 2f), 140f, UiKit.PAUSE_OPEN_MS)
        assertEquals(UiKit.PAUSE_HUB, UiKit.pauseRadialHit(screen, hub.cx, hub.cy))
        tap(h.pause, hub.cx, hub.cy)
        settle(300)
        assertEquals(listOf("pick:home"), h.did)
        // a tap on the dim beside the panel closes the wheel, nothing picked
        val d = topStage(); open(d, start = false)
        tap(d.pause, 150f, 700f)
        settle(300)
        assertFalse(d.pause.isOpen); assertTrue(d.did.isEmpty())
        // Y on the top screen: back to two screens
        val y = topStage(); open(y)
        press(y.pause, PadInput.SLOT_FACE_TOP)
        settle(300)
        assertEquals(listOf("pick:screens"), y.did)
    }

    @Test fun theRefusalsStayOnTheWheelsRedLine() {
        SingleScreen.offered = true
        // the review (2026-11-25): the top screen's wheel shows only the words it can meet (Swap screens and Streaming never reach single
        // screen mode: two screens' words); the bottom screen's window first (an activity of ours up there)
        val words = listOf(SingleScreen.NOT_LOADING, SingleScreen.NOT_FIGHT, SingleScreen.BOTTOM_FIRST)
        val band = 270
        val sheet = Bitmap.createBitmap(TW, band * words.size, Bitmap.Config.ARGB_8888)
        val sc = Canvas(sheet)
        for ((i, w) in words.withIndex()) {
            val s = topStage(refusal = w); open(s)
            press(s.pause, PadInput.SLOT_FACE_TOP)
            settle(100)
            assertTrue("$w: the wheel stays", s.pause.isOpen)
            assertEquals(w, field(s.pause, "line"))
            assertTrue(s.did.isEmpty())
            val pic = backdrop(); val c = Canvas(pic); dim(c, SingleLook.DIM_PANEL); c.drawBitmap(draw(s.root, TW, TH), 0f, 0f, null)
            sc.drawBitmap(pic, android.graphics.Rect(0, 0, TW, band), android.graphics.Rect(0, i * band, TW, (i + 1) * band), null)
        }
        save(sheet, "core_05_refusals.png")
    }

    /** the review (2026-11-25): Edit (X) in a fight while playing - the wheel's red line says it and the wheel stays (Home's pencils, which
     *  flash on two screens, are on a page not drawn); nothing refused = the wheel folds and Edit opens, as on two screens */
    @Test fun editInAFightIsRefusedOnTheWheel() {
        SingleScreen.offered = true
        val s = topStage(); s.pause.editRefusal = { SingleScreen.NOT_FIGHT }; open(s)
        press(s.pause, PadInput.SLOT_FACE_LEFT)
        settle(100)
        assertTrue("the wheel stays", s.pause.isOpen)
        assertEquals(SingleScreen.NOT_FIGHT, field(s.pause, "line"))
        assertTrue("no pick", s.did.isEmpty())
        val pic = backdrop(); val c = Canvas(pic); dim(c, SingleLook.DIM_PANEL); c.drawBitmap(draw(s.root, TW, TH), 0f, 0f, null)
        save(pic, "core_09_edit_refused_in_a_fight.png")
        val ok = topStage(); open(ok)
        press(ok.pause, PadInput.SLOT_FACE_LEFT)
        settle(600)
        assertEquals("no refusal: Edit as always", listOf("pick:${HomePauseModel.EDIT.key}"), ok.did)
    }

    /**
     * the review (2026-11-25, "Single mode puts DS Forever's opaque waiting page over the game's login and character select"): with the
     * game screen up the waiting page is its row alone along the top screen's foot (SingleLook row 2b, the strip SingleNpc.startRect,
     * StartView.bare): no loading scene - the game's character select shows round it and every touch outside the row is the game's
     */
    @Test fun theWaitingPageIsItsRowOverTheGame() {
        Tr.init(app)
        val up0 = LauncherHooks.gameUp
        try {
            LauncherHooks.gameUp = { true }
            val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
            val sv = StartView(act)
            sv.bare = true
            sv.line = "Waiting for the world."
            sv.login = StartView.Login.SAVED
            val r = SingleNpc.startRect(TW, TH)
            val w = r[2] - r[0]; val h = r[3] - r[1]
            assertEquals("the box's width", BW, w)
            lay(sv, 0, 0, w, h)
            val strip = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            sv.draw(Canvas(strip))
            assertEquals("no loading scene behind the row (the game shows)", 0, Color.alpha(strip.getPixel(4, 4)))
            assertEquals(0, Color.alpha(strip.getPixel(w - 5, 4)))
            assertTrue("the line's panel drawn", Color.alpha(strip.getPixel(w / 2, h - 40 - 55)) > 0)
            assertTrue("the sign-in row drawn", Color.alpha(strip.getPixel(w / 2 + 60, h - 40 - 111 - 24 - 55)) > 0)
            val pic = backdrop("SINGLE_TOP_PLAY"); Canvas(pic).drawBitmap(strip, r[0].toFloat(), r[1].toFloat(), null)
            save(pic, "core_08_waiting_row.png")
            val l = SingleLook.of(SingleLook.In(waiting = true))
            assertEquals(SingleLook.Frame.HIDDEN, l.frame); assertEquals(SingleNpc.START, l.strip)
        } finally { LauncherHooks.gameUp = up0 }
    }

    // ---------------------------------------------------------------- the panel, the glance, playing, the hints

    private fun l(t: String, c: String = "ffffff") = JSONObject().put("t", t).put("c", c)
    private fun item(slot: Int, id: Int, name: String, icon: Int, q: Int = 1, count: Int = 1) =
        JSONObject().put("bag", 0).put("slot", slot).put("id", id).put("icon", icon).put("count", count).put("quality", q).put("name", name)
            .put("itype", "Miscellaneous").put("isub", "Junk").put("cls", 15).put("sub", 0)
    private fun bagsDoc(): JSONObject {
        val a = JSONArray()
        a.put(item(1, 6948, "Hearthstone", 134414).put("use", true).put("cls", 0))
        a.put(item(2, 5523, "Small Barnacled Clam", 134433).put("opens", true))
        a.put(item(4, 2589, "Linen Cloth", 132889, count = 12).put("cls", 7).put("itype", "Trade Goods").put("isub", "Cloth"))
        a.put(item(5, 118, "Minor Healing Potion", 134829, count = 4).put("use", true).put("cls", 0))
        a.put(item(6, 2835, "Rough Stone", 135241, count = 7).put("cls", 7).put("itype", "Trade Goods").put("isub", "Parts"))
        val ctl = JSONObject().put("on", true).put("mode", "grid").put("seq", 1).put("sid", 1).put("open", true)
            .put("cur", JSONObject().put("bag", 0).put("slot", 1))
            .put("tip", JSONObject().put("key", "bag 0 1 6948").put("lines", JSONArray().put(l("Hearthstone")).put(l("Use: Returns you to your home.", "00ff00"))))
        return JSONObject().put("free", 11).put("total", 16).put("money", 10234)
            .put("bags", JSONArray().put(JSONObject().put("bag", 0).put("slots", 16).put("free", 11).put("fam", 0)))
            .put("items", a).put("ctl", ctl)
            .put("pad", JSONObject().put("rows", JSONObject().put("bmvq", 27).put("bag0", 30).put("bmv", 29).put("bagsort", 28)))
    }

    /** the host's content as single screen mode lays it: the rooms column in the box (SingleScreen.boxMargins on its params) */
    private fun panelHost(room: View, active: Room): Pair<FrameLayout, LinearLayout> {
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val content = FrameLayout(act)
        val column = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; ThemeLive.bg(this, Tok.INK) }
        (room.parent as? android.view.ViewGroup)?.removeView(room)
        column.addView(room, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val strip = Strip(act) { }
        strip.active = active
        column.addView(strip, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, Strip.HEIGHT))
        val lp = FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        val m = SingleScreen.boxMargins(intArrayOf(0, 0, 0, 0), SingleScreen.panelBox(TW, TH), TW, TH)
        lp.setMargins(m[0], m[1], m[2], m[3])
        content.addView(column, lp)
        act.setContentView(content)
        lay(content, 0, 0, TW, TH)
        return content to column
    }

    @Test fun aRoomIsTheBottomScreensPage1To1InTheMiddle() {
        Tr.init(app)
        val bags = BagsView(app)
        bags.update(JSONObject(bagsDoc().toString()).put("ctl", JSONObject().put("on", false)), true)
        bags.update(bagsDoc(), true)
        val (content, column) = panelHost(bags, Room.BAGS)
        assertEquals("the box's left", 340, column.left); assertEquals(BW, column.width); assertEquals(BH, column.height)
        assertEquals("the room's own width: the bottom screen's", BW, bags.width)
        val warm = Bitmap.createBitmap(TW, TH, Bitmap.Config.ARGB_8888)
        for (i in 0 until 4) { content.draw(Canvas(warm)); ShadowSystemClock.advanceBy(Duration.ofMillis(400)); ShadowLooper.idleMainLooper(); Thread.sleep(150); ShadowLooper.idleMainLooper() }
        val pic = backdrop(); val c = Canvas(pic); dim(c, SingleLook.DIM_PANEL); content.draw(c)
        save(pic, "core_03_room_panel_bags.png")
    }

    @Test fun homesGlance() {
        val s = bottomStage()
        val home = s.home!!
        val (content, column) = panelHost(home, Room.HOME)
        assertEquals(340, column.left)
        home.tick(); ShadowLooper.idleMainLooper(); lay(content, 0, 0, TW, TH)
        val warm = Bitmap.createBitmap(TW, TH, Bitmap.Config.ARGB_8888); content.draw(Canvas(warm)); ShadowLooper.idleMainLooper()
        val pic = backdrop(); val c = Canvas(pic); dim(c, SingleLook.DIM_PANEL); content.draw(c)
        save(pic, "core_04_home_glance.png")
        assertEquals("the glance is the panel's look", SingleLook.Root.HOME, SingleLook.of(SingleLook.In(glanceHub = true)).root)
    }

    @Test fun playingIsTheGameAlone() {
        val l = SingleLook.of(SingleLook.In())
        assertEquals(SingleLook.Frame.HIDDEN, l.frame)                // the top window's decor GONE: nothing of ours is drawn
        save(backdrop("SINGLE_TOP_PLAY"), "core_00_playing.png")
    }

    @Test fun theControllerHints() {
        Tr.init(app)
        val pic = backdrop("SINGLE_TOP_PLAY")
        val c = Canvas(pic)
        // the top window's hint plate: 640 x 72 at y 216 (fork/TopWindow), "Controller back"; the stuck one shown 100 px under it here
        UiKit.singleHint(c, RectF(640f, 216f, 1280f, 288f), SingleScreen.CONTROLLER_BACK)
        UiKit.singleHint(c, RectF(640f, 316f, 1280f, 388f), SingleScreen.TAP_FOR_KEYS)
        save(pic, "core_06_keys_hints.png")
    }

    @Test fun theButtonSheet() {
        Tr.init(app)
        val cell = 200
        val layouts = listOf(CastSettings.ButtonLayout.NINTENDO to "Nintendo", CastSettings.ButtonLayout.XBOX to "Xbox",
            CastSettings.ButtonLayout.PLAYSTATION to "PlayStation")
        val sheet = Bitmap.createBitmap(cell * 4 + 280, cell * layouts.size + 60, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.rgb(12, 12, 16))
        val tp = UiKit.bodyPaint(22f, Color.WHITE)
        val heads = listOf("One screen", "One screen (held)", "Two screens", "Two screens (held)")
        for ((i, h) in heads.withIndex()) c.drawText(h, 220f + i * cell + 10f, 36f, tp)
        val screen = RectF(0f, 0f, BW.toFloat(), BH.toFloat())
        val b = UiKit.pauseScreensBox(screen)
        for ((row, lay) in layouts.withIndex()) {
            CastSettings.buttonLayout = lay.first
            c.drawText(lay.second, 16f, 60f + row * cell + cell / 2f, tp)
            for (col in 0 until 4) {
                val x = 220f + col * cell + (cell - b.width()) / 2f
                val y = 60f + row * cell + (cell - b.height()) / 2f
                val sv = c.save()
                c.translate(x - b.left, y - b.top)
                UiKit.pauseScreens(c, screen, 1f, down = col % 2 == 1, single = col >= 2)
                c.restoreToCount(sv)
            }
        }
        save(sheet, "core_07_button_sheet.png")
    }
}
