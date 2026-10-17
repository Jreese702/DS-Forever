package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Spells room's data: the addon's `spells` document (WowCompanionSpells s1) turned into the book the
 * way THIS client's own window (PlayerSpellsFrame, camelot) shows it. Pure logic, no drawing, so
 * tools/spells_test runs the real file off the device.
 *
 * What the game does, read from blizzard_playerspells (2026-09-28), and mirrored here:
 * - tabs: one per skill line that is not hidden, in the game's order (General first), then Pet while a pet
 *   with a book is out;
 * - filter: passives hidden when the player's own setting says so, lower ranks hidden unless "show all
 *   ranks" is on, and with a controller the flyout is shown and the loose spells inside it are not;
 * - a spell not yet known is "Available at level N" above the player's level, "Trainable" at or below it.
 */
object SpellsModel {

    enum class Kind { SPELL, FUTURE, PET_ACTION, FLYOUT, OTHER }
    enum class Status { KNOWN, LEVEL, TRAINABLE }

    data class Entry(
        val slot: Int,
        val pet: Boolean,
        val kind: Kind,
        val actionId: Int,
        val spellId: Int?,
        val name: String,
        val rank: String?,          // the game's sub-name: "Rank 2", "Racial"; null when none
        val icon: Int,              // fileID, 0 when unknown
        val passive: Boolean,
        val lowRank: Boolean,
        val flyoutMember: Boolean,
        val levelLearned: Int?,     // only for a spell not known yet
        val line: Int?,
        val desc: String?,
        val castMs: Int?,           // 0 = instant; null = unknown
        val rangeMin: Int?,         // yards; null = none
        val rangeMax: Int?,
        val cost: List<Cost>,
        val reagents: Need? = null,
        val tools: Need? = null,
        val bars: List<Int>,        // action-bar storage slots holding it
        val pad: List<PadSlot>,     // the same slots that are controller buttons, named
        val autoCastAllowed: Boolean,
        val autoCastOn: Boolean,
        val padColumn: Int?,        // an OLDER add-on's tap-pad column of row `spells` that opens Bind for it (before Book b38)
        val prof: String? = null,   // s3: a PROFESSION's spell (the game's Professions book), the profession's name; it closes the General tab
        /** Book b38 (b-druid-forms-bind): the spell's NUMBER on the `spells` row's two taps (its group, then its offset); see [bindCells] */
        val padNumber: Int? = null,
    )

    /** Who holds the controller for the Spellbook, and where the gold glow is (`spellsctl`, addon Book.lua). */
    data class Ctl(
        val on: Boolean,
        val via: String?,           // "button" (the game's Spellbook button) or "touch"
        val line: Int?,             // the tab, as the skill line's index
        val page: Int,
        val pages: Int,
        val idx: Int,               // 1-based on the page, row by row
        val onTabs: Boolean,        // the glow is on the tab row
        val selected: Int?,         // the spell under the glow
        val binding: Int?,          // the spell the Bind box waits for, when it is open
        val note: String?,
        val mode: String = "book",  // "book", "bind" (the box waits), "result" (the box shows the answer), "confirm" (Book b42: A keeps, B undoes)
        val talentsPage: Boolean = false,   // the controller drives the room's Talents page (Book.lua b3 `pg`)
        val backSelect: Boolean = false,    // the box's way back is SELECT (capture v4; the user 2026-09-30), B a place for a spell
        val ctrlTab: Boolean = false,       // the book's CONTROLLER TAB is lit or entered (Book.lua b4 `ctrl`)
        val croom: Boolean = false,         // b17: the hold is the CONTROLLER ROOM's (its own room on Home's bar), not the Spellbook's
        val tog: Int? = null,               // b17: the controller's cursor on the room's toggles row (b20: 1 toolbar, [radial], combat, attack)
        val tn: Int? = null,                // b20: presses of A on the Radial plate since the room opened (the app flips its setting)
        val pn: Int? = null,                // b33: presses of X there (the wheels' PICK, A / Release: the app flips that setting)
        val rank: Int? = null,              // b23: the rank on show, its place in the spell's rank family (1 = the highest); null = no family
        val ranks: Int? = null,             // b23: how many ranks the family has
        val bindFight: Boolean = false,     // b37: the Bind box puts the spell on the In combat layout (b-bind-combat-toggle)
    ) {
        val bindOpen get() = binding != null
    }

