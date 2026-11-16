package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * THE DELAY AND THE DOUBLE PRESS, PROVEN (loot-pop; the user, 2026-11-16: "I have also noticed that the loot roll window is delayed and
 * buggy, sometimes making me press the button twice to move on or even just taking like 2 second after accepting an input to move on").
 *
 * The real DialogView, fed the add-on's documents on the timeline of a real roll, a finger on Need twice:
 *   t0      three rolls (1, 2, 3) shown
 *   +800    Need on roll 1           -> its cell is tapped
 *           ... and the window must show roll 2 NOW (on 4820f201 roll 1 stayed, unchanged, until the add-on's next document)
 *   +1100   the add-on's document without roll 1 (the game dropped it)
 *   +1300   Need on roll 2           -> its cell must be tapped (on 4820f201: refused "One moment" - the box dialogs' 700 ms SETTLE lock
 *                                       restarted by the roll set changing - so the player pressed again)
 * Uses only what the base's DialogView has too (update, touches, dryRun / lastSent, the drawn hits), so the same test runs against
 * 4820f201 and fails there on both points.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DialogViewRollsProofTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 1240; private val h = 1080

    private fun gameNow(v: DialogView): Long =
        DialogView::class.java.getDeclaredMethod("gameNow").apply { isAccessible = true }.invoke(v) as Long

    private fun roll(id: Int) = JSONObject().put("id", id).put("name", "Item $id").put("q", 2).put("need", true).put("greed", true)
        .put("left", 55).put("dur", 60).put("icon", 135274)
        .put("cells", JSONObject().put("need", id * 10 + 1).put("greed", id * 10 + 2).put("pass", id * 10))

    private fun doc(v: DialogView, vararg ids: Int) = JSONObject().put("type", "dialogs").put("dv", "d34").put("mode", true)
        .put("list", JSONArray()).put("touch", true).put("rolls", JSONArray().also { a -> ids.forEach { a.put(roll(it).put("sel", it == ids.first())) } })
        .put("life", JSONObject().put("col", 3).put("every", 5)).put("pad", JSONObject().put("rows", JSONObject().put("isell", 16).put("idlg", 18).put("iact", 14)))
        .put("_gt", gameNow(v))

    private fun draw(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        v.draw(Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)))
    }

    @Suppress("UNCHECKED_CAST")
    private fun hits(v: DialogView): List<Pair<RectF, String>> =
        (DialogView::class.java.getDeclaredField("hits").apply { isAccessible = true }.get(v) as ArrayList<Pair<RectF, String>>).toList()

    /** the Need button the window shows, and the roll it answers (the base's hit "roll:need:<cell>", today's "roll:<id>:need") */
    private fun need(v: DialogView): Pair<RectF, Int> {
        val hit = hits(v).last { Regex("^roll:(need:\\d+|\\d+:need)$").matches(it.second) }
        val p = hit.second.split(':')
        val id = if (p[1] == "need") p[2].toInt() / 10 else p[1].toInt()
        return hit.first to id
    }

    private fun tap(v: DialogView, r: RectF) {
        val t0 = SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, r.centerX(), r.centerY(), 0)
        v.dispatchTouchEvent(d); d.recycle()
        val u = MotionEvent.obtain(t0, t0 + 40, MotionEvent.ACTION_UP, r.centerX(), r.centerY(), 0)
        v.dispatchTouchEvent(u); u.recycle()
    }

    // both clocks: the shadowed one (the view's System.currentTimeMillis) and the real one (its game clock is java.util.Calendar's)
    private fun later(ms: Long) { ShadowSystemClock.advanceBy(Duration.ofMillis(ms)); Thread.sleep(ms); ShadowLooper.idleMainLooper() }

    /** t0 .. +800: Need on roll 1 - its cell tapped, and the window on roll 2 AT ONCE */
    private fun firstPress(): DialogView {
        Tr.init(app)
        val v = DialogView(app)
        v.dryRun = true
        v.update(doc(v, 1, 2, 3), true)
        draw(v)
        assertEquals("the window shows roll 1's Need", 1, need(v).second)
        later(800)
        tap(v, need(v).first)
        assertEquals("Need on roll 1: its cell tapped", "roll need", v.lastSent?.substringBefore(" on ")?.substringBefore(" cell"))
        draw(v)
        return v
    }

    @Test fun theDelayTheNextRollShowsAtOnce() {
        val v = firstPress()
        assertEquals("THE DELAY: the window shows the NEXT roll at once (the base kept showing roll 1 until the add-on's next document)",
            2, need(v).second)
    }

    @Test fun theDoublePressTheNextPressIsNeverLocked() {
        val v = firstPress()
        val first = v.lastSent
        later(300)
        v.update(doc(v, 2, 3), true)                       // the add-on's next document: the game dropped roll 1
        draw(v)
        later(200)
        v.lastSent = null
        tap(v, need(v).first)
        val note = DialogView::class.java.getDeclaredField("noteText").apply { isAccessible = true }.get(v)
        assertEquals("THE DOUBLE PRESS: Need on roll 2, 200 ms after the roll set changed, is tapped (the base refused it: One moment) " +
            "- sent: ${v.lastSent}, the view said: $note", true, v.lastSent != null && v.lastSent != first && v.lastSent!!.contains("21"))
    }
}
