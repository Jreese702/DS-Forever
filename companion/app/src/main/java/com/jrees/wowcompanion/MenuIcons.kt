package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF

/**
 * THE CONTEXT MENUS' ICONS, MIXED FROM WOW ART (the user, 2026-11-07: "Can we mix some wow art together to make some icons that fit our
 * context menus better?"; the design sheet E:/wow-build/menu-icon-design/sheet_v3.png, picked "All A except follow use B"). Each is the
 * game's own icon cropped round on the game's radial slot plate (kit radial_place), a gold rim, and for some a badge in the lower right
 * corner mixed in from other game art (a game icon or a kit picture: the plus, the red X, the leader's crown, the chat bubble).
 *
 * NOTHING HERE SHIPS GAME ART: the icons are the phone's own harvest (Icons.get asks for any it does not have yet), the plate and the
 * badges the harvested kit (UiKit.tex). Used by the target radial's slices, the target's / a party member's menu list and Home's portrait menu.
 */
object MenuIcons {
    /** [base] = a game icon's fileDataID; the badge = another icon ([badgeIcon]) or a kit picture ([badgeKit]) */
    data class Spec(val base: Int, val badgeIcon: Int? = null, val badgeKit: String? = null,
                    /** a symbol with its own shape (the raid markers): drawn whole on a dark disc, not cropped round */
                    val whole: Boolean = false)

    val SPECS: Map<String, Spec> = mapOf(
        "whisper" to Spec(134327, badgeKit = "ricon_chat"),          // inv_misc_note_01 + the chat bubble
        "invite" to Spec(134149),                                     // inv_misc_groupneedmore
        "trade" to Spec(133784, badgeIcon = 133639),                  // inv_misc_coin_01 + inv_misc_bag_10
        "inspect" to Spec(134441),                                    // inv_misc_spyglass_02
        "follow" to Spec(5554511),                                    // ui_followerdungeon_followme (the user's B)
        "duel" to Spec(132147),                                       // ability_dualwield
        "addfriend" to Spec(442272, badgeKit = "list_plus"),          // achievement_guildperk_everybodysfriend + the plus
        "more" to Spec(133741),                                       // inv_misc_book_09
        "setfocus" to Spec(236179),                                   // ability_hunter_focusedaim
        "raidtarget" to Spec(236188),                                 // ability_hunter_markedfordeath
        "promote" to Spec(134148, badgeKit = "mark_leader"),          // inv_misc_grouplooking + the leader's crown
        "uninvite" to Spec(134148, badgeKit = "icon_redx"),           // inv_misc_grouplooking + the red X
    ) + (1..8).associate { n -> "raid$n" to Spec(137000 + n, whole = true) }   // the game's raid markers (TargetingFrame/UI-RaidTargetingIcon_n)

    /** an entry's OWN game icon (Quests q56 `ic`: a pet action's spell icon) as a key: "icon:<fileDataID>", drawn on the same plate */
    fun iconKey(fid: Int): String = "icon:$fid"

    /** the spec for [key]: one of [SPECS], or an entry's own icon ([iconKey]) */
    fun spec(key: String): Spec? = SPECS[key] ?: key.takeIf { it.startsWith("icon:") }?.substring(5)?.toIntOrNull()?.takeIf { it > 0 }?.let { Spec(it) }

    /** [key] is one [draw] can draw */
    fun has(key: String?): Boolean = key != null && spec(key) != null

    /** a menu entry's icon key from its key (the game's mixin name) or its words; null = none of ours */
    fun keyOf(entryKey: String?, text: String): String? {
        val k = (entryKey ?: text).lowercase().replace(" ", "")
        return when {
            k.contains("uninvite") || k.contains("removefromgroup") || k == "kick" -> "uninvite"
            k.contains("whisper") -> "whisper"
            k.contains("invite") && !k.contains("guild") -> "invite"
            k.contains("trade") -> "trade"
            k.contains("inspect") -> "inspect"
            k.contains("follow") -> "follow"
            k.contains("duel") -> "duel"
            k.contains("friend") -> "addfriend"
            k.contains("clearfocus") -> null
            k.contains("focus") -> "setfocus"
            Regex("raidtarget(?:icon)?([1-8])").find(k) != null -> "raid" + Regex("raidtarget(?:icon)?([1-8])").find(k)!!.groupValues[1]
            k.contains("raidtarget") -> "raidtarget"
            k.contains("promote") -> "promote"
            k == "more" -> "more"
            else -> null
        }
    }

