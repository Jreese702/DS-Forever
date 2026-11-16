package com.jrees.wowcompanion

/**
 * EVERY CLASS'S SPELLBOOK IN THE FIRST ICON PACK (issue #404, a new rogue whose spells were blank on the bottom screen; the user's pick,
 * 2026-11-16: "seed every class's spellbook icons in the FIRST icon pack (before Play), from the game's tables, so a new character's
 * spells are never blank"). The first pack (KitSteps.Icons, before Play on a first install) held the 27 fixed companion icons only; a
 * spell's picture came later, asked for while the game ran (IconRequirements.require) and packed only once the game closed (background
 * art waits while the game runs), or with the all-icons packs, which a new install still downloads behind the maps.
 *
 * From this build's own tables, so nothing of the game ships: every spell SkillLineAbility lists under a skill line that is not a
 * profession (SkillLine.CategoryID 11: the trade skills' recipes, thousands of item pictures that are no spellbook's) - every class's
 * trees, the hunter and warlock pet families, the racial traits, the general and weapon lines, mounts and companions - through
 * SpellMisc's SpellIconFileDataID. Measured on WoW Forever 1.60.1 (70009): 7,826 abilities, 5,349 outside the professions, 817 icons.
 *
 * The fields are this table family's (layout hashes [SKILL_LINE_LAYOUT] / [ABILITY_LAYOUT] / [SPELL_MISC_LAYOUT], the same SpellMisc
 * hash on 70009 and 70399); another layout is still read, but only while the rows look like these ([check]): a layout this cannot
 * read throws [Layout], and the pack goes on without the class book (nothing blank that was not before). Pure (the tests).
 */
object ClassBookIcons {
    const val DB_SKILL_LINE = 1240935
    const val DB_SKILL_LINE_ABILITY = 1266278
    const val DB_SPELL_MISC = 1003144
    const val SKILL_LINE_LAYOUT = 0x6763217C
    const val ABILITY_LAYOUT = 0x224F7EA0
    const val SPELL_MISC_LAYOUT = 0x434B3607
    /** SkillLine.CategoryID: the professions (left out) and the classes (every class's trees, pets, mounts; at least one per class) */
    const val CATEGORY_PROFESSION = 11
    const val CATEGORY_CLASS = 7
    const val SL_CATEGORY = 6
    const val SLA_SKILL_LINE = 3
    const val SLA_SPELL = 4
    const val SM_ICON = 10
    /** the classes' own lines a real table has at least (nine classes, three trees each, on this build 62 with pets and mounts) */
    const val MIN_CLASS_LINES = 9

    /** the rows did not look like this table family's (a new layout): the pack goes on without the class book */
    class Layout(why: String) : IllegalStateException(why)

    /** an ability: the skill line it is listed under, its spell */
    data class Ability(val line: Int, val spell: Int)

    /**
     * the icons of every spell an ability outside the professions names: [category] = skill line -> SkillLine.CategoryID, [iconOf] =
     * spell -> SpellMisc's icon. A line the SkillLine table does not have is left out (no category to judge it by).
     */
    fun icons(category: Map<Int, Int>, abilities: List<Ability>, iconOf: Map<Int, Int>): Set<Int> {
        val out = sortedSetOf<Int>()
        for (a in abilities) {
            val c = category[a.line] ?: continue
            if (c == CATEGORY_PROFESSION) continue
            val icon = iconOf[a.spell] ?: continue
            if (icon > 0) out.add(icon)
        }
        return out
    }

    /** the tables' rows as [icons] reads them, checked ([check]) first */
    fun fromTables(skillLine: Db2, abilities: Db2, spellMisc: Db2): Set<Int> {
        if (skillLine.fieldCount <= SL_CATEGORY || abilities.fieldCount <= SLA_SPELL || spellMisc.fieldCount <= SM_ICON)
            throw Layout("fields: SkillLine ${skillLine.fieldCount}, SkillLineAbility ${abilities.fieldCount}, SpellMisc ${spellMisc.fieldCount}")
        val category = HashMap<Int, Int>()
        for (r in skillLine.rows()) category[r.id] = r.int(SL_CATEGORY)
        val list = ArrayList<Ability>()
        var parented = 0
        for (r in abilities.rows()) {
            val a = Ability(r.int(SLA_SKILL_LINE), r.int(SLA_SPELL))
            if (r.parent == a.line) parented++
            list.add(a)
        }
        val iconOf = HashMap<Int, Int>()
        var plausible = 0
        var misc = 0
        for (r in spellMisc.rows()) {
            val spell = r.parent ?: continue
            val icon = r.int(SM_ICON)
            misc++
            if (icon in ICON_FIDS) plausible++
            if (icon > 0 && spell !in iconOf) iconOf[spell] = icon
        }
        check(category, list, parented, iconOf, plausible, misc)
        return icons(category, list, iconOf)
    }

    /** file IDs an icon has (the game's interface icons sit from 130,000 up); a field read from another layout lands outside */
    val ICON_FIDS = 100_000..99_999_999

    /**
     * A NEW LAYOUT READS AS GARBAGE THAT STILL LOOKS LIKE NUMBERS, so the rows must look like this family's: enough class lines, the
     * abilities parented on the line their field names (SkillLineAbility's relationship block is its SkillLine), most abilities' lines
     * known and most of their spells with an icon, SpellMisc's icons file IDs.
     */
    internal fun check(category: Map<Int, Int>, abilities: List<Ability>, parented: Int, iconOf: Map<Int, Int>, plausible: Int, misc: Int) {
        val classLines = category.values.count { it == CATEGORY_CLASS }
        if (classLines < MIN_CLASS_LINES) throw Layout("SkillLine: $classLines class lines (at least $MIN_CLASS_LINES)")
        if (abilities.isEmpty()) throw Layout("SkillLineAbility: no abilities")
        if (parented * 10 < abilities.size * 9) throw Layout("SkillLineAbility: ${abilities.size - parented} of ${abilities.size} not on their line")
        val known = abilities.count { it.line in category }
        if (known * 10 < abilities.size * 9) throw Layout("SkillLineAbility: ${abilities.size - known} of ${abilities.size} on unknown lines")
        val drawn = abilities.count { it.spell in iconOf }
        if (drawn * 2 < abilities.size) throw Layout("SpellMisc: only $drawn of ${abilities.size} abilities' spells have an icon")
        if (misc == 0 || plausible * 10 < misc * 9) throw Layout("SpellMisc: $plausible of $misc icons are file IDs")
    }
}
