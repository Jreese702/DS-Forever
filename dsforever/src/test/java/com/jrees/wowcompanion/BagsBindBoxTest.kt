package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Book b59 / bags-bindbox1 (the user, 2026-11-13: "when doing bind to a button from bags it should open the normal spell binding menu"):
 *  the Bags room's Bind to a Button opens the Spellbook's Bind box for the item, hosted by Bags. The app reads the box's item, that it
 *  is hosted, and an answer that cleared the item off its button (issue #213's rule inside the box) */
class BagsBindBoxTest {
    private fun ctl(s: String) = SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b59","ctl":$s}"""))!!
    private fun ack(s: String) = SpellsModel.parseBind(JSONObject(s))!!

    @Test fun anItemBoxCarriesTheItemAndIsHosted() {
        val c = ctl("""{"on":true,"via":"bags","mode":"bind","bind":6948,"bitem":{"id":6948,"n":"Hearthstone","i":134414}}""")
        assertTrue(c.on && c.bindOpen && c.hosted)
        assertEquals(6948, c.binding)
        assertEquals(SpellsModel.BoxItem(6948, "Hearthstone", 134414), c.bindItem)
        assertNull("an item's box has no Cast bar slot", c.bindCb)
    }

    @Test fun aSpellBoxHasNoItemAndIsNotHosted() {
        val c = ctl("""{"on":true,"via":"button","mode":"bind","bind":133}""")
        assertNull(c.bindItem)
        assertFalse(c.hosted)
        assertEquals(133, c.binding)
    }

    @Test fun theItemStaysOnTheConfirmAndAClosedBoxIsOff() {
        val c = ctl("""{"on":true,"via":"bags","mode":"confirm","bitem":{"id":6948,"n":"Hearthstone","i":134414}}""")
        assertEquals("confirm", c.mode)
        assertEquals(6948, c.bindItem?.id)
        val off = ctl("""{"on":false,"mode":"book","note":"bind-kept"}""")
        assertFalse(off.on); assertNull(off.bindItem); assertFalse(off.hosted)
    }

    @Test fun anItemAnswerAndAClear() {
        val bound = ack("""{"type":"spellsack","op":"bind","v":"b59","id":6948,"ok":true,"item":true,"pad":{"s":186,"pg":1,"b":2,"p":2}}""")
        assertTrue(bound.ok && bound.item && !bound.cleared)
        assertEquals("Bound to", SpellsOverlay.answerWord(bound))
        assertEquals("Keep this binding?", SpellsOverlay.keepQuestion(bound))
        val cleared = ack("""{"type":"spellsack","op":"bind","v":"b59","id":6948,"ok":true,"item":true,"clr":true,"pad":{"pg":1,"b":1,"p":9}}""")
        assertTrue(cleared.ok && cleared.cleared)
        assertEquals("L3 (no bar slot) is a place", 9, cleared.pad?.pos)
        assertEquals("Cleared from", SpellsOverlay.answerWord(cleared))
        assertEquals("Keep it cleared?", SpellsOverlay.keepQuestion(cleared))
        val fight = ack("""{"type":"spellsack","op":"bind","v":"b59","id":6948,"ok":true,"item":true,"pad":{"pg":1,"b":2,"p":5,"fight":true}}""")
        assertEquals("In combat on", SpellsOverlay.answerWord(fight))
        val refused = ack("""{"type":"spellsack","op":"bind","v":"b59","id":6948,"ok":false,"item":true,"clr":true,"why":"combat"}""")
        assertFalse("a refusal never reads as a clear", refused.cleared)
        assertEquals("combat", refused.why)
    }

    @Test fun aSpellAnswerIsNoItem() {
        val r = ack("""{"type":"spellsack","op":"bind","v":"b59","id":133,"ok":true,"pad":{"s":198,"pg":1,"b":3,"p":6}}""")
        assertFalse(r.item); assertFalse(r.cleared)
    }
}
