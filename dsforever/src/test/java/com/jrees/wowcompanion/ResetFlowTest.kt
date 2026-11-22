package com.jrees.wowcompanion

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * RESET TO DEFAULTS, THE WAY THROUGH (the user, 2026-11-19): the pop-up asks first (Yes / No; Everything: This character / All characters,
 * then Yes / No), and while it is up the pad is held from the game; with the game up the add-on's half goes first and the app's half only
 * on its ok (a fight or no answer changes nothing), then the game's Reload; with no game the app's half alone. The app's half removes
 * only its own kind's saved values (a backup first), sets the toolbar back ON, moves the In-combat backup away, and gives back only the
 * game settings DS Forever recorded the player's own values for. Pure (a map-backed SharedPreferences; temp folders).
 */
class ResetFlowTest {
    private val root: File = Files.createTempDirectory("resetflow").toFile()
    @After fun clean() { root.deleteRecursively() }
    private val me = "Shammy-Fakerealm"
    private val other = "Huntress-Fakerealm"

    // ---------------------------------------------------------------- the pop-up

    @Test fun everyButtonAsksYesOrNo() {
        for (b in listOf(ResetPlan.Button.SETTINGS, ResetPlan.Button.LAYOUTS, ResetPlan.Button.SKILLS)) {
            val a = ResetAsk(b, whoKnown = true)
            assertEquals(ResetAsk.Step.CONFIRM, a.step)
            assertEquals(listOf("Yes", "No"), a.choices)
            assertEquals(ResetAsk.Out.None, a.key("PADDUP"))               // nothing else does anything
            assertEquals(ResetAsk.Out.None, a.key("PAD3"))
            assertEquals(ResetAsk.Out.Closed, ResetAsk(b, true).key("PAD2"))
            assertEquals(ResetAsk.Out.Yes(false), a.key("PAD1"))
        }
        assertEquals("Reset settings?", ResetAsk(ResetPlan.Button.SETTINGS, true).title)
        assertEquals("Back to defaults.", ResetAsk(ResetPlan.Button.SETTINGS, true).line)
        assertEquals("This character, back to defaults.", ResetAsk(ResetPlan.Button.SKILLS, true).line)
        // a touch on No closes; Right then A = No too
        assertEquals(ResetAsk.Out.Closed, ResetAsk(ResetPlan.Button.LAYOUTS, true).pick(1))
        val r = ResetAsk(ResetPlan.Button.LAYOUTS, true); r.key("PADDRIGHT")
        assertEquals(ResetAsk.Out.Closed, r.key("PAD1"))
    }

    @Test fun everythingAsksWhichCharactersThenConfirms() {
        val a = ResetAsk(ResetPlan.Button.EVERYTHING, whoKnown = true)
        assertEquals(ResetAsk.Step.SCOPE, a.step)
        assertEquals(listOf("This character", "All characters"), a.choices)
        a.key("PADDRIGHT"); assertEquals(1, a.lit)
        assertEquals(ResetAsk.Out.None, a.key("PAD1"))
        assertEquals(ResetAsk.Step.CONFIRM, a.step)
        assertEquals("All characters, back to defaults.", a.line)
        // B on the confirm: back to the scope; B again: closed
        assertEquals(ResetAsk.Out.None, a.key("PAD2")); assertEquals(ResetAsk.Step.SCOPE, a.step)
        a.key("PADDLEFT"); a.key("PAD1")
        assertEquals("This character, back to defaults.", a.line)
        assertEquals(ResetAsk.Out.Yes(false), a.key("PAD1"))
        assertEquals(ResetAsk.Out.Closed, ResetAsk(ResetPlan.Button.EVERYTHING, true).key("PAD2"))
        // no character known: This character cannot be picked, All characters is lit
        val n = ResetAsk(ResetPlan.Button.EVERYTHING, whoKnown = false)
        assertFalse(n.enabled(0)); assertEquals(1, n.lit)
        assertEquals(ResetAsk.Out.None, n.pick(0)); assertEquals(ResetAsk.Step.SCOPE, n.step)
        n.key("PADDLEFT"); assertEquals(1, n.lit)
        n.key("PAD1"); assertEquals(ResetAsk.Out.Yes(true), n.key("PAD1"))
    }

