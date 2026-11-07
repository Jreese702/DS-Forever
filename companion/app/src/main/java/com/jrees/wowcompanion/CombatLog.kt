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
    /** one unit's health as the log last said it; [exactAt] = the last time the log gave it outright (not by an amount) */
    data class Hp(val hp: Long, val max: Long, val at: Long, val exactAt: Long)

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

    class Health {
        private val map = HashMap<String, Hp>()
        @Synchronized fun get(guid: String): Hp? = map[guid]
        @Synchronized fun clear() = map.clear()
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
                map[info] = Hp(hp.coerceIn(0, max), max, t, t); changed = true
            }
            if (dst == info || dst == "0000000000000000") return changed
            val s = a + ADV + (if (ev.startsWith("ENVIRONMENTAL_")) 1 else 0)
            val old = map[dst] ?: return changed
            when {
                ev.endsWith("_DAMAGE") || ev == "DAMAGE_SPLIT" || ev == "DAMAGE_SHIELD" -> {
                    val amt = f.getOrNull(s)?.toLongOrNull() ?: return changed
                    map[dst] = old.copy(hp = (old.hp - amt).coerceIn(0, old.max), at = t); changed = true
                }
                ev.endsWith("_HEAL") -> {
                    val amt = f.getOrNull(s)?.toLongOrNull() ?: return changed
                    val over = f.getOrNull(s + 2)?.toLongOrNull()?.coerceAtLeast(0) ?: 0
                    map[dst] = old.copy(hp = (old.hp + amt - over).coerceIn(0, old.max), at = t); changed = true
                }
            }
            return changed
        }
    }
}
