package com.jrees.wowcompanion

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** b-group-map: Map.lua's grp read back */
class GroupMembersTest {
    @Test fun membersWithTheGamesOwnColours() {
        val a = JSONArray("""[{"n":"Lyra","c":"PALADIN","col":"fff58cba","wx":-10630,"wy":1040.5,"cont":0},
            {"n":"Thane","c":"MAGE","col":"ff3fc7eb","wx":-10500,"wy":-1260,"cont":0,"dead":true},
            {"n":"Gone","c":"ROGUE","col":"fffff569","wx":-9000,"wy":0,"off":true},
            {"n":"Bad","wx":"x"}]""")
        val g = GroupMembers.parse(a)
        assertEquals(3, g.size)
        assertEquals(0xFFF58CBA.toInt(), g[0].color); assertEquals(0, g[0].cont)
        assertTrue(g[1].dead); assertTrue(g[2].offline); assertEquals(null, g[2].cont)
        assertEquals(0xFFB0B0B0.toInt(), GroupMembers.color("nonsense"))
        assertTrue(GroupMembers.parse(null).isEmpty())
    }
}
