package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Professions room's data and rules, with NO drawing: the look waits for UI Art's design and the
 * user's pick (user, 2026-09-28: "Yes, build offline now" for the part with no look).
 *
 * WHAT THE USER RULED (2026-09-28, AGENT_LOG "Professions: THE USER'S ANSWERS"):
 *  - missing reagents "like the game": each reagent as have / need for the chosen count, short ones red;
 *  - what can be made now "like the game": a count on every recipe row, and the game's "can make now" filter;
 *  - a profession closed in the game shows its recipes AS LAST SEEN, with the counts kept live from the bags;
 *    to craft, one tap opens the profession in the game, then Create;
 *  - actions: Open, Track, Create (a count chosen first) and Create All. Nothing else.
 *
 * WHERE THE NUMBERS COME FROM: the addon's `prof` and `profrec` documents (WowCompanionProfessions),
 * and the Bags room's section of the `full` document for what is in the bags (bags 0-4, never the bank).
 * While a profession is open in the game, "can make now" is the game's own count; otherwise it is worked
 * out here from the bags, which is the same sum for the plain reagents this game's recipes use.
 */
object ProfessionsModel {

    /** make:1 .. make:26 on the addon's control row; a bigger count is Create All */
    const val MAKE_MAX = 26

    /** the pad rows the addon registers (TapPad.lua, the shared pad) */
    const val ROW_CTL = "prof"
    const val ROW_SEL = "profsel"
    /** the SECURE row: one cell per profession casting its own spell (user's answer 2026-09-28 23:12: "The game's own way, clicked securely") */
    const val ROW_OPEN = "profopen"

    // the Safety kernel's sentinels (secret / absent / threw) are control characters
    private fun real(s: String?) = !s.isNullOrEmpty() && s[0].code > 3
    private fun JSONObject.str(k: String): String? = if (has(k)) optString(k).takeIf { real(it) } else null
    private fun JSONObject.int(k: String): Int? = if (has(k)) optInt(k) else null

    // ---------------------------------------------------------------- the documents

    /**
     * One of the game's spell buttons on a book card (user ruling 2026-09-30, "Copy the game"): the camelot book draws each
     * profession's spells as square buttons. [opens] = the one the secure open cell casts (it has a recipe window); every other
     * button is shown but INERT in the room (the user's "no spell casting" ruling: Fishing, Find Minerals).
     */
    class Btn(val name: String, val icon: Int, val rec: Boolean, val opens: Boolean)

    class Profession(val pos: Int, val id: Int, val name: String, val icon: Int, val rank: Int, val max: Int,
                     val mod: Int, val main: Boolean, val craft: Boolean,
                     /** its cell on the secure open row; published only while the cell really casts its spell */
                     val oslot: Int?,
                     /** the card's buttons, in the game's order (at most two) */
                     val btn: List<Btn> = emptyList()) {
        /**
         * A card on the Overview. The game's camelot book draws FIVE (the two main professions, First Aid,
         * Fishing, Cooking: its code reads five of GetProfessions' returns) while its window takes tabs for
         * seven, so a sixth or seventh line (a rogue's Poisons) is a tab only, as in the game.
         */
        val onBook: Boolean get() = pos in 1..5
    }

    /** crafting: [castMs] / [castEl] = the game's cast for the current item (its length, how far in it was when sent), or null */
    class Busy(val id: Int, val n: Int, val made: Int, val left: Int?, val castMs: Int? = null, val castEl: Int? = null)

    /**
     * How full the room's crafting bar is (0..1): the items made, plus how far the current item's cast is, like the game's own
     * cast bar (the user, 2026-09-30: "the actual crafting timer did not fill at all"). [sinceSent] = ms since the document came.
     */
    fun craftFill(b: Busy, sinceSent: Long): Float {
        val n = b.n.coerceAtLeast(1)
        val done = b.made.coerceIn(0, n)
        val d = b.castMs ?: 0
        val part = if (d > 0 && b.castEl != null) ((b.castEl + sinceSent).toFloat() / d).coerceIn(0f, 1f) else 0f
        return ((done + part) / n).coerceIn(0f, 1f)
    }

