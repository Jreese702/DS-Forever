package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/*
 * The Character room's data (character session): the Character addon's `{"type":"character","c":{...}}` document,
 * merged part by part (the addon sends only the parts that changed; the heartbeat sends them all). No drawing here
 * and nothing sent to the game: the first build is look-only (user ruling 2026-09-28).
 *
 * The room mirrors the game's CharacterFrame (user pick "C, the game's panes in a room"): six side tabs, a list on
 * the left and the selected entry's details on the right. Reputation, Skills and Currency list in TWO COLUMNS under
 * full-width headings (user pick); Statistics lists over the whole width (UI Art's choice). The paper doll is Bags'
 * (Doll.kt) and the PvP tab's contents are the PVP session's (PvpRankTab); neither is read here.
 */

/** The game's six tabs, in its order. [key] is the addon's name for it. */
enum class CharTab(val key: String) {
    CHARACTER("char"), REPUTATION("rep"), SKILLS("skills"), PVP("pvp"), CURRENCY("cur"), STATISTICS("stats");
    companion object { fun of(key: String?) = values().firstOrNull { it.key == key } }
}

/** The three small tabs over the stats on the Character tab. */
enum class CharSide(val key: String) {
    STATS("stats"), SETS("sets"), PET("pet");
    companion object { fun of(key: String?) = values().firstOrNull { it.key == key } }
}

/** A value the game hid (secret) or that could not be read: drawn as "?" (my hand-over, "States"). */
private const val SECRET = "\u0001"
private const val ABSENT = "\u0002"
private const val THREW = "\u0003"
private fun hidden(v: String) = v == SECRET || v == ABSENT || v == THREW

/** the room's two tap cells on the addon's pad: [pad] = TapPad's describe(), [row] its name, [open] / [close] the columns; [secure] =
 *  the game's own Character window opened / closed the game's way (addon c9; null from older addons) */
data class CharGrid(val pad: JSONObject, val row: String, val open: Int, val close: Int, val secure: CharSecure? = null) {
    /**
     * The cell a route INTO the room taps (the user, 2026-10-01: "Every room opens the game's own window behind it, as Professions
     * does"; the same on every route). [gameOpen] / [held] as the last CURRENT document said. null = no tap.
     *  - the game's window closed: the SECURE open cell (a click of the game's own Character button; its OnShow takes the controller);
     *    the addon leaves that cell without an action while the window is open, so a stale [gameOpen] cannot toggle it shut
     *  - the window open but the room not holding (it was left in the app and stayed off): the plain `open` cell (the addon takes it
     *    the window's way)
     *  - older addons (no secure cells): the plain `open` cell when not held
     */
    fun enterCell(gameOpen: Boolean, held: Boolean): Pair<String, Int>? = when {
        secure != null && !gameOpen -> secure.row to secure.open
        held -> null
        else -> row to open
    }
    /**
     * The cell a route OUT of the room taps: the SECURE close cell (the window's own close button, the game's way; the addon then
     * lets go "closed", and a room held without the window lets go too) while the window is open or the room holds; older addons:
     * the plain `close` cell while held. null = no tap (nothing to close, nothing held: no bounce).
     */
    fun leaveCell(gameOpen: Boolean, held: Boolean): Pair<String, Int>? = when {
        secure != null && (gameOpen || held) -> secure.row to secure.close
        held -> row to close
        else -> null
    }
    /**
     * The HOLD a route into the room asks FIRST (Group Finder's `h`, Social's hold; Home's consistency pass 2026-10-06): the PLAIN
     * `open` cell, no game action (Character.lua touchOpen: the touch spec, or the window's when the game's window is open already).
     * A plain cell fires on the PRESS, so a tap right after a controller press (the bar's A) is not lost the way the secure cell's
     * release is. null while held (a re-ask is harmless either way: the addon's take is idempotent).
     */
    fun holdCell(held: Boolean): Pair<String, Int>? = if (held) null else row to open
    /**
     * The game's own window BEHIND the room, opened only once the hold is REAL (Home's rule for Group Finder: "Never open the game
     * window before a plain true or state().holder == id"): the SECURE open cell while the window is shut. null for older addons
     * (no secure cells), an open window, or a hold that is not real yet ([active] = held and not waiting).
     */
    fun openCell(gameOpen: Boolean, active: Boolean): Pair<String, Int>? =
        if (secure != null && active && !gameOpen) secure.row to secure.open else null
}

