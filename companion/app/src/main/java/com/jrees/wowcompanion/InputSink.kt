package com.jrees.wowcompanion

/**
 * WHERE THE GAME INPUT GOES WHEN DS FOREVER RUNS INSIDE THE LAUNCHER (the private fork, phase 3b; the user 2026-10-08: "(a) 3b: switch
 * taps & keys"). The app's own taps and keys are root `input -d 0 ...` commands, and every one of them goes through ONE place,
 * RootShell. Inside the launcher there is no root; the launcher's glue sets [sink] to its own X server (XServer.injectKeyPress /
 * injectPointer*, the path its own pad uses - measured 10-08 19:46: a held Ctrl+Shift+F11 and a plain K arrived as KEYS), and RootShell
 * hands each batch to [play] instead of a shell. Nothing else changes: the same gate, the same quiet pad, the same one-touch batches.
 *
 * Understood here: `input -d 0 tap X Y` (display pixels of display 0 -> a fraction of the game's screen), `input -d 0 keyevent N`
 * (an Android keycode -> the X keycode, pressed and released), `input -d 0 text "..."` (typed as real keys, Shift for capitals and
 * symbols; S3's typed chat) and `sleep S`. Anything else (the setup scripts) is refused, as a shell with no root would refuse it.
 * The sign-in ([signIn], S4) is typed straight from memory - never through a command line.
 */
object InputSink {
    interface Sink {
        /** one X keycode down or up on the game's screen */
        fun key(xKeycode: Int, down: Boolean)
        /** one left click at this fraction of the game's screen (0..1 each way) */
        fun tap(fx: Float, fy: Float)
        /** f-trackpad-keyboard: the game's mouse pointer moved by (dx, dy) screen pixels (the trackpad); default: nothing */
        fun moveBy(dx: Int, dy: Int) {}
        /** f-trackpad-keyboard: the left / right mouse button down / up where the pointer is; default: nothing */
        fun button(right: Boolean, down: Boolean) {}
    }

    // ---------------------------------------------------------------- f-trackpad-keyboard: the tool's ONLY ways into the game
    /** one worker for the tool's keys and pointer (never the UI thread; in order) */
    private val gameIo = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** [back] Backspaces, then [type] typed: ONLY GameTyping's allowed characters reach the game (letters, digits, space) */
    fun gameKeys(back: Int, type: String) {
        val s = sink ?: return
        val text = type.filter { GameTyping.allowed(it) }
        gameIo.execute {
            repeat(back.coerceIn(0, 200)) { s.key(GameTyping.X_BACKSPACE, true); s.key(GameTyping.X_BACKSPACE, false); Thread.sleep(CHAR_GAP_MS) }
            if (text.isNotEmpty()) typeText(text)
        }
    }

    /** Enter, pressed and let go (the game's text box takes the line) */
    fun gameEnter() {
        val s = sink ?: return
        gameIo.execute { s.key(GameTyping.X_ENTER, true); Thread.sleep(CHAR_GAP_MS); s.key(GameTyping.X_ENTER, false) }
    }

    /** the trackpad: the pointer moved */
    fun gameMove(dx: Int, dy: Int) {
        val s = sink ?: return
        gameIo.execute { s.moveBy(dx, dy) }
    }

    /** a click (left / right) where the pointer is */
    fun gameClick(right: Boolean) {
        val s = sink ?: return
        gameIo.execute { s.button(right, true); Thread.sleep(30); s.button(right, false) }
    }

    @Volatile var sink: Sink? = null
    /** display 0's physical size (the tap commands' pixels are TapBridge's, scaled to it) */
    @Volatile var displayW = 0
    @Volatile var displayH = 0

    private const val KEY_GAP_MS = 40L
    /**
     * a rest after every click: the root `input tap` it replaces cost ~60 ms, and the game takes one touch per frame (b61: two actions'
     * taps close together lost one). Without it two cells of one batch could land in the same frame (Chat measured the instant taps
     * against its repeat guard, 2026-10-08)
     */
    private const val TAP_REST_MS = 20L          // smoothness B (the user's pick): 50 -> 20 ms, about one frame
    /** a batch's TRAILING rest (TapBridge's 0.08 s) is capped at this inside the launcher (smoothness B; the root shell's cost is gone) */
    private const val TAIL_REST_MS = 30L

    /** Android keycodes the app sends -> X keycodes (the launcher's XKeycode ids: evdev + 8, US layout) */
    val ANDROID_TO_X: Map<Int, Int> = buildMap {
        put(7, 19); for (i in 1..9) put(7 + i, 9 + i)                         // 0, 1..9
        val letters = intArrayOf(38, 56, 54, 40, 26, 41, 42, 43, 31, 44, 45, 46, 58, 57, 32, 33, 24, 27, 39, 28, 30, 55, 25, 53, 29, 52)
        for (i in 0 until 26) put(29 + i, letters[i])                          // A..Z
        for (i in 0 until 10) put(131 + i, 67 + i)                             // F1..F10
        put(141, 95); put(142, 96)                                             // F11, F12
        put(111, 9); put(66, 36); put(61, 23); put(62, 65); put(67, 22)        // Esc, Enter, Tab, Space, Backspace
        put(69, 20); put(70, 21)                                               // -, =
        put(122, 110); put(123, 115)                                           // MOVE_HOME, MOVE_END
    }

