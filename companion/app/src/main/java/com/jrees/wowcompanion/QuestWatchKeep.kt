package com.jrees.wowcompanion

import android.content.Context

/**
 * THE QUEST WATCH LIST, KEPT (the user, 2026-10-15: "All of my tracked quests are getting untracked between updates"; the pick:
 * "Restore silently"). The game keeps its watch list in the state it writes at logout / exit / reload, so a killed game (an install,
 * the app swiped away, a crash) comes back with none. DS Forever keeps each character's watch list (by name-realm) from the quests
 * documents, and AFTER AN UNCLEAN END ONLY - the last session of that character had no "bye" (the add-on's word at logout) and no
 * clean exit - when the game comes back with an EMPTY watch list, it watches the same quests again through the game's own
 * AddQuestWatch (the quests add-on's "watch" action), at most [MAX], only those still in the log, with no prompt. The user granted
 * this one re-tracking as an exception to "leave no trace"; nothing else is ever changed.
 */
object QuestWatchKeep {
    const val MAX = 25

    /** one quest of a quests document: its id, its pick column, watched now */
    data class Q(val id: Int, val slot: Int, val watched: Boolean)

    /** where the lists live (SharedPreferences on the device, a map in the tests) */
    interface Store { fun get(k: String): String?; fun put(k: String, v: String?) }

    /**
     * The rules, pure. [character] / [newSession] / [cleanEnd] follow the game; [onQuests] keeps the list and answers what to watch
     * again (empty = nothing).
     */
    class Model(private val store: Store) {
        private var char: String? = null
        private var checked = false

        /** the character now (a padmap names it); another one starts its own check */
        fun character(key: String) { if (key != char) { char = key; checked = false } }

        /** a login or a reload (the add-on's hello): the next quests document is the session's first */
        fun newSession() { checked = false }

        /** the character logged out or the game exited by itself: its state is saved, nothing to restore next time */
        fun cleanEnd() { char?.let { store.put("c:$it", "1") } }

        /**
         * A CURRENT quests document's [list]. [canAct] = the restore could be sent now (the game live, no fight, the pad there); the
         * session's check waits for it. Returns the quests to watch again, in the order they were watched.
         */
        fun onQuests(list: List<Q>, canAct: Boolean): List<Q> {
            val c = char ?: return emptyList()
            if (list.isEmpty()) return emptyList()                       // the log is not loaded yet: decide nothing, keep nothing
            val watched = list.filter { it.watched }.map { it.id }
            if (!checked) {
                if (!canAct) return emptyList()
                checked = true
                val clean = store.get("c:$c") == "1"
                val saved = store.get("w:$c")?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
                store.put("c:$c", null)                                  // this session runs: unclean until its bye
                if (!clean && watched.isEmpty() && saved.isNotEmpty()) {
                    val inLog = list.associateBy { it.id }
                    val back = saved.mapNotNull { inLog[it] }.filter { it.slot >= 0 }.take(MAX)
                    if (back.isNotEmpty()) return back                   // the kept list stays until the game shows them watched
                }
            }
            store.put("w:$c", watched.joinToString(","))
            return emptyList()
        }
    }

    private var model: Model? = null
    fun init(c: Context) {
        if (model != null) return
        val prefs = c.applicationContext.getSharedPreferences("questwatch", Context.MODE_PRIVATE)
        model = Model(object : Store {
            override fun get(k: String): String? = prefs.getString(k, null)
            override fun put(k: String, v: String?) { prefs.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply() }
        })
    }

    fun character(key: String) { model?.character(key) }
    fun newSession() { model?.newSession() }
    fun cleanEnd() { model?.cleanEnd() }
    fun onQuests(list: List<Q>, canAct: Boolean): List<Q> = model?.onQuests(list, canAct) ?: emptyList()

    /** a quests document's list (its `list`: id, slot, watch) */
    fun parse(q: org.json.JSONObject): List<Q> {
        val a = q.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<Q>(a.length())
        for (i in 0 until a.length()) {
            val e = a.optJSONObject(i) ?: continue
            val id = e.optInt("id", 0)
            if (id == 0) continue
            out.add(Q(id, if (e.has("slot")) e.optInt("slot", -1) else -1, e.has("watch") && !e.isNull("watch")))
        }
        return out
    }
}