/** the secure cells of the addon's own row ([row]): [open] clicks the game's Character button, [close] the window's close button */
data class CharSecure(val row: String, val open: Int, val close: Int)

data class CharWho(
    val name: String, val title: String, val level: Int, val line: String, val cls: String, val clsFile: String,
    val race: String, val spec: String, val faction: String,
)

/** One stats row as the game writes it. [colour] 0 plain, 1 green (buffed), 2 red (debuffed); [hidden] = draw "?". */
data class StatRow(val key: String, val label: String, val value: String, val colour: Int, val hidden: Boolean, val tips: List<String>,
                   /** the value from before the fight, kept while the game hides it (drawn greyed) */
                   val stale: Boolean = false)
data class StatSection(val title: String, val rows: List<StatRow>)
data class GearSet(val id: Int, val name: String, val icon: Int, val equipped: Boolean, val lost: Int,
                   /** its Equip / Save cells on the add-on's `eqset` row (f-equipment-manager); null = none free */
                   val equipCell: Int? = null, val saveCell: Int? = null)

/**
 * The gear sets' buttons (f-equipment-manager, the addon's `eqs` part): the `eqset` row and its fixed Yes / No cells, the question
 * the add-on is asking ([ask]: the game's "save this set?") and its last answer to a tap ([ack], numbered).
 */
data class SetAsk(val id: Int, val name: String, val text: String)
data class SetAck(val seq: Int, val op: String, val id: Int?, val ok: Boolean, val why: String)
data class SetPad(val row: String, val yes: Int?, val no: Int?, val ask: SetAsk?, val ack: SetAck?)

/**
 * The pet page's left pane (the Character tab's third small tab in this client; user ruling: the hunter's pet page goes
 * under the Character tab). All texts are the game's own words. [hunter] = a hunter's pet: only then [happy] (1 unhappy,
 * 2 content, 3 happy), [happyText], [damageText], [loyaltyNote] and [diet]. [xpMax] 0 = no pet XP bar (a demon).
 */
data class CharPet(
    val name: String, val family: String, val level: Int, val line: String, val hunter: Boolean, val icon: Int,
    val xp: Long, val xpMax: Long, val xpText: String, val loyalty: String,
    val happy: Int, val happyText: String, val damageText: String, val loyaltyNote: String, val diet: String,
) {
    val xpFill: Float get() = if (xpMax > 0) (xp.toFloat() / xpMax).coerceIn(0f, 1f) else 0f
}

/** A standing / rank bar. [colour] = the game's colour index "1".."8" or "blue"; [kind] "", "friend", "major". */
data class CharBar(val cur: Long, val max: Long, val label: String, val progress: String, val colour: String, val kind: String) {
    val fill: Float get() = if (max > 0) (cur.toFloat() / max).coerceIn(0f, 1f) else 0f
}

/** A check box in a detail pane: SHOWN in the first build, not pressable (the game's buttons come in step 2). */
data class CharCheck(val key: String, val label: String, val on: Boolean, val enabled: Boolean)

/**
 * One line of a list tab (Reputation, Skills, Currency, Statistics). [key] is stable across refreshes (headings by
 * name, entries by their game id). [depth] 0 = a top heading. [gameFolded] = folded IN THE GAME: its entries did not
 * come (read-only: this room never unfolds it). [value] = a currency's count or a statistic's value.
 */
data class CharEntry(
    val key: String, val name: String, val header: Boolean, val depth: Int, val gameFolded: Boolean = false,
    val bar: CharBar? = null, val desc: String = "", val red: Boolean = false, val grey: Boolean = false,
    val value: String = "", val icon: Int = 0, val rows: List<Pair<String, String>> = emptyList(),
    val lines: List<String> = emptyList(), val note: String = "", val checks: List<CharCheck> = emptyList(),
)

/** A list tab laid out: a full-width heading, or a row of one or two entries (two columns, user pick). */
sealed class CharLine {
    data class Heading(val entry: CharEntry, val folded: Boolean) : CharLine()
    data class Row(val left: CharEntry, val right: CharEntry?) : CharLine()
}

/**
 * The state of one list tab: the entries, the headings folded IN THE ROOM (a local view, sends nothing), and the
 * selected entry (its details show on the right). The selection survives a refresh by key.
 */
class CharList(private val autoSelectFirst: Boolean = false) {
    var entries: List<CharEntry> = emptyList()
        private set
    private val folded = mutableSetOf<String>()
    var selected: String? = null
        private set

