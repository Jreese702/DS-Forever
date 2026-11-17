package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Verifier, 2026-11-17: a region picked in the game screen's Menu (the launcher gone while the game runs) reached no listener, so its
 * download started later with no Stop. The switch is kept on disk until the launcher settles it ([GameRegion.pending] / [GameRegion.settle]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RegionPendingTest {
    private val ctx = RuntimeEnvironment.getApplication()

    @After fun tearDown() { ctx.getSharedPreferences("dsf_region", 0).edit().clear().commit() }

    @Test fun aMenuSwitchIsKeptForALauncherThatStartsLater() {
        GameRegion.choose(ctx, "us", GameRegion.BY_PROMPT); GameRegion.settle(ctx)
        GameRegion.choose(ctx, "eu", GameRegion.BY_MENU)
        // no listener heard it; a launcher composed now reads it back
        assertEquals(GameRegion.Change("us", "eu", GameRegion.BY_MENU), GameRegion.pending(ctx))
        GameRegion.settle(ctx)
        assertNull(GameRegion.pending(ctx))
    }

    @Test fun stopGoesBackToTheFirstRegionAndKeepsNoSwitch() {
        GameRegion.choose(ctx, "us", GameRegion.BY_PROMPT); GameRegion.settle(ctx)
        GameRegion.choose(ctx, "eu", GameRegion.BY_MENU)
        GameRegion.choose(ctx, "kr", GameRegion.BY_MENU)
        assertEquals("us", GameRegion.pending(ctx)?.from)
        GameRegion.restore(ctx, GameRegion.pending(ctx)?.from)
        assertEquals("us", GameRegion.chosen(ctx))
        assertNull(GameRegion.pending(ctx))
    }

    @Test fun noChoiceBeforeIsForgottenAgainOnStop() {
        GameRegion.choose(ctx, "eu", GameRegion.BY_MENU)
        assertEquals(GameRegion.Change(null, "eu", GameRegion.BY_MENU), GameRegion.pending(ctx))
        GameRegion.restore(ctx, null)
        assertNull(GameRegion.chosen(ctx)); assertNull(GameRegion.pending(ctx))
    }

    @Test fun theNextPendingRules() {
        assertEquals(GameRegion.Change("us", "eu", ""), GameRegion.nextPending(null, "us", "eu", GameRegion.BY_MENU))
        val first = GameRegion.Change("us", "eu", "")
        assertEquals(GameRegion.Change("us", "kr", ""), GameRegion.nextPending(first, "eu", "kr", GameRegion.BY_MENU))
        assertNull(GameRegion.nextPending(first, "eu", "us", GameRegion.BY_MENU))       // back to the first: no switch
        assertNull(GameRegion.nextPending(first, "eu", "us", GameRegion.BY_STOP))
    }
}
