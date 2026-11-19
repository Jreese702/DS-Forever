package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * RESET TO DEFAULTS THAT THE SYNC CANNOT UNDO (the user, 2026-11-19: "A reset must NOT be undone by SvSync / Desktop sync bringing the
 * old values back"): a reset clears entries in the kept copy and leaves a mark each (SvReset); the next Local start's fold (SvKeep), the
 * stream's sync (SvSync.onFiles: the PC's file) and the PC's live documents (SvSync.fromPc) all leave the old values out; a value the live
 * game still holds after the grace is the player's own again. Each reset touches only its own entries and character. Fake names, temp
 * folders only.
 */
class SvResetTest {
    private val root: File = Files.createTempDirectory("svreset").toFile()
    private val kept = File(root, "kept")
    private val wtf = File(root, "WTF")
    private val sv = File(wtf, "Account/FAKEACCOUNT/SavedVariables")
    @After fun clean() { root.deleteRecursively(); SvSync.forget() }

    private val me = "Shammy-Fakerealm"
    private val other = "Huntress-Fakerealm"
    private val S = "WowCompanionSpellsDB"
    private val D = "WowCompanionDB"
    /** the Thor's own table: my fight spells and binds, the other character's, a learned cooldown (never reset), the toolbar off */
    private val spellsLua = "{[\"binds\"]={[\"$other\"]={[\"1:2\"]=\"JUMP\"},[\"$me\"]={[\"1:2\"]=\"JUMP\",[\"1:3\"]=\"SIT\"}}," +
        "[\"cdLearn\"]={[\"$me\"]={[\"s403\"]=1.5}},[\"dpad\"]={[\"$other\"]=false,[\"$me\"]=false}," +
        "[\"faceBy\"]={[\"$other\"]={[1]=3044,[2]=1978},[\"$me\"]={[1]=403,[2]=8042,[3]=324}}}"
    private val dbLua = "{[\"compass\"]=false,[\"hideTips\"]=true,[\"lay\"]={[\"$other\"]={[\"hud\"]={[\"s\"]=90}},[\"$me\"]={[\"hud\"]={[\"dx\"]=5,[\"s\"]=110}}}," +
        "[\"pqol\"]={[\"$me\"]=7}}"

    private fun gameFile(v: String) = File(sv, SvKeep.FILES.getValue(v))
    private fun writeGame(v: String, lua: String, at: Long) { sv.mkdirs(); gameFile(v).writeText("\n$v = $lua\n"); gameFile(v).setLastModified(at) }
    private fun keep(v: String, lua: String, at: Long) {
        kept.mkdirs()
        SvKeep.keptFile(kept, v).writeText(SvKeep.toJson(SvKeep.Doc(v, SvLua.leaves(SvLua.parse(lua)), lua, at)))
    }
    private fun keptT(v: String) = SvLua.parse(SvKeep.fromJson(SvKeep.keptFile(kept, v).readText())!!.lua)
    private fun gameT(v: String) = SvLua.parse(gameFile(v).readText(), v)
    @Suppress("UNCHECKED_CAST") private fun Map<Any, Any>.sub(vararg k: Any): Any? = k.fold(this as Any?) { m, x -> (m as? Map<Any, Any>)?.get(x) }
    private val log = ArrayList<String>()
    private fun seen() { kept.mkdirs(); SvKeep.gameWtfFile(kept).writeText(wtf.absolutePath) }

    /** a Local game that started at [t] with both tables as ours, the kept copy the same */
    private fun localGame(t: Long) {
        writeGame(S, spellsLua, t); writeGame(D, dbLua, t)
        keep(S, spellsLua, t); keep(D, dbLua, t)
        seen()
        SvKeep.beforeStart(wtf, kept, File(root, "bk"), t) { log += it }
    }

    private fun skills(who: String) = ResetPlan.svTargets(setOf(ResetPlan.Kind.SKILLS), who, false, emptyMap())
    private fun layouts(who: String) = ResetPlan.svTargets(setOf(ResetPlan.Kind.LAYOUTS), who, false, emptyMap())

    // ---------------------------------------------------------------- the marks

    @Test fun stripLeavesOutTheOldValueOrAnOlderCopy() {
        val m = SvReset.Mark(S, "faceBy", me, listOf(SvLua.ser(mapOf<Any, Any>(1L to 403L))), 5_000L)
        val t = SvLua.parse(spellsLua)
        // a copy NEWER than the reset holding another value: kept (a genuine later change)
        assertEquals(0, SvReset.strip(S, t, listOf(m), 9_000L).second)
        // a copy OLDER than the reset: its entry is stale whatever it holds
        val (old, n) = SvReset.strip(S, t, listOf(m), 1_000L)
        assertEquals(1, n)
        assertNull(old.sub("faceBy", me))
        assertNotNull("the other character untouched", old.sub("faceBy", other))
        // the exact value it took away, from a copy of unknown time: stale
        val same = SvReset.withValue(t, "faceBy", me, mapOf<Any, Any>(1L to 403L))
        assertEquals(1, SvReset.strip(S, same, listOf(m), null).second)
    }

