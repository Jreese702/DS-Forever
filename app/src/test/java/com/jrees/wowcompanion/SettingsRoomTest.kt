package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * THE SIX TABS' FOLDING GROUPS, off-device (the user's approved layout, 2026-11-12): the room built as the host builds it, each tab
 * shown with its FIRST group open and the rest folded, the cursor stopping on each heading and A opening / folding it.
 *
 * With SETTINGS_SHOTS_DIR set it also renders each tab in its default fold state, one tab with a second group opened, and a contact
 * sheet (settings_sheet.png); THEME_ART_ROOT = an art root laid out like the app's (ui/kit, fonts, icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsRoomTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var n = 0

    private fun room(): SettingsRoom {
        Settings.init(app); SettingsTabs.init(app)
        System.getenv("THEME_ART_ROOT")?.let { root ->
            val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, File(root))
        }
        val v = SettingsView(app) { }
        v.loginRow = true                                          // the host's wiring (CompanionHost): the login row is there
        return SettingsRoom(app, v) { null }.also { lay(it) }
    }

    private fun lay(r: View, w: Int = 1240, h: Int = 1080 - Strip.HEIGHT) {
        for (k in 0..2) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            r.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            r.layout(0, 0, w, h)
            // a part's hit boxes are its last draw's: draw once so the cursor's targets are there
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            r.draw(Canvas(b))
        }
    }

    private fun press(r: SettingsRoom, k: String) {
        r.pad.onPad(JSONObject().put("k", k).put("n", ++n))
        lay(r)
    }

    private fun box(r: SettingsRoom, g: SettingsTabs.Group) = r.groupBoxes.first { it.group == g }

    @Test fun eachTabOpensOnItsFirstGroup() {
        val r = room()
        for (t in SettingsTabs.visible) {
            r.show(t); lay(r)
            val groups = SettingsTabs.groups(t)
            assertEquals(t.label, setOf(groups.first()), r.open)
            for (gb in r.groupBoxes) {
                val onTab = gb.group in groups
                assertEquals("${gb.group} box", if (onTab) View.VISIBLE else View.GONE, gb.box.visibility)
                val isOpen = gb.group == groups.first()
                assertEquals("${gb.group} heading", isOpen, gb.heading.open)
                for (p in gb.parts) assertEquals("${gb.group} part", if (isOpen) View.VISIBLE else View.GONE, p.visibility)
            }
        }
        // every time: a group opened, the tab left and shown again - only the first open
        r.show(SettingsTabs.Tab.CONTROLLER); r.toggle(SettingsTabs.Group.WHEELS)
        assertEquals(setOf(SettingsTabs.Group.BUTTONS, SettingsTabs.Group.WHEELS), r.open)
        r.show(SettingsTabs.Tab.PERFORMANCE); r.show(SettingsTabs.Tab.CONTROLLER)
        assertEquals(setOf(SettingsTabs.Group.BUTTONS), r.open)
    }

    @Test fun aOnAHeadingFoldsAndOpensIt() {
        val r = room()
        r.show(SettingsTabs.Tab.GENERAL); lay(r)
        val look = box(r, SettingsTabs.Group.LOOK)
        val account = box(r, SettingsTabs.Group.ACCOUNT)
        // the targets: every heading of the tab is a stop, and the open group's rows; a folded group's rows are not
        val heads = SettingsTabs.groups(SettingsTabs.Tab.GENERAL).map { g -> headBox(r, g) }
        val targets = r.pad.targetBoxes()
        for (h in heads) assertTrue("heading $h is a stop", targets.any { it == h })
        val lookRows = targets.count { it.top > heads[0].bottom && it.bottom < heads[1].top }
        assertTrue("Look's rows are stops", lookRows >= 2)
        // the cursor's first stop is the first heading (D-pad into the page)
        press(r, "PADDDOWN")
        assertEquals(heads[0], r.pad.focusBox())
        // A folds Look
        press(r, "PAD1")
        assertTrue(SettingsTabs.Group.LOOK !in r.open)
        assertTrue(look.parts.all { it.visibility == View.GONE })
        assertTrue(!look.heading.open)
        // down: the next heading (Look's rows are gone); A opens Account
        press(r, "PADDDOWN")
        assertEquals(headBox(r, SettingsTabs.Group.ACCOUNT), r.pad.focusBox())
        press(r, "PAD1")
        assertTrue(SettingsTabs.Group.ACCOUNT in r.open && account.heading.open)
        assertTrue(account.parts.all { it.visibility == View.VISIBLE })
        // the login row is a stop now, under Account's heading
        press(r, "PADDDOWN")
        val f = r.pad.focusBox()
        assertNotNull(f)
        assertTrue(f!!.top >= headBox(r, SettingsTabs.Group.ACCOUNT).bottom)
        // up, up: back on Look's heading; A opens it again
        press(r, "PADDUP"); press(r, "PADDUP")
        assertEquals(heads[0], r.pad.focusBox())
        press(r, "PAD1")
        assertTrue(SettingsTabs.Group.LOOK in r.open)
    }

    @Test fun aTapOnAHeadingToggles() {
        val r = room()
        r.show(SettingsTabs.Tab.PERFORMANCE); lay(r)
        val mem = box(r, SettingsTabs.Group.MEMORY)
        val h = mem.heading
        val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 300f, 40f, 0)
        val up = android.view.MotionEvent.obtain(0, 40, android.view.MotionEvent.ACTION_UP, 300f, 40f, 0)
        h.dispatchTouchEvent(down); h.dispatchTouchEvent(up)
        assertTrue(SettingsTabs.Group.MEMORY in r.open)
        assertTrue(mem.parts.all { it.visibility == View.VISIBLE })
        h.dispatchTouchEvent(down); h.dispatchTouchEvent(up)
        assertTrue(SettingsTabs.Group.MEMORY !in r.open)
    }

    /** every row of the data is drawn by a part of its own group (the rows a part draws now, by their words) */
    @Test fun partsDrawTheirGroupsRows() {
        val r = room()
        val home = box(r, SettingsTabs.Group.HOME).parts.filterIsInstance<HomeSettingsSection>().single()
        assertTrue("Start the attack when a spell is cast" !in home.rowLabels())
        val combat = box(r, SettingsTabs.Group.COMBAT).parts
        assertEquals(3, combat.size)                                  // GameUiSection, Home's attack row, GameUiSection
        assertTrue(combat[1] is HomeSettingsSection)
        // every group has a part, and a GameUiSection part only in Top screen's groups, Help and Combat helpers
        for (gb in r.groupBoxes) assertTrue("${gb.group} has parts", gb.parts.isNotEmpty())
        assertEquals(setOf(SettingsTabs.Group.HELP, SettingsTabs.Group.HUD, SettingsTabs.Group.TARGET, SettingsTabs.Group.PARTY,
            SettingsTabs.Group.NAVIGATION, SettingsTabs.Group.WINDOWS, SettingsTabs.Group.COMBAT),
            r.groupBoxes.filter { gb -> gb.parts.any { it is GameUiSection } }.map { it.group }.toSet())
    }

    private fun headBox(r: SettingsRoom, g: SettingsTabs.Group): RectF {
        val gb = box(r, g)
        val hr = gb.heading.rect()
        return RectF(hr.left, hr.top + gb.box.top, hr.right, hr.bottom + gb.box.top)
    }

    // ---------------------------------------------------------------- the renders (SETTINGS_SHOTS_DIR)

    @Test fun shots() {
        val out = System.getenv("SETTINGS_SHOTS_DIR")?.let { File(it) } ?: return
        out.mkdirs()
        val r = room()
        val made = ArrayList<Pair<String, Bitmap>>()
        fun shot(name: String) {
            lay(r)
            Thread.sleep(250); lay(r)                                       // icons decode off the UI thread
            val b = Bitmap.createBitmap(1240, 1080 - Strip.HEIGHT, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(Theme.ink); r.draw(c)
            File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            made.add(name to b)
        }
        for ((i, t) in SettingsTabs.visible.withIndex()) { r.show(t); shot("settings_${i + 1}_${t.name.lowercase()}") }
        // one tab with a second group opened: Controller with Wheels open as well as Buttons
        r.show(SettingsTabs.Tab.CONTROLLER); r.toggle(SettingsTabs.Group.WHEELS); shot("settings_4b_controller_wheels_open")
        // the six tabs in Spanish and French (all on the row, no scrolling)
        for (l in listOf("es", "fr")) {
            listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
                .firstOrNull { it.canRead() }?.let { Tr.load(l, it.readText()) }
            Tr.setChoice(app, l); r.show(SettingsTabs.Tab.PERFORMANCE); shot("settings_6_performance_$l")
        }
        Tr.setChoice(app, "auto")
        // the contact sheet: three across at half size, each name under it
        val k = 0.5f
        val cw = (1240 * k).toInt(); val ch = ((1080 - Strip.HEIGHT) * k).toInt()
        val cols = 3; val rows = (made.size + cols - 1) / cols
        val pad = 12; val label = 34
        val sheet = Bitmap.createBitmap(cols * (cw + pad) + pad, rows * (ch + label + pad) + pad, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(android.graphics.Color.rgb(40, 40, 40))
        val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 24f }
        for ((i, e) in made.withIndex()) {
            val x = pad + (i % cols) * (cw + pad); val y = pad + (i / cols) * (ch + label + pad)
            c.drawBitmap(e.second, null, android.graphics.Rect(x, y, x + cw, y + ch), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            c.drawText(e.first, x.toFloat(), (y + ch + 26).toFloat(), tp)
        }
        File(out, "settings_sheet.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
