package com.winlator.winhandler;

import com.winlator.inputcontrols.GamepadState;

/**
 * DS Forever: player 1's state as the game gets it (WinHandler.holdOut's body; WinHandler loads a native library at its class init, so
 * the off-device tests reach this one alone - MenuTriggersMarkTest). [s] itself, or a copy with hold to cast's forced controls up and its
 * kept controls down, the fire's mark on every free trigger (fix/hold-tweaks), and the DS menu's ZL / ZR hold-back (the 1536 DS menu).
 */
final class PadOut {
    private PadOut() {}

    static GamepadState holdOut(GamepadState s) {
        int m, k, mk;
        float free;
        boolean trig;
        try {
            m = com.jrees.wowcompanion.HoldCast.forcedMask();
            k = com.jrees.wowcompanion.HoldCast.keptMask();
            mk = com.jrees.wowcompanion.HoldCast.markCode();
            free = com.jrees.wowcompanion.HoldCast.TRIG_FREE;
            // DS Forever, the 1536 DS menu: a room on single screen mode's menu stage owns ZL / ZR - their pull is not the game's
            trig = com.jrees.wowcompanion.SetupBridge.INSTANCE.getRoomTriggers();
        } catch (Throwable t) {
            return s;
        }
        if (s == null) return s;
        boolean band = (s.triggerL > 0f && s.triggerL < free) || (s.triggerR > 0f && s.triggerR < free);
        if (m == 0 && k == 0 && mk == 0 && !band && !trig) return s;
        GamepadState o = new GamepadState();
        o.copy(s);
        // int/1536: the menu's hold-back and the fire's mark share the triggers - a held-back trigger is FREE for the game (no pull), so
        // the launcher's code goes on it as on any free one (HoldCast.trigOut): a mark mid-hold is never zeroed away, and the code (under
        // TRIG_FREE, far below the game's trigger button) is never a ZL / ZR press
        o.triggerL = com.jrees.wowcompanion.HoldCast.trigOut(s.triggerL, trig, mk);
        o.triggerR = com.jrees.wowcompanion.HoldCast.trigOut(s.triggerR, trig, mk);
        o.buttons = (short) ((s.buttons | (k & 0x0D)) & ~(m & 0x0D));
        // (after the buttons: they are made from s, whose L2 / R2 bits are the player's pull)
        if (trig) {
            o.setPressed(com.winlator.inputcontrols.ExternalController.IDX_BUTTON_L2, false);
            o.setPressed(com.winlator.inputcontrols.ExternalController.IDX_BUTTON_R2, false);
        }
        for (int i = 0; i < 4; i++) {
            if ((k & (1 << (4 + i))) != 0) o.dpad[i] = true;
            if ((m & (1 << (4 + i))) != 0) o.dpad[i] = false;
        }
        return o;
    }
}
