package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE MENU'S REGION CHOICE (the user, 2026-11-17: "when selecting the region in menu, it is closing the menu before I can even confirm
 * and downloading a new build. I accidentally swaped to EU and had to painfully get back to US one click at a time"), off-device: the
 * list browses without a change, a different region asks first with Cancel focused, B backs out, only Switch applies; and the Menu's
 * Region button no longer saves anything by itself.
 */
class RegionPickerTest {
    private fun opened(current: String = "us") = RegionPicker().also { it.open(current) }

    @Test fun theListOpensOnTheRegionInUse() {
        val p = opened("eu")
        assertEquals(RegionPicker.Stage.LIST, p.stage)
        assertEquals(listOf("us", "eu", "kr", "tw"), p.regions)
        assertEquals(1, p.cursor)
    }

    @Test fun browsingChangesNothingAndDoesNotWrap() {
        val p = opened("us")
        assertEquals(RegionPicker.Result.None, p.move(0, -1))
        assertEquals(0, p.cursor)
        repeat(6) { assertEquals(RegionPicker.Result.None, p.move(0, 1)) }
        assertEquals(3, p.cursor)
        assertEquals(RegionPicker.Stage.LIST, p.stage)
        assertNull(p.target)
    }

    @Test fun aOnTheRegionInUseClosesWithNoChange() {
        val p = opened("us")
        assertEquals(RegionPicker.Result.Closed, p.a())
        assertFalse(p.open)
    }

    @Test fun bInTheListClosesWithNoChange() {
        val p = opened("us")
        p.move(0, 1)
        assertEquals(RegionPicker.Result.Closed, p.b())
        assertFalse(p.open)
    }

    @Test fun anotherRegionAsksFirstWithCancelFocused() {
        val p = opened("us")
        p.move(0, 1)
        assertEquals(RegionPicker.Result.Ask("eu"), p.a())
        assertEquals(RegionPicker.Stage.CONFIRM, p.stage)
        assertEquals("eu", p.target)
        assertFalse(p.switchFocused)
        assertEquals(RegionPicker.Preview.CHECKING, p.preview)
        // A on the default focus is Cancel: back to the list, the cursor where it was, nothing applied
        assertEquals(RegionPicker.Result.None, p.a())
        assertEquals(RegionPicker.Stage.LIST, p.stage)
        assertEquals(1, p.cursor)
    }

    @Test fun bOnTheCardIsCancel() {
        val p = opened("us")
        p.pick(2)
        assertEquals(RegionPicker.Result.None, p.b())
        assertEquals(RegionPicker.Stage.LIST, p.stage)
        assertEquals(RegionPicker.Result.Closed, p.b())
        assertFalse(p.open)
    }

    @Test fun onlySwitchApplies() {
        val p = opened("us")
        p.move(0, 1)
        p.a()
        p.move(1, 0)
        assertTrue(p.switchFocused)
        p.move(-1, 0)
        assertFalse(p.switchFocused)
        p.move(1, 0)
        assertEquals(RegionPicker.Result.Apply("us", "eu"), p.a())
        assertFalse(p.open)
    }

    @Test fun aTouchOnARowAsksAndTheCardsButtonsAnswer() {
        val p = opened("eu")
        assertEquals(RegionPicker.Result.Ask("us"), p.pick(0))
        assertEquals(RegionPicker.Result.Apply("eu", "us"), p.switch())
        val q = opened("eu")
        q.pick(3)
        assertEquals(RegionPicker.Result.None, q.cancel())
        assertEquals(RegionPicker.Stage.LIST, q.stage)
        assertEquals(RegionPicker.Result.Closed, q.pick(1))         // the region in use: no change
    }

    @Test fun switchingBackIsOneChoiceFromTheSameList() {
        // EU picked by mistake: back to US is the same list, one row up, one Switch (never three presses through Korea and Taiwan)
        val p = opened("eu")
        p.move(0, -1)
        assertEquals(RegionPicker.Result.Ask("us"), p.a())
        p.move(1, 0)
        assertEquals(RegionPicker.Result.Apply("eu", "us"), p.a())
    }

    // ---------------------------------------------------------------- the Menu's Region button

    private fun menu(start: String): MutableList<String> {
        val saved = ArrayList<String>()
        var code = start
        StartMenu.region = { code }
        StartMenu.onRegion = { saved += it; code = it }
        StartMenu.regionPreview = null
        StartMenu.open = true
        return saved
    }

