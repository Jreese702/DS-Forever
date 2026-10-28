package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * PARTY MODE, THE APP'S MODEL (PARTY_BUILD.md sections 1 + 3.4; the user's rulings in party_mode/SPEC_DRAFT.md). Pure: no Android
 * types, JVM-tested (PartyModelTest). The add-on (PadMap m77) is the truth for Party mode's on / off and the box; the app shows what
 * the documents say, moves its box at once on its own pad reader (the same [nav]) and lets the newest `partybox` correct it.
 *
 * Documents (add-on -> app): `party` (the roster and Party mode's state), `partycfg` (what every button / touch does, the choosers,
 * Smart Y's party list, the pad columns), `partybox` (every press Party mode takes), `partyack` (a settings answer). An older add-on
 * sends none of them, or a shape this build cannot read: every parse returns null, never a crash.
 */
object PartyModel {
    /** the slots of 1.3 in the Controller tab's pick order (padtab 27-36: k = col - 26) */
    val SLOTS = listOf("a", "x", "zla", "zlx", "zly", "zra", "zrx", "zry")
    /** padtab pick k (1..10) -> the slot's name (section 2.3) */
    val PICKS = listOf("a", "x", "yc", "touch", "zla", "zlx", "zly", "zra", "zrx", "zry")

    data class Member(
        val u: String, val i: Int, val n: String, val c: String, val col: String?, val role: String,
        val dead: Boolean, val off: Boolean, val pt: String, val hpMax: Int?, val pmMax: Int?,
        /** the health ESTIMATE, 0..100 (the app always prints "~") */
        val hp: Int, val full: Boolean, val rng: Boolean?,
    )

    data class Party(
        val v: String, val rs: Int, val on: Boolean, val aside: String?, val box: Int?, val fight: Boolean, val grp: Boolean,
        val sel: String, val m: List<Member>,
    ) {
        /** Party mode on and routing (not suspended by a room / window): the only state that draws a box */
        val onLive: Boolean get() = on && aside == null
        val n: Int get() = m.size
    }

    data class Spell(val id: Int, val n: String, val i: Int, val k: Boolean = true, val d: Boolean = false, val res: Boolean = false)

    data class SmartY(val on: Boolean, val list: List<Spell>, val known: List<Spell>, val pick: Map<String, Spell>)

    /** `partycfg.pad`: every column the app taps comes from here; null = that action unavailable */
    data class Pad(
        val row: String?, val tgt: Int?, val tsp: Int?, val on: Int?, val off: Int?, val focus: Int?, val menu: Int?,
        val resend: Int?, val pbsel: Int?, val pbRemove: Int?, val pbUp: Int?, val pbDown: Int?, val pbAdd: Int?, val mdone: Int?,
        val tabRow: String?, val tab: Int?, val slot0: Int?, val none: Int?,
    )

    data class Cfg(
        val v: String, val slots: Map<String, Spell?>, val yc: Spell?, val touch: Spell?, val spells: List<Spell>, val sy: SmartY,
        val pad: Pad,
    ) {
        fun slot(s: String): Spell? = slots[s]
    }

    data class Box(
        val n: Int, val on: Boolean, val box: Int?, val cnt: Int, val u: String?, val k: String, val key: String?, val ly: String,
        val sp: String?, val fight: Boolean, val pm: Boolean,
    )

    data class Ack(val op: String, val ok: Boolean, val why: String?, val slot: String?, val id: Int?)

    // ------------------------------------------------------------------------------------------------ parse

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k, "").ifEmpty { null } else null
    private fun JSONObject.int(k: String): Int? = if (has(k) && !isNull(k)) opt(k).let { (it as? Number)?.toInt() } else null
    private fun JSONObject.bool(k: String): Boolean? = if (has(k) && !isNull(k)) (opt(k) as? Boolean) else null

    fun parseParty(msg: JSONObject): Party? = runCatching {
        if (msg.optString("type") != "party") return null
        val p = msg.optJSONObject("p") ?: return null
        val arr = p.optJSONArray("m") ?: return null
        val m = ArrayList<Member>()
        for (k in 0 until minOf(arr.length(), 5)) {
            val o = arr.optJSONObject(k) ?: continue
            val u = o.str("u") ?: continue
            m.add(Member(
                u, o.int("i") ?: unitIndex(u) ?: k, o.str("n") ?: "?", o.str("c") ?: "", o.str("col"), o.str("role") ?: "NONE",
                o.bool("dead") ?: false, o.bool("off") ?: false, o.str("pt") ?: "", o.int("hpMax"), o.int("pmMax"),
                (o.int("hp") ?: 100).coerceIn(0, 100), o.bool("full") ?: false, o.bool("rng"),
            ))
        }
        if (m.isEmpty()) return null
        val on = p.bool("on") ?: false
        Party(msg.optString("v", ""), msg.optInt("rs", 0), on, p.str("aside"), if (on) p.int("box") else null,
            p.bool("fight") ?: false, p.bool("grp") ?: false, p.str("sel") ?: "off", m)
    }.getOrNull()

    private fun spell(o: JSONObject?): Spell? {
        if (o == null) return null
        val id = o.int("id") ?: return null
        return Spell(id, o.str("n") ?: "", o.int("i") ?: 0, o.bool("k") ?: true, o.bool("d") ?: false, o.bool("res") ?: false)
    }

    private fun spells(a: JSONArray?): List<Spell> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { spell(a.optJSONObject(it)) }
    }

    fun parseCfg(msg: JSONObject): Cfg? = runCatching {
        if (msg.optString("type") != "partycfg") return null
        val c = msg.optJSONObject("c") ?: return null
        val so = c.optJSONObject("slots")
        val slots = LinkedHashMap<String, Spell?>()
        for (s in SLOTS) slots[s] = spell(so?.optJSONObject(s))
        val syo = c.optJSONObject("sy")
        val pick = HashMap<String, Spell>()
        syo?.optJSONObject("pick")?.let { po -> for (u in po.keys()) spell(po.optJSONObject(u))?.let { pick[u] = it } }
        val sy = SmartY(syo?.bool("on") ?: false, spells(syo?.optJSONArray("list")).take(8), spells(syo?.optJSONArray("known")), pick)
        val po = c.optJSONObject("pad")
        val ops = po?.optJSONObject("pbops")
        val pt = po?.optJSONObject("ptab")
        val pad = Pad(po?.str("row"), po?.int("tgt"), po?.int("tsp"), po?.int("on"), po?.int("off"), po?.int("focus"), po?.int("menu"),
            po?.int("resend"), po?.int("pbsel"), ops?.int("remove"), ops?.int("up"), ops?.int("down"), ops?.int("add"), po?.int("mdone"),
            pt?.str("row"), pt?.int("tab"), pt?.int("slot0"), pt?.int("none"))
        Cfg(msg.optString("v", ""), slots, spell(c.optJSONObject("yc")), spell(c.optJSONObject("touch")), spells(c.optJSONArray("spells")),
            sy, pad)
    }.getOrNull()

    fun parseBox(msg: JSONObject): Box? = runCatching {
        if (msg.optString("type") != "partybox") return null
        val b = msg.optJSONObject("b") ?: return null
        val n = b.int("n") ?: return null
        Box(n, b.bool("on") ?: false, b.int("box"), b.int("cnt") ?: 0, b.str("u"), b.str("k") ?: "none", b.str("key"),
            b.optString("ly", ""), b.str("sp"), b.bool("fight") ?: false, b.bool("pm") ?: false)
    }.getOrNull()

    fun parseAck(msg: JSONObject): Ack? = runCatching {
        if (msg.optString("type") != "partyack") return null
        val op = msg.str("op") ?: return null
        Ack(op, msg.optBoolean("ok", false), msg.str("why"), msg.str("slot"), msg.int("id"))
    }.getOrNull()

    // ------------------------------------------------------------------------------------------------ units, NAV

    /** "player" 0, "party1".."party4" 1..4, else null */
    fun unitIndex(u: String?): Int? = when {
        u == "player" -> 0
        u != null && u.startsWith("party") -> u.removePrefix("party").toIntOrNull()?.takeIf { it in 1..4 }
        else -> null
    }

    enum class Dir { UP, DOWN, LEFT, RIGHT }

    /** the press's key name (PadInput / partybox `key`) -> a D-pad direction, else null */
    fun dirOf(key: String?): Dir? = when (key) {
        "PADDUP" -> Dir.UP; "PADDDOWN" -> Dir.DOWN; "PADDLEFT" -> Dir.LEFT; "PADDRIGHT" -> Dir.RIGHT; else -> null
    }

    /** the box's rows by n (index 1 = you): n1 [1]; n2 [1][2]; n3 [1][2 3]; n4 [1][2 3 4]; n5 [1][2 3][4 5] */
    fun rows(n: Int): List<List<Int>> = when (n.coerceIn(1, 5)) {
        1 -> listOf(listOf(1))
        2 -> listOf(listOf(1), listOf(2))
        3 -> listOf(listOf(1), listOf(2, 3))
        4 -> listOf(listOf(1), listOf(2, 3, 4))
        else -> listOf(listOf(1), listOf(2, 3), listOf(4, 5))
    }

    /**
     * THE BOX'S MOVES (3.4; identical in the add-on's OV.partyNav, both test the same table): rows by Up / Down (the same place in the
     * next row, clamped to its length), cells by Left / Right, no wrap. Null = no move (the strip dims that arrow). A box past n clamps.
     */
    fun nav(n: Int, from: Int, dir: Dir): Int? {
        val rs = rows(n)
        val f = from.coerceIn(1, n.coerceIn(1, 5))
        val r = rs.indexOfFirst { f in it }
        val c = rs[r].indexOf(f)
        return when (dir) {
            Dir.LEFT -> rs[r].getOrNull(c - 1)
            Dir.RIGHT -> rs[r].getOrNull(c + 1)
            Dir.UP -> rs.getOrNull(r - 1)?.let { it[c.coerceAtMost(it.size - 1)] }
            Dir.DOWN -> rs.getOrNull(r + 1)?.let { it[c.coerceAtMost(it.size - 1)] }
        }
    }

    // ------------------------------------------------------------------------------------------------ the app's live state

    /** the app refuses cell taps this long after `rs` changes (a unit may have renumbered) */
    const val RS_GUARD_MS = 500L
    /** the resend (party row col 22) at most this often */
    const val RESEND_MS = 10_000L

    /** THE app's one live state (CompanionHost feeds it; Home, the strip, the Controller tab and Settings read it) */
    val live = State()

    /**
     * Open question 4's switch (the user's default 2026-10-28: Party mode does NOT turn off when you leave Home or open a Home module
     * full screen; it is suspended by the add-on while a room holds). true = the contract's proposal: the app taps `off` first.
     */
    const val OFF_ON_LEAVE = false

    /** why the app asks for a resend (1.1) */
    enum class Ask { FIRST_DOC, HOME_SHOWN, PARTY_ON }

    /**
     * Everything the app keeps from the documents, and the rules of 1.1 / 1.4 / 1.5 / 4.2. Replayed documents (not current) may be
     * SHOWN (`party`, `partycfg`) but never cause a tap; `partybox` / `partyack` count only when current. [now] is the caller's clock.
     */
    class State {
        var party: Party? = null
            private set
        var cfg: Cfg? = null
            private set
        /** a current `party` came in this app run */
        var currentParty = false
            private set
        /** a current `partycfg` came in this game session */
        var currentCfg = false
            private set
        /** the newest current `partybox` (the box follows its n) */
        var lastBox: Box? = null
            private set
        private var rsAt = -1L
        private var askedAt = -RESEND_MS
        private var firstAskDone = false
        /** the box the app draws while Party mode is on (1-based), the optimistic one between documents */
        var box = 1
            private set
        /** the player menu was opened by the PartyPad's Y (its presses come as partybox k = "key") */
        val padMenu: Boolean get() = lastBox?.pm == true

        /** a `party` document; true when something drawn changed */
        fun onParty(p: Party, current: Boolean, now: Long): Boolean {
            val before = party
            if (before != null && before.rs != p.rs && current) rsAt = now
            party = p
            if (current) currentParty = true
            p.box?.let { box = it.coerceIn(1, p.n) } ?: run { box = box.coerceIn(1, p.n) }
            return before != p
        }

        fun onCfg(c: Cfg, current: Boolean): Boolean {
            val before = cfg
            cfg = c
            if (current) currentCfg = true
            return before != c
        }

        /** a `partybox`: only a CURRENT one with a NEW n counts; true = it was taken (the box follows it) */
        fun onBox(b: Box, current: Boolean): Boolean {
            if (!current) return false
            val last = lastBox
            // a repeat n is dropped; current documents arrive in order, so any other n is the newest (a /reload restarts n at 1)
            if (last != null && b.n == last.n) return false
            lastBox = b
            party = party?.let { p -> if (p.on != b.on || (b.on && b.box != p.box)) p.copy(on = b.on, box = if (b.on) b.box else null) else p }
            b.box?.let { bx -> box = bx.coerceIn(1, (party?.n ?: b.cnt).coerceAtLeast(1)) }
            return true
        }

        /** the game session ended (logout / world closed): the cfg must be asked again */
        fun newSession() { currentCfg = false; firstAskDone = false }

        /** the app's own pad reader moved the box (optimistic; the add-on's partybox corrects it); null = no move */
        fun move(dir: Dir): Int? {
            val p = party ?: return null
            if (!p.onLive) return null
            val to = nav(p.n, box, dir) ?: return null
            box = to
            return to
        }

        /** cell taps are refused for [RS_GUARD_MS] after the roster changed */
        fun tapsAllowed(now: Long): Boolean = rsAt < 0L || now - rsAt >= RS_GUARD_MS

        /**
         * 1.1's re-ask: true when the app should tap RESEND now (and it is counted as asked). Only while [live]; at most once per
         * [RESEND_MS]. [why]: FIRST_DOC (the first current document after the start, no current party yet), HOME_SHOWN, PARTY_ON (only
         * if partycfg is older than this game session).
         */
        fun shouldAsk(why: Ask, live: Boolean, now: Long): Boolean {
            if (!live) return false
            val wanted = when (why) {
                Ask.FIRST_DOC -> !firstAskDone && !currentParty
                Ask.HOME_SHOWN -> true
                Ask.PARTY_ON -> !currentCfg
            }
            if (!wanted || now - askedAt < RESEND_MS) return false
            askedAt = now
            if (why == Ask.FIRST_DOC) firstAskDone = true
            return true
        }
    }
}
