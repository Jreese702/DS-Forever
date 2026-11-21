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
 * B: PET RECALL (the user, 2026-11-18: "I like the pet recall but I think we need to remove the pet smart attack, its obnoxious"; the pick:
 * rename to "B: pet recall", ON by default for pet classes, a player's saved choice kept). It was 2026-11-17's Smart pet; the attack half
 * is gone. The add-on halves are tools/test_b_pet_follow.py (B: PadMap m165 / m171 / m173, which writes the padmaps read here,
 * src/test/resources/bpet-fixtures) and tools/test_pet_recall.py (PadMap m174: nothing of the attack left). This is the app's half: the one
 * row (a pet class only, per character, ON by default, Smart pet's / B: pet follow's saved value kept), the word it tells (off / on with
 * Home free / on with Home busy) and when.
 */
class PetRecallTest {
    @After fun down() { HomeSettings.startForTest(null) }

    private fun fixture(name: String): PadMapModel.Map {
        val s = javaClass.classLoader!!.getResourceAsStream("bpet-fixtures/$name.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        return PadMapModel.parse(JSONObject(s))!!
    }

    @Test fun thePadmapsPf() {
        assertEquals(PadMapModel.PetB(on = true, cls = true, pet = true, bound = true, bfree = true), fixture("pf_hunter_on").petB)
        assertEquals(PadMapModel.PetB(on = false, cls = true, pet = true, bound = false), fixture("pf_hunter_no_word").petB)
        assertEquals(PadMapModel.PetB(on = true, cls = true, pet = false, bound = false, bfree = true), fixture("pf_hunter_on_no_pet").petB)
        // on with Home busy (padtab 39 then 0): B not carried out of combat (only the fight's give-back B recalls)
        assertEquals(PadMapModel.PetB(on = true, cls = true, pet = true, bound = false, bfree = false), fixture("pf_hunter_on_busy").petB)
        // a Warrior with no pet and no word: an empty `pf` (the encoder's []): nothing to show, nothing to tell
        assertNull(fixture("pf_warrior").petB)
        // an add-on before m165: no `pf`
        assertNull(PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m164","m":{}}"""))!!.petB)
        // an m172 / m173 add-on's `sp` (the attack half armed) is ignored: the same recall word
        assertEquals(PadMapModel.PetB(on = true, cls = true, pet = true, bound = true, bfree = true),
            PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m173","m":{"pf":{"on":true,"bf":true,"cls":true,"pet":true,"b":true,"sp":true}}}"""))!!.petB)
    }

    @Test fun theRowIsAPetClassOnly() {
        assertTrue(PetRecall.shown(fixture("pf_hunter_no_word").petB))
        assertTrue(PetRecall.shown(fixture("pf_hunter_on_no_pet").petB))            // the row whatever the pet's state now
        assertFalse(PetRecall.shown(fixture("pf_warrior").petB))
        assertFalse(PetRecall.shown(PadMapModel.PetB(on = false, cls = false, pet = true, bound = false)))
        assertFalse(PetRecall.shown(null))
    }

    @Test fun theWordIsTheSettingAndHomeFreeForB() {
        val hunter = fixture("pf_hunter_no_word").petB
        assertEquals(PetRecall.ON, PetRecall.want(setting = true, pf = hunter, homeFree = true))
        assertEquals("setting off", PetRecall.OFF, PetRecall.want(setting = false, pf = hunter, homeFree = true))
        // a module full screen / the buff list / the notice: B is Home's own out of combat, the fight's give-back B still recalls
        assertEquals(PetRecall.BUSY, PetRecall.want(setting = true, pf = hunter, homeFree = false))
        assertEquals("a non-pet class", PetRecall.OFF, PetRecall.want(setting = true, pf = PadMapModel.PetB(false, false, true, false), homeFree = true))
        assertEquals("an add-on without it", PetRecall.OFF, PetRecall.want(setting = true, pf = null, homeFree = true))
        assertEquals(PetRecall.ON, PetRecall.held(fixture("pf_hunter_on").petB!!))
        assertEquals(PetRecall.BUSY, PetRecall.held(fixture("pf_hunter_on_busy").petB!!))
        assertEquals(PetRecall.OFF, PetRecall.held(hunter!!))
    }

    @Test fun theTaps() {
        assertEquals(listOf("padtab" to 39, "padtab" to 37), PetRecall.cells(PetRecall.ON))
        assertEquals(listOf("padtab" to 39, "padtab" to 0), PetRecall.cells(PetRecall.BUSY))
        assertEquals(listOf("padtab" to 39, "padtab" to 36), PetRecall.cells(PetRecall.OFF))
    }

    @Test fun toldWhenThePadmapDisagrees() {
        val noWord = fixture("pf_hunter_no_word").petB
        val on = fixture("pf_hunter_on").petB
        val busy = fixture("pf_hunter_on_busy").petB
        // a connect / a /reload: the add-on says off, the app wants on: told at once
        assertTrue(PetRecall.needsTell(PetRecall.ON, noWord, null, 0L, 10_000L))
        // the same value again before RESEND_MS: not again (the padmap that confirms it is on its way)
        assertFalse(PetRecall.needsTell(PetRecall.ON, noWord, PetRecall.ON, 10_000L, 10_000L + PetRecall.RESEND_MS - 1))
        assertTrue(PetRecall.needsTell(PetRecall.ON, noWord, PetRecall.ON, 10_000L, 10_000L + PetRecall.RESEND_MS))
        // a NEW value (Home's module opened right after) goes at once
        assertTrue(PetRecall.needsTell(PetRecall.BUSY, on, PetRecall.ON, 10_000L, 10_001L))
        assertTrue(PetRecall.needsTell(PetRecall.ON, busy, PetRecall.BUSY, 10_000L, 10_001L))
        // agreeing: never a tap
        assertFalse(PetRecall.needsTell(PetRecall.ON, on, null, 0L, 10_000L))
        assertFalse(PetRecall.needsTell(PetRecall.BUSY, busy, null, 0L, 10_000L))
        assertFalse(PetRecall.needsTell(PetRecall.OFF, noWord, null, 0L, 10_000L))
        // a Warrior / an older add-on: nothing to tell, ever (no taps for a class with no pet)
        assertFalse(PetRecall.needsTell(PetRecall.OFF, fixture("pf_warrior").petB, null, 0L, 10_000L))
        assertFalse(PetRecall.needsTell(PetRecall.ON, null, null, 0L, 10_000L))
        // the game closed or quiet: no tap is tried, and once it is live again the word goes
        assertFalse(PetRecall.needsTell(PetRecall.OFF, on, PetRecall.ON, 10_000L, 20_000L, live = false))
        assertFalse(PetRecall.needsTell(PetRecall.ON, noWord, null, 0L, 10_000L, live = false))
        assertTrue(PetRecall.needsTell(PetRecall.OFF, on, PetRecall.ON, 10_000L, 20_000L, live = true))
    }

    @Test fun perCharacterOnByDefault() {
        val p = Prefs()
        HomeSettings.startForTest(p)
        assertTrue("the user's pick (2026-11-18): on by default for a pet class", HomeSettings.petRecall("Korvash-Realm"))
        assertFalse("no character known: nothing to tell", HomeSettings.petRecall(null))
        assertTrue("reading the default saves nothing", p.m.isEmpty())
        HomeSettings.setPetRecall("Korvash-Realm", false)
        assertFalse(HomeSettings.petRecall("Korvash-Realm"))
        assertTrue("another character keeps its own (the default)", HomeSettings.petRecall("Brannoc-Realm"))
        assertEquals(false, p.m["smartPet.Korvash-Realm"])
        HomeSettings.setPetRecall(null, true)                                          // no character known: nothing saved
        assertEquals(1, p.m.keys.count { it.startsWith("smartPet.") })
        HomeSettings.setPetRecall("Korvash-Realm", true)
        assertTrue(HomeSettings.petRecall("Korvash-Realm"))
        assertEquals(true, p.m["smartPet.Korvash-Realm"])
        HomeSettings.setPetRecall("Brannoc-Realm", true)                               // the same as the default: saved as its own
        assertEquals(true, p.m["smartPet.Brannoc-Realm"])
    }

    /** the saved choices are kept: Smart pet's (the same key), else B: pet follow's; nothing saved = On */
    @Test fun savedChoicesAreKept() {
        val p = Prefs()
        p.m["smartPet.Korvash-Realm"] = false                                          // Smart pet saved Off: Off
        p.m["smartPet.Ilsa-Realm"] = true                                              // Smart pet saved On: On (recall only now)
        p.m["petFollowB.Brannoc-Realm"] = false                                        // B: pet follow saved Off: Off
        p.m["petFollowB.Mora-Realm"] = true
        p.m["smartPet.Mora-Realm"] = false                                             // a newer choice wins over the older key
        HomeSettings.startForTest(p)
        assertFalse(HomeSettings.petRecall("Korvash-Realm"))
        assertTrue(HomeSettings.petRecall("Ilsa-Realm"))
        assertFalse(HomeSettings.petRecall("Brannoc-Realm"))
        assertFalse(HomeSettings.petRecall("Mora-Realm"))
        assertTrue("nothing saved: On", HomeSettings.petRecall("Thessa-Realm"))
        // a choice made now wins over the old key, and the old key is left as it was
        HomeSettings.setPetRecall("Brannoc-Realm", true)
        assertTrue(HomeSettings.petRecall("Brannoc-Realm"))
        assertEquals(false, p.m["petFollowB.Brannoc-Realm"])
    }

    /** the review: the word is this padmap's character's setting (GameUi.character is set later in the same padmap's handling) */
    @Test fun theWordIsThePadmapsOwnCharacters() {
        assertEquals("Korvash-Realm", PetRecall.who("Korvash", "Realm"))
        assertNull(PetRecall.who("Korvash", null))     // the 11-20 hunter (CharKey): no realm = no key (it was "Korvash-", a partial key)
        assertNull(PetRecall.who("Korvash", ""))
        assertNull(PetRecall.who(null, "Realm"))
        assertNull(PetRecall.who("", "Realm"))
        val p = Prefs()
        HomeSettings.startForTest(p)
        HomeSettings.setPetRecall("Brannoc-Realm", false)
        val hunter = fixture("pf_hunter_no_word").petB
        assertEquals(PetRecall.ON, PetRecall.want(HomeSettings.petRecall(PetRecall.who("Korvash", "Realm")), hunter, homeFree = true))
        assertEquals(PetRecall.OFF, PetRecall.want(HomeSettings.petRecall(PetRecall.who("Brannoc", "Realm")), hunter, homeFree = true))
    }

    @Test fun theOneRowSitsUnderButtons() {
        assertEquals("B: pet recall", PetRecall.ROW_ID)
        assertEquals(SettingsTabs.Group.BUTTONS, SettingsTabs.Block.PET_RECALL.group)
        assertEquals(listOf(PetRecall.ROW_ID), SettingsTabs.Block.PET_RECALL.rows)
        assertEquals(SettingsTabs.Tab.CONTROLLER, SettingsTabs.Block.PET_RECALL.tab)
        assertTrue("no B: pet follow / Smart pet row any more",
            SettingsTabs.Block.values().none { b -> b.rows.any { it == "B: pet follow" || it == "Smart pet" } })
        assertEquals(PetRecall.ROW_ID, ControllerTiles.NAMES[ControllerPictures.PET_B])
        assertEquals("B calls your pet back.", PetRecall.line(true))
        assertEquals("B does nothing to your pet.", PetRecall.line(false))
    }

    @Test fun theWordsTranslate() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { java.io.File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) Tr.load(l, java.io.File(dir, "$l.json").readText(Charsets.UTF_8))
        val words = listOf(PetRecall.ROW_ID, SettingsTabs.Block.PET_RECALL.heading, PetRecall.line(true), PetRecall.line(false),
            "B: pet recall: On", "B: pet recall: Off") +
            listOf("you", "your pet follows", "your pet attacks", "Pet Follow", "B's own action")
        for (l in listOf("es", "fr")) for (w in words) assertTrue("$l: $w", Tr.t(w, l) != w)
        // the attack half's words are gone from both files
        for (l in listOf("es", "fr")) {
            val text = java.io.File(dir, "$l.json").readText(Charsets.UTF_8)
            for (gone in listOf("\"Smart pet\"", "Your attacks send it in", "your attack sends it", "Attack or cast a harmful spell:", "it attacks your target."))
                assertFalse("$l: $gone", text.contains(gone))
        }
        assertNotNull(PetRecall.SEGMENTS.map { Tr.t(it, "es") })
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
