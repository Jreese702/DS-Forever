package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Two player relays from the user, 2026-11-13: "the pause radial is appearing on the top and bottom. They have some ui elements showing on
 * the top and I think that the problem is linked" and "they let everything install and it keeps reinstalling, and the buttons and map
 * show 'waiting for game' and are blank". The Lua half (PadMap m160: Start held over game windows) is tools/test_pause_start.py.
 */
class PauseDupTest {
    private fun st(bound: String? = "pause", why: String? = null, aside: String? = null) = PadMapModel.StartState("PADFORWARD", bound, aside, false, why)

    // ---------------------------------------------------------------- StartRoute: one press, one radial
    @Test fun theOldRuleOpenedHomesWheelOverTheGamesOwn() {
        // up to 1.0.32: want == true alone opened Home's wheel - a REPLAYED padmap said so while this run's add-ons never spoke, and the
        // game's OPENRADIAL (its Core binding set, always live) opened its own radial on the same press
        val oldRule = { want: Boolean? -> want == true }
        assertTrue(oldRule(true))
        val r = StartRoute.decide(toolbarWant = true, docsLive = false, runPadmap = false, start = null, capHolder = "", gameUpMs = 5 * 60_000L)
        assertEquals(StartRoute.Kind.NOTICE, r.kind)                      // now: never our wheel; Home says the add-ons are silent
        assertTrue(r.why, r.why.contains("silent"))
    }

    @Test fun silentEarlyIsTheGamesWithoutANotice() {
        val r = StartRoute.decide(true, docsLive = false, runPadmap = false, start = null, capHolder = "", gameUpMs = 30_000L)
        assertEquals(StartRoute.Kind.GAME, r.kind)                         // character select, the add-ons still loading
        assertEquals(StartRoute.Kind.NOTICE, StartRoute.decide(null, false, false, null, "", StartRoute.NOTICE_AFTER_MS).kind)
    }

    @Test fun theWheelOnlyWhileStartIsOurs() {
        assertEquals(StartRoute.Kind.WHEEL, StartRoute.decide(true, true, true, st("pause"), "", 600_000L).kind)
        assertEquals(StartRoute.Kind.WHEEL, StartRoute.decide(true, true, true, st("menu"), "", 600_000L).kind)
        // a guest binds Start itself (Quests q35): the app's wheel, as before
        assertEquals(StartRoute.Kind.WHEEL, StartRoute.decide(true, true, true, st(null, "guest", "room"), "pad:map", 600_000L).kind)
        assertEquals(StartRoute.Kind.WHEEL, StartRoute.decide(true, true, true, st(null, null, "room"), "pad:chat", 600_000L).kind)
        // the Game Menu or the game's radial open, a login in a fight: the press is the game's alone (its own close / toggle)
        for (why in listOf("menu", "radial", "combat", "refused", null)) {
            val r = StartRoute.decide(true, true, true, st(null, why, "GameMenuFrame"), "", 600_000L)
            assertEquals("why $why", StartRoute.Kind.GAME, r.kind)
        }
        // the toolbar off: the game's own wheel (the user's Q7 ruling), live or not
        assertEquals(StartRoute.Kind.GAME, StartRoute.decide(false, true, true, st(null, "toolbar"), "", 600_000L).kind)
        // the other add-ons speak, Spells' router never did this run (its add-on off / a Lua error): the game's (a REPLAYED padmap of
        // an older run said "toolbar on" - the old rule opened Home's wheel over the game's)
        assertEquals(StartRoute.Kind.GAME, StartRoute.decide(true, docsLive = true, runPadmap = false, start = null, capHolder = "", gameUpMs = 600_000L).kind)
        // an add-on before m37 (no `start`): the old behaviour
        assertEquals(StartRoute.Kind.WHEEL, StartRoute.decide(true, true, true, null, "", 600_000L).kind)
    }