    /** the `prof` document: always sent */
    class Overview(val list: List<Profession>, val open: Int?, val foreign: Boolean, val sel: Int?,
                   val selName: String?, val busy: Busy?, val combat: Boolean,
                   val ctl: Map<String, Int>, val padRows: Set<String>,
                   /** rows the addon could not register because the shared pad was full */
                   val padFull: Set<String> = emptySet(),
                   /** the open line's name, and whether the game lists it (a class line it opens here may not be) */
                   val openName: String? = null, val openListed: Boolean = true,
                   /** the game's own words for the room (the addon's `str`: title, search, create, ...) */
                   val str: Map<String, String> = emptyMap(),
                   /** the room holds the controller (a guest of the shared capture), or why it could not take it */
                   val held: Boolean = false, val heldWhy: String? = null,
                   /** "book" / "page": what the addon holds the controller for; [back] = B is the game's own close button */
                   val mode: String? = null, val back: Boolean = false,
                   /** the close cell's column on the secure row (leaving by touch closes the profession), once the game loaded it */
                   val cslot: Int? = null,
                   /** LB / RB reach the room (capture version 4): they change the tabs, as in the game */
                   val shoulders: Boolean = false,
                   /** the game's professions window is shown (its book or a page), and the book cell's column (its Professions button) */
                   val win: Boolean = false, val bslot: Int? = null,
                   /** B cancels the craft now (addon p12: while a craft runs, B is the game's own /stopcasting and the room stays) */
                   val bstop: Boolean = false) {
        /** the game's word for [key], or [fallback] (English) when the addon did not send it */
        fun word(key: String, fallback: String): String = str[key] ?: fallback
        /** the tabs, in the game's order: every listed line with a crafting page (class-only lines only for their class) */
        val tabs: List<Profession> get() = list.filter { it.craft }
        /** the Overview's cards (the game's book: five places) */
        val cards: List<Profession> get() = list.filter { it.onBook }
    }

    class Output(val item: Int, val name: String?, val icon: Int, val quality: Int, val min: Int, val max: Int)
    class Reagent(val item: Int, val need: Int, val name: String?, val icon: Int, val quality: Int, val currency: Int)
    class Requirement(val name: String, val met: Boolean)
    /** one line of the game's tooltip for what a recipe makes (addon p13): [t] left text, [c] its colour "rrggbb"; [r] / [rc] the
     *  right text and its colour on a two-sided line ("Chest" ... "Leather") */
    class Tip(val t: String, val c: String?, val r: String?, val rc: String?)
    class Category(val id: Int, val name: String, val parent: Int?, val ord: Int)

    class Recipe(val id: Int, val name: String, val cat: Int, val icon: Int,
                 /** 0 orange, 1 yellow, 2 green, 3 grey (the game's relative difficulty); -1 unknown */
                 val diff: Int, val ups: Int, val multi: Boolean, val ench: Boolean,
                 val disabled: Boolean, val why: String?,
                 /** the game's own "can make now", only meaningful while the book is live */
                 val gameCount: Int?,
                 val tracked: Boolean, val req: List<Requirement>, val cooldown: Int,
                 val desc: String?, val out: Output?, val reag: List<Reagent>, val slot: Int?,
                 /** the game's sort keys inside a category: the level where it turns grey, the item level */
                 val triv: Int = 0, val ilvl: Int = 0,
                 /** what it makes, in the game's own tooltip lines (addon p13; empty until the addon has read them) */
                 val tip: List<Tip> = emptyList())

    /** the `profrec` document: one profession's recipe book */
    class Book(val id: Int, val name: String, val rank: Int, val max: Int, val mod: Int, val foreign: Boolean,
               val cats: List<Category>, val recipes: List<Recipe>, val waiting: Boolean) {
        private val byId = recipes.associateBy { it.id }
        fun recipe(id: Int?): Recipe? = id?.let { byId[it] }
    }

