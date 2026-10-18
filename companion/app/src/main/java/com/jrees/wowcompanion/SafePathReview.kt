package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * SAFEPATH'S REVIEW (b-safepath-review; the user, 2026-10-18: "designate safe pathing on every map and then clearly highlight the
 * intended entry/exits to the surrounding zones"; their pick "Auto-draft, then review"). Over Settings (Route data › Review paths), as
 * the report page is: first the zones with a draft, then one zone's own map with its network on it - roads gold, spurs blue, trails
 * dashed orange, the draft's ways dim until approved, avoided ones red, removed ones grey - its stops and its exits named. The Map
 * room's cursor (the left stick, the D-pad a step) picks a way or a point: A selects it (on another point with one selected: joins them;
 * on open ground: draws a new point joined to it), Y its actions (approve, avoid, remove / restore, road / trail, move, exit to a
 * neighbour; with nothing picked: approve every draft way, add my last walk, the zone reviewed, start over), X undoes, B lets go /
 * goes back, LB / RB zoom. Every change is saved at once as the zone's own network (SafePath.save), never over the draft.
 */
class SafePathReview(context: Context) : View(context) {
    var isOpen = false; private set
    var onClosed: () -> Unit = {}

    // ---- the zone list
    private class Row(val map: Int, val name: String, val state: String)
    private var rows: List<Row> = emptyList()
    private var listSel = 0
    private var loading = false

    // ---- one zone
    private var map = 0
    private var zoneName = ""
    private var edit: SafePath.Edit? = null
    private var rect: DoubleArray? = null                  // the zone's world rect: top, bottom, left, right
    private var art: Bitmap? = null
    private var neighbours: List<Pair<Int, String>> = emptyList()
    private var zoom = 1f
    private var panX = 0.5f; private var panY = 0.5f
    private var curX = 0.5f; private var curY = 0.5f
    private var selNode: Int? = null
    private var selEdge: Int? = null
    private var moving: Int? = null
    private class Item(val label: String, val act: () -> Unit)
    private var menu: List<Item>? = null
    private var menuTitle = ""
    private var menuSel = 0
    private var note: String? = null
    private var noteAt = 0L

    init { visibility = GONE; isFocusable = false }

    fun open() {
        isOpen = true; visibility = VISIBLE
        map = 0; menu = null
        loadList()
        startStick()
        invalidate()
    }

    fun close() {
        if (!isOpen) return
        stopStick()
        isOpen = false; visibility = GONE
        art = null; edit = null
        onClosed()
    }

    /** a zone's state for the list: reviewed, corrected, or its draft's size */
    private fun loadList() {
        loading = true
        RouteWork.run({
            val here = SafePath.lastMap
            SafePath.zonesDrafted().sortedWith(compareBy({ it != here }, { nameOf(it) })).mapNotNull { m ->
                val net = SafePath.load(m) ?: return@mapNotNull null
                val ok = net.edges.count { it.state != SafePath.DRAFT }
                val state = when {
                    net.reviewed -> "Reviewed"
                    SafePath.hasReview(m) -> "$ok of ${net.edges.size} ways checked"
                    else -> "Draft: ${net.edges.size} ways, ${net.exits.size} exits"
                } + if (m == here) " (you are here)" else ""
                Row(m, nameOf(m).ifEmpty { "Map $m" }, state)
            }
        }) { r -> rows = r; loading = false; listSel = listSel.coerceIn(0, max(0, r.size - 1)); invalidate() }
    }

    private fun nameOf(m: Int): String = runCatching { JSONObject(File(ArtHarvest.dir("worldmap"), "$m/index.json").readText()).optString("name", "") }.getOrDefault("")

