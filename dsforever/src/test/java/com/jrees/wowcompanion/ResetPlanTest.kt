package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RESET TO DEFAULTS, WHAT EACH BUTTON TOUCHES (the user, 2026-11-19): every key of our saved tables is in exactly one bucket (settings /
 * layouts / skills / never), every key the add-on files name is classified, each reset's preference keys are its own kind's, and
 * Layouts / Skills reach only the character asked (Everything's "All characters" every one). The never-reset keys (login, pairing,
 * downloads, Polestar, first-run marks) never go. Pure.
 */
class ResetPlanTest {
    private val me = "Shammy-Fakerealm"
    private val other = "Huntress-Fakerealm"
    private val K = ResetPlan.Kind.values().toSet()
    /** names only the add-on's comments carry (never stored): chatStyleSet (the old name of chatStyleSet2), hudsplit (hub64: session only) */
    private val COMMENT_ONLY = setOf("chatStyleSet", "hudsplit")

    private fun addonDir(): File = listOf(File("../companion/app/src/main/assets/companion/addons"), File("companion/app/src/main/assets/companion/addons"))
        .first { it.isDirectory }

    @Test fun everySavedKeyInExactlyOneBucket() {
        for ((v, never) in ResetPlan.SV_NEVER) {
            val reset = ResetPlan.SV_KEYS.filter { it.variable == v }.map { it.top }
            assertEquals("no key twice in $v", reset.size, reset.toSet().size)
            assertTrue("reset and never are apart in $v: ${reset.intersect(never)}", reset.intersect(never).isEmpty())
        }
    }

    /** every WowCompanionDB.x / WowCompanionSpellsDB.x the add-on files name (and the stores reached through helpers) is classified */
    @Test fun everyKeyTheAddonNamesIsClassified() {
        val named = HashMap<String, MutableSet<String>>()
        val re = Regex("""(WowCompanion(?:Spells)?DB)\.([A-Za-z_][A-Za-z0-9_]*)""")
        addonDir().walk().filter { it.isFile && it.name.endsWith(".lua") }.forEach { f ->
            for (m in re.findAll(f.readText())) named.getOrPut(m.groupValues[1]) { HashSet() } += m.groupValues[2]
        }
        // the per-character stores reached through accessors (db.x / DB.x), as the inventory lists them
        named.getOrPut("WowCompanionSpellsDB") { HashSet() } += listOf("faceBy", "fightBy", "formFightBy", "formSlotsBy", "cmdBy", "binds",
            "stickBy", "attackBy", "dpad", "dpadZL", "ccOff", "genUse", "lowPickBy", "srGone", "smartOn", "smartBuffs", "smartModel",
            "partyBy", "petDeadBy", "petSpellBy", "macroRoom", "radialPins", "cdLearn", "sradials", "castBar", "padLayout",
            "castBarFight", "castBarShow", "castBarParty", "castBarPShow", "rbm", "ctop")  // cb14 / cb15: reached as db[field] / rawget(db, ...)
        named.getOrPut("WowCompanionDB") { HashSet() } += listOf("prdW")
        for ((v, keys) in named) {
            val known = ResetPlan.SV_KEYS.filter { it.variable == v }.map { it.top }.toSet() + ResetPlan.SV_NEVER[v].orEmpty()
            val missing = keys.filter { it !in known && it !in COMMENT_ONLY }
            assertTrue("$v keys not classified: $missing", missing.isEmpty())
        }
    }

    @Test fun theInventoryMatchesTheRulings() {
        fun kind(v: String, top: String) = ResetPlan.SV_KEYS.first { it.variable == v && it.top == top }.kind
        val sp = "WowCompanionSpellsDB"; val db = "WowCompanionDB"
        // Layouts: the top screen's look, party layout, toolbar
        for (k in listOf("lay", "hubSw", "hubLy", "meters", "prdY", "prdS", "prdW")) assertEquals(k, ResetPlan.Kind.LAYOUTS, kind(db, k))
        assertEquals(ResetPlan.Kind.LAYOUTS, kind(sp, "dpad"))
        assertEquals("the toolbar back ON, never 'not asked'", true, ResetPlan.SV_KEYS.first { it.top == "dpad" }.default)
        // Skills: binds, fight spells, radials, Smart Y, pet wheels' picks, macros on buttons
        for (k in listOf("faceBy", "fightBy", "formFightBy", "cmdBy", "binds", "stickBy", "smartOn", "smartBuffs", "sradials", "sradial",
                "radialPins", "castBar", "castBarFight", "castBarParty", "partyBy")) assertEquals(k, ResetPlan.Kind.SKILLS, kind(sp, k))
        // cb15 (the user, 2026-11-20, Option C): Party mode's bar layer resets with Skills; Bar in Party mode is a home LAYOUTS key, the
        // add-on's copy never (the app tells it again)
        assertTrue("castBarPShow" in ResetPlan.SV_NEVER.getValue(sp))
        assertEquals(setOf("castBarPartyShow"), ResetPlan.prefKeys("home", setOf("castBarPartyShow"), setOf(ResetPlan.Kind.LAYOUTS), null, true))
        assertEquals(emptySet<String>(), ResetPlan.prefKeys("home", setOf("castBarPartyShow"), setOf(ResetPlan.Kind.SKILLS), null, true))
        // cb14 (the user, 2026-11-19, D10): the in-combat layer resets with Skills; Show in combat stays a home LAYOUTS key (castBarCombat)
        assertTrue("castBarShow" in ResetPlan.SV_NEVER.getValue(sp))
        assertEquals(setOf("castBarCombat"), ResetPlan.prefKeys("home", setOf("castBarCombat"), setOf(ResetPlan.Kind.LAYOUTS), null, true))
        assertEquals(emptySet<String>(), ResetPlan.prefKeys("home", setOf("castBarCombat"), setOf(ResetPlan.Kind.SKILLS), null, true))
        assertEquals(ResetPlan.Kind.SKILLS, kind(db, "bagBinds"))
        // Polestar, learned facts and one-time marks: never
        for (k in listOf("pqol", "pskip", "npFriends", "layV", "qtDefaults", "cursorSoftSet")) assertTrue(k, k in ResetPlan.SV_NEVER.getValue(db))
        for (k in listOf("cdLearn", "dpadZL", "srGone", "keyProbe", "smartModel", "formSlotsBy")) assertTrue(k, k in ResetPlan.SV_NEVER.getValue(sp))
    }

