package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * TOP SCREEN AS PICTURE TILES, off-device (the user, 2026-11-16: "VISUAL overhaul of UI menus (picture beside each game-UI element
 * option)"; the design's recommended picks, accepted the same day): the room built as the host builds it, the Top screen tab's five
 * plain headings over grids of tiles, a band under a tile's row, the controller's walk (A opens the band and the cursor stays on the
 * tile, Down goes into the choices, B closes the band with the cursor back on the tile, then the tab row, then Home), touch the same,
 * no reflow while the add-on's switches arrive.
 *
 * With UIMENUS_SHOTS_DIR set it also renders the tab (no game yet, every element reported, bands open, Spanish and French, no kit) and
 * a sheet of the 19 pictures; THEME_ART_ROOT = an art root laid out like the app's (ui/kit, ui/cast, fonts).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameUiTilesRoomTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var n = 0
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    private fun art(root: String? = System.getenv("THEME_ART_ROOT")) {
        val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, root?.let { File(it) })
        UiKit.root = UiKit.root                                         // the kit's caches forget (a font, a texture missing before)
        for (n in listOf("castCache", "castMissing")) when (val o = UiKit::class.java.getDeclaredField(n).apply { isAccessible = true }.get(UiKit)) {
            is MutableMap<*, *> -> o.clear(); is MutableSet<*> -> o.clear()
        }
    }

    private fun room(kit: Boolean = true): SettingsRoom {
        Settings.init(app); SettingsTabs.init(app); GameUi.init(app)
        if (kit) art() else art(null)
        val v = SettingsView(app) { }
        v.loginRow = true
        return SettingsRoom(app, v) { null }.also { it.show(SettingsTabs.Tab.TOP); lay(it) }
    }

    private fun lay(r: View) {
        for (k in 0..2) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            r.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
            r.layout(0, 0, W, H)
            r.draw(Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)))
        }
    }

    private fun press(r: SettingsRoom, k: String) { r.pad.onPad(JSONObject().put("k", k).put("n", ++n)); lay(r) }

    /** the room entered and holding the controller (the hold's answer), the cursor on the tab row (enter() puts it there at once: a
     *  detached view's post never runs here) */
    private fun hold(r: SettingsRoom) {
        r.pad.enter(); r.show(SettingsTabs.Tab.TOP, force = true)
        r.pad.onGuestAck(JSONObject().put("op", "hold").put("ok", true)); lay(r)
    }

    private fun parts(r: SettingsRoom) = r.gameUis.filter { it.tiled }

    /** every tile's box in the page's content coordinates, by id */
    private fun tileBoxes(r: SettingsRoom): Map<String, RectF> {
        val out = LinkedHashMap<String, RectF>()
        for (p in parts(r)) {
            val top = contentTop(r, p) ?: continue
            val ids = p.tileIds()
            val boxes = p.focusTargets().take(ids.size)
            for ((i, id) in ids.withIndex()) out[id] = RectF(boxes[i]).apply { offset(0f, top) }
        }
        return out
    }

    private fun contentTop(r: SettingsRoom, v: View): Float? {
        val sc = SettingsRoom::class.java.getDeclaredField("scroll").apply { isAccessible = true }.get(r) as android.widget.ScrollView
        val content = sc.getChildAt(0)
        var y = 0f; var cur: View = v
        while (cur !== content) { if (cur.visibility != View.VISIBLE) return null; y += cur.top; cur = cur.parent as? View ?: return null }
        return y
    }

    private fun partOf(r: SettingsRoom, id: String) = parts(r).first { id in it.tileIds() }
    private fun openTile(r: SettingsRoom) = parts(r).mapNotNull { it.openTile }.singleOrNull()

    /** a touch (down, up) at ([x], [y]) of [v] */
    private fun tap(v: View, x: Float, y: Float) {
        val d = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0); v.dispatchTouchEvent(d); d.recycle()
        val u = MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, x, y, 0); v.dispatchTouchEvent(u); u.recycle()
    }

    // ---------------------------------------------------------------- the game's word (a full hub document and the switches)

    private fun game(hudHidden: Boolean = false, chatHidden: Boolean = true, pending: Boolean = false) {
        GameUi.character = "Sample-Realm"
        val cols = JSONObject()
        listOf("PLAYER_SHOW", "PLAYER_HIDE", "TARGET_SHOW", "TARGET_HIDE", "NP_OFF", "NP_ON", "PRD_OFF", "PRD_ON", "WM_OFF", "WM_ON",
            "PARTY_SHOW", "PARTY_HIDE", "UFP_ON", "UFP_OFF", "UFT_ON", "UFT_OFF", "UFTOT_ON", "UFTOT_OFF", "UFHPP_ON", "UFHPP_OFF",
            "UFHPT_ON", "UFHPT_OFF", "PS_HIDE", "PS_SHOW", "PET_ON", "PET_OFF", "NPF_ON", "NPF_OFF").forEachIndexed { i, k -> cols.put(k, i) }
        val sw = JSONObject().put("row", "uisw").put("cols", cols).put("set", JSONObject())
            .put("player", "hidden").put("target", "hidden").put("np", true).put("prd", true).put("wm", false).put("party", "shown")
            .put("ufp", true).put("uft", true).put("uftot", false).put("ufhpp", false).put("ufhpt", false).put("ps", false).put("pet", true)
            .put("npf", false)
        val els = org.json.JSONArray()
        for ((i, id) in listOf("minimap", "hud", "tracker", "chat", "dialogs").withIndex())
            els.put(JSONObject().put("id", id).put("bit", i).put("hidden", if (id == "chat") chatHidden else if (id == "hud") hudHidden else true))
        val hub = JSONObject().put("boot", 1).put("n", 3).put("pending", pending).put("row", "ui").put("els", els)
            .put("cb", JSONObject().put("hidden", true)).put("sw", sw)
            .put("prd", JSONObject().put("y", -350).put("w", 280).put("s", 100))
        GameUi.onHub(JSONObject().put("hub", hub), true)
        GameUi.onVisible(true)                                          // the companion on the bottom screen (its choices hold)
        HomeLootSwitch.note(JSONObject().put("proof", JSONObject().put("lootc", JSONObject().put("top", false).put("on", 1).put("off", 2))))
        CompassSwitch.note(JSONObject().put("proof", JSONObject().put("compc", JSONObject().put("show", true).put("on", 1).put("off", 2)
            .put("cam", false).put("camOn", 3).put("camOff", 4))))
        TipsSwitch.note(JSONObject().put("proof", JSONObject().put("tipc", JSONObject().put("hide", false).put("on", 1).put("off", 2))))
        RingsSwitch.note(JSONObject().put("proof", JSONObject().put("ringc", JSONObject().put("show", true).put("on", 1).put("off", 2))))
    }

    /** the singletons back to "no game" (Robolectric keeps statics between tests: the other rooms' tests expect none) */
    @After fun forget() {
        for (o in listOf(HomeLootSwitch, CompassSwitch, TipsSwitch, RingsSwitch))
            o::class.java.getDeclaredField("state").apply { isAccessible = true }.set(o, null)
        for (f in listOf("sw", "state", "castBarHidden", "prdY", "prdW", "prdS", "prefs"))
            GameUi::class.java.getDeclaredField(f).apply { isAccessible = true }.set(GameUi, null)
        GameUi.character = null
        Tr.setChoice(app, "auto")
    }

    // ---------------------------------------------------------------- the tab

    /** the Top screen tab: five plain headings (no folding, no heading stops), every group open, each drawn by ONE tile part */
    @Test fun topTabIsFiveOpenGroupsOfTiles() {
        val r = room()
        val groups = SettingsTabs.groups(SettingsTabs.Tab.TOP)
        assertEquals(5, groups.size)
        assertEquals(groups.toSet(), r.open)
        for (gb in r.groupBoxes.filter { it.group in groups }) {
            assertEquals("${gb.group}", View.VISIBLE, gb.box.visibility)
            assertEquals("${gb.group} one part", 1, gb.parts.size)
            val p = gb.parts.single() as GameUiSection
            assertTrue(p.tiled)
            assertEquals(GameUiTiles.slots(gb.group, false), p.tileIds())
        }
        // no stop on a heading (General's rule): the first stop under the tab row is the first tile
        val heads = r.groupBoxes.filter { it.group in groups }.map { gb -> gb.heading.rect().let { RectF(it.left, it.top + gb.box.top, it.right, it.bottom + gb.box.top) } }
        assertTrue(r.pad.targetBoxes().none { t -> heads.any { it == t } })
        // a tap on a heading folds nothing
        r.toggle(SettingsTabs.Group.TARGET)
        assertEquals(groups.toSet(), r.open)
        // 18 tiles with Polestar not in use (19 with it); before the game: every one a "..." tile but the elements' (their choice known)
        assertEquals(18, tileBoxes(r).size)
        // Controller > Combat helpers keep their rows
        assertTrue(r.gameUis.filter { it.part == GameUiSection.Part.COMBAT || it.part == GameUiSection.Part.AUTO_SHOT }.none { it.tiled })
    }

    /** the grid: five columns of 232 x 184 tiles on the room's 1240 px page, 12 px apart */
    @Test fun gridIsFiveColumnsOfTiles() {
        val r = room()
        val w = tileBoxes(r)
        val win = GameUiTiles.slots(SettingsTabs.Group.WINDOWS, false).map { w.getValue(it) }
        assertEquals(10, win.size)
        for (b in win) { assertEquals(GameUiTiles.TILE_W, b.width(), 0.5f); assertEquals(GameUiTiles.TILE_H, b.height(), 0.01f) }
        assertEquals(16f, win[0].left, 0.5f)
        assertEquals(W - 16f, win[4].right, 0.5f)
        assertEquals(win[0].top, win[4].top, 0.01f)
        assertEquals(win[0].bottom + GameUiTiles.GAP, win[5].top, 0.01f)
        assertEquals(win[0].left, win[5].left, 0.01f)
    }

    /** NO REFLOW: a tile keeps its place while the add-on's switches come (a "..." tile until then); only its badge and word change */
    @Test fun tilesKeepTheirPlacesWhileTheGameReports() {
        val r = room()
        val before = tileBoxes(r)
        val p = partOf(r, GameUi.UFT_ID)
        assertNull(GameUiSection::class.java.getDeclaredMethod("rowOf", String::class.java).apply { isAccessible = true }.invoke(p, GameUi.UFT_ID))
        game()
        lay(r)
        assertEquals(before, tileBoxes(r))
        // and the same while the switches go again (a /reload: the add-on's word not said yet)
        GameUi::class.java.getDeclaredField("sw").apply { isAccessible = true }.set(GameUi, null)
        GameUi.onRoom(Room.QUESTS); lay(r)
        assertEquals(before, tileBoxes(r))
    }

    /** the badges follow the game's word: "..." before it, the mode after, the hourglass while the game has not caught up */
    @Test fun badgesFollowTheGame() {
        assertEquals(GameUiTiles.Badge.WAITING, GameUiTiles.badge(GameUi.ufTargetRow()))
        GameUi.init(app); game()
        assertEquals(GameUiTiles.Badge.ON, GameUiTiles.badge(GameUi.ufTargetRow()))
        assertEquals(GameUiTiles.Badge.OFF, GameUiTiles.badge(GameUi.friendlyPlatesRow()))
        // a choice the game has not taken yet: the picture follows the choice (On), the badge waits
        GameUi.setSwitch(GameUi.SW_NPF, true)
        val npf = GameUi.friendlyPlatesRow()!!
        assertEquals(UiMode.ON, npf.mode)
        assertEquals(GameUiTiles.Badge.SENT, GameUiTiles.badge(npf))
        assertTrue(!GameUiTiles.dimmed(npf))
    }

    // ---------------------------------------------------------------- the controller

    /** Down walks the tiles in reading order (the HUD's one, Target's three, ...), Right along a row */
    @Test fun theCursorWalksTheGrid() {
        val r = room(); hold(r)
        assertTrue(r.pad.onTabRow)
        press(r, "PADDDOWN")
        val boxes = tileBoxes(r)
        assertEquals(boxes.getValue(GameUi.HUD_ID), r.pad.focusBox())
        press(r, "PADDDOWN")
        assertEquals(boxes.getValue(GameUi.UFT_ID), r.pad.focusBox())
        press(r, "PADDRIGHT")
        assertEquals(boxes.getValue(GameUi.NP_ID), r.pad.focusBox())
        press(r, "PADDRIGHT")
        assertEquals(boxes.getValue(GameUi.NPF_ID), r.pad.focusBox())
        press(r, "PADDRIGHT")                                            // the row's end: it stays
        assertEquals(boxes.getValue(GameUi.NPF_ID), r.pad.focusBox())
        press(r, "PADDDOWN")                                             // Party's row, the nearest in x
        assertEquals(boxes.getValue(GameUi.PARTY_ID), r.pad.focusBox())
        // every tile is a stop
        val stops = r.pad.targetBoxes()
        for ((id, b) in boxes) assertTrue("$id is a stop", stops.any { it == b })
    }

    /** A opens the tile's band and the cursor STAYS on the tile; Down goes into its choices; A there applies one choice; B closes the
     *  band with the cursor back on the tile; B again: the tab row; B again: Home */
    @Test fun aOpensTheBandAndBStepsBack() {
        val r = room(); game(); lay(r); hold(r)
        var left = false
        r.pad.onLeave = { left = true }
        press(r, "PADDDOWN")                                             // the Resource bar
        val tile = r.pad.focusBox()!!
        press(r, "PAD1")
        assertEquals(GameUi.HUD_ID, openTile(r))
        assertEquals(tile, r.pad.focusBox())
        // Down: the band's first line, its On / Off
        press(r, "PADDDOWN")
        val on = r.pad.focusBox()!!
        assertTrue("into the band", on.top > tile.bottom)
        press(r, "PADDRIGHT")                                            // Off
        press(r, "PAD1")
        assertEquals(false, GameUi.swWant(GameUi.SW_PRD))
        assertEquals(GameUi.HUD_ID, openTile(r))                         // the band stays open
        press(r, "PADDLEFT"); press(r, "PAD1")                           // On again
        assertEquals(true, GameUi.swWant(GameUi.SW_PRD))
        // B: the band closes, the cursor back on the Resource bar
        press(r, "PAD2")
        assertNull(openTile(r))
        assertEquals(tile, r.pad.focusBox())
        assertTrue(!r.pad.onTabRow)
        press(r, "PAD2")
        assertTrue(r.pad.onTabRow)
        assertTrue(!left)
        press(r, "PAD2")
        assertTrue("B on the tab row leaves (Home)", left)
    }

    /** A on another tile moves the band there (one band in the room), and the cursor is on that tile, wherever the rows moved */
    @Test fun aOnAnotherTileMovesTheBand() {
        val r = room(); game(); lay(r); hold(r)
        press(r, "PADDDOWN"); press(r, "PADDDOWN")                       // Target bar
        press(r, "PAD1")
        assertEquals(GameUi.UFT_ID, openTile(r))
        // down through the band to the Party row, A there: Target's band closes, Party frames' opens
        fun onParty() = tileBoxes(r).any { (id, b) -> b == r.pad.focusBox() && (id == GameUi.UFP_ID || id == GameUi.PARTY_ID) }
        repeat(6) { if (!onParty()) press(r, "PADDDOWN") }
        val atParty = tileBoxes(r).entries.firstOrNull { it.value == r.pad.focusBox() }?.key
        assertTrue("on a Party tile: $atParty", atParty == GameUi.UFP_ID || atParty == GameUi.PARTY_ID)
        press(r, "PAD1")
        assertEquals(atParty, openTile(r))
        assertTrue(partOf(r, GameUi.UFT_ID).openTile == null)
        assertEquals(tileBoxes(r).getValue(atParty!!), r.pad.focusBox())
        // in one grid: Minimap's band, then Right + A on the Quest tracker (same row): the band moves along
        val m = tileBoxes(r).getValue("minimap")
        tap(partOf(r, "minimap"), m.centerX(), m.centerY() - contentTop(r, partOf(r, "minimap"))!!); lay(r)
        assertEquals("minimap", openTile(r))
        assertNull(partOf(r, GameUi.UFP_ID).openTile)
    }

    /** the band's choices: an element's modes (the minimap's four), a touch applies one; B / L / R close the band */
    @Test fun touchDoesTheSameAndLAndRCloseTheBand() {
        val r = room(); game(); lay(r)
        val p = partOf(r, "minimap")
        val t = tileBoxes(r).getValue("minimap")
        val top = contentTop(r, p)!!
        tap(p, t.centerX(), t.centerY() - top); lay(r)
        assertEquals("minimap", p.openTile)
        val band = p.bandBox()!!
        val buttons = p.focusTargets().drop(p.tileIds().size)
        assertEquals("On, Off, Dynamic, While tracking", 4, buttons.size)
        for (b in buttons) { assertTrue(b.height() >= 110f); assertTrue(band.contains(b.centerX(), b.centerY())) }
        tap(p, buttons[2].centerX(), buttons[2].centerY()); lay(r)
        assertEquals(UiMode.DYNAMIC, GameUi.mode("minimap"))
        // a tap on the open tile closes its band
        tap(p, t.centerX(), t.centerY() - top); lay(r)
        assertNull(p.openTile)
        // L / R: another tab, the band closed
        hold(r)
        tap(p, t.centerX(), t.centerY() - top); lay(r)
        assertEquals("minimap", p.openTile)
        press(r, "PADRSHOULDER")
        assertNull(p.openTile)
        assertEquals(SettingsTabs.Tab.CONTROLLER, r.tab)
    }

    /** the Resource bar's band carries the pet strip and the three steppers while the bar is on; it fits on one screen with its tile */
    @Test fun resourceBarBandCarriesItsRows() {
        val r = room(); game(); lay(r)
        val p = partOf(r, GameUi.HUD_ID)
        val t = tileBoxes(r).getValue(GameUi.HUD_ID)
        tap(p, t.centerX(), t.centerY() - contentTop(r, p)!!); lay(r)
        val buttons = p.focusTargets().drop(1)
        assertEquals("On / Off, Pet bar On / Off, three steppers of three", 2 + 2 + 9, buttons.size)
        val band = p.bandBox()!!
        assertTrue("tile + band ${band.bottom - (t.top - contentTop(r, p)!!)} fit on one screen",
            band.bottom - (t.top - contentTop(r, p)!!) + SettingsPad.REVEAL_TOP + SettingsPad.REVEAL_BOTTOM <= H - SettingsTabBar.BAR_H)
        // the stepper's Higher: one step, kept for this character
        val before = GameUi.hudHeight()!!
        val higher = buttons[4 + 2]
        tap(p, higher.centerX(), higher.centerY()); lay(r)
        assertEquals(before + 10, GameUi.hudHeight())
        // the resource bar Off: its rows go, the band shrinks to its own line
        tap(p, buttons[1].centerX(), buttons[1].centerY()); lay(r)
        assertEquals(2, p.focusTargets().drop(1).size)
        assertTrue(p.bandBox()!!.height() < band.height())
    }

    /** a "..." tile (the game has not said) opens its band, its choices greyed: a press there writes nothing */
    @Test fun aWaitingTilesChoicesAreGreyed() {
        val r = room()
        val p = partOf(r, GameUi.WM_ID)
        val t = tileBoxes(r).getValue(GameUi.WM_ID)
        tap(p, t.centerX(), t.centerY() - contentTop(r, p)!!); lay(r)
        assertEquals(GameUi.WM_ID, p.openTile)
        val b = p.focusTargets().drop(p.tileIds().size)
        assertEquals(2, b.size)
        GameUi.character = "Sample-Realm"
        tap(p, b[0].centerX(), b[0].centerY()); lay(r)
        assertEquals("nothing written", false, GameUi.swWant(GameUi.SW_WM))
    }

    /** our own add-on textures read on the phone (the TGA reader): the HUD rim, the compass band, the markers */
    @Test fun ourMediaTexturesDecode() {
        MediaArt.clear()
        for (name in listOf("hud_rim_8", "compass_band", "marker_ring", "marker_beam", "marker_diamond", "marker_pin", "quest_disc", "target_arrow", "tint_glow")) {
            val b = MediaArt.get(app, name)
            assertNotNull(name, b)
        }
        val rim = MediaArt.get(app, "hud_rim_8")!!
        assertEquals(128, rim.width); assertEquals(256, rim.height)
        // the rim's top-left texel is the outer black band (row 0 first: the reader turned the bottom-up file the right way)
        assertTrue(Color.alpha(rim.getPixel(40, 1)) > 100)
        assertTrue(Color.alpha(rim.getPixel(40, 250)) < 20)
        assertNull(MediaArt.get(app, "no_such_texture"))
        assertTrue(MediaArt.bytes() > 0)
    }

    // ---------------------------------------------------------------- in a window, as on the device (the views' posts run)

    /** the room in an activity's window: a view's post runs there (the band's reveal scroll, the add-on listeners' refresh), which a
     *  detached room never does (the tests above, and the renders, scroll by hand) */
    private fun windowed(): Pair<SettingsRoom, android.widget.ScrollView> {
        Settings.init(app); SettingsTabs.init(app); GameUi.init(app); art()
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val v = SettingsView(act) { }
        val r = SettingsRoom(act, v) { null }
        act.setContentView(r)
        r.show(SettingsTabs.Tab.TOP)
        settle(r)
        val sc = SettingsRoom::class.java.getDeclaredField("scroll").apply { isAccessible = true }.get(r) as android.widget.ScrollView
        return r to sc
    }

    /** a few of the window's frames: the posts run, a smooth scroll runs out, the page is drawn (the pad resolves the cursor there) */
    private fun settle(r: View) {
        repeat(8) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            val root = r.rootView
            if (root.width > 0 && root.height > 0) root.draw(Canvas(Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)))
        }
    }

    /** one press in the window */
    private fun pressW(r: SettingsRoom, k: String) { r.pad.onPad(JSONObject().put("k", k).put("n", ++n)); settle(r) }

    /** THE BAND COMES INTO VIEW (the design: "the tile and its band scroll into view"; the builder's renders scrolled by hand): A on a
     *  tile low on the page, or a tap there, and the page moves so the tile and its whole band show; B brings the tile back */
    @Test @Config(qualifiers = "w1240dp-h1080dp-mdpi")
    fun theBandComesIntoViewInAWindow() {
        val (r, sc) = windowed()
        game(); settle(r)
        assertTrue("the page is taller than the window", sc.getChildAt(0).height > sc.height)
        fun inView(id: String) {
            val p = partOf(r, id)
            val top = contentTop(r, p)!!
            val tile = tileBoxes(r).getValue(id)
            val band = p.bandBox()!!.let { RectF(it.left, it.top + top, it.right, it.bottom + top) }
            val y0 = sc.scrollY.toFloat(); val y1 = y0 + sc.height
            assertTrue("$id tile ${tile.top}..${tile.bottom} in $y0..$y1", tile.top >= y0 && tile.bottom <= y1)
            assertTrue("$id band ${band.top}..${band.bottom} in $y0..$y1", band.top >= y0 && band.bottom <= y1)
        }
        // touch: the page at its top, a tap on the Target frame (Game windows' second row, below the window)
        sc.scrollTo(0, 0); settle(r)
        val tf = tileBoxes(r).getValue(GameUi.TARGET_ID)
        assertTrue("the Target frame starts below the window", tf.bottom > sc.height)
        val p = partOf(r, GameUi.TARGET_ID)
        tap(p, tf.centerX(), tf.centerY() - contentTop(r, p)!!); settle(r)
        assertEquals(GameUi.TARGET_ID, openTile(r))
        inView(GameUi.TARGET_ID)
        r.gameUis.forEach { it.closeSelection() }; sc.scrollTo(0, 0); settle(r)
        // the controller: down the tiles to Game windows' second row, A there - the cursor stays on its tile, the band in view
        r.pad.enter(); r.show(SettingsTabs.Tab.TOP, force = true)
        r.pad.onGuestAck(JSONObject().put("op", "hold").put("ok", true)); settle(r)
        repeat(12) { if (r.pad.focusBox() != tileBoxes(r)[GameUiSection.TIPS_ID]) pressW(r, "PADDDOWN") }
        assertEquals(tileBoxes(r).getValue(GameUiSection.TIPS_ID), r.pad.focusBox())
        pressW(r, "PAD1")
        assertEquals(GameUiSection.TIPS_ID, openTile(r))
        assertEquals("the cursor stays on the tile", tileBoxes(r).getValue(GameUiSection.TIPS_ID), r.pad.focusBox())
        inView(GameUiSection.TIPS_ID)
        // A on the Resource bar's tile up top (Up through the page): the band moves there, and it is the one in view
        repeat(12) { if (r.pad.focusBox() != tileBoxes(r)[GameUi.HUD_ID]) pressW(r, "PADDUP") }
        pressW(r, "PAD1")
        assertEquals(GameUi.HUD_ID, openTile(r))
        assertEquals(tileBoxes(r).getValue(GameUi.HUD_ID), r.pad.focusBox())
        inView(GameUi.HUD_ID)
        // into the band, then B: the band closes, the cursor on the tile and in view
        pressW(r, "PADDDOWN"); pressW(r, "PADDDOWN")
        pressW(r, "PAD2")
        assertNull(openTile(r))
        val hud = tileBoxes(r).getValue(GameUi.HUD_ID)
        assertEquals(hud, r.pad.focusBox())
        assertTrue(hud.top >= sc.scrollY && hud.bottom <= sc.scrollY + sc.height)
    }

    /** POLESTAR'S TILE (it was SettingsView's Show / Hide row; the switch says "hidden"): there while Polestar is in use, On = its frame
     *  shown; the choice writes the switch the right way round; the guide off takes the tile (and its open band) away */
    @Test @Config(qualifiers = "w1240dp-h1080dp-mdpi")
    fun polestarsTileSwitchesItsFrame() {
        ThirdPartyAddons::class.java.getDeclaredField("noFetch").apply { isAccessible = true }.setBoolean(null, true)   // no downloads here
        val dirF = ThirdPartyAddons::class.java.getDeclaredField("dir").apply { isAccessible = true }
        val stateF = ThirdPartyAddons::class.java.getDeclaredField("stateJ").apply { isAccessible = true }
        val savedDir = dirF.get(ThirdPartyAddons); val savedState = stateF.get(ThirdPartyAddons)
        try {
            // Polestar's core in the game (its state as the launcher's step writes it)
            dirF.set(ThirdPartyAddons, null)
            stateF.set(ThirdPartyAddons, JSONObject().put("polestar/core", JSONObject().put("in", ThirdPartyAddons.POLESTAR.core.tag)))
            val (r, _) = windowed()
            game(); settle(r)
            val nav = r.gameUis.single { it.part == GameUiSection.Part.NAVIGATION }
            assertEquals("the guide not chosen: no tile", listOf(GameUiSection.COMPASS_ID, GameUi.WM_ID), nav.tileIds())
            Settings.polestarGuide = PolestarGuide.ON; settle(r)
            assertEquals(listOf(GameUiSection.COMPASS_ID, GameUi.WM_ID, GameUiTiles.PS_ID), nav.tileIds())
            assertEquals(3, nav.focusTargets().size)
            val row = { GameUiSection::class.java.getDeclaredMethod("rowOf", String::class.java).apply { isAccessible = true }
                .invoke(nav, GameUiTiles.PS_ID) as GameUi.Row? }
            // the game says its frame is shown ("ps" = hidden: false): On
            assertEquals(UiMode.ON, row()!!.mode)
            assertEquals(GameUiTiles.Badge.ON, GameUiTiles.badge(row()))
            val t = nav.focusTargets()[2]
            tap(nav, t.centerX(), t.centerY()); settle(r)
            assertEquals(GameUiTiles.PS_ID, nav.openTile)
            val b = nav.focusTargets().drop(3)
            assertEquals("On / Off", 2, b.size)
            // Off: the switch's "hidden" wanted; the picture follows (dimmed), the badge waits for the game
            tap(nav, b[1].centerX(), b[1].centerY()); settle(r)
            assertEquals(true, GameUi.swWant(GameUi.SW_PS))
            assertEquals(UiMode.OFF, row()!!.mode)
            assertEquals(GameUiTiles.Badge.SENT, GameUiTiles.badge(row()))
            // On again: shown wanted, the game agrees
            tap(nav, b[0].centerX(), b[0].centerY()); settle(r)
            assertEquals(false, GameUi.swWant(GameUi.SW_PS))
            assertEquals(GameUiTiles.Badge.ON, GameUiTiles.badge(row()))
            // the guide off: the tile goes, its band with it (B has nothing of it to close)
            Settings.polestarGuide = PolestarGuide.OFF; settle(r)
            assertEquals(listOf(GameUiSection.COMPASS_ID, GameUi.WM_ID), nav.tileIds())
            assertNull(nav.openTile)
            assertTrue(r.gameUis.none { it.hasSelection })
        } finally {
            dirF.set(ThirdPartyAddons, savedDir); stateF.set(ThirdPartyAddons, savedState)
            Settings.polestarGuide = null
        }
    }

    // ---------------------------------------------------------------- the renders (UIMENUS_SHOTS_DIR)

    @Test fun shots() {
        val out = System.getenv("UIMENUS_SHOTS_DIR")?.let { File(it) } ?: return
        out.mkdirs()
        val made = ArrayList<Pair<String, Bitmap>>()
        var r = room()
        val sc = SettingsRoom::class.java.getDeclaredField("scroll")
        fun scroll(to: Int) { (sc.apply { isAccessible = true }.get(r) as android.widget.ScrollView).scrollTo(0, to); lay(r) }
        fun shot(name: String) {
            lay(r); Thread.sleep(250); lay(r)
            val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(Theme.ink); r.draw(c)
            File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            made.add(name to b)
        }
        // the room brings the tile and its band in view (its post does not run on a detached view here; theBandComesIntoViewInAWindow
        // proves the room does it): the same least move, computed as the pad computes it, so a shot shows what the player sees
        fun reveal(id: String) {
            val p = partOf(r, id); val top = contentTop(r, p)!!
            val t = tileBoxes(r).getValue(id)
            val bottom = p.bandBox()?.let { it.bottom + top } ?: t.bottom
            val sv = sc.apply { isAccessible = true }.get(r) as android.widget.ScrollView
            GameUiTiles.reveal(sv.scrollY.toFloat(), sv.height.toFloat(), t.top - SettingsPad.REVEAL_TOP, bottom + SettingsPad.REVEAL_BOTTOM)
                ?.let { scroll(it.toInt().coerceAtLeast(0)) }
        }
        fun open(id: String) {
            val p = partOf(r, id); val t = tileBoxes(r).getValue(id)
            tap(p, t.centerX(), t.centerY() - contentTop(r, p)!!); lay(r)
            reveal(id)
            Thread.sleep(400); org.robolectric.shadows.ShadowLooper.idleMainLooper(); lay(r)
        }
        fun close() { r.gameUis.forEach { it.closeSelection() }; lay(r) }
        shot("top_1_no_game")
        game(chatHidden = false); GameUi.setMode("minimap", UiMode.DYNAMIC); GameUi.setMode("chat", UiMode.ON)
        GameUi.setSwitch(GameUi.SW_NPF, true)                            // sent, the game not caught up: the hourglass
        lay(r)
        shot("top_2_reported")
        scroll(900); shot("top_3_reported_lower")
        scroll(0)
        open(GameUi.HUD_ID); shot("top_4_band_resource_bar")
        open(GameUi.UFT_ID); shot("top_5_band_target_bar")
        close(); open(GameUi.NP_ID); shot("top_6_band_nameplates")
        close(); open("minimap"); shot("top_7_band_minimap")
        close(); open("hud"); GameUi.setMode("hud", UiMode.NINTENDO); lay(r); shot("top_8_band_controller_bars")
        // the cursor on a tile with its band open (the gold box over the picture; the open tile's lit edge)
        // (the page where the room puts it: the tile and its whole band in view, as on the device - it was scrolled back to the top here,
        // which cut the band off at the screen's foot)
        close(); hold(r); press(r, "PADDDOWN"); press(r, "PADDDOWN"); press(r, "PAD1"); reveal(GameUi.UFT_ID); shot("top_9_cursor_on_open_tile")
        press(r, "PADDDOWN"); shot("top_10_cursor_in_band")
        press(r, "PAD2"); shot("top_11_b_back_on_tile")
        // Spanish and French: the names fitted (shrunk, then cut)
        for (l in listOf("es", "fr")) {
            listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
                .firstOrNull { it.canRead() }?.let { Tr.load(l, it.readText()) }
            Tr.setChoice(app, l); close(); scroll(0); shot("top_12_$l")
            open(GameUi.HUD_ID); shot("top_13_${l}_band")
        }
        Tr.setChoice(app, "auto")
        // no kit on the phone yet: every picture our own sketch
        r = room(kit = false); game(); lay(r); shot("top_14_no_kit")
        open(GameUi.UFT_ID); shot("top_15_no_kit_band")
        art()
        sheet(out, made)
        pictures(out)
    }

    /** the 19 pictures at the band's size, each with its name: the review sheet */
    private fun pictures(out: File) {
        GameUi.init(app); game()
        val ids = GameUiTiles.ALL
        val cw = 438; val ch = 220; val pad = 14; val label = 30; val cols = 4
        val rows = (ids.size + cols - 1) / cols
        val sheet = Bitmap.createBitmap(cols * (cw + pad) + pad, rows * (ch + label + pad) + pad, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet); c.drawColor(Color.rgb(40, 40, 40))
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f }
        for ((i, id) in ids.withIndex()) {
            val x = pad + (i % cols) * (cw + pad); val y = pad + (i / cols) * (ch + label + pad)
            val look = TilePictures.Look(mode = if (id == "hud") UiMode.NINTENDO else UiMode.ON, pet = true, hpText = 1, tot = true, cls = "SHAMAN")
            TilePictures.draw(c, app, id, RectF(x.toFloat(), y.toFloat(), (x + cw).toFloat(), (y + ch).toFloat()), look)
            c.drawText("$id  (${GameUiTiles.NAMES[id]})", x.toFloat(), (y + ch + 24).toFloat(), tp)
        }
        File(out, "pictures_sheet.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun sheet(out: File, made: List<Pair<String, Bitmap>>) {
        val k = 0.5f
        val cw = (W * k).toInt(); val ch = (H * k).toInt()
        val cols = 3; val rows = (made.size + cols - 1) / cols
        val pad = 12; val label = 34
        val sheet = Bitmap.createBitmap(cols * (cw + pad) + pad, rows * (ch + label + pad) + pad, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.rgb(40, 40, 40))
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f }
        for ((i, e) in made.withIndex()) {
            val x = pad + (i % cols) * (cw + pad); val y = pad + (i / cols) * (ch + label + pad)
            c.drawBitmap(e.second, null, android.graphics.Rect(x, y, x + cw, y + ch), Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawText(e.first, x.toFloat(), (y + ch + 26).toFloat(), tp)
        }
        File(out, "top_sheet.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
