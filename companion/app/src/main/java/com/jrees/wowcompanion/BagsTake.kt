package com.jrees.wowcompanion

/**
 * Bags' take and its ONE check (BagsView.touchedIn / takeCheck / stopAsking), pure (RoomLeaveStopsTest). Home's consistency pass,
 * 2026-10-06 (the user: a room holds the controller the WHOLE time it shows): no bags.ctl on [BagsView.TAKE_CHECK_MS] after the take =
 * one more take on bmvq 29. Since 2026-11-16 the room's leave ends it at once on every route ([stop]; the user: "after opening the bags
 * from the Dpad select, and then pressing be to close has a weird behavior where it reopens again"): up to 1.0.33 only the close cells
 * ended it, and the B route puts those off, so a check inside that wait took the controller for a room already gone.
 */
class BagsTake {
    /** the room was entered and wants the controller (from [enter] until [stop]) */
    var wantIn = false
        private set
    private var retried = false

    /** a route into the room: true = a take is tapped now and checked later; false = the room holds already (nothing tapped) */
    fun enter(ctlOn: Boolean): Boolean {
        wantIn = true; retried = false
        return !ctlOn
    }

    /** the check, [BagsView.TAKE_CHECK_MS] after [enter]: true = ONE more take (bmvq 29) */
    fun check(ctlOn: Boolean): Boolean {
        if (!wantIn || ctlOn || retried) return false
        retried = true
        return true
    }

    /** the room is left (any route): no take for it any more */
    fun stop() { wantIn = false }
}