    /** The answer to a Bind press (`spellsack`). [why]: "combat", "reserved", "unknown", "failed". */
    data class BindResult(val spellId: Int, val ok: Boolean, val why: String?, val pad: PadSlot?,
                          /** b37: it went on as that button's FIGHT spell (no game slot of its own: storage 0) */
                          val fight: Boolean = false)

    fun parseCtl(msg: JSONObject): Ctl? {
        if (msg.optString("type") != "spellsctl") return null
        val c = msg.optJSONObject("ctl") ?: return null
        return Ctl(
            on = c.optBoolean("on", false),
            via = c.optString("via", "").takeIf { it.isNotEmpty() },
            line = c.optInt("line", 0).takeIf { it > 0 },
            page = c.optInt("page", 1).coerceAtLeast(1),
            pages = c.optInt("pages", 1).coerceAtLeast(1),
            idx = c.optInt("idx", 1).coerceAtLeast(1),
            onTabs = c.optBoolean("tabs", false),
            selected = c.optInt("sel", 0).takeIf { it > 0 },
            binding = if (c.optString("mode") == "bind") c.optInt("bind", 0).takeIf { it > 0 } else null,
            note = c.optString("note", "").takeIf { it.isNotEmpty() },
            mode = c.optString("mode", "book").ifEmpty { "book" },
            talentsPage = c.optString("pg", "book") == "talents",
            backSelect = c.optString("bk", "") == "select",
            ctrlTab = c.optBoolean("ctrl", false),
            croom = c.optBoolean("croom", false),
            tog = c.optInt("tog", 0).takeIf { it in 1..4 },
            tn = if (c.has("tn")) c.optInt("tn", 0) else null,
            pn = if (c.has("pn")) c.optInt("pn", 0) else null,
            rank = c.optInt("rk", 0).takeIf { it > 0 },
            ranks = c.optInt("rks", 0).takeIf { it > 1 },
            bindFight = c.optBoolean("bf", false),
        )
    }

    fun parseBind(msg: JSONObject): BindResult? {
        if (msg.optString("type") != "spellsack" || msg.optString("op") != "bind") return null
        val id = msg.optInt("id", 0).takeIf { it > 0 } ?: return null
        val p = msg.optJSONObject("pad")
        val pad = p?.let {
            val st = it.optInt("s", 0); val pg = it.optInt("pg", 0); val b = it.optInt("b", 0); val ps = it.optInt("p", 0)
            val fight = it.optBoolean("fight", false)                       // b37: an In combat bind has no game slot
            if ((st > 0 || fight) && pg in 1..3 && b in 1..4 && ps in 1..8) PadSlot(st, pg, b, ps) else null
        }
        val ok = msg.optBoolean("ok", false) && pad != null
        return BindResult(id, ok, if (ok) null else msg.optString("why", "failed").ifEmpty { "failed" }, pad,
            p?.optBoolean("fight", false) == true)
    }

    /**
     * A controller-bar slot, named the way the game's own diagram does (actionbindingutil.lua): [page] 1..3,
     * [bar] 1 = no trigger, 2 = LT, 3 = RT, 4 = LT + RT, [pos] 1..4 = D-pad left / up / right / down,
     * 5..8 = the face buttons left / top / right / bottom by POSITION (the Cast room's layout names them).
     */
    data class PadSlot(val storage: Int, val page: Int, val bar: Int, val pos: Int, val form: String? = null)

    /** A reagent or tool line in the game's own words ("Reagents: Soul Shard"); [missing] = the game writes it red. */
    data class Need(val text: String, val missing: Boolean)

    /** One power cost: [amount] (null when only a percentage), [token] the game's power token ("MANA", "FOCUS"). */
    data class Cost(val amount: Int?, val minAmount: Int?, val percent: Double?, val perSec: Double?, val type: Int?, val token: String?)

    /** [prof]: s5, the room's own Professions line (k 100), sent after the class's lines (the user 2026-10-07: "instead of putting professions on a second page of general, give those buttons their own tab") */
    data class Line(val index: Int, val name: String, val icon: Int, val hidden: Boolean, val prof: Boolean = false)

