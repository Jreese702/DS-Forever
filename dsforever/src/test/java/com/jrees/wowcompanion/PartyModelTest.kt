package com.jrees.wowcompanion

import com.jrees.wowcompanion.PartyModel.Dir
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PARTY_BUILD.md section 1's documents (m129: plain facts only), the one-column NAV (the user's decisions 2026-11-11) and 1.1's
 *  resend / stale-tap rules */
class PartyModelTest {
    companion object {
        val PARTY = """{"type":"party","v":"m77","rs":12,"p":{
  "on":true,"aside":null,"box":2,"fight":false,"grp":true,"sel":"bound",
  "m":[{"u":"player","i":0,"n":"Korvash","c":"SHAMAN","col":"ff0070dd","role":"HEALER","dead":false,"off":false,
        "pt":"MANA","lv":20},
       {"u":"party1","i":1,"n":"Brannoc","c":"WARRIOR","col":"ffc69b6d","role":"TANK","dead":false,"off":false,
        "pt":"RAGE","lv":21,"rng":true}]}}"""
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
  "pad":{"row":"party","pick":0,"on":10,"off":11,"focus":12,"menu":17,"resend":22,"pbsel":23,
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
        assertEquals("MANA", me.pt); assertEquals(20, me.lv)
        assertNull(me.rng); assertFalse(me.dim)
        val t = p.m[1]
        assertEquals("party1", t.u); assertEquals(1, t.i); assertEquals("TANK", t.role); assertEquals("RAGE", t.pt)
        assertEquals(21, t.lv); assertEquals(true, t.rng); assertFalse(t.dim)
        // m129 (party_design.md A.4): offline or out of range by the shared answer = dim (0.45, the top screen's); you never by range
        assertTrue(t.copy(rng = false).dim); assertTrue(t.copy(off = true).dim); assertFalse(me.copy(rng = false).dim)
        assertEquals(0.45f, PartyModel.DIM)
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
        assertEquals("party", pad.row); assertEquals(0, pad.pick); assertEquals(10, pad.on); assertEquals(11, pad.off)
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
        // a sparse member: defaults (no level, no role, the index from the unit); an older add-on's health fields are ignored
        val p = PartyModel.parseParty(JSONObject("""{"type":"party","p":{"m":[{"u":"party3","hp":40,"full":false,"lv":0}]}}"""))!!
        assertEquals(3, p.m[0].i); assertEquals("?", p.m[0].n); assertNull(p.m[0].lv); assertEquals("NONE", p.m[0].role)
        assertFalse(p.on); assertNull(p.box)
        // a cfg with no pad: every column missing (actions unavailable)
        val c = PartyModel.parseCfg(JSONObject("""{"type":"partycfg","c":{}}"""))!!
        assertNull(c.pad.pick); assertNull(c.pad.row); assertNull(c.slot("a")); assertFalse(c.sy.on)
        // box only while on
        val off = PartyModel.parseParty(JSONObject(PARTY.replace("\"on\":true", "\"on\":false")))!!
        assertNull(off.box)
        // aside = suspended: drawn as off
        val aside = PartyModel.parseParty(JSONObject(PARTY.replace("\"aside\":null", "\"aside\":\"room\"")))!!
        assertTrue(aside.on); assertFalse(aside.onLive)
        // m132: in a fight `on` alone (a stale `aside` never hides a live Party mode; the add-on never steps aside at a fight's start)
        assertTrue(aside.copy(fight = true).onLive)
        assertFalse(aside.copy(fight = true, on = false).onLive)
        // ... so the box moves and the plates pick in a fight even with an `aside` left over
        val st = PartyModel.State()
        st.onParty(aside.copy(fight = true), current = true, now = 0L)
        assertNotNull(st.move(PartyModel.Dir.UP))
    }

    /** m133 (review): the tab's tap follows its look; a prompt's "dialogs" capture never taps off */
    @Test fun tabFollowsItsLookAndDialogsOnlySuspend() {
        val p = party()
        assertFalse(PartyModel.tabWants(p))                                                 // drawn on -> off
        assertTrue(PartyModel.tabWants(p.copy(on = false)))                                 // drawn off -> on
        assertTrue(PartyModel.tabWants(p.copy(aside = "managed")))                          // suspended by a prompt out of combat: drawn off -> on
        assertFalse(PartyModel.tabWants(p.copy(aside = "managed", fight = true)))           // in a fight `on` alone: drawn on -> off
        assertTrue(PartyModel.tabWants(null))
        val st = PartyModel.State()
        st.onParty(p, current = true, now = 0L)
        assertFalse(st.takerSeen("dialogs", 0L))                                            // a popup's mirror: suspended only
        assertTrue(st.takerSeen("spells", 10_000L))                                         // a room still ends it out of combat
    }

    @Test fun unitIndex() {
        assertEquals(0, PartyModel.unitIndex("player")); assertEquals(1, PartyModel.unitIndex("party1"))
        assertEquals(4, PartyModel.unitIndex("party4")); assertNull(PartyModel.unitIndex("party5")); assertNull(PartyModel.unitIndex("raid1"))
        assertEquals(5, PartyModel.unitIndex("pet"))                                            // m131: the pet's index (its pick cell)
        assertNull(PartyModel.unitIndex(null))
    }

    /**
     * m134 THE PLAYER / PET ROW (the user, 2026-11-11, after using 1428: "I think the pet should be to the right of the player"):
     * PartyModel.nav equals the GEOMETRIC table tools/test_party.py builds from the rows and tests the add-on's OV.partyNav and PARTY-NAV
     * snippet against (party-fixtures/party_nav.json): every shape (n 1..5 without the pet, 2..6 with it), every box, all four
     * directions - so the app, the pure Lua and the secure snippet agree for every shape
     */
    @Test fun navIsTheAddOnsTable() {
        val s = javaClass.classLoader!!.getResourceAsStream("party-fixtures/party_nav.json")!!.bufferedReader().use { it.readText() }
        val rows = JSONObject(s).getJSONArray("rows")
        val seen = HashSet<Triple<Int, Boolean, Int>>()
        for (k in 0 until rows.length()) {
            val r = rows.getJSONObject(k)
            val n = r.getInt("n"); val box = r.getInt("box"); val pet = r.getBoolean("pet")
            seen.add(Triple(n, pet, box))
            for ((key, d) in listOf("up" to Dir.UP, "down" to Dir.DOWN, "left" to Dir.LEFT, "right" to Dir.RIGHT)) {
                val want = if (r.isNull(key)) null else r.getInt(key)
                assertEquals("n $n pet $pet box $box $key", want, PartyModel.nav(n, box, d, pet))
            }
        }
        // every shape: no pet n 1..5 (15 boxes), the pet n 2..6 (20 boxes)
        assertEquals((1..5).sum() + (2..6).sum(), seen.size)
        // a box past n clamps
        assertEquals(2, PartyModel.nav(3, 5, Dir.UP))
    }

    /** m134: the rows - you (+ the pet on your right) first, the party under it: 1-2 full rows, 3: full + shared, 4: 2x2 */
    @Test fun rowsWithAndWithoutThePet() {
        assertEquals(listOf(listOf(1)), PartyModel.rows(1))
        assertEquals(listOf(listOf(1), listOf(2), listOf(3)), PartyModel.rows(3))
        assertEquals(listOf(listOf(1), listOf(2), listOf(3, 4)), PartyModel.rows(4))
        assertEquals(listOf(listOf(1), listOf(2, 3), listOf(4, 5)), PartyModel.rows(5))
        assertEquals(listOf(listOf(1, 2)), PartyModel.rows(2, pet = true))
        assertEquals(listOf(listOf(1, 2), listOf(3)), PartyModel.rows(3, pet = true))
        assertEquals(listOf(listOf(1, 2), listOf(3), listOf(4)), PartyModel.rows(4, pet = true))
        assertEquals(listOf(listOf(1, 2), listOf(3), listOf(4, 5)), PartyModel.rows(5, pet = true))
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6)), PartyModel.rows(6, pet = true))
        // you <-> the pet in the first row; the pet's Down: party1 (a full row) or party2 (the 2x2's right)
        assertEquals(2, PartyModel.nav(3, 1, Dir.RIGHT, pet = true)); assertEquals(1, PartyModel.nav(3, 2, Dir.LEFT, pet = true))
        assertEquals(3, PartyModel.nav(3, 2, Dir.DOWN, pet = true)); assertEquals(4, PartyModel.nav(6, 2, Dir.DOWN, pet = true))
        assertNull(PartyModel.nav(2, 1, Dir.DOWN, pet = true)); assertNull(PartyModel.nav(2, 2, Dir.UP, pet = true))
    }

    /** m134 (the user, 2026-11-11: "highlighting it should target"): the app's optimistic move follows the pet's row too */
    @Test fun theStatesMoveKnowsThePet() {
        val s = PartyModel.State()
        val doc = javaClass.classLoader!!.getResourceAsStream("party-fixtures/party_pet_on.json")!!.bufferedReader().use { it.readText() }
        s.onParty(PartyModel.parseParty(JSONObject(doc))!!.copy(on = true, box = 1), true, 0L)
        assertEquals(2, s.move(Dir.RIGHT)); assertEquals(3, s.move(Dir.DOWN)); assertEquals(1, s.move(Dir.UP))
    }

    /** p37 the Controller tab's places (PadTabCtl's slotAt): A / X / Y per layer, the rest locked; p38: all four D-pad places move the pick */
    @Test fun controllerTabPlaces() {
        assertEquals("a", PartyModel.slotAt(1, 8)); assertEquals("x", PartyModel.slotAt(1, 5)); assertEquals("yc", PartyModel.slotAt(1, 6))
        assertEquals("zla", PartyModel.slotAt(2, 8)); assertEquals("zly", PartyModel.slotAt(2, 6)); assertEquals("zrx", PartyModel.slotAt(3, 5))
        for (p in listOf(1, 2, 3, 4, 7)) assertNull(PartyModel.slotAt(1, p))
        for (p in 1..8) assertNull(PartyModel.slotAt(4, p))                                 // ZL + ZR: locked
        for (s in PartyModel.PICKS.filter { it != "touch" }) PartyModel.placeOf(s)!!.let { (set, pos) -> assertEquals(s, PartyModel.slotAt(set, pos)) }
        assertNull(PartyModel.placeOf("touch"))
        assertTrue((1..4).all { PartyModel.movesPick(it) }); assertFalse(PartyModel.movesPick(7) || PartyModel.movesPick(8))
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
        // m131: five entries = you | (2 3) | (4 5): Down onto the left of the shared row, Right, Down, Up
        assertEquals(2, s.move(Dir.DOWN)); assertNull(s.move(Dir.LEFT)); assertEquals(3, s.move(Dir.RIGHT)); assertEquals(5, s.move(Dir.DOWN))
        assertEquals(3, s.move(Dir.UP)); assertEquals(1, s.move(Dir.UP)); assertEquals(2, s.move(Dir.DOWN))
        // m129 a plate's tap draws the pick at once (the add-on decides): another member moves it; the picked one does not (its Touch)
        assertEquals(5, s.choose(4)); assertEquals(5, s.box); assertNull(s.choose(4)); assertEquals(1, s.choose(0)); assertNull(s.choose(7))
        s.onParty(p5.copy(on = false, box = null), true, 0L)
        assertNull(s.move(Dir.UP)); assertNull(s.choose(2))                       // off: a tap is Target, nothing drawn as a pick
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
