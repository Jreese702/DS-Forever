package com.jrees.wowcompanion

import kotlin.math.hypot

/**
 * THE LIVE MOBS (the live aggro layer; AGGRO_DOC_CONTRACT_v1.md, LIVE_AGGRO_LAYER_DESIGN.md 5.2 - 5.3): the aggro records as they
 * come, kept per plate. Thread-safe (every entry synchronized; the doc thread writes, the UI thread reads [snapshot]); time-stamped by
 * [clock] (ms, the app's elapsed clock).
 *
 * - A FULL record replaces the set: a slot in `m` is that plate (a new TRACK when the slot was empty, its identity changed or its age
 *   went back - the slot was freed and given again); a slot missing is its plate GONE (its disc fades over [GONE_FADE_MS]: the mob
 *   most likely still stands there, out of plate range or behind the camera); dd:1 is a death (gone at once, its spawn held dead).
 * - A SAME record keeps the last full set (seen now); one naming another q is ignored. q going backwards (a reload, a login): every
 *   slot new. No record for [STALE_MS]: the set counts as gone from its last sighting.
 * - An OFF record (inst / enc / pvp) clears everything until the next full one.
 * - Each plate's bracket history (a ring from each player spot, [RING_STEP_YD] apart or on a bracket change; [RING_KEEP] at most, none
 *   older than [RING_KEEP_MS]) places it ([LivePlace.place]), each spawn held by one plate at most.
 * - ABSENCE: a spawn of this map's QuestieDB list within the plates' range (cv.maxd, else [DEFAULT_PLATE_YD], less [ABSENT_MARGIN_YD])
 *   and in view (anywhere with offscreen plates on; else within the FOV of the facing while moving) with no plate that could be it
 *   for [ABSENT_MS] is ABSENT for [HOLD_MS] (QuestieDB has no respawn timers: a rule constant). Never in combat, with enemy plates off,
 *   or while a plate of unknown identity is up.
 * - The full record's pull events go to [AggroFit].
 */
class LiveMobs(private val clock: () -> Long) {
    companion object {
        const val GONE_FADE_MS = 15_000L
        const val STALE_MS = 7_000L          // forced fulls come every 5 s
        const val HOLD_MS = 5 * 60_000L
        const val ABSENT_MS = 2_000L
        const val DEFAULT_PLATE_YD = 20.0    // the classic default nameplate distance (design 3.2); cv.maxd when sent
        const val ABSENT_MARGIN_YD = 3.0
        const val RING_STEP_YD = 4.0
        const val RING_KEEP = 8
        const val RING_KEEP_MS = 30_000L
        const val MOVING_YD = 1.0            // the player moved this far since the last full record ...
        const val MOVING_MS = 1_500L         // ... within this: walking (the camera ~ the facing)
        const val NEAR_SPAWN_YD = 80.0       // spawns this near the player are looked at

        /** the app's one store (the doc thread feeds it, the map reads it) */
        val shared by lazy { LiveMobs { android.os.SystemClock.elapsedRealtime() } }
    }

    class Track(val id: Long, val slot: Int, var mob: AggroDoc.Mob, val firstMs: Long, var lastMs: Long) {
        val rings = ArrayList<LivePlace.Ring>()
        var placed: LivePlace.Placed? = null
        var goneMs: Long = -1L
    }

    private var nextId = 1L
    private var q = -1
    private var me: AggroDoc.Me? = null
    private var meAtMs = 0L
    private var comb = false
    private var lastMoveMs = Long.MIN_VALUE / 2
    private var lastFullMs = Long.MIN_VALUE / 2
    private var cov: AggroDoc.Coverage? = null
    var offWhy: String? = null; private set
    private val live = HashMap<Int, Track>()
    private val gone = ArrayList<Track>()
    // the spawns of [spawnMap] by npc, and their states
    private var spawnMap = 0
    private var byNpc: Map<Int, List<LivePlace.SpawnPt>> = emptyMap()
    private var all: List<LivePlace.SpawnPt> = emptyList()
    private val absentSince = HashMap<Int, Long>()
    private val absentUntil = HashMap<Int, Long>()
    private val deadUntil = HashMap<Int, Long>()
    /** the records read (for the log) */
    var fulls = 0; private set
    var sames = 0; private set

    @Synchronized fun onCoverage(c: AggroDoc.Coverage) { cov = c }

    /** this map's QuestieDB spawns in world yards (the map's own list; [map] its uiMap) */
    @Synchronized fun setSpawns(map: Int, spawns: List<LivePlace.SpawnPt>) {
        if (map == spawnMap && spawns === all) return
        spawnMap = map; all = spawns; byNpc = spawns.groupBy { it.npc }
        absentSince.clear(); absentUntil.clear(); deadUntil.clear()
        for (t in live.values) t.placed = null
    }
    @Synchronized fun spawnsFor(): Int = spawnMap

