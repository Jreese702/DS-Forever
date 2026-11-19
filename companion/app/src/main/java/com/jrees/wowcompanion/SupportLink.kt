package com.jrees.wowcompanion

/**
 * SUPPORT THE PROJECT (the user, 2026-11-19: "add a link to my Kofi to the bottom of the General tab. just a simple button at the
 * bottom that says 'Support the Project'"): Settings > General's last row, ONE full-width button. A tap or A opens [URL] in the browser
 * the way the app's other links open (CompanionHost.openUrlOnTop: ACTION_VIEW, NEW_TASK, on the game's screen so it never sits under
 * this window); no browser = a short note on the row. No game interaction, no add-on change, no permission.
 */
object SupportLink {
    const val ROW_ID = "Support the Project"
    const val URL = "https://ko-fi.com/yeslander"
    /** the row's value word for a few seconds after a press that found no browser */
    const val NO_BROWSER = "No browser found"
    const val NOTE_MS = 4000L
}
