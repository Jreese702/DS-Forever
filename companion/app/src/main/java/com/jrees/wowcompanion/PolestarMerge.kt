package com.jrees.wowcompanion

/**
 * ONE ROW FOR POLESTAR'S QUEST (pure: no Android types). The user, 2026-11-11: "The tracker is a bit confusing when it shows the
 * blue quest but then a second focused quest. I want to consolidate that into one when it truly is tracking a quest and show that
 * quests objectives instead of 'complete that quest'".
 *
 * While the light blue row is picked and its step's quest is in the log, the row IS that quest: its title and its live objectives
 * (done / progress as the quest's own card reads them) in Polestar's light blue, and that quest's own row (tracked or untracked) is
 * not drawn a second time. Its A stays the Polestar toggle. A step with no quest, or one not in the log (accept / travel / train /
 * talk): the step's title and goal words, as before. Not picked: nothing merges.
 */
object PolestarMerge {
    /** the quest the light blue row stands for, 0 = none (the row keeps Polestar's own words) */
    fun quest(shown: Boolean, picked: Boolean, stepQuest: Int, inLog: (Int) -> Boolean): Int =
        if (shown && picked && stepQuest > 0 && inLog(stepQuest)) stepQuest else 0

    /**
     * the QUEST a list key stands for on the map: the merged light blue row's key [MapList.POLESTAR] is its quest [merged] - its objective point,
     * its reveal / framing, its route line, its bubble ring, its panel - exactly as on that quest's own row; any other key is itself
     * (the unmerged row stays POLESTAR: no quest of its own on the map)
     */
    fun key(key: Int, merged: Int): Int = if (key == MapList.POLESTAR && merged > 0) merged else key

    /** what the light blue row reads: a title and its lines (text, done) */
    class Content(val title: String, val lines: List<Pair<String, Boolean>>, val merged: Boolean)

    /**
     * the row's words: merged = the quest's [questTitle] and [questLines] (the quest's own card's lines); else the step's [stepTitle]
     * and [stepWords] (the words once, under the title). Null: nothing to draw.
     */
    fun content(merged: Int, stepTitle: String?, stepWords: String?, questTitle: String?,
                questLines: List<Pair<String, Boolean>>): Content? {
        if (merged > 0 && !questTitle.isNullOrEmpty()) return Content(questTitle, questLines, true)
        val title = stepTitle ?: stepWords ?: return null
        val words = stepWords?.takeIf { it != title }
        return Content(title, listOfNotNull(words?.let { it to false }), false)
    }
}
