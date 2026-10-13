package com.jrees.wowcompanion

/**
 * TYPED CHAT AND QUICK CHAT GO THROUGH THE ADD-ON (the user, 2026-10-07: "Send from the add-on, like quick chat"; it replaced the
 * 09-28 typed-chat route, whose injected letters made Android's keyboard app show itself over the companion). Chat.lua chat12 keeps
 * a BUFFER that this class fills one pad tap per character as the player types, and sends with ONE SendChatMessage inside the tap
 * on its Send cell (the hardware event). The cells are Chat.lua's (rows "chq" / "chr" / "chs", namespace "chat"):
 *   chq 0-11 presets, 12 delete, 13 clear, 14 / 15 the letters spell the whisper target / the message, 16-22 the type, 23 SEND,
 *   24-39 the characters 32-47; chr 0-39 the characters 48-87; chs 0-38 the characters 88-126 but "|" (chat13);
 *   chs 39 SHOWS the chat on the top screen (the add-on's own frame, chat13), chs 36 hides it.
 *
 * THE ADD-ON'S BUFFER IS THE TRUTH (Quests' condition: a tap can land on the sink, and a lost letter is silent). Every typed pick is
 * answered with the buffer's length + hash ([hash], the same sum in Lua), never the text. Letters are answered lazily (one flush
 * after a pause in typing), so this class only CHECKS before a send: it taps 15 ("report now"), and sends only when the answer
 * matches what the player wrote; otherwise it clears and types it all again (twice at most), then gives up with the text kept.
 *
 * A cell swallows a second press within 0.25 s, and taps queue, so a letter that may land too soon after its own last press
 * ("ll", and since the launcher's taps cost nothing also "lol", "that") goes as ONE spaced batch [spacer, letter]. The spacer is the
 * top-screen cell that matches what shows (39 while it shows, 36 while hidden), so it changes nothing.
 *
 * Pure Kotlin, tested off the device (tools/chat_test): [taps] hands cells to TapBridge (gap in ms between a batch's taps), [now]
 * is the clock, and the view feeds the add-on's answers to [heard].
 */
