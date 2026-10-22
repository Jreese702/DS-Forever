package com.jrees.wowcompanion

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The launch switch, off-device (2026-10-21: the beta closed): when it is due, the .build.info row it writes, Config.wtf for _forever_,
 * the paths that follow the client, the settings carry-over, and a 1.0.9 player's folder. Rows shaped like the Thor's real .build.info
 * (the beta's 84bea423... row); no game data.
 */
class WowSwitchTest {
    private val head = "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|" +
        "CDN Hosts!STRING:0|CDN Servers!STRING:0|Tags!STRING:0|Armadillo!STRING:0|Last Activated!STRING:0|Version!STRING:0|" +
        "KeyRing!HEX:16|Product!STRING:0"
    private val betaRow = "us|1|84bea4233331e390fda8724c6f91ffa5|6bff2e685995b214c2ff5d08f3344542|1111|17000|tpr/wow|" +
        "level3.blizzard.com us.cdn.blizzard.com|http://level3.blizzard.com/?maxhosts=4|Windows arm64 US? enUS speech?:Windows arm64 US? enUS text?||" +
        "2026-10-20T10:00:00Z|1.60.1.70450||wow_classic_beta"
    private val betaOnly = "$head\n$betaRow\n"
    private val remote = WowSwitch.Remote("1.60.1.70532", "2582289d81e380163c7a13472df4cb81", "6bff2e685995b214c2ff5d08f3344542",
        listOf("level3.blizzard.com", "us.cdn.blizzard.com"), "tpr/wow", "http://us.cdn.blizzard.com/?maxhosts=4", null)

    private val closed = WowProduct.LAUNCH_DAY
    private val before = WowProduct.LAUNCH_DAY.minusDays(1)

    private lateinit var root: File
    @Before fun setUp() { root = Files.createTempDirectory("dsf-switch").toFile() }
    @After fun tearDown() { root.deleteRecursively() }

    private fun folder(text: String, vararg exes: WowProduct) {
        File(root, ".build.info").writeText(text)
        for (p in exes) File(root, "${p.flavorDir}/${p.exeName}").apply { parentFile.mkdirs(); writeText("exe") }
    }

    // ---------------------------------------------------------------- the trigger

    @Test fun betaOnlyFolderIsDueFromTheBetasLastDay() {
        folder(betaOnly, WowProduct.BETA)
        assertTrue(WowSwitch.due(root, closed))
        assertTrue(WowSwitch.due(root, closed.plusDays(14)))
        assertFalse(WowSwitch.due(root, before))                       // before the date: no switch
        assertEquals(WowProduct.BETA, WowProduct.of(root, closed))    // the folder itself still says beta until the move is done
    }

    @Test fun pcCopyWithWowfInstalledRunsWowfAndIsNotDue() {
        // the beta plus wowf installed by the Battle.net app on a PC (a wowf row, _forever_ with its exe)
        folder(WowSwitch.withLaunchRow(betaOnly, remote), WowProduct.BETA, WowProduct.LAUNCH)
        assertEquals(WowProduct.LAUNCH, WowProduct.of(root, closed))
        assertFalse(WowSwitch.due(root, closed))
        assertEquals("1.60.1.70532", WowProduct.choose(root, closed)!!.row["Version"])
    }

    @Test fun wowfRowWithoutItsExeIsAMoveStillUnderWay() {
        // a killed move: the row is written, the exe not yet. The beta still runs and the move is still due (it resumes)
        folder(WowSwitch.withLaunchRow(betaOnly, remote), WowProduct.BETA)
        assertEquals(WowProduct.BETA, WowProduct.of(root, closed))
        assertTrue(WowSwitch.due(root, closed))
    }

    @Test fun freshInstallExpectsTheLaunchClient() {
        assertNull(WowProduct.choose(root, closed))                   // no folder content at all
        assertEquals(WowProduct.LAUNCH, WowProduct.expected(closed))
        assertEquals(WowProduct.LAUNCH, WowProduct.of(root, closed))
        assertEquals(WowProduct.BETA, WowProduct.expected(before))
        assertFalse(WowSwitch.due(root, closed))
    }

    @Test fun otherGamesAreNeverDue() {
        folder("$head\nus|1|bk|ck||0|tpr/wow|h|||||12.0.5.1||wow\n")
        assertFalse(WowSwitch.due(root, closed))
    }

    // ---------------------------------------------------------------- the .build.info row

    @Test fun launchRowIsAddedAndTheBetaRowKeptByteForByte() {
        val out = WowSwitch.withLaunchRow(betaOnly, remote)
        val lines = out.split("\n")
        assertEquals(head, lines[0])
        assertEquals(betaRow, lines[1])
        assertTrue(out.endsWith("\n"))
        val rows = WowProduct.parse(out)
        assertEquals(2, rows.size)
        val w = rows[1]
        assertEquals("wowf", w["Product"])
        assertEquals("1", w["Active"])
        assertEquals("us", w["Branch"])
        assertEquals("2582289d81e380163c7a13472df4cb81", w["Build Key"])
        assertEquals("6bff2e685995b214c2ff5d08f3344542", w["CDN Key"])
        assertEquals("", w["Install Key"])
        assertEquals("1.60.1.70532", w["Version"])
        assertEquals("level3.blizzard.com us.cdn.blizzard.com", w["CDN Hosts"])
        assertEquals("http://us.cdn.blizzard.com/?maxhosts=4", w["CDN Servers"])
        assertEquals(rows[0]["Tags"], w["Tags"])                       // the beta's locale / platform tags
        assertEquals(head.split('|').size, out.lines()[2].split('|').size)
    }

    @Test fun launchRowRoundTripReplacesItselfAndKeepsCrlf() {
        val once = WowSwitch.withLaunchRow(betaOnly, remote)
        assertEquals(once, WowSwitch.withLaunchRow(once, remote))      // a resumed move writes the same file
        val newer = WowSwitch.withLaunchRow(once, remote.copy(version = "1.60.1.70600", buildConfig = "ffff"))
        assertEquals(2, WowProduct.parse(newer).size)
        assertEquals("1.60.1.70600", WowProduct.parse(newer)[1]["Version"])
        assertEquals(betaRow, newer.lines()[1])
        val crlf = betaOnly.replace("\n", "\r\n")
        val out = WowSwitch.withLaunchRow(crlf, remote)
        assertTrue(out.split("\r\n")[1] == betaRow && out.endsWith("\r\n") && !out.replace("\r\n", "").contains("\n"))
        // two wowf rows (a hand-edited file): one is left
        val two = once.trimEnd('\n') + "\n" + once.lines()[2] + "\n"
        assertEquals(2, WowProduct.parse(WowSwitch.withLaunchRow(two, remote)).size)
    }

    @Test fun replaceWritesWholeFilesOverALeftTemp() {
        val f = File(root, ".build.info")
        File(root, ".build.info.tmp").writeText("half")                 // a kill during the last write
        WowSwitch.replace(f, "one".toByteArray())
        assertEquals("one", f.readText())
        WowSwitch.replace(f, "two".toByteArray())
        assertEquals("two", f.readText())
        assertFalse(File(root, ".build.info.tmp").exists())
    }

    @Test fun statusLineSaysTheMoveWithProgress() {
        assertTrue(WowSwitch.line("Downloading Wow-arm64.exe (2/2)...").startsWith("Moving to World of Warcraft: Forever… 52% · Downloading"))
        assertTrue(WowSwitch.line("Fetching build 1.60.1.70532 manifest...").contains("8%"))
        assertEquals("Moving to World of Warcraft: Forever… whatever", WowSwitch.line("whatever"))
    }

    // ---------------------------------------------------------------- Config.wtf for _forever_

    @Test fun configWtfForTheLaunchClient() {
        val row = WowProduct.parse(WowSwitch.withLaunchRow(betaOnly, remote))[1]
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, row)
        val cfg = File(root, "_forever_/WTF/Config.wtf").readText()
        assertTrue(cfg.contains("SET portal \"us\"\n"))
        assertTrue(cfg.contains("SET agentUID \"wowf\"\n"))
        assertFalse(cfg.contains("accountName"))
        assertEquals("Product Flavor!STRING:0\nwowf\n", File(root, "_forever_/.flavor.info").readText())
        // a _forever_ Config.wtf copied from the beta's: its portal and agentUID are put right, the player's own lines stay
        File(root, "_forever_/WTF/Config.wtf").writeText("SET portal \"test\"\nSET agentUID \"wow_classic_beta\"\nSET accountName \"X\"\n")
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, row.plus("Branch" to "eu"))
        val fixed = File(root, "_forever_/WTF/Config.wtf").readText()
        assertTrue(fixed.contains("SET portal \"eu\"\n") && fixed.contains("SET agentUID \"wowf\"\n") && fixed.contains("SET accountName \"X\"\n"))
        // the beta's own stays "test"
        GameConfigWtf.ensure(root, WowProduct.BETA, null)
        assertTrue(File(root, "_classic_beta_/WTF/Config.wtf").readText().contains("SET portal \"test\"\n"))
    }

    // ---------------------------------------------------------------- the paths that follow the client

    @Test fun pathsMoveToForeverWhenWowfRuns() {
        folder(WowSwitch.withLaunchRow(betaOnly, remote), WowProduct.BETA)
        assertEquals("${root.path}/_classic_beta_/Logs/General.log", GamePaths.log(root.path))     // mid-move: still the beta
        File(root, "_forever_/Wow-arm64.exe").apply { parentFile.mkdirs() }.writeText("exe")                                      // the move's last file
        // "Beta until launch": both installed before launch day - still the beta (the Thor test55 switched goes back by itself)
        if (java.time.LocalDate.now().isBefore(WowProduct.LAUNCH_DAY)) {
            assertEquals("${root.path}/_classic_beta_/Logs/General.log", GamePaths.log(root.path))
            File(root, "_classic_beta_/WowB-ARM64.exe").delete()                                    // a wowf-only folder runs wowf any day
        }
        assertEquals("${root.path}/_forever_/Logs/General.log", GamePaths.log(root.path))
        assertEquals(File(root, "_forever_"), WowProduct.flavorDir(root))                          // WTF, AddOns, login.txt live here
        assertEquals(File(root, "_forever_/Interface/AddOns"), File(WowProduct.flavorDir(root), "Interface/AddOns"))
        assertEquals("_forever_", WowProduct.of(root).flavorDir)                                    // NativeLogin.prepare's flavor
        assertEquals("wowf" to "2582289d81e380163c7a13472df4cb81", GameFiles.activeBuild(root))     // the net stamp's build key
    }

    // ---------------------------------------------------------------- the settings carry-over

    @Test fun settingsCarryOverOnceNeverOverNewer() {
        val beta = File(root, "_classic_beta_/WTF")
        val sv = File(beta, "Account/ACCT1/SavedVariables").apply { mkdirs() }
        File(sv, "WowCompanion.lua").apply { writeText("WowCompanionDB = {a=1}"); setLastModified(1_000_000_000_000) }
        File(sv, "WowCompanionSpells.lua").apply { writeText("WowCompanionSpellsDB = {b=1}"); setLastModified(1_000_000_000_000) }
        File(sv, "Questie.lua").writeText("not ours")
        File(beta, "Account/ACCT1/bindings-cache.wtf").writeText("the game's own")
        val fwd = File(root, "_forever_/WTF")
        val newer = File(fwd, "Account/ACCT1/SavedVariables/WowCompanionSpells.lua").apply {
            parentFile.mkdirs(); writeText("newer"); setLastModified(1_100_000_000_000)
        }
        val log = mutableListOf<String>()
        SvKeep.carryOver(beta, fwd) { log += it }
        assertEquals("WowCompanionDB = {a=1}", File(fwd, "Account/ACCT1/SavedVariables/WowCompanion.lua").readText())
        assertEquals(1_000_000_000_000, File(fwd, "Account/ACCT1/SavedVariables/WowCompanion.lua").lastModified())
        assertEquals("newer", newer.readText())                                                  // never over a newer one
        assertFalse(File(fwd, "Account/ACCT1/SavedVariables/Questie.lua").exists())              // only ours
        assertFalse(File(fwd, "Account/ACCT1/bindings-cache.wtf").exists())
        assertTrue(File(fwd, SvKeep.CARRIED).isFile)
        assertEquals(File(fwd, "Account/ACCT1/SavedVariables"), SvKeep.savedVariablesDir(fwd))   // beforeStart now has its folder
        // once: a later beta change is not copied again
        File(sv, "WowCompanion.lua").apply { writeText("WowCompanionDB = {a=2}"); setLastModified(1_200_000_000_000) }
        SvKeep.carryOver(beta, fwd) { log += it }
        assertEquals("WowCompanionDB = {a=1}", File(fwd, "Account/ACCT1/SavedVariables/WowCompanion.lua").readText())
        assertEquals("WowCompanionDB = {a=2}", File(sv, "WowCompanion.lua").readText())          // the beta's untouched
    }

    // ---------------------------------------------------------------- a 1.0.9 player's folder, first run of the new app

    @Test fun upgradeFrom109KeepsEverythingOfTheirs() {
        // what 1.0.9 left: the beta-only .build.info, the beta client, its WTF (Config.wtf on "test", our settings), our add-ons
        folder(betaOnly, WowProduct.BETA)
        val betaCfg = File(root, "_classic_beta_/WTF/Config.wtf").apply { parentFile.mkdirs(); writeText("SET portal \"test\"\nSET agentUID \"wow_classic_beta\"\n") }
        val betaSv = File(root, "_classic_beta_/WTF/Account/ACCT1/SavedVariables/WowCompanion.lua").apply { parentFile.mkdirs(); writeText("WowCompanionDB = {x=1}") }
        val betaAddon = File(root, "_classic_beta_/Interface/AddOns/WowCompanion/WowCompanion.toc").apply { parentFile.mkdirs(); writeText("## Interface: 16001") }
        val kept = File(root, "Documents/DS Forever/settings/WowCompanionDB.json").apply { parentFile.mkdirs(); writeText("{}") }
        val snapshot = listOf(betaCfg, betaSv, betaAddon, kept, File(root, "_classic_beta_/WowB-ARM64.exe")).associateWith { it.readText() }
        assertTrue(WowSwitch.due(root, closed))
        // the move (its file steps: the row, then the client with the exe last), then Play's steps for the launch client
        WowSwitch.replace(File(root, ".build.info"), WowSwitch.withLaunchRow(File(root, ".build.info").readText(), remote).toByteArray())
        File(root, "_forever_/Wow-arm64.exe").apply { parentFile.mkdirs() }.writeText("exe")
        val choice = WowProduct.choose(root, closed)!!
        assertEquals(WowProduct.LAUNCH, choice.product)
        GameConfigWtf.ensure(root, choice.product!!, choice.row)
        SvKeep.carryOver(File(root, "_classic_beta_/WTF"), File(root, "_forever_/WTF")) {}
        assertEquals(betaRow, File(root, ".build.info").readText().lines()[1])
        snapshot.forEach { (f, text) -> assertEquals(f.path, text, f.readText()) }                 // nothing of theirs changed
        assertTrue(File(root, "_forever_/WTF/Config.wtf").readText().contains("SET portal \"us\""))
        assertEquals("WowCompanionDB = {x=1}", File(root, "_forever_/WTF/Account/ACCT1/SavedVariables/WowCompanion.lua").readText())
        assertFalse(WowSwitch.due(root, closed))
    }
}
