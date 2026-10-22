package com.jrees.wowcompanion

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import org.json.JSONObject

/**
 * HOME'S ONE SETTING (home session): how the controller bars stand on Home out of combat.
 * User, 2026-09-29 15:15: "I want a toggle for the daimond button layout"; 15:27: the switch lives in Settings
 * ("In Settings"), and Home starts with the diamonds ("The diamonds"). Kept in Home's own prefs.
 */
object HomeSettings {
    private var prefs: SharedPreferences? = null
    private val listeners = ArrayList<() -> Unit>()

    fun init(c: Context) {
        if (prefs == null) prefs = c.applicationContext.getSharedPreferences("home", Context.MODE_PRIVATE)
    }

    var rest: HomeRest
        get() = prefs?.getString(KEY, null)?.let { runCatching { HomeRest.valueOf(it) }.getOrNull() } ?: HomeRest.DIAMONDS
        set(v) {
            if (v == rest) return
            prefs?.edit()?.putString(KEY, v.name)?.apply()
            for (l in listeners) l()
        }

    /** the radial's way (the user, 2026-10-06: "make the Other radial type ... an option ... under the DS wow toolbar"): true = TAP
     *  (THE DEFAULT since the user's b64 test: "I think this setting should be the default"; tap Up, the wheel stays, aim, A uses, B
     *  closes; the B and A glyphs show), false = HOLD (hold Up, aim, let the stick go; no glyphs) */
    /** the focused quest's card over the small map, MINIMIZED to its title line (the user, b124: "instead of the focused quest going
     *  to the quests tab, I wan it to "Minimize" the focused quest down to see more of the minimap. tapping it again should restore
     *  its full size"); kept across restarts */
    var questMin: Boolean
        get() = prefs?.getBoolean("questMin", false) ?: false
        set(v) {
            if (v == questMin) return
            prefs?.edit()?.putBoolean("questMin", v)?.apply()
            for (l in listeners) l()
        }

    /**
     * WHERE HOME'S FOCUSED QUEST STANDS (the user 2026-10-09: "I want to have a home screen option to have the focused quest above the
     * minimap or the Chat module"): true = over the minimap (b121, the default: today's), false = at the top of the chat column, the
     * minimap its full height. The card's rules are the same both ways (no focus = no card; a tap minimizes it)
     */
    var questOverMap: Boolean
        get() = prefs?.getBoolean("questOverMap", true) ?: true
        set(v) {
            if (v == questOverMap) return
            prefs?.edit()?.putBoolean("questOverMap", v)?.apply()
            for (l in listeners) l()
        }

    var radialTap: Boolean
        get() = prefs?.getBoolean(KEY_RADIAL, true) ?: true
        set(v) {
            if (v == radialTap) return
            prefs?.edit()?.putBoolean(KEY_RADIAL, v)?.apply()
            for (l in listeners) l()
        }

    /**
     * EVERY WHEEL'S PICK (the user 2026-10-09: "Tap VS hold and A to select vs release select"; one pair for all wheels, defaults Tap +
     * A): true = A to select (the stick only aims), false = letting the stick go selects. [radialTap] is the OPEN half (Tap / Hold).
     */
    var radialPickA: Boolean
        get() = prefs?.getBoolean(KEY_PICK, true) ?: true
        set(v) {
            if (v == radialPickA) return
            prefs?.edit()?.putBoolean(KEY_PICK, v)?.apply()
            for (l in listeners) l()
        }

    /**
     * THE PAUSE WHEEL'S OWN PAIR (the user 2026-10-09, revising "one pair for all wheels": "for the wheels open vs held, I dont want that
     * setting to affect the pause menu wheel. I have realized I want them to work differently"; picked "Pause gets its own Pick too",
     * Open defaults Tap): [pauseTap] true = Start opens it and it stays, false = held while Start is held; [pausePickA] true = A picks,
     * false = letting the stick go picks. [radialTap] / [radialPickA] stay the item & spell wheels' pair. App-wide, as those.
     */
    /**
     * f-radial-settings (1.0.9, the user: "better organize each radial setting into categories"): THE SPELL WHEEL'S OWN PAIR, apart from
     * the item wheel's ([radialTap] / [radialPickA], D-pad Up). Until set it follows the item wheel's (they were one pair before).
     */
    var spellTap: Boolean
        get() = prefs?.getBoolean(KEY_SPELL_TAP, radialTap) ?: radialTap
        set(v) {
            if (prefs?.contains(KEY_SPELL_TAP) == true && v == spellTap) return
            prefs?.edit()?.putBoolean(KEY_SPELL_TAP, v)?.apply()
            for (l in listeners) l()
        }
    var spellPickA: Boolean
        get() = prefs?.getBoolean(KEY_SPELL_PICK, radialPickA) ?: radialPickA
        set(v) {
            if (prefs?.contains(KEY_SPELL_PICK) == true && v == spellPickA) return
            prefs?.edit()?.putBoolean(KEY_SPELL_PICK, v)?.apply()
            for (l in listeners) l()
        }

