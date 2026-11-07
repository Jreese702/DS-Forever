package com.jrees.wowcompanion

/**
 * THE GAME'S COMBAT LOG, READ (the user, 2026-11-07: "yes I want to build it I also want to bring back the party mode built off of it").
 * WoW Forever hides every health number from add-ons, but with Advanced Combat Logging on (Options > Network; advancedCombatLogging)
 * the game writes them, plain, into its own file Logs/WoWCombatLog-*.txt - the file Warcraft Logs reads. Measured on the Thor the same
 * day (COMBAT_LOG_VERSION 22, build 1.60.1): every event with the "advanced" block carries its ACTING unit's current and max health
 * ("Sirlord ... 278,290"), and every damage / heal its amount. So:
 *  - an event's advanced block = that unit's health, exactly (the source of a swing / cast / heal);
 *  - a hit or heal on a unit that is not the block's unit moves the last known value by the amount, until its next exact one;
 *  - UNIT_DIED = 0.
 * Pure: lines in, [Health] out; [CombatLogTail] feeds it from the file.
 */
object CombatLog {
    /** one unit's health as the log last said it; [exactAt] = the last time the log gave it outright (not by an amount); [hurtAt] = its
     *  last hit; [rate] = its own regen out of a fight, health per second, learned (0 = not yet) */
    data class Hp(val hp: Long, val max: Long, val at: Long, val exactAt: Long, val hurtAt: Long = 0, val rate: Double = 0.0,
                  /** THE POWER (the user, 2026-11-07: "The mana bars always show 100%" - the game hides it from add-ons too): the advanced
                   *  block's power type (0 = mana), value and max at [pwAt]; [castAt] = the unit's last cast with a cost; [pwRate] = its
                   *  learned regen (per second) from [MANA_AFTER_MS] after a cast (the game's five-second rule); -1 = not seen */
                  val pt: Int = -1, val pw: Long = -1, val pwMax: Long = 0, val pwAt: Long = 0, val castAt: Long = 0, val pwRate: Double = 0.0) {
        /** THE REGEN TRUE-UP (the user, 2026-11-07: "out of combat my HP is showing stuck at 90%" - natural regen is never logged;
         *  picked "Learn my regen", with the old model's idea of combining): [REGEN_AFTER_MS] after the last hit, the value climbs from
         *  the last reading at the unit's own learned rate, to its max; the next exact reading replaces it */
        fun hpAt(now: Long): Long {
            if (rate <= 0.0 || hp >= max) return hp
            val from = maxOf(at, hurtAt + REGEN_AFTER_MS)
            if (now <= from) return hp
            return minOf(max, hp + (rate * (now - from) / 1000.0).toLong())
        }
    }
    /** out of a fight the game regenerates health only a few seconds after the last hit */
    const val REGEN_AFTER_MS = 6_000L
    /** mana comes back (spirit) only five seconds after the last cast that cost any */
    const val MANA_AFTER_MS = 5_000L
    /** a learned rate needs two exact readings this far apart with no hit or heal between */
    const val LEARN_MIN_MS = 4_000L

    /** the fields of one line after its timestamp, split on commas outside quotes (names and spells are quoted) */
    fun fields(s: String): List<String> {
        val out = ArrayList<String>(48)
        val b = StringBuilder()
        var q = false
        for (ch in s) {
            when {
                ch == '"' -> q = !q
                ch == ',' && !q -> { out.add(b.toString()); b.setLength(0) }
                else -> b.append(ch)
            }
        }
        out.add(b.toString())
        return out
    }

    /** "11/7/2026 11:10:38.610-7  EVENT,..." -> the ms since the epoch (null = not a combat-log line) and the rest after the two spaces */
    fun split(line: String): Pair<Long?, String>? {
        val gap = line.indexOf("  ")
        if (gap < 10) return null
        return stamp(line.substring(0, gap)) to line.substring(gap + 2)
    }

