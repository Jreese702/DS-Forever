package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * GitHub #550 (with #564, #570, #249, #260): the top screen's switches waited forever below 1280x720 or with a lowered UI scale. The pads
 * are the add-on's own describe() (TapPad pad26), dumped by tools/test_pad_order.py into src/test/resources/pad-fixtures (the harness
 * fails when one is stale): at every layout the switches' rows are placed, and a layout that cannot place some row says so ONCE.
 */
class PadFitTest {
    private fun pad(name: String): JSONObject {
        val s = javaClass.classLoader!!.getResourceAsStream("pad-fixtures/$name.json")!!.bufferedReader().readText()
        return JSONObject(s)
    }

    @Before fun fresh() { PadFit.reset() }

    private fun switchesPlaced(p: JSONObject) {
        val rows = p.getJSONObject("rows")
        assertEquals(0, rows.getInt("uisw"))
        assertEquals(1, rows.getInt("ui"))
        assertFalse(PadFit.missing(p, "uisw"))
        assertFalse(PadFit.missing(p, "ui"))
    }

    @Test fun at960x540TheSwitchesArePlacedAndTheNoteFiresOnce() {
        val p = pad("pad_960x540")
        switchesPlaced(p)
        assertEquals(16, PadFit.dropped(p).size)
        assertEquals("talents", PadFit.dropped(p).first())                 // by row: the least used go first
        assertEquals("bmv", PadFit.dropped(p).last())
        assertEquals(PadFit.NOTE_RES, PadFit.once(p))
        assertNull("once per layout", PadFit.once(p))
        assertNull(PadFit.once(JSONObject(p.toString())))                  // the next document of the same layout
        assertTrue(PadFit.missing(p, "gf"))                                // a dropped row: a tap can never land
        assertFalse("a row the add-on never registered is not 'dropped'", PadFit.missing(p, "nosuchrow"))
    }

    @Test fun atTheSmallest960x540ScaleTheSwitchesStillArePlaced() {
        val p = pad("pad_960x540_min")
        switchesPlaced(p)
        assertEquals(33, PadFit.dropped(p).size)
        assertEquals(PadFit.NOTE_RES, PadFit.once(p))
    }

    @Test fun at1280x720WithALowUiScaleTheNoteSaysTheScale() {
        val p = pad("pad_1280x720_low")
        switchesPlaced(p)
        assertEquals(listOf("prof", "profsel", "profopen", "gf", "gfl", "gfw", "gfclose", "keytest", "bmv"), PadFit.dropped(p))
        assertEquals(PadFit.NOTE_SCALE, PadFit.once(p))
        assertNull(PadFit.once(p))
        // another layout (the 960x540 one) is a new note
        assertEquals(PadFit.NOTE_RES, PadFit.once(pad("pad_960x540")))
    }

    @Test fun at1920x1080NothingIsDroppedAndNoNote() {
        val p = pad("pad_1920x1080")
        switchesPlaced(p)
        assertEquals(56, p.getJSONObject("rows").length())                 // all 56 rows; the shared row once (its tenant, mac)
        assertTrue(PadFit.dropped(p).isEmpty())
        assertNull(PadFit.note(p))
        assertNull(PadFit.once(p))
        val min = pad("pad_1920x1080_min")
        switchesPlaced(min)
        assertEquals(listOf("keytest", "bmv"), PadFit.dropped(min))
        assertEquals(PadFit.NOTE_SCALE, PadFit.once(min))
    }

    @Test fun anOlderPadOrNoneSaysNothing() {
        val old = JSONObject(pad("pad_960x540").toString()).apply { remove("drop") }
        assertTrue(PadFit.dropped(old).isEmpty())
        assertNull(PadFit.once(old))
        assertFalse(PadFit.missing(old, "uisw"))
        assertNull(PadFit.once(null))
        assertFalse(PadFit.missing(null, "uisw"))
    }

    @Test fun aSwitchTileWithItsRowOffTheScreenShowsTheBangNotTheHourglass() {
        val r = GameUi.Row("worldmarkers", "World markers", null, UiMode.ON, true, true, PadFit.NO_ROW_NOTE)
        assertEquals(GameUiTiles.Badge.REFUSED, GameUiTiles.badge(r))
        val hud = GameUi.Row("hud", "Controller UI", null, UiMode.ON, true, false, PadFit.NO_ROW_NOTE)
        assertEquals(GameUiTiles.Badge.REFUSED, GameUiTiles.badge(hud))
        val sent = GameUi.Row("worldmarkers", "World markers", null, UiMode.ON, true, true, "Sent")
        assertEquals(GameUiTiles.Badge.SENT, GameUiTiles.badge(sent))
    }
}
