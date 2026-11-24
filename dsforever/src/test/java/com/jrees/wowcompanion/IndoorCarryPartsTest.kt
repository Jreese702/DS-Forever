package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 1525's parts (the user's pick of 2026-11-24, "Reuse what didn't change"): the building list's memory across game updates
 * (IndoorCatalogueMemo), the carry-over step's place and stamp, the cheap checks and the row's words in es / fr. The whole flow is
 * IndoorUpdateReuseTest.
 */
class IndoorCarryPartsTest {
    private fun tmp(name: String) = Files.createTempDirectory(name).toFile()

    // ------------------------------------------------------------------ the listing's memory

    @Test fun anUnchangedFileIsAnsweredFromMemoryAndNotComputedAgain() {
        val dir = tmp("memo")
        var computed = 0
        val m1 = IndoorCatalogueMemo(File(dir, IndoorCatalogueMemo.FILE), "gen|a3|x|lib:1|a3")
        assertEquals("{\"placements\":[1]}", m1.text("places", "aa11", "") { computed++; "{\"placements\":[1]}" })
        assertArrayEquals(intArrayOf(5, 6, 7), m1.ints("inputs", "bb22", "1|2|3") { computed++; intArrayOf(5, 6, 7) })
        assertArrayEquals(IntArray(0), m1.ints("tiles", "cc33", "") { computed++; IntArray(0) })
        assertTrue(m1.save(complete = true))
        assertEquals(3, computed)
        // the next game update: the same content keys are answered from memory, a changed one is computed
        val m2 = IndoorCatalogueMemo(File(dir, IndoorCatalogueMemo.FILE), "gen|a3|x|lib:1|a3")
        assertEquals("{\"placements\":[1]}", m2.text("places", "aa11", "") { computed++; "changed?" })
        assertArrayEquals(intArrayOf(5, 6, 7), m2.ints("inputs", "bb22", "1|2|3") { computed++; intArrayOf(0) })
        assertArrayEquals(IntArray(0), m2.ints("tiles", "cc33", "") { computed++; intArrayOf(9) })
        assertEquals("nothing computed for the unchanged files", 3, computed)
        assertArrayEquals("another content key: computed", intArrayOf(8), m2.ints("inputs", "dd44", "1|2|3") { computed++; intArrayOf(8) })
        assertArrayEquals("the same file with other numbers: computed", intArrayOf(9), m2.ints("inputs", "bb22", "1|2|4") { computed++; intArrayOf(9) })
        assertEquals(5, computed)
        assertEquals(3, m2.hits); assertEquals(2, m2.misses)
    }

    @Test fun noContentKeyIsNeverKeptAndAFailureIsNotKept() {
        val dir = tmp("memo2")
        val m = IndoorCatalogueMemo(File(dir, IndoorCatalogueMemo.FILE), "b")
        var n = 0
        m.text("units", null, "x") { n++; "{}" }; m.text("units", null, "x") { n++; "{}" }
        assertEquals("no content key: computed each time", 2, n)
        assertNull(m.text("units", "ee", "x") { null })
        assertEquals("{}", m.text("units", "ee", "x") { "{}" })
        assertEquals(0, IndoorCatalogueMemo(File(dir, IndoorCatalogueMemo.FILE), "b").size)
    }

    @Test fun anotherBuilderStartsEmpty() {
        val dir = tmp("memo3")
        val f = File(dir, IndoorCatalogueMemo.FILE)
        IndoorCatalogueMemo(f, "gen|a3|x|lib:1|a3").apply { text("places", "aa", "") { "{}" }; save(true) }
        assertEquals(1, IndoorCatalogueMemo(f, "gen|a3|x|lib:1|a3").size)
        assertEquals("a new native library: nothing reused", 0, IndoorCatalogueMemo(f, "gen|a3|x|lib:2|a3").size)
        assertNull("the library's bytes unread: no memory at all", IndoorCatalogueMemo.forBuilder(dir, "gen|a3|x|lib:unread", 3))
        assertEquals("gen|a3|x|lib:1|a3", IndoorCatalogueMemo.forBuilder(dir, "gen|a3|x|lib:1", 3)!!.builder)
    }

    @Test fun aCompleteListingKeepsOnlyWhatItUsedAnInterruptedOneKeepsEverything() {
        val dir = tmp("memo4")
        val f = File(dir, IndoorCatalogueMemo.FILE)
        IndoorCatalogueMemo(f, "b").apply { text("p", "old1", "") { "1" }; text("p", "old2", "") { "2" }; save(true) }
        IndoorCatalogueMemo(f, "b").apply { text("p", "old1", "") { "x" }; text("p", "new", "") { "3" }; save(complete = false) }
        assertEquals("interrupted: the old answers stay", 3, IndoorCatalogueMemo(f, "b").size)
        IndoorCatalogueMemo(f, "b").apply { text("p", "new", "") { "x" }; save(complete = true) }
        assertEquals("complete: one build's worth", 1, IndoorCatalogueMemo(f, "b").size)
    }

