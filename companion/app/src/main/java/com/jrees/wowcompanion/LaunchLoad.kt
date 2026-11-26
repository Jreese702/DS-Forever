package com.jrees.wowcompanion

import java.util.concurrent.CopyOnWriteArrayList

/**
 * OUR LOADING SCREEN WHILE THE GAME LAUNCHES (the user, 2026-11-24: "When the game is launching instead of showing the game native
 * loading screen could we replace that with out own loading screen intul the game launches?"; picks: "Our swirl loading screen", "The
 * game's first real picture", "The swirl across both screens"). From Play until the game draws its first real picture the top screen
 * shows the start-up's swirl reveal (LaunchTopView over the game screen; the bottom keeps the same scene), with the gold status line of
 * each launch step; then the top fades out over the game. Pure (no Android: the JVM tests run it); times in ms on one clock.
 *
 * THE HAND-OVER SIGNAL ([frame]): the X server's Present requests (PresentExtension.presentPixmap: every frame a Vulkan / GL swapchain
 * shows, DXVK's included; Vortek's window updates too) while [watching]: a sparse, downscaled look at the presented picture's own pixels
 * ([LaunchPicture]) - [PICTURE_FRAMES] presented frames in a row with enough lit pixels = the first real picture (WoW's black frames
 * before its login screen are not). After the hand-over nothing is looked at any more ([watching] false: one volatile read a frame).
 *
 * FALLBACKS, never an endless swirl: the game window up but no picture for [PICTURE_TIMEOUT_MS] = the hand-over anyway (the game's own
 * screen, as before); no step and no progress for [BOOT_TIMEOUT_MS] before the window = the screen goes (whatever is under it shows);
 * the game ending, a launch error or the abort chord = [end] at once (the existing error path shows). While Browser login's page waits
 * for the player ([signInWait]) no timeout runs.
 */
object LaunchLoad {
    enum class Phase { IDLE, BOOTING, WAITING, FADING }

    @Volatile var phase = Phase.IDLE
        private set
    /** the status line's step, terse (the English: drawn through Tr) */
    @Volatile var line = START
        private set
    /** the step's progress 0..1, or < 0 = none (no bar) */
    @Volatile var progress = -1f
        private set
    @Volatile private var lastChangeAt = 0L
    @Volatile private var mappedAt = 0L
    @Volatile private var fadeAt = 0L
    @Volatile private var beganAt = 0L
    @Volatile private var signInWaiting = false
    /** presented frames in a row that look like a picture */
    private var inRow = 0
    /** presented frames whose pixels could not be read (no CPU mapping): since when, and whether any frame was readable */
    private var unreadableSince = 0L
    private var anyReadable = false
    /** why the last launch's screen went (the report, the tests) */
    @Volatile var lastEnd = ""
        private set

    /** the frames are looked at (the X server's Present hook asks this first: nothing else runs once it is false) */
    val watching: Boolean get() = phase == Phase.BOOTING || phase == Phase.WAITING

    /** our screen covers the top screen (also while it fades out) */
    fun covers(): Boolean = phase != Phase.IDLE

