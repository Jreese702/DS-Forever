package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE MACROS ROOM'S DATA (Spells s10's `macros` document; the user, 2026-11-13: "Lets scrap the smart ranged attack and start building
 * a macro room. The calender can be moved up to a button on the right side of the map name make it small. Use the room slot for
 * macros."). Pure: no Android types, so the off-device tests read it.
 *
 * THE STATE IS THE ADD-ON'S (as the Spellbook's and Bags' are): the controller's presses go to the add-on, which moves the glow and sends
 * the document again; the app draws it. A finger taps the k-th thing on show (`s<k>`: a grid cell, an editor row, a pick row, a
 * recipe tile), A / X / B (`a` / `x` / `b`), the tabs (`tg` / `tc`) or the page arrows (`pp` / `pn`) on the room's own pad row "mac",
 * whose columns the document names ([Keys]). The row is LAZY (the pad is full at its fullest layout): before it exists the document has
 * no keys and the app asks for it with Book's padtab pick (39 then [ROW_CODE]).
 *
 * The macros' names and lines are the PLAYER'S TEXT: never through Tr.t (the menus-only rule); the room's own labels are.
 */
object MacrosModel {
    /** Book b63: the padtab one-shot pick's code that makes the Macros room's row (39 then this) */
    const val ROW_CODE = 34
    const val PICK = 39
    const val PER_PAGE = 24
    const val LINE_ROWS = 19
    const val BODY_MAX = 255
    /** the editor's four buttons' s-cells (Spells s10 MR.editCell: s0 the name, s1..s19 the lines, then these) */
    val BUTTONS = listOf("add", "pick", "rec", "del")
    /** the recipes, in the add-on's order (MR.RECIPES): their labels are the app's menu words */
    val RECIPES = listOf("mo", "fo", "me", "sh", "at", "tr")
    val RECIPE_WORDS = mapOf("mo" to "Mouseover heal", "fo" to "Focus", "me" to "Self cast", "sh" to "Shift pair",
        "at" to "Start attack", "tr" to "Trinket")
    /** the line each recipe adds, with "Spell" for the pick (the tile's small print) */
    val RECIPE_LINES = mapOf("mo" to "/cast [@mouseover,help,nodead][] Spell", "fo" to "/cast [@focus,exists,nodead][] Spell",
        "me" to "/cast [@player] Spell", "sh" to "/cast [mod:shift] Spell 2; Spell 1", "at" to "/startattack  /cast Spell",
        "tr" to "/use 13  /use 14  /cast Spell")

    data class Count(val n: Int, val max: Int)
    data class Cell(val name: String, val icon: Int, val plus: Boolean = false)
    data class Mac(val name: String, val icon: Int, val lines: List<String>, val len: Int, val lock: Set<Int>, val more: Boolean)
    data class Keys(val col: Map<String, Int>, val s0: Int, val n: Int) {
        fun of(k: String): Int? = col[k]
        fun s(k: Int): Int? = if (k in 0 until n) s0 + k else null
    }
    data class PickRow(val kind: String, val name: String, val icon: Int)
    data class Pick(val rows: List<PickRow>, val page: Int, val pages: Int, val cur: Int, val rec: String?, val step: Int, val of: Int)
    data class Kbd(val name: String, val text: String, val max: Int, val line: Int?)

    data class State(
        val ready: Boolean, val off: Boolean, val win: Boolean, val combat: Boolean, val noRow: Boolean,
        val tab: String, val general: Count, val char: Count, val me: String,
        val mode: String, val view: String, val note: String?, val n: Int,
        val held: Boolean, val want: Boolean, val why: String?,
        val keys: Keys?, val pad: JSONObject?,
        val page: Int, val pages: Int, val cur: Int, val cells: List<Cell>,
        val mac: Mac?, val ef: String?, val efc: Int?, val del: Boolean,
        val pick: Pick?, val recCur: Int?, val kbd: Kbd?,
    ) {
        val count: Count get() = if (tab == "c") char else general
    }

    private fun cnt(o: JSONObject?): Count = Count(o?.optInt("n", 0) ?: 0, o?.optInt("max", 0) ?: 0)
    private fun str(o: JSONObject, k: String): String? = if (o.has(k) && !o.isNull(k)) o.optString(k).takeIf { it.isNotEmpty() } else null

