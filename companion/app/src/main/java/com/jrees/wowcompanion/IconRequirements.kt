package com.jrees.wowcompanion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persist actual UI dependencies as metadata; never infer availability from a failed read. */
object IconRequirements {
    private var app: Context? = null
    private var build = ""
    private var seed: JSONObject? = null
    private var observed: JSONObject? = null
    private var ids = emptySet<Int>()
    private var failure: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { app?.let { ArtHarvest.start(it) } }
    private var listening = false

    @Synchronized fun init(ctx: Context) {
        val active = GameFiles.activeBuild(GameFiles.gameRoot(ctx))?.second ?: return
        if (app != null && active == build) return
        app = ctx.applicationContext
        build = active
        failure = null
        runCatching {
            seed = ctx.assets.open("harvest/icons.json").bufferedReader().use { JSONObject(it.readText()) }
            val file = File(ctx.filesDir, "icon_requirements.json")
            observed = if (file.exists()) JSONObject(file.readText()) else null
            val array = IconRecipe.resolve(seed!!, observed, build).getJSONArray("fids")
            ids = (0 until array.length()).map { array.getInt(it) }.toSet()
        }.onFailure { failure = "icon dependencies could not be loaded: ${it.message}" }
        if (!listening) {
            listening = true
            ArtHarvest.onState { state ->
                if (state is ArtHarvest.State.Ready) {
                    Icons.refresh()
                    app?.let { if (ArtHarvest.needsBeforePlay(it)) schedule() }
                }
            }
        }
    }

    @Synchronized fun recipe(ctx: Context, active: String): JSONObject {
        init(ctx)
        failure?.let { error(it) }
        check(active == build) { "game build changed during icon harvest" }
        return IconRecipe.resolve(seed ?: error("no fixed icon recipe"), observed, build)
    }

    fun inputKey(ctx: Context, active: String): String = runCatching {
        IconRecipe.fingerprint(recipe(ctx, active))
    }.getOrElse { "invalid" }

    @Synchronized fun require(id: Int) {
        if (id <= 0 || id in ids || failure != null) return
        val ctx = app ?: return
        runCatching {
            val updated = ids + id
            val data = JSONObject().put("build", build).put("fids", JSONArray(updated.sorted()))
            val file = File(ctx.filesDir, "icon_requirements.json")
            val temp = File(ctx.filesDir, "icon_requirements.json.part")
            temp.writeText(data.toString())
            check(temp.renameTo(file)) { "required icon metadata could not be saved" }
            observed = data
            ids = updated
            Log.i("WCHARVEST", "icons: UI requires file $id for this game build")
            schedule()
        }.onFailure {
            failure = "icon $id dependency could not be saved: ${it.message}"
            Log.w("WCHARVEST", failure, it)
            schedule()
        }
    }

    private fun schedule() { handler.removeCallbacks(retry); handler.postDelayed(retry, 250) }
}
