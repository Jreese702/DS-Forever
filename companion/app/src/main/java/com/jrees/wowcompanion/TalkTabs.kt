package com.jrees.wowcompanion

/**
 * HOLD TO TALK'S CHAT TABS (the user, 2026-11-17: "on the push to talk, I want to add some pop up tabs to press L or R to change what chat
 * that its for. lets give it its own pop up UI around it."): the chats the player can talk in NOW, in the Chat room's own order, names
 * and colours (ChatView.talkTabs: Say, Party / Raid / Guild when there, Yell, Reply, the joined channels, the whisper's person); the
 * hold starts on the last used one (TalkKeep), LB / RB move one tab and STOP at the ends (the Chat room's "no cycling" ruling, b119),
 * a finger picks one. The send goes to the chosen tab and keeps it as the last used chat.
 *
 * Pure: the host seeds it when the hold starts and refreshes it when the chats change (a group left: back to Say).
 */
class TalkTabs {
    /** one tab: [key] the composer's type (SAY .. WHISPER, "CHANNEL:<n>"), [label] the Chat room's tab name, [whisper] its person */
    data class Tab(val key: String, val label: String, val colour: Int, val whisper: String = "")

    var tabs: List<Tab> = emptyList(); private set
    var sel = 0; private set

    val canL: Boolean get() = sel > 0
    val canR: Boolean get() = sel < tabs.size - 1

    /** the hold starts: [ts] the chats now, on [startKey] (TalkKeep's, resolved); not there = the first (Say) */
    fun seed(ts: List<Tab>, startKey: String) {
        tabs = ts
        sel = index(startKey) ?: 0
    }

    /** LB (-1) / RB (+1): one tab that way; at an end it stays (false) */
    fun step(dir: Int): Boolean {
        val i = sel + dir
        if (dir == 0 || i !in tabs.indices) return false
        sel = i
        return true
    }

    /** a finger on a tab */
    fun pick(key: String): Boolean {
        val i = index(key) ?: return false
        if (i == sel) return false
        sel = i
        return true
    }

    /** the chats changed while the panel is up: the chosen one stays if it is still there, else Say */
    fun refresh(ts: List<Tab>) {
        val k = now()?.key
        tabs = ts
        sel = k?.let { index(it) } ?: index(TalkKeep.SAY) ?: 0
    }

    /** the chosen chat (null = none seeded) */
    fun now(): Tab? = tabs.getOrNull(sel)

    fun clear() { tabs = emptyList(); sel = 0 }

    private fun index(key: String): Int? = tabs.indexOfFirst { it.key == key }.takeIf { it >= 0 }
}
