package com.jrees.wowcompanion

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONObject

/**
 * The bottom-screen companion.
 *
 *   adb shell am start --display 4 -n com.jrees.wowcompanion/.MainActivity
 *
 * ROOM WIRING lives here: the views, show() and handleMessage(). Room sessions add to those.
 *
 * INPUT does not: everything sent to the game goes through [GameInput] (hub session), and which
 * of the game's own UI elements are hidden from the top screen is [GameUi]'s business. The old
 * minimap contract (hidden at start, restored at stop, typed into chat) became the minimap's
 * Dynamic mode there: hidden while the Map room shows, by one tap, and only once the game is
 * provably in the world.
 */
/**
 * THE FEW ACTIVITY CALLS THE HOST NEEDS (the rooms refactor, S1, 2026-10-08; AGENT_LOG "the rooms refactor plan"). The app's MainActivity
 * implements it; the private launcher's bottom-screen Presentation implements it too (S2).
 */
interface HostShell {
    fun setHostContent(v: View)
    fun hostGone(): Boolean
    fun recreateHost()
    fun hostWindow(): android.view.Window?
    fun hostIntent(): Intent?
    fun startHostActivity(i: Intent)
}

/**
 * DS FOREVER'S WHOLE BOTTOM SCREEN (the rooms refactor, S1, 2026-10-08; the user: "(c) Rooms refactor", "(a) Short freeze"): every room,
 * Home, the wheels, the pause menu, the sign-in watcher and the document routes - MainActivity's former body, moved here UNCHANGED so
 * the app and the private launcher ("DS Forever Launcher") build the same thing. A Context itself (ContextWrapper over the Activity or the
 * Presentation's context), so every call it made as an Activity still works; the Activity-only ones go through [shell].
 * EVERY EDIT THAT USED TO GO TO MainActivity.kt GOES HERE (shared file: list it in AGENT_LOG first).
 */
class CompanionHost(base: Context, private val shell: HostShell) : ContextWrapper(base) {

    private lateinit var content: FrameLayout
    /** THEMES (the user, 2026-11-12): a theme picked in Settings repaints the whole bottom screen at once (ThemeLive) */
    private var themeRoot: View? = null
    private val themeChanged: () -> Unit = {
        ui.post { themeRoot?.let { r -> ThemeLive.repaint((r.parent as? View) ?: r, Theme.prev, Theme.cur) } }
    }
    private lateinit var strip: Strip
    /** the status text lives in the Settings room now (user, 2026-09-28); every `status.text = ...` is unchanged */
    private val status = StatusLine

