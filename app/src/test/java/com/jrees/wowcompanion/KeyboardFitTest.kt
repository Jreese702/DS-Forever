package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * #557 (the Ayaneo Pocket DS, a 1024 x 768 bottom screen: "The single most oversized element is the popup keyboard its so large it
 * doesn't fit on the screen"): the companion's keyboard took the Thor's pixels (92 px keys, a 528 px board) on every screen, and in the
 * Chat room it never drew its keys shorter than 92 px however little room it had - the Space / Send row ran off the screen. Now the
 * board scales to the screen it is on (the kit's rule, min(1, w / 1240, h / 1080)) and its keys never outgrow the board. The Thor's
 * board is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardFitTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val rows = ChatKeys.Layout.DEFAULT.pages[0].size

    @Test fun theScaleIsTheKitsRule() {
        assertEquals(1f, ChatKeyboard.scaleFor(1240f, 1080f), 1e-6f)              // the Thor
        assertEquals(1f, ChatKeyboard.scaleFor(1920f, 1200f), 1e-6f)              // a bigger screen: never larger
        assertEquals(768f / 1080f, ChatKeyboard.scaleFor(1024f, 768f), 1e-6f)     // the Pocket DS: its height decides
        assertEquals(1f, ChatKeyboard.scaleFor(0f, 0f), 1e-6f)                    // not known yet
        assertEquals(ChatKeyboard.MIN_SCALE, ChatKeyboard.scaleFor(100f, 100f), 1e-6f)
    }

    @Test fun theThorsBoardIsUnchanged() {
        assertEquals(528, ChatKeyboard.fullHeight())
        val g = ChatKeyboard.keyMetrics(1228f, 524f, rows, 1f)
        assertEquals(92f, g[1], 1e-3f); assertEquals(16f, g[2], 1e-6f); assertEquals(8f, g[3], 1e-6f)
        // the Chat room's input screen gives it the room: taller keys, as before
        assertTrue(ChatKeyboard.keyMetrics(1228f, 690f, rows, 1f)[1] > 110f)
    }

    @Test fun onThePocketDsTheBoardFitsAndChatsSendRowStaysOnScreen() {
        val k = ChatKeyboard.scaleFor(1024f, 768f)
        val full = ChatKeyboard.fullHeight(k)
        assertTrue("the board takes at most half the screen: $full", full <= 768 / 2)
        // the Chat room's board on a 768 px screen (about 380 px): every key row inside it, the old 92 px floor's overflow gone
        val board = ChatKeyboard(app).apply { open(); scaleOverride = k; fillHeight = 380 }
        board.measure(View.MeasureSpec.makeMeasureSpec(1024, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(380, View.MeasureSpec.EXACTLY))
        board.layout(0, 0, 1024, 380)
        board.draw(Canvas(Bitmap.createBitmap(1024, 380, Bitmap.Config.ARGB_8888)))
        val keys = places(board)
        assertTrue(keys.isNotEmpty())
        val lowest = keys.maxOf { it.bottom }
        assertTrue("the Space / Send row's foot $lowest inside the 380 px board", lowest <= 380f - 4f)
        assertEquals(rows, keys.map { it.top }.distinct().size)
    }

    @Test @Config(qualifiers = "w1024dp-h768dp-mdpi")
    fun aBoardInAWindowTakesItsScreensScale() {
        val (kb, root) = attached()
        val k = ChatKeyboard.scaleFor(root.width.toFloat(), root.height.toFloat())
        assertTrue("a 1024 x 768 window: ${root.width} x ${root.height}", k < 0.75f)
        assertEquals(k, kb.scale, 1e-6f)
        assertEquals(ChatKeyboard.fullHeight(k), kb.height)
        assertEquals(kb.height, kb.boardHeight())
        assertTrue(kb.bottom <= root.height)
    }

    @Test @Config(qualifiers = "w1240dp-h1080dp-mdpi")
    fun onTheThorsScreenTheBoardIsTheThors() {
        val (kb, _) = attached()
        assertEquals(1f, kb.scale, 1e-6f)
        assertEquals(528, kb.height)
    }

    /** a board along the bottom of a window's room, as Bags / Professions / the NPC windows put it (it measures itself) */
    private fun attached(): Pair<ChatKeyboard, View> {
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val room = FrameLayout(act)
        val kb = ChatKeyboard(act, swap = false).apply { open() }
        room.addView(kb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        act.setContentView(room)
        // a frame or two: the window's size is known, the board measured again at its scale (its draw asks for it)
        repeat(3) {
            ShadowLooper.idleMainLooper()
            val root = kb.rootView
            root.measure(View.MeasureSpec.makeMeasureSpec(root.width.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(root.height.coerceAtLeast(1), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.width, root.height)
            kb.draw(Canvas(Bitmap.createBitmap(maxOf(1, kb.width), maxOf(1, kb.height), Bitmap.Config.ARGB_8888)))
        }
        return kb to kb.rootView
    }

    @Suppress("UNCHECKED_CAST")
    private fun places(kb: ChatKeyboard): List<RectF> =
        (ChatKeyboard::class.java.getDeclaredField("places").apply { isAccessible = true }.get(kb) as List<Triple<Int, Int, RectF>>).map { it.third }
}
