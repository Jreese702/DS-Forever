package com.jrees.wowcompanion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The bundled service-POI layer: vendors, trainers, bankers, innkeepers, auctioneers, mailboxes,
 * flight masters, stable masters, forges and anvils.
 *
 * WHY THIS EXISTS. WoW's minimap draws a "tracking blip" layer -- vendor, trainer, mailbox,
 * banker, innkeeper -- straight onto the minimap texture, and NO Lua API exposes it.
 * GetMinimapBlip, GetMapLandmarkInfo and the gathering-node functions are all absent from this
 * client binary. So in open country the addon's own live data (quests, flight points, area POIs,
 * vignettes) is enough, but in a city our map was nearly empty while the game's was full.
 *
 * WHAT THE DATA IS, EXACTLY. Static NPC and object spawn positions extracted from the local
 * AzerothCore world database, which is pre-Cataclysm Azeroth -- the same geography this client
 * ships. It is REFERENCE DATA, not a live sighting: the roster is WotLK-era and WoW: Forever
 * reorganises capital-city trainers by race and adds its own NPCs. So a seed pin is drawn
 * DIMMED AND HOLLOW, a pin confirmed by actually interacting with the NPC is drawn SOLID, and
 * the absence of a pin never means "there is none here".
 *
 * WHAT SHIPS IS WORLD COORDINATES. Converting offline would bake this build's map rects into the
 * asset and silently drift if Blizzard re-cuts the map art. Instead the addon reads the rect out
 * of the LIVE client (C_Map.GetWorldPosFromMapPos at the 0,0 and 1,1 corners) and sends five
 * numbers; we convert at draw time and cannot disagree with the map the client is drawing.
 */
object PoiStore {

    // ---- role bits. NPC bits are the real npcflag bits; 0x4000000 and up are synthetic and
    // safe because zero creature templates in the source DB use them.
    const val TRAINER = 0x000010
    const val TRAINER_CLASS = 0x000020
    const val TRAINER_PROF = 0x000040
    const val VENDOR = 0x000080
    const val VENDOR_AMMO = 0x000100
    const val VENDOR_FOOD = 0x000200
    const val VENDOR_POISON = 0x000400
    const val VENDOR_REAGENT = 0x000800
    const val REPAIR = 0x001000
    const val FLIGHTMASTER = 0x002000
    // synthetic, from QuestieDB's npcFlags (Map 2026-10-12, the map's NPC icons): the spirit healer, the guild master, the battlemaster
    const val SPIRIT = 0x004000
    const val GUILD = 0x008000
    const val BATTLEMASTER = 0x040000
    const val INNKEEPER = 0x010000
    const val BANKER = 0x020000
    const val TABARD = 0x080000
    const val AUCTIONEER = 0x200000
    const val STABLEMASTER = 0x400000
    const val MAILBOX = 0x4000000
    const val FORGE = 0x8000000
    const val ANVIL = 0x10000000

    /** display order is deliberate: the things a player walks into a city to find come first */
    val ROLES: List<Triple<Int, String, String>> = listOf(
        Triple(VENDOR, "Vendor", "V"),
        Triple(REPAIR, "Repair", "R"),
        Triple(TRAINER_CLASS, "Class trainer", "C"),
        Triple(TRAINER_PROF, "Profession trainer", "P"),
        Triple(TRAINER, "Trainer", "T"),
        Triple(BANKER, "Bank", "B"),
        Triple(AUCTIONEER, "Auction house", "A"),
        Triple(MAILBOX, "Mailbox", "M"),
        Triple(INNKEEPER, "Innkeeper", "I"),
        Triple(FLIGHTMASTER, "Flight master", "F"),
        Triple(STABLEMASTER, "Stable master", "S"),
        Triple(VENDOR_REAGENT, "Reagents", "G"),
        Triple(VENDOR_FOOD, "Food & drink", "D"),
        Triple(VENDOR_AMMO, "Ammo", "N"),
        Triple(VENDOR_POISON, "Poisons", "X"),
        Triple(TABARD, "Tabard designer", "Y"),
        Triple(ANVIL, "Anvil", "L"),
        Triple(FORGE, "Forge", "O"),
        Triple(SPIRIT, "Spirit healer", "H"),
        Triple(GUILD, "Guild master", "U"),
        Triple(BATTLEMASTER, "Battlemaster", "W"),
    )