    private const val SHIFT_L = 50
    private const val CTRL_L = 37
    /** never pressed by the key channel, whatever a document says (Windows / Wine system keys) */
    private val SYSTEM_KEYS = setOf("ESCAPE", "ESC", "TAB", "ENTER", "RETURN", "SPACE", "BACKSPACE", "ALT", "LALT", "RALT", "MENU", "APPS")
    private const val ALT_L = 64  // not used by the key channel any more (no Alt: 2026-10-09)
    /** the key channel's gap between X events (Quests' L1: ~10 ms, not [KEY_GAP_MS]) */
    private const val WCKEY_GAP_MS = 10L

    /**
     * THE KEY CHANNEL (L1, the user 2026-10-09 "L1 key channel": actions at the PRESS, not the release; Quests owns TapPad / TapBridge,
     * this line is Home's InputSink part): `wckeys F5 A ...` = Ctrl+Alt+Shift held once, each named key pressed and let go, then the
     * modifiers let go. The add-on binds CTRL-ALT-SHIFT-<key> (a row key, then a column key = ONE action). Names are WoW's key names:
     * F1..F12, A..Z, 0..9, - (MINUS), = (EQUALS), [ ], INSERT DELETE HOME END PAGEUP PAGEDOWN. X keycodes = evdev + 8, US layout.
     */
    fun keyNamed(name: String): Int? {
        val n = name.uppercase()
        if (n.length == 1 && n[0] in 'A'..'Z') return ANDROID_TO_X.getValue(29 + (n[0] - 'A'))
        if (n.length == 1 && n[0] in '0'..'9') return if (n[0] == '0') 19 else 9 + (n[0] - '0')
        if (n.startsWith("F") && n.length in 2..3) n.substring(1).toIntOrNull()?.let { f -> if (f in 1..10) return 66 + f; if (f == 11) return 95; if (f == 12) return 96 }
        return when (n) {
            "-", "MINUS" -> 20; "=", "EQUALS" -> 21; "[" -> 34; "]" -> 35
            "INSERT" -> 118; "DELETE" -> 119; "HOME" -> 110; "END" -> 115; "PAGEUP" -> 112; "PAGEDOWN" -> 117
            "UP" -> 111; "DOWN" -> 116; "LEFT" -> 113; "RIGHT" -> 114
            else -> null
        }
    }
    private const val CHAR_GAP_MS = 12L

    /** one typed character -> (X keycode, Shift), US layout (the launcher's own keyboard) */
    fun charKey(c: Char): Pair<Int, Boolean>? {
        val letters = "abcdefghijklmnopqrstuvwxyz"
        val li = letters.indexOf(c.lowercaseChar())
        if (li >= 0) return ANDROID_TO_X.getValue(29 + li) to c.isUpperCase()
        if (c in '1'..'9') return (9 + (c - '0')) to false
        if (c == '0') return 19 to false
        val plain = mapOf(' ' to 65, '-' to 20, '=' to 21, '[' to 34, ']' to 35, '\\' to 51, ';' to 47, '\'' to 48, ',' to 59, '.' to 60,
            '/' to 61, '`' to 49)
        plain[c]?.let { return it to false }
        val shifted = mapOf('_' to 20, '+' to 21, '{' to 34, '}' to 35, '|' to 51, ':' to 47, '"' to 48, '<' to 59, '>' to 60, '?' to 61,
            '~' to 49, '!' to 10, '@' to 11, '#' to 12, '$' to 13, '%' to 14, '^' to 15, '&' to 16, '*' to 17, '(' to 18, ')' to 19)
        shifted[c]?.let { return it to true }
        return null
    }

    /** types [text] as keys (false = a character that has no key here; nothing of the rest is sent then) */
    fun typeText(text: String): Boolean {
        val s = sink ?: return false
        val keys = text.map { charKey(it) ?: return false }
        for ((k, shift) in keys) {
            if (shift) s.key(SHIFT_L, true)
            s.key(k, true); s.key(k, false)
            if (shift) s.key(SHIFT_L, false)
            Thread.sleep(CHAR_GAP_MS)
        }
        return true
    }

    private fun keyTap(code: Int) { val s = sink ?: return; s.key(code, true); Thread.sleep(KEY_GAP_MS); s.key(code, false) }
    private fun click(at: Pair<Float, Float>) { sink?.tap(at.first, at.second) }

