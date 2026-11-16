package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * LOOT ROLLS, ANSWERED AT ONCE (loot-pop; the user, 2026-11-16: "I have also noticed that the loot roll window is delayed and buggy,
 * sometimes making me press the button twice to move on or even just taking like 2 second after accepting an input to move on").
 *
 * THE CAUSE (proven off-device: DialogViewRollsTest on the app's half, tools/test_loot_rolls.py on the add-on's):
 *  1. an answered roll stayed ON SCREEN until the add-on's next document dropped it - and the add-on listed it until the game's own
 *     CANCEL_LOOT_ROLL, a server round trip after the answer (Dialogs d33 read GetActiveLootRollIDs only), so the press looked lost and
 *     the next one landed on the same roll;
 *  2. every change of the roll set (the answered one going, the next one coming) LOCKED every press for 700 ms ("One moment": the box
 *     dialogs' SETTLE guard, made for a finger aimed at a dialog that just changed) and every tap locked the next for 500 ms (LOCKOUT) -
 *     the press made in that window did nothing and had to be made again;
 *  3. the game's bind-on-pickup confirm ("Looting X will bind it to you.") came as a separate box over everything, only once the add-on's
 *     document carried it, and its Okay fell in the same 700 ms lock;
 *  4. a new roll's first document had no cells (the add-on's dialogs looked a frame before Interact gave the roll its cells): a touch then
 *     was "The game gave this no tap cell".
 *
 * THE FIX, here (the app's half; the add-on's is Dialogs d34 / Interact i162): one press = one roll. The answer is taken OPTIMISTICALLY -
 * the roll leaves the screen at once and the next one shows - and CORRECTED by the game's word: a document written [REFUSE_MS] after the
 * answer that still offers the roll (and does not say the game asks first) brings it back with a note. No settle lock and no lockout for
 * rolls: only [GAP_MS] between two answers (a finger's bounce). A bind-on-pickup roll answered Need / Greed / Disenchant /
 * Transmogrification shows the GAME'S confirm on that roll at once (the game asks: the app never answers it for the player); its Okay /
 * Cancel are the game's pop-up's own cells, tapped the moment the pop-up is known - an Okay pressed before the add-on's document carried
 * the pop-up waits for it ([taps]) instead of being lost. A controller press out of a fight is the GAME'S first (its own roll frame and
 * pop-up have the focus): the app taps the cell only if a document [PAD_FALLBACK_MS] later still shows what the press answered.
 *
 * Pure (RollBookTest): the times are the game clock of the documents (`_gt`) and of the presses (DialogView.gameNow).
 */
class RollBook {

    /** one roll as the add-on lists it (Dialogs readRolls) */
    class Roll(val o: JSONObject) {
        val id = o.optInt("id", -1)
        val name: String = o.optString("name", "")
        val icon = o.optInt("icon", 0)
        val q = o.optInt("q", 1)
        val count = o.optInt("count", 1)
        val need = o.optBoolean("need")
        val greed = o.optBoolean("greed")
        val de = o.optBoolean("de")
        val transmog = o.optBoolean("transmog")
        val bop = o.optBoolean("bop")
        /** the game's own frame selects this one (its A / B / X act on it) */
        val sel = o.optBoolean("sel")
        /** the game asked first (Dialogs d34): the roll it asks about ("need", "greed", "de", "transmog") */
        val ask: String? = if (o.has("ask") && !o.isNull("ask")) o.optString("ask").ifEmpty { null } else null
        /** seconds left when the document was written, of [dur] */
        val left = o.optInt("left", 0)
        val dur = o.optInt("dur", 0)
        fun cell(kind: String): Int = o.optJSONObject("cells")?.optInt(kind, -1) ?: -1
        fun can(kind: String) = when (kind) { "pass" -> true; "need" -> need; "greed" -> greed; "de" -> de; "transmog" -> transmog; else -> false }
        /** X's roll in the game's own frame: Greed, else Transmogrification */
        val xKind: String get() = if (!greed && transmog) "transmog" else "greed"
    }

    /** the game's bind confirm on a roll: a CONFIRM_LOOT_ROLL dialog naming it (Dialogs d34's `roll`), its words and its two cells */
    class Ask(val rollId: Int, val text: String, val okCell: Int, val cancelCell: Int, val okLabel: String, val cancelLabel: String)

    /** what a shown roll is: open to answer, or showing the game's confirm */
    enum class Row { OPEN, CONFIRM }

    /** a tap the view makes now: [cell] on [row] ("isell" for a roll, "idlg" for the confirm's buttons) */
    data class Tap(val rollId: Int, val row: String, val cell: Int, val what: String)

    private enum class Stage { GONE, CONFIRM, CONFIRMED }
    private class Answer(val kind: String, var gt: Long, var stage: Stage, val gameFirst: Boolean) { var fellBack = false }
    private class Want(val rollId: Int, val ok: Boolean, val gt: Long, val gameFirst: Boolean) { var sentGt = -1L }

    /** the rolls of the last document, in its order */
    var rolls: List<Roll> = emptyList()
        private set
    private var asks: Map<Int, Ask> = emptyMap()
    private val answers = LinkedHashMap<Int, Answer>()
    private val wants = LinkedHashMap<Int, Want>()
    private val pending = ArrayList<Tap>()
    private var lastAnswerGt = Long.MIN_VALUE / 2

    /** the rolls on screen, in the document's order: the answered ones are gone, a confirming one shows its confirm */
    fun shown(): List<Roll> = rolls.filter { rowOf(it) != null }

    /** what [r] shows, or null when it is answered (gone from the screen) */
    fun rowOf(r: Roll): Row? {
        val a = answers[r.id]
        val w = wants[r.id]
        return when {
            a != null && (a.stage == Stage.GONE || a.stage == Stage.CONFIRMED) -> null
            a != null -> Row.CONFIRM
            w != null && w.ok -> null                                  // the game's confirm answered Okay
            w != null -> Row.OPEN                                      // ... Cancel: the roll is open again (the pop-up on its way out)
            r.ask != null || asks.containsKey(r.id) -> Row.CONFIRM     // the game asks (its own frame's press, or a document after a restart)
            else -> Row.OPEN
        }
    }

    /** the game's confirm on roll [id], once the add-on's document carries it */
    fun askOf(id: Int): Ask? = asks[id]

    /** the roll asked about on [id]: the game's word, else the answer that expects it */
    fun askedKind(id: Int): String? = rolls.firstOrNull { it.id == id }?.ask ?: answers[id]?.kind

    /** an answer may go now ([gt] the game clock): only [GAP_MS] after the last (a finger's bounce, never a lock) */
    fun mayAnswer(gt: Long): Boolean = gt - lastAnswerGt >= GAP_MS

    /**
     * The player answered roll [id] with [kind] at [gt]; [gameFirst] = a controller press the GAME answers itself (out of a fight: its own
     * roll frame has the focus). False = no such roll on screen, or not allowed. The roll leaves the screen at once, or - a bind-on-pickup
     * roll answered anything but Pass - shows the game's confirm, which the game is about to ask.
     */
    fun answer(id: Int, kind: String, gt: Long, gameFirst: Boolean = false): Boolean {
        val r = rolls.firstOrNull { it.id == id } ?: return false
        if (rowOf(r) != Row.OPEN || !r.can(kind)) return false
        val asks = r.bop && kind != "pass"
        answers[id] = Answer(kind, gt, if (asks) Stage.CONFIRM else Stage.GONE, gameFirst)
        wants.remove(id)
        lastAnswerGt = gt
        return true
    }

    /**
     * The player answered the game's confirm on roll [id] at [gt]: [ok] Okay / Cancel. Okay: the roll leaves; Cancel: it is open again.
     * The tap goes as soon as the game's pop-up is known ([taps]); [gameFirst]: only if a document [PAD_FALLBACK_MS] later still shows it.
     */
    fun confirm(id: Int, ok: Boolean, gt: Long, gameFirst: Boolean = false): Boolean {
        val r = rolls.firstOrNull { it.id == id } ?: return false
        if (rowOf(r) != Row.CONFIRM) return false
        if (ok) answers[id]?.stage = Stage.CONFIRMED else answers.remove(id)
        if (ok && answers[id] == null) answers[id] = Answer(askedKind(id) ?: "need", gt, Stage.CONFIRMED, gameFirst)
        wants[id] = Want(id, ok, gt, gameFirst)
        lastAnswerGt = gt
        queueTaps(Long.MIN_VALUE / 2, gt)
        return true
    }

    /**
     * A new document: its [list] of rolls and the confirms of its dialogs ([confirms], by roll), written at [docGt]. Returns the rolls
     * that CAME BACK (the game did not take the answer: a note). The taps it makes due are in [taps].
     */
    fun onDoc(list: List<Roll>, confirms: Map<Int, Ask>, docGt: Long): List<Int> {
        rolls = list
        asks = confirms
        val back = ArrayList<Int>()
        val it = answers.entries.iterator()
        while (it.hasNext()) {
            val (id, a) = it.next()
            val r = list.firstOrNull { x -> x.id == id }
            if (r == null) { it.remove(); continue }                                       // the game took it
            val asking = r.ask != null || confirms.containsKey(id)
            val w = wants[id]
            when {
                asking && a.stage == Stage.GONE -> a.stage = Stage.CONFIRM                  // the game asks after all
                asking && a.stage == Stage.CONFIRMED && w != null && w.sentGt >= 0 && docGt >= w.sentGt + REFUSE_MS -> {
                    a.stage = Stage.CONFIRM; wants.remove(id); back.add(id)                 // its Okay did not take
                }
                !asking && a.gameFirst && !a.fellBack && a.stage == Stage.GONE && docGt >= a.gt + PAD_FALLBACK_MS -> {
                    // the controller's press was the game's first and the game did not take it: the roll's own cell now, once
                    a.fellBack = true; a.gt = docGt
                    val cell = r.cell(a.kind)
                    if (cell >= 0) pending.add(Tap(id, "isell", cell, "roll ${a.kind} (the game did not take the controller's)"))
                }
                !asking && docGt >= (w?.gt ?: a.gt) + REFUSE_MS -> { it.remove(); back.add(id) }   // still offered, nothing asked: back
            }
        }
        val wi = wants.entries.iterator()
        while (wi.hasNext()) {
            val (id, w) = wi.next()
            val r = list.firstOrNull { x -> x.id == id }
            val asking = r != null && (r.ask != null || confirms.containsKey(id))
            when {
                r == null -> wi.remove()                                                     // the roll is over
                asking -> Unit                                                               // its pop-up up: the tap is due / on its way
                w.sentGt >= 0 -> wi.remove()                                                 // the pop-up gone after our tap: answered
                w.gameFirst && docGt >= w.gt + PAD_FALLBACK_MS -> wi.remove()                // the game answered its own pop-up
                docGt >= w.gt + WANT_WAIT_MS -> wi.remove()                                  // the game never asked
            }
        }
        queueTaps(docGt, docGt)
        return back
    }

    /** the taps due now (each handed out once): a confirm's Okay / Cancel, a roll the game did not take from the controller */
    fun taps(): List<Tap> { val t = ArrayList(pending); pending.clear(); return t }

    private fun queueTaps(docGt: Long, nowGt: Long) {
        for (w in wants.values) {
            if (w.sentGt >= 0) continue
            val a = asks[w.rollId] ?: continue                       // the game's pop-up not known yet: it waits for the document
            if (w.gameFirst && docGt < w.gt + PAD_FALLBACK_MS) continue
            val cell = if (w.ok) a.okCell else a.cancelCell
            if (cell < 0) continue
            w.sentGt = nowGt
            pending.add(Tap(w.rollId, "idlg", cell, if (w.ok) "confirm ok" else "confirm cancel"))
        }
    }

    /** a confirm press is waiting for the game's pop-up (the view may show it as pressed) */
    fun waiting(id: Int): Boolean = wants[id]?.let { it.sentGt < 0 } == true

    /** an answer whose tap could not go: the roll is open again */
    fun forget(id: Int) { answers.remove(id); wants.remove(id) }

    /** everything forgotten (no rolls any more) */
    fun clear() { rolls = emptyList(); asks = emptyMap(); answers.clear(); wants.clear(); pending.clear() }

    companion object {
        /** a document this much newer than the answer that still offers the roll = the game did not take it */
        const val REFUSE_MS = 1_500L
        /** a controller press the game answers first: its cell is tapped if a document this much newer still shows it */
        const val PAD_FALLBACK_MS = 400L
        /** an Okay / Cancel pressed before the game's pop-up was known waits this long for it */
        const val WANT_WAIT_MS = 3_000L
        /** two answers at least this far apart: a bounce, never a lock (was SETTLE 700 ms after any change + LOCKOUT 500 ms per tap) */
        const val GAP_MS = 120L

        /** the roll the game's own frame selects once [gone] leaves [ids] (GamepadGroupLootRollFrame.RemoveRoll: the one before, else the
         *  first); null when none is left */
        fun nextAfter(ids: List<Int>, gone: Int): Int? {
            val i = ids.indexOf(gone)
            val rest = ids.filter { it != gone }
            if (rest.isEmpty()) return null
            if (i < 0) return rest.first()
            return rest[(i - 1).coerceIn(0, rest.size - 1)]
        }

        /** the confirms of a dialogs document's list (Dialogs d34: CONFIRM_LOOT_ROLL with `roll`), by roll id */
        fun asksOf(list: org.json.JSONArray?): Map<Int, Ask> {
            val out = HashMap<Int, Ask>()
            if (list == null) return out
            for (n in 0 until list.length()) {
                val d = list.optJSONObject(n) ?: continue
                if (d.optString("which") != "CONFIRM_LOOT_ROLL" || !d.has("roll")) continue
                val id = d.optInt("roll", -1)
                if (id < 0) continue
                val cells = d.optJSONObject("cells")
                val ba = d.optJSONArray("buttons")
                fun label(k: Int, def: String): String {
                    if (ba != null) for (m in 0 until ba.length()) {
                        val b = ba.optJSONObject(m) ?: continue
                        if (b.optInt("k") == k) return b.optString("label", def).ifEmpty { def }
                    }
                    return def
                }
                out[id] = Ask(id, d.optString("text", ""), cells?.optInt("b1", -1) ?: -1, cells?.optInt("b2", -1) ?: -1,
                    label(1, "Okay"), label(2, "Cancel"))
            }
            return out
        }
    }
}
