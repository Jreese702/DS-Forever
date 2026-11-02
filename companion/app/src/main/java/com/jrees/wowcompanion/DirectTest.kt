package com.jrees.wowcompanion

/**
 * THE DIRECT INSTALL'S TEST BUILD, never a release: the folder choice shows once though a game folder is saved, the install goes to
 * its own folder [FOLDER], and that folder's art is built on its own, so the player's game and art are not touched.
 */
object DirectTest {
    const val ON = false
    /** the low storage warning's test build: it always shows */
    const val LOW_SPACE_ALWAYS = false
    const val FOLDER = "WoW Forever Direct 5"
}
