package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * THE QUEST PANEL'S BUTTONS (the user, 2026-11-17: "when opening quests in this menu in general them not having the buttons or glyphs on
 * their options is also confusing- a button is not needed for close as B will close it, but share abandon and track should each have
 * buttons with the glyphs on them (A for track I dont care for the others)"): Track / Untrack = A, Share = X, Abandon = Y, each a button
 * wearing its glyph; no Close button (B closes); Abandon's question Cancel (B) / Abandon (A). MK_SHOTS_DIR: the panel drawn there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuestPanelTest {
    private val out: File? = System.getenv("MK_SHOTS_DIR")?.let { File(it) }

    private class FakeHost(var inParty: Boolean = true, var isTracked: Boolean = false) : QuestPanel.Host {
        val did = ArrayList<String>()
        val notes = ArrayList<String>()
        var q: JSONObject = JSONObject().put("id", 8).put("title", "Vile Familiars").put("lv", 3).put("zone", "Durotar").put("push", true)
            .put("obj", JSONArray().put(JSONObject().put("t", "Vile Familiar slain: 2/12")))
            .put("desc", "Make the Valley of Trials safe: slay the vile familiars to the east.")
        override fun quest(id: Int): JSONObject? = if (id == 8) q else null
        override fun tracked(id: Int): Boolean = isTracked
        override fun colour(q: JSONObject): Int = Theme.questGreen
        override fun party(): Boolean = inParty
        override fun note(text: String) { notes.add(text) }
        override fun share(id: Int) { did.add("share $id") }
        override fun abandonArm(id: Int) { did.add("arm $id") }
        override fun abandonConfirm(id: Int) { did.add("confirm $id") }
        override fun changed() {}
        override fun redrawIn(ms: Long) {}
        override fun track(id: Int, on: Boolean) { did.add("track $id $on") }
        override fun glyphs(): Boolean = true
    }

    /** the harvested kit (the buttons' plates, the glyphs) and fonts from MK_ART_ROOT, when set */
    private fun art() {
        val real = System.getenv("MK_ART_ROOT") ?: return
        val root = File("build/tmp/qp-art").absoluteFile
        for (d in listOf("ui", "fonts")) if (!File(root, d).isDirectory) File(real, d).takeIf { it.isDirectory }?.copyRecursively(File(root, d))
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, root)
    }
    private fun panel(h: FakeHost = FakeHost()) = QuestPanel(h).also { art(); it.open(8) }
    private fun draw(p: QuestPanel): Bitmap {
        val bm = Bitmap.createBitmap(720, 640, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm); c.drawColor(Theme.ink)
        p.draw(c, 20f, 20f, 620f, 680f)
        return bm
    }
    private fun shot(name: String, bm: Bitmap) {
        val d = out ?: return
        d.mkdirs(); File(d, "$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun theButtonsAreTrackShareAbandonWithNoClose() {
        val p = panel()
        assertEquals(listOf("track", "share", "abandon"), p.buttons())
        draw(p)
        assertFalse("no Close among the touch targets", p.hits.any { it.second == "close" })
        assertEquals(listOf("track", "share", "abandon"), p.hits.map { it.second })
    }

    @Test fun aTracksAnUntrackedQuestAndUntracksATrackedOne() {
        val h = FakeHost()
        val p = panel(h)
        assertTrue("A is the panel's, the cursor on the list", p.key("PAD1"))
        assertEquals(listOf("track 8 true"), h.did)
        h.isTracked = true; h.did.clear()
        p.key("PAD1")
        assertEquals(listOf("track 8 false"), h.did)
    }

    @Test fun xSharesOnlyInAParty() {
        val h = FakeHost(inParty = false)
        val p = panel(h)
        assertTrue(p.key("PAD3"))
        assertTrue("nothing shared", h.did.isEmpty())
        assertEquals(listOf("You are not in a party."), h.notes)
        h.inParty = true
        p.key("PAD3")
        assertEquals(listOf("share 8"), h.did)
    }

    @Test fun yArmsAbandonThenAConfirmsAndBCancels() {
        val h = FakeHost()
        val p = panel(h)
        p.key("PAD4")
        assertEquals(listOf("arm 8"), h.did)
        p.onAck(JSONObject().put("op", "arm").put("id", 8).put("ok", true).put("title", "Vile Familiars"))
        assertTrue(p.armedNow())
        assertEquals(listOf("cancel", "abandon!"), p.buttons())
        draw(p)
        p.key("PAD2")
        assertFalse("B cancels the question", p.armedNow())
        assertTrue("... the panel stays", p.isOpen)
        p.key("PAD4")
        p.onAck(JSONObject().put("op", "arm").put("id", 8).put("ok", true).put("title", "Vile Familiars"))
        h.did.clear()
        p.key("PAD1")
        assertEquals("A confirms", listOf("confirm 8"), h.did)
    }

    @Test fun bOnTheListClosesThePanel() {
        val p = panel()
        assertTrue(p.key("PAD2"))
        assertFalse(p.isOpen)
    }

    @Test fun theButtonsWearTheirGlyphs() {
        CastSettings.init(RuntimeEnvironment.getApplication())
        val p = panel()
        draw(p)
        assertEquals(listOf("rev_a", "rev_x", "rev_y"), p.lastGlyphs)
        for (layout in listOf(CastSettings.ButtonLayout.NINTENDO, CastSettings.ButtonLayout.XBOX)) {
            val was = CastSettings.buttonLayout
            CastSettings.buttonLayout = layout
            try { shot("quest_panel_axy_${layout.name.lowercase()}", draw(panel(FakeHost(inParty = false)))) } finally { CastSettings.buttonLayout = was }
        }
        val armed = panel()
        armed.key("PAD4")
        armed.onAck(JSONObject().put("op", "arm").put("id", 8).put("ok", true).put("title", "Vile Familiars"))
        val bm = draw(armed)
        assertEquals(listOf(UiKit.backGlyph(), "rev_a"), armed.lastGlyphs)
        shot("quest_panel_abandon_question", bm)
    }
}
