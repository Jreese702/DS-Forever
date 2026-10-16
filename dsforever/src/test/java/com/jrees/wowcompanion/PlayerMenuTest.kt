package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** the game's player menu on the bottom screen (Quests q41's {"type":"pmenu"}), driven by the controller */
class PlayerMenuTest {
    private val doc = JSONObject("""{"type":"pmenu","shape":"GetEntries","items":[
        {"t":"Remy","ttl":1},
        {"t":"Raid Target Icon","en":1,"sub":[{"t":"Star","i":0,"en":1,"ck":0},{"t":"Circle","i":1,"en":1,"ck":1}]},
        {"t":"Set Focus","k":"SetFocus","sc":0,"en":1},
        {"t":"Reset all instances","k":"ResetInstances","i":3,"en":1,"cf":"Reset all instances?"},
        {"t":"Copy Name","k":"CopyName","en":0}]}""")

    private fun opened(runs: Boolean = true) = PlayerMenu(runs).apply { openReading(); onDoc(doc) }

    @Test fun opensOnTheFirstEntryNotTheTitle() {
        val m = opened()
        assertFalse(m.loading)
        assertEquals("Remy", m.heading())
        assertEquals(1, m.sel)
        assertTrue(m.rows()[0].title)
        assertEquals(true, m.rows()[1].sub!![1].checked)
    }

    @Test fun aOpensASubMenuAndRunsAnEntryThenCloses() {
        val m = opened()
        assertEquals(PlayerMenu.Do.None, m.key("PAD1"))
        assertEquals(1, m.depth); assertEquals("Raid Target Icon", m.heading())
        m.key("PADDDOWN")
        assertEquals(PlayerMenu.Do.Run(1, "Circle"), m.key("PAD1"))
        assertFalse(m.open)
    }

    @Test fun bGoesBackALevelThenCloses() {
        val m = opened()
        m.key("PAD1")
        assertEquals(PlayerMenu.Do.None, m.key("PAD2")); assertEquals(0, m.depth); assertTrue(m.open)
        assertEquals(PlayerMenu.Do.Close, m.key("PAD2")); assertFalse(m.open)
    }

    @Test fun focusGoesToTheSecureCellAndAnUnknownEntryIsGrey() {
        val m = opened()
        m.key("PADDDOWN")
        assertEquals(PlayerMenu.Do.Secure(0, "Set Focus"), m.key("PAD1"))
        val n = opened()
        repeat(3) { n.key("PADDDOWN") }
        assertEquals(PlayerMenu.Do.Refused("Not available now"), n.key("PAD1"))
        n.key("PADDUP"); n.key("PADDUP"); n.key("PADDUP"); n.key("PADDUP")
        assertEquals(1, n.sel)                                       // the title row is no stop
    }

    @Test fun resetAsksFirstOnTheBottomScreenNoFirst() {
        val m = opened()
        m.key("PADDDOWN"); m.key("PADDDOWN")
        assertEquals(PlayerMenu.Do.None, m.key("PAD1"))
        assertEquals("Reset all instances?", m.heading())
        assertEquals(listOf("Yes", "No"), m.rows().map { it.text })
        assertEquals(1, m.sel)                                       // the cursor starts on No
        assertEquals(PlayerMenu.Do.None, m.key("PAD1")); assertEquals(null, m.rows().firstOrNull { it.text == "Yes" })   // No: back to the menu
        m.key("PAD1"); m.key("PADDUP")
        assertEquals(PlayerMenu.Do.Run(3, "Reset all instances"), m.key("PAD1"))
        assertFalse(m.open)
        val b = opened(); b.key("PADDDOWN"); b.key("PADDDOWN"); b.key("PAD1")
        assertEquals(PlayerMenu.Do.None, b.key("PAD2")); assertTrue(b.open); assertEquals("Remy", b.heading())   // B: no reset
    }

    @Test fun anEntryGoesBackAsTwoDigitTaps() {
        assertEquals(listOf(33, 30), PlayerMenu.digitCols(3))
        assertEquals(listOf(32, 29), PlayerMenu.digitCols(12))     // 1 * 8 + 4
        assertEquals(listOf(listOf("qact" to 33, "qact" to 30)), listOf(GuestPad.playerMenuRun(JSONObject("""{"rows":{"qact":{}}}"""), 3)))
    }

    @Test fun anUnreadableMenuSaysSo() {
        val m = PlayerMenu().apply { openReading(); onDoc(JSONObject("""{"type":"pmenu","shape":"none","items":[]}""")) }
        assertTrue(m.empty)
        assertEquals(PlayerMenu.Do.None, m.key("PAD1"))
    }

    @Test fun nothingRunsWhileTheRunPathIsOff() {
        assertTrue(PlayerMenu.RUNS)
        val m = opened(runs = false)
        m.key("PADDDOWN"); m.key("PADDDOWN")
        assertEquals(PlayerMenu.Do.Refused("Not available yet"), m.key("PAD1"))
        assertTrue(m.open)
    }
}