    /** the 1503 pattern: while the pop-up is up every pad button is held from the game, and for the grace after (the hold race) */
    @Test fun thePopUpHoldsThePad() {
        val w = PopupHold.Wheel()
        assertFalse(w.holds(1_000L))
        w.up()
        assertTrue(w.holds(1_000L))
        for (k in listOf(android.view.KeyEvent.KEYCODE_BUTTON_A, android.view.KeyEvent.KEYCODE_BUTTON_B, android.view.KeyEvent.KEYCODE_DPAD_LEFT))
            assertTrue(PopupHold.holdsKey(false, k, down = true, wheel = w.holds(1_000L)))
        assertFalse("a release always passes", PopupHold.holdsKey(false, android.view.KeyEvent.KEYCODE_BUTTON_A, down = false, wheel = true))
        w.down(2_000L)
        assertTrue(w.holds(2_000L + PopupHold.GRACE_MS - 1))
        assertFalse(w.holds(2_000L + PopupHold.GRACE_MS + 1))
    }

    // ---------------------------------------------------------------- the flow

    private class Rig(var live: Boolean = true, var tapOk: Boolean = true, var up: Boolean = live) {
        var now = 10_000L
        val taps = ArrayList<Int>()
        val applied = ArrayList<Pair<ResetPlan.Button, Boolean>>()
        var reloads = 0
        val flow = ResetFlow({ live }, { up }, { c -> taps += c; tapOk }, { b, all -> applied += b to all; ResetRun.Result(1, 1, 0, emptyList(), false, emptyList()) },
            { reloads++ }, { now })
    }

    @Test fun noGameTheAppsHalfAlone() {
        val r = Rig(live = false)
        r.flow.start(ResetPlan.Button.SKILLS, false)
        assertEquals(listOf(ResetPlan.Button.SKILLS to false), r.applied)
        assertTrue(r.taps.isEmpty()); assertEquals(0, r.reloads)
        assertEquals(ResetFlow.DONE, r.flow.word(ResetPlan.Button.SKILLS))
        assertNull("another button keeps its name", r.flow.word(ResetPlan.Button.LAYOUTS))
        r.now += ResetFlow.SHOW_MS + 1
        assertNull(r.flow.word(ResetPlan.Button.SKILLS))
    }

    @Test fun theGameUpButNotInTheWorldChangesNothing() {
        val r = Rig(live = false, up = true)
        r.flow.start(ResetPlan.Button.LAYOUTS, false)
        assertTrue(r.applied.isEmpty()); assertTrue(r.taps.isEmpty())
        assertEquals(ResetFlow.NOT_NOW, r.flow.word(ResetPlan.Button.LAYOUTS))
    }

    @Test fun theGameUpTheAddonFirstThenTheAppThenReload() {
        val r = Rig()
        r.flow.start(ResetPlan.Button.LAYOUTS, false)
        assertEquals(listOf(21), r.taps)
        assertTrue("nothing changed before the add-on's answer", r.applied.isEmpty())
        assertEquals(ResetFlow.WORKING, r.flow.word(ResetPlan.Button.LAYOUTS))
        r.flow.start(ResetPlan.Button.SKILLS, false)                          // one at a time
        assertEquals(1, r.taps.size)
        r.flow.onAck(22, true, null)                                           // another column's answer: not this one
        assertTrue(r.applied.isEmpty())
        r.flow.onAck(21, true, null)
        assertEquals(listOf(ResetPlan.Button.LAYOUTS to false), r.applied)
        assertEquals(1, r.reloads)
        assertEquals(ResetFlow.DONE, r.flow.word(ResetPlan.Button.LAYOUTS))
    }

    @Test fun aFightOrNoAnswerChangesNothing() {
        val r = Rig()
        r.flow.start(ResetPlan.Button.EVERYTHING, true)
        assertEquals(listOf(27), r.taps)
        r.flow.onAck(27, false, "combat")
        assertTrue(r.applied.isEmpty()); assertEquals(0, r.reloads)
        assertEquals(ResetFlow.NOT_IN_COMBAT, r.flow.word(ResetPlan.Button.EVERYTHING))
        r.now += ResetFlow.SHOW_MS + 1
        r.flow.start(ResetPlan.Button.SETTINGS, true)
        assertEquals("Settings has no 'every character'", 20, r.taps.last())
        r.now += ResetFlow.ACK_MS + 1
        assertEquals(ResetFlow.NO_ANSWER, r.flow.word(ResetPlan.Button.SETTINGS))
        assertFalse(r.flow.busy)
        r.flow.onAck(20, true, null)                                           // too late: nothing
        assertTrue(r.applied.isEmpty())
        val refused = Rig(tapOk = false)
        refused.flow.start(ResetPlan.Button.SKILLS, false)
        assertTrue(refused.applied.isEmpty())
        assertEquals(ResetFlow.NO_ANSWER, refused.flow.word(ResetPlan.Button.SKILLS))
    }

