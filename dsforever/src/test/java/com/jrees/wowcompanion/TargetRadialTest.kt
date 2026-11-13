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

    /** Quests q56 (the user, 2026-11-13: "The pet inspect radial should include some of the pet actions like dismiss or feed pet") */
    @Test fun thePetsActionsLeadItsWheelWithTheirOwnIcons() {
        val doc = org.json.JSONArray(
            """[{"t":"Feed Pet","k":"PetFeed","sc":5,"en":1,"ic":132165},{"t":"Dismiss Pet","k":"PetDismiss","sc":6,"en":1,"ic":136095},""" +
            """{"t":"Mend Pet","k":"PetMend","sc":8,"en":1,"ic":132179},{"t":"Beast Training","k":"PetTrain","sc":9,"en":0,"ic":132162},""" +
            """{"t":"Set Focus","k":"SetFocus","sc":3,"en":1}]""")
        val items = PlayerMenu.parse(doc)
        assertEquals(listOf(132165, 136095, 132179, 132162, null), items.map { it.icon })
        assertEquals(listOf(5, 6, 8, 9, 3), items.map { it.secure })
        assertEquals("pfocus", items[0].secureRow)
        val root = TargetRadial.slices(items)!!
        assertEquals(listOf("Feed Pet", "Dismiss Pet", "Mend Pet", "Beast Training", "Set Focus", null, null, null), labels(root))
        assertEquals("icon:132165", root[0]!!.menu)
        assertEquals("setfocus", root[4]!!.menu)
        assertEquals(false, root[3]!!.enabled)
        assertTrue(MenuIcons.has("icon:132165"))
        assertEquals(132165, MenuIcons.spec("icon:132165")!!.base)
        assertTrue(!MenuIcons.has("icon:0") && !MenuIcons.has("icon:x") && !MenuIcons.has(null) && MenuIcons.has("whisper"))
        // a pet key is no game menu icon of ours (no mix-up with Dismiss / Trade / Follow)
        for (k in listOf("PetFeed", "PetDismiss", "PetRevive", "PetMend", "PetTrain")) assertNull(MenuIcons.keyOf(k, k))
    }
}
