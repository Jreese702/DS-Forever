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
            // General does not fold (the user, 2026-11-12): every group open. A headless group (Add-ons' Import button, the user
            // 2026-11-16) is always open, with the first group that has a heading
            // (beautify pick 5: only Add-ons folds; every tiled tab shows every group open)
            val want = if (t.folds) groups.filter { it.headless }.toSet() + listOfNotNull(groups.firstOrNull { !it.headless })
                else groups.filter { !it.foldsAlone }.toSet()          // 2026-11-19: General's Reset shows folded
            assertEquals(t.label, want, r.open)
            for (gb in r.groupBoxes) {
                val onTab = gb.group in groups
                assertEquals("${gb.group} box", if (onTab) View.VISIBLE else View.GONE, gb.box.visibility)
                val isOpen = gb.group in want
                // (a headless group - Add-ons' Import, Performance's tiles - draws no heading at all)
                if (!gb.group.headless) assertEquals("${gb.group} heading", isOpen, gb.heading.open)
                for (p in gb.parts) assertEquals("${gb.group} part", if (isOpen) View.VISIBLE else View.GONE, p.visibility)
            }
        }
        // every time: a group folded by hand, the tab left and shown again - its first headed group open again (Add-ons, the one
        // folding tab: pick 5)
        withVoice {
            val a = room()
            a.show(SettingsTabs.Tab.ADDONS); lay(a)
            assertTrue(SettingsTabs.Group.VOICE in a.open)
            a.toggle(SettingsTabs.Group.VOICE)
            assertTrue(SettingsTabs.Group.VOICE !in a.open)
            a.show(SettingsTabs.Tab.PERFORMANCE); a.show(SettingsTabs.Tab.ADDONS)
            assertTrue(SettingsTabs.Group.VOICE in a.open)
        }
    }

    /**
     * BEAUTIFY PICK 5 (the user, 2026-11-17: tiled tabs - Bottom, Controller, Performance, like Top - do not fold): every group of a
     * tiled tab open, no heading is a cursor stop, a tap or A on a heading folds nothing
     */
    @Test fun tiledTabsDoNotFold() {
        val r = room()
        for (t in listOf(SettingsTabs.Tab.BOTTOM, SettingsTabs.Tab.CONTROLLER, SettingsTabs.Tab.PERFORMANCE)) {
            r.show(t); lay(r)
            val groups = SettingsTabs.groups(t).filter { r.hasRows(it) }
            assertEquals(t.label, groups.toSet(), r.open)
            for (g in groups) assertTrue("$g shown", box(r, g).parts.all { it.visibility == View.VISIBLE })
            assertEquals(t.label, 0, headingStops(r))
            for (g in groups) { r.toggle(g); assertEquals("$g stays open", groups.toSet(), r.open) }
        }
    }

    /** Add-ons' Voice / Imported in the room for a test (Voice is offered only in a build with the offline voice library) */
    private fun withVoice(block: () -> Unit) {
        val was = VoiceModels.offered
        try { VoiceModels.offered = { true }; block() } finally { VoiceModels.offered = was }
    }

    // (beautify pick 5: Add-ons is the one tab that folds - its Voice list; the fold walk uses it)
    @Test fun aOnAHeadingFoldsAndOpensIt() = withVoice {
        val r = room()
        r.show(SettingsTabs.Tab.ADDONS); lay(r)
        val g = SettingsTabs.Group.VOICE
        val head = headBox(r, g)
        assertTrue("the Voice heading is a stop", r.pad.targetBoxes().any { it == head })
        assertTrue(g in r.open)
        // the cursor down to the heading; A folds it
        repeat(12) { if (r.pad.focusBox() != head) press(r, "PADDDOWN") }
        assertEquals(head, r.pad.focusBox())
        press(r, "PAD1")
        assertTrue(g !in r.open)
        assertTrue(box(r, g).parts.all { it.visibility == View.GONE })
        assertTrue(!box(r, g).heading.open)
        // A again opens it
        press(r, "PAD1")
        assertTrue(g in r.open && box(r, g).heading.open)
        assertTrue(box(r, g).parts.all { it.visibility == View.VISIBLE })
    }

    /** the user, 2026-11-12: "the general tab doesnt need the folding sections": General's groups always open, no heading stops,
     *  a tap or toggle on a heading does nothing */
    private fun targetsHaveResetHeading(r: SettingsRoom) = r.pad.targetBoxes().any { it == headBox(r, SettingsTabs.Group.RESET) }

    @Test fun generalDoesNotFold() {
        val r = room()
        r.show(SettingsTabs.Tab.GENERAL); lay(r)
        // 2026-11-19: every group but Reset, which folds on its own (shown folded, its heading a stop)
        val groups = SettingsTabs.groups(SettingsTabs.Tab.GENERAL).filter { r.hasRows(it) && !it.foldsAlone }
        assertEquals(groups.toSet(), r.open)
        assertTrue(targetsHaveResetHeading(r))
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

    @Test fun aTapOnAHeadingToggles() = withVoice {
        val r = room()
        r.show(SettingsTabs.Tab.ADDONS); lay(r)
        val mem = box(r, SettingsTabs.Group.VOICE)
        val h = mem.heading
        val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 300f, 40f, 0)
        val up = android.view.MotionEvent.obtain(0, 40, android.view.MotionEvent.ACTION_UP, 300f, 40f, 0)
        h.dispatchTouchEvent(down); h.dispatchTouchEvent(up)
        assertTrue(SettingsTabs.Group.VOICE !in r.open)
        assertTrue(mem.parts.all { it.visibility == View.GONE })
        h.dispatchTouchEvent(down); h.dispatchTouchEvent(up)
        assertTrue(SettingsTabs.Group.VOICE in r.open)
    }

    /** every row of the data is drawn by a part of its own group (the rows a part draws now, by their words) */
    @Test fun partsDrawTheirGroupsRows() {
        val r = room()
        // beautify/bottom: Bottom screen > Home is ONE tile part (Home's 8 tiles; the attack row is Controller's Combat helpers tile now)
        val home = box(r, SettingsTabs.Group.HOME).parts.single() as BottomTilesView
        assertTrue(home.tileIds().containsAll(listOf(BottomTiles.REST, BottomTiles.QUEST, BottomTiles.CASTBAR)))
        // the beautify pass: the Controller tab's groups are ONE tile part each (Combat helpers' tiles take Home's attack row and the
        // two GameUi parts); Smart Y's own group has none (its tile is in Buttons, its list a sub-page)
        val combat = box(r, SettingsTabs.Group.COMBAT).parts
        assertEquals(1, combat.size)
        assertTrue(combat.single() is ControllerTiles)
        assertTrue(box(r, SettingsTabs.Group.SMART_Y).parts.isEmpty())
        // every group has a part but Smart Y's; General's groups are GeneralTilesView parts; a GameUiSection part only in Top screen's groups
        for (gb in r.groupBoxes.filter { it.group != SettingsTabs.Group.SMART_Y }) assertTrue("${gb.group} has parts", gb.parts.isNotEmpty())
        for (gb in r.groupBoxes.filter { it.group.tab == SettingsTabs.Tab.GENERAL }) assertTrue("${gb.group}", gb.parts.single() is GeneralTilesView)
        assertEquals(setOf(SettingsTabs.Group.LAYOUT, SettingsTabs.Group.HUD, SettingsTabs.Group.TARGET, SettingsTabs.Group.PARTY,
            SettingsTabs.Group.NAVIGATION, SettingsTabs.Group.WINDOWS),
            r.groupBoxes.filter { gb -> gb.parts.any { it is GameUiSection } }.map { it.group }.toSet())
        // beautify: Performance's one group is ONE part, its picture tiles
        assertTrue(box(r, SettingsTabs.Group.FRAMES).parts.single() is PerformanceTiles)
        // 2026-11-16 (the tiles): Navigation is ONE tile part (its Polestar tile was SettingsView's row); every Top screen group's part draws
        // its tiles, the rest keep their rows
        val nav = box(r, SettingsTabs.Group.NAVIGATION).parts
        assertEquals(1, nav.size)
        assertEquals(GameUiSection.Part.NAVIGATION, (nav.single() as GameUiSection).part)
        for (gb in r.groupBoxes) for (p in gb.parts.filterIsInstance<GameUiSection>())
            assertEquals("${gb.group}", gb.group.tab == SettingsTabs.Tab.TOP, p.tiled)
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
        // 2026-11-17 (the beautify pass): General's Account group is ONE GeneralTilesView part of compact rows
        val part = box(r, SettingsTabs.Group.ACCOUNT).parts.single() as GeneralTilesView
        val before = part.focusTargets()
        // the user, 2026-11-14 ("build it"): + Windows ID's Per install / Original (no game here: no restart row)
        // the user, 2026-11-18 (upstream v2.4.5's Browser login, opt-in): + its Off / On under Windows ID
        assertEquals("the login button, then Ask each time / Remembered, then Per install / Original, then Browser login Off / On", 7, before.size)
        val loginBox = before.first()
        assertTrue(r.pad.targetBoxes().any { it.top >= headBox(r, SettingsTabs.Group.ACCOUNT).bottom && it.height() == loginBox.height() })
        val host = SettingsRoom::class.java.getDeclaredField("view").apply { isAccessible = true }.get(r) as SettingsView
        // a slice of SettingsView's own list rows (Add-ons > Voice: one row per model while the build offers them): its rows go away,
        // measured again it is 0 px with no stale box
        withVoice {
            val voiceOnly = host.slice(setOf(SettingsTabs.Block.ADDON_VOICE))
            lay(voiceOnly)
            assertEquals(VoiceModels.Model.values().size, voiceOnly.focusTargets().size)
            VoiceModels.offered = { false }
            voiceOnly.requestLayout()
            voiceOnly.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080 - Strip.HEIGHT, View.MeasureSpec.EXACTLY))
            voiceOnly.layout(0, 0, 1240, voiceOnly.measuredHeight)
            assertEquals(0, voiceOnly.height)
            assertTrue("no stale voice box", voiceOnly.focusTargets().isEmpty())
        }
        // the slices draw only their own list blocks: a slice of a tiled block (the login row is General's tiles now) draws nothing
        val loginOnly = host.slice(setOf(SettingsTabs.Block.ACCOUNT))
        lay(loginOnly)
        assertEquals(0, loginOnly.measuredHeight)
        assertTrue(loginOnly.focusTargets().isEmpty())
        host.loginRow = false
        // the room's Account group: the login row is gone, the Game account row stays (its group and heading with it)
        lay(r)
        val after = part.focusTargets()
        assertEquals(6, after.size)
        assertTrue("no stale login box", after.none { it == loginBox })
        assertEquals(View.VISIBLE, box(r, SettingsTabs.Group.ACCOUNT).box.visibility)
        assertEquals("no stops on General headings but Reset's (it folds)", 1, headingStops(r))
    }

    /** 2: entering the room shows its tab afresh: the first group open, the rest folded, the page at its top */
    @Test fun reEnteringTheRoomOpensTheFirstGroup() = withVoice {
        val r = room()
        r.show(SettingsTabs.Tab.ADDONS); lay(r)
        r.toggle(SettingsTabs.Group.VOICE)
        assertTrue(SettingsTabs.Group.VOICE !in r.open)
        r.pad.leave()
        r.pad.enter()
        lay(r)
        assertEquals(SettingsTabs.Tab.ADDONS, r.tab)
        assertTrue(SettingsTabs.Group.VOICE in r.open)
    }

    /** 3: Polestar's "choose it in" names the row's new place, in all three languages */
    @Test fun polestarPointsAtItsQuestTrackingRow() {
        assertEquals("Add-ons › Polestar › Quest tracking", ThirdPartyAddons.POLESTAR.usedIn)
        // the beautify pass (2026-11-17): Quest tracking is a line of Polestar's card's band, in the cards' group (AddonCards.lines)
        assertEquals(SettingsTabs.Group.CARDS, SettingsTabs.Block.QUEST_TRACKING.group)
        assertEquals(SettingsTabs.Group.CARDS, SettingsTabs.Block.ADDON_POLESTAR.group)
        val line = "Installed, not in use: choose it in ${ThirdPartyAddons.POLESTAR.usedIn}"
        for (l in listOf("es", "fr")) {
            val f = listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json")).first { it.canRead() }
            Tr.load(l, f.readText())
            val t = Tr.t(line, l)
            assertTrue("$l: $t", t != line && "Polestar" in t && "Map" !in t && "Mapa" !in t && "Carte" !in t)
        }
    }

    /**
     * 4 / 6: a group with no rows now is not shown (no heading, no stop). The Top screen's groups are TILES since 2026-11-16 (the user's
     * pick: a fixed place per element, a dim "..." tile until the game reports, so nothing reflows): all five show before the game says
     * anything, all open (no folding on that tab), and the compass's word changes its tile, not the page
     */
    @Test fun emptyGroupsHideUntilTheirRowsCome() {
        val r = room()
        r.show(SettingsTabs.Tab.TOP); lay(r)
        val top = listOf(SettingsTabs.Group.HUD, SettingsTabs.Group.TARGET, SettingsTabs.Group.PARTY, SettingsTabs.Group.NAVIGATION,
            SettingsTabs.Group.WINDOWS)
        for (g in top) assertEquals("$g", View.VISIBLE, box(r, g).box.visibility)
        assertEquals(top.toSet(), r.open)
        assertTrue(r.pad.targetBoxes().none { it == headBox(r, SettingsTabs.Group.NAVIGATION) })
        val before = r.pad.targetBoxes()
        val navTop = box(r, SettingsTabs.Group.NAVIGATION).box.top
        // the add-on says the compass bar's word: the Compass tile is a reported one now, the page does not move
        CompassSwitch.note(JSONObject().put("proof", JSONObject().put("compc", JSONObject().put("show", true).put("on", 1).put("off", 2))))
        lay(r)
        assertEquals(navTop, box(r, SettingsTabs.Group.NAVIGATION).box.top)
        assertEquals(before, r.pad.targetBoxes())
        // Controller > Combat helpers: tiles since the beautify pass (fixed slots, dim "..." ones before the game): shown; Smart Y's
        // own group never (its tile is in Buttons)
        r.show(SettingsTabs.Tab.CONTROLLER); lay(r)
        assertEquals(View.VISIBLE, box(r, SettingsTabs.Group.COMBAT).box.visibility)
        assertEquals(View.GONE, box(r, SettingsTabs.Group.SMART_Y).box.visibility)
        CompassSwitch::class.java.getDeclaredField("state").apply { isAccessible = true }.set(CompassSwitch, null)
    }

    /** 5 (beautify: the Frames rows are Performance's tiles now): a line that comes into the open band (Restart, once a change waits for
     *  the running game) asks for a new layout */
    @Test fun performanceTilesAskForLayoutWhenTheBandsLinesChange() {
        Settings.init(app); SettingsTabs.init(app); FrameGen.init(app)
        val v = SettingsView(app) { }
        v.gameRunning = { true }
        val r = SettingsRoom(app, v) { null }
        r.show(SettingsTabs.Tab.PERFORMANCE); lay(r)
        val p = box(r, SettingsTabs.Group.FRAMES).parts.single() as PerformanceTiles
        FrameGen.markLaunched()                                       // the running game's limit is today's
        val t = p.focusTargets()[0]                                   // Frame limit
        val d = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, t.centerX(), t.centerY(), 0)
        val u = android.view.MotionEvent.obtain(0, 40, android.view.MotionEvent.ACTION_UP, t.centerX(), t.centerY(), 0)
        p.dispatchTouchEvent(d); p.dispatchTouchEvent(u)
        lay(r)
        assertEquals(PerformanceTileModel.LIMIT, p.openTile)
        assertTrue(!p.isLayoutRequested)
        val was = FrameGen.fpsLimit
        FrameGen.askLimit(if (was == 40) 60 else 40)                  // a pick the running game does not have: Restart comes
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue("the band's new line asked for a layout", p.isLayoutRequested)
        FrameGen.askLimit(was)
    }

    /** 7: an add-on that stops being shown (QuestTrail out) goes at once, its card and its band, without a tab switch (the beautify
     *  pass, 2026-11-17: a card in the cards' row; it was its own group) */
    @Test fun questTrailCardFollowsItsAddon() {
        ThirdPartyAddons::class.java.getDeclaredField("noFetch").apply { isAccessible = true }.setBoolean(null, true)   // no downloads here
        val r = room()
        ThirdPartyAddons.setCore(ThirdPartyAddons.QUESTTRAIL, true)
        r.show(SettingsTabs.Tab.ADDONS); lay(r)
        val cards = box(r, SettingsTabs.Group.CARDS).parts.single() as AddonTiles
        assertEquals(View.VISIBLE, box(r, SettingsTabs.Group.CARDS).box.visibility)
        assertEquals(ThirdPartyAddons.QUESTTRAIL.id, cards.cardIds().last())
        ThirdPartyAddons.setCore(ThirdPartyAddons.QUESTTRAIL, false)
        lay(r)
        assertTrue(ThirdPartyAddons.QUESTTRAIL.id !in cards.cardIds())
        assertEquals("one stop per shown heading", r.groupBoxes.count { it.box.visibility == View.VISIBLE && !it.group.headless }, headingStops(r))
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
        r.show(SettingsTabs.Tab.CONTROLLER); shot("settings_4b_controller_all_open")
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
