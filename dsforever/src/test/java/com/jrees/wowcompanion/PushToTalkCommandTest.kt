package com.jrees.wowcompanion

import com.jrees.wowcompanion.HoldTalk.Out
import com.jrees.wowcompanion.HoldTalk.St
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * hub58 / PadMap m170 (the user's picks, 2026-11-17: two new bindable DS commands, "Push to talk (hold)" and "Polestar menu"),
 * off-device: the hold machine for a bound button (its own release, a tap does nothing, the press time counts), finding the button
 * from the app's reader, the Controller room's Y steps, the routes, the kit pictures and the host's wiring (read from the source).
 * The add-on side is tools/test_ptt_bind.py.
 */
class PushToTalkCommandTest {
    private fun src(name: String): String = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }.let { File(it, name).readText() }

    @Test fun theButtonIsTheLastOneStillHeldPressedLately() {
        assertNull("nothing held: a tap", HoldTalk.pttSource(emptyMap(), 5000L))
        // ZL held first (a modifier), then X: X is the button
        assertEquals("PAD3" to 4800L, HoldTalk.pttSource(mapOf(DirectPadKeys.LT to 4600L, "PAD3" to 4800L), 5000L))
        // a key held for ages (the stick pushed in a while ago) is not it
        assertNull(HoldTalk.pttSource(mapOf("PADLSTICK" to 1000L), 1000L + HoldTalk.PTT_WINDOW_MS + 1L))
    }

    @Test fun aBoundHoldRecordsAndItsOwnReleaseStopsIt() {
        val h = HoldTalk()
        assertTrue(h.down(1000L, false, "PAD3", isPtt = true))
        assertTrue(h.ptt); assertEquals("PAD3", h.src)
        assertTrue(h.wantsGuest)
        // the word came 200 ms after the press: the hold still counts from the press
        assertEquals(Out.NONE, h.tick(1000L + HoldTalk.HOLD_MS - 1L))
        assertEquals(Out.LISTEN, h.tick(1000L + HoldTalk.HOLD_MS))
        assertEquals(Out.TAB_R, h.key(1500L, HoldTalk.DOWN))
        assertEquals(Out.STOP, h.up(2000L))
        assertEquals(St.WORKING, h.st)
        assertEquals(Out.SHOW, h.heard(2300L, "pull now"))
        assertEquals(Out.SEND, h.key(2400L, HoldTalk.A))
    }

    @Test fun aTapOfTheBoundButtonDoesNothingButGiveTheGuestBack() {
        val h = HoldTalk()
        assertTrue(h.down(1000L, false, "PAD3", isPtt = true))
        assertFalse("the router's word is not a Chat room", h.word(1100L))
        assertEquals("no Chat room: the guest goes back", Out.FREE, h.up(1200L))
        assertEquals(St.IDLE, h.st)
        // the toolbar's own tap still opens the Chat room
        val t = HoldTalk(); t.down(1000L, false); t.word(1100L)
        assertEquals(Out.OPEN_CHAT, t.up(1200L))
        assertEquals(HoldTalk.LEFT_KEY, t.src); assertFalse(t.ptt)
    }

    @Test fun sendStraightAwayFollowsTheSameSetting() {
        val h = HoldTalk(); h.down(0L, true, "PAD4", isPtt = true); h.tick(HoldTalk.HOLD_MS); h.up(800L)
        assertEquals(Out.SEND, h.heard(900L, "omw"))
    }

    @Test fun yStepsTheChatsSettings() {
        assertEquals(true to false, HoldTalk.nextSetting(false, false))
        assertEquals(true to false, HoldTalk.nextSetting(false, true))
        assertEquals(true to true, HoldTalk.nextSetting(true, false))
        assertEquals(false to true, HoldTalk.nextSetting(true, true))
    }

    @Test fun theRoutesAndTheHubKeys() {
        assertEquals(BarCommands.Route.HOST, BarCommands.route(BarCommands.TALK))
        assertEquals(BarCommands.Route.HOST, BarCommands.route(BarCommands.PS_MENU))
        val hub = listOf(File("../companion/app/src/main/assets/companion/addons/WowCompanionHub/Hub.lua"),
            File("companion/app/src/main/assets/companion/addons/WowCompanionHub/Hub.lua")).first { it.canRead() }.readText()
        assertTrue(BarCommands.hubKeys(hub).containsAll(listOf(BarCommands.TALK, BarCommands.PS_MENU)))
    }

    @Test fun theCommandsDrawTheKitsPictures() {
        assertEquals(UiKit.CMD_PTT_ART, PadMapModel.kitArt(PadMapModel.PTT_KEY))
        assertEquals("ricon_quests", PadMapModel.kitArt(PadMapModel.PS_MENU_KEY))
        assertEquals("home_live_base", PadMapModel.kitArt(PadMapModel.HOME_KEY))
        assertNull(PadMapModel.kitArt("DSCHAT"))
        val m = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m170","m":{"page":1,"pages":3,"sets":[{"set":1,"slots":[
            {"pos":3,"s":3,"kind":"command","key":"DSPTT","n":"Push to talk (hold)","i":134332,"b":"/click WowCompanionPadTalk","ov":true},
            {"pos":2,"s":2,"kind":"command","key":"DSPSMENU","n":"Polestar menu","i":134269,"b":"/click WowCompanionPadPolestarMenu","ov":true}]}],
            "commands":[{"key":"DSPTT","n":"Push to talk (hold)","i":134332,"made":true,"b":"/click WowCompanionPadTalk"},
            {"key":"DSPSMENU","n":"Polestar menu","i":134269,"made":true,"b":"/click WowCompanionPadPolestarMenu"}]}}"""))!!
        val icons = HomeFightFace.commandIcons(m)
        assertEquals(CastView.KIT_PTT, icons[HomeFightFace.cmdKey(1, 3)])
        assertEquals(CastView.KIT_PS_MENU, icons[HomeFightFace.cmdKey(1, 2)])
    }

    @Test fun theHostWiresBothCommands() {
        val h = src("CompanionHost.kt")
        assertTrue(h.contains("BarCommands.TALK -> talkCommand()"))
        assertTrue(h.contains("BarCommands.PS_MENU -> polestarMenuCommand()"))
        // the bound button's release ends the hold, whichever button it is (the talk registration and the unclaimed path)
        val reg = h.substring(h.indexOf("DirectPad.Reg(\"talk\""))
        assertTrue(reg.substringBefore("padUnclaimed(k, true)").contains("k == holdTalk.src && !down -> talkUp()"))
        assertTrue(reg.substringBefore("wantsRelease").contains("\"PADLSTICK\", \"PADRSTICK\""))
        assertTrue(h.contains("if (!down && k == holdTalk.src) talkUp()"))
        // the button comes from the app's own reader, the hold from its press; not tied to the toolbar's switch
        val cmd = h.substring(h.indexOf("private fun talkCommand()")).substringBefore("\n    }\n")
        assertTrue(cmd.contains("HoldTalk.pttSource(DirectPad.heldSince(), now)"))
        assertTrue(cmd.contains("src.first, isPtt = true"))
        assertFalse("binding it is the opt-in", cmd.contains("HomeSettings.holdTalk("))
        assertTrue(cmd.contains("HomeSettings.talkSendNow(GameUi.character)"))
        assertTrue(h.contains("HoldTalk.Out.FREE -> {"))
        // the Polestar menu: over the Map room, else the Quests room's tab; no Polestar: the short refusal
        val ps = h.substring(h.indexOf("private fun polestarMenuCommand()")).substringBefore("\n    }\n")
        assertTrue(ps.contains("worldMapRoom.openPolestarMenu()"))
        assertTrue(ps.contains("questsView.openPolestar(path = mapView.polestarChoose())"))
        assertTrue(ps.contains("PolestarSkip.NO_STEP_LINE"))
        // the reader keeps each held key's press time
        assertTrue(src("DirectPad.kt").contains("if (down) downAt[k] = android.os.SystemClock.uptimeMillis() else downAt.remove(k)"))
    }
}
