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

    /** Quests q49: a party unit's menu (which = PARTY, src = party): Set Focus on the party row, Whisper's app step, Inspect greyed */
    private val party = JSONObject("""{"type":"pmenu","unit":"party2","which":"PARTY","nm":"Brannoc","src":"party","items":[
        {"t":"Brannoc","ttl":1},
        {"t":"Set Focus","k":"SetFocus","sc":14,"scr":"party","en":1},
        {"t":"Whisper","k":"Whisper","i":5,"ap":"whisper","en":1},
        {"t":"Inspect","k":"Inspect","i":6,"en":1},
        {"t":"Interact","k":"Interact","i":7,"en":1},
        {"t":"Add Friend","en":1,"sub":[{"t":"Character","k":"AddFriend","i":9,"en":1}]}]}""")

    private fun partyMenu() = PlayerMenu(greyKeys = PlayerMenu.PARTY_GREY).apply { openReading(); onDoc(party) }

    @Test fun aPartyUnitsEntries() {
        val m = partyMenu()
        assertEquals("Brannoc", m.heading())
        assertEquals(listOf("Brannoc", "Set Focus", "Whisper", "Inspect", "Interact", "Add Friend"), m.rows().map { it.text })
        // Set Focus: the PARTY row's column (Do.Secure carries the row)
        assertEquals(PlayerMenu.Do.Secure(14, "Set Focus", "party"), m.key("PAD1"))
        // Whisper: run, then the app's step
        val w = partyMenu(); w.key("PADDDOWN")
        assertEquals(PlayerMenu.Do.Run(5, "Whisper", "whisper"), w.key("PAD1"))
        // Inspect greyed (taint), the others run
        val i = partyMenu(); i.key("PADDDOWN"); i.key("PADDDOWN")
        assertFalse(i.rows()[3].enabled)
        assertEquals(PlayerMenu.Do.Refused("Not available now"), i.key("PAD1"))
        i.key("PADDDOWN")
        assertEquals(PlayerMenu.Do.Run(7, "Interact"), i.key("PAD1"))
        // the map's own menu is unchanged: Inspect not greyed there, Focus on pfocus
        val map = PlayerMenu().apply { openReading(); onDoc(party) }
        assertTrue(map.rows()[3].enabled)
        assertEquals("pfocus", PlayerMenu.parse(doc.optJSONArray("items"))[2].secureRow)
        // an app step with nothing to tap
        val only = PlayerMenu().apply { openReading(); onDoc(JSONObject("""{"items":[{"t":"Whisper","ap":"whisper","en":1}]}""")) }
        assertEquals(PlayerMenu.Do.Run(-1, "Whisper", "whisper"), only.key("PAD1"))
    }

    @Test fun nothingRunsWhileTheRunPathIsOff() {
        assertTrue(PlayerMenu.RUNS)
        val m = opened(runs = false)
        m.key("PADDDOWN"); m.key("PADDDOWN")
        assertEquals(PlayerMenu.Do.Refused("Not available yet"), m.key("PAD1"))
        assertTrue(m.open)
    }
}
