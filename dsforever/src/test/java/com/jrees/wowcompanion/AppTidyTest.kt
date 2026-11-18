package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The app's tidy (the user's ruling 2026-11-18): newest kept, caps, the 7-day heap rule, never under 24 h, never outside our folders. Temp folders only. */
class AppTidyTest {
    private val base: File = Files.createTempDirectory("apptidy").toFile()
    @After fun clean() { base.deleteRecursively() }

    private val now = 1_800_000_000_000L
    private val hour = 3600_000L
    private val day = 24 * hour
    private val docs = File(base, "Documents")
    private val sd = File(base, "sdcard")
    private val files = File(base, "files")
    private val ext = File(base, "ext")
    private fun rules(art: Boolean = false) = AppTidy.rules(docs, sd, files, ext, art)

    private fun file(f: File, at: Long, bytes: Int = 10): File {
        f.parentFile.mkdirs(); f.writeBytes(ByteArray(bytes)); f.setLastModified(at); return f
    }
    private fun launcher(ms: Long): File {
        val d = File(sd, "WowCompanion/setup/launcher-$ms")
        file(File(d, "WowCompanion/WowCompanion.toc"), ms, 100); d.setLastModified(ms); return d
    }

    @Test fun keepsNewestFiveLauncherBackupsDeletesOlder() {
        val all = (1..12).map { launcher(now - it * day) }
        val r = AppTidy.run(rules(), now)
        assertEquals(all.take(5), all.filter { it.exists() })
        assertEquals(7, r.per["launcher backups"])
        assertEquals(700L, r.bytes)
        assertTrue(r.line, r.line.startsWith("tidy: freed") && "launcher backups 7" in r.line)
    }

    @Test fun neverUnder24hEvenPastTheCap() {
        val fresh = (1..8).map { launcher(now - it * hour) }          // all within a day
        val old = launcher(now - 3 * day)
        AppTidy.run(rules(), now)
        assertTrue(fresh.all { it.exists() })
        assertFalse(old.exists())
    }

    @Test fun anOldNamedBackupWithAFreshFileInsideStays() {
        (1..5).forEach { launcher(now - it * hour) }
        val d = launcher(now - 10 * day)
        file(File(d, "WowCompanion/new.lua"), now - hour)                  // still being written
        AppTidy.run(rules(), now)
        assertTrue(d.exists())
    }

    @Test fun heapDumpsOlderThanSevenDaysGoNewerStay() {
        val logs = File(sd, "WowCompanion/logs")
        val old = file(File(logs, "heap-1101-120000.hprof"), now - 8 * day)
        val old2 = file(File(logs, "heap-1102-120000.hprof"), now - 7 * day - hour)
        val recent = file(File(logs, "heap-1115-120000.hprof"), now - 3 * day)
        val log = file(File(logs, "mem.log"), now - 30 * day)            // not a heap dump: never touched
        val lat = file(File(logs, "lat.log.1"), now - 30 * day)
        val r = AppTidy.run(rules(), now)
        assertFalse(old.exists()); assertFalse(old2.exists())
        assertTrue(recent.exists()); assertTrue(log.exists()); assertTrue(lat.exists())
        assertEquals(2, r.per["heap dumps"])
    }

    @Test fun settingsBackupsKeepTenReportsKeepTenPartsAfterADay() {
        val bk = File(docs, "WoW-AddOns-backup")
        val sets = (1..14).map { i -> File(bk, "settings-2027-01-%02d_120000".format(i)).also { file(File(it, "WowCompanionDB.lua"), now - 30 * day) } }
        val shadow = File(bk, "shadow-2020-01-01_000000").also { file(File(it, "WTF/Config.wtf"), now - 400 * day) }
        val reports = (1..13).map { file(File(docs, "DS Forever/reports/r$it.zip"), now - it * day) }
        val part = file(File(docs, "DS Forever/reports/x.zip.part"), now - 2 * day)
        val freshPart = file(File(docs, "DS Forever/reports/y.zip.part"), now - hour)
        AppTidy.run(rules(), now)
        assertEquals(sets.takeLast(10), sets.filter { it.exists() })    // the newest by the name's date
        assertTrue("shadow backups (the player's game files) are never touched", shadow.exists())
        assertEquals(reports.take(10), reports.filter { it.exists() })
        assertFalse(part.exists()); assertTrue(freshPart.exists())
    }

    @Test fun nothingOutsideOurFolders() {
        // the game's folder, a look-alike name beside our folder, and a link out of our folder: none touched
        val game = File(base, "game/_classic_/Interface/AddOns-backup-dsforever/QuestieDB-1.0-x").also { file(File(it, "a.lua"), now - 100 * day) }
        val beside = file(File(sd, "WowCompanion/launcher-1"), now - 100 * day)
        val outside = File(base, "outside/launcher-2").also { file(File(it, "keep.txt"), now - 100 * day) }
        (1..5).forEach { launcher(now - it * day) }
        val link = File(sd, "WowCompanion/setup/launcher-3")
        val linked = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()); true }.getOrDefault(false)
        val r = AppTidy.run(rules(), now)
        assertTrue(game.exists()); assertTrue(beside.exists())
        assertTrue(File(outside, "keep.txt").exists())
        if (linked) assertTrue("a link is never removed", Files.isSymbolicLink(link.toPath()))
        assertEquals(0, r.files)
        assertEquals("tidy: nothing to free", r.line)
    }

    @Test fun staleTestArtOnlyWhenTheTestIsOff() {
        val art = File(files, "art").also { file(File(it, "stamp.json"), now - 50 * day) }
        val stale = File(files, "art-direct4").also { file(File(it, "x.webp"), now - 5 * day) }
        AppTidy.run(rules(art = true), now)
        assertTrue(stale.exists())
        AppTidy.run(rules(art = false), now)
        assertFalse(stale.exists()); assertTrue(art.exists())
    }

    @Test fun stopsWhenTheGameStarts() {
        (1..10).forEach { launcher(now - it * day) }
        var calls = 0
        val r = AppTidy.run(rules(), now) { ++calls > 2 }
        assertEquals(2, r.files)
        assertTrue(r.stopped)
        assertEquals(8, File(sd, "WowCompanion/setup").listFiles()!!.size)
    }
}