    /**
     * f-radial-settings / b-class-takeover (the user: "add radial settings for the left and right class radials like we have for the other
     * ones"): each class radial's open (Tap = the chord opens it and it stays; Hold = open while the chord is held) and pick (A; Release =
     * letting go picks). Defaults Hold + Release: the game's own way (hold the chord, aim, let go). L = LB + LT, R = RB + RT.
     */
    var classTapL: Boolean
        get() = prefs?.getBoolean("classTapL", false) ?: false
        set(v) { if (v != classTapL) { prefs?.edit()?.putBoolean("classTapL", v)?.apply(); for (l in listeners) l() } }
    var classPickAL: Boolean
        get() = prefs?.getBoolean("classPickAL", false) ?: false
        set(v) { if (v != classPickAL) { prefs?.edit()?.putBoolean("classPickAL", v)?.apply(); for (l in listeners) l() } }
    var classTapR: Boolean
        get() = prefs?.getBoolean("classTapR", false) ?: false
        set(v) { if (v != classTapR) { prefs?.edit()?.putBoolean("classTapR", v)?.apply(); for (l in listeners) l() } }
    var classPickAR: Boolean
        get() = prefs?.getBoolean("classPickAR", false) ?: false
        set(v) { if (v != classPickAR) { prefs?.edit()?.putBoolean("classPickAR", v)?.apply(); for (l in listeners) l() } }

    var pauseTap: Boolean
        get() = prefs?.getBoolean(KEY_PAUSE_TAP, true) ?: true
        set(v) {
            if (v == pauseTap) return
            prefs?.edit()?.putBoolean(KEY_PAUSE_TAP, v)?.apply()
            for (l in listeners) l()
        }

    var pausePickA: Boolean
        get() = prefs?.getBoolean(KEY_PAUSE_PICK, true) ?: true
        set(v) {
            if (v == pausePickA) return
            prefs?.edit()?.putBoolean(KEY_PAUSE_PICK, v)?.apply()
            for (l in listeners) l()
        }

    /**
     * THE COMBAT LAYOUT'S SIZE (r-combat-anim-off; the user on batch2: "it was meant to be just a toggle to either make it animate bigger or
     * stay the same size"): true = GROW (the default: today's bigger In combat band), false = SAME SIZE (the fight's controls in the
     * rest's place and size: nothing on the page moves).
     */
    var combatGrow: Boolean
        get() = prefs?.getBoolean(KEY_COMBAT_GROW, true) ?: true
        set(v) {
            if (v == combatGrow) return
            prefs?.edit()?.putBoolean(KEY_COMBAT_GROW, v)?.apply()
            for (l in listeners) l()
        }

    /** p-swing-timer (a player: "would rather turn this off"): the SWING line under the controller in a fight (batch 12: not the cast bar); true = shown (the
     *  default, today's), false = none */
    var swingTimer: Boolean
        get() = prefs?.getBoolean(KEY_SWING, true) ?: true
        set(v) {
            if (v == swingTimer) return
            prefs?.edit()?.putBoolean(KEY_SWING, v)?.apply()
            for (l in listeners) l()
        }

    fun onChange(l: () -> Unit) { listeners.add(l) }

    private const val KEY = "rest"
    private const val KEY_COMBAT_GROW = "combatGrow"
    private const val KEY_SWING = "swingTimer"
    private const val KEY_PAUSE_TAP = "pauseTap"
    private const val KEY_PAUSE_PICK = "pausePickA"
    private const val KEY_PICK = "radialPickA"
    private const val KEY_RADIAL = "radialTap"
    private const val KEY_SPELL_TAP = "spellTap"
    private const val KEY_SPELL_PICK = "spellPickA"
}

