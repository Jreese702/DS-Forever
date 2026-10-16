package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** gm3 (the user's pick A): Options / AddOns / Support open through the game's own menu; no Menu button = the Escape fallback */
class GameMenuRouteTest {
    private fun doc(micro: Boolean) = JSONObject("""{"type":"gamemenu","items":[{"key":"options","t":"Options","on":true},
        {"key":"addons","t":"AddOns","on":true},{"key":"support","t":"Support","on":true},{"key":"logout","t":"Log Out","on":true,"col":0}]""" +
        (if (micro) ""","micro":{"x":1800,"y":1040,"w":28,"h":36}}""" else "}"))

    @Test fun optionsGoesThroughTheGamesMenuButton() {
        val d = doc(true)
        val f = GameMenuModel.Flow(GameMenuModel.items(d), GameMenuModel.micro(d))
        val a = f.a(0L, f.rows.indexOfFirst { it?.key == "options" })
        assertTrue(a is GameMenuModel.Act.Game)
        assertEquals(1814.0, (a as GameMenuModel.Act.Game).micro.cx, 0.01)
    }

    @Test fun aHiddenMenuButtonFallsBackToEscape() {
        val d = doc(false)
        val f = GameMenuModel.Flow(GameMenuModel.items(d), GameMenuModel.micro(d))
        assertTrue(f.a(0L, f.rows.indexOfFirst { it?.key == "support" }) is GameMenuModel.Act.Escape)
    }

    @Test fun theShownDocumentSaysWhereEachButtonIs() {
        val s = JSONObject("""{"type":"gamemenu","why":"shown","targets":{"addons":{"x":900,"y":400,"w":200,"h":40}}}""")
        assertEquals(1000.0, GameMenuModel.target(s, "addons")!!.cx, 0.01)
        assertNull(GameMenuModel.target(s, "options"))
    }
}
