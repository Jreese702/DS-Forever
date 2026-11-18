package com.jrees.wowcompanion

import com.jrees.wowcompanion.stream.BridgeProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * THE THOR'S UI SCALE ON THE PC'S STREAM (the user's ruling 2026-11-18: "while streaming, Desktop also sets WoW's UI scale to match the
 * Thor's and puts the PC's own back after the session"): the Thor reads its own Config.wtf's useUiScale / uiScale and says them in the
 * bridge's hello ("ui_scale"); a Thor with neither says nothing, so Desktop leaves the PC alone. Fake account lines only.
 */
class UiScaleHelloTest {
    private val secret = "SECRET_ACCT_ONE"

    @Test fun theTwoKeysComeBackInOneSpellingTheLastLineWins() {
        val got = GameConfigWtf.uiScale(listOf("SET accountName \"$secret\"", "set USEUISCALE \"1\"", "SET uiScale \"0.9\"",
            "SET gxWindowedResolution \"1920x1080\"", "  SET uiscale \"0.64\"  "))
        assertEquals(mapOf("useUiScale" to "1", "uiScale" to "0.64"), got)
        assertFalse(got.toString().contains(secret))
    }

    @Test fun noScaleLinesIsNull() {
        // the Thor's own Config.wtf (backup 2026-11-18) has neither key: RenderScale and the LOD scales are not the UI's
        assertNull(GameConfigWtf.uiScale(listOf("SET RenderScale \"1\"", "SET doodadLodScale \"50\"", "SET entityShadowFadeScale \"10\"")))
        assertNull(GameConfigWtf.uiScale(emptyList()))
    }

    @Test fun onlyPlainNumbers() {
        assertEquals(mapOf("useUiScale" to "1"), GameConfigWtf.uiScale(listOf("SET useUiScale \"1\"", "SET uiScale \"0.6\\\" x\"", "SET uiScale \"-1\"")))
        assertNull(GameConfigWtf.uiScale(listOf("SET uiScale \"\"", "SET uiScale \"12345678901234567\"")))
    }

    @Test fun readFromTheGamesFolder() {
        val root = Files.createTempDirectory("uiscale").toFile()
        try {
            GameConfigWtf.ensure(root, WowProduct.LAUNCH, mapOf("Branch" to "us"))
            assertNull(GameConfigWtf.uiScale(root))
            File(root, "${WowProduct.LAUNCH.flavorDir}/WTF/Config.wtf").appendText("SET useUiScale \"1\"\nSET uiScale \"0.71\"\n")
            assertEquals(mapOf("useUiScale" to "1", "uiScale" to "0.71"), GameConfigWtf.uiScale(root))
        } finally { root.deleteRecursively() }
        assertNull(GameConfigWtf.uiScale(File(root, "gone")))
    }

    @Test fun theHelloCarriesItOnlyWhenThereIsOne() {
        assertFalse(JSONObject(BridgeProtocol.hello("c1", "tok")).has("ui_scale"))
        assertFalse(JSONObject(BridgeProtocol.hello("c1", "tok", emptyMap())).has("ui_scale"))
        val h = JSONObject(BridgeProtocol.hello("c1", "tok", mapOf("useUiScale" to "1", "uiScale" to "0.64")))
        assertEquals("1", h.getJSONObject("ui_scale").getString("useUiScale"))
        assertEquals("0.64", h.getJSONObject("ui_scale").getString("uiScale"))
        assertEquals("tok", h.getString("token"))
    }
}
