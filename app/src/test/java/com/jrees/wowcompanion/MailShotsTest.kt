package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
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
 * THE MAIL ENVELOPE ON HOME, OFF-DEVICE (the user, 2026-11-16: "we need to add an icon somewhere to the home menu for when the player has
 * mail"; HomeMailButton, Calendar c15). The real HomeView, its touches and its open map module's pad route:
 *  - the envelope shows beside the Calendar button only while the calendar document says there is mail, the band one button shorter;
 *  - its first touch opens the senders card (in a fight too); a touch anywhere on Home closes it - on the map it does nothing else,
 *    elsewhere it goes on (the controller's buttons still cast); B closes it; the class wheel closes it; the Calendar button keeps
 *    its own touch beside it; mail gone = the card gone (and the open map's cursor onto the Calendar's stop);
 *  - the open map module: Right from the map reaches the envelope, Right again the Calendar, Left back; A opens / closes the card; B
 *    closes the card, then goes back to the map;
 *  - a long zone name steps its size down rather than being cut.
 * With MAIL_SHOTS_DIR set the page is drawn as home_mail_<case>.png (1240 x 1080, the bar under it): the documents are tools/test_mail.py's
 * own (MAIL_DUMP=<MAIL_SHOTS_DIR>/docs), else built here; MAIL_ART_ROOT = an art root laid out like the app's. The checks run always.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MailShotsTest {
    private val out: File? = System.getenv("MAIL_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    private fun art() {
        System.getenv("MAIL_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
    }

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun settle() { for (i in 0 until 30) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }

    /** the calendar document [name] as tools/test_mail.py wrote it, else the same shape built here */
    private fun doc(name: String): JSONObject {
        out?.let { d -> File(d, "docs/$name.json").takeIf { it.isFile }?.let { return JSONObject(it.readText()) } }
        val mail = when (name) {
            "none" -> JSONObject().put("on", false)
            "one" -> JSONObject().put("on", true).put("from", JSONArray().put("Brannoc"))
            "three" -> JSONObject().put("on", true).put("from", JSONArray().put("Brannoc").put("Auction House").put("Thalia Ashgrove"))
            "nobody" -> JSONObject().put("on", true)
            else -> JSONObject().put("on", true).put("from", JSONArray().put("Feathermoon Stronghold Quartermaster Long Name")
                .put("Brannoc").put("Thalia Ashgrove"))
        }
        return JSONObject().put("type", "calendar").put("c", JSONObject().put("v", "c15").put("mail", mail))
    }

    private var calendarOpened = 0

    private fun home(zone: String, mailDoc: String? = "one", buffs: Boolean = false): HomeView {
        art()
        HomeSettings.init(app)
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.onCalendar = { calendarOpened++ }
        home.borrow()
        lay(home, W, H)
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        home.onFull(JSONObject().put("type", "full").put("map", JSONObject().put("zone", zone).put("pvp", "contested"))
            .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true))), true)
        if (buffs) {
            // two buffs and a debuff (the game's icons from the art root's icons.pak), and a tracked, focused quest for the card
            home.onAuras(JSONObject().put("type", "auras").put("now", 100.0)
                .put("b", JSONArray().put(JSONObject().put("id", 1).put("n", "Lightning Shield").put("i", 136051).put("exp", 600.0))
                    .put(JSONObject().put("id", 2).put("n", "Power Word: Fortitude").put("i", 135987).put("exp", 1800.0)))
                .put("d", JSONArray().put(JSONObject().put("id", 3).put("n", "Weakened Soul").put("i", 135871).put("exp", 112.0))), true)
            val q = JSONObject().put("plv", 6).put("list", JSONArray().put(JSONObject().put("id", 7).put("title", "Kobold Camp Cleanup")
                .put("lv", 6).put("watch", 1).put("focus", true)
                .put("obj", JSONArray().put(JSONObject().put("t", "Kobold Vermin slain: 4/10")))))
            map.onQuests(q); home.onQuests(q, true)
        }
        home.calPending = false
        mailDoc?.let { home.mail = HomeMail.parse(doc(it)) }
        home.tick()
        ShadowLooper.idleMainLooper()
        lay(home, W, H)
        // the page laid once it has its size (on the device the size's post does it; a detached view never runs a post)
        HomeView::class.java.getDeclaredMethod("changed").apply { isAccessible = true }.invoke(home)
        settle()
        lay(home, W, H)
        return home
    }

    private fun fight(home: HomeView, on: Boolean) {
        home.onFull(JSONObject().put("type", "full").put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true))
            .put("hudS", JSONObject().put("c", on))), true)
        home.tick()
        settle()
        lay(home, W, H)
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapBox(home: HomeView): RectF {
        val f = HomeView::class.java.getDeclaredField("now"); f.isAccessible = true
        val now = f.get(home) as Map<Any, RectF>
        return RectF(now.entries.firstOrNull { it.key.toString() == "MAP" }?.value ?: error("no MAP: ${now.keys}"))
    }

    private fun full(home: HomeView): Any? = home.flow.full

    private fun tap(home: HomeView, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        home.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        home.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0))
        ShadowLooper.idleMainLooper()
    }

    private var padN = 0
    private fun key(home: HomeView, k: String) { home.auraPad(JSONObject().put("k", k).put("n", ++padN)); ShadowLooper.idleMainLooper() }
    /** 2026-11-17 (HomeMapPages): from the quest list Right is the map controls, Right again the pane (the auras, the envelope, the Calendar) */
    private fun toPane(home: HomeView) {
        key(home, "PADDRIGHT"); assertEquals("Right from the list: the map controls", HomeMapPages.Page.CONTROLS, home.mapPageNow())
        key(home, "PADDRIGHT")
    }

    private fun shot(home: HomeView, name: String) {
        val d = out ?: return
        d.mkdirs()
        val bm = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        val strip = Strip(app) { }
        for (pass in 0..1) {
            val c = Canvas(bm); c.drawColor(Theme.ink)
            home.draw(c)
            lay(strip, W, Strip.HEIGHT)
            c.save(); c.translate(0f, H.toFloat()); strip.draw(c); c.restore()
            if (pass == 0) { ShadowLooper.idleMainLooper(); Thread.sleep(150); ShadowLooper.idleMainLooper() }
        }
        File(d, "home_mail_$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---------------------------------------------------------------- the envelope's place

    @Test fun theEnvelopeShowsOnlyWithMail() {
        val home = home("Elwynn Forest", mailDoc = "none")
        assertTrue("no mail: no envelope", home.mailBoxOnHome().isEmpty)
        shot(home, "none")
        home.mail = HomeMail.parse(doc("one"))
        val m = mapBox(home)
        val b = HomeMailButton.box(m.right, m.top)
        val on = home.mailBoxOnHome()
        assertEquals(b[0], on.left, 0.5f); assertEquals(b[1], on.top, 0.5f); assertEquals(b[2], on.right, 0.5f)
        // beside the Calendar button, on the band's strip (the buff row starts at BTN_TOP, 80 under the map's top)
        assertEquals(HomeCalendarButton.box(m.right, m.top)[0] - HomeMailButton.GAP, on.right, 0.5f)
        assertTrue(on.bottom <= m.top + HomeView.BTN_TOP)
        shot(home, "one")
        home.mail = HomeMail.parse(doc("three")); shot(home, "three")
        home.mail = HomeMail.parse(doc("none"))
        assertTrue("all read: the envelope goes", home.mailBoxOnHome().isEmpty)
        home.mail = null
        assertTrue("an older add-on: none", home.mailBoxOnHome().isEmpty)
    }

    @Test fun itCoversNeitherTheBuffsNorTheQuestCard() {
        val home = home("Elwynn Forest", mailDoc = "three", buffs = true)
        val on = home.mailBoxOnHome()
        assertFalse(on.isEmpty)
        val m = mapBox(home)
        assertTrue("above the buff row", on.bottom <= m.top + HomeView.BTN_TOP)
        assertTrue("above the quest card (under the band)", on.bottom <= m.top + HomeView.ZONE_BAND_BOTTOM)
        shot(home, "buffs")
        tap(home, on.centerX(), on.centerY())
        shot(home, "buffs_card")
    }

    // ---------------------------------------------------------------- the touch

    @Test fun aTouchOpensTheCardAndAnyTouchClosesIt() {
        val home = home("Elwynn Forest", mailDoc = "three")
        val on = home.mailBoxOnHome()
        tap(home, on.centerX(), on.centerY())
        assertTrue("the envelope's touch opens the card", home.mailOpen)
        assertNull("the map module did not open", full(home))
        shot(home, "card_three")
        val m = mapBox(home)
        tap(home, m.centerX(), m.centerY())
        assertFalse("a touch elsewhere closes it", home.mailOpen)
        assertNull("... and the map stays small", full(home))
        tap(home, on.centerX(), on.centerY()); assertTrue("the next touch is the page's again (no swallow left)", home.mailOpen)
        tap(home, on.centerX(), on.centerY())
        assertFalse("a second touch on the envelope closes it", home.mailOpen)
        // with the card open, a touch on the Calendar button only closes the card (that touch does nothing else)
        val cal = HomeCalendarButton.box(m.right, m.top)
        val cx = (cal[0] + cal[2]) / 2f; val cy = (cal[1] + cal[3]) / 2f
        val before = calendarOpened
        tap(home, on.centerX(), on.centerY()); assertTrue(home.mailOpen)
        tap(home, cx, cy)
        assertFalse(home.mailOpen); assertEquals("the closing touch did not open the Calendar", before, calendarOpened)
        // the Calendar button keeps its own touch beside the envelope
        tap(home, cx, cy)
        assertEquals(before + 1, calendarOpened); assertFalse(home.mailOpen)
        // the near edge of the envelope's finger box (the band's end) is the envelope's, the band's middle the map's
        tap(home, on.left - 10f, on.centerY()); assertTrue(home.mailOpen); tap(home, 10f, 10f)
        assertFalse(home.mailOpen)
    }

    @Test fun bClosesTheCard() {
        val home = home("Elwynn Forest", mailDoc = "one")
        val on = home.mailBoxOnHome()
        var told = 0
        home.onStateChanged = { told++ }
        assertFalse("nothing of Home's open yet", home.bClosesSomething())
        tap(home, on.centerX(), on.centerY()); assertTrue(home.mailOpen)
        // the 1.0.33 merge review (feat/wk-mail x feat/wk-b-pet-follow): the card is a thing B closes, so B: pet recall's rest-layout B goes off (was Smart pet / B: pet follow)
        // while it is open, and the host is told at once (its petBTell rides onStateChanged)
        assertTrue("the open card is something B closes (no pet follow)", home.bClosesSomething())
        assertTrue("the card's opening told the host", told >= 1)
        shot(home, "card_one")
        val beforeB = told
        home.pressB()
        assertFalse("B closes it", home.mailOpen)
        assertFalse("closed: Home's plain B again", home.bClosesSomething())
        assertTrue("the card's closing told the host", told > beforeB)
        assertNull(full(home))
    }

    private fun field(home: HomeView, name: String): Any? =
        HomeView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(home)

    @Test fun theClosingTouchStillReachesTheController() {
        // review of feat/wk-mail: with the card open a touch on the controller's buttons closes it AND goes on to the buttons (a touch
        // cast in a fight is never eaten); only a touch on the map (where the card hangs) does nothing else
        val home = home("Elwynn Forest", mailDoc = "one")
        fight(home, true)
        val on = home.mailBoxOnHome()
        tap(home, on.centerX(), on.centerY()); assertTrue(home.mailOpen)
        val bars = field(home, "barsSlot") as View
        val x = (bars.left + bars.right) / 2f; val y = (bars.top + bars.bottom) / 2f
        assertTrue("the controller lies off the map", !mapBox(home).contains(x, y))
        val t = SystemClock.uptimeMillis()
        home.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        assertFalse("the touch closes the card", home.mailOpen)
        assertTrue("... and the controller's buttons have it", field(home, "barsDown") as Boolean)
        assertFalse("... not swallowed", field(home, "mailSwallow") as Boolean)
        home.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0))
        ShadowLooper.idleMainLooper()
        // on the map the closing touch is still swallowed (the map's own touch does not run)
        tap(home, on.centerX(), on.centerY()); assertTrue(home.mailOpen)
        val m = mapBox(home)
        val t2 = SystemClock.uptimeMillis()
        home.dispatchTouchEvent(MotionEvent.obtain(t2, t2, MotionEvent.ACTION_DOWN, m.centerX(), m.centerY(), 0))
        assertFalse(home.mailOpen); assertTrue("the map's closing touch is swallowed", field(home, "mailSwallow") as Boolean)
        home.dispatchTouchEvent(MotionEvent.obtain(t2, t2 + 60, MotionEvent.ACTION_UP, m.centerX(), m.centerY(), 0))
        assertFalse("its end lets go", field(home, "mailSwallow") as Boolean)
    }

    @Test fun theClassRadialClosesTheCard() {
        // one overlay at a time: the class wheel's first touch is the wheel's, never a hidden card's close
        val home = home("Elwynn Forest", mailDoc = "one")
        val on = home.mailBoxOnHome()
        tap(home, on.centerX(), on.centerY()); assertTrue(home.mailOpen)
        HomeView::class.java.getDeclaredMethod("classLayerShow", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(home, true)
        assertFalse(home.mailOpen)
    }

    @Test fun mailGoneMovesTheOpenMapsCursorOntoTheCalendar() {
        val home = home("Elwynn Forest", mailDoc = "three")
        assertTrue(home.openMapModule())
        settle(); lay(home, W, H)
        toPane(home); assertEquals("mail", home.cornerFocus())
        home.mail = HomeMail.parse(doc("none"))
        assertEquals("the cursor never rests on nothing", "calendar", home.cornerFocus())
        val before = calendarOpened
        key(home, "PAD1"); assertEquals(before + 1, calendarOpened)
        // a card opened by a touch while the cursor is elsewhere (on the Calendar): B closes the card first, the cursor stays
        home.mail = HomeMail.parse(doc("one"))
        assertNull("A left the cursor on the map", home.cornerFocus())
        toPane(home); assertEquals("mail", home.cornerFocus())
        key(home, "PADDRIGHT"); assertEquals("calendar", home.cornerFocus())
        val on = home.mailBoxOnHome()
        tap(home, on.centerX(), on.centerY()); assertTrue("the envelope's touch in the open map", home.mailOpen)
        key(home, "PAD2"); assertFalse("B closes the card first", home.mailOpen)
        assertEquals("... and does nothing else", "calendar", home.cornerFocus())
        tap(home, on.centerX(), on.centerY()); assertTrue(home.mailOpen)
        key(home, "PADDLEFT"); assertFalse("another key closes it and goes on", home.mailOpen)
        assertEquals("mail", home.cornerFocus())
    }

    @Test fun mailGoneClosesTheCard() {
        val home = home("Elwynn Forest", mailDoc = "nobody")
        val on = home.mailBoxOnHome()
        tap(home, on.centerX(), on.centerY()); assertTrue(home.mailOpen)
        shot(home, "card_nobody")
        home.mail = HomeMail.parse(doc("none"))
        assertFalse(home.mailOpen)
        home.mailCard(true)
        assertFalse("no envelope, no card", home.mailOpen)
    }

    @Test fun theFightView() {
        val home = home("Elwynn Forest", mailDoc = "one")
        fight(home, true)
        assertTrue(home.fightShown())
        val m = mapBox(home)
        val on = home.mailBoxOnHome()
        val b = HomeMailButton.box(m.right, m.top)
        assertEquals("the fight view's map has the envelope too", b[0], on.left, 0.5f)
        assertTrue(on.left > m.left)
        shot(home, "fight")
        tap(home, on.centerX(), on.centerY())
        assertTrue("its first touch opens the card in a fight too", home.mailOpen)
        shot(home, "fight_card")
        fight(home, false)
        assertFalse("the fight view going closes the card", home.mailOpen)
    }

    @Test fun thePauseSlide() {
        if (out == null) return
        // the pause menu's slide: the envelope (and its card) go out with the band and the map, on the same curve
        for ((k, tag) in listOf(0.03f to "start", 0.08f to "mid")) {
            val home = home("Stormwind City", mailDoc = "one")
            home.mailBoxOnHome().let { tap(home, it.centerX(), it.centerY()) }
            home.pauseSlide(UiKit.PAUSE_OPEN_MS * k)
            shot(home, "pause_$tag")
        }
    }

    // ---------------------------------------------------------------- long names

    @Test fun longZoneNamesStepDownBeforeTheyAreCut() {
        val home = home("Lordamere Internment Camp", mailDoc = "one")
        shot(home, "long_zone")
        shot(home("Lordamere Internment Camp", mailDoc = "none"), "long_zone_no_mail")
        shot(home("The Great Sea of Endless Storms and Grief", mailDoc = "one"), "very_long_zone")
        shot(home("Elwynn Forest", mailDoc = "long").also { h -> h.mailBoxOnHome().let { tap(h, it.centerX(), it.centerY()) } }, "card_long")
        // the band's room with the envelope: 452 px less the plate's ends; the name's size steps down to fit, never under ZONE_MIN_K
        val w = HomeMailButton.bandRight(608f, true) - 8f
        val s = UiKit.zoneTextSize("Lordamere Internment Camp", w, 64f)
        assertTrue("never smaller than the floor: $s", s >= 64f * UiKit.ZONE_MIN_K - 0.01f)
        assertTrue("no larger than half the band: $s", s <= 32f + 0.01f)
        assertEquals("a short name keeps its size", 32f, UiKit.zoneTextSize("Goldshire", w, 64f), 0.01f)
    }

    // ---------------------------------------------------------------- the open map module's pad route

    @Test fun theOpenMapsPadRoute() {
        val home = home("Elwynn Forest", mailDoc = "three")
        assertTrue(home.openMapModule())
        settle(); lay(home, W, H)
        assertEquals("MAP", full(home).toString())
        assertNull(home.cornerFocus())
        toPane(home)
        assertEquals("Right from the map controls (no auras): the envelope", "mail", home.cornerFocus())
        shot(home, "map_cursor")
        key(home, "PADDRIGHT"); assertEquals("Right again: the Calendar button", "calendar", home.cornerFocus())
        key(home, "PADDLEFT"); assertEquals("Left: back onto the envelope", "mail", home.cornerFocus())
        key(home, "PAD1"); assertTrue("A opens the card", home.mailOpen)
        shot(home, "map_card")
        key(home, "PAD2"); assertFalse("B closes the card", home.mailOpen)
        assertEquals("... and stays on the envelope", "mail", home.cornerFocus())
        key(home, "PAD1"); assertTrue(home.mailOpen)
        key(home, "PAD1"); assertFalse("A again closes it", home.mailOpen)
        key(home, "PAD1"); key(home, "PADDRIGHT")
        assertFalse("leaving the envelope closes the card", home.mailOpen); assertEquals("calendar", home.cornerFocus())
        val before = calendarOpened
        key(home, "PAD1"); assertEquals("A on the Calendar still opens it", before + 1, calendarOpened)
        toPane(home); assertEquals("mail", home.cornerFocus())
        key(home, "PAD2"); assertNull("B with no card: back to the map", home.cornerFocus())
        assertEquals("... its quest list", HomeMapPages.Page.QUESTS, home.mapPageNow())
        assertEquals("MAP", full(home).toString())
        // the module closing closes the card
        toPane(home); key(home, "PAD1"); assertTrue(home.mailOpen)
        home.homeDisc(); settle()
        assertFalse(home.mailOpen)
    }

    @Test fun withoutMailTheRouteIsTheCalendarsAsBefore() {
        val home = home("Elwynn Forest", mailDoc = "none")
        assertTrue(home.openMapModule())
        settle(); lay(home, W, H)
        toPane(home); assertEquals("calendar", home.cornerFocus())
        key(home, "PADDLEFT"); assertNull(home.cornerFocus())
        assertEquals("Left off the pane: the map controls", HomeMapPages.Page.CONTROLS, home.mapPageNow())
    }

    @Test fun theStandInEnvelope() {
        // the drawn envelope (no kit picture on the device yet), 2x
        val d = out ?: return
        art()
        val bm = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm); c.drawColor(Theme.ink)
        c.scale(2f, 2f)
        UiKit::class.java.getDeclaredMethod("envelope", Canvas::class.java, RectF::class.java).apply { isAccessible = true }
            .invoke(UiKit, c, RectF(12f, 8f, 68f, 32f))
        File(d, "home_mail_standin_2x.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
