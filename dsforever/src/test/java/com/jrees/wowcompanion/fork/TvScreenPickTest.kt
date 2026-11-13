package com.jrees.wowcompanion.fork

import com.jrees.wowcompanion.fork.DsForeverScreen.Companion.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** issue #196: a TV plugged into the Thor took DS Forever's bottom screen. Which display the Presentation goes on, off-device. */
class TvScreenPickTest {
    private val TOP = 0
    private val bottom = Screen(4, builtIn = true, name = "Built-in Screen")
    private val tv = Screen(5, builtIn = false, name = "HDMI Screen")
    private val pick = DsForeverScreen::pickScreen

    @Test fun noTvTheBottomScreen() = assertEquals(4, pick(listOf(bottom), TOP, null))

    // Android 12+ lists EXTERNAL presentation displays before the built-in ones: the old firstOrNull took the TV
    @Test fun tvListedFirstAtStartStillTheBottomScreen() = assertEquals(4, pick(listOf(tv, bottom), TOP, null))

    @Test fun tvPluggedInLaterNeverTakesItAway() = assertEquals(4, pick(listOf(tv, bottom), TOP, 4))

    @Test fun typeUnknownBeatsExternal() =
        assertEquals(4, pick(listOf(tv, bottom.copy(builtIn = null)), TOP, null))

    @Test fun neverTheGamesDisplay() = assertEquals(4, pick(listOf(Screen(0, true, "top"), tv, bottom), TOP, null))

    @Test fun onlyATvLeftGoesThere() = assertEquals(5, pick(listOf(tv), TOP, 4))

    @Test fun nothingButTheGame() = assertNull(pick(listOf(Screen(0, true, "top")), TOP, null))

    @Test fun twoBuiltInLowestIdFirst() = assertEquals(3, pick(listOf(bottom, bottom.copy(id = 3)), TOP, null))
}
