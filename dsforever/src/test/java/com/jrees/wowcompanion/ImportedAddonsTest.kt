package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Settings > Add-ons > Import, off-device (the user, 2026-11-16: "maybe we just do an import addons button then?"): where the add-ons
 * are in a zip (flat, a wrapper, nested, a suite, a loose .toc, a flavour .toc), the guards (a path out, a link, too many, too big, a
 * squeezed bomb, an entry bigger than it says), the .toc's words, the names never touched, and the swap's failures (no room: nothing
 * touched; a move failing part way: every old copy back; the list written only on success; Remove only for what was imported).
 */
class ImportedAddonsTest {
    private lateinit var tmp: File
    private lateinit var game: File
    private val addons get() = File(game, "Interface/AddOns")
    private val backups get() = File(game, "Interface/${DependencyAddons.BACKUP}")
    private val sep = File.separator
    private val realRename = ThirdPartyAddons.renameDir
    private val realFileRename = ThirdPartyAddons.fileRename

    @Before fun up() {
        tmp = Files.createTempDirectory("imported").toFile()
        game = File(tmp, "_forever_").apply { mkdirs() }
        ImportedAddons.start(File(tmp, "private"), setOf("WowCompanion", "WowCompanionHub", "WowCompanionSpells"))
        ImportedAddons.freeSpace = { Long.MAX_VALUE }
        ImportedAddons.gameRunning = { false }
        ThirdPartyAddons.renameDir = realRename; ThirdPartyAddons.fileRename = realFileRename
    }
    @After fun down() {
        ThirdPartyAddons.renameDir = realRename; ThirdPartyAddons.fileRename = realFileRename
        ImportedAddons.freeSpace = { it.usableSpace }
        ImportedAddons.gameRunning = { DependencyAddons.gameRunning() }
        tmp.deleteRecursively()
    }

    // ---------------------------------------------------------------- zips
    private var n = 0
    /** a zip by java.util.zip (deflated) */
    private fun zip(vararg files: Pair<String, String>): File {
        val f = File(tmp, "z${n++}.zip")
        ZipOutputStream(f.outputStream()).use { z -> for ((name, t) in files) { z.putNextEntry(ZipEntry(name)); z.write(t.toByteArray()); z.closeEntry() } }
        return f
    }

    /** one entry for [raw]: its name, its bytes, its external attributes, its sizes as declared (null = the true ones), deflated or stored */
    private class R(val name: String, val data: ByteArray = ByteArray(0), val attr: Long = 0, val usize: Long? = null, val csize: Long? = null,
                    val deflate: Boolean = false, val flags: Int = 0)

    /** a zip written by hand: any name, any attributes, any declared size */
    private fun raw(vararg es: R): File {
        val bo = ByteArrayOutputStream()
        fun w16(v: Int) { bo.write(v and 255); bo.write((v shr 8) and 255) }
        fun w32(v: Long) { w16((v and 0xFFFF).toInt()); w16(((v shr 16) and 0xFFFF).toInt()) }
        val cd = ArrayList<Triple<R, Long, Pair<ByteArray, Long>>>()     // entry, local offset, (stored bytes, crc)
        for (e in es) {
            val crc = CRC32().apply { update(e.data) }.value
            val body = if (e.deflate) Deflater(Deflater.DEFAULT_COMPRESSION, true).run { setInput(e.data); finish(); val b = ByteArray(e.data.size + 64); val k = deflate(b); end(); b.copyOf(k) } else e.data
            val off = bo.size().toLong()
            val nb = e.name.toByteArray(Charsets.UTF_8)
            w32(0x04034b50); w16(20); w16(e.flags or 0x800); w16(if (e.deflate) 8 else 0); w16(0); w16(0)
            w32(crc); w32(e.csize ?: body.size.toLong()); w32(e.usize ?: e.data.size.toLong()); w16(nb.size); w16(0)
            bo.write(nb); bo.write(body)
            cd.add(Triple(e, off, body to crc))
        }
        val cdOff = bo.size().toLong()
        for ((e, off, bc) in cd) {
            val nb = e.name.toByteArray(Charsets.UTF_8)
            w32(0x02014b50); w16(0x0314); w16(20); w16(e.flags or 0x800); w16(if (e.deflate) 8 else 0); w16(0); w16(0)
            w32(bc.second); w32(e.csize ?: bc.first.size.toLong()); w32(e.usize ?: e.data.size.toLong()); w16(nb.size); w16(0); w16(0)
            w16(0); w16(0); w32(e.attr); w32(off); bo.write(nb)
        }
        val cdSize = bo.size().toLong() - cdOff
        w32(0x06054b50); w16(0); w16(0); w16(cd.size); w16(cd.size); w32(cdSize); w32(cdOff); w16(0)
        return File(tmp, "r${n++}.zip").apply { writeBytes(bo.toByteArray()) }
    }