    /** the pad's presses are held back from the game (nothing of the game shows yet); releases always pass */
    fun holdsPad(): Boolean = watching

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun onChange(l: () -> Unit): () -> Unit { listeners.add(l); return { listeners.remove(l) } }   // its removal: HostEnds
    fun offChange(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { for (l in listeners) runCatching { l() } }

    /** what the launch log is told (LauncherHooks.launchEvent inside the launcher; null = nothing, the tests) */
    @Volatile var log: ((String) -> Unit)? = null

    /** Play reached the launch (the container's start): our screen from here; a launch already showing goes on */
    @Synchronized fun begin(now: Long) {
        if (watching) { lastChangeAt = now; return }
        phase = Phase.BOOTING; line = START; progress = -1f; lastChangeAt = now; beganAt = now; mappedAt = 0L; fadeAt = 0L
        inRow = 0; signInWaiting = false; lastEnd = ""; unreadableSince = 0L; anyReadable = false
        log?.invoke("launch screen: up")
        changed()
    }

    /** a launcher step's words (GameNative's splash texts, the pre-launch's messages): the line, terse */
    @Synchronized fun step(raw: String, now: Long) {
        if (!watching) return
        val t = terse(raw) ?: return
        if (t != line) { line = t; lastChangeAt = now; changed() }
    }

    /** the step's progress (0..1, < 0 = none) */
    @Synchronized fun progress(p: Float, now: Long) {
        if (!watching) return
        val v = if (p < 0f || p.isNaN()) -1f else p.coerceIn(0f, 1f)
        if (v != progress) { progress = v; lastChangeAt = now; changed() }
    }

    /** the game's window is up (XServerScreen's onMapWindow for the game): what is left is its first picture */
    @Synchronized fun windowMapped(now: Long) {
        if (!watching) return
        if (phase == Phase.BOOTING) { phase = Phase.WAITING; mappedAt = now; progress = -1f; line = OPENING; lastChangeAt = now; changed() }
    }

    /**
     * Browser login waits ([waiting]: its helper or its page; [page] = Blizzard's page shows on the bottom screen): no timeout while it
     * waits; the line says where to sign in
     */
    @Synchronized fun signInWait(waiting: Boolean, page: Boolean, now: Long) {
        if (!watching) return
        val l = if (page) SIGN_IN_BELOW else if (line == SIGN_IN_BELOW) (if (phase == Phase.WAITING) OPENING else LAUNCHING) else line
        if (waiting == signInWaiting && l == line) return
        if (signInWaiting && !waiting && mappedAt != 0L) mappedAt = now     // the picture's wait starts again once it is done
        signInWaiting = waiting; lastChangeAt = now; line = l
        changed()
    }

    /**
     * a presented frame (any thread: the X server's): [picture] = it has enough lit pixels ([LaunchPicture.lit]). [PICTURE_FRAMES] in
     * a row = the hand-over (true returned once)
     */
    @Synchronized fun frame(picture: Boolean, now: Long): Boolean {
        if (!watching) return false
        anyReadable = true
        if (!picture) { inRow = 0; return false }
        if (++inRow < PICTURE_FRAMES) return false
        handOver(now, "the game's first picture after ${(now - beganAt) / 1000} s")
        return true
    }

    /**
     * a presented frame whose pixels cannot be looked at (no CPU mapping of it): the game draws, we cannot tell what. Such frames for
     * [UNREADABLE_MS] with none readable in this launch = the hand-over (never [PICTURE_TIMEOUT_MS] over a game already drawing)
     */
    @Synchronized fun unreadable(now: Long): Boolean {
        if (!watching || anyReadable) return false
        if (unreadableSince == 0L) { unreadableSince = now; return false }
        if (now - unreadableSince < UNREADABLE_MS) return false
        handOver(now, "the game draws frames that cannot be looked at (${(now - beganAt) / 1000} s)")
        return true
    }

    /** the clock: the timeouts, the fade's end. Any thread (the screen's draw asks it every frame) */
    @Synchronized fun tick(now: Long) {
        when (phase) {
            Phase.IDLE -> {}
            Phase.BOOTING -> if (!signInWaiting && now - lastChangeAt >= BOOT_TIMEOUT_MS) end(now, "no launch step for ${BOOT_TIMEOUT_MS / 1000} s")
            Phase.WAITING -> if (!signInWaiting && now - maxOf(mappedAt, lastChangeAt) >= PICTURE_TIMEOUT_MS)
                handOver(now, "no picture ${PICTURE_TIMEOUT_MS / 1000} s after the game's window (the game's own screen)")
            Phase.FADING -> if (now - fadeAt >= FADE_MS) { phase = Phase.IDLE; changed() }
        }
    }

    private fun handOver(now: Long, why: String) {
        phase = Phase.FADING; fadeAt = now; inRow = 0; lastEnd = why
        log?.invoke("launch screen: hand-over ($why)")
        changed()
    }

    /** the launch is over without a picture (the game ended, an error, the abort chord, the game screen went): our screen goes now */
    @Synchronized fun end(now: Long, why: String) {
        if (phase == Phase.IDLE) return
        val was = phase
        phase = Phase.IDLE; inRow = 0; signInWaiting = false
        if (was != Phase.FADING) { lastEnd = why; log?.invoke("launch screen: gone ($why)") }
        changed()
    }

    /** the top's opacity: 1 until the hand-over, then down to 0 over [FADE_MS] (eased) */
    fun alpha(now: Long): Float = when (phase) {
        Phase.IDLE -> 0f
        Phase.FADING -> (1f - (now - fadeAt).toFloat() / FADE_MS).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        else -> 1f
    }

    /**
     * GameNative's step words -> the player's (terse: feedback_wow_ui_terse_text); null = keep the line (an unknown text never shows
     * the launcher's own words)
     */
    fun terse(raw: String): String? {
        val s = raw.trim().trimEnd('.', '…').lowercase()
        return when {
            s.isEmpty() -> null
            s.startsWith("booting") || s == "loading" || s == "initializing" -> START
            s.startsWith("downloading first-time") -> DOWNLOADING
            s.startsWith("installing") && s.contains("component") -> INSTALLING
            s.startsWith("preparing") && s.contains("base") -> PREPARING
            s.startsWith("migrating") -> UPDATING
            s.startsWith("launching") -> LAUNCHING
            else -> null
        }
    }

    /** the status line as drawn: [step] in the language in use, then one to three dots moving every half second (never in the words) */
    fun statusLine(step: String, t: Float): String = Tr.t(step) + ".".repeat(((t / 0.5f).toInt() % 3 + 3) % 3 + 1)

    const val START = "Starting the game"
    const val DOWNLOADING = "Downloading first-time files"
    const val INSTALLING = "Installing the game system"
    const val PREPARING = "Preparing the game system"
    const val UPDATING = "Updating the game system"
    const val LAUNCHING = "Launching the game"
    const val OPENING = "Opening the game"
    const val SIGN_IN_BELOW = "Sign in on the bottom screen"
    /** the abort chord, as GameNative's splash said it (terse) */
    const val ABORT_HINT = "Start + Select + L2 + R2 to cancel"

    const val PICTURE_FRAMES = 3
    const val PICTURE_TIMEOUT_MS = 90_000L
    const val UNREADABLE_MS = 8_000L
    const val BOOT_TIMEOUT_MS = 180_000L
    const val FADE_MS = 600L

    /** the tests: back to the start */
    fun reset() { phase = Phase.IDLE; line = START; progress = -1f; inRow = 0; signInWaiting = false; lastEnd = ""; listeners.clear(); log = null }
}

/**
 * IS IT A PICTURE YET (the launch's hand-over, 2026-11-24): a sparse look at a presented frame's pixels, 4 bytes a pixel (RGBA / BGRA;
 * the 4th byte, alpha or padding, is not read). [COLS] x [ROWS] points inset from the edges, moved a little each frame ([phase]: never
 * the same few bytes twice in a row); a point is lit when its brightest channel is over [LIT]; a picture when [SHARE] of the points are.
 * WoW's black (or near-black) frames before its login screen are not; the login screen, its fade-in's first frames included, is. About
 * 300 reads of the buffer, only until the hand-over.
 */
object LaunchPicture {
    const val COLS = 24
    const val ROWS = 14
    const val LIT = 24
    const val SHARE = 0.04f
    /** a window smaller than this is no game picture (a tooltip, a cursor-sized helper) */
    const val MIN_W = 320
    const val MIN_H = 200

