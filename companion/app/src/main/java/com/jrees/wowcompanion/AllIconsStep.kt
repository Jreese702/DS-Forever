package com.jrees.wowcompanion

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** [AllIcons]' harvest step on the device (registered in ArtHarvest.STEPS): every icon pack, resumable. Before Play since 2026-11-04
 *  (the user: "I shouldn't be able to start the game before my Icons have finished loading") */
object AllIconsStep : ArtHarvest.Step {
    override val id = "allicons"
    override val version = 2                 // 2: packs named by their icons' content keys (ArtFingerprint)
    // 2026-11-07 (the user stuck on "Preparing components" after a game update: every update rebuilt all ~30,000 icons before Play):
    // before Play only while NO pack exists (a first install); after that a rebuild runs in the background on the packs already there
    // 1.0.17 (the player reports: art "5 hours and only 54%" - this step was 40 % of the first bar; the user's pick "Core only, rest in
    // game"): always in the background; an icon not packed yet is asked for (IconRequirements) and blank until then
    override val beforePlay: Boolean get() = false
    override val weight = 3000
    override fun run(job: ArtHarvest.Job) {
        val table = job.game.db2(AllIcons.MANIFEST) ?: error(job.game.lastReadWhy ?: "the game's interface manifest could not be read")
        val ids = AllIcons.iconIds(table.rows(strings = setOf(0)).map { r: Db2.Row -> r.id to ((r.values.getOrNull(0) as? String) ?: "") })
        if (ids.isEmpty()) error("the game's interface manifest names no icons")
        val parts = AllIcons.chunks(ids)
        // 2026-11-07 (ArtFingerprint): a pack is named by its icons' CASC content keys, not the game build, so a new build keeps every
        // pack whose icons did not change (a few changed icons = their pack only)
        // (memory relief 2: the stop checked at each icon's key too - 30,000 lookups in the index)
        val names = parts.mapIndexed { i, c ->
            AllIcons.chunkName(ArtFingerprint.of(c, emptyList(), { job.checkpoint(); job.game.contentKey(it) }, { null }), i, c)
        }
        val dir = job.file("${AllIcons.DIR}/.keep").parentFile!!
        AllIcons.progress(0, ids.size); AllIcons.finish(false)
        // MEMORY RELIEF 2 (the user's picks, 2026-11-14: "STOP ART WORK SOONER: when Play is pressed, a running background art step lets
        // go of the game files within one file (check per file / per download batch, not per pack; #254 kept ~470 MB for 39 s), and frees
        // its buffers"): #254's run reported once a PACK (1000 icons, ~40 s on a Thor), so the launch waited that long with the game files
        // open. Now the stop is checked before every icon (KitBuild.icons: its threads take no further file once asked) and every download
        // of the pack's prefetch (GameFiles.prefetch), and the wait for a fight's end no longer sits inside the files
        val kit = KitBuild(read = { fid -> synchronized(job.game) { job.game.read(fid) } }, write = { _, _ -> }, asset = { null },
            letGo = { job.letGoAsked() }, checkpoint = { job.checkpoint() })
        parts.forEachIndexed { i, c ->
            val f = File(dir, names[i])
            if (!f.isFile) {
                while (AllIcons.busy()) { job.checkpoint(); Thread.sleep(2_000) }
                // the pack's missing game files fetched several at once first (a partial / streamed Data folder: one by one was minutes);
                // a failed fetch is not the step's (the reads fetch again), a stop is (ArtHarvest.tolerant)
                ArtHarvest.tolerant { job.game.prefetch(c, letGo = { job.letGoAsked() }) }
                job.checkpoint()                                    // a stop asked during the fetch: here, before any icon is read
                val n = kit.icons(JSONObject().put("fids", JSONArray(c)), f, lenient = true, encode = KitSteps::webp)
                Log.i("WCHARVEST", "all icons: pack ${i + 1} / ${parts.size}: $n of ${c.size}")
                Icons.refresh()                                     // the new pack is read at once (and blank toolbar icons ask again)
                Thread.sleep(AllIcons.PAUSE_MS)
            }
            AllIcons.progress(AllIcons.done + c.size, ids.size)
            job.progress(AllIcons.done, ids.size, "icons ${AllIcons.done} / ${ids.size}")
        }
        // the packs of another list / build go only now, once every pack of this one is written (icons never blank meanwhile)
        dir.listFiles()?.filter { it.name.endsWith(".pak") && it.name !in names }?.forEach { it.delete() }
        Icons.refresh()
        AllIcons.finish(true)
        Log.i("WCHARVEST", "all icons ready: ${ids.size} in ${parts.size} packs")
    }
}
