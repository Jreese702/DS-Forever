package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** THE CONTEXT RADIAL'S PAGES (the user, 2026-11-07: "no more drop down menus ... including when selecting more make another radial") */
class TargetRadialTest {
    private fun e(t: String, k: String = t.replace(" ", ""), sub: List<PlayerMenu.Entry>? = null, on: Boolean = true) =
        PlayerMenu.Entry(t, if (sub != null) null else 1, on, null, false, sub, key = k)
    private fun labels(s: List<TargetRadial.Slice?>?) = s!!.map { it?.label }

    private val raid = listOf("Star", "Circle", "Diamond", "Triangle", "Moon", "Square", "Cross", "Skull").mapIndexed { i, n -> e(n, "RaidTarget${i + 1}") }

    @Test fun theRootRingKeepsItsPlacesAndMoreTakesTheRest() {
        val items = listOf(PlayerMenu.Entry("Brannoc", null, true, null, true, null), e("Set Focus"), e("Whisper"), e("Inspect"), e("Invite"),
            e("Trade"), e("Follow"), e("Duel"), e("Add Friend"), e("Raid Target Icon", "RaidTarget", raid), e("Report Player"), e("Uninvite"))
        val root = TargetRadial.slices(items)
        assertEquals(listOf("Whisper", "Invite", "Trade", "Inspect", "Follow", "Duel", "Add Friend", "More"), labels(root))
        assertTrue(root!![7]!!.more)
        // More: the rest in the menu's order; the sub-menu opens its own wheel
        val more = TargetRadial.slices(items, TargetRadial.Page(more = true))
        assertEquals(listOf("Set Focus", "Raid Target Icon", "Report Player", "Uninvite", null, null, null, null), labels(more))
        assertEquals(TargetRadial.Page(sub = "Raid Target Icon"), more!![1]!!.next)
        assertEquals(listOf("Star", "Circle", "Diamond", "Triangle", "Moon", "Square", "Cross", "Skull"),
            labels(TargetRadial.slices(items, TargetRadial.Page(sub = "Raid Target Icon"))))
    }

    @Test fun oneLeftOverTakesTheLastSliceItself() {
        val items = listOf(e("Whisper"), e("Invite"), e("Trade"), e("Inspect"), e("Follow"), e("Duel"), e("Add Friend"), e("Set Focus"))
        assertEquals(listOf("Whisper", "Invite", "Trade", "Inspect", "Follow", "Duel", "Add Friend", "Set Focus"), labels(TargetRadial.slices(items)))
        assertNull(TargetRadial.slices(items, TargetRadial.Page(more = true)))         // no More page: gone
    }

    @Test fun aLongMoreChainsMore() {
        val items = (1..20).map { e("Entry $it") }
        val root = TargetRadial.slices(items)!!
        assertEquals("More", root[7]!!.label)
        val p1 = TargetRadial.slices(items, TargetRadial.Page(more = true))!!
        assertEquals("Entry 8", p1[0]!!.label)
        assertEquals("More", p1[7]!!.label)
        assertEquals(TargetRadial.Page(more = true, offset = 7), p1[7]!!.next)
        val p2 = TargetRadial.slices(items, p1[7]!!.next!!)!!
        assertEquals(listOf("Entry 15", "Entry 16", "Entry 17", "Entry 18", "Entry 19", "Entry 20", null, null), labels(p2))
    }

    @Test fun theIconsFollowTheEntries() {
        assertEquals("whisper", MenuIcons.keyOf("Whisper", "Whisper"))
        assertEquals("uninvite", MenuIcons.keyOf("Uninvite", "Uninvite"))
        assertEquals("invite", MenuIcons.keyOf("InviteParty", "Invite"))
        assertEquals("raid8", MenuIcons.keyOf("RaidTarget8", "Skull"))
        assertEquals("raidtarget", MenuIcons.keyOf("RaidTarget", "Raid Target Icon"))
        assertNull(MenuIcons.keyOf("ClearFocus", "Clear Focus"))
        assertEquals("setfocus", MenuIcons.keyOf("SetFocus", "Set Focus"))
    }
}
