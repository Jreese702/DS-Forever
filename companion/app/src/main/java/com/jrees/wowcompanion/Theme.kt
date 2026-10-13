package com.jrees.wowcompanion

import android.graphics.Color

/**
 * The WoW look, in one place.
 *
 * Item-quality colours are CANONICAL -- players read them without thinking, so they are not up
 * for adjustment. The two exceptions are RARE and EPIC: Blizzard's #0070DD measures about
 * 3.2:1 on this dark ground and fails legibility at list text sizes, so both are lifted just
 * enough to pass while staying unmistakably "blue" and "purple".
 */
object Theme {
    val ink = Color.parseColor("#0E0B08")       // page ground
    val field = Color.parseColor("#141009")     // map field / recessed areas
    val panel = Color.parseColor("#17120C")     // header + strip
    val raised = Color.parseColor("#1B1610")    // tiles, rows
    val selected = Color.parseColor("#2E2411")  // selected row / active tab

    val gold = Color.parseColor("#FFD100")      // headings, quest markers, the active tab
    val bronze = Color.parseColor("#C8AA6E")    // rules, borders
    val bronzeDim = Color.parseColor("#4A3A1C")
    val text = Color.parseColor("#E8DCC0")
    val textDim = Color.parseColor("#B3A48A")
    val dim = Color.parseColor("#8F8168")
    val warn = Color.parseColor("#D99A4E")

    val flight = Color.parseColor("#7FC4E8")
    val ok = Color.parseColor("#8FC47A")

    // parchment is for prose that is read; text on it is ink, never the light text colours
    val parchment = Color.parseColor("#D8A55D")     // the flat stand-in when the art is missing
    val parchmentInk = Color.parseColor("#2A1808")
    val parchmentInkDim = Color.parseColor("#5C3414")

    // the spellbook's page: a spell that is not known yet is written fainter, in the page's own browns
    val bookInkOff = Color.parseColor("#584026")
    val bookInkOffDim = Color.parseColor("#684E2E")
    // "not on a button": the game's light blue (LIGHTBLUE_FONT_COLOR) on dark, a dark blue on the page
    val notOnBar = Color.parseColor("#88ABFF")
    val notOnBarInk = Color.parseColor("#1E3C8C")

    // a standing with a faction, the game's own bar colours (FACTION_BAR_COLORS): index 1 Hated .. 8 Exalted
    fun standing(index: Int): Int = when (index) {
        1, 2 -> Color.parseColor("#CC4D38")     // Hated, Hostile
        3 -> Color.parseColor("#BF4500")        // Unfriendly
        4 -> Color.parseColor("#E6B300")        // Neutral
        else -> Color.parseColor("#00991A")     // Friendly, Honored, Revered, Exalted
    }
    val skillBar = Color.parseColor("#3C6CD2")  // a skill's bar
    val buffed = Color.parseColor("#1EFF00")    // a stat a buff has raised
    val debuffed = Color.parseColor("#FF2020")  // a stat something has lowered

    val battleNet = Color.parseColor("#82C5FF")     // a Battle.net friend's BattleTag, the game's own blue
    val offline = Color.parseColor("#808080")       // a player who is offline
    val motd = Color.parseColor("#40FF40")          // the guild's message of the day, the game's guild green

    // the group finder's tones, the game's own (its GlobalColor table, read from the client)
    val finderPlain = Color.parseColor("#808080")   // what a listing looks for (GRAY_FONT_COLOR)
    val finderMatch = Color.parseColor("#66BBFF")   // ... and it matches my own listing (BRIGHTBLUE_FONT_COLOR)
    val finderMine = Color.parseColor("#66FF60")    // my own listing (LIGHTGREEN_FONT_COLOR)
    val finderOff = Color.parseColor("#4D4D4D")     // delisted, or filtered out (0.3 grey)
    val welcome = Color.parseColor("#19FF19")       // "New Players Welcome" (GREEN_FONT_COLOR)
    val note = Color.parseColor("#999999")          // a listing has a description (LIGHTGRAY_FONT_COLOR)

    // the calendar: an event's name by MY status, the game's own colours (its CALENDAR_INVITESTATUS_INFO)
    val tentative = Color.parseColor("#FF8040")     // Tentative, Standby (ORANGE_FONT_COLOR)
    val moderator = Color.parseColor("#8ABFFF")     // an event I made or moderate: it wins over the status

    /**
     * The colour of a calendar event's name by my status, the game's numbers: 0 Invited (gold),
     * 1 Accepted, 3 Confirmed, 6 Signed Up (green), 2 Declined, 4 Out (red), 5 Standby,
     * 8 Tentative (orange), 7 Not Signed Up (grey).
     */
    fun invite(status: Int): Int = when (status) {
        1, 3, 6 -> welcome
        2, 4 -> debuffed
        5, 8 -> tentative
        7 -> finderPlain
        else -> gold
    }

    // an item the character cannot use: the game writes its name in red
    val unusable = Color.parseColor("#FF4040")

    // Quest difficulty, the game's own colours (GetQuestDifficultyColor). CANONICAL like item
    // quality: players read them without thinking. The same values QuestsView draws today.
    val questRed = Color.parseColor("#FF1A1A")
    val questOrange = Color.parseColor("#FF8040")
    val questYellow = Color.parseColor("#FFFF00")
    val questGreen = Color.parseColor("#40C040")
    val questGrey = Color.parseColor("#808080")

