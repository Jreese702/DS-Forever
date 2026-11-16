package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ISSUE #404 (a new rogue's spells blank on the bottom screen; the user's pick, 2026-11-16: "seed every class's spellbook icons in the
 * FIRST icon pack (before Play), from the game's tables, so a new character's spells are never blank"): ClassBookIcons reads
 * SkillLine / SkillLineAbility / SpellMisc into every non-profession spell's icon; Db2 reads a table whose ids are inline while it has
 * encrypted sections (SkillLineAbility: every encrypted section writes its count and ids, 40 bytes db2.py's rule left over); the
 * recipe folds the class book in for its own build only; the icons step reads it before the pack, once per build, version 6.
 * Synthetic WDC5 tables (no game data in the repo): the same block layout the real files have.
 */
class ClassBookIconsTest {
    /** one section of a synthetic WDC5 table: [rows] = per record its 32-bit fields (inline), [parents] = per record its parent */
    private class Sec(val rows: List<IntArray>, val parents: List<Int>? = null, val tact: Long = 0L, val idList: List<Int>? = null)

    /**
     * A WDC5 file as the client's: header, section headers, field structure, storage info (every field 32 bits inline, ST_NONE),
     * then the encrypted sections' id lists ([encIds]: per encrypted section the ids written, or null = that section writes none),
     * then section 0's records, its id list, its relationship block. Encrypted sections' data sit past the file's end (as the real).
     */
    private fun wdc5(fields: Int, idIndex: Int, secs: List<Sec>, encIds: List<List<Int>?>, layout: Int = 0x1234, wrongEnd: Int = 0): ByteArray {
        val recSize = fields * 4
        val plain = secs.filter { it.tact == 0L }
        check(plain.size == 1)
        val s0 = plain[0]
        val headerEnd = 0xCC + secs.size * 40 + fields * 4 + fields * 24
        var encEnd = headerEnd
        for ((i, s) in secs.withIndex()) if (s.tact != 0L) encIds[i]?.let { encEnd += 4 + it.size * 4 }
        val idListSize = (s0.idList?.size ?: 0) * 4
        val relSize = if (s0.parents != null) 12 + s0.parents.size * 8 else 0
        val s0Size = s0.rows.size * recSize + idListSize + relSize
        val size = encEnd + s0Size
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("WDC5".toByteArray()); b.putInt(5)
        b.position(0x88)
        val total = secs.sumOf { it.rows.size }
        b.putInt(total); b.putInt(fields); b.putInt(recSize); b.putInt(0); b.putInt(0); b.putInt(layout); b.putInt(0); b.putInt(0); b.putInt(0)
        b.putShort(if (s0.idList != null) 4 else 0); b.putShort(idIndex.toShort())
        b.putInt(fields); b.putInt(0); b.putInt(0); b.putInt(fields * 24); b.putInt(0); b.putInt(0); b.putInt(secs.size)
        for (s in secs) {
            b.putLong(s.tact)
            if (s.tact == 0L) b.putInt(encEnd + wrongEnd) else b.putInt(size + 4096)
            b.putInt(s.rows.size); b.putInt(0); b.putInt(0)
            b.putInt(if (s.tact == 0L) idListSize else (s.idList?.size ?: 0) * 4)
            b.putInt(if (s.tact == 0L) relSize else 0); b.putInt(0); b.putInt(0)
        }
        repeat(fields) { b.putShort(0); b.putShort((it * 4).toShort()) }
        repeat(fields) { b.putShort((it * 32).toShort()); b.putShort(32); b.putInt(0); b.putInt(Db2.ST_NONE); b.putInt(0); b.putInt(0); b.putInt(0) }
        for ((i, s) in secs.withIndex()) if (s.tact != 0L) encIds[i]?.let { ids -> b.putInt(ids.size); ids.forEach { b.putInt(it) } }
        for (r in s0.rows) { check(r.size == fields); r.forEach { b.putInt(it) } }
        s0.idList?.forEach { b.putInt(it) }
        if (s0.parents != null) {
            b.putInt(s0.parents.size); b.putInt(0); b.putInt(0)
            s0.parents.forEachIndexed { i, p -> b.putInt(p); b.putInt(i) }
        }
        check(b.position() == size)
        return b.array()
    }

    private fun row(fields: Int, vararg set: Pair<Int, Int>) = IntArray(fields).also { r -> set.forEach { (f, v) -> r[f] = v } }

    // ---------------------------------------------------------------- Db2: the encrypted sections' id lists
    @Test fun inlineIdsWithEncryptedSectionsRead() {
        // SkillLineAbility's shape: ids inline (no id list), five encrypted sections of one row each, each writing count 1 + its id
        val rows = listOf(row(5, 2 to 69, 3 to 6, 4 to 116), row(5, 2 to 70, 3 to 8, 4 to 133))
        val enc = (1..5).map { Sec(listOf(IntArray(5)), tact = 1000L + it) }
        val d = wdc5(5, 2, listOf(Sec(rows, parents = listOf(6, 8))) + enc, listOf(null) + (1..5).map { listOf(59000 + it) })
        val t = Db2(d, "sla")
        val got = t.rows().map { Triple(it.id, it.int(4), it.parent) }
        assertEquals(listOf(Triple(69, 116, 6), Triple(70, 133, 8)), got)
    }

    @Test fun anIdListTableWithEncryptedIdListsStillReads() {
        // map.db2's shape (db2.py's rule): an id list, encrypted sections each with an id list of their own
        val rows = listOf(row(3, 1 to 11), row(3, 1 to 12))
        val enc = listOf(Sec(List(2) { IntArray(3) }, tact = 7L, idList = listOf(901, 902)), Sec(listOf(IntArray(3)), tact = 8L, idList = listOf(903)))
        val d = wdc5(3, 0, listOf(Sec(rows, idList = listOf(1, 2))) + enc, listOf(null, listOf(901, 902), listOf(903)))
        val t = Db2(d, "map")
        assertEquals(listOf(1 to 11, 2 to 12), t.rows().map { it.id to it.int(1) })
    }

    @Test fun noRuleThatClosesStillThrows() {
        val rows = listOf(row(5, 2 to 69))
        val enc = listOf(Sec(listOf(IntArray(5)), tact = 9L))
        val d = wdc5(5, 2, listOf(Sec(rows)) + enc, listOf(null, listOf(5)), wrongEnd = 12)
        try { Db2(d, "bad"); fail("a block that closes on neither rule must throw") } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("section 0 offset"))
        }
    }

    // ---------------------------------------------------------------- ClassBookIcons
    private val category = mapOf(6 to 7, 8 to 7, 39 to 7, 101 to 9, 164 to 11, 183 to 12)

    @Test fun everyNonProfessionSpellsIcon() {
        val ab = listOf(ClassBookIcons.Ability(6, 116), ClassBookIcons.Ability(8, 133), ClassBookIcons.Ability(39, 1784),
            ClassBookIcons.Ability(101, 20594), ClassBookIcons.Ability(183, 6603), ClassBookIcons.Ability(164, 2660),
            ClassBookIcons.Ability(999, 5), ClassBookIcons.Ability(6, 205))
        val icon = mapOf(116 to 135846, 133 to 135812, 1784 to 132320, 20594 to 136225, 6603 to 135641, 2660 to 136241, 5 to 135000, 205 to 135846)
        assertEquals("the class trees, racials and the general line; no profession recipe, no unknown line, one per icon",
            setOf(135846, 135812, 132320, 136225, 135641), ClassBookIcons.icons(category, ab, icon))
        assertEquals("a spell with no icon is left out", setOf(135812), ClassBookIcons.icons(category, listOf(ClassBookIcons.Ability(8, 133),
            ClassBookIcons.Ability(8, 134)), mapOf(133 to 135812, 134 to 0)))
    }

    private fun tables(lines: List<Pair<Int, Int>> = (1..12).map { it to 7 } + listOf(164 to 11),
                       abilities: List<Triple<Int, Int, Int>> = (1..12).map { Triple(it, it, 1000 + it) } + listOf(Triple(13, 164, 2660)),
                       misc: List<Pair<Int, Int>> = (1..12).map { 1000 + it to 135000 + it } + listOf(2660 to 136241),
                       parentOff: Int = 0): Triple<Db2, Db2, Db2> {
        val sl = Db2(wdc5(15, 5, listOf(Sec(lines.map { (id, cat) -> row(15, 5 to id, 6 to cat) })), listOf(null)), "skillline")
        val sla = Db2(wdc5(18, 2, listOf(Sec(abilities.map { (id, line, spell) -> row(18, 2 to id, 3 to line, 4 to spell) },
            parents = abilities.map { it.second + parentOff })), listOf(null)), "skilllineability")
        val sm = Db2(wdc5(16, 0, listOf(Sec(misc.mapIndexed { i, (_, icon) -> row(16, 0 to (26000 + i), 10 to icon) },
            parents = misc.map { it.first })), listOf(null)), "spellmisc")
        return Triple(sl, sla, sm)
    }

    @Test fun fromTheTables() {
        val (sl, sla, sm) = tables()
        assertEquals("every class line's spell icon, the profession's recipe left out", (1..12).map { 135000 + it }.toSet(),
            ClassBookIcons.fromTables(sl, sla, sm))
    }

    @Test fun aLayoutTheRowsDoNotFitIsRefused() {
        fun refused(why: String, t: Triple<Db2, Db2, Db2>) {
            try { ClassBookIcons.fromTables(t.first, t.second, t.third); fail(why) } catch (e: ClassBookIcons.Layout) { assertTrue(e.message, e.message!!.isNotEmpty()) }
        }
        refused("too few class lines (the category field elsewhere)", tables(lines = (1..12).map { it to 3 }))
        refused("abilities not on their line's parent (the line field elsewhere)", tables(parentOff = 500))
        refused("icons that are no file IDs (the icon field elsewhere)", tables(misc = (1..12).map { 1000 + it to 7 } + listOf(2660 to 9)))
        refused("most spells without an icon (the spell field elsewhere)", tables(misc = listOf(1 to 135001)))
        val (_, sla, sm) = tables()
        val narrow = Db2(wdc5(5, 0, listOf(Sec((1..12).map { row(5, 0 to it) })), listOf(null)), "skillline5")
        refused("a SkillLine with too few fields (no category field)", Triple(narrow, sla, sm))
    }

    // ---------------------------------------------------------------- the recipe and the step
    @Test fun theRecipeFoldsTheClassBookInForItsOwnBuildOnly() {
        val seed = JSONObject().put("source", "companion-requests").put("fids", JSONArray(listOf(132152, 626008)))
        val book = JSONObject().put("build", "b1").put("fids", JSONArray(listOf(135846, 135812)))
        val r = IconRecipe.resolve(seed, null, "b1", book).getJSONArray("fids")
        assertEquals(listOf(132152, 135812, 135846, 626008), (0 until r.length()).map { r.getInt(it) })
        val other = IconRecipe.resolve(seed, null, "b2", book).getJSONArray("fids")
        assertEquals("another build's class book is not this one's", listOf(132152, 626008), (0 until other.length()).map { other.getInt(it) })
        assertEquals("no class book: as before", IconRecipe.fingerprint(IconRecipe.resolve(seed, null, "b1")),
            IconRecipe.fingerprint(IconRecipe.resolve(seed, null, "b1", null)))
        assertFalse("the fingerprint changes with it", IconRecipe.fingerprint(IconRecipe.resolve(seed, null, "b1", book)) ==
            IconRecipe.fingerprint(IconRecipe.resolve(seed, null, "b1")))
    }

    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }

    @Test fun theIconsStepReadsTheClassBookFirstAndIsVersionSix() {
        assertEquals(6, KitSteps.Icons.version)
        val s = File(src, "KitSteps.kt").readText()
        val run = s.substring(s.indexOf("object Icons"), s.indexOf("internal fun classBook"))
        assertTrue("the class book before the pack", run.indexOf("classBook(job)") in 0 until run.indexOf("icons.icons(recipe"))
        assertTrue("the pack's files prefetched first", run.indexOf("job.game.prefetch(") in 0 until run.indexOf("icons.icons(recipe"))
        assertTrue("a pack built without the tables is built again later", run.contains("CLASS_BOOK_LATER + job.inputKey"))
        assertTrue("the three tables", s.contains("ClassBookIcons.DB_SKILL_LINE)") && s.contains("ClassBookIcons.DB_SKILL_LINE_ABILITY)")
            && s.contains("ClassBookIcons.DB_SPELL_MISC)"))
        assertTrue("its stamp key never matches the requirements' (a fingerprint is hex)",
            !KitSteps.Icons.CLASS_BOOK_LATER.all { it in "0123456789abcdef" })
    }

    @Test fun theDb2RuleIsWrittenDown() {
        val s = File(src, "Db2.kt").readText()
        assertTrue("both rules tried, the one that closes on section 0 kept",
            s.contains("encryptedIdsEnd(o, every = false)") && s.contains("encryptedIdsEnd(o, every = true)") && s.contains("listed == s0 || every == s0"))
    }
}
