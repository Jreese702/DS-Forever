package com.jrees.wowcompanion

/**
 * TRAINERS FOREVER REPLACED (pure: tools/map_test runs it off-device).
 *
 * The user, 2026-10-06 via tracking: AzerothCore "showed trainers that were in the wrong places", and picked "Pins for trainers
 * who aren't there any more (Dun Morogh)". Map's check: the class trainers the user MET in Dun Morogh (Teo Hammerstorm in Coldridge,
 * Ingrid Dunwald in Kharanos, both Forever-new) stand where the classic class trainers stood, and both AzerothCore and QuestieDB
 * still list the classic ones there. The rule: a CLASS trainer the player has met that the old data does not have (a harvested row
 * with neither "e" nor "mv") hides the CLASS trainer pins within [RADIUS_YD] that the player has never met. Profession trainers and
 * every other pin stay; where the player has met no such trainer nothing changes.
 */
object ReplacedTrainers {
    const val RADIUS_YD = 30.0

    /** a met class trainer the old data lacks, at ([x], [y]) on this map */
    class Met(val x: Double, val y: Double, val name: String?)

    /**
     * Should a pin at ([x], [y]) with bundle-style [flags] and [name], [confirmed] = the player has met it, be hidden? [ax] / [ay] =
     * the yards across / down the map's 0..1.
     */
    fun hidden(x: Double, y: Double, flags: Int, name: String, confirmed: Boolean, met: List<Met>, ax: Double, ay: Double): Boolean {
        if (confirmed || met.isEmpty() || flags and QdbPins.TRAINER_CLASS == 0) return false
        return met.any { m -> m.name != name && Math.hypot((m.x - x) * ax, (m.y - y) * ay) <= RADIUS_YD }
    }
}
