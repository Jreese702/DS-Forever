package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/*
 * HOME: THE LOGIC, WITHOUT THE LOOK (home session). The page the bottom screen rests on when no game
 * window is open. The user decided the behaviour on 2026-09-28 (AGENT_LOG, the "Home:" entries); the
 * look comes from UI Art's build spec and lives in HomeView, not here.
 *
 *   REST          the controller bars on top (one thin row, or the diamonds: the user's toggle);
 *                 the left column split EQUALLY between the tracked quests and the chat; the
 *                 minimap fills the right.
 *   FULL SCREEN   a tap on the chat, on the minimap or on the bars opens that module full
 *                 screen, with no bar on top; the HOME button (showing B) goes back (user,
 *                 2026-09-29 15:08). A tap on the quest list outside its rows opens the Quests
 *                 ROOM (user, 09-28 20:35).
 *   AFTER A FIGHT the page comes back at once (user, 20:35: "At once"; HomeCombat's leave delay 0).
 *   COMBAT        entering combat, the bars take over (the row unfolds into the diamonds, the rest
 *                 shrinks to the bottom corners); leaving it, the page comes back as it was.
 *   FOCUS         a disc beside each tracked quest makes it the focused quest: one tap on the tap
 *                 pad's `focus` row (quests session). The disc of the focused quest does nothing.
 *
 * Pure Kotlin + org.json on purpose (no android.*), so tools/home_test runs this very file off the
 * device against the org.json test double. Times are passed in, never read from a clock here.
 */

/** what can open full screen ON Home; the quest list opens the Quests room instead (user, 20:35) */
enum class HomeModule { CHAT, MAP, BARS }

/**
 * How the controller bars stand on Home OUT of combat (user, 2026-09-29 15:15: "I really hate the homescreen layout when not in
 * combat, I want a toggle for the daimond button layout"): ROW = the thin row (the first design), DIAMONDS = the diamonds as in
 * the combat takeover. In combat it is always the takeover.
 */
enum class HomeRest { ROW, DIAMONDS }

/** what the page shows now; HomeView lays itself out from this and animates between two of them */
data class HomeScreen(val rest: HomeRest, val full: HomeModule?, val takeover: Boolean)

/**
 * The states and the taps that move between them. Every tap returns true when the screen changed.
 * The quests and the chat share the left column in an EQUAL split (user, 15:15: "the quests and chat should be an equal
 * split I think instead of expanding"; this replaces the trade of 09-28 18:55), so there is no swap any more.
 */
class HomeFlow {
    var rest = HomeRest.ROW
        private set
    var full: HomeModule? = null
        private set
    var takeover = false
        private set

    val screen get() = HomeScreen(rest, full, takeover)

    /** arriving on Home from another room: nothing open */
    fun land(): Boolean = close()

    /** the strip's HOME disc while Home already shows: back from a full-screen module */
    fun homeDisc(): Boolean = close()

    /** the chat was tapped: it opens full screen. (The quest list opens the Quests ROOM: the view's business.) */
    fun tapChat(): Boolean = open(HomeModule.CHAT)
    fun tapMap(): Boolean = open(HomeModule.MAP)
    fun tapBars(): Boolean = open(HomeModule.BARS)

    /** back from a full-screen module */
    fun close(): Boolean {
        if (takeover || full == null) return false
        full = null
        return true
    }

    /** the toggle between the thin row and the diamonds out of combat (a setting; where it lives is the user's to choose) */
    fun setRest(r: HomeRest): Boolean {
        if (r == rest) return false
        rest = r
        return true
    }

    /**
     * The combat takeover on or off, from [HomeCombat.shown]. It covers whatever was on screen and
     * gives it back unchanged, a full-screen module included. While it shows, the page's taps do
     * nothing: the layout is fixed until the fight is over.
     */
    fun combat(on: Boolean): Boolean {
        if (on == takeover) return false
        takeover = on
        return true
    }

    private fun open(m: HomeModule): Boolean {
        if (takeover || full != null) return false
        full = m
        return true
    }
}

/**
 * IS THE PLAYER IN COMBAT, for the takeover. Read-only, from documents the app already receives
 * (checked 2026-09-28, AGENT_LOG "Home: read-only check"):
 *   - quests doc `cb`   = UnitAffectingCombat("player"), sent on PLAYER_REGEN_* after a 0.25 s coalesce;
 *   - full doc `actionbar.hudS.c` = InCombatLockdown(), read at least a frame after the event.
 * NEVER `hub.combat` or the legacy `actionbar.combat`: both are read inside PLAYER_REGEN_DISABLED,
 * before the lockdown starts, so they miss the start of a fight for up to 15 s. Never `hudS.aa`
 * (auto-attack).
 *
 * LATEST WINS, not OR: an OR would hold the takeover until the slowest source says false.
 * ONLY CURRENT DOCUMENTS count (InputGate's verdict): at start LogTail replays hours of backlog,
 * and an old `cb = true` in it must not start a takeover.
 * STALE = OUT: every source is re-sent on the 15 s heartbeat, so a takeover with no current word
 * for [staleMs] lapses; the page must never be stuck behind the diamonds because the log stalled.
 * [leaveDelayMs] keeps the takeover up a little after the fight, so it does not bounce between
 * pulls (the user has not ruled on this; 0 = none).
 */
