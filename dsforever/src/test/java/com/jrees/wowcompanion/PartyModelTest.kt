package com.jrees.wowcompanion

import com.jrees.wowcompanion.PartyModel.Dir
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PARTY_BUILD.md section 1's documents, 3.4's NAV and 1.1's resend / stale-tap rules */
class PartyModelTest {
    companion object {
        val PARTY = """{"type":"party","v":"m77","rs":12,"p":{
  "on":true,"aside":null,"box":2,"fight":false,"grp":true,"sel":"bound",
  "m":[{"u":"player","i":0,"n":"Korvash","c":"SHAMAN","col":"ff0070dd","role":"HEALER","dead":false,"off":false,
        "pt":"MANA","hpMax":412,"pmMax":380,"hp":100,"full":true},
       {"u":"party1","i":1,"n":"Brannoc","c":"WARRIOR","col":"ffc69b6d","role":"TANK","dead":false,"off":false,
        "pt":"RAGE","hpMax":510,"hp":72,"full":false,"rng":true}]}}"""
        val CFG = """{"type":"partycfg","v":"m77","c":{
  "slots":{"a":{"id":331,"n":"Healing Wave","i":136052,"k":true,"d":true},
           "x":{"id":8004,"n":"Lesser Healing Wave","i":136043,"k":false,"d":true},
           "zla":{"id":526,"n":"Cure Poison","i":136067,"k":false,"d":true},
           "zlx":{"id":2870,"n":"Cure Disease","i":136083,"k":false,"d":true},
           "zly":null,"zra":{"id":2008,"n":"Ancestral Spirit","i":136077,"k":false,"d":true,"res":true},"zrx":null,"zry":null},
  "yc":null,"touch":null,
  "spells":[{"id":331,"n":"Healing Wave","i":136052}],
  "sy":{"on":false,"list":[{"id":131,"n":"Water Breathing","i":136148,"k":false}],"known":[],
        "pick":{"party1":{"id":131,"n":"Water Breathing","i":136148}}},
  "pad":{"row":"party","tgt":0,"tsp":5,"on":10,"off":11,"focus":12,"menu":17,"resend":22,"pbsel":23,
         "pbops":{"remove":31,"up":32,"down":33,"add":34},"mdone":35,
         "ptab":{"row":"padtab","tab":26,"slot0":27,"none":37}}}}"""
        val BOX = """{"type":"partybox","v":"m77","b":{"n":57,"on":true,"box":2,"cnt":3,"u":"party1","k":"cast","key":"PAD1","ly":"",
  "sp":"Healing Wave","fight":true,"pm":false}}"""
        val ACK = """{"type":"partyack","v":"m77","op":"slot","ok":true,"why":null,"slot":"a","id":331}"""
        fun party() = PartyModel.parseParty(JSONObject(PARTY))!!
        fun cfg() = PartyModel.parseCfg(JSONObject(CFG))!!
    }

    @Test fun partyParsesFieldForField() {
        val p = party()
        assertEquals("m77", p.v); assertEquals(12, p.rs); assertTrue(p.on); assertNull(p.aside); assertEquals(2, p.box)
        assertFalse(p.fight); assertTrue(p.grp); assertEquals("bound", p.sel); assertEquals(2, p.n); assertTrue(p.onLive)
        val me = p.m[0]
        assertEquals("player", me.u); assertEquals(0, me.i); assertEquals("Korvash", me.n); assertEquals("SHAMAN", me.c)
        assertEquals("ff0070dd", me.col); assertEquals("HEALER", me.role); assertFalse(me.dead); assertFalse(me.off)
        assertEquals("MANA", me.pt); assertEquals(412, me.hpMax); assertEquals(380, me.pmMax); assertEquals(100, me.hp); assertTrue(me.full)
        assertNull(me.rng)
        val t = p.m[1]
        assertEquals("party1", t.u); assertEquals(1, t.i); assertEquals("TANK", t.role); assertEquals("RAGE", t.pt)
        assertNull(t.pmMax); assertEquals(72, t.hp); assertFalse(t.full); assertEquals(true, t.rng)
    }

