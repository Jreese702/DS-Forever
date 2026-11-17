package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * pm1, THE MAP ROOM'S POLESTAR ROW OFF THE DEVICE (the user, 2026-11-16: "The polestar option in the map room, I want x to open the
 * polestar menu and y to open skip options to go to the next or previous step"): the real Map room on a made-up zone picture, its quest
 * list's Polestar row, the Quests room's own Polestar menu (QuestsView's [PolestarMenu], fed Map.lua's documents) handed to it as the
 * host does, driven by the presses as Map.lua relays them ([WorldMapRoomView.onMapPad]). The checks run always; the pictures only when
 * PSMAP_SHOTS_DIR is set (PSMAP_ART_ROOT = an art root laid out like the app's: its ui/ and fonts/ are copied once under build/).
 *   PSMAP_SHOTS_DIR=<dir> PSMAP_ART_ROOT=<root> gradlew :app:testModernDebugUnitTest --tests "*MapPolestarShotsTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MapPolestarShotsTest {
    private val out: File? = System.getenv("PSMAP_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    /** an art root: the kit's ui / fonts (when PSMAP_ART_ROOT is set) and one made-up zone, Elwynn Forest (1429) */
    private fun art() {
        val root = File("build/tmp/psmap-art").absoluteFile
        System.getenv("PSMAP_ART_ROOT")?.let { src ->
            for (d in listOf("ui", "fonts")) if (!File(root, d).isDirectory) File(src, d).takeIf { it.isDirectory }?.copyRecursively(File(root, d))
        }
        val wm = File(root, "worldmap/1429").apply { mkdirs() }
        File(root, "worldmap/manifest.json").writeText(JSONObject().put("1429", JSONObject()).toString())
        File(wm, "index.json").writeText(JSONObject().put("uiMapID", 1429).put("name", "Elwynn Forest").put("mapType", 3).put("parent", 0)
            .put("w", 1002).put("h", 668).toString())
        val pic = File(wm, "base.webp")
        if (!pic.isFile) {
            val b = Bitmap.createBitmap(1002, 668, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 1002f, 668f, Color.rgb(74, 96, 52), Color.rgb(40, 58, 34), Shader.TileMode.CLAMP) })
            val road = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 124, 84); strokeWidth = 18f; style = Paint.Style.STROKE }
            c.drawLine(80f, 600f, 920f, 120f, road); c.drawLine(300f, 80f, 520f, 640f, road)
            pic.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, root)
    }

    private fun lang(l: String) {
        if (l != "en") listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
            .firstOrNull { it.canRead() }?.let { Tr.load(l, it.readText()) }
        Tr.setChoice(app, l)
    }

    private fun questsDoc(): JSONObject {
        val a = JSONArray()
        val log = listOf(Triple(7, "Kobold Camp Cleanup", "Elwynn Forest"), Triple(33, "Wolves Across the Border", "Elwynn Forest"),
            Triple(46, "Bounty on Murlocs", "Elwynn Forest"))
        for ((slot, q) in log.withIndex()) a.put(JSONObject().put("id", q.first).put("title", q.second).put("zone", q.third).put("lv", 8)
            .put("slot", slot + 1).put("watch", 1).put("obj", JSONArray().put(JSONObject().put("t", "Kobold Vermin slain: 4/10").put("have", 4).put("need", 10))))
        return JSONObject().put("list", a).put("plv", 9).put("maxQ", 25)
    }
    private val titles = listOf("Northshire Abbey", "Wolves Across the Border", "Kobold Camp Cleanup", "Investigate Echo Ridge",
        "Brotherhood of Thieves", "Milly Osworth", "Skirmish at Echo Ridge", "Report to Goldshire", "Lost Necklace", "The Fargodeep Mine")
    private fun guide(u: Int) = JSONObject().put("type", "pguide").put("v", "pg1").put("have", true).put("gid", "forever/alliance/1-10/elwynn")
        .put("gs", 444).put("t", "Elwynn Forest").put("z", "Elwynn Forest").put("zm", 1429).put("lo", 1).put("hi", 10).put("n", 10).put("dn", 3)
        .put("tt", 10).put("sk", true).put("u", u).put("tb", true).put("s", 4).put("l", 4).put("st", "dddluuuuuu")
    private fun steps() = JSONObject().put("type", "pgsteps").put("gs", 444).put("n", 10).put("o", 1).put("t", JSONArray(titles))
    private fun stepDoc(i: Int) = JSONObject().put("type", "pgstep").put("gs", 444).put("i", i).put("t", titles[i - 1])
        .put("nt", "Kobolds gather around the camp's fire")
        .put("g", JSONArray().put(JSONObject().put("g", 1).put("s", "active").put("x", "Kill Kobold Vermin").put("c", 4).put("m", 10).put("tk", true))
            .put(JSONObject().put("g", 2).put("s", "pending").put("x", "Talk to Marshal McBride").put("tk", true)))

    private fun list(): JSONObject {
        val g = JSONArray()
        fun add(t: String, f: Int, lo: Int, hi: Int, extra: (JSONObject) -> Unit = {}) =
            g.put(JSONObject().put("t", t).put("f", f).put("st", "n").put("lo", lo).put("hi", hi).put("n", 30).also(extra))
        add("Elwynn Forest", 1, 1, 10) { it.put("st", "s").put("tb", 1).put("sg", 1).put("h", true).put("dn", 3).put("tt", 10).put("zm", 1429) }
        add("Dun Morogh", 1, 1, 10)
        add("Loch Modan", 2, 10, 20) { it.put("sg", 3).put("zm", 1432) }
        add("Westfall", 2, 10, 20) { it.put("sg", 2).put("zm", 1436) }
        return JSONObject().put("type", "pglist").put("ls", 901).put("f", JSONArray(listOf("Alliance 1–10", "Alliance 10–20"))).put("g", g)
    }

    private fun frames(v: View, n: Int = 4): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        for (i in 0 until n) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(16))
            ShadowLooper.idleMainLooper()
            val c = Canvas(b); c.drawColor(Theme.ink); v.draw(c)
        }
        return b
    }
    private fun shot(v: View, name: String) {
        val d = out ?: return
        val b = frames(v)
        d.mkdirs()
        File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun <T> field(v: WorldMapRoomView, name: String): T {
        val f = WorldMapRoomView::class.java.getDeclaredField(name); f.isAccessible = true
        @Suppress("UNCHECKED_CAST") return f.get(v) as T
    }
    private var n = 0
    private fun key(v: WorldMapRoomView, k: String) { v.onMapPad(JSONObject().put("n", ++n).put("k", k)); frames(v, 2) }

    /** the presses the room sent (Skip = false, Previous = true) */
    private val skips = ArrayList<Boolean>()

    /** the Map room on Elwynn Forest, its quest list out, a Polestar step on its top row; [canPrev] = a skip is left to undo */
    private fun room(canPrev: Boolean = true, polestar: Boolean = true): Pair<WorldMapRoomView, QuestsView> {
        art()
        Settings.init(app)
        Settings.polestarGuide = PolestarGuide.ON
        val q = QuestsView(app, { null }, { })
        for (d in listOf(guide(if (canPrev) 1 else 0), steps(), list(), stepDoc(4))) q.onPolestarDoc(d)
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val v = WorldMapRoomView(act)
        act.setContentView(v)
        v.psMenu = q.psMenu                                   // as CompanionHost hands it
        v.polestar = { if (polestar) WorldMapRoomView.Ps("Kobold Camp Cleanup", "Kill Kobold Vermin", canSkip = true, canPrev = canPrev) else null }
        v.polestarHead = { if (polestar) "Polestar · Elwynn Forest" else null }
        v.onPolestarSkip = { prev -> skips.add(prev); true }
        v.loadArt()
        val until = System.currentTimeMillis() + 10_000L
        while (!field<Boolean>(v, "artReady") && System.currentTimeMillis() < until) { ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        assertTrue("the made-up art read", field<Boolean>(v, "artReady"))
        v.onQuests(questsDoc())
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, W, H)
        v.onShown()
        frames(v)
        // the base picture decodes off the UI thread
        val pic = System.currentTimeMillis() + 5_000L
        while (v.pictureBytes == 0L && System.currentTimeMillis() < pic) { ShadowLooper.idleMainLooper(); Thread.sleep(10); frames(v, 1) }
        return v to q
    }
    /** into the list (Right), up onto the Polestar row */
    private fun toRow(v: WorldMapRoomView) {
        key(v, "PADDRIGHT")
        repeat(4) { key(v, "PADDUP") }
        assertEquals("on the Polestar row", 0, field<Int>(v, "listIndex"))
    }

    @Test fun xOpensThePolestarMenuAndBGoesBackToTheMap() {
        lang("en")
        val (v, q) = room()
        toRow(v)
        shot(v, "01_map_room_polestar_row")
        key(v, "PAD3")
        assertTrue("X: the Polestar menu", field(v, "psOpen"))
        assertEquals("on the shown step, as the tab", "s:4", q.psMenu.cur.sel)
        assertEquals("map", q.psMenu.shownIn)
        shot(v, "02_polestar_menu")
        key(v, "PADDDOWN"); key(v, "PADDDOWN")
        assertEquals("s:6", q.psMenu.cur.sel)
        shot(v, "03_polestar_menu_step6")
        // the guide row's A: Change guide (a sub-view), B one step back, B again: the map
        repeat(6) { key(v, "PADDUP") }
        assertEquals("guide", q.psMenu.cur.sel)
        key(v, "PAD1")
        assertEquals(PolestarTab.View.PICKER, q.psMenu.cur.view)
        shot(v, "04_polestar_menu_change_guide")
        key(v, "PAD2")
        assertEquals(PolestarTab.View.LIST, q.psMenu.cur.view)
        assertTrue("B out of a sub-view: still the menu", field(v, "psOpen"))
        key(v, "PAD2")
        assertFalse("B on its list: back to the map", field(v, "psOpen"))
        assertEquals(null, q.psMenu.shownIn)
        assertEquals("still on the row", 0, field<Int>(v, "listIndex"))
        shot(v, "05_back_on_the_map")
    }

    @Test fun yOpensTheSkipChoiceNextAndPrevious() {
        lang("en")
        val (v, _) = room()
        toRow(v)
        key(v, "PAD4")
        assertTrue("Y: the skip choice", field(v, "skipOpen"))
        assertEquals(0, field<Int>(v, "skipSel"))
        shot(v, "06_skip_choice_next")
        key(v, "PADDDOWN")
        assertEquals(1, field<Int>(v, "skipSel"))
        shot(v, "07_skip_choice_previous")
        key(v, "PAD1")
        assertEquals("Previous step: pk1's undo", listOf(true), skips)
        assertFalse(field(v, "skipOpen"))
        key(v, "PAD4"); key(v, "PAD1")
        assertEquals("Next step: pk1's skip", listOf(true, false), skips)
        key(v, "PAD4"); key(v, "PAD2")
        assertFalse("B closes it", field(v, "skipOpen"))
        key(v, "PAD4"); key(v, "PAD4")
        assertFalse("Y again closes it", field(v, "skipOpen"))
        assertEquals(listOf(true, false), skips)
    }

    @Test fun previousIsDimWithNothingToUndo() {
        lang("en")
        val (v, _) = room(canPrev = false)
        toRow(v)
        key(v, "PAD4")
        key(v, "PADDDOWN")
        key(v, "PAD1")
        assertTrue("a dim row: the choice stays", field(v, "skipOpen"))
        assertEquals("nothing sent", emptyList<Boolean>(), skips)
        shot(v, "08_skip_choice_previous_dim")
        lang("es")
        shot(v, "09_skip_choice_es")
        lang("fr")
        key(v, "PADDUP")
        shot(v, "10_skip_choice_fr")
        lang("en")
    }

    @Test fun withoutAPolestarRowXAndYAreTheListsAsBefore() {
        lang("en")
        val (v, _) = room(polestar = false)
        key(v, "PADDRIGHT")
        val rows = field<Int>(v, "listIndex")
        assertTrue("on a quest row", rows >= 0)
        key(v, "PAD4")
        assertFalse(field(v, "skipOpen"))
        assertTrue("Y: the quest's options, as before", field<Int>(v, "optQuest") != 0)
        key(v, "PAD2")
        key(v, "PAD3")
        assertFalse("X: no menu", field(v, "psOpen"))
    }
}
