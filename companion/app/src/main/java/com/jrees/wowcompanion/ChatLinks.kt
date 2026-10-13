package com.jrees.wowcompanion

/**
 * The Chat rework's rules that do not depend on the look (the user's Chat rework, 2026-10-07): the links in a line (Q5: items,
 * spells, quests, players) and quick chat's presets (Q4: sent by the add-on on the tap). Pure Kotlin, tested off the device
 * (tools/chat_test).
 *
 * A line's `links` come from Chat.lua (chat11): `[{k, id, n, p}]` in the order they stand in the text. The text keeps a link as
 * its name in brackets ("WTS [Copper Bar] x20"), so where each one sits is found again here, in order, for the light on the
 * chosen link and for a finger on it.
 */
object ChatLinks {
    class Link(val k: String, val id: Int?, val n: String, val p: String?) {
        /** the tooltip comes from the add-on's `chatlink` document, by this key; a player has none (its view is the actions) */
        val tipKey: String? get() = if (k == "player") null else p
        override fun toString() = "$k:$id:$n"
    }

    /**
     * Where each link stands in [text], in order: its "[name]", brackets included; a link whose name is not found again (the add-on
     * cut the line) gets null and is skipped by [step]. A second link to the same thing finds the second place.
     */
    fun spans(text: String, links: List<Link>): List<IntRange?> {
        var from = 0
        return links.map { l ->
            val want = "[" + l.n + "]"
            var at = if (l.n.isEmpty()) -1 else text.indexOf(want, from)
            var len = want.length
            if (at < 0 && l.n.isNotEmpty()) {
                at = text.indexOf(l.n, from); len = l.n.length
            }
            if (at < 0) null else {
                from = at + len
                at until at + len
            }
        }
    }

    /** The first link a line's cursor lands on: the first one that stands in the text, or -1. */
    fun first(spans: List<IntRange?>): Int = spans.indexOfFirst { it != null }

    /** Left / right among a line's links; at either end it stays (no wrap: the user sees where the row ends). */
    fun step(cur: Int, d: Int, spans: List<IntRange?>): Int {
        var k = cur + d
        while (k in spans.indices) {
            if (spans[k] != null) return k
            k += d
        }
        return cur
    }

    /** A finger at character [ch] of the line: the link under it, or -1. */
    fun at(ch: Int, spans: List<IntRange?>): Int = spans.indexOfFirst { it != null && ch in it }

    /** The tooltips the add-on has sent, by payload; the oldest go first once [cap] is reached. */
    class Tips<T>(private val cap: Int = 300) {
        private val map = object : LinkedHashMap<String, T>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?) = size > cap
        }
        fun put(key: String, tip: T) { map[key] = tip }
        operator fun get(key: String?): T? = key?.let { map[it] }
        val size: Int get() = map.size
    }

    // ---------------------------------------------------------------- quick chat
    /**
     * The presets, BY INDEX: Chat.lua's WC.CHAT_PRESETS is the same list; the pad cell is the index (row "chq", cols 0-10). The
     * user (2026-10-07): "Replace Good game with GG", "replace be right back with BRB", and Inviting's place went to the Keyboard
     * button: quick chat's grid is these 11 and then [KEYBOARD_TILE] in the bottom right.
     */
    val PRESETS = listOf("Hello!", "Thanks!", "On my way", "Ready", "One moment", "Need help?", "GG", "BRB", "Yes", "No", "lol")

    /** quick chat's tiles: the phrases, then the Keyboard button (the user: "make the keyboard one of the buttons in the bottom right") */
    const val KEYBOARD_TILE = 11
    const val TILES = 12

    /** The chat types quick chat can send on, and their "chq" cells (16-21): setting one is no game action. */
    val TYPE_COL = linkedMapOf("SAY" to 16, "PARTY" to 17, "RAID" to 18, "GUILD" to 19, "YELL" to 20, "REPLY" to 21)

    /** A preset's cell, or -1. */
    fun presetCol(k: Int): Int = if (k in PRESETS.indices) k else -1
}
