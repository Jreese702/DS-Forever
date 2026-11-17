package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * hub57 / PadMap m169 (the user, 2026-11-16: "The polestar previous or next options should be able to be bound to keys in the controller
 * bindings menus"): Hub's two new buttons send barcmd psnext / psprev, which the host takes ([BarCommands.HOST]: HomeBarSelect hands
 * them on); a button carrying the command draws its arrow on the toolbar as any command ([HomeFightFace.commandIcons]). The add-on side
 * is tools/test_polestar_bind.py; the press itself (the one Polestar menu) is the app module's PolestarStepCommandTest.
 */
class PolestarBindTest {
    private fun hubLua(): String = listOf(File("../companion/app/src/main/assets/companion/addons/WowCompanionHub/Hub.lua"),
        File("companion/app/src/main/assets/companion/addons/WowCompanionHub/Hub.lua")).first { it.canRead() }.readText()

    @Test fun theStepKeysAreTheHostsAndEveryHubKeyHasARoute() {
        assertEquals(BarCommands.Route.HOST, BarCommands.route(BarCommands.PS_NEXT))
        assertEquals(BarCommands.Route.HOST, BarCommands.route(BarCommands.PS_PREV))
        val keys = BarCommands.hubKeys(hubLua())
        assertTrue("Hub.lua sends both: $keys", keys.containsAll(listOf("psnext", "psprev")))
        for (k in keys) assertNotEquals("Hub's key $k has a route", BarCommands.Route.UNKNOWN, BarCommands.route(k))
    }

    @Test fun aBoundStepCommandDrawsItsArrowLikeAnyCommand() {
        val m = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m169","m":{"page":1,"pages":3,"sets":[{"set":1,"slots":[
            {"pos":1,"s":1,"kind":"command","key":"DSPSNEXT","n":"Polestar: next step","i":450908,"b":"/click WowCompanionPadPolestarNext","ov":true},
            {"pos":2,"s":2,"kind":"command","key":"DSPSPREV","n":"Polestar: previous step","i":450906,"b":"/click WowCompanionPadPolestarPrev","ov":true}]}],
            "commands":[{"key":"DSPSNEXT","n":"Polestar: next step","i":450908,"made":true,"b":"/click WowCompanionPadPolestarNext"},
            {"key":"DSPSPREV","n":"Polestar: previous step","i":450906,"made":true,"b":"/click WowCompanionPadPolestarPrev"}]}}"""))!!
        val icons = HomeFightFace.commandIcons(m)
        assertEquals(450908, icons[HomeFightFace.cmdKey(1, 1)])
        assertEquals(450906, icons[HomeFightFace.cmdKey(1, 2)])
        assertEquals(listOf("DSPSNEXT", "DSPSPREV"), m.commands.filter { it.key.startsWith("DSPS") }.map { it.key })
    }
}
