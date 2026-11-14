package com.jrees.wowcompanion

import com.jrees.wowcompanion.AddonSilence.StartMode as S
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The user's pick (2026-11-14): "When the app finds DS Forever's own add-ons switched off in the game's AddOns list: switch them back on"
 * (LauncherSetup.reenable, before Play with the game stopped), and the add-ons-silent triage: the silence clock per game run, a bye with
 * no hello as silence, the start screen's line once the game's memory says the world (AddonSilence.Watch / WorldByMemory / startMode).
 */
class AddonsReenableTest {
    private val tmp: File = Files.createTempDirectory("are").toFile()
    private val wtf = File(tmp, "WTF")
    @After fun clean() { tmp.deleteRecursively() }

    /** names the walk must never put in a line: they stand in for a real account, realm and character */
    private val acct = "SECRETACCT1"
    private val realm = "HiddenRealm"
    private val char = "Privatechar"

    private fun charFile(a: String = acct, r: String = realm, c: String = char) = File(wtf, "Account/$a/$r/$c/AddOns.txt")
    private fun write(f: File, text: String) { f.parentFile.mkdirs(); f.writeBytes(text.toByteArray(Charsets.UTF_8)) }
    private val notRunning = { false }

    // ---------------------------------------------------------------- the rewrite
    @Test fun onlyOurOwnSwitchedOffLinesGoBackOn() {
        val f = charFile()
        val before = "Questie: disabled\r\nWowCompanion: disabled\r\nWowCompanionSpells: disabled\r\nWowCompanionHub: enabled\r\n" +
            "NotWowCompanion: disabled\r\nBagnon: enabled\r\nwowcompanionlower: disabled\r\nWowCompanionQuests:disabled\r\n"
        write(f, before)
        val r = LauncherSetup.reenable(wtf, notRunning)
        assertEquals(1, r.files); assertEquals(1, r.off); assertEquals(3, r.lines); assertEquals(1, r.written); assertEquals(0, r.failed)
        assertEquals("Questie: disabled\r\nWowCompanion: enabled\r\nWowCompanionSpells: enabled\r\nWowCompanionHub: enabled\r\n" +
            "NotWowCompanion: disabled\r\nBagnon: enabled\r\nwowcompanionlower: disabled\r\nWowCompanionQuests:enabled\r\n", f.readText())
        assertFalse(File(f.path + LauncherSetup.NEW).exists())
        // the next Play: nothing to do, the file not written again
        f.setLastModified(1_000_000L)
        val again = LauncherSetup.reenable(wtf, notRunning)
        assertEquals(0, again.off); assertEquals(0, again.written); assertEquals(1_000_000L, f.lastModified())
        assertTrue(again.line(), again.line().endsWith("on in every one"))
    }

    @Test fun everyOtherByteStaysAsItWas() {
        // a BOM, LF endings, no ending on the last line, spacing, a name with accents (not ours) and a line that is not "name: state"
        val f = charFile()
        val text = "\uFEFFWowCompanion: disabled\nCaf\u00E9_Addon: disabled\n  WowCompanionMap :  disabled  \n# a note\n\nWowCompanionPvp: disabled"
        write(f, text)
        val r = LauncherSetup.reenable(wtf, notRunning)
        assertEquals(3, r.lines)
        val want = "\uFEFFWowCompanion: enabled\nCaf\u00E9_Addon: disabled\n  WowCompanionMap :  enabled  \n# a note\n\nWowCompanionPvp: enabled"
        assertArrayEquals(want.toByteArray(Charsets.UTF_8), f.readBytes())
    }

