package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SETTINGS > GENERAL AS PICTURE TILES, the pure half (the user, 2026-11-17: "I REALLY REALLY love what you did with the top screen
 * settings menu with the pictures and want to do something similar to the other menus"; the beautify design, section 2): the Look row's
 * slots (Swap screens only with a second screen), the wide Theme tile's geometry (two columns and the gap; never started in the last
 * column), the band under a row, the compact rows of each group and their controls' boxes (right-aligned in the right 58 %).
 */
private typealias Row = GeneralTiles.Row
private typealias Slot = GeneralTiles.Slot

class GeneralTilesTest {

    @Test fun lookRowSlots() {
        assertEquals(listOf(Slot(GeneralTiles.THEME, true), Slot(GeneralTiles.SWAP), Slot(GeneralTiles.VOICE)), GeneralTiles.slots(true))
        // a single-screen device: no Swap screens slot (a slot that does not apply is not drawn)
        assertEquals(listOf(GeneralTiles.THEME, GeneralTiles.VOICE), GeneralTiles.slots(false).map { it.id })
        for (s in GeneralTiles.slots(true)) assertTrue(s.id, !GeneralTiles.NAMES[s.id].isNullOrBlank())
    }

    @Test fun wideThemeTileSpansTwoColumns() {
        val g = GeneralTiles.grid(GeneralTiles.slots(true), 1240f, -1, 0f)
        assertEquals(3, g.tiles.size)
        val (theme, swap, voice) = g.tiles
        // Top screen's numbers: 232 x 184 tiles, 12 apart, 16 in from the page's edge; the wide one 2 x 232 + 12
        assertEquals(16f, theme.l, 0.01f); assertEquals(476f, theme.w, 0.01f); assertEquals(GameUiTiles.TILE_H, theme.h, 0.01f)
        assertEquals(theme.r + GameUiTiles.GAP, swap.l, 0.01f); assertEquals(232f, swap.w, 0.01f)
        assertEquals(swap.r + GameUiTiles.GAP, voice.l, 0.01f)
        assertTrue(listOf(theme, swap, voice).all { it.t == GameUiTiles.TOP })
        assertNull(g.band)
        assertEquals(GameUiTiles.TOP + GameUiTiles.TILE_H + GameUiTiles.BOTTOM, g.height, 0.01f)
        // the well of the wide tile: its two columns less the padding (460 x 108)
        val well = GameUiTiles.well(theme)
        assertEquals(460f, well.w, 0.01f); assertEquals(GameUiTiles.WELL_H, well.h, 0.01f)
    }

    @Test fun aWideTileNeverStartsInTheLastColumn() {
        val slots = listOf(Slot("a"), Slot("b"), Slot("c"), Slot("d"), Slot("w", true), Slot("e"))
        val g = GeneralTiles.grid(slots, 1240f, -1, 0f)
        // four singles fill columns 1-4; the wide one would start in column 5: it starts the next row, and the single after it follows
        assertEquals(g.tiles[0].t, g.tiles[3].t, 0.01f)
        assertEquals(16f, g.tiles[4].l, 0.01f)
        assertTrue(g.tiles[4].t > g.tiles[3].t)
        assertEquals(g.tiles[4].r + GameUiTiles.GAP, g.tiles[5].l, 0.01f)
        assertEquals(g.tiles[4].t, g.tiles[5].t, 0.01f)
        assertEquals(GameUiTiles.TOP + 2 * GameUiTiles.TILE_H + GameUiTiles.GAP + GameUiTiles.BOTTOM, g.height, 0.01f)
    }

    @Test fun theBandOpensUnderItsRow() {
        val g = GeneralTiles.grid(GeneralTiles.slots(true), 1240f, 1, 300f)
        val band = g.band!!
        assertEquals(GameUiTiles.TOP + GameUiTiles.TILE_H + GameUiTiles.GAP, band.t, 0.01f)
        assertEquals(300f, band.h, 0.01f)
        assertEquals(16f, band.l, 0.01f); assertEquals(1224f, band.r, 0.01f)
        assertEquals(GameUiTiles.TOP + GameUiTiles.TILE_H + 300f + GameUiTiles.GAP + GameUiTiles.BOTTOM, g.height, 0.01f)
        // the band's own lines (Top screen's band): the choices beside the picture at the 110 px touch floor
        val b = GameUiTiles.band(band.l, band.t, band.w, listOf(GameUiTiles.Line("swap", 2, false), GameUiTiles.Line("restart", 1, true)))
        assertEquals(2, b.lines.size)
        assertTrue(b.lines.all { l -> l.buttons.all { it.h >= GameUiTiles.LINE_H } })
    }