    fun set(list: List<CharEntry>) {
        entries = list
        if (selected != null && list.none { it.key == selected && !it.header }) selected = null
        if (selected == null && autoSelectFirst) selected = list.firstOrNull { !it.header }?.key
    }

    fun isFolded(e: CharEntry) = e.header && (e.key in folded || e.gameFolded)

    /** Fold / unfold a heading in the room. False for a heading the GAME folded (its entries never came). */
    fun toggle(key: String): Boolean {
        val e = entries.firstOrNull { it.key == key && it.header } ?: return false
        if (e.gameFolded) return false
        if (!folded.add(key)) folded.remove(key)
        return true
    }

    fun select(key: String?): Boolean {
        if (key != null && entries.none { it.key == key && !it.header }) return false
        if (selected == key) return false
        selected = key
        return true
    }

    fun selectedEntry(): CharEntry? = entries.firstOrNull { it.key == selected }

    /** The entries a reader sees: whatever sits under a folded heading (deeper than it) is left out. */
    fun visible(): List<CharEntry> {
        val out = ArrayList<CharEntry>()
        var hideBelow = Int.MAX_VALUE
        for (e in entries) {
            if (e.depth <= hideBelow) hideBelow = Int.MAX_VALUE
            if (e.depth > hideBelow) continue
            out += e
            if (isFolded(e)) hideBelow = e.depth
        }
        return out
    }

    /** Laid out for the screen: headings full width; entries two to a row ([twoColumns]) or one per row. */
    fun lines(twoColumns: Boolean): List<CharLine> {
        val out = ArrayList<CharLine>()
        var pending: CharEntry? = null
        fun flush() { pending?.let { out += CharLine.Row(it, null) }; pending = null }
        for (e in visible()) {
            if (e.header) { flush(); out += CharLine.Heading(e, isFolded(e)); continue }
            if (!twoColumns) { out += CharLine.Row(e, null); continue }
            val p = pending
            if (p == null) pending = e else { out += CharLine.Row(p, e); pending = null }
        }
        flush()
        return out
    }
}

class CharacterModel {
    var who: CharWho? = null; private set
    var stats: List<StatSection> = emptyList(); private set
    var pet: List<StatSection> = emptyList(); private set
    var sets: List<GearSet> = emptyList(); private set
    /** the gear sets' buttons and question (null until the addon has sent its `eqs` part) */
    var setPad: SetPad? = null; private set
    /** null = no pet UI (the Pet tab is not shown, as in the game) */
    var petInfo: CharPet? = null; private set
    val reputation = CharList()
    val skills = CharList(autoSelectFirst = true)    // the game does this for Skills (SelectFirstSkillIfNoneSelected)
    val currency = CharList()
    val statistics = CharList()
    /** The game's own words for the room's fixed labels (the addon's `str` part), by the game's global name. */
    var strings: Map<String, String> = emptyMap(); private set
    /** The side tabs' icons (fileDataIDs) by tab key; the Character tab has none (the game shows a portrait). */
    var tabIcons: Map<String, Int> = emptyMap(); private set
    var combat = false; private set

    /** The game's window: open or not, which tab and side pane it shows. The room follows it while it is open. */
    var gameOpen = false; private set
    var tab = CharTab.CHARACTER
    var side = CharSide.STATS

    /** the room holds the controller (the addon took it through interact's capture while the game's window is open) */
    var controller = false; private set
    /** the controller's last word from the addon ("taken", "take-skip-combat", "lost-npc", "closed" ...) */
    var controllerNote = ""; private set
    /** how the room holds it: "window" (the game's Character window is open) or "touch" (opened in the app); "" = not held */
    var controllerVia = ""; private set
    /** the room asked for the controller and WAITS (addon c11 `win.pend`): another holder is still on it (capture v11's queue), or a
     *  fight's take waits for Home's ENTER tap. Counted as held for leaving (a leave gives the wait up), not for the cursor */
    var controllerWaiting = false; private set
    /** where the app taps to take / let go of the controller for the room opened in the app (the addon's `grid` part):
     *  the pad's published grid, the row, and the two fixed columns. null until the addon has sent it */
    var grid: CharGrid? = null; private set
    /** the last press the addon passed on (PADDUP / PADDDOWN / PADDLEFT / PADDRIGHT / PAD1 / PAD3 / PAD4); null once taken */
    var pad: String? = null
    private var padSeq = -1

