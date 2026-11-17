package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * THE CONTROLLER TAB AS PICTURE TILES, off-device (the beautify pass; the user, 2026-11-17: "I REALLY REALLY love what you did with the
 * top screen settings menu with the pictures and want to do something similar to the other menus"; the design's section 4 and critic C6):
 * the room built as the host builds it; three tile rows (Buttons with Smart Y as the one wide tile, Wheels, Combat helpers); the
 * band under a tile, its choices writing through the old rows' setters; B one step back (band -> tile, sub-page -> tile, then the tab
 * row); Smart Y's "List" opening the list as a sub-page that lands on its first stop; slots that do not apply not drawn.
 *
 * With CTL_SHOTS_DIR set it also renders the tab (no game, reported, bands open, the sub-page, the cursor, es / fr, no kit) and a sheet
 * of the pictures; THEME_ART_ROOT = an art root laid out like the app's (ui/kit, ui/cast, fonts, icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControllerTilesRoomTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var n = 0
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private var doc: SmartYModel.Doc? = null
    private var pet: PadMapModel.PetB? = null
    private val sent = ArrayList<List<Pair<String, Int>>>()
    private var live = true

    private fun art(root: String? = System.getenv("THEME_ART_ROOT")) {
        val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, root?.let { File(it) })
        UiKit.root = UiKit.root
        for (nm in listOf("castCache", "castMissing")) when (val o = UiKit::class.java.getDeclaredField(nm).apply { isAccessible = true }.get(UiKit)) {
            is MutableMap<*, *> -> o.clear(); is MutableSet<*> -> o.clear()
        }
    }

    private fun room(kit: Boolean = true): SettingsRoom {
        Settings.init(app); SettingsTabs.init(app); GameUi.init(app); HomeSettings.init(app); CastSettings.init(app)
        if (kit) art() else art(null)
        val v = SettingsView(app) { }
        v.loginRow = true
        v.smartDoc = { doc }
        v.petB = { pet }
        v.smartTaps = { t -> sent.add(t); live }
        return SettingsRoom(app, v) { null }.also { it.show(SettingsTabs.Tab.CONTROLLER); lay(it) }
    }

    private fun lay(r: View) {
        for (k in 0..2) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            r.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
            r.layout(0, 0, W, H)
            r.draw(Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)))
        }
    }

    private fun press(r: SettingsRoom, k: String) { r.pad.onPad(JSONObject().put("k", k).put("n", ++n)); lay(r) }

    private fun hold(r: SettingsRoom) {
        r.pad.enter(); r.show(SettingsTabs.Tab.CONTROLLER, force = true)
        r.pad.onGuestAck(JSONObject().put("op", "hold").put("ok", true)); lay(r)
    }

    /** every group of the tab open (the tab still folds: the user's pick 5 is open) */
    private fun openAll(r: SettingsRoom) {
        for (g in listOf(SettingsTabs.Group.WHEELS, SettingsTabs.Group.COMBAT)) if (g !in r.open) r.toggle(g)
        lay(r)
    }

    private fun parts(r: SettingsRoom) = r.ctlTiles
    private fun partOf(r: SettingsRoom, id: String) = parts(r).first { id in it.tileIds() }
    private fun openTile(r: SettingsRoom) = parts(r).mapNotNull { it.openTile }.singleOrNull()

    private fun contentTop(r: SettingsRoom, v: View): Float? {
        val sc = SettingsRoom::class.java.getDeclaredField("scroll").apply { isAccessible = true }.get(r) as android.widget.ScrollView
        val content = sc.getChildAt(0)
        var y = 0f; var cur: View = v
        while (cur !== content) { if (cur.visibility != View.VISIBLE) return null; y += cur.top; cur = cur.parent as? View ?: return null }
        return y
    }

    /** every drawn tile's box in the page's content coordinates, by id */
    private fun tileBoxes(r: SettingsRoom): Map<String, RectF> {
        val out = LinkedHashMap<String, RectF>()
        for (p in parts(r)) {
            val top = contentTop(r, p) ?: continue
            for (id in p.tileIds()) p.tileBox(id)?.let { out[id] = RectF(it).apply { offset(0f, top) } }
        }
        return out
    }

    private fun tap(v: View, x: Float, y: Float) {
        val d = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0); v.dispatchTouchEvent(d); d.recycle()
        val u = MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, x, y, 0); v.dispatchTouchEvent(u); u.recycle()
    }

    /** the add-on's switches (a full hub document) for a sample character of [cls] */
    private fun game(cls: String = "HUNTER", three: Boolean = true) {
        GameUi.character = "Sample-Realm"
        val cols = JSONObject()
        listOf("ATK_ON", "ATK_OFF", "SHOT_ON", "SHOT_OFF", "SHOTF_ON", "SHOTF_OFF", "PMODE_ON", "PMODE_OFF", "SRES_ON", "SRES_OFF")
            .forEachIndexed { i, k -> cols.put(k, i) }
        val sw = JSONObject().put("row", "uisw").put("cols", cols).put("set", JSONObject())
            .put("atk", false).put("shot", true).put("pmode", true).put("sres", true)
        if (three) sw.put("shotf", false)
        val hub = JSONObject().put("boot", 1).put("n", 3).put("pending", false).put("row", "ui").put("els", org.json.JSONArray()).put("sw", sw)
        GameUi.onHub(JSONObject().put("hub", hub), true)
        GameUi.onVisible(true)
        GameUi::class.java.getDeclaredField("playerClass").apply { isAccessible = true }.set(GameUi, cls)
        HomeAttackSwitch::class.java.getDeclaredField("state").apply { isAccessible = true }.set(HomeAttackSwitch,
            PadMapModel.AttackState(on = true, n = 1, full = 0, user = 0, refused = 0, pending = false, colOn = 1, colOff = 2))
    }

    private fun smartDoc(on: Boolean = true) = SmartYModel.Doc(
        rows = listOf(SmartYModel.Row(SmartYModel.FOOD), SmartYModel.Row(SmartYModel.DRINK), SmartYModel.Row(SmartYModel.BUFF, listOf(1)),
            SmartYModel.Row(SmartYModel.BUFF, listOf(2, 3)), SmartYModel.Row(SmartYModel.MANNERS)),
        list = listOf(SmartYModel.Buff(1, "Lightning Shield", 136051, false), SmartYModel.Buff(2, "Rockbiter Weapon", 136086, true),
            SmartYModel.Buff(3, "Flametongue Weapon", 135814, true)),
        at = null, row = "smarty", pad = true, sel0 = 0, ops = mapOf("remove" to 1, "up" to 2, "down" to 3, "addor" to 4, "out" to 5),
        on = on, manners = true, icons = mapOf(SmartYModel.FOOD to 133964, SmartYModel.DRINK to 132794, SmartYModel.MANNERS to 136222))

    @After fun forget() {
        for (f in listOf("sw", "state", "castBarHidden", "prdY", "prdW", "prdS", "prefs"))
            runCatching { GameUi::class.java.getDeclaredField(f).apply { isAccessible = true }.set(GameUi, null) }
        GameUi.character = null
        GameUi.resetSeenForTest()
        HomeAttackSwitch::class.java.getDeclaredField("state").apply { isAccessible = true }.set(HomeAttackSwitch, null)
        doc = null; pet = null; live = true
        Tr.setChoice(app, "auto")
        Theme.use(ThemePresets.CLASSIC.id)
    }

    // ---------------------------------------------------------------- the tab

    /** three tile rows, one part each; Smart Y's own group gone (its tile is the wide last one of Buttons); no "What it does" panel */
    @Test fun controllerTabIsThreeRowsOfTiles() {
        val r = room(); openAll(r)
        assertEquals(View.GONE, r.preview.visibility)
        val groups = SettingsTabs.groups(SettingsTabs.Tab.CONTROLLER).filter { r.hasRows(it) }
        assertEquals(listOf(SettingsTabs.Group.BUTTONS, SettingsTabs.Group.WHEELS, SettingsTabs.Group.COMBAT), groups)
        for (gb in r.groupBoxes.filter { it.group in groups }) assertTrue("${gb.group}", gb.parts.single() is ControllerTiles)
        assertEquals(View.GONE, r.groupBoxes.first { it.group == SettingsTabs.Group.SMART_Y }.box.visibility)
        assertEquals(listOf("abxy", "glyphs", "smarty"), partOf(r, "abxy").tileIds())                  // no pet class: no B tile
        assertEquals(listOf("pause", "item", "spell", "classL", "classR"), partOf(r, "pause").tileIds())
        // no class known yet: every helper's slot is there (dim "..." tiles until the game says)
        assertEquals(listOf(GameUi.PMODE_ID, GameUi.SRES_ID, GameUi.ATK_ID, "attackcast", GameUi.SHOT_ID), partOf(r, GameUi.ATK_ID).tileIds())
        val combat = partOf(r, GameUi.ATK_ID)
        assertTrue(combat.waiting(GameUi.PMODE_ID) && combat.value(GameUi.PMODE_ID) == "...")
        assertEquals(GameUiTiles.Badge.WAITING, combat.badge(GameUi.PMODE_ID))
        assertNull("the app's own settings carry no badge", partOf(r, "pause").badge("pause"))
    }

    /** the tiles are Top's: 232 x 184, five columns; Smart Y spans two columns and the gap */
    @Test fun smartYIsTheOneWideTile() {
        val r = room()
        val b = tileBoxes(r)
        val abxy = b.getValue("abxy"); val sy = b.getValue("smarty")
        assertEquals(GameUiTiles.TILE_W, abxy.width(), 0.5f)
        assertEquals(2 * GameUiTiles.TILE_W + GameUiTiles.GAP, sy.width(), 0.5f)
        assertEquals(abxy.top, sy.top, 0.01f)
        // with the pet's tile: the wide one at columns 4-5, ending at the page's margin
        pet = PadMapModel.PetB(on = true, cls = true, pet = true, bound = true)
        partOf(r, "abxy").refresh(); lay(r)
        assertEquals(listOf("abxy", "glyphs", "petb", "smarty"), partOf(r, "abxy").tileIds())
        assertEquals(W - 16f, tileBoxes(r).getValue("smarty").right, 0.5f)
    }

    /** the grid's wide rule: a wide tile that would start in the last column starts the next row */
    @Test fun aWideTileNeverStartsInTheLastColumn() {
        val g = ControllerTiles.grid(listOf(false, false, false, false, true), W.toFloat(), -1, 0f)
        assertEquals(g.tiles[0].l, g.tiles[4].l, 0.01f)
        assertTrue(g.tiles[4].t > g.tiles[0].t)
        assertEquals(2 * (GameUiTiles.TILE_H) + GameUiTiles.GAP + GameUiTiles.TOP + GameUiTiles.BOTTOM, g.height, 0.5f)
        val open = ControllerTiles.grid(listOf(false, true, false), W.toFloat(), 1, 300f)
        assertNotNull(open.band)
        assertEquals(open.tiles[0].b + GameUiTiles.GAP, open.band!!.t, 0.01f)
    }

    /** A opens the band (the cursor stays on the tile); Down into the choices; A writes ONE choice (CastSettings, as the old row);
     *  B closes the band with the cursor back on the tile, B again the tab row, B again Home */
    @Test fun aOpensTheBandAndBStepsBack() {
        val r = room(); hold(r)
        var left = false
        r.pad.onLeave = { left = true }
        CastSettings.abxyLocation = CastSettings.AbxyLocation.NINTENDO
        press(r, "PADDDOWN")                                             // the Buttons heading (the tab still folds: pick 5)
        press(r, "PADDDOWN"); press(r, "PADDLEFT"); press(r, "PADDLEFT")  // the row's nearest tile, then left to its first
        val tile = tileBoxes(r).getValue("abxy")
        assertEquals(tile, r.pad.focusBox())
        press(r, "PAD1")
        assertEquals("abxy", openTile(r))
        assertEquals(tile, r.pad.focusBox())
        press(r, "PADDDOWN")
        assertTrue("into the band", r.pad.focusBox()!!.top > tile.bottom)
        press(r, "PAD1")                                                 // Xbox (the first choice)
        assertEquals(CastSettings.AbxyLocation.XBOX, CastSettings.abxyLocation)
        assertEquals("abxy", openTile(r))
        press(r, "PAD2")
        assertNull(openTile(r))
        assertEquals(tile, r.pad.focusBox())
        press(r, "PAD2"); assertTrue(r.pad.onTabRow); assertFalse(left)
        press(r, "PAD2"); assertTrue("B on the tab row leaves (Home)", left)
    }

    /** a wheel's band: Open (Tap / Hold), Pick (A / Release), the Pause menu's Over game windows; each the old row's HomeSettings value */
    @Test fun wheelBandsWriteTheirSettings() {
        val r = room(); openAll(r)
        HomeSettings.pauseTap = true; HomeSettings.pausePickA = true; HomeSettings.pauseOverGame = false; HomeSettings.classTapR = false
        val p = partOf(r, "pause")
        assertEquals(listOf(ControllerTiles.OPEN, ControllerTiles.PICK, ControllerTiles.OVER), p.lines("pause").map { it.key })
        assertEquals(listOf(ControllerTiles.OPEN, ControllerTiles.PICK), p.lines("item").map { it.key })
        // Pick: the A glyph alone (no "A" word beside it), then the word Release (no stick glyph that reads as the R shoulder)
        val pick = p.lines("classL")[1]
        assertEquals(listOf("", "Release"), pick.words)
        assertEquals(listOf("a", null), pick.glyphs)
        p.press("pause", ControllerTiles.OPEN, 1); assertFalse(HomeSettings.pauseTap)
        p.press("pause", ControllerTiles.PICK, 1); assertFalse(HomeSettings.pausePickA)
        p.press("pause", ControllerTiles.OVER, 1); assertTrue(HomeSettings.pauseOverGame)
        p.press("classR", ControllerTiles.OPEN, 0); assertTrue(HomeSettings.classTapR)
        // a tap on the Item wheel's tile opens its band; a tap on its Hold writes radialTap
        HomeSettings.radialTap = true
        val t = p.tileBox("item")!!
        tap(p, t.centerX(), t.centerY()); lay(r)
        assertEquals("item", openTile(r))
        val holdBox = p.focusTargets()[p.tileIds().size + 1]                     // the band's first line: Tap, Hold
        tap(p, holdBox.centerX(), holdBox.centerY()); lay(r)
        assertFalse(HomeSettings.radialTap)
        HomeSettings.pauseTap = true; HomeSettings.pausePickA = true; HomeSettings.pauseOverGame = false; HomeSettings.radialTap = true
        HomeSettings.classTapR = false
    }

    /** the combat helpers are the add-on's switches: Top's badges; a choice is GameUi's per-character switch; the three-way Auto Shot;
     *  Attack on cast through HomeAttackSwitch; a class without a res or Auto Shot has no such tile */
    @Test fun combatHelpersAreTheGamesSwitches() {
        val r = room(); game(); openAll(r)
        val c = partOf(r, GameUi.ATK_ID)
        c.refresh(); lay(r)
        assertEquals(GameUiTiles.Badge.ON, c.badge(GameUi.PMODE_ID))
        assertEquals(GameUiTiles.Badge.OFF, c.badge(GameUi.ATK_ID))
        c.press(GameUi.PMODE_ID, GameUi.PMODE_ID, 1)
        assertFalse(GameUi.swWant(GameUi.SW_PMODE))
        assertEquals(GameUiTiles.Badge.SENT, c.badge(GameUi.PMODE_ID))
        assertEquals(listOf("Off", "On", "Smart"), c.lines(GameUi.SHOT_ID).single().words)
        c.press(GameUi.SHOT_ID, GameUi.SHOT_ID, 2)
        assertEquals(2, GameUi.shotMode())
        assertNull(c.badge("attackcast"))
        assertEquals("On", c.value("attackcast"))
        // a warrior: no Smart Res; Auto Shot stays (a Warrior's bow, a Mage's wand); a class that can never shoot has no Auto Shot (0.4)
        GameUi::class.java.getDeclaredField("playerClass").apply { isAccessible = true }.set(GameUi, "WARRIOR")
        assertEquals(listOf(GameUi.PMODE_ID, GameUi.ATK_ID, "attackcast", GameUi.SHOT_ID), c.tileIds())
        GameUi::class.java.getDeclaredField("playerClass").apply { isAccessible = true }.set(GameUi, "MAGE")
        assertTrue(GameUi.SHOT_ID in c.tileIds())
        for (cls in listOf("SHAMAN", "PALADIN", "DRUID", "DEATHKNIGHT")) {
            GameUi::class.java.getDeclaredField("playerClass").apply { isAccessible = true }.set(GameUi, cls)
            assertFalse(cls, GameUi.SHOT_ID in c.tileIds())
        }
    }

    /** Smart Y: dim "..." before the game; On / Off through Smart Y's own taps; "List" opens the list as a sub-page that lands on its
     *  first stop (the back chip is no stop); B back on the Smart Y tile; L / R leave it closed */
    @Test fun smartYListIsASubPage() {
        val r = room(); hold(r)
        val b = partOf(r, "smarty")
        assertTrue(b.waiting("smarty"))
        doc = smartDoc(on = false); b.refresh(); lay(r)
        assertEquals("Off", b.value("smarty"))
        b.press("smarty", "smarty", 0)
        assertEquals(listOf(listOf("smarty" to 6)), sent)                 // the doc's On cell
        assertNull(b.status("smarty"))
        // the game not taking the taps: the old row's note on the band's status line
        live = false; b.press("smarty", "smarty", 0)
        assertEquals("Game not live", b.status("smarty"))
        live = true; b.press("smarty", "smarty", 0)
        assertNull(b.status("smarty"))
        sent.clear()
        doc = smartDoc(on = true); b.refresh(); lay(r)
        // the cursor onto Smart Y, A, down to the band's List, A
        press(r, "PADDDOWN"); press(r, "PADDDOWN"); press(r, "PADDRIGHT"); press(r, "PADDRIGHT"); press(r, "PADDRIGHT")
        val tile = tileBoxes(r).getValue("smarty")
        assertEquals(tile, r.pad.focusBox())
        press(r, "PAD1")
        assertEquals("smarty", openTile(r))
        val listLine = b.focusTargets().last()                               // the band's last button: List
        repeat(4) { if (r.pad.focusBox()?.let { f -> Math.abs(f.centerY() - (listLine.centerY() + contentTop(r, b)!!)) < 1f } != true) press(r, "PADDDOWN") }
        press(r, "PAD1")
        assertEquals(View.VISIBLE, r.smartYPage.visibility)
        assertTrue(r.groupBoxes.all { it.box.visibility == View.GONE })
        assertNull(openTile(r))
        val first = r.smartYPage.firstStop()!!.let { RectF(it).apply { offset(0f, contentTop(r, r.smartYPage.list)!!) } }
        assertEquals(first, r.pad.focusBox())
        val chip = r.smartYPage.strip.chip()
        assertTrue("the back chip is no stop", r.pad.targetBoxes().none { it.centerY() < chip.bottom + 1f && it.left < chip.right })
        press(r, "PAD2")
        assertEquals(View.GONE, r.smartYPage.visibility)
        assertEquals(tileBoxes(r).getValue("smarty"), r.pad.focusBox())
        // L / R step the tab and leave the page closed
        b.press("smarty", ControllerTiles.LIST, 0); lay(r)
        assertEquals(View.VISIBLE, r.smartYPage.visibility)
        press(r, "PADRSHOULDER")
        assertEquals(View.GONE, r.smartYPage.visibility)
        // the back chip (touch) closes it too
        r.show(SettingsTabs.Tab.CONTROLLER); lay(r); b.press("smarty", ControllerTiles.LIST, 0); lay(r)
        tap(r.smartYPage.strip, chip.centerX(), chip.centerY()); lay(r)
        assertEquals(View.GONE, r.smartYPage.visibility)
    }

    /** B: pet follow only for a pet class; its choice is the old row's, per character */
    @Test fun petFollowOnlyForAPetClass() {
        val r = room()
        val b = partOf(r, "abxy")
        assertFalse("petb" in b.tileIds())
        pet = PadMapModel.PetB(on = true, cls = true, pet = true, bound = true); GameUi.character = "Sample-Realm"
        b.refresh(); lay(r)
        assertTrue("petb" in b.tileIds())
        b.press("petb", "petb", 1)
        assertFalse(HomeSettings.petFollowB("Sample-Realm"))
        assertEquals("Off", b.value("petb"))
        b.press("petb", "petb", 0)
        assertTrue(HomeSettings.petFollowB("Sample-Realm"))
    }

    /** every tile has a picture of its own, with the kit and without (our drawing stands in) */
    @Test fun everyTileHasAPicture() {
        for (kit in listOf(true, false)) {
            room(kit)
            val bmp = Bitmap.createBitmap(438, 220, Bitmap.Config.ARGB_8888)
            val prints = HashMap<List<Int>, String>()
            for (id in ControllerPictures.IDS) {
                bmp.eraseColor(Color.TRANSPARENT)
                assertTrue(id, ControllerPictures.draw(Canvas(bmp), app, id, RectF(0f, 0f, 438f, 220f), ControllerPictures.Look(t = 1.3f)))
                // a picture of its own: no two ids draw the same thing (a sample of the pixels)
                val print = ArrayList<Int>()
                for (y in 0 until 220 step 6) for (x in 0 until 438 step 6) print.add(bmp.getPixel(x, y))
                assertNull("$id draws as ${prints[print]} (kit=$kit)", prints[print])
                prints[print] = id
            }
        }
    }

    // ---------------------------------------------------------------- the renders (CTL_SHOTS_DIR)

    @Test fun shots() {
        val out = System.getenv("CTL_SHOTS_DIR")?.let { File(it) } ?: return
        out.mkdirs()
        val made = ArrayList<Pair<String, Bitmap>>()
        var r = room()
        val sc = SettingsRoom::class.java.getDeclaredField("scroll")
        fun scroll(to: Int) { (sc.apply { isAccessible = true }.get(r) as android.widget.ScrollView).scrollTo(0, to); lay(r) }
        fun shot(name: String) {
            lay(r); Thread.sleep(250); lay(r)
            val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(Theme.ink); r.draw(c)
            File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            made.add(name to b)
        }
        fun reveal(id: String) {
            val p = partOf(r, id); val top = contentTop(r, p)!!
            val t = tileBoxes(r).getValue(id)
            val bottom = p.bandBox()?.let { it.bottom + top } ?: t.bottom
            val sv = sc.apply { isAccessible = true }.get(r) as android.widget.ScrollView
            GameUiTiles.reveal(sv.scrollY.toFloat(), sv.height.toFloat(), t.top - SettingsPad.REVEAL_TOP, bottom + SettingsPad.REVEAL_BOTTOM)
                ?.let { scroll(it.toInt().coerceAtLeast(0)) }
        }
        fun open(id: String) {
            val p = partOf(r, id); val t = tileBoxes(r).getValue(id)
            tap(p, t.centerX(), t.centerY() - contentTop(r, p)!!); lay(r)
            reveal(id)
            Thread.sleep(400); org.robolectric.shadows.ShadowLooper.idleMainLooper(); lay(r)
        }
        fun close() { r.ctlTiles.forEach { it.closeSelection() }; lay(r) }
        shot("ctl_1_no_game")
        openAll(r); shot("ctl_2_all_groups_no_game")
        game(); doc = smartDoc(); pet = PadMapModel.PetB(on = true, cls = true, pet = true, bound = true)
        GameUi.setSwitch(GameUi.SW_ATK, true)                                   // sent, the game not caught up: the hourglass
        r.ctlTiles.forEach { it.refresh() }; lay(r)
        scroll(0); shot("ctl_3_reported")
        scroll(700); shot("ctl_4_reported_lower")
        scroll(0)
        open("abxy"); shot("ctl_5_band_abxy")
        close(); open("glyphs"); shot("ctl_6_band_glyphs")
        close(); open("petb"); shot("ctl_7_band_pet_follow")
        close(); open("smarty"); shot("ctl_8_band_smart_y")
        close(); open("pause"); shot("ctl_10_band_pause")
        close(); open("item"); shot("ctl_11_band_item_wheel")
        close(); open("classL"); shot("ctl_12_band_left_class")
        close(); open(GameUi.PMODE_ID); shot("ctl_13_band_party_mode")
        close(); open(GameUi.ATK_ID); shot("ctl_14_band_start_attack_sent")
        close(); open("attackcast"); shot("ctl_15_band_attack_on_cast")
        close(); open(GameUi.SHOT_ID); shot("ctl_16_band_auto_shot")
        close(); open(GameUi.SRES_ID); shot("ctl_17_band_smart_res")
        // a Shaman: the Combat helpers row without Auto Shot (a class that can never shoot), then back to the Hunter
        close(); GameUi::class.java.getDeclaredField("playerClass").apply { isAccessible = true }.set(GameUi, "SHAMAN")
        r.ctlTiles.forEach { it.refresh() }; lay(r); reveal(GameUi.ATK_ID); shot("ctl_28_shaman_no_auto_shot")
        game(); r.ctlTiles.forEach { it.refresh() }; lay(r)
        // the cursor on an open tile, then in its band (the gold box; the open tile's lit edge), then B back on the tile
        close(); scroll(0); hold(r); openAll(r); press(r, "PADDDOWN"); press(r, "PADDDOWN"); press(r, "PADDLEFT"); press(r, "PAD1"); reveal("glyphs")
        shot("ctl_18_cursor_on_open_tile")
        press(r, "PADDDOWN"); shot("ctl_19_cursor_in_band")
        press(r, "PAD2"); shot("ctl_20_b_back_on_tile")
        // the Smart Y list as the pad reaches it: the cursor (gold box) on the list's first row
        close(); scroll(0); partOf(r, "smarty").press("smarty", ControllerTiles.LIST, 0); lay(r); shot("ctl_9_smart_y_list_subpage")
        press(r, "PAD2"); shot("ctl_9b_subpage_b_back_on_smart_y")
        // Spanish and French
        for (l in listOf("es", "fr")) {
            listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
                .firstOrNull { it.canRead() }?.let { Tr.load(l, it.readText()) }
            Tr.setChoice(app, l); close(); scroll(0); shot("ctl_21_$l")
            open("pause"); shot("ctl_22_${l}_band")
        }
        Tr.setChoice(app, "auto")
        // the themes: the tiles, the bands and the pictures in an OLED, a Dark and the Classic look (the wells are the game's world)
        for (th in listOf("oled_bronze", "oled_rose", "dark_silver", "classic")) {
            Theme.use(th); r.invalidate(); close(); scroll(0); shot("ctl_25_theme_$th")
            open("smarty"); shot("ctl_26_theme_${th}_band_smart_y")
            close(); open("pause"); shot("ctl_27_theme_${th}_band_pause")
        }
        Theme.use(ThemePresets.CLASSIC.id); close()
        // no kit on the phone yet: every picture our own drawing
        r = room(kit = false); game(); openAll(r); r.ctlTiles.forEach { it.refresh() }; lay(r); shot("ctl_23_no_kit")
        open("pause"); shot("ctl_24_no_kit_band")
        pictures(out, "ctl_pictures_nokit")
        art()
        pictures(out, "ctl_pictures")
        sheet(out, made)
    }

    /** every picture at the band's size, and the wide one at its tile's, with its name: the review sheet */
    private fun pictures(out: File, name: String) {
        val ids = ControllerPictures.IDS.toList()
        val cw = 438; val ch = 220; val pad = 14; val label = 30; val cols = 4
        val rows = (ids.size + cols - 1) / cols
        val sheet = Bitmap.createBitmap(cols * (cw + pad) + pad, rows * (ch + label + pad) + pad + 140, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet); c.drawColor(Color.rgb(40, 40, 40))
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f }
        for ((i, id) in ids.withIndex()) {
            val x = pad + (i % cols) * (cw + pad); val y = pad + (i / cols) * (ch + label + pad)
            ControllerPictures.draw(c, app, id, RectF(x.toFloat(), y.toFloat(), (x + cw).toFloat(), (y + ch).toFloat()),
                ControllerPictures.Look(t = 1.3f, shot = 2))
            c.drawText("$id  (${ControllerTiles.NAMES[id]})", x.toFloat(), (y + ch + 24).toFloat(), tp)
        }
        val y = pad + rows * (ch + label + pad)
        ControllerPictures.draw(c, app, ControllerPictures.SMART_Y, RectF(pad.toFloat(), y.toFloat(), pad + 460f, y + 108f), ControllerPictures.Look())
        c.drawText("smarty wide tile 460 x 108", pad + 480f, y + 60f, tp)
        File(out, "$name.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun sheet(out: File, made: List<Pair<String, Bitmap>>) {
        val k = 0.5f
        val cw = (W * k).toInt(); val ch = (H * k).toInt()
        val cols = 3; val rows = (made.size + cols - 1) / cols
        val pad = 12; val label = 34
        val sheet = Bitmap.createBitmap(cols * (cw + pad) + pad, rows * (ch + label + pad) + pad, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.rgb(40, 40, 40))
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f }
        for ((i, e) in made.withIndex()) {
            val x = pad + (i % cols) * (cw + pad); val y = pad + (i / cols) * (ch + label + pad)
            c.drawBitmap(e.second, null, android.graphics.Rect(x, y, x + cw, y + ch), Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawText(e.first, x.toFloat(), (y + ch + 26).toFloat(), tp)
        }
        File(out, "ctl_sheet.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
