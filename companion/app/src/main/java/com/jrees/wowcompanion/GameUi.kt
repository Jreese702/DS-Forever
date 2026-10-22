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
    /** hub13 HUD MODE: the game's player / target frames hidden, per character; the hub document's hud.hidden (null = no row) */
    private val hud = CastBarPolicy(CastBarPolicy.HUD_SHOW, CastBarPolicy.HUD_HIDE)
    @Volatile private var hudHidden: Boolean? = null
    /** hub15: HUD mode's bar place now (the hub document's prd: y, its range and step, Interact's WC.prd); null = no bar */
    @Volatile private var prdY: Int? = null
    @Volatile private var prdMin = -420
    @Volatile private var prdMax = -120
    @Volatile private var prdStep = 10
    @Volatile private var prdDef = -350
    /** hub16: the bar's width (spec px) and scale (%) now, their ranges, and which setting the step columns move ("y" / "w" / "s") */
    @Volatile private var prdW: Int? = null
    @Volatile private var prdS: Int? = null
    @Volatile private var prdSel: String? = null
    @Volatile private var wMin = 180; @Volatile private var wMax = 400; @Volatile private var wStep = 20; @Volatile private var wDef = 280
    @Volatile private var sMin = 70; @Volatile private var sMax = 160; @Volatile private var sStep = 10; @Volatile private var sDef = 100
    private var hudHTapAt = 0L
    private var hudHTapN = -1
    /** the character now ("Name-Realm", the host's from the padmap); null = not known yet: no HUD row */
    @Volatile var character: String? = null
        set(v) { if (field != v) { field = v; reconcile(); changed() } }
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
    fun onHello() { helloAt = System.currentTimeMillis(); policy.freshLogin(); castBar.freshLogin(); hud.freshLogin() }

    /** HUD mode for this character (the user's pick; off until chosen) */
    // (the user's new default: HUD mode ON for a character that never chose; a saved choice stays)
    fun hudOn(): Boolean = character?.let { prefs?.getBoolean("hud.$it", true) } ?: false
    fun setHud(on: Boolean) {
        val who = character ?: return
        prefs?.edit()?.putBoolean("hud.$who", on)?.apply()
        reconcile()
        changed()
    }
    /** the Settings row; null while the add-on does not offer it or the character is not known */
    fun hudRow(): Row? {
        val h = hudHidden ?: return null
        if (character == null) return null
        val on = hudOn()
        val want = hud.wantsHidden(on, visible)
        val note = if (h == want) (if (h) "Hidden now" else "On the top screen now") else "Sent"
        return Row(HUD_ID, "HUD mode", null, if (on) UiMode.ON else UiMode.OFF, true, h, note)
    }
    const val HUD_ID = "hudmode"

    /** hub15: HUD mode's bar place for this character (the player's choice; the bar's own until one is made), Interact's y px */
    fun hudHeight(): Int? {
        val who = character ?: return null
        val p = prefs?.getInt("prdy.$who", Int.MIN_VALUE) ?: Int.MIN_VALUE
        return if (p != Int.MIN_VALUE) p else prdY
    }
    /** one step down ([d] -1), up (+1), or back to the default (0); the taps follow until the game says it */
    fun stepHudHeight(d: Int) {
        val who = character ?: return
        val now = hudHeight() ?: return
        val next = if (d == 0) prdDef else (now + d * prdStep).coerceIn(prdMin, prdMax)
        prefs?.edit()?.putInt("prdy.$who", next)?.apply()
        reconcile()
        changed()
    }
    /** hub16: the width / scale for this character (the player's choice; the bar's own until one is made) */
    fun hudWidth(): Int? = character?.let { who -> prefs?.getInt("prdw.$who", Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE } } ?: prdW
    fun hudScale(): Int? = character?.let { who -> prefs?.getInt("prds.$who", Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE } } ?: prdS
    /** one step narrower / smaller ([d] -1), wider / larger (+1), or back to the default (0); the taps follow (the bar redraws live) */
    fun stepHudWidth(d: Int) {
        val who = character ?: return
        val now = hudWidth() ?: return
        prefs?.edit()?.putInt("prdw.$who", if (d == 0) wDef else (now + d * wStep).coerceIn(wMin, wMax))?.apply()
        reconcile(); changed()
    }
    fun stepHudScale(d: Int) {
        val who = character ?: return
        val now = hudScale() ?: return
        prefs?.edit()?.putInt("prds.$who", if (d == 0) sDef else (now + d * sStep).coerceIn(sMin, sMax))?.apply()
        reconcile(); changed()
    }
    /** the value a row shows on its right: the height in px under the centre, the width in px, the scale in % */
    fun hudValue(id: String): String = when (id) {
        HUD_H_ID -> prdY?.let { "${-it}" } ?: "-"
        HUD_W_ID -> prdW?.let { "$it" } ?: "-"
        HUD_S_ID -> prdS?.let { "$it%" } ?: "-"
        else -> ""
    }
    fun hudWidthRow(): Row? {
        if (hudRow() == null || !hudOn()) return null
        val w = prdW ?: return null
        val want = hudWidth() ?: w
        return Row(HUD_W_ID, "HUD width", null, UiMode.ON, true, false,
            (if (want == w) "$w px" else "$w px, going to $want") + (if (w == wDef) " (the default)" else " (default $wDef)"))
    }
    fun hudScaleRow(): Row? {
        if (hudRow() == null || !hudOn()) return null
        val sc = prdS ?: return null
        val want = hudScale() ?: sc
        return Row(HUD_S_ID, "HUD scale", null, UiMode.ON, true, false,
            (if (want == sc) "$sc %" else "$sc %, going to $want %") + (if (sc == sDef) " (the default)" else " (default $sDef %)"))
    }
    const val HUD_W_ID = "hudwidth"
    const val HUD_S_ID = "hudscale"
    /** the step the row shows: 0 = the lowest place */
    fun hudHeightStep(): Int? = hudHeight()?.let { (it - prdMin) / prdStep }
    fun hudHeightSteps(): Int = (prdMax - prdMin) / prdStep
    /** the Settings row under HUD mode, while HUD mode is on and Interact's bar is there */
    fun hudHeightRow(): Row? {
        if (hudRow() == null || !hudOn()) return null
        val y = prdY ?: return null
        val want = hudHeight() ?: y
        val st = (y - prdMin) / prdStep; val n = hudHeightSteps()
        return Row(HUD_H_ID, "HUD height", null, UiMode.ON, true, false,
            (if (want == y) "Step $st of $n" + (if (y == prdDef) " (the default)" else "") else "Step $st of $n, going to ${(want - prdMin) / prdStep}") +
                ", ${-y} px under the centre")
    }
    const val HUD_H_ID = "hudheight"

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
        hudHidden = h.optJSONObject("hud")?.let { if (it.has("hidden")) it.optBoolean("hidden") else null }
        val prd = h.optJSONObject("prd")
        prdY = prd?.let { if (it.has("y")) it.optInt("y") else null }
        if (prd != null) {
            prdMin = prd.optInt("min", prdMin); prdMax = prd.optInt("max", prdMax)
            prdStep = prd.optInt("step", prdStep).coerceAtLeast(1); prdDef = prd.optInt("def", prdDef)
            // hub16: the width and the scale (absent from an older add-on: no rows)
            prdW = if (prd.has("w")) prd.optInt("w") else null
            prdS = if (prd.has("s")) prd.optInt("s") else null
            prdSel = if (prd.has("sel")) prd.optString("sel") else null
            wMin = prd.optInt("wmin", wMin); wMax = prd.optInt("wmax", wMax); wStep = prd.optInt("wstep", wStep).coerceAtLeast(1); wDef = prd.optInt("wdef", wDef)
            sMin = prd.optInt("smin", sMin); sMax = prd.optInt("smax", sMax); sStep = prd.optInt("sstep", sStep).coerceAtLeast(1); sDef = prd.optInt("sdef", sDef)
        } else { prdW = null; prdS = null; prdSel = null }
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
            // hub13: HUD mode's own tap, never in the same moment as another (one tap at a time); no character known = nothing
            else if (character != null) {
                val hc = hud.step(hudHidden, hudOn(), visible, s, live, now, helloAt + GameUiPolicy.SETTLE_MS)
                if (hc != null) GameInput.tap(ctx, pad, ROW, hc)
                else {
                    // hub15 / hub16: HUD height, then width, then scale - one step a tap toward the choice; the next only after the
                    // game's answer (or 1.5 s). The step columns move the setting the add-on has SELECTED (its doc's sel): when it is
                    // not the one to move, the tap is column 39 (the next setting) first
                    data class Want(val sel: String, val cur: Int?, val want: Int?, val def: Int, val step: Int)
                    val todo = listOf(Want("y", prdY, hudHeight(), prdDef, prdStep), Want("w", prdW, hudWidth(), wDef, wStep),
                        Want("s", prdS, hudScale(), sDef, sStep)).firstOrNull { it.cur != null && it.want != null && it.cur != it.want }
                    if (todo != null && s != null && live && s.current && s.hasRow &&
                        (s.n != hudHTapN || now - hudHTapAt > 1500L) && now >= helloAt + GameUiPolicy.SETTLE_MS) {
                        hudHTapN = s.n; hudHTapAt = now
                        val cur = todo.cur!!; val want = todo.want!!
                        val selNow = prdSel ?: "y"
                        // the default is one tap (col 38); otherwise one step toward the choice
                        val col = if (selNow != todo.sel) CastBarPolicy.HUD_SEL
                            else if (want == todo.def && kotlin.math.abs(want - cur) > todo.step) CastBarPolicy.HUD_HDEF
                            else if (want > cur) CastBarPolicy.HUD_HIGHER else CastBarPolicy.HUD_LOWER
                        GameInput.tap(ctx, pad, ROW, col)
                    }
                }
            }
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
