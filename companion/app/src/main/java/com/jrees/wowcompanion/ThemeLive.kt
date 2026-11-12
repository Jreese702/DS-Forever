package com.jrees.wowcompanion

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.reflect.KProperty

/**
 * A THEME CHANGE, LIVE (the user, 2026-11-12, the theme presets: the picker applies at once). Most of the app reads Theme's colours as
 * it draws, so a repaint is enough; what was COPIED when a view was built follows through these two helpers:
 *
 *  - [themed]: a member made from Theme's colours (`private val pTitle by themed { Paint().apply { color = Theme.gold } }`), made again
 *    the first time it is read after the theme changed;
 *  - [repaint]: the host's whole view tree walked once: a background or a TextView's words that hold one of the OLD theme's colours get
 *    the new theme's same colour, then every view is invalidated.
 */
class Themed<T>(private val make: () -> T) {
    private var v: T? = null
    private var t: ThemeSpec? = null
    operator fun getValue(thisRef: Any?, p: KProperty<*>): T {
        val now = Theme.cur
        val cur = v
        if (cur != null && t === now) return cur
        return make().also { v = it; t = now }
    }
}

fun <T> themed(make: () -> T) = Themed(make)

object ThemeLive {
    /** the old theme's colour -> the new one's, for the tokens a view copies when it is built */
    fun map(old: ThemeSpec, new: ThemeSpec): Map<Int, Int> {
        val m = LinkedHashMap<Int, Int>()
        fun put(a: Int, b: Int) { if (!m.containsKey(a)) m[a] = b }
        put(old.ink, new.ink); put(old.field, new.field); put(old.panel, new.panel); put(old.raised, new.raised)
        put(old.selected, new.selected); put(old.accent, new.accent); put(old.text, new.text); put(old.textDim, new.textDim)
        put(old.dim, new.dim); put(old.edge, new.edge); put(old.edgeDim, new.edgeDim)
        return m
    }

    /** [root] and everything under it brought to the new theme (main thread) */
    fun repaint(root: View, old: ThemeSpec, new: ThemeSpec) {
        val m = map(old, new)
        fun walk(v: View) {
            (v.background as? ColorDrawable)?.let { d -> m[d.color]?.let { if (it != d.color) v.setBackgroundColor(it) } }
            if (v is TextView) m[v.currentTextColor]?.let { if (it != v.currentTextColor) v.setTextColor(it) }
            v.invalidate()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }
}
