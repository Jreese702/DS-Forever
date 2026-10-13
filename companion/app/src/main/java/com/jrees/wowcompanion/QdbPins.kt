package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * QUESTIEDB'S PINS AND NODE SPOTS FOR ONE MAP (pure: tools/map_test runs it off-device against the org.json double).
 *
 * The user, 2026-10-06 via tracking: "Try QuestieDB's NPC + node data first". Quests' add-on (q21, the only QuestieDB reader) sends
 * a PAIR per uiMap when the player's map changes: "qdbpins" {m, set, svc, rares, obj} and "qdbnodes" {m, set, kinds, herb, ore,
 * chest}, both with 0..1 positions on that map. Map's probe found QuestieDB's service NPCs EQUAL to the AzerothCore bundle in
 * Westfall (counts and places within 0.2 yd), so its services feed the same pin layer: [poiBits] turns classic npcFlags into the
 * bundle's bit layout (poi_meta.json), and MapView's service layer, filter and icons work on them unchanged.
 *   - A document with "err" is no answer (the add-on's build broke): the caller keeps what it had.
 *   - Empty groups come as [] (an instance, a Forever-only zone): an empty answer is a real one, and clears the map's pins.
 *   - "set" pairs the two documents of one build; a re-send with the same set changed nothing.
 */
object QdbPins {
    // QuestieDB's classic npcFlags (its own enum; Quests' probe 2026-10-06, flagsFrom "Classic")
    const val Q_VENDOR = 4; const val Q_FLIGHT = 8; const val Q_TRAINER = 16; const val Q_SPIRIT = 32
    const val Q_INN = 128; const val Q_BANKER = 256; const val Q_AUCTION = 4096; const val Q_STABLE = 8192; const val Q_REPAIR = 16384
    // Quests.lua sends these too since 2026-10-12 (QDB_SERVICES): the guild master (PETITIONER), the tabard designer, the battlemaster
    const val Q_SPIRITGUIDE = 64; const val Q_PETITION = 512; const val Q_TABARD = 1024; const val Q_BATTLE = 2048

    // the bundle's bits (PoiStore / poi_meta.json; tools/map_test checks these against the asset)
    const val TRAINER = 16; const val TRAINER_CLASS = 32; const val TRAINER_PROF = 64; const val VENDOR = 128
    const val VENDOR_AMMO = 256; const val VENDOR_FOOD = 512; const val VENDOR_POISON = 1024; const val VENDOR_REAGENT = 2048
    const val REPAIR = 4096; const val FLIGHTMASTER = 8192; const val INNKEEPER = 65536; const val BANKER = 131072
    const val AUCTIONEER = 2097152; const val STABLEMASTER = 4194304; const val MAILBOX = 67108864; const val FORGE = 134217728
    const val ANVIL = 268435456
    const val SPIRIT = 16384; const val GUILD = 32768; const val BATTLEMASTER = 262144; const val TABARD = 524288

    private val CLASS_TRAINER = Regex("^(Warrior|Paladin|Hunter|Rogue|Priest|Shaman|Mage|Warlock|Druid|Pet) Trainer$")

    class Svc(val id: Int, val name: String, val sub: String, val bits: Int, val spirit: Boolean, val faction: String?,
              val x: Double, val y: Double)
    class Rare(val id: Int, val name: String, val rank: Int, val pts: List<DoubleArray>)
    class Pins(val m: Int, val set: Int, val svc: List<Svc>, val rares: List<Rare>,
               val mail: List<DoubleArray>, val forge: List<DoubleArray>, val anvil: List<DoubleArray>)
    /** one spot; [mask] bit i = kinds[i] */
    class Node(val x: Double, val y: Double, val mask: Int)
    class Nodes(val m: Int, val set: Int, val kinds: List<String>, val herb: List<Node>, val ore: List<Node>, val chest: List<Node>)

    /**
     * Classic npcFlags [f] (the whole value: gossip and quest-giver bits are ignored) and the subtitle [sub] -> the bundle's bits.
     * Classic has ONE trainer bit, so a class (or pet) trainer is told by its subtitle (by its [name] when it has none: "Rogue
     * Trainer"), as the bundle marks them; every other trainer is a profession one. The vendor kinds come from the subtitle too.
     */
    fun poiBits(f: Int, sub: String, name: String = ""): Int {
        var b = 0
        if (f and Q_VENDOR != 0) {
            b = b or VENDOR
            if (sub.contains("Reagent", ignoreCase = true)) b = b or VENDOR_REAGENT
            if (Regex("Food|Drink|Baker|Butcher|Cook|Fisherman", RegexOption.IGNORE_CASE).containsMatchIn(sub)) b = b or VENDOR_FOOD
            if (Regex("Bowyer|Gunsmith|Ammo|Arrow|Bullet", RegexOption.IGNORE_CASE).containsMatchIn(sub)) b = b or VENDOR_AMMO
            if (sub.contains("Poison", ignoreCase = true)) b = b or VENDOR_POISON
        }
        if (f and Q_TRAINER != 0) {
            val cls = CLASS_TRAINER.matches(sub) || (sub.isEmpty() && CLASS_TRAINER.matches(name))
            b = b or TRAINER or (if (cls) TRAINER_CLASS else TRAINER_PROF)
        }
        if (f and Q_REPAIR != 0) b = b or REPAIR
        if (f and Q_FLIGHT != 0) b = b or FLIGHTMASTER
        if (f and Q_INN != 0) b = b or INNKEEPER
        if (f and Q_BANKER != 0) b = b or BANKER
        if (f and Q_AUCTION != 0) b = b or AUCTIONEER
        if (f and Q_STABLE != 0) b = b or STABLEMASTER
        if (f and (Q_SPIRIT or Q_SPIRITGUIDE) != 0) b = b or SPIRIT
        if (f and Q_PETITION != 0) b = b or GUILD
        if (f and Q_TABARD != 0) b = b or TABARD
        if (f and Q_BATTLE != 0) b = b or BATTLEMASTER
        return b
    }

    private fun xy(a: JSONArray?, i: Int): DoubleArray? {
        val p = a?.optJSONArray(i) ?: return null
        val x = p.optDouble(0); val y = p.optDouble(1)
        return if (x.isNaN() || y.isNaN()) null else doubleArrayOf(x, y)
    }
    private fun points(a: JSONArray?): List<DoubleArray> {
        if (a == null) return emptyList()
        val out = ArrayList<DoubleArray>(a.length())
        for (i in 0 until a.length()) xy(a, i)?.let { out.add(it) }
        return out
    }

    /** a "qdbpins" document -> its pins; null = no answer (an error, or no map) */
    fun pins(d: JSONObject): Pins? {
        if (d.has("err") || !d.has("m")) return null
        val svc = ArrayList<Svc>()
        d.optJSONArray("svc")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val f = e.optInt("f", 0)
                val sub = e.optString("s", "")
                val name = e.optString("n", "")
                val bits = poiBits(f, sub, name)
                val spirit = f and Q_SPIRIT != 0
                if (bits == 0 && !spirit) continue                   // only gossip / quest bits: not a service
                val x = e.optDouble("x", Double.NaN); val y = e.optDouble("y", Double.NaN)
                if (x.isNaN() || y.isNaN()) continue
                svc.add(Svc(e.optInt("id", 0), name, sub, bits, spirit,
                    if (e.has("fa")) e.optString("fa", "") else null, x, y))
            }
        }
        val rares = ArrayList<Rare>()
        d.optJSONArray("rares")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val pts = points(e.optJSONArray("p"))
                if (pts.isEmpty()) continue
                rares.add(Rare(e.optInt("id", 0), e.optString("n", ""), e.optInt("r", 0), pts))
            }
        }
        val o = d.optJSONObject("obj")
        return Pins(d.optInt("m"), d.optInt("set", 0), svc, rares,
            points(o?.optJSONArray("mail")), points(o?.optJSONArray("forge")), points(o?.optJSONArray("anvil")))
    }

    /** a "qdbnodes" document -> its spots; null = no answer */
    fun nodes(d: JSONObject): Nodes? {
        if (d.has("err") || !d.has("m")) return null
        val kinds = ArrayList<String>()
        d.optJSONArray("kinds")?.let { a -> for (i in 0 until a.length()) kinds.add(a.optString(i)) }
        fun group(k: String): List<Node> {
            val a = d.optJSONArray(k) ?: return emptyList()
            val out = ArrayList<Node>(a.length())
            for (i in 0 until a.length()) {
                val p = a.optJSONArray(i) ?: continue
                val x = p.optDouble(0); val y = p.optDouble(1)
                if (x.isNaN() || y.isNaN()) continue
                out.add(Node(x, y, p.optInt(2)))
            }
            return out
        }
        return Nodes(d.optInt("m"), d.optInt("set", 0), kinds, group("herb"), group("ore"), group("chest"))
    }

    /**
     * THE DEVELOPER PEDESTALS (PoiStore's rule for the bundle): "[DND] TAR Pedestal" test NPCs and the generic NPC standing on each
     * ("Druid Trainer", "Weapons Vendor", no subtitle) never show in the game, so they never draw: a "[DND]" row and any row within
     * PEDESTAL_YD of one go. [ax] / [ay] = the yards across / down the map's 0..1 (QuestieDB gives no height, so no height test).
     */
    fun dropPedestals(svc: List<Svc>, ax: Double, ay: Double): List<Svc> {
        val ped = svc.filter { it.name.startsWith("[DND]") }
        if (ped.isEmpty()) return svc
        return svc.filter { v ->
            !v.name.startsWith("[DND]") && ped.none { q -> Math.hypot((q.x - v.x) * ax, (q.y - v.y) * ay) <= PEDESTAL_YD }
        }
    }
    const val PEDESTAL_YD = 1.5

    /**
     * LATER-EXPANSION NPCs IN THE BUNDLE (the user, 2026-10-06, standing on a Stormwind pin with no trainer: "Remove them from the
     * map"). The bundle is 3.3.5a's world. 409 of its NPC rows are in neither Forever's classic world nor QuestieDB (which lists
     * every classic NPC and Forever's new ones): Farseer Umbrua and the other Draenei / blood-elf class trainers, Inscription
     * suppliers, holiday vendors -- and EVERY one has a creature entry >= [LATER_ENTRY] (TBC / WotLK). Measured against QuestieDB
     * 1.0.5: no bundle NPC below it is missing, and only Renn'az (17598) above it exists, so he is kept. Objects (mailbox, forge,
     * anvil) carry object entries and are never dropped by this.
     */
    /**
     * THE CITY ESSENTIALS (the user's pick 2026-10-07, after Stormwind's small map was "just a hot mess ... very few of them seem
     * accurate": "City essentials + every NPC you've met"): in a capital Home's SMALL map shows an UNMET guess (the bundle's Wrath-era
     * spawns, QuestieDB's) only when it is a bank, an auction house, a flight master, an inn or a mailbox; whatever the player has met,
     * always. The full map keeps everything.
     */
    const val CITY_ESSENTIALS = BANKER or AUCTIONEER or FLIGHTMASTER or INNKEEPER or MAILBOX
    fun cityLeanKeeps(flags: Int, met: Boolean): Boolean = met || flags and CITY_ESSENTIALS != 0

    /**
     * QUESTIEDB'S AVAILABLE QUESTS (Quests' add-on, {"type":"qdbavail"}, 2026-10-07; the user: "The mini map os missing quests that
     * I haven't accepted yet. Can this be populated from questie db?"): the quests the player can take on map [m], by Questie's own
     * checks (race / class masks with Forever's races, levels, pre-quests, exclusives, the giver's faction), each at its giver's spawn
     * nearest the player. [f]: AV_TRIVIAL (grey for this level), AV_REPEAT (repeatable / daily), AV_SOON (fits but for 1-2 levels),
     * AV_HOLIDAY (a holiday / world-event quest, offered only while its event runs: Quests q32).
     */
    class AvailQ(val id: Int, val title: String, val lv: Int, val x: Double, val y: Double, val f: Int, val giver: Int, val obj: Boolean)
    class Avail(val m: Int, val set: Int, val q: List<AvailQ>)
    const val AV_TRIVIAL = 1; const val AV_REPEAT = 2; const val AV_SOON = 4; const val AV_HOLIDAY = 8

    /** a qdbavail document, or null (an error, no map); quests without an id or a spot are left out */
    fun avail(d: JSONObject): Avail? {
        if (d.has("err") || !d.has("m")) return null
        val out = ArrayList<AvailQ>()
        d.optJSONArray("q")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val id = e.optInt("i", 0)
                val x = e.optDouble("x", Double.NaN); val y = e.optDouble("y", Double.NaN)
                if (id <= 0 || x.isNaN() || y.isNaN()) continue
                out.add(AvailQ(id, e.optString("n", ""), e.optInt("lv", 0), x, y, e.optInt("f", 0), e.optInt("g", 0), e.optInt("o", 0) == 1))
            }
        }
        return Avail(d.optInt("m"), d.optInt("set", 0), out)
    }

    /** whether the map shows a quest with flags [f]: trivial ones only with [showTrivial], "soon" ones only with [showSoon],
     *  holiday ones only with [showHoliday] */
    fun availShows(f: Int, showTrivial: Boolean, showSoon: Boolean, showHoliday: Boolean = false): Boolean =
        (f and AV_TRIVIAL == 0 || showTrivial) && (f and AV_SOON == 0 || showSoon) && (f and AV_HOLIDAY == 0 || showHoliday)

    fun laterExpansion(entry: Int, flags: Int): Boolean =
        entry >= LATER_ENTRY && entry !in LATER_KEEP && flags and (MAILBOX or FORGE or ANVIL) == 0
    const val LATER_ENTRY = 17000
    private val LATER_KEEP = setOf(17598)

    /** the names in [mask] (bit i = kinds[i]) */
    fun names(n: Nodes, mask: Int): List<String> = n.kinds.filterIndexed { i, _ -> i < 31 && mask and (1 shl i) != 0 }
}