    private fun toc(title: String = "T", ver: String? = "1.0", iface: String = "16001") =
        "## Interface: $iface\n## Title: $title\n" + (ver?.let { "## Version: $it\n" } ?: "")

    private fun refused(why: String, block: () -> Unit) {
        try { block(); fail("not refused ($why)") } catch (r: ImportedAddons.Refused) { assertEquals(why, r.message) }
    }
    private fun spots(z: File) = ImportedAddons.layout(ImportedAddons.scan(z)).map { it.src to it.folder }

    // ---------------------------------------------------------------- where the add-ons are
    @Test fun aFlatFolder() {
        assertEquals(listOf("Bagnon" to "Bagnon"), spots(zip("Bagnon/Bagnon.toc" to toc(), "Bagnon/a.lua" to "x")))
    }
    @Test fun aWrapperFolder() {
        assertEquals(listOf("Bagnon-2.1/Bagnon" to "Bagnon"), spots(zip("Bagnon-2.1/Bagnon/Bagnon.toc" to toc(), "Bagnon-2.1/README.md" to "r")))
    }
    @Test fun doubleNesting() {
        assertEquals(listOf("a/b/Details" to "Details"), spots(zip("a/b/Details/Details.toc" to toc(), "a/b/Details/x/y.lua" to "z")))
    }
    @Test fun aSuiteOfThree() {
        val z = zip("DBM-Core/DBM-Core.toc" to toc(), "DBM-GUI/DBM-GUI.toc" to toc(), "DBM-StatusBarTimers/DBM-StatusBarTimers.toc" to toc(),
            "DBM-Core/x.lua" to "1")
        assertEquals(listOf("DBM-Core", "DBM-GUI", "DBM-StatusBarTimers"), spots(z).map { it.second })
    }
    @Test fun aLooseTocAtTheTop() {
        assertEquals(listOf("" to "Questie"), spots(zip("Questie.toc" to toc(), "Questie.lua" to "x", "Modules/a.lua" to "y")))
    }
    @Test fun aGitHubArchiveFolder() {
        // "Name-main/" holding Name.toc: the folder is named after the .toc
        assertEquals(listOf("Leatrix-main" to "Leatrix"), spots(zip("Leatrix-main/Leatrix.toc" to toc(), "Leatrix-main/Leatrix_Forever.toc" to toc())))
    }
    @Test fun aFlavourTocOnly() {
        val z = zip("Manners/Manners_Camelot.toc" to toc(), "Manners/Manners_Forever.toc" to toc(ver = "2.0"), "Manners/m.lua" to "x")
        assertEquals(listOf("Manners" to "Manners"), spots(z))
        assertEquals("Manners/Manners_Forever.toc", ImportedAddons.preferredToc("Manners", ImportedAddons.layout(ImportedAddons.scan(z))[0].tocs))
        assertEquals("2.0", ImportedAddons.plan(z, null)[0].version)
    }
    @Test fun aNonAddonFolderInsideAnAddon() {
        // Libs/LibStub/LibStub.toc inside the add-on: part of it, not a second add-on
        val z = zip("Ace/Ace.toc" to toc(), "Ace/Libs/LibStub/LibStub.toc" to toc(), "Ace/Libs/LibStub/LibStub.lua" to "x", "Ace/media/a.tga" to "y")
        assertEquals(listOf("Ace" to "Ace"), spots(z))
        assertEquals(4, ImportedAddons.plan(z, null)[0].files)
    }
    @Test fun noTocAtAll() {
        refused("No add-on in this zip") { spots(zip("readme.txt" to "hi", "x/y.lua" to "z")) }
    }
    @Test fun tocsOfTwoNamesLooseAreNoAddon() {
        refused("No add-on in this zip") { spots(zip("A.toc" to toc(), "B.toc" to toc())) }
    }
    @Test fun aFolderNameOfDotsIsRefused() {
        // "...toc" has the stem ".." (the Interface folder): never an add-on name, nothing written anywhere
        File(addons, "WowCompanion").mkdirs(); File(addons, "WowCompanion/WowCompanion.toc").writeText("ours")
        refused("Bad name") { spots(zip("...toc" to toc(), "a.lua" to "x")) }
        refused("Bad name") { spots(zip("x/.._Forever.toc" to toc())) }
        refused("Bad name") { spots(zip(".._Forever.toc" to toc())) }
        refused("Bad name") { spots(zip("....toc" to toc())) }                          // stem "..."
        refused("Bad name") { spots(zip("   .toc" to toc())) }                          // all spaces
        refused("Bad name") { spots(zip("w/.../....toc" to toc())) }                    // a folder "..." holding its own .toc
        refused("Bad name") { ImportedAddons.plan(zip("...toc" to toc()), addons) }
        assertTrue(File(addons, "WowCompanion/WowCompanion.toc").isFile)
        assertFalse(File(game, "Interface/${DependencyAddons.STAGING}").exists())
        assertFalse(backups.exists())
    }
    @Test fun folderNames() {
        for (n in listOf("Bagnon", "DBM-Core", "Bagnon_Config", "My Addon", "!BugGrabber", "Leatrix_Plus"))
            assertTrue(n, ImportedAddons.validFolder(n))
        for (n in listOf("", ".", "..", "...", " ", "a/b", "a\\b", "C:", "a\u0000", "a\n", ".hidden", "trail.", "trail ", " lead", "x".repeat(101)))
            assertFalse(n, ImportedAddons.validFolder(n))
        refused("Bad name") { ImportedAddons.addonDir(addons, "..") }
        assertEquals(File(addons, "Bagnon"), ImportedAddons.addonDir(addons, "Bagnon"))
        // a card made by hand with such a name: refused before anything moves
        val plan = ImportedAddons.plan(zip("Bagnon/Bagnon.toc" to toc()), addons)
        refused("Bad name") { ImportedAddons.installNow(plan.map { it.copy(folder = "..") }, game) }
        assertFalse(File(addons, "Bagnon").exists())
    }
    @Test fun aFakeEndRecordInTheCommentIsSkipped() {
        // a comment holding an end record (no entries) whose comment length does not run to the file's end: the real one is read
        val f = File(tmp, "fake.zip")
        val fake = "PK\u0005\u0006" + "\u0000".repeat(18) + "tail"
        ZipOutputStream(f.outputStream()).use { z ->
            z.setComment(fake)
            for ((n, t) in listOf("A/A.toc" to toc(), "A/a.lua" to "x")) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
        }
        assertEquals(listOf("A/A.toc", "A/a.lua"), ImportedAddons.scan(f).map { it.name })
    }
    @Test fun unpackedFilesMustBeTheScannedOnes() {
        val z = zip("A/A.toc" to toc(), "A/a.lua" to "x")
        val s = ImportedAddons.scan(z)
        java.util.zip.ZipFile(z).use { zf ->
            ImportedAddons.sameFiles(zf, s)
            refused("Not a zip") { ImportedAddons.sameFiles(zf, s.filter { it.name != "A/a.lua" }) }
            refused("Not a zip") { ImportedAddons.sameFiles(zf, s.map { if (it.name == "A/a.lua") it.copy(usize = 99) else it }) }
            refused("Not a zip") { ImportedAddons.sameFiles(zf, s + ImportedAddons.Entry("A/b.lua", 1, 1, 0, 0)) }
        }
    }
    @Test fun aListThatCannotBeSavedIsSaid() {
        // the store's .part is a folder: the swap is done, the line says it is not on the list
        File(tmp, "private").mkdirs(); File(tmp, "private/${ImportedAddons.STORE}.part").mkdirs()
        assertEquals("Installed, but not on the Imported list", ImportedAddons.installNow(ImportedAddons.plan(zip("Q/Q.toc" to toc()), addons), game))
        assertTrue(File(addons, "Q/Q.toc").isFile)
    }