/**
 * f-class-radial: WHERE THE GAME PUTS THE STANCE / FORM BAR AND THE PET BAR (its Controller settings, GamepadStanceBarOverride /
 * GamepadPossessBarOverride), read only: DS Forever never writes a CVar. The add-on's hud.bars carries the value and the game's own
 * words; Settings > Home shows them with where to change them.
 */
object GameBars {
    var stance: String? = null; private set
    var stanceTitle: String? = null; private set
    var pet: String? = null; private set
    var petTitle: String? = null; private set
    private val listeners = ArrayList<() -> Unit>()
    fun onChange(l: () -> Unit) { listeners.add(l) }
    fun note(st: JSONObject?, ps: JSONObject?) {
        fun word(o: JSONObject?) = o?.optString("n", "")?.takeIf { it.isNotEmpty() } ?: o?.optInt("v", 0)?.takeIf { it > 0 }?.let { "Setting $it" }
        fun title(o: JSONObject?) = o?.optString("t", "")?.takeIf { it.isNotEmpty() }
        val a = word(st); val at = title(st); val b = word(ps); val bt = title(ps)
        if (a == stance && at == stanceTitle && b == pet && bt == petTitle) return
        stance = a; stanceTitle = at; pet = b; petTitle = bt
        for (l in listeners) l()
    }
}

/**
 * START ATTACK ON CAST (Spells' PadMap m13; the user 2026-10-01 picked "option 2": harmful spells on the controller also start the
 * attack): the SWITCH lives in Home's Settings section, its meaning in Spells' add-on. The state comes from Spells' padmap (Map.attack;
 * null = an older add-on: no switch shown); one tap on the `spells` row's colOn / colOff turns it (no-action notes, idempotent: the one
 * retry is safe - a tap right after the Settings cursor's A can be lost to the park point). HomeView feeds the map and the pad.
 */
object HomeAttackSwitch {
    var state: PadMapModel.AttackState? = null
        private set
    /** the pad of the latest quests document (HomeView), where the `spells` row's cells are */
    var pad: JSONObject? = null
    private val listeners = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun onChange(l: () -> Unit) { listeners.add(l) }

    /** every padmap (HomeView.onPadMap): the switch follows the add-on's word */
    fun note(map: PadMapModel.Map) {
        if (map.attack == state) return
        state = map.attack
        for (l in listeners) l()
    }

    /** the user turned it [on] (a touch, or the Settings cursor's A): ONE tap; true = sent */
    fun turn(ctx: Context, on: Boolean): Boolean {
        val sent = tap(ctx, on)
        main.removeCallbacksAndMessages(null)
        if (sent) main.postDelayed({ val s = state; if (s != null && s.on != on && !s.pending) tap(ctx, on) }, RETRY_MS)
        return sent
    }

    private fun tap(ctx: Context, on: Boolean): Boolean {
        val s = state ?: return false
        val col = (if (on) s.colOn else s.colOff) ?: return false
        return TapBridge.tap(ctx, pad, PadMapModel.ROW_SPELLS, col) { }
    }

    private const val RETRY_MS = 450L
}

/**
 * THE LOOT WINDOW ON THE TOP SCREEN (r-loot-on-top, a player's request 2026-10-13): the "Loot window" row of Settings' Game UI section
 * (GameUiSection; it was Home's section until the user's batch4 word: "loot window should be moved to the UI settings"). The CHOICE is
 * the add-on's (WowCompanionInteract: WowCompanionDB.lootTop, kept across updates by SvKeep), so it holds from the login on: the
 * state comes from every interact document (`proof.lootc`: on / off cells and `top`; absent = an older add-on: no row), and a press
 * is one tap on the `iact` row's on / off cell, tried once more if the add-on has not said so a moment later (idempotent).
 */
object HomeLootSwitch {
    data class State(val top: Boolean, val colOn: Int, val colOff: Int)
    var state: State? = null
        private set
    /** the pad of the latest interact document that carried one (else Home's quests pad: the same shared grid) */
    private var pad: JSONObject? = null
    private val listeners = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun onChange(l: () -> Unit) { listeners.add(l) }

