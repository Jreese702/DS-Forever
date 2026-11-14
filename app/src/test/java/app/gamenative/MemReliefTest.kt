package app.gamenative

import android.content.ComponentCallbacks2
import com.jrees.wowcompanion.MemWatch
import com.jrees.wowcompanion.PreviewStage
import com.jrees.wowcompanion.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue triage 2026-11-14, the 1.0.29 - 1.0.31 low-memory cluster (#236 - #276; the user, 2026-11-13: "yes on the triage things"):
 * the loading pictures and the Settings miniatures are let go when nothing shows them (MemWatch's own parts: dsforever MemReliefTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemReliefTest {

    @Test fun theLoadingPicturesGoOnlyWhenNoneWasAskedForAWhile() {
        val mb5 = 5L shl 20
        assertFalse(Scene.mayGo(20_000L, 19_000L, 8 * mb5, Scene.IDLE_MS))          // the start screen draws them: kept
        assertFalse(Scene.mayGo(20_000L, 5_001L, 8 * mb5, Scene.IDLE_MS))
        assertTrue(Scene.mayGo(20_000L, 5_000L, 8 * mb5, Scene.IDLE_MS))            // 15 s with no loading page: they go
        assertFalse(Scene.mayGo(90_000L, 5_000L, 0L, Scene.IDLE_MS))                // none held: nothing to do
        assertTrue(Scene.mayGo(20_000L, 18_000L, mb5, Scene.TRIM_IDLE_MS))         // a trim or a relief: 2 s are enough
        assertFalse(Scene.mayGo(20_000L, 18_500L, mb5, Scene.TRIM_IDLE_MS))
        assertEquals(0L, Scene.held)                                                 // nothing decoded in a test
        assertEquals(0L, Scene.letGo())
    }

    @Test fun aLoadingPictureMissingFromTheKitIsNullAndHoldsNothing() {
        assertEquals(null, Scene.pic(Scene.all[0]))                                  // no kit on this machine: the plain dark ground
        assertEquals(0L, Scene.held)
    }

    @Test fun theSettingsMiniaturesGoUnlessSettingsIsOnShow() {
        assertTrue(PreviewStage.cacheLetsGo(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, "home"))
        assertTrue(PreviewStage.cacheLetsGo(MemWatch.RELIEF_LEVEL, "map"))
        assertTrue(PreviewStage.cacheLetsGo(MemWatch.LOW_MEMORY, "bags"))
        assertFalse(PreviewStage.cacheLetsGo(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, "settings"))   // on show: kept
        assertFalse(PreviewStage.cacheLetsGo(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, "home"))
    }
}
