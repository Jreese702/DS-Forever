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
    private val listeners = ArrayList<() -> Unit>()

    /** the picker activity closed (the bottom screen's window, hidden while it was up, comes back): set by the host, run once */
    @Volatile var onPickerClosed: (() -> Unit)? = null

    /** the last pick's result for the Settings row ("" = none this run) */
    @Volatile var lastPick: String = ""
        private set

    fun init(c: Context) {
        if (prefs == null) prefs = c.applicationContext.getSharedPreferences("framegen", Context.MODE_PRIVATE)
        if (dir == null) dir = File(c.applicationContext.filesDir, "framegen")
    }

    fun onChange(l: () -> Unit) { listeners.add(l) }
    private fun changed() { for (l in listeners.toList()) l() }

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

    fun markLaunched() { launched = current(); changed() }

    /** a restart would change what frame generation does in the running game */
    fun needsRestart(launched: Applied?, now: Applied): Boolean = launched != null && launched != now

    /** the Frame generation section's rows: only the DLL row until one is set (the user: "hide the other settings until its chosen") */
    enum class Row { DLL, ON_OFF, MULTIPLIER, FLOW, PERFORMANCE, RESTART }

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
