package com.jrees.wowcompanion

/**
 * THE APP'S FOCUS IS THE GAME'S (the user, 2026-10-19: "these should be one in the same? I have no way of focusing quests in the
 * game, so the DS forever should be synced and force the game to follow what ever it has focused"; an explicit exception to
 * leave-no-trace, approved by the user). The default focus ([MapView]'s nearest tracked quest, shown while the game has none) is made
 * the game's focus too, so the game's arrow and the route's pin follow it. Pure: whether to send the focus for the default now.
 *
 * Never fights the player: a quest whose push the game TOOK (it came back as the game's focus) is not pushed again this session,
 * so a focus the player then clears (Unfocus, or in the game) stays cleared for that quest; one the game never took (refused in a
 * fight, a busy pad) is tried again after [RETRY_MS].
 */
class DefaultFocusPush {
    private val took = HashSet<Int>()
    private val sentAt = HashMap<Int, Long>()

    /** [defaultQid] the shown default (0: none), [gameQid] the game's own focus (0: none), at [nowMs]: the quest to focus, or 0 */
    fun tick(defaultQid: Int, gameQid: Int, nowMs: Long): Int {
        if (gameQid > 0 && sentAt.containsKey(gameQid)) took.add(gameQid)
        if (gameQid != 0 || defaultQid <= 0 || defaultQid in took) return 0
        val at = sentAt[defaultQid]
        if (at != null && nowMs - at < RETRY_MS) return 0
        sentAt[defaultQid] = nowMs
        return defaultQid
    }

    companion object {
        const val RETRY_MS = 30_000L
    }
}
