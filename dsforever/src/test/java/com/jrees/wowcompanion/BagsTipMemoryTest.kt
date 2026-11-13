package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * bags-tip1 (the user, 2026-11-13: "the tool tip shows one thing for a half second and has to load the rest and reloads the tool tip. I
 * want it to feel fast and load that tool tip as its full description each time"; E:/wow-build/bags_design_1113.md fix 1): the app keeps
 * each item's last FULL tooltip, and while warm holds the previous card instead of flashing the stand-in.
 */
class BagsTipMemoryTest {
    private fun full(key: String, text: String = "Full") = JSONObject().put("key", key)
        .put("lines", JSONArray().put(JSONObject().put("t", text)).put(JSONObject().put("t", "Use: does a thing")))
    private fun loading(key: String) = JSONObject().put("key", key).put("loading", true)
        .put("lines", JSONArray().put(JSONObject().put("t", "Retrieving item information")))
    private fun none(key: String) = JSONObject().put("key", key).put("none", true)
    private fun stand(key: String) = { JSONObject().put("key", key).put("lines", JSONArray().put(JSONObject().put("t", "stand-in"))) }
    private val A = "bag 0 1 501"; private val B = "bag 0 2 502"

    @Test fun theStandInOnlyWhenNothingEverCame() {
        val m = BagsCursor.TipMemory()
        val c = m.pick(A, 1, null, "Jerky", 1, 0, stand(A))
        assertTrue(c.stand); assertEquals("stand-in", c.tip.optJSONArray("lines")!!.optJSONObject(0).optString("t"))
        val f = m.pick(A, 1, full(A), "Jerky", 1, 10, stand(A))
        assertFalse(f.stand); assertFalse(f.held); assertEquals("Full", f.tip.optJSONArray("lines")!!.optJSONObject(0).optString("t"))
    }

    /** a refresh / heartbeat document without a tooltip (or another slot's): the item's kept full one, never the stand-in */
    @Test fun aRefreshNeverDropsTheCard() {
        val m = BagsCursor.TipMemory()
        val t = full(A)
        m.pick(A, 1, t, "Jerky", 1, 0, stand(A))
        for (now in listOf(5_000L, 15_000L, 60_000L)) {          // long after any hold: still the full card
            val c = m.pick(A, 1, null, "Jerky", 1, now, stand(A))
            assertSame(t, c.tip); assertFalse(c.stand); assertFalse(c.held)
        }
        assertSame(t, m.pick(A, 1, full(B), "Jerky", 1, 70_000L, stand(A)).tip)       // a late one for another slot: dropped
    }

    /** keyed on bag, slot, item id AND count: a stack that changed size is a new card; the game's new one is kept over the old */
    @Test fun keyedOnBagSlotIdAndCount() {
        val m = BagsCursor.TipMemory()
        m.pick(A, 5, full(A, "five"), "Jerky", 1, 0, stand(A))
        val c = m.pick(A, 4, null, "Jerky", 1, 10_000, stand(A))
        assertTrue(c.stand)
        assertEquals("five", m.pick(A, 5, null, "Jerky", 1, 20_000, stand(A)).tip.optJSONArray("lines")!!.optJSONObject(0).optString("t"))
        m.pick(A, 5, full(A, "newer"), "Jerky", 1, 30_000, stand(A))
        assertEquals("newer", m.pick(A, 5, null, "Jerky", 1, 40_000, stand(A)).tip.optJSONArray("lines")!!.optJSONObject(0).optString("t"))
    }

    /** the no-flash hold: moving on to an item not read yet keeps the previous card up to HOLD_MS, then the stand-in */
    @Test fun warmHoldsThePreviousCardInsteadOfFlashing() {
        val m = BagsCursor.TipMemory()
        val a = full(A)
        m.pick(A, 1, a, "Jerky", 1, 1_000, stand(A))
        m.pick(A, 1, null, "Jerky", 1, 1_016, stand(A))                       // drawn again the next frame: the hold counts from here
        val h = m.pick(B, 1, null, "Potion", 1, 1_016 + 100, stand(B))
        assertTrue(h.held); assertSame(a, h.tip); assertEquals("Jerky", h.name)
        assertTrue(m.pick(B, 1, null, "Potion", 1, 1_016 + BagsCursor.TipMemory.HOLD_MS, stand(B)).held)
        val s = m.pick(B, 1, null, "Potion", 1, 1_016 + BagsCursor.TipMemory.HOLD_MS + 1, stand(B))
        assertTrue(s.stand); assertFalse(s.held)                              // nothing came in time: the stand-in
        // ... and the full one when it comes, at once
        val f = m.pick(B, 1, full(B), "Potion", 1, 1_400, stand(B))
        assertFalse(f.held); assertFalse(f.stand); assertEquals(B, f.tip.optString("key"))
    }

    /** a held card never extends itself: drawing it frame after frame does not keep the hold alive */
    @Test fun theHoldEndsEvenWhileRedrawn() {
        val m = BagsCursor.TipMemory()
        m.pick(A, 1, full(A), "Jerky", 1, 0, stand(A))
        var t = 16L
        while (t <= BagsCursor.TipMemory.HOLD_MS) { assertTrue(m.pick(B, 1, null, "Potion", 1, t, stand(B)).held); t += 16 }
        assertTrue(m.pick(B, 1, null, "Potion", 1, t, stand(B)).stand)
    }

    /** loading / none / empty tooltips are never kept; a loading one shows only when nothing better is there */
    @Test fun onlyCompleteTooltipsAreKept() {
        val m = BagsCursor.TipMemory()
        assertFalse(m.offer(loading(A), A, 1)); assertFalse(m.offer(none(A), A, 1))
        assertFalse(m.offer(JSONObject().put("key", A).put("lines", JSONArray()), A, 1))
        assertEquals(0, m.size)
        val l = m.pick(A, 1, loading(A), "Jerky", 1, 0, stand(A))
        assertTrue(l.tip.optBoolean("loading")); assertFalse(l.stand)          // no card ever: the game's loading one over the stand-in
        assertTrue(m.pick(A, 1, none(A), "Jerky", 1, 10, stand(A)).stand)     // none: the stand-in, not a header-only card
        // a full one kept: a later loading re-read never replaces it
        val f = full(A)
        m.pick(A, 1, f, "Jerky", 1, 20, stand(A))
        assertSame(f, m.pick(A, 1, loading(A), "Jerky", 1, 5_000, stand(A)).tip)
        assertSame(f, m.pick(A, 1, none(A), "Jerky", 1, 9_000, stand(A)).tip)
    }

    /** what is worn changed: the kept ones go (their compare), the current card is held while the fresh one comes */
    @Test fun wornGearChangeForgetsTheKeptOnes() {
        val m = BagsCursor.TipMemory()
        m.worn("900,901")
        m.pick(A, 1, full(A), "Tunic", 2, 0, stand(A))
        m.worn("900,901"); assertEquals(1, m.size)                            // the same gear: kept
        m.worn("950,901"); assertEquals(0, m.size)
        assertTrue(m.pick(A, 1, null, "Tunic", 2, 100, stand(A)).held)        // no flash while the fresh compare is on its way
        assertTrue(m.pick(A, 1, null, "Tunic", 2, 10_000, stand(A)).stand)
    }

    @Test fun theMemoryIsBounded() {
        val m = BagsCursor.TipMemory(max = 4)
        for (s in 1..10) m.offer(full("bag 0 $s ${500 + s}"), "bag 0 $s ${500 + s}", 1)
        assertEquals(4, m.size)
    }

    /** the State's side: a ctl tooltip is kept only while its item (the key's id) is still in that slot */
    @Test fun theStateKeepsTheTooltipOfTheItemInTheSlot() {
        val st = BagsView.State()
        val items = JSONArray().put(JSONObject().put("bag", 0).put("slot", 1).put("id", 501).put("name", "Jerky").put("count", 3))
        val bags = JSONObject().put("bags", JSONArray().put(JSONObject().put("bag", 0).put("slots", 16))).put("items", items)
            .put("equipped", JSONObject().put("slots", JSONArray().put(JSONObject().put("slot", 5).put("id", 900))))
        st.set(bags)
        st.ctl = JSONObject().put("on", true).put("mode", "grid").put("tip", full("bag 0 1 999"))      // another item's: not kept
        st.noteTip(bags); assertEquals(0, st.tips.size)
        st.ctl = JSONObject().put("on", true).put("mode", "grid").put("tip", full(A))
        st.noteTip(bags); assertEquals(1, st.tips.size)
        // a later document (the heartbeat) with no tooltip: the card is the kept full one
        st.ctl = JSONObject().put("on", true).put("mode", "grid")
        st.noteTip(bags)
        assertFalse(st.tips.pick(A, 3, st.ctl!!.optJSONObject("tip"), "Jerky", 1, 99_999, stand(A)).stand)
        // the chest swapped: forgotten
        bags.put("equipped", JSONObject().put("slots", JSONArray().put(JSONObject().put("slot", 5).put("id", 950))))
        st.noteTip(bags); assertEquals(0, st.tips.size)
    }
}