    @Synchronized fun onRecord(r: AggroDoc.Record, now: Long = clock()) {
        when (r) {
            is AggroDoc.Off -> { offWhy = r.why; live.clear(); gone.clear(); absentSince.clear(); absentUntil.clear(); deadUntil.clear(); me = null; q = -1 }
            is AggroDoc.Same -> if (r.q == q && offWhy == null) { sames++; lastFullMs = now; for (t in live.values) t.lastMs = now; absence(now) }
            is AggroDoc.Full -> full(r, now)
        }
    }

    private fun full(r: AggroDoc.Full, now: Long) {
        fulls++
        offWhy = null
        // q backwards: a reload or a login, every slot new
        if (r.q < q) { live.clear() }
        q = r.q
        val prev = me
        if (prev != null && hypot(r.me.x - prev.x, r.me.y - prev.y) >= MOVING_YD && now - meAtMs <= MOVING_MS * 2) lastMoveMs = now
        me = r.me; meAtMs = now; comb = r.comb; lastFullMs = now
        val moving = now - lastMoveMs <= MOVING_MS
        val seen = HashSet<Int>()
        for (m in r.m) {
            seen.add(m.s)
            var t = live[m.s]
            if (t != null && (!m.sameMobAs(t.mob) || (m.age != null && t.mob.age != null && m.age + 1.0 < t.mob.age!!))) {
                t.goneMs = now; gone.add(t); t = null
            }
            if (t == null) { t = Track(nextId++, m.s, m, now, now); live[m.s] = t }
            // the identity a plate read before combat stays on it (the contract keeps n into combat; a doc that drops it keeps ours)
            val keep = t.mob
            t.mob = if (m.n == null && keep.n != null) copyId(m, keep) else m
            t.lastMs = now
            ring(t, r.me, now)
            if (m.dd) {
                // a death: gone at once, its spawn dead for the hold
                t.placed?.spawn?.takeIf { t.placed?.sure == true }?.let { deadUntil[it.key] = now + HOLD_MS }
                live.remove(m.s)
            }
        }
        for (s in live.keys.toList()) if (s !in seen) { val t = live.remove(s)!!; t.goneMs = now; gone.add(t) }
        gone.removeAll { now - it.goneMs > GONE_FADE_MS }
        place(r.me, moving)
        absence(now)
        for (p in r.pl) AggroFit.add(p)
    }

    private fun copyId(m: AggroDoc.Mob, k: AggroDoc.Mob) = AggroDoc.Mob(m.s, k.n, m.ct ?: k.ct, m.lv, m.c, m.r, m.cb, m.om, m.a, m.b, m.rk, m.qi,
        m.dd, m.td, m.sx, m.cl, m.age, m.mk, m.br, m.bs)

    private fun ring(t: Track, me: AggroDoc.Me, now: Long) {
        val (a, b) = LivePlace.bracket(t.mob, cov?.maxd)
        val last = t.rings.lastOrNull()
        if (last == null || last.a != a || last.b != b || hypot(last.x - me.x, last.y - me.y) >= RING_STEP_YD)
            t.rings.add(LivePlace.Ring(me.x, me.y, a, b, now))
        else t.rings[t.rings.size - 1] = LivePlace.Ring(last.x, last.y, a, b, now)
        t.rings.removeAll { now - it.ms > RING_KEEP_MS }
        while (t.rings.size > RING_KEEP) t.rings.removeAt(0)
    }

    private fun near(me: AggroDoc.Me, npc: Int?): List<LivePlace.SpawnPt> =
        (npc?.let { byNpc[it] } ?: emptyList()).filter { hypot(it.x - me.x, it.y - me.y) <= NEAR_SPAWN_YD }

    /** each plate placed; the plates with the fewest fitting spawns first, so two of one kind never take the same spawn */
    private fun place(me: AggroDoc.Me, moving: Boolean) {
        val taken = HashSet<Int>()
        val order = live.values.sortedBy { t -> near(me, t.mob.n).count { s -> t.rings.sumOf { LivePlace.miss(s.x, s.y, it, LivePlace.WANDER_YD) } <= LivePlace.FEASIBLE_YD } }
        for (t in order) {
            val p = LivePlace.place(t.mob, me, t.rings, near(me, t.mob.n), taken, moving, cov?.maxd)
            if (p.spawn != null) { taken.add(p.spawn.key); if (p.sure) { absentUntil.remove(p.spawn.key); deadUntil.remove(p.spawn.key) } }
            t.placed = p
        }
    }