    /**
     * DS FOREVER'S OWN SIGN-IN INSIDE THE LAUNCHER (S4; the user's login pick, no credential file): the same steps as HomeLogin.script
     * (each field: tap it, End, clear, type; then the Log In button), typed from memory through the X server. Null = done, else why not.
     */
    fun signIn(email: String, password: String, emailAt: Pair<Float, Float>, passwordAt: Pair<Float, Float>,
               logInAt: Pair<Float, Float>, clearKeys: Int): String? {
        if (sink == null) return "The launcher's input is not ready"
        if ((email + password).any { charKey(it) == null }) return "The login has a character the launcher cannot type"
        for ((at, text) in listOf(emailAt to email, passwordAt to password)) {
            click(at); Thread.sleep(120)
            keyTap(115)                                                  // End
            repeat(clearKeys) { keyTap(22) }                             // Backspace
            if (!typeText(text)) return "The login could not be typed"
            Thread.sleep(80)
        }
        click(logInAt)
        return null
    }

    /** one RootShell batch, played on the launcher's X server; the same answer shape as a shell's */
    fun play(script: String): RootShell.Result {
        val s = sink ?: return RootShell.Result(-1, emptyList())
        val lines = script.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for ((i, line) in lines.withIndex()) {
            val f = line.split(Regex("\\s+"))
            when {
                f[0] == "sleep" -> {
                    val ms = ((f.getOrNull(1)?.toDoubleOrNull() ?: 0.0) * 1000).toLong().coerceIn(0L, 2_000L)
                    // only TapBridge's own tail (<= 80 ms) is cut; a longer deliberate trailing wait plays in full (Quests)
                    Thread.sleep(if (i == lines.lastIndex && ms <= 80L) TAIL_REST_MS else ms)
                }
                f.size == 6 && f[0] == "input" && f[1] == "-d" && f[3] == "tap" -> {
                    val x = f[4].toFloatOrNull(); val y = f[5].toFloatOrNull()
                    if (x == null || y == null || displayW <= 0 || displayH <= 0) return refuse(line)
                    LatLog.mark("click", "$x,$y")
                    s.tap((x / displayW).coerceIn(0f, 1f), (y / displayH).coerceIn(0f, 1f))
                    Thread.sleep(TAP_REST_MS)
                }
                f.size >= 5 && f[0] == "input" && f[1] == "-d" && f[3] == "text" -> {
                    // GameInput.chat's shell word: "..." with backslash escapes and %s for spaces (or '...', '\'' for a quote)
                    val word = line.substringAfter(" text ").trim()
                    val inner = when {
                        word.length >= 2 && word.startsWith("\"") && word.endsWith("\"") -> word.substring(1, word.length - 1)
                            .replace(Regex("\\\\(.)"), "$1")
                        word.length >= 2 && word.startsWith("'") && word.endsWith("'") -> word.substring(1, word.length - 1).replace("'\\''", "'")
                        else -> word
                    }.replace("%s", " ")
                    if (!typeText(inner)) return refuse("input text (a character with no key)")
                }
                f.size >= 3 && f[0] == "wckeys" -> {
                    // `wckeys CTRL-SHIFT INSERT A` (Quests' pad10c alphabet). DEFENCE IN DEPTH (2026-10-09: Ctrl+Alt+Shift+F4 was ALT+F4 and
                    // closed the game at every world entry): the whole line is refused for an Alt, an F-key, a system key or any unknown name,
                    // so no document can ever make it press those
                    val mods = f[1].uppercase().split('-')
                    if (mods.any { it !in setOf("CTRL", "SHIFT") }) return refuse("$line (modifiers: CTRL / SHIFT only)")
                    val names = f.drop(2)
                    if (names.any { n -> n.uppercase().let { it.matches(Regex("F[0-9]{1,2}")) || it in SYSTEM_KEYS } }) return refuse("$line (a system key)")
                    val codes = names.map { keyNamed(it) ?: return refuse(line) }
                    val held = mods.map { if (it == "CTRL") CTRL_L else SHIFT_L }
                    LatLog.mark("keys", names.joinToString(" "))
                    for (m in held) s.key(m, true)
                    try {
                        for (k in codes) { Thread.sleep(WCKEY_GAP_MS); s.key(k, true); Thread.sleep(WCKEY_GAP_MS); s.key(k, false) }
                        Thread.sleep(WCKEY_GAP_MS)
                    } finally { for (m in held.asReversed()) s.key(m, false) }
                }
                f.size == 5 && f[0] == "input" && f[1] == "-d" && f[3] == "keyevent" -> {
                    val k = ANDROID_TO_X[f[4].toIntOrNull() ?: -1] ?: return refuse(line)
                    LatLog.mark("key", "$k")
                    s.key(k, true); Thread.sleep(KEY_GAP_MS); s.key(k, false)
                }
                else -> return refuse(line)
            }
        }
        return RootShell.Result(0, emptyList())
    }

    private fun refuse(line: String): RootShell.Result {
        android.util.Log.w("InputSink", "not sent (the launcher takes taps and keys only): $line")
        return RootShell.Result(1, listOf("unsupported: $line"))
    }
}
