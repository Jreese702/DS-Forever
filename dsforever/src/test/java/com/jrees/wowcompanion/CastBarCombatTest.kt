package com.jrees.wowcompanion

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TAP-TO-CAST BAR IN A FIGHT (the user, 2026-11-13: "there should be a settings toggle to make them Hide/stay in combat"; the picks:
 * the bar's own Bottom-tab section, "Hide in combat" the default): the setting (Hide by default, saved by name), cb7's slide on Home's
 * fight (the user: "it should slide down behind the home bar out of view, not fade out and give that space back to the map or chat":
 * below the page at the start, its column taking the room, back at the end, one progress for both), no touch while hidden or sliding, Stay seen and tappable in a fight (the
 * takeover with Party mode off and the grow's fight with it on are both Home's fightShown()), the Settings row and its picture.
 */
class CastBarCombatTest {
    private val HIDE = CastStripModel.Combat.HIDE
    private val STAY = CastStripModel.Combat.STAY

    @After fun down() { HomeSettings.startForTest(null) }

    @Test fun hideIsTheDefault() {
        HomeSettings.startForTest(null)
        assertEquals(HIDE, HomeSettings.castBarCombat)
        HomeSettings.startForTest(Prefs())
        assertEquals(HIDE, HomeSettings.castBarCombat)
        assertEquals(HIDE, CastStripModel.combatOf(null))
        assertEquals(HIDE, CastStripModel.combatOf("SOMETHING"))
        assertEquals(listOf("Off", "On"), CastStripModel.Combat.values().map { it.label })      // cb14 (D5b): "Show in combat: Off | On"
    }

    @Test fun theChoiceIsSavedByName() {
        val p = Prefs()
        HomeSettings.startForTest(p)
        HomeSettings.castBarCombat = STAY
        assertEquals("STAY", p.m["castBarCombat"])
        assertEquals(STAY, HomeSettings.castBarCombat)
        HomeSettings.castBarCombat = HIDE
        assertEquals("HIDE", p.m["castBarCombat"])
        HomeSettings.startForTest(Prefs(hashMapOf("castBarCombat" to "STAY")))
        assertEquals(STAY, HomeSettings.castBarCombat)
    }

    @Test fun hideGoesWhenTheFightStartsAndBackWhenItEnds() {
        val f = CastStripModel.FightGate()
        assertFalse(f.note(HIDE, false))                                      // at rest: shown, nothing changes
        assertTrue(f.takesTouch()); assertFalse(f.gone)
        assertTrue(f.note(HIDE, true))                                        // the fight starts: Home lays the page again
        assertTrue(f.hidden)
        assertFalse(f.note(HIDE, true))                                       // still fighting: no change
        assertTrue(f.note(HIDE, false))                                       // the fight ends
        assertFalse(f.hidden); assertTrue(f.takesTouch())
    }

    /** cb7 (the user: "it should slide down behind the home bar out of view, not fade out and give that space back to the map or
     *  chat"): the hidden box is the laid one moved down until its top is the page's foot; the column's bottom goes back to its own */
    @Test fun hiddenTheBarLiesBelowThePageAndTheColumnTakesItsRoom() {
        val h = 990f
        for (side in listOf(CastStripModel.Side.LEFT, CastStripModel.Side.RIGHT, CastStripModel.Side.BOTH)) {
            val b = CastStripModel.place(side, 1240f, h, UiKit.MODULE_GAP)!!
            val x = CastStripModel.hiddenBox(b, h)
            assertEquals(h, x.t, 0f); assertEquals(b.h, x.h, 0f); assertEquals(b.l, x.l, 0f); assertEquals(b.r, x.r, 0f)
        }
        assertEquals(300f, CastStripModel.columnBottom(300f, 450f, hidden = false), 0f)
        assertEquals(450f, CastStripModel.columnBottom(300f, 450f, hidden = true), 0f)
    }

    /** one progress for both: half way down the bar has gone half its way and the column grown half its way */
    @Test fun theSlideAndTheColumnShareOneProgress() {
        val b = CastStripModel.Box(10f, 800f, 610f, 932f)
        val h = 950f
        for (k in listOf(0f, 0.25f, 0.5f, 1f)) {
            val s = CastStripModel.slideAt(b, h, k)
            val col = CastStripModel.columnAt(790f, 940f, k)
            assertEquals((s.t - b.t) / (h - b.t), (col - 790f) / (940f - 790f), 1e-4f)
            assertEquals(b.h, s.h, 1e-3f)
        }
        assertEquals(CastStripModel.hiddenBox(b, h), CastStripModel.slideAt(b, h, 1f))
        assertEquals(b, CastStripModel.slideAt(b, h, 0f))
    }

    @Test fun noTouchWhileHiddenOrSliding() {
        val f = CastStripModel.FightGate()
        f.note(HIDE, true)
        assertFalse(f.takesTouch())                                           // at once, not only once it landed
        f.sliding = true; assertFalse(f.gone)                                 // on its way: drawn, no touch
        f.sliding = false; assertTrue(f.gone)                                 // landed below the page: not drawn
        f.note(HIDE, false); f.sliding = true
        assertFalse(f.takesTouch())                                           // coming back up: still no touch
        f.sliding = false; assertTrue(f.takesTouch())
        // the view's own touch gate: its very first line refuses every touch while hidden / sliding; no fade any more
        val v = src("CastStripView.kt")
        val touch = v.substringAfter("override fun onTouchEvent(ev: MotionEvent): Boolean {")
        assertTrue(touch.trimStart().lines().take(2).joinToString("\n").contains("if (!fightGate.takesTouch()) return false"))
        assertTrue(v.contains("if (fightGate.gone) return"))
        assertFalse(v.contains("saveLayerAlpha"))
    }

    @Test fun stayIsSeenAndTappableInAFight() {
        val f = CastStripModel.FightGate()
        assertFalse(f.note(STAY, true))
        assertFalse(f.hidden); assertTrue(f.takesTouch())
        // switched to Stay mid-fight: it comes back
        val g = CastStripModel.FightGate()
        g.note(HIDE, true)
        assertTrue(g.note(STAY, true)); assertTrue(g.takesTouch())
        assertFalse(CastStripModel.hiddenIn(STAY, true)); assertTrue(CastStripModel.hiddenIn(HIDE, true))
        assertFalse(CastStripModel.hiddenIn(HIDE, false))
    }

    /** Home's fight is fightShown() (the takeover with Party mode off, the grow's fight with it on); the bar is laid in the takeover
     *  (only a module full screen outside the takeover drops it), so Stay keeps it in fights with Party mode on and off */
    @Test fun homeFollowsItsOwnFightSignal() {
        val h = src("HomeView.kt")
        // movable Home P2: both halves of a Split bar (castStrip2) take the same gate
        assertTrue(h.contains("private fun castStripGate() { for (st in strips) st.fight(castCombatNow(), fightShown(), castPartyKeep()) }"))
        assertTrue(h.contains("CastStripModel.hiddenIn(castCombatNow(), fightShown(), castPartyKeep(), true)"))     // cb15: Bar in Party mode
        // the player's own rule (a preview look - the cast bar question's picture - shows Hide); In fights = Top screen keeps the bar
        // (the 11-22 shots: TopCombatBar.stripCombat - STAY with Top screen live, Party mode's fight too (the 1519 audit), else the player's setting)
        assertTrue(h.contains("if (renderLook?.castSide != null) CastStripModel.Combat.HIDE\n" +
            "        else TopCombatBar.stripCombat(layoutNow(), HomeSettings.castBarCombat)"))
        // cb7: a change of hidden lays the page again with the one motion; apply itself only gates (no re-lay from inside apply)
        assertTrue(h.contains("if (laidSide == CastStripModel.Side.OFF || castHiddenNow() == laidHidden) return"))
        val apply = h.substringAfter("private fun apply(s: HomeScreen, animate: Boolean) {").substringBefore("\n    private fun big(")
        assertTrue(apply.contains("castStripGate()")); assertFalse(apply.contains("castStripFight()"))
        assertTrue(h.contains("fun fightShown(): Boolean = flow.takeover || grow.fighting(System.currentTimeMillis())"))
        // movable Home: the solver lays the bar (HomeLayoutSolver: a module full screen outside the takeover has none, the bar in the takeover)
        assertTrue(h.contains("if (full) laidSide = CastStripModel.Side.OFF"))
        assertTrue(h.contains("castHidden = !full && castHiddenNow()"))
        // checked on every tick (a fight's start / end), every settings change and (cb15) Party mode's on / off; the gate on every apply
        assertEquals(3, Regex("""castStripFight\(\)""").findAll(h).count() - 1)
    }

    @Test fun theRowAndItsPicture() {
        assertEquals(listOf("Tap-to-cast bar", "Show in combat"), SettingsTabs.Block.CAST_BAR.rows)      // cb14 (D5b)
        val hs = src("HomeSettings.kt")
        assertTrue(hs.contains("row(c, y + GAP, \"Show in combat\""))
        assertTrue(hs.indexOf("\"Tap-to-cast bar\", listOf(") < hs.indexOf("\"Show in combat\", listOf("))
    }

    @Test fun theWordsAreInSpanishAndFrench() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { java.io.File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) {
            Tr.load(l, java.io.File(dir, "$l.json").readText(Charsets.UTF_8))
            for (w in listOf("In combat", "Hide", "Stay", "Show in combat", "Off", "On", "Off: the bar slides away in a fight.", "Tap to cast",
                "Turn on", "Hidden in fights", "Bar is off", "Show tap-to-cast", "Out of combat", "Controller", "Hide: the bar slides away in a fight.", "Cast bar in combat: Hide",
                "Cast bar in combat: Stay", "In a fight the tap-to-cast bar slides", "down; chat and map take its room.",
                "In a fight the tap-to-cast bar stays,", "one tap casts each.", "Hide: the bar slides away", "Stay: the bar stays",
                "Eight spells in one bar under both;", "Eight spells, one tap each.",
                "In a fight:", "no bar, no taps", "tap to cast"))
                assertTrue("$l: $w", Tr.t(w, l) != w)
        }
    }

    private fun src(name: String): String {
        val dir = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
            .map { java.io.File(it) }.first { it.isDirectory }
        return java.io.File(dir, name).readText()
    }

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
}
