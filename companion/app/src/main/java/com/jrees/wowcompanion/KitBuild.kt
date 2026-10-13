package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * UI ART'S SHARE OF THE ART HARVEST (UI Art, 2026-10-09; the user: the APK carries no game art, the phone builds it from the user's own
 * game files before Play). The PC tools ported, one for one, reading OUR recipes (assets/harvest/<step>.json, written from the PC tools' own
 * tables by tools/harvest/export_recipes.py in the add-on repo):
 *   [kit]   extract_kit.py (fonts/, ui/kit/: textures, crops, scenes, by-id pictures, atlas members by name and pattern, cuts, scales,
 *           copies, manifest.json), minimap_kit.py (the map pins, round cuts in the gold ring), extract_ui.py (ui/: the bags and paper
 *           doll), home_kit.py (the Home button's stone: [HomeStone]), and my own pictures copied from the APK (the swirl, the noise)
 *   [cast]  extract_cast_art.py (ui/cast/: the controller HUD's pieces by atlas ELEMENT name, the largest member, + manifest.json)
 *   [icons] build_pak.py (icons.pak, WCI1: every icon at 64 px, WebP, through [encode])
 * The same relative paths as the PC tools wrote under /sdcard/WowCompanion, now under the harvest's private folder. Pure Kotlin (no
 * android.*): the steps ([KitSteps]) hand it the game reader and the writer; tools/harvest_test runs it on the PC against the PC tools'
 * own pictures.
 */
class KitBuild(
    private val read: (Int) -> ByteArray?,
    private val write: (String, ByteArray) -> Unit,
    private val asset: (String) -> ByteArray?,
    private val progress: (Int, Int, String) -> Unit = { _, _, _ -> },
    private val log: (String) -> Unit = {},
    /** why the last [read] came back null (GameFiles.lastReadWhy), shown in the error so a player can see the cause */
    private val why: () -> String? = { null },
) {
    /** Missing recipe outputs; required steps call [requireComplete] before accepting their stamp. */
    val missing = ArrayList<String>()
    var written = 0
        private set

    fun requireComplete(step: String) {
        check(missing.isEmpty()) { "$step has ${missing.size} missing or unreadable pictures: ${missing.take(8).joinToString("; ")}" }
    }

    fun texture(fid: Int): KitImage? = read(fid)?.let { Blp.decode(it) }?.let { KitImage.of(it) }

    /** the pictures a later copy needs (MEMBER_ALSO's sources), by kit name */
    private val keep = HashMap<String, ByteArray>()
    private var keepNames = emptySet<String>()

    private fun png(rel: String, im: KitImage, rgb: Boolean = false) {
        val b = KitPng.encode(im, rgb)
        write(rel, b); written++
        val name = rel.substringAfterLast('/').removeSuffix(".png")
        if (name in keepNames) keep[name] = b
    }

    // ------------------------------------------------------------------------------------------------ the atlas tables

    class Rect(val fid: Int, val l: Int, val t: Int, val r: Int, val b: Int, val element: String, val member: String) {
        val area get() = (r - l) * (b - t)
    }

    /**
     * The client's atlas tables (UiTextureAtlas / -Member / -Element), as the PC tools read them: [members] = extract_kit's map (member
     * name, lower case, the last row wins), [elements] = extract_cast_art's (element names win, the largest member of an element; member
     * names as aliases, the first row wins)
     */
    class Atlas(val members: Map<String, Rect>, val elements: Map<String, Rect>)

    private fun because() = why()?.let { ": $it" }.orEmpty()

    fun atlas(db: JSONObject): Atlas {
        fun table(key: String): Db2 {
            val fid = db.optInt(key)
            val b = read(fid) ?: error("the game's atlas table $key ($fid) did not read${because()}")
            return Db2(b, key)
        }
        val tex = HashMap<Int, Int>()
        for (r in table("atlas").rows()) tex[r.id] = r.int(1)
        val elem = HashMap<Int, String>()
        runCatching { for (r in table("element").rows(setOf(0))) elem[r.id] = r.str(0) }
            .onFailure { log("atlas elements unreadable (${it.message}): cast pieces by member name only") }
        val members = HashMap<String, Rect>()
        val byElem = HashMap<String, Rect>(); val byMember = HashMap<String, Rect>()
        for (r in table("member").rows(setOf(0))) {
            // f0 name, f2 atlas id, f3 width, f4 height, f5 left, f6 right, f7 top, f8 bottom, f9 element id
            val fid = tex[r.int(2)] ?: continue
            if (r.int(6) - r.int(5) != r.int(3) || r.int(8) - r.int(7) != r.int(4)) continue
            val name = r.str(0)
            val en = elem[r.int(9)]?.takeIf { it.isNotEmpty() } ?: name
            val rec = Rect(fid, r.int(5), r.int(7), r.int(6), r.int(8), en, name)
            members[name.lowercase()] = rec
            val k = en.lowercase()
            val old = byElem[k]
            if (old == null || rec.area > old.area) byElem[k] = rec
            byMember.putIfAbsent(name.lowercase(), rec)
        }
        return Atlas(members, HashMap(byMember).apply { putAll(byElem) })
    }

    /**
     * Cuts many atlas pieces with each game sheet decoded once and let go before the next (the sheets are up to 2048 px: kept all at
     * once they would not fit the app's memory). [jobs]: (piece, what to do with the cut).
     */
    private fun cutAll(jobs: List<Pair<Rect, (KitImage) -> Unit>>, what: String, done: () -> Unit) {
        for ((fid, group) in jobs.groupBy { it.first.fid }.toSortedMap()) {
            val sheet = texture(fid)
            for ((r, use) in group) {
                if (sheet == null) missing.add("$what: atlas texture $fid (${r.member})")
                else use(sheet.crop(r.l, r.t, r.r, r.b))
                done()
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ kit

    fun kit(recipe: JSONObject) {
        val files = JSONObject()
        val atlas = atlas(recipe.optJSONObject("db2") ?: error("kit.json: db2 missing"))
        // a control that must read, so that a missing piece below means something (extract_kit.py's)
        if (read(136048) == null) error("the control icon 136048 did not read: the game's files are not readable${because()}")

        val fonts = recipe.obj("fonts"); val textures = recipe.obj("textures"); val online = recipe.obj("online")
        val crops = recipe.obj("crops"); val scenes = recipe.obj("scenes"); val byId = recipe.obj("byId")
        val mm = recipe.obj("minimap"); val ui = recipe.obj("ui"); val home = recipe.obj("home")
        val orig = recipe.optJSONArray("orig") ?: JSONArray()

        // the atlas pieces wanted: by name, then every name a pattern takes (extract_kit.py's order: a name already wanted keeps its file)
        keepNames = HashSet<String>().apply { recipe.obj("also").forEach { _, src -> add(src as String) } }
        val wanted = LinkedHashMap<String, String>()
        recipe.obj("members").forEach { k, v -> wanted[k] = v as String }
        val patterns = recipe.optJSONArray("patterns") ?: JSONArray()
        val sortedNames = atlas.members.keys.sorted()
        for (i in 0 until patterns.length()) {
            val p = patterns.optJSONArray(i) ?: continue
            val rx = Regex(p.optString(0))
            val template = p.optString(1).replace(Regex("""\\(\d)"""), "\\$$1")
            for (m in sortedNames) if (rx.containsMatchIn(m) && m !in wanted) wanted[m] = rx.replace(m, template)
        }
        val mmWanted = LinkedHashMap<String, String>()
        mm.obj("members").forEach { k, v -> mmWanted[k] = v as String }
        mm.obj("profs").forEach { k, v -> mmWanted[k] = v as String }
        val mmRound = mm.obj("profsRound"); val mmTex = mm.obj("textures"); val mmObj = mm.obj("objectIcons"); val mmRoundIcons = mm.obj("round")
        val uiTex = ui.obj("textures"); val uiCrops = ui.obj("crops")

        val total = fonts.length() + textures.length() + online.length() + crops.length() + scenes.length() + byId.length() + wanted.size +
            mmWanted.size + mmRound.length() + mmTex.length() + mmObj.length() + mmRoundIcons.length() + uiTex.length() + uiCrops.length() +
            8 + orig.length()
        var done = 0
        fun step(what: String) { done++; if (done % 8 == 0 || done == total) progress(done, total, what) }

        fonts.forEach { fid, name ->
            val b = read(fid.toInt())
            if (b == null) missing.add("font $fid $name") else { write("fonts/$name", b); written++ }
            step("the fonts")
        }
        fun whole(src: JSONObject, kind: String, dir: String, keep: Boolean) = src.forEach { fid, name ->
            val im = texture(fid.toInt())
            if (im == null) missing.add("$kind $fid $name")
            else { png("$dir/$name.png", im); if (keep) files.put(name as String, JSONArray().put(im.w).put(im.h)) }
            step("the game's pictures")
        }
        whole(textures, "texture", "ui/kit", true)
        whole(online, "online", "ui/kit", true)
        crops.forEach { name, v ->
            val c = v as JSONArray
            val im = texture(c.optInt(0))?.crop(c.optInt(1), c.optInt(2), c.optInt(3), c.optInt(4))
            if (im == null) missing.add("crop ${c.optInt(0)} $name") else { png("ui/kit/$name.png", im); files.put(name, size(im)) }
            step("the game's pictures")
        }
        scenes.forEach { fid, focus ->
            val im0 = texture(fid.toInt())
            if (im0 == null) missing.add("scene $fid") else {
                val painting = im0.opaque().crop(0, 272, im0.w, 1412)
                val sc = painting.resize((painting.w * 1080.0 / painting.h).toInt(), 1080)
                val x = ((sc.w - 1240) * (focus as Number).toDouble()).toInt()
                val im = sc.crop(x, 0, x + 1240, 1080)
                png("ui/kit/scene_$fid.png", im, rgb = true); files.put("scene_$fid", size(im))
            }
            step("the loading pictures")
        }
        byId.forEach { fid, what ->
            val im = texture(fid.toInt())
            if (im == null) missing.add("by id $fid $what") else { png("ui/kit/fid_$fid.png", im); files.put("fid_$fid", size(im)) }
            step("the game's pictures")
        }

        // atlas members, cut, scaled, kept
        val cuts = recipe.obj("cuts"); val scale = recipe.obj("scale")
        val jobs = ArrayList<Pair<Rect, (KitImage) -> Unit>>()
        for ((member, name) in wanted) {
            val r = atlas.members[member.lowercase()]
            if (r == null) { missing.add("atlas member $member"); step("the frames"); continue }
            jobs.add(r to { cut: KitImage ->
                var im = cut
                cuts.optJSONArray(name)?.let { c ->
                    val cr = c.optInt(2); val cb = c.optInt(3)
                    im = im.crop(c.optInt(0), c.optInt(1), if (cr <= 0) im.w + cr else cr, if (cb <= 0) im.h + cb else cb)
                }
                scale.forEach { pre, sc ->
                    if (name.startsWith(pre)) {
                        val s = (sc as Number).toDouble()
                        im = im.resize(Math.rint(im.w * s).toInt(), Math.rint(im.h * s).toInt())
                    }
                }
                png("ui/kit/$name.png", im); files.put(name, size(im))
            })
        }
        cutAll(jobs, "kit") { step("the frames") }
        recipe.obj("also").forEach { also, src ->
            val f = files.optJSONArray(src as String)
            val b = keep[src]
            if (f != null && b != null) { write("ui/kit/$also.png", b); written++; files.put(also, JSONArray().put(f.optInt(0)).put(f.optInt(1))) }
            else missing.add("copy $also from $src")
        }
        keep.clear()

        // the map pins (minimap_kit.py)
        val size = mm.optInt("size", 64)
        val trim = (0 until (mm.optJSONArray("trim")?.length() ?: 0)).map { mm.optJSONArray("trim")!!.optString(it) }.toSet()
        val mmJobs = ArrayList<Pair<Rect, (KitImage) -> Unit>>()
        for ((name, m) in mmWanted) {
            val r = atlas.members[m.lowercase()]
            if (r == null) { missing.add("map pin $m"); step("the map pins"); continue }
            mmJobs.add(r to { cut: KitImage ->
                var im = cut
                if (name in trim) im.alphaBox(24)?.let { b -> im = im.crop(b[0], b[1], b[2], b[3]) }
                png("ui/kit/$name.png", im)
            })
        }
        cutAll(mmJobs, "map pins") { step("the map pins") }
        val ringRect = atlas.members[mm.optString("ringFrom").lowercase()]
        val ring = ringRect?.let { r -> texture(r.fid)?.crop(r.l, r.t, r.r, r.b) }
        if (ring == null) missing.add("the gold ring ${mm.optString("ringFrom")}: no round pins")
        mmRound.forEach { name, m ->
            val r = atlas.members[(m as String).lowercase()]
            val im = r?.let { texture(it.fid)?.crop(it.l, it.t, it.r, it.b) }
            if (im == null || ring == null) missing.add("map pin $m") else png("ui/kit/$name.png", ringCut(im, ring, size))
            step("the map pins")
        }
        mmTex.forEach { name, fid ->
            val im = texture((fid as Number).toInt())
            if (im == null) missing.add("map pin $fid") else png("ui/kit/$name.png", im)
            step("the map pins")
        }
        val oi = texture(mm.optInt("objectIconsFid", 136435))
        mmObj.forEach { name, rc ->
            val c = rc as JSONArray
            if (oi == null) missing.add("ObjectIcons for $name") else {
                val cw = oi.w / 8; val row = c.optInt(0); val col = c.optInt(1)
                png("ui/kit/$name.png", oi.crop(col * cw, row * cw, (col + 1) * cw, (row + 1) * cw))
            }
            step("the map pins")
        }
        mmRoundIcons.forEach { name, fid ->
            val im = texture((fid as Number).toInt())
            if (im == null || ring == null) missing.add("map pin icon $fid") else png("ui/kit/$name.png", ringCut(im, ring, size))
            step("the map pins")
        }

        // the bags and the paper doll (extract_ui.py: ui/<name>.png)
        whole(uiTex, "bags", "ui", false)
        uiCrops.forEach { name, v ->
            val c = v as JSONArray
            val im = texture(c.optInt(0))?.crop(c.optInt(1), c.optInt(2), c.optInt(3), c.optInt(4))
            if (im == null) missing.add("bags crop $name") else png("ui/$name.png", im)
            step("the bags")
        }

        // the Home button's stone (home_kit.py): my layers + the game's stone tile and cracked ground
        val gz = asset("harvest/home.bin.gz")
        val tileR = atlas.members[home.optString("tile").lowercase()]; val crackR = atlas.members[home.optString("cracked").lowercase()]
        val tile = tileR?.let { r -> texture(r.fid)?.crop(r.l, r.t, r.r, r.b) }
        val cracked = crackR?.let { r -> texture(r.fid)?.crop(r.l, r.t, r.r, r.b) }
        if (gz == null) {
            for (name in OWN_STONE) {
                val bytes = asset("harvest/own/$name.png")
                if (bytes == null) missing.add("own picture $name")
                else { write("ui/kit/$name.png", bytes); written++ }
            }
        }
        else if (tile == null || cracked == null) missing.add("the Home stone (game textures)")
        else {
            progress(done, total, "the Home button")
            for ((name, pic) in HomeStone.pieces(home, HomeStone.layers(gz, home), tile, cracked)) png("ui/kit/$name.png", pic.first, pic.second)
        }
        done += 8; progress(done, total, "the Home button")

        // my own pictures, as they are
        for (i in 0 until orig.length()) {
            val name = orig.optString(i)
            val b = asset("harvest/orig/$name.png")
            if (b == null) missing.add("own picture $name") else { write("ui/kit/$name.png", b); written++ }
            step("the Home button")
        }

        val man = JSONObject().put("v", 1).put("files", files)
        if (missing.isNotEmpty()) man.put("missing", JSONArray(missing))
        write("ui/kit/manifest.json", man.toString().toByteArray())
        log("kit: $written files, ${missing.size} missing" + if (missing.isEmpty()) "" else ": " + missing.joinToString("; "))
    }

    /** minimap_kit.py's ring_cut: [icon] cut round and set inside the bag filters' gold ring, [size] px */
    fun ringCut(icon: KitImage, ring: KitImage, size: Int): KitImage {
        val k = 4
        val big = size * k
        val scale = big / 50.0
        val rIn = 15.5 * scale; val rOut = 21.0 * scale
        val rg = ring.resize(big, big)
        val hole = Plane.ellipse(big, big, big / 2 - rIn, big / 2 - rIn, big / 2 + rIn, big / 2 + rIn, fill = 0, ground = 255)
        val ra = rg.alpha()
        val rgCut = rg.withAlpha(Plane(big, big, IntArray(big * big) { if (hole.v[it] == 255) ra.v[it] else hole.v[it] }))
        val w = icon.w; val h = icon.h
        var inner = icon.crop((w * 0.08).toInt(), (h * 0.08).toInt(), (w * 0.92).toInt(), (h * 0.92).toInt())
        val d = (2 * (rIn + 2 * k)).toInt()
        inner = inner.resize(d, d)
        val mask = Plane.ellipse(d, d, 0.0, 0.0, (d - 1).toDouble(), (d - 1).toDouble())
        val out = KitImage.blank(big, big)
        out.paste(inner, (big - d) / 2, (big - d) / 2, mask)
        out.compositeOver(rgCut)
        val c = (big / 2 - rOut - k).toInt()
        return out.crop(c, c, big - c, big - c).resize(size, size)
    }

    // ------------------------------------------------------------------------------------------------ cast

    fun cast(recipe: JSONObject, db: JSONObject) {
        val atlas = atlas(db)
        val wanted = recipe.obj("wanted")
        val man = JSONObject()
        val jobs = ArrayList<Pair<Rect, (KitImage) -> Unit>>()
        val total = wanted.length(); var done = 0
        wanted.forEach { fn, v ->
            val o = v as JSONObject
            val names = o.optJSONArray("names") ?: JSONArray()
            var hit: Rect? = null
            for (i in 0 until names.length()) { hit = atlas.elements[names.optString(i).lowercase()]; if (hit != null) break }
            if (hit == null) { missing.add("cast $fn <- " + (0 until names.length()).joinToString(" | ") { names.optString(it) }); done++; return@forEach }
            val r: Rect = hit
            jobs.add(r to { piece: KitImage ->
                png("ui/cast/$fn.png", piece)
                val ink = piece.alphaBox(24)
                val e = JSONObject().put("atlas", r.element).put("w", r.r - r.l).put("h", r.b - r.t).put("purpose", o.optString("purpose"))
                    .put("ink", if (ink == null) JSONObject.NULL else JSONArray().put(ink[0]).put(ink[1]).put(ink[2]).put(ink[3]))
                if (!r.member.equals(r.element, ignoreCase = true)) e.put("member", r.member)
                man.put("$fn.png", e)
            })
        }
        cutAll(jobs, "cast") { done++; if (done % 8 == 0 || done == total) progress(done, total, "the controller's pieces") }
        write("ui/cast/manifest.json", man.toString().toByteArray())
        log("cast: $written files, ${missing.size} missing" + if (missing.isEmpty()) "" else ": " + missing.joinToString("; "))
    }

    // ------------------------------------------------------------------------------------------------ icons

    /**
     * build_pak.py: every icon in the recipe, at [tile] px, through [encode] (WebP on the phone), into [out] (icons.pak, WCI1), written
     * to out.part first and renamed when whole. Returns the number of icons packed.
     */
    fun icons(recipe: JSONObject, out: File, lenient: Boolean = false, encode: (KitImage, Int) -> ByteArray?): Int {
        val tile = recipe.optInt("tile", 64); val q = recipe.optInt("quality", 80)
        val arr = recipe.optJSONArray("fids") ?: JSONArray()
        val fids = IntArray(arr.length()) { arr.optInt(it) }.sorted()
        val blobs = File(out.path + ".blobs")
        val ids = ArrayList<Int>(fids.size); val lens = ArrayList<Int>(fids.size)
        var miss = 0; var bad = 0
        // decoded and encoded on a few threads (the reads queue on the game's one handle), packed in fileDataID order
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        try {
            blobs.outputStream().buffered(1 shl 16).use { o ->
                var done = 0
                for (chunk in fids.toList().chunked(256)) {
                    val jobs = chunk.map { fid ->
                        pool.submit(Callable<Any?> {
                            val d = read(fid) ?: return@Callable null
                            var im = Blp.decode(d)?.let { KitImage.of(it) } ?: return@Callable false
                            if (im.w != tile || im.h != tile) im = im.resize(tile, tile)
                            encode(im, q) ?: false
                        })
                    }
                    for ((k, j) in jobs.withIndex()) {
                        when (val r = j.get()) {
                            null -> { miss++; missing.add("icon ${chunk[k]} not readable") }
                            is ByteArray -> { o.write(r); ids.add(chunk[k]); lens.add(r.size) }
                            else -> { bad++; missing.add("icon ${chunk[k]} undecodable") }
                        }
                    }
                    done += chunk.size
                    progress(done, fids.size, "the icons")
                }
            }
        } finally { pool.shutdown() }
        val count = ids.size
        log("icons: $count packed, $miss not in the game's files, $bad undecodable" +
            if (missing.isEmpty()) "" else ": ${missing.joinToString("; ")}")
        // [lenient] (Track C, the background all-icons step): an icon the game lacks or cannot decode is left out, never a failure
        if (missing.isNotEmpty() && lenient) missing.clear()
        if (missing.isNotEmpty()) {
            blobs.delete()
            requireComplete("icons")
        }
        val base = 12 + 4 * count + 4 * (count + 1)
        val part = File(out.path + ".part")
        RandomAccessFile(part, "rw").use { f ->
            f.setLength(0)
            val head = java.nio.ByteBuffer.allocate(base).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            head.put("WCI1".toByteArray()); head.putInt(count); head.putInt(base)
            for (id in ids) head.putInt(id)
            var off = 0; head.putInt(0)
            for (l in lens) { off += l; head.putInt(off) }
            f.write(head.array())
            blobs.inputStream().buffered(1 shl 16).use { i ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = i.read(buf); if (n < 0) break; f.write(buf, 0, n) }
            }
        }
        blobs.delete()
        check(part.renameTo(out)) { "icons.pak could not be published; the earlier pack was preserved" }
        written++
        return count
    }

    companion object {
        fun withHolidays(recipe: JSONObject, table: Db2): JSONObject {
            check(table.layoutHash == 0x432931C0 && table.fieldCount == 12) { "unsupported Holidays table layout" }
            val rows = table.rows(arrays = setOf(11))
            check(rows.isNotEmpty()) { "the game's holiday table has no readable rows" }
            val ids = rows.flatMap { row ->
                val textures = row.ints(11)
                check(textures.size == 3) { "holiday ${row.id}: expected three texture IDs" }
                textures.filter { it > 0 }
            }.toSortedSet()
            check(ids.isNotEmpty()) { "the game's holiday table names no pictures" }
            val resolved = JSONObject(recipe.toString())
            val byId = JSONObject()
            recipe.obj("byId").forEach { id, purpose -> if (purpose != "calendar holiday") byId.put(id, purpose) }
            for (id in ids) byId.put(id.toString(), "calendar holiday")
            resolved.put("byId", byId)
            return resolved
        }

        private val OWN_STONE = listOf("home_live_base", "home_live_flare", "home_live_red_base", "home_live_red_flare",
            "home_live_gold_base", "home_live_gold_flare", "home_stone_rough", "home_stone_rough_b")
        private fun size(im: KitImage) = JSONArray().put(im.w).put(im.h)
        private fun JSONObject.obj(k: String): JSONObject = optJSONObject(k) ?: JSONObject()
        private inline fun JSONObject.forEach(f: (String, Any) -> Unit) {
            val ks = ArrayList<String>(); val it = keys(); while (it.hasNext()) ks.add(it.next())
            for (k in ks) f(k, opt(k) ?: continue)
        }
    }
}
