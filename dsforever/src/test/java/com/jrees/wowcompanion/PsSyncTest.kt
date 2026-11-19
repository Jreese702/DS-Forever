package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Polestar progress follows between Local and Streaming (the user's pick 2026-11-18): Polestar's own Polestar.lua files merged per
 * entry, the Thor's winning the first sync, never an empty or thinner entry over a fuller one, backups first, nothing written on the
 * Thor while its game runs, no character name crossing. Temp folders and fake names only.
 */
class PsSyncTest {
    private val root: File = Files.createTempDirectory("pssync").toFile()
    @After fun clean() { root.deleteRecursively() }

    private val wtf = File(root, "WTF")
    private val acct = File(wtf, "Account/11111111#1").also { File(it, "SavedVariables").mkdirs() }
    private val chDir = File(acct, "Fakerealm/Fakechar/SavedVariables").also { it.mkdirs() }
    private val thorChar = File(chDir, "Polestar.lua")
    private val key = PsSync.charKey("Fakerealm", "Fakechar")
    private var running = false
    private val ctx = PsSync.Ctx(wtf, File(root, "kept"), gameRunning = { running })
    private val log = ArrayList<String>()

    private fun charFile(lua: String) = "\nPolestarCharDB = $lua\n"
    private fun pcFiles(vararg f: Pair<String, String?>): JSONObject {
        val a = JSONArray()
        for ((k, text) in f) a.put(JSONObject().put("key", k).put("var", PsSync.varOf(k)).put("exists", text != null).put("hash", if (text == null) "" else "h-" + text.hashCode())
            .also { if (text != null) it.put("text", text) })
        return JSONObject().put("t", "ps_files").put("state", "ok").put("acct", "x").put("files", a)
    }
    private fun docs(out: List<JSONObject>): List<JSONObject> = out.flatMap { m -> (0 until m.getJSONArray("docs").length()).map { m.getJSONArray("docs").getJSONObject(it) } }
    private fun table(text: String, v: String = "PolestarCharDB") = SvLua.parse(text, v)

    @Test fun theFirstSyncTheThorWinsAndThePcsOwnEntriesComeIn() {
        thorChar.writeText(charFile("{[\"manual\"]={[\"g1\"]={[\"1:1\"]=true}},[\"schema\"]=1}"))
        val pc = charFile("{[\"manual\"]={[\"g1\"]={[\"1:1\"]=true,[\"2:1\"]=true},[\"g2\"]={[\"1:1\"]=true}},[\"schema\"]=1}")
        val out = PsSync.onFiles(ctx, pcFiles(PsSync.ACCT to null, key to pc), 1_000L) { log += it }
        val t = table(thorChar.readText())
        @Suppress("UNCHECKED_CAST") val manual = t["manual"] as Map<Any, Any>
        assertEquals("the Thor's g1 (a conflict, first sync)", mapOf<Any, Any>("1:1" to true), manual["g1"])
        assertEquals("the PC's own guide came in", mapOf<Any, Any>("1:1" to true), manual["g2"])
        val d = docs(out).single()
        assertEquals(key, d.getString("key")); assertEquals("PolestarCharDB", d.getString("var"))
        assertEquals("h-" + pc.hashCode(), d.getString("if_hash"))
        assertTrue(SvMerge.same(t, SvLua.parse(d.getString("lua"))))
        // backups: dated, and the Thor's file as it was before the first sync, for good
        val b = File(ctx.keptDir, "polestar/${SvBackup.DIR}")
        assertTrue(b.listFiles()!!.any { it.name.endsWith(SvBackup.PRESYNC) })
        assertTrue(b.listFiles()!!.any { it.name.endsWith(".bak") })
        // no name crosses; the log says counts
        assertFalse(out.toString().contains("Fakechar")); assertFalse(log.toString().contains("Fakechar"))
    }

    @Test fun anEmptyOrThinnerEntryNeverWinsEitherWay() {
        // the Thor's file lost its progress (never saved before Android ended the game): the PC's fuller guide stays, both sides
        thorChar.writeText(charFile("{[\"manual\"]={[\"g1\"]={[\"1:1\"]=true}}}"))
        val pc = charFile("{[\"manual\"]={[\"g1\"]={[\"1:1\"]=true,[\"2:1\"]=true,[\"3:1\"]=true,[\"4:1\"]=true,[\"5:1\"]=true}}}")
        val out = PsSync.onFiles(ctx, pcFiles(key to pc), 1_000L) { log += it }
        @Suppress("UNCHECKED_CAST") val g1 = (table(thorChar.readText())["manual"] as Map<Any, Any>)["g1"] as Map<*, *>
        assertEquals(5, g1.size)
        assertTrue("the PC already holds it: nothing sent", docs(out).isEmpty())
        // and the other way: a PC file reset to almost nothing never thins the Thor's
        thorChar.writeText(charFile("{[\"manual\"]={[\"g1\"]={[\"1:1\"]=true,[\"2:1\"]=true,[\"3:1\"]=true,[\"4:1\"]=true}}}"))
        val out2 = PsSync.onFiles(ctx, pcFiles(key to charFile("{[\"manual\"]={[\"g1\"]={}}}")), 2_000L) { log += it }
        @Suppress("UNCHECKED_CAST") val g1b = (table(thorChar.readText())["manual"] as Map<Any, Any>)["g1"] as Map<*, *>
        assertEquals(4, g1b.size)
        @Suppress("UNCHECKED_CAST") val sent = (SvLua.parse(docs(out2).single().getString("lua"))["manual"] as Map<Any, Any>)["g1"] as Map<*, *>
        assertEquals(4, sent.size)
    }

    @Test fun afterAConfirmedSyncThePcsNewProgressComesBackAndTheThorsGoesOver() {
        thorChar.writeText(charFile("{[\"manual\"]={[\"g1\"]={[\"1:1\"]=true}},[\"pins\"]={[\"g1\"]={[\"step\"]=1}}}"))
        val pc0 = charFile("{[\"manual\"]={[\"g1\"]={[\"1:1\"]=true}},[\"pins\"]={[\"g1\"]={[\"step\"]=1}}}")
        PsSync.onFiles(ctx, pcFiles(key to pc0), 1_000L) { log += it }
        assertTrue("both already the same: the base is set", PsSync.baseFile(ctx, key).isFile)
        // streaming: the PC checked off step 2 and moved the pin; Local meanwhile turned off a guide's pin of its own
        val pc1 = charFile("{[\"manual\"]={[\"g1\"]={[\"1:1\"]=true,[\"2:1\"]=true}},[\"pins\"]={[\"g1\"]={[\"step\"]=2}}}")
        thorChar.writeText(charFile("{[\"manual\"]={[\"g1\"]={[\"1:1\"]=true}},[\"pins\"]={[\"g1\"]={[\"step\"]=1},[\"g3\"]={[\"step\"]=4}}}"))
        val out = PsSync.onFiles(ctx, pcFiles(key to pc1), 2_000L) { log += it }
        val t = table(thorChar.readText())
        @Suppress("UNCHECKED_CAST") val manual = t["manual"] as Map<Any, Any>
        @Suppress("UNCHECKED_CAST") val pins = t["pins"] as Map<Any, Any>
        assertEquals("the PC's progress came to the Thor", 2, (manual["g1"] as Map<*, *>).size)
        assertEquals(2L, (pins["g1"] as Map<*, *>)["step"])
        assertTrue("the Thor's own new pin stays", pins.containsKey("g3"))
        val d = docs(out).single()
        PsSync.onWritten(ctx, JSONObject().put("results", JSONArray().put(JSONObject().put("key", key).put("state", "written")))) { log += it }
        assertTrue(SvMerge.same(t, SvLua.parse(PsSync.baseFile(ctx, key).readText())))
        assertTrue(SvMerge.same(t, SvLua.parse(d.getString("lua"))))
    }

    @Test fun nothingIsWrittenOnTheThorWhileItsGameRunsAndAFileThatDoesNotReadIsLeftAlone() {
        val before = charFile("{[\"manual\"]={[\"g1\"]={[\"1:1\"]=true}}}")
        thorChar.writeText(before)
        running = true
        val out = PsSync.onFiles(ctx, pcFiles(key to charFile("{[\"manual\"]={[\"g2\"]={[\"1:1\"]=true}}}")), 1_000L) { log += it }
        assertEquals(before, thorChar.readText())
        assertFalse("no base while the Thor doesn't hold the merge", PsSync.baseFile(ctx, key).exists())
        assertEquals(1, docs(out).size)                                    // the PC still gets the Thor's entries
        running = false
        val out2 = PsSync.onFiles(ctx, pcFiles(key to "PolestarCharDB = {[\"manual\"]="), 2_000L) { log += it }
        assertTrue(docs(out2).isEmpty())
        assertEquals(before, thorChar.readText())
        // a character the PC has never seen (no folder there): the Thor keeps its own, nothing is sent for it
        val out3 = PsSync.onFiles(ctx, pcFiles(PsSync.ACCT to null), 3_000L) { log += it }
        assertTrue(docs(out3).none { it.getString("key") == key })
        assertEquals(before, thorChar.readText())
    }

    @Test fun theAccountsTableTravelsToo() {
        File(acct, "SavedVariables/Polestar.lua").writeText("\nPolestarDB = {[\"packs\"]={[\"p1\"]={[\"off\"]=true}},[\"schema\"]=1}\n")
        File(acct, "SavedVariables/Questie.lua").writeText("not ours")
        val out = PsSync.onFiles(ctx, pcFiles(PsSync.ACCT to null), 1_000L) { log += it }
        val d = docs(out).single()
        assertEquals(PsSync.ACCT, d.getString("key")); assertEquals("PolestarDB", d.getString("var")); assertEquals("", d.getString("if_hash"))
        assertEquals("not ours", File(acct, "SavedVariables/Questie.lua").readText())
        assertEquals("ps_read", PsSync.readRequest(ctx)!!.getString("t"))
        assertEquals(SvSync.accountHash("11111111#1"), PsSync.readRequest(ctx)!!.getString("acct"))
    }

    /** fix/desk-click-switch (the user, 2026-11-19: Polestar on the PC "is still not on my step"): the newer file wins a conflict */
    @Test fun aConflictGoesToTheNewerFileTheThorsOnlyWhenDesktopSaysNoTime() {
        thorChar.writeText(charFile("{[\"tab\"]={[\"active\"]=\"old\"},[\"schema\"]=1}"))
        thorChar.setLastModified(1_000_000L)
        val pc = charFile("{[\"tab\"]={[\"active\"]=\"new\"},[\"schema\"]=1}")
        val files = pcFiles(PsSync.ACCT to null, key to pc)
        files.getJSONArray("files").getJSONObject(1).put("mtime", 2_000_000L)          // the PC's written later (a stale Thor file)
        PsSync.onFiles(ctx, files, 3_000_000L) { log += it }
        @Suppress("UNCHECKED_CAST") val tab = table(thorChar.readText())["tab"] as Map<Any, Any>
        assertEquals("the PC's newer file wins", "new", tab["active"])
        assertTrue(log.last(), log.last().contains("(1 newer on the PC)"))
        // the Thor's file the newer: the Thor's wins, as before
        val m = PsSync.merge(mapOf<Any, Any>("a" to mapOf<Any, Any>("x" to 1.0)), mapOf<Any, Any>("a" to mapOf<Any, Any>("x" to 2.0)), null, thorNewer = true)
        assertEquals(mapOf<Any, Any>("x" to 1.0), m.table["a"])
        val n = PsSync.merge(mapOf<Any, Any>("a" to mapOf<Any, Any>("x" to 1.0)), mapOf<Any, Any>("a" to mapOf<Any, Any>("x" to 2.0)), null, thorNewer = false)
        assertEquals(mapOf<Any, Any>("x" to 2.0), n.table["a"])
        // never a thinner side, even the newer one
        val thinPc = PsSync.merge(mapOf<Any, Any>("a" to mapOf<Any, Any>("x" to 1.0, "y" to 1.0, "z" to 1.0, "w" to 1.0)), mapOf<Any, Any>("a" to mapOf<Any, Any>("x" to 2.0)), null, thorNewer = false)
        @Suppress("UNCHECKED_CAST") val am = thinPc.table["a"] as Map<Any, Any>
        assertEquals(1.0, am["y"])
    }
}
