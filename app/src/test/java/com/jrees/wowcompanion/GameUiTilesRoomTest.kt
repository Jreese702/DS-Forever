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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * a sheet of the 22 pictures; THEME_ART_ROOT = an art root laid out like the app's (ui/kit, ui/cast, fonts).
 *
 * hub61 (the user, 2026-11-17: "DPS meters and also the cooldown manager ... we need to make sure that its consistent with the other
 * picture options"): Game windows' Damage meter (On / Off / Dynamic) and Cooldown Manager (its four parts in its band, gone while Off),
 * the game's option off (GAME_OFF), Layout's Edit layout tile (A opens the editor's page, no band); shots top_16..25 (22..24 the cursor,
 * 25 the OLED / Dark themes).
 *
 * split-hud (the user, 2026-11-17: "we need to split out the Buffs controller UI and XP bar" -> "Both"): Game windows' Controller UI,
 * Buffs and XP bar, three tiles with their own pictures and modes; a pick sends that part's word on "lay" (the add-on's hs); with
 * SPLIT_HUD_SHOTS_DIR set, the three tiles, their bands (es / fr too) and their pictures.
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

    /** hub61: the hub document's `mt` as Hub.lua's WC.meters.doc() writes it */
    private fun mtDoc(d: String = "on", c: Boolean = true, p: Int = 15, dok: Boolean = true, cok: Boolean = true, q: Boolean = false) =
        JSONObject().put("v", "mt1").put("d", d).put("c", c).put("p", p).put("df", true).put("cf", true).put("dok", dok).put("dav", true)
            .put("cok", cok).put("cav", true).put("q", q).put("n", 2)
            .put("cols", JSONObject().put("DMG_OFF", 0).put("DMG_ON", 1).put("DMG_DYN", 2).put("CDM_OFF", 3).put("CDM_ON", 4).put("PARTS", 8))

    /** the layout editor's document (tools/test_layout.py's fixture): the Layout group's tile shows */
    private fun layDoc() {
        val f = listOf(File("../dsforever/src/test/resources/layout-fixtures/lay_closed.json"), File("dsforever/src/test/resources/layout-fixtures/lay_closed.json"))
            .first { it.canRead() }
        LayoutModel.Live.note(JSONObject(f.readText()))
    }

    /** split-hud: the hub document's `hs` as Hub.lua's HS.doc() writes it (words: null = ride) */
    private fun hsDoc(c: String? = null, b: String? = null, x: String? = null, ch: Boolean = false, bh: Boolean = false, xh: Boolean = false) =
        JSONObject().put("v", "hs1").put("n", 0).apply {
            c?.let { put("c", it) }; b?.let { put("b", it) }; x?.let { put("x", it) }
            if (ch) put("ch", true); if (bh) put("bh", true); if (xh) put("xh", true)
        }.put("cols", JSONObject().put("C_ON", 24).put("C_OFF", 25).put("C_RIDE", 26).put("B_ON", 27).put("B_OFF", 28).put("B_RIDE", 29)
            .put("X_ON", 30).put("X_OFF", 31).put("X_RIDE", 32))

    private fun game(hudHidden: Boolean = false, chatHidden: Boolean = true, pending: Boolean = false, mt: JSONObject? = mtDoc(),
                     hs: JSONObject? = hsDoc(ch = hudHidden, bh = hudHidden, xh = hudHidden), psHidden: Boolean = false) {
        GameUi.character = "Sample-Realm"
        val cols = JSONObject()
        listOf("PLAYER_SHOW", "PLAYER_HIDE", "TARGET_SHOW", "TARGET_HIDE", "NP_OFF", "NP_ON", "PRD_OFF", "PRD_ON", "WM_OFF", "WM_ON",
            "PARTY_SHOW", "PARTY_HIDE", "UFP_ON", "UFP_OFF", "UFT_ON", "UFT_OFF", "UFTOT_ON", "UFTOT_OFF", "UFHPP_ON", "UFHPP_OFF",
            "UFHPT_ON", "UFHPT_OFF", "PS_HIDE", "PS_SHOW", "PET_ON", "PET_OFF", "NPF_ON", "NPF_OFF").forEachIndexed { i, k -> cols.put(k, i) }
        val sw = JSONObject().put("row", "uisw").put("cols", cols).put("set", JSONObject())
            .put("player", "hidden").put("target", "hidden").put("np", true).put("prd", true).put("wm", false).put("party", "shown")
            .put("ufp", true).put("uft", true).put("uftot", false).put("ufhpp", false).put("ufhpt", false).put("ps", psHidden).put("pet", true)
            .put("npf", false)
        val els = org.json.JSONArray()
        for ((i, id) in listOf("minimap", "hud", "tracker", "chat", "dialogs").withIndex())
            els.put(JSONObject().put("id", id).put("bit", i).put("hidden", if (id == "chat") chatHidden else if (id == "hud") hudHidden else true))
        val hub = JSONObject().put("boot", 1).put("n", 3).put("pending", pending).put("row", "ui").put("els", els)
            .put("cb", JSONObject().put("hidden", true)).put("sw", sw)
            .put("prd", JSONObject().put("y", -350).put("w", 280).put("s", 100))
        if (mt != null) hub.put("mt", mt)
        if (hs != null) hub.put("hs", hs)
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
        LayoutModel.Live.clearForTest()
        for (f in listOf("sw", "state", "castBarHidden", "prdY", "prdW", "prdS", "prefs", "mt", "hs"))
            GameUi::class.java.getDeclaredField(f).apply { isAccessible = true }.set(GameUi, null)
        GameUi.character = null
        Tr.setChoice(app, "auto")
    }

    // ---------------------------------------------------------------- the tab

    /** the Top screen tab: five plain headings (no folding, no heading stops), every group open, each drawn by ONE tile part */
    @Test fun topTabIsFiveOpenGroupsOfTiles() {
        val r = room()
        // (11-16 merge: the layout editor's Layout group comes first on the tab, its one row SettingsView's, hidden with no "lay" document)
        assertEquals(SettingsTabs.Group.LAYOUT, SettingsTabs.groups(SettingsTabs.Tab.TOP).first())
        val groups = SettingsTabs.groups(SettingsTabs.Tab.TOP) - SettingsTabs.Group.LAYOUT
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
        // 22 tiles with Polestar not in use and no layout editor (hub61: + the damage meter and the Cooldown Manager; split-hud: +
        // Buffs and XP bar); before the game: every one a "..." tile but the elements' (their choice known)
        assertEquals(22, tileBoxes(r).size)
        // the layout editor's document: its Layout group shows, one tile (23; 24 with Polestar)
        layDoc(); r.refreshGroups(); lay(r)
        assertEquals(23, tileBoxes(r).size)
        assertEquals(GameUiTiles.LAYOUT_ID, tileBoxes(r).keys.first())
        // Controller > Combat helpers keep their rows
        assertTrue(r.gameUis.filter { it.part == GameUiSection.Part.COMBAT || it.part == GameUiSection.Part.AUTO_SHOT }.none { it.tiled })
    }

    /** the grid: five columns of 232 x 184 tiles on the room's 1240 px page, 12 px apart */
    @Test fun gridIsFiveColumnsOfTiles() {
        val r = room()
        val w = tileBoxes(r)
        val win = GameUiTiles.slots(SettingsTabs.Group.WINDOWS, false).map { w.getValue(it) }
        assertEquals(14, win.size)
        // hub61: 5 + 5 + 2; split-hud: 5 + 5 + 4
        assertEquals(3, win.map { it.top }.distinct().size)
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
        // (11-16 merge: HUD height and HUD scale are the layout editor's now: the width stepper stays)
        assertEquals("On / Off, Pet bar On / Off, the width stepper of three", 2 + 2 + 3, buttons.size)
        val band = p.bandBox()!!
        assertTrue("tile + band ${band.bottom - (t.top - contentTop(r, p)!!)} fit on one screen",
            band.bottom - (t.top - contentTop(r, p)!!) + SettingsPad.REVEAL_TOP + SettingsPad.REVEAL_BOTTOM <= H - SettingsTabBar.BAR_H)
        // the width stepper's Wider: one step, kept for this character (11-16 merge: the height stepper is the layout editor's now)
        val before = GameUi.hudWidth()!!
        val wider = buttons[4 + 2]
        tap(p, wider.centerX(), wider.centerY()); lay(r)
        assertTrue("one step wider: $before -> ${GameUi.hudWidth()}", GameUi.hudWidth()!! > before)
        // (review 11-17) the game has not taken it yet: the Width line and the picture both say the game's width, as the Height and
        // Scale lines always have; the choice waits on the badge
        val game = GameUi::class.java.getDeclaredField("prdW").apply { isAccessible = true }.get(GameUi) as Int
        assertNotEquals(game, GameUi.hudWidth())
        assertEquals("$game", GameUi.hudValue(GameUi.HUD_W_ID))
        assertEquals(game / 280f, GameUi.hudWidthFactor(), 0.001f)
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


    // ---------------------------------------------------------------- hub61: the damage meter, the Cooldown Manager, Edit layout

    private fun rowOf(p: GameUiSection, id: String) =
        GameUiSection::class.java.getDeclaredMethod("rowOf", String::class.java).apply { isAccessible = true }.invoke(p, id) as GameUi.Row?

    /** hub61 (verifier 11-17): the Damage meter's and the Cooldown Manager's pictures keep the tile's badge corner clear (the well's
     *  top-right 36 px badge and its 4 px inset, in the picture's 216 x 108 units): only the world behind is drawn there, as on the other
     *  tiles that keep it clear */
    @Test fun theMetersPicturesKeepTheBadgeCornerClear() {
        art()
        val world = TilePictures::class.java.getDeclaredMethod("world", Canvas::class.java).apply { isAccessible = true }
        val w = TilePictures.W.toInt(); val h = TilePictures.H.toInt()
        val bg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { world.invoke(TilePictures, Canvas(it)) }
        val corner = android.graphics.Rect(w - 4 - GameUiTiles.BADGE.toInt() - 4, 4, w - 4, 4 + GameUiTiles.BADGE.toInt() + 4)
        for (id in listOf(GameUi.DMG_ID, GameUi.CDM_ID)) for (mode in listOf(UiMode.ON, UiMode.DYNAMIC)) {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            TilePictures.draw(Canvas(b), app, id, RectF(0f, 0f, w.toFloat(), h.toFloat()), TilePictures.Look(mode = mode, cls = "SHAMAN"))
            var differ = 0
            for (y in corner.top until corner.bottom) for (x in corner.left until corner.right)
                if (b.getPixel(x, y) != bg.getPixel(x, y)) differ++
            assertEquals("$id $mode: pixels drawn in the badge corner", 0, differ)
        }
    }

    /** the 24-tile walk: every tile a stop, in reading order (Edit layout first, the meters Game windows' last row) */
    @Test fun theCursorWalksAllTwentyFourTiles() {
        ThirdPartyAddons::class.java.getDeclaredField("noFetch").apply { isAccessible = true }.setBoolean(null, true)
        val dirF = ThirdPartyAddons::class.java.getDeclaredField("dir").apply { isAccessible = true }
        val stateF = ThirdPartyAddons::class.java.getDeclaredField("stateJ").apply { isAccessible = true }
        val savedDir = dirF.get(ThirdPartyAddons); val savedState = stateF.get(ThirdPartyAddons)
        try {
            dirF.set(ThirdPartyAddons, null)
            stateF.set(ThirdPartyAddons, JSONObject().put("polestar/core", JSONObject().put("in", ThirdPartyAddons.POLESTAR.core.tag)))
            Settings.init(app); Settings.polestarGuide = PolestarGuide.ON
            val r = room(); game(); layDoc(); r.refreshGroups(); lay(r); hold(r)
            val boxes = tileBoxes(r)
            assertEquals(24, boxes.size)
            assertEquals(GameUiTiles.ALL.toSet(), boxes.keys)
            val seen = LinkedHashSet<String>()
            press(r, "PADDDOWN")
            repeat(40) {
                boxes.entries.firstOrNull { it.value == r.pad.focusBox() }?.let { seen += it.key }
                // along each row, then down
                val before = r.pad.focusBox()
                press(r, "PADDRIGHT")
                if (r.pad.focusBox() == before) { repeat(5) { press(r, "PADDLEFT") }; press(r, "PADDDOWN") }
            }
            assertEquals("every tile reached", GameUiTiles.ALL.toSet(), seen)
            assertEquals(GameUiTiles.LAYOUT_ID, seen.first())
        } finally {
            dirF.set(ThirdPartyAddons, savedDir); stateF.set(ThirdPartyAddons, savedState); Settings.polestarGuide = null
        }
    }

    /** the Damage meter's band: On / Off / Dynamic; a press keeps the choice per character and the picture follows at once, the badge
     *  waits for the add-on's word (Sent); the add-on's word in: its mode */
    @Test fun theDamageMetersBandHasThreeModes() {
        val r = room(); game(); lay(r)
        val p = partOf(r, GameUi.DMG_ID)
        val t = tileBoxes(r).getValue(GameUi.DMG_ID)
        tap(p, t.centerX(), t.centerY() - contentTop(r, p)!!); lay(r)
        assertEquals(GameUi.DMG_ID, p.openTile)
        val b = p.focusTargets().drop(p.tileIds().size)
        assertEquals("On, Off, Dynamic", 3, b.size)
        tap(p, b[2].centerX(), b[2].centerY()); lay(r)
        assertEquals(UiMode.DYNAMIC, GameUi.dmgMode())
        assertEquals(GameUiTiles.Badge.SENT, GameUiTiles.badge(rowOf(p, GameUi.DMG_ID)))
        game(mt = mtDoc(d = "dyn")); lay(r)
        assertEquals(GameUiTiles.Badge.DYNAMIC, GameUiTiles.badge(rowOf(p, GameUi.DMG_ID)))
        assertEquals("Hidden now", rowOf(p, GameUi.DMG_ID)!!.note)
        // another character: its own choice (none made: the add-on's word)
        GameUi.character = "Other-Realm"
        game(mt = mtDoc(d = "on")); GameUi.character = "Other-Realm"
        assertEquals(UiMode.ON, GameUi.dmgMode())
    }

    /** the Cooldown Manager's band: its On / Off, then one On / Off line per part - only while it is On (a line that cannot act is not
     *  drawn); A on a part's Off keeps the mask; Off: the four lines go, the cursor stays on the button pressed */
    @Test fun theCooldownManagersPartsComeAndGoWithIt() {
        val r = room(); game(); lay(r); hold(r)
        val p = partOf(r, GameUi.CDM_ID)
        val t = tileBoxes(r).getValue(GameUi.CDM_ID)
        repeat(30) { if (r.pad.focusBox() != t) press(r, "PADDDOWN") }
        repeat(6) { if (r.pad.focusBox() != t) press(r, "PADDRIGHT") }
        assertEquals(t, r.pad.focusBox())
        press(r, "PAD1")
        assertEquals(GameUi.CDM_ID, p.openTile)
        var b = p.focusTargets().drop(p.tileIds().size)
        assertEquals("On / Off + 4 parts x On / Off", 2 + 4 * 2, b.size)
        // Utility Off (the second part's Off)
        tap(p, b[2 + 2 + 1].centerX(), b[2 + 2 + 1].centerY()); lay(r)
        assertEquals(0b1101, GameUi.cdmParts())
        assertEquals(UiMode.OFF, rowOf(p, GameUi.CDM_UTIL_ID)!!.mode)
        // the tile Off: the parts' lines go
        b = p.focusTargets().drop(p.tileIds().size)
        tap(p, b[1].centerX(), b[1].centerY()); lay(r)
        assertEquals(false, GameUi.cdmOn())
        assertEquals("only its own On / Off", 2, p.focusTargets().drop(p.tileIds().size).size)
        assertNull(rowOf(p, GameUi.CDM_ESS_ID))
        // On again: the lines back, the mask kept
        b = p.focusTargets().drop(p.tileIds().size)
        tap(p, b[0].centerX(), b[0].centerY()); lay(r)
        assertEquals(10, p.focusTargets().drop(p.tileIds().size).size)
        assertEquals(0b1101, GameUi.cdmParts())
        // B: the band closes, the cursor back on the tile
        press(r, "PAD2")
        assertNull(p.openTile)
    }

    /** the game's own option off: the alert_gear badge, the picture dimmed, "Off in the game's Options"; the buttons still take the
     *  choice (it works once the option is on) */
    @Test fun theGamesOptionOffWaits() {
        val r = room(); game(mt = mtDoc(dok = false, cok = false)); lay(r)
        val p = partOf(r, GameUi.DMG_ID)
        val d = rowOf(p, GameUi.DMG_ID)!!
        assertEquals(GameUi.GAME_OFF_NOTE, d.note)
        assertEquals(GameUiTiles.Badge.GAME_OFF, GameUiTiles.badge(d))
        assertTrue(GameUiTiles.dimmed(d))
        assertEquals(GameUiTiles.Badge.GAME_OFF, GameUiTiles.badge(rowOf(p, GameUi.CDM_ID)))
        val t = tileBoxes(r).getValue(GameUi.DMG_ID)
        tap(p, t.centerX(), t.centerY() - contentTop(r, p)!!); lay(r)
        val b = p.focusTargets().drop(p.tileIds().size)
        tap(p, b[1].centerX(), b[1].centerY()); lay(r)
        assertEquals("the choice kept", UiMode.OFF, GameUi.dmgMode())
    }

    /** Edit layout: a tile with no band; A (or a tap) opens the editor's page; there only once the add-on set has the editor */
    @Test fun editLayoutOpensItsPage() {
        val r = room(); game(); lay(r)
        assertTrue(GameUiTiles.LAYOUT_ID !in tileBoxes(r))
        layDoc(); r.refreshGroups(); lay(r)
        val p = partOf(r, GameUiTiles.LAYOUT_ID)
        var opened = 0
        p.onEditLayout = { opened++ }
        val t = tileBoxes(r).getValue(GameUiTiles.LAYOUT_ID)
        tap(p, t.centerX(), t.centerY() - contentTop(r, p)!!); lay(r)
        assertEquals(1, opened)
        assertNull("no band", p.openTile)
        assertEquals("the tile alone is a stop", 1, p.focusTargets().size)
        hold(r)
        press(r, "PADDDOWN")
        assertEquals(t, r.pad.focusBox())
        press(r, "PAD1")
        assertEquals(2, opened)
        assertNull(p.openTile)
    }

    /** the Cooldown Manager's row follows the add-on's word: Sent until it says so, then its mode; a press held for the fight says so
     *  (the column sent is GameUiTilesTest's theMetersTapOnlyWhatWasChosen, the add-on's side tools/test_layout.py's o) */
    @Test fun theMetersRowsFollowTheAddOnsWord() {
        GameUi.init(app)
        game()
        GameUi.setCdm(false)
        assertEquals(GameUiTiles.Badge.SENT, GameUiTiles.badge(GameUi.cdmRow()))
        game(mt = mtDoc(c = false))
        assertEquals(GameUiTiles.Badge.OFF, GameUiTiles.badge(GameUi.cdmRow()))
        assertNull("its parts' lines go while Off", GameUi.cdmPartRow(GameUi.CDM_ESS_ID))
        game(mt = mtDoc(c = false, q = true))
        assertEquals("Changes when combat ends", GameUi.cdmRow()!!.note)
        assertEquals(GameUiTiles.Badge.SENT, GameUiTiles.badge(GameUi.cdmRow()))
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
            // 2026-11-20 (the user: "we should hide that by default"): nothing chosen = hidden wanted. The game still shows it
            // ("ps" = hidden: false): Off, the badge waits for the game
            assertEquals(true, GameUi.swWant(GameUi.SW_PS))
            assertEquals(UiMode.OFF, row()!!.mode)
            assertEquals(GameUiTiles.Badge.SENT, GameUiTiles.badge(row()))
            // the game took the hide: Off, settled
            game(psHidden = true); settle(r)
            assertEquals(UiMode.OFF, row()!!.mode)
            assertEquals(GameUiTiles.Badge.OFF, GameUiTiles.badge(row()))
            assertEquals("Hidden now", row()!!.note)
            val t = nav.focusTargets()[2]
            tap(nav, t.centerX(), t.centerY()); settle(r)
            assertEquals(GameUiTiles.PS_ID, nav.openTile)
            val b = nav.focusTargets().drop(3)
            assertEquals("On / Off", 2, b.size)
            // On: shown wanted (the switch's "hidden" false); the badge waits for the game
            tap(nav, b[0].centerX(), b[0].centerY()); settle(r)
            assertEquals(false, GameUi.swWant(GameUi.SW_PS))
            assertEquals(UiMode.ON, row()!!.mode)
            assertEquals(GameUiTiles.Badge.SENT, GameUiTiles.badge(row()))
            // Off again: hidden wanted, the game agrees
            tap(nav, b[1].centerX(), b[1].centerY()); settle(r)
            assertEquals(true, GameUi.swWant(GameUi.SW_PS))
            assertEquals(GameUiTiles.Badge.OFF, GameUiTiles.badge(row()))
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

    /** 2026-11-20 POLESTAR HIDDEN BY DEFAULT, EXISTING PLAYERS: a character that never chose (no key) moves to hidden; one whose saved
     *  choice is shown (they turned it On) keeps it shown; one that chose hidden stays hidden. Nothing is written by the move */
    @Test fun existingPlayersKeepAChosenOnAndEveryoneElseIsHidden() {
        GameUi.init(app)
        val sp = app.getSharedPreferences("gameui", android.content.Context.MODE_PRIVATE)
        sp.edit().putBoolean("sw.ps.Shown-Realm", false).putBoolean("sw.ps.Hidden-Realm", true).commit()
        val want = { who: String -> GameUi.character = who; GameUi.swWant(GameUi.SW_PS) }
        assertEquals("never chose: hidden", true, want("Sample-Realm"))
        assertEquals("turned it On: kept shown", false, want("Shown-Realm"))
        assertEquals("chose hidden: hidden", true, want("Hidden-Realm"))
        assertFalse("no key written for the one that never chose", sp.contains("sw.ps.Sample-Realm"))
        // the game shows it for the one that never chose: the next switch tap is the hide; for the one that turned it On, nothing
        game()
        val s = GameUi::class.java.getDeclaredField("sw").apply { isAccessible = true }.get(GameUi) as GameUi.Sw
        val shown = s.copy(set = s.set + (GameUi.SW_PS to true))
        GameUi.character = "Sample-Realm"
        assertEquals(GameUi.SW_PS to true, GameUi.swNextSkip(shown, setOf(GameUi.SW_PLAYER, GameUi.SW_TARGET, GameUi.SW_NP, GameUi.SW_PRD,
            GameUi.SW_WM, GameUi.SW_PARTY, GameUi.SW_UFP, GameUi.SW_UFT, GameUi.SW_UFTOT, GameUi.SW_UFHPP, GameUi.SW_UFHPT)) { GameUi.swWant(it) })
        GameUi.character = "Shown-Realm"
        assertNull(GameUi.swNextSkip(shown, setOf(GameUi.SW_PLAYER, GameUi.SW_TARGET, GameUi.SW_NP, GameUi.SW_PRD, GameUi.SW_WM,
            GameUi.SW_PARTY, GameUi.SW_UFP, GameUi.SW_UFT, GameUi.SW_UFTOT, GameUi.SW_UFHPP, GameUi.SW_UFHPT, GameUi.SW_PET, GameUi.SW_NPF)) { GameUi.swWant(it) })
    }

    // ---------------------------------------------------------------- split-hud: the three tiles

    /** the user, 2026-11-17: "we need to split out the Buffs controller UI and XP bar" -> "Both": three tiles where one was, each its
     *  own mode; the old choice carried over until one is tapped; the others then keep theirs; a pick's word sent on "lay"; an add-on set
     *  before the split: the buffs and the XP bar wait ("..."), the controller tile as before */
    @Test fun theBuffsAndTheXpBarAreTilesOfTheirOwn() {
        // a player from before the split: Controller bars saved Dynamic (written as that build wrote it)
        app.getSharedPreferences("gameui", android.content.Context.MODE_PRIVATE).edit().putString("mode.hud", UiMode.DYNAMIC.name).commit()
        val r = room()
        game()
        lay(r)
        val boxes = tileBoxes(r)
        for (id in HudSplit.PARTS) assertTrue(id, boxes.containsKey(id))
        assertEquals("the old mode carried over", UiMode.DYNAMIC, GameUi.elementRow(HudSplit.BUFFS_ID)!!.mode)
        assertEquals(UiMode.DYNAMIC, GameUi.elementRow(HudSplit.XP_ID)!!.mode)
        assertEquals(listOf("hud", "buffs", "xp"), GameUiSection.WINDOWS_ORDER.filter { it in HudSplit.PARTS })
        // the XP bar On: the controller and the buffs keep Dynamic (no longer following the controller tile)
        GameUi.setMode(HudSplit.XP_ID, UiMode.ON)
        GameUi.setMode("hud", UiMode.OFF)
        assertEquals(UiMode.DYNAMIC, GameUi.mode(HudSplit.BUFFS_ID))
        assertEquals(UiMode.ON, GameUi.mode(HudSplit.XP_ID))
        // the bit rides Dynamic; the controller stands off, the XP bar on: their words; the buffs ride
        assertEquals(UiMode.DYNAMIC, GameUi.hudBitMode())
        assertEquals(mapOf("hud" to "off", "buffs" to "ride", "xp" to "on"), GameUi.hsWant())
        val h = GameUi.hsFrom(hsDoc())!!
        assertEquals("the controller's word first: C_OFF", 25, GameUi.hsCol(h))
        assertEquals("then the XP bar's: X_ON", 30, GameUi.hsCol(GameUi.hsFrom(hsDoc(c = "off"))!!))
        assertNull(GameUi.hsCol(GameUi.hsFrom(hsDoc(c = "off", x = "on"))!!))
        // the tile says "Sent" until the add-on's word and state match, then its state
        val sent = GameUi.elementRow(HudSplit.XP_ID)!!
        assertEquals("On the top screen now", sent.note)                        // shown, as chosen
        game(hs = hsDoc(c = "off", x = "on", ch = true)); lay(r)
        assertEquals("Hidden now", GameUi.elementRow("hud")!!.note)
        assertEquals(GameUiTiles.Badge.OFF, GameUiTiles.badge(GameUi.elementRow("hud")))
        assertEquals(GameUiTiles.Badge.ON, GameUiTiles.badge(GameUi.elementRow(HudSplit.XP_ID)))
        // an add-on set before the split: the two new tiles wait, the controller tile is the old element's row
        game(hs = null); lay(r)
        assertEquals(GameUiTiles.Badge.WAITING, GameUiTiles.badge(GameUi.elementRow(HudSplit.BUFFS_ID)))
        assertEquals("the bit as before: the controller tile's mode", UiMode.OFF, GameUi.hudBitMode())
        assertEquals("Controller UI", GameUi.elementRow("hud")!!.label)
    }

    /** the three tiles drawn (SPLIT_HUD_SHOTS_DIR): the Game windows grid, each band open, Spanish and French, the pictures */
    @Test fun splitHudShots() {
        val out = System.getenv("SPLIT_HUD_SHOTS_DIR")?.let { File(it) } ?: return
        out.mkdirs()
        val r = room()
        val sc = SettingsRoom::class.java.getDeclaredField("scroll")
        fun scroll(to: Int) { (sc.apply { isAccessible = true }.get(r) as android.widget.ScrollView).scrollTo(0, to); lay(r) }
        fun shot(name: String) {
            lay(r); Thread.sleep(250); lay(r)
            val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(Theme.ink); r.draw(c)
            File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
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
        // the player's split: the controller UI Dynamic, the buffs On, the XP bar Off (the add-on caught up)
        GameUi.setMode("hud", UiMode.DYNAMIC); GameUi.setMode(HudSplit.BUFFS_ID, UiMode.ON); GameUi.setMode(HudSplit.XP_ID, UiMode.OFF)
        game(hs = hsDoc(b = "on", x = "off", xh = true)); lay(r)
        reveal(HudSplit.XP_ID); shot("split_1_game_windows")
        open("hud"); shot("split_2_band_controller_ui")
        close(); open(HudSplit.BUFFS_ID); shot("split_3_band_buffs")
        close(); open(HudSplit.XP_ID); shot("split_4_band_xp_bar")
        for (l in listOf("es", "fr")) {
            listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
                .firstOrNull { it.canRead() }?.let { Tr.load(l, it.readText()) }
            Tr.setChoice(app, l); close(); reveal(HudSplit.XP_ID); shot("split_5_$l")
            open(HudSplit.BUFFS_ID); shot("split_6_${l}_band_buffs")
        }
        Tr.setChoice(app, "auto")
        // the three pictures at the band's size (the controller On and Nintendo)
        val cw = 438; val ch = 220; val pad = 14; val label = 30
        val looks = listOf("hud" to UiMode.ON, "hud" to UiMode.NINTENDO, HudSplit.BUFFS_ID to UiMode.ON, HudSplit.XP_ID to UiMode.ON)
        val sheet = Bitmap.createBitmap(2 * (cw + pad) + pad, 2 * (ch + label + pad) + pad, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet); c.drawColor(Color.rgb(40, 40, 40))
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f }
        for ((i, e) in looks.withIndex()) {
            val x = pad + (i % 2) * (cw + pad); val y = pad + (i / 2) * (ch + label + pad)
            TilePictures.draw(c, app, e.first, RectF(x.toFloat(), y.toFloat(), (x + cw).toFloat(), (y + ch).toFloat()), TilePictures.Look(mode = e.second, cls = "SHAMAN"))
            c.drawText("${GameUiTiles.NAMES[e.first]}  ${e.second}", x.toFloat(), (y + ch + 24).toFloat(), tp)
        }
        File(out, "split_7_pictures.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
        // hub61: the damage meter (Dynamic, the band's numbers), the Cooldown Manager (Utility off), each without the kit too
        fun meters(kit: Boolean, sfx: String) {
            r = room(kit = kit); game()
            Icons.refresh()                                              // (no kit: the spell icons' pack goes with it)
            GameUi.setDmg(UiMode.DYNAMIC); GameUi.setCdmPart(1, false); lay(r)
            open(GameUi.DMG_ID); shot("top_16_band_damage_meter$sfx")
            close(); open(GameUi.CDM_ID); shot("top_17_band_cooldown_manager$sfx")
        }
        meters(true, ""); meters(false, "_nokit"); art(); Icons.refresh()
        // the game's option off: the alert_gear badges, the pictures dimmed, the status line
        r = room(); game(mt = mtDoc(dok = false, cok = false)); lay(r)
        open(GameUi.DMG_ID); shot("top_18_game_off")
        // Edit layout's tile (the editor's own pieces), the compass following the camera, the HUD at its widest
        r = room(); game(); layDoc(); r.refreshGroups(); lay(r); scroll(0); shot("top_19_layout_tile")
        CompassSwitch.note(JSONObject().put("proof", JSONObject().put("compc", JSONObject().put("show", true).put("on", 1).put("off", 2)
            .put("cam", true).put("camOn", 3).put("camOff", 4)))); lay(r)
        open(GameUiSection.COMPASS_ID); shot("top_20_band_compass_cam")
        // (the game took the width: the picture follows the game's word, review 11-17)
        close(); repeat(6) { GameUi.stepHudWidth(1) }
        GameUi::class.java.getDeclaredField("prdW").apply { isAccessible = true }.set(GameUi, GameUi.hudWidth()); lay(r)
        open(GameUi.HUD_ID); shot("top_21_band_hud_wide")
        // (verifier 11-17) the cursor: the golden box on the Damage meter tile, on the Cooldown Manager band's first part line, on Edit
        // layout (its A opens the editor's page: theEditLayoutTileOpensTheEditor proves it; the page is LayoutShotsTest's)
        fun cursorTo(id: String) {
            val t = tileBoxes(r).getValue(id)
            repeat(30) { if (r.pad.focusBox()?.top != t.top) press(r, if ((r.pad.focusBox()?.top ?: 0f) < t.top) "PADDDOWN" else "PADDUP") }
            repeat(6) { if (r.pad.focusBox() != t) press(r, if ((r.pad.focusBox()?.left ?: 0f) < t.left) "PADDRIGHT" else "PADDLEFT") }
            reveal(id)
        }
        r = room(); game(); layDoc(); r.refreshGroups(); lay(r); hold(r)
        cursorTo(GameUi.DMG_ID); shot("top_22_cursor_on_damage_meter")
        cursorTo(GameUi.CDM_ID); press(r, "PAD1"); press(r, "PADDDOWN"); press(r, "PADDDOWN"); reveal(GameUi.CDM_ID)
        shot("top_23_cursor_in_cdm_parts")
        press(r, "PAD2"); press(r, "PAD2"); scroll(0); cursorTo(GameUiTiles.LAYOUT_ID); scroll(0); shot("top_24_cursor_on_edit_layout")
        // (verifier 11-17) the OLED and Dark themes: the meters' pictures, the GAME_OFF badge and the dimmed well on each ground
        try {
            for (t in listOf("oled_bronze", "oled_silver", "dark_rose")) {
                Theme.use(t)
                r = room(); game(); GameUi.setDmg(UiMode.DYNAMIC); lay(r)
                open(GameUi.DMG_ID); shot("top_25_${t}_damage_meter")
                close(); open(GameUi.CDM_ID); shot("top_25_${t}_cooldown_manager")
                r = room(); game(mt = mtDoc(dok = false, cok = false)); lay(r)
                open(GameUi.CDM_ID); shot("top_25_${t}_game_off")
            }
        } finally { Theme.use(ThemePresets.CLASSIC.id) }
        art()
        sheet(out, made)
        pictures(out)
    }

    /** the 22 pictures at the band's size, each with its name: the review sheet */
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
            val look = TilePictures.Look(mode = if (id == "hud") UiMode.NINTENDO else if (id == GameUi.DMG_ID) UiMode.DYNAMIC else UiMode.ON,
                pet = true, hpText = 1, tot = true, cls = "SHAMAN", cdm = if (id == GameUi.CDM_ID) 0b1111 else 0b1111)
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