    /** every interact document (CompanionHost): the row follows the add-on's word */
    fun note(msg: JSONObject) {
        msg.optJSONObject("pad")?.let { pad = it }
        val c = msg.optJSONObject("proof")?.optJSONObject("lootc") ?: return
        val s = State(c.optBoolean("top", false), c.optInt("on", -1), c.optInt("off", -1))
        if (s.colOn < 0 || s.colOff < 0 || s == state) return
        state = s
        for (l in listeners) l()
    }

    /** the player picked the TOP screen ([top]) or the bottom: ONE tap; true = sent */
    fun turn(ctx: Context, top: Boolean): Boolean {
        val sent = tap(ctx, top)
        main.removeCallbacksAndMessages(null)
        if (sent) main.postDelayed({ val s = state; if (s != null && s.top != top) tap(ctx, top) }, RETRY_MS)
        return sent
    }

    private fun tap(ctx: Context, top: Boolean): Boolean {
        val s = state ?: return false
        return TapBridge.tap(ctx, pad ?: HomeAttackSwitch.pad, "iact", if (top) s.colOn else s.colOff) { }
    }

    private const val RETRY_MS = 450L
}

/**
 * THE COMPASS BAR ON THE TOP SCREEN (live layer part 1, the user's "compass F", 2026-10-19): the "Compass bar" row of Settings > Top
 * screen (GameUiSection). Like the loot window's switch, the CHOICE is the add-on's (WowCompanion's Trail.lua, WowCompanionDB.compass,
 * kept across updates by SvKeep; default on), so it holds from the login on. The state comes from every interact document
 * (`proof.compc`: on / off cells and `show`; absent = an older add-on set: no row); a press is one tap on the `iact` row's cell, tried
 * once more if the add-on has not said so a moment later (idempotent).
 */
object CompassSwitch {
    data class State(val show: Boolean, val colOn: Int, val colOff: Int, val why: String?,
                     val cam: Boolean? = null, val camOn: Int = -1, val camOff: Int = -1)
    var state: State? = null
        private set
    private var pad: JSONObject? = null
    private val listeners = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun onChange(l: () -> Unit) { listeners.add(l) }

    /** every interact document (CompanionHost): the row follows the add-on's word */
    fun note(msg: JSONObject) {
        msg.optJSONObject("pad")?.let { pad = it }
        val c = msg.optJSONObject("proof")?.optJSONObject("compc") ?: return
        val s = State(c.optBoolean("show", true), c.optInt("on", -1), c.optInt("off", -1), c.optString("why").ifEmpty { null },
            if (c.has("cam")) c.optBoolean("cam") else null, c.optInt("camOn", -1), c.optInt("camOff", -1))
        if (s.colOn < 0 || s.colOff < 0 || s == state) return
        state = s
        for (l in listeners) l()
    }

    /** the player switched the compass bar [on] or off: ONE tap; true = sent */
    fun turn(ctx: Context, on: Boolean): Boolean {
        val sent = tap(ctx, on)
        main.removeCallbacksAndMessages(null)
        if (sent) main.postDelayed({ val s = state; if (s != null && s.show != on) tap(ctx, on) }, RETRY_MS)
        return sent
    }

    private fun tap(ctx: Context, on: Boolean): Boolean {
        val s = state ?: return false
        return TapBridge.tap(ctx, pad ?: HomeAttackSwitch.pad, "iact", if (on) s.colOn else s.colOff) { }
    }

    /** cp7: "Compass follows the camera" (an experiment; off by default): ONE tap, tried once more like the bar's own switch */
    fun turnCam(ctx: Context, on: Boolean): Boolean {
        val s = state ?: return false
        val col = if (on) s.camOn else s.camOff
        if (col < 0) return false
        val sent = TapBridge.tap(ctx, pad ?: HomeAttackSwitch.pad, "iact", col) { }
        if (sent) main.postDelayed({ val n = state; if (n != null && n.cam != on) TapBridge.tap(ctx, pad ?: HomeAttackSwitch.pad, "iact", col) { } }, RETRY_MS)
        return sent
    }

    private const val RETRY_MS = 450L
}

/**
 * at1 (the user's "Nameplate tint plus bottom map"; ar1's "Aggro rings" switch): Settings > Top screen's "Aggro tint" (default on): the
 * add-on's tint round the nameplates of hostile mobs not yet pulled (Trail.lua's WC.rings). The row follows the add-on's word (proof.ringc.show); one tap, tried once more.
 */