    /** the line's time: local date and time with the game's UTC offset in hours ("-7"); null when it does not read */
    fun stamp(s: String): Long? = runCatching {
        val sp = s.indexOf(' ')
        val d = s.substring(0, sp).split('/')
        val t = s.substring(sp + 1)
        val off = t.indexOfAny(charArrayOf('-', '+'), 8)
        val hms = (if (off > 0) t.substring(0, off) else t).split(':')
        val offH = if (off > 0) t.substring(off).replace("+", "").toDouble() else 0.0
        val secParts = hms[2].split('.')
        val ms = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toInt() ?: 0
        val ldt = java.time.LocalDateTime.of(d[2].toInt(), d[0].toInt(), d[1].toInt(), hms[0].toInt(), hms[1].toInt(), secParts[0].toInt(), ms * 1_000_000)
        ldt.toInstant(java.time.ZoneOffset.ofTotalSeconds((offH * 3600).toInt())).toEpochMilli()
    }.getOrNull()

    private fun isGuid(s: String) = s == "0000000000000000" || s.startsWith("Player-") || s.startsWith("Creature-") || s.startsWith("Pet-") ||
        s.startsWith("Vehicle-") || s.startsWith("GameObject-")

    /** the prefix's own fields between the destination and the advanced block: SPELL_ / RANGE_ / SPELL_PERIODIC_ / SPELL_BUILDING_ = 3 */
    private fun prefixLen(ev: String) = when {
        ev.startsWith("SWING_") || ev.startsWith("ENVIRONMENTAL_") || ev == "UNIT_DIED" -> 0
        ev.startsWith("SPELL_") || ev.startsWith("RANGE_") || ev == "DAMAGE_SPLIT" || ev == "DAMAGE_SHIELD" -> 3
        else -> -1
    }

    /** the advanced block's length (infoGUID, ownerGUID, hp, max, AP, SP, armor, absorb, 2 more, power type, power, max, cost, x, y,
     *  map, facing, level - measured: 19 fields in version 22) */
    const val ADV = 19

    /** THE CALIBRATION (the user picked it, 2026-11-07): how big a unit's hits, crits and heals are, learned from every one the log
     *  shows (a moving mean); the app spends it on the add-on's live COUNTS, whose amounts the game hides. 0 = not seen yet */
    data class Cal(val hit: Double = 0.0, val hitN: Int = 0, val crit: Double = 0.0, val critN: Int = 0, val heal: Double = 0.0, val healN: Int = 0,
                   /** the mean cost of a cast (every SPELL_CAST_SUCCESS of the unit, free ones too: the add-on counts them all live) */
                   val cost: Double = 0.0, val costN: Int = 0) {
        fun withCost(a: Long): Cal = copy(cost = mean(cost, costN, a), costN = costN + 1)
        fun withHit(a: Long, crit: Boolean): Cal = if (crit) copy(crit = mean(this.crit, critN, a), critN = critN + 1)
            else copy(hit = mean(hit, hitN, a), hitN = hitN + 1)
        fun withHeal(a: Long): Cal = copy(heal = mean(heal, healN, a), healN = healN + 1)
        private fun mean(old: Double, n: Int, a: Long): Double = if (n == 0) a.toDouble() else old + (a - old) * (if (n < 10) 1.0 / (n + 1) else CAL_ALPHA)
    }
    /** past the first ten, each new hit moves the mean by this share (newer fights count more: the mobs change as you level) */
    const val CAL_ALPHA = 0.1

    class Health {
        private val map = HashMap<String, Hp>()
        private val cals = HashMap<String, Cal>()
        @Synchronized fun get(guid: String): Hp? = map[guid]
        @Synchronized fun cal(guid: String): Cal? = cals[guid]
        @Synchronized fun clear() { map.clear() }
        @Synchronized fun size() = map.size

        /** one line; [now] = when it was read (used when the line has no time); true = a unit's health changed */
        fun line(line: String, now: Long): Boolean {
            val (t, rest) = split(line) ?: return false
            return event(fields(rest), t ?: now)
        }

