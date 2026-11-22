package com.jrees.wowcompanion

import android.os.SystemClock
import android.view.View

/**
 * THE MAP'S TWO STICKS, ONE DRIVER (the Map room's since 2026-10-07 / 10-17; Home's map controls since 2026-11-17, the user: "when using
 * map controls it should function just like the map room with the sticks"). The left stick moves the map's free cursor (the game's
 * SoftCursor: [MapRoomKeys.cursorStep], [speedPxS] at full tilt, slower over a marker), the right stick zooms up in / down out
 * ([MapRoomKeys.stickZoom]); each runs a frame loop on [view] while it is pushed past the dead-zone and the [host] takes the sticks.
 * The host owns what a move means (its own frame, follow, hover); this class only reads ([PadInput]: a read-only subscription) and
 * paces. Main thread throughout.
 */
class MapStickDriver(private val view: View, private val speedPxS: Float, private val host: Host) {
    interface Host {
        /** the map takes the sticks now (shown, no menu up, and - #584 - the map holds the controller: MapHoldClaim) */
        fun sticksLive(): Boolean
        /** the left stick moves the cursor now (the Map room: the map side, not its list) */
        fun cursorLive(): Boolean = true
        /** the cursor rests on a marker: it slows down (the user's pick after b107) */
        fun overMarker(): Boolean = false
        /** one frame's cursor move, screen px (x right, y down) */
        fun moveCursor(dx: Float, dy: Float)
        /** one frame's zoom over [dtS] seconds: multiply the zoom by [f] (> 1 = in) */
        fun zoomBy(f: Float, dtS: Float)
        /** every left-stick vector, as it comes (the Map room's list steps a row a push) */
        fun leftVector(x: Float, y: Float) {}
    }

    private var reader: PadInput? = null
    var stickX = 0f; private set
    var stickY = 0f; private set
    var zoomY = 0f; private set
    private var cursorRunning = false
    private var cursorAt = 0L
    private var zoomRunning = false
    private var zoomAt = 0L
    private val cursorFrame = Runnable { cursorStep() }
    private val zoomFrame = Runnable { zoomStep() }

    val running: Boolean get() = reader != null

    fun start() {
        if (reader != null) return
        reader = PadInput(onPad = { _, _, _, _ -> }, onStatus = { _, _ -> }, onStickVector = { stick, x, y -> vector(stick, x, y) })
            .also { it.start() }
    }

    fun stop() {
        reader?.stop(); reader = null
        stickX = 0f; stickY = 0f; zoomY = 0f
        view.removeCallbacks(cursorFrame); view.removeCallbacks(zoomFrame)
        cursorRunning = false; zoomRunning = false
    }

    /**
     * the host's sticks became live again (#584: the map's hold landed, the stick catch showed): a stick already pushed and held still
     * sends no new vector, so the frame loops start here from the last one read
     */
    fun resume() {
        if (reader == null || !host.sticksLive()) return
        if (!cursorRunning && host.cursorLive() && MapRoomKeys.stickMoves(stickX, stickY)) { cursorRunning = true; cursorAt = 0L; view.postOnAnimation(cursorFrame) }
        if (!zoomRunning && kotlin.math.abs(zoomY) > MapRoomKeys.STICK_DEAD) { zoomRunning = true; zoomAt = 0L; view.postOnAnimation(zoomFrame) }
    }

    /** one stick vector (PadInput's: 0 = left, 1 = right; x right, y down, -1..1) */
    internal fun vector(stick: Int, x: Float, y: Float) {
        if (stick == 1) {
            zoomY = y
            if (!zoomRunning && kotlin.math.abs(y) > MapRoomKeys.STICK_DEAD) { zoomRunning = true; zoomAt = 0L; view.postOnAnimation(zoomFrame) }
            return
        }
        if (stick != 0) return
        stickX = x; stickY = y
        if (!cursorRunning && host.cursorLive() && MapRoomKeys.stickMoves(x, y)) { cursorRunning = true; cursorAt = 0L; view.postOnAnimation(cursorFrame) }
        host.leftVector(x, y)
    }

    private fun cursorStep() {
        if (!host.sticksLive() || !host.cursorLive() || !MapRoomKeys.stickMoves(stickX, stickY)) { cursorRunning = false; return }
        val now = SystemClock.uptimeMillis()
        val dt = MapRoomKeys.frameDt(now, cursorAt); cursorAt = now
        val (dx, dy) = MapRoomKeys.cursorStep(stickX, stickY, dt, speedPxS, overMarker = host.overMarker())
        host.moveCursor(dx, dy)
        view.postOnAnimation(cursorFrame)
    }

    private fun zoomStep() {
        val f0 = MapRoomKeys.stickZoom(zoomY, 1f / 60f)
        if (!host.sticksLive() || f0 == 1f) { zoomRunning = false; return }
        val now = SystemClock.uptimeMillis()
        val dt = MapRoomKeys.frameDt(now, zoomAt); zoomAt = now
        host.zoomBy(MapRoomKeys.stickZoom(zoomY, dt), dt)
        view.postOnAnimation(zoomFrame)
    }
}