    /** [buf] (absolute reads only: its position is not moved), [w] x [h] pixels, [stridePx] pixels a row */
    fun lit(buf: java.nio.ByteBuffer, w: Int, h: Int, stridePx: Int, phase: Int): Boolean {
        if (w < MIN_W || h < MIN_H || stridePx < w) return false
        val need = (stridePx.toLong() * (h - 1) + w) * 4
        if (buf.capacity() < need) return false
        val jx = (phase * 7) % 16; val jy = (phase * 5) % 16
        var lit = 0
        for (row in 0 until ROWS) {
            val y = ((h - 1) * (row + 0.5f) / ROWS).toInt().plus(jy - 8).coerceIn(0, h - 1)
            for (col in 0 until COLS) {
                val x = ((w - 1) * (col + 0.5f) / COLS).toInt().plus(jx - 8).coerceIn(0, w - 1)
                val i = (y * stridePx + x) * 4
                val a = buf.get(i).toInt() and 255; val b = buf.get(i + 1).toInt() and 255; val c = buf.get(i + 2).toInt() and 255
                if (maxOf(a, b, c) > LIT) lit++
            }
        }
        return lit >= SHARE * COLS * ROWS
    }
}

/**
 * THE ABORT CHORD, as GameNative's splash had it (kept, the brief: "the abort chord still works"): Start + Select (or Back) + L2 + R2
 * held together = [onAbort] once. The triggers come as keys or as their axes ([triggers]). [key] = true when the press is the chord's
 * (one of its keys pressed while any of them is down: it is not the game's).
 */
class LaunchChord(private val onAbort: () -> Unit) {
    private var start = false
    private var select = false
    private var l2 = false
    private var r2 = false
    private var aborted = false

    private fun check(): Boolean = (!aborted && start && select && l2 && r2).also { if (it) { aborted = true; onAbort() } }

    /** a key; false = not one of the chord's */
    fun key(code: Int, down: Boolean): Boolean {
        when (code) {
            android.view.KeyEvent.KEYCODE_BUTTON_START -> start = down
            android.view.KeyEvent.KEYCODE_BUTTON_SELECT, android.view.KeyEvent.KEYCODE_BACK -> select = down
            android.view.KeyEvent.KEYCODE_BUTTON_L2 -> l2 = down
            android.view.KeyEvent.KEYCODE_BUTTON_R2 -> r2 = down
            else -> return false
        }
        return check() || (down && (start || select || l2 || r2))
    }

    /** the analog triggers (0..1): held past [TRIGGER] counts as pressed */
    fun triggers(left: Float, right: Float): Boolean {
        l2 = left >= TRIGGER; r2 = right >= TRIGGER
        return check()
    }

    companion object { const val TRIGGER = 0.3f }
}