class HomeCombat(
    private val staleMs: Long = 20_000L,
    private val leaveDelayMs: Long = 0L,
) {
    private var raw = false
    private var heardAt = -1L
    private var offAt = -1L

    /** a current or replayed document said [inCombat] at [now] */
    fun note(inCombat: Boolean, current: Boolean, now: Long) {
        if (!current) return
        // the leave delay starts only for a fight that was still SHOWING: after a stale lapse the
        // page is already back, and the game's "over" must not bring the diamonds back for a moment
        if (raw && !inCombat && heardAt >= 0 && now - heardAt < staleMs) offAt = now
        raw = inCombat
        heardAt = now
    }

    /** the takeover should show at [now] */
    fun shown(now: Long): Boolean {
        if (heardAt < 0 || now - heardAt >= staleMs) return false
        if (raw) return true
        return offAt >= 0 && now - offAt < leaveDelayMs
    }

    companion object {
        /** the quests doc's word on combat; null when it says nothing (a secret value is dropped) */
        fun fromQuests(q: JSONObject): Boolean? = if (q.has("cb")) q.optBoolean("cb") else null

        /** a `full` envelope's word, from the controller bars' state part only */
        fun fromFull(msg: JSONObject): Boolean? {
            val s = msg.optJSONObject("actionbar")?.optJSONObject("hudS") ?: return null
            return if (s.has("c")) s.optBoolean("c") else null
        }
    }
}

/**
 * THE BIG CONTROLLER'S TRIGGER. It grows while the player's AUTO-ATTACK runs (user, 2026-09-29 16:46: "based on when I have
 * my start attack going, that way I can press B to make it minimize"): `aa` (the melee Attack, PLAYER_ENTER/LEAVE_COMBAT) or
 * `ar` (an auto-repeat: Auto Shot, Shoot, a wand), the controller bars' state part (cast session); AND while in COMBAT (17:17:
 * "entering combat should also make it expand, as before"): the quests doc's `cb` and the state part's `c`, latest wins.
 * A B press [minimize]s it back to the rest size until a NEW START of either (an auto-attack starting, a fight starting)
 * or until both are over. Minimized while the fight is on, the small controller wears a RED reminder ([red]; 17:17: "pressing
 * B and making it minimize while in Combat, should make the area around the controller have a Red Highlight until exiting
 * combat"). Each word counts only from a current document; quiet for [staleMs] = off.
 */
class HomeGrow(staleMs: Long = 20_000L) {
    private val attack = HomeCombat(staleMs)
    private val fight = HomeCombat(staleMs)
    private var attackWas = false                   // the last word of each, to see a START (false -> true)
    private var fightWas = false
    private var minimized = false

    /** a document said the auto-attack is [on] at [now] */
    fun noteAttack(on: Boolean, current: Boolean, now: Long) {
        if (!current) return
        if (on && !attackWas) minimized = false     // an auto-attack STARTS (a new target): the controller grows again
        attackWas = on
        attack.note(on, current, now)
    }

    /** a document said the player is in combat ([on]) at [now] */
    fun noteFight(on: Boolean, current: Boolean, now: Long) {
        if (!current) return
        if (on && !fightWas) minimized = false      // a fight STARTS
        fightWas = on
        fight.note(on, current, now)
    }

    private fun on(now: Long) = attack.shown(now) || fight.shown(now)

    /** B while the big controller shows: shrink it until the next start. False when there was nothing to shrink. */
    fun minimize(now: Long): Boolean {
        if (minimized || !on(now)) return false
        minimized = true
        return true
    }

    /** the big controller should show at [now] */
    fun shown(now: Long): Boolean {
        val on = on(now)
        if (!on) minimized = false                  // nothing runs any more: nothing stays minimized
        return on && !minimized
    }

    /** the red reminder around the small controller: B shrank it and the fight is still on */
    fun red(now: Long): Boolean = minimized && fight.shown(now)

    /** the fight word alone (quests cb / hudS.c, current, not stale): B's give-back of A / X / Y is for out of a fight only */
    fun fighting(now: Long): Boolean = fight.shown(now)

    companion object {
        /** a `full` envelope's word on the auto-attack, from the controller bars' state part; null when it says nothing */
        fun fromFull(msg: JSONObject): Boolean? {
            val s = msg.optJSONObject("actionbar")?.optJSONObject("hudS") ?: return null
            if (!s.has("aa") && !s.has("ar")) return null
            return s.optBoolean("aa", false) || s.optBoolean("ar", false)
        }
    }
}

/**
 * ONE AURA of the player's, as the character session's `auras` document (a1) sends it. A field the game hides (in combat, or
 * under the game's aura lock) arrives as the kernel's SECRET sentinel and is null here: [hidden] then says the name is not
 * known, and Home draws a "?" for it.
 */
data class HomeAura(
    val id: Int,
    val name: String?,
    val icon: Int?,
    val stacks: Int,
    val spellId: Int?,
    /** seconds; 0 = no end */
    val duration: Double,
    /** seconds left when the document was sent; null = no end, or hidden */
    val left: Double?,
    /** Magic / Curse / Disease / Poison (the game colours a debuff's border by it) */
    val dispel: String?,
    val tip: List<String>,
    /** the aura's index for its filter (out of combat only): what a secure cancel would act on */
    val index: Int?,
    /** a weapon enchant (oil, poison, stone), shown first among the buffs */
    val enchant: Boolean = false,
    /** its column on the pad's `aurapick` row (character's a1 doc); null = not removable from here */
    val slot: Int? = null,
) {
    val hidden get() = name == null && !enchant
}

/** what a Remove press may do: send the PICK tap for [slot], or say why not */
sealed class AuraTap {
    data class Send(val slot: Int) : AuraTap()
    data class Refused(val why: String) : AuraTap()
}

/** what an `auraack` asks of Home: tap the (now armed) cancel cell, show a refusal, or nothing */
sealed class AuraAck {
    object Cancel : AuraAck()
    data class Note(val text: String) : AuraAck()
    object Idle : AuraAck()
}