        @Synchronized fun event(f: List<String>, t: Long): Boolean {
            val ev = f.firstOrNull() ?: return false
            if (ev == "UNIT_DIED" || ev == "UNIT_DESTROYED") {
                val g = f.getOrNull(5) ?: return false
                val old = map[g] ?: return false
                map[g] = old.copy(hp = 0, at = t, exactAt = t)
                return true
            }
            val pre = prefixLen(ev)
            if (pre < 0 || f.size < 9 + pre + ADV) return false
            val a = 9 + pre
            var changed = false
            val info = f[a]
            if (!isGuid(info)) return false                          // no advanced block (an aura line, or the option is off)
            val dst = f[5]
            val hp = f[a + 2].toLongOrNull(); val max = f[a + 3].toLongOrNull()
            if (info != "0000000000000000" && hp != null && max != null && max > 0) {
                val old = map[info]
                val now = hp.coerceIn(0, max)
                // learn the regen: two exact readings, nothing between (at == exactAt), out of a fight (no hit since REGEN_AFTER_MS
                // before the first), health went up and was not full
                var rate = old?.rate ?: 0.0
                if (old != null && old.at == old.exactAt && t - old.exactAt >= LEARN_MIN_MS && now > old.hp && old.hp < old.max &&
                    old.exactAt - old.hurtAt >= REGEN_AFTER_MS) {
                    val sample = (now - old.hp) * 1000.0 / (t - old.exactAt)
                    rate = if (rate <= 0.0) sample else rate * 0.6 + sample * 0.4
                }
                // the power: type, value, max, and this event's cost (a cast's line carries what it cost)
                val ptype = f[a + 10].toIntOrNull(); val pcur = f[a + 11].toLongOrNull(); val pmax = f[a + 12].toLongOrNull()
                val cost = f[a + 13].toLongOrNull() ?: 0L
                val isCast = ev == "SPELL_CAST_SUCCESS" && f.getOrNull(1) == info
                var pw = old?.pw ?: -1L; var pwMax = old?.pwMax ?: 0L; var pt = old?.pt ?: -1; var pwAt = old?.pwAt ?: 0L
                var castAt = old?.castAt ?: 0L; var pwRate = old?.pwRate ?: 0.0
                if (ptype != null && pcur != null && pmax != null && pmax > 0) {
                    // learn the mana regen: two readings with no cast between, both past the five-second rule, mana went up
                    if (old != null && old.pw >= 0 && old.pt == ptype && !isCast && t - old.pwAt >= LEARN_MIN_MS && pcur > old.pw &&
                        old.pw < old.pwMax && old.pwAt - old.castAt >= MANA_AFTER_MS) {
                        val sample = (pcur - old.pw) * 1000.0 / (t - old.pwAt)
                        pwRate = if (pwRate <= 0.0) sample else pwRate * 0.6 + sample * 0.4
                    }
                    pt = ptype; pw = pcur.coerceIn(0, pmax); pwMax = pmax; pwAt = t
                }
                if (isCast) {
                    cals[info] = (cals[info] ?: Cal()).withCost(cost)
                    if (cost > 0) castAt = t
                }
                map[info] = Hp(now, max, t, t, old?.hurtAt ?: 0, rate, pt, pw, pwMax, pwAt, castAt, pwRate); changed = true
            }
            if (dst == "0000000000000000") return changed
            val s = a + ADV + (if (ev.startsWith("ENVIRONMENTAL_")) 1 else 0)
            // the calibration sees every hit / heal on [dst] (the add-on counts the same ones live, UNIT_COMBAT)
            val isDmg = ev.endsWith("_DAMAGE") || ev == "DAMAGE_SPLIT" || ev == "DAMAGE_SHIELD"
            val isHeal = ev.endsWith("_HEAL")
            if (isDmg || isHeal) f.getOrNull(s)?.toLongOrNull()?.let { amt ->
                val c = cals[dst] ?: Cal()
                cals[dst] = if (isDmg) c.withHit(amt, f.getOrNull(s + 7) == "1")
                    else c.withHeal((amt - (f.getOrNull(s + 2)?.toLongOrNull()?.coerceAtLeast(0) ?: 0)).coerceAtLeast(0))
            }
            if (dst == info) return changed
            val old = map[dst] ?: return changed
            when {
                ev.endsWith("_DAMAGE") || ev == "DAMAGE_SPLIT" || ev == "DAMAGE_SHIELD" -> {
                    val amt = f.getOrNull(s)?.toLongOrNull() ?: return changed
                    map[dst] = old.copy(hp = (old.hpAt(t) - amt).coerceIn(0, old.max), at = t, hurtAt = t); changed = true
                }
                ev.endsWith("_HEAL") -> {
                    val amt = f.getOrNull(s)?.toLongOrNull() ?: return changed
                    val over = f.getOrNull(s + 2)?.toLongOrNull()?.coerceAtLeast(0) ?: 0
                    map[dst] = old.copy(hp = (old.hpAt(t) + amt - over).coerceIn(0, old.max), at = t); changed = true
                }
            }
            return changed
        }
    }
}

