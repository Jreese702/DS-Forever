package com.jrees.wowcompanion

import com.jrees.wowcompanion.SocialModel.Act
import com.jrees.wowcompanion.SocialModel.Tab
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Social room's Leave Group (a player's report on 1.0.39: "I cannot leave the party through the Social menu"), off-device:
 * the player's own line in the Party / Raid list offers Leave Group in its More menu, confirmed first ("Leave the group?"), and
 * sends ONE `sact` tap at the add-on's "leave" column (Social so15: the game's own leave-party call). No pick is needed (the room's
 * own action, as Convert to Raid). Out of a group, or with an add-on before so15 (no "leave" column), there is none.
 */
class SocialLeaveGroupTest {

    private fun model(kind: String, leaveCol: Int? = 39): SocialModel {
        val m = SocialModel()
        val codes = JSONObject().put("toraid", 21).put("uninvite", 1)
        if (leaveCol != null) codes.put("leave", leaveCol)
        m.apply(JSONObject().put("type", "social").put("k", "me").put("v", "so15")
            .put("s", JSONObject().put("view", "group").put("rows", true).put("n", "Me").put("lead", false).put("codes", codes)), 0L)
        val list = JSONArray()
        if (kind != "none") {
            list.put(JSONObject().put("n", "Me").put("me", true).put("on", true).put("slot", 0))
            list.put(JSONObject().put("n", "Other").put("on", true).put("slot", 1).put("rank", 2))
        }
        m.apply(JSONObject().put("type", "social").put("k", "group").put("v", "so15")
            .put("s", JSONObject().put("kind", kind).put("list", list)), 0L)
        return m
    }

    private fun meRow(m: SocialModel) = m.rows(Tab.RAID).first { it.me }

    @Test fun leaveIsAConfirmedRoomAction() {
        assertTrue(Act.LEAVE.confirm)
        assertTrue(Act.LEAVE.onRoom)
        assertEquals("leave", Act.LEAVE.code)
        assertEquals("Leave Group", Act.LEAVE.word)
    }

    @Test fun inAPartyMyOwnLineOffersLeaveGroup() {
        val m = model("party")
        assertEquals(listOf(Act.LEAVE), m.actions(meRow(m)))
        assertEquals(listOf(Act.LEAVE), m.menu(meRow(m)))
    }

    @Test fun inARaidToo() {
        val m = model("raid")
        assertEquals(listOf(Act.LEAVE), m.menu(meRow(m)))
    }

    @Test fun anotherMembersLineHasNoLeave() {
        val m = model("party")
        val other = m.rows(Tab.RAID).first { !it.me }
        assertFalse(Act.LEAVE in m.actions(other))
        assertFalse(Act.LEAVE in m.menu(other))
    }

    @Test fun theTapIsOneSactAtTheLeaveColumnWithNoPick() {
        val m = model("party")
        assertEquals(SocialModel.Tap("sact", 39), m.actionTap(meRow(m), Act.LEAVE, 1_000L))
        assertEquals(SocialModel.Tap("sact", 39), m.actionTap(null, Act.LEAVE, 1_000L))
    }

    @Test fun anOlderAddonWithoutTheColumnOffersNone() {
        val m = model("party", leaveCol = null)
        assertTrue(m.menu(meRow(m)).isEmpty())
        assertNull(m.actionTap(null, Act.LEAVE, 1_000L))
    }

    @Test fun notInAGroupNoLeave() {
        val m = model("none")
        assertTrue(m.rows(Tab.RAID).isEmpty())
    }
}
