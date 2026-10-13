package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Social room's data: the addon's `social` documents (WowCompanionSocial so2, one document per PART)
 * merged into the lists the room shows, which actions each row allows, and the ONE tap each touch sends.
 * Pure logic, no drawing, so tools/social_test runs the real file off the device. The look comes from UI
 * Art's design (not drawn yet); nothing here decides it.
 *
 * No Who here (the user's answer 2026-09-30: "Only in Group Finder, like the game"): Who is the Group Finder room's tab 3.
 * A `who` part from an older addon is kept but belongs to no tab, so it is never shown.
 *
 * The tap protocol (addon so2, pad rows `spick` + `sact`):
 * - a TAB touch sends one `sact` view tap (no game action); the addon then re-sends that list with `slot`s;
 * - a ROW touch sends one `spick` tap at the row's slot (no game action); the addon's reply NAMES the player;
 * - an ACTION touch sends one `sact` tap with the action's code, and only when the addon's last pick names
 *   the selected row (so a stale list can never act on someone else). Whisper is the Chat room's, no tap.
 * The actions the room confirms first carry [Act.confirm]; the confirmation shows [pickedName], the addon's
 * own understanding of the target.
 */
class SocialModel {

    enum class Tab(val part: String, val viewCode: String) {
        FRIENDS("friends", "vfriends"), IGNORE("ignore", "vignore"), GUILD("guild", "vguild"),
        RAID("group", "vgroup");

        companion object { fun ofPart(p: String) = values().firstOrNull { it.part == p } }
    }

    enum class Act(val code: String, val confirm: Boolean = false, val onRoom: Boolean = false, val word: String = "") {
        WHISPER("", word = "Whisper"),                // the Chat room's composer; no tap
        INVITE("invite", word = "Invite"), UNINVITE("uninvite", confirm = true, word = "Remove from Group"),
        LEADER("leader", confirm = true, word = "Make Leader"),
        ASSIST("assist", word = "Make Assistant"), DEMOTE("demote", word = "Demote"),
        ADD_FRIEND("addfriend", word = "Add Friend"), UNFRIEND("unfriend", confirm = true, word = "Remove Friend"),
        IGNORE("ignore", confirm = true, word = "Ignore"), UNIGNORE("unignore", word = "Unignore"),
        GUILD_INVITE("ginvite", word = "Guild Invite"), GUILD_REMOVE("gremove", confirm = true, word = "Remove from Guild"),
        GUILD_PROMOTE("gpromote", word = "Promote"), GUILD_DEMOTE("gdemote", word = "Demote"),
        GROUP1("group1"), GROUP2("group2"), GROUP3("group3"), GROUP4("group4"),
        GROUP5("group5"), GROUP6("group6"), GROUP7("group7"), GROUP8("group8"),
        TO_RAID("toraid", onRoom = true), TO_PARTY("toparty", onRoom = true), ALL_ASSIST("allassist", onRoom = true);

        companion object {
            fun moveTo(group: Int) = values().first { it.code == "group$group" }
            fun ofCode(c: String) = values().firstOrNull { it.code == c && c.isNotEmpty() }
        }
    }

    /** One row of any list. [actName] = the name the game's calls take (character, "Name-Realm"); null = cannot be acted on. */
    data class Row(
        val tab: Tab,
        val label: String,          // what the row is called: a character name, or a BattleTag
        val actName: String?,       // the character to act on (a Battle.net friend: the one they play now)
        val slot: Int?,             // tap-pad column while this list is the shown one
        val online: Boolean,
        val me: Boolean = false,
        val level: Int? = null,
        val cls: String? = null,    // class FILE ("MAGE"): colour through Theme
        val zone: String? = null,
        val afk: Boolean = false,
        val dnd: Boolean = false,
        val note: String? = null,
        val officerNote: String? = null,
        val bnTag: String? = null,
        val game: String? = null,   // Battle.net: the program they are in ("WoW", "BSAp" = the app)
        val favourite: Boolean = false,
        val rank: Int = 0,          // group: 2 leader, 1 assistant; guild: the rank's index (0 = guild master)
        val rankName: String? = null,
        val group: Int? = null,     // raid group 1..8
        val role: String? = null,   // TANK / HEALER / DAMAGER
        val dead: Boolean = false,
        val ready: String? = null,  // ready check: ready / notready / waiting
        val guild: String? = null,
        val race: String? = null,
        val hoursAgo: Int? = null,  // guild: offline for this many hours
        val lastOnline: Long? = null, // Battle.net: seconds since 1970 (0 = unknown)
        val pet: String? = null,    // group: the hunter's or warlock's pet out now ("" = one without a name yet)
        val mainTank: Boolean = false,   // raid marks, as the game's raid window's Main Tank / Main Assist buttons
        val mainAssist: Boolean = false,
    )

    /** One tap: the pad row's NAME and the column. */
    data class Tap(val row: String, val col: Int)

    /** The game's answer to the last action, for the room's status line. */
    data class Answer(val op: String, val name: String?, val ok: Boolean, val why: String?, val said: String?)

    private val parts = HashMap<String, JSONObject>()
    var version: String? = null; private set
    var pickedName: String? = null; private set
    var pickedTag: String? = null; private set
    var pickedTab: Tab? = null; private set
    private var pickedAtMs = 0L
    var answer: Answer? = null; private set

    /** Feed one wire document. True when the room's picture may have changed. */
    fun apply(doc: JSONObject, nowMs: Long): Boolean {
        return when (doc.optString("type")) {
            "social" -> {
                val k = doc.optString("k")
                val s = doc.optJSONObject("s") ?: return false
                if (k.isEmpty()) return false
                parts[k] = s
                version = doc.optString("v", "")
                // a list re-sent without slots is no longer the shown one: a pick from it is void
                if (Tab.ofPart(k) == pickedTab && !hasSlots(k)) clearPick()
                true
            }
            "socialack" -> { onAck(doc, nowMs); true }
            else -> false
        }
    }

    private fun onAck(a: JSONObject, nowMs: Long) {
        val op = a.optString("op")
        val ok = a.optBoolean("ok", true)
        val name = if (a.has("n")) a.optString("n") else null
        when (op) {
            "pick" -> if (ok) {
                pickedName = name
                pickedTag = if (a.has("tag")) a.optString("tag") else null
                pickedTab = Tab.ofPart(a.optString("k"))
                pickedAtMs = nowMs
            } else clearPick()
            "view" -> clearPick()
            else -> {
                answer = Answer(op, name, ok, if (a.has("why")) a.optString("why") else null,
                    if (a.has("said")) a.optString("said") else answer?.takeIf { it.op == op && it.name == name }?.said)
                val act = Act.ofCode(op)
                if (act != null && act.confirm && ok) clearPick()
            }
        }
    }

    private fun clearPick() { pickedName = null; pickedTag = null; pickedTab = null; pickedAtMs = 0L }

    // ---------------------------------------------------------------- the addon's state

    private val me get() = parts["me"]
    val shown: Tab? get() = me?.optString("view")?.let { Tab.ofPart(it) }
    val rowsGranted: Boolean get() = me?.optBoolean("rows", false) ?: false
    val pad: JSONObject? get() = me?.optJSONObject("pad")
    val inCombat: Boolean get() = me?.optBoolean("cb", false) ?: false
    val isLeader: Boolean get() = me?.optBoolean("lead", false) ?: false
    val isAssistant: Boolean get() = me?.optBoolean("assist", false) ?: false
    val canInvite: Boolean get() = me?.optBoolean("canInvite", true) ?: true
    /**
     * Can a CHARACTER be added as a friend here? Only through the old friend list or a "title friend" request (the game's own
     * AddFriendFrame_Accept). WoW: Forever has BOTH off (measured on the Thor 2026-09-30: legacyFriends false, titleFriends
     * false): the game then adds friends only by BattleTag, typed in its own box, and its own Who menu offers no Add Friend. So the
     * room offers it only when the server has one of the two on (a feed older than so5 does not say: then the old list decides).
     */
    val canAddFriend: Boolean get() = me?.optJSONObject("sys")?.let { s ->
        s.optBoolean("titleFriends", false) || s.optBoolean("legacyFriends", !s.has("legacyFriends"))
    } ?: true
    val groupKind: String get() = parts["group"]?.optString("kind", "none") ?: "none"
    /** Hunters' and warlocks' pets out in the group (the game's raid window counts them on a button of their own). */
    val pets: Int get() = parts["group"]?.optInt("pets", 0) ?: 0
    val inRaid: Boolean get() = groupKind == "raid"
    val inGuild: Boolean get() = parts["guild"]?.optBoolean("inGuild", false) ?: false
    /** The game hides the guild list right now (its chat restrictions): show that, never an empty list. */
    val guildHidden: Boolean get() = parts["guild"]?.optBoolean("hidden", false) ?: false
    val guildCut: Int get() = parts["guild"]?.optInt("cut", 0) ?: 0
    val guildName: String? get() = parts["guild"]?.let { str(it, "name") }
    val guildMembers: Int get() = parts["guild"]?.optInt("n", 0) ?: 0
    val guildOnline: Int get() = parts["guild"]?.optInt("on", 0) ?: 0
    val guildMotd: String? get() = parts["guild"]?.let { str(it, "motd") }
    /** the room holds the controller (a guest of the shared capture) */
    val controllerHeld: Boolean get() = me?.optBoolean("held", false) ?: false
    /** the GAME opened its own Friends / Raid / Guild window: which, and a count that grows at every opening (its Who page is
     *  the Group Finder room's: an older addon's "who" opens nothing here) */
    val gameOpened: Tab? get() = me?.let { str(it, "open") }?.let { when (it) { "friends" -> Tab.FRIENDS; "raid" -> Tab.RAID;
        "guild" -> Tab.GUILD; else -> null } }
    val gameOpenedN: Int get() = me?.optInt("openN", 0) ?: 0
    private fun guildCan(k: String) = parts["guild"]?.optJSONObject("can")?.optBoolean(k, false) ?: false

    /** The code's column on `sact`, from the addon's own table; null when this build does not know it. */
    fun codeCol(code: String): Int? {
        val c = me?.optJSONObject("codes") ?: return null
        return if (c.has(code)) c.optInt(code, -1).takeIf { it >= 0 } else null
    }

    private fun hasSlots(part: String): Boolean = rowsOfPart(part).any { it.slot != null }

    // ---------------------------------------------------------------- lists

    fun rows(tab: Tab): List<Row> = rowsOfPart(tab.part).filter { it.tab == tab }

    private fun rowsOfPart(part: String): List<Row> {
        val s = parts[part] ?: return emptyList()
        return when (part) {
            "group" -> objs(s.optJSONArray("list")).map { m ->
                Row(Tab.RAID, m.optString("n"), m.optString("n"), slotOf(m), m.optBoolean("on", false),
                    me = m.optBoolean("me", false), level = int(m, "lvl"), cls = str(m, "cls"), zone = str(m, "zone"),
                    rank = m.optInt("rank", 0), group = int(m, "sub"), role = str(m, "role"), dead = m.optBoolean("dead", false),
                    ready = str(m, "rc"), race = str(m, "race"), guild = str(m, "guild"),
                    pet = when { !m.has("pet") -> null; m.optBoolean("pet", false) -> ""; else -> m.optString("pet") },
                    mainTank = m.optBoolean("mt", false), mainAssist = m.optBoolean("ma", false))
            }
            "friends" -> objs(s.optJSONArray("bn")).map { b ->
                val char = str(b, "char")
                val realm = str(b, "realm")
                Row(Tab.FRIENDS, b.optString("tag"), char?.let { if (realm != null) "$it-$realm" else it }, slotOf(b),
                    b.optBoolean("on", false), level = int(b, "lvl"), cls = str(b, "cls"), zone = str(b, "area"),
                    afk = b.optBoolean("afk", false), dnd = b.optBoolean("dnd", false), note = str(b, "note"),
                    bnTag = b.optString("tag"), game = str(b, "game"), favourite = b.optBoolean("fav", false), race = str(b, "race"),
                    lastOnline = if (b.has("last")) b.optLong("last") else null)
            } + objs(s.optJSONArray("list")).map { f ->
                Row(Tab.FRIENDS, f.optString("n"), f.optString("n"), slotOf(f), f.optBoolean("on", false),
                    level = int(f, "lvl"), cls = str(f, "cls"), zone = str(f, "area"), afk = f.optBoolean("afk", false),
                    dnd = f.optBoolean("dnd", false), note = str(f, "note"))
            }
            "ignore" -> objs(s.optJSONArray("list")).map { i ->
                Row(Tab.IGNORE, i.optString("n"), i.optString("n"), slotOf(i), false)
            }
            "guild" -> objs(s.optJSONArray("list")).map { g ->
                // the player's own line: shown, never acted on (the guild's roster does not mark it; the name does)
                Row(Tab.GUILD, g.optString("n"), g.optString("n"), slotOf(g), g.optBoolean("on", false), me = isMe(g.optString("n")),
                    level = int(g, "lvl"), cls = str(g, "cls"), zone = str(g, "zone"), note = str(g, "note"),
                    officerNote = str(g, "onote"), rank = g.optInt("ri", 0), rankName = str(g, "rank"),
                    afk = g.optInt("st", 0) == 1, dnd = g.optInt("st", 0) == 2, hoursAgo = int(g, "ago"))
            }
            else -> emptyList()
        }
    }

    /** The controller while the room shows: `hold` on entering, `free` on leaving (no game action). */
    fun holdTap(): Tap? = if (!rowsGranted) null else codeCol("hold")?.let { Tap("sact", it) }
    fun freeTap(): Tap? = if (!rowsGranted) null else codeCol("free")?.let { Tap("sact", it) }
    /**
     * (addon so7, the user's answer 2026-10-01: "Every room opens the game's own window behind it, as Professions does") the
     * game's own Friends window opened behind the room: a SECURE click of the game's own button, which the addon arms only while
     * the room holds the controller, no Social window is shown and no fight is on, so it can never toggle the window shut. Sent
     * once the `hold` answer says held (the take first: the window then opens at alpha 0). Null on an addon before so7.
     */
    fun openTap(): Tap? = if (!rowsGranted) null else codeCol("wopen")?.let { Tap("sact", it) }
    /** where the room's next B leads (no game action): Home (B closes the game's window and leaves) or back inside the room */
    fun bTap(home: Boolean): Tap? = if (!rowsGranted) null else codeCol(if (home) "bh" else "bb")?.let { Tap("sact", it) }
    /** a Social window of the game is shown (behind the room while it holds the controller) */
    val windowShown: Boolean get() = me?.optBoolean("win", false) ?: false
    /** the addon's `wopen` cell would click the game's button now (it holds, no window is shown; a fight freezes the cell as it was) */
    val openArmed: Boolean get() = me?.optBoolean("armed", false) ?: false

    /**
     * The game's player menu for a chosen row, as UI Art's SPEC 5 lists it (the game's own words, its order), with the rows
     * the player may not use left out; at most six. A raid's moves between groups are a drag, not a menu row.
     */
    fun menu(r: Row): List<Act> {
        val can = actions(r)
        val order = when (r.tab) {
            Tab.FRIENDS -> listOf(Act.WHISPER, Act.INVITE, Act.UNFRIEND, Act.IGNORE)
            Tab.GUILD -> listOf(Act.WHISPER, Act.INVITE, Act.ADD_FRIEND, Act.GUILD_PROMOTE, Act.GUILD_DEMOTE, Act.GUILD_REMOVE)
            Tab.RAID -> listOf(Act.WHISPER, Act.ADD_FRIEND, Act.LEADER, Act.ASSIST, Act.DEMOTE, Act.UNINVITE)
            Tab.IGNORE -> listOf(Act.UNIGNORE)
        }
        return order.filter { it in can }.take(6)
    }

    /** May the leader (or an assistant) move this member between the raid's groups by dragging? */
    fun canMove(r: Row): Boolean = r.tab == Tab.RAID && inRaid && !r.me && (isLeader || isAssistant)

    // ---------------------------------------------------------------- what a row allows

    /** Every action the selected row allows now, in the game's menu order. Whisper first when possible. */
    fun actions(r: Row): List<Act> {
        if (r.me) return emptyList()
        val out = ArrayList<Act>()
        val playing = r.actName != null
        if (playing && r.online && r.tab != Tab.IGNORE) out += Act.WHISPER
        when (r.tab) {
            Tab.RAID -> {
                if (canAddFriend && !isFriend(r.actName)) out += Act.ADD_FRIEND
                if (isLeader && r.online) out += Act.LEADER
                if (inRaid && isLeader) { if (r.rank == 0) out += Act.ASSIST else if (r.rank == 1) out += Act.DEMOTE }
                if (inRaid && (isLeader || isAssistant)) out += (1..8).filter { it != r.group }.map { Act.moveTo(it) }
                if (isLeader || (inRaid && isAssistant && r.rank == 0)) out += Act.UNINVITE
                if (guildCan("invite") && !isGuildmate(r.actName)) out += Act.GUILD_INVITE
                out += Act.IGNORE
            }
            Tab.FRIENDS -> {
                if (playing && r.online && canInvite && !isGroupmate(r.actName)) out += Act.INVITE
                if (r.bnTag == null) out += Act.UNFRIEND
                if (playing && r.bnTag == null) out += Act.IGNORE
                if (playing && r.online && guildCan("invite") && !isGuildmate(r.actName)) out += Act.GUILD_INVITE
            }
            Tab.IGNORE -> out += Act.UNIGNORE
            Tab.GUILD -> {
                if (r.online && canInvite && !isGroupmate(r.actName)) out += Act.INVITE
                if (canAddFriend && !isFriend(r.actName)) out += Act.ADD_FRIEND
                if (guildCan("promote")) out += Act.GUILD_PROMOTE
                if (guildCan("demote")) out += Act.GUILD_DEMOTE
                if (guildCan("remove")) out += Act.GUILD_REMOVE
                out += Act.IGNORE
            }
        }
        return out
    }

    /** The ONE contextual action the room shows for a selected row (the rest go in the player menu). */
    fun primary(r: Row): Act? {
        val a = actions(r)
        val first = when (r.tab) {
            Tab.IGNORE -> Act.UNIGNORE
            else -> Act.WHISPER
        }
        return if (first in a) first else a.firstOrNull()
    }

    /** Room buttons the game's Raid tab shows (Convert to Raid / Party, All Assist). */
    fun roomActions(tab: Tab): List<Act> = when (tab) {
        Tab.RAID -> buildList {
            if (groupKind == "party" && me?.optBoolean("canRaid", false) == true) add(Act.TO_RAID)
            if (inRaid && me?.optBoolean("canParty", false) == true) add(Act.TO_PARTY)
            if (inRaid && isLeader) add(Act.ALL_ASSIST)
        }
        else -> emptyList()
    }

    private fun isMe(name: String): Boolean {
        val mine = me?.let { str(it, "n") } ?: return false
        return name == mine || name == mine + "-" + (me?.let { str(it, "realm") } ?: "")
    }
    private fun isFriend(name: String?) = name != null &&
        objs(parts["friends"]?.optJSONArray("list")).any { it.optString("n") == name }
    private fun isGroupmate(name: String?) = name != null &&
        objs(parts["group"]?.optJSONArray("list")).any { it.optString("n") == name }
    private fun isGuildmate(name: String?) = name != null &&
        objs(parts["guild"]?.optJSONArray("list")).any { it.optString("n") == name }

    // ---------------------------------------------------------------- taps

    /** A tab touch: the view tap, or null when the addon already shows that list (or cannot be told). */
    fun tabTap(tab: Tab): Tap? {
        if (shown == tab || !rowsGranted) return null
        return codeCol(tab.viewCode)?.let { Tap("sact", it) }
    }

    /** A row touch: the pick tap, only for a row of the list the addon shows. */
    fun selectTap(r: Row): Tap? {
        if (!rowsGranted || shown != r.tab || r.slot == null) return null
        return Tap("spick", r.slot)
    }

    /** Is the addon's last pick THIS row, and still fresh for [act]? Only then may the action be sent. */
    fun pickMatches(r: Row, act: Act, nowMs: Long): Boolean {
        if (pickedTab != r.tab || pickedName == null) return false
        val same = if (r.bnTag != null) pickedTag == r.bnTag && pickedName == r.actName else pickedName == r.actName
        val limitMs = (if (act.confirm) PICK_MS_HARD else PICK_MS) - MARGIN_MS
        return same && nowMs - pickedAtMs <= limitMs
    }

    /** The act tap for [act] on a row, IGNORING the pick (for the view to send right after a fresh pick's reply). */
    fun actTapUnchecked(act: Act): Tap? = if (!rowsGranted || act == Act.WHISPER) null else codeCol(act.code)?.let { Tap("sact", it) }

    /** An action touch: the act tap, or null (Whisper, a stale or missing pick, an unknown code). */
    fun actionTap(r: Row?, act: Act, nowMs: Long): Tap? {
        if (!rowsGranted || act == Act.WHISPER) return null
        val col = codeCol(act.code) ?: return null
        if (act.onRoom) return Tap("sact", col)
        if (r == null || act !in actions(r) || !pickMatches(r, act, nowMs)) return null
        return Tap("sact", col)
    }

    companion object {
        const val PICK_MS = 120_000L        // the addon's PICK_SECONDS
        const val PICK_MS_HARD = 60_000L    // ... for the confirmed actions
        const val MARGIN_MS = 5_000L        // the tap still has to reach the game

        private fun objs(a: JSONArray?): List<JSONObject> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }
        private fun str(o: JSONObject, k: String): String? = if (o.has(k)) o.optString(k).takeIf { it.isNotEmpty() } else null
        private fun int(o: JSONObject, k: String): Int? = if (o.has(k)) o.optInt(k) else null
        private fun slotOf(o: JSONObject): Int? = if (o.has("slot")) o.optInt("slot", -1).takeIf { it in 0..39 } else null
    }
}