    /** Take a document; returns the parts that changed (empty for anything else). */
    fun update(msg: JSONObject): Set<String> {
        if (msg.optString("type") != "character") return emptySet()
        val c = msg.optJSONObject("c") ?: return emptySet()
        combat = msg.optBoolean("combat")
        val changed = LinkedHashSet<String>()
        // the game's words first: the lists below take their labels from them
        c.optJSONObject("str")?.let { j -> strings = STRING_NAMES.filter { j.has(it) }.associateWith { j.optString(it) }; changed += "str" }
        c.optJSONObject("tabs")?.let { j -> tabIcons = TAB_ICON_KEYS.filter { j.has(it) }.associateWith { j.optInt(it) }; changed += "tabs" }
        c.optJSONObject("who")?.let { who = readWho(it); changed += "who" }
        if (c.has("st")) { stats = holdInFight(stats, readStats(c.optJSONArray("st")), combat); changed += "st" }
        if (c.has("pet")) { pet = holdInFight(pet, readStats(c.optJSONArray("pet")), combat); changed += "pet" }
        if (c.has("sets")) { sets = readSets(c.optJSONArray("sets")); changed += "sets" }
        c.optJSONObject("eqs")?.let { j ->
            val ask = j.optJSONObject("ask")?.takeIf { it.has("id") }?.let { a -> SetAsk(a.optInt("id"), a.optString("n"), a.optString("text")) }
            val ack = j.optJSONObject("ack")?.let { a ->
                SetAck(a.optInt("seq"), a.optString("op"), if (a.has("id")) a.optInt("id") else null, a.optBoolean("ok"), a.optString("why"))
            }
            setPad = SetPad(j.optString("row"), if (j.has("yes")) j.optInt("yes") else null, if (j.has("no")) j.optInt("no") else null, ask, ack)
            changed += "eqs"
        }
        if (c.has("petinfo")) { petInfo = c.optJSONObject("petinfo")?.let { readPet(it) }; changed += "petinfo" }
        if (c.has("rep")) { reputation.set(readRep(c.optJSONArray("rep"))); changed += "rep" }
        if (c.has("sk")) { skills.set(readSkills(c.optJSONArray("sk"))); changed += "sk" }
        if (c.has("cur")) { currency.set(readCur(c.optJSONArray("cur"))); changed += "cur" }
        if (c.has("sx")) { statistics.set(readStatistics(c.optJSONArray("sx"))); changed += "sx" }
        c.optJSONObject("win")?.let { readWin(it); changed += "win" }
        if (c.has("grid")) {
            grid = c.optJSONObject("grid")?.let { g ->
                val pad = g.optJSONObject("pad")
                val row = g.optString("row")
                val sec = g.optJSONObject("secure")?.let { x ->
                    val r = x.optString("row")
                    if (r.isEmpty() || !x.has("open") || !x.has("close")) null else CharSecure(r, x.optInt("open"), x.optInt("close"))
                }
                if (pad == null || row.isEmpty() || !g.has("open") || !g.has("close")) null
                else CharGrid(pad, row, g.optInt("open"), g.optInt("close"), sec)
            }
            changed += "grid"
        }
        c.optJSONObject("pad")?.let { j ->
            val seq = j.optInt("seq", -1)
            // a new number is a new press; after a /reload the addon counts from 1 again, so any change counts
            if (seq != padSeq) { padSeq = seq; pad = j.optString("k").ifEmpty { null }; if (pad != null) changed += "pad" }
        }
        return changed
    }

    /** A label in the game's words, or [fallback] until the `str` part has come. */
    fun word(name: String, fallback: String) = strings[name]?.takeIf { it.isNotEmpty() } ?: fallback

    // ------------------------------------------------------------------ parts

    private fun readWin(j: JSONObject) {
        gameOpen = j.optBoolean("open")
        controller = j.optBoolean("ctl")
        controllerNote = j.optString("note")
        controllerVia = if (controller) j.optString("via", "window") else ""
        controllerWaiting = controller && j.optBoolean("pend")
        if (gameOpen) {
            CharTab.of(j.optString("tab"))?.let { tab = it }
            CharSide.of(j.optString("side"))?.let { side = it }
        }
    }

    private fun readWho(j: JSONObject) = CharWho(
        name = j.optString("name"), title = j.optString("title"), level = j.optInt("lvl"), line = j.optString("line"),
        cls = j.optString("cls"), clsFile = j.optString("clsFile"), race = j.optString("race"), spec = j.optString("spec"),
        faction = j.optString("faction"),
    )