    @Test fun cfgParsesFieldForField() {
        val c = cfg()
        assertEquals(331, c.slot("a")!!.id); assertEquals("Healing Wave", c.slot("a")!!.n); assertEquals(136052, c.slot("a")!!.i)
        assertTrue(c.slot("a")!!.k); assertTrue(c.slot("a")!!.d)
        assertFalse(c.slot("x")!!.k)
        assertNull(c.slot("zly")); assertNull(c.slot("zrx")); assertNull(c.slot("zry"))
        assertTrue(c.slot("zra")!!.res); assertFalse(c.slot("zla")!!.res)
        assertNull(c.yc); assertNull(c.touch)
        assertEquals(listOf(331), c.spells.map { it.id })
        assertFalse(c.sy.on); assertEquals(listOf(131), c.sy.list.map { it.id }); assertFalse(c.sy.list[0].k)
        assertTrue(c.sy.known.isEmpty()); assertEquals(131, c.sy.pick["party1"]!!.id)
        val pad = c.pad
        assertEquals("party", pad.row); assertEquals(0, pad.tgt); assertEquals(5, pad.tsp); assertEquals(10, pad.on); assertEquals(11, pad.off)
        assertEquals(12, pad.focus); assertEquals(17, pad.menu); assertEquals(22, pad.resend); assertEquals(23, pad.pbsel)
        assertEquals(31, pad.pbRemove); assertEquals(32, pad.pbUp); assertEquals(33, pad.pbDown); assertEquals(34, pad.pbAdd)
        assertEquals(35, pad.mdone); assertEquals("padtab", pad.tabRow); assertEquals(26, pad.tab); assertEquals(27, pad.slot0)
        assertEquals(37, pad.none)
    }

    @Test fun boxAndAckParse() {
        val b = PartyModel.parseBox(JSONObject(BOX))!!
        assertEquals(57, b.n); assertTrue(b.on); assertEquals(2, b.box); assertEquals(3, b.cnt); assertEquals("party1", b.u)
        assertEquals("cast", b.k); assertEquals("PAD1", b.key); assertEquals("", b.ly); assertEquals("Healing Wave", b.sp)
        assertTrue(b.fight); assertFalse(b.pm)
        val a = PartyModel.parseAck(JSONObject(ACK))!!
        assertEquals("slot", a.op); assertTrue(a.ok); assertNull(a.why); assertEquals("a", a.slot); assertEquals(331, a.id)
    }

    @Test fun olderOrBrokenDocumentsAreNullOrDefaultsNeverACrash() {
        assertNull(PartyModel.parseParty(JSONObject("""{"type":"party","v":"m70"}""")))
        assertNull(PartyModel.parseParty(JSONObject("""{"type":"party","p":{"m":[]}}""")))
        assertNull(PartyModel.parseParty(JSONObject("""{"type":"party","p":{"m":"nope"}}""")))
        assertNull(PartyModel.parseParty(JSONObject("""{"type":"padmap"}""")))
        assertNull(PartyModel.parseCfg(JSONObject("""{"type":"partycfg"}""")))
        assertNull(PartyModel.parseBox(JSONObject("""{"type":"partybox","b":{}}""")))
        assertNull(PartyModel.parseAck(JSONObject("""{"type":"partyack"}""")))
        // a sparse member: defaults (hp full, no role, the index from the unit)
        val p = PartyModel.parseParty(JSONObject("""{"type":"party","p":{"m":[{"u":"party3"}]}}"""))!!
        assertEquals(3, p.m[0].i); assertEquals("?", p.m[0].n); assertEquals(100, p.m[0].hp); assertEquals("NONE", p.m[0].role)
        assertFalse(p.on); assertNull(p.box)
        // a cfg with no pad: every column missing (actions unavailable)
        val c = PartyModel.parseCfg(JSONObject("""{"type":"partycfg","c":{}}"""))!!
        assertNull(c.pad.tgt); assertNull(c.pad.row); assertNull(c.slot("a")); assertFalse(c.sy.on)
        // box only while on
        val off = PartyModel.parseParty(JSONObject(PARTY.replace("\"on\":true", "\"on\":false")))!!
        assertNull(off.box)
        // aside = suspended: drawn as off
        val aside = PartyModel.parseParty(JSONObject(PARTY.replace("\"aside\":null", "\"aside\":\"room\"")))!!
        assertTrue(aside.on); assertFalse(aside.onLive)
    }

    @Test fun unitIndex() {
        assertEquals(0, PartyModel.unitIndex("player")); assertEquals(1, PartyModel.unitIndex("party1"))
        assertEquals(4, PartyModel.unitIndex("party4")); assertNull(PartyModel.unitIndex("party5")); assertNull(PartyModel.unitIndex("raid1"))
        assertNull(PartyModel.unitIndex(null))
    }

