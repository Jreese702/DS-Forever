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
    /**
     * p37 THE CONTROLLER TAB'S PLACES (PadTabCtl's slotAt; party_design.md B): the slot on layer [set] (1 base, 2 ZL, 3 ZR, 4 both:
     * locked) at the router's position [pos] (8 A, 5 X, 6 Y; the D-pad 1-4 and B 7 are locked: p38, the user's adaptive rows
     * 2026-11-11 - all four D-pad directions move the pick). Null = a locked place.
     */
    fun slotAt(set: Int, pos: Int): String? {
        val f = when (pos) { 8 -> "a"; 5 -> "x"; 6 -> "y"; else -> return null }
        return when (set) { 1 -> if (f == "y") "yc" else f; 2 -> "zl$f"; 3 -> "zr$f"; else -> null }
    }
    /** a slot's place: (layer, position); "touch" = the Touch row (null) */
    fun placeOf(slot: String): Pair<Int, Int>? {
        if (slot == "touch") return null
        for (set in 1..3) for (pos in listOf(8, 5, 6)) if (slotAt(set, pos) == slot) return set to pos
        return null
    }
    /** p38 (the user's adaptive rows, 2026-11-11): the D-pad's four places (1 Left, 2 Up, 3 Right, 4 Down) all move the pick */
    fun movesPick(pos: Int) = pos in 1..4

    /** the Controller tab's chooser lists from the add-on (PadTabCtl p33 `padtabctl.chooser.list`): "party" = a slot's spell (row 1 =
     *  the pseudo row Empty / Inspect / Target, then partycfg.spells), "pbuff" = Smart Y's Add (sy.known minus the list, no pseudo row) */
    val CTL_LISTS = setOf("party", "pbuff")

    /**
     * A member's PLAIN facts (PadMap m129; party_design.md A.5, the user 2026-11-11: no health or mana on the bottom): who, class
     * colour, role, dead, offline, the power type, the level, and [rng] = the ONE range answer both screens dim by (null = not known).
     */
    data class Member(
        val u: String, val i: Int, val n: String, val c: String, val col: String?, val role: String,
        val dead: Boolean, val off: Boolean, val pt: String, val lv: Int?, val rng: Boolean?,
    ) {
        /** offline, or out of range by the shared answer (party_design.md A.4): drawn at [DIM] on both screens */
        val dim: Boolean get() = off || (rng == false && u != "player" && u != "pet")
        /** m131: your pet's entry (right after you while one is out) */
        val isPet: Boolean get() = u == "pet"
    }

    /** offline / out of range: the same 0.45 as the top screen (party_design.md A.4) */
    const val DIM = 0.45f

    data class Party(
        val v: String, val rs: Int, val on: Boolean, val aside: String?, val box: Int?, val fight: Boolean, val grp: Boolean,
        val sel: String, val m: List<Member>,
    ) {
        /** Party mode on and routing (not suspended by a room / window): the only state that draws a box. IN A FIGHT `on` alone
         *  (PadMap m132, the user 2026-11-11, a healer: "Party mode needs to be usable in combat for healers"): the add-on never steps
         *  aside at a fight's start while Party mode is allowed, and its `aside` is not refreshed in the lockdown, so a stale one must
         *  never hide a live Party mode */
        val onLive: Boolean get() = on && (aside == null || fight)
        val n: Int get() = m.size
        /** m131: a pet is out (its entry the second, PadMap's PM.units) */
        val pet: Boolean get() = m.any { it.isPet }
    }

    data class Spell(val id: Int, val n: String, val i: Int, val k: Boolean = true, val d: Boolean = false, val res: Boolean = false)

    data class SmartY(val on: Boolean, val list: List<Spell>, val known: List<Spell>, val pick: Map<String, Spell>)

    /** `partycfg.pad`: every column the app taps comes from here; null = that action unavailable */
    /** m129: [pick] = the plates' pick cells (pick + i: the add-on's snippet picks, touches or targets) */
    data class Pad(
        val row: String?, val pick: Int?, val on: Int?, val off: Int?, val focus: Int?, val menu: Int?,
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
        for (k in 0 until minOf(arr.length(), MAX_N)) {
            val o = arr.optJSONObject(k) ?: continue
            val u = o.str("u") ?: continue
            m.add(Member(
                u, o.int("i") ?: unitIndex(u) ?: k, o.str("n") ?: "?", o.str("c") ?: "", o.str("col"), o.str("role") ?: "NONE",
                o.bool("dead") ?: false, o.bool("off") ?: false, o.str("pt") ?: "", o.int("lv")?.takeIf { it > 0 }, o.bool("rng"),
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
        val pad = Pad(po?.str("row"), po?.int("pick"), po?.int("on"), po?.int("off"), po?.int("focus"), po?.int("menu"),
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

    /** m131: at most you, your pet and four party members */
    const val MAX_N = 6
    /** m131: the pet's unit index (its pick cell = the party row's pick + 5; PadMap PM.PET_COL) */
    const val PET_I = 5

    /** "player" 0, "party1".."party4" 1..4, "pet" 5 (m131), else null */
    fun unitIndex(u: String?): Int? = when {
        u == "player" -> 0
        u == "pet" -> PET_I
        u != null && u.startsWith("party") -> u.removePrefix("party").toIntOrNull()?.takeIf { it in 1..4 }
        else -> null
    }

    enum class Dir { UP, DOWN, LEFT, RIGHT }

    /** the press's key name (PadInput / partybox `key`) -> a D-pad direction, else null */
    fun dirOf(key: String?): Dir? = when (key) {
        "PADDUP" -> Dir.UP; "PADDDOWN" -> Dir.DOWN; "PADDLEFT" -> Dir.LEFT; "PADDRIGHT" -> Dir.RIGHT; else -> null
    }

    /**
     * m131 ADAPTIVE ROWS (party_design.md "ADAPTIVE ROWS"; the user, 2026-11-11: "when one two or three full sized, when 4, bottom 2
     * consolidate, when 5 the next 2 consolidate"): the n entries (1-based: you, your pet while out, party1..4) in min(n, 3) rows, the
     * bottom max(0, n - 3) of them shared by two. The first [fullRows] entries are full-width rows, the rest pairs (left, right).
     * n 1 [1]; n 3 [1][2][3]; n 4 [1][2][3,4]; n 5 [1][2,3][4,5]; n 6 [1,2][3,4][5,6].
     */
    fun rows(n: Int): List<List<Int>> {
        val m = n.coerceIn(1, MAX_N)
        val f = fullRows(m)
        return (1..f).map { listOf(it) } + (f + 1..m step 2).map { listOf(it, it + 1) }
    }

    /** how many of the first entries stand alone on a full-width row */
    fun fullRows(n: Int): Int { val m = n.coerceIn(1, MAX_N); return if (m <= 3) m else 6 - m }

    /**
     * THE PICK'S MOVES over [rows] (the SAME rule as the add-on's OV.partyNav and its PARTY-NAV snippet body; tools/test_party.py writes
     * the geometric table both are tested against, party-fixtures/party_nav.json): Up / Down a row (a shared row into a full one: that
     * cell; a full row into a shared one: its LEFT cell; shared to shared: the same side), Left / Right only inside a shared row; no
     * wrap. Null = no move (the strip dims that arrow). A box past n clamps first.
     */
    fun nav(n: Int, from: Int, dir: Dir): Int? {
        val m = n.coerceIn(1, MAX_N)
        val b = from.coerceIn(1, m)
        val f = fullRows(m)
        val to: Int? = if (b <= f) when (dir) {
            Dir.UP -> (b - 1).takeIf { it >= 1 }
            Dir.DOWN -> (b + 1).takeIf { it <= m }
            Dir.LEFT, Dir.RIGHT -> null
        } else {
            val right = (b - f) % 2 == 0
            when (dir) {
                Dir.LEFT -> if (right) b - 1 else null
                Dir.RIGHT -> if (!right) b + 1 else null
                Dir.UP -> if (b - 2 > f) b - 2 else f.takeIf { it >= 1 }
                Dir.DOWN -> (b + 2).takeIf { it <= m }
            }
        }
        return to
    }

    // ------------------------------------------------------------------------------------------------ the app's live state

    /** the app refuses cell taps this long after `rs` changes (a unit may have renumbered) */
    const val RS_GUARD_MS = 500L
    /** the resend (party row col 22) at most this often */
    const val RESEND_MS = 10_000L
    /** a taker's off tap again after this while Party mode still says on */
    const val TAKER_RETRY_MS = 2_000L

    /** THE app's one live state (CompanionHost feeds it; Home, the strip, the Controller tab and Settings read it) */
    val live = State()

    /**
     * Open question 4's switch. THE USER'S RULINGS (2026-10-28, applied at integration): Party mode TURNS OFF when you leave Home (a
     * room opens, or a Home module goes full screen), and ANYTHING that takes the controller ends it (the pause wheel, the bar cursor,
     * a game window: [State.takerSeen] on the interact document's holder): the app taps party `off` (col 11, a secure click: in fights
     * too), so the router releases the pad and Home is found with Party mode off. The add-on's PM.OFF_WHEN_ASIDE (true) does the same
     * itself out of combat, and at a fight's end when something took the controller during it. false = suspended only.
     */
    const val OFF_ON_LEAVE = true
    /** m133: capture holders that are a prompt (only suspend Party mode, never end it): PadMap PM.PROMPT_HOLDERS */
    val PROMPT_HOLDERS = setOf("dialogs")

    /** leaving Home's rest page: tap `off` when Party mode is ON at all (on, or on but suspended by an `aside`: off is absolute).
     *  m132: never IN A FIGHT ([fight]): there it is only suspended while something else holds (the user: usable in combat) */
    /** m133 (review): what a tap on the bottom tab sends (true = `on`): the opposite of what the tab SHOWS (`onLive`, PartyLayout's look),
     *  so a Party mode only suspended by a prompt out of combat (drawn off) gets a harmless `on`, never an `off` the player did not see */
    fun tabWants(p: Party?): Boolean = p?.onLive != true

    fun offOnLeave(p: Party?, fight: Boolean = false): Boolean = OFF_ON_LEAVE && !fight && p?.fight != true && p?.on == true

    /** Home's screen went from its rest page (no module full screen; a fight's takeover counts as rest) to a module full screen */
    fun leftRest(wasFull: Boolean, nowFull: Boolean): Boolean = nowFull && !wasFull

    /**
     * A `partyack` the SETTINGS pages answer to (the Controller tab, Settings' Smart Y): `slot` / `yc` / `pb`, and `touch` when it
     * names a slot (a Touch setting). Not `resend`, `cell` (a spare column) or a stale TSP tap (`touch` why `target`, no slot): those
     * are logged only.
     */
    fun forSettings(a: Ack): Boolean = a.op == "slot" || a.op == "yc" || a.op == "pb" || (a.op == "touch" && a.slot != null)

    /**
     * The PartyPad's menu sub-state is on (`pm`) but the app has no player menu the pad drives (a lost `partybox k=menu`, an app
     * restart, Home left): every press is swallowed as `key` until MENU DONE, so the app taps it (once per press counter).
     */
    fun strandedMenu(b: Box, padMenuOpen: Boolean): Boolean = b.pm && !padMenuOpen && (b.k == "key" || b.k == "state")

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

        private var takerTapped: String? = null
        private var takerAt = 0L

        /**
         * THE USER'S RULING (anything that takes the controller ends Party mode): a CURRENT interact document names the capture's
         * [holder] (a room, a Home module full screen, the pause wheel, the bar cursor, an NPC / vendor / loot window in a fight).
         * True = tap party `off` now: Party mode is on and someone holds; once per holder, again after [TAKER_RETRY_MS] while it still
         * says on (a lost tap). m132 (the user, 2026-11-11, a healer: "Party mode needs to be usable in combat for healers. Locking it
         * is retarded"): NEVER IN A FIGHT ([fight], or the party document's own `fight`): a holder there only suspends Party mode (the
         * router's room mode comes first) and it is live again when the holder lets go. Out of combat the add-on ends it itself.
         */
        fun takerSeen(holder: String?, now: Long, fight: Boolean = false): Boolean {
            if (fight || party?.fight == true) return false
            // m133 (review): a prompt's capture (Interact's "dialogs": a static popup's mirror) only suspends, as PadMap's PM.PROMPT_HOLDERS
            if (holder != null && holder in PROMPT_HOLDERS) return false
            if (!OFF_ON_LEAVE || holder.isNullOrEmpty() || party?.on != true) { if (holder.isNullOrEmpty()) takerTapped = null; return false }
            if (holder == takerTapped && now - takerAt < TAKER_RETRY_MS) return false
            takerTapped = holder; takerAt = now
            return true
        }

        /**
         * m129: a plate's tap with Party mode on and live (the add-on's pick snippet decides; this only draws the move at once): member
         * [i] (unit index 0..4) becomes the pick unless it already is. -> the new box (1-based), or null (nothing moves: off, not
         * there, or already picked - that tap is the Touch action).
         */
        fun choose(i: Int): Int? {
            val p = party ?: return null
            if (!p.onLive) return null
            val k = p.m.indexOfFirst { it.i == i }
            if (k < 0 || k + 1 == box) return null
            box = k + 1
            return box
        }

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
                // 2026-11-04 (the cursor: every ask is a click on the top screen): Home's return asks only while no CURRENT party
                // document came this session (the add-on sends every change itself, and its heartbeat re-sends)
                Ask.HOME_SHOWN -> !currentParty
                Ask.PARTY_ON -> !currentCfg
            }
            if (!wanted || now - askedAt < RESEND_MS) return false
            askedAt = now
            if (why == Ask.FIRST_DOC) firstAskDone = true
            return true
        }
    }
}