    private fun readStats(a: JSONArray?): List<StatSection> {
        if (a == null) return emptyList()           // false: no pet UI
        return (0 until a.length()).mapNotNull { i ->
            val s = a.optJSONObject(i) ?: return@mapNotNull null
            val rows = s.optJSONArray("r")
            StatSection(s.optString("h"), (0 until (rows?.length() ?: 0)).mapNotNull { k ->
                val r = rows!!.optJSONObject(k) ?: return@mapNotNull null
                val v = r.optString("v")
                StatRow(r.optString("k"), r.optString("l"), if (hidden(v)) "?" else v, r.optInt("c"), hidden(v), strings(r.optJSONArray("t")))
            })
        }
    }

    /**
     * IN A FIGHT the game hides most stats from add-ons (measured on the Thor 2026-09-30 18:20: 17 of 28 rows threw, 4 were secret;
     * health and power stayed readable). The user's ruling (via tracking): show the last values from BEFORE the fight, greyed, until
     * it ends; health and power stay live. So a fight's document keeps the pre-fight rows: a row the game still lets us read takes
     * its new value, any other keeps its old one marked stale; rows the fight adds (the zero rows the game normally hides, which a
     * secret cannot be compared to) are left out. The first document after the fight replaces everything.
     */
    private fun holdInFight(prev: List<StatSection>, next: List<StatSection>, inFight: Boolean): List<StatSection> {
        if (!inFight || prev.isEmpty()) return next
        val fresh = next.flatMap { it.rows }.associateBy { it.key }
        return prev.map { s ->
            StatSection(s.title, s.rows.map { old ->
                val n = fresh[old.key]
                if (n != null && !n.hidden) n else old.copy(stale = true)
            })
        }
    }

    private fun readSets(a: JSONArray?): List<GearSet> = objects(a).map {
        GearSet(it.optInt("id"), it.optString("n"), it.optInt("icon"), it.optBoolean("eq"), it.optInt("lost"),
            if (it.has("ce")) it.optInt("ce") else null, if (it.has("cs")) it.optInt("cs") else null)
    }

    private fun readPet(j: JSONObject) = CharPet(
        name = j.optString("name"), family = j.optString("family"), level = j.optInt("lvl"), line = j.optString("line"),
        hunter = j.optBoolean("hunter"), icon = j.optInt("icon"), xp = j.optLong("xp"), xpMax = j.optLong("xpMax"), xpText = j.optString("xpText"),
        loyalty = j.optString("loyalty"), happy = j.optInt("happy"), happyText = j.optString("happyText"),
        damageText = j.optString("dmgText"), loyaltyNote = j.optString("loyaltyNote"), diet = j.optString("diet"),
    )

    private fun readBar(j: JSONObject?): CharBar? = j?.let {
        CharBar(it.optLong("cur"), it.optLong("max"), it.optString("st"), it.optString("p"), it.optString("col"), it.optString("kind"))
    }

    // Reputation: top headings depth 0, child headings 1, factions under a child heading 2, others 1.
    private fun readRep(a: JSONArray?): List<CharEntry> = objects(a).map { j ->
        val header = j.optBoolean("h")
        val child = j.optBoolean("ch")
        val depth = when { header && !child -> 0; header -> 1; child -> 2; else -> 1 }
        val name = j.optString("n")
        CharEntry(
            key = if (header) "h:$depth:$name" else "f:${j.optInt("id")}", name = name, header = header, depth = depth,
            gameFolded = header && j.optBoolean("fold"), bar = readBar(j.optJSONObject("bar")), desc = j.optString("desc"),
            red = j.optBoolean("war"),
            note = if (j.optBoolean("acct")) word("REPUTATION_TOOLTIP_ACCOUNT_WIDE_LABEL", "") else "",
            checks = if (header && !j.optBoolean("hb")) emptyList() else listOf(
                CharCheck("war", word("AT_WAR", "At War"), j.optBoolean("war"), j.optBoolean("cw") && !header),
                CharCheck("inactive", word("MOVE_TO_INACTIVE", "Inactive"), j.optBoolean("inact"), j.optBoolean("ci")),
                CharCheck("watch", word("SHOW_FACTION_ON_MAINSCREEN", "Show as Experience Bar"), j.optBoolean("watch"), true),
            ),
        )
    }