    /** 3.4's table, row for row ("-" = null) */
    @Test fun navIsTheTable() {
        val t = listOf(
            "1 1 - - - -",
            "2 1 - 2 - -", "2 2 1 - - -",
            "3 1 - 2 - -", "3 2 1 - - 3", "3 3 1 - 2 -",
            "4 1 - 2 - -", "4 2 1 - - 3", "4 3 1 - 2 4", "4 4 1 - 3 -",
            "5 1 - 2 - -", "5 2 1 4 - 3", "5 3 1 5 2 -", "5 4 2 - - 5", "5 5 3 - 4 -",
        )
        for (row in t) {
            val f = row.split(" ")
            val n = f[0].toInt(); val from = f[1].toInt()
            for ((k, d) in listOf(Dir.UP, Dir.DOWN, Dir.LEFT, Dir.RIGHT).withIndex()) {
                val want = f[2 + k].toIntOrNull()
                assertEquals("n $n from $from $d", want, PartyModel.nav(n, from, d))
            }
        }
        // a box past n clamps
        assertEquals(1, PartyModel.nav(3, 5, Dir.UP))
    }

    @Test fun newestNWinsRepeatsAndReplaysDropped() {
        val s = PartyModel.State()
        s.onParty(party(), current = true, now = 0L)
        val b = PartyModel.parseBox(JSONObject(BOX))!!
        assertFalse(s.onBox(b, current = false))                     // a replay never counts
        assertNull(s.lastBox)
        assertTrue(s.onBox(b, current = true)); assertEquals(2, s.box)
        assertFalse(s.onBox(b.copy(box = 1), current = true)); assertEquals(2, s.box)   // the same n again
        assertTrue(s.onBox(b.copy(n = 58, box = 1), current = true)); assertEquals(1, s.box)
        assertTrue(s.onBox(b.copy(n = 1, box = 2), current = true)); assertEquals(2, s.box)  // a /reload restarts n
        // an "off" press turns Party mode off in the held roster at once
        assertTrue(s.onBox(b.copy(n = 2, on = false, box = null, k = "off"), current = true))
        assertFalse(s.party!!.on)
    }

    @Test fun optimisticMoveFollowsNavOnlyWhileOnLive() {
        val s = PartyModel.State()
        val p5 = party().let { p -> p.copy(box = 1, m = (0..4).map { i -> p.m[0].copy(u = if (i == 0) "player" else "party$i", i = i) }) }
        s.onParty(p5, true, 0L)
        assertEquals(2, s.move(Dir.DOWN)); assertEquals(3, s.move(Dir.RIGHT)); assertNull(s.move(Dir.RIGHT)); assertEquals(5, s.move(Dir.DOWN))
        s.onParty(p5.copy(on = false, box = null), true, 0L)
        assertNull(s.move(Dir.UP))
        s.onParty(p5.copy(aside = "room"), true, 0L)
        assertNull(s.move(Dir.UP))
    }

    @Test fun staleTapWindowAfterRs() {
        val s = PartyModel.State()
        s.onParty(party(), true, 1_000L)
        assertTrue(s.tapsAllowed(1_000L))                            // the first roster is no change
        s.onParty(party().copy(rs = 13), true, 2_000L)
        assertFalse(s.tapsAllowed(2_000L)); assertFalse(s.tapsAllowed(2_499L)); assertTrue(s.tapsAllowed(2_500L))
        s.onParty(party().copy(rs = 14), current = false, now = 3_000L)   // a replay does not start the window
        assertTrue(s.tapsAllowed(3_000L))
    }

    @Test fun replaysNeverAskAndTheResendRule() {
        val s = PartyModel.State()
        s.onParty(party(), current = false, now = 0L)                // a replayed party: shown, nothing asked
        assertNotNull(s.party)
        assertFalse(s.shouldAsk(PartyModel.Ask.FIRST_DOC, live = false, now = 0L))   // not live: never
        assertTrue(s.shouldAsk(PartyModel.Ask.FIRST_DOC, live = true, now = 100L))
        assertFalse(s.shouldAsk(PartyModel.Ask.FIRST_DOC, live = true, now = 20_000L))   // once
        assertFalse(s.shouldAsk(PartyModel.Ask.HOME_SHOWN, live = true, now = 5_000L))   // 10 s apart
        assertTrue(s.shouldAsk(PartyModel.Ask.HOME_SHOWN, live = true, now = 10_100L))
        s.onCfg(cfg(), current = true)
        assertFalse(s.shouldAsk(PartyModel.Ask.PARTY_ON, live = true, now = 40_000L))    // cfg is of this session
        s.newSession()
        assertTrue(s.shouldAsk(PartyModel.Ask.PARTY_ON, live = true, now = 40_000L))
        // a current party first: no FIRST_DOC ask
        val t = PartyModel.State(); t.onParty(party(), current = true, now = 0L)
        assertFalse(t.shouldAsk(PartyModel.Ask.FIRST_DOC, live = true, now = 0L))
        // 2026-11-04: with a current party document, Home's return asks nothing (each ask is a click)
        assertFalse(t.shouldAsk(PartyModel.Ask.HOME_SHOWN, live = true, now = 60_000L))
    }
}