object RingsSwitch {
    data class State(val show: Boolean, val colOn: Int, val colOff: Int)
    var state: State? = null
        private set
    private var pad: JSONObject? = null
    private val listeners = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun onChange(l: () -> Unit) { listeners.add(l) }

    fun note(msg: JSONObject) {
        msg.optJSONObject("pad")?.let { pad = it }
        val c = msg.optJSONObject("proof")?.optJSONObject("ringc") ?: return
        val s = State(c.optBoolean("show", true), c.optInt("on", -1), c.optInt("off", -1))
        if (s.colOn < 0 || s.colOff < 0 || s == state) return
        state = s
        for (l in listeners) l()
    }

    fun turn(ctx: Context, on: Boolean): Boolean {
        val sent = tap(ctx, on)
        main.removeCallbacksAndMessages(null)
        if (sent) main.postDelayed({ val s = state; if (s != null && s.show != on) tap(ctx, on) }, RETRY_MS)
        return sent
    }

    private fun tap(ctx: Context, on: Boolean): Boolean {
        val s = state ?: return false
        return TapBridge.tap(ctx, pad ?: HomeAttackSwitch.pad, "iact", if (on) s.colOn else s.colOff) { }
    }

    private const val RETRY_MS = 450L
}

/**
 * tt1 (test50, the user: "I need a setting to hide top screen tooltips"): Settings > Top screen's "Hide game tooltips" (default off).
 * The add-on hides the game's tooltips by alpha only (Trail.lua's WC.tips) and says so in every interact document (proof.tipc.hide);
 * the row follows that word. One tap per touch, tried once more if the word does not change, as the compass switch.
 */
object TipsSwitch {
    data class State(val hide: Boolean, val colOn: Int, val colOff: Int)
    var state: State? = null
        private set
    private var pad: JSONObject? = null
    private val listeners = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun onChange(l: () -> Unit) { listeners.add(l) }

    fun note(msg: JSONObject) {
        msg.optJSONObject("pad")?.let { pad = it }
        val c = msg.optJSONObject("proof")?.optJSONObject("tipc") ?: return
        val s = State(c.optBoolean("hide", false), c.optInt("on", -1), c.optInt("off", -1))
        if (s.colOn < 0 || s.colOff < 0 || s == state) return
        state = s
        for (l in listeners) l()
    }

    /** the player switched hiding [on] or off: ONE tap; true = sent */
    fun turn(ctx: Context, on: Boolean): Boolean {
        val sent = tap(ctx, on)
        main.removeCallbacksAndMessages(null)
        if (sent) main.postDelayed({ val s = state; if (s != null && s.hide != on) tap(ctx, on) }, RETRY_MS)
        return sent
    }

    private fun tap(ctx: Context, on: Boolean): Boolean {
        val s = state ?: return false
        return TapBridge.tap(ctx, pad ?: HomeAttackSwitch.pad, "iact", if (on) s.colOn else s.colOff) { }
    }

    private const val RETRY_MS = 450L
}

/**
 * The Settings room's HOME section: one row, "Controller on Home", with its two choices in view as the game's red buttons
 * (UiKit.choice, the chosen one lit). Stacked under the game-UI section by settingsRoom (GameUiSection.kt). Touches act on
 * RELEASE and only when the finger has not moved (the page scrolls).
 */
