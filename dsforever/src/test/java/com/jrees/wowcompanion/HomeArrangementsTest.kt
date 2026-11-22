package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Test

/** the frozen arrangements equal 1517's presets exactly (==, the name too) over every base the tests lay them on */
class HomeArrangementsTest {
    @Test fun theFrozenArrangementsAre1517sPresets() {
        val bases = ArrayList<HomeLayout>()
        for (c in HomeLayout.CastDock.values()) for (s in HomeLayout.Side.values()) for (f in HomeLayout.FightBand.values())
            for (r in HomeRest.values()) bases += HomeLayout(cast = c, chatSide = s, fight = f, rest = r, chatShare = HomeLayout.Share.THIRD)
        bases += HomeLayout()
        for (b in bases) for (p in HomePresets.ALL) assertEquals("${p.id} on $b", p.apply(b), HomeArrangements.of(p.id, b))
        assertEquals(HomePresets.ALL.map { it.id }, HomeArrangements.IDS)
    }
}
