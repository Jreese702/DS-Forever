package com.jrees.wowcompanion

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * Runs the self-setup's script (assets/companion/companion_setup.sh) as root, one step at a time, off the UI thread (Home,
 * 2026-10-02; the decisions are CompanionSetup's, the screen is SetupView).
 *
 * The APK carries the add-on set (tools/addon_set/snapshot.sh: every file read from GameNative's install, md5-checked) and the
 * script. Both are copied out to this app's own files the first time a new set is seen, so root can read them. `--mount-master`:
 * GameNative's folder is that app's private data, invisible from this app's own mount namespace (the LogTail lesson).
 *
 * The script only ever touches WowCompanion* folders and the listed Config.wtf lines, backs both up before it writes, and puts the
 * backup back itself when any step fails.
 */
class SetupRunner(private val ctx: Context) {
    private val worker = Executors.newSingleThreadExecutor()
    private val dir = File(ctx.filesDir, "companion")

    /** the app's add-on set id ("set 40"), or null when this build carries none (the setup then offers only the settings) */
    val appSet: Int? by lazy {
        try { ctx.assets.open("companion/manifest.txt").bufferedReader().use { CompanionSetup.setId(it.readLine()) } }
        catch (_: Throwable) { null }
    }

    /** WoW Forever as GameLocator found it on this device (null = the script's usual folder); its paths go to the script */
    @Volatile var wowForever: GameLocator.Found? = null

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** one script run: [mode] for [g] ([extra]: install's rec flag "0" / "1"); [done] gets every line it printed (on the worker thread) */
    fun run(mode: String, g: CompanionSetup.Game, extra: String = "", done: (List<String>) -> Unit) {
        worker.execute {
            // inside the private launcher: no root setup (the launcher installs the add-ons itself, S5); nothing is reported
            if (!RootPolicy.allowed) { done(listOf("SKIP the launcher", "END")); return@execute }
            val lines = try {
                unpack()
                val script = File(dir, "companion_setup.sh").absolutePath
                // the located game's folders (no hard-coded paths in the share): the script reads them as WC_WF / WC_WF_CT
                val env = wowForever?.let { "WC_WF=${q(it.root)} WC_WF_CT=${q(it.container)} " } ?: ""
                val p = Runtime.getRuntime().exec(arrayOf("su", "--mount-master", "-c", "${env}sh '$script' $mode ${g.id} '${dir.absolutePath}' $extra"))
                p.outputStream.close()
                val out = p.inputStream.bufferedReader().readLines()
                p.waitFor()
                out
            } catch (t: Throwable) {
                listOf("FAIL $mode ${t.message ?: "root shell failed"}", "END")
            }
            Log.i(TAG, "$mode ${g.id}: ${lines.joinToString(" | ").take(400)}")
            done(lines)
        }
    }

    /** copies the script + the add-on set out of the APK when this build's manifest is not the one already unpacked */
    private fun unpack() {
        val am = ctx.assets
        val want = try { am.open("companion/manifest.txt").use { it.readBytes() } } catch (_: Throwable) { null }
        val have = File(dir, "manifest.txt").takeIf { it.exists() }?.readBytes()
        val script = am.open("companion/companion_setup.sh").use { it.readBytes() }
        dir.mkdirs()
        File(dir, "companion_setup.sh").writeBytes(script)
        if (want == null || (have != null && have.contentEquals(want))) return
        val out = File(dir, "addons")
        out.deleteRecursively()
        copyTree("companion/addons", out)
        File(dir, "manifest.txt").writeBytes(want)      // last: a half-copied set is never taken for a whole one
    }

    private fun copyTree(asset: String, to: File) {
        val kids = ctx.assets.list(asset) ?: emptyArray()
        if (kids.isEmpty()) {
            to.parentFile?.mkdirs()
            ctx.assets.open(asset).use { i -> to.outputStream().use { o -> i.copyTo(o) } }
            return
        }
        to.mkdirs()
        for (k in kids) copyTree("$asset/$k", File(to, k))
    }

    companion object { const val TAG = "WCSETUP" }
}
