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
 * into Classic; the presets and their names; the per-character store (D5) with its default, first-login seed, "All characters" and the
 * three device slots; the old setters as facades; the toolbar mirror's resend rule; hold to talk's key after a swap; the Controller room's
 * toolbar words; the page-3 code registry; the reset plan's new keys. Pure.
 */
class HomeLayoutStoreTest {
    @After fun down() { HomeSettings.startForTest(null) }

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
        assertEquals(listOf<HomeLayout?>(null, null, null), HomeLayoutJson.slots("nope"))
        val s = listOf(HomeLayout(name = "1"), null, HomeLayout(name = "3", chatShare = HomeLayout.Share.THIRD))
        assertEquals(s, HomeLayoutJson.slots(HomeLayoutJson.slotsJson(s)))
    }

    // ---------------------------------------------------------------- migration

    @Test fun todaysKeysBecomeClassic() {
        for (rest in HomeRest.values()) for (grow in listOf(true, false)) for (over in listOf(true, false)) for (side in CastStripModel.Side.values()) {
            val l = HomeLayoutMigration.fromToday(rest, grow, over, side)
            assertEquals(HomePresets.CLASSIC, l.name)
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

    // ---------------------------------------------------------------- presets

    @Test fun presetsNameThemselves() {
        val base = HomeLayout(cast = HomeLayout.CastDock.ACROSS, rest = HomeRest.DIAMONDS)
        for (p in HomePresets.OFFERED) assertEquals(p.id, HomePresets.nameOf(p.apply(base)))
        assertEquals(listOf("Classic", "Mirrored", "Big map", "Big chat", "Compact", "Fighter"), HomePresets.OFFERED.map { it.id })
        assertTrue("phase 3: Fighter is offered (the player's own opt-in to the top-screen bar, D1)", HomePresets.byId(HomePresets.FIGHTER)!!.available)
        assertEquals(HomePresets.FIGHTER, HomePresets.nameOf(HomePresets.byId(HomePresets.FIGHTER)!!.apply(base)))
        // Classic keeps the player's bar dock and Grow / Same size, and is the way back from the others (SPEC section 9: Diamonds, slots
        // L; Fighter's Split / Top screen undone); Mirrored is today's swapped with the bar under the chat
        val mine = HomeLayout(cast = HomeLayout.CastDock.UNDER_MAP, rest = HomeRest.ROW, fight = HomeLayout.FightBand.SAME, castSize = HomeLayout.SlotSize.M)
        val c = HomePresets.byId(HomePresets.CLASSIC)!!.apply(mine.copy(chatSide = HomeLayout.Side.RIGHT))
        assertEquals(mine.copy(name = HomePresets.CLASSIC, rest = HomeRest.DIAMONDS, castSize = HomeLayout.SlotSize.L), c)
        val m = HomePresets.byId(HomePresets.MIRRORED)!!.apply(HomeLayout())
        assertTrue(m.mirrored); assertEquals(HomeLayout.CastDock.UNDER_CHAT, m.cast)
        assertEquals(HomePresets.CUSTOM, HomePresets.nameOf(HomeLayout(chatSide = HomeLayout.Side.RIGHT, card = HomeLayout.CardDock.OFF)))
        assertEquals(true, HomePresets.byId(HomePresets.COMPACT)!!.folded)
    }

    // ---------------------------------------------------------------- per character (D5)

    @Test fun eachCharacterHasItsOwnAndAllCharactersCopiesIt() {
        val p = fresh()
        HomeLayoutStore.character("Shammy-Realm")
        val mirrored = HomePresets.byId(HomePresets.MIRRORED)!!.apply(HomeSettings.layout)
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

    /** the 1514 merge (one character key, CharKey): a character named with no realm is pending - no record read or written under a
     *  partial key ("homeLayout.Name-", "Name-?", "Name-_") nor the default, the previous character's key let go; when the key comes the
     *  real record is read first and only this session's changes are laid on top */
    @Test fun aRealmLessCharacterNeverStoresAndLetsThePreviousGo() {
        val p = fresh()
        HomeLayoutStore.character("Korvash-Realm")
        HomeLayoutStore.set(HomePresets.byId(HomePresets.MIRRORED)!!.apply(HomeSettings.layout))
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
        HomeLayoutStore.character("Thrandil-Realm")
        assertTrue("the held edit went with the switch", HomeSettings.layout.card != HomeLayout.CardDock.OFF)
        // a partial key string is never a record key, never listed
        HomeLayoutStore.character("Korvash-?")
        assertNull(HomeLayoutStore.who)
        assertFalse(p.m.containsKey("homeLayout.Korvash-?"))
        // the reset keys: the record keys are character keys (ResetPlan's per-character prefix), the device keys exact
        assertEquals(ResetPlan.Kind.LAYOUTS, ResetPlan.ruleOf("home", "homeLayout.Korvash-Realm")?.kind)
        assertEquals(ResetPlan.Kind.LAYOUTS, ResetPlan.ruleOf("home", HomeLayoutStore.KEY_DEFAULT)?.kind)
        assertEquals(ResetPlan.Kind.LAYOUTS, ResetPlan.ruleOf("home", HomeLayoutStore.KEY_SLOTS)?.kind)
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

    @Test fun threeDeviceSlots() {
        fresh()
        assertEquals(listOf<HomeLayout?>(null, null, null), HomeLayoutStore.slots())
        val big = HomePresets.byId(HomePresets.BIG_MAP)!!.apply(HomeLayout())
        HomeLayoutStore.saveSlot(1, big.copy(chatSide = HomeLayout.Side.RIGHT))
        HomeLayoutStore.saveSlot(7, big)                                      // no such slot
        val s = HomeLayoutStore.slots()
        assertNull(s[0]); assertEquals("2", s[1]!!.name); assertNull(s[2])
        HomeLayoutStore.set(s[1]!!)
        assertEquals("a slot's layout is named by its slot", "2", HomeLayoutStore.nameOf(HomeSettings.layout))
        // the slots are the device's: another character sees the same
        HomeLayoutStore.character("Alt-Realm")
        assertEquals(s, HomeLayoutStore.slots())
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
        // padtab 39, 38, 37 then 9 / 10 (Book b74)
        assertEquals(listOf("padtab" to 39, "padtab" to 38, "padtab" to 37, "padtab" to 10), ToolbarMirror.cells(true))
        assertEquals(9, ToolbarMirror.cells(false).last().second)
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
        // Book b74 handles exactly these (the add-on's own table)
        val book = java.io.File(addons(), "WowCompanionSpells/Book.lua").readText()
        assertTrue(book.contains("for k = 0, 1 do P3[9 + k] = function()"))
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

    // ---------------------------------------------------------------- reset

    @Test fun layoutsResetTheNewKeys() {
        val me = "Shammy-Realm"; val other = "Hunt-Realm"
        val keys = setOf("homeLayout.$me", "homeLayout.$other", HomeLayoutStore.KEY_DEFAULT, HomeLayoutStore.KEY_SLOTS, HomeLayoutStore.KEY_MIGRATED)
        val L = setOf(ResetPlan.Kind.LAYOUTS)
        assertEquals(setOf("homeLayout.$me", HomeLayoutStore.KEY_DEFAULT, HomeLayoutStore.KEY_SLOTS), ResetPlan.prefKeys("home", keys, L, me, false))
        assertEquals(setOf("homeLayout.$me", "homeLayout.$other", HomeLayoutStore.KEY_DEFAULT, HomeLayoutStore.KEY_SLOTS),
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