    @Test fun everyCharacterAndTheAccountWideFile() {
        write(charFile(), "WowCompanion: disabled\n")
        write(charFile(c = "Second"), "WowCompanionSpells: disabled\nWowCompanionHub: disabled\n")
        write(charFile(r = "OtherRealm", c = "Third"), "WowCompanion: enabled\n")
        write(File(wtf, "Account/$acct/AddOns.txt"), "WowCompanionInteract: disabled\nQuestie: disabled\n")
        write(File(wtf, "Account/ACCT2/RealmX/Fourth/AddOns.txt"), "WowCompanionSocial: disabled\n")
        // SavedVariables folders are never walked as realms or characters
        write(File(wtf, "Account/$acct/SavedVariables/Fake/AddOns.txt"), "WowCompanion: disabled\n")
        write(File(wtf, "Account/SavedVariables/x/y/AddOns.txt"), "WowCompanion: disabled\n")
        val r = LauncherSetup.reenable(wtf, notRunning)
        assertEquals(5, r.files); assertEquals(1, r.accountWide); assertEquals(4, r.off); assertEquals(5, r.lines); assertEquals(4, r.written)
        assertEquals("WowCompanionInteract: enabled\nQuestie: disabled\n", File(wtf, "Account/$acct/AddOns.txt").readText())
        assertEquals("WowCompanion: disabled\n", File(wtf, "Account/$acct/SavedVariables/Fake/AddOns.txt").readText())
        assertEquals("WowCompanion: disabled\n", File(wtf, "Account/SavedVariables/x/y/AddOns.txt").readText())
        assertEquals("5 checked (1 account-wide); DS Forever's add-ons were switched off in 4: 5 switched back on in 4", r.line())
    }

    @Test fun theLineNeverNamesAnAccountRealmOrCharacter() {
        write(charFile(), "WowCompanion: disabled\n")
        File(charFile().path + LauncherSetup.NEW).mkdirs()          // a folder in the way of the new copy: this file fails
        File(charFile().path + LauncherSetup.NEW, "x").writeText("x")
        write(charFile(c = "Okchar"), "WowCompanion: disabled\n")
        val r = LauncherSetup.reenable(wtf, notRunning)
        assertEquals(1, r.failed); assertEquals(1, r.written)
        for (l in listOf(r.line(), r.short(), r.failWhy ?: "")) for (name in listOf(acct, realm, char, "Okchar", tmp.name, "Account"))
            assertFalse("$l names $name", l.contains(name))
        // the failed file is whole and as it was
        assertEquals("WowCompanion: disabled\n", charFile().readText())
        assertTrue(r.line(), r.line().contains("1 NOT written"))
    }

    @Test fun missingFilesAndFolders() {
        assertEquals("none yet (no WTF/Account)", LauncherSetup.reenable(wtf, notRunning).line())
        File(wtf, "Account").mkdirs()
        val empty = LauncherSetup.reenable(wtf, notRunning)
        assertEquals(0, empty.files); assertNull(empty.skipped)
        File(wtf, "Account/$acct/$realm/$char").mkdirs()             // a character folder with no AddOns.txt yet (never made here)
        assertEquals(0, LauncherSetup.reenable(wtf, notRunning).files)
        assertFalse(charFile().exists())
        // a file where a folder is expected, an AddOns.txt that is a folder: skipped, nothing thrown
        File(wtf, "Account/$acct/notadir").writeText("x")
        File(wtf, "Account/$acct/$realm/Folderchar/AddOns.txt").mkdirs()
        val r = LauncherSetup.reenable(wtf, notRunning)
        assertEquals(0, r.files); assertEquals(0, r.failed)
    }

    @Test fun nothingWhileTheGameRuns() {
        write(charFile(), "WowCompanion: disabled\n")
        val r = LauncherSetup.reenable(wtf) { true }
        assertEquals("not checked: the game is running", r.line())
        assertEquals("WowCompanion: disabled\n", charFile().readText())
        // the game started part-way through the walk: the file found switched off is NOT written
        var calls = 0
        val r2 = LauncherSetup.reenable(wtf) { calls++ > 0 }
        assertEquals(1, r2.off); assertEquals(0, r2.written); assertEquals(1, r2.failed)
        assertEquals("WowCompanion: disabled\n", charFile().readText())
    }

