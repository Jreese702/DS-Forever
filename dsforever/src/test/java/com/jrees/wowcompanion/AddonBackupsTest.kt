package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The user's ruling 2026-11-18: "cap the add-on backups inside the game folders at the NEWEST 2 PER ADD-ON". Temp folders only. */
class AddonBackupsTest {
    private val base: File = Files.createTempDirectory("addonbk").toFile()
    @After fun clean() { base.deleteRecursively() }

    private val hour = 3600_000L
    private val day = 24 * hour
    private val now = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).parse("20261118-120000")!!.time
    private val flavor = File(base, "_forever_")
    private val home = File(flavor, "Interface/${DependencyAddons.BACKUP}")
    private val addons = File(flavor, "Interface/AddOns").apply { mkdirs() }
    private fun stamp(t: Long) = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(t))
    private fun bk(name: String): File = File(home, name).apply { mkdirs(); File(this, "x.toc").writeText("## Version: 1\n") }

    @Test fun newestTwoPerAddOnKeptOlderDeleted() {
        val q1 = bk("QuestieDB-1.1.1-${stamp(now - 10 * day)}")
        val q2 = bk("QuestieDB-1.1.2-${stamp(now - 5 * day)}")
        val q3 = bk("QuestieDB-1.1.3-${stamp(now - 3 * day)}")
        val q3b = bk("QuestieDB-1.1.3-${stamp(now - 3 * day)}~2")
        val p1 = bk("Polestar-1.0-${stamp(now - 9 * day)}")
        val p2 = bk("Polestar-1.1-${stamp(now - 8 * day)}")
        val n1 = bk("Polestar_Nav-1.0-${stamp(now - 9 * day)}")
        val odd = bk("my own stuff")
        val file = File(home, "QuestieDB-1.0-20200101-000000").apply { writeText("a file") }
        val mine = File(addons, "QuestieDB-0.9-20200101-000000").apply { mkdirs() }          // in AddOns: never looked at
        val gone = AddonBackups.cap(flavor, now) { false }
        assertEquals(setOf(q1.name, q2.name), gone.toSet())
        assertFalse(q1.exists()); assertFalse(q2.exists())
        assertTrue(q3.exists() && q3b.exists())
        assertTrue(p1.exists() && p2.exists() && n1.exists())
        assertTrue(odd.exists() && file.isFile && mine.exists())
    }

    @Test fun nothingWhileTheGameRuns() {
        val all = (1..5).map { bk("Manners-$it-${stamp(now - (10 - it) * day)}") }
        assertTrue(AddonBackups.cap(flavor, now) { true }.isEmpty())
        assertTrue(all.all { it.exists() })
        var calls = 0
        assertTrue(AddonBackups.cap(flavor, now) { ++calls > 1 }.isEmpty())                  // the game came up mid-way
        assertTrue(all.all { it.exists() })
    }

    @Test fun neverUnderADayOldEvenPastTheCap() {
        val y = (1..4).map { bk("SpokenQuests-$it-${stamp(now - (5 - it) * hour)}") }
        val future = bk("SpokenQuests-0-${stamp(now + 3 * day)}")
        assertTrue(AddonBackups.cap(flavor, now) { false }.isEmpty())
        assertTrue(y.all { it.exists() } && future.exists())
        // a day on: the newest 2 (the future one, the newest real one) stay, the rest are old enough now
        val gone = AddonBackups.cap(flavor, now + 30 * hour) { false }
        assertEquals(setOf(y[0].name, y[1].name, y[2].name), gone.toSet())
        assertTrue(future.exists() && y[3].exists())
    }

    @Test fun aLinkInsideIsRemovedNotWalked() {
        val outside = File(base, "outside").apply { mkdirs(); File(this, "keep.txt").writeText("k") }
        val old = bk("QuestTrail-1-${stamp(now - 9 * day)}")
        bk("QuestTrail-2-${stamp(now - 8 * day)}"); bk("QuestTrail-3-${stamp(now - 7 * day)}")
        val linked = runCatching { Files.createSymbolicLink(File(old, "link").toPath(), outside.toPath()) }.isSuccess
        AddonBackups.cap(flavor, now) { false }
        assertFalse(old.exists())
        if (linked) assertTrue(File(outside, "keep.txt").isFile)
    }
}