    @Test fun theRegionButtonOpensTheListAndSavesNothing() {
        val saved = menu("us")
        try {
            StartMenu.press(StartMenu.REGION)
            assertTrue(StartMenu.picker.open)
            assertEquals(StartMenu.ROW0, StartMenu.focusId())
            // the old button stepped (and saved) on every press: pressing again browses, never saves
            repeat(4) { StartMenu.pickerMove(0, 1) }
            assertTrue(saved.isEmpty())
            assertTrue(StartMenu.open)
        } finally { StartMenu.open = false; StartMenu.onRegion = null }
    }

    @Test fun theMenuStaysUntilSwitchAndBBacksOutWithNoChange() {
        val saved = menu("us")
        try {
            StartMenu.press(StartMenu.REGION)
            StartMenu.pickerMove(0, 1)
            StartMenu.pickerA()                                    // the card
            assertEquals(RegionPicker.Stage.CONFIRM, StartMenu.picker.stage)
            assertEquals(StartMenu.REGION_CANCEL, StartMenu.focusId())
            // no launcher check: the card warns of a download
            assertEquals(RegionPicker.Preview.UNKNOWN, StartMenu.picker.preview)
            assertTrue(StartMenu.back())                           // B = Cancel
            assertTrue(StartMenu.back())                           // B = the list shuts
            assertFalse(StartMenu.back())                          // B again: the host shuts the Menu
            assertTrue(StartMenu.open)
            assertTrue(saved.isEmpty())
            StartMenu.press(StartMenu.REGION)
            StartMenu.press(StartMenu.ROW0 + 1)                    // a touch on EU
            assertTrue(StartMenu.open)
            assertTrue(saved.isEmpty())
            StartMenu.press(StartMenu.REGION_SWITCH)
            assertEquals(listOf("eu"), saved)
            assertFalse(StartMenu.open)
            assertFalse(StartMenu.picker.open)
        } finally { StartMenu.open = false; StartMenu.onRegion = null }
    }

    @Test fun theCardsLineFollowsTheLaunchersAnswer() {
        menu("us")
        var answer: ((RegionPicker.Preview) -> Unit)? = null
        StartMenu.regionPreview = { code, a -> assertEquals("kr", code); answer = a }
        try {
            StartMenu.press(StartMenu.REGION)
            StartMenu.press(StartMenu.ROW0 + 2)
            assertEquals(RegionPicker.Preview.CHECKING, StartMenu.picker.preview)
            assertEquals("Checking...", StartMenu.previewLine("kr", StartMenu.picker.preview))
            answer!!(RegionPicker.Preview.INSTALL)
            assertEquals("Uses this install", StartMenu.previewLine("kr", StartMenu.picker.preview))
            assertEquals("Downloads the Korea game", StartMenu.previewLine("kr", RegionPicker.Preview.DOWNLOAD))
            // an answer for a card no longer shown changes nothing
            StartMenu.back()
            answer!!(RegionPicker.Preview.DOWNLOAD)
            assertNull(StartMenu.picker.target)
        } finally { StartMenu.open = false; StartMenu.onRegion = null; StartMenu.regionPreview = null }
    }

    @Test fun shuttingTheMenuShutsTheList() {
        menu("us")
        StartMenu.press(StartMenu.REGION)
        StartMenu.open = false
        assertFalse(StartMenu.picker.open)
        StartMenu.onRegion = null
    }

    // ---------------------------------------------------------------- the setup page's Stop

    @Test fun aRegionsDownloadOffersStop() {
        val base = SetupFlow.Inputs(path = "/g", folderFound = true, art = SetupFlow.Art.READY, busy = "Downloading Wow.exe (1/3)...",
            updating = true, gameOutdated = "1.60.1.70862", loginSaved = true)
        val s = SetupFlow.screen(base.copy(regionSwitch = "EU"))
        assertEquals("Switching to EU", s.prompt?.title)
        assertEquals(listOf(SetupFlow.Button("Stop", SetupFlow.Action.STOP_UPDATE)), s.prompt?.buttons)
        assertEquals(SetupFlow.Button("Stopping...", SetupFlow.Action.STOP_UPDATE, false),
            SetupFlow.screen(base.copy(regionSwitch = "EU", stopping = true)).prompt?.buttons?.single())
        // an update of the install's own build: no Stop (as before)
        assertEquals("client", SetupFlow.screen(base).prompt?.id)
    }
}
