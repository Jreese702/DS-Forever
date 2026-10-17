package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * MAP'S ART HARVEST STEPS (the user 2026-10-09, phase 1; [ArtHarvest]'s contract, Hub's): what the Map room and Home's map draw, built
 * on the device from the user's own game files into the app's private art folder, in the layout the PC chain wrote (the readers are
 * unchanged). The plans are [MapHarvest]'s (pure, tested off-device); this file reads, decodes, draws and writes.
 *   [WORLD_MAP]  "worldmap", before Play: worldmap/<uiMapID>/{base.webp, ov_<id>.webp, index.json}, worldmap/hl/<fid>.webp, manifest
 *   [POI_ICONS]  "poiicons", before Play: ui/poi_icons.png (the map's service icons, one 64 px cell each)
 *   [MINIMAP]    "minimap", in the background, nearest zone first: minimap/<continent>/<col>_<row>.webp + minimap/index.json
 * Our recipes (assets/harvest): worldmap.json (each map's world rect), poiicons.json (the strip's cells).
 */
object MapHarvestSteps {

    /**
     * Where the player is, for the minimap step's nearest-first order: (uiMapID, world X north, world Y west), from the last map / pos
     * document (Hub passes it). Null = continents in id order.
     */
    @Volatile var near: Triple<Int, Double, Double>? = null

    private fun rects(job: ArtHarvest.Job): Map<Int, MapHarvest.Rect> {
        val out = LinkedHashMap<Int, MapHarvest.Rect>()
        val o = job.recipe("worldmap").optJSONObject("rects") ?: return out
        for (k in o.keys()) {
            val a = o.optJSONArray(k) ?: continue
            if (a.length() < 5) continue
            out[k.toIntOrNull() ?: continue] = MapHarvest.Rect(a.optInt(0), a.optDouble(1), a.optDouble(2), a.optDouble(3), a.optDouble(4))
        }
        return out
    }

    private fun Bitmap.lossless(): ByteArray = ByteArrayOutputStream().also { s ->
        @Suppress("DEPRECATION")
        val fmt = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
        compress(fmt, 100, s)
    }.toByteArray()

    /** tiles at (col * [tile], row * [tile]) on one canvas, cropped to [w] x [h]; null when not one tile could be read */
    private fun stitch(game: GameFiles, tiles: List<MapHarvest.Tile>, tile: Int, w: Int, h: Int): Bitmap? {
        val cols = (w + tile - 1) / tile; val rows = (h + tile - 1) / tile
        val canvas = Bitmap.createBitmap(maxOf(1, cols * tile), maxOf(1, rows * tile), Bitmap.Config.ARGB_8888)
        val cv = Canvas(canvas)
        var got = 0
        for (t in tiles) {
            val b = game.blp(t.fid) ?: continue
            cv.drawBitmap(b, (t.col * tile).toFloat(), (t.row * tile).toFloat(), null)
            b.recycle(); got++
        }
        if (got == 0) { canvas.recycle(); return null }
        if (canvas.width == w && canvas.height == h) return canvas
        val out = Bitmap.createBitmap(canvas, 0, 0, minOf(w, canvas.width), minOf(h, canvas.height))
        if (out !== canvas) canvas.recycle()
        return out
    }

    // ---------------------------------------------------------------------------------------------- the world map set

    val WORLD_MAP = object : ArtHarvest.Step {
        override val id = "worldmap"
        override val version = 1
        override val beforePlay = true
        override val weight = 700                       // ~60 maps, ~600 overlays, ~45 highlights
        override fun run(job: ArtHarvest.Job) {
            val g = job.game
            fun t(fid: Int) = g.db2(fid) ?: error("table $fid unreadable")
            val art = t(MapHarvest.DB_UIMAP_ART)
            val maps = MapHarvest.worldMaps(t(MapHarvest.DB_UIMAP).rows(setOf(0)), t(MapHarvest.DB_UIMAP_X_ART).rows(), art.rows(),
                art.copies(), t(MapHarvest.DB_ART_STYLE_LAYER).rows(), t(MapHarvest.DB_ART_TILE).rows(),
                t(MapHarvest.DB_OVERLAY).rows(), t(MapHarvest.DB_OVERLAY_TILE).rows())
            check(maps.isNotEmpty()) { "no map art in the tables" }
            val rects = rects(job)
            val manifest = JSONObject()
            val hlDone = HashSet<Int>()
            for ((i, m) in maps.withIndex()) {
                job.progress(i, maps.size, m.name)
                val dir = "worldmap/${m.uiMap}"
                stitch(g, m.tiles, m.tile, m.w, m.h)?.let { job.writeWebp("$dir/base.webp", it, 88); it.recycle() }
                val ovs = JSONArray()
                for (o in m.overlays) {
                    val b = stitch(g, o.tiles, MapHarvest.OVERLAY_TILE_PX, o.w, o.h) ?: continue
                    job.writeWebp("$dir/ov_${o.id}.webp", b, 88); b.recycle()
                    ovs.put(JSONObject().put("id", o.id).put("fid", o.fid).put("fids", JSONArray(o.tiles.map { it.fid }))
                        .put("x", o.x).put("y", o.y).put("w", o.w).put("h", o.h).put("hit", JSONArray(o.hit.toList())))
                }
                val idx = JSONObject().put("uiMapID", m.uiMap).put("artID", m.artId).put("name", m.name).put("mapType", m.type)
                    .put("parent", m.parent).put("w", m.w).put("h", m.h).put("overlays", ovs)
                // the zone's highlight picture (drawn ADD on its continent; the box comes from Map.lua's maphl sweep at runtime)
                if (m.hl != 0) {
                    if (m.hl !in hlDone) g.blp(m.hl)?.let { job.writeBytes("worldmap/hl/${m.hl}.webp", it.lossless()); it.recycle(); hlDone.add(m.hl) }
                    if (m.hl in hlDone) idx.put("hl", m.hl)
                }
                rects[m.uiMap]?.let { r -> idx.put("rect", JSONArray(listOf(r.c, r.t, r.b, r.l, r.r))) }
                job.writeBytes("$dir/index.json", idx.toString().toByteArray())
                manifest.put(m.uiMap.toString(), JSONObject().put("name", m.name).put("mapType", m.type).put("parent", m.parent)
                    .put("overlays", ovs.length()))
            }
            job.writeBytes("worldmap/manifest.json", manifest.toString(1).toByteArray())
            job.progress(maps.size, maps.size)
        }
    }

    // ---------------------------------------------------------------------------------------------- the POI icon strip

    val POI_ICONS = object : ArtHarvest.Step {
        override val id = "poiicons"
        override val version = 1
        override val beforePlay = true
        override val weight = 31
        override fun run(job: ArtHarvest.Job) {
            val r = job.recipe()
            val cellPx = r.optInt("cell", 64)
            val list = r.optJSONArray("cells") ?: error("the poiicons recipe has no cells")
            val cells = (0 until list.length()).mapNotNull { i ->
                val o = list.optJSONObject(i) ?: return@mapNotNull null
                MapHarvest.Cell(o.optString("src"), o.optString("name"), o.optInt("row"), o.optInt("col"))
            }
            val sheet = job.game.blpName(r.optString("objectIcons", "Interface\\Minimap\\ObjectIcons.blp"))
                ?: error("ObjectIcons unreadable")
            val cw = sheet.width / 8
            val strip = Bitmap.createBitmap(cellPx * cells.size, cellPx, Bitmap.Config.ARGB_8888)
            val cv = Canvas(strip)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            for ((i, cell) in cells.withIndex()) {
                job.progress(i, cells.size, cell.name)
                val src: Bitmap? = when (cell.src) {
                    "oi" -> Bitmap.createBitmap(sheet, cell.col * cw, cell.row * cw, cw, cw)
                    "ico" -> job.game.blpName("Interface\\Icons\\${cell.name}.blp")
                    "cur" -> job.game.blpName("Interface\\Cursor\\${cell.name}.blp")?.let { cut(it) }
                    "blp" -> job.game.blpName("${cell.name}.blp")
                    else -> null
                }
                src ?: error("POI cell ${cell.src} ${cell.name} unreadable")
                cv.drawBitmap(src, null, android.graphics.RectF(i * cellPx.toFloat(), 0f, (i + 1) * cellPx.toFloat(), cellPx.toFloat()), paint)
                if (src !== sheet) src.recycle()
            }
            sheet.recycle()
            job.writePng("ui/poi_icons.png", strip)
            strip.recycle()
        }

        /** a cursor icon with its pointer tip cut away (the top-left triangle) */
        private fun cut(b: Bitmap): Bitmap {
            val m = if (b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true).also { b.recycle() }
            for (y in 0 until m.height) for (x in 0 until m.width) if (MapHarvest.cursorTip(x, y, m.width)) m.setPixel(x, y, 0)
            return m
        }
    }

    // ---------------------------------------------------------------------------------------------- the map's NPC icons

    /**
     * THE MAP'S NPC ICONS (the user, 2026-10-12, the signed-off picture: the game's own minimap tracking icons, the sack for vendors,
     * the anvil for repair, the class icon with the trainer book, the profession's icon; see NpcIcons): ui/npc_icons.png (one cell a
     * key, the recipe's order) + ui/npc_icons.json ("keys"). Sources by game path (the recipe, assets/harvest/npcicons.json):
     * "track" a tracking icon as it is; "oi" a cell of ObjectIcons; "icon" an Interface\Icons picture made a minimap blip (its frame
     * trimmed, round, a thin dark ring); "classbook" a class icon so, with the trainer book at its bottom right. A texture the game
     * does not have falls back to the cell's "or" path, else the gold dot: never an empty pin.
     */
    val NPC_ICONS = object : ArtHarvest.Step {
        override val id = "npcicons"
        override val version = 1
        override val beforePlay = true
        override val weight = 45

        override fun run(job: ArtHarvest.Job) {
            val r = job.recipe()
            val px = r.optInt("cell", 64)
            val list = r.optJSONArray("cells") ?: error("the npcicons recipe has no cells")
            val sheet = job.game.blpName(r.optString("objectIcons", "Interface\\Minimap\\ObjectIcons.blp"))
                ?: error("NPC ObjectIcons unreadable")
            val book = job.game.blpName(r.optString("trainerBook", "Interface\\Minimap\\Tracking\\Class.blp"))
                ?: error("NPC trainer book unreadable")
            val strip = Bitmap.createBitmap(px * list.length(), px, Bitmap.Config.ARGB_8888)
            val cv = Canvas(strip)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val keys = JSONArray()
            val missing = JSONArray()
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val key = o.optString("key")
                job.progress(i, list.length(), key)
                val cell = android.graphics.RectF(i * px.toFloat(), 0f, (i + 1) * px.toFloat(), px.toFloat())
                fun oi(row: Int, col: Int, scale: Float = 1f): Boolean {
                    val s = sheet
                    val cw = s.width / 8
                    val b = Bitmap.createBitmap(s, col * cw, row * cw, cw, cw)
                    fit(cv, b, cell, scale, paint)
                    if (b !== s) b.recycle()
                    return true
                }
                val path = o.optString("path", "")
                var ok = when (o.optString("src")) {
                    "track" -> job.game.blpName(path)?.let { fit(cv, it, cell, 1f, paint); it.recycle(); true } ?: false
                    "oi" -> oi(o.optInt("row"), o.optInt("col"), o.optDouble("scale", 1.0).toFloat())
                    "icon" -> job.game.blpName(path)?.let { round(cv, it, cell, paint); it.recycle(); true } ?: false
                    "classbook" -> job.game.blpName(path)?.let { cls ->
                        val k = px * 0.82f
                        round(cv, cls, android.graphics.RectF(cell.left, cell.top, cell.left + k, cell.top + k), paint)
                        cls.recycle()
                        fit(cv, book, android.graphics.RectF(cell.right - px * 0.56f, cell.bottom - px * 0.56f, cell.right, cell.bottom), 1f, paint)
                        true
                    } ?: false
                    else -> false
                }
                if (!ok && o.has("or")) ok = job.game.blpName(o.optString("or"))?.let { fit(cv, it, cell, 1f, paint); it.recycle(); true } ?: false
                if (!ok) { missing.put(key); oi(0, 3, 0.75f) }                       // the gold dot: never an empty pin
                keys.put(key)
            }
            sheet.recycle(); book.recycle()
            job.writePng("ui/npc_icons.png", strip)
            strip.recycle()
            job.writeBytes("ui/npc_icons.json", JSONObject().put("keys", keys).put("cell", px).put("missing", missing).toString().toByteArray())
            job.progress(list.length(), list.length())
        }

        /** [b] fitted into [box] (its shape kept), [scale] of it, centred */
        private fun fit(cv: Canvas, b: Bitmap, box: android.graphics.RectF, scale: Float, paint: Paint) {
            val s = scale * minOf(box.width() / b.width, box.height() / b.height)
            val w = b.width * s; val h = b.height * s
            cv.drawBitmap(b, null, android.graphics.RectF(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2), paint)
        }

        /** an Interface\Icons picture as a minimap blip: its frame trimmed (1/12 a side), round, a thin dark ring */
        private fun round(cv: Canvas, b: Bitmap, box: android.graphics.RectF, paint: Paint) {
            val k = b.width / 12
            val inner = Bitmap.createBitmap(b, k, k, b.width - 2 * k, b.height - 2 * k)
            val pad = box.width() * 0.04f
            val r = android.graphics.RectF(box.left + pad, box.top + pad, box.right - pad, box.bottom - pad)
            cv.save()
            val clip = android.graphics.Path().apply { addOval(r, android.graphics.Path.Direction.CW) }
            cv.clipPath(clip)
            cv.drawBitmap(inner, null, r, paint)
            cv.restore()
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = box.width() * 0.06f; color = android.graphics.Color.argb(255, 20, 14, 8)
            }
            cv.drawOval(r, ring)
            if (inner !== b) inner.recycle()
        }
    }

    // ---------------------------------------------------------------------------------------------- the minimap tiles

    val MINIMAP = object : ArtHarvest.Step {
        override val id = "minimap"
        override val version = 2
        override val beforePlay = true
        override val weight = 1500
        private val main = Handler(Looper.getMainLooper())

        override fun run(job: ArtHarvest.Job) {
            val g = job.game
            val rects = rects(job)
            check(rects.isNotEmpty()) { "the worldmap recipe has no rects" }
            val mapRows = (g.db2(MapHarvest.DB_MAP) ?: error("Map.db2 unreadable: ${g.lastReadWhy ?: "unknown reason"}"))
                .rows(setOf(MapHarvest.MAP_DIRECTORY)).associateBy { it.id }
            val maid = HashMap<Int, Map<Int, Int>>()
            val dirs = HashMap<Int, String>()
            for (c in rects.values.map { it.c }.toSortedSet()) {
                val row = mapRows[c] ?: error("Map.db2 has no continent $c")
                val wdt = g.read(row.int(MapHarvest.MAP_WDT))
                    ?: error("continent $c WDT unreadable: ${g.lastReadWhy ?: "unknown reason"}")
                maid[c] = MapHarvest.maidMinimap(wdt)
                    ?: error("continent $c WDT has no minimap tile references")
                dirs[c] = row.str(MapHarvest.MAP_DIRECTORY).lowercase()
            }
            val want = MapHarvest.wanted(rects.values, maid)
            check(want.isNotEmpty()) { "no readable minimap tile references were found" }
            val n = near
            val nearC = n?.let { rects[it.first]?.c }
            val order = MapHarvest.order(want, nearC, n?.second ?: 0.0, n?.third ?: 0.0)
            val names = names(job)
            // RESUME: an interrupted run (the app closed) goes on where it was, for the same game build; a new build makes them all again
            val made = job.file("minimap/made.txt")
            val done = HashSet<String>()
            runCatching { made.readLines() }.getOrNull()?.let { lines ->
                if (lines.firstOrNull() == g.buildKey) done.addAll(lines.drop(1))
            }
            if (done.isEmpty()) made.writeText(g.buildKey + "\n")
            val got = HashMap<Int, MutableList<Int>>()
            var missing = 0
            for ((i, ck) in order.withIndex()) {
                val (c, k) = ck
                val col = MapHarvest.colOf(k); val row = MapHarvest.rowOf(k)
                val name = "$c/${col}_$row"
                if (name !in done) {
                    val b = g.blp(maid[c]!![k]!!)
                    if (b != null) {
                        b.setHasAlpha(false)                  // the PC saved them RGB: opaque
                        job.writeWebp("minimap/$name.webp", b, 82); b.recycle()
                        made.appendText(name + "\n"); done.add(name)
                    }
                }
                if (name in done) got.getOrPut(c) { ArrayList() }.add(k)
                else missing++
                if (i % 20 == 0) {
                    job.progress(i, order.size, MapHarvest.zoneOf(c, k, rects)?.let { names[it] } ?: "")
                    main.post { MinimapTiles.refresh() }      // the map shows what is made so far
                }
            }
            val conts = JSONObject()
            for ((c, ks) in got) conts.put(c.toString(), JSONObject().put("dir", dirs[c] ?: c.toString())
                .put("tiles", JSONArray(ks.sortedWith(compareBy({ MapHarvest.colOf(it) }, { MapHarvest.rowOf(it) }))
                    .map { JSONArray(listOf(MapHarvest.colOf(it), MapHarvest.rowOf(it))) })))
            job.writeBytes("minimap/index.json", JSONObject().put("about", "minimap tiles, 512 px per ADT (533.333 yd), <continentId>/<col>_<row>.webp")
                .put("px", 512).put("adt", MapHarvest.ADT_YD).put("continents", conts).toString().toByteArray())
            main.post { MinimapTiles.refresh() }
            check(missing == 0) { "$missing minimap tiles could not be read; retry to resume" }
            job.progress(order.size, order.size)
        }

        /** the zone names the world-map step wrote (its manifest), for the progress line */
        private fun names(job: ArtHarvest.Job): Map<Int, String> = runCatching {
            val o = JSONObject(File(job.out, "worldmap/manifest.json").readText())
            o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to (o.optJSONObject(k)?.optString("name") ?: "") } }.toMap()
        }.getOrElse { emptyMap() }
    }

    /** every Map step, for [ArtHarvest.STEPS] (Hub registers them) */
    /**
     * [TRANSPORTS] "transports", in the background: harvest/transports.json, the boats' / zeppelins' / trams' rides from the game's own
     * TaxiPathNode table (TransportRoutes; b-route-transports). Also the probe: WCMAP logs how much the table gave on this client.
     */
    val TRANSPORTS = object : ArtHarvest.Step {
        override val id = "transports"
        override val version = 3                    // 2: each ride's cost (yd) from the game's path; 3: its docks' towns
        override val beforePlay = false
        override val weight = 5
        override fun run(job: ArtHarvest.Job) {
            val t = job.game.db2(TransportRoutes.DB_TAXI_PATH_NODE)
            if (t == null) {
                android.util.Log.i("WCMAP", "transports: the TaxiPathNode table did not read (${job.game.lastReadWhy})")
                error(job.game.lastReadWhy ?: "TaxiPathNode unreadable")
            }
            // fields (this build, measured on the PC client): 0 the position (3 floats), 1 the id, 2 the path, 3 the node's index,
            // 4 the continent, 5 the flags, 6 the delay
            val nodes = t.rows().mapNotNull { row ->
                val p = row.vec3(0) ?: return@mapNotNull null
                TransportRoutes.Node(row.int(2), row.int(3), row.int(4), row.int(5), row.int(6), p[0], p[1], p[2])
            }
            // the docks' towns (b-route-faction): the game's flight masters, Alliance / Horde / both (TaxiNodes: 1 the position, 5 the
            // continent, 8 the flags; measured on the PC client)
            val masters = job.game.db2(TransportRoutes.DB_TAXI_NODES)?.rows()?.mapNotNull { row ->
                val p = row.vec3(1) ?: return@mapNotNull null
                TransportRoutes.FlightMaster(row.int(5), p[0], p[1], row.int(8))
            } ?: emptyList<TransportRoutes.FlightMaster>().also { android.util.Log.i("WCMAP", "transports: TaxiNodes did not read (${job.game.lastReadWhy})") }
            val rides = TransportRoutes.withFactions(TransportRoutes.rides(nodes), masters)
            android.util.Log.i("WCMAP", TransportRoutes.summary(nodes, rides))
            fun town(f: Int) = when (f) { 1 -> "Alliance"; 2 -> "Horde"; 3 -> "both"; else -> "?" }
            for (rd in rides.take(40)) android.util.Log.i("WCMAP", "transports: path ${rd.path} ${rd.ca}:" +
                "${rd.a[0].toInt()},${rd.a[1].toInt()} (${town(rd.fa)}) -> ${rd.cb}:${rd.b[0].toInt()},${rd.b[1].toInt()} (${town(rd.fb)})")
            job.writeBytes("transports.json", TransportRoutes.toJson(nodes, rides).toString().toByteArray())
        }
    }

    /**
     * [WMO_MINIMAP] "wmominimap", in the background: THE PROBE for the game's own building art (b-interiors-game-art; the user's
     * COMBINE pick, 2026-10-17). Reads the client's WMOMinimapTexture table (WmoMinimap) and, for every building root the published
     * interiors snapshot shows, its WMO id: WCMAP logs how many have the game's art (and for how many of their groups), and a sample
     * of the tiles is read to prove they are on this device. harvest/wmominimap.json keeps those buildings' groups (their boxes) and
     * tiles, each tile's picture under wmomm/ (MapView draws them as a building's floor: b-interiors-game-art).
     */
    val WMO_MINIMAP = object : ArtHarvest.Step {
        override val id = "wmominimap"
        override val version = 2                    // 2: the tiles' pictures and the groups' boxes, for the drawing
        override val beforePlay = false
        override val weight = 5
        override fun run(job: ArtHarvest.Job) {
            val t = job.game.db2(WmoMinimap.DB_WMO_MINIMAP_TEXTURE)
            if (t == null) {
                android.util.Log.i("WCMAP", "wmo minimap: the WMOMinimapTexture table did not read (${job.game.lastReadWhy})")
                error(job.game.lastReadWhy ?: "WMOMinimapTexture unreadable")
            }
            val rows = t.rows()
            val by = WmoMinimap.byWmo(rows)
            android.util.Log.i("WCMAP", "wmo minimap: ${rows.size} tiles, ${by.size} buildings, " +
                "${by.values.sumOf { ts -> ts.map { it.group }.toSet().size }} groups in the table")
            val roots = WmoMinimap.snapshotRoots(java.io.File(job.out, "interiors"))
            if (roots.isEmpty()) android.util.Log.i("WCMAP", "wmo minimap: no interiors snapshot yet: the table's totals only")
            val out = JSONObject()
            var withArt = 0; var groupsAll = 0; var groupsArt = 0; var unread = 0
            val none = ArrayList<Int>(); val sample = ArrayList<Int>()
            val sizes = HashMap<Int, IntArray>()
            for (fid in roots.sorted()) {
                val root = job.game.read(fid)
                if (root == null) { unread++; continue }
                val wmo = WmoMinimap.wmoId(root)
                if (wmo == null) { unread++; continue }
                val groups = WmoMinimap.groupCount(root) ?: 0
                groupsAll += groups
                val tiles = by[wmo]
                if (tiles.isNullOrEmpty()) { none.add(fid); continue }
                withArt++
                groupsArt += tiles.map { it.group }.toSet().size
                if (sample.size < 20) sample.add(tiles[tiles.size / 2].fid)
                // each tile's picture once (textures are shared between buildings); its size for the drawing
                out.put(fid.toString(), WmoMinimap.toJson(wmo, tiles, WmoMinimap.groupBoxes(root)) { tf ->
                    sizes.getOrPut(tf) {
                        val f = job.file(WmoMinimap.tileFile(tf))
                        val known = if (f.isFile) runCatching {
                            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeFile(f.path, o); if (o.outWidth > 0) intArrayOf(o.outWidth, o.outHeight) else null
                        }.getOrNull() else null
                        known ?: job.game.blp(tf)?.let { bm ->
                            job.writeBytes(WmoMinimap.tileFile(tf), bm.lossless()); intArrayOf(bm.width, bm.height).also { bm.recycle() }
                        } ?: intArrayOf(-1, -1)
                    }.takeIf { it[0] > 0 }
                })
            }
            var readable = 0
            for (f in sample) if ((sizes[f]?.get(0) ?: -1) > 0) readable++
            android.util.Log.i("WCMAP", "wmo minimap: of ${roots.size} interiors buildings $withArt have the game's art " +
                "($groupsArt of $groupsAll groups), ${none.size} have none, $unread roots unread; sample tiles readable $readable / ${sample.size}")
            if (none.isNotEmpty()) android.util.Log.i("WCMAP", "wmo minimap: no game art for roots " + none.take(60).joinToString(",") +
                if (none.size > 60) " (+${none.size - 60})" else "")
            android.util.Log.i("WCMAP", "wmo minimap: ${sizes.count { it.value[0] > 0 }} tile pictures kept, ${sizes.count { it.value[0] <= 0 }} unreadable")
            job.writeBytes("wmominimap.json", JSONObject().put("v", 2).put("roots", out).toString().toByteArray())
        }
    }

    val STEPS: List<ArtHarvest.Step> = listOf(WORLD_MAP, POI_ICONS, NPC_ICONS, MINIMAP, TRANSPORTS, WMO_MINIMAP, DangerStore.AREAS_STEP)
}
