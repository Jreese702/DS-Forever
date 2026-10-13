package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * The TALENTS room's data (spells session), from the addon's `talents` t1 feed (WowCompanionSpells/Talents.lua).
 *
 * USER RULINGS (2026-09-30, relayed as the user's answers): "A, one school at a time" (UI Art's sheet), the first build
 * "See and learn" (Add Point / Remove, staged, then Apply with a confirmation naming the changes), Legacy "Its own room,
 * later" (nothing of it here). Nothing is drawn from memory: every talent, name and text is what the game sends.
 *
 * Three documents: `talents` (the tree: schools, the pool, every visible talent with its school, column and row),
 * `talentsel` (the selected talent: this rank's text and the next one's, what it still needs), `talentsack` (the answer
 * to an action: add / remove / undo / apply, then "applied" when the game has taken the changes or refused them).
 */
object TalentsModel {

    // THE PAD ROWS (the pad owner's YES, AGENT_LOG 9f7202e): fixed columns. Row `talents`: the room's actions; row `talentn`:
    // a no-action pick of the talent at cell (column + 4 x row) of the school on screen.
    const val ROW_ACTIONS = "talents"
    const val ROW_CELLS = "talentn"
    const val PAD_OPEN = 0
    const val PAD_CLOSE = 1
    const val PAD_ADD = 2
    const val PAD_REMOVE = 3
    const val PAD_UNDO = 4
    const val PAD_APPLY = 5          // only after the room's own confirmation, which names the changes
    const val PAD_SCHOOL_1 = 6       // 6, 7, 8 = school 1, 2, 3
    const val PAD_RESET = 9          // staged, like the game's own Reset "All"
    const val PAD_CLOSE_BOX = 10     // a touch on Cancel in a box the CONTROLLER opened: closes it (no game action)
    const val CELL_COLS = 4
    const val CELLS = 36

    /**
     * The school pictures `talentbg_<class>_<school>` (UI Art's SPEC 3), the school found by its PLACE left to right. Camelot's own
     * window draws one picture per class; these are the picked mock-up's, and they are in this client.
     */
    val SCHOOL_PICTURES: Map<String, List<String>> = mapOf(
        "WARRIOR" to listOf("arms", "fury", "protection"),
        "PALADIN" to listOf("holy", "protection", "retribution"),
        "HUNTER" to listOf("beastmastery", "marksmanship", "survival"),
        "ROGUE" to listOf("assassination", "outlaw", "subtlety"),
        "PRIEST" to listOf("discipline", "holy", "shadow"),
        "SHAMAN" to listOf("elemental", "enhancement", "restoration"),
        "MAGE" to listOf("arcane", "fire", "frost"),
        "WARLOCK" to listOf("affliction", "demonology", "destruction"),
        "DRUID" to listOf("balance", "feral", "restoration"),
    )

    /** the picture key of a school (1-based place) for a class token; null when the class or place is not known */
    fun schoolPicture(cls: String?, index: Int): String? = SCHOOL_PICTURES[cls?.uppercase()]?.getOrNull(index - 1)

    /** Where the controller is in the room (TalentsCtl.lua c1). `focus`: "grid", "schools", "specs", "foot". */
    data class Ctl(
        val focus: String,
        val tab: Int,               // the school tab lit while on the tabs (1-based)
        val spec: Int,              // the spec tab lit while on the specs
        val foot: Int,              // 1 Undo, 2 Reset, 3 Apply
        val box: String?,           // "apply" or "undo" while its box is open
        val busy: Boolean,          // an Apply is on its way: every action grey
        val note: String?,
    )

    fun parseCtl(msg: JSONObject): Ctl? {
        if (msg.optString("type") != "talentsctl") return null
        val c = msg.optJSONObject("c") ?: return null
        return Ctl(
            focus = c.optString("focus", "grid").ifEmpty { "grid" },
            tab = c.optInt("tab", 1).coerceIn(1, 3),
            spec = c.optInt("spec", 1).coerceIn(1, 2),
            foot = c.optInt("foot", 1).coerceIn(1, 3),
            box = c.optString("box", "").takeIf { it == "apply" || it == "undo" },
            busy = c.optBoolean("busy", false),
            note = textOrNull(c, "note"),
        )
    }

