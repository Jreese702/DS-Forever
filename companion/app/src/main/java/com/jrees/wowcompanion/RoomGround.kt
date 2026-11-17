package com.jrees.wowcompanion

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * THE GROUNDS OF THE TWO ROOMS THAT HAD NONE (beautify 8.4; the user, 2026-11-17: "the background on some of the OLED themes look
 * lifeless ... I do really like having the background image there"): Settings and the Controller room were a flat page in every theme.
 * Now both stand on the kit's rock, dimmed so the tiles and rows (each on its own plate) stay calm over it, under the theme's vignette.
 *
 * Core wires the calls (SettingsView's page, SettingsPad's scroll, ControllerView's page); only this file decides what is drawn.
 * User pick 6 (open): [CLASSIC_TOO] = the stone in every theme, Classic included (recommended); false = OLED / Dark only.
 */
object RoomGround {
    /** user pick 6: Classic gets the stone too */
    const val CLASSIC_TOO = true

    /** the Settings page: rock at 0.55 x the theme's stone light */
    const val SETTINGS_LIGHT = 0.55f
    /**
     * The Controller room: the design named the kit's `pad_gen_back` as a pad ground, but that picture is the generic pad's Back
     * BUTTON glyph (a 64 px icon), not a ground; so its stated fallback, rock at 0.8 x the stone light, is the ground
     */
    const val CONTROLLER_LIGHT = 0.8f

    private val pFlat = Paint()

    fun settings(c: Canvas, r: RectF) = draw(c, r, SETTINGS_LIGHT)

    fun controller(c: Canvas, r: RectF) = draw(c, r, CONTROLLER_LIGHT)

    private fun draw(c: Canvas, r: RectF, light: Float) {
        pFlat.color = Theme.ink
        c.drawRect(r, pFlat)
        if (!CLASSIC_TOO && Theme.classic) return
        UiKit.roomGround(c, r, "ground_rock", light)
    }
}
