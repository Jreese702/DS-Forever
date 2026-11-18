package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * THE THOR'S ADD-ONS FOR THE PC (the user, 2026-11-18: "My desktop seems to be missing the required addons for the app like polestar and
 * questie"; "I would have expected those to import with being enabled"). On every connect to DS Forever Desktop the Thor sends
 * "addons_want" (docs/STREAM_BRIDGE_PROTOCOL.md section 7):
 *  - questiedb: wanted (DependencyAddons' rule: always), Desktop takes Questie/QuestieDB's latest release itself;
 *  - managed: every package of [ThirdPartyAddons.ALL] with its PIN (tag, asset, size, sha256, folders, the CDN url for a CurseForge one)
 *    and whether the Thor wants it now (the player's Install / Polestar's choice / a module switched on). Desktop takes each only from its
 *    own known source, so a pin can never point it anywhere else;
 *  - imported: the add-ons the player imported here (Settings > Add-ons > Import), each with its files (path, size, sha256; [hashes]
 *    cached by size and time) and whether it is on; Desktop then asks for them a part at a time ("addon_file_get", [serve]);
 *  - enabled: on / off of those folders in the Thor's own AddOns.txt files ([enabled]); Desktop mirrors it only for the folders it put in.
 * Pure java.io (dsforever's PcAddonsWantTest).
 */
object PcAddonsWant {
    /** an imported add-on this big is copied to the PC only after the player's OK ([allowBig]) */
    const val BIG = 200L * 1_000_000
    /** the biggest part Desktop may ask for (its own is 256 KB; the bridge's frame cap is 1 MB after base64) */
    const val MAX_PART = 512 * 1024

    /** every managed package, its pin and the Thor's want now */
    fun managed(all: List<ThirdPartyAddons.Addon> = ThirdPartyAddons.ALL, wanted: (ThirdPartyAddons.Addon, String) -> Boolean = ThirdPartyAddons::wanted): JSONArray {
        val out = JSONArray()
        for (a in all) for ((key, p) in a.pkgs()) {
            if (a.repo.isEmpty() && p.url == null) continue                 // no source Desktop could take it from
            out.put(JSONObject().put("id", a.id).put("key", key).put("title", a.title).put("tag", p.tag).put("asset", p.asset)
                .put("bytes", p.bytes).put("sha256", p.sha256).put("folders", JSONArray(p.folders)).put("license", a.license)
                .put("url", p.url ?: "").put("want", runCatching { wanted(a, key) }.getOrDefault(false)))
        }
        return out
    }

    /**
     * On / off of [folders] in the Thor's AddOns.txt files (WTF/Account/<account>/AddOns.txt and each character's): on when any file says
     * "enabled", off when every file that names it says "disabled", left out when none names it (the game's default: on). Counts nothing
     * else; names are never logged.
     */
    fun enabled(flavorRoot: File?, folders: Set<String>): Map<String, Boolean> {
        val account = File(flavorRoot ?: return emptyMap(), "WTF/Account")
        if (!account.isDirectory || folders.isEmpty()) return emptyMap()
        val on = HashSet<String>(); val off = HashSet<String>()
        val lower = folders.associateBy { it.lowercase() }
        fun dirs(d: File) = d.listFiles()?.filter { it.isDirectory && !it.name.equals("SavedVariables", true) }.orEmpty()
        fun read(f: File) {
            if (!f.isFile) return
            runCatching { f.readText(Charsets.ISO_8859_1) }.getOrNull()?.lineSequence()?.forEach { l ->
                val m = LINE.matchEntire(l.trimEnd('\r')) ?: return@forEach
                val name = lower[m.groupValues[1].trim().lowercase()] ?: return@forEach
                if (m.groupValues[2] == "enabled") on += name else off += name
            }
        }
        for (a in dirs(account)) { read(File(a, "AddOns.txt")); for (r in dirs(a)) for (c in dirs(r)) read(File(c, "AddOns.txt")) }
        return folders.filter { it in on || it in off }.associateWith { it in on }
    }
    private val LINE = Regex("""^(?:ï»¿)?[ \t]*([^:]+?)[ \t]*:[ \t]*(enabled|disabled)[ \t]*$""")

    data class FileSum(val p: String, val n: Long, val h: String)

    /** [dir]'s files (no link followed), '/' paths, each one's sha256 - taken from [cache] while its size and time are unchanged */
    fun hashes(dir: File, cache: JSONObject = JSONObject()): List<FileSum> {
        val out = ArrayList<FileSum>()
        val root = dir.canonicalFile
        fun walk(d: File, rel: String) {
            for (c in d.listFiles()?.sortedBy { it.name } ?: return) {
                if (runCatching { java.nio.file.Files.isSymbolicLink(c.toPath()) }.getOrDefault(true)) continue
                val r = if (rel.isEmpty()) c.name else "$rel/${c.name}"
                if (c.isDirectory) { walk(c, r); continue }
                if (!c.isFile || !c.canonicalPath.startsWith(root.path + File.separator)) continue
                val k = "${dir.name}/$r"
                val was = cache.optJSONObject(k)
                val h = if (was != null && was.optLong("n") == c.length() && was.optLong("t") == c.lastModified()) was.optString("h")
                    else sha256(c).also { cache.put(k, JSONObject().put("n", c.length()).put("t", c.lastModified()).put("h", it)) }
                out.add(FileSum(r, c.length(), h))
            }
        }
        walk(dir, "")
        return out
    }

    /** the content's one hash (Desktop's PcImport.Tree): sha256 over the sorted "path\nsize\nsha256\n" lines */
    fun tree(files: List<FileSum>): String = sha256Text(files.sortedBy { it.p }.joinToString("") { "${it.p}\n${it.n}\n${it.h}\n" })

    /** the imported add-ons in the game's AddOns, each with its files; [allowed] = the player's OK for a big one */
    fun imported(addons: File, folders: List<String>, on: Map<String, Boolean>, cache: JSONObject, allowed: (String) -> Boolean): JSONArray {
        val out = JSONArray()
        for (f in folders) {
            val d = File(addons, f)
            if (!ImportedAddons.validFolder(f) || ImportedAddons.protectedName(f) || !d.isDirectory) continue
            val files = runCatching { hashes(d, cache) }.getOrNull() ?: continue
            if (files.isEmpty() || files.size > ImportedAddons.MAX_ENTRIES) continue
            val bytes = files.sumOf { it.n }
            val ver = DependencyAddons.installedVersion(d)
            out.put(JSONObject().put("folder", f).put("version", ver ?: "").put("enabled", on[f] ?: true)
                .put("big", bytes > BIG).put("allowed", bytes <= BIG || allowed(f)).put("bytes", bytes)
                .put("files", JSONArray(files.map { JSONObject().put("p", it.p).put("n", it.n).put("h", it.h) })))
        }
        return out
    }

    /**
     * The whole "addons_want". [flavorRoot] = the Thor's game folder (null: no imported ones, no on / off); [stateDir] = where the hash
     * cache and the big-add-on OKs live (the add-ons' private folder); [acct] = the account's hash (SvSync's; "" = not known).
     */
    fun build(flavorRoot: File?, stateDir: File?, acct: String,
              importedFolders: List<String> = runCatching { ImportedAddons.list().map { it.folder } }.getOrDefault(emptyList())): JSONObject {
        val managed = managed()
        val ownFolders = HashSet<String>()
        for (i in 0 until managed.length()) managed.optJSONObject(i)?.optJSONArray("folders")?.let { a -> for (j in 0 until a.length()) ownFolders += a.optString(j) }
        // the folders a package really brought (Polestar: its guides, databases and names too), as the Thor's own state recorded them
        for (a in ThirdPartyAddons.ALL) for ((k, _) in a.pkgs()) ownFolders += runCatching { ThirdPartyAddons.folders(a, k) }.getOrDefault(emptyList())
        ownFolders += "QuestieDB"
        val on = enabled(flavorRoot, ownFolders + importedFolders)
        val cacheFile = stateDir?.let { File(it, "pc_hashes.json") }
        val cache = runCatching { JSONObject(cacheFile!!.readText()) }.getOrDefault(JSONObject())
        val imp = flavorRoot?.let { imported(File(it, "Interface/AddOns"), importedFolders, on, cache) { f -> allowed(stateDir, f) } } ?: JSONArray()
        runCatching { cacheFile?.let { it.parentFile?.mkdirs(); it.writeText(cache.toString()) } }
        val en = JSONObject(); for ((k, v) in on) en.put(k, v)
        val out = JSONObject().put("t", "addons_want").put("v", 1).put("acct", acct)
            .put("questiedb", JSONObject().put("want", true)).put("managed", managed).put("imported", imp).put("enabled", en)
        fit(out)
        return out
    }

    /** the bridge's frame cap (Desktop: 1 MiB per message): the biggest file lists go (Desktop then skips that add-on) until it fits */
    const val MAX_WANT = 900_000
    internal fun fit(out: JSONObject) {
        val imp = out.optJSONArray("imported") ?: return
        while (out.toString().length > MAX_WANT) {
            val big = (0 until imp.length()).mapNotNull { imp.optJSONObject(it) }.filter { (it.optJSONArray("files")?.length() ?: 0) > 0 }
                .maxByOrNull { it.optJSONArray("files")!!.length() } ?: break
            big.put("files", JSONArray()).put("too_many", true)
        }
    }

    // ---------------------------------------------------------------- the player's OK for a big imported add-on (asked once)
    private fun okFile(stateDir: File?) = stateDir?.let { File(it, "pc_copy_ok.json") }
    fun allowed(stateDir: File?, folder: String): Boolean = runCatching { JSONObject(okFile(stateDir)!!.readText()).optBoolean(folder) }.getOrDefault(false)
    fun allowBig(stateDir: File?, folder: String, yes: Boolean) {
        val f = okFile(stateDir) ?: return
        val j = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        j.put(folder, yes); f.parentFile?.mkdirs(); f.writeText(j.toString())
    }

    // ---------------------------------------------------------------- Desktop's "addon_file_get"
    /**
     * One part of an imported add-on's file for Desktop: only a folder on the Imported list ([importedFolders]), only a plain path inside
     * that folder (no "..", no link, canonical check), at most [MAX_PART] bytes. "addon_file" with the bytes, or "addon_file_err".
     */
    fun serve(req: JSONObject, addons: File?, importedFolders: Set<String>): JSONObject {
        val id = req.optString("id")
        fun err(why: String) = JSONObject().put("t", "addon_file_err").put("id", id).put("why", why)
        val folder = req.optString("folder"); val p = req.optString("p")
        val off = req.optLong("off", -1); val len = req.optInt("len", -1)
        if (addons == null) return err("no game folder")
        if (folder !in importedFolders || !ImportedAddons.validFolder(folder) || ImportedAddons.protectedName(folder)) return err("not imported here")
        if (p.isEmpty() || p.startsWith("/") || p.contains('\\') || p.contains(':') || p.split('/').any { it.isEmpty() || it == "." || it == ".." })
            return err("bad path")
        if (off < 0 || len <= 0 || len > MAX_PART) return err("bad range")
        val root = File(addons, folder).canonicalFile
        val f = File(root, p)
        if (!f.canonicalPath.startsWith(root.path + File.separator) || !f.isFile ||
            runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(true)) return err("no such file")
        return runCatching {
            RandomAccessFile(f, "r").use { r ->
                if (off > r.length()) return err("bad range")
                val n = minOf(len.toLong(), r.length() - off).toInt()
                val b = ByteArray(n); r.seek(off); r.readFully(b)
                JSONObject().put("t", "addon_file").put("id", id).put("folder", folder).put("p", p).put("off", off)
                    .put("data", java.util.Base64.getEncoder().encodeToString(b))
            }
        }.getOrElse { err(it.javaClass.simpleName) }
    }

    // ---------------------------------------------------------------- Desktop's "addons_pc": the Thor's line
    /**
     * The status line for Desktop's add-on run, null = nothing to say (all up to date). Terse: progress while it works; when done, only
     * what is not in, QuestieDB named first (the user: "questie especially").
     */
    fun statusLine(o: JSONObject): String? {
        if (o.optString("state") == "working") {
            val total = o.optLong("total"); val done = o.optLong("done")
            val pct = if (total > 0) " ${(done * 100 / total).coerceIn(0, 100)}%" else ""
            return "PC add-ons: ${o.optString("line")}$pct"
        }
        val items = o.optJSONArray("items") ?: return null
        val bad = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            .filter { it.optString("state") in setOf("failed", "deferred", "refused", "waiting") }
            .sortedBy { if (it.optString("name") == "QuestieDB") 0 else 1 }
        if (bad.isEmpty()) return null
        val first = bad.first()
        val more = if (bad.size > 1) " (+${bad.size - 1} more)" else ""
        val word = when (first.optString("state")) { "deferred" -> "waits"; "waiting" -> "waits"; else -> "not installed" }
        return "PC add-ons: ${first.optString("name")} $word (${first.optString("why")})$more"
    }

    fun sha256(f: File): String = DependencyAddons.sha256(f)
    private fun sha256Text(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
