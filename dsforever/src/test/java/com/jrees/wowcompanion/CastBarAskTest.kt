package com.jrees.wowcompanion

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE ONE-TIME "TRY THE NEW TAP-TO-CAST BAR?" QUESTION (the user, 2026-11-13: "For this new install, I want it to have a one time pop up
 * asking people if they want to try the new touch input bar. if they select yes then assign it as both with the default, and hide in
 * combat as the default."): shown once, only on Home in the world after a quiet moment, never in a fight (put away and back after),
 * Yes = Both + Hide, No = Off, a player who set the bar already is never asked, never again after an answer. [Host] plays the
 * CompanionHost.castBarAsk loop over the real rules and settings.
 */
class CastBarAskTest {
    private val OFF = CastStripModel.Side.OFF

    @After fun down() { HomeSettings.startForTest(null) }

    /** CompanionHost.castBarAsk's loop: one [tick] a second; [open] the question shows; [shown] how many times it opened */
    private class Host {
        var inWorld = false; var onHome = false; var busy = false; var fight = false
        var open = false; var shown = 0; var now = 0L
        private var readySince = -1L
        fun tick(ms: Long = 1_000L) {
            now += ms
            if (open) {
                if (CastBarAsk.hideNow(true, fight, onHome)) { open = false; readySince = -1L }
                return
            }
            if (!CastBarAsk.ready(inWorld, onHome, busy, fight)) readySince = -1L else if (readySince < 0) readySince = now
            when (CastBarAsk.decide(HomeSettings.castBarAsked, HomeSettings.castBarChosen, HomeSettings.castBarSide,
                if (readySince < 0) 0L else now - readySince)) {
                CastBarAsk.Act.MARK -> HomeSettings.castBarAsked = true
                CastBarAsk.Act.ASK -> { open = true; shown++ }
                else -> {}
            }
        }
        fun ticks(n: Int) = repeat(n) { tick() }
        fun answer(yes: Boolean) { check(open); open = false; CastBarAsk.answer(yes) }
    }

    private fun fresh(p: Prefs = Prefs()): Prefs { HomeSettings.startForTest(p); return p }

    @Test fun notBeforeTheWorldNorOffHome() {
        fresh()
        val h = Host()
        h.ticks(20); assertEquals(0, h.shown)                                 // the launcher's start screen / loading: nothing
        h.inWorld = true; h.ticks(20); assertEquals(0, h.shown)              // in the world but a room is open
        h.onHome = true; h.busy = true; h.ticks(20); assertEquals(0, h.shown)   // another prompt / the start screen up
        h.busy = false
        h.ticks(3); assertEquals("a quiet moment first", 0, h.shown)
        h.ticks(2); assertEquals(1, h.shown); assertTrue(h.open)
    }

    @Test fun neverInAFightAndPutAwayWhenOneStarts() {
        fresh()
        val h = Host().apply { inWorld = true; onHome = true; fight = true }
        h.ticks(30); assertEquals(0, h.shown)
        h.fight = false; h.ticks(5); assertEquals(1, h.shown)
        h.fight = true; h.tick()                                              // a fight starts while it shows: put away, unanswered
        assertFalse(h.open); assertFalse(HomeSettings.castBarAsked)
        h.ticks(20); assertFalse(h.open)                                      // held hidden for the whole fight
        h.fight = false; h.ticks(3); assertFalse(h.open)                      // not the moment it ends
        h.ticks(2); assertTrue(h.open); assertEquals(2, h.shown)             // back after
        h.onHome = false; h.tick(); assertFalse(h.open)                       // a room opened: away too
    }

    @Test fun yesSetsBothAndHideInCombat() {
        val p = fresh()
        HomeSettings.castBarCombat = CastStripModel.Combat.STAY               // whatever it was: Yes gives the user's defaults
        val h = Host().apply { inWorld = true; onHome = true }
        h.ticks(5); assertTrue(h.open)
        h.answer(true)
        assertEquals(CastStripModel.Side.BOTH, HomeSettings.castBarSide)
        assertEquals(CastStripModel.Combat.HIDE, HomeSettings.castBarCombat)
        assertEquals("BOTH", p.m["castBarSide"]); assertEquals("HIDE", p.m["castBarCombat"])
        assertTrue(HomeSettings.castBarAsked); assertEquals(true, p.m["castBarAsked"])
        h.ticks(60); assertEquals("never again", 1, h.shown)
    }

    @Test fun noKeepsItOffAndNeverAsksAgain() {
        val p = fresh()
        val h = Host().apply { inWorld = true; onHome = true }
        h.ticks(5); h.answer(false)
        assertEquals(OFF, HomeSettings.castBarSide)
        assertFalse("No writes no side", p.m.containsKey("castBarSide"))
        assertTrue(HomeSettings.castBarAsked)
        h.ticks(60); assertEquals(1, h.shown)
        // a restart: the same install's prefs
        HomeSettings.startForTest(p)
        val h2 = Host().apply { inWorld = true; onHome = true }
        h2.ticks(60); assertEquals(0, h2.shown)
    }

    @Test fun playersWhoSetTheBarThemselvesAreNeverAsked() {
        for (seed in listOf(hashMapOf<String, Any?>("castBarSide" to "LEFT"), hashMapOf<String, Any?>("castBarSide" to "RIGHT"),
                hashMapOf<String, Any?>("castStrip" to true),                // the old Home row's On (cb2)
                hashMapOf<String, Any?>("castBarSide" to "OFF"))) {           // turned it on, then off again in Settings
            val p = fresh(Prefs(seed))
            val h = Host()
            h.tick()                                                          // marked at once, even before the world
            assertTrue("$seed", HomeSettings.castBarAsked); assertEquals(true, p.m["castBarAsked"])
            h.inWorld = true; h.onHome = true; h.ticks(60)
            assertEquals("$seed", 0, h.shown)
        }
    }

    @Test fun theRules() {
        assertEquals(CastBarAsk.Act.NONE, CastBarAsk.decide(true, false, OFF, 99_999L))
        assertEquals(CastBarAsk.Act.MARK, CastBarAsk.decide(false, false, CastStripModel.Side.BOTH, 0L))
        assertEquals(CastBarAsk.Act.MARK, CastBarAsk.decide(false, true, OFF, 0L))
        assertEquals(CastBarAsk.Act.WAIT, CastBarAsk.decide(false, false, OFF, CastBarAsk.SETTLE_MS - 1))
        assertEquals(CastBarAsk.Act.ASK, CastBarAsk.decide(false, false, OFF, CastBarAsk.SETTLE_MS))
        assertFalse(CastBarAsk.ready(true, true, false, true))
        assertFalse(CastBarAsk.ready(false, true, false, false))
        assertTrue(CastBarAsk.ready(true, true, false, false))
        assertFalse(CastBarAsk.hideNow(false, true, true))
        assertTrue(CastBarAsk.hideNow(true, true, true)); assertTrue(CastBarAsk.hideNow(true, false, false))
        assertFalse(CastBarAsk.hideNow(true, false, true))
    }

    /** the picture's loop: at rest, the fight cue, slid away, back up - and it repeats */
    @Test fun thePictureLoops() {
        val rest = SettingsPreview.askPhase(200L)
        assertEquals(0f, rest.slide, 0f); assertEquals(0f, rest.fight, 0f)
        val mid = SettingsPreview.askPhase(1_275L)
        assertTrue(mid.slide > 0.2f && mid.slide < 0.8f); assertEquals(1f, mid.fight, 0f)
        val hid = SettingsPreview.askPhase(2_000L)
        assertEquals(1f, hid.slide, 0f); assertEquals(1f, hid.fight, 0f)
        val back = SettingsPreview.askPhase(2_900L)
        assertTrue(back.slide > 0f && back.slide < 1f); assertEquals(0f, back.fight, 0f)
        assertEquals(0f, SettingsPreview.askPhase(3_400L).slide, 0f)
        val again = SettingsPreview.askPhase(2_000L + (SettingsPreview.ASK_LOOP_S * 1000).toLong())
        assertEquals(hid.slide, again.slide, 0.01f)
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
