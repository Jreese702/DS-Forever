package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * THE 11-18 HUNTER CASE (the user: "whatever you do please dont delete my current hunters settings on local"; "I opened the streaming
 * first on my hunter and my action bars didnt match what my local was"). Before build 1489 the PC's svkeep documents REPLACED the
 * Thor's kept copy while streaming, so the kept copy held the PC's thin hunter (no fight spells, fewer faces and buffs) with a NEWER
 * time than the Thor's game file, which held the real hunter (WoW's own save, with its skipped keys). The old start rule (the kept copy
 * wins every conflict) would have written the empty fight spells over the game file; the stream sent the PC the thin copy.
 * The shapes below are the real ones (the store keys and their sizes), the names and spell ids fake. Temp folders only.
 */
class SvSafetyTest {
    private val dir: File = Files.createTempDirectory("svsafety").toFile()
    @After fun clean() { dir.deleteRecursively(); SvSync.forget() }

    private val hunter = "Huntress-Fakerealm"
    private val shaman = "Shammy-Fakerealm"
    private val V = "WowCompanionSpellsDB"
    private val at0851 = 1_795_013_460_000L          // the game file's own save (08:51)
    private val at1230 = 1_795_030_236_936L          // the kept copy's time after the 12:29 stream

    private fun t(vararg p: Pair<Any, Any>): Map<Any, Any> = linkedMapOf(*p)
    private fun list(vararg v: Any): Map<Any, Any> = linkedMapOf<Any, Any>().apply { v.forEachIndexed { i, x -> put((i + 1).toLong(), x) } }

    /** the hunter as Local really has it (the Thor's game file): fight spells, faces, a cast bar, the macro room, learned cooldowns ... */
    private val hunterGame = mapOf(
        "fightBy" to t("A" to list(t("id" to 3044L, "n" to "Arcane Shot"), t("id" to 1978L, "n" to "Serpent Sting")),
            "X" to list(t("id" to 75L, "n" to "Auto Shot")), "Y" to list(t("id" to 2643L, "n" to "Multi-Shot"), t("id" to 19434L, "n" to "Aimed Shot"))),
        "faceBy" to t("5" to t("id" to 3044L, "i" to 1L), "6" to t("id" to 1978L, "i" to 2L), "7" to t("id" to 13165L, "i" to 3L)),
        "castBar" to list(3044L, 1978L, 2643L, 19434L, 13165L, 5384L),
        "macroRoom" to t("tab" to "char", "sel" to 2L),
        "cdLearn" to t("3044" to 6L, "2643" to 10L, "19434" to 10L, "5384" to 30L),
        "smartBuffs" to t("13165" to true, "19506" to true, "1462" to false, "883" to true),
        "smartModel" to t("melee" to 0.25, "range" to 0.75),
        "srGone" to t("aspect" to true),
        "petSpellBy" to t("A" to 16827L),
        "sradials" to list("Aspects"),
        "partyBy" to t("on" to true, "dip" to 2L),
        "attackBy" to t("adopted" to true, "on" to true, "slots" to t()),
    )
    /** the PC's hunter, which the kept copy held since the morning stream: no fight spells, fewer faces and buffs, no cast bar */
    private val hunterThin = mapOf(
        "fightBy" to t(),
        "faceBy" to t("5" to t("id" to 3044L, "i" to 1L), "6" to t("id" to 1978L, "i" to 2L)),
        "cdLearn" to t("3044" to 6L),
        "smartBuffs" to t("13165" to true, "883" to true),
        "smartModel" to t("melee" to 0.3, "range" to 0.7),
        "srGone" to t("aspect" to false),
        "petSpellBy" to t("A" to 16827L),
        "sradials" to list("Aspects"),
        "partyBy" to t("on" to true, "dip" to 2L),
        "attackBy" to t("adopted" to true, "on" to true, "slots" to t()),
        "dpadZL" to t("on" to true),
    )

    private fun table(hunterStores: Map<String, Map<Any, Any>>, extra: Map<Any, Any> = emptyMap()): Map<Any, Any> {
        val out = LinkedHashMap<Any, Any>()
        for ((store, v) in hunterStores) out[store] = t(hunter to v)
        out["sitMode"] = "stand"; out["aclReset1"] = true
        out.putAll(extra)
        return out
    }
    private fun gameTable() = table(hunterGame, mapOf("keyProbe" to t("k" to 1L), "faceCd" to t(hunter to t("3044" to 6L)), "faceSwap" to t()))
    private fun thinTable() = table(hunterThin, mapOf("stickBy" to t(shaman to t())))

