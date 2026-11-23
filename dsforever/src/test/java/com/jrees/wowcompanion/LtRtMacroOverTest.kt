package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE LT / RT MACRO FIXES, the app's half (PadMap m188; the add-on's half: tools/test_ltrt_macro_over.py). The tester, 2026-11-23:
 * "couldn't use any macros on LT or RT with a face button, kept saying invalid target or cant cast when moving". The user's picks:
 * "Say what was pressed", "Show what's on top" (and "In combat wins": the order is kept, only shown).
 * - PressLog: a routed press's words also carry the triggers as read (trig), the way when it is not the kind's word (path) and
 *   over-macro; the rate limit goes by the kind too; an older add-on's press reads as before;
 * - PadMapModel: the padmap's `um` -> Slot.overMacro; the room's line for an In combat entry / a command over the player's macro;
 *   every such cell by Cast's index (layer * 8 + position);
 * - es / fr for the lines; Home's bars and the Controller room draw the mark.
 */
class LtRtMacroOverTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }

    @After fun reset() = PressLog.reset()

    private fun cast(kind: String, lay: String = "lt", bar: String = "page1", slot: Int = 18, tr: String? = "L1R0", path: String? = null,
                     om: Boolean = false, fight: Boolean = false) =
        JSONObject().put("type", "facecast").put("v", "m188").put("pos", 6).put("kind", kind).put("down", true)
            .put("lay", lay).put("bar", bar).put("slot", slot).apply {
                if (tr != null) put("tr", tr); if (path != null) put("path", path); if (om) put("om", true); if (fight) put("fight", true)
            }

    // ---------------------------------------------------------------- say what was pressed

    @Test fun theWordsSayTheTriggersTheWayAndOverMacro() {
        assertEquals("pos 6 slotfight fight via LT page1 slot 18 trig L1R0 over-macro",
            PressLog.pressWords(cast("slotfight", path = "slotfight", om = true, fight = true)))
        assertEquals("pos 6 slotmacro via RT page1 slot 30 trig L0R1 path command over-macro",
            PressLog.pressWords(cast("slotmacro", lay = "rt", slot = 30, tr = "L0R1", path = "command", om = true)))
        assertEquals("the way is said only when it differs from the kind", "pos 6 slotmacro via LT page1 slot 18 trig L1R0",
            PressLog.pressWords(cast("slotmacro", path = "slotmacro")))
        assertEquals("no button number for LT", "pos 6 native via bare page1 slot 6 trig L-R0",
            PressLog.pressWords(cast("native", lay = "bare", slot = 6, tr = "L-R0", path = "native")))
        assertEquals("no controller state", "pos 6 native via bare page1 slot 6 trig none",
            PressLog.pressWords(cast("native", lay = "bare", slot = 6, tr = "none")))
        // our plain codes only: anything else in tr / path is dropped, never echoed
        assertEquals("pos 6 native via LT page1 slot 18", PressLog.pressWords(cast("native", tr = "L1R0<b>", path = "MyHeal")))
        // an older add-on (m187: no tr / path / om): exactly as before
        assertEquals("pos 6 native via LT page1 slot 18", PressLog.pressWords(cast("native", tr = null)))
    }

    @Test fun theRateLimitGoesByTheKindToo() {
        assertTrue(PressLog.press(cast("slotmacro", path = "slotmacro"), 1_000)!!.contains("slotmacro"))
        assertNull("the same press again within the limit", PressLog.press(cast("slotmacro", path = "slotmacro"), 1_500))
        // the fight starts: the same position / layer / bar now runs the In combat entry - its own line at once
        assertTrue(PressLog.press(cast("slotfight", path = "slotfight", om = true, fight = true), 1_600)!!.endsWith("over-macro"))
        assertNull(PressLog.press(cast("slotfight", path = "slotfight", om = true, fight = true), 1_700))
        assertTrue(PressLog.lines().any { it.endsWith("pos 6 slotfight fight via LT page1 slot 18 trig L1R0 over-macro") })
    }

    // ---------------------------------------------------------------- show what's on top

    private fun slot(pos: Int, o: JSONObject) = o.put("pos", pos)
    private fun map(): PadMapModel.Map {
        val lt = JSONArray()
            .put(slot(6, JSONObject().put("kind", "combat").put("s", 18).put("ov", true).put("base", "macro").put("um", true)
                .put("c", JSONObject().put("n", "Healing Wave").put("id", 331).put("i", 136052)).put("o", JSONObject().put("n", "MyHeal").put("i", 1))))
            .put(slot(5, JSONObject().put("kind", "combat").put("s", 17).put("ov", true).put("base", "spell")
                .put("c", JSONObject().put("n", "Lightning Bolt").put("id", 403).put("i", 2))))
        val rt = JSONArray()
            .put(slot(6, JSONObject().put("kind", "command").put("s", 30).put("ov", true).put("um", true).put("key", "TARGETNEARESTENEMY").put("n", "Target Enemy")))
            .put(slot(8, JSONObject().put("kind", "command").put("s", 32).put("ov", true).put("key", "ASSISTTARGET").put("n", "Assist")))
            .put(slot(7, JSONObject().put("kind", "macro").put("s", 31).put("um", true).put("n", "Odd")))     // not one of ours: no line
        val sets = JSONArray().put(JSONObject().put("set", 1).put("slots", JSONArray()))
            .put(JSONObject().put("set", 2).put("slots", lt)).put(JSONObject().put("set", 3).put("slots", rt))
        return PadMapModel.parse(JSONObject().put("type", "padmap").put("v", "m188").put("m", JSONObject().put("sets", sets)))!!
    }

    @Test fun theViewsSayWhatIsOnTop() {
        val m = map()
        val lt6 = PadMapModel.slot(m, 2, 6)!!
        assertTrue(lt6.overMacro)
        assertEquals("In combat: Healing Wave casts here in fights, over your macro", PadMapModel.overMacro(lt6))
        assertNull("an In combat entry over a spell", PadMapModel.overMacro(PadMapModel.slot(m, 2, 5)))
        assertEquals("DS Forever's Target Enemy runs here, over your macro", PadMapModel.overMacro(PadMapModel.slot(m, 3, 6)))
        assertNull("a command over nothing of the player's", PadMapModel.overMacro(PadMapModel.slot(m, 3, 8)))
        assertNull("um on something not ours: nothing said", PadMapModel.overMacro(PadMapModel.slot(m, 3, 7)))
        assertNull(PadMapModel.overMacro(null))
        // Cast's cells: LT (layer 1) + Y (position 6) = 13, RT (layer 2) + Y = 21
        assertEquals(setOf(1 * 8 + 5, 2 * 8 + 5), PadMapModel.overMacroKeys(m))
        assertEquals(HomeFightFace.cmdKey(2, 6), 13)
    }

    @Test fun anOlderAddOnSaysNothing() {
        val sets = JSONArray().put(JSONObject().put("set", 2).put("slots", JSONArray().put(JSONObject().put("pos", 6).put("kind", "combat")
            .put("ov", true).put("base", "macro").put("c", JSONObject().put("n", "Healing Wave").put("id", 331)))))
        val m = PadMapModel.parse(JSONObject().put("type", "padmap").put("v", "m187").put("m", JSONObject().put("sets", sets)))!!
        assertFalse(PadMapModel.slot(m, 2, 6)!!.overMacro)
        assertTrue(PadMapModel.overMacroKeys(m).isEmpty())
    }

    @Test fun theLinesAreInSpanishAndFrench() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n").map { File(it) }.first { it.isDirectory }
        for (lang in listOf("es", "fr")) {
            val raw = String(File(dir, "$lang.json").readBytes(), Charsets.UTF_8)
            assertTrue("$lang.json stays CRLF", raw.contains("\r\n") && !raw.replace("\r\n", "").contains("\n"))
            Tr.load(lang, raw)
        }
        val m = map()
        val lines = listOf(PadMapModel.overMacro(PadMapModel.slot(m, 2, 6))!!, PadMapModel.overMacro(PadMapModel.slot(m, 3, 6))!!,
            "In combat: a spell casts here in fights, over your macro")
        for (w in lines) for (lang in listOf("es", "fr")) assertFalse("$lang: $w", Tr.t(w, lang) == w)
        assertEquals("En combate: Healing Wave se lanza aquí en los combates, por encima de tu macro", Tr.t(lines[0], "es"))
        assertEquals("En combat : Healing Wave est lancé ici pendant les combats, par-dessus votre macro", Tr.t(lines[0], "fr"))
        assertEquals("Target Enemy de DS Forever se usa aquí, por encima de tu macro", Tr.t(lines[1], "es"))
        // the shorter "In combat: %s" template does not take the longer line
        assertFalse(Tr.t(lines[0], "es").startsWith("En combate: Healing Wave casts"))
    }

    @Test fun homeAndTheRoomDrawTheMark() {
        val home = File(src, "HomeView.kt").readText()
        val cast = File(src, "CastView.kt").readText()
        val room = File(src, "PadTabPage.kt").readText()
        val kit = File(src, "UiKit.kt").readText()
        assertTrue(home.contains("castView.overMacro = PadMapModel.overMacroKeys(map)"))
        assertTrue(cast.contains("overMacro?.contains(memKey) == true") && cast.contains("UiKit.overMark(c, cx - iconHalf * 0.62f"))
        assertTrue(room.contains("PadMapModel.overMacro(sl) != null) UiKit.overMark(") && room.contains("drawOverLine(c, it, x0, x1, top + 386f)")
            && room.contains("drawOverLine(c, it, x0, x1, FIGHT_ROW_Y + 94f)"))
        assertTrue(kit.contains("fun overMark(c: Canvas, cx: Float, cy: Float, r: Float)"))
    }
}
