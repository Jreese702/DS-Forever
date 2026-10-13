package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID

/** Immutable per-building outputs, resumable receipts, and one atomic index publication. */
internal class IndoorBakeStore(artRoot: File) {
    data class Location(val continent: Int, val xmin: Double, val xmax: Double, val ymin: Double, val ymax: Double) {
        init {
            require(continent >= 0 && listOf(xmin, xmax, ymin, ymax).all { it.isFinite() })
            require(xmin <= xmax && ymin <= ymax)
        }
    }

    data class Work(val key: String, val inputs: String, val algorithm: Int, val locations: List<Location>) {
        init {
            require(key.matches(Regex("[A-Za-z0-9_]{1,120}")))
            require(inputs.matches(Regex("[0-9a-f]{64}")) && algorithm > 0)
        }
        // The build is deliberately absent: unchanged verified inputs survive a game update.
        val signature: String get() = digest("$algorithm\n$key\n$inputs".toByteArray(Charsets.UTF_8))
    }

    data class Completed(val work: Work, val directory: File)
    private val root = File(artRoot, "interiors")
    private val receipts = File(root, "receipts")
    private val buildings = File(root, "buildings")
    private val snapshots = File(root, "snapshots")
    private data class Verified(val directory: File, val fingerprint: String)
    private val verified = object : LinkedHashMap<String, Verified>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Verified>?) = size > 2048
    }
    internal var publicationCacheHits = 0L
        private set
    internal var hashedBytes = 0L
        private set

    private fun checkedHash(file: File): String {
        hashedBytes += file.length()
        return hashFile(file)
    }

    /** Private sealed outputs are immutable; metadata changes invalidate this process-only publication cache. */
    private fun fingerprint(directory: File, receipt: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fun stamp(file: File, isDirectory: Boolean = false) {
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(if (isDirectory) attrs.isDirectory else attrs.isRegularFile)
            hash.update((file.name + "\u0000" + attrs.size() + "\u0000" + attrs.lastModifiedTime() +
                "\u0000" + attrs.creationTime() + "\u0000" + attrs.fileKey() + "\n").toByteArray(Charsets.UTF_8))
        }
        stamp(receipt)
        stamp(directory, true)
        val files = directory.listFiles()?.sortedBy { it.name } ?: error("missing sealed directory")
        require(files.size in 3..512)
        var bytes = 0L
        for (file in files) {
            IndoorAssets.basename(file.name)
            require(file.length() in 1..MAX_FILE)
            bytes += file.length()
            require(bytes <= MAX_BUILDING)
            stamp(file)
        }
        return hex(hash.digest())
    }

    private fun forPublication(work: Work): Completed? {
        val cached = verified[work.signature]
        if (cached != null && runCatching {
                fingerprint(cached.directory, File(receipts, work.signature + ".json")) == cached.fingerprint
            }.getOrDefault(false)) {
            publicationCacheHits++
            return Completed(work, cached.directory)
        }
        return completed(work)
    }

    init { listOf(receipts, buildings, snapshots).forEach { check(it.isDirectory || it.mkdirs()) } }

    @Synchronized fun completed(work: Work): Completed? = runCatching {
        val receiptFile = File(receipts, work.signature + ".json")
        require(!Files.isSymbolicLink(receiptFile.toPath()))
        val receipt = json(receiptFile)
        require(receipt.optInt("schema") == 1 && receipt.optString("signature") == work.signature)
        val name = receipt.optString("directory")
        require(name.matches(Regex("b[0-9a-f]{32}")))
        val directory = File(buildings, name)
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
        val before = fingerprint(directory, receiptFile)
        val files = receipt.optJSONObject("files") ?: error("missing output hashes")
        val names = files.keys().asSequence().toList()
        require(names.size in 3..512 && names.containsAll(INDEXES))
        var total = 0L
        for (leaf in names) {
            IndoorAssets.basename(leaf)
            val file = File(directory, leaf)
            require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..MAX_FILE)
            total += file.length()
            require(total <= MAX_BUILDING)
            require(checkedHash(file) == files.optString(leaf))
        }
        validateIndexes(directory, work.key)
        val after = fingerprint(directory, receiptFile)
        require(before == after) { "sealed output changed during verification" }
        verified[work.signature] = Verified(directory, after)
        Completed(work, directory)
    }.onFailure { verified.remove(work.signature) }.getOrNull()

    /** The producer must validate geometry and its output contract before returning. */
    @Synchronized fun bake(work: Work, produce: (File) -> Unit): Completed {
        completed(work)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "")
        val staging = File(buildings, ".pending_$id")
        check(staging.mkdir())
        try {
            produce(staging)
            validateIndexes(staging, work.key)
            val files = staging.listFiles()?.sortedBy { it.name } ?: error("missing building output")
            require(files.size in 3..512)
            val hashes = JSONObject()
            var total = 0L
            for (file in files) {
                IndoorAssets.basename(file.name)
                require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..MAX_FILE)
                total += file.length()
                require(total <= MAX_BUILDING) { "interiors building storage budget exceeded" }
                // Sync producer-owned files before making their receipt visible.
                FileOutputStream(file, true).use { it.fd.sync() }
                hashes.put(file.name, checkedHash(file))
            }
            val final = File(buildings, "b$id")
            Files.move(staging.toPath(), final.toPath(), ATOMIC_MOVE)
            val receipt = JSONObject().put("schema", 1).put("signature", work.signature)
                .put("key", work.key).put("inputs", work.inputs).put("algorithm", work.algorithm)
                .put("directory", final.name).put("files", hashes)
            atomicJson(File(receipts, work.signature + ".json"), receipt)
            return Completed(work, final)
        } finally {
            discardStaging(staging)
        }
    }

    /** Only currently verified work is visible; unfinished/changed buildings show outdoors. */
    @Synchronized fun publish(build: String, catalogue: List<Work>): Int {
        require(build.matches(Regex("[0-9a-f]{32}")))
        require(catalogue.map { it.key }.distinct().size == catalogue.size)
        val models = linkedMapOf<String, JSONObject>()
        val places = linkedMapOf<String, JSONArray>()
        var count = 0
        for (work in catalogue.sortedBy { it.key }) {
            val complete = forPublication(work) ?: continue
            val dir = complete.directory
            val model = json(File(dir, "models.json")).optJSONObject(work.key) ?: error("missing model")
            fun reference(leaf: String): String {
                IndoorAssets.basename(leaf)
                require(File(dir, leaf).isFile)
                return dir.name + "--" + leaf
            }
            val levels = model.optJSONArray("levels") ?: error("missing levels")
            for (i in 0 until levels.length()) {
                val level = levels.optJSONObject(i) ?: error("invalid level")
                level.put("img", reference(level.optString("img")))
                if (!level.isNull("outline")) level.optString("outline").takeIf { it.isNotEmpty() }
                    ?.let { level.put("outline", reference(it)) }
            }
            model.optJSONObject("grid")?.let { it.put("file", reference(it.optString("file"))) }
            // Older sealed receipts may put stairs only in their detail index; preserve them without republishing the duplicate.
            if (model.optJSONArray("stairs") == null) model.put("stairs",
                json(File(dir, "interiors.json")).optJSONObject("models")?.optJSONObject(work.key)?.optJSONArray("stairs")
                    ?: JSONArray())
            val source = json(File(dir, "places.json"))
            for (continent in source.keys()) {
                val list = source.optJSONArray(continent) ?: error("invalid placements")
                if (list.length() == 0) continue
                models.getOrPut(continent) { JSONObject() }.put(work.key, model)
                val target = places.getOrPut(continent) { JSONArray() }
                for (i in 0 until list.length()) target.put(list.optJSONObject(i) ?: error("invalid placement"))
            }
            count++
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        val staging = File(snapshots, ".pending_$id")
        check(staging.mkdir())
        try {
            val continents = places.keys.map { it.toInt() }.sorted()
            for (continent in continents) {
                val key = continent.toString()
                writeJson(File(staging, "models_$continent.json"), models.getValue(key))
                writeJson(File(staging, "places_$continent.json"), JSONObject().put(key, places.getValue(key)))
            }
            val generation = "g$id"
            val pointer = JSONObject().put("schema", 2).put("generation", generation).put("build", build)
                .put("continents", JSONArray(continents))
            require(pointer.toString().toByteArray(Charsets.UTF_8).size <= 4096) { "interiors pointer budget exceeded" }
            Files.move(staging.toPath(), File(snapshots, generation).toPath(), ATOMIC_MOVE)
            atomicJson(File(root, "current.json"), pointer)
            return count
        } finally {
            discardStaging(staging)
        }
    }

    private fun validateIndexes(directory: File, key: String) {
        val models = json(File(directory, "models.json"))
        require(models.keys().asSequence().toList() == listOf(key)) { "one building per receipt required" }
        val model = models.optJSONObject(key) ?: error("missing building model")
        val levels = model.optJSONArray("levels") ?: error("missing levels")
        require(levels.length() in 1..64)
        fun asset(name: String) {
            IndoorAssets.basename(name)
            require(name !in INDEXES && File(directory, name).isFile) { "missing building asset" }
        }
        for (i in 0 until levels.length()) {
            val level = levels.optJSONObject(i) ?: error("invalid level")
            asset(level.optString("img"))
            if (!level.isNull("outline")) level.optString("outline").takeIf { it.isNotEmpty() }?.let { asset(it) }
        }
        val grid = model.optJSONObject("grid") ?: error("missing height grid")
        asset(grid.optString("file"))
        val gridFile = File(directory, grid.optString("file"))
        require(gridFile.length() <= IndoorGrid.MAX_COMPRESSED_BYTES)
        IndoorGrid.decode(gridFile.readBytes(), grid.optDouble("cell"), grid.optInt("nx"), grid.optInt("ny"),
            grid.optDouble("lx0"), grid.optDouble("ly0"), grid.optDouble("zmin").toFloat(),
            grid.optDouble("zscale").toFloat(), grid.optInt("slots", 4))
        val details = json(File(directory, "interiors.json")).optJSONObject("models") ?: error("missing details")
        require(details.keys().asSequence().toList() == listOf(key) && details.optJSONObject(key) != null)
        val places = json(File(directory, "places.json"))
        var placements = 0
        for (continent in places.keys()) {
            require((continent.toIntOrNull() ?: -1) >= 0)
            require(continent.toInt().toString() == continent) { "non-canonical continent" }
            val list = places.optJSONArray(continent) ?: error("invalid placements")
            require(list.length() <= 10000)
            for (i in 0 until list.length()) {
                val place = list.optJSONObject(i) ?: error("invalid placement")
                require(place.optString("m") == key)
                val transform = place.optJSONArray("l2w") ?: error("missing placement transform")
                require(transform.length() == 4 && (0..3).all { transform.optDouble(it).isFinite() })
                Location(continent.toInt(), place.optDouble("wxmin"), place.optDouble("wxmax"),
                    place.optDouble("wymin"), place.optDouble("wymax"))
                placements++
            }
        }
        require(placements > 0) { "building has no placement" }
    }

    companion object {
        private val INDEXES = setOf("models.json", "places.json", "interiors.json")
        private const val MAX_FILE = 32L * 1024 * 1024
        private const val MAX_BUILDING = 128L * 1024 * 1024
        const val MAX_INDEX_BYTES = 4L * 1024 * 1024
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun hashFile(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    hash.update(buffer, 0, n)
                }
            }
            return hex(hash.digest())
        }
        private fun json(file: File): JSONObject {
            require(file.isFile && file.length() in 1..MAX_INDEX_BYTES) { "invalid interiors index size" }
            return JSONObject(file.readText())
        }
        private fun writeJson(file: File, value: JSONObject) {
            val bytes = value.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_INDEX_BYTES) { "interiors index budget exceeded" }
            FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
        }
        private fun atomicJson(file: File, value: JSONObject) {
            val temp = File(file.parentFile, ".pending_" + UUID.randomUUID())
            try {
                writeJson(temp, value)
                // No delete-then-rename fallback: failure must leave the old generation readable.
                Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { discardStaging(temp) }
        }

        private fun discardStaging(file: File) {
            if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory)
                file.listFiles()?.forEach { discardStaging(it) }
            Files.deleteIfExists(file.toPath())
        }
    }
}
