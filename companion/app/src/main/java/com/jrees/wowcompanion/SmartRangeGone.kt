package com.jrees.wowcompanion

/**
 * m160 SMART RANGE IS GONE (the user, 2026-11-13: "Lets scrap the smart ranged attack"). The add-on's one-time clean-up per character
 * (PadMap OV.srx: only our own saved "DSF ..." macros - our name AND our exact body - and the stored entries' Smart Range fields) says
 * its counts in every padmap (`srx`, [PadMapModel.SrGone]); this keeps the latest for the problem report's settings, counts only (no
 * macro or character names). Pure: the tests build it from a padmap.
 */
object SmartRangeGone {
    @Volatile private var last: PadMapModel.SrGone? = null

    /** the latest padmap's counts (the current character's); true when they changed (the host logs the line once then) */
    @Synchronized fun note(g: PadMapModel.SrGone?): Boolean {
        if (g == null || g == last) return false
        last = g
        return true
    }

    @Synchronized fun reset() { last = null }

    /** the report's line; null before a padmap said anything */
    fun line(): String? = last?.let { line(it) }

    fun line(g: PadMapModel.SrGone): String =
        if (g.pending && g.macros == 0 && g.buttons == 0 && g.kept == 0) "Smart Range clean-up: waiting (macros not loaded yet, or a fight)"
        else "Smart Range clean-up: ${g.macros} macros deleted, ${g.buttons} buttons back to one spell, ${g.kept} look-alike macros kept" +
            (if (g.pending) " (an earlier session; this one has not run yet)" else "")
}