    @Test fun aDamagedFileIsAnEmptyMemoryAndTheTextSurvivesEveryCharacter() {
        val dir = tmp("memo5")
        val f = File(dir, IndoorCatalogueMemo.FILE)
        val odd = "{\"a\":\"tab\\there\"}\nline\r\\end\t"
        IndoorCatalogueMemo(f, "b").apply { text("p", "k", "q|r") { odd }; save(true) }
        assertEquals(odd, IndoorCatalogueMemo(f, "b").text("p", "k", "q|r") { "computed" })
        f.writeText("{\"schema\":1,\"builder\":\"b\"}\nno tab here\n")
        assertEquals(0, IndoorCatalogueMemo(f, "b").size)
        f.writeText("not json")
        assertEquals(0, IndoorCatalogueMemo(f, "b").size)
        assertTrue("no staging left behind", dir.listFiles()!!.none { it.name.startsWith(".pending_") })
        for (s in listOf("", "a\\b", "\\n", "x\ny", "\t\r\n\\")) assertEquals(s, IndoorCatalogueMemo.unescape(IndoorCatalogueMemo.escape(s)))
    }

    @Test fun aSplicedOrCutMemoryNeverAnswers() {
        // review of 1525: an answer becomes a building's spec, sealed under its own signature; a file changed in place (two saves at
        // once, a bad flash block) or cut short must never answer - every answer or none
        val dir = tmp("memo7")
        val f = File(dir, IndoorCatalogueMemo.FILE)
        IndoorCatalogueMemo(f, "b").apply {
            text("places", "aa", "") { "{\"placements\":[1]}" }; text("places", "bb", "") { "{\"placements\":[2]}" }; save(true)
        }
        val good = f.readText()
        assertEquals(2, IndoorCatalogueMemo(f, "b").size)
        f.writeText(good.replace("[2]", "[3]"))
        var computed = 0
        assertEquals("a changed answer is never given", "{\"placements\":[2]}",
            IndoorCatalogueMemo(f, "b").text("places", "bb", "") { computed++; "{\"placements\":[2]}" })
        assertEquals(1, computed)
        f.writeText(good.lines().take(2).joinToString("\n") + "\n")
        assertEquals("cut after its first answer: nothing", 0, IndoorCatalogueMemo(f, "b").size)
        f.writeText(good + "places:cc|\t{}\n")
        assertEquals("a line after the closing one: nothing", 0, IndoorCatalogueMemo(f, "b").size)
        f.writeText(good)
        assertEquals(2, IndoorCatalogueMemo(f, "b").size)
    }

    @Test fun aMemoryThatCannotBeWrittenLeavesTheOldOne() {
        val dir = tmp("memo6")
        val f = File(dir, IndoorCatalogueMemo.FILE)
        IndoorCatalogueMemo(f, "b").apply { text("p", "k", "") { "1" }; save(true) }
        val before = f.readText()
        // its folder replaced by a file: the write fails, the old memory stays as it was
        val blocked = File(dir, "blocked").apply { writeText("x") }
        assertFalse(IndoorCatalogueMemo(File(blocked, IndoorCatalogueMemo.FILE), "b").apply { text("p", "k", "") { "2" } }.save(true))
        assertEquals(before, f.readText())
    }

    /** a generator that asks the listing's memory for each building's "file" (content key ck<n>), as the native one does */
    private class MemoGen(val keys: List<String>, val onYield: () -> Unit = {}) : IndoorGenerator {
        var computed = 0
        var gotMemo: IndoorCatalogueMemo? = null
        override val algorithm = 1
        override fun catalogue(yield: IndoorGenerator.Yield): List<IndoorBakeStore.Work> = catalogue(yield, null)
        override fun catalogue(yield: IndoorGenerator.Yield, memo: IndoorCatalogueMemo?): List<IndoorBakeStore.Work> {
            gotMemo = memo
            return keys.mapIndexed { i, k ->
                yield.point(); onYield()
                val inputs = memo?.text("places", "ck$i", "") { computed++; "%064x".format(i + 1) } ?: run { computed++; "%064x".format(i + 1) }
                IndoorBakeStore.Work(k, inputs, 1, listOf(IndoorBakeStore.Location(0, i * 50.0, i * 50.0 + 10.0, 0.0, 10.0)))
            }
        }
        override fun produce(work: IndoorBakeStore.Work, out: File, yield: IndoorGenerator.Yield) = IndoorFinishTest.building(out, work.key)
        override fun close() {}
    }

