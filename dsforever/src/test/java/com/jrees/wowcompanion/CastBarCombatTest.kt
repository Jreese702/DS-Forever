package com.jrees.wowcompanion

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TAP-TO-CAST BAR IN A FIGHT (the user, 2026-11-13: "there should be a settings toggle to make them Hide/stay in combat"; the picks:
 * the bar's own Bottom-tab section, "Hide in combat" the default): the setting (Hide by default, saved by name), the fade on Home's fight
 * (out at the start, back at the end, reversing from where it is), no touch while hidden, Stay seen and tappable in a fight (the
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
        assertEquals(listOf("Hide", "Stay"), CastStripModel.Combat.values().map { it.label })
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

    @Test fun hideFadesOutWhenTheFightStartsAndBackWhenItEnds() {
        val f = CastStripModel.FightFade(ms = 100f)
        assertFalse(f.note(HIDE, false, 0))                                   // at rest: shown, nothing changes
        assertEquals(1f, f.vis(0), 0f); assertTrue(f.takesTouch())
        assertTrue(f.note(HIDE, true, 1_000))                                 // the fight starts
        assertTrue(f.hidden)
        assertEquals(0.5f, f.vis(1_050), 0.01f); assertTrue(f.fading(1_050))
        assertEquals(0f, f.vis(1_100), 0f); assertFalse(f.fading(1_200))
        assertFalse(f.note(HIDE, true, 1_500))                                // still fighting: no change
        assertTrue(f.note(HIDE, false, 2_000))                                // the fight ends
        assertFalse(f.hidden)
        assertEquals(0.5f, f.vis(2_050), 0.01f)
        assertEquals(1f, f.vis(2_200), 0f); assertTrue(f.takesTouch())
    }

    @Test fun aFadeReversesFromWhereItIs() {
        val f = CastStripModel.FightFade(ms = 100f)
        f.note(HIDE, true, 0)
        f.note(HIDE, false, 50)                                               // half way out: back from 0.5
        assertEquals(0.5f, f.vis(50), 0.01f)
        assertEquals(0.75f, f.vis(100), 0.01f)
    }

    @Test fun noTouchWhileHidden() {
        val f = CastStripModel.FightFade()
        f.note(HIDE, true, 0)
        assertFalse(f.takesTouch())
        assertFalse(f.takesTouch())                                           // at once, not only once faded
        // the view's own touch gate: its very first line refuses every touch while hidden
        val v = src("CastStripView.kt")
        val touch = v.substringAfter("override fun onTouchEvent(ev: MotionEvent): Boolean {")
        assertTrue(touch.trimStart().lines().take(2).joinToString("\n").contains("if (!fightFade.takesTouch()) return false"))
        assertTrue(v.contains("if (vis <= 0f) return"))                       // nothing drawn once faded out
    }

    @Test fun stayIsSeenAndTappableInAFight() {
        val f = CastStripModel.FightFade()
        assertFalse(f.note(STAY, true, 0))
        assertFalse(f.hidden); assertEquals(1f, f.vis(10_000), 0f); assertTrue(f.takesTouch())
        // switched to Stay mid-fight: it comes back
        val g = CastStripModel.FightFade(ms = 100f)
        g.note(HIDE, true, 0)
        assertTrue(g.note(STAY, true, 500)); assertTrue(g.takesTouch()); assertEquals(1f, g.vis(600), 0f)
        assertFalse(CastStripModel.hiddenIn(STAY, true)); assertTrue(CastStripModel.hiddenIn(HIDE, true))
        assertFalse(CastStripModel.hiddenIn(HIDE, false))
    }

    /** Home's fight is fightShown() (the takeover with Party mode off, the grow's fight with it on); the bar is laid in the takeover
     *  (only a module full screen outside the takeover drops it), so Stay keeps it in fights with Party mode on and off */
    @Test fun homeFollowsItsOwnFightSignal() {
        val h = src("HomeView.kt")
        assertTrue(h.contains("private fun castStripFight() = castStrip.fight(HomeSettings.castBarCombat, fightShown())"))
        assertTrue(h.contains("fun fightShown(): Boolean = flow.takeover || grow.fighting(System.currentTimeMillis())"))
        assertTrue(h.contains("if (s.full != null && !s.takeover) { laidSide = CastStripModel.Side.OFF; return base }"))
        assertTrue(h.contains("return withStrip(withParty(base, s, w), w, h)"))
        // checked on every tick (a fight's start / end), every apply (the takeover) and every settings change
        assertEquals(3, Regex("""castStripFight\(\)""").findAll(h).count() - 1)
    }

    @Test fun theRowAndItsPicture() {
        assertEquals(listOf("Tap-to-cast bar", "In combat"), SettingsTabs.Block.CAST_BAR.rows)
        val hs = src("HomeSettings.kt")
        assertTrue(hs.contains("row(c, y + GAP, \"In combat\""))
        assertTrue(hs.indexOf("\"Tap-to-cast bar\", listOf(") < hs.indexOf("\"In combat\", listOf("))
        assertTrue(SettingsPreview.has("In combat"))
        assertEquals("Cast bar in combat: Hide", SettingsPreview.title("In combat", SettingsPreview.State()))
        assertEquals("Cast bar in combat: Stay", SettingsPreview.title("In combat", SettingsPreview.State(castCombatHide = false)))
        assertTrue(SettingsPreview.caption("In combat", SettingsPreview.State()).first().contains("fades"))
        assertTrue(SettingsPreview.caption("In combat", SettingsPreview.State(castCombatHide = false)).first().contains("stays"))
    }

    @Test fun theWordsAreInSpanishAndFrench() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { java.io.File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) {
            Tr.load(l, java.io.File(dir, "$l.json").readText(Charsets.UTF_8))
            for (w in listOf("In combat", "Hide", "Stay", "Hide: the bar fades out in a fight.", "Cast bar in combat: Hide",
                "Cast bar in combat: Stay", "In a fight the tap-to-cast bar fades", "out; it comes back when the fight ends.",
                "In a fight the tap-to-cast bar stays,", "one tap casts each.", "Hide: the bar fades out", "Stay: the bar stays",
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
