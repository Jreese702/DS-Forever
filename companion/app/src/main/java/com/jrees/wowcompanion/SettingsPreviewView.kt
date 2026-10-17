package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View

/**
 * THE "WHAT IT DOES" PANEL (board f-settings-tabs, phase 2; the user's pick: "Panel on the right"): beside the Settings page, the
 * picture of the row the cursor is on (or the last row touched), at the setting's value now ([SettingsPreview]). It moves only
 * while it shows and its picture is an animated one; a setting changed redraws it (an ABXY change glides).
 */
class SettingsPreviewView(context: Context, private val toolbarOn: () -> Boolean?) : View(context) {
    /** the row shown (SettingsPreview's id = the row's words); null = nothing yet */
    var id: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    private val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; color = Theme.dim }
    private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f; color = Theme.gold; typeface = Typeface.DEFAULT_BOLD }
    private val pCap = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 21f; color = Theme.textDim }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }

    private var last: SettingsPreview.State? = null
    private var prev: SettingsPreview.State? = null
    private var changedAt = 0L
    private val t0 = SystemClock.uptimeMillis()

    /** the button pictures, in the chosen set (UiKit.pad: the kit's glyphs) */
    private val glyph = SettingsPreview.Glyph { c, action, set, cx, cy, size ->
        val name = when (set) {
            1 -> "ltr_$action"
            2 -> "shp_" + when (action) { "a" -> "cross"; "b" -> "circle"; "x" -> "square"; "y" -> "triangle"; else -> action }
            else -> "rev_$action"
        }
        UiKit.pad(c, name, cx, cy, size)
    }

    private val changed: () -> Unit = { post { invalidate() } }

    /** f-settings-previews: the real Home in miniature (PreviewStage); the panel draws it again while a picture is still coming */
    private val live = object : SettingsPreview.Live {
        override fun home(look: HomeView.Companion.Look, fight: Boolean, toolbar: Boolean?) = PreviewStage.home(context, look, fight, toolbar)
        override fun wheelItems(spell: Boolean) = PreviewStage.wheelItems(spell)
        override fun classWheel(c: Canvas, w: Float, h: Float, left: Boolean, pointed: Int) = PreviewStage.classWheel(context, c, w, h, left, pointed)
        override fun mapBox() = PreviewStage.mapBox
        override fun keyboard(c: Canvas, w: Float, h: Float) = PreviewStage.keyboard(context, c, w, h)
    }

    init {
        HomeSettings.onChange(changed); Settings.onChange(changed); FrameGen.onChange(changed); CastSettings.onChange(changed)
        HomeAttackSwitch.onChange(changed)
    }

    /** the values now (the room's settings objects) */
    private fun state() = SettingsPreview.State(
        diamonds = HomeSettings.rest == HomeRest.DIAMONDS, combatGrow = HomeSettings.combatGrow, swing = HomeSettings.swingTimer,
        questOverMap = HomeSettings.questOverMap, startAttack = HomeAttackSwitch.state?.on, toolbar = toolbarOn(),
        abxyNintendo = CastSettings.faceSwap, glyphSet = CastSettings.ButtonLayout.values().indexOf(CastSettings.buttonLayout),
        pauseTap = HomeSettings.pauseTap, pausePickA = HomeSettings.pausePickA, radialTap = HomeSettings.radialTap,
        radialPickA = HomeSettings.radialPickA, spellTap = HomeSettings.spellTap, spellPickA = HomeSettings.spellPickA,
        classTapL = HomeSettings.classTapL, classPickAL = HomeSettings.classPickAL, classTapR = HomeSettings.classTapR,
        classPickAR = HomeSettings.classPickAR, miniOutYd = Settings.minimapYd, miniInYd = Settings.minimapIndoorYd,
        quickChat = Settings.chatDefaultInput == "quick", fpsLimit = FrameGen.fpsLimit, frameGen = FrameGen.enabled && FrameGen.hasDll(),
        multiplier = FrameGen.multiplier, flow = FrameGen.flowScale, indoorBuild = Settings.indoorBuild)

    override fun onDraw(c: Canvas) {
        UiKit.asBody(pLabel); UiKit.asTitle(pTitle); UiKit.asBody(pCap)
        c.drawColor(Theme.ink)
        val box = RectF(4f, 8f, width - 12f, height - 12f)
        pFill.color = Theme.field; c.drawRoundRect(box, 10f, 10f, pFill)
        pStroke.color = Theme.bronze; c.drawRoundRect(box, 10f, 10f, pStroke)
        c.drawText("WHAT IT DOES", box.left + 18f, box.top + 30f, pLabel)
        val row = id
        if (row == null || !SettingsPreview.has(row)) {
            pCap.color = Theme.dim
            c.drawText("Pick a setting to see what it does.", box.left + 18f, box.top + 70f, pCap)
            return
        }
        val now = SystemClock.uptimeMillis()
        val s = state()
        if (last != null && s != last) { prev = last; changedAt = now }
        last = s
        c.drawText(fit(pTitle, SettingsPreview.title(row, s), box.width() - 36f), box.left + 18f, box.top + 64f, pTitle)
        val caps = SettingsPreview.caption(row, s)
        val capTop = box.bottom - 20f - caps.size * 28f
        val pic = RectF(box.left + 8f, box.top + 84f, box.right - 8f, capTop - 8f)
        c.save(); c.clipRect(pic)
        SettingsPreview.draw(c, pic, row, s, now - t0, glyph, prev, now - changedAt, live)
        c.restore()
        pCap.color = Theme.textDim
        for ((i, line) in caps.withIndex()) c.drawText(fit(pCap, line, box.width() - 36f), box.left + 18f, capTop + 22f + i * 28f, pCap)
        if ((SettingsPreview.animated(row) || now - changedAt < 600L) && isShown) postInvalidateOnAnimation()
    }

    private fun fit(p: Paint, s: String, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        val n = p.breakText(s, true, (maxW - p.measureText("…")).coerceAtLeast(0f), null)
        return s.substring(0, n).trimEnd() + "…"
    }
}
