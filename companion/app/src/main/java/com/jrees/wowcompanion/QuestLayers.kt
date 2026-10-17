package com.jrees.wowcompanion

/**
 * THE QUEST LAYERS OF THE MAP (the user, 2026-10-17: "add the questie quest information to our map so that I can see where my
 * objectives are as well as what quests are available where on the map just like it is on normal"; their picks: Q1 - every layer,
 * each switchable in the Map Filter's Quests tab, the defaults as drawn in the mock, and Home's small map as in the mock). Pure: the
 * switches, their defaults, and the small map's rule.
 */
class QuestLayers(private val get: (String, Boolean) -> Boolean, private val put: (String, Boolean) -> Unit) {
    enum class Layer(val key: String, val label: String, val sub: String, val default: Boolean) {
        AVAILABLE("q_avail", "Available quests", "givers you can take now (QuestieDB: level, class, race, quest chains)", true),
        LOW("q_low", "Low-level quests", "the grey ones, dimmed", false),
        TRACKED("q_tracked", "Tracked quests' objectives", "the game's own areas, QuestieDB where it has none", true),
        OTHER("q_other", "Other quests' objectives", "every quest in the log, dimmer", true),
        TURN_INS("q_turnins", "Turn-ins", "every quest ready to hand in", true),
        DAILY("q_daily", "Daily & repeatable", "", true),
        SMALL("q_small", "Small map: quests nearby", "Home's small map: the ! within 200 yd, the focused quest's objectives", true),
        // b-danger-layer (the user's pick: the tint only with this filter, off by default)
        DANGER("q_danger", "Danger areas", "hostile mobs at or above your level, tinted red (QuestieDB)", false),
    }

    fun on(l: Layer): Boolean = get(l.key, l.default)
    fun toggle(l: Layer) = put(l.key, !on(l))

    companion object {
        /** Home's small map shows a "!" only this near (yards) */
        const val SMALL_NEAR_YD = 200.0

        /**
         * On Home's small map ([compact]): a "!" only within SMALL_NEAR_YD and only while the SMALL switch is on; another quest's
         * objective and an untracked quest's turn-in not at all (the mock: the nearby "!" and the focused quest only; the tracked quests'
         * numbered bubbles stay, the user's 2026-10-02 ruling). On the open map: everything its own switch allows.
         */
        fun smallMapShows(compact: Boolean, small: Boolean, kind: Kind, yards: Double): Boolean = when {
            !compact -> true
            kind == Kind.AVAIL -> small && yards <= SMALL_NEAR_YD
            kind == Kind.OTHER_OBJECTIVE || kind == Kind.OTHER_TURN_IN -> false
            else -> true
        }
    }

    enum class Kind { AVAIL, OTHER_OBJECTIVE, OTHER_TURN_IN, TRACKED }
}