/**
 * THE PLAYER'S BUFFS AND DEBUFFS for Home's small map (user, 17:30: buffs top-left, debuffs bottom-left, each in a faded
 * button that opens a list over the small map). Latest document wins; a replayed one only fills an empty model. [locked] =
 * the game hides what they are now (combat). Time left counts down from when the document came.
 * IN COMBAT the buttons and the list show THE LAST LIST BEFORE THE FIGHT, GREYED ([shownBuffs], [greyed]; user 17:49: "The last
 * list before the fight, greyed out"). REMOVING a buff is out of combat only (17:49): [remove] -> the pick + cancel taps.
 */
class HomeAuras(private val staleMs: Long = 40_000L, private val pendingMs: Long = 8_000L) {
    var buffs: List<HomeAura> = emptyList(); private set
    var debuffs: List<HomeAura> = emptyList(); private set
    var locked = false; private set
    /** the pad (rows `aurapick` / `auracancel`) as the auras doc publishes it */
    var pad: JSONObject? = null; private set
    private var openBuffs: List<HomeAura> = emptyList()      // the last lists seen while NOT locked
    private var openDebuffs: List<HomeAura> = emptyList()
    private var openAt = -1L
    private var heardAt = -1L
    private var pendingId: Int? = null
    private var pendingAt = 0L
    private var picked = false

    /** what the buttons and the list show: the live lists, or in combat the last ones before the fight */
    val shownBuffs get() = if (locked) openBuffs else buffs
    val shownDebuffs get() = if (locked) openDebuffs else debuffs
    /** the shown lists are the old ones from before the fight */
    val greyed get() = locked

    /** true when something a button or the list shows changed */
    fun update(msg: JSONObject, current: Boolean, now: Long): Boolean {
        if (!current && heardAt >= 0) return false
        val gameNow = msg.optDouble("now", Double.NaN)
        val e = parse(msg.optJSONArray("e"), gameNow, enchant = true)
        val b = e + parse(msg.optJSONArray("b"), gameNow, enchant = false)
        val d = parse(msg.optJSONArray("d"), gameNow, enchant = false)
        val lock = msg.optBoolean("lock", false)
        msg.optJSONObject("pad")?.let { pad = it }
        heardAt = now
        if (!lock) { openBuffs = b; openDebuffs = d; openAt = now }
        // a buff being removed that is gone now: done
        if (pendingId != null && b.none { it.id == pendingId }) pendingId = null
        if (b == buffs && d == debuffs && lock == locked) return false
        buffs = b; debuffs = d; locked = lock
        return true
    }

    /**
     * The player pressed Remove on buff [id]. Out of combat only, a buff (never a debuff: the game has no cancel for them; never a
     * weapon enchant), with a published pad and a slot. On Send the caller taps `aurapick` [AuraTap.Send.slot] (no action: it ARMS
     * the cancel cell for 5 s); the cancel tap follows only on the pick's ok ([ack] -> [AuraAck.Cancel]), character's protocol.
     */
    fun remove(id: Int, live: Boolean, now: Long): AuraTap {
        if (!live || heardAt < 0) return AuraTap.Refused("The game is not connected")
        if (locked) return AuraTap.Refused("Out of combat only")
        if (pending(now) != null) return AuraTap.Refused("Still removing the last one")
        val a = buffs.firstOrNull { it.id == id } ?: return AuraTap.Refused("That buff is gone")
        if (a.enchant) return AuraTap.Refused("A weapon enchant cannot be removed here")
        val slot = a.slot ?: return AuraTap.Refused("The game has not published its tap pad")
        val rows = pad?.optJSONObject("rows")
        if (rows == null || !rows.has("aurapick") || !rows.has("auracancel")) return AuraTap.Refused("The game has not published its tap pad")
        pendingId = id; pendingAt = now; picked = false
        return AuraTap.Send(slot)
    }

    /** the buff whose removal is waiting on the game, or null */
    fun pending(now: Long): Int? = pendingId?.takeIf { now - pendingAt < pendingMs }

    /** the send failed on this side (the tap could not be made) */
    fun sendFailed() { pendingId = null; picked = false }

    /** true once when a removal waited too long (the caller says so) */
    fun expire(now: Long): Boolean {
        if (pendingId == null || now - pendingAt < pendingMs) return false
        pendingId = null; picked = false
        return true
    }

    /**
     * The addon's answer (`auraack`, character's a2). The pick's ok -> [AuraAck.Cancel]: tap `auracancel` 0 now (the cell stays
     * armed for `secs`, 5 s). A refusal of either -> its reason. The cancel's ok ends the wait (the next auras doc drops the buff).
     * An answer about another buff, or with nothing waiting, is ignored.
     */
    fun ack(msg: JSONObject, now: Long): AuraAck {
        if (msg.optString("type") != "auraack") return AuraAck.Idle
        val pend = pendingId ?: return AuraAck.Idle
        if (msg.has("id") && msg.optInt("id", -1) != pend) return AuraAck.Idle
        val ok = msg.optBoolean("ok", false)
        val why = msg.optString("why", "").ifEmpty { "The game refused" }
        return when (msg.optString("op")) {
            "pick" -> when {
                !ok -> { pendingId = null; picked = false; AuraAck.Note(why) }
                picked -> AuraAck.Idle                           // a repeated pick answer: the cancel is already on its way
                else -> { picked = true; pendingAt = now; AuraAck.Cancel }
            }
            "cancel" -> { pendingId = null; picked = false; if (ok) AuraAck.Idle else AuraAck.Note(why) }
            else -> AuraAck.Idle
        }
    }

    /** the document is recent enough to draw from */
    fun live(now: Long) = heardAt >= 0 && now - heardAt < staleMs

