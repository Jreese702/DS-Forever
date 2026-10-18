package com.jrees.wowcompanion

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * GAME UI ON THE TOP SCREEN: the modes the user picked, what the game shows now, and the one tap
 * that brings the two together. The rules are in [GameUiPolicy]; the addon side is Hub.lua.
 *
 *   GameUi.mode("minimap") / GameUi.setMode("minimap", UiMode.DYNAMIC)
 *   GameUi.rows()            what the Settings section draws
 *   GameUi.onChange { }      a mode, or the game's state, changed
 *
 * The hub calls [onHub], [onRoom], [onVisible] and [tick]; rooms do not.
 *
 * A room that replaces another element of the game's UI adds it in two places: a row in
 * [UiElements.KNOWN] here, and `WC.hub.element(id, ...)` in its own addon file.
 */
object GameUi {
    private var prefs: SharedPreferences? = null
    private var appCtx: Context? = null
    private val listeners = ArrayList<() -> Unit>()

    private val policy = GameUiPolicy({ tracking }) { id -> mode(id) }
    /** hub11: the game's cast bar (its own Show / Hide, not a layout bit) */
    private val castBar = CastBarPolicy()
    /** the hub document's cb.hidden; null = an add-on without it (no row) */
    @Volatile private var castBarHidden: Boolean? = null
    /** LATER 25: one of the game's tracking abilities is on (the map document's track: h / m / t = 1) */
    @Volatile private var tracking = false
    /** the map document's `track` part arrived ({h, m, t}: 1 = on); a change re-checks the layout (the "While tracking" minimap) */
    fun onTracking(track: JSONObject?) {
        val on = track != null && (track.optInt("h") == 1 || track.optInt("m") == 1 || track.optInt("t") == 1)
        if (on == tracking) return
        tracking = on
        tick()
    }
    private var state: HubState? = null
    private var pad: JSONObject? = null
    private var room: Room = Room.MAP
    private var visible = false
    private var last: UiStep = UiStep.Wait("Waiting for the game")

    fun init(c: Context) {
        appCtx = c.applicationContext
        if (prefs == null) prefs = c.applicationContext.getSharedPreferences("gameui", Context.MODE_PRIVATE)
    }

    fun mode(id: String): UiMode {
        val def = UiElements.of(id)?.default ?: UiMode.ON
        val s = prefs?.getString("mode.$id", null) ?: return def
        return try { UiMode.valueOf(s) } catch (_: Throwable) { def }
    }

    /** the user picked a mode: one touch, so at most one tap follows */
    fun setMode(id: String, m: UiMode) {
        prefs?.edit()?.putString("mode.$id", m.name)?.apply()
        reconcile()
        changed()
    }

    // ------------------------------------------------------------------ from the hub

    /** when the last CURRENT hello came (a login or a reload): the first hide waits for the loading screen (GameUiPolicy.SETTLE_MS) */
    @Volatile private var helloAt = 0L
    fun onHello() { helloAt = System.currentTimeMillis(); policy.freshLogin(); castBar.freshLogin() }

    /** f-top-castbar-toggle: the user's Hide for the game's cast bar. Default HIDE (1.0.10-test3, the user: "The cast bar needs to be
     *  hidden": Home's bottom bar shows every cast); a player who picked Show keeps it */
    fun castBarHide(): Boolean = prefs?.getBoolean("castbar.hide", true) ?: true
    /** one touch, so at most one tap follows */
    fun setCastBar(show: Boolean) {
        prefs?.edit()?.putBoolean("castbar.hide", !show)?.apply()
        reconcile()
        changed()
    }
    /** the Settings row; null while the game's add-on does not offer it */
    fun castBarRow(): Row? {
        val h = castBarHidden ?: return null
        val hide = castBarHide()
        val want = castBar.wantsHidden(hide, visible)
        val note = when {
            h == want -> if (h) "Hidden now" else "On the top screen now"
            last is UiStep.Tap -> "Waits for the layout first"
            else -> "Sent"
        }
        return Row(CASTBAR_ID, "Cast bar", null, if (hide) UiMode.OFF else UiMode.ON, true, h, note)
    }
    const val CASTBAR_ID = "castbar"