    private lateinit var mapView: MapView
    // the Map room, like the game's own world map (the user, 2026-10-07); Home keeps mapView as its module
    private lateinit var worldMapRoom: WorldMapRoomView
    private lateinit var questsView: QuestsView
    private lateinit var castView: CastView
    private lateinit var bagsView: BagsView
    private lateinit var chatView: ChatView
    /** the Character room (character session) */
    private lateinit var characterView: CharacterView
    private lateinit var settingsView: SettingsView
    /** the interrupt layer: an open NPC window takes the whole screen, over the rooms AND the strip */
    private lateinit var interactView: InteractView
    private lateinit var interactKb: ChatKeyboard
    /** the room's red combat cue (Home's): over the rooms only, while a room keeps the controller through a fight */
    private lateinit var roomCue: RoomCombatCue
    private lateinit var dialogView: DialogView
    private lateinit var settingsRoom: View
    /** the Settings room with its controller cursor (home session, LATER item 5; the guest "settings" of Quests.lua q6) */
    private lateinit var settings: SettingsRoom
    /** the pad as the quests documents publish it (the `qact` row carries the generic controller guests) */
    private var questsPad: org.json.JSONObject? = null
    /** capture v9: while a room holds through a fight, every leave the app starts goes through ONE secure leave cell (Home's) */
    private val fightLeave = FightLeave()
    /** combat_nav Step 3 (the user, 2026-11-11: "If I am in combat and press B I should be able to fully navigate everything like normal"):
     *  in a fight the pad follows what the bottom screen wants - ENTER when something of ours is up and the mode is "game", LEAVE at Home
     *  when it is "room" (Interact's doc.fightMode); it replaces SPEC_COMBAT 9 / 10's per-room enter tap and show()'s inline leave */
    private val padOwner = PadOwnerSync()
    private val padOwnerTick = object : Runnable {
        override fun run() { padOwnerStep(); if (padOwner.mode != null) ui.postDelayed(this, PAD_OWNER_TICK_MS) }
    }
    private lateinit var gameMenuLock: GameMenuLock
    /** the capture ids of the room the app last left by its own show(), and when (uptime ms): HomeBack.isOwnLeave */
    private var leftIds: Set<String> = emptySet()
    private var leftAt = 0L
    /** the page the bottom screen rests on (home session); it borrows mapView and castView while it shows */
    private lateinit var homeView: HomeView
    /** the start screen (UI Art): over the rooms and the bar until the game has answered */
    private lateinit var startView: StartView
    /** the consumables radial (D-pad UP, Home): over the rooms and the bar, under the NPC windows and the dialogs */
    private lateinit var radialView: HomeRadialView
    /** the radial pause menu (the user 2026-10-08: Start on Home; HomePauseView) */
    private lateinit var pauseView: HomePauseView
    /** the TARGET radial (Y out of a fight with a target; PadMap m101 / Quests q51) */
    private lateinit var targetRadial: TargetRadialView
    /** 1.0.18: LB held on Home pictures the game's party targeting (LbPartyView) */
    private lateinit var lbView: LbPartyView
    private fun tradOpen() = ::targetRadial.isInitialized && targetRadial.isOpen
    /** Bags' pin picker (the radial's "+"), hung over the room; Home's radial drives it */
    private lateinit var pinPicker: BagsPinPicker
    // our own Battle.net sign-in (LATER 29): the prompt, the login screen's watcher, and the line the start screen showed before it
    private lateinit var loginPrompt: HomeLoginPrompt
    /** the launcher's setup on the bottom screen (Track C): Play + one pop-up at a time ([SetupBridge]) */
    private lateinit var setupPanel: SetupPanel
    /** the start-up chooser (Local / Streaming, S1 of the streaming phase) and Streaming's placeholder page: over the setup */
    private lateinit var chooserPanel: ChooserPanel
    private lateinit var loginWatch: HomeLoginWatch
    private var lineBeforeLogin: String? = null
    private var loginPadHeld = 0
    // the self-setup (Home, 2026-10-02): its screen over everything, its root runner, and whether the user said Not now this run
    private lateinit var setupView: SetupView
    private lateinit var setupRunner: SetupRunner
    private var setupDeclined = false
    private var setupBusy = false
    /** the Social room (social session): party / raid, friends, ignore, guild, Who */
    private lateinit var socialView: SocialView
    /** the addon's count of the game's own social windows opened; -1 until the first document (no room jumps at start) */
    private var socialOpenN = -1
    private var questWinN = 0          // Quests.lua q25's questwin n (it grows across game reloads: seeded from the server's second)
    /** the Group Finder room (groupfinder session): the game's Looking For Group board */
    private lateinit var groupFinderView: GroupFinderView
    /** the game's own LFG window was open at the last `gf` document; null until the first (no room jump at start) */
    private var gfWin: Boolean? = null
    /** the Spellbook room (spells session), and what it brings over the whole screen (the Bind box) */
    private lateinit var spellbookView: SpellbookView
    // the CONTROLLER ROOM (spells session's view; Home hosts it in Chat's old spot on the bar, 2026-10-05)
    private lateinit var controllerView: ControllerView
    private lateinit var spellsOverlay: SpellsOverlay
    private var spellsPad: JSONObject? = null
    // the user's "Both" ruling after test20: the In-combat backup and the clean exit
    private lateinit var combatKeep: CombatKeepHost
    private var lastBook: SpellsModel.Book? = null
    /** m62: the latest padmap (Settings' Smart Y section reads its Smart buffs) */
    private var lastPadMap: PadMapModel.Map? = null
    private var lastGameMenu: JSONObject? = null
    private var lastAddonAt = 0L
    private var gmAckAt = 0L          // the game menu row's last answer (Radial.lua r12 "gmack") and its cell
    private var gmAckCol = -1
    private var cleanExitWaiting = false   // a clean exit waits for the game: its answers are copied to WCSETUP
    private var lastCountLogged = ""
    private var gmRefused: String? = null  // the game's own refusal of the Exit tap (Radial gm2), during a clean exit's wait
    // SvKeep (the user's pick after test22): our tables confirmed this run (a non-empty first word, or the player changed a setting)
    private val svArmed = HashSet<String>()
    private val svSeen = HashSet<String>()                              // the run's FIRST word per table decides (a partial restore later must not)
    private var svRun = Long.MIN_VALUE
    private val SV_PLAYER_OPS = setOf("combat", "cface", "uncombat", "uncface", "clear", "command", "spell", "move", "bind", "rspell", "rankup")
    private fun svKeptDir() = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "DS Forever/settings")
    /** the Professions room (professions session): the book of cards and a crafting page per profession */
    private lateinit var professionsView: ProfessionsView
    /** the Calendar room (calendar session): the game's month calendar, look and answer */
    private lateinit var calendarView: CalendarView
    /** the game's own calendar window was open at the last `calendar` document; null until the first (no room jump at start) */
    private var calWin: Boolean? = null
    /** the Talents room (spells session): its OWN room on the bar (the user 2026-09-30: "replace it with a button for the talents") */
    private lateinit var talentsView: TalentsView
    /** the bar selector (home session, LATER item 7): Select puts a controller cursor on the bar */
    private lateinit var barSelect: HomeBarSelect

    private var tail: LogSource? = null
    /** WoW Forever as GameLocator found it on this device (the log, the setup's folders); set once at start */
    @Volatile private var game: GameLocator.Found? = null
        set(v) { field = v; if (::setupRunner.isInitialized) setupRunner.wowForever = v }
    private val ui = Handler(Looper.getMainLooper())
    private val iconArtState: (ArtHarvest.State) -> Unit = { state ->
        ui.post {
            if (state is ArtHarvest.State.Ready) {
                fun redraw(view: View) {
                    view.invalidate()
                    if (view is ViewGroup) for (i in 0 until view.childCount) redraw(view.getChildAt(i))
                }
                shell.hostWindow()?.decorView?.let { redraw(it) }
            } else if (state is ArtHarvest.State.Failed && ::startView.isInitialized) {
                // p-build-tables-blocks-keyboard (a player, 2026-10-14: an art table error on the bottom screen at the login screen and
                // no keyboard "by any method"): with the game up, the art's failure NEVER takes the bottom screen, its start view or
                // its line; the sign-in and its Keyboard always stay reachable. It shows in Settings' status line and is retried at
                // the next start. Before Play (no game) the start screen says it, as before.
                if (LauncherHooks.gameUp()) {
                    status.text = "Game art: ${state.why}".take(110)
                    Log.w("WCHARVEST", "art failed with the game up (not shown over the sign-in): ${state.why}")
                } else {
                    startView.line = state.why
                    startView.visibility = View.VISIBLE
                    startView.invalidate()
                }
            }
        }
    }
    private var lastMapAt = 0L
    private var lastMap: JSONObject? = null
    private var room = Room.HOME
    private var enterProfessions = false
    /** the last `prof` document was CURRENT (not replayed from the backlog); the room follows the game only between two of these */
    private var profCurrent = false

    /** this instance only shows [unpackFirst]: nothing of the normal start exists, so onStart / onStop leave it all alone
     *  (2026-10-03, the share test on the Thor: onStart's barSelect crashed every fresh install before the unpack screen) */
    private var unpacking = false

    /**
     * Settings > Frame generation > Choose: FrameGenPickActivity on THIS context's display (the bottom screen), so the game's window on
     * the top screen is never covered. A Presentation is a system window and stands OVER the activities of its display: while the
     * picker is up, this window is hidden (GONE: no touches, the picker shows), and it comes back when the picker closes
     * (FrameGen.onPickerClosed). If the bottom display refuses the launch, the picker opens where Android puts it and nothing is hidden.
     */
    private fun openDllPicker() = openOnBottom(FrameGenPickActivity::class.java, "FrameGen") { FrameGen.onPickerClosed = it }

    /**
     * Settings / the setup screen > "Send a problem report" (board f-problem-report): the report PAGE inside the bottom screen (the
     * user: "part of the bottom app UI instead of tabbing out"), typing with Chat's keyboard. [sendNow] = Settings' "Send report" while
     * recording. In the game the page opens over the Settings room, which holds the controller (its guest pad passes each press).
     */
    private lateinit var reportPage: ReportPage
    private lateinit var pathReview: SafePathReview

    private fun openReport(sendNow: Boolean = false) {
        if (!::reportPage.isInitialized) return
        DiagLog.event("report", if (sendNow) "send now (from Settings, recording)" else "report page opened")
        // issue #215 (diagnostics only): what the bottom screen showed when the player chose to report, before Settings covers it
        StartupDiag.bottomAtReport = System.currentTimeMillis() to runCatching { bottomState(atReport = true) }.getOrElse { "(could not be read: ${it.javaClass.simpleName})" }
        if (LauncherHooks.gameUp() && room != Room.SETTINGS) show(Room.SETTINGS)
        reportPage.open(sendNow)
    }

    /** GitHub's pre-filled issue in the browser, on the TOP screen (a browser on the bottom one would sit under this window) */
    private fun openUrlOnTop(url: String): Boolean = runCatching {
        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle())
    }.onFailure { android.util.Log.w("WCREPORT", "browser: ${it.message}") }.isSuccess

    /**
     * SEND REPORT (the user after batch16: Send was "stuck" on Making the report; now it "should close this menu and take the player
     * back home with a notice"): Home, the notice "Compiling your report…", the report built and saved OFF the main thread, then
     * "Report ready" (Open issue / OK) or "Couldn't save the report: <why>" (OK). A watchdog ends a build that takes longer than
     * [REPORT_TIMEOUT_MS]. The saved report also leaves Settings' note until its issue is opened or the note dismissed.
     */
    private lateinit var reportNotice: ReportNotice
    private var reportGen = 0
    private val REPORT_TIMEOUT_MS = 45_000L

    private fun sendReport(words: String) {
        if (!::reportNotice.isInitialized) return
        val gen = ++reportGen
        if (LauncherHooks.gameUp() && room != Room.HOME) show(Room.HOME)
        noticeShow(ReportNotice.State.Compiling)
        DiagLog.event("report", "send: compiling")
        val app = applicationContext
        val upload = ReportUpload.enabled
        Thread({
            // always saved first (a failed send keeps it); then, with a receiver in this build, sent (the user's in-game upload)
            val res = runCatching {
                val r = ProblemReport.collect(app, words)
                val f = ProblemReport.saveOrWhy(app, r).getOrThrow()
                Triple(r, f, ProblemReport.issueUrl(r, words, ProblemReport.facts(app), f.path, Redact.names()))
            }
            val sent = res.getOrNull()?.takeIf { upload }?.let { (r, _, _) ->
                ui.post { if (gen == reportGen) noticeShow(ReportNotice.State.Sending) }
                ReportUpload.send(ReportUpload.request(r, words, ProblemReport.facts(app), DiagLog.lines(ProblemReport.WINDOW_MS)
                    .any { it.contains("report: recording started") }))
            }
            ui.post {
                if (gen != reportGen || shell.hostGone()) return@post
                res.onSuccess { (_, f, url) ->
                    ReportFlow.description = ""
                    when {
                        sent == null -> {
                            ReportFlow.markSaved(f.path, System.currentTimeMillis(), url)
                            noticeShow(ReportNotice.State.Ready(f.path, url)); DiagLog.event("report", "send: saved")
                        }
                        sent.isSuccess -> sent.getOrThrow().let { s ->
                            ReportFlow.clearPending()
                            noticeShow(ReportNotice.State.Sent(s.number, s.url)); DiagLog.event("report", "send: sent as issue #${s.number}")
                        }
                        else -> {
                            val why = sent.exceptionOrNull()?.message ?: "unknown reason"
                            ReportFlow.markSaved(f.path, System.currentTimeMillis(), url)
                            noticeShow(ReportNotice.State.SendFailed(why, f.path, url)); DiagLog.event("report", "send: not sent ($why), saved")
                        }
                    }
                }.onFailure { e ->
                    noticeShow(ReportNotice.State.Failed(e.message ?: e.javaClass.simpleName))
                    DiagLog.event("report", "send: failed (${e.message})")
                }
            }
        }, "report-send").start()
        ui.postDelayed({
            if (gen == reportGen && (reportNotice.state == ReportNotice.State.Compiling || reportNotice.state == ReportNotice.State.Sending)) {
                reportGen++
                noticeShow(ReportNotice.State.Failed("it took more than ${REPORT_TIMEOUT_MS / 1000} s; try again, or send the files in Documents/DS Forever/logs"))
                DiagLog.event("report", "send: timed out")
            }
        }, if (ReportUpload.enabled) REPORT_TIMEOUT_MS * 2 else REPORT_TIMEOUT_MS)
    }

    private fun noticeShow(s: ReportNotice.State) {
        reportNotice.show(s)
        SetupBridge.noticeAsking = reportNotice.asking
    }

    /** Home's pad copy to the notice while it asks (A / B / left / right); true = taken */
    private fun noticePad(down: Int): Boolean {
        if (!::reportNotice.isInitialized || !reportNotice.asking) return false
        val k = when {
            PadInput.isHeld(down, PadInput.SLOT_FACE_BOTTOM) -> "PAD1"
            PadInput.isHeld(down, PadInput.SLOT_FACE_RIGHT) -> "PAD2"
            PadInput.isHeld(down, PadInput.SLOT_DPAD_LEFT) -> "PADDLEFT"
            PadInput.isHeld(down, PadInput.SLOT_DPAD_RIGHT) -> "PADDRIGHT"
            else -> return false
        }
        return reportNotice.padKey(k)
    }

    /** the note of a report saved by itself > Open GitHub issue: the browser, then the note goes */
    private fun openSavedReport() {
        val saved = ReportFlow.pending ?: return
        if (openUrlOnTop(saved.url)) { ReportFlow.clearPending(); DiagLog.event("report", "saved report's issue opened") }
    }

    /**
     * One of our activities on THIS context's display (the bottom screen), so the game's window on the top screen is never covered. A
     * Presentation is a system window over its display's activities: while the activity is up, this window is hidden (GONE), and it
     * comes back when the activity closes ([setClosed]'s hook). Refused on the bottom display: where Android puts it, nothing hidden.
     */
    private fun openOnBottom(cls: Class<*>, tag: String, setClosed: ((() -> Unit)?) -> Unit) {
        val i = android.content.Intent(this, cls).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val displayId = runCatching {
            (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.displayId
        }.getOrNull()
        val decor = shell.hostWindow()?.decorView
        setClosed { ui.post { decor?.visibility = View.VISIBLE; setClosed(null) } }
        val onBottom = displayId != null && runCatching {
            startActivity(i, android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        }.onFailure { android.util.Log.w(tag, "${cls.simpleName} on display $displayId: ${it.message}") }.isSuccess
        if (onBottom) {
            decor?.visibility = View.GONE
            android.util.Log.i(tag, "${cls.simpleName} on display $displayId; the bottom window hidden until it closes")
        } else {
            setClosed(null)
            runCatching { shell.startHostActivity(i) }.onFailure { android.util.Log.w(tag, "${cls.simpleName}: ${it.message}") }
        }
    }

    /** "● REC 3:12 · Stop" over every room while Record 5 minutes runs (board f-problem-report); a tap stops it */
    private lateinit var recIndicator: android.widget.TextView
    private var lastArtKind = ""
    private val recTick = object : Runnable {
        override fun run() {
            if (shell.hostGone() || !::recIndicator.isInitialized) return
            val on = DiagLog.recording()
            if (on) {
                val left = DiagLog.recordLeftMs() / 1000
                recIndicator.text = "\u25CF REC %d:%02d  \u00B7  Stop".format(left / 60, left % 60)
                if (!hitchOn) { hitchOn = true; lastFrameNs = 0L; android.view.Choreographer.getInstance().postFrameCallback(hitch) }
            } else DiagLog.checkEnd()
            recIndicator.visibility = if (on) View.VISIBLE else View.GONE
            if (::settingsView.isInitialized) settingsView.invalidate()
            if (++newSpellTick % 5 == 0) newSpellAsk()                    // f-new-spell-prompt: a fight ended, a window closed
            ui.postDelayed(this, 1000)
        }
    }

    /** while recording: a bottom-screen frame later than [HITCH_MS] is logged with its time (cheap: one callback per frame, 5 minutes) */
    private var hitchOn = false
    private var lastFrameNs = 0L
    private val hitch = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!DiagLog.recording()) { hitchOn = false; return }
            if (lastFrameNs != 0L) {
                val ms = (t - lastFrameNs) / 1_000_000
                if (ms >= HITCH_MS) DiagLog.event("hitch", "bottom screen frame $ms ms (room ${room.name.lowercase()}, game up ${LauncherHooks.gameUp()})")
            }
            lastFrameNs = t
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private val HITCH_MS = 100L

    fun onCreate(savedInstanceState: Bundle?) {
        // the problem report's own log (board f-problem-report): DS Forever's events, never a login, names only to hide them
        DiagLog.init(this)
        Tr.init(this)                              // 2026-11-07: Spanish / French (the Thor's language, or Settings > System > Language)
        DataStaleness.init(this)                   // issues #8 / #9: is the game folder's Data the build's?
        if (LauncherHooks.pkg != null && !LauncherHooks.gameUp()) GameLife.launcherBack()     // issue #12
        DiagLog.event("app", "bottom screen up (inside the launcher: ${LauncherHooks.pkg != null})")
        ArtHarvest.onState { s ->
            val kind = s.javaClass.simpleName + ((s as? ArtHarvest.State.Failed)?.let { ": ${it.why}" } ?: "")
            if (kind != lastArtKind) { lastArtKind = kind; DiagLog.event("art", kind) }
        }
        ArtHarvest.init(this)                     // the harvested art folder, before any view reads a picture (the art harvest, phase 1)
        ArtHarvest.onState(iconArtState)
        // interiors (Track C): the optional background bake (re)starts with the companion; it waits out the game's own start
        IndoorSchedulerStep.factory = IndoorGenerator.Factory { game ->
            game.loadClientKeys()
            NativeIndoorGenerator.factory.open(game)
        }
        IndoorSchedulerStep.companionUp(this)
        // the native sign-in's crash backstop: a login.txt left by an EARLIER process goes; never this run's (the launcher may auto-start
        // the game before this host exists, and the host is rebuilt on every resume)
        if (LauncherHooks.pkg != null && !NativeLogin.active) NativeLogin.clear(this)
        // THE PRIVATE SHARE'S PICTURES (RuntimeArt): copied out once, BEFORE anything loads a picture - the readers remember a miss
        if (RuntimeArt.needed(this)) { unpacking = true; unpackFirst(); return }
        Settings.init(this)
        Theme.init(this)                     // the saved theme (Settings > System > Theme), before any view copies a colour
        GameUi.init(this)
        PolestarQol.init(this)
        GameInput.onStatus = { s -> ui.post { status.text = s.take(110) } }
        // NOTE: do NOT set FLAG_NOT_FOCUSABLE. It leaves display 4 with no focused window, so
        // the first touch waits 5 s for a focus target and the system ANRs us at 0% CPU. This
        // device has per-display focus, so holding focus here does not take it from the game.
        shell.hostWindow()?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            ThemeLive.bg(this, Tok.INK)
        }
        themeRoot = root
        Theme.onChange(themeChanged)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        strip = Strip(this) { pick -> if (pick == Room.HOME) swirlTouched() else pickRoom(pick) }
        // the bar is Strip.BAR high; Home stands Strip.RISE over the room above it, so the strip overlaps the room by that much
        root.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Strip.HEIGHT)
            .apply { topMargin = -Strip.RISE })
        // the tracking bar in the bottom bar (UI Art's glue, listed in AGENT_LOG e8f3d1c): the numbers stay in the Character room
        CharacterBars.listen {
            strip.xp = CharacterBars.xp?.takeIf { it.show }?.let { Strip.Track(if (it.rested) "rested" else "xp", it.fill, if (it.rest > 0) it.restReach else 0f) }
            strip.reputation = CharacterBars.watch?.let { Strip.Track(when (it.colour) { "1", "2" -> "red"; "3" -> "orange"; "4" -> "yellow"; "blue" -> "blue"; else -> "green" }, it.fill) }
        }

        mapView = MapView(this)
        worldMapRoom = WorldMapRoomView(this).apply { loadArt() }
        questsView = QuestsView(this,
            where = { lastMap },
            // TODO(map session): centre the map on the quest once MapView has a focusOn(x, y)
            onShowOnMap = { _ -> show(Room.MAP) })
        PoiHarvest.init(this) { refreshServices() }
        castView = CastView(this) { key -> GameInput.key(key) }
        ItemCd.init(this)                                   // r20: the Hearthstone's kept end time (per character)
        bagsView = BagsView(this)
        // bags controller mode (bags session, AGENT_LOG 2026-09-28): the Bags room while the addon holds the controller
        bagsView.onController = { on, why -> onBagsController(on, why) }
        chatView = ChatView(this) { cmd -> GameInput.chat(cmd) }
        // Chat's rework (AGENT_LOG 2026-10-07): typed and quick chat go out through the add-on's chat rows, in ordered batches
        chatView.tapper = { cells, gap -> TapBridge.tapsSpaced(this, questsPad, cells, gap) }
        characterView = CharacterView(this)
        // B leaves the cursor -> Home (user ruling, every room)
        characterView.onLeave = { roomLeft(Room.CHARACTER) }
        characterView.kits = { if (::bagsView.isInitialized) bagsView.kitItems() else emptyList() }
        characterView.bagItems = { if (::bagsView.isInitialized) bagsView.bagItems() else emptyList() }     // char-more: Replace
        // tab 4 (PvP): the PVP session's page inside the room's frame (its PvpRankTab, UI Art's SPEC 6)
        characterView.pvpDrawer = PvpRankTab::draw
        PvpRankTab.onChange = { characterView.invalidate() }
        // LATER item 11 (the user: "when reloading from the settings, the bottom screen should enter its loading menu and return
        // to home"): the key sent, the start screen covers the bottom screen until the game's new hello, then Home
        val reload = { if (GameInput.reload()) reloadStarted() }
        // SettingsView (map session): its own rows, drawn by the room's tab (board f-settings-tabs; SettingsTabs says which tab)
        settingsView = SettingsView(this, reload)
        val settingsRest = settingsView
        // Frame generation (board f-frame-gen): the player's own Lossless.dll through Android's picker. The Settings room is open only
        // with the game up (v1.0.4), so the picker opens HERE, on the bottom screen's display: the game's window on the top screen is never
        // covered (the picker's task sits over this Presentation until it closes). The copy applies at the next game start
        settingsRest.gameRunning = { LauncherHooks.gameUp() }
        // test51: Add-ons > "QuestTrail settings": Settings closes (its room left as B does), the game opens QuestTrail's own pages and
        // the bottom screen mirrors them (InteractView's QuestTrail window)
        settingsRest.onAddonSettings = { a ->
            if (room != Room.HOME) show(Room.HOME)
            val sent = interactView.openAddonSettings(a.id)
            Log.i("WCINTERACT", "add-on settings ${a.id}: ${if (sent) "asked" else "not asked (no cell yet)"}")
        }
        settingsRest.onChooseDll = { openDllPicker() }
        // "Restart the game" when a frame-gen setting differs from the running game's start: the sign-in's restart (close, wait, Play)
        // b-addons-restart (test40, the user: "The restart button after installing addons breaks our addon because it behaves like it's
        // on home but visually appears like it's still in settings"): the restart went out with the Settings room still up and holding
        // the controller in the OLD game; the new game starts with nothing held, so presses drove Home under a Settings picture. Now the
        // room is left first, the same way as B (its hand-over and guest released while the old game still hears them), then the restart
        // test45 ("The restart game buttons don't work"): the restart did go out, but the game's own Exit Game counts ~20 s outside a
        // rested area with nothing shown meanwhile. Now the restart goes out AT ONCE after the room leave (the launcher's clean exit taps
        // through TapBridge's one worker, queued after the leave's own taps anyway), Home shows the notice until the new run attaches,
        // and every step is logged under WCSETUP
        settingsRest.onRestart = {
            val restart = SetupBridge.restartGame
            Log.i("WCSETUP", "restart from Settings: pressed in $room; launcher hook ${if (restart != null) "present" else "MISSING"}" +
                "; restarting already ${SetupBridge.restarting}")
            SetupBridge.log?.invoke("restart: from Settings (frame generation / add-ons)")
            if (room != Room.HOME) show(Room.HOME)
            if (restart == null) Log.w("WCSETUP", "restart from Settings: no launcher hook, nothing restarts")
            else {
                restartNotice()
                restart()
                Log.i("WCSETUP", "restart from Settings: the launcher's restart called (the game's own Exit Game first)")
            }
        }
        settings = SettingsRoom(this, settingsView, { questsPad })
        settings.pad.onLeave = { show(Room.HOME) }
        settingsRoom = settings
        // the DS WoW toolbar's switch lives in Home's Settings section since 2026-10-06 (renamed from "D-pad room bar")
        // issue #136 (2026-11-11): the row shows the player's pending choice at once (it showed the add-on's old word, "On", while the
        // game was closed or had not taken the tap yet)
        settings.homeSection.dpadState = { lastDpad?.let { d -> dpadShown(d) } }
        settings.homeSection.onDpad = { on -> dpadSet(on) }
        homeView = HomeView(this, mapView, castView, chatView) { r -> show(r) }
        // LATER item 10 (map session): a tap on a numbered quest bubble, or on a quest in the full map's list, focuses it through
        // Home's ONE focus path (its checks and WCHOME logging); a refusal shows on the map
        mapView.onFocusQuest = { id -> homeView.focusQuest(id) { mapView.note(it) } }
        mapView.onPushFocus = { id -> homeView.focusQuest(id) { Log.i("WCMAP", "default focus $id: $it") } }
        // ps3 (the user, 2026-11-11): the Polestar row clears the game's focus for a step with no quest in the log (logged only: a
        // refusal such as "In combat" draws no note), and stops a focus check still retrying the step it moved on from
        mapView.onPushUnfocus = { id -> homeView.unfocusQuest(id) { Log.i("WCMAP", "polestar unfocus $id: $it") } }
        // pk1 (the user, 2026-11-12: "we need to make a way to skip a polestar objective"): Skip / Previous on Map.lua's "pnav" row
        mapView.onPolestarSkip = { prev -> polestarSkipTap(prev) }
        mapView.onCancelFocus = { id -> homeView.cancelFocusWant(id) }
        // the Map room's X on its list (map session, the user's quest-list feedback): untrack through Home's one quest-action path
        mapView.onUntrackQuest = { id -> homeView.untrackQuest(id) { mapView.note(it) } }
        // A on the focused quest clears the focus (Quests q9, qact 39)
        mapView.onUnfocusQuest = { id -> homeView.unfocusQuest(id) { mapView.note(it) } }
        // the map module's Untracked tabs and its portrait (the user, 2026-10-16): A tracks an untracked quest, A on the portrait opens
        // the game's player menu (Quests q38)
        mapView.onTrackQuest = { id -> homeView.watchQuest(id) { mapView.note(it) } }
        // the Untracked rows (the user, 2026-10-16): A tracks and focuses; Y's panel shares and abandons (Abandon asks first)
        mapView.onTrackFocusQuest = { id -> mapView.psRelease(); homeView.trackAndFocus(id) { mapView.note(it) } }   // ps3: a pick
        mapView.onShareQuest = { id -> homeView.shareQuest(id) { mapView.note(it) } }
        mapView.onAbandonArm = { id -> homeView.armAbandon(id) { mapView.note(it) } }
        mapView.onAbandonConfirm = { id -> homeView.confirmAbandon(id) { mapView.note(it) } }
        mapView.onPlayerMenu = { homeView.playerMenu { mapView.note(it) } }
        mapView.onPlayerMenuRun = { id -> homeView.playerMenuRun(id) { mapView.note(it) } }
        mapView.onPlayerMenuSecure = { col -> homeView.playerMenuSecure(col) { mapView.note(it) } }
        // LATER item 5, the generic controller guests (Quests.lua q6): B with nothing nearer to do leaves with B -> Home
        questsView.onLeave = { show(Room.HOME) }
        // B leaves the chat for Home: the disc's press (UI Art's push + gold flash; the user's Home-button feedback 2026-10-01)
        chatView.onLeave = { guestTap(GuestPad.leaveB(questsPad)); strip.homePressed(); show(Room.HOME) }
        // only while a chat guest shows (the Chat room, or Home's chat module): the ChatView's scene also changes when it is given
        // back, and a bnext then reached the addon after the free ("no guest holds", batch 12's touch test)
        // 2026-11-04 (the cursor: each is a click): sent only when the answer CHANGES (the scene changes far more often than B's place)
        chatView.onBackChanged = { home ->
            if ((room == Room.CHAT || (room == Room.HOME && homeView.chatModuleOn)) && home != chatBSaid && guestTap(GuestPad.bnext(questsPad, home)))
                chatBSaid = home
        }
        mapView.onLeave = { show(Room.HOME) }
        mapView.offMapSpots = { qid -> worldMapRoom.questWorldSpots(qid) }      // Map: the arrow to a quest in another zone
        mapView.zoneSpots = { qid -> worldMapRoom.questZoneSpots(qid) }         // Polestar's goal on its own zone map
        // the cursor line's ways out of a map (b-route-city-exits): the maps' world rectangles and names, the game's own
        mapView.worldOf = { id -> worldMapRoom.model.world(id) }
        mapView.mapName = { id -> worldMapRoom.model.name(id) }
        mapView.mapType = { id -> worldMapRoom.model.maps[id]?.type }                  // test42: ways only between zone / city maps
        mapView.zoneAt = { c, wx, wy -> worldMapRoom.model.zoneAt(c, wx, wy)?.name }       // a ride's far dock: "Ride to <zone>"
        mapView.zonesAt = { c, wx, wy -> worldMapRoom.model.zonesAt(c, wx, wy) }          // the multi-hop route's "a walk from here"
        mapView.zonesIn = { c, x0, x1, y0, y1 -> worldMapRoom.model.zonesIn(c, x0, x1, y0, y1) }   // the cross-zone plan's zones
        mapView.zoneIdAt = { c, wx, wy -> worldMapRoom.model.zoneAt(c, wx, wy)?.id }      // a dock's / flight master's map (b-travel-layer)
        worldMapRoom.travelScene = { mapView.travelScene() }                              // the travel layer in the room (b-travel-layer)
        // off-thread loads (test56's ANRs): the views drawn again once a zone file or a picture has arrived
        TravelPaint.onArt = { mapView.postInvalidate(); worldMapRoom.postInvalidate() }
        Terrain.files.onLoaded { mapView.postInvalidate() }
        Terrain.buildings.onLoaded { mapView.postInvalidate() }
        Roads.files.onLoaded { mapView.postInvalidate() }
        mapView.onProbeOptions = { bits -> worldMapRoom.sendProbeOptions(bits) }   // Map: the minimap probe's options to the add-on
        worldMapRoom.onLeave = { show(Room.HOME) }
        worldMapRoom.onFocusQuest = { id -> mapView.psRelease(); homeView.focusQuest(id) { } }
        worldMapRoom.onUntrackQuest = { id -> homeView.untrackQuest(id) { } }
        worldMapRoom.onUnfocusQuest = { id -> mapView.psRelease(); mapView.focusCleared(id); homeView.unfocusQuest(id) { } }
        // ps3 (the user, 2026-11-11): the World map's and the Quests room's own Focus / Remove Focus (QuestPad, sent by the rooms
        // themselves) are the player's picks too: the Polestar row lets go (on Remove Focus as well, or the sync would re-focus the
        // step quest), a cleared focus is not pushed back as the default
        worldMapRoom.onFocusPicked = { id, on -> mapView.psRelease(); if (!on) mapView.focusCleared(id) }
        // pk1: the Map room's Polestar row (its X Skip, Y Previous) shows and presses the Map's own Polestar step
        worldMapRoom.polestar = { mapView.polestarTitle()?.let { WorldMapRoomView.Ps(it, mapView.polestarWords(), mapView.polestarCanSkip(), mapView.polestarCanPrev()) } }
        worldMapRoom.onPolestarSkip = { prev -> mapView.polestarSkip(prev) }
        questsView.onFocusPicked = { id, on -> mapView.psRelease(); if (!on) mapView.focusCleared(id) }
        // LATER item 7: Select opens a cursor on the bar; A on a room is what a touch on its picture does
        // the swirl (Home) picked on the bar = the pause wheel, on Home and in rooms (the user 2026-10-09: "if I select the spiral I want
        // that to open the pause menu"; "Wheel everywhere"); B still goes Home from a room, a room slice switches rooms
        barSelect = HomeBarSelect(this, strip, { questsPad }) { r -> if (r == Room.HOME) openPause("the bar's swirl", byStart = false) else pickRoom(r) }
        // LATER item 23a (the user: "the buttons at the top still show their presses, and that's confusing"): quiet while the cursor is up
        // p35: while the bar holds the controller its D-pad Left / Right move it: Cast shows the bar's arrows there
        barSelect.onOpenChanged = { open -> castView.quietPresses = open; castView.barArrows = open }
        // b-cast-predict-menus (the user: "the predictive cast is triggering when I am navigating menus"): the predicted bar starts only
        // for a press that goes to the game's bars - the router's own holder (the add-on's capture), Home's guests, the menus on screen
        castView.pressesReachGame = {
            CastPredict.reaches(capHolder, room == Room.HOME, homeView.homeGuestWanted(), pauseView.isOpen, barSelect.open,
                interactView.visibility == View.VISIBLE || dialogView.visibility == View.VISIBLE ||
                    spellsOverlay.visibility == View.VISIBLE || startView.visibility == View.VISIBLE,
                barsUsable = true, radialOpen = false)
        }
        // the D-pad layout (the user's design 2026-10-05; Hub hub5, Spells' router): right = Home's map module, left = the Chat room
        // ("The full Chat room": one way into chat), up = the consumables radial (batch b; nothing yet). Down is the bar's own
        barSelect.onDpad = dpad@{ k ->
            // while the radial shows, its own reader moves round the ring; the router's map / chat / home words wait
            if (::radialView.isInitialized && radialView.isOpen && k != "radial" && !k.startsWith("sradial") && k != "smartor") return@dpad
            // the pause menu reads its own controller while it shows: only another Start ("pause") reaches it
            if (::pauseView.isInitialized && pauseView.isOpen && k != "pause") return@dpad
            if (tradOpen()) return@dpad                               // the target radial reads its own controller
            when (k) {
                // Spells' router: Start with nothing holding the controller (the user's Q1: a module that holds Start keeps it)
                // the router's word comes ~0.1-0.2 s after the app's own Start read (startPressed) already acted: dropped then
                "pause" -> if (SystemClock.uptimeMillis() - startReadAt > START_DEDUPE_MS && room == Room.HOME &&
                    !(::radialView.isInitialized && radialView.isOpen)) pauseView.toggle()
                "map" -> { if (room != Room.HOME) show(Room.HOME); homeView.post { homeView.openMapModule() } }
                "chat" -> if (room != Room.CHAT) show(Room.CHAT)
                "radial" -> radialView.toggle()
                // Spells' spell radials (Hub's WowCompanionPadSpellRadial / ...<n>; hub26: "sradial:<n>", Radial.lua q2)
                "sradial" -> radialView.toggle("sradial", 1)
                "smartor" -> radialView.toggle("smartor")     // m62: Smart buffs' OR row (Y with none of its buffs up; Hub's WowCompanionPadSmartyOr)
                else -> if (k.startsWith("sradial:")) radialView.toggle("sradial", k.substringAfter(':').toIntOrNull() ?: 1)
            }
        }
        barSelect.roomNow = { room }        // Select's cursor starts on the room on screen (never on Home: the user's skip-Home rule)
        // the Home button's B follows Home's own state at once (a module full screen shows B; user 15:08)
        homeView.onStateChanged = { discB("Home's state") }
        homeView.onBHome = { strip.homePressed() }
        partyWire()
        MemWatch.testBuild = runCatching { packageManager.getPackageInfo(packageName, 0).versionName?.contains("-test") == true }.getOrDefault(false)
        MemWatch.start(this)                                              // WCMEM: where the memory goes (the 1.0.17 low-memory kills); 1.0.28: Android's trims
        // the spell radials' own names / icons (RadialMeta): the bars and the Controller room show a change at once
        RadialMeta.init(this)
        RadialMeta.onChange { lastPadMap?.let { m -> homeView.onPadMap(m); controllerView.onPadMap(m); spellbookView.onPadMap(m) } }
        homeView.onPartyLeaveRest = { partyLeaveHome("a Home module full screen") }
        // Home's controller reader passes each held mask to Interact's pop-up while it shows: its Make Space ring follows the real A
        // press (display only; one getevent). In a room no reader of Home's runs: the ring falls back to Interact's document
        homeView.onPadHeldAny = { m ->
            // our own sign-in's row on the start screen takes the pad while it shows (LATER 29); then Interact's loot ring
            // (the edge is taken on EVERY mask, whatever the route, so a press held through a route change is not swallowed later)
            val down = m and loginPadHeld.inv(); loginPadHeld = m
            // the report page has the controller through the Settings room's guest pad (or the launcher's keys before Play)
            if (::reportPage.isInitialized && reportPage.isOpen) Unit
            else if (noticePad(down)) Unit                                  // the report's notice on Home (Open issue / OK)
            // B closes the game keyboard the sign-in opened (the game gets the same B: nothing else here takes it then)
            else if (SetupBridge.keyboardOpen && PadInput.isHeld(down, PadInput.SLOT_FACE_RIGHT)) {
                NativeLogin.logEvent("sign-in: keyboard closed (B)"); SetupBridge.keyboard(false)
            }
            else if (startView.menuOpen && PadInput.isHeld(down, PadInput.SLOT_FACE_RIGHT)) startView.menuOpen = false     // B: the menu shuts
            else if (::setupPanel.isInitialized && !loginPrompt.isOpen && setupPanel.pad(down)) Unit      // the setup's pop-up (Track C)
            // b-login-a-input: not in the game (its login screen): A goes to the game, never to our login row / form
            else if (startView.visibility == View.VISIBLE && startView.login != StartView.Login.NONE && !loginPrompt.isOpen && !startView.menuOpen &&
                setupView.visibility != View.VISIBLE && !LauncherHooks.gameUp()) loginPad(down)
            else if (::dialogView.isInitialized && dialogView.visibility == View.VISIBLE) dialogView.onPadHeld(m)   // Interact: the dialog's press look
            else if (::interactView.isInitialized && interactView.visibility == View.VISIBLE) interactView.onPadHeld(m)
        }
        // the Social room (social session). B that leaves it goes HOME (user ruling 2026-09-28 23:12: "Pressing be to leave the
        // cursor should take me back to the home by default for everything"). Whisper opens the Chat room with the name ready
        // (chat's whisperTo)
        socialView = SocialView(this).apply {
            onLeave = { roomLeft(Room.SOCIAL) }
            onWhisper = { name -> show(Room.CHAT); chatView.whisperTo(name) }
            canWhisper = { ChatNames.canWhisper(it) }
        }
        // the Group Finder room (groupfinder session). B that leaves it goes HOME (user ruling 2026-09-28 23:12); Send Message
        // opens the Chat room with the name ready (chat's whisperTo). Who is the room's own tab 3 (user 2026-09-30: "Only in Group
        // Finder, like the game"), so there is no door to another room
        groupFinderView = GroupFinderView(this).apply {
            onLeave = { roomLeft(Room.GROUPFINDER) }
            onWhisper = { name -> show(Room.CHAT); chatView.whisperTo(name) }
            canWhisper = { ChatNames.canWhisper(it) }
        }
        // NO DOORS between the Spellbook and Talents (the user 2026-09-30: "remove the buttons for the spells and talents in those rooms"):
        // each is reached from the bar
        spellbookView = SpellbookView(this, onBind = { e -> spellsCells(e) },
            // the Controller tab (spells session): its cells on spells' own pad rows; it reads ZL / ZR only while no other reader runs
            onPadTab = { cells -> padTabTap(cells) }, otherReader = { castView.isShown })
        // the Controller room: the same "spells" guest on its own page (b17 croom), the same wiring as the Spellbook's tab
        controllerView = ControllerView(this, onPadCells = { cells -> padTabTap(cells) }, onBind = { e -> spellsCells(e) },
            otherReader = { castView.isShown })
        controllerView.onDpadPick = { on, sent -> lastDpad?.let { d -> dpadChoose(d, on, "the Controller room", tap = false) }
            ?: Log.w(HomeView.TAG, "dpad: the Controller room's switch to $on (tap ${if (sent) "sent" else "not sent"}) not kept: no toolbar word yet") }
        talentsView = TalentsView(this, onTap = { row, col -> talentsTap(row, col) })
        ProfessionsStore.init(this)
        // B that leaves the Professions room goes HOME (user ruling 2026-09-28 23:12: "Pressing be to leave the cursor should take
        // me back to the home by default for everything")
        professionsView = ProfessionsView(this).apply { onLeave = { roomLeft(Room.PROFESSIONS) }; lastHandOverAt = { capHolderAt } }
        // the Calendar room (calendar session). B that leaves it goes HOME (user ruling)
        calendarView = CalendarView(this).apply { onLeave = { roomLeft(Room.CALENDAR) } }
        for (v in listOf<View>(homeView, mapView, worldMapRoom, questsView, castView, spellbookView, controllerView, talentsView, professionsView, groupFinderView, characterView, bagsView, chatView, socialView, calendarView, settingsRoom)) {
            content.addView(v, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            v.visibility = View.GONE
        }
        // THE ROOM'S COMBAT CUE (Home; the user: "I want the room that I am in to flash red"): over every room, under the bar and every
        // overlay; it takes no touch
        roomCue = RoomCombatCue(this)
        content.addView(roomCue, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // the user's decision 1 (2026-11-11): the game menu open at the pull keeps the game's pad, the bottom screen locked until it closes
        gameMenuLock = GameMenuLock(this)
        content.addView(gameMenuLock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        interactView = InteractView(this)
        interactKb = ChatKeyboard(this, swap = false).apply { visibility = View.GONE }
        interactView.keyboard = interactKb
        interactView.padSource = { questsPad }
        dialogView = DialogView(this)
        dialogView.appOwnsPad = { padOwner.roomMode() }          // combat_nav: a pop-up / a roll answered with the pad in a fight
        dialogView.rollsOwnPad = { padOwner.holder == null || padOwner.holder == "app" }   // review 2026-11-11: never with a room's press
        startView = StartView(this)
        // INSIDE THE ONE APP (the native login.txt sign-in; the user: "injecting the keys is not the way to go"): the row never types -
        // its button saves / updates the login, which the game uses by itself at its next start (NativeLogin)
        StartMenu.onReport = { openReport() }
        // 1.0.17: did the last session end badly? (LastRun; the start screen asks, Send = the ordinary report, no typing)
        LastRun.init(this)
        SetupBridge.sendCrash = { why -> ui.post { sendReport("Automatic crash report: $why") } }
        StartMenu.autoLogin = { HomeLoginStore.autoLogin(this) }
        StartMenu.onAutoLogin = { on -> HomeLoginStore.setAutoLogin(this, on); if (!on) NativeLogin.clear() }
        StartMenu.onChangeLogin = { restartOnSave = LauncherHooks.gameUp() && HomeLoginStore.autoLogin(this); loginPrompt.open() }
        StartMenu.canExit = { SetupBridge.exitStream != null || LauncherHooks.gameUp() }
        StartMenu.onExit = { (SetupBridge.exitStream ?: SetupBridge.exitGame)?.invoke() }
        StartMenu.canForget = { HomeLoginStore.has(this) }
        StartMenu.onForgetLogin = { HomeLoginStore.clear(this); NativeLogin.clear() }
        StartMenu.canRestart = { LauncherHooks.gameUp() }
        StartMenu.onRestart = { settingsRest.onRestart() }
        StartMenu.canSwitch = { SetupBridge.exitStream != null || ModeBridge.canGoBack() }
        StartMenu.onSwitch = { if (SetupBridge.exitStream != null) SetupBridge.exitStream?.invoke() else ModeBridge.back("the Menu") }
        // the Menu's Region: read once (Config.wtf on disk), then the choice; GameConfigWtf.ensure writes it as the portal at the next start
        var regionNow: String? = null
        StartMenu.region = { GameRegion.chosen(this) ?: regionNow ?: runCatching { GameRegion.current(this, GameFiles.gameRoot(this)) }.getOrDefault("us").also { regionNow = it } }
        StartMenu.onRegion = { code -> GameRegion.choose(this, code); regionNow = code }
        startView.onLoginButton = { gear -> if (gear || LauncherHooks.pkg != null) loginPrompt.open() else signIn() }
        loginPrompt = HomeLoginPrompt(this) { login ->
            settingsView.invalidate()
            if (LauncherHooks.pkg != null) { loginNote = "Saved. The game signs in by itself the next time it starts."; loginShow() }
            else signIn(login, explicit = true)
            SetupBridge.loginSaved()
            // the game never re-reads login.txt in place (test4): it restarts at once and the next start signs in with the new login
            val restartNow = restartOnSave && LauncherHooks.gameUp()
            restartOnSave = false                  // the hold ends here: RESTARTING shows at once
            if (restartNow) {
                signInCheck.restarting(System.currentTimeMillis())
                NativeLogin.logEvent("sign-in: login updated, restarting the game")
                signInStep()
                ui.postDelayed({
                    val restart = SetupBridge.restartGame
                    if (restart == null) NativeLogin.logEvent("sign-in: restart failed (no launcher hook)") else restart()
                }, RESTART_DELAY_MS)
            }
        }
        setupPanel = SetupPanel(this)
        chooserPanel = ChooserPanel(this)
        SetupBridge.openReport = { ui.post { if (!shell.hostGone()) openReport() } }
        SetupBridge.openSavedReport = { ui.post { if (!shell.hostGone()) openSavedReport() } }
        SetupBridge.reportKey = { code, down ->
            (::reportPage.isInitialized && reportPage.key(code, down)) ||
                (::reportNotice.isInitialized && reportNotice.asking && (!down || ReportPage.Model.keyName(code)?.let { reportNotice.padKey(it) } == true))
        }
        // the 5 minutes ran out: the report is saved by itself (no browser mid-game) and its note waits in Settings / the setup screen
        ReportFlow.init(this)
        DiagLog.onEndedByItself = {
            val app = applicationContext
            Thread({ runCatching { ReportFlow.autoSave(app) }.onFailure { Log.w("WCREPORT", "auto-save failed", it) }; SetupBridge.loginSaved() },
                "report-autosave").start()
        }
        DiagLog.onChange { ui.post { ui.removeCallbacks(recTick); recTick.run() } }
        SetupBridge.openLoginForm = { loginPrompt.open() }
        // a refused saved login (M3): "Update login" = the same form, then the game restarts by itself with it (test4 ruling)
        SetupBridge.updateLogin = { restartOnSave = true; loginPrompt.open() }
        // "Enter it on the game screen": the headline says so, the buttons stay; the keyboard opens on the TOP screen for the user
        SetupBridge.enterOnGame = { signInCheck.type(); SetupBridge.keyboard(true); signInStep() }
        // one WCLOGIN line per sign-in button (bug 2 of test3: one log read must time it); never anything of the login itself
        SetupBridge.log = { NativeLogin.logEvent(it) }
        combatKeep = CombatKeepHost(ui,
            tap = { cells -> TapBridge.tapsSpaced(this, talentsPad ?: spellsPad, cells, 300L) },
            column = { id -> lastBook?.let { b -> SpellsModel.tabSpells(b).firstOrNull { (it.spellId ?: it.actionId) == id }?.let { SpellsModel.bindCells(b, it) } } },
            bookReady = { lastBook != null },
            fighting = { homeView.inFight() })
        LauncherHooks.cleanExit = { why, done -> ui.post { cleanExit(why, done) } }
        QuestWatchKeep.init(this)
        TaxiNet.init(this)                                // which known flight points connect, per character (b-route-unknown-taxi)                         // the watch list kept per character (the user's "Restore silently")
        AllIcons.busy = { homeView.inFight() }                            // the background icons wait while the player fights
        if (LauncherHooks.pkg != null) {
            NativeLogin.onTrafficSample = { rx, tx -> signInCheck.traffic(NativeLogin.firstReadAt, rx, tx) }
            ui.post(signInTick)
        }
        ui.postDelayed(guardTick, 3000L)                                 // the exit guard: no room ever without a working B
        hostUpAt = SystemClock.elapsedRealtime(); ui.postDelayed(silenceCheck, AddonSilence.AFTER_MS)   // #147: the add-ons' silence
        // the saved login is changed in Settings (the user 2026-10-08: "Change saved login" moves there)
        settingsView.loginSaved = { HomeLoginStore.has(this) }
        settingsView.onLogin = { loginPrompt.open() }
        settingsView.loginRow = true
        settingsView.onReport = { openReport() }
        // m62 Smart buffs: the Settings section reads the latest padmap, edits by taps, names a spell by the book's two taps
        settingsView.smartDoc = { lastPadMap?.smartBuffs }
        settingsView.smartNow = { lastPadMap?.smartY?.kind }                // m127: the row Y would act on now
        settingsView.smartTaps = { cells -> TapBridge.tapsSpaced(this, spellsPad ?: questsPad, cells, 120L) }   // the user 11-11: Pick added nothing - a repeated cell inside 0.25 s was swallowed (as Polestar's digits)
        settingsView.smartSpellCells = { name ->
            lastBook?.let { b -> SpellsModel.tabSpells(b).firstOrNull { it.name == name }?.let { SpellsModel.bindCells(b, it) } }
        }
        settingsView.onReportSendNow = { DiagLog.stopRecording(); sendReport(ReportFlow.description.ifBlank { "(sent from Settings while recording)" }) }
        settingsView.onReportOpenSaved = { openSavedReport() }
        settings.pad.padOverride = { k ->
            (::reportPage.isInitialized && reportPage.isOpen && run { reportPage.padKey(k); true }) ||
                (::pathReview.isInitialized && pathReview.isOpen && run { pathReview.padKey(k); true })
        }
        settingsView.onRouteReview = { if (::pathReview.isInitialized) pathReview.open() }
        if (LauncherHooks.pkg != null) {
            startView.firstLabel = "Save your Battle.net login"; startView.firstW = 680f      // the longer words
            sweepTrigger()
            NativeLogin.onChange = { if (!shell.hostGone()) { loginShow(); downloadLine() } }
            NativeLogin.bottomState = { bottomState() }
        }
        loginWatch = HomeLoginWatch(
            // the games whose login screen can be up: GameNative's and WoW Forever's logs, each with the package that writes it
            // (the private share: WoW Forever only, at the log GameLocator found on the device)
            // INSIDE THE ONE APP (the launcher, whatever its package is called): its own game only (S5 bug 2026-10-08: the row
            // waited forever because the list had the launcher's OLD package name)
            // the game's log from the ONE chosen game root (test11: the hard-coded old default missed Documents/WoW Forever)
            logs = { LauncherHooks.pkg?.let { own -> listOf(gameLog() to own) }
                ?: if (BuildConfig.SHARE) listOf(gameLog() to "app.wowforever")
                     else listOf(LogTail.DEFAULT_PATH to "app.gamenative", gameLog() to "app.wowforever",
                         gameLog() to GameLocator.DSLAUNCHER) },
            onChange = { s ->
                loginNote = null; loginShow(); awayShow()
                // the game's log, when its buffer has it: a refused sign-in at once (M3)
                if (s.phase == HomeLogin.Phase.FAILED) signInCheck.fatal(System.currentTimeMillis())
            })
        setupRunner = SetupRunner(this)
        setupView = SetupView(this).apply {
            visibility = View.GONE
            onSetUp = { setupGo() }
            onNotNow = { setupDeclined = true; close() }
            onDismiss = { close() }
        }
        // the user 2026-11-12: the "New spell" card is scrapped (Home's one-at-a-time prompt asks about each learned spell)
        spellsOverlay = SpellsOverlay(this,
            // ONE controller reader at a time (home's finding): the Bind box reads the triggers only while the Cast view's is off
            otherReader = { castView.isShown },
            // the Bind box's controller picture, drawn by the Cast room as it draws its own sets (false = no layout yet: stand-ins)
            drawSet = { c, set, dpad, face, lit, icon, fight -> castView.drawSet(c, set, dpad, face, lit, icon, fight) },
            onTap = { col -> spellsTap(col) },                    // b37: the Bind box's Normal / In combat
            onCastBarTaps = { cells -> castBarTaps(cells) },        // GitHub #43 cb3: the box's Cast bar tab and its slots
            // Book b59 / bags-bindbox1: a finger's Keep / Undo / Back in the box the Bags room hosts (a bag item's): Bags' own A / B
            onHostKey = { k -> if (::bagsView.isInitialized) bagsView.boxKey(k) })
        spellsOverlay.visibility = View.GONE
        radialView = HomeRadialView(this, strip, { questsPad }) { cells -> guestTap(cells) }
        pinPicker = BagsPinPicker(this)
        radialView.picker = pinPicker
        PreviewStage.wheelItems = { spell -> radialView.previewItems(spell) }        // f-settings-previews: the wheels' pictures
        // f-radial-middle: on Home the item / spell wheel takes Home's middle slot (the columns part), mapped into the wheel's view
        radialView.homeSlot = { open ->
            if (!open) { homeView.wheelSlot(false); null }
            else if (room != Room.HOME) null
            else homeView.wheelSlot(true)?.also { r ->
                val a = IntArray(2); val b = IntArray(2)
                homeView.getLocationOnScreen(a); radialView.getLocationOnScreen(b)
                r.offset((a[0] - b[0]).toFloat(), (a[1] - b[1]).toFloat())
            }
        }
        // f-radial-behind: the wheel drawn by Home, under its columns, in the wheel view's coordinates moved onto Home's
        radialView.under = { on ->
            homeView.wheelPainter = if (!on) null else { c ->
                val a = IntArray(2); val b = IntArray(2)
                homeView.getLocationOnScreen(a); radialView.getLocationOnScreen(b)
                val save = c.save()
                c.translate((b[0] - a[0]).toFloat(), (b[1] - a[1]).toFloat())
                radialView.drawUnder(c)
                c.restoreToCount(save)
            }
        }
        radialView.underRedraw = { homeView.wheelRedraw() }
        // test26 (the user: "Same thing with the top radial button exactly"): the item / spell wheel shares the pause's guest ("radial"),
        // so its close is made final the same way: B dropped, the guest's late "home" muted, the disc blue before it shows again
        radialView.onClosed = {
            homeView.dropB("pad:radial", HomeBGlyph.WHEEL_MUTE_MS)
            discB("the wheel closed")
            strip.snapRed()
        }
        // b-radial-exclusive (1.0.9, the user: "while another overlay is open, a player should not be able to acutally open the class
        // radials"): one overlay at a time
        // b-class-takeover: the class wheel holds the controller (the guest "class") and casts through the "classr" row
        castView.onClassHold = { hold ->
            classFreeTry++
            val sent = guestTap(GuestPad.cells(questsPad, "class", hold))
            Log.i("WCLOG", "class radial: ${if (hold) "hold" else "free"} ${if (sent) "sent" else "not sent (no qact row)"}")
            if (!hold) { val mine = classFreeTry; ui.postDelayed({ classFreeCheck(mine, 1) }, CLASS_FREE_CHECK_MS) }
            // b-game-wheel-shut (the user: "do what we need to do so that it works how I want"): the same chord opened the GAME's own wheel
            // (a toggle that keeps the right stick until pressed again): its secure cell hides it, at our open and again at our close
            castView.gameWheelShutCol()?.let { col ->
                val shut = TapBridge.tap(this, questsPad, "classr", col) { why -> Log.i("WCLOG", "class radial: game wheel shut refused ($why)") }
                Log.i("WCLOG", "class radial: game wheel shut ${if (shut) "sent" else "not sent"} (${if (hold) "open" else "close"})")
            }
        }
        castView.onClassPick = { col -> TapBridge.tap(this, questsPad, "classr", col) { why -> Log.i("WCLOG", "class radial: cast refused ($why)") } }
        castView.onClassChordLog = { what ->
            Log.i("WCLOG", "class radial: $what capHolder=${capHolder.ifEmpty { "-" }} room=${room.name} pause=${pauseView.isOpen} " +
                "wheel=${radialView.isOpen} tool=${homeView.toolWanted()} barCursor=${barSelect.open}")
        }
        castView.overlayOpen = {
            radialView.isOpen || (::pauseView.isInitialized && pauseView.isOpen) || homeView.toolWanted() ||
                setupView.visibility == View.VISIBLE || loginPrompt.isOpen || barSelect.open ||
                (::dialogView.isInitialized && dialogView.visibility == View.VISIBLE) ||
                (::interactView.isInitialized && interactView.visibility == View.VISIBLE) || spellsOverlay.visibility == View.VISIBLE ||
                (room == Room.HOME && homeView.flow.screen.full.let { it == HomeModule.CHAT || it == HomeModule.MAP })
        }
        pauseView = HomePauseView(this, strip, { questsPad }) { cells -> guestTap(cells) }
        pauseView.onSlide = { t -> pauseSlideAll(t) }
        // the open's REVERSE after the wheel has gone (the user 2026-10-09: "it needs to have a reverse animation for when the B button
        // gets pressed"): the same quick time; input is already the room's / Home's (the wheel is gone in the B frame)
        pauseView.onClose = { from ->
            // test26 (the user: "Now it changes blue and still goes back to red with the b button at the bottom before transitioning back
            // to blue"): the pause's own guest made B lead Home, so the bar's disc (hidden under the hub) still wore its red B look and
            // played it out after the hub landed. The close is final: that B is dropped now, the guest's late "home" says nothing for
            // WHEEL_MUTE_MS, and the disc is plain blue before it shows again (no red-to-blue flow); only Start brings the pause back
            homeView.dropB("pad:radial", HomeBGlyph.WHEEL_MUTE_MS)
            discB("the pause closed")
            strip.snapRed()
            // test40 (the user: "slide the rooms down out of view"): the rooms' bar comes back up with the hub's glide onto it
            stripBack?.cancel()
            if (strip.translationY > 0f) stripBack = android.animation.ValueAnimator.ofFloat(strip.translationY, 0f).apply {
                duration = HomePauseView.GLIDE_MS.toLong()
                interpolator = stripIn
                addUpdateListener { a -> strip.translationY = a.animatedValue as Float }
                start()
            }
            Log.i("WCLOG", "pause: rooms bar back up (${HomePauseView.GLIDE_MS.toInt()} ms)")
            pauseBack?.cancel()
            if (from <= 0f) pauseSlideAll(0f)
            else pauseBack = android.animation.ValueAnimator.ofFloat(from, 0f).apply {
                duration = (UiKit.PAUSE_OPEN_MS / HomePauseView.SPEED * (from / UiKit.PAUSE_OPEN_MS)).toLong().coerceAtLeast(16L)
                addUpdateListener { a -> slideT = a.animatedValue as Float; pauseSlideAll(slideT) }
                start()
            }
        }
        pauseView.onPick = { s -> pausePick(s) }
        pauseView.polestar = { mapView.polestarCanSkip() to mapView.polestarCanPrev() }      // pk1: its Skip / Previous step slices
        // THE TARGET RADIAL: the pause wheel's slide, close and place; a pick runs as the party menu's entries do
        targetRadial = TargetRadialView(this, strip, { questsPad }) { cells -> guestTap(cells) }
        // 1.0.18 (the user: "When holding LB i want the bottom screen to shift around like when opening a radial ... the players bar with
        // the A button glyph ... a cross layout of the other 4 party members with the dpad glyphs ... We are just visualizing it"): on
        // Home, LB alone held (CastView's reader) slides Home's panels aside - Party mode's view with them - and shows the targeting
        lbView = LbPartyView(this)
        // 1.0.19: Home's sides move only far enough for the picture (LbPartyView.SIDE_PX)
        lbView.onSlide = { t -> if (room == Room.HOME && !pauseView.isOpen && !tradOpen()) homeView.pauseSlide(t, LbPartyView.SIDE_PX) }
        castView.onShoulders = { lb, rb -> lbAloneNow = lb && !rb; lbEval() }
        // 1.0.20: the class wheel's close done (its column back over it): the LB picture's turn
        homeView.onClassIdle = { lbEval() }
        castView.onButtonsAlso = { m -> lbView.buttons(m) }
        // 1.0.19: a touch on a controller button presses the game's own action (ActionBar.lua's "tcast" row)
        castView.onTouchCast = { cell -> TapBridge.tap(this, questsPad, "tcast", cell) { why -> Log.i("WCLOG", "touch cast refused ($why)") } }
        // the context menus' icons (the raid markers too) asked for early, so the phone's icon harvest has them before a wheel opens
        ui.postDelayed({ runCatching { MenuIcons.prefetch() } }, 8_000L)
        targetRadial.onSlide = { t -> pauseSlideAll(t) }
        targetRadial.onClose = { from -> pauseView.onClose(from) }
        targetRadial.barsBottom = { pauseView.barsBottom?.invoke() ?: 0f }
        targetRadial.onDo = { d, name -> targetDo(d, name) }
        // Home's portrait menu: the context radial (the user: "no more drop down menus in any of those places"); the map module holds the
        // controller, so the wheel reads it (READ) and the map ignores its keys meanwhile
        mapView.radialOpen = { tradOpen() && targetRadial.opener == "portrait" }
        mapView.onPortraitRadial = { name ->
            targetRadial.open("portrait", TargetRadialView.Input.READ, name, { d, _ ->
                when (d) {
                    is PlayerMenu.Do.Run -> mapView.onPlayerMenuRun?.invoke(d.id)
                    is PlayerMenu.Do.Secure -> mapView.onPlayerMenuSecure?.invoke(d.col)
                    is PlayerMenu.Do.Refused -> mapView.note(d.why)
                    else -> {}
                }
            }, {})
        }
        pauseView.inFight = { homeView.inFight() }
        pauseView.barsBottom = {
            if (room != Room.HOME || !homeView.isShown || homeView.barsBottom() <= 0f) 0f else {
                val a = IntArray(2); val b = IntArray(2)
                homeView.getLocationOnScreen(a); pauseView.getLocationOnScreen(b)
                homeView.barsBottom() + (a[1] - b[1])
            }
        }
        shell.setHostContent(object : FrameLayout(this) {
            // b-release-spirit (test16: a bottom-screen dialog press reached nothing, not even the dialog's own view): while a dialog
            // shows, a finger's DOWN says which view on top took it, so one log read tells "covered" from "pressed"
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                val r = super.dispatchTouchEvent(e)
                if (e.actionMasked == MotionEvent.ACTION_DOWN && ::dialogView.isInitialized && dialogView.visibility == View.VISIBLE) {
                    val above = (indexOfChild(dialogView) + 1 until childCount).map { getChildAt(it) }
                        .filter { it.visibility == View.VISIBLE }.map { it.javaClass.simpleName.ifEmpty { "view" } }
                    android.util.Log.i("WCDLG", "finger down at ${e.x.toInt()},${e.y.toInt()} with a dialog up; visible above it: " +
                        (above.joinToString().ifEmpty { "none" }) + (if (r) "" else "; nobody took it"))
                }
                return r
            }
        }.apply {
            addView(root, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // the bar selector's cursor and hint plate: over the rooms and the bar (it takes no touch)
            addView(barSelect, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // the Spellbook's Bind box and card: over the rooms and the bar, under the NPC windows and the dialogs
            addView(spellsOverlay, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(radialView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(pauseView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(targetRadial, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(lbView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // the radial's pin picker: over the room only (the bar stays below it)
            addView(pinPicker, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { bottomMargin = Strip.HEIGHT })
            addView(interactView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // 1.0.8: the Chat keyboard for the NPC windows' fields (Send Mail, the auction-house search), along the bottom over them
            addView(interactKb, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))
            // the game's dialogs lie over everything, an NPC window included
            addView(dialogView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // the start screen lies over all of it, and goes with the game's first current word
            addView(startView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // the launcher's setup (Track C): over the start screen before Play, its sign-in pop-ups over the rooms in the game
            addView(setupPanel, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // the start-up chooser (Local / Streaming): over the setup; only before a mode is entered (or Streaming's page)
            addView(chooserPanel, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // our own Battle.net sign-in's prompt (LATER 29): over the start screen, whose gear / first button opens it
            addView(loginPrompt, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // the self-setup's prompt and progress: over the start screen too
            addView(setupView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Record 5 minutes' indicator (board f-problem-report): over everything, top right; a tap stops the recording
            // the report page (board f-problem-report): over the rooms and the setup, under the REC badge
            reportPage = ReportPage(this@CompanionHost).apply {
                openUrl = { url -> openUrlOnTop(url) }
                onClosed = { DiagLog.event("report", "report page closed") }
            }
            addView(reportPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // SafePath's review (b-safepath-review): over Settings as the report page is, opened by Route data's "Review paths"
            pathReview = SafePathReview(this@CompanionHost).apply { onClosed = { DiagLog.event("safepath", "review closed") } }
            addView(pathReview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            reportPage.onSend = { words -> sendReport(words) }
            // Send report's notice on Home (compiling, then ready / failed)
            reportNotice = ReportNotice(this@CompanionHost).apply {
                onOpen = { s -> if (openUrlOnTop(s.url)) ReportFlow.clearPending() }
                onDismiss = { SetupBridge.noticeAsking = false }
                onOpenUrl = { url -> if (openUrlOnTop(url)) ReportFlow.clearPending() }
            }
            addView(reportNotice, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            recIndicator = android.widget.TextView(this@CompanionHost).apply {
                textSize = 16f; setTextColor(android.graphics.Color.WHITE); setPadding(22, 10, 22, 10)
                setBackgroundColor(android.graphics.Color.argb(220, 150, 20, 20)); visibility = View.GONE
                setOnClickListener { DiagLog.stopRecording(); visibility = View.GONE }
            }
            addView(recIndicator, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.END).apply { topMargin = 8; rightMargin = 8 })
        })
        show(Room.HOME)
        ui.postDelayed(castAskTick, 1_000L)                                  // the one-time tap-to-cast bar question (CastBarAsk)

        if (!hasAllFiles()) {
            status.text =
                "needs all-files access: appops set com.jrees.wowcompanion MANAGE_EXTERNAL_STORAGE allow"
            startView.line = "The companion may not read files yet"
        }

        // a new start mark for the input gate before the log is replayed: nothing written before THIS start is current, even
        // when the Activity is recreated in the same process (quests' InputGate, the app-start rule)
        GameInput.gate.restart()
        // WHERE THE GAME IS (GameLocator, one root read off the main thread): our builds follow GameNative + WoW Forever; the PRIVATE
        // share follows only the WoW Forever log the device itself names (no hard-coded paths there)
        Thread {
            // the private fork first when it is set up (the user 2026-10-08), else WoW Forever: both use the same game folder
            val wf = (if (BuildConfig.SHARE) null else GameLocator.locate(GameLocator.DSLAUNCHER)) ?: GameLocator.locate()
            game = wf
            ui.post { if (!shell.hostGone()) startTail() }
        }.start()
        // the chosen game folder changes (the launcher's Choose folder): the log follow moves with it, no restart (test11)
        getSharedPreferences(GameFiles.PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(gameRootChanged)
        // the self-setup's first look, once the start has settled
        ui.postDelayed({ setupCheck() }, 5_000L)
        loginWatch.start()
        HomeRoot.shellAlive = { RootShell.alive() }
        ui.post(rootCheck)
        directPads()
        // a look at the sign-in row with no game (the emulator has neither root nor the game; adb only):
        //   am start -n com.jrees.wowcompanion/.MainActivity --es login_preview first|saved|failed|queue|prompt
        //   the one app's rows (UI Art's look, 2026-10-08): --es login_preview native_save|native_signing|native_late|native_auth
        shell.hostIntent()?.getStringExtra("login_preview")?.let { p -> ui.postDelayed({ loginPreview(p) }, 1_500L) }

        ui.post(object : Runnable {
            override fun run() {
                // Home holds the same map (compact), so it is fed there too
                if (room == Room.MAP || room == Room.HOME) mapView.update(lastMap,
                    if (lastMapAt == 0L) 0 else System.currentTimeMillis() - lastMapAt)
                GameUi.tick()
                homeView.tick()
                roomCue.tick()
                discB("no fresh word")      // on Home a B with no fresh word goes back to the house
                ui.postDelayed(this, 1000)
            }
        })
    }

    fun onStart() {
        DirectPad.start()
        if (room == Room.HOME) ui.post { partyAsk(PartyModel.Ask.HOME_SHOWN); homeView.partyHomeOpened() }     // back from the background: Party mode's documents
        TapBridge.watchPad()        // the quiet pad's clock from the start (Quests, step 0): the first tap already knows the last press
        if (unpacking) return
        GameUi.onVisible(true)
        barSelect.appBack()
    }

    fun onStop() {
        if (unpacking) return
        // the sign-in prompt never stays open behind the app's back (its fields are cleared; the review)
        if (::loginPrompt.isInitialized && loginPrompt.isOpen) loginPrompt.close()
        // the radial never stays open (its reader, its hold) while the app is away
        if (::radialView.isInitialized) radialView.shut()
        if (::pauseView.isInitialized) { pauseView.shut(); pauseView.clearHint() }
        if (::targetRadial.isInitialized) targetRadial.shut()
        DirectPad.shutdown()
        // nothing of the game's UI stays hidden without its replacement in view
        GameUi.onVisible(false)
        // Select is the game's again while the app is away (the bar selector asks for it back when it returns)
        barSelect.appAway()
    }

    private fun show(r: Room, byB: Boolean = false, motion: Boolean = true) {
        MemWatch.room = r.name.lowercase()                                // WCMEM: what is on show
        flushBLeave()
        // PARTY MODE (1.1, 4.2): Home shown again re-asks the add-on's documents; leaving Home turns Party mode off only by the switch
        if (r == Room.HOME && room != Room.HOME) { partyAsk(PartyModel.Ask.HOME_SHOWN); homeView.partyHomeOpened() }
        if (r != Room.HOME && room == Room.HOME) partyLeaveHome("room $r")
        if (r == Room.HOME) ui.postDelayed({ newSpellAsk() }, 800L)          // f-new-spell-prompt: back Home, the next one
        // the pause menu never stays over a room change (a whisper's jump, a game window); its own picks run after it has folded
        if (::pauseView.isInitialized && pauseView.isOpen) pauseView.shut()
        if (::pauseView.isInitialized && r != room) pauseView.clearHint()
        if (tradOpen() && r != Room.HOME) targetRadial.shut()
        // SafePath's review belongs to Settings: it closes when another room comes up
        if (r != Room.SETTINGS && ::pathReview.isInitialized && pathReview.isOpen) pathReview.close()
        // any room change ends the bar selector first. NO GAP (the user 2026-10-06): into another room the bar's hold is KEPT for that
        // room's own take to pre-empt (capture v7 / v12; HomeBarSelect.closeFor, RoomHandOver.Bar); Home or the same room: freed now
        barSelect.closeFor(r, room)
        // and the room's combat cue: a room shown anew is cued only once a document says it holds through the fight
        if (::roomCue.isInitialized && r != room) roomCue.off()
        // MID-FIGHT (combat_nav Step 3, 2026-11-11): the router in room mode lets go ONLY through the capture's secure leave cell, and
        // PadOwnerSync taps it once nothing on the bottom screen wants the pad (Home); room to room is the add-on's in-place swap, so the
        // new room's take goes out as usual. The old room's own release taps are skipped (refused in a fight anyway)
        val fightLeft = r != room && padOwner.roomMode()
        // EVERY ROUTE THE SAME (the user 2026-10-01: "it should act the EXACT same for all rooms"; picked "Every room opens the game's own
        // window behind it, as Professions does"): the rooms whose hand-over lived on the touch path only (Settings, Bags, the Spellbook and
        // its Talents, Character) let go HERE, before the new room takes, for every route that leaves them - a touch, the bar cursor, a game
        // window's jump, a whisper, the map pin, the Spells overlay. Each tap is guarded on the room still holding (no bounce). Skipped when
        // the fight leave went out above: in an armed fight only that secure cell can let go
        val leaving = room
        if (!fightLeft) { if (byB) bLeave = { touchRoomsLeave(leaving, r) } else touchRoomsLeave(room, r) }
        // the Professions room holds the controller while it shows (a guest of the shared capture): `hold` on entering. Leaving it
        // closes the profession as the game would (user ruling 2026-09-30, "Copy the game"): the room's secure close cell clicks the
        // game's own close button and hands the controller back; B already did both (professions session)
        if (room == Room.PROFESSIONS && r != Room.PROFESSIONS) {
            if (byB) bLeave = bLeave.let { f -> { f?.invoke(); professionsView.leave() } } else professionsView.leave()
        }
        if (byB && bLeave != null) ui.postDelayed(bLeaveRun, B_LEAVE_MS)
        if (r == Room.PROFESSIONS && room != Room.PROFESSIONS) enterProfessions = true
        // the Calendar room holds the controller while it shows (a guest of the shared capture): `f` on leaving, `h` on entering
        val calIn = r == Room.CALENDAR && room != Room.CALENDAR
        if (room == Room.CALENDAR && r != Room.CALENDAR) calendarView.onHidden()
        // THE CHAT ROOM'S GLIDE (b-chat-slide): a glide still running lands at once; Home's picture is taken BEFORE it gives the map
        // and the bars back (after that its slots are empty)
        chatGlideEnd()
        val chatOpening = room == Room.HOME && r == Room.CHAT && homeView.isShown
        val chatClosing = room == Room.CHAT && r == Room.HOME && chatView.isShown
        val openPane = if (chatOpening) homeView.chatPaneBox() else null
        val homePicture = if (openPane != null) homeShot() else null
        // THE ROOMS' OPEN / CLOSE MOTION (f-room-animations): every other room change moves too. The room being left, as it looks
        // now, is pictured BEFORE anything is taken from it (Home gives its map and bars back right below)
        roomMotionEnd()
        val outgoing = shownView
        val roomMotion = motion && r != room && !chatOpening && !chatClosing && outgoing != null && outgoing.isShown &&
            !(::pauseView.isInitialized && pauseView.isOpen) && !tradOpen()
        // Home is pictured as it leaves (its map and bars go back to their rooms right below); a room keeps drawing itself
        val outPicture = if (roomMotion && outgoing === homeView) viewShot(homeView) else null
        val fromRoom = room
        // Home gives the map and the bars back BEFORE the loop below walks the rooms, and takes
        // them AFTER it: never re-parent a view while `content`'s children are being walked
        if (r != Room.HOME) { homeView.closeAuras(); homeView.giveBack() }     // an open buff list hands the controller back
        val again = r == room
        // the Social room holds the controller while it shows (a guest of the shared capture): `free` on leaving, `hold` on entering
        val socialIn = r == Room.SOCIAL && room != Room.SOCIAL
        if (room == Room.SOCIAL && r != Room.SOCIAL) { socialAsk.hidden(); socialTry++ }
        if (room == Room.SOCIAL && r != Room.SOCIAL) socialView.model.freeTap()?.let { TapBridge.tap(this, socialView.model.pad, it.row, it.col) }
        // the Group Finder room holds the controller while it shows (a guest of the shared capture): `f` on leaving, `h` on entering
        val gfIn = r == Room.GROUPFINDER && room != Room.GROUPFINDER
        if (room == Room.GROUPFINDER && r != Room.GROUPFINDER) { groupFinderView.onHidden(); gfReleaseCheck() }
        // the Quests and Chat rooms hold the controller while they show (the generic guests "quests" / "chat", LATER item 5): free on
        // leaving, hold on entering. No document ever opens either room by itself, so every show() here is a touch's
        val questsIn = r == Room.QUESTS && room != Room.QUESTS
        if (room == Room.QUESTS && r != Room.QUESTS) questsView.onHidden()
        val chatIn = r == Room.CHAT && room != Room.CHAT
        if (room == Room.CHAT && r != Room.CHAT) { chatHoldTry++; chatAsk.hidden(); guestTap(GuestPad.cells(questsPad, "chat", hold = false)) }
        // the Map room the same (the guest "map", map session's MapView); Home's own full-screen map does not hold it
        val mapIn = r == Room.MAP && room != Room.MAP
        if (room == Room.MAP && r != Room.MAP) worldMapRoom.onHidden()
        val was = room
        // which room the app itself left, and when: a "B" release echoing this leave is not the player's B (HomeBack.isOwnLeave)
        if (r != was) { leftIds = HomeBack.leftOf(was.name, r.name); leftAt = SystemClock.uptimeMillis() }
        room = r
        // Chat's cursor only where presses reach it: the Chat room, not Home's borrowed full-screen chat (chat session's switch)
        chatView.cursorOnShow = r == Room.CHAT
        if (enterProfessions) { enterProfessions = false; professionsView.post { professionsView.enter() } }
        if (questsIn) questsView.onShown()
        if (mapIn) worldMapRoom.onShown()
        if (chatIn) { chatHold(0); chatView.post { chatView.onBackChanged(chatView.bLeadsHome()) } }
        strip.active = r
        // ONE EXIT FEEDBACK for every room (the user 2026-10-09: "the B return animation is inconsistent across the rooms"): any room -> Home
        // plays the disc's press (the yellow flash) in the same frame, whichever route left (B, a room's own leave, the bar, a touch);
        // half the rooms' onLeave went Home without it
        // (RC3: `was`, not `room` - room is already r here, so RC2's check never fired) and the B glyph goes in the same frame
        if (r == Room.HOME && was != Room.HOME) { strip.homePressed(); homeView.dropB(capHolder) }
        discB("room ${r.name}")       // the disc follows the room at once (red B in every room)
        val v: View = when (r) {
            Room.HOME -> homeView
            Room.MAP -> worldMapRoom; Room.QUESTS -> questsView
            Room.SPELLBOOK -> spellbookView
            Room.TALENTS -> talentsView
            Room.PROFESSIONS -> professionsView
            Room.GROUPFINDER -> groupFinderView
            Room.CHARACTER -> characterView; Room.BAGS -> bagsView; Room.CHAT -> chatView
            Room.CONTROLLER -> controllerView
            Room.SOCIAL -> socialView
            Room.CALENDAR -> calendarView
            Room.SETTINGS -> settingsRoom
        }
        shownView?.takeIf { it !== v }?.let { it.alpha = 1f; it.scaleX = 1f; it.scaleY = 1f }
        shownView = v
        for (i in 0 until content.childCount) content.getChildAt(i).visibility = View.GONE
        v.visibility = View.VISIBLE
        // the disc pressed ON Home goes back from a full-screen module; arriving from a room lands
        if (r == Room.HOME) { homeView.borrow(); if (again) homeView.homeDisc() else homeView.land() }
        if (r == Room.CHAT) chatView.focusInput()
        if (openPane != null && homePicture != null) chatGlideStart(true, openPane, homePicture)
        else if (chatClosing) homeView.chatPaneBox()?.let { chatGlideStart(false, it, null) }
        else if (roomMotion && outgoing != null && outgoing !== v) roomMotionStart(v, outgoing, fromRoom, r, outPicture)
        if (socialIn) {
            socialHold(0)
            socialView.onShown()
        }
        if (gfIn) groupFinderView.onShown()
        if (calIn) calendarView.onShown()
        // ... and the same rooms take the controller here, for every route that brings them up (each tap guarded on the room not
        // holding already: a room the GAME opened holds before its jump lands here, so nothing is tapped then - the trainer lend too)
        touchRoomsEnter(was, r)
        // in a fight the pad follows the room shown now (PadOwnerSync)
        padOwnerStep()
        GameUi.onRoom(r)
    }

    /**
     * Social's hold, CONFIRMED (b82, the user: "The social test is working now like half the time"; measured 21:39:13: the hold tap
     * went out ~120 ms after the bar let the controller go and landed on the freelook park point - padstray n 15 - so Social never
     * held and its B could not work). Not held by Social [HoldAsk.EVERY_MS] later while the room still shows = sent again (b112: HoldAsk, 4 asks),
     * twice at most (the take is idempotent: "already held" when it did land).
     */
    private fun socialHold(attempt: Int) {
        // b112 (Social's note): Map's HoldAsk - every 0.7 s while not held, 4 asks at most; a show BEFORE Social's first "me" doc (no
        // pad codes yet: nothing could be asked) asks once on that first doc ([socialAsk].padArrived, from the "social" route)
        if (attempt == 0) { socialAsk.shown(); socialTry++ }
        if (room != Room.SOCIAL || capHolder == "social") { socialAsk.answered(); return }
        if (!socialAsk.due()) return
        val t = socialView.model.holdTap()
        val sent = t != null && TapBridge.tap(this, socialView.model.pad, t.row, t.col)
        if (attempt > 0) Log.i("WCHOME", "social: hold again ($attempt) ${if (sent) "sent" else "not sent${if (t == null) " (no pad codes yet)" else ""}"}")
        val mine = socialTry
        if (socialAsk.asked(sent)) ui.postDelayed({ if (mine == socialTry) socialHold(attempt + 1) }, HoldAsk.EVERY_MS)
    }
    private val socialAsk = HoldAsk()
    private var socialTry = 0

    /**
     * combat_nav Step 3 (PadOwnerSync): does the bottom screen want the pad now - a room, a Home module (map, chat, an aura list, the
     * tool), the bar cursor, a radial, the pause or target radial, InteractView or DialogView. Never while the game menu has it.
     */
    private fun bottomWantsPad(): Boolean = bottomWantsPadBesidesDialogs() || dialogWantsPad()

    /** everything but DialogView (Interact i152: a dead player's DEATH pop-up alone never asks for an ENTER) */
    private fun bottomWantsPadBesidesDialogs(): Boolean =
        room != Room.HOME || homeView.homeGuestWanted() || tradOpen() || radialView.isOpen || barSelect.open ||
            (::pauseView.isInitialized && pauseView.isOpen) || spellsOverlay.visibility == View.VISIBLE ||
            // a loot / NPC window left with B in this fight wants the pad no more (its LEAVE went out; Step 4)
            (::interactView.isInitialized && interactView.visibility == View.VISIBLE && !padOwner.npcLeft)

    // a pop-up as the box, or the rolls open - never the folded rolls pill (DialogView.wantsPad; the review of 2026-11-11)
    private fun dialogWantsPad(): Boolean = ::dialogView.isInitialized && dialogView.wantsPad()

    /** one look of the reconciler: from every current interact document, show(), and a tick while a fight lasts */
    private fun padOwnerStep() {
        val now = SystemClock.uptimeMillis()
        // combat_nav Step 4: B at a held window's top level (doc.fightBack stepped): the app's LEAVE, once
        padOwner.backAsked()?.let { (row, col) ->
            leftIds = setOfNotNull(padOwner.holder); leftAt = now
            val sent = TapBridge.tap(this, questsPad, row, col) { }
            Log.i("WCHOME", "pad owner: B at the window's top level -> LEAVE $row $col ${if (sent) "sent" else "not sent"}")
            return
        }
        // B GOES HOME FROM EVERY ROOM IN A FIGHT (the user, 2026-11-12: "ALL ROOMS SHOULD ALWAYS GO HOME WITH B EVEN IN COMBAT"; 1429: B in
        // Bags did nothing after the pull): the room's own B at its top level let go through the capture, which in a fight only asks
        // (Interact i151's doc.fightHome) - Home now, and show() runs this again: nothing wants the pad there, so the LEAVE goes out
        padOwner.homeAsked()?.let { who ->
            if (PadOwnerSync.fightHomeGoes(who, room.name)) {
                Log.i("WCHOME", "pad owner: B in ${room.name} let $who go in the fight -> Home (the LEAVE follows)")
                show(Room.HOME)
                return
            }
            Log.i("WCHOME", "pad owner: B let $who go in the fight, not the room on screen (${room.name}): stays")
        }
        val others = bottomWantsPadBesidesDialogs()
        val dlg = dialogWantsPad()
        val deathOnly = !others && dlg && dialogView.wantsPadForDeathOnly()
        val outOfFight = padOwner.mode == null
        val st = padOwner.step(others || dlg, now, deathOnly)
        if (outOfFight && st.act == PadOwnerSync.Act.LEAVE) {
            // Interact i152 (the user, 2026-11-12: "I died and then my jump input just stopped working"): the router stuck in room mode
            // out of a fight with nobody holding - every routed press went to the add-on's empty sink; its own LEAVE flips it back
            st.cell?.let { (row, col) ->
                val sent = TapBridge.tap(this, questsPad, row, col) { }
                Log.i("WCHOME", "pad owner: OUT-OF-FIGHT room mode -> LEAVE ${if (sent) "sent" else "not sent"} ($row $col)")
            }
            return
        }
        when (st.act) {
            PadOwnerSync.Act.NONE -> {}
            PadOwnerSync.Act.ENTER, PadOwnerSync.Act.LEAVE -> st.cell?.let { (row, col) ->
                // the LEAVE's release by "B" is the app's own (it must never pull a room Home): HomeBack.isOwnLeave
                if (st.act == PadOwnerSync.Act.LEAVE) { leftIds = setOfNotNull(padOwner.holder); leftAt = now }
                val sent = TapBridge.tap(this, questsPad, row, col) { }
                Log.i("WCHOME", "pad owner: ${st.act} $row $col ${if (sent) "sent" else "not sent"} (room $room, mode ${padOwner.mode})")
            }
            PadOwnerSync.Act.STUCK -> {
                Log.w("WCLOG", "pad owner: STUCK - ${PadOwnerSync.TRIES} taps and the mode is still ${padOwner.mode ?: "room (out of a fight)"} (room $room, " +
                    "holder ${padOwner.holder ?: "-"})")
                if (::roomCue.isInitialized) roomCue.stuck()
            }
        }
    }

    /** the let-go half of the hand-overs that lived in pickRoom (touch only) until 2026-10-01: decided by RoomHandOver for every route */
    private fun touchRoomsLeave(was: Room, r: Room) = handOver(RoomHandOver.leave(handKind(was), handKind(r), was == r, handHeld()))

    /** the take half (opened through the game's own window, Bags / Character / Spells hold already and nothing is tapped) */
    private fun touchRoomsEnter(was: Room, r: Room) = handOver(RoomHandOver.enter(handKind(was), handKind(r), was == r, handHeld()))

    private fun handKind(r: Room) = when (r) {
        Room.SETTINGS -> RoomHandOver.Kind.SETTINGS
        Room.BAGS -> RoomHandOver.Kind.BAGS
        Room.SPELLBOOK -> RoomHandOver.Kind.SPELLBOOK
        Room.TALENTS -> RoomHandOver.Kind.TALENTS
        Room.CONTROLLER -> RoomHandOver.Kind.CONTROLLER
        Room.CHARACTER -> RoomHandOver.Kind.CHARACTER
        Room.MAP -> RoomHandOver.Kind.MAP
        else -> RoomHandOver.Kind.OTHER
    }

    private fun handHeld() = RoomHandOver.Held(spellbookView.held, talentsView.held, croom = controllerView.held)

    /** each room's own cells (the owners': Bags' game doors, the Spellbook's secure open / topen / close, Character's secure grid) */
    private fun handOver(taps: List<RoomHandOver.Tap>) {
        for (t in taps) when (t) {
            RoomHandOver.Tap.SETTINGS_LEAVE -> settings.pad.leave()
            RoomHandOver.Tap.BAGS_LEAVE -> bagsView.touchedOut()
            RoomHandOver.Tap.SPELLS_CLOSE, RoomHandOver.Tap.TALENTS_CLOSE -> spellbookView.leaveRoom()
            RoomHandOver.Tap.CONTROLLER_CLOSE -> controllerView.leaveRoom()
            RoomHandOver.Tap.CHARACTER_CLOSE -> characterView.touchedOut()
            RoomHandOver.Tap.MAP_LEAVE -> worldMapRoom.touchedOut()
            RoomHandOver.Tap.SETTINGS_ENTER -> settings.pad.enter()
            RoomHandOver.Tap.BAGS_ENTER -> bagsView.touchedIn()
            RoomHandOver.Tap.SPELLS_OPEN -> spellbookView.enterRoom(talents = false)
            RoomHandOver.Tap.TALENTS_OPEN -> spellbookView.enterRoom(talents = true)
            RoomHandOver.Tap.CONTROLLER_OPEN -> controllerView.enterRoom()
            RoomHandOver.Tap.CHARACTER_OPEN -> characterView.touchedIn()
            RoomHandOver.Tap.MAP_ENTER -> worldMapRoom.touchedIn()
        }
    }

    // ================================================================ PARTY MODE (PARTY_BUILD.md 1.1, 2.4, 4.2, 4.6, 4.7)

    /** the module's touches and the player menu's outcomes: every tap from `partycfg.pad` (PartyTaps), on the pad the app holds */
    private fun partyWire() {
        val live = PartyModel.live
        // m129 (party_design.md C, Touch): a plate's tap is its pick cell; the add-on's snippet decides (off = Target; another member
        // = the pick moves; the picked one = the Touch action). The module draws a pick at once (the partybox corrects it)
        homeView.onPartyCell = { i ->
            val cells = PartyTaps.choose(live.cfg, i)
            when {
                !live.tapsAllowed(SystemClock.uptimeMillis()) -> Log.i("WCHOME", "party: tap on $i refused (the roster just changed)")
                cells == null -> Log.i("WCHOME", "party: tap on $i: no cell (no partycfg / column)")
                TapBridge.taps(this, questsPad, cells) { why -> Log.i("WCHOME", "party: tap on $i not sent: $why") } ->
                    if (live.choose(i) != null) partyRedraw()
                else -> {}
            }
        }
        // m157 (the user on 1462: "pressing B in a player inspect radial opened from party mode does not close the radial"; "holding on
        // the pet bar does not open the pet inspect"): the hold's batch is MENU HOLD + the menu read (PartyTaps.hold; the pet: its own
        // menu cell). With Party mode on the pad then hands the wheel every press (B = its back / close, never Party mode off); with it
        // off nothing of ours holds the pad, so the wheel reads the presses itself
        homeView.onPartyLong = { i ->
            // a wheel of the party's still open closes FIRST (its MENU DONE before this hold's MENU HOLD, never after it)
            if (partyWheelOpen()) targetRadial.close("another hold")
            val h = PartyTaps.hold(live.cfg, i)
            if (h != null && live.tapsAllowed(SystemClock.uptimeMillis()) && TapBridge.taps(this, questsPad, h.first) { }) {
                partyWheel(i, byPad = false, padKeys = h.second && live.party?.onLive == true, menuHeld = h.second)
            } else Log.i("WCHOME", "party: hold on $i: the menu was not asked")
        }
        // party_design.md E: the Select tab turns Party mode on (off / not bound) or off (on); the look follows the document only
        homeView.onPartyTag = { wantOn ->
            val cells = if (wantOn) PartyTaps.on(live.cfg) else PartyTaps.off(live.cfg)
            val sent = cells?.let { TapBridge.taps(this, questsPad, it) { } } == true
            Log.i("WCHOME", "party: the Select tab: ${if (wantOn) "on" else "off"} ${if (sent) "tapped" else "not sent"}")
        }
        homeView.partyMenu.onDo = { d, byPad ->
            val menu = homeView.partyMenu
            when (d) {
                is PlayerMenu.Do.Run -> {
                    if (d.id >= 0) GuestPad.playerMenuRun(questsPad, d.id)?.let { TapBridge.taps(this, questsPad, it) { } }
                    // q49 ap = "whisper": the Chat room, replying to the member (as Social's Whisper opens it)
                    if (d.app == "whisper" && menu.name.isNotEmpty()) { val n = menu.name; ui.post { show(Room.CHAT); chatView.whisperTo(n) } }
                }
                is PlayerMenu.Do.Secure -> TapBridge.taps(this, questsPad, listOf((if (d.row == "party") PartyModel.live.cfg?.pad?.row ?: "party" else d.row) to d.col)) { }
                is PlayerMenu.Do.Refused -> Log.i("WCHOME", "party menu: ${d.why}")
                PlayerMenu.Do.Close, PlayerMenu.Do.None -> {}
            }
            // the PartyPad's menu sub-state ends with the menu (only when its Y opened it)
            if (byPad && !menu.isOpen) PartyTaps.menuDone(live.cfg)?.let { TapBridge.taps(this, questsPad, it) { } }
        }
    }

    /** the target radial's / its list's pick: the portrait menu's routes (digit taps; a secure cell: Inspect, Set Focus; Whisper = Chat) */
    private fun targetDo(d: PlayerMenu.Do, name: String) {
        when (d) {
            is PlayerMenu.Do.Run -> {
                if (d.id >= 0) GuestPad.playerMenuRun(questsPad, d.id)?.let { TapBridge.taps(this, questsPad, it) { w -> Log.i("WCHOME", "target radial: run not sent: $w") } }
                if (d.app == "whisper" && name.isNotEmpty()) ui.post { show(Room.CHAT); chatView.whisperTo(name) }
            }
            is PlayerMenu.Do.Secure -> TapBridge.taps(this, questsPad, listOf(d.row to d.col)) { w -> Log.i("WCHOME", "target radial: cell not sent: $w") }
            is PlayerMenu.Do.Refused -> Log.i("WCHOME", "target radial: ${d.why}")
            PlayerMenu.Do.Close, PlayerMenu.Do.None -> {}
        }
    }

    /** Party mode's member menu as the context radial (the user: "no more drop down menus"): the PartyPad's presses drive it ([byPad]:
     *  its Y opened it; [padKeys]: m157, a hold with Party mode on put the pad in its menu sub-state; either way the wheel's close taps
     *  MENU DONE), else (a hold with Party mode off) the wheel's own controller reader (B closes it, as the portrait's wheel).
     *  [menuHeld]: the hold's MENU HOLD went out, so the close taps MENU DONE whatever drove it (a sub-state never stranded) */
    private fun partyWheel(i: Int, byPad: Boolean, padKeys: Boolean = false, menuHeld: Boolean = false) {
        val live = PartyModel.live
        val name = live.party?.m?.firstOrNull { it.i == i }?.n ?: ""
        val viaPad = byPad || padKeys
        targetRadial.open("party", if (viaPad) TargetRadialView.Input.KEYS else TargetRadialView.Input.READ, name, { d, who ->
            when (d) {
                is PlayerMenu.Do.Run -> {
                    if (d.id >= 0) GuestPad.playerMenuRun(questsPad, d.id)?.let { TapBridge.taps(this, questsPad, it) { } }
                    if (d.app == "whisper" && who.isNotEmpty()) ui.post { show(Room.CHAT); chatView.whisperTo(who) }
                }
                is PlayerMenu.Do.Secure -> TapBridge.taps(this, questsPad, listOf((if (d.row == "party") PartyModel.live.cfg?.pad?.row ?: "party" else d.row) to d.col)) { }
                is PlayerMenu.Do.Refused -> Log.i("WCHOME", "party wheel: ${d.why}")
                else -> {}
            }
        }, {
            if (viaPad || menuHeld) PartyTaps.menuDone(live.cfg)?.let { TapBridge.taps(this, questsPad, it) { } }
        })
    }
    private fun partyWheelOpen() = tradOpen() && targetRadial.opener == "party"

    /** 1.1's re-ask (party row col 22): only with a known column, live, at most once per 10 s */
    private fun partyAsk(why: PartyModel.Ask) {
        val cells = PartyTaps.resend(PartyModel.live.cfg) ?: return
        if (!PartyModel.live.shouldAsk(why, GameInput.live(), SystemClock.uptimeMillis())) return
        val sent = TapBridge.taps(this, questsPad, cells) { }
        Log.i("WCHOME", "party: resend ($why) ${if (sent) "tapped" else "not sent"}")
    }

    private fun partyRedraw() {
        homeView.partyChanged()
        castView.partyChanged()
        controllerView.partyChanged()
        settingsView.partyChanged()
    }

    private fun partyDoc(p: PartyModel.Party, current: Boolean) {
        // 2026-11-04 (the user: logged in on another character, "it showed my hunter as myself in the party"): a party document from the
        // log's history may be another character's; only a CURRENT one is shown (the add-on sends its own within seconds of a login)
        if (!current) return
        GameUi.partySeen(p)                                                 // Smart Res: this character's class (shown / hidden)
        val live = PartyModel.live
        val wasOn = live.party?.on == true
        if (live.onParty(p, current, SystemClock.uptimeMillis())) partyRedraw()
        if (current && p.on && !wasOn) partyAsk(PartyModel.Ask.PARTY_ON)
        // the menu's member left the group: it closes
        val menu = homeView.partyMenu
        if (menu.isOpen && p.m.none { it.i == menu.unit }) menu.close()
    }

    private fun partyBox(b: PartyModel.Box) {
        val live = PartyModel.live
        if (!live.onBox(b, true)) return
        val menu = homeView.partyMenu
        when (b.k) {
            // Y opened the player menu for b.u: the PartyPad's next presses are the menu's
            "menu" -> PartyModel.unitIndex(b.u)?.let { i -> partyWheel(i, byPad = true) }
            "key" -> if (partyWheelOpen()) b.key?.let { targetRadial.key(it) } else if (menu.isOpen && menu.byPad) b.key?.let { menu.key(it) }
            "off" -> { if (partyWheelOpen()) targetRadial.close("Party mode off"); if (menu.isOpen && menu.byPad) menu.close() }
            // m150 SMART RES: a tap on a dead member in a fight with no res a fight allows (only Rebirth / Revive Pet do)
            "nores" -> { Log.i("WCHOME", "party: Smart Res refused in a fight"); homeView.partyNote("Not in combat") }
            "res" -> Log.i("WCHOME", "party: Smart Res cast")
        }
        // the pad's menu sub-state with no menu of ours driving it (a lost k=menu, an app restart): MENU DONE frees the presses
        if (PartyModel.strandedMenu(b, partyWheelOpen() || (menu.isOpen && menu.byPad))) {
            val sent = PartyTaps.menuDone(live.cfg)?.let { TapBridge.taps(this, questsPad, it) { } } == true
            Log.i("WCHOME", "party: the pad's menu sub-state with no menu here: menu done ${if (sent) "tapped" else "not sent"}")
        }
        partyRedraw()
    }

    private fun partyAck(a: PartyModel.Ack) {
        Log.i("WCHOME", "party: ack ${a.op} ${if (a.ok) "ok" else "refused ${a.why ?: ""}"}")
        // only a settings answer reaches the pages (op resend / cell / a stale touch tap are logged, never said)
        if (!PartyModel.forSettings(a)) return
        controllerView.onPartyAck(a)
        settingsView.onPartyAck(a)
    }

    /**
     * Leaving Home's rest page (a room opens, or a Home module goes full screen: [why]): THE USER'S RULING, Party mode turns OFF
     * (PartyModel.OFF_ON_LEAVE). The party menu closes (MENU DONE when the pad's Y opened it), then party `off` (absolute, in fights
     * too) so the router releases the pad; Home shown again finds it off (the add-on's party / partybox say so; the resend re-asks).
     */
    /** the capture's holder (a current interact document): a taker while Party mode is on -> off (PartyModel.State.takerSeen) */
    private fun partyTaker(holder: String) {
        // m132: in a fight a holder only suspends Party mode (never the off tap: the user, a healer, "usable in combat")
        if (!PartyModel.live.takerSeen(holder, SystemClock.uptimeMillis(), homeView.fightShown())) return
        if (homeView.partyMenu.isOpen) homeView.partyMenu.close()
        if (partyWheelOpen()) targetRadial.close("Party mode left")
        val sent = PartyTaps.off(PartyModel.live.cfg)?.let { TapBridge.taps(this, questsPad, it) { w -> Log.i("WCHOME", "party: off not sent: $w") } } == true
        Log.i("WCHOME", "party: $holder took the controller: off ${if (sent) "tapped" else "not sent"}")
    }

    private fun partyLeaveHome(why: String) {
        if (homeView.partyMenu.isOpen) homeView.partyMenu.close()
        if (partyWheelOpen()) targetRadial.close("Party mode left")
        if (!PartyModel.offOnLeave(PartyModel.live.party, homeView.fightShown())) return     // m132: never in a fight
        val sent = PartyTaps.off(PartyModel.live.cfg)?.let { TapBridge.taps(this, questsPad, it) { w -> Log.i("WCHOME", "party: off not sent: $w") } } == true
        Log.i("WCHOME", "party: left Home ($why): off ${if (sent) "tapped" else "not sent"}")
    }

    /**
     * A TOUCH ON THE SWIRL = THE BAR CURSOR'S PICK OF IT (the user on RC4: "tapping on the swirl should do the same thing as picking it
     * with the controller"): the pause wheel on Home's plain disc; where the disc shows B (test51) it is B: Home. What the cursor can never reach keeps the disc's B: a game window
     * whose B goes Home closes with its own close (the 2026-10-01 ruling) and Home shows; on Home, a full-screen module or the buff list
     * steps back (the B the disc shows there, the user 15:08).
     */
    private fun swirlTouched() {
        // PARTY MODE (4.2): on Home the disc shows red with B while Party mode is on; a touch on it is B: Party mode OFF
        if (room == Room.HOME && homeView.partyOnRest()) {
            val sent = PartyTaps.off(PartyModel.live.cfg)?.let { TapBridge.taps(this, questsPad, it) { } } == true
            Log.i("WCHOME", "home disc: touched in Party mode: off ${if (sent) "tapped" else "not sent"}")
            return
        }
        if (strip.bGoesHome && ::interactView.isInitialized && interactView.closeByTouch()) {
            Log.i("WCHOME", "swirl touched while it shows B: the game window closes (its own close)")
            pickRoom(Room.HOME); return
        }
        // test51 (the user: "When I'm in a room and I touch the B spiral button to go home, the pause menu opens instead of going home"):
        // while the disc wears its B look (every room; on Home, a step back to take) a touch is B: Home, the room stepping back as B
        // does it. Only the plain blue disc on Home opens the pause wheel
        // b-fix (rooms the same on every route): the Character room's More menu / its lists / the save question step back one, as its B
        if (room == Room.CHARACTER && characterView.stepBackByTouch()) {
            Log.i("WCLOG", "home disc: touched with the B look in CHARACTER: one step back (a menu of the room's closed)")
            return
        }
        if (room != Room.HOME || strip.bGoesHome || homeView.bGoesHome()) {
            Log.i("WCLOG", "home disc: touched with the B look in ${room.name}: Home")
            pickRoom(Room.HOME); return
        }
        openPause("a touch on the swirl", byStart = false)
    }

    /**
     * A room picked by touch or the bar cursor. Since 2026-10-01 (the user: "it should act the EXACT same for all rooms") it is plain
     * show(): every room's hand-over runs there, for this route and every other (touchRoomsLeave / touchRoomsEnter). The rooms' cells:
     * Settings the guest "settings"; Bags `bmvq` 29 / 30; the Spellbook `spells` 0 / 1 and its Talents `talents` open / close (one guest,
     * two pages); Character the two fixed cells of its `aurapick` row (its `grid` part).
     */
    private fun pickRoom(r: Room) = show(r)

    /** a pause-menu pick, once the wheel has folded: a ROOM opens as the bar opens it; View Buffs = Home's buff list */
    private fun pausePick(s: HomePauseModel.Slice) {
        when (s.kind) {
            HomePauseModel.Kind.ROOM -> s.room?.let { name -> runCatching { Room.valueOf(name) }.getOrNull() }?.let { r -> pickRoom(r) }
            HomePauseModel.Kind.HOME -> when (s.key) {
                "buffs" -> homeView.openAuras(debuff = false)
                // f-trackpad-keyboard: the pause wheel's D-pad Left; on Home (from a room: Home first)
                // the pause wheel's D-pad Left / Right (the user, 2026-10-16): the Track Pad / the Keyboard, the same tool on its tab
                "trackpad", "typetool" -> { if (room != Room.HOME) show(Room.HOME); homeView.post { homeView.openTypeTool(0) } }
                "keyboard" -> { if (room != Room.HOME) show(Room.HOME); homeView.post { homeView.openTypeTool(1) } }
                // pk1: Polestar's Skip step / Previous step, where the player is (no room opens); pk3: a refusal (now, or the add-on's
                // answer) says why on the wheel's own line, in whatever room the wheel was opened
                PolestarSkip.KEY_SKIP, PolestarSkip.KEY_PREV -> {
                    psWheelAt = SystemClock.uptimeMillis()
                    mapView.polestarPress(prev = s.key == PolestarSkip.KEY_PREV)?.let { pauseView.note(it) }
                }
            }
            else -> {}
        }
    }

    /** the Home disc shows B in every room (HomeDisc: no blue flash on a room-to-room switch); on Home it follows Home's own state */
    private fun discShowsB(): Boolean = HomeDisc.showsB(room != Room.HOME, homeView.bGoesHome())
    /** the bar disc's red B look follows [discShowsB]; each CHANGE is logged with its cause (test26: tracking reads the event order) */
    private fun discB(why: String) {
        val v = discShowsB()
        if (v != strip.bGoesHome) Log.i("WCLOG", "home disc: B look ${if (v) "ON" else "off"} ($why)")
        strip.bGoesHome = v
    }

    /**
     * A GUEST NEVER KEEPS THE CONTROLLER FOR A ROOM THAT IS NOT ON SCREEN (2026-09-30 20:11: a release tap that never reached the game
     * left the hidden Map room holding it, and the user's B did nothing). A press or a "hold ok" for such a guest sends its free again:
     * a release with no game action (tracking's yes), at most once every 2 s per guest, logged.
     */
    private val refreeAt = HashMap<String, Long>()
    /**
     * THE CHAT ROOM'S HOLD, CONFIRMED (b50, 2026-10-06 09:26:46: opened from the D-pad, its hold never reached the game - Spells' read of
     * the log shows no "chat" hold at all - so nobody held the controller and the router kept the D-pad: up / down did Home's commands).
     * The hold goes out, and the capture's "chat" guestack confirms it; no answer = sent again every [HoldAsk.EVERY_MS], [HoldAsk.MAX]
     * asks at most (b112). A hold the capture already has is taken "again" (harmless). Leaving the room ends the checks ([chatHoldTry]).
     */
    private fun chatHold(attempt: Int) {
        if (room != Room.CHAT) return
        // b112 (Chat's pick (a)): Map's HoldAsk, asked again every 0.7 s while unanswered, 4 asks at most (was 2 s, twice);
        // any answer (the hold's ack either way, the first press) stops it, and leaving the room ends it
        if (attempt == 0) chatAsk.shown()
        if (!chatAsk.due()) return
        val sent = guestTap(GuestPad.cells(questsPad, "chat", hold = true))
        chatBSaid = null                                             // a new hold: B's place said again once
        Log.i("WCHOME", "chat room: hold${if (attempt > 0) " again ($attempt)" else ""} ${if (sent) "sent" else "NOT sent (no pad, or the game is not live)"}")
        val mine = ++chatHoldTry
        if (chatAsk.asked(sent)) ui.postDelayed({ if (mine == chatHoldTry && room == Room.CHAT) chatHold(attempt + 1) }, HoldAsk.EVERY_MS)
    }
    private val chatAsk = HoldAsk()
    private var chatHoldTry = 0
    /** the login screen's sign-in prompts and their keyboard (off since 2026-11-05, the user's ruling) */
    private val SIGN_IN_PROMPTS = false
    /** pad15: the one reload for add-ons switched back on (this app run) */
    private var addonsReloaded = false
    /** the chat guest's B place last sent (null = not since its hold): repeats are not clicked again */
    private var chatBSaid: Boolean? = null

    /** the shared capture's holder as last known (the interact document's `holder`, or a guest's own hold / free answer) */
    /** b-class-free-gap: the class wheel's free is checked [CLASS_FREE_CHECK_MS] later and sent again (up to [CLASS_FREE_TRIES]) while the
     *  capture still names "pad:class" and no class wheel holds: a lost click left the right stick caught until the next open + close.
     *  A spare free is harmless (Quests' freeGuest answers ok when nothing is held) */
    private var classFreeTry = 0
    private fun classFreeCheck(mine: Int, n: Int) {
        if (mine != classFreeTry || castView.classHolding() || capHolder != "pad:class") return
        val sent = guestTap(GuestPad.cells(questsPad, "class", false))
        Log.i("WCLOG", "class radial: free once more ($n, the capture still names pad:class) ${if (sent) "sent" else "not sent"}")
        if (n < CLASS_FREE_TRIES) ui.postDelayed({ classFreeCheck(mine, n + 1) }, CLASS_FREE_CHECK_MS)
    }
    private val CLASS_FREE_CHECK_MS = 700L
    private val CLASS_FREE_TRIES = 3
    private var capHolder = ""
    /** b97: when the interact doc's holder last changed (uptime ms): Professions' book tap waits for the hand-over to settle */
    private var capHolderAt = 0L
    private var directN = 0

    /**
     * DIRECTPAD FOR THE GENERIC GUESTS (the latency study, the user's "All of it"; Quests' spec): while a guest's room is shown and the
     * capture holds "pad:<id>", its presses come from the app's reader (~20 ms) in the capture's own names; the relayed copy is dropped.
     * Quests takes them through its own onDirect (with its repeat); the other rooms get the SAME document their onPad already reads
     * (a unique negative n), so nothing changes for them but the speed.
     */
    private fun directPads() {
        fun synth(id: String, handle: (org.json.JSONObject) -> Unit): (String, Boolean, Boolean) -> Unit = { k, down, _ ->
            if (down) handle(org.json.JSONObject().put("type", "guestpad").put("id", id).put("k", k).put("n", --directN))
        }
        // combat_nav Step 3: in a fight's room mode the shown surface is live too (the in-place swap / the stray ENTER's "app" holder)
        fun live(id: String): () -> Boolean = { PadOwnerSync.directLive(guestShown(id), capHolder == "pad:$id", padOwner.roomMode()) }
        val lr = setOf(DirectPadKeys.LB, DirectPadKeys.RB)
        DirectPad.register(DirectPad.Reg("quests", DirectPadKeys.EIGHT, repeat = setOf("PADDUP", "PADDDOWN"), active = live("quests")) { k, down, rp ->
            if (down) questsView.onDirect(k, rp) })
        DirectPad.register(DirectPad.Reg("settings", DirectPadKeys.EIGHT + lr, active = live("settings"), onKey = synth("settings") { settings.pad.onPad(it) }))
        DirectPad.register(DirectPad.Reg("chat", DirectPadKeys.EIGHT + lr + setOf(DirectPadKeys.LT, DirectPadKeys.RT, "PADBACK"), active = live("chat"), onKey = synth("chat") { chatView.onPad(it) }))
        // the Map room: + L3 (the game's map pin) and Start (Show:), the user's context controls (Map.lua mr3 holds them too)
        DirectPad.register(DirectPad.Reg("map", DirectPadKeys.EIGHT + lr + setOf("PADLSTICK", "PADBACK"),
            active = { live("map")() || (guestShown("map") && capHolder == "map") }, onKey = synth("map") { worldMapRoom.onPad(it) }))
        // b98: the bar opened early on the app's own D-pad Down (the game's hub took its hold in that click) is live at once
        DirectPad.register(DirectPad.Reg("bar", DirectPadKeys.EIGHT + lr + setOf(DirectPadKeys.LT, DirectPadKeys.RT, "PADBACK"),
            active = { live("bar")() || (barSelect.open && barSelect.early) }, onKey = synth("bar") { barSelect.onPad(it) }))
        // b98: D-pad Down that no room takes, on Home with nobody holding: the bar comes up at once (the game's command confirms it)
        DirectPad.unclaimed = { k, down ->
            // Bags' press feedback (the user: "all other context buttons should have similar feedback"): A / B / X / Y push in the
            // drawn button at once; display only (Bags ignores other keys, and keys while it does not hold the controller)
            if (room == Room.BAGS) bagsView.onPadKey(k, down)
            if (::interactView.isInitialized) interactView.onPadKey(k, down)     // 1.0.8: its keyboard, only while it is up
            if (k == "PADFORWARD" && down) startPressed()
            if (k == "PAD2" && down) bPressed()
            // p35 (the user 2026-11-10: "When pressing down on the Dpad without the DS toolbar enabled the home button is lighting up for
            // a half second"): only while the bare Down IS the Home bar - the toolbar on, or the Home Bar command put on it
            if (down && k == "PADDDOWN" && room == Room.HOME && capHolder.isEmpty() && !DirectPad.sideHeld() && downIsHomeBar()
                && interactView.visibility != View.VISIBLE && dialogView.visibility != View.VISIBLE
                && spellsOverlay.visibility != View.VISIBLE && startView.visibility != View.VISIBLE
                && !homeView.partyHoldsDpad()) barSelect.openEarly(room)       // Party mode's D-pad: no early bar either (2026-11-04)
        }
        DirectPad.register(DirectPad.Reg("home", DirectPadKeys.EIGHT + lr + setOf("PADBACK"), active = live("home"),
            onKey = synth("home") { if (setupPad) setupView.padKey(it.optString("k")) else homeView.auraPad(it) }))
        // the Track Pad & Keyboard: LB / RB the tabs, ZL / ZR the clicks (the user, 2026-10-16)
        DirectPad.register(DirectPad.Reg("tool", DirectPadKeys.EIGHT + lr + setOf(DirectPadKeys.LT, DirectPadKeys.RT), active = live("tool"),
            onKey = synth("tool") { homeView.toolPad(it) }))
    }

    private fun guestShown(id: String): Boolean = when (id) {
        "settings" -> room == Room.SETTINGS
        "quests" -> room == Room.QUESTS
        "chat" -> room == Room.CHAT
        "map" -> room == Room.MAP
        // during the no-gap hand-over the bar still holds ON PURPOSE: its presses are dropped (HomeBarSelect.onPad, closed), never
        // re-freed here (a free then would open the very gap the hand-over avoids); the hand-over frees it itself when it must
        "bar" -> barSelect.open || barSelect.handingOver
        "home" -> room == Room.HOME && (homeView.homeOwnGuestWanted() || setupPad)
        "tool" -> room == Room.HOME && homeView.toolWanted()
        // the consumables radial holds only while its wheel shows (b57, 13:29: the close's free went out before the capture had taken
        // the hold, so "radial" kept the controller with no wheel; a late "hold ok", a press or the holder report now frees it again)
        "radial" -> (::radialView.isInitialized && radialView.isOpen) || (::pauseView.isInitialized && pauseView.isOpen) || tradOpen()
        else -> true
    }
    /** [why] names the trigger in the log line (a press, a hold ok, a holder in an interact document), so every re-free has a visible cause */
    private fun refree(id: String, why: String) {
        val now = SystemClock.uptimeMillis()
        if (now - (refreeAt[id] ?: 0L) < 2000L) return
        refreeAt[id] = now
        if (guestTap(GuestPad.cells(questsPad, id, hold = false))) Log.i(HomeView.TAG, "guest $id: free again (its room is not on screen; $why)")
    }

    /**
     * ONE RULE FOR EVERY ROOM (tracking's yes, 2026-09-30, after the Calendar room kept the controller from 22:49:14 while the user was
     * elsewhere): an ADDON guest (its own capture id, not Quests' "pad:" ones) that still holds for a room not on screen is freed by
     * that room's own no-game-action release, its view's freeAgain(). Holder id -> (its room is on screen, its freeAgain).
     * Owners join as they expose freeAgain(). Since Interact i78 (2026-10-01) the interact document's generic `leave` cell does it for
     * every room alike when the document carries one (freeHidden): the room's own game window closes with it, as any leave closes it.
     */
    private val addonGuests: Map<String, Pair<() -> Boolean, () -> Boolean>> by lazy {
        mapOf(
            "character" to Pair({ room == Room.CHARACTER }, { characterView.freeAgain() }),
            "calendar" to Pair({ room == Room.CALENDAR }, { calendarView.freeAgain() }),
            "professions" to Pair({ room == Room.PROFESSIONS }, { professionsView.freeAgain() }),
            "groupfinder" to Pair({ room == Room.GROUPFINDER }, { groupFinderView.freeAgain() }),
            "social" to Pair({ room == Room.SOCIAL }, { socialView.freeAgain() }),
            // the Map room's own hold (Map.lua mr1): the game's map closed with it when it shows
            "map" to Pair({ room == Room.MAP }, { worldMapRoom.freeAgain() }),
            "bags" to Pair({ room == Room.BAGS }, { bagsView.freeAgain() }),
            // one guest for the Spellbook, its Talents room and the Controller tab (spells: the trainer lend shows the Spellbook room)
            "spells" to Pair({ room == Room.SPELLBOOK || room == Room.TALENTS || room == Room.CONTROLLER },
                { if (room == Room.CONTROLLER) controllerView.freeAgain() else spellbookView.freeAgain() }),
            // Interact's own hold for an NPC window: "on screen" while its pop-up or a dialog shows; after a refusal (the game's window
            // still open, a finger having dismissed the pop-up) no new try for NPC_BACKOFF
            "npc" to Pair({ interactView.visibility == View.VISIBLE || dialogView.visibility == View.VISIBLE ||
                SystemClock.uptimeMillis() - npcRefusedAt < NPC_BACKOFF }, { interactView.freeAgain() }),
        )
    }
    /**
     * b90 (22:54:20 / 22:54:35, Home as integrator): the leave's `fb` sometimes never reaches the addon (no padstray, the doc still
     * held), and the self-heal above waits for the NEXT interact doc - 7 s and 26 s that day, with the red B on Home. So 1 s after the
     * room is left, a holder still "groupfinder" is let go at once (Interact's generic leave), once (refreeAt keeps it to one per 2 s)
     */
    private fun gfReleaseCheck() {
        (shell.hostWindow()?.decorView ?: View(this)).postDelayed({
            if (room != Room.GROUPFINDER && capHolder == "groupfinder") {
                Log.i(HomeView.TAG, "guest groupfinder: still holds 1 s after the room was left: let go")
                freeHidden("groupfinder", press = true)
            }
        }, GF_RELEASE_CHECK_MS)
    }

    /** when each holder was first seen with its room NOT on screen (cleared once it shows): the grace below */
    private val hiddenSince = HashMap<String, Long>()
    /** when Interact last refused "release npc" (a game window open, or a room holding): see "npc" above */
    private var npcRefusedAt = -NPC_BACKOFF
    /**
     * [press] = a press that reached a hidden room (proof it still holds): at once. From the holder field alone, only after the holder
     * has been seen with its room hidden for HIDDEN_GRACE: a room the GAME opens (Bags with the bag button) takes the controller a
     * moment BEFORE the app switches to it, and must not be freed in that moment.
     */
    private fun freeHidden(holder: String, press: Boolean = false) {
        val (shown, free) = addonGuests[holder] ?: return
        val now = SystemClock.uptimeMillis()
        if (shown()) { hiddenSince.remove(holder); return }
        val since = hiddenSince.getOrPut(holder) { now }
        if (!press && now - since < HIDDEN_GRACE) return
        if (now - (refreeAt[holder] ?: 0L) < 2000L) return
        refreeAt[holder] = now
        // Interact i78's generic leave for every ROOM (not its own "npc" hold): the holder's own close + its release, one secure click,
        // refused by the addon for 2 s after a take (a room that just took is never closed by a late tap)
        val leave = roomLeaveCell?.takeIf { holder != "npc" }
        if (leave != null) {
            val sent = TapBridge.tap(this, questsPad, leave.first, leave.second) { }
            Log.i(HomeView.TAG, "guest $holder: left (generic leave ${leave.first} ${leave.second}; holder, room not shown)${if (sent) "" else ": not sent"}")
        } else Log.i(HomeView.TAG, "guest $holder: free again (holder, room not shown)${if (free()) "" else ": not sent"}")
    }
    /** Interact i78's generic room leave (interact doc `leave` {row, col}, while a room holds; in an armed fight the fight leave's cell) */
    private var roomLeaveCell: Pair<String, Int>? = null

    /**
     * The document being handled is CURRENT (not replayed from the log's backlog at start). Every AUTOMATIC room jump checks it:
     * an old document must never open a room, because a room's entry taps the game (Professions did at 14:55:53, 2026-09-30).
     */
    private var docCurrent = false
    /** the first CURRENT document since start was logged (Home's restart check proves documents arrive; batch 24, 2026-10-01) */
    private var docFlowSeen = false
    /** #147 (1.0.24, "cant get the bottom screen to work"): no add-on document at all while the bottom screen was up, said once */
    private var hostUpAt = 0L
    private val silenceCheck = Runnable {
        if (shell.hostGone()) return@Runnable
        val gameMs = if (LauncherHooks.gameStartSec > 0) System.currentTimeMillis() - LauncherHooks.gameStartSec * 1000 else 0L
        // issue #231 ("ui iz on top screen": no document in 2 runs, the game at in-world memory): the reader and the shadow's Logs
        val detail = if (docFlowSeen) null else readerDetail()
        AddonSilence.line(docFlowSeen, LauncherHooks.gameUp(), SystemClock.elapsedRealtime() - hostUpAt, gameMs, detail)?.let {
            Log.w("WCSETUP", it); DiagLog.event("addons", it)
        }
    }
    /** issue #231: the log reader's and the shadow's Logs state in one line (main thread) */
    private fun readerDetail(): String? = runCatching { listOfNotNull(tail?.diag(), NativeLogin.shadowLogsLine()).joinToString("; ") }.getOrNull()
    /**
     * The game's bag button handed the controller to Bags: show that room. Bags letting go while its room shows ends it as every room
     * ends: HOME (USER RULING 2026-09-28 23:12, B goes Home everywhere; 2026-10-01 "the EXACT same for all rooms" - no more going back
     * to the room before Bags). Not for "room" / "free" (the app left it already) or "character" (the game's Character window took the
     * controller, user ruling 2026-09-30: its room's own route shows it). The B route's own show(HOME) finds Home shown: no second one.
     */
    private fun onBagsController(on: Boolean, why: String? = null) {
        if (on) {
            if (room != Room.BAGS && docCurrent) show(Room.BAGS)
        } else if (room == Room.BAGS && docCurrent && why != "room" && why != "free" && why != "character") show(Room.HOME)
    }

    /** when the Settings' reload key went out (0 = no reload pending): the start screen waits for the game's new hello */
    private var reloadAt = 0L

    private fun reloadStarted() {
        reloadAt = System.currentTimeMillis()
        if (::loginWatch.isInitialized) loginShow()      // the sign-in row goes during a reload (before the line is written)
        startView.line = "Reloading the game's interface"
        startView.visibility = View.VISIBLE
        // a safety: no hello in 90 s and the screen goes anyway (a touch still sends it away at once, as always)
        val at = reloadAt
        ui.postDelayed({ if (reloadAt == at) { reloadAt = 0L; startView.visibility = View.GONE; loginShow() } }, 90_000L)
    }

    private fun reloadDone() {
        reloadAt = 0L
        startView.visibility = View.GONE
        loginShow()
        show(Room.HOME)
    }

    // ---------------------------------------------------------------- the self-setup (Home, 2026-10-02; CompanionSetup, SetupRunner)

    /** the game the prompt on screen is about */
    private var setupGame: CompanionSetup.Game? = null

    /** both games' state through the script (read-only), then at most ONE prompt */
    private fun setupCheck() {
        if (setupDeclined || setupBusy || setupView.visibility == View.VISIBLE) return
        setupBusy = true
        // the private share sets up the WoW Forever app only (the user: "this release to be for the wow forever app specifically")
        val games = if (BuildConfig.SHARE) listOf(CompanionSetup.Game.WOWFOREVER) else CompanionSetup.Game.values().toList()
        setupRunner.wowForever = game
        val found = LinkedHashMap<CompanionSetup.Game, Pair<CompanionSetup.Check, CompanionSetup.Need>>()
        fun next(i: Int) {
            if (i >= games.size) { ui.post { setupBusy = false; setupAsk(found) }; return }
            setupRunner.run("check", games[i]) { lines ->
                CompanionSetup.parse(lines)?.let { found[games[i]] = it to CompanionSetup.need(it, setupRunner.appSet) }
                next(i + 1)
            }
        }
        next(0)
    }

    private fun setupAsk(found: Map<CompanionSetup.Game, Pair<CompanionSetup.Check, CompanionSetup.Need>>) {
        if (setupDeclined) return
        val g = CompanionSetup.first(found) ?: return
        // never over a fight: look again in 30 s
        if (homeView.inFight()) { ui.postDelayed({ setupCheck() }, 30_000L); return }
        val (chk, need) = found.getValue(g)
        setupGame = g
        Log.i(SetupRunner.TAG, "ask: ${g.id} $need (missing ${chk.missing}, stamp ${chk.stamp}, diff ${chk.diff}, " +
            "config ${chk.configMissing}, touch ${chk.touchMissing}, defaults ${chk.defaultsMissing}, running ${chk.running}, app set ${setupRunner.appSet})")
        val p = CompanionSetup.prompt(g, need, chk)
        setupView.prompt(p)
    }

    /** Set up: close the game (only if it runs; never in a fight) -> install (the script restores on any failure) -> start it again */
    private fun setupGo() {
        val g = setupGame ?: run { setupView.close(); return }
        if (homeView.inFight()) { setupView.refuse(CompanionSetup.IN_FIGHT); return }
        setupBusy = true
        setupView.work(CompanionSetup.CLOSING)
        setupRunner.run("close", g) { cl ->
            val wasRunning = cl.any { it.trim() == "CLOSED" }
            cl.firstOrNull { it.startsWith("FAIL") }?.let { f ->
                ui.post { setupBusy = false; setupView.failed(CompanionSetup.failLine(f, false)) }
                return@run
            }
            ui.post { setupView.work(CompanionSetup.INSTALLING) }
            setupRunner.run("install", g) { il ->
                val fail = il.firstOrNull { it.startsWith("FAIL") }
                if (fail != null || il.none { it.startsWith("INSTALLED") }) {
                    val restored = il.any { it.startsWith("RESTORED") }
                    ui.post { setupBusy = false; setupView.failed(CompanionSetup.failLine(fail ?: "FAIL install no answer", restored)) }
                    return@run
                }
                if (!wasRunning) {
                    ui.post { setupView.work(CompanionSetup.DONE_IDLE) }
                    ui.postDelayed({ setupBusy = false; setupView.close() }, 3_000L)
                    return@run
                }
                ui.post { setupView.work(CompanionSetup.STARTING) }
                setupRunner.run("start", g) { _ ->
                    // the start screen waits for the game's first current word, as at every start
                    ui.postDelayed({
                        setupBusy = false; setupView.close()
                        startView.line = if (SetupBridge.seeding) SetupFlow.timesLine(SetupFlow.Step.PREPARE) else PreLoginTraffic.waitingLine(PreLoginTraffic.WAITING_LINE, NativeLogin.downloading); startView.visibility = View.VISIBLE
                    }, 4_000L)
                }
            }
        }
    }

    /** LIVE AGGRO: one aggro record into the live store (the learned radii read from the app's files first), the map drawn */
    private var aggroOff: String? = null
    private fun onAggro(r: AggroDoc.Record) {
        AggroFit.init(java.io.File(filesDir, "aggro_pulls.json"))
        LiveMobs.shared.onRecord(r)
        val off = (r as? AggroDoc.Off)?.why
        if (off != aggroOff && (off != null || r is AggroDoc.Full)) { aggroOff = off; Log.i("WCMAP", if (off != null) "aggro: off ($off): cleared" else "aggro: on") }
        mapView.onAggro()
    }

    private fun handleMessage(msg: JSONObject) {
        // the problem report's log: the document summarized (types and flags, never its words; names only to hide them)
        runCatching { DiagLog.doc(msg) }
        // first, for every document: is the game live, and is a text box open over there
        val current = GameInput.note(msg)
        docCurrent = current
        if (current) lastAddonAt = SystemClock.elapsedRealtime()           // the clean exit's "past the login screen"
        // the re-login: the world is back, so a waiting login.txt goes (NativeLogin.rearm wrote it when the world closed)
        if (current && LauncherHooks.pkg != null) NativeLogin.worldSeen()
        // smoothness A: an answer document's arrival, for the latency chain (inside the launcher only)
        if (LatLog.on) msg.optString("type").let { t -> if (t.endsWith("ack") || t.endsWith("use")) LatLog.mark("doc", "$t gt=${msg.optLong("_gt", -1L)}") }
        if (current && !docFlowSeen) { docFlowSeen = true; Log.i("WCHOME", "docs: first current document (${msg.optString("type")})") }
        if (current) partyAsk(PartyModel.Ask.FIRST_DOC)
        if (current) GameLife.inWorld()                      // issue #12: the first current document = the game is in the world
        // the interiors bake (Track C) runs while the game is there and quiet; it waits while it fights or loads
        if (current) IndoorSchedulerStep.noteAlive(homeView.inFight() || setupLoading || startView.visibility == View.VISIBLE)
        // a reload from the Settings keeps it up until the new hello (the game writes on for a moment before it reloads)
        // (an art failure kept it up before Play only: with the game live it never holds the bottom screen; p-build-tables-blocks-keyboard)
        if (current && startView.visibility == View.VISIBLE && reloadAt == 0L && !setupLoading && restartNoticeAt == 0L &&
            (ArtHarvest.state !is ArtHarvest.State.Failed || LauncherHooks.gameUp())) startView.visibility = View.GONE
        // where the character stands chooses the scene behind every waiting screen (UI Art's Scene)
        msg.optJSONObject("map")?.let { if (it.has("inst")) Scene.note(it.optInt("inst", -1)) }
        // the game's "has been blocked from an action" pop-up names no function: the add-on's report does (ActionBar onBlocked)
        msg.optJSONObject("hudBlocked")?.let {
            Log.w("WCBLOCK", "${it.optString("who")} ${it.optString("ev")}: ${it.optString("fn")} (#${it.optInt("n")})")
            if (current) BlockTally.note(it)                 // the loot test's report line (forbidden / blocked counts)
        }
        // m85: Party mode's own errors (a member it could not read, or the whole document), once each
        // pad15: DS Forever add-ons the game had switched off (the "blocked" pop-up's Disable) were switched back on: one reload loads them
        if (msg.optString("probe") == "addonsoff" && current) {
            Log.w("WCSETUP", "add-ons the game had switched off, on again: ${msg.optJSONArray("list")}${if (addonsReloaded) " (reloaded once already)" else ": reloading"}")
            if (!addonsReloaded) { addonsReloaded = true; if (GameInput.reload()) reloadStarted() }
        }
        // chat33 (#183, "Not able to send messages"): each DS Forever add-on the game did not load (the game's reason), the shared
        // capture's version (0 = Interact never ran: no room can hold the controller) and the first Lua errors from our files; one a login
        if (msg.optString("probe") == "ours") oursProbeLine(msg)?.let { (warn, line) ->
            if (warn) Log.w("WCSETUP", line) else Log.i("WCSETUP", line)
            DiagLog.event("addons", line)                    // #84 / #147: also in the rolling file (logcat's lines go within minutes)
        }
        // hub21: the frames standing where the bottom-left bar is (one probe per login)
        if (msg.optString("probe") == "barprobe") Log.i("WCHUB", "bar probe (${msg.optInt("scanned")} frames): ${msg.optJSONArray("frames")}")
        // pad14: WoW's hardware cursor setting before / after DS Forever's one switch to the software cursor
        if (msg.optString("probe") == "cursorsoft") Log.i("WCCURSOR", "gxCursor before ${msg.optString("before")} set ${msg.optString("set")} after ${msg.optString("after")}")
        if (msg.optString("probe") == "partyerr") Log.w("WCPARTY", "party error (${msg.optString("u")}): ${msg.optString("err")}")
        // prd16 / uf16 (issue #181): what a fight's aura reads gave the top screen's HUD row / target bar, once per kind (WCLOG: the report
        // carries it)
        if (msg.optString("probe") == "aurasec") Log.i("WCLOG", AuraProbe.line(msg))
        // chat29: what the game says its chat style is, and what the switch did (the chat bar on the top screen, 2026-11-04)
        if (msg.optString("probe") == "chatstyle") Log.i("WCCHAT", "chat style: now ${msg.optString("now")} default ${msg.optString("default")} " +
            "set ${msg.optString("set")} after ${msg.optString("now2")} ${msg.optString("why")}")
        when (msg.optString("type")) {
            "full" -> {
                msg.optJSONObject("map")?.let {
                    // the addon's session list heals a lost "poi" line; then the stored list
                    // (every session, in MapView's shape) replaces it
                    PoiHarvest.ingestAll(it.optJSONArray("svc")); PoiHarvest.attach(it)
                    lastMap = it; lastMapAt = System.currentTimeMillis(); mapView.update(it, 0); worldMapRoom.update(it)
                    if (current) mapView.learnExit(it)          // the ways out of a map, learned from live play only (CityExits.Learner)
                    if (current) routePinTick()
                    if (current) GameUi.onTracking(it.optJSONObject("track"))     // LATER 25: the "While tracking" minimap
                }
                msg.optJSONObject("actionbar")?.let { castView.update(it) }
                homeView.onFull(msg, current)
                if (castAskOpen && homeView.inFight()) castBarAsk()          // a fight: the cast bar question goes at once (back after)
                if (current) PreviewStage.feedFull(msg)                     // f-settings-previews: the Settings panel's live miniatures
                PreviewStage.roomBar = { castView.roomBar }; PreviewStage.roomBarOn = { castView.roomBarOn }
                msg.optJSONObject("bags")?.let { bagsView.update(it, current); pinPicker.update(it); characterView.updateBags(it)
                    it.optJSONObject("equipped")?.let { eq -> homeView.durability(eq.optJSONObject("durw")) }; if (ProfessionsStore.onBags(it)) professionsView.refresh() }   // old = no room switch, no tap (bags)
                status.text = "gaps ${tail?.gapCount() ?: 0}   v${msg.optString("v", "?")}"
            }
            // (a) the Bags room's light document: a press that moved only the cursor (Bags.lua sendCtlSoon)
            "bagsctl" -> msg.optJSONObject("ctl")?.let { bagsView.updateCtl(it, current) }
            "pos" -> msg.optJSONObject("map")?.let {
                mapView.updatePos(it, msg.optLong("_gt", -1L)); worldMapRoom.updatePos(it)
                if (current) AggroDoc.fromPos(it)?.let { r -> onAggro(r) }      // LIVE AGGRO: the record riding the pos doc (map.ag)
                if (current) mapView.learnNow()                     // the last place on a map before a crossing is the tick's, not a stale doc's
                if (current) routePinTick()
                // the background minimap step builds the player's own zone first (Map's nearest-first hook)
                if (it.has("mapID") && it.has("wx")) MapHarvestSteps.noteNear(this, Triple(it.optInt("mapID"), it.optDouble("wx"), it.optDouble("wy")))
                // ... and the interiors bake its building first
                IndoorSchedulerStep.notePosition(it)
                lastMapAt = System.currentTimeMillis()
                lastMap?.let { cur ->
                    for (k in listOf("x", "y", "wx", "wy", "facing", "zone", "subzone", "mapID"))
                        if (it.has(k)) cur.put(k, it.get(k))
                }
            }
            // LIVE AGGRO (AGGRO_DOC_CONTRACT_v1, Interact's Trail.lua): the record alone while still, the coverage, the acks
            "aggro" -> if (current) AggroDoc.record(msg)?.let { onAggro(it) }
            "aggrocov" -> if (current) AggroDoc.coverage(msg)?.let { c -> LiveMobs.shared.onCoverage(c)
                Log.i("WCMAP", "aggro: coverage: plates ${c.maxd?.toInt() ?: "?"} yd, offscreen ${c.offscreen}, enemies ${c.enemies}, ${c.sp.size} spells, ${c.it.size} items") }
            "aggroack" -> if (current) AggroDoc.ack(msg)?.let { a -> Log.i("WCMAP", "aggro: ack ${a.op} k ${a.k} s ${a.s} ${if (a.ok) "ok" else "refused ${a.why ?: ""}"}") }
            // QuestieDB's pins and spawn spots for one map (Quests' q21 pair; Map draws them)
            "qdbpins" -> { mapView.onQdbPins(msg); worldMapRoom.onQdbPins(msg) }
            "qdbnodes" -> { mapView.onQdbNodes(msg); worldMapRoom.onQdbNodes(msg) }
            "qdbavail" -> mapView.onQdbAvail(msg)            // Map: QuestieDB's available quests, Home's "!" layer (Quests' add-on)
            "qdbareas" -> mapView.onQdbAreas(msg)            // Map: QuestieDB's objective spawns, Home's quest areas (Quests' add-on)
            "mmprobe" -> mapView.onMmProbe(msg)              // Map: the minimap probe (Map.lua mp1): live dots, seen NPCs, options
            "padkeytest" -> TapBridge.onKeyTest(msg, current)  // Quests: L1's handshake + sweep trace (TapPad pad10c)
            // the Map room's painted world map: what this character has explored (Map.lua mapexpl)
            "mapexpl" -> worldMapRoom.onMapExpl(msg)
            // the zone highlights' boxes for the Map room's continent view (Map.lua maphl)
            "maphl" -> worldMapRoom.onMapHl(msg)
            // every zone's quest markers for the Map room (Map.lua mq1; the user after b101: "I cant see the quests on the map")
            "mapquests" -> worldMapRoom.onMapQuests(msg)
            // Hub hub6's logout word: the world closed (character select, or the game quitting): the waiting screen at once
            // a flight master's map (Map.lua taxinet): which known nodes connect (b-route-unknown-taxi); replays too (they stay true)
            // the game's player menu, read for the map module's bottom-screen copy (Quests q41): only a current one is the game's now
            // ... and Party mode's menu for a party member (q49 src = "party"): its own view over Home's left column
            // Bags.lua: the game's "pick an item" (an armor kit / stone / enchant) started or ended: a kit used from the Character room
            "itarget" -> if (current) {
                // bags-kit3: the pick's type and what the game said of it (cx), for a later device check of kits / stones
                if (msg.optBoolean("on")) Log.i("WCKIT", "itarget: ctx=${msg.optString("ctx")} wornOk=${msg.optString("wornOk")} cx=${msg.optJSONObject("cx")}")
                characterView.onItarget(msg.optBoolean("on"), msg)
            }
            // PadMap m104: which LB button targets whom, from the game's own bindings (the LB picture)
            "lbmap" -> LbMap.update(msg)
            "pmenu" -> if (current) {
                when (msg.optString("src")) {
                    "party" -> if (partyWheelOpen()) targetRadial.fill(msg) else homeView.partyMenu.onDoc(msg)
                    // q51: the target radial (a bare Y out of a fight with a target): opens it, or refreshes it / its list
                    // q54: a re-read after a run (again = 1) only refreshes an open wheel; it never opens one (the user: Trade "doesn't work":
                    // the wheel came back by itself and the next B closed it)
                    "radial" -> if (msg.optInt("again") == 1) { if (tradOpen() && targetRadial.opener == "target") targetRadial.fill(msg) }
                        else if (!(::pauseView.isInitialized && pauseView.isOpen) && !(::radialView.isInitialized && radialView.isOpen) &&
                        !(tradOpen() && targetRadial.opener != "target")) targetRadial.onDoc(msg)
                    else -> if (tradOpen() && targetRadial.opener == "portrait") targetRadial.fill(msg) else mapView.onPlayerMenuDoc(msg)
                }
            }
            // PARTY MODE (PARTY_BUILD.md 1, 4.7): the roster and the settings may be SHOWN from a replay; a box press and an answer only
            // count when current; nothing is ever tapped because of a replayed document
            "party" -> PartyModel.parseParty(msg)?.let { partyDoc(it, current) }
            "partycfg" -> PartyModel.parseCfg(msg)?.let { if (PartyModel.live.onCfg(it, current)) partyRedraw() }
            "partybox" -> if (current) PartyModel.parseBox(msg)?.let { partyBox(it) }
            "partyack" -> if (current) PartyModel.parseAck(msg)?.let { partyAck(it) }
            // the route's pin's answer (Map.lua b-route-waypoint): a refused one is not tried again for a while
            // Polestar's answer and its route (Map.lua b-route-polestar)
            "pnav" -> if (current) {
                mapView.polestar.onAnswer(msg.optString("op"), msg.optBoolean("ok"), msg.optString("why", ""), msg.optBoolean("have"),
                    SystemClock.elapsedRealtime())
                Log.i("WCMAP", "polestar: ${msg.optString("op")} ${if (msg.optBoolean("ok")) "ok" else "refused"} ${msg.optString("why", "")}" +
                    " (Polestar ${if (msg.optBoolean("have")) "there" else "missing"})")
                mapView.invalidate()
            }
            "pnavroute" -> if (current) { mapView.polestar.onRoute(msg, SystemClock.elapsedRealtime()); mapView.invalidate() }
            "pqol" -> if (current) { PolestarQol.onDoc(msg); if (::settingsView.isInitialized) settingsView.postInvalidate() }     // pq1: Polestar's automation
            "pstep" -> if (current) { mapView.onPolestarStep(msg); if (room == Room.MAP) worldMapRoom.postInvalidate() }   // ps1: the light blue row
            "pskip" -> if (current) {                                   // pk1: a Skip / Previous answered
                val ok = msg.optBoolean("ok")
                Log.i("WCMAP", "polestar ${msg.optString("op")}: ${if (ok) "ok, step ${msg.optInt("i")}" else "refused ${msg.optString("why", "")}"}" +
                    (if (msg.has("u")) ", undo ${msg.optInt("u")}" else ""))
                // pk3: the undo count at once (Previous right after a Skip); a refusal's line where the press came from
                val line = PolestarSkip.refusedLine(msg.optString("why", "").takeUnless { ok })
                val where = PolestarSkip.noteWhere(ok, line, SystemClock.uptimeMillis(), psWheelAt, room == Room.MAP)
                psWheelAt = 0L                                          // (one answer per press)
                mapView.onPolestarSkipAnswer(msg, note = where == PolestarSkip.Where.HOME_MAP)
                when (where) {
                    PolestarSkip.Where.WHEEL -> if (::pauseView.isInitialized) line?.let { pauseView.note(it) }
                    PolestarSkip.Where.MAP_ROOM -> line?.let { worldMapRoom.sayLine(it) }
                    else -> {}
                }
                if (room == Room.MAP) worldMapRoom.postInvalidate()
            }
            "routewp" -> if (current) {
                routeWp.onAnswer(msg.optString("op"), msg.optBoolean("ok"), SystemClock.elapsedRealtime())
                Log.i("WCMAP", "route pin: ${msg.optString("op")} ${if (msg.optBoolean("ok")) "ok" else "refused"} ${msg.optString("why", "")}")
            }
            "taxinet" -> {
                val reach = msg.optJSONArray("reach")?.let { r -> List(r.length()) { r.optInt(it) }.filter { it != 0 } } ?: emptyList()
                val cur = msg.optInt("cur", 0)
                if (cur != 0) { TaxiNet.onTaxiNet(cur, reach); Log.i("WCMAP", "taxinet: at $cur, ${reach.size} reachable") }
            }
            "bye" -> if (current) { QuestWatchKeep.cleanEnd(); byeShow(); PartyModel.live.newSession() }     // a logout: the game writes its state (the watch list)
            "chat" -> msg.optJSONObject("chat")?.let { chatView.update(it); homeView.onChat(it); PreviewStage.feedChat(it) }
            // the game's world notice (Chat chat21: the shard transfer's countdown) over Home's chat: current only (a replayed one is old)
            "notice" -> if (current) homeView.onNotice(msg)
            "chatt", "chatq", "chatlink" -> {
                chatView.onDoc(msg)      // Chat's rework: typed / quick chat answers, link tooltips
                if (current && ::interactView.isInitialized) interactView.onChatAnswer(msg)     // 1.0.8: an NPC window's field (chat22)
            }
            "quests" -> msg.optJSONObject("q")?.let {
                it.optJSONObject("pad")?.let { p -> questsPad = p }
                questsView.update(it); homeView.onQuests(it, current); mapView.onQuests(it); worldMapRoom.onQuests(it)
                if (current) PreviewStage.feedQuests(it)
                // fix/focus-latency (test50): the focus a tap just changed rides THIS document; the game's pin follows it now, not at the next
                // pos / full document (standing still that was the 15 s heartbeat)
                if (current) routePinTick()
                if (current) {
                    val back = QuestWatchKeep.onQuests(QuestWatchKeep.parse(it), canAct = questsPad != null && GameInput.live() && !homeView.inFight())
                    if (back.isNotEmpty()) restoreWatches(back)
                }
            }
            // the generic controller guests (Quests.lua q6, LATER item 5): each room's presses (CURRENT only, the room shown) and answers
            "guestpad" -> if (current) {
                val id = msg.optString("id")
                // DIRECTPAD: while the room gets its presses straight from the app's reader, the game's copy of the same press is dropped
                // (it would act twice); "lost" is a control signal, never dropped
                if (!msg.has("lost") && DirectPad.active(id)) return
                // a press for a guest whose room is not on screen: it should not hold the controller at all (b7b)
                if (!msg.has("lost") && !guestShown(id)) refree(id, "press ${msg.optString("k")} n ${msg.optInt("n", -1)}")
                else when (id) {
                    "settings" -> settings.pad.onPad(msg)
                    "quests" -> questsView.onPad(msg)
                    "chat" -> { chatAsk.answered(); chatView.onPad(msg) }
                    "map" -> worldMapRoom.onPad(msg)
                    "bar" -> barSelect.onPad(msg)
                    "home" -> if (setupPad) { if (!msg.has("lost")) setupView.padKey(msg.optString("k")) } else homeView.auraPad(msg)
                    "tool" -> homeView.toolPad(msg)
                }
            }
            // THE MAP ROOM's own hold (Map.lua mr1, the user's v2): its state, its presses, its answers. The GAME opening its map
            // (M, the radial) brings the room up, as Social's openN does; only on a CURRENT document (a replay never jumps rooms)
            // q25 (Quests): the GAME opened its quest log (the quest-log key, the radial): the Quests room comes up (the user's answer
            // 2026-10-07, "The Quests room"); Quests.lua took the controller already. M stays the Map room's (mapwin below)
            "questwin" -> {
                questsView.onQuestWin(msg)
                val n = msg.optInt("n", 0)
                if (QuestsWin.gameOpened(questWinN, n, msg.optBoolean("own"), current) && room != Room.QUESTS) show(Room.QUESTS)
                if (n > questWinN) questWinN = n
            }
            "mapwin" -> {
                questsView.onMapWin(MapRoomPad.Win.from(msg))
                val opened = worldMapRoom.onMapWin(msg)
                if (current && opened && room != Room.MAP) show(Room.MAP)
            }
            "mappad" -> if (current) {
                // DirectPad already gave the room this press (the app's reader): the game's copy is dropped; lost / BACK never are
                if (!msg.has("lost") && msg.optString("k") != "BACK" && DirectPad.active("map")) return
                if (!msg.has("lost") && msg.optString("k") != "BACK" && room != Room.MAP) worldMapRoom.freeAgain()
                else worldMapRoom.onMapPad(msg)
            }
            "mapack" -> if (current) worldMapRoom.onMapAck(msg)
            // an answer replayed from the log at start belongs to the OLD app instance's taps: only CURRENT answers act (batch 8)
            // the consumables radial (Spells' Radial.lua): its items (the latest, replays too) and the game's word that a use landed
            "radial", "sradial", "smartor" -> radialView.onDoc(msg)
            "radialuse", "sradialuse" -> if (current) radialView.onUse(msg)
            // q1b: one cast's cooldown for the spell radial (no sweep in the add-on: the app counts it down); a replayed one is stale
            "sradialcd" -> if (current) radialView.onCast(msg)
            // r20 (feat/item-cooldowns): the item cooldowns (Spells' Radial.lua `itemcd`): the item wheel, the bags, Home's clock extras.
            // Only a live one counts (ItemCd ignores a replay: an old life's countdowns)
            "itemcd" -> if (ItemCd.onDoc(msg, current) && current) {
                radialView.invalidate(); radialView.underRedraw?.invoke(); bagsView.cooldowns(); homeView.invalidate()
            }
            // m71: the spells out of mana now (Spells' PadMap): the app's spell pictures wear the game's blue
            "nomana" -> if (current && NoMana.onDoc(msg)) {
                spellbookView.invalidate(); radialView.invalidate(); castView.invalidate(); homeView.wheelRedraw(); settingsView.invalidate()
            }
            // Spells' stick catch (Radial r15): shown / hidden, for whom, and what it caught (the user on test20: "The control sticks are not
            // locking on the dpad up radial"): ok = the catch can take sticks at all; n = stick events kept from the game while it showed,
            // pass = the left stick's events let through (the class wheel)
            "radialstick" -> if (current) Log.i("WCLOG", "radial: stick catch ${if (msg.optBoolean("on")) "ON" else "off"} " +
                "(holder=${msg.optString("holder", "-")} mode=${msg.optString("mode", "both")} ok=${msg.optBoolean("ok", false)} " +
                "fight=${msg.optBoolean("fight", false)} arm=${msg.optBoolean("arm", false)}" +
                (if (msg.has("n")) " caught=${msg.optInt("n")}" else "") + (if (msg.has("pass")) " passed=${msg.optInt("pass")}" else "") + ", ${msg.optString("v")})")
            "pauseack" -> if (current && ::pauseView.isInitialized) pauseView.onAck(msg)
            // the Game Menu on the bottom screen (Spells r12): the game's list + its countdown, and the cells' answers
            "gamemenu" -> {
                if (current) lastGameMenu = msg
                // during a clean exit's wait, the game's own countdown goes into the app's log too (General.log starts afresh at the
                // next launch, so after an install only logcat still has it): each change of it, and its end
                if (current && cleanExitWaiting) {
                    if (msg.optString("why") == "refused") gmRefused = msg.optString("err", "").ifEmpty { "no reason given" }
                    val c = if (msg.has("count")) "count ${msg.optString("count")} left ${msg.optInt("left", -1)}"
                            else if (msg.has("ended")) "countdown ended (${msg.optString("ended")})"
                            else if (msg.optString("why") == "refused") "refused: ${msg.optString("err")}"
                            else if (msg.optString("why") == "event") "event ${msg.optString("ev")}"
                            else "a game menu document (${msg.optString("why")})"
                    if (c.isNotEmpty() && c != lastCountLogged) { lastCountLogged = c; Log.i("WCSETUP", "clean exit: the game says: $c") }
                }
                if (current && ::pauseView.isInitialized) pauseView.onGameMenuDoc(msg)
            }
            "gmack" -> {
                if (current) { gmAckAt = SystemClock.elapsedRealtime(); gmAckCol = msg.optInt("col", -1) }    // the clean exit's wait reads it
                if (current && cleanExitWaiting) Log.i("WCSETUP", "clean exit: the game's menu row answered: col ${msg.optInt("col", -1)} " +
                    "${msg.optString("cmd")} ok ${msg.optBoolean("ok")}${msg.optString("why", "").let { if (it.isEmpty()) "" else " ($it)" }}")
                if (current && ::pauseView.isInitialized) pauseView.onGameMenuAck(msg)
            }
            "radialpinack" -> if (current) radialView.onPinAck(msg)
            "guestack" -> if (current) {
                val gid = msg.optString("id")
                // the capture's own word, sooner than its next holder report: a hold that HOLDS (not "pending") starts direct presses
                val op = msg.optString("op")
                if (op == "hold" && msg.optBoolean("ok") && msg.optString("why") != "pending") capHolder = "pad:$gid"
                // a REFUSED free / leave (Quests q37: "close first" while the game's map shows) keeps the guest holding: capHolder stays
                if ((op == "free" || op == "leave") && msg.optBoolean("ok", true) && capHolder == "pad:$gid") capHolder = ""
                // the facility took a guest "again" (it was still wanted) after its room left the screen: let it go (b7b)
                // b74: the game now takes a room's hold IN THE SAME CLICK as its command (Hub hub7), so this answer can come before the room
                // counts as shown (the map module: freed at 18:29:17 / 18:29:47, then re-held by the app). Look again after a short grace
                if (current && msg.optString("op") == "hold" && msg.optBoolean("ok") && !guestShown(gid))
                    strip.postDelayed({ if (!guestShown(gid)) refree(gid, "hold ok") }, 400L)
                if (op == "pmenurun") {
                    // every menu run's answer in the log (the user: Trade "doesn't work" - its answer was nowhere to be read)
                    Log.i("WCHOME", "menu run: ${if (msg.optBoolean("ok")) "done" else "refused (${msg.optString("why")})"}")
                    mapView.onPlayerMenuRan(msg)
                }
                when (gid) {
                    "settings" -> settings.pad.onGuestAck(msg)
                    "quests" -> questsView.onGuestAck(msg)
                    "map" -> worldMapRoom.onGuestAck(msg)
                    // the Chat room's hold confirmed: no more re-sends, and the Home button's B follows the chat's chain from now
                    "chat" -> if (msg.optString("op") == "hold" && room == Room.CHAT) {
                        chatAsk.answered()
                        if (msg.optBoolean("ok")) { chatView.post { chatView.onBackChanged(chatView.bLeadsHome()) } }
                        else Log.i("WCHOME", "chat room: hold refused (${msg.optString("why")})")
                    }
                    "bar" -> barSelect.onGuestAck(msg)
                    "radial" -> if (::targetRadial.isInitialized && targetRadial.owning) targetRadial.onGuestAck(msg)
                        else if (::pauseView.isInitialized && pauseView.isOpen) pauseView.onGuestAck(msg)
                        else if (::radialView.isInitialized) radialView.onGuestAck(msg)
                    "home" -> { setupHoldAck(msg); homeView.auraGuestAck(msg) }
                    "tool" -> homeView.toolGuestAck(msg)
                }
            }
            // both may be waiting: each takes only the answers to what it sent
            "questack" -> { questsView.ack(msg); homeView.ack(msg); if (current) { worldMapRoom.onQuestAck(msg); mapView.onQuestAck(msg) } }
            // character's aura documents: Home's buff / debuff buttons and their list (listed in AGENT_LOG first)
            "auras" -> homeView.onAuras(msg, current)
            "auraack" -> homeView.auraAck(msg)
            "interact" -> {
                interactView.update(msg, current)
                // PARTY MODE (the user's ruling): anything that takes the controller ends it - in fights too (the app's tap is the route)
                if (current) partyTaker(msg.optString("holder", ""))
                // Home > Settings' loot window row (r-loot-on-top): the add-on's word; a replayed document only draws
                if (current) HomeLootSwitch.note(msg)
                // Settings > Top screen's compass bar row: the add-on's word (Trail.lua's WC.compass, through Interact's proof.compc)
                if (current) CompassSwitch.note(msg)
                if (current) TipsSwitch.note(msg)
                if (current) RingsSwitch.note(msg)
                // a room keeping the controller through a fight (capture v8's `fight`): the room flashes red (Home's cue)
                if (current) roomCue.note(msg, room != Room.HOME)
                // ... and leaving it in the fight goes through the capture's secure leave cell (v9's `fightLeave`)
                if (current) fightLeave.note(msg)
                // ... and an NPC window that closed by itself in the fight (loot taken) lets the controller go at once (RC5)
                // (settled 300 ms: a gossip -> quest hand-over reads windowless for a moment; Interact). Its rel by "B" for "npc" is the
                // app's own leave (leftIds), so it never pulls a room Home
                if (current && fightLeave.orphanSeen()) ui.postDelayed({
                    fightLeave.orphanSettled()?.let { (row, col) ->
                        leftIds = setOf(FightLeave.NPC); leftAt = SystemClock.uptimeMillis()
                        val sent = TapBridge.tap(this, questsPad, row, col) { }
                        Log.i("WCHOME", "fight leave: the npc window is gone in the fight -> $row $col ${if (sent) "sent" else "not sent"}")
                        ui.postDelayed({
                            fightLeave.retryNow()?.let { (rr, cc) ->
                                leftAt = SystemClock.uptimeMillis()
                                val again = TapBridge.tap(this, questsPad, rr, cc) { }
                                Log.i("WCHOME", "fight leave: once more $rr $cc (npc still holds) ${if (again) "sent" else "not sent"}")
                            }
                        }, FightLeave.RETRY_MS)
                    }
                }, FightLeave.SETTLE_MS)
                // ... and in a fight the pad follows the bottom screen (PadOwnerSync: ENTER / LEAVE on Interact's fightMode), the game menu
                // open at the pull locking the bottom screen until it closes (the user's decision 1, 2026-11-11)
                if (current) {
                    val wasFight = padOwner.mode != null
                    padOwner.note(msg, current)
                    if (::gameMenuLock.isInitialized) gameMenuLock.locked = padOwner.gmLock
                    padOwnerStep()
                    if (!wasFight && padOwner.mode != null) { ui.removeCallbacks(padOwnerTick); ui.postDelayed(padOwnerTick, PAD_OWNER_TICK_MS) }
                }
                // B handed the controller back (interact's shared capture, rel.n / rel.by): Home, as the user ruled
                // ... and the disc that showed B gets its press (the user: "Pushing B should have the button push effect and do the
                // yellow flash"), on Home too (a window closed over Home)
                if (homeView.backToHome(msg, current)) {
                    // a "B" release naming the room THE APP just left (within 1 s) is that leave's echo, not the player's B: it must not
                    // pull the screen Home from the room the player picked (Social's touch check 2026-10-02 09:28:48)
                    val who = homeView.bReleasedBy()
                    if (HomeBack.isOwnLeave(who, leftIds, leftAt, SystemClock.uptimeMillis()))
                        Log.i("WCHOME", "release by B from $who: the app's own leave, not the player's B (stays on $room)")
                    else if (HomeBack.wheelB(who))
                        Log.i("WCHOME", "release by B from $who: a wheel's own B (its close is the animation; no bar press)")
                    else { strip.homePressed(); if (room != Room.HOME) show(Room.HOME) }
                }
                // A FIGHT LET GO OF THE ROOM ON SCREEN (rel by "combat": the user 2026-10-01, "I get stuck in there ... Controller inputs
                // still do my default keybinds but I can't see any of it"): Home, whose combat view takes over - going Home "engages the
                // combat transition" (the user's ruling). Only for the room the release names; a hidden holder let go changes nothing
                else homeView.combatReleased()?.let { who ->
                    if (room != Room.HOME && room.name in HomeBack.roomsOf(who)) {
                        Log.i("WCHOME", "fight let go of $who: the room on screen ($room) goes Home")
                        show(Room.HOME)
                    }
                }
                // the Home button shows B while B would return Home (user 09:23; interact's bnext, UI Art's Strip)
                homeView.noteB(msg, current)
                discB("doc holder=${msg.optString("holder", "-")} bnext=${msg.optString("bnext", "-")}")
                // LATER item 7: capture v5's Select listener stays wanted while the app runs (Home's bar selector)
                if (current) barSelect.onInteract(msg)
                // a generic guest still holding for a room that is not on screen (interact i41's holder; a lost release, an app killed
                // mid-hold): let it go at once instead of on the user's next press (batch 8). Only Quests' "pad:" guests
                // ONE grace for both kinds, keyed by the full holder string: a holder that let go (or changed) starts it afresh
                val holderNow = if (current) msg.optString("holder") else ""
                if (current) { if (holderNow != capHolder) capHolderAt = SystemClock.uptimeMillis(); capHolder = holderNow }
                if (current) hiddenSince.keys.retainAll { it == holderNow }
                // after HIDDEN_GRACE only (batch 19's log: a holder doc written just before a guest's own free landed was read after
                // its room closed, and freed it a second time)
                holderNow.takeIf { it.startsWith("pad:") }?.removePrefix("pad:")?.let { id ->
                    if (id in GuestPad.IDS && !guestShown(id)) {
                        val now = SystemClock.uptimeMillis()
                        if (now - hiddenSince.getOrPut(holderNow) { now } >= HIDDEN_GRACE) refree(id, "holder in an interact doc, room $room")
                    } else hiddenSince.remove(holderNow)
                    Unit
                }
                // and every ADDON guest the same way (the one rule for every room), through Interact's generic leave when the doc has one
                if (current) roomLeaveCell = msg.optJSONObject("leave")?.let { l ->
                    val row = l.optString("row", ""); val col = l.optInt("col", -1)
                    if (row.isNotEmpty() && col >= 0) row to col else null
                }
                holderNow.takeIf { it.isNotEmpty() && !it.startsWith("pad:") }?.let { freeHidden(it) }
            }
            // Select with nobody holding the controller (interact's capture v5): the bar selector (LATER item 7), never over a window
            "cappad" -> if (current && msg.optString("room") == "bar") barSelect.onSelect(msg, blocked = interactView.visibility == View.VISIBLE ||
                    dialogView.visibility == View.VISIBLE || spellsOverlay.visibility == View.VISIBLE || startView.visibility == View.VISIBLE)
            // the bar commands (Hub.lua hub4; Spells' macros "DS Bar Left / Right / Open"): the bar cursor without holding the controller
            "barcmd" -> if (current) barSelect.onCommand(msg, room, blocked = interactView.visibility == View.VISIBLE ||
                    dialogView.visibility == View.VISIBLE || spellsOverlay.visibility == View.VISIBLE || startView.visibility == View.VISIBLE ||
                    (room == Room.HOME && homeView.partyHoldsDpad()))
            "interactack" -> {
                if (msg.optString("cmd") == "release npc" && !msg.optBoolean("ok", true)) {
                    val now = SystemClock.uptimeMillis()
                    if (now - npcRefusedAt >= NPC_BACKOFF) Log.i(HomeView.TAG, "guest npc: free refused (${msg.optString("why")}), next try in 30 s")
                    npcRefusedAt = now
                }
                interactView.ack(msg)
            }
            "dialogs" -> dialogView.update(msg, current)
            "dialogack" -> dialogView.ack(msg, current)
            // b-release-spirit: the dialogs' tap row could not be had (the tap pad has no row left): every dialog button would tap nothing
            "dialogpad" -> if (current) android.util.Log.w("WCDLG", "the dialogs have NO tap row: ${msg.optString("why")} (rows ${msg.optInt("rows", -1)})")
            "pvp" -> PvpModel.update(msg)   // the PvP rank (Character room tab 4) + the battleground queues (pvp session)
            "hub" -> GameUi.onHub(msg, current)
            // hub22: a new hostile target with Start attack on: the secure /startattack cell (a live document only, never a replay)
            "atkgo" -> if (current) GameUi.startAttack(msg.optString("row", "party"), msg.optInt("col", 36))
            "poi" -> msg.optJSONObject("poi")?.let { if (PoiHarvest.ingest(it)) refreshServices() }
            "qg" -> if (PoiHarvest.ingestQg(msg)) refreshServices()
            "character" -> {   // the bars + the Character room (character session)
                val byWindowBefore = characterView.heldByWindow
                CharacterBars.update(msg); characterView.update(msg, current)
                // the addon took the controller because the GAME's Character window opened: show the room, once, on the change,
                // and only from a CURRENT document (a replayed one must never open a room)
                if (current && !byWindowBefore && characterView.heldByWindow && room != Room.CHARACTER) show(Room.CHARACTER)
            }
            "hello" -> {
                status.text = "addon ${msg.optString("v", "?")} connected"
                // a login or a reload just happened: the game's UI hide waits out the loading screen (GameUiPolicy.SETTLE_MS)
                if (current) GameUi.onHello()
                if (current) QuestWatchKeep.newSession()        // a login or reload: its first quests document is checked
                if (current && reloadAt != 0L) reloadDone()
            }
            // the Social room (social session): its lists and the answers to its taps; the controller's presses while it shows.
            // When the GAME opens its own Friends / Guild / Who window, the room comes up at that page (the end-goal ruling)
            "social" -> {
                socialView.onDoc(msg)
                if (msg.optString("k") == "me") {
                    // b112: the first "me" doc since Social showed, still not held: ask now (its pad codes may only just have come)
                    if (current && room == Room.SOCIAL && capHolder != "social" && socialAsk.padArrived()) socialHold(1)
                    val m = socialView.model
                    val n = m.gameOpenedN
                    val opened = m.gameOpened
                    if (current && socialOpenN >= 0 && n > socialOpenN && opened != null) {
                        if (room != Room.SOCIAL) show(Room.SOCIAL)
                        socialView.openPage(opened)
                    }
                    socialOpenN = n
                }
            }
            "socialack" -> socialView.onDoc(msg)
            "socialpad" -> if (room == Room.SOCIAL) socialView.onPad(msg)
            // the Group Finder room (groupfinder session): its catalogue, its state and the answers to its taps; the controller's
            // presses while it shows. When the GAME opens its own Looking For Group window, the room comes up (the end-goal ruling), only on
            // a CURRENT document (Home's rule, AGENT_LOG 9596d1a: a replayed one is history)
            "gf" -> {
                groupFinderView.onDoc(msg)
                val win = groupFinderView.state?.windowOpen == true
                if (current && gfWin == false && win && room != Room.GROUPFINDER) show(Room.GROUPFINDER)
                gfWin = win
            }
            "gfcat", "gfack" -> groupFinderView.onDoc(msg)
            // b90: a press that reached the room while it is NOT on screen = proof it still holds (a lost release): let go at once
            "gfpad" -> if (current && room == Room.GROUPFINDER) groupFinderView.onPad(msg)
                else if (current && !msg.has("lost")) freeHidden("groupfinder", press = true)
            // the Spellbook (spells session): the book, who holds the controller, the answer to a Bind press. Leaving it with B goes
            // Home through the capture's release ("B") and the home session's route, as for Bags
            "spells" -> SpellsModel.parse(msg)?.let { lastBook = it; spellsPad = it.pad; spellbookView.update(it); controllerView.update(it); spellsOverlay.onBook(it)
                if (current) newSpellsFrom(it) }
            // CONTROL documents act only when CURRENT: one replayed from the backlog at app start tells who held the controller THEN, and
            // must not open the room or a page, bring up the Bind box or a Talents box, or leave a stale "held" behind (a later room change
            // would tap close). Home's finding, AGENT_LOG 9596d1a. The book, the tree and the chosen talent replay: the last known state shows.
            "spellsctl" -> if (current) SpellsModel.parseCtl(msg)?.let { c ->
                spellbookView.onCtl(c); controllerView.onCtl(c); spellsOverlay.onCtl(c)
                if (!c.hosted) newSpellBindCtl(c)   // f-new-spell-prompt: the Bind box closed, kept or undid (SpellsModel.bindStepCtl); b59: not a bag item's
                // the page the controller drives (Book.lua b3 `pg`) picks the room: the game's own Talents / Spellbook buttons bring theirs
                // up; between the two rooms the page follows the bar (no taps here: see pickRoom)
                // (the Talents page flag BEFORE the jump: show()'s hand-over reads it, so a page the game moved is not moved again)
                talentsView.held = c.on && c.talentsPage
                if (c.on) {
                    // the page picks the room: the Controller room's own page (b17 croom), Talents, or the book
                    val want = if (c.croom) Room.CONTROLLER else if (c.talentsPage) Room.TALENTS else Room.SPELLBOOK
                    // via "trainer": Context Menus' trainer lent the controller on Y (the user: "Y opens the Spellbook room; B comes back")
                    // 1.0.8-test7 (the user: "the controller room seems to open and swipe right instantly"): a page the BOOK moves inside its
                    // own rooms (Spellbook / Talents / Controller, the same add-on page following the game) is not a room change the player
                    // made, so it does not push: the old instant swap. Coming in from another room (the game's button) still pushes
                    val inBook = room == Room.SPELLBOOK || room == Room.TALENTS || room == Room.CONTROLLER
                    if (room != want && (c.via == "button" || c.via == "trainer" || inBook)) {
                        Log.i("WCHOME", "spellsctl: the book's page picks ${want.name} (from ${room.name}, via ${c.via}, croom=${c.croom})")
                        show(want, motion = !inBook)
                    }
                }
            }
            "spellsack" -> if (current) {
                // issues #18 / #20 / #31: every bind answer in the report's log (the add-on's trigger read beside the app's own)
                if (msg.optString("op") == "bind") {
                    Log.i("WCHOME", SpellsModel.bindLogLine(msg, spellsOverlay.triggerRead()))
                }
                // review of #31's fix: a refusal the box keeps waiting after (dpad bar, reserved, unsure, form-unknown) must not end the
                // new-spell flow, or the player is sent Home before pressing again; the box's close (bind-cancel / result-closed) and the
                // 120 s timeout still end it
                // the user 2026-11-12 ("Wait for keep/undo"): a bind that TOOK does not end it either: the box asks keep / undo first
                // Book b59: a bag item's box (hosted by Bags) is never the new-spell flow's
                SpellsModel.parseBind(msg)?.let { spellsOverlay.onBind(it); spellbookView.invalidate(); if (!it.item) newSpellBindStep(SpellsModel.bindStepAck(it)) }
            }
            // the Professions room (professions session): the professions, the open one's recipes, the answers to its taps
            "prof" -> msg.optJSONObject("p")?.let { p ->
                val was = ProfessionsStore.overview?.open
                ProfessionsStore.onProf(p, System.currentTimeMillis())
                val ov = ProfessionsStore.overview
                // the game opened a profession: its window appears on the bottom screen (the end-goal ruling); a line the game does
                // not list (a hunter's Beast Training, if it opens here) is the Character room's, and someone else's list is not shown.
                // ONLY a live change (ProfessionsModel.follows): a replayed backlog document once made the restarted app enter the
                // room and tap with no touch (Home, 2026-09-30)
                val follow = ProfessionsModel.follows(was, ov, current, profCurrent)
                profCurrent = current
                if (follow && ov?.open != null) {
                    if (room != Room.PROFESSIONS) show(Room.PROFESSIONS)
                    professionsView.showProfession(ov.open)
                }
                professionsView.refresh()
            }
            "profrec" -> msg.optJSONObject("p")?.let { if (ProfessionsStore.onBook(it, System.currentTimeMillis())) professionsView.refresh() }
            "profack" -> { ProfessionsStore.onAck(msg); professionsView.onAck(msg) }
            "profmade" -> { ProfessionsStore.onMade(msg); professionsView.refresh() }
            // never an old, replayed press; a live press while the room is not shown (the addon still holds the controller for it,
            // e.g. after the app was killed) hands the controller back: the player's own press, one no-action tap
            "profpad" -> if (current) { if (room == Room.PROFESSIONS) professionsView.onPad(msg) else professionsView.orphanPress() }
            // the Calendar room (calendar session): its document, the answers to its taps, the controller's presses while it
            // shows; the bar's mark while an invitation waits (SPEC 6). When the GAME opens its own calendar window, the room
            // comes up (the end-goal ruling); the game's window itself is never touched
            "calendar" -> {
                calendarView.onDoc(msg, current)
                val s = calendarView.state
                strip.mark(Room.CALENDAR.label, (s?.pending ?: 0) > 0)
                val win = s?.shown == true
                // only on a CURRENT document: a change inside the backlog LogTail replays at start is history, and the jump
                // would send the room's `hold` tap into a game that may not be live (Home's finding, AGENT_LOG 9596d1a)
                if (current && calWin == false && win && room != Room.CALENDAR) show(Room.CALENDAR)
                calWin = win
            }
            "calack" -> calendarView.onDoc(msg, current)
            // a press while the room is hidden means Calendar still holds: free it (the one rule for every room)
            "calpad" -> if (current) { if (room == Room.CALENDAR) calendarView.onPad(msg) else if (!msg.has("lost")) freeHidden("calendar", press = true) }
            // the Talents page (spells session): the tree, the chosen talent, the controller's place, the answers
            "talents" -> TalentsModel.parse(msg)?.let { talentsPad = it.pad ?: talentsPad; talentsView.update(it) }
            "talentsel" -> TalentsModel.parseSel(msg)?.let { talentsView.onDetail(it) }
            "talentsctl" -> if (current) TalentsModel.parseCtl(msg)?.let { talentsView.onCtl(it) }
            "talentsack" -> if (current) TalentsModel.parseAck(msg)?.let { talentsView.onAck(it) }
            // the Spellbook's Controller tab (spells session): what is on every button (replays: the last known buttons show), where the
            // controller is and the answers (control documents: current only)
            // every padmap also to Home: Cast's fight faces (armed buttons only) and B's give-back cell (HomeView.onPadMap)
            "padmap" -> PadMapModel.parse(msg)?.let {
                lastPadMap = it; if (::settingsView.isInitialized) { settingsView.requestLayout(); settingsView.invalidate() }
                if (::combatKeep.isInitialized) combatKeep.onMap(it, current)     // the In-combat backup (the user's "Both" ruling)
                spellbookView.onPadMap(it); controllerView.onPadMap(it); homeView.onPadMap(it); spellsOverlay.onPadMap(it)
                if (current) mapView.qitemArmed = it.qitem?.id ?: 0          // ps4: the light blue row's Y glyph (PadMap m132)
                if (current) { newSpellMap = it; it.dpad?.name?.takeIf { n -> n.isNotEmpty() }?.let { n -> newSpellWho = "$n-${it.dpad.realm ?: ""}" } }
                // the D-pad room bar (Spells' m24): the Settings row's state, and the once-per-character prompt
                it.dpad?.let { d ->
                    // the character now: the wheels' kept lists of another character go (the per-character ruling)
                    if (current && !d.name.isNullOrEmpty() && ::radialView.isInitialized) radialView.character("${d.name}-${d.realm ?: ""}")
                    // r20: the Hearthstone's kept end time is the character's (per name-realm)
                    if (current && !d.name.isNullOrEmpty()) ItemCd.character("${d.name}-${d.realm ?: ""}")
                    if (current && !d.name.isNullOrEmpty()) RadialMeta.who = "${d.name}-${d.realm ?: ""}"     // the spell radials' own settings
                    if (current && !d.name.isNullOrEmpty()) QuestWatchKeep.character("${d.name}-${d.realm ?: ""}")
                    if (!d.name.isNullOrEmpty()) TaxiNet.character("${d.name}-${d.realm ?: ""}")
                    if (current && !d.name.isNullOrEmpty()) GameUi.character = "${d.name}-${d.realm ?: ""}"     // hub13: HUD mode per character
                    lastDpad = d; settings.homeSection.dpadChanged()
                    // Quests q35: the rooms' guests keep Start from the game while the toolbar is on (our wheel), and let it through while
                    // it is off (the game's own wheel); told at the first current word and at every change of the toolbar's state
                    if (current && d.want != startToldWant) startTell(d.want == true)
                    // Cast's toolbar group: while the router holds the bare D-pad, AND while it only stepped aside for a room's or the
                    // bar's hold (b51, the user: "my dpad shows my normal spells" - the router says on=false whenever a guest holds, and
                    // the app kept the last one replayed at its start, with no padmap after it for 12 minutes). A GAME WINDOW (aside =
                    // its name) gives the D-pad back to the game, so its slots show then
                    castView.roomBarOn = d.on || (d.want == true && d.aside == "room")
                    castView.roomBar = d.dirs.ifEmpty { null }; castView.invalidate()
                    if (current) dpadAsk(d)
                }
            }
            // GitHub #43 THE TAP-TO-CAST BAR (ActionBar cb1): the slots (a state: a replayed one counts, the last one wins); its cooldowns
            // current only (as facecd); the edit's answer only logged (the next castbar document shows it)
            "castbar" -> CastStripModel.parse(msg)?.let { CastStripModel.Live.set(it); homeView.onCastBar(it) }
            "castcd" -> if (current) CastStripModel.parseCd(msg)?.let { homeView.onCastCd(it) }
            "castbarack" -> if (current) Log.i(HomeView.TAG, "cast bar: ${msg.optString("op")} ok=${msg.optBoolean("ok")} slot=${msg.optInt("s")} " +
                "queued=${msg.optInt("q") == 1} ${msg.optString("why")}")
            // the fight faces' cooldowns (Spells' m12): current only - a replayed one would start a sweep that already ran out
            "facecd" -> if (current) PadMapModel.parseCd(msg)?.let { homeView.onFaceCd(it) }
            // the fight flag also from Spells' facewatch (Tracking after test18): Home's D-pad picture follows the newest word
            "facewatch" -> if (current) homeView.onFightWord(msg.optBoolean("fight", false))
            // m127: Smart Y's list edits answered (a refusal said on its Add row; the user, 2026-11-11: it was never read)
            "smartyack" -> if (current && ::settingsView.isInitialized) settingsView.onSmartAck(msg.optString("op"), msg.optBoolean("ok", false), msg.optString("why").ifEmpty { null })
            "padack" -> if (current) PadMapModel.parseAck(msg)?.let {
                spellbookView.onPadAck(it); controllerView.onPadAck(it)
                if (it.op == "rankup") newSpellRankAck(it)                      // PadMap m143: the rank-up prompt's A answered
                // issue #136 (2026-11-11): the toolbar switch refused by the game (a fight, no character) is logged; DpadChoice re-sends it
                if (!it.ok && (it.op == "dpad-on" || it.op == "dpad-off")) Log.w(HomeView.TAG, "dpad: the game refused ${it.op} (${it.why ?: "no reason"}); kept, sent again")
                // the player changed an In-combat setting: the backup follows the store from now on (test22 P0)
                if (msg.optBoolean("ok") && msg.optString("op") in setOf("combat", "cface", "uncombat", "uncface", "clear", "rankup") &&
                    ::combatKeep.isInitialized) combatKeep.onPlayerChange()
                // the PLAYER's own change (not the app's default switches, not a restore's puts): the store is the truth from now on
                if (msg.optBoolean("ok") && msg.optString("op") in SV_PLAYER_OPS && !(::combatKeep.isInitialized && combatKeep.restoringNow()))
                    svArmed += SvKeep.FILES.keys
            }
            // Spells' m41 S1: the clean-up's plan for this character (the Controller room's DS tab; AGENT_LOG 2026-10-09); a state, so a
            // replayed one counts too (the last one wins)
            // the add-on's own saved tables (PadMap m51): kept in Documents/DS Forever for the write before the next game start
            "svkeep" -> if (current) SvKeep.parse(msg, System.currentTimeMillis())?.let { d ->
                if (LauncherHooks.gameStartSec != svRun) { svRun = LauncherHooks.gameStartSec; svArmed.clear(); svSeen.clear() }
                if (svSeen.add(d.variable) && d.n > 0) svArmed += d.variable
                val line = runCatching { SvKeep.store(svKeptDir(), d, d.variable in svArmed) }.getOrElse { "settings backup: NOT kept (${it.message})" }
                if (line.isNotEmpty()) Log.i("WCSETUP", line)
            }
            "padclean" -> {
                // m45: PadMap's automatic removal of our own old DS macros (no UI): one line per character
                if (msg.optString("why") == "auto") Log.i("WCSETUP", "padclean: removed ${msg.optInt("removed")} old DS macros (auto; " +
                    "${msg.optInt("char")} character, ${msg.optInt("shared")} shared)")
                PadMapModel.parseClean(msg)?.let { controllerView.onClean(it) }
            }
            "padtabctl" -> if (current) PadMapModel.parseCtl(msg)?.let { spellbookView.onPadTabCtl(it); controllerView.onPadTabCtl(it) }
        }
    }

    /** a harvested service landed (or the seed index finished): redraw the map's service layer */
    private fun refreshServices() {
        val m = lastMap ?: return
        PoiHarvest.attach(m)
        mapView.update(m, if (lastMapAt == 0L) 0 else System.currentTimeMillis() - lastMapAt)
    }

    private fun hasAllFiles(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /**
     * A share build's first start (RuntimeArt): a plain screen - no game art, which is what is being put in place - says what happens,
     * copies the pictures out with a percentage, then recreate()s into the normal start. Without All files access it asks for it
     * (Android's own page) and starts once it is given.
     */
    private fun unpackFirst() {
        val text = android.widget.TextView(this).apply {
            setTextColor(android.graphics.Color.rgb(235, 225, 200)); textSize = 22f
            gravity = android.view.Gravity.CENTER; setPadding(60, 60, 60, 60)
        }
        shell.setHostContent(FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.rgb(12, 14, 20)); addView(text) })
        if (!hasAllFiles()) {
            text.text = "DS Forever needs All files access to put its pictures in place.\n\nAllow it on the page that opens, then come back."
            try {
                shell.startHostActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$packageName")))
            } catch (_: Throwable) {}
            ui.postDelayed(object : Runnable {
                override fun run() { if (hasAllFiles()) shell.recreateHost() else ui.postDelayed(this, 1_000L) }
            }, 1_000L)
            return
        }
        text.text = "Unpacking the companion's pictures… 0%"
        Thread {
            val why = RuntimeArt.unpack(this) { pct -> ui.post { text.text = "Unpacking the companion's pictures… $pct%" } }
            ui.post {
                if (why == null) shell.recreateHost()
                else text.text = "The pictures could not be put in place:\n$why\n\nClose DS Forever and start it again to retry."
            }
        }.start()
    }

    /** M3 (Track C): the refused-login check, once a second while the launcher's game runs */
    private val signInCheck = SignInCheck()
    private var restartOnSave = false
    private var signInRun = 0L
    private val signInTick: Runnable = object : Runnable {
        override fun run() {
            if (shell.hostGone()) return
            signInStep()
            ui.postDelayed(this, 1000L)
        }
    }

    /** one step of the sign-in check: the bottom's pop-up follows it, the keyboard closes past the login screen, each change is logged */
    private fun signInStep() {
        val now = System.currentTimeMillis()
        // TEST6: while the Update login form is open the state HOLDS (no SIGNING -> FAILED -> NONE flapping under the form); the save
        // restarts the game, a close without saving lets it move again
        if (restartOnSave && !loginPrompt.isOpen) restartOnSave = false
        val st0 = if (restartOnSave) SetupBridge.signIn else if (LauncherHooks.gameUp()) {
            // a new client run starts clean
            if (LauncherHooks.gameStartSec != signInRun) {
                // a new client run (any restart route): nothing of a room's hold survives it, so the bottom screen is Home again
                if (signInRun != 0L && room != Room.HOME) { Log.i("WCHOME", "new game run: $room -> HOME"); show(Room.HOME, motion = false) }
                // the restart notice ends with the new run (the new game's first current word takes the start view away)
                if (restartNoticeAt != 0L) { restartNoticeAt = 0L; Log.i("WCSETUP", "restart from Settings: the new game run attached") }
                signInRun = LauncherHooks.gameStartSec; signInCheck.reset()
                if (SetupBridge.restarting) { SetupBridge.restarting = false; NativeLogin.logEvent("restart: game attached (a new client run)") }
            }
            // no saved login: the game's own login screen, once the client has had time to show it
            // test12: "no file" = NO SAVED LOGIN - not a file deleted unread at its cap (a slow first boot), which is nothing to call
            // refused; that one is written again when the game's window appears (NativeLogin.gameWindowUp)
            val noFile = !HomeLoginStore.has(this) && NativeLogin.firstReadAt == 0L &&
                now / 1000 - LauncherHooks.gameStartSec >= NO_FILE_LOGIN_S
            // the keepalives say it got through (no burst needed): past the login screen for everything that watches it
            // the authenticator code box (the log's authenticatorRequired="true"): no traffic verdict until the log moves on
            signInCheck.authenticator(loginWatch.state.phase == HomeLogin.Phase.AUTH)
            if (signInCheck.signedIn && !NativeLogin.pastLogin) NativeLogin.markPastLogin("two-way keepalives after the sign-in")
            signInCheck.tick(now, NativeLogin.firstReadAt, NativeLogin.pastLogin, noFile)
        } else SetupFlow.SignIn.NONE
        // p-auth-keyboard: a traffic-only FAILED in the first 25 s is "Check the game screen"; the log's AUTH / failure replaces it
        val logFailed = loginWatch.state.phase == HomeLogin.Phase.FAILED
        var st = SignInKeyboard.shown(st0, NativeLogin.firstReadAt, now, logFailed)
        // the simulation (adb only: a file in the app's own external folder): the authenticator's state with the game at its login
        // screen, through the same pop-up and the same keyboard path (no write into the game's log)
        if (LauncherHooks.gameUp() && simulateAuth()) st = SetupFlow.SignIn.AUTH
        // 2026-11-05 (the user: the checks "are actually making things harder if someone just isnt paying attention on an account
        // select. now that we added the menu and keyboard functions to the loading screen ... we can remove those"; picked "All the
        // sign-in prompts"): no verdict, no pop-up, no keyboard opened by itself; the waiting screen's Menu and Keyboard are the way.
        // The check still runs (its "signed in" feeds NativeLogin's pastLogin); a keyboard the player opened is never closed from here.
        if (!SIGN_IN_PROMPTS) {
            if (SetupBridge.signIn != SetupFlow.SignIn.NONE) SetupBridge.signIn(SetupFlow.SignIn.NONE, false)
            return
        }
        val restart = st != SetupFlow.SignIn.NONE && signInCheck.restartOffered
        if (st != SetupBridge.signIn || restart != SetupBridge.restartOffered) {
            NativeLogin.logEvent("sign-in: $st${if (restart) " (restart offered)" else ""}" +
                if (NativeLogin.firstReadAt > 0) ", ${(now - NativeLogin.firstReadAt) / 1000} s after the read" else "")
        }
        // the code box: the top screen's keyboard opens by itself, once as the game asks ("Keyboard" toggles it after that)
        // the keyboard opens by itself at the traffic's moment (CHECK, +13 s on the Thor) and again, harmlessly (show only), at the log's
        // AUTH (+38-45 s): the player can type the code as soon as the game shows its box (p-auth-keyboard, the user's "SUPER late")
        if (SignInKeyboard.autoOpen(SetupBridge.signIn, st)) {
            NativeLogin.logEvent("sign-in: ${if (st == SetupFlow.SignIn.AUTH) "authenticator code asked" else "code or password asked (traffic)"}, keyboard opened")
            SetupBridge.keyboard(true)
        }
        SetupBridge.loginFormOpen = loginPrompt.isOpen
        SetupBridge.signIn(st, restart)
        if (st == SetupFlow.SignIn.NONE && SetupBridge.keyboardOpen) { NativeLogin.logEvent("sign-in: keyboard closed (past the login screen)"); SetupBridge.keyboard(false) }
    }
    private val NO_FILE_LOGIN_S = 30L

    /**
     * RESTARTING, SAID (test45: a restart from Settings showed nothing for the ~20 s of the game's own Exit Game countdown, so it looked
     * like the button did nothing): the start view's line over the bottom screen until the new client run attaches (the start view goes
     * by itself with the game's first current word), at most RESTART_NOTICE_MS.
     */
    private fun restartNotice() {
        if (!::startView.isInitialized) return
        startView.line = "Restarting the game: it saves and closes first (about 20 s)..."
        startView.visibility = View.VISIBLE
        startView.invalidate()
        val at = SystemClock.elapsedRealtime()
        restartNoticeAt = at
        // held over the old game's documents (they keep coming through its exit countdown) until the new run attaches; at most this long
        ui.postDelayed({ if (restartNoticeAt == at) { restartNoticeAt = 0L; if (LauncherHooks.gameUp()) startView.visibility = View.GONE } }, RESTART_NOTICE_MS)
    }
    private var restartNoticeAt = 0L
    private val RESTART_NOTICE_MS = 120_000L

    /**
     * THE AUTHENTICATOR, SIMULATED (p-auth-keyboard, for a Thor without one): while
     * /sdcard/Android/data/<package>/files/simulate_auth exists (adb shell touch / rm; no exported entry point), the sign-in reads AUTH
     * with the game up. Checked at most once a second (one stat).
     */
    private var simChecked = 0L
    private var simOn = false
    private fun simulateAuth(): Boolean {
        val t = SystemClock.elapsedRealtime()
        if (t - simChecked >= 1000L) {
            simChecked = t
            val on = runCatching { java.io.File(getExternalFilesDir(null), SignInKeyboard.SIMULATE_AUTH_FILE).exists() }.getOrDefault(false)
            if (on != simOn) { simOn = on; NativeLogin.logEvent("sign-in: authenticator simulation ${if (on) "on" else "off"}") }
        }
        return simOn
    }
    /** "Restarting the game to sign in…" stays readable this long before the game closes */
    private val RESTART_DELAY_MS = 1_500L

    /** the game's General.log: inside the launcher from its chosen root; in the app the located game first; the old default last */
    private fun gameLog(): String = GamePaths.follow(LauncherHooks.pkg != null, GameFiles.gameRoot(this).path, game?.log)

    private val gameRootChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == GameFiles.KEY_GAME_PATH) ui.post { if (!shell.hostGone()) startTail() }
    }

    /** (re)starts following the game's log at [gameLog] when it is a new path (a new chosen root stops the old follow first) */
    private var tailLog: String? = null
    private fun startTail() {
        // Streaming (S2): the PC's log through the Desktop bridge (PlayModes.pick); Local: the game's own log, as before
        val streaming = PlayModes.current == PlayMode.STREAM && PlayModes.bridgeLog != null
        val log = if (streaming) "bridge:${PlayModes.bridgeKey}" else gameLog()
        if (log == tailLog && tail != null) return          // the launcher saves the same folder on every check: no restart then
        tailLog = log
        tail?.stop()
        // inside the launcher there is only its own game: no GameNative log ahead of it
        val follow = if (BuildConfig.SHARE || LauncherHooks.pkg != null) listOf(log) else listOf(LogTail.DEFAULT_PATH, log)
        Log.i("WCLOG", "game log: $log (located ${game?.root ?: "none"}, share ${BuildConfig.SHARE}, mode ${PlayModes.current})")
        val onMessage: (org.json.JSONObject) -> Unit = { msg -> ui.post { handleMessage(msg) } }
        val onStatus: (String) -> Unit = { s -> ui.post {
            status.text = s.take(110)
            // the other game started: is IT set up for the companion?
            if (s.startsWith("following the game's log")) ui.postDelayed({ setupCheck() }, 3_000L)
        } }
        tail = PlayModes.pick(PlayModes.current, PlayModes.bridgeLog, onMessage, onStatus) {
            LogTail(follow.first(), onMessage = onMessage, onStatus = onStatus, follow = follow)
        }.also { it.start() }
        // m129: the combat log is no longer read (no health on the bottom); the files our older add-ons made still go
        if (!streaming) java.io.File(log).parentFile?.let { dir ->
            // 1.0.19 (the user's pick "Delete ours older than a day"): the combat log files (Party mode's, PadMap m92 / m95-97) piled up in
            // the game's Logs folder (264 MB on the Thor in two days); only WoWCombatLog-*.txt older than 24 h, nothing else there
            Thread {
                val cut = System.currentTimeMillis() - 24 * 3600_000L
                var n = 0; var bytes = 0L
                dir.listFiles { f -> f.isFile && f.name.startsWith("WoWCombatLog") && f.name.endsWith(".txt") && f.lastModified() < cut }?.forEach { f ->
                    val len = f.length()
                    if (runCatching { f.delete() }.getOrDefault(false)) { n++; bytes += len }
                }
                if (n > 0) Log.i("WCLOG", "old combat logs deleted: $n files, ${bytes / (1024 * 1024)} MB")
            }.apply { isDaemon = true; name = "clog-clean" }.start()
        }
        PlayModes.onChanged = sourceChanged
    }

    /** Streaming (S2): the mode or the bridge's PC changed (paired, or another PC): follow the new source */
    private val sourceChanged: () -> Unit = { ui.post { if (!shell.hostGone()) { tailLog = null; startTail() } } }

    // ---------------------------------------------------------------- EXIT GUARD: no room ever without a working B (ExitGuard)
    private var guardUnheldSince = 0L
    private var guardReader: PadInput? = null
    private var guardHeld = 0
    private var guardAskAt = 0L
    private val guardTick = object : Runnable {
        override fun run() { try { guardStep() } catch (t: Throwable) { Log.w("WCLOG", "exit guard: ${t.message}") }; ui.postDelayed(this, 500L) }
    }
    private fun guardStep() {
        val wheel = (::radialView.isInitialized && radialView.isOpen) || (::pauseView.isInitialized && pauseView.isOpen) || tradOpen()
        val needs = ExitGuard.needs(room == Room.HOME, homeView.homeGuestWanted(), wheel, homeView.mapGuarding())
        val now = SystemClock.uptimeMillis()
        if (!needs || capHolder.isNotEmpty()) { guardUnheldSince = 0L; guardOff(); return }
        if (guardUnheldSince == 0L) guardUnheldSince = now
        if (!ExitGuard.on(needs, capHolder, now - guardUnheldSince)) return
        guardOn()
        if (room == Room.HOME && ExitGuard.retry(true, homeView.inFight(), now - guardAskAt)) { guardAskAt = now; homeView.guardReask() }
    }
    private fun guardOn() {
        if (guardReader != null) return
        Log.i("WCLOG", "exit guard: ${if (room == Room.HOME) "Home's module / list / tool" else room.name} has no controller hold: B goes back")
        guardHeld = 0; guardAskAt = SystemClock.uptimeMillis()
        guardReader = PadInput(onPad = { _, _, _, _ -> }, onStatus = { _, _ -> }, onButtons = { h ->
            ui.post {
                val was = guardHeld; guardHeld = h
                if (guardReader != null && MapExitGuard.bPressed(was, h)) {
                    if (room != Room.HOME) { Log.i("WCLOG", "exit guard: B read by Home: ${room.name} -> Home"); show(Room.HOME) }
                    else if (homeView.guardBack()) Log.i("WCLOG", "exit guard: B read by Home: Home's layer closed")
                }
            }
        }).also { it.start() }
    }
    private fun guardOff() {
        val r = guardReader ?: return
        guardReader = null; guardHeld = 0
        r.stop()
        Log.i("WCLOG", "exit guard: off (the controller is held, or nothing needs it)")
    }

    fun onDestroy() {
        Theme.removeListener(themeChanged)
        ui.removeCallbacks(guardTick); guardReader?.stop(); guardReader = null
        ui.removeCallbacks(silenceCheck)
        ArtHarvest.offState(iconArtState)
        IndoorSchedulerStep.stop()
        ui.removeCallbacks(signInTick)
        NativeLogin.onTrafficSample = null
        SetupBridge.keyboard(false)
        ui.removeCallbacks(rootCheck)
        if (PlayModes.onChanged === sourceChanged) PlayModes.onChanged = null
        tail?.stop()
        getSharedPreferences(GameFiles.PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(gameRootChanged)
        if (::loginWatch.isInitialized) loginWatch.stop()
    }

    /**
     * OUR OWN BATTLE.NET SIGN-IN (LATER 29) on the start screen: the row (the first time one "Battle.net login" button, then
     * [gear][Login]) shows while [HomeLogin.State.showRow]: for WoW Forever from its client's start until the world (its login screen
     * writes nothing; the user's pick "Show it until you're in the world"), for GameNative only on a login screen its log proves; never
     * during a reload. Login is greyed whenever it may not type ([HomeLogin.State.canType]); the line says where a sign-in is, and the
     * row's end gives the line back unless someone else has written a newer one.
     */
    private fun loginShow() {
        if (nativePreview) return
        val s = loginWatch.state
        // INSIDE THE ONE APP: the row only while it has something to say ([nativeRow]); a just-saved note shows once
        val native = if (LauncherHooks.pkg == null) null else nativeRow(s)
        val oneApp = LauncherHooks.pkg != null
        if (oneApp && s.realm) NativeLogin.clear(this)       // the native sign-in done: login.txt goes now at the latest (backstop)
        // the watcher's inWorld stays set for the run without root (its "back at login" needs root): out of the world = NativeLogin's
        if (if (oneApp) (native == null && loginNote == null) || !s.clientUp || (s.inWorld && !NativeLogin.outOfWorld) || reloadAt != 0L
            else !s.showRow || reloadAt != 0L) {
            startView.login = StartView.Login.NONE
            startView.loginBusy = false
            // the line back, but never over a newer one (the review: "Reloading the game's interface" must not be overwritten)
            lineBeforeLogin?.let { if (startView.line == lineWritten) startView.line = it }
            lineBeforeLogin = null; lineWritten = null
            downloadLine()
            // a start screen brought back only for the row goes with it (never during a reload, which owns the screen then)
            if (loginRevealed) { loginRevealed = false; if (reloadAt == 0L) startView.visibility = View.GONE }
            return
        }
        // the row lives on the start screen, which can be gone from an earlier client run (the review): a new run brings it back - but
        // never while the game is provably in the world (its documents are current), whatever a late log read still says
        if (startView.visibility != View.VISIBLE && !GameInput.live()) { startView.visibility = View.VISIBLE; loginRevealed = true }
        if (lineBeforeLogin == null) lineBeforeLogin = startView.line
        if (oneApp) {
            startView.login = if (loginNote != null) StartView.Login.NONE else native?.first ?: StartView.Login.NONE
            startView.loginBusy = false
            val shown = loginNote ?: native?.second ?: startView.line
            startView.line = shown; lineWritten = shown
            return
        }
        val saved = HomeLoginStore.has(this)
        startView.login = if (saved) StartView.Login.SAVED else StartView.Login.FIRST
        // greyed whenever Login may not type (a run, a queue, the authenticator, no answer yet, or the log says the game is past its
        // login screen), so a press can never type into character select or a dialog; the gear stays free
        startView.loginBusy = HomeLoginRunner.busy || !s.canType
        val line = loginNote ?: when (s.phase) {
            HomeLogin.Phase.IDLE -> when {
                s.realm || s.signingIn || (s.screen != null && !s.atLogin) -> "Signed in. Waiting for the world."
                s.atLogin -> if (saved) "The game's login screen." else "Sign in with your Battle.net login."
                saved -> "On the game's login screen? Tap Login."
                else -> "On the game's login screen? Sign in from here."
            }
            HomeLogin.Phase.RUNNING -> "Signing in."
            HomeLogin.Phase.QUEUE -> "In the login queue: ${s.queue}" + (if (s.queueAt.isNotEmpty()) " at ${s.queueAt}." else ".")
            HomeLogin.Phase.AUTH -> "Type the authenticator code on the top screen."
            HomeLogin.Phase.FAILED -> "Sign-in failed: ${s.reason}"
            HomeLogin.Phase.UNSURE -> "No answer from the game yet. Check the top screen."
        }
        startView.line = line; lineWritten = line
        // a failure shows the reason and does not retry by itself (the user's pick; the gold box itself is gone, 2026-10-05)
    }
    private var lineWritten: String? = null

    /**
     * B ON THE PRESS FRAME (the user 2026-10-09, "Now, before release": the flash and the B glyph's removal came only after the screen
     * was already Home; "this can all happen at the same time, there doesnt need to be a sequence to it"). In a room whose B goes
     * through the game, the app's own reader sees the press as the game does: when the room says its B leads Home ([BLeadsHome]), the
     * disc's flash, the glyph and the room's exit all start in this frame (show: strip.homePressed) and Home takes the screen at once.
     * The game's own B still closes its window and lets the controller go; that release (rel by B) is this leave's echo (isOwnLeave).
     * Not in a fight (the capture's secure leave then, the old route) nor under any overlay (the wheels, a dialog, the bar cursor).
     */
    private fun bPressed() {
        if (room == Room.HOME || shell.hostGone()) return
        val v = shownView as? BLeadsHome ?: return
        if (fightLeave.cell != null || barSelect.open || startView.visibility == View.VISIBLE || setupView.visibility == View.VISIBLE ||
            loginPrompt.isOpen || (::pauseView.isInitialized && pauseView.isOpen) || (::radialView.isInitialized && radialView.isOpen) ||
            tradOpen() || (::dialogView.isInitialized && dialogView.visibility == View.VISIBLE) ||
            (::interactView.isInitialized && interactView.visibility == View.VISIBLE) || spellsOverlay.visibility == View.VISIBLE) return
        if (!v.bLeadsHome()) return
        LatLog.mark("b", "B -> Home on the press (${room.name})")
        Log.i("WCHOME", "B pressed in ${room.name}: its B leads Home, Home now (the game's own B closes the room behind)")
        show(Room.HOME, byB = true)
    }

    /**
     * A ROOM LEFT BY THE GAME'S OWN B, ITS LEAVE TAPS WAIT ([bPressed]): the game's B is closing the window and letting the controller
     * go right now, so the room's own close cells (one of them a toggle: Bags' bag0) must not race it. They run [B_LEAVE_MS] later or
     * at the next show(), when the rooms' own guards see the release (nothing tapped then: exactly the old echo route) - and if B did
     * not leave after all, they do the leave as any route would.
     */
    private var bLeave: (() -> Unit)? = null
    private val bLeaveRun = Runnable { flushBLeave() }
    private fun flushBLeave() {
        ui.removeCallbacks(bLeaveRun)
        val f = bLeave ?: return
        bLeave = null
        f()
    }

    /** a room's own leave (its B, the addon's "back"): Home from that room only; after [bPressed] already went Home it is the echo */
    private fun roomLeft(r: Room) {
        if (room == r) show(Room.HOME) else flushBLeave()
    }

    /**
     * START OPENS THE PAUSE WHEEL FROM EVERY ROOM (the user 2026-10-09: "Wheel everywhere"; DS WoW toolbar on - off, Start stays the
     * game's own wheel), read by the app itself (DirectPad: Start is no room's registered key any more), ONE route everywhere. An open
     * keyboard keeps Start (Chat's Send, Bags' Search key: their ownsStart); an open wheel reads Start itself (it closes); the start
     * screen / setup take it. The router's own "pause" word that follows is dropped ([START_DEDUPE_MS]).
     */
    private fun startPressed() {
        if (shell.hostGone()) return
        if (room == Room.CHAT && chatView.ownsStart()) {
            chatView.onPad(JSONObject().put("type", "guestpad").put("id", "chat").put("k", "PADFORWARD").put("n", --directN)); return
        }
        if (room == Room.BAGS && bagsView.ownsStart()) return          // bagsView.onPadKey already had it (the unclaimed route)
        if (::interactView.isInitialized && interactView.ownsStart()) return   // an NPC window's keyboard: its Done (onPadKey had it)
        if (lastDpad?.want != true) return                              // the toolbar off: Start is the game's own wheel
        startReadAt = SystemClock.uptimeMillis()
        openPause("Start", byStart = true)
    }

    /** the pause wheel over the room on show (Start, or the bar's swirl): one route */
    private fun openPause(why: String, byStart: Boolean) {
        if (::radialView.isInitialized && radialView.isOpen) return
        if (::pauseView.isInitialized && pauseView.isOpen) return       // its own reader closes it
        if (tradOpen()) return
        if (startView.visibility == View.VISIBLE || setupView.visibility == View.VISIBLE || loginPrompt.isOpen) return
        Log.i("WCHOME", "pause: the wheel over ${room.name} ($why)")
        if (room == Room.HOME) partyLeaveHome("the pause wheel")          // the user's ruling: the wheel takes the controller: Party mode off
        roomMotionEnd()                                              // a room still moving lands first: the wheel moves it itself
        // a reverse still playing: stopped, and the open goes on from where the panels are (no jump)
        val from = if (pauseBack?.isRunning == true) slideT else 0f
        pauseBack?.cancel(); pauseBack = null
        pauseView.open(from, byStart)
    }
    /** the reverse running (a Start meanwhile cancels it and the open goes on from [slideT]) */
    private var pauseBack: android.animation.ValueAnimator? = null
    private var slideT = 0f

    /** Home's panels, the bar and the room on show, at [t] of the pause wheel's open (0 = at rest) */
    /** LB alone held now (CastView's reader) */
    private var lbAloneNow = false
    /** the LB picture shows while LB alone is held and nothing else has the screen: only in the world (1.0.19: never over the start-up
     *  screens), and only once the class wheel is closed and covered (1.0.20: they take turns, HomeView.classBusy) */
    private fun lbEval() {
        val free = room == Room.HOME && !pauseView.isOpen && !tradOpen() && !(::radialView.isInitialized && radialView.isOpen) &&
            // 1.0.21 (the user, streaming: "when I press L it didnt show the party member targetor"): GameLife only knows the Thor's own
            // game; a live add-on stream (Local or Streaming) counts too
            (GameLife.worldNow || GameInput.live(System.currentTimeMillis())) && (!::chooserPanel.isInitialized || chooserPanel.visibility != View.VISIBLE) && !homeView.classBusy()
        lbView.lb(lbAloneNow && free)
    }

    private fun pauseSlideAll(t: Float) {
        homeView.pauseSlide(t)
        // THE ROOM STEPS BACK (the user 2026-10-09: "the Pause screen needs to be pushing the other menus off screen"; UI Art's motion:
        // a full-screen room does not slide - alpha 1 -> 0 and scale 1 -> 0.96 about its centre over the first half of the open,
        // FastOutLinearIn; t = 0 puts it back at once). Home keeps its own slide-off (separate panels)
        shownView?.takeIf { it !== homeView }?.let { v ->
            val p = (t / (UiKit.PAUSE_OPEN_MS / 2f)).coerceIn(0f, 1f)
            val e = roomExit.getInterpolation(p)
            v.alpha = 1f - e; v.scaleX = 1f - 0.04f * e; v.scaleY = 1f - 0.04f * e
        }
        // test40 (the user: "Cycling on the pause wheel still has the problem from before where the rooms flash through the background.
        // Maybe to fix this we should slide the rooms down out of view"): the rooms' bar slides DOWN off the screen over the open (the
        // room's own exit curve) and stays down while the pause is up, the D-pad bar rising over where it was; on the close it comes back
        // up with the hub's glide ([stripBack], set by the close), so nothing of it is left behind the wheel to show through
        if (pauseView.isOpen || tradOpen()) {
            stripBack?.cancel()
            strip.translationY = (strip.height + 4f) * roomExit.getInterpolation((t / UiKit.PAUSE_OPEN_MS).coerceIn(0f, 1f))
        } else if (stripBack?.isRunning != true) strip.translationY = 0f
    }

    /** the rooms' bar's way back up on a pause close (the hub's glide), and its curve (the room's enter: LinearOutSlowIn) */
    private var stripBack: android.animation.ValueAnimator? = null
    private val stripIn = android.view.animation.PathInterpolator(0f, 0f, 0.2f, 1f)

    // ---------------------------------------------------------------- THE CHAT ROOM'S GLIDE (b-chat-slide)
    // The user's pick: chat going full screen from Home and back should move like the map's morph. Home's chat pane opens the Chat
    // ROOM (HomeView.ChatPane's tap), a room switch that used to swap at once. Now the room's view (laid out once, full size, as it
    // always is) is DRAWN scaled down into the pane's box and grows with the box to the frame (a view transform, no layout), cut to
    // a rounded window, on the map's time and curve
    // (Motion.MODULE_OPEN / MODULE_CLOSE, Motion.curve), over a picture of Home taken as it leaves (its map and bars go back to
    // their rooms then); back Home it shrinks onto the pane while Home comes in under it. No layout during the glide: an outline
    // and alphas only (a requestLayout per frame was what made the combat switch lag the game).
    private var chatGlideAnim: android.animation.ValueAnimator? = null
    private val chatGlideBox = android.graphics.RectF()
    private var chatGlidePic: android.widget.ImageView? = null
    private var chatGlideBmp: android.graphics.Bitmap? = null
    /** Home's chat pane, the picture that GROWS (Thor batch5), over the room until the hand-over */
    private var chatGlidePane: android.widget.ImageView? = null
    private val chatGlidePaneBox = android.graphics.RectF()
    /** a rounded window [box] in the view's own px (the corner as round on screen at every scale of the glide); empty = all of it */
    private class GlideClip(val box: android.graphics.RectF) : android.view.ViewOutlineProvider() {
        override fun getOutline(v: View, o: android.graphics.Outline) {
            val b = box
            if (b.isEmpty) o.setRect(0, 0, v.width, v.height)
            else o.setRoundRect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt(), UiKit.MODULE_R / v.scaleX.coerceAtLeast(0.05f))
        }
    }
    private val chatGlideOutline = GlideClip(chatGlideBox)
    private val chatGlidePaneOutline = GlideClip(chatGlidePaneBox)

    /**
     * [v] ([w] x [h] of its own px, laid out at its host's 0,0 + [ox], [oy]) drawn into the box [bl]..[bb] (host px): ONE scale (text
     * keeps its shape) just big enough to fill the box, its bottom-left on the box's; [clip] gets the box mapped back into [v]'s px
     */
    private fun glideInto(v: View, clip: android.graphics.RectF, w: Float, h: Float, ox: Float, oy: Float,
                          bl: Float, bt: Float, br: Float, bb: Float) {
        val sc = maxOf((br - bl) / w, (bb - bt) / h).coerceAtLeast(0.05f)
        val tx = bl; val ty = bb - h * sc
        v.scaleX = sc; v.scaleY = sc
        v.translationX = tx - ox; v.translationY = ty - oy
        clip.set((bl - tx) / sc, (bt - ty) / sc, (br - tx) / sc, (bb - ty) / sc)
        v.invalidateOutline()
    }

    /** Home as it looks now, drawn into one kept bitmap (null when it cannot be) */
    private fun homeShot(): android.graphics.Bitmap? = viewShot(homeView)

    /** [v] as it looks now, drawn into the one kept bitmap (the chat glide and the rooms' motion never run at once) */
    private fun viewShot(v: View): android.graphics.Bitmap? = try {
        val w = v.width; val h = v.height
        if (w <= 0 || h <= 0) null else {
            val b = chatGlideBmp?.takeIf { it.width == w && it.height == h && !it.isRecycled }
                ?: android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { chatGlideBmp = it }
            b.eraseColor(Theme.ink)
            v.draw(android.graphics.Canvas(b))
            b
        }
    } catch (t: Throwable) { Log.i("WCHOME", "room motion: no picture of the room left (${t.message})"); null }

    // ---------------------------------------------------------------- THE ROOMS' OPEN / CLOSE MOTION (f-room-animations)
    // The user after the Thor test (15 Oct): "I want it to slide up from the bottom, and then when pressing B, the home slides back
    // in from the top. if a room is currently selected, a new room should slide in from the side (directionally compared to where the
    // current room is in) the sliding in/out should not slide over top the rooms but rather push them off the screen (same with the
    // home)". So every room change is a PUSH: the two pages move together as one strip, edge to edge, no overlap, no fade:
    //  - Home -> a room: the room rises from the bottom edge and pushes Home up off the top (Motion.MODULE_OPEN);
    //  - a room -> Home (B, the disc): Home comes down from the top and pushes the room down off the bottom (MODULE_CLOSE);
    //  - a room -> another: sideways, by their places on the bottom bar ([barOrder]): one to the RIGHT comes in from the right and
    //    pushes the current one off to the left, and the other way round.
    // One curve (Motion.curve). Home leaving is a picture of Home (its map and bars go back to their rooms the moment it is left);
    // a room leaving draws itself. Translation only on hardware layers: no layout per frame. The chat's glide and the map's morph
    // keep their own motion; a room change or the pause wheel lands a motion still running.
    private var roomAnim: android.animation.ValueAnimator? = null
    private var roomAnimPic: android.widget.ImageView? = null
    private var roomAnimViews: List<View> = emptyList()

    /** a room's place on the bottom bar, left to right (Room's order, the strip's; Home's disc between Group Finder and Character).
     *  Chat has no place on the bar: it counts where it stands in Room's order (between Controller and Social) */
    private fun barOrder(r: Room): Int = r.ordinal

    private fun roomMotionStart(incoming: View, outgoing: View, from: Room, to: Room, outPicture: android.graphics.Bitmap?) {
        val leavingHome = from == Room.HOME
        if (leavingHome && outPicture == null) return              // no picture of Home: the old swap
        val w = content.width.toFloat(); val h = content.height.toFloat()
        if (w <= 0f || h <= 0f) return
        // where the incoming page starts (the outgoing one ends the opposite way: they move as one strip)
        val (sx, sy) = when {
            leavingHome -> 0f to h                                  // up from the bottom edge
            to == Room.HOME -> 0f to -h                             // down from the top edge
            barOrder(to) > barOrder(from) -> w to 0f                // a room to the right: in from the right
            else -> -w to 0f
        }
        val pic = if (leavingHome) android.widget.ImageView(this).apply {
            setImageBitmap(outPicture); scaleType = android.widget.ImageView.ScaleType.MATRIX
        }.also { content.addView(it, content.indexOfChild(incoming), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        else null
        roomAnimPic = pic
        val leaving: View = pic ?: outgoing.also { it.visibility = View.VISIBLE }     // a room leaving draws itself until it is off
        roomAnimViews = listOfNotNull(incoming, if (pic == null) outgoing else null)
        val baseX = if (pic != null) outgoing.left.toFloat() else 0f
        val baseY = if (pic != null) outgoing.top.toFloat() else 0f
        for (v in listOf(incoming, leaving)) v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        fun at(k: Float) {
            incoming.translationX = sx * (1f - k); incoming.translationY = sy * (1f - k)
            leaving.translationX = baseX - sx * k; leaving.translationY = baseY - sy * k
        }
        at(0f)
        roomAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (to == Room.HOME) Motion.MODULE_CLOSE else Motion.MODULE_OPEN
            interpolator = Motion.curve()
            addUpdateListener { a -> at(a.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (roomAnim === a) roomMotionEnd() }
            })
            start()
        }
    }

    /** the motion over (or cut short): the picture goes, every view moved is whole again, and only the room on show stays up */
    private fun roomMotionEnd() {
        val a = roomAnim ?: return
        roomAnim = null
        a.cancel()
        roomAnimPic?.let { content.removeView(it) }
        roomAnimPic = null
        for (v in roomAnimViews) {
            v.translationX = 0f; v.translationY = 0f
            v.setLayerType(View.LAYER_TYPE_NONE, null)
            if (v !== shownView) v.visibility = View.GONE
        }
        roomAnimViews = emptyList()
    }

    /** [opening]: from the pane's box ([pane], Home's px) to the frame over Home's [picture]; else from the frame onto the pane */
    private fun chatGlideStart(opening: Boolean, pane: android.graphics.RectF, picture: android.graphics.Bitmap?) {
        // the room's full size: its own, or (never laid out yet: the first open) the content's it fills
        val fw = (chatView.width.takeIf { it > 0 } ?: content.width).toFloat()
        val fh = (chatView.height.takeIf { it > 0 } ?: content.height).toFloat()
        if (fw <= 0f || fh <= 0f) return
        val cx = chatView.left.toFloat(); val cy = chatView.top.toFloat()
        // everything below in CONTENT px: the pane's box, and the frame the room fills
        val p = android.graphics.RectF(pane).apply { offset(homeView.left.toFloat(), homeView.top.toFloat()) }
        val pw = pane.width().toInt(); val ph = pane.height().toInt()
        val paneBmp = homeView.chatPanePicture(pw, ph)
        val pic = picture?.let { bmp ->
            android.widget.ImageView(this).apply {
                setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.MATRIX
                translationX = homeView.left.toFloat(); translationY = homeView.top.toFloat()
            }.also { content.addView(it, content.indexOfChild(chatView), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        }
        chatGlidePic = pic
        // THE PANE ITSELF GROWS (Thor batch5, the user: "it looks like a new window is popping up and then growing instead of that chat
        // window growing"): Home's chat pane as it looks, over the room, in the same gliding box; the room is under it from the
        // first frame and shows only as the pane hands over, between HAND_FROM and HAND_TO of the way (back Home: the reverse)
        val paneV = paneBmp?.let { bmp ->
            android.widget.ImageView(this).apply {
                setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.MATRIX
                pivotX = 0f; pivotY = 0f
                outlineProvider = chatGlidePaneOutline; clipToOutline = true
            }.also { content.addView(it, content.indexOfChild(chatView) + 1, FrameLayout.LayoutParams(bmp.width, bmp.height)) }
        }
        chatGlidePane = paneV
        chatView.visibility = View.VISIBLE                         // back Home the room's view stays up, over Home, until it lands
        chatView.outlineProvider = chatGlideOutline
        chatView.clipToOutline = true
        val ms = if (opening) Motion.MODULE_OPEN else Motion.MODULE_CLOSE
        val fade = HomeView.FADE_MS
        chatView.pivotX = 0f; chatView.pivotY = 0f
        fun at(k: Float, t: Float) {
            val g = if (opening) k else 1f - k
            // the box at g, content px: the pane's box at 0, the room's frame at 1
            val bl = p.left + (cx - p.left) * g; val bt = p.top + (cy - p.top) * g
            val br = p.right + (cx + fw - p.right) * g; val bb = p.bottom + (cy + fh - p.bottom) * g
            // THE CONTENTS GROW WITH THE BOX (Thor batch4): the room and the pane each drawn into the box at one scale, bottom-left
            // on the box's (the type bar and the newest lines), cut to the box: a view transform, no layout
            glideInto(chatView, chatGlideBox, fw, fh, cx, cy, bl, bt, br, bb)
            if (paneV != null) glideInto(paneV, chatGlidePaneBox, pw.toFloat(), ph.toFloat(), 0f, 0f, bl, bt, br, bb)
            // the hand-over: the pane's picture leaves as the box nears the frame (and comes back as it nears the pane)
            val hand = ((g - HAND_FROM) / (HAND_TO - HAND_FROM)).coerceIn(0f, 1f)
            paneV?.alpha = 1f - hand * hand * (3f - 2f * hand)
            // the room's message bar (the pane has none) slides up into place over the glide's last part, the log's lines riding up
            // with it; back Home it slides down out of the box first, so the room's lines meet the pane's where they stand (Thor batch7)
            val bar = ((g - BAR_FROM) / (1f - BAR_FROM)).coerceIn(0f, 1f)
            chatView.glideBar(bar * bar * (3f - 2f * bar))
            if (opening) {
                // Home's other parts (its picture) fade over the first FADE ms, as Home's own do; without the pane's picture the
                // room comes in inside its box meanwhile (the old way)
                chatView.alpha = if (paneV != null) 1f else (t / fade).coerceIn(0f, 1f)
                pic?.alpha = 1f - (t / fade).coerceIn(0f, 1f)
            } else {
                // the box lands on the pane (its picture whole by then): Home comes in under it over the last FADE ms
                val inK = ((t - (ms - fade)) / fade).coerceIn(0f, 1f)
                chatView.alpha = 1f - inK
                homeView.alpha = inK
            }
        }
        at(0f, 0f)
        chatGlideAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = Motion.curve()
            addUpdateListener { a -> at(a.animatedValue as Float, a.animatedFraction * ms) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (chatGlideAnim === a) chatGlideEnd() }
            })
            start()
        }
    }

    /** the glide over: the picture goes, the room's view whole again (shown only while the Chat room is) */
    private fun chatGlideEnd() {
        val a = chatGlideAnim ?: return
        chatGlideAnim = null
        a.cancel()
        chatGlidePic?.let { content.removeView(it) }
        chatGlidePic = null
        chatGlidePane?.let { content.removeView(it) }
        chatGlidePane = null
        chatGlidePaneBox.setEmpty()
        chatGlideBox.setEmpty()
        chatView.clipToOutline = false
        chatView.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        chatView.alpha = 1f
        chatView.glideBar(1f)
        chatView.scaleX = 1f; chatView.scaleY = 1f
        chatView.translationX = 0f; chatView.translationY = 0f
        homeView.alpha = 1f
        chatView.visibility = if (room == Room.CHAT) View.VISIBLE else View.GONE
    }

    /** the pane's picture hands over to the room between these two points of the glide's way (0 = the pane's box, 1 = the frame) */
    private val HAND_FROM = 0.35f
    private val HAND_TO = 0.8f
    /** the room's message bar rises into place from this point of the way to the frame (Thor batch7) */
    private val BAR_FROM = 0.45f

    private var startReadAt = 0L
    /** the room's view on show (the pause wheel makes it step back) */
    private var shownView: View? = null
    private val roomExit = android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f)      // FastOutLinearIn (UI Art)
    private var startToldWant: Boolean? = null
    /** qact 36 = Start to the rooms (our wheel), 35 = Start to the game (Quests q35); answered by guestack {op "start"} */
    private fun startTell(rooms: Boolean) {
        val sent = TapBridge.tap(this, questsPad, "qact", if (rooms) 36 else 35) { why -> Log.i("WCHOME", "start: told refused ($why)") }
        Log.i("WCHOME", "start: rooms' Start -> ${if (rooms) "our wheel" else "the game"} ${if (sent) "told" else "NOT told (no q35 yet)"}")
        if (sent) startToldWant = rooms
    }

    /**
     * THE KEY SWEEP'S TRIGGER (Quests' pad10c probe; the user: "the sweep only on your word"): ONE broadcast from the PC, sent only when
     * tracking relays the user's "sweep" - `adb shell am broadcast -a app.dsforever.companion.KEYSWEEP -p app.dsforever.companion`.
     * The sweep presses only Quests' test keys (CTRL-SHIFT row keys alone, the keytest row's columns), which do nothing in the game;
     * live keys stay off (TapBridge.KEYS_ON). Registered once per app life, inside the launcher only.
     */
    private fun sweepTrigger() {
        if (sweepRegistered) return
        sweepRegistered = true
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                if (i.action != SWEEP_ACTION || shell.hostGone()) return
                Log.i("WCHOME", "keys: sweep asked (the user's word)")
                TapBridge.keySweep(this@CompanionHost, questsPad, 300L) { res -> Log.i("WCHOME", "keys: sweep $res") }
            }
        }
        val f = android.content.IntentFilter(SWEEP_ACTION)
        if (android.os.Build.VERSION.SDK_INT >= 33) applicationContext.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
        else applicationContext.registerReceiver(r, f)
    }

    /**
     * THE ROW INSIDE THE ONE APP, ONLY WHEN NEEDED (the user 2026-10-08: "It still shows battle.net login on the bottom screen. How can
     * we make that make more sense contextually when its needed"; the pick "Only when needed"). The game signs in by itself from the
     * private login.txt (NativeLogin); nothing is ever typed. null = no row (character select, the world, after a sign-in, or a saved
     * login whose game has not reached its login screen yet). The saved login is changed in Settings.
     */
    private fun nativeRow(s: HomeLogin.State): Pair<StartView.Login, String>? = when {
        s.realm || NativeLogin.pastLogin -> null
        // 2026-11-07 (the user: "the sign in prompts are still appearing ... if I dont push anything fast enough"): no authenticator,
        // failure or "not signed in yet" lines either - account select read as "not signed in"; the Menu and Keyboard are the way
        !HomeLoginStore.has(this) -> StartView.Login.FIRST to "Save it once and the game signs in by itself."
        NativeLogin.firstReadAt > 0L && NativeLogin.active -> StartView.Login.NONE to "Signing in…"
        else -> null
    }
    private var loginRevealed = false

    /**
     * issue #215 (diagnostics only, 1.0.27: the bottom screen "just a background graphic" while the game downloaded past its login
     * screen): what is on show, for NativeLogin's busy-minute line and the report's "Bottom screen when the report page opened" line - the start screen and its line (the app's own words), or the room
     * behind it and whether any current document came. Main thread.
     */
    private fun bottomState(atReport: Boolean = false): String {
        if (!::startView.isInitialized) return "not up yet"
        fun shown(v: View?) = v != null && v.visibility == View.VISIBLE
        val start = if (shown(startView)) "start screen '${startView.line}' (row ${startView.login.name.lowercase()}${if (loginRevealed) ", for the row" else ""})"
            else "no start screen${if (reloadAt != 0L) " (reload)" else ""}"
        val over = buildList {
            if (::setupView.isInitialized && shown(setupView)) add("setup")
            if (::setupPanel.isInitialized && shown(setupPanel)) add("setup pop-up")
            if (::chooserPanel.isInitialized && shown(chooserPanel)) add("chooser")
            if (::loginPrompt.isInitialized && loginPrompt.isOpen) add("login prompt")
        }
        // issue #231 (review): at report time, with no document yet, the reader's state NOW (the silence line's is from 2 min in)
        val docs = AddonSilence.atReport(docFlowSeen, if (atReport && !docFlowSeen) readerDetail() else null)
        return "$start; room ${room.name.lowercase()}; documents ${if (docFlowSeen) "seen" else "none yet"}$docs" +
            if (over.isEmpty()) "" else "; over it: ${over.joinToString()}"
    }

    /** issue #132 (the user's pick 2026-11-12): the plain waiting line says "Game downloading" while the game fetches its files */
    private fun downloadLine() {
        if (!::startView.isInitialized) return
        val l = PreLoginTraffic.waitingLine(startView.line, NativeLogin.downloading)
        if (l != startView.line) { startView.line = l; startView.invalidate() }
    }

    /**
     * OUT OF THE WORLD, STILL SIGNED IN ([HomeLogin.State.away]: character select after a logout, measured 2026-10-06 - the bottom
     * screen kept the world it left): the start screen instead, the user's pick ("show a waiting screen there"). The next current
     * document hides it ([handleMessage]'s rule); never during a reload or the setup, never while the game's documents are current.
     */
    /**
     * THE LOGOUT WORD (Hub hub6, the user 2026-10-06: "I tested logging out and the bottom screen still looks like its in game"): the
     * add-on's "bye" at PLAYER_LOGOUT, flushed at once, so the start screen comes ~1 s after the world closes instead of after
     * [awayShow]'s 30 s of silence (which stays for disconnects). Not during the app's own reload (a /reload fires PLAYER_LOGOUT too)
     * or the setup; the next current document hides it again ([handleMessage]'s rule).
     */
    /**
     * ROOT REFUSED OR ASKED, SAID ON THE START SCREEN (the share's fresh test 2026-10-06; the user: "Both: SETUP line + app message"):
     * while the start screen waits for a game that is not live, [HomeRoot] is asked every 5 s (every 30 s once root is ours); a saved
     * Deny or a waiting Magisk prompt replaces the plain waiting line, and root granted gives it back. Never over another line (the
     * sign-in row's, a reload's, the waiting-for-the-world line).
     */
    private val rootCheck = object : Runnable {
        override fun run() {
            if (shell.hostGone()) return
            if (startView.visibility == View.VISIBLE && !GameInput.live()) HomeRoot.check { st ->
                ui.post {
                    // root came back after a refusal or a waiting prompt: the setup check that ran without it runs again (the share's
                    // fresh test: the Magisk switch turned on, "root: OK", and no Set up prompt - it is checked once, at the start)
                    if (st == HomeRoot.State.OK && rootWasMissing) { rootWasMissing = false; ui.postDelayed({ setupCheck() }, 1_000L) }
                    if (st != HomeRoot.State.OK) rootWasMissing = true
                    rootOk = st == HomeRoot.State.OK
                    HomeRoot.line(st, startView.line)?.let { if (it != startView.line) { Log.i("WCHOME", "root: $st"); startView.line = it } }
                }
            }
            ui.postDelayed(this, if (rootOk) 30_000L else 5_000L)
        }
    }
    private var rootOk = false
    private var rootWasMissing = false

    private fun byeShow() {
        if (reloadAt != 0L || setupView.visibility == View.VISIBLE) return
        Log.i("WCHOME", "bye: the world closed (logout); the waiting screen")
        if (LauncherHooks.pkg != null) NativeLogin.rearm("the add-on's logout word")
        startView.line = "Waiting for the world."; startView.visibility = View.VISIBLE
    }

    private fun awayShow() {
        if (!loginWatch.state.away || reloadAt != 0L || GameInput.live()) return
        if (startView.visibility == View.VISIBLE || setupView.visibility == View.VISIBLE) return
        startView.line = "Waiting for the world."; startView.visibility = View.VISIBLE
    }
    /** a one-off line over the state's own (e.g. "Saved. ..."); cleared by the next change of the state */
    private var loginNote: String? = null

    /**
     * Login by TOUCH, or the prompt's "Save and log in" ([explicit]: the user asked for a run themselves, so an earlier run's unknown
     * end does not hold it back). Types the saved login once; no saved login opens the prompt. Every check and change of the state
     * runs on the watcher's thread (the review: it was changed from two threads).
     */
    private fun signIn(login: HomeLoginStore.Login? = HomeLoginStore.load(this), explicit: Boolean = false) {
        if (loginPreviewing) return                       // the preview's state is made up: it never types
        if (login == null) { loginPrompt.open(); return }
        if (reloadAt != 0L || HomeLoginRunner.busy) return
        loginWatch.change { s ->
            if (explicit) s.rearm()
            if (!s.canType) {
                if (explicit) ui.post { loginNote = "Saved. Tap Login on the game's login screen."; loginShow() }
                return@change
            }
            s.running()
            ui.post { loginNote = null; startRun(login) }
        }
    }

    private fun startRun(login: HomeLoginStore.Login) {
        val run = ++loginRun
        HomeLoginRunner.run(this, login) { why ->
            loginWatch.change { s -> if (why != null) s.fail(why) }
            // typed, and the game has written nothing yet: say so, and keep Login shut until the log, a client restart or the gear
            ui.postDelayed({ if (run == loginRun) loginWatch.change { it.noAnswer() } }, HomeLogin.NO_ANSWER_MS)
        }
        loginShow()
    }
    private var loginRun = 0
    private var loginPreviewing = false
    private var nativePreview = false

    /** [onCreate]'s login_preview: the row as the login screen would show it, from made-up lines. It never types ([signIn] refuses) */
    private fun loginPreview(p: String) {
        loginPreviewing = true
        if (p.startsWith("native_")) {
            // the one app's rows, drawn as nativeRow gives them (the watcher's own changes no longer touch the row: loginShow returns)
            nativePreview = true
            startView.firstLabel = "Save your Battle.net login"; startView.firstW = 680f
            val (login, line) = when (p) {
                "native_save" -> StartView.Login.FIRST to "Save it once and the game signs in by itself."
                "native_signing" -> StartView.Login.NONE to "Signing in…"
                "native_auth" -> StartView.Login.NONE to "Enter the authenticator code on the game screen."
                else -> StartView.Login.NONE to "Not signed in yet? Enter it on the game screen."
            }
            startView.login = login; startView.line = line; startView.visibility = View.VISIBLE
            return
        }
        loginWatch.change { s ->
            s.client(true, true, 0L)
            s.note(HomeLogin.Event.Screen(HomeLogin.LOGIN_SCREEN))
            when (p) {
                "failed" -> s.note(HomeLogin.Event.Failed("Status has no name (14400)"))
                "queue" -> s.note(HomeLogin.Event.Queue(2036, "17:47"))
            }
        }
        ui.postDelayed({
            if (p == "first") startView.login = StartView.Login.FIRST
            if (p == "prompt") loginPrompt.open()
        }, 300L)
    }

    /**
     * The pad on the row (read-only: the game gets the same presses): only A, on Login (no box, no D-pad: the user, 2026-10-05).
     * A on Login signs in only where it is safe (signIn -> canType, on the watcher's thread): the game ignores A on its login screen
     * (measured), and past it (a realm connection, a sign-in begun) the typing is refused and the A stays the game's own.
     */
    private fun loginPad(down: Int) {
        if (PadInput.isHeld(down, PadInput.SLOT_FACE_BOTTOM)) startView.padA()
    }

    /** one tap on the Spellbook's pad row (open 0, close 1, a spell's Bind 2..39); nothing when the pad is not known yet */
    private var talentsPad: JSONObject? = null

    /** one tap on a Talents pad row (`talents`: actions, `talentn`: a no-action pick of a cell) */
    /** one tap on a generic controller guest's cell (Quests.lua q6's `qact` columns); false when the pad has no such row */
    private fun guestTap(cells: List<Pair<String, Int>>?): Boolean =
        cells != null && TapBridge.taps(this, questsPad, cells) { }

    private fun talentsTap(row: String, col: Int) {
        TapBridge.tap(this, talentsPad ?: spellsPad, row, col)
    }

    private fun spellsTap(col: Int?) {
        if (col == null) return
        TapBridge.tap(this, spellsPad, "spells", col)
    }

    /** GitHub #43 cb3: the Bind box's Cast bar tab (its tab, a slot: padtab 39 then 26 / 20-25, Book b56), through the freshest pad
     *  that has the row */
    private fun castBarTaps(cells: List<Pair<String, Int>>) {
        val need = cells.map { it.first }.toSet()
        val pad = listOf(spellsPad, questsPad, talentsPad).firstOrNull { p -> p?.optJSONObject("rows")?.let { r -> need.all { r.has(it) } } == true } ?: spellsPad
        val sent = TapBridge.taps(this, pad, cells) { why -> Log.i(HomeView.TAG, "cast bar: refused ($why)") }
        Log.i(HomeView.TAG, "cast bar: ${cells.joinToString(" ") { "${it.first} ${it.second}" }} ${if (sent) "tapped" else "not sent"}")
    }

    /** b-druid-forms-bind: a spell's Bind by touch, its taps from the book on show (Book b38's two, or an older add-on's one) in ONE batch */
    private fun spellsCells(e: SpellsModel.Entry) {
        val cells = lastBook?.let { SpellsModel.bindCells(it, e) } ?: return
        TapBridge.taps(this, spellsPad, cells)
    }

    // ---------------------------------------------------------------- the D-pad room bar (Home, 2026-10-02; Spells' padmap m25)
    // The user: "I want this to work just like the in combat bar, and have its own group page that is on the default layer out of
    // combat" (left / right only) + "as long as it can be toggle off". Spells' router holds the BARE D-pad left / right out of combat
    // (`dpad.on`), the game's own D-pad in fights; Cast draws it as its own "Room bar" group while it is on.

    /** Spells' last word on the D-pad room bar (null = an older add-on) */
    private var lastDpad: PadMapModel.Dpad? = null
    /** p35: the bare D-pad Down opens Home's room bar: the DS WoW toolbar is on, or the Home Bar command (DSHOME) is on that button */
    private fun downIsHomeBar(): Boolean =
        lastDpad?.want == true || lastPadMap?.let { m -> PadMapModel.slot(m, 1, 4)?.key == PadMapModel.HOME_KEY } == true

    // ---------------------------------------------------------------- f-new-spell-prompt: learned spells, one prompt at a time on Home
    /** the user (1.0.9): "After learning new spells when I return home they should have a prompt 1 at a time a to bind or b to dismiss" */
    private val newSpells by lazy { NewSpells.Store(getSharedPreferences("newspells", MODE_PRIVATE)) }
    private var newSpellWho: String? = null           // the character now (the padmap's name-realm)
    private var newSpellMap: PadMapModel.Map? = null  // what is on the buttons (a spell already there is not news)
    private var newSpellAsking = 0                    // the spell the open (or waiting) prompt is about
    private var newSpellBinding = 0                   // the spell whose Bind box A opened (Home again when it closes)
    private var newSpellWaitId = 0
    private var newSpellTick = 0

    /** a `spells` document: the add-on's news joins this character's queue; then maybe ask */
    private fun newSpellsFrom(book: SpellsModel.Book) {
        val who = newSpellWho ?: return
        val onPad = NewSpells.onPad(newSpellMap)
        val q = NewSpells.enqueue(newSpells.queue(who), newSpells.handled(who), book.news.sortedBy { it.nid }.map { it.spellId }) {
            NewSpells.askKind(book, it, onPad) != null                        // rank up (2026-11-12): a new rank of a bound spell too
        }
        if (q != newSpells.queue(who)) { newSpells.add(who, q); Log.i(HomeView.TAG, "new spells: queue $q") }
        newSpellAsk()
    }

    /** the next prompt, when the player is on Home with nothing else up: no room, no module of Home's, no game window, no fight */
    private fun newSpellAsk() {
        val who = newSpellWho ?: return
        val book = lastBook ?: return
        if (newSpellBinding != 0) { if (room == Room.HOME) { newSpellBinding = 0; newSpellConfirm = 0 } else return }   // back Home: that bind is over
        if (newSpellAsking != 0) return
        if (room != Room.HOME || homeView.homeGuestWanted() || capHolder.isNotEmpty() || homeView.inFight() || book.combat) return
        if (setupView.visibility == View.VISIBLE || setupWaiting != null || startView.visibility == View.VISIBLE) return
        if (interactView.visibility == View.VISIBLE || dialogView.visibility == View.VISIBLE || spellsOverlay.visibility == View.VISIBLE) return
        val onPad = NewSpells.onPad(newSpellMap)
        val q = NewSpells.stillWanted(newSpells.queue(who)) { NewSpells.askKind(book, it, onPad) != null }
        if (q != newSpells.queue(who)) newSpells.setQueue(who, q)            // bound / upgraded meanwhile: dropped unasked
        val id = q.firstOrNull() ?: return
        val e = NewSpells.entryOf(book, id) ?: return
        val kind = NewSpells.askKind(book, id, onPad) ?: return
        newSpellAsking = id
        val title = "New spell: ${e.name}" + (e.rank?.let { " ($it)" } ?: "")
        val show = if (kind == NewSpells.Ask.RANK_UP) {
            // rank up (the user 2026-11-12, "Prompt, A upgrades all"): A = every lower copy at once, B = never this rank again
            val n = NewSpells.rankUpOf(book, id)?.count ?: 0
            ({
                setupView.ask(CompanionSetup.Prompt(Tr.t(NewSpells.rankUpTitle(e)), emptyList(), Tr.t(NewSpells.rankUpCount(n))), "Upgrade", "Skip",
                    onYes = { newSpellRankUp(who, id) }, onNo = { newSpellAnswer(who, id, bind = false) }, icon = e.icon)
            })
        } else ({
            setupView.ask(CompanionSetup.Prompt(title, emptyList(), "Put it on a controller button now, or dismiss it."), "Bind", "Dismiss",
                onYes = { newSpellAnswer(who, id, bind = true) }, onNo = { newSpellAnswer(who, id, bind = false) }, icon = e.icon)
        })
        // only once the game hands Home the controller: a press never also reaches the game (setupHoldAck runs [setupWaiting])
        setupWaiting = show
        val wait = ++newSpellWaitId
        if (!setupPadOn()) { setupWaiting = null; newSpellAsking = 0; return }
        Log.i(HomeView.TAG, "new spells: ask $id (${e.name}, ${kind.name.lowercase()}), waits for the controller")
        ui.postDelayed({ if (wait == newSpellWaitId && newSpellAsking == id && setupView.visibility != View.VISIBLE) newSpellRetry("no hold") }, 6_000L)
    }

    /** the controller never came (or was refused): nothing shows; asked again later */
    private fun newSpellRetry(why: String) {
        Log.i(HomeView.TAG, "new spells: not asked now ($why)")
        setupWaiting = null; setupPadOff(); newSpellAsking = 0
        ui.postDelayed({ newSpellAsk() }, 5_000L)
    }

    /** A = Bind: the Spellbook's own Bind box for that spell (the game's controller press puts it on); B = Dismiss. Never asked again. */
    private fun newSpellAnswer(who: String, id: Int, bind: Boolean) {
        newSpells.done(who, id)
        newSpellAsking = 0
        setupView.close(); setupPadOff()
        val e = if (bind) lastBook?.let { NewSpells.bindEntry(it, id) } else null
        if (e == null) { ui.postDelayed({ newSpellAsk() }, 400L); return }
        Log.i(HomeView.TAG, "new spells: bind $id")
        newSpellBinding = id; newSpellConfirm = 0
        show(Room.SPELLBOOK); spellbookView.openAt(id)
        ui.postDelayed({ spellsCells(e) }, 400L)
        ui.postDelayed({ if (newSpellBinding == id && room != Room.SPELLBOOK) newSpellBindDone() }, 120_000L)
    }

    /** RANK UP's A (the user 2026-11-12, "Done at once"): Book b51's pick and the new rank's two taps; the add-on upgrades every lower
     *  copy at once and answers padack op "rankup" (its count), which the prompt shows in one line ("Upgraded on 3 buttons") before it
     *  closes. In combat it is refused and asked again later (the prompt's own gates hold it until the fight is over) */
    private var newSpellRankWait = 0                  // the spell whose A was sent, waiting for the add-on's answer
    private fun newSpellRankUp(who: String, id: Int) {
        if (newSpellRankWait != 0) return
        val cells = lastBook?.let { NewSpells.rankUpCells(it, id) }
        if (cells == null || !TapBridge.tapsSpaced(this, spellsPad, cells, 120L)) {
            Log.w(HomeView.TAG, "new spells: rank up $id not sent (no touch way)")
            newSpellRankDone(who, id, "That did not work", done = true); return
        }
        newSpellRankWait = id
        Log.i(HomeView.TAG, "new spells: rank up $id sent")
        ui.postDelayed({ if (newSpellRankWait == id) { Log.w(HomeView.TAG, "new spells: rank up $id, no answer"); newSpellRankDone(who, id, "That did not work", done = true) } }, 5_000L)
    }
    private fun newSpellRankAck(a: PadMapModel.Ack) {
        val id = newSpellRankWait
        if (id == 0 || a.what != id.toString()) return
        val who = newSpellWho ?: return
        Log.i(HomeView.TAG, "new spells: rank up $id ${if (a.ok) "upgraded ${a.idx ?: 0}" else "refused (${a.why})"}")
        when {
            a.ok -> newSpellRankDone(who, id, NewSpells.rankUpLine(a.idx ?: 0), done = true)
            a.why == "combat" -> newSpellRankDone(who, id, "Not while in combat", done = false)   // deferred: asked again after the fight
            else -> newSpellRankDone(who, id, "That did not work", done = true)
        }
    }
    /** the answer's line on the prompt for a moment, then the next one; [done] = never asked again (this rank) */
    private fun newSpellRankDone(who: String, id: Int, line: String, done: Boolean) {
        newSpellRankWait = 0
        if (done) newSpells.done(who, id)
        setupView.answer(Tr.t(line))
        ui.postDelayed({
            if (newSpellAsking == id) { newSpellAsking = 0; setupView.close(); setupPadOff(); ui.postDelayed({ newSpellAsk() }, 400L) }
        }, 1_500L)
    }

    /** the user 2026-11-12 ("Wait for keep/undo"): a bind that took waits for the box's keep / undo (or its close) before Home. Not
     *  stuck: no word within [NEW_SPELL_CONFIRM_MS] ends it as before; leaving the room is the 120 s timer / Home's newSpellAsk as before */
    private var newSpellConfirm = 0                   // >0: the generation of the armed keep / undo wait; 0 = not waiting
    private var newSpellConfirmGen = 0
    private val NEW_SPELL_CONFIRM_MS = 90_000L

    private fun newSpellBindStep(step: SpellsModel.BindStep) {
        if (newSpellBinding == 0) return
        when (step) {
            SpellsModel.BindStep.DONE -> newSpellBindDone()
            SpellsModel.BindStep.WAIT -> newSpellConfirm = 0
            SpellsModel.BindStep.CONFIRM -> {
                val gen = ++newSpellConfirmGen
                newSpellConfirm = gen
                val id = newSpellBinding
                Log.i(HomeView.TAG, "new spells: bound $id, waits for keep / undo")
                ui.postDelayed({ if (newSpellConfirm == gen && newSpellBinding == id) { Log.i(HomeView.TAG, "new spells: no keep / undo word, Home"); newSpellBindDone() } }, NEW_SPELL_CONFIRM_MS)
            }
        }
    }

    private fun newSpellBindCtl(c: SpellsModel.Ctl) {
        if (newSpellBinding == 0) return
        SpellsModel.bindStepCtl(c, newSpellConfirm != 0)?.let { newSpellBindStep(it) }
    }

    // ---------------------------------------------------------------- THE ONE-TIME "TRY THE NEW TAP-TO-CAST BAR?" QUESTION
    /** the user, 2026-11-13: "For this new install, I want it to have a one time pop up asking people if they want to try the new touch
     *  input bar. if they select yes then assign it as both with the default, and hide in combat as the default." and "show a little
     *  picture with the pop up showing how it looks with it animating away in combat". Rules in [CastBarAsk]; the prompt look of the
     *  D-pad / new spell questions ([SetupView.ask]) with the bar's picture; A / B once the game hands Home the controller, the D-pad
     *  moving the gold box. A fight (or leaving Home) puts it away unanswered; it comes back once Home has stood quiet again */
    private var castAskOpen = false          // the question shows, or waits for the controller
    private var castAskReadySince = 0L       // when Home last became ready to ask (0 = not ready)
    private var castAskWaitId = 0
    private var castAskFrames: PreviewStage.AskFrames? = null   // the picture's frames while it shows (dropped when it closes)

    private fun castBarAsk() {
        if (!::setupView.isInitialized || !::startView.isInitialized) return
        val fight = homeView.inFight()
        // merge review (1.0.30 code 1464): the question's own hold ("pad:home", setupPadOn) is not "Home left": it put itself away
        val onHome = room == Room.HOME && !homeView.homeGuestWanted() && CastBarAsk.capFree(capHolder, castAskOpen && setupPad)
        if (castAskOpen) {
            if (CastBarAsk.hideNow(true, fight, onHome)) {
                Log.i(HomeView.TAG, "cast bar ask: put away (${if (fight) "a fight" else "Home left"}), asked again later")
                castAskOpen = false; castAskReadySince = 0L; castAskWaitId++
                setupView.close(); setupPadOff(); castAskFrames?.drop(); castAskFrames = null
            }
            return
        }
        val busy = setupView.visibility == View.VISIBLE || setupWaiting != null || setupBusy || setupLoading ||
            startView.visibility == View.VISIBLE || reloadAt != 0L || loginPrompt.isOpen || newSpellAsking != 0 || newSpellBinding != 0 ||
            interactView.visibility == View.VISIBLE || dialogView.visibility == View.VISIBLE || spellsOverlay.visibility == View.VISIBLE ||
            !PreviewStage.ready                                               // the picture is the real Home: the game's documents first
        val inWorld = docCurrent && (GameLife.worldNow || LauncherHooks.pkg == null)
        val now = SystemClock.uptimeMillis()
        if (!CastBarAsk.ready(inWorld, onHome, busy, fight)) castAskReadySince = 0L
        else if (castAskReadySince == 0L) castAskReadySince = now
        val act = CastBarAsk.decide(HomeSettings.castBarAsked, HomeSettings.castBarChosen, HomeSettings.castBarSide,
            if (castAskReadySince == 0L) 0L else now - castAskReadySince)
        when (act) {
            CastBarAsk.Act.NONE, CastBarAsk.Act.WAIT -> return
            CastBarAsk.Act.MARK -> { HomeSettings.castBarAsked = true; Log.i(HomeView.TAG, "cast bar ask: the player set the bar already, never asked"); return }
            CastBarAsk.Act.ASK -> {}
        }
        castAskOpen = true
        val show = {
            // the user, 2026-11-13: "make sure that this pop up uses realistic visuals instead of the renders": the real Home in
            // miniature (PreviewStage's own, fed the game's documents), the bar on Both with the player's spells
            val known = lastBook?.let { b -> SpellsModel.tabSpells(b).filter { !it.passive && it.icon > 0 }.map { (it.spellId ?: it.actionId) to it.icon } }
                ?: emptyList()
            val frames = PreviewStage.AskFrames(this, CastBarAsk.pictureBar(CastStripModel.Live.bar, known, { Icons.get(it) != null }),
                CAST_ASK_PIC_H / 1080f)
            castAskFrames = frames
            setupView.ask(CompanionSetup.Prompt(Tr.t(CastBarAsk.TITLE), emptyList(), ""), "Yes", "No",
                onYes = { castBarAnswer(true) }, onNo = { castBarAnswer(false) },
                picture = SetupView.Picture(CAST_ASK_PIC_H, animated = true) { c, r, t -> frames.draw(c, r, t) },
                cursor = true)
        }
        // as the new spell question: it shows once the game hands Home the controller (setupHoldAck runs [setupWaiting]), so an A
        // never also reaches the game; no hold in time = shown anyway, touch first (A / B once the hold lands)
        setupWaiting = show
        val wait = ++castAskWaitId
        if (!setupPadOn()) { setupWaiting = null; castAskOpen = false; return }
        Log.i(HomeView.TAG, "cast bar ask: waits for the controller")
        ui.postDelayed({ if (wait == castAskWaitId && castAskOpen && setupWaiting === show) setupShowAnyway("cast bar ask: no hold") }, 6_000L)
    }

    /** Yes = the bar under both columns, Hide in combat; No = it stays Off. Never asked again either way */
    private fun castBarAnswer(yes: Boolean) {
        if (!castAskOpen) return
        castAskOpen = false; castAskWaitId++
        CastBarAsk.answer(yes)
        Log.i(HomeView.TAG, "cast bar ask: ${if (yes) "Yes - the bar on Both, Hide in combat" else "No - the bar stays Off"}")
        setupView.close(); setupPadOff(); castAskFrames?.drop(); castAskFrames = null
        // the user, 2026-11-13: "if they press yes on the prompt to try the tap to cast bar, we should open them to the spell bind room
        // that you are making so they can add spells to it": the Controller room on its Cast bar tab, slot 1 (once Home is free)
        if (yes) { castTabOwed = true; controllerView.openOnCastBar(); ui.removeCallbacks(castTabTick); ui.postDelayed(castTabTick, 600L) }
    }

    /** the Yes's Controller room open, still owed (CastBarAsk.openTabNow) */
    private var castTabOwed = false
    private val castTabTick = object : Runnable {
        override fun run() {
            if (shell.hostGone() || !castTabOwed) return
            val busy = setupView.visibility == View.VISIBLE || setupWaiting != null || startView.visibility == View.VISIBLE ||
                loginPrompt.isOpen || newSpellAsking != 0 || newSpellBinding != 0 || interactView.visibility == View.VISIBLE ||
                dialogView.visibility == View.VISIBLE || spellsOverlay.visibility == View.VISIBLE
            if (CastBarAsk.openTabNow(castTabOwed, room == Room.HOME && !homeView.homeGuestWanted(), homeView.inFight(),
                    CastBarAsk.capFree(capHolder, false), busy)) {
                castTabOwed = false
                Log.i(HomeView.TAG, "cast bar ask: Yes - the Controller room on its Cast bar tab")
                controllerView.openOnCastBar()
                pickRoom(Room.CONTROLLER)
            } else ui.postDelayed(this, 1_000L)
        }
    }

    private val CAST_ASK_PIC_H = 400f

    /** once a second until the question has been answered (or was never owed); then it stops for good */
    private val castAskTick = object : Runnable {
        override fun run() {
            if (shell.hostGone()) return
            HomeSettings.init(this@CompanionHost)
            if (HomeSettings.castBarAsked && !castAskOpen) return
            runCatching { castBarAsk() }.onFailure { Log.w(HomeView.TAG, "cast bar ask failed", it) }
            ui.postDelayed(this, 1_000L)
        }
    }

    /** the Bind box closed (kept, cancelled, refused): back Home, where the next prompt follows */
    private fun newSpellBindDone() {
        if (newSpellBinding == 0) return
        newSpellBinding = 0
        newSpellConfirm = 0
        ui.postDelayed({ if (room == Room.SPELLBOOK) show(Room.HOME); ui.postDelayed({ newSpellAsk() }, 700L) }, 900L)
    }
    /** issue #136 (2026-11-11): the game run ([LauncherHooks.gameStartSec]) whose first word each character's kept choice was checked
     *  against ([DpadChoice]: once a run the app's choice wins over a different add-on word), and when its choice was last sent */
    private val dpadRun = HashMap<String, Long>()
    private val dpadSentAt = HashMap<String, Long>()

    /** this character's choice in the app (both games share it: each game's add-on keeps its own) */
    private fun dpadKey(d: PadMapModel.Dpad): String? =
        if (d.name.isNullOrEmpty()) null else "choice_${d.name}-${d.realm ?: ""}"

    /**
     * ONCE PER CHARACTER, after the first login (the user's pick): "Use DS Forever D-pad controls?". A game whose add-on already has the
     * answer is followed (and the app remembers it); a game whose add-on has none gets the app's remembered choice, silently, once a
     * run (the user answered in the other game); only a character never asked anywhere sees the prompt. Never over a fight, the
     * setup's prompt or the start screen.
     */
    private fun dpadAsk(d: PadMapModel.Dpad) {
        val key = dpadKey(d) ?: return
        if (d.colOn == null || d.colOff == null) return
        val prefs = getSharedPreferences("dpad", MODE_PRIVATE)
        // b139 (the user, 2026-10-07: "I think that I want to have them on by default and then still have the prompt when its able to. for new characters specifically it would be great if we could keep the loading screen during the cutscene"): an unasked character gets the toolbar ON by default and the prompt still comes; the add-on's "want" that OUR default
        // tap set is not an answer while the prompt is owed ("owed_<key>", kept across restarts)
        val owed = prefs.getBoolean("owed_$key", false)
        // issue #136 (the player, 2026-11-11: "Cannot disable DsWoW toolbar from settings"): the decision is DpadChoice's - an explicit
        // choice stays pending (re-sent, the prompt never runs) until the add-on says it; at a run's first word the app's choice wins
        val stored = if (prefs.contains(key)) prefs.getBoolean(key, false) else null
        val pending = prefs.getBoolean("pend_$key", false)
        val run = LauncherHooks.gameStartSec
        val now = SystemClock.elapsedRealtime()
        val act = DpadChoice.decide(d.want, stored, pending, owed, dpadRun[key] != run, homeView.inFight(),
            dpadSentAt[key]?.let { now - it })
        if (act != DpadChoice.Act.Wait && act != DpadChoice.Act.Ask) dpadRun[key] = run
        when (act) {
            DpadChoice.Act.None, DpadChoice.Act.Wait -> return
            DpadChoice.Act.Confirm -> {
                // issue #136 review (2026-11-11): also a pre-update Settings switch's owed flag (DpadChoice.held)
                prefs.edit().remove("pend_$key").remove("owed_$key").apply(); dpadSentAt.remove(key)
                Log.i(HomeView.TAG, "dpad: the game has the choice $stored for $key"); return
            }
            is DpadChoice.Act.Adopt -> { prefs.edit().putBoolean(key, act.want).apply(); Log.i(HomeView.TAG, "dpad: the game's choice ${act.want} kept for $key"); return }
            is DpadChoice.Act.Send -> {
                dpadSentAt[key] = now
                prefs.edit().putBoolean("pend_$key", true).remove("owed_$key").apply()
                Log.i(HomeView.TAG, "dpad: send the choice ${act.on} for $key (${act.why})")
                dpadTap(d, act.on); return
            }
            DpadChoice.Act.Ask -> {}
        }
        if (setupView.visibility == View.VISIBLE || (startView.visibility == View.VISIBLE && !setupLoading) || homeView.inFight() || setupWaiting != null) return
        if (!owed) prefs.edit().putBoolean("owed_$key", true).apply()
        // b133 (the user: "The actual text prompt for the dpad controls needs to be updated"; the user's pick "Pairs, 2 lines"): today's
        // DS WoW toolbar - the plain D-pad left Chat, right Map, up the radial, down the Home bar, in fights too
        val b = arrayListOf("Left: Chat     Right: Map", "Up: the radial     Down: the Home bar", "Works in fights too")
        Log.i(HomeView.TAG, "dpad: ask $key")
        val show = show@{
            // issue #136: answered meanwhile (the Settings row): no default ON tap, no prompt
            if (!prefs.getBoolean("owed_$key", false)) { Log.i(HomeView.TAG, "dpad prompt: dropped (already chosen for $key)"); setupPadOff(); return@show }
            // b139: ON by default the moment the game takes taps again, then the prompt asks to keep it
            spellsTap(d.colOn)
            setupLoadingDone()
            setupView.ask(CompanionSetup.Prompt("Keep the DS WoW toolbar on your D-pad?", b,
                "No turns it off (your own D-pad). Change it any time in the Controller room (DS tab)."), "Yes", "No",
                onYes = { dpadChoose(d, true, "the prompt's Yes"); setupView.close(); setupPadOff() },
                onNo = { dpadChoose(d, false, "the prompt's No"); setupView.close(); setupPadOff() })
        }
        // b137 (the user's pick "(a) Show once ready": "if I let the cutscene play out then it takes a while"): on Home the prompt WAITS,
        // unseen, while the app asks the game for the controller; it shows the moment the game hands it over (right after the intro
        // cinematic, or at once when it is skipped), A / B working from its first frame. Where Home cannot hold, it shows at once,
        // touch only, as before
        // b138 (the user on b137: "I made a new charactor and did not get the prompt after the cutscene at all"; measured: "waits"
        // logged, NOT ONE hold asked - the waiting prompt was stored only after the first ask, which saw none and stopped): stored
        // FIRST. And it never vanishes: no hold within SETUP_SHOW_ANYWAY_MS (or a refusal) shows it anyway, touch first, A / B when
        // the hold lands
        setupWaiting = show
        val wait = ++setupWaitId
        if (setupPadOn()) {
            Log.i(HomeView.TAG, "dpad prompt: waits for the game's controller")
            // b139: DS Forever's loading screen stays up while the game ignores us (a new character's intro cutscene)
            setupLoading = true
            startView.line = "Loading your character"
            startView.visibility = View.VISIBLE
            ui.postDelayed({ if (wait == setupWaitId) setupShowAnyway("no hold after ${SETUP_SHOW_ANYWAY_MS / 1000} s") }, SETUP_SHOW_ANYWAY_MS)
        } else { setupWaiting = null; show() }
    }

    /** b139: the start screen is up for the waiting D-pad prompt (the first documents do not take it down meanwhile) */
    private var setupLoading = false
    private fun setupLoadingDone() {
        if (!setupLoading) return
        setupLoading = false
        if (reloadAt == 0L) startView.visibility = View.GONE
    }

    /** b137: the D-pad prompt that shows once the game hands Home the controller (null = none waiting) */
    private var setupWaiting: (() -> Unit)? = null
    private var setupWaitId = 0          // b138: a show-anyway timer acts only for the prompt it was set for

    /** b138: the waiting prompt shows now, touch only; the asks go on and A / B arrive with the hold */
    private fun setupShowAnyway(why: String) {
        val w = setupWaiting ?: return
        setupWaiting = null
        Log.i(HomeView.TAG, "dpad prompt: shown without the controller ($why)")
        w()
        setupView.padOn = setupHeld          // the ask loop already running goes on; A / B arrive with the hold
    }

    /**
     * b130 (the user, 2026-10-07: "When loading in on a new charactor can we put button glyphs on the setup prompt? A for yes b for no?"): while the D-pad prompt shows ON HOME with nothing of Home's holding the controller, the app holds the guest "home",
     * so A / B press the prompt's buttons and never reach the game. Anywhere else the prompt stays touch-only (a room holds its own).
     */
    private var setupPad = false
    /** b132 (the user on b131: "The prompt has the glyphs but a and b isn't actually working"; measured: the ONE hold tap went out
     *  1.5 s after a new character's first document and was never answered - the load swallowed it): the hold is asked again every
     *  second until the game answers (SETUP_HOLD_TRIES at most), and the A / B glyphs show only once it holds, so a press never also
     *  reaches the game (A = Jump) */
    private var setupHeld = false
    private var setupTry = 0
    /** true when Home can hold the controller for the prompt and has started asking */
    private fun setupPadOn(): Boolean {
        if (room != Room.HOME || homeView.homeGuestWanted() || capHolder.isNotEmpty()) return false
        setupPad = true; setupHeld = false; setupTry = 0
        setupView.padOn = false
        setupAskHold()
        return true
    }
    private fun setupAskHold() {
        if (!setupPad || setupHeld || (setupWaiting == null && setupView.visibility != View.VISIBLE)) return
        // b137: never take the controller from a room the player opened meanwhile (or a module of Home's): skip, ask again later
        if (room != Room.HOME || homeView.homeGuestWanted()) { ui.postDelayed({ setupAskHold() }, 2_000L); return }
        val sent = guestTap(GuestPad.cells(questsPad, "home", hold = true))
        Log.i(HomeView.TAG, "dpad prompt: hold ${if (sent) "asked" else "NOT sent"} (try ${setupTry + 1})")
        // b136 (the user on b135: "The new setup prompt is missing the button glyphs and inputs"; measured: a new character's first
        // load ignored every pad tap for ~85 s, 21:22:55 -> 21:24:19, and the 15 s of asks ran out): asked for as long as the prompt
        // is open - every second for the first SETUP_HOLD_TRIES, then every 2 s
        ui.postDelayed({ setupAskHold() }, if (++setupTry < SETUP_HOLD_TRIES) 1_000L else 2_000L)
    }
    /** the game's answer for the guest "home" while the prompt wants it */
    private fun setupHoldAck(msg: JSONObject) {
        if (!setupPad || msg.optString("op") != "hold") return
        setupHeld = msg.optBoolean("ok") && msg.optString("why") != "pending"
        // b137: the waiting prompt shows now, its A / B live from the first frame; b138: a refusal shows it too (touch first)
        if (setupHeld) setupWaiting?.let { setupWaiting = null; it() }
        else if (newSpellAsking != 0) { newSpellRetry("the game said no: ${msg.optString("why")}"); return }
        else setupShowAnyway("the game said no: ${msg.optString("why")}")
        setupView.padOn = setupHeld
        Log.i(HomeView.TAG, "dpad prompt: ${if (setupHeld) "held - A / B press it" else "hold not given (${msg.optString("why")})"}")
    }
    private fun setupPadOff() {
        setupWaiting = null
        setupLoadingDone()
        if (!setupPad) return
        setupPad = false; setupHeld = false; setupView.padOn = false
        if (!homeView.homeOwnGuestWanted()) guestTap(GuestPad.cells(questsPad, "home", hold = false))
        Log.i(HomeView.TAG, "dpad prompt: free")
    }

    /** the Settings row: true = the router mode on (cols.on), false = fully off (cols.off); refused in a fight (one red pulse) */
    private fun dpadSet(on: Boolean) {
        val d = lastDpad ?: run { Log.w(HomeView.TAG, "dpad: the Settings switch to $on not kept (no toolbar word from the game yet)"); return }
        dpadChoose(d, on, "the Settings row")
    }

    /**
     * issue #136 (2026-11-11): THE PLAYER'S CHOICE (the Settings row, the prompt's Yes / No): kept, the owed prompt cleared (its default
     * ON tap no longer runs), PENDING until the add-on's padmap says it ([DpadChoice]: re-sent then, and at the next game run), and sent
     * now. A refused tap (the game closed or not taking taps) is logged; the choice is not lost.
     */
    private fun dpadChoose(d: PadMapModel.Dpad, on: Boolean, from: String, tap: Boolean = true) {
        val key = dpadKey(d)
        if (key == null) Log.w(HomeView.TAG, "dpad: $from chose $on but no character is known; sent, not kept")
        else {
            getSharedPreferences("dpad", MODE_PRIVATE).edit().putBoolean(key, on).putBoolean("pend_$key", true).remove("owed_$key").apply()
            dpadSentAt[key] = SystemClock.elapsedRealtime()
            Log.i(HomeView.TAG, "dpad: $from chose $on for $key (kept until the game says it)")
        }
        settings.homeSection.dpadChanged()
        if (tap) dpadTap(d, on)
    }

    /** the toolbar word as the Settings row shows it: the pending choice over the add-on's ([DpadChoice.shown]) */
    private fun dpadShown(d: PadMapModel.Dpad): PadMapModel.Dpad {
        val key = dpadKey(d) ?: return d
        val prefs = getSharedPreferences("dpad", MODE_PRIVATE)
        val w = DpadChoice.shown(d.want, if (prefs.contains(key)) prefs.getBoolean(key, false) else null, prefs.getBoolean("pend_$key", false),
            prefs.getBoolean("owed_$key", false))
        return if (w == d.want) d else d.copy(want = w)
    }

    /** one toolbar tap (Book b15's dpad-on / dpad-off); a refusal is logged (issue #136: it was silent) */
    private fun dpadTap(d: PadMapModel.Dpad, on: Boolean) {
        val col = (if (on) d.colOn else d.colOff) ?: run { Log.w(HomeView.TAG, "dpad: no ${if (on) "dpad-on" else "dpad-off"} cell (an older Spells add-on)"); return }
        TapBridge.tap(this, spellsPad, "spells", col) { why -> Log.w(HomeView.TAG, "dpad: the ${if (on) "on" else "off"} tap did not reach the game ($why); kept, sent again when the game takes it") }
    }

    /** the Controller tab's cells (spells' own rows `talents` 11-39 and `talentn` 36 / 37), in order: picks, then at most ONE action */
    private fun padTabTap(cells: List<Pair<String, Int>>): Boolean = TapBridge.taps(this, talentsPad ?: spellsPad, cells)

    /**
     * THE WATCH LIST BACK after an unclean end ([QuestWatchKeep]; the user's "Restore silently"): the game's own "watch" action for each
     * quest (Quests' qpick + qact WATCH, the same pair the Quests room's Track sends), one every [WATCH_GAP_MS], never in a fight.
     */
    private fun restoreWatches(back: List<QuestWatchKeep.Q>) {
        Log.i(HomeView.TAG, "quest watch: restoring ${back.size} after an unclean end: ${back.joinToString(",") { it.id.toString() }}")
        fun step(i: Int) {
            if (i >= back.size) { Log.i(HomeView.TAG, "quest watch: restore sent"); return }
            if (homeView.inFight()) { Log.i(HomeView.TAG, "quest watch: a fight: the rest (${back.size - i}) not sent"); return }
            val q = back[i]
            val cells = QuestPad.cells(questsPad, QuestPad.Act.WATCH, q.slot)
            val ok = cells != null && TapBridge.taps(this, questsPad, cells) { why -> Log.i(HomeView.TAG, "quest watch: ${q.id}: tap failed: $why") }
            Log.i(HomeView.TAG, "quest watch: ${q.id} ${if (ok) "sent" else "not sent"}")
            ui.postDelayed({ step(i + 1) }, WATCH_GAP_MS)
        }
        step(0)
    }

    /**
     * A CLEAN EXIT FIRST ([CleanExit]; the user's "Both" ruling after test20): the game's own Exit Game cell (Spells' secure gamemenu
     * row: "/quit", which writes SavedVariables), then wait for the game to go; [done] says whether the old close is still needed.
     */
    // THE GAME'S PIN ON THE ROUTE (b-route-waypoint, the user's pick): RouteWaypoint decides the taps of Map.lua's row "uwp"
    private val routeWp = RouteWaypoint()
    private fun routePinTick() {
        polestarTick()
        // 2026-11-04 (the user: "it happens immidiatly when I track a quest thats exactly the cause", then "No pin at all"): every pin
        // move is up to 21 mouse clicks on the top screen, and each one shows the game's cursor. The app never moves the game's pin;
        // the game's own super-track points at the quest, the bottom screen's map still draws the route
        if (!RouteWaypoint.PIN_ON) return pinState("none: off (the game's pin is never moved: each move is mouse clicks)")
        val pad = questsPad ?: return pinState("none: no quests pad yet")
        if (pad.optJSONObject("rows")?.has(RouteWaypoint.ROW) != true) return pinState("none: the add-on has no \"${RouteWaypoint.ROW}\" row (an older add-on, or the pad's row budget full)")
        val want = mapView.routePin(); pinState(mapView.pinWhy)
        val cols = routeWp.tick(want, SystemClock.elapsedRealtime(), mapView.pinMore, mapView.pinRouting, quest = mapView.gameFocusQuest())
        if (cols.isEmpty()) return
        Log.i("WCMAP", "route pin: tap $cols (${mapView.pinWhy})")
        TapBridge.taps(this, pad, cols.map { RouteWaypoint.ROW to it }) { why -> Log.i("WCMAP", "route pin: tap failed: $why") }
    }

    // why the game's pin is or isn't set, logged once per change (test13: no checkpoint marker on the compass, and no line said why)
    private var pinStateLast = ""
    private var pinStateAt = 0L
    /** the pin's why, logged when it CHANGES (its "N yd ahead" aside: test70 logged it 4-5 times a second while walking) or every 5 s */
    private fun pinState(why: String) {
        val key = why.replace(Regex(""", \d+ yd ahead"""), "")
        val now = SystemClock.elapsedRealtime()
        if (key != pinStateLast || now - pinStateAt >= 5_000L) { pinStateLast = key; pinStateAt = now; Log.i("WCMAP", "route pin: $why") }
    }

    /** pk3: when a Skip / Previous was last picked on the pause wheel (uptime ms, 0 none): its answer's refusal goes on the wheel's line */
    private var psWheelAt = 0L

    /** pk1: one Skip ([prev] false) or Previous on Map.lua's "pnav" row (38 then 10 / 11; the one tap worker keeps it apart from
     *  PolestarNav's batches). False = not sent (an older add-on, the game not live) */
    private fun polestarSkipTap(prev: Boolean): Boolean {
        val pad = questsPad ?: return false
        if (pad.optJSONObject("rows")?.has(PolestarNav.ROW) != true) return false
        val cols = PolestarSkip.cols(prev)
        Log.i("WCMAP", "polestar ${if (prev) "previous" else "skip"}: tap $cols")
        return TapBridge.tapsSpaced(this, pad, cols.map { PolestarNav.ROW to it }, 120L) { why -> Log.i("WCMAP", "polestar skip: tap failed: $why") }
    }

    // POLESTAR AS THE ROUTER (b-route-polestar): PolestarNav decides the taps of Map.lua's row "pnav" (only while the setting is Polestar;
    // switching away cancels its guidance)
    private fun polestarTick() {
        val pad = questsPad ?: return
        if (pad.optJSONObject("rows")?.has(PolestarNav.ROW) != true) return          // an older add-on: no Polestar bridge
        // pn4 (the user, 2026-11-11): the add-on is told while the light blue Polestar row holds (its compass leads to Polestar's goal);
        // int/1429: the same word is ps4's "row picked" (PadMap m132's starter item on Y): ONE sender, this tick (PolestarPickTell folded in)
        val cols = mapView.polestar.tick(mapView.polestarGoal(), SystemClock.elapsedRealtime(), mapView.polestarHolds())
        if (cols.isEmpty()) {
            // pq1: Polestar's automation, when our choice differs from the one the add-on holds (its own batch: never inside a GO's digits)
            val q = PolestarQol.taps(GameUi.character, SystemClock.elapsedRealtime())
            if (q.isNotEmpty()) {
                Log.i("WCMAP", "polestar automation: tap $q")
                TapBridge.tapsSpaced(this, pad, q.map { PolestarNav.ROW to it }, 120L) { why -> Log.i("WCMAP", "polestar automation: tap failed: $why") }
            }
            return
        }
        Log.i("WCMAP", "polestar: tap $cols")
        // spaced (the user, 2026-11-11: the Polestar path never showed): a cell swallows a second press within 0.25 s, so a target like
        // "1, 34, 1" lost its second digit, the add-on never had all seven and never asked Polestar (RouteWaypoint spaces its digits too)
        TapBridge.tapsSpaced(this, pad, cols.map { PolestarNav.ROW to it }, 120L) { why ->
            mapView.polestar.lost(); Log.i("WCMAP", "polestar: tap failed: $why") }       // (ps4: the row's word said again)
    }

    private fun cleanExit(why: String, done: (Boolean) -> Unit) {
        // every route line is logged AND kept, so the safe close's own "done" line can repeat it (tracking: the WCSETUP lines rolled
        // out of logcat before they were read)
        fun say(line: String) { Log.i("WCSETUP", line); LauncherHooks.lastCleanExit = line }
        val route = CleanExit.route(lastAddonAt, SystemClock.elapsedRealtime(), homeView.inFight(), CleanExit.quitCol(lastGameMenu))
        if (route is CleanExit.Route.Force) { say("clean exit ($why): ${route.why}: the old close"); done(true); return }
        val col = (route as CleanExit.Route.Quit).col
        val sent = TapBridge.tap(this, questsPad, GameMenuModel.ROW, col) { r -> say("clean exit ($why): tap refused ($r)") }
        if (!sent) { say("clean exit ($why): the Exit Game tap was not sent: the old close"); done(true); return }
        say("clean exit ($why): the game's own Exit Game asked (its save is written as it exits)")
        val t0 = SystemClock.elapsedRealtime()
        var retapped = false
        cleanExitWaiting = true; lastCountLogged = ""; gmRefused = null
        var lastStatusS = 0L
        val poll = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val s = (now - t0) / 1000
                if (!LauncherHooks.gameUp()) {
                    cleanExitWaiting = false
                    say("clean exit ($why): the game exited by itself after $s s")
                    QuestWatchKeep.cleanEnd()                                   // its state is written: nothing to restore next time
                    done(false); return
                }
                val acked = gmAckAt >= t0 && gmAckCol == col
                // the game's own QUIT countdown, as Radial r12's "gamemenu" documents count it (null = none showing)
                val gm = lastGameMenu
                val countLeft = if (gm != null && gm.optString("count") == "quit" && gm.has("left")) gm.optInt("left") else null
                // every 5 s, where the wait stands: whether the game still speaks (a paused game goes quiet), the fight flag
                if (s >= lastStatusS + 5) {
                    lastStatusS = s
                    Log.i("WCSETUP", "clean exit ($why): waiting $s s: exit cell ${if (acked) "answered" else "silent"}, countdown " +
                        "${countLeft?.let { "$it s left" } ?: "none seen"}, the game last spoke ${(now - lastAddonAt) / 1000} s ago, " +
                        "fight ${homeView.inFight()}")
                }
                when (val w = CleanExit.waitAfterTap(now - t0, acked, retapped, countLeft, gmRefused)) {
                    CleanExit.Wait.Retap -> {
                        retapped = true
                        val again = TapBridge.tap(this@CompanionHost, questsPad, GameMenuModel.ROW, col) { }
                        say("clean exit ($why): no answer from the Exit cell after $s s: tapped again (${if (again) "sent" else "not sent"})")
                        ui.postDelayed(this, 500L)
                    }
                    is CleanExit.Wait.GiveUp -> { cleanExitWaiting = false; say("clean exit ($why): still running after $s s: ${w.why}: the old close"); done(true) }
                    CleanExit.Wait.On -> ui.postDelayed(this, 500L)
                }
            }
        }
        ui.postDelayed(poll, 500L)
    }

    companion object {
        /** between two restored watches: the add-on's pick serves one action for 2 s, a pair is two taps */
        private const val WATCH_GAP_MS = 700L
        /** the router's "pause" after the app's own Start read is the same press (the doc arrives ~0.1-0.4 s later) */
        const val START_DEDUPE_MS = 800L
        const val SWEEP_ACTION = "app.dsforever.companion.KEYSWEEP"
        @Volatile private var sweepRegistered = false
        /** a holder seen with its room hidden this long is freed (the one rule for every room; see freeHidden) */
        private const val HIDDEN_GRACE = 3_000L
        /** combat_nav Step 3: PadOwnerSync's look while a fight lasts (a surface opening or closing needs no hook of its own) */
        private const val PAD_OWNER_TICK_MS = 150L
        /** a room left by the game's own B: its leave taps wait this long for the release (bPressed; the echo lands well within 1 s) */
        private const val B_LEAVE_MS = 1_000L
        private const val SETUP_SHOW_ANYWAY_MS = 120_000L   // b138: a waiting prompt shows by now at the latest (a new character's intro ran ~85 s)
        private const val SETUP_HOLD_TRIES = 15      // b132: the D-pad prompt's first asks a second apart; b136: then every 2 s while open
        private const val GF_RELEASE_CHECK_MS = 1_000L
        /** after Interact refuses "release npc", this long before another try */
        private const val NPC_BACKOFF = 30_000L
        /** Social's hold unconfirmed this long (the holder the interact documents report is not "social") = sent again */
        /** the Chat room's hold unconfirmed this long = sent again ([chatHold]); a needless second hold is harmless */
        /** the Spellbook's pad row: the addon claims these two columns first (AGENT_LOG "Pad owner -> spells") */

        /** moved to [GameInput]; kept under this name for the rooms that read it */
        val KEYMAP: Map<String, Int> get() = GameInput.KEYMAP
    }
}
