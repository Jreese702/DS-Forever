package com.jrees.wowcompanion

import java.io.File

/** Interiors must never fall back to old server-derived plans. */
internal object IndoorAssets {
    data class Stamp(val exists: Boolean, val size: Long, val modified: Long)
    data class Revision(val root: String?, val indexes: List<Stamp>)
    private val indexes = setOf("models.json", "places.json", "interiors.json")
    private val shard = Regex("(models|places)_(0|[1-9][0-9]*)\\.json")
    private val generation = Regex("g[0-9a-f]{32}")
    private val building = Regex("b[0-9a-f]{32}")

    /** Captured once for all indexes and lazy assets belonging to the same publication. */
    data class Snapshot(val directory: File, val buildings: File? = null, val continents: List<Int>? = null) {
        fun indexFiles(): List<File> = (continents?.flatMap { listOf("models_$it.json", "places_$it.json") }
            ?: indexes.toList()).map { File(directory, it) }

        fun models(continent: Int): File = index("models", continent)
        fun places(continent: Int): File = index("places", continent)
        private fun index(kind: String, continent: Int): File {
            require(continent >= 0)
            val file = File(directory, if (continents == null) "$kind.json" else "${kind}_$continent.json")
            require(!file.exists() || file.length() in 1..MAX_INDEX_BYTES) { "invalid interiors index size" }
            return file
        }

        fun file(name: String): File {
            basename(name)
            if (buildings == null || name in indexes || shard.matches(name)) return File(directory, name)
            val split = name.indexOf("--")
            require(split == 33 && building.matches(name.substring(0, split))) { "invalid interiors asset reference" }
            val leaf = name.substring(split + 2)
            basename(leaf)
            return File(File(buildings, name.substring(0, split)), leaf)
        }

        fun revision() = Revision(directory.absolutePath, indexFiles().map { f ->
            Stamp(f.isFile, f.length(), f.lastModified())
        })
    }

    fun snapshot(artRoot: File?, expectedBuild: String? = null): Snapshot {
        val missing = Snapshot(File("/dev/null/interiors"))
        val root = artRoot?.let { File(it, "interiors") } ?: return missing
        val pointer = File(root, "current.json")
        // Only host review bundles use the pre-generation layout. A running game must
        // have a publication explicitly verified against its active build.
        if (!pointer.exists()) return if (expectedBuild == null) Snapshot(root) else missing
        return runCatching {
            require(pointer.isFile && pointer.length() in 1..4096)
            val value = org.json.JSONObject(pointer.readText())
            val name = value.optString("generation")
            val schema = value.optInt("schema")
            require(schema in 1..2 && generation.matches(name))
            require(expectedBuild == null || value.optString("build") == expectedBuild)
            val dir = File(File(root, "snapshots"), name)
            val continents = if (schema == 2) {
                val ids = value.optJSONArray("continents") ?: error("missing continents")
                require(ids.length() <= 512)
                val list = (0 until ids.length()).map { ids.optInt(it, -1).also { id ->
                    require(id >= 0 && ids.optDouble(it) == id.toDouble())
                } }
                require(list == list.distinct().sorted())
                list
            } else null
            val snapshot = Snapshot(dir, File(root, "buildings"), continents)
            require(dir.isDirectory && snapshot.indexFiles().all { it.isFile })
            snapshot
        }.getOrDefault(missing)
    }

    fun revision(artRoot: File?): Revision = snapshot(artRoot).revision()

    fun file(artRoot: File?, name: String): File {
        return snapshot(artRoot).file(name)
    }

    internal fun basename(name: String) {
        require(name.isNotEmpty() && name.length <= 180 && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == ':' || it.code < 32 }) { "invalid interiors filename" }
    }
    const val MAX_INDEX_BYTES = 4L * 1024 * 1024
}
