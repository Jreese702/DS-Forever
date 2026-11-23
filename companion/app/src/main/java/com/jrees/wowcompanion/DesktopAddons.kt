package com.jrees.wowcompanion

import android.content.Context

/**
 * ISSUE 453 ("cannot bind to the new cast bar anymore", two players, both streaming): while streaming, the documents come from the PC's
 * add-ons - the set DS Forever Desktop put there - and an older Desktop carries older add-ons (a castbar with no slot pick, a 6- or
 * 12-slot bar). The Bind box's Tap to cast tab then vanished with no word. Now the app compares what the PC's add-ons say they are
 * (the padmap's `v`, PadMap's PV; the castbar's `version`, ActionBar's CBV) with the tags of the set it carries itself (its own assets),
 * and while streaming with an older one it says so ([NOTE]): one WCSETUP line, and the Tap to cast tab stays on show, dimmed, with the
 * note. A newer or an unknown tag is never "older". Pure but for [init] (DesktopAddonsTest).
 */
object DesktopAddons {
    const val NOTE = "Desktop's add-ons are older: update DS Forever Desktop"
    const val PADMAP_ASSET = "companion/addons/WowCompanionSpells/PadMap.lua"
    const val ACTIONBAR_ASSET = "companion/addons/WowCompanion/ActionBar.lua"

    /** the bundled set's PadMap tag ("m186") and cast bar tag ("cb16"); null until [init] read them (or a file without one) */
    @Volatile var bundledPad: String? = null
    @Volatile var bundledCb: String? = null

    /** the tag a Lua file declares as `local <name> = "<tag>"` (the first one), or null */
    fun tagIn(lines: Sequence<String>, name: String): String? {
        val re = Regex("""^\s*local\s+$name\s*=\s*"([a-z]+\d+[a-z]?)"""")
        for (l in lines) re.find(l)?.let { return it.groupValues[1] }
        return null
    }

    /** a tag's number for its letters ("m186" with "m" -> 186; "cb16" with "cb" -> 16); null for another kind or none */
    fun num(tag: String?, prefix: String): Int? {
        if (tag == null || !tag.startsWith(prefix)) return null
        return tag.substring(prefix.length).takeWhile { it.isDigit() }.toIntOrNull()
    }

    /** [have] is an older tag than [bundled] (both known, the same kind) */
    fun olderTag(have: String?, bundled: String?, prefix: String): Boolean {
        val h = num(have, prefix) ?: return false
        val b = num(bundled, prefix) ?: return false
        return h < b
    }

    /** while [streaming], the PC's add-ons ([padV] the padmap's, [cbV] the castbar's) are older than the set the app carries */
    fun older(streaming: Boolean, padV: String?, cbV: String?, bPad: String? = bundledPad, bCb: String? = bundledCb): Boolean =
        streaming && (olderTag(padV, bPad, "m") || olderTag(cbV, bCb, "cb"))

    /** the WCSETUP line (tags only, never a name) */
    fun line(padV: String?, cbV: String?, bPad: String? = bundledPad, bCb: String? = bundledCb): String =
        "streaming: the PC's add-ons are older (padmap ${padV ?: "?"} < ${bPad ?: "?"} or castbar ${cbV ?: "?"} < ${bCb ?: "?"}): $NOTE"

    /** the bundled tags, once (a background read of two asset files: each stops at its tag's line) */
    fun init(ctx: Context) {
        if (bundledPad != null && bundledCb != null) return
        val am = ctx.applicationContext.assets
        Thread({
            bundledPad = runCatching { am.open(PADMAP_ASSET).bufferedReader().use { tagIn(it.lineSequence(), "PV") } }.getOrNull()
            bundledCb = runCatching { am.open(ACTIONBAR_ASSET).bufferedReader().use { tagIn(it.lineSequence(), "CBV") } }.getOrNull()
        }, "dsf-addon-tags").apply { isDaemon = true }.start()
    }
}
