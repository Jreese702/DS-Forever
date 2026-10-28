package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CROSS-SIDE (PARTY_BUILD.md INTEGRATION): the documents the add-on's Lua REALLY sends, as Safety.lua's encoder writes them (a nil
 * field is ABSENT, an empty table is `[]`), dumped by tools/test_party.py into src/test/resources/party-fixtures (the harness fails
 * when they go stale), read here by the app's own parsers, models and taps.
 */
class PartyFixturesTest {
    private fun fx(name: String): JSONObject {
        val s = javaClass.classLoader!!.getResourceAsStream("party-fixtures/$name.json")
        assertNotNull("fixture $name (run tools/test_party.py with PARTY_FIXTURES_WRITE=1)", s)
        return JSONObject(s!!.bufferedReader().use { it.readText() })
    }

    @Test fun partySoloOff() {
        val p = PartyModel.parseParty(fx("party_solo_off"))!!
        assertFalse(p.on); assertNull(p.box); assertNull(p.aside); assertFalse(p.grp); assertEquals("bound", p.sel)
        assertEquals(1, p.n)
        val me = p.m[0]
        assertEquals("player", me.u); assertEquals(0, me.i); assertEquals("Korvash", me.n); assertEquals("SHAMAN", me.c)
        assertEquals("MANA", me.pt); assertEquals(100, me.hp); assertTrue(me.full); assertNull(me.rng); assertEquals(400, me.hpMax)
    }

    @Test fun partyThreeOnAndOff() {
        val on = PartyModel.parseParty(fx("party_three_on"))!!
        assertTrue(on.on); assertTrue(on.onLive); assertEquals(1, on.box); assertEquals(3, on.n)
        assertEquals(listOf("player", "party1", "party2"), on.m.map { it.u }); assertEquals(listOf(0, 1, 2), on.m.map { it.i })
        assertEquals("TANK", on.m[1].role); assertEquals("ffc69b6d", on.m[1].col)
        val off = PartyModel.parseParty(fx("party_three_off"))!!
        assertFalse(off.on); assertNull(off.box)
    }

    @Test fun cfgShamanDefaults() {
        val c = PartyModel.parseCfg(fx("partycfg_shaman_default"))!!
        assertEquals(331, c.slot("a")!!.id); assertTrue(c.slot("a")!!.k); assertTrue(c.slot("a")!!.d)
        assertFalse(c.slot("x")!!.k)
        assertTrue(c.slot("zra")!!.res)
        // absent slots (the encoder drops nil) = Empty
        assertNull(c.slot("zly")); assertNull(c.slot("zrx")); assertNull(c.slot("zry"))
        assertNull(c.yc); assertNull(c.touch)
        assertFalse(c.sy.on); assertTrue(c.sy.list.isEmpty()); assertTrue(c.sy.known.isEmpty()); assertTrue(c.sy.pick.isEmpty())
        assertEquals(listOf(331), c.spells.map { it.id })
        val pad = c.pad
        assertEquals("party", pad.row); assertEquals(0, pad.tgt); assertEquals(5, pad.tsp); assertEquals(10, pad.on); assertEquals(11, pad.off)
        assertEquals(12, pad.focus); assertEquals(17, pad.menu); assertEquals(22, pad.resend); assertEquals(23, pad.pbsel)
        assertEquals(31, pad.pbRemove); assertEquals(32, pad.pbUp); assertEquals(33, pad.pbDown); assertEquals(34, pad.pbAdd)
        assertEquals(35, pad.mdone); assertEquals("padtab", pad.tabRow); assertEquals(26, pad.tab); assertEquals(27, pad.slot0); assertEquals(37, pad.none)
    }

    /** `"slots":[]` (an empty Lua table) = every slot Empty, never a failed parse */
    @Test fun cfgWarriorEmptyArrays() {
        val c = PartyModel.parseCfg(fx("partycfg_warrior_empty"))
        assertNotNull(c)
        assertTrue(PartyModel.SLOTS.all { c!!.slot(it) == null })
        assertTrue(c!!.spells.isEmpty())
        // the strip: every face Empty
        assertNotNull(c.pad.on)
    }

