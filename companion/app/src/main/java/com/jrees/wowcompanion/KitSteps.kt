package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * UI ART'S HARVEST STEPS (2026-10-09; the user picked the kit, the icons and the world maps BEFORE Play): [KitBuild] wired to the harvest's
 * [ArtHarvest.Job] - the game's files in, the private art folder out (the same relative paths the PC tools pushed to /sdcard/WowCompanion:
 * fonts/, ui/kit/, ui/cast/, ui/<bags>.png, icons.pak). Registered in ArtHarvest.STEPS (Home's file): KitSteps.Kit, KitSteps.Cast,
 * KitSteps.Icons. Bump a step's [ArtHarvest.Step.version] when its recipe or code changes what it writes.
 */
object KitSteps {
    private const val TAG = "WCHARVEST"

    private fun build(job: ArtHarvest.Job) = KitBuild(
        read = { fid -> synchronized(job.game) { job.game.read(fid).also { bytes ->
            if (bytes == null) Log.w(TAG, job.game.lastReadWhy ?: "file $fid could not be read")
        } } },
        write = { rel, b -> job.writeBytes(rel, b) },
        asset = { p -> runCatching { job.ctx.assets.open(p).use { it.readBytes() } }.getOrNull() },
        progress = { done, total, what -> job.progress(done, total, what) },
        log = { Log.i(TAG, it) })

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
        }
    }

    /** the controller HUD's pieces (extract_cast_art.py) */
    object Cast : ArtHarvest.Step {
        override val id = "cast"
        override val version = 2
        override val beforePlay = true
        override val weight = 50
        override fun run(job: ArtHarvest.Job) {
            val r = job.recipe()
            val cast = build(job)
            cast.cast(r, r.optJSONObject("db2") ?: JSONObject())
            cast.requireComplete(id)
        }
    }

    /** icons.pak (build_pak.py): every icon the rooms show, 64 px WebP */
    object Icons : ArtHarvest.Step {
        override val id = "icons"
        override val version = 5
        override val beforePlay = true
        override val weight = 1200
        override fun run(job: ArtHarvest.Job) {
            Log.i(TAG, "icons: registered ${job.game.loadClientKeys()} keys from this game's own tables")
            do {
                val recipe = IconRequirements.recipe(job.ctx, job.game.buildKey)
                val key = IconRecipe.fingerprint(recipe)
                Log.i(TAG, "icons: ${recipe.getJSONArray("fids").length()} required companion references")
                val icons = build(job)
                val n = icons.icons(recipe, job.file("icons.pak"), encode = ::webp)
                if (n == 0) error("no icon could be read from the game's files")
                icons.requireComplete(id)
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
