package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject

/**
 * THE ROOM'S COMBAT CUE (the user, 2026-10-01: "I don't want the game to take the controller at all, I want the room that I am in to
 * flash red"; Interact's capture v8 lets a room KEEP the controller through a fight and says so in the interact document's `fight`).
 * Over the ROOM AREA only (not Home, not the bar), UI Art's look: the game's red screen-edge glow (UiKit.combatGlow, COMBAT_EDGE deep)
 * at UiKit.combatFlash's strength - a flash to full, a second flash, then a steady edge for the rest of the fight; when the fight ends
 * with the room still on screen it fades out over 300 ms; leaving the room ends it at once (Home's combat transition takes over).
 * An "in combat" refusal (`fight.n` stepping) flashes it again. Edge only (UI Art's lean; the red Home disc says where to go). It takes
 * no touch: the room under it works as before.
 */
class RoomCombatCue(context: Context) : View(context) {
    private var active = false
    private var n = -1
    private var startAt = 0L                 // the fight (or the last refusal) started the flash
    private var endAt = -1L                  // the fight ended with the room on screen: the fade's start
    private var heardAt = 0L
    private val box = RectF()

    init {
        setWillNotDraw(false)
        visibility = GONE
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = false

    /** a CURRENT interact document, while a room (not Home) shows when [inRoom] */
    fun note(msg: JSONObject, inRoom: Boolean) {
        if (!inRoom) { off(); return }
        val fight = msg.optJSONObject("fight")
        val now = SystemClock.uptimeMillis()
        if (fight == null) {
            // no room holds through a fight any more: the edge fades out (if one was showing)
            if (active && endAt < 0) { endAt = now; invalidate() }
            return
        }
        heardAt = now
        val nn = fight.optInt("n", 0)
        if (!active || endAt >= 0 || nn != n) { startAt = now; endAt = -1 }
        n = nn
        if (!active) { active = true; visibility = VISIBLE }
        invalidate()
    }

    /** Home shows, or the app goes away: no cue, at once */
    fun off() {
        if (!active) return
        active = false; n = -1; endAt = -1
        visibility = GONE
    }

    /** the 1 s tick: no current word for [STALE_MS] - a stale red must never stay */
    fun tick() { if (active && endAt < 0 && SystemClock.uptimeMillis() - heardAt > STALE_MS) { endAt = SystemClock.uptimeMillis(); invalidate() } }

    override fun onDraw(c: Canvas) {
        if (!active) return
        val now = SystemClock.uptimeMillis()
        val endMs = if (endAt >= 0) (now - endAt).toFloat() else -1f
        if (endMs >= FADE_MS) { off(); return }
        val k = UiKit.combatFlash((now - startAt).toFloat(), endMs)
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        if (k > 0.004f) UiKit.combatGlow(c, box, k, UiKit.COMBAT_EDGE)
        // frames only while it moves: the first 0.8 s and the fade; the steady edge needs none
        if (now - startAt < FLASH_MS || endAt >= 0) postInvalidateOnAnimation()
    }

    private companion object {
        const val FLASH_MS = 820L
        const val FADE_MS = 300f
        const val STALE_MS = 20_000L
    }
}