    /** seconds left of [a] at [now], or null (no end, hidden) */
    fun leftAt(a: HomeAura, now: Long): Double? = a.left?.let { maxOf(0.0, it - (now - heardAt) / 1000.0) }

    private fun parse(arr: JSONArray?, gameNow: Double, enchant: Boolean): List<HomeAura> {
        if (arr == null) return emptyList()
        val out = ArrayList<HomeAura>(arr.length())
        for (k in 0 until arr.length()) {
            val o = arr.optJSONObject(k) ?: continue
            val id = if (enchant) 100_000 + o.optInt("slot", k) else (field(o, "id") as? Number)?.toInt() ?: continue
            val exp = num(field(o, "exp"))
            val tip = o.optJSONArray("tip")?.let { t -> (0 until t.length()).mapNotNull { plain(t.optString(it)) } } ?: emptyList()
            out.add(HomeAura(
                id = id,
                name = plain(field(o, "n")),
                icon = num(field(o, "i"))?.toInt(),
                stacks = num(field(o, "s"))?.toInt() ?: 0,
                spellId = num(field(o, "sp"))?.toInt(),
                duration = num(field(o, "dur")) ?: 0.0,
                left = if (exp != null && !gameNow.isNaN()) maxOf(0.0, exp - gameNow) else null,
                dispel = plain(field(o, "t")),
                tip = tip,
                index = num(field(o, "x"))?.toInt(),
                enchant = enchant,
                slot = num(field(o, "slot"))?.toInt(),
            ))
        }
        return out
    }

    private companion object {
        const val SECRET = "\u0001"
        fun plain(v: Any?): String? = (v as? String)?.takeIf { it.isNotEmpty() && it != SECRET }
        fun num(v: Any?): Double? = (v as? Number)?.toDouble()
        /** a field's raw value, or null when absent (has + get: works on Android's org.json and the test double alike) */
        fun field(o: JSONObject, k: String): Any? = if (o.has(k)) o.get(k) else null
    }
}

/** the difficulty colour of a quest, by the game's rule; HomeView maps it to the game's colours */
enum class QuestTier { TRIVIAL, STANDARD, DIFFICULT, VERY_DIFFICULT, IMPOSSIBLE, UNKNOWN }

data class HomeObjective(val text: String, val finished: Boolean)

data class HomeQuest(
    val id: Int,
    val title: String,
    val level: Int?,
    val tier: QuestTier,
    val objectives: List<HomeObjective>,
    val ready: Boolean,
    val failed: Boolean,
    val tracked: Boolean,
    val focused: Boolean,
    val slot: Int?,
) {
    /** "[5] Vile Familiars" */
    val heading get() = if (level != null) "[$level] $title" else title

    /**
     * THE PROGRESS LINE (user, 19:30: the minimized quest shows its current objective progress at
     * minimum): the first unfinished objective as the game words it ("4/12 Vile Familiar slain"),
     * "Ready for turn-in" (the game's QUEST_WATCH_QUEST_READY), or "Failed".
     */
    val progress: String
        get() = when {
            failed -> "Failed"
            ready -> "Ready for turn-in"
            else -> objectives.firstOrNull { !it.finished }?.text ?: ""
        }

    /** open objectives after the first, for a "+N" */
    val moreOpen get() = (objectives.count { !it.finished } - 1).coerceAtLeast(0)
}

/** what a tap on a quest's focus disc does */
sealed class FocusTap {
    /** send ONE tap: column [slot] of the pad row `focus` */
    data class Send(val questId: Int, val slot: Int) : FocusTap()
    /** the disc of the quest that is already focused: nothing (user, via the tracking session, ~18:30) */
    object Already : FocusTap()
    /** nothing is sent; [why] is shown for a moment */
    data class Refused(val why: String) : FocusTap()
}

/**
 * THE QUESTS PART OF HOME, from the `quests` doc (WowCompanionQuests). The doc is the WHOLE quest
 * log; TRACKED = has "watch" (the game's objective tracker), FOCUSED = "focus" (super-tracking, the
 * single quest with the on-screen waypoint). They are different things (AGENT_LOG 09-27 11:xx).
 * The list keeps the log's order and does not move when the focus changes.
 */
class HomeQuests(private val pendingMs: Long = 8_000L, private val freshMs: Long = 25_000L) {
    var tracked: List<HomeQuest> = emptyList()
        private set
    /** the log's quests the tracker does not show (the map module's Untracked tabs track them) */
    private var untracked: List<HomeQuest> = emptyList()
    /** the quest the strip and the combat takeover show; see [pickShown] */
    var shown: HomeQuest? = null
        private set
    var inCombat = false
        private set
    private var focusId: Int? = null
    private var hasFocusRow = false
    private var pendingId: Int? = null
    private var pendingAt = 0L
    /** what the waiting tap asked for, as the questack names it: "track" (focus) or "unwatch" (untrack) */
    private var pendingOp = "track"
    private var currentAt = -1L

