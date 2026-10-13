package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * THE CALENDAR ROOM'S DATA (calendar session): the addon's `calendar` document (WowCompanionCalendar, c8) read
 * into what the room draws, and the GAME'S OWN RULES for it, read in this client's `blizzard_calendar`
 * (`CalendarFrame_UpdateDayEvents` / `UpdateDayTextures`, the day button's tooltip, the event page):
 *  - a day shows an ENTRY only where something begins, ends or happens: a holiday's START ("%s Begins") and END
 *    ("%s Ends", unless the entry says "don't display end") and one-day things; NOTHING on a day a holiday only
 *    runs through (ONGOING);
 *  - the day's banner = the FIRST holiday of the day that runs over more than two days, with THAT day's picture;
 *    no picture, no banner (the game draws none);
 *  - the chosen day's list = the game's day note: the entries by time (an END entry by its end time), then
 *    "Ongoing" and each holiday running through the day as a date range;
 *  - the answers offered are the addon's (`ans`: the game's day menu decides), never recomputed here.
 * Pure Kotlin + org.json, so tools/calendar_test runs it off the device against documents the real addon wrote.
 */
object CalendarModel {

    const val PAD_ROW = "cal"

    /** the six filters of this client's calendar, in the game's order (camelot/calendarfilterdata.lua) */
    val FILTERS = listOf(
        "calendarShowHolidays" to "CALENDAR_FILTER_HOLIDAYS",
        "calendarShowDarkmoon" to "CALENDAR_FILTER_DARKMOON",
        "calendarShowLockouts" to "CALENDAR_FILTER_RAID_LOCKOUTS",
        "calendarShowResets" to "CALENDAR_FILTER_RAID_RESETS",
        "calendarShowWeeklyHolidays" to "CALENDAR_FILTER_WEEKLY_HOLIDAYS",
        "calendarShowBattlegrounds" to "CALENDAR_FILTER_BATTLEGROUND",
    )
    /** the answers, in the order they stand on the page; "rem" lives in More */
    val ANSWERS = listOf("acc", "ten", "dec", "sig", "rem")
    private val PLAYER_MADE = setOf("PLAYER", "GUILD_EVENT", "GUILD_ANNOUNCEMENT", "COMMUNITY_EVENT")

