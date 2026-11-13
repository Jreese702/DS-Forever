package com.jrees.wowcompanion

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #170 SETUP PRESETS, off-device: Classic / Balanced / Full as bundles of the app's own switches, "Custom" after any manual change,
 * the one-time picker on the launcher's bottom screen (A picks, B = Keep current), and the words in Spanish and French.
 */
class SetupPresetsTest {
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

    private lateinit var ui: Prefs
    private lateinit var set: Prefs

    @Before fun up() {
        ThirdPartyAddons.noFetch = true
        ui = Prefs(); GameUi.startForTest(ui)
        // an existing install that said Yes to Polestar, the picker not answered yet
        set = Prefs(hashMapOf("questTrackingSafePath" to true, "questTrackingPolestar1112" to true, "polestarGuide" to "on"))
        Settings.startForTest(set)
    }
    @After fun down() { GameUi.startForTest(null); Settings.startForTest(null); SetupBridge.publish(null); ThirdPartyAddons.noFetch = false }

    private val P = SetupPresets.Preset.values().toList()

    @Test fun theThreeBundles() {
        val ids = UiElements.KNOWN.map { it.id }
        val classic = SetupPresets.bundle(SetupPresets.Preset.CLASSIC)
        assertEquals(ids.associateWith { UiMode.ON }, classic.modes)          // the game's UI fully On on top: nothing hidden, no Nintendo bar
        assertFalse(classic.castBarHide); assertFalse(classic.partyMode); assertTrue(classic.vanilla)
        val bal = SetupPresets.bundle(SetupPresets.Preset.BALANCED)
        for (id in listOf("minimap", "chat", "hud")) assertEquals(id, UiMode.DYNAMIC, bal.modes[id])
        assertTrue(bal.partyMode); assertFalse(bal.vanilla)
        val full = SetupPresets.bundle(SetupPresets.Preset.FULL)
        assertEquals(ids.associateWith { UiElements.of(it)!!.default }, full.modes)   // today's defaults
        assertTrue(full.castBarHide); assertTrue(full.partyMode); assertFalse(full.vanilla)
        // every mode a preset writes is one its element offers; no preset brings the Nintendo bar (a controller overlay)
        for (p in P) for ((id, m) in SetupPresets.bundle(p).modes) { assertTrue("$p $id", m in UiElements.modesFor(id)); assertTrue(m != UiMode.NINTENDO) }
        // the three are told apart: one preset at most matches any state
        for (p in P) { val b = SetupPresets.bundle(p)
            val now = SetupPresets.Now(b.modes, b.castBarHide, b.partyMode, "vanilla")
            assertEquals(p, SetupPresets.matching(now)); assertEquals(1, P.count { SetupPresets.matches(it, now) }) }
    }

    @Test fun customWhenNoPresetMatches() {
        val full = SetupPresets.bundle(SetupPresets.Preset.FULL)
        val now = SetupPresets.Now(full.modes, full.castBarHide, full.partyMode, "polestar")
        assertEquals(SetupPresets.Preset.FULL, SetupPresets.matching(now))   // Full leaves the quest guide as it is
        assertEquals(SetupPresets.Preset.FULL.ordinal, SetupPresets.litIndex(now))
        val changed = now.copy(modes = now.modes + ("chat" to UiMode.ON))
        assertNull(SetupPresets.matching(changed))
        assertEquals(P.size, SetupPresets.litIndex(changed))                 // the Custom tile
        // Classic's switches with Polestar on: Custom (Classic is Vanilla)
        val c = SetupPresets.bundle(SetupPresets.Preset.CLASSIC)
        assertNull(SetupPresets.matching(SetupPresets.Now(c.modes, c.castBarHide, c.partyMode, "polestar")))
    }

