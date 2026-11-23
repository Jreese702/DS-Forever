package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE OPT-IN TEST GRAPHICS DRIVER (the user, 2026-11-23: "Is there any new drivers that we can leverage?", the pick "Both
 * (Recommended)"), off-device: Recommended is today's driver exactly, a test driver only where it applies and only when its zip is in
 * the APK, the launch's pick and its fallbacks, which driver the game's memory maps show, the report line, the Settings row and its
 * restart, Reset settings, the words in es / fr, and that the zip, its pin, its notices and the release check agree.
 */
class GraphicsDriverTest {
    private val root = listOf(File(".."), File(".")).first { File(it, "tools/build_release.py").isFile }
    private fun read(path: String) = File(root, path).readText().replace("\r\n", "\n")
    private val TEST = GraphicsDriver.Choice.TEST_MESA
    private val REC = GraphicsDriver.Choice.RECOMMENDED
    private val ASSETS = setOf("proton-11.0-90624-arm64ec.wcp", "turnip-wow-scheduler-test.zip", "turnip-test-mesa-26.3.1.zip")

    @After fun forget() = GraphicsDriver.forgetForTest()

    @Test fun recommendedIsTheDefaultAndAnythingElseFallsBackToIt() {
        assertEquals(REC, GraphicsDriver.DEFAULT)
        assertEquals(REC, GraphicsDriver.sanitize(null))
        assertEquals(REC, GraphicsDriver.sanitize("banners-27.0"))
        assertEquals(TEST, GraphicsDriver.sanitize("test-mesa-26.3.1"))
        assertEquals(listOf("Recommended", "Test: Mesa 26.3.1"), GraphicsDriver.Choice.values().map { it.label })
        // Settings' getter: nothing saved = Recommended
        assertTrue("GraphicsDriver.sanitize(prefs?.getString(GraphicsDriver.KEY, null))" in read("companion/app/src/main/java/com/jrees/wowcompanion/Settings.kt"))
    }

    @Test fun onlyDriversThisApkCarriesAreOffered() {
        assertEquals(listOf(REC, TEST), GraphicsDriver.offered(ASSETS))
        assertEquals("no test zip in the APK: Recommended only", listOf(REC), GraphicsDriver.offered(ASSETS - "turnip-test-mesa-26.3.1.zip"))
        assertTrue(GraphicsDriver.rowShown(true, ASSETS))
        assertFalse("Adreno 8xx keeps its own driver: no row", GraphicsDriver.rowShown(false, ASSETS))
        assertFalse("nothing to choose: no row", GraphicsDriver.rowShown(true, setOf("turnip-wow-scheduler-test.zip")))
        assertFalse("the standalone app (the launcher never said): no row", GraphicsDriver.rowShown(false, emptySet()))
        assertEquals(1, GraphicsDriver.segment(TEST, listOf(REC, TEST)))
        assertEquals("a choice not listed lights Recommended", 0, GraphicsDriver.segment(TEST, listOf(REC)))
    }

    @Test fun theLaunchPickKeepsTheRecommendedDriverUnlessATestOneIsReady() {
        val rec = "Turnip-WoW-scheduler-test"
        assertEquals(GraphicsDriver.Pick(rec, ""), GraphicsDriver.pick(REC, rec, takesTests = true, installed = false))
        assertEquals(GraphicsDriver.Pick("Turnip-V32-RP6sched", ""), GraphicsDriver.pick(REC, "Turnip-V32-RP6sched", false, false))
        assertEquals(GraphicsDriver.Pick("Turnip-Test-Mesa-26.3.1", ""), GraphicsDriver.pick(TEST, rec, true, true))
        assertEquals(rec, GraphicsDriver.pick(TEST, rec, true, installed = false).driver)
        assertTrue(GraphicsDriver.pick(TEST, rec, true, false).note.contains("did not unpack"))
        val on8xx = GraphicsDriver.pick(TEST, "Turnip-V32-RP6sched", takesTests = false, installed = false)
        assertEquals("Turnip-V32-RP6sched", on8xx.driver)
        assertTrue(on8xx.note.contains("not used on this GPU"))
    }

    @Test fun theLauncherLeavesRecommendedExactlyAsBefore() {
        val s = read("app/src/main/java/app/gamenative/ui/screen/wow/WoWForeverScreen.kt")
        // today's drivers and their unpack are unchanged
        assertTrue("private const val DEFAULT_DRIVER = \"Turnip-WoW-scheduler-test\"" in s)
        assertTrue("installDriver(context, DEFAULT_DRIVER, \"turnip-wow-scheduler-test.zip\", onStatus)" in s)
        assertTrue("installDriver(context, ADRENO_8XX_DRIVER, \"turnip-V32-RP6sched.zip\", onStatus)" in s)
        assertTrue("GpuProfile(DEFAULT_DRIVER, \"noconform\", \"1920x1080\", \"Adreno 740\"" in s)
        // the choice: the profile itself when the pick is its own driver; the test unpack only for a test choice on a profile that takes it
        val with = s.substringAfter("private fun withDriverChoice(").substringBefore("\n}\n")
        assertTrue("(if (pick.driver == profile.driver) profile else profile.copy(driver = pick.driver))" in with)
        assertTrue("choice.folder != null && takes && choice.asset in G.assets && installTestDriver(" in with)
        assertTrue("val takes = profile.driver == DEFAULT_DRIVER" in with)
        // the request is logged at every start and the watch follows the container's set-up
        val prep = s.substringAfter("private fun prepareLaunch(").substringBefore("\n}\n")
        assertTrue(prep.indexOf("withDriverChoice(context, profile, onStatus)") < prep.indexOf("containerConfig(wineRoot, gpu,"))
        assertTrue("GraphicsDriver.watch(driverLaunch" in prep)
        // a test driver unpacks into .part first, renamed only after its meta.json and library check out
        val inst = s.substringAfter("private fun installTestDriver(").substringBefore("\n}\n")
        assertTrue("File(root, \"\$folder.part\")" in inst && "ready(part) && part.renameTo(dir)" in inst)
    }

    @Test fun theMemoryMapsSayWhichDriverLoaded() {
        val app = "/data/user/0/app.dsforever.companion/files"
        val custom = sequenceOf(
            "7a00000000-7a00100000 r-xp 00000000 fd:30 123 /system/lib64/libvulkan.so",
            "7b00000000-7b00e00000 r--p 00000000 fd:30 456 /vendor/lib64/hw/vulkan.adreno.so",
            "7c00000000-7c00e00000 r-xp 00000000 fd:30 789 $app/contents/adrenotools/Turnip-Test-Mesa-26.3.1/libvulkan_freedreno.so")
        assertEquals("an adrenotools driver wins over the system one", "Turnip-Test-Mesa-26.3.1", GraphicsDriver.driverInMaps(custom))
        assertEquals("Turnip-WoW-scheduler-test", GraphicsDriver.driverInMaps(sequenceOf(
            "1-2 r-xp 0 0:0 1 $app/contents/adrenotools/Turnip-WoW-scheduler-test/libvulkan_freedreno.so (deleted)")))
        assertEquals("the wrapper fell back to the phone's driver", GraphicsDriver.SYSTEM, GraphicsDriver.driverInMaps(sequenceOf(
            "1-2 r-xp 0 0:0 1 /vendor/lib64/hw/vulkan.adreno.so", "3-4 r--p 0 0:0 2 [anon:scudo]")))
        assertNull("no Vulkan driver mapped yet", GraphicsDriver.driverInMaps(sequenceOf("1-2 r-xp 0 0:0 1 /system/lib64/libc.so", "3-4 rw-p 0 0 0")))
        assertNull("not a library", GraphicsDriver.driverInMaps(sequenceOf("1-2 r--p 0 0:0 1 $app/contents/adrenotools/X/meta.json")))
        assertNull("the folder alone, or the unpack's tmp", GraphicsDriver.driverInMaps(sequenceOf(
            "1-2 r--p 0 0:0 1 $app/contents/adrenotools/a.so", "1-2 r--p 0 0:0 1 $app/contents/adrenotools/tmp/libvulkan_freedreno.so")))
        // a process label: argv[0]'s last part, Unix or Windows, safe characters only
        assertEquals("WowClassic.exe", GraphicsDriver.processLabel("G:\\_forever_\\WowClassic.exe\u0000-d3d11\u0000"))
        assertEquals("wineserver", GraphicsDriver.processLabel("/data/data/x/files/imagefs/usr/bin/wineserver\u0000"))
        assertEquals("?", GraphicsDriver.processLabel(""))
    }

    @Test fun theScanSkipsOurOwnProcessOtherUidsAndProcessesFromBeforeThisLaunch() {
        val proc = kotlin.io.path.createTempDirectory("proc").toFile()
        try {
            fun p(pid: Int, uid: Int, so: String, exe: String) = File(proc, "$pid").apply {
                mkdirs()
                File(this, "status").writeText("Name:\tx\nUid:\t$uid\t$uid\t$uid\t$uid\n")
                File(this, "maps").writeText("1-2 r-xp 0 0:0 1 $so\n")
                File(this, "cmdline").writeText("$exe\u0000")
            }
            val app = "/data/user/0/x/files/contents/adrenotools"
            p(10, 10123, "$app/Turnip-WoW-scheduler-test/libvulkan_freedreno.so", "self")
            p(20, 10123, "$app/Turnip-WoW-scheduler-test/libvulkan_freedreno.so", "G:\\old\\Wow.exe")
            p(30, 10999, "$app/Other/libvulkan_freedreno.so", "other-uid")
            File(proc, "self").mkdirs()
            val before = GraphicsDriver.ourPids(10123, proc)
            assertEquals(setOf(10, 20), before)
            assertEquals("without the snapshot a previous run's game answers", "Turnip-WoW-scheduler-test" to "Wow.exe",
                GraphicsDriver.scan(10, 10123, emptySet(), proc))
            assertNull("only our own, another uid's and a previous run's processes so far", GraphicsDriver.scan(10, 10123, before, proc))
            p(40, 10123, "/vendor/lib64/hw/vulkan.adreno.so", "G:\\new\\Wow.exe")
            assertEquals(GraphicsDriver.SYSTEM to "Wow.exe", GraphicsDriver.scan(10, 10123, before, proc))
            p(50, 10123, "$app/Turnip-Test-Mesa-26.3.1/libvulkan_freedreno.so", "G:\\new\\Wow.exe")
            assertEquals("an adrenotools driver wins over the system one", "Turnip-Test-Mesa-26.3.1" to "Wow.exe",
                GraphicsDriver.scan(10, 10123, before, proc))
        } finally { proc.deleteRecursively() }
        // the report's launch line keeps its "Launch: " words
        assertTrue("\"Launch: \${LauncherHooks.launchState" in read("companion/app/src/main/java/com/jrees/wowcompanion/StartupDiag.kt"))
    }

    @Test fun theDriversOwnMetaGivesItsVersion() {
        val test = read("licenses/turnip-test-mesa-26.3.1/meta.json")
        assertEquals(TEST.version, GraphicsDriver.metaVersion(test))
        assertTrue("its meta's name is its folder (the launcher never unpacks a folder again)", "\"name\": \"${TEST.folder}\"" in test)
        assertTrue("\"libraryName\": \"libvulkan_freedreno.so\"" in test)
        assertEquals("26.1.99-fe067b17d9-wow-test", GraphicsDriver.metaVersion(read("licenses/turnip-scheduler/meta.json")))
        assertNull(GraphicsDriver.metaVersion("{}"))
        assertNull(GraphicsDriver.metaVersion(null))
    }

    @Test fun theReportShowsTheRequestAndWhatLoaded() {
        val at = { _: Long -> "2026-11-23 10:00:00" }
        assertEquals("Graphics driver: Recommended chosen; no game start in this run", GraphicsDriver.reportLine(REC, null, null, null, at))
        assertEquals("Graphics driver: Test: Mesa 26.3.1 chosen; no game start in this run; the last one: X",
            GraphicsDriver.reportLine(TEST, null, null, "X", at))
        val l = GraphicsDriver.markLaunched(TEST, "Turnip-Test-Mesa-26.3.1", "", 1L)
        assertEquals(l, GraphicsDriver.launched)
        assertEquals("Graphics driver: chosen Test: Mesa 26.3.1; requested Turnip-Test-Mesa-26.3.1", GraphicsDriver.requestLine(l))
        assertEquals("Graphics driver: Test: Mesa 26.3.1 chosen; 2026-11-23 10:00:00 requested Turnip-Test-Mesa-26.3.1 (Test: Mesa 26.3.1); " +
            "loaded (still looking)", GraphicsDriver.reportLine(TEST, l, null, null, at))
        val ok = GraphicsDriver.Loaded("Turnip-Test-Mesa-26.3.1", "26.3.1-dsf-icd", "WowClassic.exe", 14_200L, 3)
        assertEquals("Graphics driver loaded: Turnip-Test-Mesa-26.3.1 26.3.1-dsf-icd (in WowClassic.exe, 14 s after Play)",
            GraphicsDriver.loadedLine(l, ok))
        val fell = GraphicsDriver.Loaded(GraphicsDriver.SYSTEM, null, "WowClassic.exe", 9_000L, 2)
        assertTrue(GraphicsDriver.loadedLine(l, fell).endsWith("; NOT the requested Turnip-Test-Mesa-26.3.1"))
        val none = GraphicsDriver.Loaded(null, null, "", 300_000L, 60)
        assertEquals("Graphics driver loaded: none seen yet (60 looks over 300 s after Play)", GraphicsDriver.loadedLine(l, none))
        // chosen back to Recommended while this run's game used the test driver: a restart is due
        assertTrue(GraphicsDriver.reportLine(REC, l, ok, null, at).endsWith("; a restart is due"))
        // a fallback's reason stays in the line
        val back = GraphicsDriver.Launch(TEST, "Turnip-V32-RP6sched", "test drivers are not used on this GPU: its own driver", 1L)
        assertTrue(GraphicsDriver.ranLine(back, null, at).contains("(Test: Mesa 26.3.1), test drivers are not used on this GPU"))
        // the report's GPU and container section carries it; the log tag is one the report's log keeps
        assertTrue("GraphicsDriver.reportLine(Settings.graphicsDriver, GraphicsDriver.launched, GraphicsDriver.loaded" in
            read("companion/app/src/main/java/com/jrees/wowcompanion/StartupDiag.kt"))
        assertTrue(GraphicsDriver.TAG in StartupDiag.LOG_TAGS)
    }

    @Test fun theSettingsRowAndItsRestart() {
        assertEquals(SettingsTabs.Group.GRAPHICS, SettingsTabs.Block.GRAPHICS_DRIVER.group)
        assertEquals(SettingsTabs.Tab.GENERAL, SettingsTabs.Block.GRAPHICS_DRIVER.tab)
        assertEquals(listOf(GraphicsDriver.ROW_ID), SettingsTabs.Block.GRAPHICS_DRIVER.rows)
        val G = SettingsTabs.Group.GRAPHICS
        assertEquals(listOf(GeneralTiles.Row.GRAPHICS_DRIVER), GeneralTiles.rows(G, true, false, driverRestart = false, driverRow = true))
        assertEquals(listOf(GeneralTiles.Row.GRAPHICS_DRIVER, GeneralTiles.Row.WINDOWS_RESTART),
            GeneralTiles.rows(G, true, false, driverRestart = true, driverRow = true))
        assertTrue("not offered: no rows", GeneralTiles.rows(G, true, true, driverRestart = true, driverRow = false).isEmpty())
        assertEquals(GraphicsDriver.ROW_ID, GeneralTiles.Row.GRAPHICS_DRIVER.label)
        // the group sits between Account and Help; Support stays last
        val gs = SettingsTabs.Group.values().filter { it.tab == SettingsTabs.Tab.GENERAL }
        assertEquals(gs.indexOf(SettingsTabs.Group.ACCOUNT) + 1, gs.indexOf(G))
        assertEquals(gs.indexOf(SettingsTabs.Group.HELP) - 1, gs.indexOf(G))
        // the restart: only while the running game started with the other choice
        assertFalse(GraphicsDriver.showRestart(true, false, null, TEST))
        assertTrue(GraphicsDriver.showRestart(true, false, REC, TEST))
        assertFalse(GraphicsDriver.showRestart(true, false, TEST, TEST))
        assertFalse("a restart under way", GraphicsDriver.showRestart(true, true, REC, TEST))
        assertFalse("no game", GraphicsDriver.showRestart(false, false, REC, TEST))
    }

    @Test fun resetSettingsPutsRecommendedBack() {
        val k = ResetPlan.PREFS.first { it.file == "settings" && it.key == GraphicsDriver.KEY }
        assertEquals(ResetPlan.Kind.SETTINGS, k.kind)
        assertNull("the last start's line is never reset", ResetPlan.PREFS.first { it.file == "settings" && it.key == GraphicsDriver.KEY_RAN }.kind)
    }

    @Test fun theWordsTranslate() {
        val dir = File(root, "companion/app/src/main/assets/companion/i18n")
        for (l in listOf("es", "fr")) Tr.load(l, File(dir, "$l.json").readText(Charsets.UTF_8))
        val words = listOf(GraphicsDriver.ROW_ID, SettingsTabs.Group.GRAPHICS.heading, "Installing the test graphics driver...") +
            GraphicsDriver.Choice.values().map { it.label }
        for (l in listOf("es", "fr")) for (w in words) assertTrue("$l: $w", Tr.t(w, l) != w)
    }

    @Test fun theZipItsPinItsNoticesAndTheReleaseCheckAgree() {
        val pin = read("tools/components-test-drivers.sha256").trim()
        assertEquals("3272d6189aa4202898457503236dad88980691fc0347fe56c60888cc73d09a33  ${TEST.asset}", pin)
        // the four originals' manifest stays upstream's
        assertFalse(TEST.asset!! in read("tools/components.sha256"))
        val br = read("tools/build_release.py")
        assertTrue("TEST_DRIVERS = (\"${TEST.asset}\",)" in br)
        assertTrue("\"tools/components-test-drivers.sha256\"" in br)
        assertTrue("checkTestDrivers" in read("app/build.gradle.kts"))
        // notices: the app's copies and the licence folder
        for (f in listOf("RELEASE_NOTICES.md", "app/src/main/assets/notices/RELEASE_NOTICES.md.txt", "THIRD_PARTY_NOTICES",
                "app/src/main/assets/notices/THIRD_PARTY_NOTICES.txt", "SOURCE_PROVENANCE.md")) assertTrue(f, TEST.asset in read(f))
        for (n in listOf("LICENSE-MIT", "LICENSE-Apache-2.0", "LICENSE-BSL-1.0", "LICENSE-SGI-B-2.0", "LICENSE-GPL-2.0-only",
                "LICENSE-GPL-1.0-or-later", "mesa-docs-license.rst", "SOURCE.txt", "0001-android-export-vk_icd-entrypoints.diff")) {
            assertTrue(n, File(root, "licenses/turnip-test-mesa-26.3.1/$n").isFile)
            assertTrue(n, File(root, "app/src/main/assets/notices/licenses/turnip-test-mesa-26.3.1/$n").isFile)
        }
        // the patch in the recipe is the one in the zip's notices
        assertEquals(read("tools/turnip-test/0001-android-export-vk_icd-entrypoints.diff"),
            read("licenses/turnip-test-mesa-26.3.1/0001-android-export-vk_icd-entrypoints.diff"))
    }
}
