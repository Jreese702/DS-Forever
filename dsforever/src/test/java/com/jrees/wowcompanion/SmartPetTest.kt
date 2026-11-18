package com.jrees.wowcompanion

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SMART PET (the user, 2026-11-17: "to be clear I think this should just be a smart pet feature that includes both of those settings in
 * one"; it replaces 2026-11-16's B: pet follow). The add-on halves are tools/test_b_pet_follow.py (B: PadMap m165 / m171, which writes the
 * padmaps read here, src/test/resources/bpet-fixtures) and tools/test_smart_pet.py (the attack: PadMap m172). This is the app's half: the
 * one row (a pet class only, per character, OFF by default, B: pet follow's saved value carried over), the word it tells (off / on with
 * Home free / on with Home busy) and when.
 */
class SmartPetTest {
    @After fun down() { HomeSettings.startForTest(null) }

    private fun fixture(name: String): PadMapModel.Map {
        val s = javaClass.classLoader!!.getResourceAsStream("bpet-fixtures/$name.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        return PadMapModel.parse(JSONObject(s))!!
    }

    @Test fun thePadmapsPf() {
        assertEquals(PadMapModel.PetB(on = true, cls = true, pet = true, bound = true, bfree = true, sp = true), fixture("pf_hunter_on").petB)
        assertEquals(PadMapModel.PetB(on = false, cls = true, pet = true, bound = false), fixture("pf_hunter_no_word").petB)
        assertEquals(PadMapModel.PetB(on = true, cls = true, pet = false, bound = false, bfree = true, sp = true), fixture("pf_hunter_on_no_pet").petB)
        // m172: on with Home busy (padtab 39 then 0): the attack half only, B not carried
        assertEquals(PadMapModel.PetB(on = true, cls = true, pet = true, bound = false, bfree = false, sp = true), fixture("pf_hunter_on_busy").petB)
        // a Warrior with no pet and no word: an empty `pf` (the encoder's []): nothing to show, nothing to tell
        assertNull(fixture("pf_warrior").petB)
        // an add-on before m165: no `pf`
        assertNull(PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m164","m":{}}"""))!!.petB)
    }

    @Test fun theRowIsAPetClassOnly() {
        assertTrue(SmartPet.shown(fixture("pf_hunter_no_word").petB))
        assertTrue(SmartPet.shown(fixture("pf_hunter_on_no_pet").petB))            // the row whatever the pet's state now
        assertFalse(SmartPet.shown(fixture("pf_warrior").petB))
        assertFalse(SmartPet.shown(PadMapModel.PetB(on = false, cls = false, pet = true, bound = false)))
        assertFalse(SmartPet.shown(null))
    }

    @Test fun theWordIsTheSettingAndHomeFreeForB() {
        val hunter = fixture("pf_hunter_no_word").petB
        assertEquals(SmartPet.BOTH, SmartPet.want(setting = true, pf = hunter, homeFree = true))
        assertEquals("setting off", SmartPet.OFF, SmartPet.want(setting = false, pf = hunter, homeFree = true))
        // a module full screen / the buff list / the notice: B is Home's own, the pet's attack stays
        assertEquals(SmartPet.ATTACK, SmartPet.want(setting = true, pf = hunter, homeFree = false))
        assertEquals("a non-pet class", SmartPet.OFF, SmartPet.want(setting = true, pf = PadMapModel.PetB(false, false, true, false), homeFree = true))
        assertEquals("an add-on without it", SmartPet.OFF, SmartPet.want(setting = true, pf = null, homeFree = true))
        assertEquals(SmartPet.BOTH, SmartPet.held(fixture("pf_hunter_on").petB!!))
        assertEquals(SmartPet.ATTACK, SmartPet.held(fixture("pf_hunter_on_busy").petB!!))
        assertEquals(SmartPet.OFF, SmartPet.held(hunter!!))
    }

    @Test fun theTaps() {
        assertEquals(listOf("padtab" to 39, "padtab" to 37), SmartPet.cells(SmartPet.BOTH))
        assertEquals(listOf("padtab" to 39, "padtab" to 0), SmartPet.cells(SmartPet.ATTACK))
        assertEquals(listOf("padtab" to 39, "padtab" to 36), SmartPet.cells(SmartPet.OFF))
    }

    @Test fun toldWhenThePadmapDisagrees() {
        val noWord = fixture("pf_hunter_no_word").petB
        val on = fixture("pf_hunter_on").petB
        val busy = fixture("pf_hunter_on_busy").petB
        // a connect / a /reload: the add-on says off, the app wants on: told at once
        assertTrue(SmartPet.needsTell(SmartPet.BOTH, noWord, null, 0L, 10_000L))
        // the same value again before RESEND_MS: not again (the padmap that confirms it is on its way)
        assertFalse(SmartPet.needsTell(SmartPet.BOTH, noWord, SmartPet.BOTH, 10_000L, 10_000L + SmartPet.RESEND_MS - 1))
        assertTrue(SmartPet.needsTell(SmartPet.BOTH, noWord, SmartPet.BOTH, 10_000L, 10_000L + SmartPet.RESEND_MS))
        // a NEW value (Home's module opened right after: the attack half only) goes at once
        assertTrue(SmartPet.needsTell(SmartPet.ATTACK, on, SmartPet.BOTH, 10_000L, 10_001L))
        assertTrue(SmartPet.needsTell(SmartPet.BOTH, busy, SmartPet.ATTACK, 10_000L, 10_001L))
        // agreeing: never a tap
        assertFalse(SmartPet.needsTell(SmartPet.BOTH, on, null, 0L, 10_000L))
        assertFalse(SmartPet.needsTell(SmartPet.ATTACK, busy, null, 0L, 10_000L))
        assertFalse(SmartPet.needsTell(SmartPet.OFF, noWord, null, 0L, 10_000L))
        // a Warrior / an older add-on: nothing to tell, ever (no taps for a class with no pet)
        assertFalse(SmartPet.needsTell(SmartPet.OFF, fixture("pf_warrior").petB, null, 0L, 10_000L))
        assertFalse(SmartPet.needsTell(SmartPet.BOTH, null, null, 0L, 10_000L))
        // the game closed or quiet: no tap is tried, and once it is live again the word goes
        assertFalse(SmartPet.needsTell(SmartPet.OFF, on, SmartPet.BOTH, 10_000L, 20_000L, live = false))
        assertFalse(SmartPet.needsTell(SmartPet.BOTH, noWord, null, 0L, 10_000L, live = false))
        assertTrue(SmartPet.needsTell(SmartPet.OFF, on, SmartPet.BOTH, 10_000L, 20_000L, live = true))
    }

    @Test fun perCharacterOffByDefault() {
        val p = Prefs()
        HomeSettings.startForTest(p)
        assertFalse("the user's pick: off by default", HomeSettings.smartPet("Korvash-Realm"))
        assertFalse(HomeSettings.smartPet(null))
        HomeSettings.setSmartPet("Korvash-Realm", true)
        assertTrue(HomeSettings.smartPet("Korvash-Realm"))
        assertFalse("another character keeps its own", HomeSettings.smartPet("Brannoc-Realm"))
        assertEquals(true, p.m["smartPet.Korvash-Realm"])
        HomeSettings.setSmartPet(null, true)                                          // no character known: nothing saved
        assertEquals(1, p.m.keys.count { it.startsWith("smartPet.") })
        HomeSettings.setSmartPet("Korvash-Realm", false)
        assertFalse(HomeSettings.smartPet("Korvash-Realm"))
        assertEquals(false, p.m["smartPet.Korvash-Realm"])
    }

    /** the migration: a character with B: pet follow SAVED keeps that value as its Smart pet; nothing saved = Off */
    @Test fun bPetFollowsSavedValueCarriesOver() {
        val p = Prefs()
        p.m["petFollowB.Korvash-Realm"] = false
        p.m["petFollowB.Brannoc-Realm"] = true
        HomeSettings.startForTest(p)
        assertFalse(HomeSettings.smartPet("Korvash-Realm"))
        assertTrue("B: pet follow saved On: Smart pet On", HomeSettings.smartPet("Brannoc-Realm"))
        assertFalse("nothing saved (B: pet follow's old default On): Off", HomeSettings.smartPet("Thessa-Realm"))
        // a choice made now wins over the old key, and the old key is left as it was
        HomeSettings.setSmartPet("Brannoc-Realm", false)
        assertFalse(HomeSettings.smartPet("Brannoc-Realm"))
        assertEquals(true, p.m["petFollowB.Brannoc-Realm"])
        HomeSettings.setSmartPet("Korvash-Realm", false)                              // the same as carried over: saved as its own
        assertEquals(false, p.m["smartPet.Korvash-Realm"])
    }

    /** the review: the word is this padmap's character's setting (GameUi.character is set later in the same padmap's handling) */
    @Test fun theWordIsThePadmapsOwnCharacters() {
        assertEquals("Korvash-Realm", SmartPet.who("Korvash", "Realm"))
        assertEquals("Korvash-", SmartPet.who("Korvash", null))                    // GameUi.character's own form for no realm
        assertNull(SmartPet.who(null, "Realm"))
        assertNull(SmartPet.who("", "Realm"))
        val p = Prefs()
        HomeSettings.startForTest(p)
        HomeSettings.setSmartPet("Korvash-Realm", true)
        val hunter = fixture("pf_hunter_no_word").petB
        assertEquals(SmartPet.BOTH, SmartPet.want(HomeSettings.smartPet(SmartPet.who("Korvash", "Realm")), hunter, homeFree = true))
        assertEquals(SmartPet.OFF, SmartPet.want(HomeSettings.smartPet(SmartPet.who("Brannoc", "Realm")), hunter, homeFree = true))
    }

    @Test fun theOneRowSitsUnderButtons() {
        assertEquals(SettingsTabs.Group.BUTTONS, SettingsTabs.Block.SMART_PET.group)
        assertEquals(listOf(SmartPet.ROW_ID), SettingsTabs.Block.SMART_PET.rows)
        assertEquals(SettingsTabs.Tab.CONTROLLER, SettingsTabs.Block.SMART_PET.tab)
        assertTrue("no separate B: pet follow row any more", SettingsTabs.Block.values().none { b -> b.rows.any { it == "B: pet follow" } })
        // the 1.0.34 merge (feat/beautify: every tab is picture tiles, the "What it does" panel gone): Smart pet is the Controller
        // tab's pet tile
        assertEquals(SmartPet.ROW_ID, ControllerTiles.NAMES[ControllerPictures.PET_B])
    }

    @Test fun theWordsTranslate() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { java.io.File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) Tr.load(l, java.io.File(dir, "$l.json").readText(Charsets.UTF_8))
        val words = listOf(SmartPet.ROW_ID, SettingsTabs.Block.SMART_PET.heading, SmartPet.line(true), SmartPet.line(false),
            "Smart pet: On", "Smart pet: Off") +
            listOf("you", "your pet follows", "your pet attacks", "Pet Follow", "B's own action", "your attack sends it")
        for (l in listOf("es", "fr")) for (w in words) assertTrue("$l: $w", Tr.t(w, l) != w)
        assertNotNull(SmartPet.SEGMENTS.map { Tr.t(it, "es") })
    }

    private class Prefs(val m: HashMap<String, Any?> = HashMap()) : SharedPreferences {
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
