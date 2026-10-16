package com.jrees.wowcompanion

import android.content.SharedPreferences

/**
 * f-new-spell-prompt (the user, 1.0.9: "After learning new spells when I return home they should have a prompt 1 at a time a to bind or
 * b to dismiss"). The add-on already reports what the character learns (Spells.lua `news`: LEARNED_SPELL_IN_SKILL_LINE, by spell id);
 * this decides which of those deserve a prompt, keeps them in order PER CHARACTER (name-realm) across restarts, and never asks twice
 * about a spell that was bound or dismissed. Pure rules here; the host shows the prompt (CompanionHost.newSpellAsk).
 */
object NewSpells {
    /** the book's entry for spell [id] (any rank), or null */
    fun entryOf(book: SpellsModel.Book, id: Int): SpellsModel.Entry? = book.items.firstOrNull { (it.spellId ?: it.actionId) == id }

    /** what the Bind flow binds for [id]: the Controller list's entry of that spell (its pad number), the same line and name */
    fun bindEntry(book: SpellsModel.Book, id: Int): SpellsModel.Entry? {
        val e = entryOf(book, id) ?: return null
        val list = SpellsModel.tabSpells(book)
        return list.firstOrNull { (it.spellId ?: it.actionId) == id }
            ?: list.firstOrNull { it.name == e.name && it.line == e.line }
    }

    /**
     * Should learned spell [id] be asked about? A castable spell the player can put on a button (not passive, not a profession's, a pad
     * number), and NO rank of it already on a bar or in one of the companion's sets ([onPad]: spell ids the padmap shows anywhere, In
     * combat ones included) - a new rank of a spell that is already bound is not news; a new rank of one that is not, is.
     */
    fun worthAsking(book: SpellsModel.Book, id: Int, onPad: Set<Int>): Boolean {
        val e = entryOf(book, id) ?: return false
        if (e.passive || e.pet || e.flyoutMember || e.prof != null || e.kind != SpellsModel.Kind.SPELL) return false
        if (bindEntry(book, id)?.padNumber == null) return false
        val family = SpellsModel.ranksOf(book, e).ifEmpty { listOf(e) }
        return family.none { f -> f.bars.isNotEmpty() || (f.spellId ?: f.actionId) in onPad }
    }

    /** the spell ids a padmap shows on any button: the bar's spells, the In-combat (overlay) spells, the faces' fight spells */
    fun onPad(map: PadMapModel.Map?): Set<Int> {
        if (map == null) return emptySet()
        val out = HashSet<Int>()
        for (st in map.sets) for (sl in st.slots) {
            sl.id?.takeIf { sl.kind == PadMapModel.Kind.SPELL }?.let { out += it }
            sl.combat?.id?.let { out += it }; sl.otherwise?.id?.let { out += it }; sl.fight?.id?.let { out += it }
        }
        for (ff in map.formFights) ff.ref.id?.let { out += it }
        return out
    }

    /** the queue after the add-on's [news] (spell ids, oldest first): each new, worthy, not-yet-handled spell once, in learning order */
    fun enqueue(queue: List<Int>, handled: Set<Int>, news: List<Int>, worthy: (Int) -> Boolean): List<Int> {
        val out = queue.toMutableList()
        for (id in news) if (id !in handled && id !in out && worthy(id)) out += id
        return out
    }

    /** the queue as it should be shown now: a spell the player bound meanwhile (it shows on a bar) drops out unasked */
    fun stillWanted(queue: List<Int>, worthy: (Int) -> Boolean): List<Int> = queue.filter(worthy)

    /** the per-character store (SharedPreferences "newspells"): "q.<who>" the queue, "h.<who>" every spell already asked about */
    class Store(private val prefs: SharedPreferences) {
        private fun ids(s: String?): List<Int> = s?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        fun queue(who: String): List<Int> = ids(prefs.getString("q.$who", null))
        fun handled(who: String): Set<Int> = ids(prefs.getString("h.$who", null)).toSet()
        fun setQueue(who: String, q: List<Int>) { prefs.edit().putString("q.$who", q.joinToString(",")).apply() }
        /** bound or dismissed: out of the queue, never asked again */
        fun done(who: String, id: Int) {
            prefs.edit().putString("q.$who", queue(who).filter { it != id }.joinToString(","))
                .putString("h.$who", (handled(who) + id).joinToString(",")).apply()
        }
        /** every queued id counts as handled the moment it is queued, so a lost queue never brings a spell back twice */
        fun add(who: String, q: List<Int>) {
            prefs.edit().putString("q.$who", q.joinToString(",")).putString("h.$who", (handled(who) + q).joinToString(",")).apply()
        }
    }
}