/**
 * THE HEALTH TO SHOW (the party module), from the combat log and the add-on's live counts:
 *  - the ANCHOR = the log's last word on the unit (exact, or moved by a logged hit / heal), CombatLog.Health;
 *  - the add-on's live WOUND / CRIT / HEAL counts (PadMap m96: UNIT_COMBAT, amounts hidden) since the anchor, each spent at the unit's
 *    calibrated size (CombatLog.Cal; until one is seen, DEFAULT_* of its max) - the game writes the log only at a fight's end (measured,
 *    test11), so in a fight these counts are all there is;
 *  - its learned regen out of a fight, from REGEN_AFTER_MS after the last hit (logged or live).
 * The fight's end writes the log: the exact value replaces the estimate.
 */
object CombatHealth {
    val store = CombatLog.Health()
    @Volatile var lastDelay: String = ""
    /** an anchor with nothing newer (no line, no live count) for this long gives way to the add-on's own value, unless a regen is known */
    const val FRESH_MS = 60_000L
    /** a live count reaches the app up to this long after the hit (the add-on's 0.5 s throttle, the log's flush): one taken within it
     *  after the anchor is the anchor's own hit, not a new one */
    const val LIVE_LAG_MS = 1_200L
    /** the sizes used before the log has shown one (share of the unit's max) */
    const val DEFAULT_HIT = 0.02; const val DEFAULT_CRIT = 0.04; const val DEFAULT_HEAL = 0.05
    private const val KEEP_MS = 15 * 60_000L

    /** the live counts as one series per GUID: (when received, wounds, crits, heals), made monotonic over a /reload's reset */
    private data class Snap(val t: Long, val w: Int, val c: Int, val h: Int, val hurtAt: Long, val s: Int = 0, val castAt: Long = 0L)
    private class Series { val snaps = ArrayDeque<Snap>(); var offW = 0; var offC = 0; var offH = 0; var offS = 0
        var rawW = -1; var rawC = 0; var rawH = 0; var rawS = 0 }
    private val live = HashMap<String, Series>()

    /** a party document's counts ([w] wounds, [c] crits of them, [h] heals, [sc] casts; cumulative in the add-on), received at [now] */
    @Synchronized fun counts(guid: String?, w: Int?, c: Int?, h: Int?, now: Long, sc: Int? = null) {
        if (guid.isNullOrEmpty() || w == null) return
        val s = live.getOrPut(guid) { Series() }
        val cc = c ?: 0; val hh = h ?: 0; val ss = sc ?: 0
        if (s.rawW >= 0 && (w < s.rawW || cc < s.rawC || hh < s.rawH || ss < s.rawS)) {                  // a /reload
            s.offW += s.rawW; s.offC += s.rawC; s.offH += s.rawH; s.offS += s.rawS
        }
        s.rawW = w; s.rawC = cc; s.rawH = hh; s.rawS = ss
        val mw = w + s.offW; val mc = cc + s.offC; val mh = hh + s.offH; val ms = ss + s.offS
        val last = s.snaps.lastOrNull()
        if (last != null && last.w == mw && last.c == mc && last.h == mh && last.s == ms) return
        s.snaps.addLast(Snap(now, mw, mc, mh, if (last != null && mw > last.w) now else last?.hurtAt ?: 0L,
            ms, if (last != null && ms > last.s) now else last?.castAt ?: 0L))
        while (s.snaps.size > 2 && now - s.snaps.first().t > KEEP_MS) s.snaps.removeFirst()
    }

    @Synchronized fun clear() { store.clear(); live.clear() }