    private class Quiet : IndoorScheduler.Signals {
        var t = 0L
        override fun position(): IndoorBakeQueue.Position? = null
        override fun busy() = false
        override fun hot() = false
        override fun stop() = false
        override fun now() = t
        override fun sleep(ms: Long) { t += ms }
    }

    /** the art run's pause for the game (ArtHarvest.PausedForGame is one): an InterruptedException from the checkpoint */
    private class Paused : InterruptedException("paused for the game")

    @Test fun theArtRunsPauseStopsTheCarryOverAndTheMemoryKeepsWhatItLearned() {
        val root = tmp("carrymemo"); val dir = File(root, "interiors_sched")
        val A = "0123456789abcdef0123456789abcdef"; val B = "fedcba9876543210fedcba9876543210"
        val keys = listOf("wmo_a", "wmo_b", "wmo_c", "wmo_d")
        val app = "gen|a1|lib:aa"
        IndoorScheduler(IndoorBakeStore(root), root, A, MemoGen(keys), Quiet(), dir, app = app,
            memo = IndoorCatalogueMemo.forBuilder(dir, app, 1)).run()
        val pointer = File(root, "interiors/current.json").readText()
        // the game update; the player presses Play while the carry-over lists: the art run's checkpoint throws at the third building
        var asked = 0
        val gen = MemoGen(keys)
        try {
            IndoorScheduler(IndoorBakeStore(root), root, B, gen, Quiet(), dir, app = app, memo = IndoorCatalogueMemo.forBuilder(dir, app, 1),
                checkpoint = { if (++asked == 4) throw Paused() }).carryOver()
            org.junit.Assert.fail("the pause goes to the art run")
        } catch (e: Paused) { }
        assertNotNull("the generator was handed the memory", gen.gotMemo)
        assertEquals("all answers were known from the first listing", 0, gen.computed)
        assertEquals("the old publication untouched", pointer, File(root, "interiors/current.json").readText())
        assertFalse(File(dir, "catalogue-$B.json").exists())
        // after the game: the carry-over runs to its end from memory, nothing read again, everything kept
        val again = MemoGen(keys)
        val r = IndoorScheduler(IndoorBakeStore(root), root, B, again, Quiet(), dir, app = app, memo = IndoorCatalogueMemo.forBuilder(dir, app, 1)).carryOver()
        assertEquals(0, again.computed)
        assertEquals(4, r!!.kept); assertEquals(0, r.rebuild); assertTrue(r.published)
        assertEquals(B, JSONObject(File(root, "interiors/current.json").readText()).getString("build"))
        // a new builder: the memory is not used, every file computed again
        val lib2 = MemoGen(keys)
        IndoorScheduler(IndoorBakeStore(root), root, "e".repeat(32), lib2, Quiet(), dir, app = "gen|a1|lib:bb",
            memo = IndoorCatalogueMemo.forBuilder(dir, "gen|a1|lib:bb", 1)).carryOver()
        assertEquals(4, lib2.computed)
    }

    // ------------------------------------------------------------------ the step and the cheap checks

    @Test fun theCarryOverRunsRightAfterTheMaps() {
        val base = listOf("kit", "cast", "icons", "worldmap", "minimap", "allicons", "poiicons", "wmominimap", "interiors")
        assertEquals(listOf("kit", "cast", "icons", "worldmap", "minimap", "interiorscarry", "allicons", "poiicons", "wmominimap", "interiors"),
            ArtHarvest.carryAfterMaps(base, "interiorscarry") { it })
        assertEquals("no maps: before the icon packs", listOf("kit", "interiorscarry", "allicons"),
            ArtHarvest.carryAfterMaps(listOf("kit", "allicons"), "interiorscarry") { it })
        assertEquals("neither: last", listOf("kit", "interiorscarry"), ArtHarvest.carryAfterMaps(listOf("kit"), "interiorscarry") { it })
        val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
            .map { File(it) }.first { it.isDirectory }
        assertTrue(File(src, "ArtHarvest.kt").readText().contains("IndoorCarryStep) { it.id }"))
        assertEquals("interiorscarry", IndoorCarryStep.id)
        assertFalse("background: never holds Play", IndoorCarryStep.beforePlay)
        assertFalse("on the art run's own thread, with its open game files", IndoorCarryStep.detached)
    }

