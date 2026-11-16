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
    /** #404 (the user's pick, 2026-11-16): every class's spellbook icons for one game build (ClassBookIcons, read in the icons step) */
    private var classBook: JSONObject? = null
    private var ids = emptySet<Int>()
    private var failure: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { app?.let { ArtHarvest.start(it) } }
    private var listening = false
    private const val CLASS_BOOK = "icon_classbook.json"

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
            // a class book that does not read is only not there (the icons step reads the tables again)
            classBook = runCatching { File(ctx.filesDir, CLASS_BOOK).takeIf { it.exists() }?.let { JSONObject(it.readText()) } }.getOrNull()
            val array = IconRecipe.resolve(seed!!, observed, build, classBook).getJSONArray("fids")
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
        return IconRecipe.resolve(seed ?: error("no fixed icon recipe"), observed, build, classBook)
    }

    fun inputKey(ctx: Context, active: String): String = runCatching {
        IconRecipe.fingerprint(recipe(ctx, active))
    }.getOrElse { "invalid" }

    /** #404: this build's class spellbook icons are in the recipe already */
    @Synchronized fun hasClassBook(ctx: Context, active: String): Boolean {
        init(ctx)
        return active == build && classBook?.optString("build") == active
    }

    /**
     * #404: [icons] are every class's spellbook icons for game build [active]: kept (an atomic write, as the asked icons) and in the
     * recipe from now on, so the first pack holds them and the bottom screen never asks for them one by one
     */
    @Synchronized fun setClassBook(ctx: Context, active: String, icons: Collection<Int>) {
        init(ctx)
        check(active == build) { "game build changed during icon harvest" }
        val data = JSONObject().put("build", active).put("fids", JSONArray(icons.filter { it > 0 }.sorted()))
        val file = File(ctx.filesDir, CLASS_BOOK)
        val temp = File(ctx.filesDir, "$CLASS_BOOK.part")
        temp.writeText(data.toString())
        check(temp.renameTo(file)) { "class spellbook icon metadata could not be saved" }
        classBook = data
        ids = ids + icons.filter { it > 0 }
    }

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
