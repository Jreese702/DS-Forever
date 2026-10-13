package com.jrees.wowcompanion

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * THE ADD-ON SETUP INSIDE THE LAUNCHER (S5, 2026-10-08; the user: "(a) Become 'DS Forever'"). The app did this with companion_setup.sh
 * through root; inside the one app there is no root and none is needed: the game folder is on shared storage (All-files access). The
 * launcher calls [run] before every launch (its prepareLaunch), with the game not running. The same rules as the script:
 *  - the add-on set ships in the app's assets (companion/manifest.txt + companion/addons/...);
 *  - it installs when a folder is MISSING, or updates when the game's set stamp is OLDER than the app's and files differ; an install
 *    whose files differ but carry no older stamp is left alone (the owners push ahead of the app between builds);
 *  - a backup first (/sdcard/WowCompanion/setup/launcher-<time>), every copied file checked, the old set put back on any failure;
 *  - Config.wtf: the required keys (developerLog, InputDeviceInterfaceStyle = 1) and the defaults only where the game has none.
 *  - NO container edits: the launcher's X input needs no touch mode.
 * Returns one line for the launcher's status / log.
 */
object LauncherSetup {
    private const val TAG = "WCSETUP"
    private const val STAMP = ".companion_set"
    private val REQ = listOf("developerLog", "InputDeviceInterfaceStyle")
    private val DEFAULTS = listOf("GamepadDisableTooltips" to "1", "GamePadTurnWithCamera" to "1")
    /**
     * THE FRAME LIMIT ON AT 40 FPS on a fresh install (the user 2026-10-09: "the game native should have frame limit on with 40 FPS by
     * default when installed"): the client's own pair (useMaxFPS + maxFPS, names from its strings), written only when NEITHER is in
     * Config.wtf, so a choice made in the game's options (the cap off, another number) is never overwritten. Local to the device's
     * Config.wtf. The background limit (useMaxFPSBk / maxFPSBk) stays the game's own default.
     */
    private val FPS_PAIR = listOf("useMaxFPS" to "1", "maxFPS" to "40")

    fun run(ctx: Context, flavorRoot: File): String = try { work(ctx, flavorRoot) } catch (t: Throwable) {
        Log.w(TAG, "launcher setup: ${t.message}"); "DS Forever setup failed: ${t.message}"
    }

    private fun work(ctx: Context, root: File): String {
        val manifest = ctx.assets.open("companion/manifest.txt").use { it.readBytes().toString(Charsets.UTF_8) }.lines()
        val appSet = manifest.firstOrNull { it.startsWith("set ") }?.removePrefix("set ")?.trim()?.toIntOrNull()
        val want = manifest.filter { it.length > 34 && !it.startsWith("set ") }.map { it.substring(0, 32) to it.substring(34).trim() }
        val addons = File(root, "Interface/AddOns")
        val folders = want.map { it.second.substringBefore('/') }.toSet()
        val missing = folders.any { !File(addons, it).isDirectory }
        val stamp = File(addons, "WowCompanion/$STAMP").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        val diff = want.count { (md5, rel) -> md5Of(File(addons, rel)) != md5 }
        val update = appSet != null && stamp != null && stamp < appSet && diff > 0
        var line = "DS Forever add-ons: set ${stamp ?: "none"}, app set $appSet, $diff differ"
        if (missing || update) {
            val backup = File("/sdcard/WowCompanion/setup/launcher-${System.currentTimeMillis()}").apply { mkdirs() }
            for (f in folders) File(addons, f).takeIf { it.isDirectory }?.copyRecursively(File(backup, "addons/$f"), overwrite = true)
            try {
                for ((_, rel) in want) {
                    val to = File(addons, rel); to.parentFile?.mkdirs()
                    ctx.assets.open("companion/addons/$rel").use { i -> to.outputStream().use { o -> i.copyTo(o) } }
                }
                // what the set no longer has goes (inside its own folders only)
                val keep = want.map { it.second }.toSet()
                for (f in folders) File(addons, f).walkTopDown().filter { it.isFile && it.name != STAMP }.forEach { file ->
                    if (file.relativeTo(addons).path.replace('\\', '/') !in keep) file.delete()
                }
                val bad = want.count { (md5, rel) -> md5Of(File(addons, rel)) != md5 }
                check(bad == 0) { "$bad copied files do not match the set" }
                appSet?.let { File(addons, "WowCompanion/$STAMP").writeText("$it\n") }
                line = "DS Forever add-ons ${if (missing) "installed" else "updated"}: set $appSet, ${want.size} files"
            } catch (t: Throwable) {
                // the old set back, as the script's restore
                for (f in folders) { File(addons, f).deleteRecursively(); File(backup, "addons/$f").takeIf { it.isDirectory }?.copyRecursively(File(addons, f)) }
                line = "DS Forever add-ons NOT installed (${t.message}); the old set is back"
            }
        }
        line += "; " + config(File(root, "WTF/Config.wtf"))
        Log.i(TAG, line)
        return line
    }

    /** the required keys set to 1 and the defaults where missing; the file's own line ending kept (the game writes CRLF) */
    private fun config(f: File): String {
        if (!f.isFile) return "no Config.wtf yet"
        val raw = f.readText()
        val nl = if (raw.contains("\r\n")) "\r\n" else "\n"
        val lines = raw.split(nl).filter { it.isNotEmpty() }.toMutableList()
        fun value(k: String) = lines.firstOrNull { it.startsWith("SET $k ") }?.substringAfter("\"")?.substringBeforeLast("\"")
        var n = 0
        for (k in REQ) if (value(k) != "1") { lines.removeAll { it.startsWith("SET $k ") }; lines += "SET $k \"1\""; n++ }
        for ((k, v) in DEFAULTS) if (value(k) == null) { lines += "SET $k \"$v\""; n++ }
        if (FPS_PAIR.none { (k, _) -> lines.any { it.startsWith("SET $k ", ignoreCase = true) } })
            for ((k, v) in FPS_PAIR) { lines += "SET $k \"$v\""; n++ }
        if (n > 0) f.writeText(lines.joinToString(nl) + nl)
        return "Config.wtf: $n set"
    }

    private fun md5Of(f: File): String? = if (!f.isFile) null else MessageDigest.getInstance("MD5").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
}