    @Test fun aLeftoverCopyFromAStoppedRunGoes() {
        write(charFile(), "WowCompanion: enabled\n")
        File(charFile().path + LauncherSetup.NEW).writeText("half")
        LauncherSetup.reenable(wtf, notRunning)
        assertFalse(File(charFile().path + LauncherSetup.NEW).exists())
        assertEquals("WowCompanion: enabled\n", charFile().readText())
    }

    @Test fun switchOnIsLineExact() {
        assertEquals("" to 0, LauncherSetup.switchOn(""))
        assertEquals("\n" to 0, LauncherSetup.switchOn("\n"))
        assertEquals("WowCompanion: enabled" to 1, LauncherSetup.switchOn("WowCompanion: disabled"))
        assertEquals("WowCompanion: disabledX\n" to 0, LauncherSetup.switchOn("WowCompanion: disabledX\n"))
        assertEquals("X WowCompanion: disabled\n" to 0, LauncherSetup.switchOn("X WowCompanion: disabled\n"))
        assertEquals("WowCompanion-Fork: disabled\n" to 0, LauncherSetup.switchOn("WowCompanion-Fork: disabled\n"))
    }

    // ---------------------------------------------------------------- the silence clock, per game run
    private val host = 1_000_000L
    private val run = 1_010_000L

    @Test fun theClockStartsAtTheGamesStartAndRechecksEveryMinute() {
        val w = AddonSilence.Watch(host)
        assertEquals(AddonSilence.Watch.Act.NONE, w.tick(host + 5_000, gameUp = false, runStartMs = 0L, pastLoginAtMs = 0L))
        assertFalse("no game: never silent", w.silent)
        assertEquals(AddonSilence.Watch.Act.NONE, w.tick(run + 1_000, true, run, 0L))
        assertTrue(w.silent); assertEquals(AddonSilence.Watch.Clock.GAME_START, w.clock)
        assertEquals(AddonSilence.Watch.Act.NONE, w.tick(run + AddonSilence.AFTER_MS - 1, true, run, 0L))
        assertEquals(AddonSilence.Watch.Act.SAY, w.tick(run + AddonSilence.AFTER_MS, true, run, 0L))
        assertEquals(AddonSilence.Watch.Act.NONE, w.tick(run + AddonSilence.AFTER_MS + 59_000, true, run, 0L))
        assertEquals(AddonSilence.Watch.Act.RECHECK, w.tick(run + AddonSilence.AFTER_MS + 60_000, true, run, 0L))
        assertEquals(AddonSilence.Watch.Act.RECHECK, w.tick(run + AddonSilence.AFTER_MS + 120_000, true, run, 0L))
        // a current document: the checks stop
        w.word(run + AddonSilence.AFTER_MS + 130_000)
        assertFalse(w.silent)
        assertEquals(AddonSilence.Watch.Act.NONE, w.tick(run + AddonSilence.AFTER_MS + 300_000, true, run, 0L))
        assertEquals(0L, w.silentMs(run + 900_000))
    }

    @Test fun pastTheLoginScreenRestartsTheClock() {
        val w = AddonSilence.Watch(host)
        w.tick(run, true, run, 0L)
        val past = run + 100_000
        assertEquals(AddonSilence.Watch.Act.NONE, w.tick(past + 1_000, true, run, past))
        assertEquals(AddonSilence.Watch.Clock.PAST_LOGIN, w.clock); assertEquals(past, w.from)
        assertEquals(AddonSilence.Watch.Act.NONE, w.tick(run + AddonSilence.AFTER_MS + 5_000, true, run, past))   // the old clock's moment
        assertEquals(AddonSilence.Watch.Act.SAY, w.tick(past + AddonSilence.AFTER_MS, true, run, past))
        // the world's first document marks past-login itself (NativeLogin.worldSeen): never a new silence after a word
        val w2 = AddonSilence.Watch(host)
        w2.tick(run, true, run, 0L)
        w2.word(run + 50_000)
        w2.tick(run + 50_001, true, run, run + 50_001)
        assertFalse(w2.silent); assertEquals(AddonSilence.Watch.Clock.GAME_START, w2.clock)
    }