    /**
     * A quests doc arrived at [now]; [current] is InputGate's verdict (written by the game moments
     * ago). A focus tap uses THIS doc's pad row and column, so it needs a current quests doc of its
     * own: the game-wide live gate can be true on the core's stream while the quests addon is not
     * loaded and the last quests doc is a replayed one, whose row numbers may belong to another
     * room today.
     */
    fun update(q: JSONObject, current: Boolean = false, now: Long = 0L) {
        if (current) currentAt = now
        val plv = if (q.has("plv")) q.optInt("plv") else null
        val triv = if (q.has("trivRange")) q.optInt("trivRange") else 5
        val all = ArrayList<HomeQuest>()
        val list = q.optJSONArray("list")
        if (list != null) for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            all.add(parse(o, plv, triv))
        }
        tracked = all.filter { it.tracked }
        untracked = all.filter { !it.tracked }
        focusId = all.firstOrNull { it.focused }?.id ?: (if (q.has("focus")) q.optInt("focus") else null)
        shown = pickShown(all)
        inCombat = q.optBoolean("cb")
        // Quests.lua q5 folded its rows (qpick + qact); QuestPad answers for either pad (quests' QuestPad.kt)
        hasFocusRow = QuestPad.cells(q.optJSONObject("pad"), QuestPad.Act.FOCUS, 0) != null
        // the game did it: a focus that is the focus now, an untrack whose quest left the tracker
        if (pendingId != null && pendingOp == "track" && pendingId == focusId) pendingId = null
        if (pendingId != null && pendingOp == "unwatch" && tracked.none { it.id == pendingId }) pendingId = null
        if (pendingId != null && pendingOp == "watch" && tracked.any { it.id == pendingId }) pendingId = null
        if (pendingId != null && pendingOp == "untrack" && focusId != pendingId) pendingId = null
    }

    /**
     * The focused quest, tracked or not. With no focus, the first tracked quest.
     * OPEN QUESTION for the user (not asked yet): what the strip shows when nothing is focused.
     */
    private fun pickShown(all: List<HomeQuest>): HomeQuest? =
        all.firstOrNull { it.focused } ?: all.firstOrNull { it.id == focusId } ?: tracked.firstOrNull()

    /** the app has no word from the game: it must not send; [live] comes from GameInput */
    fun tapDisc(questId: Int, live: Boolean, now: Long): FocusTap {
        if (questId == focusId) return FocusTap.Already
        val q = tracked.firstOrNull { it.id == questId } ?: return FocusTap.Refused("Not in your tracker any more")
        if (!live) return FocusTap.Refused("Waiting for the game")
        if (currentAt < 0 || now - currentAt >= freshMs) return FocusTap.Refused("Waiting for the game's quest list")
        if (inCombat) return FocusTap.Refused("In combat")
        if (pending(now) != null) return FocusTap.Refused("Waiting for the game's answer")
        if (!hasFocusRow) return FocusTap.Refused("Focus is not on the tap pad yet")
        val slot = q.slot ?: return FocusTap.Refused("This quest has no tap column")
        pendingId = questId; pendingAt = now; pendingOp = "track"
        return FocusTap.Send(questId, slot)
    }

    /**
     * UNTRACK from the Map room's quest list (X; map session, the user's quest-list feedback 2026-09-30): the game's objective
     * tracker drops the quest (QuestPad UNWATCH; the quest stays in the log). The same gates as a focus; one tap waits at a time.
     */
    fun tapUntrack(questId: Int, live: Boolean, now: Long): FocusTap {
        val q = tracked.firstOrNull { it.id == questId } ?: return FocusTap.Refused("Not in your tracker any more")
        if (!live) return FocusTap.Refused("Waiting for the game")
        if (currentAt < 0 || now - currentAt >= freshMs) return FocusTap.Refused("Waiting for the game's quest list")
        if (inCombat) return FocusTap.Refused("In combat")
        if (pending(now) != null) return FocusTap.Refused("Waiting for the game's answer")
        val slot = q.slot ?: return FocusTap.Refused("This quest has no tap column")
        pendingId = questId; pendingAt = now; pendingOp = "unwatch"
        return FocusTap.Send(questId, slot)
    }

    /** the quest of the log (tracked or not) with id [questId] */
    fun inLog(questId: Int): HomeQuest? = tracked.firstOrNull { it.id == questId } ?: untracked.firstOrNull { it.id == questId }

    /** how many quests the game's tracker shows now */
    val trackedCount: Int get() = tracked.size

    /**
     * ONE ACTION ON ANY QUEST OF THE LOG (the map module's Untracked rows and their panel, the user 2026-10-16: A track and focus,
     * Share, Abandon): [op] is the questack that answers ("track", "share", "arm"). The same gates as every quest tap.
     */
    fun tapAny(questId: Int, op: String, live: Boolean, now: Long): FocusTap {
        val q = inLog(questId) ?: return FocusTap.Refused("Not in your quest log any more")
        if (!live) return FocusTap.Refused("Waiting for the game")
        if (currentAt < 0 || now - currentAt >= freshMs) return FocusTap.Refused("Waiting for the game's quest list")
        if (inCombat) return FocusTap.Refused("In combat")
        if (pending(now) != null) return FocusTap.Refused("Waiting for the game's answer")
        val slot = q.slot ?: return FocusTap.Refused("This quest has no tap column")
        pendingId = questId; pendingAt = now; pendingOp = op
        return FocusTap.Send(questId, slot)
    }

    /** Abandon's second tap (CONFIRM drops what ARM named, Quests.lua): it answers "abandon" */
    fun tapConfirm(questId: Int, live: Boolean, now: Long): FocusTap {
        if (!live) return FocusTap.Refused("Waiting for the game")
        if (inCombat) return FocusTap.Refused("In combat")
        if (pending(now) != null) return FocusTap.Refused("Waiting for the game's answer")
        pendingId = questId; pendingAt = now; pendingOp = "abandon"
        return FocusTap.Send(questId, 0)
    }

    /**
     * TRACK from the map module's Untracked tabs (A on an untracked quest; the user, 2026-10-16): the game's objective tracker takes
     * the quest (QuestPad WATCH). The same gates as an untrack; one tap waits at a time.
     */
    fun tapWatch(questId: Int, live: Boolean, now: Long): FocusTap {
        if (tracked.any { it.id == questId }) return FocusTap.Already
        val q = untracked.firstOrNull { it.id == questId } ?: return FocusTap.Refused("Not in your quest log any more")
        if (!live) return FocusTap.Refused("Waiting for the game")
        if (currentAt < 0 || now - currentAt >= freshMs) return FocusTap.Refused("Waiting for the game's quest list")
        if (inCombat) return FocusTap.Refused("In combat")
        if (pending(now) != null) return FocusTap.Refused("Waiting for the game's answer")
        val slot = q.slot ?: return FocusTap.Refused("This quest has no tap column")
        pendingId = questId; pendingAt = now; pendingOp = "watch"
        return FocusTap.Send(questId, slot)
    }

    /**
     * UNFOCUS from the Map room (A on the quest that has the focus; Quests.lua q9: it clears the focus only while that quest still
     * has it, replying op "untrack"). The quest must be the focused one now; the same gates; one tap waits at a time.
     */
    fun tapUnfocus(questId: Int, live: Boolean, now: Long): FocusTap {
        if (questId != focusId) return FocusTap.Refused("That quest is not the focused one")
        if (!live) return FocusTap.Refused("Waiting for the game")
        if (currentAt < 0 || now - currentAt >= freshMs) return FocusTap.Refused("Waiting for the game's quest list")
        if (inCombat) return FocusTap.Refused("In combat")
        if (pending(now) != null) return FocusTap.Refused("Waiting for the game's answer")
        val slot = tracked.firstOrNull { it.id == questId }?.slot ?: return FocusTap.Refused("This quest has no tap column")
        pendingId = questId; pendingAt = now; pendingOp = "untrack"
        return FocusTap.Send(questId, slot)
    }

    /**
     * A `questack`. Only ours: the op of the tap that waits ("track" or "unwatch") for the quest we asked for (the Quests room's
     * acks and our own share the route). Returns null when it was not ours, else the note to show ("" = ok).
     */
    fun ack(msg: JSONObject, now: Long): String? {
        if (msg.optString("op") != pendingOp) return null
        val id = if (msg.has("id")) msg.optInt("id") else null
        // an answer after the wait ran out is nobody's: an id-less one must not land on a newer tap
        if (pending(now) == null || (id != null && id != pendingId)) return null
        pendingId = null
        val failed = when (pendingOp) {
            "unwatch" -> "The game did not untrack it"
            "watch" -> "The game did not track it"
            "share" -> "The game did not share it"
            "arm", "abandon" -> "The game did not abandon it"
            "untrack" -> "The game did not clear the focus"
            else -> "The game did not change the focus"
        }
        return if (msg.optBoolean("ok")) "" else msg.optString("why", "").ifEmpty { failed }
    }

    /** the tap could not be sent (no pad geometry, device busy): nothing is waiting for an answer */
    fun sendFailed() { pendingId = null }

    /** the quest whose focus tap is waiting for the game's answer, while the wait lasts */
    fun pending(now: Long): Int? = if (pendingId != null && now - pendingAt < pendingMs) pendingId else null

    /** the wait ran out with no answer: true once, so the view can say so ("No answer from the game") */
    fun expire(now: Long): Boolean {
        if (pendingId == null || now - pendingAt < pendingMs) return false
        pendingId = null
        return true
    }

    companion object {
        /** the game's rule (difficultyutil / GetDifficultyColor), as the Quests room already applies it */
        fun tier(level: Int?, plv: Int?, trivRange: Int, trivial: Boolean): QuestTier {
            if (trivial) return QuestTier.TRIVIAL
            if (level == null || plv == null) return QuestTier.UNKNOWN
            val d = level - plv
            return when {
                d >= 5 -> QuestTier.IMPOSSIBLE
                d >= 3 -> QuestTier.VERY_DIFFICULT
                d >= -2 -> QuestTier.DIFFICULT
                -d <= trivRange -> QuestTier.STANDARD
                else -> QuestTier.TRIVIAL
            }
        }

        private fun parse(o: JSONObject, plv: Int?, triv: Int): HomeQuest {
            val lv = if (o.has("lv")) o.optInt("lv") else null
            val objs = ArrayList<HomeObjective>()
            o.optJSONArray("obj")?.let { a ->
                for (k in 0 until a.length()) {
                    val ob = a.optJSONObject(k) ?: continue
                    // a text the addon could not read (secret, threw) arrives as no `t`: the numbers
                    // still do, as the Quests room shows them ("have/need")
                    val t = ob.optString("t", "").ifEmpty {
                        if (ob.has("need")) "${ob.optInt("have")}/${ob.optInt("need")}" else ""
                    }
                    objs.add(HomeObjective(t, ob.optBoolean("f")))
                }
            }
            return HomeQuest(
                id = o.optInt("id"),
                title = o.optString("title", ""),
                level = lv,
                tier = tier(lv, plv, triv, o.optBoolean("trivial")),
                objectives = objs,
                ready = o.optBoolean("ready") || o.optBoolean("done"),
                failed = o.optBoolean("failed"),
                tracked = o.has("watch"),
                focused = o.optBoolean("focus"),
                slot = if (o.has("slot")) o.optInt("slot") else null,
            )
        }
    }
}