    data class Pool(val have: Int, val max: Int, val spent: Int)
    data class School(val id: Int, val name: String, val icon: Int, val spent: Int)

    /** A talent's look in the game's terms: full, partly learnt, can be learnt, locked. */
    /** GREY (Talents t7, the kit's Talent.GREY): the game's "Disabled", reachable but nothing to spend and nothing learnt; LOCKED = its row or arrow not met */
    enum class State { FULL, PART, OPEN, LOCKED, GREY }

    data class Node(
        val id: Int,
        val school: Int,            // 1-based, the game's order; 0 = not in a school
        val col: Int?,              // column inside its school, 0..3; null = the addon could not place it
        val row: Int?,              // row from the top of the tree
        val rank: Int,              // what the window shows, staged changes included
        val active: Int?,           // the rank in force (its meaning is to be confirmed on the device)
        val bought: Int?,
        val max: Int,
        val state: State,
        val canAdd: Boolean,
        val canRemove: Boolean,
        val name: String?,
        val icon: Int,
        val to: List<Int>,          // the talents its arrows lead to
        val lit: List<Boolean>,     // each arrow lit (met), same order as [to]
        val pending: Char?,         // '+' a point added, '-' a point taken back, not applied yet
    )

    data class Tree(
        val version: String,
        val config: Int?,
        val tree: Int?,
        val specGroup: Int?,
        val specGroups: Int,
        val level: Int,
        val combat: Boolean,
        val editable: Boolean?,
        val whyNot: String?,        // the game's own words when the tree cannot be changed
        val staged: Boolean,
        val pool: Pool?,
        val schools: List<School>,
        val nodes: List<Node>,
        val odd: List<Int>,         // talents the addon could not place on the grid (their raw positions travel)
        val petTree: String?,       // the hunter pet's tree name, when the game gives one
        val touchRows: Boolean,     // both pad rows registered (false = the pad had no room: touch cannot act, the controller can)
        val cls: String?,           // the class token ("HUNTER"): the school pictures
        val secret: Int,
        val threw: Int,
        val pad: JSONObject?,
        /** the game's talent button is enabled (a talent point earned, t2): false = the room greys its page (the user 2026-09-30) */
        val usable: Boolean = true,
        /** the level the game opens talents at (10 less the Legacy Adventure ranks) */
        val unlockLevel: Int? = null,
        /** the game's own line while they are not open ("This feature becomes available at level 10.") */
        val lockText: String? = null,
    )

    data class Need(val text: String, val met: Boolean)
    data class From(val id: Int, val name: String?, val met: Boolean)

    data class Detail(
        val school: Int?,           // the school the addon counts touch picks in (its echo, `sch`)
        val id: Int?,               // null = nothing selected
        val name: String?,
        val icon: Int,
        val rank: Int,
        val max: Int,
        val canAdd: Boolean,
        val canRemove: Boolean,
        val pending: Char?,
        val state: State,
        val text: String?,          // this rank's description (rank 1's before any point)
        val next: String?,          // the next rank's, while there is one
        val needs: List<Need>,      // in the game's words, e.g. "Requires 5 points in Beast Mastery"
        val from: List<From>,       // the talents whose arrows lead here
    )

    data class Ack(val op: String, val ok: Boolean, val why: String?, val id: Int?)

    /** One line of the Apply confirmation: which talent, from which rank to which. */
    data class Change(val id: Int, val name: String, val from: Int, val to: Int)

    // ------------------------------------------------------------------------------------------ parsing

