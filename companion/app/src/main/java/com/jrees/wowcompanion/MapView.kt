package com.jrees.wowcompanion

import android.content.Context
import android.graphics.*
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import org.json.JSONObject
import kotlin.math.*

/**
 * The bottom-screen zone map.
 *
 * This replaced a player-centred radar, for two reasons the user gave after using it:
 *  - unlabelled blips on a circle are unreadable ("I can't tell what anything on it is"), and
 *  - a zone map is more useful than a minimap on a screen you glance at rather than track.
 *
 * So: the zone's own 0..1 coordinate space is projected into a rectangle, every marker carries
 * its NAME, and the player is an arrow at their true position with true facing.
 *
 * THE TERRAIN ART IS REAL. An earlier version of this comment said the art "lives in CASC and
 * is not reachable from an addon". That was wrong: the addon cannot read the BLPs, but it can
 * ask the client for their fileDataIDs (C_Map.GetMapArtLayerTextures), and those ids go
 * straight into CascLib on the PC. tools/icons/mapart.py stitches each zone's 4x3 grid of
 * 256x256 tiles into one 1002x668 image, shipped in assets/maps/<uiMapID>.webp.
 *
 * The art covers exactly the map's 0..1 space, so it is drawn through the SAME sx()/sy()
 * projection as every marker -- pan and zoom therefore move art and markers together by
 * construction, rather than by two transforms that have to be kept in step.
 */
class MapView(context: Context) : View(context) {

    private var map: JSONObject? = null
    private var staleMs: Long = 0

    // ---- THE TERRAIN: the game's minimap tiles (MinimapTiles), harvested on the device. The bundled zone pictures
    // (assets/maps, stitched from the same tiles) left the APK with the art harvest (the user 2026-10-09: no game art in the
    // APK); in their place a SMALL copy of each tile is drawn at any width, the full tile over it while the view is close.
    private val pArt = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tileDst = RectF()
    // Full-size tiles only while the view is at most this many yards across (then at most ~4 x 3 are on screen)
    private val TILE_MAX_YD = 1600f
    // the small copies: a quarter (128 px) while at most this many tiles are on screen, else an eighth (64 px); more than
    // SMALL_TILES_MAX on screen (a continent): none, the grid
    private val QUARTER_TILES_MAX = 80
    private val SMALL_TILES_MAX = 320

    // ZOOM. In WoW the minimap is just the world map zoomed in with the same markers on it,
    // so this is one map with a zoom level rather than two different views. zoom 1 = whole
    // zone; higher = closer, centred on the player the way the minimap is.
    // Default zoom. Not 1: at whole-zone the art is a pretty picture you cannot navigate by --
    // Elwynn is ~2800 yards across, so the 40 yards that matter are a thumbnail. 3x opens on
    // roughly the area the minimap covered, centred on the player, and double-tap still drops
    // straight back to the whole zone.
    private var zoom = 3.0f
    // The old cap of 12x was set by the terrain art (0.96 px/yd), and it silently capped the
    // indoor frame: a 58 yd inn inside a 2800 yd zone needs ~34x. Indoors the thing being
    // drawn is the floorplan, baked at 7 px/yd, so it holds up far past where terrain stops.
    private val ZOOM_MAX = 40.0f
    private var followPlayer = true