/** one chat line, with the KEYS its colour comes from (HomeView maps them; Theme is android-only) */
data class HomeChatLine(
    val ch: String, val who: String, val text: String, val chan: String, val idx: Int,
    val cls: String, val style: String, val out: Boolean, val me: Boolean,
    /** b126 (the user: "the different link colors should also show in the home module"): each link's name and its game colour as
     *  hex ("rrggbb"), in order (Chat.lua chat16's `lc`, the same data the Chat room colours with) */
    val lc: List<Pair<String, String>> = emptyList(),
)

/**
 * THE CHAT PART OF HOME: its own short tail of the `chat` doc, so Home never reaches into ChatView.
 * Chat.lua re-sends its whole ring (up to 60 lines) with every document and starts the ring again
 * at every /reload with a new session `s`; lines are appended by (s, i) exactly as ChatView does,
 * so a /reload's empty ring does not blank the line Home shows.
 */
class HomeChatTail(private val keep: Int = 40) {
    private val lines = ArrayList<HomeChatLine>()
    private var lastS = 0L
    private var lastI = 0
    private var legacyN = -1

    val all: List<HomeChatLine> get() = lines
    val newest: HomeChatLine? get() = lines.lastOrNull()
    fun last(n: Int): List<HomeChatLine> = lines.takeLast(n)

