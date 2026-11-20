package com.jrees.wowcompanion

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
 * THE 11-20 HUNTER (test build 1513: "what happened to my combat bars on my hunter? they are gone?"). The game answered GetRealmName() with
 * "" for a whole session: the add-on keyed every per-character store "Name-" / "Name-?", the app followed (the combat backup
 * "Name-_.json"), the bars looked gone; the real "Name-Realm" data was intact. [CharKey]: no partial key anywhere in the app;
 * [RealmFix]: the orphans folded away, the real key always wins. The device's shape (names and values fake): the real key in 22 stores,
 * the "Name-" orphan in 15, "Name-?" in 2, an empty combat backup "Name-_.json". Temp folders only.
 */
class RealmKeyTest {
    private val dir: File = Files.createTempDirectory("realmkey").toFile()
    @After fun clean() { dir.deleteRecursively(); SvSync.forget() }

    private val name = "Huntress"
    private val real = "Huntress-Fake Realm 7 PvE"
    private val V = "WowCompanionSpellsDB"
    private val at0841 = 1_795_120_860_000L          // the last good session's save
    private val at1320 = 1_795_136_400_000L          // the realm-less session's kept copy

    private fun t(vararg p: Pair<Any, Any>): Map<Any, Any> = linkedMapOf(*p)
    @Suppress("UNCHECKED_CAST") private fun Map<Any, Any>.sub(vararg k: Any): Any? = k.fold(this as Any?) { m, x -> (m as? Map<Any, Any>)?.get(x) }
    private fun ser(x: Any?) = SvLua.ser(x!!)

    // ---------------------------------------------------------------- the key

    @Test fun aKeyIsOnlyEverAWholeNameRealm() {
        assertEquals(real, CharKey.of(name, "Fake Realm 7 PvE"))
        for (r in listOf(null, "", "   ", "?", "_", "\u0001", "\u0002", "\u0003")) assertNull("realm '$r'", CharKey.of(name, r))
        assertNull(CharKey.of("", "Realm")); assertNull(CharKey.of(null, "Realm"))
        assertEquals("Huntress-Azjol-Nerub", CharKey.of(name, "Azjol-Nerub"))                        // a realm may hold a '-'
        assertTrue(CharKey.isKey(real)); assertTrue(CharKey.isKey("Huntress-Azjol-Nerub"))
        for (o in listOf("$name-", "$name-?", "$name-_", "$name-\u0002")) { assertTrue(o, CharKey.isOrphan(o)); assertFalse(o, CharKey.isKey(o)) }
        assertFalse(CharKey.isOrphan(real)); assertFalse(CharKey.isOrphan("fightBy")); assertFalse(CharKey.isOrphan(7L))
        assertEquals(name, CharKey.nameOf(real)); assertEquals(name, CharKey.nameOf("$name-?"))
        // the Reset's character key follows the same rule (a "Name-?" / "Name-_" key is no character)
        assertTrue(ResetPlan.isCharKey(real)); assertFalse(ResetPlan.isCharKey("$name-")); assertFalse(ResetPlan.isCharKey("$name-?"))
        assertFalse(ResetPlan.isCharKey("$name-_"))
    }

    private fun padmap(who: String) = PadMapModel.parse(JSONObject(
        """{"type":"padmap","v":"m178","m":{"page":1,"sets":[],"dpad":{"on":true,"want":true,"cols":{"on":1,"off":2},"who":$who}}}"""))!!

    @Test fun aPadmapWithNoRealmMakesNoKeyAnywhereInTheApp() {
        for (who in listOf("""{"n":"$name","r":"","rs":"none"}""", """{"n":"$name","rs":"none"}""", """{"n":"$name","r":"?"}""")) {
            val m = padmap(who)
            assertNull(who, m.dpad!!.realm)
            assertNull("no combat backup key ($who)", CombatKeep.who(m))
            assertNull("no In-combat backup written ($who)", CombatKeep.merge(null, m))
            assertNull("no pet recall key ($who)", PetRecall.who(m.dpad.name, m.dpad.realm))
        }
        val ok = padmap("""{"n":"$name","r":"Fake Realm 7 PvE","rs":"kept"}""")
        assertEquals(real, CombatKeep.who(ok)); assertEquals(real, PetRecall.who(ok.dpad!!.name, ok.dpad.realm))
        assertEquals("kept", ok.dpad.realmSource)
        assertNull("an older add-on: no source", padmap("""{"n":"$name","r":"Fake Realm 7 PvE"}""").dpad!!.realmSource)
    }