    @Test fun skillsResetTouchesOnlyMySkillsAndBacksUpFirst() {
        localGame(1_000L)
        val d = SvReset.apply(kept, skills(me), 50_000L) { log += it }
        assertTrue(d.refused.isEmpty())
        val t = keptT(S)
        assertNull("my fight spells", t.sub("faceBy", me))
        assertNull("my binds", t.sub("binds", me))
        assertEquals(3044L, t.sub("faceBy", other, 1L))
        assertEquals("JUMP", t.sub("binds", other, "1:2"))
        assertEquals("learned, never reset", 1.5, t.sub("cdLearn", me, "s403"))
        assertEquals("layouts untouched", false, t.sub("dpad", me))
        assertEquals(110L, keptT(D).sub("lay", me, "hud", "s"))
        // the marks: one per entry, with the values taken away
        val marks = SvReset.read(kept)
        assertEquals(setOf("faceBy", "binds"), marks.map { it.top }.toSet())
        assertTrue(marks.all { it.who == me && it.at == 50_000L && it.gone.isNotEmpty() })
        // the kept file backed up first (in reset-backups)
        assertTrue(File(kept, SvReset.BACKUP_DIR).listFiles()!!.any { it.name.startsWith("$S.json.") })
    }

    @Test fun theNextLocalStartDoesNotBringTheOldValuesBack() {
        localGame(1_000L)
        SvReset.apply(kept, skills(me) + layouts(me), 50_000L) { log += it }
        // the game file still holds the old values (the reset was made with the game closed); the next start folds
        log.clear()
        SvKeep.beforeStart(wtf, kept, File(root, "bk"), 60_000L) { log += it }
        val g = gameT(S)
        assertNull("the game file is written without my old fight spells", g.sub("faceBy", me))
        assertNull(g.sub("binds", me))
        assertEquals("the toolbar back to its default ON", true, g.sub("dpad", me))
        assertEquals(3044L, g.sub("faceBy", other, 1L))
        assertNull(gameT(D).sub("lay", me))
        assertEquals(90L, gameT(D).sub("lay", other, "hud", "s"))
        assertEquals("Polestar never reset", 7L, gameT(D).sub("pqol", me))
        assertNull(keptT(S).sub("faceBy", me))
        // and again at the start after: still gone (the marks stand until the live game holds a value again)
        SvKeep.beforeStart(wtf, kept, File(root, "bk"), 70_000L) { log += it }
        assertNull(gameT(S).sub("faceBy", me))
    }

    @Test fun aStaleLiveDocumentInsideTheGraceIsNotBroughtBack() {
        localGame(1_000L)
        SvReset.apply(kept, skills(me), 50_000L) { log += it }
        // the add-on's save tick that left before the reset reached it: the old table, 5 s after the reset
        SvKeep.store(kept, SvKeep.Doc(S, SvLua.leaves(SvLua.parse(spellsLua)), spellsLua, 55_000L), armed = true)
        assertEquals("the mark stands inside the grace", 2, SvReset.read(kept).size)
        SvKeep.beforeStart(wtf, kept, File(root, "bk"), 60_000L) { log += it }
        assertNull(gameT(S).sub("faceBy", me))
    }

    @Test fun aValueTheLiveGameStillHoldsAfterTheGraceIsThePlayers() {
        localGame(1_000L)
        SvReset.apply(kept, skills(me), 50_000L) { log += it }
        // the player put the same spells back (or the reset never reached the game): the game is followed, never fought
        SvKeep.store(kept, SvKeep.Doc(S, SvLua.leaves(SvLua.parse(spellsLua)), spellsLua, 50_000L + SvReset.GRACE_MS + 1), armed = true)
        assertTrue(SvReset.read(kept).isEmpty())
        SvKeep.beforeStart(wtf, kept, File(root, "bk"), 200_000L) { log += it }
        assertEquals(403L, gameT(S).sub("faceBy", me, 1L))
    }

    // ---------------------------------------------------------------- the stream's sync