    // lazy: [keyOf] / [SPECS] stay usable off-device (the JVM tests), the paints come with the first draw
    /**
     * every icon these menus use, asked for once (the user, 2026-11-07: "The raid target Icons should use the actual raid target icon
     * shapes"): Icons.get puts any the phone has not harvested yet on the required list, so the next icon harvest brings the game's own
     * raid markers (and the rest) before a wheel first shows them, instead of the stand-in until then
     */
    @Volatile private var asked = false
    fun prefetch() {
        if (asked) return
        asked = true
        for (s in SPECS.values) { Icons.get(s.base); s.badgeIcon?.let { Icons.get(it) } }
    }

    private val pBmp by lazy { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    private val pRim by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = RIM } }
    private val pFill by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = BADGE_FILL } }
    private val grey by lazy { ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
    private val path by lazy { Path() }

    /** the icon [key] in [r] (a square); [alpha] 0..255, [dim] = greyed (a greyed entry); false when the key is not ours */
    fun draw(c: Canvas, r: RectF, key: String, alpha: Int = 255, dim: Boolean = false): Boolean {
        val spec = spec(key) ?: return false
        val s = minOf(r.width(), r.height())
        val cx = r.centerX(); val cy = r.centerY()
        pBmp.alpha = alpha
        pBmp.colorFilter = if (dim) grey else null
        UiKit.tex("radial_place")?.let { c.drawBitmap(it, null, RectF(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f), pBmp) }
        if (spec.whole) {
            pFill.alpha = alpha
            c.drawCircle(cx, cy, s * 0.357f, pFill)
            val b = Icons.get(spec.base)
            if (b != null) {
                val h = s * 0.25f
                c.drawBitmap(b, null, RectF(cx - h, cy - h, cx + h, cy + h), pBmp)
            } else disc(c, Icons.getOrUnknown(SPECS["raidtarget"]?.base), cx, cy, s * 0.357f)   // until the phone has harvested the marker
        } else disc(c, Icons.getOrUnknown(spec.base), cx, cy, s * 0.357f)
        pRim.strokeWidth = maxOf(1.5f, s * 0.027f); pRim.alpha = alpha
        c.drawCircle(cx, cy, s * 0.357f + pRim.strokeWidth / 2f, pRim)
        if (spec.badgeIcon != null || spec.badgeKit != null) {
            val br = s * 0.196f
            val bx = cx + s / 2f - br - s * 0.02f; val by = cy + s / 2f - br - s * 0.02f
            pFill.alpha = alpha
            c.drawCircle(bx, by, br, pFill)
            c.drawCircle(bx, by, br - pRim.strokeWidth / 2f, pRim)
            if (spec.badgeIcon != null) disc(c, Icons.getOrUnknown(spec.badgeIcon), bx, by, br - s * 0.036f)
            else UiKit.tex(spec.badgeKit!!)?.let { k ->
                val f = (br * 2f - s * 0.125f) / maxOf(k.width, k.height)
                val w = k.width * f; val h = k.height * f
                c.drawBitmap(k, null, RectF(bx - w / 2f, by - h / 2f, bx + w / 2f, by + h / 2f), pBmp)
            }
        }
        pBmp.alpha = 255; pBmp.colorFilter = null; pRim.alpha = 255; pFill.alpha = 255
        return true
    }

    /** a game icon cropped round (its own 4 px dark edge trimmed first, as the sheet), radius [rad] at (cx, cy) */
    private fun disc(c: Canvas, b: Bitmap?, cx: Float, cy: Float, rad: Float) {
        b ?: return
        val inset = (b.width * 5f / 64f).toInt()
        val save = c.save()
        path.reset(); path.addCircle(cx, cy, rad, Path.Direction.CW)
        c.clipPath(path)
        c.drawBitmap(b, Rect(inset, inset, b.width - inset, b.height - inset), RectF(cx - rad, cy - rad, cx + rad, cy + rad), pBmp)
        c.restoreToCount(save)
    }

    private const val RIM = 0xFFC9A048.toInt()
    private const val BADGE_FILL = 0xFF0E0A06.toInt()
}