    @Test fun aPickWritesTheBundleAndAManualChangeShowsCustom() {
        assertEquals(SetupPresets.Preset.FULL, SetupPresets.matching(SetupPresets.current()))   // a fresh store: today's defaults
        assertFalse(Settings.setupAsked)
        SetupPresets.apply(SetupPresets.Preset.CLASSIC)
        assertEquals(SetupPresets.Preset.CLASSIC, SetupPresets.matching(SetupPresets.current()))
        assertEquals(UiMode.ON, GameUi.mode("minimap")); assertFalse(GameUi.castBarHide()); assertFalse(GameUi.swWant(GameUi.SW_PMODE))
        assertEquals("off", Settings.polestarGuide); assertEquals("vanilla", Settings.questTracking)
        assertTrue(Settings.setupAsked)
        GameUi.setMode("chat", UiMode.DYNAMIC)                               // any manual change
        assertNull(SetupPresets.matching(SetupPresets.current()))
        SetupPresets.apply(SetupPresets.Preset.BALANCED)
        assertEquals(SetupPresets.Preset.BALANCED, SetupPresets.matching(SetupPresets.current()))
        assertEquals("off", Settings.polestarGuide)                          // Balanced leaves the guide as it is
        SetupPresets.apply(SetupPresets.Preset.FULL)
        assertEquals(SetupPresets.Preset.FULL, SetupPresets.matching(SetupPresets.current()))
        assertEquals(UiMode.OFF, GameUi.mode("hud")); assertTrue(GameUi.swWant(GameUi.SW_PMODE))
        // rooms and per-character switches are not in a bundle: nothing per character was written
        assertTrue(ui.m.keys.none { it.startsWith("sw.") && it != "sw.${GameUi.SW_PMODE}" })
    }

    private fun ready() = SetupFlow.Inputs(path = "/g", folderFound = true, art = SetupFlow.Art.READY, loginSaved = true, addonsInstalled = true)

    @Test fun theOneTimePicker() {
        val s = SetupFlow.screen(ready().copy(setupAsk = true, polestarAsk = true))
        val p = s.prompt!!
        assertEquals("setup", p.id)                                          // before the Polestar question
        assertEquals("", p.text)                                             // terse: a title and the buttons
        assertEquals(listOf("Classic", "Balanced", "Full", "Keep current"), p.buttons.map { it.label })
        assertTrue(p.buttons.all { SetupFlow.padTarget(it) })                 // the pad's box lands on each; A picks
        assertEquals(SetupFlow.Action.SETUP_KEEP, p.back)                    // B = Keep current
        assertEquals(true, s.play?.enabled); assertTrue(SetupFlow.padDriven(s))
        assertNull(SetupFlow.screen(ready().copy(setupAsk = true, gameRunning = true)).prompt)
        assertEquals("login", SetupFlow.screen(ready().copy(setupAsk = true, loginSaved = false)).prompt?.id)
        assertNull(SetupFlow.screen(ready().copy(polestarAsk = true)).prompt?.back)   // other pop-ups: B as before
        assertEquals(SetupPresets.Preset.CLASSIC, SetupFlow.setupPreset(SetupFlow.Action.SETUP_CLASSIC))
        assertNull(SetupFlow.setupPreset(SetupFlow.Action.SETUP_KEEP))
    }

    @Test fun thePickerAsksOnceThroughTheBridge() {
        SetupBridge.publish(ready())
        assertEquals("setup", SetupBridge.screen(gameRunning = false)?.prompt?.id)
        SetupBridge.press(SetupFlow.Action.SETUP_KEEP)                       // B: nothing changes
        assertTrue(Settings.setupAsked)
        assertEquals("on", Settings.polestarGuide)
        assertEquals(SetupPresets.Preset.FULL, SetupPresets.matching(SetupPresets.current()))
        assertNull(SetupBridge.screen(gameRunning = false)?.prompt)          // asked once
        // a pick from the picker writes the bundle
        Settings.setupAsked = false
        SetupBridge.press(SetupFlow.Action.SETUP_CLASSIC)
        assertEquals(SetupPresets.Preset.CLASSIC, SetupPresets.matching(SetupPresets.current()))
        assertTrue(Settings.setupAsked)
        // no prefs at all (an off-device test without its own): never asked
        Settings.startForTest(null)
        assertTrue(Settings.setupAsked)
    }

    @Test fun spanishAndFrenchWords() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { java.io.File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) Tr.load(l, java.io.File(dir, "$l.json").readText())
        val words = listOf("Setup", SetupFlow.SETUP_TITLE, SetupPresets.CUSTOM, "Keep current") + P.map { it.label }
        for (l in listOf("es", "fr")) for (w in words) assertTrue("$l: $w", Tr.t(w, l) != w)
        assertEquals("Classique", Tr.t("Classic", "fr"))
        assertEquals("Equilibrado", Tr.t("Balanced", "es"))
    }
}
