package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE TESTER'S LT / RT + FACE REPORT (2026-11-23: "couldn't use any macros on LT or RT with a face button, kept saying invalid target or
 * cant cast when moving"), the app's half, off-device (the add-on's half: tools/test_ltrt_stancebar.py, PadMap m187):
 * - PressLog: a routed press's layer, the bar it clicked and its slot in the words, one WCPAD line per routed press (rate-limited);
 * - PadLayerNote: a WoW Stance / Possess Bar setting moved onto LT / RT / LT + RT is one Home note per change per character (the first
 *   sight kept silently), in English, Spanish and French; the host feeds it the current hud document's `bars` and B lets it go.
 */
class LtRtStanceBarTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }

    @After fun reset() = PressLog.reset()

    private fun cast(pos: Int, kind: String, lay: String?, bar: String?, slot: Int?, at: String? = null, down: Boolean = true) =
        JSONObject().put("type", "facecast").put("v", "m187").put("pos", pos).put("kind", kind).apply {
            if (at != null) put("at", at); if (down) put("down", true)
            if (lay != null) put("lay", lay); if (bar != null) put("bar", bar); if (slot != null) put("slot", slot)
        }

    // ---------------------------------------------------------------- E: the press log's where

    @Test fun theWordsSayTheLayerTheBarAndTheSlot() {
        assertEquals("pos 6 native at GPStance6 via LT stance slot 78", PressLog.pressWords(cast(6, "native", "lt", "stance", 78, "GPStance6")))
        assertEquals("pos 8 native at GPRight8 via RT page1 slot 32", PressLog.pressWords(cast(8, "native", "rt", "page1", 32, "GPRight8")))
        assertEquals("pos 5 native via LT+RT possess slot 125", PressLog.pressWords(cast(5, "native", "ltrt", "possess", 125)))
        assertEquals("pos 6 tradial via bare ours", PressLog.pressWords(cast(6, "tradial", "bare", "ours", null)))
        // an older add-on (no lay): exactly as before; an unknown layer code says nothing of it
        assertEquals("pos 6 native at GPTop6", PressLog.pressWords(cast(6, "native", null, null, null, "GPTop6")))
        assertEquals("pos 6 native", PressLog.pressWords(cast(6, "native", "zz", "stance", 3)))
        // our plain codes only: anything else in the bar word is dropped
        assertEquals("pos 6 native via LT stanceXY", PressLog.pressWords(cast(6, "native", "lt", "stance<X Y>", 0)))
    }

    @Test fun oneLinePerRoutedPressRateLimited() {
        assertEquals("press: pos 6 native at GPStance6 via LT stance slot 78", PressLog.press(cast(6, "native", "lt", "stance", 78, "GPStance6"), 1_000))
        assertNull("the same press again within the limit", PressLog.press(cast(6, "native", "lt", "stance", 78, "GPStance6"), 2_000))
        assertTrue("another layer is its own line", PressLog.press(cast(6, "native", "rt", "page1", 30, "GPRight6"), 2_100)!!.contains("via RT page1"))
        assertTrue("another bar on the same layer too", PressLog.press(cast(6, "native", "lt", "page1", 18, "GPLeft6"), 2_200)!!.contains("via LT page1"))
        assertTrue("after the limit, again", PressLog.press(cast(6, "native", "lt", "stance", 78), 1_000 + PressLog.ROUTED_EVERY_MS) != null)
        assertNull("a key-up: nothing", PressLog.press(cast(6, "native", "lt", "stance", 78, down = false), 20_000))
        assertNull("an older add-on's press (no layer): no line, as before", PressLog.press(cast(3, "slotfight", null, null, null, "ActionButton1"), 30_000))
        // a press that did nothing keeps its own line (and its words now say where)
        assertEquals("press did nothing: pos 2 none via LT none", PressLog.press(cast(2, "none", "lt", "none", null), 40_000))
        val l = PressLog.lines()
        assertTrue(l.any { it.endsWith("pos 6 native at GPStance6 via LT stance slot 78") })
    }

    @Test fun theHostLogsTheRoutedLine() {
        val host = File(src, "CompanionHost.kt").readText()
        assertTrue(host.contains("\"facecast\" -> if (current) PressLog.press(msg, System.currentTimeMillis())?.let { l -> Log.i(\"WCPAD\", l)"))
    }

    // ---------------------------------------------------------------- D: the note

    private class Mem : PadLayerNote.Store {
        val m = HashMap<String, String>()
        override fun get(who: String) = m[who]
        override fun put(who: String, memo: String) { m[who] = memo }
    }

    private fun bars(st: Int?, ps: Int?) = JSONObject().apply {
        if (st != null) put("st", JSONObject().put("v", st).put("n", "Page 1 Left"))
        if (ps != null) put("ps", JSONObject().put("v", ps))
    }

    @Test fun theLayersOfTheGamesValues() {
        assertNull(PadLayerNote.layer(1)); assertEquals("LT", PadLayerNote.layer(2)); assertEquals("RT", PadLayerNote.layer(3))
        assertEquals("LT + RT", PadLayerNote.layer(4)); assertNull(PadLayerNote.layer(5)); assertNull(PadLayerNote.layer(12)); assertNull(PadLayerNote.layer(null))
    }

    @Test fun firstSightIsSilentAChangeOntoATriggerIsOneNote() {
        val s = Mem()
        assertTrue("the first sight: kept, nothing said", PadLayerNote.onBars(s, "c:a", bars(3, 1)).isEmpty())
        assertEquals("st=3;ps=1", s.m["c:a"])
        assertTrue("the same again: nothing", PadLayerNote.onBars(s, "c:a", bars(3, 1)).isEmpty())
        assertEquals(listOf("WoW's Stance Bar is on LT: in a form, LT + buttons cast your form bar"), PadLayerNote.onBars(s, "c:a", bars(2, 1)))
        assertTrue("once per change: the same value again says nothing", PadLayerNote.onBars(s, "c:a", bars(2, 1)).isEmpty())
        assertTrue("moved off the triggers (None): nothing to say", PadLayerNote.onBars(s, "c:a", bars(1, 1)).isEmpty())
        assertTrue("pages 2-3: not LT / RT on the player's page", PadLayerNote.onBars(s, "c:a", bars(6, 1)).isEmpty())
        assertEquals(listOf("WoW's Stance Bar is on LT + RT: in a form, LT + RT + buttons cast your form bar",
            "WoW's Possess Bar is on RT: with a pet, RT + buttons cast your pet bar"), PadLayerNote.onBars(s, "c:a", bars(4, 3)))
        assertEquals("st=4;ps=3", s.m["c:a"])
    }

    @Test fun perCharacterAndUnknowns() {
        val s = Mem()
        PadLayerNote.onBars(s, "c:a", bars(3, 1))
        assertTrue("another character's first sight is its own (silent)", PadLayerNote.onBars(s, "c:b", bars(2, 1)).isEmpty())
        assertTrue("no character known: nothing kept, nothing said", PadLayerNote.onBars(s, null, bars(2, 4)).isEmpty())
        assertEquals(2, s.m.size)
        assertTrue("no values (an older add-on): nothing", PadLayerNote.onBars(s, "c:a", JSONObject()).isEmpty())
        // one value missing keeps the last seen one
        assertEquals(listOf("WoW's Possess Bar is on LT + RT: with a pet, LT + RT + buttons cast your pet bar"), PadLayerNote.onBars(s, "c:a", bars(null, 4)))
        assertEquals("st=3;ps=4", s.m["c:a"])
    }

    @Test fun aCharacterSwitchNeverMovesOneCharactersValuesIntoAnother() {
        // the review: GameUi.character comes from the padmap; the next character's first "full" can come before it
        assertEquals("Ann-Realm", PadLayerNote.whoFor("Ann-Realm", "Ann"))
        assertNull("the document is another character's: nobody's", PadLayerNote.whoFor("Ann-Realm", "Bob"))
        assertEquals("no name in the document (an older add-on): the key", "Ann-Realm", PadLayerNote.whoFor("Ann-Realm", null))
        assertNull(PadLayerNote.whoFor(null, "Ann"))
        val s = Mem()
        PadLayerNote.onBars(s, PadLayerNote.whoFor("Ann-Realm", "Ann"), bars(3, 1))
        assertTrue("Bob's values before his padmap: nothing said", PadLayerNote.onBars(s, PadLayerNote.whoFor("Ann-Realm", "Bob"), bars(2, 1)).isEmpty())
        assertEquals("Ann's memo is untouched", "st=3;ps=1", s.m["Ann-Realm"])
        assertTrue("Ann back: no false change", PadLayerNote.onBars(s, PadLayerNote.whoFor("Ann-Realm", "Ann"), bars(3, 1)).isEmpty())
    }

    @Test fun itGoesByItself() {
        assertEquals(0f, PadLayerNote.alpha(-1), 0f)
        assertEquals(1f, PadLayerNote.alpha(PadLayerNote.IN_MS + 10), 0f)
        assertTrue(PadLayerNote.alpha(PadLayerNote.IN_MS + PadLayerNote.HOLD_MS + PadLayerNote.OUT_MS / 2) in 0.1f..0.9f)
        assertEquals(0f, PadLayerNote.alpha(PadLayerNote.SHOW_MS), 0f)
        assertTrue("a few seconds", PadLayerNote.HOLD_MS in 3_000L..8_000L)
    }

    @Test fun everyNoteIsInSpanishAndFrench() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n").map { File(it) }.first { it.isDirectory }
        for (lang in listOf("es", "fr")) {
            val raw = File(dir, "$lang.json").readBytes()
            assertTrue("$lang.json stays CRLF", String(raw, Charsets.UTF_8).contains("\r\n") && !String(raw, Charsets.UTF_8).replace("\r\n", "").contains("\n"))
            Tr.load(lang, String(raw, Charsets.UTF_8))
        }
        assertEquals(6, PadLayerNote.ALL.size)
        for (w in PadLayerNote.ALL) for (lang in listOf("es", "fr")) {
            val t = Tr.t(w, lang)
            assertFalse("$lang: $w", t == w)
            assertTrue("$lang keeps the layer: $t", listOf("LT + RT", "LT", "RT").first { w.contains("on $it:") }.let { t.contains(it) })
        }
        assertEquals("La Barra de actitudes de WoW está en LT: en una forma, LT + botones lanzan tu barra de forma",
            Tr.t("WoW's Stance Bar is on LT: in a form, LT + buttons cast your form bar", "es"))
    }

    @Test fun theHostAndHomeAreWired() {
        val host = File(src, "CompanionHost.kt").readText()
        val home = File(src, "HomeView.kt").readText()
        assertTrue("the current hud document's bars", host.contains("if (current) msg.optJSONObject(\"actionbar\")?.optJSONObject(\"hud\")?.optJSONObject(\"bars\")?.let {"))
        assertTrue("with the document's own character", host.contains("padLayerNote(it, msg.optJSONObject(\"player\")?.optString(\"name\", \"\")") &&
            host.contains("val who = PadLayerNote.whoFor(GameUi.character, docName)"))
        assertTrue("its own prefs file", host.contains("getSharedPreferences(\"padnote\", MODE_PRIVATE)"))
        assertTrue("B lets it go", host.contains("if (PadInput.isHeld(down, PadInput.SLOT_FACE_RIGHT)) ui.post { homeView.padNoteB() }"))
        assertTrue("drawn translated", home.contains("val text = Tr.t(words)") && home.contains("drawPadNote(c)"))
        assertTrue("a reset never opens it", "padnote" in ResetPlan.NEVER_FILES)
    }
}