    // ---------------------------------------------------------------- the device's shape

    private val stores = listOf("fightBy", "faceBy", "binds", "cmdBy", "stickBy", "attackBy", "dpad", "ccOff", "genUse", "lowPickBy", "smartOn",
        "smartBuffs", "partyBy", "petSpellBy", "macroRoom", "radialPins", "sradials", "castBar", "castBarFight", "castBarParty", "formSlotsBy", "padLayout")

    /** the real hunter: 22 stores, each with its own settings (fightBy = the bars) */
    private fun realStore(s: String): Any = when (s) {
        "fightBy" -> t(1L to t("t" to "command", "key" to "DSMAP", "page" to 1L, "set" to 1L, "pos" to 1L), 5L to t("t" to "spell", "id" to 3044L))
        "dpad", "smartOn" -> true
        else -> t("v" to (stores.indexOf(s) + 1).toLong(), "note" to "real $s")
    }

    /** the game file / kept copy of the device: the real key in 22 stores; "Name-" in 15 (14 over real stores - every other one empty -,
     *  one with a setting where the real key has none: hmBy); "Name-?" in 2 (castBar, castBarFight) */
    private fun deviceTable(withOrphans: Boolean = true): Map<Any, Any> {
        val out = LinkedHashMap<Any, Any>()
        for (s in stores) out[s] = linkedMapOf<Any, Any>(real to realStore(s))
        out["sitMode"] = "stand"
        if (!withOrphans) return out
        stores.take(14).forEachIndexed { i, s ->
            @Suppress("UNCHECKED_CAST") (out[s] as MutableMap<Any, Any>)["$name-"] = if (i % 2 == 0) t() else t("v" to 999L, "note" to "orphan")
        }
        out["hmBy"] = linkedMapOf<Any, Any>("$name-" to t("hp" to 5L, "t" to 1L))
        @Suppress("UNCHECKED_CAST") (out["castBar"] as MutableMap<Any, Any>)["$name-?"] = t(1L to t("k" to "spell", "id" to 1L))
        @Suppress("UNCHECKED_CAST") (out["castBarFight"] as MutableMap<Any, Any>)["$name-?"] = t()
        return out
    }

    private fun orphans(m: Map<*, *>): List<String> {
        val out = ArrayList<String>()
        fun walk(x: Map<*, *>, path: String, d: Int) {
            for ((k, v) in x) {
                if (CharKey.isOrphan(k)) out += "$path.$k"
                if (v is Map<*, *> && d < 3) walk(v, "$path.$k", d + 1)
            }
        }
        walk(m, "", 1)
        return out
    }

    private fun assertRealWhole(m: Map<Any, Any>, what: String) {
        for (s in stores) assertEquals("$what: $s", ser(realStore(s)), ser(m.sub(s, real)))
    }

    @Test fun theDevicesShapeFoldsOrphansAwayAndKeepsTheRealKey() {
        val dev = deviceTable()
        assertEquals("15 + 2 orphans", 17, orphans(dev).size)
        val r = RealmFix.clean(dev, RealmFix.realmsByName(listOf(dev)))
        assertEquals(emptyList<String>(), orphans(r.table))
        assertRealWhole(r.table, "the fold")
        assertEquals("the one store the real key lacked takes the orphan's setting", ser(t("hp" to 5L, "t" to 1L)), ser(r.table.sub("hmBy", real)))
        assertEquals(1, r.adopted); assertEquals(16, r.dropped)
        assertEquals("the other keys untouched", "stand", r.table["sitMode"])
        // idempotent: nothing more to fold
        assertTrue(RealmFix.clean(r.table, RealmFix.realmsByName(listOf(r.table))).items.isEmpty())
        // never a guess: the name under two realms leaves the orphans as they are
        val two = LinkedHashMap(dev).apply { put("lay", t("$name-Other Realm" to t("x" to 1L))) }
        val r2 = RealmFix.clean(two, RealmFix.realmsByName(listOf(two)))
        assertTrue(r2.items.isEmpty()); assertEquals(17, orphans(r2.table).size)
    }

