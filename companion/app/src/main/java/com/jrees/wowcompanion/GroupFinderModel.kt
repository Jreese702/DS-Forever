package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Group Finder room's data: the addon's `gfcat` and `gf` documents (WowCompanionGroupFinder g1) turned
 * into the board the way THIS client's own window shows it. Pure logic, no drawing, so
 * tools/groupfinder_test runs the real file off the device.
 *
 * What the game has (Blizzard's source, 2026-09-28): the Classic "Looking for Group" board
 * (Blizzard_GroupFinder_VanillaStyle, window LFGParentFrame), not the retail Dungeon Finder. The user's
 * ruling: BROWSE FIRST. What the game's Browse tab does, read from blizzard_lfgvanilla_browse.lua, and
 * mirrored here:
 * - results in the game's order, with a "Solo players" / "Groups" divider where the kind changes (your
 *   own listing sits under no divider);
 * - an activity filter does not hide a result, it greys it (the game's `matchesFilters`);
 * - the activity column reads "your listing", the one activity's short name, or "N activities" ("N
 *   matching" when some match your own listing);
 * - the right-hand column is picked by the best display type among the listing's activities: roles still
 *   needed, a row of role icons, a player count, or the description (protected text: the room can only
 *   say one exists); a solo player shows the roles they listed;
 * - Send Message is there when the leader is named; Group Invite only for a solo player, and only when
 *   you are not in a group or lead / assist it.
 */
object GroupFinderModel {

    const val PAD_ROW = "gf"

    // ---------------------------------------------------------------- the catalogue

    data class Activity(
        val id: Int,
        val name: String,           // the game prefers the short name
        val fullName: String?,      // only when it differs
        val group: Int?,            // activity group (e.g. the Scarlet Monastery wings)
        val suggestedMin: Int?,
        val suggestedMax: Int?,
        val minLevel: Int?,
        val maxPlayers: Int?,
        val displayType: Int?,      // Enum.LFGListDisplayType
        val order: Int?,
        val mapId: Int?,
        val saved: Boolean = false, // the character is saved to it (addon g5): the game's warning mark after the name
    )

    data class ActivityGroup(val id: Int, val name: String, val order: Int?)

    data class Category(
        val id: Int,
        val name: String,
        val autoChoose: Boolean,    // Quests & Zones, Custom: the game picks the activity itself
        val activities: List<Activity>,
        val groups: List<ActivityGroup>,
        val searchSlot: Int?,       // the pad column that searches it
    )

    data class Catalogue(
        val version: String,
        val level: Int?,
        val noLevelFilter: Boolean,
        val categories: List<Category>,
        val secret: Int,
        val threw: Int,
    )

    // ---------------------------------------------------------------- the state

    data class Roles(val tank: Boolean, val healer: Boolean, val dps: Boolean) {
        val any get() = tank || healer || dps
    }

    data class RoleCounts(val tank: Int, val healer: Int, val dps: Int, val none: Int = 0)

    data class Member(
        val name: String?, val level: Int?,
        val classFile: String?,     // PALADIN: for the class colour and icon
        val className: String?,     // the game's own word: "Paladin"
        val role: String?, val leader: Boolean, val roles: Roles?,
    )

    data class Result(
        val id: Int,
        val leader: String?,        // null when the game hides it
        val members: Int?,
        val activities: List<Int>,
        val self: Boolean,
        val delisted: Boolean,
        val newPlayerFriendly: Boolean,
        val hasDescription: Boolean,    // the text itself is protected and never arrives
        val friends: Int,
        val guildMates: Int,
        val need: RoleCounts?,
        val have: RoleCounts?,
        val memberList: List<Member>,
        val leaderClass: String?,
        val leaderClassName: String?,
        val leaderLevel: Int?,
        val soloRoles: Roles?,
        val invitable: Boolean,
        val inviteSlot: Int?,
        val ageSeconds: Int?,
    ) {
        val solo get() = members == 1
    }

    enum class SearchStatus { IDLE, SEARCHING, DONE, FAILED, TIMEOUT, ERROR }

    data class Search(
        val status: SearchStatus,
        val category: Int?,
        val why: String?,
        val mine: Boolean,
        val total: Int,
        val sorted: String?,
        val results: List<Result>,
    )

    data class Listing(
        val on: Boolean,
        val leader: Boolean,
        val activities: List<Int>,
        val newPlayerFriendly: Boolean,
        val durationSeconds: Int?,
        val hasDescription: Boolean,
    )

    data class State(
        val version: String,
        val enabled: Boolean?,          // the server's switch; null = the client could not say
        val canUse: Boolean?,
        val cannotReason: String?,
        val style: Int?,                // 2 = the vanilla board
        val lockdown: Boolean,          // an instance fight: the game hides the results from addons
        val combat: Boolean,
        val inGroup: Boolean,
        val leader: Boolean,
        val assistant: Boolean,
        val canInvite: Boolean,
        val roles: Roles?,
        val windowOpen: Boolean?,       // the game's own LFGParentFrame; null = not loaded
        val windowTab: String?,         // its shown tab: "listing", "browse" or "who" (addon g5); null = closed, or an older addon
        val listing: Listing,
        val delistSlot: Int?,
        val keys: Keys,                 // the pad columns of the fixed keys
        val controllerHeld: Boolean,    // the room holds the shared controller capture
        val controllerWhy: String?,     // why not, when a hold was refused
        val captureThere: Boolean,      // interact's capture is loaded in the game
        val controllerBack: Boolean = false,    // B is the game's own close now (its window is open, addon g6): the room never sees it
        val controllerShoulders: Boolean = false, // LB / RB come to the room (capture v4): they switch the tabs
        val controllerWait: String? = null,     // capture v11: "queued" (behind another holder) / "pending" (a fight's enter); not held yet
        val controllerTakes: Int = 0,           // how many times the hold became REAL (addon g9): the window opens only after a new one
        val search: Search,
        val draft: Draft?,              // Create Listing's draft (addon g4); null before g4
        val groupSize: Int?,            // in a group, its size (addon g5): the game's group-size rule for Post
        val closeSlot: Int?,            // the secure close cell (row gfclose, addon g6): set up once the game's window exists
        val openSlot: Int? = null,      // the secure OPEN cell (row gfclose column 1, addon g7): armed only while the window is shut
        val who: Who?,                  // the Who tab (addon g6); null before g6
        val pad: JSONObject?,           // for TapBridge
        val secret: Int,
        val threw: Int,
    )

    /**
     * the pad columns of the feed's fixed keys (g3): delist, "Your Listing", controller hold / free, free after the player
     * left with B (the capture is told "B"), and where the next B leads (capture v3's bnext: home / back)
     */
    data class Keys(val delist: Int?, val mine: Int?, val hold: Int?, val free: Int?,
                    val freeB: Int? = null, val bHome: Int? = null, val bBack: Int? = null)

    // ---------------------------------------------------------------- parsing

    private fun JSONObject.intOrNull(k: String): Int? = if (has(k)) optInt(k) else null
    private fun JSONObject.strOrNull(k: String): String? = if (has(k)) optString(k).ifEmpty { null } else null
    private fun ints(a: JSONArray?): List<Int> = if (a == null) emptyList() else (0 until a.length()).map { a.optInt(it) }
    private fun objs(a: JSONArray?): List<JSONObject> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun roles(o: JSONObject?): Roles? =
        o?.let { Roles(it.optBoolean("t"), it.optBoolean("h"), it.optBoolean("d")) }

    private fun counts(o: JSONObject?): RoleCounts? =
        o?.let { RoleCounts(it.optInt("t"), it.optInt("h"), it.optInt("d"), it.optInt("x")) }

    /** The `gfcat` message (the whole document); null when it is not one. */
    fun parseCatalogue(msg: JSONObject): Catalogue? {
        if (msg.optString("type") != "gfcat") return null
        val c = msg.optJSONObject("c") ?: return null
        val cats = objs(c.optJSONArray("cats")).map { k ->
            Category(
                id = k.optInt("id"),
                name = k.optString("n"),
                autoChoose = k.optBoolean("auto"),
                activities = objs(k.optJSONArray("acts")).map { a ->
                    Activity(
                        id = a.optInt("id"), name = a.optString("n"), fullName = a.strOrNull("full"),
                        group = a.intOrNull("g"), suggestedMin = a.intOrNull("smin"), suggestedMax = a.intOrNull("smax"),
                        minLevel = a.intOrNull("min"), maxPlayers = a.intOrNull("max"), displayType = a.intOrNull("dt"),
                        order = a.intOrNull("o"), mapId = a.intOrNull("map"), saved = a.optBoolean("sv"),
                    )
                },
                groups = objs(k.optJSONArray("grp")).map { ActivityGroup(it.optInt("id"), it.optString("n"), it.intOrNull("o")) },
                searchSlot = k.intOrNull("slot"),
            )
        }
        val sec = c.optJSONObject("sec")
        return Catalogue(c.optString("v"), c.intOrNull("lvl"), c.optBoolean("nofilter"), cats,
            sec?.optInt("s") ?: 0, sec?.optInt("t") ?: 0)
    }

    /** The `gf` message (the whole document); null when it is not one. */
    fun parseState(msg: JSONObject): State? {
        if (msg.optString("type") != "gf") return null
        val g = msg.optJSONObject("g") ?: return null
        val s = g.optJSONObject("search") ?: JSONObject()
        val results = objs(s.optJSONArray("res")).map { r ->
            Result(
                id = r.optInt("id"), leader = r.strOrNull("leader"), members = r.intOrNull("n"),
                activities = ints(r.optJSONArray("acts")), self = r.optBoolean("self"), delisted = r.optBoolean("del"),
                newPlayerFriendly = r.optBoolean("npf"), hasDescription = r.optBoolean("note"),
                friends = r.optInt("fr"), guildMates = r.optInt("gm"),
                need = counts(r.optJSONObject("need")), have = counts(r.optJSONObject("have")),
                memberList = objs(r.optJSONArray("mem")).map { m ->
                    Member(m.strOrNull("n"), m.intOrNull("lvl"), m.strOrNull("cls"), m.strOrNull("cn"), m.strOrNull("role"),
                        m.optBoolean("lead"), roles(m.optJSONObject("r")))
                },
                leaderClass = r.strOrNull("cls"), leaderClassName = r.strOrNull("cn"), leaderLevel = r.intOrNull("lvl"), soloRoles = roles(r.optJSONObject("r")),
                invitable = r.optBoolean("inv"), inviteSlot = r.intOrNull("slot"), ageSeconds = r.intOrNull("age"),
            )
        }
        val status = when (s.optString("st")) {
            "searching" -> SearchStatus.SEARCHING
            "done" -> SearchStatus.DONE
            "failed" -> SearchStatus.FAILED
            "timeout" -> SearchStatus.TIMEOUT
            "error" -> SearchStatus.ERROR
            else -> SearchStatus.IDLE
        }
        val me = g.optJSONObject("me") ?: JSONObject()
        val sec = g.optJSONObject("sec")
        return State(
            version = g.optString("v"),
            enabled = if (g.has("on")) g.optBoolean("on") else null,
            canUse = if (g.has("can")) g.optBoolean("can") else null,
            cannotReason = g.strOrNull("cannot"),
            style = g.intOrNull("style"),
            lockdown = g.optBoolean("lock"),
            combat = g.optBoolean("cb"),
            inGroup = g.optBoolean("grp"),
            leader = g.optBoolean("lead"),
            assistant = g.optBoolean("asst"),
            canInvite = g.optBoolean("inv"),
            roles = roles(g.optJSONObject("roles")),
            windowOpen = if (g.has("win")) g.optBoolean("win") else null,
            windowTab = g.strOrNull("wtab"),
            listing = Listing(me.optBoolean("on"), me.optBoolean("lead"), ints(me.optJSONArray("acts")),
                me.optBoolean("npf"), me.intOrNull("dur"), me.optBoolean("note")),
            delistSlot = g.intOrNull("dslot"),
            keys = g.optJSONObject("keys").let { k ->
                Keys(k?.intOrNull("d"), k?.intOrNull("sm"), k?.intOrNull("h"), k?.intOrNull("f"),
                    k?.intOrNull("fb"), k?.intOrNull("bh"), k?.intOrNull("bb"))
            },
            controllerHeld = g.optJSONObject("ctl")?.optBoolean("held") == true,
            controllerWhy = g.optJSONObject("ctl")?.strOrNull("why"),
            captureThere = g.optJSONObject("ctl")?.optBoolean("cap") == true,
            controllerBack = g.optJSONObject("ctl")?.optBoolean("back") == true,
            controllerShoulders = g.optJSONObject("ctl")?.optBoolean("lr") == true,
            controllerWait = g.optJSONObject("ctl")?.strOrNull("wait"),
            controllerTakes = g.optJSONObject("ctl")?.optInt("tn") ?: 0,
            search = Search(status, s.intOrNull("cat"), s.strOrNull("why"), s.optBoolean("mine"),
                s.optInt("total"), s.strOrNull("sorted"), results),
            draft = g.optJSONObject("draft")?.let { parseDraft(it) },
            groupSize = g.intOrNull("gsize"),
            closeSlot = g.intOrNull("cslot"),
            openSlot = g.intOrNull("oslot"),
            who = g.optJSONObject("who")?.let { parseWho(it) },
            pad = g.optJSONObject("pad"),
            secret = sec?.optInt("s") ?: 0,
            threw = sec?.optInt("t") ?: 0,
        )
    }

    private val LIST_KEYS = listOf("c2", "c114", "c116", "c118", "c120", "rt", "rh", "rd", "ln", "lp")

    private fun parseDraft(d: JSONObject): Draft {
        val k = d.optJSONObject("keys")
        val keys = HashMap<String, Int>()
        if (k != null) for (name in LIST_KEYS) k.intOrNull(name)?.let { keys[name] = it }
        return Draft(
            category = d.intOrNull("cat"), activities = ints(d.optJSONArray("acts")), newPlayers = d.optBoolean("npf"),
            dirty = d.optBoolean("dirty"), why = d.strOrNull("why"), posting = d.optBoolean("posting"),
            ticks = objs(d.optJSONArray("ticks")).map { Tick(it.optInt("id"), it.intOrNull("slot")) },
            groupTicks = objs(d.optJSONArray("gticks")).map { GroupTick(it.optInt("g"), it.intOrNull("slot")) },
            keys = keys, max = d.intOrNull("max"), newPlayersDefault = d.optBoolean("npfDef"),
            descriptionTyped = if (d.has("desc")) d.optBoolean("desc") else null,
        )
    }


    private fun parseWho(w: JSONObject): Who {
        val f = w.optJSONObject("f") ?: JSONObject()
        val k = f.optJSONObject("keys")
        val asked = w.optJSONObject("asked")
        return Who(
            filter = WhoFilter(
                level = f.optBoolean("lvl"), zone = f.optBoolean("zone"),
                classes = objs(f.optJSONArray("classes")).map {
                    WhoOption(it.optInt("id"), it.optString("n"), it.strOrNull("cls"), it.optBoolean("on"), it.intOrNull("slot"))
                },
                races = objs(f.optJSONArray("races")).map {
                    WhoOption(it.optInt("id"), it.optString("n"), null, it.optBoolean("on"), it.intOrNull("slot"))
                },
                searchSlot = k?.intOrNull("ws"), levelSlot = k?.intOrNull("wl"), zoneSlot = k?.intOrNull("wz"),
                classesAllSlot = k?.intOrNull("wca"), classesNoneSlot = k?.intOrNull("wcu"),
                racesAllSlot = k?.intOrNull("wra"), racesNoneSlot = k?.intOrNull("wru"),
                levelRange = f.strOrNull("lr"), zoneName = f.strOrNull("zn"),
            ),
            askedLevel = asked?.strOrNull("lvl"), askedZone = asked?.strOrNull("zone"),
            none = w.optBoolean("none"), count = w.optInt("n"), total = w.intOrNull("total"),
            at = if (w.has("at")) w.optDouble("at") else null,
            players = objs(w.optJSONArray("list")).map {
                WhoPlayer(it.optString("n"), it.strOrNull("guild"), it.intOrNull("lvl"), it.strOrNull("race"), it.strOrNull("cls"),
                    it.strOrNull("cn"), it.strOrNull("zone"), it.intOrNull("slot"))
            },
        )
    }

    // ---------------------------------------------------------------- lookups

    fun category(cat: Catalogue?, id: Int?): Category? = cat?.categories?.firstOrNull { it.id == id }

    fun activity(cat: Catalogue?, id: Int): Activity? {
        cat ?: return null
        for (c in cat.categories) c.activities.firstOrNull { it.id == id }?.let { return it }
        return null
    }

    // ---------------------------------------------------------------- the Browse tab, as the game draws it

    /** The activity column of a row (LFGBrowseSearchEntry_Update). */
    sealed class ActivityText {
        object Self : ActivityText()                                    // "your listing"
        data class One(val name: String) : ActivityText()
        data class Many(val count: Int, val matching: Boolean) : ActivityText()   // "N activities" / "N matching"
    }

    /** The activities of [r] that are also in your own listing (the game shows those first). */
    fun matchingMine(st: State, r: Result): List<Int> =
        if (!st.listing.on) emptyList() else r.activities.filter { it in st.listing.activities }

    fun activityText(cat: Catalogue?, st: State, r: Result): ActivityText {
        if (r.self) return ActivityText.Self
        val matching = matchingMine(st, r)
        val shown = if (matching.isNotEmpty()) matching else r.activities
        if (shown.size == 1) return ActivityText.One(activity(cat, shown[0])?.name ?: "")
        return ActivityText.Many(shown.size, matching.isNotEmpty())
    }

    /** Colour of a row: the game greys a delisted or filtered-out row, marks yours, and a match with yours. */
    enum class Tone { DIM, SELF, MATCH, NORMAL }

    fun matchesFilter(r: Result, filter: Set<Int>): Boolean = filter.isEmpty() || r.activities.any { it in filter }

    fun tone(st: State, r: Result, filter: Set<Int>): Tone = when {
        r.delisted || !matchesFilter(r, filter) -> Tone.DIM
        r.self -> Tone.SELF
        matchingMine(st, r).isNotEmpty() -> Tone.MATCH
        else -> Tone.NORMAL
    }

    /** The right-hand column (LFGBrowseGroupDataDisplay_Update). */
    enum class Display { SOLO_ROLES, ROLE_COUNT, ROLE_ENUMERATE, CLASS_ENUMERATE, PLAYER_COUNT, DESCRIPTION, NONE }

    private val DISPLAY_PRIORITY = mapOf(5 to 6, 0 to 5, 4 to 4, 1 to 3, 2 to 2, 3 to 1)   // LFGBROWSE_DISPLAYTYPE_PRIORITY

    /** The best display type among the listing's activities and the largest group size among them. */
    fun bestDisplay(cat: Catalogue?, r: Result): Pair<Int?, Int> {
        var best: Int? = null
        var bestP = 0
        var maxPlayers = 0
        for (id in r.activities) {
            val a = activity(cat, id) ?: continue
            val p = a.displayType?.let { DISPLAY_PRIORITY[it] } ?: 0
            if (p > bestP) { best = a.displayType; bestP = p }
            if ((a.maxPlayers ?: 0) > maxPlayers) maxPlayers = a.maxPlayers ?: 0
        }
        return best to maxPlayers
    }

    fun display(cat: Catalogue?, r: Result): Display {
        val (dt, _) = bestDisplay(cat, r)
        return when {
            dt == null -> Display.NONE
            dt == 5 -> Display.DESCRIPTION          // the text is protected: the room can only say there is one
            r.solo -> if (r.soloRoles?.any == true) Display.SOLO_ROLES else Display.NONE
            dt == 0 -> Display.ROLE_COUNT
            dt == 1 -> Display.ROLE_ENUMERATE
            dt == 2 -> Display.CLASS_ENUMERATE
            dt == 4 -> Display.PLAYER_COUNT
            else -> Display.NONE                    // HideAll
        }
    }

    enum class Slot { TANK, HEALER, DPS, EMPTY }

    /**
     * The role icons of a group, LEFT to RIGHT as the game lays them out (LFGBrowseGroupDataDisplayEnumerate_Update
     * fills from the right: tanks, then healers, then damage, then empty slots on the left).
     */
    fun roleIcons(cat: Catalogue?, r: Result): List<Slot> {
        val (_, maxPlayers) = bestDisplay(cat, r)
        val have = r.have ?: return emptyList()
        val fromRight = ArrayList<Slot>()
        repeat(have.tank) { fromRight.add(Slot.TANK) }
        repeat(have.healer) { fromRight.add(Slot.HEALER) }
        repeat(have.dps) { fromRight.add(Slot.DPS) }
        val n = maxPlayers.coerceAtLeast(0)
        val filled = fromRight.take(n)
        return List(n - filled.size) { Slot.EMPTY } + filled.reversed()
    }

    /** The game's player count: every role plus those without one. */
    fun playerCount(r: Result): Int? = r.have?.let { it.tank + it.healer + it.dps + it.none }

    /** One line of the list: a divider, or a result. */
    sealed class Row {
        data class Divider(val solo: Boolean) : Row()                  // "Solo players" / "Groups"
        data class Entry(val result: Result, val tone: Tone) : Row()
    }

    /**
     * The list as the game builds it (LFGBrowseMixin:UpdateResults, collapsible categories on): the results in the
     * order they came, a divider where the kind changes between solo and group; your own listing gets none.
     * Empty while the game hides the results (an instance fight).
     */
    fun rows(st: State, filter: Set<Int> = emptySet()): List<Row> {
        if (st.lockdown) return emptyList()
        val out = ArrayList<Row>()
        var current: Boolean? = null
        for (r in st.search.results) {
            if (!r.self) {
                val solo = (r.members ?: 0) <= 1
                if (solo != current) { current = solo; out.add(Row.Divider(solo)) }
            }
            out.add(Row.Entry(r, tone(st, r, filter)))
        }
        return out
    }


    // ---------------------------------------------------------------- the head row, as the game builds it

    /**
     * One row of the Category list (LFGBrowseCategoryDropdownMixin:SetupDropdown): "Your Listing" first while I am
     * listed (its search is the game's SearchActiveEntry), then every category that has at least one activity in the
     * game's own (level-filtered) list. Choosing a row closes the list and searches: ONE tap on [slot].
     */
    data class CategoryRow(val mine: Boolean, val category: Category?, val slot: Int?)

    fun categoryRows(cat: Catalogue?, st: State?): List<CategoryRow> {
        val out = ArrayList<CategoryRow>()
        if (st?.listing?.on == true) out.add(CategoryRow(true, null, st.keys.mine))
        cat?.categories?.forEach { if (it.activities.isNotEmpty()) out.add(CategoryRow(false, it, it.searchSlot)) }
        return out
    }

    /** The category the game shows in its drop-down after "Your Listing" was chosen: my first activity's */
    fun categoryOfMine(cat: Catalogue?, st: State): Int? {
        for (id in st.listing.activities) cat?.categories?.firstOrNull { c -> c.activities.any { it.id == id } }?.let { return it.id }
        return null
    }

    /** the game's activity order (LFGUtil_SortActivityIDs): order index, then the name without case, then the id */
    private val activityOrder = compareBy<Activity>({ it.order ?: 0 }, { it.name.lowercase() }, { it.id })

    /** One row of the Filter by activity list: a loose activity, or an activity group that opens a list of its own. */
    sealed class FilterRow {
        data class One(val activity: Activity) : FilterRow()
        data class Group(val group: ActivityGroup, val activities: List<Activity>) : FilterRow()
    }

    /**
     * The Filter by activity list (LFGBrowseActivityDropdownMixin:SetupDropdown): the loose activities first (the game's
     * activity group 0 sorts first), then each activity group by its order index, name, id, with its activities in
     * the game's activity order.
     */
    fun filterRows(c: Category?): List<FilterRow> {
        c ?: return emptyList()
        val loose = c.activities.filter { it.group == null }.sortedWith(activityOrder).map { FilterRow.One(it) }
        val groups = c.activities.filter { it.group != null }.groupBy { it.group!! }.map { (gid, acts) ->
            FilterRow.Group(c.groups.firstOrNull { it.id == gid } ?: ActivityGroup(gid, "", null), acts.sortedWith(activityOrder))
        }.sortedWith(compareBy<FilterRow.Group>({ it.group.order ?: Int.MAX_VALUE }, { it.group.name.lowercase() }, { it.group.id }))
        return loose + groups
    }

    /** a group reads as ticked when ANY of its activities is (IsAnyValueSelectedForActivityGroup) */
    fun groupTicked(g: FilterRow.Group, ticked: Set<Int>): Boolean = g.activities.any { it.id in ticked }

    /** ticking a group sets all of its activities to the opposite of "any ticked" (SetAllValuesForActivityGroup) */
    fun toggleGroup(g: FilterRow.Group, ticked: Set<Int>): Set<Int> =
        if (groupTicked(g, ticked)) ticked - g.activities.map { it.id }.toSet() else ticked + g.activities.map { it.id }

    /** What the Filter by activity drop-down says: nothing ticked = null (the game's "Filter by activity"); one = its name; more = the count */
    sealed class FilterLabel {
        object None : FilterLabel()
        data class One(val name: String) : FilterLabel()
        data class Count(val n: Int) : FilterLabel()
    }

    fun filterLabel(cat: Catalogue?, ticked: Set<Int>): FilterLabel = when (ticked.size) {
        0 -> FilterLabel.None
        1 -> FilterLabel.One(activity(cat, ticked.first())?.name ?: "")
        else -> FilterLabel.Count(ticked.size)
    }


    // ---------------------------------------------------------------- Create Listing (step two; the game's own window, user 2026-09-30)

    const val LIST_ROW = "gfl"

    data class Tick(val id: Int, val slot: Int?)

    /**
     * The addon's draft (g4): the category, the ticked activities, New Players Welcome, whether anything changed, a post
     * waiting for the game, the draft category's tick columns, the row's fixed keys ("c<cat>", "rt" "rh" "rd", "ln", "lp"),
     * the game's activity limit, and whether the game's own description box holds typed text (never the text).
     */
    data class Draft(
        val category: Int?, val activities: List<Int>, val newPlayers: Boolean, val dirty: Boolean, val why: String?,
        val posting: Boolean, val ticks: List<Tick>, val keys: Map<String, Int>, val max: Int?, val newPlayersDefault: Boolean,
        val descriptionTyped: Boolean?,
        val groupTicks: List<GroupTick> = emptyList(),      // the game's group line: ONE tick for all its activities (addon g5)
    )

    data class GroupTick(val group: Int, val slot: Int?)

    /** a category button of the Categories view: the game's rule (at least one activity at my level); greyed when it needs a description */
    data class ListingCategory(val category: Category, val needsDescription: Boolean, val slot: Int?)

    fun listingCategories(cat: Catalogue?, st: State?): List<ListingCategory> =
        cat?.categories?.filter { it.activities.isNotEmpty() }?.map {
            ListingCategory(it, it.autoChoose, st?.draft?.keys?.get("c${it.id}"))
        } ?: emptyList()

    fun listRowPublished(st: State): Boolean = st.pad?.optJSONObject("rows")?.has(LIST_ROW) == true

    /**
     * The level column of an activity row as the game fills it (LFGListingActivityView_InitActivityButton): one level
     * (LFD_LEVEL_FORMAT_SINGLE "(%d)") when the suggested minimum and maximum are the same or the maximum is 0, nothing when that
     * one level is 0; else the range (LFD_LEVEL_FORMAT_RANGE "(%d - %d)").
     */
    fun levelText(a: Activity): String? {
        val lo = a.suggestedMin ?: return null
        val hi = a.suggestedMax ?: 0
        if (lo == hi || hi == 0) return if (lo == 0) null else "($lo)"
        return "($lo - $hi)"
    }

    /** One line of the listing's activity view: a group line (its own tick for all; a fold button when the game shows one), or an activity */
    sealed class ListingRow {
        data class Group(val group: ActivityGroup, val activities: List<Activity>, val foldable: Boolean, val open: Boolean) : ListingRow()
        data class One(val activity: Activity, val inGroup: Boolean) : ListingRow()
    }

    /** the game's activity order in the listing (ActivitySortComparator): order index high first, then the highest level (0 first), the lowest, the name */
    private val listingActivityOrder = Comparator<Activity> { l, r ->
        val lo = l.order ?: 0; val ro = r.order ?: 0
        val lMax = l.suggestedMax ?: 0; val rMax = r.suggestedMax ?: 0
        val lMin = l.suggestedMin ?: 0; val rMin = r.suggestedMin ?: 0
        when {
            lo != ro -> ro.compareTo(lo)
            lMax != rMax -> if (lMax == 0 || rMax == 0) (if (lMax == 0) -1 else 1) else rMax.compareTo(lMax)
            lMin != rMin -> rMin.compareTo(lMin)
            else -> l.name.lowercase().compareTo(r.name.lowercase())
        }
    }

    private fun listingGroups(c: Category): List<Pair<ActivityGroup, List<Activity>>> =
        c.activities.filter { it.group != null }.groupBy { it.group!! }.map { (gid, acts) ->
            (c.groups.firstOrNull { it.id == gid } ?: ActivityGroup(gid, "", null)) to acts.sortedWith(listingActivityOrder)
        }.sortedWith(compareBy<Pair<ActivityGroup, List<Activity>>>({ it.first.order ?: 0 }, { it.first.name.lowercase() }))

    /**
     * The groups the game folds when it opens the category: with more than one group, a group none of whose activities suits my
     * level (suggested minimum <= level <= maximum) starts folded.
     */
    fun foldedAtStart(c: Category?, level: Int?): Set<Int> {
        c ?: return emptySet()
        val groups = listingGroups(c)
        if (groups.size <= 1 || level == null) return emptySet()
        return groups.filter { (_, acts) -> acts.none { (it.suggestedMin ?: 0) <= level && level <= (it.suggestedMax ?: 0) } }
            .map { it.first.id }.toSet()
    }

    /**
     * The listing's activity view as the game builds it (LFGListingActivityView_UpdateActivities): the loose activities first, in the
     * game's activity order, then each group (order index, name) with its activities under it unless [folded].
     */
    fun listingRows(c: Category?, folded: Set<Int>): List<ListingRow> {
        c ?: return emptyList()
        val out = ArrayList<ListingRow>()
        c.activities.filter { it.group == null }.sortedWith(listingActivityOrder).forEach { out += ListingRow.One(it, false) }
        val groups = listingGroups(c)
        for ((g, acts) in groups) {
            val open = g.id !in folded
            out += ListingRow.Group(g, acts, groups.size > 1, open)
            if (open) acts.forEach { out += ListingRow.One(it, true) }
        }
        return out
    }

    /** The game's group-size rule: the smallest maximum among the ticked activities that the group already fills; null = fine */
    fun groupTooBig(cat: Catalogue?, st: State): Int? {
        val d = st.draft ?: return null
        if (!st.inGroup) return null
        val n = st.groupSize ?: return null
        return d.activities.mapNotNull { activity(cat, it)?.maxPlayers }.filter { it != 0 && it <= n }.minOrNull()
    }

    /** Which view of the game's listing tab shows: Locked (in a group, not its leader), Categories, or Activities */
    enum class ListingView { LOCKED, CATEGORIES, ACTIVITIES }

    fun listingView(st: State): ListingView = when {
        st.inGroup && !st.leader -> ListingView.LOCKED
        st.draft?.category == null -> ListingView.CATEGORIES
        else -> ListingView.ACTIVITIES
    }

    /** the Post button's word, the game's three (LFGListingPostButton_UpdateText): update while listed, the group's, mine */
    enum class PostWord { UPDATE, PARTY, SOLO }

    fun postWord(st: State): PostWord = when {
        st.listing.on -> PostWord.UPDATE
        st.inGroup -> PostWord.PARTY
        else -> PostWord.SOLO
    }

    /** While listed, the game's Back button reads "Unlist" and ends the listing (user: "Yes, as the game") */
    fun backUnlists(st: State): Boolean = st.listing.on

    // COMBAT stays in the enums only for older code that names it: since addon g8 / the user's combat spec (SPEC_COMBAT 4, 2026-10-01)
    // a fight greys nothing here, because the game's own LFG code allows all of it in a fight
    enum class PostGate { OK, NO_COLUMN, WAITING, COMBAT, LOCKED, NO_CATEGORY, NEEDS_DESCRIPTION, TOO_MANY, NOTHING_CHANGED, NO_ACTIVITY, GROUP_TOO_BIG }

    /** Whether Post works now: the game's rules, in the order the addon checks them */
    fun postGate(cat: Catalogue?, st: State?): PostGate {
        val d = st?.draft ?: return PostGate.NO_COLUMN
        return when {
            !listRowPublished(st) || d.keys["lp"] == null -> PostGate.NO_COLUMN
            d.posting -> PostGate.WAITING
            st.inGroup && !st.leader -> PostGate.LOCKED
            d.category == null -> PostGate.NO_CATEGORY
            category(cat, d.category)?.autoChoose == true -> PostGate.NEEDS_DESCRIPTION
            d.max != null && d.activities.size > d.max -> PostGate.TOO_MANY
            !d.dirty -> PostGate.NOTHING_CHANGED
            !st.listing.on && d.activities.isEmpty() -> PostGate.NO_ACTIVITY
            groupTooBig(cat, st) != null -> PostGate.GROUP_TOO_BIG
            else -> PostGate.OK
        }
    }

    // ---------------------------------------------------------------- what can be done

    /** The actions for a selected row, the first being the ONE the room shows (user: select, then one action). */
    sealed class Action {
        data class Invite(val name: String, val slot: Int) : Action()     // one tap on the pad
        data class Whisper(val name: String) : Action()                   // the Chat room's composer, app side
    }

    /**
     * [canWhisper] is the Chat room's own rule for a name its composer can address (`ChatNames.canWhisper` on the
     * chat branch: typed chat carries plain letters only, so an accented name or a realm suffix cannot be spelt).
     * The view passes it in; this file does not depend on the Chat room.
     */
    fun actions(st: State, r: Result, canWhisper: (String) -> Boolean = { true }): List<Action> {
        val out = ArrayList<Action>()
        val name = r.leader ?: return out
        if (r.self || st.lockdown) return out
        if (r.invitable && st.canInvite && r.inviteSlot != null && padRowPublished(st)) out.add(Action.Invite(name, r.inviteSlot))
        if (canWhisper(name)) out.add(Action.Whisper(name))
        return out
    }

    fun padRowPublished(st: State): Boolean = st.pad?.optJSONObject("rows")?.has(PAD_ROW) == true

    enum class SearchGate { OK, OFF, COMBAT, NO_COLUMN, BUSY }

    /** Whether the Search button for [c] works now, and why not. */
    fun searchGate(st: State?, c: Category?): SearchGate = when {
        st == null || c == null -> SearchGate.NO_COLUMN
        st.enabled == false || st.canUse == false -> SearchGate.OFF
        c.searchSlot == null || !padRowPublished(st) -> SearchGate.NO_COLUMN
        st.search.status == SearchStatus.SEARCHING -> SearchGate.BUSY
        else -> SearchGate.OK
    }

    /** Your own listing as the minimap eye's row (the pvp session draws it): names, and whether Unlist works. */
    data class EyeRow(val activityNames: List<String>, val canUnlist: Boolean, val unlistSlot: Int?, val secondsLeft: Int?)

    fun eyeRow(cat: Catalogue?, st: State): EyeRow? {
        if (!st.listing.on) return null
        val names = st.listing.activities.map { activity(cat, it)?.name ?: "" }.filter { it.isNotEmpty() }
        val can = st.listing.leader && st.delistSlot != null && padRowPublished(st)
        return EyeRow(names, can, st.delistSlot, st.listing.durationSeconds)
    }


    // ---------------------------------------------------------------- Who, the game's tab 3 (user 2026-09-30; Social's code, addon g6)

    const val WHO_ROW = "gfw"
    const val CLOSE_ROW = "gfclose"

    /** One option of the Who filter: a class (with its file, for the colour) or a race; [slot] = its gfw column */
    data class WhoOption(val id: Int, val name: String, val classFile: String?, val on: Boolean, val slot: Int?)

    /**
     * The Who filter as the game's: My level and My zone off at first, every class and race TICKED at first; a search leaves out
     * the unticked ones (so Uncheck All finds nobody, as in the game). Check All / Uncheck All are one tap each.
     */
    data class WhoFilter(
        val level: Boolean, val zone: Boolean, val classes: List<WhoOption>, val races: List<WhoOption>,
        val searchSlot: Int?, val levelSlot: Int?, val zoneSlot: Int?,
        val classesAllSlot: Int? = null, val classesNoneSlot: Int? = null, val racesAllSlot: Int? = null, val racesNoneSlot: Int? = null,
        val levelRange: String? = null,     // what My level asks now: "18-22"
        val zoneName: String? = null,       // where My zone is now
    ) {
        /** anything narrower than everyone */
        val any get() = level || zone || classes.any { !it.on } || races.any { !it.on }
    }

    /**
     * What the filter drop-down reads (UI Art's WHO_SPEC 1: "Level 13 - 17", "Elwynn Forest", "All players"): level, zone, then the
     * classes and races when not all are ticked (one name, or a count), joined; nothing narrower than everyone reads "All players".
     */
    fun whoFilterWords(f: WhoFilter): String {
        val parts = ArrayList<String>()
        if (f.level) f.levelRange?.let { parts += "Level " + it.replace("-", " - ") }
        if (f.zone) parts += f.zoneName ?: "My zone"
        fun some(opts: List<WhoOption>, many: String) {
            val on = opts.filter { it.on }
            if (opts.isEmpty() || on.size == opts.size) return
            parts += when (on.size) { 0 -> "No $many"; 1 -> on[0].name; else -> "${on.size} $many" }
        }
        some(f.classes, "classes")
        some(f.races, "races")
        return if (parts.isEmpty()) "All players" else parts.joinToString(", ")
    }

    /** The game's totals line (WHO_FRAME_TOTAL_TEMPLATE, its plural rule), with "(N displayed)" when the server found more than shown */
    fun whoTotals(w: Who): String {
        val n = w.total ?: w.count
        val found = if (n == 1) "1 Person Found" else "$n People Found"
        return if (n > w.players.size && w.players.isNotEmpty()) "$found  (${w.players.size} displayed)" else found
    }

    data class WhoPlayer(
        val name: String, val guild: String?, val level: Int?, val race: String?,
        val classFile: String?, val className: String?, val zone: String?,
        val slot: Int?,         // the invite column; null = no column left (shown as not invitable from the room)
    )

    data class Who(
        val filter: WhoFilter,
        val askedLevel: String?, val askedZone: String?,   // what the last search asked, for the line over the list
        val none: Boolean,                                  // nothing asked yet this session
        val count: Int, val total: Int?,
        val players: List<WhoPlayer>,
        val at: Double? = null,                             // the answer's stamp: a new answer, even with the same list, has a new one
    )

    fun whoRowPublished(st: State): Boolean = st.pad?.optJSONObject("rows")?.has(WHO_ROW) == true

    enum class WhoGate { OK, OFF, COMBAT, NO_COLUMN }

    /** Whether the Who search works now (a real press: one tap). A fight changes nothing (the game allows it; SPEC_COMBAT 4) */
    fun whoSearchGate(st: State?): WhoGate = when {
        st?.who == null -> WhoGate.OFF
        st.who.filter.searchSlot == null || !whoRowPublished(st) -> WhoGate.NO_COLUMN
        else -> WhoGate.OK
    }

    /** The actions for a selected Who row, the first being the ONE the room shows: Invite (Social's primary), then Whisper */
    fun whoActions(st: State, p: WhoPlayer, canWhisper: (String) -> Boolean = { true }): List<Action> {
        val out = ArrayList<Action>()
        if (p.slot != null && st.canInvite && whoRowPublished(st)) out.add(Action.Invite(p.name, p.slot))
        if (canWhisper(p.name)) out.add(Action.Whisper(p.name))
        return out
    }

    /**
     * The column that OPENS the game's own window (the secure cell clicking the game's Group Finder button), when the room is opened by
     * touch or the bar (the user, 2026-10-01: "Every room opens the game's own window behind it"). Null while the window is open (the
     * addon makes the cell inert then, so a stale tap could not toggle it shut either) or when the pad does not publish the row.
     */
    fun openTap(st: State?): Int? =
        if (st?.openSlot != null && st.windowOpen != true && st.pad?.optJSONObject("rows")?.has(CLOSE_ROW) == true) st.openSlot else null

    /** The column that closes the game's own window (the secure cell), when the pad publishes it; null = none */
    fun closeTap(st: State?): Int? =
        if (st?.closeSlot != null && st.pad?.optJSONObject("rows")?.has(CLOSE_ROW) == true) st.closeSlot else null
}