    @Test fun cfgFull() {
        val c = PartyModel.parseCfg(fx("partycfg_full"))!!
        assertNull(c.slot("x"))                                         // a CHOSEN Empty is absent too
        assertEquals(331, c.yc!!.id); assertEquals(8004, c.touch!!.id)
        assertTrue(c.sy.on); assertEquals(listOf(131, 546), c.sy.list.map { it.id })
        assertEquals(131, c.sy.pick["party1"]!!.id); assertEquals(546, c.sy.pick["party2"]!!.id); assertNull(c.sy.pick["player"])
        // the touch spell routes a cell tap to tsp + i
        assertEquals(listOf("party" to 6), PartyTaps.touch(c, 1))
        assertEquals(listOf("party" to 1), PartyTaps.touch(PartyModel.parseCfg(fx("partycfg_shaman_default")), 1))
        // a PB op = ONE batch of distinct cells (TapPad's 0.25 s same-cell guard never swallows one of them)
        val op = PartyTaps.pbOp(c, 2, PartyTaps.PbOp.REMOVE)!!
        assertEquals(listOf("party" to 24, "party" to 31), op)
        assertEquals(op.size, op.toSet().size)
        assertEquals(listOf("party" to 11), PartyTaps.off(c))
        assertEquals(listOf("padtab" to 30, "padtab" to 37), PartyTaps.setNone(c, "touch"))
    }

    @Test fun boxes() {
        val st = PartyModel.State()
        st.onParty(PartyModel.parseParty(fx("party_three_on"))!!, current = true, now = 0L)
        val on = PartyModel.parseBox(fx("partybox_on"))!!
        assertEquals("on", on.k); assertNull(on.u); assertNull(on.key); assertNull(on.sp)
        assertTrue(st.onBox(on, true))
        val mv = PartyModel.parseBox(fx("partybox_move"))!!
        assertEquals("move", mv.k); assertEquals(2, mv.box); assertEquals("party1", mv.u); assertEquals(PartyModel.Dir.DOWN, PartyModel.dirOf(mv.key))
        assertTrue(st.onBox(mv, true)); assertEquals(2, st.box)
        assertFalse(st.onBox(mv, true))                                 // a repeat n is dropped
        val cast = PartyModel.parseBox(fx("partybox_cast"))!!
        assertEquals("cast", cast.k); assertEquals("Healing Wave", cast.sp)
        val none = PartyModel.parseBox(fx("partybox_none_zl"))!!
        assertEquals("none", none.k); assertEquals("zl", none.ly); assertNull(none.sp)
        val menu = PartyModel.parseBox(fx("partybox_menu"))!!
        assertEquals("menu", menu.k); assertTrue(menu.pm); assertEquals(1, PartyModel.unitIndex(menu.u))
        assertTrue(st.onBox(menu, true)); assertTrue(st.padMenu)
        val down = PartyModel.parseBox(fx("partybox_key_down"))!!
        assertEquals("key", down.k); assertEquals("PADDDOWN", down.key); assertTrue(down.pm)
        // the app's menu driven by those keys (PlayerMenu, the same words)
        val pm = PlayerMenu(greyKeys = PlayerMenu.PARTY_GREY)
        pm.openReading(); pm.onDoc(fx("pmenu_party2"))
        assertEquals(PlayerMenu.Do.None, pm.key(down.key!!))
        val b = PartyModel.parseBox(fx("partybox_key_b"))!!
        assertEquals("PAD2", b.key)
        assertEquals(PlayerMenu.Do.Close, pm.key(b.key!!))
        // a `key` press with no menu of the app's driving the pad: stranded, MENU DONE is due; with the menu: not
        assertTrue(PartyModel.strandedMenu(down, padMenuOpen = false))
        assertFalse(PartyModel.strandedMenu(down, padMenuOpen = true))
        assertFalse(PartyModel.strandedMenu(mv, padMenuOpen = false))
        val off = PartyModel.parseBox(fx("partybox_off"))!!
        assertEquals("off", off.k); assertFalse(off.on); assertFalse(off.pm)
        assertTrue(st.onBox(off, true)); assertFalse(st.party!!.on); assertNull(st.party!!.box)
    }