    /** zone [m]'s network, art, world rect and neighbours (off the UI thread) */
    private fun openZone(m: Int) {
        loading = true; invalidate()
        RouteWork.run<Any>({
            val net = SafePath.load(m) ?: return@run false
            val idx = JSONObject(File(ArtHarvest.dir("worldmap"), "$m/index.json").readText())
            val r = idx.optJSONArray("rect")?.takeIf { it.length() == 5 }?.let { doubleArrayOf(it.optDouble(1), it.optDouble(2), it.optDouble(3), it.optDouble(4)) }
                ?: return@run false
            val bmp = SafePath.artPixels(m)?.let { (px, w, h) -> Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888) }
            // the neighbours: every other zone whose ground lies in this one's rect, and the ones its exits lead to
            val near = LinkedHashMap<Int, String>()
            for (x in net.exits) near[x.toMap] = x.name
            val zg = SafePath.zones.get(m); val ar = SafePath.areas()
            if (zg != null && ar != null) for (a in zg.area.toSet()) { val u = ar.uiMap(a); if (u != 0 && u != m && u !in near) near[u] = nameOf(u) }
            Triple(net, r, bmp) to near.filterValues { it.isNotEmpty() }.toList()
        }) { any ->
            loading = false
            @Suppress("UNCHECKED_CAST")
            val res = any as? Pair<Triple<SafePath.Net, DoubleArray, Bitmap?>, List<Pair<Int, String>>>
            if (res == null) { say("This zone has no draft yet"); invalidate(); return@run }
            val (t, nb) = res
            map = m; zoneName = nameOf(m); edit = SafePath.Edit(t.first); rect = t.second; art = t.third; neighbours = nb
            zoom = 1f; panX = 0.5f; panY = 0.5f; curX = 0.5f; curY = 0.5f
            selNode = null; selEdge = null; moving = null; menu = null
            invalidate()
        }
    }

    private fun save() { val e = edit ?: return; val n = e.net(); RouteWork.run({ SafePath.save(n); true }) { } }
    private fun say(t: String) { note = t; noteAt = SystemClock.uptimeMillis(); invalidate() }

    // ---------------------------------------------------------------- the frame
    private val TOP = 74f
    private val BOTTOM = 78f
    private val artR = RectF()
    private fun layoutArt() {
        val a = art
        val aspect = if (a != null && a.height > 0) a.width.toFloat() / a.height else 1.5f
        val w = width.toFloat(); val h = height - TOP - BOTTOM
        var aw = w; var ah = aw / aspect
        if (ah > h) { ah = h; aw = ah * aspect }
        artR.set((w - aw) / 2f, TOP + (h - ah) / 2f, (w + aw) / 2f, TOP + (h + ah) / 2f)
    }
    private fun sx(u: Float) = artR.left + (u - (panX - 0.5f / zoom)) * zoom * artR.width()
    private fun sy(v: Float) = artR.top + (v - (panY - 0.5f / zoom)) * zoom * artR.height()
    private fun ux(x: Float) = (panX - 0.5f / zoom) + (x - artR.left) / (zoom * artR.width())
    private fun uy(y: Float) = (panY - 0.5f / zoom) + (y - artR.top) / (zoom * artR.height())
    /** world x, y to the zone's 0..1 */
    private fun u(wy: Double): Float { val r = rect!!; return ((r[2] - wy) / (r[2] - r[3])).toFloat() }
    private fun v(wx: Double): Float { val r = rect!!; return ((r[0] - wx) / (r[0] - r[1])).toFloat() }
    private fun wxOf(v: Float): Double { val r = rect!!; return r[0] - v * (r[0] - r[1]) }
    private fun wyOf(u: Float): Double { val r = rect!!; return r[2] - u * (r[2] - r[3]) }
    private fun clampPan() {
        val h = 0.5f / zoom
        panX = if (h >= 0.5f) 0.5f else panX.coerceIn(h, 1f - h); panY = if (h >= 0.5f) 0.5f else panY.coerceIn(h, 1f - h)
    }
    private fun follow() {
        val h = 0.5f / zoom * 0.85f
        if (curX < panX - h) panX = curX + h; if (curX > panX + h) panX = curX - h
        if (curY < panY - h) panY = curY + h; if (curY > panY + h) panY = curY - h
        clampPan()
    }

    // ---------------------------------------------------------------- what the cursor is on
    /** the node within [NODE_PX] of the cursor, else the edge within [EDGE_PX] (its index), else neither */
    private fun hover(): Pair<Int?, Int?> {
        val e = edit ?: return null to null
        val cx = sx(curX); val cy = sy(curY)
        val n = e.nodes.minByOrNull { hypot((sx(u(it.wy)) - cx).toDouble(), (sy(v(it.wx)) - cy).toDouble()) }
            ?.takeIf { hypot((sx(u(it.wy)) - cx).toDouble(), (sy(v(it.wx)) - cy).toDouble()) <= NODE_PX }
        if (n != null) return n.id to null
        var best = -1; var bd = Double.MAX_VALUE
        for ((i, ed) in e.edges.withIndex()) {
            val a = e.node(ed.a) ?: continue; val b = e.node(ed.b) ?: continue
            val d = segDist(cx, cy, sx(u(a.wy)), sy(v(a.wx)), sx(u(b.wy)), sy(v(b.wx)))
            if (d < bd) { bd = d; best = i }
        }
        return null to best.takeIf { it >= 0 && bd <= EDGE_PX }
    }
    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Double {
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        val t = if (l2 <= 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot((px - (ax + t * dx)).toDouble(), (py - (ay + t * dy)).toDouble())
    }

    // ---------------------------------------------------------------- the keys
    fun padKey(k: String) {
        if (!isOpen) return
        menu?.let { m ->
            when (k) {
                "PADDUP" -> menuSel = (menuSel - 1 + m.size) % m.size
                "PADDDOWN" -> menuSel = (menuSel + 1) % m.size
                "PAD1" -> { menu = null; m.getOrNull(menuSel)?.act?.invoke() }
                "PAD2", "PAD4" -> menu = null
            }
            invalidate(); return
        }
        if (map == 0) {
            when (k) {
                "PADDUP" -> listSel = max(0, listSel - 1)
                "PADDDOWN" -> listSel = min(max(0, rows.size - 1), listSel + 1)
                "PAD1" -> rows.getOrNull(listSel)?.let { openZone(it.map) }
                "PAD2" -> close()
            }
            invalidate(); return
        }
        val e = edit ?: return
        moving?.let { id ->
            when (k) {
                "PAD1" -> { e.move(id, wxOf(curY), wyOf(curX)); moving = null; save(); say("Moved") }
                "PAD2" -> moving = null
                else -> nudge(k)
            }
            invalidate(); return
        }
        when (k) {
            "PADDUP", "PADDDOWN", "PADDLEFT", "PADDRIGHT" -> nudge(k)
            "PADLSHOULDER" -> { zoom = max(1f, zoom / 1.6f); clampPan() }
            "PADRSHOULDER" -> { zoom = min(8f, zoom * 1.6f); panX = curX; panY = curY; clampPan() }
            "PAD1" -> press()
            "PAD3" -> if (e.undo()) { save(); say("Undone"); selEdge = selEdge?.takeIf { it < e.edges.size }; selNode = selNode?.takeIf { e.node(it) != null } } else say("Nothing to undo")
            "PAD4" -> openMenu()
            "PAD2" -> if (selNode != null || selEdge != null) { selNode = null; selEdge = null } else { map = 0; edit = null; art = null; loadList() }
        }
        invalidate()
    }
    private fun nudge(k: String) {
        val s = 0.03f / zoom
        when (k) { "PADDUP" -> curY -= s; "PADDDOWN" -> curY += s; "PADDLEFT" -> curX -= s; "PADDRIGHT" -> curX += s }
        curX = curX.coerceIn(0f, 1f); curY = curY.coerceIn(0f, 1f); follow()
    }

    /** A: select what is under the cursor; with a point selected, join it to another point or draw a new one on open ground */
    private fun press() {
        val e = edit ?: return
        val (hn, he) = hover()
        val sel = selNode
        when {
            hn != null && sel != null && hn != sel -> { e.join(sel, hn); selNode = hn; save(); say("Joined") }
            hn != null -> { selNode = hn; selEdge = null }
            he != null -> { selEdge = he; selNode = null }
            sel != null -> { selNode = e.draw(sel, wxOf(curY), wyOf(curX)); save(); say("A new way: A on, or B to stop") }
            else -> { selNode = null; selEdge = null }
        }
    }

    /** Y: the selection's actions, or the zone's */
    private fun openMenu() {
        val e = edit ?: return
        val items = ArrayList<Item>()
        val ei = selEdge; val ni = selNode
        fun done(t: String) { save(); say(t) }
        if (ei != null && ei < e.edges.size) {
            val ed = e.edges[ei]
            menuTitle = "This way (${kindWord(ed.kind)}, ${stateWord(ed.state)})"
            if (ed.state != SafePath.OK) items += Item("Approve") { e.setState(ei, SafePath.OK); done("Approved") }
            if (ed.state != SafePath.AVOID) items += Item("Avoid it") { e.setState(ei, SafePath.AVOID); done("Avoided") }
            items += if (ed.state == SafePath.REMOVED) Item("Restore") { e.setState(ei, SafePath.DRAFT); done("Restored") }
                else Item("Remove") { e.setState(ei, SafePath.REMOVED); done("Removed") }
            if (ed.kind != SafePath.ROAD) items += Item("Make it a road") { e.setKind(ei, SafePath.ROAD); done("A road") }
            if (ed.kind != SafePath.TRAIL) items += Item("Make it a trail") { e.setKind(ei, SafePath.TRAIL); done("A trail") }
        } else if (ni != null && e.node(ni) != null) {
            val n = e.node(ni)!!
            val ex = e.exits.firstOrNull { it.node == ni }
            menuTitle = n.name ?: ex?.let { "Exit to ${it.name}" } ?: kindWord(n.kind)
            items += Item("Approve its ways") { e.setNodeEdges(ni, SafePath.OK); done("Approved") }
            items += Item("Move") { moving = ni; say("Move it, then A") }
            items += Item("Remove its ways") { e.setNodeEdges(ni, SafePath.REMOVED); done("Removed") }
            items += Item(if (ex == null) "Make it an exit to..." else "Change its exit...") { exitMenu(ni) }
            if (ex != null) items += Item("Not an exit") { e.exit(ni, 0, ""); done("No longer an exit") }
        } else {
            menuTitle = zoneName
            items += Item("Approve every draft way") { e.approveAll(); done("Every draft way approved") }
            items += Item("Add my last walk") {
                if (e.addWalk(SafePath.lastWalk(map))) done("Your last walk added") else say("No walk here yet (walk this zone, then add it)")
            }
            items += if (e.reviewed) Item("Not reviewed yet") { e.markReviewed(false); done("Not reviewed") }
                else Item("This zone is reviewed") { e.markReviewed(true); done("Reviewed") }
            items += Item("Start over from the draft") {
                RouteWork.run({ SafePath.forget(map); SafePath.load(map) }) { n -> if (n != null) { edit = SafePath.Edit(n); selNode = null; selEdge = null; say("The draft again") } }
            }
        }
        menu = items; menuSel = 0
    }
    private fun exitMenu(id: Int) {
        val e = edit ?: return
        menuTitle = "An exit to"
        menu = neighbours.map { (m, name) -> Item(name) {
            e.exit(id, m, name, if (m in CityExits.CITIES || map in CityExits.CITIES) "gate" else "walk"); save(); say("An exit to $name")
        } }.ifEmpty { listOf(Item("(no neighbour known yet)") {}) }
        menuSel = 0
        invalidate()
    }
    private fun kindWord(k: String) = when (k) { SafePath.ROAD -> "road"; SafePath.SPUR -> "spur"; SafePath.TRAIL -> "trail"
        SafePath.FLIGHT -> "Flight master"; SafePath.DOCK -> "Dock"; SafePath.HUB -> "Town"; SafePath.EXIT -> "Exit"; else -> "Point" }
    private fun stateWord(s: String) = when (s) { SafePath.OK -> "approved"; SafePath.AVOID -> "avoided"; SafePath.REMOVED -> "removed"; else -> "draft" }

    // ---------------------------------------------------------------- the left stick: the cursor
    private var reader: PadInput? = null
    private var stickX = 0f; private var stickY = 0f
    private var frameAt = 0L
    private var running = false
    private val frame = Runnable { stickFrame() }
    private fun startStick() {
        if (reader != null) return
        reader = PadInput(onPad = { _, _, _, _ -> }, onStatus = { _, _ -> }, onStickVector = { stick, x, y ->
            if (stick != 0) return@PadInput
            stickX = x; stickY = y
            if (MapRoomKeys.stickMoves(x, y) && !running) { running = true; frameAt = 0L; postOnAnimation(frame) }
        }).also { it.start() }
    }
    private fun stopStick() { reader?.stop(); reader = null; removeCallbacks(frame); running = false }
    private fun stickFrame() {
        if (!isOpen || map == 0 || menu != null || !MapRoomKeys.stickMoves(stickX, stickY)) { running = false; return }
        val now = SystemClock.uptimeMillis()
        val dt = if (frameAt == 0L) 1f / 60f else ((now - frameAt) / 1000f).coerceIn(0f, 0.05f)
        frameAt = now
        val (dx, dy) = MapRoomKeys.cursorStep(stickX, stickY, dt, CURSOR_PX_S, overMarker = hover().let { it.first != null || it.second != null })
        curX = (curX + dx / (artR.width() * zoom)).coerceIn(0f, 1f); curY = (curY + dy / (artR.height() * zoom)).coerceIn(0f, 1f)
        follow(); invalidate()
        postOnAnimation(frame)
    }

    // ---------------------------------------------------------------- touch: the same, by finger
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isOpen) return false
        if (ev.action != MotionEvent.ACTION_UP) return true
        val x = ev.x; val y = ev.y
        menu?.let { m ->
            val hit = menuHits.indexOfFirst { it.contains(x, y) }
            menu = null
            if (hit >= 0) m.getOrNull(hit)?.act?.invoke()
            invalidate(); return true
        }
        if (map == 0) {
            val hit = rowHits.indexOfFirst { it.contains(x, y) }
            if (hit >= 0) { listSel = hit; rows.getOrNull(hit)?.let { openZone(it.map) } }
            invalidate(); return true
        }
        if (artR.contains(x, y)) { curX = ux(x).coerceIn(0f, 1f); curY = uy(y).coerceIn(0f, 1f); padKey("PAD1") }
        return true
    }

    // ---------------------------------------------------------------- drawing
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    private val dashFine = DashPathEffect(floatArrayOf(8f, 10f), 0f)
    private val rowHits = ArrayList<RectF>()
    private val menuHits = ArrayList<RectF>()

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(16, 12, 9))
        if (map == 0) drawList(c) else drawZone(c)
        drawLegend(c)
        val n = note
        if (n != null && SystemClock.uptimeMillis() - noteAt < 3000L) {
            val tp = UiKit.bodyPaint(26f, Theme.text)
            val w = tp.measureText(n) + 40f
            pFill.color = Color.argb(220, 8, 6, 4)
            val r = RectF(width / 2f - w / 2f, height - BOTTOM - 70f, width / 2f + w / 2f, height - BOTTOM - 22f)
            c.drawRoundRect(r, 8f, 8f, pFill)
            c.drawText(n, r.left + 20f, r.bottom - 15f, tp)
            postInvalidateDelayed(500)
        }
    }

    private fun drawList(c: Canvas) {
        c.drawText("SafePath: review the zones", 32f, 54f, UiKit.titlePaint(38f))
        rowHits.clear()
        if (loading) { c.drawText("Reading the drafts", 32f, 130f, UiKit.bodyPaint(28f, Theme.textDim)); return }
        if (rows.isEmpty()) {
            c.drawText("No zone has a draft yet: the drafts are made as you walk (the route's data and your learned roads).", 32f, 130f,
                UiKit.bodyPaint(26f, Theme.textDim))
            return
        }
        val box = RectF(24f, TOP + 10f, width - 24f, height - BOTTOM - 10f)
        UiKit.menu(c, box)
        val rowH = 66f
        val first = max(0, min(listSel - 4, rows.size - ((box.height() - 16f) / rowH).toInt()))
        var y = box.top + 8f
        for (i in first until rows.size) {
            if (y + rowH > box.bottom) break
            val r = RectF(box.left + 8f, y, box.right - 8f, y + rowH)
            UiKit.menuRow(c, r, rows[i].name, i == listSel, size = 32f)
            val sp = UiKit.bodyPaint(24f, Theme.textDim).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(rows[i].state, r.right - 24f, r.centerY() + 9f, sp)
            while (rowHits.size < i) rowHits.add(RectF())
            rowHits.add(r)
            y += rowH
        }
    }

    private fun drawZone(c: Canvas) {
        val e = edit ?: return
        layoutArt()
        c.save(); c.clipRect(0f, TOP, width.toFloat(), height - BOTTOM)
        art?.let { c.drawBitmap(it, null, RectF(sx(0f), sy(0f), sx(1f), sy(1f)), pFill.apply { isFilterBitmap = true; color = Color.WHITE }) }
        val (hn, he) = hover()
        // the ways: removed and draft under, approved over
        val order = e.edges.indices.sortedBy { when (e.edges[it].state) { SafePath.REMOVED -> 0; SafePath.DRAFT -> 1; else -> 2 } }
        for (pass in 0..1) for (i in order) {
            val ed = e.edges[i]
            val a = e.node(ed.a) ?: continue; val b = e.node(ed.b) ?: continue
            val x0 = sx(u(a.wy)); val y0 = sy(v(a.wx)); val x1 = sx(u(b.wy)); val y1 = sy(v(b.wx))
            val lit = i == selEdge || i == he
            val w = (if (ed.kind == SafePath.ROAD) 6f else 4f) * (if (ed.state == SafePath.DRAFT) 0.8f else 1f)
            if (pass == 0) {
                if (ed.state == SafePath.REMOVED) continue
                pLine.pathEffect = null
                pLine.color = if (lit) Color.argb(230, 255, 255, 255) else Color.argb(170, 0, 0, 0)
                pLine.strokeWidth = w + if (lit) 9f else 5f
                c.drawLine(x0, y0, x1, y1, pLine)
                continue
            }
            val base = when (ed.kind) { SafePath.ROAD -> Color.rgb(255, 209, 0); SafePath.SPUR -> Color.rgb(140, 220, 255); else -> Color.rgb(255, 140, 60) }
            pLine.pathEffect = if (ed.kind == SafePath.TRAIL || ed.state == SafePath.REMOVED) (if (ed.state == SafePath.REMOVED) dashFine else dash) else null
            pLine.strokeWidth = if (ed.state == SafePath.REMOVED) 3f else w
            pLine.color = when (ed.state) {
                SafePath.REMOVED -> Color.argb(150, 150, 150, 150)
                SafePath.AVOID -> Color.rgb(200, 30, 30)
                SafePath.DRAFT -> Color.argb(150, Color.red(base), Color.green(base), Color.blue(base))
                else -> base
            }
            c.drawLine(x0, y0, x1, y1, pLine)
            // the draft's danger: a red core where most of the way is among hostile mobs
            if (ed.state == SafePath.DRAFT && ed.danger >= 0.5f) { pLine.pathEffect = null; pLine.color = Color.argb(200, 230, 40, 40); pLine.strokeWidth = 2f; c.drawLine(x0, y0, x1, y1, pLine) }
        }
        pLine.pathEffect = null
        // the points: stops and exits named; the plain points when zoomed in or picked
        val tp = UiKit.bodyPaint(24f, Color.WHITE)
        val taken = ArrayList<RectF>()
        fun label(t: String, x: Float, y: Float) {
            var yy = y; val w = tp.measureText(t); var r = RectF(x, yy - 22f, x + w, yy + 6f); var g = 0
            while (taken.any { RectF.intersects(it, r) } && g++ < 10) { yy += 28f; r = RectF(x, yy - 22f, x + w, yy + 6f) }
            taken.add(r); c.drawText(t, x, yy, tp)
        }
        for (n in e.nodes) {
            val x = sx(u(n.wy)); val y = sy(v(n.wx))
            val lit = n.id == selNode || n.id == hn || n.id == moving
            val ex = e.exits.firstOrNull { it.node == n.id }
            when {
                ex != null -> {
                    pFill.color = Color.BLACK; c.drawCircle(x, y, 19f, pFill)
                    val p = Path().apply { moveTo(x, y - 15f); lineTo(x + 13f, y + 9f); lineTo(x - 13f, y + 9f); close() }
                    pFill.color = if (ex.how == "gate") Color.rgb(255, 80, 80) else Color.rgb(255, 140, 40); c.drawPath(p, pFill)
                    label("To ${ex.name}", x + 22f, y - 6f)
                }
                n.kind == SafePath.FLIGHT || n.kind == SafePath.DOCK || n.kind == SafePath.HUB -> {
                    pFill.color = Color.BLACK; c.drawCircle(x, y, 14f, pFill)
                    pFill.color = when (n.kind) { SafePath.FLIGHT -> Color.rgb(80, 170, 255); SafePath.DOCK -> Color.rgb(200, 120, 255); else -> Color.rgb(120, 220, 110) }
                    c.drawCircle(x, y, 11f, pFill)
                    label(n.name ?: kindWord(n.kind), x + 18f, y + 8f)
                }
                lit || zoom >= 2f -> { pFill.color = Color.BLACK; c.drawCircle(x, y, 7f, pFill); pFill.color = Color.rgb(255, 230, 120); c.drawCircle(x, y, 5f, pFill) }
            }
            if (lit) { pLine.color = Color.WHITE; pLine.strokeWidth = 3f; c.drawCircle(x, y, 22f, pLine) }
        }
        // the cursor: the Map room's golden cross
        val cx = sx(curX); val cy = sy(curY)
        pLine.color = Color.rgb(255, 209, 0); pLine.strokeWidth = 3f
        c.drawCircle(cx, cy, 16f, pLine)
        c.drawLine(cx - 28f, cy, cx - 10f, cy, pLine); c.drawLine(cx + 10f, cy, cx + 28f, cy, pLine)
        c.drawLine(cx, cy - 28f, cx, cy - 10f, pLine); c.drawLine(cx, cy + 10f, cx, cy + 28f, pLine)
        c.restore()
        // the bar: the zone, its progress
        val ok = e.edges.count { it.state != SafePath.DRAFT }
        c.drawText("SafePath review: $zoneName", 32f, 50f, UiKit.titlePaint(36f))
        val sp = UiKit.bodyPaint(24f, Theme.textDim).apply { textAlign = Paint.Align.RIGHT }
        c.drawText("$ok of ${e.edges.size} ways checked, ${e.exits.size} exits" + (if (e.reviewed) "  -  Reviewed" else ""), width - 32f, 50f, sp)
        drawMenu(c)
    }

    private fun drawMenu(c: Canvas) {
        menuHits.clear()
        val m = menu ?: return
        val rowH = 56f
        val w = 520f
        val h = 70f + m.size * rowH + 12f
        val x = (sx(curX) + 40f).let { if (it + w > width - 16f) sx(curX) - 40f - w else it }.coerceAtLeast(16f)
        val y = (sy(curY) - h / 2f).coerceIn(TOP + 8f, height - BOTTOM - h - 8f)
        val box = RectF(x, y, x + w, y + h)
        UiKit.menu(c, box, solid = true)
        c.drawText(ellipsize(menuTitle, w - 44f), box.left + 22f, box.top + 46f, UiKit.titlePaint(28f))
        for ((i, it) in m.withIndex()) {
            val r = RectF(box.left + 8f, box.top + 62f + i * rowH, box.right - 8f, box.top + 62f + (i + 1) * rowH)
            UiKit.menuRow(c, r, it.label, i == menuSel, size = 28f)
            menuHits.add(r)
        }
    }
    private fun ellipsize(t: String, w: Float): String {
        val p = UiKit.titlePaint(28f)
        if (p.measureText(t) <= w) return t
        var n = t.length
        while (n > 1 && p.measureText(t, 0, n) + p.measureText("...") > w) n--
        return t.substring(0, n) + "..."
    }

    /** the keys' words at the bottom, as the Map room's legend says them */
    private fun drawLegend(c: Canvas) {
        val prompts: List<Pair<List<String>, String>> = when {
            menu != null -> listOf(listOf("a") to "Choose", listOf("b") to "Close")
            map == 0 -> listOf(listOf("a") to "Review", listOf("b") to "Close")
            moving != null -> listOf(listOf("a") to "Put it here", listOf("b") to "Cancel")
            else -> {
                val (hn, he) = hover()
                val a = when {
                    hn != null && selNode != null && hn != selNode -> "Join"
                    hn != null || he != null -> "Select"
                    selNode != null -> "Draw a way here"
                    else -> null
                }
                listOfNotNull(a?.let { listOf("a") to it }, listOf("y") to "More", listOf("x") to "Undo",
                    listOf("b") to (if (selNode != null || selEdge != null) "Let go" else "Zones"), listOf("lb", "rb") to "Zoom")
            }
        }
        val tp = UiKit.bodyPaint(26f, Theme.gold)
        val icon = 40f
        val w = prompts.sumOf { (g, t) -> (g.size * (icon + 6f) + 8f + tp.measureText(t) + 30f).toDouble() }.toFloat()
        var x = width / 2f - w / 2f
        val cy = height - BOTTOM / 2f
        pFill.color = Color.argb(220, 8, 6, 4)
        c.drawRoundRect(x - 16f, cy - 30f, x + w + 4f, cy + 30f, 10f, 10f, pFill)
        for ((g, t) in prompts) {
            for (k in g) { UiKit.pad(c, glyph(k), x + icon / 2f, cy, icon); x += icon + 6f }
            x += 8f
            c.drawText(t, x, cy + 9f, tp)
            x += tp.measureText(t) + 30f
        }
    }
    private fun glyph(k: String): String = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> when (k) { "a" -> "ltr_a"; "b" -> "ltr_b"; "x" -> "ltr_x"; "y" -> "ltr_y"; "lb" -> "ltr_lshoulder"; else -> "ltr_rshoulder" }
        CastSettings.ButtonLayout.PLAYSTATION -> when (k) { "a" -> "shp_cross"; "b" -> "shp_circle"; "x" -> "shp_square"; "y" -> "shp_triangle"; "lb" -> "shp_lshoulder"; else -> "shp_rshoulder" }
        else -> when (k) { "a" -> "rev_a"; "b" -> "rev_b"; "x" -> "rev_x"; "y" -> "rev_y"; "lb" -> "rev_lshoulder"; else -> "rev_rshoulder" }
    }

    private companion object {
        const val NODE_PX = 24.0
        const val EDGE_PX = 16.0
        const val CURSOR_PX_S = 900f
    }
}
