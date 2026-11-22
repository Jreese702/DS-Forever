package com.jrees.wowcompanion

/**
 * THE SIX ARRANGEMENTS THE TESTS COVER (test build 1518, the user's ruling 2026-11-21: "LAYOUTS: REMOVE EVERYWHERE"): Home has no
 * presets any more, but the matrices (menus fit, the motion, the solver) keep covering the very arrangements 1517's presets made -
 * frozen here as data. [on] lays each over a record exactly as 1517's preset did (its own fields set, the rest kept; Classic also
 * undoing the others' fields), named as the preset named it, so every record is equal (==, the name too) to what the tests used.
 * A copy lives in each test module (dsforever and app have no shared test source).
 */
object HomeArrangements {
    const val CLASSIC = "Classic"
    const val MIRRORED = "Mirrored"
    const val BIG_MAP = "Big map"
    const val BIG_CHAT = "Big chat"
    const val COMPACT = "Compact"
    const val FIGHTER = "Fighter"
    val IDS = listOf(CLASSIC, MIRRORED, BIG_MAP, BIG_CHAT, COMPACT, FIGHTER)

    private val L = HomeLayout.Side.LEFT
    private val R = HomeLayout.Side.RIGHT

    /** arrangement [id] laid over [b] */
    fun of(id: String, b: HomeLayout): HomeLayout = when (id) {
        CLASSIC -> b.copy(name = id, chatSide = L, chatShare = HomeLayout.Share.HALF, card = HomeLayout.CardDock.ON_MAP,
            rest = HomeRest.DIAMONDS, castSize = HomeLayout.SlotSize.L,
            cast = if (b.cast == HomeLayout.CastDock.SPLIT) HomeLayout.CastDock.UNDER_CHAT else b.cast.withTop(false),
            fight = if (b.fight == HomeLayout.FightBand.TOP) HomeLayout.FightBand.GROW else b.fight)
        MIRRORED -> b.copy(name = id, chatSide = R, chatShare = HomeLayout.Share.HALF, card = HomeLayout.CardDock.ON_MAP,
            cast = HomeLayout.CastDock.UNDER_CHAT)
        BIG_MAP -> b.copy(name = id, chatSide = L, chatShare = HomeLayout.Share.THIRD, card = HomeLayout.CardDock.ABOVE_CHAT,
            cast = HomeLayout.CastDock.UNDER_MAP)
        BIG_CHAT -> b.copy(name = id, chatSide = L, chatShare = HomeLayout.Share.TWO_THIRDS, card = HomeLayout.CardDock.ON_MAP,
            cast = HomeLayout.CastDock.UNDER_CHAT)
        COMPACT -> b.copy(name = id, rest = HomeRest.ROW, cast = HomeLayout.CastDock.ACROSS, castSize = HomeLayout.SlotSize.M)
        FIGHTER -> b.copy(name = id, fight = HomeLayout.FightBand.TOP, cast = HomeLayout.CastDock.SPLIT)
        else -> error("no arrangement $id")
    }

    /** all six over [b], in 1517's preset order (id to record) */
    fun on(b: HomeLayout): List<Pair<String, HomeLayout>> = IDS.map { it to of(it, b) }

    /** the matrices' base: the bar under the chat */
    val BASE = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
    val ALL: List<Pair<String, HomeLayout>> get() = on(BASE)
}
