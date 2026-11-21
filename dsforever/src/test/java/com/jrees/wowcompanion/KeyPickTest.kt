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
        """{"type":"padmap","v":"m181","m":{"page":1,"sets":[],"dpad":{"on":true,"want":true,"cols":{"on":1,"off":2},"who":$who}}}"""))!!

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
        assertEquals(listOf(KeyPick.Cand(kept, 288, 1), KeyPick.Cand("Classic Beta PvE", 1, 2)), d.picks)
        assertEquals("ask", d.realmSource)
    }

    @Test fun aCandidateThatIsNoRealmIsDropped() {
        val d = padmap("""{"n":"Korvash","rs":"ask","pk":[{"r":"","n":3},{"r":"?","n":3},{"r":"A","n":-4},{"n":2}]}""").dpad!!
        assertEquals("the add-on's own place kept (3rd)", listOf(KeyPick.Cand("A", 0, 3)), d.picks)
    }

    /** Safety k12 / PadMap m182 (the review, round 2): a candidate the app drops (a C1 control, a Unicode-only blank the add-on's
     *  byte-wise check lets through) never shifts another onto its row: the answer names the add-on's own place */
    @Test fun aDroppedCandidateNeverShiftsTheAnswer() {
        val d = padmap("""{"n":"Korvash","rs":"ask","pk":[{"r":"Realm\u0085X","n":5},{"r":"\u00a0","n":4},{"r":"B","n":3},{"r":"C","n":2}]}""").dpad!!
        assertEquals(listOf(KeyPick.Cand("B", 3, 3), KeyPick.Cand("C", 2, 4)), d.picks)
        assertEquals("row 0 (B) = the add-on's pk[3]", 3, KeyPick.pickOf(0, d.picks))
        assertEquals(listOf("padtab" to 39, "padtab" to 38, "padtab" to 37, "padtab" to 12), KeyPick.cells(KeyPick.pickOf(0, d.picks)))
        assertEquals(listOf("padtab" to 39, "padtab" to 38, "padtab" to 37, "padtab" to 13), KeyPick.cells(KeyPick.pickOf(1, d.picks)))
        assertEquals("the last row: New character", listOf("padtab" to 39, "padtab" to 38, "padtab" to 37, "padtab" to 9),
            KeyPick.cells(KeyPick.pickOf(2, d.picks)))
    }

    @Test fun theChoicesWordsAndAnswers() {
        val c = listOf(KeyPick.Cand(kept, 288, 1), KeyPick.Cand("Classic Beta PvE", 1, 2))
        assertEquals(listOf("Korvash - $kept, 288 settings", "Korvash - Classic Beta PvE, 1 setting", "New character"), KeyPick.options("Korvash", c))
        assertEquals(1, KeyPick.pickOf(0, c)); assertEquals(2, KeyPick.pickOf(1, c)); assertNull("New character", KeyPick.pickOf(2, c))
        // the padtab's third page (Book b74): 39, 38, 37, then 9 + the add-on's place / 9
        assertEquals(listOf("padtab" to 39, "padtab" to 38, "padtab" to 37, "padtab" to 10), KeyPick.cells(1))
        assertEquals(listOf("padtab" to 39, "padtab" to 38, "padtab" to 37, "padtab" to 11), KeyPick.cells(2))
        assertEquals(listOf("padtab" to 39, "padtab" to 38, "padtab" to 37, "padtab" to 9), KeyPick.cells(null))
        assertEquals("the add-on's 10th", 19, KeyPick.cells(10)!!.last().second)
        assertNull("never past the add-on's 10: nothing sent", KeyPick.cells(42))
        assertNull("no place 0", KeyPick.cells(0))
    }

    @Test fun askedOnceOnHomeOutOfFightsLaterUntilTheNextLogin() {
        val c = listOf(KeyPick.Cand(kept, 288, 1), KeyPick.Cand("B", 1, 2))
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
        assertEquals("the review, round 2: an open question drawn from another list is put away", KeyPick.Act.CLOSE,
            KeyPick.decide(c, true, false, null, true, false, changed = true))
    }

    /** the review, round 2: the question is up for X; the player relogs to Y (also asked): X's rows are never answered for Y */
    @Test fun anOpenQuestionFollowsTheCharacterAndItsList() {
        val x = padmap("""{"n":"Korvash","rs":"ask","pk":[{"r":"A","n":3},{"r":"B","n":1}]}""").dpad!!
        val y = padmap("""{"n":"Brenna","rs":"ask","pk":[{"r":"C","n":9},{"r":"D","n":2}]}""").dpad!!
        val shown = KeyPick.Shown(x.name!!, x.picks)
        assertEquals("the same padmap again: still the same question", shown, KeyPick.Shown(x.name!!, x.picks))
        val changed = shown != KeyPick.Shown(y.name!!, y.picks)
        assertTrue(changed)
        assertEquals(KeyPick.Act.CLOSE, KeyPick.decide(y.picks, open = true, later = false, sentAgoMs = null, ready = true, fight = false, changed = changed))
        // put away, then asked with Y's own list
        assertEquals(KeyPick.Act.ASK, KeyPick.decide(y.picks, open = false, later = false, sentAgoMs = null, ready = true, fight = false))
        assertEquals(listOf("Brenna - C, 9 settings", "Brenna - D, 2 settings", "New character"), KeyPick.options(y.name!!, y.picks))
        // the same name, another list (a candidate gone or another count): put away too
        val x2 = padmap("""{"n":"Korvash","rs":"ask","pk":[{"r":"B","n":1},{"r":"E","n":4}]}""").dpad!!
        assertTrue(shown != KeyPick.Shown(x2.name!!, x2.picks))
    }

    /** the review, round 2: "Later" outlives a rebuilt bottom screen (a new host over the same prefs) within the same login */
    @Test fun laterIsKeptAcrossARebuiltHost() {
        val store = HashMap<String, String>()
        fun host() = KeyPick.LaterMarks(get = { store[it] }, put = { k, v -> if (v == null) store.remove(k) else store[k] = v })
        val mark = KeyPick.laterMark(10000L, 77L)
        host().said("Korvash", mark)
        val rebuilt = host()
        assertTrue("the same login: still Later", rebuilt.isLater("Korvash", mark))
        assertFalse("the next login: asked again", rebuilt.isLater("Korvash", KeyPick.laterMark(10450L, 77L)))
        assertFalse("another character: never", rebuilt.isLater("Brenna", mark))
        assertEquals("one entry per name, overwritten", 1, store.size)
        rebuilt.said("Korvash", KeyPick.laterMark(10450L, 77L)); assertEquals(1, store.size)
        rebuilt.answered("Korvash"); assertTrue(store.isEmpty())
        assertFalse(host().isLater("Korvash", KeyPick.laterMark(10450L, 77L)))
        val broken = KeyPick.LaterMarks(get = { error("no prefs") }, put = { _, _ -> error("no prefs") })
        assertFalse("a store that fails: never Later, never a crash", broken.isLater("Korvash", mark)); broken.said("Korvash", mark)
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
            assertEquals(label, Tr.t(KeyPick.label("Korvash", KeyPick.Cand("R 7", 288, 1)), lang))
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

    // ---------------------------------------------------------------- Safety k11 (the k10 review, round 1)

    @Test fun laterLastsUntilTheNextLoginNotTheGameRun() {
        val d1 = padmap("""{"n":"Korvash","rs":"ask","ls":10000,"pk":[{"r":"$kept","n":3},{"r":"B","n":1}]}""").dpad!!
        assertEquals(10000L, d1.loginStamp)
        val mark = KeyPick.laterMark(d1.loginStamp, run = 77L)
        // the same login (another padmap of it): still Later
        assertEquals(mark, KeyPick.laterMark(padmap("""{"n":"Korvash","rs":"ask","ls":10000}""").dpad!!.loginStamp, 77L))
        // a relog or a /reload in the SAME game run: a new stamp = asked again
        val d2 = padmap("""{"n":"Korvash","rs":"ask","ls":10450,"pk":[{"r":"$kept","n":3},{"r":"B","n":1}]}""").dpad!!
        assertFalse(mark == KeyPick.laterMark(d2.loginStamp, 77L))
        // an older add-on (no stamp): the game run, as before
        assertNull(padmap("""{"n":"Korvash","rs":"ask"}""").dpad!!.loginStamp)
        assertEquals(KeyPick.laterMark(null, 77L), KeyPick.laterMark(null, 77L))
        assertFalse(KeyPick.laterMark(null, 77L) == KeyPick.laterMark(null, 78L))
        assertNull("not a stamp", padmap("""{"n":"Korvash","rs":"ask","ls":-3}""").dpad!!.loginStamp)
    }

    private fun e(id: Int) = CombatKeep.Entry(1, 1, id, 1000 + id, "Spell $id", false)

    @Test fun aFreshKeyTakesThisNamesOneBackupUnderAnotherRealm() {
        val fresh = "Korvash-Realm7"                                       // the add-on's key after its saved tables were lost (the hint)
        val real = CombatKeep.Backup(key, (1..288).map { e(it) }, "HUNTER")
        val got = CombatKeep.adopt(fresh, "HUNTER", listOf(real, CombatKeep.Backup("Korvash-_", listOf(e(1)), "HUNTER"),
            CombatKeep.Backup("Brenna-$kept", listOf(e(2)), "HUNTER")))!!
        assertEquals("restored INTO the add-on's key (the app never picks a realm)", fresh, got.who)
        assertEquals(288, got.entries.size); assertEquals("HUNTER", got.cls)
        // the restore's own rule takes it (the store empty at login), only for a fresh key nobody chose (k12: who.ad)
        val m = padmap("""{"n":"Korvash","r":"Realm7","rs":"new","c":"HUNTER","nc":0,"ad":1}""")
        assertEquals(fresh, CombatKeep.who(m))
        assertTrue(CombatKeep.mayAdopt(m))
        assertTrue(CombatKeep.needsRestore(m, got))
        assertNull("another class's backup is never this one's", CombatKeep.adopt(fresh, "WARRIOR", listOf(real)))
        assertNull("two of this name under other realms: never a guess", CombatKeep.adopt(fresh, "HUNTER",
            listOf(real, CombatKeep.Backup("Korvash-Classic Beta PvE", listOf(e(3)), "HUNTER"))))
        assertNull("an empty one is nothing to take", CombatKeep.adopt(fresh, "HUNTER", listOf(real.copy(entries = emptyList()))))
        assertNull("only another name's", CombatKeep.adopt(fresh, "HUNTER", listOf(CombatKeep.Backup("Brenna-$kept", listOf(e(2)), "HUNTER"))))
        // k12 (the review, round 2): both classes known and the same
        assertNull("a backup of no class (older) is never taken", CombatKeep.adopt(fresh, "HUNTER", listOf(real.copy(cls = null))))
        assertNull("an add-on of no class: never", CombatKeep.adopt(fresh, null, listOf(real)))
    }

    /** Safety k12 / PadMap m182 (the review, round 2): the key question's answer is never overridden by another realm's backup */
    @Test fun noAdoptionAfterTheKeyQuestionsAnswer() {
        // the player picked "New character" (rs new, no ad), a realm (picked), or the add-on found the settings (sticky / guid)
        for (who in listOf("""{"n":"Korvash","r":"Forever","rs":"new","c":"HUNTER","nc":0}""",
                """{"n":"Korvash","r":"Pool B","rs":"picked","c":"HUNTER","nc":2,"ad":1}""",
                """{"n":"Korvash","r":"Pool A","rs":"sticky","c":"HUNTER","nc":1}""",
                """{"n":"Korvash","r":"Pool A","rs":"guid","c":"HUNTER","nc":2}""",
                """{"n":"Korvash","r":"Realm7","rs":"new","c":"HUNTER","nc":2,"ad":1}""",
                """{"n":"Korvash","r":"Realm7","rs":"new","c":"HUNTER"}""")) {
            assertFalse(who, CombatKeep.mayAdopt(padmap(who)))
        }
        assertFalse("no padmap who: never", CombatKeep.mayAdopt(padmap("null")))
        assertTrue(padmap("""{"n":"Korvash","r":"Realm7","rs":"new","ad":1}""").dpad!!.adopt)
        assertFalse(padmap("""{"n":"Korvash","r":"Realm7","rs":"new","ad":0}""").dpad!!.adopt)
    }

    @Test fun theChoiceAlwaysFitsTheBottomScreen() {
        val w = 1240f; val h = 1080f
        for (tl in 1..4) for (fl in 0..3) for (n in 1..(KeyPick.MAX + 1)) {
            val f = ChoiceFit.fit(w, h, tl, fl, n)
            val top = maxOf(ChoiceFit.MARGIN, (h - f.boxH) / 2f)
            assertTrue("tl $tl fl $fl n $n: the box ${f.boxH} fits", top + f.boxH <= h - ChoiceFit.MARGIN + 0.01f)
            assertTrue("every choice placed", f.cols * f.rows >= n)
            assertTrue("the box fits the width", f.boxW <= w)
            // Later (111 px, 50 px off the box's bottom) is on the screen
            assertTrue(top + f.boxH - 50f <= h)
            if (tl <= 3 && fl <= 2) assertTrue("tl $tl fl $fl n $n: rows stay touchable (${f.rowH})", f.rowH >= ChoiceFit.MIN_H)
        }
        // a short list keeps the full rows in one column (the look as before)
        val two = ChoiceFit.fit(w, h, 2, 1, 3)
        assertEquals(1, two.cols); assertEquals(ChoiceFit.OPT_H, two.rowH); assertEquals(ChoiceFit.BOX_W, two.boxW)
        // the review's case: 5 candidates + New character, a 2-line title: it used to run 61 px off the screen
        val six = ChoiceFit.fit(w, h, 2, 1, 6)
        assertTrue(six.boxH <= h - 2 * ChoiceFit.MARGIN)
        // 10 candidates + New character: two columns in a wider box
        val all = ChoiceFit.fit(w, h, 3, 2, 11)
        assertEquals(2, all.cols); assertEquals(6, all.rows); assertTrue(all.boxW > ChoiceFit.BOX_W)
    }
}