    @Test fun aRealKeyIsNeverOverwrittenNorGivenAnOrphanItHoldsInAnotherCopy() {
        // the orphan's different fight spells over the real ones: the real ones stay
        val m = t("fightBy" to t(real to t(1L to "DSMAP"), "$name-" to t(1L to "DSCHAT", 3L to "DSBAGS")))
        val r = RealmFix.clean(m, RealmFix.realmsByName(listOf(m)))
        assertEquals(ser(t(real to t(1L to "DSMAP"))), ser(r.table["fightBy"]))
        // the game file lacks the real store, the kept copy holds it: the game's orphan is NOT adopted (the kept real one wins the fold)
        val game = t("faceBy" to t("$name-" to t("x" to 9L)), "fightBy" to t(real to t(1L to "DSMAP")))
        val kept = t("faceBy" to t(real to t("x" to 1L)), "fightBy" to t(real to t(1L to "DSMAP")))
        val realms = RealmFix.realmsByName(listOf(game, kept))
        val g = RealmFix.clean(game, realms, RealmFix.held(listOf(kept)))
        assertEquals(0, g.adopted); assertNull(g.table.sub("faceBy", real))
        val f = SvKeep.fold(g.table, 1L, kept, 2L, null, emptySet())
        assertEquals(1L, f.table.sub("faceBy", real, "x"))
    }

    private val wtf get() = File(dir, "WTF")
    private val kd get() = File(dir, "kept")
    private val gameF get() = File(wtf, "Account/FAKEACCT/SavedVariables/WowCompanionSpells.lua")
    private fun setUpTheThor(game: Map<Any, Any>, kept: Map<Any, Any>) {
        gameF.parentFile!!.mkdirs(); kd.mkdirs()
        gameF.writeText(SvKeep.fileText(SvKeep.Doc(V, SvLua.leaves(game), SvLua.ser(game), at0841)).replace(" = {", " = {\n"))
        gameF.setLastModified(at0841)
        SvKeep.keptFile(kd, V).writeText(SvKeep.toJson(SvKeep.Doc(V, SvLua.leaves(kept), SvLua.ser(kept), at1320)))
    }
    private fun gameNow() = SvLua.parse(gameF.readText(), V)
    private fun keptNow() = SvLua.parse(SvKeep.fromJson(SvKeep.keptFile(kd, V).readText())!!.lua)
    private fun realmFixBackups() = File(kd, "${SvBackup.DIR}/${RealmFix.DIR}").listFiles()?.map { it.name }.orEmpty().sorted()

    /** the device: the game file (08:41, the real hunter + the orphans the realm-less session's svkeep folded in), the kept copy (13:20) */
    @Test fun theNextStartFoldsTheKeptCopyAndTheGameFileWithBackups() {
        setUpTheThor(deviceTable(), deviceTable())
        val gameBefore = gameF.readText(); val keptBefore = SvKeep.keptFile(kd, V).readText()
        val log = mutableListOf<String>()
        SvKeep.beforeStart(wtf, kd, File(dir, "bak"), now = at1320 + 60_000L) { log += it }
        val g = gameNow(); val k = keptNow()
        assertEquals("the game file: no orphan", emptyList<String>(), orphans(g))
        assertEquals("the kept copy: no orphan", emptyList<String>(), orphans(k))
        assertRealWhole(g, "the game file"); assertRealWhole(k, "the kept copy")
        assertEquals(ser(t("hp" to 5L, "t" to 1L)), ser(g.sub("hmBy", real)))
        assertEquals("stand", g["sitMode"])
        // every file touched was copied first: the realm-fix copies (one of each), the start's own backup, the kept copy's dated one
        val baks = realmFixBackups()
        assertEquals(baks.toString(), 2, baks.size)
        assertEquals(gameBefore, File(kd, "${SvBackup.DIR}/${RealmFix.DIR}/${baks.first { it.startsWith("WowCompanionSpells.lua.") }}").readText())
        assertEquals(keptBefore, File(kd, "${SvBackup.DIR}/${RealmFix.DIR}/${baks.first { it.startsWith("$V.json.") }}").readText())
        assertEquals(gameBefore, File(dir, "bak/WowCompanionSpells.lua").readText())
        assertTrue(log.toString(), log.any { "orphan keys folded (1 into the real key, 16 dropped)" in it })
        assertFalse("no name in the log", log.any { name in it })
        // idempotent: the next start folds nothing and keeps no new copy
        SvKeep.beforeStart(wtf, kd, File(dir, "bak2"), now = at1320 + 120_000L) { }
        assertEquals(baks, realmFixBackups())
        assertRealWhole(gameNow(), "the second start")
        // two more realm-less sessions: the realm-fix copies stay at the 2 newest per file
        for (i in 1..2) {
            setUpTheThor(deviceTable(), deviceTable())
            SvKeep.beforeStart(wtf, kd, File(dir, "bak$i$i"), now = at1320 + 600_000L * i) { }
        }
        val names = realmFixBackups()
        assertEquals(names.toString(), 2, names.count { it.startsWith("WowCompanionSpells.lua.") })
        assertEquals(names.toString(), 2, names.count { it.startsWith("$V.json.") })
        assertRealWhole(gameNow(), "after three folds")
    }

