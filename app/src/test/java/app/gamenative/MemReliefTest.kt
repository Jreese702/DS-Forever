package app.gamenative

import android.content.ComponentCallbacks2
import com.jrees.wowcompanion.ArtHarvest
import com.jrees.wowcompanion.MapRoomLetGo
import com.jrees.wowcompanion.MemWatch
import com.jrees.wowcompanion.PreviewStage
import com.jrees.wowcompanion.Scene
import com.jrees.wowcompanion.WorldMapRoomView
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

    /**
     * MEMORY RELIEF 2 (the user's picks, 2026-11-14: "FREE MAP PICTURES: the World map room's pictures (43-72 MB) are released about a
     * minute after the player leaves the map room (not only at Android's warning); reopening decodes again"): the real room, attached,
     * with one picture of a made-up art set decoded the way a draw asks for it.
     */
    @Test fun theMapRoomsPicturesGoAMinuteAfterItIsLeftAndDecodeAgain() {
        val root = java.nio.file.Files.createTempDirectory("art").toFile()
        java.io.File(root, "worldmap/1429").mkdirs()
        val pic = android.graphics.Bitmap.createBitmap(64, 32, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF8A6A3C.toInt()) }
        java.io.File(root, "worldmap/1429/base.webp").outputStream().use { pic.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val f = ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }
        val was = f.get(null)
        f.set(null, root)
        try {
            val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
            val room = WorldMapRoomView(act)
            act.setContentView(room)
            fun decoded(): Boolean {                                     // the room's own decode (its io thread, then the main thread)
                val until = System.currentTimeMillis() + 5_000L
                while (System.currentTimeMillis() < until) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    if (room.pictureBytes > 0L) return true
                    Thread.sleep(10)
                }
                return false
            }
            room.onShown()
            assertEquals(null, room.bmp("1429/base"))                    // asked for: decoded off the UI thread
            assertTrue("the picture decoded", decoded())
            val held = room.pictureBytes
            val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())

            room.onHidden()                                              // left (any route: the host's show())
            looper.idleFor(java.time.Duration.ofMillis(MapRoomLetGo.AFTER_MS - 1_000L))
            assertEquals("kept for the minute", held, room.pictureBytes)
            room.onShown(); room.onHidden()                              // back within it, and left again: the minute starts over
            looper.idleFor(java.time.Duration.ofMillis(MapRoomLetGo.AFTER_MS - 1_000L))
            assertEquals(held, room.pictureBytes)
            looper.idleFor(java.time.Duration.ofMillis(2_000L))
            assertEquals("a minute after it was left: let go", 0L, room.pictureBytes)

            room.onShown()                                               // reopened: the draw asks again and it decodes again
            assertEquals(null, room.bmp("1429/base"))
            assertTrue("decoded again", decoded())
            assertTrue(room.bmp("1429/base") != null)

            looper.idleFor(java.time.Duration.ofMillis(3 * MapRoomLetGo.AFTER_MS))
            assertEquals("on show: never let go by the minute", held, room.pictureBytes)
        } finally { f.set(null, was) }
    }

    @Test fun theSettingsMiniaturesGoUnlessSettingsIsOnShow() {
        assertTrue(PreviewStage.cacheLetsGo(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, "home"))
        assertTrue(PreviewStage.cacheLetsGo(MemWatch.RELIEF_LEVEL, "map"))
        assertTrue(PreviewStage.cacheLetsGo(MemWatch.LOW_MEMORY, "bags"))
        assertFalse(PreviewStage.cacheLetsGo(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, "settings"))   // on show: kept
        assertFalse(PreviewStage.cacheLetsGo(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, "home"))
    }
}
