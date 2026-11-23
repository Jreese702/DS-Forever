package com.jrees.wowcompanion

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MOVABLE HOME, THE RECORD'S LIFE (SPEC.txt sections 9 / 15; phase 1): JSON round trip and tolerant parse; the migration of today's keys
 * into Classic; 1518's records with a legacy preset / slot name (read as their own fields, the name kept, never rewritten); the
 * per-character store (D5) with its default, first-login seed and "All characters"; the old setters as facades; the toolbar mirror's resend rule; hold to talk's key after a swap; the Controller room's
 * toolbar words; the page-3 code registry; the reset plan's new keys. Pure.
 */
class HomeLayoutStoreTest {
    @After fun down() { HomeSettings.startForTest(null); RealmFix.forget() }

    private fun fresh(p: Prefs = Prefs()): Prefs { HomeSettings.startForTest(p); return p }

    // ---------------------------------------------------------------- JSON

    @Test fun jsonRoundTripsEveryRecord() {
        for (l in HomeLayoutRules.all()) assertEquals(l, HomeLayoutJson.parse(HomeLayoutJson.json(l)))
        val named = HomeLayout(name = "2", chatSide = HomeLayout.Side.RIGHT, xpBar = false)
        assertEquals(named, HomeLayoutJson.parse(HomeLayoutJson.json(named)))
    }

    @Test fun jsonIsTolerant() {
        val classic = HomeLayout()
        for (bad in listOf(null, "", "{", "[]", "garbage", """{"v":99,"chat":"RIGHT"}""", """{"v":0}""", """{"chat":"RIGHT"}"""))
            assertEquals("$bad", classic, HomeLayoutJson.parse(bad))
        // unknown words fall back field by field
        val l = HomeLayoutJson.parse("""{"v":1,"chat":"RIGHT","share":"QUARTER","card":"MOON","cast":"ACROSS","size":"XL","rest":"ROW","fight":"SAME"}""")
        assertEquals(HomeLayout(chatSide = HomeLayout.Side.RIGHT, cast = HomeLayout.CastDock.ACROSS, rest = HomeRest.ROW,
            fight = HomeLayout.FightBand.SAME), l)
    }

    // ---------------------------------------------------------------- migration

    @Test fun todaysKeysBecomeClassic() {
        for (rest in HomeRest.values()) for (grow in listOf(true, false)) for (over in listOf(true, false)) for (side in CastStripModel.Side.values()) {
            val l = HomeLayoutMigration.fromToday(rest, grow, over, side)
            assertEquals(HomeLayout.LEGACY_CLASSIC, l.name)
            assertEquals(HomeLayout.Side.LEFT, l.chatSide); assertEquals(HomeLayout.Share.HALF, l.chatShare)
            assertEquals(rest, l.rest)
            assertEquals(if (grow) HomeLayout.FightBand.GROW else HomeLayout.FightBand.SAME, l.fight)
            assertEquals(if (over) HomeLayout.CardDock.ON_MAP else HomeLayout.CardDock.ABOVE_CHAT, l.card)
            assertEquals("the bar keeps its screen side", side, l.castSide)
            assertEquals(HomeLayout.SlotSize.L, l.castSize)
        }
    }

    @Test fun theStoreMigratesOnceFromThePrefs() {
        val p = fresh(Prefs(hashMapOf("rest" to "ROW", "combatGrow" to false, "questOverMap" to false, "castBarSide" to "RIGHT")))
        val l = HomeSettings.layout
        assertEquals(HomeRest.ROW, l.rest); assertEquals(HomeLayout.FightBand.SAME, l.fight)
        assertEquals(HomeLayout.CardDock.ABOVE_CHAT, l.card); assertEquals(HomeLayout.CastDock.UNDER_MAP, l.cast)
        assertEquals(true, p.m[HomeLayoutStore.KEY_MIGRATED])
        // the old castStrip On = Left
        fresh(Prefs(hashMapOf("castStrip" to true)))
        assertEquals(HomeLayout.CastDock.UNDER_CHAT, HomeSettings.layout.cast)
        // nothing kept: Classic with today's defaults (D1: Grow)
        fresh()
        assertEquals(HomeLayout(), HomeSettings.layout)
        assertEquals(HomeLayout.FightBand.GROW, HomeSettings.layout.fight)
    }

    // ---------------------------------------------------------------- facades

    @Test fun theOldSettersWriteTheRecord() {
        val p = fresh()
        HomeSettings.rest = HomeRest.ROW
        assertEquals(HomeRest.ROW, HomeSettings.layout.rest); assertEquals("the old key too", "ROW", p.m["rest"])
        HomeSettings.combatGrow = false
        assertEquals(HomeLayout.FightBand.SAME, HomeSettings.layout.fight); assertFalse(HomeSettings.combatGrow)
        HomeSettings.questOverMap = false
        assertEquals(HomeLayout.CardDock.ABOVE_CHAT, HomeSettings.layout.card); assertFalse(HomeSettings.questOverMap)
        HomeSettings.castBarSide = CastStripModel.Side.BOTH                    // CastBarAsk's yes / "Turn on"
        assertEquals(HomeLayout.CastDock.ACROSS, HomeSettings.layout.cast)
        assertTrue(HomeSettings.castBarChosen)
        // after a swap the screen's Left is the map's column: an old route's Left means "under the map" now
        HomeLayoutStore.update { it.swapped() }
        HomeSettings.castBarSide = CastStripModel.Side.LEFT
        assertEquals(HomeLayout.CastDock.UNDER_MAP, HomeSettings.layout.cast)
        assertEquals(CastStripModel.Side.LEFT, HomeSettings.castBarSide)
        HomeSettings.castDock = HomeLayout.CastDock.UNDER_CHAT
        assertEquals(CastStripModel.Side.RIGHT, HomeSettings.castBarSide)
        // a change is heard by Home's listeners
        var heard = 0
        HomeSettings.onChange { heard++ }
        HomeLayoutStore.update { it.copy(chatShare = HomeLayout.Share.THIRD) }
        assertTrue(heard >= 1)
    }

    // ---------------------------------------------------------------- 1518: no presets, the legacy name

