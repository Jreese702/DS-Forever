package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
 * pg1, THE POLESTAR TAB OFF THE DEVICE (the user, 2026-11-16: "if Polestar is active it should have its own tab at the top of the quests
 * tab"; design_polestar, every recommended pick): the Quests room fed a quests document and Map.lua's Polestar documents, driven by the
 * controller's presses as DirectPad delivers them ([QuestsView.onDirect]). The checks run always; the pictures only when
 * PSTAB_SHOTS_DIR is set (PSTAB_ART_ROOT = an art root laid out like the app's): the steps, the Done fold, the notices, Change guide,
 * Choose your path, Reset's question, no guide, a fight.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PolestarTabShotsTest {
    private val out: File? = System.getenv("PSTAB_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    private fun art() {
        System.getenv("PSTAB_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
    }

    private fun questsDoc(): JSONObject {
        val a = JSONArray()
        val log = listOf(Triple(7, "Kobold Camp Cleanup", "Elwynn Forest"), Triple(33, "Wolves Across the Border", "Elwynn Forest"),
            Triple(46, "Bounty on Murlocs", "Elwynn Forest"), Triple(109, "Report to Gryan Stoutmantle", "Westfall"))
        for ((slot, q) in log.withIndex()) a.put(JSONObject().put("id", q.first).put("title", q.second).put("zone", q.third).put("lv", 8)
            .put("slot", slot + 1).put("watch", 1).put("obj", JSONArray().put(JSONObject().put("t", "Kobold Vermin slain: 4/10").put("have", 4).put("need", 10))))
        return JSONObject().put("list", a).put("plv", 9).put("maxQ", 25)
    }

    private val titles = listOf("Northshire Abbey", "Wolves Across the Border", "Kobold Camp Cleanup", "Investigate Echo Ridge",
        "Brotherhood of Thieves", "Milly Osworth", "Skirmish at Echo Ridge", "Report to Goldshire", "Lost Necklace", "The Fargodeep Mine",
        "Gold Dust Exchange", "Kobold Candles", "Bounty on Murlocs", "Princess Must Die!", "Westfall stew", "Hearth to Goldshire")

    private fun guide(s: Int? = 4, l: Int? = 4, st: String? = "dddluuuuuuuuuuuu", u: Int = 1, extra: (JSONObject) -> Unit = {}): JSONObject {
        val d = JSONObject().put("type", "pguide").put("v", "pg1").put("have", true).put("gid", "forever/alliance/1-10/elwynn").put("gs", 444)
            .put("t", "Elwynn Forest").put("z", "Elwynn Forest").put("zm", 1429).put("lo", 1).put("hi", 10).put("n", 16).put("dn", 3).put("tt", 16)
            .put("sk", true).put("u", u).put("tb", true)
        if (s != null) d.put("s", s)
        if (l != null) d.put("l", l)
        if (st != null) d.put("st", st)
        d.put("tabs", JSONArray().put(JSONObject().put("t", "Elwynn Forest").put("s", 444).put("i", s ?: 1).put("n", 16).put("dn", 3).put("tt", 16))
            .put(JSONObject().put("t", "Fishing").put("s", 77).put("i", 2).put("n", 8).put("dn", 1).put("tt", 8))).put("ta", 1)
        extra(d)
        return d
    }
    private fun steps() = JSONObject().put("type", "pgsteps").put("gs", 444).put("n", 16).put("o", 1).put("t", JSONArray(titles))
    private fun stepDoc(i: Int) = JSONObject().put("type", "pgstep").put("gs", 444).put("i", i).put("t", titles[i - 1])
        .put("nt", if (i == 4) "Kobolds gather around the camp's fire" else JSONObject.NULL)
        .put("g", JSONArray().put(JSONObject().put("g", 1).put("s", "active").put("x", "Kill Kobold Vermin").put("c", 4).put("m", 10).put("tk", true))
            .put(JSONObject().put("g", 2).put("s", "pending").put("x", "Talk to Marshal McBride").put("tk", true))
            .put(JSONObject().put("g", 3).put("s", "active").put("x", "Watch out for the Kobold Laborers")))
    private fun list(): JSONObject {
        val g = JSONArray()
        fun add(t: String, f: Int, lo: Int, hi: Int, extra: (JSONObject) -> Unit = {}) =
            g.put(JSONObject().put("t", t).put("f", f).put("st", "n").put("lo", lo).put("hi", hi).put("n", 30).also(extra))
        add("Elwynn Forest", 1, 1, 10) { it.put("st", "s").put("tb", 1).put("sg", 1).put("h", true).put("dn", 3).put("tt", 16).put("zm", 1429) }
        add("Dun Morogh", 1, 1, 10)
        add("Loch Modan", 2, 10, 20) { it.put("sg", 3).put("zm", 1432) }
        add("Westfall", 2, 10, 20) { it.put("sg", 2).put("zm", 1436) }
        add("Redridge Mountains", 3, 15, 25)
        add("Earth Totem", 4, 4, 10) { it.put("gr", true).put("gl", 24) }
        add("Fishing", 5, 5, 60) { it.put("tb", 2).put("st", "s").put("dn", 1).put("tt", 8) }
        return JSONObject().put("type", "pglist").put("ls", 901)
            .put("f", JSONArray(listOf("Alliance 1–10", "Alliance 10–20", "Alliance 15–25", "Class", "Professions"))).put("g", g)
    }
    private fun route() = JSONObject().put("type", "pgroute").put("fs", 444).put("ft", "Elwynn Forest")
        .put("o", JSONArray().put(JSONObject().put("t", "Westfall").put("z", "Westfall").put("zm", 1436).put("lo", 10).put("hi", 20).put("sec", 130)
            .put("q", 28).put("rec", true).put("pt", "Farmland overrun by the Defias Brotherhood, and a road south to Duskwood.")
            .put("pr", JSONArray(listOf("Close to Goldshire", "Many quests in one place", "The Deadmines at its end")))
            .put("cn", JSONArray(listOf("Busy at peak hours", "Long walks between farms"))))
            .put(JSONObject().put("t", "Loch Modan").put("z", "Loch Modan").put("zm", 1432).put("lo", 10).put("hi", 20).put("sec", 420).put("q", 22)
                .put("pt", "Lakes and dwarves east of Dun Morogh.").put("pr", JSONArray(listOf("Quiet"))).put("cn", JSONArray(listOf("A long run there"))))
            .put(JSONObject().put("t", "Darkshore").put("z", "Darkshore").put("lo", 10).put("hi", 20).put("sec", 900).put("q", 30).put("ol", true)))

    private fun lay(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, W, H)
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
    private fun <T> field(v: QuestsView, name: String): T {
        val f = QuestsView::class.java.getDeclaredField(name); f.isAccessible = true
        @Suppress("UNCHECKED_CAST") return f.get(v) as T
    }
    private fun tab(v: QuestsView): Int = field(v, "tab")
    private fun onTabs(v: QuestsView): Boolean = field(v, "onTabs")
    private fun cur(v: QuestsView): PolestarTab.Cursor = v.psMenu.cur      // pm1: the one Polestar menu (PolestarMenu)
    private fun key(v: QuestsView, k: String) { v.onDirect(k); frames(v, 2) }

    /** the Quests room, Polestar the tracker, its documents in */
    private fun room(g: JSONObject = guide(), fight: Boolean = false): QuestsView {
        Settings.init(app)
        Settings.polestarGuide = PolestarGuide.ON
        val v = QuestsView(app, { null }, { })
        v.fight = { fight }
        v.update(questsDoc())
        v.onPolestarDoc(g)
        v.onPolestarDoc(steps())
        v.onPolestarDoc(list())
        g.optInt("s", 0).takeIf { it > 0 }?.let { v.onPolestarDoc(stepDoc(it)) }
        lay(v)
        frames(v)
        return v
    }
    /** onto the Polestar tab from the first quest: Up (the tab row), Right (landing opens it), Down (its list) */
    private fun toPolestar(v: QuestsView, shotName: String? = null) {
        key(v, "PADDUP")
        assertTrue("Up from the first quest: the tab row", onTabs(v))
        shotName?.let { shot(v, it) }
        key(v, "PADDRIGHT")
        assertEquals("Right: the Polestar tab, the cursor still on the row", 1, tab(v))
        assertTrue(onTabs(v))
        key(v, "PADDDOWN")
        assertTrue("Down: its list", !onTabs(v))
    }

    @Test fun theTabRowAndTheSteps() {
        art()
        val v = room()
        shot(v, "01_quests_tab_row")
        toPolestar(v, "01b_tab_row_cursor")
        assertEquals("entered on the shown step", "s:4", cur(v).sel)
        shot(v, "02_steps_shown_step")
        key(v, "PADDDOWN"); key(v, "PADDDOWN")
        assertEquals("s:6", cur(v).sel)
        v.onPolestarDoc(stepDoc(6))
        shot(v, "03_step_details")
        // LB / RB from anywhere
        key(v, "PADLSHOULDER")
        assertEquals(0, tab(v))
        key(v, "PADRSHOULDER")
        assertEquals(1, tab(v))
        assertEquals("entered again: the shown step", "s:4", cur(v).sel)
        // fix/desk-click-switch: every step is listed (no Done fold): up from the shown step is the done step before it
        key(v, "PADDUP")
        assertEquals("s:3", cur(v).sel)
        shot(v, "04_done_steps_listed")
        // B: Home (the list is the last step back); the room's leave asked
        var left = false
        v.onLeave = { left = true }
        key(v, "PAD2")
        assertTrue(left)
    }

    @Test fun theGoalsAndTheLook() {
        art()
        val v = room(guide(s = 9, st = "dddluuuuuuuuuuuu"))
        toPolestar(v)
        assertEquals("s:9", cur(v).sel)
        v.onPolestarDoc(stepDoc(9))
        shot(v, "06_looking_ahead_notice")
        // Right onto the goals (D2), A ticks
        key(v, "PADDRIGHT")
        assertEquals(PolestarTab.View.GOALS, cur(v).view)
        shot(v, "07_goals")
        key(v, "PAD2")
        assertEquals(PolestarTab.View.LIST, cur(v).view)
        // the notice "Back to step 4" is above the guide's steps
        val rows = PolestarTab.rows(PolestarTab.Guide.parse(guide(s = 9))!!, false)
        assertEquals("n:AWAY", rows[1].key)
    }

    @Test fun changeGuideAndThePath() {
        art()
        val v = room()
        toPolestar(v)
        while (cur(v).sel?.startsWith("s:") == true) key(v, "PADDUP")   // (every step listed: past the done ones)
        assertEquals("guide", cur(v).sel)
        shot(v, "08_guide_row")
        key(v, "PAD1")
        assertEquals(PolestarTab.View.PICKER, cur(v).view)
        shot(v, "09_change_guide")
        repeat(4) { key(v, "PADDDOWN") }
        shot(v, "10_change_guide_suggested")
        repeat(3) { key(v, "PADDDOWN") }
        key(v, "PAD1")                                         // a folder opens
        shot(v, "11_change_guide_folder")
        key(v, "PAD2")
        assertEquals(PolestarTab.View.LIST, cur(v).view)
        // the path choice waiting (D4): the notice, then the view
        val w = room(guide(s = null, l = null, st = "dddddddddddddddd") { it.put("cp", true).put("rw", true).put("rc", true).put("fs", 444) })
        w.onPolestarDoc(route())
        toPolestar(w)
        assertEquals("guide", cur(w).sel)
        key(w, "PADDDOWN")
        assertEquals("n:CHOOSE", cur(w).sel)
        shot(w, "12_choose_notice")
        key(w, "PAD1")
        assertEquals(PolestarTab.View.PATH, cur(w).view)
        shot(w, "13_choose_your_path")
        key(w, "PADDDOWN")
        shot(w, "14_choose_your_path_second")
    }

    @Test fun homesChooseRowOpensTheRoomOnThePath() {
        art()
        val w = room(guide(s = null, l = null, st = "dddddddddddddddd") { it.put("cp", true).put("rw", true).put("rc", true).put("fs", 444) })
        w.onPolestarDoc(route())
        w.openPolestar(path = true)
        key(w, "PADDDOWN")                                     // (the first press applies it: the hold came)
        assertEquals(1, tab(w))
        assertEquals(PolestarTab.View.PATH, cur(w).view)
        // pg1 review: opened before its choice came (a first open: no pgroute yet), it lands on the recommended option when it arrives
        val late = room(guide(s = null, l = null, st = "dddddddddddddddd") { it.put("cp", true).put("rw", true).put("rc", true).put("fs", 445) })
        late.openPolestar(path = true)
        key(late, "PADDDOWN")
        assertEquals(PolestarTab.View.PATH, cur(late).view)
        val r = route().put("fs", 445)
        r.getJSONArray("o").getJSONObject(0).remove("rec"); r.getJSONArray("o").getJSONObject(1).put("rec", true)
        late.onPolestarDoc(r)
        assertEquals("the recommended option (Loch Modan, the second)", 1, cur(late).path)
    }

    @Test fun resetAndAFight() {
        art()
        val v = room()
        toPolestar(v)
        repeat(20) { key(v, "PADDDOWN") }
        assertEquals("reset", cur(v).sel)
        key(v, "PAD1")
        assertEquals(PolestarTab.View.CONFIRM, cur(v).view)
        shot(v, "15_reset_confirm")
        key(v, "PAD2")
        assertEquals(PolestarTab.View.LIST, cur(v).view)
        val f = room(fight = true)
        toPolestar(f)
        while (cur(f).sel?.startsWith("s:") == true) key(f, "PADDUP")
        assertEquals("guide", cur(f).sel)
        shot(f, "16_fight_change_guide_grey")
        key(f, "PAD1")
        assertEquals("refused in a fight (D3)", PolestarTab.View.LIST, cur(f).view)
    }

    @Test fun noGuideAndOutlevelled() {
        art()
        val n = room(JSONObject().put("type", "pguide").put("v", "pg1").put("have", true).put("none", true).put("tb", true))
        toPolestar(n)
        shot(n, "17_no_guide")
        val o = room(guide(st = "dddluuuuuuuuuuuu") { it.put("ol", true).put("ot", "Westfall").put("oi", 4) })
        toPolestar(o)
        while (cur(o).sel?.startsWith("s:") == true) key(o, "PADDUP")
        assertEquals("n:OUTLEVEL", cur(o).sel)
        shot(o, "18_outlevelled_notice")
    }

    @Test fun noTabWithoutPolestar() {
        art()
        Settings.init(app)
        Settings.polestarGuide = PolestarGuide.OFF
        val v = QuestsView(app, { null }, { })
        v.update(questsDoc())
        v.onPolestarDoc(guide())
        lay(v); frames(v)
        key(v, "PADDUP")
        assertTrue("Polestar not the tracker: no tab row", !onTabs(v))
        key(v, "PADRSHOULDER")
        assertEquals(0, tab(v))
        shot(v, "19_quests_without_polestar")
    }
}
