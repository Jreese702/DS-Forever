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

    private val locale = java.util.Locale.getDefault()

    // no game folder in the test, so the region in effect with no choice made is the device's country's: a US device
    @org.junit.Before fun setUp() { java.util.Locale.setDefault(java.util.Locale.US) }

    @After fun tearDown() {
        ctx.getSharedPreferences("dsf_region", 0).edit().clear().commit()
        GameRegion.listeners.clear(); java.util.Locale.setDefault(locale)
    }

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

    // reviewer, 2026-11-17: a first-time player saving the login prompt's pre-filled row (none chosen -> us, us in effect) was kept as a
    // switch "none -> us", so a normal patch read "Switching to US" with Stop
    @Test fun aFirstTimePromptSaveIsNotASwitch() {
        assertEquals("us", GameRegion.current(ctx))
        GameRegion.choose(ctx, "us", GameRegion.BY_PROMPT_PREFILLED)
        assertEquals("us", GameRegion.chosen(ctx))
        assertNull(GameRegion.pending(ctx))
        assertNull(GameRegion.nextPending(null, null, "us", GameRegion.BY_PROMPT_PREFILLED, inEffect = "us"))
    }

    // ... and the launcher's listener took that fake switch as one on purpose: autoUpdatedFor = null restarted a stopped build
    @Test fun theLiveListenerIgnoresANoOpSave() {
        val heard = ArrayList<Pair<String, GameRegion.Change?>>()
        GameRegion.listeners.add { code -> heard += code to GameRegion.switchFor(ctx, code) }
        GameRegion.choose(ctx, "us", GameRegion.BY_PROMPT_PREFILLED)
        assertEquals(listOf("us" to null), heard)
    }

    @Test fun aRealUsToEuSwitchStillOffersStop() {
        val heard = ArrayList<Pair<String, GameRegion.Change?>>()
        GameRegion.choose(ctx, "us", GameRegion.BY_PROMPT_PREFILLED)
        GameRegion.listeners.add { code -> heard += code to GameRegion.switchFor(ctx, code) }
        GameRegion.choose(ctx, "eu", GameRegion.BY_MENU)
        assertEquals(listOf("eu" to GameRegion.Change("us", "eu", GameRegion.BY_MENU)), heard)
        // with no choice made, a pick other than the region in effect is a switch too; Stop forgets the choice again
        ctx.getSharedPreferences("dsf_region", 0).edit().clear().commit()
        GameRegion.choose(ctx, "eu", GameRegion.BY_MENU)
        assertEquals(GameRegion.Change(null, "eu", GameRegion.BY_MENU), GameRegion.pending(ctx))
    }
}