    /** [sect]: in the General tab, how many entries come before the PROFESSIONS, which start on a page of their own (null = none) */
    data class Tab(val key: String, val name: String, val icon: Int, val entries: List<Entry>, val sect: Int? = null, val prof: Boolean = false)

    data class News(val nid: Int, val spellId: Int, val line: Int?, val at: Double?)
    data class Pushed(val nid: Int, val spellId: Int, val slot: Int?, val page: Int?, val at: Double?)

    data class Book(
        val version: String,
        val level: Int,
        val hidePassives: Boolean,
        val showAllRanks: Boolean,
        val combat: Boolean,
        val lines: List<Line>,
        val items: List<Entry>,
        val petName: String?,
        val petIcon: Int,
        val petHasBook: Boolean,
        val petItems: List<Entry>,
        val news: List<News>,
        val pushed: List<Pushed>,
        val introOff: Boolean?,     // null = the client could not say
        val secret: Int,
        val threw: Int,
        val pad: JSONObject? = null, // the tap pad's geometry (TapBridge aims row `spells` with it)
        /** Book b12 (s2): the room's way in and out on the `spells` row: "open" (the Spellbook face), "topen" (the Talents face),
         *  "close" -- secure clicks on the game's own buttons; empty from an older addon */
        val win: Map<String, Int> = emptyMap(),
    )

    /** The `spells` message (the whole document, `type` = "spells"); null when it is not one. */
    fun parse(msg: JSONObject): Book? {
        if (msg.optString("type") != "spells") return null
        val s = msg.optJSONObject("s") ?: return null
        val lines = objects(s.optJSONArray("lines")).mapNotNull { o ->
            val k = o.optInt("k", 0)
            if (k <= 0) null else Line(k, o.optString("n", ""), o.optInt("i", 0), o.optBoolean("hide", false), o.optBoolean("prof", false))
        }
        val pet = s.optJSONObject("pet")
        val sec = s.optJSONObject("sec")
        return Book(
            version = msg.optString("v", "?"),
            level = s.optInt("lvl", 0),
            hidePassives = s.optBoolean("pas", false),
            showAllRanks = s.optBoolean("ranks", false),
            combat = s.optBoolean("combat", false),
            lines = lines,
            items = objects(s.optJSONArray("items")).mapNotNull { entry(it, false) },
            petName = pet?.let { p -> p.optString("n", "").takeIf { it.isNotEmpty() } },
            petIcon = pet?.optInt("i", 0) ?: 0,
            petHasBook = pet?.optBoolean("book", false) ?: false,
            petItems = objects(pet?.optJSONArray("items")).mapNotNull { entry(it, true) },
            news = objects(s.optJSONArray("news")).mapNotNull { o ->
                val id = o.optInt("id", 0)
                if (id <= 0) null else News(o.optInt("nid", 0), id, o.optInt("ln", 0).takeIf { it > 0 }, num(o, "at"))
            },
            pushed = objects(s.optJSONArray("pushed")).mapNotNull { o ->
                val id = o.optInt("id", 0)
                if (id <= 0) null else Pushed(o.optInt("nid", 0), id, o.optInt("slot", 0).takeIf { it > 0 },
                    o.optInt("page", 0).takeIf { it > 0 }, num(o, "at"))
            },
            introOff = if (s.has("introOff")) s.optBoolean("introOff", false) else null,
            secret = sec?.optInt("s", 0) ?: 0,
            threw = sec?.optInt("t", 0) ?: 0,
            pad = s.optJSONObject("pad"),
            win = s.optJSONObject("win")?.let { w ->
                // b17: "croom" = the Controller room's own way in (ControllerView.enterRoom); dropping it sent b47 to the old tab fallback
                // b37: "bfon" / "bfoff" the Bind box's Normal / In combat by touch
                // b38: "vb0".."vb12" / "vo0".."vo12" a spell's two taps (bindCells)
                (listOf("open", "close", "topen", "croom", "bfon", "bfoff") + (0 until PV_GROUPS).map { "vb$it" } +
                    (0 until PV_PER).map { "vo$it" }).mapNotNull { k -> if (w.has(k)) w.optInt(k, -1).takeIf { it in 0..39 }?.let { k to it } else null }.toMap()
            } ?: emptyMap(),
        )
    }

