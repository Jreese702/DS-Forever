package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * SETTINGS > ADD-ONS > IMPORTED, off the device (the user, 2026-11-16: "maybe we just do an import addons button then?"): the room on
 * the Add-ons tab with the Imported group open, driven the real way (a pick handed to ImportedAddons.receive, its worker, the card, the
 * Install press, the list, Remove). The checks run always; the pictures only when ADDONS_IMPORT_SHOTS_DIR is set
 * (ADDONS_IMPORT_ART_ROOT = an art root laid out like the app's, E:/wow-build/runtime-art/r1/files):
 *   ADDONS_IMPORT_SHOTS_DIR=<dir> ADDONS_IMPORT_ART_ROOT=<root> gradlew :app:testModernDebugUnitTest --tests "*AddonsImportShotsTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddonsImportShotsTest {
    private val out: File? = System.getenv("ADDONS_IMPORT_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    private fun art() {
        System.getenv("ADDONS_IMPORT_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
    }

    private fun lay(r: View) {
        for (k in 0..2) {
            ShadowLooper.idleMainLooper()
            r.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
            r.layout(0, 0, W, H)
            r.draw(Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)))
        }
    }
    /** the room's scroll at its foot (the Imported group is the tab's last) */
    private fun toFoot(v: View) {
        if (v is android.widget.ScrollView) { v.scrollTo(0, (v.getChildAt(0)?.height ?: 0) - v.height); return }
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) toFoot(v.getChildAt(i))
    }
    private fun shot(r: View, name: String) {
        lay(r); toFoot(r); lay(r)
        val d = out ?: return
        Thread.sleep(250); lay(r); toFoot(r)                            // icons decode off the UI thread
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Theme.ink); r.draw(c)
        d.mkdirs()
        File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> for ((n, t) in files) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return bo.toByteArray()
    }
    private fun toc(title: String, ver: String, iface: Int = 16001) = "## Interface: $iface\n## Title: $title\n## Version: $ver\n"

    private fun waitIdle(r: View) {
        val until = System.currentTimeMillis() + 15_000
        Thread.sleep(50)
        while (ImportedAddons.busy && System.currentTimeMillis() < until) Thread.sleep(20)
        assertFalse("the worker finished", ImportedAddons.busy)
        lay(r)
    }

    /** the Imported group's slice (the SettingsView part that draws it) */
    private fun slice(r: SettingsRoom): SettingsView =
        r.groupBoxes.first { it.group == SettingsTabs.Group.IMPORTED }.parts.filterIsInstance<SettingsView>().single()
    private fun box(v: SettingsView, name: String): RectF = SettingsView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(v) as RectF
    @Suppress("UNCHECKED_CAST")
    private fun removeBoxes(v: SettingsView): Map<String, RectF> =
        SettingsView::class.java.getDeclaredField("importRemove").apply { isAccessible = true }.get(v) as Map<String, RectF>
    @Suppress("UNCHECKED_CAST")
    private fun unqueueBoxes(v: SettingsView): Map<String, RectF> =
        SettingsView::class.java.getDeclaredField("importUnqueue").apply { isAccessible = true }.get(v) as Map<String, RectF>
    /** ImportedAddons' game-running answer (internal to the companion module) */
    private fun running(up: Boolean) {
        ImportedAddons::class.java.getDeclaredField("gameRunning").apply { isAccessible = true }.set(null, { up })
    }
    /** a press at [b]'s centre, as A on the cursor or a touch: the view's own tap */
    private fun press(v: SettingsView, b: RectF) {
        assertFalse("pressable", b.isEmpty)
        assertTrue("a controller stop", v.focusTargets().any { it.contains(b.centerX(), b.centerY()) })
        SettingsView::class.java.getDeclaredMethod("tap", Float::class.java, Float::class.java).apply { isAccessible = true }
            .invoke(v, b.centerX(), b.centerY())
    }

    private fun lang(l: String) {
        if (l != "en") listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
            .firstOrNull { it.canRead() }?.let { Tr.load(l, it.readText()) }
        Tr.setChoice(app, l)
    }

    @Test fun importTheCardAndTheList() {
        art()
        Settings.init(app); SettingsTabs.init(app)
        ThirdPartyAddons::class.java.getDeclaredField("noFetch").apply { isAccessible = true }.setBoolean(null, true)   // no downloads here
        val game = Files.createTempDirectory("game").toFile()
        val flavor = File(game, "_forever_")
        // the player's own Bagnon already there (the card says it replaces it)
        File(flavor, "Interface/AddOns/Bagnon").mkdirs()
        File(flavor, "Interface/AddOns/Bagnon/Bagnon.toc").writeText("## Version: 9.0.1\n")
        val oldRoot = ImportedAddons.flavorRoot
        ImportedAddons.flavorRoot = { flavor }
        try {
            val v = SettingsView(app) { }
            v.loginRow = true
            var picker = 0
            v.onImportAddons = { picker++ }
            val r = SettingsRoom(app, v) { null }.also { lay(it) }
            r.show(SettingsTabs.Tab.ADDONS); lay(r)
            r.toggle(SettingsTabs.Group.POLESTAR); r.toggle(SettingsTabs.Group.IMPORTED)
            val s = slice(r)
            shot(r, "01_addons_tab_import")
            // Import: the host's picker (A and a touch alike)
            press(s, box(s, "btnImport"))
            assertEquals(1, picker)

            // ---- the pick: a suite (one replaces yours, one made for another game version, one of DS Forever's own) and a zip with none
            val suite = zip("ElvUI-13.9/Bagnon/Bagnon.toc" to toc("|cff00aaffBagnon|r", "10.2.1"), "ElvUI-13.9/Bagnon/core.lua" to "x".repeat(40_000),
                "ElvUI-13.9/Details/Details.toc" to toc("Details! Damage Meter", "2.3.0"), "ElvUI-13.9/Details/a.lua" to "y".repeat(2_400_000),
                "ElvUI-13.9/OldBars/OldBars.toc" to toc("Old Bars", "1.4", iface = 11507),
                "ElvUI-13.9/WowCompanionHub/WowCompanionHub.toc" to toc("Not ours", "0.1"))
            val none = zip("readme.txt" to "no add-on here")
            ImportedAddons.receive(listOf(ImportedAddons.Picked("ElvUI-13.9.zip") { suite.inputStream() },
                ImportedAddons.Picked("notes.zip") { none.inputStream() }))
            waitIdle(r)
            val p = ImportedAddons.pending
            assertNotNull(p)
            assertEquals(listOf("Bagnon", "Details", "OldBars", "WowCompanionHub"), p!!.found.map { it.folder })
            assertTrue(p.found.first { it.folder == "Bagnon" }.replaces)
            assertFalse(p.found.first { it.folder == "OldBars" }.ifaceOk)
            assertEquals("Kept: DS Forever's own", p.found.first { it.folder == "WowCompanionHub" }.refused)
            assertEquals("No add-on in this zip", p.bad.single().why)
            assertEquals("Bagnon", p.found.first().title)
            shot(r, "02_confirm_card")
            for (l in listOf("es", "fr")) { lang(l); shot(r, "02_confirm_card_$l") }
            lang("en")

            // Install: it replaces one of yours, so twice
            press(s, box(s, "btnImportInstall")); lay(r)
            assertNotNull("not installed on the first press", ImportedAddons.pending)
            shot(r, "03_confirm_tap_again")
            press(s, box(s, "btnImportInstall"))
            waitIdle(r)
            assertEquals(null, ImportedAddons.pending)
            assertEquals("Installed", ImportedAddons.status)
            assertEquals("## Interface: 16001\n## Title: |cff00aaffBagnon|r\n## Version: 10.2.1\n",
                File(flavor, "Interface/AddOns/Bagnon/Bagnon.toc").readText())
            assertFalse(File(flavor, "Interface/AddOns/WowCompanionHub").exists())
            assertEquals(listOf("Bagnon", "Details", "OldBars"), ImportedAddons.list().map { it.folder })
            shot(r, "04_imported_list")

            // Remove: twice; then it is in the backup
            val rm = removeBoxes(s)["Details"]!!
            press(s, RectF(rm)); lay(r)
            shot(r, "05_remove_tap_again")
            press(s, RectF(removeBoxes(s)["Details"]!!))
            waitIdle(r)
            assertEquals(listOf("Bagnon", "OldBars"), ImportedAddons.list().map { it.folder })
            assertFalse(File(flavor, "Interface/AddOns/Details").exists())
            assertTrue(File(flavor, "Interface/AddOns-backup-dsforever").listFiles()!!.any { it.name.startsWith("Details-2.3.0-") })
            shot(r, "06_after_remove")

            // the user's pick (2026-11-16, "New now, replace next start"): the game running, a replacement of Bagnon waits
            running(true)
            val newer = zip("Bagnon/Bagnon.toc" to toc("Bagnon", "11.0"), "Bagnon/core.lua" to "z")
            ImportedAddons.receive(listOf(ImportedAddons.Picked("Bagnon-11.0.zip") { newer.inputStream() }))
            waitIdle(r)
            press(s, box(s, "btnImportInstall")); lay(r)
            press(s, box(s, "btnImportInstall"))
            waitIdle(r)
            assertEquals("Replaces at next start", ImportedAddons.status)
            assertEquals("## Interface: 16001\n## Title: |cff00aaffBagnon|r\n## Version: 10.2.1\n",
                File(flavor, "Interface/AddOns/Bagnon/Bagnon.toc").readText())
            assertEquals(listOf("Bagnon"), ImportedAddons.queued().map { it.folder })
            assertEquals("the waiting row stands for it", setOf("OldBars"), removeBoxes(s).keys)
            shot(r, "06b_replaces_at_next_start")
            for (l in listOf("es", "fr")) { lang(l); shot(r, "06b_replaces_at_next_start_$l") }
            lang("en")
            // its Cancel: the row goes, AddOns untouched
            press(s, RectF(unqueueBoxes(s)["Bagnon"]!!))
            waitIdle(r)
            assertTrue(ImportedAddons.queued().isEmpty())
            assertEquals(setOf("Bagnon", "OldBars"), removeBoxes(s).keys)
            running(false)
            // no game folder: Import greyed with its word
            ImportedAddons.flavorRoot = { null }
            lay(r)
            assertTrue("greyed", box(s, "btnImport").width() == 0f)
            for (f in ImportedAddons.list()) ImportedAddons.removeNow(f.folder, flavor)
            ImportedAddons.cancel()
            shot(r, "07_no_game_folder")
        } finally {
            ImportedAddons.flavorRoot = oldRoot
            ImportedAddons::class.java.getDeclaredField("gameRunning").apply { isAccessible = true }
                .set(null, { DependencyAddons.gameRunning() })
            Tr.setChoice(app, "auto")
            game.deleteRecursively()
        }
    }
}
