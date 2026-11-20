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
        assertTrue(onMap.indexOf("CombatKeep.otherClass(map, backup0)") in 0 until onMap.indexOf("foldOrphans(who)"))
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
        assertEquals("1 adopted (holdTalk), 2 dropped (the real smartPet wins; an empty one)", 1 to 2, RealmFix.foldPrefs(home, "home", real, bak, at1320))
        assertEquals(mapOf("smartPet.$real" to true, "rest" to "THIN", "holdTalk.Ash-R" to false, "holdTalk.$real" to true), home.m)
        val kept = JSONArray(bak.getString("home", null))
        assertEquals(1, kept.length())
        assertEquals("what went is kept with its type", false, kept.getJSONObject(0).getJSONObject("removed").getJSONObject("smartPet.$name-").get("v"))
        assertEquals("idempotent", 0 to 0, RealmFix.foldPrefs(home, "home", real, bak, at1320 + 1))
        // the name under another realm in a file: left as it is (never a guess)
        val gameui = Prefs(linkedMapOf("sw.player.$name-" to true, "sw.player.$name-Other Realm" to false))
        assertEquals(0 to 0, RealmFix.foldPrefs(gameui, "gameui", real, bak, at1320))
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
        // ChatFilter folds at its character switch
        var chat: String? = JSONObject().put("$name-", JSONArray().put("loot")).toString()
        ChatFilter.reset(); ChatFilter.init(object : ChatFilter.Store { override fun read() = chat; override fun write(s: String) { chat = s } })
        ChatFilter.character(real)
        assertFalse("the realm-less session's tick is the hunter's now", ChatFilter.shows("loot"))
        assertFalse(JSONObject(chat!!).has("$name-"))
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

    /** item 7 / 10: the diagnostic line says the hint; the In-combat backup's and the radials' log lines carry no character name */
    @Test fun theLogsSayTheHintAndNeverAName() {
        val h = src("CompanionHost.kt")
        assertTrue(h.contains("\", hint \$h\"") && h.contains("REALM_HINTS = setOf(\"present\", \"none\")"))
        val host = src("CombatKeepHost.kt")
        for (bad in listOf("for \${b.who}", "of \$who\")", "of \${backup.who}", "for \${backup.who}")) assertFalse(bad, host.contains(bad))
        assertFalse(src("HomeRadialView.kt").contains("radial: character \$key"))
    }
}
