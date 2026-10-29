package com.jrees.wowcompanion

import android.app.Activity
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * THE CONTROLLER ON THE START-UP PAGES, THROUGH THE DEVICE'S OWN CHAIN (the user on the Thor, 2026-10-28: "Controller inputs need to be
 * able to change the Local vs Streaming mode"; A on Update did nothing). A game controller's KeyEvent goes the way the launcher sends
 * it ([StartKeys.route]: the chooser's ModeBridge.key, then the setup's SetupBridge.key) to real panels in a window, AFTER the bottom's
 * host was rebuilt (the new panels attached, then the old ones detached: the old detach used to clear the new panels' hold on the pad).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartKeysChainTest {
    private val passed = SetupFlow.Inputs(path = "/storage/emulated/0/WoW Forever", folderFound = true, loginSaved = true,
        art = SetupFlow.Art.READY, addonsInstalled = true, client = "1.60.1.70685 (up to date)")

    private fun route(r: StartMode.Route?) {
        ModeBridge::class.java.getDeclaredField("route").apply { isAccessible = true }.set(ModeBridge, r)
    }

    private fun pad(code: Int, action: Int): KeyEvent {
        val t = SystemClock.uptimeMillis()
        return KeyEvent(t, t, action, code, 0, 0, 1, 0, 0, InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD)
    }

    private fun press(code: Int): Boolean {
        val down = StartKeys.route(pad(code, KeyEvent.ACTION_DOWN))
        StartKeys.route(pad(code, KeyEvent.ACTION_UP))
        return down
    }

    private fun cellOf(p: ChooserPanel): StartMode.Cell =
        ChooserPanel::class.java.getDeclaredField("cell").apply { isAccessible = true }.get(p) as StartMode.Cell

    @After fun clean() { SetupBridge.publish(null); ModeBridge.reset() }

    @Test fun theChooserAnswersThePadAfterTheHostWasRebuilt() {
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(act); act.setContentView(root)
        route(StartMode.Route.Chooser)
        SetupBridge.publish(passed)
        // the old host's panels, then the rebuilt host's, then the old ones go (the order a recreate gives)
        val oldSetup = SetupPanel(act); val oldChooser = ChooserPanel(act)
        root.addView(oldSetup); root.addView(oldChooser)
        val setup = SetupPanel(act); val chooser = ChooserPanel(act)
        root.addView(setup); root.addView(chooser)
        root.removeView(oldChooser); root.removeView(oldSetup)
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, chooser.visibility)
        assertNotNull("the setup's hold on the pad survived the old panel's detach", SetupBridge.keyHandler)

        assertEquals(StartMode.Mode.LOCAL, cellOf(chooser).mode)
        assertTrue("D-pad right reaches the chooser", press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(StartMode.Mode.STREAM, cellOf(chooser).mode)
        assertTrue(press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(StartMode.Mode.LOCAL, cellOf(chooser).mode)
        // A: the card goes DOWN first, the pick comes StartPress.DOWN_MS later
        assertTrue(press(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(StartMode.Route.Chooser, ModeBridge.route)
        ShadowLooper.idleMainLooper(StartPress.DOWN_MS + 50, java.util.concurrent.TimeUnit.MILLISECONDS)
        val r = ModeBridge.route
        assertTrue("A picked Local: $r", r is StartMode.Route.Into && r.mode == StartMode.Mode.LOCAL)
    }

    @Test fun theSetupAnswersAOnUpdateAfterTheHostWasRebuilt() {
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(act); act.setContentView(root)
        route(StartMode.Route.Chooser)
        // the device that day: the game out of date (the "Game update needed" pop-up, Update boxed), the chooser held back
        SetupBridge.publish(passed.copy(client = "1.60.1.70658 (1.60.1.70685 needed)", gameOutdated = "1.60.1.70685"))
        val pressed = ArrayList<SetupFlow.Action>()
        SetupBridge.launcherActions = { a -> pressed += a }
        val old = SetupPanel(act); root.addView(old)
        val setup = SetupPanel(act); root.addView(setup)
        val chooser = ChooserPanel(act); root.addView(chooser)
        root.removeView(old)
        ShadowLooper.idleMainLooper()
        assertFalse("the chooser waits for the checks", chooser.visibility == View.VISIBLE)
        assertEquals(View.VISIBLE, setup.visibility)
        // the panel lays out its buttons when it draws
        setup.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        setup.layout(0, 0, 1240, 1080)
        setup.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(1240, 1080, android.graphics.Bitmap.Config.ARGB_8888)))
        assertTrue("A reaches the setup", press(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(listOf(SetupFlow.Action.UPDATE_GAME), pressed)
        SetupBridge.launcherActions = null
    }
}