    @Test fun eachResetsPreferencesAreItsOwn() {
        val settings = setOf("lighterMode", "lowMemGraphics", "theme", "theme_custom", "browserLogin", "windowsId", "polestarGuide", "settingsTab",
            "questTrackingPolestar1112", "swapScreens")
        assertEquals(setOf("lighterMode", "lowMemGraphics", "theme", "swapScreens"),
            ResetPlan.prefKeys("settings", settings, setOf(ResetPlan.Kind.SETTINGS), me, false))
        assertTrue(ResetPlan.prefKeys("settings", settings, setOf(ResetPlan.Kind.LAYOUTS, ResetPlan.Kind.SKILLS), me, true).isEmpty())
        val fg = setOf("enabled", "multiplier", "flowScale", "performance", "fpsLimitContainer", "fpsLimitAsk", "fpsLimit108", "fpsLimitNote")
        assertEquals(fg - setOf("fpsLimit108", "fpsLimitNote"), ResetPlan.prefKeys("framegen", fg, setOf(ResetPlan.Kind.SETTINGS), me, false))
        val gu = setOf("mode.hud", "mode.chat", "sw.pmode", "castbar.hide", "sw.player.$me", "sw.np.$other", "hud.$me", "prdy.$me", "prdw.$other",
            "mt.dmg.$me", "mt.cdm.$other")
        assertEquals(setOf("mode.hud", "mode.chat", "sw.pmode", "castbar.hide"), ResetPlan.prefKeys("gameui", gu, setOf(ResetPlan.Kind.SETTINGS), me, false))
        assertEquals("this character's layouts only", setOf("sw.player.$me", "hud.$me", "prdy.$me", "mt.dmg.$me"),
            ResetPlan.prefKeys("gameui", gu, setOf(ResetPlan.Kind.LAYOUTS), me, false))
        assertEquals("every character's", setOf("sw.player.$me", "sw.np.$other", "hud.$me", "prdy.$me", "prdw.$other", "mt.dmg.$me", "mt.cdm.$other"),
            ResetPlan.prefKeys("gameui", gu, setOf(ResetPlan.Kind.LAYOUTS), me, true))
        val home = setOf("rest", "clockGold", "radialTap", "pauseOverGame", "smartPet.$me", "smartPet.$other", "holdTalk.$me", "castBarAsked",
            "ruling.hide.2026-09-28")
        assertEquals(setOf("pauseOverGame"), ResetPlan.prefKeys("home", home, setOf(ResetPlan.Kind.SETTINGS), me, false))
        assertEquals(setOf("rest", "clockGold", "radialTap"), ResetPlan.prefKeys("home", home, setOf(ResetPlan.Kind.LAYOUTS), me, false))
        assertEquals(setOf("smartPet.$me"), ResetPlan.prefKeys("home", home, setOf(ResetPlan.Kind.SKILLS), me, false))
        assertEquals("first-run marks and chat stay", emptySet<String>(),
            ResetPlan.prefKeys("home", setOf("holdTalk.$me", "castBarAsked", "ruling.hide.2026-09-28"), K, me, true))
        val dp = setOf("choice_$me", "pend_choice_$me", "owed_choice_$me", "choice_$other")
        assertEquals(setOf("choice_$me"), ResetPlan.prefKeys("dpad", dp, setOf(ResetPlan.Kind.LAYOUTS), me, false))
        assertEquals(setOf("quality"), ResetPlan.prefKeys("dsf_stream", setOf("quality", "host", "client_id", "client_cert", "client_key"), K, me, true))
        assertEquals(setOf("mask", "chests"), ResetPlan.prefKeys("mapfilters", setOf("mask", "chests"), setOf(ResetPlan.Kind.SETTINGS), me, false))
        for (f in listOf("addons", "polestar_qol", "home_login_prefs", "battle_net_login", "voice", "wow_forever", "chat_filter"))
            assertTrue(f, ResetPlan.prefKeys(f, setOf("x", "pq.$me", "auto_login"), K, me, true).isEmpty())
    }

    @Test fun noCharacterNoCharacterKeys() {
        val gu = setOf("sw.player.$me", "mode.hud")
        assertEquals(setOf("mode.hud"), ResetPlan.prefKeys("gameui", gu, K, null, false))
        assertTrue(ResetPlan.svTargets(setOf(ResetPlan.Kind.LAYOUTS, ResetPlan.Kind.SKILLS), null, false, emptyMap()).isEmpty())
        assertFalse(ResetPlan.isCharKey("mode"))
        assertTrue(ResetPlan.isCharKey("Yes-Classic Beta PvE"))
    }
}
