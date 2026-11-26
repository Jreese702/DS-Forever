package com.jrees.wowcompanion

import android.view.View

/**
 * SINGLE SCREEN MODE'S TOP WINDOW AS THE HOST SEES IT (SingleScreen): the launcher's DsForeverScreen hosts CompanionHost in a
 * never-focused window over the game on display 0 (fork/TopWindow) and hands it to the host as this ([HostShell.hostSingle]); null =
 * two screens (the bottom Presentation, the app's own MainActivity, Swap screens' activity): every single-mode branch of the host is
 * `single != null`.
 */
interface SingleShell {
    /** the top window's size (1920 x 1080 on the Thor) */
    val screenW: Int
    val screenH: Int
    /** the centred panel: SingleScreen.panelBox(screenW, screenH), (left, top, right, bottom) */
    val box: IntArray
    /** the second screen's display id on a two-screen device (NPC: our activities go there), else null */
    val otherDisplay: Int?
    /** what shows now ([SingleLook.of]); main thread, applies only changes */
    fun look(l: SingleLook.Look)
    /** a sub window ("dockL", "dockR" (PLAY), "strip" (NPC), "hint" (CORE)): made on the first ask, gone with the top window */
    fun sub(id: String): SingleSub
    /** the plate at the top screen's top centre (not touchable): [text] (Tr'd here), null = off; [ms] 0 = until cleared */
    fun hint(text: String?, ms: Long = 0L)
}

/** one small never-focused window over the game (SingleShell.sub): it hosts one view, at bounds the caller gives */
interface SingleSub {
    /** the one view it hosts (re-parented by the caller); null = empty */
    fun put(v: View?)
    /** its bounds on the top screen, px: ONE window update */
    fun place(l: Int, t: Int, r: Int, b: Int)
    /** shown (VISIBLE) or gone (GONE: no surface) */
    fun shown(on: Boolean)
    fun touchable(on: Boolean)
}