    /** the inner "chat" object; returns true when a line was added */
    fun update(chat: JSONObject): Boolean {
        val a = chat.optJSONArray("lines") ?: return false
        val s = chat.optLong("s", 0L)
        var added = false
        if (s == 0L) {
            // an addon from before line numbers: its document IS the log
            val n = chat.optInt("n", -1)
            if (n == legacyN) return false
            legacyN = n
            lines.clear()
            for (k in 0 until a.length()) a.optJSONObject(k)?.let { lines.add(line(it)); added = true }
            lastS = 0L; lastI = 0
        } else {
            for (k in 0 until a.length()) {
                val o = a.optJSONObject(k) ?: continue
                val i = o.optInt("i", 0)
                if (s == lastS && i <= lastI) continue      // the ring again: already have it
                lastS = s; lastI = i
                lines.add(line(o)); added = true
            }
        }
        while (lines.size > keep) lines.removeAt(0)
        return added
    }

    private fun line(o: JSONObject) = HomeChatLine(
        ch = o.optString("ch", "other"),
        // the addon's sentinels are control characters; a name that starts with one is not a name
        who = o.optString("who", "").let { if (it.isNotEmpty() && it[0].code > 3) it else "" },
        text = o.optString("text", ""),
        chan = o.optString("chan", ""),
        idx = o.optInt("idx", 0),
        cls = o.optString("cls", ""),
        style = o.optString("style", ""),
        out = o.optString("dir", "") == "out",
        me = o.optBoolean("me", false),
        lc = o.optJSONArray("lc")?.let { a ->
            (0 until a.length()).mapNotNull { k ->
                a.optJSONObject(k)?.let { l -> l.optString("c").takeIf { it.length == 6 }?.let { l.optString("n") to it } }
            }
        } ?: emptyList(),
    )
}

/**
 * THE HOME DISC'S RED B IN EVERY ROOM (the user 2026-10-02: "when swapping around rooms the B button like flashes like its resetting or
 * something ... they should all just stay red with the b button until I return home"). A room-to-room switch passes through a moment
 * with no holder (one room lets go, the next takes; ~125 ms measured 09:15:38), and the disc used to follow only the capture's `bnext`,
 * so it flashed blue. Now: while ANY room is on screen the disc shows B; on Home it follows Home's own state (bnext, a full-screen
 * module, the buff list) as before.
 */
object HomeDisc {
    fun showsB(inRoom: Boolean, homeSays: Boolean): Boolean = inRoom || homeSays
}

/**
 * B = BACK TO HOME (user, 2026-09-28 23:12: "Pressing be to leave the cursor should take me back to the home by
 * default for everything"). The interact session's shared capture counts every hand-back of the controller in
 * each `interact` document: `rel = { n, by, who }` (absent until the first hand-back since the addon loaded; `n`
 * counts up by one; `by` = "B" | "closed" | "combat" | "mode" | "npc" | "guest"). Home goes to the front on a NEW
 * `n` with `by == "B"`, read from a CURRENT document only, so the heartbeat's re-sent documents and the backlog
 * replayed at app start never throw the player Home. The first current document only sets the baseline; a
 * document without `rel` is a baseline of 0 (so the first B after a /reload counts); a smaller `n` is a reload.
 */
class HomeBack {
    private var seen = -1

    /**
     * A FIGHT LET GO OF A ROOM (user, 2026-10-01 21:2x: "When I go into combat in a room the hom button just changes blue and I get
     * stuck in there. Controller inputs still do my default keybinds but I can't see any of it"): when the capture lets go at a
     * fight's start (`by == "combat"`, the v7 release - the room's hold is not kept), the room would sit on screen while every press is
     * the game's. The capture id it let go (`rel.who`), set by the last [note] for a NEW hand-back by "combat"; null otherwise.
     * MainActivity shows Home when that id is the room on screen: going Home "engages the combat transition" (the user's ruling).
     */
    var combatWho: String? = null
        private set

    /** the capture id a NEW release by "B" named (rel.who), set by the last [note] that returned true; null otherwise */
    var bWho: String? = null
        private set

    /** an `interact` document arrived; true = show Home now (B) */
    fun note(msg: JSONObject, current: Boolean): Boolean {
        combatWho = null
        bWho = null
        if (!current) return false
        val rel = msg.optJSONObject("rel")
        val n = rel?.optInt("n", 0) ?: 0
        val before = seen
        seen = n
        if (before < 0 || n <= before) return false
        if (rel?.optString("by") == "combat") combatWho = rel.optString("who", "").ifEmpty { null }
        if (rel?.optString("by") == "B") bWho = rel.optString("who", "").ifEmpty { null }
        return rel?.optString("by") == "B"
    }