    @Test fun itsStampIsStaleForEveryNewBuildAndAfterAFailure() {
        val a = IndoorCarryStep.inputKey("0123456789abcdef0123456789abcdef")
        val b = IndoorCarryStep.inputKey("fedcba9876543210fedcba9876543210")
        assertTrue("another game build: never re-stamped by the unchanged-files shortcut", ArtHarvest.inputsStale(a, b))
        assertFalse(ArtHarvest.inputsStale(a, a))
        assertTrue("a failed one: tried again", ArtHarvest.inputsStale("$a|failed", a))
    }

    @Test fun dueOnlyWhenTheBuildingsArePublishedForAnotherBuild() {
        val root = tmp("due"); val dir = File(root, "interiors_sched").apply { mkdirs() }
        val A = "0123456789abcdef0123456789abcdef"; val B = "fedcba9876543210fedcba9876543210"
        assertFalse("nothing built yet: the bake's own first run", IndoorScheduler.carryDue(root, dir, B))
        File(root, "interiors/receipts").mkdirs(); File(root, "interiors/receipts/${"a".repeat(64)}.json").writeText("{}")
        File(root, "interiors/current.json").writeText(JSONObject().put("schema", 2).put("generation", "g" + "0".repeat(32)).put("build", A).toString())
        assertTrue(IndoorScheduler.carryDue(root, dir, B))
        assertTrue("its own build but never listed (no cached catalogue)", IndoorScheduler.carryDue(root, dir, A))
        File(dir, "catalogue-$A.json").writeText("{}")
        assertFalse("published and listed for this build", IndoorScheduler.carryDue(root, dir, A))
    }

    @Test fun theCountOnDiskComesFromTheListingAndTheReceipts() {
        val root = tmp("counts"); val dir = File(root, "interiors_sched").apply { mkdirs() }
        val works = (1..3).map { IndoorBakeStore.Work("wmo_$it", "%064x".format(it), 1, listOf(IndoorBakeStore.Location(if (it == 3) 36 else 0, 0.0, 1.0, 0.0, 1.0))) }
        IndoorScheduler.writeCatalogue(File(dir, "catalogue-${"a".repeat(32)}.json"), 1, works, setOf(36))
        File(root, "interiors/receipts").mkdirs(); File(root, "interiors/receipts/${works[0].signature}.json").writeText("{}")
        val c = IndoorScheduler.countsOnDisk(root, dir, "b".repeat(32))
        assertNotNull("another build's listing: the newest one", c)
        assertEquals("the dungeon left out of the count", 2, c!!.total); assertEquals(1, c.ready)
        assertNull(IndoorScheduler.countsOnDisk(tmp("none"), File(tmp("none2"), "x"), "b".repeat(32)))
    }

    @Test fun theRowSaysWhatWasKept() {
        val s = IndoorScheduler.Status(IndoorScheduler.Phase.PAUSED, 1600, 1853, rejected = 0, kept = 1600, keptFor = "b".repeat(32))
        assertEquals("Indoor Maps (Optional): Updated for the new game build: 1,600 kept, 253 to rebuild", IndoorSchedulerStep.readiness(s).label)
        assertEquals(s, IndoorScheduler.Status.of(JSONObject(s.toJson().toString())))
        assertEquals("nothing left", "Updated for the new game build: 1,632 kept", IndoorSchedulerStep.carried(1632, 0))
        assertEquals("building: the bake's own row", "Indoor Maps (Optional): Building 1600/1853",
            IndoorSchedulerStep.readiness(s.copy(phase = IndoorScheduler.Phase.BAKING)).label)
        assertEquals("an old status file (no carry-over fields)", -1, IndoorScheduler.Status.of(JSONObject().put("phase", "PAUSED"))!!.kept)
        for (lang in listOf("es", "fr")) {
            val f = File("../companion/app/src/main/assets/companion/i18n/$lang.json")
            val raw = f.readText()
            Tr.load(lang, raw)
            for (en in listOf(IndoorSchedulerStep.carried(1600, 253), IndoorSchedulerStep.carried(1632, 0),
                    "Indoor maps (builds while the game is closed): " + IndoorSchedulerStep.carried(1600, 253),
                    "Indoor maps (builds while the game is closed): " + IndoorSchedulerStep.carried(12, 0))) {
                val t = Tr.t(en, lang)
                assertTrue("$lang: $en -> $t", t != en && !t.contains("Updated") && t.contains(if (en.contains("1,600")) "1,600" else if (en.contains("1,632")) "1,632" else "12"))
            }
            for (note in listOf(IndoorScheduler.CARRIED_NOTE, IndoorScheduler.PUBLISH_FAILED_NOTE))
                assertNotNull("$lang: $note", JSONObject(raw).getJSONObject("plain").optString(note, null))
            assertTrue("$lang keeps CRLF", raw.contains("\r\n") && raw.replace("\r\n", "").none { it == '\n' })
        }
    }
}
