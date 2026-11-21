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
 * THE 11-20 HUNTER (test build 1513: "what happened to my combat bars on my hunter? they are gone?"). The game answered GetRealmName() with
 * "" for a whole session: the add-on keyed every per-character store "Name-" / "Name-?", the app followed (the combat backup
 * "Name-_.json"), the bars looked gone; the real "Name-Realm" data was intact. [CharKey]: no partial key anywhere in the app;
 * [RealmFix]: the orphans folded away, the real key always wins. The device's shape (names and values fake): the real key in 22 stores,
 * the "Name-" orphan in 15, "Name-?" in 2, an empty combat backup "Name-_.json". Temp folders only.
 */
class RealmKeyTest {
    private val dir: File = Files.createTempDirectory("realmkey").toFile()
    @After fun clean() { dir.deleteRecursively(); SvSync.forget(); RealmFix.forget(); RealmFix.keepRemoved = null }

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
        """{"type":"padmap","v":"m179","m":{"page":1,"sets":[],"dpad":{"on":true,"want":true,"cols":{"on":1,"off":2},"who":$who}}}"""))!!

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
    private fun realmFixBackups() = File(kd, "${SvBackup.DIR}/${RealmFix.DIR}").listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty().sorted()

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
        // round 2 (item E): the FIRST copy of each file stays for good (later folds never push the earliest one out)
        assertEquals(gameBefore, File(kd, "${SvBackup.DIR}/${RealmFix.DIR}/${RealmFix.FIRST}/WowCompanionSpells.lua").readText())
        assertEquals(keptBefore, File(kd, "${SvBackup.DIR}/${RealmFix.DIR}/${RealmFix.FIRST}/$V.json").readText())
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
        assertNull(RealmFix.combat(realB, at0841, empty, at1320, real, "HUNTER"))
        // an orphan with a fight spell the real one lacks, NEWER: it comes in; the real entries stay as they are
        val orphan = CombatKeep.Backup("$name-?", listOf(e(1, 1, 999, face = true), e(3, 2, 2643)), "HUNTER")
        val next = RealmFix.combat(realB, at0841, orphan, at1320, real, "HUNTER")!!
        assertEquals(real, next.who)
        assertTrue(next.entries.contains(e(1, 1, 3044, face = true))); assertTrue(next.entries.contains(e(2, 3, 1978)))
        assertTrue(next.entries.contains(e(3, 2, 2643)))
        assertFalse("the real face is never overwritten", next.entries.contains(e(1, 1, 999, face = true)))
        // an OLDER orphan: nothing
        assertNull(RealmFix.combat(realB, at1320, orphan, at0841, real, "HUNTER"))
        // no real backup at all: the orphan's entries are the character's (it says this class)
        assertEquals(orphan.entries.toSet(), RealmFix.combat(null, Long.MIN_VALUE, orphan, at1320, real, "HUNTER")!!.entries.toSet())
        // round 2 (item B): an orphan of NO class (written before k7) or another class is never taken (its file left as it is)
        assertFalse(RealmFix.combatTakes(orphan.copy(cls = null), "HUNTER")); assertFalse(RealmFix.combatTakes(orphan, "WARRIOR"))
        assertFalse("no class now: not this class's either", RealmFix.combatTakes(orphan, null))
        assertNull(RealmFix.combat(null, Long.MIN_VALUE, orphan.copy(cls = null), at1320, real, "HUNTER"))
        assertNull(RealmFix.combat(null, Long.MIN_VALUE, orphan, at1320, real, "WARRIOR"))
        assertTrue("an empty orphan takes nothing (it may go)", RealmFix.combatTakes(empty, "HUNTER"))
        assertFalse("one that does not read is left", RealmFix.combatTakes(null, "HUNTER"))
        // the file names a realm-less session made
        assertEquals(listOf("$name-.json", "$name-_.json"), RealmFix.combatOrphanFiles(name))
        assertEquals("$name-_.json", CombatKeep.fileName("$name-?"))
        assertEquals("$name-.json", CombatKeep.fileName("$name-"))
    }

    @Test fun realmFixBackupsKeepTheTwoNewestPerFile() {
        val f = File(dir, "x.json").apply { writeText("1") }
        val bak = File(dir, "b")
        for (i in 1..4) { f.writeText("$i"); assertTrue(RealmFix.backup(f, bak, at0841 + i * 1000L)) }
        val left = bak.listFiles()!!.filter { it.isFile }.map { it.readText() }.sorted()
        assertEquals(listOf("3", "4"), left)
        assertEquals("round 2 (item E): the first copy stays for good", "1", File(bak, "${RealmFix.FIRST}/x.json").readText())
        assertTrue("no file: nothing to keep", RealmFix.backup(File(dir, "none.json"), bak, at1320))
    }

    // ================================================================ Safety k7: the k6 review's must-fix list (the app's side)

    private class Prefs(val m: LinkedHashMap<String, Any?> = LinkedHashMap()) : android.content.SharedPreferences {
        override fun getAll(): MutableMap<String, *> = LinkedHashMap(m)
        override fun getString(k: String?, d: String?) = m[k] as? String ?: d
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(k: String?, d: MutableSet<String>?) = m[k] as? MutableSet<String> ?: d
        override fun getInt(k: String?, d: Int) = m[k] as? Int ?: d
        override fun getLong(k: String?, d: Long) = m[k] as? Long ?: d
        override fun getFloat(k: String?, d: Float) = m[k] as? Float ?: d
        override fun getBoolean(k: String?, d: Boolean) = m[k] as? Boolean ?: d
        override fun contains(k: String?) = m.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
            val put = LinkedHashMap<String, Any?>(); val rm = HashSet<String>()
            override fun putString(k: String, v: String?) = apply { put[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = apply { put[k] = v }
            override fun putInt(k: String, v: Int) = apply { put[k] = v }
            override fun putLong(k: String, v: Long) = apply { put[k] = v }
            override fun putFloat(k: String, v: Float) = apply { put[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { put[k] = v }
            override fun remove(k: String) = apply { rm += k }
            override fun clear() = apply { rm += m.keys }
            override fun commit(): Boolean { for (k in rm) m.remove(k); m.putAll(put); return true }
            override fun apply() { commit() }
        }
    }

    private fun src(f: String): String {
        val d = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
            .map { File(it) }.first { it.isDirectory }
        return File(d, f).readText()
    }

    /** item 1 (app side): a backup written for another class is another character's - never restored, merged into or written over */
    @Test fun aSameNameCharacterOfAnotherClassNeverGetsTheHuntersCombatBackup() {
        val m = PadMapModel.parse(JSONObject(
            """{"type":"padmap","v":"m179","m":{"page":1,"sets":[],"dpad":{"on":true,"want":true,"cols":{"on":1,"off":2},""" +
            """"who":{"n":"$name","r":"Fake Realm 7 PvE","rs":"kept","rh":"none","c":"WARRIOR"}}}}"""))!!
        assertEquals("WARRIOR", m.dpad!!.cls); assertEquals("none", m.dpad.realmHint)
        val hunter = CombatKeep.Backup(real, listOf(e(1, 1, 3044, face = true)), "HUNTER")
        assertEquals("the class rides the backup file", hunter, CombatKeep.fromJson(CombatKeep.toJson(hunter)))
        assertNull("an older backup has none", CombatKeep.fromJson("""{"v":1,"who":"$real","entries":[]}""")!!.cls)
        assertTrue(CombatKeep.otherClass(m, hunter))
        assertFalse("the alt's empty store never restores the hunter's backup", CombatKeep.needsRestore(m, hunter))
        assertFalse("an older backup (no class) or an older add-on (no class): not refused on that ground",
            CombatKeep.otherClass(m, hunter.copy(cls = null)))
        assertEquals("a merge carries the class the padmap says", "WARRIOR", CombatKeep.merge(null, m)!!.cls)
        // the host: checked before anything (restore, fold, merge, write); never a name in its log lines
        val host = src("CombatKeepHost.kt")
        val onMap = host.substring(host.indexOf("fun onMap("), host.indexOf("private fun foldOrphans("))
        assertTrue(onMap.indexOf("CombatKeep.otherClass(map, backup0)") in 0 until onMap.indexOf("foldOrphans(who, map.dpad?.cls)"))
        assertTrue(onMap.contains("if (who in otherClass) return"))
    }

    /** item 2: a character named with no realm (a switch, rs "none") lets the previous character's key go in every app store */
    @Test fun aCharacterWithNoRealmIsNeverThePreviousOne() {
        val qs = HashMap<String, String>()
        val qw = QuestWatchKeep.Model(object : QuestWatchKeep.Store {
            override fun get(k: String) = qs[k]
            override fun put(k: String, v: String?) { if (v == null) qs.remove(k) else qs[k] = v }
        })
        qw.character(real); qw.onQuests(listOf(QuestWatchKeep.Q(10, 1, true)), canAct = true)
        assertEquals("10", qs["w:$real"])
        qw.character(null)
        assertEquals(emptyList<QuestWatchKeep.Q>(), qw.onQuests(listOf(QuestWatchKeep.Q(20, 1, true)), canAct = true))
        qw.cleanEnd()
        assertEquals("nothing of the new character under the hunter's key", "10", qs["w:$real"]); assertNull(qs["c:$real"])
        val ts = HashMap<String, String>()
        val tn = TaxiNet.Model(object : TaxiNet.Store {
            override fun get(k: String) = ts[k]
            override fun put(k: String, v: String?) { if (v == null) ts.remove(k) else ts[k] = v }
        })
        tn.character(real); tn.onTaxiNet(1, listOf(2)); assertTrue(tn.connected(1, 2))
        tn.character(null)
        assertFalse("the hunter's flights are not the new character's", tn.connected(1, 2))
        tn.onTaxiNet(3, listOf(4))
        assertEquals(setOf("1", "2"), ts["g:$real"]!!.split(",").toSet()); assertEquals(1, ts.size)
        // the chat checklist and the last used chat
        var chat: String? = null
        ChatFilter.reset(); ChatFilter.init(object : ChatFilter.Store { override fun read() = chat; override fun write(s: String) { chat = s } })
        ChatFilter.character(real); ChatFilter.toggle("loot")
        assertFalse(ChatFilter.shows("loot"))
        ChatFilter.character(null)
        assertNull(ChatFilter.who); assertTrue("everything shows, nothing saved", ChatFilter.shows("loot"))
        ChatFilter.toggle("xp")
        assertFalse("nothing written under any name", chat!!.contains("xp"))
        ChatFilter.reset()
        var talk: String? = null
        TalkKeep.reset(); TalkKeep.init(object : TalkKeep.Store { override fun read() = talk; override fun write(s: String) { talk = s } })
        TalkKeep.character(real); TalkKeep.went(TalkKeep.Used("PARTY"))
        TalkKeep.character(null)
        assertNull(TalkKeep.who); assertEquals(TalkKeep.Used(), TalkKeep.used)
        TalkKeep.went(TalkKeep.Used("GUILD"))
        assertFalse(talk!!.contains("GUILD"))
        TalkKeep.reset()
        // the Hearthstone's kept end time
        val ic = HashMap<String, String>()
        ItemCd.reset(); ItemCd.store = object : ItemCd.Store {
            override fun get(key: String) = ic[key]
            override fun put(key: String, v: String?) { if (v == null) ic.remove(key) else ic[key] = v }
        }
        ItemCd.character(real, now = 0L)
        ItemCd.onDoc(JSONObject("""{"type":"itemcd","who":"$real","t":{"123":{"d":30,"l":20}}}"""), true, now = 0L)
        assertTrue(ItemCd.left(123, now = 0L) > 0.0)
        ItemCd.character(null, now = 0L)
        assertEquals("the hunter's countdowns are not the new character's", 0.0, ItemCd.left(123, now = 0L), 0.0)
        ItemCd.reset(); ItemCd.store = null
        // the host: a named character with no key sets every store to null (never left on the previous one)
        val h = src("CompanionHost.kt")
        val block = h.substring(h.indexOf("val ck = CharKey.of(d.name, d.realm)"), h.indexOf("lastDpad = d; settings.homeSection.dpadChanged()"))
        assertTrue(block.contains("val named = ck != null || !d.name.isNullOrEmpty()"))
        for (s in listOf("radialView.character(ck)", "ItemCd.character(ck)", "RadialMeta.who = ck", "QuestWatchKeep.character(ck)",
                "TaxiNet.character(ck)", "GameUi.character = ck", "ChatFilter.character(ck)", "TalkKeep.character(ck)"))
            assertTrue(s, block.contains(s))
        assertFalse("no setter waits for a whole key only", block.contains("ck != null) GameUi.character"))
        assertTrue(h.contains("if (k != null || !it.dpad?.name.isNullOrEmpty()) newSpellWho = k"))
    }

    /** item 3: a Reset with orphans present - its plan takes the orphans with it, and no fold brings an orphan's old value back */
    @Test fun aResetIsNeverUndoneByAnOrphan() {
        val castOld = t(1L to t("k" to "spell", "id" to 3044L))
        val castOrphan = t(1L to t("k" to "spell", "id" to 1L))
        val game = t("castBar" to t(real to castOld, "$name-?" to castOrphan), "dpad" to t(real to true, "$name-" to false))
        // the plan: the real key's entries and its name's orphans (removed: never given the default)
        val ts = ResetPlan.svTargets(setOf(ResetPlan.Kind.SKILLS, ResetPlan.Kind.LAYOUTS), real, false, mapOf(V to listOf(game)))
        assertTrue(ts.contains(SvReset.Target(V, "castBar", real, null)))
        assertTrue(ts.contains(SvReset.Target(V, "castBar", "$name-?", null)))
        assertTrue("the orphan is removed, never set to the default", ts.contains(SvReset.Target(V, "dpad", "$name-", null)))
        assertTrue(ts.contains(SvReset.Target(V, "dpad", real, true)))
        // the name under two realms: its orphans are not this character's to reset (never a guess)
        val two = LinkedHashMap(game).apply { put("fightBy", t("$name-Other Realm" to t(1L to 1L))) }
        assertFalse(ResetPlan.svTargets(setOf(ResetPlan.Kind.SKILLS), real, false, mapOf(V to listOf(two))).any { CharKey.isOrphan(it.who) })
        assertTrue("All characters: every orphan", ResetPlan.svTargets(setOf(ResetPlan.Kind.SKILLS), null, true, mapOf(V to listOf(two)))
            .any { it.who == "$name-?" })
        // the preferences: "<prefix>Name-" goes with the character
        assertEquals(setOf("smartPet.$real", "smartPet.$name-"),
            ResetPlan.prefKeys("home", setOf("smartPet.$real", "smartPet.$name-", "smartPet.Alt-R"), setOf(ResetPlan.Kind.SKILLS), real, false))
        assertEquals("another realm in the file: its orphan left", setOf("smartPet.$real"),
            ResetPlan.prefKeys("home", setOf("smartPet.$real", "smartPet.$name-", "smartPet.$name-Other"), setOf(ResetPlan.Kind.SKILLS), real, false))

        // a reset made by an older plan (the real key only) with the orphan still in the game file: the next start does NOT adopt it
        val keptAfter = t("dpad" to t(real to true))
        setUpTheThor(game, keptAfter)
        val resetAt = at1320
        SvReset.write(kd, listOf(SvReset.Mark(V, "castBar", real, listOf(ser(castOld)), resetAt)))
        SvKeep.beforeStart(wtf, kd, File(dir, "bak"), now = resetAt + 60_000L) { }
        assertNull("the game file: the reset entry stays reset, the orphan dropped", gameNow().sub("castBar", real))
        assertNull(keptNow().sub("castBar", real))
        assertEquals(emptyList<String>(), orphans(gameNow()))
        // the PC's realm-less copy after the reset: its orphan never comes in either
        val pc = t("castBar" to t("$name-?" to castOrphan), "dpad" to t(real to true))
        SvSync.fromPc(kd, SvKeep.Doc(V, SvLua.leaves(pc), SvLua.ser(pc), resetAt + 120_000L), resetAt + 120_000L) { }
        assertNull("the PC's orphan never fills the reset entry", keptNow().sub("castBar", real))

        // the In-combat backup: the reset takes the name's orphan files with the real one (backed up first)
        val combat = File(dir, "combat").apply { mkdirs() }
        val realF = File(combat, CombatKeep.fileName(real)).apply { writeText(CombatKeep.toJson(CombatKeep.Backup(real, listOf(e(1, 1, 3044, true))))) }
        val orphanF = File(combat, "$name-_.json").apply { writeText("""{"v":1,"who":"$name-?","entries":[{"page":1,"set":2,"pos":3,"id":1978,"n":"S"}]}""") }
        val prefs = HashMap<String, Prefs>()
        val kd2 = File(dir, "kept2").apply { mkdirs() }
        val r = ResetRun({ prefs.getOrPut(it) { Prefs() } }, kd2, combat) { }.run(ResetPlan.Button.SKILLS, real, false, gameRunning = false, now = resetAt)
        assertEquals(2, r.combat)
        assertFalse(realF.exists()); assertFalse("the orphan goes too: the next run's fold cannot make it the real backup", orphanF.exists())
        assertEquals("both backed up first", 2, File(kd2, SvReset.BACKUP_DIR).listFiles()!!.count { it.name.startsWith("$name-") && it.name.endsWith(".bak") })
        // another realm's backup of that name: the orphan is not this character's (left)
        orphanF.writeText("""{"v":1,"who":"$name-?","entries":[]}"""); realF.writeText(CombatKeep.toJson(CombatKeep.Backup(real, emptyList())))
        File(combat, CombatKeep.fileName("$name-Other Realm")).writeText("""{"v":1,"who":"$name-Other Realm","entries":[]}""")
        ResetRun({ prefs.getOrPut(it) { Prefs() } }, kd2, combat) { }.run(ResetPlan.Button.SKILLS, real, false, gameRunning = false, now = resetAt + 1)
        assertTrue(orphanF.exists()); assertFalse(realF.exists())
    }

    /** item 8: the app's own per-character choices a realm-less session left under "Name-" fold into the real key (it wins) */
    @Test fun theAppsOwnChoicesFoldIntoTheRealKey() {
        val home = Prefs(linkedMapOf("smartPet.$real" to true, "smartPet.$name-" to false, "holdTalk.$name-" to true, "rest" to "THIN",
            "holdTalk.Ash-R" to false, "talkSendNow.$name-" to ""))
        val bak = Prefs()
        val alone = RealmFix.Census(setOf("Fake Realm 7 PvE"), classes = setOf("HUNTER"))
        assertEquals("no census: nothing folds (one file cannot tell)", 0 to 0, RealmFix.foldPrefs(home, "home", real, bak, at1320, null))
        assertEquals("1 adopted (holdTalk), 2 dropped (the real smartPet wins; an empty one)", 1 to 2, RealmFix.foldPrefs(home, "home", real, bak, at1320, alone))
        assertEquals(mapOf("smartPet.$real" to true, "rest" to "THIN", "holdTalk.Ash-R" to false, "holdTalk.$real" to true), home.m)
        val kept = JSONArray(bak.getString(RealmFix.bakKey("home", real), null))
        assertEquals(1, kept.length())
        assertEquals("what went is kept with its type", false, kept.getJSONObject(0).getJSONObject("removed").getJSONObject("smartPet.$name-").get("v"))
        assertEquals("idempotent", 0 to 0, RealmFix.foldPrefs(home, "home", real, bak, at1320 + 1, alone))
        // the name under another realm in a file: left as it is (never a guess)
        val gameui = Prefs(linkedMapOf("sw.player.$name-" to true, "sw.player.$name-Other Realm" to false))
        assertEquals(0 to 0, RealmFix.foldPrefs(gameui, "gameui", real, bak, at1320, alone))
        assertEquals(2, gameui.m.size)
        // a name that only ENDS with this one is another character ("XHuntress-" is not "Huntress-")
        assertTrue(RealmFix.prefPlan(mapOf("q.X$name-" to "1"), real).empty)
        // the JSON stores (RadialMeta / ChatFilter / TalkKeep)
        val all = JSONObject().put("$name-", JSONArray().put("loot")).put("Alt-R", JSONArray())
        val gone = RealmFix.foldJson(all, real)!!
        assertEquals(listOf("loot"), (0 until all.getJSONArray(real).length()).map { all.getJSONArray(real).getString(it) })
        assertFalse(all.has("$name-")); assertTrue(gone.has("$name-"))
        val both = JSONObject().put(real, JSONArray()).put("$name-", JSONArray().put("xp"))
        RealmFix.foldJson(both, real)
        assertEquals("the real key wins", 0, both.getJSONArray(real).length())
        assertNull("another realm: nothing changed", RealmFix.foldJson(JSONObject().put("$name-", 1).put("$name-Other", 2), real))
        // ChatFilter folds at its character switch (the device's census agrees, what goes is kept first)
        RealmFix.census = { alone }
        val chatBak = Prefs()
        RealmFix.keepRemoved = { f, r, removed -> RealmFix.keepIn(chatBak, f, r, removed, at1320) }
        var chat: String? = JSONObject().put("$name-", JSONArray().put("loot")).toString()
        ChatFilter.reset(); ChatFilter.init(object : ChatFilter.Store { override fun read() = chat; override fun write(s: String) { chat = s } })
        ChatFilter.character(real)
        assertFalse("the realm-less session's tick is the hunter's now", ChatFilter.shows("loot"))
        assertFalse(JSONObject(chat!!).has("$name-"))
        assertTrue("kept first", chatBak.m.containsKey(RealmFix.bakKey("chat_filter", real)))
        ChatFilter.reset()
        // the host folds before the stores read, once per key, every per-character file
        val h = src("CompanionHost.kt")
        assertTrue(h.indexOf("if (current && ck != null) foldPrefOrphans(ck)") in 0 until h.indexOf("if (current && named) ItemCd.character(ck)"))
        assertEquals(listOf("dpad", "home", "gameui", "wc_itemcd", "questwatch", "taxinet", "newspells", "polestar_qol"), RealmFix.PREF_FILES)
    }

    /** item 9: a saved table's own "word-" key is never taken for a character's orphan */
    @Test fun aTablesOwnWordKeyIsNoOrphan() {
        for (k in listOf("lay-", "v2-", "hub_x-", "pos-?", "2-")) assertFalse(k, CharKey.isOrphan(k))
        assertTrue(CharKey.isOrphan("Ærwyn-")); assertTrue(CharKey.isOrphan("치유사-?"))
        val m = t("lay-" to t("x" to 1L), "lay-Big" to t("y" to 2L), "store" to t("lay-" to 5L, "lay-Big" to 6L))
        val r = RealmFix.clean(m, RealmFix.realmsByName(listOf(m)))
        assertTrue(r.items.isEmpty()); assertEquals(1L, r.table.sub("lay-", "x"))
    }

    // ================================================================ round 2: the WHOLE device decides (a same-name alt on another realm)

    private val realmA = "Fake Realm 7 PvE"
    private val alt = "$name-Other Realm"
    /** the hunter's saved tables: its real key, and realmOf's record of realm A */
    private val hunterSv = t("fightBy" to t(real to t(1L to "DSMAP")), "realmOf" to t(name to t(realmA to t("s" to "1", "c" to "HUNTER"))))

    /** item A (item 8 of round 1): the alt (realm B, live) never takes the hunter's orphans of a file where the hunter has nothing else */
    @Test fun aSameNameAltOnAnotherRealmNeverAdoptsTheHuntersAppOrphans() {
        val questwatch = Prefs(linkedMapOf("w:$name-" to "10,11", "c:$name-" to "12"))
        val taxinet = Prefs(linkedMapOf("g:$name-" to "1,2"))
        val chat = JSONObject().put("$name-", JSONArray().put("loot"))
        fun census(r: String) = RealmFix.censusFrom(r, listOf(hunterSv), listOf(questwatch.m.keys, taxinet.m.keys), listOf(chat), emptyList())
        // the file alone would adopt both (the round-1 rule)
        assertEquals(setOf("w:$name-", "c:$name-"), RealmFix.prefPlan(questwatch.m, alt).adopt.keys)
        // the alt logs in live: the census holds the name under A (the saved tables) -> nothing folds, nothing is removed
        RealmFix.saw(alt)
        val cAlt = census(alt)
        assertEquals(setOf(realmA), cAlt.stored); assertEquals(setOf("Other Realm"), cAlt.seen)
        assertFalse(RealmFix.foldOk(alt, cAlt))
        val bak = Prefs()
        assertEquals(0 to 0, RealmFix.foldPrefs(questwatch, "questwatch", alt, bak, at1320, cAlt))
        assertEquals(0 to 0, RealmFix.foldPrefs(taxinet, "taxinet", alt, bak, at1320, cAlt))
        assertEquals(mapOf("w:$name-" to "10,11", "c:$name-" to "12"), questwatch.m); assertEquals(1, taxinet.m.size); assertTrue(bak.m.isEmpty())
        // the JSON stores the same (the device hook)
        RealmFix.census = { r -> census(r) }
        RealmFix.keepRemoved = { f, r, removed -> RealmFix.keepIn(bak, f, r, removed, at1320) }
        assertFalse(RealmFix.foldStore(chat, alt, "chat_filter")); assertTrue(chat.has("$name-")); assertFalse(chat.has(alt))
        var stored: String? = chat.toString()
        ChatFilter.reset(); ChatFilter.init(object : ChatFilter.Store { override fun read() = stored; override fun write(s: String) { stored = s } })
        ChatFilter.character(alt)
        assertTrue("the alt shows everything (the hunter's tick is not its)", ChatFilter.shows("loot"))
        assertTrue(JSONObject(stored!!).has("$name-")); assertTrue(bak.m.isEmpty())
        ChatFilter.reset()
        // an alt's own live realm is never evidence by itself (no saved table of anyone): nothing folds
        assertFalse(RealmFix.foldOk(alt, RealmFix.Census(emptySet(), setOf("Other Realm"))))
        // the name stored under two realms: never a fold, for either
        assertFalse(RealmFix.foldOk(real, RealmFix.Census(setOf(realmA, "Other Realm"))))
        // the hunter itself (a later app life: only A seen): everything folds into its real key
        RealmFix.forget()
        RealmFix.saw(real)
        val cA = census(real)
        assertTrue(RealmFix.foldOk(real, cA))
        assertEquals(2 to 0, RealmFix.foldPrefs(questwatch, "questwatch", real, bak, at1320, cA))
        assertEquals(mapOf("w:$real" to "10,11", "c:$real" to "12"), questwatch.m)
        // a name seen live under two realms this app life (the alt played earlier): no fold
        RealmFix.saw(alt)
        assertFalse(RealmFix.foldOk(real, census(real)))
        // every source counts: the alt under B in another preference file, a JSON store or an In-combat backup file's name
        RealmFix.forget()
        assertEquals(setOf(realmA, "Other Realm"), RealmFix.censusFrom(real, listOf(hunterSv), listOf(setOf("smartPet.$alt")), emptyList(), emptyList()).stored)
        assertEquals(setOf(realmA, "Other Realm"), RealmFix.censusFrom(real, listOf(hunterSv), emptyList(), listOf(JSONObject().put(alt, 1)), emptyList()).stored)
        val withFile = RealmFix.censusFrom(real, listOf(hunterSv), emptyList(), emptyList(), listOf(CombatKeep.fileName(alt)))
        assertTrue(withFile.otherFile); assertFalse(RealmFix.foldOk(real, withFile))
        assertEquals("its own backup file is evidence; an orphan file is none", setOf(realmA),
            RealmFix.censusFrom(real, emptyList(), emptyList(), emptyList(), listOf(CombatKeep.fileName(real), "$name-_.json")).stored)
        assertEquals("a name that only ends with this one is another character", emptySet<String>(), RealmFix.prefRealms(setOf("q.X$alt"), name))
        // the host: the census asked before any preference fold; every source read; a live key counted before the In-combat fold
        val h = src("CompanionHost.kt")
        val fold = h.substring(h.indexOf("private fun foldPrefOrphans("), h.indexOf("private fun realmCensus("))
        assertTrue(fold.contains("RealmFix.foldOk(ck, c)"))
        val cen = h.substring(h.indexOf("private fun realmCensus(")).take(1500)
        for (x in listOf("SvKeep.tables(kd)", "RealmFix.PREF_FILES", "RealmFix.JSON_STORES", "DS Forever/combat")) assertTrue(x, cen.contains(x))
        assertTrue(h.contains("RealmFix.census = { real -> realmCensus(real) }"))
        assertTrue(h.indexOf("RealmFix.saw(CharKey.of(it.dpad?.name, it.dpad?.realm))") in 0 until h.indexOf("combatKeep.onMap(it, current)"))
    }

    /** item B: the hunter's "Name-_.json" (no real file) never becomes a same-name alt's backup; a no-class orphan is never restored */
    @Test fun theHuntersCombatOrphanNeverBecomesTheAltsBackup() {
        val c = RealmFix.censusFrom(alt, listOf(hunterSv), emptyList(), emptyList(), listOf("$name-_.json"))
        assertFalse("the name is the hunter's (realm A) in the saved tables", RealmFix.foldOk(alt, c))
        val orphan = CombatKeep.fromJson("""{"v":1,"who":"$name-?","entries":[{"page":1,"set":2,"pos":3,"id":1978,"n":"S"}]}""")!!
        assertNull(orphan.cls)
        assertFalse("no class: never taken", RealmFix.combatTakes(orphan, "WARRIOR"))
        assertNull(RealmFix.combat(null, Long.MIN_VALUE, orphan, at1320, alt, "WARRIOR"))
        // the host: the census and the class are asked before anything comes in; the file is then left as it is
        val host = src("CombatKeepHost.kt")
        val fo = host.substring(host.indexOf("private fun foldOrphans("))
        assertTrue(fo.indexOf("RealmFix.mayFold(who)") in 0 until fo.indexOf("for (f in orphans)"))
        assertTrue(fo.indexOf("RealmFix.combatTakes(orphan, cls)") in 0 until fo.indexOf("RealmFix.combat(real"))
        assertTrue(host.contains("foldOrphans(who, map.dpad?.cls)"))
        // no device census at all (off the device): never a fold
        assertFalse(RealmFix.mayFold(real))
    }

    /** item C: our saved tables count the name's realms over BOTH tables (the other table's realmOf / keys too), as the add-on does */
    @Test fun theSavedTablesCountTheNameUnderBothTables() {
        setUpTheThor(deviceTable(), deviceTable())
        val core = t("realmOf" to t(name to t("Other Realm" to t("s" to "2", "c" to "WARRIOR"))))
        SvKeep.keptFile(kd, "WowCompanionDB").writeText(SvKeep.toJson(SvKeep.Doc("WowCompanionDB", SvLua.leaves(core), SvLua.ser(core), at1320)))
        SvKeep.beforeStart(wtf, kd, File(dir, "bak"), now = at1320 + 60_000L) { }
        assertEquals("never a guess: the Spells table's orphans left (the core table knows the name under another realm)", 17, orphans(gameNow()).size)
        assertRealWhole(gameNow(), "the game file")
        assertNull("nothing adopted", gameNow().sub("hmBy", real))
        // the PC's realm-less copy the same: its orphan never fills the real key
        val pc = t("hmBy" to t("$name-" to t("hp" to 9L)))
        SvSync.fromPc(kd, SvKeep.Doc(V, SvLua.leaves(pc), SvLua.ser(pc), at1320 + 120_000L), at1320 + 120_000L) { }
        assertNull(keptNow().sub("hmBy", real))
        // the device census (both tables, every source) is asked by the fold too: another realm only the app knows leaves the orphans
        RealmFix.census = { r -> RealmFix.censusFrom(r, listOf(deviceTable()), listOf(setOf("smartPet.$alt")), emptyList(), emptyList()) }
        val dev = deviceTable()
        assertTrue(RealmFix.clean(dev, RealmFix.realmsByName(listOf(dev))).items.isEmpty())
        RealmFix.census = { r -> RealmFix.censusFrom(r, listOf(deviceTable()), emptyList(), emptyList(), emptyList()) }
        assertTrue("round 3 (item 2): no class recorded for the realm (no live k7+ session saved): nothing folds",
            RealmFix.clean(dev, RealmFix.realmsByName(listOf(dev))).items.isEmpty())
        RealmFix.census = { r -> RealmFix.censusFrom(r, listOf(deviceTable(), hunterCore), emptyList(), emptyList(), emptyList()) }
        assertEquals(17, RealmFix.clean(dev, RealmFix.realmsByName(listOf(dev))).items.size)
    }

    /** item D: a JSON store folds only once what goes is kept (no backup, a failed one: nothing changed, nothing written) */
    @Test fun aJsonStoreFoldsOnlyOnceWhatGoesIsKept() {
        RealmFix.census = { RealmFix.Census(setOf(realmA), classes = setOf("HUNTER")) }
        val all = JSONObject().put("$name-", JSONArray().put("loot"))
        RealmFix.keepRemoved = null
        assertFalse("no backup: nothing changed", RealmFix.foldStore(all, real, "chat_filter")); assertTrue(all.has("$name-"))
        RealmFix.keepRemoved = { _, _, _ -> false }
        assertFalse(RealmFix.foldStore(all, real, "chat_filter")); assertTrue(all.has("$name-"))
        RealmFix.keepRemoved = { _, _, _ -> error("disk full") }
        assertFalse(RealmFix.foldStore(all, real, "chat_filter")); assertTrue(all.has("$name-")); assertFalse(all.has(real))
        // TalkKeep's character switch writes nothing then
        var talk: String? = JSONObject().put("$name-", JSONObject().put("t", "PARTY")).toString()
        val before = talk
        TalkKeep.reset(); TalkKeep.init(object : TalkKeep.Store { override fun read() = talk; override fun write(s: String) { talk = s } })
        TalkKeep.character(real)
        assertEquals(before, talk)
        TalkKeep.reset()
        RealmFix.keepRemoved = { _, _, _ -> true }
        assertTrue(RealmFix.foldStore(all, real, "chat_filter")); assertFalse(all.has("$name-")); assertTrue(all.has(real))
        for (f in listOf("ChatFilter.kt", "TalkKeep.kt", "RadialMeta.kt")) {
            val x = src(f); assertTrue(f, x.contains("RealmFix.foldStore(")); assertFalse(f, x.contains("RealmFix.foldJson("))
        }
    }

    /** item E: the folds' backups are kept per character (another character's folds never push this one's out) */
    @Test fun theFoldBackupsAreKeptPerCharacter() {
        val bak = Prefs()
        for (i in 1..3) assertTrue(RealmFix.keepIn(bak, "home", real, JSONObject().put("k$i", i).toString(), at1320 + i))
        for (i in 1..3) assertTrue(RealmFix.keepIn(bak, "home", "Ash-R", JSONObject().put("a", i).toString(), at1320 + 9 + i))
        val mine = JSONArray(bak.getString(RealmFix.bakKey("home", real), null))
        assertEquals("the 2 newest of this character", 2, mine.length())
        assertEquals(3, mine.getJSONObject(0).getJSONObject("removed").getInt("k3"))
        assertEquals(2, mine.getJSONObject(1).getJSONObject("removed").getInt("k2"))
        assertEquals(2, JSONArray(bak.getString(RealmFix.bakKey("home", "Ash-R"), null)).length())
    }

    /** item G: a single character's Reset of the D-pad choice removes the orphan "choice_Name-" (never writes the default under it) */
    @Test fun aResetWritesNoOrphanKey() {
        val prefs = HashMap<String, Prefs>()
        prefs["dpad"] = Prefs(linkedMapOf("choice_$real" to false, "choice_$name-" to false))
        val kd3 = File(dir, "kept3").apply { mkdirs() }
        ResetRun({ prefs.getOrPut(it) { Prefs() } }, kd3, File(dir, "combat3")) { }
            .run(ResetPlan.Button.LAYOUTS, real, false, gameRunning = false, now = at1320)
        val d = prefs["dpad"]!!.m
        assertEquals(true, d["choice_$real"]); assertEquals(true, d["pend_choice_$real"])
        assertFalse("the orphan removed, never given the default", d.containsKey("choice_$name-"))
        assertFalse(d.containsKey("pend_choice_$name-"))
    }

    // ================================================================ round 3 (the k6 review's third verdict)

    /** the core table's realmOf of the hunter's realm, as a live k7+ session records it (server, class, k9's GUID; fake values) */
    private val hunterCore = t("realmOf" to t(name to t(realmA to t("s" to "1", "c" to "HUNTER", "g" to "Player-1-0000AAAA"))))

    /** item 2: a fold needs the realm's RECORDED class, and it must be the class playing now (the add-on's Safety k9 rule) */
    @Test fun aFoldNeedsTheRecordedClassOfThePlayingCharacter() {
        val keys = listOf(setOf("smartPet.$name-"))
        fun c(tables: List<Map<*, *>?>, playing: String?) = RealmFix.censusFrom(real, tables, keys, emptyList(), emptyList(), playing)
        val keysOnly = t("fightBy" to t(real to t(1L to "DSMAP")))
        assertFalse("only our tables' keys (no realmOf record): no fold", RealmFix.foldOk(real, c(listOf(keysOnly), null)))
        val k6 = t("realmOf" to t(name to t(realmA to true)))
        assertFalse("k6's `true` record (no class): no fold", RealmFix.foldOk(real, c(listOf(keysOnly, k6), null)))
        assertEquals(setOf("HUNTER"), c(listOf(keysOnly, hunterCore), null).classes)
        assertTrue("the recorded class, nothing playing now (before a start)", RealmFix.foldOk(real, c(listOf(keysOnly, hunterCore), null)))
        assertTrue("the recorded class is the one playing", RealmFix.foldOk(real, c(listOf(keysOnly, hunterCore), "HUNTER")))
        assertFalse("ANOTHER class playing under that key: never a fold", RealmFix.foldOk(real, c(listOf(keysOnly, hunterCore), "WARRIOR")))
        val two = t("realmOf" to t(name to t(realmA to t("c" to "MAGE"))))
        assertFalse("two classes recorded over the copies: never a guess", RealmFix.foldOk(real, c(listOf(hunterCore, two), "HUNTER")))
        // the app's own preference fold asks the same census: another class playing leaves every orphan where it is
        val home = Prefs(linkedMapOf("smartPet.$name-" to true))
        assertEquals(0 to 0, RealmFix.foldPrefs(home, "home", real, Prefs(), at1320, c(listOf(hunterCore), "WARRIOR")))
        assertEquals(1, home.m.size)
        assertEquals(1 to 0, RealmFix.foldPrefs(home, "home", real, Prefs(), at1320, c(listOf(hunterCore), "HUNTER")))
        // the host passes the class of the current padmap (only when it is [real]'s)
        val cen = src("CompanionHost.kt").let { it.substring(it.indexOf("private fun realmCensus(")).take(2000) }
        assertTrue(cen.contains("CharKey.of(d.name, d.realm) == real }?.cls") && cen.contains("combat, playing)"))
    }

    /** item 5: a single character's Reset takes its name's orphans only when the DEVICE knows that name under no other realm */
    @Test fun aResetLeavesOrphansTheDeviceCannotTieToThisCharacter() {
        val game = t("castBar" to t(real to t(1L to t("k" to "spell", "id" to 3044L)), "$name-?" to t(1L to t("k" to "spell", "id" to 1L))),
            "realmOf" to t(name to t(realmA to t("s" to "1", "c" to "HUNTER"))))
        val homeKeys = setOf("smartPet.$real", "smartPet.$name-")
        // the device: the alt under another realm in a preference file only (one table cannot see it)
        RealmFix.census = { r -> RealmFix.censusFrom(r, listOf(game), listOf(setOf("holdTalk.$alt")), emptyList(), emptyList()) }
        assertFalse(RealmFix.resetTakesOrphans(real))
        val ts = ResetPlan.svTargets(setOf(ResetPlan.Kind.SKILLS), real, false, mapOf(V to listOf(game)))
        assertTrue(ts.contains(SvReset.Target(V, "castBar", real, null)))
        assertFalse("the orphan left (it may be the alt's)", ts.any { CharKey.isOrphan(it.who) })
        assertEquals(setOf("smartPet.$real"), ResetPlan.prefKeys("home", homeKeys, setOf(ResetPlan.Kind.SKILLS), real, false))
        // ... nor would any fold take it (the same census): the reset is never undone by it
        assertFalse(RealmFix.mayFold(real))
        // the run: the In-combat orphan file and the radials' orphan stay too; the real ones go (backed up first)
        val combat = File(dir, "combat5").apply { mkdirs() }
        val realF = File(combat, CombatKeep.fileName(real)).apply { writeText(CombatKeep.toJson(CombatKeep.Backup(real, listOf(e(1, 1, 3044, true))))) }
        val orphanF = File(combat, "$name-_.json").apply { writeText("""{"v":1,"who":"$name-?","entries":[]}""") }
        val prefs = HashMap<String, Prefs>()
        prefs["radial_meta"] = Prefs(linkedMapOf("all" to JSONObject().put(real, 1).put("$name-", 2).toString()))
        prefs["home"] = Prefs(linkedMapOf("smartPet.$real" to true, "smartPet.$name-" to false))
        val kd5 = File(dir, "kept5").apply { mkdirs() }
        val r = ResetRun({ prefs.getOrPut(it) { Prefs() } }, kd5, combat) { }.run(ResetPlan.Button.SKILLS, real, false, gameRunning = false, now = at1320)
        assertEquals(1, r.combat); assertFalse(realF.exists()); assertTrue("the orphan backup file left", orphanF.exists())
        val meta = JSONObject(prefs["radial_meta"]!!.getString("all", "{}")!!)
        assertFalse(meta.has(real)); assertTrue("the radials' orphan left", meta.has("$name-"))
        assertEquals(mapOf("smartPet.$name-" to false), prefs["home"]!!.m)
        // the device knows the name under this realm alone: the orphans go with the character (as before)
        RealmFix.forget()
        RealmFix.census = { r2 -> RealmFix.censusFrom(r2, listOf(game), emptyList(), emptyList(), emptyList()) }
        assertTrue(RealmFix.resetTakesOrphans(real))
        assertTrue(ResetPlan.svTargets(setOf(ResetPlan.Kind.SKILLS), real, false, mapOf(V to listOf(game))).contains(SvReset.Target(V, "castBar", "$name-?", null)))
        assertEquals(homeKeys, ResetPlan.prefKeys("home", homeKeys, setOf(ResetPlan.Kind.SKILLS), real, false))
        ResetRun({ prefs.getOrPut(it) { Prefs() } }, kd5, combat) { }.run(ResetPlan.Button.SKILLS, real, false, gameRunning = false, now = at1320 + 1)
        assertFalse(orphanF.exists()); assertTrue(prefs["home"]!!.m.isEmpty())
        assertFalse(JSONObject(prefs["radial_meta"]!!.getString("all", "{}")!!).has("$name-"))
        // a census that cannot be read: left (never a guess)
        RealmFix.forget(); RealmFix.census = { error("unreadable") }
        assertFalse(RealmFix.resetTakesOrphans(real))
    }

    /** item 4: no log line carries an exception's message (an IOException's names the file: the character's name, the account's path) */
    @Test fun noLogCarriesAnExceptionsMessage() {
        for (f in listOf("CombatKeepHost.kt", "SvKeep.kt", "SvReset.kt", "RealmFix.kt", "ResetRun.kt", "SvMerge.kt")) {
            val x = src(f)
            assertFalse(f, x.contains("\${it.message}"))
            assertFalse(f, x.contains(".message}"))
        }
        assertTrue(src("CombatKeepHost.kt").contains("orphan backup NOT folded (\${it.javaClass.simpleName})"))
    }

    /** item 7 / 10: the diagnostic line says the hint; the In-combat backup's and the radials' log lines carry no character name */
    @Test fun theLogsSayTheHintAndNeverAName() {
        // Safety k10: the line is KeyPick.diagLine's (its words: KeyPickTest), the host logs it as it is
        val h = src("CompanionHost.kt")
        assertTrue(h.contains("KeyPick.diagLine(it.dpad)") && src("KeyPick.kt").contains("b.append(\", hint \").append(it)")
            && src("KeyPick.kt").contains("it == \"present\" || it == \"none\""))
        val host = src("CombatKeepHost.kt")
        for (bad in listOf("for \${b.who}", "of \$who\")", "of \${backup.who}", "for \${backup.who}")) assertFalse(bad, host.contains(bad))
        assertFalse(src("HomeRadialView.kt").contains("radial: character \$key"))
    }
}