    companion object {
        /**
         * NOT THE PLAYER'S B (Social's touch check 2026-10-02 09:28:48: leaving the room by the Home disc, its release went out as "B"
         * - the free cell's secure click ran on the tap's press, before the room's own-close mark, so the window's close looked like
         * B): a release by "B" naming a room THE APP ITSELF LEFT within [windowMs] is that leave's echo, not the player's B, and must
         * not pull the screen Home from the room the player picked. [left] = the capture ids of the room the app last left, [leftAtMs]
         * when (uptime ms).
         */
        fun isOwnLeave(who: String?, left: Set<String>, leftAtMs: Long, nowMs: Long, windowMs: Long = 1000L): Boolean =
            who != null && who in left && nowMs - leftAtMs in 0..windowMs

        /**
         * a B release of a WHEEL's guest (the user on 1.0.9-test18: "The b animation on the radicals plays and then plays again when its at
         * the bottom of the screen"): B on the item / spell / pause wheel frees "radial" in the game (Quests q17), and the release came back
         * here as "B went Home", so the bar's Home disc played its press AGAIN after the wheel's own close had played. The wheel's close is
         * the one animation: no bar press, no room change ("class" alike)
         */
        fun wheelB(who: String?): Boolean = who == "pad:radial" || who == "pad:class"

        /**
         * b94 (Spells' find, 23:33:56): the capture ids a move from room [was] to room [to] really LEFT. A move between rooms of the SAME
         * id (Spellbook -> Talents, both "spells": Book's "taken" shows the book face, then "page-talents" the Talents room) leaves
         * nothing, so the player's B right after it is not taken for that move's echo
         */
        fun leftOf(was: String, to: String): Set<String> = idsOf(was) - idsOf(to)

        /** the capture ids that hold the controller for room [roomName] (the inverse of [roomsOf]) */
        fun idsOf(roomName: String): Set<String> =
            listOf("bags", "spells", "character", "calendar", "professions", "groupfinder", "social", "map",
                "pad:settings", "pad:quests", "pad:map", "pad:chat").filter { roomName in roomsOf(it) }.toSet()

        /** the rooms (Room names) a capture id holds the controller for; empty = no room (Interact's "npc", Home's own, the bar) */
        fun roomsOf(who: String): Set<String> = when (who) {
            "bags" -> setOf("BAGS")
            "spells" -> setOf("SPELLBOOK", "TALENTS", "CONTROLLER")     // the Controller room is the same guest's page (b17 croom)
            "character" -> setOf("CHARACTER")
            "calendar" -> setOf("CALENDAR")
            "professions" -> setOf("PROFESSIONS")
            "groupfinder" -> setOf("GROUPFINDER")
            "social" -> setOf("SOCIAL")
            "pad:settings" -> setOf("SETTINGS")
            "pad:quests" -> setOf("QUESTS")
            "pad:map" -> setOf("MAP")
            "map" -> setOf("MAP")          // the Map room's own capture hold (Map v2, the game's world map behind it)
            "pad:chat" -> setOf("CHAT")
            else -> emptySet()
        }
    }
}

/**
 * THE B GLYPH ON THE HOME BUTTON (user, 2026-09-29 09:23: "change the little house in the home Icon to be the B button glyph
 * when pressing it would return home"). The interact session's documents say, one press ahead, what B does next: `bnext` =
 * "home" | "back" | absent (absent = nobody holds the controller; the house stays). Interact writes a document at once on a
 * take, a change and a dialog coming or going, so only CURRENT documents count, and a glyph with no word for [staleMs] goes
 * back to the house: a stale B must never promise Home.
 */
class HomeBGlyph(private val staleMs: Long = 20_000L) {
    private var home = false
    private var at = -1L
    private var mutedHolder: String? = null
    private var mutedUntil = 0L

    /** an `interact` document arrived at [now] */
    fun note(msg: JSONObject, current: Boolean, now: Long) {
        if (!current) return
        at = now
        // the room just left is still the holder (the game has not let go yet): its "home" is the B already pressed
        if (mutedHolder != null && now < mutedUntil && msg.optString("holder") == mutedHolder) { home = false; return }
        mutedHolder = null
        home = msg.optString("bnext") == "home"
    }

    /**
     * THE GLYPH GOES WITH THE PRESS (RC3, the user 2026-10-09: the B glyph went "after its already returned home"): a room left for
     * Home drops the B at once; documents still naming that room's [holder] say nothing for [MUTE_MS] (the release follows)
     */
    fun drop(now: Long, holder: String, muteMs: Long = MUTE_MS) {
        home = false
        mutedHolder = holder.takeIf { it.isNotEmpty() }; mutedUntil = now + muteMs
    }

    companion object {
        const val MUTE_MS = 1_500L
        /** test26: a closed wheel's guest (its free waits for the stick, up to RadialModel's STICK_BACK_MS): its late "home" says nothing */
        const val WHEEL_MUTE_MS = 5_000L
    }

    /** the Home button shows B at [now] */
    fun shown(now: Long) = home && at >= 0 && now - at < staleMs
}

/**
 * START ATTACK ON CAST'S PLAIN WORDS under Home's switch (Spells' PadMap m13 AttackState, Spells' wording): a change waiting for the
 * fight to end, and the buttons the add-on had to leave plain, each only when it is not zero.
 */
object HomeAttackWords {
    fun notes(s: PadMapModel.AttackState): List<String> = buildList {
        if (s.pending) add("Changes after the fight.")
        if (s.full > 0) add("${s.full} ${buttons(s.full)} stayed plain: all 30 Character Macros are in use.")
        if (s.user > 0) add("${s.user} ${buttons(s.user)} stayed plain: you have your own macro of that name.")
        if (s.refused > 0) add("The game refused ${s.refused}.")
    }

    private fun buttons(n: Int) = if (n == 1) "button" else "buttons"
}