    @Test fun acks() {
        val resend = PartyModel.parseAck(fx("partyack_resend"))!!
        assertTrue(resend.ok); assertNull(resend.why); assertFalse(PartyModel.forSettings(resend))
        val cell = PartyModel.parseAck(fx("partyack_cell"))!!
        assertEquals("cell", cell.op); assertFalse(cell.ok); assertEquals("unknown column", cell.why); assertFalse(PartyModel.forSettings(cell))
        val tt = PartyModel.parseAck(fx("partyack_touch_target"))!!
        assertEquals("touch", tt.op); assertEquals("target", tt.why); assertNull(tt.slot); assertFalse(PartyModel.forSettings(tt))
        val combat = PartyModel.parseAck(fx("partyack_combat"))!!
        assertEquals("combat", combat.why); assertEquals("a", combat.slot); assertEquals(331, combat.id); assertTrue(PartyModel.forSettings(combat))
        val ok = PartyModel.parseAck(fx("partyack_slot_ok"))!!
        assertTrue(ok.ok); assertTrue(PartyModel.forSettings(ok))
        val pb = PartyModel.parseAck(fx("partyack_pb"))!!
        assertEquals("pb", pb.op); assertTrue(pb.ok); assertEquals(131, pb.id); assertNull(pb.slot); assertTrue(PartyModel.forSettings(pb))
    }

    /** padtabctl's party fields: ppos 1..12, pfocus rows / right, pright 0-BASED, the chooser lists "party" and "pbuff" */
    @Test fun padTabCtl() {
        val r = PadMapModel.parseCtl(fx("padtabctl_party_right"))!!
        assertEquals("party", r.view); assertEquals(5, r.tcur); assertEquals(2, r.ppos); assertEquals("right", r.pfocus); assertEquals(0, r.pright)
        assertNull(r.chooser)
        val ch = PadMapModel.parseCtl(fx("padtabctl_party_chooser"))!!
        assertEquals("party", ch.chooser!!.list); assertEquals(2, ch.chooser!!.row); assertEquals("331", ch.chooser!!.id)
        // row 1 = the pseudo row, so the controller's row 2 = partycfg.spells[0] (the app's list: the pseudo row first)
        val cfg = PartyModel.parseCfg(fx("partycfg_full"))!!
        assertEquals(331, cfg.spells[ch.chooser!!.row - 2].id)
        val y = PadMapModel.parseCtl(fx("padtabctl_party_y_add"))!!
        assertEquals(4, y.ppos); assertEquals("right", y.pfocus)
        // Smart Y on, two buffs: rows buff, buff, Add, In combat; pright 2 = Add
        val rows = cfg.sy.list.map { "buff" } + "add" + (if (PartyTabPage.YC_ROW_IN_SMART_Y) listOf("yc") else emptyList())
        assertEquals("add", rows[y.pright])
        val pb = PadMapModel.parseCtl(fx("padtabctl_party_pbuff"))!!
        assertEquals("pbuff", pb.chooser!!.list); assertEquals(1, pb.chooser!!.row)
        assertTrue(pb.chooser!!.list in PartyModel.CTL_LISTS && ch.chooser!!.list in PartyModel.CTL_LISTS)
    }

    /** q49's pmenu for a party unit: Set Focus on the party row, Whisper runnable + ap, Promote / Uninvite ask first, Inspect greyed */
    @Test fun pmenuParty() {
        val d = fx("pmenu_party2")
        assertEquals("party", d.optString("src")); assertEquals("party2", d.optString("unit")); assertEquals("Iselle", d.optString("nm"))
        val m = PlayerMenu(greyKeys = PlayerMenu.PARTY_GREY)
        m.openReading(); m.onDoc(d)
        val rows = m.rows()
        val byText = rows.associateBy { it.text }
        assertEquals(14, byText["Set Focus"]!!.secure); assertEquals("party", byText["Set Focus"]!!.secureRow)
        assertEquals(1, byText["Clear Focus"]!!.secure); assertEquals("pfocus", byText["Clear Focus"]!!.secureRow)
        assertFalse(byText["Inspect"]!!.enabled)
        assertEquals("whisper", byText["Whisper"]!!.app); assertEquals(0, byText["Whisper"]!!.id)
        assertNotNull(byText["Uninvite"]!!.confirm); assertNotNull(byText["Promote to Party Leader"]!!.confirm)
        // Set Focus (the first row): a secure tap on the party row
        assertEquals(PlayerMenu.Do.Secure(14, "Set Focus", "party"), m.key("PAD1"))
        // Whisper: runs the digits AND opens the Chat room (ap)
        m.openReading(); m.onDoc(d)
        m.key("PADDDOWN")
        assertEquals(PlayerMenu.Do.Run(0, "Whisper", "whisper"), m.key("PAD1"))
        // Uninvite: asks first (No under the cursor), Yes runs it
        m.openReading(); m.onDoc(d)
        val ui = rows.indexOfFirst { it.text == "Uninvite" }
        repeat(ui) { m.key("PADDDOWN") }
        assertEquals(PlayerMenu.Do.None, m.key("PAD1"))
        assertEquals("Uninvite?", m.heading())
        m.key("PADDUP")
        assertEquals(PlayerMenu.Do.Run(4, "Uninvite"), m.key("PAD1"))
    }

