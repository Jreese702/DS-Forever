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
            // the tab's groups WITH ROWS NOW (no game here: Top screen's switches have not come, only Game windows' minimap)
            val groups = SettingsTabs.groups(t).filter { r.hasRows(it) }
            assertTrue(t.label, groups.isNotEmpty())
            // General does not fold (the user, 2026-11-12): every group open
            assertEquals(t.label, if (t.folds) setOf(groups.first()) else groups.toSet(), r.open)
            for (gb in r.groupBoxes) {
                val onTab = gb.group in groups
                assertEquals("${gb.group} box", if (onTab) View.VISIBLE else View.GONE, gb.box.visibility)
                val isOpen = if (t.folds) gb.group == groups.first() else onTab
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
        r.show(SettingsTabs.Tab.PERFORMANCE); lay(r)
        val groups = SettingsTabs.groups(SettingsTabs.Tab.PERFORMANCE).filter { r.hasRows(it) }
        val first = groups[0]; val second = groups[1]
        val heads = groups.map { g -> headBox(r, g) }
        val targets = r.pad.targetBoxes()
        for (h in heads) assertTrue("heading $h is a stop", targets.any { it == h })
        // the cursor's first stop is the first heading; A folds it
        press(r, "PADDDOWN")
        assertEquals(heads[0], r.pad.focusBox())
        press(r, "PAD1")
        assertTrue(first !in r.open)
        assertTrue(box(r, first).parts.all { it.visibility == View.GONE })
        assertTrue(!box(r, first).heading.open)
        // down: the next heading; A opens it
        press(r, "PADDDOWN")
        assertEquals(headBox(r, second), r.pad.focusBox())
        press(r, "PAD1")
        assertTrue(second in r.open && box(r, second).heading.open)
        assertTrue(box(r, second).parts.all { it.visibility == View.VISIBLE })
        // up: back on the first heading; A opens it again
        press(r, "PADDUP")
        assertEquals(headBox(r, first), r.pad.focusBox())
        press(r, "PAD1")
        assertTrue(first in r.open)
    }

    /** the user, 2026-11-12: "the general tab doesnt need the folding sections": General's groups always open, no heading stops,
     *  a tap or toggle on a heading does nothing */
    @Test fun generalDoesNotFold() {
        val r = room()
        r.show(SettingsTabs.Tab.GENERAL); lay(r)
        val groups = SettingsTabs.groups(SettingsTabs.Tab.GENERAL).filter { r.hasRows(it) }
        assertEquals(groups.toSet(), r.open)
        for (g in groups) assertTrue(box(r, g).parts.all { it.visibility == View.VISIBLE })
        val targets = r.pad.targetBoxes()
        for (g in groups) assertTrue("no stop on $g's heading", targets.none { it == headBox(r, g) })
        r.toggle(groups[0])
        assertEquals(groups.toSet(), r.open)
        // the cursor's first stop is a row, never a heading
        press(r, "PADDDOWN")
        val f = r.pad.focusBox()
        assertNotNull(f)
        assertTrue(groups.none { headBox(r, it) == f })
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

    // ---------------------------------------------------------------- the review's findings (2026-11-12)

    /**
     * 1: a part shrunk to 0 px keeps no pressable boxes, and the cursor never stops in it. #47: the Account group always has its Game
     * account row now, so the group's slice loses the login row and keeps the Game account's two segments; the 0 px case is a slice of
     * the login block alone
     */
    @Test fun zeroHeightPartsAreNoStops() {
        val r = room()
        r.show(SettingsTabs.Tab.GENERAL); r.toggle(SettingsTabs.Group.ACCOUNT); lay(r)
        val slice = box(r, SettingsTabs.Group.ACCOUNT).parts.single() as SettingsView
        val before = slice.focusTargets()
        assertEquals("the login button, then Ask each time / Remembered", 3, before.size)
        val loginBox = before.first()
        assertTrue(r.pad.targetBoxes().any { it.top >= headBox(r, SettingsTabs.Group.ACCOUNT).bottom && it.height() == loginBox.height() })
        val host = SettingsView::class.java.getDeclaredField("hub").apply { isAccessible = true }.get(slice) as SettingsView
        // a slice of the login block alone: the host takes the login row away, measured again it is 0 px with no stale box
        val loginOnly = host.slice(setOf(SettingsTabs.Block.ACCOUNT))
        lay(loginOnly)
        assertEquals(1, loginOnly.focusTargets().size)
        host.loginRow = false
        loginOnly.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080 - Strip.HEIGHT, View.MeasureSpec.EXACTLY))
        loginOnly.layout(0, 0, 1240, loginOnly.measuredHeight)
        assertEquals(0, loginOnly.height)
        assertTrue("no stale login box", loginOnly.focusTargets().isEmpty())
        // the room's Account group: the login row is gone, the Game account row stays (its group and heading with it)
        lay(r)
        val after = slice.focusTargets()
        assertEquals(2, after.size)
        assertTrue("no stale login box", after.none { it == loginBox })
        assertEquals(View.VISIBLE, box(r, SettingsTabs.Group.ACCOUNT).box.visibility)
        assertEquals("no stops on General headings", 0, headingStops(r))
    }

    /** 2: entering the room shows its tab afresh: the first group open, the rest folded, the page at its top */
    @Test fun reEnteringTheRoomOpensTheFirstGroup() {
        val r = room()
        r.show(SettingsTabs.Tab.CONTROLLER); lay(r)
        r.toggle(SettingsTabs.Group.BUTTONS); r.toggle(SettingsTabs.Group.WHEELS); r.toggle(SettingsTabs.Group.SMART_Y)
        assertEquals(setOf(SettingsTabs.Group.WHEELS, SettingsTabs.Group.SMART_Y), r.open)
        r.pad.leave()
        r.pad.enter()
        lay(r)
        assertEquals(SettingsTabs.Tab.CONTROLLER, r.tab)
        assertEquals(setOf(SettingsTabs.Group.BUTTONS), r.open)
    }

    /** 3: Polestar's "choose it in" names the row's new place, in all three languages */
    @Test fun polestarPointsAtItsQuestTrackingRow() {
        assertEquals("Add-ons › Polestar › Quest tracking", ThirdPartyAddons.POLESTAR.usedIn)
        assertEquals(SettingsTabs.Group.POLESTAR, SettingsTabs.Block.QUEST_TRACKING.group)
        val line = "Installed, not in use: choose it in ${ThirdPartyAddons.POLESTAR.usedIn}"
        for (l in listOf("es", "fr")) {
            val f = listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json")).first { it.canRead() }
            Tr.load(l, f.readText())
            val t = Tr.t(line, l)
            assertTrue("$l: $t", t != line && "Polestar" in t && "Map" !in t && "Mapa" !in t && "Carte" !in t)
        }
    }

    /** 4 / 6: a group with no rows now is not shown (no heading, no stop); it shows - and opens, folds untouched - when rows come */
    @Test fun emptyGroupsHideUntilTheirRowsCome() {
        val r = room()
        r.show(SettingsTabs.Tab.TOP); lay(r)
        // no game: only Game windows (the minimap is always listed) has rows; it is the open one
        for (g in listOf(SettingsTabs.Group.HUD, SettingsTabs.Group.TARGET, SettingsTabs.Group.PARTY, SettingsTabs.Group.NAVIGATION))
            assertEquals("$g", View.GONE, box(r, g).box.visibility)
        assertEquals(setOf(SettingsTabs.Group.WINDOWS), r.open)
        assertTrue(r.pad.targetBoxes().none { it == headBox(r, SettingsTabs.Group.NAVIGATION) })
        // the add-on says the compass bar's word: Navigation has a row now, shows, and (folds untouched) is the open one
        CompassSwitch.note(JSONObject().put("proof", JSONObject().put("compc", JSONObject().put("show", true).put("on", 1).put("off", 2))))
        lay(r)
        assertEquals(View.VISIBLE, box(r, SettingsTabs.Group.NAVIGATION).box.visibility)
        assertEquals(setOf(SettingsTabs.Group.NAVIGATION), r.open)
        assertTrue(r.pad.targetBoxes().any { it == headBox(r, SettingsTabs.Group.NAVIGATION) })
        // Controller > Combat helpers: no switch, no attack row - hidden
        r.show(SettingsTabs.Tab.CONTROLLER); lay(r)
        assertEquals(View.GONE, box(r, SettingsTabs.Group.COMBAT).box.visibility)
    }

    /** 5: the Frames slice's draw compares with the MEASURED row count, so a row that comes or goes asks for a new layout */
    @Test fun framesDrawAsksForLayoutWhenItsRowsChange() {
        val r = room()
        r.show(SettingsTabs.Tab.PERFORMANCE); lay(r)
        val frames = box(r, SettingsTabs.Group.FRAMES).parts.single() as SettingsView
        val f = SettingsView::class.java.getDeclaredField("measuredFg").apply { isAccessible = true }
        assertTrue(f.getInt(frames) > 0)
        assertTrue(!frames.isLayoutRequested)
        f.setInt(frames, 99)                                         // as if the rows changed since the measure
        frames.draw(Canvas(Bitmap.createBitmap(1240, 1000, Bitmap.Config.ARGB_8888)))
        assertTrue("the draw asked for a new layout", frames.isLayoutRequested)
        assertEquals(99, f.getInt(frames))                           // the draw path leaves the measured count alone
    }

    /** 7: an add-on group that stops being shown (QuestTrail out) goes at once, heading and band, without a tab switch */
    @Test fun questTrailGroupFollowsItsAddon() {
        ThirdPartyAddons::class.java.getDeclaredField("noFetch").apply { isAccessible = true }.setBoolean(null, true)   // no downloads here
        val r = room()
        ThirdPartyAddons.setCore(ThirdPartyAddons.QUESTTRAIL, true)
        r.show(SettingsTabs.Tab.ADDONS); lay(r)
        assertEquals(View.VISIBLE, box(r, SettingsTabs.Group.QUESTTRAIL).box.visibility)
        ThirdPartyAddons.setCore(ThirdPartyAddons.QUESTTRAIL, false)
        lay(r)
        assertEquals(View.GONE, box(r, SettingsTabs.Group.QUESTTRAIL).box.visibility)
        assertEquals("one stop per shown heading", r.groupBoxes.count { it.box.visibility == View.VISIBLE }, headingStops(r))
    }

    /** 8: a change that decides a slice's rows (an add-on's state, Polestar's top-screen row's inputs) measures every part again */
    @Test fun aSliceIsMeasuredAgainWhenItsRowsChange() {
        val r = room()
        r.show(SettingsTabs.Tab.ADDONS); lay(r)
        val parts = r.groupBoxes.filter { it.box.visibility == View.VISIBLE && it.group in r.open }.flatMap { it.parts }
        assertTrue(parts.isNotEmpty())
        for (p in parts) assertTrue(!p.isLayoutRequested)
        // an add-on change (as Polestar coming into use, which decides Navigation's top-screen row): every part measured again
        val o = ThirdPartyAddons.SPOKEN.options.first()
        ThirdPartyAddons.setOption(ThirdPartyAddons.SPOKEN, o, !ThirdPartyAddons.option(ThirdPartyAddons.SPOKEN, o))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue("every part measured again", parts.all { it.isLayoutRequested })
    }

    /** the cursor's heading stops now (a heading's box is the only stop of its height) */
    private fun headingStops(r: SettingsRoom): Int = r.pad.targetBoxes().count { Math.abs(it.height() - (SettingsFoldHeading.H - 8f)) < 0.5f }

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
