package com.jrees.wowcompanion

import android.content.Context

/**
 * WHICH KNOWN FLIGHT POINTS CONNECT, per character (b-route-unknown-taxi; the user, 2026-10-16: "my current quest is telling me to
 * take a flight path, however I dont have any flight paths connected to where I am at"). The route used to take every known point
 * of a continent as connected. The game says what connects only at a flight master: its map marks the node there (Current) and every
 * node a flight can reach from it (Reachable: known AND connected). Map.lua's "taxinet" document carries that; each one is a GROUP of
 * connected nodes, and groups that share a node are one. A flight is suggested only between two nodes of one group; a character
 * that never opened a flight master in that network walks.
 */
object TaxiNet {
    interface Store { fun get(k: String): String?; fun put(k: String, v: String?) }

    /** the rules, pure: the groups of one character, merged as flight masters show them */
    class Model(private val store: Store) {
        private var char: String? = null
        private var groups: MutableList<MutableSet<Int>> = ArrayList()

        fun character(key: String) {
            if (key == char) return
            char = key
            groups = (store.get("g:$key") ?: "").split(';').filter { it.isNotBlank() }
                .map { g -> g.split(',').mapNotNull { it.trim().toIntOrNull() }.toMutableSet() }
                .filter { it.isNotEmpty() }.toMutableList()
        }

        /** a flight master's answer: the node here and those reachable from it are one network */
        fun onTaxiNet(cur: Int, reach: List<Int>) {
            val c = char ?: return
            val g = (reach + cur).toMutableSet()
            val touching = groups.filter { it.any { n -> n in g } }
            for (t in touching) { g.addAll(t); groups.remove(t) }
            groups.add(g)
            store.put("g:$c", groups.joinToString(";") { it.joinToString(",") })
        }

        /** a flight from node [a] to node [b] is known to exist */
        fun connected(a: Int, b: Int): Boolean = a != b && groups.any { a in it && b in it }
    }

    private var model: Model? = null
    fun init(c: Context) {
        if (model != null) return
        val prefs = c.applicationContext.getSharedPreferences("taxinet", Context.MODE_PRIVATE)
        model = Model(object : Store {
            override fun get(k: String): String? = prefs.getString(k, null)
            override fun put(k: String, v: String?) { prefs.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply() }
        })
    }
    fun character(key: String) { model?.character(key) }
    fun onTaxiNet(cur: Int, reach: List<Int>) { model?.onTaxiNet(cur, reach) }
    fun connected(a: Int, b: Int): Boolean = model?.connected(a, b) ?: false
}
