package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** f-loot-feedback: the loot sparkle's and the refused shake's timings (off-device) */
class LootFxTest {
    @Test fun theFlashIsFirstAndShort() {
        assertEquals(255, LootFx.flashAlpha(0))
        assertTrue(LootFx.flashAlpha(LootFx.FLASH_MS / 2) in 100..160)
        assertEquals(0, LootFx.flashAlpha(LootFx.FLASH_MS))
    }

    @Test fun theCardFadesAndShrinksThenIsGone() {
        assertEquals(1f, LootFx.cardAlpha(0), 0.001f)
        assertEquals(0f, LootFx.cardAlpha(LootFx.CARD_MS), 0.001f)
        assertTrue(LootFx.cardScale(LootFx.CARD_MS) < LootFx.cardScale(0))
    }

    @Test fun theFlakesStartInTurnRiseAndEndWithTheSparkle() {
        assertNull(LootFx.piece(3, 2, 0))                                  // not started yet
        val mid = LootFx.piece(0, 2, LootFx.VANISH_MS / 2)
        assertNotNull(mid)
        assertTrue("they rise", mid!!.dy < 0f)
        assertNull(LootFx.piece(0, 2, LootFx.VANISH_MS))                   // all gone at the end
        for (k in 0 until LootFx.N) assertNull(LootFx.piece(k, 2, LootFx.VANISH_MS + 1))
        // two slots never sparkle alike
        assertNotEquals(LootFx.piece(1, 2, 300)!!.dx, LootFx.piece(1, 3, 300)!!.dx, 0.01f)
    }

    @Test fun aRefusedLootShakesAndSettles() {
        assertTrue(LootFx.shakeTint(0) > 0)
        assertEquals(0f, LootFx.shake(LootFx.SHAKE_MS), 0.001f)
        assertEquals(0, LootFx.shakeTint(LootFx.SHAKE_MS))
        assertTrue((0L until LootFx.SHAKE_MS step 20).any { kotlin.math.abs(LootFx.shake(it)) > 5f })
    }
}