    // ---- AUTO-RECENTRE. Replaces the "recentre" button entirely.
    // Dragging the map is a glance somewhere else, not a new home: the moment you WALK, the
    // view should come back to you on its own. So a drag drops following and remembers where
    // you were standing; the next position update that is more than a jitter away from that
    // anchor starts a timed glide back, and following resumes when it lands. Nothing to press,
    // and a stationary look-around is never yanked away from you.
    private var panAnchorX = 0f            // where the player stood when the drag ended
    private var panAnchorY = 0f
    private var lastDragAt = 0L
    private var recentreFrom: FloatArray? = null   // view centre at the moment the glide began
    private var recentreStart = 0L
    private var panX = 0.5f     // centre of view in map space when not following
    private var panY = 0.5f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                autoZoom = false        // you have taken the wheel; double-tap gives it back
                zoomEase = Float.NaN
                focusRestoreZoom = Float.NaN
                zoom = (zoom * d.scaleFactor).coerceIn(1.0f, ZOOM_MAX)
                autoZoomCur = zoom
                postInvalidate(); return true
            }
        })

    private val tapDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (compact || onFocusQuest == null) return false
                var best = -1; var bestD = BUBBLE_HIT * BUBBLE_HIT
                for (i in 0 until bubbleN) {
                    val dx = bubbleXY[2 * i] - e.x; val dy = bubbleXY[2 * i + 1] - e.y
                    val d = dx * dx + dy * dy
                    if (d <= bestD) { bestD = d; best = i }
                }
                if (best < 0) return false
                touchQuest(bubbleQ[best], null)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // double tap: hand control back to the auto-framer, or if it already has it,
                // toggle out to the whole zone for a look around.
                followPlayer = true
                recentreFrom = null
                focusRestoreZoom = Float.NaN
                if (!autoZoom) { autoZoom = true } else { autoZoom = false; zoom = 1.0f; autoZoomCur = 1.0f }
                postInvalidate(); return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                // The field is 3:2, not square, so a pixel of drag is a DIFFERENT amount of map
                // in x than in y. Dividing both by one number made the map slide diagonally
                // when you dragged straight.
                if (lastFieldW > 0 && lastFieldH > 0) {
                    // Starting a drag from a following view has to seed the pan from where the
                    // view actually IS, or the first drag jumps: panX/panY were last written
                    // before the follow took over and are stale.
                    val m = map
                    if (followPlayer && m != null) {
                        val px = m.optDouble("x", Double.NaN)
                        val py = m.optDouble("y", Double.NaN)
                        if (!px.isNaN()) panX = px.toFloat()
                        if (!py.isNaN()) panY = py.toFloat()
                    }
                    followPlayer = false
                    recentreFrom = null                  // a new drag cancels a glide in flight
                    lastDragAt = System.currentTimeMillis()
                    map?.let {
                        panAnchorX = it.optDouble("x", 0.0).toFloat()
                        panAnchorY = it.optDouble("y", 0.0).toFloat()
                    }
                    // a pixel of drag is 2*half/field of map in each axis (the field fills the room,
                    // so the two halves differ)
                    val hx = if (lastHalfX > 0f) lastHalfX else 0.5f / zoom
                    val hy = if (lastHalfY > 0f) lastHalfY else 0.5f / zoom
                    panX = (panX + dx * 2f * hx / lastFieldW).coerceIn(0f, 1f)
                    panY = (panY + dy * 2f * hy / lastFieldH).coerceIn(0f, 1f)
                    postInvalidate()
                }
                return true
            }
        })

    // ---- SMOOTH ARROW ----------------------------------------------------------------------
    // The addon samples position at ~30 Hz and ships each batch inside a slow flush (payload
    // bytes displace padding bytes, so samples are free while flushes cost game frames). This
    // draws that timeline at the screen's own rate, PREDICTING slightly ahead of the newest
    // sample to hide the pipeline's latency.
    //
    // HISTORY, all measured on a replay of a real session (the simulator and the numbers are in
    // AGENT_LOG, 2026-09-27):
    //  * v1 played 190 ms BEHIND the newest sample: smooth, and visibly late.
    //  * v2 predicted ahead at full strength: prompt, and the user saw it "rubber band so hard".
    //    Two causes. (a) 95% of position lines reach us TWO PER READ, because the addon writes
    //    every ~107 ms but a flush only happens every ~216 ms. v2 timed samples by ARRIVAL, so
    //    both lines looked simultaneous: most of the newer one was dropped and the older one was
    //    treated as current, and the speed estimate was badly off. (b) full-strength prediction
    //    overshot every stop by ~1.7 yd (35 px indoors) and snapped back at up to 15 yd/s.
    //  * v3 (this): samples are timed by the GAME's own log timestamp, and the prediction runs
    //    at HALF strength (the user's call). Two rules take the snap out of it: while moving, the
    //    arrow never steps backwards along its direction of travel, and any pull-back is capped
    //    at 6 yd/s. Against v2 in the replay, backward travel while walking went 10.6 -> 3.1
    //    yd/min, stop overshoot (p90) 1.74 -> 0.85 yd, settle 719 -> 352 ms, and lag 158 -> 130 ms.
    //
    // Everything is in YARDS, converted with the zone rect, because the hold and the caps need
    // x and y on the same scale. The zone's 0..1 units are 1.5x wider than tall.
    private class Sample(val g: Long, val x: Double, val y: Double, val f: Float)  // g = game ms

    private val trail = ArrayList<Sample>()
    private var lastAnimAt = 0L
    private var trailMap = Int.MIN_VALUE      // mapID the samples belong to
    private var ydW = 1000.0                  // yards per zone unit the samples were converted with
    private var ydH = 1000.0

    // The game clock is mapped onto ours by an OFFSET: the 2nd-smallest of the recent values of
    // (arrival - game stamp), meaning the last 32 lines and none older than 5 s. The smallest
    // arrival delay is the least-delayed read. 2nd-smallest ignores one freak. The window forgets
    // a clock step instead of holding a stale minimum forever: a running minimum measured
    // 26 yd/min of rubber band after a step. The 5 s expiry covers a step that happens while no
    // lines flow (standing, AFK, loading), which a count-only window would carry into the next run.
    private val offVal = LongArray(32)
    private val offAt = LongArray(32)
    private var offN = 0
    private var offHead = 0
    private var clkOff = Long.MIN_VALUE       // MIN_VALUE = unknown
    private var lastLineGame = Long.MIN_VALUE // previous line's stamp
    private var backLines = 0                 // consecutive lines stamped before their predecessor

    private val STRENGTH = 0.5        // the user's "about half the prediction"; 0.55 already overshoots 0.94 yd
    private val PIPE_MS = 100.0       // lead at arrival (before STRENGTH)
    private val MAX_LEAD = 300.0      // lead cap (before STRENGTH)
    private val FADE_FROM = 230.0     // batches land ~216 ms apart while moving...
    private val FADE_TO = 430.0       // ...so by here the silence is a stop, not a late batch
    private val POS_TAU = 60.0        // ms; ease toward the prediction
    private val VEL_WIN = 90L         // ms; velocity window (positions are quantised to ~0.35 yd)
    private val OFF_KEEP_MS = 5_000L  // clock-offset samples older than this are ignored
    private val MOVE_V = 0.0015       // yd/ms (1.5 yd/s); slower counts as stopped
    private val HOLD_AGE = 430.0      // the no-backward-step hold applies while data is this fresh
    private val RET_V = 0.006         // yd/ms (6 yd/s); pull-back speed cap
    private val RET_MS = 300.0        // ...raised so any pull-back finishes in ~this (fast mounts)
    private val STOP_TAU = 40.0       // ms; ease onto the stop point
    private val STALE_MS = 1500.0     // beyond this, the payload position is drawn instead
    private val TRAIL_KEEP = 3_000L
    private val JUMP_YD = 10.0        // a step this long that is also...
    private val JUMP_V = 0.1          // ...faster than 100 yd/s, or...
    private val JUMP_GAP = 1000L      // ...across a gap this long, is a teleport: cut the trail
    private val SNAP_YD = 6.0         // drawn arrow further than max(this, SNAP_MS x speed) from
    private val SNAP_MS = 500.0       //   the prediction: jump to it instead of easing or holding
    private val GAP_MS = 250L         // no frame for this long (map hidden, UI stall): snap

    // FRAME PROBE, off unless switched on from adb: `setprop log.tag.WCLAG DEBUG` (and
    // `setprop log.tag.WCLAG INFO` to stop). One line per drawn frame -- the arrow position the
    // screen actually showed and when -- which tools/.../real_measure.py scores against the
    // positions the game logged. It is how the latency work was measured on real play rather
    // than argued about. The property is read once a second, not per frame.
    private val PROBE_TAG = "WCLAG"
    private var probeCheckedAt = 0L
    private var probeCached = false
    private fun probeOn(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - probeCheckedAt > 1000L) {
            probeCheckedAt = now
            probeCached = android.util.Log.isLoggable(PROBE_TAG, android.util.Log.DEBUG)
        }
        return probeCached
    }

    /**
     * Absorb one "dtms,x,y,facing;..." batch, oldest sample first.
     * @param gameMsIn the log line's own timestamp (LogTail "_gt"), -1 if it had none. The
     *   newest sample was taken then; each older one is its newer neighbour's time minus the
     *   NEWER sample's dt.
     */
    private fun addTrail(spec: String?, gameMsIn: Long, mapId: Int) {
        if (spec.isNullOrEmpty()) return
        val now = android.os.SystemClock.elapsedRealtime()
        val r = rect
        val w = if (r != null && r.valid) r.width else 1000.0
        val h = if (r != null && r.valid) r.height else 1000.0
        if (mapId != trailMap || w != ydW || h != ydH) {
            // another map: its coordinates mean nothing next to the old ones
            trail.clear(); dispX = Double.NaN
            trailMap = mapId; ydW = w; ydH = h
        }
        val parts = spec.split(';')
        val dts = LongArray(parts.size)
        val xs = DoubleArray(parts.size)
        val ys = DoubleArray(parts.size)
        val fs = FloatArray(parts.size)
        var n = 0
        for (p in parts) {
            val f = p.split(',')
            if (f.size < 4) continue
            dts[n] = f[0].toLongOrNull() ?: continue
            xs[n] = (f[1].toDoubleOrNull() ?: continue) / 10000.0 * w
            ys[n] = (f[2].toDoubleOrNull() ?: continue) / 10000.0 * h
            fs[n] = (f[3].toFloatOrNull() ?: continue) / 1000f
            n++
        }
        if (n == 0) return

        val real = gameMsIn >= 0
        // No stamp (an older LogTail, or a prefix that did not parse): place the batch at its
        // arrival on the game scale, and keep it out of the offset estimate.
        if (!real && clkOff == Long.MIN_VALUE) clkOff = 0L
        val gameMs = if (real) gameMsIn else now - clkOff
        val g = LongArray(n)
        g[n - 1] = gameMs
        for (i in n - 2 downTo 0) g[i] = g[i + 1] - dts[i + 1]

        if (lastLineGame != Long.MIN_VALUE && gameMs < lastLineGame - 5) {
            // A line stamped EARLIER than the one before it. On its own it is a stray (a re-read
            // line): drop it, because resetting on it snapped the arrow 16 yd back in testing.
            // Two in a row means the game's clock stepped back (an NTP step, or the year or
            // leap-day wrap of the day-of-year stamp): start the timeline over.
            if (++backLines < 2) return
            trail.clear(); offN = 0; offHead = 0; clkOff = Long.MIN_VALUE; dispX = Double.NaN
        }
        backLines = 0
        lastLineGame = gameMs
        if (real) {
            offVal[offHead] = now - gameMs; offAt[offHead] = now
            offHead = (offHead + 1) % offVal.size
            if (offN < offVal.size) offN++
            var m1 = Long.MAX_VALUE
            var m2 = Long.MAX_VALUE
            var cnt = 0
            for (i in 0 until offN) {
                if (now - offAt[i] > OFF_KEEP_MS) continue
                val v = offVal[i]; cnt++
                if (v < m1) { m2 = m1; m1 = v } else if (v < m2) m2 = v
            }
            clkOff = if (cnt >= 2) m2 else m1       // the line just added is always counted
        }

        var newest = trail.lastOrNull()?.g ?: Long.MIN_VALUE
        for (i in 0 until n) {
            if (g[i] > newest) { trail.add(Sample(g[i], xs[i], ys[i], fs[i])); newest = g[i] }
        }
        val lastG = trail.last().g
        while (trail.size > 2 && trail[0].g < lastG - TRAIL_KEEP) trail.removeAt(0)

        // TELEPORTS (hearth, portal, blink, zone line): cut the trail at the jump, or the
        // velocity spans it and the arrow flies -- and the pull-back cap would then crawl it
        // back at 6 yd/s across hundreds of yards.
        var cut = -1
        for (i in 1 until trail.size) {
            val a = trail[i - 1]; val b = trail[i]
            val d = hypot(b.x - a.x, b.y - a.y)
            val gap = b.g - a.g
            if (d > JUMP_YD && (d / max(gap.toDouble(), 1.0) > JUMP_V || gap > JUMP_GAP)) cut = i
        }
        if (cut > 0) {
            repeat(cut) { trail.removeAt(0) }
            if (trail.size == 1) {             // keep a standing pair so there is a velocity
                val s = trail[0]
                trail.add(0, Sample(s.g - VEL_WIN, s.x, s.y, s.f))
            }
            dispX = Double.NaN
        }
    }

    private var dispX = Double.NaN
    private var dispY = 0.0
    private var dispF = 0f

    /** @return [x, y, facing] (zone units) for this frame, or null when there is no timeline. */
    private fun smoothPos(): FloatArray? {
        if (trail.size < 2 || clkOff == Long.MIN_VALUE) return null
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastAnimAt != 0L && now - lastAnimAt > GAP_MS) dispX = Double.NaN
        val dt = if (lastAnimAt == 0L) 16.0 else (now - lastAnimAt).coerceIn(0L, 200L).toDouble()
        lastAnimAt = now
        val nw = trail.last()
        // Long arithmetic FIRST: game stamps are ~2e10 ms, and a Float cannot hold them to the
        // nearest second.
        val age = (now - clkOff - nw.g).toDouble()
        if (age > STALE_MS) { dispX = Double.NaN; return null }   // stream stopped: use the payload

        var j = trail.size - 2
        while (j > 0 && nw.g - trail[j].g < VEL_WIN) j--
        val ref = trail[j]
        val span = (nw.g - ref.g).coerceAtLeast(1L).toDouble()
        val vx = (nw.x - ref.x) / span
        val vy = (nw.y - ref.y) / span
        val TAU = (Math.PI * 2).toFloat()
        var df = nw.f - ref.f
        if (df > TAU / 2) df -= TAU
        if (df < -TAU / 2) df += TAU
        val vf = df / span

        val fade = 1.0 - ((age - FADE_FROM) / (FADE_TO - FADE_FROM)).coerceIn(0.0, 1.0)
        val lead = STRENGTH * (age + PIPE_MS).coerceIn(0.0, MAX_LEAD) * fade
        val tx = nw.x + vx * lead
        val ty = nw.y + vy * lead
        // cap predicted rotation: a camera flick is the least predictable input there is
        val tf = nw.f + (vf * lead).toFloat().coerceIn(-0.5f, 0.5f)

        val sp = hypot(vx, vy)
        if (!dispX.isNaN() && hypot(tx - dispX, ty - dispY) > max(SNAP_YD, SNAP_MS * sp)) {
            dispX = Double.NaN                 // far off (teleport the cut missed): jump, not crawl
        }
        if (dispX.isNaN()) {
            dispX = tx; dispY = ty; dispF = tf
        } else {
            val cap = max(RET_V, hypot(tx - dispX, ty - dispY) / RET_MS)
            var sx: Double
            var sy: Double
            if (sp <= MOVE_V) {
                // STOPPED (the addon's all-standing stop line has arrived): ease onto the stop
                // point, never faster than the cap -- a glide back, not a snap
                val k = 1.0 - exp(-dt / STOP_TAU)
                sx = (tx - dispX) * k; sy = (ty - dispY) * k
                val m = hypot(sx, sy)
                if (m > cap * dt) { sx *= cap * dt / m; sy *= cap * dt / m }
            } else {
                // MOVING: the usual ease, minus any backward part along the direction of travel
                // while the data is fresh (sideways corrections after a turn pass through)
                val k = 1.0 - exp(-dt / POS_TAU)
                sx = (tx - dispX) * k; sy = (ty - dispY) * k
                val ux = vx / sp; val uy = vy / sp
                val a = sx * ux + sy * uy
                val floor = if (age < HOLD_AGE) 0.0 else -cap * dt
                if (a < floor) { sx += (floor - a) * ux; sy += (floor - a) * uy }
            }
            dispX += sx; dispY += sy
            var ef = tf - dispF
            if (ef > TAU / 2) ef -= TAU
            if (ef < -TAU / 2) ef += TAU
            dispF += ef * (1.0 - exp(-dt / POS_TAU)).toFloat()
        }
        return floatArrayOf((dispX / ydW).toFloat(), (dispY / ydH).toFloat(), dispF)
    }

    private fun trailLive() = trail.size >= 2 && clkOff != Long.MIN_VALUE &&
        android.os.SystemClock.elapsedRealtime() - clkOff - trail.last().g < STALE_MS.toLong()

    // ---- INTERIORS ------------------------------------------------------------------------
    // WoW ships no map image for any interior -- not for a tavern and not for a dungeon; the
    // minimap renders the model live. tools/icons/interiors.py renders the WMO geometry into
    // a per-storey floorplan: a real architectural section cut at chest height, so what you
    // get is walls with doorway gaps, the furniture and the staircases, rather than every
    // beam and rafter in the building projected on top of each other.
    //
    // THE PLAN IS BAKED IN THE MODEL'S OWN LOCAL SPACE, not in world coordinates, and the
    // placement travels in the JSON as l2w = [ox, oy, cos, sin]. Two reasons, both learned
    // the hard way: the world transform was wrong once already (a rotation error that
    // depended on the building's own angle, so it was 14 deg out on this inn and 180 deg out
    // on a building at rot 0), and with world-space pixels every attempt at fixing it meant
    // re-rendering every floor of every building. Now it is four numbers in a JSON file.
    // The cost is that the bitmap arrives rotated, so it goes through a Matrix rather than a
    // RectF -- the whole chain is affine, so three corners define it exactly.
    // zlo/zhi are the heights the storey is actually WALKED at, which is not the same as
    // z0. The inn's ground storey has its hall slab at -0.19 and its rooms at 0.45 -- one
    // storey with a step in it -- so classifying a reconstructed height against z0 put
    // anyone standing in the hall in the cellar, by 0.02 yd.
    private class Level(val z0: Float, val z1: Float, val zlo: Float, val zhi: Float,
                        val img: String, val outline: String?,
                        val lxmin: Double, val lxmax: Double,
                        val lymin: Double, val lymax: Double,
                        val w: Int = 0, val h: Int = 0,        // the full-size plan image's pixels
                        val zones: List<Zone> = emptyList())

    // TRANSITION ZONES (the user, 2026-09-29 15:00: "drawing transition zones with highlighted colors
    // instead of trying to draw stairs, like red for the exit and different colors for up or down";
    // colours confirmed 2026-10-11: exit red, down blue, up green). Baked per storey from
    // the building's game-file walk and doorway portals. Rings are in plan-image pixels, holes even-odd.
    private class Zone(val kind: Int, val ring: FloatArray, val holes: List<FloatArray>)

    private class Place(val fid: String,
                        val ox: Double, val oy: Double, val cs: Double, val sn: Double,
                        val wxmin: Double, val wxmax: Double,
                        val wymin: Double, val wymax: Double,
                        val levels: List<Level>, val gridMeta: JSONObject?,
                        val stairs: List<FloatArray> = emptyList()) {
        fun contains(wx: Double, wy: Double) = wx in wxmin..wxmax && wy in wymin..wymax
        fun worldX(lx: Double, ly: Double) = ox - lx * cs + ly * sn
        fun worldY(lx: Double, ly: Double) = oy - lx * sn - ly * cs
        fun localX(wx: Double, wy: Double) = -(cs * (wx - ox) + sn * (wy - oy))
        fun localY(wx: Double, wy: Double) = sn * (wx - ox) - cs * (wy - oy)
    }

    private val pScrim = Paint()
    private var places: List<Place>? = null
    private val planCache = LinkedHashMap<String, Bitmap>()
    private val planMatrix = Matrix()
    private val planSrc = FloatArray(6)
    private val planDst = FloatArray(6)

    // PLACES ARE PER CONTINENT AND THAT IS NOT AN OPTIMISATION. Every map has its own 64x64
    // ADT grid, so WORLD COORDINATES COLLIDE BETWEEN MAPS -- looking a building up by world
    // position without filtering by continent matches one in Kalimdor while you stand in
    // Azeroth. Models, by contrast, are global: human_farm is placed 17 times in Elwynn alone
    // and bakes once.
    //
    // The parse happens OFF THE DRAW THREAD. Baked whole, this is ~1 MB of JSON for 400-odd
    // models and 1,400 placements, and parsing that inside onDraw is a visible stall on the
    // first step through any doorway.
    @Volatile private var placesFor = -2
    private var placesLoading = -2
    private var placesRevision: IndoorAssets.Revision? = null
    private var placesSnapshot = IndoorAssets.snapshot(null)
    private var placesProbeAt = 0L
    private var placesToken = 0

    private fun ensurePlaces(cont: Int) {
        if (cont < 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now >= placesProbeAt) {
            placesProbeAt = now + 1_000
            val snapshot = currentIndoorSnapshot()
            val revision = snapshot.revision()
            if (revision != placesRevision) {
                placesSnapshot = snapshot
                placesRevision = revision
                placesFor = -2
                placesLoading = -2
                places = null
                placesToken++
                planCache.clear()
                planBytes = 0L
                planWanted.clear()
                planGen++
                gridCache.clear()
                trackFor = ""
                zEst = Float.NaN
                zAt = 0L
                zEvidenceAt = 0L
                pinnedLevel = -1
            }
        }
        if (placesFor == cont || placesLoading == cont) return
        placesLoading = cont
        val token = ++placesToken
        val revision = placesRevision
        val snapshot = placesSnapshot
        Thread {
            val out = parsePlaces(cont, snapshot)
            post {
                if (token != placesToken) return@post
                placesLoading = -2
                if (revision != currentIndoorSnapshot().revision()) {
                    placesProbeAt = 0L
                    invalidate()
                    return@post
                }
                places = out
                placesFor = cont
                invalidate()
            }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /**
     * An interiors file. The bundled interiors left the APK with the art harvest (the user 2026-10-09: no game art in the APK; phase 2
     * builds them on the device into the private art folder). Never read the old pushed server-derived plans. Until a private plan
     * exists, the building shows the outdoor map beneath the player.
     */
    private fun currentIndoorSnapshot(): IndoorAssets.Snapshot {
        val build = runCatching { GameFiles.activeBuild(GameFiles.gameRoot(context))?.second }.getOrNull()
            ?: return IndoorAssets.snapshot(null)
        return IndoorAssets.snapshot(ArtHarvest.rootDir, build)
    }

    private fun interiorFile(name: String): java.io.File = placesSnapshot.file(name)

    private fun parsePlaces(cont: Int, snapshot: IndoorAssets.Snapshot): List<Place> {
        val out = ArrayList<Place>()
        try {
            val models = JSONObject(snapshot.models(cont).readText())
            val all = JSONObject(snapshot.places(cont).readText())
            val arr = all.optJSONArray(cont.toString()) ?: return out
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                // The model key is NOT always the fileDataID. A city ships as one WMO holding
                // every interior in it, split here into per-building models keyed "<fid>_<n>"
                // that all share the one placement.
                val key = p.optString("m", null) ?: p.get("fid").toString()
                val m = models.optJSONObject(key) ?: continue
                val l2w = p.getJSONArray("l2w")
                val lv = ArrayList<Level>()
                val la = m.optJSONArray("levels") ?: continue
                for (j in 0 until la.length()) {
                    val o = la.getJSONObject(j)
                    lv.add(Level(o.optDouble("z0").toFloat(), o.optDouble("z1").toFloat(),
                        o.optDouble("zlo", o.optDouble("z0")).toFloat(),
                        o.optDouble("zhi", o.optDouble("z0")).toFloat(),
                        o.getString("img"), if (o.isNull("outline")) null else o.optString("outline", null),
                        o.getDouble("lxmin"), o.getDouble("lxmax"),
                        o.getDouble("lymin"), o.getDouble("lymax"),
                        o.optInt("w", 0), o.optInt("h", 0), parseZones(o.optJSONArray("zones"))))
                }
                if (lv.isEmpty()) continue
                out.add(Place(key, l2w.getDouble(0), l2w.getDouble(1),
                    l2w.getDouble(2), l2w.getDouble(3),
                    p.getDouble("wxmin"), p.getDouble("wxmax"),
                    p.getDouble("wymin"), p.getDouble("wymax"),
                    lv, m.optJSONObject("grid"), InteriorStairs.pairs(m)))
            }
        } catch (t: Throwable) { /* no interiors bundled for this continent */ }
        return out
    }

    private fun ring(a: org.json.JSONArray?): FloatArray? {
        if (a == null || a.length() < 3) return null
        val out = FloatArray(a.length() * 2)
        for (i in 0 until a.length()) {
            val pt = a.optJSONArray(i) ?: return null
            out[2 * i] = pt.optDouble(0).toFloat(); out[2 * i + 1] = pt.optDouble(1).toFloat()
        }
        return out
    }

    private fun parseZones(a: org.json.JSONArray?): List<Zone> {
        if (a == null) return emptyList()
        val out = ArrayList<Zone>(a.length())
        for (i in 0 until a.length()) {
            val z = a.optJSONObject(i) ?: continue
            val kind = when (z.optString("kind")) { "exit" -> 0; "up" -> 1; "down" -> 2; else -> -1 }
            val r = ring(z.optJSONArray("poly")) ?: continue
            if (kind < 0) continue
            val holes = ArrayList<FloatArray>()
            z.optJSONArray("holes")?.let { hs -> for (j in 0 until hs.length()) ring(hs.optJSONArray(j))?.let { holes.add(it) } }
            out.add(Zone(kind, r, holes))
        }
        return out
    }

    // HEIGHT FIELDS ARE INFLATED ON DEMAND, ONE BUILDING AT A TIME. Inflating them at load
    // was a 231 MB allocation and an immediate OutOfMemoryError: Azeroth alone has 763
    // placements and the fields total 244 MB uncompressed (the biggest single one is 13 MB).
    // The player is inside exactly one building, so keep a couple and drop the rest.
    private val gridCache = LinkedHashMap<String, IndoorGrid>()

    private fun gridFor(p: Place): IndoorGrid? {
        gridCache[p.fid]?.let { return it }
        val g = readGrid(p.gridMeta) ?: return null
        gridCache[p.fid] = g
        while (gridCache.size > 3) gridCache.remove(gridCache.keys.first())
        return g
    }

    private fun readGrid(o: JSONObject?): IndoorGrid? {
        if (o == null) return null
        return try {
            // The height field lives in its own file, not inline in the index: with 80-odd
            // buildings the base64 would be megabytes of string the app parses at startup to
            // use one of them.
            val file = interiorFile(o.getString("file"))
            if (file.length() > 32L*1024*1024) return null
            val raw = file.readBytes()
            IndoorGrid.decode(raw, o.getDouble("cell"), o.getInt("nx"), o.getInt("ny"),
                o.getDouble("lx0"), o.getDouble("ly0"),
                o.getDouble("zmin").toFloat(), o.getDouble("zscale").toFloat(), o.optInt("slots", 4))
        } catch (t: Throwable) { null }
    }

    // ---- WHICH STOREY ---------------------------------------------------------------------
    // The client will not say. UnitPosition returns 4 values and the third is 0 while the
    // player stands 56 yd above sea level, and a sweep of every Get*/Is*/Has* name in this
    // build's binary found no other height: every promising distance call turns out to be a
    // distance to a 2D MAP POINT, which has no Z by construction. Measured, not assumed --
    // C_QuestLog.GetDistanceSqToQuest returns floor(the exact planar distance) on 228 of 230
    // logged samples.
    //
    // So RECONSTRUCT the height rather than ask for it. The baked height field says, for the
    // cell you are standing in, which heights have walkable floor. You can only be on one of
    // them. Carry one float and snap it each tick to the nearest layer that is reachable from
    // where it was; walking up the stairs the layer under you rises continuously, so zEst
    // rides it and the storey changes on its own, halfway up, as a dissolve.
    //
    // A FOOTPRINT TEST CANNOT DO THIS, which is why the first version had a manual button:
    // the inn's ground and upper storeys overlap on 81-85% of their walkable area, because an
    // inn's upper storey sits on top of its ground storey. Only the height separates them.
    //
    // BEING UNSURE IS A REAL STATE. Log in upstairs and the cell under you holds two layers
    // and no history says which. The honest answer is then to show every storey as a ghost
    // and none of them solid, and to resolve on the first cell that has only one layer --
    // measured a median 2.5 yd away up there, so it costs a couple of steps. Guessing instead
    // is the one behaviour that is worse than the button this replaces, because with 85%
    // overlap a wrong guess never gets contradicted.
    private var zEst = Float.NaN
    private var zLx = 0.0
    private var zLy = 0.0
    private var zAt = 0L
    private var zEvidenceAt = 0L         // when a cell last PROVED the storey
    private var dropHold = 0
    private var provisionalUsed = false
    private var pinnedLevel = -1
    private var trackFor = ""
    private val layerBuf = FloatArray(IndoorGrid.MAX_SLOTS)
    private val EVIDENCE_MS = 12_000L    // after this long with no proof, say so
    private val PROVE_MARGIN = 3.0f      // yd to the next candidate before a cell proves it

    private fun placeHere(m: JSONObject): Place? {
        val cont = rect?.continent ?: return null
        ensurePlaces(cont)
        if (!m.optBoolean("indoors", false)) return null
        if (placesFor != cont) return null            // still parsing on the other thread
        val wx = m.optDouble("wx", Double.NaN)
        val wy = m.optDouble("wy", Double.NaN)
        if (wx.isNaN() || wy.isNaN()) return null
        // SMALLEST match, not the first. Footprints nest: a tavern sits inside the district
        // WMO that contains it, and a Caverns of Time arm is a 900 yd box that would swallow
        // anything sharing its map. The most specific building is the one you are in.
        return places?.filter { it.contains(wx, wy) }
            ?.minByOrNull { (it.wxmax - it.wxmin) * (it.wymax - it.wymin) }
    }

    private fun track(p: Place, wx: Double, wy: Double) {
        val g = gridFor(p) ?: return
        if (trackFor != p.fid) {             // entered a different building: start over
            trackFor = p.fid
            zEst = Float.NaN
            pinnedLevel = -1
            dropHold = 0
            provisionalUsed = false
        }
        val lx = p.localX(wx, wy)
        val ly = p.localY(wx, wy)
        val now = android.os.SystemClock.elapsedRealtime()
        val dt = if (zAt == 0L) 0f else (now - zAt) / 1000f
        val moved = if (zAt == 0L) 0f else hypot(lx - zLx, ly - zLy).toFloat()
        zAt = now; zLx = lx; zLy = ly

        val n = g.layersAt(lx, ly, layerBuf)
        if (n == 0) return                   // off the walkable set: hold, do not invent
        val proves = n == 1

        if (zEst.isNaN()) {
            if (proves) { zEst = layerBuf[0]; zEvidenceAt = now; return }
            // Ambiguous cell and no history. Showing nothing at all is the honest answer but
            // a poor one on the FIRST tick after walking in the door, so take the lowest
            // candidate ONCE -- you nearly always enter a building at the bottom -- and mark
            // it unproven, which draws it at 55% with a hollow dot. If that guess is wrong
            // the gate throws it out within a step or two, and after that no more guessing:
            // the app waits for a cell that can only mean one thing. Upstairs in this inn
            // that is a median 2.5 yd away.
            if (!provisionalUsed) { provisionalUsed = true; zEst = layerBuf[0]; zEvidenceAt = 0L }
            return
        }
        // A LOG GAP IS NOT A TELEPORT, BUT IT IS NOT EVIDENCE EITHER. The stream is block
        // buffered and can go quiet; a quiet stretch across a staircase would otherwise be
        // resolved as "nearest to where I was", which is the old floor, with no way back.
        if (dt > 1.5f && !proves) { zEst = Float.NaN; return }

        val up = 0.6f + 1.8f * moved
        val down = min(2.5f + 3.0f * moved, 12f)
        var best = Float.NaN
        var bestD = Float.MAX_VALUE
        var second = Float.MAX_VALUE
        for (i in 0 until n) {
            val d = layerBuf[i] - zEst
            if (d > up || d < -down) continue
            val ad = abs(d)
            if (ad < bestD) { second = bestD; bestD = ad; best = layerBuf[i] }
            else if (ad < second) second = ad
        }
        if (best.isNaN()) { zEst = Float.NaN; return }

        // CHARGE FOR A BIG DROP. A stairwell lip or a balcony rail puts a cell 7 yd below the
        // one beside it, and taking that in one tick teleports the map to the floor below
        // while the player is standing still on the landing. Make a drop prove itself.
        if (zEst - best > 2.5f && moved < 1.5f) {
            if (++dropHold < 3) return
        }
        dropHold = 0
        zEst = best
        if (proves || second - bestD > PROVE_MARGIN) zEvidenceAt = now
    }

    /** true when the tracker has been running on guesswork for a while. */
    private fun floorUnsure() =
        zEst.isNaN() || zEvidenceAt == 0L ||
        android.os.SystemClock.elapsedRealtime() - zEvidenceAt > EVIDENCE_MS

    /**
     * Which storeys to draw, as [lowerIndex, upperIndex, u]. u is 0 on a level floor and
     * sweeps 0..1 across the middle of a staircase, which drives both the crossfade and the
     * camera so the map travels up the building instead of popping. -1 means "unknown".
     */
    private fun floorBlend(p: Place): FloatArray {
        if (pinnedLevel >= 0) return floatArrayOf(pinnedLevel.toFloat(), pinnedLevel.toFloat(), 0f)
        val L = p.levels
        return IndoorFloorBlend.atHeight(zEst, L.size, { L[it].zlo }, { L[it].zhi })
    }

    // A CACHE BUDGETED IN BYTES, NOT IN ENTRIES. A city district's plan is 1400x1047 ARGB,
    // which is 5.9 MB each; a twelve-storey one drawn with all its ghosts would ask for
    // fourteen of them at once. Counting entries says "8" and means 47 MB, and this device's
    // lowmemorykiller has already taken the game once.
    private var planBytes = 0L
    private val PLAN_BUDGET = 24L * 1024 * 1024

    // DECODED OFF THE UI THREAD (D's full-catalogue report, 2026-10-13): a district's plan is 1400x1047, and decoding it inside
    // onDraw stalled the first frame in every newly entered building. A plan not decoded yet is asked for here and drawn when it
    // lands (until then the outdoor map, or the storey already showing, stands in). One decoder thread, so two 5.9 MB plans never
    // decode at once; a plan that cannot be decoded is not asked for again until the snapshot changes.
    private val planWanted = HashSet<String>()
    private var planGen = 0
    private val planDecoder by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "map-plans").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }

    private fun planBitmap(name: String?, half: Boolean = false): Bitmap? {
        if (name == null) return null
        planCache[name]?.let {
            planCache.remove(name); planCache[name] = it       // touch: keep LRU honest
            return it
        }
        if (!planWanted.add(name)) return null
        val file = runCatching { interiorFile(name) }.getOrNull() ?: return null     // a bad asset reference: never asked again
        val gen = planGen
        planDecoder.execute {
            val bm = try {
                val o = BitmapFactory.Options()
                // A ghost is a 1 px contour drawn small and faint; half resolution is free.
                if (half) o.inSampleSize = 2
                BitmapFactory.decodeFile(file.path, o)
            } catch (t: Throwable) { null }
            post { planArrived(gen, name, bm) }
        }
        return null
    }

    private fun planArrived(gen: Int, name: String, bm: Bitmap?) {
        if (gen != planGen) { bm?.recycle(); return }       // the snapshot changed while it decoded
        if (bm == null) return                               // stays wanted: never retried every frame
        planWanted.remove(name)
        planCache[name] = bm
        planBytes += bm.byteCount.toLong()
        while (planBytes > PLAN_BUDGET && planCache.size > 2) {
            val k = planCache.keys.first()
            planCache.remove(k)?.let { planBytes -= it.byteCount.toLong(); it.recycle() }
        }
        invalidate()
    }

    /** World AABB of a level, from the four corners of its local rect. */
    private fun levelWorldBox(p: Place, f: Level): DoubleArray {
        val lx = doubleArrayOf(f.lxmin, f.lxmax, f.lxmax, f.lxmin)
        val ly = doubleArrayOf(f.lymin, f.lymin, f.lymax, f.lymax)
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE
        var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (i in 0..3) {
            val wx = p.worldX(lx[i], ly[i]); val wy = p.worldY(lx[i], ly[i])
            if (wx < x0) x0 = wx
            if (wx > x1) x1 = wx
            if (wy < y0) y0 = wy
            if (wy > y1) y1 = wy
        }
        return doubleArrayOf(x0, x1, y0, y1)
    }

    /** Draw one baked local-space plan through the shared world projection. */
    private fun drawPlan(c: Canvas, p: Place, f: Level, bmp: Bitmap, alpha: Int,
                         sx: (Double) -> Float, sy: (Double) -> Float) {
        val r = rect ?: return
        planSrc[0] = 0f; planSrc[1] = 0f
        planSrc[2] = bmp.width.toFloat(); planSrc[3] = 0f
        planSrc[4] = 0f; planSrc[5] = bmp.height.toFloat()
        val lx = doubleArrayOf(f.lxmin, f.lxmax, f.lxmin)
        val ly = doubleArrayOf(f.lymax, f.lymax, f.lymin)
        for (i in 0..2) {
            planDst[i * 2] = sx(r.nx(p.worldY(lx[i], ly[i])))
            planDst[i * 2 + 1] = sy(r.ny(p.worldX(lx[i], ly[i])))
        }
        planMatrix.setPolyToPoly(planSrc, 0, planDst, 0, 3)
        pArt.alpha = alpha
        c.drawBitmap(bmp, planMatrix, pArt)
        pArt.alpha = 255
    }

    private val zoneMatrix = Matrix()
    private var zonePts = FloatArray(256)
    private val zonePath = Path()
    private val pZoneFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pZoneRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = Theme.ZONE_RIM_PX; strokeJoin = Paint.Join.ROUND
    }

    private fun addRing(src: FloatArray) {
        if (zonePts.size < src.size) zonePts = FloatArray(src.size * 2)
        zoneMatrix.mapPoints(zonePts, 0, src, 0, src.size / 2)
        zonePath.moveTo(zonePts[0], zonePts[1])
        var i = 2
        while (i < src.size) { zonePath.lineTo(zonePts[i], zonePts[i + 1]); i += 2 }
        zonePath.close()
    }

    /**
     * A storey's transition zones over its plan, UI Art's look: filled at the zone colour's own alpha
     * with a ZONE_RIM_PX rim, no glyph. The points are mapped to the SCREEN first and stroked there,
     * so the rim is the same width at every zoom. The matrix is built from the storey's full-size w/h,
     * not from a decoded bitmap (the ghost outlines decode at half size). [alpha] follows the storey's
     * own crossfade.
     */
    private fun drawZones(c: Canvas, p: Place, f: Level, alpha: Float,
                          sx: (Double) -> Float, sy: (Double) -> Float) {
        if (f.zones.isEmpty() || f.w <= 0 || f.h <= 0 || alpha <= 0.01f) return
        val r = rect ?: return
        planSrc[0] = 0f; planSrc[1] = 0f
        planSrc[2] = f.w.toFloat(); planSrc[3] = 0f
        planSrc[4] = 0f; planSrc[5] = f.h.toFloat()
        val lx = doubleArrayOf(f.lxmin, f.lxmax, f.lxmin)
        val ly = doubleArrayOf(f.lymax, f.lymax, f.lymin)
        for (i in 0..2) {
            planDst[i * 2] = sx(r.nx(p.worldY(lx[i], ly[i])))
            planDst[i * 2 + 1] = sy(r.ny(p.worldX(lx[i], ly[i])))
        }
        zoneMatrix.setPolyToPoly(planSrc, 0, planDst, 0, 3)
        for (z in f.zones) {
            zonePath.reset()
            zonePath.fillType = Path.FillType.EVEN_ODD
            addRing(z.ring)
            for (h in z.holes) addRing(h)
            val fill = when (z.kind) { 0 -> Theme.zoneExit; 1 -> Theme.zoneUp; else -> Theme.zoneDown }
            val rim = when (z.kind) { 0 -> Theme.zoneExitRim; 1 -> Theme.zoneUpRim; else -> Theme.zoneDownRim }
            pZoneFill.color = fill
            pZoneFill.alpha = (Color.alpha(fill) * alpha).toInt()
            pZoneRim.color = rim
            pZoneRim.alpha = (255 * alpha).toInt()
            c.drawPath(zonePath, pZoneFill)
            c.drawPath(zonePath, pZoneRim)
        }
    }

    // ---- AUTO-FRAMING -------------------------------------------------------------------
    // The view decides what it should CONTAIN and solves for centre+zoom, instead of holding a
    // zoom number the player has to manage. Four situations, in priority order:
    //   indoors   a cave or a building: the space that matters is tens of yards across, so
    //             frame that and nothing else. The addon sends IsIndoors() on every tick.
    //   arrived   the nearest tracked objective is within ARRIVE_YD: frame you AND it, so the
    //             last hundred yards of "where actually is it" are legible.
    //   cruising  objectives known but far: frame you and them together, capped, so the map
    //             answers "which way" without shrinking to a postage stamp.
    //   idle      no objectives: a fixed comfortable radius around you.
    // All distances are YARDS and converted through the zone rect, so a small zone and a huge
    // one frame the same amount of ground rather than the same fraction of the map.
    // ALWAYS ON since 2026-10-02 (the user: "I want to remove the dynamic map toggle, as I REALLY love the way that it currently
    // works"): Settings' Dynamic zoom switch and its fixed Indoor / Outdoor widths are gone. Pinch or +/- still turn it off
    // for the moment; a double-tap or selecting a quest turns it back on.
    private var autoZoom = true

    // indoors or not (the map document's flag; the small map's two widths follow it), and the glide of a zoom change
    private var indoorSeen = false
    private var zoomEase = Float.NaN               // target of a gliding zoom change; NaN = none
    private var lastZoomEaseAt = 0L
    private val ZOOM_EASE_TAU = 0.18f              // seconds

    init {
        Settings.onChange { applySettings() }
    }

    /** Settings changed (from the Settings room): take them on now. */
    fun applySettings() {
        postInvalidate()                           // the small map's widths are read every frame
    }

    private fun noteIndoor(m: JSONObject?) {
        val ind = m?.optBoolean("indoors", false) ?: return
        if (ind != indoorSeen) indoorSeen = ind
    }

    /** zoom factor that shows [yards] across the map width in the current zone, or NaN */
    private fun zoomForYards(yards: Int): Float {
        val r = rect ?: return Float.NaN
        if (!r.valid) return Float.NaN
        return (r.width.toFloat() / yards).coerceIn(1f, ZOOM_MAX)
    }

    /** once per frame: glide a zoom change (a focus's restore) */
    private fun stepFixedZoom() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (zoomEase.isNaN()) return
        val dt = if (lastZoomEaseAt == 0L) 0.016f else ((now - lastZoomEaseAt) / 1000f).coerceIn(0f, 0.1f)
        lastZoomEaseAt = now
        // ease in log space: a zoom change reads as even steps of scale, not of the number
        val k = 1f - exp(-dt / ZOOM_EASE_TAU)
        zoom = exp(ln(zoom) + (ln(zoomEase) - ln(zoom)) * k).coerceIn(1f, ZOOM_MAX)
        if (abs(ln(zoom / zoomEase)) < 0.01f) { zoom = zoomEase; zoomEase = Float.NaN }
        else postInvalidateOnAnimation()
    }
    private var autoCx = 0.5f
    private var autoCy = 0.5f
    private var autoZoomCur = 3f
    private val CAM_TAU = 0.45f        // seconds; the camera's easing time constant
    private var lastCamAt = 0L
    // THE CAMERA FOLLOWS THE PLAYER RIGIDLY AND EASES ONLY THE FRAMING. It used to ease the
    // whole centre, so while walking the view trailed the arrow by speed x CAM_TAU and kept
    // sliding for a second after a stop -- the map visibly arriving late, on top of whatever
    // the data pipeline cost. Now each frame says how much its centre moves WITH the player
    // (the anchor: 1 = centred on you, 0.5 = midpoint of you and an objective, 0 = a building
    // that does not move), that share is applied directly, and only the rest -- the framing
    // decision itself -- is eased. Walking is instant; changing what is framed still glides.
    private var camResX = 0f
    private var camResY = 0f
    private var camAncX = Float.NaN    // NaN = re-seed from what is on screen
    private var camAncY = 0f
    private var camLastPx = 0f
    private var camLastPy = 0f
    private var lastViewCx = 0.5f
    private var lastViewCy = 0.5f
    private val INDOOR_YD = 70f
    private val ARRIVE_YD = 150f
    private val ARRIVE_PAD_YD = 45f
    private val CRUISE_YD = 260f
    private val MAX_FRAME_YD = 1100f   // never zoom out past this to reach a far objective

    /** @return [centreX, centreY, zoom, anchorX, anchorY] in map space (anchor = how much
     *  the centre moves with the player), or null if we cannot measure yards. */
    private fun computeFrame(m: JSONObject, px: Double, py: Double): FloatArray? {
        val r = rect ?: return null
        if (!r.valid || px.isNaN() || py.isNaN()) return null
        val yw = r.width.toFloat()
        val yh = r.height.toFloat()
        val cx = px.toFloat()
        val cy = py.toFloat()

        fun frameRadius(yards: Float): FloatArray {
            val half = (yards / yh).coerceAtLeast(0.5f / ZOOM_MAX)
            return floatArrayOf(cx, cy, (0.5f / half), 1f, 1f)
        }

        if (m.optBoolean("indoors", false)) {
            // frame the actual building when we have its floorplan, not a guessed radius.
            // On the stairs the two storeys' rects are BLENDED by the same u that crossfades
            // them, so the camera travels up the building rather than jumping at the top.
            val pl = placeHere(m)
            if (pl != null) {
                val bl = floorBlend(pl)
                val lo = bl[0].toInt()
                val hiIdx = bl[1].toInt()
                val box = if (lo >= 0) {
                    val a0 = levelWorldBox(pl, pl.levels[lo])
                    val a1 = levelWorldBox(pl, pl.levels[hiIdx])
                    DoubleArray(4) { a0[it] + (a1[it] - a0[it]) * bl[2] }
                } else {
                    // unsure which storey: frame the whole building, which is also the honest
                    // picture -- every storey is on screen as a ghost
                    doubleArrayOf(pl.wxmin, pl.wxmax, pl.wymin, pl.wymax)
                }
                val nx0 = r.nx(box[3]).toFloat(); val nx1 = r.nx(box[2]).toFloat()
                val ny0 = r.ny(box[1]).toFloat(); val ny1 = r.ny(box[0]).toFloat()
                val padX = 12f / yw; val padY = 12f / yh
                val half = max(max(abs(nx1 - nx0) / 2f + padX, abs(ny1 - ny0) / 2f + padY),
                               0.5f / ZOOM_MAX)
                return floatArrayOf((nx0 + nx1) / 2f, (ny0 + ny1) / 2f, 0.5f / half, 0f, 0f)
            }
            return frameRadius(INDOOR_YD)
        }

        // tracked objectives on this map. A quest ready to turn in is framed at its TURN-IN
        // point (questXY), since that is where the player is going next. Available quests
        // ("avail") are deliberately left out: offers, not destinations, and a zone can hold
        // dozens that would drag the frame out to the whole map.
        var minX = cx; var maxX = cx; var minY = cy; var maxY = cy
        var nearest = Float.MAX_VALUE
        var nearestX = 0f; var nearestY = 0f
        var any = false
        m.optJSONArray("quests")?.let { a ->
            for (i in 0 until a.length()) {
                val q = a.optJSONObject(i) ?: continue
                if (!shownQuest(q.optInt("questID", 0))) continue     // untracked: not on the map at all
                val src = questXY(q, qxy)
                if (src == 0) continue
                val qx = qxy[0].toFloat()
                val qy = qxy[1].toFloat()
                any = true
                // The addon's `dist` is the game's distance to the QUEST, and nothing says it is
                // measured to the turn-in point, so for that point measure it here, in yards,
                // through the same rect the frame is solved in.
                val d = if (src == 2) hypot((qx - cx) * yw, (qy - cy) * yh)
                        else if (q.has("dist")) q.optDouble("dist").toFloat() else Float.MAX_VALUE
                if (d < nearest) { nearest = d; nearestX = qx; nearestY = qy }
                minX = min(minX, qx); maxX = max(maxX, qx)
                minY = min(minY, qy); maxY = max(maxY, qy)
            }
        }
        // a QuestieDB guess stands in for the game's missing point (deriveQdb): each guessed quest's lead spot is framed like one
        // (the emulator look, 2026-10-06: at the automatic zoom the guesses sat outside the view)
        for (s in qdbSpots) {
            if (!s.lead) continue
            val qx = s.x.toFloat(); val qy = s.y.toFloat()
            any = true
            val d = hypot((qx - cx) * yw, (qy - cy) * yh)
            if (d < nearest) { nearest = d; nearestX = qx; nearestY = qy }
            minX = min(minX, qx); maxX = max(maxX, qx)
            minY = min(minY, qy); maxY = max(maxY, qy)
        }
        // THE SELECTED QUEST (the user's 2026-09-30 rule, see revealSelected): the frame is the player and that quest, however
        // far (up to the whole zone), instead of the nearest objective
        // THE CORPSE (a ghost): the player and the corpse, up to the whole zone, as for a selected quest; a corpse on another
        // map leaves the framing alone (the rim arrow points the way)
        if (corpseOnMap()) {
            val gx = corpseX.toFloat(); val gy = corpseY.toFloat()
            val f = solveFrame(cx, cy, min(cx, gx), max(cx, gx), min(cy, gy), max(cy, gy), yw, yh, capYd = Float.MAX_VALUE)
            // 1.4 times wider about the same centre (so the anchor that makes the view follow the player is unchanged): at a
            // far corpse's zoom solveFrame's 45 yd pad is ~30 px, and the stone must stand clear of the arrow's band at the edge
            // (seen on the emulator: half a stone at the edge with the arrow beside it)
            f[2] = max(1f, f[2] / 1.4f)
            return f
        }
        val sp = selectedPoint()
        if (sp != null) {
            minX = min(cx, sp.x.toFloat()); maxX = max(cx, sp.x.toFloat())
            minY = min(cy, sp.y.toFloat()); maxY = max(cy, sp.y.toFloat())
            return solveFrame(cx, cy, minX, maxX, minY, maxY, yw, yh, capYd = Float.MAX_VALUE)
        }
        if (!any) return frameRadius(CRUISE_YD)

        // ARRIVED: frame just the player and that one objective, tightly.
        if (nearest <= ARRIVE_YD) {
            minX = min(cx, nearestX); maxX = max(cx, nearestX)
            minY = min(cy, nearestY); maxY = max(cy, nearestY)
        }
        return solveFrame(cx, cy, minX, maxX, minY, maxY, yw, yh, capYd = MAX_FRAME_YD)
    }

    /**
     * The frame around a box (map space) holding the player at ([cx], [cy]), padded, capped at [capYd] across (and at the
     * whole zone). The quest list covers the field's left strip, so the box must fit in the part right of it: the width is
     * widened by the list's share, and the projection's own shift (cxView) centres it there.
     */
    private fun solveFrame(cx: Float, cy: Float, minX: Float, maxX: Float, minY: Float, maxY: Float,
                           yw: Float, yh: Float, capYd: Float): FloatArray {

        val padX = ARRIVE_PAD_YD / yw
        val padY = ARRIVE_PAD_YD / yh
        val visible = if (lastFieldW > 0f) ((lastFieldW - lastTrackPx) / lastFieldW).coerceIn(0.4f, 1f) else 1f
        var halfX = ((maxX - minX) / 2f + padX) / visible
        var halfY = (maxY - minY) / 2f + padY
        // cap how far out we will go chasing a distant objective (a SELECTED quest: up to the whole zone)
        halfX = min(halfX, min(capYd / yw, 0.5f))
        halfY = min(halfY, min(capYd / yh, 0.5f))
        val half = max(max(halfX, halfY), 0.5f / ZOOM_MAX)
        // the centre is the bbox midpoint, so it moves with the player by half for each side
        // of the box the player IS
        var ax = (if (minX == cx) 0.5f else 0f) + (if (maxX == cx) 0.5f else 0f)
        var ay = (if (minY == cy) 0.5f else 0f) + (if (maxY == cy) 0.5f else 0f)
        var mx = (minX + maxX) / 2f
        var my = (minY + maxY) / 2f
        // THE PLAYER STAYS ON SCREEN (FrameKeep, p-map-center): a CAPPED box (objectives wider apart than capYd) kept the box's
        // middle as the centre, and the capped view could leave the player at its edge or off it. The centre slides toward the
        // player until the player is inside FrameKeep.SHARE of the visible half (x: right of the list, which the projection
        // centres on; y: from the field's shape, as fullMapping / onDraw solve it), and then follows the player one for one.
        // Not at whole-zone view (half 0.5: everything shows).
        if (half < 0.5f) {
            val ratio = if (lastFieldW > 0f && lastFieldH > 0f) lastFieldH / lastFieldW else 2f / 3f
            val kx = FrameKeep.keep(mx, cx, half * visible)
            val ky = FrameKeep.keep(my, cy, half * yw / yh * ratio)
            if (kx.second) { mx = kx.first; ax = 1f }
            if (ky.second) { my = ky.first; ay = 1f }
        }
        return floatArrayOf(mx, my, 0.5f / half, ax, ay)
    }

    // ---- BUTTON FADE. The controls are needed for a second and then they are just three
    // bright rectangles sitting on the map. They hold full opacity briefly after any touch and
    // then sink to a ghost, so the terrain underneath is never permanently covered.
    private var lastTouchAt = 0L
    private val FADE_HOLD_MS = 1800L
    private val FADE_MS = 450f
    private val FADE_MIN = 0.16f

    private fun buttonAlpha(): Float {
        val since = System.currentTimeMillis() - lastTouchAt
        if (since < FADE_HOLD_MS) return 1f
        val t = ((since - FADE_HOLD_MS) / FADE_MS).coerceIn(0f, 1f)
        return 1f - (1f - FADE_MIN) * t
    }

    private val RECENTRE_MS = 650f     // long enough to read as a move, short enough not to nag
    private val MOVE_EPS = 0.002f      // ~7 yards in Elwynn; below this it is sampling jitter
    private val DRAG_GRACE_MS = 350L   // do not glide between two scroll events of one drag

    private var lastSide = 0f
    private var lastFieldW = 0f
    private var lastFieldH = 0f
    private var lastFieldTop = 0f
    private var lastTrackPx = 0f        // the quest list's strip over the field's left side, last frame
    private var lastHalfX = 0f          // the Map room's projection, for the drag maths
    private var lastHalfY = 0f

    private var btnIn = RectF()
    private var btnOut = RectF()
    private var btnHome = RectF()
    private var btnFilter = RectF()
    // The floor spine: a column of slabs on the right edge, HIGHEST AT THE TOP, so it reads
    // as a little side elevation of the building rather than as a list. It says how many
    // storeys there are, which one you are on, and how sure the app is, in no words at all.
    // It replaces a button labelled "2/3", which had no state, no affordance for how many
    // storeys existed, and made the map's correctness the player's job.
    private val spine = ArrayList<RectF>()

    // ---- the bundled service-POI layer (see PoiStore). Loaded off the UI thread: the Eastern
    // Kingdoms file is 143 KB of JSON and parsing it in onDraw drops frames on this device.
    private var continentId = -1
    private var continentList: List<PoiStore.Poi>? = null
    private var rect: PoiStore.Rect? = null
    private var seed: List<PoiStore.Pin> = emptyList()
    // the filter panel's Show switches are KEPT across app lives (b-map-filters-persist): the role bits here, Chests / Rares
    // ([showChests], [showRares]) beside them, app-wide like the probe's three ("mapprobe") and the Map room's ("maproom")
    private val filterPrefs by lazy { context.getSharedPreferences("mapfilters", Context.MODE_PRIVATE) }
    private var mask = runCatching { filterPrefs.getInt("mask", PoiStore.DEFAULT_MASK) }.getOrDefault(PoiStore.DEFAULT_MASK)
    private var filterOpen = false
    private val filterHits = HashMap<Int, RectF>()
    private val filterBox = RectF()          // the open panel, as last drawn
    private var seedNote = ""
    private var zoneMatch: PoiStore.ZoneMatch? = null

    // ---- COMPACT MODE: the same map as Home's minimap (user ruling 2026-09-28: "My companion
    // map, full right side", no compass ring). Home BORROWS this one instance -- it moves the view
    // into its own frame and sets compact -- because a second MapView would hold a second copy
    // of the zone art, the floorplans, the arrow's trail and the floor tracker.
    // In compact mode the map FILLS the view (cropped, not letterboxed), always follows the
    // player at its own width in yards (Settings.minimapYd), and draws no header, buttons, floor
    // spine or labels. It reads the Map room's zoom, pan and camera but never writes them, so the
    // Map room comes back exactly as it was left. A tap goes to the host.
    // Nothing is allocated per size: Home resizes the view every frame during its transitions.
    var compact = false
        set(v) {
            if (field == v) return
            field = v
            filterOpen = false
            pmenu.close(); closePanel()
            compactDown = false
            lastCamAt = 0L                  // the Map room's camera eases from a fresh clock
            postInvalidate()
        }
    /**
     * Pixels kept clear at the RIGHT end of the header, for a host that draws a control over it
     * (Home's close button while it shows the map full screen). The coordinates and the age line
     * move left by this much. 0 in the Map room.
     */
    var headerRightInset = 0f

    /**
     * The full map's 72 px header band (zone, subzone, coordinates, "Ns old"). Home sets this false
     * while it hosts the full map (user 2026-09-29 15:08: "when they go full screen we need to get
     * rid of the ugly top bars and use the B glyph on the home button"), so the field starts at the
     * top; the Map room leaves it true. Without the band, a stale stream still says so, small, in the
     * field's top-left corner. No effect in [compact] mode, which never draws the band.
     */
    var showHeader = true
        set(v) { if (field != v) { field = v; invalidate() } }
    private var staleNote: String? = null      // headerless: the stream's age, drawn over the field
        set(v) { if (field != v) { field = v; postInvalidate() } }

    /**
     * Pixels the COMPACT map's own +/- pair ([compactZoomButtons], top right) is moved down. The full
     * map's buttons are at the bottom right since 2026-09-30 and use [buttonBottomInset] instead.
     */
    var buttonTopInset = 0f
        set(v) { if (field != v) { field = v; postInvalidate() } }

    /** called for a tap while [compact]; the host opens the full map */
    var onCompactTap: (() -> Unit)? = null

    /**
     * Zoom buttons on the COMPACT map (Home's minimap; user 2026-09-29 16:35: "I want to have the zoom
     * in and out buttons on the minimap - these buttons should directly overwrite the minimap zoom in
     * the settings"). They step Settings.minimapYd through Settings.ZOOM_STEPS_YD -- the very value the
     * Settings row shows -- so the two can never disagree. The host turns them on; a tap on a button
     * zooms and never opens the full map.
     */
    var compactZoomButtons = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private var cBtnIn = RectF()            // touch boxes, at least MIN_TOUCH_PX each way (48 dp)
    private var cBtnOut = RectF()
    private val cBtnDrawIn = RectF()        // the drawn buttons, centred in their boxes
    private val cBtnDrawOut = RectF()
    private var compactBtnDown = 0          // -1 / +1: the button a finger went down on, 0 none
    private val MIN_TOUCH_PX = 112f         // 48 dp on the Thor's 369 dpi screen is ~111 px
    // The full map's buttons sit at the BOTTOM RIGHT (user 2026-09-30, via tracking: "the + and - buttons should
    // be in the same positoins, at the bottom, move the filter button to the bottom right as well"; reverses the
    // 2026-09-29 "filter at the top left"): exactly where Home puts the small map's pair (HomeView ZOOM_BOX 112 /
    // ZOOM_DRAWN 104, 4 px from the edges) -- minus in the corner, plus above it, the filter left of minus. btnIn /
    // btnOut / btnFilter are the 112 px TOUCH boxes; these are the 104 px buttons drawn centred in them.
    private val ROOM_EDGE = 4f
    private val btnInDraw = RectF()
    private val btnOutDraw = RectF()
    private val btnFilterDraw = RectF()
    private fun centred(dst: RectF, box: RectF, size: Float) =
        dst.set(box.centerX() - size / 2f, box.centerY() - size / 2f, box.centerX() + size / 2f, box.centerY() + size / 2f)

    /** Pixels the full map's bottom-right buttons are lifted, for a host whose own control covers that corner. */
    var buttonBottomInset = 0f
        set(v) { if (field != v) { field = v; invalidate() } }
    // TWO WIDTHS since 2026-10-02 (the user: "we need to split the minimap default zoom toggle into an indoor and outdoor"):
    // Settings.minimapYd outdoors, Settings.minimapIndoorYd indoors (the map document's `indoors`), each its own Settings row;
    // the +/- step the one in use. (The old rule, min(minimapYd, indoorYd) indoors with a cap released by the buttons, is gone.)

    /** The width the small map shows now, in yards (Home greys its +/- at the ends with this). */
    fun minimapYdNow(): Int = compactYd()

    /** The width the small map shows now, in yards. */
    private fun compactYd(): Int =
        if (indoorSeen) Settings.minimapIndoorYd else Settings.minimapYd

    /**
     * One step of the minimap zoom: [dir] -1 = closer (fewer yards), +1 = wider. It steps from the width
     * on screen and WRITES Settings.minimapYd -- the value the Settings row shows (the user's ask).
     */
    fun stepMinimapZoom(dir: Int) {
        val next = Settings.stepYd(compactYd(), dir)
        if (indoorSeen) Settings.minimapIndoorYd = next else Settings.minimapYd = next
        invalidate()
    }

    // ---- HOME'S OPEN AND CLOSE, SMOOTH (the user, 2026-10-01: "when the map opens it seamlessly transitions to the top spot of
    // the quests list. On the flip side when the map closes I want that transition to go back the other way. Similarly I want
    // to make sure that the transition in map size and place is smooth"). Home grows / shrinks the map's slot with one animator
    // and calls [morph] from it every frame. While it runs, the FULL map's parts draw whichever way it goes:
    //  * the map itself glides: the scale in log space and the player's spot on a straight line (Home's px), from what was on
    //    screen when it began to the other mode's framing at the slot's FINAL size (the small map's yards, or the full map's own
    //    framing), so the art never jumps while the box grows; at the end the full map's camera is handed exactly that frame;
    //  * the full map's own parts (list, buttons, labels, controls line, numbered bubbles) fade in on the way up and out on the
    //    way down, the small map's dots and "?" handing over to the bubbles;
    //  * THE FOCUSED QUEST'S CARD flies between [card] (Home's module, Home's px) and the list's top spot, where it lives (the
    //    top spot always holds the focused quest: the user's answer, 2026-10-01). Home hides its own card while it flies; Home's
    //    view and the map's slot do not clip their children, so it can fly outside the map's box ([drawFlight]).
    // Touches wait until it ends.
    private var morphOn = false
    private var morphK = 0f
    private var morphOpening = true
    private val morphFrom = RectF()
    private val morphTo = RectF()
    private var morphCard: RectF? = null
    private var morphOk = false                    // the start's framing was known (else only the fades and the flight)
    private var morphP0x = 0f
    private var morphP0y = 0f
    private var morphS0 = 0f
    private var morphHints = false                 // closing: the controls line was showing when it began
    private var morphLastAt = 0L
    private val MORPH_STALE_MS = 700L              // no call for this long: ended (a host that stopped calling)
    private val morphMap = FloatArray(6)
    // what the last frame drew: the player's spot in HOME's px, the scale (px per map-x unit), the player in map space
    private var drawnPx = Float.NaN
    private var drawnPy = Float.NaN
    private var drawnS = 0f
    private var drawnMx = Double.NaN
    private var drawnMy = Double.NaN
    private var drawnHints = false
    private val flyLand = RectF()                  // the list's top spot as the list puts it (view px); empty: none
    private val flyTmp = RectF()

    /**
     * One frame of Home's open / close: [k] 0..1 along its animator (1 ends it), [from] / [to] the map slot's rects in Home's
     * px, [opening] small -> full, [card] Home's focused-quest card in Home's px (null: no focused quest, nothing flies).
     * Home calls it with 0 as its animator starts (right after it sets [compact]), from the update listener, and with 1 from
     * onAnimationEnd (a cancel too). A new direction or new rects start a new glide from what is on screen.
     */
    fun morph(k: Float, from: RectF, to: RectF, opening: Boolean, card: RectF?) {
        if (k >= 1f) { if (morphOn) morphEnd(); return }
        if (!morphOn || opening != morphOpening || from != morphFrom || to != morphTo) {
            morphHints = drawnHints
            morphOn = true
            morphOpening = opening
            morphFrom.set(from); morphTo.set(to)
            morphOk = !drawnPx.isNaN() && !drawnPy.isNaN() && drawnS > 0f
            morphP0x = drawnPx; morphP0y = drawnPy; morphS0 = drawnS
            filterOpen = false
            pmenu.close(); closePanel()
            touchOwner = OWN_NONE
            if (opening) {
                // the list starts settled, the focused quest in its top spot
                listFirst = 0; listShow(shownFocus())
                rowPos.clear(); listScroll = Float.NaN; listGroundH = Float.NaN
                openTabs.clear(); rebuildItems()          // every opening starts with the Untracked tabs closed (the user, 2026-10-16)
            }
        }
        morphCard = card?.let { RectF(it) }
        morphK = k.coerceIn(0f, 1f)
        morphLastAt = android.os.SystemClock.elapsedRealtime()
        invalidate()
    }

    // UI Art's flight (the Home picker's proposal): the list's other cards fade in over the open's LAST 120 of 320 ms and out over
    // the close's FIRST 100 of 260 ms; on FastOutSlowIn (0.4, 0, 0.2, 1) those are k >= 0.894 opening and k <= 0.581 closing
    private val ROWS_IN_K = 0.894f
    private val ROWS_OUT_K = 0.581f
    private fun morphRowsAlpha(): Float = if (!morphOn) 1f
        else if (morphOpening) ((morphK - ROWS_IN_K) / (1f - ROWS_IN_K)).coerceIn(0f, 1f)
        else (1f - morphK / ROWS_OUT_K).coerceIn(0f, 1f)

    /** how much of the full map shows: 1 outside a glide; along it, k opening and 1 - k closing */
    private fun morphFull(): Float = if (!morphOn) 1f else if (morphOpening) morphK else 1f - morphK

    private fun morphEnd() {
        morphOn = false
        morphCard = null
        if (morphOpening) {
            // the full map's camera carries on from exactly the frame the glide ended on: no settling afterwards
            val m = map
            if (m != null && !drawnMx.isNaN() && !drawnMy.isNaN() && fullMapping(m, drawnMx, drawnMy, morphTo, morphMap)) {
                lastViewCx = morphMap[3]; lastViewCy = morphMap[4]
                autoCx = morphMap[3]; autoCy = morphMap[4]
                if (autoZoom) autoZoomCur = morphMap[5]
                camAncX = Float.NaN
                lastCamAt = 0L
            }
        }
        invalidate()
    }

    /** the view's top-left in its host's px (Home: the map slot's place; the view fills the slot) */
    private fun hostX(): Float = ((parent as? View)?.left ?: 0).toFloat() + this.left
    private fun hostY(): Float = ((parent as? View)?.top ?: 0).toFloat() + this.top

    /**
     * The SMALL map's framing in box [r] (host px): the player at its centre, the width showing [compactYd] yards.
     * [out]: player x, y (host px), scale (px per map-x unit).
     */
    private fun compactMapping(r: RectF, out: FloatArray): Boolean {
        val rc = rect ?: return false
        if (!rc.valid || r.width() <= 0f) return false
        val hx = (compactYd().toFloat() / 2f / rc.width.toFloat()).coerceIn(0.5f / ZOOM_MAX / 4f, 0.5f)
        out[0] = r.centerX(); out[1] = r.centerY() + compactDown(); out[2] = r.width() / (2f * hx)    // lower under the card
        return true
    }

    /**
     * The FULL map's framing in box [r] (host px), as onDraw would settle on it there: its own dynamic framing (computeFrame at
     * that size), else its zoom and centre (the player, or the pan); the list's strip shift; no header while Home hosts it.
     * [out]: player x, y (host px), scale, centre x, y (map space), zoom.
     */
    private fun fullMapping(m: JSONObject, px: Double, py: Double, r: RectF, out: FloatArray): Boolean {
        val rc = rect ?: return false
        if (!rc.valid || px.isNaN() || py.isNaN() || r.width() <= 0f || r.height() <= 0f) return false
        val hh = if (showHeader) 72f else 0f
        val fw = r.width(); val fh = r.height() - hh
        if (fh <= 0f) return false
        var zT: Float; var cxT: Float; var cyT: Float
        var fr: FloatArray? = null
        if (autoZoom && followPlayer && recentreFrom == null) {
            val keepW = lastFieldW; val keepH = lastFieldH; val keepT = lastTrackPx
            lastFieldW = fw; lastFieldH = fh
            lastTrackPx = if (showTracker && items.isNotEmpty()) min(TRACKER_W, fw * 0.34f) + BTN_GAP else 0f
            fr = computeFrame(m, px, py)
            lastFieldW = keepW; lastFieldH = keepH; lastTrackPx = keepT
        }
        if (fr != null) { zT = fr[2].coerceIn(1f, ZOOM_MAX); cxT = fr[0]; cyT = fr[1] }
        else {
            zT = if (!zoomEase.isNaN()) zoomEase else zoom
            cxT = if (followPlayer) px.toFloat() else panX
            cyT = if (followPlayer) py.toFloat() else panY
        }
        val zoneView = zT <= 1.001f
        if (zoneView) { cxT = 0.5f; cyT = 0.5f }
        val hx = 0.5f / zT
        val hy = hx * rc.width.toFloat() / rc.height.toFloat() * (fh / fw)
        val tp = if (showTracker && items.isNotEmpty() && !zoneView) min(TRACKER_W, fw * 0.34f) + BTN_GAP else 0f
        val cxV = cxT - (tp / 2f) / fw * (2f * hx)
        out[0] = r.left + (px.toFloat() - (cxV - hx)) / (2f * hx) * fw
        out[1] = r.top + hh + (py.toFloat() - (cyT - hy)) / (2f * hy) * fh
        out[2] = fw / (2f * hx)
        out[3] = cxT; out[4] = cyT; out[5] = zT
        return true
    }

    /** an objective's gold dot in its dark rim, at [alpha] (the small map's mark; Home's open hands it over to the bubble) */
    private fun objDot(c: Canvas, x: Float, y: Float, alpha: Float) {
        val a = alpha.coerceIn(0f, 1f)
        pFill.color = Color.argb((170 * a).toInt(), 0, 0, 0)
        c.drawCircle(x, y, OBJ_DOT_R + OBJ_RIM_PX, pFill)
        pFill.color = cGold
        pFill.alpha = (255 * a).toInt()
        c.drawCircle(x, y, OBJ_DOT_R, pFill)
        pFill.alpha = 255
    }

    private var compactDown = false
    private var compactCheckedAt = 0L
    private var compactForcedNow = false
    private fun compactForced(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - compactCheckedAt > 1000L) {
            compactCheckedAt = now
            compactForcedNow = android.util.Log.isLoggable("WCCOMPACT", android.util.Log.DEBUG)
        }
        return compactForcedNow
    }
    private var compactDownX = 0f
    private var compactDownY = 0f
    private val compactSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val OWN_NONE = 0
    private val OWN_LIST = 1
    private val OWN_CONTROL = 2
    private var touchOwner = OWN_NONE
    private var listDownY = 0f
    private var listDragY = 0f
    private var listMoved = false
    private val LIST_DRAG_ROW = 70f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (morphOn) return true                 // Home's open / close is running: touches wait until it lands
        if (compact) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    compactBtnDown = when {
                        compactZoomButtons && cBtnIn.contains(e.x, e.y) -> -1
                        compactZoomButtons && cBtnOut.contains(e.x, e.y) -> 1
                        else -> 0
                    }
                    compactDown = compactBtnDown == 0; compactDownX = e.x; compactDownY = e.y
                    // a press on a button is the touch that brings the faded buttons back (user 17:00:
                    // "make sure it fades like normal"); a tap elsewhere opens the full map instead
                    if (compactBtnDown != 0) { lastTouchAt = System.currentTimeMillis(); postInvalidate() }
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> { compactDown = false; compactBtnDown = 0 }
                MotionEvent.ACTION_UP -> {
                    if (compactBtnDown != 0) {
                        // on the finger's release, like every other tap in the app, and only if it
                        // is still on the button it went down on
                        val r = if (compactBtnDown < 0) cBtnIn else cBtnOut
                        if (isShown && r.contains(e.x, e.y)) stepMinimapZoom(compactBtnDown)
                        compactBtnDown = 0
                    } else {
                        val tap = compactDown && isShown &&
                            hypot(e.x - compactDownX, e.y - compactDownY) < compactSlop
                        compactDown = false
                        if (tap) onCompactTap?.invoke()
                    }
                }
            }
            return true
        }
        // any touch wakes the controls back to full opacity
        lastTouchAt = System.currentTimeMillis()
        postInvalidate()
        // A TOUCH THAT LANDS ON A CONTROL IS THE CONTROL'S, from the landing to the lift (the user: "When selecting quests in
        // the map I keep accidentally moiving it, because my touches go through it"). Its moves and its lift never reach the
        // pan and zoom detectors. On the quest list a drag scrolls the list and a clean tap selects (on the lift).
        if (touchOwner != OWN_NONE && e.actionMasked != MotionEvent.ACTION_DOWN) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (touchOwner == OWN_LIST) {
                    val dy = e.y - listDownY
                    if (abs(dy) > compactSlop) listMoved = true
                    // a row's worth of drag scrolls one quest; finger up = the list moves up (later quests)
                    val steps = ((listDragY - e.y) / LIST_DRAG_ROW).toInt()
                    if (steps != 0) {
                        listFirst = (listFirst + steps).coerceIn(0, listMaxFirst.coerceAtLeast(0))
                        listDragY -= steps * LIST_DRAG_ROW
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (touchOwner == OWN_LIST && !listMoved)
                        trackerHits.firstOrNull { it.first.contains(e.x, e.y) }?.let { (_, item) -> touchItem(item) }
                    touchOwner = OWN_NONE
                }
                MotionEvent.ACTION_CANCEL -> touchOwner = OWN_NONE
            }
            return true
        }
        if (e.action == MotionEvent.ACTION_DOWN) {
            touchOwner = OWN_NONE
            if (panelId != 0 && !pmenu.open) {
                // the quest panel: its buttons act, the rest of it does nothing, outside it closes
                val hit = panelHits.firstOrNull { it.first.contains(e.x, e.y) }
                if (hit != null) panelAct(hit.second)
                else if (!panelBox.contains(e.x, e.y)) closePanel()
                touchOwner = OWN_CONTROL
                syncB(); postInvalidate(); return true
            }
            if (pmenu.open) {
                // the player menu: a row chooses it, anywhere else closes it (and does nothing else)
                val hit = pmenuHits.firstOrNull { it.first.contains(e.x, e.y) }
                if (hit != null) pmenuDid(pmenu.touch(hit.second))
                else if (!pmenuBox.contains(e.x, e.y)) { pmenu.close(); syncB() }
                touchOwner = OWN_CONTROL
                postInvalidate(); return true
            }
            if (filterOpen) {
                // A pop-out panel, not a row of chips: the user's standing rule for these
                // companions is icons and pop-outs over words, and no per-row buttons.
                for ((bit, box) in filterHits) if (box.contains(e.x, e.y)) {
                    when (bit) {
                        FILTER_CHESTS -> showChests = !showChests
                        FILTER_RARES -> showRares = !showRares
                        FILTER_LIVE -> setProbeOption(PROBE_ON)
                        FILTER_SWITCH -> setProbeOption(PROBE_SWITCH)
                        FILTER_SPARKLES -> setProbeOption(PROBE_SPARKLES)
                        else -> { mask = mask xor bit; recomputeSeed() }
                    }
                    runCatching { filterPrefs.edit().putInt("mask", mask).putBoolean("chests", showChests).putBoolean("rares", showRares).apply() }
                    postInvalidate(); return true
                }
                // beside the rows but inside the panel: nothing. Outside the panel: it closes
                if (!filterBox.contains(e.x, e.y)) { filterOpen = false; syncB() }
                touchOwner = OWN_CONTROL
                postInvalidate(); return true
            }
            // the quest list (its whole panel, gaps included): the list's gesture
            if (!trackerRect.isEmpty && trackerRect.contains(e.x, e.y)) {
                touchOwner = OWN_LIST; listDownY = e.y; listDragY = e.y; listMoved = false
                return true
            }
            when {
                // + / - take the wheel exactly as a pinch does (autoZoom off; double-tap gives it back).
                // They used to leave the auto-framer on, which put its own zoom back within a second, so
                // the buttons seemed to do nothing while the label said "auto" (seen on UI Art's emulator)
                !hostZoomHidden && btnIn.contains(e.x, e.y) -> { touchOwner = OWN_CONTROL; autoZoom = false; zoomEase = Float.NaN; focusRestoreZoom = Float.NaN; zoom = (zoom * 1.6f).coerceAtMost(ZOOM_MAX); autoZoomCur = zoom; postInvalidate(); return true }
                !hostZoomHidden && btnOut.contains(e.x, e.y) -> { touchOwner = OWN_CONTROL; autoZoom = false; zoomEase = Float.NaN; focusRestoreZoom = Float.NaN; zoom = (zoom / 1.6f).coerceAtLeast(1f); autoZoomCur = zoom; postInvalidate(); return true }
                btnFilter.contains(e.x, e.y) -> { touchOwner = OWN_CONTROL; filterOpen = true; syncB(); postInvalidate(); return true }
                spine.indexOfFirst { it.contains(e.x, e.y) } >= 0 -> {
                    // Tap a slab to PIN that storey; tap the pinned one again to hand control
                    // back to the tracker. For a view control the selection is the action, so
                    // a second confirming step would be ceremony.
                    val i = spine.indexOfFirst { it.contains(e.x, e.y) }
                    val n = map?.let { placeHere(it) }?.levels?.size ?: 0
                    val lvl = n - 1 - i                     // the spine is drawn top-down
                    pinnedLevel = if (pinnedLevel == lvl) -1 else lvl
                    lastTouchAt = System.currentTimeMillis()
                    touchOwner = OWN_CONTROL
                    postInvalidate(); return true
                }
            }
        }
        scaleDetector.onTouchEvent(e)
        tapDetector.onTouchEvent(e)
        return true
    }

    /**
     * Re-derive the seed pins for the current zone, and with them everything the service layer
     * draws (deriveServiceLayer). Cheap enough to call on any map update: it is one bounding-box
     * test and two multiplies per bundled row, over at most 1,560 rows, plus two passes over svc.
     */
    private fun recomputeSeed() {
        loadSeen()
        harvestedEntries(svcE)
        zoneEntries.clear()
        zoneRows()?.let { rows -> for (p in rows) if (p.entry > 0) zoneEntries.add(p.entry) }
        seed = seedPins()
        deriveServiceLayer()
    }

    private fun seedPins(): List<PoiStore.Pin> {
        val m = map
        val r = rect
        val all = zoneRows()
        if (m == null || all == null || r == null) return emptyList()
        // The addon's own round-trip test failed, so this zone's rect does not agree with the
        // client's map position: every bundled pin would be displaced by the same wrong transform.
        // Draw nothing. A pin that is confidently in the wrong place is worse than an empty map,
        // and the note below says which check failed.
        m.optJSONObject("chk")?.let { if (it.optInt("ok", 1) == 0) return emptyList() }
        // Prefer the world position the addon reported. Failing that, invert the player's
        // normalised position through the rect -- exact, and it means a cheap position-only
        // update keeps the yard distances live without sending anything extra.
        var wx = if (m.has("wx")) m.optDouble("wx") else Double.NaN
        var wy = if (m.has("wy")) m.optDouble("wy") else Double.NaN
        if (wx.isNaN() || wy.isNaN()) {
            val nx = m.optDouble("x", Double.NaN); val ny = m.optDouble("y", Double.NaN)
            if (!nx.isNaN() && !ny.isNaN()) {
                wx = r.top - ny * r.height
                wy = r.left - nx * r.width
            }
        }
        return PoiStore.pinsFor(all, r, mask,
            if (wx.isNaN()) null else wx, if (wy.isNaN()) null else wy, svcE,
            zoneMatch?.nested ?: emptySet())
    }

    /** creature entry ids the player has met AT the bundle's spot (svc rows' `e`), into [out] */
    private fun harvestedEntries(out: HashSet<Int>) {
        out.clear()
        val a = map?.optJSONArray("svc") ?: return
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (it.has("e")) out.add(it.optInt("e")) }
    }

    // ---- THE SERVICE LAYER, WORKED OUT WHEN ITS DATA CHANGES. Everything onDraw needs from
    // map.svc and the seed is derived here, into fields reused from one call to the next.
    // recomputeSeed is the one door: update(), updatePos(), the filter and the continent load
    // all come through it. (adoptRect also empties the seed on a continent change, but its only
    // caller is update(), which calls recomputeSeed right after it in the same UI-thread call, so
    // no frame ever sees the derived fields out of step.) onDraw runs at up
    // to 60 Hz while the trail animates, and there it now only projects and draws: no JSON
    // reads, no set lookups and no boxed Integer per pin.
    // ---- PIN KINDS (NpcKind). Every service pin has a KEY naming UI Art's cut-out picture
    // ui/kit/mm_<key>.png (the user, 2026-09-29: every icon a cut-out, the game's own minimap icon
    // for the kind, class and profession trainers by class and profession, vendors by what they
    // sell). Keys are registered once and handled as small ints, so the declutter grid and the draw
    // loop never touch a string per frame. Until UiKit has the picture on the device, the old strip
    // cell for that key stands in (pinFallback).
    private val KIND_MAX = 96
    private val kindKeys = ArrayList<String>()
    private val kindNames = ArrayList<String>()        // "mm_" + key, built once per key
    private val kindIdx = HashMap<String, Int>()
    private val kindMemo = java.util.IdentityHashMap<PoiStore.Poi, Int>()  // regex once per NPC
    private fun kindOf(key: String): Int {
        kindIdx[key]?.let { return it }
        if (kindKeys.size >= KIND_MAX) return kindOf("generic").coerceAtMost(KIND_MAX - 1)
        kindKeys.add(key); kindNames.add("mm_$key")
        val i = kindKeys.size - 1
        kindIdx[key] = i
        return i
    }
    private fun kindOfPoi(p: PoiStore.Poi): Int {
        kindMemo[p]?.let { return it }
        val k = kindOf(NpcIcons.icon(NpcKind.key(p.flags, p.title), p.flags))
        kindMemo[p] = k
        return k
    }

    /** the old strip's cell for a key (NpcIcons'): what a pin looks like until the NPC icons are harvested */
    private fun pinFallback(key: String): Int = when {
        key == "mailbox" -> Ic.MAILBOX
        key == "banker" -> Ic.BANKER
        key == "auctioneer" -> Ic.AUCTIONEER
        key == "flightmaster" -> Ic.FLIGHT
        key == "innkeeper" -> Ic.INNKEEPER
        key == "trainer" || key.startsWith("class_") -> Ic.TRAINER_CLASS
        key == "profession" || key.startsWith("prof_") -> Ic.TRAINER_PROF
        key == "stablemaster" -> Ic.STABLE
        key == "repair" -> Ic.REPAIR
        key == "anvil" -> Ic.ANVIL
        key == "guild" -> Ic.TABARD
        key == "reagents" -> Ic.REAGENT
        key == "food" -> Ic.FOOD
        key == "ammo" -> Ic.AMMO
        key == "poisons" -> Ic.POISON
        key == "forge" -> Ic.FORGE
        key == "vendor" -> Ic.VENDOR
        else -> Ic.GENERIC
    }

    /** Draw pin kind [k] centred on ([cx], [cy]) at [box] px: the harvested NPC icon (NpcSheet, the user's 2026-10-12 icons), else
     *  the old strip cell */
    private fun drawPin(c: Canvas, k: Int, cx: Float, cy: Float, box: Float = SVC_ICON_PX) {
        val key = kindKeys[k]
        if (!NpcSheet.draw(c, key, cx, cy, box, pArt, this)) drawIcon(c, pinFallback(key), cx, cy, box)
    }

    private val seedTitle = HashMap<Int, String>()     // bundled subtitle per entry (an `mv` row's)
    private var seedKind = IntArray(16)                // the pin kind of seedDraw[i]
    private val svcE = HashSet<Int>()              // entries met AT the bundle's spot (`e`)
    private val seedSpawns = HashMap<Int, Int>()   // bundled spawns per entry on this map
    private val movedE = HashSet<Int>()            // seed pins hidden by `mv` (rules below)
    private val seedDraw = ArrayList<PoiStore.Pin>()  // seed pins to draw, in claim order
    private var svcN = 0                           // svc rows to draw, as x, y, icon
    private var svcX = DoubleArray(16)
    private var svcY = DoubleArray(16)
    private var svcIc = IntArray(16)

    /**
     * Fill the fields above from [seed], map.svc and the filter [mask].
     *
     * SVC ROWS TO DRAW: the harvested services the bundle does not already draw -- a Forever-only
     * NPC, or one met away from its bundled spot. A row passes the filter, is not a seed pin
     * itself (`e` naming one of this map's seed pins, which draws it) and has a usable x, y.
     *
     * SEED PINS TO DRAW, in claim order: the pins the player has confirmed, then the guesses,
     * each nearest-first as pinsFor sorted them. onDraw says why that order matters.
     *
     * MOVED NPCs (`mv`). A harvested NPC met ELSEWHERE than the bundle says (a wanderer: Kira
     * Songshine is 468 yd from her bundled spot) arrives as an svc row where she was met, carrying
     * `mv` = her creature entry. With every pin looking the same, the bundle's stale copy would
     * read as a second NPC, so that seed pin is skipped -- but only where skipping it can never
     * hide the NPC, or a different spawn of her, completely:
     *   (a) the mv row is one this view actually draws: it passes the filter and has a valid
     *       x, y (the same tests as SVC ROWS TO DRAW). A filtered-out or position-less row puts
     *       nothing on the map, and hiding the seed pin too would leave the NPC nowhere.
     *   (b) a CONFIRMED seed pin is never skipped: its entry is also some row's `e`, so the
     *       player has met this NPC at the bundle's spot as well, and that spot is real.
     *   (c) only an entry with exactly ONE bundled spawn on this map is skipped. With several (a
     *       patrol, the same creature placed in two towns) `mv` cannot say which spawn the
     *       player met away from home, so every spawn keeps its pin.
     * (`e` keeps its meaning: that seed pin IS the harvested one, and it is drawn once, as a pin.)
     */
    private val metNew = ArrayList<ReplacedTrainers.Met>()   // met class trainers the old data lacks (see ReplacedTrainers)
    private val metIds = HashSet<Int>()                      // the creature ids of the opened (harvested) NPCs on this map

    private fun deriveServiceLayer() {
        NpcKind.init(context)
        seedSpawns.clear()
        seedTitle.clear()
        for (p in seed) {
            seedSpawns[p.poi.entry] = (seedSpawns[p.poi.entry] ?: 0) + 1
            if (p.poi.title.isNotEmpty()) seedTitle[p.poi.entry] = p.poi.title
        }
        svcN = 0
        movedE.clear()
        metNew.clear()
        metIds.clear()
        map?.optJSONArray("svc")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.optJSONObject(i) ?: continue
                val f = s.optInt("f")
                // a met CLASS trainer the old data lacks (neither "e" nor "mv"): it replaces the old ones nearby (ReplacedTrainers)
                if (f and PoiStore.TRAINER_CLASS != 0 && !s.has("e") && !s.has("mv")) {
                    val mx = s.optDouble("x", Double.NaN); val my = s.optDouble("y", Double.NaN)
                    if (!mx.isNaN() && !my.isNaN()) metNew.add(ReplacedTrainers.Met(mx, my, if (s.has("n")) s.optString("n") else null))
                }
                if (s.has("i")) metIds.add(s.optInt("i"))       // an opened NPC: its own row draws it, its QuestieDB row does not
                if (f and mask == 0) continue
                if (s.has("e") && seedSpawns.containsKey(s.optInt("e"))) continue
                val nx = s.optDouble("x", Double.NaN); val ny = s.optDouble("y", Double.NaN)
                if (nx.isNaN() || ny.isNaN()) continue
                if (svcN == svcX.size) {
                    svcX = svcX.copyOf(svcN * 2); svcY = svcY.copyOf(svcN * 2)
                    svcIc = svcIc.copyOf(svcN * 2)
                }
                // a Forever-only NPC has no subtitle; one met away from home (`mv`) has its
                // bundled one
                // what it is: QuestieDB's row for this creature when there is one (its subtitle, its full roles), else its own
                val id = if (s.has("i")) s.optInt("i") else (if (s.has("mv")) s.optInt("mv") else 0)
                val db = if (id != 0) seed.firstOrNull { it.poi.entry == id }?.poi else null
                val sub = db?.title ?: (if (s.has("mv")) seedTitle[s.optInt("mv")] else null)
                val ff = f or (db?.flags ?: 0)
                svcX[svcN] = nx; svcY[svcN] = ny; svcIc[svcN] = kindOf(NpcIcons.icon(NpcKind.key(ff, sub), ff)); svcN++
                // (a): only a row that got this far is drawn, so only it may hide a seed pin
                if (s.has("mv")) {
                    val e = s.optInt("mv")
                    if (e !in svcE && seedSpawns[e] == 1) movedE.add(e)      // (b), (c)
                }
            }
        }
        seedDraw.clear()
        // ONLY NPCs ACTUALLY SEEN (the user, 2026-10-12: the pins were ghosts, "the icon is on the minimap but nothing is in the
        // game"): a QuestieDB row is drawn only for an NPC DS Forever has seen in the game (SeenNpcs: the soft target, a minimap dot;
        // an opened one is its harvested row above), at QuestieDB's spot when the sighting is near it, else where it was seen.
        // Never a database-only NPC, in any zone, capitals included (the old capital lean folds into this).
        val r = rect
        val ax = if (r != null && r.valid) r.width else 1.0; val ay = if (r != null && r.valid) r.height else 1.0
        val mid = map?.optInt("mapID", Int.MIN_VALUE) ?: Int.MIN_VALUE
        for (p in seed) {
            if (p.poi.entry in metIds) continue
            val s = SeenNpcs.get(mid, p.poi.entry) ?: continue
            val at = SeenNpcs.spot(p.nx, p.ny, s, ax, ay)
            seedDraw.add(if (at[0] == p.nx && at[1] == p.ny) p else PoiStore.Pin(p.poi, at[0], at[1], p.dist, true))
        }
        if (seedKind.size < seedDraw.size) seedKind = IntArray(seedDraw.size * 2)
        for (i in 0 until seedDraw.size) seedKind[i] = kindOfPoi(seedDraw[i].poi)
    }

    // ---- THE DECLUTTER GRID: one flag per (icon kind, SVC_CELL_PX screen cell) over the field
    // and the margin onField allows around it. A flat array cleared once a frame instead of a
    // HashSet<Long> built every frame, so a claim neither allocates nor boxes.
    private var claimGrid = BooleanArray(0)
    private var claimCols = 1
    private var claimRows = 1
    private var claimX0 = 0f
    private var claimY0 = 0f

    /** Empty the grid for a frame whose claimable area starts at ([x0], [y0]) and is [w] x [h] px. */
    private fun resetClaims(x0: Float, y0: Float, w: Float, h: Float) {
        claimX0 = x0; claimY0 = y0
        claimCols = (w / SVC_CELL_PX).toInt() + 1
        claimRows = (h / SVC_CELL_PX).toInt() + 1
        val n = KIND_MAX * claimCols * claimRows
        if (claimGrid.size < n) claimGrid = BooleanArray(n)
        else java.util.Arrays.fill(claimGrid, 0, n, false)
    }

    /** Take the cell under ([x], [y]) for icon kind [idx]; false when that kind already holds it. */
    private fun claim(idx: Int, x: Float, y: Float): Boolean {
        if (idx < 0 || idx >= KIND_MAX) return true
        val col = ((x - claimX0) / SVC_CELL_PX).toInt().coerceIn(0, claimCols - 1)
        val row = ((y - claimY0) / SVC_CELL_PX).toInt().coerceIn(0, claimRows - 1)
        val k = (idx * claimRows + row) * claimCols + col
        if (claimGrid[k]) return false
        claimGrid[k] = true
        return true
    }

    /**
     * Pick up the zone rect and load the right continent file.
     *
     * The rect comes from the addon, which read it out of the LIVE client, so it is right for
     * whatever map art this build ships. The name-matched fallback exists only so the layer is
     * not blank on the very first frame after a zone change, and it is drawn with a warning
     * because it is a 3.3.5a rect being trusted for a modern client's map.
     */
    private fun adoptRect(m: JSONObject) {
        val fromClient = PoiStore.Rect.fromJson(m.optJSONObject("rect"))
        val r = fromClient ?: PoiStore.fallbackRect(context, m.optString("zone", null))
        val changed = r?.continent != rect?.continent ||
                r?.top != rect?.top || r?.left != rect?.left
        rect = r
        zoneMatch = r?.let { PoiStore.identify(context, it) }
        val chk = m.optJSONObject("chk")
        seedNote = when {
            chk != null && chk.optInt("ok", 1) == 0 ->
                "coord check FAILED (${chk.optString("why", "")} ${chk.optInt("e", -1)}yd)"
            r == null -> "no zone rect - service layer off"
            !r.fromClient -> "baked rect - addon sent none"
            zoneMatch == null -> "rect off the baked continents"
            // The one link that could not be verified off the device. If it ever reads "re-cut",
            // the client's map art no longer matches the 3.3.5a rects and every bundled pin is
            // displaced -- say so instead of drawing a confidently wrong map.
            !zoneMatch!!.exact -> "map art re-cut - pins may be off"
            else -> ""
        }
        if (r != null && r.continent != continentId) {
            continentId = r.continent
            continentList = null
            seed = emptyList()
            val want = r.continent
            Thread {
                val l = PoiStore.forContinent(context, want)
                post {
                    if (continentId == want) {
                        continentList = l
                        if (l == null) seedNote = PoiStore.note
                        recomputeSeed(); deriveMarkers(map); invalidate()
                    }
                }
            }.start()
        } else if (changed) recomputeSeed()
    }

    // Buttons are sized for a THUMB on a 369 dpi panel, not for a mouse. At the old 56 px they
    // were 3.9 mm across and the user could not reliably hit them; BTN_SIZE is ~7 mm, which is
    // the usual minimum for a touch target, and they are drawn over a dark scrim so they stay
    // findable against bright parchment.
    private val BTN_SIZE = 104f
    private val BTN_GAP = 14f

    private fun fade(color: Int, a: Float) =
        Color.argb((Color.alpha(color) * a).toInt().coerceIn(0, 255),
                   Color.red(color), Color.green(color), Color.blue(color))

    /**
     * The floor spine: one slab per storey, highest at the top, with a dot showing where the
     * tracker thinks you are. A connector is drawn between two slabs only where a staircase
     * actually joins them, so "there is no way up from here" is visible rather than implied.
     *
     * The dot goes HOLLOW when the app is running on dead reckoning rather than on evidence,
     * and sits between two slabs while you are on the stairs. That is the whole uncertainty
     * UI: no text, no dialog, it just stops looking certain.
     */
    private val SPINE_H = 34f
    private val SPINE_GAP = 12f

    private fun drawSpine(c: Canvas, p: Place, yTop: Float, x0: Float) {
        spine.clear()
        val a = 1f                              // the Map room's controls no longer fade (user 17:30)
        val n = p.levels.size
        val w = BTN_SIZE
        val h = SPINE_H
        val gap = SPINE_GAP
        val bl = floorBlend(p)
        val lo = bl[0].toInt(); val hi = bl[1].toInt(); val u = bl[2]
        val unsure = floorUnsure()

        for (i in 0 until n) {
            val lvl = n - 1 - i                       // drawn top-down, highest storey first
            val r = RectF(x0, yTop + i * (h + gap), x0 + w, yTop + i * (h + gap) + h)
            spine.add(r)
            val on = lvl == lo || (u > 0f && lvl == hi)
            pFill.color = fade(if (on) Color.argb(235, 46, 36, 17)
                               else Color.argb(200, 22, 18, 12), a)
            c.drawRoundRect(r, 8f, 8f, pFill)
            val st = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = if (on) 3f else 2f
                color = fade(if (lvl == pinnedLevel) cGold
                             else if (on) cGold else Color.parseColor("#6A5428"), a)
            }
            c.drawRoundRect(r, 8f, 8f, st)
            // a connector wherever a staircase joins this storey to the one below it
            if (lvl > 0 && joined(p, lvl - 1, lvl)) {
                val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    strokeWidth = 3f; color = fade(cBronze, a * 0.8f)
                }
                c.drawLine(r.centerX(), r.bottom, r.centerX(), r.bottom + gap, lp)
            }
        }
        if (lo < 0) return
        // the dot rides between the two slabs while you climb, so it physically walks the spine
        val iLo = n - 1 - lo
        val iHi = n - 1 - hi
        val yLo = yTop + iLo * (h + gap) + h / 2f
        val yHi = yTop + iHi * (h + gap) + h / 2f
        val cy = yLo + (yHi - yLo) * u
        val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fade(cPlayer, a)
            style = if (unsure) Paint.Style.STROKE else Paint.Style.FILL
            strokeWidth = 3f
        }
        c.drawCircle(x0 + 14f, cy, 7f, dp)
    }

    /** Is there a baked staircase linking these two storeys? (Place.stairs: parsed with the placements, off the UI thread) */
    private fun joined(p: Place, a: Int, b: Int): Boolean =
        InteriorStairs.joined(p.stairs, p.levels[a].zlo, p.levels[a].zhi, p.levels[b].zlo, p.levels[b].zhi)

    private fun drawButton(c: Canvas, r: RectF, glyph: String, active: Boolean, a: Float = buttonAlpha()) {
        // the game's own tracker buttons; a button that can do nothing now is drawn fainter
        val what = when (glyph) { "+" -> "plus"; "-" -> "minus"; else -> "filter" }
        if (UiKit.trackerButton(c, what, r.centerX(), r.centerY(), r.width(),
                alpha = (255 * a * (if (active) 1f else 0.4f)).toInt())) return
        pFill.color = fade(if (active) Color.argb(235, 46, 36, 17) else Color.argb(220, 22, 18, 12), a)
        c.drawRoundRect(r, 12f, 12f, pFill)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f
            color = fade(if (active) cGold else Color.parseColor("#6A5428"), a)
        }
        c.drawRoundRect(r, 12f, 12f, p)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 52f; textAlign = Paint.Align.CENTER
            color = fade(if (active) cGold else cDim, a)
        }
        c.drawText(glyph, r.centerX(), r.centerY() + 19f, t)
    }

    private val cGold = Color.parseColor("#FFD100")
    private val cBronze = Color.parseColor("#C8AA6E")
    private val cDim = Color.parseColor("#8F8168")
    private val cInk = Color.parseColor("#0E0B08")
    private val cField = Color.parseColor("#141009")
    private val cPanel = Color.parseColor("#17120C")
    private val cPlayer = Color.parseColor("#AAD372")
    private val cFlight = Color.parseColor("#7FC4E8")
    private val cText = Color.parseColor("#E8DCC0")

    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = cBronze
    }
    private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.argb(34, 200, 170, 110)
    }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 25f; color = cText }
    private val pDist = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 21f; color = cDim }
    private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 34f; color = cGold; isFakeBoldText = true
    }
    private val pSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 21f; color = cDim }
    private val pHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 25f; color = cInk; style = Paint.Style.STROKE; strokeWidth = 5f
    }

    // ---- small paints from the service layer's labelled days. The pins no longer use them (they
    // are bare icons now); pSeed strokes the objective blob's rim and the filter's swatches, and
    // pLabelS sets the filter's text.
    private val cSeedText = Color.parseColor("#9C8F78")
    private val pSeed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val pLabelS = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f }
    private val pHaloS = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f; style = Paint.Style.STROKE; strokeWidth = 4.5f
    }

    // ---- OFFICIAL MAP ICONS ------------------------------------------------------------
    // A strip of 22 64px cells built by tools/icons/poi_icons.py out of WoW's own art:
    // Interface\Minimap\ObjectIcons.blp (the 8x8 sheet the game itself puts on the minimap and
    // world map) plus a few named Interface\Icons entries for roles that sheet has no
    // unambiguous cell for. A vendor is therefore the same stack of coins the player already
    // reads without thinking, instead of a coloured dot that needed a name beside it.
    // The strip is harvested from the game's files on the device (MapHarvestSteps.POI_ICONS, before Play), not in the APK: read once
    // it is there (a map drawn before it exists tries again on the next draw)
    // (decoded off the UI thread, asked for at most every 3 s; the map redraws when it lands)
    private var iconSheetMemo: Bitmap? = null
    private var iconSheetAsked = 0L
    private val iconSheet: Bitmap?
        get() {
            iconSheetMemo?.let { return it }
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - iconSheetAsked < 3_000L) return null
            iconSheetAsked = now
            val f = ArtHarvest.file("ui/poi_icons.png")
            Thread {
                val bm = if (f.isFile) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
                if (bm != null) post { iconSheetMemo = bm; invalidate() }
            }.apply { isDaemon = true }.start()
            return null
        }
    private val iconSrc = Rect()
    private val iconDst = RectF()

    private object Ic {
        const val VENDOR = 0; const val REPAIR = 1; const val TRAINER_CLASS = 2
        const val TRAINER_PROF = 3; const val TRAINER = 4; const val BANKER = 5
        const val AUCTIONEER = 6; const val MAILBOX = 7; const val INNKEEPER = 8
        const val FLIGHT = 9; const val STABLE = 10; const val REAGENT = 11
        const val FOOD = 12; const val AMMO = 13; const val POISON = 14; const val TABARD = 15
        const val ANVIL = 16; const val FORGE = 17; const val QUEST = 18
        const val QUEST_DONE = 19; const val VIGNETTE = 20; const val GENERIC = 21
        // the object beside the player: the game's own cursor for it (poi_icons.py 'cur' rows)
        const val SOFT_ORE = 22; const val SOFT_HERB = 23; const val SOFT_LOOT = 24
        const val SOFT_LOCK = 25; const val SOFT_QUEST = 26; const val SOFT_USE = 27
        const val SOFT_FISH = 28; const val SOFT_SKIN = 29
        const val QUEST_ARROW = 30                  // the game's minimap rim arrow, pointing UP
        const val COUNT = 31                        // cells in the strip
    }

    /** Draw cell [idx] centred on (cx, cy) at [size] px. Falls back to nothing if no sheet. */
    private fun drawIcon(c: Canvas, idx: Int, cx: Float, cy: Float, size: Float, paint: Paint = pArt) {
        val sheet = iconSheet ?: return
        val cell = sheet.height                     // the strip is one row of square cells
        iconSrc.set(idx * cell, 0, (idx + 1) * cell, cell)
        val h = size / 2f
        iconDst.set(cx - h, cy - h, cx + h, cy + h)
        c.drawBitmap(sheet, iconSrc, iconDst, paint)
    }

    // ---- MARKER SIZES (px on the bottom screen, 369 dpi) and kinds.
    // Service pins are ~2x the old 19 px (user ruling 2026-09-27), and the declutter cell grew
    // with them, keeping the old ratio of a hair under one icon width (18 px for 19 px). A flight
    // point's live boot is drawn at SVC_ICON_PX as well: it is the same icon as a flightmaster's
    // service pin, and one boot at two sizes reads as two different things.
    private val SVC_ICON_PX = 38f
    private val PLAYER_ART_PX = 44f     // UI Art's mm_player (SPEC section 4)
    private val SVC_CELL_PX = 36f
    // "!" and "?" fill only the middle of their cell (the "!" is 25x58 of 64), so at 40 px the
    // glyph stands ~36 px tall: level with a service icon, never lost among them.
    private val QUEST_ICON_PX = 40f
    // the objective's centre: a 19 px gold dot inside a 2.5 px dark rim, 24 px across in all.
    // The first cut (10 px) was lost on busy terrain; this is still about half a "!", so it
    // stays the lesser mark.
    private val OBJ_DOT_R = 9.5f
    private val OBJ_RIM_PX = 2.5f
    private val SH_OBJECTIVE = 0
    private val SH_FLIGHT = 1
    private val SH_VIGNETTE = 2
    private val SH_TURNIN = 3
    private val SH_AVAIL = 4
    private val SH_AVAIL_DAILY = 5
    private val TRIVIAL_DIM = 0.62f     // the trivial "!" brightness: (255,209,0) -> (158,130,0)
    private val SH_AVAIL_LOW = 6         // a low-level (trivial) quest's "!": a DIMMED yellow, the game's own trivial look
    // the user, 2026-10-07: "Normally grey is stuff these too high level. I think it should instead be a dimmed yellow like it
    // normally is": the yellow "!" at reduced brightness (an olive gold, as the game's trivialquests mark), the dark outline kept
    private val pTrivial = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            TRIVIAL_DIM, 0f, 0f, 0f, 0f,
            0f, TRIVIAL_DIM, 0f, 0f, 0f,
            0f, 0f, TRIVIAL_DIM, 0f, 0f,
            0f, 0f, 0f, 1f, 0f)))
    }
    // A daily's "!" is blue in the game. The strip only has the yellow one, so swap red and blue
    // (yellow 255,209,0 -> 0,~160,255) and leave the dark outline dark.
    private val pDaily = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0f, 0f, 1f, 0f, 0f,
            0f, 0.77f, 0f, 0f, 0f,
            1f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f)))
    }

    /**
     * Where a map.quests row belongs on THIS map, into [out] as (x, y).
     * A quest READY TO TURN IN (done = true) goes to its turn-in point (tx, ty) when the addon
     * found one on this map; otherwise, and for every quest still in progress, to its own x, y.
     * @return 2 = the turn-in point, 1 = the row's x, y, 0 = no usable position
     */
    private fun questXY(q: JSONObject, out: DoubleArray): Int {
        if (q.optBoolean("done", false)) {
            val tx = q.optDouble("tx", Double.NaN); val ty = q.optDouble("ty", Double.NaN)
            if (!tx.isNaN() && !ty.isNaN()) { out[0] = tx; out[1] = ty; return 2 }
        }
        val x = q.optDouble("x", Double.NaN); val y = q.optDouble("y", Double.NaN)
        if (x.isNaN() || y.isNaN()) return 0
        out[0] = x; out[1] = y
        return 1
    }
    private val qxy = DoubleArray(2)       // scratch for questXY; UI thread only

    /**
     * Colour by the most specific role, from a deliberately small palette. Only the filter's
     * swatches use it now: the pins are the game's own icons, which say the kind by themselves,
     * and provenance is no longer drawn on the map at all (user ruling 2026-09-27).
     */
    private fun roleColour(f: Int): Int = when {
        f and PoiStore.MAILBOX != 0 -> Color.parseColor("#D8C07A")
        f and PoiStore.BANKER != 0 -> Color.parseColor("#7FE0B0")
        f and PoiStore.AUCTIONEER != 0 -> Color.parseColor("#E0A87F")
        f and PoiStore.FLIGHTMASTER != 0 -> cFlight
        f and PoiStore.INNKEEPER != 0 -> Color.parseColor("#E08FC0")
        f and (PoiStore.TRAINER_CLASS or PoiStore.TRAINER_PROF or PoiStore.TRAINER) != 0 ->
            Color.parseColor("#8FB8E8")
        f and PoiStore.STABLEMASTER != 0 -> Color.parseColor("#B9E08F")
        else -> Color.parseColor("#C0A878")     // vendors and the rest
    }


    /** Feed the storey tracker. Called from the data path, NOT from onDraw: it integrates
     *  over samples, so running it once per frame would make it depend on the frame rate. */
    private fun trackFromMap(m: JSONObject?) {
        val here = m?.let { placeHere(it) }
        if (here == null) {
            if (trackFor != "") {          // left the building: forget everything about it
                trackFor = ""; zEst = Float.NaN; pinnedLevel = -1; zAt = 0L
                provisionalUsed = false
            }
            return
        }
        track(here, m.optDouble("wx"), m.optDouble("wy"))
    }

    fun update(m: JSONObject?, ageMs: Long) {
        map = m; staleMs = ageMs
        addTrail(m?.optString("tr", null), -1L,  // the view is handed the map object itself
                 m?.optInt("mapID", Int.MIN_VALUE) ?: Int.MIN_VALUE)
        if (m != null) { adoptRect(m); recomputeSeed(); learnExit(m) }
        deriveMarkers(m)
        deriveFocusTarget(m)
        deriveCorpse(m)
        deriveQuestAreas(m)
        refreshDefault(m)
        fireFocus()
        noteIndoor(m)
        val where = m?.let { it.optString("zone", "") + "\u0000" + it.optString("subzone", "") }
        if (where != null && where != listWhere) { listWhere = where; rebuildItems() }
        trackFromMap(m)
        postInvalidate()
    }

    /** merge a cheap position-only update into the last full map */
    fun updatePos(pos: JSONObject, gameMs: Long = -1L) {
        val m = map ?: return
        // "indoors"/"inst" MUST be here: the position tick is the only thing that carries them,
        // and a key missing from this list silently never reaches the map object -- the indoor
        // framing simply never fired even though the addon was sending indoors=true.
        for (k in listOf("x", "y", "facing", "zone", "subzone", "mapID", "wx", "wy",
                         "indoors", "inst")) {
            if (pos.has(k)) m.put(k, pos.get(k))
        }
        staleMs = 0
        absorbSoft(pos.optJSONObject("soft"), m)    // absent = nothing beside the player now
        // the quest-area tracer's samples (Map.lua traceSample), taken at this update's wx / wy
        pos.optJSONArray("qb")?.let { qb ->
            val r = rect
            val wx = m.optDouble("wx", Double.NaN); val wy = m.optDouble("wy", Double.NaN)
            if (r != null && !wx.isNaN() && !wy.isNaN()) {
                QuestTrace.init(context)
                if (QuestTrace.absorb(qb, wx, wy, r.continent)) deriveQuestAreas(m)
                QuestTrace.saveIfDue()
            }
        }
        addTrail(pos.optString("tr", null), gameMs, m.optInt("mapID", Int.MIN_VALUE))
        noteIndoor(m)
        trackFromMap(m)
        maybeRecentre(m.optDouble("x", Double.NaN), m.optDouble("y", Double.NaN))
        // only the distances change on a position update, so re-sort rather than re-filter
        recomputeSeed()
        refreshDefault(m)                           // the nearest tracked quest may have changed
        fireFocus()
        postInvalidate()
    }

    /**
     * Start the glide home if the player has actually walked since the drag.
     *
     * The threshold matters: position is sampled ten times a second and the last decimal
     * wobbles even standing still, so any non-zero delta would snatch the view back the
     * instant you let go. MOVE_EPS is ~0.2% of the zone -- about 7 yards in Elwynn -- which no
     * amount of standing still produces. The grace period stops the glide firing between two
     * scroll events of one continuous drag.
     */
    private fun maybeRecentre(px: Double, py: Double) {
        if (followPlayer || recentreFrom != null || px.isNaN() || py.isNaN()) return
        if (System.currentTimeMillis() - lastDragAt < DRAG_GRACE_MS) return
        val dx = px.toFloat() - panAnchorX
        val dy = py.toFloat() - panAnchorY
        if (dx * dx + dy * dy < MOVE_EPS * MOVE_EPS) return
        recentreFrom = floatArrayOf(panX, panY)
        recentreStart = System.currentTimeMillis()
        postInvalidate()
    }

    // ---- THE OBJECT BESIDE THE PLAYER (the addon's collectSoft; the user's pick 2026-09-29 13:58:
    // "mark what you're standing next to"). The game names the object in front of the player and,
    // through the cursor it would show for it, what it is -- the mining pick, the herb glove. It
    // gives NO position for an object, so the mark goes where the player stood when it came into
    // reach, a step ahead along the facing, and stays there. Live only: once the game stops naming
    // it (gathered, walked off, turned away) it fades. Remembering nodes was the other option,
    // which was not chosen. Only game objects: a corpse to loot or skin is not what this is for.
    private class Soft(val key: String, val icon: Int, val x: Double, val y: Double, val mapId: Int) {
        var goneAt = 0L
    }
    private var soft: Soft? = null
    private val SOFT_FADE_MS = 1200L
    private val SOFT_AHEAD_YD = 3.0
    private val SOFT_ICON_PX = 36f
    private val SOFT_MIN_PX = 30f          // never under the player's own arrow
    private val pSoft = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private fun softIcon(k: String?): Int = when (k) {
        "ore" -> Ic.SOFT_ORE; "herb" -> Ic.SOFT_HERB; "loot" -> Ic.SOFT_LOOT
        "lock" -> Ic.SOFT_LOCK; "quest" -> Ic.SOFT_QUEST; "fish" -> Ic.SOFT_FISH
        else -> -1      // "use" (doors, mailboxes), "skin", unknown: not what the mark is for
    }

    private fun absorbSoft(s: JSONObject?, m: JSONObject) {
        val now = android.os.SystemClock.elapsedRealtime()
        val mapId = m.optInt("mapID", Int.MIN_VALUE)
        if (s != null && !s.optBoolean("go") && s.optString("t") == "Creature") seeSoft(s, m, mapId)
        val icon = if (s != null && s.optBoolean("go") && s.optBoolean("r"))
            softIcon(s.optString("k", null)) else -1
        if (s == null || icon < 0) {
            soft?.let { if (it.goneAt == 0L) it.goneAt = now }
            postInvalidate()
            return
        }
        val key = s.optString("s", "") + ":" + s.optInt("e", 0) + ":" + s.optString("n", "")
        val cur = soft
        if (cur != null && cur.key == key && cur.mapId == mapId) { cur.goneAt = 0L; return }
        val r = rect
        val wx = m.optDouble("wx", Double.NaN)
        val wy = m.optDouble("wy", Double.NaN)
        val f = m.optDouble("facing", Double.NaN)
        val x: Double
        val y: Double
        if (r != null && r.valid && s.has("wx") && s.has("wy")) {
            // the game gave the object's own position after all (to be measured on the device)
            x = r.nx(s.optDouble("wy")); y = r.ny(s.optDouble("wx"))
        } else if (r != null && r.valid && !wx.isNaN() && !wy.isNaN()) {
            // world X is north, world Y is west; facing 0 = north, counter-clockwise
            val a = if (f.isNaN()) 0.0 else SOFT_AHEAD_YD
            val ff = if (f.isNaN()) 0.0 else f
            x = r.nx(wy + a * sin(ff)); y = r.ny(wx + a * cos(ff))
        } else {
            x = m.optDouble("x", Double.NaN); y = m.optDouble("y", Double.NaN)
            if (x.isNaN() || y.isNaN()) return
        }
        soft = Soft(key, icon, x, y, mapId)
        postInvalidate()
    }

    // ---- THE CORPSE (the user, 2026-10-01: "when dying the Gravestone and marker back to it needs to show up on the minimap").
    // While the player is a ghost the map document carries `corpse` {x, y} on this map (Map.lua: the game's own
    // C_DeathInfo.GetCorpseMapPosition, else where the player died; x / y outside 0..1 = on another map). Both maps draw the
    // game's gravestone there (UI Art's mm_corpse: the world map's corpse pin); the rim arrow points back to it in the game's
    // own corpse arrow (mm_corpse_arrow) INSTEAD of the quest arrow while a ghost; the full map frames the player and the
    // corpse. With no corpse known (Map.lua then lets the game's own minimap show while dead) the quest arrow stays.
    private var ghost = false
    private var corpseX = Double.NaN
    private var corpseY = Double.NaN
    private val CORPSE_PX = 44f          // the game's 17 px pin, filtered (its world map draws it at 24)
    private val CORPSE_ARROW_PX = 150f   // the corpse arrow's 32 px picture: its chevron then reads ~80 px, as the quest arrow
    // RED (the user, 2026-10-01: "The Corpse arrow should be red"): the game's gold chevron recoloured by its own brightness
    // (R 1.6, G 0.18, B 0.14 x luminance) -- light parts bright red, the dark rim near black -- so the picture's shading stays
    private val pCorpseArrow = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0.478f, 0.939f, 0.182f, 0f, 0f,
            0.054f, 0.106f, 0.021f, 0f, 0f,
            0.042f, 0.082f, 0.016f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f)))
    }
    private val corpseDst = RectF()
    private fun deriveCorpse(m: JSONObject?) {
        ghost = m?.optBoolean("ghost", false) == true
        val c = if (ghost) m?.optJSONObject("corpse") else null
        corpseX = c?.optDouble("x", Double.NaN) ?: Double.NaN
        corpseY = c?.optDouble("y", Double.NaN) ?: Double.NaN
        if (corpseX.isNaN() || corpseY.isNaN()) { corpseX = Double.NaN; corpseY = Double.NaN }
    }
    private fun corpseKnown() = ghost && !corpseX.isNaN()
    private fun corpseOnMap() = corpseKnown() && corpseX in 0.0..1.0 && corpseY in 0.0..1.0

    /** the game's gravestone at ([x], [y]); a drawn stand-in until the picture is on the device */
    private fun drawCorpse(c: Canvas, x: Float, y: Float) {
        val pic = UiKit.tex("mm_corpse")
        val h = CORPSE_PX / 2f
        if (pic != null) {
            corpseDst.set(x - h, y - h, x + h, y + h)
            c.drawBitmap(pic, null, corpseDst, pArt)
        } else {
            pFill.color = Color.argb(200, 0, 0, 0)
            c.drawRoundRect(x - h * 0.55f, y - h * 0.8f, x + h * 0.55f, y + h * 0.8f, h * 0.5f, h * 0.5f, pFill)
            pFill.color = Color.rgb(170, 170, 160)
            c.drawRoundRect(x - h * 0.45f, y - h * 0.7f, x + h * 0.45f, y + h * 0.7f, h * 0.42f, h * 0.42f, pFill)
        }
    }

    // ---- THE FOCUSED QUEST'S ARROW (user 2026-09-29 14:06: "the focused quest arrow on the mini
    // map"). The game's super-tracked quest; the arrow points at its objective on this map, at its
    // turn-in when it is ready, and off this map at the game's own next waypoint for this map
    // (map.stWay). Worked out when a full map lands, like the other live markers.
    private var focusTX = Double.NaN
    private var focusTY = Double.NaN
    // Three times its first size (the user, 2026-09-30 via Home: "the quest arrow marker needs to be like 3 times
    // bigger, and have special rules for the top side of the screen so that it doesnt go under the map name bar")
    private val RIM_ARROW_PX = 102f
    private val RIM_R = RIM_ARROW_PX / 2f + 4f     // what the marker covers around its centre, at any rotation
    private val FLY_ICON_PX = 44f                   // the flight step's winged boot beside the arrow / on the flight master
    private val FLY_ICON_OFF = RIM_ARROW_PX * 0.62f // that far inside the arrow's centre
    private val FLY_BACK_PX = 52f                   // its dark round backing (the kit's overlay dot: black at 140)
    private val pFlyBack = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** the flight master to fly from, on the field: its winged boot on a filled soft gold light (no outline: the kit's rule) */
    private fun drawFlyStep(c: Canvas, x: Float, y: Float) {
        val r = FLY_ICON_PX * 0.95f
        pFlyGlow.shader = RadialGradient(x, y, r, intArrayOf(Color.argb(200, 255, 209, 0), Color.argb(110, 255, 190, 0),
            Color.argb(0, 255, 190, 0)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, pFlyGlow)
        pFlyGlow.shader = null
        drawIcon(c, Ic.FLIGHT, x, y, FLY_ICON_PX)
    }
    private val pFlyGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * The top of the COMPACT map the rim arrow must stay below: Home's zone-name bar runs 4..68 px down the
     * small map (home ad6d86f), so the whole marker keeps under 72. 0 = no bar.
     */
    /**
     * The HOST hides the open module's + / - (Home, while its buff / debuff list is open over that corner: UI Art, b129, their
     * edges stuck out past the list), as the filter hides the quest cards: not drawn, not touched. LB / RB still zoom.
     */
    var hostZoomHidden = false
        set(v) { if (field != v) { field = v; invalidate() } }

    var compactRimTop = 72f
        set(v) { if (field != v) { field = v; invalidate() } }

    /**
     * THE HOST'S CARD OVER THE SMALL MAP (the user 2026-10-09: "the current quest arrow goes behind the quest overlay making it hard to
     * see. additionally, moving it down would kind of overlap the player, so the players location on the minimap should probably
     * shift down a bit to make space for the arrow"): how far down from this view's top the host covers the small map (Home's
     * focused-quest card lying over the map under its zone band, b121), px; 0 = nothing beyond the name bar. The rim arrow stays
     * below it (a mark under it counts as off the map, so its arrow shows instead), and the player sits lower by half of what the
     * card covers beyond the name bar, so the arrow has the band between the card and the player. The host sets it as its card
     * moves (folds, fades), so the player glides with it.
     */
    var compactCover = 0f
        set(v) { if (abs(field - v) > 0.5f) { field = v; invalidate() } }

    /** the small map's top rule: the host's name bar or its card, whichever reaches lower */
    private fun compactTop(): Float = max(compactRimTop, compactCover)

    /** how far below the view's middle the player sits on the small map: half of what the card covers beyond the name bar */
    private fun compactDown(): Float = max(0f, compactCover - compactRimTop) / 2f

    /**
     * Boxes of the HOST's own controls over the map (view coordinates: Home's buff / debuff buttons and their list on the
     * small map, its buff / debuff rows over the open module), which the rim arrow slides along the edge to stay clear of.
     * NOT Home's + / - column: there the arrow keeps the true edge, under the faded buttons (the user, 2026-10-12: "It
     * should show on the edge of the screen for those"). The open module also keeps clear of its own buttons, storey
     * spine and tracked-quest list.
     */
    var rimKeepOut: List<RectF> = emptyList()
        set(v) { if (v != field) { field = v.map { RectF(it) }; invalidate() } }   // copies; same boxes = no redraw
    private val trackerRect = RectF()              // the tracked-quest list as last drawn (full map)
    private val keepTmp = ArrayList<RectF>(8)
    private val keepBox = RectF()

    /**
     * Slide a rim point ([p] = x, y on the rim rectangle l0..r0 / t0..b0) out of every keep-out box, along the
     * rim: a point on a left / right side moves up or down past the box, one on the top / bottom moves left or
     * right, whichever way is shorter and stays on the rim. Twice over, in case one move lands in another box.
     */
    private fun slideClear(p: FloatArray, l0: Float, t0: Float, r0: Float, b0: Float, keep: List<RectF>) {
        repeat(2) {
            for (k in keep) {
                if (k.isEmpty) continue
                keepBox.set(k.left - RIM_R, k.top - RIM_R, k.right + RIM_R, k.bottom + RIM_R)
                if (!keepBox.contains(p[0], p[1])) continue
                var bx = p[0]; var by = p[1]; var best = Float.MAX_VALUE
                val onV = abs(p[0] - l0) < 1f || abs(p[0] - r0) < 1f
                val onH = abs(p[1] - t0) < 1f || abs(p[1] - b0) < 1f
                if (onV) for (y in floatArrayOf(keepBox.top - 1f, keepBox.bottom + 1f)) if (y in t0..b0 && abs(y - p[1]) < best) {
                    best = abs(y - p[1]); bx = p[0]; by = y
                }
                if (onH) for (x in floatArrayOf(keepBox.left - 1f, keepBox.right + 1f)) if (x in l0..r0 && abs(x - p[0]) < best) {
                    best = abs(x - p[0]); bx = x; by = p[1]
                }
                p[0] = bx; p[1] = by
            }
        }
    }
    private val rimPt = FloatArray(2)

    private fun deriveFocusTarget(m: JSONObject?) {
        focusTX = Double.NaN; focusTY = Double.NaN; flyStep = false
        if (m == null) return
        val st = m.optInt("superTracked", 0)
        if (m.has("superTracked")) {
            val was = focusQid
            focusQid = MapCursor.focusFromMap(true, st, focusQid)          // 0 = none (Map.lua mp1 sends it)
            if (askedQid != 0 && askedQid == focusQid) askedQid = 0
            if (was != st) cards.clear()                 // the focused card's gold moves with the focus
            if (was != st && !orderingDoc) orderTracked(trackedIds())
        }
        val tq = if (st > 0) st else if (focusQid == 0) defaultQid else 0      // the game's focus, else the default
        if (tq <= 0 || !shownQuest(tq)) return            // an untracked focus has no arrow (not on the map at all)
        val qs = m.optJSONArray("quests")
        if (qs != null) for (i in 0 until qs.length()) {
            val q = qs.optJSONObject(i) ?: continue
            if (q.optInt("questID", 0) != tq) continue
            val done = q.optBoolean("done", false)
            val tx = q.optDouble("tx", Double.NaN); val ty = q.optDouble("ty", Double.NaN)
            if (done && !tx.isNaN() && !ty.isNaN()) { focusTX = tx; focusTY = ty }
            else { focusTX = q.optDouble("x", Double.NaN); focusTY = q.optDouble("y", Double.NaN) }
            if (focusTX.isNaN() || focusTY.isNaN()) { focusTX = Double.NaN; focusTY = Double.NaN }
            return
        }
        // off this map: the game's own next waypoint for this map (stWay, the game focus's) when the game answers...
        if (tq == st) m.optJSONObject("stWay")?.let { w ->
            val wx = w.optDouble("x", Double.NaN); val wy = w.optDouble("y", Double.NaN)
            if (!wx.isNaN() && !wy.isNaN()) { focusTX = wx; focusTY = wy; return }
        }
        // ...else where the game's own zone maps show the quest (Map.lua's mapquests via the Map room: offMapSpots), the nearest of
        // them, carried onto this map through the world rects; an arrow needs only the direction. MEASURED 2026-10-07: this build's
        // GetNextWaypointForMap answers nothing across zones (Goldshire, the focus a Dun Morogh quest: no stWay), so without this
        // there was no arrow at all (the user: "I am in stormwind and I dont have a marker for quests in elwyn forest"). The
        // default quest (no game focus) is pointed at the same way.
        val r = rect ?: return
        if (!r.valid) return
        val spots = offMapSpots?.invoke(tq) ?: return
        val best = WorldMapModel.nearestWorld(spots, r.continent, m.optDouble("wx", Double.NaN), m.optDouble("wy", Double.NaN)) ?: return
        focusTX = r.nx(best[2]); focusTY = r.ny(best[1])
        routeFlight(m, r)
    }

    /** the arrow points at a flight master first (routeFlight): it wears the step's icon, the winged boot */
    private var flyStep = false

    /**
     * THE FLIGHT STEP (the user, 2026-10-07: "a marker to a flight master and then the quest Icon"; their picks: the edge arrow wears
     * the step's icon, a flight only when clearly faster): for a quest in another zone, when flying from the known flight point
     * nearest the player to the one nearest the quest is clearly faster than walking (FlightRoute), the arrow goes to that flight
     * master first. The known points are the game's own (Map.lua map.fly, this map's coordinates); after the flight the quest is the
     * nearer walk and the arrow points at it again.
     */
    private fun routeFlight(m: JSONObject, r: PoiStore.Rect) {
        val a = m.optJSONArray("fly") ?: return
        val pxm = m.optDouble("x", Double.NaN); val pym = m.optDouble("y", Double.NaN)
        if (pxm.isNaN() || pym.isNaN()) return
        val nodes = ArrayList<DoubleArray>(a.length())
        val ids = ArrayList<Int>(a.length())
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
            val x = o.optDouble("x", Double.NaN); val y = o.optDouble("y", Double.NaN)
            // b-route-unknown-taxi: a point without its node id (an older add-on) cannot be shown connected: never a flight from it
            if (!x.isNaN() && !y.isNaN()) { nodes.add(doubleArrayOf(x, y)); ids.add(o.optInt("id", 0)) }
        }
        val k = FlightRoute.pick(pxm, pym, focusTX, focusTY, nodes, r.width, r.height) { i, j ->
            ids[i] != 0 && ids[j] != 0 && TaxiNet.connected(ids[i], ids[j])
        } ?: return
        focusTX = nodes[k][0]; focusTY = nodes[k][1]; flyStep = true
    }

    /** a map's world rectangle and its name (the Map room's model, read from the game's own world map data) */
    var worldOf: ((Int) -> WorldMapModel.World?)? = null
    var mapName: ((Int) -> String)? = null
    private var learner: CityExits.Learner? = null
    private fun exitLearner(): CityExits.Learner? {
        learner?.let { return it }
        val prefs = runCatching { context.getSharedPreferences("cityexits", Context.MODE_PRIVATE) }.getOrNull() ?: return null
        return CityExits.Learner(object : CityExits.Learner.Store {
            override fun get(k: String): String? = prefs.getString(k, null)
            override fun put(k: String, v: String?) { prefs.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply() }
        }).also { learner = it }
    }

    /** one map document for the learner: a way out it just learned is logged (WCMAP) in both world yards and the old map's 0..1 */
    private fun learnExit(m: JSONObject) {
        val mapId = m.optInt("mapID", 0)
        val cont = m.optJSONObject("rect")?.optInt("c", -1) ?: -1
        val e = exitLearner()?.onDoc(mapId, cont, m.optDouble("wx", Double.NaN), m.optDouble("wy", Double.NaN), m.optInt("taxi", 0) == 1,
            android.os.SystemClock.elapsedRealtime()) ?: return
        val w = worldOf?.invoke(e.map)
        val at = if (w != null && w.valid) String.format(java.util.Locale.US, " = %.1f, %.1f on its map", w.nx(e.wy) * 100, w.ny(e.wx) * 100) else ""
        android.util.Log.i("WCMAP", "learned a way out of map ${e.map} (${mapName?.invoke(e.map) ?: ""}) to ${e.toMap} " +
            "(${mapName?.invoke(e.toMap) ?: ""})${if (e.transport) " by a transport" else " on foot"}: world ${e.wx.toInt()}, ${e.wy.toInt()}$at")
        routeKey = null
    }

    /** where the game's zone maps show a quest, in world yards ([continent, wx, wy] each): the Map room's kept mapquests (MainActivity) */
    var offMapSpots: ((Int) -> List<DoubleArray>)? = null

    // ---- QUESTIEDB'S GUESSES (the user, 2026-10-06 via tracking: "Yes, Quests room + map"; "QuestieDB only, nothing of Questie's
    // on top"). Quests' add-on reads QuestieDB and puts every quest's spots on the quests document: "qdb" {"src":"qdb", "obj": the
    // objective spots per objective index, nearest first, "turnin": the finisher with its name}, in each map's 0..1 space. A TRACKED
    // quest the game shows nothing for on this map (no point and no turn-in in the map document's `quests`) gets them here, DIMMED
    // under the game's own marks, in UI Art's GHOST look (art-kit 20e67f80: see-through, pale parchment ink, never the focus yellow):
    // a ghost dot at every objective spot plus the quest's number once, as a ghost disc on the spot NEAREST THE PLAYER (it takes taps
    // like a bubble), or its turn-in "?" as a ghost disc when it is ready. The game's answer always wins; no "!" for quests not taken.
    private val qdbSpots = ArrayList<QdbSpots.Spot>()
    private val QDB_SCALE = 0.8f                 // of BUBBLE_ART (UI Art: a guess at 80 % of the live disc)

    private fun deriveQdb(m: JSONObject?) {
        qdbSpots.clear()
        if (m == null || tracked.isEmpty()) return
        val r = rect
        val ax = if (r != null && r.valid) r.width else 1.5; val ay = if (r != null && r.valid) r.height else 1.0   // 0..1 -> yards
        qdbSpots.addAll(QdbSpots.derive(m, tracked, ax, ay))
    }

    // ---- QUESTIEDB'S PINS AND SPAWN SPOTS (QdbPins: Quests' q21 pair per uiMap; the user's pick 2026-10-06, "Try QuestieDB's NPC +
    // node data first"). The SERVICE NPCs and mailboxes replace the bundle's for the map they came for (equal in Westfall, measured
    // by Map's probe: counts, and places within 0.2 yd); the bundle stays the fallback for a map with no answer, and for forge /
    // anvil while ObjectDB has none. The SPAWN SPOTS draw under the service pins in UI Art's ghost family (UiKit.spawnSpot,
    // art-kit e675ef37): herbs while the game's Find Herbs tracking is on (map.track.h, Map.lua), ore while Find Minerals is (m),
    // rares while [showRares]; dots at zone zoom, the game's pictures close in. CHESTS are not QuestieDB's spots (most hold nothing
    // at a time: b-ghost-chests) but the ones the minimap probe saw (SeenChests), while [showChests].
    // Places things CAN spawn, never "it is there now" (the minimap's own blips stay unreadable).
    private val qdbPins = LinkedHashMap<Int, QdbPins.Pins>()     // by uiMap, the last QDB_MAPS
    private val qdbNodes = LinkedHashMap<Int, QdbPins.Nodes>()
    private val QDB_MAPS = 6
    private val SPAWN_DOT = 11f                  // UI Art: herb / ore / chest at zone zoom, a texture of dots
    private val SPAWN_PIC = 26f                  // ... and the game's picture from SPAWN_PIC_PX_PER_YD in
    private val SPAWN_PIC_PX_PER_YD = 1.7f       // a cluster's spots (~15 yd apart) stop overlapping at 26 px
    private val RARE_PX = 30f; private val RARE_ELITE_PX = 36f
    /** the chests the probe saw (SeenChests) show with this (the filter's) */
    var showChests = runCatching { filterPrefs.getBoolean("chests", true) }.getOrDefault(true)
        set(v) { if (field != v) { field = v; invalidate() } }
    /** rares' spawn spots */
    var showRares = runCatching { filterPrefs.getBoolean("rares", true) }.getOrDefault(true)
        set(v) { if (field != v) { field = v; invalidate() } }

    /** {"type":"qdbpins"} (MainActivity routes it): this map's service NPCs, mailboxes and rares */
    fun onQdbPins(d: JSONObject) {
        val p = QdbPins.pins(d) ?: return                       // an error: keep what we had
        if (qdbPins[p.m]?.set == p.set) return                 // a loading screen's re-send: nothing changed
        qdbPins.remove(p.m); qdbPins[p.m] = p
        while (qdbPins.size > QDB_MAPS) qdbPins.remove(qdbPins.keys.first())
        if (p.m == map?.optInt("mapID", Int.MIN_VALUE)) { recomputeSeed(); invalidate() }
    }

    /** {"type":"qdbavail"} (Quests' add-on): this map's quests the player can take, by QuestieDB; the "!" layer's source here */
    fun onQdbAvail(d: JSONObject) {
        val a = QdbPins.avail(d) ?: return
        if (qdbAvail[a.m]?.set == a.set) return                 // the heartbeat's re-send: nothing changed
        qdbAvail.remove(a.m); qdbAvail[a.m] = a
        while (qdbAvail.size > QDB_MAPS) qdbAvail.remove(qdbAvail.keys.first())
        if (a.m == map?.optInt("mapID", Int.MIN_VALUE)) { deriveMarkers(map); invalidate() }
    }
    private val qdbAvail = LinkedHashMap<Int, QdbPins.Avail>()
    /** which of QuestieDB's quests show (the user's pick 2026-10-07, "A: Gold now + grey low"): low-level (trivial) ones, in a
     *  dimmed yellow (the user's correction), yes; those 1-2 levels away ("soon": grey in the game) and holiday ones, no */
    var showTrivialQuests = true
        set(v) { if (field != v) { field = v; deriveMarkers(map); invalidate() } }
    var showSoonQuests = false
        set(v) { if (field != v) { field = v; deriveMarkers(map); invalidate() } }
    /** holiday / world-event quests (offered only while their event runs): hidden until the user's pick */
    var showHolidayQuests = false
        set(v) { if (field != v) { field = v; deriveMarkers(map); invalidate() } }

    /** {"type":"qdbnodes"}: this map's herb / ore / chest spawn spots */
    fun onQdbNodes(d: JSONObject) {
        val n = QdbPins.nodes(d) ?: return
        if (qdbNodes[n.m]?.set == n.set) return
        qdbNodes.remove(n.m); qdbNodes[n.m] = n
        while (qdbNodes.size > QDB_MAPS) qdbNodes.remove(qdbNodes.keys.first())
        if (n.m == map?.optInt("mapID", Int.MIN_VALUE)) invalidate()
    }

    /** this map's QuestieDB services and mailboxes as bundle rows (world yards through [r]), plus the bundle's forge / anvil it lacks */
    private fun qdbRows(p: QdbPins.Pins, r: PoiStore.Rect, bundle: List<PoiStore.Poi>?): List<PoiStore.Poi> {
        val out = ArrayList<PoiStore.Poi>(p.svc.size + 8)
        fun add(entry: Int, name: String, title: String, bits: Int, x: Double, y: Double) {
            out.add(PoiStore.Poi(entry, name, title, bits, r.top - y * r.height, r.left - x * r.width, 0, 0))
        }
        // the developer pedestals go as they do from the bundle; the spirit healer (bits 0) has no pin kind yet
        for (v in QdbPins.dropPedestals(p.svc, r.width, r.height)) if (v.bits != 0) add(v.id, v.name, v.sub, v.bits, v.x, v.y)
        for (q in p.mail) add(0, "Mailbox", "", QdbPins.MAILBOX, q[0], q[1])
        for (q in p.forge) add(0, "Forge", "", QdbPins.FORGE, q[0], q[1])
        for (q in p.anvil) add(0, "Anvil", "", QdbPins.ANVIL, q[0], q[1])
        val smithy = (if (p.forge.isEmpty()) QdbPins.FORGE else 0) or (if (p.anvil.isEmpty()) QdbPins.ANVIL else 0)
        if (smithy != 0 && bundle != null) for (b in bundle) if (b.flags and smithy != 0 && b.flags and smithy.inv() == 0) out.add(b)
        return out
    }

    /** the service rows for this map: QuestieDB's when it answered for it, else the bundle's (the pins and the filter's counts) */
    private fun zoneRows(): List<PoiStore.Poi>? {
        val r = rect
        val qp = map?.let { qdbPins[it.optInt("mapID", Int.MIN_VALUE)] }
        return if (qp != null && r != null && r.valid) qdbRows(qp, r, continentList) else continentList
    }

    /** a tappable mark of quest [qid] at ([x], [y]) for this frame's tap test, as the numbered bubbles are */
    private fun addBubble(x: Float, y: Float, qid: Int) {
        if (bubbleN * 2 + 2 > bubbleXY.size) { bubbleXY = bubbleXY.copyOf(bubbleXY.size * 2); bubbleQ = bubbleQ.copyOf(bubbleQ.size * 2) }
        bubbleXY[2 * bubbleN] = x; bubbleXY[2 * bubbleN + 1] = y; bubbleQ[bubbleN] = qid; bubbleN++
    }

    /** one QuestieDB spot as a ghost: the turn-in "?" disc; an objective's dot, with the quest's numbered disc on its lead spot */
    private fun drawQdbSpot(c: Canvas, x: Float, y: Float, s: QdbSpots.Spot) {
        val size = BUBBLE_ART * QDB_SCALE
        val focus = s.qid == shownFocus()
        if (s.turnIn) { UiKit.questDisc(c, x, y, size, state = UiKit.Quest.TURN_IN, focus = focus, ghost = true); return }
        UiKit.ghostDot(c, x, y)
        if (s.lead) UiKit.questDisc(c, x, y, size, focus = focus, number = trackedNo[s.qid], ghost = true)
    }

    // ---- QUEST AREAS (QuestAreas): the real shapes the game's map shades around an objective, in
    // this zone's 0..1 space, worked out when a full map lands. Drawn the game's way (user pick
    // 2026-09-29 14:34): the FOCUSED quest filled with the game's blue under a soft light-blue rim
    // (the colours of this build's QuestBlob textures), every other quest as a faint outline. A
    // quest with no baked shape (Forever's own quests) keeps a circle of AREA_CIRCLE_YD yards
    // around the game's point, in the same look. Drawn whenever ANY part is in view: the old disc
    // was skipped when its CENTRE left the view, even with the player standing inside the area,
    // and its size was in pixels, so it shrank to a spot exactly when the player arrived.
    private class QArea(
        val xs: FloatArray, val ys: FloatArray, val focused: Boolean,
        val minX: Float, val maxX: Float, val minY: Float, val maxY: Float,
        val traced: Boolean = false,        // QuestTrace's: mapped from the user's play, may still grow
        val floored: Boolean = false        // a QuestieDB area (filled or outlined): never drawn narrower than QdbAreas.FLOOR_PX
    )
    private class QCircle(val x: Double, val y: Double, val focused: Boolean)
    private val qAreas = ArrayList<QArea>()
    private val qCircles = ArrayList<QCircle>()
    private val AREA_CIRCLE_YD = 50.0
    private val qPath = Path()
    private val pAreaFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.argb(128, 57, 109, 255)
    }
    private val pAreaRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; strokeJoin = Paint.Join.ROUND
        color = Color.argb(191, 150, 190, 255)
    }
    private val pAreaLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f; strokeJoin = Paint.Join.ROUND
        color = Color.argb(120, 130, 170, 255)
    }
    // a TRACED area (QuestTrace) wears the same colours with a DASHED edge: "mapped by you so far"
    private val pAreaRimDash = Paint(pAreaRim).apply { pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f) }
    private val pAreaLineDash = Paint(pAreaLine).apply { pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f) }

    /** a list of world points (x north, y west) as a QArea in this zone's 0..1 space, or null off the zone */
    private fun toQArea(r: PoiStore.Rect, wx: DoubleArray, wy: DoubleArray, focused: Boolean, traced: Boolean): QArea? {
        val n = wx.size
        val xs = FloatArray(n); val ys = FloatArray(n)
        var inZone = false
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE
        var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (j in 0 until n) {
            val x = r.nx(wy[j]).toFloat(); val y = r.ny(wx[j]).toFloat()
            xs[j] = x; ys[j] = y
            if (x > -0.05f && x < 1.05f && y > -0.05f && y < 1.05f) inZone = true
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
        return if (inZone) QArea(xs, ys, focused, x0, x1, y0, y1, traced) else null
    }

    /** a QuestieDB area's outline (x, y in this map's 0..1) as a QArea, or null when it lies off the map */
    private fun qAreaOf01(pts: List<DoubleArray>, focused: Boolean, floored: Boolean = false): QArea? {
        if (pts.size < 3) return null
        val xs = FloatArray(pts.size) { pts[it][0].toFloat() }; val ys = FloatArray(pts.size) { pts[it][1].toFloat() }
        val x0 = xs.minOrNull()!!; val x1 = xs.maxOrNull()!!; val y0 = ys.minOrNull()!!; val y1 = ys.maxOrNull()!!
        if (x1 < -0.05f || x0 > 1.05f || y1 < -0.05f || y0 > 1.05f) return null
        return QArea(xs, ys, focused, x0, x1, y0, y1, floored = floored)
    }

    /**
     * QUESTIEDB'S OBJECTIVE AREAS for this map (Quests' qdbareas; QdbAreas groups the spawn spots and rounds each group), the user's
     * picks 2026-10-07: "Soft area per group" (the game's blob look) and "QuestieDB first". True when the quest got one here.
     */
    private fun qdbAreasFor(m: JSONObject, id: Int, focused: Boolean): Boolean {
        val r = rect ?: return false
        if (!r.valid) return false
        val a = qdbAreas[m.optInt("mapID", Int.MIN_VALUE)] ?: return false
        var any = false
        val groups = a.objs.filter { it.qid == id }.flatMap { QdbAreas.groups(it.pts, r.width, r.height) }
        for (g in QdbAreas.keep(groups, focused)) qAreaOf01(g.outline, focused, floored = true)?.let { qAreas.add(it); any = true }
        return any
    }

    /** {"type":"qdbareas"} (Quests' add-on): this map's QuestieDB spawn spots of the tracked quests' objectives */
    fun onQdbAreas(d: JSONObject) {
        val a = QdbAreas.parse(d) ?: return
        QdbAreas.remember(a)                                    // the Map room shows the same map's areas
        if (qdbAreas[a.m]?.set == a.set) return                 // the heartbeat's re-send: nothing changed
        qdbAreas.remove(a.m); qdbAreas[a.m] = a
        while (qdbAreas.size > QDB_MAPS) qdbAreas.remove(qdbAreas.keys.first())
        if (a.m == map?.optInt("mapID", Int.MIN_VALUE)) { deriveQuestAreas(map); invalidate() }
    }
    private val qdbAreas = LinkedHashMap<Int, QdbAreas.Areas>()

    private fun deriveQuestAreas(m: JSONObject?) {
        qAreas.clear(); qCircles.clear()
        deriveQdb(m)
        if (m == null) return
        QuestTrace.init(context)
        val st = shownFocus()                           // the game's focus, else the default (refreshDefault)
        val r = rect
        val placed = HashSet<Int>()
        // a tracked quest the GAME places no point for on this map but QuestieDB has spawns for here still gets its areas
        qdbAreas[m.optInt("mapID", Int.MIN_VALUE)]?.let { a ->
            val onMap = HashSet<Int>()
            m.optJSONArray("quests")?.let { qs -> for (i in 0 until qs.length()) qs.optJSONObject(i)?.let { onMap.add(it.optInt("questID", 0)) } }
            for (o in a.objs.map { it.qid }.distinct()) {
                if (o in onMap || !shownQuest(o)) continue
                if (tracked.firstOrNull { it.optInt("id", 0) == o }?.let { it.optBoolean("ready") || it.optBoolean("done") } == true) continue
                if (qdbAreasFor(m, o, o == st)) placed.add(o)
            }
        }
        val qs = m.optJSONArray("quests") ?: run { qAreas.sortBy { if (it.focused) 1 else 0 }; return }
        for (i in 0 until qs.length()) {
            val q = qs.optJSONObject(i) ?: continue
            if (q.optBoolean("done", false)) continue      // ready: the "?" at the hand-in, no area
            val id = q.optInt("questID", 0)
            if (!shownQuest(id)) continue                  // untracked: no area
            val focused = id != 0 && id == st
            // QuestieDB's areas first (the user's pick), then the shape traced from the user's play, the circle. (The baked
            // AzerothCore shapes, assets/quest_areas.json, left the app with the art harvest, the user 2026-10-09.)
            var any = id in placed || qdbAreasFor(m, id, focused)
            if (!any && r != null && r.valid) QuestTrace.shape(id, r.continent)?.let { pts ->
                val wx = DoubleArray(pts.size) { pts[it][0] }
                val wy = DoubleArray(pts.size) { pts[it][1] }
                toQArea(r, wx, wy, focused, true)?.let { qAreas.add(it); any = true }
            }
            if (!any) {
                val x = q.optDouble("x", Double.NaN); val y = q.optDouble("y", Double.NaN)
                if (!x.isNaN() && !y.isNaN()) qCircles.add(QCircle(x, y, focused))
            }
        }
        qAreas.sortBy { if (it.focused) 1 else 0 }     // the focused fill lies over the outlines
        qCircles.sortBy { if (it.focused) 1 else 0 }
    }

    // ---- THE TRACKED QUESTS, down the Map room's left side (user 2026-09-29 17:30: "when the map is
    // open, show the currently tracked quests on the left side"), as the game's objective tracker lists
    // them: title, then each objective. From the Quests room's own feed (the "quests" document, `watch`
    // set = tracked in the game), handed over by MainActivity. A tap on a quest shows it on the map
    // (focusOn, the Quests room's "Show on map"); nothing is sent to the game. The host may turn it off.
    var showTracker = true
        set(v) { if (field != v) { field = v; invalidate() } }
    private val tracked = ArrayList<JSONObject>()
    /** every quest of the last quests document by id (the focused one may not be a tracked one: p-card-invisible) */
    private val questById = HashMap<Int, JSONObject>()
    private var trackedMap = Int.MIN_VALUE
    private val trackerHits = ArrayList<Pair<RectF, MapList.Item>>()
    private val TRACKER_W = 400f

    // ---- NUMBERED QUEST BUBBLES (the user, LATER.md item 10, 2026-09-30: "in the map function the yellow quest bubble
    // should be numbered and correlate with the quests on the left side, these bubbles are small, so they should be large
    // enough to click at a certain zoom scale, once clicked the focus needs to change to that quest. additionally when
    // clicking on a quest from the tracked list on the left side, it should change the focus to that quest").
    // The tracked quests are numbered 1..n in the left list's order; the same number sits in that quest's objective
    // bubble on the Map room's map. A tap on either asks the HOST to focus the quest ([onFocusQuest]): the host owns the
    // one focus path (Home's checks, QuestPad's cells, TapBridge); this view never taps the pad itself.
    private val trackedNo = HashMap<Int, Int>()          // questID -> its number in the left list
    private var focusQid = 0                             // the game's focused quest (map.superTracked, else the doc's flag)
    // THE DEFAULT FOCUS (the user, 2026-10-06: "I want the focused quest to default to the closest tracked quest if there is
    // "No focused quest""): with no game focus, the nearest TRACKED quest on this map is SHOWN as the focus -- Home's card
    // (focusedCard), the list's top spot, the gold look, the area's fill and the rim arrow. The game's focus is never set
    // (A on it focuses it for real; the hints and A / X keep reading the game's own focusQid). FocusDefault.pick holds the
    // choice until another quest is clearly nearer, so walking between two does not make the top card jump.
    private var defaultQid = 0
    private fun shownFocus(): Int = if (focusQid != 0) focusQid else defaultQid
    /** true while the SHOWN focus is the default (the game has none): Home may label its card, or act on the game's focus only */
    fun focusIsDefault(): Boolean = focusQid == 0 && defaultQid != 0
    /** the quest shown as focused (the game's, else the default), 0 = none */
    fun shownFocusQuest(): Int = shownFocus()

    /**
     * Called (on the UI thread) whenever the SHOWN focus changes -- the game's focus or the default. Home's quest card reads
     * focusedCard() when it draws, so it redraws on this (seen on the emulator: the quests document redrew Home before the map
     * document cleared the game's focus, and the card kept the old quest until something else redrew it).
     */
    var onShownFocusChanged: (() -> Unit)? = null
    private var firedFocus = Int.MIN_VALUE
    private fun fireFocus() {
        val f = shownFocus()
        if (f == firedFocus) return
        firedFocus = f
        onShownFocusChanged?.let { cb -> post { cb() } }
    }

    /** the default focus from [m]'s quests and the player's place; on a change, everything that shows the focus follows */
    private fun refreshDefault(m: JSONObject?) {
        val before = shownFocus()
        val beforeIds = trackedIds()
        defaultQid = if (focusQid != 0 || m == null) 0 else FocusDefault.pick(defaultCands(m), defaultQid, trackedOrder())
        if (shownFocus() == before) return
        cards.clear()
        orderTracked(beforeIds)
        deriveFocusTarget(m)
        deriveQuestAreas(m)
        postInvalidate()
    }

    /** the tracked quests on [m]'s map with their distance from the player in yards (to the turn-in when ready) */
    /** the tracked quests in the list's own order (trackedNo's numbers): the default when none is on the player's map */
    private fun trackedOrder(): List<Int> = trackedNo.entries.sortedBy { it.value }.map { it.key }

    private fun defaultCands(m: JSONObject): List<FocusDefault.Cand> {
        val r = rect ?: return emptyList()
        if (!r.valid) return emptyList()
        val px = m.optDouble("x", Double.NaN); val py = m.optDouble("y", Double.NaN)
        if (px.isNaN() || py.isNaN()) return emptyList()
        val qs = m.optJSONArray("quests") ?: return emptyList()
        val out = ArrayList<FocusDefault.Cand>()
        for (i in 0 until qs.length()) {
            val q = qs.optJSONObject(i) ?: continue
            val id = q.optInt("questID", 0)
            if (id == 0 || !trackedNo.containsKey(id)) continue
            val tx = q.optDouble("tx", Double.NaN); val ty = q.optDouble("ty", Double.NaN)
            val ready = q.optBoolean("done", false) && !tx.isNaN() && !ty.isNaN()
            val qx = if (ready) tx else q.optDouble("x", Double.NaN)
            val qy = if (ready) ty else q.optDouble("y", Double.NaN)
            if (qx.isNaN() || qy.isNaN()) continue
            out.add(FocusDefault.Cand(id, hypot((qx - px) * r.width, (qy - py) * r.height)))
        }
        return out
    }
    private var askedQid = 0                             // the quest a tap asked for, until the game answers
    private var askedAt = 0L
    private val ASK_MS = 8000L
    private val BUBBLE_ART = 76f                         // UI Art: the marker art at 76 = about 48 px visible
    private val BUBBLE_HIT = MIN_TOUCH_PX / 2f           // a 112 px (48 dp) reach around its centre
    private var bubbleXY = FloatArray(32)                 // this frame's bubbles: x, y
    private var bubbleQ = IntArray(16)                   //   and their quests
    private var bubbleN = 0
    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f }

    /**
     * The host's one way to focus a quest (Home: its focus path with every check and the WCHOME log). Null: a tap on the
     * list only centres the map on the quest, as before.
     */
    var onFocusQuest: ((questId: Int) -> Unit)? = null

    private var noteText: String? = null
    private var noteAt = 0L
    private val NOTE_MS = 3500L
    /** A short line at the top of the Map room (why a focus tap was refused). */
    fun note(text: String) {
        noteText = text; noteAt = android.os.SystemClock.elapsedRealtime()
        postInvalidate(); postInvalidateDelayed(NOTE_MS + 50)
    }

    private fun askFocus(qid: Int) {
        if (qid == 0) return
        val cb = onFocusQuest ?: return
        if (qid == focusQid) return                      // already the focused quest: nothing to send
        askedQid = qid; askedAt = android.os.SystemClock.elapsedRealtime()
        cb(qid)
        postInvalidate()
    }

    // ---- THE CONTROLLER (LATER item 5, Home's spec, 2026-09-30). While the Map room shows it holds the controller as the
    // generic guest "map" ([GuestPad]; Quests.lua q6 takes interact's shared capture for it and relays every press as
    // {"type":"guestpad","id":"map"}). [MapCursor] turns a press into what a touch would do: the D-pad walks the tracked-quest
    // list and the numbered bubbles (or pans), A focuses (the one focus path, [onFocusQuest]), X / Y zoom, B closes the filter
    // or leaves with "B" (the capture's document then takes the app Home). MainActivity calls [onShown] / [onHidden] and
    // routes the id "map" documents to [onPad] / [onGuestAck]. The guest cells ride the quests document's pad.
    private val cursor = MapCursor()
    private var padDoc: JSONObject? = null
    private var held = false
    private var lastPadN = -1
    private var bSaid = "home"
    // the hold is asked again while it has no answer ([HoldAsk]: Home's consistency pass, b74's leak to Home's bar)
    private val holdAsk = HoldAsk()
    private val holdTick = Runnable { askHold() }
    private fun askHold() {
        removeCallbacks(holdTick)
        if (!holdAsk.due()) return
        if (holdAsk.asked(guestTap(GuestPad.cells(padDoc, "map", hold = true)))) postDelayed(holdTick, HoldAsk.EVERY_MS)
    }
    private var listFirst = 0                 // the first tracked quest the list draws (it scrolls)
    private var listFits = 1                  // how many the list fitted last frame
    private var listMaxFirst = 0              // the furthest it may scroll (its last quest in view)
    private fun trackedIds(): List<Int> = tracked.map { it.optInt("id", 0) }

    // ---- THE WHOLE LIST (the user, 2026-10-16; [MapList]): the portrait above the quests, the tracked quests, then the Untracked
    // section with one tab per quest-log header, every tab closed until the player opens it. [items] is what the list draws and the
    // cursor walks; it is rebuilt when the quests document, the tracked order or the zone changes, or a tab opens / closes.
    /** A on an untracked quest: the host's way to TRACK it (QuestPad's watch). Null: A only says so. */
    var onTrackQuest: ((questId: Int) -> Unit)? = null
    /** A on the portrait: the host's way to open the game's own player menu (Quests q38). Null: A only says so. */
    var onPlayerMenu: (() -> Unit)? = null
    private var items: List<MapList.Item> = emptyList()
    private val untrackedLog = ArrayList<JSONObject>()        // the log's untracked quests, in log order
    private var portrait: JSONObject? = null                      // the quests document's `me`: name, class file, class word
    private var ownHeaders: List<String> = emptyList()         // the log headers that are the player's class or professions
    private val openTabs = HashMap<String, Boolean>()          // the tabs the player opened (all closed until then)
    private var listWhere: String? = null                      // the zone and subzone the tabs were sorted for
    private fun listKeys(): List<Int> = MapList.stops(items)
    private fun groupZone(key: Int): String? = (items.firstOrNull { it.key == key } as? MapList.Item.Group)?.zone

    private fun rebuildItems() {
        val m = map
        val entries = untrackedLog.map { e ->
            MapList.Entry(e.optInt("id", 0), e.optString("zone", ""), if (e.has("lv")) e.optInt("lv") else null,
                here = e.has("x") && e.has("y"))
        }
        val current = listOfNotNull(m?.optString("subzone", "")?.ifEmpty { null }, m?.optString("zone", "")?.ifEmpty { null })
        val groups = MapList.groups(entries, questsPlv, current, ownHeaders)
        items = MapList.items(portrait != null && trackedKnown, trackedIds(), groups) { z -> openTabs[z] == true }
    }

    private fun toggleTab(key: Int) {
        val z = groupZone(key) ?: return
        openTabs[z] = openTabs[z] != true
        rebuildItems()
        listShow(key)
        invalidate()
    }

    /**
     * the cursor came onto [key]: the portrait brings the map to the player; a tracked quest with a mark on this map comes into view;
     * a quest with none here (another zone, or an untracked one) brings the map back to the player too (b-map-glide, the user:
     * "they are just keeping the map placement where ever it just was instead of transitioning to the player location")
     */
    private fun landOn(key: Int) {
        when {
            key == MapList.ME -> centreOnPlayer()
            key > 0 && key in trackedNo && selectedPoint() != null -> revealSelected()
            key > 0 -> centreOnPlayer()
        }
    }

    /**
     * THE MAP GLIDES TO THE PLAYER, at this zoom (the portrait: "it should center my current position on the map"; b-map-glide: "the
     * map snaps to the player location instead of gliding smoothly"): the glide home a pan already uses ([recentreFrom], eased over
     * RECENTRE_MS toward the LIVE position), from where the view is now; at its end the view follows the player.
     */
    private fun centreOnPlayer() {
        focusRestoreZoom = Float.NaN
        zoomEase = Float.NaN
        autoZoom = false; autoZoomCur = zoom
        camAncX = Float.NaN
        panX = lastViewCx; panY = lastViewCy
        followPlayer = false
        recentreFrom = floatArrayOf(lastViewCx, lastViewCy)
        recentreStart = System.currentTimeMillis()
        invalidate()
    }

    /** a touch on a row of the list: a quest as always; the portrait centres (again: its menu); a tab opens / closes */
    private fun touchItem(it: MapList.Item) {
        when (it) {
            is MapList.Item.Tracked -> touchQuest(it.id, tracked.firstOrNull { e -> e.optInt("id", 0) == it.id })
            is MapList.Item.Me -> {
                if (cursor.shown && cursor.sel == MapList.ME) onPlayerMenu?.invoke()
                cursor.touched(MapList.ME, listKeys()); centreOnPlayer()
            }
            is MapList.Item.Group -> { cursor.touched(it.key, listKeys()); toggleTab(it.key) }
            is MapList.Item.Untracked -> { cursor.touched(it.id, listKeys()); listShow(it.id) }
            is MapList.Item.Section -> {}
        }
        invalidate()
    }

    // ---- THE PLAYER MENU ON THE BOTTOM SCREEN ([PlayerMenu]; the user, 2026-10-16: "a replication of the menu on the bottom screen
    // basically that the controller still drives"). A on the portrait asks the game for its own player menu (Quests q41 reads it) and
    // shows it beside the list; a chosen entry goes back as its id (two digit taps) and the game's own entry runs it.
    /** the host's way to run entry [id] of the game's player menu (Quests q41's digit cells) */
    var onPlayerMenuRun: ((id: Int) -> Unit)? = null
    /** the host's way to tap column [col] of the add-on's secure "pfocus" row (Set / Clear Focus, Quests q43) */
    var onPlayerMenuSecure: ((col: Int) -> Unit)? = null
    private val pmenu = PlayerMenu()
    private val pmenuHits = ArrayList<Pair<RectF, Int>>()
    private val pmenuBox = RectF()

    private fun openPlayerMenu() {
        val ask = onPlayerMenu ?: run { note("The player menu is not on the tap pad yet"); return }
        pmenu.openReading()
        ask()
        syncB(); invalidate()
    }

    private fun pmenuDid(d: PlayerMenu.Do) {
        when (d) {
            is PlayerMenu.Do.Run -> onPlayerMenuRun?.invoke(d.id) ?: note("The player menu is not on the tap pad yet")
            is PlayerMenu.Do.Secure -> onPlayerMenuSecure?.invoke(d.col) ?: note("Focus is not on the tap pad yet")
            is PlayerMenu.Do.Refused -> note(d.why)
            PlayerMenu.Do.Close, PlayerMenu.Do.None -> {}
        }
        syncB(); invalidate()
    }

    /** {"type":"pmenu"} (Quests q41): the game's player menu, as it would show it now */
    fun onPlayerMenuDoc(d: JSONObject) {
        if (!pmenu.open) return
        pmenu.onDoc(d)
        postInvalidate()
    }

    /** the game's answer to a run (guestack op "pmenurun"): only a refusal is said */
    fun onPlayerMenuRan(msg: JSONObject) {
        if (!msg.optBoolean("ok")) note(msg.optString("why", "").ifEmpty { "The game did not do it" })
    }

    private fun drawPlayerMenu(c: Canvas, x: Float, y0: Float, yMax: Float, maxW: Float) {
        pmenuHits.clear()
        val rows = pmenu.rows()
        val rowH = 80f; val head = 64f
        val lines = if (pmenu.loading || pmenu.empty) 1 else rows.count { !(it.title && it.text == pmenu.heading()) }.coerceAtLeast(1)
        val w = min(560f, maxW)
        if (w < 200f || yMax - y0 < head + rowH) return
        pmenuBox.set(x, y0, x + w, min(yMax, y0 + 20f + head + lines * rowH + 20f))
        val inner = UiKit.menu(c, pmenuBox, solid = true)
        val hp = UiKit.bodyPaint(28f, cGold)
        val heading = (if (pmenu.depth > 0) "< " else "") + (pmenu.heading() ?: portrait?.optString("n", "") ?: "")
        c.drawText(ellipsize(hp, heading, inner.width() - 32f), inner.left + 16f, inner.top + 40f, hp)
        val top0 = inner.top + head
        if (pmenu.loading || pmenu.empty) {
            val dp = UiKit.bodyPaint(24f, cDim)
            c.drawText(if (pmenu.loading) "Asking the game..." else "The game's menu could not be read here", inner.left + 16f, top0 + 46f, dp)
            return
        }
        // the game's own title row is the heading already: not twice
        val shown = rows.indices.filter { !(rows[it].title && rows[it].text == pmenu.heading()) }
        val fit = ((inner.bottom - top0) / rowH).toInt().coerceAtLeast(1)
        val first = (shown.indexOf(pmenu.sel) - fit + 1).coerceIn(0, max(0, shown.size - fit))
        for (k in first until min(shown.size, first + fit)) {
            val i = shown[k]
            val e = rows[i]
            val r = RectF(inner.left, top0 + (k - first) * rowH, inner.right, top0 + (k - first + 1) * rowH)
            if (e.title) {
                val tp = UiKit.bodyPaint(22f, cDim)
                c.drawText(ellipsize(tp, e.text, r.width() - 32f), r.left + 16f, r.centerY() + 8f, tp)
                continue
            }
            UiKit.menuRow(c, r, e.text + (if (e.sub != null) "  >" else ""), i == pmenu.sel, enabled = e.enabled,
                mark = if (e.checked == true) "icon_check" else null, size = 28f)
            pmenuHits.add(RectF(r) to i)
        }
    }

    // ---- AN UNTRACKED QUEST'S PANEL (the user, 2026-10-16: Y "opens a panel kind of like a tool tip that does not take over the
    // full space, but it shows the right half panel of what it would show in the quest log, complete with two buttons at the bottom,
    // for Share and Abandon instead of track and more"). Beside the list: the quest as the Quests room's right half words it (title in
    // its difficulty colour, level and zone, its state, the next step, the objectives, the description), scrolled with the D-pad, and
    // Share (A) and Abandon (Y) through the Quests actions. Abandon asks first, as the game does (the add-on arms it and names the
    // quest; A drops it, B keeps it). B closes the panel.
    /** A on an untracked quest: the host tracks it AND focuses it (the user: "A to track and Focus") */
    var onTrackFocusQuest: ((questId: Int) -> Unit)? = null
    var onShareQuest: ((questId: Int) -> Unit)? = null
    var onAbandonArm: ((questId: Int) -> Unit)? = null
    var onAbandonConfirm: ((questId: Int) -> Unit)? = null
    private var panelId = 0
    private var armedId = 0
    private var armedTitle = ""
    private var armedAt = 0L
    private var panelScroll = 0f
    private var panelContentH = 0f
    private var panelViewH = 0f
    private val panelBox = RectF()
    private val panelHits = ArrayList<Pair<RectF, String>>()
    private val ARM_MS = 10_000L                     // Quests.lua ARM_SECONDS: a confirm after it is refused

    private fun openPanel(id: Int) { panelId = id; armedId = 0; panelScroll = 0f; panelContentH = 0f; syncB(); invalidate() }
    private fun closePanel() { panelId = 0; armedId = 0; panelHits.clear() }
    private fun armedNow(): Boolean = armedId != 0 && armedId == panelId && android.os.SystemClock.elapsedRealtime() - armedAt < ARM_MS

    private fun panelKey(k: String) {
        when (k) {
            "PAD2" -> if (armedNow()) armedId = 0 else closePanel()
            "PAD1" -> panelAct(if (armedNow()) "abandon!" else "share")
            "PAD4" -> if (!armedNow()) panelAct("abandon")
            "PADDUP" -> panelScroll = (panelScroll - 90f).coerceAtLeast(0f)
            "PADDDOWN" -> panelScroll = (panelScroll + 90f).coerceAtMost(max(0f, panelContentH - panelViewH))
        }
        syncB(); invalidate()
    }

    private fun panelAct(act: String) {
        val id = panelId
        if (id == 0) return
        when (act) {
            "share" -> {
                val q = questById[id] ?: return
                if (!questsParty) { note("You are not in a party."); return }          // the game's ERR_QUEST_PUSH_NOT_IN_PARTY_S
                if (!q.optBoolean("push")) { note("That quest cannot be shared."); return }
                onShareQuest?.invoke(id) ?: note("Share is not on the tap pad yet")
            }
            "abandon" -> {
                if (questById[id]?.optBoolean("canAbandon", true) == false) { note("That quest cannot be abandoned."); return }
                onAbandonArm?.invoke(id) ?: note("Abandon is not on the tap pad yet")
            }
            "abandon!" -> if (armedNow()) { armedId = 0; onAbandonConfirm?.invoke(id) }
            "cancel" -> armedId = 0
        }
        syncB(); invalidate()
    }

    /** {"type":"questack"}: Abandon's arm opens the question; a share / abandon answer is said */
    fun onQuestAck(msg: JSONObject) {
        val op = msg.optString("op")
        val id = msg.optInt("id", 0)
        if (panelId == 0 || (id != 0 && id != panelId)) return
        val ok = msg.optBoolean("ok")
        when (op) {
            "arm" -> if (ok) { armedId = panelId; armedTitle = msg.optString("title", "this quest"); armedAt = android.os.SystemClock.elapsedRealtime() }
                else note(msg.optString("why", "").ifEmpty { "The game did not abandon it" })
            "abandon" -> if (ok) { closePanel(); note("Abandoned") } else note(msg.optString("why", "").ifEmpty { "The game did not abandon it" })
            "share" -> note(if (ok) "Shared" else msg.optString("why", "").ifEmpty { "The game did not share it" })
            else -> return
        }
        syncB(); postInvalidate()
    }

    private fun wrap(text: String, p: Paint, w: Float): android.text.StaticLayout =
        android.text.StaticLayout.Builder.obtain(text, 0, text.length, android.text.TextPaint(p), w.toInt().coerceAtLeast(10)).setLineSpacing(0f, 1.05f).build()

    private fun drawPanel(c: Canvas, x: Float, y0: Float, yMax: Float, maxW: Float) {
        panelHits.clear()
        val q = questById[panelId]
        if (q == null) { closePanel(); return }                     // it left the log
        val w = min(680f, maxW)
        if (w < 260f || yMax - y0 < 260f) return
        val pad = 20f
        val btnH = 84f
        val armed = armedNow()
        // as tall as its text needs (a tooltip, not a pane: the user), at most down to the field's bottom
        val want = if (panelContentH > 0f) 10f + 16f + panelContentH + 8f + (if (armed) 46f else 0f) + 16f + btnH + 10f else Float.MAX_VALUE
        panelBox.set(x, y0, x + w, min(yMax, y0 + max(want, 360f)))
        val inner = UiKit.menu(c, panelBox, solid = true)
        val tw = inner.width() - 2f * pad
        val actTop = inner.bottom - btnH - 16f - (if (armed) 46f else 0f)
        // the text, scrolled, clipped above the buttons
        panelViewH = actTop - inner.top - 8f
        panelScroll = panelScroll.coerceIn(0f, max(0f, panelContentH - panelViewH))
        c.save()
        c.clipRect(inner.left, inner.top, inner.right, actTop - 8f)
        c.translate(inner.left + pad, inner.top + 16f - panelScroll)
        var y = 0f
        val tl = wrap(q.optString("title", "Quest"), UiKit.bodyPaint(32f, cardFor(q).colour), tw); tl.draw(c); y += tl.height + 8f
        val meta = listOfNotNull(if (q.has("lv")) "Level ${q.optInt("lv")}" else null, q.optString("zone", "").ifEmpty { null },
            if (q.optInt("grp", 0) > 1) "Group ${q.optInt("grp")}" else null,
            if (trackedNo.containsKey(panelId)) "Tracked" else "Not tracked").joinToString("  ·  ")
        c.drawText(ellipsize(UiKit.bodyPaint(22f, cDim), meta, tw), 0f, y + 22f, UiKit.bodyPaint(22f, cDim)); y += 38f
        when {
            q.optBoolean("failed") -> { c.drawText("Failed", 0f, y + 24f, UiKit.bodyPaint(25f, Color.rgb(230, 70, 50))); y += 38f }
            q.optBoolean("ready") || q.optBoolean("done") -> { c.drawText("Ready to turn in", 0f, y + 24f, UiKit.bodyPaint(25f, Theme.ok)); y += 38f }
        }
        q.optString("wp", "").takeIf { it.isNotEmpty() }?.let {
            val l = wrap("Next: $it", UiKit.bodyPaint(24f, Theme.text), tw); c.save(); c.translate(0f, y); l.draw(c); c.restore(); y += l.height + 10f
        }
        val objs = q.optJSONArray("obj")
        if (objs != null && objs.length() > 0) {
            y += 6f
            c.drawText("Objectives", 0f, y + 26f, UiKit.bodyPaint(27f, cGold)); y += 42f
            for (i in 0 until objs.length()) {
                val o = objs.optJSONObject(i) ?: continue
                val fin = o.optBoolean("f")
                val t = o.optString("t", "").ifEmpty { if (o.has("need")) "${o.optInt("have")}/${o.optInt("need")}" else "objective" }
                val l = wrap(t, UiKit.bodyPaint(24f, if (fin) Theme.dim else Theme.text), tw - 34f)
                UiKit.objectiveMark(c, 13f, y + 15f, 22f, done = fin)
                c.save(); c.translate(34f, y); l.draw(c); c.restore()
                y += l.height + 10f
            }
        }
        q.optString("desc", "").takeIf { it.isNotEmpty() }?.let { desc ->
            y += 14f
            val edge = 28f
            val l = wrap(desc, UiKit.bodyPaint(23f, Color.rgb(48, 32, 16)), tw + 16f - edge * 2.4f)
            val box = RectF(-8f, y, tw + 8f, y + l.height + 44f + edge * 2.2f + 8f)
            val pin = UiKit.parchment(c, box, edge)
            c.drawText("Description", pin.left, pin.top + 26f, UiKit.bodyPaint(26f, Color.rgb(64, 40, 16)))
            c.save(); c.translate(pin.left, pin.top + 44f); l.draw(c); c.restore()
            y = box.bottom + 12f
        }
        panelContentH = y + 24f
        c.restore()
        if (panelContentH - panelScroll > panelViewH + 1f) {
            val cue = RectF(inner.left, inner.top, inner.right, actTop - 14f)
            UiKit.moveArrowsUpDown(c, cue, up = false, down = true, size = 18f, width = 26f, around = true)
        }
        // the two buttons: Share and Abandon (Cancel and Abandon while the game asks)
        val gap = 10f
        val bw = (inner.width() - 2f * pad - gap) / 2f
        val b1 = RectF(inner.left + pad, inner.bottom - btnH - 12f, inner.left + pad + bw, inner.bottom - 12f)
        val b2 = RectF(b1.right + gap, b1.top, b1.right + gap + bw, b1.bottom)
        val glyphs = held || padHints
        if (armed) {
            val qp = UiKit.bodyPaint(25f, Theme.text)
            c.drawText(ellipsize(qp, "Abandon \"$armedTitle\"?", tw), inner.left + pad, b1.top - 16f, qp)
            if (glyphs) UiKit.button(c, b1, "Cancel", UiKit.backGlyph()) else UiKit.button(c, b1, "Cancel")
            if (glyphs) UiKit.button(c, b2, "Abandon", glyph("a")) else UiKit.button(c, b2, "Abandon")
            panelHits.add(RectF(b1) to "cancel"); panelHits.add(RectF(b2) to "abandon!")
        } else {
            val shareOk = questsParty && q.optBoolean("push")
            val abandonOk = q.optBoolean("canAbandon", true)
            val s1 = if (shareOk) UiKit.Button.UP else UiKit.Button.DISABLED
            val s2 = if (abandonOk) UiKit.Button.UP else UiKit.Button.DISABLED
            if (glyphs) UiKit.button(c, b1, "Share", glyph("a"), s1) else UiKit.button(c, b1, "Share", s1)
            if (glyphs) UiKit.button(c, b2, "Abandon", glyph("y"), s2) else UiKit.button(c, b2, "Abandon", s2)
            panelHits.add(RectF(b1) to "share"); panelHits.add(RectF(b2) to "abandon")
        }
        if (armedId != 0 && !armed) { armedId = 0 }                   // the game's question ran out
        else if (armed) postInvalidateDelayed(500)
    }

    // ---- THE CURSOR'S DOTTED LINE (the user, 2026-10-16: first "Opening the map module ... highlights a dotted line slowly moving
    // towards the quest marker if it is available", then on test13: "The dotted lines to quests is only showing when first opening the
    // Map module, instead of on what ever quest I am currently highlighting (if available)"). From the player to the HIGHLIGHTED quest's
    // mark, tracked or untracked, its dots marching toward the mark, for as long as the quest is highlighted (my pick: stays on; it
    // fades in on each new quest). No line on the portrait, a tab, or a quest with no mark on this map.
    private var pathFor = 0                       // the quest the line was drawn for (a new one fades in)
    private var pathAt = 0L
    private val pPath = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pathTmp = Path()

    /**
     * THE LINE'S ROUTE for the highlighted quest ([CursorRoute]; the user's agreed design, 2026-10-16): the nearest still-needed goal on
     * this map, else the next step of the way there (a known, connected flight master; the game's next waypoint; the map's edge).
     * Worked out again when the quest, the map document or the quests document changes (not every frame).
     */
    private var routeKey: Any? = null
    private var routeNow: CursorRoute.Route? = null
    private fun cursorRoute(): CursorRoute.Route? {
        val q = cursor.sel
        if (!cursor.shown || q <= 0) return null
        val m = map ?: return null
        val key = listOf(q, System.identityHashCode(m), System.identityHashCode(questById[q]))
        if (key == routeKey) return routeNow
        routeKey = key
        routeNow = null
        val r = rect ?: return null
        if (!r.valid) return null
        val mapId = m.optInt("mapID", Int.MIN_VALUE)
        var mq: JSONObject? = null
        m.optJSONArray("quests")?.let { qs -> for (i in 0 until qs.length()) qs.optJSONObject(i)?.let { if (it.optInt("questID", 0) == q) mq = it } }
        val px = m.optDouble("x", Double.NaN); val py = m.optDouble("y", Double.NaN)
        val goals = ArrayList(CursorRoute.goals(mq, questById[q], mapId))
        // where the game's zone maps show it (the Map room's kept mapquests), carried onto this map: a goal when it falls on this map
        var far: DoubleArray? = null
        offMapSpots?.invoke(q)?.let { spots ->
            WorldMapModel.nearestWorld(spots, r.continent, m.optDouble("wx", Double.NaN), m.optDouble("wy", Double.NaN))?.let { best ->
                val fx = r.nx(best[2]); val fy = r.ny(best[1])
                if (fx in 0.0..1.0 && fy in 0.0..1.0) { if (goals.isEmpty()) goals.add(doubleArrayOf(fx, fy)) } else far = doubleArrayOf(fx, fy)
            }
        }
        // the game's next waypoint is given for its focused quest only (Map.lua's stWay)
        val way = if (q == focusQid) m.optJSONObject("stWay")?.let { w ->
            val wx = w.optDouble("x", Double.NaN); val wy = w.optDouble("y", Double.NaN)
            if (wx.isNaN() || wy.isNaN()) null else doubleArrayOf(wx, wy)
        } else null
        val nodes = ArrayList<CursorRoute.Node>()
        m.optJSONArray("fly")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
            val x = o.optDouble("x", Double.NaN); val y = o.optDouble("y", Double.NaN)
            if (!x.isNaN() && !y.isNaN()) nodes.add(CursorRoute.Node(x, y, o.optInt("id", 0), o.optString("name", "")))
        } }
        // the map's real ways out (b-route-city-exits): what this player learned here, else the approved table's estimates
        fun here(wx: Double, wy: Double) = doubleArrayOf(r.nx(wy), r.ny(wx))
        val ways = ArrayList<CityExits.Way>()
        val learned = exitLearner()?.of(mapId) ?: emptyList()
        for (e in learned) {
            val at = here(e.wx, e.wy)
            val end = if (e.transport) (if (!e.toWx.isNaN()) here(e.toWx, e.toWy) else null) else at
            if (end != null) ways.add(CityExits.Way(e, at[0], at[1], end[0], end[1]))
        }
        val learnedFoot = learned.any { !it.transport }
        for (e in CityExits.TABLE) if (e.map == mapId && !(learnedFoot && !e.transport)) {
            val end = if (!e.transport) doubleArrayOf(e.x, e.y)
                else worldOf?.invoke(e.toMap)?.takeIf { it.valid && it.continent == r.continent }?.let { w -> here(w.wx(e.toY), w.wy(e.toX)) }
            if (end != null) ways.add(CityExits.Way(e, e.x, e.y, end[0], end[1]))
        }
        routeNow = CursorRoute.plan(px, py, r.width, r.height, goals, far, way, questById[q]?.optString("wp", ""), nodes,
            ways = ways, inCity = mapId in CityExits.CITIES,
            wayName = { e ->
                if (!e.learned) e.name
                else mapName?.invoke(e.toMap)?.takeIf { it.isNotEmpty() }?.let { if (e.transport) "Ride to $it" else "To $it" }
            }) { i, j -> TaxiNet.connected(i, j) }
        return routeNow
    }

    /**
     * The line from the player ([x0], [y0]) to the route's point ([x1], [y1]), inside the field [fl]..[fb]. A point off the field: the
     * dots run to its edge, where a small gold arrow points on and the yards stand; a step of the way (flight, waypoint, exit) wears its
     * words at the line's end.
     */
    private fun drawCursorPath(c: Canvas, qid: Int, route: CursorRoute.Route, yards: Int, x0: Float, y0: Float, x1: Float, y1: Float,
                               fl: Float, ft: Float, fr: Float, fb: Float) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (qid != pathFor) { pathFor = qid; pathAt = now }
        val inset = 26f
        val l = fl + inset; val t = ft + inset; val r = fr - inset; val b = fb - inset
        val inside = x1 in l..r && y1 in t..b
        var ex = x1; var ey = y1
        if (!inside) {
            // where the line leaves the field (the player clamped in first: a panned map may have the player off it too)
            val ox = x0.coerceIn(l, r); val oy = y0.coerceIn(t, b)
            val dx = x1 - ox; val dy = y1 - oy
            var k = 1f
            if (dx > 0f) k = min(k, (r - ox) / dx) else if (dx < 0f) k = min(k, (l - ox) / dx)
            if (dy > 0f) k = min(k, (b - oy) / dy) else if (dy < 0f) k = min(k, (t - oy) / dy)
            ex = ox + dx * k; ey = oy + dy * k
        }
        val len = hypot(ex - x0, ey - y0)
        val age = now - pathAt
        val a = min(1f, age / 300f)
        if (len >= 40f) {
            // the dots: 16 px apart, moving 24 px a second toward the goal (a dash path's phase runs against the path, hence minus)
            val phase = -(age / 1000f * 24f) % 16f
            pathTmp.reset(); pathTmp.moveTo(x0, y0); pathTmp.lineTo(ex, ey)
            pPath.pathEffect = DashPathEffect(floatArrayOf(0.1f, 16f), phase)
            pPath.strokeWidth = 9f; pPath.color = Color.argb((160 * a).toInt(), 0, 0, 0); c.drawPath(pathTmp, pPath)
            pPath.strokeWidth = 6f; pPath.color = Color.argb((255 * a).toInt(), Color.red(cGold), Color.green(cGold), Color.blue(cGold))
            c.drawPath(pathTmp, pPath)
            pPath.pathEffect = null
        }
        // at the edge: an arrow on along the way (not for the focused quest: the game's own rim arrow already points there)
        if (!inside && qid != shownFocus()) {
            val ang = atan2((y1 - ey).toDouble(), (x1 - ex).toDouble()).toFloat()
            c.save(); c.rotate(Math.toDegrees(ang.toDouble()).toFloat(), ex, ey)
            pathTmp.reset(); pathTmp.moveTo(ex + 14f, ey); pathTmp.lineTo(ex - 8f, ey - 11f); pathTmp.lineTo(ex - 8f, ey + 11f); pathTmp.close()
            pArrowFill.color = Color.argb((255 * a).toInt(), Color.red(cGold), Color.green(cGold), Color.blue(cGold)); c.drawPath(pathTmp, pArrowFill)
            pArrowEdge.color = Color.argb((200 * a).toInt(), 0, 0, 0); c.drawPath(pathTmp, pArrowEdge)
            c.restore()
        }
        // the words: the step's (flight / waypoint / exit) and the yards; a goal on the field needs none
        val words = listOfNotNull(route.label, if (!inside || route.kind != CursorRoute.Kind.GOAL) "$yards yd" else null).joinToString("  ·  ")
        if (words.isNotEmpty()) {
            val tp = UiKit.bodyPaint(22f, Color.argb((255 * a).toInt(), 240, 228, 200))
            val tw = tp.measureText(words)
            // set back from the end toward the player, kept inside the field
            val ux = if (len > 1f) (ex - x0) / len else 0f; val uy = if (len > 1f) (ey - y0) / len else 0f
            val back = if (inside) 46f else 96f                        // clear of the edge arrow
            val cx = (ex - ux * back).coerceIn(fl + tw / 2f + 12f, fr - tw / 2f - 12f)
            val cy = (ey - uy * back + 8f).coerceIn(ft + 30f, fb - 12f)
            labelBox.set(cx - tw / 2f - 10f, cy - 22f, cx + tw / 2f + 10f, cy + 8f)
            pLabelBack.color = Color.argb((190 * a).toInt(), 10, 8, 6); c.drawRoundRect(labelBox, 8f, 8f, pLabelBack)
            c.drawText(words, cx - tw / 2f, cy, tp)
        }
        // the dots keep moving while the map shows: about 30 frames a second is plenty for 24 px a second
        if (isShown) postInvalidateDelayed(33)
    }
    private val pArrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pArrowEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val pLabelBack = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelBox = RectF()

    // ---- THE CONTROLS LINE (the user, 2026-09-30: "we still need to add the one line to the bottom with the focus controlls and
    // stuff for that screen"): the kit's hints (UiKit.hints: glyph + word, as the bar's "A Open  B Back"), on a slim band along the
    // field's bottom, while the map holds the controller (the Map room's guest, or a host that says so with [padHints]). The
    // buttons and the scale label stand above the band while it shows.
    /** a host that holds the controller itself (Home's full map) shows the controls line with this */
    var padHints = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val HINT_BAND = 62f
    // during Home's close the line keeps showing (fading with the rest) if it showed when the close began; Home lets go of
    // the controller as it starts closing
    private fun hintsOn(): Boolean =
        if (morphOn && !morphOpening) morphHints else (!compact || morphOn) && (held || padHints)
    private fun glyph(k: String): String = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> when (k) {
            "a" -> "ltr_a"; "b" -> "ltr_b"; "x" -> "ltr_x"; "y" -> "ltr_y"; "lb" -> "ltr_lshoulder"; "rb" -> "ltr_rshoulder"
            else -> "ltr_up" }
        CastSettings.ButtonLayout.PLAYSTATION -> when (k) {
            "a" -> "shp_cross"; "b" -> "shp_circle"; "x" -> "shp_square"; "y" -> "shp_triangle"; "lb" -> "shp_lshoulder"
            "rb" -> "shp_rshoulder"; else -> "shp_up" }
        else -> when (k) {
            "a" -> "rev_a"; "b" -> "rev_b"; "x" -> "rev_x"; "y" -> "rev_y"; "lb" -> "rev_lshoulder"; "rb" -> "rev_rshoulder"
            else -> "gen_up" }
    }

    private fun drawPadHints(c: Canvas, left: Float, top: Float, fw: Float, fh: Float) {
        val band = RectF(left, top + fh - HINT_BAND, left + fw, top + fh)
        pFill.color = Color.argb(175, 8, 6, 4)
        c.drawRect(band, pFill)
        pFill.color = Color.argb(200, 120, 96, 52)
        c.drawRect(band.left, band.top, band.right, band.top + 1.5f, pFill)
        val cy = band.centerY()
        if (filterOpen) { UiKit.hints(c, band.right - 24f, cy, glyph("b") to "Close"); return }
        if (pmenu.open) { UiKit.hints(c, band.right - 24f, cy, glyph("up") to "Select", glyph("a") to "Choose", glyph("b") to "Back"); return }
        if (panelId != 0) {
            val hp = if (armedId == panelId) arrayOf(glyph("a") to "Abandon", glyph("b") to "Cancel")
                else listOfNotNull(if (panelContentH > panelViewH + 1f) glyph("up") to "Scroll" else null, glyph("a") to "Share",
                    glyph("y") to "Abandon", glyph("b") to "Close").toTypedArray()
            UiKit.hints(c, band.right - 24f, cy, *hp); return
        }
        // only what does something on the map right now (UI Art / the user's clutter rule)
        val ids = trackedIds()
        val sel = if (cursor.shown && cursor.sel != 0 && ids.contains(cursor.sel)) cursor.sel else 0
        val q = if (sel != 0) tracked.firstOrNull { it.optInt("id", 0) == sel } else null
        val pairs = ArrayList<Pair<String, String>>()
        val stops = listKeys()
        val at = if (cursor.shown && stops.contains(cursor.sel)) cursor.sel else 0
        if (stops.size > 1) pairs.add(glyph("up") to "Select")
        when {
            at == MapList.ME -> if (onPlayerMenu != null) pairs.add(glyph("a") to "Menu")
            MapList.isGroup(at) -> pairs.add(glyph("a") to (if (openTabs[groupZone(at)] == true) "Close" else "Open"))
            at > 0 && at in MapList.untracked(items) -> {
                if (onTrackFocusQuest != null) pairs.add(glyph("a") to "Track & focus")
                if (onTrackQuest != null) pairs.add(glyph("x") to "Track")
                pairs.add(glyph("y") to "Open")
            }
        }
        if (sel != 0) {
            if (sel != focusQid) pairs.add(glyph("a") to "Focus")
            else if (onUnfocusQuest != null) pairs.add(glyph("a") to "Unfocus")
            if (onUntrackQuest != null) pairs.add(glyph("x") to "Untrack")
            pairs.add(glyph("y") to "Open")                       // the quest's panel, as on an untracked quest
        }
        pairs.add(glyph("b") to "Back")
        UiKit.hints(c, band.right - 24f, cy, *pairs.toTypedArray())
    }

    // ---- ONLY TRACKED QUESTS ON THE MAP (the user, 2026-09-30: "Untracked quests should not appear in the map module at all"):
    // a quest of the map document's `quests` (the whole log on this map) is drawn -- its bubble or dot, its "?", its area, its
    // pull on the framing, the rim arrow -- only while it is in the game's tracker. Until the quests document has arrived the
    // tracker is unknown and every quest shows, so the map is never blank for want of it.
    private var trackedKnown = false
    private fun shownQuest(qid: Int): Boolean = !trackedKnown || trackedNo.containsKey(qid)

    /** the host's way to UNTRACK a quest (X; QuestPad's unwatch). Null: X only says so. */
    var onUntrackQuest: ((questId: Int) -> Unit)? = null
    /** the host's way to UNFOCUS (A on the focused quest; needs a pad column from Quests). Null: A only says so. */
    var onUnfocusQuest: ((questId: Int) -> Unit)? = null

    /** where the selected quest is on this map (its objective, or its turn-in when ready), or null */
    private fun selectedPoint(): Marker? {
        if (!cursor.shown || cursor.sel == 0) return null
        for (mk in mkMid) if (mk.qid == cursor.sel && mk.shape == SH_OBJECTIVE) return mk
        for (mk in mkTurnIn) if (mk.qid == cursor.sel) return mk
        for (q in qdbSpots) if (q.lead && q.qid == cursor.sel) return Marker(q.x, q.y, if (q.turnIn) SH_TURNIN else SH_OBJECTIVE, q.qid)
        return null
    }

    /**
     * THE SELECTED QUEST COMES INTO VIEW (the user, 2026-09-30: "whatever quest is currently selected, the dynamic map should
     * shift the map around to try to include that quest in the map view. If for some reason the quest is on the left half of
     * the screen then it should shift over to center that quest so its not under the quest list"). While the map frames
     * itself, computeFrame includes the selected quest; after a manual pan / zoom a selection hands the map back to that
     * dynamic framing (below).
     */
    private fun revealSelected() {
        if (selectedPoint() == null) return
        // Selecting a quest HANDS THE MAP BACK TO THE DYNAMIC FRAMING, also after a manual pan or zoom (the user, 2026-09-30:
        // "After manually moving the map and then selecting a quest its snapping the screen to the quest, I want it to
        // instead return to the dynamic map movement"): the state a double-tap restores. computeFrame then frames the player
        // and the selected quest, clear of the list, gliding from what is on screen (camAncX = NaN re-seeds the camera).
        followPlayer = true
        recentreFrom = null
        focusRestoreZoom = Float.NaN
        zoomEase = Float.NaN
        if (!autoZoom) { autoZoom = true; autoZoomCur = zoom }
        camAncX = Float.NaN
        invalidate()
    }

    /**
     * THE TOP SPOT HOLDS THE FOCUSED QUEST (the user, 2026-10-01; Home's card flies into it and back out of it), the rest in
     * the game tracker's order (their numbers, which the bubbles wear, are kept). A new focus slides up there (the rows glide,
     * drawTracker); the selection stays on its quest and the list keeps it in view.
     */
    private var orderingDoc = false          // onQuests is rebuilding the list: it orders once, at its end
    private fun orderTracked(before: List<Int>) {
        if (tracked.size < 2) return
        val f = shownFocus()                     // the game's focus, else the default focus
        tracked.sortBy { e -> val id = e.optInt("id", 0); if (id == f && f != 0) 0 else trackedNo[id] ?: Int.MAX_VALUE }
        if (!orderingDoc) rebuildItems()
        if (trackedIds() != before) {          // against the order that was SHOWN, so an unchanged list never scrolls
            if (cursor.shown && cursor.sel != 0) listShow(cursor.sel) else listFirst = 0
            postInvalidate()
        }
    }

    /** keep [id] in the list's window */
    private fun listShow(id: Int) {
        val i = items.indexOfFirst { it.key == id && it !is MapList.Item.Section }
        if (i < 0) return
        if (i < listFirst) listFirst = i
        else if (i >= listFirst + listFits) listFirst = i - listFits + 1
    }

    /** a touch picked quest [id]: select it (highlighted at once) and focus it, as the list always did */
    private fun touchQuest(id: Int, q: JSONObject?) {
        cursor.touched(id, listKeys())
        listShow(id)
        revealSelected()
        if (onFocusQuest != null) askFocus(id)
        else if (q != null) {
            val qx = q.optDouble("x", Double.NaN); val qy = q.optDouble("y", Double.NaN)
            if (!qx.isNaN() && !qy.isNaN()) focusOn(qx, qy, trackedMap)
        }
        invalidate()
    }

    /** only when the leave-with-B cell cannot be tapped: the host's own way Home */
    var onLeave: (() -> Unit)? = null

    private fun guestTap(cells: List<Pair<String, Int>>?): Boolean = cells != null && TapBridge.taps(context, padDoc, cells)

    /** the Map room came on screen: hold the controller */
    fun onShown() {
        lastPadN = -1; bSaid = "home"
        cursor.reset()
        openTabs.clear(); rebuildItems()          // every opening starts with the Untracked tabs closed (the user, 2026-10-16)
        holdAsk.shown()
        askHold()
    }

    /** the Map room left the screen: let the controller go */
    fun onHidden() {
        held = false
        holdAsk.hidden(); removeCallbacks(holdTick)
        cursor.reset()
        pmenu.close(); closePanel()
        guestTap(GuestPad.cells(padDoc, "map", hold = false))
        invalidate()
    }

    /** {"type":"guestack","id":"map"} */
    fun onGuestAck(msg: JSONObject) {
        when (msg.optString("op")) {
            "hold" -> {
                holdAsk.answered()                          // either way: the add-on has answered, no more asking
                if (msg.optBoolean("ok")) {
                    held = true; bSaid = "home"; syncB()
                    // the room just took the controller: the selection is shown at once, on the focused quest, else the first
                    if (cursor.startAt(listKeys(), shownFocus())) { listShow(cursor.sel); revealSelected() }
                }
            }
            "free", "leave" -> held = false
        }
        invalidate()
    }

    /** {"type":"guestpad","id":"map"}: one press, only while the room shows (MainActivity checks) */
    fun onPad(msg: JSONObject) {
        holdAsk.answered()                                  // a press (or "lost"): the capture is talking to this room
        if (msg.has("lost")) { held = false; cursor.reset(); invalidate(); return }
        val n = msg.optInt("n", -1)
        if (n == lastPadN) return                           // the same press seen twice
        lastPadN = n
        held = true
        if (applyKey(msg.optString("k")) == MapCursor.Do.Leave && !guestTap(GuestPad.leaveB(padDoc))) onLeave?.invoke()
        syncB()
        invalidate()
    }

    /**
     * For a host that holds the controller ITSELF (Home's full-screen map, its "home" guest): one key [k], the same as the
     * Map room's. False when the key is not the map's: B with no filter to close (the host leaves).
     */
    fun hostKey(k: String): Boolean {
        val d = applyKey(k)
        invalidate()
        return d != MapCursor.Do.Leave
    }

    /** the host's full map just took the controller: the selection SHOWN at once (the focused quest, else the first) */
    fun hostStart() {
        if (cursor.startAt(listKeys(), shownFocus())) { listShow(cursor.sel); revealSelected() }
        invalidate()
    }

    /** where the host's next B leads: "back" (closes the filter) or "home" */
    fun hostBLeads(): String = cursor.bLeads(filterOpen || pmenu.open || panelId != 0)

    /** apply one key; everything but leaving (returned as [MapCursor.Do.Leave] for the caller) */
    private fun applyKey(k: String): MapCursor.Do {
        if (pmenu.open) { pmenuDid(pmenu.key(k)); return MapCursor.Do.Nothing }      // every key is the open player menu's
        if (panelId != 0) { panelKey(k); return MapCursor.Do.Nothing }                // ... and the open quest panel's
        val d = cursor.key(k, listKeys(), focusQid, filterOpen, MapList.untracked(items))
        when (d) {
            MapCursor.Do.Show -> { listShow(cursor.sel); landOn(cursor.sel) }
            is MapCursor.Do.Select -> { listShow(d.id); landOn(d.id) }
            MapCursor.Do.PlayerMenu -> openPlayerMenu()
            is MapCursor.Do.Toggle -> toggleTab(d.key)
            is MapCursor.Do.Track -> onTrackFocusQuest?.invoke(d.id) ?: note("Track is not on the tap pad yet")
            is MapCursor.Do.Watch -> onTrackQuest?.invoke(d.id) ?: note("Track is not on the tap pad yet")
            is MapCursor.Do.Open -> openPanel(d.id)
            is MapCursor.Do.Focus -> askFocus(d.id)
            is MapCursor.Do.Unfocus -> onUnfocusQuest?.invoke(d.id) ?: note("Unfocus is not on the tap pad yet")
            is MapCursor.Do.Untrack -> onUntrackQuest?.invoke(d.id) ?: note("Untrack is not on the tap pad yet")
            is MapCursor.Do.ShowOnMap -> tracked.firstOrNull { it.optInt("id", 0) == d.id }?.let { q ->
                val qx = q.optDouble("x", Double.NaN); val qy = q.optDouble("y", Double.NaN)
                if (!qx.isNaN() && !qy.isNaN()) focusOn(qx, qy, trackedMap) else note("This quest has no place on this map")
            }
            is MapCursor.Do.Zoom -> {
                autoZoom = false; zoomEase = Float.NaN; focusRestoreZoom = Float.NaN
                zoom = if (d.out) (zoom / 1.6f).coerceAtLeast(1f) else (zoom * 1.6f).coerceAtMost(ZOOM_MAX)
                autoZoomCur = zoom
            }
            MapCursor.Do.CloseFilter -> filterOpen = false
            MapCursor.Do.ToggleFilter -> { filterOpen = !filterOpen; syncB() }
            MapCursor.Do.Leave, MapCursor.Do.Nothing -> {}
        }
        return d
    }

    /** tell the capture where B leads now, so the Home button shows B only when it leads Home */
    private fun syncB() {
        if (!held) return
        val where = cursor.bLeads(filterOpen || pmenu.open || panelId != 0)
        if (where != bSaid && guestTap(GuestPad.bnext(padDoc, home = where == "home"))) bSaid = where
    }

    /** the Quests room's document (MainActivity routes "quests" here too) */
    fun onQuests(q: JSONObject) {
        q.optJSONObject("pad")?.let { padDoc = it; if (holdAsk.padArrived()) askHold() }
        questsPlv = if (q.has("plv")) q.optInt("plv") else null
        questsParty = q.optBoolean("party")
        questsTriv = if (q.has("trivRange")) q.optInt("trivRange") else 5
        cards.clear()
        val shownBefore = trackedIds()
        tracked.clear()
        questById.clear()
        trackedNo.clear()
        trackedMap = q.optInt("mapID", Int.MIN_VALUE)
        untrackedLog.clear()
        portrait = q.optJSONObject("me")
        ownHeaders = q.optJSONArray("own")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it, "").ifEmpty { null } } } ?: emptyList()
        val list = q.optJSONArray("list") ?: return
        var docFocus = 0
        for (i in 0 until list.length()) {
            val e = list.optJSONObject(i) ?: continue
            if (e.optBoolean("focus")) docFocus = e.optInt("id", 0)
            e.optInt("id", 0).takeIf { it != 0 }?.let { questById[it] = e }
            if (e.has("watch") && !e.isNull("watch")) {
                tracked.add(e)
                val id = e.optInt("id", 0)
                if (id != 0) trackedNo[id] = tracked.size
            } else if (e.optInt("id", 0) != 0) untrackedLog.add(e)
        }
        // "none" too when the map document does not say (an older add-on left superTracked out for none: test24's stuck Unfocus)
        focusQid = MapCursor.focusFromQuests(docFocus, map?.has("superTracked") == true, focusQid)
        trackedKnown = true
        orderingDoc = true
        map?.let { m -> deriveMarkers(m); deriveFocusTarget(m); deriveQuestAreas(m) }
        orderingDoc = false
        orderTracked(shownBefore)
        refreshDefault(map)                     // the tracked list changed: the default focus may too
        fireFocus()
        rebuildItems()
        cursor.keepValid(listKeys())
        listFirst = listFirst.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (askedQid != 0 && askedQid == focusQid) askedQid = 0
        postInvalidate()
    }

    // ---- THE SELECTION'S UP / DOWN ARROWS (the user, 2026-09-30: "Put some up and down arrows on the currently selected quest
    // too, so that its clear that the controller can change it", then "The up and down arrows on the quests should actually be on
    // the top and bottom of the selection not the side, add some buffer between current selection and next quests"). The game's
    // own gold arrow turned, drawn by UI Art's kit (UiKit.moveArrowsUpDown, around + gap), centred above and below the highlighted
    // row in a SEL_GAP gap the list opens around it.
    private val SEL_GAP = 28f            // the gap above and below the selected row, the arrows' room

    private fun ellipsize(p: Paint, s: String, w: Float): String {
        if (p.measureText(s) <= w) return s
        var n = s.length
        while (n > 1 && p.measureText(s, 0, n) + p.measureText("...") > w) n--
        return s.substring(0, n).trimEnd() + "..."
    }

    // ---- THE LIST'S ROWS GLIDE (the user's answer, 2026-10-01: with the map open, a newly focused quest "slides to the top", an
    // animated move, the cursor riding with it). Every row's top, the list's scroll and its ground's height ease to where the
    // layout puts them, in LIST SPACE (0 = the first quest's top), so a reorder, a selection's gaps opening, a scroll or a quest
    // leaving all move instead of jumping. The rows are clipped to the list's window, so a row arriving from outside it slides in.
    private val LINE_O = 29f
    private val ROW_PAD_X = 14f
    private val rowPos = HashMap<Int, Float>()
    private var listScroll = Float.NaN
    private var listGroundH = Float.NaN
    private var lastRowAt = 0L
    private val ROW_TAU = 0.07f              // seconds: settled in about a quarter of a second

    // ---- EVERY ROW IS UI ART'S QUEST CARD (LATER 18, "one module language"): the list and Home's quest module draw the one
    // picture, UiKit.questCard, so the card that flies between them is one drawing changing only its box and type size. The cards
    // stand 8 px apart (UiKit.MODULE_GAP) with no ground behind the list: each card is its own well. focused = the top spot's
    // quest (the game's focused one): the gold bar and the gold disc; every other card the dark disc, no bar. The card draws no
    // cursor: the selection box and the up / down arrows stay the list's.
    private val cards = HashMap<Int, UiKit.QuestCard>()      // questID -> its card, until the quests document or the focus changes
    private var questsParty = false                          // in a party: Share Quest's other half (Quests.lua q11)
    private var questsPlv: Int? = null                       // the quests document's player level and trivial range (Home's tier)
    private var questsTriv = 5

    /** the card for one entry of the quests document: the title in its difficulty colour (Home's rule), the objectives as the
     *  game's tracker reads them, the map's number */
    private fun cardFor(e: JSONObject): UiKit.QuestCard {
        val id = e.optInt("id", 0)
        cards[id]?.let { return it }
        val lv = if (e.has("lv")) e.optInt("lv") else null
        val colour = when (HomeQuests.tier(lv, questsPlv, questsTriv, e.optBoolean("trivial"))) {
            QuestTier.TRIVIAL -> Theme.questGrey
            QuestTier.STANDARD -> Theme.questGreen
            QuestTier.DIFFICULT -> Theme.questYellow
            QuestTier.VERY_DIFFICULT -> Theme.questOrange
            QuestTier.IMPOSSIBLE -> Theme.questRed
            QuestTier.UNKNOWN -> Theme.gold
        }
        val objs = ArrayList<Pair<String, Boolean>>()
        e.optJSONArray("obj")?.let { arr ->
            for (k in 0 until arr.length()) {
                val o = arr.optJSONObject(k) ?: continue
                // an unreadable text arrives as no `t`: the numbers still do (as Home and the Quests room show them)
                val t = o.optString("t", "").ifEmpty { if (o.has("need")) "${o.optInt("have")}/${o.optInt("need")}" else "" }
                objs.add(t to o.optBoolean("f"))
            }
        }
        val card = UiKit.QuestCard(e.optString("title", ""), lv ?: 0, colour, objs,
            turnIn = e.optBoolean("ready") || e.optBoolean("done"), number = trackedNo[id], focused = id != 0 && id == shownFocus())
        cards[id] = card
        return card
    }

    /**
     * THE FOCUSED QUEST'S CARD, the very object the list's top spot draws (its number, colour and objectives): Home's quest module
     * draws it at UiKit.CARD_HOME_SCALE, so both ends of the flight are one picture (the user picked the harmony sheet, 2026-10-01).
     * Null when no TRACKED quest is focused (the list holds tracked quests only).
     */
    fun focusedCard(): UiKit.QuestCard? {
        val f = shownFocus()                     // the default focus stands in for "No focused quest" (2026-10-06)
        if (f == 0) return null
        // the tracked quest's card (its number); a focused quest the tracker does not list still gets its card, without a number
        // (p-card-invisible: the user saw no card while a quest was focused)
        val e = tracked.firstOrNull { it.optInt("id", 0) == f } ?: questById[f] ?: return null
        return cardFor(e)
    }

    /** one card's height (the selection's gaps and the gap under it are the layout's) */
    private fun rowContentH(e: JSONObject): Float = UiKit.questCardHeight(cardFor(e))

    /** one quest's card in [box] at type size [scale], [alpha] */
    private fun drawRow(c: Canvas, e: JSONObject, box: RectF, scale: Float = 1f) {
        UiKit.questCard(c, box, cardFor(e), scale = scale)
    }

    private val ME_H = 92f                   // the portrait's row
    private val GROUP_H = 52f                // an untracked tab (the quest book's header, smaller)
    private val SECTION_H = 62f              // the "Untracked" line (UiKit.trackerHeader)

    /** one item's height in the list */
    private fun itemH(it: MapList.Item): Float = when (it) {
        is MapList.Item.Me -> ME_H
        is MapList.Item.Tracked -> tracked.firstOrNull { e -> e.optInt("id", 0) == it.id }?.let { e -> rowContentH(e) } ?: 0f
        is MapList.Item.Section -> SECTION_H
        is MapList.Item.Group -> GROUP_H
        is MapList.Item.Untracked -> questById[it.id]?.let { e -> rowContentH(e) } ?: 0f
    }

    /** the portrait: the class's picture in the game's round ring, the name and "Level N Class" */
    private fun drawMe(c: Canvas, box: RectF) {
        val who = portrait ?: return
        pFill.color = Color.argb(215, 18, 12, 8); c.drawRoundRect(box, 10f, 10f, pFill)
        val cy = box.centerY(); val rad = (box.height() - 18f) / 2f; val cx = box.left + 12f + rad
        pFill.color = Color.argb(255, 8, 6, 4); c.drawCircle(cx, cy, rad, pFill)
        UiKit.classIcon(who.optString("c", ""))?.let { b ->
            val save = c.save()
            pathTmp.reset(); pathTmp.addCircle(cx, cy, rad - 3f, Path.Direction.CW); c.clipPath(pathTmp)
            c.drawBitmap(b, null, RectF(cx - rad, cy - rad, cx + rad, cy + rad), pBmpList)
            c.restoreToCount(save)
        }
        pRing.color = cGold; c.drawCircle(cx, cy, rad, pRing)
        val tx = cx + rad + 16f; val tw = box.right - 12f - tx
        val np = UiKit.bodyPaint(27f, cGold)
        c.drawText(ellipsize(np, who.optString("n", ""), tw), tx, cy - 6f, np)
        val lp = UiKit.bodyPaint(21f, cDim)
        val line = listOfNotNull(questsPlv?.let { "Level $it" }, who.optString("cl", "").ifEmpty { null }).joinToString(" ")
        c.drawText(ellipsize(lp, line, tw), tx, cy + 24f, lp)
    }

    /** an untracked tab: the quest book's header bar, the zone, its count and the game's + / - */
    private fun drawGroup(c: Canvas, g: MapList.Item.Group, r: RectF) {
        val bar = UiKit.tex("list_heading")
        if (bar != null) {
            val cap = 30f / 56f
            val capS = (bar.height * cap).toInt().coerceIn(1, bar.width / 2); val capD = r.height() * cap
            srcList.set(0, 0, capS, bar.height); dstList.set(r.left, r.top, r.left + capD, r.bottom); c.drawBitmap(bar, srcList, dstList, pBmpList)
            srcList.set(capS, 0, bar.width - capS, bar.height); dstList.set(r.left + capD, r.top, r.right - capD, r.bottom); c.drawBitmap(bar, srcList, dstList, pBmpList)
            srcList.set(bar.width - capS, 0, bar.width, bar.height); dstList.set(r.right - capD, r.top, r.right, r.bottom); c.drawBitmap(bar, srcList, dstList, pBmpList)
        } else { pFill.color = Color.argb(200, 40, 30, 20); c.drawRect(r, pFill) }
        val tp = UiKit.bodyPaint(22f, Color.rgb(230, 222, 200)).apply { setShadowLayer(2f, 1f, 1f, Color.BLACK) }
        val label = "${g.zone} (${g.count})"
        c.drawText(ellipsize(tp, label, r.width() - 80f), r.left + 18f, r.centerY() - (tp.ascent() + tp.descent()) / 2f, tp)
        UiKit.tex(if (g.open) "list_minus" else "list_plus")?.let { b ->
            val sc = 26f / 26f
            val bw = b.width * sc; val bh = b.height * sc
            val cx = r.right - 30f; val cy = r.centerY()
            dstList.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f); c.drawBitmap(b, null, dstList, pBmpList)
        }
    }
    private val srcList = Rect()
    private val dstList = RectF()
    private val pBmpList = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun drawTracker(c: Canvas, x: Float, y0: Float, yMax: Float, fly: Boolean) {
        trackerHits.clear()
        trackerRect.setEmpty()
        flyLand.setEmpty()
        if (items.isEmpty() || yMax - y0 < 60f) { rowPos.clear(); listScroll = Float.NaN; listGroundH = Float.NaN; return }
        // the same width onDraw keeps clear (trackPx); during Home's open / close, the full map's (so the top spot holds still)
        val fieldW = if (morphOn) (if (morphOpening) morphTo.width() else morphFrom.width()) else lastFieldW
        val w = min(TRACKER_W, fieldW * 0.34f)
        val dp = UiKit.bodyPaint(23f, cDim)
        val lineO = LINE_O
        val gapC = UiKit.MODULE_GAP
        val list = items
        // the selected row stands in a gap above and below for its up / down arrows (the user: "add some buffer between current
        // selection and next quests")
        fun isSel(it: MapList.Item) = cursor.shown && it !is MapList.Item.Section && it.key == cursor.sel
        fun need(it: MapList.Item) = itemH(it) + gapC + (if (isSel(it)) 2f * SEL_GAP else 0f)
        // THE LAYOUT, in list space
        val n = list.size
        val at = FloatArray(n)
        var acc = 0f
        for (i in 0 until n) { at[i] = acc; acc += need(list[i]) }
        // the furthest it may scroll: until its LAST row is in view (a list that fits never scrolls)
        var tail = 0f
        var maxFirst = n - 1
        for (i in (0 until n).reversed()) {
            val nd = need(list[i])
            if (y0 + (if (i > 0) lineO else 0f) + tail + nd > yMax) break
            tail += nd; maxFirst = i
        }
        listMaxFirst = maxFirst
        listFirst = listFirst.coerceIn(0, listMaxFirst)
        val above = listFirst
        var h = (if (above > 0) lineO else 0f)
        var shown = 0
        for (i in listFirst until n) {
            val nd = need(list[i])
            if (y0 + h + nd + lineO > yMax && shown > 0) break
            h += nd; shown++
        }
        if (shown == 0) return
        listFits = shown
        val more = n - listFirst - shown
        if (more > 0) h += lineO
        // THE GLIDE toward the layout
        val now = android.os.SystemClock.elapsedRealtime()
        // the step since the last frame; after a pause (an idle map does not redraw) ONE frame's, or the first frame of a move
        // would do nearly all of it (seen on the emulator: the reorder jumped)
        val since = now - lastRowAt
        val dt = if (lastRowAt == 0L || since > 100L) 0.016f else (since / 1000f)
        lastRowAt = now
        val ek = 1f - exp(-dt / ROW_TAU)
        var moving = false
        fun ease(cur: Float, to: Float): Float {
            if (cur.isNaN()) return to
            val v = cur + (to - cur) * ek
            if (abs(to - v) < 0.5f) return to
            moving = true
            return v
        }
        listScroll = ease(listScroll, at[listFirst])
        listGroundH = ease(listGroundH, h)
        val live = HashSet<Int>(n * 2)
        for (i in 0 until n) {
            val k = list[i].key
            live.add(k)
            rowPos[k] = ease(rowPos[k] ?: Float.NaN, at[i])
        }
        rowPos.keys.retainAll(live)
        // no ground: each card is its own well (the box is still the list's, for touches and the rim arrow's keep-out)
        val gh = listGroundH
        trackerRect.set(x, y0, x + w, y0 + gh)
        val winTop = y0 + (if (above > 0) lineO else 0f)
        val winBot = y0 + gh - (if (more > 0) lineO else 0f)
        if (above > 0) c.drawText("$above more above", x + ROW_PAD_X, y0 + lineO - 10f, dp)
        // THE TOP SPOT, where Home's flying card lands (see [morph]): the first tracked quest's row as the list puts it once settled
        val ti = list.indexOfFirst { it is MapList.Item.Tracked }
        if (ti >= listFirst && ti >= 0) {
            val e0 = tracked.firstOrNull { it.optInt("id", 0) == list[ti].key }
            if (e0 != null) {
                val t0 = winTop + at[ti] - at[listFirst] + (if (isSel(list[ti])) SEL_GAP else 0f)
                flyLand.set(x, t0, x + w, t0 + rowContentH(e0))
            }
        }
        val stops = listKeys()
        c.save()
        c.clipRect(x, winTop, x + w, winBot)
        // the FOCUSED quest is drawn last, so while it slides up to the top spot it passes over the others
        for (pass in 0..1) for (i in 0 until n) {
            val it = list[i]
            val front = it is MapList.Item.Tracked && it.id == shownFocus()
            if ((pass == 1) != front) continue
            if (fly && i == ti && front) continue            // in flight: drawFlight draws it
            val pos = rowPos[it.key] ?: at[i]
            val sel = isSel(it)
            val ch = itemH(it)
            val ct = winTop + pos - listScroll + (if (sel) SEL_GAP else 0f)
            if (ct + ch < winTop - SEL_GAP || ct > winBot + SEL_GAP) continue    // outside the window
            val row = RectF(x, ct, x + w, ct + ch)
            when (it) {
                is MapList.Item.Me -> drawMe(c, row)
                is MapList.Item.Tracked -> tracked.firstOrNull { e -> e.optInt("id", 0) == it.id }?.let { e -> drawRow(c, e, row) }
                is MapList.Item.Section -> UiKit.trackerHeader(c, row.left, row.top, row.right, "Untracked", "${it.count}")
                is MapList.Item.Group -> drawGroup(c, it, row)
                is MapList.Item.Untracked -> questById[it.id]?.let { e -> drawRow(c, e, row) }
            }
            if (it !is MapList.Item.Section) trackerHits.add(row to it)
            // THE SELECTED ROW, highlighted with the kit's focus (the user: "it should like highlight"), and the up / down arrows
            // that say the controller changes it (none above the first stop, none below the last), centred in its gaps
            if (sel) {
                UiKit.focus(c, row)
                val si = stops.indexOf(it.key)
                UiKit.moveArrowsUpDown(c, row, up = si > 0, down = si < stops.size - 1, size = 22f, width = 34f, around = true, gap = SEL_GAP)
            }
        }
        c.restore()
        if (more > 0) c.drawText("$more more below", x + ROW_PAD_X, y0 + gh - 10f, dp)
        if (moving) postInvalidateOnAnimation()
    }

    /**
     * HOME'S FLYING CARD (see [morph]): the focused quest's card ([drawRow], UI Art's questCard), its box and type size easing
     * together between Home's module ([morphCard], Home's px, at UiKit.CARD_HOME_SCALE) and the list's top spot ([flyLand], 1).
     * Opening it lands; closing it leaves.
     */
    private fun drawFlight(c: Canvas) {
        val card = morphCard ?: return
        if (flyLand.isEmpty || tracked.isEmpty()) return
        val e = tracked[0]
        if (e.optInt("id", 0) != shownFocus()) return
        val a = if (morphOpening) morphK else 1f - morphK         // 1 = in the list's top spot
        val ox = hostX(); val oy = hostY()
        // UI Art's flight: the box AND the type size eased together, Home's CARD_HOME_SCALE to the list's 1
        flyTmp.set(card.left - ox + (flyLand.left - (card.left - ox)) * a, card.top - oy + (flyLand.top - (card.top - oy)) * a,
                   card.right - ox + (flyLand.right - (card.right - ox)) * a, card.bottom - oy + (flyLand.bottom - (card.bottom - oy)) * a)
        val sc = UiKit.CARD_HOME_SCALE + (1f - UiKit.CARD_HOME_SCALE) * a
        drawRow(c, e, flyTmp, sc)
        if (cursor.shown && cursor.sel == e.optInt("id", 0)) UiKit.focus(c, flyTmp)
    }

    // ---- FOCUS: the Quests room's "Show on map" (requested by the quests session).
    // It frames the player AND the point together, AS A PAN: the map says "panned - walk to
    // recentre", walking brings it home exactly as it does after a drag, and the zoom the player
    // had comes back with it. A ring pulses on the point for a few seconds so it is obvious which
    // pin was meant; with names gone from the map, nothing else would say so.
    private var focusX = 0.0
    private var focusY = 0.0
    private var focusAt = 0L
    private var focusRestoreZoom = Float.NaN
    private val FOCUS_RING_MS = 3000L
    private val FOCUS_MIN_YD = 160.0       // never frame tighter than this across
    private val pFocus = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }

    /**
     * Show ([x], [y]) -- this zone's 0..1 map space, the addon's quest x/y -- with the player.
     * @param mapId the uiMapID the point belongs to. When it is given and is not the map on
     *   screen, nothing changes and this returns false (the caller should just open the map).
     * @return true when the view moved
     */
    fun focusOn(x: Double, y: Double, mapId: Int = Int.MIN_VALUE): Boolean {
        val m = map ?: return false
        if (x.isNaN() || y.isNaN()) return false
        if (mapId != Int.MIN_VALUE && m.optInt("mapID", Int.MIN_VALUE) != mapId) return false
        val px = m.optDouble("x", Double.NaN)
        val py = m.optDouble("y", Double.NaN)
        val hasPlayer = !px.isNaN() && !py.isNaN()
        val cx = if (hasPlayer) (x + px) / 2 else x
        val cy = if (hasPlayer) (y + py) / 2 else y
        var z = zoom
        val r = rect
        if (r != null && r.valid) {
            // zoom z shows width/z yards across and height/z down; fit both spans with a margin
            val spanX = if (hasPlayer) abs(x - px) * r.width else 0.0
            val spanY = if (hasPlayer) abs(y - py) * r.height else 0.0
            val zx = r.width / max(spanX * 1.35, FOCUS_MIN_YD)
            val zy = if (spanY > 0.0) r.height / (spanY * 1.35) else Double.MAX_VALUE
            z = min(zx, zy).toFloat().coerceIn(1f, ZOOM_MAX)
        }
        if (focusRestoreZoom.isNaN()) focusRestoreZoom = zoom
        zoomEase = Float.NaN
        zoom = z
        followPlayer = false
        recentreFrom = null
        panX = cx.toFloat().coerceIn(0f, 1f)
        panY = cy.toFloat().coerceIn(0f, 1f)
        // walking away from where the player stands NOW is what brings the view home
        panAnchorX = if (hasPlayer) px.toFloat() else panX
        panAnchorY = if (hasPlayer) py.toFloat() else panY
        lastDragAt = System.currentTimeMillis()
        focusX = x; focusY = y; focusAt = android.os.SystemClock.elapsedRealtime()
        postInvalidate()
        return true
    }

    // ---- THE LIVE MARKERS, WORKED OUT IN update(). Quests, avail, taxi and pois reach this view
    // only in a full map (updatePos merges position keys alone), so onDraw need not read them
    // out of the JSON and sort them again every frame. They are split into onDraw's draw passes
    // here, each list already sorted by y.
    /** [guess] = a "!" from the bundle's quest givers (not one the game listed to the player): off a capital's small map */
    private class Marker(val x: Double, val y: Double, val shape: Int, val qid: Int = 0, val guess: Boolean = false)
    private val mkMid = ArrayList<Marker>()     // objectives, "!" (plain and daily), flight, vignettes
    private val mkTurnIn = ArrayList<Marker>()  // "?"
    private var turnInPx = FloatArray(0)        // the "?" screen centres, refilled each frame

    // ---- AVAILABLE QUESTS (QuestStore explains where they come from now). The addon's
    // map.me says who is asking. `done` arrives only now and then (login, a turn-in, every 5
    // minutes), so the last list seen is kept, and saved, because the app can start long after
    // the addon last sent it. Without a known level nothing is drawn: a "!" on every giver in
    // the zone would be worse than none.
    private var me: QuestStore.Me? = null
    private var meSeen: JSONObject? = null         // the map.me object already absorbed
    private var meDone: Set<Int> = emptySet()
    private var meDoneLoaded = false
    private val mePrefs by lazy { context.getSharedPreferences("questme", Context.MODE_PRIVATE) }

    private fun absorbMe(m: JSONObject?) {
        val o = m?.optJSONObject("me") ?: return
        if (o === meSeen) return                    // update() runs every second on the same object
        meSeen = o
        if (!meDoneLoaded) {
            meDoneLoaded = true
            val saved = try { mePrefs.getString("done", "") ?: "" } catch (t: Throwable) { "" }
            // the saved list is ONE character's (the slot is not per character): it is used only for the same race and class at the
            // same or a higher level. A NEW character (the user, 2026-10-07, a level-1 in a starting area: "The mini map os missing
            // quests that I haven't accepted yet") starts with none until the game's own list arrives (login, else Map.lua's 5 min),
            // instead of the old character's hiding its starting quests
            val who = try { mePrefs.getString("who", "") ?: "" } catch (t: Throwable) { "" }
            if (saved.isNotEmpty() && sameCharacter(who, o)) meDone = saved.split(',').mapNotNull { it.toIntOrNull() }.toHashSet()
        }
        o.optJSONArray("done")?.let { a ->
            val set = HashSet<Int>(a.length() * 2)
            for (i in 0 until a.length()) set.add(a.optInt(i))
            if (set != meDone) {
                meDone = set
                try { mePrefs.edit().putString("done", set.joinToString(",")).putString("who", whoOf(o)).apply() } catch (t: Throwable) {}
            }
        }
        val on = HashSet<Int>()
        o.optJSONArray("on")?.let { a -> for (i in 0 until a.length()) on.add(a.optInt(i)) }
        val lvl = o.optInt("lvl", 0)
        me = if (lvl > 0) QuestStore.Me(lvl, o.optInt("race", 0), o.optInt("cls", 0), on, meDone) else null
    }

    /** who a completed list belongs to: the character's GUID (map.me.g, Map.lua) when sent, else "level,race,class" */
    private fun whoOf(o: JSONObject): String {
        val g = o.optString("g", "")
        return if (g.isNotEmpty()) "g:$g" else "${o.optInt("lvl", 0)},${o.optInt("race", 0)},${o.optInt("cls", 0)}"
    }

    /** the saved list's owner [who] is the character in [o]: the same GUID; without one the same race and class, not a lower level
     *  (an unknown owner: no) */
    private fun sameCharacter(who: String, o: JSONObject): Boolean {
        val g = o.optString("g", "")
        if (who.startsWith("g:")) return g.isEmpty() || who.substring(2) == g
        if (g.isNotEmpty()) return false                               // an owner saved before the GUID: not known to be this one
        val w = who.split(',').mapNotNull { it.toIntOrNull() }
        if (w.size != 3) return false
        val lvl = o.optInt("lvl", 0); val race = o.optInt("race", 0); val cls = o.optInt("cls", 0)
        if (lvl <= 0 || race <= 0 || cls <= 0) return true            // the doc cannot tell: keep it
        return race == w[1] && cls == w[2] && lvl >= w[0]
    }

    /** "!" from QuestieDB (Quests' qdbavail) and from the harvester's recorded givers (map.qg), into mkMid */
    private fun deriveDbQuests(m: JSONObject) {
        val who = me ?: return
        val r = rect ?: return
        if (!r.valid) return
        // RECORDED givers first: the game itself listed their quests, so they replace QuestieDB's
        // guess for the same NPC, including an NPC that turned out to offer nothing.
        var recC: HashSet<Int>? = null
        var recO: HashSet<Int>? = null
        m.optJSONArray("qg")?.let { a ->
            for (i in 0 until a.length()) {
                val g = a.optJSONObject(i) ?: continue
                if (g.has("e")) (recC ?: HashSet<Int>().also { recC = it }).add(g.optInt("e"))
                if (g.has("o")) (recO ?: HashSet<Int>().also { recO = it }).add(g.optInt("o"))
                val x = g.optDouble("x", Double.NaN); val y = g.optDouble("y", Double.NaN)
                if (x.isNaN() || y.isNaN()) continue
                val offer = g.optJSONArray("offer") ?: continue
                val ids = IntArray(offer.length()) { offer.optInt(it) }
                if (QuestStore.offersRecorded(ids, who)) mkMid.add(Marker(x, y, SH_AVAIL))
            }
        }
        // QUESTIEDB'S AVAILABLE QUESTS for this map (Quests' qdbavail), when it has answered (the bundled Wrath givers left the app
        // with the art harvest, the user 2026-10-09); one "!" a giver, blue when every quest there is a repeatable / daily one; a
        // giver the game itself listed (map.qg, above) keeps its own mark
        qdbAvail[m.optInt("mapID", Int.MIN_VALUE)]?.let { av ->
            // per giver the strongest mark (the user's pick "Gold now + grey low", then "a dimmed yellow like it normally is"): gold
            // for a quest to take now, blue when all that is takeable now repeats, dimmed yellow when everything there is low-level
            val byGiver = LinkedHashMap<Long, Pair<QdbPins.AvailQ, Int>>()     // giver -> its spot, its strongest mark (3 / 2 / 1)
            for (q in av.q) {
                if (q.id in who.on || q.id in who.done) continue          // the app's lists may be newer than the add-on's answer
                if (!QdbPins.availShows(q.f, showTrivialQuests, showSoonQuests, showHolidayQuests)) continue
                if ((if (q.obj) recO else recC)?.contains(q.giver) == true) continue
                val key = if (q.giver != 0) (if (q.obj) -q.giver.toLong() else q.giver.toLong()) else q.id.toLong() shl 32
                val rank = when {
                    q.f and QdbPins.AV_TRIVIAL != 0 -> 1
                    q.f and QdbPins.AV_REPEAT != 0 -> 2
                    else -> 3
                }
                val was = byGiver[key]
                byGiver[key] = (was?.first ?: q) to max(was?.second ?: 0, rank)
            }
            for ((q, rank) in byGiver.values) mkMid.add(Marker(q.x, q.y,
                when (rank) { 3 -> SH_AVAIL; 2 -> SH_AVAIL_DAILY; else -> SH_AVAIL_LOW }, guess = true))
        }
    }

    private fun deriveMarkers(m: JSONObject?) {
        mkMid.clear(); mkTurnIn.clear()
        absorbMe(m)
        if (m != null) deriveDbQuests(m)
        if (m != null) {
            m.optJSONArray("quests")?.let { a ->
                for (i in 0 until a.length()) {
                    val q = a.optJSONObject(i) ?: continue
                    if (!shownQuest(q.optInt("questID", 0))) continue   // untracked: no dot, no "?"
                    if (questXY(q, qxy) == 0) continue
                    if (q.optBoolean("done", false)) mkTurnIn.add(Marker(qxy[0], qxy[1], SH_TURNIN, q.optInt("questID", 0)))
                    else mkMid.add(Marker(qxy[0], qxy[1], SH_OBJECTIVE, q.optInt("questID", 0)))
                }
            }
            // Quests AVAILABLE to pick up on this map, at the giver (addon: GetAvailableQuestLines,
            // trivial/grey ones already dropped). Not framed by the auto-zoom: they are offers,
            // not anything the player has agreed to go and do.
            m.optJSONArray("avail")?.let { a ->
                for (i in 0 until a.length()) {
                    val q = a.optJSONObject(i) ?: continue
                    val x = q.optDouble("x", Double.NaN); val y = q.optDouble("y", Double.NaN)
                    if (x.isNaN() || y.isNaN()) continue
                    mkMid.add(Marker(x, y,
                        if (q.optBoolean("daily", false)) SH_AVAIL_DAILY else SH_AVAIL))
                }
            }
            m.optJSONArray("taxi")?.let { a ->
                for (i in 0 until a.length()) {
                    val t = a.optJSONObject(i) ?: continue
                    val x = t.optDouble("x", Double.NaN); val y = t.optDouble("y", Double.NaN)
                    if (x.isNaN() || y.isNaN()) continue
                    mkMid.add(Marker(x, y, SH_FLIGHT))
                }
            }
            m.optJSONArray("pois")?.let { a ->
                for (i in 0 until a.length()) {
                    val t = a.optJSONObject(i) ?: continue
                    val x = t.optDouble("x", Double.NaN); val y = t.optDouble("y", Double.NaN)
                    if (x.isNaN() || y.isNaN()) continue
                    mkMid.add(Marker(x, y, SH_VIGNETTE))
                }
            }
        }
        mkMid.sortBy { it.y }
        mkTurnIn.sortBy { it.y }
        if (turnInPx.size < mkTurnIn.size * 2) turnInPx = FloatArray(mkTurnIn.size * 2)
    }

    /** the game's type (UI art, fonts step). Set where the paints are USED: the kit can reach the
     *  device after this view is built, and a paint keeps the font it was given. */
    private fun fonts() {
        UiKit.asTitle(pTitle)
        UiKit.asBody(pSmall); UiKit.asNumber(pDist)
        // a label on the map has its halo for an edge, so no shadow on top of it
        UiKit.asBody(pLabel, shadow = false); UiKit.asBody(pHalo, shadow = false)
        UiKit.asBody(pLabelS, shadow = false); UiKit.asBody(pHaloS, shadow = false)
    }

    override fun onDraw(c: Canvas) {
        fonts()
        val w = width.toFloat(); val h = height.toFloat()
        // the ground as a rect, not drawColor: while Home's card flies its parents do not clip, and drawColor fills the clip
        pFill.color = cInk
        c.drawRect(0f, 0f, w, h, pFill)
        if (morphOn && android.os.SystemClock.elapsedRealtime() - morphLastAt > MORPH_STALE_MS) morphEnd()
        // one value for the whole frame. The second term is a TEST SWITCH, read once a second:
        // `adb shell setprop log.tag.WCCOMPACT DEBUG` draws the Map room in compact mode, so the
        // mode can be looked at on the device before Home hosts it (`... INFO` turns it off).
        // While Home's open / close runs ([morph]) the FULL map's parts draw, whichever way it goes, faded by [mf].
        val compact = (this.compact && !morphOn) || compactForced()
        // a CAPITAL's small map is lean (the user's pick 2026-10-07, QdbPins.cityLeanKeeps): unmet guesses only for the city
        // essentials, no guessed "!"; what the player has met and the game's own live marks stay
        val cityLean = compact && WorldMapModel.CITY_ZONE.containsKey(map?.optInt("mapID", 0) ?: 0)
        val mf = morphFull()

        // never during Home's open / close (Home's showHeader follows its compact, which flips as a close begins)
        val header = !compact && showHeader && !morphOn
        val headerH = if (header) 72f else 0f
        if (header) {
            pFill.color = cPanel
            c.drawRect(0f, 0f, w, 72f, pFill)
            pFill.color = cBronze
            c.drawRect(0f, 71f, w, 72f, pFill)
        }

        val m = map
        if (m == null || !m.optBoolean("ok", false)) {
            if (compact) c.drawText("Waiting for the game", 12f, 30f, pSmall)
            else c.drawText("Waiting for the game", 24f, if (header) 48f else 56f, pTitle)
            return
        }

        if (header) {
            val zone = m.optString("zone", "Unknown")
            c.drawText(zone, 24f, 48f, pTitle)
            val sub = m.optString("subzone", "")
            if (sub.isNotEmpty() && sub != zone) {
                c.drawText(sub, 24f + pTitle.measureText(zone) + 18f, 48f, pSmall)
            }
        }

        // Load this zone's terrain art. Keyed on uiMapID, so walking Elwynn -> Stormwind swaps
        // the picture on the same frame the markers swap.

        // Draw from the PLAYBACK timeline when there is one: the arrow then moves at the
        // screen's rate rather than the packet's. Falls back to the raw payload whenever the
        // stream is stale or an older addon is loaded.
        val sm = smoothPos()
        val px = if (sm != null) sm[0].toDouble() else m.optDouble("x", Double.NaN)
        val py = if (sm != null) sm[1].toDouble() else m.optDouble("y", Double.NaN)
        if (probeOn()) android.util.Log.d(PROBE_TAG, "d ${System.currentTimeMillis()} $px $py")
        if (header) {
            val coord = if (px.isNaN()) "--" else String.format("%.1f, %.1f", px * 100, py * 100)
            pSmall.color = cText
            c.drawText(coord, w - headerRightInset - pSmall.measureText(coord) - 22f, 46f, pSmall)
            if (staleMs > 5000) {
                val s = "${staleMs / 1000}s old"
                pSmall.color = Color.parseColor("#D99A4E")
                c.drawText(s, w - headerRightInset - pSmall.measureText(s) - 22f, 68f, pSmall)
            }
        }
        pSmall.color = cDim

        // ---- the map field: the zone's 0..1 space, on the left.
        // The field takes the ART's aspect (every WoW zone map is 1002x668, i.e. 3:2). It used
        // to be square, which was harmless when the field was empty but would squash the real
        // map by 1.5x vertically.
        val listW = 0f              // the NEARBY/SERVICES column was removed; the map takes it all
        val pad = 22f
        val availW = w - listW - pad * 2
        val availH = h - headerH - pad * 2 - 26f
        val aspect = 1002f / 668f           // the zone maps' shape (both modes fill their view below anyway)
        var fw = availW
        var fh = fw / aspect
        if (fh > availH) { fh = availH; fw = fh * aspect }
        // Both modes FILL their view now; the projection below gives x and y their own extents, so
        // nothing is squashed (user 2026-09-29 17:30: "the map should fill the whole area, theres a
        // lot of empty space on the edges" -- the Map room used to keep the art's 3:2 inside pads).
        if (compact) { fw = w; fh = h } else { fw = w; fh = h - headerH }
        val left = 0f
        val top = if (compact) 0f else headerH
        val side = fw                       // button column + filter box still key off the width

        pFill.color = cField
        c.drawRect(left, top, left + fw, top + fh, pFill)
        // not during Home's open / close (it would pop in and out with the glide)
        if (!compact && !header && !morphOn && staleMs > 5000) staleNote = "${staleMs / 1000}s old" else staleNote = null

        if (!compact) {                     // the Map room's gestures measure against ITS field
            lastSide = side
            lastFieldW = fw
            lastFieldH = fh
            lastFieldTop = top
        }
        // View centre in map space: the player when following (minimap behaviour), else the pan.
        //
        // AT ZOOM 1 THE CENTRE IS ALWAYS THE ZONE, never the player. The class contract is
        // "zoom 1 = whole zone", and centring on the player instead pushed the map off the
        // field by however far the player was from the middle -- with an empty field that only
        // shifted some dots, but with real art it means the zone is never once shown whole.
        // THE GLIDE HOME. Eased from where the view sat when you stopped dragging toward the
        // LIVE player position -- not toward a snapshot of it, so if you keep walking the view
        // chases you instead of landing where you used to be. Cubic ease-out: quick off the
        // mark, soft on arrival, which reads as the map catching up rather than snapping.
        if (!compact) recentreFrom?.let { from ->
            val t = (System.currentTimeMillis() - recentreStart).toFloat() / RECENTRE_MS
            if (t >= 1f || px.isNaN() || py.isNaN()) {
                recentreFrom = null
                followPlayer = true
                if (!focusRestoreZoom.isNaN()) {
                    // home from a focus: give back the zoom the player had. With the auto-framer
                    // on, it takes over from here instead, starting at the current zoom.
                    if (autoZoom) autoZoomCur = zoom
                    else { zoomEase = focusRestoreZoom; lastZoomEaseAt = 0L }
                    focusRestoreZoom = Float.NaN
                }
            } else {
                val inv = 1f - t
                val e = 1f - inv * inv * inv
                panX = from[0] + (px.toFloat() - from[0]) * e
                panY = from[1] + (py.toFloat() - from[1]) * e
                postInvalidateOnAnimation()
            }
        }

        // ---- AUTO-FRAMING. Decide what the view should CONTAIN, then solve for centre+zoom.
        if (!compact) stepFixedZoom()
        val frame = if (!compact && autoZoom && followPlayer && recentreFrom == null)
            computeFrame(m, px, py) else null
        if (frame != null) {
            // Ease toward the target rather than snapping. Exponential smoothing, not a timed
            // tween, because the target moves continuously as the player walks -- a tween would
            // restart every frame and never arrive.
            //
            // The rate is PER SECOND, not per frame. It used to be a flat 0.14 per draw, which
            // was tuned when the view only redrew when a packet arrived, about ten times a
            // second. Now it redraws at 120, and a flat per-frame constant would converge
            // twelve times faster -- the glide would become a snap, silently, purely because
            // the frame rate went up.
            val nowMs = android.os.SystemClock.elapsedRealtime()
            val fdt = if (lastCamAt == 0L) 0.1f else ((nowMs - lastCamAt) / 1000f).coerceIn(0f, 0.25f)
            lastCamAt = nowMs
            val k = 1f - exp(-fdt / CAM_TAU)
            val pxF = px.toFloat()
            val pyF = py.toFloat()
            val ax = frame[3]
            val ay = frame[4]
            if (camAncX.isNaN()) {
                // first frame, or back from a manual pan: start from what is on screen
                camResX = lastViewCx - ax * pxF
                camResY = lastViewCy - ay * pyF
            } else if (ax != camAncX || ay != camAncY) {
                // re-base so a change of anchor does not move the camera by itself
                camResX += (camAncX - ax) * camLastPx
                camResY += (camAncY - ay) * camLastPy
            }
            camAncX = ax; camAncY = ay
            camLastPx = pxF; camLastPy = pyF
            camResX += (frame[0] - ax * pxF - camResX) * k
            camResY += (frame[1] - ay * pyF - camResY) * k
            autoCx = ax * pxF + camResX
            autoCy = ay * pyF + camResY
            autoZoomCur += (frame[2] - autoZoomCur) * k
            if (abs(frame[0] - autoCx) > 1e-4f || abs(frame[1] - autoCy) > 1e-4f ||
                abs(frame[2] - autoZoomCur) > 0.01f) postInvalidateOnAnimation()
            zoom = autoZoomCur.coerceIn(1f, ZOOM_MAX)
            panX = autoCx; panY = autoCy
        } else if (!compact) {
            camAncX = Float.NaN
        }

        val zoneView = !compact && zoom <= 1.001f
        val cxMap = if (compact) (if (px.isNaN()) 0.5f else px.toFloat())
                    else if (zoneView) 0.5f else if (frame != null) autoCx
                    else if (followPlayer && !px.isNaN()) px.toFloat() else panX
        val cyMap = if (compact) (if (py.isNaN()) 0.5f else py.toFloat())
                    else if (zoneView) 0.5f else if (frame != null) autoCy
                    else if (followPlayer && !py.isNaN()) py.toFloat() else panY
        if (!compact) { lastViewCx = cxMap; lastViewCy = cyMap }
        // The Map room's field has the art's aspect, so one half-extent serves both axes. The
        // compact field has whatever shape the host gives it, so x and y get their own: the
        // width shows minimapYd yards (the indoor width inside a building, if that is closer),
        // and the height shows as many yards as the same pixels-per-yard fits.
        var halfX = 0.5f / zoom
        var halfY = halfX
        if (!compact && fw > 0f) {
            // the room's field is no longer the art's 3:2: the height shows as many yards as the
            // width's pixels-per-yard fits
            val rc = rect
            halfY = if (rc != null && rc.valid) halfX * rc.width.toFloat() / rc.height.toFloat() * (fh / fw)
                    else halfX * aspect * (fh / fw)
            lastHalfX = halfX; lastHalfY = halfY
        }
        if (compact) {
            val rc = rect
            if (rc != null && rc.valid && fw > 0f) {
                val yd = compactYd().toFloat()
                halfX = (yd / 2f / rc.width.toFloat()).coerceIn(0.5f / ZOOM_MAX / 4f, 0.5f)
                halfY = halfX * rc.width.toFloat() / rc.height.toFloat() * (fh / fw)
            } else {
                halfX = 0.5f / 8f
                halfY = if (fw > 0f) halfX * aspect * (fh / fw) else halfX
            }
        }
        val half = halfX
        // THE TRACKER covers the field's left side (drawTracker): keep the view's centre in the middle
        // of the part it leaves, so nothing under the list is lost -- inside the Goldshire inn the list
        // hid the red exit (seen on UI Art's emulator). Not at whole-zone view, which must show it all.
        val trackPx = if (!compact && showTracker && items.isNotEmpty() && !zoneView)
            min(TRACKER_W, fw * 0.34f) + BTN_GAP else 0f
        var cxView = cxMap - (trackPx / 2f) / fw * (2f * halfX)
        var cyView = cyMap
        // the small map: the player lower by half of what the host's card covers (compactCover), so its arrow has room above
        if (compact && fh > 0f) cyView -= compactDown() / fh * (2f * halfY)
        if (!compact) lastTrackPx = trackPx
        // HOME'S OPEN / CLOSE ([morph]): the glide's projection stands in for the mode's own. The scale goes in log space and
        // the player's spot on a straight line (both in Home's px) from what was on screen at the start to the other mode's
        // framing at the slot's FINAL size; this frame's box only says how much of the map shows around them.
        if (morphOn && morphOk && !px.isNaN() && !py.isNaN() && fw > 0f && fh > 0f) {
            val rc = rect
            val okT = if (morphOpening) fullMapping(m, px, py, morphTo, morphMap) else compactMapping(morphTo, morphMap)
            if (okT && rc != null && rc.valid) {
                val k = morphK
                val s = exp(ln(morphS0) + (ln(morphMap[2]) - ln(morphS0)) * k)
                val sY = s * rc.height.toFloat() / rc.width.toFloat()
                val plx = morphP0x + (morphMap[0] - morphP0x) * k - hostX()
                val ply = morphP0y + (morphMap[1] - morphP0y) * k - hostY()
                halfX = fw / (2f * s); halfY = fh / (2f * sY)
                cxView = px.toFloat() - (plx - left) / s + halfX
                cyView = py.toFloat() - (ply - top) / sY + halfY
            }
        }
        fun sx(v: Double) = (left + ((v.toFloat() - (cxView - halfX)) / (halfX * 2f)) * fw)
        fun sy(v: Double) = (top + ((v.toFloat() - (cyView - halfY)) / (halfY * 2f)) * fh)
        // what this frame drew, for the next glide's start (Home's px)
        drawnS = if (halfX > 0f) fw / (2f * halfX) else 0f
        drawnMx = px; drawnMy = py
        if (!px.isNaN() && !py.isNaN()) { drawnPx = hostX() + sx(px); drawnPy = hostY() + sy(py) }
        else { drawnPx = Float.NaN; drawnPy = Float.NaN }
        drawnHints = hintsOn()
        fun onField(fx: Float, fy: Float) =
            fx >= left - 40f && fx <= left + fw + 40f && fy >= top - 20f && fy <= top + fh + 20f

        // ---- THE TERRAIN: the minimap tiles, drawn through the SAME projection as the markers (pan and zoom move tiles and
        // markers by one transform). A small copy of each tile at any width (no zone picture underneath any more: phase 1 of the
        // art harvest), the full tile over it while the view is close (user 2026-09-29 14:58: the shrunk picture was the blur).
        // World: x north, y west; an ADT's west edge is y = (32 - col) * 533.333, its north edge x = (32 - row) * 533.333.
        // No tiles on the device yet (the harvest still building), or a continent's worth on screen: the old grid.
        val rT = rect
        var terrain = false
        if (rT != null && rT.valid && MinimapTiles.has(rT.continent)) {
            val adt = MinimapTiles.ADT
            val wyW = rT.left - (cxView - halfX) * rT.width; val wyE = rT.left - (cxView + halfX) * rT.width
            val wxN = rT.top - (cyView - halfY) * rT.height; val wxS = rT.top - (cyView + halfY) * rT.height
            val c0 = floor(32 - wyW / adt).toInt(); val c1 = floor(32 - wyE / adt).toInt()
            val r0 = floor(32 - wxN / adt).toInt(); val r1 = floor(32 - wxS / adt).toInt()
            val n = (c1 - c0 + 1) * (r1 - r0 + 1)
            if (n in 1..SMALL_TILES_MAX) {
                terrain = true
                val sample = if (n <= QUARTER_TILES_MAX) 4 else 8
                val full = 2f * halfX * rT.width.toFloat() <= TILE_MAX_YD
                c.save()
                c.clipRect(left, top, left + fw, top + fh)
                for (row in r0..r1) for (col in c0..c1) {
                    // half a pixel of overlap so filtered neighbours never show a hairline between them
                    tileDst.set(sx(rT.nx((32 - col) * adt)) - 0.5f, sy(rT.ny((32 - row) * adt)) - 0.5f,
                                sx(rT.nx((31 - col) * adt)) + 0.5f, sy(rT.ny((31 - row) * adt)) + 0.5f)
                    MinimapTiles.get(rT.continent, col, row, sample) { invalidate() }?.let { c.drawBitmap(it, null, tileDst, pArt) }
                    if (full) MinimapTiles.get(rT.continent, col, row) { invalidate() }?.let { c.drawBitmap(it, null, tileDst, pArt) }
                }
                c.restore()
            }
        }
        if (!terrain) {
            // no tiles for this map (yet): keep the old grid so the field is not a blank slab
            for (i in 1..3) {
                c.drawLine(left + fw * i / 4f, top, left + fw * i / 4f, top + fh, pGrid)
                c.drawLine(left, top + fh * i / 4f, left + fw, top + fh * i / 4f, pGrid)
            }
        }
        // the floorplan sits ON the terrain, under every marker -- same projection, so it
        // lines up with the world by construction rather than by a second transform
        val flPlace = placeHere(m)
        if (flPlace != null && rect != null) {
            val bl = floorBlend(flPlace)
            val lo = bl[0].toInt()
            val hiIdx = bl[1].toInt()
            val u = bl[2]
            // the storeys' plans first (asked for off the UI thread; null until decoded), the one you are on before the ghosts
            val bmLo = if (lo >= 0) planBitmap(flPlace.levels[lo].img) else null
            val bmHi = if (lo >= 0 && u > 0f) planBitmap(flPlace.levels[hiIdx].img) else null
            c.save()
            c.clipRect(left, top, left + fw, top + fh)
            // Indoors the frame is ~60 yd wide, and 0.96 px/yd terrain at 34x is a smear that
            // fights the plan for attention. Scrim it: the roof and the road outside still
            // give you your bearings, just quietly. No plan decoded yet: the outdoor map as it is.
            pScrim.color = 0xA0100C08.toInt()
            if (bmLo != null || bmHi != null) c.drawRect(left, top, left + fw, top + fh, pScrim)
            // ALWAYS DRAW THE WHOLE BUILDING. The storeys you are not on are stroked as
            // ghosts, cool below and warm above, so being on the wrong one is one glance from
            // being obvious instead of being a dead end.
            for (i in flPlace.levels.indices) {
                if (i == lo || (u > 0f && i == hiIdx)) continue
                // Only the storeys within reach. Ghosting all twelve floors of a city
                // district is unreadable AND costs a bitmap each; two either way is what a
                // person can actually relate to where they are standing.
                if (lo >= 0 && abs(i - lo) > 2) continue
                planBitmap(flPlace.levels[i].outline, half = true)?.let { ob ->
                    drawPlan(c, flPlace, flPlace.levels[i], ob, if (i < lo) 70 else 62, ::sx, ::sy)
                }
            }
            if (lo >= 0) {
                // unsure -> everything at 55%, which is how the map says so without words
                val k = if (floorUnsure()) 0.55f else 1f
                // the storey above / below still decoding: the one you are on stays whole until it lands
                val uLo = if (bmHi == null) 0f else u
                bmLo?.let { bm ->
                    drawPlan(c, flPlace, flPlace.levels[lo], bm, (255 * (1f - uLo) * k).toInt(), ::sx, ::sy)
                }
                bmHi?.let { bm ->
                    drawPlan(c, flPlace, flPlace.levels[hiIdx], bm, (255 * u * k).toInt(), ::sx, ::sy)
                }
                // the storey's exits and ups and downs, fading with the storey itself
                drawZones(c, flPlace, flPlace.levels[lo], (1f - u) * k, ::sx, ::sy)
                if (u > 0f) drawZones(c, flPlace, flPlace.levels[hiIdx], u * k, ::sx, ::sy)
            }
            c.restore()
        }

        if (compact) {
            spine.clear()
            if (compactZoomButtons) {
                // the Map room's own buttons, a little smaller, fading like the Map room's (user
                // 2026-09-29 17:00: "yes but make sure it fades like normal"); still tappable while faint
                val bs = min(BTN_SIZE, min(fw, fh) / 4.5f)
                val hs = max(bs, MIN_TOUCH_PX)            // touch box: never under 48 dp
                val g = BTN_GAP * 0.6f
                val x1 = left + fw - g; val y0 = top + g + buttonTopInset
                cBtnIn = RectF(x1 - hs, y0, x1, y0 + hs)
                cBtnOut = RectF(x1 - hs, y0 + hs, x1, y0 + 2 * hs)
                cBtnDrawIn.set(cBtnIn.centerX() - bs / 2, cBtnIn.centerY() - bs / 2, cBtnIn.centerX() + bs / 2, cBtnIn.centerY() + bs / 2)
                cBtnDrawOut.set(cBtnOut.centerX() - bs / 2, cBtnOut.centerY() - bs / 2, cBtnOut.centerX() + bs / 2, cBtnOut.centerY() + bs / 2)
                // drawn in the OVERLAY pass at the end of onDraw, above every map marker
            } else {
                cBtnIn = RectF(); cBtnOut = RectF()
            }
            if (trailLive()) postInvalidateOnAnimation()
        }
        if (!compact) {
        val lineA = pLine.alpha; val smallA = pSmall.alpha
        if (mf < 1f) { pLine.alpha = (lineA * mf).toInt(); pSmall.alpha = (smallA * mf).toInt() }
        c.drawRect(left, top, left + fw, top + fh, pLine)
        c.drawText("N", left + fw / 2f - 8f, top + 26f, pSmall)
        pLine.alpha = lineA; pSmall.alpha = smallA

        // THE BOTTOM-RIGHT GROUP (see ROOM_EDGE): minus in the corner, plus above it
        val box = MIN_TOUCH_PX
        val br = left + fw - ROOM_EDGE
        val bb = top + fh - ROOM_EDGE - buttonBottomInset - (if (hintsOn()) HINT_BAND else 0f)
        btnOut = RectF(br - box, bb - box, br, bb)
        btnIn = RectF(btnOut.left, btnOut.top - box, btnOut.right, btnOut.top)
        // THE FILTER in the TOP RIGHT corner, as in the other rooms (the user, 2026-10-07: "the filter buton to be there like in
        // other menus and open it with start"; UI Art's box), its key's glyph to its left (Select / View since 2026-10-09: Start
        // is the pause wheel from every room); the bottom-right one is gone
        btnFilter = RectF(left + fw - 84f, top + 16f, left + fw - 22f, top + 78f)
        centred(btnInDraw, btnIn, BTN_SIZE); centred(btnOutDraw, btnOut, BTN_SIZE); centred(btnFilterDraw, btnFilter, BTN_SIZE)
        // The recentre button is GONE -- walking recentres on its own now. btnHome is kept as
        // an empty rect so nothing can hit-test into it.
        btnHome = RectF()
        // NO FADE in the Map room (same message: "When clicking on the map dont make those buttons
        // fade anymore"); Home's small map keeps its fade (the user's 17:00 answer)
        // the buttons, the storey spine and the tracker are drawn in the OVERLAY pass at the end of
        // onDraw: the map's markers drawn after this point used to land ON TOP of them

        if (trailLive()) postInvalidateOnAnimation()

        // the scale label is drawn in the OVERLAY pass, above the pins (a quest "!" sat on it)
        }

        // ---- QUEST AREAS, BEFORE ANY ICON (deriveQuestAreas). Shading on the terrain, so it goes
        // under every icon on the map, service pins included, and it is clipped to the field.
        // The objective's gold dot is drawn with the markers, further down.
        if (qAreas.isNotEmpty() || qCircles.isNotEmpty()) {
            val fl = left; val ft = top; val fr = left + fw; val fb = top + fh
            c.save()
            c.clipRect(fl, ft, fr, fb)
            for (qa in qAreas) {
                val ax0 = sx(qa.minX.toDouble()); val ax1 = sx(qa.maxX.toDouble())
                val ay0 = sy(qa.minY.toDouble()); val ay1 = sy(qa.maxY.toDouble())
                if (ax1 < fl || ax0 > fr || ay1 < ft || ay0 > fb) continue
                qPath.reset()
                // a QuestieDB area is never narrower on screen than QdbAreas.FLOOR_PX, so it reads as an area, not a marker or a
                // dot (UI Art): its outline is scaled up about its middle when the zoom would draw it smaller
                val span = max(ax1 - ax0, ay1 - ay0)
                val k = if (qa.floored) QdbAreas.floorK(span) else 1f
                val mx = (ax0 + ax1) / 2f; val my = (ay0 + ay1) / 2f
                for (j in qa.xs.indices) {
                    var vx = sx(qa.xs[j].toDouble()); var vy = sy(qa.ys[j].toDouble())
                    if (k != 1f) { vx = mx + (vx - mx) * k; vy = my + (vy - my) * k }
                    if (j == 0) qPath.moveTo(vx, vy) else qPath.lineTo(vx, vy)
                }
                qPath.close()
                if (qa.focused) {
                    c.drawPath(qPath, pAreaFill); c.drawPath(qPath, if (qa.traced) pAreaRimDash else pAreaRim)
                } else c.drawPath(qPath, if (qa.traced) pAreaLineDash else pAreaLine)
            }
            val rr = rect
            for (qc in qCircles) {
                val ccx = sx(qc.x); val ccy = sy(qc.y)
                val rad = if (rr != null && rr.valid) abs(sx(qc.x + AREA_CIRCLE_YD / rr.width) - ccx) else 40f
                if (ccx + rad < fl || ccx - rad > fr || ccy + rad < ft || ccy - rad > fb) continue
                if (qc.focused) { c.drawCircle(ccx, ccy, rad, pAreaFill); c.drawCircle(ccx, ccy, rad, pAreaRim) }
                else c.drawCircle(ccx, ccy, rad, pAreaLine)
            }
            c.restore()
        }

        // ---- THE SERVICE LAYER, drawn UNDER the live markers.
        // JUST THE ICON, FLOATING. User ruling 2026-09-27: "the actual icon instead of disks. So
        // like just a floating anvil", at about twice the old 19 px. There is no disc, no ring
        // and no shadow behind a pin. None is needed: mocked at SVC_ICON_PX over the real art of
        // Elwynn grass, Dun Morogh snow and Stormwind stone, every cell of the strip stays
        // legible on its own, because the ObjectIcons cells carry their own dark outline and the
        // Interface\Icons cells carry their own square frame.
        // ONE LOOK FOR EVERY PIN. A bundled guess (3.3.5a world DB: right geography, but WoW:
        // Forever adds NPCs and reorganises city trainers, so it is only a hint) and a visited
        // one (the player has opened this NPC's frame) are drawn IDENTICALLY, on the user's call.
        // Provenance is not drawn at all any more. It survives in exactly two places: the ORDER
        // below, which decides who wins a crowded cell, and the de-duplication of an NPC that
        // was met somewhere other than where the bundle puts it (`mv`, in deriveServiceLayer).
        // NO NAMES ON THE MAP. Labelling these was a wall of overlapping text the moment the
        // zoom passed 2.2x -- Stormwind alone has 217 of them. The icon says what it is, which
        // is the question a glance asks; the name is not.
        // DECLUTTER. Goldshire stacks six vendors inside one icon's width and the result was a
        // pile, not information. One icon per (kind, screen cell): a second vendor in the same
        // spot tells the player nothing the first did not, and as they zoom in the cells
        // separate and the rest appear on their own. The cell grows WITH the icon: it was 18 px
        // for a 19 px icon and is SVC_CELL_PX (36) for a 38 px one, the same ratio. Two same-kind
        // icons collapse to one when their centres fall in the SAME 36 px grid cell, and that
        // needs their centres within 36 px of each other on each axis (so the 38 px icons would
        // overlap). It is necessary, not sufficient: this is a grid, not a spacing test, so two
        // centres a pixel apart either side of a cell edge land in different cells and both draw.
        // That is the price of an O(1) claim per pin on a layer that can hold 400 of them every
        // frame.
        resetClaims(left - 40f, top - 20f, fw + 80f, fh + 40f)     // onField's bounds

        // FLIGHT POINTS CLAIM FIRST. A live taxi node (drawn with the markers below, at the same
        // SVC_ICON_PX) and the flightmaster's service pin beside it are the same winged boot a few
        // yards apart: two boots for one flight point. So every on-field taxi node takes its
        // Ic.FLIGHT cell before any service pin is placed, and a flightmaster's boot in that cell
        // gives it up. The node wins because it is the game's own live data.
        for (i in 0 until mkMid.size) {
            val mk = mkMid[i]
            if (mk.shape != SH_FLIGHT) continue
            val cxp = sx(mk.x); val cyp = sy(mk.y)
            if (onField(cxp, cyp)) claim(kindOf("flightmaster"), cxp, cyp)
        }

        // WHAT THE PLAYER HAS SEEN GOES FIRST. Cells are claimed first-come, so the drawing order
        // decides who wins a crowded spot. Seed first used to let a bundled guess of the same
        // kind take the cell of a vendor the player had actually visited (harvest session
        // request). Now the order is: harvested rows the bundle lacks (svcX/svcY), then bundled
        // pins the player has confirmed, and only then the bundled guesses (seedDraw holds both,
        // in that order, less the `mv` skips). They all look the same, but which one survives
        // the declutter should still be the one the player has stood at.
        // ---- QUESTIEDB'S SPAWN SPOTS (onQdbNodes / onQdbPins) under the service pins: dots at zone zoom, the game's pictures close in
        run {
            val mid = m.optInt("mapID", Int.MIN_VALUE)
            val rr = rect
            val pxYd = if (rr != null && rr.valid) fw / (2f * halfX * rr.width.toFloat()) else 0f
            val sp = if (pxYd >= SPAWN_PIC_PX_PER_YD) SPAWN_PIC else SPAWN_DOT
            val tr = m.optJSONObject("track")
            // the minimap probe's LIVE herb / ore dots (Map.lua mp1): what the game's minimap shows now, a filter switch
            if (probeWanted and PROBE_ON != 0 && liveMap == mid && android.os.SystemClock.elapsedRealtime() - liveAt < LIVE_MS) {
                for (lv in live) { val gx = sx(lv.x); val gy = sy(lv.y); if (onField(gx, gy)) UiKit.spawnSpot(c, gx, gy, lv.kind, sp, glow = true) }
            }
            qdbNodes[mid]?.let { n ->
                fun layer(a: List<QdbPins.Node>, kind: UiKit.Spawn) {
                    for (q in a) { val gx = sx(q.x); val gy = sy(q.y); if (onField(gx, gy)) UiKit.spawnSpot(c, gx, gy, kind, sp, glow = true) }
                }
                if (tr?.optInt("h") == 1) layer(n.herb, UiKit.Spawn.HERB)
                if (tr?.optInt("m") == 1) layer(n.ore, UiKit.Spawn.ORE)
            }
            // chests: only the ones the probe SAW (SeenChests; b-ghost-chests), never QuestieDB's spawn spots
            if (showChests) for (ch in SeenChests.on(mid, android.os.SystemClock.elapsedRealtime())) {
                val gx = sx(ch.nx); val gy = sy(ch.ny)
                if (onField(gx, gy)) UiKit.spawnSpot(c, gx, gy, UiKit.Spawn.CHEST, sp, glow = tr?.optInt("t") == 1)
            }
            if (showRares) qdbPins[mid]?.let { p ->
                for (rare in p.rares) {
                    val elite = rare.rank == 2                      // cmangos ranks: 4 rare, 2 rare elite
                    for (q in rare.pts) {
                        val gx = sx(q[0]); val gy = sy(q[1])
                        if (onField(gx, gy)) UiKit.spawnSpot(c, gx, gy, if (elite) UiKit.Spawn.RARE_ELITE else UiKit.Spawn.RARE,
                            if (elite) RARE_ELITE_PX else RARE_PX)
                    }
                }
            }
        }
        var drawn = 0
        for (i in 0 until svcN) {
            val cxp = sx(svcX[i]); val cyp = sy(svcY[i])
            if (!onField(cxp, cyp)) continue
            val kind = svcIc[i]
            if (!claim(kind, cxp, cyp)) continue
            drawPin(c, kind, cxp, cyp)
            drawn++
        }
        for (i in 0 until seedDraw.size) {
            val pin = seedDraw[i]
            val cxp = sx(pin.nx); val cyp = sy(pin.ny)
            if (!onField(cxp, cyp)) continue
            if (drawn++ > 400) break            // hard cap so a city can never stall a frame
            val kind = seedKind[i]
            if (!claim(kind, cxp, cyp)) continue
            drawPin(c, kind, cxp, cyp)
        }

        // ---- THE CURSOR'S DOTTED LINE, under the live markers and the player's arrow (the agreed design), inside the field
        val route = if (!compact && !morphOn && !corpseKnown() && !px.isNaN() && !py.isNaN()) cursorRoute() else null
        if (route != null) {
            val fb = top + fh - (if (hintsOn()) HINT_BAND else 0f)
            val save = c.save()
            c.clipRect(left, top, left + fw, fb)
            val rr = rect
            val yd = if (rr != null && rr.valid) CursorRoute.yards(px, py, route.x, route.y, rr.width, rr.height) else 0
            drawCursorPath(c, cursor.sel, route, yd, sx(px), sy(py), sx(route.x), sy(route.y), left, top, left + fw, fb)
            c.restoreToCount(save)
        } else pathFor = 0

        // ---- live markers, as icons, in the game's own vocabulary so nothing needs learning:
        //   "!"  (yellow, 40 px)   a quest AVAILABLE to pick up, at the giver. Blue for a daily,
        //                          as the game draws it.
        //   "?"  (yellow, 40 px)   a quest READY TO TURN IN, at the turn-in point on this map
        //                          when the addon found one (tx, ty), else at the quest's own x, y.
        //   gold dot + blob        the objective of a quest still in progress: a 19 px dot in a
        //                          2.5 px dark rim. It used to wear the "!", which now means
        //                          "available", so it gets a plain dot.
        //   winged boot (38 px)    a flight point, the size of a service pin.
        // Floating, like the service icons: no dark disc behind "!" or "?" any more. The glyphs
        // carry their own dark outline and read on grass, snow and stone without one. They are
        // sized a touch above the 38 px service icons (QUEST_ICON_PX) because a glyph only fills
        // the middle of its cell, and live quest data is the map while services are reference.
        // No names and no leader lines: the whole label-nudging apparatus existed to stop names
        // colliding, and with the names gone there is nothing left to nudge.
        // IN PASSES. What they guarantee: no objective blob tints any icon, and nothing covers a
        // "?". Within pass 2 a nearer (larger-y) mark still overlaps a farther one.
        //   1. the objective blobs (above, before the service layer)
        //   2. objective dots (a TRACKED quest's as its numbered bubble), "!", flight boots and vignettes, sorted by y
        //   3. the "?", last of all, sorted by y. The game's rule is that a turn-in outranks an
        //      offer, and an NPC with both shows only the "?". So a "!" whose centre is within
        //      QUEST_ICON_PX/2 of an ON-FIELD "?" is taken to be the same NPC and is not drawn.
        //      Off-field "?" are left out of that test (NaN never compares), or an NPC just past
        //      the field edge would lose its "!" without its "?" being drawn either.
        val nT = mkTurnIn.size
        for (i in 0 until nT) {
            val tx = sx(mkTurnIn[i].x); val ty = sy(mkTurnIn[i].y)
            val on = onField(tx, ty)
            turnInPx[2 * i] = if (on) tx else Float.NaN; turnInPx[2 * i + 1] = if (on) ty else Float.NaN
        }
        val sameNpc2 = (QUEST_ICON_PX / 2f) * (QUEST_ICON_PX / 2f)
        bubbleN = 0                                    // this frame's numbered bubbles, for the tap test
        for (lp in 0..1) for (s in qdbSpots) {         // QuestieDB's objective guesses (deriveQdb), under the game's marks; leads last
            if (s.turnIn || s.lead != (lp == 1)) continue
            val gx = sx(s.x); val gy = sy(s.y)
            if (!onField(gx, gy)) continue
            drawQdbSpot(c, gx, gy, s)
            if (s.lead) addBubble(gx, gy, s.qid)
        }
        for (i in 0 until mkMid.size) {
            val mk = mkMid[i]
            if (cityLean && mk.guess) continue
            val cxp = sx(mk.x); val cyp = sy(mk.y)
            if (!onField(cxp, cyp)) continue
            when (mk.shape) {
                SH_FLIGHT -> drawIcon(c, Ic.FLIGHT, cxp, cyp, SVC_ICON_PX)
                SH_VIGNETTE -> drawIcon(c, Ic.VIGNETTE, cxp, cyp, 22f)
                SH_AVAIL, SH_AVAIL_DAILY, SH_AVAIL_LOW -> {
                    var underQ = false
                    for (j in 0 until nT) {
                        val dx = turnInPx[2 * j] - cxp; val dy = turnInPx[2 * j + 1] - cyp
                        if (dx * dx + dy * dy <= sameNpc2) { underQ = true; break }
                    }
                    if (!underQ) drawIcon(c, Ic.QUEST, cxp, cyp, QUEST_ICON_PX,
                        when (mk.shape) { SH_AVAIL_DAILY -> pDaily; SH_AVAIL_LOW -> pTrivial; else -> pArt })
                }
                else -> {
                    val no = trackedNo[mk.qid] ?: 0          // both maps (the user, 2026-10-02: the small map too)
                    if (no > 0) {
                        // A TRACKED quest's objective: the GAME's numbered quest marker (UI Art's UiKit.questDisc, the
                        // same art as Home's tracker), its number from the left list; the focused quest wears the
                        // game's yellow focus disc. A white ring expands while a tap waits for the game's answer.
                        val focused = mk.qid == shownFocus()
                        UiKit.questDisc(c, cxp, cyp, BUBBLE_ART, focus = focused, number = no)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (!morphOn && !focused && mk.qid == askedQid && now - askedAt < ASK_MS) {
                            val ph = ((now - askedAt) % 900L) / 900f
                            pRing.color = Color.argb((230 * (1f - ph)).toInt(), 255, 255, 255)
                            c.drawCircle(cxp, cyp, BUBBLE_ART * 0.32f + 6f + 10f * ph, pRing)
                            postInvalidateOnAnimation()
                        }
                        if (bubbleN * 2 + 2 > bubbleXY.size) { bubbleXY = bubbleXY.copyOf(bubbleXY.size * 2); bubbleQ = bubbleQ.copyOf(bubbleQ.size * 2) }
                        bubbleXY[2 * bubbleN] = cxp; bubbleXY[2 * bubbleN + 1] = cyp; bubbleQ[bubbleN] = mk.qid; bubbleN++
                    } else {
                        // THE CENTRE of an objective, as a gold dot: the real point inside the
                        // invented outline (its blob went down before the service layer). The dark
                        // rim is what keeps gold visible on sand and snow; the "!" it replaces had an
                        // outline of its own.
                        objDot(c, cxp, cyp, 1f)
                    }
                }
            }
        }
        // a GUESSED turn-in (QuestieDB) keeps pass 3's rule that a turn-in outranks an offer: over the "!" and the service pins,
        // under the game's own "?" (the emulator look, 2026-10-06: drawn in pass 2 it vanished under Northshire's pins)
        for (s in qdbSpots) {
            if (!s.turnIn) continue
            val gx = sx(s.x); val gy = sy(s.y)
            if (!onField(gx, gy)) continue
            drawQdbSpot(c, gx, gy, s)
            if (s.lead) addBubble(gx, gy, s.qid)
        }
        for (i in 0 until nT) {
            val cxp = turnInPx[2 * i]; val cyp = turnInPx[2 * i + 1]
            if (cxp.isNaN()) continue                  // off the field
            val qid = mkTurnIn[i].qid
            if (qid != 0 && trackedNo.containsKey(qid)) {      // both maps
                // A TRACKED quest ready to turn in keeps its BUBBLE, at the turn-in, with the game's "?" in place of its number
                // (the user, 2026-09-30: "On completed quests it should still show the bubbles and instead of a number it
                // should show a question mark"): tappable, ringed and framed like any other bubble
                UiKit.questDisc(c, cxp, cyp, BUBBLE_ART, state = UiKit.Quest.TURN_IN, focus = qid == shownFocus())
                if (bubbleN * 2 + 2 > bubbleXY.size) { bubbleXY = bubbleXY.copyOf(bubbleXY.size * 2); bubbleQ = bubbleQ.copyOf(bubbleQ.size * 2) }
                bubbleXY[2 * bubbleN] = cxp; bubbleXY[2 * bubbleN + 1] = cyp; bubbleQ[bubbleN] = qid; bubbleN++
            } else drawIcon(c, Ic.QUEST_DONE, cxp, cyp, QUEST_ICON_PX)
        }

        // ---- THE CORPSE's gravestone: drawn with the rim arrow below (the stone OR the arrow); here only when the player's own
        // position is unknown, which leaves the arrow nothing to point from
        if (corpseOnMap() && (px.isNaN() || py.isNaN())) {
            val gx = sx(corpseX); val gy = sy(corpseY)
            if (onField(gx, gy)) drawCorpse(c, gx, gy)
        }

        // ---- focus ring (Show on map): two expanding pulses, then gone
        val fAge = android.os.SystemClock.elapsedRealtime() - focusAt
        if (focusAt != 0L && fAge < FOCUS_RING_MS) {
            val fx = sx(focusX); val fy = sy(focusY)
            val u = (fAge % 1500L) / 1500f
            pFocus.color = Color.argb((255 * (1f - u)).toInt(), Color.red(cGold), Color.green(cGold), Color.blue(cGold))
            c.drawCircle(fx, fy, 16f + 34f * u, pFocus)
            postInvalidateOnAnimation()
        }

        // ---- the object beside the player: the game's cursor icon for it (see absorbSoft).
        // Kept SOFT_MIN_PX off the player's arrow so the arrow never hides it.
        soft?.let { so ->
            val fade = if (so.goneAt == 0L) 1f
                else 1f - (android.os.SystemClock.elapsedRealtime() - so.goneAt).toFloat() / SOFT_FADE_MS
            if (fade <= 0f || so.mapId != m.optInt("mapID", Int.MIN_VALUE)) {
                soft = null
            } else {
                var mx = sx(so.x); var my = sy(so.y)
                if (!px.isNaN() && !py.isNaN()) {
                    val ppx = sx(px); val ppy = sy(py)
                    val dx = mx - ppx; val dy = my - ppy
                    val d = hypot(dx, dy)
                    if (d < SOFT_MIN_PX) {
                        val f = m.optDouble("facing", 0.0)
                        val ux = if (d > 0.5f) dx / d else -sin(f).toFloat()
                        val uy = if (d > 0.5f) dy / d else -cos(f).toFloat()
                        mx = ppx + ux * SOFT_MIN_PX; my = ppy + uy * SOFT_MIN_PX
                    }
                }
                if (onField(mx, my)) {
                    pSoft.alpha = (255 * fade.coerceIn(0f, 1f)).toInt()
                    drawIcon(c, so.icon, mx, my, SOFT_ICON_PX, pSoft)
                }
                if (so.goneAt != 0L) postInvalidateOnAnimation()
            }
        }

        // ---- the focused quest's arrow on the rim: the game's own minimap quest arrow, where the
        // line from the player to the target leaves the field, pointing along it. Only while the
        // target is off the field, as on the game's minimap; on the field its own mark shows.
        // while a ghost with a corpse known it points at the CORPSE instead (the user's answer, 2026-10-01: "The corpse only")
        val toCorpse = corpseKnown()
        val rimTX = if (toCorpse) corpseX else focusTX
        val rimTY = if (toCorpse) corpseY else focusTY
        if (!rimTX.isNaN() && !px.isNaN() && !py.isNaN()) {
            val tx = sx(rimTX); val ty = sy(rimTY)
            val inset = RIM_R + 6f
            val l0 = left + inset; val r0 = left + fw - inset
            // the top rule: on the small map the whole marker stays below the host's name bar
            val rimRule = if (compactTop() > 0f && (compact || morphOn)) (compactTop() + RIM_R) * (if (compact) 1f else 1f - mf) else 0f
            val t0 = top + max(inset, rimRule)
            val b0 = top + fh - inset - (if (hintsOn()) HINT_BAND else 0f)
            // THE TARGET'S OWN MARK OR THE ARROW, never both: the arrow only while the target is off what the map shows (the
            // field below the host's name bar and above the controls line), as the game's minimap does. UI Art, b41: zoomed
            // out, the focused quest's bubble sat in the arrow's band at the edge and the arrow was drawn right on top of it;
            // the corpse had shown half a stone with its arrow beside it the same way
            val nameBar = if (compactTop() > 0f && (compact || morphOn)) compactTop() * (if (compact) 1f else 1f - mf) else 0f
            val seen = tx >= left && tx <= left + fw && ty >= top + nameBar &&
                ty <= top + fh - (if (hintsOn()) HINT_BAND else 0f)
            if (toCorpse && seen) drawCorpse(c, tx, ty)
            if (!toCorpse && seen && flyStep) drawFlyStep(c, tx, ty)
            if (r0 > l0 && b0 > t0 && !seen) {
                val ox = sx(px).coerceIn(l0, r0); val oy = sy(py).coerceIn(t0, b0)
                val dx = tx - ox; val dy = ty - oy
                var k = 1f
                if (dx > 0f) k = min(k, (r0 - ox) / dx) else if (dx < 0f) k = min(k, (l0 - ox) / dx)
                if (dy > 0f) k = min(k, (b0 - oy) / dy) else if (dy < 0f) k = min(k, (t0 - oy) / dy)
                rimPt[0] = ox + dx * k; rimPt[1] = oy + dy * k
                // clear of the controls over the map: the host's on the small map, our own on the full one
                keepTmp.clear()
                if (compact) keepTmp.addAll(rimKeepOut)
                else {
                    keepTmp.add(RectF(btnIn.left - 26f, btnIn.top, btnOut.right, btnOut.bottom + 26f))   // + their shoulders' glyphs
                    keepTmp.add(RectF(btnFilter.left - 56f, btnFilter.top, btnFilter.right, btnFilter.bottom))   // + its key's glyph
                    keepTmp.addAll(rimKeepOut)          // the host's own over the open module too (Home's buffs / debuffs)
                    for (r in spine) keepTmp.add(r)
                    if (!trackerRect.isEmpty) keepTmp.add(trackerRect)
                }
                slideClear(rimPt, l0, t0, r0, b0, keepTmp)
                val ax = rimPt[0]; val ay = rimPt[1]
                // pointing from where it sits (it may have slid) at the target
                val deg = Math.toDegrees(atan2((tx - ax).toDouble(), -(ty - ay).toDouble())).toFloat()   // 0 = up
                c.save(); c.rotate(deg, ax, ay)
                val corpseArrow = if (toCorpse) UiKit.tex("mm_corpse_arrow") else null
                if (corpseArrow != null) {
                    // the game's corpse arrow: pointing up, turned about its centre (UI Art); its art fills only 17 x 14 of
                    // the 32 x 32 (centred), so it is drawn bigger to read as large as the quest arrow
                    val hA = CORPSE_ARROW_PX / 2f
                    corpseDst.set(ax - hA, ay - hA, ax + hA, ay + hA)
                    c.drawBitmap(corpseArrow, null, corpseDst, pCorpseArrow)
                } else drawIcon(c, Ic.QUEST_ARROW, ax, ay, RIM_ARROW_PX, if (toCorpse) pCorpseArrow else pArt)
                c.restore()
                // the step's icon (the user's pick): the winged boot just inside the arrow, upright, while the way goes by air
                if (!toCorpse && flyStep) {
                    val len = Math.hypot((tx - ax).toDouble(), (ty - ay).toDouble()).toFloat().coerceAtLeast(1f)
                    val bx = ax - (tx - ax) / len * FLY_ICON_OFF; val by = ay - (ty - ay) / len * FLY_ICON_OFF
                    // on a dark round backing (UI Art, mr16: on busy city art the bare boot read as a flight-master pin there)
                    pFlyBack.color = Color.argb(140, 0, 0, 0); c.drawCircle(bx, by, FLY_BACK_PX / 2f, pFlyBack)
                    drawIcon(c, Ic.FLIGHT, bx, by, FLY_ICON_PX)
                }
            }
        }

        // ---- the player: the game's own minimap arrow (UI Art's mm_player, 44 px, pointing up,
        // turned by the facing, no halo of ours: user 2026-09-29 "match the normal UI arrow").
        // Until the picture is on the device, the drawn arrow and halo below.
        val playerArt = UiKit.tex("mm_player")
        if (!px.isNaN() && !py.isNaN() && playerArt != null && playerArt.width > 0) {
            val cxp = sx(px); val cyp = sy(py)
            val facing = if (sm != null) sm[2].toDouble() else m.optDouble("facing", Double.NaN)
            val sc = PLAYER_ART_PX / max(playerArt.width, playerArt.height)
            val hw = playerArt.width * sc / 2f; val hh = playerArt.height * sc / 2f
            c.save()
            if (!facing.isNaN()) c.rotate(-Math.toDegrees(facing).toFloat(), cxp, cyp)
            iconDst.set(cxp - hw, cyp - hh, cxp + hw, cyp + hh)
            c.drawBitmap(playerArt, null, iconDst, pArt)
            c.restore()
        } else if (!px.isNaN() && !py.isNaN()) {
            val cxp = sx(px); val cyp = sy(py)
            val facing = if (sm != null) sm[2].toDouble() else m.optDouble("facing", Double.NaN)
            pFill.color = cPlayer
            if (!facing.isNaN()) {
                c.save(); c.rotate(-Math.toDegrees(facing).toFloat(), cxp, cyp)
                val path = Path().apply {
                    moveTo(cxp, cyp - 19f); lineTo(cxp - 12f, cyp + 13f)
                    lineTo(cxp, cyp + 5f); lineTo(cxp + 12f, cyp + 13f); close()
                }
                c.drawPath(path, pFill); c.restore()
            } else c.drawCircle(cxp, cyp, 9f, pFill)
            pFill.color = Color.argb(60, 170, 211, 114)
            c.drawCircle(cxp, cyp, 22f, pFill)
        }

        // THE RIGHT-HAND NEARBY/SERVICES LIST IS GONE, on the user's call: once the field
        // carries the real zone art, a third of the canvas spent on names repeats what the
        // labelled markers already say, and the map is the thing worth the space.
        // Everything it listed is still on the map itself, as the game's own icons.

        staleNote?.let { note ->
            pSmall.color = Color.parseColor("#D99A4E")
            val tw = pSmall.measureText(note)
            pFill.color = Color.argb(170, 0, 0, 0)
            val nx0 = left + fw / 2f - tw / 2f - 8f       // top centre: the top left is the filter's now
            c.drawRect(nx0, top + 8f, nx0 + tw + 16f, top + 40f, pFill)
            c.drawText(note, nx0 + 8f, top + 31f, pSmall)
            pSmall.color = cDim
        }

        // ---- the note (a refused focus tap): a short line at the top centre of the field, for NOTE_MS
        noteText?.let { t ->
            if (android.os.SystemClock.elapsedRealtime() - noteAt > NOTE_MS) noteText = null
            else if (!compact) {
                val np = UiKit.bodyPaint(26f, Color.parseColor("#FFD27A"))
                val tw = np.measureText(t)
                // centred in the part of the field the tracked-quest list leaves (it overlapped the list)
                val nx = left + trackPx + (fw - trackPx) / 2f - tw / 2f
                pFill.color = Color.argb(200, 8, 6, 4)
                c.drawRoundRect(nx - 16f, top + 44f, nx + tw + 16f, top + 84f, 10f, 10f, pFill)
                c.drawText(t, nx, top + 72f, np)
            }
        }

        // ---- THE OVERLAY PASS: the controls and the tracker above everything on the map
        if (compact) {
            if (compactZoomButtons) {
                val steps = Settings.ZOOM_STEPS_YD
                val yd = compactYd()
                drawButton(c, cBtnDrawIn, "+", yd > steps.first())
                drawButton(c, cBtnDrawOut, "-", yd < steps.last())
                if (buttonAlpha() > FADE_MIN) postInvalidateOnAnimation()     // the fade needs frames
            }
        } else {
            // Home's open / close: the full map's own parts fade in on the way up and out on the way down
            val fade = if (mf < 1f) c.saveLayerAlpha(left, top, left + fw, top + fh, (255 * mf).toInt()) else -1
            if (!hostZoomHidden) {
                drawButton(c, btnInDraw, "+", zoom < ZOOM_MAX, 1f)
                drawButton(c, btnOutDraw, "-", zoom > 1f, 1f)
                // their shoulders on their lower left corners (the user, 2026-10-16: "Put the L and R button glyphs onto the + and _
                // buttons instead of in the bottom with the glyph descriptions"): RB zooms in, LB out (MapCursor)
                if (hintsOn()) {
                    UiKit.pad(c, glyph("rb"), btnInDraw.left + 4f, btnInDraw.bottom - 4f, 44f)
                    UiKit.pad(c, glyph("lb"), btnOutDraw.left + 4f, btnOutDraw.bottom - 4f, 44f)
                }
            }
            // the game's own filter button (pressed while the panel is open), as the other rooms draw it, Select / View's glyph to its left
            val fb = UiKit.tex(if (filterOpen) "map_filter_btn_down" else "map_filter_btn")
            if (fb != null) c.drawBitmap(fb, null, btnFilter, pArt) else drawButton(c, btnFilterDraw, "≡", seed.isNotEmpty(), 1f)
            UiKit.pad(c, "gen_back", btnFilter.left - 28f, btnFilter.centerY(), 44f)
            // the storey spine stands on top of the plus button, its lowest storey nearest it
            val here = placeHere(m)
            if (here != null && here.levels.size > 1) {
                val n = here.levels.size
                drawSpine(c, here, btnIn.top - BTN_GAP - (n * SPINE_H + (n - 1) * SPINE_GAP), btnInDraw.left)
            } else spine.clear()
            // in yards across, the unit Settings uses, so the two can be compared at a glance
            val rz = rect
            val zlabel = if (zoom <= 1.02f) "zone"
                         else if (rz != null && rz.valid)
                             String.format("%.0f yd%s", rz.width / zoom, if (autoZoom) " auto" else "")
                         else String.format("%.1fx%s", zoom, if (autoZoom) " auto" else "")
            pSmall.color = cDim
            val lift = if (hintsOn()) HINT_BAND else 0f
            c.drawText(zlabel, left + 10f, top + fh - 12f - lift, pSmall)
            if (!followPlayer) c.drawText(
                if (recentreFrom != null) "recentring" else "panned - walk to recentre",
                left + 10f, top + fh - 34f - lift, pSmall)
            if (hintsOn()) drawPadHints(c, left, top, fw, fh)
            if (fade >= 0) c.restoreToCount(fade)
            // the tracked quests from the top left now that the filter has moved down; during Home's open / close the cards
            // other than the flying one fade on UI Art's timing ([morphRowsAlpha])
            val ra = morphRowsAlpha()
            val rows = if (ra < 1f) c.saveLayerAlpha(left, top, left + fw, top + fh, (255 * ra).toInt()) else -1
            // the quest cards hide while the filter is open (UI Art, after the user's rule for Home's auras list: "when opening
            // buffs or debuffs the quest should hide to give that menu that opens more space"); they come back when it closes
            if (showTracker && !filterOpen) drawTracker(c, left + BTN_GAP, top + BTN_GAP, top + fh - 64f - (if (hintsOn()) HINT_BAND else 0f),
                fly = morphOn && morphCard != null)
            else { trackerRect.setEmpty(); flyLand.setEmpty() }
            if (rows >= 0) c.restoreToCount(rows)
            // the player menu beside the list, from the top (the portrait's height)
            if (pmenu.open && !morphOn) {
                val lw = if (showTracker && items.isNotEmpty()) min(TRACKER_W, fw * 0.34f) + 16f else 0f
                drawPlayerMenu(c, left + BTN_GAP + lw, top + BTN_GAP, top + fh - 64f - (if (hintsOn()) HINT_BAND else 0f), fw - lw - 2f * BTN_GAP)
            } else { pmenuHits.clear(); pmenuBox.setEmpty() }
            if (panelId != 0 && !pmenu.open && !morphOn) {
                val lw = if (showTracker && items.isNotEmpty()) min(TRACKER_W, fw * 0.34f) + 16f else 0f
                drawPanel(c, left + BTN_GAP + lw, top + BTN_GAP, top + fh - 64f - (if (hintsOn()) HINT_BAND else 0f), fw - lw - 2f * BTN_GAP)
            } else { panelHits.clear(); panelBox.setEmpty() }
        }

        // the selected quest's bubble wears the round ring (UI Art: round things get round cursors), touch or controller;
        // its list row is highlighted in drawTracker
        if (!compact && !morphOn && cursor.shown && cursor.sel != 0) {
            for (i in 0 until bubbleN) if (bubbleQ[i] == cursor.sel)
                UiKit.focusRing(c, bubbleXY[2 * i], bubbleXY[2 * i + 1], BUBBLE_ART * 0.32f + 10f)
        }

        if (filterOpen && !compact) drawFilter(c, left, top, side)
        // last of all, above everything and outside the box: the focused quest's card in flight
        if (morphOn) drawFlight(c)
    }

    /**
     * The service filter: a pop-out over the map, open only while the player chooses. EVERYTHING IN
     * VIEW and FINGER-SIZED (user, 2026-09-28): three columns over the whole map field, each row a
     * full finger high with the game's check box, the pin's own icon, the name in full and the
     * count of that kind in THIS zone. It closes by the game's close button or a tap outside it.
     */
    private fun drawFilter(c: Canvas, left: Float, top: Float, side: Float) {
        val all = zoneRows()
        val r = rect
        val roles = PoiStore.ROLES
        val cols = 3
        val lines = (roles.size + SPAWN_SWITCHES + cols - 1) / cols
        val fieldW = if (lastFieldW > 0f) lastFieldW else side
        val fieldH = if (lastFieldH > 0f) lastFieldH else side
        val head = 88f
        // the panel opens UNDER the top-right filter button (UI Art), right-aligned with the field
        val top0 = if (!btnFilter.isEmpty) btnFilter.bottom + 6f else top
        val rowH = min(FILTER_ROW, (fieldH - (top0 - top) - head - 20f) / lines)
        filterBox.set(left, top0, left + fieldW, top0 + head + lines * rowH + 20f)
        UiKit.menu(c, filterBox, solid = true)                      // the game's menu box, solid over the quest list (the kit's rule)
        val title = UiKit.bodyPaint(34f, cGold)
        c.drawText("Show", filterBox.left + 28f, filterBox.top + 44f - (title.ascent() + title.descent()) / 2f, title)
        // no close button (UI Art: the game's own map filter has none): the pressed funnel says "open", and the funnel (a touch
        // outside the panel), B and Start close it

        // counts for THIS zone only: the same containment test the pins use
        // only what can be drawn: the NPCs seen (the user's "Only NPCs actually seen") and the opened ones
        val counts = HashMap<Int, Int>()
        val fm = map?.optInt("mapID", Int.MIN_VALUE) ?: Int.MIN_VALUE
        fun count(flags: Int) { for ((bit, _, _) in roles) if (flags and bit != 0) counts[bit] = (counts[bit] ?: 0) + 1 }
        if (all != null && r != null) for (p in all) {
            if (!r.contains(p.wx, p.wy) || !SeenNpcs.has(fm, p.entry)) continue
            count(p.flags)
        }
        map?.optJSONArray("svc")?.let { a -> for (k in 0 until a.length()) a.optJSONObject(k)?.let { count(it.optInt("f")) } }

        filterHits.clear()
        val cw = (filterBox.width() - 24f) / cols
        val name = UiKit.bodyPaint(28f)
        val num = UiKit.numberPaint(27f, cDim).apply { textAlign = Paint.Align.RIGHT }
        // ONE label size for the whole panel (UI Art: a single label shrunk on its own reads as a mistake): the largest, from 28,
        // at which the longest label fits beside a three-figure count
        val room = cw - 16f - num.measureText("000") - 18f - 156f
        val longest = (roles.map { it.second } + listOf("Chests", "Rares", "Live dots", "Auto-switch", "Sparkles"))
            .maxByOrNull { name.measureText(it) } ?: ""
        var labelSize = 28f
        while (name.measureText(longest) > room && labelSize > 18f) { labelSize -= 1f; name.textSize = labelSize }
        for ((i, role) in roles.withIndex()) {
            val (bit, label, _) = role
            val cx0 = filterBox.left + 12f + (i % cols) * cw
            val y0 = filterBox.top + head + (i / cols) * rowH
            val cy = y0 + rowH / 2f
            filterHits[bit] = RectF(cx0, y0, cx0 + cw, y0 + rowH)
            val on = mask and bit != 0
            if (i % cols > 0) { pFill.color = Theme.bronzeDim; c.drawRect(cx0 - 1f, cy - 38f, cx0, cy + 38f, pFill) }
            UiKit.check(c, cx0 + 44f, cy, 64f, on)
            drawPin(c, kindOf(NpcIcons.icon(NpcKind.key(bit, null), bit)), cx0 + 116f, cy, 56f)
            val n = (counts[bit] ?: 0).toString()
            c.drawText(n, cx0 + cw - 16f, cy - (num.ascent() + num.descent()) / 2f, num)
            name.textSize = labelSize
            name.color = if (on) roleColour(bit) else cDim
            c.drawText(label, cx0 + 156f, cy - 2f - (name.ascent() + name.descent()) / 2f, name)
        }
        // QUESTIEDB'S CHESTS AND RARES (the user, 2026-10-06: "Shown, with a filter switch" for each), two more cells of the grid:
        // the game's picture, the count of spots on THIS map. Herbs and ore have no switch: they follow the game's own tracking.
        val mid = map?.optInt("mapID", Int.MIN_VALUE) ?: Int.MIN_VALUE
        val spawnCells = listOf(
            Triple(FILTER_CHESTS, "Chests", SeenChests.on(mid, android.os.SystemClock.elapsedRealtime()).size),
            Triple(FILTER_RARES, "Rares", qdbPins[mid]?.rares?.size ?: 0),
            // the minimap probe's options (the user, 2026-10-12): the live dots, the Find Herbs / Minerals switch, sparkles
            Triple(FILTER_LIVE, "Live dots", if (liveMap == mid) live.size else 0),
            Triple(FILTER_SWITCH, "Auto-switch", 0),
            Triple(FILTER_SPARKLES, "Sparkles", 0))
        for ((j, cell) in spawnCells.withIndex()) {
            val (key, label, count) = cell
            val i = roles.size + j
            val cx0 = filterBox.left + 12f + (i % cols) * cw
            val y0 = filterBox.top + head + (i / cols) * rowH
            val cy = y0 + rowH / 2f
            filterHits[key] = RectF(cx0, y0, cx0 + cw, y0 + rowH)
            val on = when (key) {
                FILTER_CHESTS -> showChests
                FILTER_RARES -> showRares
                FILTER_LIVE -> probeWanted and PROBE_ON != 0
                FILTER_SWITCH -> probeWanted and PROBE_SWITCH != 0
                else -> probeWanted and PROBE_SPARKLES != 0
            }
            if (i % cols > 0) { pFill.color = Theme.bronzeDim; c.drawRect(cx0 - 1f, cy - 38f, cx0, cy + 38f, pFill) }
            UiKit.check(c, cx0 + 44f, cy, 64f, on)
            // the game's picture PLAIN, as the role pins are (UI Art: no live rim / glow, no ghost: the switch carries on / off)
            when (key) {
                FILTER_LIVE -> UiKit.spawnSpot(c, cx0 + 116f, cy, UiKit.Spawn.HERB, 56f)
                FILTER_SWITCH -> UiKit.spawnSpot(c, cx0 + 116f, cy, UiKit.Spawn.ORE, 56f)
                FILTER_SPARKLES -> drawIcon(c, Ic.SOFT_USE, cx0 + 116f, cy, 56f)
                else -> UiKit.tex(if (key == FILTER_CHESTS) "mm_chest" else "mm_rare")?.let { bmp ->
                    val sc = 56f / max(bmp.width, bmp.height)
                    val hw = bmp.width * sc / 2f; val hh = bmp.height * sc / 2f
                    iconDst.set(cx0 + 116f - hw, cy - hh, cx0 + 116f + hw, cy + hh)
                    c.drawBitmap(bmp, null, iconDst, pArt)
                }
            }
            val n = if (key == FILTER_SWITCH || key == FILTER_SPARKLES) "" else count.toString()
            c.drawText(n, cx0 + cw - 16f, cy - (num.ascent() + num.descent()) / 2f, num)
            name.textSize = labelSize
            name.color = if (on) cGold else cDim
            c.drawText(label, cx0 + 156f, cy - 2f - (name.ascent() + name.descent()) / 2f, name)
        }
    }

    private val FILTER_ROW = 111f            // 48 dp on this panel: the touch floor
    private val SPAWN_SWITCHES = 5           // Chests, Rares, the probe's three (after the roles)
    private val FILTER_CHESTS = -1           // their keys in filterHits, beside the role bits (all positive)
    private val FILTER_RARES = -2
    private val FILTER_LIVE = -3
    private val FILTER_SWITCH = -4
    private val FILTER_SPARKLES = -5

    // ---- THE MINIMAP PROBE (Map.lua mp1, "mmprobe"; the user, 2026-10-12): the names of the dots the game's minimap shows, in
    // yards from the player. A name that is one of this map's QuestieDB node kinds is a LIVE herb / ore / chest dot (drawn while the
    // "Live dots" switch is on); one that is a QuestieDB service NPC here is that NPC SEEN (SeenNpcs). Its options are the user's
    // (kept), sent to the add-on by the host ([onProbeOptions]: the pin lane's op digit 2) whenever the add-on says otherwise.
    private val PROBE_ON = 1
    private val PROBE_SWITCH = 2
    private val PROBE_SPARKLES = 4
    private val LIVE_MS = 30_000L
    private class Live(val x: Double, val y: Double, val kind: UiKit.Spawn)
    private val live = ArrayList<Live>()
    private var liveMap = Int.MIN_VALUE
    private var liveAt = 0L
    private val probePrefs by lazy { context.getSharedPreferences("mapprobe", Context.MODE_PRIVATE) }
    /** the user's probe options (bits: 1 the probe / live dots, 2 the Find Herbs / Minerals switch, 4 sparkles), kept */
    private var probeWanted: Int = -1
        get() { if (field < 0) field = runCatching { probePrefs.getInt("opts", PROBE_ON) }.getOrDefault(PROBE_ON); return field }
    /** the host sends the options to the add-on (WorldMapRoomView.sendProbeOptions) */
    var onProbeOptions: ((Int) -> Unit)? = null
    private var optsAskedAt = 0L
    private var optsAsked = -1

    private fun setProbeOption(bit: Int) {
        probeWanted = probeWanted xor bit
        runCatching { probePrefs.edit().putInt("opts", probeWanted).apply() }
        askProbeOptions(force = true)
    }

    private fun askProbeOptions(force: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && optsAsked == probeWanted && now - optsAskedAt < 15_000L) return
        optsAsked = probeWanted; optsAskedAt = now
        onProbeOptions?.invoke(probeWanted)
    }

    /** {"type":"mmprobe"}: the minimap's dots now (live nodes, seen NPCs) and the add-on's options */
    fun onMmProbe(d: JSONObject) {
        if (d.has("o") && d.optInt("o") != probeWanted) askProbeOptions(force = false)
        val m = map ?: return
        val r = rect ?: return
        if (!r.valid) return
        val mid = m.optInt("mapID", Int.MIN_VALUE)
        if (d.has("m") && d.optInt("m") != mid) return
        val wx = m.optDouble("wx", Double.NaN); val wy = m.optDouble("wy", Double.NaN)
        if (wx.isNaN() || wy.isNaN()) return
        val nodes = qdbNodes[mid]
        val rows = zoneRows()
        val now = android.os.SystemClock.elapsedRealtime()
        live.clear(); liveMap = mid; liveAt = now
        var changed = false
        val chests = ArrayList<DoubleArray>()
        val a = d.optJSONArray("d")
        if (a != null) for (i in 0 until a.length()) {
            val dot = a.optJSONObject(i) ?: continue
            val name = dot.optString("t", "")
            if (name.isEmpty()) continue
            val at = SeenNpcs.dotSpot(wx, wy, dot.optDouble("n", 0.0), dot.optDouble("w", 0.0), r.top, r.left, r.height, r.width)
            val nk = nodeKind(name, nodes) ?: if (name in SeenChests.NAMES) UiKit.Spawn.CHEST else null
            if (nk == UiKit.Spawn.CHEST) { chests.add(at); continue }         // a chest: SeenChests keeps it (drawn by the Chests switch)
            if (nk != null) { live.add(Live(at[0], at[1], nk)); continue }
            if (rows == null) continue
            val cands = rows.filter { it.name == name && it.entry > 0 }.map { Triple(it.entry, r.nx(it.wy), r.ny(it.wx)) }
            SeenNpcs.nearest(cands, at[0], at[1], r.width, r.height)?.let { e ->
                if (SeenNpcs.see(mid, e, at[0], at[1], 'p', System.currentTimeMillis(), r.width, r.height)) changed = true
            }
        }
        if (changed) { recomputeSeed(); saveSeenSoon() }
        // the chests it named, and the kept ones it should have named and did not (only while Find Treasure is on: else it cannot)
        val me = SeenNpcs.dotSpot(wx, wy, 0.0, 0.0, r.top, r.left, r.height, r.width)
        val radius = if (d.has("r")) d.optDouble("r", Double.NaN).takeIf { !it.isNaN() } else null
        SeenChests.probe(mid, chests, me[0], me[1], r.width, r.height, radius, m.optJSONObject("track")?.optInt("t") == 1,
            android.os.SystemClock.elapsedRealtime())
        invalidate()
    }

    /** a probed name -> its node kind on this map (QuestieDB's node kinds: which list carries its bit), or null */
    private fun nodeKind(name: String, n: QdbPins.Nodes?): UiKit.Spawn? {
        val i = n?.kinds?.indexOf(name) ?: -1
        if (n == null || i < 0 || i > 30) return null
        val bit = 1 shl i
        return when {
            n.herb.any { it.mask and bit != 0 } -> UiKit.Spawn.HERB
            n.ore.any { it.mask and bit != 0 } -> UiKit.Spawn.ORE
            n.chest.any { it.mask and bit != 0 } -> UiKit.Spawn.CHEST
            else -> UiKit.Spawn.HERB
        }
    }

    /** the game's soft target on a creature (the 10 Hz position document): a QuestieDB service NPC of this map is SEEN */
    private fun seeSoft(s: JSONObject, m: JSONObject, mapId: Int) {
        val e = s.optInt("e", 0)
        if (e <= 0 || e !in zoneEntries) return
        val r = rect ?: return
        if (!r.valid) return
        val x = m.optDouble("x", Double.NaN); val y = m.optDouble("y", Double.NaN)
        if (x.isNaN() || y.isNaN()) return
        if (SeenNpcs.see(mapId, e, x, y, 's', System.currentTimeMillis(), r.width, r.height)) { recomputeSeed(); saveSeenSoon() }
    }

    /** the creature ids of this map's QuestieDB service rows (a soft target outside them is not a service: not kept) */
    private val zoneEntries = HashSet<Int>()

    // the seen store's file: read once, written a few seconds after a change (world facts: one store for every character)
    private var seenLoaded = false
    private val seenFile by lazy { java.io.File(context.filesDir, "seen_npcs.json") }
    private val seenSave = Runnable {
        if (!SeenNpcs.dirty) return@Runnable
        val text = SeenNpcs.toJson()
        SeenNpcs.saved()
        Thread { runCatching { val tmp = java.io.File(seenFile.path + ".tmp"); tmp.writeText(text); tmp.renameTo(seenFile) } }.start()
    }
    private fun loadSeen() {
        if (seenLoaded) return
        seenLoaded = true
        runCatching { if (seenFile.isFile) SeenNpcs.load(seenFile.readText()) }
    }
    private fun saveSeenSoon() { removeCallbacks(seenSave); postDelayed(seenSave, 5_000L) }
}