    @Test fun neverBeforeThisBottomScreenCouldHear() {
        // the bottom screen came up 10 min into the game: its clock starts then, not at the game's start
        val w = AddonSilence.Watch(run + 600_000)
        assertEquals(AddonSilence.Watch.Act.NONE, w.tick(run + 601_000, true, run, 0L))
        assertEquals(run + 600_000, w.from)
        assertEquals(AddonSilence.Watch.Act.SAY, w.tick(run + 600_000 + AddonSilence.AFTER_MS, true, run, 0L))
    }

    @Test fun aByeWithNoHelloIsSilence() {
        val w = AddonSilence.Watch(host)
        w.tick(run, true, run, 0L)
        w.word(run + 200_000)
        val bye = run + 300_000
        w.word(bye); w.bye(bye)
        assertEquals(AddonSilence.Watch.Act.NONE, w.tick(bye + AddonSilence.BYE_HELLO_MS - 1, true, run, 0L))
        assertFalse(w.silent)
        assertEquals(AddonSilence.Watch.Act.SAY, w.tick(bye + AddonSilence.BYE_HELLO_MS, true, run, 0L))
        assertTrue(w.silentByBye); assertEquals(AddonSilence.Watch.Clock.BYE, w.clock)
        assertEquals(AddonSilence.Watch.Act.RECHECK, w.tick(bye + AddonSilence.BYE_HELLO_MS + 60_000, true, run, 0L))
        // the hello after all (a slow reload): not silent any more
        w.word(bye + 100_000)
        assertFalse(w.silent); assertFalse(w.silentByBye)
    }

    @Test fun aByeWithAHelloIsAReload() {
        val w = AddonSilence.Watch(host)
        w.tick(run, true, run, 0L)
        val bye = run + 300_000
        w.word(bye); w.bye(bye)
        w.word(bye + 8_000)                                           // the reload's hello
        for (t in listOf(bye + 30_000, bye + 90_000, bye + 400_000)) assertEquals(AddonSilence.Watch.Act.NONE, w.tick(t, true, run, 0L))
        assertFalse(w.silent)
    }

    @Test fun theLogoutsOwnLastDocumentsAreNoHello() {
        // the Disable + ReloadUI: the bye's flush carries a few more of our documents written in the same moment; nothing after
        val w = AddonSilence.Watch(host)
        w.tick(run, true, run, 0L)
        val bye = run + 300_000
        w.word(bye); w.bye(bye)
        w.word(bye); w.word(bye + 400); w.word(bye + AddonSilence.BYE_TRAIL_MS)
        assertEquals(AddonSilence.Watch.Act.SAY, w.tick(bye + AddonSilence.BYE_HELLO_MS, true, run, 0L))
        assertTrue(w.silentByBye)
        // a reload whose hello is read in the same moment as the bye (one read of the log): the hello always counts
        val w2 = AddonSilence.Watch(host)
        w2.tick(run, true, run, 0L)
        w2.word(bye); w2.bye(bye); w2.word(bye, hello = true)
        assertEquals(AddonSilence.Watch.Act.NONE, w2.tick(bye + AddonSilence.BYE_HELLO_MS, true, run, 0L))
        assertFalse(w2.silent)
    }

    @Test fun aByeAsTheGameClosesIsNothing() {
        val w = AddonSilence.Watch(host)
        w.tick(run, true, run, 0L)
        val bye = run + 300_000
        w.word(bye); w.bye(bye)
        assertEquals(AddonSilence.Watch.Act.NONE, w.tick(bye + 40_000, gameUp = false, runStartMs = run, pastLoginAtMs = 0L))
        assertFalse(w.silent)
        // the next run: its own clock, the old bye forgotten
        val run2 = bye + 60_000
        assertEquals(AddonSilence.Watch.Act.NONE, w.tick(run2 + 1_000, true, run2, 0L))
        assertEquals(AddonSilence.Watch.Clock.GAME_START, w.clock); assertEquals(run2, w.from)
        assertEquals(AddonSilence.Watch.Act.SAY, w.tick(run2 + AddonSilence.AFTER_MS, true, run2, 0L))
    }

