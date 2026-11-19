package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE POLESTAR TAB OF THE QUESTS ROOM (pure: no Android types; dsforever/src/test PolestarTabTest). The user, 2026-11-16: "if Polestar is
 * active it should have its own tab at the top of the quests tab" - a Polestar menu: change zone and do whatever the player normally would
 * there. Built to design_polestar with every recommended pick:
 *  - D1 A on a step = LOOK at it (Polestar's own Goto: the arrow and the light blue row go there until it is done or Back is pressed);
 *  - D2 in a step's details Right moves onto its goals, A ticks a goal (again clears it, Polestar's own boxes);
 *  - D3 in a fight Skip, Previous, Look and Back work; Change guide, Choose your path and Reset are grey ("After the fight");
 *  - D4 a waiting path choice: Home's light blue row reads "Choose your path" and A opens this tab on the choice;
 *  - D5 Change guide lists the open guides, Suggested ("Here" in your zone), then folders of every guide this character can use;
 *  - D6 Home's list (and the Map room's) gets a "Polestar · <zone>" header the cursor skips ("Polestar · No guide" without a guide).
 * Map.lua's WC.pg (pg1) answers {"type":"pguide" / "pgsteps" / "pglist" / "pgstep" / "pgroute" / "pgop"}; the app taps FRAMED COMMANDS
 * on the "pnav" row ([frame]): 38, 12, op, s1, s2, args, k. Skip / Previous stay 38 then 10 / 11 (Home's, the Map room's, the wheel's).
 * Polestar's own words (titles, steps, goals, pitch, pros, cons) are drawn as Polestar gives them, never through Tr.t; our labels are
 * the constants below (Tr.t, with es / fr lines).
 */
object PolestarTab {
    const val SAY = 38
    const val FRAME = 12
    const val OPEN = 13; const val CLOSE = 14; const val LOOK = 15; const val BACK = 16; const val LOAD = 17; const val CONTINUE = 18
    const val ROUTE = 19; const val CHOOSE = 20; const val RESET = 21; const val DESCRIBE = 22; const val TICK = 23
    /** pg2 / pm2: Next / Previous step, Polestar's own Engine.Next / Engine.Prev (a look: nothing skipped, nothing un-skipped) */
    const val NEXT = 24; const val PREV = 25
    /** each op's args (Map.lua WC.pg.LEN) */
    val ARGS = mapOf(OPEN to 0, CLOSE to 0, LOOK to 2, BACK to 0, LOAD to 2, CONTINUE to 1, ROUTE to 0, CHOOSE to 1, RESET to 0,
        DESCRIBE to 2, TICK to 3, NEXT to 0, PREV to 0)
    /** the ops a fight refuses (D3; Map.lua WC.pg.FIGHT) */
    val FIGHT = setOf(LOAD, CONTINUE, CHOOSE, RESET)
    /** op 13's stamp (pg1 review): the tab opened (everything again) / the 20 s "still open" (nothing resent; Map.lua WC.pg.OPEN_KEEP).
     *  Like every stamp they are 432-1295 ([frame]): op 14 uses [OPEN_FRESH] */
    const val OPEN_FRESH = 468
    const val OPEN_KEEP = 469
    /** the ops an UNANSWERED frame may be tapped again for (pg1 review): ones that end the same if they ran twice. A Tick toggles and a
     *  Back can Unskip then Goto, so a frame that ran but whose answer came late would undo or change the press: never again unanswered
     *  (a garbled frame never ran: any op is sent once more) */
    val AGAIN_UNANSWERED = setOf(OPEN, CLOSE, LOOK, LOAD, CONTINUE, ROUTE, CHOOSE, RESET, DESCRIBE)

    // ---------------------------------------------------------------- our words (Tr.t, es / fr in i18n)
    const val TAB = "Polestar"
    const val CHANGE_GUIDE = "Change guide"
    const val CHOOSE_PATH = "Choose your path"
    const val NO_GUIDE = "No guide"
    const val DONE_WORD = "Done"
    const val RESET_WORD = "Reset guide progress"
    const val RESET_ASK = "Reset this guide's ticks and skips?"
    const val RESET_YES = "Reset"
    const val CANCEL = "Cancel"
    const val LOOK_WORD = "Look at step"
    const val TICK_WORD = "Tick"
    const val UNTICK_WORD = "Clear"
    const val GOALS_WORD = "Goals"
    const val LOAD_WORD = "Load guide"
    const val CONTINUE_WORD = "Continue"
    const val CHOOSE_WORD = "Choose"
    const val OPEN_WORD = "Open"
    const val SUGGESTED = "Suggested"
    const val HERE = "Here"
    const val GROUP = "Group"
    const val NEW = "New"
    const val RECOMMENDED = "Recommended"
    const val CHOSEN = "Chosen"
    const val OUTLEVELLED = "Outlevelled"
    const val COMPLETE = "Guide complete"
    const val FIGHT_LINE = "After the fight"
    const val CHANGED_LINE = "Polestar moved on"
    const val LOST_LINE = "Polestar did not answer"
    const val NOTHING_LINE = "Nothing to change"
    const val WAITING = "Waiting for Polestar"
    /** templates (Tr's "%s") */
    const val STEP_OF = "Step %s / %s"
    const val STEP_N = "Step %s"
    const val TAB_N = "Tab %s"
    const val LEVEL_N = "Level %s"
    const val JUMP_TO = "Jump to %s"
    const val BACK_TO = "Back to step %s"
    const val UNSKIP_N = "Unskip step %s"
    const val NEXT_IS = "Next: %s"
    const val DONE_N = "Done · %s"
    const val STARTED_N = "Started %s/%s"
    const val MINUTES_N = "~%s min"
    const val QUESTS_N = "%s quests"
    const val STEPS_N = "%s steps"
    const val HEAD = "Polestar · %s"

    /** every label the tab draws (the i18n test reads them) */
    val WORDS = listOf(TAB, CHANGE_GUIDE, CHOOSE_PATH, NO_GUIDE, DONE_WORD, RESET_WORD, RESET_ASK, RESET_YES, CANCEL, LOOK_WORD, TICK_WORD,
        UNTICK_WORD, GOALS_WORD, LOAD_WORD, CONTINUE_WORD, CHOOSE_WORD, OPEN_WORD, SUGGESTED, HERE, GROUP, NEW, RECOMMENDED, CHOSEN,
        OUTLEVELLED, COMPLETE, FIGHT_LINE, CHANGED_LINE, LOST_LINE, NOTHING_LINE, WAITING, "Unskip", "Back", "Close", "Started")
    val TEMPLATES = listOf(STEP_OF, STEP_N, TAB_N, LEVEL_N, JUMP_TO, BACK_TO, UNSKIP_N, NEXT_IS, DONE_N, STARTED_N, MINUTES_N, QUESTS_N,
        STEPS_N, HEAD)

    /** a template filled ("Step %s / %s", 8, 29 -> "Step 8 / 29"): the English Tr.t then matches */
    fun fill(t: String, vararg v: Any): String { var r = t; for (x in v) r = r.replaceFirst("%s", x.toString()); return r }

    // ---------------------------------------------------------------- the frame
    /** one framed command's taps on the "pnav" row: 38, 12, op, s1, s2, args, k (k = the sum of op, stamp digits and args, mod 36);
     *  empty for what no frame can say (an op of another length, an arg past 35): nothing is sent. Every stamp the add-on hands out is
     *  432-1295 (Map.lua WC.pg.hash; ours [OPEN_FRESH] / [OPEN_KEEP]), so s1 is 12-35: a frame that lost both its 12 and its op never
     *  leaves 0-11 behind the 38 (the automation mask, the row's word, Skip / Previous; pg1 review) */
    fun frame(op: Int, stamp: Int, args: List<Int> = emptyList()): List<Int> {
        if (op !in 13..35 || ARGS[op] != args.size || args.any { it !in 0..35 }) return emptyList()
        val st = stamp.coerceIn(0, 1295)
        val body = listOf(op, st / 36, st % 36) + args
        return listOf(SAY, FRAME) + body + (body.sum() % 36)
    }
    /** a number 0..1295 as its two base-36 digits */
    fun two(n: Int): List<Int> = n.coerceIn(0, 1295).let { listOf(it / 36, it % 36) }

    // ---------------------------------------------------------------- the documents
    class Tab(val title: String, val stamp: Int, val step: Int?, val count: Int, val done: Int?, val total: Int?, val complete: Boolean)

    /** {"type":"pguide"} */
    class Guide(
        val have: Boolean, val none: Boolean, val gid: String?, val stamp: Int, val title: String?, val zone: String?, val zoneMap: Int,
        val lo: Int?, val hi: Int?, val shown: Int?, val live: Int?, val count: Int, val done: Int?, val total: Int?,
        val complete: Boolean, val waiting: Boolean, val route: Boolean, val routeStamp: Int,
        val outlevel: Boolean, val outTitle: String?, val outIndex: Int?, val outChoice: Boolean,
        val nextTitle: String?, val nextIndex: Int?, val gate: Int?, val skip: Boolean, val undo: Int,
        val open: Boolean, val status: String?, val tabs: List<Tab>?, val active: Int?,
    ) {
        /** the header's place: the guide's zone, else its title */
        fun place(): String? = zone ?: title
        /** a step's status letter (d s n l u k), or null when not known (the tab closed, or out of range) */
        fun statusOf(i: Int): Char? = status?.getOrNull(i - 1)
        /** the shown step is a step Polestar skipped */
        fun shownSkipped(): Boolean = shown?.let { statusOf(it) == 's' || statusOf(it) == 'k' } ?: false
        /** Polestar looks at another step than the live one (the away banner's case: skipped, or a live step to go back to) */
        fun away(): Boolean = shown != null && shown != live && (shownSkipped() || live != null)
        fun sig(): String = listOf(gid, stamp, shown, live, done, total, waiting, route, outlevel, complete, status, tabs?.size, skip, undo)
            .joinToString("|")

        companion object {
            fun parse(d: JSONObject?): Guide? {
                if (d == null || d.optString("type") != "pguide") return null
                fun int(k: String): Int? = if (d.has(k) && !d.isNull(k)) d.optInt(k) else null
                fun str(k: String): String? = if (d.has(k) && !d.isNull(k)) d.optString(k, "").ifEmpty { null } else null
                val tabs = d.optJSONArray("tabs")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { t ->
                        Tab(t.optString("t", ""), t.optInt("s", -1), if (t.has("i")) t.optInt("i") else null, t.optInt("n", 0),
                            if (t.has("dn")) t.optInt("dn") else null, if (t.has("tt")) t.optInt("tt") else null, t.optBoolean("c"))
                    }
                }
                return Guide(d.optBoolean("have"), d.optBoolean("none"), str("gid"), d.optInt("gs", -1), str("t"), str("z"), d.optInt("zm", 0),
                    int("lo"), int("hi"), int("s"), int("l"), d.optInt("n", 0), int("dn"), int("tt"), d.optBoolean("cp"), d.optBoolean("rw"),
                    d.optBoolean("rc"), d.optInt("fs", -1), d.optBoolean("ol"), str("ot"), int("oi"), d.optBoolean("oc"), str("nx"),
                    int("ni"), int("gl"), d.optBoolean("sk"), d.optInt("u", 0), d.optBoolean("tb"), str("st"), tabs, int("ta"))
            }
        }
    }

    /** one guide of {"type":"pglist"} (its index in the list is its [k], 1-based) */
    class ListGuide(val k: Int, val title: String, val lo: Int?, val hi: Int?, val folder: Int, val state: Char, val done: Int?,
                    val total: Int?, val group: Boolean, val gate: Int?, val steps: Int?, val zoneMap: Int, val here: Boolean,
                    val suggested: Int?, val tab: Int?)
    class GuideList(val stamp: Int, val folders: List<String>, val guides: List<ListGuide>) {
        companion object {
            fun parse(d: JSONObject?): GuideList? {
                if (d == null || d.optString("type") != "pglist") return null
                val f = d.optJSONArray("f")?.let { a -> (0 until a.length()).map { a.optString(it, "") } } ?: emptyList()
                val g = d.optJSONArray("g")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { x ->
                        fun int(k: String): Int? = if (x.has(k) && !x.isNull(k)) x.optInt(k) else null
                        ListGuide(i + 1, x.optString("t", ""), int("lo"), int("hi"), x.optInt("f", 0), x.optString("st", "n").firstOrNull() ?: 'n',
                            int("dn"), int("tt"), x.optBoolean("gr"), int("gl"), int("n"), x.optInt("zm", 0), x.optBoolean("h"), int("sg"), int("tb"))
                    } }
                } ?: emptyList()
                return GuideList(d.optInt("ls", -1), f, g)
            }
        }
    }

    class Goal(val g: Int, val status: String, val text: String?, val cur: Int?, val max: Int?, val tickable: Boolean, val ticked: Boolean,
               val item: String?) {
        val done: Boolean get() = status == "done" || ticked
    }
    /** {"type":"pgstep"} */
    class StepDetail(val stamp: Int, val i: Int, val title: String?, val note: String?, val status: String?, val skipped: Boolean, val goals: List<Goal>) {
        val tickable: List<Goal> get() = goals.filter { it.tickable }
        companion object {
            fun parse(d: JSONObject?): StepDetail? {
                if (d == null || d.optString("type") != "pgstep") return null
                val goals = d.optJSONArray("g")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { x ->
                        Goal(x.optInt("g", 0), x.optString("s", ""), x.optString("x", "").ifEmpty { null },
                            if (x.has("c") && x.has("m")) x.optInt("c") else null, if (x.has("c") && x.has("m")) x.optInt("m") else null,
                            x.optBoolean("tk"), x.optBoolean("ti"), x.optString("it", "").ifEmpty { null })
                    }
                } ?: emptyList()
                fun str(k: String): String? = if (d.has(k) && !d.isNull(k)) d.optString(k, "").ifEmpty { null } else null
                return StepDetail(d.optInt("gs", -1), d.optInt("i", 0), str("t"), str("nt"), str("st"), d.optBoolean("sk"), goals)
            }
        }
    }

    class RouteOpt(val o: Int, val title: String, val zone: String?, val zoneMap: Int, val lo: Int?, val hi: Int?, val seconds: Int?,
                   val quests: Int?, val steps: Int?, val recommended: Boolean, val chosen: Boolean, val outlevelled: Boolean,
                   val pitch: String?, val pros: List<String>, val cons: List<String>)
    /** {"type":"pgroute"} */
    class Route(val stamp: Int, val fromTitle: String?, val joins: String?, val options: List<RouteOpt>) {
        companion object {
            fun parse(d: JSONObject?): Route? {
                if (d == null || d.optString("type") != "pgroute" || d.optBoolean("none")) return null
                fun list(x: JSONObject, k: String) = x.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it, "") }.filter { it.isNotEmpty() } } ?: emptyList()
                val o = d.optJSONArray("o")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { x ->
                        fun int(k: String): Int? = if (x.has(k) && !x.isNull(k)) x.optInt(k) else null
                        RouteOpt(i + 1, x.optString("t", ""), x.optString("z", "").ifEmpty { null }, x.optInt("zm", 0), int("lo"), int("hi"),
                            int("sec"), int("q"), int("n"), x.optBoolean("rec"), x.optBoolean("ch"), x.optBoolean("ol"),
                            x.optString("pt", "").ifEmpty { null }, list(x, "pr").take(3), list(x, "cn").take(2))
                    } }
                } ?: emptyList()
                return Route(d.optInt("fs", -1), d.optString("ft", "").ifEmpty { null }, d.optString("j", "").ifEmpty { null }, o)
            }
        }
    }

    /** an answer {"type":"pgop"} */
    class Answer(val op: Int, val ok: Boolean, val why: String?, val i: Int?)
    fun answer(d: JSONObject?): Answer? {
        if (d == null || d.optString("type") != "pgop") return null
        return Answer(d.optInt("op", 0), d.optBoolean("ok"), d.optString("why", "").ifEmpty { null }, if (d.has("i")) d.optInt("i") else null)
    }

    /** the line for a refused answer's why (null: nothing to say) */
    fun refusedLine(why: String?): String? = when (why) {
        null, "" -> null
        "combat" -> FIGHT_LINE
        "changed" -> CHANGED_LINE
        "nothing", "none" -> NOTHING_LINE
        "garbled", "lost" -> LOST_LINE
        "no Polestar" -> PolestarSkip.NO_STEP_LINE
        else -> PolestarSkip.REFUSED_LINE
    }

    /** "Title (min–max)": Polestar's title with its levels */
    fun levels(lo: Int?, hi: Int?): String? = when {
        lo == null && hi == null -> null
        lo == null || hi == null || lo == hi -> (lo ?: hi).toString()
        else -> "$lo–$hi"
    }
    fun titled(title: String?, lo: Int?, hi: Int?): String = listOfNotNull(title, levels(lo, hi)?.let { "($it)" }).joinToString(" ")

    // ---------------------------------------------------------------- the docs, kept
    /** everything the add-on said, kept by guide (a step's goals by its stamp and index) */
    class Book {
        var guide: Guide? = null; private set
        var guideAt = 0L; private set
        var list: GuideList? = null; private set
        var route: Route? = null; private set
        private val titleChunks = HashMap<Int, Array<String?>>()       // stamp -> titles
        private val steps = HashMap<String, StepDetail>()               // "stamp:i" -> goals
        private val stepAt = HashMap<String, Long>()

        /** one document; true = something the tab shows changed */
        fun onDoc(d: JSONObject, now: Long): Boolean = when (d.optString("type")) {
            "pguide" -> Guide.parse(d)?.let { g ->
                val was = guide; guide = g; guideAt = now
                // a new guide: its titles are the new stamp's (an old stamp's go, as its goals)
                if (was?.stamp != g.stamp) { titleChunks.keys.retainAll(setOf(g.stamp)); steps.keys.retainAll { it.startsWith("${g.stamp}:") } }
                was?.sig() != g.sig() || was?.title != g.title
            } ?: false
            "pgsteps" -> {
                val gs = d.optInt("gs", -1); val n = d.optInt("n", 0); val o = d.optInt("o", 1)
                if (n in 1..2000) {
                    val arr = titleChunks[gs]?.takeIf { it.size == n } ?: arrayOfNulls<String>(n).also { titleChunks[gs] = it }
                    val t = d.optJSONArray("t")
                    if (t != null) for (j in 0 until t.length()) { val idx = o - 1 + j; if (idx in 0 until n) arr[idx] = t.optString(j, "") }
                    true
                } else false
            }
            "pglist" -> GuideList.parse(d)?.let { list = it; true } ?: false
            "pgroute" -> { route = Route.parse(d); true }
            "pgstep" -> StepDetail.parse(d)?.let { s -> steps["${s.stamp}:${s.i}"] = s; stepAt["${s.stamp}:${s.i}"] = now; true } ?: false
            else -> false
        }

        /** step [i]'s title of the shown guide ("" none known) */
        fun title(i: Int): String? = guide?.let { g -> titleChunks[g.stamp]?.getOrNull(i - 1)?.takeIf { it.isNotEmpty() } }
        /** step [i]'s goals, when the add-on has described it for this guide */
        fun step(i: Int): StepDetail? = guide?.let { g -> steps["${g.stamp}:$i"] }
        fun stepAt(i: Int): Long = guide?.let { g -> stepAt["${g.stamp}:$i"] } ?: 0L
        /** the tab can show: Polestar answered with its engine */
        fun ready(): Boolean = guide?.have == true
        /** forget (Polestar went away, a new login) */
        fun clear() { guide = null; list = null; route = null; titleChunks.clear(); steps.clear(); stepAt.clear() }
    }

    // ---------------------------------------------------------------- the list's rows
    enum class Notice { NO_GUIDE, CHOOSE, COMPLETE, AWAY, OUTLEVEL }

    sealed class Row {
        abstract val key: String
        object GuideRow : Row() { override val key = "guide" }
        data class Note(val kind: Notice) : Row() { override val key get() = "n:$kind" }
        data class DoneFold(val count: Int, val open: Boolean) : Row() { override val key get() = "done" }
        /** step [i]: [status] its letter, [shown] the step Polestar shows (the light blue one), [live] its live step */
        data class Step(val i: Int, val status: Char, val shown: Boolean, val live: Boolean) : Row() { override val key get() = "s:$i" }
        object ResetRow : Row() { override val key = "reset" }
    }

    /** the notices (0-2), first first */
    fun notices(g: Guide): List<Notice> {
        if (g.none) return listOf(Notice.NO_GUIDE)
        val out = ArrayList<Notice>()
        if (g.waiting) out.add(Notice.CHOOSE) else if (g.complete) out.add(Notice.COMPLETE)
        if (g.away()) out.add(Notice.AWAY)
        if (g.outlevel && out.size < 2) out.add(Notice.OUTLEVEL)
        return out.take(2)
    }

    /**
     * The list, top to bottom: the guide row, the notices, the done steps before the live one (and before the shown one) folded into
     * one row (open while [foldOpen]), the live and upcoming steps (steps not for this character left out), Reset last. No guide: the
     * guide row and its notice only.
     */
    fun rows(g: Guide?, foldOpen: Boolean): List<Row> {
        if (g == null) return emptyList()
        val out = ArrayList<Row>()
        out.add(Row.GuideRow)
        for (n in notices(g)) out.add(Row.Note(n))
        if (g.none) return out
        val n = g.count
        val foldEnd = minOf(g.live ?: (n + 1), g.shown ?: (n + 1))
        val letters = (1..n).map { g.statusOf(it) ?: if (g.live != null && it < g.live) 'd' else 'u' }
        val folded = (1 until foldEnd).filter { letters[it - 1] != 'n' }
        if (folded.isNotEmpty()) {
            out.add(Row.DoneFold(folded.size, foldOpen))
            if (foldOpen) for (i in folded) out.add(Row.Step(i, letters[i - 1], i == g.shown, i == g.live))
        }
        for (i in foldEnd..n) if (letters[i - 1] != 'n') out.add(Row.Step(i, letters[i - 1], i == g.shown, i == g.live))
        out.add(Row.ResetRow)
        return out
    }

    /** the row the tab lands on when it is entered: the shown step, else the guide row */
    fun landing(rows: List<Row>): String = rows.firstOrNull { it is Row.Step && it.shown }?.key ?: rows.firstOrNull()?.key ?: Row.GuideRow.key

    /**
     * CURSOR STABILITY (the user's untrack complaint, the same list problem): after any refresh the cursor stays on the same key; a
     * step that left (folded, not for this character) hands it to the nearest step by number, else the fold, else the guide row.
     */
    fun keep(sel: String?, rows: List<Row>): String? {
        if (rows.isEmpty()) return null
        if (sel != null && rows.any { it.key == sel }) return sel
        val i = sel?.takeIf { it.startsWith("s:") }?.removePrefix("s:")?.toIntOrNull()
        if (i != null) {
            val steps = rows.filterIsInstance<Row.Step>()
            steps.minByOrNull { kotlin.math.abs(it.i - i) * 2 + (if (it.i < i) 1 else 0) }?.let { near ->
                // inside the closed fold: the fold row
                val fold = rows.firstOrNull { it is Row.DoneFold } as Row.DoneFold?
                return if (fold != null && !fold.open && near.i > i && steps.none { it.i < i }) fold.key else near.key
            }
        }
        if (sel != null && sel.startsWith("n:")) return rows.firstOrNull { it is Row.Note }?.key ?: Row.GuideRow.key
        return rows.firstOrNull { it is Row.GuideRow }?.key ?: rows.first().key
    }

    // ---------------------------------------------------------------- Change guide's rows (D5)
    sealed class PickRow {
        abstract val key: String
        /** a heading: not a cursor stop */
        data class Head(val word: String, val count: Int) : PickRow() { override val key get() = "h:$word" }
        /** an open tab [t] (1-based) */
        data class OpenTab(val t: Int, val tab: Tab, val active: Boolean) : PickRow() { override val key get() = "t:$t" }
        /** a guide of the list ([sugg]: under Suggested) */
        data class Pick(val g: ListGuide, val sugg: Boolean) : PickRow() { override val key get() = (if (sugg) "g:" else "f:") + g.k }
        /** a folder [f] (0-based in the list's folders), closed until opened */
        data class Folder(val f: Int, val word: String, val count: Int, val open: Boolean) : PickRow() { override val key get() = "d:$f" }
        val stop: Boolean get() = this !is Head
    }

    /** Open guides (Polestar's tabs), Suggested (up to 5), then every folder ([openFolders] open) with its guides */
    fun pickRows(g: Guide?, list: GuideList?, openFolders: Set<Int>): List<PickRow> {
        val out = ArrayList<PickRow>()
        val tabs = g?.tabs.orEmpty()
        if (tabs.isNotEmpty()) {
            out.add(PickRow.Head(OPEN_WORD, tabs.size))
            for ((i, t) in tabs.withIndex()) out.add(PickRow.OpenTab(i + 1, t, g?.active == i + 1))
        }
        val l = list ?: return out
        val sugg = l.guides.filter { it.suggested != null }.sortedBy { it.suggested }.take(5)
        if (sugg.isNotEmpty()) {
            out.add(PickRow.Head(SUGGESTED, sugg.size))
            for (s in sugg) out.add(PickRow.Pick(s, sugg = true))
        }
        for ((f, word) in l.folders.withIndex()) {
            val gs = l.guides.filter { it.folder == f + 1 }
            if (gs.isEmpty()) continue
            val open = f in openFolders
            out.add(PickRow.Folder(f, word, gs.size, open))
            if (open) for (x in gs) out.add(PickRow.Pick(x, sugg = false))
        }
        return out
    }

    /** the key the picker lands on: the active tab, else the first suggestion, else the first stop */
    fun pickLanding(rows: List<PickRow>): String? =
        rows.firstOrNull { it is PickRow.OpenTab && it.active }?.key ?: rows.firstOrNull { it is PickRow.Pick && it.sugg }?.key
            ?: rows.firstOrNull { it.stop }?.key

    // ---------------------------------------------------------------- the cursor
    enum class View { LIST, GOALS, PICKER, PATH, CONFIRM }

    /** what a press does */
    sealed class Do {
        object Nothing : Do()
        /** Up from the first row: the tab row (the Quests room's) */
        object ToTabs : Do()
        object Leave : Do()
        data class Sel(val key: String) : Do()
        data class Look(val i: Int) : Do()
        object Back : Do()
        object Skip : Do()
        object Prev : Do()
        object Picker : Do()
        object Path : Do()
        object AskReset : Do()
        object Reset : Do()
        object Fold : Do()
        data class Load(val k: Int) : Do()
        data class Cont(val t: Int) : Do()
        data class Choose(val o: Int) : Do()
        data class Tick(val i: Int, val g: Int) : Do()
        data class ToggleFolder(val f: Int) : Do()
        /** B / Left out of a sub-view: back to the list (or the goals to the list) */
        object Out : Do()
        object GoalsIn : Do()
        /** a press the fight refuses (D3): nothing sent, its line said */
        data class Refused(val line: String) : Do()
    }

    /**
     * THE TAB'S CONTROLLER: one selector per view, the details follow it. B one step back (confirm -> sub-view -> goals -> list ->
     * Home); select -> ONE action (A), X / Y only Skip / Previous on the guide row and the shown step's row.
     */
    class Cursor {
        var view = View.LIST; private set
        var sel: String? = null; private set
        var goal = 0; private set
        var pick: String? = null; private set
        var path = 0; private set
        var foldOpen = false
        val folders = HashSet<Int>()

        /** the tab was entered: the cursor on the shown step (only here, never on a refresh) */
        fun enter(rows: List<Row>) { view = View.LIST; sel = landing(rows); goal = 0 }
        /**
         * after a refresh: the same key ([keep]); one exception - the cursor was on the shown step's row ([oldShown]) and Polestar shows
         * another one now ([newShown]: a Skip, a Previous, a Back), so it follows to the new shown step (X again skips the next one)
         */
        fun refresh(rows: List<Row>, oldShown: Int? = null, newShown: Int? = null) {
            val follow = oldShown != null && newShown != null && oldShown != newShown && sel == "s:$oldShown" && rows.any { it.key == "s:$newShown" }
            sel = if (follow) "s:$newShown" else keep(sel, rows)
            if (view == View.GOALS && (follow || selStep(rows) == null)) view = View.LIST
        }
        fun select(key: String) { sel = key; if (view == View.GOALS) view = View.LIST }
        fun openPicker(rows: List<PickRow>) { view = View.PICKER; pick = pickLanding(rows) }
        fun openPath(route: Route?) { view = View.PATH; path = route?.options?.indexOfFirst { it.chosen }?.takeIf { it >= 0 }
            ?: route?.options?.indexOfFirst { it.recommended }?.takeIf { it >= 0 } ?: 0 }
        fun openConfirm() { view = View.CONFIRM }
        fun toList() { view = View.LIST }
        fun toGoals() { view = View.GOALS; goal = 0 }
        fun pickSel(key: String) { pick = key }
        fun pathSel(o: Int) { path = o }
        fun goalSel(g: Int) { goal = g }
        private fun selStep(rows: List<Row>): Row.Step? = rows.firstOrNull { it.key == sel } as? Row.Step

        /** B's next stop, for the Home button's B glyph */
        fun bLeads(): String = if (view == View.LIST) "home" else "back"

        /**
         * One press [k] (PADDUP / DOWN / LEFT / RIGHT, PAD1 A, PAD2 B, PAD3 X, PAD4 Y). [rows] the list, [picks] Change guide's rows,
         * [route] the path choice, [detail] the selected step's goals (when loaded), [g] the guide, [fight] in combat.
         */
        fun key(k: String, rows: List<Row>, picks: List<PickRow>, route: Route?, detail: StepDetail?, g: Guide?, fight: Boolean): Do {
            val d = press(k, rows, picks, route, detail, g)
            return if (fight && fightOnly(d)) Do.Refused(FIGHT_LINE) else d
        }

        private fun press(k: String, rows: List<Row>, picks: List<PickRow>, route: Route?, detail: StepDetail?, g: Guide?): Do {
            when (view) {
                View.CONFIRM -> return when (k) { "PAD1" -> Do.Reset; "PAD2" -> Do.Out; else -> Do.Nothing }
                View.PATH -> {
                    val n = route?.options?.size ?: 0
                    return when (k) {
                        "PADDUP", "PADDDOWN" -> { if (n == 0) return Do.Nothing
                            val j = (path + if (k == "PADDUP") -1 else 1).coerceIn(0, n - 1); if (j != path) { path = j; Do.Sel("o:$j") } else Do.Nothing }
                        "PAD1" -> if (n == 0) Do.Nothing else Do.Choose(route!!.options[path].o)
                        "PAD2" -> Do.Out
                        else -> Do.Nothing
                    }
                }
                View.PICKER -> {
                    val stops = picks.filter { it.stop }
                    val i = stops.indexOfFirst { it.key == pick }
                    return when (k) {
                        "PADDUP", "PADDDOWN" -> { if (stops.isEmpty()) return Do.Nothing
                            val j = if (i < 0) 0 else (i + if (k == "PADDUP") -1 else 1).coerceIn(0, stops.size - 1)
                            if (stops[j].key != pick) { pick = stops[j].key; Do.Sel(stops[j].key) } else Do.Nothing }
                        "PAD1" -> when (val r = stops.getOrNull(i)) {
                            is PickRow.Folder -> Do.ToggleFolder(r.f)
                            is PickRow.OpenTab -> if (r.active) Do.Out else Do.Cont(r.t)
                            is PickRow.Pick -> r.g.tab?.let { t -> if (g?.active == t) Do.Out else Do.Cont(t) } ?: Do.Load(r.g.k)
                            else -> Do.Nothing
                        }
                        "PAD2" -> Do.Out
                        else -> Do.Nothing
                    }
                }
                View.GOALS -> {
                    val goals = detail?.tickable.orEmpty()
                    val step = selStep(rows)
                    return when (k) {
                        "PADDUP", "PADDDOWN" -> { if (goals.isEmpty()) return Do.Nothing
                            val j = (goal + if (k == "PADDUP") -1 else 1).coerceIn(0, goals.size - 1); if (j != goal) { goal = j; Do.Sel("g:$j") } else Do.Nothing }
                        "PAD1" -> { val x = goals.getOrNull(goal); if (x != null && step != null) Do.Tick(step.i, x.g) else Do.Nothing }
                        "PAD2", "PADDLEFT" -> { view = View.LIST; Do.Out }
                        else -> Do.Nothing
                    }
                }
                View.LIST -> {}
            }
            val i = rows.indexOfFirst { it.key == sel }
            val row = rows.getOrNull(i)
            return when (k) {
                "PADDUP" -> if (i <= 0) Do.ToTabs else { sel = rows[i - 1].key; Do.Sel(rows[i - 1].key) }
                "PADDDOWN" -> if (rows.isEmpty()) Do.Nothing else {
                    val j = if (i < 0) 0 else (i + 1).coerceAtMost(rows.size - 1)
                    if (rows[j].key != sel) { sel = rows[j].key; Do.Sel(rows[j].key) } else Do.Nothing
                }
                "PADDRIGHT" -> if (row is Row.Step && !detail?.tickable.isNullOrEmpty() && detail?.i == row.i) { view = View.GOALS; goal = 0; Do.GoalsIn } else Do.Nothing
                "PAD1" -> when (row) {
                    Row.GuideRow -> Do.Picker
                    is Row.Note -> noteDo(row.kind, g)
                    is Row.DoneFold -> Do.Fold
                    is Row.Step -> if (row.shown) Do.Nothing else Do.Look(row.i)
                    Row.ResetRow -> Do.AskReset
                    null -> Do.Nothing
                }
                "PAD3" -> if (canXY(row, g)) Do.Skip else Do.Nothing
                "PAD4" -> if (canXY(row, g) && (g?.undo ?: 0) > 0) Do.Prev else Do.Nothing
                "PAD2" -> Do.Leave
                else -> Do.Nothing
            }
        }
    }

    /** X / Y (Skip / Previous) are drawn and pressed only on the guide row and the shown step's row (design B6) */
    fun canXY(row: Row?, g: Guide?): Boolean = g != null && g.skip && !g.none && g.shown != null &&
        (row == Row.GuideRow || (row is Row.Step && row.shown))

    /** a notice's one action */
    fun noteDo(kind: Notice, g: Guide?): Do = when (kind) {
        Notice.NO_GUIDE -> Do.Picker
        Notice.CHOOSE -> Do.Path
        Notice.AWAY -> Do.Back
        Notice.OUTLEVEL -> if (g?.outChoice == true) Do.Path else g?.outIndex?.let { Do.Load(it) } ?: Do.Picker
        Notice.COMPLETE -> g?.nextIndex?.let { Do.Load(it) } ?: Do.Picker
    }

    /** a notice's words (ours, the guide's title filled in as Polestar gives it) */
    fun noteWords(kind: Notice, g: Guide): String = when (kind) {
        Notice.NO_GUIDE -> CHANGE_GUIDE
        Notice.CHOOSE -> CHOOSE_PATH
        Notice.AWAY -> if (g.shownSkipped()) fill(UNSKIP_N, g.shown ?: 0) else fill(BACK_TO, g.live ?: 0)
        Notice.OUTLEVEL -> if (g.outChoice) CHOOSE_PATH else fill(JUMP_TO, g.outTitle ?: "")
        Notice.COMPLETE -> g.nextTitle?.let { fill(NEXT_IS, it) } ?: COMPLETE
    }
    /** the action word on a notice's A */
    fun noteAction(kind: Notice, g: Guide): String = when (kind) {
        Notice.NO_GUIDE -> CHANGE_GUIDE
        Notice.CHOOSE -> CHOOSE_WORD
        Notice.AWAY -> if (g.shownSkipped()) "Unskip" else "Back"
        Notice.OUTLEVEL -> if (g.outChoice) CHOOSE_WORD else LOAD_WORD
        Notice.COMPLETE -> if (g.nextIndex != null) LOAD_WORD else CHANGE_GUIDE
    }
    /** a press that needs the fight to end (D3): grey with [FIGHT_LINE] */
    fun fightOnly(d: Do): Boolean = d is Do.Picker || d is Do.Path || d is Do.AskReset || d is Do.Reset || d is Do.Load || d is Do.Cont || d is Do.Choose

    // ---------------------------------------------------------------- one frame in flight
    /**
     * THE SENDER (one frame in flight; the next press waits up to [WAIT_MS] for the answer; a garbled frame is sent once more, an
     * unanswered one too when running it twice ends the same, [AGAIN_UNANSWERED]; "changed" refreshes and says so). Pure: [now] ms.
     * The host taps what [send] / [onAnswer] / [tick] hand back.
     */
    class Sender {
        class Out(val op: Int, val taps: List<Int>, val resend: Boolean)
        /** [dur]: how long its own taps take (the spacing's), counted before the wait */
        private class Flight(val op: Int, val taps: List<Int>, val at: Long, val tries: Int, val dur: Long)
        private var flight: Flight? = null
        /** the presses waiting, in order: one per op (a later press of the same op replaces it; open / close replace each other) */
        private val queued = ArrayList<Pair<Triple<Int, List<Int>, Long>, Long>>()

        val busy: Boolean get() = flight != null
        fun inFlight(): Int? = flight?.op

        /** a frame to send: now (returned), or after the one in flight (kept: the latest press of each op waits, in order) */
        fun send(op: Int, taps: List<Int>, now: Long, dur: Long = 0L): Out? {
            if (taps.isEmpty()) return null
            if (flight != null) {
                queued.removeAll { it.first.first == op || (op == OPEN && it.first.first == CLOSE) || (op == CLOSE && it.first.first == OPEN) }
                queued.add(Triple(op, taps, dur) to now)
                return null
            }
            flight = Flight(op, taps, now, 1, dur)
            return Out(op, taps, false)
        }
        /** the tap could not go at all (the game not live, no pad): nothing in flight */
        fun failed() { flight = null; queued.clear() }

        /** what an answer to [op] means: the frame to tap next (a resend, or the queued press), and whether it ended [ok] */
        class After(val next: Out?, val done: Boolean, val ok: Boolean, val why: String?)
        fun onAnswer(op: Int, ok: Boolean, why: String?, now: Long): After {
            val f = flight ?: return After(null, false, ok, why)
            if (f.op != op) return After(null, false, ok, why)       // (an answer to another frame: a stale one)
            if (!ok && why == "garbled" && f.tries < 2) {
                flight = Flight(f.op, f.taps, now, f.tries + 1, f.dur)
                return After(Out(f.op, f.taps, true), false, false, why)
            }
            flight = null
            return After(next(now), true, ok, why)
        }
        /** time passing: an unanswered frame is sent once more (only an op that ends the same run twice: [AGAIN_UNANSWERED]), then
         *  given up ([After.why] "lost") */
        fun tick(now: Long, waitMs: Long = WAIT_MS): After? {
            val f = flight ?: return null
            if (now - f.at < waitMs + f.dur) return null
            if (f.tries < 2 && f.op in AGAIN_UNANSWERED) {
                flight = Flight(f.op, f.taps, now, f.tries + 1, f.dur); return After(Out(f.op, f.taps, true), false, false, "lost") }
            flight = null
            return After(next(now), true, false, "lost")
        }
        private fun next(now: Long): Out? {
            while (queued.isNotEmpty()) {
                val (q, at) = queued.removeAt(0)
                if (now - at > WAIT_MS * 3) continue                       // a press waiting this long is stale: dropped
                flight = Flight(q.first, q.second, now, 1, q.third)
                return Out(q.first, q.second, false)
            }
            return null
        }
    }
    /** a frame's taps' own time on the "pnav" row ([gapMs] apart, a repeated cell spaced: [TapSpacing.offsets]), with the batch's rest */
    fun frameMs(taps: List<Int>, gapMs: Long = GAP_MS): Long =
        TapSpacing.offsets(taps.map { PolestarNav.ROW to it }, gapMs).lastOrNull()?.plus(TapSpacing.MIN_MS) ?: 0L
    /** the gap between a frame's taps (PolestarNav's, Skip's) */
    const val GAP_MS = 120L
    /** how long a frame waits for its answer (design: "the next press waits up to 3 s"), after its own taps' time */
    const val WAIT_MS = 3_000L
    /** the app says the tab is open again this often (the add-on forgets it after 60 s) */
    const val KEEP_MS = 20_000L
    /** fix/desk-click-switch: this many frames in a row with no answer hold the menu's own frames back ([PolestarMenu.quiet]) */
    const val QUIET_AFTER = 2
    /** the longest such wait */
    const val QUIET_MAX_MS = 120_000L
    /** a step's goals are asked when the selection rests this long (a read, not a game action) */
    const val REST_MS = 600L
    /** a step's goals described this long ago are asked again on rest */
    const val STEP_FRESH_MS = 15_000L
}
