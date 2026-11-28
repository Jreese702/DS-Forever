package com.jrees.wowcompanion

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * FRAME GENERATION WITH THE PLAYER'S OWN Lossless.dll (board f-frame-gen, 2026-10-13). The launcher already carries the lsfg-vk layer
 * and GameNative's native frame-generation pipeline; both need Lossless Scaling's Lossless.dll, which is commercial: it is NEVER
 * shipped, bundled, downloaded or put in git. The player picks their own copy (Settings > Frame generation), it is copied into this
 * app's PRIVATE storage ([dllFile]) and checked to be a PE DLL. The launcher reads [snapshot] at each game start
 * (LsfgVkManager.applyDsForever); Off (the default) or no DLL = the launch is exactly as without this feature.
 */
object FrameGen {
    const val DLL_NAME = "Lossless.dll"
    val MULTIPLIERS = listOf(2, 3, 4)
    const val FLOW_MIN = 0.25f
    const val FLOW_MAX = 1.0f
    const val FLOW_STEP = 0.05f
    const val FLOW_DEFAULT = 0.80f
    /** bigger than any Lossless.dll seen; a pick past this is refused before it is copied */
    private const val MAX_BYTES = 256L * 1024 * 1024
    private const val MIN_BYTES = 1024L

    data class Snapshot(val enabled: Boolean, val multiplier: Int, val flowScale: Float, val performance: Boolean)

