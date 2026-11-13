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
 * THE POLESTAR QUEST GUIDE IN THE BASE INSTALL, off-device (the user, 2026-11-12: "Lets start including this addon to the base install
 * like questie and give a prompt asking players if they want to use the new polestar quest guide integration. If they say no let's add
 * a toggle in settings to turn it on and off. We need to remove safe path and quest trail from the options. If anyone used these
 * default them over to the polestar quest tracking"): the one-time move, the prompt, the toggle and the two modes left.
 */
class PolestarGuideTest {
    /** SharedPreferences over a map (android.jar's interface; no device) */
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

    @Before fun up() { ThirdPartyAddons.noFetch = true }
    @After fun down() { Settings.startForTest(null); ThirdPartyAddons.noFetch = false }

    // ------------------------------------------------------------------ the pure rules
    @Test fun twoModesLeft() {
        assertEquals(listOf("vanilla", "polestar"), Settings.QUEST_TRACKING)
        assertEquals(listOf("safepath", "questtrail"), Settings.RETIRED_QUEST_TRACKING)
        assertEquals("polestar", PolestarGuide.mode("on"))
        assertEquals("vanilla", PolestarGuide.mode("off"))
        assertEquals("vanilla", PolestarGuide.mode(null))                  // not asked yet: the game's own way
        assertTrue(PolestarGuide.ask(null)); assertFalse(PolestarGuide.ask("on")); assertFalse(PolestarGuide.ask("off"))
        assertEquals(true, PolestarGuide.wanted("on")); assertEquals(false, PolestarGuide.wanted("off")); assertNull(PolestarGuide.wanted(null))
    }

    @Test fun theOneTimeMove() {
        // a new install: asked
        assertNull(PolestarGuide.migrate(existing = false, stored = null, guide = null))
        // SafePath (saved, or never changed: the default was SafePath), QuestTrail, Polestar: on, no question
        for (s in listOf(null, "safepath", "questtrail", "polestar", "something-new"))
            assertEquals("$s", "on", PolestarGuide.migrate(existing = true, stored = s, guide = null))
        // Vanilla: asked
        assertNull(PolestarGuide.migrate(existing = true, stored = "vanilla", guide = null))
        // a choice already made stays
        assertEquals("off", PolestarGuide.migrate(existing = true, stored = "safepath", guide = "off"))
        assertEquals("on", PolestarGuide.migrate(existing = false, stored = "vanilla", guide = "on"))
    }

    // ------------------------------------------------------------------ the move as Settings runs it
    private fun start(vararg kv: Pair<String, Any?>): Prefs = Prefs(hashMapOf(*kv)).also { Settings.startForTest(it) }

    @Test fun aSafePathPlayerIsOnPolestarWithNoQuestion() {
        val p = start("questTrackingSafePath" to true, "questTracking" to "safepath")
        assertEquals("on", Settings.polestarGuide)
        assertEquals("polestar", Settings.questTracking)
        assertEquals("polestar", p.m["questTracking"])                      // the old key follows
        assertFalse(PolestarGuide.ask(Settings.polestarGuide))
    }

    @Test fun aDefaultSafePathPlayerToo() {
        start("questTrackingSafePath" to true)                               // never touched: SafePath was the default
        assertEquals("on", Settings.polestarGuide)
        assertEquals("polestar", Settings.questTracking)
    }

    @Test fun aQuestTrailPlayerToo() {
        start("questTrackingSafePath" to true, "questTracking" to "questtrail")
        assertEquals("polestar", Settings.questTracking)
        // review of 2026-11-12: two guides at once otherwise: QuestTrail goes out once (ThirdPartyAddons takes the mark)
        assertTrue(Settings.takeQuestTrailRetire())
        assertFalse("once", Settings.takeQuestTrailRetire())
    }

    @Test fun onlyAQuestTrailPlayerLosesQuestTrail() {
        start("questTrackingSafePath" to true, "questTracking" to "safepath")
        assertFalse(Settings.takeQuestTrailRetire())
        start("questTrackingSafePath" to true, "questTracking" to "vanilla")
        assertFalse(Settings.takeQuestTrailRetire())
    }

    @Test fun aVanillaPlayerIsAsked() {
        start("questTrackingSafePath" to true, "questTracking" to "vanilla")
        assertNull(Settings.polestarGuide)
        assertEquals("vanilla", Settings.questTracking)
        assertTrue(PolestarGuide.ask(Settings.polestarGuide))
    }

    @Test fun aNewInstallIsAskedAndTheMoveRunsOnce() {
        val p = start()
        assertNull(Settings.polestarGuide)
        assertEquals("vanilla", Settings.questTracking)
        // the next start (the old mark is there now): still asked, never moved to Polestar by itself
        Settings.startForTest(p)
        assertNull(Settings.polestarGuide)
        assertEquals("vanilla", Settings.questTracking)
    }

    @Test fun anOldValueArrivingIsIgnored() {
        val p = start("questTrackingSafePath" to true, "questTracking" to "vanilla")
        p.m["questTracking"] = "safepath"                                    // a restored backup, an older build's write
        assertEquals("vanilla", Settings.questTracking)
        p.m["polestarGuide"] = "on"
        p.m["questTracking"] = "questtrail"
        assertEquals("polestar", Settings.questTracking)
        p.m["polestarGuide"] = "maybe"                                       // a word this build does not know: not asked = asked again
        assertNull(Settings.polestarGuide)
    }

    // ------------------------------------------------------------------ the prompt
    private fun ready() = SetupFlow.Inputs(path = "/g", folderFound = true, art = SetupFlow.Art.READY, loginSaved = true, addonsInstalled = true)

    @Test fun thePromptAsksOnceAndNeverHoldsPlay() {
        val asked = SetupFlow.screen(ready().copy(polestarAsk = true))
        assertEquals("polestar", asked.prompt?.id)
        assertEquals(PolestarGuide.TITLE, asked.prompt?.title)
        assertEquals("", asked.prompt?.text)                                 // terse: a title and two buttons
        assertEquals(listOf("Yes", "No"), asked.prompt?.buttons?.map { it.label })
        assertEquals(listOf(SetupFlow.Action.POLESTAR_YES, SetupFlow.Action.POLESTAR_NO), asked.prompt?.buttons?.map { it.action })
        assertTrue(asked.prompt!!.buttons.all { SetupFlow.padTarget(it) })     // the pad's cursor lands on both
        assertEquals(true, asked.play?.enabled)                              // never holds Play
        assertTrue(SetupFlow.padDriven(asked))
        assertTrue(SetupFlow.checksPass(ready().copy(polestarAsk = true)))
        // answered: gone
        assertNull(SetupFlow.screen(ready()).prompt)
        // the login question comes first; the game up: none of the setup's pop-ups
        assertEquals("login", SetupFlow.screen(ready().copy(polestarAsk = true, loginSaved = false)).prompt?.id)
        assertNull(SetupFlow.screen(ready().copy(polestarAsk = true, gameRunning = true)).prompt)
    }

    @Test fun yesAndNoSetTheChoiceAndTheMode() {
        start()                                                              // a new install: asked
        SetupBridge.publish(ready())
        assertEquals("polestar", SetupBridge.screen(gameRunning = false)?.prompt?.id)
        SetupBridge.press(SetupFlow.Action.POLESTAR_NO)
        assertEquals("off", Settings.polestarGuide); assertEquals("vanilla", Settings.questTracking)
        assertEquals(false, ThirdPartyAddons.coreWanted(ThirdPartyAddons.POLESTAR))
        assertNull(SetupBridge.screen(gameRunning = false)?.prompt)          // asked once
        SetupBridge.press(SetupFlow.Action.POLESTAR_YES)
        assertEquals("on", Settings.polestarGuide); assertEquals("polestar", Settings.questTracking)
        assertEquals(true, ThirdPartyAddons.coreWanted(ThirdPartyAddons.POLESTAR))
        SetupBridge.publish(null)
    }

    // ------------------------------------------------------------------ the toggle (Settings > Add-ons, Home > Map's Quest tracking)
    @Test fun theToggleSwitchesTheAddOnAndTheMode() {
        start("questTrackingSafePath" to true, "questTracking" to "vanilla")
        PolestarGuide.set(true)
        assertEquals("polestar", Settings.questTracking)
        assertTrue(ThirdPartyAddons.coreWanted(ThirdPartyAddons.POLESTAR))
        // Quest tracking's Vanilla segment is the same choice: Polestar off, out at the next start
        Settings.questTracking = "vanilla"
        assertEquals("off", Settings.polestarGuide)
        assertFalse(ThirdPartyAddons.coreWanted(ThirdPartyAddons.POLESTAR))
        // the Add-ons row's Install word on a base add-on is its choice too
        ThirdPartyAddons.setCore(ThirdPartyAddons.POLESTAR, true)
        assertEquals("on", Settings.polestarGuide)
        // a retired mode chosen by an old caller: never SafePath / QuestTrail; it is not Polestar, so the guide is off
        Settings.questTracking = "safepath"
        assertEquals("vanilla", Settings.questTracking)
    }
}