    private fun objects(a: JSONArray?): List<JSONObject> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun ints(a: JSONArray?): List<Int> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optInt(it) }

    // the test double of org.json has no optBoolean(int); a boolean's text is "true" on both
    private fun bools(a: JSONArray?): List<Boolean> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) == "true" }

    private fun intOrNull(o: JSONObject, k: String): Int? = if (o.has(k)) o.optInt(k, 0) else null
    private fun textOrNull(o: JSONObject, k: String): String? = o.optString(k, "").takeIf { it.isNotEmpty() }

    private fun state(s: String): State = when (s) {
        "full" -> State.FULL
        "part" -> State.PART
        "open" -> State.OPEN
        "grey" -> State.GREY
        else -> State.LOCKED
    }

    private fun pend(s: String): Char? = when (s) { "+" -> '+'; "-" -> '-'; else -> null }

    /** The `talents` message; null when it is not one. */
    fun parse(msg: JSONObject): Tree? {
        if (msg.optString("type") != "talents") return null
        val t = msg.optJSONObject("t") ?: return null
        val pool = t.optJSONObject("pool")
        val sec = t.optJSONObject("sec")
        return Tree(
            version = msg.optString("v", "?"),
            config = intOrNull(t, "cfg"),
            tree = intOrNull(t, "tree"),
            specGroup = intOrNull(t, "g"),
            specGroups = t.optInt("ng", 1),
            level = t.optInt("lvl", 0),
            // an addon older than t2 says nothing: the old rule, below level 10
            usable = if (t.has("use")) t.optBoolean("use", true) else t.optInt("lvl", 0) !in 1..9,
            unlockLevel = intOrNull(t, "unlock"),
            lockText = textOrNull(t, "lockw"),
            combat = t.optBoolean("combat", false),
            editable = if (t.has("edit")) t.optBoolean("edit", true) else null,
            whyNot = textOrNull(t, "why"),
            staged = t.optBoolean("staged", false),
            pool = pool?.let { Pool(it.optInt("have", 0), it.optInt("max", 0), it.optInt("spent", 0)) },
            schools = objects(t.optJSONArray("groups")).map {
                School(it.optInt("id", 0), it.optString("n", ""), it.optInt("i", 0), it.optInt("spent", 0))
            },
            nodes = objects(t.optJSONArray("nodes")).mapNotNull { o ->
                val id = o.optInt("id", 0)
                if (id <= 0) null else Node(
                    id = id,
                    school = o.optInt("g", 0),
                    col = intOrNull(o, "c"),
                    row = intOrNull(o, "r"),
                    rank = o.optInt("rk", 0),
                    active = intOrNull(o, "a"),
                    bought = intOrNull(o, "p"),
                    max = o.optInt("max", 1).coerceAtLeast(1),
                    state = state(o.optString("st", "")),
                    canAdd = o.optBoolean("cp", false),
                    canRemove = o.optBoolean("cr", false),
                    name = textOrNull(o, "n"),
                    icon = o.optInt("i", 0),
                    to = ints(o.optJSONArray("to")),
                    lit = bools(o.optJSONArray("lit")),
                    pending = pend(o.optString("pend", "")),
                )
            },
            odd = ints(t.optJSONArray("odd")),
            petTree = t.optJSONObject("pet")?.let { textOrNull(it, "tree") },
            touchRows = t.optJSONObject("rows")?.let { it.optBoolean(ROW_ACTIONS, false) && it.optBoolean(ROW_CELLS, false) } ?: false,
            cls = textOrNull(t, "cls"),
            secret = sec?.optInt("s", 0) ?: 0,
            threw = sec?.optInt("t", 0) ?: 0,
            pad = t.optJSONObject("pad"),
        )
    }

    /** The `talentsel` message; null when it is not one. A Detail with id null = nothing selected. */
    fun parseSel(msg: JSONObject): Detail? {
        if (msg.optString("type") != "talentsel") return null
        val s = msg.optJSONObject("sel") ?: return null
        return Detail(
            school = intOrNull(msg, "sch")?.takeIf { it in 1..3 },
            id = intOrNull(s, "id")?.takeIf { it > 0 },
            name = textOrNull(s, "n"),
            icon = s.optInt("i", 0),
            rank = s.optInt("rk", 0),
            max = s.optInt("max", 1).coerceAtLeast(1),
            canAdd = s.optBoolean("cp", false),
            canRemove = s.optBoolean("cr", false),
            pending = pend(s.optString("pend", "")),
            state = state(s.optString("st", "")),
            text = textOrNull(s, "d"),
            next = textOrNull(s, "dn"),
            needs = objects(s.optJSONArray("req")).mapNotNull { o ->
                textOrNull(o, "t")?.let { Need(it, o.optBoolean("met", false)) }
            },
            from = objects(s.optJSONArray("from")).mapNotNull { o ->
                val id = o.optInt("id", 0)
                if (id <= 0) null else From(id, textOrNull(o, "n"), o.optBoolean("met", false))
            },
        )
    }

    /** The `talentsack` message; null when it is not one. */
    fun parseAck(msg: JSONObject): Ack? {
        if (msg.optString("type") != "talentsack") return null
        val op = msg.optString("op", "")
        if (op.isEmpty()) return null
        return Ack(op, msg.optBoolean("ok", false), textOrNull(msg, "why"), intOrNull(msg, "id"))
    }

    // ------------------------------------------------------------------------------------------ the room's questions

    /** One school's placed talents, top row first, then left to right. */
    fun school(tree: Tree, index: Int): List<Node> =
        tree.nodes.filter { it.school == index && it.col != null && it.row != null }
            .sortedWith(compareBy({ it.row }, { it.col }))

    /** How many rows one school needs (its lowest talent's row + 1); 0 when it has none. */
    fun rows(tree: Tree, index: Int): Int = (school(tree, index).maxOfOrNull { it.row!! } ?: -1) + 1

    /** The talent at a grid place of one school, if any. */
    fun at(tree: Tree, index: Int, col: Int, row: Int): Node? =
        tree.nodes.firstOrNull { it.school == index && it.col == col && it.row == row }

    fun find(tree: Tree, id: Int): Node? = tree.nodes.firstOrNull { it.id == id }

    /** The school a talent is in (1-based); null when it is in none. */
    fun schoolOf(tree: Tree, id: Int): Int? = find(tree, id)?.school?.takeIf { it > 0 }

    /** The arrows drawn inside one school: from a talent to one below it, lit or not. */
    fun arrows(tree: Tree, index: Int): List<Triple<Node, Node, Boolean>> {
        val placed = school(tree, index)
        return placed.flatMap { from ->
            from.to.mapIndexedNotNull { k, toId ->
                val to = placed.firstOrNull { it.id == toId } ?: return@mapIndexedNotNull null
                Triple(from, to, from.lit.getOrElse(k) { false })
            }
        }
    }

    /**
     * What Apply would make the character's: every talent with a change pending, from the rank in force to the one
     * shown. The confirmation names these (the rule: nothing permanent on one press without a question that names it).
     */
    fun changes(tree: Tree): List<Change> = tree.nodes.mapNotNull { n ->
        val from = n.active ?: return@mapNotNull null
        if (n.pending == null && from == n.rank) null
        else Change(n.id, n.name ?: "?", from, n.rank)
    }

    /** The pad column that picks a grid place of the school on screen; null off the grid. */
    fun cell(col: Int, row: Int): Int? =
        if (col in 0 until CELL_COLS && row >= 0 && col + CELL_COLS * row < CELLS) col + CELL_COLS * row else null

    /**
     * Add / Remove may be sent only once the addon has ECHOED the talent the player touched (the pad owner's guard): a pick is
     * read against the school the addon last set, so a school tap and a pick that ever disagree show only in the echo.
     */
    fun echoed(detail: Detail?, touchedId: Int, school: Int): Boolean =
        detail != null && detail.id == touchedId && detail.school == school

    /** "Rank 1/5": the rank and its maximum, as the window shows them. */
    fun rankLine(n: Node): String = "${n.rank}/${n.max}"

    /** The Apply button is live: something is staged, the tree can be changed, and no fight is on. */
    fun canApply(tree: Tree): Boolean = tree.staged && tree.editable != false && !tree.combat
}
