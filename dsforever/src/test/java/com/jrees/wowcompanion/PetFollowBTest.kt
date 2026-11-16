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
 * B: PET FOLLOW (the user, 2026-11-16: "I want a pet class specific option where pressing B in the out of combat home menu basically stops
 * the pet attacking"). The add-on half (PadMap m165: the router's B, the snippet's /petfollow, the fight, rooms, a dead pet) is
 * tools/test_b_pet_follow.py, which writes the padmaps read here (src/test/resources/bpet-fixtures). This is the app's half: the row
 * (a pet class only, per character, on by default), the word it tells (its setting AND Home with nothing of its own open) and when.
 */
class PetFollowBTest {
    @After fun down() { HomeSettings.startForTest(null) }

    private fun fixture(name: String): PadMapModel.Map {
        val s = javaClass.classLoader!!.getResourceAsStream("bpet-fixtures/$name.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        return PadMapModel.parse(JSONObject(s))!!
    }

    @Test fun thePadmapsPf() {
        assertEquals(PadMapModel.PetB(on = true, cls = true, pet = true, bound = true), fixture("pf_hunter_on").petB)
        assertEquals(PadMapModel.PetB(on = false, cls = true, pet = true, bound = false), fixture("pf_hunter_no_word").petB)
        assertEquals(PadMapModel.PetB(on = true, cls = true, pet = false, bound = false), fixture("pf_hunter_on_no_pet").petB)
        // a Warrior with no pet and no word: an empty `pf` (the encoder's []): nothing to show, nothing to tell
        assertNull(fixture("pf_warrior").petB)
        // an add-on before m165: no `pf`
        assertNull(PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m164","m":{}}"""))!!.petB)
    }

    @Test fun theRowIsAPetClassOnly() {
        assertTrue(PetFollowB.shown(fixture("pf_hunter_no_word").petB))
        assertTrue(PetFollowB.shown(fixture("pf_hunter_on_no_pet").petB))            // the row whatever the pet's state now
        assertFalse(PetFollowB.shown(fixture("pf_warrior").petB))
        assertFalse(PetFollowB.shown(PadMapModel.PetB(on = false, cls = false, pet = true, bound = false)))
        assertFalse(PetFollowB.shown(null))
    }

    @Test fun theWordIsTheSettingAndHomeWithNothingOpen() {
        val hunter = fixture("pf_hunter_no_word").petB
        assertTrue(PetFollowB.want(setting = true, pf = hunter, homeFree = true))
        assertFalse("setting off", PetFollowB.want(setting = false, pf = hunter, homeFree = true))
        assertFalse("a module full screen / the buff list / the notice", PetFollowB.want(setting = true, pf = hunter, homeFree = false))
        assertFalse("a non-pet class", PetFollowB.want(setting = true, pf = PadMapModel.PetB(false, false, true, false), homeFree = true))
        assertFalse("an add-on without it", PetFollowB.want(setting = true, pf = null, homeFree = true))
    }

    @Test fun theTwoTaps() {
        assertEquals(listOf("padtab" to 39, "padtab" to 37), PetFollowB.cells(true))
        assertEquals(listOf("padtab" to 39, "padtab" to 36), PetFollowB.cells(false))
    }

    @Test fun toldWhenThePadmapDisagrees() {
        val noWord = fixture("pf_hunter_no_word").petB
        val on = fixture("pf_hunter_on").petB
        // a connect / a /reload: the add-on says off, the app wants on: told at once
        assertTrue(PetFollowB.needsTell(true, noWord, null, 0L, 10_000L))
        // the same value again before RESEND_MS: not again (the padmap that confirms it is on its way)
        assertFalse(PetFollowB.needsTell(true, noWord, true, 10_000L, 10_000L + PetFollowB.RESEND_MS - 1))
        assertTrue(PetFollowB.needsTell(true, noWord, true, 10_000L, 10_000L + PetFollowB.RESEND_MS))
        // a NEW value (Home's module opened right after) goes at once
        assertTrue(PetFollowB.needsTell(false, on, true, 10_000L, 10_001L))
        // agreeing: never a tap
        assertFalse(PetFollowB.needsTell(true, on, null, 0L, 10_000L))
        assertFalse(PetFollowB.needsTell(false, noWord, null, 0L, 10_000L))
        // a Warrior / an older add-on: nothing to tell, ever (no taps for a class with no pet)
        assertFalse(PetFollowB.needsTell(false, fixture("pf_warrior").petB, null, 0L, 10_000L))
        assertFalse(PetFollowB.needsTell(true, null, null, 0L, 10_000L))
    }

    @Test fun perCharacterOnByDefault() {
        val p = Prefs()
        HomeSettings.startForTest(p)
        assertTrue(HomeSettings.petFollowB("Korvash-Realm"))
        assertTrue(HomeSettings.petFollowB(null))
        HomeSettings.setPetFollowB("Korvash-Realm", false)
        assertFalse(HomeSettings.petFollowB("Korvash-Realm"))
        assertTrue("another character keeps its own", HomeSettings.petFollowB("Brannoc-Realm"))
        assertEquals(false, p.m["petFollowB.Korvash-Realm"])
        HomeSettings.setPetFollowB(null, false)                                       // no character known: nothing saved
        assertEquals(1, p.m.keys.count { it.startsWith("petFollowB.") })
        HomeSettings.setPetFollowB("Korvash-Realm", true)
        assertTrue(HomeSettings.petFollowB("Korvash-Realm"))
    }

    /** the review: the word is this padmap's character's setting (GameUi.character is set later in the same padmap's handling) */
    @Test fun theWordIsThePadmapsOwnCharacters() {
        assertEquals("Korvash-Realm", PetFollowB.who("Korvash", "Realm"))
        assertEquals("Korvash-", PetFollowB.who("Korvash", null))                  // GameUi.character's own form for no realm
        assertNull(PetFollowB.who(null, "Realm"))
        assertNull(PetFollowB.who("", "Realm"))
        val p = Prefs()
        HomeSettings.startForTest(p)
        HomeSettings.setPetFollowB("Korvash-Realm", false)
        val hunter = fixture("pf_hunter_no_word").petB
        assertFalse(PetFollowB.want(HomeSettings.petFollowB(PetFollowB.who("Korvash", "Realm")), hunter, homeFree = true))
        assertTrue(PetFollowB.want(HomeSettings.petFollowB(PetFollowB.who("Brannoc", "Realm")), hunter, homeFree = true))
    }

    @Test fun theRowSitsUnderButtons() {
        assertEquals(SettingsTabs.Group.BUTTONS, SettingsTabs.Block.PET_B.group)
        assertEquals(listOf(PetFollowB.ROW_ID), SettingsTabs.Block.PET_B.rows)
        assertEquals(SettingsTabs.Tab.CONTROLLER, SettingsTabs.Block.PET_B.tab)
        assertTrue(SettingsPreview.has(PetFollowB.ROW_ID) && SettingsPreview.animated(PetFollowB.ROW_ID))
        assertEquals("B: pet follow: On", SettingsPreview.title(PetFollowB.ROW_ID, SettingsPreview.State()))
        assertEquals("B: pet follow: Off", SettingsPreview.title(PetFollowB.ROW_ID, SettingsPreview.State(petFollowB = false)))
        assertTrue(SettingsPreview.caption(PetFollowB.ROW_ID, SettingsPreview.State()) !=
            SettingsPreview.caption(PetFollowB.ROW_ID, SettingsPreview.State(petFollowB = false)))
    }

    @Test fun theWordsTranslate() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { java.io.File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) Tr.load(l, java.io.File(dir, "$l.json").readText(Charsets.UTF_8))
        val words = listOf(PetFollowB.ROW_ID, SettingsTabs.Block.PET_B.heading, PetFollowB.line(true), PetFollowB.line(false),
            "B: pet follow: On", "B: pet follow: Off") + PetFollowB.caption(true) + PetFollowB.caption(false) +
            listOf("you", "your pet follows", "your pet attacks", "Pet Follow", "B's own action")
        for (l in listOf("es", "fr")) for (w in words) assertTrue("$l: $w", Tr.t(w, l) != w)
        assertNotNull(PetFollowB.SEGMENTS.map { Tr.t(it, "es") })
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
