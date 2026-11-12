package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #197 ("Not portrait for the target even when enabled"): the game's Target frame and DS Forever's target bar are one choice
 * (GameUi.swLinked), so turning the frame on is never silently overruled by the bar. Issue #181: the add-on's "aurasec" probe as one
 * plain log line (AuraProbe).
 */
class TopFramesIssuesTest {
    @Test fun targetFrameOnTurnsTheBarOff() {
        assertEquals(GameUi.SW_UFT to false, GameUi.swLinked(GameUi.SW_TARGET, true))
    }

    @Test fun targetBarOnHidesTheGameFrame() {
        assertEquals(GameUi.SW_TARGET to false, GameUi.swLinked(GameUi.SW_UFT, true))
    }

    @Test fun turningEitherOffTouchesNothingElse() {
        assertNull(GameUi.swLinked(GameUi.SW_TARGET, false))
        assertNull(GameUi.swLinked(GameUi.SW_UFT, false))
        for (p in listOf(GameUi.SW_PLAYER, GameUi.SW_PARTY, GameUi.SW_UFP, GameUi.SW_PET, GameUi.SW_NP, GameUi.SW_UFTOT)) {
            assertNull(p, GameUi.swLinked(p, true))
            assertNull(p, GameUi.swLinked(p, false))
        }
    }

    @Test fun auraProbeLine() {
        val m = JSONObject("""{"type":"cap","probe":"aurasec","v":"prd16","u":"target","fight":true,"lock":true,"via":"ids/ids/ids",
            |"seen":4,"shown":4,"st":3,"sf":12,"th":0}""".trimMargin())
        assertEquals("aura read target (prd16): fight true secret true via ids/ids/ids seen 4 shown 4 secret tables 3 secret fields 12 threw 0",
            AuraProbe.line(m))
        val t = JSONObject("""{"probe":"aurasec","u":"player","fight":true,"via":"index/index","seen":0,"shown":0,"st":0,"sf":0,"th":2,"err":"restricted"}""")
        val l = AuraProbe.line(t)
        assertTrue(l, l.contains("secret ?") && l.endsWith("threw 2 (restricted)"))
    }
}
