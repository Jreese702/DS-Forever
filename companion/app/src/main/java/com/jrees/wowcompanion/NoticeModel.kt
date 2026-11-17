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
    data class Notice(val id: String, val kind: String, val text: String, val left: Int, val at: Long, val urgent: Boolean, val seenAt: Long,
                      val since: Long = at) {
        /** chat42: ms since this notice first showed ([since]: kept across its own updates) - its flash (UiKit.noticeFlash) */
        fun flashMs(now: Long): Long = now - since
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
        // chat42: the flash once, when the notice is new; its 10 s updates (and a /reload's new id while it shows) keep the first time
        return Notice(id, msg.optString("kind", "shard"), msg.optString("text", ""), msg.optInt("left", 0).coerceAtLeast(0), now,
            msg.optBoolean("urgent", false), now, since = cur?.since ?: now)
    }

    /** the notice still on show at [now] (it goes STALE_MS after the last document) */
    fun live(cur: Notice?, now: Long): Notice? = cur?.takeIf { now - it.seenAt < STALE_MS }

    // chat42 (the user, 2026-11-16: "There is a little white flash animation that happens whenever someone comes online in battlenet.
    // We need to hit that on the top screen and make something similar on the bottom"): each notice flashes white once as it comes (the
    // game's toast glow, UI-Toast-Flair: 0.2 s up, 0.5 s down; ours UiKit.NOTICE_FLASH_MS), timed from [Bnet.at] / [Notice.since].

    // ---------------------------------------------------------------- chat40: THE BATTLE.NET FRIEND NOTICES (the user, 2026-11-16: "the
    // Bnet friends coming online pop ups should appear over my chat similar to the world refreesh prompt"). Chat's {kind "bnet", id,
    // who, on} as the game's toast lives: 4 s, then a 1.5 s fade (SocialToastAnimOutTemplate); the newest first, at most [BNET_MAX].

    const val BNET_HOLD_MS = 4_000L
    const val BNET_FADE_MS = 1_500L
    const val BNET_MAX = 2

    // chat41 (the user's pick, 2026-11-16: "Yes, all of them"): every other Battle.net toast the game shows comes the same way, its [t]
    // saying which: "invite" (a new friend request), "pending" ([n] requests waiting, at the login), "bcast" (a friend's broadcast: [who]
    // and its [text] when the game gives it plain, else none: "posted a broadcast"), "club" (a community invite: [name] when plain). Each
    // lives the game's own toast time ([holdMs]: Chat's `hold` = its toastDuration setting, seconds; none = the game's 4 s). Information
    // only: nothing is accepted or declined here.
    const val T_ON = "on"
    const val T_OFF = "off"
    const val T_INVITE = "invite"
    const val T_PENDING = "pending"
    const val T_BCAST = "bcast"
    const val T_CLUB = "club"
    private val KNOWN = setOf(T_ON, T_OFF, T_INVITE, T_PENDING, T_BCAST, T_CLUB)

    /** one Battle.net toast: [t] its kind ([online] for "on"); [who] = the character or the BattleTag's name, null = none readable ("A
     *  friend"); [text] a broadcast's words, [name] a community's, [n] the pending count */
    data class Bnet(val id: String, val who: String?, val online: Boolean, val at: Long, val t: String = if (online) T_ON else T_OFF,
                    val text: String? = null, val name: String? = null, val n: Int = 0, val holdMs: Long = BNET_HOLD_MS) {
        /** chat42: ms since it came - its one flash (UiKit.noticeFlash); a notice comes once per id, so it flashes once */
        fun flashMs(now: Long): Long = now - at
        /** 1 while it holds, down to 0 over the fade; 0 = gone */
        fun alpha(now: Long): Float {
            val t = now - at
            return when {
                t < holdMs -> 1f
                t < holdMs + BNET_FADE_MS -> 1f - (t - holdMs).toFloat() / BNET_FADE_MS
                else -> 0f
            }
        }
    }

    /** a player-written value from Chat: plain, never a protected |K string; blank = none */
    private fun plain(msg: JSONObject, key: String): String? =
        (if (msg.isNull(key)) "" else msg.optString(key, "")).takeIf { it.isNotBlank() && !it.contains("|K") }

    /** one `notice` document onto the BN notices on show ([cur], newest first): a new one goes on top, the oldest past [BNET_MAX] goes */
    fun applyBnet(cur: List<Bnet>, msg: JSONObject, now: Long): List<Bnet> {
        if (msg.optString("type") != "notice" || msg.optString("kind") != "bnet") return cur
        val id = msg.optString("id")
        if (id.isEmpty() || cur.any { it.id == id }) return cur
        val online = msg.optBoolean("on", true)
        // chat40 documents have no t: on / off from `on`; a kind this build does not know is not drawn
        val t = msg.optString("t", "").ifEmpty { if (online) T_ON else T_OFF }
        if (t !in KNOWN) return cur
        val hold = msg.optDouble("hold", Double.NaN)
        val holdMs = if (hold.isNaN() || hold <= 0.0) BNET_HOLD_MS else (hold * 1000.0).toLong().coerceIn(1_000L, 30_000L)
        val b = Bnet(id, plain(msg, "who"), t == T_ON, now, t, plain(msg, "text"), plain(msg, "name"),
            msg.optInt("n", 0).coerceAtLeast(0), holdMs)
        return (listOf(b) + cur).take(BNET_MAX)
    }

    /** the BN notices still on show at [now] */
    fun liveBnet(cur: List<Bnet>, now: Long): List<Bnet> = cur.filter { it.alpha(now) > 0f }

    /** the refresh press's answer, or null when [msg] is not one: true = the game took it */
    fun goAnswer(msg: JSONObject): Boolean? =
        if (msg.optString("type") == "notice" && msg.optString("kind", "shard") == "shard" && msg.optString("op") == "go") msg.optBoolean("ok")
        else null
}
