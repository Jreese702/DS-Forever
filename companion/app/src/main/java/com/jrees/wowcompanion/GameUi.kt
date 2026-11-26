package com.jrees.wowcompanion

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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

    /** split-hud: the layout bit of the "hud" element carries the three parts' riding mode ([hudBitMode]) */
    private val policy = GameUiPolicy({ tracking }) { id -> if (id == HudSplit.CONTROLLER_ID) hudBitMode() else effMode(id) }
    /** hub11: the game's cast bar (its own Show / Hide, not a layout bit) */
    private val castBar = CastBarPolicy()
    /** hub67 (PR #532 reworked): the ONE restore tap per load; the hub document's `rst` (null = an add-on set without it) */
    private val restore = RestorePolicy()
    @Volatile private var rst: HubRestore? = null
    /** hub13 HUD MODE: the game's player / target frames hidden; the hub document's hud.hidden (hub67: its Show column is RESTORE now) */
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
    /** hub55 (the layout editor; Trail prd18): the layout owns the HUD's height and scale - the tap loop leaves them alone (the width
     *  stays a row); false = an older add-on (the loop as before) */
    @Volatile var prdLay = false; private set
    /** PR #533: the HUD height / width / scale step's own back-off (one key, [HUD_KEY]) */
    private val hudBack = TapBackoff()
    /** the character now ("Name-Realm", the host's from the padmap); null = not known yet: no HUD row */
    @Volatile var character: String? = null
        set(v) { if (field != v) { field = v; playerClass = classFor(v); reconcile(); changed() } }    // Smart Res: the new character's class (its party document, before or after)
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
        if (prefs?.getBoolean(LOAD_REPORTS_KEY, false) == true) GameInput.load.reports = true
    }

    /** the add-ons reported a loading screen: from now on a login's taps wait for its end too ([LoadGate.reports], kept) */
    fun loadReported() {
        if (GameInput.load.reports) return
        GameInput.load.reports = true
        prefs?.edit()?.putBoolean(LOAD_REPORTS_KEY, true)?.apply()
    }

    fun mode(id: String): UiMode {
        val def = UiElements.of(id)?.default ?: UiMode.ON
        val saved = prefs?.getString("mode.$id", null)?.let { s -> runCatching { UiMode.valueOf(s) }.getOrNull() }
        // split-hud: the buffs and the XP bar rode the controller bars' mode until the split; until one is picked it still comes from there
        if (id in HudSplit.EXTRA) return HudSplit.migrate(saved, mode(HudSplit.CONTROLLER_ID))
        return saved ?: def
    }

    /**
     * SINGLE SCREEN MODE (SingleScreen, 11-25): the mode that decides the GAME's element now - SingleUi's preset while the mode is on,
     * else the saved one ([mode], which Settings draws). Never saved: the player's choices come back as they were with two screens
     */
    fun effMode(id: String): UiMode {
        val saved = mode(id)
        return if (SingleScreen.inUse) SingleUi.mode(id, saved) else saved
    }

    /**
     * single screen mode's switch (DsForeverScreen.toggleSingle, the new host's onStart): no top-screen tap before [untilMs] (wall ms),
     * so the old host's away and the new host's back are ONE reconcile after it (status [SWITCHING]); a later call replaces it
     */
    fun holdTaps(untilMs: Long) { tapHoldUntil = untilMs }
    fun tapsHeld(now: Long): Boolean = now < tapHoldUntil
    /** the hold now (wall ms): a parked bottom screen holds every tap and gives this back at its return (CompanionHost.park) */
    fun tapHold(): Long = tapHoldUntil
    @Volatile private var tapHoldUntil = 0L
    const val SWITCHING = "Switching screens"

    /** the user picked a mode: one touch, so at most one tap follows */
    fun setMode(id: String, m: UiMode) {
        val ed = prefs?.edit() ?: return
        // split-hud: a part picked = the parts are separate from now on: the others keep the mode they show now (never follow this one)
        if (id in HudSplit.PARTS) for (o in HudSplit.EXTRA) if (o != id && prefs?.contains("mode.$o") != true) ed.putString("mode.$o", mode(o).name)
        ed.putString("mode.$id", m.name).apply()
        reconcile()
        changed()
    }

    // ------------------------------------------------------------------ split-hud: the controller UI, the buffs, the XP bar

    /** the hub document's `hs` (Hub.lua hs2): each part's word ("on" / "off" / "ride"), each part hidden now, the bit, the columns;
     *  null = an add-on set before the split (the controller tile alone, the bit as before) */
    data class Hs(val words: Map<String, String>, val hidden: Map<String, Boolean>, val bit: Boolean, val n: Int, val cols: Map<String, Int>)
    @Volatile private var hs: Hs? = null

    fun hsFrom(o: JSONObject?): Hs? = o?.let { h ->
        val cols = HashMap<String, Int>()
        h.optJSONObject("cols")?.let { c -> for (k in c.keys()) cols[k] = c.optInt(k, -1) }
        val words = HashMap<String, String>(); val hid = HashMap<String, Boolean>()
        for ((id, l) in HudSplit.LETTER) {
            words[id] = HudSplit.norm(if (h.has(l) && !h.isNull(l)) h.optString(l) else null)
            hid[id] = h.optBoolean(l + "h", false)
        }
        Hs(words, hid, h.optBoolean("bit", false), h.optInt("n", 0), cols)
    }

    /** the three parts' modes now */
    private fun partModes(): Map<String, UiMode> = HudSplit.PARTS.associateWith { effMode(it) }
    /** the mode the "hud" bit carries: the parts' riding one; an add-on before the split: the controller tile's, exactly as before */
    fun hudBitMode(): UiMode = if (hs == null) effMode(HudSplit.CONTROLLER_ID) else HudSplit.riding(partModes().values)
    /** each part's word for the add-on */
    fun hsWant(modes: Map<String, UiMode> = partModes()): Map<String, String> {
        val r = HudSplit.riding(modes.values)
        return modes.mapValues { (_, m) -> HudSplit.word(m, r) }
    }
    /** the split's tap on "lay" now, or null (the add-on's words already the chosen ones) */
    fun hsCol(h: Hs, modes: Map<String, UiMode> = partModes()): Int? = HudSplit.col(h.cols, h.words, hsWant(modes))

    private val PART_LABELS = mapOf(HudSplit.BUFFS_ID to "Buffs", HudSplit.XP_ID to "XP bar")

    /** a part's tile row (the controller UI's too, once the add-on has the split); null = no such part / the controller with an older set */
    fun partRow(id: String): Row? {
        if (id !in HudSplit.PARTS) return null
        val info = UiElements.of(HudSplit.CONTROLLER_ID) ?: return null
        val el = state?.els?.firstOrNull { it.id == info.id }
        val m = mode(id)
        val label = PART_LABELS[id] ?: info.label
        val home = roomOf(info.home)
        val h = hs
        if (h == null && id == HudSplit.CONTROLLER_ID) return null
        if (h == null || el == null)
            return Row(id, label, home, m, el != null, false, if (state == null) "Waiting for the game" else "The game does not offer this yet")
        val now = h.hidden[id] == true
        if (id == HudSplit.CONTROLLER_ID && m == UiMode.NINTENDO) return Row(id, label, home, m, true, now, nbarNote(sw?.nbar))
        val want = HudSplit.wantsHidden(effMode(id), room.name, visible)
        val steady = if (now) "Hidden now" else "On the top screen now"
        val note = when {
            now == want -> steady
            HudSplit.norm(h.words[id]) != hsWant()[id] -> "Sent"
            // the word stands: the bit's own state says (sent, held for combat, refused ...)
            else -> note(el, hudBitMode()).let { n -> if (n == "Hidden now" || n == "On the top screen now") steady else n }
        }
        return Row(id, label, home, m, true, now, note)
    }

    // ------------------------------------------------------------------ from the hub

    /** when the last CURRENT hello came (a login or a reload): the first hide waits for the loading screen (GameUiPolicy.SETTLE_MS) */
    @Volatile private var helloAt = 0L
    /** when the top-screen taps may start: never under a loading screen, then SETTLE_MS after the hello or LOAD_SETTLE_MS after the
     *  loading screen went, whichever is later ([LoadGate]: the login's restore tap was lost under its loading screen) */
    private fun settleAt(): Long = GameInput.load.settleAt(System.currentTimeMillis(), helloAt, GameUiPolicy.SETTLE_MS, LOAD_SETTLE_MS)
    fun onHello() { helloAt = System.currentTimeMillis(); GameInput.load.hello(helloAt); policy.freshLogin(); castBar.freshLogin(); restore.freshLogin(); swBack.clear(); hudBack.clear() }

    /**
     * HUB17 THE HUD-MODE SPLIT (the user: the player frame, the target frame and the enemy nameplates on Top screen, the resource bar on
     * QOL; Interact's Hub.lua hub17): each part its own switch on the pad row "uisw", two ABSOLUTE columns each, the state in the hub
     * document's `sw`. The add-on keeps nothing: the app keeps each choice per character and taps it whenever the game's word differs or
     * the switch was not set since the game's last /reload (`sw.set`). Defaults (the user's): the player frame and the target frame
     * HIDDEN, the enemy nameplates ON, the resource bar ON. HUD mode's old columns 34 / 35 are not tapped any more.
     */
    data class Sw(val row: String, val cols: Map<String, Int>, val player: Boolean?, val target: Boolean?, val np: Boolean?, val prd: Boolean?,
                  val set: Map<String, Boolean>,
                  /** the world markers shown (the user: "the world markers should be a toggle, off by default"); null = an add-on without */
                  val wm: Boolean? = null,
                  /** hub19: the game's party frames shown; null = an add-on without the switch */
                  val party: Boolean? = null,
                  /** hub22: a new hostile target starts the attack; null = an add-on without the switch */
                  val atk: Boolean? = null,
                  /** hub22: the game's Auto Shot switching (autoRangedCombat), any class since hub23; null = an add-on without it */
                  val shot: Boolean? = null,
                  /** hub25: Party mode allowed (the experimental switch); null = an add-on without it */
                  val pmode: Boolean? = null,
                  /** hub26: DS Forever's own party frames / target bar on (the game's of that group hidden meanwhile); null = without */
                  val ufp: Boolean? = null, val uft: Boolean? = null,
                  /** uf8: the target bar's target of target on; null = without */
                  val uftot: Boolean? = null,
                  /** uf11: the target bar's health text, percent / total (both off = blank); null = without */
                  val ufhpp: Boolean? = null, val ufhpt: Boolean? = null,
                  /** hub28: Combat Auto Shot only in fights; null = an add-on without it. [shotfk]: the last fight's write took (false =
                   *  the game refused it in the fight), null = no fight yet */
                  val shotf: Boolean? = null, val shotfk: Boolean? = null,
                  /** hub31: Polestar's own windows hidden on the top screen; null = an add-on without the switch */
                  val ps: Boolean? = null,
                  /** hub33: the pet's strip under the resource bar on (Trail.lua prd11); null = an add-on without the switch */
                  val pet: Boolean? = null,
                  /** hub45 (issue #195): DS Forever's Nintendo bar in the stock gamepad bar's place on; null = an add-on without it */
                  val nbar: Boolean? = null,
                  /** hub47 (the user, 2026-11-13): Smart Res on; null = an add-on without the switch */
                  val sres: Boolean? = null,
                  /** hub52 (the user, 2026-11-13): friendly plates while a friend is targeted (Trail ag10); null = without the switch */
                  val npf: Boolean? = null)
    @Volatile private var sw: Sw? = null
    private var swTapAt = 0L
    /** PR #533 (mummigast): a switch the game never answers backs off and is parked, so the ones after it still go ([TapBackoff]) */
    private val swBack = TapBackoff()
    /** parts: "player" / "target" shown (true) or hidden; "np" nameplates on; "prd" the resource bar on */
    const val SW_PLAYER = "player"; const val SW_TARGET = "target"; const val SW_NP = "np"; const val SW_PRD = "prd"; const val SW_WM = "wm"
    const val SW_PARTY = "party"
    const val SW_ATK = "atk"; const val SW_SHOT = "shot"
    /** hub28 (the user, 2026-11-10: "add a third toggle to the hunter option to basically only turn it on during combat"): Combat Auto
     *  Shot's third choice, kept as its own switch (off by default): while on, the add-on turns the game's option on as a fight starts
     *  and off when it ends, and SW_SHOT is not reconciled (the game's word flips with each fight) */
    const val SW_SHOTF = "shotf"
    /** hub25: Party mode, one choice for the whole app (not per character). ON BY DEFAULT since 1.0.26 (the user, 2026-11-12: "Party
     *  mode is ready to be a default on feature instead of experimental"; it was an experimental switch, off by default, from 2026-11-07).
     *  A choice already made (the key present) is kept, so a player who turned it off stays off */
    const val SW_PMODE = "pmode"
    /** hub26 (the user, 2026-11-09: our own party frames and a target bar under the compass; the picks "replaces the game's"): on by default */
    const val SW_UFP = "ufp"; const val SW_UFT = "uft"
    /** uf8 (the user, 2026-11-09: "I want the target of target to be a toggle for this and turned off by default") */
    const val SW_UFTOT = "uftot"
    /** uf11 (the user: "toggles for health total, Percentage and blank with blank as the default"): one row of three, kept as two switches */
    const val SW_UFHPP = "ufhpp"; const val SW_UFHPT = "ufhpt"
    /** hub31 (the user, 2026-11-11: "one other setting to hide the polestar UI from the top"): on = Polestar's windows hidden (Hub.lua
     *  hub31 / hub36: every top-level Polestar frame at alpha 0, nothing of Polestar's own changed, so its guide, arrow data and steps
     *  still run and the Polestar tab / quest tracker keep driving it; hub72, the user's "Also make it ignore touches": their mouse
     *  input off too, given back exactly at Off). HIDDEN BY DEFAULT since 2026-11-20 (the user: "does polestar show
     *  on the top screen by default? we should hide that by default"); it was shown by default from 2026-11-11. Existing players: the
     *  key "sw.ps.<character>" is only written by the player's own tap on Settings > Top screen > Navigation > Polestar ([setSwitch]),
     *  so a character with no key (never chose) moves to hidden, and a character whose key says shown (false: they turned it On) keeps
     *  it shown; no migration key. Reset > Layouts ("sw.*.") takes it back to hidden ([ResetPlan]) */
    const val SW_PS = "ps"
    /** the Polestar switch's default: hidden (see [SW_PS]) */
    const val SW_PS_DEFAULT_HIDDEN = true
    /** hub33 (the user, 2026-11-11: "find a way to include Pet HP in the HUD, and better distinguish the player HUD vs Pet HUD"): the pet's
     *  strip under the resource bar (mock-up A, "Sub-strip under"); on by default */
    const val SW_PET = "pet"
    /** hub45 (issue #195, the user 2026-11-12: a "Nintendo" choice next to On / Off / Dynamic for the top screen's action bar): the
     *  Nintendo bar's switch. Not a choice of its own: it follows the "hud" element's mode (wanted exactly while that mode is NINTENDO),
     *  so it is kept where the element's mode is kept (app-wide, as every element's mode) */
    const val SW_NBAR = "nbar"
    /** hub47 SMART RES (the user, 2026-11-13: "There should be an enable smart res toggle setting for party mode to enable pressing dead
     *  team members on touch if they are dead and I am playing a class with a res. Similarly a revive pet would be neat."): a tap on a dead
     *  party member casts the class's res (PadMap m150); per character (the res is the class's). ON BY DEFAULT since 2026-11-13 (the
     *  user's pick: "Smart Res is ON by default, still saved per character (for classes with a res or Revive Pet; a character whose
     *  class has neither shows it off/hidden)"); in Party mode or not, as built */
    const val SW_SRES = "sres"
    /** hub52 FRIENDLY PLATES (the user, 2026-11-13: "friendly plates off ... maybe we should set this as a toggle defaulted to off"): On =
     *  Trail's old rule (ag2..ag8: the game's friendly nameplates while a friend is targeted, out of a fight, put back after); Off = ag9
     *  (nothing switched on). Per character, OFF BY DEFAULT */
    const val SW_NPF = "npf"
    /** the classes Smart Res has something for: a res (Priest, Paladin, Shaman, Druid's Rebirth) or Revive Pet (Hunter); PadMap's RES_BY */
    val SRES_CLASSES = setOf("PRIEST", "PALADIN", "SHAMAN", "DRUID", "HUNTER")
    /** the class (UnitClass's file token) of [character] from the CURRENT party document's own entry; null = not known yet */
    @Volatile var playerClass: String? = null
        private set
    /** Smart Res for [cls]: true = a res / Revive Pet, false = neither (the row hidden, the switch off), null = the class not known */
    fun sresClass(cls: String?): Boolean? = cls?.takeIf { it.isNotBlank() }?.let { it.uppercase() in SRES_CLASSES }
    /** the host's current party document: you are its "player" entry; a class for another name (a stale document) is not taken */
    fun partySeen(p: PartyModel.Party) {
        val me = p.m.firstOrNull { it.u == "player" } ?: return
        // review 2026-11-13: kept even with no character yet: a party document that comes before the padmap's word (a login, a character
        // switch) would otherwise leave the class unknown (the row shown On for a Warrior) until the add-on's next party document
        seenMe = me.n to me.c
        val who = character ?: return
        val cls = classFor(who)
        if (cls != playerClass) { playerClass = cls; reconcile(); changed() }
    }
    /** the last current party document's own entry (name, class token); null = none seen */
    @Volatile private var seenMe: Pair<String, String>? = null
    /** [who]'s class from that entry: only when its name is [who]'s (a stale document for another character says nothing) */
    private fun classFor(who: String?): String? {
        val me = seenMe ?: return null
        if (who == null || me.first != who.substringBefore('-')) return null
        return me.second.takeIf { it.isNotBlank() }
    }
    /** tests: forget the party document's entry */
    fun resetSeenForTest() { seenMe = null; playerClass = null }
    // hub19: the party frames; ON by default since 2026-11-05 (the game hides health from add-ons: its own frames are the real health)
    private val SW_DEFAULT = mapOf(SW_PLAYER to false, SW_TARGET to false, SW_NP to true, SW_PRD to true, SW_WM to false, SW_PARTY to true,
        // hub22: start attack off until chosen; Auto Shot follows the game's own default (on) unless chosen
        SW_ATK to false, SW_SHOT to true, SW_PMODE to true, SW_UFP to true, SW_UFT to true, SW_UFTOT to false, SW_UFHPP to false, SW_UFHPT to false, SW_SHOTF to false, SW_PS to SW_PS_DEFAULT_HIDDEN,
        SW_PET to true, SW_SRES to true, SW_NPF to false)
    /** this character's choice for [part] (its default until one is made); the resource bar's old "HUD mode" choice carries over */
    fun swWant(part: String): Boolean {
        if (part == SW_PMODE) return prefs?.getBoolean("sw.$SW_PMODE", true) ?: true
        if (part == SW_NBAR) return nbarWanted(effMode("hud"))
        // 2026-11-13 (the user's pick): a class with neither a res nor Revive Pet: off, whatever was saved
        if (part == SW_SRES && sresClass(playerClass) == false) return false
        val who = character ?: return SW_DEFAULT[part] ?: false
        val def = if (part == SW_PRD) prefs?.getBoolean("hud.$who", true) ?: true else SW_DEFAULT[part] ?: false
        return prefs?.getBoolean("sw.$part.$who", def) ?: def
    }
    /**
     * Issue #197 ("Not portrait for the target even when enabled": the player turned the game's Target frame on and it stayed hidden):
     * while DS Forever's target bar is on, the add-on keeps the game's target frame hidden (hub26, the user's pick "replaces the game's"),
     * and the two switches sit on different tabs. Now they are one choice: turning the game's Target frame ON turns the target bar off,
     * and turning the target bar ON hides the game's frame again. The other switch to write for [part] [on], or null.
     */
    fun swLinked(part: String, on: Boolean): Pair<String, Boolean>? = when {
        part == SW_TARGET && on -> SW_UFT to false
        part == SW_UFT && on -> SW_TARGET to false
        else -> null
    }
    fun setSwitch(part: String, on: Boolean) {
        if (part == SW_PMODE) { prefs?.edit()?.putBoolean("sw.$SW_PMODE", on)?.apply(); reconcile(); changed(); return }
        val who = character ?: return
        val ed = prefs?.edit()?.putBoolean("sw.$part.$who", on)
        swLinked(part, on)?.let { (p, v) -> ed?.putBoolean("sw.$p.$who", v) }
        ed?.apply()
        reconcile()
        changed()
    }
    /** the game's word for [part] now (null = not said / no add-on with it) */
    fun swNow(part: String): Boolean? = sw?.let { when (part) { SW_PLAYER -> it.player; SW_TARGET -> it.target; SW_NP -> it.np; SW_WM -> it.wm; SW_PARTY -> it.party; SW_ATK -> it.atk; SW_SHOT -> it.shot; SW_SHOTF -> it.shotf; SW_PMODE -> it.pmode
        SW_UFP -> it.ufp; SW_UFT -> it.uft; SW_UFTOT -> it.uftot; SW_UFHPP -> it.ufhpp; SW_UFHPT -> it.ufhpt; SW_PS -> it.ps; SW_PET -> it.pet; SW_NBAR -> it.nbar; SW_SRES -> it.sres; SW_NPF -> it.npf; else -> it.prd } }
    /** issue #195: the Nintendo bar is wanted exactly while the controller bars' mode is NINTENDO */
    fun nbarWanted(hudMode: UiMode): Boolean = hudMode == UiMode.NINTENDO
    /** the absolute column that makes [part] [on] */
    fun swCol(s: Sw, part: String, on: Boolean): Int? = s.cols[when (part) {
        SW_PLAYER -> if (on) "PLAYER_SHOW" else "PLAYER_HIDE"
        SW_TARGET -> if (on) "TARGET_SHOW" else "TARGET_HIDE"
        SW_NP -> if (on) "NP_ON" else "NP_OFF"
        SW_WM -> if (on) "WM_ON" else "WM_OFF"
        SW_PARTY -> if (on) "PARTY_SHOW" else "PARTY_HIDE"
        SW_ATK -> if (on) "ATK_ON" else "ATK_OFF"
        SW_SHOT -> if (on) "SHOT_ON" else "SHOT_OFF"
        SW_SHOTF -> if (on) "SHOTF_ON" else "SHOTF_OFF"
        SW_PMODE -> if (on) "PMODE_ON" else "PMODE_OFF"
        SW_UFP -> if (on) "UFP_ON" else "UFP_OFF"
        SW_UFT -> if (on) "UFT_ON" else "UFT_OFF"
        SW_UFTOT -> if (on) "UFTOT_ON" else "UFTOT_OFF"
        SW_UFHPP -> if (on) "UFHPP_ON" else "UFHPP_OFF"
        SW_UFHPT -> if (on) "UFHPT_ON" else "UFHPT_OFF"
        SW_PS -> if (on) "PS_HIDE" else "PS_SHOW"
        SW_PET -> if (on) "PET_ON" else "PET_OFF"
        SW_NBAR -> if (on) "NBAR_ON" else "NBAR_OFF"
        SW_SRES -> if (on) "SRES_ON" else "SRES_OFF"
        SW_NPF -> if (on) "NPF_ON" else "NPF_OFF"
        else -> if (on) "PRD_ON" else "PRD_OFF"
    }]
    /** the switch to tap now: the first whose game word differs from the choice, or which the game has not had since its /reload */
    fun swNext(s: Sw, want: (String) -> Boolean): Pair<String, Boolean>? = swNextSkip(s, emptySet(), want)
    /** the game's word for [p] in [s] */
    fun swWord(s: Sw, p: String): Boolean? = when (p) { SW_PLAYER -> s.player; SW_TARGET -> s.target; SW_NP -> s.np; SW_WM -> s.wm; SW_PARTY -> s.party
        SW_ATK -> s.atk; SW_SHOT -> s.shot; SW_SHOTF -> s.shotf; SW_PMODE -> s.pmode; SW_UFP -> s.ufp; SW_UFT -> s.uft; SW_UFTOT -> s.uftot; SW_UFHPP -> s.ufhpp; SW_UFHPT -> s.ufhpt; SW_PS -> s.ps; SW_PET -> s.pet; SW_NBAR -> s.nbar; SW_SRES -> s.sres; SW_NPF -> s.npf; else -> s.prd }
    /** PR #533: the game's answer to a tap of [p] - its word and its `set` flag (an answered tap moves one of them) */
    fun swSig(s: Sw?, p: String): Any? = s?.let { swWord(it, p) to it.set[p] }
    /** fix/1492-feedback: the add-on's last press count said ([landedNote]) and the last `off` line written */
    private var rxN = -1
    private var offSaid = ""
    /**
     * fix/1492-feedback (test 1492 streamed: the "ui" row's restore and layout taps never answered, the "uisw" row's did): WHERE THE
     * TAPS LANDED, in the tap log beside the taps sent - Hub hub69's `rx` (TapPad pad23's last presses, "row:col") whenever its count
     * moves, and pad23's `off` (rows whose cells do not stand where the grid says) when it changes. Logged only; nothing decided on it.
     */
    private fun landedNote(rx: JSONObject?, off: JSONObject?, current: Boolean, offToo: Boolean = true) {
        if (!current) return
        val n = rx?.optInt("n", -1) ?: -1
        if (n >= 0 && n != rxN) {
            // fix/stream-row-offset (pad24): each new press with where the game saw it, against what the app meant ([TapCal.landed])
            // fix/desk-click-switch (test 1497: the stream's first report, n=12 on the PC's fresh client after the Thor's own larger
            // count, was taken as "nothing new", so the posted presses it listed were never matched): a count that went DOWN (another
            // client, a /reload) or the first one heard counts every press since that client's start
            val fresh = freshPresses(rxN, n)
            rxN = n
            TapBridge.note("landed n=$n: ${rx?.optString("last").orEmpty().take(160)}")
            if (fresh > 0) for (line in landedChecks(rx?.optString("last").orEmpty(), rx?.optString("at").orEmpty(), fresh, System.currentTimeMillis()))
                TapBridge.note(line)
        }
        if (!offToo) return
        val o = off?.let { j -> j.keys().asSequence().sorted().joinToString(" ") { k -> "$k@${j.opt(k)}" } }.orEmpty()
        if (o != offSaid) { offSaid = o; if (o.isNotEmpty()) TapBridge.note("pad rows not where described: $o") }
    }
    /**
     * fix/stream-row-offset: the [fresh] newest presses of the ring ("row:col" in [last], "x,y" or "-" in [at], newest last) checked
     * against the taps the app meant ([TapCal.landed]): the tap log's notes. Pure but for TapCal's memory of the taps sent.
     */
    /** fix/desk-click-switch: the presses new since count [before] (-1: none heard yet) at count [n]: a count that fell is a new client */
    fun freshPresses(before: Int, n: Int): Int = when {
        n <= 0 -> 0
        before in 0..n -> n - before
        else -> n
    }
    fun landedChecks(last: String, at: String, fresh: Int, now: Long): List<String> {
        val names = last.split(' ').filter { it.isNotEmpty() }
        val seen = at.split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        val from = (names.size - fresh).coerceAtLeast(0)
        // fix/desk-click-switch: more new presses than the ring lists = some never shown: the older batches are unknown, not unseen
        DeskClicks.ringGap(fresh - names.size, now)
        for (i in from until names.size) {
            val nc = names[i]; val cut = nc.lastIndexOf(':')
            if (cut <= 0) continue
            val col = nc.substring(cut + 1).toIntOrNull() ?: continue
            // `at` is as long as `last` (pad24); an older add-on has none: no seen y
            val sy = if (seen.size == names.size) seen[i].split(',').getOrNull(1)?.toDoubleOrNull() else null
            DeskClicks.landed(nc.substring(0, cut), col, now)?.let { out += it }   // feat/desktop-clicks: a posted click the game saw
            TapCal.landed(nc.substring(0, cut), col, sy, now)?.let { out += it }
        }
        return out
    }
    /** fix/1492-feedback: the gap between the switch taps of one batch (each its own cell; TapPad's 0.25 s guard is per cell) */
    const val SW_GAP_MS = 60L
    /** a batch's most switches (every part there is) */
    const val SW_BATCH_MAX = 24
    /**
     * fix/1492-feedback (test 1492: ~16 "uisw" taps spread over 3 s at the stream's start): EVERY switch to tap now, in [swNextSkip]'s
     * order - each part whose word differs from the choice (or not had since the /reload), not parked ([skip]), [due] (TapBackoff) -
     * as (part, absolute column). Pure.
     */
    fun swBatch(s: Sw, skip: Set<String>, due: (String) -> Boolean, want: (String) -> Boolean): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        val seen = HashSet(skip)
        while (out.size < SW_BATCH_MAX) {
            val (p, on) = swNextSkip(s, seen, want) ?: break
            seen += p
            if (!due(p)) continue
            val c = swCol(s, p, on) ?: continue
            if (c >= 0) out += p to c
        }
        return out
    }
    /** [swNext] without the parts in [skip] (PR #533: switches the game never answers, parked by [TapBackoff]). With every waiting
     *  part parked this is null, and the HUD steps / meters may go meanwhile; a parked part comes back by itself for its one probe */
    fun swNextSkip(s: Sw, skip: Set<String>, want: (String) -> Boolean): Pair<String, Boolean>? =
        listOf(SW_PLAYER, SW_TARGET, SW_NP, SW_PRD, SW_WM, SW_PARTY, SW_ATK, SW_SHOTF, SW_SHOT, SW_PMODE, SW_UFP, SW_UFT, SW_UFTOT, SW_UFHPP, SW_UFHPT, SW_PS, SW_PET, SW_NBAR, SW_SRES, SW_NPF).filter { it !in skip }.firstOrNull { p ->
            val now = swWord(s, p)
            // hub22: Auto Shot is the game's own saved option: only a CHOICE made here is sent (never the default over the game's)
            val chosen = p != SW_SHOT || character?.let { prefs?.contains("sw.$p.$it") } == true
            // hub28: while "In combat" is wanted (and the add-on knows it), the game's option follows the fight: not tapped
            if (p == SW_SHOT && s.shotf != null && want(SW_SHOTF)) return@firstOrNull false
            now != null && chosen && (now != want(p) || (p != SW_SHOT && s.set[p] != true))
        }?.let { it to want(it) }
    private fun swRow(id: String, part: String, label: String, onWord: String, offWord: String): Row? {
        if (character == null) return null
        val now = swNow(part) ?: return null
        val want = swWant(part)
        return Row(id, label, null, if (want) UiMode.ON else UiMode.OFF, true, !now,
            if (now == want) (if (now) onWord else offWord) else if (swNoRow()) PadFit.NO_ROW_NOTE else "Sent")
    }
    /** #550: the switches' row is registered but this layout cannot place it (TapPad pad26 `drop`): no tap can land, none waits */
    private fun swNoRow(): Boolean = PadFit.missing(pad, sw?.row ?: "uisw")
    /** #550: why a tap on [row] did not go: a row the layout cannot place, else no pad yet */
    private fun noTapWhy(row: String): String = if (PadFit.missing(pad, row)) PadFit.NO_ROW_NOTE else "The game has not published its tap pad"
    fun playerRow(): Row? = swRow(PLAYER_ID, SW_PLAYER, "Player frame", "Shown on the top screen", "Hidden: the resource bar and the HUD instead")
    // hub26: while DS Forever's own frames are on, the game's of that group stay hidden whatever their own switch says
    fun targetRow(): Row? = swRow(TARGET_ID, SW_TARGET, "Target frame", "Shown on the top screen", "Hidden: the bars over the mobs instead")
        // #197: shown as Off while the bar stands in (it IS hidden), so its On is the tap that brings it back (swLinked)
        ?.let { r -> if (swWant(SW_UFT) && swNow(SW_UFT) == true) Row(r.id, r.label, r.home, UiMode.OFF, r.known, true, "Hidden: the target bar replaces it") else r }
    fun partyRow(): Row? = swRow(PARTY_ID, SW_PARTY, "Party frames", "Shown on the top screen", "Hidden: the party strip below instead")
        ?.let { r -> if (swWant(SW_UFP) && swNow(SW_UFP) == true) Row(r.id, r.label, r.home, r.mode, r.known, true, "Hidden: DS party frames replace them") else r }
    /** hub26: DS Forever's own party frames (a left column; Party mode's pick and LB glyphs) and the target bar under the compass */
    fun ufPartyRow(): Row? = swRow(UFP_ID, SW_UFP, "DS party frames", "On: a column on the left", "Off")
    fun ufTargetRow(): Row? = swRow(UFT_ID, SW_UFT, "Target bar", "On: under the compass", "Off")
    /** uf8: the target bar's target of target, off by default */
    fun ufTotRow(): Row? = swRow(UFTOT_ID, SW_UFTOT, "Target of target", "On: beside the target bar", "Off")
    const val UFTOT_ID = "uftot"
    /** uf11: the target bar's health text: 0 Off (blank, the default), 1 Percent, 2 Total */
    val HP_WORDS = listOf("Off", "Percent", "Total")
    fun hpText(): Int = if (swWant(SW_UFHPT)) 2 else if (swWant(SW_UFHPP)) 1 else 0
    fun setHpText(k: Int) {
        val who = character ?: return
        prefs?.edit()?.putBoolean("sw.$SW_UFHPP.$who", k == 1)?.putBoolean("sw.$SW_UFHPT.$who", k == 2)?.apply()
        reconcile()
        changed()
    }
    fun ufHpRow(): Row? {
        if (character == null) return null
        val p = swNow(SW_UFHPP) ?: return null
        val t = swNow(SW_UFHPT) ?: return null
        val k = hpText()
        val now = if (t) 2 else if (p) 1 else 0
        return Row(UFHP_ID, "Target health text", null, if (k == 0) UiMode.OFF else UiMode.ON, true, k == 0,
            if (now != k) "Sent" else listOf("Off: the bar alone", "Percent of health", "Health and its maximum")[k])
    }
    const val UFHP_ID = "ufhp"
    const val UFP_ID = "ufparty"
    const val UFT_ID = "uftarget"
    const val PARTY_ID = "partyframes"
    fun nameplatesRow(): Row? = swRow(NP_ID, SW_NP, "Enemy nameplates", "On: the bars over hostile mobs", "The game's own setting")
    /** hub52: Top > Target, under Enemy nameplates: friendly plates while a friend is targeted (per character, off by default) */
    fun friendlyPlatesRow(): Row? = swRow(NPF_ID, SW_NPF, "Friendly plates", "On: while a friend is targeted", "Off")
    const val NPF_ID = "friendlyplates"
    /** QOL > Navigation: the world markers (off by default), Interact's switch on "uisw" */
    fun worldMarkersRow(): Row? = swRow(WM_ID, SW_WM, "World markers", "On: the markers in the 3D world", "Off: no markers in the world")
    const val WM_ID = "worldmarkers"
    /** QOL > Combat (hub22): a new hostile target starts the attack; the hunter's Auto Shot under it */
    fun attackRow(): Row? = swRow(ATK_ID, SW_ATK, "Start attack on hostile target", "On: targeting an enemy starts your attack", "Off")
    const val ATK_ID = "startattack"
    /** QOL > Party (hub25): Party mode, on by default since 1.0.26 */
    fun partyModeRow(): Row? = swRow(PMODE_ID, SW_PMODE, "Party mode", "On: Select turns Party mode on", "Off")
    const val PMODE_ID = "partymode"
    /** hub47: Controller > Combat helpers, under Party mode: Smart Res (per character, on by default since 2026-11-13); hidden for a
     *  class with neither a res nor Revive Pet */
    fun smartResRow(): Row? = if (sresClass(playerClass) == false) null else swRow(SRES_ID, SW_SRES, "Smart Res", "On: tap a dead member to res", "Off")
    const val SRES_ID = "smartres"
    /** Party mode allowed: Home draws its party module only then */
    fun partyModeOn(): Boolean = swWant(SW_PMODE)
    fun autoShotRow(): Row? {
        val now = swNow(SW_SHOT) ?: return null
        val who = character ?: return null
        if (shotMode() == 2) {
            val f = swNow(SW_SHOTF)
            return Row(SHOT_ID, "Combat Auto Shot", null, UiMode.ON, true, false,
                if (f != true) "Sent" else if (sw?.shotfk == false) "Smart: the game refused a change in the last fight" else "Smart: off for a plain attack you start")
        }
        val want = if (prefs?.contains("sw.$SW_SHOT.$who") == true) swWant(SW_SHOT) else now
        return Row(SHOT_ID, "Combat Auto Shot", null, if (want) UiMode.ON else UiMode.OFF, true, !now,
            if (now == want) (if (now) "On: Auto Shot at range, auto attack up close" else "Off") else "Sent")
    }
    /** hub28: Combat Auto Shot's three choices (Off / On / In combat); "In combat" only with an add-on that has it */
    val SHOT_WORDS = listOf("Off", "On", "Smart")
    fun shotThree(): Boolean = swNow(SW_SHOTF) != null
    fun shotMode(): Int {
        if (shotThree() && swWant(SW_SHOTF)) return 2
        val who = character ?: return 0
        val want = if (prefs?.contains("sw.$SW_SHOT.$who") == true) swWant(SW_SHOT) else swNow(SW_SHOT) == true
        return if (want) 1 else 0
    }
    fun setShotMode(k: Int) {
        val who = character ?: return
        val e = prefs?.edit() ?: return
        e.putBoolean("sw.$SW_SHOTF.$who", k == 2)
        if (k != 2) e.putBoolean("sw.$SW_SHOT.$who", k == 1)
        e.apply()
        reconcile()
        changed()
    }
    const val SHOT_ID = "autoshot"
    /** hub22: the add-on's "atkgo" (a new hostile target): tap the secure /startattack cell it names (Spells' party row 36), at most every 300 ms */
    @Volatile private var atkAt = 0L
    fun startAttack(row: String, col: Int) {
        val ctx = appCtx ?: return
        val now = System.currentTimeMillis()
        if (now - atkAt < 300L || character == null || !swWant(SW_ATK)) return
        atkAt = now
        GameInput.tap(ctx, pad, row, col)
    }
    const val PLAYER_ID = "playerframe"
    const val TARGET_ID = "targetframe"
    const val NP_ID = "nameplates"

    /** the resource bar for this character (QOL > HUD; was "HUD mode"): its own switch now, on by default */
    fun hudOn(): Boolean = swWant(SW_PRD)
    fun setHud(on: Boolean) = setSwitch(SW_PRD, on)
    /** the Settings row; null while the add-on does not offer it or the character is not known */
    fun hudRow(): Row? = swRow(HUD_ID, SW_PRD, "Resource bar", "Under your character: health, power, buffs", "Off")
    const val HUD_ID = "hudmode"
    /** hub33: the pet's strip, shown while the resource bar is on and the add-on has the switch */
    fun petRow(): Row? {
        if (hudRow() == null || !hudOn()) return null
        return swRow(PET_ID, SW_PET, "Pet bar", "On: under the resource bar", "Off")
    }
    const val PET_ID = "petbar"

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
    /** the HUD's width as the game reports it, against the bar's default (1 = the default): the Top screen tile's picture follows it,
     *  as the Width line does (hudValue); a choice the game has not taken yet is the badge's word, never the picture's (review 11-17) */
    fun hudWidthFactor(): Float = prdW?.let { it.toFloat() / wDef.coerceAtLeast(1) } ?: 1f
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

    // ------------------------------------------------------------------ hub61: the damage meter and the Cooldown Manager
    /*
     * The user, 2026-11-17: "DPS meters and also the cooldown manager ... we need to make sure that its consistent with the other picture
     * options". Settings > Top screen > Game windows' two tiles (the design's recommended picks: the game's option off = the choice waits,
     * never the CVar; Damage meter On / Off / Dynamic = a fight and 15 s after it; ONE Cooldown Manager tile with a line per part; per
     * character). Hub.lua hub61's WC.meters hides the game's frames by alpha + mouse only and keeps the choice per character itself; the
     * player's press reaches it on the layout editor's row "lay" while that editor is closed (the row is on the pad only while the
     * Settings room holds the controller). The app keeps each choice per character too and sends one where the add-on's word differs
     * (only a choice MADE here: never the default over the game's own, the Auto Shot rule).
     */
    /** the hub document's `mt` (null = an add-on set without the meters: the tiles wait) */
    data class Mt(val d: String, val dset: Boolean, val c: Boolean, val cset: Boolean, val p: Int, val pset: Boolean,
                  /** the game's own options (damageMeterEnabled / cooldownViewerEnabled), its Is*Available, the frames there; null = not read */
                  val dok: Boolean?, val dav: Boolean?, val df: Boolean, val cok: Boolean?, val cav: Boolean?, val cf: Boolean,
                  /** a press waits for the fight's end; a fight / its 15 s now; the presses the add-on has taken */
                  val q: Boolean, val fight: Boolean, val linger: Boolean, val n: Int, val cols: Map<String, Int>)
    @Volatile private var mt: Mt? = null
    const val DMG_ID = "dmg"
    const val CDM_ID = "cdm"
    /** the Cooldown Manager's parts, bit 0..3 of the add-on's mask (Essential, Utility, Buff icons, Buff bars) */
    const val CDM_ESS_ID = "cdm_ess"; const val CDM_UTIL_ID = "cdm_util"; const val CDM_BICON_ID = "cdm_bicon"; const val CDM_BBAR_ID = "cdm_bbar"
    val CDM_PARTS = listOf(CDM_ESS_ID, CDM_UTIL_ID, CDM_BICON_ID, CDM_BBAR_ID)
    const val SW_DMG = "dmg"; const val SW_CDM = "cdm"; const val SW_CDM_PARTS = "cdmp"
    /** the status line (and the tile's alert_gear badge) while the game's own option is off: the choice waits */
    const val GAME_OFF_NOTE = "Off in the game's Options"
    /** the row "lay" (LayoutModel.ROW) */
    const val MT_ROW = "lay"
    /** the "lay" document is OPEN, or the layout editor wants it open / did within LayoutModel.EDITOR_HOLD_MS (the add-on takes the
     *  meters' and the parts' columns only while closed; a word retried after an open would be an editor command) */
    @Volatile var layOpen: () -> Boolean = { LayoutModel.Live.editorBusy(LayoutModel.Live.clock()) }

    fun mtFrom(o: JSONObject?): Mt? = o?.let { m ->
        fun ob(k: String) = if (m.has(k) && !m.isNull(k)) m.optBoolean(k) else null
        val cols = HashMap<String, Int>()
        m.optJSONObject("cols")?.let { c -> for (k in c.keys()) cols[k] = c.optInt(k, -1) }
        Mt(m.optString("d", "on"), m.optBoolean("dset", false), m.optBoolean("c", true), m.optBoolean("cset", false),
            m.optInt("p", 15) and 15, m.optBoolean("pset", false), ob("dok"), ob("dav"), m.optBoolean("df", false), ob("cok"), ob("cav"),
            m.optBoolean("cf", false), m.optBoolean("q", false), m.optBoolean("fight", false), m.optBoolean("linger", false),
            m.optInt("n", 0), cols)
    }
    private fun dmgWord(m: UiMode) = when (m) { UiMode.OFF -> "off"; UiMode.DYNAMIC -> "dyn"; else -> "on" }
    private fun dmgOf(w: String) = when (w) { "off" -> UiMode.OFF; "dyn" -> UiMode.DYNAMIC; else -> UiMode.ON }

    /** this character's choice (null = none made here: the add-on's word stands) */
    private fun dmgChosen(): UiMode? = character?.let { who -> prefs?.getString("mt.$SW_DMG.$who", null)?.let { s -> runCatching { UiMode.valueOf(s) }.getOrNull() } }
    private fun cdmChosen(): Boolean? = character?.let { who -> prefs?.takeIf { it.contains("mt.$SW_CDM.$who") }?.getBoolean("mt.$SW_CDM.$who", true) }
    private fun partsChosen(): Int? = character?.let { who -> prefs?.takeIf { it.contains("mt.$SW_CDM_PARTS.$who") }?.getInt("mt.$SW_CDM_PARTS.$who", 15) }
    /** the damage meter's mode as chosen (the add-on's word until a choice is made; On = the game's own) */
    fun dmgMode(): UiMode = dmgChosen() ?: mt?.let { dmgOf(it.d) } ?: UiMode.ON
    fun cdmOn(): Boolean = cdmChosen() ?: mt?.c ?: true
    /** the parts' mask as chosen (bit 0 Essential .. 3 Buff bars) */
    fun cdmParts(): Int = (partsChosen() ?: mt?.p ?: 15) and 15
    fun setDmg(m: UiMode) {
        val who = character ?: return
        prefs?.edit()?.putString("mt.$SW_DMG.$who", (if (m == UiMode.OFF || m == UiMode.DYNAMIC) m else UiMode.ON).name)?.apply()
        reconcile(); changed()
    }
    fun setCdm(on: Boolean) {
        val who = character ?: return
        prefs?.edit()?.putBoolean("mt.$SW_CDM.$who", on)?.apply()
        reconcile(); changed()
    }
    /** part [bit] (0..3) on or off */
    fun setCdmPart(bit: Int, on: Boolean) {
        val who = character ?: return
        if (bit !in 0..3) return
        val p = cdmParts()
        prefs?.edit()?.putInt("mt.$SW_CDM_PARTS.$who", if (on) p or (1 shl bit) else p and (1 shl bit).inv() and 15)?.apply()
        reconcile(); changed()
    }

    /**
     * the column to tap on "lay" now, or null: the first CHOSEN value the add-on's word differs from (the damage meter, the tile's On /
     * Off, the parts' mask); [dmg] / [cdm] / [parts] = this character's choices (null = none made here)
     */
    fun mtCol(m: Mt, dmg: UiMode?, cdm: Boolean?, parts: Int?): Int? {
        fun col(k: String) = m.cols[k]?.takeIf { it >= 0 }
        if (dmg != null && dmgWord(dmg) != m.d) return col(when (dmg) { UiMode.OFF -> "DMG_OFF"; UiMode.DYNAMIC -> "DMG_DYN"; else -> "DMG_ON" })
        if (cdm != null && cdm != m.c) return col(if (cdm) "CDM_ON" else "CDM_OFF")
        if (parts != null && (parts and 15) != m.p) return col("PARTS")?.let { it + (parts and 15) }
        return null
    }
    private var mtTapAt = 0L
    private var mtTapN = -1
    /** the meters' one tap: the Settings room holding (the row "lay" on the pad), the editor closed, after the add-on's answer or 1.5 s */
    private fun mtStep(ctx: Context, s: HubState?, live: Boolean, now: Long) {
        val m = mt
        val h = hs
        if (m == null && h == null) return
        if (character == null || s == null || !live || !s.current || now < settleAt()) return
        if (pad?.optJSONObject("rows")?.has(MT_ROW) != true || layOpen()) return
        // split-hud: the parts' words first (the same row, one tap at a time), then the meters'
        val col = h?.let { hsCol(it) } ?: m?.let { mtCol(it, dmgChosen(), cdmChosen(), partsChosen()) } ?: return
        val n = (m?.n ?: 0) + (h?.n ?: 0)
        if (n == mtTapN && now - mtTapAt <= 1500L) return
        if (now - swTapAt < 300L) return                                  // one tap at a time: a switch's tap just went
        mtTapN = n; mtTapAt = now
        GameInput.tap(ctx, pad, MT_ROW, col)
    }
    /** the game's own option for the damage meter (or the Cooldown Manager) is off: the choice waits ("Off in the game's Options") */
    private fun gameOff(ok: Boolean?, av: Boolean?) = ok == false || av == false

    /** Top screen > Game windows' Damage meter; null = no add-on word / no character yet (a "..." tile) */
    fun dmgRow(): Row? {
        val m = mt ?: return null
        if (character == null) return null
        if (!m.df) return Row(DMG_ID, "Damage meter", null, dmgMode(), true, true, "The game does not offer this yet")
        val want = dmgMode()
        val shown = when (want) { UiMode.OFF -> false; UiMode.DYNAMIC -> m.fight || m.linger; else -> true }
        val note = when {
            gameOff(m.dok, m.dav) -> GAME_OFF_NOTE
            dmgWord(want) != m.d -> if (m.q) "Changes when combat ends" else "Sent"
            m.q -> "Changes when combat ends"
            shown -> "On the top screen now"
            else -> "Hidden now"
        }
        return Row(DMG_ID, "Damage meter", null, want, true, !shown, note)
    }
    /** Top screen > Game windows' Cooldown Manager (its parts are the band's lines: [cdmPartRow]) */
    fun cdmRow(): Row? {
        val m = mt ?: return null
        if (character == null) return null
        if (!m.cf) return Row(CDM_ID, "Cooldown Manager", null, if (cdmOn()) UiMode.ON else UiMode.OFF, true, true, "The game does not offer this yet")
        val on = cdmOn()
        val note = when {
            gameOff(m.cok, m.cav) -> GAME_OFF_NOTE
            on != m.c || cdmParts() != m.p -> if (m.q) "Changes when combat ends" else "Sent"
            m.q -> "Changes when combat ends"
            on -> "On the top screen now"
            else -> "Hidden now"
        }
        return Row(CDM_ID, "Cooldown Manager", null, if (on) UiMode.ON else UiMode.OFF, true, !on, note)
    }
    /** a part's line in the Cooldown Manager's band: only while the tile is On (a line that cannot act is not drawn) */
    fun cdmPartRow(id: String): Row? {
        val bit = CDM_PARTS.indexOf(id)
        if (bit < 0 || cdmRow() == null || !cdmOn()) return null
        val on = (cdmParts() shr bit) and 1 == 1
        return Row(id, listOf("Essential", "Utility", "Buff icons", "Buff bars")[bit], null, if (on) UiMode.ON else UiMode.OFF, true, !on, "")
    }

    /** f-top-castbar-toggle: the user's Hide for the game's cast bar. Default HIDE (1.0.10-test3, the user: "The cast bar needs to be
     *  hidden": Home's bottom bar shows every cast); a player who picked Show keeps it */
    fun castBarHide(): Boolean = prefs?.getBoolean("castbar.hide", true) ?: true
    /** the Hide that decides the game's cast bar now: single screen mode's (SingleUi) while it is on, else the saved one */
    private fun castBarHideNow(): Boolean = if (SingleScreen.inUse) SingleUi.castBarHide(castBarHide()) else castBarHide()
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
        val want = castBar.wantsHidden(castBarHideNow(), visible)
        val note = when {
            h == want -> if (h) "Hidden now" else "On the top screen now"
            last is UiStep.Tap -> "Waits for the layout first"
            else -> "Sent"
        }
        return Row(CASTBAR_ID, "Cast bar", null, if (hide) UiMode.OFF else UiMode.ON, true, h, note)
    }
    const val CASTBAR_ID = "castbar"

    /**
     * fix/desk-click-switch (Hub hub70): the add-on's `padrx` document, sent when its press count moves (the hub document carries `rx`
     * only as a ride-along, so a press's word could wait for the next heartbeat, 15 s). The same landing checks, then the retries.
     */
    fun onPadRx(msg: JSONObject, current: Boolean) {
        if (!current) return
        landedNote(msg.optJSONObject("rx"), null, current = true, offToo = false)
        appCtx?.let { TapBridge.retryMissed(it, pad) }
    }

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
        // #550: rows this layout cannot place (TapPad pad26 `drop`) are said ONCE, never a silent wait
        if (current) PadFit.once(pad)?.let { n -> Log.i("WCLOG", "pad: $n (not placed: ${PadFit.dropped(pad).joinToString(" ")})"); GameInput.onStatus(n) }
        // fix/stream-row-offset: the load the stream's measured fit is for, and a strip measurement whose answer this document carries
        if (h.has("boot")) TapCal.boot = h.optLong("boot", 0L)
        if (current) TapCal.settle(pad, System.currentTimeMillis())?.let { line -> Log.i("WCHUB", line); TapBridge.note(line) }
        landedNote(h.optJSONObject("rx"), pad?.optJSONObject("off"), current)
        // fix/stream-row-center: a stream batch that pressed another row goes once more, its row's aim nudged (TapCal.landed)
        if (current) appCtx?.let { TapBridge.retryMissed(it, pad) }
        castBarHidden = h.optJSONObject("cb")?.let { if (it.has("hidden")) it.optBoolean("hidden") else null }
        hudHidden = h.optJSONObject("hud")?.let { if (it.has("hidden")) it.optBoolean("hidden") else null }
        // hub67: the one-tap memory (absent from an older add-on set: no restore tap, nothing held)
        rst = h.optJSONObject("rst")?.let { o -> if (o.has("col")) HubRestore(o.optInt("col", -1), o.optInt("n", 0), o.optBoolean("q", false)) else null }
        // hub17: the split switches (absent from an older add-on: no rows, no taps)
        sw = h.optJSONObject("sw")?.let { o ->
            val cols = HashMap<String, Int>()
            o.optJSONObject("cols")?.let { c -> for (k in c.keys()) cols[k] = c.optInt(k, -1) }
            val set = HashMap<String, Boolean>()
            o.optJSONObject("set")?.let { c -> for (k in c.keys()) set[k] = c.optBoolean(k, false) }
            fun shown(k: String) = if (o.has(k)) o.optString(k) == "shown" else null
            fun flag(k: String) = if (o.has(k)) o.optBoolean(k) else null
            Sw(o.optString("row", "uisw"), cols, shown("player"), shown("target"), flag("np"), flag("prd"), set, flag("wm"), shown("party"), flag("atk"), flag("shot"), flag("pmode"),
               flag("ufp"), flag("uft"), flag("uftot"), flag("ufhpp"), flag("ufhpt"), flag("shotf"), flag("shotfk"), flag("ps"), flag("pet"), flag("nbar"), flag("sres"), flag("npf"))
        }
        // hub61: the damage meter and the Cooldown Manager (absent from an older add-on set: "..." tiles, no taps)
        mt = mtFrom(h.optJSONObject("mt"))
        // split-hud: the controller UI, the buffs and the XP bar (absent from an older add-on set: the controller tile alone)
        hs = hsFrom(h.optJSONObject("hs"))
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
            prdLay = prd.optBoolean("lay", false)
        } else { prdW = null; prdS = null; prdSel = null; prdLay = false }
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

    /** BottomSync round 3: the game's UI is as the modes want it now (nothing left to tap: fork/DsForeverScreen's give-back reader ends) */
    fun inPlace(): Boolean = last == UiStep.InPlace

    /** once a second: the game becoming live, and a reply that never came */
    fun tick() {
        val before = last
        reconcile()
        if (last != before) changed()
    }

    private fun reconcile() {
        val ctx = appCtx ?: return
        val now = System.currentTimeMillis()
        // single screen mode's switch: nothing is tapped on the top screen until the new window has settled (one reconcile after it)
        if (tapsHeld(now)) { last = UiStep.Wait(SWITCHING); return }
        val live = GameInput.live(now)
        // a state the game wrote before it went quiet says nothing about now
        val s = state?.let { if (live) it else it.copy(current = false) }
        // hub67: this load's ONE restore tap goes before any other top-screen tap; the others wait for its answer (at most 3 s), then
        // tap only what still differs from this character's choices
        // fix/stream-row-offset (the user, 2026-11-18, test 1494 streamed: "testing stream, ui still didnt hide"): on the stream the pad's
        // calibration strip is tapped first (twice; TapCal), and this object's own taps wait for its answer (at most TapCal.WAIT_MS)
        // feat/desktop-clicks: not when Desktop clicks the pad (an exact pixel: nothing to measure)
        // LoadGate: the strip's taps wait out the loading screen too (a strip tap under it is lost and spends one of TapCal's tries)
        if (live && s?.current == true && now >= settleAt() && DeskClicks.route() == DeskClicks.Route.STREAM_TOUCH) {
            val cal = TapCal.wanted(pad, true, now)
            if (cal != null) {
                if (TapBridge.calibrate(ctx, pad, cal)) { last = UiStep.Wait("Measuring where the stream's taps land"); return }
                TapCal.cancel()
            }
            if (TapCal.busy(now)) { last = UiStep.Wait("Measuring where the stream's taps land"); return }
        }
        val r = rst
        val rcol = restore.step(r, s, live, now, settleAt())
        if (rcol != null) {
            last = if (GameInput.tap(ctx, pad, ROW, rcol)) UiStep.Wait("Restoring this character's top screen") else UiStep.Wait(noTapWhy(ROW))
            return
        }
        if (restore.holding(r, s, now)) { last = UiStep.Wait("Restoring this character's top screen"); return }
        if (restore.unanswered(r, s, now)) {
            Log.i("WCHUB", "restore: no answer to the \"ui\" tap; the switches go as one batch")
            TapBridge.note("restore: no answer to ui:${r?.col} (${RestorePolicy.MAX_TRIES} taps); the switches go as one batch")
        }
        val step = policy.step(s, room.name, visible, live, now, settleAt())
        if (step is UiStep.Tap && !GameInput.tap(ctx, pad, ROW, step.mask)) {
            last = UiStep.Wait(noTapWhy(ROW))
            return
        }
        last = step
        // hub11: the cast bar's own tap, never in the same moment as a layout tap (one tap at a time)
        if (step !is UiStep.Tap) {
            val col = castBar.step(castBarHidden, castBarHideNow(), visible, s, live, now, settleAt())
            if (col != null) GameInput.tap(ctx, pad, ROW, col)
            // hub13: HUD mode's own tap, never in the same moment as another (one tap at a time); no character known = nothing
            else if (character != null) {
                // hub17: the split switches first, one absolute tap at a time on their own row, the next after the game's answer (or 1.5 s)
                val sws = sw
                // PR #533: a part the game answered since its last tap is forgotten; a parked one sits out its slow wait
                swBack.answered { p -> swSig(sws, p) }
                val next = sws?.let { swNextSkip(it, swBack.parked(now)) { p -> swWant(p) } }
                val swCol = if (sws != null && next != null) swCol(sws, next.first, next.second) else null
                if (sws != null && swCol != null && swCol >= 0 && s != null && live && s.current && !swNoRow() &&
                    now >= settleAt()) {
                    // fix/1492-feedback (the user, 2026-11-18, test 1492: "when opening the stream, it looked like it was doing a lot of
                    // clicks again"): every switch that differs goes in ONE batch ([swBatch], [SW_GAP_MS] apart: the whole set in about
                    // a second, never one per answer spread over seconds); each part still waits for its own answer (its word / set)
                    // or 1.5 s before it is tapped again, and after 3 unanswered taps one probe every 30 s (TapBackoff). The add-on keeps
                    // every switch that took (hub67), so the next login is the one restore tap
                    val batch = swBatch(sws, swBack.parked(now), { p -> swBack.due(p, now) }) { p -> swWant(p) }
                    if (batch.isNotEmpty()) {
                        for ((part, _) in batch) swBack.tapped(part, swSig(sws, part), now)
                        swTapAt = now
                        if (batch.size > 1) Log.i("WCHUB", "switches: ${batch.size} in one batch")
                        GameInput.taps(ctx, pad, batch.map { sws.row to it.second }, SW_GAP_MS)
                    }
                }
                else if (hudOn() && hudStepWanted()) {
                    // hub15 / hub16: HUD height, then width, then scale - one step a tap toward the choice; the next only after the
                    // game's answer (or 1.5 s). The step columns move the setting the add-on has SELECTED (its doc's sel): when it is
                    // not the one to move, the tap is column 39 (the next setting) first
                    data class Want(val sel: String, val cur: Int?, val want: Int?, val def: Int, val step: Int)
                    val todo = hudWants(prdLay).map { k -> when (k) {
                        "y" -> Want("y", prdY, hudHeight(), prdDef, prdStep); "w" -> Want("w", prdW, hudWidth(), wDef, wStep)
                        else -> Want("s", prdS, hudScale(), sDef, sStep) } }
                        .firstOrNull { it.cur != null && it.want != null && it.cur != it.want }
                    // PR #533: answered = the selected setting or a value moved; 3 unanswered taps, then one every 30 s
                    val hudSig = listOf(prdSel, prdY, prdW, prdS)
                    hudBack.answered { hudSig }
                    if (todo != null && s != null && live && s.current && s.hasRow &&
                        hudBack.due(HUD_KEY, now) && now >= settleAt()) {
                        hudBack.tapped(HUD_KEY, hudSig, now)
                        val cur = todo.cur!!; val want = todo.want!!
                        val selNow = prdSel ?: "y"
                        // the default is one tap (col 38); otherwise one step toward the choice
                        val col = if (selNow != todo.sel) CastBarPolicy.HUD_SEL
                            else if (want == todo.def && kotlin.math.abs(want - cur) > todo.step) CastBarPolicy.HUD_HDEF
                            else if (want > cur) CastBarPolicy.HUD_HIGHER else CastBarPolicy.HUD_LOWER
                        GameInput.tap(ctx, pad, ROW, col)
                    }
                }
                // hub61: the meters' choice on "lay" (only while no switch / HUD step is under way)
                else mtStep(ctx, s, live, now)
            }
        }
    }

    /** hub15 / hub16: a HUD height / width / scale step waits (the meters' tap goes only when none does) */
    private fun hudStepWanted(): Boolean = hudWants(prdLay).any { k -> when (k) {
        "y" -> prdY != null && hudHeight() != null && prdY != hudHeight()
        "w" -> prdW != null && hudWidth() != null && prdW != hudWidth()
        else -> prdS != null && hudScale() != null && prdS != hudScale() } }
    /** the HUD settings the tap loop steps toward the player's choice: height, width and scale; with the layout owning the height and the
     *  scale (hub55 prd.lay) the width alone (the layout editor moves and sizes the HUD itself) */
    fun hudWants(layOwns: Boolean): List<String> = if (layOwns) listOf("w") else listOf("y", "w", "s")

    // ------------------------------------------------------------------ for the Settings section

    class Row(val id: String, val label: String, val home: Room?, val mode: UiMode,
              val known: Boolean, val hidden: Boolean, val note: String)

    /** the elements to list: what the game reports, and always the minimap */
    fun rows(): List<Row> = UiElements.KNOWN.mapNotNull { info -> elementRow(info.id)?.takeIf { it.known || info.id == "minimap" } } +
        HudSplit.EXTRA.mapNotNull { id -> partRow(id)?.takeIf { it.known } }       // split-hud: the buffs and the XP bar

    /** one element's row whether the game reports it or not (known = false until it does): Top screen's tiles keep a fixed place for
     *  every element (the user's 2026-11-16 tile grid: a dim "..." tile until the game reports, no reflow); null = no such element */
    fun elementRow(id: String): Row? {
        if (id in HudSplit.EXTRA) return partRow(id)
        if (id == HudSplit.CONTROLLER_ID) partRow(id)?.let { return it }           // split-hud: the add-on has the split
        val info = UiElements.of(id) ?: return null
        val el = state?.els?.firstOrNull { it.id == info.id }
        val m = mode(info.id)
        return Row(info.id, info.label, roomOf(info.home), m, el != null,
                   el?.hidden == true, if (info.id == "hud" && m == UiMode.NINTENDO && el != null) nbarNote(sw?.nbar) else note(el, m))
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

    /** issue #195: the controller bars' row while Nintendo is chosen: what the Nintendo bar's switch says (null = an older add-on) */
    fun nbarNote(now: Boolean?): String = when (now) {
        null -> "The game does not offer this yet"
        true -> "DS Forever's bar on the top screen now"
        false -> "Sent"
    }

    private fun roomOf(name: String): Room? = try { Room.valueOf(name) } catch (_: Throwable) { null }

    fun onChange(l: () -> Unit) { listeners.add(l) }
    private fun changed() { for (l in listeners) l() }
    /** a reset (ResetRun) removed saved switches: the game is told the defaults where it differs, the rows read them again */
    fun resetDone() { runCatching { reconcile() }; changed() }

    private const val ROW = "ui"      // Hub.lua ROW
    private const val LOAD_REPORTS_KEY = "load.reports"
    private const val LOAD_SETTLE_MS = 1_000L   // after the loading screen went (LoadGate)
    private const val HUD_KEY = "hud" // PR #533: hudBack's one key
}