    fun parseOverview(p: JSONObject): Overview {
        val list = ArrayList<Profession>()
        val a = p.optJSONArray("list") ?: JSONArray()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.int("id") ?: continue
            val btn = ArrayList<Btn>()
            o.optJSONArray("btn")?.let { b ->
                for (k in 0 until b.length()) {
                    val x = b.optJSONObject(k) ?: continue
                    btn.add(Btn(x.str("n") ?: "", x.optInt("icon"), x.optBoolean("rec"), x.optBoolean("o")))
                }
            }
            list.add(Profession(o.optInt("pos"), id, o.str("name") ?: "", o.optInt("icon"), o.optInt("rank"),
                o.optInt("max"), o.optInt("mod"), o.optBoolean("main"), o.optBoolean("craft"), o.int("oslot"), btn))
        }
        val ctl = HashMap<String, Int>()
        p.optJSONObject("ctl")?.let { c -> for (k in c.keys()) ctl[k] = c.optInt(k) }
        val rows = HashSet<String>()
        p.optJSONObject("pad")?.optJSONObject("rows")?.let { r -> for (k in r.keys()) rows.add(k) }
        val busy = p.optJSONObject("busy")?.let { b ->
            val cast = b.optJSONObject("cast")
            Busy(b.optInt("id"), b.optInt("n"), b.optInt("made"), b.int("left"), cast?.int("d"), cast?.int("el"))
        }
        val full = HashSet<String>()
        p.optJSONArray("padFull")?.let { f -> for (i in 0 until f.length()) full.add(f.optString(i)) }
        val words = HashMap<String, String>()
        p.optJSONObject("str")?.let { o -> for (k in o.keys()) o.str(k)?.let { words[k] = it } }
        return Overview(list, p.int("open"), p.optBoolean("foreign"), p.int("sel"), p.str("selName"), busy,
            p.optBoolean("cb"), ctl, rows, full, p.str("openName"), if (p.has("openListed")) p.optBoolean("openListed") else true, words,
            p.optBoolean("held"), p.str("heldWhy"), p.str("mode"), p.optBoolean("back"), p.int("cslot"), p.optBoolean("tabs"),
            p.optBoolean("win"), p.int("bslot"), p.optBoolean("bstop"))
    }

    fun parseBook(p: JSONObject): Book {
        val cats = ArrayList<Category>()
        val ca = p.optJSONArray("cats") ?: JSONArray()
        for (i in 0 until ca.length()) {
            val o = ca.optJSONObject(i) ?: continue
            cats.add(Category(o.optInt("id"), o.str("name") ?: "", o.int("parent"), o.optInt("ord")))
        }
        val recipes = ArrayList<Recipe>()
        val ra = p.optJSONArray("r") ?: JSONArray()
        for (i in 0 until ra.length()) {
            val o = ra.optJSONObject(i) ?: continue
            val reag = ArrayList<Reagent>()
            val ga = o.optJSONArray("reag") ?: JSONArray()
            for (k in 0 until ga.length()) {
                val g = ga.optJSONObject(k) ?: continue
                reag.add(Reagent(g.optInt("item"), g.optInt("need", 1), g.str("name"), g.optInt("icon"),
                    g.optInt("q", 1), g.optInt("cur")))
            }
            val req = ArrayList<Requirement>()
            val qa = o.optJSONArray("req") ?: JSONArray()
            for (k in 0 until qa.length()) {
                val q = qa.optJSONObject(k) ?: continue
                req.add(Requirement(q.str("name") ?: continue, q.optBoolean("met")))
            }
            val out = o.optJSONObject("out")?.let { u ->
                Output(u.optInt("item"), u.str("name"), u.optInt("icon"), u.optInt("q", 1), u.optInt("min", 1), u.optInt("max", 1))
            }
            val tip = ArrayList<Tip>()
            o.optJSONArray("tip")?.let { ta ->
                for (k in 0 until ta.length()) {
                    val l = ta.optJSONObject(k) ?: continue
                    tip.add(Tip(l.str("t") ?: continue, l.str("c"), l.str("r"), l.str("rc")))
                }
            }
            recipes.add(Recipe(o.optInt("id"), o.str("name") ?: "?", o.optInt("cat"), o.optInt("icon"),
                o.int("diff") ?: -1, o.optInt("ups", 1), o.optBoolean("multi"), o.optBoolean("ench"),
                o.optBoolean("dis"), o.str("why"), o.int("n"), o.optBoolean("track"), req, o.optInt("cd"),
                o.str("desc"), out, reag, o.int("slot"), o.optInt("triv"), o.optInt("ilvl"), tip))
        }
        return Book(p.optInt("id"), p.str("name") ?: "", p.optInt("rank"), p.optInt("max"), p.optInt("mod"),
            p.optBoolean("foreign"), cats, recipes, p.optBoolean("waiting"))
    }

    /** the tip lines the page shows: the first line left out when it is the name the page already shows above them */
    fun tipShown(r: Recipe): List<Tip> {
        val name = r.out?.name ?: r.name
        return if (r.tip.isNotEmpty() && r.tip[0].t == name) r.tip.drop(1) else r.tip
    }

    // ---------------------------------------------------------------- counting

    /** item id -> how many are in the bags, from the Bags room's section (`items[].id`, `.count`) */
    fun bagCounts(bags: JSONObject?): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        val its = bags?.optJSONArray("items") ?: return out
        for (i in 0 until its.length()) {
            val o = its.optJSONObject(i) ?: continue
            val id = o.optInt("id")
            if (id <= 0) continue
            val n = if (o.has("count")) o.optInt("count") else 1
            out[id] = (out[id] ?: 0) + maxOf(n, 1)
        }
        return out
    }

    fun have(g: Reagent, counts: Map<Int, Int>): Int = if (g.item > 0) counts[g.item] ?: 0 else 0

    /** how many the bags allow; a recipe with no reagents, or a currency reagent, counts 0 here */
    fun fromBags(r: Recipe, counts: Map<Int, Int>): Int {
        if (r.reag.isEmpty() || r.reag.any { it.item <= 0 || it.need <= 0 }) return 0
        return r.reag.minOf { have(it, counts) / it.need }
    }

    /** "can make now": the game's own count while the book is live, the bags' otherwise */
    fun canMake(r: Recipe, counts: Map<Int, Int>, live: Boolean): Int =
        if (live && r.gameCount != null) r.gameCount else fromBags(r, counts)

    /** one reagent line of the details, for a chosen count (user: "like the game", with the shortfall) */
    class Line(val reagent: Reagent, val have: Int, val need: Int) {
        val short: Int get() = maxOf(0, need - have)
    }

    fun lines(r: Recipe, count: Int, counts: Map<Int, Int>): List<Line> =
        r.reag.map { Line(it, have(it, counts), it.need * maxOf(count, 1)) }

    /** the count box: 1 .. min(can make, 28); at least 1 so the box always reads a number */
    fun countRange(canMake: Int): IntRange = 1..maxOf(1, minOf(canMake, MAKE_MAX))

    // ---------------------------------------------------------------- the list, in the game's structure

    sealed class Row {
        class Header(val cat: Category, val depth: Int, val collapsed: Boolean) : Row()
        class Item(val recipe: Recipe, val canMake: Int, val depth: Int) : Row()
    }

    class Filter(val query: String = "", val onlyMakeable: Boolean = false, val onlySkillUp: Boolean = false)

    private fun matches(r: Recipe, q: String): Boolean {
        if (q.isEmpty()) return true
        if (r.name.lowercase().contains(q)) return true
        if (r.out?.name?.lowercase()?.contains(q) == true) return true
        return r.reag.any { it.name?.lowercase()?.contains(q) == true }
    }

    /** the game's order inside a category (its SortCategoryData): grey-level higher first, item level higher, then the name */
    private val recipeOrder = compareByDescending<Recipe> { it.triv }.thenByDescending { it.ilvl }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    private val catOrder = compareBy<Category> { it.ord }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

    /**
     * Headers and recipes as the game's list has them (Professions.GenerateCraftingDataProvider):
     *  - NOT searching: every category without a recipe of its own is dropped (the game's "root" headers),
     *    so the list is one level of headers, in the game's order (`ord`, then the name);
     *  - searching: the whole tree is kept, each sub-category under its parent.
     * Recipes inside a category in the game's order (see [recipeOrder]). A header shows only when something
     * under it survives the filter; a collapsed header hides what is under it.
     */
    fun rows(book: Book, counts: Map<Int, Int>, live: Boolean, filter: Filter, collapsed: Set<Int>): List<Row> {
        val q = filter.query.trim().lowercase()
        val kept = book.recipes.filter { r ->
            val n = canMake(r, counts, live)
            matches(r, q) && (!filter.onlyMakeable || n > 0) && (!filter.onlySkillUp || (r.diff in 0..2))
        }
        val byCat = kept.groupBy { it.cat }.mapValues { (_, v) -> v.sortedWith(recipeOrder) }
        val cats = book.cats.associateBy { it.id }
        val out = ArrayList<Row>()
        fun items(c: Category, depth: Int) {
            for (r in byCat[c.id] ?: emptyList()) out.add(Row.Item(r, canMake(r, counts, live), depth))
        }
        if (q.isEmpty()) {
            for (c in book.cats.filter { byCat.containsKey(it.id) }.sortedWith(catOrder)) {
                val shut = c.id in collapsed
                out.add(Row.Header(c, 0, shut))
                if (!shut) items(c, 1)
            }
        } else {
            val children = book.cats.groupBy { it.parent }
            fun hasAny(c: Category): Boolean =
                byCat.containsKey(c.id) || (children[c.id] ?: emptyList()).any { hasAny(it) }
            fun walk(c: Category, depth: Int) {
                if (!hasAny(c)) return
                val shut = c.id in collapsed
                out.add(Row.Header(c, depth, shut))
                if (shut) return
                items(c, depth + 1)
                for (sub in (children[c.id] ?: emptyList()).sortedWith(catOrder)) walk(sub, depth + 1)
            }
            for (c in book.cats.filter { it.parent == null || !cats.containsKey(it.parent) }.sortedWith(catOrder)) walk(c, 0)
        }
        // recipes whose category never arrived: at the end, without a header, never lost
        val placed = out.filterIsInstance<Row.Item>().map { it.recipe.id }.toHashSet()
        val shutAll = book.cats.filter { it.id in collapsed }.map { it.id }.toHashSet()
        for (r in kept.sortedWith(recipeOrder)) if (r.id !in placed && !isUnder(r.cat, shutAll, cats) && !cats.containsKey(r.cat))
            out.add(Row.Item(r, canMake(r, counts, live), 0))
        return out
    }

    private fun isUnder(cat: Int, shut: Set<Int>, cats: Map<Int, Category>): Boolean {
        var c: Int? = cat
        var guard = 0
        while (c != null && guard++ < 16) {
            if (c in shut) return true
            c = cats[c]?.parent
        }
        return false
    }

    // ---------------------------------------------------------------- the words the picked pictures show

    /** the Overview card's recipe line (UI Art's P1): how many, none (no crafting page), or not known yet */
    sealed class CardRecipes {
        class Count(val n: Int) : CardRecipes()
        object None : CardRecipes()          // "No recipes": a gathering profession or Fishing
        object Unseen : CardRecipes()        // a crafting profession never opened while the companion ran
    }

    fun cardRecipes(p: Profession, seen: Book?): CardRecipes = when {
        !p.craft -> CardRecipes.None
        seen == null -> CardRecipes.Unseen
        else -> CardRecipes.Count(seen.recipes.size)
    }

    /** "seen 2 h ago" beside the title of a book that is not live (UI Art's P2, seen); null while live */
    fun seenAgo(now: Long, seenAt: Long, live: Boolean): String? {
        if (live || seenAt <= 0L) return null
        val min = maxOf(0L, now - seenAt) / 60_000L
        return when {
            min < 1 -> "seen just now"
            min < 60 -> "seen $min min ago"
            min < 48 * 60 -> "seen ${min / 60} h ago"
            else -> "seen ${min / (24 * 60)} days ago"
        }
    }

    /** the game's templates ("Requires: %s", "%s [%d]"): each %s / %d takes the next argument, in order */
    fun fill(template: String, vararg args: Any): String {
        val out = StringBuilder()
        var k = 0; var i = 0
        while (i < template.length) {
            val ch = template[i]
            if (ch == '%' && i + 1 < template.length && template[i + 1] in "sd" && k < args.size) {
                out.append(args[k++].toString()); i += 2
            } else { out.append(ch); i++ }
        }
        return out.toString()
    }

    /** "2 days 3 hrs" style, for a cooldown in seconds */
    fun duration(secs: Int): String {
        val d = secs / 86400; val h = (secs % 86400) / 3600; val m = (secs % 3600) / 60
        return when {
            d > 0 -> "$d day${if (d > 1) "s" else ""}" + (if (h > 0) " $h hr${if (h > 1) "s" else ""}" else "")
            h > 0 -> "$h hr${if (h > 1) "s" else ""}" + (if (m > 0) " $m min" else "")
            else -> "${maxOf(m, 1)} min"
        }
    }

    /** the foot's line beside Open (UI Art's P2, seen) */
    fun notOpenLine(p: Profession): String = "${p.name} is not open in the game"

    // ---------------------------------------------------------------- the ONE action (select, then one action)

    /** a tap on the shared pad: the row's name and the column */
    class Tap(val row: String, val col: Int)

    enum class Kind { NONE, OPEN, CREATE }

    /** what the room's single action place shows, and what a press sends */
    class Action(val kind: Kind, val enabled: Boolean, val why: String?, val tap: Tap?)

    /** why a pad row cannot be used: the pad was full when the addon loaded, or it is simply not there */
    private fun missing(ov: Overview, row: String) = if (row in ov.padFull) "the tap pad is full" else "unavailable"

    private fun ctlTap(ov: Overview, key: String): Tap? =
        if (ROW_CTL in ov.padRows) ov.ctl[key]?.let { Tap(ROW_CTL, it) } else null

    /** is [book] the profession open in the game right now, and the player's own */
    fun isLive(ov: Overview?, book: Book?): Boolean =
        ov != null && book != null && ov.open == book.id && !ov.foreign && !book.foreign

    /**
     * The tap that makes the ADDON select [recipe] (no game action), or null when it already has.
     * The room sends it when the player picks a row of the live book; Create stays disabled until the
     * addon's `sel` comes back as that recipe, so the confirm the player reads is the addon's own.
     */
    fun selectTap(ov: Overview?, book: Book?, recipe: Recipe?): Tap? {
        if (ov == null || recipe == null || !isLive(ov, book) || ov.sel == recipe.id) return null
        val col = recipe.slot ?: return null
        return if (ROW_SEL in ov.padRows) Tap(ROW_SEL, col) else null
    }

    /**
     * Open while the viewed profession is closed in the game; Create once it is open (user's Q3 answer).
     * [count] is the count box's value.
     */
    fun action(ov: Overview?, viewed: Profession?, book: Book?, recipe: Recipe?, count: Int,
               counts: Map<Int, Int>): Action {
        if (ov == null || viewed == null || !viewed.craft) return Action(Kind.NONE, false, null, null)
        if (ov.open != viewed.id) {
            // Open = ONE tap on the profession's SECURE cell: the game casts its spell and opens its window in its own code
            val tap = viewed.oslot?.let { if (ROW_OPEN in ov.padRows) Tap(ROW_OPEN, it) else null }
            return Action(Kind.OPEN, tap != null, if (tap == null) missing(ov, ROW_OPEN) else null, tap)
        }
        if (ov.foreign || book?.foreign == true) return Action(Kind.NONE, false, "not your own profession", null)
        fun no(why: String) = Action(Kind.CREATE, false, why, null)
        if (recipe == null) return no("pick a recipe")
        if (ov.sel != recipe.id) return no(when {
            // without the select row the addon can never confirm the recipe: say so, never wait forever
            ROW_SEL !in ov.padRows -> missing(ov, ROW_SEL)
            recipe.slot == null -> "unavailable"
            else -> "waiting for the game"
        })
        ov.busy?.let { return no("crafting") }
        if (recipe.ench) return no("needs an item to enchant")
        if (recipe.cooldown > 0) return no("cooldown")
        recipe.req.filter { !it.met }.takeIf { it.isNotEmpty() }?.let { m ->
            return no("requires " + m.joinToString(", ") { it.name })
        }
        val can = canMake(recipe, counts, isLive(ov, book))
        if (can < 1) return no("missing reagents")
        if (count !in countRange(can)) return no("too many")
        val tap = ctlTap(ov, "make:$count") ?: return no(missing(ov, ROW_CTL))
        return Action(Kind.CREATE, true, null, tap)
    }

    /** Create All: the same checks as Create, for every one that can be made */
    fun createAll(ov: Overview?, viewed: Profession?, book: Book?, recipe: Recipe?, counts: Map<Int, Int>): Action {
        val one = action(ov, viewed, book, recipe, 1, counts)
        if (one.kind != Kind.CREATE || !one.enabled || ov == null) return Action(one.kind, false, one.why, null)
        val tap = ctlTap(ov, "all") ?: return Action(Kind.CREATE, false, missing(ov, ROW_CTL), null)
        return Action(Kind.CREATE, true, null, tap)
    }

    /** the Track check box: one explicit tap to [want] (never a toggle), only on the live book */
    fun trackTap(ov: Overview?, book: Book?, recipe: Recipe?, want: Boolean): Tap? {
        if (ov == null || recipe == null || !isLive(ov, book) || ov.sel != recipe.id) return null
        if (recipe.tracked == want) return null
        return ctlTap(ov, if (want) "track" else "untrack")
    }

    /**
     * The room holds the controller while it shows, in the mode of what it shows: "book" (B there is the game's own close button
     * while a profession is open: the game closes its window in its own code) or "page" (B = back to the book). No game action.
     * Nothing in the room ever closes the game's window by a call (user's answer 23:12).
     */
    fun modeTap(ov: Overview?, book: Boolean): Tap? {
        if (ov == null) return null
        val mode = if (book) "book" else "page"
        return if (ov.held && ov.mode == mode) null else ctlTap(ov, mode)
    }
    fun freeTap(ov: Overview?): Tap? = if (ov != null) ctlTap(ov, "free") else null

    /**
     * Entering the room on its book (addon p19, the order every room uses since R1): the `enter` key, with which the addon answers
     * "open now" and takes the controller one frame after the game's window has shown. An older addon without the key: the old
     * hold (`book`). Nothing while the room already holds on its book.
     */
    fun enterTap(ov: Overview?, book: Boolean): Tap? {
        if (ov == null || !book) return modeTap(ov, book)
        if (ov.held && ov.mode == "book") return null
        return ctlTap(ov, "enter") ?: modeTap(ov, book)
    }

    /**
     * A LIVE press reached the addon for this room while the room is NOT on screen: the addon still holds the controller (the
     * app was killed while it held, 2026-09-30). The player's own press gives it back: ONE `free` (no game action), at most one
     * every [wait] ms. Never from a document alone.
     */
    fun orphanFree(ov: Overview?, now: Long, lastSent: Long, wait: Long = 3000L): Tap? =
        if (ov != null && ov.held && now - lastSent >= wait) freeTap(ov) else null

    // ---------------------------------------------------------------- copying the game's window (user ruling 2026-09-30)

    /**
     * Selecting [p] (its tab, or its recipe button on the book) does what the game's tab and button do: ONE cast of its spell by
     * the secure cell, unless it is already the profession open in the game (the game then only shows its page). Null = no tap.
     */
    fun openTap(ov: Overview?, p: Profession?): Tap? {
        if (ov == null || p == null || !p.craft || ov.open == p.id) return null
        val col = p.oslot ?: return null
        return if (ROW_OPEN in ov.padRows) Tap(ROW_OPEN, col) else null
    }

    /**
     * An open that the game never answered (the lost-release race after a controller press: the secure open button acts on
     * the finger's release): ONE more tap, only when the addon did not answer the first ([answered]) AND the game still does not
     * have [p] open (openTap is null once it does). The first tap did nothing in the game, so this is still one open per press.
     */
    fun retryOpen(ov: Overview?, p: Profession?, answered: Boolean): Tap? = if (answered) null else openTap(ov, p)

    /**
     * Leaving the room by touch closes the game's window: ONE secure click on its own close button (null = it is not shown).
     * [bookJustSent]: the room itself opened the window a moment ago and the addon's report may not have arrived yet (on the Thor
     * 2026-09-30 15:40:43 a leave 0.4 s after entering sent `free` and left the window showing); the taps go in order, so the
     * close lands after the open. On a window that did not open, the game's close button does nothing.
     */
    fun closeTap(ov: Overview?, bookJustSent: Boolean = false): Tap? {
        if (ov == null || (ov.open == null && !ov.win && !bookJustSent)) return null
        val col = ov.cslot ?: return null
        return if (ROW_OPEN in ov.padRows) Tap(ROW_OPEN, col) else null
    }

    /**
     * The game's own Professions button (the book cell): ONE click opens the game's window on its BOOK, the way that readies its
     * tab bar (the fix for the game's TabIndicators error window). Only while the window is NOT shown: the game's button toggles.
     */
    fun bookTap(ov: Overview?): Tap? {
        if (ov == null || ov.win || ov.open != null) return null
        val col = ov.bslot ?: return null
        return if (ROW_OPEN in ov.padRows) Tap(ROW_OPEN, col) else null
    }

    /**
     * The game opened a profession: does the room come up on its page? Only for a change seen between TWO CURRENT documents.
     * A document replayed from the log's backlog (the app just started) or the first one after a start says what WAS, not what
     * the player just did. LOCK (2026-09-30, Home's integ-home20 finding): a backlog "open" made the restarted app enter the room
     * and tap the game with no touch. [current] = GameInput.note()'s answer for this document; [prevCurrent] = for the last one.
     */
    fun follows(was: Int?, ov: Overview?, current: Boolean, prevCurrent: Boolean): Boolean =
        current && prevCurrent && ov != null && ov.open != null && ov.open != was && ov.openListed && !ov.foreign

    /** the tabs as the game's tab indicators cycle them: the book (0), then every profession with a recipe window */
    fun tabOrder(ov: Overview?): List<Int> = listOf(0) + (ov?.tabs?.map { it.id } ?: emptyList())

    /** LB / RB: the tab next to [viewed], no wrapping (the game's indicators stop at the ends); null at an end */
    fun nextTab(ov: Overview?, viewed: Int, step: Int): Int? {
        val order = tabOrder(ov)
        val i = order.indexOf(viewed)
        return order.getOrNull((if (i < 0) 0 else i) + step)
    }
}
