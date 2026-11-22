package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the six arrangements the matrices cover, frozen from 1517's presets (checked equal to HomePresets' own over every base before the
 * presets went, commit "freeze 1517's six preset arrangements"): their fields on the matrices' base, literally
 */
class HomeArrangementsTest {
    @Test fun theFrozenArrangementsAreThePresetsOf1517() {
        val b = HomeArrangements.BASE
        val want = listOf(
            HomeArrangements.CLASSIC to b,
            HomeArrangements.MIRRORED to b.copy(name = HomeArrangements.MIRRORED, chatSide = HomeLayout.Side.RIGHT),
            HomeArrangements.BIG_MAP to b.copy(name = HomeArrangements.BIG_MAP, chatShare = HomeLayout.Share.THIRD,
                card = HomeLayout.CardDock.ABOVE_CHAT, cast = HomeLayout.CastDock.UNDER_MAP),
            HomeArrangements.BIG_CHAT to b.copy(name = HomeArrangements.BIG_CHAT, chatShare = HomeLayout.Share.TWO_THIRDS),
            HomeArrangements.COMPACT to b.copy(name = HomeArrangements.COMPACT, rest = HomeRest.ROW, cast = HomeLayout.CastDock.ACROSS,
                castSize = HomeLayout.SlotSize.M),
            HomeArrangements.FIGHTER to b.copy(name = HomeArrangements.FIGHTER, fight = HomeLayout.FightBand.TOP, cast = HomeLayout.CastDock.SPLIT),
        )
        assertEquals(want, HomeArrangements.ALL)
        // Classic's places (the LB picture's 1516 spot) as 1517's Classic preset held them: Classic, and Compact (chat left at half)
        assertEquals(listOf(HomeArrangements.CLASSIC, HomeArrangements.COMPACT), HomeArrangements.ALL.filter { it.second.classicPlace }.map { it.first })
        // Classic over any of them is the way back (Settings' Reset Home to Classic)
        for ((id, l) in HomeArrangements.ALL) assertTrue(id, l.classic().classicPlace)
    }
}
