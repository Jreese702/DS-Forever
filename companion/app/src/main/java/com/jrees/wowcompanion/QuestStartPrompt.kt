package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * A QUEST STARTER IN THE BAGS ASKS FIRST (the user, 2026-11-11: "I dont have a polestar objective because its a quest item in my bag I
 * realized. If I pick up a quest item like that we should make a prompt asking if we want to start that quest"; the item was
 * [Ripped Missive]). The add-on (Dialogs d27) offers it as one more of the game's dialogs, which = [WHICH], in the "dialogs" document,
 * so [DialogView] shows it with the same look and the same controls as every other game pop-up: A = Start (the add-on's secure use of
 * the item: the game opens its own quest page, mirrored by Interact's quest pop-up), B = Not now (no more asking for that item this
 * login). Terse (the page-text ruling): the question, the quest's title when the game gives it, the item's icon and name.
 * This file is the prompt's model: what one dialog entry says, and the words the view draws. Pure: tested.
 */
object QuestStartPrompt {
    const val WHICH = "QUEST_START"
    const val QUESTION = "Start quest?"
    const val START = "Start"
    const val LATER = "Not now"

    /** one prompt: the item (id, name, icon, bag and slot) and the quest it starts (id and title, when the game gave them plainly) */
    data class Prompt(val itemId: Int, val itemName: String?, val icon: Int, val bag: Int, val slot: Int, val questId: Int, val title: String?) {
        /** the line under the question: the quest's title, never the item's name twice */
        val sub: String? get() = title?.takeIf { it.isNotBlank() && it != itemName }
    }

    fun isPrompt(which: String?) = which == WHICH

    /** a dialog entry of the "dialogs" document -> its prompt, or null when it is not a well-formed quest starter prompt */
    fun parse(d: JSONObject?): Prompt? {
        if (d == null || !isPrompt(d.optString("which", ""))) return null
        val qs = d.optJSONObject("qs") ?: return null
        val item = qs.optInt("item", 0)
        val bag = qs.optInt("bag", -1)
        val slot = qs.optInt("slot", 0)
        if (item <= 0 || bag < 0 || slot <= 0) return null
        val it = d.optJSONObject("item")
        return Prompt(item, str(it, "name"), it?.optInt("icon", 0) ?: 0, bag, slot, qs.optInt("quest", 0), str(qs, "title"))
    }

    /** the button's label: the app's own words for the prompt's two buttons (k 1 = Start, 2 = Not now), else the add-on's */
    fun label(k: Int, sent: String): String = when (k) { 1 -> START; 2 -> LATER; else -> sent }

    private fun str(o: JSONObject?, key: String): String? =
        if (o != null && o.has(key) && !o.isNull(key)) o.optString(key).takeIf { it.isNotEmpty() } else null
}