    // Skills: headings depth 0, skills 1. The bar's text is the game's ("150 (+10) / 300").
    private fun readSkills(a: JSONArray?): List<CharEntry> = objects(a).map { j ->
        val header = j.optBoolean("h")
        val name = j.optString("n")
        val w = j.optJSONObject("w")
        val weapon = if (w == null) emptyList() else listOf(w.optString("h1"), w.optString("same"), w.optString("h2"), w.optString("boss")).filter { it.isNotEmpty() }
        CharEntry(
            key = if (header) "h:0:$name" else "s:${j.optInt("id")}", name = name, header = header, depth = if (header) 0 else 1,
            gameFolded = header && j.optBoolean("fold"), desc = j.optString("desc"),
            bar = if (header) null else CharBar(j.optLong("rank"), j.optLong("max"), j.optString("bt"), j.optString("bt"), "skill", ""),
            lines = weapon,
        )
    }

    // Currency: depth from the game; a count grey at 0; detail rows as the game writes them.
    private fun readCur(a: JSONArray?): List<CharEntry> = objects(a).map { j ->
        val header = j.optBoolean("h")
        val name = j.optString("n")
        val depth = j.optInt("d")
        val rows = j.optJSONArray("rows")
        CharEntry(
            key = if (header) "h:$depth:$name" else "c:${j.optInt("id")}", name = name, header = header, depth = depth,
            gameFolded = header && j.optBoolean("fold"), desc = j.optString("desc"),
            value = j.optString("qt", j.optString("q")), icon = j.optInt("icon"), grey = !header && j.optLong("q") == 0L,
            rows = (0 until (rows?.length() ?: 0)).mapNotNull { k ->
                val r = rows!!.optJSONObject(k) ?: return@mapNotNull null
                r.optString("l") to r.optString("v")
            },
            note = j.optString("note"),
            checks = if (header || !j.optBoolean("disc")) emptyList() else listOf(
                CharCheck("unused", word("UNUSED", "Unused"), j.optBoolean("unused"), true),
                CharCheck("backpack", word("SHOW_ON_BACKPACK", "Show on Backpack"), j.optBoolean("bp"), true),
            ),
        )
    }

    // Statistics: categories (headings, by id) and their counts; depth from the game's tree.
    private fun readStatistics(a: JSONArray?): List<CharEntry> {
        var n = 0
        return objects(a).map { j ->
            val header = j.optBoolean("h")
            n++
            CharEntry(
                key = if (header) "x:${j.optInt("id")}" else "v:$n", name = j.optString("n"), header = header,
                depth = j.optInt("d"), value = j.optString("v"),
            )
        }
    }

    private fun objects(a: JSONArray?): List<JSONObject> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }

    companion object {
        /** The addon's `str` part: the game's global names (Character.lua STRINGS, same list). */
        val STRING_NAMES = listOf(
            "CHARACTER", "REPUTATION", "SKILLS", "PVP", "CURRENCY", "STATISTICS",
            "CHARACTER_FRAME_TAB_CHARACTER", "CHARACTER_FRAME_TAB_REPUTATION", "CHARACTER_FRAME_TAB_SKILLS",
            "CHARACTER_FRAME_TAB_PVP", "CHARACTER_FRAME_TAB_CURRENCY", "CHARACTER_FRAME_TAB_STATISTICS",
            "PAPERDOLL_SIDEBAR_STATS", "PAPERDOLL_EQUIPMENTMANAGER", "PET",
            "AT_WAR", "MOVE_TO_INACTIVE", "SHOW_FACTION_ON_MAINSCREEN", "REPUTATION_DETAIL_SELECT_PROMPT",
            "REPUTATION_TOOLTIP_ACCOUNT_WIDE_LABEL", "SKILL_DETAIL_SELECT_PROMPT", "TOKEN_DETAIL_SELECT_PROMPT",
            "UNUSED", "SHOW_ON_BACKPACK", "CURRENCY_DETAIL_TOTAL_CAP_LABEL", "CURRENCY_DETAIL_WEEKLY_CAP_LABEL",
            "ACCOUNT_LEVEL_CURRENCY",
            "EQUIPSET_EQUIP", "SAVE", "YES", "NO", "CONFIRM_SAVE_EQUIPMENT_SET", "ERR_NOT_IN_COMBAT",
        )
        /** The addon's `tabs` part (fileDataIDs; found offline too: AGENT_LOG e8f3d1c). */
        val TAB_ICON_KEYS = listOf("rep", "skills", "pvpA", "pvpH", "cur", "stats")
    }
}