    private fun files(vararg f: Triple<String, String, Long>) = JSONObject().put("t", "sv_files").put("state", "ok").put("files", JSONArray().apply {
        for ((v, text, at) in f) put(JSONObject().put("var", v).put("exists", true).put("text", "\n$v = $text\n").put("hash", "h-$v").put("at", at))
    })
    private fun docOf(w: JSONObject, v: String) = w.getJSONArray("docs").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("var") == v } }

    @Test fun thePcsOlderFileDoesNotBringTheOldValuesBack() {
        localGame(1_000L)
        // the PC was synced before: its base is the old table (it holds the old values)
        SvSync.baseFile(kept, S).writeText(SvKeep.toJson(SvKeep.Doc(S, 0, spellsLua, 2_000L)))
        SvReset.apply(kept, skills(me), 50_000L) { log += it }
        assertTrue("the PC's copy among the values taken away", SvReset.read(kept).isNotEmpty())
        // the next stream session: the PC's file, written before the reset, holds the old values
        val w = SvSync.onFiles(kept, files(Triple(S, spellsLua, 3_000L), Triple(D, dbLua, 3_000L)), 60_000L) { log += it }!!
        val pc = SvLua.parse(docOf(w, S).getString("lua"))
        assertNull("the PC is written without my old fight spells", pc.sub("faceBy", me))
        assertNull(pc.sub("binds", me))
        assertEquals(3044L, pc.sub("faceBy", other, 1L))
        assertNull(keptT(S).sub("faceBy", me))
        // a file the PC wrote AFTER the reset with a NEW pick of mine: that one comes in (a genuine later change)
        val newer = SvReset.withValue(SvLua.parse(spellsLua), "faceBy", me, mapOf<Any, Any>(1L to 331L))
        val w2 = SvSync.onFiles(kept, files(Triple(S, SvLua.ser(newer), 90_000L)), 95_000L) { log += it }!!
        assertEquals(331L, SvLua.parse(docOf(w2, S).getString("lua")).sub("faceBy", me, 1L))
    }

    @Test fun thePcsLiveDocumentsDoNotBringTheOldValuesBack() {
        localGame(1_000L)
        SvReset.apply(kept, skills(me), 50_000L) { log += it }
        // streaming, the PC not confirmed yet: its live document with my old spells is left out
        SvSync.fromPc(kept, SvKeep.Doc(S, SvLua.leaves(SvLua.parse(spellsLua)), spellsLua, 52_000L), 52_000L) { log += it }
        assertNull(keptT(S).sub("faceBy", me))
        assertEquals(2, SvReset.read(kept).size)
        // confirmed (the session's sync wrote the PC) and past the grace: the PC holding them now is the player's own choice
        SvSync.onWritten(kept, JSONObject().put("results", JSONArray().put(JSONObject().put("var", S).put("state", "written"))),
            JSONObject().put("docs", JSONArray().put(JSONObject().put("var", S).put("lua", SvKeep.fromJson(SvKeep.keptFile(kept, S).readText())!!.lua))), 53_000L) { log += it }
        SvSync.fromPc(kept, SvKeep.Doc(S, SvLua.leaves(SvLua.parse(spellsLua)), spellsLua, 200_000L), 200_000L) { log += it }
        assertTrue(SvReset.read(kept).none { it.top == "faceBy" })
        assertEquals(403L, keptT(S).sub("faceBy", me, 1L))
    }

    // ---------------------------------------------------------------- scope and backups

    @Test fun everyCharacterOnlyWithAllAndAccountKeysForSettings() {
        localGame(1_000L)
        val tables = mapOf(S to listOf(keptT(S)), D to listOf(keptT(D)))
        val mine = ResetPlan.svTargets(setOf(ResetPlan.Kind.SKILLS), me, false, tables)
        assertTrue(mine.all { it.who == me })
        val all = ResetPlan.svTargets(setOf(ResetPlan.Kind.SKILLS), me, true, tables)
        assertEquals(setOf(me, other), all.filter { it.top == "faceBy" }.map { it.who }.toSet())
        val set = ResetPlan.svTargets(setOf(ResetPlan.Kind.SETTINGS), me, false, tables)
        assertTrue("settings: account keys only", set.all { it.who == null && it.variable == D })
        SvReset.apply(kept, set, 50_000L) { log += it }
        assertNull(keptT(D)["compass"]); assertNull(keptT(D)["hideTips"])
        assertEquals(110L, keptT(D).sub("lay", me, "hud", "s"))
        SvKeep.beforeStart(wtf, kept, File(root, "bk"), 60_000L) { log += it }
        assertNull("the game file's switch comes back to its default", gameT(D)["compass"])
    }

    @Test fun twoBackupsKeptPerFile() {
        localGame(1_000L)
        for ((i, t) in listOf(10_000L, 20_000L, 30_000L, 40_000L).withIndex()) {
            keep(S, spellsLua, t - 1)                      // the store refilled (the player set it all again)
            SvReset.apply(kept, skills(me), t + i) { log += it }
        }
        val baks = File(kept, SvReset.BACKUP_DIR).listFiles()!!.filter { it.name.startsWith("$S.json.") }
        assertEquals(SvReset.KEEP_BACKUPS, baks.size)
    }

    @Test fun nothingToResetWritesNothing() {
        localGame(1_000L)
        val before = SvKeep.keptFile(kept, S).readText()
        val d = SvReset.apply(kept, skills("Nobody-Fakerealm"), 50_000L) { log += it }
        assertEquals(0, d.reset)
        assertEquals(before, SvKeep.keptFile(kept, S).readText())
        assertFalse(File(kept, SvReset.BACKUP_DIR).exists())
    }
}