    // class colours (the ones we can hit; extend as needed)
    val classColour = mapOf(
        "WARRIOR" to Color.parseColor("#C69B6D"), "PALADIN" to Color.parseColor("#F48CBA"),
        "HUNTER" to Color.parseColor("#AAD372"), "ROGUE" to Color.parseColor("#FFF468"),
        "PRIEST" to Color.parseColor("#FFFFFF"), "DEATHKNIGHT" to Color.parseColor("#C41E3A"),
        "SHAMAN" to Color.parseColor("#0070DD"), "MAGE" to Color.parseColor("#3FC7EB"),
        "WARLOCK" to Color.parseColor("#8788EE"), "MONK" to Color.parseColor("#00FF98"),
        "DRUID" to Color.parseColor("#FF7C0A"), "DEMONHUNTER" to Color.parseColor("#A330C9"),
        "EVOKER" to Color.parseColor("#33937F"),
    )

    /** canonical item quality, with rare/epic lifted for contrast on dark */
    fun quality(q: Int?): Int = when (q) {
        0 -> Color.parseColor("#9D9D9D")   // poor
        1 -> Color.parseColor("#FFFFFF")   // common
        2 -> Color.parseColor("#1EFF00")   // uncommon
        3 -> Color.parseColor("#4FA8FF")   // rare      (Blizzard #0070DD, lifted)
        4 -> Color.parseColor("#C77DFF")   // epic      (Blizzard #A335EE, lifted)
        5 -> Color.parseColor("#FF8000")   // legendary
        6 -> Color.parseColor("#E6CC80")   // artifact
        7 -> Color.parseColor("#00CCFF")   // heirloom
        else -> text
    }

    fun classColourOf(name: String?): Int =
        classColour[(name ?: "").uppercase().replace(" ", "")] ?: gold

    // INDOOR TRANSITION ZONES, drawn instead of stairs (the user, 2026-09-29: "drawing transition zones with highlighted colors
    // instead of trying to draw stairs, like red for the exit and different colors for up or down"; set 2 picked 15:38).
    // A zone is FILLED with the first colour (alpha 110) and outlined 4 px with its rim (the colour 15 % toward white, opaque).
    val zoneExit = Color.argb(110, 0xFF, 0x20, 0x20)          // the way out: #FF2020
    val zoneExitRim = Color.rgb(0xFF, 0x41, 0x41)
    val zoneUp = Color.argb(72, 0x1E, 0xFF, 0x00)             // up: the game's uncommon green #1EFF00; a lighter fill (it is the brightest of the three on the tan floors)
    val zoneUpRim = Color.rgb(0x3F, 0xFF, 0x26)
    val zoneDown = Color.argb(110, 0x37, 0xAA, 0xEB)          // down: user-confirmed blue
    val zoneDownRim = Color.rgb(0x55, 0xB7, 0xEE)
    const val ZONE_RIM_PX = 4f

    /** the zone's name coloured as the game's minimap colours it by the zone's PvP type (GetZonePVPInfo) */
    fun zoneColour(pvp: String?): Int = when (pvp) {
        "sanctuary" -> Color.rgb(105, 204, 240)
        "arena", "hostile", "combat" -> Color.rgb(255, 26, 26)
        "friendly" -> Color.rgb(26, 255, 26)
        "contested" -> Color.rgb(255, 179, 0)
        else -> gold
    }
}

/**
 * Rooms. TYPE was folded into CHAT: a chat log and a composer are one activity, and the strip
 * is more legible with four rooms than five.
 *
 * THE PRINCIPLE behind every room: the bottom screen exists to REMOVE clutter from the top
 * screen. So each room must eventually REBUILD what it shows from data + extracted art, because
 * only a rebuild lets the addon hide the original. Mirroring the game's own pixels is a crutch
 * for where the rebuild is not ready -- it can never be the destination, since a mirrored
 * element is by definition still occupying the top screen.
 */
enum class Room(val label: String, val onBar: Boolean = true) {
    HOME("HOME"),                 // the page the bottom screen rests on: the round disc, not an icon in the row
    // no CAST room (the user 2026-09-30: "get rid of the casting room"; "Drop the room; keep what's on Home"): Home's controller area
    // still lends the one CastView, which stays parked in the rooms container
    MAP("MAP"), QUESTS("QUESTS"),
    SPELLBOOK("SPELLBOOK"),       // spells session
    TALENTS("TALENTS"),           // spells session: its own room right after the Spellbook (the user 2026-09-30)
    PROFESSIONS("PROFESSIONS"),   // professions session: after the Spellbook and Talents
    GROUPFINDER("GROUPFINDER"),   // groupfinder session: the last room left of Home (the kit's icon_groupfinder)
    CHARACTER("CHARACTER"),       // character session: the first room right of Home
    // the CONTROLLER room has Chat's spot on the bar (the user, 2026-10-05: "the controller page really seems to be important so I
    // feel like it should actually be a home tab ... remove the Chat tab in favor of this controller one"); CHAT stays a room, off
    // the bar, opened from Home's chat panel ("Tap Home's chat panel") and the game's chat key
    BAGS("BAGS"), CONTROLLER("CONTROLLER"), CHAT("CHAT", onBar = false), SOCIAL("SOCIAL"),
    CALENDAR("CALENDAR"),         // calendar session: the last room before Settings (the kit's icon_calendar)
    SETTINGS("SETTINGS")          // keep last: the strip lays rooms out in this order
}
