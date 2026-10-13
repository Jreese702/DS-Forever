package com.jrees.wowcompanion

import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * STORAGE POLICY for art/interiors (Track C). [IndoorBakeStore] never deletes: every publication adds a generation, every
 * re-bake a building folder. This trims, SAFELY, from the scheduler's own thread (never during a bake):
 *
 * - generations: the current one plus the newest [keepGenerations]-1 others are kept; older ones go once past [graceMs]
 *   (a reader such as MapView holds a captured generation and reads its assets lazily: grace + keep cover it);
 * - receipts: only when the catalogue is non-empty, receipts whose signature is not in it (changed or removed buildings);
 * - buildings: a folder named by no remaining receipt and by no kept generation, past grace;
 * - `.pending_*` leftovers of killed bakes / publications, past grace.
 * Symlinks are removed as links, never followed.
 */
internal object IndoorSchedulerCleanup {
    data class Report(val generations: Int, val receipts: Int, val buildings: Int, val pending: Int, val bytes: Long) {
        val removed: Int get() = generations + receipts + buildings + pending
    }

    const val KEEP_GENERATIONS = 3
    const val GRACE_MS = 10 * 60_000L
    private val GENERATION = Regex("g[0-9a-f]{32}")
    private val BUILDING = Regex("b[0-9a-f]{32}")
    private val REFERENCE = Regex("b[0-9a-f]{32}(?=--)")
    private val RECEIPT = Regex("[0-9a-f]{64}\\.json")

    fun sweep(artRoot: File, catalogue: List<IndoorBakeStore.Work>, now: Long = System.currentTimeMillis(),
              keepGenerations: Int = KEEP_GENERATIONS, graceMs: Long = GRACE_MS): Report {
        require(keepGenerations >= 1)
        val root = File(artRoot, "interiors")
        val snapshots = File(root, "snapshots"); val buildings = File(root, "buildings"); val receipts = File(root, "receipts")
        var gens = 0; var recs = 0; var blds = 0; var pend = 0; var bytes = 0L
        fun old(f: File) = now - f.lastModified() >= graceMs
        fun remove(f: File): Boolean { val n = size(f); return delete(f).also { if (it) bytes += n } }

        val current = runCatching { JSONObject(File(root, "current.json").readText()).optString("generation") }.getOrNull()
        // generations, newest first; the current one always stays
        val all = snapshots.listFiles()?.filter { it.isDirectory && GENERATION.matches(it.name) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        val kept = ArrayList<File>()
        all.firstOrNull { it.name == current }?.let { kept.add(it) }
        for (g in all) if (g !in kept && kept.size < keepGenerations) kept.add(g)
        for (g in all) if (g !in kept && old(g) && remove(g)) gens++

        // receipts of buildings that are no longer in the active catalogue (an empty catalogue proves nothing)
        if (catalogue.isNotEmpty()) {
            val live = catalogue.mapTo(hashSetOf()) { it.signature + ".json" }
            receipts.listFiles()?.filter { it.isFile && RECEIPT.matches(it.name) && it.name !in live }
                ?.forEach { if (remove(it)) recs++ }
        }

        // building folders still referenced by a receipt or a kept generation stay
        val referenced = hashSetOf<String>()
        receipts.listFiles()?.filter { it.isFile && RECEIPT.matches(it.name) }?.forEach { f ->
            runCatching { JSONObject(f.readText()).optString("directory") }.getOrNull()?.let { referenced.add(it) }
        }
        // Grace-retained generations also protect their lazy assets, not only the newest three.
        for (g in all.filter { it.exists() }) runCatching {
            g.listFiles()?.filter { it.name == "models.json" || it.name.matches(Regex("models_[0-9]+\\.json")) }
                ?.forEach { file ->
                    require(file.length() in 1..IndoorAssets.MAX_INDEX_BYTES && !Files.isSymbolicLink(file.toPath()))
                    REFERENCE.findAll(file.readText()).forEach { referenced.add(it.value) }
                }
        }
        buildings.listFiles()?.filter { BUILDING.matches(it.name) && it.name !in referenced && old(it) }
            ?.forEach { if (remove(it)) blds++ }

        for (dir in listOf(root, snapshots, buildings, receipts))
            dir.listFiles()?.filter { it.name.startsWith(".pending_") && old(it) }?.forEach { if (remove(it)) pend++ }
        return Report(gens, recs, blds, pend, bytes)
    }

    /** bytes under [f] without following links */
    fun size(f: File): Long {
        val p = f.toPath()
        if (Files.isSymbolicLink(p)) return 0L
        if (f.isFile) return f.length()
        return f.listFiles()?.sumOf { size(it) } ?: 0L
    }

    private fun delete(f: File): Boolean {
        val p = f.toPath()
        if (!Files.isSymbolicLink(p) && f.isDirectory) f.listFiles()?.forEach { delete(it) }
        return runCatching { Files.deleteIfExists(p) }.getOrDefault(false)
    }
}
