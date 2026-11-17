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

    /** one `notice` document onto the notice on show ([cur]); null = none any more. chat40: a Battle.net document ([bnet]) and the
     *  refresh press's answer (`op` "go", [goAnswer]) never touch the refresh notice */
    fun apply(cur: Notice?, msg: JSONObject, now: Long): Notice? {
        if (msg.optString("type") != "notice") return cur
        if (msg.optString("kind", "shard") != "shard" || msg.has("op")) return cur
        val id = msg.optString("id")
        if (msg.optBoolean("ended")) return if (cur == null || cur.id == id || id.isEmpty()) null else cur
        if (!msg.has("left")) return cur
        return Notice(id, msg.optString("kind", "shard"), msg.optString("text", ""), msg.optInt("left", 0).coerceAtLeast(0), now,
            msg.optBoolean("urgent", false), now)
    }

    /** the notice still on show at [now] (it goes STALE_MS after the last document) */
    fun live(cur: Notice?, now: Long): Notice? = cur?.takeIf { now - it.seenAt < STALE_MS }

    // ---------------------------------------------------------------- chat40: THE BATTLE.NET FRIEND NOTICES (the user, 2026-11-16: "the
    // Bnet friends coming online pop ups should appear over my chat similar to the world refreesh prompt"). Chat's {kind "bnet", id,
    // who, on} as the game's toast lives: 4 s, then a 1.5 s fade (SocialToastAnimOutTemplate); the newest first, at most [BNET_MAX].

    const val BNET_HOLD_MS = 4_000L
    const val BNET_FADE_MS = 1_500L
    const val BNET_MAX = 2

    /** one friend's login ([online]) or logout; [who] = the character or the BattleTag's name, null = none readable ("A friend") */
    data class Bnet(val id: String, val who: String?, val online: Boolean, val at: Long) {
        /** 1 while it holds, down to 0 over the fade; 0 = gone */
        fun alpha(now: Long): Float {
            val t = now - at
            return when {
                t < BNET_HOLD_MS -> 1f
                t < BNET_HOLD_MS + BNET_FADE_MS -> 1f - (t - BNET_HOLD_MS).toFloat() / BNET_FADE_MS
                else -> 0f
            }
        }
    }

    /** one `notice` document onto the BN notices on show ([cur], newest first): a new one goes on top, the oldest past [BNET_MAX] goes */
    fun applyBnet(cur: List<Bnet>, msg: JSONObject, now: Long): List<Bnet> {
        if (msg.optString("type") != "notice" || msg.optString("kind") != "bnet") return cur
        val id = msg.optString("id")
        if (id.isEmpty() || cur.any { it.id == id }) return cur
        val who = msg.optString("who", "").takeIf { it.isNotBlank() && !it.contains("|K") }
        return (listOf(Bnet(id, who, msg.optBoolean("on", true), now)) + cur).take(BNET_MAX)
    }

    /** the BN notices still on show at [now] */
    fun liveBnet(cur: List<Bnet>, now: Long): List<Bnet> = cur.filter { it.alpha(now) > 0f }

    /** the refresh press's answer, or null when [msg] is not one: true = the game took it */
    fun goAnswer(msg: JSONObject): Boolean? =
        if (msg.optString("type") == "notice" && msg.optString("kind", "shard") == "shard" && msg.optString("op") == "go") msg.optBoolean("ok")
        else null
}