class HomeSettingsSection(context: Context) : View(context) {
    private val pSection = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 32f; color = Theme.gold }
    private val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 31f; color = Theme.text }
    private val pSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Theme.textDim }
    private val pRow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBtn = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
    /** every choice in view, in the cursor's order: its box and what it does */
    private val boxes = ArrayList<Pair<RectF, () -> Unit>>()
    private var downX = 0f
    private var downY = 0f

    /**
     * THE DS WOW TOOLBAR (the user, 2026-10-06: the D-pad room bar "was functionally changed", renamed and moved into Home's section):
     * Spells' padmap D-pad state (null = an older add-on: no row) and the switch (MainActivity: Spells' place / remove taps). The CHOICE
     * (`want`) is shown, not the moment (`on` is false whenever a room holds the D-pad)
     */
    var dpadState: () -> PadMapModel.Dpad? = { null }
    var onDpad: (Boolean) -> Unit = {}
    /** the padmap changed: the row may appear or flip */
    fun dpadChanged() { post { requestLayout(); invalidate() } }

    init {
        HomeSettings.onChange { post { invalidate() } }
        // the attack switch appears / its words change: the section's height may change too
        HomeAttackSwitch.onChange { post { requestLayout(); invalidate() } }
        GameBars.onChange { post { requestLayout(); invalidate() } }               // f-class-radial: the rows appear / change
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthSpec), resolveSize(height().toInt(), heightSpec))
    }

    /** the section's depth: the controller row, the attack row and the toolbar row (each only while its add-on offers it) */
    private fun height(): Float = lay(null)

    override fun onDraw(c: Canvas) {
        UiKit.asTitle(pSection); UiKit.asBody(pLabel); UiKit.asBody(pSub)
        c.drawColor(Theme.ink)
        lay(c)
    }

    /** the game's stance / possess bar override rows (read only): hidden for now (test40), their code and the game's values kept */
    private val SHOW_GAME_BARS = false

    /** the rows as last drawn, with their words (the "What it does" panel) */
    private val rowHits = ArrayList<Pair<RectF, String>>()

    /** the row at ([x], [y]) in this view, by its words; null = none */
    fun rowAt(x: Float, y: Float): String? = rowHits.firstOrNull { it.first.contains(x, y) }?.second

    /** draws (or, with no canvas, only measures) the section; returns its depth */
    private fun lay(c: Canvas?): Float {
        boxes.clear()
        if (c != null) rowHits.clear()
        // THE PAGE'S ROW LOOK (the user's signed-off picture, 2026-10-06; Map's emulator check: this section had no row boxes and
        // bigger buttons): a raised row, its label and sub-line on the left, its choices as one compact pair on the right. A row is
        // ROW tall (48 dp) and each choice's touch area spans the whole row, though the button is drawn the page's compact size
        c?.drawText("Home", 28f, 48f, pSection)
        var y = 60f
        y = row(c, y, "Controller on Home", listOf("Out of combat. In combat the diamonds always take over."),
            listOf("Thin row", "Diamonds"), HomeRest.values().indexOf(HomeSettings.rest),
            listOf({ HomeSettings.rest = HomeRest.ROW }, { HomeSettings.rest = HomeRest.DIAMONDS }))
        // r-combat-anim-off (the user on batch2): in a fight the controller grows (today's) or keeps its size and place
        y = row(c, y + GAP, "Combat layout", listOf("In a fight the controller grows, or keeps its size."),
            listOf("Grow", "Same size"), if (HomeSettings.combatGrow) 0 else 1,
            listOf({ HomeSettings.combatGrow = true }, { HomeSettings.combatGrow = false }))
        // p-swing-timer: the swing line under the controller in a fight (batch 12: the cast bar is not this setting)
        y = row(c, y + GAP, "Swing timer", listOf("The swing line under the controller in a fight. The cast bar stays."),
            listOf("On", "Off"), if (HomeSettings.swingTimer) 0 else 1,
            listOf({ HomeSettings.swingTimer = true }, { HomeSettings.swingTimer = false }))
        // f-class-radial: the game's own stance / pet bar placement, read only (no choices: DS Forever never changes it).
        // HIDDEN (test40, the user: "We need to hide the settings that don't do anything for now: the stance bar override and possess bar
        // override"): not offered; GameBars still reads both (the form bars read the stance one), and SHOW_GAME_BARS brings them back
        if (SHOW_GAME_BARS) GameBars.stance?.let { v ->
            y = row(c, y + GAP, GameBars.stanceTitle ?: "Stance bar", listOf("$v. Change it in the game's Controller settings."),
                emptyList(), -1, null)
        }
        if (SHOW_GAME_BARS) GameBars.pet?.let { v ->
            y = row(c, y + GAP, GameBars.petTitle ?: "Pet bar", listOf("$v. Change it in the game's Controller settings."),
                emptyList(), -1, null)
        }
        // the focused quest's place (the user 2026-10-09): over the minimap (today's) or at the top of the chat
        y = row(c, y + GAP, "Focused quest", listOf("Where Home shows the quest you focus. Tap it to fold it."),
            // the user 2026-10-09: "Above Chat" first; the default stays Above Minimap
            listOf("Above Chat", "Above Minimap"), if (HomeSettings.questOverMap) 1 else 0,
            listOf({ HomeSettings.questOverMap = false }, { HomeSettings.questOverMap = true }))
        // START ATTACK ON CAST (Spells' m13): Off / On, from the add-on's word (null = an older add-on: no row); its notes under it
        HomeAttackSwitch.state?.let { s ->
            y = row(c, y + GAP, "Start the attack when a spell is cast",
                // PAUSED (the add-on's m40; the user 2026-10-13): greyed out, nothing lit, not switchable, "coming back later"
                if (s.paused) listOf("Coming back later.") else listOf("Harmful spells on your controller also start your attack.") + HomeAttackWords.notes(s),
                listOf("Off", "On"), if (s.paused) -1 else if (s.on) 1 else 0,
                if (s.paused) null else listOf({ HomeAttackSwitch.turn(context, false) }, { HomeAttackSwitch.turn(context, true) }))
        }
        // THE DS WOW TOOLBAR (Spells' place / remove; null = an older add-on: no row): Off / On, the CHOICE (want)
        dpadState()?.let { d ->
            val subs = if (d.colOn == null) listOf("Needs the newer Spells add-on")
                else listOf("The plain D-pad opens the map, chat, the bar and the radial.", "Off: your own D-pad slots.")
            y = row(c, y + GAP, "DS WoW toolbar", subs, listOf("Off", "On"), if (d.want == true) 1 else 0,
                if (d.colOn == null) null
                else listOf({ if (d.want == true) onDpad(false) }, { if (d.want != true) onDpad(true) }))
            // the radial's way moved to Settings' Controller section: TWO settings for every wheel (Wheels: open / Wheels: pick)
        }
        return y + 20f
    }

    /** one raised row at [top]: [label], [subs] (one line each), [choices] on the right with [sel] lit; [acts] null = not switchable */
    private fun row(c: Canvas?, top: Float, label: String, subs: List<String>, choices: List<String>, sel: Int,
                    acts: List<() -> Unit>?): Float {
        val h = maxOf(ROW, 75f + (subs.size - 1) * SUB_H + 21f)
        val box = RectF(16f, top, width - 16f, top + h)
        if (c != null) { pRow.color = Theme.raised; c.drawRoundRect(box, 8f, 8f, pRow); rowHits.add(RectF(box) to label) }
        pBtn.textSize = SEG_TEXT
        var segW = SEG_MIN_W
        for (t in choices) segW = maxOf(segW, pBtn.measureText(t) + SEG_PAD)
        val right = box.right - 16f
        val left = right - segW * choices.size
        for ((k, t) in choices.withIndex()) {
            val hit = RectF(left + k * segW, box.top, left + (k + 1) * segW, box.bottom)
            val cy = box.top + ROW_H_DRAWN / 2f
            if (c != null) UiKit.choice(c, RectF(hit.left + 2f, cy - 40f, hit.right - 2f, cy + 40f), t, k == sel,
                enabled = acts != null, textSize = SEG_TEXT)
            acts?.getOrNull(k)?.let { boxes.add(hit to it) }
        }
        c?.drawText(label, box.left + 22f, box.top + 41f, pLabel)
        for ((i, line) in subs.withIndex()) c?.drawText(fit(pSub, line, left - 20f - (box.left + 22f)), box.left + 22f, box.top + 75f + i * SUB_H, pSub)
        return box.bottom
    }

    /** [s] cut to [maxW] px with an ellipsis, or unchanged when it fits (SettingsView's rule) */
    private fun fit(p: Paint, s: String, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        val n = p.breakText(s, true, (maxW - p.measureText("…")).coerceAtLeast(0f), null)
        return s.substring(0, n).trimEnd() + "…"
    }

    /** the controller's targets (Home's Settings cursor, LATER item 5): every choice in view */
    fun focusTargets(): List<RectF> = boxes.map { RectF(it.first) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - downX) > SLOP || abs(e.y - downY) > SLOP) return true
                boxes.firstOrNull { it.first.contains(e.x, e.y) }?.let { it.second(); invalidate() }
            }
        }
        return true
    }

    private companion object {
        const val ROW = 112f            // a row's height: the 48 dp touch floor (each choice's touch area is the whole row)
        const val ROW_H_DRAWN = 96f     // where the compact buttons sit: centred as on the page's 96 px rows
        const val GAP = 10f             // between rows, as on the page
        const val SUB_H = 28f
        const val SEG_TEXT = 24f        // the page's segment text (SettingsView)
        const val SEG_PAD = 36f
        const val SEG_MIN_W = 120f
        const val SLOP = 16f
    }
}
