package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
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
import java.io.File
import java.time.Duration

/**
 * CONTROLLER | TAP TO CAST (the user, 2026-11-19, the approved proposal D1-D10; PadTabCtl p55, ActionBar cb14, Book b70): the app half.
 *  - the Controller room's top row is WoW Defaults / In combat / Spell radial / Party mode (D1: no Cast bar top tab); WoW Defaults and
 *    In combat carry a Controller | Tap to cast sub-tab row; a finger on a sub-tab is one pick (padtab 39 then 29 / 1, or the tab's
 *    own column); a top tab opens its remembered sub-tab; B leads Home only from the top tabs (the add-on steps back one level);
 *  - In combat's "Show tap-to-cast" check box IS HomeSettings.castBarCombat (D4): a finger flips it, the controller's A (the add-on's
 *    `cbsn` count) flips it, Settings' row flips it, and every change is told to the add-on (padtab 39 then 2 / 3) - both ways;
 *  - Show off: In combat > Tap to cast is locked (no Change / Move by touch, "Hidden in fights"); the bar Off: the Tap to cast pages say
 *    "Bar is off" with "Turn on" (a finger, or the add-on's `cbon` for A) which sets the bar to Both (D2);
 *  - the Bind box: "Out of combat" | "In combat" (D7), then Controller | Tap to cast; LB / RB walk all four (D9); its taps (bf-off /
 *    bf-on, 39 then 26 / 4); the Spellbook's, the Macros room's and the Bags room's boxes all have Tap to cast (D8); Turn on there too.
 * With CTLTABS_SHOTS_DIR set the room and the box are drawn as the proposal's after_* pictures (1240 x 960; CTL_DOCS = the add-on's own
 * padmap documents from tools/test_party.py CTL_DUMP, else a small stand-in; CLOCK_ART_ROOT = an art root laid out like the app's).
 * tools/test_controller_tap_tabs.py proves the add-on half.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControllerTapTabsTest {
    private val out: File? = System.getenv("CTLTABS_SHOTS_DIR")?.let { File(it).also { d -> d.mkdirs() } }
    private val docs: File? = System.getenv("CTL_DOCS")?.let { File(it) }?.takeIf { it.isDirectory }
    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 1240
    private val h = 960

    private fun artRoot() {
        val root = System.getenv("CLOCK_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private var sideWas = CastStripModel.Side.OFF
    private var combatWas = CastStripModel.Combat.HIDE
    @Before fun up() {
        artRoot(); HomeSettings.init(app); sideWas = HomeSettings.castBarSide; combatWas = HomeSettings.castBarCombat
        HomeSettings.castBarSide = CastStripModel.Side.BOTH; HomeSettings.castBarCombat = CastStripModel.Combat.STAY
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))                  // past every 1.5 s echo (the clock starts near 0)
    }
    @After fun down() { HomeSettings.castBarSide = sideWas; HomeSettings.castBarCombat = combatWas }

    /** a level-6 Shaman's bar with every kind, and (cb14) its in-combat layer: fight slots 1 and 2 set */
    private val barDoc = JSONObject("""{"type":"castbar","v":"cb14","n":8,"any":1,"mac":1,"fl":1,"show":1,"pick":8,"pp":[39,19],"e":[
        {"s":1,"id":403,"i":136048,"c":0},{"s":2,"id":331,"i":136052,"c":1},
        {"s":3,"k":"command","key":"TARGETNEARESTENEMY","n":"Target Nearest Enemy","i":132212,"c":2},
        {"s":4,"k":"command","key":"DSMAP","n":"Open Map","i":134269,"c":3},
        {"s":5,"k":"item","id":118,"n":"Minor Healing Potion","i":134829,"ct":7,"cons":1,"c":4},
        {"s":6,"k":"item","id":6948,"n":"Hearthstone","i":134414,"ct":1,"c":5},
        {"s":7,"k":"mount","id":6,"n":"Brown Horse","i":132261,"sid":458,"c":6}],
        "f":[{"s":1,"id":8042,"i":136026,"c":9},{"s":2,"id":324,"i":136051,"c":10}]}""")

    private fun stand(): PadMapModel.Map = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m159","m":{"page":1,"pages":3,
        "sets":[{"set":1,"slots":[{"pos":4,"s":4,"kind":"none"}]}],
        "commands":[{"key":"TARGETNEARESTENEMY","n":"Target Nearest Enemy","i":132212,"made":true,"b":"/targetenemy"},
                {"key":"DSMAP","n":"Open Map","i":134269,"made":true,"b":"/click WowCompanionPadMap"}],
        "binds":[{"key":"JUMP","n":"Jump","i":135992}],
        "mounts":[{"id":6,"n":"Brown Horse","i":132261}],
        "items":[{"id":118,"n":"Minor Healing Potion","i":134829},{"id":6948,"n":"Hearthstone","i":134414}]}}"""))!!
    private fun map(name: String): PadMapModel.Map =
        docs?.let { File(it, "$name.json") }?.takeIf { it.canRead() }?.let { PadMapModel.parse(JSONObject(it.readText())) } ?: stand()

    private fun book() = SpellsModel.parse(JSONObject("""{"type":"spells","v":"s9","s":{"lines":[{"k":1,"n":"General"},{"k":2,"n":"Elemental"}],
        "items":[{"n":"Lightning Bolt","t":1,"id":403,"a":403,"sl":1,"ln":2,"i":136048},{"n":"Healing Wave","t":1,"id":331,"a":331,"sl":2,"ln":2,"i":136052},
                 {"n":"Earth Shock","t":1,"id":8042,"a":8042,"sl":3,"ln":2,"i":136026},{"n":"Rockbiter Weapon","t":1,"id":8017,"a":8017,"sl":4,"ln":2,"i":136086}],
        "win":{"croom":5,"open":0,"close":1,"bfon":12,"bfoff":13}}}"""))!!

    private fun ctl(view: String, focus: String = "buttons", pos: Int = 1, extra: String = ""): PadMapModel.Ctl {
        val top = PadMapModel.topOf(view)
        val tcur = PadMapModel.TABS.indexOf(top) + 1
        return PadMapModel.parseCtl(JSONObject("""{"type":"padtabctl","v":"p55","c":{"on":true,"focus":"$focus","set":1,"shown":1,
            "pos":$pos,"page":1,"view":"$view","top":"$top","lay":"swap","tcur":$tcur,"sw":1$extra}}"""))!!
    }

    private class Rig(val v: ControllerView) { val cells = ArrayList<List<Pair<String, Int>>>() }

    private fun rig(mapName: String = "padmap_on"): Rig {
        CastStripModel.Live.set(CastStripModel.parse(barDoc)!!)
        lateinit var r: Rig
        val v = ControllerView(app, { c -> r.cells += c; true })
        r = Rig(v)
        v.update(book())
        v.onPadMap(map(mapName))
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        return r
    }

    private fun Rig.draw(name: String? = null) {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        if (name != null && out != null) File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun touch(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        v.onTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0))
    }
    private fun Rig.tap(x: Float, y: Float) { draw(); touch(v, x, y); draw() }

    // the room's places (ControllerView: the sub-tabs 250 x 50, 50 apart, centred on the left page; WoW Defaults' at 174, In combat's
    // at 280; three In combat check boxes across the left page at 196-268; the right page's lower button)
    private fun sub(i: Int, top: String) = (370f - 275f + i * 300f + 125f) to (if (top == "combat") 305f else 199f)
    private val showBox = (40f + 2 * (628f / 3f + 16f) + 104f) to 232f
    private val act = 990f to h - 85f
    private fun idle() = ShadowLooper.idleMainLooper()

    @Test fun theTopRowHasNoCastBarTab() {
        val r = rig()
        assertEquals("D1: four top tabs", listOf("normal", "combat", "radial", "party").filter { it != "party" || GameUi.partyModeOn() }, r.v.tabViews())
        r.v.onPadTabCtl(ctl("castbar")); r.draw()
        assertEquals("castbar", r.v.page().currentView())
    }

    @Test fun aFingerOnASubTabIsOnePick() {
        val r = rig()
        r.v.onPadTabCtl(ctl("normal", focus = "subtabs")); r.cells.clear()
        sub(1, "normal").let { (x, y) -> r.tap(x, y) }
        assertEquals("WoW Defaults > Tap to cast: padtab 39 then 29", listOf(CastStripModel.ctlTabCells()), r.cells)
        assertEquals("shown at the tap", "castbar", r.v.page().currentView())
        r.cells.clear()
        sub(0, "normal").let { (x, y) -> r.tap(x, y) }
        assertEquals("Controller: WoW Defaults' own column", listOf(listOf(PadMapModel.ROW_PADTAB to PadMapModel.colTab(1))), r.cells)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(3))                  // past the finger's echo guard: the add-on's view wins
        r.v.onPadTabCtl(ctl("combat", focus = "subtabs")); r.cells.clear()
        sub(1, "combat").let { (x, y) -> r.tap(x, y) }
        assertEquals("In combat > Tap to cast: padtab 39 then 1", listOf(CastStripModel.ctlFightCells()), r.cells)
        assertEquals("cbfight", r.v.page().currentView())
    }

    @Test fun aTopTabOpensItsRememberedSubTab() {
        val r = rig()
        r.v.onPadTabCtl(ctl("castbar", focus = "subtabs"))                  // the add-on: WoW Defaults > Tap to cast
        r.v.onPadTabCtl(ctl("combat", focus = "tabs")); r.cells.clear()
        r.tap(220f, 120f)                                                   // WoW Defaults' top tab
        assertEquals("remembered: Tap to cast", listOf(CastStripModel.ctlTabCells()), r.cells)
        assertTrue(r.v.enterRoom())                                         // a fresh open forgets it
        r.v.onPadTabCtl(ctl("combat", focus = "tabs")); r.cells.clear()
        r.tap(220f, 120f)
        assertEquals(listOf(listOf(PadMapModel.ROW_PADTAB to PadMapModel.colTab(1))), r.cells)
    }

    @Test fun bLeadsHomeOnlyFromTheTopTabs() {
        val r = rig()
        r.v.onCtl(SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b70","ctl":{"on":true,"croom":true,"mode":"book"}}"""))!!)
        r.v.onPadTabCtl(ctl("normal", focus = "tabs"))
        assertTrue(r.v.bLeadsHome())
        for (f in listOf("subtabs", "buttons", "switches", "sets")) {
            r.v.onPadTabCtl(ctl("normal", focus = f))
            assertFalse("B on $f is the room's (one level back)", r.v.bLeadsHome())
        }
    }

    @Test fun showInCombatIsOneSettingBothWays() {
        val r = rig("padmap_combat")
        r.v.onPadTabCtl(ctl("combat", focus = "switches", extra = ""","cbsn":0"""))
        idle(); r.cells.clear()
        // a finger on the check box: castBarCombat flips, the add-on told 39 then 3
        r.tap(showBox.first, showBox.second); idle()
        assertEquals(CastStripModel.Combat.HIDE, HomeSettings.castBarCombat)
        assertTrue("told: ${r.cells}", r.cells.contains(CastStripModel.showCells(false)))
        // Settings' row (the mirror): the add-on told again, the box follows
        r.cells.clear()
        HomeSettings.castBarCombat = CastStripModel.Combat.STAY; idle()
        assertTrue(r.cells.contains(CastStripModel.showCells(true)))
        // the controller's A (the add-on's count): flipped once per count
        r.v.onPadTabCtl(ctl("combat", focus = "switches", extra = ""","cbsn":1"""))
        assertEquals(CastStripModel.Combat.HIDE, HomeSettings.castBarCombat)
        r.v.onPadTabCtl(ctl("combat", focus = "switches", extra = ""","cbsn":1"""))
        assertEquals("the same count again: no flip", CastStripModel.Combat.HIDE, HomeSettings.castBarCombat)
        // the room's open tells it with the bar's setting
        r.cells.clear()
        assertTrue(r.v.enterRoom())
        assertEquals(listOf("padtab" to 39, "padtab" to 30, "padtab" to 39, "padtab" to 3, "spells" to 5), r.cells.first { it.contains("spells" to 5) })
    }

    @Test fun showOffLocksTheFightLayer() {
        val r = rig()
        HomeSettings.castBarCombat = CastStripModel.Combat.HIDE
        r.v.onPadTabCtl(ctl("cbfight", pos = 3)); r.cells.clear()
        r.tap(act.first, act.second)
        assertFalse("no chooser", r.v.page().chooserOpen())
        assertTrue("nothing sent: ${r.cells}", r.cells.isEmpty())
        HomeSettings.castBarCombat = CastStripModel.Combat.STAY; idle()
        r.v.onPadTabCtl(ctl("cbfight", pos = 3)); r.cells.clear()
        r.tap(act.first, act.second)
        assertTrue("Show on: Choose opens the chooser", r.v.page().chooserOpen())
        // out of combat > Tap to cast is never locked by Show
        HomeSettings.castBarCombat = CastStripModel.Combat.HIDE
        val r2 = rig()
        r2.v.onPadTabCtl(ctl("castbar", pos = 8))
        r2.tap(act.first, act.second)
        assertTrue(r2.v.page().chooserOpen())
    }

    @Test fun barOffShowsTurnOn() {
        val r = rig()
        HomeSettings.castBarSide = CastStripModel.Side.OFF
        r.v.onPadTabCtl(ctl("castbar", pos = 1, extra = ""","cbon":0""")); r.cells.clear()
        r.tap(act.first, act.second)
        assertEquals("D2: Turn on = Both", CastStripModel.Side.BOTH, HomeSettings.castBarSide)
        HomeSettings.castBarSide = CastStripModel.Side.OFF
        r.v.onPadTabCtl(ctl("castbar", pos = 1, extra = ""","cbon":1"""))
        assertEquals("the controller's A (cbon counted)", CastStripModel.Side.BOTH, HomeSettings.castBarSide)
    }

    // ---------------------------------------------------------------- the Bind box (SpellsOverlay)

    private class Box(val o: SpellsOverlay) { val taps = ArrayList<Int>(); val cells = ArrayList<List<Pair<String, Int>>>() }

    private fun box(bitem: String = ""): Box {
        CastStripModel.Live.set(CastStripModel.parse(barDoc)!!)
        lateinit var b: Box
        val o = SpellsOverlay(app, otherReader = { true }, onTap = { b.taps += it }, onCastBarTaps = { b.cells += it })
        b = Box(o)
        o.onBook(book())
        o.onCtl(SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b70","ctl":{"on":true,"mode":"bind","bind":8017$bitem}}"""))!!)
        o.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080 - Strip.HEIGHT, View.MeasureSpec.EXACTLY))
        o.layout(0, 0, w, 1080 - Strip.HEIGHT)
        return b
    }
    private fun Box.draw(name: String? = null) {
        val bm = Bitmap.createBitmap(w, 1080 - Strip.HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm); c.drawColor(Theme.ink); o.draw(c)
        if (name != null && out != null) File(out, "$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun Box.tap(x: Float, y: Float) { draw(); touch(o, x, y); draw() }
    private fun Box.doc(extra: String) = o.onCtl(SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b70","ctl":{"on":true,"mode":"bind","bind":8017$extra}}"""))!!)
    private fun Box.bump(rb: Boolean) {
        val m = SpellsOverlay::class.java.getDeclaredMethod("bumpers", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        m.isAccessible = true
        m.invoke(o, !rb, rb); m.invoke(o, false, false)
    }
    // the box's rows: Out of combat / In combat (290..614 / 626..950, 108..184), Controller / Tap to cast (360..590 / 650..880, 190..238)
    private val outTab = 452f to 146f
    private val inTab = 788f to 146f
    private val ctlTab = 475f to 214f
    private val tapTab = 765f to 214f

    @Test fun theBindBoxHasFourTabs() {
        val b = box()
        b.tap(tapTab.first, tapTab.second)
        assertEquals("Out of combat + Tap to cast: 39 then 26", listOf(CastStripModel.tabCells()), b.cells)
        b.doc(""","bcb":1""")
        b.cells.clear()
        b.tap(inTab.first, inTab.second)
        assertEquals("In combat keeps Tap to cast: 39 then 4", listOf(listOf("padtab" to 39, "padtab" to 4)), b.cells)
        b.doc(""","bcb":1,"bcf":1""")
        b.tap(ctlTab.first, ctlTab.second)
        assertEquals("In combat + Controller: bf-on", listOf(12), b.taps)
        b.doc(""","bf":true""")
        b.taps.clear()
        b.tap(outTab.first, outTab.second)
        assertEquals("Out of combat + Controller: bf-off", listOf(13), b.taps)
    }

    @Test fun lbRbWalkAllFourInReadingOrder() {
        val b = box()
        b.draw()
        b.bump(rb = true)
        assertEquals(listOf(CastStripModel.tabCells()), b.cells)
        b.doc(""","bcb":1"""); b.cells.clear()
        b.bump(rb = true)
        assertEquals("then In combat + Controller", listOf(12), b.taps)
        b.doc(""","bf":true"""); b.taps.clear()
        b.bump(rb = true)
        assertEquals("then In combat + Tap to cast", listOf(listOf("padtab" to 39, "padtab" to 4)), b.cells)
        b.doc(""","bcb":1,"bcf":1"""); b.cells.clear(); b.taps.clear()
        b.bump(rb = true)
        assertTrue("the last: no wrap", b.cells.isEmpty() && b.taps.isEmpty())
        b.bump(rb = false)
        assertEquals("LB: back to In combat + Controller", listOf(12), b.taps)
    }

    @Test fun macrosAndBagsGetTapToCast() {
        for (bi in listOf(""","bitem":{"id":3,"n":"Bravo","i":134400,"mac":true}""", ""","bitem":{"id":118,"n":"Minor Healing Potion","i":134829}""")) {
            val b = box(bi)
            b.tap(tapTab.first, tapTab.second)
            assertEquals("D8 $bi", listOf(CastStripModel.tabCells()), b.cells)
            b.doc("$bi,\"bcb\":2")
            b.cells.clear()
            b.tap(inTab.first, inTab.second)
            assertEquals(listOf(listOf("padtab" to 39, "padtab" to 4)), b.cells)
        }
    }

    @Test fun theBoxSaysOutOfCombatAndTurnsTheBarOn() {
        val src = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion/SpellsOverlay.kt"),
            File("companion/app/src/main/java/com/jrees/wowcompanion/SpellsOverlay.kt")).first { it.canRead() }.readText()
        assertTrue("D7", src.contains("UiKit.tab(c, fightRect(0, w), \"Out of combat\", !f)"))
        assertFalse(src.contains("\"Normal\""))
        HomeSettings.castBarSide = CastStripModel.Side.OFF
        val b = box(""","bcb":1""")
        b.draw()
        b.tap(620f, 793f)                                                   // Turn on (under "Bar is off")
        assertEquals(CastStripModel.Side.BOTH, HomeSettings.castBarSide)
        HomeSettings.castBarSide = CastStripModel.Side.OFF
        b.doc(""","bcb":1,"cbon":0"""); b.doc(""","bcb":1,"cbon":1""")
        assertEquals("A (the add-on's cbon)", CastStripModel.Side.BOTH, HomeSettings.castBarSide)
    }

    // ---------------------------------------------------------------- the proposal's after pictures (CTLTABS_SHOTS_DIR)

    @Test fun shots() {
        if (out == null) return
        val dl = docs?.let { File(it, "ctl_dleft.json") }?.takeIf { it.canRead() }?.let { JSONObject(it.readText()).getJSONObject("c") }
        fun from(view: String, focus: String, pos: Int, extra: (JSONObject) -> Unit = {}): PadMapModel.Ctl {
            val c = JSONObject((dl ?: JSONObject()).toString())
            val top = PadMapModel.topOf(view)
            c.put("on", true).put("view", view).put("top", top).put("focus", focus).put("pos", pos).put("tcur", PadMapModel.TABS.indexOf(top) + 1)
                .put("set", 1).put("shown", 1).put("lay", "swap").remove("note")
            extra(c)
            return PadMapModel.parseCtl(JSONObject().put("type", "padtabctl").put("v", "p55").put("c", c))!!
        }
        var r = rig("padmap_on")
        r.v.onPadTabCtl(from("normal", "subtabs", 1)); r.draw(); r.draw("after_01_wow_defaults_controller")
        r.v.onPadTabCtl(from("castbar", "buttons", 3)); r.draw(); r.draw("after_02_wow_defaults_tap_to_cast")
        HomeSettings.castBarSide = CastStripModel.Side.OFF
        r.v.onPadTabCtl(from("castbar", "subtabs", 3)); r.draw(); r.draw("after_02b_wow_defaults_tap_to_cast_bar_off")
        HomeSettings.castBarSide = CastStripModel.Side.BOTH
        r = rig("padmap_combat")
        r.v.onPadTabCtl(from("combat", "subtabs", 1)); r.draw(); r.draw("after_03_in_combat_controller")
        r.v.onPadTabCtl(from("cbfight", "buttons", 3)); r.draw(); r.draw("after_04_in_combat_tap_to_cast_on")
        HomeSettings.castBarCombat = CastStripModel.Combat.HIDE
        CastStripModel.Live.set(CastStripModel.parse(JSONObject(barDoc.toString()).apply { remove("show") })!!)
        r.v.onPadTabCtl(from("cbfight", "subtabs", 3)); r.draw(); r.draw("after_05_in_combat_tap_to_cast_off")
        HomeSettings.castBarCombat = CastStripModel.Combat.STAY
        r = rig("padmap_on")
        r.v.onPadTabCtl(from("party", "tabs", 8)); r.draw(); r.draw("after_10_party_mode_unchanged")
        // the Bind box: Rockbiter Weapon's
        var b = box(); b.draw(); b.draw("after_06_bind_box_controller")
        b.doc(""","bcb":7"""); b.draw(); b.draw("after_07_bind_box_tap_to_cast")
        b.doc(""","bcb":3,"bcf":1"""); b.draw(); b.draw("after_07b_bind_box_combat_tap_on")
        HomeSettings.castBarCombat = CastStripModel.Combat.HIDE
        b = box(""","bcb":3,"bcf":1"""); b.draw(); b.draw("after_08_bind_box_combat_tap_off")
        HomeSettings.castBarCombat = CastStripModel.Combat.STAY
        b = box(""","bitem":{"id":118,"n":"Minor Healing Potion","i":134829},"bcb":5"""); b.draw(); b.draw("after_08b_bind_box_bags_tap_to_cast")
    }
}
