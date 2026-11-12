package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * SMART Y'S LIST (Spells' PadMap m62; the user: "this setting will need its own section in the settings so taht I can also layer in
 * smart buffs ... logic rows, with the bottom row being an Add button ... on a single row I need to be able to add an "or" which would
 * basically open a radial menu for those selected buffs"; picks: an OR row with none up opens a wheel to choose, yourself only). The
 * add-on keeps the list per character and decides in the game; this is the Settings section's half: the padmap's `sb` read, and the
 * taps that edit it. The pad is full, so the edits ride Spells' secure "gamemenu" row (its cells past the Game Menu's six): a row pick
 * (sel0 + row, fresh 2 s) then ONE op, and for a spell the op's mode then the spell's own two-tap number on the "spells" row (Book b46
 * routes it), all in ONE batch. Pure: no Android, tested.
 *
 * m127 (the user, 2026-11-11: "it shouldnt hardcode food and water as first and second, let people move spell priorities above them";
 * Manners a row of its own): ONE ordered list of typed rows - Food, Water, Manners and the buff rows - moved and removed the same way;
 * Add then the kind's own select cell ([Doc.kinds]) adds Food / Water / Manners back. An add-on before m127 sends buff rows only (no
 * `k`): read as buff rows, with Food and Water [Doc.fixedFood] above them as before.
 */
object SmartYModel {
    data class Buff(val id: Int, val name: String, val icon: Int, val imbue: Boolean)

    /** one row of the list: [kind] "buff" (its [ids], one = a buff, more = an OR row), "food", "drink" or "manners" */
    data class Row(val kind: String, val ids: List<Int> = emptyList()) {
        val isBuff: Boolean get() = kind == BUFF
    }

    data class Doc(val rows: List<Row>, val list: List<Buff>, val at: Int?, val row: String, val pad: Boolean, val sel0: Int,
                   val ops: Map<String, Int>,
                   /** m76: Smart Y on for this character (off by default: Y as before Smart Y); its two absolute cells */
                   val on: Boolean = false, val onCol: Int = 6, val offCol: Int = 7,
                   /** m127: Manners installed (its row shows, and Add offers it) */
                   val manners: Boolean = false,
                   /** m127: after Add, the select cell that adds a kind ("food" / "drink" / "manners") */
                   val kinds: Map<String, Int> = emptyMap(),
                   /** m127: the kinds' pictures (game icon file ids; 0 = none) */
                   val icons: Map<String, Int> = emptyMap(),
                   val max: Int = MAX_ROWS,
                   /** an add-on before m127: Food / Water fixed above the buff rows, not in [rows] */
                   val fixedFood: Boolean = false)

    /** the padmap's `sb` (null = an add-on before m62: no section) */
    fun parse(o: JSONObject?): Doc? {
        if (o == null) return null
        val rows = ArrayList<Row>()
        var typed = false
        o.optJSONArray("rows")?.let { a ->
            for (k in 0 until a.length()) {
                val r = a.optJSONObject(k) ?: continue
                val kind = r.optString("k", "")
                if (kind.isNotEmpty()) typed = true
                when (kind) {
                    FOOD, DRINK, MANNERS -> rows += Row(kind)
                    "", BUFF -> {
                        val ids = r.optJSONArray("ids") ?: continue
                        val list = (0 until ids.length()).map { ids.optInt(it, 0) }.filter { it > 0 }
                        if (list.isNotEmpty()) rows += Row(BUFF, list)
                    }
                    else -> {}                                               // a kind of a newer add-on: not shown
                }
            }
        }
        val v2 = typed || o.optInt("v", 0) >= 2
        val list = ArrayList<Buff>()
        o.optJSONArray("list")?.let { a ->
            for (k in 0 until a.length()) {
                val e = a.optJSONObject(k) ?: continue
                val id = e.optInt("id", 0); val n = e.optString("n", "")
                if (id > 0 && n.isNotEmpty()) list += Buff(id, n, e.optInt("i", 0), e.optString("k") == "imbue")
            }
        }
        fun ints(key: String): Map<String, Int> {
            val m = HashMap<String, Int>()
            o.optJSONObject(key)?.let { j -> for (k in j.keys()) m[k] = j.optInt(k, -1) }
            return m
        }
        return Doc(rows, list, if (o.has("at")) o.optInt("at") else null, o.optString("row", "gamemenu"), o.optBoolean("pad", false),
            o.optInt("sel0", 8), ints("ops"), o.optBoolean("on", false), o.optInt("onCol", 6), o.optInt("offCol", 7),
            manners = o.optBoolean("mn", false), kinds = ints("kinds").filterValues { it >= 0 }, icons = ints("icons").filterValues { it > 0 },
            max = o.optInt("max", MAX_ROWS), fixedFood = !v2)
    }

    /** a buff row's names, in its order; a spell this character no longer has is named by id; a kind row: its one name */
    fun names(doc: Doc, row: Int): List<String> {
        val r = doc.rows.getOrNull(row) ?: return emptyList()
        if (!r.isBuff) return listOf(kindName(r.kind))
        return r.ids.map { id -> doc.list.firstOrNull { it.id == id }?.name ?: "Spell $id" }
    }

    fun label(doc: Doc, row: Int): String = names(doc, row).joinToString(" or ")

    /** the short name of a kind row (terse: the page says what, the spec says how) */
    fun kindName(kind: String): String = when (kind) { FOOD -> "Food"; DRINK -> "Water"; MANNERS -> "Manners"; else -> kind }

