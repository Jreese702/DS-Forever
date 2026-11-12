package com.jrees.wowcompanion

import com.jrees.wowcompanion.PartyModel.Dir

/**
 * WHAT THE STRIP SHOWS IN PARTY MODE (PARTY_BUILD.md 4.3; SPEC_DRAFT part 2, v10, v13). Pure, JVM-tested (PartyStripTest); CastView
 * draws it. Party mode OFF: today's strip, untouched (nothing here is asked).
 *
 * D-pad: the game's gold arrows, an arrow whose NAV is "-" dimmed. A / X: the slot's spell or Empty. Y: out of combat the game's own Y
 * (Inspect), or with Smart Y on the buff of `sy.pick[u]`; in a fight `yc`'s icon, else Smart Y's armed `sy.pick[u]`, else Target (PadMap m132:
 * no player menu in a fight). B: Back (closes Party mode). A held
 * trigger swaps A / X / Y to its chord layer (both held: nothing). Lit / dim: the boxed member DEAD -> only `res` spells lit; ALIVE ->
 * `res` spells dim; not learned (`k = false`) dim; Y's Inspect / Smart pick and B never dim.
 */
object PartyStrip {
    enum class Layer(val prefix: String) { BASE(""), ZL("zl"), ZR("zr"), BOTH("") }

    fun layer(lt: Boolean, rt: Boolean): Layer = when {
        lt && rt -> Layer.BOTH
        lt -> Layer.ZL
        rt -> Layer.ZR
        else -> Layer.BASE
    }

    enum class Kind {
        /** a spell's icon (cut round in today's ring, the glyph badge on top) */
        SPELL,
        /** the ring alone at 45 % */
        EMPTY,
        /** the game's own Y picture (Inspect: the boxed member's player menu) */
        INSPECT,
        /** Smart Y's buff for the boxed member (its icon, the Y badge on top) */
        SMART,
        /** the game's red Back */
        BACK,
        /** m132: Y in a fight with nothing to cast: Target the boxed member (the friendly reticle) */
        TARGET,
    }

    data class Pic(val kind: Kind, val spell: PartyModel.Spell?, val lit: Boolean)

    /** the boxed member, or null (no roster) */
    fun boxed(party: PartyModel.Party?, box: Int): PartyModel.Member? = party?.m?.getOrNull(box.coerceIn(1, maxOf(1, party.n)) - 1)

    /** lit / dim for a SPELL on [m] */
    fun lit(s: PartyModel.Spell, m: PartyModel.Member?): Boolean {
        if (!s.k) return false
        val dead = m?.dead == true
        return if (dead) s.res else !s.res
    }

    private fun spellPic(s: PartyModel.Spell?, m: PartyModel.Member?): Pic =
        if (s == null) Pic(Kind.EMPTY, null, false) else Pic(Kind.SPELL, s, lit(s, m))

    /**
     * Face [face] ("a", "x", "y", "b") on [layer]. [fight] = the game is fighting (the strip's Y shows `yc` only then).
     */
    fun face(face: String, layer: Layer, cfg: PartyModel.Cfg?, party: PartyModel.Party?, box: Int, fight: Boolean): Pic {
        val m = boxed(party, box)
        if (face == "b") return Pic(Kind.BACK, null, true)
        if (layer == Layer.BOTH) return Pic(Kind.EMPTY, null, false)          // both triggers: nothing
        if (cfg == null) return if (face == "y" && layer == Layer.BASE) Pic(Kind.INSPECT, null, true) else Pic(Kind.EMPTY, null, false)
        if (face == "y" && layer == Layer.BASE) {
            val pick = if (cfg.sy.on) m?.let { cfg.sy.pick[it.u] } else null
            if (fight) return cfg.yc?.let { Pic(Kind.SPELL, it, true) } ?: pick?.let { Pic(Kind.SMART, it, true) } ?: Pic(Kind.TARGET, null, true)
            return pick?.let { Pic(Kind.SMART, it, true) } ?: Pic(Kind.INSPECT, null, true)
        }
        return spellPic(cfg.slot(layer.prefix + face), m)
    }

    /** the D-pad arrows: true = lit (the box can move that way), false = dimmed to 28 % */
    fun arrows(n: Int, box: Int): Map<Dir, Boolean> = Dir.values().associateWith { PartyModel.nav(maxOf(1, n), box, it) != null }

    /** the trigger plate's chord spells (ZL: zla, zlx, zly; ZR: zra, zrx, zry), Empty places not shown */
    fun plateIcons(cfg: PartyModel.Cfg?, left: Boolean): List<PartyModel.Spell> {
        if (cfg == null) return emptyList()
        val p = if (left) "zl" else "zr"
        return listOf("a", "x", "y").mapNotNull { cfg.slot(p + it) }
    }

    /** the trigger plate is lit while its trigger is held */
    fun plateLit(left: Boolean, lt: Boolean, rt: Boolean) = if (left) lt else rt
}
