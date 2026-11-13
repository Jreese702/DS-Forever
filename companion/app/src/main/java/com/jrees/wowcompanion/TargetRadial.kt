package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE CONTEXT RADIAL'S SLICES (the user, 2026-11-07: "When targeting something while not in the combat state, I want the Y glyph to
 * appear over them for the inspect menu. Instead of that menu opening a drop down I want to design a radial for it"; then "I want to use
 * these context radicals for everything, no more drop down menus in any of those places, including when selecting more make another
 * radial"). Pure: the game's own menu (Quests' `pmenu`, parsed by [PlayerMenu.parse]) laid on the pause wheel's eight slices, page by page.
 *
 * THE ROOT RING (the kit's order, 0 = the top, clockwise): Whisper, Invite, Trade, Inspect, Follow, Duel, Add Friend where the menu has
 * them, in those places; a place the menu does not fill takes the menu's next entry; slice 7 is More when two or more entries are left
 * (one left goes there itself). MORE = another wheel of the rest, seven at a time with More again while more are left. A SUB-MENU (Raid
 * Target Icon) = a wheel of its own entries. Titles are no slices; a greyed entry stays greyed.
 */
object TargetRadial {
    /** which wheel: the root, More's page [offset] (into what the root does not show), or the sub-menu named [sub] from [offset] */
    data class Page(val sub: String? = null, val more: Boolean = false, val offset: Int = 0) {
        val root get() = sub == null && !more
    }
    val ROOT = Page()

    /** one slice: the menu [entry] it runs, or a wheel to open ([next]: More / a sub-menu) */
    data class Slice(val label: String, val icon: String, val entry: PlayerMenu.Entry?, val next: Page? = null) {
        val more get() = next != null && entry == null
        val enabled: Boolean get() = next != null || entry?.enabled == true
        /** the context menu's icon mixed from game art (MenuIcons), when it has one; an entry's own game icon first (Quests q56: the
         *  pet's actions - the user, 2026-11-13: "The pet inspect radial should include some of the pet actions like dismiss or feed pet") */
        val menu: String? get() = if (more) "more" else entry?.let { e -> e.icon?.let { MenuIcons.iconKey(it) } ?: MenuIcons.keyOf(e.key, e.text) }
    }

    /** the preferred places: the entry key's words (the mixin's name, lower case) and the slice's kit picture (the wheel's ricon_*) */
    private val PREFERRED = listOf(
        listOf("whisper") to "chat",
        listOf("invite") to "groupfinder",
        listOf("trade") to "bags",
        listOf("inspect") to "character",
        listOf("follow") to "map",
        listOf("duel") to "spellbook",
        listOf("addfriend", "addbtagfriend", "friend") to "social",
    )
    const val MORE = 7

    private fun keyOf(e: PlayerMenu.Entry) = (e.key ?: e.text).lowercase().replace(" ", "")

    private fun matches(e: PlayerMenu.Entry, words: List<String>): Boolean {
        val k = keyOf(e)
        if (words.first() == "invite" && (k.contains("uninvite") || k.contains("invitetoguild"))) return false
        return words.any { k.contains(it) }
    }

    private fun sliceOf(e: PlayerMenu.Entry, icon: String = "quests") =
        Slice(e.text, icon, e, if (e.sub != null) Page(sub = e.text) else null)

    /** the root ring and what it leaves for More */
    private fun root(usable: List<PlayerMenu.Entry>): Pair<Array<Slice?>, List<PlayerMenu.Entry>> {
        val out = arrayOfNulls<Slice>(8)
        val used = HashSet<PlayerMenu.Entry>()
        for ((i, p) in PREFERRED.withIndex()) {
            val e = usable.firstOrNull { it !in used && matches(it, p.first) } ?: continue
            out[i] = sliceOf(e, p.second); used += e
        }
        val rest = ArrayList(usable.filter { it !in used })
        for (i in 0 until MORE) if (out[i] == null && rest.isNotEmpty()) out[i] = sliceOf(rest.removeAt(0))
        return out to rest
    }

    /** [list] from [offset]: up to eight, or seven and More ([more] = that More's page) */
    private fun chunk(list: List<PlayerMenu.Entry>, offset: Int, more: (Int) -> Page): List<Slice?> {
        val rest = list.drop(offset)
        if (rest.size <= 8) return List(8) { i -> rest.getOrNull(i)?.let { sliceOf(it) } }
        return List(8) { i -> if (i < MORE) sliceOf(rest[i]) else Slice("More", "settings", null, more(offset + MORE)) }
    }

    private fun findSub(es: List<PlayerMenu.Entry>, text: String): PlayerMenu.Entry? {
        for (e in es) {
            if (e.sub != null && e.text == text) return e
            e.sub?.let { s -> findSub(s, text)?.let { return it } }
        }
        return null
    }

    /** the eight slices of [page] for a menu's [items] (PlayerMenu.parse of the document's items); null = that page is gone */
    fun slices(items: List<PlayerMenu.Entry>, page: Page = ROOT): List<Slice?>? {
        val usable = items.filter { !it.title }
        val (ring, rest) = root(usable)
        return when {
            page.root -> {
                if (rest.size == 1) ring[MORE] = sliceOf(rest[0])
                else if (rest.size >= 2) ring[MORE] = Slice("More", "settings", null, Page(more = true))
                ring.toList()
            }
            page.more -> if (rest.size < 2) null else chunk(rest, page.offset) { o -> Page(more = true, offset = o) }
            else -> findSub(usable, page.sub!!)?.sub?.filter { !it.title }?.let { s ->
                chunk(s, page.offset) { o -> Page(sub = page.sub, offset = o) }
            }
        }
    }

    /** the document's name (nm), or "" */
    fun name(d: JSONObject): String = d.optString("nm", "")
}