    private var prefs: SharedPreferences? = null
    private var dir: File? = null
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** the picker activity closed (the bottom screen's window, hidden while it was up, comes back): set by the host, run once */
    @Volatile var onPickerClosed: (() -> Unit)? = null

    /** the last pick's result for the Settings row ("" = none this run) */
    @Volatile var lastPick: String = ""
        private set

    fun init(c: Context) {
        if (prefs == null) prefs = c.applicationContext.getSharedPreferences("framegen", Context.MODE_PRIVATE)
        if (dir == null) dir = File(c.applicationContext.filesDir, "framegen")
    }

    // ---- THE FRAME LIMIT (board n-fps-40; the user: "I want the 40 fps limiter to be set specifically in the game native settings,
    // not wow"). It IS the launcher's own limiter (GameNative's quick-menu FPS limit: XServerScreen paces the renderer, the Present
    // extension and the SHM path; with frame generation on, the lsfg-vk layer takes it as its base fps, so it limits the GAME's
    // frames: 40 x2 = 80 shown). It lives in the container's fpsLimiterEnabled / fpsLimiterTarget extras. ONCE, at the first game start
    // of 1.0.8, every container was set to 40 (the user's pick then: "Set once on update"). TEST BUILD 1529 (the user, 2026-11-24, after
    // players saw better performance in another launcher at 60: "60 by default (Recommended)" = "New default 60 for everyone who never
    // changed it; anyone who picked a value keeps it"): [LIMIT_DEFAULT] 60 is the new default, and ONCE ([limit60]) a container still at
    // the 1.0.8 value whose player never picked a limit goes to 60. DS Forever's Settings row shows the container's value
    // ([noteContainer], from the launcher at each start and from the quick menu) and a pick there is written into the container at the
    // next game start ([takeAsk]). No game CVar, nothing in the game's files.

    /**
     * Settings' choices: 30, 40, 60, Off (0); the quick menu may hold any other value, shown as it is. 30 is the base for frame
     * generation x2 on the Thor's 60 Hz panel (30 x2 = exactly 60 shown; 40 x2 = 80 can't be shown)
     */
    val LIMITS = listOf(30, 40, 60, 0)
    /** the one-time 1.0.8 limit (every container then); 1529 moves a container still at it to [LIMIT_DEFAULT] when nobody picked it */
    const val LIMIT_MIGRATED = 40
    /** 1529: the default frame limit (a new install's containers, and the 1.0.8 value nobody picked) */
    const val LIMIT_DEFAULT = 60
    /** the launcher's limit for a container that never set one (XServerScreen: on, 60) */
    const val LIMIT_ENGINE_DEFAULT = 60
    private const val KEY_LIMIT = "fpsLimitContainer"
    private const val KEY_ASK = "fpsLimitAsk"
    private const val KEY_MIGRATED = "fpsLimit108"
    /**
     * set by the 1.0.8 migration, removed by the player's own pick: in Settings ([askLimit], since 1.0.8) and in the launcher's quick
     * menu ([noteContainer] fromQuickMenu, since 1529). So "still there" = the player never picked a limit in Settings since 1.0.8
     */
    private const val KEY_NOTE = "fpsLimitNote"
    /** 1529: the 60 migration ran (or a new install started at 60) */
    private const val KEY_60 = "fpsLimit60"

    /**
     * THE ONE-TIME 1529 MIGRATION TO 60, pure (the off-device test): the limit to write into the container now, null = leave it.
     *  - [done] (it ran before): null, never again;
     *  - the 1.0.8 migration never ran ([migrated108] false: a new install, or one that never started a game): [LIMIT_DEFAULT], every
     *    container as 1.0.8 did (and 1.0.8's 40 never comes);
     *  - the container still holds 1.0.8's [LIMIT_MIGRATED] AND [noteLeft] (no Settings pick since 1.0.8): [LIMIT_DEFAULT];
     *  - anything else (a Settings pick of any value, 40 included; a quick-menu value other than 40; Off): null, the player's own.
     * The one case it cannot tell: a player who set 40 again in the launcher's quick menu (40 -> other -> 40) before 1529, with no
     * Settings pick: there is no record of it, so that container goes to 60 once (a pick in Settings or the quick menu sets it back).
     */
    fun limit60(done: Boolean, migrated108: Boolean, noteLeft: Boolean, containerLimit: Int): Int? = when {
        done -> null
        !migrated108 -> LIMIT_DEFAULT
        noteLeft && containerLimit == LIMIT_MIGRATED -> LIMIT_DEFAULT
        else -> null
    }

    /**
     * the migration on this install's flags (the launcher, at each game start): [current] = the container's limit now ([containerLimit]);
     * [write] puts a limit into the container. Marks it done (and 1.0.8's, so it never writes 40 after) whatever it decided; the 1.0.8
     * note goes (its words were about 40). true = it wrote now
     */
    fun migrateLimit(current: Int, write: (Int) -> Unit): Boolean {
        val p = prefs ?: return false
        if (p.getBoolean(KEY_60, false)) return false
        val v = limit60(false, p.getBoolean(KEY_MIGRATED, false), p.getBoolean(KEY_NOTE, false), current)
        if (v != null) write(v)
        p.edit().putBoolean(KEY_60, true).putBoolean(KEY_MIGRATED, true).remove(KEY_NOTE).apply()
        return v != null
    }

    /** the container's limit from its extras (absent = the launcher's own default: on, 60); 0 = Off */
    fun containerLimit(enabledExtra: String?, targetExtra: String?): Int {
        val on = enabledExtra?.trim()?.lowercase()?.let { it == "true" || it == "1" } ?: true
        if (!on) return 0
        return targetExtra?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: LIMIT_ENGINE_DEFAULT
    }

    /** the limit the running game uses, or will at the next start: a pending pick, else the container's (0 = Off) */
    val fpsLimit: Int
        get() {
            val ask = prefs?.getInt(KEY_ASK, -1) ?: -1
            if (ask in LIMITS) return ask
            val p = prefs ?: return LIMIT_ENGINE_DEFAULT
            val stored = p.getInt(KEY_LIMIT, LIMIT_ENGINE_DEFAULT).coerceAtLeast(0)
            // 1529: before the first game start of this build Settings already shows what that start will set (the 60 migration)
            return limit60(p.getBoolean(KEY_60, false), p.getBoolean(KEY_MIGRATED, false), p.getBoolean(KEY_NOTE, false), stored) ?: stored
        }

    /** Settings' pick: written into the container at the next game start */
    fun askLimit(v: Int) {
        if (v !in LIMITS) return
        prefs?.edit()?.remove(KEY_NOTE)?.apply()
        if (v == fpsLimit) { changed(); return }
        prefs?.edit()?.putInt(KEY_ASK, v)?.apply(); changed()
    }

    /** the pending pick, taken (the launcher, at a game start); null = none */
    fun takeAsk(): Int? {
        val ask = prefs?.getInt(KEY_ASK, -1) ?: -1
        prefs?.edit()?.remove(KEY_ASK)?.apply()
        return ask.takeIf { it in LIMITS }
    }

    /** the container's limit now (the launcher at each start; the quick menu when the player changes it there: that wins) */
    fun noteContainer(limit: Int, fromQuickMenu: Boolean = false) {
        val e = prefs?.edit() ?: return
        e.putInt(KEY_LIMIT, limit.coerceAtLeast(0))
        // the quick menu applies it to the running game; and it is the player's own pick (1529: the 60 migration leaves it alone)
        if (fromQuickMenu) { e.remove(KEY_ASK).remove(KEY_NOTE); launchedLimit = limit.coerceAtLeast(0) }
        e.apply(); changed()
    }

    fun limitLabel(v: Int): String = if (v <= 0) "Off" else "$v"

    /** the limit the running game started with (the launcher, at each game start); null = no game started by this run */
    @Volatile var launchedLimit: Int? = null
        private set

    /** a restart would change the running game's frame limit */
    fun limitNeedsRestart(launched: Int?, now: Int): Boolean = launched != null && launched != now

    fun onChange(l: () -> Unit): () -> Unit { listeners.add(l); return { listeners.remove(l) } }   // its removal: HostEnds
    private fun changed() { for (l in listeners.toList()) l() }
    /** Reset settings (ResetRun) removed saved values: the rows read them again */
    fun resetDone() = changed()

    /** the private copy (null before [init]); exists only after a good pick */
    fun dllFile(): File? = dir?.let { File(it, DLL_NAME) }
    fun hasDll(): Boolean = dllFile()?.isFile == true

    var enabled: Boolean
        get() = prefs?.getBoolean("enabled", false) ?: false
        set(v) { if (v != enabled) { prefs?.edit()?.putBoolean("enabled", v)?.apply(); changed() } }

    var multiplier: Int
        get() = sanitizeMultiplier(prefs?.getInt("multiplier", 2) ?: 2)
        set(v) { val s = sanitizeMultiplier(v); if (s != multiplier) { prefs?.edit()?.putInt("multiplier", s)?.apply(); changed() } }

    var flowScale: Float
        get() = sanitizeFlow(prefs?.getFloat("flowScale", FLOW_DEFAULT) ?: FLOW_DEFAULT)
        set(v) { val s = sanitizeFlow(v); if (s != flowScale) { prefs?.edit()?.putFloat("flowScale", s)?.apply(); changed() } }

    var performance: Boolean
        get() = prefs?.getBoolean("performance", true) ?: true
        set(v) { if (v != performance) { prefs?.edit()?.putBoolean("performance", v)?.apply(); changed() } }

    fun snapshot(): Snapshot = Snapshot(enabled, multiplier, flowScale, performance)

    // ---- what the running game started with (the user: "add a restart button if thats needed")

    /** what frame generation does for a game start: off (any values) or on with its values and the DLL copy's stamp */
    data class Applied(val on: Boolean, val multiplier: Int = 0, val flowScale: Float = 0f, val performance: Boolean = false, val dll: String? = null)

    val OFF = Applied(false)

    /** [s] as a game start applies it: off or no DLL = [OFF] (so changes while off never ask for a restart) */
    fun effective(s: Snapshot, dllStamp: String?): Applied =
        if (!s.enabled || dllStamp == null) OFF
        else Applied(true, sanitizeMultiplier(s.multiplier), sanitizeFlow(s.flowScale), s.performance, dllStamp)

    /** the private copy's identity (size and time): a new pick while on asks for a restart */
    fun dllStamp(): String? = dllFile()?.takeIf { it.isFile }?.let { "${it.length()}:${it.lastModified()}" }

    fun current(): Applied = effective(snapshot(), dllStamp())

    /** what the running game was started with (the launcher, at each game start); null = no game started by this run */
    @Volatile var launched: Applied? = null
        private set

    fun markLaunched() { launched = current(); launchedLimit = fpsLimit; changed() }

    /** a restart would change what frame generation does in the running game */
    fun needsRestart(launched: Applied?, now: Applied): Boolean = launched != null && launched != now

    /** the Frame generation section's rows: only the DLL row until one is set (the user: "hide the other settings until its chosen") */
    enum class Row { LIMIT, DLL, ON_OFF, MULTIPLIER, FLOW, PERFORMANCE, RESTART }

    fun rows(hasDll: Boolean, showRestart: Boolean): List<Row> = buildList {
        add(Row.DLL)
        if (hasDll) addAll(listOf(Row.ON_OFF, Row.MULTIPLIER, Row.FLOW, Row.PERFORMANCE))
        if (showRestart) add(Row.RESTART)
    }

    /** the "Restart the game" row: the game runs, a restart would change something, and no restart is already under way */
    fun showRestart(gameUp: Boolean, restarting: Boolean, launched: Applied?, now: Applied): Boolean =
        gameUp && !restarting && needsRestart(launched, now)

    fun sanitizeMultiplier(m: Int): Int = m.coerceIn(MULTIPLIERS.first(), MULTIPLIERS.last())

    /** 0.25..1.00 on the 0.05 grid (the stepper's values) */
    fun sanitizeFlow(f: Float): Float {
        if (f.isNaN()) return FLOW_DEFAULT
        val steps = Math.round((f.coerceIn(FLOW_MIN, FLOW_MAX) - FLOW_MIN) / FLOW_STEP)
        return (FLOW_MIN + steps * FLOW_STEP).coerceIn(FLOW_MIN, FLOW_MAX)
    }

    fun stepFlow(f: Float, dir: Int): Float = sanitizeFlow(f + dir * FLOW_STEP)

    fun flowLabel(f: Float): String = String.format(Locale.US, "%d%%", Math.round(sanitizeFlow(f) * 100f))

    /**
     * Whether [head] (a file's first bytes) is a Windows PE image flagged as a DLL: "MZ", e_lfanew at 0x3C, "PE\0\0" there, and the COFF
     * header's Characteristics with IMAGE_FILE_DLL (0x2000). null = it is; else why not, in the player's words.
     */
    fun peDllProblem(head: ByteArray): String? {
        if (head.size < 0x40 || head[0] != 'M'.code.toByte() || head[1] != 'Z'.code.toByte()) return "not a Windows DLL (no MZ header)"
        val peAt = le32(head, 0x3C)
        if (peAt < 0x40 || peAt.toLong() + 24 > head.size) return "not a Windows DLL (bad PE offset)"
        if (head[peAt] != 'P'.code.toByte() || head[peAt + 1] != 'E'.code.toByte() || head[peAt + 2] != 0.toByte() || head[peAt + 3] != 0.toByte())
            return "not a Windows DLL (no PE signature)"
        val characteristics = le16(head, peAt + 4 + 18)
        if (characteristics and 0x2000 == 0) return "a Windows program, not a DLL"
        return null
    }

    private fun le16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, at: Int): Int = le16(b, at) or (le16(b, at + 2) shl 16)