class ChatStream(
    private val taps: (cells: List<Pair<String, Int>>, gapMs: Long) -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val BACK = 12; const val CLEAR = 13; const val TGT = 14; const val BODY = 15; const val SEND = 23
        val TOP_ON = "chs" to 39
        val TOP_OFF = "chs" to 36
        /** how soon the top screen's state is asked for again when an answer says it is not what was asked */
        const val TOP_RETRY_MS = 2000L
        const val REPEAT_GAP_MS = 300L
        const val BATCH_GAP_MS = 150L
        /** TapBridge's rest after every batch: the least time between two batches' taps (S3: the launcher's taps take no time) */
        const val REST_MS = 80L
        /** how long a check (15) or a send (23) may go unanswered before the send is given up, the text kept */
        const val ANSWER_MS = 4000L
        const val MAX = 255
        const val TARGET_MAX = 48
        /** the types and their cells; WHISPER goes to the spelt target */
        val TYPES = linkedMapOf("SAY" to 16, "PARTY" to 17, "RAID" to 18, "GUILD" to 19, "YELL" to 20, "REPLY" to 21, "WHISPER" to 22,
            "CHANNEL" to 11)       // chat16: a joined channel, its NUMBER spelt as the target (setTarget("2"))

        /** the cell that types [ch], or null for a character chat cannot carry (printable ASCII only, and not "|": chat13) */
        fun cellOf(ch: Char): Pair<String, Int>? = when (ch.code) {
            in 32..47 -> "chq" to ch.code - 8
            in 48..87 -> "chr" to ch.code - 48
            124 -> null
            in 88..126 -> "chs" to ch.code - 88
            else -> null
        }

        /** Chat.lua's hashOf: h = (h * 31 + byte) mod 16777213 over the bytes */
        fun hash(s: String): Int {
            var h = 0L
            for (c in s) h = (h * 31 + c.code) % 16777213L
            return h.toInt()
        }
    }

    /** one answer of the add-on's: {"type":"chatt"} (typed) or {"type":"chatq"} (presets, types) */
    data class Heard(val kind: String, val op: String, val ok: Boolean, val why: String?, val ty: String,
                     val len: Int = -1, val h: Int = -1, val tlen: Int = -1, val th: Int = -1, val into: String = "",
                     val top: Boolean? = null)

    sealed class Event {
        object Sent : Event() { override fun toString() = "Sent" }
        data class Refused(val why: String) : Event()
        data class QuickSent(val k: Int) : Event()
    }

    var onEvent: (Event) -> Unit = {}

    // ---- what the add-on will hold once every tap so far has landed (the optimistic mirror)
    var body = ""; private set
    var target = ""; private set
    var type = "SAY"; private set
    /** the chat on the top screen (the add-on's frame) is to show */
    var top = false; private set
    private var topAt = 0L
    private var into = "body"
    /** the cells handed over last, newest last, each with the least time that comes before it (the queue may only add to it) */
    private val recent = ArrayList<Pair<Pair<String, Int>, Long>>()

    // ---- the add-on's last word on the typed buffer and on the type
    var heardType: String? = null; private set
    private var heardT: Heard? = null

    // ---- a send in progress
    private enum class Step { NONE, CHECK, SEND }
    private var step = Step.NONE
    private var stepAt = 0L
    private var tries = 0
    private var pendingQuick = -1        // a preset waiting for its type to be confirmed
    // 15s handed over and not answered yet: a check's answer is the one that answers the LAST 15 (a resync taps one of its own)
    private var pendingBody = 0
    private var quickAt = 0L
    val sending: Boolean get() = step != Step.NONE

    /**
     * Hands [cells] over, in order, clear of every cell's repeat guard (0.25 s). Only the least times are counted ([BATCH_GAP_MS]
     * inside a batch, [REST_MS] between batches; S3: the launcher's taps cost nothing more), since a queue may only add to them. A
     * cell less than [REPEAT_GAP_MS] after its own last press: inside a batch a spacer before it, when that is enough; else it goes
     * as its own batch [spacer, cell] with [REPEAT_GAP_MS] between.
     */
    private fun go(cells: List<Pair<String, Int>>): Boolean {
        if (cells.isEmpty()) return true
        val batch = ArrayList<Pair<String, Int>>()
        fun flush(): Boolean {
            if (batch.isEmpty()) return true
            val ok = taps(ArrayList(batch), if (batch.size > 1) BATCH_GAP_MS else 0L)
            batch.clear()
            return ok
        }
        fun handed(c: Pair<String, Int>, before: Long) { recent.add(c to before); if (recent.size > 8) recent.removeAt(0) }
        val spacer = if (top) TOP_ON else TOP_OFF
        for (c in cells) {
            val before = if (batch.isEmpty()) REST_MS else BATCH_GAP_MS
            val since = sinceOwn(c, before)
            when {
                since >= REPEAT_GAP_MS -> { batch.add(c); handed(c, before) }
                batch.isNotEmpty() && since + BATCH_GAP_MS >= REPEAT_GAP_MS -> {
                    batch.add(spacer); handed(spacer, before); batch.add(c); handed(c, BATCH_GAP_MS)
                }
                else -> {
                    if (!flush()) return false
                    if (!taps(listOf(spacer, c), REPEAT_GAP_MS)) return false
                    handed(spacer, REST_MS); handed(c, REPEAT_GAP_MS)
                }
            }
        }
        if (!flush()) return false
        pendingBody += cells.count { it == "chq" to BODY }
        return true
    }

    /** the least time between [c]'s last press and a press [before] ms after the newest one (enough = [REPEAT_GAP_MS]) */
    private fun sinceOwn(c: Pair<String, Int>, before: Long): Long {
        var t = before
        for (i in recent.indices.reversed()) {
            if (recent[i].first == c) return t
            t += recent[i].second
            if (t >= REPEAT_GAP_MS) return t
        }
        return REPEAT_GAP_MS
    }

    private fun letters(s: String): List<Pair<String, Int>> = s.mapNotNull { cellOf(it) }

    /** only what chat can carry, and no more than the game takes */
    fun clean(s: String, max: Int = MAX): String = s.filter { cellOf(it) != null }.take(max)

    /**
     * The message as the player has it now. Sends what differs: deletes back to the common start, then the new characters.
     * Returns false when a tap could not be handed over (the game not live, an add-on without the rows).
     */
    fun setBody(want0: String): Boolean {
        val want = clean(want0)
        if (want == body) return true
        val cells = ArrayList<Pair<String, Int>>()
        if (into != "body") { cells.add("chq" to BODY); into = "body" }
        val common = body.commonPrefixWith(want).length
        val deletes = body.length - common
        // a long way back is a clear and a retype when that is fewer taps
        if (deletes > 1 && want.length < deletes) {
            cells.add("chq" to CLEAR)
            cells.addAll(letters(want))
        } else {
            repeat(deletes) { cells.add("chq" to BACK) }
            cells.addAll(letters(want.substring(common)))
        }
        body = want
        return go(cells)
    }

    /** the whisper target ("" for none): spelt into the add-on's second buffer, then back to the message */
    fun setTarget(want0: String): Boolean {
        val want = clean(want0, TARGET_MAX)
        if (want == target) return true
        val cells = ArrayList<Pair<String, Int>>()
        cells.add("chq" to TGT)
        cells.add("chq" to CLEAR)
        cells.addAll(letters(want))
        cells.add("chq" to BODY)
        target = want; into = "body"
        return go(cells)
    }

    /** the chat on the top screen: the add-on's frame shows ([on]) or goes; one tap, no game action */
    fun setTop(on: Boolean): Boolean {
        if (on == top) return true
        top = on
        topAt = now()
        return go(listOf(if (on) TOP_ON else TOP_OFF))
    }

    /** the chat type (SAY .. WHISPER): one tap, no game action */
    fun setType(t: String): Boolean {
        val col = TYPES[t] ?: return false
        if (t == type && heardType == t) return true
        type = t
        return go(listOf("chq" to col))
    }

    /** everything again: the add-on's buffers cleared and refilled from the mirror (after a reload, or a lost tap) */
    fun resync(): Boolean {
        val cells = ArrayList<Pair<String, Int>>()
        cells.add("chq" to TGT); cells.add("chq" to CLEAR); cells.addAll(letters(target))
        cells.add("chq" to BODY); cells.add("chq" to CLEAR); cells.addAll(letters(body))
        cells.add("chq" to (TYPES[type] ?: 16))
        into = "body"
        return go(cells)
    }

    /** the add-on's buffers as the last answer has them, against the mirror */
    fun matches(h: Heard? = heardT): Boolean = h != null && h.len == body.length && h.h == hash(body) &&
        h.tlen == target.length && h.th == hash(target) && h.ty == type

    /**
     * SEND the message: check first (15, answered at once), then the ONE game action (23). False when there is nothing to send or a
     * send is already on its way. The outcome comes as [Event.Sent] or [Event.Refused].
     */
    fun send(): Boolean {
        if (sending || body.isBlank()) return false
        tries = 0
        return check()
    }

    private fun check(): Boolean {
        step = Step.CHECK; stepAt = now()
        if (!go(listOf("chq" to BODY))) { step = Step.NONE; onEvent(Event.Refused("The game is not ready")); return false }
        into = "body"
        return true
    }

    /** a preset (0-11) on the current type; the type is confirmed by the add-on first */
    fun quick(k: Int): Boolean {
        if (k !in ChatLinks.PRESETS.indices || sending) return false
        if (heardType == type) return go(listOf("chq" to k)).also { if (it) onEvent(Event.QuickSent(k)) }
        pendingQuick = k; quickAt = now()
        return go(listOf("chq" to (TYPES[type] ?: 16)))
    }

    /** an answer from the add-on (the view parses the JSON) */
    fun heard(h: Heard) {
        heardType = h.ty
        if (h.kind == "chatq") {
            if (h.op == "type" && pendingQuick >= 0 && h.ty == type) {
                val k = pendingQuick; pendingQuick = -1
                if (go(listOf("chq" to k))) onEvent(Event.QuickSent(k))
            } else if (h.op == "send" && !h.ok) onEvent(Event.Refused(h.why ?: "The game refused it"))
            return
        }
        heardT = h
        if (h.op == "body") pendingBody = maxOf(0, pendingBody - 1)
        // the top screen not as asked (a lost tap, the add-on's own hide): asked again, not more often than every 2 s
        if (h.top != null && h.top != top && now() - topAt > TOP_RETRY_MS) {
            topAt = now()
            go(listOf(if (top) TOP_ON else TOP_OFF))
        }
        when {
            step == Step.CHECK && h.op == "body" && pendingBody > 0 -> {}       // an earlier 15's answer: the check's is still coming
            step == Step.CHECK && h.op == "body" -> {
                if (matches(h)) {
                    step = Step.SEND; stepAt = now()
                    if (!go(listOf("chq" to SEND))) { step = Step.NONE; onEvent(Event.Refused("The game is not ready")) }
                } else if (++tries > 2) {
                    step = Step.NONE
                    onEvent(Event.Refused("The add-on did not get all the letters"))
                } else {
                    resync(); check()
                }
            }
            step == Step.SEND && h.op == "send" -> {
                step = Step.NONE
                if (h.ok) {
                    body = ""
                    onEvent(Event.Sent)
                } else onEvent(Event.Refused(h.why ?: "The game refused it"))
            }
        }
    }

    /** the view's clock tick: a check or send nobody answered is given up, the text kept */
    fun tick() {
        val t = now()
        if (step != Step.NONE && t - stepAt > ANSWER_MS) {
            step = Step.NONE
            pendingBody = 0             // whatever was lost stays lost: the next send starts counting afresh
            onEvent(Event.Refused("No answer from the add-on"))
        }
        if (pendingQuick >= 0 && t - quickAt > ANSWER_MS) {
            pendingQuick = -1
            onEvent(Event.Refused("No answer from the add-on"))
        }
    }

    /** a new add-on (a /reload, the game restarted): its buffers are empty, so the mirror is sent again */
    fun addonRestarted() {
        heardT = null; heardType = null; pendingBody = 0
        val b = body; val t = target
        body = ""; target = ""
        if (t.isNotEmpty()) setTarget(t)
        setBody(b)
    }
}