    fun parse(msg: JSONObject): State? {
        if (msg.optString("type") != "macros") return null
        val m = msg.optJSONObject("m") ?: return null
        val keys = m.optJSONObject("keys")?.let { k ->
            val col = HashMap<String, Int>()
            for (name in k.keys()) if (name != "s0" && name != "n") col[name] = k.optInt(name, -1)
            Keys(col, k.optInt("s0", -1), k.optInt("n", 0))
        }?.takeIf { it.s0 >= 0 }
        val cells = ArrayList<Cell>()
        m.optJSONArray("cells")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                cells += if (o.optBoolean("plus", false)) Cell("", 0, plus = true) else Cell(o.optString("n", ""), o.optInt("i", 0))
            }
        }
        val mac = m.optJSONObject("mac")?.let { o ->
            val lines = ArrayList<String>()
            o.optJSONArray("lines")?.let { a -> for (i in 0 until a.length()) lines += a.optString(i, "") }
            val lock = HashSet<Int>()
            o.optJSONArray("lock")?.let { a -> for (i in 0 until a.length()) lock += a.optInt(i) }
            Mac(o.optString("n", ""), o.optInt("i", 0), lines, o.optInt("len", lines.joinToString("\n").length), lock, o.optBoolean("more", false))
        }
        val pick = m.optJSONObject("pick")?.let { o ->
            val rows = ArrayList<PickRow>()
            o.optJSONArray("rows")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { r -> rows += PickRow(r.optString("t", "s"), r.optString("n", ""), r.optInt("i", 0)) }
            }
            Pick(rows, o.optInt("page", 1), o.optInt("pages", 1), o.optInt("cur", 0), str(o, "rec"), o.optInt("step", 0), o.optInt("of", 0))
        }
        val kbd = m.optJSONObject("kbd")?.let { o ->
            Kbd(o.optString("name", ""), o.optString("text", ""), o.optInt("max", BODY_MAX).coerceIn(1, BODY_MAX),
                if (o.has("line")) o.optInt("line") else null)
        }?.takeIf { it.name.isNotEmpty() }
        val ctl = m.optJSONObject("ctl")
        val cn = m.optJSONObject("cnt")
        val mode = m.optString("mode", "grid").ifEmpty { "grid" }
        return State(
            ready = m.optBoolean("ready", false), off = m.optBoolean("off", false), win = m.optBoolean("win", false),
            combat = m.optBoolean("cb", false), noRow = m.optBoolean("norow", false),
            tab = if (m.optString("tab") == "c") "c" else "g", general = cnt(cn?.optJSONObject("g")), char = cnt(cn?.optJSONObject("c")),
            me = m.optString("me", ""), mode = mode, view = m.optString("view", mode).ifEmpty { mode }, note = str(m, "note"),
            n = m.optInt("n", 0),
            held = ctl?.optBoolean("held", false) ?: false, want = ctl?.optBoolean("want", false) ?: false, why = ctl?.let { str(it, "why") },
            keys = keys, pad = m.optJSONObject("pad"),
            page = m.optInt("page", 1).coerceAtLeast(1), pages = m.optInt("pages", 1).coerceAtLeast(1), cur = m.optInt("cur", 0),
            cells = cells, mac = mac, ef = str(m, "ef"), efc = if (m.has("efc")) m.optInt("efc") else null, del = m.optBoolean("del", false),
            pick = pick, recCur = m.optJSONObject("rec")?.optInt("cur", 0), kbd = kbd,
        )
    }

    /** B leads Home (the Home button shows B): at the grid with no keyboard and no Bind box up */
    fun bLeadsHome(s: State?): Boolean = s == null || (s.mode == "grid" && s.kbd == null)

    /** the add-on's word against the screen (Calendar's c10 rule): "h" = the room shows and is not wanted; "f" = hidden, still held */
    fun ctlFix(s: State?, shown: Boolean): String? = when {
        s == null || s.keys == null -> null
        shown && !s.want -> "h"
        !shown && (s.want || s.held) -> "f"
        else -> null
    }

    /** the taps that make the room's lazy row (Book b63: the padtab one-shot pick, then its code), on the padtab row */
    fun rowCells(): List<Pair<String, Int>> = listOf("padtab" to PICK, "padtab" to ROW_CODE)

    /** one tap on the room's row for key [k] ("h", "a", ...); null = no row yet */
    fun cell(s: State?, k: String): List<Pair<String, Int>>? = s?.keys?.of(k)?.takeIf { it >= 0 }?.let { listOf("mac" to it) }

    /** a finger on the k-th thing on show: select it (s<k>) and, where [act], press A after it in the same batch */
    fun pickCells(s: State?, k: Int, act: Boolean): List<Pair<String, Int>>? {
        val keys = s?.keys ?: return null
        val sc = keys.s(k) ?: return null
        val a = keys.of("a")
        return if (act && a != null && a >= 0) listOf("mac" to sc, "mac" to a) else listOf("mac" to sc)
    }

    /** the editor's rows as the add-on numbers them: "name", "l1".."lL" (at most [LINE_ROWS]), then the four buttons */
    fun editRows(mac: Mac?): List<String> {
        val out = arrayListOf("name")
        val n = minOf(mac?.lines?.size ?: 0, LINE_ROWS)
        for (i in 1..n) out += "l$i"
        out += BUTTONS
        return out
    }

    /** a row's s-cell (MR.editCell): the name 0, a line its number, the buttons 20..23 */
    fun editCell(row: String): Int = when {
        row == "name" -> 0
        row.startsWith("l") -> row.drop(1).toIntOrNull() ?: 0
        else -> LINE_ROWS + 1 + BUTTONS.indexOf(row).coerceAtLeast(0)
    }

    /** the macro body's length counter as the game's window writes it ("n/255") */
    fun counter(mac: Mac?): String = "${mac?.len ?: 0}/$BODY_MAX"

    /** a pick row's line, as the add-on writes it (MR.lineFor) */
    fun lineOf(r: PickRow): String = when (r.kind) { "s" -> "/cast ${r.name}"; "u" -> "/use ${r.name}"; else -> "/equip ${r.name}" }

    /** the short word for why a write or a pick was refused (the add-on's `note`); null = say nothing */
    fun noteWord(note: String?): String? = when (note) {
        null, "" -> null
        "combat" -> "In combat"
        "window" -> "Game window open"
        "loading" -> "Loading"
        "off" -> "Macros are off"
        "taken" -> "Name taken"
        "long" -> "Too long"
        "empty" -> "No name"
        "bad" -> "Not allowed"
        "locked" -> "Locked"
        "missing" -> "Missing"
        "full" -> "Full"
        "busy" -> "Busy"
        else -> "Not done"
    }

    /** the keyboard's line label */
    fun kbdWord(k: Kbd?): String = when (k?.name) { "macnew" -> "New"; "macname" -> "Name"; else -> "Line" }
}
