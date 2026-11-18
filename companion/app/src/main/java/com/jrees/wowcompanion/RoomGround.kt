package com.jrees.wowcompanion

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * THE GROUNDS OF THE ROOMS THAT HAD NONE (beautify 8.4; the user, 2026-11-17: "the background on some of the OLED themes look
 * lifeless ... I do really like having the background image there"): Settings, the Controller room and Bags were a flat page. Now each
 * stands on the kit's rock, dimmed so the tiles and rows (each on its own plate) stay calm over it, under the theme's vignette.
 *
 * IN EVERY THEME, CLASSIC INCLUDED (the user's picks, 2026-11-17: pick 6 "a darkened stone / pad ground in EVERY theme, Classic
 * included", and Bags with them - "stone ground on Settings, Controller room and BAGS on EVERY theme incl. Classic"): there is no theme
 * that keeps the flat page.
 *
 * Core wires the calls (SettingsView's page, SettingsPad's scroll, ControllerView's page, BagsView's ground); only this file decides what
 * is drawn. [light] is the model, pure (the off-device tests read it).
 */
object RoomGround {
    enum class Room { SETTINGS, CONTROLLER, BAGS }

    /** the Settings page: rock at 0.55 x the theme's stone light */
    const val SETTINGS_LIGHT = 0.55f
    /**
     * The Controller room: the design named the kit's `pad_gen_back` as a pad ground, but that picture is the generic pad's Back
     * BUTTON glyph (a 64 px icon), not a ground; so its stated fallback, rock at 0.8 x the stone light, is the ground
     */
    const val CONTROLLER_LIGHT = 0.8f
    /** Bags: rock at 0.6 x the stone light (design 8.4: "rock dropped" on OLED; it was a flat page in every theme) */
    const val BAGS_LIGHT = 0.6f

    /**
     * OLED: these grounds 1.3x brighter (review fix 2026-11-17: rock at 0.55 x stone 0.40 = 0.22 rendered indistinguishable from
     * #000, against section 8's "never plain black"); the rock is flat grey art, so it reads as dark stone, not as a lit page
     */
    const val OLED_LIFT = 1.3f

    /** the rock's light for [room] in theme [t] (times the theme's own stone light in UiKit.roomGround); every theme draws it */
    fun light(room: Room, t: ThemeSpec): Float {
        val base = when (room) { Room.SETTINGS -> SETTINGS_LIGHT; Room.CONTROLLER -> CONTROLLER_LIGHT; Room.BAGS -> BAGS_LIGHT }
        return if (t.oled) base * OLED_LIFT else base
    }

    private val pFlat by lazy { Paint() }

    fun settings(c: Canvas, r: RectF) = draw(c, r, Room.SETTINGS)

    fun controller(c: Canvas, r: RectF) = draw(c, r, Room.CONTROLLER)

    /** Bags: the room paints its own page under it, so no ink here */
    fun bags(c: Canvas, r: RectF) {
        UiKit.roomGround(c, r, "ground_rock", light(Room.BAGS, Theme.cur))
    }

    private fun draw(c: Canvas, r: RectF, room: Room) {
        pFlat.color = Theme.ink
        c.drawRect(r, pFlat)
        UiKit.roomGround(c, r, "ground_rock", light(room, Theme.cur))
    }
}