    /**
     * The default filter. NOT everything: Eastern Kingdoms alone has 947 repair NPCs and 603
     * reagent vendors, and a city drawn with all of them is a wall of dots. This is the set the
     * game's own minimap tracking offers as single choices, which is the set players look for.
     */
    const val DEFAULT_MASK = VENDOR or TRAINER_CLASS or TRAINER_PROF or BANKER or
            AUCTIONEER or MAILBOX or INNKEEPER or FLIGHTMASTER or STABLEMASTER or
            // the user, 2026-10-12: the usual vanilla services too (anvils / forges stay off: a filter option, the user's pick)
            REPAIR or TABARD or SPIRIT or GUILD or BATTLEMASTER

    /** one spawn. Coordinates are WORLD yards: +x = north, +y = west. */
    class Poi(
        val entry: Int, val name: String, val title: String, val flags: Int,
        val wx: Double, val wy: Double, val wz: Int, val areaID: Int
    ) {
        /** the most specific role we have an icon for, for labelling a single pin */
        fun primaryRole(): String {
            for ((bit, label, _) in ROLES) if (flags and bit != 0) return label
            return "Service"
        }
    }

    /**
     * A zone's world-coordinate bounding box.
     *   top/bottom are world X (north/south) and left/right are world Y (west/east), so
     *   top > bottom and left > right ALWAYS. Those two inequalities are the only cheap test
     *   that catches a transposed Vector2D, which is the one failure that still looks plausible
     *   on screen. Reject the rect rather than drawing a mirrored map.
     */
    class Rect(
        val continent: Int, val top: Double, val bottom: Double,
        val left: Double, val right: Double, val fromClient: Boolean
    ) {
        val height = top - bottom
        val width = left - right
        val valid = height > 1.0 && width > 1.0

        fun contains(wx: Double, wy: Double) =
            wx in bottom..top && wy in right..left

        /** world -> normalised uiMap. Note the SWAP: nx is fed by world Y, ny by world X. */
        fun nx(wy: Double) = (left - wy) / width
        fun ny(wx: Double) = (top - wx) / height

        companion object {
            /** the rect as the addon reported it out of the live client */
            fun fromJson(o: JSONObject?): Rect? {
                if (o == null) return null
                val r = Rect(
                    o.optInt("c", -1),
                    o.optDouble("t", Double.NaN), o.optDouble("b", Double.NaN),
                    o.optDouble("l", Double.NaN), o.optDouble("r", Double.NaN),
                    fromClient = true
                )
                if (r.top.isNaN() || r.bottom.isNaN() || r.left.isNaN() || r.right.isNaN()) return null
                return if (r.valid) r else null
            }
        }
    }

    // ---------------------------------------------------------------- loading
    @Volatile var note: String = ""          // why the layer is or is not showing, for the UI

    /**
     * THE BUNDLE IS GONE (the art harvest, the user 2026-10-09: no game data in the APK; "DROP the AzerothCore NPC pins"): the NPC
     * spawns (poi_<continent>.json) and the zone rects (poi_zones.json) baked from the AzerothCore world database left the app. The
     * service pins come from QuestieDB (Quests' qdbpins, MapView.qdbRows) and from the NPCs the player has met (PoiHarvest); the
     * rect is always the client's own (the addon's). The types and the query below stay: those sources use them.
     */
    private fun zones(@Suppress("UNUSED_PARAMETER") c: Context): List<JSONObject> = emptyList()

    /**
     * Fallback rect, matched by the zone NAME the addon already reports from GetZoneText.
     * Only used before the first client rect arrives, and flagged fromClient=false so the UI can
     * say the layer is running on baked 3.3.5a rects rather than this build's own.
     */
    fun fallbackRect(c: Context, zoneName: String?): Rect? {
        if (zoneName.isNullOrEmpty()) return null
        for (z in zones(c)) {
            if (z.optString("n") == zoneName) {
                return Rect(
                    z.optInt("m"), z.optDouble("t"), z.optDouble("b"),
                    z.optDouble("l"), z.optDouble("r"), fromClient = false
                ).takeIf { it.valid }
            }
        }
        return null
    }

