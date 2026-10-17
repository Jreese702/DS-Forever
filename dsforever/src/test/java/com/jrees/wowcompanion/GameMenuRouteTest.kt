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

/** test18: B on a wheel plays the wheel's close once; its guest's in-game release is not "B went Home" (no second press on the bar) */
class WheelBOnceTest {
    @Test fun aWheelsOwnBIsNotBackToHome() {
        assertTrue(HomeBack.wheelB("pad:radial"))
        assertTrue(HomeBack.wheelB("pad:class"))
        assertTrue(!HomeBack.wheelB("pad:chat") && !HomeBack.wheelB("npc") && !HomeBack.wheelB(null))
    }
}

/** m57 (the user's pick A): the game's own form bars out of combat - where the form bar lies, which forms' bars are known, their places */
class FormBarsParseTest {
    @Test fun theFormBarsPlaceAndTheLearnedBars() {
        val m = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m57","m":{"page":1,"pages":3,"sets":[],
            "forms":[{"x":1,"id":5487,"n":"Bear Form","i":1,"bar":true},{"x":2,"id":768,"n":"Cat Form","i":2}],
            "stance":{"pg":1,"b":4},"fb":[{"f":5487,"p":1,"i":132136,"n":"Maul"}]}}"""))!!
        assertEquals(PadMapModel.Stance(1, 4), m.stance)
        assertTrue(m.forms[0].bar && !m.forms[1].bar)
        assertEquals(132136, m.formBars.single { it.form == 5487 && it.pos == 1 }.icon)
    }
    @Test fun noFormBarWhenTheGameSaysNone() {
        val m = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m57","m":{"page":1,"pages":3,"sets":[]}}"""))!!
        assertNull(m.stance)
    }
}
