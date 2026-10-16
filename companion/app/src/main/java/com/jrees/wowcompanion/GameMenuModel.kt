package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE GAME MENU ON THE BOTTOM SCREEN, ITS RULES (the user 2026-10-09: "can we rebuild that on the bottom screen? just the menu, we wont
 * dig into all of the options"; the picks: UI Art's look on the cleared stage, "Escape fallback", "Mirror Cancel below"). The pause
 * wheel's Game Menu slice opens it. The game's own list comes from Spells' "gamemenu" document (r12: only the buttons the GAME would
 * show, its labels, greyed as it greys them); each button's route is Controller UI's table (wow-companion-pulse/GAME_MENU_ROUTES.md):
 *  - Log Out / Exit Game / Macros / Edit Mode = a tap on Spells' SECURE "gamemenu" row (the game's own slash handlers);
 *    Log Out and Exit Game ASK FIRST ("Log Out?": /quit in a rested area exits at once, the cell has no second chance - Quests' rule);
 *  - every other button (Options, Shop, AddOns, Support, ...) has no clean route: the game's own Escape, so the player finishes in the
 *    game's own menu on the top screen;
 *  - B = Return to Game (the gamepad UI has no Return button; its footer says "B Return to Game").
 * While the game counts down a log out / exit, the menu shows the seconds and Cancel (Spells' gm_cancel: CancelLogout, never ForceQuit).
 * No Android in it (tools/home_test runs it).
 */
object GameMenuModel {
    const val ROW = "gamemenu"
    const val COL_INFO = 4
    const val COL_CANCEL = 5
    /** a Log Out / Exit pick waits this long for its confirming A */
    const val CONFIRM_MS = 4_000L

    /** one button: [key] the game's (InitButtons), [label] its own words, [on] false = greyed, [col] its secure cell or -1 (Escape) */
    data class Item(val key: String, val label: String, val on: Boolean, val col: Int)

    /** a game control's box in game px (the pad's px, top-left): Spells' gm3 `micro` and `targets` */
    data class Box(val x: Double, val y: Double, val w: Double, val h: Double) {
        val cx get() = x + w / 2.0
        val cy get() = y + h / 2.0
    }
    /** the entries opened through the game's own menu (gm3) */
    val GAME_OPEN = setOf("options", "addons", "support")
    fun box(o: JSONObject?): Box? {
        if (o == null) return null
        val w = o.optDouble("w", 0.0); val h = o.optDouble("h", 0.0)
        if (w <= 0 || h <= 0 || !o.has("x") || !o.has("y")) return null
        return Box(o.optDouble("x"), o.optDouble("y"), w, h)
    }
    /** the game's Menu micro button in a document (gm3), or null (an older add-on, or the player hid it: Escape then) */
    fun micro(doc: JSONObject?): Box? = box(doc?.optJSONObject("micro"))
    /** the box of the game's own button [key] in a "shown" document (gm3), or null */
    fun target(doc: JSONObject?, key: String): Box? = box(doc?.optJSONObject("targets")?.optJSONObject(key))

    /** the game's countdown: [kind] "logout" | "quit", [left] whole seconds */
    data class Count(val kind: String, val left: Int)

    /** what the view must do after an input */
    sealed class Act {
        object None : Act()
        /** B: back to the game, nothing sent */
        object Close : Act()
        /** a secure cell: tap it, then the menu goes (Macros / Edit Mode) or shows the countdown (Log Out / Exit) */
        data class Cell(val item: Item) : Act()
        /** no clean route: the menu goes and the game's own Escape opens its menu on the top screen */
        data class Escape(val item: Item) : Act()
        /**
         * gm3 (the user's pick A): Options / AddOns / Support through the GAME's own menu: the menu goes, the app taps the game's Menu
         * micro button ([micro], its box in game px) and then the game's own button for [item] once that menu shows
         */
        data class Game(val item: Item, val micro: Box) : Act()
        /** Log Out / Exit asked once: the line asks for the second A */
        data class Ask(val item: Item) : Act()
        /** the countdown's Cancel */
        object Cancel : Act()
    }

    /** InitButtons' sections: the game draws a gap after each group (and none after the last) */
    private val GROUPS = listOf(
        setOf("external"),
        setOf("options", "shop"),
        setOf("rewards", "addons", "whatsnew", "editmode", "ratings", "support", "macros"),
        setOf("logout", "quit"),
    )

    /** before the first document: the game's usual list on this client (no shop / rewards / what's new / ratings) */
    val DEFAULT: List<Item> = listOf(
        Item("options", "Options", true, -1), Item("addons", "AddOns", true, -1), Item("support", "Support", true, -1),
        Item("macros", "Macros", true, 2), Item("logout", "Log Out", true, 0), Item("quit", "Exit Game", true, 1),
    )

    /** Spells' document -> the buttons (the game's order; "return" dropped: B is Return to Game) */
    fun items(doc: JSONObject?): List<Item> {
        val a = doc?.optJSONArray("items") ?: return DEFAULT
        val out = ArrayList<Item>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val key = o.optString("key")
            if (key.isEmpty() || key == "return") continue
            out += Item(key, o.optString("t", key), o.optBoolean("on", true), if (o.has("col")) o.optInt("col", -1) else -1)
        }
        return if (out.isEmpty()) DEFAULT else out
    }

    /** the countdown in a document, or null (none running / it ended) */
    fun count(doc: JSONObject?): Count? {
        val k = doc?.optString("count").orEmpty()
        if (k != "logout" && k != "quit") return null
        return Count(k, doc!!.optInt("left", 0))
    }

    /** the drawn rows: the buttons with null for each section gap between groups */
    fun rows(items: List<Item>): List<Item?> {
        val out = ArrayList<Item?>()
        var lastGroup = -1
        for (it in items) {
            val g = GROUPS.indexOfFirst { s -> it.key in s }.let { g -> if (g < 0) 2 else g }
            if (lastGroup >= 0 && g != lastGroup) out += null
            out += it
            lastGroup = g
        }
        return out
    }

    /** the countdown's words (the game's own box says the same in its words) */
    fun countLine(c: Count): String = (if (c.kind == "quit") "Exiting the game in " else "Logging out in ") + c.left + if (c.left == 1) " second" else " seconds"

    /**
     * THE MENU'S STATE: the cursor (an index into [rows], always on a button that is not greyed when one exists), a pending Log Out /
     * Exit question, and the game's countdown. D-pad up / down move and WRAP (the game's Smart Navigation jump from the first to the
     * last); A presses; B returns to the game.
     */
    class Flow(items: List<Item>, private var microBox: Box? = null) {
        var rows: List<Item?> = rows(items); private set
        var cursor = first(); private set
        /** the Log Out / Exit asked (its key) and when, else null */
        var asking: String? = null; private set
        private var askedAt = 0L
        var count: Count? = null; private set

        private fun first(): Int = rows.indexOfFirst { it != null && it.on }.let { if (it < 0) rows.indexOfFirst { r -> r != null } else it }

        /** a new document: the list (the cursor stays on the same button when it is still there) and the countdown */
        fun update(doc: JSONObject?) {
            val key = rows.getOrNull(cursor)?.key
            rows = rows(items(doc))
            microBox = micro(doc)
            cursor = rows.indexOfFirst { it != null && it.key == key && it.on }.let { if (it >= 0) it else first() }
            if (doc?.has("count") == true || doc?.has("ended") == true) count = count(doc)
            if (count != null) asking = null
        }

        /** up (-1) / down (+1): the next button that is not greyed, wrapping; true = moved */
        fun move(by: Int): Boolean {
            if (count != null) return false
            val n = rows.size
            if (n == 0) return false
            var i = cursor
            repeat(n) {
                i = ((i + by) % n + n) % n
                val r = rows[i]
                if (r != null && r.on) { if (i != cursor) { cursor = i; asking = null; return true }; return false }
            }
            return false
        }

        /** A (or a finger on row [at]): the button's route; Log Out / Exit ask first */
        fun a(now: Long, at: Int = cursor): Act {
            if (count != null) return Act.Cancel
            val it = rows.getOrNull(at) ?: return Act.None
            if (!it.on) return Act.None
            if (at != cursor) { cursor = at; asking = null }
            if (it.col < 0) {
                val m = microBox
                return if (it.key in GAME_OPEN && m != null) Act.Game(it, m) else Act.Escape(it)
            }
            if (it.key == "logout" || it.key == "quit") {
                if (asking != it.key || now - askedAt > CONFIRM_MS) { asking = it.key; askedAt = now; return Act.Ask(it) }
                asking = null
            }
            return Act.Cell(it)
        }

        /** B: on the Log Out / Exit question = No (the question goes, the menu stays; the user's "A = Yes, B = No"); else back to the game */
        fun b(): Act {
            if (asking != null) { asking = null; return Act.None }
            return Act.Close
        }

        /** the line under the menu: the question, else nothing */
        fun askLine(now: Long): String? {
            val k = asking ?: return null
            if (now - askedAt > CONFIRM_MS) { asking = null; return null }
            return (if (k == "quit") "Exit Game?" else "Log Out?") + "  ${Glyphs.name("a")} = Yes   ${Glyphs.name("b")} = No"
        }
    }
}
