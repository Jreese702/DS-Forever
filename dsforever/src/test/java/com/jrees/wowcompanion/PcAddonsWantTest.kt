package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The Thor's side of its add-ons on the PC (2026-11-18): the "addons_want" it sends Desktop (the managed pins, the imported add-ons'
 * files, the on / off of its AddOns.txt), Desktop's "addon_file_get" parts it serves, and the status line for Desktop's "addons_pc".
 * Temp folders, fake account names only.
 */
class PcAddonsWantTest {
    private lateinit var tmp: File
    private val game get() = File(tmp, "_forever_")
    private val addons get() = File(game, "Interface/AddOns")

    @Before fun up() { tmp = Files.createTempDirectory("pcwant").toFile(); addons.mkdirs() }
    @After fun down() { tmp.deleteRecursively() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun everyManagedPinWithTheThorsWant() {
        val wants = mapOf("polestar/core" to true, "manners/core" to false)
        val a = PcAddonsWant.managed(ThirdPartyAddons.ALL) { ad, k -> wants["${ad.id}/$k"] ?: false }
        val rows = (0 until a.length()).map { a.getJSONObject(it) }
        val polestar = rows.first { it.getString("id") == "polestar" }
        assertEquals(ThirdPartyAddons.POLESTAR.core.sha256, polestar.getString("sha256"))
        assertEquals(ThirdPartyAddons.POLESTAR.core.tag, polestar.getString("tag"))
        assertEquals("", polestar.getString("url"))                          // a GitHub package goes by its tag, never a URL
        assertTrue(polestar.getBoolean("want"))
        val manners = rows.first { it.getString("id") == "manners" }
        assertTrue(manners.getString("url").startsWith("https://mediafilez.forgecdn.net/files/"))
        assertFalse(manners.getBoolean("want"))
        // Spoken's core and its three packs, each its own row
        assertEquals(4, rows.count { it.getString("id") == "spoken" })
        assertEquals(setOf("core", "quests", "zones", "books"), rows.filter { it.getString("id") == "spoken" }.map { it.getString("key") }.toSet())
    }

    @Test fun onAnywhereIsOnOffEverywhereIsOff() {
        val acct = File(game, "WTF/Account/FAKE#1")
        File(acct, "Realm/Alpha").mkdirs(); File(acct, "Realm/Beta").mkdirs(); File(acct, "SavedVariables/X").mkdirs()
        File(acct, "Realm/Alpha/AddOns.txt").writeText("Polestar: disabled\r\nBagnon: enabled\r\nManners: disabled\r\n")
        File(acct, "Realm/Beta/AddOns.txt").writeText("ï»¿Polestar: enabled\nManners: disabled\nTradeSkillMaster: disabled\n", Charsets.ISO_8859_1)
        File(acct, "SavedVariables/X/AddOns.txt").writeText("Manners: enabled\n")      // not a game file: never read
        val on = PcAddonsWant.enabled(game, setOf("Polestar", "Manners", "Bagnon", "QuestieDB"))
        assertEquals(mapOf("Polestar" to true, "Manners" to false, "Bagnon" to true), on)
        assertFalse("only the folders asked about", on.containsKey("TradeSkillMaster"))
        assertEquals(emptyMap<String, Boolean>(), PcAddonsWant.enabled(null, setOf("Polestar")))
    }

    @Test fun importedFilesHashedCachedAndTheTreeMatchesDesktops() {
        val d = File(addons, "Bagnon").apply { mkdirs() }
        File(d, "Bagnon.toc").writeText("## Version: 10.2\n")
        File(d, "media").mkdirs(); File(d, "media/bg.blp").writeBytes(ByteArray(1000) { it.toByte() })
        val cache = JSONObject()
        val files = PcAddonsWant.hashes(d, cache)
        assertEquals(listOf("Bagnon.toc", "media/bg.blp"), files.map { it.p })
        assertEquals(sha(File(d, "media/bg.blp").readBytes()), files[1].h)
        assertTrue("cached", cache.has("Bagnon/media/bg.blp"))
        // a cached hash is used while size and time hold (a fake one shows it was not read again)
        cache.getJSONObject("Bagnon/Bagnon.toc").put("h", "cached")
        assertEquals("cached", PcAddonsWant.hashes(d, cache)[0].h)
        // the tree is Desktop's PcImport.Tree: sha256 over the sorted "path\nsize\nsha256\n" lines
        val lines = files.sortedBy { it.p }.joinToString("") { "${it.p}\n${it.n}\n${it.h}\n" }
        assertEquals(sha(lines.toByteArray()), PcAddonsWant.tree(files))
        val big = File(addons, "Huge").apply { mkdirs() }
        File(big, "Huge.toc").writeText("## Version: 1\n")
        val imp = PcAddonsWant.imported(addons, listOf("Bagnon", "Huge", "WowCompanion", "Missing"), mapOf("Bagnon" to false), JSONObject()) { false }
        assertEquals(2, imp.length())
        val b = imp.getJSONObject(0)
        assertEquals("Bagnon", b.getString("folder")); assertEquals("10.2", b.getString("version"))
        assertFalse("off on the Thor", b.getBoolean("enabled"))
        assertTrue(b.getBoolean("allowed")); assertFalse(b.getBoolean("big"))
    }

    @Test fun aBigImportedAddOnWaitsForTheOk() {
        val state = File(tmp, "state")
        assertFalse(PcAddonsWant.allowed(state, "Spoken2"))
        PcAddonsWant.allowBig(state, "Spoken2", true)
        assertTrue(PcAddonsWant.allowed(state, "Spoken2"))
        assertFalse(PcAddonsWant.allowed(state, "Other"))
    }

    @Test fun servesOnlyPartsOfImportedFiles() {
        val d = File(addons, "Bagnon").apply { mkdirs() }
        val bytes = ByteArray(700_000) { (it % 251).toByte() }
        File(d, "big.blp").writeBytes(bytes)
        File(addons, "WowCompanion").mkdirs(); File(addons, "WowCompanion/x.lua").writeText("ours")
        File(tmp, "secret.txt").writeText("no")
        val names = setOf("Bagnon", "WowCompanion")
        fun req(folder: String, p: String, off: Long, len: Int) = JSONObject().put("id", "7").put("folder", folder).put("p", p).put("off", off).put("len", len)
        val ok = PcAddonsWant.serve(req("Bagnon", "big.blp", 524_288, 262_144), addons, names)
        assertEquals("addon_file", ok.getString("t")); assertEquals("7", ok.getString("id")); assertEquals(524_288L, ok.getLong("off"))
        val got = java.util.Base64.getDecoder().decode(ok.getString("data"))
        assertEquals(700_000 - 524_288, got.size)                            // the file's end
        assertTrue(got.contentEquals(bytes.copyOfRange(524_288, 700_000)))
        assertEquals("addon_file_err", PcAddonsWant.serve(req("Bagnon", "../../secret.txt", 0, 10), addons, names).getString("t"))
        assertEquals("addon_file_err", PcAddonsWant.serve(req("Other", "x", 0, 10), addons, names).getString("t"))
        assertEquals("addon_file_err", PcAddonsWant.serve(req("WowCompanion", "x.lua", 0, 10), addons, names).getString("t"))
        assertEquals("addon_file_err", PcAddonsWant.serve(req("Bagnon", "big.blp", 0, 2_000_000), addons, names).getString("t"))
        assertEquals("addon_file_err", PcAddonsWant.serve(req("Bagnon", "big.blp", 0, 10), null, names).getString("t"))
    }

    @Test fun theStatusLineSaysOnlyWhatIsNotIn() {
        assertEquals("PC add-ons: Downloading Polestar 50%",
            PcAddonsWant.statusLine(JSONObject("""{"t":"addons_pc","state":"working","line":"Downloading Polestar","done":5,"total":10}""")))
        assertNull(PcAddonsWant.statusLine(JSONObject("""{"state":"done","items":[{"name":"QuestieDB","state":"ok","why":"1.1.1"}]}""")))
        assertEquals("PC add-ons: QuestieDB not installed (offline) (+1 more)",
            PcAddonsWant.statusLine(JSONObject("""{"state":"done","items":[{"name":"Manners","state":"deferred","why":"WoW is running"},{"name":"QuestieDB","state":"failed","why":"offline"}]}""")))
    }
}
