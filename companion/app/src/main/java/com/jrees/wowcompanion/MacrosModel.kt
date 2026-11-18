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
 *
 * Spells s14 THE MACRO BUILDER v2 (the user, 2026-11-16: the recipes "make no sense" and only add lines; every RECOMMENDED pick of
 * design_macrobuilder): the editor's lines arrive as STRIPS ([Strip]: the add-on's own chips - the app has NO grammar), the builder as
 * [Builder] (its text, its chips as rows with their s-cells, its checks, the open pop-out [Pop]), the presets as [Tile] / [Preset]. The
 * words for the chips are the room's menu words ([cmdWord], [unitWord], [condWord] ...); spell, item, unit and pet-order NAMES are the
 * game's or the player's text, never Tr.t.
 *
 * Spells s15 THE LOOK PASS (the user, 2026-11-16, the pick "Full match": "one A button plus Y More like Quests", Cancel / Confirm for
 * delete / replace): the editor's buttons are + Line and More ([BUTTONS]: s21, s22), the builder's Done and More; More is the add-on's
 * one-column pop-out (kind "more": [moreWord]); [State.ym] / [Builder.ym] = Y opens More now (the More button carries the Y glyph only
 * then); [State.del], [Builder.remArmed], [Preset.armed] = that ask is up (the app draws Cancel [B] / Confirm [A]).
 *
 * Spells s16 X = REMOVE (the user, 2026-11-18: "in the macro menu I need a button to delete a line, maybe just on the X glyph"; the pick
 * "X = Remove"): in the editor's line list X on the focused line asks to remove it ([State.remLine] = the line asking: Cancel [B] /
 * Remove [A]); [State.xRemove] = the focused line shows the X Remove hint (never in a fight, never on a "|" line; a tap on it is the
 * row's `x`); the old hidden X "a line below" is More's first row "Insert below" (`o` "ins", `l` = the line it goes under).
 */
object MacrosModel {
    /** Book b63: the padtab one-shot pick's code that makes the Macros room's row (39 then this) */
    const val ROW_CODE = 34
    const val PICK = 39
    const val PER_PAGE = 24
    const val LINE_ROWS = 19
    const val BODY_MAX = 255
    /** Spells s14: the editor's buttons' s-cells (MR.editCell: s0 the name, s1 the icon, s2..s20 the lines or tiles, then these);
     *  s15: + Line and More (Presets and Delete are More's rows) */
    val BUTTONS = listOf("add", "more")
    const val CASES = 4

    data class Count(val n: Int, val max: Int)
    /** a grid cell; s14 [locked] = the companion's own macro ("DS Combat" / "DS Attack", D6): view only */
    data class Cell(val name: String, val icon: Int, val plus: Boolean = false, val locked: Boolean = false)
    data class Mac(val name: String, val icon: Int, val lines: List<String>, val len: Int, val lock: Set<Int>, val more: Boolean,
                   val locked: Boolean = false)
    data class Keys(val col: Map<String, Int>, val s0: Int, val n: Int) {
        fun of(k: String): Int? = col[k]
        fun s(k: Int): Int? = if (k in 0 until n) s0 + k else null
    }
    /** (s13, an older add-on's + Spell/Item list) a pick row: [kind] "s" a spell, "u" an item to use, "e" an item to equip, "p" a PET
     *  COMMAND whose [line] is the game's own slash command ("/petattack" ...) */
    data class PickRow(val kind: String, val name: String, val icon: Int, val line: String? = null)
    data class Pick(val rows: List<PickRow>, val page: Int, val pages: Int, val cur: Int, val rec: String?, val step: Int, val of: Int)
    data class Kbd(val name: String, val text: String, val max: Int, val line: Int?)

    // ---------------------------------------------------------------- s14: the builder's pieces, as the add-on's chips
    /** a picture and a name (a spell's, an item's, a worn slot's item's: the GAME'S text) */
    data class Look(val icon: Int, val name: String)
    /** one case of a line: [unit] the unit token ("focus", "softenemy" ... ; null = the command's own target; anything else = a typed
     *  name), [conds] the conditions ("harm", "!dead" = not, "mod:shift", "~spec:2" = kept as written), [arg] its own spell (else) */
    data class Case(val unit: String?, val conds: List<String>, val arg: Look?)
    /** one line as the editor's strip shows it: [kind] "cmd" / "tip" (a line the builder takes), "text" (kept as written), "lock" (a "|"),
     *  "empty"; [cmd] the command (MR.CMDS' k), [cmdName] the game's own word for a pet order; [look] the line's spell / item;
     *  [bang] keep-on ("!"); [sub] the rank's subtext as written; [seq] a sequence's spells, [reset] its reset; [slot] equipslot's slot;
     *  [warn] the first soft warning ("book" / "bags" / "else") */
    data class Strip(val kind: String, val cmd: String?, val cmdName: String?, val look: Look?, val bang: Boolean, val sub: String?,
                     val seq: List<Look>, val reset: String?, val slot: Int?, val cases: List<Case>, val warn: String?)
    /** a preset tile: [key] (MR.PRESETS' k), the pictures of its picks, its text (two to three lines: the tile's small print) */
    data class Tile(val key: String, val icons: List<Int>, val text: String)
    /** an open pop-out: [kind] cmd / arg / on / if / rank / case / reset / seq / icon; [cur] its cell ("r3", a tab "t2", the search
     *  "q"); [base] the s-cell of r1 (the Spell / Item grid sits after its five tabs and the search: 6); [rows] its cells as sent */
    data class Pop(val kind: String, val cur: String?, val page: Int, val pages: Int, val cols: Int, val base: Int, val case: Int?,
                   val query: String?, val ranks: Int, val rows: List<JSONObject>, val tabs: List<String>, val tab: String?,
                   val forCmd: String?, val forSeq: Boolean, val forPreset: Boolean) {
        /** the s-cell of cell key [k] (MR.popCell) */
        fun cellOf(k: String): Int? = when {
            k == "q" -> 5
            k.startsWith("t") -> k.drop(1).toIntOrNull()?.minus(1)
            k.startsWith("r") -> k.drop(1).toIntOrNull()?.let { base + it - 1 }
            else -> null
        }
    }
    /** the builder: line [line] (null = a new line going at [at]), its composed [text], the body's [len] with it, the line's chips as
     *  [rows] of keys with their s-cells [cells], the glow [cur], [draft] (it differs from the stored line: Remove reads Revert), [remArmed]
     *  (Remove's second A pending), [hard] the fault that refuses Done and its chip [hardChip], [warns] the soft ones */
    data class Builder(val line: Int?, val at: Int?, val text: String, val len: Int, val cmd: String, val cmdName: String?,
                       val look: Look?, val bang: Boolean, val sub: String?, val rank: Pair<Int, Int>?, val seq: List<Look>,
                       val reset: String?, val slot: Int?, val cases: List<Case>, val rows: List<List<String>>, val cells: Map<String, Int>,
                       val cur: String?, val draft: Boolean, val remArmed: Boolean, val hard: String?, val hardChip: String?,
                       val warns: List<String>, val pop: Pop?, val ym: Boolean = false)
    /** a preset's preview: its lines as strips, its picks ("p1".."p3"), Done ([armed] = a macro with lines is replaced on the next A) */
    data class Preset(val key: String, val strips: List<Strip>, val text: String, val len: Int, val picks: List<Pair<Look?, String>>,
                      val cur: String?, val armed: Boolean, val hard: String?)

    data class State(
        val ready: Boolean, val off: Boolean, val win: Boolean, val combat: Boolean, val noRow: Boolean,
        val tab: String, val general: Count, val char: Count, val me: String,
        val mode: String, val view: String, val note: String?, val n: Int,
        val held: Boolean, val want: Boolean, val why: String?,
        val keys: Keys?, val pad: JSONObject?,
        val page: Int, val pages: Int, val cur: Int, val cells: List<Cell>,
        val mac: Mac?, val ef: String?, val efc: Int?, val del: Boolean,
        val pick: Pick?, val recCur: Int?, val kbd: Kbd?,
        /** Spells s10 review: the `wx` cell really is the game's window close now (armed out of combat once the game's macro UI loaded) */
        val wxArmed: Boolean = false,
        /** Spells s12 (`sh`): the room's row is the Calendar row's GUEST (TapPad pad21), so it is asked on every showing before the hold */
        val shared: Boolean = false,
        /** s14: the editor's strips (D3), an empty macro's preset tiles (D2), the draft held (its line, or where a new one goes), Move */
        val strips: List<Strip> = emptyList(),
        val tiles: List<Tile> = emptyList(),
        val draftLine: Int? = null, val draftAt: Int? = null,
        val moveFrom: Int? = null, val moveAt: Int? = null,
        /** s14: the open pop-out outside the builder (the editor's icon, a preset's pick), the presets view's glow, the builder, a preview */
        val pop: Pop? = null, val presetCur: Int = 1,
        val builder: Builder? = null, val preset: Preset? = null,
        /** s15: in the editor, Y opens More now (not on a line: its Move) */
        val ym: Boolean = false,
        /** s16: the editor's line asking to be removed (X on it: Cancel [B] / Remove [A]), and the focused line's X Remove hint */
        val remLine: Int? = null,
        val xRemove: Boolean = false,
    ) {
        val count: Count get() = if (tab == "c") char else general
    }

    private fun cnt(o: JSONObject?): Count = Count(o?.optInt("n", 0) ?: 0, o?.optInt("max", 0) ?: 0)
    private fun str(o: JSONObject, k: String): String? = if (o.has(k) && !o.isNull(k)) o.optString(k).takeIf { it.isNotEmpty() } else null
    private fun intOrNull(o: JSONObject, k: String): Int? = if (o.has(k) && !o.isNull(k)) o.optInt(k) else null
    private fun strings(a: org.json.JSONArray?): List<String> {
        val out = ArrayList<String>()
        if (a != null) for (i in 0 until a.length()) out += a.optString(i, "")
        return out
    }
    private fun look(o: JSONObject?): Look? {
        if (o == null) return null
        val i = o.optInt("i", 0); val n = o.optString("n", "")
        return if (i == 0 && n.isEmpty()) null else Look(i, n)
    }
    private fun cases(a: org.json.JSONArray?): List<Case> {
        val out = ArrayList<Case>()
        if (a != null) for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: JSONObject()
            out += Case(str(o, "u"), strings(o.optJSONArray("f")), look(o.optJSONObject("a")))
        }
        return out
    }
    private fun seq(a: org.json.JSONArray?): List<Look> {
        val out = ArrayList<Look>()
        if (a != null) for (i in 0 until a.length()) look(a.optJSONObject(i))?.let { out += it }
        return out
    }
    fun strip(o: JSONObject): Strip = Strip(
        kind = o.optString("k", "text"), cmd = str(o, "c"), cmdName = str(o, "cn"), look = look(o), bang = o.optBoolean("bang", false),
        sub = str(o, "sub"), seq = seq(o.optJSONArray("sq")), reset = str(o, "rs"), slot = intOrNull(o, "sl"),
        cases = cases(o.optJSONArray("cs")), warn = str(o, "w"))
    fun pop(o: JSONObject?): Pop? {
        if (o == null) return null
        val rows = ArrayList<JSONObject>()
        o.optJSONArray("rows")?.let { a -> for (i in 0 until a.length()) rows += (a.optJSONObject(i) ?: JSONObject()) }
        return Pop(kind = o.optString("k", ""), cur = str(o, "cur"), page = o.optInt("page", 1).coerceAtLeast(1),
            pages = o.optInt("pages", 1).coerceAtLeast(1), cols = o.optInt("cols", 1).coerceAtLeast(1), base = o.optInt("base", 0),
            case = intOrNull(o, "case"), query = str(o, "q"), ranks = o.optInt("m", 0), rows = rows, tabs = strings(o.optJSONArray("tabs")),
            tab = str(o, "tab"), forCmd = str(o, "forc"), forSeq = o.optBoolean("seq", false), forPreset = o.optBoolean("ps", false))
    }
    fun builder(o: JSONObject?): Builder? {
        if (o == null) return null
        val rows = ArrayList<List<String>>()
        o.optJSONArray("rows")?.let { a -> for (i in 0 until a.length()) rows += strings(a.optJSONArray(i)) }
        val cells = HashMap<String, Int>()
        o.optJSONObject("ch")?.let { ch -> for (k in ch.keys()) cells[k] = ch.optInt(k, -1) }
        val a = o.optJSONObject("a")
        val rk = a?.optJSONArray("rk")?.let { r -> if (r.length() >= 2) r.optInt(0) to r.optInt(1) else null }
        return Builder(line = intOrNull(o, "ln"), at = intOrNull(o, "at"), text = o.optString("t", ""), len = o.optInt("len", 0),
            cmd = o.optString("c", "cast"), cmdName = str(o, "cn"), look = look(a), bang = a?.optBoolean("bang", false) ?: false,
            sub = a?.let { str(it, "sub") }, rank = rk, seq = seq(o.optJSONArray("sq")), reset = str(o, "rs"), slot = intOrNull(o, "sl"),
            cases = cases(o.optJSONArray("cs")), rows = rows, cells = cells, cur = str(o, "cur"), draft = o.optBoolean("dr", false),
            remArmed = o.optBoolean("rem", false), hard = str(o, "h"), hardChip = str(o, "hc"), warns = strings(o.optJSONArray("w")),
            pop = pop(o.optJSONObject("pop")), ym = o.optBoolean("ym", false))
    }
    fun preset(o: JSONObject?): Preset? {
        if (o == null) return null
        val strips = ArrayList<Strip>()
        o.optJSONArray("ls")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { strips += strip(it) } }
        val picks = ArrayList<Pair<Look?, String>>()
        o.optJSONArray("picks")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { p ->
            picks += look(p) to p.optString("kind", "any") } }
        return Preset(o.optString("k", ""), strips, o.optString("t", ""), o.optInt("len", 0), picks, str(o, "cur"),
            o.optBoolean("arm", false), str(o, "h"))
    }

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
                cells += if (o.optBoolean("plus", false)) Cell("", 0, plus = true)
                         else Cell(o.optString("n", ""), o.optInt("i", 0), locked = o.optBoolean("lk", false))
            }
        }
        val mac = m.optJSONObject("mac")?.let { o ->
            val lines = ArrayList<String>()
            o.optJSONArray("lines")?.let { a -> for (i in 0 until a.length()) lines += a.optString(i, "") }
            val lock = HashSet<Int>()
            o.optJSONArray("lock")?.let { a -> for (i in 0 until a.length()) lock += a.optInt(i) }
            Mac(o.optString("n", ""), o.optInt("i", 0), lines, o.optInt("len", lines.joinToString("\n").length), lock, o.optBoolean("more", false),
                locked = o.optBoolean("lk", false))
        }
        val pick = m.optJSONObject("pick")?.let { o ->
            val rows = ArrayList<PickRow>()
            o.optJSONArray("rows")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { r ->
                    rows += PickRow(r.optString("t", "s"), r.optString("n", ""), r.optInt("i", 0), str(r, "l"))
                }
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
            wxArmed = m.optBoolean("wxa", false), shared = m.optBoolean("sh", false),
            strips = ArrayList<Strip>().also { l -> m.optJSONArray("ls")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { l += strip(it) } } },
            tiles = ArrayList<Tile>().also { l -> m.optJSONArray("tiles")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { t ->
                val ic = ArrayList<Int>(); t.optJSONArray("ic")?.let { x -> for (j in 0 until x.length()) ic += x.optInt(j, 0) }
                l += Tile(t.optString("k", ""), ic, t.optString("t", "")) } } },
            draftLine = m.optJSONObject("dr")?.let { intOrNull(it, "n") }, draftAt = m.optJSONObject("dr")?.let { intOrNull(it, "at") },
            moveFrom = m.optJSONObject("mv")?.let { intOrNull(it, "from") }, moveAt = m.optJSONObject("mv")?.let { intOrNull(it, "at") },
            pop = pop(m.optJSONObject("pop")), presetCur = m.optInt("pcur", 1),
            builder = builder(m.optJSONObject("b")), preset = preset(m.optJSONObject("ps")),
            ym = m.optBoolean("ym", false),
            remLine = intOrNull(m, "rem"), xRemove = m.optBoolean("xr", false),
        )
    }

    /**
     * D2 (the user's pick: the room closes the game's own macro window ONCE with that window's own close button): the `wx` tap goes
     * when the window shows, the room holds, the cell really is the close button (Spells s10 review: a cell made before the game's
     * macro UI loaded clicks nothing until it is armed, out of combat) and this opening has not had its tap yet
     */
    fun closeWindowNow(s: State?, sentThisOpening: Boolean): Boolean =
        s != null && s.win && s.held && s.wxArmed && s.keys != null && !s.noRow && !sentThisOpening

    /** B leads Home (the Home button shows B): at the grid with no keyboard and no Bind box up */
    fun bLeadsHome(s: State?): Boolean = s == null || (s.mode == "grid" && s.kbd == null)

    /** the add-on's word against the screen (Calendar's c10 rule): "h" = the room shows and is not wanted; "f" = hidden, still held */
    fun ctlFix(s: State?, shown: Boolean): String? = when {
        s == null || s.keys == null -> null
        shown && !s.want -> "h"
        !shown && (s.want || s.held) -> "f"
        else -> null
    }

    /** the taps that make the room's lazy row (Book b63: the padtab one-shot pick, then its code), on the padtab row; Spells s12: they
     *  also put the row, shared with the Calendar's, on its place for this room (TapPad pad21's lease) */
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

    /** the editor's rows as the add-on numbers them (Spells s14 MR.editRows): "name", "icon", then "l1".."lL" (at most [LINE_ROWS]) or
     *  (an empty macro) "t1".."tK" (its preset tiles), then (s15) + Line and More */
    fun editRows(mac: Mac?, tiles: Int = 0): List<String> {
        val out = arrayListOf("name", "icon")
        val n = minOf(mac?.lines?.size ?: 0, LINE_ROWS)
        if (n == 0) { for (i in 1..tiles) out += "t$i"; out += BUTTONS; return out }
        for (i in 1..n) out += "l$i"
        out += BUTTONS
        return out
    }

    /** a row's s-cell (MR.editCell): the name 0, the icon 1, a line / a tile its number + 1, the buttons 21 (+ Line) and 22 (More) */
    fun editCell(row: String): Int = when {
        row == "name" -> 0
        row == "icon" -> 1
        row.startsWith("l") || row.startsWith("t") -> (row.drop(1).toIntOrNull() ?: 0) + 1
        else -> LINE_ROWS + 2 + BUTTONS.indexOf(row).coerceAtLeast(0)
    }

    /** the macro body's length counter as the game's window writes it ("n/255") */
    fun counter(mac: Mac?): String = "${mac?.len ?: 0}/$BODY_MAX"

    /** a pick row's line, as the add-on writes it (MR.lineFor; s13: a pet command's own line) */
    fun lineOf(r: PickRow): String = when (r.kind) {
        "p" -> r.line ?: ""
        "s" -> "/cast ${r.name}"; "u" -> "/use ${r.name}"; else -> "/equip ${r.name}"
    }

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
    fun kbdWord(k: Kbd?): String = when (k?.name) { "macnew" -> "New"; "macname" -> "Name"; "macsearch" -> "Search"; "macunit" -> "Name"
        else -> "Line" }

    // ---------------------------------------------------------------- s14: the builder's words (menu words: Tr.t at the drawing)
    /** a command's word (MR.CMDS' k); a pet order's is the game's own when the add-on sends one (cmdName: never Tr.t) */
    fun cmdWord(k: String?): String = when (k) {
        "cast" -> "Cast"; "use" -> "Use"; "seq" -> "Sequence"; "target" -> "Target"; "focus" -> "Focus"; "assist" -> "Assist"
        "startattack" -> "Start attack"; "stopattack" -> "Stop attack"; "stopcasting" -> "Stop casting"; "cancelaura" -> "Cancel buff"
        "cancelform" -> "Leave form"; "equip" -> "Equip"; "equipslot" -> "Equip slot"; "cleartarget" -> "Clear target"
        "clearfocus" -> "Clear focus"; "targetenemy" -> "Target enemy"; "lasttarget" -> "Last target"; "stopmacro" -> "Stop macro"
        "chat" -> "Chat line"; "tip" -> "Tooltip"
        "petattack" -> "Attack"; "petfollow" -> "Follow"; "petstay" -> "Stay"; "petmoveto" -> "Move To"; "petpassive" -> "Passive"
        "petdefensive", "petdefensiveassist" -> "Defensive"; "petaggressive" -> "Aggressive"; "petassist" -> "Assist"; "petdismiss" -> "Dismiss"
        else -> "Line"
    }
    /** "On" (D5): the controller's targets first, Mouseover last; null / "" = the command's own target */
    private val UNIT_WORDS = mapOf("" to "Target", "target" to "Target", "softenemy" to "Soft enemy", "softfriend" to "Soft friend",
        "focus" to "Focus", "player" to "Me", "targettarget" to "Target's target", "pet" to "Pet", "mouseover" to "Mouseover",
        "name" to "Name…")
    /** a unit's menu word, or null when it is the player's own text (a typed name: shown as it is); party1..4 = "Party" + [partyNo] */
    fun unitWord(u: String?): String? {
        val k = u ?: ""
        UNIT_WORDS[k]?.let { return it }
        if (Regex("^party[1-4]$").matches(k)) return "Party"
        return null
    }
    fun partyNo(u: String?): Int? = u?.let { Regex("^party([1-4])$").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    /** a condition's menu word ("!dead" = Alive; "!x" drawn struck through: [condStruck]); null = kept as the game writes it ("~raw") */
    fun condWord(k: String): String? {
        if (k.startsWith("~")) return null
        return when (k.removePrefix("!")) {
            "harm" -> "Enemy"; "help" -> "Friend"; "dead" -> if (k.startsWith("!")) "Alive" else "Dead"; "exists" -> "Exists"
            "combat" -> "In combat"; "mod:shift" -> "Shift"; "mod:ctrl" -> "Ctrl"; "mod:alt" -> "Alt"; "pet" -> "Pet out"
            "stealth" -> "Stealth"; "mounted" -> "Mounted"; "channeling" -> "Channeling"; "group" -> "Group"; "form" -> "Form"
            else -> null
        }
    }
    fun condStruck(k: String): Boolean = k.startsWith("!") && k != "!dead"
    /** the modifier conditions' glyph action (the player's own set: ZL / LT / L2 is the game's Shift, LB / L / L1 its Ctrl) */
    fun condGlyph(k: String): String? = when (k.removePrefix("!")) { "mod:shift" -> "ltrigger"; "mod:ctrl" -> "lshoulder"; else -> null }
    fun tabWord(t: String?): String = when (t) { "pet" -> "Pet"; "gen" -> "General"; "it" -> "Items"; "sl" -> "Slots"; else -> "Spells" }
    fun caseWord(o: String): String = when (o) {
        "sf1" -> "Soft friend first"; "fo1" -> "Focus first"; "se1" -> "Soft enemy first"; "me" -> "Else on me"
        "tg" -> "Else on target"; "sp" -> "Else another spell"; else -> "Blank"
    }
    /** castsequence's reset words (a time is its seconds, drawn as a number) */
    fun resetWord(o: String): String? = when (o) { "target" -> "Target"; "combat" -> "Combat"; "shift" -> "Shift"; "ctrl" -> "Ctrl"
        "alt" -> "Alt"; else -> null }
    fun presetWord(k: String): String = when (k) {
        "heal" -> "Heal, else me"; "fof" -> "Friend or foe"; "int" -> "Focus interrupt"; "focus" -> "Set focus"; "atk" -> "Attack + spell"
        "petatk" -> "Pet attack + spell"; "seq" -> "Sequence"; "form" -> "Leave form + spell"; "trink" -> "Trinket + spell"
        "mo" -> "Mouseover heal"; "blank" -> "Blank"; else -> "Preset"
    }
    /** a soft warning's word (amber; Done still writes) */
    fun warnWord(w: String?): String? = when (w) { "book" -> "Not in book"; "bags" -> "Not in bags or worn"; "else" -> "No else"; else -> null }
    /** a hard fault's word (Done refused) */
    fun hardWord(h: String?): String? = when (h) { null, "" -> null; "long" -> "Too long"; "pick" -> "Pick"; "empty" -> "Empty"
        else -> "Not done" }
    /** the builder's Remove chip: a new line's draft goes, an edited one reverts, a stored one is removed (by a second A) */
    fun removeWord(b: Builder?): String = when {
        b == null -> "Remove"
        b.line != null && b.draft -> "Revert"
        b.remArmed -> "Remove?"
        else -> "Remove"
    }
    /** s15: a row of the room's More (the add-on's pop-out "more": `o` = pre / del / text / rem, `rv` = Remove reads Revert; s16 ins =
     *  Insert below): its word */
    fun moreWord(o: String, revert: Boolean = false): String = when (o) {
        "ins" -> "Insert below"; "pre" -> "Presets"; "del" -> "Delete"; "text" -> "Aa Text"; "rem" -> if (revert) "Revert" else "Remove"; else -> o
    }
    /** s16: the line More's Insert below goes under while the cursor is on that row (the editor marks it), or null */
    fun insertUnder(p: Pop?): Int? {
        if (p == null || p.kind != "more") return null
        val r = p.cur?.removePrefix("r")?.toIntOrNull() ?: return null
        val row = p.rows.getOrNull(r - 1) ?: return null
        return if (row.optString("o") == "ins" && row.has("l")) row.optInt("l") else null
    }
    /** a builder chip's s-cell, as the add-on numbers it (MR.bcell): null = not on show */
    fun chipCell(b: Builder?, key: String): Int? = b?.cells?.get(key)?.takeIf { it >= 0 }
    /** the Done key's tap (`ok`: the Select button's own work) */
    fun doneCells(s: State?): List<Pair<String, Int>>? = cell(s, "ok")
    /** text past the game's 255 (counted in bytes, as the room writes: an accented name counts more than the game's box) */
    fun tooLong(len: Int): Boolean = len > BODY_MAX
}
