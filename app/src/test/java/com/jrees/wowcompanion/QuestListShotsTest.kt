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
 * HOME'S MAP LIST, TRACK / UNTRACK AND THE POLESTAR RIBBON, OFF THE DEVICE (the user, 2026-11-16: "Tracking and untracking from that
 * menu right now feels really unintuitive where it jumps you all the way down 40 slots in the quest menu with the untracked quest, if I
 * want to track or untrack a bunch at once I want to be able quickly go down the list and hit untrack for each, not have to scroll all
 * the way back up after each untrack." and "I need some kind of header in the quests menu of the homeroom map indicating that it is the
 * polestar objective."). The map module opened full (as Home opens it, the controller Home's), fed a quests document, a map document and
 * Polestar's step; its keys are Home's [MapView.hostKey]. The checks run always; the pictures only when QLIST_SHOTS_DIR is set
 * (QLIST_ART_ROOT = an art root laid out like the app's).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class QuestListShotsTest {
    private val out: File? = System.getenv("QLIST_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    private fun art() {
        System.getenv("QLIST_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
    }

    private class Q(val id: Int, val title: String, val zone: String, val lv: Int, val obj: String?, val ready: Boolean = false)

    private val log = listOf(
        Q(7, "Kobold Camp Cleanup", "Elwynn Forest", 7, "Kobold Vermin slain: 4/10"),
        Q(33, "Wolves Across the Border", "Elwynn Forest", 6, "Tough Wolf Meat: 3/8"),
        Q(46, "Bounty on Murlocs", "Elwynn Forest", 10, "Torn Murloc Fin: 2/8"),
        Q(47, "Gold Dust Exchange", "Elwynn Forest", 9, "Gold Dust: 5/10"),
        Q(60, "Kobold Candles", "Elwynn Forest", 8, "Large Candle: 1/8"),
        Q(106, "Young Lovers", "Elwynn Forest", 7, null, ready = true),
        Q(62, "The Fargodeep Mine", "Elwynn Forest", 8, "Explore the Fargodeep Mine"),
        Q(76, "The Jasperlode Mine", "Elwynn Forest", 10, "Scout through the Jasperlode Mine"),
        Q(85, "Lost Necklace", "Elwynn Forest", 5, "Find Billy's necklace"),
        Q(88, "Princess Must Die!", "Elwynn Forest", 12, "Princess slain: 0/1"),
        Q(109, "Report to Gryan Stoutmantle", "Westfall", 10, "Talk to Gryan Stoutmantle"),
        Q(18, "Brotherhood of Thieves", "Northshire Valley", 4, "Red Burlap Bandana: 6/12"),
        Q(244, "Encroaching Gnolls", "Redridge Mountains", 15, "Talk to Guard Parker"),
        Q(416, "Rat Catching", "Loch Modan", 14, "Tunnel Rat Ear: 0/12"),
        Q(56, "The Night Watch", "Duskwood", 22, "Skeletal Fiend slain: 0/15"),
        Q(463, "The Greenwarden", "Wetlands", 21, "Talk to the Greenwarden"),
    )

    private fun questsDoc(tracked: Set<Int>): JSONObject {
        val a = JSONArray()
        for ((slot, q) in log.withIndex()) {
            val e = JSONObject().put("id", q.id).put("title", q.title).put("zone", q.zone).put("lv", q.lv).put("slot", slot + 1)
            if (q.id in tracked) e.put("watch", 1)
            if (q.ready) e.put("ready", true)
            if (q.obj != null) {
                val m = Regex("(\\d+)/(\\d+)$").find(q.obj)
                val o = JSONObject().put("t", q.obj)
                if (m != null) o.put("have", m.groupValues[1].toInt()).put("need", m.groupValues[2].toInt())
                e.put("obj", JSONArray().put(o))
            }
            a.put(e)
        }
        return JSONObject().put("type", "quests").put("plv", 9).put("list", a)
            .put("me", JSONObject().put("n", "Brightwater").put("c", "SHAMAN").put("cl", "Shaman"))
    }

    private fun mapDoc() = JSONObject().put("ok", true).put("mapID", 1429).put("zone", "Elwynn Forest").put("subzone", "Goldshire")
        .put("x", 0.42).put("y", 0.65).put("facing", 0.0)

    private fun lay(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, W, H)
    }

    /** the map module opened full by Home, its controller Home's, the quests in; Polestar's step when [step] */
    private fun map(step: JSONObject? = null, tracked: Set<Int> = setOf(7, 33, 46, 47, 60, 106)): MapView {
        Settings.init(app)
        Settings.polestarGuide = if (step != null) PolestarGuide.ON else PolestarGuide.OFF
        val v = MapView(app)
        v.compact = false; v.showHeader = false; v.padHints = true
        v.onUntrackQuest = { }; v.onTrackQuest = { }; v.onTrackFocusQuest = { }
        v.update(mapDoc(), 0L)
        v.onQuests(questsDoc(tracked))
        step?.let { v.onPolestarStep(it) }
        lay(v)
        frames(v)
        v.hostStart()
        frames(v)
        return v
    }

    /** draw until the rows' glide settles (each frame one 16 ms step) */
    private fun frames(v: MapView, n: Int = 45): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        for (i in 0 until n) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(16))
            ShadowLooper.idleMainLooper()
            val c = Canvas(b); c.drawColor(Theme.ink); v.draw(c)
        }
        return b
    }

    private fun sel(v: MapView): Int {
        val f = MapView::class.java.getDeclaredField("cursor"); f.isAccessible = true
        return (f.get(v) as MapCursor).sel
    }

    /** where the list drew row [key] last frame (its touch box) */
    private fun rowOf(v: MapView, key: Int): RectF? {
        val f = MapView::class.java.getDeclaredField("trackerHits"); f.isAccessible = true
        @Suppress("UNCHECKED_CAST") val hits = f.get(v) as List<Pair<RectF, MapList.Item>>
        return hits.firstOrNull { it.second.key == key && it.second !is MapList.Item.Section }?.first?.let { RectF(it) }
    }

    private fun key(v: MapView, k: String) { v.hostKey(k); frames(v, 2) }

    private fun shot(v: MapView, name: String) {
        val d = out ?: return
        val b = frames(v)
        d.mkdirs()
        File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** to quest [id], down from where the cursor is */
    private fun downTo(v: MapView, id: Int) {
        for (i in 0 until 30) { if (sel(v) == id) return; key(v, "PADDDOWN") }
        error("never reached $id (on ${sel(v)})")
    }

    @Test fun untrackKeepsTheCursorsPlaceAndTheNextQuestTakesIt() {
        art()
        val v = map()
        downTo(v, 46); frames(v)
        val was = rowOf(v, 46)!!
        shot(v, "untrack_1_before")
        key(v, "PAD3")                                             // X: untrack Bounty on Murlocs
        frames(v)
        shot(v, "untrack_2_after")
        assertEquals("the next tracked quest took its place", 47, sel(v))
        val now = rowOf(v, 47)!!
        assertEquals("on the same row of the screen", was.top, now.top, 2f)
        // a bunch in a row: X, X - one press each, the cursor never leaves its place
        key(v, "PAD3"); frames(v)
        assertEquals(60, sel(v))
        assertEquals(was.top, rowOf(v, 60)!!.top, 2f)
        key(v, "PAD3"); frames(v)
        assertEquals(106, sel(v))
        shot(v, "untrack_3_after_three")
    }

    @Test fun trackInATabKeepsTheCursorsPlaceAndTheNextQuestOfTheTabTakesIt() {
        art()
        val v = map()
        val tab = MapList.groupKey("Elwynn Forest")
        downTo(v, tab)
        key(v, "PAD1")                                             // A: open the Elwynn Forest tab
        frames(v)
        downTo(v, 76); frames(v)
        val was = rowOf(v, 76)!!
        shot(v, "track_1_before")
        key(v, "PAD1")                                             // A: track & focus The Jasperlode Mine
        frames(v)
        shot(v, "track_2_after")
        // (the tab sorts nearest the player's level 9 first: 62, 76 (gap 1), 88 (gap 3), 85 (gap 4))
        assertEquals("the next quest of the tab took its place", 88, sel(v))
        val now = rowOf(v, 88)!!
        assertEquals("on the same row of the screen (the list slid under it)", was.top, now.top, 12f)
        key(v, "PAD3"); frames(v)                                  // X: track Princess Must Die! too
        assertEquals(85, sel(v))
        shot(v, "track_3_after_two")
    }

    /**
     * Polestar merged with Kobold Camp Cleanup (7): X on 7's own row sent it into its closed tab, and the list's keeping handed the
     * cursor UP to the light blue row (MapCursor.moved), where the next X skips Polestar's step. The cursor holds its place there too.
     */
    @Test fun untrackingTheQuestPolestarStandsForKeepsTheCursorOffPolestarsRow() {
        art()
        val merged = JSONObject().put("t", "Kobold Camp Cleanup").put("g", "Kill Kobold Vermin").put("q", 7).put("m", 1429)
            .put("x", 0.47).put("y", 0.55).put("b", 1L).put("sk", true)
        val v = map(merged)
        assertEquals(MapList.POLESTAR, sel(v))
        key(v, "PADDDOWN"); frames(v)
        assertEquals(7, sel(v))
        val was = rowOf(v, 7)!!
        key(v, "PAD3"); frames(v)                                  // X: untrack Kobold Camp Cleanup
        assertEquals("the next tracked quest took its place, not Polestar's row", 33, sel(v))
        assertEquals(was.top, rowOf(v, 33)!!.top, 2f)
        key(v, "PAD3"); frames(v)                                  // X again: untracks 33, never a Polestar skip
        assertEquals(46, sel(v))
    }

    @Test fun theLastTrackedQuestHandsTheCursorToTheFirstTab() {
        art()
        // tabs closed: the tab after it
        val v = map()
        downTo(v, 106); frames(v)
        key(v, "PAD3"); frames(v)
        assertEquals(MapList.groupKey("Elwynn Forest"), sel(v))
        // every Elwynn quest tracked (no Elwynn tab): its tab comes back at the place and takes it
        val w = map(tracked = setOf(7, 33, 46, 47, 60, 106, 62, 76, 85, 88))
        downTo(w, 88); frames(w)
        key(w, "PAD3"); frames(w)
        assertEquals(MapList.groupKey("Elwynn Forest"), sel(w))
    }

    @Test fun polestarsRowWearsItsRibbon() {
        art()
        val merged = JSONObject().put("t", "Kobold Camp Cleanup").put("g", "Kill Kobold Vermin").put("q", 7).put("m", 1429)
            .put("x", 0.47).put("y", 0.55).put("b", 1L).put("sk", true)
        val v = map(merged)
        // the controller starts on the light blue row (it is the shown focus): its card is the selection, the ribbon above it
        assertEquals(MapList.POLESTAR, sel(v))
        val row = rowOf(v, MapList.POLESTAR)!!
        val first = rowOf(v, 7)!!
        assertTrue("the ribbon's room above the card", row.height() > 100f)
        assertTrue("the light blue row above the tracked quests", row.bottom <= first.top)
        shot(v, "ribbon_1_selected")
        key(v, "PADDDOWN")
        shot(v, "ribbon_2_rest")
        // a step with no quest of the log (a talk): the ribbon over Polestar's own words
        val talk = JSONObject().put("t", "Speak with Marshal Dughan").put("g", "Marshal Dughan in Goldshire").put("q", 0).put("m", 1429)
            .put("x", 0.42).put("y", 0.66).put("b", 1L).put("sk", true)
        val w = map(talk)
        shot(w, "ribbon_3_step")
        // no Polestar: no ribbon, the list as before
        val off = map()
        assertEquals(null, rowOf(off, MapList.POLESTAR))
        shot(off, "ribbon_4_off")
    }
    // ---- pg1 / D6 (the user, 2026-11-16: "I need some kind of header in the quests menu of the homeroom map indicating that it is the
    // polestar objective"): Map.lua's pguide names the guide, so the ribbon reads "Polestar · <zone>"; with no row the header stands
    // alone and the cursor skips it; a waiting path choice is the light blue row "Choose your path" (D4: A opens the Quests room on it)
    private fun pguide(extra: (JSONObject) -> Unit = {}) = JSONObject().put("type", "pguide").put("v", "pg1").put("have", true)
        .put("gid", "forever/alliance/1-10/elwynn").put("gs", 444).put("t", "Elwynn Forest").put("z", "Elwynn Forest").put("zm", 1429)
        .put("lo", 1).put("hi", 10).put("n", 16).put("s", 4).put("l", 4).put("dn", 3).put("tt", 16).also(extra)

    private fun items(v: MapView): List<MapList.Item> {
        val f = MapView::class.java.getDeclaredField("items"); f.isAccessible = true
        @Suppress("UNCHECKED_CAST") return f.get(v) as List<MapList.Item>
    }

    @Test fun polestarsHeaderInEveryState() {
        art()
        val talk = JSONObject().put("t", "Speak with Marshal Dughan").put("g", "Marshal Dughan in Goldshire").put("q", 0).put("m", 1429)
            .put("x", 0.42).put("y", 0.66).put("b", 1L).put("sk", true)
        // a step: the ribbon names the guide's zone
        val v = map(talk)
        v.onPolestarGuide(pguide()); frames(v)
        assertEquals("Polestar · Elwynn Forest", v.polestarHead())
        assertTrue(items(v).contains(MapList.Item.Polestar) && !items(v).contains(MapList.Item.PsHead))
        shot(v, "header_1_step")
        // no guide: the header alone, no row; the cursor never lands on it
        val n = map(talk)
        n.onPolestarStep(JSONObject().put("type", "pstep").put("none", true).put("b", 1L))
        n.onPolestarGuide(JSONObject().put("type", "pguide").put("have", true).put("none", true))
        frames(n)
        assertEquals("Polestar · No guide", n.polestarHead())
        assertTrue(items(n).contains(MapList.Item.PsHead) && !items(n).contains(MapList.Item.Polestar))
        assertTrue(MapList.PS_HEAD !in MapList.stops(items(n)))
        n.hostStart(); frames(n)
        assertTrue("the cursor is not on the header", sel(n) != MapList.PS_HEAD)
        shot(n, "header_2_no_guide")
        // a path choice waiting (the guide done, pstep none): the light blue row reads Choose your path; A opens the Quests room on it
        val w = map(talk)
        w.onPolestarStep(JSONObject().put("type", "pstep").put("none", true).put("b", 1L))
        w.onPolestarGuide(pguide { it.remove("s"); it.remove("l"); it.put("cp", true).put("rw", true).put("rc", true).put("fs", 444) })
        var opened = false
        w.onPolestarChoose = { opened = true }
        frames(w)
        assertTrue(items(w).contains(MapList.Item.Polestar))
        assertTrue(w.polestarChoose())
        w.hostStart(); frames(w)
        while (sel(w) != MapList.POLESTAR && sel(w) != MapList.ME) key(w, "PADDUP")
        if (sel(w) == MapList.ME) key(w, "PADDDOWN")
        assertEquals(MapList.POLESTAR, sel(w))
        shot(w, "header_3_choose_your_path")
        key(w, "PAD1")
        assertTrue("A on Choose your path opens the Quests room on it", opened)
        // the guide done, nothing waiting: the header alone with the guide's zone
        val d = map(talk)
        d.onPolestarStep(JSONObject().put("type", "pstep").put("none", true).put("b", 1L))
        d.onPolestarGuide(pguide { it.remove("s"); it.remove("l"); it.put("cp", true) })
        frames(d)
        assertTrue(items(d).contains(MapList.Item.PsHead))
        shot(d, "header_4_guide_done")
        // an older add-on (no pguide): the ribbon reads "Polestar" as before
        val old = map(talk)
        assertEquals("Polestar", old.polestarHead())
    }
}
