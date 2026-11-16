package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * issue #288 ("tell me how to open windows setting to change setting? i can't find it. I want to fix the error BLZ51914400"): the
 * chooser's Menu draws Windows ID beside Region on its bottom row (the row was one wide Region button: the Menu is no taller); the
 * waiting screen's Menu is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartMenuWindowsIdDrawTest {
    private fun draw(game: Boolean): List<Pair<RectF, Int>> {
        val bmp = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        val hits = ArrayList<Pair<RectF, Int>>()
        val area = RectF(40f, 150f, 1200f, 1000f)
        StartMenu.draw(Canvas(bmp), area, -1, hits, RectF(40f, 40f, 260f, 151f), game)
        return hits.toList()
    }

    @Test fun theChooserHasItBesideRegion() {
        val hits = draw(game = false)
        val win = hits.firstOrNull { it.second == StartMenu.WINID }?.first
        assertNotNull("Windows ID on the chooser's Menu", win)
        val beside = hits.filter { it.second != StartMenu.WINID && it.first.top == win!!.top }
        assertEquals("one button beside it (Region)", 1, beside.size)
        assertTrue(beside[0].first.right < win!!.left)
        // the Menu is no taller than before: the bottom row is still the last row
        assertEquals(hits.maxOf { it.first.bottom }, win.bottom)
    }

    @Test fun theWaitingScreenIsAsBefore() {
        val hits = draw(game = true)
        assertNull(hits.firstOrNull { it.second == StartMenu.WINID })
    }
}