    /**
     * the user's ruling 2026-11-21 (layouts removed): a 1517 record that names a preset or a slot already holds that arrangement's own
     * fields, so it reads as exactly that (no visual change on the upgrade); its name is kept as read and never re-derived - a write
     * (edit mode's autosave, a facade setter, a lay-on) keeps the text, a Classic record's byte for byte; slots / default / held data
     * stay on disk untouched
     */
    @Test fun aRecordNamingAPresetReadsAsItsFieldsItsNameKept() {
        val bigMap = HomeArrangements.of(HomeArrangements.BIG_MAP, HomeArrangements.BASE)
        val raw = HomeLayoutJson.json(bigMap)                                      // the text 1517 wrote for Big map
        assertTrue(raw.contains(""""name":"Big map""""))
        val slots = """[null,{"v":1,"name":"2","chat":"RIGHT"},null]"""
        val p = fresh(Prefs(hashMapOf(HomeLayoutStore.KEY_MIGRATED to true, HomeLayoutStore.KEY_DEFAULT to HomeLayoutJson.json(HomeLayout()),
            "homeLayout.Korvash-Realm" to raw, HomeLayoutStore.KEY_SLOTS_LEGACY to slots)))
        HomeLayoutStore.character("Korvash-Realm")
        assertEquals("read as its fields", bigMap, HomeSettings.layout)
        assertEquals("nothing rewritten on reading", raw, p.m["homeLayout.Korvash-Realm"])
        // a change: the name stays as read (no preset names it any more), only the changed field differs
        HomeLayoutStore.update { it.copy(castSize = HomeLayout.SlotSize.M) }
        assertEquals(bigMap.copy(castSize = HomeLayout.SlotSize.M), HomeLayoutJson.parse(p.m["homeLayout.Korvash-Realm"] as String))
        assertEquals("Big map", HomeSettings.layout.name)
        // a lay-on keeps the real record's name
        assertEquals("Big map", HomeLayoutStore.layOn(bigMap, HomeLayout(), HomeLayout(name = "Mirrored", chatSide = HomeLayout.Side.RIGHT)).name)
        // a slot's record reads as its fields too
        p.m["homeLayout.Alt-Realm"] = """{"v":1,"name":"3","chat":"RIGHT","share":"TWO_THIRDS"}"""
        HomeLayoutStore.character("Alt-Realm")
        assertEquals(HomeLayout(name = "3", chatSide = HomeLayout.Side.RIGHT, chatShare = HomeLayout.Share.TWO_THIRDS), HomeSettings.layout)
        // the slots are left on disk, unread
        assertEquals(slots, p.m[HomeLayoutStore.KEY_SLOTS_LEGACY])
        // a Classic record's text is byte-identical through a write that changes nothing of it
        val classicRaw = HomeLayoutJson.json(HomeLayout())
        HomeLayoutStore.character("Newbie-Realm")
        assertEquals(classicRaw, p.m["homeLayout.Newbie-Realm"])
        HomeLayoutStore.update { it.copy(rest = HomeRest.ROW) }
        HomeLayoutStore.update { it.copy(rest = HomeRest.DIAMONDS) }
        assertEquals(classicRaw, p.m["homeLayout.Newbie-Realm"])
    }

    /** 1518: the Reset Home words in Spanish and French (CRLF kept), and the layouts' own words gone with them (terse: no dead strings) */
    @Test fun theResetWordsAreTranslatedAndTheLayoutWordsGone() {
        val dir = listOf(java.io.File("../companion/app/src/main/assets/companion/i18n"), java.io.File("companion/app/src/main/assets/companion/i18n"))
            .first { it.isDirectory }
        for (l in listOf("es", "fr")) {
            val text = java.io.File(dir, "$l.json").readBytes().toString(Charsets.UTF_8)
            val plain = org.json.JSONObject(text).getJSONObject("plain")
            for (w in listOf("Edit Home", "Reset Home", "Reset Home to Classic", "Use this Home for all characters", "Done", "Discard", "Add"))
                assertTrue("$l: $w", plain.has(w))
            for (w in listOf("Home layout", "Edited", "Layouts", "Keep editing", "Mirrored", "Big map", "Big chat", "Compact", "Fighter",
                    "Mine", "Save here")) assertFalse("$l: $w is gone", plain.has(w))
            assertTrue("$l: CRLF lines", text.split("\n").dropLast(1).all { it.endsWith("\r") })
        }
    }

    /** 1518, Settings' Reset Home: "Reset Home to Classic" is THIS character's only (the whole key, the store's rules); "Use this Home
     *  for all characters" writes the default (new characters' seed) and every whole key, offered only once a whole key is known */
    @Test fun resetHomeToClassicIsThisCharactersAndAllCharactersCopiesIt() {
        val p = fresh()
        HomeLayoutStore.character("Korvash-Realm")
        val fighter = HomeArrangements.of(HomeArrangements.FIGHTER, HomeArrangements.BASE).copy(xpBar = false, rooms = RoomBar.DEFAULT.reversed())
        HomeLayoutStore.set(fighter)
        HomeLayoutStore.character("Thrandil-Realm")
        HomeLayoutStore.set(HomeLayout(chatSide = HomeLayout.Side.RIGHT))
        val thrandil = p.m["homeLayout.Thrandil-Realm"]
        HomeLayoutStore.character("Korvash-Realm")
        BottomTiles.press(BottomTiles.HOME_RESET, BottomTiles.HOME_CLASSIC, 0, BottomTiles.Env(null, "Korvash-Realm")) { }
        assertEquals(fighter.classic(), HomeSettings.layout)
        assertTrue(HomeSettings.layout.classicPlace); assertTrue(HomeSettings.layout.xpBar); assertEquals(RoomBar.DEFAULT, HomeSettings.layout.rooms)
        assertEquals("another character's Home untouched", thrandil, p.m["homeLayout.Thrandil-Realm"])
        // all characters: offered with a whole key, writes the default and every whole key
        assertTrue(BottomTiles.resetLines().first { it.key == BottomTiles.ALL_CHARS }.enabled)
        HomeLayoutStore.update { it.copy(chatShare = HomeLayout.Share.THIRD) }
        BottomTiles.press(BottomTiles.HOME_RESET, BottomTiles.ALL_CHARS, 0, BottomTiles.Env(null, "Korvash-Realm")) { }
        HomeLayoutStore.character("Thrandil-Realm")
        assertEquals(HomeLayout.Share.THIRD, HomeSettings.layout.chatShare)
        HomeLayoutStore.character("Newbie-Realm")
        assertEquals("a new character starts from it", HomeLayout.Share.THIRD, HomeSettings.layout.chatShare)
        // pending (no whole key): not offered, and a press writes nothing
        HomeLayoutStore.character(null, "Pendy")
        assertFalse(BottomTiles.resetLines().first { it.key == BottomTiles.ALL_CHARS }.enabled)
        val before = HashMap(p.m)
        BottomTiles.press(BottomTiles.HOME_RESET, BottomTiles.ALL_CHARS, 0, BottomTiles.Env(null, null)) { }
        assertEquals(before, p.m)
    }

    /** the review: "Reset Home to Classic" while the key is pending would be held against the pending view (the default) and lay
     *  nothing on the character's own record when the key arrives - so it is offered, like "all characters", only under a whole key */
    @Test fun resetHomeToClassicWaitsForAWholeKey() {
        val p = fresh()
        HomeLayoutStore.character("Korvash-Realm")
        val own = HomeLayout(chatSide = HomeLayout.Side.RIGHT, hidden = listOf("MAP"))
        HomeLayoutStore.set(own)
        val stored = p.m["homeLayout.Korvash-Realm"]
        HomeLayoutStore.character(null, "Korvash")
        assertTrue(HomeLayoutStore.pending)
        assertFalse(BottomTiles.resetLines().first { it.key == BottomTiles.HOME_CLASSIC }.enabled)
        val before = HashMap(p.m)
        BottomTiles.press(BottomTiles.HOME_RESET, BottomTiles.HOME_CLASSIC, 0, BottomTiles.Env(null, null)) { }
        HomeSettings.resetHomeToClassic()
        assertEquals("nothing written or held", before, p.m)
        HomeLayoutStore.character("Korvash-Realm")
        assertEquals(stored, p.m["homeLayout.Korvash-Realm"])
        assertEquals(own, HomeSettings.layout)
        // with the key: offered, and it resets this character's own record
        assertTrue(BottomTiles.resetLines().first { it.key == BottomTiles.HOME_CLASSIC }.enabled)
        BottomTiles.press(BottomTiles.HOME_RESET, BottomTiles.HOME_CLASSIC, 0, BottomTiles.Env(null, "Korvash-Realm")) { }
        assertEquals(own.classic(), HomeSettings.layout)
        assertTrue(HomeSettings.layout.classicPlace); assertTrue(HomeSettings.layout.hidden.isEmpty())
    }

    /** the user's ruling 2026-11-22 after 1518: a character's record 1518 stored with "pad" / "clock" is read with them ignored; edit
     *  mode's autosave, Reset, and "Use this Home for all characters" write it back without them */
    @Test fun a1518RecordsPadAndClockAreDroppedOnTheNextWrite() {
        val p = fresh()
        val raw = """{"v":1,"name":"Classic","chat":"RIGHT","share":"HALF","cast":"UNDER_CHAT","pad":["LEFT_COL","HOME","RIGHT_COL","RADIAL"],"clock":"RIGHT"}"""
        p.m["homeLayout.Korvash-Realm"] = raw
        HomeLayoutStore.character("Korvash-Realm")
        assertEquals(HomeLayoutJson.parse(raw.replace(""","pad":["LEFT_COL","HOME","RIGHT_COL","RADIAL"],"clock":"RIGHT"""", "")), HomeSettings.layout)
        assertEquals("nothing rewritten on reading", raw, p.m["homeLayout.Korvash-Realm"])
        // edit mode's autosave (the store's own rules), then Discard's way back, then all characters, then Reset
        val base = HomeSettings.layout
        HomeSettings.saveLayout(HomeLayoutStore.editSave(HomeLayoutStore.owner(), base, base.copy(chatShare = HomeLayout.Share.THIRD))!!)
        fun stored() = p.m["homeLayout.Korvash-Realm"] as String
        for (step in listOf("autosave", "discard", "all", "reset")) {
            when (step) {
                "discard" -> HomeSettings.saveLayout(HomeLayoutStore.editSave(HomeLayoutStore.owner(), HomeSettings.layout, base)!!)
                "all" -> HomeLayoutStore.set(HomeSettings.layout.swapped(), all = true)
                "reset" -> HomeSettings.resetHomeToClassic()
            }
            assertFalse("$step: ${stored()}", stored().contains("\"pad\"") || stored().contains("\"clock\""))
        }
        assertTrue(HomeSettings.layout.classicPlace)
    }

    @Test fun theResetTileKeepsItsShape() {
        fresh()
        // the tile: last of Home's, named Reset Home; Edit Home has no band of its own
        assertEquals(BottomTiles.HOME_RESET, BottomTiles.HOME_TILES.last())
        assertEquals("Reset Home", BottomTiles.NAMES[BottomTiles.HOME_RESET]); assertEquals("Edit Home", BottomTiles.NAMES[BottomTiles.LAYOUT])
        assertTrue(BottomTiles.lines(BottomTiles.LAYOUT, BottomTiles.Env(null, null)).isEmpty())
        assertEquals(listOf("Reset Home to Classic", "Use this Home for all characters"),
            BottomTiles.lines(BottomTiles.HOME_RESET, BottomTiles.Env(null, null)).map { it.words.single() })
    }

    // ---------------------------------------------------------------- the bar above its column

    @Test fun theBarAboveItsColumnRoundTripsAndOldRecordsReadAsTheFoot() {
        // an older record (no Above word) reads as the foot, as before; the new words round-trip
        for (old in listOf("UNDER_CHAT", "UNDER_MAP")) {
            val l = HomeLayoutJson.parse("""{"v":1,"chat":"LEFT","cast":"$old"}""")
            assertFalse(l.cast.top)
            assertEquals(old, l.cast.name)
        }
        for (d in listOf(HomeLayout.CastDock.ABOVE_CHAT, HomeLayout.CastDock.ABOVE_MAP)) for (side in HomeLayout.Side.values()) {
            val l = HomeLayout(chatSide = side, cast = d)
            assertEquals(l, HomeLayoutJson.parse(HomeLayoutJson.json(l)))
            assertTrue(HomeLayoutJson.json(l).contains("\"cast\":\"${d.name}\""))
            assertEquals("the version stays 1", 1, l.v)
            // a swap keeps the module (and the edge): Above map is above the map whichever side the map takes
            assertEquals(d, l.swapped().cast)
            assertEquals(l.copy(cast = d.withTop(false)).castSide, l.castSide)
        }
        // layOn: an edit Under -> Above lands on the real record's other changes
        val base = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
        val real = base.copy(chatShare = HomeLayout.Share.THIRD)
        val laid = HomeLayoutStore.layOn(real, base, base.copy(cast = HomeLayout.CastDock.ABOVE_CHAT))
        assertEquals(HomeLayout.CastDock.ABOVE_CHAT, laid.cast); assertEquals(HomeLayout.Share.THIRD, laid.chatShare)
    }

    @Test fun classicPutsAnAboveBarBackAtItsFoot() {
        assertEquals(HomeLayout.CastDock.UNDER_MAP, HomeLayout(cast = HomeLayout.CastDock.ABOVE_MAP).classic().cast)
        assertEquals(HomeLayout.CastDock.UNDER_CHAT, HomeLayout(cast = HomeLayout.CastDock.ABOVE_CHAT).classic().cast)
        for (d in listOf(HomeLayout.CastDock.ABOVE_CHAT, HomeLayout.CastDock.ABOVE_MAP))
            assertFalse("Classic = 1516: never a bar above a column", HomeLayout(cast = d).classicPlace)
        assertTrue(HomeLayout(cast = HomeLayout.CastDock.UNDER_MAP).classicPlace)
    }

    @Test fun theOldSettersKeepTheBarAboveItsColumn() {
        val p = fresh()
        HomeSettings.castDock = HomeLayout.CastDock.ABOVE_CHAT
        assertEquals(HomeLayout.CastDock.ABOVE_CHAT, HomeSettings.layout.cast)
        assertEquals("pre-movable builds read a screen side (the foot)", "LEFT", p.m["castBarSide"])
        // an older route's Right (the map's column with the chat on the left): above the map now, the edge kept
        HomeSettings.castBarSide = CastStripModel.Side.RIGHT
        assertEquals(HomeLayout.CastDock.ABOVE_MAP, HomeSettings.layout.cast)
        assertEquals("RIGHT", p.m["castBarSide"])
        assertEquals(CastStripModel.Side.RIGHT, HomeSettings.castBarSide)
        // Turn on (Both) is Across, at the foot
        HomeSettings.castBarSide = CastStripModel.Side.BOTH
        assertEquals(HomeLayout.CastDock.ACROSS, HomeSettings.layout.cast)
    }

    // ---------------------------------------------------------------- per character (D5)

    @Test fun eachCharacterHasItsOwnAndAllCharactersCopiesIt() {
        val p = fresh()
        HomeLayoutStore.character("Shammy-Realm")
        val mirrored = HomeArrangements.of(HomeArrangements.MIRRORED, HomeSettings.layout)
        HomeLayoutStore.set(mirrored)
        assertTrue(HomeSettings.layout.mirrored)
        // another character: seeded from the default (Classic), not Shammy's
        HomeLayoutStore.character("Huntress-Realm")
        assertFalse(HomeSettings.layout.mirrored)
        assertTrue(p.m.containsKey("homeLayout.Huntress-Realm"))
        // back: Shammy's own; a change heard as a layout change
        var heard = 0
        HomeLayoutStore.onChange { heard++ }
        HomeLayoutStore.character("Shammy-Realm")
        assertTrue(HomeSettings.layout.mirrored); assertEquals(1, heard)
        // All characters: the default and every character
        HomeLayoutStore.set(HomeSettings.layout.copy(chatShare = HomeLayout.Share.TWO_THIRDS), all = true)
        HomeLayoutStore.character("Huntress-Realm")
        assertEquals(HomeLayout.Share.TWO_THIRDS, HomeSettings.layout.chatShare); assertTrue(HomeSettings.layout.mirrored)
        HomeLayoutStore.character("Newbie-Realm")
        assertEquals("a new character starts from the default", HomeLayout.Share.TWO_THIRDS, HomeSettings.layout.chatShare)
        assertEquals(listOf("Huntress-Realm", "Newbie-Realm", "Shammy-Realm"), HomeLayoutStore.characters())
    }

    /** after 1518 (the user's ruling 2026-11-22): the party list's dock is this character's like the rest - written only when it is not
     *  above the chat (a Classic record's text unchanged), copied by "Use this Home for all characters", laid on by key, put back by
     *  Discard, and Reset Home to Classic puts it back above the chat */
    @Test fun thePartyListsDockIsEachCharactersOwn() {
        val p = fresh()
        HomeLayoutStore.character("Korvash-Realm")
        HomeLayoutStore.set(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT))
        val classicText = p.m["homeLayout.Korvash-Realm"] as String
        assertFalse(classicText, classicText.contains("party"))
        val o = HomeLayoutStore.owner()
        val opened = HomeSettings.layout
        val moved = opened.copy(party = HomeLayout.PartyDock.UNDER_MAP)
        HomeSettings.saveLayout(HomeLayoutStore.editSave(o, opened, moved)!!)
        assertTrue((p.m["homeLayout.Korvash-Realm"] as String).contains("\"party\":\"UNDER_MAP\""))
        assertEquals(HomeLayout.PartyDock.UNDER_MAP, HomeSettings.layout.party)
        // another character: its own (Classic's)
        HomeLayoutStore.character("Huntress-Realm")
        assertEquals(HomeLayout.PartyDock.ABOVE_CHAT, HomeSettings.layout.party)
        HomeLayoutStore.character("Korvash-Realm")
        assertEquals(HomeLayout.PartyDock.UNDER_MAP, HomeSettings.layout.party)
        // a change from elsewhere meanwhile is kept; Discard puts only the dock back
        HomeLayoutStore.update { it.copy(castSize = HomeLayout.SlotSize.S) }
        HomeSettings.saveLayout(HomeLayoutStore.editSave(o, moved, opened)!!)
        assertEquals(opened.copy(castSize = HomeLayout.SlotSize.S), HomeSettings.layout)
        assertFalse((p.m["homeLayout.Korvash-Realm"] as String).contains("party"))
        // layOn by key: the dock lands on the real record's other changes
        val base = HomeLayout()
        val laid = HomeLayoutStore.layOn(base.copy(chatShare = HomeLayout.Share.THIRD), base, base.copy(party = HomeLayout.PartyDock.ABOVE_MAP))
        assertEquals(HomeLayout.PartyDock.ABOVE_MAP, laid.party); assertEquals(HomeLayout.Share.THIRD, laid.chatShare)
        // all characters
        HomeLayoutStore.set(HomeSettings.layout.copy(party = HomeLayout.PartyDock.ABOVE_MAP), all = true)
        HomeLayoutStore.character("Huntress-Realm")
        assertEquals(HomeLayout.PartyDock.ABOVE_MAP, HomeSettings.layout.party)
        // Reset Home to Classic
        assertEquals(HomeLayout.PartyDock.ABOVE_CHAT, HomeSettings.layout.classic().party)
    }

    /** the 1514 merge (one character key, CharKey): a character named with no realm is pending - no record read or written under a
     *  partial key ("homeLayout.Name-", "Name-?", "Name-_") nor the default, the previous character's key let go; when the key comes the
     *  real record is read first and only this session's changes are laid on top */
    @Test fun aRealmLessCharacterNeverStoresAndLetsThePreviousGo() {
        val p = fresh()
        HomeLayoutStore.character("Korvash-Realm")
        HomeLayoutStore.set(HomeArrangements.of(HomeArrangements.MIRRORED, HomeSettings.layout))
        assertTrue(HomeSettings.layout.mirrored)
        val defaultBefore = p.m[HomeLayoutStore.KEY_DEFAULT]
        val korvashBefore = p.m["homeLayout.Korvash-Realm"]
        // a switch to a character whose realm is not known: not Korvash's layout, no key of its own
        for (partial in listOf("Thrandil-", "Thrandil-?", "Thrandil-_")) {
            HomeLayoutStore.character(partial, "Thrandil")
            assertNull("no key for $partial", HomeLayoutStore.who)
            assertTrue(HomeLayoutStore.pending)
            assertFalse("the previous character's layout is let go", HomeSettings.layout.mirrored)
        }
        HomeLayoutStore.character(null, "Thrandil")
        assertTrue(HomeLayoutStore.pending); assertNull(HomeLayoutStore.who)
        // an edit while pending: shown, written nowhere
        assertTrue(HomeLayoutStore.update { it.copy(chatShare = HomeLayout.Share.THIRD) })
        assertEquals(HomeLayout.Share.THIRD, HomeSettings.layout.chatShare)
        HomeSettings.rest = HomeRest.ROW                                          // a facade setter too
        assertEquals(HomeRest.ROW, HomeSettings.layout.rest)
        assertTrue("no partial key: ${p.m.keys}", p.m.keys.none { it.startsWith("homeLayout.Thrandil") })
        assertTrue(p.m.keys.filter { it.startsWith(HomeLayoutStore.KEY_PREFIX) }.all { CharKey.isKey(it.removePrefix(HomeLayoutStore.KEY_PREFIX)) })
        assertEquals("the default is not written", defaultBefore, p.m[HomeLayoutStore.KEY_DEFAULT])
        assertEquals("nor the previous character's", korvashBefore, p.m["homeLayout.Korvash-Realm"])
        assertEquals(listOf("Korvash-Realm"), HomeLayoutStore.characters())
        // the key comes: a first login seeds from the default, then the session's edit on top
        HomeLayoutStore.character("Thrandil-Realm")
        assertFalse(HomeLayoutStore.pending); assertEquals("Thrandil-Realm", HomeLayoutStore.who)
        assertEquals(HomeLayout.Share.THIRD, HomeSettings.layout.chatShare); assertEquals(HomeRest.ROW, HomeSettings.layout.rest)
        val t = HomeLayoutJson.parse(p.m["homeLayout.Thrandil-Realm"] as String)
        assertEquals(HomeLayout.Share.THIRD, t.chatShare); assertFalse(t.mirrored)
        // a character that HAS a record: read first, only the changed fields laid on top (its own mirror kept)
        HomeLayoutStore.character(null, "Korvash")
        assertFalse(HomeSettings.layout.mirrored)
        HomeLayoutStore.update { it.copy(xpBar = !it.xpBar) }
        HomeLayoutStore.character("Korvash-Realm")
        assertTrue("Korvash's own record read before the write", HomeSettings.layout.mirrored)
        assertEquals(!HomeLayout().xpBar, HomeSettings.layout.xpBar)
        // an edit held for one name never goes to another
        HomeLayoutStore.character(null, "Thrandil")
        HomeLayoutStore.update { it.copy(card = HomeLayout.CardDock.OFF) }
        HomeLayoutStore.character(null, "Korvash")
        HomeLayoutStore.character("Korvash-Realm")
        assertTrue(HomeSettings.layout.card != HomeLayout.CardDock.OFF)
        // the 1515 merge's review: one name is one character - Thrandil's held edit waits (kept by name) for Thrandil's own key
        HomeLayoutStore.character("Thrandil-Realm")
        assertEquals("the held edit reaches its own name's key", HomeLayout.CardDock.OFF, HomeSettings.layout.card)
        assertNull(HomeLayoutStore.heldOf("Thrandil"))
        HomeLayoutStore.character("Korvash-Realm")
        assertTrue("and never another's", HomeSettings.layout.card != HomeLayout.CardDock.OFF)
        // a partial key string is never a record key, never listed
        HomeLayoutStore.character("Korvash-?")
        assertNull(HomeLayoutStore.who)
        assertFalse(p.m.containsKey("homeLayout.Korvash-?"))
        // the reset keys: the record keys are character keys (ResetPlan's per-character prefix), the device keys exact
        assertEquals(ResetPlan.Kind.LAYOUTS, ResetPlan.ruleOf("home", "homeLayout.Korvash-Realm")?.kind)
        assertEquals(ResetPlan.Kind.LAYOUTS, ResetPlan.ruleOf("home", HomeLayoutStore.KEY_DEFAULT)?.kind)
        assertEquals(ResetPlan.Kind.LAYOUTS, ResetPlan.ruleOf("home", HomeLayoutStore.KEY_SLOTS_LEGACY)?.kind)
        assertTrue("the device's keys: All characters only", ResetPlan.ruleOf("home", HomeLayoutStore.KEY_SLOTS_LEGACY)!!.allOnly &&
            ResetPlan.ruleOf("home", HomeLayoutStore.KEY_DEFAULT)!!.allOnly)
        assertTrue(ResetPlan.prefKeys("home", setOf("homeLayout.Korvash-Realm", "homeLayout.Thrandil-Realm"), setOf(ResetPlan.Kind.LAYOUTS),
            "Korvash-Realm", false) == setOf("homeLayout.Korvash-Realm"))
        assertTrue(ResetPlan.isCharKey("Korvash-Realm")); assertFalse(ResetPlan.isCharKey("Korvash-")); assertFalse(ResetPlan.isCharKey("Korvash-?"))
    }

    /** fix round 1 (P1 / P2 of the review): edit mode's draft is saved only to the record it was read under - the same owner as it is;
     *  the pending name's key that arrived: only the session's changes on the real record; another character: nothing written */
    @Test fun anEditIsSavedOnlyToTheRecordItWasReadUnder() {
        val p = fresh()
        var heard = 0
        HomeLayoutStore.onOwner { heard++ }
        // Korvash-Realm keeps chat RIGHT, rest ROW; Thrandil-Realm its card OFF, bar ACROSS
        HomeLayoutStore.character("Korvash-Realm")
        HomeLayoutStore.set(HomeLayout(chatSide = HomeLayout.Side.RIGHT, rest = HomeRest.ROW))
        HomeLayoutStore.character("Thrandil-Realm")
        HomeLayoutStore.set(HomeLayout(card = HomeLayout.CardDock.OFF, cast = HomeLayout.CastDock.ACROSS))
        val thrandil = p.m["homeLayout.Thrandil-Realm"]
        // P1: Korvash named before his realm (pending): edit mode opens on the default, changes only the share, then the key arrives
        HomeLayoutStore.character(null, "Korvash")
        val n0 = heard
        val o1 = HomeLayoutStore.owner()
        assertEquals(HomeLayoutStore.Owner(null, "Korvash"), o1)
        val base1 = HomeSettings.layout
        val draft1 = base1.copy(chatShare = HomeLayout.Share.THIRD)
        HomeLayoutStore.character("Korvash-Realm")
        assertTrue("the owner change is heard (edit mode re-checks)", heard > n0)
        assertEquals(HomeLayoutStore.EditSave.LAY_ON, HomeLayoutStore.editSaveFor(o1))
        val rec1 = HomeLayoutStore.editSave(o1, base1, draft1)!!
        HomeSettings.saveLayout(rec1)
        val k = HomeLayoutJson.parse(p.m["homeLayout.Korvash-Realm"] as String)
        assertEquals("the session's change", HomeLayout.Share.THIRD, k.chatShare)
        assertEquals("Korvash's own chat side kept", HomeLayout.Side.RIGHT, k.chatSide)
        assertEquals("Korvash's own rest kept", HomeRest.ROW, k.rest)
        // P2: edit mode opens on Korvash, the game switches to Thrandil, then Save: refused, Thrandil's record untouched
        val o2 = HomeLayoutStore.owner()
        assertEquals(HomeLayoutStore.EditSave.SAME, HomeLayoutStore.editSaveFor(o2))
        assertEquals("the same owner: the draft as it is", HomeLayout(xpBar = false), HomeLayoutStore.editSave(o2, k, HomeLayout(xpBar = false)))
        val draft2 = k.copy(card = HomeLayout.CardDock.ON_MAP, cast = HomeLayout.CastDock.OFF)
        HomeLayoutStore.character("Thrandil-Realm")
        assertEquals(HomeLayoutStore.EditSave.NONE, HomeLayoutStore.editSaveFor(o2))
        assertNull(HomeLayoutStore.editSave(o2, k, draft2))
        assertEquals("Thrandil's record never written", thrandil, p.m["homeLayout.Thrandil-Realm"])
        // the key let go (Korvash-Realm -> Korvash pending again) and the default (no name) -> a character: neither is the same record
        HomeLayoutStore.character("Korvash-Realm")
        val o3 = HomeLayoutStore.owner()
        HomeLayoutStore.character(null, "Korvash")
        assertEquals(HomeLayoutStore.EditSave.NONE, HomeLayoutStore.editSaveFor(o3))
        HomeLayoutStore.character(null)
        val o4 = HomeLayoutStore.owner()
        assertEquals(HomeLayoutStore.Owner(null, null), o4)
        HomeLayoutStore.character("Korvash-Realm")
        assertEquals(HomeLayoutStore.EditSave.NONE, HomeLayoutStore.editSaveFor(o4))
        // another pending name's key is not this one's
        assertEquals(HomeLayoutStore.EditSave.NONE, HomeLayoutStore.editSaveFor(HomeLayoutStore.Owner(null, "Thrandil")))
        // the same character again: no owner change heard
        val n1 = heard
        HomeLayoutStore.character("Korvash-Realm")
        assertEquals(n1, heard)
    }

    /** fix round 2: the SAME owner's Save lays only the session's own changes on the record as it is NOW - a change made while edit mode
     *  was parked (Settings > Bottom screen, the Controller room's Turn on) is kept, never the whole draft written over it */
    @Test fun aSameOwnerSaveKeepsWhatChangedWhileParked() {
        val p = fresh()
        HomeLayoutStore.character("Korvash-Realm")
        HomeLayoutStore.set(HomeLayout(cast = HomeLayout.CastDock.OFF, chatShare = HomeLayout.Share.HALF))
        val o = HomeLayoutStore.owner()
        val base = HomeSettings.layout
        val draft = base.copy(chatShare = HomeLayout.Share.THIRD)                  // the session: the chat's width only
        // parked meanwhile: the fight's dock and the bar turned on elsewhere (the record written directly)
        HomeLayoutStore.set(HomeLayoutStore.active().copy(fight = HomeLayout.FightBand.TOP))
        HomeLayoutStore.update { it.copy(cast = HomeLayout.CastDock.UNDER_CHAT) }
        assertEquals(HomeLayoutStore.EditSave.SAME, HomeLayoutStore.editSaveFor(o))
        val rec = HomeLayoutStore.editSave(o, base, draft)!!
        HomeSettings.saveLayout(rec)
        val k = HomeLayoutJson.parse(p.m["homeLayout.Korvash-Realm"] as String)
        assertEquals("the session's change", HomeLayout.Share.THIRD, k.chatShare)
        assertEquals("the fight dock set while parked: kept", HomeLayout.FightBand.TOP, k.fight)
        assertEquals("the bar turned on while parked: kept", HomeLayout.CastDock.UNDER_CHAT, k.cast)
        // the session changed the same field too: its own value wins
        val base2 = HomeSettings.layout
        HomeLayoutStore.update { it.copy(cast = HomeLayout.CastDock.OFF) }
        val r2 = HomeLayoutStore.editSave(o, base2, base2.copy(cast = HomeLayout.CastDock.ACROSS))!!
        assertEquals(HomeLayout.CastDock.ACROSS, r2.cast)
        // nothing changed underneath: the draft as it is (name and all)
        val base3 = HomeSettings.layout
        val d3 = HomeArrangements.of(HomeArrangements.MIRRORED, base3)
        assertEquals(d3, HomeLayoutStore.editSave(o, base3, d3))
    }

    /** 1518, the autosave and Discard through editSave: each change written on the record as it is (a pending name's held, a late key's
     *  laid on); Discard's inverse write puts the opening record back over the session's own changes only */
    @Test fun theAutosaveAndDiscardGoThroughEditSave() {
        val p = fresh()
        HomeLayoutStore.character("Korvash-Realm")
        HomeLayoutStore.set(HomeLayout(cast = HomeLayout.CastDock.UNDER_MAP))
        val o = HomeLayoutStore.owner()
        val opened = HomeSettings.layout
        // two settled changes, each written as it is made (the base: the draft as last written)
        val d1 = opened.swapped()
        HomeSettings.saveLayout(HomeLayoutStore.editSave(o, opened, d1)!!)
        assertTrue(HomeLayoutJson.parse(p.m["homeLayout.Korvash-Realm"] as String).mirrored)
        val d2 = d1.copy(chatShare = HomeLayout.Share.THIRD)
        HomeSettings.saveLayout(HomeLayoutStore.editSave(o, d1, d2)!!)
        // a change from elsewhere meanwhile (Settings while parked): kept by Discard
        HomeLayoutStore.update { it.copy(castSize = HomeLayout.SlotSize.S) }
        HomeSettings.saveLayout(HomeLayoutStore.editSave(o, d2, opened)!!)                  // Discard
        assertEquals(opened.copy(castSize = HomeLayout.SlotSize.S), HomeSettings.layout)
        // pending: held (never written under a partial key or the default), the late key gets it laid on its real record
        HomeLayoutStore.character("Thrandil-Realm")
        HomeLayoutStore.set(HomeLayout(rest = HomeRest.ROW))
        HomeLayoutStore.character(null, "Thrandil")
        val op = HomeLayoutStore.owner()
        val b = HomeSettings.layout
        val dp = b.copy(chatShare = HomeLayout.Share.TWO_THIRDS)
        HomeSettings.saveLayout(HomeLayoutStore.editSave(op, b, dp)!!)
        assertEquals(HomeLayout.Share.TWO_THIRDS, HomeSettings.layout.chatShare)
        assertTrue(p.m.keys.none { it.startsWith("homeLayout.Thrandil") && it != "homeLayout.Thrandil-Realm" })
        HomeLayoutStore.character("Thrandil-Realm")
        val t = HomeSettings.layout
        assertEquals("the held change on the real record", HomeLayout.Share.TWO_THIRDS, t.chatShare)
        assertEquals("its own rest kept", HomeRest.ROW, t.rest)
    }

    /** 1518 (F1): a session opened while the name was pending (the default shown), its key arriving mid-edit: Discard puts back the
     *  character's REAL record (plus the held change of before the session) - never the pending default's values over the fields the
     *  session touched. Both orders: the view's rebase then Discard, and a Discard before the rebase (a lift held when the key came) */
    /** HomeView's edit session as the store sees it (editOpen / editPersist / editRebase / editEnd's Discard / editOwnerCheck's
     *  snapshot of the late key): the draft's owner, the record Discard puts back, the draft as last saved */
    private class Sess {
        var o = HomeLayoutStore.owner()
        var saved = HomeSettings.layout
        var persisted = saved
        var draft = saved
        var late: HomeLayoutStore.Late? = null
        /** editOwnerCheck: the owner changed (the key came) */
        fun ownerCheck() { if (HomeLayoutStore.editSaveFor(o) == HomeLayoutStore.EditSave.LAY_ON && late == null) late = HomeLayoutStore.lateFor(o) }
        /** editAdopt: the late key taken (before any write under it); the Discard record, null = no late key */
        private fun adopt(): HomeLayout? {
            if (HomeLayoutStore.editSaveFor(o) != HomeLayoutStore.EditSave.LAY_ON) return null
            val back = HomeLayoutStore.lateAdopt(o, late, saved, persisted)
            saved = back; o = HomeLayoutStore.owner(); late = null
            return back
        }
        /** editPersist (a settled change, or the drop's) */
        fun persist() {
            if (draft == persisted) return
            val l = adopt()
            HomeSettings.saveLayout(HomeLayoutStore.editSave(o, persisted, draft)!!); persisted = draft
            if (l != null) { val now = HomeLayoutStore.active(); draft = now; saved = l; persisted = now }
        }
        /** editRebase (settled when the key came, or Home again after a park) */
        fun rebase() {
            val l = adopt(); val now = HomeLayoutStore.active()
            if (l == null && now == persisted) return
            saved = l ?: HomeLayoutStore.layOn(saved, persisted, now); draft = now; persisted = now
        }
        /** Discard */
        fun discard() {
            adopt()?.let { l -> val now = HomeLayoutStore.active(); saved = l; draft = now; persisted = now }
            HomeSettings.saveLayout(HomeLayoutStore.editSave(o, persisted, saved)!!)
        }
    }

    /**
     * 1518 (F1, and the checkers' F1-a / F1-b / F1-c): a session opened while the name is pending, the key arriving mid-edit, then Discard:
     * the character's OWN record comes back with only the held changes of before the session laid on - never the pending default's
     * values. Whether the session's change was saved before the key (held, carried) or not yet (lifted: written at the drop, after the
     * key); rebased before Discard or not; a held change of before the session that the session undid (on a field the character has its
     * own value for); a Settings reset (not Layouts) between the key and the session taking it
     */
    @Test fun aLateKeysDiscardPutsBackTheCharactersOwnRecord() {
        val real = HomeLayout(chatSide = HomeLayout.Side.RIGHT, chatShare = HomeLayout.Share.TWO_THIRDS, rest = HomeRest.ROW)
        for (savedFirst in listOf(true, false)) for (rebaseFirst in listOf(true, false)) for (before in listOf("none", "xp", "width undone"))
        for (reset in listOf(false, true)) for (snapshot in listOf(true, false)) {
            val what = "saved before the key $savedFirst, rebase first $rebaseFirst, held before the session $before, a reset $reset, " +
                "the session's snapshot $snapshot"
            val p = fresh()
            HomeLayoutStore.character("Korvash-Realm")
            HomeLayoutStore.set(real)
            HomeLayoutStore.character(null, "Korvash")                                     // pending: the default shows
            val d = HomeSettings.layout
            assertEquals(HomeLayout.Share.HALF, d.chatShare)
            when (before) {                                                                // held before edit mode opened
                "xp" -> HomeLayoutStore.update { it.copy(xpBar = !it.xpBar) }
                "width undone" -> HomeLayoutStore.update { it.copy(chatShare = HomeLayout.Share.THIRD) }
            }
            val s = Sess()
            // the session changes the width (a field the character's record has its own value for): the default's back again when the
            // held change was the width (the session undid it), else a third
            s.draft = s.saved.copy(chatShare = if (before == "width undone") d.chatShare else HomeLayout.Share.THIRD)
            if (savedFirst) s.persist()                                                    // settled: saved at once (held)
            HomeLayoutStore.character("Korvash-Realm")                                     // the key arrives mid-edit
            if (snapshot) s.ownerCheck()
            if (reset) HomeSettings.resetDone()                                            // a Settings reset: edit mode waits on
            assertEquals(what, HomeLayoutStore.EditSave.LAY_ON, HomeLayoutStore.editSaveFor(s.o))
            if (!savedFirst) s.persist()                                                   // the drop (or a park's save) under the key
            if (rebaseFirst) s.rebase()
            if (!savedFirst || rebaseFirst) {                                              // taken (else: Discard takes it)
                assertEquals(what, HomeLayoutStore.EditSave.SAME, HomeLayoutStore.editSaveFor(s.o))
                assertEquals(what, s.draft, HomeSettings.layout)                           // the draft is his record as written
            }
            s.discard()
            val want = when (before) { "xp" -> real.copy(xpBar = !real.xpBar); "width undone" -> real.copy(chatShare = HomeLayout.Share.THIRD); else -> real }
            assertEquals(what, want, HomeLayoutJson.parse(p.m["homeLayout.Korvash-Realm"] as String))
            assertEquals(what, want, HomeSettings.layout)
        }
        // a change made under the key after it came (Settings > Bottom screen while parked): kept by Discard, only the session's undone
        run {
            val p = fresh()
            HomeLayoutStore.character("Korvash-Realm"); HomeLayoutStore.set(real)
            HomeLayoutStore.character(null, "Korvash")
            val s = Sess()
            s.draft = s.saved.copy(chatShare = HomeLayout.Share.THIRD)
            HomeLayoutStore.character("Korvash-Realm"); s.ownerCheck()
            HomeLayoutStore.update { it.copy(rest = HomeRest.DIAMONDS) }
            s.persist(); s.discard()
            assertEquals(real.copy(rest = HomeRest.DIAMONDS), HomeLayoutJson.parse(p.m["homeLayout.Korvash-Realm"] as String))
        }
        // not a late key (the same owner, or nothing carried): the opening record as it is
        fresh()
        HomeLayoutStore.character("Korvash-Realm")
        val o = HomeLayoutStore.owner()
        assertEquals(HomeLayout(xpBar = false), HomeLayoutStore.lateSaved(o, HomeLayout(xpBar = false)))
        assertNull(HomeLayoutStore.lateFor(o))
    }

    // ---------------------------------------------------------------- the DS toolbar follows the swap

    private val normal = mapOf(1 to "chat", 2 to "radial", 3 to "map", 4 to "home")
    private val mirrored = mapOf(1 to "map", 2 to "radial", 3 to "chat", 4 to "home")

    @Test fun mirrorResendsUntilTheAddonSaysIt() {
        assertEquals(0, ToolbarMirror.bitOf(normal)); assertEquals(1, ToolbarMirror.bitOf(mirrored)); assertNull(ToolbarMirror.bitOf(emptyMap()))
        assertEquals(ToolbarMirror.Act.Matched, ToolbarMirror.decide(0, normal, false, null))
        assertEquals(ToolbarMirror.Act.Send(true), ToolbarMirror.decide(1, normal, false, null))
        assertEquals("queued in a fight", ToolbarMirror.Act.Wait, ToolbarMirror.decide(1, normal, true, null))
        assertEquals("just sent", ToolbarMirror.Act.Wait, ToolbarMirror.decide(1, normal, false, 1_000L))
        assertEquals("sent again", ToolbarMirror.Act.Send(true), ToolbarMirror.decide(1, normal, false, ToolbarMirror.RESEND_MS))
        assertEquals("no padmap yet", ToolbarMirror.Act.Wait, ToolbarMirror.decide(1, null, false, null))
        assertEquals(ToolbarMirror.Act.Send(false), ToolbarMirror.decide(0, mirrored, false, null))
        // padtab 39, 38, 37 then 20 / 21 (Book b76: off the sticky realm question's 9-19)
        assertEquals(listOf("padtab" to 39, "padtab" to 38, "padtab" to 37, "padtab" to 21), ToolbarMirror.cells(true))
        assertEquals(20, ToolbarMirror.cells(false).last().second)
        // the toast: a change only, never the run's first word
        assertFalse(ToolbarMirror.toastFor(null, 1)); assertTrue(ToolbarMirror.toastFor(0, 1)); assertFalse(ToolbarMirror.toastFor(1, 1))
        assertEquals(listOf("D-pad", "Map", "Chat"), ToolbarMirror.toastWords(mirrored))
        assertEquals(0f, ToolbarMirror.toastAlpha(0)); assertEquals(1f, ToolbarMirror.toastAlpha(500)); assertEquals(0f, ToolbarMirror.toastAlpha(ToolbarMirror.TOAST_MS))
        assertEquals("Left: Map     Right: Chat", ToolbarMirror.promptLine(mirrored))
        assertEquals("Left: Chat     Right: Map", ToolbarMirror.promptLine(null))
    }

    @Test fun page3CodesNeverCollide() {
        val used = HashMap<Int, String>()
        for ((what, r) in PadMapModel.PAGE3_CODES) for (c in r) {
            val was = used.put(c, what)
            assertNull("code $c is both $was and $what", was)
            assertTrue("a pad column", c in 0..39)
        }
        assertEquals(PadMapModel.P3_MIRROR_OFF..PadMapModel.P3_MIRROR_ON, PadMapModel.PAGE3_CODES["toolbar mirror"])
        // the 1515 merge: the key question's 9 / 10-19 are KeyPick's, never Home's
        assertEquals(KeyPick.NEW_CODE..(KeyPick.FIRST_CODE + KeyPick.MAX - 1), PadMapModel.PAGE3_CODES["key question"])
        assertEquals(KeyPick.NEW_CODE, PadMapModel.P3_KEY_NEW); assertEquals(KeyPick.FIRST_CODE + KeyPick.MAX - 1, PadMapModel.P3_KEY_LAST)
        assertEquals(KeyPick.NEW_CODE, KeyPick.cells(null)!!.last().second)
        assertEquals(KeyPick.FIRST_CODE + KeyPick.MAX - 1, KeyPick.cells(KeyPick.MAX)!!.last().second)
        // Book b76 handles exactly these (the add-on's own table)
        val book = java.io.File(addons(), "WowCompanionSpells/Book.lua").readText()
        assertTrue(book.contains("for k = 0, 1 do P3[20 + k] = function()") && book.contains("pm.dpadMirror, k == 1"))
        assertTrue(book.contains("P3[9] = function() if type(WC.charKeyPick)") && book.contains("for k = 1, 10 do P3[9 + k] = function() if type(WC.charKeyPick)"))
        assertTrue(book.contains("for k = 0, 3 do P3[k]") && book.contains("for k = 1, 5 do P3[3 + k]"))
    }

    @Test fun holdToTalkFollowsTheChatsKey() {
        assertEquals(HoldTalk.LEFT_KEY, HoldTalk.chatKey(null, true, normal, false))
        assertEquals(HoldTalk.RIGHT_KEY, HoldTalk.chatKey(null, true, mirrored, false))
        assertEquals("the router said nothing: its default", HoldTalk.LEFT_KEY, HoldTalk.chatKey(null, true, null, false))
        assertNull("the toolbar off and no DS Chat command", HoldTalk.chatKey(null, false, mirrored, false))
        assertTrue(HoldTalk.leftIsChat(null, true, normal, false)); assertFalse(HoldTalk.leftIsChat(null, true, mirrored, false))
        assertTrue(HoldTalk.chatArrowRight(mirrored)); assertFalse(HoldTalk.chatArrowRight(normal)); assertFalse(HoldTalk.chatArrowRight(null))
    }

    @Test fun theControllerRoomsToolbarIsByWord() {
        assertEquals(listOf("chat", "radial", "map", "home"), (1..4).map { toolWord(normal, it) })
        assertEquals(listOf("map", "radial", "chat", "home"), (1..4).map { toolWord(mirrored, it) })
        assertEquals("no word: the router's default", "chat", toolWord(null, 1))
    }

    /** fix round 3 (finding 2), REVISED by the 1515 merge (the sticky key; the user's ruling: one name is one character, the realm is
     *  only where its settings live): a realm-less session's edit is held for its NAME and lands on the key the add-on resolves for that
     *  name - whatever other realms the prefs or the device census know (fix round 3 dropped it then: exactly the add-on's ask case) -
     *  with only its changed fields; another name's key never gets it (HomeKeyPickTest: the question's pick and "New character") */
    @Test fun aHeldEditFollowsTheAddOnsResolvedKeyForItsName() {
        val p = fresh()
        HomeLayoutStore.character("Korvash-Silvermoon")                       // one name, settings under two realms
        HomeLayoutStore.set(HomeLayout(card = HomeLayout.CardDock.OFF))
        HomeLayoutStore.character("Korvash-Stormreach")
        HomeLayoutStore.set(HomeLayout(rest = HomeRest.ROW))
        val silver = p.m["homeLayout.Korvash-Silvermoon"]
        HomeLayoutStore.character(null, "Korvash")                            // the add-on asks: no key
        assertTrue(HomeLayoutStore.update { it.copy(chatSide = HomeLayout.Side.RIGHT) })
        val o = HomeLayoutStore.owner()
        HomeLayoutStore.character("Korvash-Stormreach")                       // the add-on resolves Korvash to Stormreach
        val s = HomeLayoutJson.parse(p.m["homeLayout.Korvash-Stormreach"] as String)
        assertTrue("the held edit lands on the resolved key", s.mirrored)
        assertEquals("its own fields kept", HomeRest.ROW, s.rest)
        assertEquals("the other realm's record untouched", silver, p.m["homeLayout.Korvash-Silvermoon"])
        assertEquals("edit mode opened while pending: saved onto the same name's resolved key", HomeLayoutStore.EditSave.LAY_ON, HomeLayoutStore.editSaveFor(o))
        // the device census knowing another realm of the name changes nothing (one name = one character)
        val q = fresh()
        HomeLayoutStore.character("Thrandil-Realm")
        RealmFix.census = { RealmFix.Census(stored = setOf("Realm", "Other")) }
        HomeLayoutStore.character(null, "Thrandil")
        HomeLayoutStore.update { it.copy(chatShare = HomeLayout.Share.THIRD) }
        val o2 = HomeLayoutStore.owner()
        HomeLayoutStore.character("Thrandil-Realm")
        assertEquals(HomeLayout.Share.THIRD, HomeLayoutJson.parse(q.m["homeLayout.Thrandil-Realm"] as String).chatShare)
        assertEquals(HomeLayoutStore.EditSave.LAY_ON, HomeLayoutStore.editSaveFor(o2))
        assertTrue(HomeLayoutStore.carryOk("Thrandil-Realm", "Thrandil"))
        assertEquals("another name's draft: never", HomeLayoutStore.EditSave.NONE, HomeLayoutStore.editSaveFor(o2.copy(name = "Korvash")))
    }

    /** fix round 3 (finding 3): a change made before ANY character is known (the app's start, the login screen) writes the default as
     *  before AND reaches the first character's own record (only the changed fields: its own others kept), never silently lost */
    @Test fun aChangeBeforeAnyCharacterReachesTheFirstOne() {
        val p = fresh()
        HomeLayoutStore.character("Korvash-Realm")
        HomeLayoutStore.set(HomeArrangements.of(HomeArrangements.MIRRORED, HomeSettings.layout))
        // the app starts again (the same prefs): no character yet, Home on the default
        fresh(p)
        assertNull(HomeLayoutStore.who); assertFalse(HomeLayoutStore.pending)
        HomeSettings.combatGrow = false                                       // Settings > Bottom screen: Combat layout = Same size
        assertEquals("the default written (the seed of a first login)", HomeLayout.FightBand.SAME,
            HomeLayoutJson.parse(p.m[HomeLayoutStore.KEY_DEFAULT] as String).fight)
        HomeLayoutStore.character("Korvash-Realm")                            // the main logs in: it HAS a record
        assertEquals("the change reached him", HomeLayout.FightBand.SAME, HomeSettings.layout.fight)
        assertTrue("his own mirror kept", HomeSettings.layout.mirrored)
        assertEquals(HomeLayout.FightBand.SAME, HomeLayoutJson.parse(p.m["homeLayout.Korvash-Realm"] as String).fight)
        // the next character gets nothing more (held once): the default seeds it as always
        HomeLayoutStore.character("Thrandil-Realm")
        assertFalse(HomeSettings.layout.mirrored)
        // no character, then a pending name adopts the change, then its key: carried (one realm known)
        fresh(p)
        HomeLayoutStore.update { it.copy(xpBar = false) }
        HomeLayoutStore.character(null, "Korvash")
        HomeLayoutStore.character("Korvash-Realm")
        assertFalse(HomeLayoutJson.parse(p.m["homeLayout.Korvash-Realm"] as String).xpBar)
        // no character, a pending name, then ANOTHER name: let go
        fresh(p)
        HomeLayoutStore.update { it.copy(card = HomeLayout.CardDock.OFF) }
        HomeLayoutStore.character(null, "Korvash")
        HomeLayoutStore.character(null, "Thrandil")
        HomeLayoutStore.character("Thrandil-Realm")
        assertTrue(HomeLayoutJson.parse(p.m["homeLayout.Thrandil-Realm"] as String).card != HomeLayout.CardDock.OFF)
    }

    // ---------------------------------------------------------------- reset

    @Test fun layoutsResetTheNewKeys() {
        val me = "Shammy-Realm"; val other = "Hunt-Realm"
        val keys = setOf("homeLayout.$me", "homeLayout.$other", HomeLayoutStore.KEY_DEFAULT, HomeLayoutStore.KEY_SLOTS_LEGACY, HomeLayoutStore.KEY_MIGRATED)
        val L = setOf(ResetPlan.Kind.LAYOUTS)
        // fix round 3: "This character" takes its own record only - the three "Mine" slots and the first-login seed are the device's
        assertEquals(setOf("homeLayout.$me"), ResetPlan.prefKeys("home", keys, L, me, false))
        assertEquals(setOf("homeLayout.$me"), ResetPlan.prefKeys("home", keys, ResetPlan.Kind.values().toSet(), me, false))
        assertEquals(setOf("homeLayout.$me", "homeLayout.$other", HomeLayoutStore.KEY_DEFAULT, HomeLayoutStore.KEY_SLOTS_LEGACY),
            ResetPlan.prefKeys("home", keys, L, me, true))
        assertTrue("never by Settings / Skills", ResetPlan.prefKeys("home", keys, setOf(ResetPlan.Kind.SETTINGS, ResetPlan.Kind.SKILLS), me, true).isEmpty())
        assertTrue("the migration mark: never", ResetPlan.prefKeys("home", keys, ResetPlan.Kind.values().toSet(), me, true).none { it == HomeLayoutStore.KEY_MIGRATED })
        val rbm = ResetPlan.SV_KEYS.first { it.top == "rbm" }
        assertEquals("WowCompanionSpellsDB", rbm.variable); assertEquals(ResetPlan.Kind.LAYOUTS, rbm.kind); assertTrue(rbm.perChar)
        assertNull("a reset = mirror 0 (no entry)", rbm.default)
        // after a reset (the keys gone, the mark kept) Home is Classic again
        val p = fresh(Prefs(hashMapOf(HomeLayoutStore.KEY_MIGRATED to true)))
        assertEquals(HomeLayout(), HomeSettings.layout)
        assertTrue(p.m.size >= 1)
    }

    private fun addons(): java.io.File = listOf(java.io.File("../companion/app/src/main/assets/companion/addons"),
        java.io.File("companion/app/src/main/assets/companion/addons")).first { it.isDirectory }

    class Prefs(val m: HashMap<String, Any?> = HashMap()) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = m
        override fun getString(k: String?, d: String?) = m[k] as? String ?: d
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(k: String?, d: MutableSet<String>?) = m[k] as? MutableSet<String> ?: d
        override fun getInt(k: String?, d: Int) = m[k] as? Int ?: d
        override fun getLong(k: String?, d: Long) = m[k] as? Long ?: d
        override fun getFloat(k: String?, d: Float) = m[k] as? Float ?: d
        override fun getBoolean(k: String?, d: Boolean) = m[k] as? Boolean ?: d
        override fun contains(k: String?) = m.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val put = HashMap<String, Any?>(); val gone = HashSet<String>()
            override fun putString(k: String, v: String?) = apply { put[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = apply { put[k] = v }
            override fun putInt(k: String, v: Int) = apply { put[k] = v }
            override fun putLong(k: String, v: Long) = apply { put[k] = v }
            override fun putFloat(k: String, v: Float) = apply { put[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { put[k] = v }
            override fun remove(k: String) = apply { gone.add(k) }
            override fun clear() = apply { gone.addAll(m.keys) }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { for (k in gone) m.remove(k); m.putAll(put) }
        }
    }
}