    private fun absence(now: Long) {
        val me = me ?: return
        val moving = now - lastMoveMs <= MOVING_MS
        val c = cov
        val can = !comb && c?.enemies != false && live.values.none { it.mob.n == null } && (c?.offscreen == true || (me.f != null && moving))
        if (!can) { absentSince.clear(); return }
        val range = (c?.maxd ?: DEFAULT_PLATE_YD).coerceAtMost(LivePlace.DEFAULT_MAX_YD) - ABSENT_MARGIN_YD
        val half = Math.toRadians(LivePlace.HFOV_DEG / 2 - 10.0)
        val looked = HashSet<Int>()
        for (s in all) {
            val d = hypot(s.x - me.x, s.y - me.y)
            if (d > range) continue
            if (c?.offscreen != true && LivePlace.angDiff(LivePlace.angleTo(me.x, me.y, s.x, s.y), me.f!!) > half) continue
            // a plate that could be it: snapped to it, or its npc with a bracket holding the spawn's distance
            val could = live.values.any { t -> t.mob.n == s.npc && (t.placed?.spawn?.key == s.key ||
                LivePlace.bracket(t.mob, c?.maxd).let { (a, b) -> d >= a - LivePlace.WANDER_YD && d <= b + LivePlace.WANDER_YD }) }
            if (could) continue
            looked.add(s.key)
            val since = absentSince.getOrPut(s.key) { now }
            if (now - since >= ABSENT_MS) absentUntil[s.key] = now + HOLD_MS
        }
        absentSince.keys.retainAll(looked)
    }

    /** the spawn's state now: "dead", "absent", "confirmed" (a plate snapped to it for sure), or null (expected: the prior) */
    @Synchronized fun spawnState(key: Int, now: Long = clock()): String? = when {
        (deadUntil[key] ?: Long.MIN_VALUE) > now -> "dead"
        (absentUntil[key] ?: Long.MIN_VALUE) > now -> "absent"
        live.values.any { it.placed?.spawn?.key == key && it.placed?.sure == true } -> "confirmed"
        else -> null
    }

    @Synchronized fun tracks(): List<Track> = live.values.sortedBy { it.slot }

    /**
     * the live layer for uiMap [map] for a player of level [plv] (the record's me.lv first): its discs (aggressive plates, by their
     * placement, fading once gone), the static shares to drop, the plates counted. [NONE][LiveDanger.NONE] off, on another map, or
     * with no record yet.
     */
    @Synchronized fun snapshot(map: Int, plv: Int?, now: Long = clock()): LiveDanger.Snapshot {
        offWhy?.let { return LiveDanger.Snapshot(emptyList(), emptyList(), 0, it) }
        val me = me ?: return LiveDanger.NONE
        if (me.map != null && map != 0 && me.map != map) return LiveDanger.NONE
        val lp = me.lv ?: plv ?: return LiveDanger.NONE
        val stale = now - lastFullMs > STALE_MS
        val discs = ArrayList<LiveDanger.Disc>()
        var mobs = 0
        fun add(t: Track, fade: Float) {
            val m = t.mob; val p = t.placed ?: return
            if (!m.aggressive) return
            val lm = m.lv ?: p.spawn?.maxLv          // lv omitted (a failed read): the spawn's top level, else equal level
            var w = LiveDanger.weight(m, lm, lp)
            if (w <= 0f) return
            if (fade >= 1f) mobs++
            if (m.cb == true && m.om == null) w *= LiveDanger.OTHER_WEIGHT.toFloat()
            w *= (LiveDanger.trust(p) * fade).toFloat() * LiveDanger.LIVE_GAIN
            if (w <= 0f) return
            // the shared rule (AggroFit.radius: fitted, learned); a skull keeps the route's own 45 yd (LiveDanger.SKULL_R)
            val r = if (lm == -1) LiveDanger.SKULL_R else AggroFit.radius(lm, lp, m.n, m.ct)
            val sure = (p.how == LivePlace.How.SPAWN && p.sure) || p.how == LivePlace.How.FIX
            discs.add(if (p.how == LivePlace.How.RING) LiveDanger.Disc(t.id, p.x, p.y, r, 0.0, w, true, p.a, p.b, p.half, p.how, m.n, lm)
                      else LiveDanger.Disc(t.id, p.x, p.y, r, p.u, w, how = p.how, npc = m.n, lv = lm, sure = sure))
        }
        for (t in live.values) {
            val since = if (stale) t.lastMs else -1L
            add(t, if (since < 0) 1f else (1f - (now - since).toFloat() / GONE_FADE_MS).coerceAtLeast(0f))
        }
        for (t in gone) add(t, (1f - (now - t.goneMs).toFloat() / GONE_FADE_MS).coerceAtLeast(0f))
        // the static shares taken off: dead / absent spawns, and those a plate holds for sure (its disc replaces them)
        val drops = ArrayList<LiveDanger.Drop>()
        val held = live.values.mapNotNull { t -> t.placed?.takeIf { it.sure }?.spawn?.key }.toHashSet()
        for (s in all) {
            if (!s.hostileToMe) continue                 // the static grid never counted it
            val why = when {
                (deadUntil[s.key] ?: Long.MIN_VALUE) > now -> "dead"
                (absentUntil[s.key] ?: Long.MIN_VALUE) > now -> "absent"
                s.key in held && !stale -> "confirmed"
                else -> continue
            }
            val w = DangerGrid.weight(s.minLv, s.maxLv, s.elite, lp)
            if (w > 0f) drops.add(LiveDanger.Drop(s.x, s.y, w, why))
        }
        return LiveDanger.Snapshot(discs, drops, mobs)
    }
}