    /**
     * What the live rect turns out to be, matched against the baked rects BY GEOMETRY.
     *
     * This does two jobs and needs no id-mapping table for either. It names the zone, and it
     * answers the one question that could not be answered off the device: does this modern
     * client's uiMap rect actually equal the 3.3.5a WorldMapArea rect? If `exact` comes back
     * false the map art has been re-cut, which is worth saying out loud in the UI rather than
     * quietly drawing pins a few hundred yards off.
     *
     * `nested` is the "h" list baked into poi_zones.json: the capital cities whose POIs must not
     * be drawn on this zone's map. Stormwind City's rect sits (almost) inside Elwynn Forest's, so
     * without it all 222 Stormwind service pins also scatter across the Elwynn map, where the game
     * draws one city icon. Which capitals to hide from which zones is decided at BUILD time from
     * an explicit six-capital list, because every geometric "is this a city" heuristic tried also
     * hid genuine neighbours -- Durotar and Mulgore from The Barrens, Moonglade from Darkshore.
     */
    class ZoneMatch(val areaID: Int, val name: String, val nested: Set<Int>, val exact: Boolean)

    fun identify(c: Context, rect: Rect): ZoneMatch? {
        var best: JSONObject? = null
        var bestErr = Double.MAX_VALUE
        for (z in zones(c)) {
            if (z.optInt("m") != rect.continent) continue
            val e = Math.abs(z.optDouble("t") - rect.top) + Math.abs(z.optDouble("b") - rect.bottom) +
                    Math.abs(z.optDouble("l") - rect.left) + Math.abs(z.optDouble("r") - rect.right)
            if (e < bestErr) { bestErr = e; best = z }
        }
        val b = best ?: return null
        val hide = HashSet<Int>()
        b.optJSONArray("h")?.let { for (i in 0 until it.length()) hide.add(it.optInt(i)) }
        // 4 corners, 2 yards each: generous enough for float rounding in the DBC, tight enough
        // that a genuinely re-cut map cannot slip through as a match.
        return ZoneMatch(b.optInt("a"), b.optString("n"), hide, bestErr <= 8.0)
    }

    fun zoneNameFor(c: Context, continent: Int, areaID: Int): String {
        for (z in zones(c)) if (z.optInt("m") == continent && z.optInt("a") == areaID)
            return z.optString("n")
        return ""
    }

    /** POIs for one continent from the bundle: none since the harvest (see [zones]); null, with [note] saying so */
    fun forContinent(@Suppress("UNUSED_PARAMETER") c: Context, continent: Int): List<Poi>? {
        note = "no bundled POIs (continent $continent): QuestieDB and the NPCs met"
        return null
    }

    // ---------------------------------------------------------------- the query
    /** one seed pin, already projected into the zone's 0..1 space */
    class Pin(
        val poi: Poi, val nx: Double, val ny: Double, val dist: Double?, val confirmed: Boolean
    )

    /**
     * Everything from the bundle that falls inside this zone's rect.
     *
     * THE RECT IS THE ZONE TEST. There is no 3.3.5a-areaId-to-modern-uiMapID table on this
     * machine and we do not need one: a POI belongs on this map exactly when its world position
     * is inside this map's world rect. Border NPCs satisfy two neighbouring rects and appear on
     * both maps, which is what you want -- the baked areaID is a label, never the filter.
     *
     * @param confirmedEntries creature entry ids the player has actually interacted with, from
     *        the addon's harvest layer. Those are drawn solid; everything else is drawn dim.
     */
    fun pinsFor(
        all: List<Poi>, rect: Rect, mask: Int,
        playerWx: Double?, playerWy: Double?, confirmedEntries: Set<Int>,
        hideAreas: Set<Int> = emptySet()
    ): List<Pin> {
        val out = ArrayList<Pin>(128)
        for (p in all) {
            if (p.flags and mask == 0) continue
            if (p.areaID in hideAreas) continue
            if (!rect.contains(p.wx, p.wy)) continue
            val d = if (playerWx != null && playerWy != null) {
                val dx = p.wx - playerWx; val dy = p.wy - playerWy
                Math.sqrt(dx * dx + dy * dy)     // world units ARE yards; never measure in 0..1
            } else null
            out.add(Pin(p, rect.nx(p.wy), rect.ny(p.wx), d, p.entry in confirmedEntries))
        }
        out.sortBy { it.dist ?: Double.MAX_VALUE }
        return out
    }
}