    /** a kind row's tag ("< 80%"); none for the rest */
    fun kindTag(kind: String): String? = when (kind) { FOOD, DRINK -> "< 80%"; MANNERS -> "others"; else -> null }

    /** an OR row: more than one buff (none up: Y opens the wheel) */
    fun isOr(doc: Doc, row: Int): Boolean = doc.rows.getOrNull(row)?.let { it.isBuff && it.ids.size > 1 } == true

    /** the row's pictures: a buff row's icons in its order (0 = none known), a kind's picture */
    fun icons(doc: Doc, row: Int): List<Int> {
        val r = doc.rows.getOrNull(row) ?: return emptyList()
        if (!r.isBuff) return listOf(doc.icons[r.kind] ?: 0)
        return r.ids.map { id -> doc.list.firstOrNull { it.id == id }?.icon ?: 0 }
    }

    /** what Add offers: the character's self-buffs not on any row yet */
    fun addChoices(doc: Doc): List<Buff> = doc.list.filter { b -> doc.rows.none { b.id in it.ids } }

    /** m127: the kinds Add offers back: Food / Water / Manners (installed) not on the list, each with its own cell */
    fun kindChoices(doc: Doc): List<String> {
        if (doc.fixedFood) return emptyList()
        return listOf(FOOD, DRINK, MANNERS).filter { k ->
            doc.kinds.containsKey(k) && doc.rows.none { it.kind == k } && (k != MANNERS || doc.manners)
        }
    }

    /** what "Or" offers on [row]: the self-buffs not on that row (10 at most on a row); buff rows only */
    fun orChoices(doc: Doc, row: Int): List<Buff> {
        val r = doc.rows.getOrNull(row)?.takeIf { it.isBuff } ?: return emptyList()
        if (r.ids.size >= MAX_OR) return emptyList()
        return doc.list.filter { it.id !in r.ids }
    }

    /** what "Take out" offers on [row]: its own buffs */
    fun outChoices(doc: Doc, row: Int): List<Buff> =
        doc.rows.getOrNull(row)?.takeIf { it.isBuff }?.ids?.mapNotNull { id -> doc.list.firstOrNull { it.id == id } } ?: emptyList()

    /** the list is full (the selects' 16 cells) */
    fun full(doc: Doc): Boolean = doc.rows.size >= doc.max

    private fun op(doc: Doc, k: String): Int? = doc.ops[k]?.takeIf { it >= 0 }
    private fun pick(doc: Doc, row: Int) = doc.row to (doc.sel0 + row)

    /** Add [spell] as a new row: the op's mode, then the spell's two taps ([spellCells], Book's) */
    fun addTaps(doc: Doc, spellCells: List<Pair<String, Int>>?): List<Pair<String, Int>>? {
        val a = op(doc, "add") ?: return null
        if (spellCells.isNullOrEmpty() || full(doc)) return null
        return listOf(doc.row to a) + spellCells
    }

    /** m127: Add a kind back (Food / Water / Manners): the op's mode, then the kind's select cell */
    fun addKindTaps(doc: Doc, kind: String): List<Pair<String, Int>>? {
        val a = op(doc, "add") ?: return null
        val cell = doc.kinds[kind] ?: return null
        if (full(doc) || kind !in kindChoices(doc)) return null
        return listOf(doc.row to a, doc.row to cell)
    }

    /** "Or" on [row] ([kind] "addor") or "Take out" ([kind] "out"): the row's pick, the op's mode, the spell's two taps; buff rows only */
    fun spellTaps(doc: Doc, row: Int, kind: String, spellCells: List<Pair<String, Int>>?): List<Pair<String, Int>>? {
        val o = op(doc, kind) ?: return null
        if (spellCells.isNullOrEmpty() || doc.rows.getOrNull(row)?.isBuff != true) return null
        return listOf(pick(doc, row), doc.row to o) + spellCells
    }

    /** Remove [row], or move it up / down ([kind] "remove" / "up" / "down"): the row's pick, then the op */
    fun rowTaps(doc: Doc, row: Int, kind: String): List<Pair<String, Int>>? {
        val o = op(doc, kind) ?: return null
        if (row !in doc.rows.indices || row >= doc.max) return null          // past the 16 selects: move the row above it down
        if (kind == "up" && row == 0 || kind == "down" && row == doc.rows.size - 1) return null
        return listOf(pick(doc, row), doc.row to o)
    }

    /** m76: Smart Y on / off: ONE absolute tap */
    fun toggleTaps(doc: Doc, on: Boolean): List<Pair<String, Int>> = listOf(doc.row to (if (on) doc.onCol else doc.offCol))

    /** m76: the section shows its rows and Add only while Smart Y is on */
    fun rowsShown(doc: Doc?): Boolean = doc?.on == true

    /** m127: the row Y would act on now, from the padmap's `smarty` kind ([nowKind]: food / drink / both / manners / buff / or) */
    fun nowRow(doc: Doc, nowKind: String?): Int? {
        val k = when (nowKind) { null -> return null; "both" -> FOOD; "or" -> BUFF; else -> nowKind }
        if (k == BUFF) return doc.at?.minus(1)?.takeIf { it in doc.rows.indices && doc.rows[it].isBuff }
        return doc.rows.indexOfFirst { it.kind == k }.takeIf { it >= 0 }
    }

    const val BUFF = "buff"
    const val FOOD = "food"
    const val DRINK = "drink"
    const val MANNERS = "manners"
    const val MAX_ROWS = 16
    const val MAX_OR = 10
}
