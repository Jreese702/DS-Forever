package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * SMART Y'S BUFF ROWS (Spells' PadMap m62; the user: "this setting will need its own section in the settings so taht I can also layer in
 * smart buffs ... logic rows, with the bottom row being an Add button ... on a single row I need to be able to add an "or" which would
 * basically open a radial menu for those selected buffs"; picks: an OR row with none up opens a wheel to choose, food / water fixed at
 * the top, yourself only). The add-on keeps the rows per character and decides in the game; this is the Settings section's half: the
 * padmap's `sb` read, and the taps that edit it. The pad is full, so the edits ride Spells' secure "gamemenu" row (its cells past the
 * Game Menu's six): a row pick (sel0 + row, fresh 2 s) then ONE op, and for a spell the op's mode then the spell's own two-tap number on
 * the "spells" row (Book b46 routes it), all in ONE batch. Pure: no Android, tested.
 */
object SmartYModel {
    data class Buff(val id: Int, val name: String, val icon: Int, val imbue: Boolean)

    data class Doc(val rows: List<List<Int>>, val list: List<Buff>, val at: Int?, val row: String, val pad: Boolean, val sel0: Int,
                   val ops: Map<String, Int>)

    /** the padmap's `sb` (null = an add-on before m62: no section) */
    fun parse(o: JSONObject?): Doc? {
        if (o == null) return null
        val rows = ArrayList<List<Int>>()
        o.optJSONArray("rows")?.let { a ->
            for (k in 0 until a.length()) {
                val ids = a.optJSONObject(k)?.optJSONArray("ids") ?: continue
                rows += (0 until ids.length()).map { ids.optInt(it, 0) }.filter { it > 0 }
            }
        }
        val list = ArrayList<Buff>()
        o.optJSONArray("list")?.let { a ->
            for (k in 0 until a.length()) {
                val e = a.optJSONObject(k) ?: continue
                val id = e.optInt("id", 0); val n = e.optString("n", "")
                if (id > 0 && n.isNotEmpty()) list += Buff(id, n, e.optInt("i", 0), e.optString("k") == "imbue")
            }
        }
        val ops = HashMap<String, Int>()
        o.optJSONObject("ops")?.let { m -> for (k in m.keys()) ops[k] = m.optInt(k, -1) }
        return Doc(rows, list, if (o.has("at")) o.optInt("at") else null, o.optString("row", "gamemenu"), o.optBoolean("pad", false),
            o.optInt("sel0", 8), ops)
    }

    /** a row's buff names, in its order ("Devotion Aura or Retribution Aura" when joined); a spell this character no longer has is named by id */
    fun names(doc: Doc, row: Int): List<String> =
        doc.rows.getOrNull(row)?.map { id -> doc.list.firstOrNull { it.id == id }?.name ?: "Spell $id" } ?: emptyList()

    fun label(doc: Doc, row: Int): String = names(doc, row).joinToString(" or ")

    /** an OR row: more than one buff (none up: Y opens the wheel) */
    fun isOr(doc: Doc, row: Int): Boolean = (doc.rows.getOrNull(row)?.size ?: 0) > 1

    /** what Add offers: the character's self-buffs not on any row yet */
    fun addChoices(doc: Doc): List<Buff> = doc.list.filter { b -> doc.rows.none { b.id in it } }

    /** what "Add or" offers on [row]: the self-buffs not on that row (10 at most on a row) */
    fun orChoices(doc: Doc, row: Int): List<Buff> {
        val ids = doc.rows.getOrNull(row) ?: return emptyList()
        if (ids.size >= MAX_OR) return emptyList()
        return doc.list.filter { it.id !in ids }
    }

    /** what "Take out" offers on [row]: its own buffs */
    fun outChoices(doc: Doc, row: Int): List<Buff> =
        doc.rows.getOrNull(row)?.mapNotNull { id -> doc.list.firstOrNull { it.id == id } } ?: emptyList()

    private fun op(doc: Doc, k: String): Int? = doc.ops[k]?.takeIf { it >= 0 }
    private fun pick(doc: Doc, row: Int) = doc.row to (doc.sel0 + row)

    /** Add [spell] as a new row: the op's mode, then the spell's two taps ([spellCells], Book's) */
    fun addTaps(doc: Doc, spellCells: List<Pair<String, Int>>?): List<Pair<String, Int>>? {
        val a = op(doc, "add") ?: return null
        if (spellCells.isNullOrEmpty() || doc.rows.size >= MAX_ROWS) return null
        return listOf(doc.row to a) + spellCells
    }

    /** "Add or" on [row] ([kind] "addor") or "Take out" ([kind] "out"): the row's pick, the op's mode, the spell's two taps */
    fun spellTaps(doc: Doc, row: Int, kind: String, spellCells: List<Pair<String, Int>>?): List<Pair<String, Int>>? {
        val o = op(doc, kind) ?: return null
        if (spellCells.isNullOrEmpty() || row !in doc.rows.indices) return null
        return listOf(pick(doc, row), doc.row to o) + spellCells
    }

    /** Remove [row], or move it up / down ([kind] "remove" / "up" / "down"): the row's pick, then the op */
    fun rowTaps(doc: Doc, row: Int, kind: String): List<Pair<String, Int>>? {
        val o = op(doc, kind) ?: return null
        if (row !in doc.rows.indices) return null
        if (kind == "up" && row == 0 || kind == "down" && row == doc.rows.size - 1) return null
        return listOf(pick(doc, row), doc.row to o)
    }

    const val MAX_ROWS = 16
    const val MAX_OR = 10
}
