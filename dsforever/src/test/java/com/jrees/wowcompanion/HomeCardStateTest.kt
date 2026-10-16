package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Test

/** p-card-invisible, off-device: the WCHOME "quest card:" line's reason, in the order a log read needs it */
class HomeCardStateTest {
    private fun why(q: Int = 6, card: Boolean = true, aura: Boolean = false, vis: Boolean = true, fly: Boolean = false,
                    a: Float = 1f, h: Int = 120, under: Boolean = false, screen: Boolean = true) =
        HomeCardState.why(q, card, aura, vis, fly, a, h, under, screen)

    @Test fun shown() = assertEquals("shown", why())
    @Test fun noFocus() = assertEquals("no focused quest", why(q = 0, card = false))
    @Test fun focusWithoutAQuest() = assertEquals("focus 6 has no quest in the log", why(card = false))
    @Test fun auraListWins() = assertEquals("buff / debuff list open", why(aura = true, vis = false))
    @Test fun theBugsState() = assertEquals("pane not laid out", why(vis = false))      // a card the layout never got (the fix re-lays it)
    @Test fun flying() = assertEquals("flying (map open / close)", why(fly = true))
    @Test fun faded() = assertEquals("faded", why(a = 0f))
    @Test fun zero() = assertEquals("zero height", why(h = 0))
    @Test fun underTheMap() = assertEquals("under the map (depth)", why(under = true))   // 1.0.9-test1's state: z 0 under the map
    @Test fun clipped() = assertEquals("clipped off screen", why(screen = false))
}
