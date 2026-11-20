package com.jrees.wowcompanion

/**
 * GitHub #471 (a player on 1.0.33: "I cannot see which rank my spells are when assigning them to my crossbars"): THE RANK BADGE. Every
 * picture of a ranked spell (Home's buttons and fight faces, the Spellbook's rows, the spell wheels, the tap-to-cast bar, the Controller
 * room's buttons and its chooser) wears a small "R3" ([UiKit.spellRank]).
 *
 * The rank is plain add-on data: the `spells` document's `sub` for every book entry (Spells.lua readItem: the book item's subName, else
 * C_Spell.GetSpellSubtext), the lower ranks included whether the book shows them or not. Nothing is read off the screen and the add-on
 * sends nothing new for it (no tag changed).
 *
 * The rules (the issue's brief):
 * - a badge only for a spell whose FAMILY (the same name on the same skill line, as [SpellsModel.ranksOf] and Book.lua's rank picker
 *   group it) has two or more known ranks with a number; a rankless spell ("Racial", none) or a lone rank has none;
 * - [Badge.top] false = the button holds a rank below the highest the player knows: the badge is drawn grey (a hint, nothing more);
 * - a passive, a profession's spell or a spell not known yet never has one; a pet's spells group among the pet's own.
 */
object SpellRanks {
    /** [rank] = the number in the game's sub-name ("Rank 3" -> 3); [top] = it is the highest rank of its family the player knows */
    data class Badge(val rank: Int, val top: Boolean) {
        /** the badge's words: "R3" (terse: a letter and the number, the same in every language the app speaks) */
        val text: String get() = "R$rank"
    }

    @Volatile private var book: SpellsModel.Book? = null
    @Volatile private var index: Map<Int, Badge> = emptyMap()

    /** the host's newest `spells` document (CompanionHost, every book); the same book twice costs nothing */
    fun update(b: SpellsModel.Book?) {
        if (b === book) return
        book = b
        index = if (b == null) emptyMap() else build(b)
    }

    /** the badge of spell [spellId] on a button, or null (no book yet, not a spell of the book, no family of ranks, or the player's
     *  "Spell rank badges" setting is Off: [HomeSettings.rankBadges], off by default - every surface asks here, so one switch hides all) */
    fun of(spellId: Int?): Badge? = if (!HomeSettings.rankBadges) null else spellId?.takeIf { it > 0 }?.let { index[it] }

    /** spell id -> its badge, for every known spell of [b] with a family of two or more numbered ranks */
    fun build(b: SpellsModel.Book): Map<Int, Badge> {
        val out = HashMap<Int, Badge>()
        b.items.filter { it.kind == SpellsModel.Kind.SPELL && !it.passive && it.prof == null && (it.spellId ?: 0) > 0 }
            .groupBy { Triple(it.pet, it.line, it.name) }
            .values.forEach { fam ->
                val ranked = fam.mapNotNull { e -> SpellsModel.rankNo(e.rank)?.takeIf { it > 0 }?.let { e to it } }
                if (ranked.map { it.second }.distinct().size < 2) return@forEach
                val top = ranked.maxOf { it.second }
                for ((e, r) in ranked) out[e.spellId!!] = Badge(r, r == top)
            }
        return out
    }
}
