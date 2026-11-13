package com.jrees.wowcompanion

/**
 * PARTY MODE'S TAPS (PARTY_BUILD.md 2.2-2.4): the cells of every action, from `partycfg.pad` only (never a constant of the app's).
 * Each function returns ONE batch for TapBridge.taps, or null when a column (or the cfg) is missing: that action is unavailable.
 * Pure, JVM-tested (PartyTapsTest). `i` = the member's unit index (0 = player, 1..4 = partyN, 5 = the pet: m131).
 */
typealias PartyCells = List<Pair<String, Int>>

object PartyTaps {


    private fun at(row: String?, col: Int?, plus: Int = 0): Pair<String, Int>? =
        if (row == null || col == null) null else row to (col + plus)

    private fun one(c: Pair<String, Int>?): PartyCells? = c?.let { listOf(it) }

    /**
     * m129 A PLATE'S TAP (party_design.md C, Touch): `pick + i`, always the same cell - the add-on's secure snippet decides what it
     * does: Party mode off = Target; on, another member = the pick moves there (nothing cast); on, the picked member = the Touch action
     * (Target, or the Touch spell). Named apart from [pick] (the Controller tab's slot picker). m131: the pet's cell is `pick + 5`
     * (PadMap PM.PET_COL); the menu / focus cells stay you and party1..4 (the pet has no player menu here).
     */
    fun choose(cfg: PartyModel.Cfg?, i: Int): PartyCells? {
        val p = cfg?.pad ?: return null
        if (i !in 0..PartyModel.PET_I) return null
        return one(at(p.row, p.pick, i))
    }

    /** the (-) tag: Party mode ON (absolute) */
    fun on(cfg: PartyModel.Cfg?): PartyCells? = cfg?.pad?.let { one(at(it.row, it.on)) }
    /** Home's red disc / leaving Home: Party mode OFF (absolute) */
    fun off(cfg: PartyModel.Cfg?): PartyCells? = cfg?.pad?.let { one(at(it.row, it.off)) }
    /** a LONG PRESS: read the player menu for member [i]; PadMap m157 (the user on 1462: "holding on the pet bar does not open the pet
     *  inspect"): the pet (i 5) = its own menu cell `pmenu` */
    fun menu(cfg: PartyModel.Cfg?, i: Int): PartyCells? = when (i) {
        in 0..4 -> cfg?.pad?.let { one(at(it.row, it.menu, i)) }
        PartyModel.PET_I -> cfg?.pad?.let { one(at(it.row, it.pmenu)) }
        else -> null
    }

    /**
     * PadMap m157 (the user on 1462: "pressing B in a player inspect radial opened from party mode does not close the radial as it
     * should."): the hold's ONE batch, MENU HOLD first (Party mode on: the pad's presses become the wheel's `key`, B its back / close,
     * never Party mode off; MENU DONE ends it with the wheel), then the menu read. Null = no menu for [i]; an add-on with no MENU HOLD
     * column = the read alone ([holds] false: the app reads the wheel's presses itself)
     */
    fun hold(cfg: PartyModel.Cfg?, i: Int): Pair<PartyCells, Boolean>? {
        val read = menu(cfg, i) ?: return null
        val mh = cfg?.pad?.let { at(it.row, it.mhold) } ?: return read to false
        return (listOf(mh) + read) to true
    }
    /** the player menu's Set Focus on member [i] */
    fun focus(cfg: PartyModel.Cfg?, i: Int): PartyCells? = if (i !in 0..4) null else cfg?.pad?.let { one(at(it.row, it.focus, i)) }
    /** RESEND: partycfg + party + partybox{k=state} */
    fun resend(cfg: PartyModel.Cfg?): PartyCells? = cfg?.pad?.let { one(at(it.row, it.resend)) }
    /** the player menu closed (only when the PartyPad opened it: partybox.pm) */
    fun menuDone(cfg: PartyModel.Cfg?): PartyCells? = cfg?.pad?.let { one(at(it.row, it.mdone)) }

    enum class PbOp { REMOVE, UP, DOWN }

    /** Smart Y's party list: select row [k] (1..8) then [op], ONE batch */
    fun pbOp(cfg: PartyModel.Cfg?, k: Int, op: PbOp): PartyCells? {
        val p = cfg?.pad ?: return null
        if (k !in 1..8) return null
        val sel = at(p.row, p.pbsel, k - 1) ?: return null
        val col = when (op) { PbOp.REMOVE -> p.pbRemove; PbOp.UP -> p.pbUp; PbOp.DOWN -> p.pbDown }
        val act = at(p.row, col) ?: return null
        return listOf(sel, act)
    }

    /** Smart Y's party list: Add = the fresh add pick, then the spell's two Book taps, ONE batch */
    fun pbAdd(cfg: PartyModel.Cfg?, spellCells: PartyCells?): PartyCells? {
        val p = cfg?.pad ?: return null
        if (spellCells.isNullOrEmpty()) return null
        val add = at(p.row, p.pbAdd) ?: return null
        return listOf(add) + spellCells
    }

    // ------------------------------------------------------------------------------------------------ the Controller room's tab

    /** the Party mode tab (padtab 26) */
    fun tab(cfg: PartyModel.Cfg?): PartyCells? = cfg?.pad?.let { one(at(it.tabRow, it.tab)) }

    /** pick [slot] ("a", "x", "yc", "touch", "zla" .. "zry") alone: padtab slot0 + k - 1 (it also moves the tab's cursor) */
    fun pick(cfg: PartyModel.Cfg?, slot: String): PartyCells? {
        val p = cfg?.pad ?: return null
        val k = PartyModel.PICKS.indexOf(slot) + 1
        if (k <= 0) return null
        return one(at(p.tabRow, p.slot0, k - 1))
    }

    /** [slot] gets its no-spell value (Empty; Inspect for yc; Target for touch): the pick, then padtab none */
    fun setNone(cfg: PartyModel.Cfg?, slot: String): PartyCells? {
        val pk = pick(cfg, slot) ?: return null
        val none = at(cfg?.pad?.tabRow, cfg?.pad?.none) ?: return null
        return pk + none
    }

    /** [slot] gets a spell: the pick, then Book's two taps for it; a spell not in `partycfg.spells` is refused (null) */
    fun setSpell(cfg: PartyModel.Cfg?, slot: String, spellId: Int, spellCells: PartyCells?): PartyCells? {
        if (cfg == null || cfg.spells.none { it.id == spellId }) return null
        if (spellCells.isNullOrEmpty()) return null
        val pk = pick(cfg, slot) ?: return null
        return pk + spellCells
    }
}