    /** The book's tabs in the game's order, each holding the entries the game would show. */
    fun tabs(book: Book): List<Tab> {
        val out = ArrayList<Tab>()
        for (line in book.lines) {
            if (line.hidden) continue
            // s3: the professions (the game's Professions book) close the General tab, on pages of their own (Book.lua b23 alike)
            val entries = book.items.filter { it.line == line.index && visible(book, it) }.sortedBy { if (it.prof != null) 1 else 0 }
            val before = entries.count { it.prof == null }
            out += Tab("line:${line.index}", line.name, line.icon, entries, before.takeIf { it < entries.size && it > 0 }, prof = line.prof)
        }
        // the game: HasPetSpells() and PetHasSpellbook()
        if (book.petItems.isNotEmpty() && book.petHasBook) {
            out += Tab("pet", book.petName ?: "Pet", book.petIcon, book.petItems.filter { visible(book, it) })
        }
        return out
    }

    // ---------------------------------------------------------------- b23: pages with a section, rank families

    /** how many pages tab [t] fills, [per] entries a page; the professions section starts a page of its own (Book.lua's pagesOf) */
    fun pagesOf(t: Tab, per: Int): Int {
        val n = t.entries.size; val s = t.sect
        if (s == null || s <= 0 || s >= n) return maxOf(1, (n + per - 1) / per)
        return (s + per - 1) / per + (n - s + per - 1) / per
    }

    /** the entries on page [page] of tab [t] (Book.lua's pageSpan) */
    fun pageOf(t: Tab, page: Int, per: Int): List<Entry> {
        val n = t.entries.size; val s = t.sect
        if (s == null || s <= 0 || s >= n) return t.entries.drop((page - 1) * per).take(per)
        val g = (s + per - 1) / per
        return if (page <= g) t.entries.subList(0, s).drop((page - 1) * per).take(per)
               else t.entries.drop(s + (page - g - 1) * per).take(per)
    }

    /** true when page [page] of tab [t] is one of the professions section's */
    fun inSection(t: Tab, page: Int, per: Int): Boolean {
        val s = t.sect ?: return false
        return s in 1 until t.entries.size && page > (s + per - 1) / per
    }

    /** the rank number in a sub-name ("Rank 3"), null without one */
    fun rankNo(s: String?): Int? = s?.let { Regex("(\\d+)").find(it)?.value?.toIntOrNull() }

    /** b23 THE RANK FAMILY of [e] (the user 2026-10-07 picked "B: a rank picker on each spell"): the line's known spells of its name,
     *  the lower ranks too (shown or not), highest first; empty when it has no other rank. Book.lua's `ranks` alike. */
    fun ranksOf(book: Book, e: Entry): List<Entry> {
        if (e.pet || e.kind != Kind.SPELL || e.passive || e.flyoutMember || e.prof != null) return emptyList()
        val f = book.items.filter {
            it.line == e.line && it.name == e.name && it.kind == Kind.SPELL && !it.passive && !it.flyoutMember && it.prof == null
        }
        if (f.size < 2) return emptyList()
        return f.sortedWith(compareByDescending<Entry> { rankNo(it.rank) ?: 0 }.thenByDescending { it.slot })
    }

    /** The game's ShouldDisplaySpellBookItem, as it runs with the controller UI on and no search. */
    fun visible(book: Book, e: Entry): Boolean {
        if (book.hidePassives && e.passive) return false
        if (!book.showAllRanks && e.lowRank) return false
        if (e.flyoutMember) return false
        return true
    }

    fun status(book: Book, e: Entry): Status = when {
        e.kind != Kind.FUTURE -> Status.KNOWN
        (e.levelLearned ?: 0) > book.level -> Status.LEVEL
        else -> Status.TRAINABLE
    }

    // ---------------------------------------------------------------- what the room writes (UI Art's SPEC 2.3 / 2.4)

    /** The line under an entry's name: the rank, "Passive", or the game's own words for a spell not known yet
     *  ("Level %d" = SPELLBOOK_AVAILABLE_AT, "See your trainer" = SPELLBOOK_TRAINABLE). null when there is none. */
    fun sub(book: Book, e: Entry): String? = when (status(book, e)) {
        Status.LEVEL -> "Level ${e.levelLearned}"
        Status.TRAINABLE -> "See your trainer"
        // s3: a profession's spell says its profession ("Find Minerals" - Mining), unless it IS the profession ("First Aid" - its rank)
        Status.KNOWN -> if (e.prof != null && e.prof != e.name) e.prof else e.rank ?: if (e.passive) "Passive" else null
    }

