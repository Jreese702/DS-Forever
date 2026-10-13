package com.jrees.wowcompanion

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** [AllIcons]' harvest step on the device (registered in ArtHarvest.STEPS): every icon pack, resumable, in the background */
object AllIconsStep : ArtHarvest.Step {
    override val id = "allicons"
    override val version = 1
    override val beforePlay = false
    override val weight = 3000
    override fun run(job: ArtHarvest.Job) {
        val table = job.game.db2(AllIcons.MANIFEST) ?: error(job.game.lastReadWhy ?: "the game's interface manifest could not be read")
        val ids = AllIcons.iconIds(table.rows(strings = setOf(0)).map { r: Db2.Row -> r.id to ((r.values.getOrNull(0) as? String) ?: "") })
        if (ids.isEmpty()) error("the game's interface manifest names no icons")
        val parts = AllIcons.chunks(ids)
        val names = parts.mapIndexed { i, c -> AllIcons.chunkName(job.game.buildKey, i, c) }
        val dir = job.file("${AllIcons.DIR}/.keep").parentFile!!
        dir.listFiles()?.filter { it.name.endsWith(".pak") && it.name !in names }?.forEach { it.delete() }   // another build / list
        AllIcons.progress(0, ids.size); AllIcons.finish(false)
        val kit = KitBuild(read = { fid -> synchronized(job.game) { job.game.read(fid) } }, write = { _, _ -> }, asset = { null })
        parts.forEachIndexed { i, c ->
            val f = File(dir, names[i])
            if (!f.isFile) {
                while (AllIcons.busy()) Thread.sleep(2_000)
                val n = kit.icons(JSONObject().put("fids", JSONArray(c)), f, lenient = true, encode = KitSteps::webp)
                Log.i("WCHARVEST", "all icons: pack ${i + 1} / ${parts.size}: $n of ${c.size}")
                Icons.refresh()                                     // the new pack is read at once (and blank toolbar icons ask again)
                Thread.sleep(AllIcons.PAUSE_MS)
            }
            AllIcons.progress(AllIcons.done + c.size, ids.size)
            job.progress(AllIcons.done, ids.size, "icons ${AllIcons.done} / ${ids.size}")
        }
        AllIcons.finish(true)
        Log.i("WCHARVEST", "all icons ready: ${ids.size} in ${parts.size} packs")
    }
}
