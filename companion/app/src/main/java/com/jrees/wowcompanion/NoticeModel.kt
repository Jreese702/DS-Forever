package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE WORLD NOTICE OVER HOME'S CHAT (the user 2026-10-09: "I have a world notification right now that the world is going to refresh in 5
 * minutes ... make it a notification above my chat in the home"; the pick "A: system line"). The game's SHARD TRANSFER pop-up
 * (StaticPopup SHARD_TRANSFER_IMMINENT_EVENT, its time GetEvictionTimeRemaining) comes from Chat's observer (chat21) as a `notice`
 * document: `left` whole seconds, the game's own `text`, `urgent` in the last minute, `ended` once. Between documents (every 10 s, and
 * at each wording change) the app ticks the seconds itself. Information only: nothing is pressed in the game. No Android in it.
 */
object NoticeModel {
    /** no document for this long (the game closed, the log stopped): the strip goes (Chat's advice) */
    const val STALE_MS = 30_000L

    /** the notice on show: [id] (a /reload starts a new one), [leftAt] = `left` seconds at [at] (uptime ms) */
    data class Notice(val id: String, val kind: String, val text: String, val left: Int, val at: Long, val urgent: Boolean, val seenAt: Long) {
        /** whole seconds left now, never under 0 */
        fun left(now: Long): Int = (left - ((now - at) / 1000L).toInt()).coerceAtLeast(0)
        /** the game's popup wording: whole MINUTES rounded up from 60 s on, then SECONDS in the last minute (UiKit.notice) */
        fun minutes(now: Long): Int = left(now).let { t -> if (t >= 60) (t + 59) / 60 else 0 }
        fun seconds(now: Long): Int = left(now).let { t -> if (t >= 60) 0 else t }
        /** the last minute: the seconds turn the game's error red (the game's own words change to seconds then) */
        fun urgent(now: Long): Boolean = urgent || left(now) < 60
    }

    /** one `notice` document onto the notice on show ([cur]); null = none any more */
    fun apply(cur: Notice?, msg: JSONObject, now: Long): Notice? {
        if (msg.optString("type") != "notice") return cur
        val id = msg.optString("id")
        if (msg.optBoolean("ended")) return if (cur == null || cur.id == id || id.isEmpty()) null else cur
        if (!msg.has("left")) return cur
        return Notice(id, msg.optString("kind", "shard"), msg.optString("text", ""), msg.optInt("left", 0).coerceAtLeast(0), now,
            msg.optBoolean("urgent", false), now)
    }

    /** the notice still on show at [now] (it goes STALE_MS after the last document) */
    fun live(cur: Notice?, now: Long): Notice? = cur?.takeIf { now - it.seenAt < STALE_MS }
}
