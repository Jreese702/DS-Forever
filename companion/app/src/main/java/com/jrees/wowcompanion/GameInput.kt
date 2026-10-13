package com.jrees.wowcompanion

import android.content.Context
import org.json.JSONObject
import java.io.DataOutputStream
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * EVERYTHING THE APP SENDS TO THE GAME goes through here. Any room may call it.
 *
 *   GameInput.key("F10")                    one key, by binding name (see KEYMAP)
 *   GameInput.tap(context, pad, op, col)    one tap on the addon's pad (TapPad.lua)
 *   GameInput.reload()                      the Settings room's Reload (F12, bound in Core.lua)
 *   GameInput.chat("/say hello")            typed chat: THE CHAT COMPOSER ONLY (user ruling)
 *
 * Each returns false, and sends nothing, when it is not safe to send.
 *
 * THE LOCKS THIS FILE KEEPS (AGENT_LOG "Rules for the Thor"):
 *  - One touch = one game action. One call here sends one key or one tap.
 *  - An Esc may come first, ONLY when the addon reports that a text box has the keyboard. A blind
 *    Esc opens the Game Menu, or cancels a cast, or drops the target.
 *  - Nothing is sent unless the game is provably in the world ([InputGate]).
 *  - ONE serial worker. Two rooms' sends can never interleave into a sequence. It used to be a
 *    new thread and a new `su` per send, and a Cast key could land between the keys of a command.
 *  - `-d 0` everywhere: the companion holds focus on display 4, and a bare `input` would deliver
 *    to itself. Never keyevent 4 (BACK): GameNative takes it as suspend.
 */
object GameInput {
    private val worker = Executors.newSingleThreadExecutor()
    val gate = InputGate()

    /** true while the game has a text box focused: `full.ui.typing` (Bags.lua) or `hub.typing` */
    @Volatile var typing = false
        private set

    /** where refusals and failures are shown (the hub's status line); called on any thread */
    @Volatile var onStatus: (String) -> Unit = {}

    /** the keybinds an action bar actually uses, mapped to Android keycodes */
    val KEYMAP: Map<String, Int> = buildMap {
        put("1", 8); put("2", 9); put("3", 10); put("4", 11); put("5", 12)
        put("6", 13); put("7", 14); put("8", 15); put("9", 16); put("0", 7)
        put("-", 69); put("=", 70)
        for (i in 0 until 26) put(('A' + i).toString(), 29 + i)
        // KEYCODE_F1 is 131. This was 130 + ..., which sent KEYCODE_MEDIA_RECORD for F1 and
        // every F-key one too low ("F11" pressed F10).
        for (i in 1..12) put("F$i", 131 + (i - 1))
    }

    /**
     * Every document from the addon passes through here first (MainActivity.handleMessage).
     * Returns true when the document is CURRENT, i.e. not replayed from the backlog.
     */
    fun note(msg: JSONObject, now: Long = System.currentTimeMillis()): Boolean {
        val current = gate.note(now, msg.optLong("_gt", -1L), wallYearMs())
        // A replayed document is in order, so the last one read is still the latest known state.
        val ui = if (msg.optString("type") == "full") msg.optJSONObject("ui") else null
        if (ui != null && ui.has("typing")) typing = ui.optBoolean("typing", false)
        val hub = if (msg.optString("type") == "hub") msg.optJSONObject("hub") else null
        if (hub != null && hub.has("typing")) typing = hub.optBoolean("typing", false)
        return current
    }

    fun live(now: Long = System.currentTimeMillis()) = gate.live(now)

    /** One key, by binding name. False when the name is unknown or the game is not live. */
    fun key(name: String): Boolean {
        val code = KEYMAP[name] ?: return false
        return keycode(code)
    }

    /** Home's pause wheel, Game Menu (the user 2026-10-09, "Escape opens it"): ONE Escape press, the game's own binding opens its menu */
    fun escape(): Boolean = keycode(111)

    /** the Settings room's Reload: F12, which Core.lua binds to ReloadUI() */
    fun reload(): Boolean = keycode(142)

    private fun keycode(code: Int): Boolean {
        if (!ready()) return false
        val cmds = ArrayList<String>()
        if (typing) { cmds.add("input -d 0 keyevent 111"); cmds.add("sleep 0.3") }
        cmds.add("input -d 0 keyevent $code")
        run(cmds)
        return true
    }

    /**
     * One tap on cell [op, col] of the addon's pad. A tap needs no Esc first: it arrives as a
     * mouse click, open chat box or not. The geometry is TapBridge's (quests session).
     *
     * ONE QUEUE FOR EVERY PAD TAP (2026-10-02, the user: "If I open the chat menu and then go to bags the home button shows blue"):
     * this used to send through THIS object's own worker while every other room tapped through TapBridge's, so two queues raced -
     * Bags' take could reach the capture before Chat's free and was refused "busy", and nobody held. It now goes through
     * TapBridge.tap: the same gate, the same geometry, and the same single worker, so taps land in the order the app made them.
     */
    fun tap(ctx: Context, pad: JSONObject?, op: String, col: Int): Boolean {
        if (!ready()) return false
        return TapBridge.tap(ctx, pad, op, col)
    }

    /**
     * Typed chat, for the Chat room's composer and nothing else. MEASURED BROKEN on the Thor
     * (2026-09-27): the Enter lands and opens the box, then the letters, the closing Enter and
     * the Esc are all lost. The cause is not measured yet (the chat session's probe will say).
     * It stays because the user ruled the composer keeps this route; every other room uses the
     * pad. There is no Esc at the end any more: it was sent blind.
     */
    fun chat(text: String): Boolean {
        if (!ready()) return false
        // The text goes to a ROOT shell inside double quotes, so everything the shell would act
        // on in there is escaped: what the player types is never a command.
        // Spaces MUST be %s: a raw space splits the argument and the command silently never runs.
        val esc = text.replace("\r", "").replace("\n", " ")
            .replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
            .replace("`", "\\`").replace(" ", "%s")
        run(listOf(
            "input -d 0 keyevent 66", "sleep 0.4",
            "input -d 0 text \"$esc\"", "sleep 0.3",
            "input -d 0 keyevent 66"))
        return true
    }

    private fun ready(): Boolean {
        val now = System.currentTimeMillis()
        if (gate.live(now)) return true
        onStatus("${gate.why(now)}: nothing was sent")
        return false
    }

    /** the commands of ONE game action as one batch on the app's one root shell (RootShell: no new su, no Magisk toast, per key) */
    private fun run(cmds: List<String>) {
        RootShell.run(cmds.joinToString("\n")) { r ->
            if (r.exit != 0) onStatus(if (r.exit == -1) "input failed: no root shell" else "input was refused (exit ${r.exit})")
        }
    }

    private fun wallYearMs(): Long {
        val c = Calendar.getInstance()
        return InputGate.yearMs(
            c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY),
            c.get(Calendar.MINUTE), c.get(Calendar.SECOND), c.get(Calendar.MILLISECOND))
    }
}