    @Test fun theSilenceLineSaysItsClock() {
        val l = AddonSilence.lineFor(150_000L, AddonSilence.Watch.Clock.BYE, 20 * 60_000L, "reader: x")
        assertTrue(l, l.startsWith("no current document from the game's add-ons 2 min after their logout word"))
        assertTrue(l, l.contains("(the game up 20 min)")); assertTrue(l, l.endsWith("; reader: x"))
        assertTrue(AddonSilence.lineFor(130_000L, AddonSilence.Watch.Clock.GAME_START, 0L).contains("the game up ?"))
    }

    // ---------------------------------------------------------------- the game's memory says the world
    @Test fun inTheWorldByMemory() {
        val m = AddonSilence.WorldByMemory()
        m.sample(1_800, 0L); assertFalse(m.inWorld)
        m.sample(2_600, 30_000L); assertFalse(m.inWorld)                  // one high sample is not 30 s
        m.sample(2_600, 30_000L); assertFalse(m.inWorld)                  // the same sample read again
        m.sample(4_600, 60_000L); assertTrue(m.inWorld)
        m.sample(2_400, 90_000L); assertFalse(m.inWorld)                  // a dip below: from the start again
        m.sample(3_000, 120_000L); m.sample(3_000, 140_000L); assertFalse(m.inWorld)
        m.sample(3_000, 150_000L); assertTrue(m.inWorld)
        m.reset(); assertFalse(m.inWorld)
    }

    // ---------------------------------------------------------------- the start screen's line
    @Test fun theStartScreenLine() {
        val wait = PreLoginTraffic.WAITING_LINE
        val world = AddonSilence.WORLD_LINE
        // past the sign-in: the plain waiting line becomes the existing "Waiting for the world."
        assertEquals(S.WORLD_LINE, AddonSilence.startMode(wait, rowUp = false, signedIn = true, downloading = false, silent = true, inWorld = false))
        assertEquals(S.KEEP, AddonSilence.startMode(wait, false, signedIn = false, downloading = false, silent = true, inWorld = false))
        assertEquals(S.KEEP, AddonSilence.startMode(wait, false, signedIn = true, downloading = true, silent = true, inWorld = false))
        assertEquals(S.KEEP, AddonSilence.startMode(world, false, true, false, silent = true, inWorld = false))
        // in the world by memory with no word: the card's text with How
        for (l in AddonSilence.WAIT_LINES) assertEquals(l, S.ADDONS, AddonSilence.startMode(l, false, true, false, silent = true, inWorld = true))
        assertEquals(S.ADDONS, AddonSilence.startMode(world, false, signedIn = false, downloading = true, silent = true, inWorld = true))
        // the add-ons spoke, the sign-in row is up, or another line (a reload, a restart, the character loading): never
        assertEquals(S.KEEP, AddonSilence.startMode(world, false, true, false, silent = false, inWorld = true))
        assertEquals(S.KEEP, AddonSilence.startMode(wait, rowUp = true, signedIn = true, downloading = false, silent = true, inWorld = true))
        for (l in listOf("Reloading the game's interface", "Loading your character", "Signing in…", "Restarting the game: it saves and closes first (about 20 s)..."))
            assertEquals(l, S.KEEP, AddonSilence.startMode(l, false, true, false, true, true))
    }

    @Test fun theStartScreenSaysWhatTheCardSays() {
        val c = ReportNotice.card(ReportNotice.State.AddonsOff(null))
        assertEquals(ReportNotice.ADDONS_TITLE, c.title); assertEquals(ReportNotice.ADDONS_DEFAULT, c.text)
        assertTrue(ReportNotice.HOW in c.buttons)
        val how = ReportNotice.card(ReportNotice.State.AddonsOff(null, how = true))
        assertEquals(ReportNotice.ADDONS_HOW, how.text); assertFalse(ReportNotice.HOW in how.buttons)
    }
}