    /**
     * Copy the picked file into private storage when it is a PE DLL: a temp file first, checked, then renamed over the old copy (a bad
     * pick never replaces a good one). Runs on a background thread. Returns the row's message and keeps it in [lastPick].
     */
    fun importDll(c: Context, uri: Uri): String {
        init(c)
        val msg = runCatching {
            c.contentResolver.openInputStream(uri)?.use { importFrom(it) } ?: "Couldn't open the file"
        }.getOrElse { "Couldn't copy it: ${it.message}" }
        lastPick = msg
        changed()
        return msg
    }

    /** [importDll]'s copy and check, from a stream */
    fun importFrom(input: InputStream): String = dir?.let { importInto(it, input) } ?: "Not ready"

    /** the copy and check into [d] (pure file work: the off-device tests call it with a temp folder) */
    fun importInto(d: File, input: InputStream): String {
        d.mkdirs()
        val tmp = File(d, "$DLL_NAME.tmp")
        try {
            var total = 0L
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return "Too big for Lossless.dll"
                    out.write(buf, 0, n)
                }
            }
            if (total < MIN_BYTES) return "Too small for Lossless.dll"
            val head = ByteArray(minOf(total, 4096L).toInt())
            tmp.inputStream().use { var o = 0; while (o < head.size) { val n = it.read(head, o, head.size - o); if (n < 0) break; o += n } }
            peDllProblem(head)?.let { return "Not used: $it" }
            val dst = File(d, DLL_NAME)
            if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) return "Couldn't save it" }
            return "Lossless.dll saved (${total / 1024} KB)"
        } finally {
            tmp.delete()
        }
    }

    /** forget the private copy (Settings' "Remove"); frame generation stays off without it */
    fun removeDll() {
        dllFile()?.delete()
        lastPick = ""
        changed()
    }
}
