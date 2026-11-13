package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * m150 / hub47 SMART RES (the user, 2026-11-13: "There should be an enable smart res toggle setting for party mode to enable pressing
 * dead team members on touch if they are dead and I am playing a class with a res. Similarly a revive pet would be neat." and "Touching
 * the party members lacks any visual feedback making it feel uncertain who im even touching or holding a press for."): the switch on
 * "uisw" 36 / 37 (off by default, its own branch everywhere), the party document's `res` / `pd` (a Hunter's dead pet entry, drawn but
 * never counted in the add-on's pick walk), and the press's dip (pure geometry).
 */
class SmartResTest {
    private val cols = mapOf("PLAYER_SHOW" to 0, "PLAYER_HIDE" to 1, "TARGET_SHOW" to 2, "TARGET_HIDE" to 3, "NP_OFF" to 4, "NP_ON" to 5,
        "PRD_OFF" to 6, "PRD_ON" to 7, "SRES_OFF" to 36, "SRES_ON" to 37)
    private val defaults = mapOf(GameUi.SW_PLAYER to false, GameUi.SW_TARGET to false, GameUi.SW_NP to true, GameUi.SW_PRD to true, GameUi.SW_SRES to false)
    private val allSet = mapOf("player" to true, "target" to true, "np" to true, "prd" to true)

    @Test fun offByDefault() = assertEquals(false, GameUi.swWant(GameUi.SW_SRES))

    @Test fun itsOwnColumns() {
        val s = GameUi.Sw("uisw", cols, false, false, true, true, allSet, sres = false)
        assertEquals(37, GameUi.swCol(s, GameUi.SW_SRES, true))
        assertEquals(36, GameUi.swCol(s, GameUi.SW_SRES, false))
        assertEquals(false, GameUi.Sw("uisw", cols, false, false, true, true, allSet, sres = false).sres)
    }

    @Test fun tappedOnceAfterAReloadThenSettled() {
        val fresh = GameUi.Sw("uisw", cols, false, false, true, true, allSet, sres = false)
        assertEquals(GameUi.SW_SRES to false, GameUi.swNext(fresh) { defaults[it]!! })
        val done = fresh.copy(set = allSet + ("sres" to true))
        assertNull(GameUi.swNext(done) { defaults[it]!! })
        val on = GameUi.swNext(done) { if (it == GameUi.SW_SRES) true else defaults[it]!! }
        assertEquals(GameUi.SW_SRES to true, on)
        // an older add-on with no switch: never tapped
        assertNull(GameUi.swNext(fresh.copy(sres = null, set = allSet)) { if (it == GameUi.SW_SRES) true else defaults[it]!! })
    }

    private fun party(extra: String, members: String = PET_LESS) = PartyModel.parseParty(JSONObject(
        """{"type":"party","v":"m150","rs":3,"p":{"on":true,"box":2,"fight":false,"grp":true,"sel":"bound",$extra"m":[$members]}}"""))!!

    private val PET_LESS = """{"u":"player","i":0,"n":"Korvash","c":"HUNTER","dead":false},
        {"u":"party1","i":1,"n":"Brannoc","c":"WARRIOR","dead":true},{"u":"party2","i":2,"n":"Iselle","c":"MAGE","dead":false}"""

    @Test fun theDocumentsResAndDeadPet() {
        val p = party(""""res":true,"pd":{"n":"Wolf","ic":132203},""")
        assertTrue(p.res)
        val d = p.pd!!
        assertEquals("pet", d.u); assertEquals(PartyModel.PET_I, d.i); assertEquals("Wolf", d.n); assertTrue(d.dead); assertEquals(132203, d.ic)
        assertTrue(d.isPet)
        // never counted: the add-on's walk (n, box, nav) stays without it
        assertEquals(3, p.n); assertFalse(p.pet)
        // drawn second, right of you, with a pet's place in the rows
        assertEquals(listOf("player", "pet", "party1", "party2"), p.view.map { it.u })
        assertTrue(p.viewPet)
        assertEquals(listOf(0, 2, 3), (0 until p.n).map { p.viewIndex(it) })
        val q = party("")
        assertFalse(q.res); assertNull(q.pd)
        assertEquals(q.m, q.view); assertFalse(q.viewPet)
        assertEquals(listOf(0, 1, 2), (0 until q.n).map { q.viewIndex(it) })
    }

    @Test fun aLivePetOutWinsOverAStaleDeadEntry() {
        val p = party(""""pd":{"n":"Wolf"},""", PET_LESS.replaceFirst("},", """},{"u":"pet","i":5,"n":"Wolf","dead":false},"""))
        assertEquals(p.m, p.view)
        assertEquals(listOf(0, 1, 2, 3), (0 until p.n).map { p.viewIndex(it) })
    }

    @Test fun theDeadPetsTapIsThePetsCell() {
        val cfg = PartyModelTest.cfg()
        assertEquals(listOf("party" to 5), PartyTaps.choose(cfg, party(""""pd":{"n":"Wolf"},""").view[1].i))
    }

    @Test fun thePressDipsAndOutlines() {
        val b = PartyLayout.Box(100f, 200f, 300f, 264f)
        val p = PartyLayout.pressed(b)
        assertEquals(b.w * 0.96f, p.w, 0.01f)
        assertEquals(b.h * 0.96f, p.h, 0.01f)
        assertEquals(b.cx, p.cx, 0.01f)
        assertEquals(b.cy + 2f, p.cy, 0.01f)
        assertEquals(b, PartyLayout.pressed(b, 0f))
        // eases in, holds while the finger is down (a hold too), eases out after the lift
        assertEquals(0f, PartyLayout.pressDepth(1000L, 1000L, null), 0f)
        assertEquals(1f, PartyLayout.pressDepth(1000L + PartyLayout.PRESS_IN_MS, 1000L, null), 0f)
        assertEquals(1f, PartyLayout.pressDepth(1000L + PartyModuleView.LONG_MS + 500L, 1000L, null), 0f)
        val up = 2000L
        assertEquals(1f, PartyLayout.pressDepth(up, 1000L, up), 0f)
        assertTrue(PartyLayout.pressDepth(up + PartyLayout.PRESS_OUT_MS / 2, 1000L, up) in 0.4f..0.6f)
        assertEquals(0f, PartyLayout.pressDepth(up + PartyLayout.PRESS_OUT_MS, 1000L, up), 0f)
        // a very short tap (lifted before the dip was in) eases out from where it got to, never jumps to the full dip
        assertTrue(PartyLayout.pressDepth(1030L, 1000L, 1030L) <= 0.5f)
    }

    @Test fun theWordsAreTranslated() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { java.io.File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) {
            val plain = JSONObject(java.io.File(dir, "$l.json").readText()).getJSONObject("plain")
            for (w in listOf("Smart Res", "On: tap a dead member to res", "Not in combat",
                    "A tap on a dead party member casts your res. Rebirth and Revive Pet in combat too. For this character.",
                    "A tap on a dead member works as on any member. For this character.")) assertTrue("$l: $w", plain.has(w))
        }
    }
}