    /** false for a KNOWN spell on no bar at all (the game's gold glow). A keyboard-only slot is still "on a bar". */
    fun onBar(book: Book, e: Entry): Boolean =
        e.pet || e.kind == Kind.FLYOUT || status(book, e) != Status.KNOWN || e.passive || e.bars.isNotEmpty()
    // (a pet's action lives on the pet bar, which the first build does not track, and a flyout is not bound in the first build:
    // neither glows)

    /** A pet's spell and auto-cast, as the kit draws it (UiKit.spell / bookEntry `autoCast`): 0 = it cannot cast itself, 1 = it may
     *  (the game's quiet gold corners), 2 = it does (bright corners and the ring). Hunter pets and warlock demons alike. */
    fun autoCast(e: Entry): Int = when {
        !e.pet || !e.autoCastAllowed -> 0
        e.autoCastOn -> 2
        else -> 1
    }

    /** Bind is offered for a known, active spell of the player's own book. */
    fun bindable(book: Book, e: Entry): Boolean = !e.pet && e.kind == Kind.SPELL && !e.passive

    /**
     * THE TAPS THAT OPEN BIND FOR [e] by touch on the `spells` row (b-druid-forms-bind, a player: "they don't have the ability to bind
     * change form as a druid"). Book b38: a fresh GROUP pick then the OFFSET of the spell's number, ONE batch (one column per spell ran
     * out at 26: a druid's forms had none and the touch did nothing). An older add-on's own column still works; null = no touch way.
     */
    fun bindCells(book: Book, e: Entry): List<Pair<String, Int>>? {
        e.padNumber?.let { n ->
            val g = book.win["vb${n / PV_PER}"]; val k = book.win["vo${n % PV_PER}"]
            if (g != null && k != null) return listOf("spells" to g, "spells" to k)
        }
        return e.padColumn?.let { listOf("spells" to it) }
    }
    /** Book b38: thirteen groups of thirteen numbers (the row's 26 free columns) */
    private const val PV_GROUPS = 13
    private const val PV_PER = 13

    /** The Controller tab's Spells list: the book's spells that can go on a button, in the book's own order, each once (the addon's
     *  `spellsBindableList` walks the same lines the same way; the controller's row is matched by spell id). */
    fun tabSpells(book: Book): List<Entry> =
        tabs(book).flatMap { it.entries }.filter { status(book, it) == Status.KNOWN && bindable(book, it) }.distinctBy { it.spellId ?: it.actionId }

    /** p11 / p14 (the user 2026-10-07: "in the controller menu, when assigning a spell, the spell menu needs some tabs. start on spells, then general (including professions), then extra"): the chooser's Spells tab ([general] false: the class's lines) or its General tab (true: the book's
     *  General line, its professions too). The addon's rows split the same way (Book b24 `gen`). */
    fun tabSpells(book: Book, which: String): List<Entry> =
        tabs(book).filter { t -> when (which) { "general" -> t.key == "line:1"; "professions" -> t.prof; else -> t.key != "line:1" && !t.prof } }
            .flatMap { it.entries }
            .filter { status(book, it) == Status.KNOWN && bindable(book, it) }.distinctBy { it.spellId ?: it.actionId }

    /** The tooltip's first line: "15 Mana   100 yd range   Instant" (three spaces between the parts, SPEC 2.4). */
    fun facts(e: Entry): String? {
        val parts = ArrayList<String>()
        e.cost.firstOrNull()?.let { c ->
            val word = when (c.token) {
                "MANA" -> "Mana"; "RAGE" -> "Rage"; "ENERGY" -> "Energy"; "FOCUS" -> "Focus"
                "RUNIC_POWER" -> "Runic Power"; null -> null
                else -> c.token.lowercase().replaceFirstChar { it.uppercase() }
            }
            when {
                c.amount != null && c.amount > 0 && word != null -> parts += "${c.amount} $word"
                c.percent != null && c.percent > 0 && word != null -> parts += "${fmt(c.percent)}% of base $word"
            }
        }
        val lo = e.rangeMin; val hi = e.rangeMax
        when {
            hi == null -> {}
            hi <= 5 -> parts += "Melee Range"
            lo != null && lo > 0 -> parts += "$lo - $hi yd range"
            else -> parts += "$hi yd range"
        }
        // a passive has no cast time in the game's tooltip
        if (!e.passive) e.castMs?.let { ms -> parts += if (ms <= 0) "Instant" else "${fmt(ms / 1000.0)} sec cast" }
        return if (parts.isEmpty()) null else parts.joinToString("   ")
    }

