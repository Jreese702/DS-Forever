package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HOLD TO CAST, WHAT THE APP SHOWS (PadMap m190 / PadTabCtl p59 / Book b78; the user's pick "A: corner badge"): the padmap's per-place
 * hold (`h`) and `hold.on`, the hold spells' cooldowns (`holdcd`) by Home's band key, the room's hold chooser, the Bind box's hold answer,
 * the badge's outer side, Settings' Hold time tile, es / fr, and the drawing hooks (Home's band, the room's Hold block and badges, the
 * Bind box's foot).
 */
class HoldCastUiTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private fun kt(n: String) = File(src, n).readText()
    private val i18n = File(src, "../../../../assets/companion/i18n")

    private fun padmap(holdOn: Boolean? = true): PadMapModel.Map {
        val sets = JSONArray()
        for (set in 1..4) {
            val a = JSONArray()
            for (p in 1..8) {
                val o = JSONObject().put("pos", p).put("kind", if (set == 1 && p >= 5) "fixed" else "spell").put("s", (set - 1) * 12 + p)
                    .put("n", "S$set$p").put("id", 100 + p).put("i", 1000 + p)
                if (set == 2 && p == 6) o.put("h", JSONObject().put("n", "Chain Heal").put("id", 1064).put("i", 136042))
                if (set == 1 && p == 8) o.put("h", JSONObject().put("n", "Hearthstone").put("t", "item").put("key", "i:6948").put("i", 134414))
                a.put(o)
            }
            sets.put(JSONObject().put("set", set).put("slots", a))
        }
        val m = JSONObject().put("page", 1).put("pages", 3).put("sets", sets)
        if (holdOn != null) m.put("hold", JSONObject().put("on", holdOn))
        return PadMapModel.parse(JSONObject().put("type", "padmap").put("v", "m190").put("m", m))!!
    }

    @Test fun thePadmapCarriesEachPlacesHold() {
        val m = padmap()
        assertEquals(true, m.holdOn)
        val h = PadMapModel.holdAt(m, 2, 6)!!
        assertEquals("Chain Heal", h.name); assertEquals(1064, h.id); assertEquals(136042, h.icon)
        val a = PadMapModel.holdAt(m, 1, 8)!!
        assertEquals("item", a.layer); assertEquals("i:6948", a.key); assertNull("an item is no spell", a.spellId)
        assertNull(PadMapModel.holdAt(m, 1, 6))
        assertEquals("Home's band keys (layer * 8 + position - 1)", setOf(1 * 8 + 5, 0 * 8 + 7), PadMapModel.holdRefs(m).keys)
        assertNull("an add-on before m190 says nothing", padmap(null).holdOn)
    }

    @Test fun theHoldCooldowns() {
        val cd = PadMapModel.parseHoldCd(JSONObject().put("type", "holdcd").put("k", JSONObject()
            .put("lt:6", JSONObject().put("d", 8).put("l", 5.5))
            .put("bare:8", JSONObject().put("d", 30).put("l", 2).put("e", true))
            .put("odd:3", JSONObject().put("d", 3).put("l", 1))
            .put("rt:2", JSONObject().put("d", 3).put("l", 4))))!!
        assertEquals(setOf(HoldGate.key(1, 6), HoldGate.key(0, 8)), cd.keys)
        assertEquals(5.5, cd.getValue(HoldGate.key(1, 6)).left, 0.0)
        assertTrue(cd.getValue(HoldGate.key(0, 8)).estimated)
        assertEquals(emptyMap<Int, PadMapModel.FaceCd>(), PadMapModel.parseHoldCd(JSONObject().put("type", "holdcd")))
        assertNull(PadMapModel.parseHoldCd(JSONObject().put("type", "facecd")))
    }

    @Test fun theRoomsHoldChooser() {
        fun ctl(hold: Boolean?) = PadMapModel.parseCtl(JSONObject().put("type", "padtabctl").put("v", "p59").put("c", JSONObject()
            .put("on", true).put("view", "normal").put("focus", "buttons").put("pos", 6).put("set", 2).put("shown", 2)
            .put("chooser", JSONObject().put("list", "spells").put("row", 1).put("id", "1064").apply { if (hold != null) put("hold", hold) })))!!
        assertTrue(ctl(true).chooser!!.hold)
        assertFalse(ctl(null).chooser!!.hold)
    }

    @Test fun theBindBoxsHoldAnswer() {
        val r = SpellsModel.parseBind(JSONObject().put("type", "spellsack").put("op", "bind").put("id", 403).put("ok", true)
            .put("pad", JSONObject().put("pg", 1).put("b", 2).put("p", 5).put("hold", true)))!!
        assertTrue(r.ok); assertTrue(r.hold)
        assertEquals("Hold spell on", SpellsOverlay.answerWord(r))
        val plain = SpellsModel.parseBind(JSONObject().put("type", "spellsack").put("op", "bind").put("id", 403).put("ok", true)
            .put("pad", JSONObject().put("s", 17).put("pg", 1).put("b", 2).put("p", 5)))!!
        assertFalse(plain.hold)
        assertEquals("Bound to", SpellsOverlay.answerWord(plain))
    }

    @Test fun theBadgeSitsOnTheClustersOuterSide() {
        // drawn 0..3 the D-pad left / up / right / down, 4..7 the faces left / top / right / bottom (y down)
        assertEquals(-1f to 1f, HoldGate.badgeSide(0)); assertEquals(-1f to -1f, HoldGate.badgeSide(1))
        assertEquals(1f to 1f, HoldGate.badgeSide(2)); assertEquals(-1f to 1f, HoldGate.badgeSide(3))
        assertEquals(-1f to 1f, HoldGate.badgeSide(4)); assertEquals(-1f to -1f, HoldGate.badgeSide(5))
        assertEquals(1f to 1f, HoldGate.badgeSide(6)); assertEquals(-1f to 1f, HoldGate.badgeSide(7))
        assertEquals(150L, HoldArt.FLASH_MS)
    }

    @Test fun settingsHoldTimeTile() {
        assertEquals(listOf("Short", "Normal", "Long"), CastSettings.HoldTime.values().map { it.label })
        assertTrue(SettingsTabs.Block.CONTROLLER.rows.contains("Hold time"))
        val t = kt("ControllerTiles.kt")
        assertTrue(t.contains("Slot(ControllerPictures.HOLD_TIME)"))
        assertTrue(t.contains("ControllerPictures.HOLD_TIME -> HOLDS.getOrNull(k)?.let { if (CastSettings.holdTime != it) { CastSettings.holdTime = it; HoldCast.holdTimeChanged() } }"))
        assertTrue(kt("ControllerPictures.kt").contains("HOLD_TIME -> holdTime(c, look)"))
    }

    @Test fun theDrawingHooks() {
        val cast = kt("CastView.kt")
        assertTrue("Home's band draws the hold after each button", cast.contains("next = min(next, drawHold(c, pos, src, li, circle, b, now))"))
        assertTrue(cast.contains("HoldArt.progress(c, 0f, 0f, iconHalf - 0.5f * k, half + 1f * k, prog, circle)"))
        assertTrue(cast.contains("HoldArt.bigFace(c, 0f, 0f, iconHalf, Icons.get(ref.icon), circle)"))
        val home = kt("HomeView.kt")
        assertTrue(home.contains("castView.holdRefs = PadMapModel.holdRefs(map).takeIf { HoldCast.usable && map.holdOn != false }"))
        assertTrue(home.contains("HoldCast.onChange = { castView.postInvalidateOnAnimation() }"))
        val room = kt("PadTabPage.kt")
        assertEquals("the Hold block on the three right pages", 3, Regex("drawHoldBlock\\(c, m, sl, w, above").findAll(room).count())
        assertTrue(room.contains("HoldArt.badge(c, r.left + 12f * k, r.top + 12f * k, 15f * k, Icons.get(hr.icon), -1f, lit = false)"))
        assertTrue(room.contains("if (holdChooser()) return if (sl?.hold != null) Thing.Unhold else null"))
        assertTrue(kt("SpellsOverlay.kt").contains("holdHint(c, box.left + 80f, HINT_Y)"))
        val host = kt("CompanionHost.kt")
        assertTrue(host.contains("\"holdmap\" -> if (current) HoldCast.onMap(msg)") && host.contains("\"holdprobe\" -> if (current) HoldCast.onProbe(msg)")
            && host.contains("\"holdcd\" -> if (current) PadMapModel.parseHoldCd(msg)?.let { homeView.onHoldCd(it) }"))
        assertTrue("the press log knows the hold way", PressLog.PATHS.contains("slothold"))
    }

    @Test fun spanishAndFrench() {
        val words = listOf(PadTabPage.HOLD_HINT, PadTabPage.HOLD_OFF_LINE, "Clear hold spell", "Hold spell on", SpellsOverlay.HOLD_HINT,
            "Hold time", "Normal", "Short", "Long", "Hold")
        for (l in listOf("es", "fr")) {
            val o = JSONObject(File(i18n, "$l.json").readText())
            val plain = o.getJSONObject("plain")
            for (w in words) assertTrue("$l: $w", plain.has(w))
            assertTrue("$l: hold %s", o.getJSONObject("templates").has("hold %s"))
            assertTrue("$l keeps CRLF", File(i18n, "$l.json").readText().contains("\r\n"))
        }
        Tr.load("es", File(i18n, "es.json").readText())
        assertNotNull(Tr)
    }
}