    @Test fun compactRowsPerGroup() {
        assertEquals(listOf(Row.LANGUAGE), GeneralTiles.rows(SettingsTabs.Group.LOOK, true, false))
        assertEquals(listOf(Row.LOGIN, Row.GAME_ACCOUNT, Row.WINDOWS_ID), GeneralTiles.rows(SettingsTabs.Group.ACCOUNT, true, false))
        assertEquals(listOf(Row.GAME_ACCOUNT, Row.WINDOWS_ID, Row.WINDOWS_RESTART), GeneralTiles.rows(SettingsTabs.Group.ACCOUNT, false, true))
        assertEquals(listOf(Row.REPORT, Row.RELOAD, Row.STATUS), GeneralTiles.rows(SettingsTabs.Group.HELP, true, false))
        assertTrue(GeneralTiles.rows(SettingsTabs.Group.HUD, true, true).isEmpty())
        // every General row word of the six-tab data is a compact row or a tile (Theme, Swap screens, Voice typing; Restart DS Forever
        // is Swap screens' band line; Companion's status is the Status row)
        val words = SettingsTabs.Block.values().filter { it.tab == SettingsTabs.Tab.GENERAL }.flatMap { it.rows }.toSet()
        val drawn = Row.values().map { it.label }.toSet() + GeneralTiles.NAMES.values + "Restart DS Forever"
        for (w in words) assertTrue(w, w in drawn)
    }

    @Test fun compactRowControlsSitRight() {
        val row = GeneralTiles.rowBox(200f, 1240f)
        assertEquals(GeneralTiles.COMPACT_H, row.h, 0.01f)
        val room = GeneralTiles.controlRoom(row)
        assertEquals(row.r - 16f, room.r, 0.01f)
        assertEquals(row.w * GeneralTiles.CONTROL_SHARE, row.r - room.l, 0.01f)
        val segs = GeneralTiles.segments(room, 4, 176f)
        assertEquals(room.r, segs.last().r, 0.01f)
        assertTrue(segs.first().l >= room.l)
        assertTrue(segs.all { it.h == GeneralTiles.CONTROL_H && it.cy == row.cy })
        // more than the room: they share it
        val wide = GeneralTiles.segments(room, 4, 400f)
        assertEquals(room.l, wide.first().l, 0.01f)
        val b = GeneralTiles.button(room)
        assertEquals(room.r, b.r, 0.01f); assertEquals(GeneralTiles.BUTTON_W, b.w, 0.01f)
    }

    @Test fun premadesWhileNoMakerPage() {
        val lines = GeneralTiles.presetLines(ThemePresets.ALL.map { it.id })
        // an even grid of swatches (2026-11-17 review: no ragged 4 / 3 / 3), in the presets' order
        assertEquals(listOf(listOf("classic", "dark_gold", "dark_silver", "dark_rose", "oled_bronze"),
            listOf("oled_gold", "oled_silver", "oled_rose", "oled_rose_gold", "oled_frost")), lines)
        assertEquals(ThemePresets.ALL.map { it.id }.toSet(), lines.flatten().toSet()); assertEquals(ThemePresets.ALL.size, lines.flatten().size)
        // as few lines as hold them at five, the same count on each (the last may hold fewer)
        fun sizes(n: Int) = GeneralTiles.presetLines((1..n).map { "t$it" }).map { it.size }
        assertEquals(listOf(4, 3), sizes(7)); assertEquals(listOf(5), sizes(5)); assertEquals(listOf(4, 4, 3), sizes(11))
        assertEquals(emptyList<Int>(), sizes(0))
    }
}