    // ---------------------------------------------------------------- the guards
    @Test fun pathsOut() {
        refused("Bad path") { ImportedAddons.scan(zip("../evil.lua" to "x")) }
        refused("Bad path") { ImportedAddons.scan(zip("A/../../evil.lua" to "x")) }
        refused("Bad path") { ImportedAddons.scan(zip("/abs/A.toc" to "x")) }
        refused("Bad path") { ImportedAddons.scan(zip("C:/Windows/A.toc" to "x")) }
        refused("Bad path") { ImportedAddons.scan(raw(R("A\\..\\..\\evil.lua", "x".toByteArray()))) }
        refused("Bad path") { ImportedAddons.scan(raw(R("\\abs.lua", "x".toByteArray()))) }
        refused("Bad path") { ImportedAddons.scan(raw(R("A/b\u0000.lua", "x".toByteArray()))) }
        // a backslash name that stays inside is made plain
        assertEquals("A/b/c.lua", ImportedAddons.safeName("A\\b\\c.lua"))
        assertEquals("A/b.lua", ImportedAddons.safeName("./A//b.lua"))
    }
    @Test fun aSymlink() {
        refused("Has a link") {
            ImportedAddons.scan(raw(R("A/A.toc", toc().toByteArray()), R("A/link", "/etc/passwd".toByteArray(), attr = (0xA1FFL shl 16))))
        }
        // a plain Unix file mode (0100644) is fine
        assertEquals(1, ImportedAddons.scan(raw(R("A/A.toc", toc().toByteArray(), attr = (0x81A4L shl 16)))).size)
    }
    @Test fun aNameTwice() {
        refused("A name twice") { ImportedAddons.scan(raw(R("A/a.lua", "1".toByteArray()), R("a/A.LUA", "2".toByteArray()))) }
        refused("A name twice") { ImportedAddons.scan(raw(R("A/x", "1".toByteArray()), R("A/x/y.lua", "2".toByteArray()))) }
        // a folder named twice is harmless
        assertEquals(3, ImportedAddons.scan(raw(R("A/"), R("A/"), R("A/a.lua", "1".toByteArray()))).size)
    }
    @Test fun encrypted() {
        refused("Encrypted zip") { ImportedAddons.scan(raw(R("A/A.toc", toc().toByteArray(), flags = 1))) }
    }
    @Test fun tooManyEntries() {
        val es = Array(ImportedAddons.MAX_ENTRIES + 1) { R("A/f$it.lua") }
        refused("Too many files") { ImportedAddons.scan(raw(*es)) }
    }
    @Test fun tooManyBytes() {
        // each squeezed only 150 times (not a ratio bomb), together over the cap
        val big = 300L * 1024 * 1024
        refused("Too big") { ImportedAddons.scan(raw(R("A/a.blp", ByteArray(4), usize = big, csize = big / 150), R("A/b.blp", ByteArray(4), usize = big, csize = big / 150))) }
    }
    @Test fun aRatioBomb() {
        refused("Too big") { ImportedAddons.scan(raw(R("A/A.toc", toc().toByteArray()), R("A/bomb.txt", ByteArray(4), usize = 11L * 1024 * 1024, csize = 1000))) }
        // a small file squeezed well is fine (under the floor)
        assertEquals(2, ImportedAddons.scan(raw(R("A/A.toc", toc().toByteArray()), R("A/spaces.txt", ByteArray(4), usize = 2L * 1024 * 1024, csize = 1000))).size)
    }
    @Test fun anEntryBiggerThanItSays() {
        // the central directory says 10 bytes; the deflated data gives 5000: caught while it unpacks, nothing in AddOns
        val z = raw(R("Liar/Liar.toc", toc().toByteArray(), deflate = true), R("Liar/big.lua", ByteArray(5000) { 'x'.code.toByte() }, usize = 10, deflate = true))
        val plan = ImportedAddons.plan(z, addons)
        refused("Too big") { ImportedAddons.installNow(plan, game) }
        assertFalse(File(addons, "Liar").exists())
        assertFalse("no staging left", File(game, "Interface/${DependencyAddons.STAGING}").exists())
        assertTrue(ImportedAddons.list().isEmpty())
    }
    @Test fun aDamagedEntry() {
        // its CRC in the central directory does not match the data (a stored entry written by hand, then one byte changed)
        val z = raw(R("Dmg/Dmg.toc", toc().toByteArray()), R("Dmg/a.lua", "hello world".toByteArray()))
        val b = z.readBytes(); val i = String(b, Charsets.ISO_8859_1).indexOf("hello world"); b[i] = 'j'.code.toByte(); z.writeBytes(b)
        val plan = ImportedAddons.plan(z, addons)
        try { ImportedAddons.installNow(plan, game); fail("installed a damaged zip") } catch (e: java.io.IOException) { assertTrue(e.message, e.message!!.contains("damaged")) }
        assertFalse(File(addons, "Dmg").exists())
    }
    @Test fun notAZip() {
        refused("Not a zip") { ImportedAddons.scan(File(tmp, "x.zip").apply { writeText("this is not a zip at all, honestly") }) }
    }
    @Test fun thePickIsCapped() {
        val big = object : java.io.InputStream() {
            var left = ImportedAddons.MAX_ZIP + 10
            override fun read(): Int = if (left-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int { if (left <= 0) return -1; val k = minOf(len.toLong(), left).toInt(); left -= k; return k }
        }
        refused("Too big") { ImportedAddons.copyCapped(big, File(tmp, "big.zip")) }
    }

    // ---------------------------------------------------------------- the .toc
    @Test fun tocColourCodesAndLists() {
        val t = ImportedAddons.parseToc("\uFEFF## Interface: 11507, 16001 ,20504\r\n## Title: |cff00ff00Bag|rnon |TInterface\\Icons\\x:16|t\r\n## Version: v2.1.3\r\n## Title-esES: no\r\n")
        assertEquals("Bagnon", t.title)
        assertEquals("v2.1.3", t.version)
        assertEquals(listOf(11507, 16001, 20504), t.iface)
        assertTrue(t.ifaceOk)
    }
    @Test fun tocInterfaceWarning() {
        val t = ImportedAddons.parseToc(toc(iface = "11507"))
        assertFalse(t.ifaceOk)
        val z = zip("Old/Old.toc" to toc(iface = "11507"))
        val f = ImportedAddons.plan(z, addons).single()
        assertFalse("warned", f.ifaceOk)
        assertNull("not refused: a warning only", f.refused)
        assertFalse(ImportedAddons.parseToc("## Title: x\n").ifaceOk)
    }
    @Test fun tocWithoutVersionOrTitle() {
        val t = ImportedAddons.parseToc("## Interface: 16001\n")
        assertNull(t.version); assertNull(t.title)
        val f = ImportedAddons.plan(zip("Bare/Bare.toc" to "## Interface: 16001\n"), null).single()
        assertEquals("the folder's name", "Bare", f.title)
        assertNull(f.version)
    }

    // ---------------------------------------------------------------- names never touched
    @Test fun namesProtected() {
        for (n in listOf("WowCompanion", "wowcompanionhub", "WOWCOMPANIONSPELLS", "WowCompanionAnything", "Polestar", "Polestar_Nav", "QuestieDB",
                "questiedb", "Spoken", "SpokenQuestsAudioHorde", "Manners", "QuestTrail", "Blizzard_AuctionUI", DependencyAddons.BACKUP, DependencyAddons.STAGING))
            assertTrue(n, ImportedAddons.protectedName(n))
        for (n in listOf("Bagnon", "Details", "Questie", "WeakAuras", "Companion")) assertFalse(n, ImportedAddons.protectedName(n))
        // the real manifest's folders
        val m = listOf(File("../companion/app/src/main/assets/companion/manifest.txt"), File("companion/app/src/main/assets/companion/manifest.txt"))
            .first { it.canRead() }.readText()
        val own = ImportedAddons.manifestFolders(m)
        assertEquals(11, own.size)
        assertTrue(own.all { it.startsWith("WowCompanion") })
    }
    @Test fun aProtectedFolderIsRefusedAndNeverTouched() {
        File(addons, "WowCompanion").mkdirs(); File(addons, "WowCompanion/WowCompanion.toc").writeText("## Version: ours\n")
        val z = zip("WowCompanion/WowCompanion.toc" to toc(), "Bagnon/Bagnon.toc" to toc())
        val plan = ImportedAddons.plan(z, addons)
        assertEquals("Kept: DS Forever's own", plan.first { it.folder == "WowCompanion" }.refused)
        ImportedAddons.installNow(plan, game)
        assertEquals("## Version: ours\n", File(addons, "WowCompanion/WowCompanion.toc").readText())
        assertTrue(File(addons, "Bagnon/Bagnon.toc").isFile)
        assertEquals(listOf("Bagnon"), ImportedAddons.list().map { it.folder })
        // a card made by hand that takes it anyway: refused
        refused("Kept: DS Forever's own") { ImportedAddons.installNow(plan.map { it.copy(refused = null) }, game) }
        refused("Nothing to install") { ImportedAddons.installNow(ImportedAddons.plan(zip("QuestieDB/QuestieDB.toc" to toc()), addons), game) }
    }
    @Test fun twiceInOnePick() {
        val a = ImportedAddons.plan(zip("X/X.toc" to toc()), null); val b = ImportedAddons.plan(zip("x/x.toc" to toc()), null)
        val m = ImportedAddons.merge(listOf(a, b))
        assertNull(m[0].refused); assertEquals("Twice in this pick", m[1].refused)
    }

    // ---------------------------------------------------------------- the swap
    @Test fun aNewFolderGoesIn() {
        val z = zip("Wrap/Bagnon/Bagnon.toc" to toc("Bagnon", "2.1"), "Wrap/Bagnon/core/a.lua" to "a=1", "Wrap/Bagnon/b.lua" to "b=1")
        val plan = ImportedAddons.plan(z, addons)
        assertFalse(plan.single().replaces)
        assertEquals("Installed", ImportedAddons.installNow(plan, game))
        assertEquals("a=1", File(addons, "Bagnon/core/a.lua").readText())
        assertTrue(File(addons, "Bagnon/Bagnon.toc").isFile)
        assertFalse("no wrapper", File(addons, "Wrap").exists())
        assertEquals(listOf(ImportedAddons.Imported("Bagnon", "Bagnon", "2.1", plan.single().bytes, ImportedAddons.list()[0].at)), ImportedAddons.list())
        assertFalse("no staging left", File(game, "Interface/${DependencyAddons.STAGING}").exists())
        // the game running: a /reload to load it (never sent)
        ImportedAddons.gameRunning = { true }
        assertEquals("Installed. /reload or restart the game to load them", ImportedAddons.installNow(ImportedAddons.plan(zip("Z/Z.toc" to toc()), addons), game))
    }
    @Test fun aLooseZipGoesInUnderItsName() {
        ImportedAddons.installNow(ImportedAddons.plan(zip("Questie.toc" to toc(), "Modules/a.lua" to "y"), addons), game)
        assertEquals("y", File(addons, "Questie/Modules/a.lua").readText())
    }
    @Test fun replacingKeepsTheOldInTheBackup() {
        File(addons, "Bagnon").mkdirs(); File(addons, "Bagnon/Bagnon.toc").writeText("## Version: 1.0\n"); File(addons, "Bagnon/old.lua").writeText("old")
        val plan = ImportedAddons.plan(zip("Bagnon/Bagnon.toc" to toc(ver = "2.0"), "Bagnon/new.lua" to "new"), addons)
        assertTrue("the card says it replaces", plan.single().replaces)
        ImportedAddons.installNow(plan, game)
        assertTrue(File(addons, "Bagnon/new.lua").isFile); assertFalse(File(addons, "Bagnon/old.lua").exists())
        val b = backups.listFiles()!!.single()
        assertTrue(b.name, b.name.startsWith("Bagnon-1.0-"))
        assertEquals("old", File(b, "old.lua").readText())
    }
    @Test fun noRoomTouchesNothing() {
        File(addons, "Bagnon").mkdirs(); File(addons, "Bagnon/Bagnon.toc").writeText("## Version: 1.0\n")
        ImportedAddons.freeSpace = { 1000L }
        val plan = ImportedAddons.plan(zip("Bagnon/Bagnon.toc" to toc(ver = "2.0")), addons)
        try { ImportedAddons.installNow(plan, game); fail() } catch (r: ImportedAddons.Refused) { assertTrue(r.message, r.message!!.startsWith("Needs ")) }
        assertEquals("## Version: 1.0\n", File(addons, "Bagnon/Bagnon.toc").readText())
        assertFalse(backups.exists()); assertFalse(File(game, "Interface/${DependencyAddons.STAGING}").exists())
        assertTrue(ImportedAddons.list().isEmpty())
    }
    @Test fun aMoveFailingOnTheSecondFolderPutsEverythingBack() {
        // two old folders there; the suite's second folder fails part way in: the first new one goes, both old copies come back whole
        for (n in listOf("SuiteA", "SuiteB")) { File(addons, n).mkdirs(); File(addons, "$n/$n.toc").writeText("## Version: old\n"); File(addons, "$n/o.lua").writeText("old $n") }
        val z = zip("SuiteA/SuiteA.toc" to toc(ver = "new"), "SuiteA/n.lua" to "new", "SuiteB/SuiteB.toc" to toc(ver = "new"), "SuiteB/n.lua" to "new",
            "SuiteB/m.lua" to "new")
        val plan = ImportedAddons.plan(z, addons)
        ThirdPartyAddons.renameDir = { _, _ -> "refused" }                // file by file, so one file can fail
        var k = 0
        ThirdPartyAddons.fileRename = { s, d ->
            if (s.path.contains("import${sep}SuiteB${sep}") && ++k == 2) throw java.io.IOException("refused") else s.renameTo(d)
        }
        try { ImportedAddons.installNow(plan, game); fail() } catch (e: java.io.IOException) { assertTrue(e.message, e.message!!.contains("SuiteB could not be moved in")) }
        for (n in listOf("SuiteA", "SuiteB")) {
            assertEquals("## Version: old\n", File(addons, "$n/$n.toc").readText())
            assertEquals("old $n", File(addons, "$n/o.lua").readText())
            assertFalse("nothing new left in $n", File(addons, "$n/n.lua").exists())
        }
        assertTrue("the list written only on success", ImportedAddons.list().isEmpty())
        assertFalse("no staging left", File(game, "Interface/${DependencyAddons.STAGING}").exists())
    }
    @Test fun aBackupFailingLeavesTheOldWhole() {
        File(addons, "Bagnon").mkdirs(); File(addons, "Bagnon/Bagnon.toc").writeText("## Version: 1.0\n"); File(addons, "Bagnon/o.lua").writeText("old")
        val plan = ImportedAddons.plan(zip("Bagnon/Bagnon.toc" to toc(ver = "2.0")), addons)
        ThirdPartyAddons.renameDir = { _, _ -> "refused" }
        var k = 0
        ThirdPartyAddons.fileRename = { s, d -> if (s.path.contains("AddOns${sep}Bagnon${sep}") && ++k == 2) throw java.io.IOException("refused") else s.renameTo(d) }
        try { ImportedAddons.installNow(plan, game); fail() } catch (e: java.io.IOException) { assertTrue(e.message, e.message!!.contains("backup")) }
        assertEquals("## Version: 1.0\n", File(addons, "Bagnon/Bagnon.toc").readText())
        assertEquals("old", File(addons, "Bagnon/o.lua").readText())
        assertTrue(ImportedAddons.list().isEmpty())
    }
    @Test fun removeOnlyWhatWasImported() {
        File(addons, "Mine").mkdirs(); File(addons, "Mine/Mine.toc").writeText("## Version: 1\n")
        refused("Not imported here") { ImportedAddons.removeNow("Mine", game) }
        assertTrue(File(addons, "Mine/Mine.toc").isFile)
        refused("Not imported here") { ImportedAddons.removeNow("WowCompanion", game) }
        ImportedAddons.installNow(ImportedAddons.plan(zip("Bagnon/Bagnon.toc" to toc(ver = "2.0")), addons), game)
        assertEquals("Moved to the backup", ImportedAddons.removeNow("Bagnon", game))
        assertFalse(File(addons, "Bagnon").exists())
        assertTrue("never deleted: in the backup", backups.listFiles()!!.any { it.name.startsWith("Bagnon-2.0-") && File(it, "Bagnon.toc").isFile })
        assertTrue(ImportedAddons.list().isEmpty())
        // the store survives a restart of the app (its own file), and only names what it imported
        ImportedAddons.installNow(ImportedAddons.plan(zip("Det/Det.toc" to toc("Details")), addons), game)
        ImportedAddons.start(File(tmp, "private"), emptySet())
        assertEquals(listOf("Det"), ImportedAddons.list().map { it.folder })
        assertNotNull(ImportedAddons.list().single().version)
    }

    // ---------------------------------------------------------------- review, 2026-11-16
    @Test fun aVersionWithSlashesStaysInTheBackup() {
        // step 1: an Evil whose Version makes a folder in the backup; step 2: a new Evil whose Version climbs into WowCompanion
        File(addons, "WowCompanion").mkdirs(); File(addons, "WowCompanion/WowCompanion.toc").writeText("## Version: 9\n")
        ImportedAddons.installNow(ImportedAddons.plan(zip("Evil/Evil.toc" to toc(ver = "x/y")), addons), game)
        ImportedAddons.installNow(ImportedAddons.plan(zip("Evil/Evil.toc" to toc(ver = "x/../../AddOns/WowCompanion/z"), "Evil/p.lua" to "p"), addons), game)
        assertEquals("Moved to the backup", ImportedAddons.removeNow("Evil", game))
        assertEquals("WowCompanion untouched", listOf("WowCompanion.toc"), File(addons, "WowCompanion").list()!!.toList())
        val names = backups.list()!!.toList()
        assertEquals(names.toString(), 2, names.size)
        assertTrue(names.toString(), names.all { it.startsWith("Evil-x_") && !it.contains("..") })
        assertTrue("every backup straight in the backup folder", backups.listFiles()!!.all { File(it, "Evil.toc").isFile })
        assertTrue(backups.listFiles()!!.any { File(it, "p.lua").isFile })
    }
    @Test fun backupWords() {
        assertEquals("2.1.0", ThirdPartyAddons.backupWord("2.1.0"))
        assertEquals("v1-beta_2", ThirdPartyAddons.backupWord("v1-beta_2"))
        assertEquals("unknown", ThirdPartyAddons.backupWord(null))
        assertEquals("_", ThirdPartyAddons.backupWord(".."))
        assertEquals("x_", ThirdPartyAddons.backupWord("x/"))
        for (v in listOf("x/../../a", "..\\..\\a", "/abs", "C:\\w", "a\u0000b", "\u00e9t\u00e9")) {
            val w = ThirdPartyAddons.backupWord(v)
            assertTrue("$v -> $w", w.matches(Regex("[A-Za-z0-9._-]+")) && !w.contains("..") && !w.startsWith("."))
        }
        assertEquals(40, ThirdPartyAddons.backupWord("a".repeat(200)).length)
    }
    @Test fun aFolderThatAppearedAfterTheCardAsksAgain() {
        // the card read with no game folder: nothing marked; the folder is there at Install: no replace, the card asks again
        val plan = ImportedAddons.plan(zip("Bagnon/Bagnon.toc" to toc(ver = "2.0"), "Bagnon/n.lua" to "new"), null)
        assertFalse(plan.single().replaces)
        File(addons, "Bagnon").mkdirs(); File(addons, "Bagnon/Bagnon.toc").writeText("## Version: 1.0\n")
        try { ImportedAddons.installNow(plan, game); fail() } catch (e: ImportedAddons.NeedsConfirm) {
            assertEquals(setOf("Bagnon"), e.folders); assertEquals("Replaces yours: tap Install again", e.message)
        }
        assertEquals("## Version: 1.0\n", File(addons, "Bagnon/Bagnon.toc").readText())
        assertFalse(File(addons, "Bagnon/n.lua").exists()); assertFalse(backups.exists())
        assertFalse(File(game, "Interface/${DependencyAddons.STAGING}").exists())
        // marked (the card's second tap): it goes in, the old to the backup
        assertEquals("Installed", ImportedAddons.installNow(plan.map { it.copy(replaces = true) }, game))
        assertTrue(File(addons, "Bagnon/n.lua").isFile); assertTrue(backups.listFiles()!!.single().name.startsWith("Bagnon-1.0-"))
    }
    @Test fun deleteTreeLeavesWhatALinkPointsAt() {
        val keep = File(tmp, "keep").apply { mkdirs() }; File(keep, "k.txt").writeText("k")
        val d = File(tmp, "staging/import").apply { mkdirs() }; File(d, "a.txt").writeText("a")
        val link = File(d, "link")
        val made = runCatching { Files.createSymbolicLink(link.toPath(), keep.toPath()) }.isSuccess
        org.junit.Assume.assumeTrue("no symlinks here (Windows without the right)", made)
        assertTrue(ThirdPartyAddons.deleteTree(File(tmp, "staging")))
        assertFalse(File(tmp, "staging").exists())
        assertEquals("k", File(keep, "k.txt").readText())
    }
}
