package com.jrees.wowcompanion

/** which of Interact's four tap rows a command's cell is on (the add-on's assignSlots asks each command's cell on that row). Pure. */
object InteractRows {
    fun of(cmd: String): String = when (cmd.substringBefore(' ')) {
        "opt", "take", "turnin", "pick", "trhead", "ahcat", "ahsfold" -> "ipick"
        "buy", "train", "pet", "mail", "ahres", "ahrow", "taxi", "bkdo", "loot" -> "ibuy"
        "sell", "mailtake", "mailmoney", "mailall", "bkpick", "bkput", "lootdelc", "lootdelr", "maildetach", "ahsitem", "ahfav" -> "isell"
        // Interact i171: the AH Sell form's 14 cells live on isell (iact ran out of columns: the confirm's Post and Reset drew grey)
        "ahsqty", "ahsedit", "ahsdur", "ahsbo", "ahspost" -> "isell"
        "lookbag", "ahfilter", "look" -> "ipick"
        "tradein" -> "ibuy"
        "tradeout", "tradepick" -> "isell"
        "mailattach", "ahcancel" -> "ibuy"
        else -> "iact"
    }
}
