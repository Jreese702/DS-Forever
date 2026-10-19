package com.jrees.wowcompanion

/**
 * THE DANGER A ROUTE WEIGHS, PER CHARACTER (the user: "danger maps are going to change faction to faction"; "Mobs are a cost, not a
 * wall"), shared by the device and the off-device harness (moved verbatim out of RenderSafePath): a zone's spawns sided by their town,
 * WoW Forever's own placeholder-level people and a zone's own peoples friendly to all made the character's side's, the danger grid of
 * those hostile to the character with the zone's typical danger taken off, and a narrow stream's wade and a building's rooms as a
 * middling danger.
 */
object DangerInputs {
    /** a narrow stream waded (SafePath.NARROW_DEPTH) costs as the mobs' middling danger does: a drier way wins where there is one */
    const val WADE_DANGER = 2f
    /** a building's room footprint costs as the mobs' middling danger: a route goes round a house unless its goal is in it */
    const val BUILDING_DANGER = 2f
    /** WoW Forever's own NPCs QuestieDB keeps at a placeholder level (9999) that attack both sides by its data: at or over this, friendly
     *  (the device's rule for every zone; the harness reads its spec's) */
    const val FRIENDLY_MIN_LEVEL = 100
    /** a zone's own peoples the guides call friendly to all, by uiMap (our words: regexes over QuestieDB's names) - the harness reads
     *  its spec's "friendlyNames", the same lists */
    val FRIENDLY_NAMES: Map<Int, List<Regex>> = mapOf(
        2521 to listOf("^Windshaper", "^High Order"),                                                     // Zephras Isle
        2652 to listOf("^Shen'dral", "^Pylon Protector$", "^Shard Guard$", "^Magister ", "^Mordent Evenshade$",
            "^(Adarien|Mithera|Liranne|Arondel|Kerelle|Jarael)$", "^Gelkis Captive$"),                     // Shen'dralar
        1450 to listOf("^Orokai$", "^Elaren Stargrove$", "^Cenarion Warden$", "^Slumbering Druid$"),      // Moonglade
        2548 to listOf("^Sentry ", "^Lieutenant (Maclan|Prenish|Shelby)$", "^Cavalry ", "^Farholde ", "^Brother Lowley$",
            "^Wounded Soldier$", "^(Tessa|Gray) Dawnbright$", "^Barracks Master Harlan$", "^Scoutmaster Vargas$", "^Scout Bolain$",
            "^Watcher Cornelius$", "^Sergeant Riftan$", "^Innkeeper Zizplink$", "^Wharfmaster Whizzbuzz$", "^Fimbo Greasemitz$",
            "^Tallow Sparksocket$", "^Thrash$", "^Spek$", "^Jasper Geartoggle$", "^Mizzy$", "^Mia Tanglewrench$", "^Argo Brinewhistle$",
            "^Rex Hardwire$", "^Sleebo Fizzlespout$", "^Meadowsbrook Farmhand$", "^Turner's Mill Lumberjack$", "^Woodworker$",
            "^Halligan Turner$", "^Benjy Wheeler$", "^(Robbie|Eleanor) Holmsby$", "^Dunston Willis$", "^Cedric Dalton$", "^Melanie Sable$",
            "^Wheatley$", "^Steed$"),                                                                       // the Riverglades
    ).mapValues { (_, l) -> l.map { Regex(it) } }

    /**
     * a zone's spawns ([spawns], its own: x, y in its map's 0..1 over world [rect]) as the character on side [foe]'s other one sees them:
     * a town's faction-less people take the side of the faction-tagged ones round them (QuestieNpcs.townSided), and those at
     * [friendlyMin] or over or named by [friendlyNames] are made the character's side's
     */
    fun sided(spawns: List<QuestieNpcs.Spawn>, rect: MapHarvest.Rect, foe: Int, friendlyMin: Int, friendlyNames: List<Regex>): List<QuestieNpcs.Spawn> {
            val ax = rect.l - rect.r; val ay = rect.t - rect.b
            // a town's faction-less people (no level, or a guard's name) take the side of the faction-tagged ones round them (QuestieNpcs.townSided)
            val here = QuestieNpcs.townSided(spawns) { sp -> doubleArrayOf(rect.t - sp.y * ay, rect.l - sp.x * ax) }.map { sp -> if ((sp.minLv >= friendlyMin || friendlyNames.any { it.containsMatchIn(sp.name) }) && sp.hostile != foe)
                QuestieNpcs.Spawn(sp.area, sp.x, sp.y, sp.minLv, sp.maxLv, sp.elite, foe, sp.name, sp.sub) else sp }
            return here
    }

    /** the spawns of [here] hostile to side [mine], in world x, y */
    fun hostile(here: List<QuestieNpcs.Spawn>, rect: MapHarvest.Rect, mine: Int): List<DoubleArray> {
        val ax = rect.l - rect.r; val ay = rect.t - rect.b
        return here.filter { it.hostile == mine || it.hostile == 3 }.map { doubleArrayOf(rect.t - it.y * ay, rect.l - it.x * ax) }
    }

    /**
     * the costs a route weighs per yard in zone [rect]: the danger grid [g] (its 0..1 map) less its [typical] danger, plus a narrow
     * stream's wade ([wading]: SafePath.Water.wading) and a building's rooms ([rooms]) - [danger]; the same with no mobs - [calm]
     */
    class Costs(val g: DangerGrid, val typical: Float, rect: MapHarvest.Rect, wading: (Double, Double) -> Boolean, rooms: (Double, Double) -> Boolean) {
        private val ax = rect.l - rect.r; private val ay = rect.t - rect.b; private val t = rect.t; private val l = rect.l
        val danger: (Double, Double) -> Float = { x, y -> maxOf(0f, g.at((l - y) / ax, (t - x) / ay) - typical) + (if (wading(x, y)) WADE_DANGER else 0f) +
            (if (rooms(x, y)) BUILDING_DANGER else 0f) }
        val calm: (Double, Double) -> Float = { x, y -> (if (wading(x, y)) WADE_DANGER else 0f) + (if (rooms(x, y)) BUILDING_DANGER else 0f) }
    }

    /** the danger grid of [here] for a character of side [mine] at level [level] over zone [rect], and its typical danger over the cells
     *  [own] (world x, y: the zone's own ground) */
    fun costs(here: List<QuestieNpcs.Spawn>, mine: Int, level: Int, rect: MapHarvest.Rect, own: (Double, Double) -> Boolean,
              wading: (Double, Double) -> Boolean, rooms: (Double, Double) -> Boolean, ground: ((Double, Double) -> Float)? = null): Costs {
        val ax = rect.l - rect.r; val ay = rect.t - rect.b
        val g = DangerGrid.build(here, mine, level, ax, ay, ground)
        // the zone's typical danger taken off (DangerGrid.typical: an all-red zone steers round its worst camps only)
        val typical = g.typical { u, v -> own(rect.t - v * ay, rect.l - u * ax) }
        return Costs(g, typical, rect, wading, rooms)
    }
}
