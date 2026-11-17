package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * SETTINGS > ADD-ONS AS PICTURE CARDS, off-device (the beautify pass, 2026-11-17; the user: "I REALLY REALLY love what you did with the
 * top screen settings menu with the pictures and want to do something similar to the other menus"): the room built as the host builds
 * it, the Add-ons tab's Import button over ONE row of standard tiles (Polestar, Spoken, Manners; QuestTrail only while a copy is there),
 * the Top screen's geometry, Top's badges, the band under the row with the ONE action first, Uninstall twice, lines that cannot act not
 * drawn, the controller's walk (A opens, the cursor stays on the card, Down into the band, B back on the card, then the tab row).
 *
 * With ADDON_TILES_SHOTS_DIR set it also renders the tab and each band (es / fr, no kit) and a sheet of the pictures; THEME_ART_ROOT =
 * an art root laid out like the app's (E:/wow-build/runtime-art/r1/files).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddonTilesRoomTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val out: File? = System.getenv("ADDON_TILES_SHOTS_DIR")?.let { File(it) }
    private var n = 0
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    private val dirF = ThirdPartyAddons::class.java.getDeclaredField("dir").apply { isAccessible = true }
    private val stateF = ThirdPartyAddons::class.java.getDeclaredField("stateJ").apply { isAccessible = true }
    private val noFetchF = ThirdPartyAddons::class.java.getDeclaredField("noFetch").apply { isAccessible = true }
    private var savedDir: Any? = null
    private var savedState: Any? = null
    private var savedNoFetch = false

    private fun art(root: String? = System.getenv("THEME_ART_ROOT")) {
        val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, root?.let { File(it) })
        UiKit.root = UiKit.root                                         // the kit's caches forget (a font, a texture missing before)
        for (n in listOf("castCache", "castMissing")) when (val o = UiKit::class.java.getDeclaredField(n).apply { isAccessible = true }.get(UiKit)) {
            is MutableMap<*, *> -> o.clear(); is MutableSet<*> -> o.clear()
        }
        Icons.refresh()
        MinimapTiles.refresh()
    }

    @Before fun start() {
        Settings.init(app); SettingsTabs.init(app); GameUi.init(app); PolestarQol.init(app)
        ThirdPartyAddons.init(app)
        savedNoFetch = noFetchF.getBoolean(null)
        noFetchF.setBoolean(null, true)                                 // no downloads here (put back in forget)
        savedDir = dirF.get(ThirdPartyAddons); savedState = stateF.get(ThirdPartyAddons)
        dirF.set(ThirdPartyAddons, null)
        installed()                                                     // nothing in the game
        for (a in ThirdPartyAddons.ALL) if (!a.base) ThirdPartyAddons.setCore(a, false)
        Settings.polestarGuide = null
    }

    /** the add-ons whose core (and [modules]: "<addon>/<module>") the launcher's step has moved in */
    private fun installed(vararg ids: String) {
        val j = JSONObject()
        for (id in ids) {
            val (a, key) = id.split("/").let { ThirdPartyAddons.ALL.first { x -> x.id == it[0] } to it.getOrElse(1) { "core" } }
            j.put("${a.id}/$key", JSONObject().put("in", a.pkgs().first { it.first == key }.second.tag))
        }
        stateF.set(ThirdPartyAddons, j)
    }

    /** what the worker says it is doing for [a]'s package [key] (ThirdPartyAddons' live status, as its renders set it) */
    private fun live(a: ThirdPartyAddons.Addon, key: String, s: ThirdPartyAddons.Status?) {
        @Suppress("UNCHECKED_CAST")
        val m = ThirdPartyAddons::class.java.getDeclaredField("live").apply { isAccessible = true }.get(ThirdPartyAddons) as HashMap<String, ThirdPartyAddons.Status>
        synchronized(m) { if (s == null) m.remove("${a.id}/$key") else m["${a.id}/$key"] = s }
        ThirdPartyAddons::class.java.getDeclaredMethod("changed").apply { isAccessible = true }.invoke(ThirdPartyAddons)
    }

    @After fun forget() {
        for (a in ThirdPartyAddons.ALL) for ((k, _) in a.pkgs()) live(a, k, null)
        for (a in ThirdPartyAddons.ALL) { if (!a.base) ThirdPartyAddons.setCore(a, false); a.modules.forEach { ThirdPartyAddons.setModule(a, it, false) } }
        dirF.set(ThirdPartyAddons, savedDir); stateF.set(ThirdPartyAddons, savedState)
        noFetchF.setBoolean(null, savedNoFetch)
        Theme.use(ThemePresets.CLASSIC.id)
        Settings.polestarGuide = null
        GameUi.character = null
        AddonTiles.kit = true
        Tr.setChoice(app, "auto")
        art(null)
    }

    private fun room(kit: Boolean = true, game: Boolean = false): SettingsRoom {
        if (kit) art() else art(null)
        AddonTiles.kit = kit
        val v = SettingsView(app) { }
        v.loginRow = true
        v.gameRunning = { game }
        return SettingsRoom(app, v) { null }.also { it.show(SettingsTabs.Tab.ADDONS); lay(it) }
    }

    /** ONE scratch canvas for every layout pass (a new 1240 x 935 bitmap per pass ran the test JVM out of native memory) */
    private val scratch by lazy { Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)) }

    private fun lay(r: View) {
        for (k in 0..2) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            r.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
            r.layout(0, 0, W, H)
            r.draw(scratch)
        }
    }

    private fun cards(r: SettingsRoom) = r.addonTiles.single()

    private fun contentTop(r: SettingsRoom, v: View): Float {
        val sc = SettingsRoom::class.java.getDeclaredField("scroll").apply { isAccessible = true }.get(r) as android.widget.ScrollView
        val content = sc.getChildAt(0)
        var y = 0f; var cur: View = v
        while (cur !== content) { y += cur.top; cur = cur.parent as View }
        return y
    }

    /** each card's box in the page's content coordinates, by add-on id */
    private fun cardBoxes(r: SettingsRoom): Map<String, RectF> {
        val p = cards(r); val top = contentTop(r, p)
        return p.cardIds().zip(p.focusTargets()).associate { (id, b) -> id to RectF(b).apply { offset(0f, top) } }
    }

    /** the open band's buttons (the card part's own coordinates) */
    private fun bandButtons(r: SettingsRoom): List<RectF> = cards(r).let { it.focusTargets().drop(it.cardIds().size) }

    private fun tap(v: View, b: RectF) {
        val d = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, b.centerX(), b.centerY(), 0); v.dispatchTouchEvent(d); d.recycle()
        val u = MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, b.centerX(), b.centerY(), 0); v.dispatchTouchEvent(u); u.recycle()
    }
    private fun open(r: SettingsRoom, id: String) { val p = cards(r); tap(p, p.focusTargets()[p.cardIds().indexOf(id)]); lay(r) }

    private fun press(r: SettingsRoom, k: String) { r.pad.onPad(JSONObject().put("k", k).put("n", ++n)); lay(r) }
    private fun hold(r: SettingsRoom) {
        r.pad.enter(); r.show(SettingsTabs.Tab.ADDONS, force = true)
        r.pad.onGuestAck(JSONObject().put("op", "hold").put("ok", true)); lay(r)
    }

    private fun shot(r: SettingsRoom, name: String, scrollTo: Float = 0f) {
        val d = out ?: return
        lay(r)
        val sc = SettingsRoom::class.java.getDeclaredField("scroll").apply { isAccessible = true }.get(r) as android.widget.ScrollView
        sc.scrollTo(0, scrollTo.toInt()); lay(r)
        Thread.sleep(300); lay(r)                                       // the map tile decodes off the UI thread
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Theme.ink); r.draw(c)
        d.mkdirs()
        File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun lang(l: String) {
        if (l != "en") {
            val f = listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json")).first { it.canRead() }
            // this area's new words (the integrator merges them into es / fr; ADDON_TILES_I18N = scratchpad/res/i18n/addons.json)
            val j = JSONObject(f.readText())
            System.getenv("ADDON_TILES_I18N")?.let { File(it) }?.takeIf { it.canRead() }?.let { add ->
                val plain = j.getJSONObject("plain"); val a = JSONObject(add.readText())
                for (k in a.keys()) a.getJSONObject(k).optString(l).takeIf { it.isNotEmpty() }?.let { plain.put(k, it) }
            }
            Tr.load(l, j.toString())
        }
        Tr.setChoice(app, l)
    }

    // ---------------------------------------------------------------- the tab

    /** Import first, then ONE headless row of standard cards (232 x 184, the Top screen's numbers), no heading over them */
    @Test fun addOnsAreOneRowOfStandardCards() {
        Settings.polestarGuide = PolestarGuide.ON; installed("polestar")
        val r = room()
        val gb = r.groupBoxes.first { it.group == SettingsTabs.Group.CARDS }
        assertEquals(View.VISIBLE, gb.box.visibility)
        assertEquals("no heading", View.GONE, gb.heading.visibility)
        assertTrue(SettingsTabs.Group.CARDS in r.open)
        assertEquals(listOf("polestar", "spoken", "manners"), cards(r).cardIds())
        val boxes = cardBoxes(r).values.toList()
        for (b in boxes) { assertEquals(GameUiTiles.TILE_W, b.width(), 0.5f); assertEquals(GameUiTiles.TILE_H, b.height(), 0.01f) }
        assertEquals("one row", 1, boxes.map { it.top }.distinct().size)
        assertEquals(16f, boxes[0].left, 0.5f)
        // under the Import button
        val imp = r.groupBoxes.first { it.group == SettingsTabs.Group.IMPORT }.box
        assertTrue(boxes[0].top >= imp.bottom)
        // the lists fold, and open folded (the cards are the tab's content)
        assertEquals(setOf(SettingsTabs.Group.IMPORT, SettingsTabs.Group.CARDS), r.open)
        shot(r, "add_01_tab")
    }

    /** Top's badges (C2) and the value words: installed = ON, on its way = SENT, failed nothing new, not installed = none + dim */
    @Test fun badgesAreTopsSet() {
        assertNull(AddonCards.badge(ThirdPartyAddons.SPOKEN))
        assertTrue(AddonCards.dimmed(ThirdPartyAddons.SPOKEN))
        assertEquals("70 MB", AddonCards.value(ThirdPartyAddons.SPOKEN))
        installed("spoken"); ThirdPartyAddons.setCore(ThirdPartyAddons.SPOKEN, true)
        assertEquals(GameUiTiles.Badge.ON, AddonCards.badge(ThirdPartyAddons.SPOKEN))
        assertFalse(AddonCards.dimmed(ThirdPartyAddons.SPOKEN))
        assertEquals("Installed", AddonCards.value(ThirdPartyAddons.SPOKEN))
        ThirdPartyAddons.setCore(ThirdPartyAddons.MANNERS, true)
        live(ThirdPartyAddons.MANNERS, "core", ThirdPartyAddons.Status(ThirdPartyAddons.Kind.DOWNLOADING, "Downloading", 42))
        assertEquals(GameUiTiles.Badge.SENT, AddonCards.badge(ThirdPartyAddons.MANNERS))
        assertEquals("42%", AddonCards.value(ThirdPartyAddons.MANNERS))
        assertTrue("on its way in: still Off, dimmed", AddonCards.dimmed(ThirdPartyAddons.MANNERS))
        // moving out at the next start: Off, dimmed, the hourglass
        ThirdPartyAddons.setCore(ThirdPartyAddons.SPOKEN, false)
        assertTrue(AddonCards.dimmed(ThirdPartyAddons.SPOKEN))
        assertEquals("Next start", AddonCards.value(ThirdPartyAddons.SPOKEN))
        // nothing to press while it downloads (its % is the status line)
        assertTrue(AddonCards.lines(ThirdPartyAddons.MANNERS, AddonCards.Env()).isEmpty())
    }

    /** not installed: the band is its ONE action (Install, with its size); its own lines only once it is in */
    @Test fun notInstalledBandIsInstallAlone() {
        val r = room()
        open(r, "spoken")
        assertEquals("spoken", cards(r).openCard)
        assertEquals(listOf("core"), cards(r).bandKeys())
        shot(r, "add_02_band_spoken_not_installed")
        // Install: one tap, the old row's write
        tap(cards(r), bandButtons(r).single()); lay(r)
        assertTrue(ThirdPartyAddons.coreWanted(ThirdPartyAddons.SPOKEN))
        // waiting to download: Cancel; no word repeated (the value says Waiting, the status line nothing more)
        assertEquals(listOf("core"), cards(r).bandKeys())
        val l = AddonCards.lines(ThirdPartyAddons.SPOKEN, AddonCards.Env()).single()
        assertEquals("Cancel", l.words.single())
        assertEquals("Version", l.label)
        assertEquals("Waiting", AddonCards.value(ThirdPartyAddons.SPOKEN))
        assertEquals("", AddonCards.status(ThirdPartyAddons.SPOKEN, AddonCards.Env()))
        shot(r, "add_03_band_spoken_waiting")
    }

    /** installed: Uninstall first (twice within 3 s), then the modules and the stop rules; a module On downloads, its % its value */
    @Test fun installedBandUninstallsTwice() {
        installed("spoken", "spoken/quests"); ThirdPartyAddons.setCore(ThirdPartyAddons.SPOKEN, true)
        ThirdPartyAddons.setModule(ThirdPartyAddons.SPOKEN, ThirdPartyAddons.SPOKEN.modules[0], true)
        ThirdPartyAddons.setModule(ThirdPartyAddons.SPOKEN, ThirdPartyAddons.SPOKEN.modules[1], true)
        live(ThirdPartyAddons.SPOKEN, "zones", ThirdPartyAddons.Status(ThirdPartyAddons.Kind.DOWNLOADING, "Downloading", 37))
        val r = room()
        open(r, "spoken")
        assertEquals(listOf("core", "m.quests", "m.zones", "m.books", "o.stopClose", "o.stopAccept"), cards(r).bandKeys())
        val zones = AddonCards.lines(ThirdPartyAddons.SPOKEN, AddonCards.Env()).first { it.key == "m.zones" }
        assertEquals("37%", zones.value)
        shot(r, "add_04_band_spoken_installed")
        shot(r, "add_04b_band_spoken_installed_foot", scrollTo = 400f)
        // Uninstall: the first tap arms it ("Tap again"), the second does it
        tap(cards(r), bandButtons(r).first()); lay(r)
        assertTrue(ThirdPartyAddons.coreWanted(ThirdPartyAddons.SPOKEN))
        shot(r, "add_05_band_uninstall_armed")
        tap(cards(r), bandButtons(r).first()); lay(r)
        assertFalse(ThirdPartyAddons.coreWanted(ThirdPartyAddons.SPOKEN))
        // moving out at the next start: Keep it alone (its own lines go with it: Off = dimmed, nothing to set), the badge the hourglass
        assertEquals(GameUiTiles.Badge.SENT, AddonCards.badge(ThirdPartyAddons.SPOKEN))
        assertEquals("Keep it", AddonCards.lines(ThirdPartyAddons.SPOKEN, AddonCards.Env()).first().words.single())
        assertEquals(listOf("core"), cards(r).bandKeys())
    }

    /** Polestar: the same band as every card - Version + Install / Uninstall (twice), which IS the guide's choice (PolestarGuide), then
     *  the QoL switches only with a character; Restart only while the game runs and a change waits for it */
    @Test fun polestarBand() {
        Settings.polestarGuide = PolestarGuide.ON; installed("polestar")
        GameUi.character = "Sample-Realm"
        val r = room(game = true)
        open(r, "polestar")
        assertEquals(listOf("core", "pq.1", "pq.2", "pq.4"), cards(r).bandKeys())
        assertEquals("Uninstall", AddonCards.lines(ThirdPartyAddons.POLESTAR, AddonCards.Env()).first().words.single())
        shot(r, "add_06_band_polestar")
        // Auto vendor On (per character)
        val b = bandButtons(r)
        tap(cards(r), b[3]); lay(r)                                     // Uninstall, quest On, Off, vendor On
        assertTrue(PolestarQol.on("Sample-Realm", PolestarQol.VENDOR))
        // no character: no per-character lines (no dead stops)
        GameUi.character = null; lay(r)
        assertEquals(listOf("core"), cards(r).bandKeys())
        // Uninstall twice = the guide Off: it moves out at the next start; Keep it, and Restart game while the game runs
        tap(cards(r), bandButtons(r)[0]); lay(r)
        assertEquals(PolestarGuide.ON, Settings.polestarGuide)
        tap(cards(r), bandButtons(r)[0]); lay(r)
        assertEquals(PolestarGuide.OFF, Settings.polestarGuide)
        assertEquals(listOf("core", "restart"), cards(r).bandKeys())
        assertEquals("Keep it", AddonCards.lines(ThirdPartyAddons.POLESTAR, AddonCards.Env(gameRunning = true)).first().words.single())
        assertTrue("Off: dimmed", AddonCards.dimmed(ThirdPartyAddons.POLESTAR))
        assertEquals("", AddonCards.status(ThirdPartyAddons.POLESTAR, AddonCards.Env(gameRunning = true)))
        shot(r, "add_07_band_polestar_restart")
        // Keep it: back On (one tap)
        tap(cards(r), bandButtons(r)[0]); lay(r)
        assertEquals(PolestarGuide.ON, Settings.polestarGuide)
    }

    /** QuestTrail: only while a copy is there, last (so it never leaves a hole); its band its Uninstall */
    @Test fun questTrailCardOnlyWhileThere() {
        installed("questtrail"); ThirdPartyAddons.setCore(ThirdPartyAddons.QUESTTRAIL, true)
        Settings.polestarGuide = PolestarGuide.ON; installed("polestar", "questtrail")
        val r = room()
        assertEquals(listOf("polestar", "spoken", "manners", "questtrail"), cards(r).cardIds())
        open(r, "questtrail")
        assertEquals(listOf("core"), cards(r).bandKeys())
        shot(r, "add_08_band_questtrail")
    }

    /** a download under way: the card's value its %, the hourglass, nothing to press in the band, its % in the status line */
    @Test fun downloadingCard() {
        ThirdPartyAddons.setCore(ThirdPartyAddons.MANNERS, true)
        live(ThirdPartyAddons.MANNERS, "core", ThirdPartyAddons.Status(ThirdPartyAddons.Kind.DOWNLOADING, "Downloading", 42))
        val r = room()
        open(r, "manners")
        assertEquals(emptyList<String>(), cards(r).bandKeys())
        assertEquals("the % is the value word; the status line only its bar", "", AddonCards.status(ThirdPartyAddons.MANNERS, AddonCards.Env()))
        shot(r, "add_09_band_manners_downloading")
    }

    // ---------------------------------------------------------------- the controller

    /** Down from the tabs: Import, then the cards; A opens a band with the cursor on its card; Down into the band; B back on the card;
     *  B again the tab row */
    @Test fun aOpensTheBandAndBStepsBack() {
        installed("manners"); ThirdPartyAddons.setCore(ThirdPartyAddons.MANNERS, true)
        val r = room(); hold(r)
        var left = false
        r.pad.onLeave = { left = true }
        assertTrue(r.pad.onTabRow)
        shot(r, "add_00_landing")                                       // the room's landing: the tab row (core's SettingsPad)
        press(r, "PADDDOWN")                                            // Import
        press(r, "PADDDOWN")                                            // the cards' row, the card nearest in x (the middle one)
        val boxes = cardBoxes(r)
        val manners = boxes.getValue("manners")
        assertEquals(manners, r.pad.focusBox())
        shot(r, "add_14_cursor_on_card")
        press(r, "PADDLEFT"); press(r, "PADDLEFT")
        assertEquals(boxes.values.first(), r.pad.focusBox())
        press(r, "PADDLEFT")                                            // the row's end: it stays
        assertEquals(boxes.values.first(), r.pad.focusBox())
        press(r, "PADDRIGHT"); press(r, "PADDRIGHT")
        assertEquals(manners, r.pad.focusBox())
        press(r, "PAD1")
        assertEquals("manners", cards(r).openCard)
        assertEquals("the cursor stays on the card", manners, r.pad.focusBox())
        shot(r, "add_15_cursor_on_open_card")
        press(r, "PADDDOWN")
        val inBand = r.pad.focusBox()!!
        assertTrue("into the band", inBand.top > manners.bottom)
        shot(r, "add_10_cursor_in_band")
        press(r, "PAD2")
        assertNull(cards(r).openCard)
        assertEquals("back on its card", manners, r.pad.focusBox())
        press(r, "PAD2")
        assertTrue(r.pad.onTabRow)
        assertFalse(left)
        // every card is a stop
        val stops = r.pad.targetBoxes()
        for ((id, b) in cardBoxes(r)) assertTrue("$id is a stop", stops.any { it == b })
    }

    /** one band in the room: a card's band closes when another opens, and leaving the tab closes it */
    @Test fun oneBandAtATime() {
        val r = room()
        open(r, "spoken"); open(r, "manners")
        assertEquals("manners", cards(r).openCard)
        open(r, "manners")
        assertNull(cards(r).openCard)
        open(r, "spoken")
        r.show(SettingsTabs.Tab.TOP); lay(r)
        assertNull(cards(r).openCard)
    }

    // ---------------------------------------------------------------- the themes (shots only)

    /** the tab and an open band under the OLED and dark presets (there is no light preset: ThemePresets.ALL is Classic, OLED x7,
     *  Dark x3) - the themes area's own render set: OLED Bronze, OLED Rose, Dark Silver */
    @Test fun shotsThemes() {
        out ?: return
        installed("spoken", "polestar"); ThirdPartyAddons.setCore(ThirdPartyAddons.SPOKEN, true); Settings.polestarGuide = PolestarGuide.ON
        for (t in listOf("oled_bronze", "oled_rose", "dark_silver")) {
            Theme.use(t)
            val r = room()
            shot(r, "add_16_${t}_tab")
            open(r, "spoken"); shot(r, "add_16_${t}_band")
        }
    }

    // ---------------------------------------------------------------- the pictures (shots only)

    /** es / fr, no kit, and a sheet of the four pictures at band size, kit and no kit */
    @Test fun shotsLanguagesNoKitAndSheet() {
        val d = out ?: return
        installed("spoken"); ThirdPartyAddons.setCore(ThirdPartyAddons.SPOKEN, true)
        Settings.polestarGuide = PolestarGuide.ON; installed("polestar", "spoken")
        for (l in listOf("es", "fr")) {
            lang(l)
            val r = room(); open(r, "spoken"); shot(r, "add_11_$l")
        }
        lang("en")
        val nk = room(kit = false); shot(nk, "add_12_nokit")
        open(nk, "spoken"); shot(nk, "add_13_nokit_band")
        // the sheet: each picture at the band's 438 x 220, kit (top row) and no kit (bottom row)
        installed("polestar", "spoken", "manners", "questtrail"); ThirdPartyAddons.setCore(ThirdPartyAddons.QUESTTRAIL, true)
        val sheet = Bitmap.createBitmap(4 * 458 + 20, 2 * 260 + 20, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet); c.drawColor(Theme.ink)
        val p = Paint().apply { color = android.graphics.Color.WHITE; textSize = 18f }
        for ((row, kit) in listOf(true, false).withIndex()) {
            if (kit) art() else art(null)
            val adds = listOf(ThirdPartyAddons.POLESTAR, ThirdPartyAddons.SPOKEN, ThirdPartyAddons.MANNERS, ThirdPartyAddons.QUESTTRAIL)
            repeat(2) {
                for ((i, a) in adds.withIndex()) {
                    val r = RectF(20f + i * 458f, 30f + row * 260f, 20f + i * 458f + 438f, 30f + row * 260f + 220f)
                    AddonPictures.draw(c, app, a, r, kit = kit)
                    c.drawText("${a.title}${if (kit) "" else " (no kit)"}", r.left, r.top - 8f, p)
                }
                org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(300); org.robolectric.shadows.ShadowLooper.idleMainLooper()
            }
        }
        d.mkdirs()
        File(d, "add_pictures_sheet.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