    @Test fun theColumns() {
        assertEquals(20, ResetFlow.column(ResetPlan.Button.SETTINGS, true))
        assertEquals(21, ResetFlow.column(ResetPlan.Button.LAYOUTS, false))
        assertEquals(22, ResetFlow.column(ResetPlan.Button.SKILLS, false))
        assertEquals(23, ResetFlow.column(ResetPlan.Button.EVERYTHING, false))
        assertEquals(25, ResetFlow.column(ResetPlan.Button.LAYOUTS, true))
        assertEquals(27, ResetFlow.column(ResetPlan.Button.EVERYTHING, true))
    }

    // ---------------------------------------------------------------- the app's half (ResetRun)

    /** a SharedPreferences over a map (commit / apply write at once) */
    private class MapPrefs : SharedPreferences {
        val m = LinkedHashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = LinkedHashMap(m)
        override fun getString(k: String?, d: String?) = m[k] as? String ?: d
        override fun getStringSet(k: String?, d: MutableSet<String>?) = d
        override fun getInt(k: String?, d: Int) = m[k] as? Int ?: d
        override fun getLong(k: String?, d: Long) = m[k] as? Long ?: d
        override fun getFloat(k: String?, d: Float) = m[k] as? Float ?: d
        override fun getBoolean(k: String?, d: Boolean) = m[k] as? Boolean ?: d
        override fun contains(k: String?) = m.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val put = LinkedHashMap<String, Any?>(); val rm = HashSet<String>()
            override fun putString(k: String, v: String?) = apply { put[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = apply { put[k] = v }
            override fun putInt(k: String, v: Int) = apply { put[k] = v }
            override fun putLong(k: String, v: Long) = apply { put[k] = v }
            override fun putFloat(k: String, v: Float) = apply { put[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { put[k] = v }
            override fun remove(k: String) = apply { rm += k }
            override fun clear() = apply { rm += m.keys }
            override fun commit(): Boolean { for (k in rm) m.remove(k); m.putAll(put); return true }
            override fun apply() { commit() }
        }
    }

    private val prefs = HashMap<String, MapPrefs>()
    private fun p(f: String) = prefs.getOrPut(f) { MapPrefs() }
    private val kept = File(root, "kept")
    private val combat = File(root, "combat")
    private fun run() = ResetRun({ p(it) }, kept, combat) {}

    private fun seed() {
        p("settings").m.putAll(mapOf("lighterMode" to true, "lowMemGraphics" to false, "theme" to "oled_rose", "browserLogin" to true,
            "windowsId" to "original", "polestarGuide" to "on", "theme_custom" to "{}"))
        p("framegen").m.putAll(mapOf("enabled" to true, "multiplier" to 3, "performance" to false, "fpsLimitContainer" to 60, "fpsLimit108" to true))
        p("gameui").m.putAll(mapOf("mode.hud" to "OFF", "sw.player.$me" to true, "sw.player.$other" to true, "prdy.$me" to -300))
        p("home").m.putAll(mapOf("rest" to "THIN", "radialTap" to false, "smartPet.$me" to false, "smartPet.$other" to false, "castBarAsked" to true))
        p("dpad").m.putAll(mapOf("choice_$me" to false, "owed_choice_$me" to true, "choice_$other" to false))
        p("radial_meta").m["all"] = "{\"$me\":{\"1\":{\"n\":\"Heals\"}},\"$other\":{\"1\":{\"n\":\"Traps\"}}}"
        p("wc_radial").m.putAll(mapOf("who" to me, "doc" to "{}", "sdoc" to "{}"))
        p("dsf_stream").m.putAll(mapOf("quality" to "{\"fps\":120}", "host" to "pc", "client_key" to "k"))
        p("addons").m["polestar.core"] = true
        combat.mkdirs(); File(combat, CombatKeep.fileName(me)).writeText("{}"); File(combat, CombatKeep.fileName(other)).writeText("{}")
    }

    @Test fun settingsResetTakesOnlySettings() {
        seed()
        val r = run().run(ResetPlan.Button.SETTINGS, me, false, gameRunning = false)
        assertTrue(r.changed)
        assertEquals("the sign-in, Polestar and the made theme stay", setOf("browserLogin", "windowsId", "polestarGuide", "theme_custom"), p("settings").m.keys)
        assertEquals(setOf("fpsLimit108"), p("framegen").m.keys)
        assertEquals(setOf("sw.player.$me", "sw.player.$other", "prdy.$me"), p("gameui").m.keys)
        assertEquals(setOf("host", "client_key"), p("dsf_stream").m.keys)
        assertEquals("layouts and skills untouched", false, p("home").m["smartPet.$me"])
        assertEquals("THIN", p("home").m["rest"])
        assertTrue(p("addons").m.isNotEmpty())
        assertTrue(File(combat, CombatKeep.fileName(me)).isFile)
        // a backup of what went
        assertTrue(File(kept, SvReset.BACKUP_DIR).listFiles()!!.any { it.name.startsWith("prefs-settings.") })
    }

    @Test fun layoutsResetTakesThisCharactersLayoutsAndTheToolbarComesBackOn() {
        seed()
        run().run(ResetPlan.Button.LAYOUTS, me, false, gameRunning = false)
        assertEquals(setOf("mode.hud", "sw.player.$other"), p("gameui").m.keys)
        assertFalse("Home's arrangement back", p("home").m.containsKey("rest"))
        assertFalse(p("home").m.containsKey("radialTap"))
        assertEquals(false, p("home").m["smartPet.$me"])
        assertEquals("the toolbar ON, pending until the game says it", true, p("dpad").m["choice_$me"])
        assertEquals(true, p("dpad").m["pend_choice_$me"])
        assertFalse("the prompt stays answered", p("dpad").m.containsKey("owed_choice_$me"))
        assertEquals("another character's choice untouched", false, p("dpad").m["choice_$other"])
        assertEquals(true, p("settings").m["lighterMode"])
    }

    /** fix round 3: "This character" Layouts = its own Home record back to Classic (WRITTEN under its key: the default it would fall back
     *  to is the device's seed and stays), the three "Mine" slots stay; Everything with All characters takes them all */
    @Test fun aLayoutsResetKeepsTheDevicesSlotsAndSeed() {
        seed()
        val mirrored = HomeLayoutJson.json(HomeLayout(chatSide = HomeLayout.Side.RIGHT))
        p("home").m.putAll(mapOf("${HomeLayoutStore.KEY_PREFIX}$me" to mirrored, "${HomeLayoutStore.KEY_PREFIX}$other" to mirrored,
            HomeLayoutStore.KEY_DEFAULT to mirrored, HomeLayoutStore.KEY_SLOTS_LEGACY to "[null,{},null]", HomeLayoutStore.KEY_MIGRATED to true))
        run().run(ResetPlan.Button.LAYOUTS, me, false, gameRunning = false)
        assertEquals("Classic under my own key", HomeLayout(), HomeLayoutJson.parse(p("home").m["${HomeLayoutStore.KEY_PREFIX}$me"] as String))
        assertEquals("another character's record untouched", mirrored, p("home").m["${HomeLayoutStore.KEY_PREFIX}$other"])
        assertEquals("the first-login seed stays", mirrored, p("home").m[HomeLayoutStore.KEY_DEFAULT])
        assertEquals("the device's slots stay", "[null,{},null]", p("home").m[HomeLayoutStore.KEY_SLOTS_LEGACY])
        // Everything, This character: the same
        run().run(ResetPlan.Button.EVERYTHING, me, false, gameRunning = false)
        assertEquals("[null,{},null]", p("home").m[HomeLayoutStore.KEY_SLOTS_LEGACY])
        assertEquals(mirrored, p("home").m[HomeLayoutStore.KEY_DEFAULT])
        // the store reads Classic for me (never the default) after the reset
        HomeSettings.startForTest(p("home")); HomeLayoutStore.character(me)
        assertEquals(HomeLayout(), HomeSettings.layout)
        HomeSettings.startForTest(null)
        // Everything, All characters: every record, the seed and the slots go (nothing written)
        run().run(ResetPlan.Button.EVERYTHING, me, true, gameRunning = false)
        assertTrue(p("home").m.keys.none { it.startsWith(HomeLayoutStore.KEY_PREFIX) || it == HomeLayoutStore.KEY_DEFAULT || it == HomeLayoutStore.KEY_SLOTS_LEGACY })
    }

    @Test fun skillsResetTakesThisCharactersSkillsOnly() {
        seed()
        val r = run().run(ResetPlan.Button.SKILLS, me, false, gameRunning = false)
        assertFalse(p("home").m.containsKey("smartPet.$me"))
        assertEquals(false, p("home").m["smartPet.$other"])
        assertEquals("{\"$other\":{\"1\":{\"n\":\"Traps\"}}}", p("radial_meta").m["all"])
        assertFalse(p("wc_radial").m.containsKey("doc"))
        assertEquals(me, p("wc_radial").m["who"])
        assertEquals(1, r.combat)
        assertFalse("my In-combat backup moved away (it would be put back)", File(combat, CombatKeep.fileName(me)).exists())
        assertTrue(File(combat, CombatKeep.fileName(other)).isFile)
        assertTrue(File(kept, SvReset.BACKUP_DIR).listFiles()!!.any { it.name.startsWith(CombatKeep.fileName(me)) })
        assertEquals("THIN", p("home").m["rest"])
    }

    @Test fun everythingAllCharacters() {
        seed()
        run().run(ResetPlan.Button.EVERYTHING, me, true, gameRunning = false)
        assertEquals(setOf("browserLogin", "windowsId", "polestarGuide", "theme_custom"), p("settings").m.keys)
        assertTrue(p("gameui").m.isEmpty())
        assertEquals(setOf("castBarAsked"), p("home").m.keys)
        assertEquals("{}", p("radial_meta").m["all"])
        assertEquals(true, p("dpad").m["choice_$other"])
        assertTrue(combat.listFiles()!!.isEmpty())
        assertTrue(p("addons").m.isNotEmpty())
    }

    // ---------------------------------------------------------------- the game's own settings

    private val wtf = File(root, "WTF")
    private fun config(vararg lines: String) { wtf.mkdirs(); File(wtf, "Config.wtf").writeText(lines.joinToString("\n") + "\n") }
    private fun configLines() = File(wtf, "Config.wtf").readLines().filter { it.isNotBlank() }

    @Test fun onlyRecordedOriginalsComeBack() {
        // DS Forever lowered these (8 GB) and kept the player's own values; the player changed shadows in the game since
        config("SET graphicsViewDistance \"1\"", "SET particleDensity \"10\"", "SET graphicsShadowQuality \"3\"", "SET farclip \"800\"",
            "SET gamma \"1.2\"", "SET worldBaseMip \"2\"")
        File(wtf, GameConfigWtf.LOW_MEMORY_8GB_SAVED).writeText("graphicsViewDistance\t\"7\"\nparticleDensity\t\"100\"\ngraphicsShadowQuality\t\"4\"\nfarclip\t-\n")
        kept.mkdirs(); SvKeep.gameWtfFile(kept).writeText(wtf.absolutePath)
        val r = run().run(ResetPlan.Button.SETTINGS, me, false, gameRunning = false)
        assertEquals(setOf("graphicsViewDistance", "particleDensity", "farclip"), r.gameKeys.toSet())
        assertEquals(listOf("SET graphicsViewDistance \"7\"", "SET particleDensity \"100\"", "SET graphicsShadowQuality \"3\"",
            "SET gamma \"1.2\"", "SET worldBaseMip \"2\""), configLines())
        assertFalse("the record goes", File(wtf, GameConfigWtf.LOW_MEMORY_8GB_SAVED).exists())
        // nothing recorded: nothing written
        val before = File(wtf, "Config.wtf").readText()
        run().run(ResetPlan.Button.SETTINGS, me, false, gameRunning = false)
        assertEquals(before, File(wtf, "Config.wtf").readText())
        // Layouts never touch the game's settings
        File(wtf, GameConfigWtf.LOW_MEMORY_8GB_SAVED).writeText("gamma\t\"9\"\n")
        assertTrue(run().run(ResetPlan.Button.LAYOUTS, me, false, gameRunning = false).gameKeys.isEmpty())
    }

    @Test fun theGameRunningTheOriginalsComeBackAtTheNextStart() {
        config("SET graphicsViewDistance \"1\"")
        File(wtf, GameConfigWtf.LOW_MEMORY_8GB_SAVED).writeText("graphicsViewDistance\t\"7\"\n")
        kept.mkdirs(); SvKeep.gameWtfFile(kept).writeText(wtf.absolutePath)
        val r = run().run(ResetPlan.Button.SETTINGS, me, false, gameRunning = true)
        assertTrue(r.gameLater && r.gameKeys.isEmpty())
        assertEquals(listOf("SET graphicsViewDistance \"1\""), configLines())
        assertTrue(File(wtf, GameConfigWtf.RESET_RESTORE).isFile)
        // the next start (low memory off): ensure gives the value back first and the marker goes
        val gameRoot = File(root, "game")
        val flavor = File(gameRoot, WowProduct.values().first().flavorDir + "/WTF")
        flavor.mkdirs(); wtf.copyRecursively(flavor, overwrite = true)
        GameConfigWtf.ensure(gameRoot, WowProduct.values().first(), null)
        assertTrue(File(flavor, "Config.wtf").readLines().contains("SET graphicsViewDistance \"7\""))
        assertFalse(File(flavor, GameConfigWtf.RESET_RESTORE).exists())
    }
}