    @Test fun thePadmapCarriesStart() {
        val m = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m160","m":{"start":{"key":"PADFORWARD","aside":"GameMenuFrame","why":"menu"}}}"""))
        assertNotNull(m); val s = m!!.start!!
        assertEquals("PADFORWARD", s.key); assertNull(s.bound); assertEquals("menu", s.why); assertEquals("GameMenuFrame", s.aside)
        assertEquals("pause", PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m160","m":{"start":{"key":"PADFORWARD","bound":"pause"}}}"""))!!.start!!.bound)
        assertNull(PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m36","m":{}}"""))!!.start)
    }

    // ---------------------------------------------------------------- the notice's cause
    @Test fun theNoticeNamesTheLikelyCause() {
        assertEquals("Add-ons not installed: needs 3 MB free (1 MB free).",
            AddonSilence.cause("Add-ons not installed: needs 3 MB free (1 MB free).", "shadow Logs: link", "shadow Interface: link", null))
        assertTrue(AddonSilence.cause(null, "shadow Logs: link", "shadow Interface: REAL dir (the game loads a private add-on copy)", null)!!.contains("private copy"))
        assertTrue(AddonSilence.cause(null, "shadow Logs: REAL dir (General.log 3 KB, written 1 s ago)", "shadow Interface: link", null)!!.contains("log"))
        assertTrue(AddonSilence.cause(null, "shadow Logs: link", "shadow Interface: link", "reader: General.log not there; nothing read yet")!!.contains("log"))
        assertNull(AddonSilence.cause(null, "shadow Logs: link", "shadow Interface: link", "reader: General.log 40 KB, written 2 s ago"))
    }

    // ---------------------------------------------------------------- issue #278: Spells DISABLED in the game's AddOns
    @Test fun issue278SpellsOffIsNamed() {
        // its login probe: "our add-ons: 9 loaded, NOT loaded: WowCompanionSpells:DISABLED"; documents flowed (the others ran), no padmap
        val r = StartRoute.decide(true, docsLive = true, runPadmap = false, start = null, capHolder = "", gameUpMs = 60_000L, spellsOff = true)
        assertEquals(StartRoute.Kind.NOTICE, r.kind)
        assertEquals("DS Forever: Spells is turned off in AddOns. Turn it on at character select.", AddonSilence.offCause(listOf("WowCompanionSpells:DISABLED")))
        assertEquals("DS Forever: Spells is turned off in AddOns. Turn it on at character select.",
            AddonSilence.cause(null, "shadow Logs: link", "shadow Interface: link", null, listOf("WowCompanionSpells:DISABLED")))
        assertTrue(AddonSilence.offCause(listOf("WowCompanionHub:INTERFACE_VERSION", "WowCompanionPvp:DISABLED"))!!.startsWith("DS Forever: Hub (+1) is out of date"))
        assertNull(AddonSilence.offCause(emptyList()))
        assertTrue(AddonSilence.offCause(listOf("WowCompanionSpells:LOADABLE"))!!.contains("isn't loaded yet"))   // on, the reload not come
        // a failed install still comes first (it explains the rest)
        assertEquals("Add-ons not installed: x.", AddonSilence.cause("Add-ons not installed: x.", null, null, null, listOf("WowCompanionSpells:DISABLED")))
    }

    // ---------------------------------------------------------------- the shadow never makes Logs / WTF / Interface private
    @Test fun theSharedFolderHasLogsBeforeTheLinks() {
        val fl = Files.createTempDirectory("flavor").toFile()
        File(fl, "WTF").mkdirs()
        assertEquals(listOf("Logs", "Interface"), ShadowRepair.ensureShared(fl))
        assertTrue(File(fl, "Logs").isDirectory && File(fl, "Interface").isDirectory)
        assertEquals(emptyList<String>(), ShadowRepair.ensureShared(fl))     // once
    }

    // ---------------------------------------------------------------- LauncherSetup: install once, verify, never loop
    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
    private val files = mapOf("WowCompanion/WowCompanion.toc" to "## Title: DS Forever\n", "WowCompanion/Core.lua" to "-- core v2\n",
                              "WowCompanionHub/WowCompanionHub.toc" to "## Title: Hub\n", "WowCompanionHub/Hub.lua" to "-- hub v2\n")
    private fun want(fs: Map<String, String> = files) = fs.map { (rel, t) -> md5(t.toByteArray()) to rel }
    private fun read(fs: Map<String, String> = files): (String) -> ByteArray = { rel -> fs.getValue(rel).toByteArray() }
    private val tmp = Files.createTempDirectory("setup").toFile()
    private val addons = File(tmp, "_forever_/Interface/AddOns")
    private val state = File(tmp, "private/${LauncherSetup.STATE}")

    @Test fun aFreshInstallIsVerifiedAndStampedOnce() {
        val o = LauncherSetup.install(addons, want(), 392, state, 1_000L, read = read())
        assertTrue(o.line, o.ok); assertEquals("install", o.action); assertNull(o.why)
        assertEquals("392", File(addons, "WowCompanion/.companion_set").readText().trim())
        for ((rel, t) in files) assertEquals(t, File(addons, rel).readText())
        assertTrue(addons.walkTopDown().none { it.name.endsWith(LauncherSetup.NEW) })
        val again = LauncherSetup.install(addons, want(), 392, state, 2_000L, read = read())
        assertTrue(again.ok); assertEquals("none", again.action)            // the next start: nothing to do
    }

    @Test fun aFailedFreshInstallNeverLeavesTheGameEmptyOrLoops() {
        // up to 1.0.32: a write failing part-way "restored" by DELETING every DS Forever folder (no backup on a first install): the game
        // had no add-ons and the next Play installed (and failed) again. Now the failure touches nothing that was there
        File(addons, "WowCompanionHub").mkdirs()
        File(addons, "WowCompanionHub/Hub.lua${LauncherSetup.NEW}").mkdirs()   // the new copy cannot be written (a folder in its way)
        File(addons, "WowCompanionHub/Mine.txt").writeText("the player's own file")
        val o = LauncherSetup.install(addons, want(), 392, state, 1_000L, read = read())
        assertFalse(o.ok); assertNotNull(o.why); assertTrue(o.why!!, o.why!!.startsWith("Add-ons not installed"))
        assertTrue(File(addons, "WowCompanionHub/Mine.txt").isFile)          // nothing deleted
        assertFalse(File(addons, "WowCompanion/Core.lua").exists())          // nothing half-swapped in
        assertFalse(File(addons, "WowCompanion/.companion_set").exists())
        assertEquals(o.why, LauncherSetup.problemOf(JSONObject(state.readText())))
        // the obstacle gone: the next start installs, verified
        File(addons, "WowCompanionHub/Hub.lua${LauncherSetup.NEW}").deleteRecursively()
        val ok = LauncherSetup.install(addons, want(), 392, state, 2_000L, read = read())
        assertTrue(ok.line, ok.ok); assertNull(LauncherSetup.problemOf(JSONObject(state.readText())))
    }

    @Test fun aFailedUpdateKeepsTheWorkingSet() {
        val v1 = files.mapValues { it.value.replace("v2", "v1") }
        assertTrue(LauncherSetup.install(addons, want(v1), 391, state, 1_000L, read = read(v1)).ok)
        File(addons, "WowCompanion/Core.lua${LauncherSetup.NEW}").mkdirs()
        // (the stray-copy sweep removes FILES only: a folder in the way stays, so the write fails)
        val o = LauncherSetup.install(addons, want(), 392, state, 2_000L, read = read())
        assertFalse(o.ok); assertEquals("update", o.action)
        for ((rel, t) in v1) assertEquals(rel, t, File(addons, rel).readText())   // set 391 as it was, every file
        assertEquals("391", File(addons, "WowCompanion/.companion_set").readText().trim())
    }

    @Test fun theAppsOwnDamagedCopyTouchesNothing() {
        val bad = files + ("WowCompanion/Core.lua" to "-- truncated")
        val o = LauncherSetup.install(addons, want(), 392, state, 1_000L, read = read(bad))
        assertFalse(o.ok); assertTrue(o.why!!.contains("damaged")); assertFalse(addons.exists() && addons.walkTopDown().any { it.isFile })
    }

    @Test fun aSetRemovedBetweenStartsIsNamedTheLoop() {
        assertTrue(LauncherSetup.install(addons, want(), 392, state, 1_000L, read = read()).ok)
        File(addons, "WowCompanionHub").deleteRecursively()                    // something else removed it
        val one = LauncherSetup.install(addons, want(), 392, state, 61_000L, read = read())
        assertTrue(one.ok); assertEquals(1, one.again); assertTrue(one.line, one.line.contains("removed by something else"))
        assertTrue(one.line, one.line.contains("missing now: WowCompanionHub"))
        assertNull(one.why)                                                    // once can be anything (a cleaner, a manual delete)
        File(addons, "WowCompanionHub").deleteRecursively()
        val two = LauncherSetup.install(addons, want(), 392, state, 121_000L, read = read())
        assertTrue(two.ok); assertEquals(2, two.again); assertEquals("Add-ons keep disappearing from the game folder.", two.why)
        assertEquals(two.why, LauncherSetup.problemOf(JSONObject(state.readText())))
        // a start that finds the set in place clears it
        val fine = LauncherSetup.install(addons, want(), 392, state, 181_000L, read = read())
        assertTrue(fine.ok); assertEquals("none", fine.action); assertNull(LauncherSetup.problemOf(JSONObject(state.readText())))
    }

    @Test fun theOldRulesStay() {
        // differing files with NO older stamp (an owner pushed ahead of the app): left alone
        assertTrue(LauncherSetup.install(addons, want(), 392, state, 1_000L, read = read()).ok)
        File(addons, "WowCompanion/Core.lua").writeText("-- pushed ahead")
        val o = LauncherSetup.install(addons, want(), 392, state, 2_000L, read = read())
        assertEquals("none", o.action); assertEquals("-- pushed ahead", File(addons, "WowCompanion/Core.lua").readText())
        // a stray new copy from a run killed part-way goes; a file the set no longer has goes at the next install
        File(addons, "WowCompanion/Old.lua").writeText("gone in 393")
        File(addons, "WowCompanion/Bags.lua${LauncherSetup.NEW}").writeText("half")
        val up = LauncherSetup.install(addons, want(), 393, state, 3_000L, read = read())
        assertTrue(up.ok); assertEquals("update", up.action)
        assertFalse(File(addons, "WowCompanion/Old.lua").exists()); assertFalse(File(addons, "WowCompanion/Bags.lua${LauncherSetup.NEW}").exists())
        assertEquals("-- core v2\n", File(addons, "WowCompanion/Core.lua").readText())
    }

    @Test fun reasonsInPlainWords() {
        assertEquals("no room left on the storage", LauncherSetup.reason(java.io.IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals("no permission to write the game folder", LauncherSetup.reason(java.io.IOException("open failed: EACCES (Permission denied)")))
    }
}