    private fun fmt(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v)

    /** The pad style the Settings room chose, as the kit's glyph sets name it. */
    enum class PadStyle(val prefix: String) { REV("rev"), LTR("ltr"), SHP("shp") }

    /** A controller button as glyph names for `UiKit.pad`: the triggers held (0, 1 or 2) and the button. */
    data class Combo(val triggers: List<String>, val button: String)

    /**
     * [p] drawn as the game names it for the bars. D-pad = the "gen" set; a face button by the KEY the game gives it
     * (position 5 left = PAD3 "x", 6 top = PAD4 "y", 7 right = PAD2 "b", 8 bottom = PAD1 "a"; the PlayStation set's
     * shapes stand for the same keys), in the chosen style. On a Nintendo pad the key "a" is the button printed A.
     */
    fun combo(p: PadSlot, style: PadStyle): Combo {
        val set = style.prefix
        val triggers = when (p.bar) {
            2 -> listOf("${set}_ltrigger"); 3 -> listOf("${set}_rtrigger"); 4 -> listOf("${set}_ltrigger", "${set}_rtrigger")
            else -> emptyList()
        }
        val key = when (p.pos) { 5 -> "x"; 6 -> "y"; 7 -> "b"; 8 -> "a"; else -> null }
        val button = when {
            p.pos in 1..4 -> "gen_" + arrayOf("left", "up", "right", "down")[p.pos - 1]
            style == PadStyle.SHP -> "shp_" + mapOf("x" to "square", "y" to "triangle", "b" to "circle", "a" to "cross").getValue(key!!)
            else -> "${set}_$key"
        }
        return Combo(triggers, button)
    }

    /** the two trigger words in a style (Glyphs.name, the one choke point for button words) */
    fun triggerNames(style: PadStyle): Pair<String, String> {
        val set = when (style) { PadStyle.REV -> CastSettings.ButtonLayout.NINTENDO; PadStyle.LTR -> CastSettings.ButtonLayout.XBOX
            PadStyle.SHP -> CastSettings.ButtonLayout.PLAYSTATION }
        return Glyphs.name("ltrigger", set) to Glyphs.name("rtrigger", set)
    }

    /** The trigger names for the Bind box's "which set" line, in the chosen style (SPEC 4). */
    fun heldLine(lt: Boolean, rt: Boolean, style: PadStyle): String {
        val (l, r) = triggerNames(style)
        return when {
            lt && rt -> "$l + $r held"; lt -> "$l held"; rt -> "$r held"; else -> "No trigger held"
        }
    }

    /** The first page (1-based) and index on it (1..perPage) of an entry in a tab, or null. */
    fun placeOf(tab: Tab, spellId: Int, perPage: Int = 10): Pair<Int, Int>? {
        val i = tab.entries.indexOfFirst { it.spellId == spellId || it.actionId == spellId }
        return if (i < 0) null else (i / perPage + 1) to (i % perPage + 1)
    }

    /** The first KNOWN entry's index on a page (0-based within the page), or 0 (SPEC 2.3: a new page chooses it). */
    fun firstKnown(book: Book, onPage: List<Entry>): Int = onPage.indexOfFirst { status(book, it) == Status.KNOWN }.coerceAtLeast(0)

    /** Learned spells the room has not shown yet, oldest first. */
    fun newSince(book: Book, seenNid: Int): List<News> = book.news.filter { it.nid > seenNid }.sortedBy { it.nid }

    /** Where the game put a spell it pushed onto a bar, if it did (the newest push for that spell). */
    fun pushedFor(book: Book, spellId: Int): Pushed? = book.pushed.filter { it.spellId == spellId }.maxByOrNull { it.nid }