    // English stand-ins, only for a document without `str` (the words are the game's, read at runtime)
    private val FALLBACK = mapOf(
        "ACCEPT" to "Accept", "DECLINE" to "Decline", "CALENDAR_VIEW_EVENT_TENTATIVE" to "Tentative",
        "CALENDAR_VIEW_EVENT_REMOVE" to "Remove", "CALENDAR_SIGNUP" to "Sign Up", "CALENDAR_FILTERS" to "Filters",
        "CALENDAR_FILTER_HOLIDAYS" to "Holidays", "CALENDAR_FILTER_DARKMOON" to "Darkmoon Faire",
        "CALENDAR_FILTER_RAID_LOCKOUTS" to "Active Raid Lockouts", "CALENDAR_FILTER_RAID_RESETS" to "Raid Resets",
        "CALENDAR_FILTER_WEEKLY_HOLIDAYS" to "Weekly Holidays", "CALENDAR_FILTER_BATTLEGROUND" to "Battleground Call to Arms",
        "CALENDAR_EVENTNAME_FORMAT_START" to "%s Begins", "CALENDAR_EVENTNAME_FORMAT_END" to "%s Ends",
        "CALENDAR_EVENTNAME_FORMAT_RAID_LOCKOUT" to "%s Unlocks", "CALENDAR_EVENTNAME_FORMAT_RAID_RESET" to "%s Resets",
        "CALENDAR_RAID_LOCKOUT_DESCRIPTION" to "Your %1\$s instance unlocks at %2\$s.",
        "CALENDAR_RAID_RESET_DESCRIPTION" to "%1\$s resets at %2\$s.",
        "CALENDAR_HOLIDAYFRAME_BEGINSENDS" to "%1\$s|n|nBegins: %2\$s %3\$s|nEnds: %4\$s %5\$s",
        "FULLDATE" to "%1\$s, %2\$s %3\$d %4\$d", "SHORTDATENOYEAR" to "%2\$d/%1\$02d",
        "TIMEMANAGER_TICKER_12HOUR" to "%d:%02d", "TIMEMANAGER_TICKER_24HOUR" to "%02d:%02d",
        "TIMEMANAGER_AM" to "AM", "TIMEMANAGER_PM" to "PM", "CALENDAR_TOOLTIP_ONGOING" to "Ongoing",
        "CALENDAR_TOOLTIP_DATE_RANGE" to "%s - %s", "CALENDAR_EVENT_INVITEDBY_PLAYER" to "Invited by %s",
        "CALENDAR_ANNOUNCEMENT_CREATEDBY_PLAYER" to "Created by %s", "CALENDAR_SIGNEDUP_FOR_GUILDEVENT_WITH_STATUS" to "Signed up (%s)",
        "CALENDAR_EVENT_INVITEDBY_YOURSELF" to "This is your event", "CALENDAR_GUILDEVENT_INVITEDBY_YOURSELF" to "This is your guild event",
        "CALENDAR_COMMUNITYEVENT_INVITEDBY_YOURSELF" to "This is your community event",
        "CALENDAR_ANNOUNCEMENT_CREATEDBY_YOURSELF" to "This is your announcement",
        "CALENDAR_VIEW_EVENTTITLE_LOCKED" to "%s (Locked)", "CALENDAR_VIEW_EVENTTYPE" to "%1\$s - %2\$s",
        "CALENDAR_VIEW_EVENT_SETSTATUS" to "Set your status:", "DUNGEON_NAME_WITH_DIFFICULTY" to "%1\$s (%2\$s)",
        "RETRIEVING_INVITE_LIST" to "Retrieving invite list", "YES" to "Yes", "NO" to "No",
        "CALENDAR_INVITELIST_CREATORNAME" to "%s (Creator)", "CALENDAR_INVITELIST_MODERATORNAME" to "%s (Moderator)",
        "CALENDAR_TYPE_RAID" to "Raid", "CALENDAR_TYPE_DUNGEON" to "Dungeon", "CALENDAR_TYPE_PVP" to "PvP",
        "CALENDAR_TYPE_MEETING" to "Meeting", "CALENDAR_TYPE_OTHER" to "Other",
        "CALENDAR_STATUS_INVITED" to "Invited", "CALENDAR_STATUS_ACCEPTED" to "Accepted", "CALENDAR_STATUS_DECLINED" to "Declined",
        "CALENDAR_STATUS_CONFIRMED" to "Confirmed", "CALENDAR_STATUS_OUT" to "Out", "CALENDAR_STATUS_STANDBY" to "Standby",
        "CALENDAR_STATUS_SIGNEDUP" to "Signed Up", "CALENDAR_STATUS_NOT_SIGNEDUP" to "Not Signed Up",
        "CALENDAR_STATUS_TENTATIVE" to "Tentative",
    )
    private val MONTHS_EN = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September",
        "October", "November", "December")
    private val WEEKDAYS_EN = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val STATUS_KEYS = listOf("CALENDAR_STATUS_INVITED", "CALENDAR_STATUS_ACCEPTED", "CALENDAR_STATUS_DECLINED",
        "CALENDAR_STATUS_CONFIRMED", "CALENDAR_STATUS_OUT", "CALENDAR_STATUS_STANDBY", "CALENDAR_STATUS_SIGNEDUP",
        "CALENDAR_STATUS_NOT_SIGNEDUP", "CALENDAR_STATUS_TENTATIVE")
    private val TYPE_KEYS = listOf("CALENDAR_TYPE_RAID", "CALENDAR_TYPE_DUNGEON", "CALENDAR_TYPE_PVP",
        "CALENDAR_TYPE_MEETING", "CALENDAR_TYPE_OTHER")

    // ---------------------------------------------------------------- the document

    data class Time(val y: Int, val m: Int, val d: Int, val h: Int, val mi: Int) {
        val dayNum: Int get() = y * 10000 + m * 100 + d
    }

    /** one day an entry is drawn on: its index on that day, the game's sequence and that day's own picture (0 = none) */
    data class DayRef(val day: Int, val index: Int, val seq: String, val picture: Int)

    data class Event(
        val id: Int?, val title: String, val ct: String, val start: Time?, val end: Time?, val eventType: Int?,
        val icon: Int?, val inv: Int?, val inviteType: Int?, val by: String?, val mod: String?, val diff: String?,
        val titleKey: String?, val locked: Boolean, val noBanner: Boolean, val noEnd: Boolean, val nSeq: Int?,
        val key: String?, val answers: Map<String, Int>, val on: List<DayRef>,
    ) {
        val playerMade: Boolean get() = ct in PLAYER_MADE
        val signUp: Boolean get() = (ct == "GUILD_EVENT" || ct == "COMMUNITY_EVENT") && inviteType == 1
        fun on(day: Int): DayRef? = on.firstOrNull { it.day == day }
    }

    data class Month(val y: Int, val m: Int, val days: Int, val wd1: Int, val events: List<Event>)
    data class Holiday(val name: String?, val desc: String?, val tex: Int?)
    data class Invitee(val name: String?, val cls: String?, val className: String?, val level: Int?, val status: Int?,
                       val mod: String?, val me: Boolean)
    data class Page(val desc: String?, val creator: String?, val guild: String?, val eventType: Int?, val dungeon: String?,
                    val dungeonIcon: Int?, val invites: List<Invitee>, val nInv: Int)
    /** the row's fixed keys: hold / free the controller, free BECAUSE OF B (c9), where the next B leads (c9), the six filters */
    data class Keys(val hold: Int?, val free: Int?, val filters: Map<String, Int>,
                    val freeB: Int? = null, val bHome: Int? = null, val bBack: Int? = null)

    data class State(
        val v: String, val api: Boolean, val off: Boolean, val secret: Boolean, val shown: Boolean, val gameCal: Boolean,
        val combat: Boolean, val now: Time?, val first: Int, val military: Boolean, val pending: Int, val busy: Boolean,
        val err: String?, val filters: Map<String, Boolean>, val months: List<Month>?, val holidays: Map<Int, Holiday>,
        val pages: Map<String, Page>, val words: Map<String, String>, val monthNames: List<String>,
        val weekdayNames: List<String>, val me: String?, val keys: Keys, val pad: JSONObject?, val held: Boolean,
        val maxDate: Time?,
        /** (c10) the addon WANTS the controller for the room (its `h` arrived, its `f` not yet); null = a c9 addon, which does not say */
        val want: Boolean? = null,
    ) {
        fun word(k: String): String = words[k]?.takeIf { it.isNotEmpty() } ?: FALLBACK[k] ?: k
        fun monthName(m: Int): String = monthNames.getOrNull(m - 1)?.takeIf { it.isNotEmpty() } ?: MONTHS_EN.getOrElse(m - 1) { "?" }
        /** [wd] 1 = Sunday .. 7 = Saturday */
        fun weekdayName(wd: Int): String = weekdayNames.getOrNull(wd - 1)?.takeIf { it.isNotEmpty() } ?: WEEKDAYS_EN.getOrElse(wd - 1) { "?" }
        fun statusName(s: Int?): String = if (s == null) "" else word(STATUS_KEYS.getOrElse(s) { "" })
        val padRow: Boolean get() = pad?.optJSONObject("rows")?.has(PAD_ROW) == true
    }

    private fun JSONObject.intOrNull(k: String): Int? = if (has(k)) optInt(k) else null
    private fun JSONObject.strOrNull(k: String): String? = if (has(k)) optString(k).takeIf { it.isNotEmpty() } else null

    private fun time(o: JSONObject?): Time? {
        if (o == null || !o.has("y") || !o.has("m") || !o.has("d")) return null
        return Time(o.optInt("y"), o.optInt("m"), o.optInt("d"), o.optInt("h"), o.optInt("mi"))
    }

    private fun event(o: JSONObject): Event? {
        val t = o.strOrNull("t") ?: return null
        val ct = o.strOrNull("ct") ?: return null
        val on = ArrayList<DayRef>()
        val a = o.optJSONArray("on")
        if (a != null) for (i in 0 until a.length()) {
            val p = a.optJSONArray(i) ?: continue
            on.add(DayRef(p.optInt(0), p.optInt(1), if (p.length() > 2) p.optString(2) else "", if (p.length() > 3) p.optInt(3) else 0))
        }
        val ans = HashMap<String, Int>()
        o.optJSONObject("ans")?.let { j -> for (op in ANSWERS) if (j.has(op)) ans[op] = j.optInt(op) }
        return Event(o.intOrNull("id"), t, ct, time(o.optJSONObject("st")), time(o.optJSONObject("en")), o.intOrNull("et"),
            o.intOrNull("icon"), o.intOrNull("inv"), o.intOrNull("it"), o.strOrNull("by"), o.strOrNull("mod"), o.strOrNull("diff"),
            o.strOrNull("tk"), o.optBoolean("lock"), o.optBoolean("nb"), o.optBoolean("ne"), o.intOrNull("nSeq"),
            o.strOrNull("k"), ans, on)
    }

    private fun page(o: JSONObject): Page {
        val inv = ArrayList<Invitee>()
        val a = o.optJSONArray("inv")
        if (a != null) for (i in 0 until a.length()) {
            val p = a.optJSONObject(i) ?: continue
            inv.add(Invitee(p.strOrNull("n"), p.strOrNull("c"), p.strOrNull("cn"), p.intOrNull("lv"), p.intOrNull("s"),
                p.strOrNull("mod"), p.optBoolean("me")))
        }
        val dun = o.optJSONObject("dun")
        return Page(o.strOrNull("desc"), o.strOrNull("creator"), o.strOrNull("guild"), o.intOrNull("et"),
            dun?.strOrNull("t"), dun?.intOrNull("icon"), inv, o.optInt("nInv", inv.size))
    }

    /** a `calendar` document -> the room's state; null when it is not one */
    fun parse(msg: JSONObject): State? {
        if (msg.optString("type") != "calendar") return null
        val c = msg.optJSONObject("c") ?: return null
        val strs = c.optJSONObject("str")
        val words = HashMap<String, String>()
        if (strs != null) for (k in FALLBACK.keys) strs.strOrNull(k)?.let { words[k] = it }
        fun list(k: String): List<String> {
            val a = strs?.optJSONArray(k) ?: return emptyList()
            return (0 until a.length()).map { a.optString(it) }
        }
        val months: List<Month>? = c.optJSONArray("months")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val m = a.optJSONObject(i) ?: return@mapNotNull null
                val evs = m.optJSONArray("ev")
                val list = if (evs == null) emptyList() else (0 until evs.length()).mapNotNull { j -> evs.optJSONObject(j)?.let { event(it) } }
                Month(m.optInt("y"), m.optInt("m"), m.optInt("n"), m.optInt("wd1", 1), list)
            }
        }
        // holidays and pages are keyed by what the entries name (the test double of org.json has no key walk)
        val hol = HashMap<Int, Holiday>()
        val pages = HashMap<String, Page>()
        val hj = c.optJSONObject("hol"); val dj = c.optJSONObject("det")
        for (m in months ?: emptyList()) for (e in m.events) {
            if (e.ct == "HOLIDAY" && e.id != null) hj?.optJSONObject(e.id.toString())?.let {
                hol[e.id] = Holiday(it.strOrNull("name"), it.strOrNull("desc"), it.intOrNull("tex"))
            }
            if (e.key != null) dj?.optJSONObject(e.key)?.let { pages[e.key] = page(it) }
        }
        val flt = HashMap<String, Boolean>()
        c.optJSONObject("flt")?.let { j -> for ((cv, _) in FILTERS) if (j.has(cv)) flt[cv] = j.optBoolean(cv) }
        val kj = c.optJSONObject("keys")
        val fk = HashMap<String, Int>()
        kj?.optJSONObject("flt")?.let { j -> for ((cv, _) in FILTERS) if (j.has(cv)) fk[cv] = j.optInt(cv) }
        return State(
            v = c.optString("v"), api = c.optBoolean("api", true), off = c.optBoolean("off"), secret = c.optBoolean("secret"),
            shown = c.optBoolean("shown"), gameCal = c.optBoolean("gameCal"), combat = c.optBoolean("cb"),
            now = time(c.optJSONObject("now")), first = c.optInt("first", 1).coerceIn(1, 7), military = c.optBoolean("mil"),
            pending = c.optInt("pend"), busy = c.optBoolean("busy"), err = c.strOrNull("err"), filters = flt,
            months = months, holidays = hol, pages = pages, words = words, monthNames = list("months"),
            weekdayNames = list("weekdays"), me = c.strOrNull("me"),
            keys = Keys(kj?.intOrNull("h"), kj?.intOrNull("f"), fk, kj?.intOrNull("fb"), kj?.intOrNull("bh"), kj?.intOrNull("bb")),
            pad = c.optJSONObject("pad"),
            held = c.optJSONObject("ctl")?.optBoolean("held") == true, maxDate = time(c.optJSONObject("maxD")),
            want = c.optJSONObject("ctl")?.takeIf { it.has("want") }?.optBoolean("want"),
        )
    }

    /**
     * (c10) The tap the room must send AGAIN so the addon agrees with the screen, or null. A hold or free tap can be lost (Thor
     * 2026-09-30 22:49:14: the room kept the shared controller through the user's moves to three other rooms). While the room
     * SHOWS the addon must want the controller ("h"); while it does not, it must neither want nor hold it ("f"). Busy (wanted,
     * not held: another guest or a fight has it) is fine: the addon takes it by itself when it is free. A c9 addon does not
     * say what it wants: nothing.
     * (c11) The game's own window is open behind the room (the user, 2026-10-01: every room as Professions). While the room
     * shows and HOLDS the controller, the window must be open too: `h` again (its door cell is idempotent, it only opens). Not
     * in a fight (the door is disarmed there), not when the realm has the calendar off, never while the controller is someone
     * else's (an NPC window: opening ours could push the game's own window shut).
     * (c12) In a fight the room's own free is not retried: capture i81 gives the pad back only through Interact's leave (Home taps
     * it), and the addon lets go by itself at the fight's end when the room no longer wants the controller.
     */
    fun ctlFix(s: State?, shown: Boolean): String? {
        val want = s?.want ?: return null
        if (!shown) return if ((want || s.held) && !s.combat) "f" else null
        if (!want) return "h"
        if (s.held && windowBehind(s) && !s.shown && !s.combat && !s.off && s.api) return "h"
        return null
    }

    /** (c11) the addon opens the game's window behind the room (its version is c11 or later) */
    fun windowBehind(s: State): Boolean = (Regex("^c(\\d+)$").find(s.v)?.groupValues?.get(1)?.toIntOrNull() ?: 0) >= 11

    /** a room kept across documents: a `secret` document (a fight in an instance) keeps the months last seen */
    fun merge(old: State?, new: State): State =
        if (new.secret && new.months == null && old?.months != null)
            new.copy(months = old.months, holidays = old.holidays, pages = old.pages) else new

    // ---------------------------------------------------------------- the month

    /** one of the 42 places of the month: [day] its number; [other] a day of the month before (-1) or after (1) */
    data class Cell(val row: Int, val col: Int, val day: Int, val other: Int)

    /** the six rows of seven, as the game lays them: the week starts on [first] (1 = Sunday) */
    fun grid(m: Month, prevDays: Int, first: Int): List<Cell> {
        val lead = ((m.wd1 - first) % 7 + 7) % 7        // days of the month before shown in the first row
        return (0 until 42).map { i ->
            val d = i - lead + 1
            when {
                d < 1 -> Cell(i / 7, i % 7, prevDays + d, -1)
                d > m.days -> Cell(i / 7, i % 7, d - m.days, 1)
                else -> Cell(i / 7, i % 7, d, 0)
            }
        }
    }

    fun daysIn(y: Int, m: Int): Int = when (m) {
        4, 6, 9, 11 -> 30
        2 -> if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28
        else -> 31
    }

    /** the weekday names in the order the weekday bar shows them */
    fun weekdayBar(st: State): List<String> = (0 until 7).map { st.weekdayName((st.first - 1 + it) % 7 + 1) }

    /** 1 = Sunday .. 7 = Saturday, for any date (Sakamoto's method) */
    fun weekday(y: Int, m: Int, d: Int): Int {
        val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
        val yy = if (m < 3) y - 1 else y
        return (yy + yy / 4 - yy / 100 + yy / 400 + t[m - 1] + d) % 7 + 1
    }

    /** the index of the month that holds today (the room opens there), else the second of the four */
    fun homeMonth(st: State): Int {
        val ms = st.months ?: return 0
        val n = st.now
        val i = if (n == null) -1 else ms.indexOfFirst { it.y == n.y && it.m == n.m }
        return if (i >= 0) i else minOf(1, ms.size - 1).coerceAtLeast(0)
    }

    // ---------------------------------------------------------------- a day

    /** a day ENTRY as the game shows it (not an ONGOING day; not an END the entry does not display) */
    private fun shownOn(e: Event, ref: DayRef): Boolean = ref.seq != "ONGOING" && !(ref.seq == "END" && e.noEnd)

    /** the day's entries in the game's own order (the index on that day) */
    fun entriesOn(m: Month, day: Int): List<Pair<Event, DayRef>> =
        m.events.mapNotNull { e -> e.on(day)?.let { r -> if (shownOn(e, r)) e to r else null } }.sortedBy { it.second.index }

    /** the holidays running THROUGH the day (the game's day note lists them under "Ongoing") */
    fun ongoingOn(m: Month, day: Int): List<Event> =
        m.events.filter { e -> e.on(day)?.seq == "ONGOING" }.sortedBy { it.start?.dayNum ?: 0 }

    /** the banner of the day: the first holiday of the day that runs over more than two days and has a picture on it */
    fun bannerOn(m: Month, day: Int): Pair<Event, Int>? {
        val first = m.events.mapNotNull { e -> e.on(day)?.let { e to it } }.filter { it.first.ct == "HOLIDAY" }
            .minByOrNull { it.second.index } ?: return null
        val (e, r) = first
        return if ((e.nSeq ?: 0) > 2 && !e.noBanner && r.picture > 0) e to r.picture else null
    }

    /** the marks at the foot of a day (four at most): an entry's picture, or null for the gold stud */
    fun marksOn(m: Month, day: Int): List<Int?> {
        val banner = bannerOn(m, day)?.first
        return entriesOn(m, day).take(4).map { (e, r) ->
            if (e === banner) null else r.picture.takeIf { it > 0 } ?: e.icon?.takeIf { it > 0 && e.playerMade }
        }
    }

    /** an invitation waits on the day: a player-made entry with my status Invited (an announcement takes no answer: none) */
    fun invitationOn(m: Month, day: Int): Boolean =
        entriesOn(m, day).any { it.first.playerMade && it.first.ct != "GUILD_ANNOUNCEMENT" && it.first.inv == 0 }

    // ---------------------------------------------------------------- words

    /** Lua's format strings, as Java's (the same syntax for %s, %d, %02d and %1$s); "|n" is a new line; colour codes go */
    fun format(fmt: String, vararg args: Any?): String = try {
        String.format(fmt.replace("|n", "\n"), *args).replace(Regex("\\|c[0-9a-fA-F]{8}|\\|r"), "")
    } catch (e: Exception) { fmt }

    /** GameTime_GetFormattedTime: the player's clock (12 or 24 hours) */
    fun clock(st: State, h: Int, mi: Int): String =
        if (st.military) format(st.word("TIMEMANAGER_TICKER_24HOUR"), h, mi)
        else format(st.word("TIMEMANAGER_TICKER_12HOUR"), if (h % 12 == 0) 12 else h % 12, mi) + " " +
            st.word(if (h < 12) "TIMEMANAGER_AM" else "TIMEMANAGER_PM")

    /** FormatShortDate(day, month) */
    fun shortDate(st: State, t: Time): String = format(st.word("SHORTDATENOYEAR"), t.d, t.m)

    /** FULLDATE: "Sunday, September 27 2026" */
    fun fullDate(st: State, y: Int, m: Int, d: Int): String =
        format(st.word("FULLDATE"), st.weekdayName(weekday(y, m, d)), st.monthName(m), d, y)

    /** the entry's title as the game writes it on the day: "%s Begins", "%s Ends", "%s Unlocks", "%s Resets", or its name */
    fun entryTitle(st: State, e: Event, r: DayRef): String {
        val name = if ((e.ct == "RAID_LOCKOUT" || e.ct == "RAID_RESET") && e.diff != null)
            format(st.word("DUNGEON_NAME_WITH_DIFFICULTY"), e.title, e.diff) else e.title
        return when {
            e.ct == "HOLIDAY" && r.seq == "START" -> format(st.word("CALENDAR_EVENTNAME_FORMAT_START"), name)
            e.ct == "HOLIDAY" && r.seq == "END" -> format(st.word("CALENDAR_EVENTNAME_FORMAT_END"), name)
            e.ct == "RAID_LOCKOUT" -> format(st.word("CALENDAR_EVENTNAME_FORMAT_RAID_LOCKOUT"), name)
            e.ct == "RAID_RESET" -> format(st.word("CALENDAR_EVENTNAME_FORMAT_RAID_RESET"), name)
            else -> name
        }
    }

    /** the one gold line the game's day note adds after a player-made entry */
    fun goldLine(st: State, e: Event): String? {
        if (!e.playerMade) return null
        if (e.by != null && e.by == st.me) return st.word(when (e.ct) {
            "GUILD_ANNOUNCEMENT" -> "CALENDAR_ANNOUNCEMENT_CREATEDBY_YOURSELF"
            "GUILD_EVENT" -> "CALENDAR_GUILDEVENT_INVITEDBY_YOURSELF"
            "COMMUNITY_EVENT" -> "CALENDAR_COMMUNITYEVENT_INVITEDBY_YOURSELF"
            else -> "CALENDAR_EVENT_INVITEDBY_YOURSELF"
        })
        if (e.signUp) return if (e.inv == 7 || e.inv == 6) st.statusName(e.inv)
            else format(st.word("CALENDAR_SIGNEDUP_FOR_GUILDEVENT_WITH_STATUS"), st.statusName(e.inv))
        val who = e.by ?: return null
        return format(st.word(if (e.ct == "GUILD_ANNOUNCEMENT") "CALENDAR_ANNOUNCEMENT_CREATEDBY_PLAYER" else "CALENDAR_EVENT_INVITEDBY_PLAYER"), who)
    }

    /** the colour of an entry's name: an event I made or moderate (light blue) wins; then my status; the rest gold */
    enum class Tone { GOLD, MODERATOR, STATUS }
    fun tone(e: Event): Tone = when {
        !e.playerMade -> Tone.GOLD
        e.mod == "CREATOR" || e.mod == "MODERATOR" -> Tone.MODERATOR
        else -> Tone.STATUS
    }

    /** one line of the chosen day's list: the game's day note */
    data class Line(val event: Event, val title: String, val under: String, val picture: Int?, val tone: Tone,
                    val ongoing: Boolean)

    /** the chosen day's note: its entries by time (an END entry by its end time), then those ongoing, with their dates */
    fun dayNote(st: State, m: Month, day: Int): Pair<List<Line>, List<Line>> {
        val banner = bannerOn(m, day)?.first
        val entries = entriesOn(m, day).map { (e, r) ->
            val t = if (r.seq == "END") e.end else e.start
            val time = if (t != null) clock(st, t.h, t.mi) else ""
            val gold = goldLine(st, e)
            val pic = r.picture.takeIf { it > 0 } ?: e.icon?.takeIf { it > 0 && e.playerMade }
            Triple(t, Line(e, entryTitle(st, e, r), if (gold != null) "$time   $gold" else time,
                if (e === banner && pic == null) null else pic, tone(e), false), r.index)
        }.sortedWith(compareBy({ it.first?.h ?: 0 }, { it.first?.mi ?: 0 }, { it.third })).map { it.second }
        val ongoing = ongoingOn(m, day).map { e ->
            val range = if (e.start != null && e.end != null)
                format(st.word("CALENDAR_TOOLTIP_DATE_RANGE"), shortDate(st, e.start), shortDate(st, e.end)) else ""
            Line(e, e.title, range, e.on(day)?.picture?.takeIf { it > 0 }, Tone.GOLD, true)
        }
        return entries to ongoing
    }

    // ---------------------------------------------------------------- an event's page

    /** the kind of a player's event, with its dungeon when it has one: "Dungeon", "Dungeon - The Deadmines" */
    fun kind(st: State, e: Event, p: Page?): String {
        val et = p?.eventType ?: e.eventType
        val k = if (et != null && et in TYPE_KEYS.indices) st.word(TYPE_KEYS[et]) else ""
        val dun = p?.dungeon ?: return k
        return if (k.isEmpty()) dun else format(st.word("CALENDAR_VIEW_EVENTTYPE"), k, dun)
    }

    fun pageTitle(st: State, e: Event): String =
        if (e.locked) format(st.word("CALENDAR_VIEW_EVENTTITLE_LOCKED"), e.title) else e.title

    /** the buttons at the page's foot, top to bottom, with their pad columns (null = shown but not offered: DISABLED) */
    data class Answer(val op: String, val label: String, val col: Int?)

    fun answers(st: State, e: Event): List<Answer> {
        if (!e.playerMade || e.ct == "GUILD_ANNOUNCEMENT" || e.locked || e.mod == "CREATOR") return emptyList()
        val today = st.now?.dayNum ?: 0
        if ((e.start?.dayNum ?: 0) < today) return emptyList()
        val usable = st.padRow && !st.busy && !st.secret
        fun a(op: String, key: String) = Answer(op, st.word(key).trim(), if (usable) e.answers[op] else null)
        return if (e.signUp) listOfNotNull(if (e.inv == 7) a("sig", "CALENDAR_SIGNUP") else null)
        else listOf(a("acc", "ACCEPT"), a("ten", "CALENDAR_VIEW_EVENT_TENTATIVE"), a("dec", "DECLINE"))
    }

    /** an invited player's name as the game's invite list writes it: "%s (Creator)", "%s (Moderator)" */
    fun inviteeName(st: State, i: Invitee): String {
        val n = i.name ?: "?"
        return when (i.mod) {
            "CREATOR" -> format(st.word("CALENDAR_INVITELIST_CREATORNAME"), n)
            "MODERATOR" -> format(st.word("CALENDAR_INVITELIST_MODERATORNAME"), n)
            else -> n
        }
    }

    /** More's Remove: its column, when the game's day menu offers it now */
    fun removeCol(st: State, e: Event): Int? =
        if (st.padRow && !st.busy && !st.secret) e.answers["rem"] else null

    /** the holiday page's "Begins:" and "Ends:" labels, cut from the game's own sentence */
    fun beginsEnds(st: State): Pair<String, String> {
        val lines = st.word("CALENDAR_HOLIDAYFRAME_BEGINSENDS").split("|n").filter { it.contains('%') && !it.startsWith("%1") }
        fun label(s: String?, d: String) = s?.substringBefore('%')?.trim()?.takeIf { it.isNotEmpty() } ?: d
        return label(lines.getOrNull(0), "Begins:") to label(lines.getOrNull(1), "Ends:")
    }

    /** the raid page's one sentence */
    fun raidSentence(st: State, e: Event): String {
        val name = if (e.diff != null) format(st.word("DUNGEON_NAME_WITH_DIFFICULTY"), e.title, e.diff) else e.title
        val t = e.start
        val time = if (t != null) clock(st, t.h, t.mi) else ""
        return format(st.word(if (e.ct == "RAID_RESET") "CALENDAR_RAID_RESET_DESCRIPTION" else "CALENDAR_RAID_LOCKOUT_DESCRIPTION"), name, time)
    }

    // ---------------------------------------------------------------- the addon's answers and presses

    data class Ack(val op: String, val key: String?, val ok: Boolean, val why: String?)

    fun parseAck(msg: JSONObject): Ack? = if (msg.optString("type") != "calack") null
        else Ack(msg.optString("op"), msg.strOrNull("key"), msg.optBoolean("ok"), msg.strOrNull("why"))
}
