package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TOP SCREEN AS PICTURE TILES, the pure half (the user, 2026-11-16: "VISUAL overhaul of UI menus (picture beside each game-UI element
 * option)"; the design's recommended picks, accepted the same day): the 19 tiles in their groups with the Pet bar inside the Resource
 * bar's band, the badges, the grid (5 columns of 232 x 184 on the room's 1240 px page), the band's boxes (the picture left, the
 * choices at the 110 px touch floor, one status line), every band fitting on one screen with its tile, and the TGA reader on our own
 * Media files.
 *
 * hub61 (the user, 2026-11-17: "DPS meters and also the cooldown manager ... we need to make sure that its consistent with the other
 * picture options"): 22 tiles - Game windows' Damage meter and Cooldown Manager (its four parts in its band), the Layout group's Edit
 * layout tile; the GAME_OFF badge (the game's own option off); the meters' one tap on the layout editor's row ([GameUi.mtCol]).
 */
private typealias TG = SettingsTabs.Group
private typealias TB = GameUiTiles.Badge

class GameUiTilesTest {

    // ---------------------------------------------------------------- the tiles

    @Test fun twentyTwoTilesInTheirGroups() {
        // hub61: Edit layout first (the Layout group), the damage meter and the Cooldown Manager after the controller bars
        assertEquals(listOf(TG.LAYOUT, TG.HUD, TG.TARGET, TG.PARTY, TG.NAVIGATION, TG.WINDOWS), GameUiTiles.TILES.keys.toList())
        assertEquals(listOf(1, 1, 3, 2, 3, 12), GameUiTiles.TILES.values.map { it.size })
        assertEquals(22, GameUiTiles.ALL.size)
        assertEquals(22, GameUiTiles.ALL.toSet().size)
        assertEquals(21, GameUiTiles.TILES.keys.sumOf { GameUiTiles.slots(it, polestar = false).size })
        assertEquals(listOf("hud", GameUi.DMG_ID, GameUi.CDM_ID), GameUiTiles.TILES.getValue(TG.WINDOWS).takeLast(3))
        assertEquals(listOf(GameUiTiles.LAYOUT_ID), GameUiTiles.TILES.getValue(TG.LAYOUT))
        // Game windows: 5 + 5 + 2 on the room's page
        assertEquals(3, GameUiTiles.grid(12, 1240f, -1, 0f).tiles.map { it.t }.distinct().size)
        // the Cooldown Manager's band: its four parts (each only while the tile is On: GameUiTilesRoomTest)
        assertEquals(GameUi.CDM_PARTS, GameUiTiles.EXTRAS.getValue(GameUi.CDM_ID))
        assertEquals(listOf("Essential", "Utility", "Buff icons", "Buff bars"), GameUi.CDM_PARTS.map { GameUiTiles.EXTRA_NAMES.getValue(it) })
        // every tile has its picture
        assertEquals(GameUiTiles.ALL.toSet(), GameUiTiles.PICTURED)
        // the Game windows in the user's approved order
        assertEquals(GameUiSection.WINDOWS_ORDER, GameUiTiles.TILES.getValue(TG.WINDOWS))
        // the user's pick (2A): the Pet bar in the Resource bar's band, with the width stepper (height and scale: the layout editor's)
        assertEquals(listOf(GameUi.PET_ID, GameUi.HUD_W_ID), GameUiTiles.EXTRAS.getValue(GameUi.HUD_ID))
        assertTrue(GameUi.PET_ID !in GameUiTiles.ALL)
        assertEquals(listOf(GameUi.UFHP_ID, GameUi.UFTOT_ID), GameUiTiles.EXTRAS.getValue(GameUi.UFT_ID))
        assertEquals(listOf(GameUiSection.RINGS_ID), GameUiTiles.EXTRAS.getValue(GameUi.NP_ID))
        assertEquals(listOf(GameUiSection.COMPASS_CAM_ID), GameUiTiles.EXTRAS.getValue(GameUiSection.COMPASS_ID))
        // every tile and band row has its short name
        for (id in GameUiTiles.ALL) assertTrue(id, !GameUiTiles.NAMES[id].isNullOrBlank())
        for (id in GameUiTiles.EXTRAS.values.flatten()) assertTrue(id, !GameUiTiles.EXTRA_NAMES[id].isNullOrBlank())
        // only Top screen's groups are tiles (hub61: its Layout group too, the Edit layout tile)
        for (g in TG.values()) assertEquals(g.heading, g.tab == SettingsTabs.Tab.TOP, GameUiTiles.tiled(g))
        assertTrue(!GameUiTiles.tiled(null))
    }

    /** a FIXED slot list: only an add-on rules a tile out (Polestar's, while Polestar is not in use), never the game's silence */
    @Test fun slotsAreFixed() {
        assertEquals(listOf(GameUiSection.COMPASS_ID, GameUi.WM_ID), GameUiTiles.slots(TG.NAVIGATION, polestar = false))
        assertEquals(listOf(GameUiSection.COMPASS_ID, GameUi.WM_ID, GameUiTiles.PS_ID), GameUiTiles.slots(TG.NAVIGATION, polestar = true))
        for (g in GameUiTiles.TILES.keys - TG.NAVIGATION) assertEquals(GameUiTiles.slots(g, true), GameUiTiles.slots(g, false))
        assertEquals(emptyList<String>(), GameUiTiles.slots(TG.COMBAT, true))
    }

    // ---------------------------------------------------------------- the badge

    private fun row(id: String, mode: UiMode, note: String, known: Boolean = true) = GameUi.Row(id, id, null, mode, known, false, note)

    @Test fun badges() {
        assertEquals(TB.WAITING, GameUiTiles.badge(null))
        assertEquals("the game has not reported the element", TB.WAITING, GameUiTiles.badge(row("chat", UiMode.ON, "Waiting for the game", known = false)))
        assertEquals(TB.WAITING, GameUiTiles.badge(row("hud", UiMode.NINTENDO, "The game does not offer this yet")))
        // an element: the game's word
        assertEquals(TB.OFF, GameUiTiles.badge(row("chat", UiMode.OFF, "Hidden now")))
        assertEquals(TB.ON, GameUiTiles.badge(row("chat", UiMode.ON, "On the top screen now")))
        assertEquals(TB.DYNAMIC, GameUiTiles.badge(row("minimap", UiMode.DYNAMIC, "On the top screen now")))
        assertEquals(TB.TRACKING, GameUiTiles.badge(row("minimap", UiMode.TRACKING, "Hidden now")))
        assertEquals(TB.NINTENDO, GameUiTiles.badge(row("hud", UiMode.NINTENDO, "DS Forever's bar on the top screen now")))
        assertEquals("held for combat", TB.SENT, GameUiTiles.badge(row("tracker", UiMode.OFF, "Changes when combat ends")))
        assertEquals("loading", TB.SENT, GameUiTiles.badge(row("tracker", UiMode.OFF, "Waiting for the game")))
        assertEquals(TB.SENT, GameUiTiles.badge(row("dialogs", UiMode.OFF, "Sent")))
        assertEquals(TB.REFUSED, GameUiTiles.badge(row("minimap", UiMode.OFF, "The game refused: protected")))
        // a switch: its own words, "Sent" while the game has not caught up
        assertEquals(TB.ON, GameUiTiles.badge(row(GameUi.UFT_ID, UiMode.ON, "On: under the compass")))
        assertEquals(TB.OFF, GameUiTiles.badge(row(GameUi.WM_ID, UiMode.OFF, "Off: no markers in the world")))
        assertEquals(TB.SENT, GameUiTiles.badge(row(GameUi.NPF_ID, UiMode.ON, "Sent")))
        assertEquals(TB.SENT, GameUiTiles.badge(row(GameUi.CASTBAR_ID, UiMode.OFF, "Waits for the layout first")))
        assertEquals(TB.OFF, GameUiTiles.badge(row(GameUi.TARGET_ID, UiMode.OFF, "Hidden: the target bar replaces it")))
        // the picture: dimmed for Off and for a tile the game has not reported; the choice decides it at once (a sent On is bright)
        assertTrue(GameUiTiles.dimmed(null))
        assertTrue(GameUiTiles.dimmed(row("chat", UiMode.ON, "", known = false)))
        assertTrue(GameUiTiles.dimmed(row(GameUi.WM_ID, UiMode.OFF, "Off")))
        assertTrue(!GameUiTiles.dimmed(row(GameUi.NPF_ID, UiMode.ON, "Sent")))
        assertTrue(!GameUiTiles.dimmed(row("minimap", UiMode.DYNAMIC, "Hidden now")))
        // hub61: the game's own option off - its own sign (the kit's alert_gear), never WAITING's dots; the picture dimmed, the choice kept
        assertEquals(TB.GAME_OFF, GameUiTiles.badge(row(GameUi.DMG_ID, UiMode.DYNAMIC, GameUi.GAME_OFF_NOTE)))
        assertEquals(TB.GAME_OFF, GameUiTiles.badge(row(GameUi.CDM_ID, UiMode.ON, GameUi.GAME_OFF_NOTE)))
        assertTrue(GameUiTiles.dimmed(row(GameUi.CDM_ID, UiMode.ON, GameUi.GAME_OFF_NOTE)))
        assertEquals(TB.DYNAMIC, GameUiTiles.badge(row(GameUi.DMG_ID, UiMode.DYNAMIC, "Hidden now")))
        assertEquals("a press held for the fight", TB.SENT, GameUiTiles.badge(row(GameUi.CDM_ID, UiMode.OFF, "Changes when combat ends")))
        assertEquals(TB.WAITING, GameUiTiles.badge(row(GameUi.DMG_ID, UiMode.ON, "The game does not offer this yet")))
    }

    // ---------------------------------------------------------------- hub61: the meters' one tap

    private fun mtDoc(d: String = "on", c: Boolean = true, p: Int = 15) = GameUi.mtFrom(org.json.JSONObject()
        .put("v", "mt1").put("d", d).put("c", c).put("p", p).put("df", true).put("cf", true).put("dok", true).put("n", 4)
        .put("cols", org.json.JSONObject().put("DMG_OFF", 0).put("DMG_ON", 1).put("DMG_DYN", 2).put("CDM_OFF", 3).put("CDM_ON", 4).put("PARTS", 8)))!!

    /** only a choice MADE here is sent, the first that differs (damage meter, the tile, the parts' mask), on the add-on's own columns */
    @Test fun theMetersTapOnlyWhatWasChosen() {
        val m = mtDoc()
        assertNull("nothing chosen: nothing sent (the game's own stands)", GameUi.mtCol(m, null, null, null))
        assertNull("chosen as it is", GameUi.mtCol(m, UiMode.ON, true, 15))
        assertEquals(0, GameUi.mtCol(m, UiMode.OFF, null, null))
        assertEquals(2, GameUi.mtCol(m, UiMode.DYNAMIC, null, null))
        assertEquals(1, GameUi.mtCol(mtDoc(d = "dyn"), UiMode.ON, null, null))
        assertEquals(3, GameUi.mtCol(m, null, false, null))
        assertEquals(4, GameUi.mtCol(mtDoc(c = false), null, true, null))
        assertEquals("the mask on 8 + mask", 8 + 0b0101, GameUi.mtCol(m, null, null, 0b0101))
        assertEquals("the damage meter first", 0, GameUi.mtCol(m, UiMode.OFF, false, 0))
        // an add-on set without the columns: nothing
        val bare = GameUi.mtFrom(org.json.JSONObject().put("d", "on"))!!
        assertNull(GameUi.mtCol(bare, UiMode.OFF, false, 1))
        // the document read: the game's options (absent = not read), the frames, a press held for the fight
        val g = GameUi.mtFrom(org.json.JSONObject().put("d", "off").put("dset", true).put("dok", false).put("q", true).put("p", 31))!!
        assertEquals(false, g.dok); assertNull(g.dav); assertTrue(g.q); assertTrue(g.dset); assertEquals(15, g.p)
        assertNull(GameUi.mtFrom(null))
    }

    // ---------------------------------------------------------------- the grid

    @Test fun fiveColumnsOnTheRoomsPage() {
        assertEquals(5, GameUiTiles.columns(1240f))
        assertEquals(3, GameUiTiles.columns(770f))                     // a page beside the panel (never on this tab; it still lays out)
        assertEquals(1, GameUiTiles.columns(100f))
        val g = GameUiTiles.grid(10, 1240f, -1, 0f)
        assertNull(g.band)
        for (b in g.tiles) { assertEquals(232f, b.w, 0.01f); assertEquals(184f, b.h, 0.01f) }
        assertEquals(16f, g.tiles[0].l, 0.01f); assertEquals(1224f, g.tiles[4].r, 0.01f)
        assertEquals(g.tiles[0].r + 12f, g.tiles[1].l, 0.01f)
        assertEquals(g.tiles[0].b + 12f, g.tiles[5].t, 0.01f)
        assertEquals(GameUiTiles.TOP + 2 * 184f + 12f + GameUiTiles.BOTTOM, g.height, 0.01f)
        // the well and the badge inside the tile
        val w = GameUiTiles.well(g.tiles[0])
        assertEquals(216f, w.w, 0.01f); assertEquals(108f, w.h, 0.01f)
        val b = GameUiTiles.badgeBox(g.tiles[0])
        assertEquals(36f, b.w, 0.01f)
        assertTrue(b.l >= w.l && b.r <= w.r && b.t >= w.t && b.b <= w.b)
        assertEquals(0f, GameUiTiles.grid(0, 1240f, -1, 0f).height, 0.01f)
    }

    /** the band goes under the open tile's row; the rows after it move down by the band, the rows before it never move */
    @Test fun theBandOpensUnderItsRow() {
        val closed = GameUiTiles.grid(10, 1240f, -1, 0f)
        for (open in 0 until 10) {
            val g = GameUiTiles.grid(10, 1240f, open, 300f)
            val band = g.band!!
            val row = open / 5
            assertEquals(closed.tiles[row * 5].b + 12f, band.t, 0.01f)
            assertEquals(300f, band.h, 0.01f)
            assertEquals(16f, band.l, 0.01f); assertEquals(1224f, band.r, 0.01f)
            for (i in 0 until 10) {
                val shift = if (i / 5 > row) 312f else 0f
                assertEquals("$open/$i", closed.tiles[i].t + shift, g.tiles[i].t, 0.01f)
                assertEquals(closed.tiles[i].l, g.tiles[i].l, 0.01f)
            }
            assertEquals(closed.height + 312f, g.height, 0.01f)
        }
    }

    // ---------------------------------------------------------------- the band

    private fun lines(vararg l: Pair<Int, Boolean>) = l.mapIndexed { i, (n, lab) -> GameUiTiles.Line("l$i", n, lab) }

    @Test fun bandHeights() {
        val w = 1208f
        // On / Off: the picture's height, the status beside it
        val onOff = GameUiTiles.band(16f, 0f, w, lines(2 to false))
        assertEquals(252f, onOff.height, 0.01f)
        assertEquals(438f, onOff.picture.w, 0.01f); assertEquals(220f, onOff.picture.h, 0.01f)
        assertTrue(onOff.status.l >= onOff.picture.r && onOff.status.b <= onOff.picture.b)
        // the minimap's four modes: one line, the same height
        assertEquals(252f, GameUiTiles.band(16f, 0f, w, lines(4 to false)).height, 0.01f)
        // Enemy nameplates + Aggro tint, Compass bar + Follows the camera: two lines beside the picture, the status under
        assertEquals(312f, GameUiTiles.band(16f, 0f, w, lines(2 to false, 2 to true)).height, 0.01f)
        // the Target bar + Health text + Target of target: the third line under the picture, the status beside it (the design's ~380)
        val tb = GameUiTiles.band(16f, 0f, w, lines(2 to false, 3 to true, 2 to true))
        assertEquals(382f, tb.height, 0.01f)
        // the Resource bar + Pet bar + Height / Width / Scale (the design's longest)
        val rb = GameUiTiles.band(16f, 0f, w, lines(2 to false, 2 to true, 3 to true, 3 to true, 3 to true))
        assertEquals(502f, rb.height, 0.01f)
    }

    /** every band: its buttons at the touch floor, inside the band, never over the picture, each other or the status line */
    @Test fun bandBoxesNeverOverlap() {
        val shapes = listOf(listOf(2 to false), listOf(4 to false), listOf(2 to false, 2 to true), listOf(2 to false, 3 to true, 2 to true),
            listOf(2 to false, 2 to true, 3 to true, 3 to true, 3 to true))
        for (width in listOf(1208f, 738f)) for (sh in shapes) {
            val top = 500f
            val b = GameUiTiles.band(16f, top, width, lines(*sh.toTypedArray()))
            val band = GameUiTiles.Box(16f, top, 16f + width, top + b.height)
            assertEquals(sh.size, b.lines.size)
            val all = ArrayList<GameUiTiles.Box>()
            for ((i, lb) in b.lines.withIndex()) {
                assertEquals(sh[i].first, lb.buttons.size)
                assertEquals(sh[i].second, lb.label != null)
                for (x in lb.buttons) {
                    assertTrue("touch floor", x.h >= 110f)
                    assertTrue("wide enough: ${x.w}", x.w >= (if (width > 1000f) 120f else 90f))
                    all += x
                }
                lb.label?.let { all += it }
            }
            all += b.status
            all += b.picture
            for (x in all) assertTrue("$width $sh: $x inside $band", x.l >= band.l && x.r <= band.r && x.t >= band.t && x.b <= band.b)
            for (i in all.indices) for (j in i + 1 until all.size) {
                val p = all[i]; val q = all[j]
                assertTrue("$width $sh: $p over $q", p.r <= q.l || q.r <= p.l || p.b <= q.t || q.b <= p.t)
            }
        }
    }

    /** the longest band fits on one screen WITH its tile (the room scrolls both into view): 777 px under the tab row */
    @Test fun everyBandFitsWithItsTile() {
        val view = 1080f - 145f - 158f
        val rb = GameUiTiles.band(16f, 0f, 1208f, lines(2 to false, 2 to true, 3 to true, 3 to true, 3 to true))
        val g = GameUiTiles.grid(1, 1240f, 0, rb.height)
        val span = g.band!!.b - g.tiles[0].t
        assertTrue("tile + band = $span", span + 24f + 16f <= view)
    }

    /** every word a tile or its band shows has its Spanish and French (the menus' words; the tiles fit them: shrink, then cut) */
    @Test fun everyTileWordIsTranslated() {
        val words = GameUiTiles.NAMES.values + GameUiTiles.EXTRA_NAMES.values +
            listOf("On", "Off", "Dynamic", "Tracking", "Nintendo", "Lower", "Default", "Higher", "Narrower", "Wider", "Smaller", "Larger",
                "Percent", "Total", "Waiting for the game", "Sent", "Hidden now", "On the top screen now",
                // hub61 (scratchpad i18n/topmeters.json until the integrator merges it into es / fr)
                "Open", GameUi.GAME_OFF_NOTE, "Changes when combat ends", "The game does not offer this yet")
        for (l in listOf("es", "fr")) {
            val f = listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
                .first { it.canRead() }
            val plain = org.json.JSONObject(f.readText()).getJSONObject("plain")
            for (w in words.toSet()) assertTrue("$l: $w", plain.has(w) && plain.getString(w).isNotBlank())
        }
    }

    /** the room brings an opened tile and its band into view with the least scroll; the tile first when both do not fit */
    @Test fun revealScrollsTheLeast() {
        assertNull("in view", GameUiTiles.reveal(0f, 777f, 100f, 700f))
        assertEquals("below: up to its foot", 900f - 777f, GameUiTiles.reveal(0f, 777f, 300f, 900f)!!, 0.01f)
        assertEquals("above: down to its top", 200f, GameUiTiles.reveal(500f, 777f, 200f, 600f)!!, 0.01f)
        assertEquals("too tall: the tile at the top", 300f, GameUiTiles.reveal(0f, 777f, 300f, 1200f)!!, 0.01f)
        assertNull(GameUiTiles.reveal(300f, 777f, 300f, 1200f))
    }

    // ---------------------------------------------------------------- the TGA reader

    private fun media(name: String): ByteArray = listOf(File("../companion/app/src/main/assets/companion/addons/WowCompanion/Media/$name.tga"),
        File("companion/app/src/main/assets/companion/addons/WowCompanion/Media/$name.tga")).first { it.canRead() }.readBytes()

    @Test fun ourMediaFilesRead() {
        for ((name, wh) in mapOf("hud_rim_8" to (128 to 256), "compass_band" to (512 to 64), "marker_ring" to (256 to 256),
                "quest_disc" to (64 to 64), "tint_glow" to (256 to 64), "cursor_blank" to (32 to 32))) {
            val px = Tga.pixels(media(name))!!
            assertEquals(name, wh, px.first to px.second)
            assertEquals(px.first * px.second, px.third.size)
        }
        // the rim: its top lip on row 0 (the bottom-up file turned the right way), the canvas's empty rows 19-31 at the bottom
        val rim = Tga.pixels(media("hud_rim_8"))!!
        fun a(x: Int, y: Int) = (rim.third[y * rim.first + x] ushr 24) and 0xFF
        assertTrue(a(40, 1) > 100)
        assertTrue(a(40, 250) < 20)
    }

    @Test fun theReaderRefusesWhatItDoesNotKnow() {
        val ok = media("quest_disc")
        assertNull(Tga.pixels(ByteArray(10)))
        assertNull("RLE", Tga.pixels(ok.copyOf().also { it[2] = 10 }))
        assertNull("a colour map", Tga.pixels(ok.copyOf().also { it[1] = 1 }))
        assertNull("24-bit", Tga.pixels(ok.copyOf().also { it[16] = 24 }))
        assertNull("short", Tga.pixels(ok.copyOf(100)))
        // a top-left origin file (descriptor bit 5): rows as stored
        val two = byteArrayOf(0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 2, 0, 32, 0x28,
            1, 2, 3, 4, 5, 6, 7, 8)
        val top = Tga.pixels(two)!!
        assertEquals(0x04030201, top.third[0]); assertEquals(0x08070605, top.third[1])
        two[17] = 0x08
        val bottom = Tga.pixels(two)!!
        assertEquals(0x08070605, bottom.third[0]); assertEquals(0x04030201, bottom.third[1])
    }
}
