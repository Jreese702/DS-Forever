package com.jrees.wowcompanion

/**
 * WHICH ICON AN NPC PIN WEARS (the user, 2026-10-12, via Tracking: "I hate that I cant tell what half of the vendors are. I would
 * rather them be like a bag ... to indicate vendors and then the anvil for vendors with repair. just the normal vanilla minimap
 * Icons and then class and proffession trainers are the only ones with the special ones but give the class trainers the book Icon
 * with theirs"; the signed-off picture E:/wc-scratch-c9979a42/icons2/npc_icons_proposal.png, picks "A: Sack", "Game's own four
 * icons"). [NpcKind] still says WHAT an NPC is (its subtitle first, its bits next); this maps that to a cell of the harvested strip
 * ui/npc_icons.png (MapHarvestSteps.NPC_ICONS: the game's own minimap tracking icons, the class icon with the trainer book, the
 * profession's icon):
 *   trainers: a class trainer's class (class_<c>), a profession trainer's profession (prof_<p>, else "profession"), every other
 *     trainer (pet, riding, weapon master, a class trainer without a class word) the plain trainer book;
 *   the services the game's tracking draws: mailbox, banker, auctioneer, flight master, innkeeper, stable master, battlemaster;
 *     the spirit healer (the green cross), the guild master and tabard designer (the tabard);
 *   vendors: one that repairs the anvil; food / drink, reagents, poisons, ammo the game's own four; every other vendor the sack;
 *   objects: forge, anvil (off by default in the filter); anything else the gold dot, never nothing.
 * Pure (tools/map_test).
 */
object NpcIcons {
    private val SERVICES = setOf("mailbox", "banker", "auctioneer", "flightmaster", "innkeeper", "stablemaster", "battlemaster",
        "spirit", "guild")

    /** the strip key for an NPC: [key] = NpcKind.key(flags, subtitle), [flags] its role bits (PoiStore's layout, mirrored in QdbPins) */
    fun icon(key: String, flags: Int): String {
        val trainer = flags and (QdbPins.TRAINER or QdbPins.TRAINER_CLASS or QdbPins.TRAINER_PROF) != 0
        if (trainer) return when {
            key.startsWith("class_") || key.startsWith("prof_") -> key
            key == "profession" -> "profession"
            else -> "trainer"
        }
        if (key in SERVICES) return key
        return when {
            key == "tabard" -> "guild"
            key == "forge" -> "forge"
            flags and QdbPins.REPAIR != 0 -> "repair"
            flags and QdbPins.ANVIL != 0 && flags and QdbPins.VENDOR == 0 -> "anvil"
            key == "food" || key == "drink" -> "food"
            key == "reagents" -> "reagents"
            key == "poisons" -> "poisons"
            key == "ammo" || key == "ranged" -> "ammo"
            flags and QdbPins.VENDOR != 0 -> "vendor"
            else -> "generic"
        }
    }
}