    fun find(book: Book, spellId: Int): Entry? =
        book.items.firstOrNull { it.spellId == spellId || it.actionId == spellId }
            ?: book.petItems.firstOrNull { it.spellId == spellId || it.actionId == spellId }

    /** The tab a spell sits in, e.g. to open the book at a new spell. */
    fun tabOf(book: Book, e: Entry): String? =
        if (e.pet) "pet" else e.line?.let { l -> if (book.lines.any { it.index == l && !it.hidden }) "line:$l" else null }

    // ---------------------------------------------------------------- parsing helpers

    private fun entry(o: JSONObject, pet: Boolean): Entry? {
        val name = o.optString("n", "")
        if (name.isEmpty()) return null
        val kind = when (o.optInt("t", 0)) {
            1 -> Kind.SPELL; 2 -> Kind.FUTURE; 3 -> Kind.PET_ACTION; 4 -> Kind.FLYOUT; else -> Kind.OTHER
        }
        val bars = ArrayList<Int>()
        o.optJSONArray("bars")?.let { a -> for (i in 0 until a.length()) a.optInt(i).takeIf { it > 0 }?.let { bars += it } }
        // the plain bars first, a form's bar after them (UI Art: "the plain one is shown and +1 after it")
        val pad = objects(o.optJSONArray("pad")).mapNotNull { w ->
            val st = w.optInt("s", 0); val pg = w.optInt("pg", 0); val b = w.optInt("b", 0); val ps = w.optInt("p", 0)
            val form = w.optString("form", "").takeIf { it.isNotEmpty() }
            if (st > 0 && pg in 1..3 && b in 1..4 && ps in 1..8) PadSlot(st, pg, b, ps, form) else null
        }.sortedBy { if (it.form == null) 0 else 1 }
        fun need(k: String) = o.optJSONObject(k)?.let { n ->
            n.optString("t", "").takeIf { it.isNotEmpty() }?.let { Need(it, n.optBoolean("miss", false)) }
        }
        return Entry(
            slot = o.optInt("sl", 0),
            pet = pet,
            kind = kind,
            actionId = o.optInt("a", 0),
            spellId = o.optInt("id", 0).takeIf { it > 0 },
            name = name,
            rank = o.optString("sub", "").takeIf { it.isNotEmpty() },
            icon = o.optInt("i", 0),
            passive = o.optBoolean("pas", false),
            lowRank = o.optBoolean("low", false),
            flyoutMember = o.optBoolean("fm", false),
            levelLearned = o.optInt("lvl", 0).takeIf { it > 0 },
            line = o.optInt("ln", 0).takeIf { it > 0 },
            desc = o.optString("d", "").takeIf { it.isNotEmpty() },
            castMs = if (o.has("ct")) o.optInt("ct", 0) else null,
            rangeMin = o.optInt("rmin", 0).takeIf { it > 0 },
            rangeMax = o.optInt("rmax", 0).takeIf { it > 0 },
            cost = objects(o.optJSONArray("cost")).map { c ->
                Cost(if (c.has("c")) c.optInt("c", 0) else null, if (c.has("m")) c.optInt("m", 0) else null,
                    num(c, "pct"), num(c, "ps"), if (c.has("t")) c.optInt("t", 0) else null,
                    c.optString("tok", "").takeIf { it.isNotEmpty() })
            },
            bars = bars,
            pad = pad,
            autoCastAllowed = o.optBoolean("ac", false),
            autoCastOn = o.optBoolean("ae", false),
            padColumn = if (o.has("pc")) o.optInt("pc", -1).takeIf { it in 2..39 } else null,
            padNumber = if (o.has("pv")) o.optInt("pv", -1).takeIf { it >= 0 } else null,
            reagents = need("rg"),
            tools = need("tl"),
            prof = o.optString("pf", "").takeIf { it.isNotEmpty() },
        )
    }

    private fun objects(a: JSONArray?): List<JSONObject> {
        if (a == null) return emptyList()
        val out = ArrayList<JSONObject>(a.length())
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { out += it }
        return out
    }

    private fun num(o: JSONObject, k: String): Double? = if (o.has(k)) o.optDouble(k).takeIf { !it.isNaN() } else null
}