    /** THE USER'S RULING: leaving Home (a room, a Home module full screen) turns Party mode off; the add-on's off then reads back */
    @Test fun offWhenLeavingHome() {
        assertTrue(PartyModel.OFF_ON_LEAVE)
        val on = PartyModel.parseParty(fx("party_three_on"))!!
        val off = PartyModel.parseParty(fx("party_three_off"))!!
        assertTrue(PartyModel.offOnLeave(on))
        assertTrue(PartyModel.offOnLeave(on.copy(aside = "room")))      // suspended still counts: off is absolute
        assertFalse(PartyModel.offOnLeave(off)); assertFalse(PartyModel.offOnLeave(null))
        assertTrue(PartyModel.leftRest(wasFull = false, nowFull = true))
        assertFalse(PartyModel.leftRest(wasFull = true, nowFull = true)); assertFalse(PartyModel.leftRest(wasFull = true, nowFull = false))
        // the tap is the add-on's own column (partycfg.pad.off)
        assertEquals(listOf("party" to 11), PartyTaps.off(PartyModel.parseCfg(fx("partycfg_shaman_default"))))
        // back on Home: the add-on's documents say off (party on false, partybox k off), so nothing is drawn as on
        val st = PartyModel.State()
        st.onParty(on, current = true, now = 0L)
        st.onBox(PartyModel.parseBox(fx("partybox_on"))!!, true)
        st.onParty(off, current = true, now = 1000L)
        assertFalse(st.party!!.onLive)
        assertNull(st.move(PartyModel.Dir.DOWN))                        // no optimistic box move with Party mode off
    }
    /** THE USER'S RULING: anything that takes the controller (a holder in a current interact document) ends Party mode, fights too */
    @Test fun takerEndsPartyMode() {
        val st = PartyModel.State()
        val on = PartyModel.parseParty(fx("party_three_on"))!!
        st.onParty(on, current = true, now = 0L)
        assertFalse(st.takerSeen("", 0L))                              // nobody holds
        for (h in listOf("pad:radial", "pad:bar", "npc", "spells", "pad:home")) {
            val s2 = PartyModel.State(); s2.onParty(on, current = true, now = 0L)
            assertTrue(h, s2.takerSeen(h, 100L))
        }
        assertTrue(st.takerSeen("npc", 100L))                          // a vendor / loot window granted in a fight
        assertFalse(st.takerSeen("npc", 600L))                         // once per holder ...
        assertTrue(st.takerSeen("npc", 100L + PartyModel.TAKER_RETRY_MS))   // ... again while Party mode still says on (a lost tap)
        st.onParty(PartyModel.parseParty(fx("party_three_off"))!!, current = true, now = 3000L)
        assertFalse(st.takerSeen("npc", 9000L))                        // off: nothing to do
        // the fight's own state does not matter: the tap (party col 11, a secure click) is the route in a fight
        val fight = PartyModel.State(); fight.onParty(on.copy(fight = true), current = true, now = 0L)
        assertTrue(fight.takerSeen("spells", 10L))
        assertEquals(listOf("party" to 11), PartyTaps.off(PartyModel.parseCfg(fx("partycfg_full"))))
    }
}
