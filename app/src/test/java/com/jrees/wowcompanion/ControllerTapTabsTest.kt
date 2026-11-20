package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
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
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * SITUATIONS FIRST (the user, 2026-11-20, the Controller room's Option C; PadTabCtl p56, ActionBar cb15, Book b71), the app half. It
 * replaces the p55 Controller | Tap to cast sub-tabs this test was first written for:
 *  - ONE row of situations in the user's order: Out of combat / In combat / Party mode / Spell radial ("WoW Defaults" is no tab name);
 *  - each situation's chips: Out of combat the DS WoW toolbar; In combat Combat controls, Start attack on cast, Show tap-to-cast (=
 *    castBarCombat); Party mode "Bar in Party mode" (= castBarPartyShow, on by default, a finger or the add-on's `cbpn` flips it, told to
 *    the add-on as padtab 39, 38 then 2 / 3 on every change and every room open);
 *  - both devices on every page: a finger on the bar's slot from the controller opens that situation's bar first (39 then 29, 39 then
 *    1, or 39, 38 then 1), a finger on the controller from the bar sends the situation's own tab first; B leads Home only from the tabs;
 *  - the locks: Show in combat off locks In combat's bar ("Hidden in fights"), Bar in Party mode off locks Party mode's, the bar Off
 *    shows Turn on;
 *  - the Bind box: the three situations as tabs with both devices; LB / RB walk the six stops in reading order (each situation's
 *    controller, then its bar); a finger on a situation = its controller, on a bar slot = that situation's bar then the slot; Party
 *    mode's controller is 39, 38 then 4, its bar 39, 38 then 5; Macros and Bags get the bar too.
 * tools/test_controller_situations.py proves the add-on half; ControllerSituationsShotsTest draws every page and state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControllerTapTabsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 1240
    private val h = 960

    private var sideWas = CastStripModel.Side.OFF
    private var combatWas = CastStripModel.Combat.HIDE
    private var partyWas = true
    @Before fun up() {
        HomeSettings.init(app); sideWas = HomeSettings.castBarSide; combatWas = HomeSettings.castBarCombat; partyWas = HomeSettings.castBarPartyShow
        HomeSettings.castBarSide = CastStripModel.Side.BOTH; HomeSettings.castBarCombat = CastStripModel.Combat.STAY; HomeSettings.castBarPartyShow = true
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
    }
    @After fun down() { HomeSettings.castBarSide = sideWas; HomeSettings.castBarCombat = combatWas; HomeSettings.castBarPartyShow = partyWas }

    private val barDoc = JSONObject(ControllerSituationsShotsTest.BAR)

    private fun stand(): PadMapModel.Map = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m176","m":{"page":1,"pages":3,
        "sets":[{"set":1,"slots":[{"pos":4,"s":4,"kind":"none"},{"pos":1,"s":1,"kind":"none"}]}],
        "commands":[{"key":"TARGETNEARESTENEMY","n":"Target Nearest Enemy","i":132212,"made":true,"b":"/targetenemy"}],
        "binds":[{"key":"JUMP","n":"Jump","i":135992}],
        "mounts":[{"id":6,"n":"Brown Horse","i":132261}],
        "items":[{"id":118,"n":"Minor Healing Potion","i":134829}]}}"""))!!

    private fun book() = SpellsModel.parse(JSONObject(ControllerSituationsShotsTest.BOOK))!!

    private fun ctl(view: String, focus: String = "buttons", pos: Int = 1, extra: String = ""): PadMapModel.Ctl {
        val top = PadMapModel.topOf(view)
        val tcur = PadMapModel.TABS.indexOf(top) + 1
        return PadMapModel.parseCtl(JSONObject("""{"type":"padtabctl","v":"p56","c":{"on":true,"focus":"$focus","set":1,"shown":1,
            "pos":$pos,"page":1,"view":"$view","top":"$top","lay":"swap","tcur":$tcur,"sw":1,"cbpn":0,"cbps":1$extra}}"""))!!
    }

    private class Rig(val v: ControllerView) { val cells = ArrayList<List<Pair<String, Int>>>() }

    private fun rig(): Rig {
        CastStripModel.Live.set(CastStripModel.parse(barDoc)!!)
        lateinit var r: Rig
        val v = ControllerView(app, { c -> r.cells += c; true })
        r = Rig(v)
        v.update(book())
        v.onPadMap(stand())
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        return r
    }

    private fun Rig.draw() { val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); v.draw(Canvas(b)) }
    private fun touch(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        v.onTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0))
    }
    private fun Rig.tap(x: Float, y: Float) { draw(); touch(v, x, y); draw() }
    private fun Rig.tap(r: RectF) = tap(r.centerX(), r.centerY())

    private fun tabRect(v: ControllerView, i: Int): RectF {
        val m = ControllerView::class.java.getDeclaredMethod("tabRect", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        m.isAccessible = true
        return m.invoke(v, i, w.toFloat()) as RectF
    }
    private fun chip(v: ControllerView, i: Int): RectF {
        val m = ControllerView::class.java.getDeclaredMethod("swRect", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        m.isAccessible = true
        return m.invoke(v, i, 3) as RectF
    }
    /** the controller's place [p] on screen (the 0.8 picture) */
    private fun place(p: Int): RectF = SituationPage.toScreen(PadDiamonds.rect(PadDiamonds.square(p, CastSettings.faceSwap)))
    private val act = RectF(780f, h - 141f, w - 56f, h - 30f)
    private fun idle() = ShadowLooper.idleMainLooper()

    @Test fun oneRowOfSituationsInTheUsersOrder() {
        val r = rig()
        val want = listOf("normal", "combat", "party", "radial").filter { it != "party" || GameUi.partyModeOn() }
        assertEquals(want, r.v.tabViews())
        val names = ControllerView::class.java.getDeclaredField("ALL_TAB_NAMES").apply { isAccessible = true }.get(null) as List<*>
        assertEquals(listOf("Out of combat", "In combat", "Party mode", "Spell radial"), names)
        r.draw()
        for ((i, v) in want.withIndex()) assertTrue("tab $v is big ($i)", tabRect(r.v, i).height() >= 70f && tabRect(r.v, i).width() >= 220f)
    }

    @Test fun aFingerOnASituationOpensItsController() {
        val r = rig()
        r.v.onPadTabCtl(ctl("castbar", pos = 3)); r.cells.clear()
        r.tap(tabRect(r.v, 1))
        assertEquals("In combat: its own column", listOf(listOf(PadMapModel.ROW_PADTAB to PadMapModel.colTab(3))), r.cells)
        assertEquals("combat", r.v.page().currentView())
    }

    @Test fun aFingerOnTheBarFromTheControllerOpensThatBarFirst() {
        for ((view, open) in listOf("normal" to CastStripModel.ctlTabCells(), "combat" to CastStripModel.ctlFightCells(),
                                    "party" to CastStripModel.ctlPartyCells())) {
            if (view == "party" && !GameUi.partyModeOn()) continue
            val r = rig()
            r.v.onPadTabCtl(ctl(view, focus = if (view == "party") "pdiam" else "buttons", extra = if (view == "party") ""","ppos":8""" else ""))
            ShadowSystemClock.advanceBy(Duration.ofSeconds(3)); r.cells.clear()
            r.tap(SituationPage.tile(5))
            assertEquals("$view: its bar's pick, then slot 5", listOf(open + listOf(PadMapModel.ROW to PadMapModel.colPos(5))), r.cells)
            assertEquals(PadMapModel.barOf(view), r.v.page().currentView())
        }
    }

    @Test fun aFingerOnTheControllerFromTheBarSendsTheSituationFirst() {
        val r = rig()
        r.v.onPadTabCtl(ctl("cbfight", pos = 2)); r.cells.clear()
        r.tap(place(5))
        assertEquals("In combat's tab, then X (5)", listOf(listOf(PadMapModel.ROW_PADTAB to PadMapModel.colTab(3)),
            listOf(PadMapModel.ROW to PadMapModel.colPos(5))), r.cells)
        assertEquals("combat", r.v.page().currentView())
    }

    @Test fun bLeadsHomeOnlyFromTheTabs() {
        val r = rig()
        r.v.onCtl(SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b71","ctl":{"on":true,"croom":true,"mode":"book"}}"""))!!)
        r.v.onPadTabCtl(ctl("normal", focus = "tabs"))
        assertTrue(r.v.bLeadsHome())
        for ((v, f) in listOf("normal" to "buttons", "normal" to "switches", "castbar" to "buttons", "party" to "pdiam", "pbar" to "buttons")) {
            r.v.onPadTabCtl(ctl(v, focus = f))
            assertFalse("B on $v / $f is the room's (one step back)", r.v.bLeadsHome())
        }
    }

    @Test fun theChipsPerSituation() {
        val r = rig()
        r.v.onPadTabCtl(ctl("combat", focus = "switches")); r.draw()
        val l = (0..2).map { chip(r.v, it) }
        assertTrue("three chips side by side on the left page", l[0].right < l[1].left && l[1].right < l[2].left && l[2].right <= 712f)
        // a finger on Show tap-to-cast: castBarCombat flips, the add-on told 39 then 3
        idle(); r.cells.clear()
        r.tap(l[2]); idle()
        assertEquals(CastStripModel.Combat.HIDE, HomeSettings.castBarCombat)
        assertTrue("told: ${r.cells}", r.cells.contains(CastStripModel.showCells(false)))
    }

    @Test fun barInPartyModeIsOneSettingBothWays() {
        if (!GameUi.partyModeOn()) return
        val r = rig()
        r.v.onPadTabCtl(ctl("party", focus = "switches", extra = ",\"pfocus\":\"sw\"")); r.draw()
        idle(); r.cells.clear()
        val c = ControllerView::class.java.getDeclaredMethod("swRect", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(r.v, 0, 1) as RectF
        r.tap(c); idle()
        assertFalse("a finger: off", HomeSettings.castBarPartyShow)
        assertTrue("told 39, 38 then 3: ${r.cells}", r.cells.contains(CastStripModel.partyShowCells(false)))
        // the controller's A (the add-on's count): flipped once per count
        r.v.onPadTabCtl(ctl("party", focus = "switches", extra = ""","cbpn":1"""))
        assertTrue(HomeSettings.castBarPartyShow)
        r.v.onPadTabCtl(ctl("party", focus = "switches", extra = ""","cbpn":1"""))
        assertTrue("the same count again: no flip", HomeSettings.castBarPartyShow)
        // the room's open tells it with the bar's other settings
        r.cells.clear()
        assertTrue(r.v.enterRoom())
        assertTrue(r.cells.first { it.contains("spells" to 5) }.containsAll(CastStripModel.partyShowCells(true)))
    }

    @Test fun theLocks() {
        val r = rig()
        HomeSettings.castBarCombat = CastStripModel.Combat.HIDE
        r.v.onPadTabCtl(ctl("cbfight", pos = 3)); r.cells.clear()
        r.tap(act)
        assertFalse("Show off: no chooser", r.v.page().chooserOpen())
        assertTrue("nothing sent: ${r.cells}", r.cells.isEmpty())
        HomeSettings.castBarCombat = CastStripModel.Combat.STAY; idle()
        HomeSettings.castBarPartyShow = false
        r.v.onPadTabCtl(ctl("pbar", pos = 1)); r.cells.clear()
        r.tap(act)
        assertFalse("Bar in Party mode off: no chooser", r.v.page().chooserOpen())
        HomeSettings.castBarPartyShow = true; idle()
        r.v.onPadTabCtl(ctl("pbar", pos = 1)); r.cells.clear()
        r.tap(act)
        assertTrue("on again: Change opens the chooser", r.v.page().chooserOpen())
        // the bar Off: Turn on
        val r2 = rig()
        HomeSettings.castBarSide = CastStripModel.Side.OFF
        r2.v.onPadTabCtl(ctl("castbar", pos = 1, extra = ""","cbon":0"""))
        r2.tap(act)
        assertEquals("D2: Turn on = Both", CastStripModel.Side.BOTH, HomeSettings.castBarSide)
    }

    // ---------------------------------------------------------------- the Bind box (SpellsOverlay)

    private class Box(val o: SpellsOverlay) { val taps = ArrayList<Int>(); val cells = ArrayList<List<Pair<String, Int>>>() }

    private fun box(extra: String = "", id: Int = 8017): Box {
        CastStripModel.Live.set(CastStripModel.parse(barDoc)!!)
        lateinit var b: Box
        val o = SpellsOverlay(app, otherReader = { true }, onTap = { b.taps += it }, onCastBarTaps = { b.cells += it })
        b = Box(o)
        o.onBook(book())
        o.onCtl(SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b71","ctl":{"on":true,"mode":"bind","bind":$id$extra}}"""))!!)
        o.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080 - Strip.HEIGHT, View.MeasureSpec.EXACTLY))
        o.layout(0, 0, w, 1080 - Strip.HEIGHT)
        return b
    }
    private fun Box.draw() { val bm = Bitmap.createBitmap(w, 1080 - Strip.HEIGHT, Bitmap.Config.ARGB_8888); val c = Canvas(bm); c.drawColor(Theme.ink); o.draw(c) }
    private fun Box.tap(r: RectF) { draw(); touch(o, r.centerX(), r.centerY()); draw() }
    private fun Box.doc(extra: String, id: Int = 8017) = o.onCtl(SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b71","ctl":{"on":true,"mode":"bind","bind":$id$extra}}"""))!!)
    private fun Box.bump(rb: Boolean) {
        val m = SpellsOverlay::class.java.getDeclaredMethod("bumpers", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        m.isAccessible = true
        m.invoke(o, !rb, rb); m.invoke(o, false, false)
    }
    private fun sit(b: Box, i: Int): RectF = SpellsOverlay::class.java.getDeclaredMethod("sitRect", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(b.o, i, w.toFloat()) as RectF
    private fun slot(b: Box, i: Int): RectF = SpellsOverlay::class.java.getDeclaredMethod("cbSlotRect", Int::class.javaPrimitiveType,
        Float::class.javaPrimitiveType, Float::class.javaPrimitiveType).apply { isAccessible = true }.invoke(b.o, i, w.toFloat(), 0f) as RectF

    @Test fun theBindBoxIsThreeSituationsWithBothDevices() {
        val b = box()
        b.draw()
        assertTrue("one row of situations", sit(b, 0).right < sit(b, 1).left && sit(b, 0).top == sit(b, 1).top)
        assertTrue("the bar under them, the controller between", slot(b, 0).top > sit(b, 0).bottom + 300f)
        b.tap(sit(b, 1))
        assertEquals("In combat: its controller (bf-on)", listOf(12), b.taps)
        if (GameUi.partyModeOn()) {
            b.tap(sit(b, 2))
            assertEquals("Party mode: its controller (39, 38 then 4)", listOf(CastStripModel.page2(4)), b.cells)
        }
    }

    @Test fun aFingerOnABarSlotPicksTheSituationsBarThenTheSlot() {
        val b = box()
        b.tap(slot(b, 4))
        assertEquals("Out of combat: 39 then 26, then 39 then 24 (slot 5)", listOf(CastStripModel.tabCells(), CastStripModel.slotCells(4)!!), b.cells)
        if (!GameUi.partyModeOn()) return
        val p = box(""","bpm":1""", id = 331)
        p.tap(slot(p, 1))
        assertEquals("Party mode: 39, 38 then 5, then the slot", listOf(CastStripModel.page2(5), CastStripModel.slotCells(1)!!), p.cells)
        val q = box(""","bcb":2,"bcp":1""", id = 331)
        q.tap(slot(q, 3))
        assertEquals("already on its bar: the slot only", listOf(CastStripModel.slotCells(3)!!), q.cells)
    }

    @Test fun lbRbWalkTheSixStopsInReadingOrder() {
        val b = box()
        b.draw()
        b.bump(rb = true)
        assertEquals("Out of combat's bar", listOf(CastStripModel.tabCells()), b.cells)
        b.doc(""","bcb":1"""); b.cells.clear()
        b.bump(rb = true)
        assertEquals("then In combat's controller", listOf(12), b.taps)
        b.doc(""","bf":true"""); b.taps.clear()
        b.bump(rb = true)
        assertEquals("then In combat's bar", listOf(listOf("padtab" to 39, "padtab" to 4)), b.cells)
        b.doc(""","bcb":1,"bcf":1"""); b.cells.clear()
        if (!GameUi.partyModeOn()) return
        b.bump(rb = true)
        assertEquals("then Party mode's controller", listOf(CastStripModel.page2(4)), b.cells)
        b.doc(""","bpm":1"""); b.cells.clear()
        b.bump(rb = true)
        assertEquals("then Party mode's bar", listOf(CastStripModel.page2(5)), b.cells)
        b.doc(""","bcb":1,"bcp":1"""); b.cells.clear(); b.taps.clear()
        b.bump(rb = true)
        assertTrue("the last: no wrap", b.cells.isEmpty() && b.taps.isEmpty())
        b.bump(rb = false)
        assertEquals("LB: back to Party mode's controller", listOf(CastStripModel.page2(4)), b.cells)
    }

    @Test fun macrosAndBagsGetTheBarInEverySituation() {
        for (bi in listOf(""","bitem":{"id":3,"n":"Bravo","i":134400,"mac":true}""", ""","bitem":{"id":118,"n":"Minor Healing Potion","i":134829}""")) {
            val b = box(bi)
            b.tap(slot(b, 7))
            assertEquals("D8 $bi", listOf(CastStripModel.tabCells(), CastStripModel.slotCells(7)!!), b.cells)
        }
    }

    @Test fun theBoxsBarTurnsOnAndLocks() {
        HomeSettings.castBarSide = CastStripModel.Side.OFF
        val b = box(""","bcb":1""")
        b.draw()
        val r = SpellsOverlay::class.java.getDeclaredField("turnOnRect").apply { isAccessible = true }.get(b.o) as RectF
        b.tap(r)
        assertEquals(CastStripModel.Side.BOTH, HomeSettings.castBarSide)
        HomeSettings.castBarPartyShow = false
        if (!GameUi.partyModeOn()) return
        val p = box(""","bcb":2,"bcp":1""", id = 331)
        p.tap(slot(p, 3))
        assertTrue("Bar in Party mode off: a finger puts nothing", p.cells.isEmpty())
    }

    // ---------------------------------------------------------------- a finger's tab is the controller's tab (PadTabCtl p57, Book b72)

    /** the user, 2026-11-20 ("when touching a tab in the controller menu and pressing down after it goes back to the last tab that was
     *  open and goes down"): a finger on a situation tab sends its one padtab pick, and the add-on's answer and its Down keep the page
     *  on the touched situation (tools/test_tab_touch.py proves the add-on half: Down = the touched situation's first row) */
    @Test fun aFingerOnASituationThenDownStaysOnIt() {
        val r = rig()
        r.v.onPadTabCtl(ctl("normal", focus = "tabs"))
        r.cells.clear()
        r.tap(tabRect(r.v, 1))                                           // In combat
        assertEquals(listOf(listOf(PadMapModel.ROW_PADTAB to 2)), r.cells)
        assertEquals("combat", r.v.page().currentView())
        r.v.onPadTabCtl(ctl("combat", focus = "tabs", extra = ""","note":"tab""""))       // the add-on's echo
        r.v.onPadTabCtl(ctl("combat", focus = "switches", extra = ""","note":"to-switches""""))   // its Down
        assertEquals("combat", r.v.page().currentView())
    }

    /** the bug's own place: a finger on the controller's CHOOSER tab (Spells / General / Professions / Extra / DS) only changed the app's
     *  picture, the add-on's chooser stayed on its list, and Down went on there (the app then showed that list again, a row down). Now
     *  the tab is told too (padtab 39, 38 then 5 + k) and the add-on's answer and Down stay on the touched list */
    @Test fun aFingerOnAChooserTabTellsTheControllersChooser() {
        val r = rig()
        r.v.onPadTabCtl(ctl("normal", pos = 4, extra = ""","chooser":{"list":"spells","row":3}"""))
        r.draw()
        val page = r.v.page()
        assertEquals("spells", page.chooserTab())
        val gaps = PadTabPage::class.java.getDeclaredField("cGaps").apply { isAccessible = true }.get(page) as UiKit.TabGaps
        val tabs = gaps.rects
        assertEquals(ChooserTabs.ALL_TABS.size, tabs.size)
        r.cells.clear()
        r.tap(tabs[3])                                                   // Extra
        assertEquals(listOf(CastStripModel.page2(9)), r.cells)
        assertEquals(ChooserTabs.touchCells("extra"), r.cells.single())
        assertEquals("extra", page.chooserTab())
        // the add-on's answer (its list, row 1, the cursor on the tab), then its Down: Extra's first row, Extra still shown
        r.v.onPadTabCtl(ctl("normal", pos = 4, extra = ""","chooser":{"list":"extra","row":1,"tabs":true},"note":"list""""))
        r.v.onPadTabCtl(ctl("normal", pos = 4, extra = ""","chooser":{"list":"extra","row":1},"note":"to-rows""""))
        assertEquals("extra", page.chooserTab())
        // each tab its own code (6-10)
        for ((k, key) in ChooserTabs.ALL_TABS.withIndex()) assertEquals(CastStripModel.page2(6 + k), ChooserTabs.touchCells(key))
        assertEquals(null, ChooserTabs.touchCells("pbuff"))
    }

    /** the Spellbook room's tabs: a finger's tab is told to the add-on's book cursor (padtab 39, 38 then 10 + line; 35 Professions, 36
     *  the pet's book), so the controller's Down goes on in THAT tab; nothing while the room does not hold the controller */
    @Test fun aFingerOnASpellbookTabTellsTheBooksCursor() {
        val cells = ArrayList<List<Pair<String, Int>>>()
        val v = SpellbookView(app, onBind = { }, onPadTab = { c -> cells += c; true })
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val b = book()
        v.update(b)
        val tabs = SpellsModel.tabs(b)
        assertTrue("the book has two tabs or more", tabs.size >= 2)
        fun tabAt(i: Int) = RectF(36f + i * 124f, 66f, 36f + i * 124f + 116f, 166f)
        fun line(key: String) = if (key == "pet") SpellsModel.PET_LINE else key.removePrefix("line:").toInt()
        // not held: the finger's tab only
        touch(v, tabAt(1).centerX(), tabAt(1).centerY())
        assertTrue("not held: nothing sent", cells.isEmpty())
        // held, on the book
        v.onCtl(SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b72","ctl":{"on":true,"mode":"book","line":${line(tabs[0].key)},
            "page":1,"idx":3}}"""))!!)
        touch(v, tabAt(1).centerX(), tabAt(1).centerY())
        assertEquals(listOf(CastStripModel.bookTabCells(line(tabs[1].key))), cells)
        // the Bind box up: nothing
        cells.clear()
        v.onCtl(SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b72","ctl":{"on":true,"mode":"bind","bind":133,
            "line":${line(tabs[0].key)},"page":1,"idx":1}}"""))!!)
        touch(v, tabAt(1).centerX(), tabAt(1).centerY())
        assertTrue("the Bind box up: nothing sent", cells.isEmpty())
        // the codes: lines 1-24, Professions, the pet's book
        assertEquals(CastStripModel.page2(11), CastStripModel.bookTabCells(1))
        assertEquals(CastStripModel.page2(34), CastStripModel.bookTabCells(24))
        assertEquals(CastStripModel.page2(35), CastStripModel.bookTabCells(100))
        assertEquals(CastStripModel.page2(36), CastStripModel.bookTabCells(101))
        assertEquals(null, CastStripModel.bookTabCells(25))
    }
}
