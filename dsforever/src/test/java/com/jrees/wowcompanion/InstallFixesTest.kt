package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The 2026-11-22 install fixes, off-device: #556 (the voice pack's unpack goes on where it stopped; its download resumes from its last
 * synced length), #579 / #576 (past the login screen the start screen says the game is downloading; a restart then asks first), #577
 * (Repair on the damaged-files pop-up; the guest program's last words when the game closes as it starts).
 */
class InstallFixesTest {
    private val tmp: File = Files.createTempDirectory("installfixes").toFile()
    @After fun down() { tmp.deleteRecursively() }

    private fun zip(vararg files: Pair<String, ByteArray>, stored: Boolean = false): File {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            for ((n, b) in files) {
                val e = ZipEntry(n)
                if (stored) { e.method = ZipEntry.STORED; e.size = b.size.toLong(); e.compressedSize = b.size.toLong()
                    e.crc = java.util.zip.CRC32().apply { update(b) }.value }
                z.putNextEntry(e); z.write(b); z.closeEntry()
            }
        }
        return File(tmp, "p.zip").apply { writeBytes(bo.toByteArray()) }
    }

    // ---------------------------------------------------------------- #556
    @Test fun anUnpackCutShortGoesOnWhereItStopped() {
        val big = ByteArray(200_000) { (it % 251).toByte() }
        val z = zip("A/A.toc" to "## Version: 1\n".toByteArray(), "A/a.ogg" to big, "A/b.ogg" to big.reversedArray(), "B/B.toc" to "x".toByteArray())
        val to = File(tmp, "staging")
        // the first run wrote A.toc and a.ogg whole, then b.ogg part-way (the app was closed); B never came
        File(to, "A").mkdirs()
        File(to, "A/A.toc").writeText("## Version: 1\n")
        File(to, "A/a.ogg").writeBytes(big)
        File(to, "A/b.ogg").writeBytes(big.reversedArray().copyOf(1000))
        val wrote = ArrayList<String>()
        val pct = ArrayList<Int>()
        val n = DependencyAddons.unpackResume(z, to, "", progress = { pct.add(it) }, wrote = { wrote.add(it) })
        assertEquals(listOf("A/b.ogg", "B/B.toc"), wrote)
        assertEquals(2, n)
        assertTrue(File(to, "A/b.ogg").readBytes().contentEquals(big.reversedArray()))
        assertEquals("each percent once, ending at 100", pct.distinct(), pct)
        assertEquals(100, pct.last())
        // a file of the right size but other bytes (zero-filled by an unclean stop) is written again: its CRC-32 tells
        File(to, "A/a.ogg").writeBytes(ByteArray(big.size))
        assertEquals(listOf("A/a.ogg"), ArrayList<String>().also { w -> DependencyAddons.unpackResume(z, to, "", wrote = { w.add(it) }) })
        assertTrue(File(to, "A/a.ogg").readBytes().contentEquals(big))
    }

    @Test fun theResumedUnpackKeepsTheOldRules() {
        val z = zip("../evil.txt" to "x".toByteArray())
        val r = runCatching { DependencyAddons.unpackResume(z, File(tmp, "s"), "") }
        assertTrue(r.exceptionOrNull()?.message.orEmpty(), r.exceptionOrNull()?.message.orEmpty().contains("bad path"))
        val many = zip(*(0 until 5).map { "f$it" to ByteArray(10) }.toTypedArray())
        assertTrue(runCatching { DependencyAddons.unpackResume(many, File(tmp, "m"), "", maxFiles = 4) }.isFailure)
        // one folder around everything is dropped, as unpack does; a stored zip unpacks too
        val wrapped = zip("Pkg/A/A.toc" to "t".toByteArray(), "Pkg/A/x" to "y".toByteArray(), stored = true)
        DependencyAddons.unpackResume(wrapped, File(tmp, "w"), "Pkg")
        assertEquals("y", File(tmp, "w/A/x").readText())
    }

    @Test fun aDownloadResumesFromItsLastSyncedLength() {
        val part = File(tmp, "x.zip.part")
        assertEquals(0L, ThirdPartyAddons.resumeFrom(part, 1000))
        part.writeBytes(ByteArray(900))
        ThirdPartyAddons.synced(part).writeText("600")
        assertEquals("the bytes after the last sync may be zeros: from there", 600L, ThirdPartyAddons.resumeFrom(part, 1000))
        ThirdPartyAddons.synced(part).writeText("950")
        assertEquals(900L, ThirdPartyAddons.resumeFrom(part, 1000))
        ThirdPartyAddons.synced(part).delete()
        assertEquals("an older app's part gives up its last stretch", 0L, ThirdPartyAddons.resumeFrom(part, 1000))
        part.writeBytes(ByteArray(1200))
        assertEquals("longer than the package: from 0", 0L, ThirdPartyAddons.resumeFrom(part, 1000))
    }

    @Test fun theStartScreenSaysTheAddOnGoesIn() {
        assertEquals("Installing add-ons: Spoken Quests 42%... Keep DS Forever open",
            ThirdPartyAddons.backgroundLine(ThirdPartyAddons.installLine("Spoken Quests", 42)))
        assertNull(ThirdPartyAddons.backgroundLine("Removing add-ons: Test..."))
        assertEquals("Installing: 42%. Keep DS Forever open", ThirdPartyAddons.installRowLine(42))
        assertEquals("Installing. Keep DS Forever open", ThirdPartyAddons.installRowLine(0))
    }

    // ---------------------------------------------------------------- #579 / #576
    @Test fun pastTheLoginScreenTheLineSaysTheGameDownloads() {
        val wait = PreLoginTraffic.WAITING_LINE
        val world = AddonSilence.WORLD_LINE
        fun mode(line: String, downloadingAfter: Boolean, signedIn: Boolean = true) = AddonSilence.startMode(line, rowUp = false,
            signedIn = signedIn, downloading = false, silent = false, inWorld = false, downloadingAfter = downloadingAfter)
        assertEquals(AddonSilence.StartMode.DOWNLOAD_LINE, mode(wait, true))
        assertEquals(AddonSilence.StartMode.DOWNLOAD_LINE, mode(world, true))
        val line = AddonSilence.downloadAfterLine(345)
        assertEquals("Game downloading (345 MB a minute): leave it running", line)
        assertEquals(AddonSilence.DOWNLOAD_AFTER_LINE, AddonSilence.downloadAfterLine(0))
        assertEquals(AddonSilence.StartMode.DOWNLOAD_LINE, mode(line, true))
        // the download ends: "Waiting for the world." again
        assertEquals(AddonSilence.StartMode.WORLD_LINE, mode(line, false))
        assertEquals(AddonSilence.StartMode.WORLD_LINE, mode(AddonSilence.DOWNLOAD_AFTER_LINE, false))
        // not signed in, or another line: never
        assertEquals(AddonSilence.StartMode.KEEP, mode(wait, true, signedIn = false))
        assertEquals(AddonSilence.StartMode.KEEP, mode("Reloading the game's interface", true))
        // the add-ons' card still wins once the game's memory says the world
        assertEquals(AddonSilence.StartMode.ADDONS, AddonSilence.startMode(line, false, true, false, silent = true, inWorld = true, downloadingAfter = true))
        assertTrue(AddonSilence.isWaitLine(line))
        assertFalse(AddonSilence.isDownloadAfterLine(PreLoginTraffic.DOWNLOADING_LINE))
        // the pre-login swap leaves it alone; a new run puts the plain waiting line back
        assertEquals(line, PreLoginTraffic.waitingLine(line, downloading = false))
        assertEquals(wait, PreLoginTraffic.lineAtNewRun(line, downloading = false))
        assertEquals(PreLoginTraffic.DOWNLOADING_LINE, PreLoginTraffic.lineAtNewRun(line, downloading = true))
    }

    @Test fun aRestartWhileDownloadingAsksFirst() {
        assertTrue(ReportNotice.restartAsks(downloading = true, gameUp = true))
        assertFalse(ReportNotice.restartAsks(downloading = false, gameUp = true))
        assertFalse(ReportNotice.restartAsks(downloading = true, gameUp = false))
        val c = ReportNotice.card(ReportNotice.State.RestartDownloading)
        assertEquals("The game is still downloading", c.title)
        assertEquals(listOf("Wait", "Restart anyway"), c.buttons)
    }

    // ---------------------------------------------------------------- #577
    @Test fun theDamagedPopUpOffersRepair() {
        val base = SetupFlow.Inputs(path = "/g", folderFound = true, loginSaved = true, art = SetupFlow.Art.FAILED,
            artWhy = CascHeal.damagedMessage(1000, CascHeal.Route.DIRECT))
        val plain = SetupFlow.screen(base).prompt!!
        assertEquals(listOf("Try again"), plain.buttons.map { it.label })
        val p = SetupFlow.screen(base.copy(repairable = true)).prompt!!
        assertEquals("art", p.id)
        assertEquals(listOf(SetupFlow.Action.REPAIR_GAME, SetupFlow.Action.RETRY_ART), p.buttons.map { it.action })
        assertFalse("never a second Repair while one runs", SetupFlow.screen(base.copy(repairable = true, busy = GameRepair.checkLine(12)))
            .prompt!!.buttons.first().enabled)
        assertEquals("Checking the game's files: 12%", SetupFlow.screen(base.copy(repairable = true, busy = GameRepair.checkLine(12))).line)
        // #577 review: Try again waits too while Repair runs (an art run then would read the files the repair writes)
        assertEquals(listOf(false, false), SetupFlow.screen(base.copy(repairable = true, busy = GameRepair.checkLine(12)))
            .prompt!!.buttons.map { it.enabled })
        assertEquals(listOf(true, true), p.buttons.map { it.enabled })
        // and an update (by itself, a region switch's) waits in the queue for the repair's end, never beside it
        assertEquals(SetupFlow.UpdateGate.WAIT_REPAIR, SetupFlow.updateGate(true, false, false, false, true, repairing = true))
        assertEquals(SetupFlow.UpdateGate.WAIT_REPAIR, SetupFlow.updateGate(true, false, true, true, true, repairing = true))
        assertEquals(SetupFlow.UpdateGate.BUSY, SetupFlow.updateGate(true, true, false, false, true, repairing = true))
        assertEquals(SetupFlow.UpdateGate.GO, SetupFlow.updateGate(true, false, false, false, true, repairing = false))
        // its result until OK, then the damaged pop-up again if anything is left
        val done = SetupFlow.screen(base.copy(repairable = true, repairNote = "Repaired 3 damaged files.")).prompt!!
        assertEquals("repair", done.id)
        assertEquals(listOf(SetupFlow.Action.REPAIR_OK), done.buttons.map { it.action })
        assertFalse(SetupFlow.canPlay(base.copy(art = SetupFlow.Art.READY, busy = GameRepair.repairLine(1, 2))))
    }

    @Test fun theGamesLastWordsWhenItClosesAsItStarts() {
        GuestOutput.started()
        for (i in 1..50) GuestOutput.line("line $i")
        GuestOutput.line("err: \u0007bell and mail me@example.com")
        GuestOutput.line("   ")
        GuestOutput.line("x".repeat(500))
        val (lines, before) = GuestOutput.tail()
        assertEquals(GuestOutput.MAX_LINES, lines.size)
        assertEquals(12, before)
        assertEquals("err: bell and mail [e-mail]", lines[lines.size - 2])
        assertEquals(GuestOutput.MAX_LEN, lines.last().length)
        assertTrue(GuestOutput.block(lines, before).startsWith("... (12 earlier lines)\nline 13"))
        assertEquals("(no output)", GuestOutput.block(emptyList(), 0))
        GuestOutput.started()
        assertTrue(GuestOutput.tail().first.isEmpty())
        // a run with an error within 2 s, not closed by us: closed as it started
        assertTrue(GameLife.quickExit(1, 79, null, started = true))
        assertFalse(GameLife.quickExit(0, 79, null, started = true))
        assertFalse(GameLife.quickExit(1, 79, "the restart from Settings", started = true))
        assertFalse(GameLife.quickExit(1, 5_000, null, started = true))
        assertFalse(GameLife.quickExit(1, 79, null, started = false))
        assertEquals("The game closed as it started (ended with error status 1). A problem report holds what it said: please send one.",
            GameLife.quickNoteText(1))
    }

    // ---------------------------------------------------------------- the words in Spanish and French (CRLF kept)
    @Test fun theNewWordsAreTranslated() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n").map { File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) {
            val b = File(dir, "$l.json").readBytes()
            assertEquals("$l.json stays CRLF", b.count { it == '\n'.code.toByte() }, Regex("\r\n").findAll(String(b, Charsets.UTF_8)).count())
            Tr.load(l, String(b, Charsets.UTF_8))
        }
        val words = listOf(
            CascHeal.damagedMessage(1000, CascHeal.Route.DIRECT), CascHeal.damagedMessage(1000, CascHeal.Route.FOLDER),
            "Repair files", "Repair game files", GameRepair.checkLine(42), GameRepair.repairLine(3, 9),
            GameRepair.summary(GameRepair.Result(9, 2, 1, listOf("x"), emptyList(), CascHeal.Route.DIRECT), 0),
            GameRepair.summary(GameRepair.Result(9, 2, 1, listOf("x"), emptyList(), CascHeal.Route.FOLDER), 0),
            GameRepair.summary(GameRepair.Result(9, 2, 2, emptyList(), emptyList(), CascHeal.Route.FOLDER), 0),
            GameRepair.summary(GameRepair.Result(9, 0, 0, emptyList(), emptyList(), CascHeal.Route.FOLDER), 0),
            GameRepair.summary(GameRepair.Result(9, 0, 0, emptyList(), emptyList(), CascHeal.Route.FOLDER), -1, "offline"),
            GameRepair.summary(GameRepair.Result(9, 0, 0, emptyList(), listOf("x"), CascHeal.Route.DIRECT), 0),
            GameRepair.summary(GameRepair.Result(9, 3000, 0, emptyList(), emptyList(), CascHeal.Route.DIRECT, tooMuch = true), 0),
            AddonSilence.downloadAfterLine(345), AddonSilence.DOWNLOAD_AFTER_LINE,
            ReportNotice.RESTART_TITLE, ReportNotice.RESTART_TEXT, ReportNotice.RESTART_ANYWAY, ReportNotice.WAIT,
            ThirdPartyAddons.backgroundLine(ThirdPartyAddons.installLine("Spoken Quests", 42))!!, ThirdPartyAddons.installRowLine(42),
            ThirdPartyAddons.installRowLine(0), GameLife.quickNoteText(1),
            GameRepair.couldNotRun(java.io.IOException("disk gone")), GameRepair.NO_MEMORY_LINE,
            SetupFlow.updateGateLine(SetupFlow.UpdateGate.WAIT_REPAIR, -1))
        for (l in listOf("es", "fr")) for (w in words) assertTrue("$l: $w", Tr.t(w, l) != w)
        assertEquals("Juego descargando (345 MB por minuto): déjalo en marcha", Tr.t(AddonSilence.downloadAfterLine(345), "es"))
        assertEquals("Réparation des fichiers du jeu : 3 sur 9", Tr.t(GameRepair.repairLine(3, 9), "fr"))
        assertEquals("La reparación no pudo ejecutarse: no hay memoria suficiente. Cierra otras apps y vuelve a reparar.",
            Tr.t(GameRepair.NO_MEMORY_LINE, "es"))
        assertEquals("La réparation n'a pas pu s'exécuter : disk gone", Tr.t("Repair could not run: disk gone", "fr"))
    }
}
