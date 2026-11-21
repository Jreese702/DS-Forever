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
 * SETTINGS FOLLOW THE PLAYER (the user's pick 2026-11-18, "Ours + WoW's server sync") under the user's hard rule "whatever you do please
 * dont delete my current hunters settings on local": the Thor's kept copy is merged with the PC's per entry, the Thor's local entries win
 * the first sync and every conflict, nothing is ever dropped, a corrupt document writes nothing, and every sync write keeps a backup.
 * Fake character names only; temp folders only.
 */
class SvSyncTest {
    private val dir: File = Files.createTempDirectory("svsync").toFile()
    @After fun clean() { dir.deleteRecursively(); SvSync.forget() }

    private val hunter = "Huntress-Fakerealm"
    private val shaman = "Shammy-Fakerealm"
    /** the Thor's hunter: rich settings (binds, a radial, fight spells) */
    private val thorLua = "{[\"bind\"]={[\"$hunter\"]={[\"A\"]=\"Arcane Shot\",[\"X\"]=75,[\"Y\"]=\"Serpent Sting\",[\"slots\"]={[1]=3044,[2]=1978}}}," +
        "[\"combat\"]={[\"$hunter\"]={[\"a\"]=2643,[\"x\"]=3044,[\"y\"]=1978}},[\"radial\"]={[\"$hunter\"]={[1]=\"Aspect of the Hawk\",[2]=\"Trap\"}},[\"toolbar\"]=true}"

    private fun keep(variable: String, lua: String, at: Long = 1_000L) {
        dir.mkdirs()
        SvKeep.keptFile(dir, variable).writeText(SvKeep.toJson(SvKeep.Doc(variable, SvLua.leaves(SvLua.parse(lua)), lua, at)))
    }
    private fun kept(variable: String) = SvKeep.fromJson(SvKeep.keptFile(dir, variable).readText())!!
    private fun keptT(variable: String) = SvLua.parse(kept(variable).lua)
    private fun files(vararg f: Pair<String, String?>) = JSONObject().put("t", "sv_files").put("state", "ok").put("files", JSONArray().apply {
        for ((v, text) in f) put(JSONObject().put("var", v).put("exists", text != null).put("text", text ?: "").put("hash", if (text == null) "" else "h-$v"))
    })
    private fun docOf(w: JSONObject, v: String): JSONObject? = w.getJSONArray("docs").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.getString("var") == v } }
    private fun gameFile(v: String, lua: String) = "\n$v = $lua\n"
    @Suppress("UNCHECKED_CAST") private fun Map<Any, Any>.sub(vararg k: Any): Any? = k.fold(this as Any?) { m, x -> (m as? Map<Any, Any>)?.get(x) }

    // ---------------------------------------------------------------- the literal reader

    @Test fun readsTheGamesOwnFileAndTheAddonsDocument() {
        val game = "\nWowCompanionSpellsDB = {\n[\"bind\"] = {\n[\"$hunter\"] = {\n[\"A\"] = \"Arcane \\\"Shot\\\"\",\n[\"X\"] = 75,\n" +
            "[\"r\"] = -1.5,\n[\"on\"] = false,\n},\n},\n[\"list\"] = {\n\"a\", -- [1]\n\"b\", -- [2]\n},\n}\n"
        val t = SvLua.parse(game, "WowCompanionSpellsDB")
        assertEquals("Arcane \"Shot\"", t.sub("bind", hunter, "A"))
        assertEquals(75L, t.sub("bind", hunter, "X"))
        assertEquals(-1.5, t.sub("bind", hunter, "r"))
        assertEquals(false, t.sub("bind", hunter, "on"))
        assertEquals("b", t.sub("list", 2L))
        // the add-on's own form, and back to it exactly
        val again = SvLua.parse(SvLua.ser(t))
        assertEquals(SvLua.ser(t), SvLua.ser(again))
        assertEquals(SvLua.ser(SvLua.parse(thorLua)), thorLua)                              // the add-on's sorted form is ours too
        assertEquals(5 + 3 + 2 + 1, SvLua.leaves(SvLua.parse(thorLua)))
        assertEquals("{[\"s\"]=\"a\\\nb\"}", SvLua.ser(mapOf<Any, Any>("s" to "a\nb")))     // %q's newline
    }

    @Test fun aBrokenDocumentDoesNotRead() {
        for (bad in listOf("{[\"a\"]=1", "{[\"a\"]=}", "WowCompanionDB = {[\"a\"]=1} trailing", "{[\"a\"]=\"open}", "", "{[\"a\"]=1}}", "x = 5"))
            assertTrue(bad, runCatching { SvLua.parse(bad, "WowCompanionDB") }.isFailure)
    }

    // ---------------------------------------------------------------- the user's three cases

    /** the Thor's hunter has rich settings, the PC has none / an older, smaller copy: after the sync both have the hunter's */
    @Test fun thorHunterRichPcEmptyOrOlderBothEndWithTheHunters() {
        keep("WowCompanionSpellsDB", thorLua)
        keep("WowCompanionDB", "{[\"lay\"]={[\"$hunter\"]={[\"hud\"]={[\"dx\"]=4}}}}")
        val pcOlder = "{[\"bind\"]={[\"$hunter\"]={[\"A\"]=\"Auto Shot\"}},[\"toolbar\"]=false}"      // the PC never set up: defaults
        val log = mutableListOf<String>()
        val w = SvSync.onFiles(dir, files("WowCompanionSpellsDB" to gameFile("WowCompanionSpellsDB", pcOlder), "WowCompanionDB" to null)) { log += it }!!
        // the FIRST sync: the Thor's local wins every conflict; the kept copy is unchanged
        assertEquals(thorLua, kept("WowCompanionSpellsDB").lua)
        assertEquals(thorLua, docOf(w, "WowCompanionSpellsDB")!!.getString("lua"))
        assertEquals("h-WowCompanionSpellsDB", docOf(w, "WowCompanionSpellsDB")!!.getString("if_hash"))
        assertEquals("", docOf(w, "WowCompanionDB")!!.getString("if_hash"))                    // a PC file not there yet: written new
        assertTrue(log.any { "first sync: the Thor's win" in it })
        // Desktop wrote it: the base is the hunter's, the PC's later documents are trusted
        SvSync.onWritten(dir, JSONObject().put("results", JSONArray().put(JSONObject().put("var", "WowCompanionSpellsDB").put("state", "written"))), w) {}
        assertTrue(SvSync.trusted("WowCompanionSpellsDB"))
        assertEquals(thorLua, SvKeep.fromJson(SvSync.baseFile(dir, "WowCompanionSpellsDB").readText())!!.lua)
    }

    /** the PC has a newer change for ONE key (after a confirmed sync): only that entry updates, the rest stays the Thor's */
    @Test fun aPcChangeToOneEntryUpdatesOnlyThatEntry() {
        keep("WowCompanionSpellsDB", thorLua)
        SvSync.baseFile(dir, "WowCompanionSpellsDB").writeText(SvKeep.toJson(SvKeep.Doc("WowCompanionSpellsDB", 0, thorLua, 1L)))
        val t = SvLua.parse(thorLua).toMutableMap()
        @Suppress("UNCHECKED_CAST") val radial = (t["radial"] as Map<Any, Any>).toMutableMap()
        radial[hunter] = mapOf<Any, Any>(1L to "Aspect of the Monkey", 2L to "Trap", 3L to "Flare")
        t["radial"] = radial
        // and something the Thor changed meanwhile (its own entry wins), and a new character on the PC (added)
        val thorNow = SvLua.parse(thorLua).toMutableMap().also { it["toolbar"] = false }
        keep("WowCompanionSpellsDB", SvLua.ser(thorNow), at = 2_000L)
        t["bind"] = (t["bind"] as Map<*, *>).toMutableMap().also { it[shaman] = mapOf("A" to "Lightning Bolt") }
        val w = SvSync.onFiles(dir, files("WowCompanionSpellsDB" to gameFile("WowCompanionSpellsDB", SvLua.ser(t)))) {}!!
        val m = keptT("WowCompanionSpellsDB")
        assertEquals("Aspect of the Monkey", m.sub("radial", hunter, 1L))                      // the PC's newer entry
        assertEquals("Flare", m.sub("radial", hunter, 3L))
        assertEquals("Arcane Shot", m.sub("bind", hunter, "A"))                                // the rest the Thor's
        assertEquals(2643L, m.sub("combat", hunter, "a"))
        assertEquals(false, m["toolbar"])                                                      // the Thor's own change kept
        assertEquals("Lightning Bolt", m.sub("bind", shaman, "A"))                             // a PC-only character added
        assertEquals(SvLua.ser(m), docOf(w, "WowCompanionSpellsDB")!!.getString("lua"))
        // a dated backup and the pre-sync copy (the hunter's as it was) kept
        assertEquals(1, File(dir, SvBackup.DIR).listFiles()!!.size)
        assertEquals(SvLua.ser(thorNow), SvKeep.fromJson(File(dir, "WowCompanionSpellsDB.json" + SvBackup.PRESYNC).readText())!!.lua)
    }

    /**
     * PadMap m180: the DS toolbar's mirror bit and the top-screen combat bar's flag store an explicit 0 for off. The three-way merge then
     * keeps the Thor's "off" against the PC's older "on" (base = on); a DELETED entry (m178 / m179's `nil`) came back from the PC
     */
    @Test fun anOffMirrorBitOrTopFlagStaysOffThroughTheMerge() {
        val who = "Thrall-Area 52"
        for (key in listOf("rbm", "ctop")) {
            val base = mapOf<Any, Any>(key to mapOf<Any, Any>(who to 1L))
            val remote = mapOf<Any, Any>(key to mapOf<Any, Any>(who to 1L))
            val off = SvMerge.merge(mapOf<Any, Any>(key to mapOf<Any, Any>(who to 0L)), remote, base).table
            assertEquals("$key: local 0, remote 1, base 1 keeps 0", 0L, (off[key] as Map<*, *>)[who])
            val gone = SvMerge.merge(mapOf<Any, Any>(key to emptyMap<Any, Any>()), remote, base).table
            assertEquals("$key: a deleted entry is added back by the merge (why off is 0, never nil)", 1L, (gone[key] as Map<*, *>)[who])
        }
    }

    /** a corrupt or partial PC document: nothing written for that table, the Thor's kept copy untouched */
    @Test fun aCorruptPcFileWritesNothing() {
        keep("WowCompanionSpellsDB", thorLua)
        val before = SvKeep.keptFile(dir, "WowCompanionSpellsDB").readText()
        val log = mutableListOf<String>()
        val w = SvSync.onFiles(dir, files("WowCompanionSpellsDB" to "\nWowCompanionSpellsDB = {\n[\"bind\"] = {\n[\"$hunter\"] = {\n")) { log += it }!!
        assertNull(docOf(w, "WowCompanionSpellsDB"))
        assertEquals(before, SvKeep.keptFile(dir, "WowCompanionSpellsDB").readText())
        assertTrue(log.any { "does not read whole" in it })
        // the same during the stream: a broken svkeep is ignored
        SvSync.fromPc(dir, SvKeep.Doc("WowCompanionSpellsDB", 3, "{[\"bind\"]={", 5_000L)) { log += it }
        assertEquals(before, SvKeep.keptFile(dir, "WowCompanionSpellsDB").readText())
        assertFalse(File(dir, SvBackup.DIR).exists())
    }

    // ---------------------------------------------------------------- the stream's own documents

    /** before (1.0.34) the PC's svkeep REPLACED the Thor's kept table: a PC without the hunter would have dropped it */
    @Test fun aPcDocumentWithoutTheHunterNeverDropsIt() {
        keep("WowCompanionSpellsDB", thorLua)
        val pc = "{[\"bind\"]={[\"$shaman\"]={[\"A\"]=\"Lightning Bolt\"}},[\"toolbar\"]=false}"
        SvSync.fromPc(dir, SvKeep.Doc("WowCompanionSpellsDB", 2, pc, 5_000L)) {}
        val m = keptT("WowCompanionSpellsDB")
        assertEquals("Arcane Shot", m.sub("bind", hunter, "A"))
        assertEquals("Aspect of the Hawk", m.sub("radial", hunter, 1L))
        assertEquals("Lightning Bolt", m.sub("bind", shaman, "A"))
        assertEquals(true, m["toolbar"])                                                       // not confirmed: the Thor's wins
        // an empty PC table changes nothing at all
        val now = SvKeep.keptFile(dir, "WowCompanionSpellsDB").readText()
        SvSync.fromPc(dir, SvKeep.Doc("WowCompanionSpellsDB", 0, "{}", 6_000L)) {}
        assertEquals(now, SvKeep.keptFile(dir, "WowCompanionSpellsDB").readText())
    }

    /** confirmed: the PC's live change comes in; a change that drops most of an entry (a reset file) is refused */
    @Test fun trustedPcChangesComeInButAResetIsRefused() {
        keep("WowCompanionSpellsDB", thorLua)
        val w = SvSync.onFiles(dir, files("WowCompanionSpellsDB" to null)) {}!!
        SvSync.onWritten(dir, JSONObject().put("results", JSONArray().put(JSONObject().put("var", "WowCompanionSpellsDB").put("state", "written"))), w) {}
        val t = SvLua.parse(thorLua).toMutableMap()
        t["toolbar"] = false
        t["bind"] = mapOf<Any, Any>(hunter to mapOf<Any, Any>("A" to "Auto Shot"))                // 5 settings down to 1: a reset
        val log = mutableListOf<String>()
        SvSync.fromPc(dir, SvKeep.Doc("WowCompanionSpellsDB", SvLua.leaves(t), SvLua.ser(t), 7_000L)) { log += it }
        val m = keptT("WowCompanionSpellsDB")
        assertEquals(false, m["toolbar"])
        assertEquals("Arcane Shot", m.sub("bind", hunter, "A"))
        assertTrue(log.any { "1 refused" in it })
        // the PC sets it back: from the new base, a change again
        val t2 = t.toMutableMap().also { it["toolbar"] = true }
        SvSync.fromPc(dir, SvKeep.Doc("WowCompanionSpellsDB", SvLua.leaves(t2), SvLua.ser(t2), 8_000L)) {}
        assertEquals(true, keptT("WowCompanionSpellsDB")["toolbar"])
    }

    @Test fun backupsKeepTheNewestFive() {
        val f = File(dir, "x.json").apply { dir.mkdirs(); writeText("1") }
        for (i in 0 until 8) assertTrue(SvBackup.dated(f, File(dir, "b"), 1_700_000_000_000L + i * 1000L))
        assertEquals(5, File(dir, "b").listFiles()!!.size)
    }

    // ---------------------------------------------------------------- the Thor's own game file before a start

    @Test fun theThorsGameFileKeepsItsOwnEntriesOnTheWrite() {
        val wtf = File(dir, "WTF")
        val sv = File(wtf, "Account/FAKEACCT/SavedVariables").apply { mkdirs() }
        val game = File(sv, "WowCompanionSpells.lua").apply {
            writeText(gameFile("WowCompanionSpellsDB", "{[\"bind\"]={[\"$shaman\"]={[\"A\"]=\"Lightning Bolt\"}},[\"keyProbe\"]=1}")); setLastModified(1_000L)
        }
        val kd = File(dir, "kept")
        kd.mkdirs()
        SvKeep.keptFile(kd, "WowCompanionSpellsDB").writeText(SvKeep.toJson(SvKeep.Doc("WowCompanionSpellsDB", 9, thorLua, 5_000L)))
        val log = mutableListOf<String>()
        SvKeep.beforeStart(wtf, kd, File(dir, "bak")) { log += it }
        val t = SvLua.parse(game.readText(), "WowCompanionSpellsDB")
        assertEquals("Arcane Shot", t.sub("bind", hunter, "A"))
        assertEquals("Lightning Bolt", t.sub("bind", shaman, "A"))                            // the game's own entry not dropped
        assertEquals(1L, t["keyProbe"])                                                        // the game's own skipped key: never dropped (11-18 fix)
        assertTrue(File(dir, "bak/WowCompanionSpells.lua").isFile)
        assertTrue(log.contains("settings backup kept before sync"))
        assertEquals(SvSync.accountHash("FAKEACCT"), SvSync.accountFile(kd).readText())       // a hash, never the name
        assertFalse(SvSync.accountFile(kd).readText().contains("FAKE"))
        val req = SvSync.readRequest(kd)
        assertEquals("sv_read", req.getString("t"))
        assertEquals(SvSync.accountHash("fakeacct"), req.getString("acct"))
        assertFalse(req.has("game_sync"))                                                      // build 1489 ruling: no WoW sync switch
    }

    @Test fun theProtocolCarriesTheSyncMessages() {
        val f = com.jrees.wowcompanion.stream.BridgeProtocol.parse("{\"t\":\"sv_files\",\"state\":\"ok\",\"files\":[]}")
        assertTrue(f is com.jrees.wowcompanion.stream.BridgeMsg.SvFiles)
        assertNotNull(com.jrees.wowcompanion.stream.BridgeProtocol.parse("{\"t\":\"sv_written\",\"results\":[]}") as? com.jrees.wowcompanion.stream.BridgeMsg.SvWritten)
        // Desktop read nothing: no sv_write is sent (none waited for)
        assertNull(SvSync.onFiles(dir, JSONObject().put("t", "sv_files").put("state", "running")) {})
    }
}
