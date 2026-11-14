package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * The review of feat/addons-reenable: Play's own path (LauncherSetup.run, from the launcher's prepareLaunch) switches DS Forever's add-ons
 * back on in the AddOns.txt files of the flavor folder's WTF - the folder the game reads (the shadow's WTF is a link to it) - says it in
 * the launcher's status line and the problem report's add-on setup line by counts only, and writes nothing while the game runs.
 * (dsforever's AddonsReenableTest drives LauncherSetup.reenable itself; this one proves Play calls it on the right folder.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddonsReenableRunTest {
    private val root: File = Files.createTempDirectory("arr").toFile()
    private val running = DependencyAddons.gameRunning
    @After fun clean() { DependencyAddons.gameRunning = running; root.deleteRecursively() }

    /** names that stand in for a real account, realm and character: never in a line */
    private val names = listOf("SECRETACCT9", "HiddenRealmQ", "PrivateCharZ")
    private val file get() = File(root, "WTF/Account/${names[0]}/${names[1]}/${names[2]}/AddOns.txt")

    @Test fun playSwitchesOursBackOnInTheFlavorFoldersWtf() {
        val ctx = RuntimeEnvironment.getApplication()
        DependencyAddons.gameRunning = { false }
        file.parentFile.mkdirs()
        file.writeBytes("Questie: disabled\r\nWowCompanionSpells: disabled\r\nWowCompanion: enabled\r\n".toByteArray())
        val line = LauncherSetup.run(ctx, root)
        assertEquals("Questie: disabled\r\nWowCompanionSpells: enabled\r\nWowCompanion: enabled\r\n", String(file.readBytes()))
        assertTrue(line, line.endsWith("; AddOns.txt: 1 of DS Forever's switched back on"))
        val report = LauncherSetup.reportLine(ctx)
        assertTrue(report, report.contains("; AddOns.txt: 1 checked; DS Forever's add-ons were switched off in 1: 1 switched back on in 1"))
        // kept for a later start's report (counts only)
        val kept = File(ctx.filesDir, LauncherSetup.REENABLE_STATE).readText()
        for (n in names) for (l in listOf(line, report, kept)) assertFalse("$l names $n", l.contains(n))
    }

    @Test fun nothingWrittenWhileTheGameRuns() {
        val ctx = RuntimeEnvironment.getApplication()
        DependencyAddons.gameRunning = { true }
        file.parentFile.mkdirs()
        file.writeBytes("WowCompanionSpells: disabled\n".toByteArray())
        val line = LauncherSetup.run(ctx, root)
        assertEquals("WowCompanionSpells: disabled\n", String(file.readBytes()))
        assertFalse(line, line.contains("AddOns.txt"))                 // the status line says nothing: nothing switched, nothing failed
        assertTrue(LauncherSetup.reportLine(ctx).contains("; AddOns.txt: not checked: the game is running"))
    }
}
