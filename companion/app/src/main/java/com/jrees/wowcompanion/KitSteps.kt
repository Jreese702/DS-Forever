package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * UI ART'S HARVEST STEPS (2026-10-09; the user picked the kit, the icons and the world maps BEFORE Play): [KitBuild] wired to the harvest's
 * [ArtHarvest.Job] - the game's files in, the private art folder out (the same relative paths the PC tools pushed to /sdcard/WowCompanion:
 * fonts/, ui/kit/, ui/cast/, ui/<bags>.png, icons.pak). Registered in ArtHarvest.STEPS (Home's file): KitSteps.Kit, KitSteps.Cast,
 * KitSteps.Icons. Bump a step's [ArtHarvest.Step.version] when its recipe or code changes what it writes.
 */
object KitSteps {
    private const val TAG = "WCHARVEST"

    private fun build(job: ArtHarvest.Job, reasons: MutableMap<Int, String> = java.util.concurrent.ConcurrentHashMap()) = KitBuild(
        read = { fid -> synchronized(job.game) { job.game.read(fid).also { bytes ->
            if (bytes == null) (job.game.lastReadWhy ?: "file $fid could not be read").let { Log.w(TAG, it); reasons[fid] = it }
        } } },
        write = { rel, b -> job.writeBytes(rel, b) },
        asset = { p -> runCatching { job.ctx.assets.open(p).use { it.readBytes() } }.getOrNull() },
        progress = { done, total, what -> job.progress(done, total, what) },
        log = { Log.i(TAG, it) },
        why = { job.game.lastReadWhy },
        whyFor = { reasons[it] })

    /** fonts, the kit's pictures, the map pins, the bags and the paper doll, the Home button (extract_kit / minimap_kit / extract_ui / home_kit) */
    object Kit : ArtHarvest.Step {
        override val id = "kit"
        override val version = 3
        override val beforePlay = true
        override val weight = 900      // the weights are shares of the bar: on the PC kit 15 s, cast 0.5 s, icons ~5 s (4 threads, PNG for WebP); the phone unmeasured
        override fun run(job: ArtHarvest.Job) {
            val kit = build(job)
            val holidays = job.game.db2(996357) ?: error(job.game.lastReadWhy ?: "the game's holiday table could not be read")
            val recipe = KitBuild.withHolidays(job.recipe(), holidays)
            Log.i(TAG, "kit: holiday recipe resolved from this build's Holidays table (${holidays.recordCount} rows)")
            kit.kit(recipe)
            kit.requireComplete(id)
            degraded(job, id, kit)
        }
    }

    /**
     * ART IN A DEGRADED STATE (issues #35 #36 #38 #42, 2026-11-11): the step is whole but for pictures outside KitBuild.CORE that did not
     * read; Play goes on with the app's stand-ins. The stamp's inputs say so ([DEGRADED]), so the next game build rebuilds the step
     * instead of re-stamping it as unchanged (ArtHarvest.unchanged).
     */
    internal fun degraded(job: ArtHarvest.Job, id: String, b: KitBuild) {
        job.inputKey = degradedInputs(job, id, b.degraded.size, b.fetchLost)
    }

    /**
     * The stamp's inputs of step [id] that left [lost] pictures to stand-ins, [fetchLost] of them only because a download failed (null:
     * whole). A download loss is tried again first, in this launch (HarvestRetry: the step throws until its last try), then stamped
     * with ":fetch:K" so the background queue builds it again on a later launch ([retryDue]); K counts those tries on this build.
     */
    internal fun degradedInputs(job: ArtHarvest.Job, id: String, lost: Int, fetchLost: Int): String? {
        if (lost == 0) return null
        if (fetchLost > 0 && !job.lastTry) error("$fetchLost of $id's pictures could not be downloaded: trying again")
        val prev = ArtHarvest.stampOf(job.ctx).optJSONObject(id)
            ?.takeIf { it.optString("build") == job.game.buildKey }?.let { fetchRetries(it.optString("inputs")) }
        val key = degradedKey(lost, fetchLost, if (prev == null) 0 else prev + 1)
        Log.w(TAG, "art in a degraded state: $id left $lost pictures to the app's stand-ins ($fetchLost not downloaded), Play goes on")
        return key
    }

    /** the stamp's inputs for a build that finished degraded, or null for a whole one */
    internal fun degradedKey(b: KitBuild): String? = degradedKey(b.degraded.size, b.fetchLost, 0)

    internal fun degradedKey(lost: Int, fetchLost: Int, retries: Int): String? =
        if (lost == 0) null else "$DEGRADED$lost" + if (fetchLost > 0) "$FETCH$retries" else ""

    /** the stamp's inputs prefix of a step that finished in a degraded state */
    const val DEGRADED = "degraded:"
    /** ... and the part that says some of its losses were failed downloads, then how many background tries this build had */
    const val FETCH = ":fetch:"

    /** the background tries of a degraded stamp with failed downloads (null: none to try again) */
    internal fun fetchRetries(inputs: String?): Int? =
        inputs?.takeIf { it.startsWith(DEGRADED) && FETCH in it }?.substringAfter(FETCH)?.toIntOrNull()

    /** the wait before a degraded stamp's failed downloads are tried again, by the tries so far: the next launch, then backing off */
    val RETRY_BACKOFF_MS = longArrayOf(0L, 10 * 60_000L, 60 * 60_000L, 6 * 60 * 60_000L, 24 * 60 * 60_000L)

    /**
     * STAND-INS FROM A NETWORK HICCUP ARE MENDED (review of #35 #36 #38, 2026-11-11): a step stamped degraded with failed downloads
     * ([FETCH]), stamped [at], is due again at [now] in the BACKGROUND queue (never before Play: ArtHarvest.queued), once its back-off
     * ([RETRY_BACKOFF_MS]) has passed. A degraded stamp of pictures that are unreadable in the game's own files is not (a new build is).
     */
    internal fun retryDue(inputs: String?, at: Long, now: Long): Boolean {
        val k = fetchRetries(inputs) ?: return false
        return now - at >= RETRY_BACKOFF_MS[k.coerceIn(0, RETRY_BACKOFF_MS.size - 1)]
    }

    /** the controller HUD's pieces (extract_cast_art.py) */
    object Cast : ArtHarvest.Step {
        override val id = "cast"
        override val version = 4                  // f-cast-bar-art: + the casting bar's pieces; b-cast-bar-size: + its TextBox
        override val beforePlay = true
        override val weight = 50
        override fun run(job: ArtHarvest.Job) {
            val r = job.recipe()
            val cast = build(job)
            cast.cast(r, r.optJSONObject("db2") ?: JSONObject())
            cast.requireComplete(id)
            degraded(job, id, cast)
        }
    }

    /** icons.pak (build_pak.py): every icon the rooms show, 64 px WebP */
    object Icons : ArtHarvest.Step {
        override val id = "icons"
        override val version = 5
        // 1.0.17: before Play only while no icons.pak exists (a first install); a newly asked icon rebuilds it in the background
        override val beforePlay: Boolean get() = !File(ArtHarvest.dir("."), "icons.pak").isFile
        override val weight = 1200
        override fun run(job: ArtHarvest.Job) {
            Log.i(TAG, "icons: registered ${job.game.loadClientKeys()} keys from this game's own tables")
            do {
                val recipe = IconRequirements.recipe(job.ctx, job.game.buildKey)
                val key = IconRecipe.fingerprint(recipe)
                Log.i(TAG, "icons: ${recipe.getJSONArray("fids").length()} required companion references")
                val icons = build(job)
                // #35 #36 #38 review (2026-11-11): tolerant, so one unreadable icon no longer locks Play on a first install
                val n = icons.icons(recipe, job.file("icons.pak"), tolerant = true, encode = ::webp)
                if (n == 0) error("no icon could be read from the game's files")
                icons.requireComplete(id)
                // a failed download is tried again in this launch; on the last try the pack keeps what it has (the all-icons packs and
                // the next asked-for icon fill the rest), the stamp stays the requirements' key so the step is not rebuilt each launch
                if (icons.fetchLost > 0 && !job.lastTry) error("${icons.fetchLost} icons could not be downloaded: trying again")
                if (icons.degraded.isNotEmpty()) Log.w(TAG, "icons in a degraded state: ${icons.degraded.size} left out, Play goes on")
                job.inputKey = key
            } while (job.inputKey != IconRequirements.inputKey(job.ctx, job.game.buildKey))
        }
    }

    @Suppress("DEPRECATION")
    internal fun webp(im: KitImage, quality: Int): ByteArray? {
        val bmp = Bitmap.createBitmap(im.px, im.w, im.h, Bitmap.Config.ARGB_8888)
        val o = ByteArrayOutputStream(4096)
        val ok = bmp.compress(if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP, quality, o)
        bmp.recycle()
        return if (ok) o.toByteArray() else null
    }
}