    /** the share of [guid]'s power bar to fill (0..1), null = the log has not shown it. Mana: the last reading minus the live casts since
     *  it at the unit's mean cost (DEFAULT_COST of the max until one is seen), plus its learned regen after the five-second rule. Other
     *  powers (rage, energy) come and go too fast to guess: the last reading as it was */
    @Synchronized fun powerFrac(guid: String?, now: Long = System.currentTimeMillis()): Float? {
        if (guid.isNullOrEmpty()) return null
        val e = store.get(guid) ?: return null
        if (e.pw < 0 || e.pwMax <= 0) return null
        var v = e.pw.toDouble()
        if (e.pt == 0) {
            var castAt = e.castAt
            live[guid]?.snaps?.let { snaps ->
                val cur = snaps.lastOrNull() ?: return@let
                val base = snaps.lastOrNull { it.t <= e.pwAt + LIVE_LAG_MS } ?: snaps.first().takeIf { it.t > e.pwAt + LIVE_LAG_MS }?.let {
                    Snap(e.pwAt, it.w, it.c, it.h, 0L, it.s, 0L) } ?: return@let
                val ds = (cur.s - base.s).coerceAtLeast(0)
                if (ds > 0) {
                    val cost = store.cal(guid)?.takeIf { it.costN > 0 }?.cost ?: (e.pwMax * DEFAULT_COST)
                    v -= ds * cost
                    castAt = maxOf(castAt, cur.castAt)
                }
            }
            val from = maxOf(e.pwAt, castAt + CombatLog.MANA_AFTER_MS)
            if (e.pwRate > 0.0 && now > from) v += e.pwRate * (now - from) / 1000.0
        }
        return (v / e.pwMax).toFloat().coerceIn(0f, 1f)
    }
    const val DEFAULT_COST = 0.05

    /** [guid]'s health in percent, null = nothing fresh enough (the add-on's own value then) */
    @Synchronized fun pct(guid: String?, now: Long = System.currentTimeMillis()): Int? {
        val hp = hp(guid, now) ?: return null
        val e = store.get(guid!!) ?: return null
        return ((hp * 100 + e.max / 2) / e.max).toInt().coerceIn(0, 100)
    }

    /** [guid]'s health in points, as above */
    @Synchronized fun hp(guid: String?, now: Long = System.currentTimeMillis()): Long? {
        if (guid.isNullOrEmpty()) return null
        val e = store.get(guid) ?: return null
        if (e.max <= 0) return null
        var v = e.hp.toDouble()
        var hurtAt = e.hurtAt
        var newest = e.at
        live[guid]?.snaps?.let { snaps ->
            val cur = snaps.lastOrNull() ?: return@let
            // the counts at the anchor: the last series point received by its time (+ the live lag)
            val base = snaps.lastOrNull { it.t <= e.at + LIVE_LAG_MS } ?: snaps.first().takeIf { it.t > e.at + LIVE_LAG_MS }?.let {
                Snap(e.at, it.w, it.c, it.h, 0L) } ?: return@let
            val dw = (cur.w - base.w).coerceAtLeast(0); val dc = (cur.c - base.c).coerceIn(0, dw); val dh = (cur.h - base.h).coerceAtLeast(0)
            val cal = store.cal(guid)
            val hit = cal?.takeIf { it.hitN > 0 }?.hit ?: (e.max * DEFAULT_HIT)
            val crit = cal?.takeIf { it.critN > 0 }?.crit ?: if (cal != null && cal.hitN > 0) hit * 2.0 else e.max * DEFAULT_CRIT
            val heal = cal?.takeIf { it.healN > 0 }?.heal ?: (e.max * DEFAULT_HEAL)
            v = v - (dw - dc) * hit - dc * crit + dh * heal
            if (dw > 0) hurtAt = maxOf(hurtAt, cur.hurtAt)
            newest = maxOf(newest, cur.t)
        }
        if (now - newest > FRESH_MS && e.rate <= 0.0) return null
        // the learned regen, from REGEN_AFTER_MS after the last hit (logged or live)
        val from = maxOf(e.at, hurtAt + CombatLog.REGEN_AFTER_MS)
        if (e.rate > 0.0 && now > from) v += e.rate * (now - from) / 1000.0
        return v.toLong().coerceIn(0, e.max)
    }
}