    /** a `hub` document; [current] is GameInput.note()'s answer for it */
    fun onHub(msg: JSONObject, current: Boolean) {
        val h = msg.optJSONObject("hub") ?: return
        val els = ArrayList<HubEl>()
        val arr = h.optJSONArray("els")
        if (arr != null) for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val id = e.optString("id", "")
            if (id.isEmpty() || !e.has("bit")) continue
            els.add(HubEl(id, e.optInt("bit", -1), e.optBoolean("hidden", false),
                          if (e.has("why")) e.optString("why") else null))
        }
        pad = h.optJSONObject("pad")
        castBarHidden = h.optJSONObject("cb")?.let { if (it.has("hidden")) it.optBoolean("hidden") else null }
        state = HubState(
            boot = h.optLong("boot", 0L), n = h.optInt("n", 0),
            want = if (h.has("want")) h.optInt("want") else null,
            pending = h.optBoolean("pending", false),
            hasRow = h.optString("row", "") == ROW && pad != null,
            els = els, current = current)
        reconcile()
        changed()
    }

    fun onRoom(r: Room) {
        if (r == room) return
        room = r
        reconcile()
        changed()
    }

    /** the companion came onto, or left, the bottom screen */
    fun onVisible(v: Boolean) {
        if (v == visible) return
        visible = v
        reconcile()
        changed()
    }

    /** once a second: the game becoming live, and a reply that never came */
    fun tick() {
        val before = last
        reconcile()
        if (last != before) changed()
    }

    private fun reconcile() {
        val ctx = appCtx ?: return
        val now = System.currentTimeMillis()
        val live = GameInput.live(now)
        // a state the game wrote before it went quiet says nothing about now
        val s = state?.let { if (live) it else it.copy(current = false) }
        val step = policy.step(s, room.name, visible, live, now, helloAt + GameUiPolicy.SETTLE_MS)
        if (step is UiStep.Tap && !GameInput.tap(ctx, pad, ROW, step.mask)) {
            last = UiStep.Wait("The game has not published its tap pad")
            return
        }
        last = step
        // hub11: the cast bar's own tap, never in the same moment as a layout tap (one tap at a time)
        if (step !is UiStep.Tap) {
            val col = castBar.step(castBarHidden, castBarHide(), visible, s, live, now, helloAt + GameUiPolicy.SETTLE_MS)
            if (col != null) GameInput.tap(ctx, pad, ROW, col)
        }
    }

    // ------------------------------------------------------------------ for the Settings section

    class Row(val id: String, val label: String, val home: Room?, val mode: UiMode,
              val known: Boolean, val hidden: Boolean, val note: String)

    /** the elements to list: what the game reports, and always the minimap */
    fun rows(): List<Row> {
        val s = state
        val out = ArrayList<Row>()
        for (info in UiElements.KNOWN) {
            val el = s?.els?.firstOrNull { it.id == info.id }
            if (el == null && info.id != "minimap") continue
            val m = mode(info.id)
            out.add(Row(info.id, info.label, roomOf(info.home), m, el != null,
                        el?.hidden == true, note(el, m)))
        }
        return out
    }

    private fun note(el: HubEl?, m: UiMode): String {
        val step = last
        if (el == null) {
            return if (state == null) "Waiting for the game"
                   else "The game does not offer this yet"
        }
        val s = state ?: return "Waiting for the game"
        val wantHidden = policy.wantsHidden(el, room.name, visible)
        if (el.hidden == wantHidden) return if (el.hidden) "Hidden now" else "On the top screen now"
        return when {
            step is UiStep.Wait && s.pending -> "Changes when combat ends"
            step is UiStep.Wait && el.why != null && step.why == "The game did not change it" ->
                "The game refused: ${el.why}"
            step is UiStep.Wait -> step.why
            else -> "Sent"
        }
    }

    private fun roomOf(name: String): Room? = try { Room.valueOf(name) } catch (_: Throwable) { null }

    fun onChange(l: () -> Unit) { listeners.add(l) }
    private fun changed() { for (l in listeners) l() }

    private const val ROW = "ui"      // Hub.lua ROW
}
