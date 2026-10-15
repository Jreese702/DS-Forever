package com.jrees.wowcompanion

/**
 * Cast's fight face (CastView.fightFace / fightFaceOn, Cast's cast-fightface): the base set's X / Y / A (positions 5 / 6 / 8) -> the icon
 * of the spell each casts in a fight (PadMapModel's Slot.fight; the user 2026-10-01 "Yes, per button", fight-only form "Try the deeper
 * route", PadMap m9). PER POSITION only while that button is ARMED (PadMapModel.Map.faceArmed: during this fight its key was switched and
 * read back pointing at the companion's spell button): the user, never a spell picture on a button that would jump. The HOST sets
 * fightFaceOn (Cast: never a constant): on only once Spells has proven on the device that A casts in a fight ([PROVEN]) AND a face is armed.
 */
object HomeFightFace {
    /** the set whose face buttons are the game's fixed X / Y / B / A (no trigger held) */
    private const val BASE_SET = 1

    /** Spells' device proof that a base A / X / Y casts its fight spell: PROVEN on the Thor 2026-10-01 06:52-06:57 (3 fights, A Lightning
     *  Bolt 9 presses + X Earth Shock 7 reached the companion's buttons, every key restored at fight end, 0 blocked / errors; tracking's
     *  word under the user's delegation). The per-button armed gate still decides each picture */
    const val PROVEN = true

    /** b-combat-bar-states: the base set's game position -> the spell id of its In-combat spell (X / Y / A's fight spell; in a fight the
     *  D-pad's overlay one), for the state the add-on sends by spell id (hudS.fs) */
    fun ids(map: PadMapModel.Map, fight: Boolean = map.fight): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for (pos in PadMapModel.FACES) PadMapModel.slot(map, BASE_SET, pos)?.fight?.id?.takeIf { it > 0 }?.let { out[pos] = it }
        if (fight) for (pos in 1..4) PadMapModel.slot(map, BASE_SET, pos)?.takeIf { it.kind == PadMapModel.Kind.COMBAT && it.ov }
            ?.combat?.id?.takeIf { it > 0 }?.let { out[pos] = it }
        return out
    }

    fun icons(map: PadMapModel.Map): Map<Int, Int> =
        PadMapModel.FACES.filter { it in map.faceArmed }.mapNotNull { pos ->
            PadMapModel.slot(map, BASE_SET, pos)?.fight?.icon?.takeIf { it != 0 }?.let { pos to it }
        }.toMap()

    /**
     * THE D-PAD'S IN-COMBAT SPELLS (Track C, after test15): IN A FIGHT (the padmap's `combat`), base-set D-pad position 1..4 -> the icon
     * of its In-combat spell (PadMap m43/m44's overlay: a COMBAT slot with `ov`). Out of combat: none (the D-pad is DS Forever's then).
     */
    fun dpadIcons(map: PadMapModel.Map, fight: Boolean = map.fight): Map<Int, Int> = if (!fight) emptyMap() else
        (1..4).mapNotNull { pos ->
            PadMapModel.slot(map, BASE_SET, pos)?.takeIf { it.kind == PadMapModel.Kind.COMBAT && it.ov }?.combat?.icon?.takeIf { it != 0 }?.let { pos to it }
        }.toMap()

    /**
     * THE COMPANION'S COMMANDS ON THE BAR (Track C, the user after test17: the Spell Radial on ZL+ZR + Y worked but "The button was not
     * there"): a command is the router's OVERLAY (PadMap m41, no macro, nothing in the game's slot), so the game's bar shows that slot
     * empty. Every set's command slot -> its icon, keyed [cmdKey] (set 1..4, position 1..8); in and out of fights alike.
     */
    fun commandIcons(map: PadMapModel.Map): Map<Int, Int> =
        map.sets.flatMap { st ->
            st.slots.filter { it.kind == PadMapModel.Kind.COMMAND && st.set in 1..4 && it.pos in 1..8 }.mapNotNull { sl ->
                (PadMapModel.commandOn(map, sl)?.icon?.takeIf { it != 0 } ?: sl.icon.takeIf { it != 0 })?.let { cmdKey(st.set, sl.pos) to it }
            }
        }.toMap()

    fun cmdKey(set: Int, pos: Int) = (set - 1) * 8 + (pos - 1)

    /** the pictures may show: proven, and at least one base face is armed with a fight spell */
    fun on(map: PadMapModel.Map, proven: Boolean = PROVEN): Boolean = proven && icons(map).isNotEmpty()

    /**
     * B'S GIVE-BACK (the user's pick 2026-10-01: "Out of a fight, B also gives A / X / Y back"): when B has just shrunk the big
     * controller, OUT of a fight, with fight spells on the buttons, Home asks the add-on (Spells' give-back code) to hand A / X / Y
     * back to the game until the next start. In a fight never: the game locks key changes, and B only shrinks.
     */
    fun giveBack(shrank: Boolean, fighting: Boolean, armed: Boolean): Boolean = shrank && !fighting && armed

    /**
     * THE FIGHT FACES' COOLDOWNS (the user 2026-10-01: Earth Shock "does not look like it goes on cooldown"; the pick "The game's dark
     * sweep"): one CURRENT `facecd` message (the WHOLE state, Spells' PadMap m12) arriving at [arrivalMs] (elapsedRealtime) -> for EVERY
     * face X / Y / A the (start, length) Cast's fightCooldown takes; a face not in it gets (0, 0) = cleared.
     */
    fun cooldowns(cds: Map<Int, PadMapModel.FaceCd>, arrivalMs: Long): Map<Int, Pair<Long, Long>> =
        PadMapModel.FACES.associateWith { pos ->
            cds[pos]?.let { PadMapModel.cdStartMs(it, arrivalMs) to PadMapModel.cdDurMs(it) } ?: (0L to 0L)
        }
}
