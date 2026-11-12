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
 *  - [repaint]: the host's whole view tree walked once: a background or words given BY NAME ([ThemeLive.bg] / [ThemeLive.fg] /
 *    [ThemeLive.hint], a [Tok]) get the new theme's colour of that name; one that only copied a value is carried over when that value
 *    was one token's alone in the old theme; then every view is invalidated.
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

/** a chrome colour by its NAME (a view built with one is repainted by name, never by value: OLED's ink, field and panel are all black) */
enum class Tok(val of: (ThemeSpec) -> Int) {
    INK({ it.ink }), FIELD({ it.field }), PANEL({ it.panel }), RAISED({ it.raised }), SELECTED({ it.selected }),
    ACCENT({ it.accent }), EDGE({ it.edge }), EDGE_DIM({ it.edgeDim }), TEXT({ it.text }), TEXT_DIM({ it.textDim }), DIM({ it.dim }),
    WARN({ it.warn });
    val now: Int get() = of(Theme.cur)
}

object ThemeLive {
    // the views that were given a theme colour by name ([bg], [fg], [hint]); weak: a view that is gone is not kept
    private val bgs = java.util.WeakHashMap<View, Tok>()
    private val fgs = java.util.WeakHashMap<TextView, Tok>()
    private val hints = java.util.WeakHashMap<TextView, Tok>()

    /** [v]'s background in the theme's [t], remembered: a theme change repaints it by name */
    fun <V : View> bg(v: V, t: Tok): V { v.setBackgroundColor(t.now); synchronized(this) { bgs[v] = t }; return v }
    /** [v]'s words in the theme's [t], remembered */
    fun <V : TextView> fg(v: V, t: Tok): V { v.setTextColor(t.now); synchronized(this) { fgs[v] = t }; return v }
    /** [v]'s hint in the theme's [t], remembered */
    fun <V : TextView> hint(v: V, t: Tok): V { v.setHintTextColor(t.now); synchronized(this) { hints[v] = t }; return v }

    /**
     * The old theme's colour -> the new one's, for a view that copied a theme colour without naming it: ONLY the colours that are one
     * token's alone in the old theme (an OLED theme's black is ink, field and panel at once: such a view is left as it is, never
     * guessed, so a black that was meant to be black stays black)
     */
    fun map(old: ThemeSpec, new: ThemeSpec): Map<Int, Int> {
        val count = HashMap<Int, Int>()
        for (t in Tok.values()) count[t.of(old)] = (count[t.of(old)] ?: 0) + 1
        val m = LinkedHashMap<Int, Int>()
        for (t in Tok.values()) { val a = t.of(old); if (count[a] == 1) m[a] = t.of(new) }
        return m
    }

    /** [root] and everything under it brought to the new theme (main thread) */
    fun repaint(root: View, old: ThemeSpec, new: ThemeSpec) {
        val m = map(old, new)
        val b: Map<View, Tok>; val f: Map<TextView, Tok>; val h: Map<TextView, Tok>
        synchronized(this) { b = HashMap(bgs); f = HashMap(fgs); h = HashMap(hints) }
        fun walk(v: View) {
            val bt = b[v]
            if (bt != null) v.setBackgroundColor(bt.of(new))
            else (v.background as? ColorDrawable)?.let { d -> m[d.color]?.let { if (it != d.color) v.setBackgroundColor(it) } }
            if (v is TextView) {
                val ft = f[v]
                if (ft != null) v.setTextColor(ft.of(new))
                else m[v.currentTextColor]?.let { if (it != v.currentTextColor) v.setTextColor(it) }
                h[v]?.let { v.setHintTextColor(it.of(new)) }
            }
            v.invalidate()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }
}
