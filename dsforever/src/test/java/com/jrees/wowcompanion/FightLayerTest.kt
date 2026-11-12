package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PadMap m132 / PadTabCtl p41, THE FIGHT-ONLY LAYER (the user, 2026-11-11: "Build a fight-only layer"): a combat view whose In combat
 * entry is a whole-button thing (`c.t` command / bind / item / mount, `c.key`, no id) reads as such, its Otherwise is the WoW Defaults
 * assignment, and nothing reads the entry as a spell (no id: the fight-spell state pictures leave it out). The add-on side is
 * tools/test_fight_layer.py (the document shape is checked there from the real Lua).
 */
class FightLayerTest {
    private fun map(c: String, o: String) = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m132","m":{"page":1,"pages":3,
        "sets":[{"set":1,"slots":[{"pos":1,"s":1,"kind":"combat","ov":true,"base":"spell","c":$c,"o":$o}]}]}}"""))!!

    @Test fun aFightLayerCommandOverAWowDefaultsSpell() {
        val m = map("""{"n":"Open Map","i":134269,"t":"command","key":"DSMAP"}""", """{"n":"Healing Wave","id":331,"i":136052}""")
        val sl = PadMapModel.slot(m, 1, 1)!!
        assertEquals(PadMapModel.Kind.COMBAT, sl.kind)
        assertEquals("Open Map", sl.combat?.name)
        assertEquals("command", sl.combat?.layer)
        assertEquals("DSMAP", sl.combat?.key)
        assertNull("no id: never read as a spell", sl.combat?.id)
        assertEquals("the WoW Defaults side, out of combat", 331, sl.otherwise?.id)
        assertEquals("the In combat picture is the command's", 134269, HomeFightFace.dpadIcons(m, fight = true)[1])
        assertEquals("no spell id for the state pictures", null, HomeFightFace.ids(m, fight = true)[1])
    }

    @Test fun aFightSpellHasNoLayerKind() {
        val m = map("""{"n":"Lightning Bolt","id":403,"i":136048}""", """{"n":"Healing Wave","id":331,"i":136052}""")
        val sl = PadMapModel.slot(m, 1, 1)!!
        assertNull(sl.combat?.layer)
        assertEquals(403, sl.combat?.id)
        assertEquals(403, HomeFightFace.ids(m, fight = true)[1])
    }

    @Test fun anItemAndAMountKeepTheirChooserKeys() {
        assertEquals("i:6948", PadMapModel.slot(map("""{"n":"Hearthstone","i":134414,"t":"item","key":"i:6948"}""", "null"), 1, 1)!!.combat?.key)
        val mount = PadMapModel.slot(map("""{"n":"Swift Brown Steed","i":132261,"t":"mount","key":"m:7"}""", "null"), 1, 1)!!
        assertEquals("mount", mount.combat?.layer)
        assertNull("nothing out of combat", mount.otherwise)
    }
}