    /** a kept copy whose real key is thinner than the game's: the orphan never wins over the game file's real store */
    @Test fun aKeptOrphanNeverShadowsTheGameFilesRealStore() {
        val game = deviceTable(withOrphans = false)
        val kept = LinkedHashMap<Any, Any>(deviceTable()).apply { remove("faceBy"); put("faceBy", linkedMapOf<Any, Any>("$name-" to t("x" to 7L))) }
        setUpTheThor(game, kept)
        SvKeep.beforeStart(wtf, kd, File(dir, "bak"), now = at1320 + 60_000L) { }
        assertRealWhole(gameNow(), "the game file")
        assertEquals(emptyList<String>(), orphans(gameNow()))
        assertEquals(ser(realStore("faceBy")), ser(keptNow().sub("faceBy", real)))
    }

    @Test fun aPcSessionWithNoRealmAddsNoOrphanToTheKeptCopy() {
        kd.mkdirs()
        val local = deviceTable(withOrphans = false)
        SvKeep.keptFile(kd, V).writeText(SvKeep.toJson(SvKeep.Doc(V, SvLua.leaves(local), SvLua.ser(local), at0841)))
        val pc = deviceTable()
        SvSync.fromPc(kd, SvKeep.Doc(V, SvLua.leaves(pc), SvLua.ser(pc), at1320), at1320) { }
        val k = keptNow()
        assertEquals(emptyList<String>(), orphans(k))
        assertRealWhole(k, "the kept copy")
        assertEquals("the PC's only real-less setting comes in under the real key", ser(t("hp" to 5L, "t" to 1L)), ser(k.sub("hmBy", real)))
    }

    // ---------------------------------------------------------------- the In-combat backup ("Name-_.json")

    private fun e(set: Int, pos: Int, id: Int, face: Boolean = false) = CombatKeep.Entry(if (face) 0 else 1, set, pos, id, "S$id", face)

    @Test fun theCombatBackupsOrphanFoldsWithTheRealOneWinning() {
        val realB = CombatKeep.Backup(real, listOf(e(1, 1, 3044, face = true), e(2, 3, 1978)))
        // the device: an EMPTY orphan (38 B, "entries":[]): nothing comes in
        val empty = CombatKeep.fromJson("""{"v":1,"who":"$name-?","entries":[]}""")
        assertNotNull(empty)
        assertNull(RealmFix.combat(realB, at0841, empty, at1320, real))
        // an orphan with a fight spell the real one lacks, NEWER: it comes in; the real entries stay as they are
        val orphan = CombatKeep.Backup("$name-?", listOf(e(1, 1, 999, face = true), e(3, 2, 2643)))
        val next = RealmFix.combat(realB, at0841, orphan, at1320, real)!!
        assertEquals(real, next.who)
        assertTrue(next.entries.contains(e(1, 1, 3044, face = true))); assertTrue(next.entries.contains(e(2, 3, 1978)))
        assertTrue(next.entries.contains(e(3, 2, 2643)))
        assertFalse("the real face is never overwritten", next.entries.contains(e(1, 1, 999, face = true)))
        // an OLDER orphan: nothing
        assertNull(RealmFix.combat(realB, at1320, orphan, at0841, real))
        // no real backup at all: the orphan's entries are the character's
        assertEquals(orphan.entries.toSet(), RealmFix.combat(null, Long.MIN_VALUE, orphan, at1320, real)!!.entries.toSet())
        // the file names a realm-less session made
        assertEquals(listOf("$name-.json", "$name-_.json"), RealmFix.combatOrphanFiles(name))
        assertEquals("$name-_.json", CombatKeep.fileName("$name-?"))
        assertEquals("$name-.json", CombatKeep.fileName("$name-"))
    }

    @Test fun realmFixBackupsKeepTheTwoNewestPerFile() {
        val f = File(dir, "x.json").apply { writeText("1") }
        val bak = File(dir, "b")
        for (i in 1..4) { f.writeText("$i"); assertTrue(RealmFix.backup(f, bak, at0841 + i * 1000L)) }
        val left = bak.listFiles()!!.map { it.readText() }.sorted()
        assertEquals(listOf("3", "4"), left)
        assertTrue("no file: nothing to keep", RealmFix.backup(File(dir, "none.json"), bak, at1320))
    }
}
