package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE STICKY REALM (the add-on's Safety k10 / PadMap m180, the user's rulings 2026-11-20: WoW Forever's realm is a ruleset pool, the
 * realm the game reports can be empty or another one; a character's name is unique; ambiguous = "the bottom screen asks ONCE"). The app
 * never picks a realm itself: the padmap's who.r is the realm of the STORED key, so every per-character key in the app (the combat
 * backup's file, pet recall, GameUi.character, ...) is the add-on's own; while the add-on asks, there is no key and [KeyPick] draws the
 * question. Names and realms are made up.
 */
class KeyPickTest {
    @After fun clean() { GameUi.character = null }

    private val kept = "CAMWOW US1 Realm 7 PvE"
    private val key = "Korvash-$kept"

    private fun padmap(who: String) = PadMapModel.parse(JSONObject(
        """{"type":"padmap","v":"m180","m":{"page":1,"sets":[],"dpad":{"on":true,"want":true,"cols":{"on":1,"off":2},"who":$who}}}"""))!!

    // ---------------------------------------------------------------- the app's key IS the add-on's

    @Test fun theAppUsesTheAddOnsStickyKeyEverywhere() {
        val m = padmap("""{"n":"Korvash","r":"$kept","rs":"sticky","rh":"present","rr":"empty","nc":1,"uc":0,"gs":"new","c":"HUNTER"}""")
        val d = m.dpad!!
        assertEquals(kept, d.realm)
        assertEquals(key, CharKey.of(d.name, d.realm))
        assertEquals("the In-combat backup follows the sticky key", key, CombatKeep.who(m))
        assertEquals("$key.json", CombatKeep.fileName(CombatKeep.who(m)!!))
        assertEquals(key, PetRecall.who(d.name, d.realm))
        assertTrue(d.picks.isEmpty())
        assertEquals("sticky", d.realmSource); assertEquals("empty", d.realmReported); assertEquals(1, d.nameCands)
        assertEquals(0, d.unclaimed); assertEquals("new", d.guidState)
    }

    @Test fun aReportedRealmTheSettingsDoNotLiveUnderIsNeverTheAppsKey() {
        // the add-on sends the STORED key's realm; the app builds nothing from the game's reported realm (it never sees it)
        val m = padmap("""{"n":"Korvash","r":"$kept","rs":"sticky","rr":"reported","rh":"present"}""")
        assertEquals(key, CombatKeep.who(m))
        assertFalse(m.toString().contains("CAMWOW US1 Realm 9 PvE"))
    }

    // ---------------------------------------------------------------- the question

    private val asking = """{"n":"Korvash","rs":"ask","rr":"empty","rh":"none","nc":2,"uc":0,"gs":"new",
        "pk":[{"r":"$kept","n":288},{"r":"Classic Beta PvE","n":1}]}"""

    @Test fun anAmbiguousKeyIsNoKeyAndCarriesTheQuestion() {
        val m = padmap(asking)
        val d = m.dpad!!
        assertNull(d.realm)
        assertNull("no key: nothing per character", CombatKeep.who(m))
        assertNull(PetRecall.who(d.name, d.realm))
        assertEquals(listOf(KeyPick.Cand(kept, 288), KeyPick.Cand("Classic Beta PvE", 1)), d.picks)
        assertEquals("ask", d.realmSource)
    }

    @Test fun aCandidateThatIsNoRealmIsDropped() {
        val d = padmap("""{"n":"Korvash","rs":"ask","pk":[{"r":"","n":3},{"r":"?","n":3},{"r":"A","n":-4},{"n":2}]}""").dpad!!
        assertEquals(listOf(KeyPick.Cand("A", 0)), d.picks)
    }

    @Test fun theChoicesWordsAndAnswers() {
        val c = listOf(KeyPick.Cand(kept, 288), KeyPick.Cand("Classic Beta PvE", 1))
        assertEquals(listOf("Korvash - $kept, 288 settings", "Korvash - Classic Beta PvE, 1 setting", "New character"), KeyPick.options("Korvash", c))
        assertEquals(0, KeyPick.pickOf(0, c)); assertEquals(1, KeyPick.pickOf(1, c)); assertNull("New character", KeyPick.pickOf(2, c))
        // the padtab's third page (Book b74): 39, 38, 37, then 10 + i / 9
        assertEquals(listOf("padtab" to 39, "padtab" to 38, "padtab" to 37, "padtab" to 10), KeyPick.cells(0))
        assertEquals(listOf("padtab" to 39, "padtab" to 38, "padtab" to 37, "padtab" to 11), KeyPick.cells(1))
        assertEquals(listOf("padtab" to 39, "padtab" to 38, "padtab" to 37, "padtab" to 9), KeyPick.cells(null))
        assertEquals("never past the add-on's 10", 19, KeyPick.cells(42).last().second)
    }

    @Test fun askedOnceOnHomeOutOfFightsLaterUntilTheNextLogin() {
        val c = listOf(KeyPick.Cand(kept, 288), KeyPick.Cand("B", 1))
        assertEquals(KeyPick.Act.ASK, KeyPick.decide(c, open = false, later = false, sentAgoMs = null, ready = true, fight = false))
        assertEquals("a fight: waits", KeyPick.Act.WAIT, KeyPick.decide(c, false, false, null, true, true))
        assertEquals("Home busy: waits", KeyPick.Act.WAIT, KeyPick.decide(c, false, false, null, false, false))
        assertEquals("already open: nothing", KeyPick.Act.NONE, KeyPick.decide(c, true, false, null, true, false))
        assertEquals("open and a fight starts: put away", KeyPick.Act.CLOSE, KeyPick.decide(c, true, false, null, true, true))
        assertEquals("B = later: not this game run", KeyPick.Act.NONE, KeyPick.decide(c, false, true, null, true, false))
        assertEquals("an answer just sent: waits for the add-on", KeyPick.Act.WAIT, KeyPick.decide(c, false, false, 2_000L, true, false))
        assertEquals("the add-on still asks long after (a lost tap): asked again", KeyPick.Act.ASK,
            KeyPick.decide(c, false, false, KeyPick.ANSWER_WAIT_MS + 1, true, false))
        assertEquals("the key came: an open question goes", KeyPick.Act.CLOSE, KeyPick.decide(emptyList(), true, false, null, true, false))
        assertEquals(KeyPick.Act.NONE, KeyPick.decide(emptyList(), false, false, null, true, false))
    }

    // ---------------------------------------------------------------- the WCSETUP line: words and counts only

    @Test fun theDiagnosticLineHasNoNameNoRealm() {
        val d = padmap("""{"n":"Korvash","r":"$kept","rs":"sticky","rr":"empty","rh":"present","nc":1,"uc":2,"gs":"server-changed"}""").dpad
        val line = KeyPick.diagLine(d)!!
        assertEquals("character key: sticky, realm empty, hint present, name candidates 1, unclaimed same-class 2, guid server-changed", line)
        assertFalse(line.contains("Korvash")); assertFalse(line.contains("CAMWOW"))
        val ask = KeyPick.diagLine(padmap(asking).dpad)!!
        assertEquals("character key: ask, realm empty, hint none, name candidates 2, unclaimed same-class 0, guid new", ask)
        assertFalse(ask.contains("Classic")); assertFalse(ask.contains("CAMWOW"))
        // an unknown word is left out (never a value the add-on did not mean as a word)
        assertEquals("character key: guid", KeyPick.diagLine(padmap("""{"n":"K","r":"R","rs":"guid","rr":"Some Realm","gs":"Player-1-x"}""").dpad))
        assertNull(KeyPick.diagLine(padmap("""{"n":"K","r":"R","rs":"Some Realm"}""").dpad))
        // an older add-on (k9): its words still make the line
        assertEquals("character key: kept, hint none", KeyPick.diagLine(padmap("""{"n":"K","r":"R","rs":"kept","rh":"none"}""").dpad))
    }

    // ---------------------------------------------------------------- the top-screen switches go once the add-on reports a key

    @Test fun polestarIsHiddenOnceTheStickyKeyComes() {
        val cols = mapOf("PLAYER_SHOW" to 0, "PLAYER_HIDE" to 1, "TARGET_SHOW" to 2, "TARGET_HIDE" to 3, "NP_OFF" to 4, "NP_ON" to 5,
            "PRD_OFF" to 6, "PRD_ON" to 7, "PS_SHOW" to 30, "PS_HIDE" to 31)
        val shown = GameUi.Sw("uisw", cols, false, false, true, true, mapOf("player" to true, "target" to true, "np" to true, "prd" to true, "ps" to true), ps = false)
        // asked: no key, so no character: GameUi waits (its reconcile sends nothing per character)
        val ask = padmap(asking).dpad!!
        GameUi.character = CharKey.of(ask.name, ask.realm)
        assertNull(GameUi.character)
        // the sticky key: the character the host sets from the padmap, and Polestar's hide is the switch that goes
        val d = padmap("""{"n":"Korvash","r":"$kept","rs":"sticky","rr":"empty"}""").dpad!!
        GameUi.character = CharKey.of(d.name, d.realm)
        assertEquals(key, GameUi.character)
        assertTrue("hidden by default for the sticky key's character", GameUi.swWant(GameUi.SW_PS))
        val next = GameUi.swNext(shown) { GameUi.swWant(it) }
        assertEquals(GameUi.SW_PS to true, next)
        assertEquals(31, GameUi.swCol(shown, next!!.first, next.second))
    }

    // ---------------------------------------------------------------- the words in Spanish and French

    private fun asset(rel: String): File =
        listOf(File("../companion/app/src/main/assets/$rel"), File("companion/app/src/main/assets/$rel")).first { it.isFile }

    @Test fun theQuestionIsTranslatedAndTheFilesStayCrlf() {
        for ((lang, title, label) in listOf(Triple("es", "¿Qué ajustes guardados son de este personaje?", "Korvash - R 7, 288 ajustes"),
                Triple("fr", "Quels réglages enregistrés sont ceux de ce personnage ?", "Korvash - R 7, 288 réglages"))) {
            val f = asset("companion/i18n/$lang.json")
            val bytes = f.readBytes()
            val lf = bytes.count { it == '\n'.code.toByte() }
            val crlf = (1 until bytes.size).count { bytes[it] == '\n'.code.toByte() && bytes[it - 1] == '\r'.code.toByte() }
            assertEquals("$lang.json stays CRLF", lf, crlf)
            Tr.load(lang, bytes.toString(Charsets.UTF_8))
            assertEquals(title, Tr.t(KeyPick.TITLE, lang))
            assertEquals(label, Tr.t(KeyPick.label("Korvash", KeyPick.Cand("R 7", 288)), lang))
            assertNotNull(Tr.t(KeyPick.FOOTER, lang).takeIf { it != KeyPick.FOOTER })
            assertNotNull(Tr.t(KeyPick.NEW_CHAR, lang).takeIf { it != KeyPick.NEW_CHAR })
        }
    }

    // ---------------------------------------------------------------- the GUID records are never a character's key

    @Test fun theGuidRecordsAreNoCharacterKeys() {
        val db: Map<*, *> = linkedMapOf("who" to linkedMapOf("Player-1-Korvash" to linkedMapOf("k" to key, "c" to "HUNTER", "i" to "Korvash")),
            "fightBy" to linkedMapOf(key to linkedMapOf("x" to 1L)))
        val realms = RealmFix.realmsByName(listOf(db))
        assertEquals(setOf(kept), realms["Korvash"])
        assertNull("a GUID key is never read as a character \"Player\"", realms["Player"])
        assertTrue(ResetPlan.SV_NEVER.getValue("WowCompanionDB").contains("who"))
    }
}
