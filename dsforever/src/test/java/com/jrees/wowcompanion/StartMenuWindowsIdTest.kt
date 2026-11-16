package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * issue #288 ("tell me how to open windows setting to change setting? i can't find it. I want to fix the error BLZ51914400"): the
 * chooser's Menu has Windows ID beside Region; a press steps Per install / Original, the same choice as Settings > General > Account.
 */
class StartMenuWindowsIdTest {
    private var saved = WindowsId.PER_INSTALL

    @After fun down() {
        StartMenu.windowsId = { Settings.windowsId }; StartMenu.onWindowsId = { Settings.windowsId = it }
    }

    @Test fun aPressStepsTheChoice() {
        StartMenu.windowsId = { saved }; StartMenu.onWindowsId = { saved = it }
        assertEquals("Windows ID: Per install", StartMenu.winIdWords())
        StartMenu.press(StartMenu.WINID)
        assertEquals(WindowsId.ORIGINAL, saved)
        assertEquals("Windows ID: Original", StartMenu.winIdWords())
        StartMenu.press(StartMenu.WINID)
        assertEquals(WindowsId.PER_INSTALL, saved)
        saved = "junk"                                                                  // an unknown saved value: the default's next
        StartMenu.press(StartMenu.WINID)
        assertEquals(WindowsId.ORIGINAL, saved)
    }
}
