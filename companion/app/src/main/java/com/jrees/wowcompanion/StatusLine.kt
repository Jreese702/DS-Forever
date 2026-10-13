package com.jrees.wowcompanion

/**
 * What the status line under the strip used to say ("gaps 0  v0.1.0", a failed root shell, the
 * all-files warning). The line itself is gone (user, 2026-09-28: the strip takes its place);
 * the Settings room shows the text in its last row.
 */
object StatusLine {
    @Volatile var text: String = ""
        set(v) { if (field != v) { field = v; onChange?.invoke() } }

    /** called on whatever thread set [text]: post to your own view */
    @Volatile var onChange: (() -> Unit)? = null
}