    /** WoW's own SavedVariables form (many lines, "-- [n]" list comments): never mistaken for OUR one-line write */
    private fun wowText(variable: String, v: Map<Any, Any>): String {
        val sb = StringBuilder("\n$variable = ")
        fun w(x: Any, ind: String) {
            when (x) {
                is Map<*, *> -> {
                    sb.append("{\n")
                    val isList = SvMerge.isList(x)
                    for ((k, y) in x) {
                        sb.append("$ind\t")
                        if (!isList) sb.append(if (k is String) "[\"$k\"] = " else "[$k] = ")
                        w(y!!, "$ind\t")
                        sb.append(if (isList) ", -- [$k]\n" else ",\n")
                    }
                    sb.append("$ind}")
                }
                is String -> sb.append("\"").append(x).append("\"")
                else -> sb.append(x.toString())
            }
        }
        w(v, "")
        return sb.append("\n").toString()
    }

    private val wtf = File(dir, "WTF")
    private val kd = File(dir, "kept")
    private val sv = File(wtf, "Account/FAKEACCT/SavedVariables")
    private val gameF = File(sv, "WowCompanionSpells.lua")

    private fun setUpTheThor() {
        sv.mkdirs(); kd.mkdirs()
        gameF.writeText(wowText(V, gameTable())); gameF.setLastModified(at0851)
        val thin = thinTable()
        SvKeep.keptFile(kd, V).writeText(SvKeep.toJson(SvKeep.Doc(V, SvLua.leaves(thin), SvLua.ser(thin), at1230)))
    }
    private fun gameNow() = SvLua.parse(gameF.readText(), V)
    private fun keptNow() = SvLua.parse(SvKeep.fromJson(SvKeep.keptFile(kd, V).readText())!!.lua)
    @Suppress("UNCHECKED_CAST") private fun Map<Any, Any>.sub(vararg k: Any): Any? = k.fold(this as Any?) { m, x -> (m as? Map<Any, Any>)?.get(x) }
    private fun ser(x: Any?) = SvLua.ser(x!!)
    private fun files(text: String?) = JSONObject().put("t", "sv_files").put("state", "ok").put("files", JSONArray()
        .put(JSONObject().put("var", V).put("exists", text != null).put("text", text ?: "").put("hash", if (text == null) "" else "h-pc")))
    private fun docOf(w: JSONObject) = w.getJSONArray("docs").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("var") == V } }

    /** every one of the hunter's game-file stores is still exactly the game's */
    private fun assertHunterWhole(m: Map<Any, Any>, what: String) {
        for ((store, v) in hunterGame) assertEquals("$what: $store", ser(v), ser(m.sub(store, hunter)))
    }

    // ---------------------------------------------------------------- Local start

    @Test fun localStartKeepsTheHuntersFullSettingsOverAThinNewerKeptCopy() {
        setUpTheThor()
        val before = gameF.readText()
        val log = mutableListOf<String>()
        SvKeep.beforeStart(wtf, kd, File(dir, "bak"), now = at1230 + 60_000L) { log += it }
        val g = gameNow()
        assertHunterWhole(g, "the game file")
        assertEquals(ser(t("k" to 1L)), ser(g["keyProbe"]))                                        // skipped keys never dropped
        assertEquals(ser(t(hunter to t("3044" to 6L))), ser(g["faceCd"]))
        // what only the kept copy had comes in (never a conflict): the hunter's dpadZL, the shaman's stickBy
        assertEquals(ser(t("on" to true)), ser(g.sub("dpadZL", hunter)))
        assertNotNull(g.sub("stickBy", shaman))
        // the game file before this write: in the start's backup AND kept for good
        assertEquals(before, File(dir, "bak/WowCompanionSpells.lua").readText())
        assertEquals(before, File(kd, "${SvBackup.DIR}/WowCompanionSpells.lua.before-fold").readText())
        assertTrue(log.toString(), log.any { "no base: the game's own win" in it })
        // the kept copy is the FULL picture now (the stream's sync sends this), without the skipped keys
        val k = keptNow()
        assertHunterWhole(k, "the kept copy")
        assertFalse(k.containsKey("keyProbe"))
        // and the base is what the game starts with
        assertEquals(ser(g), SvKeep.fromJson(SvKeep.gameBaseFile(kd, V).readText())!!.lua)
        // the next start with nothing new: nothing written
        val again = mutableListOf<String>()
        SvKeep.beforeStart(wtf, kd, File(dir, "bak2"), now = at1230 + 120_000L) { again += it }
        assertFalse(File(dir, "bak2/WowCompanionSpells.lua").exists())
        assertHunterWhole(gameNow(), "the second start")
    }

    /** the fold's own rules, entry by entry */
    @Test fun anEmptyOrThinKeptValueNeverWinsAndOnlyAGenuineChangeDoes() {
        val game = t("fightBy" to t(hunter to t("a" to 1L, "b" to 2L, "c" to 3L, "d" to 4L)), "toolbar" to true, "keyProbe" to 9L,
            "radial" to t(hunter to list("Hawk", "Trap")))
        val skip = SvLua.SKIP.getValue(V)
        // no base: the game's every conflict
        val thin = t("fightBy" to t(hunter to t("a" to 9L, "b" to 9L, "c" to 9L, "d" to 9L, "e" to 9L)), "toolbar" to false)
        var f = SvKeep.fold(game, 1L, thin, 99L, null, skip)
        assertEquals(ser(game), ser(f.table))
        // a base the game still matches: the kept copy's genuine changes win ...
        val changed = t("fightBy" to t(hunter to t("a" to 1L, "b" to 2L, "c" to 3L, "d" to 5L)), "toolbar" to false,
            "radial" to t(hunter to list("Monkey", "Trap", "Flare")))
        f = SvKeep.fold(game, 1L, changed, 2L, game, skip)
        assertEquals(5L, f.table.sub("fightBy", hunter, "d")); assertEquals(false, f.table["toolbar"]); assertEquals("Flare", f.table.sub("radial", hunter, 3L))
        assertEquals(9L, f.table["keyProbe"])
        // ... but never an empty one or one with less than half the settings, even as a "change"
        f = SvKeep.fold(game, 1L, t("fightBy" to t(hunter to t())), 2L, game, skip)
        assertEquals(ser(game), ser(f.table)); assertEquals(1, f.refused)
        f = SvKeep.fold(game, 1L, t("fightBy" to t(hunter to t("a" to 1L))), 2L, game, skip)
        assertEquals(ser(game), ser(f.table))
        // the kept entry unchanged since the base while the game's changed (its own clean save): the game's
        val base = t("toolbar" to false)
        f = SvKeep.fold(game, 5L, t("toolbar" to false), 9L, base, skip)
        assertEquals(true, f.table["toolbar"])
        // both changed: the newer table
        assertEquals(false, SvKeep.fold(game, 5L, t("toolbar" to false), 9L, t("toolbar" to "x"), skip).table["toolbar"])
        assertEquals(true, SvKeep.fold(game, 9L, t("toolbar" to false), 5L, t("toolbar" to "x"), skip).table["toolbar"])
    }

    @Test fun aGameFileThatDoesNotReadWholeIsNeverWrittenOver() {
        setUpTheThor()
        gameF.writeText("\n$V = {\n\t[\"fightBy\"] = {\n\t\t[\"$hunter\"] = {\n"); gameF.setLastModified(at0851)
        val before = gameF.readText(); val keptBefore = SvKeep.keptFile(kd, V).readText()
        val log = mutableListOf<String>()
        SvKeep.beforeStart(wtf, kd, File(dir, "bak")) { log += it }
        assertEquals(before, gameF.readText())
        assertEquals(keptBefore, SvKeep.keptFile(kd, V).readText())
        assertTrue(log.any { "does not read whole" in it })
    }

    /** an update from the old build: the game file is OUR one-line write (the game never saved since), the kept copy the last Local
     *  session's (the client killed): that session's changes are genuine and come in */
    @Test fun afterAnUpdateTheLastKilledSessionsChangesStillComeIn() {
        sv.mkdirs(); kd.mkdirs()
        val wrote = table(hunterGame)
        gameF.writeText(SvKeep.fileText(SvKeep.Doc(V, SvLua.leaves(wrote), SvLua.ser(wrote), at0851))); gameF.setLastModified(at0851)
        val session = LinkedHashMap(wrote).also { it["castBar"] = t(hunter to list(3044L, 1978L, 2643L, 19434L, 13165L, 5384L, 1499L)) }
        SvKeep.keptFile(kd, V).writeText(SvKeep.toJson(SvKeep.Doc(V, SvLua.leaves(session), SvLua.ser(session), at1230)))
        SvKeep.beforeStart(wtf, kd, File(dir, "bak")) {}
        assertEquals(1499L, gameNow().sub("castBar", hunter, 7L))
        assertEquals(ser(hunterGame["fightBy"]), ser(gameNow().sub("fightBy", hunter)))
    }

    // ---------------------------------------------------------------- the stream's sync

    /** the user's own order: streaming FIRST (no Local start with this build), the kept copy thin: the PC is sent the FULL hunter */
    @Test fun theSyncSendsThePcTheFullHunterNotTheThinKeptCopy() {
        setUpTheThor()
        SvKeep.gameWtfFile(kd).writeText(wtf.absolutePath)              // the game's folder, as the previous build's start left it known
        val pcFile = wowText(V, LinkedHashMap(thinTable()).also { it["keyProbe"] = t("k" to 7L) })
        val log = mutableListOf<String>()
        val w = SvSync.onFiles(kd, files(pcFile)) { log += it }!!
        val sent = SvLua.parse(docOf(w).getString("lua"))
        assertHunterWhole(sent, "sent to the PC")
        assertEquals(ser(t("k" to 7L)), ser(sent["keyProbe"]))                                      // the PC file's own skipped key stays
        assertHunterWhole(keptNow(), "the kept copy")
        assertEquals(gameF.readText(), wowText(V, gameTable()))                                     // the sync never writes the game file
        SvSync.onWritten(kd, JSONObject().put("results", JSONArray().put(JSONObject().put("var", V).put("state", "written"))), w) {}
        assertFalse(SvKeep.fromJson(SvSync.baseFile(kd, V).readText())!!.lua.contains("keyProbe"))
    }

    /** the whole day: Local start, stream (sync, the PC's live documents: a thin one and a genuine change), the next Local start */
    @Test fun afterAStreamTheNextLocalStartStillKeepsTheHunter() {
        setUpTheThor()
        SvKeep.beforeStart(wtf, kd, File(dir, "bak1"), now = at1230 + 60_000L) {}
        val w = SvSync.onFiles(kd, files(wowText(V, thinTable()))) {}!!
        SvSync.onWritten(kd, JSONObject().put("results", JSONArray().put(JSONObject().put("var", V).put("state", "written"))), w) {}
        val onPc = SvLua.parse(docOf(w).getString("lua"))
        // the PC's add-on: a thin hunter (a reset PC) - refused -, then a genuine change of one entry (the PC's newer smart model)
        val thinLive = LinkedHashMap(onPc).also { it["fightBy"] = t(hunter to t()); it["castBar"] = t(hunter to t()) }
        SvSync.fromPc(kd, SvKeep.Doc(V, SvLua.leaves(thinLive), SvLua.ser(thinLive), at1230 + 200_000L)) {}
        assertHunterWhole(keptNow(), "after the thin PC document")
        val changed = LinkedHashMap(onPc).also { it["smartModel"] = t(hunter to t("melee" to 0.5, "range" to 0.5)) }
        SvSync.fromPc(kd, SvKeep.Doc(V, SvLua.leaves(changed), SvLua.ser(changed), at1230 + 300_000L)) {}
        assertEquals(0.5, keptNow().sub("smartModel", hunter, "melee"))
        // the next Local start: everything of the hunter's still there, the PC's genuine change in
        SvKeep.beforeStart(wtf, kd, File(dir, "bak2"), now = at1230 + 400_000L) {}
        val g = gameNow()
        for ((store, v) in hunterGame) if (store != "smartModel") assertEquals(store, ser(v), ser(g.sub(store, hunter)))
        assertEquals(0.5, g.sub("smartModel", hunter, "melee"))
        assertEquals(ser(t("k" to 1L)), ser(g["keyProbe"]))
    }

    /** the PC's table with NO kept copy on the Thor: the Thor's game file is the kept copy first; unknown game folder: marked, and the
     *  next start lets none of the PC's entries replace the game's */
    @Test fun aPcTableWithNoKeptCopyNeverReplacesTheGamesEntries() {
        setUpTheThor()
        SvKeep.keptFile(kd, V).delete()
        val pc = thinTable()
        // the game's folder unknown: kept from the PC, marked
        SvSync.fromPc(kd, SvKeep.Doc(V, SvLua.leaves(pc), SvLua.ser(pc), at1230)) {}
        assertTrue(SvKeep.fromPcMark(kd, V).isFile)
        SvKeep.beforeStart(wtf, kd, File(dir, "bak")) {}
        assertHunterWhole(gameNow(), "after a PC-only kept copy")
        assertFalse(SvKeep.fromPcMark(kd, V).exists())
        // the game's folder known: the Thor's game file is folded in before the PC's table
        SvKeep.keptFile(kd, V).delete()
        SvSync.fromPc(kd, SvKeep.Doc(V, SvLua.leaves(pc), SvLua.ser(pc), at1230 + 1L)) {}
        assertHunterWhole(keptNow(), "kept from the game first")
        assertFalse(SvKeep.fromPcMark(kd, V).exists())
    }
}
